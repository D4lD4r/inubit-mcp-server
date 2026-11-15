TASK: central excerpt masking + owner requirements A/B (no tasks.md ID)  ROUND: 3
STAGE1: FAIL
STAGE2: PASS
TESTS: targeted run (48 classes, 1031 tests, 0 failures, 0 errors, 2 skipped); identifier check exit 0; jshell probe of `SecretTextMasker`.

FINDINGS:
- [major] SecretTextMasker.java:86-87 – Excluding `;` from the rule 8 password class (`[^\s/?#;]+`) now leaks any URL password that contains a `;`, in full (probe: `jdbc:postgresql://app:pa;ss-0001@db:5432/x` and `amqp://guest:Xy;9!k@mq.example` come back unchanged; rule 6 does not apply without `password=`). `;` is valid unencoded in RFC 3986 user-info and common in generated passwords; owner requirement A and Principle II. The reviewer's round-2 suggestion caused this and is withdrawn. – Go back to `[^\s/?#]+`; replace `rule8LeavesSemicolonParametersToRule6` with a test pinning the accepted over-masking of `;…user=admin@srv` (host readable); add a positive test for a password with `;`; revert any docs/CHANGELOG wording describing the `;` exclusion.

PREVIOUS FINDINGS:
- [minor] Rule 8 false positives on `;…user=x@y`, LDAP, file URLs → dropped (over-masking is the accepted safe behaviour; pin it with a test).
- [minor] CHANGELOG list incomplete → resolved.
- [minor] docs/tools.md line width → resolved.
- [minor] Header rule behind the `StartCLI:` prefix → resolved (`quoted()` at CliOutputClassifier.java:423-425; idempotent re-mask in `error()`).

VERDICT: CHANGES_REQUESTED
