# INUBIT 9.0 fixtures

**Documentation-derived ("reconstructed").** No INUBIT 9.0 node has been recorded yet (feature
017, research D-10). Every case was derived from the recorded 8.1.17 fixtures (`../v8_1/`) and
the documented 9.0 differences (`docs/research/inubit-interfaces.md` §4). Tests open these files
only through `de.dadecker.inubit.mcp.testing.V90Cases`, which refuses a case without its
`<case>.origin` file.

`<case>.origin` states the provenance in its first line:

- `reconstructed <source> …` — derived from documentation and 8.1 recordings; not verified
  against a 9.0 node.
- `recorded <date> <version>` — written by `tools/record-fixtures.sh` with
  `RECORD_INUBIT_LINE=9.0` against a real 9.0 node.

`NOT_RECORDABLE.txt` lists the cases the recorder does not capture (writes, or server states
that cannot be provoked safely), each with its reason. `V90RecordedFixturesTest` lists the
recordable cases that are still `reconstructed`; it never fails the build.

| Case | Derived from | 9.0 change |
|---|---|---|
| `rest/system_info` | `../v8_1/rest/system_info.*` | `Version` = `9.0.7`, `ServerJDKVersion` = `21.0.5 - 64 bit`, `ServerJDKSpec` = `21` (9.0 bundles JDK 21) |
| `rest/healthcheck`, `rest/ready`, `rest/metrics`, `rest/log_systemLog`, `rest/log_systemLog_filtered`, `rest/model_models_owner`, `rest/modelByName_sample`, `rest/model_export_sample` | the 8.1 case of the same name | none |
| `rest/rest_auth_failed` (401), `rest/rest_forbidden` (403), `rest/rest_not_found` (404), `rest/rest_internal_error` (500), `rest/rest_unavailable` (503) | §4: JSON error body `{statusCode, message, path, timestamp}` | the message texts are invented |
| `cli/version` | `../v8_1/cli/version.*` | `CLI 9.0.7`, `Supported inubit Process Engine versions from 9.0.0 to 9.0.7.` |
| `cli/help` | §2.1 option list, layout of `../v6_1/cli/help.stdout` | 9.0 shows the help only with `-h`/`--help` |
| `cli/login_failed`, `cli/unreachable`, `cli/processErrorStart_unknown`, `cli/kill_unknown`, `cli/unknown_command` | the 8.1 case of the same name | `ibis.jar:9.0.7` in the stack frames; no lines of the SOAP client (removed in 9.0) |
| `cli/ps_all`, `cli/export_history_sample`, `cli/export_modules_sample`, `cli/import_created`, `cli/tag_ok`, `cli/processErrorStart_ok`, `cli/kill_ok` | the 8.1 case of the same name | none |

## Recording

`RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh --list` prints the catalogue (`V90_CASES` of the
script); `RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh <group> <node> <case>` records one case
of a non-production 9.0 node into this directory (StartCLI against `<baseUrl>`, each REST case
with the `Accept` of its adapter) and writes `<case>.origin` = `recorded <date> <version>`. The
StartCLI client is `RECORD_CLI_HOME`, else the `defaults.cliClients."9.0"` entry, else
`defaults.cliHome` (JDK: `RECORD_CLI_JAVA_HOME`, else the entry's `javaHome`, else
`defaults.cliJavaHome`). Every CLI case first checks that this client reports `CLI 9.0.*`
(`startcli -v`), every case with a node that the node reports 9.0; otherwise nothing is recorded.
`V90FixtureCatalogueTest` keeps catalogue ∪ `NOT_RECORDABLE.txt` = the files here = `V90Cases`.

## Administration cases (feature 022)

The 9.0 counterparts of the feature-022 administration cases (research D-26; tasks T002). A
coverage-list row becomes `reachable` for 9.0 only in the commit that adds its recording here,
together with its row in the table below. No case has been recorded yet; the 9.0 recordings wait
for the decision on the changed certificate of the 9.0 test server (feature 021).

- **Name**: `admin-<entry-slug>[-<variant>]`, the same name as the 8.1 case of the same command
  or endpoint (see "Administration cases (feature 022)" in `../v8_1/cli/README.md`).
- **CLI cases** (`cli/`): `<case>.stdout`, `<case>.stderr`, `<case>.exit`; a case of a stdin
  session also has `<case>.stdin`, the lines the session sent, with the password line replaced by
  `<password>`.
- **REST cases** (`rest/`): `<case>.http` (status and headers) and the body in the format the
  endpoint answered with (`<case>.json`, `<case>.xml`, …).
- **`<case>.origin`**, first line, one of:
  - `recorded <date> <version> neutralized` — recorded against a non-production 9.0 node, then
    neutralized; `tools/record-fixtures.sh` writes `recorded <date> <version>`, and the person who
    runs `tools/neutralize.py` appends ` neutralized` (for feature-022 cases this form is required);
  - `reconstructed <source>` — derived from `<source>` (documentation or the 8.1 recording);
    not verified against a 9.0 node.
- **Catalogue**: every case is listed in `V90_CASES` of `tools/record-fixtures.sh` or in
  `NOT_RECORDABLE.txt` (a case the recorder does not capture, with its reason), and is opened
  through `V90Cases`, as `V90FixtureCatalogueTest` requires.
- **Neutralization**: with `tools/neutralize.py`, then checked with `tools/check-identifiers.py`;
  the raw outputs are never committed. Every user, workflow, host and value in a case is
  fictitious, and every secret (password, configuration or module value, key material, e-mail
  in a listing) is replaced by a planted test secret.

| Case | `.origin` | Coverage-list entry | What it is |
|---|---|---|---|
