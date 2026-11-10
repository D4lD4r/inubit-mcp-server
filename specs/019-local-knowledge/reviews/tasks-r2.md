TASKS: specs/019-local-knowledge/tasks.md ROUND: 2

The round-1 fixes correctly resolve the D-2 matrix, read-side ordering, production wiring, KeyMaterial/KnowledgeLimits TDD, log assertions, hidden-file handling, and the main dependency graph. FR/SC and offline quickstart coverage is otherwise complete, but the D-7 gate still leaves option C inconsistent across artifacts, and one foundational change still lacks a genuine red test.

FINDINGS:
- [major] tasks.md:T016 – Only option B requires full artifact reconciliation; choosing C merely removes T024's startup preparation and updates "docs", leaving FR-006/spec.md, plan.md's pending decision and Constitution Check, research D-7, configuration-delta.md's mandatory startup wait, data-model.md's START_PREPARE, and quickstart step 3/T041 contradicting the chosen behavior. Option A would also leave plan.md incorrectly marked pending – require every choice to update spec, plan and research, and require C to reconcile the contract, data model, quickstart/live check and affected tasks before Phase 4.
- [major] tasks.md:T006 – `SimilarNamesTest` is assigned to the existing `application` package, where the package-private `SimilarNames` is already accessible and its behavior already passes through `InventoryServiceTest`; therefore making the class public has no specified failing test and violates Constitution Principle III – first add a visibility/consumer test from `application.knowledge` or an explicit public-modifier assertion that fails, then change the visibility.
- [minor] tasks.md:T024 – The configuration contract requires the exact INFO log when startup credential preparation is not ready, but no task implements or positively asserts that line – add the contract log and a captured-log assertion, including the standard leak check, to T024/MainTest.

VERDICT: CHANGES_REQUESTED

RESOLUTION (applied in "Feature 019: tasks review round 2"):
- T016: for every option one reviewed commit before Phase 4 records the answer in spec, plan ("Decision needed" and Constitution Check) and research D-7; (C) additionally reconciles FR-006 wording, configuration-delta.md, data-model.md `START_PREPARE`, quickstart (rows and live-check step 3), T024, T037/T038, T041; (B) also lists T041.
- T006 starts with a red `knowledge/SimilarNamesAccessTest` calling `SimilarNames.of` from the sub-package; `SimilarNamesTest` only pins the behaviour.
- T024 implements and asserts the INFO line of configuration-delta.md through a `ListAppender`, with the leak check.
