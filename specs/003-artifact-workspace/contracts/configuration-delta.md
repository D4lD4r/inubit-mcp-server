# Configuration delta (feature 003)

Adds one optional top-level key to the configuration format v2 of feature 002
([../../002-customer-agnostic-config/contracts/configuration.md](../../002-customer-agnostic-config/contracts/configuration.md)).

```yaml
profile:
  name: acme
workspace: ~/work/acme-inubit    # optional; default ~/.inubit-mcp/<profile.name>/workspace
```

| Key | Type | Default | Validation (startup error, `--check-config` exit 1) |
|---|---|---|---|
| `workspace` | path | `~/.inubit-mcp/<profile.name>/workspace` | `~` expanded; absolute; created `rwx------` (parents created by the server `rwx------`) if missing; readable and writable; not equal to, inside, or containing the workspace of another profile of the default configuration directory |

`--check-config` adds the line `Workspace: <path> (ok | created | <error>)`.

No other key changes. Groups need no new setting to be exported from (clarification 3); the export
uses each node's existing `cliHome`, credentials and `cliExportTimeout`.
