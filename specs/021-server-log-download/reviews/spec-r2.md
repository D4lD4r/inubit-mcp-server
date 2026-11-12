SPEC: specs/021-server-log-download/spec.md  ROUND: 2

All 4 major and 8 minor findings from round 1 are fixed.

FINDINGS:
- [major] FR-009 steps 3–4 / FR-010 – Masking changes how lines line up with the file. FR-011 of feature 020 replaces the whole content between a start tag and its end tag with one token, child elements included; without an end tag it masks up to the next `<`. A secret value spanning several log lines becomes one line in the masked window, so "last `tailLines` lines of the masked window" and "line numbers absolute in the downloaded file" allow two readings. – Proposed wording for FR-010: "Masking keeps every line break of the window: a masked value that spans several lines is replaced by the mask token on its first line, and the other lines it covered remain as empty lines, so line N of the masked window is line N of the window." Alternative: count a multi-line masked value as one excerpt line carrying the number of its first file line.
- [minor] FR-009 step 1 / edge case "Binary or non-UTF-8 content" – The last 50 MiB usually start mid-line and may start mid UTF-8 character, which makes a valid UTF-8 file fall back to ISO-8859-1 and makes line counting of the cut line unclear. – Proposed: "The scanned window starts at the first line beginning within the last 50 MiB of the file; a partial first line is not part of the window."
- [minor] US1 scenario 1 – "the last 200 lines as excerpt" only holds if they fit the default `maxChars` of 20,000. – Proposed: "(fewer, with a truncation marker, if they exceed `maxChars`, FR-009 step 5)" and a scenario that checks the cut and its marker.
- [minor] FR-009 "the time of the download" – No format or time zone. – Proposed: "UTC, ISO-8601, the same instant as in the file name".
- [minor] FR-010 limit "start tag before the window" – Documented, but still a path for an unmasked secret (Constitution II) for logs over 50 MiB. – Question: accept the residual risk, or leave out lines that sit inside a secret-named element opened before the window ("when in doubt, lines are left out rather than shown")?

VERDICT: CHANGES_REQUESTED
