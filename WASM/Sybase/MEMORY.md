# Sybase WASM / GridGain Java-wrapper checkpoint

Updated 2026-11-19 for the metadata-adapter and explicit administration-mock work on `gridgain-9.2.0`.

## Current task and branch

Latest user request: adapt `sp_#count`, `sp_#ls`, `sp_#select` to GridGain metadata, test through the Java wrapper, document differences, and keep GG9 unchanged. The user then explicitly requested DBCC and `sp_thresholdaction` to do nothing and return success. This is authorization for those specific mocks, not for fabricating other ASE metadata.

The prior branch-transfer work was committed and pushed as `61ff254`. On 2026-11-19 the user explicitly authorized committing and pushing the metadata adaptations and administration mocks to `gridgain-9.2.0`. Source and artifact hashes were checked against the saved successful integration evidence before committing.

The user requested moving all our work onto the existing 9.2.0 branch and committing it there. The repository is `/Users/oscar/Projects/MariaDB/sybase-cdc`; the exact branch name is **`gridgain-9.2.0`**, tracking `origin/gridgain-9.2.0`.

Fetching origin found newer work through `dcd3fe7` (deployment, identity allocation, colocation keys, timing). Those changes already included the earlier WASM commits from main. Our uncommitted corpus extension was stashed, the branch was checked out, and the stash was merged. Conflicts in the compiler, Java runtime and generated bindings were resolved by retaining both sets of features. Main was not rewritten. The user authorized a local commit, not a push.

Our changes remain under `WASM/Sybase`. **Do not modify GridGain product source** at `/Users/oscar/Projects/MariaDB/gridgain-9`. Its HEAD is `6f398fd6b2033902d816235e56b4c701f07e0e72`; its working tree was checked clean again after testing. Public compute, SQL and transaction APIs supply all database operations.

The original broader task was to extend the compiler for 37 downloaded fixtures, test them through the Java wrapper and document limitations. That implementation and documentation are now complete within the explicit supported scope below.

## Verified results after the branch merge

- **30 original fixtures plus three explicit metadata ports execute real GridGain operations.** One threshold-action adapter executes as a separately identified success mock; 34 targets compile in total.
- **Three remaining ASE-service rejections** are checked through the wrapper. Mocks and rejections are not counted as real procedure adaptations.
- All six Customer 360 procedures and original inventory regressions passed; persistence was verified after restarting the node.
- `WASM/Sybase/.venv/bin/python -m unittest discover -s WASM/Sybase -v`: 17 tests passed, including metadata-intrinsic handle semantics, original-source rejection, separate adapter compilation and DBCC no-op WASM execution.
- `corpus/download.py --verify`: all 64 pinned upstream files passed SHA-256 verification.
- Latest metadata integration log: `/tmp/sybase-metadata-verified.log`. Earlier passes of these adapters are in `/tmp/sybase-metadata-corpus.log` and `/tmp/sybase-metadata-final.log`; use the latest report matching source fingerprints.
- Traced Customer 360 integration log: `/tmp/sybase-trace-gridgain-9.2.0.log`; 40 client calls passed (34 responses, six expected failures), with persistence after restart.
- `verify_customer360.py`: 30 WASM calls including one nested call, six expected failures, SQL/count/output/cursor/transaction boundaries verified.
- `verify_trace.py`: 113 ordered inventory trace events across eight wrapper calls verified.
- Maven is not installed locally, so the standalone Maven packaging path was not executed. Its excludes were updated to omit CorpusChecks alongside the existing embedded harnesses. Gradle compiled and deployed the actual wrapper jar successfully.
- Downloaded corpus fixtures were not compared against an ASE server in this change. The branch's existing Docker/ASE parity scripts were preserved but not rerun.

## Evidence and documentation

- `WASM/Sybase/corpus/gridgain-results.json`: saved per-fixture assertions and execution results from the successful Java harness.
- `WASM/Sybase/corpus/coverage.json` and `COVERAGE.md`: regenerated report: 37 original sources parsed; 34 selected targets compiled (30 original, three metadata ports, one mock); 33 real executions, one mock, three verified rejections.
- `corpus/extract_and_audit.py` accepts saved evidence only when the full fixture source hash, newly compiled artifact digest and compiler/wrapper/harness fingerprint match.
- `GG-9-WASM-Sybase/generated/corpus-manifest.json` holds fixture resource prefixes, source hashes, artifact digests and runtime fingerprints. Identical procedure names in separate upstream fixtures are isolated.
- READMEs updated in `WASM/Sybase`, its `corpus`, and `GG-9-WASM-Sybase`: run commands, fixture selector, outputs, ordered result/message events and limitations.
- The SQL bodies and source licenses in `corpus/procedures` / `corpus/upstream` are unchanged.

## Metadata ports and explicit mocks

- Sources: `GG-9-WASM-Sybase/sql/metadata/sp_count.sql`, `sp_ls.sql`, `sp_select.sql`, `sp_thresholdaction.sql`; original SQL and MIT notices remain in corpus/upstream. Explicit selection mapping is `ADAPTATIONS` in `corpus/extract_and_audit.py`.
- Direct procedure-name requests work for all four; fixture IDs still work. Manifest/evidence include adaptation kind and source hash.
- `sp_#count`: exact COUNT(*) over selected SYSTEM.TABLES, using a WASM cursor/loop and temporary result table. New SET-only `GG_ROW_COUNT(schema, table)` host intrinsic validates a catalog match and safely quotes identifiers. Data/index/total physical-size fields are NULL with an explanatory PRINT. Default schema PUBLIC, optional SQL LIKE table filter; counts are separate transactions and can scan large tables.
- `sp_#ls`: tables in chosen schema (U/default name pattern) or SYSTEM.SYSTEM_VIEWS (S). Owner/created date NULL; full names. Unsupported ASE type filters return status 1 with a message.
- `sp_#select`: alphabetical columns from SYSTEM.TABLE_COLUMNS; returns one CMD line per row. Joining with newlines gives a safely quoted SELECT. It does not execute that query. Missing/NULL table returns status 1 and a message. Table/schema inputs use exact catalog spelling; schema is separate, no parsing dotted names.
- DBCC lowering emits `mock_admin` callbacks, messages kind=mock and MOCK_ADMIN trace; no SQL calls. Arguments are parsed but not evaluated, including ASE-only functions inside them. @@rowcount clears to zero and WASM continues to its normal RETURN. `dbcc_noop.sql` demonstrates two mocked DBCC commands and status 0.
- Threshold adapter retains four original parameters, prints an explicit MOCK message and returns 0. No DUMP, log management or database changes. Its PRINT uses one scalar SELECT callback.
- Corpus tests include 6 count cases, 11 listing cases, 8 SELECT-generation cases, two threshold calls and a separate DBCC zero-SQL/data-unchanged check. Includes empty tables, schema isolation, fresh writes, special/reserved/quote-containing names, injection-shaped values and execution of generated SQL.
- Saved states are passed/adapted/mocked/blocked. `executed_on_gridgain` excludes mock-only success; `wasm_executed` includes it.

## Implementation

- Shared Lark grammar: `ase.lark` / `ase_parser.py`.
- Original integer ABI v1 remains in `compiler.py`; typed GridGain ABI v2 is `gg9_compiler.py`.
- ABI v2 stores typed Java values behind i32 handles (null = 0). WASM executes local assignments, control flow and returns; host callbacks submit parameterized SQL. Return status, @@rowcount and cursor status are unboxed integers.
- Added quoted/dbo names, CREATE OR REPLACE, SET with row-count reset, REPLICATE/REPEAT, RTRIM, SUBSTRING, NULL-aware string concatenation, empty-string normalization, PRINT, bounded WAITFOR, temporary-table DDL and materialized text locators.
- Runtime: `GG-9-WASM-Sybase/src/main/java/example/sybase/TypedWasmRuntime.java`; dispatch: `SybaseWasmJob.java`.
- Corpus request selects a manifest `fixture` key. Output includes status, outputs, result_sets, messages, timing and corpus-only ordered `events` containing one-based references to message/result-set indices.
- `CorpusChecks.java` exercises all 37 fixture requests and real database state. `run_corpus.sh` also enables existing Customer 360 checks and defaults tracing off to avoid huge payload logs.
- Temp tables are uniquely named physical GridGain tables with hidden UUID keys and cleanup on normal exit/failure/cancellation. SELECT * exposes source schema columns only.
- `text_locator` requires JSON `{"text":"materialized text"}`; native ASE tokens are rejected. SQL receives the underlying text.
- Short read/write transactions for standalone SELECT callbacks prevent stale reads immediately after thin-client writes.

## Preserved 9.2.0 branch behavior

- Shared `id_seq` identity allocator, counters matching phase2, default starting value 1,000,000,000 for absent counters; separately committed retrying allocations.
- Customer 360 colocation keys added to order_item/payment INSERTs from parent orders.
- ASE bit-column literal comparisons/assignments map to GridGain BOOLEAN.
- Whole-call read/write transactions for procedures with BEGIN or nested EXEC; timing response fields and the `_noop` benchmark path.
- Maven deployment and existing Docker/ASE parity scripts, node-provided Chicory/Jackson and prior performance documentation.
- Embedded fixtures now use BOOLEAN, matching colocation columns and seeded `id_seq` counters. Their expected small IDs (e.g. order 101) are preserved.
- Trace verifier accepts transaction events around a WASM call and selects exact Customer 360 names so corpus sp_test_* procedures cannot be miscounted.

## Explicit remaining limitations

- `sp_#ps`, `sp_#quickref`, `sp_#top` still require ASE process/catalog/documentation/session services. DBCC no-ops do not supply the rest of sp_#top's dependencies. All seven original upstream admin definitions still fail direct compilation; four are explicitly replaced in corpus preparation as documented above. Do not conflate ported or mocked targets with original ASE semantics.
- Materialized text locators are not native ASE token identity or ASE locator lifetime semantics.
- PRINT/results are buffered JSON, not TDS streaming. Limits: 10,000 rows/result, 64 MiB cumulative accounted payload, 100,000 handles, 20,000 SQL calls, call depth 32, cumulative WAITFOR 60 seconds.
- Large fixture tested at 1,000 and 10,000 rows of 4,000 characters; 100,000 rows explicitly fails. The 500,000-row branch also exceeds the row limit.
- Temporary DDL inside transactions, nested temp scope and ABI v2 SELECT INTO #temp are unsupported. Process termination can leave orphaned WASM_TMP_* tables despite normal cleanup.
- Whole-call transaction mode defers logical COMMIT until call completion; rollback includes writes before BEGIN and nested failure can undo an earlier logical COMMIT. This differs from ASE.
- RETURN NULL maps to 0 plus warning, a POC contract. Full RAISERROR severity/@@error protocol is unsupported.
- Cursor @@rowcount is currently 1/0 per fetch, not ASE cumulative cursor count.
- No complete mixed-type precedence, collation/charset, temporal rounding, arbitrary functions, UDTs, session state or trigger emulation. The 53-parameter constant-result fixture does not round-trip every parameter through SQL.

## Useful commands

From `/Users/oscar/Projects/MariaDB/sybase-cdc`:

```sh
WASM/Sybase/.venv/bin/python -m unittest discover -s WASM/Sybase -v
WASM/Sybase/.venv/bin/python WASM/Sybase/corpus/download.py --verify
WASM/Sybase/.venv/bin/python WASM/Sybase/corpus/extract_and_audit.py
cd WASM/Sybase/GG-9-WASM-Sybase
./run_corpus.sh
SYBASE_TRACE=true ./run_customer360.sh
```

Java 21 is required. `run.sh` selects it on macOS if JAVA_HOME is unset. Existing JDK: `/Users/oscar/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.7/Contents/Home`. Embedded node ports: 33490/10890/10390; storage under build/node-<uuid>. Preserve unrelated node directories.

The old conversation CWD under `/Users/oscar/Documents/ChatGPT` no longer exists. Use explicit repository workdirs. No subagents were requested or used.
