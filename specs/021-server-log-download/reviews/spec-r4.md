SPEC: specs/021-server-log-download/spec.md  ROUND: 4

All four round 3 minors are fixed. The withheld-excerpt rule strengthens Principle II; no constitution conflict.

FINDINGS:
- [major] US1 scenario 8 vs FR-010 – "lines 11–12 are empty" contradicts "the unmasked rest of line 12 after the end tag stays" and FR-010's partial-line rule. Proposed: "the value is replaced by the mask token on line 10, line 11 is empty, line 12 keeps `</password>` and the text after it, and line 13 keeps number 13."
- [minor] US1 scenario 9 – "every line … up to and including the end tag is masked" may mask the text after the tag; proposed: "…up to the end tag is masked; the line with the end tag keeps the end tag and the text after it."
- [minor] FR-010 withheld excerpt "in time" – bound not stated; distinguish from the addressed server's own login credentials (SECRET_UNRESOLVED/TIMEOUT). Question: does a never-resolving credential of another node withhold every excerpt, and does the hint say so?
- [minor] FR-010 withheld excerpt has no acceptance scenario; proposed US1 scenario 10.
- [minor] FR-013 terms – "excerpt permit" undefined; "credentials" ambiguous (login vs masking). Proposed rewording.
- [minor] FR-013 "file placement … bounded by their size limits (FR-009)" – the file has no size limit; proposed rewording.
- [minor] Background / FR-017 – command wording: "`trace -d <file>` (long form `trace --download <file>`)"; FR-017 "`trace --download`".

VERDICT: CHANGES_REQUESTED
