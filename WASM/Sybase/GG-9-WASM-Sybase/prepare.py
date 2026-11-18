"""Compile the shared Sybase examples and adapt snapshot table syntax for GridGain 9."""

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from compiler import compile_file

sources = [HERE / 'sql/validate_restock.sql', HERE.parent / 'restock.sql',
           HERE.parent / 'examples/inventory_summary.sql', HERE.parent / 'examples/inventory_results.sql']
output = HERE / 'generated'
for source in sources:
    name = compile_file(source, output)
    abi_path = output / f'{name}.abi.json'
    bindings_path = output / f'{name}.bindings.json'
    abi = json.loads(abi_path.read_text())
    bindings = json.loads(bindings_path.read_text())
    for imported, operation in abi['operations'].items():
        # Each example resolves its base inventory through this dedicated table.
        bindings[imported] = bindings[imported].replace('FROM inventory', 'FROM GG9_WASM_INVENTORY').replace(
            'UPDATE inventory', 'UPDATE GG9_WASM_INVENTORY')
        snapshot_args = [arg for arg in operation['arguments'] if arg.startswith('#')]
        if snapshot_args:
            if len(snapshot_args) != len(operation["arguments"]):
                raise ValueError("This example adapter requires snapshot-only query arguments")
            table = snapshot_args[0].split('.')[0]
            if any(arg.split('.')[0] != table for arg in snapshot_args):
                raise ValueError('This adapter supports one snapshot reference per query')
            columns = [arg.split('.')[1] for arg in snapshot_args]
            expected = 'TABLE(' + ', '.join(f'{col} BIGINT = ?' for col in columns) + ')'
            if bindings[imported].count(expected) != 1:
                raise ValueError(f'Unexpected temporary table SQL for {imported}')
            bindings[imported] = bindings[imported].replace(expected, '__SNAPSHOT__')
            operation['snapshot'] = table
            operation['snapshot_columns'] = columns
            operation['arguments'] = [arg for arg in operation['arguments'] if not arg.startswith('#')]
    abi_path.write_text(json.dumps(abi, indent=2) + '\n')
    bindings_path.write_text(json.dumps(bindings, indent=2) + '\n')
    print(f'Prepared {name}')

# Compile the complete Customer 360 script using the typed GridGain ABI.
from gg9_compiler import compile_script

# The GridGain order_item and payment tables carry the colocation key customer_id, which Sybase's do not.
DENORMALISED = {t: {'column': 'customer_id', 'parent': 'orders', 'via': 'order_id'} for t in ('order_item', 'payment')}
repository = HERE.parents[2]
for name in compile_script(repository / 'sybase/sql/03_procs.sql', output, repository / 'sybase/sql/01_schema.sql', DENORMALISED):
    print(f'Compiled typed GridGain job: {name}')
for name in compile_script(HERE / 'sql/typed_roundtrip.sql', output):
    print(f'Compiled typed GridGain job: {name}')

# Disposable demo schema, derived from the source column types/primary keys.
# Foreign keys, indexes and ASE defaults are not a schema migration feature.
from ase_parser import parse_sql
from gg9_compiler import datatype, Sql, counter_name
from compiler import unqualified_name, identifier
schema = parse_sql((repository / 'sybase/sql/01_schema.sql').read_text())
statements = ["CREATE ZONE IF NOT EXISTS c360_ref STORAGE PROFILES ['default']",
              'CREATE TABLE id_seq (name VARCHAR(40) PRIMARY KEY, next_id BIGINT) ZONE c360_ref']
for table in schema.find_data('create_table'):
    name = unqualified_name(table.children[0])
    columns = []
    identity = False
    for node in table.children[1:]:
        if node.data == 'column_def':
            column, typ, *options = node.children
            identity |= any(x.data == 'identity' for x in options)
            notnull = any(x.data in ('not_null', 'identity') for x in options)
            target = datatype(typ)
            sql_type = "BOOLEAN" if target["ase"] == "bit" else target["sql"]
            columns.append(f'{identifier(column)} {sql_type}' + (' NOT NULL' if notnull else ''))
        elif node.data == 'table_constraint' and any(x.data == 'primary_key' for x in node.children):
            keys = next(x for x in node.children if x.data == 'column_list')
            columns.append('PRIMARY KEY ' + Sql({}).render(keys))
    if name in DENORMALISED:
        columns.append(DENORMALISED[name]['column'] + ' INT NOT NULL')
    statements.append(f'CREATE TABLE {name} (' + ', '.join(columns) + ')')
    if identity:
        statements.append(f"INSERT INTO id_seq VALUES ('{counter_name(name)}', 1)")
(output / 'customer360-schema.sql').write_text(';\n'.join(statements)+';\n')

# Compile each upstream fixture separately: two files can define the same procedure
# with different signatures. Never rewrite the downloaded SQL to improve coverage.
from gg9_compiler import ASECapabilityError, procedure_header
from gg9_compiler import unqualified_name as typed_name
import hashlib
from corpus.extract_and_audit import execution_digest, artifact_digest
runtime_digest = execution_digest()
corpus_manifest = {}
for fixture in sorted((HERE.parent / 'corpus/procedures').glob('*.sql')):
    key = fixture.stem
    procedure = next(parse_sql(fixture.read_text()).find_data('procedure'))
    name = typed_name(procedure_header(procedure)[0])
    prefix = 'corpus/' + key + '/'
    record = dict(procedure=name, source=fixture.name, sha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                  resource_prefix=prefix, compiled=False, runtime_digest=runtime_digest)
    try:
        compile_script(fixture, output / prefix, HERE / 'sql/corpus_schema.sql')
        record['compiled'] = True
        record['artifact_digest'] = artifact_digest(output / prefix, name)
    except ASECapabilityError as error:
        record.update(capabilities=error.capabilities, diagnostic=str(error))
    # Any ordinary compiler error is a build failure; only enumerated ASE-service
    # dependencies may appear as blocked entries in the deployed manifest.
    corpus_manifest[key] = record
(output / 'corpus-manifest.json').write_text(json.dumps(corpus_manifest, indent=2)+'\n')
print(f'Corpus: {sum(r["compiled"] for r in corpus_manifest.values())}/{len(corpus_manifest)} compiled; remaining fixtures require ASE services')
