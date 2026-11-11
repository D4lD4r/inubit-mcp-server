# Queue-entry fixtures (feature 020, `read_queue_entry`)

Test data for reading the content of an INUBIT queue entry: the answers of the queue-entry
helper (research D-4), the stored data stream and variables of an entry, and the `queueLog`
rows the entry is looked up in. Everything here is **synthetic and customer-neutral** unless its
`.origin` says `recorded` (research D-18). `QueueEntryFixtureCatalogueTest` enforces the rules
below on every build.

## Layout

```
queue-entry/
  README.md          this file (the only file at this level)
  v8_1/              INUBIT 8.1 cases
  v9_0/              INUBIT 9.0 cases
```

Each version directory holds flat files only; a file belongs to the case named by the part of
its name before the suffix.

### Helper and content cases (`<case>.*`)

| Suffix | Content |
|---|---|
| `.frames` | the helper's stdout: vendor noise lines and `#QE1:<nonce> …` frames |
| `.exit` | the helper's exit code |
| `.data` | the stored data stream the helper writes to its data output path (bytes as stored) |
| `.wfsp` | the stored variables (`.wfsp` properties) the helper writes to its variables output path |
| `.rows.json` | the `queueLog` answer the entry is looked up in (same shape as `../v8_1/rest/log_queueLog_error.json`) |
| `.origin` | provenance (required) |
| `.README` | what the case shows and what the tests expect (required) |

`.frames` and `.exit` come as a pair. All frames use the fixed nonce
`0123456789abcdef0123456789abcdef`; a replay replaces it with the nonce of its request. A
`start` frame names the helper variant of its directory: `SOAP_81` in `v8_1/`, `REST_90` in
`v9_0/`. The byte counts in the `part … ok <bytes>` frames equal the lengths of `.data` and
`.wfsp`, so these two are stored without line-end conversion (`-text` in `.gitattributes`).

### Lookup answers (`rows_<case>.*`)

`rows_<case>.json` is a `queueLog` answer for the entry lookup alone (`QueueEntryRowParserTest`,
`V81ProcessQueryAdapterTest`). Such a case has only `.json` and `.origin`; its `.origin` explains
it, so it needs no `.README`.

## Provenance (`.origin`)

The first line is one of:

- `reconstructed <source> …` — written by hand or generated from the helper protocol and the
  recorded row shape; never seen on a real node.
- `recorded <date> <version> neutralized` — taken from a real node, every business value replaced
  by fictitious content of the same shape; the owner reviewed the diff before it was committed.

## Cases

| Version | Case | Shows |
|---|---|---|
| 8.1 | `api_missing`, `garbage_frames`, `login_failure`, `not_found_file`, `tls_failure` | helper failures; `not_found_file` is recorded (`QueueEntryHelperClassifierTest`, `CliQueueEntryAdapterTest`) |
| 8.1 | `error_entry` | recorded 8.1.17 ERROR entry (neutralized): metadata, text, all 50 variables with system flags, nothing masked |
| 8.1 | `binary_zip` | ZIP data stream; PDF and GIF variables reported as binary |
| 8.1 | `utf16_text` | UTF-16 text is reported as binary (NUL bytes) |
| 8.1 | `latin1_text` | ISO-8859-1 data and variables decoded as such |
| 8.1 | `secrets_everywhere` | every FR-011 masking rule in data, variables and the row |
| 8.1 | `many_variables` | 250 variables, 200 returned |
| 8.1 | `long_values` | data cut at `maxChars`, value cut at 2,000 characters, the 40,000-character variable budget, a long name |
| 8.1 | `missing_variables` | INUBIT finds the data but not the variables: partial result |
| 8.1 | `cutoff_tag` | a cut inside an unclosed secret element or placeholder leaks nothing |
| 8.1 | `too_large` | the row reports more than 10 MiB: only the variables are requested |
| 8.1 | `rows_*` | lookup answers (see above) |
| 9.0 | `error_entry` | recorded 9.0.10 ERROR entry (neutralized): metadata, text, all 11 variables as system variables, nothing masked |
| 9.0 | `missing_workflow`, `not_found_file` | 9.0 helper failures (`not_found_file` is recorded); INUBIT reports both parts not found: partial result while the row stays, `NOT_FOUND` once it left |

`QueueEntryV81ContractTest` runs the real `CliQueueEntryAdapter` and `QueueEntryService` over
every 8.1 case with a `.rows.json` (fake process launcher, no helper process, no network); its
case list must equal those files. `QueueEntryV90ContractTest` does the same for every 9.0 case
with `.frames` through the production capability matrix (the rows of `error_entry` from its
`.rows.json`, those of the failure cases answered in the test); its case list must equal those
files.

## Recording (OWNER)

- `v8_1/error_entry.*` (tasks.md T027, T035) and `v8_1/not_found_file.*` (T035) are the
  neutralized 8.1.17 recordings; the owner reviews their diff before they are committed.
  `QueueEntryV81ContractTest` runs the `error_entry` case.
- `v9_0/error_entry.*` and `v9_0/not_found_file.*` (T037, T038) are the neutralized 9.0.10
  recordings, reviewed before they were committed; `QueueEntryV90ContractTest` runs both.
- INUBIT 9.0 is `SUPPORTED` for `read_queue_entry` in the capability matrix exactly when
  `v9_0/error_entry.origin` starts with `recorded`.
