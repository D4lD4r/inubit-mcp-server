TASK: central excerpt masking (owner-requested hardening, no tasks.md ID)  ROUND: 1
STAGE1: FAIL
STAGE2: PASS
TESTS: targeted run incl. CliOutputClassifierTest, InubitHttpClientTest, ServerSettingsSecretLeakTest, ErrorExcerptSecretLeakTest, CliExportRunner*/CliLibraryRunner*/CliUserRunner*Test, *SecretLeak*Test, SecretTextMaskerTest: exit 0 (fresh reports: 541 classes, 8796 tests, 0 failures, 0 errors, 58 skipped). Identifier check exit 0. The `InubitHttpClient.excerpt` window edge case was replayed in jshell against target/classes.

FINDINGS:
- [major] src/main/java/de/dadecker/inubit/mcp/adapter/rest/InubitHttpClient.java:616-618 – Part of a secret survives at the raw window edge: the code masks raw[0, 4096+512) and cuts the masked text at 4096; when masking shortens the window below 4096 (e.g. a long secret value early in the body replaced by a short placeholder), nothing is cut and the excerpt runs to raw 4608, where a Bearer/Basic token crossing the edge is too short to match and its start passes unmasked (reproduced: excerpt ends in `</b Bearer abcdefg`). New with the masking. – When the raw text was cut, also drop at least EXCERPT_OVERLAP chars from the end of the masked window (or cut at the masked position of raw 4096); failing-first test of this shape.
- [minor] src/main/java/de/dadecker/inubit/mcp/adapter/cli/CliVersionProbe.java:79-86 – The version probe builds its own StartCLI excerpt outside the classifier, only scrubbed; CHANGELOG and docs claim every StartCLI excerpt is masked. – Mask it too or narrow the wording.
- [minor] src/test/java/de/dadecker/inubit/mcp/security/ErrorExcerptSecretLeakTest.java:166-183 – The end-to-end `list_inventory` test covers HTML, XML, JSON but not `text/plain`. – Add a `text/plain` answer.

Checks passed: all excerpt paths of both components go through `excerpt()` (classifier l. 202, 365; HTTP client l. 550, 555); removed local masking calls really redundant (`CliExportRunner.masked`, `CliLibraryRunner`, `CliUserRunner.throwFailure`; `CliSettingsRunner` via `classify`; `UnknownOutput`, `HelperFailures`, `ModuleDetailXmlParser` keep their own); double masking in the REST path harmless (placeholders stable); non-secret content unchanged; leak tests end to end over MCP for `export_archives` `workflows` and `list_inventory` (HTML/XML/JSON), `get_server_settings` for session and REST excerpts, logs and audit; JDBC, `pwd=`, Bearer forms included; CHANGELOG and docs present.

VERDICT: CHANGES_REQUESTED
