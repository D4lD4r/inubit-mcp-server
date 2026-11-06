# Contract delta: `get_health` (feature 016)

The result gains one optional top-level field; `reports` is unchanged.

```json
{
  "reports": [ /* HealthReport, unchanged */ ],
  "serverUpdate": {
    "currentVersion": "0.7.1",
    "latestVersion": "0.8.0",
    "updateAvailable": true,
    "releaseUrl": "https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0",
    "checkedAt": "2026-11-06T12:00:00Z",
    "notice": "inubit-mcp-server 0.8.0 is available (running 0.7.1): <releaseUrl>. Update manually: ..."
  }
}
```

- `serverUpdate` is **absent** when the update check is off or no check has succeeded yet.
- `notice` is present only when `updateAvailable` is true.
- It does not depend on `target`: the same value is returned for every target, also when no node is
  reachable. It never changes a report's `status`.
- Output schema: `serverUpdate` → `$defs/ServerUpdate` with `required: [currentVersion,
  latestVersion, updateAvailable, releaseUrl, checkedAt]`, `additionalProperties: false`.
- Tool description gains: "`serverUpdate` tells whether a newer release of this MCP server exists."

## Related MCP surface (FR-017, FR-018)

- **Tool results**: the first successful result after a newer release became known gets one
  extra `{"type": "text", "text": "Note: inubit-mcp-server 0.8.0 is available (installed 0.7.1),
  see https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0. Update manually; see
  get_health.serverUpdate."}` after the JSON text block. `structuredContent` is unchanged.
- **`initialize.instructions`**: with a cached newer release, the existing text ends with
  " A newer inubit-mcp-server 0.8.0 is available (installed 0.7.1):
  https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0; update manually."
