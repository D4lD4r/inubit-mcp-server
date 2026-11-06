# Spec review – round 1 (2026-11-05)

SPEC: specs/015-workbench-archives/spec.md  ROUND: 1  VERDICT: CHANGES_REQUESTED

## Findings

1. [major] FR-001/FR-007: a "read-only" tool gets `readOnlyHint: true`, so clients run it without
   asking and the model can set the acknowledgement itself. Proposed: annotate `readOnlyHint:
   false`, `destructiveHint: false`, `idempotentHint: false`, `openWorldHint: true`; the
   acknowledgement documents intent and does not replace the client's confirmation.
2. [major] FR-009 / edge case / US2 test vs FR-003: without workflow names the archive names are
   known only after the one StartCLI export; "before any export" cannot hold. Use one moment, e.g.
   "refused before the first file or folder is written".
3. [major] FR-006: byte identity is impossible for `workflow/workflow.xml` and `module/module.xml`
   (one document per group). Proposed: module files and repository files byte-identical; the
   workflow's element, the module index entries and `archive.properties` unchanged as XML; only
   other workflows/modules removed; say what happens to `Repository.zip` entry metadata.
4. [major] FR-009 vs FR-011: "temporary name, then rename" replaces a file created after the check.
   Require that an archive never replaces an existing file, also one created after the check;
   leave the mechanism to the plan; add an edge case for two concurrent calls.
5. [major] FR-004: the file-name rule is not deterministic (which characters, Windows reserved
   names, trailing dots/spaces, control characters, 255-byte limit, case-insensitive file systems).
6. [major] FR-011/FR-012/edge cases/US3-3: shape, code and audit outcome of a partial result are
   undefined; are per-workflow refusals decided before the first write?
7. [major] FR-002/FR-012 vs Constitution VI: no upper bound on workflows per call.
8. [major] FR-005: called workflows and modules referenced only by other modules are not covered.
9. [major] FR-008: "the server's state directory" undefined; other git working trees accepted.
10. [minor] FR-015: the code is `UNSUPPORTED_VERSION`.
11. [minor] FR-002: no rules for workflow names (characters, length, case, duplicates).
12. [minor] FR-010: when the folder is created; target path that is a file / not writable / symlink.
13. [minor] FR-013: schema violations are not audited by the other tools; "without values" unclear;
    what if the audit record cannot be written; record the account or a reason?
14. [minor] FR-014: the configuration says "group", not "stage"; is the tool announced on a
    production group without the opt-in?
15. [minor] No out-of-scope section.
16. [minor] Windows: inherited ACLs and UNC/network paths are the "shared by accident" case.
17. [minor] SC-002: measurement conditions undefined.
18. [minor] FR-012: list modules as `{name, pluginType}`.
