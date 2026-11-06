# Spec review – round 3 (2026-11-05)

SPEC: specs/015-workbench-archives/spec.md  ROUND: 3  VERDICT: CHANGES_REQUESTED (one major)

All 11 round-2 findings are resolved; no [NEEDS CLARIFICATION]; no conflict with the constitution.

## Findings

1. [major] `missingModules` as `{name, pluginType}` cannot be filled: a module missing from the
   export has no `module/module.xml` entry, the only source of its plug-in type; nodes carry only
   `ModuleId`, `ModuleName`, `OriginalModuleName`. Use `{name}`.
2. [minor] The Workflow Connector warning can only be detected from the connector's plug-in type;
   a connector among the missing modules cannot be detected.
3. [minor] The `resultLimits.maxItems` budget should include `missingModules`.
4. [minor] FR-010: parent folders created by the call owner-only too; does a created folder stay
   when the first archive fails?
5. [minor] Two round-1 clarification entries are superseded but not marked.
6. [minor] `SECURITY.md` should say that only the listed sync roots are detected.
7. [minor] FR-013 audit shape (one record updated vs. start + outcome) and error precedence — plan.
8. [minor] Tool, parameter and opt-in names — plan.

## Resolution (by the creator after round 3)

Findings 1–6 fixed in spec.md (FR-005/FR-012/US3-4 `{name}`, connector wording, budget, FR-010,
superseded entries marked, FR-016 sync-root scope). Findings 7–8 are left to plan.md as the reviewer
proposed. With the major fixed the reviewer considers the spec ready for planning (max rounds
reached).
