# Configuration delta (feature 009)

The key `versionLine` (group and node, features 001/002) accepts one more kind of value: a concrete
INUBIT version. Existing profiles load without change. Research D-2.

```yaml
groups:
  - name: dev
    versionLine: AUTO            # default: detected from the node on first use
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
      - name: node2
        baseUrl: https://inubit-dev-2.example.test:8443
        versionLine: "8.1.17"    # concrete pin (quote it: YAML reads 8.1 as a number)

  - name: next
    versionLine: V9_X            # line pin: reads run unverified, writes are refused
    nodes:
      - name: node1
        baseUrl: https://inubit-next-1.example.test:8443
```

| Key | Level | Default | Values and validation |
|---|---|---|---|
| `versionLine` | group, node (node wins) | `AUTO` | `AUTO` · `V8_1` · `V9_X` · a concrete version `"<major>.<minor>.<patch>[suffix]"` (e.g. `"8.1.17"`, `"9.0.7-HF1"`; the suffix is kept and ignored for matching). A number (`8.1`), a version without patch or any other text → configuration error: "Invalid versionLine at <path>: expected AUTO, V8_1, V9_X or a full version such as \"8.1.17\"" |

## Meaning per value

| Value | Detection | Capabilities |
|---|---|---|
| `AUTO` | on the first tool call on the node and in `get_health`; again before a write if missing, stale or older than `inventory.cacheTtl` | from the detected version; while unknown: reads unverified, writes refused (changed: today writes run on 8.1) |
| `V8_1` | none (`get_health` still reads the version for display and contradiction checks) | 8.1 (unchanged) |
| `V9_X` | none (as above) | reads unverified, writes refused (changed: today every tool runs on 8.1) |
| `"8.1.17"` | none (as above) | the matrix entry of 8.1; patch used for the client comparison |

A `get_health` observation whose major.minor differs from a pin refuses writes on that node until
a later observation matches or the process restarts with a corrected pin (FR-012).

## `--check-config`

- Never contacts an INUBIT server (unchanged). For each node with a StartCLI installation it runs
  `startcli.sh -v` locally (no credentials, no server URL).
- Node line gains `, inubit: <setting>` and, with a StartCLI installation,
  `, client: <version> (<from>–<to>)`; with a concrete pin also `, client vs pin: match|mismatch`.
  Example:

  ```text
  Node dev/node2: read-only, development: on (confirmation SERVER), repository: read-only, e2e: FORBIDDEN, cli: available, inubit: 8.1.17, client: 8.1.17 (4.0.1–8.1.17), client vs pin: match, username ← …
  ```

- Warning for `V9_X` (replaces "unsupported in this version; the 8.1 adapters are used"):
  "<id>: versionLine V9_X: write tools are refused on this node (INUBIT 9.x is not supported for
  writes yet); read tools run unverified".
- The client probe failing (no JDK, timeout) is a warning, not an error.
