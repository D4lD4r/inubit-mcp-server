TASK: second merge of origin/main (feature 022 US3: #90, #91) into feature 024  ROUND: 1
STAGE1: PASS
STAGE2: PASS

TESTS: ALL (`mvn clean verify`): exit 0, 593 suites, 10352 tests, 0 failures, 58 skipped, failsafe ITs green; `check-identifiers.py --all` exit 0; no conflict markers; no new line over 100 columns.

How: main brings in exactly its 260 changed files plus two integration fixes (ConfigSummary.java, NodeToolsDocsTest.java); 024 brings onto main only its own 466 files; lost-content check over the 42 files both sides changed: 144 missing lines, all deliberate rewrites (counts, javadoc lists, argument lists, combined CliCommand sets, exclusion lists, expected tool lists).

Points:
1. MODEL_DETAILS supported on 8.1/9.0 as on main; 024 node tools supported on 8.1/9.0 except IO_LOG_READ "not yet verified"; unknown-version map = union of both parents.
2. PortSet chain 17 → 13 → 12 → 11 → 10 → 9 → 8 correct; V81PortSet with helper host passes modelDetails and the real node-tool adapters; V61 17 args; wrappers cover all ports; main's model-details test covers both paths.
3. CliCommand: 022's library/exportMetadata/quotedList/ownerWithoutGroups rules and 024's trace long forms, traceForm(), LEVEL_OPTIONS and refusals intact; CliCommandTest 30 + 16 + 7 = 53.
4. 30 tools ("All thirty tools"), 60 schemas, announcement order, list_nodes enum order, CHANGELOG complete with blank-line separation, coverage page regenerated (254 entries; differs from main only in 024's four rows and counts).
5. Integration fixes correct and not weakening.

FINDINGS:
- [minor] Gateway.java:20-21 – short Javadoc line after a rewrap. → fixed.
- [minor] docs/tools.md:~220 – short line in the merged paragraph. → fixed.

VERDICT: APPROVED
