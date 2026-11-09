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
