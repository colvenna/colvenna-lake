package identity

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"regexp"
	"sync"
	"time"
)

// keyID is the form of the load balancer's key IDs (a UUID), so a key ID never shapes the URL.
var keyID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// ALBKeys fetches the load balancer's public keys, one per key ID, from the region's endpoint
// (https://public-keys.auth.elb.<region>.amazonaws.com/<key-id>, a PEM P-256 key) and keeps
// them: a key ID always names the same key. A key ID the endpoint says it does not have is not
// asked for again for a minute; a fetch that failed otherwise (a timeout, a 5xx, a cancelled
// request) is tried again on the next request, so one failure does not lock users out.
type ALBKeys struct {
	BaseURL string
	Client  *http.Client
	Now     func() time.Time

	mu     sync.Mutex
	keys   map[string]*ecdsa.PublicKey
	failed map[string]time.Time
}

// Key is the key of that ID.
func (k *ALBKeys) Key(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
	if !keyID.MatchString(kid) {
		return nil, fmt.Errorf("key ID %q is not a UUID", kid)
	}
	k.mu.Lock()
	key, failedAt := k.keys[kid], k.failed[kid]
	k.mu.Unlock()
	if key != nil {
		return key, nil
	}
	if !failedAt.IsZero() && k.Now().Sub(failedAt) < time.Minute {
		return nil, fmt.Errorf("load balancer key %q: not found a moment ago", kid)
	}
	key, err := k.fetch(ctx, kid)
	k.mu.Lock()
	defer k.mu.Unlock()
	if err != nil {
		if errors.Is(err, errNoSuchKey) {
			if k.failed == nil || len(k.failed) > 1000 {
				k.failed = map[string]time.Time{}
			}
			k.failed[kid] = k.Now()
		}
		return nil, err
	}
	if k.keys == nil {
		k.keys = map[string]*ecdsa.PublicKey{}
	}
	k.keys[kid] = key
	return key, nil
}

func (k *ALBKeys) fetch(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
	body, err := fetch(ctx, k.Client, k.BaseURL+"/"+kid)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(body)
	if block == nil {
		return nil, errors.New("load balancer key: not PEM")
	}
	parsed, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("load balancer key: %w", err)
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, errors.New("load balancer key: not a P-256 key")
	}
	return key, nil
}

// CognitoKeys holds the user pool's signing keys from its JWKS (<issuer>/.well-known/jwks.json).
// An unknown key ID reloads the set, at most once a minute: Cognito rotates keys rarely.
type CognitoKeys struct {
	URL    string
	Client *http.Client
	Now    func() time.Time

	mu     sync.Mutex
	keys   map[string]*rsa.PublicKey
	loaded time.Time
}

// Key is the key of that ID.
func (k *CognitoKeys) Key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if key := k.keys[kid]; key != nil {
		return key, nil
	}
	if !k.loaded.IsZero() && k.Now().Sub(k.loaded) < time.Minute {
		return nil, fmt.Errorf("no user pool key %q", kid)
	}
	body, err := fetch(ctx, k.Client, k.URL)
	if err != nil {
		return nil, err
	}
	keys, err := parseJWKS(body)
	if err != nil {
		return nil, err
	}
	k.keys, k.loaded = keys, k.Now()
	if key := k.keys[kid]; key != nil {
		return key, nil
	}
	return nil, fmt.Errorf("no user pool key %q", kid)
}

func parseJWKS(body []byte) (map[string]*rsa.PublicKey, error) {
	var set struct {
		Keys []struct {
			Kid string `json:"kid"`
			Kty string `json:"kty"`
			Use string `json:"use"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.Unmarshal(body, &set); err != nil {
		return nil, fmt.Errorf("user pool keys: %w", err)
	}
	keys := map[string]*rsa.PublicKey{}
	for _, jwk := range set.Keys {
		if jwk.Kty != "RSA" || (jwk.Use != "" && jwk.Use != "sig") {
			continue
		}
		n, errN := base64.RawURLEncoding.DecodeString(jwk.N)
		e, errE := base64.RawURLEncoding.DecodeString(jwk.E)
		if errN != nil || errE != nil || len(e) == 0 || len(e) > 4 {
			return nil, fmt.Errorf("user pool key %q: bad modulus or exponent", jwk.Kid)
		}
		keys[jwk.Kid] = &rsa.PublicKey{
			N: new(big.Int).SetBytes(n),
			E: int(new(big.Int).SetBytes(e).Int64()),
		}
	}
	if len(keys) == 0 {
		return nil, errors.New("user pool keys: no RSA signing key")
	}
	return keys, nil
}

// errNoSuchKey is the key endpoint's answer for a key ID it does not have (403 or 404).
var errNoSuchKey = errors.New("no such key")

func fetch(ctx context.Context, client *http.Client, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("GET %s: %s: %w", url, resp.Status, errNoSuchKey)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: %s", url, resp.Status)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 64<<10))
}
