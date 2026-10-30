"""Which Doris user a Superset connection logs in as: the signed-in person's own, or none.

Superset calls DB_CONNECTION_MUTATOR for every engine it makes (SQL Lab, charts, dashboards,
metadata browsing, exports). The Superset databases hold no password; this puts in the
person's Doris user and password for the dashboard the database is, and refuses everything
else: a guest, a request whose user is not the connection's, another host, a person who is no
user of that dashboard.
"""

import re

from flask import g

from colvenna_bi import runtime
from colvenna_bi.settings import SUB_DOMAIN

# The only options a connection may carry: mysqlclient runs others on connecting (sql_mode and
# collation go into SQL unescaped) or logs in with them (user, passwd, read_default_file), so
# anything else, from the URI's query or the engine's connect_args, is refused.
ALLOWED_CONNECT_ARGS = frozenset({"connect_timeout", "read_timeout", "write_timeout"})


class NotAllowed(Exception):
    """Why this connection may not be made."""


def credential_for(uri, username, user, guest, credentials, doris_host, doris_port, params=None):
    """The URI to connect with: the person's Doris identity on the dashboard's database."""
    if uri.query or set((params or {}).get("connect_args") or {}) - ALLOWED_CONNECT_ARGS:
        raise NotAllowed("a connection carries no options but its timeouts")
    if guest:
        raise NotAllowed("guests never connect")
    if user is None or not username or getattr(user, "username", None) != username:
        raise NotAllowed("the connection is not the signed-in user's")
    if uri.drivername.split("+")[0] != "doris" or uri.host != doris_host or uri.port != doris_port:
        raise NotAllowed("only the platform's Doris is connected to")
    # Doris URIs name <catalog>.<database>; the dashboard's database is in the internal catalog.
    catalog, _, database = (uri.database or "").rpartition(".")
    if catalog not in ("", "internal") or not database:
        raise NotAllowed("not a dashboard's database")
    identity = credentials.lookup(database, username)
    if identity is None:
        raise NotAllowed("%s is no user of the dashboard whose database is %s" % (username, database))
    return uri.set(username=identity.doris_user, password=identity.password)


def mutate(uri, params, username, security_manager, source):
    """DB_CONNECTION_MUTATOR."""
    from superset.errors import ErrorLevel, SupersetError, SupersetErrorType
    from superset.exceptions import SupersetSecurityException

    try:
        user = getattr(g, "user", None)
    except RuntimeError:
        # Outside a request or an app context: no one signed in.
        user = None
    settings = runtime.settings()
    try:
        uri = credential_for(uri, username, user, security_manager.is_guest_user(), runtime.credentials(),
                             settings.doris_host, settings.doris_port, params)
    except NotAllowed as e:
        raise SupersetSecurityException(SupersetError(
            message="Doris: %s" % e, error_type=SupersetErrorType.DATABASE_SECURITY_ACCESS_ERROR,
            level=ErrorLevel.ERROR))
    return uri, params


# The comment annotate writes, as many times as it is at the start of a statement.
_ANNOTATION = re.compile(r"\A(?:\s*/\* colvenna-bi user=[A-Za-z0-9._@+-]* sub=[A-Za-z0-9._@+-]* \*/\s*)+")


def annotate(sql, **kwargs):
    """The person and their sign-in as a comment, so Doris's audit names them as well as the Doris
    user; a comment already there (a stored query run again, by its author or by someone else)
    is replaced. It is not proof of anything: anyone may write such a comment. SQL Lab runs its
    statements where only g.user is set, so the person comes from there."""
    try:
        user = getattr(g, "user", None)
    except RuntimeError:
        return sql
    username = getattr(user, "username", None)
    if not username:
        return sql
    email = getattr(user, "email", "") or ""
    sub = email[: -len(SUB_DOMAIN)] if email.endswith(SUB_DOMAIN) else "-"
    # Only the characters a user pool name or a sub has: nothing that ends a comment.
    clean = lambda v: re.sub(r"[^A-Za-z0-9._@+-]", "_", v)  # noqa: E731
    return "/* colvenna-bi user=%s sub=%s */\n%s" % (clean(username), clean(sub), _ANNOTATION.sub("", sql))


def annotate_every_statement(database_class):
    """Superset 6.1.0 calls SQL_QUERY_MUTATOR either before statements are split or after
    (MUTATE_AFTER_SPLIT), and its paths differ: SQL Lab mutates before, its export and the
    executor after. So the annotation wraps the one method all of them call, whichever way."""
    original = database_class.mutate_sql_based_on_config
    if getattr(original, "colvenna_annotated", False):
        return

    def mutate_sql_based_on_config(self, sql_, is_split=False):
        return annotate(original(self, sql_, is_split=is_split))

    mutate_sql_based_on_config.colvenna_annotated = True
    database_class.mutate_sql_based_on_config = mutate_sql_based_on_config
