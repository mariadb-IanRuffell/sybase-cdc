# Sybase ASE stored procedure to WebAssembly

`restock(@sku, @units)` increases inventory for a single SKU when the caller supplies a positive quantity. The compiler puts the branch and return statuses in a WebAssembly module. Apache Ignite owns the rows and performs the increment in one parameterized SQL statement.

A separate [GridGain 9 compute example](GG-9-WASM-Sybase/README.md) uses unmodified GridGain 9. A Java compute wrapper hosts the inventory WASM and supplies SQL callbacks, output parameters and multiple result sets. Inventory is stored in GridGain 9's persistent storage and verified after a node restart.

## Run the inventory summary and read its results

With the local Ignite node running (see **Run it** below), run both result examples from this directory.

```sh
.venv/bin/python summary_demo.py
```

The demo compiles the original [inventory_summary.sql](examples/inventory_summary.sql) and calls the WASM export. The fixture has two lots of SKU 101 with quantities 7 and 5, one lot of SKU 202 with quantity 3, and one lot with zero stock. With the default minimum of 1, the caller receives this response.

```json
{
  "status": 0,
  "outputs": {"matches": 2},
  "result_sets": [
    {
      "columns": ["SKU", "AVAILABLE", "STOCK_LEVEL"],
      "rows": [[101, 12, "ready"], [202, 3, "low"]]
    }
  ]
}
```

1. Ignite executes the grouped `SELECT ... INTO #stock`. The host captures those rows privately for this invocation and returns their count to WASM. Two groups qualify, so `@@rowcount` is 2.
2. WASM executes `SELECT @matches = @@rowcount` by copying 2 into its `$matches` local. The final query runs on Ignite against the captured columns, using bound arrays and Ignite’s `TABLE(...)` function. The callback reads the column names and rows from the cursor and appends one result set. [Generated Ignite queries](generated/inventory_summary.bindings.json) · [Ignite TABLE function](https://ignite.apache.org/docs/ignite2/latest/sql-reference/system-functions)
3. Every WASM return path supplies `(status, matches)` as two `i32` results. The host uses the generated ABI to label the second value. With `minimum=99`, the snapshot is empty and WASM returns `(2, 0)` before the final query, so `result_sets` is empty. [WASM export](generated/inventory_summary.wat) · [ABI](generated/inventory_summary.abi.json)

To call a compiled module from Python, pass an open Ignite client and the generated-artifact directory. Keyword arguments use the lowercase parameter names without the leading `@`. An INT output parameter can receive a caller-supplied initial value. The wrapper initializes an omitted output slot to its declared default, or zero if it has none.

```python
from pathlib import Path
from procedure_runtime import invoke

reply = invoke(client, Path("generated"), "inventory_summary", minimum=4)
status = reply["status"]               # 0
matches = reply["outputs"]["matches"]  # 1
for row_set in reply["result_sets"]:
    print(row_set["columns"], row_set["rows"])
```

### Multiple result sets

ASE clients advance through successive query results and retrieve output parameters and return status separately. The demo models this with an ordered list of row sets, each with its own column names and positional rows. Every executed client-facing `SELECT` appends one entry, including an executed query that finds zero rows. Assignment, `SELECT INTO`, and statements skipped by control flow produce no entry. `SET NOCOUNT ON` still permits row sets and `@@rowcount` updates. [SAP ASE result handling](https://help.sap.com/docs/PRODUCT_ID/508bbd4903c74c50be8afcc0b92ebc9a/c00ff5556db61014bf8f040071e613d7.html?locale=en-US&state=PRODUCTION&version=16.0.3.9)

The [inventory_results procedure](examples/inventory_results.sql) returns detail rows followed by a one-row summary. At `minimum=99` the detail set has columns `SKU, AVAILABLE` and zero rows. The second set has columns `MATCHING_SKUS, REQUESTED_MINIMUM` and one row, `[0, 99]`. Its procedure status is 0 and its output parameter is 0.

The [runtime](procedure_runtime.py) buffers each result set and temporary snapshot in the calling process until the call returns. Persistent inventory stays in Ignite. Temporary snapshot columns currently accept signed 64-bit integers and SQL NULL. Nested procedure calls and streaming results to a TDS client remain outside this implementation.

## Parse a procedure into an AST

From this directory, use the Python environment described under **Run it**. Parsing works offline and requires only Lark.

```sh
.venv/bin/python ase_parser.py restock.sql
.venv/bin/python ase_parser.py examples/inventory_summary.sql --json
```

[ase.lark](ase.lark) is the shared SAP ASE Transact-SQL grammar. [ase_parser.py](ase_parser.py) returns a Lark tree from `parse_sql(source)`. The CLI prints the tree or serializes it as JSON. JSON nodes have a `kind`, ordered `children`, and source positions. Token leaves keep their spelling. Character offsets are zero-based with an exclusive end. Lines and columns start at one. The [inventory summary example](examples/inventory_summary.sql) includes an output parameter, a temporary table, grouping, and a searched `CASE`.

| Area | Syntax represented in the AST |
| --- | --- |
| Procedure definitions and scripts | `CREATE [OR REPLACE] PROC[EDURE]`, qualified and numbered procedures, typed parameters, defaults, output parameters, procedure options, and line-delimited `GO` batches |
| Control flow | Blocks, declarations, local and system variables, assignments, nested `IF/ELSE`, loops, labels, jumps, status returns, messages, and errors |
| Calls and transactions | Named or positional arguments, return-status capture, output arguments, dynamic `EXEC(...)` expressions, cursors, and named transactions and savepoints |
| Queries and expressions | Joins, correlated subqueries, `EXISTS`, quantified comparisons, grouping, aggregates, ordering, `UNION`, pagination, `CASE`, casts, function calls, and ASE operator precedence |
| Data and schema changes | `INSERT`, `UPDATE ... FROM`, `DELETE`, `MERGE`, `SELECT INTO`, temporary tables, indexes, constraints, and common table alterations |

The parser recognizes comments, including nested block comments. Double quotes initially delimit strings. Use `--quoted-identifiers` for source captured with that option enabled, or include an explicit `SET QUOTED_IDENTIFIER ON` in the script. Dynamic SQL retains its expression tree, since its final text can depend on runtime values.

Coverage targets ASE 16.x procedure bodies. Version-specific server commands, XML extensions, and some physical table and index options remain outside the grammar. A syntax error reports the file, line, and column. Name resolution, type checking, and server conformance are separate from parsing. [SAP procedure reference](https://infocenter.sybase.com/help/topic/com.sybase.infocenter.dc36272.1600/doc/html/san1393050931084.html) · [SAP operator precedence](https://help.sap.com/docs/SAP_ASE/5b36fd3f1aa440818ee6a7c9900d3e87/aaeed667bc2b1014a083e3290090c0b2.html)

An external syntax check parsed 22 published procedure-definition batches across four repositories. [SybaseASE-util](https://github.com/okolosov/SybaseASE-util/tree/589ed3bea0f0241b298f0a91075066d4d8bdb0c4) · [SAP ASE procedures](https://github.com/Hruodland/sap_ase_procs/tree/c11a36833b379470097fe3808343044793de4f60/sql) · [Sybase migration examples](https://github.com/okomissarov/SybaseToAuroraBlog/blob/d470c5c34c2978643be909abe408aedddafb9291/sql/code.sql) · [Sybase T-SQL scripts](https://github.com/abritoh/sybase-tsql-scripts/tree/d9daa32c62ba54b1f4b816142a086637404c5edc)

Run the offline AST and compiler regression tests with `.venv/bin/python -m unittest -v`.

## Trace a missing SKU from Ignite back into WASM

For `restock(999, 4)`, the positive quantity passes validation and the procedure attempts to update SKU 999.

| Stage | What happens |
| --- | --- |
| [ASE source](restock.sql) | Immediately after the `UPDATE`, the procedure checks `IF @@rowcount = 0 RETURN 2`. |
| [Generated AST](generated/restock.ast.txt) | The comparison after the update has a `system_variable` operand containing `@@rowcount`, the equality operator, and the integer literal 0. Its true branch returns 2. |
| [Ignite binding](generated/restock.bindings.json) | WASM calls `update_0(4, 999)`. The host binds those integers to `UPDATE inventory SET quantity = quantity + ? WHERE sku = ?`. |
| [Host callback](demo.py) | Ignite returns `[[0]]` for the missing key. Python unpacks the single count and returns `0` through the Wasmtime import, whose result type is `i32`. |
| [Generated WASM instructions](generated/restock.wat) | `call $update_0` leaves the count on the WASM stack. `local.set $@@rowcount` saves it, and `local.get $@@rowcount`, `i32.const 0`, and `i32.eq` test it. The comparison succeeds and the module returns status 2. |

For the existing SKU 101, the same callback returns 1. The comparison is false and the [module](generated/restock.wasm) returns status 0. Both cases use the count from the update itself.

[compiler.py](compiler.py) parses the source with the [shared grammar](ase.lark), walks the AST, emits WAT and assembles it into WASM. [demo.py](demo.py) runs the binary and uses Apache's `pyignite.Client.sql` API to create, seed, update and read the table. The host sends the two bound arguments as Ignite integers. [Apache Ignite Python client SQL example](https://github.com/apache/ignite-python-thin-client/blob/master/examples/sql.py)

## Run it

Use Python 3.11 and JDK 17 on macOS or Linux. From the project directory, install the Python packages and start the node.

```sh
python3.11 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
./start-ignite.sh
```

The launcher downloads Apache Ignite 2.18.0 on first use, verifies its SHA-512 digest, and starts an in-memory node on loopback. On macOS it selects JDK 17 when `JAVA_HOME` is unset. Once the first terminal shows `Ignite node started OK`, run this in a second terminal.

```sh
.venv/bin/python demo.py
```

The demo recompiles the SQL and prints each stage, then checks these results by querying Ignite after each call. SKU 101 starts at 10 and SKU 202 starts at 20.

| SKU | Units requested | Procedure status | UPDATE calls | Rows affected | Stock for SKU 101 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 101 | 5 | 0 | 1 | 1 | 15 |
| 101 | 0 | 1 | 0 | 0 | 15 |
| 101 | -3 | 1 | 0 | 0 | 15 |
| 999 | 4 | 2 | 1 | 0 | 15 |

In the terminal trace, `[0]` means Ignite returned zero affected rows from one update call. An empty list `[]` means validation returned status 1 before an update was attempted. The demo also checks SKU 202 after each call. It drops its sample table on completion. A pre-existing `inventory` table causes setup to fail before any rows are changed. Stop the node with Ctrl-C in the first terminal.

You can also run the compiler on its own.

```sh
.venv/bin/python compiler.py restock.sql --out generated
```

## WASM backend scope

The WASM backend handles integer inputs, defaults and output parameters, signed comparisons, blocks, conditional returns, the restock update, and the aggregate, filter, assignment, and ordered result queries used in the inventory examples. The calling convention assumes non-null signed 32-bit inputs. Table and column names resolve in Ignite. The module starts each standalone invocation with a zero row count. Each supported `UPDATE` replaces it with the Ignite result, and each `IF` evaluates its condition before clearing it. The proof of concept leaves session-wide state, transactions, `@@error`, and SQL null semantics outside its scope. Database errors propagate through the host call. Verification used the compiled module and a real Ignite node. Equivalence against a running ASE server remains untested.

SAP documents the statement-level behavior of `@@rowcount` in its [ASE global variables reference](https://help.sap.com/docs/SAP_ASE/f381b53fa63f4b1faf121bac28c093f7/aaed9e75bc2b1014bcb5dc46d648f881.html).
