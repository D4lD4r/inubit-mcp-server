# Handoff — IMC-17 · Executing · 2026-11-26 · replanned, spec merged (#109)

## Status
Mandate B is **replanned** on the owner's decisions of 2026-11-26: no recording session, no live
check; INUBIT **6.1 and 7.4** `get_health` and `find_processes` become `UNVERIFIED` (run with the
"not verified … results may be incomplete" warning, docs say untested) on **pinned nodes only**;
`AUTO` never selects 7.4. Constitution **2.1.0 ratified** by the owner in the chat (2026-11-26,
IMC-25, `660683ba`): unverified evidence level for reads (Principles III and V), on reconstructed
fixtures incl. ones built from vendor documentation.
Spec, plan, tasks, research, data-model, contracts, quickstart replanned on this branch;
review-spec: `reviews/spec-replan-r1.md` (CHANGES_REQUESTED) → `spec-replan-r2.md` (APPROVED,
minors applied); review-plan: `plan-replan-r1.md`, `r2` (CHANGES_REQUESTED) → `r3` (APPROVED,
minors applied). Merged to main with PR #109 (`3e0bde00`, CI 5/5 green); the mandate is
re-pinned to spec 025 at `3e0bde00`. No Mandate B code written.

## Jira
- IMC-17: summary/description rewritten to the replan, comment 10062; `Blocked` flag cleared
  after the merge.
- IMC-22 (recording session): cancelled, comment 10061 — **owner** moves it to Done and tells the
  colleague the kit is no longer needed (executor has no transition on Owner action cards).
- IMC-24: superseded, comment 10064 — **owner** moves it to Applied/Done.
- IMC-25 (new, Owner action): ratify 2.1.0 — ratified in the chat (comment 10066); **owner**
  moves it to Done and deletes the wrongly directed link 10025 ("IMC-17 blocks IMC-25"); the
  correct link "IMC-25 blocks IMC-17" exists (comment 10063).
- IMC-6: scope change commented (10065): criterion 1 (detection) dropped, criterion 2 met as
  unverified.

## Implementation progress (paused by the owner, 2026-11-26)
Branch `claude/025-imc-17-mandate-b-impl`, worktree `.claude/worktrees/inubit-74-support-0b6255`,
working tree clean, pushed. Done with `/d4ld4r-speckit-duo:implement` (each APPROVED in round 1):
T038 (`a3e86d9e`), T031 (`b3e8c358`, 1 minor), T032 (`361de399`, 2 minors), T033 (`d9e5585a`,
3 optional minors). Reviews in `specs/025-inubit-74-support/reviews/T0xx-r1.md`.
Resume with T046 (stopped before any change), then T039, T040, T042, T041, T044, T045, T043,
T035+T036 (one commit), T037; then a cleanup pass for the collected minors and the PR.

## Next step
1. Spec MR — done (PR #109).
2. Implementation with `/d4ld4r-speckit-duo:implement`, task range T031–T046 in the order of
   tasks.md "Dependencies" (T038 → T031 → T033 → T046 …, T041 → T044 → T045 → T043, flip T035
   with docs T036, verification T037), one PR.

## Decisions received (2026-11-26, owner, chat)
- 7.4 integration "will not work this way": offer 7.4 support, marked untested in the docs.
- Mechanism: reuse `SupportState.UNVERIFIED` (no new state).
- Scope: "everything 6.1 can" = `get_health` and `find_processes` (only 6.1 CLI adapters).
- AUTO detection of 7.4: no, pinned nodes only.
- IMC-22: cancel; kit and recorder stay in the repo as optional later verification.
- Spec review blocker: unverified may rest on fixtures derived from 6.1 vendor documentation →
  2.1.0 widened; 6.1 also becomes unverified (US9, FR-036, FR-037, SC-010).
- "Ich ratifiziere die Constitution 2.1.0".

## Executor defaults (owner may revise)
- uname version on 7.4 counts as the client's (6.1 behaviour): no server version, no pin
  contradiction from 7.4, no `-sv` run.
- Refusal next step for the other tools on 7.4 unchanged; no extra `list_nodes` warning.
- Warning on every per-node entry after the gate incl. contacted errors; pre-contact refusals
  (launch guard, cooldown, start failure, missing credentials, refused TLS options, no client,
  no free slot) carry none; the call's deadline TIMEOUT of an admitted node carries it.
- German variants built in tests; unreadable `-v` fails closed (CLI_UNAVAILABLE).

## Tried and discarded
- Recording session with the Windows kit (IMC-21/22/24) — cancelled by the owner.
- Constitution 2.0.0 route "evaluated recordings + live check" for 7.4 — still valid for a later
  mandate that raises 6.1/7.4 to supported.

## Assumptions, risks, gotchas
- 6.1 and 7.4 fixtures are reconstructed (6.1 from vendor docs, 7.4 from 6.1); the only real
  output is the 7.4 `help`. First real use may show format differences → bugs fixed as they come.
- Jira: executor cannot transition Owner action cards and cannot delete issue links;
  `createJiraIssueLink` takes `linkType`, and `inwardIssue` is the blocker ("inwardIssue: X,
  outwardIssue: Y" = X blocks Y).
- context-mode intercepts long tool output; read long files with offsets.

## Claims for verification
- None yet (no Mandate B code).
