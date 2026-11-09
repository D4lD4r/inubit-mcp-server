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
