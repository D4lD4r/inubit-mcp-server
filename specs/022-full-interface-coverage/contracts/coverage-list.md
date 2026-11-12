# Contract: Coverage List

**Feature**: 022-full-interface-coverage (FR-001 … FR-003, SC-001). Decision: research D-2.

## File

`src/main/resources/coverage/interface-coverage.tsv` holds the data. UTF-8, tab-separated, one
header line. Lines starting with `#` are comments.

```text
# source: vendor pages "Command Line Interface" and "REST API", INUBIT 8.1 and 9.0, retrieved 2026-11-12
# delivered-stories: US1
channel	entry	kind	area	story	v8_1	v9_0
REST	GET /license	READ	A	US1	reachable:get_server_settings/default	planned
CLI	license -td	READ	A	-	covered:get_server_settings	covered:get_server_settings
CLI	lls	READ	A	-	excluded:lists the local directory of the StartCLI client	excluded:lists the local directory of the StartCLI client
REST	GET /model/nodeByName/{diagram}/{module}	READ	E	US3	planned	undocumented
REST	GET /process/processes/{group}	READ	PORTAL	-	excluded:portal function	excluded:portal function
CLI	tag --tagDiagram	WRITE	E	-	excluded:ignored by INUBIT, tags every diagram of the owner	excluded:ignored by INUBIT, tags every diagram of the owner
CLI	export --exportWorkflowGroup	EXPORT	E	-	reachable:export_archives/default	reachable:export_archives/default
```

**Action key** (research D-2): the value of the tool's `action` input; otherwise of `view` or
`content`; otherwise `default`. `export_archives` uses `default` until US3 adds `content`, and
its rows then change to `workflows`.

## Rows

- **Entry granularity** (FR-001):
  - CLI: one command with one option, or one option value set such as `on|off`; written as the
    vendor writes it.
  - REST: one method plus the path template, with path parameters in braces and the query
    parameters left out (`DELETE /modules` for `?moduleName=`).
- **Scope**: all documented entries, including those the server already sends (features
  001–021) and the excluded portal entries. Today's entries are `reachable` with their tool.
- **States**: as in [data-model.md](../data-model.md) (`CoverageState`). An excluded row's reason
  is one line of at most 120 characters.

## Generated page

`docs/interface-coverage.md` is generated from the file and holds:

- one table per area;
- counts per state and version;
- the source line.

`InterfaceCoverageDocsTest` regenerates the page in memory and fails on any difference; the error
message names the command that rewrites the page (`mvn -q -Dcoverage.write=true test
-Dtest=InterfaceCoverageDocsTest`).

## Checks (`InterfaceCoverageTest`)

1. Every `CliCommand.OPTIONS` command/option pair and every `CliSessionCommand` has a row.
2. Every `RestEndpoint` value has a row.
3. Every `reachable:` and `covered:` tool is a registered tool. Every `reachable:` action is a
   value of the tool's `action`, `view` or `content` enum (the first of them its input schema
   has), or `default` for a tool that has none of the three.
4. Every row has both version states; `story` is set for every `planned` row.
5. Every story in `delivered-stories` has no row left `planned` (FR-004: the list shows what is
   open), except rows waiting on a live check, which carry `planned` plus a comment line
   `# waits: L-n`. Once all seven stories are delivered, no exception is left: every row is
   `reachable`, `covered`, `undocumented` or `excluded` (SC-001).
6. Every action of the new tools declares at least one row (`ActionSpec.entries`), and every
   declared row exists.
