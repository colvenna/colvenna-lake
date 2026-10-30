"""Colvenna Lake's extensions to Apache Superset, the BI interface behind the web entry.

Superset runs them in its own process, so they are Python: who a request comes from (identity,
security), which Doris user a query connects as (credentials, mutator), browsing Doris without
information_schema (dialect), and the Superset databases and roles the dashboards need (sync).
"""
