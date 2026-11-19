# Downloaded SAP/Sybase ASE procedure corpus

This directory contains **64 pinned upstream files** from three public projects,
plus **37 extracted procedure fixtures**. The original files, licenses and notices
are preserved under `upstream/`. Extraction never installs procedures or executes
SQL against ASE or GridGain. This is a broader compatibility corpus, not a claim
that the WASM compiler implements all ASE 16 capabilities.

| Source | What was downloaded | Useful coverage |
| --- | --- | --- |
| [Hruodland/sap_ase_procs](https://github.com/Hruodland/sap_ase_procs/tree/c11a36833b379470097fe3808343044793de4f60) | Seven complete administration/development procedures, README and license; upstream explicitly says last tested on **ASE 16.0** | Temporary tables, loops, system catalogs/global variables, procedure calls, administrative commands, output formatting, string/date functions and dynamic SQL expressions |
| [DataAction/AdoNetCore.AseClient](https://github.com/DataAction/AdoNetCore.AseClient/tree/a4c53a756e9a8af4e75b7e0ec882d07863967a2f/test/AdoNetCore.AseClient.Tests/Integration) | ASE driver's integration-test sources and license; 28 extracted procedure fixtures | Input/output parameters, return status, multiple results including empty sets, NULL behavior, numeric/money precision, character/binary values, text/image/locator operations, messages/errors, large results and WAITFOR/streaming cases; transaction/charset tests remain in their original C# context |
| [linq2db ASE fixtures](https://github.com/linq2db/linq2db/blob/d141f5671afe0b6d2d5ab23fcd3b5ae252c2dcc9/Data/Create%20Scripts/Sybase.sql) | ASE schema/setup script, provider tests and license; two extracted procedure definitions | Broad type matrix, unsigned integers, Unicode, binary/image, identity columns, procedure queries and surrounding ORM test context |

The DataAction and linq2db fixtures target ASE; their inclusion is not independent
certification that each test has passed on this machine's ASE 16 server. The
Hruodland collection supplies the explicit upstream ASE 16.0 statement. This set
is broader than the inventory examples but is **not an exhaustive ASE conformance
suite**. It does not cover every service-pack feature, security mode, optimizer
behavior, XML extension, trigger/session interaction or physical storage setting.

## Inspect the downloaded procedures

Start with [`procedures/`](procedures/) for standalone SQL fixtures, or these original
sources, including their setup and assertions:

- [ASE 16 administration procedures](upstream/Hruodland/sql/)
- [Input/output and two-result-set echo](upstream/DataAction/test/AdoNetCore.AseClient.Tests/Integration/EchoProcedureTests.cs)
- [Message and result ordering](upstream/DataAction/test/AdoNetCore.AseClient.Tests/Integration/MessageOrderTests.cs)
- [Numeric output regression](upstream/DataAction/test/AdoNetCore.AseClient.Tests/Integration/OutboundParamProcedureTests.cs)
- [Text and locator procedures](upstream/DataAction/test/AdoNetCore.AseClient.Tests/Integration/TextEchoProcedureTests.cs)
- [Type schema and seed values](<upstream/linq2db/Data/Create Scripts/Sybase.sql>)

The upstream SQL setup script contains `DROP DATABASE`, server configuration and
other installation commands. The commands below only download, extract and inspect
text; they do not run those installers. Some procedures require ASE system tables,
`sybsyntax`, temporary tables or upstream fixture setup and cannot be executed as
standalone application queries.

## Reproduce and verify

From the POC directory:

```sh
cd /Users/oscar/Projects/MariaDB/sybase-cdc/WASM/Sybase
python3 corpus/download.py --verify
.venv/bin/python corpus/extract_and_audit.py
```

To download the **same pinned revisions** again:

```sh
python3 corpus/download.py
```

[`manifest.json`](manifest.json) records each repository, full commit SHA, source
path, download URL, license designation, byte size and SHA-256. Downloads use raw
files; there are no nested Git repositories. Downloading them does not install new
dependencies or run upstream code. [`extract_and_audit.py`](extract_and_audit.py)
extracts SQL batches and literal C# procedure strings, resolves only simple
interpolation of literal constants from the same file, and retains source links
and line references. Repeated definitions from different upstream tests remain
separate fixtures. The original C# assertions remain available; they are not
translated into an ASE test runner.

## Measured coverage

On 2026-11-18:

| Stage | Result |
| --- | ---: |
| Downloaded source/license files verified by SHA-256 | 64 / 64 |
| Extracted procedure fixtures accepted by the Lark parser | 37 / 37 |
| Compiled targets (30 originals + 3 metadata ports + 1 explicit mock) | 34 / 37 |
| Real executions through the Java wrapper (30 originals + 3 ports) | 33 / 37 |
| Threshold-action success mock verified separately | 1 |
| Remaining ASE-service rejections checked through the Java wrapper | 3 / 3 |
| Downloaded fixtures executed on ASE in this change | 0 |

See [`COVERAGE.md`](COVERAGE.md) for every fixture and its capability diagnostics,
[`coverage.json`](coverage.json) for AST kinds and execution checks, and
[`gridgain-results.json`](gridgain-results.json) for the saved Java harness evidence.
The audit verifies the fixture source hash, freshly compiled artifact digest and
compiler/wrapper/harness fingerprint and adaptation source hash before accepting
saved execution results. The states `passed`, `adapted`, `mocked` and `blocked`
are distinct; mocked success is excluded from the real-execution count.

Run the actual GridGain checks and refresh the saved report:

```sh
cd /Users/oscar/Projects/MariaDB/sybase-cdc/WASM/Sybase/GG-9-WASM-Sybase
./run_corpus.sh
cp build/corpus-results.json ../corpus/gridgain-results.json
../.venv/bin/python ../corpus/extract_and_audit.py
```

The runner compiles each fixture separately, submits it as a Java compute job,
asserts results and stored data, verifies cleanup, and checks persistence after
restarting the isolated GridGain node. Its SELECTs and DML use public GridGain APIs.
Three Hruodland helpers (`sp_#count`, `sp_#ls`, `sp_#select`) now select explicit
GridGain metadata adaptations. `sp_thresholdaction` selects a clearly marked
success mock. The original files remain unchanged and direct compilation still
rejects their ASE dependencies. `sp_#ps`, `sp_#quickref` and `sp_#top` remain rejected
before SQL because they need ASE services. DBCC statements themselves are visible
no-ops in the typed backend, tested separately with zero SQL calls.

Added support includes SET, delimited/dbo names, CREATE OR REPLACE, string
operations, temporary-table DDL, ordered PRINT/result events and bounded WAITFOR.
The text-locator examples use **materialized invocation-local text**, not native
ASE tokens. The large-result fixture is checked at 1,000 and 10,000 rows; its
100,000-row branch is tested for the explicit buffering-limit failure. There is
no TDS streaming or full ASE error/transaction/collation emulation.

See the [wrapper README](../GG-9-WASM-Sybase/README.md#run-all-37-downloaded-fixtures)
for requests, output parameters, multiple result sets and remaining limitations.
All six Customer 360 procedures are also rerun as regressions. The downloaded
fixtures were not compared with an ASE server; the existing Customer 360 parity
scripts are separate from this corpus verification. GridGain source is unchanged.

## Licenses and SAP's own samples

Preserve [DataAction's Apache-2.0 license](upstream/DataAction/LICENSE),
[linq2db's MIT license](upstream/linq2db/MIT-LICENSE.txt), and
[Hruodland's MIT license and per-file notices](upstream/Hruodland/LICENSE) when
redistributing these fixtures. Extracted files identify their upstream source and
license. The pinned SQL bodies remain unchanged. Explicit ports and mocks live
under `../GG-9-WASM-Sybase/sql/metadata/`, retain attribution, and are identified
separately in the manifest and coverage report.

SAP also documents `installpubs2`, `installpubs3` and `installpix2` in its
[ASE 16 installation guide](https://help.sap.com/doc/a6167530bc2b1014a88ec21bab8c671f/16.0.4.6/en-US/SAP_ASE_Installation_Guide_Linux_en.pdf).
Those vendor sample scripts ship with an ASE installation. They have not been
copied from an unofficial mirror or included in this download; the downloaded
corpus consists of the three public projects above.
