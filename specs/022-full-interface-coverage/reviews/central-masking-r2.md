TASK: central excerpt masking + owner requirements A (URL user-info) / B (quoted INUBIT text in messages), no tasks.md ID  ROUND: 2
STAGE1: PASS
STAGE2: PASS
TESTS: targeted run (48 classes, 1026 tests, 0 failures, 0 errors, 2 skipped); full unit suite `-Dtest='*Test,!*IT'`: 541 classes, 8817 tests, 0 failures, 0 errors, 58 skipped; identifier check exit 0; jshell probes of `SecretTextMasker` rule 8 for false positives.

FINDINGS:
- [minor] SecretTextMasker.java:85 – Rule 8 (`://[^\s/?#@:]++:([^\s/?#]+)@`) masks non-secret text after `host:` when an `@` follows later in the token (Azure SQL `jdbc:sqlserver://srv…:1433;database=db;user=admin@srv;…`, `ldap://…:389;cn=admin@example`, `file://C:\Users\jdoe@corp\x.xml`). Fails safe. – Limit the password to `[^\s/?#;]`, or accept and pin with a negative test.
- [minor] CHANGELOG.md:15-17 – The list of places where rule 8 applies is incomplete (also `read_model_details` values, library list lines, helper failure details). – Name them or say "every masked result".
- [minor] docs/tools.md:1806 – line of about 115 chars. – Rewrap.
- [minor] CliOutputClassifier.java:418-420 – Masking in `error()` runs after the `"StartCLI: "` / `"StartCLI login failed: "` prefix, so the header rule (`Password: x` at line start) no longer fires for the quoted text (other rules still apply). – Mask the quoted INUBIT text before prefixing.

Dispute in implementer note 6 (InubitHttpClient messages): accepted; messages are built only from method, request path, status, `TransportErrorTexts` constants and exception class names; the body reaches errors only through the masked `excerpt(response)`. Requirement B is met where INUBIT text is quoted (`CliOutputClassifier.error()`, `parse()` masking every n-OK/n-NOK text; success checks unaffected).

Rule 8 otherwise: scheme, user, host readable; Oracle `user/pw@host` not masked; negatives cover common URL and mail shapes; linear; idempotent; false negative by design for unencoded `/`, `?`, `#` in a password.

PREVIOUS FINDINGS:
- [major] InubitHttpClient window edge → resolved (`InubitHttpClient.java:279-282` drops the last EXCERPT_OVERLAP chars when the body was cut; `Math.max(0, …)`; test `aTokenCutAtTheRawWindowEdgeDoesNotSurviveWhenMaskingShortensTheWindow`; remaining >512-char limit accepted).
- [minor] CliVersionProbe excerpt → resolved.
- [minor] `text/plain` end to end → resolved.

VERDICT: APPROVED
