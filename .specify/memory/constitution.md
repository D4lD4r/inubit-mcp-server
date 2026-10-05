# INUBIT MCP Server Constitution

## Core Principles

### I. Safe by Default (NON-NEGOTIABLE)

The server MUST start in read-only mode. Any tool that changes state on an INUBIT system
(deploy, import, delete, start/stop/suspend processes, user or configuration changes) MUST:

- be disabled unless write access is explicitly enabled in configuration, per target environment;
- be annotated as destructive / non-idempotent via MCP tool annotations so clients can require
  confirmation;
- be refused for environments classified as `production` unless that environment's configuration
  explicitly opts in, independent of the global write flag;
- validate all inputs before any call to INUBIT and never pass unvalidated input to a shell.

Rationale: an LLM-driven client can invoke tools autonomously. Mistakes against an integration
platform propagate to business processes, so safety is a property of the server, not a hope about
the client.

### II. Secrets Stay Out of the Conversation (NON-NEGOTIABLE)

Credentials, tokens, and passwords MUST be supplied only through configuration (environment
variables, config files, or an OS/secret store), never through tool parameters. Secrets MUST NOT
appear in tool results, error messages, logs, or CLI command lines visible to other processes
(use files, environment, or stdin where the INUBIT CLI permits it). Error output from REST
responses and CLI runs MUST be scrubbed of credential material before it is returned.

Rationale: everything a tool returns ends up in the model context and chat transcript.

### III. Test-First (NON-NEGOTIABLE)

TDD is mandatory: a failing test is written and observed to fail before the production code that
makes it pass (Red → Green → Refactor). Specifically:

- Unit tests run without a live INUBIT instance; the REST and CLI adapters are replaced by fakes
  or recorded fixtures.
- Every adapter has contract tests against recorded INUBIT responses / CLI outputs for each
  supported INUBIT version.
- Every safety rule from Principle I and every secret-scrubbing rule from Principle II has
  dedicated tests.
- Integration tests against a real INUBIT test server are optional, opt-in, and MUST never target
  an environment classified as `production`.

Rationale: INUBIT environments are scarce and shared; fast, offline tests are the only reliable
feedback loop, and the safety guarantees are only as good as their tests.

### IV. Use-Case-Oriented Tools

MCP tools MUST be designed around tasks an operator or developer performs (for example "diagnose a
failed process instance", "list deployed modules"), not as a 1:1 mirror of REST endpoints or CLI
commands. Each tool MUST have a precise description, a typed input schema with documented
constraints, and a documented output shape. The tool set MUST stay small enough to be useful to a
model; adding a tool requires a stated use case in a spec.

Rationale: models choose tools by description; a large, low-level surface degrades tool selection
and pushes orchestration work into the conversation.

### V. Adapter Isolation & Version Compatibility

All access to INUBIT MUST go through dedicated adapters (REST adapter, CLI adapter) behind
interfaces owned by the domain/tool layer. Tool logic MUST NOT depend on HTTP or process details.

- REST is the preferred channel; the CLI is used where REST does not offer the capability.
- INUBIT 8.1.x is the supported baseline. Differences for 9.x MUST be isolated in
  version-specific adapter code selected by configuration or detected server version, never
  scattered through tool logic.
- CLI invocations MUST use argument arrays (no shell interpolation), explicit timeouts, exit-code
  evaluation, and a tested output parser.

Rationale: the planned 9.x upgrade must be an adapter change, not a rewrite.

### VI. Bounded, Structured Output

Tool results MUST be structured (JSON-serializable with a stable shape) and bounded in size.
Large results (logs, exports, long lists) MUST be paginated, truncated with an explicit marker,
or written to a file whose path is returned. Failures MUST be returned as actionable error
results (what failed, likely cause, next step), not as raw stack traces.

Rationale: model context is limited; oversized or unstructured output wastes it and hides the
relevant facts.

### VII. Observability & Auditability

The server MUST log in structured form to stderr (stdout is reserved for the MCP stdio
transport). Every state-changing tool invocation MUST produce an audit record containing
timestamp, target environment, tool name, sanitized parameters, and outcome. Log levels MUST be
configurable; secrets are never logged (Principle II).

Rationale: when an automated client changes an integration platform, operators must be able to
reconstruct what happened.

## Technology & Security Constraints

- Language: Java, using a current LTS release (21 or newer). Framework and build tool choice
  (for example the official MCP Java SDK, optionally via Spring AI; Maven or Gradle) is decided in
  the implementation plan and MUST be justified there.
- Transport: MCP stdio is required. Additional transports (for example Streamable HTTP) MAY be
  added only via a spec that addresses authentication of MCP clients.
- Distribution: the server MUST be runnable as a single executable artifact (for example a fat
  JAR) with documented configuration.
- TLS verification for REST calls MUST be on by default; disabling it requires an explicit,
  per-environment configuration flag and produces a warning at startup.
- Dependencies MUST be pinned and kept minimal; each runtime dependency added must be justified
  in the plan.
- All SDD artifacts (constitution, specs, plans, tasks) and code are written in English.

## Development Workflow & Quality Gates

- Spec-Driven Development with Spec Kit is the mandatory workflow: specify → (clarify) → plan →
  tasks → implement. Every feature lives in its own branch with its artifacts under `specs/`.
- No production code without a spec and plan that it traces back to.
- A change is mergeable only if: all tests pass, the build is reproducible, the plan's
  Constitution Check is satisfied or deviations are recorded in its Complexity Tracking table,
  and the change has been reviewed.
- Commits are small and descriptive; SDD artifacts are committed alongside the code they govern.
- User-facing documentation (setup, configuration, tool reference) is updated in the same change
  that alters behavior.

## Governance

This constitution supersedes other project practices. Every plan MUST include a Constitution
Check against these principles, and every review MUST verify compliance. Deviations are allowed
only when documented with justification in the plan's Complexity Tracking table.

Amendments are made via `/speckit-constitution`, recorded with a Sync Impact Report, reviewed,
and committed. Versioning follows semantic versioning: MAJOR for removing or redefining a
principle, MINOR for adding a principle or materially expanding guidance, PATCH for
clarifications and wording.

**Version**: 1.0.0 | **Ratified**: 2026-10-01 | **Last Amended**: 2026-10-01
