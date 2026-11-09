# Data Model: Read the Partner Management

**Feature**: 018-partner-management-read | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

All types are immutable. Package `domain/model/partner` unless stated otherwise. None of them can
hold a raw secret value: a secret exists only as `SettingValue.Masked`.

## Domain types

### `DiagramRef`
| Field | Type | Rule |
|---|---|---|
| `name` | String | system diagram name, validated like other diagram names |
| `diagramGroup` | String | diagram group; two refs are equal only if both fields are equal (spec edge case) |

### `WorkflowRef`
| Field | Type | Rule |
|---|---|---|
| `name` | Optional<String> | the `ReferencableTWFs` entry name that the entry's `referencableTWFId` points to; empty if the id does not resolve (shown as unresolved, with a warning) |

The export names workflows without diagram group (research D-2). Filter `workflow` matches `name`
case-insensitively and exactly.

### `SettingValue` (sealed)
- `Shown(String value)`: a non-secret value, cut at 200 characters with `…[truncated]` when
  rendered (not when compared).
- `Masked(boolean set, Optional<SecretFingerprint> fingerprint)`: `set` = non-empty; the
  fingerprint is present only when `set`.

### `SecretFingerprint`
Final class (not a record). Holds the HMAC bytes privately. `equals`/`hashCode` over the bytes;
`toString()` = `SecretFingerprint[redacted]`; no getter; annotated so that Jackson refuses to
serialize it (a test serializes it and expects an exception). Created only by
`SecretFingerprinter` (adapter) through a package-crossing factory that takes the raw bytes and
keeps no reference to the input.

### `Setting`
| Field | Type | Rule |
|---|---|---|
| `name` | String | as in the export |
| `value` | SettingValue | masked by `PartnerSecretRules` |

### `ProtocolEntry` (`ReferencedTWF`)
| Field | Type | Rule |
|---|---|---|
| `protocol` | String | the `protocol` attribute (`FTP`, `WebService`, …), as found |
| `workflow` | WorkflowRef | resolved through `referencableTWFId` |
| `format` | Optional<String> | the `format` attribute, shown as found, not interpreted |
| `protocolSettings` | List<Setting> | export order |
| `additionalParameters` | List<Setting> | export order |
| `position` | int | index among entries with the same `(protocol, workflow)` (FR-013) |

### `Partner`
| Field | Type | Rule |
|---|---|---|
| `fields` | List<Setting> | partner fields in configured order (`Id`, `Name`, …); a masked field stays in the list as `Masked` |
| `entries` | List<ProtocolEntry> | export order; never merged |
| `maskedCount` | int | number of `Masked` values in fields and entries (FR-009) |

Derived: `key()` → `PartnerKey(diagram, cloud, keyField, keyValue)` with `keyField` = `Id` if present
and not masked, else the first configured non-masked field present (FR-013).

### `SystemDiagramPartners`
| Field | Type | Rule |
|---|---|---|
| `diagram` | DiagramRef | |
| `clouds` | List<CommunicationCloud> | the `sdCommunicationCloud` modules that carry `PartnerManagementSettings`, export order; empty → the diagram is reported as read without a Partner Management (FR-007) |

### `CommunicationCloud`
| Field | Type | Rule |
|---|---|---|
| `moduleName` | String | `ModuleName` of the module; part of the partner key (FR-013) |
| `partnerFields` | List<String> | configured partner field names (`PartnerFields`) |
| `referencableWorkflows` | List<String> | names of the `ReferencableTWFs` entries (their local ids are used only to resolve entries) |
| `partners` | List<Partner> | export order |

### `PartnerUsage` (story 4, in `InventoryDetail`)
| Field | Type |
|---|---|
| `variables` | List<String> — distinct `ISPartnerProtocol.*` / `ISPartnerParameter.*`, sorted |
| `partnerManagementNode` | boolean |
| `systemDiagrams` | Optional<List<{diagram, cloud}>> — the clouds whose `referencableWorkflows` contain the workflow |
| `unavailable` | Optional<ToolError> — why the system diagrams were not read (gate, channel, error) |

### `PartnerQuery`
`target`, `compareWith?`, `owner?`, `search?`, `systemDiagram?`, `protocol?`, `workflow?`,
`offset` (≥ 0, default 0), `limit` (1–100, default 20), `refresh` (default false), `mcpClient?`.
Validation before any INUBIT contact (FR-002): ids resolve through `TargetResolver`; overlap of
`target` and `compareWith` (same id, or a stage and one of its servers) → `INVALID_INPUT`; search
1–200 code points without ISO control characters.

## Application types

### `PartnerSnapshot` (cached value, D-6)
`List<SystemDiagramPartners> diagrams`, `Set<DiagramRef> covered`. Stored in `InventoryCache` under
`Key(node, PARTNERS, scope)`, scope `<owner>` or `<owner>|<group>/<name>`. A call needing set S is
served by a fresh snapshot whose `covered` ⊇ S.

### `PartnerComparison` result (D-10)
Per matched partner:
- `key`, `fields` and `entries` grouped as `SettingComparison`:
  - `equal(value)` — same on every answering server holding the setting;
  - `differs(Map<server, value>)`;
  - for masked: `set: Map<server, boolean>` and, if comparable (8.1 on every holder),
    `sameOn: List<List<server>>` (groups with equal fingerprint), else `equality:
    "not verified on <version>"`;
- `onlyOn: List<server>` for partners or entries missing on some answering servers;
- `ambiguous`: duplicate keys reported per server;
- call level: `notCompared: List<{server, error}>`, `incomplete: boolean`.

### Paging state
`total` = number of matched partner keys (single server: partners) after search and filters;
`nextOffset` present while `offset + returned < total`; `truncated` when the size bound cut
detail or entries (FR-017).

## Audit record extension (D-8)

`AuditRecord` (domain/model) gains, additively:

| Field | Type | Written when |
|---|---|---|
| `callId` | Optional<UUID> | every record of a `find_partners` call |
| `details` | Map<String, Object> | non-empty; for `find_partners`: `fromCache` (boolean), `systemDiagramsRead`, `partnersReturned`, `valuesMasked` (integers) |

Other tools leave both empty; `AuditLog` omits empty fields, so existing lines are unchanged.

## State transitions

`find_partners` call: `validated` → (`REFUSED` record, end) | per server `gated` → `cached` |
`read` → `masked model` → combined (single or comparison) → `audited` (all records written) →
`returned`; any audit write failure → `INTERNAL`, no data.
