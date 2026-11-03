# Feature Specification: Stages Whose Servers Present Different Certificates

**Feature Branch**: `014-stage-distinct-certs`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description (translated from German): "I need an additional configuration that
allows the servers of a stage to present different certificates."

## Context & Findings *(verified before writing this spec)*

The following facts were checked against `main` (18344e2); they shape the requirements below.

- **Feature 006 assumes one certificate per stage.** The certificate tooling (`inubit-cert-check`)
  compares every server with its effective pin, but it reports a stage as a **conflict** as soon as
  its reachable servers present more than one certificate, and then offers no adoption for it
  (006 FR-007, FR-016). This happens even when every server presents exactly the certificate it is
  pinned to.
- **Server-level pins already exist, but block adoption.** The MCP server accepts a `tls` block on a
  server (node) as well as on a stage (group); the effective value is inherited
  server → stage → `defaults` (data model 001). The tooling treats any server-level pin or trust
  store as "shared TLS settings" (`pinSource: server`, `ownTls: false`): such a stage gets no
  adoption offer, no start-up dialog, and `--accept` / `--prune` refuse it.
- **One pin per server at runtime.** The MCP server checks the chain against the trust store and
  then compares the leaf with exactly one pinned fingerprint per server (`PinningTrustManager`).
  The trust store may hold several certificates; the pin decides which one is accepted.
- **Adoption, rejection memory and the change log are keyed by stage** (006 FR-011 to FR-019b), and
  the operator's in-session rule (006 FR-024) calls `--accept <stage> <fingerprint>`.

Consequence: a stage whose servers legitimately use different certificates (for example a cluster
in which every node generates its own self-signed certificate on redeploy) can be configured today
only with server-level pins, and then loses every part of the confirmed-rotation workflow of
feature 006. This feature adds an explicit, opt-in stage setting for that case.

Naming in this spec: the profile is `acme`, its stages `dev` (one server) and `int` (two servers,
`int/a` and `int/b`). Real profile, stage and host names stay in the local configuration only.

## Clarifications

### Session 2026-11-03

- Q: How is the expected certificate bound in a marked stage — one expected fingerprint per server,
  or a set of fingerprints allowed for every server of the stage? → A: One expected fingerprint per
  server; a server accepts only its own, never another server's certificate.
- Q: What does one adoption decision cover in a marked stage — one server, or all changed servers
  of the stage? → A: One server per decision; the operator names stage and server, and other
  servers of the stage need not be reachable.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Declare and verify a stage with per-server certificates (Priority: P1)

As the operator I mark stage `int` as one whose servers may present different certificates and
record the expected certificate for each of its servers. The MCP server accepts this
configuration, connects to `int/a` and `int/b` with their respective certificates, and the
certificate check reports both servers as `ok` without a conflict.

**Why this priority**: Without a valid configuration and a conflict-free check the stage is either
unusable or permanently reported as broken; everything else builds on this.

**Independent Test**: With a configuration copy in which `int/a` and `int/b` present different
certificates and the stage is marked accordingly, the configuration check passes, both health
checks succeed, and the certificate check reports `int` as `ok` with no conflict and exit status 0.

**Acceptance Scenarios**:

1. **Given** `int` is marked as allowing distinct certificates and each server's expected
   certificate is recorded, **When** the operator runs the configuration check, **Then** it passes.
2. **Given** that configuration and both servers presenting their expected certificates, **When**
   the certificate check runs, **Then** `int/a` and `int/b` are `ok`, the stage shows no conflict,
   and the exit status signals "all ok".
3. **Given** that configuration, **When** `int/a` presents the certificate expected for `int/b`,
   **Then** the MCP server refuses the connection to `int/a` and the certificate check reports
   `int/a` as `changed`.
4. **Given** a stage that is **not** marked, **When** its servers present different certificates,
   **Then** the behaviour of feature 006 is unchanged: conflict, no offer, refusal of adoption.
5. **Given** a marked stage in which an https server has no expected certificate, **When** the
   configuration check runs, **Then** it fails and names the stage and the server.

---

### User Story 2 - Adopt a new certificate in a stage with per-server certificates (Priority: P1)

After a redeploy `int/b` presents a new certificate while `int/a` is unchanged. The certificate
check reports `int/b` as `changed` with an adoption offer. After confirming, I adopt the new
certificate for `int/b`; only the expected certificate of `int/b` changes, the stage's trust
store additionally trusts the new certificate, and the change is backed up, validated and logged
exactly as in feature 006.

**Why this priority**: Rotation is the reason the pinning tooling exists; a stage that cannot be
rotated without hand-editing security settings is the problem this feature solves.

**Independent Test**: On a configuration copy where `int/b`'s expected certificate is outdated,
run the check (offer for `int/b`), adopt it, and verify that only the expected certificate of
`int/b` changed, `int/a` and `dev` are untouched, a backup exists, the stage's trust store gained
one entry, and one log line records the adoption with the server named.

**Acceptance Scenarios**:

1. **Given** `int/b` presents fingerprint F and `int/a` its expected certificate, **When** the
   check runs, **Then** `int` is `changed`, `int/b` carries the offer F, and `int/a` is `ok`.
2. **Given** that offer, **When** the operator adopts F for `int/b`, **Then** only the expected
   fingerprint of `int/b` becomes F, the trust store of `int` additionally trusts F (earlier
   entries are kept), `int/a` and `dev` are unchanged, the configuration check passes, and the
   change log gains one line naming stage `int` and server `int/b`.
3. **Given** the operator adopts F for `int/b` but `int/b` no longer presents F or is unreachable,
   **When** adoption runs, **Then** it aborts without changing any file and says so.
4. **Given** `int/b` presents F and `int/a` is unreachable, **When** the operator adopts F for
   `int/b`, **Then** the adoption succeeds; `int/a` is neither contacted for the decision nor
   changed.
5. **Given** both `int/a` and `int/b` changed, **When** the operator adopts the offer of `int/b`
   only, **Then** `int/a` keeps its old expected fingerprint and is still reported as `changed`.
6. **Given** a step after the backup fails, **When** adoption runs, **Then** configuration and trust
   store are restored and the failure is reported and logged.

---

### User Story 3 - Confirm per-server changes at start-up and in a Claude session (Priority: P2)

The start-up dialog and the in-session rule of feature 006 also work for marked stages: the dialog
and Claude's question name the stage **and** the server, and only an explicit "Accept" or "yes"
("Ja") adopts.

**Why this priority**: Convenience on top of Stories 1 and 2; the operator can fall back to the
command line.

**Independent Test**: Start the MCP server with a configuration copy in which `int/b` is outdated:
a dialog names `int/b`; "Accept" leads to healthy `int/a` and `int/b`; "Reject" or timeout leave
all files unchanged and remember the rejection for `int/b` only. In a Claude session a TLS error on
`int/b` leads to a question naming `int/b` and to no change without an explicit yes.

**Acceptance Scenarios**:

1. **Given** `int/b` changed, **When** the MCP server starts, **Then** a dialog for `int/b` shows
   stage, server, old and new fingerprint, notBefore and IP, with the time limits of feature 006;
   if `int/a` changed as well, it gets its own decision.
2. **Given** the operator rejected F for `int/b`, **When** the server starts again and `int/b`
   still presents F, **Then** no dialog appears for `int/b`, and a different change on `int/a` is
   still offered.
3. **Given** a tool reports `TLS_ERROR` for a server of a marked stage, **When** Claude follows the
   operator's rule, **Then** it shows the server-level details and asks before adopting.

### Edge Cases

- **Marked stage with one server**: allowed; behaves like an unmarked stage with an own pin, but
  through the per-server workflow.
- **All servers of a marked stage present the same certificate**: allowed; no conflict, no warning.
- **One server changed, another unreachable**: the unreachable server is listed as `unreachable`;
  the changed server is still offered and can be adopted on its own (FR-010).
- **A server presents the certificate expected for another server of the same stage**: reported as
  `changed` for that server, with a note naming the server that expects this certificate; it is
  offered like any other change, so the operator decides (FR-010a).
- **Several servers changed to the same new certificate**: each server is a separate offer and a
  separate decision.
- **Marking removed while server-level pins stay**: the stage falls back to the behaviour of
  feature 006 (shared TLS settings, no offer) — no silent change of pins.
- **Server-level pin or trust store inherited from a shared anchor or `defaults`** inside a marked
  stage: reported, but no adoption for that server, because adopting would change more than that
  server; the report says which setting is shared.
- **Trust store of the marked stage used by another stage**: no adoption for the stage (as in
  006 FR-016).
- **http servers** in a marked stage: skipped as in feature 006; they need no expected certificate.
- **Pruning**: a trust-store entry is superseded only if no server of the stage currently expects
  it; an entry still expected by any server is never listed for removal.
- **Remembered rejections**: kept per server for marked stages; cleared when that server presents
  its expected certificate again or when the operator forgets it explicitly.
- **Old state and log files of feature 006** (keyed by stage only) remain readable; existing entries
  of unmarked stages keep their meaning.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration**

- **FR-001**: The profile configuration MUST offer a stage-level setting that declares that the
  servers of that stage may present different certificates. It is off by default; when it is off,
  every behaviour of feature 006 is unchanged.
- **FR-002**: In a marked stage every https server MUST have exactly one expected fingerprint of
  its own, recorded for that server; a server accepts only its own expected certificate, never one
  expected for another server of the stage. The stage itself has no stage-wide expected
  fingerprint.
- **FR-003**: The marked stage MUST keep exactly one trust store of its own that holds the
  certificates of all its servers; the chain of every server is validated against it, and the
  expected fingerprint(s) decide which certificate is accepted.
- **FR-004**: The configuration check MUST reject: the setting outside a stage (on `defaults` or a
  server), a marked stage with an https server that has no expected fingerprint of its own (also
  when one would be inherited from the stage or `defaults`), a stage-wide expected fingerprint on a
  marked stage, a server-level trust store in a marked stage, a malformed fingerprint, and
  hostname verification disabled without an expected fingerprint for that server. Each error names
  stage and server.
- **FR-005**: At runtime the MCP server MUST accept, for each server of a marked stage, only that
  server's expected certificate (FR-002), and MUST log at start which stages are marked.

**Check**

- **FR-006**: For a marked stage the check MUST NOT report a conflict when its servers present
  different certificates; each server is judged against its own expected fingerprint with the
  statuses `ok | changed | unreachable` of feature 006.
- **FR-007**: The human-readable and the JSON report MUST show for a marked stage that it is marked
  and, per changed server, the offer (old and new fingerprint, subject, issuer, validity, IP). The
  JSON fields of feature 006 MUST keep their names and meaning; new information is added only.
- **FR-008**: The exit statuses of feature 006 (0, 10, 20, distinct internal-error status) MUST keep
  their meaning; a marked stage with a changed server counts as "at least one change".

**Adoption**

- **FR-009**: Adoption for a marked stage MUST follow 006 FR-011 to FR-015 (verify at that moment,
  backup with owner-only permissions, change only what is in scope, add the verified certificate to
  the stage's trust store, keep existing entries, validate with the configuration check, restore on
  failure, log every decision).
- **FR-010**: In a marked stage one adoption decision MUST cover exactly one server: the operator
  names stage, server and fingerprint; adoption verifies only that server (it must be reachable and
  present exactly that fingerprint at that moment) and changes only that server's expected
  fingerprint plus the stage's trust store. Other servers of the stage need not be reachable and
  are not changed. For unmarked stages the stage-level adoption of feature 006 stays as it is.
- **FR-010a**: When the presented certificate of a server is the one expected for another server of
  the same stage, the check MUST say so in the server's details; the offer and the adoption rules
  are otherwise unchanged.
- **FR-011**: Every change-log line about a marked stage MUST name the server concerned in addition
  to the fields of 006 FR-015.
- **FR-012**: Adoption MUST be refused for an unknown stage, an unknown server or a server of
  another stage, a server named for an unmarked stage, a stage named without a server for a marked
  stage, a malformed fingerprint, a server whose expected fingerprint is not recorded on the server
  itself (for example taken from a shared anchor), and a stage whose trust store is shared with
  another stage.
- **FR-013**: Pruning MUST list as superseded only trust-store entries that no server of the marked
  stage currently expects; removal stays an explicit operator action.

**Start-up dialog and in-session rule**

- **FR-014**: The start-up dialog of feature 006 MUST offer each changed server of a marked stage as
  its own decision, with stage and server named, within the same time limits (006 FR-021,
  FR-021a), and only "Accept" may adopt; an "Accept" adopts that one server only.
- **FR-015**: Remembered rejections for a marked stage MUST be kept per server and fingerprint and
  suppress only the dialog for that server and fingerprint.
- **FR-016**: The operator's in-session rule and the documentation of the check MUST be updated so
  that Claude presents server-level details for marked stages and uses the adoption form of FR-010;
  Claude still never adopts without an explicit "yes" ("Ja").

**General**

- **FR-017**: Nothing in this feature MAY adopt a certificate without an explicit operator decision,
  and nothing in it MAY weaken the checks for unmarked stages.
- **FR-018**: User-facing documentation (configuration reference, setup, check tool, CHANGELOG) MUST
  describe the setting in the same change; examples and test configurations MUST stay
  customer-agnostic (`NoCustomerIdentifiersTest`).

### Key Entities

- **Stage**: a named group of servers with one own trust store; now either *uniform* (one expected
  certificate, feature 006) or *marked* (servers may present different certificates).
- **Server**: one INUBIT endpoint of a stage; in a marked stage it has its own expected
  fingerprint (FR-002).
- **Expected certificate**: the SHA-256 fingerprint a server is allowed to present — the stage's pin
  in a uniform stage, the server's own fingerprint in a marked stage.
- **Adoption offer**: per stage (uniform) or per server (marked): the presented fingerprint the
  operator may adopt.
- **Remembered rejection**: per stage (uniform) or per server (marked) and fingerprint.
- **Certificate-change log entry**: as in feature 006, extended by the server for marked stages.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A stage whose two servers present two different certificates can be configured,
  passes the configuration check, and both servers' health checks succeed — no conflict reported.
- **SC-002**: After one redeploy of a single server of a marked stage, the operator restores
  service with one confirmed adoption and no manual edit of configuration or trust store, in under
  2 minutes from seeing the error.
- **SC-003**: In 100 % of test runs, an adoption in a marked stage changes the expected fingerprint
  of the named server only and leaves every other server and stage unchanged.
- **SC-004**: In 100 % of test runs with a server presenting a certificate it is not allowed to
  present (including another server's certificate of the same stage), the connection is refused
  and the check reports `changed`.
- **SC-005**: Every existing check, adoption and dialog test of feature 006 passes unchanged for
  unmarked stages.
- **SC-006**: No certificate is ever adopted without an explicit operator action.

## Assumptions

- **Opt-in per stage.** The distinction is declared explicitly in the configuration rather than
  inferred from what the servers present, so that an unexpected second certificate in a uniform
  stage keeps being reported as a conflict (possible man-in-the-middle).
- **Scope** covers the MCP server's configuration loading and validation, the certificate tool
  (`inubit-cert-check`, check / accept / prune / interactive / forget), the launcher's dialog, the
  documentation and the operator's in-session rule. The MCP server's TLS check itself is expected
  to stay as it is, because it already enforces one pin per server; its configuration validation
  gains the rules of FR-004.
- **Command-line form.** Adoption, forgetting and the in-session rule need a way to name a server
  (for example its server id `int/b`); the exact form is decided in the plan, and the operator's
  global Claude instructions are updated accordingly (FR-016).
- **One trust store per stage** stays the rule (006 clarification); per-server trust stores are not
  introduced.
- **Backward compatibility.** Existing profiles without the setting load and behave exactly as
  before; state and log files written by feature 006 stay valid.
- **The tooling constraints of feature 006 apply** (no new packages beyond the JDK and the system
  Python/OpenSSL, no secrets, nothing on standard output before the MCP transport).
- **Real names stay local.** Real profile, stage, host names and fingerprints are kept out of this
  public repository.
