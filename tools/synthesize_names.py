#!/usr/bin/env python3
"""Replace INUBIT business object names in test data by synthetic names (feature 002, US4 / N1).

This script contains only the mechanism. It COLLECTS the object names that occur in recorded
fixtures and writes a deterministic, local mapping file (default .synthetic-map in the repository
root, listed in .gitignore; it names the original values, so it is never committed). The mapping is
applied with tools/neutralize.py:

  tools/synthesize_names.py collect --keep OWNERS --out .synthetic-map src/test/resources/fixtures
  tools/neutralize.py --map .synthetic-map src/test

Collected (fixture formats of INUBIT 8.1):
  * XML: ModelList/Model name + group, Model/Node name, versionHistory WorkflowGroup/Workflow/Module
    Name, export WorkflowGroupName / WorkflowName / ModuleName / OriginalModuleName, ModuleGroupName
    (unless it is a plugin name, i.e. equal to a PluginName), Tag
  * JSON log rows: workflowName, moduleName / inputModule / outputModule ("Name(id)" -> Name),
    objectName "Name[id] ((owner))", connector name of webservice and key manager rows, lower-case
    module names in fileName
  * StartCLI "ps -csv" output: WORKFLOW and MODULE columns
  * web service names in URLs ".../ibis/ws/<Name>"
  * --extra FILE: further names, one "<category><TAB><name>" per line (e.g. names that only occur in
    test code); categories: group, workflow, module, service, tag

Names that are a single word (letters only) are replaced only as a complete quoted value or element
text ("Name", >Name<) and get no lower-case variant (letters and digits only), so ordinary words
are never touched; web service names are also replaced as a URL path segment (/Name followed by ",
/, ?, < or the end of the line).

Synthetic names: group GRP-01.., workflow Workflow-0001.., module Module-0001.., service
Service-01.., tag TAG-01... Within a category the numbers follow the case-insensitive order of the
names, so sorting by name keeps its order. Every name gets its own number (unique), the same input
always gives the same output. Lower-case spellings (e.g. in log file names) map to lower-case
synthetic names.

--through MAP: the names were collected from files before neutralize.py ran with MAP; the mapping
then also covers the neutralized spelling of each name (and numbers by that spelling). A spelling
that two different names share is left out and reported by count (fix those by hand).

The output names categories and counts only, never an object name.

Usage:
  synthesize_names.py collect [--keep NAME]... [--through MAP] [--extra FILE] [--out FILE] PATH...
  synthesize_names.py --self-test
"""

from __future__ import annotations

import argparse
import html
import io
import json
import re
import sys
import tempfile
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import neutralize  # noqa: E402  (same directory; mechanism for parsing and applying maps)

LOCAL_SYNTHETIC_MAP = ".synthetic-map"
CATEGORIES = ("group", "workflow", "module", "service", "tag")
FORMATS = {"group": ("GRP-", 2), "workflow": ("Workflow-", 4), "module": ("Module-", 4),
           "service": ("Service-", 2), "tag": ("TAG-", 2)}
SYNTHETIC_RE = re.compile(
    r"^(?:GRP-\d{2,}|Workflow-\d{4,}|Module-\d{4,}|Service-\d{2,}|TAG-\d{2,})$")
ALWAYS_KEPT = {"", "System"}
PLACEHOLDER_RE = re.compile(r"^(?:user\d+|<[^>]*>|\[[^\]]*\])$")
ID_SUFFIX_RE = re.compile(r"^(.*?)\(\d+\)$")
PS_HEADER = "UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG"
URL_SERVICE_RE = re.compile(r"/ibis/ws/([A-Za-z0-9_.-]+)")
# A single word (no separator or digit) may also be an ordinary word or protocol name: such names
# are replaced only as a complete quoted value or element text.
SINGLE_WORD_RE = re.compile(r"^[A-Za-z0-9]+$")
ATTR_RE = re.compile(r'([\w:]+)="([^"]*)"')
XML_ELEMENT_RULES = (
    (re.compile(r"<(?:[\w-]+:)?Model\b([^>]*)>"), {"name": "workflow", "group": "group"}),
    (re.compile(r"<(?:[\w-]+:)?Node\b([^>]*)>"), {"name": "module"}),
    (re.compile(r"<WorkflowGroup\b([^>]*)>"), {"Name": "group"}),
    (re.compile(r"<Workflow\b([^>]*)>"), {"Name": "workflow"}),
    (re.compile(r"<Module\b([^>]*)>"), {"Name": "module"}),
)
XML_TEXT_RULES = (
    ("WorkflowGroupName", "group"), ("WorkflowName", "workflow"), ("ModuleName", "module"),
    ("OriginalModuleName", "module"), ("Tag", "tag"),
)


class Names:
    """Collected names per category, plus lower-case spellings found in log file names."""

    def __init__(self):
        self.by_category: dict[str, set[str]] = defaultdict(set)
        self.module_groups: set[str] = set()
        self.plugins: set[str] = set()
        self.lower_modules: set[str] = set()

    def add(self, category: str, value: str | None):
        value = html.unescape((value or "").strip())
        if value:
            self.by_category[category].add(value)


def collect_xml(text: str, names: Names):
    for pattern, attributes in XML_ELEMENT_RULES:
        for match in pattern.finditer(text):
            attrs = dict(ATTR_RE.findall(match.group(1)))
            for attribute, category in attributes.items():
                names.add(category, attrs.get(attribute))
    for element, category in XML_TEXT_RULES:
        for match in re.finditer(rf"<{element}>([^<]*)</{element}>", text):
            names.add(category, match.group(1))
    for match in re.finditer(r"<ModuleGroupName>([^<]*)</ModuleGroupName>", text):
        names.module_groups.add(html.unescape(match.group(1).strip()))
    for match in re.finditer(r"<PluginName>([^<]*)</PluginName>", text):
        names.plugins.add(html.unescape(match.group(1).strip()))


def strip_id(value: str) -> str:
    match = ID_SUFFIX_RE.match(value.strip())
    return match.group(1) if match else value.strip()


def collect_json(node, names: Names):
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, str):
                if key == "workflowName":
                    names.add("workflow", value)
                elif key in ("moduleName", "inputModule", "outputModule"):
                    names.add("module", strip_id(value))
                elif key == "objectName":
                    match = re.match(r"^(.*?)\[\d+\] \(\(.*\)\)$", value)
                    if match:
                        names.add("workflow", match.group(1))
                elif key == "name" and ("webserviceStatus" in node or "propertyName" in node):
                    names.add("module", value)
                elif key == "fileName":
                    match = re.match(r"^(.+?)\(\d+\)@", value)
                    if match:
                        names.lower_modules.add(match.group(1))
            else:
                collect_json(value, names)
    elif isinstance(node, list):
        for value in node:
            collect_json(value, names)


def collect_ps(text: str, names: Names):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.strip() == PS_HEADER:
            for row in lines[index + 1:]:
                cells = row.split(",")
                if len(cells) < 9:
                    break
                names.add("workflow", cells[5])
                names.add("module", strip_id(cells[6]))


def collect_text(text: str, names: Names):
    stripped = text.lstrip("﻿ \t\r\n")
    if stripped.startswith("<"):
        collect_xml(text, names)
    elif stripped.startswith(("{", "[")):
        try:
            collect_json(json.loads(text), names)
        except ValueError:
            pass
    if PS_HEADER in text:
        collect_ps(text, names)
    for match in URL_SERVICE_RE.finditer(text):
        names.add("service", match.group(1))


def collect_paths(paths: list[Path], names: Names):
    for file in neutralize.iter_files(paths):
        data = file.read_bytes()
        if file.suffix.lower() == ".zip":
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for info in archive.infolist():
                    content = archive.read(info)
                    if not info.is_dir() and not neutralize.is_binary(content):
                        collect_text(content.decode("utf-8", errors="replace"), names)
        elif not neutralize.is_binary(data):
            collect_text(data.decode("utf-8", errors="replace"), names)


def read_extra(path: Path, names: Names):
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        category, _, value = raw.partition("\t")
        if category not in CATEGORIES or not value:
            raise ValueError(f"{path}: line {number}: expected <category><TAB><name>")
        names.add(category, value)


def build_mapping(names: Names, keep=(), through=None) -> tuple[dict[str, str], Counter]:
    """Returns {spelling: synthetic} and statistics (counts only)."""
    through = through or (lambda value: value)
    stats: Counter = Counter()
    kept = {k for k in keep} | ALWAYS_KEPT
    for group in names.module_groups - names.plugins:
        names.add("group", group)
    stats["module groups kept as plugin names"] = len(names.module_groups & names.plugins)

    assigned: dict[str, str] = {}  # original name -> category
    for category in CATEGORIES:
        for value in names.by_category.get(category, ()):
            if value in kept or through(value) in kept or PLACEHOLDER_RE.match(value) \
                    or SYNTHETIC_RE.match(value) or SYNTHETIC_RE.match(through(value)):
                continue
            if value in assigned:
                if assigned[value] != category:
                    stats["names in several categories (first kept)"] += 1
                continue
            assigned[value] = category

    known_lower = {value.lower() for value in assigned}
    for value in sorted(names.lower_modules):
        if value not in known_lower and through(value).lower() not in known_lower \
                and not SYNTHETIC_RE.match(value):
            assigned[value] = "module"

    targets: dict[str, str] = {}
    for category in CATEGORIES:
        members = sorted((v for v, c in assigned.items() if c == category),
                         key=lambda v: (through(v).lower(), through(v), v))
        prefix, width = FORMATS[category]
        width = max(width, len(str(len(members))))
        for number, value in enumerate(members, 1):
            targets[value] = f"{prefix}{number:0{width}d}"
        stats[category] = len(members)

    spellings: dict[str, set[str]] = defaultdict(set)
    for value, target in targets.items():
        variants = {value, through(value)}
        variants |= {html.escape(v, quote=True) for v in list(variants)}
        for variant in variants:
            spellings[variant].add(target)
            if variant.lower() != variant and not SINGLE_WORD_RE.match(variant):
                spellings[variant.lower()].add(target.lower())
    mapping: dict[str, str] = {}
    for spelling, candidates in spellings.items():
        if len(candidates) == 1:
            mapping[spelling] = next(iter(candidates))
        else:
            stats["ambiguous spellings left out"] += 1
    return mapping, stats


def render_map(mapping: dict[str, str]) -> str:
    lines = ["# Generated by tools/synthesize_names.py: original object name -> synthetic name.",
             "# LOCAL ONLY, never commit (the patterns are customer values)."]
    for spelling in sorted(mapping, key=lambda s: (-len(s), s)):
        if spelling == mapping[spelling]:
            continue
        if SINGLE_WORD_RE.match(spelling) and mapping[spelling].lower().startswith("service-"):
            # web service endpoint names also occur as a path segment (.../ibis/ws/<name>)
            pattern = rf"(?<=[\">/]){re.escape(spelling)}(?=[\"</?\r\n]|\Z)"
        elif SINGLE_WORD_RE.match(spelling):
            pattern = rf"(?<=[\">]){re.escape(spelling)}(?=[\"<])"
        else:
            pattern = rf"(?<![\w-]){re.escape(spelling)}(?![\w-])"
        lines.append(pattern + "\t" + mapping[spelling])
    return "\n".join(lines) + "\n"


def through_function(map_path: Path | None):
    if map_path is None:
        return None
    rules = neutralize.parse_map(map_path.read_text(encoding="utf-8"))
    return lambda value: neutralize.apply_rules(value, rules, Counter())


def collect_command(args) -> int:
    names = Names()
    collect_paths(args.paths, names)
    if args.extra:
        read_extra(args.extra, names)
    mapping, stats = build_mapping(names, keep=args.keep, through=through_function(args.through))
    text = render_map(mapping)
    neutralize.parse_map(text)  # the output must be a valid map
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    for key, value in sorted(stats.items()):
        print(f"{key}: {value}", file=sys.stderr)
    print(f"rules: {len(text.splitlines()) - 2}", file=sys.stderr)
    return 0


# --- self-test (fictitious values only) -----------------------------------------------------

def self_test() -> int:
    failures = 0

    def check(name, condition):
        nonlocal failures
        print(("ok   " if condition else "FAIL ") + name)
        if not condition:
            failures += 1

    model_list = ('<?xml version="1.0"?><ns4:ModelList xmlns:ns4="m">'
                  '<ns4:Model name="ZZ-Billing" type="technical" group="Team-B"/>'
                  '<ns4:Model name="aa-Import" type="technical" group="Team-A"/>'
                  '<ns4:Model name="Org &amp; Co" type="bpd" group="OWN"/>'
                  '<ns4:Model name="Workflow-0007" type="technical" group="Team-A"/>'
                  '</ns4:ModelList>')
    by_name = ('<?xml version="1.0"?><ns4:Model name="ZZ-Billing" type="technical" group="Team-B">'
               '<ns4:Node name="Map-Order" type="twXSLTConverter" id="1"/></ns4:Model>')
    modules = ('<?xml version="1.0"?><IBISWorkflow><Modules><ModuleGroup>'
               '<ModuleGroupName>XSLT Converter</ModuleGroupName><Module>'
               '<ModuleName>Map-Order</ModuleName>'
               '<OriginalModuleName>Map-Order</OriginalModuleName>'
               '<PluginName>XSLT Converter</PluginName><WorkflowName>ZZ-Billing</WorkflowName>'
               '</Module></ModuleGroup><ModuleGroup><ModuleGroupName>Custom Group</ModuleGroupName>'
               '</ModuleGroup></Modules></IBISWorkflow>')
    history = ('<?xml version="1.0"?><VersionInformation><Workflows>'
               '<WorkflowGroup Name="Team-B"><Workflow Name="ZZ-Billing" Type="technical">'
               '<Tags><Tag>REL-9</Tag></Tags></Workflow></WorkflowGroup></Workflows>'
               '<Modules><Module Name="Map-Order" Type="technical"/></Modules>'
               '</VersionInformation>')
    logs = json.dumps({"queueLog": {"row": [
        {"workflowName": "ZZ-Billing", "moduleName": "Throw-Err(42)", "inputModule": "System",
         "fileName": "throw-err(42)@4711@abc", "objectName": "ZZ-Billing[3] ((OWN))",
         "owner": "OWN", "user": "user1", "message": "<message 1>"},
        {"name": "Ws-In", "webserviceStatus": "ok", "type": "Web Services Connector",
         "URL": "http://h.example.test:8000/ibis/ws/PriceService"},
        {"name": "Key-Conn", "propertyName": "Mime.Sign.Keystore", "type": "AS2 Connector"}]}})
    ps = ("Password: \n" + PS_HEADER + "\n"
          "OWN,1,normal,Error,2026-01-01T00:00:00,aa-Import,Throw-Err(42),ip-192-0-2-1,\n"
          "Total: 1\n")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "rest").mkdir()
        (root / "cli").mkdir()
        (root / "rest" / "models.xml").write_text(model_list, encoding="utf-8")
        (root / "rest" / "byName.xml").write_text(by_name, encoding="utf-8")
        (root / "rest" / "log.json").write_text(logs, encoding="utf-8")
        (root / "cli" / "ps.stdout").write_text(ps, encoding="utf-8")
        with zipfile.ZipFile(root / "cli" / "export.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("module/module.xml", modules)
            archive.writestr("versionHistory.xml", history)
        extra = root / "extra.txt"
        extra.write_text("# test-only names\nworkflow\tTest-Only-Flow\n", encoding="utf-8")

        def run_collect(through=None):
            names = Names()
            collect_paths([root / "rest", root / "cli"], names)
            read_extra(extra, names)
            return build_mapping(names, keep=["OWN"], through=through)

        mapping, stats = run_collect()
        check("groups numbered in name order", mapping.get("Custom Group") == "GRP-01"
              and mapping.get("Team-A") == "GRP-02" and mapping.get("Team-B") == "GRP-03")
        check("custom module group collected, plugin group kept",
              "Custom Group" in mapping and "XSLT Converter" not in mapping)
        check("workflows unique and in case-insensitive order",
              [mapping.get(n) for n in ("aa-Import", "Org & Co", "Test-Only-Flow", "ZZ-Billing")]
              == ["Workflow-0001", "Workflow-0002", "Workflow-0003", "Workflow-0004"])
        check("xml-escaped spelling covered", mapping.get("Org &amp; Co") == "Workflow-0002")
        check("modules from Node, export, logs and ps",
              mapping.get("Map-Order") == "Module-0002"
              and mapping.get("Throw-Err") == "Module-0003")
        check("lower-case spelling of log file names", mapping.get("throw-err") == "module-0003")
        check("service from URL and tag", mapping.get("PriceService") == "Service-01"
              and mapping.get("REL-9") == "TAG-01")
        check("connector names of webservice and key manager rows",
              "Ws-In" in mapping and "Key-Conn" in mapping)
        check("kept and placeholder values not mapped",
              not {"OWN", "System", "user1", "<message 1>", "Workflow-0007"} & set(mapping))
        check("statistics are counts only",
              stats["workflow"] == 4 and all(isinstance(v, int) for v in stats.values()))

        again, _ = run_collect()
        check("deterministic", render_map(mapping) == render_map(again))

        rules_text = "ZZ-\tYY-\n"
        through_map = root / "through.map"
        through_map.write_text(rules_text, encoding="utf-8")
        mapped, _ = run_collect(through_function(through_map))
        check("neutralized spelling covered", mapped.get("YY-Billing") == mapped.get("ZZ-Billing"))

        names = Names()
        names.add("workflow", "Ab-1")
        names.add("workflow", "Ab-2")
        clash, clash_stats = build_mapping(names, through=lambda v: "Ab-X")
        check("ambiguous neutralized spelling left out",
              "Ab-X" not in clash and "ab-x" not in clash
              and clash_stats["ambiguous spellings left out"] == 2
              and clash["Ab-1"] != clash["Ab-2"])

        # Applying the map with neutralize.py: consistent, bounded by name boundaries, idempotent.
        rules = neutralize.parse_map(render_map(mapping))
        sample = ('flow ZZ-Billing, ZZ-Billing-Copy, module Throw-Err(42), '
                  'file throw-err(42)@1, group Team-A\n')
        out = neutralize.neutralize_text(sample.encode(), rules, Counter(), "sample").decode()
        check("applied consistently",
              out == "flow Workflow-0004, ZZ-Billing-Copy, module Module-0003(42), "
                     "file module-0003(42)@1, group GRP-02\n")
        check("applying twice changes nothing",
              neutralize.neutralize_text(out.encode(), rules, Counter(), "s").decode() == out)
        rendered = render_map(mapping)
        check("map is marked local", "never commit" in rendered)
        urls = ('"URL": "http://h.example.test:8000/ibis/ws/PriceService", '
                'http://h/ibis/ws/PriceService?wsdl /ibis/ws/PriceService/op '
                '/ibis/ws/PriceService\n<u>PriceService</u> PriceServiceX /ibis/ws/PriceServices\n')
        out = neutralize.neutralize_text(urls.encode(), rules, Counter(), "urls").decode()
        check("web service names replaced in URL paths",
              out == '"URL": "http://h.example.test:8000/ibis/ws/Service-01", '
                     'http://h/ibis/ws/Service-01?wsdl /ibis/ws/Service-01/op '
                     '/ibis/ws/Service-01\n<u>Service-01</u> PriceServiceX /ibis/ws/PriceServices\n')

        # Single-word names (e.g. a diagram group called like a protocol) are only replaced as a
        # complete quoted value or element text, never inside paths or prose, and get no
        # lower-case variant.
        names = Names()
        names.add("group", "REST")
        names.add("group", "AS2")
        names.add("group", "Team-A")
        weak, _ = build_mapping(names)
        weak_rules = neutralize.parse_map(render_map(weak))
        text = ('<m group="REST"/><g>REST</g> "REST" /ibis/rest/log REST call rest '
                '"AS2 Connector" as2 <g>AS2</g> Team-A team-a\n')
        out = neutralize.neutralize_text(text.encode(), weak_rules, Counter(), "w").decode()
        check("single-word names only as whole quoted values",
              out == '<m group="GRP-02"/><g>GRP-02</g> "GRP-02" /ibis/rest/log REST call rest '
                     '"AS2 Connector" as2 <g>GRP-01</g> GRP-03 grp-03\n')

    print("self-test " + ("passed" if failures == 0 else f"FAILED ({failures})"))
    return 0 if failures == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks")
    sub = parser.add_subparsers(dest="command")
    collect = sub.add_parser("collect", help="collect names and write the mapping")
    collect.add_argument("--keep", action="append", default=[],
                         help="name to keep (e.g. the inventory owner); repeatable")
    collect.add_argument("--through", type=Path,
                         help="neutralize map that was applied to the files after recording")
    collect.add_argument("--extra", type=Path, help="further names: <category><TAB><name>")
    collect.add_argument("--out", type=Path, help=f"mapping file (e.g. {LOCAL_SYNTHETIC_MAP})")
    collect.add_argument("paths", nargs="+", type=Path, help="fixture files or directories")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.command != "collect":
        parser.error("use 'collect' or --self-test")
    if args.out and args.out.resolve().parent == REPO_ROOT \
            and args.out.name != LOCAL_SYNTHETIC_MAP:
        parser.error(f"in the repository root only {LOCAL_SYNTHETIC_MAP} (git-ignored) is allowed")
    return collect_command(args)


if __name__ == "__main__":
    sys.exit(main())
