SPEC: specs/024-unsupported-node-tools/spec.md  ROUND: 3
All round-2 majors and minors fixed.
FINDINGS:
- [major] FR-040/FR-044/AS9: `forget` for a lease of a removed server has no group to check the write guards against; the tool may not even be offered.
- [minor] reset input combinations undefined; no AS for forget on "other node answers"; no FORGOTTEN state; cleanup preconditions incomplete (client installation, version); set on a RESET_FAILED lease undefined; visibility of leases of removed servers; "owner-only" wording; AS for nodeName not in list and maxChars out of range.
VERDICT: CHANGES_REQUESTED
Creator resolution: leases of removed servers end automatically as FORGOTTEN 7 days after expiry (audited, startup warning and --check-config until then); manual forget only while the server is configured, guards of its group; exact input combinations; FORGOTTEN state; FR-005/FR-006 preconditions; a set on a RESET_FAILED lease is refused; AS added.
