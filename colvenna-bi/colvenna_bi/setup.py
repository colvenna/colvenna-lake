"""The ColvennaBI role, after `superset init`: what a BI user may do in Superset.

Gamma's and sql_lab's permissions (dashboards, charts, SQL Lab, exports) and writing datasets, but
no database: each dashboard's role gives access to its database alone (colvenna_bi.sync).

usage: python -m colvenna_bi.setup
"""


def bi_permissions(sm):
    names = set()
    for base in ("Gamma", "sql_lab"):
        role = sm.find_role(base)
        if role is None:
            raise SystemExit("run `superset init` first: no role %s" % base)
        names.update((p.permission.name, p.view_menu.name) for p in role.permissions)
    names.update({("can_read", "Dataset"), ("can_write", "Dataset")})
    # Access to databases comes from the dashboards' roles only.
    return sorted(n for n in names if n[0] not in ("database_access", "all_database_access",
                                                    "all_datasource_access", "all_query_access"))


def main():
    from superset.app import create_app

    app = create_app()
    with app.app_context():
        from superset import db, security_manager as sm

        role = sm.find_role("ColvennaBI") or sm.add_role("ColvennaBI")
        role.permissions = [sm.add_permission_view_menu(p, v) for p, v in bi_permissions(sm)]
        db.session.commit()
        print("ColvennaBI: %d permissions" % len(role.permissions))


if __name__ == "__main__":
    main()
