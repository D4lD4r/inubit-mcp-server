# Node-tool fixtures (feature 024, unsupported node tools)

Test data for the node tools: the web service log helper's answers for the cluster nodes, the I/O
log and the node configuration, and the StartCLI answers for the log level (research D-26).
Everything here uses **fictitious names only**.

## Layout

```
node-tools/
  README.md                this file
  io-message-ref.vectors   test vectors of the I/O message reference (version independent)
  v8_1/                    INUBIT 8.1 cases
    cluster-nodes/         cluster member and node detail cases (helper)
    io-log/                I/O log listing and message cases (helper)
    node-config/           node configuration cases (helper)
    log-level/             log level set and reset cases (StartCLI)
  v9_0/                    INUBIT 9.0 cases (same layout as v8_1/)
```

Each case directory holds flat files only; a file belongs to the case named by the part of its
name before the suffix (`<case>.<ext>`).

### Helper cases (`<version>/{cluster-nodes,io-log,node-config}/<case>.*`)

| Suffix | Content |
|---|---|
| `.frames` | the helper's stdout (vendor noise lines and protocol frames) |
| `.exit` | the helper's exit code |
| `.list` | `io-log` only: the I/O listing the helper writes to `listOut`, one entry per line, UTF-8, server order |
| `.message` | `io-log` only: the message the helper downloads to `messageOut` |
| `.origin` | provenance (required) |

The frames carry the fixed nonce `0123456789abcdef0123456789abcdef` of the queue-entry and
server-log fixtures. The request a case answers (e.g. `details=node1` for `cluster-nodes`) is named
in its `.origin`.

### StartCLI cases (`<version>/log-level/<case>.*`)

| Suffix | Content |
|---|---|
| `.stdout` | StartCLI's stdout, replayed by the fake process launcher |
| `.stderr` | StartCLI's stderr |
| `.exit` | StartCLI's exit code |
| `.origin` | provenance (required) |

## Provenance (`.origin`)

The first line of every `.origin` is one of:

- `recorded <date> <version> neutralized` — taken from a real non-production node and neutralized
  as described below before it entered the repository; the owner reviewed the diff before it was
  committed. A recorded case may carry inferred parts, which its `.origin` names.
- `reconstructed <source>` — written by hand from the named source (e.g. research.md, the helper
  protocol, the recorded texts of another fixture); never seen on a real node in this form.

## Names and content are always fictitious

**Names and content are always fictitious**, also in a `recorded` case: every recorded name,
address, path, process, module, logger and value is replaced by a fictitious one, and a recorded
`.message` is replaced by fictitious content of the same shape and size. Every size that refers to
replaced content (e.g. the byte count in a `part message ok <bytes>` frame) is changed to match.

## Neutralizing a recording

A recording is neutralized **outside the repository**, in a private directory, before any file is
copied in (research D-26):

1. Replace every recorded name, address, path, process, module, logger and value by fictitious
   ones: `acme`, `globex`, nodes `node1`, `node2`, hosts `*.example.test`, paths `/opt/inubit/...`.
2. Replace the content of every `.message` by fictitious content of the same shape and size, and
   adjust the sizes that refer to it.
3. Run `tools/neutralize.py` with the private map (customer values → neutral values) over the
   private directory.
4. Run `NoCustomerIdentifiersTest` with the private lists and
   `python3 -I tools/check-identifiers.py --all`; both must pass.
5. Write the `.origin` (`recorded <date> <version> neutralized`); the owner reviews the diff of the
   fixture files before the commit.

## Message reference vectors (`io-message-ref.vectors`)

Pins the I/O message reference algorithm shared by the server (`IoMessageRef.of`) and the helper:
the reference of a listed entry is Base64url without padding of the first 16 bytes of SHA-256 over
the UTF-8 bytes of the full entry
(`<processId>@@@<moduleName>(<moduleId>)@@@Input.<n>` or `…@@@Output.<n>`; 22 characters). Format: one vector per line,
`<entry>\t<ref>` (entry, a tab, the expected reference); the entries are fictitious. Asserted by
`IoMessageRefTest` and `ClientHelperTest`.

`NodeToolsFixtureCatalogueTest` checks every `.origin`; a (version, capability) is `SUPPORTED` in the
production capability matrix only when its required cases are `recorded` (research D-26, D-27).
