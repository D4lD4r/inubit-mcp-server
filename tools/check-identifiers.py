#!/usr/bin/env python3
"""Check for customer identifiers before a commit or push (docs/release-checks.md).

The same check as the guard test NoCustomerIdentifiersTest, for the git hooks in .githooks/. It
contains only the mechanism; the patterns come from three local lists that name customer values
and are therefore never committed:

  denylist        one Java regular expression per line (use the subset that Python's re module
                  reads the same way; it is compiled with re.ASCII, like Java's defaults)
  neutralize-map  "<python-regex><TAB><replacement>" per line (tools/neutralize.py); only the
  synthetic-map   regular expression is used, the replacement is ignored

Lines starting with "#" and empty lines are ignored; rules are numbered from 1 in file order. Each
list is looked up in this order, first match wins:

  1. the file named by its environment variable (INUBIT_MCP_DENYLIST, INUBIT_MCP_NEUTRALIZE_MAP,
     INUBIT_MCP_SYNTHETIC_MAP); a variable that names no file is an error, not a fallback,
  2. its file in the repository root (.denylist, .neutralize-map, .synthetic-map; git-ignored),
  3. its file in the default directory ~/.config/inubit-mcp/ (on Windows %APPDATA%\\inubit-mcp\\):
     denylist.txt, neutralize-map, synthetic-map.

Missing single lists are fine. An optional allowlist (INUBIT_MCP_IDENTIFIER_ALLOWLIST,
.identifier-allowlist, identifier-allowlist in the default directory) holds exact values that
are not reported, one per line (generic words that a map happens to contain).

Modes:
  --staged  (default; pre-commit hook) the added and changed lines of the index (git diff
            --cached), staged ZIP/JAR archives and other binary files completely (text entries
            and entry names; binary content is skipped), and the staged file paths themselves.
            The index is checked, not the working tree.
  --push    (pre-push hook) what is being pushed: reads the lines "<local ref> <local sha>
            <remote ref> <remote sha>" that git passes to the pre-push hook on stdin; --remote
            and --url take the hook's $1 and $2. Checked is every commit that the push target
            does not have yet (not reachable from the refs `git ls-remote <url>` lists, nor from
            the old value of the pushed ref): the added and changed lines of its diff against its
            first parent (the empty tree for a root commit), changed ZIP/JAR and binary files
            completely, the changed file paths, the commit message and the author and committer
            name and e-mail; every pushed annotated tag object (name, tagger, message); and the
            names of the pushed refs (local and remote side; "<name of pushed ref #n>", n = the
            line of the hook input). A
            value that was added and removed again in a later commit is found too. Branch
            deletions are skipped (they publish nothing, not even their name). The refs of the
            target are listed with a second, non-interactive connection (no prompts, ssh in
            batch mode, cached credentials only; timeout 30 s, INUBIT_MCP_LSREMOTE_TIMEOUT). If
            they cannot be listed, only the old value of the pushed ref is excluded (a warning;
            more is checked, never less).
  --all     (pre-push hook, in addition) every tracked and untracked, not ignored file (git
            ls-files --cached --others --exclude-standard), including the text entries of ZIP/JAR
            archives.

Output: one line per finding, "file:line: <list> rule <n>: <masked match>"; the match is masked
(at most its first two characters, then its length), never printed in full. A matching file or
entry name is reported by its position only ("<name of staged file #n>", "<name of scanned file
#n>", "<commit>:<name of changed file #n>", "<archive>!<name of entry #k>") and so are the
findings in its content. --push prefixes every location with the abbreviated commit or tag
("1a2b3c4d5e6f:path:line", "...:<commit message>:line", "...:<author and committer>:line",
"...:<tag object>:line").

Exit status: 0 clean, 1 findings, 2 usage or configuration error (e.g. an unreadable or invalid
list). Without any list: a warning on stderr and exit 0, with --strict exit 2.

Usage:
  check-identifiers.py [--staged | --push [--remote NAME] [--url URL] | --all] [--strict]
                       [--repo DIR]
  check-identifiers.py --self-test
"""

from __future__ import annotations

import argparse
import codecs
import contextlib
import io
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import warnings
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import neutralize  # noqa: E402  (same directory; parses the map format)

LISTS = (  # label, environment variable, repository file, file in the default directory
    ("denylist", "INUBIT_MCP_DENYLIST", ".denylist", "denylist.txt"),
    ("neutralize-map", "INUBIT_MCP_NEUTRALIZE_MAP", ".neutralize-map", "neutralize-map"),
    ("synthetic-map", "INUBIT_MCP_SYNTHETIC_MAP", ".synthetic-map", "synthetic-map"),
)
ALLOWLIST = ("allowlist", "INUBIT_MCP_IDENTIFIER_ALLOWLIST", ".identifier-allowlist",
             "identifier-allowlist")
DEFAULT_DIRECTORY_NAME = "inubit-mcp"
LOCAL_FILES = {entry[2] for entry in LISTS} | {ALLOWLIST[2]}
SKIPPED_DIRECTORIES = {".git", "target"}
BINARY_PROBE = 8000
ARCHIVE_SUFFIXES = (".zip", ".jar")


class ConfigError(Exception):
    """A usage or configuration error (exit status 2). Never names a rule's text."""


# --- lists --------------------------------------------------------------------------------------

def default_directory(environ, home: Path, windows: bool) -> Path:
    if windows:
        appdata = environ.get("APPDATA", "").strip()
        return (Path(appdata) if appdata else home / "AppData" / "Roaming") \
            / DEFAULT_DIRECTORY_NAME
    return home / ".config" / DEFAULT_DIRECTORY_NAME


def locate(entry, environ, root: Path, default_dir: Path) -> Path | None:
    _label, variable, repository_file, default_file = entry
    configured = environ.get(variable, "").strip()
    if configured:
        path = Path(configured)
        if not path.is_file():
            raise ConfigError(f"{variable} is set but names no file: {path}")
        return path
    for candidate in (root / repository_file, default_dir / default_file):
        if candidate.is_file():
            return candidate
    return None


@dataclass(frozen=True)
class Rule:
    source: str  # the list the rule comes from
    number: int
    line: int
    pattern: re.Pattern
    literal: str | None
    folded: bool


def rule_lines(text: str):
    """(rule number, line number, line) of every rule line, numbered like neutralize.py."""
    number = 0
    for index, raw in enumerate(text.split("\n"), 1):
        line = raw[:-1] if raw.endswith("\r") else raw
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        number += 1
        yield number, index, line


def parse_list(label: str, text: str) -> list[Rule]:
    rules = []
    for number, line_number, line in rule_lines(text):
        where = f"{label} rule {number} (line {line_number})"
        if label == "denylist":
            pattern = compile_java(line, where)
        else:
            try:
                [parsed] = neutralize.parse_map(line)  # the same checks as neutralize.py
            except neutralize.MapError as e:
                # "rule #1: <reason>"; the reason never contains the rule text
                raise ConfigError(f"{where}: {str(e).split(': ', 1)[-1]}") from None
            pattern = parsed.pattern
        # Both are Python patterns now (the denylist translated); extract with Python's syntax.
        literal, folded = required_literal(pattern.pattern)
        rules.append(Rule(label, number, line_number, pattern, literal, folded))
    if not rules:
        raise ConfigError(f"the {label} contains no rules")
    return rules


def parse_allowlist(text: str) -> set[str]:
    values = (line.strip() for line in text.split("\n"))
    return {value for value in values if value and not value.startswith("#")}


JAVA_LINE_END = r"(?=(?:\r\n|[\n\r\x85\u2028\u2029])?\Z)"


def compile_java(regex: str, where: str) -> re.Pattern:
    """A denylist pattern (Java syntax) for Python: ASCII semantics like Java's defaults, Java's
    \\z, \\Z and $ translated; constructs that differ (nested sets, intersections) rejected."""
    out = []
    i = 0
    in_set = False
    while i < len(regex):
        c = regex[i]
        if c == "\\" and i + 1 < len(regex):
            d = regex[i + 1]
            if in_set and d == "b":
                raise ConfigError(f"{where}: \\b inside a set (a backspace in Python, an error "
                                  "in Java); use \\x08")
            if not in_set and d == "z":
                out.append(r"\Z")
            elif not in_set and d == "Z":
                out.append(JAVA_LINE_END)
            else:
                out.append(regex[i:i + 2])
            i += 2
            continue
        if in_set:
            in_set = c != "]"
        elif c == "[":
            in_set = True
            out.append(c)
            i += 1
            for prefix in ("^", "]"):
                if regex.startswith(prefix, i):
                    out.append(prefix)
                    i += 1
            continue
        elif c == "$":
            c = JAVA_LINE_END
        out.append(c)
        i += 1
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", FutureWarning)  # nested set / set intersection
            return re.compile("".join(out), re.ASCII)
    except (re.error, FutureWarning):
        raise ConfigError(f"{where}: not a regular expression that Java and Python read the "
                          "same way") from None


@dataclass
class Lists:
    files: dict  # label -> Path | None
    allowlist: Path | None
    root: Path
    default_dir: Path

    def any_list(self) -> bool:
        return any(self.files.values())

    def missing(self) -> list[str]:
        return [label for label, path in self.files.items() if path is None]

    def describe_missing(self) -> str:
        return "; ".join(f"{label}: ${variable}, {self.root / repository_file}, "
                         f"{self.default_dir / default_file}"
                         for label, variable, repository_file, default_file in LISTS
                         if self.files[label] is None)

    def load(self) -> "Scanner":
        rules: list[Rule] = []
        for label, path in self.files.items():
            if path is not None:
                rules += parse_list(label, read_text(path))
        allowed = parse_allowlist(read_text(self.allowlist)) if self.allowlist else set()
        return Scanner(rules, allowed)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        raise ConfigError(f"cannot read {path}: {type(e).__name__}") from None


def locate_lists(environ, root: Path, default_dir: Path) -> Lists:
    files = {entry[0]: locate(entry, environ, root, default_dir) for entry in LISTS}
    return Lists(files, locate(ALLOWLIST, environ, root, default_dir), root, default_dir)


# --- literal prefilter --------------------------------------------------------------------------

QUANTIFIER_RE = re.compile(r"\{(\d*)(,?)(\d*)\}")


def required_literal(regex: str) -> tuple[str | None, bool]:
    """The longest literal every match of the Python pattern `regex` contains (None if there is no
    safe one), and whether the pattern has an inline i flag anywhere (then the literal is looked
    up case-insensitively, with re's own IGNORECASE rules). Mirrors LiteralIndex.java, with
    Python's syntax: sets do not nest, and a brace that is no quantifier is a literal."""
    if "\\Q" in regex or "(?#" in regex:
        return None, False
    folded = False
    best = ""
    run: list[str] = []
    depth = 0
    i = 0
    n = len(regex)

    def end_run():
        nonlocal best
        if len(run) > len(best):
            best = "".join(run)
        run.clear()

    while i < n:
        c = regex[i]
        if c == "\\":
            if i + 1 >= n:
                return None, False
            d = regex[i + 1]
            if d.isascii() and d.isalnum():
                if depth == 0:
                    end_run()
                i = skip_escape(regex, i)
            else:
                if depth == 0:
                    run.append(d)
                i += 2
        elif c == "[":
            if depth == 0:
                end_run()
            i = skip_set(regex, i)
            if i < 0:
                return None, False
        elif c == "(":
            flags = re.match(r"\(\?([a-zA-Z-]+)[:)]", regex[i:])
            if flags:
                if "x" in flags.group(1):
                    return None, False
                folded |= "i" in flags.group(1)
            if depth == 0:
                end_run()
            depth += 1
            i += 1
        elif c == ")":
            depth -= 1
            i += 1
        elif c == "|" and depth == 0:
            return None, False
        elif depth > 0:
            i += 1
        elif c in "*+?":
            if run:
                run.pop()
            end_run()
            i += 1
        elif c == "{":
            quantifier = QUANTIFIER_RE.match(regex, i)
            if quantifier and (quantifier.group(1) or quantifier.group(2)):
                if run:
                    run.pop()
                end_run()
                i = quantifier.end()
            else:
                run.append(c)  # a literal brace in Python
                i += 1
        elif c in ".^$":
            end_run()
            i += 1
        else:
            run.append(c)
            i += 1
    end_run()
    if not best:
        return None, False
    return best, folded


def skip_escape(regex: str, backslash: int) -> int:
    d = regex[backslash + 1]
    i = backslash + 2
    if d in "xNpP" and regex.startswith("{", i):
        close = regex.find("}", i)
        return len(regex) if close < 0 else close + 1
    if d == "k" and regex.startswith("<", i):
        close = regex.find(">", i)
        return len(regex) if close < 0 else close + 1
    width = {"x": 2, "u": 4, "U": 8, "p": 1, "P": 1, "c": 1}.get(d, 0)
    if d.isdigit():
        while i < len(regex) and regex[i].isdigit():
            i += 1
        return i
    return min(len(regex), i + width)


def skip_set(regex: str, open_index: int) -> int:
    """Skips a Python set (sets do not nest); returns -1 if it is unterminated."""
    i = open_index + 1
    if regex.startswith("^", i):
        i += 1
    if regex.startswith("]", i):
        i += 1  # a leading ] is literal
    while i < len(regex):
        c = regex[i]
        if c == "\\":
            i += 2
            continue
        if c == "]":
            return i + 1
        i += 1
    return -1


class AhoCorasick:
    """Finds which of many literals occur in a text in one pass."""

    def __init__(self, literals: list[str]):
        self.goto: list[dict] = [{}]
        self.fail: list[int] = [0]
        self.out: list[list[int]] = [[]]
        for index, literal in enumerate(literals):
            state = 0
            for ch in literal:
                nxt = self.goto[state].get(ch)
                if nxt is None:
                    nxt = len(self.goto)
                    self.goto[state][ch] = nxt
                    self.goto.append({})
                    self.fail.append(0)
                    self.out.append([])
                state = nxt
            self.out[state].append(index)
        queue = list(self.goto[0].values())
        for state in queue:  # breadth first; the list grows while it is walked
            for ch, child in self.goto[state].items():
                queue.append(child)
                fallback = self.fail[state]
                while fallback and ch not in self.goto[fallback]:
                    fallback = self.fail[fallback]
                target = self.goto[fallback].get(ch, 0)
                self.fail[child] = target if target != child else 0
                self.out[child] = self.out[child] + self.out[self.fail[child]]

    def find(self, text: str) -> set[int]:
        found: set[int] = set()
        if not self.goto[0]:
            return found
        goto, fail, out = self.goto, self.fail, self.out
        state = 0
        for ch in text:
            while state and ch not in goto[state]:
                state = fail[state]
            state = goto[state].get(ch, 0)
            if out[state]:
                found.update(out[state])
        return found


# --- scanning -----------------------------------------------------------------------------------

def mask(match: str) -> str:
    """At most the first two characters (one for 3 to 5, none below), an ellipsis, the length."""
    shown = 2 if len(match) >= 6 else 1 if len(match) >= 3 else 0
    return f"{match[:shown]}…({len(match)})"


@dataclass(frozen=True)
class Finding:
    location: str
    line: int  # 0: the file or entry name matched
    source: str  # the list
    rule: int
    masked: str

    def __str__(self):
        where = self.location if self.line == 0 else f"{self.location}:{self.line}"
        return f"{where}: {self.source} rule {self.rule}: {self.masked}"


def is_binary(data: bytes) -> bool:
    return b"\0" in data[:BINARY_PROBE]


def decode(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


class Scanner:
    def __init__(self, rules: list[Rule], allowed: set[str] = frozenset()):
        self.rules = rules
        self.allowed = set(allowed)
        self.always = [r for r, rule in enumerate(rules) if rule.literal is None]
        exact = [r for r, rule in enumerate(rules) if rule.literal is not None and not rule.folded]
        self.exact_rules = exact
        self.exact = AhoCorasick([rules[r].literal for r in exact])
        # Case-insensitive literals are searched with re itself: str.casefold() does not follow
        # re's IGNORECASE equivalences (e.g. dotted and dotless i).
        self.folded = {r: re.compile(re.escape(rule.literal), re.IGNORECASE)
                       for r, rule in enumerate(rules) if rule.literal is not None and rule.folded}

    def candidates(self, text: str) -> list[int]:
        found = set(self.always)
        found.update(self.exact_rules[i] for i in self.exact.find(text))
        found.update(r for r, literal in self.folded.items() if literal.search(text))
        return sorted(found)

    def first_reported(self, rule: Rule, line: str) -> re.Match | None:
        """The first match that is not allowlisted; after an allowlisted match the search goes on
        at the next position, so an overlapping match of the same rule is found too."""
        position = 0
        while position <= len(line):
            match = rule.pattern.search(line, position)
            if match is None:
                return None
            if match.group() not in self.allowed:
                return match
            position = match.start() + 1
        return None

    def scan_lines(self, location: str, text: str, numbered_lines, name=False) -> list[Finding]:
        """numbered_lines: (line number, line) pairs taken from `text`."""
        candidates = self.candidates(text)
        findings = []
        if not candidates:
            return findings
        for number, line in numbered_lines:
            for r in candidates:
                rule = self.rules[r]
                if rule.literal is not None:
                    folded = self.folded.get(r)
                    if folded is not None:
                        if not folded.search(line):
                            continue
                    elif rule.literal not in line:
                        continue
                match = self.first_reported(rule, line)
                if match is not None:
                    findings.append(Finding(location, 0 if name else number, rule.source,
                                            rule.number, mask(match.group())))
        return findings

    def scan_name(self, name: str, redacted: str, findings: list[Finding], prefix="") -> str:
        """Reports a matching name as prefix + redacted; returns the location to use for it."""
        hits = self.scan_lines(prefix + redacted, name, [(0, name)], name=True)
        findings += hits
        return prefix + (redacted if hits else name)

    def scan_text(self, location: str, data: bytes) -> list[Finding]:
        if is_binary(data):
            return []
        text = decode(data)
        return self.scan_lines(location, text, enumerate(text.split("\n"), 1))

    def scan_archive(self, location: str, data: bytes) -> list[Finding]:
        findings: list[Finding] = []
        try:
            archive = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            return self.scan_text(location, data)
        with archive:
            for index, info in enumerate(archive.infolist(), 1):
                where = self.scan_name(info.filename, f"<name of entry #{index}>", findings,
                                       prefix=location + "!")
                if not info.is_dir():
                    findings += self.scan_text(where, archive.read(info))
        return findings

    def scan_blob(self, location: str, path: str, data: bytes) -> list[Finding]:
        if path.lower().endswith(ARCHIVE_SUFFIXES):
            return self.scan_archive(location, data)
        return self.scan_text(location, data)


# --- git ----------------------------------------------------------------------------------------

def git(root: Path, *args: str, stdin: bytes = b"") -> bytes:
    try:
        result = subprocess.run(["git", "-c", "core.quotePath=false", *args], cwd=root,
                                input=stdin, capture_output=True, check=False)
    except OSError as e:
        raise ConfigError(f"cannot run git: {type(e).__name__}") from None
    if result.returncode != 0:
        # git's message may quote a file name (which may be a customer value): not echoed
        raise ConfigError(f"git {args[0]} failed with exit status {result.returncode}")
    return result.stdout


def split_z(output: bytes) -> list[str]:
    return [decode(part) for part in output.split(b"\0") if part]


def repository_files(root: Path) -> list[str]:
    """git ls-files --cached --others --exclude-standard; outside a checkout every file except
    .git/, target/ and the local lists (like the guard test)."""
    try:
        return sorted(set(split_z(git(root, "ls-files", "-z", "--cached", "--others",
                                      "--exclude-standard"))))
    except ConfigError:
        files = []
        for path in root.rglob("*"):
            relative = path.relative_to(root)
            if path.is_file() and relative.parts[0] not in SKIPPED_DIRECTORIES \
                    and str(relative) not in LOCAL_FILES:
                files.append(relative.as_posix())
        return sorted(files)


def check_all(root: Path, scanner: Scanner) -> list[Finding]:
    findings: list[Finding] = []
    for position, name in enumerate(repository_files(root), 1):
        location = scanner.scan_name(name, f"<name of scanned file #{position}>", findings)
        path = root / name
        if path.is_file():
            findings += scanner.scan_blob(location, name, path.read_bytes())
    return findings


DIFF_FILTER = "--diff-filter=ACMRT"
HUNK_RE = re.compile(rb"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")
ZERO_SHA_RE = re.compile(r"^0+$")


def unquote_path(raw: bytes) -> str:
    """A path of a "+++ " line: git appends a TAB to a path with a space (for GNU patch) and
    quotes a path with a TAB, a quote, a backslash or a control character."""
    if raw.endswith(b"\t"):
        raw = raw[:-1]
    if raw.startswith(b'"') and raw.endswith(b'"'):
        raw = codecs.escape_decode(raw[1:-1])[0]
    return decode(raw)


def diff_added_lines(root: Path, diff: list[str]) -> dict[str, list[tuple[int, str]]]:
    """Path -> (line number in the new version, line) of every added or changed line."""
    patch = git(root, "diff", *diff, "-U0", "--no-renames", DIFF_FILTER, "--no-color",
                "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/")
    added: dict[str, list[tuple[int, str]]] = {}
    current = None
    number = 0
    in_header = False
    for raw in patch.split(b"\n"):
        if raw.startswith(b"diff --git "):
            current, in_header = None, True
        elif in_header and raw.startswith(b"+++ "):
            target = unquote_path(raw[4:])
            current = target[2:] if target.startswith("b/") else None
        elif raw.startswith(b"@@"):
            in_header = False
            match = HUNK_RE.match(raw)
            number = int(match.group(1)) if match else 0
        elif not in_header and current is not None and raw.startswith(b"+"):
            added.setdefault(current, []).append((number, decode(raw[1:])))
            number += 1
    return added


def check_diff(root: Path, scanner: Scanner, diff: list[str], blob_prefix: str,
               location_prefix: str, redacted_name: str) -> list[Finding]:
    """The added and changed lines of `git diff <diff>`, changed archives and binary files
    completely (blob `<blob_prefix><path>`) and the changed paths. Fails closed: a text file
    with added lines whose lines could not be read is an error, not a silent pass."""
    paths = split_z(git(root, "diff", *diff, "--name-only", "-z", "--no-renames", DIFF_FILTER))
    binary = set()
    with_additions = set()
    numstat = git(root, "diff", *diff, "--numstat", "-z", "--no-renames", DIFF_FILTER)
    for record in split_z(numstat):
        added, _deleted, path = record.split("\t", 2)
        if added == "-":
            binary.add(path)
        elif added != "0":
            with_additions.add(path)
    added_lines = diff_added_lines(root, diff)
    findings: list[Finding] = []
    for position, path in enumerate(paths, 1):
        location = scanner.scan_name(path, redacted_name.format(position), findings,
                                     prefix=location_prefix)
        if path in binary or path.lower().endswith(ARCHIVE_SUFFIXES):
            blob = git(root, "cat-file", "blob", blob_prefix + path)
            findings += scanner.scan_blob(location, path, blob)
        elif path in added_lines:
            lines = added_lines[path]
            findings += scanner.scan_lines(location, "\n".join(line for _, line in lines), lines)
        elif path in with_additions:
            raise ConfigError(f"could not read the added lines of changed file #{position} "
                              f"({location_prefix or 'index'}); nothing was checked")
    return findings


def check_staged(root: Path, scanner: Scanner) -> list[Finding]:
    return check_diff(root, scanner, ["--cached"], ":", "", "<name of staged file #{}>")


def object_exists(root: Path, sha: str) -> bool:
    try:
        git(root, "cat-file", "-e", sha + "^{commit}")
        return True
    except ConfigError:
        return False


def existing_commits(root: Path, shas) -> list[str]:
    """The given objects that exist locally and peel to a commit (one git call)."""
    shas = sorted(set(shas))
    if not shas:
        return []
    output = git(root, "cat-file", "--batch-check=%(objectname) %(objecttype)",
                 stdin="".join(f"{sha}^{{commit}}\n" for sha in shas).encode())
    return [line.split()[0] for line in decode(output).splitlines() if line.endswith(" commit")]


SHA_RE = re.compile(r"^[0-9a-f]{40,64}$")
LS_REMOTE_TIMEOUT_VARIABLE = "INUBIT_MCP_LSREMOTE_TIMEOUT"
LS_REMOTE_TIMEOUT = 30.0  # seconds


def ls_remote_environment(base, ssh_command_configured: bool) -> dict:
    """The environment of the extra ls-remote: never prompt (terminal, credential manager, ssh);
    cached credentials and keys still work. A configured ssh command is left alone."""
    env = dict(base)
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GCM_INTERACTIVE"] = "never"
    if not ssh_command_configured and not env.get("GIT_SSH_COMMAND") and not env.get("GIT_SSH"):
        env["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes"
    return env


def ls_remote_timeout(environ) -> float:
    configured = environ.get(LS_REMOTE_TIMEOUT_VARIABLE, "").strip()
    if not configured:
        return LS_REMOTE_TIMEOUT
    try:
        value = float(configured)
    except ValueError:
        value = 0
    if value <= 0:
        raise ConfigError(f"{LS_REMOTE_TIMEOUT_VARIABLE} must be a positive number of seconds")
    return value


def ls_remote(root: Path, target: str, timeout: float) -> bytes | None:
    """`git ls-remote <target>`, non-interactive and bounded; None on failure or timeout (the
    whole process group is killed then, so a hanging ssh does not survive)."""
    try:
        ssh_configured = bool(git(root, "config", "--get", "core.sshCommand").strip())
    except ConfigError:
        ssh_configured = False
    posix = os.name != "nt"
    try:
        process = subprocess.Popen(["git", "ls-remote", target], cwd=root,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, start_new_session=posix,
                                   env=ls_remote_environment(os.environ, ssh_configured))
    except OSError:
        return None
    try:
        output, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        if posix:
            with contextlib.suppress(OSError):
                os.killpg(process.pid, signal.SIGKILL)
        process.kill()
        process.communicate()
        return None
    return output if process.returncode == 0 else None


def target_tips(root: Path, remote: str | None, url: str | None, environ, err) -> list[str]:
    """The commits the push target already has: the refs that `git ls-remote` lists for the URL
    git pushes to (else the remote name), as far as they exist locally. Remote-tracking refs are
    not used: they may belong to another remote or be stale (the branch deleted on the server).
    If the refs cannot be listed (error, timeout), nothing is excluded here (more is checked,
    never less)."""
    timeout = ls_remote_timeout(environ)
    target = url or remote
    if not target:
        return []
    if target.startswith("-"):
        raise ConfigError("the remote must not start with '-'")
    output = ls_remote(root, target, timeout)
    if output is None:
        print("check-identifiers: warning: cannot list the refs of the push target (it failed or "
              f"did not answer within {timeout:g} s); checking every commit that is not reachable "
              "from the old value of the pushed ref", file=err)
        return []
    return existing_commits(root, (line.split("\t")[0] for line in decode(output).splitlines()
                                   if "\t" in line))


@dataclass(frozen=True)
class PushUpdate:
    position: int  # the line among the non-empty lines of the hook input, from 1
    local_ref: str
    local_sha: str
    remote_ref: str
    remote_sha: str

    @property
    def deletion(self) -> bool:
        return bool(ZERO_SHA_RE.match(self.local_sha))


def parse_push_lines(push_lines) -> list[PushUpdate]:
    updates = []
    for raw in push_lines:
        if not raw.strip():
            continue
        parts = raw.split()
        if len(parts) != 4:
            raise ConfigError("unexpected input: expected "
                              "'<local ref> <local sha> <remote ref> <remote sha>' per line")
        position = len(updates) + 1
        if not (SHA_RE.match(parts[1]) and SHA_RE.match(parts[3])):
            raise ConfigError(f"unexpected input: line {position} has a sha that is no "
                              "hexadecimal object name")
        updates.append(PushUpdate(position, *parts))
    return updates


def commits_to_push(root: Path, updates: list[PushUpdate], tips: list[str]) -> list[str]:
    """The commits of the updates that the target does not have (not reachable from its tips or
    from the old value of the pushed ref), oldest first, without duplicates."""
    commits: list[str] = []
    seen: set[str] = set()
    for update in updates:
        exclude = list(tips)
        if not ZERO_SHA_RE.match(update.remote_sha):
            exclude += existing_commits(root, [update.remote_sha])
        revisions = "\n".join([update.local_sha, "--not", *exclude]) + "\n"
        output = git(root, "rev-list", "--reverse", "--stdin", stdin=revisions.encode())
        for commit in decode(output).split():
            if commit not in seen:
                seen.add(commit)
                commits.append(commit)
    return commits


def check_tag_objects(root: Path, scanner: Scanner, sha: str) -> list[Finding]:
    """An annotated tag (and a tag of a tag) is published with its name, tagger and message."""
    findings: list[Finding] = []
    for _ in range(16):  # tags of tags, bounded
        if decode(git(root, "cat-file", "-t", sha)).strip() != "tag":
            break
        content = git(root, "cat-file", "tag", sha)
        findings += scanner.scan_text(f"{sha[:12]}:<tag object>", content)
        first = decode(content).split("\n", 1)[0].split()
        if len(first) != 2 or first[0] != "object":
            break
        sha = first[1]
    return findings


def check_push(root: Path, scanner: Scanner, push_lines, remote=None, url=None,
               environ=None, err=sys.stderr) -> list[Finding]:
    """Everything a push publishes that the target does not have yet: the names of the pushed
    refs, its annotated tag objects, and the diffs, file names, messages and author/committer
    identities of its commits. Deletions publish nothing and are skipped (also their names)."""
    findings: list[Finding] = []
    updates = [update for update in parse_push_lines(push_lines) if not update.deletion]
    if not updates:
        return findings
    for update in updates:
        for name in dict.fromkeys([update.local_ref, update.remote_ref]):
            scanner.scan_name(name, f"<name of pushed ref #{update.position}>", findings)
    for update in updates:
        findings += check_tag_objects(root, scanner, update.local_sha)
    tips = target_tips(root, remote, url, environ or {}, err)
    commits = commits_to_push(root, updates, tips)
    if not commits:
        return findings
    empty_tree = decode(git(root, "hash-object", "-t", "tree", "--stdin")).strip()
    for commit in commits:
        parents = decode(git(root, "rev-list", "--parents", "-n", "1", commit)).split()[1:]
        short = commit[:12]
        findings += check_diff(root, scanner, [parents[0] if parents else empty_tree, commit],
                               commit + ":", short + ":", "<name of changed file #{}>")
        message = git(root, "log", "-1", "--format=%B", commit)
        findings += scanner.scan_text(f"{short}:<commit message>", message)
        identity = git(root, "log", "-1", "--format=%an%n%ae%n%cn%n%ce", commit)
        findings += scanner.scan_text(f"{short}:<author and committer>", identity)
    return findings


def repository_root(directory: Path) -> Path:
    try:
        return Path(decode(git(directory, "rev-parse", "--show-toplevel")).strip())
    except ConfigError:
        return directory


# --- command line -------------------------------------------------------------------------------

def run(argv, environ, home: Path, windows: bool, out, err, stdin=None) -> int:
    parser = argparse.ArgumentParser(
        prog="check-identifiers.py",
        description="Check staged changes or the whole repository for customer identifiers.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true",
                      help="check the added/changed lines of the index (default)")
    mode.add_argument("--push", action="store_true",
                      help="check the commits being pushed (pre-push hook input on stdin)")
    mode.add_argument("--all", action="store_true",
                      help="check all tracked and untracked, not ignored files")
    mode.add_argument("--self-test", action="store_true", help="run the built-in checks")
    parser.add_argument("--remote", help="--push: the remote name the hook gets as $1")
    parser.add_argument("--url", help="--push: the URL the hook gets as $2 (git pushes there)")
    parser.add_argument("--strict", action="store_true",
                        help="fail (exit 2) when no identifier list is found")
    parser.add_argument("--repo", type=Path, default=Path.cwd(),
                        help="repository to check (default: the current directory)")
    try:
        with contextlib.redirect_stderr(err):
            args = parser.parse_args(argv)
    except SystemExit as e:
        return 0 if e.code == 0 else 2
    if args.self_test:
        return self_test()
    try:
        root = repository_root(args.repo.resolve())
        lists = locate_lists(environ, root, default_directory(environ, home, windows))
        if not lists.any_list():
            print("check-identifiers: warning: no identifier list found, nothing checked "
                  f"(looked for {lists.describe_missing()}; see docs/release-checks.md)",
                  file=err)
            return 2 if args.strict else 0
        scanner = lists.load()
        if args.all:
            findings, what = check_all(root, scanner), "all files"
        elif args.push:
            push_lines = (stdin if stdin is not None else sys.stdin).read().splitlines()
            findings = check_push(root, scanner, push_lines, args.remote, args.url, environ, err)
            what = "commits to push"
        else:
            findings, what = check_staged(root, scanner), "staged changes"
    except ConfigError as e:
        print(f"check-identifiers: error: {e}", file=err)
        return 2
    for finding in findings:
        print(finding, file=out)
    out.flush()  # the findings first, then the summary on stderr
    if findings:
        print(f"check-identifiers: {len(findings)} finding(s) ({what}); "
              "see docs/release-checks.md", file=err)
        return 1
    return 0


def main(argv=None) -> int:
    return run(argv, os.environ, Path.home(), os.name == "nt", sys.stdout, sys.stderr)


# --- self-test (fictitious values only) ----------------------------------------------------

SELF_DENYLIST = "# fictitious customer\n(?i)foocorp\nbar-\\d+\\.internal\n"
SELF_NEUTRALIZE = "# fictitious\nglobex\\.invalid\tinubit-dev-1.example.test\n\\bFC-\tGRP-\n"
SELF_SYNTHETIC = ("# Generated by tools/synthesize_names.py (fictitious)\n"
                  "(?<![\\w-])Acme\\ Billing\\-Import(?![\\w-])\tWorkflow-0001\n"
                  "(?<=[\\\">])Initech(?=[\\\"<])\tGRP-01\n"
                  "(?<=[\\\">/])Orders(?=[\\\"</?\\r\\n]|\\Z)\tService-01\n")
VALUES = re.compile(r"(?i)foocorp|bar-\d+\.internal|globex\.invalid|\bFC-|Acme Billing|"
                    r"Initech|Orders")


@contextlib.contextmanager
def isolated_git():
    """Temp repositories must not see the user's git configuration (hooks, signing, ...)."""
    saved = {key: os.environ.get(key) for key in
             ("GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL",
              "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL")}
    os.environ.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                      GIT_AUTHOR_NAME="Self Test", GIT_AUTHOR_EMAIL="self-test@example.test",
                      GIT_COMMITTER_NAME="Self Test",
                      GIT_COMMITTER_EMAIL="self-test@example.test")
    try:
        yield
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def write_self_lists(home: Path) -> Path:
    defaults = home / ".config" / DEFAULT_DIRECTORY_NAME
    defaults.mkdir(parents=True)
    (defaults / "denylist.txt").write_text(SELF_DENYLIST, encoding="utf-8")
    (defaults / "neutralize-map").write_text(SELF_NEUTRALIZE, encoding="utf-8")
    (defaults / "synthetic-map").write_text(SELF_SYNTHETIC, encoding="utf-8")
    return defaults


def run_cli(repo: Path, home: Path, *args, environ=None, stdin=""):
    out, err = io.StringIO(), io.StringIO()
    code = run([*args, "--repo", str(repo)], environ or {}, home, False, out, err,
               io.StringIO(stdin))
    return code, out.getvalue(), err.getvalue()


def git_in(repo: Path, *args, env=None) -> str:
    result = subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True,
                            env={**os.environ, **(env or {})})
    return decode(result.stdout).strip()


PARITY_VECTORS = (Path(__file__).resolve().parent.parent / "src" / "test" / "resources"
                  / "identifier-check" / "parity-vectors.jsonl")


def prefilter_mismatches(check) -> int:
    """Prefilter + rule versus the rule alone: the shared parity vectors (also checked by the
    Java tests), fixed cases and a deterministic fuzz. Returns the number of mismatches."""
    import json
    import random

    def scanned(regex: str, text: str) -> tuple[bool, bool] | None:
        try:
            [rule] = parse_list("neutralize-map", regex + "\tX\n")
        except ConfigError:
            return None
        brute = bool(rule.pattern.search(text))
        lines = list(enumerate(text.split("\n"), 1))
        hits = Scanner([rule]).scan_lines("t", text, lines)
        whole = any(rule.pattern.search(line) for _, line in lines)
        return brute, bool(hits) == whole

    mismatches = 0
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", FutureWarning)
        in_checkout = (PARITY_VECTORS.parents[4] / "pom.xml").is_file()
        check("shared parity vectors present in a repository checkout",
              PARITY_VECTORS.is_file() or not in_checkout)
        if PARITY_VECTORS.is_file():
            vectors = [json.loads(line) for line in
                       PARITY_VECTORS.read_text(encoding="utf-8").splitlines()
                       if line.strip() and not line.startswith("#")]
            wrong = 0
            for regex, text, expected in vectors:
                result = scanned(regex, text)
                if result is None or result[0] != expected or not result[1]:
                    wrong += 1
            check(f"shared parity vectors ({len(vectors)})", wrong == 0 and len(vectors) > 50)
            mismatches += wrong
        fixed = [("(?i)İx", "ix"), ("(?i)ıx", "Ix"), ("(?i)ix", "İx"), ("(?i)ßx", "ẞx"),
                 ("a(?#c(|)b", "ab"), ("a(?#c)*b", "ab"), ("x{y}z", "x{y}z"), ("a{1}b{x", "ab{x")]
        for regex, text in fixed:
            result = scanned(regex, text)
            mismatches += 0 if result is None or result[1] else 1
        fragments = ["a", "b", "A", "ı", "İ", "i", "I", "ß", "ẞ", "{", "}", "{2}", "{,2}", "{1,}",
                     "x{y}", "(?#c(|)", "(?#z)", "*", "+", "?", "|", "(a)", "(?:b|c)", "[ab]",
                     "[{]", "\\{", "\\.", ".", "-", "\\w", "\\b", "(?i:a)", "(?-i:b)", "ab"]
        alphabet = "aAbBcxyıIİißẞ{}.,|()#- 2"
        rng = random.Random(4711)
        for _ in range(3000):
            regex = ("(?i)" if rng.random() < 0.4 else "") + "".join(
                rng.choice(fragments) for _ in range(rng.randint(1, 6)))
            for _ in range(8):
                text = "".join(rng.choice(alphabet) for _ in range(rng.randint(0, 9)))
                literal = required_literal(regex)[0]
                if literal and rng.random() < 0.5:
                    text = text[:3] + (literal.swapcase() if rng.random() < 0.5 else literal) \
                        + text[3:]
                result = scanned(regex, text)
                mismatches += 0 if result is None or result[1] else 1
    return mismatches


def special_names_and_fail_closed(check):
    """B1: staged paths with spaces, renames, non-ASCII, quotes and TABs; fail closed."""
    with isolated_git(), tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        write_self_lists(base / "home")
        repo = base / "names"
        repo.mkdir()
        git_in(repo, "init", "-q")
        (repo / "leak.txt").write_text("clean\nFooCorp\n", encoding="utf-8")
        git_in(repo, "add", "-A")
        git_in(repo, "commit", "-q", "-m", "base")
        git_in(repo, "mv", "leak.txt", "leak renamed.txt")
        names = ["my file.txt", "über.txt", 'q"uote.txt', "back\\slash.txt"]
        if os.name != "nt":
            names.append("tab\tname.txt")
        for name in names:
            (repo / name).write_text("x\n<g>Initech</g>\n", encoding="utf-8")
        git_in(repo, "add", "-A")
        code, out, _ = run_cli(repo, base / "home", "--staged")
        expected = {"leak renamed.txt:2: denylist rule 1: Fo…(7)"} | {
            f"{name}:2: synthetic-map rule 2: In…(7)" for name in names}
        check("staged paths with space, rename, non-ASCII, quote, backslash, TAB",
              code == 1 and set(out.splitlines()) == expected)

        saved = globals()["diff_added_lines"]
        globals()["diff_added_lines"] = lambda *_args: {}
        try:
            code, out, err = run_cli(repo, base / "home", "--staged")
        finally:
            globals()["diff_added_lines"] = saved
        check("added lines that cannot be read: exit 2 (fail closed)",
              code == 2 and "could not read the added lines" in err and "Initech" not in err)
        message = ""
        try:
            git(repo, "cat-file", "blob", ":secret-name.txt")
        except ConfigError as e:
            message = str(e)
        check("git errors are not echoed (file names)", message and "secret" not in message)


def ls_remote_limits(check):
    """P2: the extra ls-remote is non-interactive, bounded in time and killed on timeout."""
    env = ls_remote_environment({"PATH": "/bin"}, ssh_command_configured=False)
    check("ls-remote: no prompts, ssh in batch mode",
          env["GIT_TERMINAL_PROMPT"] == "0" and env["GCM_INTERACTIVE"] == "never"
          and env["GIT_SSH_COMMAND"] == "ssh -o BatchMode=yes")
    env = ls_remote_environment({"GIT_SSH_COMMAND": "my-ssh"}, ssh_command_configured=False)
    check("ls-remote: a configured ssh command is kept", env["GIT_SSH_COMMAND"] == "my-ssh")
    env = ls_remote_environment({}, ssh_command_configured=True)
    check("ls-remote: core.sshCommand is kept", "GIT_SSH_COMMAND" not in env)
    if os.name == "nt":
        return
    with isolated_git(), tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        home = base / "home"
        write_self_lists(home)
        repo = base / "slow"
        repo.mkdir()
        git_in(repo, "init", "-q")
        (repo / "r.txt").write_text("bar-7.internal\n", encoding="utf-8")
        git_in(repo, "add", "r.txt")
        git_in(repo, "commit", "-q", "-m", "root")
        r = git_in(repo, "rev-parse", "HEAD")
        fake_ssh = base / "fake-ssh"
        pid_file = base / "ssh.pid"
        env_file = base / "ssh.env"
        fake_ssh.write_text(f"#!/bin/sh\necho $$ > '{pid_file}'\nenv > '{env_file}'\n"
                            "exec sleep 30\n", encoding="utf-8")
        fake_ssh.chmod(0o755)
        saved = os.environ.get("GIT_SSH_COMMAND")
        os.environ["GIT_SSH_COMMAND"] = str(fake_ssh)  # no network: git runs the fake instead
        try:
            started = time.monotonic()
            code, out, err = run_cli(repo, home, "--push", "--remote", "slow", "--url",
                                     "ssh://git.example.test/slow.git",
                                     environ={"INUBIT_MCP_LSREMOTE_TIMEOUT": "1"},
                                     stdin=f"refs/heads/main {r} refs/heads/main {'0' * len(r)}\n")
            elapsed = time.monotonic() - started
        finally:
            if saved is None:
                os.environ.pop("GIT_SSH_COMMAND", None)
            else:
                os.environ["GIT_SSH_COMMAND"] = saved
        check("ls-remote: a slow remote times out, warning, more is checked",
              code == 1 and "cannot list the refs" in err and elapsed < 15
              and out == f"{r[:12]}:r.txt:1: denylist rule 2: ba…(14)\n")
        ssh_env = env_file.read_text(encoding="utf-8") if env_file.is_file() else ""
        check("ls-remote: runs without terminal prompts",
              "GIT_TERMINAL_PROMPT=0" in ssh_env and "GCM_INTERACTIVE=never" in ssh_env)
        alive = True
        if pid_file.is_file():
            pid = int(pid_file.read_text().strip())
            for _ in range(50):
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    alive = False
                    break
                time.sleep(0.1)
        check("ls-remote: the process group is killed on timeout", not alive)
        code, _, err = run_cli(repo, home, "--push", "--remote", "x", "--url", "x",
                               environ={"INUBIT_MCP_LSREMOTE_TIMEOUT": "soon"},
                               stdin=f"refs/heads/main {r} refs/heads/main {'0' * len(r)}\n")
        check("ls-remote: an invalid timeout is exit 2",
              code == 2 and "INUBIT_MCP_LSREMOTE_TIMEOUT" in err)


def push_mode(check):
    """M1: --push checks every commit being pushed, not the working tree."""
    with isolated_git(), tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        home = base / "home"
        write_self_lists(home)
        remote = base / "remote.git"
        repo = base / "push"
        repo.mkdir()
        git_in(base, "init", "-q", "--bare", str(remote))
        git_in(repo, "init", "-q")
        git_in(repo, "checkout", "-q", "-b", "main")
        git_in(repo, "remote", "add", "origin", str(remote))

        def commit(message, **files):
            for name, content in files.items():
                path = repo / name
                if content is None:
                    git_in(repo, "rm", "-q", name)
                else:
                    path.write_bytes(content if isinstance(content, bytes)
                                     else content.encode("utf-8"))
                    git_in(repo, "add", name)
            git_in(repo, "commit", "-q", "-m", message)
            return git_in(repo, "rev-parse", "HEAD")

        a1 = commit("historical value", **{"a.txt": "FooCorp\n"})
        a2 = commit("removed", **{"a.txt": None, "clean.txt": "ok\n"})
        git_in(repo, "push", "-q", "origin", "main")
        b = commit("add", **{"b.txt": "x\n<g>Initech</g>\n"})
        c = commit("remove again", **{"b.txt": None})
        zero = "0" * len(c)
        target = ["--remote", "origin", "--url", str(remote)]  # what the hook passes ($1, $2)

        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/main {c} refs/heads/main {a2}\n")
        check("push: a value added and removed again is blocked; pushed history not rescanned",
              code == 1 and out == f"{b[:12]}:b.txt:2: synthetic-map rule 2: In…(7)\n"
              and a1[:12] not in out)
        code, out, _ = run_cli(repo, home, "--all")
        check("push: the working tree alone is clean", code == 0 and out == "")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"(delete) {zero} refs/heads/old {a2}\n")
        check("push: a branch deletion passes", code == 0 and out == "")
        code, out, _ = run_cli(repo, home, "--push", *target, stdin="")
        check("push: no refs, nothing to check", code == 0 and out == "")
        code, _, err = run_cli(repo, home, "--push", *target, stdin="garbage\n")
        check("push: unexpected input is exit 2", code == 2 and "unexpected input" in err)
        code, _, err = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/x --upload-pack=x refs/heads/x {zero}\n")
        check("push: a sha that is no hex object name is exit 2",
              code == 2 and "object name" in err and "upload" not in err)

        # P1: the names of pushed refs are published too
        git_in(repo, "tag", "foocorp-v1", a2)
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/tags/foocorp-v1 {a2} refs/tags/foocorp-v1 {zero}\n")
        check("push: a tag name is checked (redacted)",
              code == 1 and out == "<name of pushed ref #1>: denylist rule 1: fo…(7)\n")
        code, out, _ = run_cli(repo, home, "--push", *target, stdin=(
            f"refs/heads/main {a2} refs/heads/main {a2}\n"
            f"refs/heads/clean {a2} refs/heads/feature/bar-7.internal-import {zero}\n"))
        check("push: a branch name on the remote side is checked",
              code == 1 and out == "<name of pushed ref #2>: denylist rule 2: ba…(14)\n")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"(delete) {zero} refs/heads/foocorp-old {a2}\n")
        check("push: the name of a deleted ref is not published, not checked",
              code == 0 and out == "")

        git_in(repo, "checkout", "-q", "-b", "feature", a2)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("dir/model.xml", "<a>\n<host>globex.invalid</host>\n</a>\n")
        (repo / "fixture.zip").write_bytes(archive.getvalue())
        git_in(repo, "add", "fixture.zip")
        git_in(repo, "commit", "-q", "-m", "add fixture", "-m", "see /ibis/ws/Orders")
        d = git_in(repo, "rev-parse", "HEAD")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/feature {d} refs/heads/feature {zero}\n")
        check("push: new branch without remote counterpart, archive and commit message",
              code == 1 and out.splitlines() == [
                  f"{d[:12]}:fixture.zip!dir/model.xml:2: neutralize-map rule 1: gl…(14)",
                  f"{d[:12]}:<commit message>:3: synthetic-map rule 3: Or…(6)"])

        git_in(repo, "checkout", "-q", "main")
        git_in(repo, "merge", "-q", "--no-ff", "-m", "merge", "feature")
        m = git_in(repo, "rev-parse", "HEAD")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/main {m} refs/heads/main {a2}\n")
        check("push: merged commits are checked", code == 1 and f"{b[:12]}:b.txt:2" in out
              and f"{d[:12]}:fixture.zip" in out and f"{m[:12]}:fixture.zip" in out)

        # N1: only what the push target has is excluded, not what another remote has
        git_in(base, "init", "-q", "--bare", str(base / "priv.git"))
        git_in(repo, "remote", "add", "priv", str(base / "priv.git"))
        git_in(repo, "checkout", "-q", "-b", "privbranch", a2)
        v = commit("private", **{"v.txt": "bar-9.internal\n"})
        git_in(repo, "push", "-q", "priv", "privbranch")
        line = f"refs/heads/privbranch {v} refs/heads/privbranch {zero}\n"
        expected_v = f"{v[:12]}:v.txt:1: denylist rule 2: ba…(14)\n"
        code, out, _ = run_cli(repo, home, "--push", *target, stdin=line)
        check("push: a commit that only another remote has is checked",
              code == 1 and out == expected_v)
        code, out, _ = run_cli(repo, home, "--push", "--remote", "priv", "--url",
                               str(base / "priv.git"), stdin=line)
        check("push: a commit the push target has is not checked again", code == 0 and out == "")
        code, out, _ = run_cli(repo, home, "--push", "--remote", str(remote), "--url",
                               str(remote), stdin=line)
        check("push to a URL (no configured remote)", code == 1 and out == expected_v)

        git_in(repo, "push", "-q", "origin", "privbranch:gone")
        git_in(base, "--git-dir", str(remote), "update-ref", "-d", "refs/heads/gone")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/again {v} refs/heads/again {zero}\n")
        check("push: a stale tracking ref (branch deleted on the server) excludes nothing",
              code == 1 and out == expected_v
              and git_in(repo, "rev-parse", "refs/remotes/origin/gone") == v)
        code, out, err = run_cli(repo, home, "--push", "--remote", "missing", "--url",
                                 str(base / "missing.git"),
                                 stdin=f"refs/heads/x {a2} refs/heads/x {zero}\n")
        check("push: refs of the target unknown: warning, the whole history is checked",
              code == 1 and "cannot list the refs" in err
              and out == f"{a1[:12]}:a.txt:1: denylist rule 1: Fo…(7)\n")

        # N2: annotated tag objects and author/committer identities are published too
        git_in(repo, "tag", "-a", "v1", "-m", "release", "-m", "for globex.invalid", a2)
        tag = git_in(repo, "rev-parse", "refs/tags/v1")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/tags/v1 {tag} refs/tags/v1 {zero}\n")
        check("push: the message of an annotated tag is checked",
              code == 1 and out == f"{tag[:12]}:<tag object>:8: neutralize-map rule 1: gl…(14)\n")
        git_in(repo, "checkout", "-q", "-b", "ident", a2)
        (repo / "e.txt").write_text("ok\n", encoding="utf-8")
        git_in(repo, "add", "e.txt")
        git_in(repo, "commit", "-q", "-m", "identity",
               env={"GIT_AUTHOR_EMAIL": "dev@foocorp.example.test"})
        e = git_in(repo, "rev-parse", "HEAD")
        code, out, _ = run_cli(repo, home, "--push", *target,
                               stdin=f"refs/heads/ident {e} refs/heads/ident {zero}\n")
        check("push: author and committer name and e-mail are checked",
              code == 1 and out == f"{e[:12]}:<author and committer>:2: denylist rule 1: fo…(7)\n")

        root_repo = base / "root"
        root_repo.mkdir()
        git_in(root_repo, "init", "-q")
        (root_repo / "r.txt").write_text("bar-7.internal\n", encoding="utf-8")
        git_in(root_repo, "add", "r.txt")
        git_in(root_repo, "commit", "-q", "-m", "root")
        r = git_in(root_repo, "rev-parse", "HEAD")
        code, out, _ = run_cli(root_repo, home, "--push",
                               stdin=f"refs/heads/main {r} refs/heads/main {zero}\n")
        check("push: root commit (no remote, empty tree as base)",
              code == 1 and out == f"{r[:12]}:r.txt:1: denylist rule 2: ba…(14)\n")


def self_test() -> int:
    failures = 0

    def check(name, condition):
        nonlocal failures
        print(("ok   " if condition else "FAIL ") + name)
        if not condition:
            failures += 1

    def config_error(function, *args) -> str:
        try:
            function(*args)
        except ConfigError as e:
            return str(e)
        return ""

    # parsing
    rules = parse_list("synthetic-map", SELF_SYNTHETIC)
    check("map rules numbered with their lines",
          [(r.number, r.line) for r in rules] == [(1, 2), (2, 3), (3, 4)])
    message = config_error(parse_list, "neutralize-map", "a\tb\n# c\n\nno-tab-secret\n")
    check("missing TAB rejected by rule and line", "neutralize-map rule 2 (line 4)" in message
          and "TAB" in message and "secret" not in message)
    message = config_error(parse_list, "synthetic-map", "\n(unclosed-secret\tx\n")
    check("invalid map regex rejected by rule and line",
          "synthetic-map rule 1 (line 2)" in message and "secret" not in message)
    message = config_error(parse_list, "denylist", "ok\nsecret\\p{Alpha}\n")
    check("Java-only denylist syntax rejected", "denylist rule 2 (line 2)" in message
          and "secret" not in message)
    message = config_error(parse_list, "denylist", "[a-z&&[^secret]]\n")
    check("set intersection rejected", "denylist rule 1 (line 1)" in message)
    message = config_error(parse_list, "denylist", "ok\nsecret[\\b]\n")
    check("\\b inside a set rejected (backspace in Python, error in Java)",
          "denylist rule 2 (line 2)" in message and "secret" not in message)
    check("empty list rejected", "no rules" in config_error(parse_list, "denylist", "# c\n"))
    java = parse_list("denylist", "acme\\z\n\\bfoo$\n\\w\n")
    check("Java \\z and $ translated", bool(java[0].pattern.search("x acme"))
          and not java[0].pattern.search("acme x") and bool(java[1].pattern.search("foo\r")))
    check("denylist classes are ASCII like Java", not java[2].pattern.search("é"))
    check("allowlist values", parse_allowlist("# c\nInitech\n  Hooli \r\n\n") == {"Initech",
                                                                                   "Hooli"})

    # masking and the prefilter
    check("mask", [mask(v) for v in ("Acme Billing", "acme1", "abc", "ab")]
          == ["Ac…(12)", "a…(5)", "a…(3)", "…(2)"])
    literals = {regex: required_literal(regex) for regex in (
        "(?<![\\w-])Acme\\ Billing\\-Import(?![\\w-])", "(?i)FooCorp", "bar-\\d+\\.internal",
        "acme|globex", "colou?r", "[[]x]acme", "(?x)a b")}
    check("required literals", list(literals.values()) == [
        ("Acme Billing-Import", False), ("FooCorp", True), (".internal", False), (None, False),
        ("colo", False), ("x]acme", False), (None, False)])
    automaton = AhoCorasick(["he", "she", "hers", "xyz"])
    check("Aho-Corasick finds overlapping literals", automaton.find("ushers") == {0, 1, 2})
    check("comments and literal braces", [required_literal(r) for r in (
        "acme(?#x)globex", "x{y}zz", "ab{2}cd", "a{,3}bc", "x{}y")] == [
        (None, False), ("x{y}zz", False), ("cd", False), ("bc", False), ("x{}y", False)])
    prefilter_failures = prefilter_mismatches(check)
    check("prefilter equals brute force (fixed cases and fuzz)", prefilter_failures == 0)
    allow = Scanner(parse_list("synthetic-map", "Initech|nitech\\-Xy\tGRP-01\n"), {"Initech"})
    check("an allowlisted match does not hide an overlapping one",
          [str(f) for f in allow.scan_lines("t", "Initech-Xy", [(1, "Initech-Xy")])]
          == ["t:1: synthetic-map rule 1: ni…(9)"])

    # lookup order
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        root = base / "repo"
        defaults = base / "home" / ".config" / DEFAULT_DIRECTORY_NAME
        root.mkdir()
        defaults.mkdir(parents=True)
        entry = LISTS[2]
        check("nothing located", locate(entry, {}, root, defaults) is None)
        (defaults / "synthetic-map").write_text("a\tb\n", encoding="utf-8")
        check("default directory last", locate(entry, {}, root, defaults)
              == defaults / "synthetic-map")
        (root / ".synthetic-map").write_text("a\tb\n", encoding="utf-8")
        check("repository file before default", locate(entry, {}, root, defaults)
              == root / ".synthetic-map")
        env_file = base / "env-map"
        env_file.write_text("a\tb\n", encoding="utf-8")
        check("environment variable first",
              locate(entry, {entry[1]: str(env_file)}, root, defaults) == env_file)
        check("blank variable ignored",
              locate(entry, {entry[1]: " "}, root, defaults) == root / ".synthetic-map")
        message = config_error(locate, entry, {entry[1]: str(base / "missing")}, root, defaults)
        check("variable naming no file is an error", entry[1] in message)
        check("default directory per platform",
              default_directory({}, Path("/home/acme"), False)
              == Path("/home/acme/.config/inubit-mcp")
              and default_directory({"APPDATA": "C:/Roaming"}, Path("C:/Users/acme"), True)
              == Path("C:/Roaming/inubit-mcp"))

    # the command line in temporary repositories
    with isolated_git(), tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        home = base / "home"
        defaults = home / ".config" / DEFAULT_DIRECTORY_NAME
        defaults.mkdir(parents=True)
        (defaults / "denylist.txt").write_text(SELF_DENYLIST, encoding="utf-8")
        (defaults / "neutralize-map").write_text(SELF_NEUTRALIZE, encoding="utf-8")
        (defaults / "synthetic-map").write_text(SELF_SYNTHETIC, encoding="utf-8")
        repo = base / "repo"
        repo.mkdir()

        def sh(*args):
            subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)

        def cli(*args, environ=None, home_dir=home):
            return run_cli(repo, home_dir, *args, environ=environ)

        sh("init", "-q")
        (repo / ".gitignore").write_text("ignored.txt\n", encoding="utf-8")
        (repo / "old.txt").write_text("line one\nFooCorp was here before\n", encoding="utf-8")
        sh("add", "-A")
        code, out, _ = cli("--staged")
        check("initial commit: staged content checked",
              code == 1 and out == "old.txt:2: denylist rule 1: Fo…(7)\n")
        sh("commit", "-q", "-m", "base")

        code, out, err = cli("--staged")
        check("nothing staged: clean", code == 0 and out == "" and err == "")

        (repo / "old.txt").write_text("line one\nFooCorp was here before\nnew clean line\n"
                                      "<g>Initech</g> on line four\n", encoding="utf-8")
        (repo / "new.xml").write_text('<m name="Acme Billing-Import"/>\n', encoding="utf-8")
        (repo / "data_foocorp.txt").write_text("x\nGET /ibis/ws/Orders\n", encoding="utf-8")
        (repo / "ignored.txt").write_text("FooCorp\n", encoding="utf-8")
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("dir/model.xml", "<a>\n<host>globex.invalid</host>\n</a>\n")
            z.writestr("img.bin", b"\0FooCorp\0")
        (repo / "fixture.zip").write_bytes(archive.getvalue())
        sh("add", "old.txt", "new.xml", "data_foocorp.txt", "fixture.zip")
        (repo / "old.txt").write_text("unstaged bar-7.internal\n", encoding="utf-8")
        (repo / "untracked.txt").write_text("FC-Utils\n", encoding="utf-8")

        code, out, err = cli("--staged")
        expected = ["<name of staged file #1>: denylist rule 1: fo…(7)",
                    "<name of staged file #1>:2: synthetic-map rule 3: Or…(6)",
                    "fixture.zip!dir/model.xml:2: neutralize-map rule 1: gl…(14)",
                    "new.xml:1: synthetic-map rule 1: Ac…(19)",
                    "old.txt:4: synthetic-map rule 2: In…(7)"]
        check("staged: added lines, archives, redacted names; index, not working tree",
              code == 1 and out.splitlines() == expected)
        check("staged: no value in the output", not VALUES.search(out + err))

        code, out, _ = cli("--all")
        expected_all = ["<name of scanned file #2>: denylist rule 1: fo…(7)",
                        "<name of scanned file #2>:2: synthetic-map rule 3: Or…(6)",
                        "fixture.zip!dir/model.xml:2: neutralize-map rule 1: gl…(14)",
                        "new.xml:1: synthetic-map rule 1: Ac…(19)",
                        "old.txt:1: denylist rule 2: ba…(14)",
                        "untracked.txt:1: neutralize-map rule 2: F…(3)"]
        check("all: tracked and untracked files of the working tree, ignored ones skipped",
              code == 1 and out.splitlines() == expected_all)

        (defaults / "identifier-allowlist").write_text("Initech\n", encoding="utf-8")
        (repo / "old.txt").write_text("<g>Initech</g>\n", encoding="utf-8")
        sh("add", "old.txt")
        code, out, _ = cli("--staged")
        check("allowlisted value not reported", "rule 2: In…(7)" not in out and code == 1)

        sh("rm", "-q", "--cached", "data_foocorp.txt", "new.xml", "fixture.zip")
        code, out, _ = cli("--staged")
        check("clean staged change", code == 0 and out == "")

        env_map = base / "env-denylist"
        env_map.write_text("Initech<\n", encoding="utf-8")
        code, out, _ = cli("--staged", environ={"INUBIT_MCP_DENYLIST": str(env_map)})
        check("environment variable wins over the default directory",
              code == 1 and out == "old.txt:1: denylist rule 1: In…(8)\n")

        code, _, err = cli("--staged", environ={"INUBIT_MCP_DENYLIST": str(base / "nope")})
        check("missing file of a variable: exit 2", code == 2 and "INUBIT_MCP_DENYLIST" in err)
        (defaults / "synthetic-map").write_text("broken-secret\n", encoding="utf-8")
        code, _, err = cli("--all")
        check("invalid list: exit 2 without its text", code == 2
              and "synthetic-map rule 1 (line 1)" in err and "secret" not in err)

        empty_home = base / "empty-home"
        empty_home.mkdir()
        code, out, err = cli("--all", home_dir=empty_home)
        check("no list: warning, exit 0", code == 0 and out == "" and "no identifier list" in err
              and "synthetic-map" in err)
        code, _, _ = cli("--all", "--strict", home_dir=empty_home)
        check("no list with --strict: exit 2", code == 2)
        code, _, _ = cli("--bogus")
        check("usage error: exit 2", code == 2)

    special_names_and_fail_closed(check)
    push_mode(check)
    ls_remote_limits(check)

    print("self-test " + ("passed" if failures == 0 else f"FAILED ({failures})"))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
