"""pydoris 1.2.0 without information_schema, which the operator withdraws from dashboard users.

pydoris asks information_schema whether a table exists (and get_columns asks that first) and for
a table's comment; everything else it reads with SHOW, which those users keep. These two are
answered with SHOW too, or not at all.
"""

from pydoris.sqlalchemy.dialect import DorisDialect
from sqlalchemy.dialects import registry


class ColvennaDorisDialect(DorisDialect):
    def has_table(self, connection, table_name, schema=None, **kw):
        self._ensure_has_table_connection(connection)
        schema = schema or self.default_schema_name
        quote = self.identifier_preparer.quote_identifier
        # Compared here rather than with LIKE: a dashboard's database holds a few objects.
        rows = connection.exec_driver_sql("SHOW FULL TABLES FROM %s" % quote(schema))
        return any(row[0] == table_name for row in rows)

    def get_table_comment(self, connection, table_name, schema=None, **kw):
        return {"text": None}


def register():
    """Doris URIs (doris://, doris+pydoris://) use this dialect, not pydoris's own."""
    registry.register("doris", "colvenna_bi.dialect", "ColvennaDorisDialect")
    registry.register("doris.pydoris", "colvenna_bi.dialect", "ColvennaDorisDialect")
