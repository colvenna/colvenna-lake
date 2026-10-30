// Package identity verifies who a request comes from. The entry's load balancer signs each user
// in through Cognito and forwards two tokens: its own signed claims (x-amzn-oidc-data, ES256) and
// the user's Cognito access token (RS256), which carries the user's groups. A request counts as a
// user's only when both verify, name the same user, and come from this load balancer and client.
package identity

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"strings"
)

// token is a compact JWS split into its parts, its header and payload decoded.
type token struct {
	header    map[string]any
	payload   []byte
	signed    string
	signature []byte
}

// parseToken splits a compact JWS. The load balancer pads its base64url segments with '=', which
// RFC 7515 does not, so both forms are accepted; the signature covers the segments as sent.
func parseToken(raw string) (*token, error) {
	parts := strings.Split(raw, ".")
	if len(parts) != 3 {
		return nil, errors.New("not a JWS: expected three segments")
	}
	head, err := segment(parts[0])
	if err != nil {
		return nil, fmt.Errorf("header: %w", err)
	}
	payload, err := segment(parts[1])
	if err != nil {
		return nil, fmt.Errorf("payload: %w", err)
	}
	signature, err := segment(parts[2])
	if err != nil {
		return nil, fmt.Errorf("signature: %w", err)
	}
	t := &token{payload: payload, signed: parts[0] + "." + parts[1], signature: signature}
	if err := json.Unmarshal(head, &t.header); err != nil {
		return nil, fmt.Errorf("header: %w", err)
	}
	return t, nil
}

func segment(s string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
}

// headerString is a string field of the header, or "" when absent or not a string.
func (t *token) headerString(name string) string {
	s, _ := t.header[name].(string)
	return s
}

// verifyES256 checks the signature with a P-256 key: r and s, 32 bytes each (RFC 7518 3.4).
func (t *token) verifyES256(key *ecdsa.PublicKey) error {
	if t.headerString("alg") != "ES256" {
		return fmt.Errorf("algorithm %q, expected ES256", t.headerString("alg"))
	}
	if len(t.signature) != 64 {
		return errors.New("ES256 signature is not 64 bytes")
	}
	digest := sha256.Sum256([]byte(t.signed))
	r := new(big.Int).SetBytes(t.signature[:32])
	s := new(big.Int).SetBytes(t.signature[32:])
	if !ecdsa.Verify(key, digest[:], r, s) {
		return errors.New("bad signature")
	}
	return nil
}

// verifyRS256 checks the signature with an RSA key, PKCS #1 v1.5 over SHA-256.
func (t *token) verifyRS256(key *rsa.PublicKey) error {
	if t.headerString("alg") != "RS256" {
		return fmt.Errorf("algorithm %q, expected RS256", t.headerString("alg"))
	}
	digest := sha256.Sum256([]byte(t.signed))
	if err := rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], t.signature); err != nil {
		return errors.New("bad signature")
	}
	return nil
}
