# Backlog

Ideas and follow-ups that are not specified yet. An entry becomes a feature through
`/speckit-specify`, which gives it its own spec, plan and review.

## Trust a new server certificate on first use (builds on feature 006)

**Problem.** A new user gets a profile file but also needs a trust store and a pinned certificate
for every group (stage) whose servers present self-signed certificates. Today these are created by
hand: fetch the certificate with `openssl`, compare its fingerprint, import it with `keytool`
(docs/setup.md, section 5). The alternative is to copy the files from a colleague.
`inubit-cert-check` (feature 006) only covers a certificate that *changes* while a pin already
exists. It does not cover the very first connection.

**Idea.** When a group has a `baseUrl` but no trust store or pin, the server fetches the
certificate on the first connection and shows it to the person:
- host, issuer, validity and SHA-256 fingerprint;
- if the profile already holds an expected pin, the server compares against it automatically.

Only after the person explicitly confirms does the server write the trust store and the pin, with
a backup of the profile file and an audit record. If the person declines, the group stays blocked
with `TLS_ERROR` and a hint. Handing over a profile then needs only the YAML file.

**Constraints.**
- **Never automatic.** The confirmation must come from the person, not from the AI assistant. The
  pin exists to detect a man-in-the-middle, and if the model could confirm through a tool call,
  that protection would be gone. Use the start dialog of feature 006 or MCP elicitation (the server
  asks the user through the client UI). Do not use a tool with a confirmation code that the model
  could return itself.
- **Real security needs a second source.** The person should compare the fingerprint with a value
  from a second source: the pin the colleague passed on, or the value the operators publish.
  Without that comparison this is trust on first use, which only protects later connections.
- Consistent with the constitution (TLS always on, every change audited) and with the
  feature-006 rule "never adopt automatically".

## Feature 019: Local knowledge per profile (to be specified right after feature 018)

**Problem.** Operating knowledge about a customer's integrations is needed to answer support
questions, but must never enter the repository. Example: "the fallback channel reads its target
directory from a repository configuration file, and server and login from the Partner
Management". Today this knowledge lives only in the operator's head or in old chat transcripts,
and research results (what was found out in a support case) are lost after the session.

**Idea.** Every profile can have a local knowledge store outside the repository. The server
offers it to the MCP client, and the client can keep research results there for later sessions.

**Decided so far (operator, 2026-11-09, clarification of feature 018).**
- The local knowledge is bound to the **profile** (like the workspace, the audit directory and
  the other per-profile directories).
- That a local knowledge exists and can be used to keep research results belongs to the **core
  of the server**: the server's instructions announce it for every profile, independent of any
  single tool such as the partner tool of feature 018.

**Open for the spec.** How the knowledge is offered (MCP resource, instructions, a read tool) and
bounded; how research results are written (a tool that writes only into the profile's knowledge
directory, never to INUBIT) and how that fits Principle I (read-only by default) and Principle VII
(audit); what must never be stored there (secrets, Principle II); format and structure (e.g. one
Markdown file per topic); and how the knowledge is kept separate between profiles.

## Masked export of system diagrams into the workspace (builds on feature 018)

**Problem.** Feature 018 reads the Partner Management in memory and answers questions about the
current state. It keeps no history, so a question like "when did the host of partner X change on
the production stage?" cannot be answered.

**Idea.** `export_artifacts` exports system diagrams (`--exportWorkflowType systemdiagram`) into
the workspace with the masking of feature 018 (at least as strict as the workspace redaction), so
that the workspace history records partner changes over time. Decided in the clarification of
feature 018 (2026-11-09): not part of 018, a later feature.

## Further read access to INUBIT (interface comparison of 2026-11-12)

**Problem.** A comparison of the vendor documentation (StartCLI and REST, 8.1 and 9.0) with the
commands and endpoints the server uses showed read access that is documented but not offered by
any tool. Questions such as "when does the license expire?", "how many workflow threads are
configured?" or "which drivers are installed?" cannot be answered today.

**Idea.** Each row is a candidate of its own; rows can be combined into one feature where they
share a tool. The order is the expected benefit for operations.

| # | Candidate | StartCLI | REST | Notes |
|---|---|---|---|---|
| 1 | License: expiry date, licensed threads | `license -td`, `license -ts` | `GET /license` | REST needs the role System Administrator; fits `get_health` |
| 2 | Workflow threads | `threads showNumberOfWorkflowThreads`, `privilegedWorkflows showThreadsPercentage`, `privilegedWorkflows showWorkflowList` | – | CLI only; fits `get_health` |
| 3 | Users and logins | `finger <login>`, `user <name>`, `-who`, `-nwho` | `GET /user/users?type=…` | personal data: decide what is returned |
| 4 | Installed drivers and plug-ins | `library --list -t driver\|plugin` | – | CLI only |
| 5 | Running processes on 8.1 and 9.0 | `ps -f 'workflow=… AND tag=…'`, `-ne`, `-nx`, `-nw`, `-nq` | – | today `ps` is used on 6.1 only; 8.1 and 9.0 read the queue log |
| 6 | Server configuration | `dbconnectiontolerance show` | `GET /configurations` | large JSON that can hold secrets: only with masking and bounds |
| 7 | Blocked run-time data | `process listBlocked <operationId>`, `process validateExport <file>` | – | needs the operation id of an earlier run-time data export |
| 8 | XSLT 1.0 usage | `fetchXSLT1.0 -ef <file>` | – | writes a file on the computer of the server, like `trace --download` |
| 9 | Module details without an export | – | `GET /model/nodeByModel/{model}/{nodeId}`, `GET /model/nodeByName/{diagram}/{module}` | `nodeByName` is gone from the 9.0 documentation; `get_inventory_item` may cover this already |
| 10 | Process metadata | `export --exportMetadata` | `GET /process/metadata/{name}` | metadata export was left out on purpose in feature 004 (research D-8) |
| 11 | Check an IS Connector | – | `GET /isconnector?workflowName&moduleName&type=validateWorkflow` | read only; `POST /isconnector` starts a workflow and is not meant here |

**Constraints.**
- Read only: no row may send a command form that changes the server. Each new StartCLI command
  enters the allowlist in `CliCommand` with exactly the read options named here.
- None of the commands is checked against a real system yet. Output formats and exit codes must be
  recorded on a non-production server before a parser is written (as for the earlier features).
- Results are bounded and masked like the existing read tools; rows 3 and 6 need their own
  decision on what may be returned.
- 6.1 has no REST API: a candidate that exists only in REST is announced from 8.1 on.

**Not planned.** Tasks, Webapps, Reports and the process documentation endpoints (`/task/…`,
`/webapp/…`, `/report/…`, `/process/processes/…`) are portal functions and need frontend users.
Write access found in the same comparison (`maintenance on|off`, `processdelete error`,
`tag --tagSetActive|--tagDelete`, user, library and configuration changes) is not part of this
entry.
