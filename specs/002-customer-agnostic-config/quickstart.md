# Quickstart & Validation: Customer-Agnostic Configuration

Validation scenarios for feature 002. Contracts: [configuration.md](contracts/configuration.md),
[mcp-tools-delta.md](contracts/mcp-tools-delta.md). Build and base setup as in
[001 quickstart](../001-inubit-mcp-mvp/quickstart.md).

## 1. Build

```bash
mvn -q clean verify
```

Expected: full suite green; jar `target/inubit-mcp-server-*.jar`; `java -jar … --version` works;
all classes of the server are in the new namespace:

```bash
unzip -Z1 target/inubit-mcp-server-*.jar | grep -E '(^|/)inubit/mcp/' | grep -vc '^de/dadecker/inubit/mcp/'
# expected: 0
```

`NoCustomerIdentifiersTest` (§5) also scans the entry names and text entries of `target/*.jar` when a
build left them there.

## 2. New customer profile (US1)

Create `~/.config/inubit-mcp/acme.yaml` from the neutral example in `docs/setup.md` with
`terminology: {group: {singular: Umgebung, plural: Umgebungen}, node: {singular: Knoten, plural: Knoten}}`
(a level's plural may equal its own singular; only the two levels must differ from each other).

```bash
export INUBIT_ACME_TEST_USERNAME=… INUBIT_ACME_TEST_PASSWORD=…
java -jar target/inubit-mcp-server-*.jar --profile acme --check-config
```

Expected: summary shows profile `acme`, terminology, nodes as "Umgebung test / Knoten node1", variable
names under `INUBIT_ACME_`; exit 0.

| # | Check | Expected |
|---|---|---|
| A1 | `tools/list` over stdio | every description starts with `[acme: …]`; no "stage"/"server" wording; "Umgebung"/"Knoten" used |
| A2 | `list_nodes` | `profile`, `terminology`, `groups[].nodes[]` with `id`, `group`, `node` |
| A3 | `get_health` against a test server | same behaviour as 001 V2; messages use "Knoten" |
| A4 | `list_inventory` without `inventory.owner` | per-node `NOT_CONFIGURED` naming `inventory.owner`; `get_health` still works |
| A5 | TLS with trust store + pin | as 001 (generic option) |

## 3. Two profiles side by side (US2)

Profiles `acme` and `globex`, both with group `test` / node `node1`, different credentials, both write-enabled
for a fake server (fake `startcli.sh`, fake REST; never a real server).

| # | Check | Expected |
|---|---|---|
| B1 | `--check-config` both | each lists only its own prefix (`INUBIT_ACME_…` / `INUBIT_GLOBEX_…`) |
| B2 | preview in `acme`, confirm code in `globex` | `CONFIRMATION_INVALID`; no execution |
| B3 | audit | records under `~/.inubit-mcp/acme/audit` and `~/.inubit-mcp/globex/audit`, each with its `profile` |
| B4 | parallel exports, kill one instance (SIGKILL), restart the other | the other's startup sweep does not touch `inubit-mcp-export-acme-*`; restarting `acme` removes its stale dir |

## 4. Migration (US3)

| # | Check | Expected |
|---|---|---|
| C1 | start with a 001-format file | exit 1; message lists `stages`→`groups`, `servers`→`nodes`, missing `profile`, prefix hint, old audit dir, migration guide |
| C2 | apply `docs/migration-001-to-002.md` incl. `credentials.envPrefix: INUBIT`, terminology Stage/Server | `--check-config` passes; same nodes; variable names identical to 001 |

## 5. Neutral product (US4)

```bash
# denylist kept locally, never committed (one regex per line)
INUBIT_MCP_DENYLIST="$HOME/.config/inubit-mcp/denylist.txt" mvn -q -Dtest=NoCustomerIdentifiersTest test
```

Expected: guard test green with the user's denylist (the denylist also covers the previous
namespace); 0 findings.

## 6. Live (read-only, after migrating the user's own profile)

```bash
INUBIT_MCP_PROFILE=<your-profile> INUBIT_LIVE_NODE=<group>/<node> mvn -q verify -Plive
```

Expected: all live tests pass as in 001.

## Validation log

### Run 2026-10-04 (T045): §1–§5 with fakes only

Setting: macOS, JDK 25 (runs the Java 21 build), Maven 3.9; JAR `inubit-mcp-server-0.1.0-SNAPSHOT.jar`.
No real INUBIT server, no real configuration or audit directory was used:

- **Homes**: temporary directories passed as `-Duser.home=…` (Java takes `user.home` from the account,
  not from `$HOME`), with `java.io.tmpdir` in the same scratch area; every run had a clean environment
  (`env -i`) holding only fictitious credential variables.
- **Profiles**: `acme` (terminology Umgebung/Umgebungen, Knoten/Knoten) and `globex` (default
  terminology), each with group `test` / node `node1`, write-enabled, CLI configured; `initech` for SC-001;
  the 001 example of `docs/migration-001-to-002.md` for §4.
- **Fake REST**: one small HTTPS server per profile (Python standard library, scratch only, not part of
  the repository) on `localhost`, serving the recorded fixtures of
  `src/test/resources/fixtures/v8_1/rest/`. It accepts only that profile's fictitious Basic credentials
  (401 otherwise) and presents a **self-signed certificate** (CN `selfsigned`, no subjectAltName), so
  every run went through the trust store, the pin and `disableHostnameVerification` (§2 A5).
- **Fake StartCLI**: `bin/startcli.sh` per profile; `-v` prints a version, `export` sleeps 300 s, every
  other command is recorded and fails. Nothing else is called.
- **MCP client**: a scripted stdio client (`initialize`, `tools/list`, `tools/call`) in place of Claude
  Code, whose registration was not changed.

| # | Check | Result |
|---|---|---|
| §1 | `mvn -q clean verify` | **pass**: 1,262 tests, 0 failures, 0 errors, 0 skipped (1,252 before T043 + 10 in `LiveTargetTest`); also green with `-DargLine=-Djdk.virtualThreadScheduler.parallelism=1` |
| §1 | `java -jar … --version` | **pass**: `inubit-mcp-server 0.1.0-SNAPSHOT`, exit 0 |
| §1 | classes outside `de/dadecker/inubit/mcp/` (namespace check) | **pass**: 0 (293 classes in the namespace) |
| §2 | `--profile acme --check-config` | **pass**: profile `acme`, `Terminology: Umgebung/Umgebungen, Knoten/Knoten`, `Umgebung test:` / `Knoten test/node1`, variables `INUBIT_ACME_TEST_USERNAME` / `_PASSWORD`, audit directory `<home>/.inubit-mcp/acme/audit`, warnings for the disabled host-name check (with pin) and the missing `inventory.owner`; exit 0 |
| A1 | `tools/list` | **pass**: 8 tools (write enabled); all 8 descriptions start with `[acme: ACME – …]`; 0 occurrences of "stage(s)"; 0 of "server(s)" other than "INUBIT server"/"MCP server"; "Umgebung" 29×, "Knoten" 52× in descriptions and schemas; the remaining "group"/"node" are field names (`node`, `group`) or INUBIT's own diagram/module group |
| A2 | `list_nodes` | **pass**: `profile {name, description}`, `terminology`, `groups[0].nodes[0]` with `id test/node1`, `group test`, `node node1`; no credential prefix |
| A3 | `get_health test/node1` against the fake server | **pass**: reachable, ready, version 8.1.17, `unavailable []`, 229 ms (cold start); unknown target → `TARGET_UNKNOWN` "Configured Umgebungen and Knoten: …" |
| A4 | `list_inventory` without `inventory.owner` | **pass**: per-node `NOT_CONFIGURED` "Knoten test/node1 has no inventory.owner …", nextStep "set inventory.owner for test/node1 or in defaults"; `get_health` in the same session works |
| A5 | trust store + pin with a self-signed certificate | **pass**: correct pin → health OK; wrong pin → `TLS_ERROR … (CertificateException)` per part, nothing reachable; without `tls` → `TLS_ERROR … (SunCertPathBuilderException)` |
| B1 | `--check-config` for both | **pass**: `acme` lists only `INUBIT_ACME_…`, `globex` only `INUBIT_GLOBEX_…` (both sets exported in the same environment; no "unmatched variable" warning for the other profile's variables) |
| B2 | preview in `acme` (process kept running), code redeemed in `globex` | **pass**: `CONFIRMATION_INVALID`, "nothing was changed"; no StartCLI call in either fake CLI |
| B3 | audit | **pass**: `<home>/.inubit-mcp/acme/audit/audit-2026-10.jsonl` (`CHALLENGE_ISSUED`, `profile: acme`) and `<home>/.inubit-mcp/globex/audit/audit-2026-10.jsonl` (`REFUSED CONFIRMATION_INVALID`, `profile: globex`); directories `rwx------`, files `rw-------`; no password in audit files or stderr |
| B4 | parallel module exports, SIGKILL `acme`, restart `globex`, then `acme` | **pass**: `inubit-mcp-export-acme-<pid>-…` and `inubit-mcp-export-globex-<pid>-…` existed side by side; StartCLI got `-u <user>` and the password on stdin only; after SIGKILL of `acme` and a normal stop of `globex`, only the `acme` directory was left; restarting `globex` left it alone; restarting `acme` removed it. Observation: while the killed JVM was still an unreaped zombie of its (scripted) parent, it counted as running and `acme`'s own restart kept the directory too (safe side); a real MCP client reaps its children |
| C1 | server start and `--check-config` with the 001 file of the migration guide | **pass**: exit 1 both; message lists `stages`→`groups`, `servers` (at `stages[0].servers, stages[1].servers`)→`nodes`, missing `profile`, the `envPrefix: INUBIT` hint, the old audit directory and the guide |
| C2 | steps 2–7 of `docs/migration-001-to-002.md` applied by hand | **pass**: `--profile acme --check-config` exit 0, output identical to the guide's expected text (same 3 nodes, `INUBIT_DEV_…`, `INUBIT_QA_…`, `INUBIT_QA_NODE2_PASSWORD`), also while the old `config.yaml` was still in the directory; `list_nodes` shows the same node ids and the terminology stage/server |
| §5 | `NoCustomerIdentifiersTest` with the local `.denylist` (after `mvn verify`, so `target/*.jar` was scanned too) | **pass**: 0 findings |
| live | `INUBIT_LIVE_SERVER` set, `mvn test -Plive -Dtest=HealthLiveTest` | **pass**: fails at once with the hint to `INUBIT_LIVE_NODE` / `INUBIT_MCP_PROFILE`; without both variables the live test is skipped; no configuration was read |

### SC-001 (new profile from the documentation)

A new fictitious profile `initech` was set up in a clean temporary home by following `docs/setup.md`
literally: §1 prerequisites (`java -version`, `mvn -v`), §3 the minimal example saved as
`~/.config/inubit-mcp/initech.yaml`, §5 steps 1–4 (`openssl s_client` → PEM, fingerprint, `keytool`
password-less PKCS12, `tls` block with pin and `disableHostnameVerification`), §4 the two
`INUBIT_INITECH_DEV_…` variables, §6 `--profile initech --check-config` (Result: OK), §7 the stdio
start with `--profile initech` and a first `get_health dev` → reachable, ready, 8.1.17.

- **Time**: 17 s wall-clock from the first prerequisite command to the first successful `get_health`
  (scripted; build excluded). The build (§2, `mvn clean verify`, offline, 1,262 tests) took 71 s on the
  same machine. Even with generous reading time for a person this stays well under the 15 minutes of
  SC-001; no source code was read or changed.
- **Doc gaps found and fixed in `docs/setup.md`**:
  1. The configuration directory was never created: §5 step 1 writes the PEM into
     `~/.config/inubit-mcp/`, which fails on a clean home. §3 now starts with
     `mkdir -p ~/.config/inubit-mcp && chmod 700 ~/.config/inubit-mcp` and says that the file is saved
     as `<name>.yaml`, named like its `profile.name`, so that `--profile <name>` finds it.
  2. Leftover level words of feature 001: "Configure the stage (or server)" (§5 step 4), "per stage or
     server", "production stages", "stage id" and similar in §1 and §3 now say group/node, matching the
     neutral YAML keys.
