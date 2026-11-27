SPEC: specs/026-system-diagram-managed-modules/spec.md  ROUND: 2

All 4 major findings of round 1 are resolved: shared module names (FR-003, US1 AS7), partial reads (FR-005, US1 AS6), owner / missing owner / foreign diagram for `read_model_details` (FR-009, US2 AS3/AS4), and unreadable module list or element in the system diagram detail (FR-010, US3 AS4/AS5). All 10 minor findings of round 1 are resolved too (NOT_FOUND items and server errors, `collectedAt` and cache in FR-016, AS5 without a cross-server comparison, deadline budget in FR-017/SC-005, reproducible SC-001, how an `sd` element is recognized, Out of Scope section, definition of "owner").

No new blocker or major. Case-sensitive module name matching agrees with the code: `ModuleUsage.workflowsOf` matches node names to module names exactly; the case-insensitive matching of feature 018 is for workflow names in `ReferencableTWFs` only, so there is no conflict.

FINDINGS:
- [minor] FR-003 – rule only covers some cases: the type check applies only when several modules share the name, so a single module whose type plainly differs from the element's `ResourceType` is "managed" without any note. Question: should that still be "managed", but with a warning that the module's type does not match the element's connector type? Proposed addition: "If exactly one module has the name and its type is known not to match the element's connector type, it is linked and its section warns about the mismatch."
- [minor] FR-003 / US1 AS7 – the "none remains" branch (several modules share the name, none has the matching type) has no acceptance scenario, although FR-003 links all of them with the attribution warning. Proposed: extend AS7 with "if none of them has the matching type, both are 'managed' with the same warning".
- [minor] FR-005 / Edge Cases "The check cannot be completed" – FR-005 requires naming "the first system diagram or element that could not be read", which cannot be met when a list the check depends on fails: the owner's diagram list (lists the system diagrams; used by FR-009 for ownership) or the module list (used by FR-003 for shared names). Proposed: "…the first system diagram or element that could not be read, or the list that could not be read (diagram list, module list)". Also state the FR-003 fallback for `read_model_details` when the module list fails (link with the attribution warning, or "unknown").
- [minor] FR-007 / Edge Cases – `get_inventory_item` (`kind: MODULE`) does not read a module's own properties today (its output schema has none), so FR-007 implies a new read, and the spec does not say what happens when it fails. Proposed addition: "If the module's own properties cannot be read, the section says the comparison is unavailable (error kind, what happened, what to do); the status and the managing elements are unaffected."

VERDICT: APPROVED

Files checked: reviews/spec-r1.md; .specify/memory/constitution.md (no conflict); src/main/java/de/dadecker/inubit/mcp/application/ModuleUsage.java (exact name match, shared-name resolution by type); src/main/resources/schemas/read_model_details.output.json (module result has `name` and `type`); src/main/resources/schemas/get_inventory_item.output.json (module result has no own properties).

## Creator follow-up (after approval)

All four minor findings were folded into spec.md: FR-003 warns about a type mismatch of a single module and AS7 covers the "none matches" branch; FR-005 and the edge case name a failed list (diagram list, module list) and FR-003 states the fallback when the module list cannot be read; FR-007 states what happens when the module's own properties cannot be read.
