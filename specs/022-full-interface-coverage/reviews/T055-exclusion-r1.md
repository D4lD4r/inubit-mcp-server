TASK: T055 completion (IMC-7 option 3: removeTag/deleteTag excluded and removed from manage_tags)  ROUND: 1
STAGE1: FAIL
STAGE2: SKIPPED
TESTS: 21 classes (28 with nested), 787 tests, 0 failures; compile confirmed (IDE errors stale).
FINDINGS:
- [major] tag-list backup only partly removed (BackupStore.writeTagList, ObjectKind.TAG_LIST, BackupKind.TAG_LIST without caller; D-15, data-model, plan still describe it). – Remove as in the setDebug precedent, or document accurately.
- [minor] FR-018 tag sentence reads as a requirement. [minor] plan.md:147 "tag removal". [minor] two rewraps. [minor] live removal() error without node.
Passed: spec/contract/tasks consistency; coverage rows excluded on both lines (reasons ≤120; 9.0 on 8.1 evidence acceptable); production removal complete; AS13/AS14 tests; live test sends the same commands.
VERDICT: CHANGES_REQUESTED
