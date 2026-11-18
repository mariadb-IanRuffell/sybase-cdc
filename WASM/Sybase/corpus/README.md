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
| Fixtures compiled to validated typed WASM | 1 / 37 |
| Downloaded fixtures executed on ASE | 0 |
| Downloaded fixtures executed on GridGain | 0 |

See [`COVERAGE.md`](COVERAGE.md) for every fixture and its first diagnostic, and
[`coverage.json`](coverage.json) for machine-readable results and AST node kinds.
The one compiled fixture is `Person_SelectAll`; compilation alone does not prove
its required `Person` table or SQL behavior at runtime.

Many initial compiler failures are currently qualified/delimited names and
`CREATE OR REPLACE` in the typed backend. Further gaps include SET assignment,
locator types, temporary-table DDL, streaming/waits, dynamic SQL and ASE catalog or
administration calls. Only the **first** failing construct is reported per fixture;
fixing that construct does not establish complete support. The broader parser can
represent constructs that the execution backend deliberately rejects.

The six procedures in this repository's `sybase/sql/03_procs.sql` are a separate,
working target: all six compile and execute through the
[GridGain 9 wrapper demo](../GG-9-WASM-Sybase/README.md#run-every-procedure-in-03_procssql).
Their real GridGain execution checks are independent of this downloaded corpus.

## Licenses and SAP's own samples

Preserve [DataAction's Apache-2.0 license](upstream/DataAction/LICENSE),
[linq2db's MIT license](upstream/linq2db/MIT-LICENSE.txt), and
[Hruodland's MIT license and per-file notices](upstream/Hruodland/LICENSE) when
redistributing these fixtures. Extracted files identify their upstream source and
license; no SQL bodies have been rewritten to make the coverage numbers improve.

SAP also documents `installpubs2`, `installpubs3` and `installpix2` in its
[ASE 16 installation guide](https://help.sap.com/doc/a6167530bc2b1014a88ec21bab8c671f/16.0.4.6/en-US/SAP_ASE_Installation_Guide_Linux_en.pdf).
Those vendor sample scripts ship with an ASE installation. They have not been
copied from an unofficial mirror or included in this download; the downloaded
corpus consists of the three public projects above.
