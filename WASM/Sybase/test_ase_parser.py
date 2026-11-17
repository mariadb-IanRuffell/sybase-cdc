import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from lark import UnexpectedInput

from ase_parser import ROOT, as_dict, parse_sql
from compiler import UnsupportedSQL, compile_file


class ProcedureGrammarTests(unittest.TestCase):
    def test_procedure_statement_families(self):
        cases = [
            ("DECLARE @p numeric(12,2), @s varchar(20) SET @p = 1.25",
             {"declare_variables", "type_args", "set_variable", "decimal"}),
            ("set cursor rows 10 for inventory_rows set transaction isolation level read committed",
             {"set_cursor_rows", "set_isolation", "read_committed"}),
            ("begin tran restock save tran checkpoint_a rollback tran checkpoint_a commit work",
             {"begin_transaction", "save_transaction", "rollback_transaction", "commit_transaction"}),
            ("declare c insensitive scroll cursor for select t.* from db..t t for read only "
             "open c fetch absolute 2 from c into @x, @y close c deallocate cursor c",
             {"declare_cursor", "omitted_owner", "qualified_star", "fetch_direction", "deallocate_cursor"}),
            ("delete from t where current of cur", {"delete", "current_of"}),
            ("while exists (select 1 from t where t.id = @id) begin "
             "if @x is null break else continue end retry: goto retry",
             {"while_statement", "exists", "is_null", "break_statement", "continue_statement", "label", "goto_statement"}),
            ("exec @rc = dbo.p;2 @qty = @q output, @mode = default with recompile "
             "exec reporting...refresh exec ('select ' + @columns)", {"capture_status", "proc_number", "named_arg", "omitted_database_and_owner", "execute_string", "add"}),
            ("raiserror @@error 'invalid %1!', @id with errordata @id as item print 'done' waitfor delay '00:00:02'",
             {"raise_error", "error_data", "system_variable", "print_statement", "wait_for"}),
            ("create table #s (id int primary key, qty int not null default 0, check (qty >= 0)) "
             "create unique clustered index ix on #s(qty desc) alter table #s add note varchar(30) "
             "truncate table #s drop table #s", {"primary_key", "check_constraint", "create_index", "add_columns", "drop_object"}),
            ("insert into #s(id, qty) select sku, sum(qty) from t group by sku having sum(qty) > 0 "
             "update #s set qty = t.qty, id = t.id from t where #s.id = t.id "
             "delete top 2 from #s where qty between 0 and 4", {"insert", "group_by", "having", "update", "top_clause", "between"}),
            ("merge into target t using (select id from src) s on t.id = s.id "
             "when matched and t.qty < 0 then delete when matched then update set qty = 2 "
             "when not matched then insert (id, qty) values (s.id,1)",
             {"merge", "derived_table", "matched_update", "matched_delete", "unmatched_insert"}),
            ("select distinct top 5 t.id, cast(t.x as int) as v from t left outer join s on t.id = s.id "
             "where t.x not in (select x from u) union all select id, 0 from z order by v desc rows limit 4 offset 1",
             {"top_clause", "join_type", "cast_expr", "not_in", "set_operator", "pagination"}),
            ("select @p = convert(int, amount), @q = (select max(n) from u) from t holdlock "
             "where k !< 5 and label_text not like 'A%' escape '/' at isolation 1",
             {"variable_assignment", "convert_expr", "scalar_subquery", "lock_hint", "not_like", "at_isolation"}),
            ("select case @kind when 1 then 0x0a else 0x0f end, "
             "case when amount >= $20.5 then N'large' else 'small' end from orders",
             {"simple_when", "searched_when", "binary", "money", "string"}),
            ("select name from sysobjects order by name compute count(name) by name", {"compute_clause", "order_by"}),
            ("set identity_insert inventory on set lock wait 3 set arithabort numeric_truncation off set prefetch on",
             {"set_identity", "set_lock", "set_arithmetic"}),
            ("readtext docs.body @p 0 128 holdlock writetext docs.body @p with log @contents",
             {"read_text", "write_text", "with_log"}),
        ]
        for body, expected in cases:
            with self.subTest(body=body):
                ast = parse_sql(f"create proc demo as begin {body} end")
                self.assertLessEqual(expected, {node.data for node in ast.iter_subtrees()})

    def test_binding_and_operator_structure(self):
        ast = parse_sql("create proc p as if not @a = 0 or @b = 1 and @c < 2 "
                        "if @k = 0 return 1; else return 2;")
        outer, = next(ast.find_data("statements")).children
        condition, inner = outer.children
        self.assertEqual(condition.data, "logical_or")
        self.assertEqual([n.data for n in condition.children], ["logical_not", "logical_and"])
        self.assertEqual(len(inner.children), 3)  # ELSE attaches to the inner IF.
        math = parse_sql("select 1 + 2 * 3 - 4 & 5")
        bit_and, = math.find_data("bit_and")
        subtract, _ = bit_and.children
        add, _ = subtract.children
        self.assertEqual([subtract.data, add.data, add.children[1].data], ["subtract", "add", "multiply"])

    def test_batches_comments_and_quote_mode_preserve_positions(self):
        source = ("set quoted_identifier on\r\n  GO -- next\r\n"
                  "/* one /* inner */ two */ create or replace proc \"inventory totals\";2 "
                  "(@s varchar(20) = '/* ''quoted'' */' output) with execute as caller "
                  "as select \"total qty\" from [t]]s]\r\nGO\r\n"
                  "set quoted_identifier off select 'a\r\nGO\r\nb', \"text\"")
        ast = parse_sql(source)
        self.assertEqual(len(list(ast.find_data("batch"))), 3)
        self.assertEqual(len(list(ast.find_data("batch_end"))), 2)
        self.assertEqual(len(list(ast.find_data("string"))), 3)
        proc, = ast.find_data("procedure")
        item = as_dict(proc)
        self.assertEqual(item["line"], 3)
        self.assertTrue(source[item["start"]:item["end"]].startswith("create or replace"))
        for token in ast.scan_values(lambda value: hasattr(value, "start_pos")):
            self.assertEqual(source[token.start_pos:token.end_pos], token)
        self.assertTrue(list(parse_sql('select "col"', quoted_identifiers=True).find_data("column")))

    def test_syntax_errors(self):
        for body in ("select from t", "if @a = return 1", "update t set q = where id = @id",
                     "frobnicate @q", "begin select 1", "select 1 where x in ()"):
            with self.subTest(body=body), self.assertRaises(UnexpectedInput):
                parse_sql("create proc p as " + body)
        with self.assertRaisesRegex(ValueError, "line 2, column 2"):
            parse_sql("select 1\n /* unfinished")

    def test_compiler_consumes_same_ast_and_rejects_unsupported_nodes(self):
        script = ROOT / "restock.sql"
        with TemporaryDirectory() as directory:
            output = Path(directory)
            compile_file(script, output)
            self.assertEqual((output / "restock.ast.txt").read_text(), parse_sql(script.read_text()).pretty())
            self.assertTrue((output / "restock.wasm").read_bytes().startswith(b"\0asm"))
            for body in ("select distinct 1", "if @x = 0 return 1 else return 2",
                         "update stock set qty = qty + @x where sku <> @x"):
                with self.subTest(body=body):
                    source = output / "new.sql"
                    source.write_text("create proc newproc @x int as " + body)
                    with self.assertRaisesRegex(UnsupportedSQL, "line 1, column"):
                        compile_file(source, output)
                    self.assertFalse((output / "newproc.wasm").exists())


if __name__ == "__main__":
    unittest.main()
