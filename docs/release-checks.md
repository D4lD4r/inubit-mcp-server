# Release checks

Checks that are not part of the normal build (`mvn verify`) but are **mandatory before every
release**.

## 1. No customer identifiers in the repository

The repository must not contain identifiers of any customer (user groups, diagram groups, stage and
host names, credential variable names, certificate names and the like). The guard test
`NoCustomerIdentifiersTest` (`src/test/java/de/dadecker/inubit/mcp/security/`) scans the current
git-tracked text files and the new files that are not ignored yet (`git ls-files --cached --others
--exclude-standard`; outside a git checkout every file except `.git/` and `target/`), including the
text entries of ZIP archives such as the fixture ZIPs, for patterns from a **denylist**. When a
previous build left JARs in `target/`, their entry names and text entries are scanned too (run the
check after `mvn package` or `mvn verify`, without `clean`, to include them). Binary files are skipped.

File and entry names are checked too. A matching name is reported by its position only, and the
findings in its content use that redacted location:

- `<name of scanned file #n>`: line `n` of
  `git ls-files --cached --others --exclude-standard | LC_ALL=C sort -u`, followed by the sorted
  `target/*.jar` files;
- `<archive>!<name of entry #k>`: line `k` of `unzip -Z1 <archive>`.

The denylist names the customer values it looks for, so it is **never committed**. It stays on the
machine of the person who runs the release check.

### Denylist location

The test reads the denylist from:

1. the file named by the environment variable `INUBIT_MCP_DENYLIST` (a path). If the variable is
   set but the file does not exist, the test **fails** (no fallback, so a mistyped path cannot
   silently skip or weaken the check);
2. otherwise the file `.denylist` in the repository root (listed in `.gitignore`).

Without a denylist the test is **skipped**. That is why the normal build does not run the check, and
why the release check has to be run explicitly with a denylist.

### Denylist format

- Plain text, UTF-8, one pattern per line.
- Each pattern is a Java regular expression (`java.util.regex.Pattern`). Use `(?i)` at the start of a
  pattern for a case-insensitive match.
- Lines starting with `#` are comments. Empty lines are ignored.
- Findings are reported as `file:line` with the number of the pattern that matched, never with the
  matched text.

Example with fictitious values:

```text
# customer user group
(?i)\bexample-owners\b
# host names of the customer
inubit-[a-z]+\.customer\.invalid
```

### Run

```bash
INUBIT_MCP_DENYLIST="$HOME/.config/inubit-mcp/denylist.txt" mvn -q -Dtest=NoCustomerIdentifiersTest test
```

or, with a `.denylist` file in the repository root:

```bash
mvn -q -Dtest=NoCustomerIdentifiersTest test
```

Expected: the test runs (not skipped) and passes. Fix every reported finding before the release.

### When to run it: after staging, before committing

The test scans the working tree. What gets committed is the index, so run the check **after
`git add` and before `git commit`**, and make sure nothing relevant is left unstaged:

```bash
git add -A                                   # stage the complete change (ignored files stay out)
git status --short                           # no unstaged (" M", "??") entries left
mvn -q -Dtest=NoCustomerIdentifiersTest test # with the denylist: 0 findings
git commit
```

A file renamed with `git mv` and edited afterwards must be staged again (`git add`), otherwise the
index still holds the old content.

## 2. Publishing a release

1. Set the release version in `pom.xml` (no `-SNAPSHOT`) and add its section `## [<version>] - <date>`
   to `CHANGELOG.md`; the release notes are taken from that section.
2. Run `mvn -q clean verify`, then the check of section 1 with the denylist (it also scans the
   JAR in `target/`), commit, and push the commit to `main` (the CI workflow runs only on `main`;
   tag only a commit that is on `main`).
3. Push the tag `v<version>` (e.g. `git tag v0.1.0 && git push origin v0.1.0`). The workflow
   `.github/workflows/release.yml` refuses a tag that differs from the pom version, builds and
   tests, and publishes `inubit-mcp-server-<version>.jar`, its `.sha256` file and a build
   provenance attestation as a GitHub release.

## Related local files

The neutralization script `tools/neutralize.py` reads its customer → neutral value mapping from the
file named by `INUBIT_MCP_NEUTRALIZE_MAP`, or from `.neutralize-map` in the repository root. Like the
denylist, this file is local and listed in `.gitignore`.

Map format: one rule per line, `<regex><TAB><replacement>` (Python regular expression, `(?i)` for a
case-insensitive match; the replacement is literal text), applied top to bottom; `#` comments and
empty lines are ignored. Example with fictitious values:

```text
# host names of the customer
inubit\.dev\.customer\.invalid	inubit-dev-1.example.test
# customer user group
\bEXAMPLE-OWNERS\b	OWNERS
```

```bash
tools/neutralize.py --self-test                 # built-in checks, fictitious values only
tools/neutralize.py --dry-run src docs specs    # report what would change
tools/neutralize.py src docs specs              # rewrite text files and ZIP text entries in place
```

The script prints file names, rule numbers and counts only, never a matched or mapped value. It is
idempotent, re-packs ZIP archives deterministically (entry order, names, timestamps and compression
kept) and reports file names that match a rule; rename those by hand. Values that depend on context
(for example short stage names that are also ordinary words) are not part of the map and are fixed
by hand; the denylist test is the acceptance criterion. All files are processed before anything is
written; on an error no file changes. `--map FILE` applies another map in the same format, and ZIP
entries larger than `--max-entry-bytes` (default 64 MiB) are refused.

## Synthetic object names

Test data uses synthetic INUBIT object names only: diagram groups `GRP-01`…, workflows `Workflow-0001`…,
modules `Module-0001`…, web services `Service-01`…, version tags `TAG-01`… (plugin types such as
`XSLT Converter` stay). For a new recording, collect the names from the recorded fixtures into the
local, git-ignored `.synthetic-map` and apply it:

```bash
tools/synthesize_names.py --self-test
tools/synthesize_names.py collect --keep OWNERS --out .synthetic-map src/test/resources/fixtures
tools/neutralize.py --map .synthetic-map src/test
```

`--through .neutralize-map` covers names whose spelling the neutralization map already changed;
`--extra FILE` adds names that only occur in test code. Numbers follow the case-insensitive order of
the names (with `--through`: of their neutralized spelling), so sorted results keep their order, and
are unique per name; the script prints counts only. Names used only by tests are chosen by hand
with the properties a test relies on (substring, sort order, edit distance).
