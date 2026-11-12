# Contract: Configuration Delta

**Feature**: 022-full-interface-coverage. Profile file `~/.config/inubit-mcp/<profile>.yaml`;
unknown keys stay errors (`FAIL_ON_UNKNOWN_PROPERTIES`).

## Per group: `administration`

```yaml
groups:
  - id: test
    write:
      enabled: true
    administration:        # all default false
      objects: true        # delete, rename, metadata import, repository delete, IS Connector start
      libraries: false     # libraries, plug-ins, EDI rules, module keys
      settings: false      # threads, privileged share, DB tolerance, configuration, license, backup start options
      users: false         # users, groups, roles, imports, messages
      runtimeData: false   # run-time data export, import, resume, delete, validate, list blocked
```

- **Allowed place**: groups only. On a node the key is an error, as for `archives` and
  `unsupported`.
- **Values**: strict booleans; `"yes"` or `1` is an error, as for `unsupported.*`.
- **`--check-config`**:
  - a warning per area enabled on a production group;
  - an error when an area is enabled but no node of the group has write enabled (except
    `runtimeData`, whose two reads need no write enablement: then a warning).
- **Summary**: one line per group, `administration: objects, libraries` (or `none`).
- **`list_nodes`**: each node gets `administration: [areas]`.

## Per profile: `uploads`

```yaml
uploads:
  folders:                 # default: empty, so uploads are off
    - /Users/me/inubit-uploads
  maxBytes:                # defaults shown; each 1 KiB … 4 GiB
    license: 1MiB
    keystore: 1MiB
    userImport: 10MiB
    ediRules: 50MiB
    metadataImport: 50MiB
    isConnectorInput: 50MiB
    library: 200MiB
```

- **Folders**: absolute, existing, not a symbolic link, not inside `~/.inubit-mcp`; checked at
  start.
- **Sizes**: written as `<n>KiB|MiB|GiB` or as plain bytes.
- **An upload while `folders` is empty**: `NOT_CONFIGURED`, with the next step naming
  `uploads.folders`.

## Named secret entries (no new key)

Secret entries live in the node's **credential store**, in their own namespace. For the name
`NEW_USER_PW`, the server first tries the node-specific entry
`<PREFIX>_<GROUP>_<NODE>_ENTRY_NEW_USER_PW`, then the group entry
`<PREFIX>_<GROUP>_ENTRY_NEW_USER_PW`, as for the login entries.

A name is rejected in two cases:

- it equals or ends in `_USERNAME`, `_PASSWORD`, `_TRUSTSTORE_PASSWORD`, `_E2E_USERNAME` or
  `_E2E_PASSWORD`;
- its resolved name equals a login credential name of any configured node.

Nodes whose secrets come from the **AWS Parameter Store** have no secret entries in this feature.
An action that needs one is refused with `NOT_CONFIGURED`, which names the credential store as the
requirement (research D-10).

`docs/setup.md` documents how to create an entry and that tool calls name the entry, never the
value.
