SPEC: specs/022-full-interface-coverage/spec.md  ROUND: 2

Most round-1 findings are fixed:
- Story column in the Background table.
- FR-006 sets a total tool limit.
- FR-002 prefers REST.
- FR-016a covers the run-time data export.
- FR-017 handles a changed set.
- FR-019, FR-019a and FR-019b protect the server's own access.
- FR-020 filters, FR-021 upload folders, per-version states in FR-001, and the minor items.

There are no `[NEEDS CLARIFICATION]` markers. Three findings are major. The worst is that the tag scenarios assume StartCLI behaviour that the project's own spike disproved.

FINDINGS:
- [major] US4 AS10/AS11, US6 AS9, FR-019a, Background table row E (tag entries) – The tag scenarios conflict with what this project already probed, and the tag tool conflicts with FR-006 and FR-016:
  - `docs/research/spike-development-deployment.md` (sections 3 and 6) and `docs/tools.md` (`tag_artifacts`) record three facts:
    - `--tagSetActive` sets a user's *active tag*, not the active version of a workflow.
    - `--tagDiagram` is ignored, so the command tags every diagram of the owner.
    - `--tagDelete`/`--tagRemove` work only owner-wide ("StartCLI removes a tag only for the whole owner").
  - US4 AS10 expects a preview of "workflows whose active version changes (from which version to which)". US6 AS9 expects a deletion limited to a group or path. Both assume behaviour the spike disproved.
  - `--tagWorkflowGroupExclude` is itself an owner-wide form (all groups but some), but the table lists it as a US6 write, while FR-019a excludes owner-wide forms.
  - The clarification and FR-019a speak of "a tool of their own" (singular), yet the tag entries are split between US4 (write enablement only) and US6 (administrative, which per FR-016 needs the objects opt-in). One tool serving both stories breaks FR-006 ("exactly one user story"). FR-019a names only FR-014 as the protection, so it is unclear whether tag delete needs the objects opt-in.
  - Questions and proposed wording:
    1. Restate what `--tagSetActive`/`--tagRemoveActive` change (the active tag of a user), and rewrite US4 AS10 to match. Or move them to the exclusion list if they do not serve an operator task.
    2. Move `--tagDiagram` and `--tagWorkflowGroupExclude` to the exclusion list. Reasons: the first is ignored by INUBIT (probed in the spike); the second is owner-wide.
    3. Add to FR-019a: "A tag removal or deletion is offered only if a check on a test system shows that INUBIT limits it to the named diagram group or repository path; otherwise it is excluded for that reason."
    4. Decide whether the remaining tag operations all go to one story, with one tool and one protection, or to two tools. State whether tag deletion needs the objects opt-in.
- [major] FR-016 / Key Entity "Administration opt-in" vs. US5, US6, US7 – FR-016 names five opt-in areas, but several write entries are not assigned to one, and the protection then becomes a guess in the plan:
  - `POST /isconnector`: starting a business workflow is not "objects", "libraries" or "settings".
  - `PUT|POST /edirules`
  - `plugin --register|--deregister`
  - `import --importMetadata`
  - `--backupFile`/`--backupRepository`
  - `msgsend`/`msgdel`: sending a message would need the user-administration opt-in.
  - Proposal: add a column "Protection" to the Background table (write enablement only / opt-in <area>), or list in FR-016 which entries each area covers.
- [major] US6 – `import --importMetadata` (named in round 1) and `rename …` still have no acceptance scenario, and their safety behaviour is open:
  - Rename: what is renamed (workflow, module, repository path)? Does INUBIT update references in other workflows and in the repository? Does FR-018 take a backup before it (it covers only "deleted or replaced")?
  - Metadata import: does it overwrite the existing metadata? If so, FR-018 should list metadata among the exportable objects, since `export --exportMetadata` exists.
  - Proposal: add one scenario each:
    - Rename: "the preview names old and new name and the objects that reference it; …"
    - Metadata import: "the preview lists the workflows whose metadata is replaced; a backup of their current metadata is stored first".
- [minor] US6 – Several other entries have no scenario. The general FRs cover them, but one line each would make them testable:
  - `dbconnectiontolerance set|drop`: what does "drop" mean to the operator?
  - `plugin --register|--deregister`
  - `PUT|POST /edirules`: replace or add?
  - `DELETE /repository`
  - `delete --deleteModule`: does the preview name the workflows that use the module?
  - In US7, `create -cug|-cur` and `user --addAdditionalGroups|--removeAdditionalGroups`
- [minor] US1 AS6 vs. AS7 – AS6 lists "the maintenance state" among the StartCLI facts (`-maintenanceState`). AS7 says the maintenance mode comes from REST (`get_health`, `/healthcheck`) and is not read again over StartCLI. Remove "the maintenance state" from AS6, or state that `-maintenanceState` is "covered by `get_health`".
- [minor] FR-016a – It does not say whether the run-time data export uses the two-step confirmation and whether it must target exactly one server (FR-014 does not apply to it). Proposed: "It targets exactly one server and needs no confirmation, like `export_archives`" (or the opposite).
- [minor] US3 AS7 / FR-012 – The export of diagrams with excluded groups writes an archive with business content. FR-012 lists only the XSLT report and the metadata export. Add it to FR-012, or say it follows `export_archives` including `archives.productionOptIn`.
- [minor] US5 AS3 (`process validateExport`) – Validation is classed as a read, but it sends an archive with business payloads to a server. On which server does it run, and does it need the run-time data opt-in of that stage, like the export?
- [minor] FR-017 – Two points are open:
  - "Differs" is undefined: is it a different count, or a different set of process ids?
  - Resuming the blocked processes (US5 AS6) is also a bulk change but is not in the FR-017 list.
  - Proposed: "differs means a different set of ids, or a different count where INUBIT reports no ids"; add resume to the list.
- [minor] FR-019b – Three questions:
  - What does `--overwriteUserRoles` do with roles missing from the file? If it replaces all roles, the login user's role is removed exactly when the file does *not* contain it, and the rule protects the wrong case.
  - Which role is "its role", when the login user has several?
  - How is "managed by the directory service" determined, and what happens when it cannot be determined? Proposed: refuse.
- [minor] FR-020 / US7 AS8 (`msgsend`) – A message text is free text that reaches a command line and is shown to other users. Name the check: maximum length, allowed characters, and passing it as one argument-array element.
- [minor] FR-022 / US6 AS6 – Can a configuration value that the FR-010 rule marks as secret be changed? Proposed: "only through a named secret-source entry; otherwise the change is refused".
- [minor] US7 AS5 – "the call says whether INUBIT also removes the user's references" reads as an output, but "INUBIT's two documented choices" suggests a caller parameter. Proposed: "the call names one of INUBIT's two documented choices; there is no default."
- [minor] US4 (`triggerMidnightTask`) – US4 calls its changes "reversible or limited in effect", but the spec does not say what the midnight task does (for example, cleanup of process data). Add a sentence from the vendor documentation, and have the AS9 preview name that effect.
- [minor] FR-018 – "Replaced" is undefined for `PUT /keys` (the module keystore belongs to an exportable module) and for EDI rule uploads. State whether a module backup is taken before a keystore deployment.

VERDICT: CHANGES_REQUESTED
