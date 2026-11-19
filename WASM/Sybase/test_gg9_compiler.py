"""Compiler ABI checks; real GridGain SQL checks live in Customer360Checks.java."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from wasmtime import Engine, Store, Module, Linker, Func, FuncType, ValType
from ase_parser import parse_sql
from gg9_compiler import compile_script, datatype, ASECapabilityError
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


class GridGainCompilerTests(unittest.TestCase):
    def test_all_customer360_procedures(self):
        with TemporaryDirectory() as temp:
            names = compile_script(REPO/'sybase/sql/03_procs.sql',temp,REPO/'sybase/sql/01_schema.sql')
            self.assertEqual(set(names), {'sp_recalc_loyalty_tier','sp_customer_360','sp_create_order',
                                         'sp_add_order_item','sp_complete_order','sp_score_customers'})
            for name in names:
                abi=json.loads((Path(temp)/f'{name}.abi.json').read_text())
                self.assertEqual(abi['version'],2)
                module=Module(Engine(),(Path(temp)/f'{name}.wasm').read_bytes())
                self.assertEqual(module.exports[0].name,name)
            score=(Path(temp)/'sp_score_customers.wat').read_text()
            self.assertIn('loop $loop',score)
            self.assertIn('local.set $@@sqlstatus',score)

    def test_wasm_branches_on_affected_count_and_returns_output_handle(self):
        source='''create proc test @key int, @value numeric(14,2) output as begin
        update data set value=1 where id=@key
        if @@rowcount=0 return -3
        select @value=amount from data where id=@key
        return 0 end'''
        with TemporaryDirectory() as temp:
            path=Path(temp)/'test.sql'
            path.write_text(source)
            compile_script(path,temp)
            abi=json.loads((Path(temp)/'test.abi.json').read_text())
            for affected,expected in [(0,[-3,42]),(1,[0,77])]:
                engine=Engine()
                store=Store(engine)
                module=Module(engine,(Path(temp)/'test.wasm').read_bytes())
                linker=Linker(engine)
                calls=[]
                def callback(op):
                    def run(*arguments):
                        calls.append(op['kind'])
                        if op['kind']=='dml': return affected
                        if op['kind']=='condition':
                            self.assertEqual(arguments,(affected,))
                            return int(affected==0)
                        if op['kind']=='assign': return [77,1]
                        raise AssertionError(op)
                    return run
                for name,op in abi['operations'].items():
                    fn=Func(store,FuncType([ValType.i32()]*len(op['arguments']),[ValType.i32()]*op['results']),callback(op))
                    linker.define(store,'ignite',name,fn)
                instance=linker.instantiate(store,module)
                self.assertEqual(instance.exports(store)['test'](store,12,42),expected)
                self.assertEqual(calls,['dml','condition']+(['assign'] if affected else []))

    def test_null_defaults_and_unsupported_statements(self):
        with TemporaryDirectory() as temp:
            path=Path(temp)/'test.sql'
            path.write_text('create proc test @s varchar(10)=null output as begin return 0 end')
            compile_script(path,temp)
            self.assertIsNone(json.loads((Path(temp)/'test.abi.json').read_text())['defaults']['s'])
            for body in ["exec ('select 1')",'save tran point','goto label','select @@error']:
                path.write_text('create proc bad as begin '+body+' end')
                with self.assertRaises(ValueError,msg=body): compile_script(path,temp)
                self.assertFalse((Path(temp)/'bad.wasm').exists())

    def test_type_families_and_rejections(self):
        for typ in ['tinyint','smallint','int','bigint','unsigned smallint','unsigned int','unsigned bigint','bit',
                    'numeric(38,9)','decimal(14,2)','money','smallmoney','real','float','float(24)',
                    'char(4)','varchar(80)','nchar(3)','nvarchar(4)','unichar(8)','univarchar(8)',
                    'text','unitext','binary(16)','varbinary(32)','image','timestamp',
                    'date','time','bigtime','datetime','smalldatetime','bigdatetime']:
            tree=parse_sql(f'create proc test @x {typ} as return 0')
            self.assertIn('sql',datatype(next(tree.find_data('datatype'))),typ)
        for typ in ['numeric(39,2)','decimal(4,5)','varchar(0)','custom_type']:
            tree=parse_sql(f'create proc test @x {typ} as return 0')
            with self.assertRaises(ValueError,msg=typ): datatype(next(tree.find_data('datatype')))

    def test_all_downloaded_fixtures_compile_or_name_their_ase_dependencies(self):
        passed, blocked = 0, 0
        with TemporaryDirectory() as temp:
            for source in sorted((ROOT/'corpus/procedures').glob('*.sql')):
                try:
                    compile_script(source, Path(temp)/source.stem, ROOT/'GG-9-WASM-Sybase/sql/corpus_schema.sql')
                    passed += 1
                except ASECapabilityError as error:
                    self.assertTrue(source.name.startswith('Hruodland_'), source)
                    self.assertTrue(error.capabilities)
                    self.assertFalse((Path(temp)/source.stem).exists())
                    blocked += 1
        self.assertEqual((passed, blocked), (30, 7))

    def test_set_clears_rowcount_and_quoted_export_survives_wasm(self):
        with TemporaryDirectory() as temp:
            source = Path(temp)/'set.sql'
            source.write_text("create or replace proc [dbo].[set-value] @v int output as begin set @v=9 return @@rowcount end")
            compile_script(source,temp)
            abi=json.loads((Path(temp)/'set-value.abi.json').read_text())
            engine=Engine()
            store=Store(engine)
            module=Module(engine,(Path(temp)/'set-value.wasm').read_bytes())
            linker=Linker(engine)
            for name,op in abi['operations'].items():
                def callback(*args, kind=op['kind']):
                    if kind=='set_variable': return 123 # opaque handle, not integer 9
                    self.assertEqual(args,(0,)) # SET must clear the row count
                    return args[0]
                fn=Func(store,FuncType([ValType.i32()]*len(op['arguments']),[ValType.i32()]*op['results']),callback)
                linker.define(store,'ignite',name,fn)
            instance=linker.instantiate(store,module)
            self.assertEqual(instance.exports(store)['set-value'](store,0),[0,123])

    def test_star_extra_projection_and_filtered_constant(self):
        with TemporaryDirectory() as temp:
            source=Path(temp)/'q.sql'
            schema=Path(temp)/'schema.sql'
            schema.write_text('create table t (a int, b varchar(5))')
            source.write_text("create proc q as begin select *, 42 as extra from t select 'empty' where 1=2 end")
            compile_script(source,temp,schema)
            bindings=json.loads((Path(temp)/'q.bindings.json').read_text())
            self.assertIn('"A", "B", 42 AS "EXTRA"', bindings['result_set_0'])
            self.assertIn('FROM (VALUES (0))', bindings['result_set_1'])
            source.write_text('create proc [../../escape] as return 0')
            with self.assertRaises(ValueError): compile_script(source,temp)


    def test_metadata_ports_and_explicit_mock_compile_separately(self):
        from corpus.extract_and_audit import ADAPTATIONS, fixture_source
        with TemporaryDirectory() as temp:
            for fixture, (_, kind) in ADAPTATIONS.items():
                original=ROOT/'corpus/procedures'/f'{fixture}.sql'
                with self.assertRaises(ASECapabilityError): compile_script(original,temp)
                name,=compile_script(fixture_source(original),temp)
                abi=json.loads((Path(temp)/f'{name}.abi.json').read_text())
                bindings=json.loads((Path(temp)/f'{name}.bindings.json').read_text())
                if kind=='metadata':
                    self.assertTrue(any('"SYSTEM".' in sql for sql in bindings.values()))
                if name=='sp_#count':
                    self.assertTrue(any(op['kind']=='metadata_count' for op in abi['operations'].values()))
                if kind=='mock':
                    self.assertEqual({op['kind'] for op in abi['operations'].values()},{'print'})

    def test_dbcc_is_visible_noop_in_wasm(self):
        with TemporaryDirectory() as temp:
            source=ROOT/'GG-9-WASM-Sybase/sql/dbcc_noop.sql'
            compile_script(source,temp)
            abi=json.loads((Path(temp)/'dbcc_noop.abi.json').read_text())
            self.assertEqual(json.loads((Path(temp)/'dbcc_noop.bindings.json').read_text()),{})
            engine=Engine(); store=Store(engine); linker=Linker(engine)
            module=Module(engine,(Path(temp)/'dbcc_noop.wasm').read_bytes())
            calls=[]
            for name,op in abi['operations'].items():
                self.assertEqual(op['kind'],'mock_admin')
                self.assertEqual(op['arguments'],[])
                fn=Func(store,FuncType([],[]),lambda operation=op['operation']: calls.append(operation))
                linker.define(store,'ignite',name,fn)
            self.assertEqual(linker.instantiate(store,module).exports(store)['dbcc_noop'](store,123),0)
            self.assertEqual(calls,['DBCC CHECKDB','DBCC CHECKTABLE'])

    def test_metadata_count_returns_handle_and_resets_rowcount(self):
        with TemporaryDirectory() as temp:
            source=Path(temp)/'count.sql'
            source.write_text("create proc c @s varchar(128), @t varchar(255), @n bigint output as begin set @n=GG_ROW_COUNT(@s,@t) return @@rowcount end")
            compile_script(source,temp)
            abi=json.loads((Path(temp)/'c.abi.json').read_text())
            engine=Engine(); store=Store(engine); linker=Linker(engine)
            module=Module(engine,(Path(temp)/'c.wasm').read_bytes())
            for name,op in abi['operations'].items():
                def callback(*args, kind=op['kind']):
                    if kind=='metadata_count':
                        self.assertEqual(args,(11,22)); return 777 # BIGINT handle, never raw row count
                    self.assertEqual(args,(0,)); return 0
                fn=Func(store,FuncType([ValType.i32()]*len(op['arguments']),[ValType.i32()]*op['results']),callback)
                linker.define(store,'ignite',name,fn)
            self.assertEqual(linker.instantiate(store,module).exports(store)['c'](store,11,22,0),[0,777])
            source.write_text("create proc bad @s varchar(128), @n int output as set @n=GG_ROW_COUNT(@s,@s)")
            with self.assertRaises(ValueError): compile_script(source,temp)
            source.write_text("create proc bad @s varchar(128) as waitfor delay GG_ROW_COUNT(@s,@s)")
            with self.assertRaises(ValueError): compile_script(source,temp)


if __name__=='__main__': unittest.main()
