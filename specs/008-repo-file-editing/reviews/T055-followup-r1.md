TASK: T055 follow-up (three minor wording findings from reviews/T055-r1.md)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: Documentation only (`git diff 553de31 --stat`: CHANGELOG.md, docs/setup.md, docs/tools.md, +7/-5); `git diff 553de31 --check` clean; offline only.

Stage 1:
- docs/tools.md:925-926 "If the last re-export of the repository files failed" matches `ImportService.failed()` (1565-1594): `seen` = `outcome.repository().map(Rollback::seen).or(() -> after)` (the last re-export, incl. the pre-rollback one when the repository rollback throws); `restorable = notRestored.isEmpty() || seen.flatMap(Reread::failure).isEmpty()`; "for those files" keeps the notRestored condition. Resolves r1 finding 1.
- docs/setup.md:476-477 matches `RepositoryStep.read` (619-646): one export per parent folder, folder skipped for `/Root/<owner>`, `UNEXPECTED_RESPONSE` falls back to per-file exports; all into `raw` → `NodeState.rawExports` (310/337) → backup ZIPs via `execute()` (ImportService.java:1301-1306); consistent with tools.md:1066. Resolves r1 finding 2.
- CHANGELOG.md:31-33 qualification matches `failed()` (when `restorable` is false the "restore them with restore_backup or" prefix is dropped, the Workbench re-import from the backup's repository exports stays). Resolves r1 finding 3.
- Scope: documentation only, nothing beyond the three findings.

FINDINGS:
- [minor] docs/setup.md:477 – The new parenthetical sits inside an existing one, so the line ends in "))"; correct but awkward. – Optional: "or of single files: those directly below `/Root/<owner>/` or in a folder over the read limits".

VERDICT: APPROVED
