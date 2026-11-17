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
