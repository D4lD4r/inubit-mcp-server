#!/usr/bin/env python3
"""Anonymize recorded INUBIT fixtures (research R-18, tasks.md T005/T006).

Reads one recording (REST body, CLI stdout/stderr, ...) and writes an anonymized copy:

* hostnames                      -> inubit.example.test
* IPv4 addresses                 -> 192.0.2.<n>  (AWS-style node names ip-a-b-c-d -> ip-192-0-2-<n>)
* usernames                      -> user<n>       (also e-mail addresses -> user<n>@example.test)
* free-text messages in log rows -> <message n>, keeping exception class names and INUBIT error
                                    codes (the CODE in "@Start@CODE@@@...@End@")
* X.509 subjects in log rows     -> CN=<subject n>
* comments in XML exports        -> [message n] (Comment, CheckinComment, UserComment, Description)
* StartCLI output (text format)  -> ps -csv TAG values -> tag-<n>; lines of the summary block after
                                    "EXECUTION ERROR"/"CONNECTION ERROR" -> <message n>, except
                                    known INUBIT system messages (KNOWN_CLI_MESSAGES)

XML documents can be trimmed before anonymization (--xml-limit Workflow=3 keeps the first three
Workflow elements, --xml-drop WorkflowModule removes those subtrees). ZIP archives are rewritten
with only the entries named by --zip-keep, each trimmed and anonymized.

Usernames are taken from the INUBIT_*_USERNAME environment variables, from --user, from user
fields of the recording (user, CheckinUser, objectName "<login> [GROUP]", ...) and from the
"<GROUP>_login" pattern for the kept groups (e.g. OWNERS_jdoe), and from hyphenated object names
ending in a lowercase login (XYZ-Calc-jdoe). The user-group names to keep have no default:
give them with --keep or in ANONYMIZE_KEEP (comma-separated); they stay.

The same original value always gets the same placeholder within one run; --state FILE keeps the
mapping across runs. The state file contains the ORIGINAL values: keep it outside the repository
and delete it after recording.

Usage:
  anonymize.py [--state FILE] [--user NAME]... [--keep NAME]... [--host NAME]...
               [--format auto|json|text] [--xml-limit NAME=N]... [--xml-drop NAME]...
               [INPUT [OUTPUT]]
  anonymize.py --zip-keep ENTRY... [--xml-limit ...] [--xml-drop ...] INPUT.zip OUTPUT.zip
  anonymize.py --verify [--secrets-stdin] FILE...
                                    # exit 1 if a file (or ZIP entry) still contains an
                                    # identifying value or one of the secrets read from stdin
  anonymize.py --self-test

Values found by --verify are never printed, only the rule and the line number.
Standard library only.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

HOST_PLACEHOLDER = "inubit.example.test"
EMAIL_DOMAIN = "example.test"
IP_PREFIX = "192.0.2."

# Top-level domains / private suffixes that identify a hostname. Java package names such as
# java.net.ConnectException are excluded by the look-ahead in HOST_RE (no further ".Word").
HOST_SUFFIXES = (
    "cloud", "com", "net", "org", "de", "eu", "io", "info", "biz", "at", "ch", "nl", "uk",
    "internal", "int", "local", "localdomain", "lan", "corp", "intra", "intranet", "test",
)
# Public hosts that appear in XML namespaces, schemas or documentation links.
PUBLIC_HOSTS = (
    "example.test", "example.com", "example.org", "example.net",
    "w3.org", "xmlsoap.org", "oasis-open.org", "openxmlformats.org", "xml.org",
    "json-schema.org", "apache.org", "sun.com", "oracle.com", "java.com", "microsoft.com",
    "inubit.com", "virtimo.de", "virtimo.net", "virtimo.com",
    # JDK vendors (system/info ServerJDKVendor)
    "amazon.com", "eclipse.org", "adoptium.net", "azul.com", "redhat.com", "openjdk.org",
)
KEEP_IPS = ("127.0.0.1", "0.0.0.0")
KEEP_ENV = "ANONYMIZE_KEEP"
RESERVED_NAMES = {"root", "system", "admin", "anonymous", "null", "none", "true", "false"}

# JSON keys / XML element or attribute names whose value is a user or user-group name.
USER_KEYS = {
    "user", "username", "userName", "login", "loginName", "owner", "checkinUser", "CheckinUser",
    "UserOrUserGroupName", "createdBy", "modifiedBy", "lockedBy", "UID", "uid",
    "ExportUser", "CheckoutUser",
}
# Keys inside log rows whose value is free text (business payload) -> <message n>.
MESSAGE_KEYS = {
    "message", "userDefined1", "userDefined2", "userDefined3", "userDefined4", "userDefined5",
    "description", "comment", "CheckinComment", "UserComment",
}
SUBJECT_KEYS = {"subject", "issuer"}
# XML elements (any namespace prefix) whose text is free text -> [message n].
XML_TEXT_RE = re.compile(
    r"<((?:[\w-]+:)?(?:Comment|CheckinComment|UserComment|Description))>(.*?)</\1>", re.DOTALL)
LOG_ROW_CONTAINER = "row"
PS_CSV_HEADER = "UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG"
CLI_SUMMARY_HEADERS = ("EXECUTION ERROR", "CONNECTION ERROR")
# INUBIT system messages that StartCLI prints in its summary block; they carry no business data
# and the CLI output classifier needs them (research R-7). Anything else is replaced.
KNOWN_CLI_MESSAGES = [re.compile(p) for p in (
    r"Internal INUBIT error!", r"Interner INUBIT-Fehler!", r"Command not found\.",
    r"Invalid filter expression!", r"Kill processes failed:", r"Exception from service object:",
    r"The user does not exist or password does not match\.",
    r"(?:Login to the server failed\.|Anmeldung am Server ist fehlgeschlagen\.)"
    r"(?: : Error opening socket: [\w.$]+(?:: [A-Za-z ]+)?)?",
    r"No process found with id \[\d+\]!",
    r"\d+: Exception from service object: : Internal INUBIT error! : No process found with id \d+!",
    r"ps \[OPTIONS\] shows list of processes being in a certain state in the Process Engine\.",
)]
# "@Start@<code>@@@<text>[@@@<text>…]@End@" segments in StartCLI exceptions and INUBIT error pages.
INUBIT_SEGMENT_RE = re.compile(r"@Start@(.*?)@End@", re.DOTALL)
INUBIT_CODE_TOKEN_RE = re.compile(r"[A-Za-z][\w.]*")
# A 4-part number is kept only as the value of a *Version attribute (system/info ServerJDKVersion).
VERSION_ATTRIBUTE_RE = re.compile(r'name="\w*Version" value="$')

EMAIL_RE = re.compile(
    r"(?<![\w.%+@-])[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9-]+\.)+(?:"
    + "|".join(HOST_SUFFIXES)
    + r"|[A-Za-z]{2})(?![\w-])",
    re.IGNORECASE,
)
HOST_RE = re.compile(
    r"(?<![\w.@-])((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+(?:"
    + "|".join(HOST_SUFFIXES)
    + r"))(?![\w(-]|\.[A-Za-z0-9])",
    re.IGNORECASE,
)
IPV4_RE = re.compile(r"(?<![\w.])((?:\d{1,3}\.){3}\d{1,3})(?![\w]|\.\d)")
AWS_NODE_RE = re.compile(r"(?<![\w-])ip-(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})(?![\w-])")
# Hyphenated INUBIT object names with at least three parts whose LAST part is all lowercase
# (XYZ-Calc-jdoe): that part is a login, unless it is a common technical word.
OBJECT_IDENTIFIER_RE = re.compile(r"(?<![\w-])[A-Z][A-Z0-9]{1,9}(?:-[A-Za-z0-9]+){2,}(?![\w-])")
IDENTIFIER_LOGIN_TOKEN_RE = re.compile(r"(?<=-)[a-z]{4,12}[0-9]*$")
NOT_A_LOGIN = {
    "user", "users", "connector", "multiplexer", "demultiplexer", "splitter", "joiner", "entry",
    "exit", "create", "delete", "update", "side", "outerxml", "test", "tests", "dummy", "mock",
    "input", "output", "service", "config", "error", "start", "end", "main", "sender",
    "receiver", "handler", "logger", "copy", "backup", "empty", "assign", "throw", "retry",
    "wait", "async", "sync", "intern", "extern", "local", "remote", "legacy", "draft",
}
OBJECT_NAME_LOGIN_RE = re.compile(r"^([A-Za-z][\w.-]{2,}) \[[^\]]+\]$")
XML_USER_RE = re.compile(
    r"(<(" + "|".join(sorted(USER_KEYS)) + r")>)([^<]+)(</\2>)"
    + r"|(\b(" + "|".join(sorted(USER_KEYS)) + r')=")([^"]+)(")'
)
EXCEPTION_RE = re.compile(
    r"\b(?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error|Throwable)\b"
)
INUBIT_CODE_RE = re.compile(r"@Start@([A-Za-z][A-Za-z0-9_]*)@@@")


@dataclass
class State:
    """Placeholder mappings; persisted with --state. Contains original values."""

    users: dict[str, str] = field(default_factory=dict)
    ips: dict[str, str] = field(default_factory=dict)
    messages: dict[str, str] = field(default_factory=dict)
    subjects: dict[str, str] = field(default_factory=dict)
    tags: dict[str, str] = field(default_factory=dict)

    @classmethod
    def load(cls, path: Path | None) -> "State":
        if path is None or not path.exists():
            return cls()
        data = json.loads(path.read_text(encoding="utf-8"))
        keys = ("users", "ips", "messages", "subjects", "tags")
        return cls(**{k: dict(data.get(k, {})) for k in keys})

    def save(self, path: Path | None) -> None:
        if path is None:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.__dict__, indent=2, ensure_ascii=False), encoding="utf-8")
        os.chmod(path, 0o600)


class Anonymizer:
    def __init__(self, state: State, users=(), keep=(), hosts=()):
        self.state = state
        self.keep = {k.lower() for k in keep} | RESERVED_NAMES
        # "<GROUP>_<login>" (e.g. OWNERS_jdoe) for every kept user-group name.
        groups = "|".join(re.escape(k) for k in keep if k) or "(?!)"
        self.prefixed_login_re = re.compile(
            rf"(?<![\w-])(?:{groups})_[A-Za-z][A-Za-z0-9.]{{2,}}(?![\w-])")
        self.extra_hosts = [h for h in hosts if h]
        for user in users:
            self.register_user(user)

    # ----- placeholders ---------------------------------------------------------------------

    def register_user(self, name: str) -> str | None:
        name = (name or "").strip()
        already_placeholder = name.startswith("user") and name[4:].isdigit()
        if len(name) < 3 or name.lower() in self.keep or already_placeholder:
            return None
        key = name.lower()
        if key not in self.state.users:
            self.state.users[key] = f"user{len(self.state.users) + 1}"
        return self.state.users[key]

    def ip_placeholder(self, ip: str) -> str:
        if ip in KEEP_IPS or ip.startswith(IP_PREFIX):
            return ip
        if not all(0 <= int(part) <= 255 for part in ip.split(".")):
            return ip
        if ip not in self.state.ips:
            self.state.ips[ip] = f"{IP_PREFIX}{len(self.state.ips) + 1}"
        return self.state.ips[ip]

    def message_placeholder(self, text: str, brackets: str = "<>") -> str:
        if text not in self.state.messages:
            self.state.messages[text] = f"message {len(self.state.messages) + 1}"
        kept = list(dict.fromkeys(INUBIT_CODE_RE.findall(text) + EXCEPTION_RE.findall(text)))
        placeholder = brackets[0] + self.state.messages[text].strip("<>") + brackets[1]
        return f"{placeholder} ({'; '.join(kept)})" if kept else placeholder

    def tag_placeholder(self, text: str) -> str:
        if text not in self.state.tags:
            self.state.tags[text] = f"tag-{len(self.state.tags) + 1}"
        return self.state.tags[text]

    def subject_placeholder(self, text: str) -> str:
        if text not in self.state.subjects:
            self.state.subjects[text] = f"CN=<subject {len(self.state.subjects) + 1}>"
        return self.state.subjects[text]

    # ----- structured input -----------------------------------------------------------------

    def collect_users_json(self, node, key: str | None = None) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                self.collect_users_json(v, k)
        elif isinstance(node, list):
            for item in node:
                self.collect_users_json(item, key)
        elif isinstance(node, str):
            self.collect_users_text(node)
            if key in USER_KEYS:
                self.register_user(node)
            elif key == "objectName":
                match = OBJECT_NAME_LOGIN_RE.match(node)
                if match:
                    self.register_user(match.group(1))

    def transform_json(self, node, key: str | None = None, in_log_row: bool = False):
        if isinstance(node, dict):
            return {k: self.transform_json(v, k, in_log_row) for k, v in node.items()}
        if isinstance(node, list):
            row_list = in_log_row or key == LOG_ROW_CONTAINER
            return [self.transform_json(item, key, row_list) for item in node]
        if isinstance(node, str) and node:
            if in_log_row and key in MESSAGE_KEYS:
                return self.message_placeholder(node)
            if in_log_row and key in SUBJECT_KEYS:
                return self.subject_placeholder(node)
            return self.transform_text(node)
        return node

    def collect_users_text(self, text: str) -> None:
        for match in XML_USER_RE.finditer(text):
            self.register_user(match.group(3) or match.group(7) or "")
        for match in self.prefixed_login_re.finditer(text):
            self.register_user(match.group(0))
        for identifier in OBJECT_IDENTIFIER_RE.finditer(text):
            token = IDENTIFIER_LOGIN_TOKEN_RE.search(identifier.group(0))
            if token and token.group(0).rstrip("0123456789") not in NOT_A_LOGIN:
                self.register_user(token.group(0))

    # ----- plain text rules -----------------------------------------------------------------

    def transform_text(self, text: str) -> str:
        text = EMAIL_RE.sub(self._email, text)
        text = AWS_NODE_RE.sub(self._aws_node, text)
        text = IPV4_RE.sub(self._ipv4, text)
        for host in self.extra_hosts:
            text = re.sub(rf"(?<![\w.-]){re.escape(host)}(?![\w-])", HOST_PLACEHOLDER, text,
                          flags=re.IGNORECASE)
        text = HOST_RE.sub(self._host, text)
        text = self.prefixed_login_re.sub(
            lambda m: self.register_user(m.group(0)) or m.group(0), text)
        for original, placeholder in sorted(self.state.users.items(), key=lambda i: -len(i[0])):
            text = re.sub(rf"(?<![A-Za-z0-9]){re.escape(original)}(?![A-Za-z0-9])", placeholder,
                          text, flags=re.IGNORECASE)
        return text

    def _email(self, match: re.Match) -> str:
        email = match.group(0)
        if email.lower().endswith("@" + EMAIL_DOMAIN):
            return email
        return f"{self.register_user(email) or 'user0'}@{EMAIL_DOMAIN}"

    def _ipv4(self, match: re.Match) -> str:
        if self.is_version_literal(match):
            return match.group(1)
        return self.ip_placeholder(match.group(1))

    @staticmethod
    def is_version_literal(match: re.Match) -> bool:
        """True only for the exact shape name="…Version" value="17.0.16.8 - …" (or …8")."""
        preceding = match.string[max(0, match.start() - 60):match.start()]
        following = match.string[match.end():match.end() + 3]
        return bool(VERSION_ATTRIBUTE_RE.search(preceding)) and (
            following.startswith(" - ") or following.startswith('"'))

    def _aws_node(self, match: re.Match) -> str:
        placeholder = self.ip_placeholder(".".join(match.groups()))
        return "ip-" + placeholder.replace(".", "-")

    @staticmethod
    def _host(match: re.Match) -> str:
        host = match.group(1).lower()
        if any(host == public or host.endswith("." + public) for public in PUBLIC_HOSTS):
            return match.group(1)
        return HOST_PLACEHOLDER

    # ----- entry point ----------------------------------------------------------------------

    def anonymize(self, content: str, fmt: str = "auto") -> str:
        if fmt in ("auto", "json"):
            try:
                data = json.loads(content)
            except ValueError:
                if fmt == "json":
                    raise
            else:
                self.collect_users_json(data)
                result = self.transform_json(data)
                return json.dumps(result, indent=2, ensure_ascii=False) + "\n"
        self.collect_users_text(content)
        content = XML_TEXT_RE.sub(self._xml_text, content)
        content = INUBIT_SEGMENT_RE.sub(self._inubit_segment, content)
        content = self.scrub_cli_output(content)
        return self.transform_text(content)

    def _inubit_segment(self, match: re.Match) -> str:
        """Keeps codes and known INUBIT system messages, replaces every other text part."""
        parts = match.group(1).split("@@@")
        scrubbed = [self._scrub_segment_part(part, is_code=(index == 0))
                    for index, part in enumerate(parts)]
        return "@Start@" + "@@@".join(scrubbed) + "@End@"

    def _scrub_segment_part(self, part: str, is_code: bool) -> str:
        lines = part.split("\n")
        for i, line in enumerate(lines):
            stripped = line.strip()
            if not stripped or any(known.fullmatch(stripped) for known in KNOWN_CLI_MESSAGES):
                continue
            if is_code and INUBIT_CODE_TOKEN_RE.fullmatch(stripped):
                continue
            lines[i] = self.message_placeholder(stripped, "[]")
        return "\n".join(lines)

    def scrub_cli_output(self, text: str) -> str:
        """ps -csv TAG column and the free text of StartCLI's summary block."""
        lines = text.split("\n")
        in_ps_table = in_summary = False
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped == PS_CSV_HEADER:
                in_ps_table, in_summary = True, False
                continue
            if stripped in CLI_SUMMARY_HEADERS:
                in_summary, in_ps_table = True, False
                continue
            if in_ps_table:
                if stripped.startswith("Total:") or not stripped:
                    in_ps_table = False
                    continue
                columns = line.split(",")
                if len(columns) >= 9 and columns[-1].strip():
                    columns[-1] = self.tag_placeholder(columns[-1].strip())
                    lines[i] = ",".join(columns)
            elif in_summary and stripped:
                if not any(known.fullmatch(stripped) for known in KNOWN_CLI_MESSAGES):
                    lines[i] = self.message_placeholder(stripped)
        return "\n".join(lines)

    def _xml_text(self, match: re.Match) -> str:
        text = match.group(2)
        if not text.strip():
            return match.group(0)
        return f"<{match.group(1)}>{self.message_placeholder(text, '[]')}</{match.group(1)}>"


# ----- XML trimming and ZIP archives --------------------------------------------------------


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def trim_xml(text: str, limits: dict[str, int], drops: set[str]) -> str:
    """Keeps the first N elements per local name in `limits`, removes `drops` subtrees, and
    removes containers that lost all their limited children. Namespace prefixes are kept,
    except ns<digits> prefixes, which ElementTree renumbers."""
    if not limits and not drops:
        return text
    for _, (prefix, uri) in ET.iterparse(io.StringIO(text), events=("start-ns",)):
        if not re.fullmatch(r"ns\d+", prefix):  # ElementTree reserves ns<digits> for itself
            ET.register_namespace(prefix, uri)
    root = ET.fromstring(text)
    seen: Counter[str] = Counter()

    def visit(parent: ET.Element) -> None:
        for child in list(parent):
            name = local_name(child.tag)
            if name in drops:
                parent.remove(child)
                continue
            if name in limits:
                seen[name] += 1
                if seen[name] > limits[name]:
                    parent.remove(child)
                    continue
            had_limited = any(local_name(c.tag) in limits for c in child)
            visit(child)
            if had_limited and not any(local_name(c.tag) in limits for c in child):
                parent.remove(child)

    visit(root)
    ET.indent(root, space="    ")
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n"


def anonymize_zip(source: Path, target: Path, anonymizer: "Anonymizer", keep: list[str],
                  limits: dict[str, int], drops: set[str]) -> None:
    """Writes a ZIP with only the `keep` entries, each trimmed (XML) and anonymized as text."""
    with zipfile.ZipFile(source) as archive:
        names = set(archive.namelist())
        missing = [entry for entry in keep if entry not in names]
        if missing:
            raise SystemExit(f"anonymize.py: ZIP entries not found: {', '.join(missing)}")
        texts = {entry: archive.read(entry).decode("utf-8") for entry in keep}
        infos = {entry: archive.getinfo(entry) for entry in keep}
    for text in texts.values():
        anonymizer.collect_users_text(text)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for entry in keep:
            text = texts[entry]
            if entry.endswith(".xml"):
                text = trim_xml(text, limits, drops)
            info = zipfile.ZipInfo(entry, date_time=infos[entry].date_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, anonymizer.anonymize(text, "text"))


def readable_texts(path: Path) -> list[tuple[str, str]]:
    """(label, text) pairs: the file itself, or every entry of a ZIP archive."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return [(f"{path}!{name}", archive.read(name).decode("utf-8", errors="replace"))
                    for name in archive.namelist() if not name.endswith("/")]
    return [(str(path), path.read_text(encoding="utf-8", errors="replace"))]


# ----- verification -------------------------------------------------------------------------


def verify(paths: list[Path], anonymizer: Anonymizer, secrets: list[str] = ()) -> int:
    """Report remaining hostnames, IPs, usernames or secrets (rule + line only, never the value)."""
    findings = 0
    known_users = list(anonymizer.state.users)
    for label, text in (pair for path in paths for pair in readable_texts(path)):
        for number, line in enumerate(text.splitlines(), start=1):
            rules = ["secret" for secret in secrets if secret and secret in line][:1]
            if any(Anonymizer._host(m) != m.group(1) for m in HOST_RE.finditer(line)):
                rules.append("hostname")
            if any(anonymizer._ipv4(m) != m.group(1) for m in IPV4_RE.finditer(line)):
                rules.append("ipv4")
            if any(not m.group(0).lower().endswith("@" + EMAIL_DOMAIN)
                   for m in EMAIL_RE.finditer(line)):
                rules.append("email")
            if any(re.search(rf"(?<![A-Za-z0-9]){re.escape(u)}(?![A-Za-z0-9])", line, re.I)
                   for u in known_users):
                rules.append("username")
            for rule in rules:
                findings += 1
                print(f"{label}:{number}: remaining {rule}", file=sys.stderr)
    return 1 if findings else 0


# ----- self-test ----------------------------------------------------------------------------


# Fictitious user group of the self-test (no default is built in, see --keep).
SELF_TEST_KEEP = ("OWNERS",)


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, actual: str, *, contains=(), absent=()):
        for expected in contains:
            if expected not in actual:
                failures.append(f"{name}: expected {expected!r} in output:\n{actual}")
        for forbidden in absent:
            if forbidden.lower() in actual.lower():
                failures.append(f"{name}: {forbidden!r} must not appear in output:\n{actual}")

    log_json = json.dumps({"auditLog": {"total": 2, "success": True, "count": 2, "row": [
        {"objectName": "jdoe [OWNERS]", "user": "jdoe", "operation": "AuditLogLoginUser",
         "message": "", "time": 1771239595462},
        {"owner": "OWNERS", "workflowName": "XYZ-Calc-jdoe", "node": "ip-10-0-0-1",
         "message": "Order 4711 for ACME failed @Start@IError@@@Internal INUBIT error!@End@ "
                    "java.net.ConnectException: Connection refused to db.corp.internal",
         "userDefined2": "customer secret text", "userDefined3": "",
         "moduleName": "XYZ-Mod-mmuster",
         "subject": "EMAILADDRESS=ops@acme.de, CN=Jane Roe, O=ACME GmbH, C=DE",
         "URL": "http://esb-dev-1.acme.int:8000/ibis/ws/Service",
         "systemId": "jdbc:postgresql://inubit-db.acme.internal:5432/log"},
    ]}})
    # "envuser" (from the environment) becomes user1, "jdoe" (found in the rows) user2.
    out = Anonymizer(State(), users=["envuser"], keep=SELF_TEST_KEEP).anonymize(log_json)
    check("json log rows", out,
          contains=['"user": "user2"', '"objectName": "user2 [OWNERS]"', '"owner": "OWNERS"',
                    "XYZ-Calc-user2", "XYZ-Mod-user3", "ip-192-0-2-1",
                    '"message": "<message 1> (IError; java.net.ConnectException)"',
                    '"userDefined2": "<message 2>"', '"userDefined3": ""',
                    '"subject": "CN=<subject 1>"',
                    "http://inubit.example.test:8000/ibis/ws/Service",
                    "jdbc:postgresql://inubit.example.test:5432/log", '"message": ""'],
          absent=["jdoe", "mmuster", "ACME", "4711", "10.0.0", "acme.internal", "acme.int",
                  "Jane Roe",
                  "ops@acme.de", "customer secret"])

    metrics = '{"serverName":"inubit.dev.acme-corp.cloud","usedMemoryInMByte":1330.0}'
    check("ipv4 near the word version", Anonymizer(State()).anonymize(
              'CLI version 8.1.17 connecting to 10.20.30.40\n'
              '<SystemInformation name="ServerOSVersion" value="x"/><x>10.9.8.7</x>', "text"),
          contains=["connecting to 192.0.2.1", "<x>192.0.2.2</x>"],
          absent=["10.20.30.40", "10.9.8.7"])
    exception = (
        "com.inubit.ibis.utils.InubitException: @Start@IError@@@Order 4711 for ACME GmbH@End@\n"
        "InubitException: @Start@IError@@@Internal INUBIT error!@End@@Start@Kill processes failed:\n"
        "999999999: Exception from service object: : Internal INUBIT error! : No process found "
        "with id 999999999!\n@@@Kill processes failed:\n@End@\n"
        "@Start@No process found with id [999999999]!@@@No process found with id [999999999]!@End@\n"
        "@Start@LoginFailure@@@The user does not exist or password does not match.@End@\n"
    )
    check("exception segments", Anonymizer(State()).anonymize(exception, "text"),
          contains=["@Start@IError@@@[message 1]@End@", "@Start@IError@@@Internal INUBIT error!@End@",
                    "No process found with id 999999999!",
                    "@Start@No process found with id [999999999]!@@@",
                    "@Start@LoginFailure@@@The user does not exist or password does not match."],
          absent=["4711", "ACME"])
    info = ('<SystemInformation name="ServerJDKVersion" value="17.0.16.8 - 64 bit"/>'
            '<SystemInformation name="ServerJDKVendor" value="Amazon.com Inc."/> host 10.2.3.4')
    check("system info", Anonymizer(State()).anonymize(info, "text"),
          contains=['value="17.0.16.8 - 64 bit"', 'value="Amazon.com Inc."', "host 192.0.2.1"])
    check("metrics", Anonymizer(State()).anonymize(metrics),
          contains=['"serverName": "inubit.example.test"', '"usedMemoryInMByte": 1330.0'],
          absent=["acme-corp"])

    cli = (
        "JAVA_HOME is set\nPassword: \n"
        "ERROR 15:04:46,408 [main      ] IBISHTTPUtils             post: \n"
        "java.net.ConnectException: Connection refused\n"
        "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login failed.@End@"
        "Error opening socket to inubit.dev.acme-corp.cloud/10.1.2.3 for envuser\n"
        "\tat com.inubit.ibis.soap.IBISSoapClient.modifyObject(IBISSoapClient.java:1556) "
        "~[ibis.jar:8.1.17]\n\tat org.slf4j.helpers.Logger.info(Logger.java:12)\n"
        "local https://127.0.0.1:9/ibis/servlet/IBISSoapServlet, ns http://www.w3.org/2001/X\n"
        "<CheckinUser>OWNERS_mmuster</CheckinUser> by OWNERS_mmuster, owner=\"OWNERS\"\n"
    )
    out = Anonymizer(State(), users=["envuser"], keep=SELF_TEST_KEEP).anonymize(cli)
    check("cli text", out,
          contains=["java.net.ConnectException: Connection refused",
                    "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@",
                    "inubit.example.test/192.0.2.1 for user1",
                    "com.inubit.ibis.soap.IBISSoapClient.modifyObject(IBISSoapClient.java:1556)",
                    "ibis.jar:8.1.17", "org.slf4j.helpers.Logger.info(Logger.java:12)",
                    "https://127.0.0.1:9/", "http://www.w3.org/2001/X",
                    "<CheckinUser>user2</CheckinUser> by user2", 'owner="OWNERS"'],
          absent=["envuser", "mmuster", "acme-corp", "10.1.2.3"])

    technical = (
        "<ModuleGroupName>AS2 Connector</ModuleGroupName> BPC_authentication PM_test "
        "XYZ-Multiplexer-entry SMIME-codierten "
        "@Start@cli.process.option.filter.invalidFilterExpression@@@ x@globex-corp.de"
    )
    check("no false positives", Anonymizer(State()).anonymize(technical, "text"),
          contains=["AS2 Connector", "BPC_authentication", "PM_test",
                    "XYZ-Multiplexer-entry", "SMIME-codierten",
                    "@Start@cli.process.option.filter.invalidFilterExpression@@@",
                    "user1@example.test"],
          absent=["globex-corp"])

    cli_output = (
        "UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG\n"
        "OWNERS,110190387,normal,Error,2026-09-25T11:11:11,XYZ-A,XYZ-B(1),ip-10-1-2-3,TICKET-7\n"
        "OWNERS,110190388,normal,Error,2026-09-25T11:11:12,XYZ-A,XYZ-B(1),ip-10-1-2-3,\n"
        "Total: 2\n\nEXECUTION ERROR\nInternal INUBIT error!\nNo process found with id [999999999]!\n"
        "Order 4711 for ACME rejected\n"
    )
    check("cli tag and summary", Anonymizer(State()).anonymize(cli_output, "text"),
          contains=["ip-192-0-2-1,tag-1\n", "ip-192-0-2-1,\n", "Internal INUBIT error!",
                    "No process found with id [999999999]!", "<message 1>"],
          absent=["TICKET-7", "ACME", "4711"])

    state = State()
    first = Anonymizer(state).anonymize('{"row":[{"message":"same"},{"message":"same"}]}')
    check("stable numbering", first, contains=['"<message 1>"'], absent=["<message 2>"])

    # No user group is kept unless it is given (--keep / ANONYMIZE_KEEP): no built-in default.
    if Anonymizer(State()).keep != RESERVED_NAMES:
        failures.append("keep names: a user group is kept without --keep")
    with contextlib.ExitStack() as stack:
        previous = os.environ.get(KEEP_ENV)
        os.environ[KEEP_ENV] = " OWNERS, ,TEAM-A "
        stack.callback(lambda: os.environ.pop(KEEP_ENV) if previous is None
                       else os.environ.__setitem__(KEEP_ENV, previous))
        if env_keep_names() != ["OWNERS", "TEAM-A"]:
            failures.append(f"keep names from {KEEP_ENV}: {env_keep_names()!r}")

    history = (
        '<?xml version="1.0" encoding="UTF-8"?><VersionInformation><Workflows>'
        '<WorkflowGroup Name="G"><Workflow Name="A"><Version><versionNode>1</versionNode>'
        '<CheckinUser>jroe</CheckinUser><CheckinComment>JR: TICKET-1: fix for ACME</CheckinComment>'
        '<DateTime>20.10.2025 11:31:48</DateTime><Tags><Tag>TICKET-1</Tag></Tags></Version>'
        '</Workflow><Workflow Name="B"/><Workflow Name="C"/></WorkflowGroup></Workflows>'
        '<Modules><Module Name="M1"><Version><versionNode>1</versionNode></Version></Module>'
        '<Module Name="M2"/></Modules></VersionInformation>'
    )
    trimmed = Anonymizer(State()).anonymize(trim_xml(history, {"Workflow": 2, "Module": 1},
                                                     set()), "text")
    check("xml trim and comments", trimmed,
          contains=['Name="A"', 'Name="B"', 'Name="M1"', "<CheckinUser>user1</CheckinUser>",
                    "<CheckinComment>[message 1]</CheckinComment>", "<Tag>TICKET-1</Tag>",
                    "<DateTime>20.10.2025 11:31:48</DateTime>"],
          absent=['Name="C"', 'Name="M2"', "jroe", "ACME"])
    dropped = trim_xml('<a xmlns:m="urn:x"><m:Keep/><Drop><x/></Drop></a>', {}, {"Drop"})
    check("xml drop", dropped, contains=["<m:Keep", 'xmlns:m="urn:x"'], absent=["Drop"])

    with tempfile.TemporaryDirectory() as tmp:
        source, target = Path(tmp, "in.zip"), Path(tmp, "out.zip")
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("versionHistory.xml", history)
            archive.writestr("module/secret-config.xml", "<p>password=hunter2</p>")
        anonymize_zip(source, target, Anonymizer(State()), ["versionHistory.xml"],
                      {"Workflow": 1}, set())
        with zipfile.ZipFile(target) as archive:
            check("zip entries", " ".join(archive.namelist()), contains=["versionHistory.xml"],
                  absent=["secret-config"])
            check("zip content", archive.read("versionHistory.xml").decode(),
                  contains=["user1"], absent=["jroe", 'Name="B"'])
        leaky = Path(tmp, "leak.txt")
        leaky.write_text("token hunter2 here\n", encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()) as report:
            leak_found = verify([leaky, target], Anonymizer(State()), ["hunter2"])
        if leak_found != 1 or "hunter2" in report.getvalue():
            failures.append("verify: a secret was not reported, or its value was printed")
        if verify([target], Anonymizer(State()), ["hunter2"]) != 0:
            failures.append("verify: clean ZIP reported")

    if failures:
        for failure in failures:
            print("FAIL " + failure, file=sys.stderr)
        return 1
    print("anonymize.py self-test: all checks passed", file=sys.stderr)
    return 0


# ----- CLI ----------------------------------------------------------------------------------


def env_keep_names() -> list[str]:
    return [name.strip() for name in os.environ.get(KEEP_ENV, "").split(",") if name.strip()]


def env_usernames() -> list[str]:
    return [value for name, value in os.environ.items()
            if name.startswith("INUBIT_") and name.endswith("_USERNAME") and value]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks")
    parser.add_argument("--verify", action="store_true",
                        help="check the given files for remaining identifying values")
    parser.add_argument("--state", type=Path, help="mapping file kept across runs (sensitive)")
    parser.add_argument("--user", action="append", default=[], help="extra username to replace")
    parser.add_argument("--keep", action="append", default=[],
                        help=f"user-group name to keep (no default; also ${KEEP_ENV}, "
                             "comma-separated)")
    parser.add_argument("--host", action="append", default=[],
                        help="extra hostname to replace (e.g. short host names)")
    parser.add_argument("--format", choices=("auto", "json", "text"), default="auto")
    parser.add_argument("--xml-limit", action="append", default=[], metavar="NAME=N",
                        help="keep only the first N elements with this local name")
    parser.add_argument("--xml-drop", action="append", default=[], metavar="NAME",
                        help="remove all elements with this local name")
    parser.add_argument("--zip-keep", action="append", default=[], metavar="ENTRY",
                        help="treat INPUT as ZIP and keep only these entries")
    parser.add_argument("--secrets-stdin", action="store_true",
                        help="with --verify: read secrets (one per line) from stdin")
    parser.add_argument("paths", nargs="*", type=Path, help="INPUT [OUTPUT] (default stdin/stdout)")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    state = State.load(args.state)
    anonymizer = Anonymizer(state, users=env_usernames() + args.user,
                            keep=args.keep + env_keep_names(), hosts=args.host)
    if args.verify:
        if not args.paths:
            parser.error("--verify needs at least one file")
        secrets = sys.stdin.read().splitlines() if args.secrets_stdin else []
        return verify(args.paths, anonymizer, secrets)

    limits = {}
    for spec in args.xml_limit:
        name, _, count = spec.partition("=")
        if not count.isdigit():
            parser.error(f"--xml-limit expects NAME=N, got {spec!r}")
        limits[name] = int(count)
    drops = set(args.xml_drop)

    if args.zip_keep:
        if len(args.paths) != 2:
            parser.error("--zip-keep needs INPUT.zip and OUTPUT.zip")
        anonymize_zip(args.paths[0], args.paths[1], anonymizer, args.zip_keep, limits, drops)
        state.save(args.state)
        return 0

    if len(args.paths) > 2:
        parser.error("expected at most INPUT and OUTPUT")
    source = args.paths[0].read_text(encoding="utf-8", errors="replace") if args.paths \
        else sys.stdin.read()
    if limits or drops:
        source = trim_xml(source, limits, drops)
    result = anonymizer.anonymize(source, args.format)
    if len(args.paths) == 2:
        args.paths[1].write_text(result, encoding="utf-8")
    else:
        sys.stdout.write(result)
    state.save(args.state)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
