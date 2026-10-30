package identity

import (
	"context"
	"crypto/ecdsa"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"time"
)

// Role is what a user may do in the platform's web interfaces. The entry defines which user pool
// group grants which role (the entry stack's RoleGroups); the console only knows what each role is.
type Role string

const (
	// Admin administers the platform.
	Admin Role = "admin"
	// Operator runs the pipeline.
	Operator Role = "operator"
	// BI queries the lake through the BI interface.
	BI Role = "bi"
)

// roles are the known roles, in the order they are shown.
var roles = []Role{Admin, Operator, BI}

// Label is the role's name in the console.
func (r Role) Label() string {
	switch r {
	case Admin:
		return "平台管理员"
	case Operator:
		return "运维"
	case BI:
		return "BI 用户"
	}
	return string(r)
}

// RoleGroups maps the user pool's groups to roles. Other groups grant nothing.
type RoleGroups map[string]Role

// ParseRoleGroups reads the entry's mapping, a JSON object of group to role; every role must be
// known, so a typo cannot grant nothing silently or something unexpected.
func ParseRoleGroups(text string) (RoleGroups, error) {
	var m RoleGroups
	if err := json.Unmarshal([]byte(text), &m); err != nil {
		return nil, fmt.Errorf("role groups: %w", err)
	}
	if len(m) == 0 {
		return nil, errors.New("role groups: none")
	}
	for group, role := range m {
		if !slices.Contains(roles, role) {
			return nil, fmt.Errorf("role groups: %q grants unknown role %q", group, role)
		}
	}
	return m, nil
}

// Of are the roles the groups grant, in a fixed order.
func (m RoleGroups) Of(groups []string) []Role {
	granted := []Role{}
	for _, r := range roles {
		for _, g := range groups {
			if m[g] == r {
				granted = append(granted, r)
				break
			}
		}
	}
	return granted
}

// User is a signed-in user, as the request's tokens prove.
type User struct {
	Sub      string   `json:"sub"`
	Username string   `json:"username"`
	Email    string   `json:"email,omitempty"`
	Groups   []string `json:"groups"`
	Roles    []Role   `json:"roles"`
	// SignedIn is when the user signed in: one value per sign-in, kept across token refreshes.
	SignedIn time.Time `json:"signedIn"`
	// Expires is when the access token expires; the load balancer refreshes it then.
	Expires time.Time `json:"expires"`
}

// Has is whether the user holds the role.
func (u User) Has(r Role) bool { return slices.Contains(u.Roles, r) }

// The request headers the load balancer sets after it signs a user in.
const (
	DataHeader        = "X-Amzn-Oidc-Data"
	AccessTokenHeader = "X-Amzn-Oidc-Accesstoken"
)

// ErrNoIdentity is a request without the load balancer's headers: it did not come through the
// entry's sign-in.
var ErrNoIdentity = errors.New("no identity headers")

// clockSkew is how far past expiry a token still counts, for clocks that disagree.
const clockSkew = 30 * time.Second

// Verifier checks the tokens of one load balancer, user pool and client.
type Verifier struct {
	LoadBalancerARN string
	RoleGroups      RoleGroups
	// Issuer is the user pool, https://cognito-idp.<region>.amazonaws.com/<pool-id>.
	Issuer   string
	ClientID string
	ALB      interface {
		Key(ctx context.Context, kid string) (*ecdsa.PublicKey, error)
	}
	Cognito interface {
		Key(ctx context.Context, kid string) (*rsa.PublicKey, error)
	}
	Now func() time.Time
}

// Verify is the request's user, or why the request is not a user's.
func (v *Verifier) Verify(r *http.Request) (User, error) {
	data, access := r.Header.Get(DataHeader), r.Header.Get(AccessTokenHeader)
	if data == "" && access == "" {
		return User{}, ErrNoIdentity
	}
	claims, err := v.verifyData(r.Context(), data)
	if err != nil {
		return User{}, fmt.Errorf("load balancer claims: %w", err)
	}
	user, err := v.verifyAccess(r.Context(), access)
	if err != nil {
		return User{}, fmt.Errorf("access token: %w", err)
	}
	if claims.Sub != user.Sub {
		return User{}, errors.New("the claims and the access token name different users")
	}
	user.Email = claims.Email
	return user, nil
}

type dataClaims struct {
	Sub   string `json:"sub"`
	Email string `json:"email"`
}

// verifyData checks the load balancer's claims: ES256 with its key, its ARN as the signer, the
// pool as issuer and the client as client, not expired.
func (v *Verifier) verifyData(ctx context.Context, raw string) (dataClaims, error) {
	var claims dataClaims
	t, err := parseToken(raw)
	if err != nil {
		return claims, err
	}
	if t.headerString("signer") != v.LoadBalancerARN {
		return claims, fmt.Errorf("signed by %q, not this load balancer", t.headerString("signer"))
	}
	if t.headerString("iss") != v.Issuer || t.headerString("client") != v.ClientID {
		return claims, errors.New("another user pool or client")
	}
	exp, ok := t.header["exp"].(float64)
	if !ok || v.Now().After(time.Unix(int64(exp), 0).Add(clockSkew)) {
		return claims, errors.New("expired")
	}
	key, err := v.ALB.Key(ctx, t.headerString("kid"))
	if err != nil {
		return claims, err
	}
	if err := t.verifyES256(key); err != nil {
		return claims, err
	}
	if err := json.Unmarshal(t.payload, &claims); err != nil {
		return claims, err
	}
	if claims.Sub == "" {
		return claims, errors.New("no subject")
	}
	return claims, nil
}

type accessClaims struct {
	Sub      string   `json:"sub"`
	Username string   `json:"username"`
	Issuer   string   `json:"iss"`
	ClientID string   `json:"client_id"`
	TokenUse string   `json:"token_use"`
	Expires  int64    `json:"exp"`
	AuthTime int64    `json:"auth_time"`
	Groups   []string `json:"cognito:groups"`
}

// verifyAccess checks the Cognito access token: RS256 with a pool key, an access token of this
// pool and client, not expired. Its groups are the user's roles.
func (v *Verifier) verifyAccess(ctx context.Context, raw string) (User, error) {
	t, err := parseToken(raw)
	if err != nil {
		return User{}, err
	}
	key, err := v.Cognito.Key(ctx, t.headerString("kid"))
	if err != nil {
		return User{}, err
	}
	if err := t.verifyRS256(key); err != nil {
		return User{}, err
	}
	var claims accessClaims
	if err := json.Unmarshal(t.payload, &claims); err != nil {
		return User{}, err
	}
	if claims.Issuer != v.Issuer || claims.ClientID != v.ClientID || claims.TokenUse != "access" {
		return User{}, errors.New("not an access token of this user pool and client")
	}
	expires := time.Unix(claims.Expires, 0)
	if v.Now().After(expires.Add(clockSkew)) {
		return User{}, errors.New("expired")
	}
	if claims.Sub == "" || claims.Username == "" || claims.AuthTime <= 0 {
		return User{}, errors.New("no subject, user name or sign-in time")
	}
	groups := claims.Groups
	if groups == nil {
		groups = []string{}
	}
	return User{
		Sub:      claims.Sub,
		Username: claims.Username,
		Groups:   groups,
		Roles:    v.RoleGroups.Of(groups),
		SignedIn: time.Unix(claims.AuthTime, 0).UTC(),
		Expires:  expires.UTC(),
	}, nil
}
