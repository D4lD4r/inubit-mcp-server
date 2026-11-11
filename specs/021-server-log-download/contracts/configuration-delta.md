# Configuration Delta: Download Server Log Files

Delta to [docs/setup.md](../../../docs/setup.md). One group setting is added:
`unsupported.serverLogRead`. It is needed only for the web service log; the trace log needs no
setting beyond a StartCLI client. A profile without the new setting loads and validates exactly as
in 0.9.1; its startup summary and `--check-config` differ only where they list capabilities (the
new capability `trace_log_read`, see "Summary").

## Setting (FR-003)

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `unsupported.serverLogRead` | group | `false` | allows `download_server_log` with `log: webservice` for the nodes of this group, which reads `webservice.log` through an **unsupported, undocumented** INUBIT interface (see the text below); independent of `unsupported.queueEntryRead`, `write.*`, `archives.*` and `production` |

```yaml
groups:
  - name: test
    unsupported:
      serverLogRead: true    # unsupported INUBIT functionality, diagnosis only
    nodes:
      - name: node1
        baseUrl: https://inubit-test-01.example.test:8443
```

Both logs need an INUBIT client installation for the node's version, configured as for StartCLI
(`cli.home`, `defaults.cliClients."8.1"` / `"9.0"`, `defaults.cliHome`; feature 017), and the
node's usual credentials and TLS settings. A download is bounded by the node's `cliTimeout`
(built-in 30 s); raise it for large logs. There is no setting for the target folder (the caller's
`targetFolder`) or the excerpt limits (`tailLines`, `maxChars`).

## Documentation text (FR-004)

`docs/setup.md`, subsection `### Unsupported functions`, gets the table row and example above and
this text after the `queueEntryRead` text (verbatim; links relative to `docs/`):

> **`unsupported.serverLogRead` uses unsupported INUBIT functionality.** INUBIT documents no
> interface that returns the web service log. When this setting is `true`,
> `download_server_log` ([reference](tools.md#download_server_log)) with `log: webservice`
> downloads `webservice.log` of the addressed node through the internal, undocumented interface
> that the Workbench's Cluster Manager uses for a node's log files, driven by the local INUBIT
> client installation. The trace log (`log: trace`) does not need this setting: it uses StartCLI's
> supported `trace -d` download.
>
> - **No vendor support, no stability guarantee.** The vendor gives no support and no stability
>   guarantee for this interface. It may stop working, or behave differently, after **any**
>   INUBIT update, including patch releases. The server reports unexpected answers as
>   `UNEXPECTED_RESPONSE`, but it cannot rule out that a changed interface behaves differently
>   without being noticed.
> - **Diagnosis only.** Use the log only to find out what a web service connector sent or
>   received.
> - **Clear-text content.** The web service log contains the messages of the web service
>   connectors **in clear text** (business data, and credentials where a message carries them).
>   The file is written unmasked into the folder you name; only the excerpt in the tool result is
>   masked as described in the tool reference. The same applies to the trace log, which may
>   contain business data and credentials as well.
> - **Read-only on INUBIT.** The tool sends only the login, the lookup of the answering node, the
>   download of the file named `webservice.log` and the logout. The server keeps a temporary copy
>   of the file in its cache store while the download runs, as it does for the Workbench. The file
>   name is fixed in the tool; no input reaches it.
> - **Cluster nodes.** The log comes from the node the configured address reaches; address
>   cluster nodes directly, not through a load balancer. The result names the INUBIT node.
> - Every call is written to the audit log (without log content and without the search text);
>   the server log names only the server, the log, the file, the size and the outcome.
> - Enable it per group and deliberately; on a group with `production: true` the server starts
>   with a warning.

The tool reference (`docs/tools.md`, section `download_server_log`) starts with the tool
description of contracts/mcp-tools-delta.md and links to this subsection. `ServerLogDocsTest`
asserts the bold sentences above in `docs/setup.md` and the short form in `docs/tools.md` and in
the tool description.

## Validation (startup and `--check-config`)

| Input | Result | Text (value never echoed) |
|---|---|---|
| `unsupported.serverLogRead: true` / `false` / empty / `null` on a group | loads (`null`/empty = `false`) | — |
| `"true"`, `1`, `0`, `yes`, a list or a mapping as the value | startup error | `Invalid value at groups[0].unsupported.serverLogRead in <source>: expected true or false` |
| any other key under `unsupported` on a group | startup error (unchanged) | `Unknown key 'traceRead' at groups[0].unsupported in <source>` |
| `unsupported` on a node or in `defaults` | startup error (unchanged) | `Unknown key 'unsupported' at groups[0].nodes[0] in <source>` |
| `unsupported.serverLogRead: true` on a group with `production: true` | startup **warning** (log `Configuration: …`; `--check-config` "Warnings:") | `unsupported.serverLogRead is enabled on production group prod: the web service log is read through an undocumented INUBIT interface without vendor support` (the word for the group follows the profile's terminology) |

An opt-in on a group none of whose nodes can use it (pinned to 6.1, no client home) is not a
separate warning: `list_nodes` lists `webservice_log_read` under `notUsable` with the reason, or
not at all without a client home.

## Summary (`--check-config`, FR-016)

Only if at least one group of the profile has `unsupported.serverLogRead: true`, every group line
ends with its state, after the archive state and after the `queueEntryRead` state if that one is
shown:

```text
Group test: archives: allowed, serverLogRead: enabled (unsupported)
Group prod (production): archives: refused (no archives.productionOptIn), serverLogRead: off
```

Without any `serverLogRead` opt-in the group lines are unchanged. The production warning appears
under "Warnings:" as above.

Capability lists: the `cliOnly` part of a setting pinned to 6.1
(`(INUBIT 6.1, StartCLI only; available: …; refused: …)`) lists "downloading the trace log" as
refused for every 6.1 setting and "downloading the web service log" only for opted-in groups
(feature-020 rule). The golden texts with a 6.1 setting (`ConfigSummaryTest`, `MainTest`) change
by that one additive name; `docs/setup.md` section 6 examples are updated with them.
