# Specification Quality Checklist: Stages Whose Servers Present Different Certificates

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-03
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Iteration 1 (2026-11-03): two [NEEDS CLARIFICATION] markers open — FR-002 (binding model) and
  FR-010 (adoption granularity). Both are scope decisions; FR-005, Story 2 scenario 2, SC-004 and
  the Assumptions refer to them and are finalized once they are answered.
- Iteration 2 (2026-11-03): operator answered Q1: A (one expected fingerprint per server) and
  Q2: A (one server per adoption decision); recorded under Clarifications, FR-002, FR-004, FR-005,
  FR-010, FR-010a (new), FR-012, FR-014, Story 2/3 scenarios, Edge Cases, Key Entities, SC-003,
  SC-004 and Assumptions updated. All items pass.
- Review loop (`/speckit-duo:review-spec`): round 1 CHANGES_REQUESTED (4 major, 8 minor, all
  resolved), round 2 APPROVED with 7 minor points; those were resolved afterwards (FR-003, FR-004,
  FR-007 `status`, FR-009 serialization, FR-015 forgetting and old stage-keyed entries, new
  acceptance scenarios Story 1 AS 6–7 and Story 2 AS 7–9). See `reviews/spec-r1.md`,
  `reviews/spec-r2.md`.
- Plan review r1 (2026-11-05, `reviews/plan-r1.md`): operator decision on finding 2 recorded under
  Clarifications (session 2026-11-05) — new FR-005a (certificate check before every StartCLI call
  in a marked stage), FR-005 and SC-004 now cover REST, SOAP and StartCLI, Story 1 AS 8–9, new edge
  cases, Assumptions adjusted. Finding 1: FR-016 names both `TLS_ERROR` causes. Finding 11: FR-004
  rejects a stage-level `cli.url` / `e2e.soap.baseUrl` inherited by several servers of a marked
  stage. All items still pass.
- Content quality: the "Context & Findings" section names existing components
  (`inubit-cert-check`, `PinningTrustManager`, data model 001) as verified facts about the current
  system, following the precedent of spec 006; requirements and success criteria themselves stay
  free of implementation choices (no key names, file formats or code structure).
- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.
