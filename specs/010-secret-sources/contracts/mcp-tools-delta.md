# MCP tools delta (feature 010)

No new tool, no new parameter, no new result field. Secrets never appear in any tool input or
output (unchanged, Constitution II). Research R-7 to R-11.

## Errors (changed texts, no new error code)

A tool call on a node whose effective source is AWS Parameter Store may fail before anything is
sent to INUBIT because its secrets cannot be fetched:

| Failure kind | `code` | message contains | nextStep |
|---|---|---|---|
| parameter not found / empty | `AUTH_FAILED` | node, region (profile), parameter name, "not found" | create the parameter or correct `credentials.parameters.<kind>` |
| access denied | `AUTH_FAILED` | node, region (profile), parameter name, "access denied" | grant `ssm:GetParameters` on the parameter and `kms:Decrypt` on its key |
| invalid value (`StringList`, unsafe username, password shorter than 4) | `AUTH_FAILED` | node, parameter name, which rule | correct the value (never shown) |
| unknown AWS profile / no AWS credentials | `AUTH_FAILED` | node, the AWS profile or "no AWS credentials" | configure or log in to the AWS profile |
| store unreachable, throttled, 10 s timeout | `UNREACHABLE` | node, region, "Parameter Store unreachable" | check network access to `ssm.<region>.amazonaws.com`; retried after 30 s |

Within 30 s after a failed fetch the same error is returned at once (no new fetch). The raw AWS
message is never part of the error (only the scrubbed WARN log).

`AUTH_FAILED` after INUBIT rejected the credentials of an AWS node has the next step "Correct the
parameter value(s) <names> in Parameter Store; no restart is needed" instead of the
environment-variable text.

## `get_health`

- On an AWS node whose secrets are not yet fetched, `get_health` waits for them at most for what
  is left of its health budget. If they are not available in time, that node's report is a failed
  report with `TIMEOUT`: "credentials not yet available (still fetching from AWS Parameter Store);
  call get_health again shortly". No call is sent to that node in this report (its TLS client is
  built once the secrets are there). The fetch continues and the next call uses it.
- A failed fetch gives a failed report with the classified error (table above).
- TLS errors caused by the trust store password name its source (the Parameter Store name for AWS
  nodes) instead of the environment variables.

## Fan-out tools

`find_processes`, `query_logs`, `list_inventory`, `get_inventory_item` (and every tool addressing
several nodes): each node's branch fetches that node's secrets on first use; the per-node deadline
includes the fetch allowance (10 s + grace) for AWS nodes, so a slow store gives that node's error
or partial result, never a fan-out `TIMEOUT`.

## `list_nodes`

Unchanged; it never fetches secrets. (Whether a node's secrets come from environment variables or
Parameter Store is shown by `--check-config`, not by any tool.)

## `run_e2e_test`

The end-to-end credentials of an AWS node are fetched when the first end-to-end test runs on that
node; failures as in the table, naming the `e2eUsername`/`e2ePassword` parameters. A rejected
end-to-end login never triggers a re-read.
</content>
</invoke>
