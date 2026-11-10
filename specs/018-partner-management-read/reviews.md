# Reviews: 018 Read the Partner Management

Producer/reviewer process: the spec and plan review rounds are in `reviews/`. After every
implementation phase a `code-review` reviewer checks the phase diff; findings and decisions go
here.

## Implementation — baseline

T001 (2026-11-09), branch `018-partner-management-read` at 3f9589e, no source change since:
`mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-018-tmp` green with
5495 unit tests (surefire), 0 failures, 0 errors, 52 skipped, plus 2 failsafe tests;
`python3 -I -m unittest discover -s tools/tests` green with 438 tests. These are the reference
counts for the feature.

The first full run had one error, `GitHubReleaseSourceTest.sendsOnlyTheFixedRequestWithoutConfigurationData`
(`ReleaseCheckException: HTTP 404` from the WireMock stub). The class passed in 3 of 3 runs on
its own and the next full build was green, so this looks like a flaky test that already existed
and is not a baseline failure. It is recorded here in case it comes back.

## Carried over to later tasks

- From T006 review: a present but blank partner `Id` (`Shown("")`) currently yields a key; T025 (`PartnerComparison`) must decide explicitly whether a blank `Id` counts as absent (FR-013 fallback) or matches. Status: done in T025, decided "absent, never matched": `PartnerComparison` treats a blank (also whitespace-only) shown key value like an absent or masked one, so that partner has no key, matches no other blank `Id` and is compared on its server alone (`key` empty, `onlyOn` its server). The key field is decided per cloud, not per partner (D-10): one blank `Id` does not switch the cloud to another field; only when no partner of the cloud holds a non-blank shown `Id` (or not every server declares `Id`) does the cloud fall back to the first declared field present on both sides. `CommunicationCloud.keyOf` is unchanged (the reader only uses it for warning texts). Tests `PartnerComparisonTest.aBlankIdIsAbsentForItsPartnerAndNeverMatched` and the row "Ids that are blank everywhere are absent" of `theKeyFieldIsIdOrTheFirstNonMaskedFieldDeclaredOnBothSides`.
- From T007 review: `PartnerQuery` does not apply defaults; T022 must test that the tool applies offset 0, limit 20 (`PartnerQuery.DEFAULT_LIMIT`) and refresh false when they are omitted.
- From T008 review (fail-closed hardening, to be done in T011, which uses these rules): mask by a type pattern `(?i)keystore|truststore|privatekey|x509|certificate`, compare `encrypted` case-insensitively, and treat a null `fieldType` like an undeclared one (masked) in `PartnerSecretRules`.
- From T010 review (for T011): give the `AS4` entry of `PartnerFixtures.detail()` a secret leaf (e.g. encrypted `Password` with `AES_PASSWORD`) so T011 can test masking on an unknown protocol.
- From T011 round 2 (for T025 and the output tasks): a masked setting with child elements is `Masked(set=true)` without a fingerprint; comparison and output must not assume every set masked value has a fingerprint (no equality statement for such a value). Status: the comparison part is done in T025: `PartnerComparison` makes no `sameOn` statement when any server holding the value set lacks a fingerprint (a set value with child elements, or a value shown on its server and masked on another), and gives no reason text either, since a reason would tell something about the stored form (FR-008); a version outside `COMPARABLE_SECRET_LINES` still yields `equality: "not verified on <version>"`. Test rows "a set value without fingerprint" and "shown on one server, masked on the other" of `PartnerComparisonTest.aMaskedSettingIsStatedAsSetAndServerGroupsOnly`. The output part is done in T027: `ComparedSetting` in `find_partners.output.json` carries `set`, optional `sameOn` and `equality`; a set value without fingerprint shows only `set` (contract paragraph on masked settings).
- From T012 review (for T018): pass the owner to `PartnerManagementPort.read` exactly as `InventoryPort.listDiagrams` returned it (the collision check compares owner case-sensitively).
- From T014 review (for T032): mention in CHANGELOG that `list_nodes` reports 9.0 nodes as `partially_supported` because partner management is unverified there; add the capability row to the docs/tools.md table.
- From T015 review (for T018): `partnersScope(owner, diagram)` keys can collide (`a/b`+`c` vs `a`+`b/c`); serve a cached `PartnerSnapshot` only if its `covered` contains every requested `DiagramRef`, never on the key alone.
- From T016 review (for T017): `AuditRecord` still requires a node, but the contract wants a refusal before any server is contacted written "without node"; decide in T017 (e.g. the requested target, as `ArchiveExportAudit` does, or make node optional) and keep `details` values to booleans and integers (a null value is written as text "null").
- From T017 (for T021): per-server REFUSED/FAILED records carry no `reason`; add `Optional<String> reason` to `PartnerAudit.perServer` and pass the server's error code (code only, no message) so the audit shows why a server was not answered.
- From T019 (for T022): `CloudWorkflows` (`referencableWorkflows[]`) names a cloud only by module name; give it the same optional `moduleId` as `PartnerView`/`CloudCount` (only when two clouds of one diagram share a name) and put it in the contract.
- From T020 (for T026): test a `systemDiagram` filter on two answering servers where only one has the diagram: no call-level NOT_FOUND, the other server is marked. Status: done in T026 (`PartnerServiceCompareTest.aSystemDiagramOnSomeServersMarksItsPartnersOnlyOn`; `NOT_FOUND` with an audit record per server only when no answering server has it: `aSystemDiagramOnNoAnsweringServerIsNotFound`).
- From T022 (for T024 and T026): `FindPartnersTool` writes interim values: `compared` always false, `offset`/`limit` only echoed, `total` = partners returned, `truncated` false, no `nextOffset`; a stage with two answering servers returns merged partners in the single-server shape. T024 must take total/nextOffset/truncated from the paged service result, T026 must set `compared` and the comparison shape; their tests replace the interim ones. Status: the T024 part is done in T024 (`total`/`nextOffset` from the service since the phase 3 review, `truncated` from `Found.truncated` since T024); the service part is done in T026 (`PartnerService.answer` returns `Compared` with `compared: true` for a stage of several servers or a `compareWith`; `Found.compared` stays `false`); the tool switch is done in T027 (`FindPartnersTool.handle` returns `service.answer(query)`, `find` removed, multi-server tool tests expect the comparison shape).
- From the phase 3 code review (for T023 and T026): `find_partners` refuses `protocol`, `workflow` and `compareWith` with INVALID_INPUT `<parameter> is not supported yet` (one REFUSED audit record, no server contacted; `FindPartnersTool.refuseUnsupported`, tests `compareWithProtocolAndWorkflowAreAuditedAsNotSupportedYetWithoutAnyRead` and the `protocol` case of `theLimitsOfTheSchemaAreInclusive`). T023 must remove the refusal of `protocol`/`workflow` and its tests, T026 the refusal of `compareWith` and its tests; the parameters stay in the input schema. Status: `protocol`/`workflow` done in T023 (refusal lifted, the `protocol` case of `theLimitsOfTheSchemaAreInclusive` now expects an empty result; the interim test is now `compareWithIsAuditedAsNotSupportedYetWithoutAnyRead`); `compareWith` moved to T027: the service compares since T026, but the tool switches to `PartnerService.answer` only in T027 together with the output shape and schema, so T027 lifts the refusal and replaces `compareWithIsAuditedAsNotSupportedYetWithoutAnyRead`. Done in T027 (test `aComparisonShowsTheComparedPartnerInTheContractShapeWithoutAnySecret`).
- From the phase 3 code review (for T024): `PartnerService.find` already pages (`offset`/`limit` over the matches of all servers in server order, `Found.total` = number of matches, `Found.nextOffset` while more follow, audit `partnersReturned`/`valuesMasked` count the page); the T022 note's "`offset`/`limit` only echoed, `total` = partners returned, no `nextOffset`" no longer holds. T024 adds the byte bound (`resultLimits.maxChars`, text cuts, `truncated`) on top of this paging. Status: done in T024 (`PartnerBounds`, `PartnerServiceBoundsTest`).
- From the phase 3 code review (for T032): the exports of one server's system diagrams share one `cliExportTimeout` (as the module usage index of `InventoryService`); the docs must say that an operator raises `cliExportTimeout` for owners with many system diagrams.
- From the phase 4 code review (for T026): list mode orders and pages the matches per server, then the servers in config order (`PartnerService.find`); a stage with several answering servers is not in list order across servers. This merged single-server list is interim: with several answering servers the result becomes the comparison shape (`compared: true`), and that shape decides the order across servers; until then a stage list is ordered per server. T026 sets `Found.compared` and puts the comparison fields into `Found` too: the size bound measures `Found`, which the tool returns as it is (phase 4 finding 2). Status: done differently in T026: a comparison is its own result `PartnerService.Compared` (every output field in the schema's order, `compared: true`), ordered and paged across servers by the comparison (`COMPARED_ORDER` in list mode; test `listModeOrdersByDiagramCloudIdAndNameAcrossTheServers`); `Found` stays the single-server shape. The `PartnerService.find` path that still merges a stage per server is used by the tool until T027 switches it to `answer` (T027 then restricts `find` to one server and migrates `PartnerServiceSearchTest`, `PartnerServiceAuditTest`, `PartnerServiceBoundsTest` and `FindPartnersToolTest` cases that target a stage of two answering servers). Done in T027 differently: `find` is removed instead of restricted, `answer` is the only entry point and the migrated tests take `Found` or `Compared` by server count.
- From T026 (for T027 and T032): a comparison search matches shown field values only (an `Equal` value or any value of a `Differs`); a field masked on any compared server is not searched, even where it is shown on another. Docs and the tool description must say so. Status: tool description and contract done in T027; docs stay open for T032.
- From T025 (for T027 and T032): an ambiguous or keyless partner is compared alone and carries `onlyOn: [<its server>]`; the output description and docs must say this means 'compared alone', not 'absent on the other servers' (the `ambiguous` list or the missing `key` tells them apart). Status: tool description, `onlyOn`/`key` schema descriptions and contract done in T027; docs stay open for T032.

## Implementation — phase 2 build

After T017 (d75367a): `mvn -o -q clean verify` green with 5700 unit tests (surefire, 376 suites), 0 failures, 0 errors, 52 skipped, plus 2 failsafe tests (baseline T001: 5495 + 2).

## Implementation — phase 2 code review (2bdb193..cc06fad)

8 findings, none fixed yet:
1. `PartnerAudit` assumes a non-null, non-blank target; an input error on a missing/blank target becomes INTERNAL instead of INVALID_INPUT (relevant for T022).
2. A 404 for one system diagram fails the whole server read (adapter loop); could be a per-diagram `UnreadableDiagram`.
3. Two workflows of the same name in one group pass the collision check, then the reader throws for the whole server (node stale).
4. `PartnerAudit.bounded` can split a surrogate pair.
5. `FAIL_ON_EMPTY_BEANS` on the shared mapper is broader than needed; a refusing serializer for `SecretFingerprint` would be local.
6. Exports per diagram run sequentially (SC-005 budget on many diagrams).
7. XML helpers and workflow selection duplicated between adapter and reader.
8. A null `details` value is written as text "null".

Fix round (uncommitted on 4fb823b), one line per finding:
1. Fixed: `PartnerAudit` accepts a null or blank `target`; a refusal writes its REFUSED record with `node` = the target as given (null → empty text and no `target` input, blank kept, cut to 500 chars) and `group` only if it parses, so the tool can answer INVALID_INPUT; tests for null and blank.
2. Fixed: the adapter accepts HTTP 404 on the export request and reports that diagram as `UnreadableDiagram` with `NOT_FOUND` naming group/name and server (no body); other diagrams are still read, every other status still propagates from `InubitHttpClient`; WireMock test.
3. Fixed: the collision check counts the workflows of the requested name; more than one (also all of the requested group and owner) is per-diagram `UNEXPECTED_RESPONSE` instead of a server failure (`NOT_UNIQUE` if one lies in another group or owner, else the new `NOT_SINGLE` "holds several diagrams of this name in this group"); test. Docs (data-model, research D-4, tasks T005/T012, find_partners contract) updated for 2, 3 and 5.
4. Fixed: `PartnerAudit.bounded` backs off one char when the cut would split a surrogate pair; test with an emoji at positions 500/501.
5. Fixed: `ResultJson` no longer enables `FAIL_ON_EMPTY_BEANS` (back to the mapper default as before 018); a serializer registered for `SecretFingerprint` refuses it with a message without the value; tests for a fingerprint alone and inside a record.
6. Not changed: exports per diagram stay sequential. SC-005 measures the time beyond the read of the owner's system diagrams, servers are already read in parallel by FanOut, and one export at a time keeps the load on the INUBIT server low.
7. Fixed: `XmlTree.Element.elements(name)` and `childText(name)` replace the copies in adapter and reader; `PartnerManagementReader.workflowsNamed(root, name)` (with `GroupedWorkflow`) is the one workflow selection, used by the reader and the adapter's collision check; reader test for it.
8. Fixed: `AuditRecord` rejects a null `details` key or value with NullPointerException at construction; `AuditLog` no longer has a `null` case; test.

Review of the fix round: reviews/phase2-cr-fix-r1.md (APPROVED, 3 minors fixed afterwards: `NOT_SINGLE` message, docs on the refusing serializer and on per-diagram NOT_FOUND). Full build `mvn -o -q clean verify` green: 5707 unit tests (52 skipped) + 2 integration tests.

## Implementation — phase 3 build

After T022 (5097278): `mvn -o -q clean verify` green with 5773 unit tests (52 skipped), 0 failures, 0 errors, plus 2 integration tests (after phase 2 fixes: 5707 + 2).

## Implementation — phase 3 code review (f19caac..3466933)

10 findings:
1. `FindPartnersTool` accepts `compareWith`, `protocol`, `workflow`, but `PartnerService` ignores them; filtered or compared calls silently return unfiltered single-server data.
2. No paging or result bound while the tool is wired: `offset`/`limit` only echoed, every matching partner returned in full.
3. Fan-out deadline gives one `cliExportTimeout` for all sequential exports of a server, whatever the number of system diagrams.
4. `PartnerService.covering` loops with `refresh=true` without a bound; each round is a full export.
5. `PartnerService.loggable` replaces only C0/DEL (not C1 NEL, U+2028/U+2029) and can split a surrogate pair.
6. `loggable` is a copy of `ArchiveExportService.loggable`.
7. Whether a target refusal's record was written is inferred from `ToolError` equality.
8. `similarValues` is `[]` when no server answered.
9. `FindPartnersTool` mirrors `PartnerView`/`EntryView`/`WorkflowView` only for one `@JsonInclude`.
10. Case-insensitive search via `toLowerCase(Locale.ROOT)` on both sides is not full case-insensitive matching.

Fix round (uncommitted on 675b0d6), one line per finding:
1. Fixed: `FindPartnersTool` refuses `compareWith`, `protocol` and `workflow` with INVALID_INPUT `<parameter> is not supported yet` after `PartnerQuery` validation, audited like the other input refusals (one REFUSED record, node = target as requested), before the service is called; the parameters stay in the schema; tool test for all three (no port read) and the 64-char `protocol` case; carry-over line for T023/T026 added.
2. Fixed: `PartnerService.find` pages the matches of all servers (server order, then export order) by `offset`/`limit`; `Found` carries `total` and `nextOffset` (`OptionalInt`, absent on the last page and beyond), the tool writes them, `truncated` stays false (plain paging, as `ResultLimiter`); `referencableWorkflows` lists only clouds of returned partners; each server's audit record and INFO line count the partners of that server on the page; tests in `PartnerServiceSearchTest` (two servers, offset across the server boundary, offset beyond the matches), `PartnerServiceAuditTest` and `FindPartnersToolTest`; carry-over line for T024 (byte bound on top) added.
3. Not changed: the fan-out deadline keeps one `cliExportTimeout` as the budget of all exports of a server, the same budget rule as the module usage index of `InventoryService`; an operator raises `cliExportTimeout` for owners with many system diagrams. Carry-over line for T032 (docs) added.
4. Fixed: `PartnerService.covering` makes at most two cache rounds (the first, then one with `refresh`); if the entry still does not answer (another call's read for other diagrams won again), the call reads once with its own loader without the cache. Test `anEntryThatKeepsNotAnsweringEndsInOneReadOfTheCallsOwn` with a cache double that always hands out an entry for another diagram: rounds `[false, true]`, one port read. `InventoryCache` is no longer `final` so that the test can stand in for it (no other change).
5. Fixed: the new package-private `application.LogText.loggable` cuts at 500 chars without splitting a surrogate pair and replaces C0, DEL, C1 (incl. NEL U+0085) and U+2028/U+2029 with `?`; `LogTextTest` covers NEL, U+2028/U+2029, the other ranges and an emoji at the cut.
6. Fixed: `PartnerService` and `ArchiveExportService` both use `LogText.loggable`; their private copies are removed. The log bound of `ArchiveExportService` (target and diagram group) is now 500 instead of 100 chars.
7. Fixed: `PartnerAudit.refused` returns `PartnerAudit.Refusal(exception, recorded)`; `PartnerService` logs ", no audit record" from `recorded()`, `FindPartnersTool` throws `exception()`. `PartnerAuditTest` asserts `recorded()` for a written and a failed record; both log lines are covered by `anUnknownTargetIsOneRefusedRecordWithoutServerAndOneLine` (written) and `aRefusalLineBoundsTheTargetAndCannotForgeALine` (not written). (`ArchiveExportService` still compares errors for its own refusal line; not part of this finding.)
8. Fixed: `similarValues` only when the search has no match (`total` 0) and at least one server answered; test `noSimilarValuesWhenNoServerAnswered`.
9. Fixed: `PackageBoundaryTest` has no rule for `infra`, and `infra.FileUpdateCache` already uses `application` (which may not use `infra`, so there is no cycle). `ResultJson` registers a mixin for `PartnerService.WorkflowView` that keeps an empty `name` (`@JsonInclude(ALWAYS)`); the mirror records `Partner`/`Entry`/`Workflow` in `FindPartnersTool` are removed. The existing tool test for `{"name": null, "unresolved": true}` passes unchanged.
10. Fixed: the search term is compiled once per call as `Pattern.quote(term)` with `CASE_INSENSITIVE | UNICODE_CASE` and found in each shown field; test with U+0130 (lower-casing changes the length) in the value and in the term, and a term with a regex metacharacter.

Review of the fix round: reviews/phase3-cr-fix-r1.md (APPROVED, 1 minor fixed). Full build `mvn -o -q clean verify` green: 5784 unit tests (52 skipped) + 2 integration tests.

## Implementation — phase 4 build

After T024 (f32d832): `mvn -o -q clean verify` green with 5810 unit tests (52 skipped), 0 failures, 0 errors, plus 2 integration tests (after phase 3 fixes: 5784 + 2).

## Implementation — phase 4 code review (7f4b0d0..330c6e7)

7 findings:
1. `PartnerService.find` bounds the diagram lists against the uncut page before `PartnerBounds.page` drops partners; a returned partner's diagram can be cut from `systemDiagramsRead`.
2. The service reserves `TOOL_FIELDS_CHARS = 64` for fields only the tool adds (mode, compared, offset); fields added later (T026) silently break the bound.
3. `PartnerBounds.size` re-serializes the workflows and sums all partner sizes on every loop step.
4. `Texts.cloud` cuts and measures a cloud's workflow list once per partner instead of once per cloud.
5. List mode sorts per server and pages over servers concatenated; a stage result is not in list order across servers.
6. `PartnerBounds.fit` keeps elements by set membership; equal duplicates of a kept element are all kept.
7. `similarValues` bypass the text cut accounting (`truncated` not set for a cut suggestion).

Fix round (uncommitted on bd49ff9), one line per finding:
1. Fixed: `PartnerBounds.servers` ranks the system diagrams of the page's partners in page order (not list order) and always keeps the first partner's, then returns the page cut to the longest prefix whose partners' system diagrams it lists (`PartnerBounds.Servers.page`); `PartnerBounds.page` bounds only that prefix, so every partner returned has its system diagram in `systemDiagramsRead` and the lists `fixed` was measured with are the ones returned (no second pass); a partner left out this way shortens the page (`truncated`, `nextOffset`). Tests: `PartnerServiceBoundsTest.everyPartnerReturnedHasItsSystemDiagramListedAlsoWhenTheListIsCut` (60 diagrams listed in reverse name order, both modes, all pages; fails if the uncut page is bounded) and `PartnerBoundsTest.theFirstPartnersSystemDiagramIsAlwaysListedAndThePageEndsBeforeAnUnlistedOne`.
2. Fixed: `TOOL_FIELDS_CHARS` removed; `Found` carries every output field in the schema's order (`mode`, `compared`, `offset` added) and `FindPartnersTool` returns it as it is (its `Result` record removed), so `fixed` and the over-bound guard measure exactly what the tool returns. Test: `FindPartnersToolTest.theToolReturnsWhatTheBoundMeasuredWithinMaxCharsAlsoAtTheLargestOffset` (the tool returns a `Found`, within `maxChars` in both modes, near the end and at offset `Integer.MAX_VALUE`; the structured output has no field outside `Found`'s components); `PartnerServiceBoundsTest.assertWithin` checks the plain `Found` size.
3. Fixed: `PartnerBounds.Page` keeps running totals (sum of partner sizes, sum of the sizes of the clouds on the page with a count of partners per server and cloud), updated on add, shrink and remove; nothing is re-serialized per loop step.
4. Fixed: `PartnerBounds.page` cuts each cloud once per server and cloud (`computeIfAbsent`), and `Page` measures each cloud once; partners of one cloud share the cut cloud.
5. Not changed: a stage with several answering servers becomes a comparison in T026 (contract: `compared: true` with a stage of several servers), so the merged single-server list across servers is interim; carry-over line for T026 added (the order across servers is decided by the comparison shape; until then a stage list is ordered per server).
6. Fixed: `PartnerBounds.fit` orders positions (the page's first, then the others in list order), keeps a prefix of those positions and returns them in list order; equal elements are kept only at kept positions. Test: `PartnerBoundsTest.equalElementsAreKeptByPositionNotByValue` (ten equal entries, only a prefix kept).
7. Fixed: `PartnerBounds.similarValues` cuts each similar value to `MAX_FIELD_CHARS` with the marker and reports the cut, which sets `truncated`; the candidates are now the uncut shown values (`SettingValue.Shown.value()`, not `rendered()`, which was cut already and hid the cut); values equal once cut are shown once (`distinct` after the cut, coordinator minor). Test: `PartnerServiceBoundsTest.aLongSimilarValueIsCutWithTheMarkerAndTruncated` (two names differing only after the cut give one cut suggestion, `truncated`).

Review of the fix round: reviews/phase4-cr-fix-r1.md (APPROVED, 1 minor fixed). Full build `mvn -o -q clean verify` green: 5815 unit tests (52 skipped) + 2 integration tests.

## Implementation — phase 5 build

After T027 (6490f01): `mvn -o -q clean verify` green with 5879 unit tests (52 skipped), 0 failures, 0 errors, plus 2 integration tests (after phase 4 fixes: 5815 + 2).

## Implementation — phase 5 code review (36533b6..eaf7199)

2 findings:
1. [medium] `PartnerService.compared` builds each server's `PartnerComparison.Answered` from `read.diagrams()` alone and drops `ServerRead.unreadableDiagrams`: a server that answers but could not read one system diagram (404 after listing, name collision, unexpected response) still counts as answering for that diagram, so every partner of the diagram on the other servers gets `onlyOn` without that server (a false "missing there") while `incomplete` stays false and `notCompared` is empty. No test with an unreadable diagram in a comparison.
2. [minor] `find_partners.output.json` `servers` description says "one entry per targeted {node}"; a `compareWith` comparison also lists the compared ones (Javadoc and contract say so).

Decisions judged (accepted): key field per cloud, blank/masked/absent key compared alone, ambiguity with `moduleId`; `onlyOn` relative to answering servers (partners) or holders (entries, settings); masked settings with `sameOn` only for ≥2 fingerprinted holders on comparable lines; search and similar values on shown values only; paging, `truncated`, `nextOffset` from the returned page and the over-bound guard; one audit record per server; `NOT_FOUND` only if no answering server has the diagram; output `oneOf` on `compared` matches the serialized shape. Verdict: CHANGES REQUESTED.

Fix round (uncommitted on 516c342), one line per finding:
1. Fixed: `PartnerComparison.Answered` carries the server's unreadable system diagrams (`read.unreadableDiagrams()`); `Result` lists them as `notRead` (`incomplete` includes them) and `readers(diagram)` gives the answering servers that read the diagram; a partner's `onlyOn` and the holders of its items, audit counts and referencable workflows are relative to the readers of its system diagram, so a server that could not read it neither holds nor lacks its partners. `NotCompared` gains an optional `systemDiagram` (schema, contract): after each failed server, the diagrams it could not read, only those kept in the bounded `servers[].unreadableDiagrams` with their cut names (review rounds 1–2: unbounded otherwise; a returned partner's diagram could be cut). `PartnerBounds.servers` keeps the page's diagrams first in `unreadableDiagrams` too and ends the page before a partner whose unreadable diagram it does not list. Tests: `PartnerComparisonTest.aServerThatCouldNotReadTheSystemDiagramIsLeftOutOfItsStatementsOnly`, `PartnerServiceCompareTest.aSystemDiagramOneServerCouldNotReadLeavesItOutOfThatDiagramsStatementsOnly` (both fail with `onlyOn` relative to all answering servers, checked by mutation), `…manyLongNamedSystemDiagramsOneServerCouldNotReadStayWithinTheBound`, `…theUnreadableSystemDiagramOfAPartnerOnThePageIsKeptFirst`.
2. Fixed: `servers` description: one entry per targeted {node}, then per compared {node} (`compareWith`).

Review of the fix round: reviews/phase5-cr-fix-r1.md (APPROVED after 3 rounds, 2 medium fixed). Full build `mvn -o -q clean verify` green: 5883 unit tests (52 skipped) + 2 integration tests.

## Implementation — phase 6 build

After T029 (59fdd34): `mvn -o -q clean verify` green with 5895 unit tests (52 skipped), 0 failures, 0 errors, plus 2 integration tests (after phase 5 fixes: 5883 + 2).
