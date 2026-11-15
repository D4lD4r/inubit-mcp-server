TASK: central excerpt masking + owner requirements A/B (no tasks.md ID)  ROUND: 4 (extra round approved by the owner)
STAGE1: PASS
STAGE2: PASS
TESTS: no Maven/jshell this round (the orchestrator's full `clean verify` ran in the worktree); read-only checks (`git diff e25eb239`, grep) and `python3 -I tools/check-identifiers.py --all --strict` (exit 0). Earlier runs: round 2 full unit suite 8817 tests, round 3 targeted 1031 tests, 0 failures.

FINDINGS:
none

Checked: `URL_USER_INFO` back to `://[^\s/?#@:]++:([^\s/?#]+)@` (SecretTextMasker.java:87-88) with Javadoc on why `;` belongs to the password and that this over-masks; `rule8MasksAPasswordContainingASemicolon` (both round-3 probes, exact result, nothing of the password left); `rule8MasksSemicolonParametersAfterAPortUpToTheLastAtSign` pins the accepted over-masking for Azure SQL and LDAP (host readable); Oracle `user/pw@host` still a negative; linear-time and `file://` tests unchanged; no docs wording described the `;` exclusion; rest of the diff unchanged from round 3 (`quoted()`, CHANGELOG list, rewrap).

PREVIOUS FINDINGS:
- [major] Rule 8 password class excluded `;` (URL passwords with `;` leaked) → resolved.

VERDICT: APPROVED
