# Server-log fixtures (feature 021, `download_server_log`)

Test data for downloading an INUBIT server log: the StartCLI answers of the trace download, the
web service log helper's answers, the log files both "write", and synthetic logs for the excerpt
rules (research D-21). Everything here uses **fictitious names only** (FR-018).

## Layout

```
server-log/
  README.md          this file (the only file at this level)
  v8_1/              INUBIT 8.1 cases
    trace/           trace log cases (StartCLI)
    webservice/      web service log cases (helper)
  v9_0/              INUBIT 9.0 cases (same layout as v8_1/)
  excerpt/           synthetic logs for the excerpt and masking rules (version independent)
```

Each case directory holds flat files only; a file belongs to the case named by the part of its
name before the suffix (`<case>.<ext>`).

### Trace cases (`<version>/trace/<case>.*`)

| Suffix | Content |
|---|---|
| `.stdout` | StartCLI's stdout, replayed by the fake process launcher |
| `.stderr` | StartCLI's stderr |
| `.exit` | StartCLI's exit code |
| `.log` | the trace log StartCLI "writes" to the `--download` path |
| `.origin` | provenance (required) |

### Web service cases (`<version>/webservice/<case>.*`)

| Suffix | Content |
|---|---|
| `.frames` | the helper's stdout (vendor noise lines and protocol frames) |
| `.exit` | the helper's exit code |
| `.log` | the web service log the helper copies to its log output path |
| `.origin` | provenance (required) |

### Excerpt cases (`excerpt/<case>.*`)

| Suffix | Content |
|---|---|
| `.log` | a synthetic log file |
| `.README` | what the case shows and what the tests expect |

## Provenance (`.origin`)

The first line of every `.origin` is one of:

- `recorded <date> <version> neutralized` — taken from a real non-production node (research
  D-23) and neutralized as described below before it entered the repository; the owner reviewed
  the diff before it was committed. A recorded case may carry inferred parts, which its
  `.origin` names (e.g. `v9_0/webservice/empty.origin`: the outcome was recorded, the helper
  frames are inferred from it).
- `reconstructed <source>` — written by hand from the named source (e.g. the static facts in
  research.md, the recorded StartCLI texts of another fixture, the helper protocol); never seen
  on a real node in this form.

## Log content is always fictitious

**Log content is always fictitious; recorded sizes and MB values are adjusted to the fictitious
content.** A `.log` never holds a real server log, also not in a `recorded` case: the recorded
log is replaced by a fictitious log of the same shape, and every size that refers to it (the MB
value in a trace `.stdout`, the byte counts in webservice `.frames`) is changed to match the
fictitious file.

## Neutralizing a recording

A recording is neutralized **outside the repository**, in a private directory, before any file
is copied in (research D-21, as in feature 020 research D-18):

1. Replace the recorded `.log` by a fictitious log of the same shape: timestamps, levels, line
   structure, `acme` classes, node `node1`, hosts `*.example.test`.
2. Adjust the recorded size values to the fictitious `.log`: the MB value in `.stdout`
   (`Trace data (<n> MB) stored to: …`) and the byte counts in `.frames`.
3. Neutralize the remaining recorded files (`.stdout`, `.stderr`, `.frames`): run
   `tools/synthesize_names.py collect --keep <owners> --out <private>/.synthetic-map <dir>` and
   `tools/neutralize.py --map <private>/.synthetic-map <dir>` plus the private `neutralize-map`
   (customer names → `acme`, `*.example.test`).
4. Run `NoCustomerIdentifiersTest` with the private lists and
   `python3 -I tools/check-identifiers.py --all`; both must pass.
5. Write the `.origin` (`recorded <date> <version> neutralized`); the owner reviews the diff of
   the fixture files before the commit.
