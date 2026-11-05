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
6. **Given** any one of these configurations — the setting on `defaults`, the setting on a server,
   a trust store on a server of a marked stage, an expected fingerprint in the marked stage's
   `tls` (directly or through an alias), or a marked stage without any trust store — **When** the
   configuration check runs, **Then** it fails and names the stage and, where it applies, the
   server (FR-004).
7. **Given** a profile with the marked stage `int`, **When** the MCP server starts, **Then** its log
   says that `int` is marked (FR-005).

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
   **When** adoption runs, **Then** it aborts without changing any file, says so, and logs the
   attempt with outcome `failed` (006 FR-015).
4. **Given** `int/b` presents F and `int/a` is unreachable, **When** the operator adopts F for
   `int/b`, **Then** the adoption succeeds; `int/a` is neither contacted for the decision nor
   changed.
5. **Given** both `int/a` and `int/b` changed, **When** the operator adopts the offer of `int/b`
   only, **Then** `int/a` keeps its old expected fingerprint and is still reported as `changed`.
6. **Given** a step after the backup fails, **When** adoption runs, **Then** configuration and trust
   store are restored and the failure is reported and logged.
7. **Given** any one of these requests — adoption for marked stage `int` without a server, adoption
   for a server of an unmarked stage, adoption for an http server, adoption for a server whose
   expected fingerprint is reached through an alias, or adoption in a marked stage whose trust
   store is not its own — **When** adoption runs, **Then** it is refused without changing any file
   and the message names the reason (FR-012).
8. **Given** `int/a` expects certificate A and `int/b` was just moved from B to F, so the trust store
   of `int` holds A, B and F, **When** the operator lists or prunes superseded entries of `int`,
   **Then** only B is listed for removal; A and F are kept (FR-013).
9. **Given** a late dialog "Accept" for `int/b` and a command-line adoption for `int/a` start at the
   same time, **When** both run, **Then** they run one after another and both changes are in the
   final configuration and trust store (FR-009). If the dialog stays open longer than the 30 s the
   command-line adoption waits for the profile lock, that adoption is refused with "profile lock
   busy" and changes nothing; repeated after the dialog, it succeeds (research R-12).

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
- **Server fingerprint reached through an alias** (the server's own `tls` refers to a shared
  anchor): valid for connections and reported normally, but no adoption for that server, because
  changing the anchor would change more than that server; the report says so (FR-012).
- **Trust store of the marked stage not its own** (inherited from `defaults`, set through an alias,
  or used by another stage): valid for connections, but no adoption for any server of the stage;
  the report says so (FR-003, FR-012).
- **http servers** in a marked stage: skipped as in feature 006; they need no expected certificate
  and cannot be named for adoption.
- **Pruning and the check's list of superseded entries**: a trust-store entry is superseded only if
  no server of the stage currently expects it; an entry still expected by any server is never
  listed for removal (FR-013).
- **Remembered rejections**: kept per server for marked stages; cleared when that server presents
  its expected certificate again, when the remembered fingerprint is adopted for that server, or
  when the operator forgets it explicitly (FR-015).
- **Several servers of one stage changed at start-up**: their dialogs are shown one after another
  within the same start budget; adoptions confirmed for them run one after another, each on the
  result of the previous one (FR-014).
- **Two MCP server instances start at the same time**: at most one dialog per server and
  fingerprint is shown at a time (per-server form of the 006 edge case).
- **Old state and log files of feature 006** (keyed by stage only) remain readable; existing entries
  of unmarked stages keep their meaning. A stage-keyed rejection entry for a stage that is now
  marked is ignored and removed by the next check; it never suppresses a per-server dialog
  (FR-015).

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
- **FR-003**: A marked stage MUST use exactly one trust store for all its servers, taken from the
  stage's `tls` or inherited from `defaults`, never set on a server; it holds the certificates of
  all its servers; the chain of every server is validated against it, and each
  server's expected fingerprint decides which certificate is accepted. The trust store counts as
  the stage's *own* when it is written in the stage's own `tls` block (not inherited from
  `defaults`, not set through an alias) and no other stage uses the same file — the rule of
  feature 006. Only a stage with its own trust store can adopt (FR-012).
- **FR-004**: The configuration check MUST reject:
  - the setting outside a stage (on `defaults` or on a server);
  - a marked stage without any effective trust store;
  - a trust store set on a server of a marked stage;
  - an expected fingerprint in the marked stage's `tls`, written directly or reached through an
    alias (a fingerprint in `defaults` is allowed, because every server of the marked stage
    overrides it with its own);
  - an https server of a marked stage whose expected fingerprint is not set in that server's own
    `tls` (directly or through an alias the server's `tls` refers to);
  - a malformed fingerprint.
  Each error names stage and server. A fingerprint reached through an alias on the server's own
  `tls` and a stage trust store that is not the stage's own are valid; they only restrict adoption
  (FR-012).
- **FR-005**: At runtime the MCP server MUST accept, for each server of a marked stage, only that
  server's expected certificate (FR-002), and MUST log at start which stages are marked.

**Check**

- **FR-006**: For a marked stage the check MUST NOT report a conflict when its servers present
  different certificates; each server is judged against its own expected fingerprint with the
  statuses `ok | changed | unreachable` of feature 006.
- **FR-007**: The human-readable and the JSON report MUST show for a marked stage that it is marked
  and, per changed server, the offer (old and new fingerprint, subject, issuer, validity, IP) and
  whether it was rejected earlier. For unmarked stages the JSON document of feature 006 is
  unchanged. For a marked stage the existing stage fields have these values, and new fields are
  only added:
  - a new stage field states that the stage is marked;
  - `pin` is null, because the stage has no stage-wide expected fingerprint;
  - `pinSource` is `server`;
  - `conflict` is false;
  - the stage-level `offer` and `rejected` are null; offers and rejections are given per server,
    in new fields of each server object;
  - `status` is derived from the servers as in feature 006 (the worst server status wins:
    `changed` before `unreachable` before `ok`);
  - `ownTls` is true exactly when the stage has its own trust store (FR-003);
  - each server object gains a field naming the server(s) of the stage that expect the presented
    certificate, if any (FR-010a), and a field saying why that server cannot be adopted, if it
    cannot (FR-012).
- **FR-008**: The exit statuses of feature 006 (0, 10, 20, distinct internal-error status) MUST keep
  their meaning; a marked stage with a changed server counts as "at least one change".

**Adoption**

- **FR-009**: Adoption for a marked stage MUST follow 006 FR-011 to FR-015 (verify at that moment,
  backup with owner-only permissions, change only what is in scope, add the verified certificate to
  the stage's trust store, keep existing entries, validate with the configuration check, restore on
  failure, log every decision). Any two adoptions or prunes that touch the same configuration or
  trust store, whatever their actor (`dialog`, `cli`, `claude`), MUST run one after another, each
  on the result of the previous one.
- **FR-010**: In a marked stage one adoption decision MUST cover exactly one server: the operator
  names stage, server and fingerprint; adoption verifies only that server (it must be reachable and
  present exactly that fingerprint at that moment) and changes only that server's expected
  fingerprint plus the stage's trust store. Other servers of the stage need not be reachable and
  are not changed. For unmarked stages the stage-level adoption of feature 006 stays as it is.
- **FR-010a**: When the presented certificate of a server is the one expected for another server of
  the same stage, the check MUST say so in the server's details, and the start-up dialog and
  Claude's question MUST show the same note; the offer and the adoption rules are otherwise
  unchanged.
- **FR-011**: Every change-log line about a server of a marked stage (`adopted`, `rejected`,
  `timed-out`, `dialog-failed`, `failed`) MUST name that server in addition to the fields of
  006 FR-015. `pruned` lines concern the stage's trust store and name the stage only. Lines about
  unmarked stages keep the exact format of feature 006.
- **FR-012**: Adoption MUST be refused, with a message naming the reason, for: an unknown stage; an
  unknown server or a server of another stage; an http server; a server named for an unmarked
  stage; a marked stage named without a server; a malformed fingerprint; a server whose expected
  fingerprint is reached through an alias (FR-004); and a marked stage without its own trust store
  (FR-003).
- **FR-013**: For a marked stage, both the check's list of superseded trust-store entries
  (006 FR-013a) and pruning MUST treat an entry as superseded only if no server of the stage
  currently expects it; removal stays an explicit operator action.

**Start-up dialog and in-session rule**

- **FR-014**: The start-up dialog of feature 006 MUST offer each changed server of a marked stage as
  its own decision, with stage and server named, and only "Accept" may adopt; an "Accept" adopts
  that one server only. Dialogs for several servers (of one or several stages) are shown one after
  another, as feature 006 does for several stages. The start waits at most about 20 seconds in
  total, measured from the start of the check (006 FR-021a); each dialog closes itself after its
  60 seconds, and late answers are handled as in 006 FR-021a. Adoptions confirmed for servers of
  the same stage run one after another, each starting from the configuration and trust store the
  previous one left, so none undoes another. At most one dialog per server and fingerprint is shown
  at a time, also across MCP server instances that start at the same time.
- **FR-015**: Remembered rejections for a marked stage MUST be kept per server and fingerprint
  (per-server form of 006 FR-019a and FR-019b):
  - a remembered fingerprint suppresses only the dialog for that server and fingerprint; a warning
    goes to standard error and the log instead;
  - the check marks such a server as rejected; its status stays `changed` and the exit status is
    unchanged;
  - adopting the remembered fingerprint for that server via command line or Claude clears that
    server's entry, and so does the server presenting its expected certificate again;
  - explicit forgetting can be given a stage and a server and then clears only that server's entry;
    given only a marked stage, it clears the entries of all servers of that stage;
  - a stage-keyed entry written by feature 006 for a stage that is now marked is ignored and removed
    by the next check.
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
- **One trust store per stage** stays the rule (006 clarification).
- **Out of scope:**
  - tool support for converting an existing uniform stage into a marked one (the operator edits the
    configuration by hand and runs the configuration check);
  - per-server trust stores;
  - automatic detection of stages that should be marked;
  - changes to the MCP server's TLS check at connection time.
- **Backward compatibility.** Existing profiles without the setting load and behave exactly as
  before; state and log files written by feature 006 stay valid.
- **The tooling constraints of feature 006 apply** (no new packages beyond the JDK and the system
  Python/OpenSSL, no secrets, nothing on standard output before the MCP transport).
- **Real names stay local.** Real profile, stage, host names and fingerprints are kept out of this
  public repository.
