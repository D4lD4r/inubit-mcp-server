# Spec review – round 2 (2026-11-05)

SPEC: specs/015-workbench-archives/spec.md  ROUND: 2  VERDICT: CHANGES_REQUESTED

All 18 findings of round 1 are resolved; small leftovers in #3, #5, #6, #8, #11, #12.

## Findings

1. [major] FR-005/FR-017/US3-4/edge case: a module missing from the diagram-group export is normal
   (modules of another owner, `WorkspaceWriter.java:225-228` only warns), not an INUBIT defect;
   refusing the whole call blocks every archive of the group. (a) refuse the call, (b) refuse that
   workflow, or (c) archive it and list `missingModules` with a warning?
2. [major] FR-002: workflow names can contain any character (`NameCodec`, US1-6); the diagram-group
   character rules make such workflows unnameable. Proposed: 1–50 unique names, 1–200 characters,
   no control characters, matched case-sensitively.
3. [major] FR-012 vs Constitution VI: the result is unbounded (50 archives with all modules and
   repository paths). Limit by `resultLimits.maxItems` with `truncated` and counts? Where does a
   full list go?
4. [minor] FR-006: `archive.properties` is a properties file, not XML: byte-identical. Say that
   `Repository.zip` keeps the `.xml` and `.dat` entries and the folder entries.
5. [minor] US1-2, US1 test, Key Entities omit the modules bound by `WorkflowName`.
6. [minor] FR-004: empty name after trimming, leading dot, reserved name with extension, Unicode
   normalization in the case-insensitive comparison.
7. [minor] FR-011/US3-3: list workflows not attempted (`notWritten[]`); code of a write failure;
   failure on folder creation or the first archive.
8. [minor] FR-008/FR-010: checks for a target that does not exist yet (nearest existing ancestor).
9. [minor] Assumptions: earlier archives are never replaced (wording).
10. [minor] Assumptions: warn per archive when the workflow has a Workflow Connector node.
11. [minor] FR-008/US2: cloud-synced folders (OneDrive, `~/Library/CloudStorage`,
    `~/Library/Mobile Documents`): refuse, warn, or document?
