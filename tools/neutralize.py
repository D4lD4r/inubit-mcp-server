#!/usr/bin/env python3
"""Replace customer values by neutral values in repository files (research D-11, tasks.md T038).

This script contains only the mechanism. The concrete customer -> neutral mapping lives in a local,
untracked file (it names the customer values, so it is never committed):

  1. the file named by the environment variable INUBIT_MCP_NEUTRALIZE_MAP, else
  2. .neutralize-map in the repository root (listed in .gitignore).

Map format (UTF-8): one rule per line, "<regex><TAB><replacement>"; lines starting with "#" and
empty lines are ignored. Rules are applied top to bottom; each rule is a Python regular expression
(case-sensitive unless it starts with "(?i)"), the replacement is literal text. Rules are numbered
from 1 in file order.

Processed: the given files and directories (recursively; .git/ and target/ are skipped). Text files
are rewritten in place. ZIP archives are re-packed with the text entries replaced; entry order,
names, timestamps, attributes and compression are kept, so the result is deterministic. Binary
files and binary ZIP entries are left alone. A file name that matches a rule is reported (rename it
by hand and update its references).

Idempotent: a second run changes nothing. A map whose output (file content or ZIP entry name) would
still match one of its rules is rejected, as is a ZIP whose renamed entries would collide. All files
are processed before anything is written: on any error no file is changed. ZIP entries larger than
--max-entry-bytes (default 64 MiB, uncompressed) are refused.

--map FILE uses another map in the same format, e.g. the local .synthetic-map written by
tools/synthesize_names.py.

The output names files, rule numbers and counts only, never a matched or mapped value.

Usage:
  neutralize.py [--dry-run] [--map FILE] [--max-entry-bytes N] PATH...
  neutralize.py --self-test
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys
import tempfile
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

ENV_MAP = "INUBIT_MCP_NEUTRALIZE_MAP"
LOCAL_MAP = ".neutralize-map"
REPO_ROOT = Path(__file__).resolve().parent.parent
SKIPPED_DIRECTORIES = {".git", "target"}
LOCAL_FILES = {LOCAL_MAP, ".denylist", ".synthetic-map", ".identifier-allowlist"}
BINARY_PROBE = 8000
MAX_ENTRY_BYTES = 64 * 1024 * 1024  # default cap for one ZIP entry (uncompressed)


@dataclass(frozen=True)
class Rule:
    number: int
    pattern: re.Pattern
    replacement: str


class MapError(Exception):
    pass


def locate_map(environ=os.environ, root: Path = REPO_ROOT) -> Path | None:
    configured = environ.get(ENV_MAP, "").strip()
    if configured and Path(configured).is_file():
        return Path(configured)
    local = root / LOCAL_MAP
    return local if local.is_file() else None


def parse_map(text: str) -> list[Rule]:
    rules: list[Rule] = []
    for raw in text.split("\n"):
        line = raw[:-1] if raw.endswith("\r") else raw
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        number = len(rules) + 1
        if "\t" not in line:
            raise MapError(f"rule #{number}: expected <regex><TAB><replacement>")
        regex, replacement = line.split("\t", 1)
        try:
            pattern = re.compile(regex)
        except re.error:
            # The rule text is a customer value: report its number only.
            raise MapError(f"rule #{number}: invalid regular expression") from None
        rules.append(Rule(number, pattern, replacement))
    if not rules:
        raise MapError("the map contains no rules")
    return rules


def is_binary(data: bytes) -> bool:
    return b"\0" in data[:BINARY_PROBE]


def apply_rules(text: str, rules: list[Rule], counts: Counter) -> str:
    for rule in rules:
        text, n = rule.pattern.subn(lambda _match, r=rule.replacement: r, text)
        if n:
            counts[rule.number] += n
    return text


def neutralize_text(data: bytes, rules: list[Rule], counts: Counter, where: str) -> bytes:
    # surrogateescape keeps bytes that are not UTF-8 unchanged.
    text = data.decode("utf-8", errors="surrogateescape")
    result = apply_rules(text, rules, counts)
    check: Counter = Counter()
    apply_rules(result, rules, check)
    if check:
        raise MapError(f"{where}: map is not idempotent (rules {sorted(check)})")
    return result.encode("utf-8", errors="surrogateescape")


def neutralize_zip(data: bytes, rules: list[Rule], counts: Counter, where: str,
                   max_entry: int = MAX_ENTRY_BYTES) -> bytes:
    source = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    names: set[str] = set()
    with zipfile.ZipFile(out, "w") as target:
        for index, info in enumerate(source.infolist(), 1):
            if info.file_size > max_entry:
                raise MapError(f"{where}: entry #{index} is larger than {max_entry} bytes")
            content = source.read(info)
            name = apply_rules(info.filename, rules, counts)
            check: Counter = Counter()
            apply_rules(name, rules, check)
            if check:
                raise MapError(f"{where}: entry name #{index}: map is not idempotent "
                               f"(rules {sorted(check)})")
            if name in names:
                raise MapError(f"{where}: duplicate entry name after replacement (entry #{index})")
            names.add(name)
            if not info.is_dir() and not is_binary(content):
                content = neutralize_text(content, rules, counts, f"{where}!{info.filename}")
            copy = zipfile.ZipInfo(name, date_time=info.date_time)
            copy.compress_type = info.compress_type
            copy.external_attr = info.external_attr
            copy.internal_attr = info.internal_attr
            copy.create_system = info.create_system
            copy.comment = info.comment
            copy.extra = info.extra
            target.writestr(copy, content)
        target.comment = source.comment
    return out.getvalue()


def iter_files(paths: list[Path]):
    for path in paths:
        if path.is_dir():
            for child in sorted(path.rglob("*")):
                relative = child.relative_to(path).parts
                if any(part in SKIPPED_DIRECTORIES for part in relative):
                    continue
                if child.is_file() and child.name not in LOCAL_FILES:
                    yield child
        elif path.is_file():
            if path.name not in LOCAL_FILES:
                yield path
        else:
            raise MapError(f"{path}: no such file or directory")


def run(paths: list[Path], rules: list[Rule], dry_run: bool, out=sys.stdout,
        max_entry: int = MAX_ENTRY_BYTES) -> Counter:
    """Processes all files; prints file names and counts. Returns the counts per rule number.

    All files are processed first; nothing is written unless every file succeeded."""
    total: Counter = Counter()
    changed_files = 0
    pending: list[tuple[Path, bytes]] = []
    for file in iter_files(paths):
        counts: Counter = Counter()
        data = file.read_bytes()
        name_hits = [rule.number for rule in rules if rule.pattern.search(file.name)]
        if name_hits:
            print(f"{file}: file name matches rule(s) {name_hits}; rename it by hand", file=out)
        if file.suffix.lower() == ".zip":
            result = neutralize_zip(data, rules, counts, str(file), max_entry)
            if not counts:
                result = data  # unchanged archives stay byte-identical
        elif is_binary(data):
            continue
        else:
            result = neutralize_text(data, rules, counts, str(file))
        if result != data:
            changed_files += 1
            summary = ", ".join(f"#{k}: {v}" for k, v in sorted(counts.items()))
            print(f"{file}: {sum(counts.values())} replacement(s) ({summary})", file=out)
            pending.append((file, result))
        total.update(counts)
    if not dry_run:
        for file, result in pending:
            file.write_bytes(result)
    mode = "would change" if dry_run else "changed"
    print(f"{mode} {changed_files} file(s), {sum(total.values())} replacement(s)", file=out)
    for number, count in sorted(total.items()):
        print(f"  rule #{number}: {count}", file=out)
    return total


# --- self-test (fictitious values only) -----------------------------------------------------

SELF_TEST_MAP = (
    "# fictitious customer\n"
    "foo\\.corp\\.invalid\tinubit-dev-1.example.test\n"
    "(?i)foocorp\tacme\n"
    "\\bFC-\tGRP-\n"
    "\n"
    "\\bFC\\b\tOWNERS\n"
)


def self_test() -> int:
    failures = 0

    def check(name, condition):
        nonlocal failures
        if not condition:
            failures += 1
            print(f"FAIL {name}")
        else:
            print(f"ok   {name}")

    rules = parse_map(SELF_TEST_MAP)
    check("map parsed, comments and blanks ignored", [r.number for r in rules] == [1, 2, 3, 4])

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        text = root / "docs" / "a.md"
        text.parent.mkdir()
        text.write_text("Host foo.corp.invalid of FooCorp, group FC, diagram FC-Utils.\n"
                        "FCX stays, xFC stays.\n", encoding="utf-8")
        binary = root / "blob.bin"
        binary.write_bytes(b"\0foocorp\0")
        named = root / "list_foocorp.xml"
        named.write_text("<a/>\n", encoding="utf-8")
        latin = root / "latin.txt"
        latin.write_bytes("FooCorp caf\xe9\n".encode("latin-1"))
        zipped = root / "sample.zip"
        with zipfile.ZipFile(zipped, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(zipfile.ZipInfo("model/m.xml", (2026, 1, 2, 3, 4, 6)),
                             "<owner>FC</owner><host>foo.corp.invalid</host>\n",
                             compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr(zipfile.ZipInfo("img.bin", (2026, 1, 2, 3, 4, 6)), b"\0FC\0")
        clean_zip = root / "clean.zip"
        with zipfile.ZipFile(clean_zip, "w") as archive:
            archive.writestr("x.txt", "nothing to do\n")
        clean_zip_bytes = clean_zip.read_bytes()
        target = root / "target" / "ignored.txt"
        target.parent.mkdir()
        target.write_text("FooCorp\n", encoding="utf-8")
        before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}

        dry = io.StringIO()
        counts = run([root], rules, dry_run=True, out=dry)
        after_dry = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
        check("dry run counts", counts == Counter({1: 2, 2: 2, 3: 1, 4: 2}))
        check("dry run writes nothing", before == after_dry)

        out = io.StringIO()
        run([root], rules, dry_run=False, out=out)
        report = out.getvalue() + dry.getvalue()
        check("text replaced",
              text.read_text(encoding="utf-8")
              == "Host inubit-dev-1.example.test of acme, group OWNERS, diagram GRP-Utils.\n"
                 "FCX stays, xFC stays.\n")
        check("non-UTF-8 bytes kept", latin.read_bytes() == "acme caf\xe9\n".encode("latin-1"))
        check("binary file untouched", binary.read_bytes() == b"\0foocorp\0")
        check("target/ skipped", target.read_text(encoding="utf-8") == "FooCorp\n")
        check("clean zip byte-identical", clean_zip.read_bytes() == clean_zip_bytes)
        with zipfile.ZipFile(zipped) as archive:
            infos = archive.infolist()
            check("zip entry order and names kept",
                  [i.filename for i in infos] == ["model/m.xml", "img.bin"])
            check("zip timestamps kept",
                  all(i.date_time == (2026, 1, 2, 3, 4, 6) for i in infos))
            check("zip compression kept", infos[0].compress_type == zipfile.ZIP_DEFLATED)
            check("zip text entry replaced",
                  archive.read("model/m.xml").decode()
                  == "<owner>OWNERS</owner><host>inubit-dev-1.example.test</host>\n")
            check("zip binary entry untouched", archive.read("img.bin") == b"\0FC\0")
        check("matching file name reported",
              "list_foocorp.xml: file name matches rule(s) [2]" in report)
        # File names are printed on purpose; nothing else may name a matched or mapped value.
        without_names = report.replace(str(named), "<file>")
        check("output names no values",
              not re.search(r"(?i)foocorp|foo\.corp|\bFC\b|acme|OWNERS|GRP-|example\.test",
                            without_names))

        first = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
        again = io.StringIO()
        counts = run([root], rules, dry_run=False, out=again)
        second = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
        check("idempotent", not counts and first == second)

        with zipfile.ZipFile(zipped, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(zipfile.ZipInfo("m.xml", (2026, 1, 2, 3, 4, 6)), "FooCorp\n",
                             compress_type=zipfile.ZIP_DEFLATED)
        original = zipped.read_bytes()
        repacked = [neutralize_zip(original, rules, Counter(), "z") for _ in range(2)]
        check("zip re-pack deterministic", repacked[0] == repacked[1])

    try:
        parse_map("(unclosed-secret\tx\n")
        check("invalid rule rejected", False)
    except MapError as e:
        check("invalid rule rejected by number only",
              "rule #1" in str(e) and "unclosed-secret" not in str(e))
    try:
        parse_map("no-tab-secret\n")
        check("rule without tab rejected", False)
    except MapError as e:
        check("rule without tab rejected", "rule #1" in str(e) and "secret" not in str(e))
    try:
        neutralize_text(b"ab", parse_map("a\tb\nb\ta\n"), Counter(), "loop.txt")
        check("non-idempotent map rejected", False)
    except MapError as e:
        check("non-idempotent map rejected", "not idempotent" in str(e))

    # ZIP entry names: the renamed name must not match a rule again; renamed entries stay unique.
    looping = parse_map("a\tb\nb\ta\n")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        archive_path = root / "names.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("ab.txt", "x\n")
        try:
            neutralize_zip(archive_path.read_bytes(), looping, Counter(), "names.zip")
            check("non-idempotent entry name rejected", False)
        except MapError as e:
            check("non-idempotent entry name rejected", "entry name" in str(e)
                  and "not idempotent" in str(e))
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("FooCorp.txt", "x\n")
            archive.writestr("acme.txt", "y\n")
        try:
            neutralize_zip(archive_path.read_bytes(), rules, Counter(), "names.zip")
            check("duplicate entry names rejected", False)
        except MapError as e:
            check("duplicate entry names rejected", "duplicate entry" in str(e))

    # All or nothing: a failure in a later file leaves every earlier file unchanged.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        first = root / "a.txt"
        first.write_text("FooCorp\n", encoding="utf-8")
        second = root / "b.txt"
        second.write_text("q\n", encoding="utf-8")
        mixed = parse_map("(?i)foocorp\tzzz\nq\tr\nr\tq\n")
        try:
            run([first, second], mixed, dry_run=False, out=io.StringIO())
            check("failing run rejected", False)
        except MapError:
            check("failing run writes nothing",
                  first.read_text(encoding="utf-8") == "FooCorp\n"
                  and second.read_text(encoding="utf-8") == "q\n")

    # Size cap per ZIP entry.
    with tempfile.TemporaryDirectory() as tmp:
        archive_path = Path(tmp) / "big.zip"
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("big.txt", "x" * 2048)
        try:
            neutralize_zip(archive_path.read_bytes(), rules, Counter(), "big.zip", max_entry=1024)
            check("oversized entry rejected", False)
        except MapError as e:
            check("oversized entry rejected", "larger than" in str(e))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        env_map = root / "env.map"
        env_map.write_text("a\tb\n", encoding="utf-8")
        (root / LOCAL_MAP).write_text("c\td\n", encoding="utf-8")
        check("environment map wins", locate_map({ENV_MAP: str(env_map)}, root) == env_map)
        check("local map as fallback", locate_map({}, root) == root / LOCAL_MAP)
        check("missing env file falls back",
              locate_map({ENV_MAP: str(root / "missing")}, root) == root / LOCAL_MAP)
        (root / LOCAL_MAP).unlink()
        check("no map found", locate_map({}, root) is None)

    print("self-test " + ("passed" if failures == 0 else f"FAILED ({failures})"))
    return 0 if failures == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Replace customer values by neutral values (map: $" + ENV_MAP
                    + " or " + LOCAL_MAP + " in the repository root).")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks")
    parser.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    parser.add_argument("--map", type=Path,
                        help=f"map file to use instead of ${ENV_MAP} / {LOCAL_MAP} "
                             "(e.g. a .synthetic-map from tools/synthesize_names.py)")
    parser.add_argument("--max-entry-bytes", type=int, default=MAX_ENTRY_BYTES,
                        help="refuse ZIP entries larger than this (uncompressed)")
    parser.add_argument("paths", nargs="*", type=Path, help="files or directories to process")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.paths:
        parser.error("no paths given")
    map_file = args.map if args.map else locate_map()
    if args.map and not args.map.is_file():
        print(f"error: map file not found: {args.map}", file=sys.stderr)
        return 2
    if map_file is None:
        print(f"no map: set {ENV_MAP} or create {LOCAL_MAP} in the repository root",
              file=sys.stderr)
        return 2
    try:
        rules = parse_map(map_file.read_text(encoding="utf-8"))
        run(args.paths, rules, args.dry_run, max_entry=args.max_entry_bytes)
    except MapError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
