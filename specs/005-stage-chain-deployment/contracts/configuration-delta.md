# Configuration delta (feature 005)

Additions to the configuration format of features 002–004. All keys are optional; without a `deploy`
record the server behaves as before (`deploy_release` is not offered). Research D-2.

```yaml
profile:
  name: acme

defaults:
  deployConfirmationTtl: PT30M     # default; at most PT2H

groups:
  - name: dev
    development: { enabled: true }
    nodes: [ { name: node1, baseUrl: https://inubit-dev.example.test:8443 } ]

  - name: int
    deploy:
      from: dev                    # required in the record
      exclude:
        - diagramGroup: GRP-SYS
    e2eTests: FREE
    e2e: { soap: { baseUrl: https://inubit-int.example.test:8443 } }
    nodes:
      - { name: node1, baseUrl: https://inubit-int-1.example.test:8443 }
      - { name: node2, baseUrl: https://inubit-int-2.example.test:8443 }

  - name: qa
    deploy: { from: int }
    e2eTests: CONFIRM
    e2e: { soap: { baseUrl: https://inubit-qa.example.test:8443 } }
    nodes: [ { name: node1, baseUrl: https://inubit-qa.example.test:8443 } ]

  - name: prod
    production: true
    deploy:
      from: qa
      mode: PACKAGE_ONLY
    nodes: [ { name: node1, baseUrl: https://inubit-prod.example.test:8443 } ]
```

| Key | Level | Default | Validation (startup error) |
|---|---|---|---|
| `deploy.from` | group | — | an existing group other than this one; the chain is acyclic; every node of this group and of the source has a StartCLI installation |
| `deploy.mode` | group | `EXECUTE` | `EXECUTE` or `PACKAGE_ONLY`; `EXECUTE` on `production: true` needs effective `write.productionOptIn: true` on every node |
| `deploy.exclude[]` | group | `[]` | each entry exactly one of `diagramGroup`, `name` (glob), `repositoryPath` (glob); non-blank |
| `deploy` on a node | — | — | unknown key (startup error) |
| `defaults.deployConfirmationTtl` | defaults | `PT30M` | positive, at most `PT2H` |
| `e2eTests` | group, node | `FORBIDDEN` | now also effective on non-production groups with `deploy` (research D-14); still only `FORBIDDEN` on production |

`--check-config` adds a `Chains:` block (`dev → int → qa → prod (package only)`), the exclusions of
each target, and per node `deploy: from <group> (EXECUTE | PACKAGE_ONLY)`.

Files (not configurable): `~/.inubit-mcp/<profile>/deployments/` (ledger, locks) and
`~/.inubit-mcp/<profile>/packages/` (package-only output), both owner-only.
