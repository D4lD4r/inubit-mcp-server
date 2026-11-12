SPEC: specs/022-full-interface-coverage/spec.md  ROUND: 3

All three major findings from round 2 are fixed:
- **Tags:** the spike facts are applied in US4 AS10–AS12 and FR-019a; `--tagDiagram` and `--tagWorkflowGroupExclude` are excluded; there is one tag tool in US4 with write enablement and the production opt-in.
- **Protection:** the Background table has a "Protection" column, and FR-016 lists which entries each opt-in covers.
- **Rename and metadata import:** they now have scenarios (US6 AS9, AS12) and backups (FR-018).

Most round-2 minors are also resolved: US1 AS6/AS7, FR-016a, FR-012, `validateExport`, FR-017, FR-019b, FR-020, FR-022, US7 AS5, and FR-018 for keystores and EDI rules. There are no `[NEEDS CLARIFICATION]` markers, and nothing in the spec conflicts with the constitution (Principles I, II, IV, V, VI and VII checked). The findings left are all minor.

FINDINGS:
- [minor] US6 intro (line 363) vs FR-019a / Background row E – US6's description still includes "delete a tag". Tag deletion now belongs to US4 and its one tag tool. – Remove "delete a tag" from the US6 description.
- [minor] Background / FR-001 / FR-019a – `tag --tagMove` (set or move a tag) appears nowhere in the spec, but the coverage list (SC-001) needs a state for it.
  - Today `tag_artifacts` uses it on development stages, and `deploy_release` uses it on the deployed stage (docs/tools.md lines 2287 and 2729).
  - The owner's round-1 answer ("Tags on operational stages get a tool of their own") could be read to include setting a tag. FR-019a lists only the active tag and removal.
  - Question: is setting a tag on an operational stage outside this feature's tag tool? If yes, add `tag --tagMove` to "Already covered by a tool with the same effect" (`tag_artifacts`, `deploy_release`) and say that the new tag tool does not set tags.
- [minor] FR-008 vs FR-012 / US3 AS6–AS7 – FR-008 says reads "MUST NOT send any command form that changes the server". The spike (docs/research/spike-development-deployment.md, section 3) shows that a StartCLI export lengthens the check-in comment of every exported workflow, so the diagram export with excluded groups is not strictly side-effect free. `msgread` may also mark a message as read. Proposed wording for FR-008: "…apart from side effects INUBIT itself attaches to a read, which the tool description names (the check-in comment trace of exports, as for `export_archives`)". For `msgread`, check on a test system whether it changes the message state.
- [minor] FR-016a / US5 AS5 – The table gives `process listBlocked` the run-time data opt-in, but no FR or scenario says so. FR-016a names only the export and `validateExport`. – Add `listBlocked` to FR-016a, and add "on a stage with the run-time data opt-in" to US5 AS5.
- [minor] US4 AS2 vs FR-015 – AS2 expects a server-side preview with a confirmation code. Under FR-015, the operational changes follow the stage's `write.confirmation`, and with `CLIENT` there is no server-side preview. – Add "and `write.confirmation: SERVER`" to the Given of AS2, or add a scenario for `CLIENT`.
- [minor] US4 – `POST /ready/{on|off}` and `trace on|off` have no scenario of their own. – Add one line each:
  - readiness: the preview names the current and the requested readiness state, and says that readiness stays NOT READY while maintenance mode is on;
  - trace: switching tracing on or off works like AS8 for debug output.
- [minor] US4 "Why this priority" – It calls the changes "reversible or limited in effect". That does not hold for `processdelete error` or tag removal, and the effect of `triggerMidnightTask` is still not described (open since round 2). – Proposed: "most are reversible; deleting processes in error state and removing a tag are not, and are previewed and backed up (FR-017, FR-018)". For the midnight task, either state its effect from the vendor documentation or say that the vendor does not document it, and have the tool description say so.
- [minor] Background row D (`ps -u`) – No scenario or text says what `ps -u` returns, so the plan has to guess. – Name its output in US3 (for example in AS1 or AS5), or explain it in the table.
- [minor] US7 AS1 vs FR-022 – FR-022 mentions "creating a user with a password", but AS1 does not say whether creating a user takes a secret-source entry for an initial password. Is it required, optional or never? – Proposed: "the call optionally names a secret-source entry for the initial password (FR-022); without one, INUBIT's documented default applies", or the opposite.

VERDICT: APPROVED
