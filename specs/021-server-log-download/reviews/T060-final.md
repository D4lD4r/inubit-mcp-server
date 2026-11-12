TASK: T060 – final cross-cutting code review of `git diff main...HEAD` (independent, read-only)

FINDINGS:
1. [medium] The call deadline did not cover the CredentialGuard wait on both server-log paths; on the trace path StartCLI got a Duration fixed before the pin probe and the guard wait (FR-013, D-15). → FIXED: CredentialGuard.acquire(Duration, setting) bounded wait with TIMEOUT; CliRunner.run(…, deadlineNanos, …) recomputes the StartCLI timeout after pin probe and guard; other tools keep the unbounded acquire().
2. [low–medium] Non-ASCII known credentials could appear unmasked when the excerpt window falls back to ISO-8859-1 (FR-010). → FIXED: SecretForms adds the UTF-8-read-as-ISO-8859-1 reading of every form (raw, JSON, XML); also closes the same gap of read_queue_entry.
3. [low] An Error during the excerpt left the audit record PENDING (FR-015). → FIXED: ServerLogService writes FAILED "INTERNAL: <Error>" and rethrows; private directory still deleted.
4. [info] Folder rules checked at step 4 but used at placement up to cliExportTimeout later (same-user threat, as export_archives). → ACCEPTED.

Checked clean: constant file names (webservice.log, trace --download only), credentials only on stdin, masking order (mask whole window, then contains), file placement (CREATE_NEW, owner-only, never replaced, cleanup), private temp directories (registered, swept, deleted on every path), deadline elsewhere, audit transitions, version gating, announcement/admission consistency, contracts.

FIX REVIEW (task-reviewer, round 1): APPROVED; three minors fixed before commit (CredentialGuardTest proves the probe is free after a timed-out wait; CliTraceRunnerTest upper bound widened to 3.4 s; Latin-1 reading of the JSON/XML-escaped forms with a test). Full clean verify after the fixes: 7961 + 4 tests, 0 failures.
