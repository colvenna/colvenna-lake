"""Superset's configuration for Colvenna Lake's BI interface (LLD "BI 查询界面").

Who a request is comes from the entry alone (colvenna_bi.security); every connection to Doris is the
signed-in person's own Doris user, and every statement names them (colvenna_bi.mutator); nothing keeps one person's results where
another could read them: no results backend, no data or metadata cache, no reports, alerts,
thumbnails or embedding.
"""

import os
from datetime import timedelta

from flask_appbuilder.security.manager import AUTH_REMOTE_USER

from colvenna_bi import dialect, mutator, security

dialect.register()

SECRET_KEY = os.environ["SUPERSET_SECRET_KEY"]
SQLALCHEMY_DATABASE_URI = os.environ["COLVENNA_BI_METADATA_URI"]
# A random one, though embedding is off: the default is public.
GUEST_TOKEN_JWT_SECRET = os.environ["COLVENNA_BI_GUEST_TOKEN_SECRET"]

# Signing in.
AUTH_TYPE = AUTH_REMOTE_USER
AUTH_USER_REGISTRATION = False
PUBLIC_ROLE_LIKE = None
FAB_API_SWAGGER_UI = False
FLASK_APP_MUTATOR = security.install
SESSION_COOKIE_SECURE = True
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = "Lax"
PERMANENT_SESSION_LIFETIME = timedelta(hours=8)
# Behind the load balancer: its scheme and the client's address.
ENABLE_PROXY_FIX = True
TALISMAN_ENABLED = True
TALISMAN_CONFIG = {
    "content_security_policy": {
        "base-uri": ["'self'"],
        "default-src": ["'self'"],
        "img-src": ["'self'", "blob:", "data:"],
        "worker-src": ["'self'", "blob:"],
        "connect-src": ["'self'"],
        "object-src": "'none'",
        "style-src": ["'self'", "'unsafe-inline'"],
        "script-src": ["'self'", "'strict-dynamic'"],
        "frame-ancestors": ["'none'"],
    },
    "content_security_policy_nonce_in": ["script-src"],
    "force_https": False,
    "session_cookie_secure": True,
}

# Connecting to Doris.
DB_CONNECTION_MUTATOR = mutator.mutate
# Every statement carries the person: colvenna_bi.security.install wraps the one method all paths call
# (SQL_QUERY_MUTATOR alone covers either the paths before splitting or those after).
PREVENT_UNSAFE_DB_CONNECTIONS = True
SQL_MAX_ROW = 10000

# Nothing kept between people.
CACHE_CONFIG = {"CACHE_TYPE": "NullCache"}
DATA_CACHE_CONFIG = {"CACHE_TYPE": "NullCache"}
RESULTS_BACKEND = None
FEATURE_FLAGS = {
    # Should anyone turn a cache on, its entries are per person.
    "CACHE_QUERY_BY_USER": True,
    "ALERT_REPORTS": False,
    "THUMBNAILS": False,
    "GLOBAL_ASYNC_QUERIES": False,
    "EMBEDDED_SUPERSET": False,
    "EMBEDDABLE_CHARTS": False,
    "ENABLE_TEMPLATE_PROCESSING": False,
    "SSH_TUNNELING": False,
}
ENABLE_SUPERSET_META_DB = False

# What happened, on the pod's log.
from superset.utils.log import StdOutEventLogger  # noqa: E402

EVENT_LOGGER = StdOutEventLogger()
