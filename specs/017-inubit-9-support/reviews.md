# Reviews: 017 INUBIT 9 Support

Producer/reviewer process: after every Spec Kit artefact a `rubber-duck` reviewer, after every
implementation phase and before the push a `code-review` reviewer. Each finding is verified before
it is accepted.

## Spec — round 1 (rubber-duck, 2026-11-06)

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | Version-keyed setting has no key, schema or validation rules | Accepted | FR-003 now names `defaults.cliClients`, key grammar `<major>.<minor>`, `home` required, `javaHome` optional, invalid vs. warning cases; edge case added |
| 2 | BLOCKING | Client check is cached per node and `get_health` probes in parallel with `/system/info`; an upgraded `AUTO` node would keep the 8.1 client's result | Accepted | Verified: `NodeVersions.clientProbed` stores one client result per node, `HealthService` submits the probe before the version is known. New FR-017; US2 scenario 2 extended |
| 3 | BLOCKING | `V9_X` choosing the sole 9.x entry could run a 9.0 client against 9.1 with false confidence | Accepted (simplified) | `V9_X` now always uses the version-less client; no entry is chosen without a minor (FR-005, FR-011) |
| 4 | BLOCKING | FR-015 changes result shapes of `list_nodes`/`get_health`, contradicting Out of Scope and SC-003 | Accepted (modified) | `get_health` dropped from FR-015 (its client part already reports the check); `list_nodes` gains one additive field; Out of Scope and SC-003 say so |
| 5 | SHOULD | "`get_health` without contacting any node" impossible; undetected `AUTO` display unclear | Accepted | FR-015: "no additional node contact", undetected nodes show the fallback and "decided at runtime" |
| 6 | SHOULD | Partial overrides can pair a 9.0 client with JDK 17 | Accepted (modified) | Precedence written out per field in FR-003; `--check-config` warns about the JDK major for 8.1/9.0 only (other versions have no documented JDK); US2 scenario 5 |
| 7 | SHOULD | "No configured client" conflicts with the `defaults.cliHome` fallback; refusal timing ambiguous | Accepted | FR-005 is a decision table (no client → `CLI_UNAVAILABLE`; excluding range → existing client-check refusal; unknown/`V9_X` → version-less); selection after version resolution; US2 scenarios 3–4 |
| 8 | SHOULD | Servlet-address warning only for "detected" 9.x contradicts SC-004 | Accepted | FR-010 and US3 scenario 2 use the effective version (pin, detection, observation) |
| 9 | SHOULD | 9.0 `startcli -v` output unverified; live check does not require keeping the evidence | Accepted | New SC-006: captured output and REST responses become contract fixtures before 0.8.1 |

Verdict after round 1: no blocking finding open.
