# Configuration delta (feature 008)

This adds one key to the configuration format of features 002 to 005. Without the key the server
behaves exactly as before: repository files are never written by the development tools. Research
D-1 and D-2.

```yaml
defaults:
  development:
    repository: WRITE          # inherited: takes effect only on development stages (D-2)

groups:
  - name: dev
    development:
      enabled: true            # development stage
      # repository: WRITE      # or here, or on the node; the node wins
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443

  - name: int                  # not a development stage: the inherited WRITE has no effect here
    deploy: { from: dev }
    nodes:
      - name: node1
        baseUrl: https://inubit-int-1.example.test:8443
```

| Key | Level | Default | Validation |
|---|---|---|---|
| `development.repository` | defaults, group, node | `READ_ONLY` | `READ_ONLY` or `WRITE` (otherwise a loader error listing both). **Effective** `WRITE` = resolved `WRITE` and `development.enabled` and not `production` |
| | node | | **startup error**: `WRITE` set on a node that is not a development stage |
| | group | | **startup error**: `WRITE` set on a `production: true` group or on any of its nodes |
| | group | | **warning**: `WRITE` set on a non-production group without a development-enabled node ("has no effect") |
| | defaults | | never an error: nodes that are not development stages resolve to read-only |

The error messages use the profile's terminology (`{group}`, `{node}`), as `MessageTerminologyTest`
requires.

## `--check-config`

The development part of a node line gains the switch:

```text
Group dev:
  Node dev/node1: read-only, development: on (confirmation SERVER), repository: write, e2e: FORBIDDEN, cli: available, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
```

- `repository: write` or `repository: read-only` is printed only for development nodes (`development: on`).
- The first `read-only` in the line is the existing restart/kill write flag and stays unchanged.

## `list_nodes`

Each node item gains the required boolean `repositoryWrite` (the effective value), placed after
`confirmationMode`. Example: `"developmentEnabled": true, "confirmationMode": "SERVER",
"repositoryWrite": true`.
