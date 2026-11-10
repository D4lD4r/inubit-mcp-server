PLAN: specs/019-local-knowledge/plan.md (with research.md, data-model.md, contracts/, quickstart.md) ROUND: 1

Review of the plan artifacts against spec.md (FR-001 … FR-022, SC-001 … SC-006) and the
constitution (I–VII), with the cited code checked (OsPrivateFileSystem, NodeSecrets,
McpServerFactory, ItemBounds). The design is sound in structure; the findings concern secret
leaks in edge paths, atomicity on POSIX, link handling on internal paths, audit semantics and
one unconfirmed decision that the tasks would silently commit to.

FINDINGS:
- [major] plan.md Decision needed / D-7 – the credential-readiness choice (A/B/C) is pending the person's confirmation, yet the plan says "the tasks implement (A)" – tasks could build on an unconfirmed decision that changes 011 R-10; put the dependent tasks behind an explicit decision gate.
- [major] research.md D-6 – YAML block scalars (`password: |`, `>`) are exempt from the assignment rule, so the indented value lines below leak – treat the deeper-indented lines as the value span and test it.
- [major] research.md D-5 – only title/topics are masked; `source` and `lastWriter` from a hand-written front matter are returned unmasked and untested – mask all exposed metadata, one line, cut; never return unknown keys.
- [major] research.md D-10 – the name rule runs before the credential/secret check, so an invalid name holding a credential is echoed in the error and written to the audit – check secrets first; carry the name as `***` until then.
- [major] research.md D-12 – `ATOMIC_MOVE` without `REPLACE_EXISTING` replaces an existing target on POSIX (rename(2)), so `create` is not "never replace" (FR-004b) – publish new notes by hard link (`Files.createLink`), `FileAlreadyExists` → `CONFLICT`, unsupported → `PRECONDITION_FAILED`.
- [major] research.md D-12 / OsPrivateFileSystem:92-100, 221-230 – `.history` and `.lock` are opened following links, so a link there writes outside the directory – check every internal path with `NOFOLLOW_LINKS`, refuse links, test each.
- [major] research.md D-13 – a failed final audit only yields a warning, which conflicts with FR-020/SC-005/VII as written – state the precise meaning (pre-write record), follow the 015 precedent, and record it as a plan decision and in Complexity Tracking.
- [major] research.md D-13 / McpServerFactory:114 – `validateToolInputs(true)` rejects schema-invalid calls before the tool runs, so they are never audited (FR-019) – keep the schema structural and check bounds, patterns and combinations in the service; record the residual gap as a plan decision.
- [major] research.md D-4 – `toUpperCase().toLowerCase()` is not Unicode case folding (e.g. `ı`), so FR-004b's "case folding" is not met literally – document it as a conservative approximation in the spec's plan decisions and test the collisions.
- [major] research.md D-10 – the 100 MiB total is measured with the listing walk's depth/entry limits, so content beyond them is not counted – use a separate walk without the depth limit that fails closed beyond a fixed entry bound.
- [major] contracts/mcp-tools-delta.md – the consent text in the instructions and the record_knowledge description omit topics (FR-017: everything that is written is shown first) – add topics.
- [major] research.md D-10 / D-11 – the history version is copied and pruned before the note is published, so a failed write still changes the history (FR-014) – add the version, publish, prune only after success, undo on failure.
- [minor] contracts/mcp-tools-delta.md read output – no `oneOf` for the two result shapes and `content` may reach 20,012 characters with the marker – use `oneOf` with complete required sets; 20,000 including the marker.
- [minor] research.md D-8 – the 4,000-character list bound in the instructions excludes the summary line – reserve the summary line inside the bound.
- [minor] research.md D-7 / NodeSecrets – `Cell.Refusal` keeps only `(ToolError, Instant)`, so a never-throwing `settle` cannot report the failure kind – note the `Cell` refactor and the name clash with the private `Cell.settle` (lines 548-566).

VERDICT: CHANGES_REQUESTED
