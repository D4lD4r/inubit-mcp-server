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
      settings: false      # threads, privileged share, DB tolerance, configuration, license
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

The existing secret source of the node is used, in its own namespace:

| Source | Entry for the name `NEW_USER_PW` |
|---|---|
| credential store | `<PREFIX>_<GROUP>_ENTRY_NEW_USER_PW` (node-specific `<PREFIX>_<GROUP>_<NODE>_ENTRY_…` is tried first, as for the login entries) |
| AWS Parameter Store | `<prefix>/<group>/entries/NEW_USER_PW` (node-specific `<prefix>/<group>/<node>/entries/…` first) |

`docs/setup.md` documents how to create an entry with each source and that tool calls name the
entry, never the value.
