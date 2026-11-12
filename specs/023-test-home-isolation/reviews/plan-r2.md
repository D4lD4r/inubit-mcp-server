FEATURE: specs/023-test-home-isolation  ROUND: 2
Round-1 findings 1, 2, 4-9 resolved (checked against code); 3 taken up, see below.
FINDINGS:
- [major] T008 / contract – LiveTargetTest (package live) cannot reach package-private Wiring.homeLocations() nor the wiring's ProfileConfig through TestWiring. State how the test observes the values: assert wiring.userHome() below the LiveTarget temp dir (WiringHomeLocationsTest pins backups/deployments/packages to userHome) and make isolated(...) package-private for audit/workspace/knowledge.
- [minor] T008 / D-4 – LiveTarget is a record; hold the lazy temp directory in a holder component set in resolve/resolveDeployment (new LiveTarget(...) at :97 and :170).
- [minor] T006 / D-5 – TemplateGrammarTest.withoutComments finds literals before comments (Javadoc "user.home" would be reported), shifts line numbers, ignores char literals and text blocks. Specify a single-pass scanner (code, string, char, text block, line comment, block comment) blanking comments but keeping newlines.
- [minor] quickstart step 1 – '+' separates methods in surefire -Dtest; use a comma.
VERDICT: CHANGES_REQUESTED
