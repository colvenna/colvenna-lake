"""Who a request comes from, as colvenna-console decides it (LLD "入口与登录").

The entry's load balancer signs each user in through Cognito and forwards its own signed claims
(x-amzn-oidc-data, ES256) and the user's Cognito access token (RS256, with the user's groups). A
request is a user's only when both verify, name the same user, and come from this load balancer,
user pool and client.
"""

import re
import threading
import time
from dataclasses import dataclass

import jwt
import requests
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key

from colvenna_bi.settings import ROLES

DATA_HEADER = "X-Amzn-Oidc-Data"
ACCESS_TOKEN_HEADER = "X-Amzn-Oidc-Accesstoken"
CLOCK_SKEW = 30
_KEY_ID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class Refused(Exception):
    """The request is not a user's; the message says why."""


class NoIdentity(Refused):
    """The request has neither of the load balancer's headers."""


@dataclass(frozen=True)
class User:
    sub: str
    username: str
    email: str
    groups: tuple
    roles: tuple
    signed_in: int


class AlbKeys:
    """The load balancer's public keys by key ID, kept once fetched. A key ID the endpoint says it
    does not have (403, 404) is not asked for again for a minute; other failures are retried."""

    def __init__(self, base_url, get=requests.get, now=time.time):
        self.base_url, self.get, self.now = base_url, get, now
        self.keys, self.missing = {}, {}
        self.lock = threading.Lock()

    def key(self, kid):
        if not isinstance(kid, str) or not _KEY_ID.match(kid):
            raise Refused("key ID %r is not a UUID" % (kid,))
        with self.lock:
            if kid in self.keys:
                return self.keys[kid]
            if self.now() - self.missing.get(kid, -120) < 60:
                raise Refused("load balancer key %s: not found a moment ago" % kid)
        resp = self.get("%s/%s" % (self.base_url, kid), timeout=5)
        if resp.status_code in (403, 404):
            with self.lock:
                if len(self.missing) > 1000:
                    self.missing.clear()
                self.missing[kid] = self.now()
            raise Refused("load balancer key %s: %s" % (kid, resp.status_code))
        if resp.status_code != 200:
            raise Refused("load balancer key %s: %s" % (kid, resp.status_code))
        key = load_pem_public_key(resp.content)
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise Refused("load balancer key %s: not a P-256 key" % kid)
        with self.lock:
            self.keys[kid] = key
        return key


class PoolKeys:
    """The user pool's signing keys from its JWKS; an unknown key ID reloads it, at most once a
    minute."""

    def __init__(self, url, get=requests.get, now=time.time):
        self.url, self.get, self.now = url, get, now
        self.keys, self.loaded = {}, None
        self.lock = threading.Lock()

    def key(self, kid):
        with self.lock:
            if kid in self.keys:
                return self.keys[kid]
            if self.loaded is not None and self.now() - self.loaded < 60:
                raise Refused("no user pool key %r" % (kid,))
            resp = self.get(self.url, timeout=5)
            if resp.status_code != 200:
                raise Refused("user pool keys: %s" % resp.status_code)
            keys = {}
            for jwk in resp.json().get("keys", []):
                if jwk.get("kty") == "RSA" and jwk.get("use", "sig") == "sig":
                    keys[jwk["kid"]] = jwt.algorithms.RSAAlgorithm.from_jwk(jwk)
            if not keys:
                raise Refused("user pool keys: no RSA signing key")
            self.keys, self.loaded = keys, self.now()
            if kid in self.keys:
                return self.keys[kid]
        raise Refused("no user pool key %r" % (kid,))


class Verifier:
    def __init__(self, settings, alb_keys, pool_keys, now=time.time):
        self.s, self.alb_keys, self.pool_keys, self.now = settings, alb_keys, pool_keys, now

    def verify(self, headers):
        data, access = headers.get(DATA_HEADER), headers.get(ACCESS_TOKEN_HEADER)
        if not data and not access:
            raise NoIdentity("no identity headers")
        claims = self._data(data or "")
        user = self._access(access or "")
        if claims.get("sub") != user["sub"]:
            raise Refused("the claims and the access token name different users")
        groups = tuple(user.get("cognito:groups") or ())
        roles = tuple(r for r in ROLES if any(self.s.role_groups.get(g) == r for g in groups))
        return User(sub=user["sub"], username=user["username"], email=claims.get("email") or "",
                    groups=groups, roles=roles, signed_in=int(user["auth_time"]))

    def _data(self, token):
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as e:
            raise Refused("load balancer claims: %s" % e)
        if header.get("alg") != "ES256":
            raise Refused("load balancer claims: algorithm %r" % header.get("alg"))
        if header.get("signer") != self.s.load_balancer_arn:
            raise Refused("load balancer claims: signed by %r" % header.get("signer"))
        if header.get("iss") != self.s.issuer or header.get("client") != self.s.client_id:
            raise Refused("load balancer claims: another user pool or client")
        exp = header.get("exp")
        if not isinstance(exp, (int, float)) or self.now() > exp + CLOCK_SKEW:
            raise Refused("load balancer claims: expired")
        key = self.alb_keys.key(header.get("kid"))
        try:
            # Expiry is checked here, against this verifier's clock.
            claims = jwt.decode(token, key, algorithms=["ES256"],
                                options={"verify_aud": False, "verify_iss": False, "verify_exp": False,
                                         "verify_nbf": False, "verify_iat": False})
        except jwt.PyJWTError as e:
            raise Refused("load balancer claims: %s" % e)
        if not claims.get("sub"):
            raise Refused("load balancer claims: no subject")
        if isinstance(claims.get("exp"), (int, float)) and self.now() > claims["exp"] + CLOCK_SKEW:
            raise Refused("load balancer claims: expired")
        return claims

    def _access(self, token):
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as e:
            raise Refused("access token: %s" % e)
        if header.get("alg") != "RS256":
            raise Refused("access token: algorithm %r" % header.get("alg"))
        key = self.pool_keys.key(header.get("kid"))
        try:
            claims = jwt.decode(token, key, algorithms=["RS256"], issuer=self.s.issuer,
                                options={"verify_aud": False, "verify_exp": False, "verify_nbf": False,
                                         "verify_iat": False, "require": ["exp", "iss", "sub"]})
        except jwt.PyJWTError as e:
            raise Refused("access token: %s" % e)
        if not isinstance(claims["exp"], (int, float)) or self.now() > claims["exp"] + CLOCK_SKEW:
            raise Refused("access token: expired")
        if claims.get("client_id") != self.s.client_id or claims.get("token_use") != "access":
            raise Refused("access token: not an access token of this client")
        if not claims.get("username") or not claims.get("auth_time"):
            raise Refused("access token: no user name or sign-in time")
        return claims
