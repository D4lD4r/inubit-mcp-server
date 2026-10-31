# MCP tools delta (feature 011)

No new tool, no new parameter, no new result field (FR-016). Research R-7, R-8, R-12.

## Errors (changed texts and next steps, no new error code)

For a node whose effective source is the credential store, before anything is sent to INUBIT:

| Failure kind | `code` | message contains | nextStep |
|---|---|---|---|
| entry not found / empty (username or password) | `AUTH_FAILED` | node, store, both account names, "not found" | create the entry (service `inubit-mcp`, account …) with the system command |
| access denied (dialog denied, keyring prompt dismissed) | `AUTH_FAILED` | node, store, account, "access denied" | allow access when asked; retried after 30 s |
| invalid value | `AUTH_FAILED` | node, account, which rule (incl. "cannot be decoded") | correct the entry (value never shown) |
| store not available | `AUTH_FAILED` | node, "no usable credential store" and why (no Secret Service, `secret-tool` missing, no logon session, no GUI session) | install/start the store or use `awsParameterStore` |
| trust store password entry on a node with CLI | `AUTH_FAILED` | node, account | delete the entry; use a password-less trust store |
| only one e2e entry (`run_e2e_test`) | `AUTH_FAILED` | node, missing account | create the missing entry or delete the other |
| e2e entries with `http://` `e2e.soap.baseUrl` (`run_e2e_test`) | `AUTH_FAILED` | node, "http://" | use `https://` |
| timeout (60 s, also an unanswered dialog) | `UNREACHABLE` | node, store, "timeout" | answer the access dialog; retried after 30 s |

`AUTH_FAILED` after INUBIT rejected the credentials: next step "Correct the credential store entries
<accounts> in the <store>; no restart is needed" (Parameter Store unchanged). The text "restart the
MCP client from a shell with the new values" no longer appears.

## `get_health`

A node whose read is still running is reported as `TIMEOUT` "<node>: credentials not yet available
(still reading from the <store>); call get_health again shortly" (Parameter Store text unchanged).

## Fan-out deadlines

The read allowance (store timeout + 1 s) is added to a node's branch deadline only while its
credentials are not held, for both sources (FR-008).
