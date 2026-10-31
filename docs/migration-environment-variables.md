# Moving from environment variables to the credential store (0.7.0)

Since 0.7.0 the server reads INUBIT credentials only from the **credential store** of the system
it runs on (macOS Keychain, Windows Credential Manager, Linux Secret Service) or from **AWS
Systems Manager Parameter Store**. Environment variables are no longer read
([setup.md, section 4](setup.md#4-credentials-sources)). This guide moves an existing
installation.

## What changes

| Before (0.6.x) | Since 0.7.0 |
|---|---|
| `INUBIT_<PROFILE>_<GROUP>[_<NODE>]_USERNAME` / `_PASSWORD` (and `_TRUSTSTORE_PASSWORD`, `_E2E_USERNAME`, `_E2E_PASSWORD`) exported in the shell or by a start script | credential store entries with the **same names** as account, service `inubit-mcp` |
| read once at startup; a missing variable stopped the server | read on the node's first use; a missing entry fails that call with `AUTH_FAILED` and is reported by `--check-config` |
| a changed password needed a restart of the MCP client from a new shell (or `/mcp` → reconnect with the start script) | no restart: the server reads the entry again after INUBIT rejects the old password |
| `credentials.envPrefix` | `credentials.prefix` (`envPrefix` still works, with a warning) |
| `credentials.source: { type: environment }` | `type: credentialStore` (the default; `environment` is refused at startup) |
| only one of `_E2E_USERNAME` / `_E2E_PASSWORD` set: no basic authentication | only one of the two entries: `run_e2e_test` fails and names the missing entry |
| a `<PREFIX>_*_PASSWORD` variable matching no node: "typo?" warning | variables with the names of this profile still set: one warning "Environment variables are no longer read for INUBIT credentials (since 0.7.0): …", names only |

Parameter Store nodes (feature 010) are not affected.

## From the macOS start script

If you followed the Keychain how-to of 0.6.x, your entries already have the right names (service
`inubit-mcp`, account = variable name). Only the registration changes:

1. Register the JAR directly instead of the script, with the absolute path of `java` (a client
   started from the Dock does not see your shell's `PATH`):

   ```bash
   claude mcp remove --scope user inubit-acme
   claude mcp add --scope user inubit-acme -- /opt/homebrew/opt/openjdk/bin/java \
     -jar "$HOME/.local/lib/inubit-mcp-server-0.7.0.jar" --profile acme
   ```

2. Run `java -jar … --profile acme --check-config`: every node line shows
   `secrets: credentialStore (macOS Keychain)` and `ok` after each entry.
3. Delete the start script. The server reads the entries with `/usr/bin/security`, as the script
   did, so an earlier "Always Allow" still applies and normally no dialog appears; otherwise
   choose "Always Allow" once per entry.

If the script did more (for example the certificate check of setup.md section 5), keep that as a
separate step; it is no longer needed to start the server.

## From exported shell variables

For every variable in your shell profile (e.g. `~/.zshrc`), create an entry with the variable's
name as account, then delete the `export` line. The commands ask for the value, so it does not end
up on a command line or in the shell history:

```bash
# macOS
security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_USERNAME -w
security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_PASSWORD -w
```

```bat
:: Windows
cmdkey /generic:inubit-mcp:INUBIT_ACME_DEV_USERNAME /user:INUBIT_ACME_DEV_USERNAME /pass
cmdkey /generic:inubit-mcp:INUBIT_ACME_DEV_PASSWORD /user:INUBIT_ACME_DEV_PASSWORD /pass
```

```bash
# Linux (needs secret-tool and a Secret Service, see setup.md section 4)
secret-tool store --label='inubit-mcp INUBIT_ACME_DEV_USERNAME' service inubit-mcp account INUBIT_ACME_DEV_USERNAME
secret-tool store --label='inubit-mcp INUBIT_ACME_DEV_PASSWORD' service inubit-mcp account INUBIT_ACME_DEV_PASSWORD
```

Then run `--check-config` (it warns about variables that are still set) and start the client as
usual; it no longer needs to be started from a particular shell.

Where no credential store is available (a container, CI, a headless Linux server), move the
group to Parameter Store instead ([setup.md](setup.md#aws-systems-manager-parameter-store)).

## Profile changes

- `credentials.source: { type: environment }` (with or without `prefix`): replace by
  `type: credentialStore` with the same `prefix`, or remove it (the credential store is the
  default).
- `credentials.envPrefix: X`: rename to `credentials.prefix: X`.
- A group that used `source: { type: environment, prefix: OLD }`: `source: { type:
  credentialStore, prefix: OLD }` keeps its entry names.

## End-to-end credentials

`<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME` / `_E2E_PASSWORD` become entries as well. Create both
or neither: with only one, `run_e2e_test` now fails and names the missing entry.
