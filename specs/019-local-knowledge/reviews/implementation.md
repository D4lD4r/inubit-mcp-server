# Implementation reviews — Feature 019

## Baseline (T001)

- `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-019-tmp` on 556fc7f
  (0.8.0 + the 019 documents), JDK 25.0.5 / Maven 3.10.0: surefire 5495 tests, 0 failures,
  1 error, 52 skipped. The plan's figure (4998 / 51) was outdated.
- The error was environmental: `GitCliTest.hooksSigningAndLineEndingSettingsOfTheRepositoryHaveNoEffect`
  writes `.git/hooks/pre-commit`, but git 2.56 no longer creates `.git/hooks` on `init`. The test
  now creates the directory first (test-only change, same assertion); it passes alone.

## Implementation — phase 2 (T002–T007)

Build on 1240f95: surefire 5532 tests, 1 failure, 52 skipped (failsafe not reached). Code review
of `556fc7f..1240f95` by a review agent.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| B1 | build | `NoCustomerIdentifiersTest` (local denylist) matched a word in a character-name comment of `CaseFolding-15.1.0-CF.txt` | the name comments of the data lines are removed (header and Unicode notice kept); the table test reads only the fields |
| R1 | minor | `NoteName.writableProblem` refused `.md` only at the end of the whole name, so `x.md/y` was writable: its folder `x.md` clashes with the file of note `x`, and the histories nest (D-11) | no segment may end in `.md` (any case), message `segment <n> ends in .md`; tests `x.md/y`, `a/x.Md/y`; D-4 and T003 wording updated |
| R2 | minor | `PackageWriter.defaultDirectory` was inserted between the constructor's Javadoc and the constructor | moved above the constructor's Javadoc |

All other points of D-2, D-4, D-6 and data-model.md were confirmed.

Build on 89184f2 + T008 (a0269e3): surefire 5542 tests, 0 failures, 0 errors, 52 skipped;
failsafe 2 tests, 0 failures.

## Implementation — phase 3 (T008–T011)

Build on 6d8cb9c: surefire 5581 tests, 0 failures, 0 errors, 52 skipped; failsafe 2 tests,
0 failures. Code review of `89184f2..6d8cb9c` by a review agent.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | major | The allowance "inside `~/.inubit-mcp/<own profile>`" applied to every forbidden directory, so `workspace: ~` or `auditDirectory: ~/.inubit-mcp/acme` (or another profile's audit directory there) let the default knowledge directory lie inside a workspace or audit directory | the allowance holds only for the configuration directory and the folder of the configuration file (`Forbidden.mayHoldOwnData`); test `theDataDirectoryExemptionDoesNotCoverAWorkspaceOrAuditDirectoryHoldingIt` (fails without the fix); D-2 and the contract worded accordingly |

Targeted after the fix: `ConfigValidatorKnowledgeTest` 16, `ConfigValidatorTest` 189 (1 skipped),
`MainTest` 78 (3 skipped), 0 failures.

## Implementation — phase 4 (T012–T016)

Build on 54691ce: surefire 5679 tests, 0 failures, 0 errors, 52 skipped; failsafe 2 tests,
0 failures. Code review of `11c91ee..54691ce` by a review agent (no findings in
`NodeSecrets.awaitSettled` or `ProfileSecrets`).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | major | A MIME-wrapped base64 keystore was joined with a prose line directly before it (`Truststore`) or after it (`Hello`): the decoded bytes no longer started with the keystore header or did not decode, so the keystore was neither masked nor refused (fail-open) | a continuation run must fill its line; a block holds runs of the width of its first run, only the last may be shorter; a block that does not decode is tried once more without its last run; tests `aProseLineDirectlyBeforeAWrappedKeystoreIsNotPartOfIt`, `aProseLineDirectlyAfterAWrappedKeystoreIsNotPartOfIt` (fail without the fix) |
| R2 | major | `START_TAG` backtracked quadratically on `<` followed by a long word without `>` (40,000 characters took about 5 s), against the linear bound of T012 | possessive quantifiers (same matches); test `aLongTagLikeWordWithoutClosingBracketIsScannedInLinearTime` (200,000 characters, 2 s bound; times out without the fix) |

Targeted after the fixes: `SecretFinderTest` 67, 0 failures.

## Implementation — phase 5 (T017–T024)

Build on 63c71fb: surefire 5829 tests, 3 failures, 0 errors, 56 skipped (failsafe not reached).
Code review of `04aaa8e..63c71fb` by a review agent (no findings on fail-closed reading, masking
before search, cut and page, logs, package boundaries, level words or the start-time wait).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| B1 | build | `TerminologyRenderingTest`: the `ref` description of the `read_knowledge` output schema said "valid while the server runs" (a level word of the default terminology), and the read-only tool count was still nine | the description says "valid until the next restart" (schema and contract); the count is ten |
| R1 | minor | `KnowledgeService.search` computed `offset + limit` in `int`: an offset near `Integer.MAX_VALUE` wrapped and returned a negative `nextOffset`, against the output schema (`minimum: 0`) | the page end is a `long`; `nextOffset` only when hits follow; test `anOffsetNearTheLargestIntegerGivesAnEmptyPageWithoutANextOffset` (fails without the fix) |

Targeted after the fixes: `KnowledgeServiceSearchTest` 17 (1 skipped), `TerminologyRenderingTest`
8, `ReadKnowledgeToolTest` 11, 0 failures.
Build on 74ec687: surefire 5830 tests, 0 failures, 0 errors, 56 skipped; failsafe 2 tests,
0 failures.

## Implementation — phase 6 (T025–T033)

Build on e17bc1d: surefire 5935 tests, 0 failures, 0 errors, 58 skipped; failsafe 3 tests,
0 failures.
Code review of `74ec687..e17bc1d` by a review agent (no findings on secrets in content, title,
topics or `versionToken`, the INFO line, the order history version → publish → prune, the undo
before the commit point, the token and secret order, the bounds, links on the way, the audit
records, the lock, schemas, wiring, package boundaries or level words).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | minor | `KnowledgeDirectory.publishNew` removed the temporary file in `finally`: when that failed after the hard link (the commit point), the created note was reported as `INTERNAL` "as they were" with a `FAILED` audit record; on the `EXISTS` path the failure hid the `CONFLICT` | the temporary file is removed quietly on every path, after the link too (a stale one is removed by a later size walk); `publishReplace` likewise; tests `aTemporaryFileThatCannotBeRemovedAfterTheCommitPointIsNoError` and `aTemporaryFileThatCannotBeRemovedDoesNotHideThatTheNoteExists` (both fail without the fix) |
| R2 | minor | `create` did not check `.history` before publication (D-10 step 7): a link there was first met by the prune after the commit, and the warning reported an invented 11 versions | `KnowledgeHistory.check` runs under the lock before a new note is published and refuses with `PRECONDITION_FAILED`; a count that cannot be read is reported as "an unknown number of versions"; test `aLinkInTheHistoryIsRefusedBeforeANoteIsCreated` |
| R3 | minor | the lock-file errors named the absolute path `<real dir>/.lock` and the file system's reason text, unmasked and uncut (D-14), in the model's result and the audit `reason` | the message names `.lock` in the knowledge directory and only the exception's class; `KnowledgeLockTest` asserts that neither the directory's path nor a link target appears |
| R4 | minor | `source`/`lastWriter` held the client's `name/version` (`CallContext.client()`), against D-5 and data-model.md (`client` for the metadata, `mcpClient` for the audit) | `Record` carries `client` (the `clientInfo` name, for the metadata) and `mcpClient` (`name/version`, for the audit); `RecordKnowledgeTool` passes `clientName()` and `client()`; the service, tool and wiring tests expect `assistant (<name>)` and `name/version` in the audit |
| R5 | note | pruning orders versions by their stamp: if ten versions carry stamps later than the clock (the clock stepped back, or a history copied from a machine with clock skew), the version just added is the oldest and pruned at once | not changed: the stamp is the version's identity (D-11) and a modification time is just as exposed to skew; the note itself is never lost |

Targeted after the fixes: `KnowledgeServiceRecordTest` 39 (1 skipped), `KnowledgeDirectoryTest` 38
(3 skipped), `KnowledgeLockTest` 9, `KnowledgeHistoryTest`, `RecordKnowledgeToolTest`,
`KnowledgeToolsWiringTest`, 0 failures. The full build follows at the phase 7 checkpoint.

## Implementation — phase 7 (T034–T036)

T036 found a leak in the code of phase 5: a nested note name such as `kinds/AES-…` or
`kinds/<keystore>` was shown unmasked, because `/` separates the segments but belongs to the
base64 alphabet (the lookbehind of the encrypted-value rule and the keystore runs took the
preceding segment along). `SecretFinder.findInName`/`maskName` scan every run of whole segments;
every name masking and the name check of `record_knowledge` use them (fixed in 415ed5c; the leak
test fails without it).

Build on 415ed5c (the phase 6 fixes included): surefire 5944 tests, 0 failures, 0 errors,
58 skipped; failsafe 3 tests, 0 failures.
Code review of `f87993d..415ed5c` by a review agent (no findings on the two-profile test, the
bounds tests, the other name call sites, the record name bound, or the 5,000-note listing).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | major | the title of a note without a metadata `title` (also of an unreadable note) was its last name segment masked alone: of `notes/AES-q8Zr/7Kp2…` the name showed `notes/***`, the title `7Kp2…`, in hits, pages, the instructions and the search | the title is the last segment of the masked name; the leak test has such a note (fails without the fix) |
| R2 | minor | `findInName` is cubic in the number of segments: a `read_knowledge` name of 1,000 slashes cost about 3 s of CPU for its `NOT_FOUND` message | `maskName` masks a name with more segments than the walk lists (`WALK_DEPTH`) as a whole; `record_knowledge` bounds the name before (step 1); test `aNameDeeperThanAnyNoteIsMaskedWholeAndInTime` (times out without the fix) |
| R3 | minor | the leak test built the PKCS#12 twice (random salt and MAC), so its wrapped form was never checked | built once; a slice of the second wrapped line is a leak string, and the test asserts that the note holds it |
| R4 | — | reported: a name secret `******` would be an invalid file name on Windows | not a finding: the display of the review tool masked `password=leakNameValue`; the names hold only `%`, `+`, `-`, `=` and base64 letters |

Targeted after the fixes: the knowledge tests (`Knowledge*Test`, `SecretFinderTest`,
`RecordKnowledgeToolTest`, `ReadKnowledgeToolTest`) 266, 0 failures. The full build follows at
the phase 8 checkpoint.

## Implementation — phase 8 and final review (T037–T040)

T039's reference test found that `docs/setup.md` named neither `knowledge.directory` nor the
configuration delta of feature 019 in its introduction; both added (2d390b1). A mutation check
(one bound in `setup.md`, one field row in `tools.md` changed) turns the test red.

Build on 2d390b1: surefire 5952 tests, 0 failures, 0 errors, 58 skipped; failsafe 3 tests,
0 failures. Python tools: 411 tests OK. `tools/check-identifiers.py --all`: clean.

Final code review of `git diff origin/main...HEAD` (142 files) by a review agent: no findings.
Checked: link handling and no-follow reads in the walk, publish (hard link, atomic replace),
history and lock; the lock timeouts, version token and `ref` re-resolution under the lock;
history removal on failed publish and pruning; the 256 KiB, 100 MiB, size-walk, page, offset and
search bounds against schemas and docs; masking before search, cut, excerpt and paging, and of
names, titles, topics, `lastWriter`, errors, hints and the audit; D-7 readiness (scrubber
registration before completion, timeout as waiting, back-off refusal, both credential sets, the
withheld instructions list); the `recording` strict boolean, the overlap rules and
`--check-config`; the `KeyMaterial` move; the docs against the code. Every `******` seen was the
display masking of the review tool.

T041 (live check, quickstart steps 1–7) is left to the person.
