SPEC: specs/019-local-knowledge/spec.md  ROUND: 3

All round-2 findings are fixed (1 major, 9 minor). No [NEEDS CLARIFICATION] markers; nothing conflicts with the constitution (Principle I scoped to INUBIT state with the export_artifacts precedent; the audit record uses the profile in place of the target environment, which fits a local write). One new major from allowing Unicode letters; six minors remain.

FINDINGS:
- [major] FR-004 / FR-004b / FR-011 `create` – Names with Unicode letters are not compared under normalization. On macOS (APFS, case- and normalization-insensitive) `Störung.md` may exist in NFD while the assistant sends NFC: lookup gives NOT_FOUND, and the `create` collision check misses it, so the new file can silently overwrite the note without a history copy (breaks FR-011, FR-016). – "Names are compared after Unicode NFC normalization. Lookup is exact on the normalized name and is done against the names as listed, not by opening the path. `create` is refused with `CONFLICT` if a note exists whose name matches after NFC normalization and Unicode case folding. A `create` never overwrites an existing file." Add an edge case.
- [minor] US2 Independent Test / US2-AS3 – Still speak of "the last change it read" / "stale last change" instead of the version token. – Reword.
- [minor] US3 Independent Test – "umlauts" now follow the rule. – Use `&` instead.
- [minor] FR-006 / FR-008 – Names are not masked, single-lined and cut like titles; hand-written names are an injection path into the instructions. – Apply the rule to names too; add a hand-written file name to US1-AS6.
- [minor] FR-004 – A malformed metadata block on `append`/`replace` is not covered. – State one rule.
- [minor] FR-004 / FR-011 – A name ending in `.md` gives `partner.md.md`; the case of the extension (`.MD`) is not covered. – Refuse names ending in `.md`; say which extensions count.
- [minor] FR-013 – Unclear whether the bound values are final or placeholders. – "Bounds (fixed, not configurable, documented in docs/setup.md): …" or "the plan may adjust them".

VERDICT: CHANGES_REQUESTED
