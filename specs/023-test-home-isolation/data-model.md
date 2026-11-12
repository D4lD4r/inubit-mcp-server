# Data Model: home-derived locations

No new data. This lists every location derived from the user home H for profile P, who derives it,
and how it is reached after this change (reference list for FR-009 / SC-005).

| Location | Path for H, P | Derived by | Home source after the change |
|---|---|---|---|
| Import backups | `H/.inubit-mcp/P/backups` | `BackupStore.defaultRoot` in `Wiring` | `Wiring` parameter `userHome` |
| Deployment ledger | `H/.inubit-mcp/P/deployments` | `DeploymentLedger.defaultDirectory` in `Wiring` | `Wiring` parameter `userHome` |
| Deployment packages | `H/.inubit-mcp/P/packages` | `PackageWriter.defaultDirectory` in `Wiring` | `Wiring` parameter `userHome` |
| Forbidden export root | `H/.inubit-mcp` | `TargetFolderRules` in `Wiring` | `Wiring` parameter `userHome` |
| Audit log (default) | `H/.inubit-mcp/P/audit` | `ConfigLoader` | unchanged (loader parameter) |
| Workspace (default) | `H/.inubit-mcp/P/workspace` | `ConfigLoader` | unchanged (loader parameter) |
| Knowledge (default) | `H/.inubit-mcp/P/knowledge` | `ConfigLoader` | unchanged (loader parameter) |
| Update-check cache | `H/.inubit-mcp/update-check.json` | `FileUpdateCache.inHome` in `Launcher.Context.system` | unchanged (context) |
| Credential store home | `H` | `CredentialStores.forOs` in `Launcher.Context.system` | unchanged (context) |

H itself is read from the process exactly once, in `Launcher.Context.system`
(`System.getProperty("user.home")`).

In tests H is a temporary directory: per server from `TestWiring` (removed on close, at the latest
at JVM exit), or a `@TempDir` where a test calls the constructor directly.
