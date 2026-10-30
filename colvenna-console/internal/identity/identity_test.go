package identity

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const (
	albARN   = "arn:aws:elasticloadbalancing:us-west-2:111122223333:loadbalancer/app/entry/0123456789abcdef"
	issuer   = "https://cognito-idp.us-west-2.amazonaws.com/us-west-2_TEST"
	clientID = "client123"
	albKID   = "6a1b2c3d-0000-4000-8000-000000000001"
	poolKID  = "pool-key-1"
)

var now = time.Unix(1_800_000_000, 0)

var entryRoles = RoleGroups{"colvenna-admin": Admin, "colvenna-operator": Operator, "colvenna-bi": BI}

type fixture struct {
	albKey  *ecdsa.PrivateKey
	poolKey *rsa.PrivateKey
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	albKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	poolKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return &fixture{albKey: albKey, poolKey: poolKey}
}

// padded is base64url with '=' padding, as the load balancer writes its segments.
func padded(b []byte) string { return base64.URLEncoding.EncodeToString(b) }

func raw(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// data is the load balancer's x-amzn-oidc-data for the header and payload, signed with key.
func (f *fixture) data(header, payload map[string]any, key *ecdsa.PrivateKey) string {
	signed := padded(mustJSON(header)) + "." + padded(mustJSON(payload))
	digest := sha256.Sum256([]byte(signed))
	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		panic(err)
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return signed + "." + padded(sig)
}

func (f *fixture) dataHeader() map[string]any {
	return map[string]any{
		"alg": "ES256", "kid": albKID, "signer": albARN, "iss": issuer, "client": clientID,
		"exp": now.Add(time.Minute).Unix(),
	}
}

// access is a Cognito access token for the claims, signed with key.
func (f *fixture) access(claims map[string]any, key *rsa.PrivateKey) string {
	signed := raw(mustJSON(map[string]any{"alg": "RS256", "kid": poolKID})) + "." + raw(mustJSON(claims))
	digest := sha256.Sum256([]byte(signed))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		panic(err)
	}
	return signed + "." + raw(sig)
}

func (f *fixture) accessClaims() map[string]any {
	return map[string]any{
		"sub": "user-1", "username": "alice", "iss": issuer, "client_id": clientID,
		"token_use": "access", "exp": now.Add(5 * time.Minute).Unix(), "auth_time": now.Add(-time.Hour).Unix(),
		"cognito:groups": []string{"colvenna-operator", "colvenna-admin", "unrelated"},
	}
}

type fixedALB struct{ key *ecdsa.PublicKey }

func (k fixedALB) Key(_ context.Context, kid string) (*ecdsa.PublicKey, error) {
	if kid != albKID {
		return nil, errors.New("unknown key")
	}
	return k.key, nil
}

type fixedPool struct{ key *rsa.PublicKey }

func (k fixedPool) Key(_ context.Context, kid string) (*rsa.PublicKey, error) {
	if kid != poolKID {
		return nil, errors.New("unknown key")
	}
	return k.key, nil
}

func (f *fixture) verifier() *Verifier {
	return &Verifier{
		LoadBalancerARN: albARN, Issuer: issuer, ClientID: clientID, RoleGroups: entryRoles,
		ALB:     fixedALB{&f.albKey.PublicKey},
		Cognito: fixedPool{&f.poolKey.PublicKey},
		Now:     func() time.Time { return now },
	}
}

func request(data, access string) *http.Request {
	r := httptest.NewRequest(http.MethodGet, "https://console.example/", nil)
	if data != "" {
		r.Header.Set(DataHeader, data)
	}
	if access != "" {
		r.Header.Set(AccessTokenHeader, access)
	}
	return r
}

func TestVerifyAcceptsTheEntrysTokens(t *testing.T) {
	f := newFixture(t)
	data := f.data(f.dataHeader(), map[string]any{"sub": "user-1", "email": "a@example.com"}, f.albKey)
	user, err := f.verifier().Verify(request(data, f.access(f.accessClaims(), f.poolKey)))
	if err != nil {
		t.Fatal(err)
	}
	if user.Sub != "user-1" || user.Username != "alice" || user.Email != "a@example.com" {
		t.Fatalf("user %+v", user)
	}
	if !user.SignedIn.Equal(now.Add(-time.Hour)) {
		t.Fatalf("signed in %v", user.SignedIn)
	}
	if fmt.Sprint(user.Roles) != "[admin operator]" || !user.Has(Admin) || user.Has(BI) {
		t.Fatalf("roles %v", user.Roles)
	}
}

func TestVerifyRefuses(t *testing.T) {
	f := newFixture(t)
	other := newFixture(t)
	payload := map[string]any{"sub": "user-1"}
	goodData := f.data(f.dataHeader(), payload, f.albKey)
	goodAccess := f.access(f.accessClaims(), f.poolKey)
	withHeader := func(k string, v any) string {
		h := f.dataHeader()
		h[k] = v
		return f.data(h, payload, f.albKey)
	}
	withClaim := func(k string, v any) string {
		c := f.accessClaims()
		c[k] = v
		return f.access(c, f.poolKey)
	}
	tampered := strings.Split(goodData, ".")
	tampered[1] = padded(mustJSON(map[string]any{"sub": "user-2"}))
	none := padded(mustJSON(map[string]any{"alg": "none", "kid": albKID, "signer": albARN,
		"iss": issuer, "client": clientID, "exp": now.Add(time.Minute).Unix()})) + "." +
		padded(mustJSON(payload)) + "."
	cases := map[string]struct{ data, access string }{
		"another load balancer":        {withHeader("signer", "arn:aws:elasticloadbalancing:us-west-2:1:loadbalancer/app/x/1"), goodAccess},
		"another client in the claims": {withHeader("client", "other"), goodAccess},
		"another pool in the claims":   {withHeader("iss", "https://evil"), goodAccess},
		"expired claims":               {withHeader("exp", now.Add(-time.Hour).Unix()), goodAccess},
		"claims signed by another key": {f.data(f.dataHeader(), payload, other.albKey), goodAccess},
		"claims altered":               {strings.Join(tampered, "."), goodAccess},
		"unsigned claims":              {none, goodAccess},
		"claims without access token":  {goodData, ""},
		"access token without claims":  {"", goodAccess},
		"token from another pool key":  {goodData, f.access(f.accessClaims(), other.poolKey)},
		"token of another client":      {goodData, withClaim("client_id", "other")},
		"token of another pool":        {goodData, withClaim("iss", "https://evil")},
		"an ID token":                  {goodData, withClaim("token_use", "id")},
		"expired token":                {goodData, withClaim("exp", now.Add(-time.Hour).Unix())},
		"token of another user":        {goodData, withClaim("sub", "user-2")},
		"token without sign-in time":   {goodData, withClaim("auth_time", 0)},
		"garbage":                      {"a.b", "c"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			if user, err := f.verifier().Verify(request(c.data, c.access)); err == nil {
				t.Fatalf("accepted %+v", user)
			}
		})
	}
	if _, err := f.verifier().Verify(request("", "")); !errors.Is(err, ErrNoIdentity) {
		t.Fatalf("no headers: %v", err)
	}
}

func TestRolesComeOnlyFromTheEntrysGroups(t *testing.T) {
	if got := fmt.Sprint(entryRoles.Of([]string{"colvenna-bi", "admins", "colvenna-bi"})); got != "[bi]" {
		t.Fatal(got)
	}
	if got := entryRoles.Of(nil); got == nil || len(got) != 0 {
		t.Fatalf("%#v", got)
	}
	m, err := ParseRoleGroups(`{"colvenna-admin":"admin","colvenna-bi":"bi"}`)
	if err != nil || len(m) != 2 || m["colvenna-bi"] != BI {
		t.Fatalf("%v %v", m, err)
	}
	for _, bad := range []string{`{"colvenna-admin":"root"}`, `{}`, `[]`, ``} {
		if _, err := ParseRoleGroups(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestALBKeysFetchesOncePerKeyAndRefusesOtherIDs(t *testing.T) {
	f := newFixture(t)
	der, err := x509.MarshalPKIXPublicKey(&f.albKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	var down atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if down.Load() {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		if r.URL.Path != "/"+albKID {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		_ = pem.Encode(w, &pem.Block{Type: "PUBLIC KEY", Bytes: der})
	}))
	defer server.Close()
	clock := now
	keys := &ALBKeys{BaseURL: server.URL, Client: server.Client(), Now: func() time.Time { return clock }}
	// The endpoint failing is not an answer: the next request tries again.
	down.Store(true)
	if _, err := keys.Key(context.Background(), albKID); err == nil {
		t.Fatal("key from a failing endpoint")
	}
	down.Store(false)
	calls.Store(0)
	for range 2 {
		key, err := keys.Key(context.Background(), albKID)
		if err != nil || !key.Equal(&f.albKey.PublicKey) {
			t.Fatalf("%v %v", key, err)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("%d fetches", calls.Load())
	}
	for _, kid := range []string{"../etc/passwd", "6a1b2c3d-0000-4000-8000-00000000000Z", ""} {
		if _, err := keys.Key(context.Background(), kid); err == nil {
			t.Fatalf("accepted key ID %q", kid)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("a refused key ID was fetched")
	}
	// A key ID the endpoint does not know is fetched once, then not again within the minute.
	unknown := "6a1b2c3d-0000-4000-8000-0000000000ff"
	for range 3 {
		if _, err := keys.Key(context.Background(), unknown); err == nil {
			t.Fatal("unknown key accepted")
		}
	}
	if calls.Load() != 2 {
		t.Fatalf("%d fetches for an unknown key ID", calls.Load()-1)
	}
	clock = clock.Add(2 * time.Minute)
	_, _ = keys.Key(context.Background(), unknown)
	if calls.Load() != 3 {
		t.Fatalf("not retried after a minute: %d", calls.Load())
	}
}

func TestCognitoKeysReloadsAtMostOnceAMinute(t *testing.T) {
	f := newFixture(t)
	jwks := mustJSON(map[string]any{"keys": []map[string]any{{
		"kid": poolKID, "kty": "RSA", "use": "sig", "alg": "RS256",
		"n": raw(f.poolKey.N.Bytes()), "e": raw([]byte{1, 0, 1}),
	}}})
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		_, _ = w.Write(jwks)
	}))
	defer server.Close()
	clock := now
	keys := &CognitoKeys{URL: server.URL, Client: server.Client(), Now: func() time.Time { return clock }}
	key, err := keys.Key(context.Background(), poolKID)
	if err != nil || !key.Equal(&f.poolKey.PublicKey) {
		t.Fatalf("%v %v", key, err)
	}
	if _, err := keys.Key(context.Background(), "rotated"); err == nil {
		t.Fatal("unknown key accepted")
	}
	if calls.Load() != 1 {
		t.Fatalf("reloaded within the minute: %d", calls.Load())
	}
	clock = clock.Add(2 * time.Minute)
	if _, err := keys.Key(context.Background(), "rotated"); err == nil {
		t.Fatal("unknown key accepted")
	}
	if calls.Load() != 2 {
		t.Fatalf("not reloaded after a minute: %d", calls.Load())
	}
}

func TestParseTokenAcceptsPaddedAndUnpaddedSegments(t *testing.T) {
	for _, enc := range []func([]byte) string{padded, raw} {
		tok, err := parseToken(enc([]byte(`{"alg":"ES256"}`)) + "." + enc([]byte("{}")) + "." + enc([]byte("s")))
		if err != nil || tok.headerString("alg") != "ES256" {
			t.Fatalf("%v %v", tok, err)
		}
	}
}
