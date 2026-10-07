# Configuration delta (feature 004)

Additions to the configuration format of features 002/003. All keys are optional; without them the
server behaves as before (no development tool is offered).

```yaml
profile:
  name: acme

defaults:
  e2eTests: FORBIDDEN       # default

groups:
  - name: dev
    development:
      enabled: true         # default false
      confirmation: SERVER  # SERVER (default) | CLIENT
    e2eTests: FREE          # FREE | CONFIRM | FORBIDDEN
    e2e:
      soap:
        baseUrl: https://inubit-dev.example.test:8443
    nodes:
      - name: node1
        baseUrl: https://inubit-dev.example.test:8443
```

| Key | Level | Default | Validation (startup error unless noted) |
|---|---|---|---|
| `development.enabled` | defaults, group, node | `false` | `true` not allowed where `production: true` |
| `development.confirmation` | defaults, group, node | `SERVER` | `SERVER` or `CLIENT` |
| `e2eTests` | defaults, group, node | `FORBIDDEN` | `FREE`/`CONFIRM` not allowed where `production: true`; need `e2e.soap.baseUrl` |
| `e2e.soap.baseUrl` | group, node | — | absolute URL; `https`, or `http` with a startup **warning**; no query or fragment |

`--check-config` per node adds: `development: on (confirmation SERVER) | off`, `e2e: FREE (https://…) |
CONFIRM (…) | FORBIDDEN`.

There is no owner setting (research D-26, superseding D-21): every import names the owner with
`--importUser`, for users and user groups alike; a top-level `owners:` key is an unknown key
(startup error).

Backups are stored in `~/.inubit-mcp/<profile>/backups` (not configurable), kept 30 days; the newest per
node, owner and scope is always kept.
