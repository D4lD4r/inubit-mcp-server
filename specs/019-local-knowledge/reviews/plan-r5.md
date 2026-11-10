PLAN: specs/019-local-knowledge/plan.md (with research.md, data-model.md, contracts/, quickstart.md) ROUND: 5

The three round-4 defects are substantively resolved: refs are resolved before `PENDING` and re-resolved under the lock, the data model now includes `NoteSelector`, `NoteRefs`, and hit refs, and history counters advance beyond the greatest counter with content-retention coverage. Two residual name-or-ref contradictions remain in descriptive and error-contract text; no new secret leak was found.

FINDINGS:
- [minor] research.md D-9; contracts/mcp-tools-delta.md read_knowledge description – list/read selection is still described as "no name" versus "with name," which classifies a ref-only request as listing despite later text and the output contract classifying it as a note read – define listing as neither `name` nor `ref` and reading as exactly one of them, including the model-facing description and `name` property description.
- [minor] research.md D-14; contracts/mcp-tools-delta.md record_knowledge validation text – both broadly classify name-rule and secret-check failures as `INVALID_INPUT`, contradicting D-10 step 4, where an already-resolved ref targeting a non-writable hand-written note returns `PRECONDITION_FAILED` – qualify `INVALID_INPUT` as applying to supplied names and new text, and explicitly classify a resolved ref whose target fails the name or secret rule as `PRECONDITION_FAILED`.

VERDICT: CHANGES_REQUESTED

RESOLUTION (applied in "Feature 019: plan review round 5"): both minors fixed (listing = neither `name` nor `ref`, reading = one of them, in D-9 and the tool description; D-14 and the record contract qualify `INVALID_INPUT` to the given name and new text and map a non-writable `ref` target to `PRECONDITION_FAILED`, also in the error table). No major finding remains after five rounds; the plan review is closed.
