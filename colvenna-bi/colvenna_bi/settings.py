"""What the BI interface knows of the entry and the platform, from the environment the chart sets."""

import json
import os
from dataclasses import dataclass

# The roles of the entry (the entry stack's RoleGroups) and the Superset roles each gets here.
ROLES = ("admin", "operator", "bi")
SUPERSET_ROLES = {"admin": ["Admin"], "bi": ["ColvennaBI"]}
# A Superset user's e-mail is the person's sub at this domain: who the account was made for.
SUB_DOMAIN = "@users.colvenna-lake.invalid"
# The sign-out page: the second step of signing out, where the cookie is dropped.
SIGNED_OUT_PATH = "/signed-out"
# A dashboard's role: access to its Superset database, given to the dashboard's sign-in users.
DASHBOARD_ROLE_PREFIX = "Dashboard "


@dataclass(frozen=True)
class Settings:
    region: str
    load_balancer_arn: str
    user_pool_id: str
    client_id: str
    role_groups: dict
    public_url: str
    sign_in_url: str
    session_cookie: str
    doris_host: str
    doris_port: int
    doris_users_dir: str

    @property
    def issuer(self):
        return "https://cognito-idp.%s.amazonaws.com/%s" % (self.region, self.user_pool_id)

    @staticmethod
    def from_environment(env=os.environ):
        groups = json.loads(env["COLVENNA_ROLE_GROUPS"])
        if not groups or not all(r in ROLES for r in groups.values()):
            raise ValueError("COLVENNA_ROLE_GROUPS: unknown role in %s" % groups)
        return Settings(
            region=env["AWS_REGION"],
            load_balancer_arn=env["COLVENNA_LOAD_BALANCER_ARN"],
            user_pool_id=env["COLVENNA_USER_POOL_ID"],
            client_id=env["COLVENNA_CLIENT_ID"],
            role_groups=groups,
            public_url=env["COLVENNA_PUBLIC_URL"].rstrip("/"),
            sign_in_url=env["COLVENNA_SIGN_IN_URL"].rstrip("/"),
            session_cookie=env["COLVENNA_SESSION_COOKIE"],
            doris_host=env["COLVENNA_DORIS_HOST"],
            doris_port=int(env.get("COLVENNA_DORIS_PORT", "9030")),
            doris_users_dir=env.get("COLVENNA_DORIS_USERS_DIR", "/etc/colvenna-bi/doris-users"),
        )
