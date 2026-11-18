"""Check the real demo's trace against its already-validated compute responses (stdlib only)."""

import argparse
from collections import defaultdict
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("log", type=Path)
parser.add_argument("--disabled", action="store_true", help="Expect no tracing in a successful demo run")
args = parser.parse_args()
log = args.log.read_text()
assert "ALL COMPUTE CHECKS PASSED" in log, "The demo must finish its business-result checks"
assert "[SYBASE-TRACE-ERROR]" not in log, "Trace serialization failed"
prefix = "[SYBASE-TRACE] "
events = [json.loads(line[len(prefix):]) for line in log.splitlines() if line.startswith(prefix)]
if args.disabled:
    assert not events, "Tracing was not disabled"
    print("Trace-disabled run passed: no trace events; business results and persistence checks passed.")
    raise SystemExit(0)

# Customer 360 may run in the same log; this verifier checks the original ABI v1 calls.
events = [e for e in events if e["procedure"] in ("restock", "inventory_summary", "inventory_results")]
responses = json.loads((Path(__file__).parent / "build/demo-results.json").read_text())
calls = defaultdict(list)
for event in events:
    calls[event["call_id"]].append(event)
assert len(calls) == 8, f"Expected eight Java-wrapper calls, got {len(calls)}"

cases = []
for trace in calls.values():
    assert [e["sequence"] for e in trace] == list(range(1, len(trace) + 1))
    assert trace[0]["event"] == "CALL_START" and trace[-1]["event"] == "CALL_END"
    assert all(e["node"] == "sybase-wasm-node" and e["procedure"] == trace[0]["procedure"] for e in trace)
    select = lambda kind: [e for e in trace if e["event"] == kind]
    procedure = trace[0]["procedure"]
    parameters = trace[0]["parameters"]
    if procedure == "restock":
        key = "restock_invalid" if parameters["units"] <= 0 else (
            "restock_missing" if parameters["sku"] == 999 else "restock_existing")
    else:
        key = procedure + ("_empty" if parameters["minimum"] == 99 else "")
    cases.append(key)
    expected = responses[key]
    assert trace[-1]["status"] == expected["status"]
    assert trace[-1]["outputs"] == expected["outputs"]
    returned = select("WASM_RETURN")
    assert len(returned) == 1
    assert returned[0]["values"] == [expected["status"], *expected["outputs"].values()]
    assert {e["parameter"][1:]: e["value"] for e in select("OUTPUT")} == expected["outputs"]
    assert {e["variable"][1:]: e["value"] for e in select("OUTPUT_SET")} == expected["outputs"]
    sets = select("RESULT_SET")
    assert [e["index"] for e in sets] == list(range(1, len(sets) + 1))
    assert [{"columns": e["columns"], "rows": e["rows"]} for e in sets] == expected["result_sets"]
    assert trace[-1]["result_set_count"] == len(sets)

    starts, ends, host_returns = select("SQL_START"), select("SQL_END"), select("HOST_RETURN")
    count = trace[-1]["sql_call_count"]
    assert len(starts) == len(ends) == len(host_returns) == count
    assert [e["sql_call"] for e in starts] == list(range(1, count + 1))
    for start, end, returned in zip(starts, ends, host_returns):
        assert start["sequence"] < end["sequence"] < returned["sequence"]
        assert start["sql_call"] == end["sql_call"] == returned["sql_call"]
        assert start["operation"] == returned["operation"]
        assert returned["i32"] == end.get("affected_rows", end.get("row_count"))
        # The listener must observe the actual assignment immediately after the import returns.
        assigned = trace[returned["sequence"]]
        assert assigned["event"] == "ROWCOUNT_SET" and assigned["variable"] == "@@rowcount"
        assert assigned["value"] == returned["i32"]
        assert "__SNAPSHOT__" not in start["sql"], "Trace must show expanded SQL"
        assert start["sql"].count("?") == len(start["parameters"])

    rowcounts = [e["value"] for e in select("ROWCOUNT_SET")]
    if procedure == "inventory_summary":
        matches = expected["outputs"]["matches"]
        assert rowcounts == ([0, 0, 1, 0] if matches == 0 else [0, matches, 1, 0, matches])
        snapshot, = select("SNAPSHOT")
        assert snapshot["target"] == "#stock" and len(snapshot["rows"]) == matches
        if matches:
            assert "VALUES" in starts[1]["sql"]
            assert starts[1]["parameters"] == [cell for row in snapshot["rows"] for cell in row]
    elif procedure == "inventory_results":
        matches = expected["outputs"]["matches"]
        assert rowcounts == [0, matches, 1, 1], "Final row count is 1 even when @matches is 2 or 0"
        assert starts[1]["parameters"] == [matches, parameters["minimum"]]
        assert not select("SNAPSHOT")
    else:
        assert count == (0 if key == "restock_invalid" else 1)
        if count:
            assert starts[0]["parameters"] == [parameters["units"], parameters["sku"]]

assert sorted(cases) == sorted([
    "restock_existing", "restock_missing", "restock_invalid", "inventory_summary", "inventory_summary",
    "inventory_summary_empty", "inventory_results", "inventory_results_empty",
])
print(f"Trace verified: {len(events)} ordered events across eight calls, including SQL, WASM assignments and result sets.")
