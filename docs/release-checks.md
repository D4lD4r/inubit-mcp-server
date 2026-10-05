# Release checks

Checks that are not part of the normal build (`mvn verify`) but are **mandatory before every
release**.

## 1. No customer identifiers in the repository

The repository must not contain identifiers of any customer (user groups, diagram groups, stage and
host names, credential variable names, certificate names, workflow and module names and the like).
Two tools check this against the same local **identifier lists**:

- the guard test `NoCustomerIdentifiersTest` (`src/test/java/de/dadecker/inubit/mcp/security/`),
  part of every local `mvn verify`, and
- `tools/check-identifiers.py`, run by the git hooks in `.githooks/` before every commit and push.

The guard test scans the current git-tracked text files and the new files that are not ignored yet
(`git ls-files --cached --others --exclude-standard`; outside a git checkout every file except
`.git/` and `target/`), including the text entries of ZIP archives such as the fixture ZIPs. When a
previous build left JARs in `target/`, their entry names and text entries are scanned too (run the
check after `mvn package` or `mvn verify`, without `clean`, to include them). Binary files are
skipped.

File and entry names are checked too. A matching name is reported by its position only, and the
findings in its content use that redacted location:

- `<name of scanned file #n>`: line `n` of
  `git ls-files --cached --others --exclude-standard | LC_ALL=C sort -u`, followed by the sorted
  `target/*.jar` files;
- `<name of staged file #n>` (pre-commit hook): line `n` of
  `git diff --cached --name-only --no-renames --diff-filter=ACMRT`;
- `<commit>:<name of changed file #n>` (pre-push hook): line `n` of
  `git diff --name-only --no-renames --diff-filter=ACMRT <first parent> <commit>`;
- `<archive>!<name of entry #k>`: line `k` of `unzip -Z1 <archive>`.

### Identifier lists

The lists name the customer values they look for, so they are **never committed**. They stay on
the machine of the person who works with customer data.

| List | Content | Used as forbidden patterns |
|------|---------|----------------------------|
| denylist | one Java regular expression per line | every line |
| neutralize map | `<regex><TAB><replacement>` per line (`tools/neutralize.py`) | the regular expressions (left-hand sides) |
| synthetic map | the same format, written by `tools/synthesize_names.py` | the regular expressions (left-hand sides) |

All three are plain UTF-8 text; lines starting with `#` are comments, empty lines are ignored, and
rules are numbered from 1 in file order (the numbering of `tools/neutralize.py`). The replacement of
a map rule is ignored. Missing single lists are fine: the check uses the lists that exist.

- **Denylist**: Java regular expressions (`java.util.regex.Pattern`, `(?i)` at the start for a
  case-insensitive match). `tools/check-identifiers.py` compiles them with Python's `re` module and
  ASCII semantics (like Java's defaults; `\z`, `\Z` and `$` are translated), so use the common
  subset of both: no `\p{...}`, `\Q...\E`, nested sets or set intersections; a pattern outside that
  subset is reported as a configuration error.
- **Maps**: Python regular expressions, as `tools/neutralize.py` applies them. The guard test
  compiles them for Java with Python's semantics: `\w`, `\W`, `\b` and `\B` are written out
  with Python's word characters (letters, numbers, `_`; Java's Unicode `\w` would also take
  combining marks, connector punctuation and join controls), `\s` and `\S` include `\x1c`-`\x1f`
  like Python's, and `\Z`, `{,n}`, braces that are no quantifier, `(?P<name>...)`, literal `[`
  and `&` in sets and the like are translated. A rule that cannot be translated faithfully
  (conditional groups, the verbose flag `(?x)`, the locale flag `(?L)`) fails the test with its
  list, rule and line number, never with its text. Remaining differences: case-insensitive
  matching uses the JDK's case folding, which misses a few character pairs that Python's `re`
  matches (at most 7 in the BMP, e.g. `ß`/`ẞ` on older JDKs); and character classes follow the
  Unicode version of the JDK, which can differ from Python's for newly assigned characters.
  `tools/check-identifiers.py` (and so the hooks) uses Python's own semantics. The shared test
  vectors in `src/test/resources/identifier-check/parity-vectors.jsonl` are checked by both.

Example with fictitious values:

```text
# denylist: customer user group, host names of the customer
(?i)\bexample-owners\b
inubit-[a-z]+\.customer\.invalid
```

### Lookup order

Each list is looked up separately; the first match wins:

| List | 1. environment variable | 2. repository root | 3. default directory |
|------|-------------------------|--------------------|----------------------|
| denylist | `INUBIT_MCP_DENYLIST` | `.denylist` | `denylist.txt` |
| neutralize map | `INUBIT_MCP_NEUTRALIZE_MAP` | `.neutralize-map` | `neutralize-map` |
| synthetic map | `INUBIT_MCP_SYNTHETIC_MAP` | `.synthetic-map` | `synthetic-map` |
| allowlist (optional) | `INUBIT_MCP_IDENTIFIER_ALLOWLIST` | `.identifier-allowlist` | `identifier-allowlist` |

The default directory is `~/.config/inubit-mcp/` (`$HOME`; on Windows `%APPDATA%\inubit-mcp\`).
With the lists there, a plain `mvn verify` runs the check, and so do the hooks. The files in the
repository root are listed in `.gitignore`.

A variable that is set but names no file is an **error** (the test fails, the script exits with
2): a mistyped path must not silently skip or weaken the check. `tools/neutralize.py` keeps its own
lookup (`--map`, `INUBIT_MCP_NEUTRALIZE_MAP`, `.neutralize-map`).

Without any of the three lists the guard test is **skipped**, and its skip message names the lists
it looked for and where (paths only). That is why CI cannot run the check: the lists are private
and do not exist on the CI runner.

### Findings

Every finding names the location, the list and the rule number, and shows the match **masked**: at
most its first two characters (one for matches of 3 to 5 characters, none below), an ellipsis and
its length. The full value is never printed, also not in assertion messages:

```text
src/test/resources/fixtures/sample.xml:12: synthetic-map rule 345: Gl…(14)
src/test/resources/fixtures/export.zip!<name of entry #3>: denylist rule 2: in…(23)
```

Fix every finding before committing: replace the value by a fictitious one (`acme`, `globex`,
`*.example.test`, `Workflow-0001`, …; `tools/neutralize.py` and `tools/synthesize_names.py` do this
for recorded fixtures).

### Allowlist for generic words

A map can contain a generic word: the synthetic map, for example, also maps single-word object
names such as a diagram group named like a protocol, and the repository uses such words in
fictitious test data. Such a match is a false positive. Do not remove the rule from the map (the
map is still needed for new recordings) and do not weaken the check; add the exact value to the
local allowlist instead:

- one value per line, compared with the **complete** match (case-sensitive; surrounding blanks are
  removed; `#` comments and empty lines are ignored);
- a match whose text is on the allowlist is not reported; a longer or differently spelled match of
  the same rule still is;
- the allowlist is local like the lists (it shows which words the maps contain) and is looked up
  in the same way (table above).

Keep it short and review it when the maps change: every value on it is a value the check no longer
reports anywhere.

### Run

```bash
mvn -q -Dtest=NoCustomerIdentifiersTest test     # the guard test alone
python3 tools/check-identifiers.py --all         # the same check without Maven
python3 tools/check-identifiers.py --staged      # only the staged changes
printf 'HEAD %s HEAD %040d\n' "$(git rev-parse HEAD)" 0 \
  | python3 tools/check-identifiers.py --push --remote origin   # what origin does not have
python3 tools/check-identifiers.py --self-test   # built-in checks, fictitious values only
```

Expected: the test runs (not skipped; its output names the rule count per list) and passes, and the
script prints nothing and exits with 0. `tools/check-identifiers.py` exits with 1 on findings and
with 2 on a usage or configuration error (for example an unreadable or invalid list). Without any
list it prints a warning and exits with 0, with `--strict` it exits with 2.

### Git hooks

The hooks in `.githooks/` run the check automatically. Install them once per clone:

```bash
git config core.hooksPath .githooks
```

- `pre-commit` runs `python3 tools/check-identifiers.py --staged`: the added and changed lines of
  the index (`git diff --cached`), staged ZIP and other binary files completely (text entries and
  entry names), and the staged file names. It checks what gets committed, not the working tree.
  Renames are checked as added files (`--no-renames`), so a commit that only renames a file that
  contains a value is reported too; this is intended.
- `pre-push` checks what is pushed and then the working tree:
  1. `python3 tools/check-identifiers.py --push --remote "$1" --url "$2"` reads the lines git passes
     to the hook (`<local ref> <local sha> <remote ref> <remote sha>`) and checks everything the
     push publishes that the push target does not have yet. "Has" means: reachable from a ref that
     `git ls-remote <url>` lists for the URL git pushes to, or from the old value of the pushed ref.
     Remote-tracking refs are not used, because they may belong to another remote or be stale (a
     branch deleted on the server). If the refs of the target cannot be listed, only the old value
     of the pushed ref is excluded and a warning is printed: more is checked, never less. Listing
     the refs contacts the remote a second time, non-interactively: no terminal or
     credential-manager prompts (`GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`), ssh in batch
     mode unless `GIT_SSH_COMMAND`, `GIT_SSH` or `core.sshCommand` is set, so only cached
     credentials and loaded keys are used; it is stopped after 30 seconds
     (`INUBIT_MCP_LSREMOTE_TIMEOUT`, in seconds) together with its child processes. For every such
     commit the check covers the added and changed lines of its diff against its first parent (the
     empty tree for a root commit), changed ZIP and binary files completely, the changed file names,
     the commit message and the author and committer name and e-mail; for every pushed annotated tag
     its tag object (name, tagger, message); and the names of the pushed refs, local and remote side
     (a matching name is reported as `<name of pushed ref #n>`, `n` = the line of the hook input).
     So a value that was committed with `--no-verify`, came in by a merge, cherry-pick or rebase, or
     was added and removed again in a later commit, still blocks the push. Branch deletions are
     skipped, names included: a deletion publishes nothing. Findings carry the abbreviated commit or
     tag: `1a2b3c4d5e6f:path:line: …`, `…:<commit message>:line: …`,
     `…:<author and committer>:line: …`, `…:<tag object>:line: …`.
  2. `python3 tools/check-identifiers.py --all`: every tracked and untracked, not ignored file of
     the working tree, including ZIP text entries (like the guard test, without the JARs in
     `target/`).

  A finding in a commit is fixed by rewriting the unpushed commits (for example
  `git rebase -i`, `git commit --amend`), not by a new commit on top.

Both block on findings and on configuration errors and print how to fix them. Without any list
they print a warning and let the commit or push pass. `git commit --no-verify` and
`git push --no-verify` bypass them; this is discouraged, run the check by hand before.

### When to run it

The guard test scans the working tree, the pre-commit hook the index. Without the hooks, run the
check **after `git add` and before `git commit`**, and make sure nothing relevant is left unstaged:

```bash
git add -A                                   # stage the complete change (ignored files stay out)
git status --short                           # no unstaged (" M", "??") entries left
python3 tools/check-identifiers.py --staged  # with the lists: no findings
git commit
```

A file renamed with `git mv` and edited afterwards must be staged again (`git add`), otherwise the
index still holds the old content.

## 2. Publishing a release

1. Set the release version in `pom.xml` (no `-SNAPSHOT`) and add its section
   `## [<version>] - <date>` to `CHANGELOG.md`; the release notes are taken from that section.
2. Run `mvn -q clean verify`, then the guard test of section 1 again without `clean`
   (`mvn -q -Dtest=NoCustomerIdentifiersTest test`, so it also scans the JAR in `target/`; it must
   run, not be skipped), commit, and push the commit to `main` (the CI workflow runs only on `main`;
   tag only a commit that is on `main`).
3. Push the tag `v<version>` (e.g. `git tag v0.1.0 && git push origin v0.1.0`). The workflow
   `.github/workflows/release.yml` refuses a tag that differs from the pom version, builds and
   tests, and publishes `inubit-mcp-server-<version>.jar`, its `.sha256` file and a build
   provenance attestation as a GitHub release.

## Related local files

The neutralization script `tools/neutralize.py` reads its customer → neutral value mapping from the
file named by `INUBIT_MCP_NEUTRALIZE_MAP`, or from `.neutralize-map` in the repository root. Like
the denylist, this file is local and listed in `.gitignore`; the identifier check of section 1 also
uses its regular expressions as forbidden patterns (and finds it in `~/.config/inubit-mcp/` too).

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
by hand; the identifier check of section 1 is the acceptance criterion. All files are processed
before anything is written; on an error no file changes. `--map FILE` applies another map in the
same format, and ZIP entries larger than `--max-entry-bytes` (default 64 MiB) are refused.

## Synthetic object names

Test data uses synthetic INUBIT object names only: diagram groups `GRP-01`…, workflows
`Workflow-0001`…, modules `Module-0001`…, web services `Service-01`…, version tags `TAG-01`… (plugin
types such as `XSLT Converter` stay). For a new recording, collect the names from the recorded
fixtures into the local, git-ignored `.synthetic-map` and apply it:

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

The identifier check of section 1 uses the regular expressions of the synthetic map as forbidden
patterns, so an original object name cannot come back with a later change. Keep the map (or a
copy in `~/.config/inubit-mcp/synthetic-map`) after applying it.
