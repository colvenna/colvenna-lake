"""The Superset databases and roles the dashboards need, kept in step with the published users.

One Superset database per dashboard, named <namespace>/<name>, pointing at the dashboard's Doris
database with no password (the connection mutator puts in each person's own), with DML, CTAS,
async queries and uploads off. One role per dashboard with access to that database alone, which
the sign-in gives the dashboard's users. A dashboard whose users are all gone loses its role; its
database stays (with its datasets and charts) but nobody can connect to it.

usage: python -m colvenna_bi.sync [--once]
"""

import json
import sys
import time

MANAGED = "colvenna_managed"


def database_settings(dashboard, database, host, port):
    return {
        "database_name": dashboard,
        "sqlalchemy_uri": "doris://sso@%s:%d/internal.%s" % (host, port, database),
        "expose_in_sqllab": True,
        "allow_dml": False,
        "allow_ctas": False,
        "allow_cvas": False,
        "allow_run_async": False,
        "allow_file_upload": False,
        "impersonate_user": False,
        # Every round: what anyone set on the database since (engine options, connect_args,
        # secrets) goes, and the connection mutator refuses options anyway.
        "extra": json.dumps({MANAGED: True, "allow_multi_catalog": False,
                             "cost_estimate_enabled": False}),
        "encrypted_extra": None,
        "server_cert": None,
    }


def wanted(identities):
    """Each dashboard's Doris database, by dashboard."""
    return {i.dashboard: i.database for i in identities}


def sync_once(db, sm, Database, identities, host, port, role_of):
    """One round. A dashboard whose database name an unmanaged Superset database already has is
    skipped, and one that fails does not stop the others."""
    want = wanted(identities)
    managed, others = {}, set()
    for d in db.session.query(Database).all():
        if json.loads(d.extra or "{}").get(MANAGED):
            managed[d.database_name] = d
        else:
            others.add(d.database_name)
    done, skipped = [], []
    for dashboard, database in sorted(want.items()):
        if dashboard in others:
            skipped.append(dashboard)
            continue
        try:
            # Superset's security manager commits as it makes roles and permissions, which would
            # end a savepoint early: they are made first, the database and its grant after.
            role = sm.find_role(role_of(dashboard)) or sm.add_role(role_of(dashboard))
            with db.session.begin_nested():
                d = managed.get(dashboard) or Database(database_name=dashboard)
                for k, v in database_settings(dashboard, database, host, port).items():
                    if k == "sqlalchemy_uri":
                        d.set_sqlalchemy_uri(v)
                    else:
                        setattr(d, k, v)
                db.session.add(d)
                db.session.flush()
            access = sm.add_permission_view_menu("database_access", d.perm)
            role.permissions = [access]
            db.session.commit()
            done.append(dashboard)
        except Exception as e:  # noqa: BLE001 - this dashboard is tried again next round
            skipped.append("%s: %s" % (dashboard, e))
    for dashboard in sorted(set(managed) - set(want)):
        role = sm.find_role(role_of(dashboard))
        if role is not None:
            db.session.delete(role)
    db.session.commit()
    return done, skipped


def main(argv):
    from superset.app import create_app

    app = create_app()
    with app.app_context():
        from superset import db, security_manager
        from superset.models.core import Database

        from colvenna_bi import runtime
        from colvenna_bi.security import dashboard_role

        s = runtime.settings()
        while True:
            try:
                done, skipped = sync_once(db, security_manager, Database, runtime.credentials().all(),
                                          s.doris_host, s.doris_port, dashboard_role)
                print(json.dumps({"kind": "sync", "dashboards": done, "skipped": skipped}), flush=True)
            except Exception as e:  # noqa: BLE001 - tried again next round
                db.session.rollback()
                print(json.dumps({"kind": "sync-failed", "error": str(e)}), flush=True)
                if "--once" in argv:
                    raise
            if "--once" in argv:
                return
            time.sleep(30)


if __name__ == "__main__":
    main(sys.argv[1:])
