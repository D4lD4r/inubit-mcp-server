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

- From T006 review: a present but blank partner `Id` (`Shown("")`) currently yields a key; T025 (`PartnerComparison`) must decide explicitly whether a blank `Id` counts as absent (FR-013 fallback) or matches.
- From T007 review: `PartnerQuery` does not apply defaults; T022 must test that the tool applies offset 0, limit 20 (`PartnerQuery.DEFAULT_LIMIT`) and refresh false when they are omitted.
- From T008 review (fail-closed hardening, to be done in T011, which uses these rules): mask by a type pattern `(?i)keystore|truststore|privatekey|x509|certificate`, compare `encrypted` case-insensitively, and treat a null `fieldType` like an undeclared one (masked) in `PartnerSecretRules`.
- From T010 review (for T011): give the `AS4` entry of `PartnerFixtures.detail()` a secret leaf (e.g. encrypted `Password` with `AES_PASSWORD`) so T011 can test masking on an unknown protocol.
- From T011 round 2 (for T025 and the output tasks): a masked setting with child elements is `Masked(set=true)` without a fingerprint; comparison and output must not assume every set masked value has a fingerprint (no equality statement for such a value).
- From T012 review (for T018): pass the owner to `PartnerManagementPort.read` exactly as `InventoryPort.listDiagrams` returned it (the collision check compares owner case-sensitively).
- From T014 review (for T032): mention in CHANGELOG that `list_nodes` reports 9.0 nodes as `partially_supported` because partner management is unverified there; add the capability row to the docs/tools.md table.
- From T015 review (for T018): `partnersScope(owner, diagram)` keys can collide (`a/b`+`c` vs `a`+`b/c`); serve a cached `PartnerSnapshot` only if its `covered` contains every requested `DiagramRef`, never on the key alone.
- From T016 review (for T017): `AuditRecord` still requires a node, but the contract wants a refusal before any server is contacted written "without node"; decide in T017 (e.g. the requested target, as `ArchiveExportAudit` does, or make node optional) and keep `details` values to booleans and integers (a null value is written as text "null").
- From T017 (for T021): per-server REFUSED/FAILED records carry no `reason`; add `Optional<String> reason` to `PartnerAudit.perServer` and pass the server's error code (code only, no message) so the audit shows why a server was not answered.
- From T019 (for T022): `CloudWorkflows` (`referencableWorkflows[]`) names a cloud only by module name; give it the same optional `moduleId` as `PartnerView`/`CloudCount` (only when two clouds of one diagram share a name) and put it in the contract.

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
