# Quickstart: Validating Read-Only INUBIT 6.1 Support

How to prove the feature works. Offline scenarios need no INUBIT server; the live part needs the
person's approval in the chat (FR-020). Contracts: [configuration-delta](contracts/configuration-delta.md),
[mcp-tools-delta](contracts/mcp-tools-delta.md); decisions: [research.md](research.md).

## Prerequisites

- JDK 21, Maven 3.9; the repository builds offline: `mvn -q -o clean verify`.
- For the live part only: a 6.1 node reachable from this machine, a StartCLI client for it (6.1
  client with its Java runtime, or the 8.1.17 client), a password-less trust store and the pin of
  the node's certificate.

## Offline validation

```bash
mvn -q -o clean verify
mvn -q -o test -Dtest='V61*Test,VersionGate*Test,CapabilityMatrixTest,HealthServiceTest,DiagnosisServiceTest,ListNodesToolTest,ConfigValidatorTest,TlsHandshakeCheckTest,NoCustomerIdentifiersTest'
```

| Scenario (spec) | Expected |
|---|---|
| `versionLine: V6_1`, `"6.1.0.89-HF1"` load; `"6.1"` is refused (FR-002) | config tests green; error text names `V6_1` |
| `get_health` on a pinned 6.1 node with session fixtures (US1) | report with memory, counts, maintenance, `ready`/`threads`/`blockingQueue` as `NOT_AVAILABLE_ON_6_1`; WireMock records 0 requests |
| alive check negative / login refused / unreachable (US1-2, US1-6) | `status` `ERROR` / `UNKNOWN`; one login per 60 s |
| group with 8.1 and 6.1 nodes (US1-5) | each node by its own channel; within 10 s; 6.1 parts `TIMEOUT` when the fake session hangs |
| `find_processes` states, hanging, workflow, time range, cap, `Total: 0` (US2) | filtered, newest first, truncation marker at 10,000 rows, empty page |
| `tag` filter on 6.1 (US2-4) | `UNSUPPORTED_VERSION` naming `tag`, no launch |
| every unsupported tool on 6.1 (US3, SC-003) | `UNSUPPORTED_VERSION` before any launch, TLS check or REST request |
| contradicted 6.1 pin (US3-6) | `find_processes` refused, `get_health` runs |
| `AUTO` node, detection HTTP 404 vs. 401 (US3-7) | hint only for 404 |
| `check_artifacts` with module lookup (US3-8) | offline findings plus `MODULE_UNVERIFIED` with the 6.1 text, no launch |
| pin mismatch / untrusted certificate on the local TLS test server (US5-1, US5-2) | `TLS_ERROR`, no launch |
| host-name skip without client option (US5-4) | `TLS_ERROR` naming the option; `--check-config` warning |
| password channel (US5-7) | password only on stdin; not in argv or environment of the fake launch |
| five parallel calls on one 6.1 node (US5-10) | at most two launches at once; the rest wait, then `TIMEOUT` |
| production matrix vs. `RECONSTRUCTED.txt` (FR-005) | `V61RecordedFixturesTest` green |
| existing 8.1 suites (SC-005) | green, apart from the capability names and the 404 hint |

## Live recordings (FR-020, by hand, read-only)

Run only after the person has approved the recording in the chat; for a node classified
production, the person also confirms node and classification. Nothing here writes to INUBIT.

1. Configure the node in a local profile with `versionLine: V6_1` (never committed).
2. Record the catalogue cases with StartCLI only (password from the environment via stdin):

   ```bash
   RECORD_ALLOWED_GROUPS=legacy tools/record-fixtures.sh legacy node1 v61_help
   ```

   Cases: `v61_help`, `v61_version`, `v61_health_session`, `v61_ps_all`, `v61_ps_error`,
   `v61_ps_waiting`, `v61_ps_queued`, `v61_ps_limit5`; for US4 later `v61_export_group`,
   `v61_export_repository`.
3. Neutralize with `tools/neutralize.py` and the local maps; run
   `mvn -q -o test -Dtest=NoCustomerIdentifiersTest` (must run, not be skipped).
4. Answer the REC questions of research D-5, D-6 and D-10 in `docs/research/inubit-interfaces.md`
   (6.1 section): interactive mode through stdin; `uname` = server version; meaning of `DATE`;
   Processing rows in `ps -a`; rows dropped at `-l`; German labels.
5. Replace the reconstructed cases, remove them from `RECONSTRUCTED.txt`, flip the 6.1
   capabilities in `CapabilityMatrix.production()`, run `mvn -q -o clean verify`.

## Live smoke check (optional, after step 5, read-only)

With the approved node: `get_health` for the node (within 15 s, version and counts plausible),
`find_processes` with `states: [ERROR]`, and `query_logs` (expect `UNSUPPORTED_VERSION`). Record
the result in the "Validation record" section of this file.

## Validation record

*(filled in after the offline validation and, if approved, the live steps)*
