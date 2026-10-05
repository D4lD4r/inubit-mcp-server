# Implementation Plan: Customer-Agnostic Configuration

**Branch**: `002-customer-agnostic-config` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-customer-agnostic-config/spec.md`

## Summary

Turn the feature-001 INUBIT MCP server into a customer-neutral product configured per customer:

1. **Namespace**: rename the previous namespace → `de.dadecker.inubit.mcp` and the coordinates
   → `de.dadecker:inubit-mcp-server`, as a pure refactoring step first.
2. **Configuration format v2**: a required `profile` (name, description), optional `terminology`
   (display names of the two levels), `credentials.envPrefix` (default `INUBIT_<PROFILE>`) and neutral
   keys `groups`/`nodes`.
3. **Neutral tool contract**: `list_nodes`, fields `group`/`node`, `TARGET_UNKNOWN`, the new
   `NOT_CONFIGURED`. Descriptions and messages are rendered from templates with the profile's
   terminology and prefixed with the profile.
4. **Per-profile isolation**: credential variables, audit directory + `profile` in audit records,
   export temp directories + scoped stale sweep, and profile selection via `--profile`.
5. **No customer defaults**: `inventory.owner` must be configured.
6. **Neutral repository**: test data, fixtures, tools, docs and the 001 specs are neutralized with a
   fixed mapping; a local-denylist guard test supports release checks.
7. **Migration**: 001-format files are rejected with an exact list of what to change, plus a migration
   guide.

Behaviour and safety guarantees of 001 are unchanged.

## Technical Context

**Language/Version**: Java 21 (unchanged)

**Primary Dependencies**: unchanged (MCP Java SDK 2.0.1, Jackson 3 YAML, Logback). No new dependency.

**Storage**: per-profile local files: config, audit `~/.inubit-mcp/<profile>/audit`, temporary export
directories in `java.io.tmpdir`.

**Testing**: unchanged stack. New tests cover:
- the profile and terminology loader and validator;
- template rendering of all 8 descriptions and 16 schemas (no unknown placeholder, no residual
  "stage"/"server" for custom terms);
- prefix-based credentials;
- two-profile isolation (credentials, codes, audit, sweep);
- 001-format detection;
- `NoCustomerIdentifiersTest`, which uses a local denylist and is skipped without one.

**Target Platform**: unchanged.

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: unchanged (SC-002 of 001); rendering happens once at startup.

**Constraints**:
- breaking changes to the config format and tool field names are allowed (spec assumption), but
  must be documented;
- one profile per process;
- git history is not rewritten.

**Scale/Scope**:
- ~266 Java files moved;
- ~120 contract sites renamed;
- ~1,000 test lines with customer data neutralized;
- 16 schemas;
- 8 tool descriptions.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ FR-017 keeps all guards | ✅ The write rules are unchanged. Confirmation codes, caches and credential guard stay per process, and there is one profile per process (FR-013). The two-profile test proves that one profile cannot accept another's code. |
| II. Secrets Stay Out | ✅ | ✅ Credentials are still read only from the environment. The new prefix stays under the same rules: scrubbing, never in argv, and the variable names reported without values. The repository guard keeps customer identifiers out, but the denylist itself is never committed (D-11). |
| III. Test-First | ✅ | ✅ Every new behaviour gets a test written first. The rename step is validated by test parity: all 1,019 tests stay green (D-1). |
| IV. Use-Case-Oriented Tools | ✅ | ✅ Same 8 tools. Descriptions become clearer for each customer (terminology plus profile prefix). |
| V. Adapter Isolation & Version Compatibility | ✅ | ✅ Terminology lives in the domain; rendering happens in `mcp`; messages get their terms through `Wiring`. PackageBoundaryTest moves to the new BASE. |
| VI. Bounded, Structured Output | ✅ | ✅ Field renames only; all bounds unchanged. |
| VII. Observability & Auditability | ✅ | ✅ Audit records gain `profile`, and each profile gets its own audit directory (D-7). |
| Tech constraints | ✅ | ✅ No new dependencies; single artifact; English artifacts. |
| Workflow | ✅ | ✅ Feature 002 on its own branch, built on 001. Docs (setup, tools, migration guide) change in the same change as the behaviour. |

**Result**: PASS, no violations.

## Project Structure

### Documentation (this feature)

```text
specs/002-customer-agnostic-config/
├── plan.md                    # this file
├── research.md                # D-1…D-13, spike T-S1
├── data-model.md              # delta to 001
├── quickstart.md              # validation scenarios A–C, US4 checks, live
├── contracts/
│   ├── configuration.md       # format v2 (supersedes 001's)
│   └── mcp-tools-delta.md     # renamed names, terminology, profile, audit
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks
```

### Source Code (after the rename)

```text
pom.xml                                         # de.dadecker:inubit-mcp-server, main.class de.dadecker…Main
src/main/java/de/dadecker/inubit/mcp/
├── config/                                     # ProfileConfig, GroupConfig, NodeConfig, EffectiveNodeConfig,
│                                               # ProfileSection/TerminologyConfig/CredentialsConfig,
│                                               # Format001Detector (migration messages), ConfigLoader (+ --profile)
├── domain/model/                               # GroupId, NodeId, Target.Group/Node, NodeSummary, NodeResult,
│                                               # Terminology, ProfileInfo, ErrorCode (+TARGET_UNKNOWN, NOT_CONFIGURED)
├── application/                                # unchanged services; messages via Terminology
├── adapter/                                    # CredentialGuard (prefix), CliResources (profile-scoped names/sweep)
├── mcp/                                        # McpServerFactory renders templates + profile prefix (+ instructions)
│   └── tools/                                  # ListNodesTool (was ListServersTool), templated DESCRIPTIONs
└── infra/                                      # AuditLog (+profile, node/group fields)
src/main/resources/
├── schemas/                                    # list_nodes.*.json (renamed), group/node fields, {placeholders}
├── de/dadecker/inubit/mcp/build.properties
└── logback.xml                                 # FQNs updated
src/test/java/de/dadecker/inubit/mcp/           # mirrors main; + security/NoCustomerIdentifiersTest,
                                                # + config/ProfileLoaderTest, mcp/TerminologyRenderingTest,
                                                # + isolation/TwoProfilesTest
docs/
├── setup.md                                    # neutral example profile, multi-profile setup
├── tools.md                                    # new names, terminology note
├── migration-001-to-002.md                     # NEW
└── live-tests.md                               # INUBIT_LIVE_NODE, INUBIT_MCP_PROFILE
tools/                                          # record-fixtures.sh / anonymize.py without customer defaults
```

**Structure Decision**: same single module. Sequencing:

1. **R0, rename**: pure refactoring.
2. **Config v2, profile and isolation**: covers US1 and US2.
3. **Contract rename and templating**: covers US1.
4. **Migration detection and guide**: covers US3.
5. **Neutralization and guard**: covers US4.
6. **Validation.**

Each step is reviewed and committed separately (Creator/Reviewer, as in 001).

## Complexity Tracking

No constitution violations.
