# Plan review – round 1 (2026-11-05)

FEATURE: specs/015-workbench-archives  ROUND: 1  VERDICT: CHANGES_REQUESTED

Coverage complete except the configuration directory (FR-008, US2-2) and partial FR-006
(directory entries), FR-016 (CHANGELOG). Constitution claims are honest; spot-checked code
references are accurate.

## Findings

1. [blocker] FR-008 forbids the configuration directory (`~/.config/inubit-mcp`,
   `%APPDATA%\inubit-mcp`, or the folder of the configuration file in use); no artifact has it.
   Needs: both roots in R-9 step 4 and the data-model refusal list (default directory per
   `ConfigLoader.defaultDirectory()`, ConfigLoader.java:185-194; parent of `LoadedConfig.source()`,
   LoadedConfig.java:13), checked on the given and the resolved path; T012 tests (POSIX and APPDATA
   form, a config file loaded from elsewhere, via a symbolic link); plumbing of the config source
   path `Launcher.serve` (258-279) → `Wiring` (180) → `TestWiring` and other `new Wiring(` callers
   in T011 or T015; Q10, contracts/mcp-tools-delta.md and the folder-rule wording T020/T021 assert.
2. [major] T020/T021/T022 are [P] but all edit `ArchiveToolReferenceTest.java`.
3. [major] T008 says `Repository.zip` holds "no other entry", contradicting R-7/FR-006 (ancestor
   directory entries kept); no fixture has directory entries; R-7 points to a non-existent report.
4. [minor] T013 edits `Wiring.java`/`TestWiring.java` like T011 but may run in parallel.
5. [minor] T015 does not name `Wiring.java`/`TestWiring.java`.
6. [minor] T022 does not check the client confirmation and the Windows ACL sentence (FR-016).
7. [minor] A write failure on the first archive is audited `FAILED`, FR-013 says partial results
   are "partially completed".
8. [minor] The leftover `.part` warning is per archive in R-11, call-level in the data model.
9. [minor] The plug-in type string `Workflow Connector` comes from a REST log fixture, not a
   recorded `module/module.xml`; mark as unverified, confirm in T024.
10. [minor] T010/T007 are large; split result bounding (R-14); state T010's interim folder handling.
11. [minor] T018: no test for the 128 MiB export limit passing through unchanged.
12. [minor] T011: "`TestWiring` if it builds handlers" is vague (5 `new Wiring(` call sites).
