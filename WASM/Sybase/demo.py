"""Compile restock.sql, run its WASM export and verify the rows in Apache Ignite."""

import json
from functools import partial

from pyignite import Client
from pyignite.datatypes import IntObject
from wasmtime import Engine, FuncType, Linker, Module, Store, ValType

from compiler import ROOT, compile_file


def sql(client, statement, *args):
    with client.sql(statement, query_args=[(value, IntObject) for value in args]) as cursor:
        return list(cursor)


def run_cases(client, name, output):
    engine = Engine()
    store = Store(engine)
    linker = Linker(engine)
    module = Module.from_file(engine, output / f"{name}.wasm")
    bindings = json.loads((output / f"{name}.bindings.json").read_text())
    affected = []

    def update(statement, increment, key):
        [[count]] = sql(client, statement, increment, key)
        affected.append(count)
        return count

    signature = FuncType([ValType.i32(), ValType.i32()], [ValType.i32()])
    for imported, statement in bindings.items():
        linker.define_func("ignite", imported, signature, partial(update, statement))
    restock = linker.instantiate(store, module).exports(store)[name]

    # Each expected snapshot also checks that the second SKU is untouched.
    cases = [
        # sku, units, SQL return status, affected rows per UPDATE call, quantity for 101
        (101,  5, 0, [1], 15),
        (101,  0, 1,  [], 15),
        (101, -3, 1,  [], 15),
        (999,  4, 2, [0], 15),
    ]
    read_inventory = "SELECT sku, quantity FROM inventory ORDER BY sku"
    print("Starting inventory:", sql(client, read_inventory))
    print(" sku  units  Ignite -> WASM (counts)  WASM status  inventory after call")
    for sku, units, expected_status, expected_affected, quantity in cases:
        affected.clear()
        status = restock(store, sku, units)
        inventory = sql(client, read_inventory)
        print(f"{sku:4} {units:6}  {str(affected):22} {status:11}  {inventory}")
        expected = (expected_status, expected_affected, [[101, quantity], [202, 20]])
        if (status, affected, inventory) != expected:
            raise AssertionError(f"restock({sku}, {units}) expected {expected}")
    print("All four cases passed.")


def main():
    source = ROOT / "restock.sql"
    output = ROOT / "generated"
    name = compile_file(source, output)
    for label, path in [
        ("Sybase ASE SQL", source),
        ("Abstract syntax tree", output / f"{name}.ast.txt"),
        ("WebAssembly text", output / f"{name}.wat"),
        ("Ignite imports", output / f"{name}.bindings.json"),
    ]:
        print(f"\n{label}\n{path.read_text()}")

    client = Client()
    with client.connect("127.0.0.1", 10870):
        # CREATE must succeed before this run owns the table and may drop it.
        sql(client, "CREATE TABLE inventory (sku INT PRIMARY KEY, quantity INT NOT NULL)")
        try:
            sql(client, "INSERT INTO inventory (sku, quantity) VALUES (?, ?), (?, ?)", 101, 10, 202, 20)
            run_cases(client, name, output)
        finally:
            sql(client, "DROP TABLE inventory")


if __name__ == "__main__":
    main()
