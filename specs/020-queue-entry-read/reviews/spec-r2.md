SPEC: specs/020-queue-entry-read/spec.md  ROUND: 2
All 6 major findings of round 1 fixed; most minors fixed. Two new major ambiguities.
FINDINGS:
- [major] Edge cases "not retrievable" vs "vendor interface answers unexpectedly", FR-012 vs FR-013 – different outcomes for the same cause; unclear how a failed content request is told from "entry disappeared". Proposed: unparsable response → UNEXPECTED_RESPONSE; parsable but one part missing/error → partial result (FR-012); content request says entry does not exist → re-read row: gone → NOT_FOUND, still there → partial result. Remove "vendor interface changed" from the partial-result edge case.
- [major] FR-011 bullet 2 – (a) "matches" = equals or contains? (b) element with child content: replace whole content or only text values (leaks). Proposed: contains (case-insensitive); whole content between start and end tag replaced; JSON object/array value replaced whole; name=value ends at first &, ;, whitespace or line end.
- [minor] FR-019 – no fallback if no 9.0 entry can be recorded.
- [minor] FR-009 – which bytes are control characters (C1?), window for short streams, UTF-8 checked on whole stream; UTF-16 → binary.
- [minor] FR-010 – `IS` + upper-case heuristic misclassifies e.g. ISIN; name length bound; do system variables count toward 200.
- [minor] US1 acceptance scenarios numbered 1,2,3,4,6,5.
- [minor] Coverage – add scenarios for FR-005 (several candidates, repeat with fileName), FR-012 (partial), FR-010 bounds, FR-016 (timeout), FR-017 (production warning).
VERDICT: CHANGES_REQUESTED
