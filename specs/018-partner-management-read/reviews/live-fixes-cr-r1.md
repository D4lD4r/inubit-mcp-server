# Live check T034 fixes — review of the fix round (23d0961..b076c07)

No findings.

1. Race (7cd3a79): closed. `found()` and `compared()` take one `Map.copyOf` of the admitted
   servers right after the fan-out and pass it to both `serverView` and `gateWarnings`, so a failed
   server's warnings and gate count come from the same list; an answered server takes both from
   its payload. No other path reads the live map (`read(PartnerQuery)` and `awaitReference` pass a
   fresh map nobody reads; `referencingClouds` uses the payload's consistent pair). `Map.copyOf`
   cannot meet a null (a `ConcurrentHashMap` holds none; values are `List.copyOf`), and each server
   puts its key once per call. A put after the snapshot is not shown, as for a server that timed
   out before it was admitted.
2. Docs (b076c07): accurate against `DiagramExportParser` (non-ZIP in `workflowEntry`, any
   `IOException` in `readEntry`, `total > MAX_ENTRY_BYTES` = 64 MiB in `bounded`, all server-level
   `UNEXPECTED_RESPONSE`, none caught by the adapter's per-diagram handler) and consistent across
   docs/tools.md, the contract and the spec.
3. Rest of the fix (84c4b98, d5184e4): no high-confidence bug. The adapter's per-diagram handler
   catches only a missing or malformed `workflow.xml`, a mismatch and a reader
   `IllegalArgumentException`; a server the gate refuses has no entry, so no warnings and count 0.

Verdict: APPROVED
