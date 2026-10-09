# Contract: archive ports and the connection-order rule

Feature: [spec.md](../spec.md) · Research: [research.md](../research.md)

No MCP tool, parameter or result shape changes. The contract changes are internal: two port
interfaces in `domain/port` and the shared rule in `adapter/archive/v81`.

## P-1 Connection-order rule (`WorkflowComparison`, package-private)

`connectionsOrdered(Element e)`: returns `e` with, for every `WorkflowModule` element whose parent
is a `Workflow` element (at any depth), the direct `Connection` element children re-ordered within
the positions they occupy:

1. connections with a plain-decimal `moduleOutId` attribute **and** a plain-decimal
   `ConnectionId` child text, by (`moduleOutId`, `ConnectionId`) numerically;
2. then all others; ties in group 1 and everything in group 2 by the `XmlNormalizer`
   serialization of the connection element (UTF-8, unsigned byte order).

Other children keep their positions and relative order. Nothing else in the document changes.
Idempotent: `connectionsOrdered(connectionsOrdered(e)) == connectionsOrdered(e)`.

`reviewed(root, withoutActive)`: as today in both adapters (drop `CheckinComment`, `LastUpdate`,
`WorkflowUId`, `ModuleUId`, `CheckoutUser`; with `withoutActive` also a workflow's `IsActive`), now
in one place.

## P-2 `ImportArchivePort`

| Method | Change |
|---|---|
| `equivalent(path, expected, actual)` | XML: compares `normalize(connectionsOrdered(reviewed(root, false)))`. `.xsl`/`.wsdl`, other files, null and parse failures: unchanged. |
| `connectionOrdered(path, file)` *(new)* | XML: `normalize(connectionsOrdered(root))` — the rendering with only the connection order normalized (volatile elements kept). Not XML / unparsable: an unchanged copy. Used only for fingerprints and the workspace keep-rule; never imported or written. |
| `differsOnlyInConnectionOrder(path, existing, rendered)` *(new)* | true only if the bytes differ, both files are already in normalized form, and their connection-ordered forms are equal; used by the workspace keep-rule (P-6). |

Invariant: `equivalent(p, a, b)` is true whenever `a` and `b` differ only in connection order.

## P-3 `ReleaseArchivePort`

| Method | Change |
|---|---|
| `canonical(path, file)` | `normalize(connectionsOrdered(reviewed(root, false)))`; parse failure: unchanged copy. Still only compared or hashed. |
| `equivalent(path, release, target)` | compares `normalize(connectionsOrdered(reviewed(x, true)))`. |
| `layoutOnly(path, release, target)` | `LayoutDiff.layoutOnly` over the connection-ordered forms. |
| `legacyCanonical(path, file)` *(new, `@Deprecated`)* | today's `canonical` (no connection ordering); used only to recognise fingerprints recorded by ≤ 0.5.0 (P-5). |

## P-4 Fingerprints

`ConflictDetector.fingerprint(Map<String, byte[]>)` is unchanged. Its inputs change:

- import side (`ConflictDetector.detect`, `ImportService` `set_active`, `requireStateLeftBy`,
  `Fresh`, `updateManifest`): renderings mapped through `ImportArchivePort.connectionOrdered`;
- release side: `canonical` (P-3), as before.

## P-5 Recorded fingerprints from ≤ 0.5.0

A *persisted* fingerprint (backup manifest `intendedState`, deployment ledger artifact state)
matches the current state if it equals the current fingerprint **or** the legacy fingerprint
(import: over the plain renderings; release: over `legacyCanonical`). Newly recorded values always
use the current fingerprint. Short-lived preview states are not affected (in memory only).

## P-6 Workspace keep-rule

When writing a server rendering of a workflow file into the workspace (export via
`WorkspaceWriter.write`, after an import via `ImportService.writeBack`, after a deployment via
`DeployService.commit`): if the file exists and `differsOnlyInConnectionOrder(existing, rendered)`
(one shared implementation in `WorkflowComparison`), the existing file is kept unchanged (not
written, not committed, not reported). Every other case — including a file that differs in
formatting, and an imported workflow whose rendering carries a new check-in comment — writes the
new rendering exactly as today.
