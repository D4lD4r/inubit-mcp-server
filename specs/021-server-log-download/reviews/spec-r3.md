SPEC: specs/021-server-log-download/spec.md  ROUND: 3

All five round 2 findings are fixed. No conflict with the constitution found.

FINDINGS:
- [minor] FR-010 line breaks – A masked value that ends partway through a line (`abc</password> next`): emptied line or unmasked rest kept? Proposed: "A line that the masked value covers only in part keeps its unmasked text; only lines the value covers completely become empty."
- [minor] FR-010 value opened before the window – The rule only covers XML end tags; a secret-named JSON key whose object/array value opens before the window is not detected. Accept as documented limit or extend the rule; a documented limit is enough.
- [minor] US2 scenario 4 vs edge case "Missing versus empty" – Scenario 4 always expects NOT_FOUND; align with the edge case.
- [minor] FR-009 step 1 / FR-010 – No acceptance scenarios for the security-relevant excerpt rules: log > 50 MiB (window start, note, absolute line numbers), multi-line masked value (aligned line numbers), secret end tag with start tag before the window. Add as US1 scenarios 7–9.

VERDICT: APPROVED
