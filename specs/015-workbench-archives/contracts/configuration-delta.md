# Configuration delta (feature 015)

Research R-15. One new group-level key; nothing else changes.

## `archives.productionOptIn`

| Key | Level | Type | Default | Meaning |
|---|---|---|---|---|
| `archives.productionOptIn` | group | boolean | `false` | allows `export_archives` (archives with clear-text secrets) on a group with `production: true`; independent of `write.enabled` and `write.productionOptIn` |

```yaml
groups:
  - name: prod
    production: true
    archives:
      productionOptIn: true   # export_archives allowed on this production group
    nodes:
      - name: inubit01
        baseUrl: https://inubit-prod-01.example.test:8443
```

Validation:

- unknown keys under `archives` and `archives` on a node or in `defaults` → startup error (unknown
  key, as every other key; Jackson `FAIL_ON_UNKNOWN_PROPERTIES`);
- a non-boolean value → startup error;
- `archives.productionOptIn: true` on a group without `production: true` → startup warning
  "archives.productionOptIn has no effect on group <name> (not production)".

## `--check-config`

Each group header line gets the archive state:

```text
Group dev: archives: allowed
Group prod (production): archives: refused (no archives.productionOptIn)
Group prod (production): archives: allowed (archives.productionOptIn)
```

(The exact header wording follows `ConfigSummary`; the terminology placeholder `{Group}` applies.)

## Audit log documentation (`docs/setup.md` → Audit log)

- `capability` gains `export_archives`; outcomes gain `PARTIALLY_COMPLETED` (an `export_archives`
  call whose writing failed: some or no archives written, one failed, the rest not attempted).
