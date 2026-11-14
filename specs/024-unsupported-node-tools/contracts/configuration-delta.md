# Configuration Delta: Unsupported Node Tools

Delta to [docs/setup.md](../../../docs/setup.md). Four group settings are added under
`unsupported`. A profile without them loads, validates and starts exactly as with the 021 build:
same tool list, same startup summary and `--check-config` output (SC-005). There is no setting for
the lease store; it lives in the profile's data directory (`~/.inubit-mcp/<profile>/log-levels/`)
and is created by the first `set_temporary_log_level`.

## Settings (FR-002, FR-003)

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `unsupported.nodeStatusRead` | group | `false` | offers `list_cluster_nodes` for the nodes of this group (unsupported interface) |
| `unsupported.ioLogRead` | group | `false` | offers `read_io_log` (unsupported interface; messages can contain business and personal data) |
| `unsupported.nodeConfigRead` | group | `false` | offers `get_node_config` (unsupported interface; fixed list of values) |
| `unsupported.logLevelWrite` | group | `false` | offers `set_temporary_log_level` and `reset_temporary_log_level` for nodes of this group **with write access** (`write.enabled`, and `write.productionOptIn` on production); the answering-node lookup is unsupported |

All are independent of each other and of `unsupported.queueEntryRead` / `serverLogRead`.

```yaml
groups:
  - name: test
    write:
      enabled: true          # needed only for the log-level tools
    unsupported:
      nodeStatusRead: true   # unsupported INUBIT functionality, diagnosis only
      ioLogRead: true
      nodeConfigRead: true
      logLevelWrite: true
    nodes:
      - name: node1
        baseUrl: https://inubit-test-01.example.test:8443
```

Every tool needs an INUBIT client installation for the node's version, configured as for StartCLI
(`cli.home`, `defaults.cliClients."8.1"` / `"9.0"`, `defaults.cliHome`; feature 017), and the
node's usual credentials and TLS settings. Timeouts: `cliTimeout` (built-in 30 s) for the node list,
the configuration values, the I/O listing and each log-level StartCLI run; `cliExportTimeout`
(built-in 120 s) for reading one I/O message.

## Documentation text (FR-003)

`docs/setup.md`, subsection `### Unsupported functions`, gets the rows and example above and this
text after the `serverLogRead` text (verbatim; links relative to `docs/`):

> **`unsupported.nodeStatusRead`, `unsupported.ioLogRead` and `unsupported.nodeConfigRead` use
> unsupported INUBIT functionality.** INUBIT documents no interface for the cluster's node list,
> the input/output logging of modules or node configuration values. When a setting is `true`, the
> tool ([`list_cluster_nodes`](tools.md#list_cluster_nodes), [`read_io_log`](tools.md#read_io_log),
> [`get_node_config`](tools.md#get_node_config)) reads them through the internal, undocumented
> interfaces the Workbench uses, driven by the local INUBIT client installation.
>
> - **No vendor support, no stability guarantee.** The vendor gives no support and no stability
>   guarantee for these interfaces. They may stop working, or behave differently, after **any**
>   INUBIT update, including patch releases.
> - **Diagnosis only.**
> - **INUBIT checks no right beyond the login** for `list_cluster_nodes` and `get_node_config`:
>   every INUBIT user the profile logs in with can read them. `read_io_log` is the exception:
>   INUBIT requires a right of the configured user for the listing and refuses it without that
>   right (`FORBIDDEN`).
> - **`read_io_log`: business and personal data.** Logged messages contain what the modules
>   received and sent. They are masked like Queue Manager entries (known credentials and
>   secret-named values), but masking does not cover business and personal data.
> - **`get_node_config`: fixed list.** Only the 20 listed values can be read; values that hold
>   secrets (database connection, cluster password, initial root password, identity provider) can
>   never be requested.
> - **Cluster nodes.** Each tool reads from the node the configured address reaches and names it;
>   address cluster nodes directly, not through a load balancer.
>
> **`unsupported.logLevelWrite` changes a running INUBIT node.** `set_temporary_log_level` raises
> the level of named INUBIT loggers on the node the address reaches through StartCLI's documented
> `trace --nodelevel`, for a limited time; `reset_temporary_log_level` runs `trace --reset`. Both
> are write tools: they need `write.enabled` (and `write.productionOptIn` on production) and follow
> the node's confirmation mode.
>
> - **The reset restores every temporary log level on that node**, also levels other users set.
>   Only the exactly named logger changes; levels are kept in the node's memory only and can be
>   lost earlier (INUBIT restart, log configuration reload). The persistent trace settings are never
>   changed.
> - **Automatic reset.** Every change is recorded as a lease in
>   `~/.inubit-mcp/<profile>/log-levels/` (owner-only). The MCP server resets the node within a
>   minute after the lease expires, before it shuts down, and at its next start; another running
>   instance of the same profile takes over leases whose instance no longer runs.
> - **Cleanup resets run also when write access is off.** If `write.enabled`,
>   `write.productionOptIn` or `unsupported.logLevelWrite` is removed while a lease is open, the
>   tools are no longer offered, but the MCP server still resets the recorded nodes (a deliberate
>   exception to the read-only default, decided so that a raised level never stays without limit).
>   A lease that cannot be reset is shown in `get_health` and the startup log and retried every
>   minute; `reset_temporary_log_level` with `leaseId` and `forget: true` ends it without contacting
>   INUBIT. A lease of a server you remove from the configuration ends automatically 7 days after
>   its expiry.
> - **Address the node directly.** The MCP server asks which INUBIT node answers before every
>   change and reset and refuses when another node answers than the open lease names; behind a
>   load balancer the lookup and the change can still reach different nodes.
> - Every set, reset and automatic reset is written to the audit log.
>
> Enable each setting per group and deliberately; on a group with `production: true` the server
> starts with a warning.

The tool reference sections start with the tool description of contracts/mcp-tools-delta.md and
link to this subsection. `NodeToolsDocsTest` asserts the bold sentences above in `docs/setup.md`
and the short forms in `docs/tools.md` and in the tool descriptions.

## Validation (startup and `--check-config`)

| Input | Result | Text (value never echoed) |
|---|---|---|
| a key `true` / `false` / empty / `null` on a group | loads (`null`/empty = `false`) | — |
| `"true"`, `1`, `0`, `yes`, a list or a mapping as the value | startup error | `Invalid value at groups[0].unsupported.ioLogRead in <source>: expected true or false` |
| any other key under `unsupported` on a group | startup error (unchanged) | `Unknown key 'gcRun' at groups[0].unsupported in <source>` |
| a key on a node or in `defaults` | startup error (unchanged) | `Unknown key 'unsupported' at groups[0].nodes[0] in <source>` |
| `nodeStatusRead: true` on a `production: true` group | warning | `unsupported.nodeStatusRead is enabled on production {group} prod: the cluster's node list is read through an undocumented INUBIT interface without vendor support` |
| `ioLogRead: true` on production | warning | `unsupported.ioLogRead is enabled on production {group} prod: module input and output messages are read through an undocumented INUBIT interface without vendor support; they can contain business and personal data` |
| `nodeConfigRead: true` on production | warning | `unsupported.nodeConfigRead is enabled on production {group} prod: node configuration values are read through an undocumented INUBIT interface without vendor support` |
| `logLevelWrite: true` on production | warning | `unsupported.logLevelWrite is enabled on production {group} prod: temporary log levels of production nodes can be changed; the answering node is looked up through an undocumented INUBIT interface` |
| `logLevelWrite: true` on a group none of whose nodes has write access | warning | `unsupported.logLevelWrite has no effect on {group} test: write access is off (cleanup resets of recorded leases still run)` |

## Summary (`--check-config`, startup)

Only if at least one group sets a key, every group line ends with its state, in the order
`queueEntryRead`, `serverLogRead`, `nodeStatusRead`, `ioLogRead`, `nodeConfigRead`, `logLevelWrite`
(only the keys some group sets):

```text
Group test: archives: allowed, nodeStatusRead: enabled (unsupported), logLevelWrite: enabled (unsupported)
Group prod (production): archives: refused (no archives.productionOptIn), nodeStatusRead: off, logLevelWrite: off
```

Capability lists: the `cliOnly` part of a 6.1 setting lists a new capability as refused only for
groups with its opt-in (020 rule).

Leases (FR-045), only if the store holds a lease that is not ended; `--check-config` only reads:

```text
Temporary log levels:
  test/node1 (INUBIT node node1): 1 logger(s), until 2026-11-12T08:25:31Z, ACTIVE
  legacy/node2 (INUBIT node node2): 2 logger(s), until 2026-11-10T09:00:00Z, RESET_FAILED (server no longer configured; forgotten automatically on 2026-11-17)
```

An unreadable store is a warning: `The temporary log level leases in <path> cannot be read
(<reason>); log-level changes are refused until the file is fixed or moved`.
