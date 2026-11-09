# Contract: changes to existing interfaces

## `get_inventory_item` (story 4, FR-015)

Output `item` gains an optional field, present only for a technical workflow:

```jsonc
"partnerUsage": {
  "variables": ["ISPartnerParameter.FTPProtocol", "ISPartnerProtocol.ServerName"],
  "partnerManagementNode": true,
  "systemDiagrams": [{"systemDiagram": {"name": "…", "diagramGroup": "…"},
                      "cloud": "Module-0901"}],   // absent if not read
  "unavailable": {"code": "UNSUPPORTED_VERSION", "message": "…", "nextStep": "…"}  // absent if read
}
```

- No `variables` and no node → `partnerUsage` has `variables: []`, `partnerManagementNode:
  false`, no `systemDiagrams`, and no system diagram is read.
- Not audited (names only). Uses the cache of `find_partners`.
- Schema `get_inventory_item.output.json` changes additively.

## `list_nodes`

`capabilities.usable` / `notUsable` may contain `partner_management` (with the "not verified"
reason on 9.0.x and other versions, refused on 6.1).

## `--check-config`

The capability lines list "partner management" like the other capabilities (CLI-only pins on 6.1
show it as refused).

## Audit record (JSONL)

Additive fields on lines written by `find_partners`:

```jsonc
{ "auditId": "…", "callId": "…", "capability": "find_partners", "step": "EXECUTE",
  "node": "qa/node1", "inputs": {"target": "qa", "search": "acme"}, "outcome": "EXECUTED",
  "details": {"fromCache": false, "systemDiagramsRead": 2, "partnersReturned": 1, "valuesMasked": 3} }
```

A refusal before any server is contacted writes one record whose `node` is the target as requested (cut to 500 characters; no server named or contacted) and whose `group` is present only if the target parses. Lines of other tools
are unchanged.

## Configuration

No new key. `inventory.owner` and `inventory.cacheTtl` are reused; `resultLimits` bounds the result.

## Record scripts

`tools/record-fixtures.sh` gains two read-only 8.1 cases (D-1; `.ps1` has no REST cases and stays):
`model_export_systemdiagram` (`RECORD_SAMPLE_SYSTEMDIAGRAM`) and `export_systemdiagram` (`RECORD_OWNER`, `RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP`); `tools/anonymize.py` neutralizes every `PartnerManagementSettings` subtree (always on, `--partner-limit 5`): partner Ids and names, hosts, logins, workflow
references and every other non-harmless value become synthetic placeholders, and every secret value
(`type="Password"`, `encrypted="true"`, key material, secret-named settings) with a synthetic
value of the same form (`AES-` + synthetic base64 for AES values), keeping which values were
equal. No recorded ciphertext enters the repository. Diagram, group and workflow names outside that
subtree are replaced afterwards like for every fixture (`synthesize_names.py`, `neutralize.py` with
the local maps); because `synthesize_names.py` treats `Workflow-\d{4}` as already synthetic, the
workflow references inside and outside the subtree are checked by hand before the fixtures are
committed.
