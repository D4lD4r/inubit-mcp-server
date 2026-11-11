# Configuration Delta: Read Queue Manager Entries

Delta to [docs/setup.md](../../../docs/setup.md). One group setting is added:
`unsupported.queueEntryRead`. A profile without it loads, validates and behaves exactly as in
0.8.0, byte-for-byte including the startup summary and `--check-config` (SC-002).

## Setting (FR-003)

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `unsupported.queueEntryRead` | group | `false` | offers `read_queue_entry` for the nodes of this group, which reads Queue Manager entries through an **unsupported, undocumented** INUBIT interface (see the text below); independent of `write.*`, `archives.*` and `production` |

```yaml
groups:
  - name: test
    unsupported:
      queueEntryRead: true   # unsupported INUBIT functionality, diagnosis only
    nodes:
      - name: node1
        baseUrl: https://inubit-test-01.example.test:8443
```

The tool also needs an INUBIT client installation for the node's version, configured as for
StartCLI (`cli.home`, `defaults.cliClients."8.1"` / `"9.0"`, `defaults.cliHome`; feature 017),
and the node's usual credentials and TLS settings. Nothing else is configured; there is no
profile setting for the text limit (the caller's `maxChars`).

## Documentation text (FR-004)

`docs/setup.md` gets the subsection `### Unsupported functions` after `### Workbench archives`,
with the table and example above and this text (verbatim; the bracketed links are relative to
`docs/`):

> **`unsupported.queueEntryRead` uses unsupported INUBIT functionality.** INUBIT documents no
> interface that returns the content of a Queue Manager entry. When this setting is `true`,
> `read_queue_entry` ([reference](tools.md#read_queue_entry)) reads the data stream and the
> workflow variables of an ERROR entry through the internal, undocumented interface that the
> Workbench's Queue Manager uses for "View file" (INUBIT 8.1: the Workbench's SOAP servlet; INUBIT
> 9.0: the Workbench's internal REST API), driven by the local INUBIT client installation.
>
> - **No vendor support, no stability guarantee.** The vendor gives no support and no stability
>   guarantee for this interface. It may stop working, or behave differently, after **any**
>   INUBIT update, including patch releases. The server detects unexpected answers and reports
>   them as `UNEXPECTED_RESPONSE`, but it cannot rule out that a changed interface behaves
>   differently without being noticed.
> - **Diagnosis only.** Use the results only to find out why a process failed. Do not rely on
>   them for anything else (no automation, no evidence, no data recovery).
> - **Business data leaves the server.** The tool returns the business data of the entry (the
>   data stream and the variable values) to the MCP client and therefore into the model context
>   and the chat transcript. Credentials are masked only as described in the tool reference:
>   variables and XML/JSON/`name=value`/header fields whose name contains `password`, `passwd`,
>   `pwd`, `secret`, `token`, `apikey`, `api_key`, `credential`, `authorization`, `private`,
>   `keystore` or `cookie`, `Authorization` headers and `Basic`/`Bearer` credentials, and the
>   credentials of this profile. **Credentials in unusual places** (for example under a neutral
>   element name such as INUBIT's `<Property name="password">`, or inside binary or encoded
>   content) **may still appear in the result.**
> - **Read-only.** The tool uses only the read request of "View file"; it never restarts, kills,
>   deletes, modifies or exports an entry. A static review and a live check against INUBIT 8.1
>   found no change to the entry, which is still no vendor guarantee.
> - Reads are not written to the audit log; the server log names only the server, process ID,
>   queue file name, sizes and the outcome, never content.
> - Enable it per group and deliberately; on a group with `production: true` the server starts
>   with a warning.

The tool reference (`docs/tools.md`, section `read_queue_entry`) starts with the same text in
short form (the tool description of contracts/mcp-tools-delta.md) and links to this subsection.
`QueueEntryDocsTest` asserts the bold sentences above in `docs/setup.md` and the short form in
`docs/tools.md` and in the tool description.

## Validation (startup and `--check-config`)

| Input | Result | Text (value never echoed) |
|---|---|---|
| `unsupported.queueEntryRead: true` / `false` / empty / `null` on a group | loads (`null`/empty = `false`) | — |
| `"true"`, `1`, `0`, `yes`, a list or a mapping as the value | startup error | `Invalid value at groups[0].unsupported.queueEntryRead in <source>: expected true or false` |
| any other key under `unsupported` on a group | startup error | `Unknown key 'archiveRead' at groups[0].unsupported in <source>` |
| `unsupported` on a node | startup error | `Unknown key 'unsupported' at groups[0].nodes[0] in <source>` |
| `unsupported` in `defaults` | startup error | `Unknown key 'unsupported' at defaults in <source>` |
| `unsupported.queueEntryRead: true` on a group with `production: true` | startup **warning** (log `Configuration: …`; `--check-config` "Warnings:") | `unsupported.queueEntryRead is enabled on production group prod: Queue Manager entries are read through an undocumented INUBIT interface without vendor support` (the word for the group follows the profile's terminology) |

Paths are rendered by the existing `ConfigLoader.pathOf` (`config/ConfigLoader.java:785-798`); the
examples show its form. An opt-in on a group none of whose nodes can use it (pinned to 6.1, no
client home) is not a separate warning: the tool is simply not announced (FR-002); `list_nodes`
lists `queue_entry_read` only for nodes of opted-in groups with a client home (as
`workspace_export`), under `notUsable` with the version reason where the version refuses it.

## Summary (`--check-config`, FR-017)

Only if at least one group of the profile has `unsupported.queueEntryRead: true`, every group
line ends with the state, after the archive state (owner decision 2026-11-10). Without any opt-in
the summary is byte-for-byte that of 0.8.0 and no existing golden text changes:

```text
Group test: archives: allowed, queueEntryRead: enabled (unsupported)
Group prod (production): archives: refused (no archives.productionOptIn), queueEntryRead: off
```

The production warning appears under "Warnings:" as above. `docs/setup.md` (section 6) gets one
additional example with an opt-in; the existing examples, `ConfigSummaryTest` and `MainTest`
golden texts stay unchanged.
