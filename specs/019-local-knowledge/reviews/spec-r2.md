SPEC: specs/019-local-knowledge/spec.md  ROUND: 2

All 8 major and 10 minor findings of round 1 are fixed (history, record modes, serialized writes, exact secret rules, masking before paging, the instruction list, mtime as last change, which files count as notes). One new major and 9 minor findings remain. No [NEEDS CLARIFICATION] markers; nothing conflicts with the constitution (Context states how Principle I applies, with the export_artifacts precedent).

FINDINGS:
- [major] FR-004 / US3 / Edge "Files that are not notes" – The name rule (characters, 100 per segment, 4 segments) does not say whether it only limits names the assistant records or also decides which hand-written files count as notes (`Partner (alt).md`, `Störung & Fallback.md`, a note 5 levels deep). "Letters" may mean ASCII or Unicode. – Add to FR-004: "The segment rule applies to names given to the record tool. A hand-written `.md` file whose relative path violates it is [listed with a warning and readable by its path | listed with a warning, not searched or read]. 'Letters' means Unicode letters." Add the case to the edge cases and the US3 independent test.
- [minor] FR-011 / FR-004a – `replace` detects a change by the modification time, which can miss changes (coarse time precision, editors and sync folders that keep or reset times, two changes in one tick). – Compare at the file system's precision and/or a content hash as well; at least name the limitation.
- [minor] FR-016 – "never deletes and keeps the previous versions", but FR-014 drops the oldest after 10. – Say "keeps the last 10 previous versions", or state that the destructive hint stays false although history is capped.
- [minor] FR-008 – Term and topic filter: AND? Empty term lists all? Excerpt when the match is not in the content? Hits per page not bounded. – Add these rules.
- [minor] FR-008 / FR-011 – Is lookup by name case-sensitive (`Foo.md` and `foo.md` on a case-sensitive file system)? – "Lookup by name is exact (case-sensitive); NOT_FOUND lists case-insensitive matches among the similar names."
- [minor] FR-010 (d) – Where an assigned value ends for masking on read (`password: foo bar`). – State the extent (end of line, closing quote, end of XML attribute or element).
- [minor] FR-011 / FR-004 – Does a write add metadata to a hand-written note without metadata? Which `create` fields are required? – State both.
- [minor] US2-AS7 – The server cannot check that the assistant proposes and waits for consent. – Rephrase as "the instructions and the record tool's description contain the propose-then-record rule"; leave the behaviour to the SC-001 live test.
- [minor] FR-013 / FR-015 / Edge "Symbolic links" – No scenarios for an append over 256 KiB, a write over 100 MiB, two concurrent writes, a symbolic link; link behaviour (silent or warning, write at a link name) unstated. – Add scenarios and state the link behaviour.
- [minor] FR-002 – Two profiles that both set the same knowledge directory outside `~/.inubit-mcp` would share it. – "…nor equal, lie inside or contain the knowledge directory of another profile in the default configuration directory (a configuration error naming both profiles)."

VERDICT: CHANGES_REQUESTED
