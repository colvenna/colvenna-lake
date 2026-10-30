"""The process's settings, verifier and credentials, made once from the environment."""

import functools

from colvenna_bi.credentials import Credentials
from colvenna_bi.identity import AlbKeys, PoolKeys, Verifier
from colvenna_bi.settings import Settings


@functools.lru_cache(maxsize=1)
def settings():
    return Settings.from_environment()


@functools.lru_cache(maxsize=1)
def verifier():
    s = settings()
    return Verifier(s, AlbKeys("https://public-keys.auth.elb.%s.amazonaws.com" % s.region),
                    PoolKeys(s.issuer + "/.well-known/jwks.json"))


@functools.lru_cache(maxsize=1)
def credentials():
    return Credentials(settings().doris_users_dir)
