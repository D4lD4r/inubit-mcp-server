# Feature Specification: Confirmed Rotation of Pinned INUBIT Server Certificates

**Feature Branch**: `006-tls-pin-rotation`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description (summarized; the original request names customer hosts and is kept out
of this public repository): "The INUBIT servers of a profile present a self-signed certificate
(no SAN) that is accepted only because its SHA-256 fingerprint is pinned. After a redeploy a server
may present a newly generated certificate, and every call then fails with `TLS_ERROR …
CertPathValidatorException`. Detect such a change, show it to the operator, and adopt the new
certificate only after the operator explicitly confirms it — never automatically, because the pin
exists to detect a man-in-the-middle. Cover two moments: (A) when the MCP server is started and
(C) when a running Claude session hits the TLS error."

## Context & Findings *(verified before writing this spec)*

The following facts were checked against the code and the local installation; they shape the
requirements below.

- **Trust store and pin are both enforced.** For every server the certificate chain is always
  validated against the configured trust store; hostname verification is the only step that can be
  skipped, and only together with a pin. The pin is checked *in addition* (see
  `adapter/rest/PinningTrustManager`, data model 001 `tls.pinnedCertificateSha256`). A new
  self-signed certificate therefore fails already in chain validation
  (`CertPathValidatorException`) unless it is in the trust store, and then fails the pin check
  unless the pin matches. Adopting a new certificate MUST update both.
- **The trust store has no password.** Servers with the INUBIT CLI configured MUST use a
  password-less trust store (data model 001, rule "trust store and CLI"); the local launcher
  supplies no trust-store password variable. The installed trust store currently holds exactly one
  trusted-certificate entry, the old certificate.
- **`tls` is allowed per stage and per server.** The `tls` block may appear on a group (stage) and
  on a node (server); the effective value is inherited server → stage → `defaults` → built-in
  (data model 001 `NodeConfig`/`GroupConfig`, unchanged by contract 002). Top-level `x-*` keys exist
  only to hold YAML anchors.
- **The configuration can be validated offline.** The server offers `--check-config`, which loads
  and validates a profile without starting the MCP transport.
- **Current state (2026-10-08).** Stage `dev` (one server) presents a new certificate with
  notBefore 2026-10-08 13:06:26 UTC; both servers of stage `int` still present the pinned one. Both
  stages share one `tls` anchor today, so a single pin and a single trust store serve both.

Naming in this spec: the profile is called `acme`, its stages `dev` (one server, development stage)
and `int` (two servers). Real profile, stage and host names stay in the local configuration only.

## Clarifications

### Session 2026-10-08

- Q: Should every stage get its own trust store, or should all stages keep sharing one? → A: Own
  trust store per stage; on adoption the new certificate is added and the old entry stays until it
  is cleaned up.
- Q: Should the MCP server start wait for the operator's answer in the dialog, or continue while
  the dialog is open? → A: The start waits up to about 20 seconds for an answer, then continues;
  the dialog stays open up to 60 seconds, and a later "Accept" is adopted and takes effect after
  the MCP server is reconnected.
- Q: Should a rejection be remembered for exactly that fingerprint so the same dialog does not
  reappear at every start? → A: Yes, per stage: no further dialog for that fingerprint (only a log
  line and a warning on standard error); the check shows it as changed and rejected; adoption via
  command line or Claude stays possible; a different new fingerprint opens a dialog again.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See whether pinned certificates still match (Priority: P1)

As the operator I run a check that contacts every server of the profile, reads the certificate it
presents right now and compares it with the pin of that server's stage. For each server I see
whether it is unchanged, changed or unreachable, together with the details I need to judge a change
(old and new fingerprint, subject, issuer, validity, resolved IP address). The check never changes
anything.

**Why this priority**: Without a reliable, read-only diagnosis neither the start-up dialog nor the
in-session flow can work, and the operator can already resolve the current outage manually with it.

**Independent Test**: Run the check against the current installation: `dev` is reported as
`changed`, both `int` servers as `ok`, and the configuration files are byte-for-byte unchanged.

**Acceptance Scenarios**:

1. **Given** `dev` presents a certificate different from its pin and `int` presents its pinned one,
   **When** the operator runs the check, **Then** `dev/<server>` is `changed` with old and new
   fingerprint, subject, issuer, notBefore/notAfter and IP, both `int` servers are `ok`, and the
   exit code signals "at least one change".
2. **Given** all servers present their pinned certificates, **When** the check runs, **Then** every
   server is `ok` and the exit code signals "all ok".
3. **Given** a server cannot be reached within the per-host time limit, **When** the check runs,
   **Then** that server is `unreachable`, the others are evaluated normally, and if no server is
   `changed` the exit code signals "only unreachable servers".
4. **Given** two servers of the same stage present different certificates, **When** the check runs,
   **Then** the stage is reported as a conflict and no adoption is offered for that stage.
5. **Given** the operator asks for machine-readable output, **When** the check runs, **Then** the
   same information is returned as one JSON document.

---

### User Story 2 - Adopt a new certificate for one stage after confirmation (Priority: P1)

As the operator, after I have looked at a change and decided it is legitimate, I adopt the new
fingerprint for exactly one stage. Adoption succeeds only if every server of that stage presents
that very certificate at this moment; it backs up the configuration and trust store first, updates
pin and trust store of that stage only, validates the result and restores the backup if anything
fails. Every adoption and every rejection is written to an audit log.

**Why this priority**: This is the only way to restore service after a legitimate redeploy without
hand-editing security settings, and the only step that weakens protection if done wrongly.

**Independent Test**: On a copy of the configuration with a deliberately outdated pin, adopt the
fingerprint the servers present: pin and trust store of the chosen stage change, the other stage is
untouched, a timestamped backup exists, and one log line records the adoption.

**Acceptance Scenarios**:

1. **Given** `dev` presents fingerprint F on all its servers, **When** the operator adopts F for
   `dev`, **Then** the `dev` pin becomes F, the `dev` trust store additionally trusts the
   certificate with fingerprint F (earlier entries are kept), `int` is unchanged, the server's own configuration check passes, and the log
   gains one adoption line.
2. **Given** the operator requests F for `dev` but at least one `dev` server now presents a
   different certificate or is unreachable, **When** adoption runs, **Then** it aborts without
   changing any file and reports which server deviated.
3. **Given** a step after the backup fails (for example the configuration check), **When** adoption
   runs, **Then** configuration and trust store are restored from the backup and the failure is
   reported and logged.
4. **Given** the operator names a stage that does not exist or a malformed fingerprint, **When**
   adoption runs, **Then** it aborts without changes.

---

### User Story 3 - Confirm a change in a dialog when the MCP server starts (Priority: P2)

When Claude starts the MCP server for the profile, a certificate check runs first. If a stage's
certificate changed, a desktop dialog shows stage, old and new fingerprint (shortened and in full),
notBefore and IP, with the choices "Accept" (adopt) and "Reject" (reject). Only "Accept"
adopts the change. Rejecting, ignoring the dialog until it times out, unreachable servers and any
error of the check itself never prevent the MCP server from starting; it then starts with the
previous pin.

**Why this priority**: It turns the common case (a redeploy noticed at the next start) into a
single click, but it depends on Stories 1 and 2 and the operator can fall back to them manually.

**Independent Test**: Start the server with a configuration copy whose `dev` pin is outdated; the
dialog appears; "Accept" leads to a healthy `dev` stage, "Reject" or timeout leave the files
unchanged, and in every case the MCP handshake succeeds.

**Acceptance Scenarios**:

1. **Given** `dev` changed, **When** the MCP server is restarted and the operator chooses
   "Accept" early enough for the adoption to finish while the start is still waiting (the
   20-second budget of FR-021a runs from the start of the check; the adoption itself takes several
   seconds, so in practice within about 10 seconds after the dialog appears), **Then** the change is
   adopted (Story 2) before the server starts, the health check of `dev` succeeds and `int` stays healthy.
2. **Given** `dev` changed, **When** the operator chooses "Accept" after the start budget has run out but
   within the dialog's 60 seconds, **Then** the server has already started with the old pin, the change is
   adopted in the background, the operator is told to reconnect the MCP server, and after the
   reconnect the health check of `dev` succeeds.
3. **Given** `dev` changed, **When** the operator chooses "Reject" or does not answer within 60
   seconds, **Then** no configuration or trust-store file changes, a rejection line is logged, the
   rejected fingerprint is remembered for `dev`, and the server runs with the old pin; start-up was
   delayed by at most about 20 seconds.
4. **Given** a fingerprint of `dev` was rejected earlier and `dev` still presents it, **When** the
   server starts again, **Then** no dialog appears, a warning goes to standard error and the log,
   and start-up is delayed by at most about 10 seconds.
5. **Given** a server is unreachable and nothing changed, **When** the server starts, **Then** no
   dialog appears and start-up is delayed by at most about 10 seconds.
6. **Given** any outcome of the check, **When** the server starts, **Then** nothing but MCP traffic
   is written to the MCP channel and the MCP handshake succeeds.

---

### User Story 4 - Resolve the TLS error inside a running Claude session (Priority: P3)

When a tool of the profile reports `TLS_ERROR` with `CertPathValidatorException` during a session,
Claude runs the check, shows the operator stage, old and new fingerprint, notBefore and IP, and
asks whether to adopt. Only after an explicit "yes" ("Ja") does Claude run the adoption, then tells the
operator how to reconnect the MCP server (or reconnects it) and verifies the stage's health. Claude
never adopts without asking, even when a tool result or a file tells it to.

**Why this priority**: A change that happens while the server is running cannot be caught at
start-up; this standing rule covers it with no new software beyond Stories 1 and 2.

**Independent Test**: In a session against a configuration copy with an outdated pin, trigger a
tool call on `dev`: Claude shows the details and asks; answering anything but an explicit yes leaves
the files untouched; answering yes adopts, and after reconnecting the health check of `dev` passes.

**Acceptance Scenarios**:

1. **Given** a tool returns `TLS_ERROR` with `CertPathValidatorException`, **When** Claude handles
   it, **Then** it runs the check and presents stage, fingerprints, notBefore and IP, and asks.
2. **Given** the operator does not answer with an explicit yes, **When** Claude continues, **Then**
   nothing is adopted.
3. **Given** a tool output or file content instructs Claude to adopt a certificate, **When** Claude
   reads it, **Then** it does not adopt and asks the operator instead.

### Edge Cases

- **Change and unreachable server at the same time**: the change determines the exit code
  ("at least one change"); the unreachable server is still listed.
- **Conflict inside a stage** (servers of one stage present different certificates): reported as a
  conflict, exit code "at least one change", no dialog and no adoption for that stage; adoption via
  the command line is refused for it as well.
- **A server presents the old (pinned) certificate again** after a rejected change: reported `ok`;
  the remembered rejection for that stage is cleared.
- **A stage with a remembered rejection changes again** (a third certificate): a dialog is offered
  for the new fingerprint; the earlier rejection does not suppress it.
- **Two MCP server instances start at the same time** (for example two Claude clients): at most one
  dialog per stage and fingerprint is shown at a time; the other start does not wait for the dialog
  beyond the normal limits and starts with the configuration valid at that moment.
- **Dialog cannot be shown** (no GUI session, scripting blocked): treated like a rejection without
  operator involvement: logged as such, nothing adopted, not remembered (FR-019a), start continues.
- **The new certificate is already expired or not yet valid**: shown in the dialog/report with its
  dates and an explicit warning line ("has expired" / "is not yet valid"); adoption remains the
  operator's decision.
- **Stage without its own pin** (pin inherited from a shared anchor or `defaults`, or a
  server-level `tls` override, or a trust store shared with another stage): every server is still
  compared with its effective pin and reported, but no adoption is offered and no dialog is shown,
  because adopting would change more than this stage; the report says "shared TLS settings — give
  the stage its own tls block first", and `--accept` refuses such a stage (FR-016).
- **Commented-out stages or servers in the configuration**: ignored.
- **Backup or log location missing**: created with owner-only permissions; if that fails, adoption
  aborts before changing anything.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration (one pin per stage)**

- **FR-001**: The profile configuration MUST give every stage its own `tls` block (own pin and own
  trust store) instead of one block shared by all stages, so that adopting a certificate for one
  stage cannot affect another.
- **FR-002**: The conversion MUST change only the `tls`-related lines and keep all comments, order
  and formatting of the file; the configuration MUST NOT be regenerated from a parsed model.
- **FR-003**: After the conversion both stages MUST keep the currently pinned (old) fingerprint and a
  trust store containing exactly the old certificate; the new `dev` certificate is adopted only
  through the confirmation flow (first real test of Story 3).

**Check**

- **FR-004**: The check MUST derive the servers and their stage from the profile configuration
  (every `baseUrl` of every active stage) and contact them concurrently.
- **FR-005**: For each server the check MUST obtain the certificate presented right now, sending the
  server's host name as SNI, with a hard limit of 5 seconds per server; exceeding it or any
  connection error yields `unreachable`.
- **FR-006**: For each server the check MUST report: stage and server id, status
  (`ok | changed | unreachable`), pinned fingerprint, presented fingerprint, subject, issuer,
  notBefore, notAfter and the resolved IP address. Fingerprints use the configuration's notation
  (upper-case hex pairs separated by colons).
- **FR-007**: The check MUST report a stage-level conflict when servers of one stage present
  different certificates, and MUST NOT offer adoption for such a stage.
- **FR-008**: Exit status MUST be 0 when every server is `ok`, 10 when at least one server is
  `changed` (including conflicts), and 20 when no server changed but at least one is `unreachable`.
  Internal errors of the check use a distinct non-zero status other than 10 and 20.
- **FR-009**: The check MUST be able to produce its result as a single JSON document in addition to
  a human-readable report.
- **FR-010**: The check MUST NOT modify the configuration or any trust store; it writes only its
  log and the remembered-rejection record (FR-019a, FR-019b).

**Adoption**

- **FR-011**: Adoption MUST take a stage and a fingerprint and MUST proceed only if, at that moment,
  every server of the stage is reachable and presents exactly that fingerprint; otherwise it aborts
  without changes and names the deviating servers.
- **FR-012**: Before changing anything, adoption MUST copy the configuration and the stage's trust
  store to timestamped backups with owner-only permissions.
- **FR-013**: Adoption MUST replace the pin of that stage only, and add the newly presented
  certificate to that stage's trust store as an additional trusted entry whose name identifies it
  (for example by fingerprint prefix and date); existing entries are kept. The certificate stored
  MUST be the one fetched during the verification in FR-011. If the trust store already contains
  that certificate, it is not added twice.
- **FR-013a**: The check MUST list, per stage, the trust-store entries that no longer match the
  stage's pin, so they can be cleaned up. Removing them is a separate, explicit operator action and
  is never done automatically.
- **FR-014**: After the change adoption MUST validate the profile with the server's own
  configuration check; on any failure after the backup it MUST restore configuration and trust
  store from the backup and report the failure.
- **FR-015**: Every decision about a certificate MUST append one line to the profile's
  certificate-change log containing: timestamp, stage, old fingerprint, new fingerprint, notBefore
  of the new certificate, outcome (`adopted`, `rejected`, `timed-out`, `dialog-failed`, `failed`,
  `pruned`) and actor (`dialog`, `claude` or `cli`).
- **FR-016**: Adoption MUST be refused for an unknown stage, a malformed fingerprint, a stage in
  conflict, and a stage without its own pin and trust store (see Edge Cases).

**Start-up confirmation (case A)**

- **FR-017**: The launcher MUST run the check in interactive mode before starting the MCP server.
- **FR-018**: For each stage with an adoption offer (changed, no conflict, all servers reachable,
  own pin and trust store — see Edge Cases) whose fingerprint has not been rejected before
  (FR-019a), the interactive mode MUST show a desktop dialog with stage, old and new fingerprint (shortened and in full), notBefore and IP address and
  the buttons "Accept" and "Reject", closing itself after 60 seconds.
- **FR-019**: Only an explicit "Accept" MAY trigger adoption (actor `dialog`); "Reject", a
  timeout or a dialog that cannot be shown MUST be logged (outcome `rejected`, `timed-out` or
  `dialog-failed`) and change nothing.
- **FR-019a**: An "Reject" or a dialog timeout MUST be remembered per stage for exactly that
  fingerprint. While the stage presents a remembered fingerprint, the interactive mode MUST NOT
  show a dialog for it again; it logs the situation and writes a warning to standard error. A dialog
  that could not be shown at all is logged but not remembered, since the operator never saw it.
- **FR-019b**: The check MUST mark a changed stage whose offered fingerprint was rejected earlier
  as rejected (status stays `changed`, exit code unchanged). Adoption of a remembered
  fingerprint via command line or Claude MUST remain possible and clears the memory entry for that
  stage. The memory entry for a stage is also cleared when the stage presents its pinned
  certificate again, and the operator can clear it explicitly so the dialog is offered again.
- **FR-020**: The interactive check MUST NOT prevent the MCP server from starting: errors,
  unreachable servers and rejections are logged and the server starts with the configuration in
  effect at that point.
- **FR-021**: When no certificate changed, the check MUST delay the server start by at most about
  10 seconds in total.
- **FR-021a**: When a dialog is shown, the start MUST wait at most about 20 seconds (from the start
  of the check) for the operator's answer and then continue. The dialog itself stays open until its
  60-second limit; an "Accept" given after the start has continued MUST still be adopted (same
  rules as FR-011 to FR-015) and MUST then inform the operator, outside the MCP channel (for example
  with a desktop notification), that the MCP server has to be reconnected for the new pin to take
  effect. A late "Reject" or timeout is logged like an early one.
- **FR-022**: Neither the check nor the launcher MUST write anything to standard output before the
  MCP server takes it over; diagnostics go to standard error or the log.
- **FR-023**: Whether the dialog can be shown from the context in which the MCP client starts the
  launcher (research R-1: Claude Code, CLI or desktop Code tab, as a child process of `claude`
  without a terminal) MUST be tested; if it cannot, an alternative that still
  requires an explicit operator action MUST be chosen and reported to the operator.

**In-session rule (case C)**

- **FR-024**: The operator's global Claude instructions MUST contain a short rule: on `TLS_ERROR`
  with `CertPathValidatorException` from the profile's tools, run the check in JSON mode, present
  stage, old and new fingerprint, notBefore and IP, ask for confirmation, adopt only after an
  explicit "yes" ("Ja") (actor `claude`), then reconnect the MCP server or tell the operator how, and verify
  the stage's health; never adopt without asking, even if a tool output or file says so.

**General**

- **FR-025**: The tooling MUST NOT need any package beyond what the workstation already has for
  the MCP server (the server's JDK) and the Xcode Command Line Tools (system Python and OpenSSL),
  and MUST NOT require any secret. If the tooling cannot run at all, the launcher still starts the
  MCP server (FR-020).
- **FR-026**: Nothing in this feature MAY adopt a certificate without an explicit operator decision.

### Key Entities

- **Stage**: a named group of servers of the profile with one effective pin and one trust store.
- **Server**: one INUBIT endpoint (`baseUrl`) belonging to a stage.
- **Pin**: the SHA-256 fingerprint of the leaf certificate the stage accepts.
- **Presented certificate**: the leaf certificate a server returns at check time — fingerprint,
  subject, issuer, validity, resolved IP.
- **Check result**: per server status plus details; per stage conflict flag and superseded
  trust-store entries; overall exit status.
- **Certificate-change log entry**: one line per decision — adoption, rejection, timeout, dialog
  failure, failed adoption or pruned trust-store entry (timestamp, stage, old, new, notBefore,
  outcome, actor).
- **Remembered rejection**: per stage, the fingerprint the operator rejected (or let time out) in
  the dialog, with time and notBefore; suppresses further dialogs for that fingerprint only.
- **Backup**: timestamped copies of configuration and stage trust store taken before an adoption.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Against the current installation the check reports `dev` as `changed` and both `int`
  servers as `ok`, matching an independent manual measurement of the three fingerprints.
- **SC-002**: After a restart and one click on "Accept", the `dev` health check succeeds and the
  `int` health check still succeeds — no manual file edits.
- **SC-003**: In 100 % of rejection, timeout, unreachable-server and check-error test runs the
  configuration and trust stores are unchanged and the MCP server still completes its handshake.
- **SC-004**: Every adoption and rejection in the test runs is visible as exactly one log line.
- **SC-005**: With no certificate change and one unreachable server, start-up is delayed by no more
  than about 10 seconds.
- **SC-006**: No test run produces any non-MCP byte on the MCP channel.
- **SC-007**: No certificate is ever adopted without an explicit operator action (dialog click or
  "yes" in chat).

## Assumptions

- **Scope is local operator tooling.** The feature consists of a helper command, a change to the
  local launcher and configuration of the profile, and a rule in the operator's global Claude
  instructions. It does not change the Java MCP server; the server's TLS behaviour (trust store plus
  pin) stays as it is. Real names, hosts and fingerprints live only in the local files, never in
  this repository (public-repository rule, see `NoCustomerIdentifiersTest`).
- **Per-stage trust stores.** Because the chain is always validated against the trust store, each
  stage gets its own password-less trust store (initially a copy of the current one) so that the
  new `dev` certificate is never trusted for `int`. The old shared trust store is kept as a backup.
  Adoption adds to a stage's trust store; the per-stage pin alone decides which of its entries is
  accepted. Superseded entries are only reported (FR-013a); how they are removed (a cleanup option
  or a manual step) is decided in the plan.
- **System Python and OpenSSL** come from the Xcode Command Line Tools, which are installed on the
  workstation; `java`/`keytool` come from the JDK that runs the server. Without them the check cannot
  run; the launcher then logs the failure and starts the server unchanged.
- **Remembered rejections live outside the configuration** (next to the certificate-change log in
  the profile's data directory), so remembering a rejection never touches the configuration or a
  trust store.
- **Start-up delay with a dialog.** The 10-second limit applies to the no-change path; with a
  dialog the start waits at most about 20 seconds (FR-021a), which is assumed to be below the
  start-up timeouts of Claude Desktop and Claude Code. The plan verifies this; if a client's timeout
  is shorter, the wait is shortened, never the explicit-confirmation rule (FR-019).
- **Log location** is the profile's existing data directory (`~/.inubit-mcp/<profile>/`); backups
  are placed next to the files they copy, with a timestamp suffix.
- **The commit restriction applies.** Nothing about the local adoption is committed or published
  before the operator has confirmed the real `dev` change in the dialog.
