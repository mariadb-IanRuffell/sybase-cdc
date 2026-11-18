"""Typed GridGain 9 backend: WASM control flow, parameterized SQL host operations.

ABI v2 i32 values are invocation-scoped handles (0 = SQL NULL). Only status,
rowcount, cursor status and conditions are unboxed integers. Decimal arithmetic
is performed by GridGain SQL, never binary floating point in WASM.
"""
import json
import re
from pathlib import Path

from lark import Token, Tree
from wasmtime import Engine, Module, wat2wasm
from ase_parser import parse_sql
from compiler import UnsupportedSQL, identifier, unqualified_name


def datatype(node):
    name, *args = node.children
    sizes = [int(n) for n in args[0].children] if args else []
    if name.data == 'object_name':
        ase = unqualified_name(name).lower()
    elif name.data == 'time_type':
        ase = 'time'
    elif name.data == 'double_precision':
        ase = 'double'
    elif name.data == 'unsigned_type':
        ase = 'unsigned ' + str(name.children[0]).lower()
    elif name.data == 'char_type':
        ase = ('n' if any(c.data == 'national' for c in name.children) else '') + (
            'varchar' if any(c.data == 'varying' for c in name.children) else 'char')
    else:
        raise UnsupportedSQL(node, 'a supported scalar type')
    integer_types = {'tinyint': ('SMALLINT', 0, 255), 'smallint': ('SMALLINT', -32768, 32767),
                     'int': ('INT', -(2**31), 2**31-1), 'integer': ('INT', -(2**31), 2**31-1),
                     'bigint': ('BIGINT', -(2**63), 2**63-1),
                     'unsigned smallint': ('INT', 0, 65535),
                     'unsigned int': ('BIGINT', 0, 2**32-1),
                     'unsigned bigint': ('DECIMAL(20,0)', 0, 2**64-1), 'bit': ('SMALLINT', 0, 1)}
    sized = {'numeric', 'decimal', 'dec', 'float', 'char', 'varchar', 'nchar', 'nvarchar',
             'unichar', 'univarchar', 'binary', 'varbinary'}
    if sizes and ase not in sized:
        raise UnsupportedSQL(node, 'a type without length/precision arguments')
    if ase == 'float' and sizes and not 1 <= sizes[0] <= 53:
        raise UnsupportedSQL(node, 'FLOAT precision 1..53')
    result = {'ase': ase}
    if ase in integer_types:
        sql, low, high = integer_types[ase]
        result.update(kind='integer', sql=sql, minimum=str(low), maximum=str(high))
    elif ase in ('numeric', 'decimal', 'dec', 'money', 'smallmoney'):
        precision, scale = ((19, 4) if ase == 'money' else (10, 4)) if ase in ('money', 'smallmoney') else (
            (sizes[0], sizes[1] if len(sizes) > 1 else 0) if sizes else (18, 0))
        if not 1 <= precision <= 38 or not 0 <= scale <= precision:
            raise UnsupportedSQL(node, 'DECIMAL precision 1..38 and scale 0..precision')
        result.update(kind='decimal', sql=f'DECIMAL({precision},{scale})', precision=precision, scale=scale)
        if ase in ('money', 'smallmoney'):
            low, high = ('-922337203685477.5808', '922337203685477.5807') if ase == 'money' else ('-214748.3648', '214748.3647')
            result.update(minimum=low, maximum=high)
    elif ase in ('float', 'double', 'real'):
        sql = 'REAL' if ase == 'real' or (sizes and sizes[0] <= 24) else 'DOUBLE'
        result.update(kind='float', sql=sql)
    elif ase in ('char', 'varchar', 'nchar', 'nvarchar', 'unichar', 'univarchar', 'text', 'unitext', 'sysname', 'longsysname'):
        length = sizes[0] if sizes else {'text': 2147483647, 'unitext': 2147483647, 'sysname': 30, 'longsysname': 255}.get(ase, 1)
        fixed = ase in ('char', 'nchar', 'unichar')
        result.update(kind='string', sql=f'VARCHAR({length})', length=length, fixed=fixed)
    elif ase in ('binary', 'varbinary', 'image', 'timestamp'):
        length = 8 if ase == 'timestamp' else (sizes[0] if sizes else (2147483647 if ase == 'image' else 1))
        result.update(kind='binary', sql=f'VARBINARY({length})', length=length, fixed=ase in ('binary', 'timestamp'))
    elif ase in ('date', 'time', 'bigtime', 'datetime', 'smalldatetime', 'bigdatetime'):
        kind = 'date' if ase == 'date' else ('time' if ase in ('time', 'bigtime') else 'datetime')
        sql = {'date': 'DATE', 'time': 'TIME(6)', 'datetime': 'TIMESTAMP(6)'}[kind]
        result.update(kind=kind, sql=sql)
    else:
        raise UnsupportedSQL(node, 'a supported ASE scalar type (user-defined types need an explicit mapping)')
    if len(sizes) > (2 if ase in ('numeric', 'decimal', 'dec') else 1):
        raise UnsupportedSQL(node, 'valid type arguments')
    if sizes and (min(sizes) < 0 or sizes[0] == 0):
        raise UnsupportedSQL(node, 'positive type length/precision')
    return result


INT = {'ase': 'int', 'kind': 'integer', 'sql': 'INT', 'minimum': str(-(2**31)), 'maximum': str(2**31-1)}


def literal(node):
    kind, c = node.data, node.children
    if kind == 'null': return None
    if kind == 'integer': return int(c[0])
    if kind in ('decimal', 'money'): return str(c[0]).lstrip('$')
    if kind in ('negate', 'positive'):
        value = literal(c[0])
        return (-value if isinstance(value, int) else '-' + value) if kind == 'negate' else value
    if kind == 'string':
        value = str(c[0])
        if value[0].lower() == 'n': value = value[1:]
        return value[1:-1].replace(value[0]*2, value[0])
    if kind == 'binary': return str(c[0])
    raise UnsupportedSQL(node, 'a constant parameter default')


def counter_name(table):
    """id_seq counter shared with the phase2 jobs: orders -> order_id, order_item -> order_item_id, ..."""
    return 'order_id' if table == 'orders' else table + '_id'


class Sql:
    def __init__(self, types, bits=frozenset()):
        self.types = types
        self.bits = bits
        self.arguments = []

    def is_bit(self, node):
        """A reference to an ASE bit column, which GridGain stores as BOOLEAN."""
        return node.data == 'column' and identifier(node.children[-1].children[-1]).lower() in self.bits

    def bit_literal(self, node, other):
        """Render the 0/1 literal compared with or assigned to a BOOLEAN column as FALSE/TRUE."""
        if self.is_bit(other) and node.data == 'integer' and str(node.children[0]) in ('0', '1'):
            return 'TRUE' if str(node.children[0]) == '1' else 'FALSE'
        return self.render(node)

    def render(self, node):
        if isinstance(node, Token): return identifier(node)
        k, c = node.data, node.children
        r = self.render
        if k in ('local_var', 'system_variable'):
            key = str(c[0]).lower()
            if key not in self.types: raise UnsupportedSQL(node, 'a declared variable or supported @@variable')
            self.arguments.append(key)
            return f'CAST(? AS {self.types[key]["sql"]})'
        if k == 'object_name': return '.'.join(identifier(x) for x in c)
        if k == 'table_reference':
            if any(x.data != 'alias' for x in c[1:]): raise UnsupportedSQL(node, 'a table with an optional alias')
            return ' '.join([unqualified_name(c[0]), *(r(x) for x in c[1:])])
        if k in ('integer', 'decimal'): return str(c[0])
        if k == 'money': return str(c[0]).lstrip('$')
        if k == 'null': return 'NULL'
        if k == 'binary': return "X'" + str(c[0])[2:] + "'"
        if k == 'string': return "'" + literal(node).replace("'", "''") + "'"
        if k in ('star_arg', 'star'): return '*'
        if k in ('comparison_op', 'direction', 'quantifier', 'join_type'): return ' '.join(str(x).upper() for x in c)
        operators = {'add': '+', 'subtract': '-', 'multiply': '*', 'divide': '/', 'modulo': '%',
                     'logical_and': 'AND', 'logical_or': 'OR', 'concatenate': '||'}
        if k in operators: return f'({r(c[0])} {operators[k]} {r(c[1])})'
        if k in ('negate', 'positive', 'logical_not'):
            return '(' + {'negate': '-', 'positive': '+', 'logical_not': 'NOT '}[k] + r(c[0]) + ')'
        if k == 'comparison':
            op = r(c[1])
            if op not in ('=', '<>', '!=', '<', '>', '<=', '>='): raise UnsupportedSQL(node, 'an ANSI comparison')
            return f'({self.bit_literal(c[0], c[2])} {op} {self.bit_literal(c[2], c[0])})'
        if k in ('is_null', 'is_not_null'): return f'({r(c[0])} IS {"NOT " if k == "is_not_null" else ""}NULL)'
        if k == 'exists': return 'EXISTS (' + r(c[0]) + ')'
        if k == 'scalar_subquery': return '(' + r(c[0]) + ')'
        if k == 'function_call':
            name = unqualified_name(c[0]).lower()
            args = c[1].children if len(c) == 2 and c[1].data == 'expr_list' else c[1:]
            if name == 'getdate' and not args: return 'LOCALTIMESTAMP'
            if name in ('dateadd', 'datediff') and len(args) == 3:
                unit = unqualified_name(args[0].children[0]).lower()
                if unit not in ('dd', 'day'): raise UnsupportedSQL(node, 'DATEADD/DATEDIFF with day units')
                if name == 'datediff':
                    return f'TIMESTAMPDIFF(DAY, CAST({r(args[1])} AS DATE), CAST({r(args[2])} AS DATE))'
                return f'TIMESTAMPADD(DAY, {r(args[1])}, {r(args[2])})'
            functions = {'isnull': 'COALESCE', 'sum': 'SUM', 'count': 'COUNT', 'avg': 'AVG', 'min': 'MIN', 'max': 'MAX'}
            if name not in functions: raise UnsupportedSQL(node, 'a supported SQL function')
            return functions[name] + '(' + ', '.join(r(x) for x in args) + ')'
        if k in ('convert_expr', 'cast_expr'):
            if len(c) != 2: raise UnsupportedSQL(node, 'CONVERT without a style code')
            type_node, value = c if k == 'convert_expr' else (c[1], c[0])
            target = datatype(type_node)
            value_sql = r(value)
            if target['kind'] == 'integer': value_sql = 'TRUNCATE(' + value_sql + ', 0)'
            return f'CAST({value_sql} AS {target["sql"]})'
        if k == 'case_expr': return 'CASE ' + ' '.join(r(x) for x in c) + ' END'
        if k in ('searched_when', 'simple_when'): return f'WHEN {r(c[0])} THEN {r(c[1])}'
        if k == 'else_value': return 'ELSE ' + r(c[0])
        if k == 'join_clause':
            table, condition = c[-2:]
            return (' '.join(r(x) for x in c[:-2]) + ' JOIN ' + r(table) + ' ON ' + r(condition)).strip()
        if k == 'cross_join': return 'CROSS JOIN ' + r(c[0])
        if k == 'query':
            # TOP applies after ORDER BY. Emit LIMIT at the query boundary.
            tops = [x for x in c[0].children if x.data == 'top_clause']
            sql = ' '.join(r(x) for x in c)
            if tops: sql += ' LIMIT ' + r(tops[0].children[0])
            return sql
        if k == 'select_core': return 'SELECT ' + ' '.join(r(x) for x in c if x.data != 'top_clause')
        prefixes = {'from_clause': 'FROM ', 'where_clause': 'WHERE ', 'group_by': 'GROUP BY ',
                    'having': 'HAVING ', 'order_by': 'ORDER BY ', 'alias': 'AS ', 'column_alias': 'AS '}
        comma = {'select_list', 'expr_list', 'order_by', 'from_clause', 'assignments', 'name_list'}
        if k in prefixes or k in comma or k in ('select_item', 'column', 'joined_table', 'order_item'):
            return prefixes.get(k, '') + (', ' if k in comma else ' ').join(r(x) for x in c)
        if k == 'assignment': return r(c[0]) + ' = ' + self.bit_literal(c[1], c[0])
        if k == 'update': return 'UPDATE ' + unqualified_name(c[0]) + ' SET ' + ' '.join(r(x) for x in c[1:])
        if k == 'delete': return 'DELETE FROM ' + unqualified_name(c[0]) + ' ' + ' '.join(r(x) for x in c[1:])
        if k == 'column_list': return '(' + r(c[0]) + ')'
        if k == 'values': return 'VALUES ' + ', '.join(r(x) for x in c)
        if k == 'value_row': return '(' + ', '.join(r(x) for x in c) + ')'
        raise UnsupportedSQL(node, 'a supported GridGain SQL expression/statement')


class Compiler:
    def __init__(self, procedure, identities, signatures, bits=frozenset(), denormalised=None):
        self.bits, self.denormalised = bits, denormalised or {}
        name, *modifiers, self.body = procedure.children
        self.name = unqualified_name(name)
        self.types = {'@@rowcount': INT, '@@sqlstatus': INT, '@@identity': INT}
        self.parameters, self.outputs, self.defaults = [], [], {}
        self.operations, self.bindings, self.cursors = {}, {}, {}
        self.identities, self.signatures = identities, signatures
        for m in modifiers:
            if m.data != 'parameters': raise UnsupportedSQL(m, 'ordinary parameters')
            for decl in m.children:
                var, typ, *options = decl.children
                key = str(var).lower()
                self.declare(key, datatype(typ))
                self.parameters.append(key[1:])
                for option in options:
                    if option.data == 'default_argument': self.defaults[key[1:]] = literal(option.children[0])
                    elif option.data == 'output_mode': self.outputs.append(key[1:])
                    else: raise UnsupportedSQL(option, 'a default or OUTPUT')
        for d in self.body.find_data('variable_decl'): self.declare(str(d.children[0]).lower(), datatype(d.children[1]))
        self.label = 0

    def declare(self, key, typ):
        if key in self.types: raise ValueError(f'Duplicate variable {key}')
        self.types[key] = typ

    def local(self, key): return key if key.startswith('@@') else key[1:]

    def call(self, kind, sql=None, arguments=(), results=1, **metadata):
        key = f'{kind}_{len(self.operations)}'
        self.operations[key] = dict(kind=kind, arguments=list(arguments), results=results, **metadata)
        if sql is not None: self.bindings[key] = sql
        return [*(f'local.get ${self.local(arg)}' for arg in arguments), f'call ${key}']

    def query(self, node, kind, **metadata):
        renderer = Sql(self.types, self.bits)
        sql = renderer.render(node)
        return self.call(kind, sql, renderer.arguments, **metadata)

    def condition(self, node):
        renderer = Sql(self.types, self.bits)
        sql = 'SELECT CASE WHEN ' + renderer.render(node) + ' THEN 1 ELSE 0 END'
        return self.call('condition', sql, renderer.arguments)

    def emit(self, node):
        k, c = node.data, node.children
        if k in ('statements', 'block'): return [line for n in c for line in self.emit(n)]
        if k in ('declare_variables', 'declare_cursor'):
            if k == 'declare_cursor':
                if len(c) != 2: raise UnsupportedSQL(node, 'a forward-only cursor')
                self.cursors[identifier(c[0])] = c[1]
            return []
        if k == 'if_statement':
            return [*self.condition(c[0]), 'i32.const 0', 'local.set $@@rowcount', 'if', *self.emit(c[1]),
                    *(['else', *self.emit(c[2])] if len(c) == 3 else []), 'end']
        if k == 'while_statement':
            label = self.label
            self.label += 1
            return [f'block $done{label}', f'loop $loop{label}', *self.condition(c[0]),
                    'i32.const 0', 'local.set $@@rowcount', 'i32.eqz', f'br_if $done{label}',
                    *self.emit(c[1]), f'br $loop{label}', 'end', 'end']
        if k == 'return_status':
            if not c: status = ['i32.const 0']
            elif c[0].data in ('integer', 'negate', 'positive'): status = [f'i32.const {literal(c[0])}']
            else:
                renderer = Sql(self.types, self.bits)
                sql = 'SELECT ' + renderer.render(c[0])
                status = self.call('status', sql, renderer.arguments)
            return [*status, *(f'local.get ${key}' for key in self.outputs), 'return']
        if k == 'query':
            projections = next(x for x in c[0].children if x.data == 'select_list')
            assignments = [p for p in projections.children if p.data == 'variable_assignment']
            if assignments:
                if len(assignments) != len(projections.children): raise UnsupportedSQL(node, 'SELECT assigning only variables')
                destinations = [str(a.children[0].children[0]).lower() for a in assignments]
                if any(d not in self.types for d in destinations): raise UnsupportedSQL(node, 'declared assignment targets')
                renderer = Sql(self.types, self.bits)
                # Retain the original projection order and FROM/WHERE clauses.
                query = Tree('query', [Tree('select_core', [Tree('select_list', [a.children[1] for a in assignments]),
                        *(x for x in c[0].children if x is not projections)]), *c[1:]])
                sql = renderer.render(query)
                instructions = self.call('assign', sql, renderer.arguments + destinations, len(destinations)+1,
                                         destinations=destinations, sql_arguments=len(renderer.arguments))
                return [*instructions, 'local.set $@@rowcount', *(f'local.set ${self.local(d)}' for d in reversed(destinations))]
            return [*self.query(node, 'result_set'), 'local.set $@@rowcount']
        if k in ('update', 'delete'):
            return [*self.query(node, 'dml'), 'local.set $@@rowcount']
        if k == 'insert':
            if len(c) != 3 or c[1].data != 'column_list' or c[2].data != 'values' or len(c[2].children) != 1:
                raise UnsupportedSQL(node, 'INSERT with explicit columns and one VALUES row')
            table = unqualified_name(c[0]).lower()
            renderer = Sql(self.types, self.bits)
            columns = [identifier(x) for x in c[1].children[0].children]
            nodes = list(c[2].children[0].children)
            if len(columns) != len(nodes): raise UnsupportedSQL(node, 'one value per inserted column')
            values = []
            for col, x in zip(columns, nodes):
                values.append('TRUE' if col.lower() in self.bits and x.data == 'integer' and str(x.children[0]) == '1' else
                              'FALSE' if col.lower() in self.bits and x.data == 'integer' and str(x.children[0]) == '0' else
                              renderer.render(x))
            copy = self.denormalised.get(table)
            if copy and copy['column'] not in [x.lower() for x in columns]:
                # The GridGain table carries a colocation key Sybase's does not: take it from the parent row.
                lowered = [x.lower() for x in columns]
                if copy['via'] not in lowered: raise UnsupportedSQL(node, f'{copy["via"]} in the {table} INSERT')
                columns.append(copy['column'])
                values.append(f'(SELECT {copy["column"]} FROM {copy["parent"]} WHERE {copy["via"]} = '
                              + renderer.render(nodes[lowered.index(copy['via'])]) + ')')
            identity = self.identities.get(table)
            if identity and identity not in columns:
                columns.append(identity)
                values.append('CAST(? AS INT)')
            else: identity = None
            sql = f'INSERT INTO {table} (' + ', '.join(columns) + ') VALUES (' + ', '.join(values) + ')'
            return [*self.call('insert', sql, renderer.arguments, 2 if identity else 1, table=table, identity=identity, counter=counter_name(table) if identity else None),
                    *(['local.set $@@identity'] if identity else []), 'local.set $@@rowcount']
        if k in ('begin_transaction', 'commit_transaction', 'rollback_transaction'):
            return [*self.call(k, results=0, name=str(c[0]) if c else ''), 'i32.const 0', 'local.set $@@rowcount']
        if k == 'raise_error':
            if len(c) != 2: raise UnsupportedSQL(node, 'RAISERROR number and literal message')
            return [*self.call('raise_error', results=0, number=literal(c[0]), message=literal(c[1])),
                    'i32.const 0', 'local.set $@@rowcount']
        if k == 'open_cursor':
            name = identifier(c[0])
            if name not in self.cursors: raise UnsupportedSQL(node, 'a declared cursor')
            return [*self.query(self.cursors[name], 'open_cursor', cursor=name), 'local.set $@@rowcount']
        if k == 'fetch_cursor':
            if isinstance(c[0], Tree): raise UnsupportedSQL(node, 'forward-only FETCH')
            name = identifier(c[0])
            destinations = [str(x.children[0]).lower() for x in c[1:]]
            if name not in self.cursors or any(d not in self.types for d in destinations):
                raise UnsupportedSQL(node, 'a declared cursor and variables')
            return [*self.call('fetch_cursor', arguments=destinations, results=len(destinations)+2,
                              cursor=name, destinations=destinations), 'local.set $@@rowcount', 'local.set $@@sqlstatus',
                    *(f'local.set ${self.local(d)}' for d in reversed(destinations))]
        if k in ('close_cursor', 'deallocate_cursor'):
            return [*self.call(k, results=0, cursor=identifier(c[0])), 'i32.const 0', 'local.set $@@rowcount']
        if k == 'execute_procedure':
            if len(c) != 2 or c[0].data != 'object_name' or c[1].data != 'call_args':
                raise UnsupportedSQL(node, 'a bundled procedure with positional variable arguments')
            name = unqualified_name(c[0])
            if name not in self.signatures: raise UnsupportedSQL(node, 'a procedure in the same compilation unit')
            arguments = []
            for arg in c[1].children:
                if len(arg.children) != 1 or arg.children[0].data != 'local_var':
                    raise UnsupportedSQL(arg, 'a positional local variable argument')
                arguments.append(str(arg.children[0].children[0]).lower())
            if len(arguments) > len(self.signatures[name]): raise UnsupportedSQL(node, 'the callee parameter count')
            return self.call('execute_procedure', arguments=arguments, results=0, procedure=name,
                             parameters=self.signatures[name][:len(arguments)])
        if k == 'set_options' and str(c[0]).lower() == 'nocount':
            return ['i32.const 0', 'local.set $@@rowcount']
        raise UnsupportedSQL(node, 'a statement supported by the typed GridGain backend')

    def compile(self):
        lines = self.emit(self.body)
        lines.extend(['i32.const 0', *(f'local.get ${p}' for p in self.outputs)])
        imports = []
        for name, op in self.operations.items():
            params = ' (param ' + ' '.join(['i32']*len(op['arguments'])) + ')' if op['arguments'] else ''
            results = ' (result ' + ' '.join(['i32']*op['results']) + ')' if op['results'] else ''
            imports.append(f'  (import "ignite" "{name}" (func ${name}{params}{results}))')
        signature = ' '.join(f'(param ${p} i32)' for p in self.parameters)
        locals_ = [f'    (local ${self.local(k)} i32)' for k in self.types if k not in ['@'+p for p in self.parameters]]
        wat = '\n'.join(['(module', *imports, f'  (func (export "{self.name}") {signature} (result {" ".join(["i32"]*(1+len(self.outputs)))})',
                         *locals_, *('    '+line for line in lines), '  )', ')', ''])
        abi = dict(version=2, name=self.name, parameters=self.parameters, outputs=self.outputs, defaults=self.defaults,
                   types=self.types, operations=self.operations, representation='i32-handles-null-zero')
        return wat, abi, self.bindings


def bit_columns_in(schema):
    """Names of the ASE bit columns declared in a schema script."""
    return frozenset(m.lower() for m in re.findall(r'(?im)^\s*(\w+)\s+bit\b', Path(schema).read_text()))


def compile_script(source, output, schema=None, denormalised=None):
    script = parse_sql(Path(source).read_text(encoding='utf-8-sig'))
    procedures = []
    # USE and guarded DROP PROCEDURE are deployment scaffolding, never executed.
    for batch in script.children:
        if batch.data == 'batch_end': continue
        node = batch.children[0]
        if node.data == 'procedure': procedures.append(node)
        elif node.data == 'statements' and all(
            s.data == 'use_database' or (s.data == 'if_statement' and len(s.children) == 2
            and s.children[1].data == 'drop_object' and str(s.children[1].children[0].children[0]).lower() in ('proc', 'procedure'))
            for s in node.children): pass
        else: raise UnsupportedSQL(node, 'CREATE PROCEDURE batches with optional USE/guarded DROP scaffolding')
    identities = {}
    bits = bit_columns_in(schema) if schema else frozenset()
    if schema:
        for table in parse_sql(Path(schema).read_text()).find_data('create_table'):
            for col in table.children[1:]:
                if col.data == 'column_def' and any(x.data == 'identity' for x in col.children[2:]):
                    identities[unqualified_name(table.children[0]).lower()] = identifier(col.children[0])
    signatures = {}
    for p in procedures:
        name = unqualified_name(p.children[0])
        if name in signatures: raise ValueError(f'Duplicate procedure {name}')
        params = next((x for x in p.children if x.data == 'parameters'), None)
        signatures[name] = [str(x.children[0])[1:].lower() for x in params.children] if params else []
    if not procedures: raise ValueError('No procedures found')
    # Validate everything before writing any artifact.
    compiled = []
    for p in procedures:
        compiler = Compiler(p, identities, signatures, bits, denormalised)
        wat, abi, bindings = compiler.compile()
        wasm = wat2wasm(wat)
        Module.validate(Engine(), wasm)
        compiled.append((p, wat, abi, bindings, wasm))
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    for p, wat, abi, bindings, wasm in compiled:
        name = abi['name']
        (output / f'{name}.wat').write_text(wat)
        (output / f'{name}.wasm').write_bytes(wasm)
        (output / f'{name}.ast.txt').write_text(p.pretty())
        for suffix, data in [('abi', abi), ('bindings', bindings)]:
            (output / f'{name}.{suffix}.json').write_text(json.dumps(data, indent=2)+'\n')
    return [x[2]['name'] for x in compiled]
