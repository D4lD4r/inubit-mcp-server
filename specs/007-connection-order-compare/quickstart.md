# Quickstart & Validation: Order-Insensitive Comparison of Workflow Connections

Feature: [spec.md](spec.md) · Contract: [contracts/ports.md](contracts/ports.md)

## 0. Offline

```bash
mvn -q verify
```

Expected: green, including the new tests for the connection-order rule, import, deployment, export
and the legacy-fingerprint rule, and `NoCustomerIdentifiersTest`.

## 1. Build and install 0.5.1 locally

```bash
mvn -q clean verify
cp target/inubit-mcp-server-0.5.1.jar ~/.local/lib/
```

Then, with backups of both files first, set the JAR name to `inubit-mcp-server-0.5.1.jar` in the
start script `~/.local/bin/inubit-mcp-<profile>` and in `x-cert-check.serverJar` of
`~/.config/inubit-mcp/<profile>.yaml`. Validate:

```bash
inubit-cert-check --profile <profile> --check
```

Expected: exit 0; the server's `--version` reports 0.5.1. Start a new Claude session so that the
MCP server runs the new JAR.

## 2. Live: the incident's diagram group (SC-005)

In the new session, preview and confirm `import_artifacts` for the affected diagram group on the
development server with an unchanged workspace (or with the intended change).

Expected: `EXECUTED`; no `VERIFY_MISMATCH`, no rollback, no new `.reports/verify-*.diff`.

## 3. Live: export stays quiet (SC-006)

Run `export_artifacts` for the same diagram group twice.

Expected: no `MODIFIED` entry and no new workspace commit for workflows whose connections only
changed order.

## 4. Live, later: deployment to the next stage (SC-002)

When the group is released to the next stage, `deploy_release` previews the affected workflows as
`UNCHANGED` (or `CHANGED` only for real changes) and executes without `VERIFY_MISMATCH`.
