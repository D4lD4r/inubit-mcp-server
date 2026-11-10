PLAN: specs/019-local-knowledge/plan.md (with research.md, data-model.md, contracts/, quickstart.md) ROUND: 4

The single-quoted front-matter serialization, complete-block bounds, and parsed history ordering are consistently documented, and all four contract JSON blocks remain valid. However, the new `ref` mechanism is not fully integrated into the service model and recording flow, and the history counter allocation remains unsafe after pruning.

FINDINGS:
- [major] research.md:458-468,594-603; spec.md:371-376,417-418 – A ref-based append/replace writes its durable `PENDING` audit record before resolving the ref, so that record contains `name=***`; if publication succeeds and the final audit write fails, the only durable audit record cannot identify the changed note, violating FR-019, SC-005 and Constitution Principle VII – resolve and secret-check the ref before writing `PENDING`, then revalidate it under the lock, so the pre-write record contains the masked note name.
- [major] research.md:387-419,443-456,604-608; data-model.md:57-61; contracts/mcp-tools-delta.md:75-81,187-210 – The schemas allow ref-only reads and writes, but the design still selects read mode solely by `name`, declares `name` required, always scans and validates it before ref resolution, and models only `read(String name)`, `Record.String name` and a `Hit` without `ref`; no HMAC key/generator/resolver member is modeled – introduce an explicit name-or-ref selector and ref generator/resolver in the data model, add `ref` to hits, and update D-9, D-10 and D-13 so ref-only requests have an implementable, consistent path.
- [major] research.md:524-529; data-model.md:14,54; quickstart.md:45 – Counter allocation is not defined after pruning: an implementation that probes from `-000` for the first free name will reuse a pruned low counter, after which parsed ordering immediately treats the newest snapshot as oldest and deletes it, violating FR-014 and SC-005 – allocate one more than the greatest matching counter for that instant, never reuse lower gaps, fail above `-999`, and test at least twelve same-instant writes while asserting the retained version contents.

VERDICT: CHANGES_REQUESTED

RESOLUTION (applied in "Feature 019: plan review round 4"):
- D-10 step 4 "Target": a `ref` is resolved (fresh listing), secret-checked and name-ruled before the `PENDING` record, which therefore always names the masked note; under the lock the ref is resolved again and must still point to the same path (`NOT_FOUND` otherwise). D-13 inputs updated.
- Data model: `NoteSelector` (`ByName`/`ByRef`), `NoteRefs` (per-process HMAC key, `of`, `resolve`), `ref` in `Hit`, `read(NoteSelector, offset)`, `Record.target`. D-9 selects the read shape by `name` or `ref`; D-10/D-13 require `mode`/`content` plus exactly one of `name`/`ref`.
- D-11: the counter is one more than the greatest counter for the same instant; gaps never reused; twelve-version test asserting the kept contents.
