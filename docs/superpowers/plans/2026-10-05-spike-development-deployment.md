# Spike: development and deployment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the open questions of section 10 of the design with evidence from a real INUBIT
8.1 development stage, so that features 003–005 can be specified without guesses.

**Architecture:** Throw-away probe scripts in a private spike directory outside the repository
call StartCLI exactly as the server's `CliRunner` does (argument array, password on stdin,
English locale). Raw exports stay in that directory; only neutralized findings are written to
`docs/research/spike-development-deployment.md` and committed. All writes go to one dedicated
test workflow on the development stage.

**Tech Stack:** zsh, StartCLI of the local INUBIT 8.1 client, `unzip`/`zip`, `xmllint`, `diff`,
Saxon-HE 10.9 (Maven Central) for the XSLT probe, `tools/check-identifiers.py`.

**Spec:** `docs/design/2026-10-05-development-and-deployment.md` (sections 4 and 10).

## Global Constraints

- Production code only via Spec Kit (Constitution, "Development Workflow"): this spike adds **no**
  code under `src/`; probe scripts are throw-away and are never committed.
- Writes (import, tag, activate) only on the development node of the local profile and only on the
  test workflow and test modules named in Task 1. Every other artifact is read-only.
- No write of any kind on any other stage. Exports (read-only) from the next stage are allowed in
  Task 5 only.
- Secrets never reach the conversation, a committed file or a command line (Constitution II):
  passwords go to StartCLI on stdin; output that may contain `Password`, `KeyStore`, `X509` or
  `AES-` values is shown only through the masking filter of Task 2.
- Raw exports and probe outputs live only in `$SPIKE` (`~/.inubit-mcp/<profile>/spike`,
  `rwx------`). They name customer values and must never be copied into the repository.
- Committed text is English and neutral: no customer, host, user, group, workflow or module names
  (`python3 tools/check-identifiers.py --staged` must pass before every commit).
- StartCLI environment: `JAVA_HOME=<cliJavaHome>`, `JAVA_TOOL_OPTIONS="-Duser.language=en
  -Duser.country=US"`, arguments `-u <user> [--trustStoreFilePath <p12>]
  [--disableHostNameVerification] --execCommand "<command>" <url>/ibis/servlet/IBISSoapServlet`.

## Review Focus

- **Import touches more than the test workflow** (e.g. an export ZIP that also carries shared
  modules or repository files): Task 6 lists every artifact in the archive before importing and
  aborts if anything outside the test set is in it.
- **A failed import leaves the test workflow half-changed**: Task 6 takes a backup export first
  and Task 6 Step 6 proves that re-importing the backup restores it.
- **Secret values printed while inspecting module XML**: every inspection command in Tasks 3–8
  pipes through `mask` (Task 2); Task 2 Step 3 proves the filter catches each secret shape.
- **A colleague has the test workflow checked out**: Task 9 measures what export and import do in
  that state instead of assuming.
- **Export of a large group times out**: Task 3 exports only the test diagram group; the
  owner-wide stylesheet probe in Task 10 uses the existing module export with a 300 s timeout and
  records the duration.

---

### Task 1: Test artifacts and safety gate (person + agent)

**Files:**
- Create: `$SPIKE/spike.env` (never committed)

**Interfaces:**
- Produces: `$SPIKE/spike.env` exporting `SPIKE`, `CLI_HOME`, `CLI_JAVA_HOME`, `CLI_URL`,
  `TRUSTSTORE`, `NO_HOSTNAME_CHECK` (0/1), `USER_VAR`, `PASS_VAR` (names of the credential
  variables), `OWNER`, `TEST_GROUP`, `TEST_WORKFLOW`, `NEXT_STAGE_URL`.

- [ ] **Step 1: The person creates the test artifacts in the Workbench** on the development
  stage, owned by the usual owner:
  - diagram group `SPIKE` (or a name the person chooses), technical workflow `SPIKE_Roundtrip`;
  - nodes: an input (any connector the stage allows, or none), an **XSLT Converter** module
    `SPIKE_Xslt` with a 3-line identity stylesheet, a **Demultiplexer** with two outputs (one
    condition `/*/@type = 'a'`, one default), an **Assign** module on each branch;
  - checked in, not checked out.
- [ ] **Step 2: The person confirms in chat** the diagram group and workflow name and that the
  agent may import, tag and (de)activate exactly these artifacts. Without this confirmation stop.
- [ ] **Step 3: Create the spike directory and env file**

```bash
SPIKE=~/.inubit-mcp/<profile>/spike
mkdir -p "$SPIKE" && chmod 700 "$SPIKE"
cat > "$SPIKE/spike.env" <<'EOF'
export SPIKE=~/.inubit-mcp/<profile>/spike
export CLI_HOME=<cliHome from the profile>
export CLI_JAVA_HOME=<cliJavaHome from the profile>
export CLI_URL=<baseUrl of the development node>/ibis/servlet/IBISSoapServlet
export TRUSTSTORE=<tls.trustStore of the group, empty if none>
export NO_HOSTNAME_CHECK=<1 if tls.disableHostnameVerification else 0>
export USER_VAR=${USER_VAR:-<name of the username variable, e.g. INUBIT_DEV_USERNAME>}
export PASS_VAR=${PASS_VAR:-<name of the password variable>}
export OWNER=<inventory.owner>
export TEST_GROUP=<diagram group from Step 2>
export TEST_WORKFLOW=<workflow from Step 2>
export NEXT_STAGE_URL=<baseUrl of the first node of the next stage>/ibis/servlet/IBISSoapServlet
EOF
chmod 600 "$SPIKE/spike.env"
```

Fill the values from `~/.config/inubit-mcp/<profile>.yaml`; credentials themselves are not in the
file, only the names of the variables (the Keychain start script or the shell provides them).

### Task 2: Probe helper and masking filter

**Files:**
- Create: `$SPIKE/cli.sh`, `$SPIKE/mask.sh`

**Interfaces:**
- Produces: `cli.sh [--url <url>] "<command>"` → StartCLI stdout+stderr on stdout, exit code of
  StartCLI, duration on stderr; `mask.sh` → stdin to stdout with secret values replaced by
  `«masked»`.

- [ ] **Step 1: Write `cli.sh`**

```zsh
#!/bin/zsh
# Throw-away StartCLI probe: same invocation as CliRunner (password on stdin).
set -euo pipefail
source ~/.inubit-mcp/<profile>/spike/spike.env
url=$CLI_URL
if [[ ${1:-} == --url ]]; then url=$2; shift 2; fi
args=(-u "${(P)USER_VAR}")
[[ -n $TRUSTSTORE ]] && args+=(--trustStoreFilePath "${TRUSTSTORE/#\~/$HOME}")
[[ $NO_HOSTNAME_CHECK == 1 ]] && args+=(--disableHostNameVerification)
args+=(--execCommand "$1" "$url")
start=$SECONDS
set +e
print -r -- "${(P)PASS_VAR}" | JAVA_HOME=$CLI_JAVA_HOME \
  JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \
  "${CLI_HOME/#\~/$HOME}/bin/startcli.sh" "${args[@]}" 2>&1
rc=$?
print -u2 "exit=$rc seconds=$((SECONDS - start))"
exit $rc
```

- [ ] **Step 2: Write `mask.sh`**

```zsh
#!/bin/zsh
# Masks secret values in XML/text: Password properties, AES values, keystores, certificates.
sed -E \
  -e 's/(type="(Password|KeyStore|X509)"[^>]*>)[^<]*/\1«masked»/g' \
  -e 's/AES-[A-Za-z0-9+\/=]+/AES-«masked»/g' \
  -e 's/(isPassword="true"[^>]*>)[^<]*/\1«masked»/g' \
  -e 's/H4sI[A-Za-z0-9+\/=]{40,}/«gzip-base64»/g'
```

- [ ] **Step 3: Prove the filter**

```zsh
chmod 700 $SPIKE/cli.sh $SPIKE/mask.sh
printf '%s\n' '<Property name="Password" type="Password" encrypted="true">AES-abc+/=</Property>' \
  '<Property name="k" type="KeyStore">MIIabc</Property>' '<literal isPassword="true">pw</literal>' \
  | $SPIKE/mask.sh
```

Expected: three lines, each with `«masked»` and none of `abc`, `MIIabc`, `pw`.

- [ ] **Step 4: Smoke test and help texts**

```zsh
$SPIKE/cli.sh "uname" | $SPIKE/mask.sh
for c in export import tag deploy delete; do $SPIKE/cli.sh "help $c" > $SPIKE/help-$c.txt 2>&1; done
$SPIKE/cli.sh "-h" > $SPIKE/help-all.txt 2>&1 || true
```

Expected: `uname` prints the server version and exits 0. If `help <cmd>` is not a command, try
`<cmd> -h` and `<cmd> --help` and keep whichever prints options. Record per command: options,
which ones take a file, and the exit code of an unknown command
(`$SPIKE/cli.sh "nosuchcommand"`) and of a wrong password (run once with `PASS_VAR` pointing to an
unset variable — one failed login only, the account-lockout rule of the server applies).

### Task 3: Export layout (read-only)

**Files:**
- Create: `$SPIKE/export/{wf,wf-history,wf-modules,module-only}/…`

- [ ] **Step 1: Export the test workflow four ways**, using the options found in Task 2 (the
  server already uses `export --exportWorkflowUser '<owner>' --exportWorkflowType 'technical'
  --exportWorkflowGroup '<group>' [--includeHistory] --exportFile <zip>` and
  `export --exportModule '' --exportModuleGroup '' --exportModuleUser '<owner>' --exportFile <zip>`):

```zsh
cd $SPIKE/export
$SPIKE/cli.sh "export --exportWorkflowUser '$OWNER' --exportWorkflowType 'technical' --exportWorkflowGroup '$TEST_GROUP' --exportFile '$SPIKE/export/wf.zip'"
$SPIKE/cli.sh "export --exportWorkflowUser '$OWNER' --exportWorkflowType 'technical' --exportWorkflowGroup '$TEST_GROUP' --includeHistory --exportFile '$SPIKE/export/wf-history.zip'"
$SPIKE/cli.sh "export --exportModule 'SPIKE_Xslt' --exportModuleGroup 'XSLT Converter' --exportModuleUser '$OWNER' --exportFile '$SPIKE/export/module-only.zip'"
```

Add the variant "workflow with all used modules" if Task 2 found an option for it.

- [ ] **Step 2: List and compare**

```zsh
for z in *.zip; do print "== $z"; unzip -Z1 $z; done > layout.txt
for z in *.zip; do mkdir -p ${z%.zip}; unzip -oq $z -d ${z%.zip}; done
```

Record per variant: entries, whether `workflow/workflow.xml`, `module/module.xml`,
`module/<name>.xml`, `Repository.zip`, `versionHistory.xml`, `archive.properties` occur, and how
the module-only archive differs from the workflow archive. Compare with section 4 of the design.

### Task 4: Volatile values (read-only)

- [ ] **Step 1: Export the same workflow again** to `wf2.zip` (as Task 3 Step 1, first command),
  unzip to `wf2/`.
- [ ] **Step 2: Diff**

```zsh
cd $SPIKE/export
diff -r wf wf2 | $SPIKE/mask.sh > volatile.diff; wc -l volatile.diff
unzip -Z -T wf.zip | head; unzip -Z -T wf2.zip | head
```

Record every field that differs (expected per design: `archive.properties` date and
`operationId`, entry timestamps, `CheckinComment` timestamp, `ExportUser`). Record whether `AES-`
values differ between the two exports (`grep -c AES- …` per file, then compare the values with
`cmp`, never print them).

### Task 5: Secret values across stages (read-only)

- [ ] **Step 1: Pick one connector module with a `Password` property that exists on both the
  development stage and the next stage** (the person names it; it is exported read-only).
- [ ] **Step 2: Export it from both**: `cli.sh "<export command>"` for the development stage and
  `USER_VAR=<next stage username variable> PASS_VAR=<next stage password variable> cli.sh --url
  "$NEXT_STAGE_URL" "<export command>"` for the next stage (`spike.env` keeps values already set).
- [ ] **Step 3: Compare without printing**

```zsh
a=$(grep -o 'AES-[A-Za-z0-9+/=]*' dev/module/<name>.xml | shasum | cut -c1-12)
b=$(grep -o 'AES-[A-Za-z0-9+/=]*' next/module/<name>.xml | shasum | cut -c1-12)
print "dev=$a next=$b"
```

Record: same hash → ciphertext is server-independent (or both stages use the same password); a
different hash is inconclusive. In both cases the design rule "restore secrets from the target"
stands; the finding only decides whether a placeholder can ever be resolved from a source stage.

### Task 6: Round trip without change (write: test workflow only)

- [ ] **Step 1: Backup**: export the test diagram group to `$SPIKE/backup/before-6.zip`.
- [ ] **Step 2: Guard**: `unzip -Z1` the archive to be imported and check that `workflow.xml`
  contains only workflows of `$TEST_GROUP` and the module files only the test modules
  (`xmllint --xpath '//WorkflowName/text()' …`, `//ModuleName/text()`). Abort otherwise.
- [ ] **Step 3: Repack unchanged** (`cd wf && zip -X -r ../wf-repacked.zip .`) — same entries,
  new timestamps.
- [ ] **Step 4: Import** with the option that keeps the active flag (from Task 2, likely
  `import --importWorkflow '<zip>' --returnProtocol`); save stdout to `import-6.txt`.
- [ ] **Step 5: Export again** to `after-6.zip`, unzip, `diff -r wf after-6 | mask.sh`.
  Record: new version created? `CheckinComment` content? ids (`WorkflowUId`, `ModuleUId`,
  `ModuleId`) stable? Active flag unchanged? Protocol format and exit code.
- [ ] **Step 6: Restore proof**: import `before-6.zip`, export, diff against `wf` — the workflow
  content must match (version counters aside).

### Task 7: Structural change (write: test workflow only)

- [ ] **Step 1: Edit `wf/workflow/workflow.xml`** (copy of the unzipped export):
  - duplicate one Assign `WorkflowModule`, give it the next free `ModuleId` and a new name
    `SPIKE_Assign3`, and insert it on the default branch (change the Demultiplexer's
    `Connection@moduleOutId` to the new node, add a `Connection` from the new node to the old
    target);
  - change the Demultiplexer condition value from `'a'` to `'b'` (instance property
    `<Name>(<id>)@@@DeMuxInput`).
- [ ] **Step 2: Backup, guard, repack, import** as Task 6 Steps 1–4 (`import-7.txt`).
- [ ] **Step 3: Export and diff**; the person opens the workflow in the Workbench and confirms the
  new node, the edges and the condition look right.
- [ ] **Step 4: Negative probe**: repeat with a broken edge (`moduleOutId` of a non-existent id).
  Record whether INUBIT rejects the import, accepts it silently, or repairs it — this decides how
  much `check_artifacts` must catch. Restore with the Step 2 backup afterwards and confirm by export.
- [ ] **Step 5: New module probe**: does importing a workflow that references a module name that
  exists neither in the archive nor on the server fail? Restore afterwards.

### Task 8: Module content change (write: test module only)

- [ ] **Step 1: Change the stylesheet** inside `module/spike_xslt.xml` (`xslt.stylesheet`,
  escaped XML): add a comment `<!-- spike -->`.
- [ ] **Step 2: Import as module only** (module-only archive from Task 3 with the changed file, or
  the module option from Task 2) and as part of the workflow archive; record which works.
- [ ] **Step 3: Export and diff**; record whether the stylesheet comes back byte-identical
  (escaping, encoding, `xslt.base64Zipped`).

### Task 9: Checkout, tags, active flag (write: test workflow only)

- [ ] **Step 1: Checkout**: the person checks out `$TEST_WORKFLOW` in the Workbench. Export →
  is `CheckoutUser` set? Import (as Task 6) → refused, overwritten or queued? The person checks it
  in again; restore if needed.
- [ ] **Step 2: Tag**: set tag `SPIKE-1` on the current version (CLI `tag` options from Task 2,
  else REST); change the workflow (Task 7 style, trivial) and import; export with
  `--exportTag 'SPIKE-1'` → does it return the tagged (older) version?
- [ ] **Step 3: Active flag**: deactivate and reactivate the test workflow (CLI `tag
  --tagSetActive` or REST; record which channel works) and confirm with `get_health`-independent
  evidence: export shows `IsActive`.

### Task 10: Stylesheets on Saxon-HE 10 (read-only, local)

- [ ] **Step 1: Get Saxon-HE 10.9** from Maven Central into `$SPIKE/lib/`
  (`https://repo1.maven.org/maven2/net/sf/saxon/Saxon-HE/10.9/Saxon-HE-10.9.jar`; ask the person
  before downloading).
- [ ] **Step 2: Extract all stylesheets** of the owner's XSLT modules from the module export the
  server already performs (`export --exportModule '' …`, timeout 300 s), unescaping
  `xslt.stylesheet` into `$SPIKE/xsl/<n>.xsl` (number them; no names in the results).
- [ ] **Step 3: Compile each** (`java -cp Saxon-HE-10.9.jar net.sf.saxon.Transform -xsl:<f>
  -s:empty.xml -o:/dev/null`) and classify: compiles; fails on `java:` extension (which class);
  fails on schema-aware/EE feature; other error. Record counts per class and the list of INUBIT
  extension functions used, with call counts.

### Task 11: Findings document

**Files:**
- Create: `docs/research/spike-development-deployment.md`
- Modify: `docs/design/2026-10-05-development-and-deployment.md` (section 4 "*Spike*" markers and
  section 10 → resolved, with a link)

- [ ] **Step 1: Write the findings** in the order of Tasks 2–10, each with: question, evidence
  (neutral: `<workflow>`, `<module>`, counts, option names, exit codes), answer, consequence for
  features 003–005. Include the StartCLI option tables for `export`, `import`, `tag`, `deploy`.
- [ ] **Step 2: Update the design**: replace each *spike* marker with the answer or a link.
- [ ] **Step 3: Check and commit**

```bash
git add docs/research/spike-development-deployment.md docs/design/2026-10-05-development-and-deployment.md
python3 tools/check-identifiers.py --staged
git commit -m "docs: findings of the development/deployment spike"
```

Expected: the identifier check reports no findings; nothing under `$SPIKE` is staged.

- [ ] **Step 4: Clean up**: the person decides whether the test workflow stays on the development
  stage; `$SPIKE` stays local (it holds raw exports) until feature 003 has taken what it needs, then
  it is deleted.
