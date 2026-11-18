"""Compiler ABI checks; real GridGain SQL checks live in Customer360Checks.java."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from wasmtime import Engine, Store, Module, Linker, Func, FuncType, ValType
from ase_parser import parse_sql
from gg9_compiler import compile_script, datatype
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


if __name__=='__main__': unittest.main()
