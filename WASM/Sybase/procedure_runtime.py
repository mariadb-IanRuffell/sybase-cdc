"""Invoke a compiled procedure against an open Ignite client."""

import json
from functools import partial

from pyignite.datatypes import IntObject, LongObject, Null, ObjectArrayObject
from wasmtime import Engine, FuncType, Linker, Module, Store, ValType


def invoke(client, directory, name, /, **parameters):
    """Return status, named INT outputs and ordered row sets for one WASM call."""
    abi = json.loads((directory / f"{name}.abi.json").read_text())
    statements = json.loads((directory / f"{name}.bindings.json").read_text())
    unknown = parameters.keys() - set(abi["parameters"])
    if unknown:
        raise TypeError(f"Unknown parameters: {', '.join(sorted(unknown))}")
    inputs = []
    for key in abi["parameters"]:
        if key in parameters:
            value = parameters[key]
        elif key in abi["defaults"]:
            value = abi["defaults"][key]
        elif key in abi["outputs"]:
            value = 0  # Convenience initial value for an omitted INT output slot.
        else:
            raise TypeError(f"Missing parameter @{key}")
        if type(value) is not int or not -(2**31) <= value < 2**31:
            raise ValueError(f"@{key} requires a signed 32-bit integer")
        inputs.append(value)

    # Each invocation owns its snapshot columns and the stream of row sets.
    snapshots = {}
    result_sets = []

    def execute(imported, *wasm_values):
        op = abi["operations"][imported]
        values = iter(wasm_values)
        args = []
        for arg in op["arguments"]:
            if arg.startswith("#"):
                if arg not in snapshots:
                    raise ValueError(f"Temporary column {arg} was never materialized on this execution path")
                args.append(snapshots[arg])
            else:
                args.append((next(values), IntObject))
        kind = op["kind"]
        with client.sql(statements[imported], query_args=args, include_field_names=kind != "update") as cursor:
            if kind == "update":
                [[affected]] = list(cursor)
                return affected
            columns = next(cursor)
            rows = list(cursor)

        if kind == "result_set":
            result_sets.append({"columns": columns, "rows": rows})
        elif kind == "snapshot":
            if [col.lower() for col in columns] != op["columns"]:
                raise ValueError(f"Unexpected columns for {op['target']}: {columns}")
            for index, column in enumerate(op["columns"]):
                cells = []
                for row in rows:
                    cell = row[index]
                    if cell is not None and (type(cell) is not int or not -(2**63) <= cell < 2**63):
                        raise ValueError("Temporary snapshot columns currently support signed integers and NULL")
                    cells.append((cell, Null if cell is None else LongObject))
                snapshots[f"{op['target']}.{column}"] = ((ObjectArrayObject.OBJECT, cells), ObjectArrayObject)
        else:
            raise ValueError(f"Unknown Ignite operation {kind}")
        return len(rows)

    engine = Engine()
    module = Module.from_file(engine, directory / f"{name}.wasm")
    with Store(engine) as store, Linker(engine) as linker:
        for imported, op in abi["operations"].items():
            count = sum(arg.startswith("@") for arg in op["arguments"])
            signature = FuncType([ValType.i32()] * count, [ValType.i32()])
            linker.define_func("ignite", imported, signature, partial(execute, imported))
        call = linker.instantiate(store, module).exports(store)[name]
        returned = call(store, *inputs)
    if abi["outputs"]:
        status, *values = returned
    else:
        status, values = returned, []
    return {"status": status, "outputs": dict(zip(abi["outputs"], values, strict=True)), "result_sets": result_sets}
