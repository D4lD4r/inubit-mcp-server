# Data Model: Order-Insensitive Comparison of Workflow Connections

Feature: [spec.md](spec.md) · Contract: [contracts/ports.md](contracts/ports.md)

No persisted format changes. The entities below are views used for comparison.

## Workflow rendering

The XML of a workflow: as rendered by the export (`WorkspaceWriter.render`, one file per workflow
`<group>/<owner>/workflows/<diagram group>/<workflow>.xml`, root `Workflow`) or inside an archive
(`workflow/workflow.xml`, root `IBISWorkflow/…/WorkflowGroup/Workflow`).

```text
Workflow
├── (volatile) CheckinComment, LastUpdate, WorkflowUId, CheckoutUser …
├── IsActive …
└── WorkflowModule *            ← order significant
    ├── … other children (positions fixed)
    └── Connection *            ← order NOT significant for comparison
        ├── @moduleOutId        (target module id, decimal)
        ├── ConnectionId        (decimal)
        └── StyleSheet @labelPosition   (layout)
```

## Comparison forms

| Form | Definition | Used for |
|---|---|---|
| connection-ordered rendering | rendering with P-1 applied, normalized | import-side fingerprints, workspace keep-rule |
| import comparison form | `normalize(connectionsOrdered(reviewed(root, false)))` | `ImportArchivePort.equivalent` |
| release canonical form | same as above | `ReleaseArchivePort.canonical`, release/node fingerprints, artifact states |
| release equivalence form | `normalize(connectionsOrdered(reviewed(root, true)))` | `ReleaseArchivePort.equivalent`, `layoutOnly` |
| legacy forms | the above without `connectionsOrdered` | recognising values recorded by ≤ 0.5.0 only |

None of these forms is ever imported, deployed, stored in the workspace or shown in a difference
report.

## Recorded fingerprint (unchanged formats)

- Backup manifest `intendedState`: artifact → `sha256:…` (import: connection-ordered rendering;
  deploy: canonical artifact state).
- Deployment ledger entry: node → artifact key → `{fingerprint, auditId, tag, at}`.
- Matching rule (P-5): equal to the current **or** the legacy fingerprint.
