SPEC: specs/014-stage-distinct-certs/spec.md  ROUND: 1

FINDINGS:

- [major] Edge Cases ("Server-level pin or trust store inherited from a shared anchor or
  `defaults`") vs FR-004 / FR-012 – The three sections disagree.
  - FR-004 makes two things configuration errors: a server-level trust store in a marked stage, and
    an https server whose pin would be inherited from the stage or `defaults`.
  - The edge case treats these same configurations as valid: they are "reported, but no adoption".
  - FR-012 refuses adoption for a pin "not recorded on the server itself (for example taken from a
    shared anchor)". That only makes sense if a server-level `tls: *anchor` passes FR-004, which
    never says whether an alias on the server's own `tls` counts as "of its own".
  - Question: does a server's `tls: *x-anchor` that carries the pin pass the configuration check?
    Proposed wording: "FR-004: … an https server whose expected fingerprint is not written on that
    server (inherited from the stage or `defaults`) is an error; a fingerprint reached through an
    alias on the server's own `tls` is valid, but adoption is refused for it (FR-012)." Then reduce
    the edge case to the alias case only and drop "trust store" and "`defaults`" from it.
- [major] FR-003 / FR-004 / Edge Case "Trust store of the marked stage used by another stage" – "a
  trust store of its own" is not defined. The tool (`_finish_stages` in
  tools/inubit-cert-check.py) currently needs the trustStore to be written inline in the stage's
  `tls` block and used by no other stage. The spec does not say:
  - whether the configuration check rejects a marked stage with no trust store, or one inherited
    from `defaults` or a stage-level alias;
  - or whether such a stage is valid and only adoption is refused.
  - Proposed wording: "FR-004 additionally rejects a marked stage without a trust store. A trust
    store inherited from `defaults`, set through an alias, or shared with another stage is valid for
    connections, but FR-012 refuses adoption for it and the report says so."
- [major] FR-007 (JSON fields keep their names and meaning) – For a marked stage the existing
  stage-level fields `pin`, `pinSource`, `ownTls`, `offer`, `rejected` and `conflict` (see
  `check_json`) cannot keep their 006 meaning. Under 006, a stage with server-level pins has
  `pinSource: server` and `ownTls: false`, which consumers (and the in-session rule) read as "no
  adoption possible". Specify the values of each of these fields for a marked stage and where the
  per-server offer and rejection live. Example wording: "For a marked stage: `pin` is null,
  `conflict` false, stage-level `offer` and `rejected` are null; `ownTls` is true when the stage's
  trust store is its own; each server object gains `offer`, `rejected` and `expectedBy` (FR-010a);
  a new stage field `distinctCertificates: true` marks the stage."
- [major] FR-014 / Story 3 – It is not defined how several per-server dialogs relate to the 006
  timing and locking rules:
  - Are the dialogs for `int/a` and `int/b` shown at the same time or one after another?
  - Does the about-20-second start budget (006 FR-021a) cover all of them together?
  - What is the outcome when two Accepts (possibly late ones) for servers of the same stage come
    close together? Each adoption backs up, edits and validates the same configuration and trust
    store.
  - The 006 edge case "at most one dialog per stage and fingerprint" needs a per-server version.
  - Proposed wording: "Dialogs for several servers are shown [at the same time / one after
    another]. The start waits at most about 20 s in total, measured from the start of the check.
    Adoptions confirmed for different servers of the same stage are run one after another, and each
    starts from the result of the previous one, so none undoes another. At most one dialog per
    server and fingerprint is shown at a time."
- [minor] FR-011 – "Every change-log line about a marked stage MUST name the server" conflicts with
  `pruned` lines, which concern the stage's trust store and no single server. State what the server
  field contains for prune lines (for example empty or `-`). Also state whether lines for unmarked
  stages gain the field (empty) or keep the exact 006 format.
- [minor] FR-013 vs 006 FR-013a – FR-013 only covers the prune command. State that the check's
  listing of superseded trust-store entries (`supersededTrustStoreEntries`) uses the same rule for
  marked stages: an entry is superseded only if no server of the stage expects it.
- [minor] FR-015 / Edge Case "Remembered rejections" – The per-server version of 006 FR-019b is
  missing:
  - the check marks a changed server as rejected, and the exit code does not change;
  - adopting a remembered fingerprint via command line or Claude clears that server's entry.
  - Also say what explicit forgetting does when given only a stage for a marked stage: clear all of
    its servers' entries, or refuse and ask for a server.
- [minor] FR-012 – Add "an http server of a marked stage" to the refusal list. Today it would fall
  under "unknown server", which gives a misleading message.
- [minor] FR-010a / FR-014 / FR-016 – The note "this certificate is expected for server X" is only
  required in the check's details. Since this is the case most likely to mean misrouting or a
  man-in-the-middle, require the start-up dialog and Claude's question to show it as well.
- [minor] FR-004 – The rule "hostname verification disabled without an expected fingerprint for
  that server" can never fire in a marked stage, because every https server must already have its
  own fingerprint. Remove it, or say it is listed only for completeness.
- [minor] Story 2 AS 3 – "aborts without changing any file and says so". Also state that the abort
  is logged with outcome `failed` (006 FR-015), as FR-009 implies.
- [minor] Scope – There is no explicit out-of-scope list. Add one, for example:
  - no tool support for converting an existing uniform stage into a marked one (a manual edit, then
    the configuration check);
  - no per-server trust stores;
  - no automatic detection of marked stages;
  - no change to the MCP server's runtime TLS check.

FINDINGS ON CONSTITUTION: none. FR-017 keeps TLS verification and pinning strict for unmarked
stages, and FR-018 covers the documentation gate.

VERDICT: CHANGES_REQUESTED

Files consulted:
- specs/014-stage-distinct-certs/spec.md
- .specify/memory/constitution.md
- specs/006-tls-pin-rotation/spec.md (FR-007 to FR-024, edge cases)
- tools/inubit-cert-check.py (`_read_stage`, `_finish_stages`, `check_json`, `_usable_stage`,
  `cmd_prune`)
- src/main/java/de/dadecker/inubit/mcp/config/TlsConfig.java
- src/main/java/de/dadecker/inubit/mcp/config/GroupConfig.java
- src/main/java/de/dadecker/inubit/mcp/config/ConfigValidator.java (`checkTls`)
