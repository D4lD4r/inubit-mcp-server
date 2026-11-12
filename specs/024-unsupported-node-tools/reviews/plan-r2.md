FEATURE: specs/024-unsupported-node-tools  ROUND: 2
All round-1 majors resolved (lease generation + cross-process node lock via generalized KnowledgeLock, fail-closed audit for cleanup resets, FR-003 sentence, orphaned unexpired leases decision); round-1 minors fixed; full coverage of FR/US/SC/edge cases.
FINDINGS:
- [minor] T015 creates LeaseView returning List<Lease> before Lease exists (T043) — move LeaseView to T043/T045.
- [minor] T007 IoMessageRefTest reads io-message-ref.vectors created only in T013 — create the vectors in T007.
- [minor] T019/T024 lack the cluster-nodes case details_truncated named in D-26 and the quickstart.
- [minor] D-16 step 3/T051: a cleanup failing its preconditions has no audit record — add REFUSED audit and test; note audit volume of retries (consider 60 s retry).
- [minor] D-15 forget takes only the store lock; D-16 step 6 claim "generation unchanged because node lock held" not true for this path — let forget take the node lock or state/test the failed compare-and-set audit.
- [minor] T053 edits Wiring.java/MainTest.java but is not ordered after T036.
- [minor] (optional) reset description lacks the "address cluster nodes directly" sentence.
VERDICT: APPROVED
