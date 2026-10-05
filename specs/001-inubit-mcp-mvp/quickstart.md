# Quickstart & Validation Guide: INUBIT MCP Server MVP

Runnable scenarios that prove the feature works end to end. Contracts:
[mcp-tools.md](contracts/mcp-tools.md), [configuration.md](contracts/configuration.md).
Data shapes: [data-model.md](data-model.md).

## Prerequisites

- JDK 21 or newer on `PATH` (`java -version`)
- Maven 3.9+ (`mvn -v`)
- For CLI-backed tools: a local INUBIT client installation matching the servers' patch level (8.1.17) (`<cliHome>/bin/startcli.sh`)
- Network access to at least one **non-production** INUBIT 8.1.17 server (DEV or QA) and a personal account
  with rights to read logs/monitoring/models and "CLI login access"

## 1. Build and run the offline test suite

```bash
mvn clean verify
```

Expected: all unit and contract tests pass without any INUBIT server; the shaded JAR is at
`target/inubit-mcp-server-<version>.jar`.

## 2. Configure

Create `~/.config/inubit-mcp/config.yaml` following [configuration.md](contracts/configuration.md)
with stage `dev` and one server `inubit01`, write disabled. Provide the credentials as environment
variables (e.g. in `~/.zshrc`):

```bash
export INUBIT_DEV_USERNAME='jdoe'
export INUBIT_DEV_PASSWORD='…'
java -jar target/inubit-mcp-server-*.jar --check-config
```

Expected: summary lists `dev/inubit01 (read-only, cli: available, username ← INUBIT_DEV_USERNAME,
password ← INUBIT_DEV_PASSWORD)`; exit code 0; no credential value in the output.

Negative checks (each must exit 1 with a clear message and no secret in output):
- `unset INUBIT_DEV_PASSWORD` → error names `INUBIT_DEV_INUBIT01_PASSWORD` / `INUBIT_DEV_PASSWORD`
- add `password: x` to a server in the YAML → error "credentials belong in environment variables"
- set `write.confirmation: CLIENT` on a stage with `production: true`

Precedence check: `export INUBIT_DEV_INUBIT01_PASSWORD='wrong'` → `--check-config` reports the
server-specific variable as source; a subsequent health check reports `AUTH_FAILED`.

## 3. Register with Claude Code

```bash
claude mcp add inubit -- java -jar "$PWD/target/inubit-mcp-server-<version>.jar"
```

Start Claude Code from a shell that has the `INUBIT_*` variables. `/mcp` shows `inubit` connected
with 6 tools (write tools hidden while no server has write access).

## 4. Validation scenarios

| # | Story | Prompt to the assistant | Expected outcome |
|---|---|---|---|
| V1 | P1 | "Which INUBIT systems do you know?" | `list_servers` lists stages with their servers (`dev/inubit01`, …) |
| V2 | P1 | "Is dev up?" | `get_health` → reachable, ready, maintenance flag, version, load figures (or `unavailable: load` with reason) within 5 s |
| V3 | P1 | add an unreachable server `dev/bogus` (`baseUrl: https://127.0.0.1:9`), ask "Health of all systems?" | `dev/bogus` reported `UNREACHABLE`, other servers reported normally, total ≤ timeout + 1 s |
| V4 | P2 | "Show failed processes on dev in the last 24 hours" | `find_processes(states=[ERROR], since=PT24H)` → instances with processId, workflow, module, since |
| V5 | P2 | "Why did process <id> fail?" | assistant combines `find_processes` (module, `since`) with `query_logs(logType=systemLog, processId=…)` (matched on `workflowId`/`globalPId`) or `query_logs(logType=systemLog, workflow=…, since=<a few minutes before the instance's since>)` and names module (from `find_processes`) + error message (from systemLog) |
| V6 | P2 | "Show the last 500 systemLog errors" | first page (≤ limit), `total`, `nextOffset`; result within size limit |
| V7 | P3 | "List technical workflows in group GRP-41 on dev" | `list_inventory(kind=DIAGRAM, type=technical, group=GRP-41)` → names match the Workbench |
| V7a | P3 | "Which XSLT Converter modules are not used by any workflow on dev?" | `list_inventory(kind=MODULE, type='XSLT Converter')`, all pages, items with `workflowCount: 0` and `usageComplete: true` (first call ~10–15 s, then cached, `collectedAt` set) |
| V8 | P3 | "Show the version history of <workflow> on qa" | `get_inventory_item` per server: versions with check-in user/time/comment/tags and `active` match the Workbench |
| V9 | P4 | (write disabled) "Restart process <id> on dev/inubit01" | tool not offered / `WRITE_DISABLED`; audit file contains a `REFUSED` record |
| V10 | P4 | enable `write.enabled: true` (confirmation SERVER), restart a process in ERROR | 1st call → preview + `confirmationCode`; 2nd call with code → `EXECUTED`, `stateAfter` ≠ ERROR; three audit records (`CHALLENGE_ISSUED`, `PENDING` written before the CLI call, `EXECUTED`) |
| V11 | P4 | reuse the same code, or use it after 5 min | `CONFIRMATION_INVALID`; audit `REFUSED` |
| V12 | P4 | restart a process that is not in ERROR | `PRECONDITION_FAILED` with actual state |
| V13 | P4 | "Kill process <id> on prod/inubit01" (production, no opt-in) | `PRODUCTION_PROTECTED` regardless of `write.enabled`; audit `REFUSED` |

## 5. Security checks (SC-006)

```bash
# while a CLI-backed tool runs (e.g. during V7a or V9), in another terminal:
ps -ef | grep -i startcli
```

Expected: no password visible in any process argument list.

```bash
# the password goes to grep through a file descriptor, not as a process argument
grep -rlF -f <(printf '%s\n' "$INUBIT_DEV_PASSWORD") ~/.inubit-mcp/ target/surefire-reports/ \
  || echo "clean"
```

Expected: `clean`.

## 6. Optional live tests

```bash
INUBIT_LIVE_SERVER=dev/inubit01 mvn verify -Plive
```

Runs the read-only live tests (`HealthLiveTest`, `DiagnosisLiveTest`, `InventoryLiveTest`)
against the named server; refuses to run if its stage is classified `production: true`. There is
no automated write live test: restart and kill against a real server are manual checks that need
the user's approval per action (T115, see `docs/live-tests.md`).

## Validation log 2026-10-03

T124, read-only. Shaded JAR `0.1.0-SNAPSHOT` over stdio, driven by a minimal JSON-RPC client
(protocol `2025-11-25`) instead of Claude Code, whose registration was left untouched. Real
configuration (`dev/node1`, `qa/node1`, `qa/node2`; write disabled everywhere; CLI
available), credentials from the shell profile (`zsh -ic`). Only counts, timings and outcomes are
recorded. **No `restart_process` / `kill_process` call reached a real server**: V9–V12 were not
run (they need the user's approval, T115), V13 used a fake configuration whose servers point to
`https://127.0.0.1:9`, and the real audit directory stayed empty.

| Step | Result | Measured |
|---|---|---|
| §1 build | PASS | `mvn clean verify` green, 986 tests, 68 s |
| §2 `--check-config` | PASS | 3 servers, read-only, `cli: available`, variable names only; exit 0 |
| §2 missing password | PASS | error names `INUBIT_DEV_NODE1_PASSWORD` / `INUBIT_DEV_PASSWORD`; exit 1 (fake credentials, no network) |
| §2 `password:` in YAML | PASS | "credentials belong in environment variables"; exit 1; value not printed |
| §2 `CLIENT` on production | PASS | "write.confirmation CLIENT is not allowed on a production stage"; exit 1 |
| §2 precedence | PASS (config part) | `--check-config` reports `INUBIT_DEV_NODE1_PASSWORD`. The live `AUTH_FAILED` health check was **not** run: it would cost a failed login on a personal account; covered offline (`GetHealthToolTest`, `CredentialGuardTest`) |
| §3 tools | PASS | `tools/list`: the 6 read-only tools (write tools hidden) |
| V1 `list_servers` | PASS | 2 stages, 3 servers, `writeEnabled: false`, `cliAvailable: true`, `AUTO`; 16 ms |
| V2 `get_health` | PASS | `dev` 440 ms, `qa` (2 servers) 373 ms; all reachable, `OK`, ready, no maintenance, 8.1.17, `load` present, nothing unavailable |
| V3 unreachable server | PASS | config copy plus `dev/bogus` (`https://127.0.0.1:9`): `UNREACHABLE`, the 3 real servers normal; 468 / 195 / 89 ms |
| V4 `find_processes(ERROR, PT24H)` | PASS | DEV total 319, page 20 (all `ERROR`, with workflow and module), `nextOffset` 20, 153 ms; QA 1 + 3, 357 ms |
| V5 diagnosis | PASS | newest DEV `ERROR` instance: `query_logs(systemLog, processId, ERROR)` → 1 entry with the error message, 418 ms; module from `find_processes`. A `since` equal to the instance's `since` finds nothing (systemLog filters on `startTime`) → guidance in tools.md fixed |
| V6 systemLog errors | PASS | total 3,919, page 84 of `limit` 100 (`truncated`, cut by the 50,000-char limit: result 49,788 chars), `nextOffset` 84, 583 ms (SC-007) |
| V7 technical diagrams of one group (DEV) | PASS (shape) | 61 diagrams, all `technical` / the requested group, `collectedAt` set, 228 ms; name comparison with the Workbench: T125 |
| V7a XSLT Converter modules (DEV) | **FINDING F1** → fixed by T126, see the re-run below | 678 modules; first call 8.6 s, cached 4 ms with the same `collectedAt`. But **none** of the 678 has `workflow`: of 1,630 modules only connector types carry `WorkflowName` in the StartCLI module export (e.g. Web Services Connector 154/157, HTTP 33/37), while XSLT Converter 0/678, Workflow Connector 0/317, Assign 0/52. So "no `workflow`" does not mean "unused" for non-connector modules; the V7a question cannot be answered this way (see below) |
| V8 version history (QA) | PASS (shape) | a technical diagram present on both QA servers: 3 versions each, newest first, tags on 1, `active` present, 22 modules, `collectedAt`, `truncated: true` because a check-in comment > 200 chars; 5.1 s (history export). Unknown name → `NOT_FOUND` with 2 `similarNames` per server, 22 ms. Workbench comparison: T010 / T125 |
| V9–V12 | not run | need the user's approval per action (T115) |
| V13 production refusal | PASS | fake config (`dev/fake` write-enabled, `prod/inubit01` `production: true`, both `https://127.0.0.1:9`, fake credentials, no CLI): 8 tools listed; `kill_process` and `restart_process` (with a code) on `prod/inubit01` → `PRODUCTION_PROTECTED`; 2 audit records `REFUSED` |
| §5 process list | PASS | 274 `ps` samples during two module exports (DEV, QA), 93 with a running StartCLI JVM; 0 occurrences of either password in any argument list |
| §5 files | PASS | `~/.inubit-mcp/`, `target/surefire-reports/` (incl. live-test reports), all captured server stderr logs and results of this validation: 0 occurrences of the passwords or of `base64(user:password)` |
| §6 live tests | PASS | `dev/node1`, `qa/node1`, `qa/node2`: 3/3 live tests each (health ≈ 450–460 ms; module list 6.4–8.2 s, cached 2 ms; diagram detail 4.4–5.3 s) |

**SC-002** (target: one environment ≤ 5 s, all ≤ 10 s with one unreachable):

| Measurement | Time |
|---|---|
| `get_health(dev)` / `get_health(qa)` (first call, incl. version detection) | 440 ms / 373 ms |
| all 3 real servers, 3 runs | 200 / 87 / 88 ms |
| all 3 real servers + 1 unreachable (V3), 3 runs | 468 / 195 / 89 ms |
| offline: 10 HTTPS servers in 5 stages, one hanging 30 s, default `timeout` PT5S (`HealthOverviewPerformanceTest`) | 5.1 s (9 healthy, 1 `TIMEOUT`) |

**SC-001** (target: first health check in under 15 minutes following docs/setup.md): followed
setup.md in a clean temporary home (`-Duser.home`), stage `dev` only: prerequisites, build (68 s),
minimal configuration, credential check (variable count only), TLS steps of section 5 (certificate
fetched with `openssl`, fingerprint equal to the published pin, password-less trust store with
`keytool`), `--check-config` (OK), first `get_health` over stdio (438 ms, reachable, OK, 8.1.17).
Mechanical time 71 s; with reading the guide well below 15 minutes. Gaps found and fixed in
setup.md: tool list in section 7 (only two tools named), `openssl`/`keytool` missing from the
prerequisites, which part of the `openssl` fingerprint output to paste, the harmless "variable
matches no configured stage" warning, and an overview of the remaining settings (timeouts,
limits, log level, audit directory).

**Finding F1 (V7a) — resolved by T126 (user decision option A):** INUBIT 8.1's module export
sets `WorkflowName` only for connector modules (the workflow a connector is bound to), so a
missing `workflow` did not mean "not used". Module usage is now derived from the module nodes of
every technical workflow (`modelByName`, joined with the connectors' `WorkflowName`); results
carry `workflows`, `workflowCount` and `usageComplete`.

Re-run after T126 (same day, read-only, shaded JAR over stdio, real configuration):

| Step | Result | Measured |
|---|---|---|
| build | PASS | `mvn clean verify` 3× and once with `-Djdk.virtualThreadScheduler.parallelism=1`: 1,010 tests, 0 failures, about 69 s each |
| analysis (curl, read-only) | — | DEV: 392 technical workflows, 4,730 module nodes, all matched a module of the owner by name; 0 duplicate module names among 1,630; node type = `tw` + plugin type in every case; 222 modules used by more than one workflow |
| V7a XSLT Converter modules (DEV) | PASS | `usageComplete: true`; 678 modules: **630 used, 48 unused** (matches the independent analysis); up to 28 workflows per module; first call 12.7 s (module export + usage index), then cached |
| V7a XSLT Converter modules (QA, stage) | PASS | `usageComplete: true` on both servers; 9.0 s for both; 515–518 of the sampled modules used per server |
| live test `dev/node1` (extended `InventoryLiveTest`) | PASS | module list incl. usage index 9.4 s (cached repeat 5 ms); first XSLT page: 98 used, 2 unused; detail of a used XSLT module: 1 workflow, 2 versions, nothing unavailable, 4.4 s |

## Walkthrough checklist (T125, prepared — run with the user)

Prerequisites: the JAR registered in Claude Code (`claude mcp add --scope local inubit -- java
-jar <jar>`), Claude Code started from a shell with the `INUBIT_*` variables, write disabled.
Only MCP tools are used during the measurement; the user does not open the Workbench.

1. **SC-003 — 5 real failure cases** (DEV or QA): the user picks at least five instances in
   `ERROR` (or lets `find_processes(states=[ERROR], since=PT24H)` list them). For each, the user
   asks "Why did process <id> fail?". Recorded per case: failed workflow, failing module and error
   message named by the assistant, and whether the user confirms them (pass ≥ 4 of 5).
2. **SC-004 — time to answer**: the user first states the manual baseline (minutes per case with
   the Workbench). Per case, the time from sending the question to the assistant's answer is
   taken (stopwatch or transcript timestamps). Pass: every case < 2 minutes; record the median
   against the baseline.
3. **SC-008 — inventory comparison**: one workflow present on `qa/node1` and `qa/node2`
   (the user names it), asked as "Compare the version history of <workflow> on qa". The user
   compares field by field with the Workbench on both servers: version numbers, check-in user and
   time, comments, tags, `active`. Pass: 100% match. Also confirm V7 (diagram names of a group)
   and T010 (the three DEV diagrams of spike S-6b).
4. **F1 / T126 check**: the user names one XSLT Converter module that is used in a workflow and
   one that is not (Workbench "where used"); `get_inventory_item(kind=MODULE)` must list the
   same workflows (`workflows`, `usageComplete: true`) and an empty list for the unused one.
5. Record the results (counts, times, pass/fail; no business names) in this validation log.
