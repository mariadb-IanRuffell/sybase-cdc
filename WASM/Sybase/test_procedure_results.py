"""Check procedure result boundaries and the multi-value WASM ABI without an Ignite node."""

import unittest
from contextlib import nullcontext
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock

from compiler import compile_file
from procedure_runtime import invoke


class ProcedureResultTests(unittest.TestCase):
    def test_results_follow_executed_branches_and_parameter_order(self):
        source = """
        CREATE OR REPLACE PROCEDURE dbo.report @seed INT = 11, @first INT OUTPUT, @second INT OUTPUT
        AS BEGIN
            SELECT @first = @seed
            IF @first = 0 RETURN 7
            SELECT k FROM lots WHERE k = @seed
            SELECT @second = @@rowcount
            SELECT @second AS n
        END
        """

        db = Mock()
        db.sql.side_effect = [nullcontext(iter([["K"]])), nullcontext(iter([["N"], [0]]))]

        with TemporaryDirectory() as tmp:
            directory = Path(tmp)
            path = directory / "report.sql"
            path.write_text(source)
            name = compile_file(path, directory)
            completed = invoke(db, directory, name, second=27)
            self.assertEqual(completed, {"status": 0, "outputs": {"first": 11, "second": 0},
                             "result_sets": [{"columns": ["K"], "rows": []}, {"columns": ["N"], "rows": [[0]]}]})
            args = [call.kwargs["query_args"][0][0] for call in db.sql.call_args_list]
            self.assertEqual(args, [11, 0])
            early = invoke(db, directory, name, seed=0, second=27)
            self.assertEqual(early, {"status": 7, "outputs": {"first": 0, "second": 27}, "result_sets": []})

    def test_snapshot_is_captured_once_and_is_internal(self):
        source = """
        CREATE PROC grouped @key INT, @saved INT OUTPUT AS BEGIN
            SELECT a.id AS first_id INTO #items FROM data AS a WHERE a.id = @key
            SELECT @saved = @@rowcount
            SELECT s.first_id FROM #items AS s ORDER BY s.first_id
            RETURN @saved
        END
        """

        db = Mock()
        db.sql.side_effect = [nullcontext(iter([["FIRST_ID"], [42]])) for _ in range(2)]
        with TemporaryDirectory() as tmp:
            directory = Path(tmp)
            path = directory / "another.sql"
            path.write_text(source)
            name = compile_file(path, directory)
            self.assertEqual(invoke(db, directory, name, key=42), {
                "status": 1, "outputs": {"saved": 1}, "result_sets": [{"columns": ["FIRST_ID"], "rows": [[42]]}]
            })
            materialize, read_snapshot = db.sql.call_args_list
            self.assertEqual(materialize.args[0], "SELECT a.id AS first_id FROM data AS a WHERE a.id = CAST(? AS INT)")
            self.assertEqual(materialize.kwargs["query_args"][0][0], 42)
            self.assertEqual(read_snapshot.args[0], "SELECT s.first_id FROM TABLE(first_id BIGINT = ?) AS s ORDER BY s.first_id")
            column = read_snapshot.kwargs["query_args"][0][0][1]
            self.assertEqual([value for value, _type in column], [42])
