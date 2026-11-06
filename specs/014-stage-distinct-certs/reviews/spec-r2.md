SPEC: specs/014-stage-distinct-certs/spec.md  ROUND: 2

All 12 findings from round 1 are fixed:
- The alias, `defaults` and trust-store cases now agree across FR-003, FR-004, FR-012 and the edge
  cases.
- FR-007 now gives the value of every existing JSON stage field for a marked stage.
- FR-014 now defines the dialog order, the 20 s start limit, one-after-another adoptions and the
  per-server lock.
- The minor points are covered in FR-011 (prune lines), FR-013 (superseded list), FR-015
  (rejections), FR-012 (http server), FR-010a (note in the dialog and in Claude's question),
  Story 2 AS 3 and the new out-of-scope list.

I checked the spec against the code:
- `TlsConfig` inherits field by field. So a server-level pin combined with a stage-level trust
  store, as FR-003 needs, works at runtime.
- `ConfigValidator.checkTls` runs on the effective config.
- The tool already has a profile lock (`cert-check.lock`).
- `check_json` matches the fields named in FR-007.

FINDINGS:

- [minor] FR-003 vs FR-004 / Edge Cases – FR-003 says a marked stage uses "exactly one trust store,
  set at stage level". But FR-004 only rejects a stage with no effective trust store, and the edge
  case "Trust store … inherited from `defaults` … valid for connections" allows one inherited from
  `defaults`. A planner could read FR-003 as a validation rule that rejects the `defaults` case.
  Proposed wording for FR-003: "A marked stage MUST use exactly one trust store for all its
  servers, taken from the stage's `tls` or inherited from `defaults`, never set on a server; …"
- [minor] FR-004 bullet 4 ("an expected fingerprint in the marked stage's own `tls` block") –
  FR-003 uses "own" to mean "not set through an alias", so this bullet is ambiguous when the stage's
  `tls` is an alias to an anchor that contains a pin. Question: is a pin reached through a
  stage-level alias also rejected? It probably should be, because the Java validator sees aliases
  already resolved. Proposed wording: "an expected fingerprint in the marked stage's `tls` (written
  directly or through an alias)".
- [minor] FR-007 – The existing stage field `status` (part of `check_json`) is not in the list of
  values for a marked stage. Story 2 AS 1 implies `changed`. Proposed addition: "`status` is
  `changed` if any server is `changed`, otherwise `unreachable` if any server is `unreachable`,
  otherwise `ok`", or "derived from the servers as in feature 006" if 006 already uses that rule.
- [minor] Edge Case "Old state and log files of feature 006" / FR-015 – The spec does not say what
  happens to a stage-keyed rejection entry (006 format) for a stage that has since been marked.
  This is likely right after a manual conversion. Question: is the entry ignored, applied to every
  server of the stage, or cleared? Proposed wording: "A stage-keyed rejection entry for a marked
  stage is ignored and removed by the next check; it never suppresses a per-server dialog."
- [minor] FR-014 / FR-009 – Serial execution is only required for adoptions confirmed in dialogs.
  A late dialog "Accept" for `int/b` can coincide with a CLI or Claude adoption for `int/a`. Both
  edit the same configuration and trust store. The existing profile lock probably covers this, but
  the spec should say so. Proposed wording for FR-009: "Any two adoptions or prunes that touch the
  same configuration or trust store, whatever their actor (dialog, cli, claude), run one after
  another, each on the result of the previous one."
- [minor] FR-015 last bullet – "explicit forgetting accepts a server" uses "accept", which means
  adoption in this domain. Proposed wording: "explicit forgetting can be given a stage and a server
  and then clears only that server's entry; given only a marked stage, it clears the entries of all
  its servers."
- [minor] Acceptance coverage – Several requirements have no Given/When/Then scenario:
  - FR-004: only the missing server pin is covered (Story 1 AS 5). Not covered: the setting on
    `defaults` or on a server, a trust store on a server, a pin in the stage `tls`, and a marked
    stage with no trust store.
  - FR-012: the refusals (marked stage named without a server, server named for an unmarked stage,
    http server, pin reached through an alias, trust store that is not the stage's own).
  - FR-013: pruning keeps an entry that another server still expects.
  - FR-005: the start log lists the marked stages.

  Proposed fix: add Story 1 scenarios for the FR-004 rejections, and Story 2 scenarios such as
  "Given `int/a` expects A and `int/b` was just moved from B to F, When the operator prunes `int`,
  Then only B is offered for removal and A is kept", plus one refusal scenario per FR-012 case.
  Alternatively, mark the edge-case bullets as acceptance tests.

Constitution: no conflicts. TLS verification and pinning stay strict for unmarked stages (FR-017).
Secrets stay out of the flow, nothing goes to stdout before the MCP transport, and FR-018 covers
the documentation gate. The Python tooling was already accepted in feature 006.

VERDICT: APPROVED

Files consulted:
- specs/014-stage-distinct-certs/spec.md
- specs/014-stage-distinct-certs/reviews/spec-r1.md
- .specify/memory/constitution.md
- specs/006-tls-pin-rotation/spec.md (edge cases, FR-001 to FR-024)
- src/main/java/de/dadecker/inubit/mcp/config/TlsConfig.java
- src/main/java/de/dadecker/inubit/mcp/config/ConfigValidator.java (`checkTls`)
- tools/inubit-cert-check.py (`check_json`, lock handling, `--forget`)
