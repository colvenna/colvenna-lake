import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "iceberg"))
import iceberg  # noqa: E402

OUT = """Certificate was added to keystore
@@ 001
1\ta
2\tb
@@ 002
2\tb
1\ta
@@ 003 ERROR org.apache.iceberg.exceptions.ForbiddenException: Forbidden: not authorized
@@# org.apache.iceberg.exceptions.ForbiddenException: Forbidden: not authorized
@@ 004
3
"""


class IcebergTest(unittest.TestCase):
    def check(self, *argv):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write(OUT)
        out = io.StringIO()
        with redirect_stdout(out):
            code = iceberg.main([argv[0], f.name] + list(argv[1:]))
        os.unlink(f.name)
        return code, json.loads(out.getvalue())

    def test_same_rows_in_any_order(self):
        self.assertEqual(0, self.check("same", "1", "2")[0])
        self.assertEqual(1, self.check("same", "1", "4")[0])
        self.assertEqual(1, self.check("same", "1", "3")[0])

    def test_rows_failed_and_has(self):
        self.assertEqual(0, self.check("rows", "1", "2")[0])
        self.assertEqual(1, self.check("rows", "3", "0")[0])
        self.assertEqual(0, self.check("failed", "3", "Forbidden")[0])
        self.assertEqual(1, self.check("failed", "1", "Forbidden")[0])
        self.assertEqual(0, self.check("has", "4", "3")[0])
        # The error output is not a row.
        self.assertEqual(0, self.check("rows", "4", "1")[0])


if __name__ == "__main__":
    unittest.main()
