# Helper Protocol Delta: Unsupported Node Tools

Delta to the `ClientHelper` protocol of feature 020 (research D-4) and 021 (research D-4). Wire ids
unchanged: request header `qe=1`, frame prefix `#QE1:<nonce> `, percent-encoded tokens, empty token
`-`. The `queueEntry` (020) and `serverLog` (021) operations are unchanged; their requests are
encoded byte-identically (the encoder writes `op` for every operation except `queueEntry`).

## Request (stdin, UTF-8)

```text
qe=1
op=<ownNode | clusterNodes | nodeConfig | ioLog>
nonce=<32 hex>
variant=SOAP_81 | REST_90
url=<cli url of the selection>
trustStore=<path or empty>
trustStorePassword=<one-time password or empty>
hostnameVerification=true | false
<operation keys>
user=<INUBIT user>
password=<INUBIT password>
end
```

| `op` | Keys | Rules (else exit 2, nothing sent) |
|---|---|---|
| `ownNode` | — | — |
| `clusterNodes` | `details` | empty or `^[A-Za-z0-9_.:-]{1,128}$` |
| `nodeConfig` | `names` | comma-separated, 1–20 distinct names from the helper's constant list: `OperationMode`, `ClusterName`, `ZoneName`, `NodeName`, `LocalMaintenanceMode`, `ActivateClusterManager`, `ClusterDebug`, `ContainerShutdownOnNodeFailure`, `RegisterMBeans`, `RegisterInternalMBeans`, `RuntimeDataCacheXMXPercentage`, `socketTimeoutInMillisecs`, `indexEnabled`, `iterationCount`, `DataSourceLocation`, `LogsDBDataSourceLocation`, `RepositoryDataSourceLocation`, `ClusterBindAddress`, `noOfRetries`, `retryInterval` |
| `ioLog` | `processId`, `workflow`, `ref`, `listOut`, `messageOut` | `processId` `^[0-9A-Za-z_-]{1,64}$`; `workflow` 1–200 chars, no control character, no `@@@`, no `###`; `ref` empty or `^[A-Za-z0-9_-]{22}$`; `listOut` non-empty; `messageOut` non-empty iff `ref` non-empty |

A key of another operation, a repeated or missing key, an unknown `op` or a repeated `op` is a
malformed request (exit 2).

## Vendor calls per operation (FR-007)

| `op` | Calls, in order |
|---|---|
| all | client creation (020 variant), `loginUserCli(user, password)`; `new ClusterClient(client, user, CurrentUser.getClientUID(), CurrentUser.getUsedAdditionalUserGroup())`; `getNode()` → `node ok <name>` |
| `ownNode` | — |
| `clusterNodes` | `getNodes()`; with `details` naming a listed member: `getNode(NodeName.nodeName(details))` → `getNodeInformation()` → `getNodeInformationMap()` → `getPropertyNames()` / `get(name).getStringValue()` (nested map properties flattened as `<outer>.<inner>`) |
| `nodeConfig` | per name: `getNodeConfigurationProperty(<NodeName of getNode()>, name)` |
| `ioLog` | `client.getObject("<processId>@@@<workflow>", user, "inputOutputMessageDetails")`; with `ref` and a matching entry: `new MultipartDownloadThread(client, user, "<workflow>###<entry>", "InputOutputLogging", null, CurrentUser.getUsedAdditionalUserGroup())` with `setOutputFile(messageOut)`, `start`, `join`, `exceptionOccured`/`getException` |
| all | `logoutUser(user)` on 8.1 (`finally`) |

Never called: `setNodeConfigurationProperty`, `changeTemporaryLogLevel`, `resetTemporaryLogLevel`,
`startGarbageCollector`, any cache method, `clusterDownload*`, `downloadData`, `uploadData` (the
fake-vendor tests fail on any call outside the table).

## Frames (stdout)

| Frame | Operation | Meaning |
|---|---|---|
| `start <variant>`, `login ok`, `login error <chain> <message>`, `api missing <what>`, `end` | all | as 020 |
| `node ok <name>` / `node error <chain> <message>` | all new | the answering node; after `node error` → `end`, no further call |
| `member <name> <status> <version> <zone> <nodeId> <uptimeMs>` | `clusterNodes` | one per element of `getNodes()`, order kept; `-` for `null` |
| `members ok <count>` / `members error <chain> <message>` | `clusterNodes` | — |
| `detail <key> <value> [cut]` | `clusterNodes` | ≤ 500; key ≤ 200 chars, value ≤ 4,000 chars (longer: cut, token `cut`) |
| `details ok <count>` / `details error <chain> <message>` / `details unknown` | `clusterNodes` | `unknown` = `details` is not a listed member (no request sent) |
| `config <name> ok <type> <editable> <deprecated> <displayName> <category> <description> <value>` | `nodeConfig` | `type` = `getType().getSimpleName()`; flags `true`/`false`; texts ≤ 4,000 chars |
| `config <name> absent` / `config <name> error <chain> <message>` | `nodeConfig` | `absent` = `null` answer |
| `io list ok <count>` / `io list error <chain> <message>` | `ioLog` | the entries are in `listOut`, one per line, UTF-8, server order |
| `io match <entry>` / `io match none` | `ioLog` with `ref` | the entry whose reference equals `ref` |
| `part message ok <bytes>` / `part message error <chain> <message>` | `ioLog` after `io match <entry>` | the message is in `messageOut` |

Order: `start`, `login …`, `node …`, operation frames, `end`. Messages in error frames are cut at
1,000 characters and have the configured password replaced (as 020). The stdout cap of 1 MiB (020)
applies.

## Message reference

`ref(entry)` = Base64url without padding of the first 16 bytes of SHA-256 over the UTF-8 bytes of
the full listed entry (`<processId>@@@<moduleName>(<moduleId>)@@@Input.<n>`), 22 characters.
Implemented in `IoMessageRef.of` (server) and in the helper (JDK `MessageDigest`,
`Base64.getUrlEncoder().withoutPadding()`); `fixtures/node-tools/io-message-ref.vectors` (entry →
ref lines, fictitious entries) is asserted by `IoMessageRefTest` and `ClientHelperTest`.

## API check (`api missing <what>`, exit 4)

Before `start`, per operation, only the members it calls (table above), plus the 020 client
creation members of the variant. A missing member → `CLI_UNAVAILABLE` naming the class and member.

## Exit codes

0 after `end`; 1 anything else; 2 malformed request; 3 login failed; 4 vendor API missing (020).
The adapter treats exit code ≠ frames (e.g. exit 0 without `end`) as `UNEXPECTED_RESPONSE`.
