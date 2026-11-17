"""Compile and run the inventory summary and multiple-result examples on Apache Ignite."""

import json

from pyignite import Client

from compiler import ROOT, compile_file
from demo import sql
from procedure_runtime import invoke


def main():
    output = ROOT / "generated"
    summary = compile_file(ROOT / "examples/inventory_summary.sql", output)
    multiple = compile_file(ROOT / "examples/inventory_results.sql", output)

    labeled = ["SKU", "AVAILABLE", "STOCK_LEVEL"]
    detail = ["SKU", "AVAILABLE"]
    totals = ["MATCHING_SKUS", "REQUESTED_MINIMUM"]
    cases = [
        (summary, {}, 0, 2, [(labeled, [[101, 12, "ready"], [202, 3, "low"]])]),
        (summary, {"minimum": 4}, 0, 1, [(labeled, [[101, 12, "ready"]])]),
        (summary, {"minimum": 99, "matches": 17}, 2, 0, []),
        (multiple, {}, 0, 2, [(detail, [[101, 12], [202, 3]]), (totals, [[2, 1]])]),
        (multiple, {"minimum": 99}, 0, 0, [(detail, []), (totals, [[0, 99]])]),
    ]
    client = Client()
    with client.connect("127.0.0.1", 10870):
        sql(client, "CREATE TABLE inventory (lot INT PRIMARY KEY, sku INT NOT NULL, quantity INT NOT NULL)")
        try:
            sql(client, "INSERT INTO inventory (lot, sku, quantity) VALUES "
                        "(1, 101, 7), (2, 101, 5), (3, 202, 3), (4, 303, 0)")
            for name, args, status, matches, sets in cases:
                result = invoke(client, output, name, **args)
                print(f"{name}({', '.join(f'@{k}={v}' for k, v in args.items())})")
                print(json.dumps(result))
                expected = {"status": status, "outputs": {"matches": matches},
                            "result_sets": [{"columns": cols, "rows": rows} for cols, rows in sets]}
                if result != expected:
                    raise AssertionError(f"Expected {expected}, received {result}")
        finally:
            sql(client, "DROP TABLE inventory")
    print("All five procedure calls passed.")


if __name__ == "__main__":
    main()
