#!/usr/bin/python3 -I
"""Detect changed INUBIT server certificates and adopt a new one only after an operator decision.

An operator tool for the TLS pins of an inubit-mcp profile (feature 006). It is customer-agnostic:
everything profile-specific comes from the profile file named by --profile or --config. The
command line, outputs, exit codes and files are specified in
specs/006-tls-pin-rotation/contracts/cli.md; the entities in data-model.md of the same feature.

Usage:
  inubit-cert-check (--profile NAME | --config PATH) COMMAND [--state-dir DIR] [--java PATH]
                    [--server-jar PATH]

Commands (exactly one):

  --check [--json]      C-1, read-only: contacts every https server of the profile (5 s limit
                        each, in parallel), compares the presented certificate with the pin and
                        lists superseded trust-store entries. Exit 0 all ok, 10 a certificate
                        changed (conflicts included), 20 a server unreachable.
  --accept STAGE FINGERPRINT [--by cli|claude]
                        C-2: re-verifies that every server of the stage presents FINGERPRINT,
                        backs up profile and trust store, imports the certificate, replaces the
                        pin line, validates with the server's --check-config; restores both
                        files on any failure or interrupt. Exit 0 adopted, 3 refused (nothing
                        changed), 4 failed and rolled back.
  --interactive         C-3, the launcher's start-up check: a desktop dialog per changed stage,
                        the start waits at most 20 s; always exit 0, nothing on stdout, stdin
                        never read.
  --forget STAGE        C-4: forgets the remembered rejection of a stage. Exit 0, 3 unknown stage.
  --prune STAGE [--yes] C-5: lists the trust-store entries that differ from the pin; with --yes
                        removes them (backup first). Exit 0, 3 refused, 4 failed and rolled back.

Common options: --profile NAME (file ~/.config/inubit-mcp/NAME.yaml) or --config PATH,
--state-dir DIR (default ~/.inubit-mcp/<profile.name>), --java PATH and --server-jar PATH
(override x-cert-check.java / x-cert-check.serverJar of the profile). Usage errors exit 2,
unexpected errors 1.

Files (C-6) in the state directory: cert-changes.log (one line per decision), cert-check.log
(diagnostics), cert-rejections.json (remembered rejections), cert-check.lock, and the dialog
worker's cert-dialog-<pid>.json / .offers.json; backups <file>.bak-YYYYmmdd-HHMMSS next to the
profile and the trust store.

Runs with the system Python (3.9 or newer, standard library only) in isolated mode (-I), so
neither the environment nor the current directory can inject modules. External programs:
openssl (/usr/bin/openssl first), and keytool/java of the JDK that runs the server.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import datetime
import fcntl
import hashlib
import ipaddress
import json
import os
import re
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import urllib.parse
from dataclasses import dataclass, field
from typing import Optional, Tuple

PROG = "inubit-cert-check"

# --- exit codes (contract C-1 ... C-5) ----------------------------------------------------------

OK = 0
INTERNAL = 1
USAGE = 2
REFUSED = 3
ROLLED_BACK = 4
CHANGED = 10
UNREACHABLE = 20

# --- profile names (same rule as the server's ProfileInfo.NAME_RULE) -----------------------------

PROFILE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,31}")
RESERVED_PROFILE_NAMES = frozenset({"audit"})
CONFIG_DIRECTORY = os.path.join("~", ".config", "inubit-mcp")


class UsageError(Exception):
    """Wrong command line (exit status 2)."""


class ToolError(Exception):
    """An expected failure with a one-line message for the operator (exit status 1)."""


def valid_profile_name(name):
    return bool(PROFILE_NAME.fullmatch(name)) and name not in RESERVED_PROFILE_NAMES


def profile_path(name):
    """The profile file of --profile NAME: ~/.config/inubit-mcp/NAME.yaml (like the server)."""
    return os.path.join(os.path.expanduser(CONFIG_DIRECTORY), name + ".yaml")


# --- fingerprints --------------------------------------------------------------------------------

_FINGERPRINT_BARE = re.compile(r"[0-9A-Fa-f]{64}")
_FINGERPRINT_COLONS = re.compile(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")


def normalize_fingerprint(text):
    """A SHA-256 fingerprint in the configuration's notation "AA:BB:...": 64 hex digits, either
    without colons or as 32 colon-separated pairs, any case. Raises ValueError otherwise."""
    if not isinstance(text, str):
        raise ValueError("a SHA-256 fingerprint must be text")
    value = text.strip()
    if _FINGERPRINT_COLONS.fullmatch(value):
        value = value.replace(":", "")
    elif not _FINGERPRINT_BARE.fullmatch(value):
        raise ValueError("expected a SHA-256 fingerprint: 64 hex digits, colons optional")
    value = value.upper()
    return ":".join(value[i:i + 2] for i in range(0, 64, 2))


# --- profile reader (research R-7) ---------------------------------------------------------------
#
# The standard library has no YAML parser, so the tool reads the small subset of the profile it
# needs: profile.name, credentials.envPrefix, x-cert-check, top-level "x-*: &anchor" mappings,
# defaults.tls and under groups each item's name, tls (inline block or *alias) and
# nodes[].name/baseUrl/tls. Values of other keys are never parsed (their extent is found by
# indentation only), so flow mappings, block scalars and the like elsewhere do not matter.
# Anything inside the needed paths the reader does not understand is an error: no partial result.

STAGE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,31}")  # group and node names (ProfileInfo)
ENV_PREFIX = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
DEFAULT_ENV_PREFIX_START = "INUBIT_"
TLS_KEYS = ("trustStore", "disableHostnameVerification", "pinnedCertificateSha256")
CERT_CHECK_KEYS = ("java", "serverJar")
PIN_KEY = "pinnedCertificateSha256"
DEFAULT_HTTPS_PORT = 443

_KEY = re.compile(r"""([A-Za-z0-9_][A-Za-z0-9_.-]*|"[^"\\]*"|'[^']*')[ ]*:(?:[ ]+(.*))?$""")
_ANCHOR = re.compile(r"&([^\s\[\]{},]+)(?:[ ]+(.*))?$")
_ALIAS = re.compile(r"\*([^\s\[\]{},]+)$")
_ANCHOR_TOKEN = re.compile(r"(?:^|[\s\[{,])&([^\s\[\]{},]+)")
_MERGE_KEY = re.compile(r"(?:^(?:-[ ]+)*|[{,][ ]*)<<[ ]*:")
_ESCAPES = {"\\": "\\", '"': '"', "/": "/", "n": "\n", "t": "\t", "r": "\r", "0": "\0",
            " ": " "}


class ConfigError(ToolError):
    """The profile file cannot be read or uses YAML the reader does not support (fail closed)."""


@dataclass(frozen=True)
class Server:
    """One INUBIT endpoint of a stage; pin and trust store are the effective ones (server ->
    stage -> defaults)."""
    id: str
    stage: str
    name: str
    base_url: str
    host: str
    port: int
    pin: Optional[str] = None
    trust_store: Optional[str] = None


@dataclass(frozen=True)
class SkippedServer:
    """A server that is not contacted: an http:// server has no certificate (reason "no TLS")."""
    id: str
    stage: str
    name: str
    base_url: str
    reason: str = "no TLS"


@dataclass(frozen=True)
class Stage:
    """A group of the profile (data-model "Stage"). pin and trust_store are the stage-level
    effective values (stage -> defaults); pin_line is the line of the pin in the stage's own
    inline tls block (pin_source "stage"), else None. servers are the https servers only."""
    name: str
    pin: Optional[str]
    pin_source: str  # stage | alias | defaults | server
    pin_line: Optional[int]
    trust_store: Optional[str]
    own_tls: bool
    servers: Tuple[Server, ...]
    skipped_servers: Tuple[SkippedServer, ...] = ()
    server_ids: Tuple[str, ...] = ()  # every server of the stage, in file order


@dataclass(frozen=True)
class Profile:
    path: str
    name: str
    env_prefix: str
    java: Optional[str]
    server_jar: Optional[str]
    stages: Tuple[Stage, ...]

    def stage(self, name):
        for stage in self.stages:
            if stage.name == name:
                return stage
        return None

    @property
    def skipped_servers(self):
        return [server for stage in self.stages for server in stage.skipped_servers]

    def server_ids(self):
        """Every server id of the profile in file order (as `--check-config` lists them)."""
        return [server_id for stage in self.stages for server_id in stage.server_ids]


@dataclass(frozen=True)
class _Line:
    number: int  # 1-based line of the file
    indent: int
    text: str  # without indentation and comment


class _Node:
    def __init__(self, number, anchor=None):
        self.number = number
        self.anchor = anchor


class _Scalar(_Node):
    def __init__(self, number, value, anchor=None):
        super().__init__(number, anchor)
        self.value = value


class _Alias(_Node):
    def __init__(self, number, name):
        super().__init__(number)
        self.name = name


class _Opaque(_Node):
    """A value the reader does not interpret (flow collection, block scalar, tag, ...)."""

    def __init__(self, number, what, text="", anchor=None):
        super().__init__(number, anchor)
        self.what = what
        self.text = text


class _Sequence(_Node):
    def __init__(self, number, items):
        super().__init__(number)
        self.items = items


class _Mapping(_Node):
    """A mapping whose values are parsed only when asked for (unknown keys are never parsed)."""

    def __init__(self, number, entries):
        super().__init__(number)
        self._entries = entries  # key -> (line number, inline text, child lines) or a _Node
        self._parsed = {}

    def keys(self):
        return list(self._entries)

    def key_line(self, key):
        entry = self._entries[key]
        return entry.number if isinstance(entry, _Node) else entry[0]

    def inline_text(self, key):
        entry = self._entries[key]
        return None if isinstance(entry, _Node) else entry[1]

    def has_children(self, key):
        entry = self._entries[key]
        return not isinstance(entry, _Node) and bool(entry[2])

    def get(self, key):
        if key not in self._entries:
            return None
        if key not in self._parsed:
            entry = self._entries[key]
            self._parsed[key] = entry if isinstance(entry, _Node) else _value(*entry)
        return self._parsed[key]


def _fail(number, message):
    raise ConfigError(f"line {number}: {message}" if number else message)


def _strip_comment(text):
    """The line without a trailing comment ("#" after whitespace, outside quoted scalars)."""
    blanked = _without_quoted(text)
    for i, c in enumerate(blanked):
        if c == "#" and (i == 0 or text[i - 1] in " \t"):
            return text[:i].rstrip()
    return text.rstrip()


def _without_quoted(text):
    """text with quoted scalars blanked out (a quote opens only at the start of a token)."""
    out = []
    quote = None
    previous = " "
    i = 0
    while i < len(text):
        c = text[i]
        if quote == "'":
            if c == "'" and text[i + 1:i + 2] == "'":
                out.append("  ")
                i += 2
                continue
            if c == "'":
                quote = None
            out.append(" ")
        elif quote == '"':
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == '"':
                quote = None
            out.append(" ")
        elif c in "'\"" and previous in " \t[{,":
            quote = c
            out.append(" ")
        else:
            out.append(c)
        previous = c
        i += 1
    return "".join(out)


def _anchor_lines(lines):
    """{anchor name: line} of every anchor token in the file; a name defined twice is an error."""
    anchors = {}
    for line in lines:
        for name in _ANCHOR_TOKEN.findall(_without_quoted(line.text)):
            if name in anchors:
                _fail(line.number, f"anchor '&{name}' is defined twice (lines {anchors[name]} "
                                   f"and {line.number})")
            anchors[name] = line.number
    return anchors


def _content_lines(text):
    """The non-empty, non-comment lines; whole-file checks that fail closed."""
    lines = []
    for number, raw in enumerate(text.split("\n"), 1):
        if raw.endswith("\r"):
            raw = raw[:-1]
        if number == 1 and raw.startswith("\ufeff"):
            raw = raw[1:]
        if not raw.strip():
            continue
        body = raw.lstrip(" \t")
        if "\t" in raw[:len(raw) - len(body)]:
            _fail(number, "tab in the indentation (only spaces are supported)")
        indent = len(raw) - len(body)
        content = _strip_comment(body)
        if not content:
            continue
        if indent == 0 and (content in ("---", "...") or content.startswith(("--- ", "... "))
                            or content.startswith("%")):
            _fail(number, "document markers ('---', '...') and directives are not supported; "
                          "the profile must be a single YAML document")
        if _MERGE_KEY.search(content):
            _fail(number, "merge keys ('<<:') are not supported")
        lines.append(_Line(number, indent, content))
    return lines


def _is_sequence_item(text):
    return text == "-" or text.startswith("- ")


def _parse_mapping(lines):
    indent = lines[0].indent
    entries = {}
    k = 0
    while k < len(lines):
        line = lines[k]
        if line.indent != indent:
            _fail(line.number, "unexpected indentation")
        match = _KEY.match(line.text)
        if not match or _is_sequence_item(line.text):
            _fail(line.number, "expected 'key: value'")
        key = match.group(1)
        if key[0] in "'\"":
            key = key[1:-1]
        rest = (match.group(2) or "").strip()
        bare = _ANCHOR.match(rest)
        empty_value = rest == "" or (bare is not None and not (bare.group(2) or "").strip())
        m = k + 1
        while m < len(lines) and (lines[m].indent > indent or (
                empty_value and lines[m].indent == indent and _is_sequence_item(lines[m].text))):
            m += 1
        if key in entries:
            _fail(line.number, f"duplicate key '{key}'")
        entries[key] = (line.number, rest, lines[k + 1:m])
        k = m
    return _Mapping(lines[0].number, entries)


def _parse_sequence(lines):
    indent = lines[0].indent
    items = []
    k = 0
    while k < len(lines):
        line = lines[k]
        if line.indent != indent or not _is_sequence_item(line.text):
            _fail(line.number, "expected a sequence item ('- ...') at this indentation")
        m = k + 1
        while m < len(lines) and lines[m].indent > indent:
            m += 1
        content = line.text[1:]
        stripped = content.lstrip(" ")
        if not stripped:
            items.append(_value(line.number, "", lines[k + 1:m]))
        else:
            first = _Line(line.number, indent + 1 + len(content) - len(stripped), stripped)
            items.append(_block_value([first] + lines[k + 1:m]))
        k = m
    return _Sequence(lines[0].number, items)


def _block_value(lines):
    """A sequence item's value; its first line is the text after "- "."""
    first = lines[0]
    if _is_sequence_item(first.text):
        return _parse_sequence(lines)
    if _KEY.match(first.text) and not first.text.startswith(("&", "*", "{", "[", "!", "|",
                                                                ">", "'", '"')):
        return _parse_mapping(lines)
    return _value(first.number, first.text, lines[1:])


def _value(number, rest, children):
    """The value of "key: <rest>" followed by the more-indented child lines."""
    anchor = None
    match = _ANCHOR.match(rest)
    if match:
        anchor, rest = match.group(1), (match.group(2) or "").strip()
    if rest.startswith("*"):
        alias = _ALIAS.match(rest)
        if not alias or children or anchor:
            _fail(number, "unsupported alias")
        return _Alias(number, alias.group(1))
    if rest == "":
        if not children:
            node = _Scalar(number, None)
        elif _is_sequence_item(children[0].text):
            node = _parse_sequence(children)
        else:
            node = _parse_mapping(children)
    elif rest[0] in "{[":
        text = " ".join([rest] + [line.text for line in children])
        node = _Opaque(number, "flow collection" if children else "flow collection on one line",
                       text)
    elif rest[0] in "|>":
        node = _Opaque(number, "block scalar")
    elif rest[0] in "!&%@`?":
        node = _Opaque(number, "tag or reserved indicator")
    elif children:
        node = _Opaque(number, "multi-line scalar")
    else:
        node = _Scalar(number, _scalar(number, rest))
    node.anchor = anchor
    return node


def _scalar(number, text):
    """A plain or quoted scalar on one line; plain null / ~ / empty is None."""
    if text[0] == '"':
        out = []
        i = 1
        while i < len(text):
            c = text[i]
            if c == "\\":
                escaped = text[i + 1:i + 2]
                if escaped not in _ESCAPES:
                    _fail(number, "unsupported escape sequence in a double-quoted value")
                out.append(_ESCAPES[escaped])
                i += 2
                continue
            if c == '"':
                if text[i + 1:].strip():
                    _fail(number, "text after a closing quote")
                return "".join(out)
            out.append(c)
            i += 1
        _fail(number, "unterminated double-quoted value")
    if text[0] == "'":
        out = []
        i = 1
        while i < len(text):
            c = text[i]
            if c == "'":
                if text[i + 1:i + 2] == "'":
                    out.append("'")
                    i += 2
                    continue
                if text[i + 1:].strip():
                    _fail(number, "text after a closing quote")
                return "".join(out)
            out.append(c)
            i += 1
        _fail(number, "unterminated single-quoted value")
    if text in ("~", "null", "Null", "NULL"):
        return None
    return text


def _split_flow(text, number):
    """The comma-separated parts of a one-line flow mapping without nested collections."""
    parts, current, quote = [], [], None
    for c in text:
        if quote:
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c in "{}[]":
            _fail(number, "nested flow collections are not supported here")
        elif c == ",":
            parts.append("".join(current))
            current = []
            continue
        current.append(c)
    if quote:
        _fail(number, "unterminated quoted value")
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def _simple_flow_mapping(node):
    """A one-line flow mapping of plain or quoted scalars, e.g. "profile: {name: acme}"."""
    text = node.text.strip()
    if node.what != "flow collection on one line" or not (text.startswith("{")
                                                          and text.endswith("}")):
        _fail(node.number, "expected a block mapping or a one-line flow mapping")
    entries = {}
    for part in _split_flow(text[1:-1], node.number):
        match = _KEY.match(part)
        if not match or not (match.group(2) or "").strip():
            _fail(node.number, "expected 'key: value' in the flow mapping")
        key = match.group(1).strip("'\"")
        if key in entries:
            _fail(node.number, f"duplicate key '{key}'")
        entries[key] = _Scalar(node.number, _scalar(node.number, match.group(2).strip()))
    return _Mapping(node.number, entries)


def _mapping(node, what, number, allow_flow=False):
    """node as a mapping (None stays None); everything else is an error."""
    if node is None or (isinstance(node, _Scalar) and node.value is None):
        return None
    if node.anchor is not None:
        _fail(node.number, f"{what}: anchors are only supported on top-level x-* keys")
    if isinstance(node, _Mapping):
        return node
    if allow_flow and isinstance(node, _Opaque):
        return _simple_flow_mapping(node)
    _fail(node.number if node else number, f"{what} must be a mapping")


def _string(mapping, key, what):
    """The scalar value of mapping[key] as text, or None when absent or null."""
    if mapping is None:
        return None
    node = mapping.get(key)
    if node is None:
        return None
    if not isinstance(node, _Scalar) or node.anchor is not None:
        _fail(node.number, f"{what}.{key} must be a single-line value")
    return node.value


def _expand(path):
    return None if path is None else os.path.expanduser(path)


class _TlsBlock:
    """A tls block as written: kind "inline" or "alias", values and their lines."""

    def __init__(self, kind, values, lines):
        self.kind = kind
        self.values = values
        self.lines = lines

    def get(self, key):
        return self.values.get(key)


def _tls_block(node, anchors, what):
    """anchors: {name: (node, line)} of the top-level x-* keys and "*": {name: line} of every
    anchor in the file."""
    if node is None or (isinstance(node, _Scalar) and node.value is None and node.anchor is None):
        return None
    kind = "inline"
    if isinstance(node, _Alias):
        if node.name not in anchors:
            where = anchors["*"].get(node.name)
            if where is not None:
                _fail(node.number, f"{what}.tls: alias '*{node.name}' refers to an anchor on line "
                                   f"{where}; only anchors on top-level x-* keys are supported")
            _fail(node.number, f"{what}.tls: undefined alias '*{node.name}' (aliases must refer "
                               "to a top-level 'x-...: &name' mapping)")
        target, target_line = anchors[node.name]
        if target_line > node.number:
            _fail(node.number, f"{what}.tls: alias '*{node.name}' is used before its anchor "
                               f"(line {target_line})")
        kind, node = "alias", target
        if not isinstance(node, _Mapping):
            _fail(node.number, f"{what}.tls: the alias must refer to a block mapping")
    elif not isinstance(node, _Mapping):
        _fail(node.number, f"{what}.tls must be a block mapping or an alias")
    elif node.anchor is not None:
        _fail(node.number, f"{what}.tls: anchors are only supported on top-level x-* keys")
    values, lines = {}, {}
    for key in node.keys():
        if key not in TLS_KEYS:
            _fail(node.key_line(key), f"{what}.tls: unknown key '{key}'")
        if key == PIN_KEY and not node.inline_text(key) and node.has_children(key):
            _fail(node.key_line(key), f"{what}.tls.{PIN_KEY} must be on the line of its key")
        value = _string(node, key, f"{what}.tls")
        if value is None:
            continue
        if key == PIN_KEY:
            try:
                value = normalize_fingerprint(value)
            except ValueError as e:
                _fail(node.key_line(key), f"{what}.tls.{PIN_KEY}: {e}")
        elif key == "trustStore":
            value = _expand(value)
        values[key] = value
        lines[key] = node.key_line(key)
    return _TlsBlock(kind, values, lines)


def _effective(key, *blocks):
    for block in blocks:
        if block is not None and block.get(key) is not None:
            return block.get(key)
    return None


def _base_url(text, number, what):
    if not text:
        _fail(number, f"{what}: baseUrl is missing")
    try:
        parts = urllib.parse.urlsplit(text)
        port = parts.port
    except ValueError:
        _fail(number, f"{what}: invalid baseUrl")
    scheme = parts.scheme.lower()
    if scheme not in ("https", "http"):
        _fail(number, f"{what}: baseUrl must use https (or http for a server without TLS, "
                      "which is skipped)")
    if not parts.hostname:
        _fail(number, f"{what}: baseUrl has no host")
    if parts.username is not None or parts.password is not None:
        _fail(number, f"{what}: baseUrl must not contain credentials")
    return text.rstrip("/"), scheme, parts.hostname, port or DEFAULT_HTTPS_PORT


def _same_file_key(path):
    return os.path.realpath(os.path.normpath(path))


def read_profile(path, expected_name=None):
    """The profile view of data-model.md, read-only. Raises ConfigError (no partial result)."""
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError as e:
        raise ConfigError(f"cannot read the profile file {path}: {e.strerror or e}") from None
    return parse_profile(path, data, expected_name)


def parse_profile(path, data, expected_name=None):
    """The profile view of the file content data (bytes) that was read from path."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise ConfigError(f"cannot read the profile file {path}: {e}") from None
    try:
        return _read_profile(path, text, expected_name)
    except ConfigError as e:
        raise ConfigError(f"{path}: {e}") from None


def _read_profile(path, text, expected_name):
    lines = _content_lines(text)
    if not lines:
        _fail(None, "the profile file is empty")
    if lines[0].indent != 0 or _is_sequence_item(lines[0].text):
        _fail(lines[0].number, "the top level must be a mapping")
    root = _parse_mapping(lines)

    anchors = {"*": _anchor_lines(lines)}
    for key in root.keys():
        rest = root.inline_text(key) or ""
        if key.startswith("x-") and rest.startswith("&"):
            node = root.get(key)
            anchors[node.anchor] = (node, root.key_line(key))

    profile_node = _mapping(root.get("profile"), "profile", 1, allow_flow=True)
    name = _string(profile_node, "name", "profile")
    if not name or not valid_profile_name(name):
        _fail(root.key_line("profile") if "profile" in root.keys() else None,
              f"profile.name is missing or invalid (expected {PROFILE_NAME.pattern}, "
              f"except {', '.join(sorted(RESERVED_PROFILE_NAMES))})")
    if expected_name is not None and name != expected_name:
        _fail(root.key_line("profile"), f"profile.name '{name}' differs from the requested "
                                        f"profile '{expected_name}'")

    credentials = _mapping(root.get("credentials"), "credentials", 1, allow_flow=True)
    env_prefix = _string(credentials, "envPrefix", "credentials")
    if env_prefix is None:
        env_prefix = DEFAULT_ENV_PREFIX_START + re.sub(r"[^A-Z0-9]", "_", name.upper())
    elif not ENV_PREFIX.fullmatch(env_prefix):
        _fail(root.key_line("credentials"), f"credentials.envPrefix must match "
                                            f"{ENV_PREFIX.pattern}")

    cert_check = _mapping(root.get("x-cert-check"), "x-cert-check", 1, allow_flow=True)
    if cert_check is not None:
        for key in cert_check.keys():
            if key not in CERT_CHECK_KEYS:
                _fail(cert_check.key_line(key), f"x-cert-check: unknown key '{key}'")
    java = _expand(_string(cert_check, "java", "x-cert-check"))
    server_jar = _expand(_string(cert_check, "serverJar", "x-cert-check"))

    defaults = _mapping(root.get("defaults"), "defaults", 1)
    defaults_tls = _tls_block(defaults.get("tls") if defaults else None, anchors, "defaults")

    groups = root.get("groups")
    if not isinstance(groups, _Sequence) or not groups.items:
        _fail(root.key_line("groups") if "groups" in root.keys() else None,
              "groups must be a non-empty block sequence of stages")
    stages = []
    for item in groups.items:
        stages.append(_read_stage(item, anchors, defaults_tls))
    names = [stage.name for stage in stages]
    for index, stage_name in enumerate(names):
        if stage_name in names[:index]:
            _fail(groups.items[index].number, f"stage '{stage_name}' is defined twice")
    return Profile(path=path, name=name, env_prefix=env_prefix, java=java,
                   server_jar=server_jar, stages=tuple(_finish_stages(stages)))


@dataclass
class _StageDraft:
    """A stage as read, before own_tls (which needs every stage) is known."""
    name: str
    pin: Optional[str]
    pin_source: str
    pin_line: Optional[int]
    trust_store: Optional[str]
    own_store: bool  # trustStore set in the stage's own inline tls block
    servers: list
    skipped: list
    server_ids: list


def _read_stage(item, anchors, defaults_tls):
    if not isinstance(item, _Mapping) or item.anchor is not None:
        _fail(item.number, "unsupported shape inside groups: each stage must be a block mapping")
    stage_name = _string(item, "name", "groups[]")
    if not stage_name or not STAGE_NAME.fullmatch(stage_name):
        _fail(item.number, f"stage name missing or invalid (expected {STAGE_NAME.pattern})")
    what = f"stage {stage_name}"
    group_tls = _tls_block(item.get("tls"), anchors, what)
    nodes = item.get("nodes")
    if not isinstance(nodes, _Sequence) or not nodes.items:
        _fail(item.number, f"{what}: nodes must be a non-empty block sequence")
    servers, skipped, node_names, node_blocks = [], [], [], []
    for node in nodes.items:
        if not isinstance(node, _Mapping) or node.anchor is not None:
            _fail(node.number, f"{what}: unsupported shape inside nodes: each server must be a "
                               "block mapping")
        node_name = _string(node, "name", f"{what}.nodes[]")
        if not node_name or not STAGE_NAME.fullmatch(node_name):
            _fail(node.number, f"{what}: server name missing or invalid "
                               f"(expected {STAGE_NAME.pattern})")
        server_id = f"{stage_name}/{node_name}"
        server_what = f"server {server_id}"
        if node_name in node_names:
            _fail(node.number, f"{server_what} is defined twice")
        node_names.append(node_name)
        url_line = node.key_line("baseUrl") if "baseUrl" in node.keys() else node.number
        base_url, scheme, host, port = _base_url(_string(node, "baseUrl", server_what),
                                                 url_line, server_what)
        node_tls = _tls_block(node.get("tls"), anchors, server_what)
        if scheme == "http":  # no certificate: not contacted, not part of the evaluation
            skipped.append(SkippedServer(id=server_id, stage=stage_name, name=node_name,
                                         base_url=base_url))
            continue
        node_blocks.append(node_tls)
        servers.append(Server(
            id=server_id, stage=stage_name, name=node_name, base_url=base_url, host=host,
            port=port, pin=_effective(PIN_KEY, node_tls, group_tls, defaults_tls),
            trust_store=_effective("trustStore", node_tls, group_tls, defaults_tls)))

    if any(block is not None and (block.get(PIN_KEY) or block.get("trustStore"))
           for block in node_blocks):
        pin_source = "server"
    elif group_tls is not None and group_tls.kind == "alias":
        pin_source = "alias"
    elif group_tls is not None and (group_tls.get(PIN_KEY) or defaults_tls is None
                                    or defaults_tls.get(PIN_KEY) is None):
        pin_source = "stage"
    else:
        pin_source = "defaults"
    return _StageDraft(
        name=stage_name, pin=_effective(PIN_KEY, group_tls, defaults_tls),
        pin_source=pin_source,
        pin_line=group_tls.lines.get(PIN_KEY) if pin_source == "stage" else None,
        trust_store=_effective("trustStore", group_tls, defaults_tls),
        own_store=pin_source == "stage" and group_tls.get("trustStore") is not None,
        servers=servers, skipped=skipped,
        server_ids=[f"{stage_name}/{node_name}" for node_name in node_names])


def _finish_stages(drafts):
    """Stages with own_tls: own pin line, own trustStore in the stage's inline tls block, and a
    trust store (compared by real path) no other stage uses."""
    stores = {}
    for draft in drafts:
        for server in draft.servers:
            if server.trust_store is not None:
                stores.setdefault(_same_file_key(server.trust_store), set()).add(draft.name)
    for draft in drafts:
        own_tls = (draft.pin_source == "stage" and draft.pin_line is not None
                   and draft.own_store
                   and stores.get(_same_file_key(draft.trust_store), {draft.name})
                   == {draft.name})
        yield Stage(name=draft.name, pin=draft.pin, pin_source=draft.pin_source,
                    pin_line=draft.pin_line, trust_store=draft.trust_store, own_tls=own_tls,
                    servers=tuple(draft.servers), skipped_servers=tuple(draft.skipped),
                    server_ids=tuple(draft.server_ids))


# --- child processes -----------------------------------------------------------------------------
#
# openssl, keytool and the server's --check-config run with a minimal environment: never the
# operator's environment, which may hold credentials (<PREFIX>_<GROUP>_PASSWORD) or variables that
# change the programs' behaviour (OPENSSL_CONF, JAVA_TOOL_OPTIONS, ...).

SYSTEM_PATH = ("/usr/bin", "/bin")


def child_environment(path_dirs=()):
    """PATH (the given directories, then /usr/bin:/bin), HOME and LC_ALL=C; nothing else.
    LC_ALL=C makes the output the tool parses (openssl dates, keytool listings, the
    --check-config summary) independent of the operator's language settings."""
    path = [directory for directory in path_dirs if directory] + list(SYSTEM_PATH)
    return {"PATH": ":".join(dict.fromkeys(path)),
            "HOME": os.environ.get("HOME") or os.path.expanduser("~"), "LC_ALL": "C"}


def run_process(argv, input=None, timeout=None, env=None):
    """The default process runner: argv only (no shell), stdin /dev/null unless input is given,
    output captured as text; raises subprocess.TimeoutExpired after killing the process."""
    return subprocess.run(argv, input=input, stdin=None if input is not None
                          else subprocess.DEVNULL, capture_output=True, text=True,
                          errors="replace", timeout=timeout,
                          env=env if env is not None else child_environment())


# --- certificate fetch (research R-10) -----------------------------------------------------------
#
# Per server: resolve the host once, then `openssl s_client -connect <ip>:<port> -servername
# <host> -showcerts` (stdin /dev/null, killed at the deadline), and the first (leaf) certificate
# through `openssl x509`. Connecting to the resolved address guarantees that the reported IP is the
# one whose certificate was read. The PEM stays in memory; it is never logged or written.

FETCH_TIMEOUT = 5.0  # seconds per server (FR-005)
SYSTEM_OPENSSL = "/usr/bin/openssl"
ISO_UTC = "%Y-%m-%dT%H:%M:%SZ"
_PEM = re.compile(r"-----BEGIN CERTIFICATE-----\r?\n.*?-----END CERTIFICATE-----", re.DOTALL)
_DN_SLASH = re.compile(r"/(?=[A-Za-z][A-Za-z0-9.]*=)")


@dataclass(frozen=True)
class Presented:
    """The certificate a server presented in one check run, or why it could not be read
    (error: timeout | dns | connect | tls)."""
    server_id: str
    ip: Optional[str]
    error: Optional[str] = None
    fingerprint: Optional[str] = None
    subject: Optional[str] = None
    issuer: Optional[str] = None
    not_before: Optional[str] = None
    not_after: Optional[str] = None
    valid_now: Optional[bool] = None
    pem: Optional[str] = field(default=None, repr=False)

    @property
    def reachable(self):
        return self.error is None


def find_openssl():
    """/usr/bin/openssl (LibreSSL on macOS) first, else openssl on PATH, else None."""
    if os.access(SYSTEM_OPENSSL, os.X_OK):
        return SYSTEM_OPENSSL
    return shutil.which("openssl")


def resolve_host(host, port):
    """The first IPv4/IPv6 address of host (socket.gaierror if there is none)."""
    for family, _type, _proto, _name, address in socket.getaddrinfo(
            host, port, type=socket.SOCK_STREAM):
        if family in (socket.AF_INET, socket.AF_INET6):
            return address[0]
    raise socket.gaierror(socket.EAI_NONAME, "no IPv4 or IPv6 address")


def connect_address(ip, port):
    return f"[{ip}]:{port}" if ":" in ip else f"{ip}:{port}"


def _is_ip_literal(host):
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def first_certificate(text):
    """The first PEM certificate of `openssl s_client -showcerts` output, or None."""
    match = _PEM.search(text or "")
    return match.group(0) + "\n" if match else None


def _distinguished_name(value):
    """One-line "CN=x, O=y" from LibreSSL ("/CN=x/O=y") or OpenSSL 3 ("CN = x, O = y")."""
    value = value.strip()
    if value.startswith("/"):
        return ", ".join(part for part in _DN_SLASH.split(value[1:]) if part)
    return value.replace(" = ", "=")


def _openssl_date(value):
    """ISO-8601 UTC from "Oct  8 13:06:26 2026 GMT" or "2026-10-08 13:06:26Z"."""
    text = " ".join(value.split())
    for pattern in ("%b %d %H:%M:%S %Y GMT", "%Y-%m-%d %H:%M:%SZ"):
        try:
            return datetime.datetime.strptime(text, pattern).strftime(ISO_UTC)
        except ValueError:
            continue
    raise ValueError(f"unknown date format: {text}")


def validity_problem(not_before, not_after, valid_now, now=None):
    """None, "expired" or "not yet valid" for a presented certificate."""
    if valid_now or valid_now is None:
        return None
    moment = now or datetime.datetime.now(datetime.timezone.utc)
    return "expired" if not_after and moment > _parse_iso(not_after) else "not yet valid"


def _parse_iso(value):
    return datetime.datetime.strptime(value, ISO_UTC).replace(tzinfo=datetime.timezone.utc)


def parse_x509_output(text):
    """The fields of `openssl x509 -noout -fingerprint -sha256 -subject -issuer -dates`
    (LibreSSL and OpenSSL 3 formats). Raises ValueError when one is missing."""
    info = {}
    for line in (text or "").splitlines():
        key, sep, value = line.partition("=")
        if not sep:
            continue
        key = key.strip()
        if key.lower() == "sha256 fingerprint":
            info["fingerprint"] = normalize_fingerprint(value)
        elif key in ("subject", "issuer"):
            info[key] = _distinguished_name(value)
        elif key in ("notBefore", "notAfter"):
            info[key] = _openssl_date(value)
    missing = [key for key in ("fingerprint", "subject", "issuer", "notBefore", "notAfter")
               if key not in info]
    if missing:
        raise ValueError(f"openssl x509 output without {', '.join(missing)}")
    return info


def _resolve_within(resolver, host, port, deadline):
    """(ip, None) or (None, "dns" | "timeout"); a resolver still busy at the deadline is left
    behind in its daemon thread."""
    box = {}

    def work():
        try:
            box["ip"] = resolver(host, port)
        except Exception as e:  # noqa: BLE001 - any resolver failure is "dns"
            box["error"] = e

    thread = threading.Thread(target=work, daemon=True)
    thread.start()
    thread.join(max(0.0, deadline - time.monotonic()))
    if thread.is_alive():
        return None, "timeout"
    if "error" in box or not box.get("ip"):
        return None, "dns"
    return box["ip"], None


def fetch_certificate(server, deadline=None, resolver=None, openssl=None, log=None, now=None,
                      runner=None):
    """The certificate `server` presents now (Presented). deadline is an absolute
    time.monotonic() value (default: now + FETCH_TIMEOUT) that no step overruns: whatever
    finishes after it counts as "timeout". resolver(host, port) -> ip, the openssl path, the
    process runner and log(message) can be injected. Raises ToolError only if openssl cannot be
    run at all."""
    if deadline is None:
        deadline = time.monotonic() + FETCH_TIMEOUT
    log = log or (lambda message: None)
    runner = runner or run_process

    def unreachable(ip, error, detail=""):
        log(f"{server.id}: unreachable ({error}){' at ' + ip if ip else ''}"
            f"{': ' + detail if detail else ''}")
        return Presented(server_id=server.id, ip=ip, error=error)

    def run(argv, input=None):
        """The step's result, or None when the deadline is (or was) reached."""
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            result = runner(argv, input=input, timeout=remaining, env=environment)
        except subprocess.TimeoutExpired:
            return None
        except OSError as e:
            raise ToolError(f"cannot run {openssl}: {e.strerror or e}") from None
        return result if time.monotonic() <= deadline else None

    ip, error = _resolve_within(resolver or resolve_host, server.host, server.port, deadline)
    if error:
        return unreachable(None, error)
    openssl = openssl or find_openssl()
    if openssl is None:
        raise ToolError("openssl not found (expected /usr/bin/openssl or openssl on PATH)")
    environment = child_environment([os.path.dirname(openssl)])
    argv = [openssl, "s_client", "-connect", connect_address(ip, server.port), "-showcerts"]
    if not _is_ip_literal(server.host):
        argv[4:4] = ["-servername", server.host]
    result = run(argv)
    if result is None:
        return unreachable(ip, "timeout")
    pem = first_certificate(result.stdout)
    if pem is None:
        connected = "CONNECTED(" in (result.stdout or "")
        reason = (result.stderr or "").strip().splitlines()
        return unreachable(ip, "tls" if connected else "connect", reason[0][:200] if reason else "")
    parsed = run([openssl, "x509", "-noout", "-fingerprint", "-sha256", "-subject", "-issuer",
                  "-dates"], input=pem)
    if parsed is None:
        return unreachable(ip, "timeout")
    try:
        info = parse_x509_output(parsed.stdout)
    except ValueError as e:
        return unreachable(ip, "tls", f"unreadable certificate ({e})")
    moment = now or datetime.datetime.now(datetime.timezone.utc)
    log(f"{server.id}: presents {info['fingerprint']} at {ip}")
    return Presented(
        server_id=server.id, ip=ip, fingerprint=info["fingerprint"], subject=info["subject"],
        issuer=info["issuer"], not_before=info["notBefore"], not_after=info["notAfter"],
        valid_now=_parse_iso(info["notBefore"]) <= moment <= _parse_iso(info["notAfter"]),
        pem=pem)


# --- state directory (contract C-6, research R-8) ------------------------------------------------

STATE_DIRECTORY = os.path.join("~", ".inubit-mcp")
DIAG_LOG_LIMIT = 1024 * 1024  # bytes; the diagnostic log is rotated to ".1" beyond this
LOCK_TIMEOUT = 30  # seconds a blocking acquire (--accept, --prune) waits for the profile lock
LOCK_POLL = 0.1
REJECTIONS_LOCK_TIMEOUT = 2.0  # seconds; the short lock around read-modify-write of rejections
OUTCOMES = ("adopted", "rejected", "timed-out", "dialog-failed", "failed", "pruned")
ACTORS = ("dialog", "claude", "cli")
REMEMBERED_OUTCOMES = ("rejected", "timed-out")
REJECTION_KEYS = ("fingerprint", "notBefore", "outcome", "at")
_LOG_VALUE = re.compile(r"\S+")


class StateError(ToolError):
    """A state file exists but cannot be used (e.g. cert-rejections.json is not valid)."""


class StateBusy(StateError):
    """The rejection store stayed locked by another process for REJECTIONS_LOCK_TIMEOUT."""


def default_state_dir(profile_name):
    """~/.inubit-mcp/<profile.name>, the server's per-profile directory."""
    return os.path.join(os.path.expanduser(STATE_DIRECTORY), profile_name)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def _timestamp(now=None):
    return (now or utc_now()).astimezone(datetime.timezone.utc).strftime(ISO_UTC)


def _append(path, text):
    """Appends with O_APPEND (one write per call), creating the file owner-only."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.fchmod(fd, 0o600)
        os.write(fd, text.encode("utf-8"))
    finally:
        os.close(fd)


class FileChanged(ToolError):
    """The file is no longer the one that was read (another process changed it)."""


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def write_atomically(path, data, mode=None, expect_sha256=None):
    """Replaces the file path resolves to (a symlink stays a symlink) with bytes: temporary file
    in the same directory, fsync, rename, fsync of the directory. The file keeps its mode; a new
    file gets mode (default 0600). With expect_sha256 the current content is compared just before
    the rename; a mismatch raises FileChanged and leaves the file as it is."""
    target = os.path.realpath(path)
    directory = os.path.dirname(target)
    if mode is None:
        try:
            mode = stat.S_IMODE(os.stat(target).st_mode)
        except FileNotFoundError:
            mode = 0o600
    fd, temporary = tempfile.mkstemp(prefix="." + os.path.basename(target) + ".",
                                     suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "wb") as handle:
            os.fchmod(handle.fileno(), mode)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        if expect_sha256 is not None:
            with open(target, "rb") as current:
                if sha256_hex(current.read()) != expect_sha256:
                    raise FileChanged(f"{path} changed while it was being edited")
        os.replace(temporary, target)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise
    directory_fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def _lock_fd(fd, timeout):
    """flock(LOCK_EX) on fd, waiting at most timeout seconds (0: one try); True if held."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(LOCK_POLL, remaining))


class Lock:
    """A held flock on the profile's lock file; released by release() or when the process dies."""

    def __init__(self, fd):
        self._fd = fd

    def fileno(self):
        """The locked file descriptor (None once released or detached), e.g. for pass_fds."""
        return self._fd

    def detach(self):
        """Closes this process's descriptor WITHOUT unlocking: a child that inherited the
        descriptor (same open file description) keeps holding the lock (contract C-3 step 5)."""
        if self._fd is not None:
            os.close(self._fd)
            self._fd = None

    def release(self):
        if self._fd is not None:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_UN)
            finally:
                os.close(self._fd)
                self._fd = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.release()


class State:
    """The state directory of one profile: change log, diagnostic log, remembered rejections,
    lock, dialog worker status. Nothing is created before it is needed."""

    def __init__(self, directory, profile_name):
        self.directory = directory
        self.profile_name = profile_name
        self.changes_log = os.path.join(directory, "cert-changes.log")
        self.rejections_file = os.path.join(directory, "cert-rejections.json")
        self.diag_log = os.path.join(directory, "cert-check.log")
        self.lock_file = os.path.join(directory, "cert-check.lock")
        self.rejections_lock_file = self.rejections_file + ".lock"

    def dialog_status_file(self, pid):
        return os.path.join(self.directory, f"cert-dialog-{pid}.json")

    def ensure(self):
        """Creates the directory and its missing parents, each with mode 0700."""
        missing = []
        path = os.path.abspath(self.directory)
        while not os.path.isdir(path):
            missing.append(path)
            parent = os.path.dirname(path)
            if parent == path:
                break
            path = parent
        for path in reversed(missing):
            try:
                os.mkdir(path, 0o700)
            except FileExistsError:
                continue
            os.chmod(path, 0o700)

    # change log ----------------------------------------------------------------------------------

    def log_change(self, stage, outcome, old, new, not_before, by, now=None):
        """Appends one decision line (contract C-6); values must not contain spaces."""
        if outcome not in OUTCOMES:
            raise ValueError(f"unknown outcome {outcome!r}")
        if by not in ACTORS:
            raise ValueError(f"unknown actor {by!r}")
        values = [("profile", self.profile_name), ("stage", stage), ("outcome", outcome),
                  ("old", old), ("new", new), ("notBefore", not_before), ("by", by)]
        fields = []
        for key, value in values:
            value = "-" if value is None else str(value)
            if not _LOG_VALUE.fullmatch(value):
                raise ValueError(f"log value {key} must be one word")
            fields.append(f"{key}={value}")
        self.ensure()
        _append(self.changes_log, f"{_timestamp(now)} {' '.join(fields)}\n")

    def check_appendable(self):
        """Raises OSError unless the change log can be appended to (created if missing)."""
        self.ensure()
        fd = os.open(self.changes_log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        os.close(fd)

    # diagnostic log ------------------------------------------------------------------------------

    def diag(self, message, now=None):
        """Appends one line to the diagnostic log; rotates it to ".1" at DIAG_LOG_LIMIT."""
        self.ensure()
        try:
            if os.path.getsize(self.diag_log) >= DIAG_LOG_LIMIT:
                os.replace(self.diag_log, self.diag_log + ".1")
        except FileNotFoundError:
            pass
        _append(self.diag_log, f"{_timestamp(now)} {' '.join(str(message).split())}\n")

    # remembered rejections -----------------------------------------------------------------------

    def load_rejections(self):
        """{stage: {fingerprint, notBefore, outcome, at}}; {} without a file."""
        try:
            with open(self.rejections_file, encoding="utf-8") as handle:
                data = json.load(handle)
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as e:
            raise StateError(f"cannot read {self.rejections_file}: {e}") from None
        if not isinstance(data, dict):
            raise StateError(f"{self.rejections_file}: expected an object per stage")
        for stage, entry in data.items():
            if not isinstance(entry, dict) or set(entry) != set(REJECTION_KEYS) \
                    or not isinstance(entry["fingerprint"], str) \
                    or entry["outcome"] not in REMEMBERED_OUTCOMES:
                raise StateError(f"{self.rejections_file}: invalid entry for stage {stage}")
        return data

    def save_rejections(self, rejections):
        self.ensure()
        data = json.dumps(rejections, indent=2, sort_keys=True) + "\n"
        write_atomically(self.rejections_file, data.encode("utf-8"), mode=0o600)

    def rejection(self, stage):
        return self.load_rejections().get(stage)

    @contextlib.contextmanager
    def _rejections_locked(self):
        """A short lock around read-modify-write of the store, separate from the profile lock
        (so --check is never held up by a running dialog worker); StateBusy after
        REJECTIONS_LOCK_TIMEOUT."""
        self.ensure()
        fd = os.open(self.rejections_lock_file, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            os.fchmod(fd, 0o600)
            if not _lock_fd(fd, REJECTIONS_LOCK_TIMEOUT):
                raise StateBusy(f"{self.rejections_file} is locked by another process")
            yield
        finally:
            os.close(fd)  # closing the only descriptor releases the flock

    def remember_rejection(self, stage, fingerprint, not_before, outcome, now=None):
        """Remembers a rejected or timed-out fingerprint of a stage (FR-019a)."""
        if outcome not in REMEMBERED_OUTCOMES:
            raise ValueError(f"only {' and '.join(REMEMBERED_OUTCOMES)} are remembered")
        with self._rejections_locked():
            rejections = self.load_rejections()
            rejections[stage] = {"fingerprint": fingerprint, "notBefore": not_before,
                                 "outcome": outcome, "at": _timestamp(now)}
            self.save_rejections(rejections)

    def clear_rejection(self, stage):
        """Forgets the stage's remembered rejection; True if there was one."""
        with self._rejections_locked():
            rejections = self.load_rejections()
            if stage not in rejections:
                return False
            del rejections[stage]
            self.save_rejections(rejections)
            return True

    # lock ----------------------------------------------------------------------------------------

    def _open_lock_file(self):
        self.ensure()
        fd = os.open(self.lock_file, os.O_RDWR | os.O_CREAT, 0o600)
        os.fchmod(fd, 0o600)
        return fd

    def try_lock(self):
        """The profile lock if it is free right now, else None."""
        return self.acquire_lock(timeout=0)

    def acquire_lock(self, timeout=LOCK_TIMEOUT):
        """The profile lock, waiting at most timeout seconds; None ("busy") after that."""
        fd = self._open_lock_file()
        if _lock_fd(fd, timeout):
            return Lock(fd)
        os.close(fd)
        return None

    def adopt_lock(self, fd):
        """The profile lock from a descriptor inherited from the parent (dialog worker). The
        descriptor must refer to this profile's lock file; its open file description holds the
        lock already (or takes it here when nobody holds it)."""
        try:
            held, expected = os.fstat(fd), os.stat(self.lock_file)
        except OSError as e:
            raise StateError(f"cannot adopt the profile lock: {e.strerror or e}") from None
        if (held.st_dev, held.st_ino) != (expected.st_dev, expected.st_ino):
            raise StateError("cannot adopt the profile lock: descriptor of another file")
        if not _lock_fd(fd, 0):  # succeeds only for the open file description holding it
            raise StateError("cannot adopt the profile lock: it is held by another process")
        return Lock(fd)


# --- trust store (research R-5) ------------------------------------------------------------------
#
# The stage trust stores are password-less PKCS12 files (docs/setup.md section 5). keytool needs a
# -storepass but does not use it when both PKCS12 algorithms are NONE. keytool is the one of the
# JDK that runs the server (sibling of x-cert-check.java); it always gets an argv, never a shell
# line, and certificates are passed on stdin, so a PEM is never written to disk.

KEYTOOL_TIMEOUT = 60  # seconds
STORE_OPTIONS = ("-storetype", "PKCS12", "-storepass", "unused",
                 "-J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE",
                 "-J-Dkeystore.pkcs12.macAlgorithm=NONE")
ENGLISH_OUTPUT = ("-J-Duser.language=en", "-J-Duser.country=US")
_KEYTOOL_ALIAS = re.compile(r"(?:Alias name|Aliasname):\s*(.+?)\s*$")
_KEYTOOL_SHA256 = re.compile(r"SHA-?256:\s*([0-9A-Fa-f:]+)\s*$")


class KeytoolError(ToolError):
    """keytool could not be run or reported an error."""


@dataclass(frozen=True)
class TrustEntry:
    alias: str
    fingerprint: str


def trust_alias(stage, fingerprint, date=None):
    """The alias of an adopted certificate: <stage>-<first 8 hex digits, lower case>-<yyyymmdd>
    (date in UTC, default today)."""
    day = date or utc_now().date()
    return f"{stage}-{normalize_fingerprint(fingerprint).replace(':', '')[:8].lower()}-" \
           f"{day.strftime('%Y%m%d')}"


def parse_keytool_list(text):
    """[TrustEntry] from `keytool -list -v` (English or German output)."""
    entries = []
    alias = None
    for line in (text or "").splitlines():
        match = _KEYTOOL_ALIAS.match(line.strip())
        if match:
            if alias is not None:
                raise KeytoolError(f"keytool listed no SHA-256 fingerprint for alias {alias}")
            alias = match.group(1)
            continue
        match = _KEYTOOL_SHA256.match(line.strip())
        if match and alias is not None:
            try:
                fingerprint = normalize_fingerprint(match.group(1))
            except ValueError:
                raise KeytoolError(f"keytool listed an invalid fingerprint for alias {alias}") \
                    from None
            entries.append(TrustEntry(alias, fingerprint))
            alias = None
    if alias is not None:
        raise KeytoolError(f"keytool listed no SHA-256 fingerprint for alias {alias}")
    return entries


class Keytool:
    """List, import and delete entries of a password-less PKCS12 trust store."""

    def __init__(self, path, runner=None):
        self.path = path
        self._runner = runner or run_process

    @classmethod
    def for_java(cls, java, runner=None):
        """The keytool next to the configured java binary."""
        return cls(os.path.join(os.path.dirname(java), "keytool"), runner=runner)

    def _run(self, action, store, extra, input=None, timeout=None):
        argv = [self.path, *ENGLISH_OUTPUT, action, *extra, "-keystore", store, *STORE_OPTIONS]
        timeout = timeout or KEYTOOL_TIMEOUT
        try:
            result = self._runner(argv, input=input, timeout=timeout,
                                  env=child_environment([os.path.dirname(self.path)]))
        except subprocess.TimeoutExpired:
            raise KeytoolError(f"keytool {action} timed out after {timeout} s") from None
        except OSError as e:
            raise KeytoolError(f"cannot run {self.path}: {e.strerror or e}") from None
        if result.returncode != 0:
            output = (result.stdout or "") + "\n" + (result.stderr or "")
            reason = next((line.strip() for line in output.splitlines()
                           if line.strip() and "CERTIFICATE" not in line), "no output")
            raise KeytoolError(f"keytool {action} failed (exit {result.returncode}): "
                               f"{reason[:300]}")
        return result.stdout or ""

    def list_entries(self, store, timeout=None):
        return parse_keytool_list(self._run("-list", store, ["-v"], timeout=timeout))

    def import_cert(self, store, pem, alias):
        """Adds the PEM certificate (given on stdin) as a trusted entry under alias."""
        self._run("-importcert", store, ["-noprompt", "-alias", alias], input=pem)

    def delete_entry(self, store, alias):
        self._run("-delete", store, ["-noprompt", "-alias", alias])


# --- check (contract C-1, data-model "Check result") ---------------------------------------------

CHECK_WAIT = 5.5  # seconds the main thread waits for all fetches (each has FETCH_TIMEOUT)
CHECK_LIST_TIMEOUT = 15  # seconds for one trust-store listing of --check
STATUS_ORDER = {"ok": 0, "unreachable": 1, "changed": 2}  # worst wins
SHARED_TLS = "shared TLS settings — give the stage its own tls block first"


def make_keytool(java):
    """The keytool of the JDK that runs the server (a test replaces this function)."""
    return Keytool.for_java(java)


@dataclass(frozen=True)
class ServerResult:
    server: Server
    presented: Presented
    status: str  # ok | changed | unreachable


@dataclass(frozen=True)
class StageResult:
    stage: Stage
    servers: Tuple[ServerResult, ...]
    status: str
    conflict: bool
    offer: Optional[str]
    rejected: bool
    superseded: Optional[Tuple[TrustEntry, ...]]  # None: not inspected
    not_inspected: Optional[str]  # why the trust store was not inspected


@dataclass(frozen=True)
class CheckResult:
    profile: Profile
    checked_at: str
    stages: Tuple[StageResult, ...]
    exit_code: int

    def stage(self, name):
        return next((result for result in self.stages if result.stage.name == name), None)


def fetch_all(servers, log=None, started=None):
    """{server id: Presented} for all servers, fetched in parallel; every fetch has the deadline
    started + FETCH_TIMEOUT and the caller waits at most CHECK_WAIT in total. Daemon threads, so
    a hanging name lookup never delays the exit."""
    started = time.monotonic() if started is None else started
    deadline = started + FETCH_TIMEOUT
    results, errors = {}, []

    def work(server):
        try:
            results[server.id] = fetch_certificate(server, deadline=deadline, log=log)
        except Exception as e:  # noqa: BLE001 - reported by the caller's thread
            errors.append(e)

    threads = [threading.Thread(target=work, args=(server,), daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(max(0.0, started + CHECK_WAIT - time.monotonic()))
    if errors:
        raise errors[0]
    return {server.id: results.get(server.id) or Presented(server_id=server.id, ip=None,
                                                           error="timeout")
            for server in servers}


def server_status(pin, presented):
    if not presented.reachable:
        return "unreachable"
    return "ok" if pin is not None and presented.fingerprint == pin else "changed"


def evaluate_stage(stage, presented, rejection=None, superseded=None, not_inspected=None):
    """The stage's check result (pure): status, conflict, offer, rejected."""
    servers = tuple(ServerResult(server, presented[server.id],
                                 server_status(server.pin, presented[server.id]))
                    for server in stage.servers)
    status = max((result.status for result in servers), key=STATUS_ORDER.get, default="ok")
    fingerprints = {result.presented.fingerprint for result in servers
                    if result.presented.reachable}
    conflict = len(fingerprints) > 1
    offer = None
    if status == "changed" and not conflict and stage.own_tls and stage.pin is not None \
            and all(result.presented.reachable for result in servers):
        offer = next(iter(fingerprints))
    rejected = offer is not None and rejection is not None \
        and rejection.get("fingerprint") == offer
    return StageResult(stage=stage, servers=servers, status=status, conflict=conflict,
                       offer=offer, rejected=rejected, superseded=superseded,
                       not_inspected=not_inspected)


def superseded_entries(profile, stage):
    """(entries of the stage's trust store whose fingerprint differs from the pin, None) or
    (None, reason) when the store cannot be inspected."""
    if stage.pin is None:
        return None, "no pin configured"
    if profile.java is None:
        return None, "no x-cert-check.java configured"
    if stage.trust_store is None:
        return None, "no trust store configured"
    try:
        entries = make_keytool(profile.java).list_entries(stage.trust_store,
                                                          timeout=CHECK_LIST_TIMEOUT)
    except KeytoolError as e:
        return None, one_line(e)
    return tuple(entry for entry in entries if entry.fingerprint != stage.pin), None


def superseded_all(profile):
    """{stage name: superseded_entries(...)} for every stage, listed in parallel."""
    results = {}

    def work(stage):
        try:
            results[stage.name] = superseded_entries(profile, stage)
        except Exception as e:  # noqa: BLE001 - one unreadable store must not end the check
            results[stage.name] = (None, f"{type(e).__name__}: {one_line(e)}")

    threads = [threading.Thread(target=work, args=(stage,), daemon=True)
               for stage in profile.stages]
    for thread in threads:
        thread.start()
    deadline = time.monotonic() + CHECK_LIST_TIMEOUT + 5
    for thread in threads:
        thread.join(max(0.0, deadline - time.monotonic()))
    return {stage.name: results.get(stage.name, (None, "keytool did not answer in time"))
            for stage in profile.stages}


def run_check(profile, state, list_store=True, started=None):
    """Contract C-1 without output: fetch, evaluate, forget rejections of stages that are ok
    again (FR-019b); the trust-store listing only with list_store (never on the start-up path)."""
    servers = [server for stage in profile.stages for server in stage.servers]
    presented = fetch_all(servers, log=state.diag, started=started)
    try:
        rejections = state.load_rejections()
    except StateError as e:
        state.diag(f"remembered rejections ignored: {one_line(e)}")
        rejections = {}
    results = []
    listings = superseded_all(profile) if list_store else {}
    for stage in profile.stages:
        superseded, reason = listings.get(stage.name, (None, "not inspected on start-up"))
        result = evaluate_stage(stage, presented, rejections.get(stage.name), superseded, reason)
        if result.status == "ok" and stage.name in rejections:
            try:
                state.clear_rejection(stage.name)
                state.diag(f"stage {stage.name} presents its pin again: remembered rejection "
                           "cleared")
            except StateError as e:
                state.diag(f"stage {stage.name}: remembered rejection not cleared: "
                           f"{one_line(e)}")
        results.append(result)
    statuses = [server.status for result in results for server in result.servers]
    exit_code = CHANGED if "changed" in statuses else UNREACHABLE \
        if "unreachable" in statuses else OK
    return CheckResult(profile=profile, checked_at=_timestamp(), stages=tuple(results),
                       exit_code=exit_code)


def check_json(result):
    """The JSON document of contract C-1 (built field by field: a Presented holds the PEM)."""
    stages = []
    for stage_result in result.stages:
        stage = stage_result.stage
        stages.append({
            "stage": stage.name,
            "pin": stage.pin,
            "status": stage_result.status,
            "conflict": stage_result.conflict,
            "offer": stage_result.offer,
            "rejected": stage_result.rejected,
            "pinSource": stage.pin_source,
            "ownTls": stage.own_tls,
            "supersededTrustStoreEntries": None if stage_result.superseded is None else [
                {"alias": entry.alias, "fingerprint": entry.fingerprint}
                for entry in stage_result.superseded],
            "servers": [{
                "id": server.server.id,
                "baseUrl": server.server.base_url,
                "ip": server.presented.ip,
                "status": server.status,
                "pinned": server.server.pin,
                "presented": server.presented.fingerprint,
                "subject": server.presented.subject,
                "issuer": server.presented.issuer,
                "notBefore": server.presented.not_before,
                "notAfter": server.presented.not_after,
                "validNow": server.presented.valid_now,
                "error": server.presented.error,
            } for server in stage_result.servers],
        })
    return {
        "profile": result.profile.name,
        "checkedAt": result.checked_at,
        "exitCode": result.exit_code,
        "stages": stages,
        "skippedServers": [{"id": server.id, "reason": server.reason}
                           for server in result.profile.skipped_servers],
    }


def short_fingerprint(fingerprint):
    return f"{fingerprint[:11]}…{fingerprint[-8:]}" if fingerprint else "none"


def check_text(result, profile_option):
    """The human-readable report of contract C-1; profile_option e.g. "--profile acme"."""
    out = []
    for stage_result in result.stages:
        stage = stage_result.stage
        pin = f"pin {short_fingerprint(stage.pin)}" if stage.pin else "no pin configured"
        out.append(f"Stage {stage.name}  {stage_result.status.upper()}  ({pin})")
        for server in stage_result.servers:
            presented = server.presented
            out.append(f"  {server.server.id:<12} {server.status:<12} {presented.ip or '-'}"
                       + (f"  ({presented.error})" if presented.error else ""))
            if server.status == "changed":
                out.append(f"      old  {server.server.pin or 'none'}")
                out.append(f"      new  {presented.fingerprint}")
                out.append(f"      subject {presented.subject}  issuer {presented.issuer}")
                out.append(f"      valid {presented.not_before} … {presented.not_after}")
                problem = validity_problem(presented.not_before, presented.not_after,
                                           presented.valid_now)
                if problem == "expired":
                    out.append("      WARNING: the new certificate has expired")
                elif problem:
                    out.append("      WARNING: the new certificate is not yet valid")
        for skipped in stage.skipped_servers:
            out.append(f"  {skipped.id:<12} skipped ({skipped.reason})")
        if not stage.servers:
            out.append("  no TLS servers")
        if stage_result.superseded is None:
            out.append(f"  trust store not inspected: {stage_result.not_inspected}")
        elif stage_result.superseded:
            out.append("  superseded trust-store entries:")
            out.extend(f"      {entry.alias}  {entry.fingerprint}"
                       for entry in stage_result.superseded)
        else:
            out.append("  superseded trust-store entries: none")
        if stage_result.conflict:
            out.append("  conflict: the servers of this stage present different certificates"
                       " — no offer")
        if stage_result.offer:
            out.append(f"  offer: {PROG} {profile_option} --accept {stage.name} "
                       f"{stage_result.offer}"
                       + ("  (rejected earlier in the dialog)" if stage_result.rejected else ""))
        elif stage_result.status == "changed" and not stage.own_tls:
            out.append(f"  {'no pin configured' if stage.pin is None else SHARED_TLS}")
    return "\n".join(out) + "\n"


def emit(text):
    """Writes the report to stdout as UTF-8, whatever the locale says."""
    buffer = getattr(sys.stdout, "buffer", None)
    if buffer is not None:
        sys.stdout.flush()
        buffer.write(text.encode("utf-8"))
        buffer.flush()
    else:
        sys.stdout.write(text)


# --- common steps of the commands ----------------------------------------------------------------

def profile_file(args):
    return profile_path(args.profile) if args.profile is not None else args.config


def load_profile(args, data=None):
    """The profile of --profile/--config with the --java/--server-jar overrides; parsed from data
    (the bytes read from the file) when given."""
    path = profile_file(args)
    if data is None:
        profile = read_profile(path, expected_name=args.profile)
    else:
        profile = parse_profile(path, data, expected_name=args.profile)
    overrides = {}
    if args.java:
        overrides["java"] = os.path.abspath(os.path.expanduser(args.java))
    if args.server_jar:
        overrides["server_jar"] = os.path.abspath(os.path.expanduser(args.server_jar))
    return dataclasses.replace(profile, **overrides) if overrides else profile


def state_for(args, profile):
    directory = os.path.abspath(os.path.expanduser(args.state_dir)) if args.state_dir \
        else default_state_dir(profile.name)
    return use_state(State(directory, profile.name))


def command_options(args):
    """The options to repeat in a suggested command line (shell-quoted): the profile and every
    non-default --state-dir/--java/--server-jar."""
    options = ["--profile", args.profile] if args.profile else ["--config", args.config]
    for option, value in (("--state-dir", args.state_dir), ("--java", args.java),
                          ("--server-jar", args.server_jar)):
        if value:
            options += [option, value]
    return " ".join(shlex.quote(part) for part in options)


profile_option = command_options


def cmd_check(args):
    profile = load_profile(args)
    state = state_for(args, profile)
    result = run_check(profile, state, list_store=True)
    if args.json:
        emit(json.dumps(check_json(result), indent=2, ensure_ascii=False) + "\n")
    else:
        emit(check_text(result, profile_option(args)))
    return result.exit_code


# --- configuration check of the server (research R-6) ---------------------------------------------

CHECK_CONFIG_TIMEOUT = 60  # seconds
PLACEHOLDER = "cert-check-placeholder"  # never a real credential; --check-config needs a value
check_config_runner = None  # the process runner for --check-config (None: run_process)
_SUMMARY_SERVER = re.compile(  # "  <Node term> <stage>/<node>: ..." (any terminology)
    r"^  (?!- )\S.*? ([a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}):(?:\s|$)")


class ConfigCheckFailed(ToolError):
    """The server's --check-config did not accept the profile (or could not run)."""


def credential_variable(prefix, stage, kind):
    """<PREFIX>_<GROUP>_<KIND> as the server builds it (group upper-cased, others -> "_")."""
    return f"{prefix}_{re.sub(r'[^A-Z0-9]', '_', stage.upper())}_{kind}"


def check_config_environment(profile):
    """The minimal environment plus a placeholder username and password for every stage: the
    configuration check needs values, never the real secrets."""
    env = child_environment([os.path.dirname(profile.java)])
    for stage in profile.stages:
        for kind in ("USERNAME", "PASSWORD"):
            env[credential_variable(profile.env_prefix, stage.name, kind)] = PLACEHOLDER
    return env


def run_check_config(profile, path):
    """Runs `java -Duser.home=<HOME> -jar <serverJar> --config <path> --check-config`; returns
    the server ids of its
    summary ("  Server <stage>/<node>: ..."). Raises ConfigCheckFailed unless the exit status is
    0 and the summary ends with "Result: OK"."""
    env = check_config_environment(profile)
    # Java takes user.home from the account, not from $HOME; the server derives its default
    # audit and workspace directories from it, so it must follow the child's HOME.
    argv = [profile.java, f"-Duser.home={env['HOME']}", "-jar", profile.server_jar, "--config",
            path, "--check-config"]
    runner = check_config_runner or run_process
    try:
        result = runner(argv, input=None, timeout=CHECK_CONFIG_TIMEOUT, env=env)
    except subprocess.TimeoutExpired:
        raise ConfigCheckFailed(f"--check-config timed out after {CHECK_CONFIG_TIMEOUT} s") \
            from None
    except OSError as e:
        raise ConfigCheckFailed(f"cannot run {profile.java}: {e.strerror or e}") from None
    lines = (result.stdout or "").splitlines()
    if result.returncode != 0 or "Result: OK" not in lines:
        reason = next((line.strip() for line in reversed(lines) if line.startswith("Result:")),
                      f"exit {result.returncode}")
        raise ConfigCheckFailed(f"--check-config refused the changed profile: {reason}")
    ids = []
    for line in lines:
        if line in ("Warnings:", "Errors:"):  # the server list ends here
            break
        match = _SUMMARY_SERVER.match(line)
        if match:
            ids.append(match.group(1))
    return ids


# --- adoption (contract C-2) and pruning (contract C-5) -------------------------------------------

_PIN_LINE = re.compile(r"""^(\s*(?:pinnedCertificateSha256|"pinnedCertificateSha256"|"""
                       r"""'pinnedCertificateSha256')[ ]*:[ ]+)(["']?)([0-9A-Fa-f:]+)\2"""
                       r"((?:\s+#.*)?\s*)$")
INTERRUPTING_SIGNALS = ("SIGINT", "SIGTERM", "SIGHUP")


class VerificationFailed(ToolError):
    """A check after a change did not hold; the change is rolled back."""


class Interrupted(BaseException):
    """SIGINT, SIGTERM or SIGHUP arrived while a change was in progress."""


class SignalGuard:
    """While a change is in progress, SIGINT/SIGTERM/SIGHUP raise Interrupted (once), so the
    change is rolled back instead of the process dying half-way. On an exceptional exit the
    signals are set to SIG_IGN (not back to their old handlers) so that a second signal cannot
    end the rollback; the caller calls restore() once the rollback is done. Main thread only
    (elsewhere signals cannot be handled)."""

    def __init__(self):
        self.previous = {}
        self.raised = []

    def _handler(self, signum, _frame):
        if not self.raised:
            self.raised.append(signum)
            raise Interrupted(signal.Signals(signum).name)

    def __enter__(self):
        if threading.current_thread() is threading.main_thread():
            for name in INTERRUPTING_SIGNALS:
                number = getattr(signal, name)
                self.previous[number] = signal.signal(number, self._handler)
        return self

    def __exit__(self, exc_type, _exc, _traceback):
        if exc_type is None:
            self.restore()
        else:
            for number in self.previous:
                signal.signal(number, signal.SIG_IGN)
        return False

    def restore(self):
        """The handlers from before the change (idempotent)."""
        for number, old in self.previous.items():
            signal.signal(number, old)
        self.previous = {}


@contextlib.contextmanager
def signals_ignored():
    """No interruption while a rollback restores files (main thread only)."""
    if threading.current_thread() is not threading.main_thread():
        yield
        return
    previous = {}
    try:
        for name in INTERRUPTING_SIGNALS:
            number = getattr(signal, name)
            previous[number] = signal.signal(number, signal.SIG_IGN)
        yield
    finally:
        for number, old in previous.items():
            signal.signal(number, old)


def replace_pin_line(text, line_number, old_pin, new_pin):
    """text with the value on line line_number replaced; indentation, the (quoted or plain) key,
    the value's quotes and the trailing comment are kept. Refuses a line without old_pin."""
    lines = text.split("\n")
    if not 1 <= line_number <= len(lines):
        raise VerificationFailed(f"line {line_number} no longer exists")
    line = lines[line_number - 1]
    ending = "\r" if line.endswith("\r") else ""
    match = _PIN_LINE.match(line[:len(line) - len(ending)])
    try:
        current = normalize_fingerprint(match.group(3)) if match else None
    except ValueError:
        current = None
    if current is None or current != old_pin:
        raise VerificationFailed(f"line {line_number} is no longer the stage's pin line")
    lines[line_number - 1] = (match.group(1) + match.group(2) + new_pin + match.group(2)
                              + match.group(4) + ending)
    return "\n".join(lines)


def make_backup(path, stamp, data):
    """Writes data to <path>.bak-<stamp> (-2, -3, ... appended on a collision), mode 0600."""
    candidate, n = f"{path}.bak-{stamp}", 1
    while True:
        try:
            fd = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            break
        except FileExistsError:
            n += 1
            candidate = f"{path}.bak-{stamp}-{n}"
    try:
        os.fchmod(fd, 0o600)
        view = memoryview(data)
        while view:
            view = view[os.write(fd, view):]
        os.fsync(fd)
    except BaseException:
        os.close(fd)
        os.unlink(candidate)
        raise
    os.close(fd)
    return candidate


class Change:
    """The files one change touches: original bytes and modes (for an exact restore) and their
    backups. Files are given as {real path: bytes read}."""

    def __init__(self, originals):
        self.originals = {path: (data, stat.S_IMODE(os.stat(path).st_mode))
                          for path, data in originals.items()}
        self.backups = []

    def back_up(self):
        """Backups of every file (one time stamp); OSError leaves no partial backup behind."""
        stamp = utc_now().strftime("%Y%m%d-%H%M%S")
        try:
            for path, (data, _mode) in self.originals.items():
                self.backups.append(make_backup(path, stamp, data))
        except OSError:
            self.discard_backups()
            raise

    def discard_backups(self):
        for backup in self.backups:
            try:
                os.unlink(backup)
            except OSError:
                pass
        self.backups = []

    def restore(self, paths=None):
        """Writes the original bytes back (of all files, or of paths); returns the failures
        (empty when all restored)."""
        failures = []
        with signals_ignored():
            for path, (data, mode) in self.originals.items():
                if paths is not None and path not in paths:
                    continue
                try:
                    with open(path, "rb") as handle:
                        if handle.read() == data and \
                                stat.S_IMODE(os.fstat(handle.fileno()).st_mode) == mode:
                            continue
                except OSError:
                    pass
                try:
                    write_atomically(path, data, mode=mode)
                except OSError as e:
                    failures.append(f"{path}: {e.strerror or e}")
        return failures


def _refuse(message):
    _report(f"refused: {message}")
    return REFUSED


def _try_log(state, *entry):
    """Writes a change-log line; on failure warns on stderr instead (returns False)."""
    try:
        state.log_change(*entry)
        return True
    except Exception as e:  # noqa: BLE001 - a missing audit line must not hide the outcome
        _report(f"WARNING: the audit line ({entry[1]}) could not be written to "
                f"{state.changes_log}: {one_line(e)}")
        return False


def _rolled_back(state, change, error, what, log_entry):
    """Restore after a failure or interruption: files, log line, report; exit status 4."""
    failures = change.restore()
    _try_log(state, *log_entry)
    reason = one_line(error) or type(error).__name__
    if isinstance(error, (Interrupted, KeyboardInterrupt)):
        reason = f"interrupted ({reason})"
    if failures:
        _report(f"{what} failed ({reason}) and the RESTORE FAILED for {'; '.join(failures)} — "
                f"restore by hand from the backups {', '.join(change.backups)}")
    else:
        _report(f"{what} failed, original files restored: {reason} "
                f"(backups {', '.join(change.backups)})")
    return ROLLED_BACK


def _unwritable_directory(paths):
    """The first directory of paths that is not writable (backups go there), or None."""
    for path in paths:
        directory = os.path.dirname(path)
        if not os.access(directory, os.W_OK | os.X_OK):
            return directory
    return None


def _usable_stage(profile, stage_name, command):
    """(stage, None) or (None, refusal message) for --accept / --prune."""
    stage = profile.stage(stage_name)
    if stage is None:
        return None, f"unknown stage '{stage_name}'"
    if not stage.own_tls:
        return None, f"stage {stage_name}: {SHARED_TLS}"
    if profile.java is None or (command == "accept" and profile.server_jar is None):
        needed = "x-cert-check.java and x-cert-check.serverJar" if command == "accept" \
            else "x-cert-check.java"
        return None, f"{needed} (or --java/--server-jar) required for --{command}"
    if not os.path.isfile(stage.trust_store):
        return None, f"stage {stage_name}: trust store {stage.trust_store} not found"
    return stage, None


def _lock_profile(state):
    """(lock, None) or (None, refusal message); the state directory and change log must be
    usable before anything is changed."""
    try:
        state.ensure()
    except OSError as e:
        return None, f"state directory {state.directory} cannot be created: {e.strerror or e}"
    try:
        state.check_appendable()
    except OSError as e:
        return None, f"the change log {state.changes_log} is not writable: {e.strerror or e}"
    lock = state.acquire_lock(timeout=LOCK_TIMEOUT)
    if lock is None:
        return None, (f"the profile lock is held by another process (adoption, prune or "
                      f"dialog) for more than {LOCK_TIMEOUT} s")
    return lock, None


def _read_bytes(path):
    with open(path, "rb") as handle:
        return handle.read()


def cmd_accept(args):
    stage_name, raw = args.accept
    profile = load_profile(args)
    state = state_for(args, profile)
    try:
        fingerprint = normalize_fingerprint(raw)
    except ValueError as e:
        return _refuse(f"malformed fingerprint: {e}")
    _stage, problem = _usable_stage(profile, stage_name, "accept")
    if problem:
        return _refuse(problem)
    lock, problem = _lock_profile(state)
    if problem:
        return _refuse(problem)
    try:
        return accept_locked(args, stage_name, fingerprint, args.by or "cli", state)
    finally:
        lock.release()


def accept_locked(args, stage_name, fingerprint, by, state):
    """Contract C-2 steps 1-7; the caller holds the profile lock (--accept, dialog worker).
    The profile bytes are read once here; parsing, backup, rollback and edit all use them."""
    path = os.path.realpath(profile_file(args))
    try:
        data = _read_bytes(path)
    except OSError as e:
        raise ConfigError(f"cannot read the profile file {path}: {e.strerror or e}") from None
    profile = load_profile(args, data)
    stage, problem = _usable_stage(profile, stage_name, "accept")
    if problem:
        return _refuse(problem)
    try:
        state.check_appendable()
    except OSError as e:
        return _refuse(f"the change log {state.changes_log} is not writable: {e.strerror or e}")
    if stage.pin == fingerprint:
        emit(f"Stage {stage_name} already pins {fingerprint}; nothing changed.\n")
        return OK
    if not stage.servers:
        return _refuse(f"stage {stage_name} has no https server to verify")

    # step 1: every server of the stage presents exactly that fingerprint right now
    presented = fetch_all(stage.servers, log=state.diag)
    deviating = []
    for server in stage.servers:
        result = presented[server.id]
        if not result.reachable:
            deviating.append(f"{server.id} unreachable ({result.error})")
        elif result.fingerprint != fingerprint:
            deviating.append(f"{server.id} presents {result.fingerprint}")
    if deviating:
        return _refuse(f"stage {stage_name}: not every server presents {fingerprint}: "
                       + "; ".join(deviating))
    leaf = presented[stage.servers[0].id]

    # step 2: backups next to the real files
    store = os.path.realpath(stage.trust_store)
    unwritable = _unwritable_directory([path, store])
    if unwritable:
        return _refuse(f"backup directory {unwritable} is not writable")
    try:
        change = Change({path: data, store: _read_bytes(store)})
        change.back_up()
    except OSError as e:
        return _refuse(f"backup failed: {e.strerror or e}")

    # steps 3-5, all-or-nothing (interruptions included). The trust store comes first: an
    # additional trusted certificate is harmless while the old pin still applies.
    log_entry = (stage_name, "failed", stage.pin, fingerprint, leaf.not_before, by)
    keytool = make_keytool(profile.java)
    alias = trust_alias(stage_name, fingerprint)
    expected = sha256_hex(data)
    guard = SignalGuard()
    try:
        return _change_and_report(args, state, profile, stage, change, leaf, keytool, alias,
                                  path, store, data, expected, fingerprint, by, guard,
                                  log_entry)
    finally:
        guard.restore()


def _change_and_report(args, state, profile, stage, change, leaf, keytool, alias, path, store,
                       data, expected, fingerprint, by, guard, log_entry):
    """Contract C-2 steps 3-7 for accept_locked (signals guarded by guard)."""
    stage_name = stage.name
    try:
        with guard:
            if sha256_hex(_read_bytes(path)) != expected:
                raise FileChanged(f"{path} changed")
            entries = keytool.list_entries(stage.trust_store)
            imported = fingerprint not in {entry.fingerprint for entry in entries}
            if imported:
                keytool.import_cert(stage.trust_store, leaf.pem, alias)
            edited = replace_pin_line(data.decode("utf-8"), stage.pin_line, stage.pin,
                                      fingerprint)
            write_atomically(path, edited.encode("utf-8"), expect_sha256=expected)
            listed = verify_adoption(profile, args, stage_name, fingerprint, keytool,
                                     stage.trust_store)
    except FileChanged:
        # Another program edited the profile: it is left as that program wrote it (no profile
        # restore), only the trust-store import is undone; a refusal, since nothing else changed.
        with signals_ignored():
            failures = change.restore(paths=[store])
            if failures:
                _try_log(state, *log_entry)
                _report(f"profile changed during adoption, and undoing the trust-store import "
                        f"FAILED for {'; '.join(failures)} — restore {store} by hand from "
                        f"{change.backups[1]}")
                return ROLLED_BACK
            change.discard_backups()
            return _refuse("profile changed during adoption (by another program); nothing "
                           "changed — run the command again")
    except BaseException as e:  # noqa: BLE001 - every failure after the backup is rolled back
        with signals_ignored():
            return _rolled_back(state, change, e, "adoption", log_entry)
    superseded = [entry for entry in listed if entry.fingerprint != fingerprint]

    # step 7
    logged = _try_log(state, stage_name, "adopted", stage.pin, fingerprint, leaf.not_before, by)
    try:
        if state.clear_rejection(stage_name):
            state.diag(f"stage {stage_name}: remembered rejection cleared by the adoption")
    except (StateError, OSError) as e:
        _report(f"stage {stage_name}: remembered rejection not cleared: {one_line(e)}")
    try:
        state.diag(f"stage {stage_name}: adopted {fingerprint} (by {by})")
    except OSError:
        pass
    emit(f"Stage {stage_name}: adopted {fingerprint}\n"
         f"  profile      {profile.path}\n"
         f"               backup {change.backups[0]}\n"
         f"  trust store  {stage.trust_store}"
         f" ({'entry ' + alias + ' added' if imported else 'certificate was already trusted'})\n"
         f"               backup {change.backups[1]}\n"
         f"Reconnect the MCP server so that the new pin takes effect: "
         f"{reconnect_hint(profile.name)}; other running sessions as well.\n"
         + (f"The trust store still holds {len(superseded)} superseded certificate(s) "
            f"(StartCLI still trusts them) until you remove them: {PROG} "
            f"{command_options(args)} --prune {stage_name}\n" if superseded else "")
         + ("" if logged else "WARNING: the audit line could not be written (see stderr).\n"))
    return OK


def verify_adoption(before, args, stage_name, fingerprint, keytool, trust_store):
    """Contract C-2 step 5: the server accepts the profile, the re-read profile has the new pin
    for this stage only, the server ids match, the trust store holds the new certificate.
    Returns the trust store's entries."""
    ids = run_check_config(before, before.path)
    after = load_profile(args)
    stage = after.stage(stage_name)
    if stage is None or stage.pin != fingerprint \
            or any(server.pin != fingerprint for server in stage.servers):
        raise VerificationFailed(f"the re-read profile does not pin {fingerprint} for stage "
                                 f"{stage_name}")
    for old in before.stages:
        if old.name == stage_name:
            continue
        new = after.stage(old.name)
        if new is None or new.pin != old.pin \
                or [s.pin for s in new.servers] != [s.pin for s in old.servers]:
            raise VerificationFailed(f"the pin of stage {old.name} changed as well")
    if sorted(ids) != sorted(after.server_ids()):
        raise VerificationFailed("the servers of --check-config differ from the profile's")
    listed = keytool.list_entries(trust_store)
    if fingerprint not in {entry.fingerprint for entry in listed}:
        raise VerificationFailed("the trust store does not list the new certificate")
    return listed


def cmd_prune(args):
    profile = load_profile(args)
    state = state_for(args, profile)
    stage, problem = _usable_stage(profile, args.prune, "prune")
    if problem:
        return _refuse(problem)
    if not args.yes:
        entries = make_keytool(profile.java).list_entries(stage.trust_store)
        superseded = [entry for entry in entries if entry.fingerprint != stage.pin]
        emit(_prune_listing(stage, superseded)
             + (f"Remove them with: {PROG} {command_options(args)} --prune {stage.name} --yes\n"
                if superseded else ""))
        return OK
    lock, problem = _lock_profile(state)
    if problem:
        return _refuse(problem)
    try:
        return _prune_locked(args, args.prune, state)
    finally:
        lock.release()


def _prune_listing(stage, superseded):
    head = f"Stage {stage.name} trust store {stage.trust_store} (pin {stage.pin}):\n"
    if not superseded:
        return head + "  superseded entries: none\n"
    return head + "  superseded entries:\n" + "".join(
        f"    {entry.alias}  {entry.fingerprint}\n" for entry in superseded)


def _prune_locked(args, stage_name, state):
    profile = load_profile(args)
    stage, problem = _usable_stage(profile, stage_name, "prune")
    if problem:
        return _refuse(problem)
    keytool = make_keytool(profile.java)
    entries = keytool.list_entries(stage.trust_store)
    if stage.pin not in {entry.fingerprint for entry in entries}:
        return _refuse(f"stage {stage_name}: the pinned certificate {stage.pin} is not in the "
                       f"trust store {stage.trust_store}; nothing removed")
    superseded = [entry for entry in entries if entry.fingerprint != stage.pin]
    if not superseded:
        emit(_prune_listing(stage, superseded))
        return OK
    store = os.path.realpath(stage.trust_store)
    unwritable = _unwritable_directory([store])
    if unwritable:
        return _refuse(f"backup directory {unwritable} is not writable")
    try:
        change = Change({store: _read_bytes(store)})
        change.back_up()
    except OSError as e:
        return _refuse(f"backup failed: {e.strerror or e}")
    guard = SignalGuard()
    try:
        with guard:
            for entry in superseded:
                keytool.delete_entry(stage.trust_store, entry.alias)
            remaining = keytool.list_entries(stage.trust_store)
            if stage.pin not in {entry.fingerprint for entry in remaining}:
                raise VerificationFailed("the pinned certificate is no longer in the trust store")
            if {entry.alias for entry in remaining} & {entry.alias for entry in superseded}:
                raise VerificationFailed("a superseded entry is still in the trust store")
    except BaseException as e:  # noqa: BLE001 - every failure after the backup is rolled back
        with signals_ignored():
            return _rolled_back(state, change, e, "prune",
                                (stage_name, "failed", None, stage.pin, None, "cli"))
    finally:
        guard.restore()
    for entry in superseded:
        _try_log(state, stage_name, "pruned", entry.fingerprint, stage.pin, None, "cli")
    try:
        state.diag(f"stage {stage_name}: pruned {len(superseded)} trust-store entries")
    except OSError:
        pass
    emit(f"Stage {stage_name}: removed from {stage.trust_store}:\n"
         + "".join(f"    {entry.alias}  {entry.fingerprint}\n" for entry in superseded)
         + f"  backup {change.backups[0]}\n")
    return OK


# --- start-up dialog (contract C-3, research R-2, R-8, R-11) -------------------------------------

OSASCRIPT = "/usr/bin/osascript"
DIALOG_GIVE_UP = 60  # seconds the dialog stays open
BUTTON_REJECT = "Reject"
BUTTON_ACCEPT = "Accept"
START_BUDGET = 20.0  # seconds the start waits for answers, from the start of the check
POLL_INTERVAL = 0.2  # seconds between two looks at the worker's status file
WORKER_GRACE = 0.6  # seconds the worker keeps its final status for the polling parent
dialog_runner = None  # the process runner for osascript (None: run_process)
_DIALOG_RESULTS = {
    f"button returned:{BUTTON_ACCEPT}, gave up:false": "accept",
    f"button returned:{BUTTON_REJECT}, gave up:false": "reject",
    "button returned:, gave up:true": "timeout",
}
_STATUS_FILE = re.compile(r"cert-dialog-(\d+)\.json")


def applescript_string(text):
    """An AppleScript string expression for text: quotes and backslashes escaped, line breaks
    joined with linefeed (so the script stays on one line)."""
    parts = str(text).split("\n")
    return " & linefeed & ".join(
        '"' + part.replace("\\", "\\\\").replace('"', '\\"') + '"' for part in parts)


def _utc_text(iso):
    return iso.replace("T", " ").replace("Z", " UTC") if iso else "?"


def ascii_fingerprint(fingerprint):
    """The first and the last 4 pairs, plain ASCII (dialog and notification texts)."""
    return f"{fingerprint[:11]}...{fingerprint[-11:]}" if fingerprint else "none"


def dialog_title(info):
    return f"INUBIT certificate changed - stage {info['stage']} (profile {info['profile']})"


def dialog_text(info, now=None):
    """The dialog text of contract C-3 for one offer (see offer_info). Plain ASCII: osascript
    runs with the minimal environment, where its decoding of other characters is not
    established."""
    lines = [f"Server {server['id']} ({server['ip']}) presents a new certificate."
             for server in info["servers"]]
    lines += ["", f"Old (pin): {ascii_fingerprint(info['pin'])}",
              f"New:       {ascii_fingerprint(info['fingerprint'])}",
              f"valid from: {_utc_text(info['notBefore'])}   until: {_utc_text(info['notAfter'])}"]
    problem = validity_problem(info["notBefore"], info["notAfter"], info["validNow"], now)
    if problem == "expired":
        lines.append("WARNING: The new certificate has expired.")
    elif problem:
        lines.append("WARNING: The new certificate is not yet valid.")
    lines += ["", "Old, full:", info["pin"][:48], info["pin"][48:],
              "New, full:", info["fingerprint"][:48], info["fingerprint"][48:],
              "", "Accept only if the change is expected (e.g. a redeploy)."]
    return "\n".join(lines)


def dialog_argv(info):
    script = (f"display dialog {applescript_string(dialog_text(info))} "
              f"with title {applescript_string(dialog_title(info))} "
              f'buttons {{"{BUTTON_REJECT}", "{BUTTON_ACCEPT}"}} '
              f'default button "{BUTTON_REJECT}" giving up after {DIALOG_GIVE_UP}')
    return [OSASCRIPT, "-e", script]


def parse_dialog_result(returncode, stdout):
    """accept | reject | timeout | dialog-failed (anything unexpected: the operator may not have
    seen the dialog, research R-2)."""
    if returncode != 0:
        return "dialog-failed"
    text = unicodedata.normalize("NFC", (stdout or "").strip())
    return _DIALOG_RESULTS.get(text, "dialog-failed")


def show_dialog(info, runner=None):
    """Shows the dialog for one offer and waits for the answer (at most DIALOG_GIVE_UP s)."""
    try:
        result = (runner or dialog_runner or run_process)(
            dialog_argv(info), input=None, timeout=DIALOG_GIVE_UP + 30, env=child_environment())
    except (OSError, subprocess.TimeoutExpired):
        return "dialog-failed"
    return parse_dialog_result(result.returncode, result.stdout)


def reconnect_hint(profile_name):
    """How to restart the MCP server with the new pin; the desktop app's Code tab has no
    /mcp Reconnect, a new session is needed there."""
    return (f"/mcp -> inubit-{profile_name} -> Reconnect, or start a new session "
            "(desktop Code tab)")


def notification_text(stage, profile_name):
    return (f"New certificate for stage {stage} adopted - reconnect inubit-{profile_name} "
            f"(/mcp -> Reconnect, or start a new session)")


def not_adopted_text(stage, outcome):
    return f"Accept for stage {stage} not adopted ({outcome}) - see cert-check.log"


def notify(text, runner=None):
    """A desktop notification (outside the MCP channel); True if osascript accepted it."""
    argv = [OSASCRIPT, "-e", f"display notification {applescript_string(text)} "
                             f"with title {applescript_string(PROG)}"]
    try:
        result = (runner or dialog_runner or run_process)(argv, input=None, timeout=15,
                                                          env=child_environment())
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def offer_info(profile, stage_result):
    """What the dialog worker asks about (the offers file holds a list of these)."""
    first = stage_result.servers[0].presented
    return {"stage": stage_result.stage.name, "profile": profile.name,
            "pin": stage_result.stage.pin, "fingerprint": stage_result.offer,
            "notBefore": first.not_before, "notAfter": first.not_after,
            "validNow": all(server.presented.valid_now for server in stage_result.servers),
            "servers": [{"id": server.server.id, "ip": server.presented.ip}
                        for server in stage_result.servers]}


def offers_path(status_file):
    return status_file[:-len(".json")] + ".offers.json"


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def _write_json(path, value):
    write_atomically(path, (json.dumps(value, indent=2) + "\n").encode("utf-8"), mode=0o600)


def _remove(*paths):
    for path in paths:
        try:
            os.unlink(path)
        except OSError:
            pass


def worker_options(args, state):
    """The parent's profile option, its state directory and any --java/--server-jar."""
    options = ["--profile", args.profile] if args.profile else ["--config", args.config]
    options += ["--state-dir", state.directory]
    if args.java:
        options += ["--java", args.java]
    if args.server_jar:
        options += ["--server-jar", args.server_jar]
    return options


def _no_dialog_reason(stage_result):
    if stage_result.stage.pin is None:
        return "no pin configured"
    if stage_result.conflict:
        return "the servers present different certificates"
    if any(not server.presented.reachable for server in stage_result.servers):
        return "not every server is reachable"
    return SHARED_TLS


def cmd_interactive(args):
    """Contract C-3, the launcher's start-up check: never writes to stdout, never reads stdin,
    returns after at most START_BUDGET seconds (main() turns every outcome into exit 0)."""
    started = time.monotonic()
    profile = load_profile(args)
    state = state_for(args, profile)
    result = run_check(profile, state, list_store=False, started=started)
    offers = []
    for stage_result in result.stages:
        name = stage_result.stage.name
        if stage_result.offer and stage_result.rejected:
            _report(f"WARNING: stage {name} presents the certificate {stage_result.offer} that "
                    f"was rejected earlier; no dialog (to be asked again: {PROG} "
                    f"{command_options(args)} --forget {name})")
        elif stage_result.offer:
            offers.append(offer_info(profile, stage_result))
        elif stage_result.status == "changed":
            state.diag(f"stage {name}: certificate changed, no dialog: "
                       f"{_no_dialog_reason(stage_result)}")
        elif stage_result.status == "unreachable":
            down = [server.server.id for server in stage_result.servers
                    if not server.presented.reachable]
            state.diag(f"stage {name}: unreachable: {', '.join(down)}")
    if not offers:
        return OK
    lock = state.try_lock()
    if lock is None:
        _report(f"dialog open in another session; no dialog for "
                f"{', '.join(offer['stage'] for offer in offers)}")
        return OK
    status_file = state.dialog_status_file(os.getpid())
    offers_file = offers_path(status_file)
    deadline = started + START_BUDGET
    worker = None
    try:
        _write_json(offers_file, {"lockFd": lock.fileno(),
                                  "parentDeadline": time.time() + deadline - time.monotonic(),
                                  "offers": offers})
        worker = subprocess.Popen(
            worker_command(status_file, worker_options(args, state)),
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True, pass_fds=[lock.fileno()], env=child_environment())
    except BaseException as e:  # noqa: BLE001 - also a signal during the handover
        if worker is None:
            lock.release()
            _remove(offers_file)
        else:
            lock.detach()  # the worker owns the lock and the offers file now
        if isinstance(e, OSError):
            _report(f"the dialog could not be started: {e.strerror or e}")
            _log_dialog_failed(state, offers)
            return OK
        raise
    lock.detach()  # the worker holds the lock now (same open file description); never LOCK_UN
    status, seen = None, False
    while time.monotonic() < deadline:
        current = _read_json(status_file)
        if current is not None:
            status, seen = current, True
            if not status.get("pending"):
                break
        elif worker.poll() is not None:
            break
        time.sleep(min(POLL_INTERVAL, max(0.0, deadline - time.monotonic())))
    final = _read_json(status_file)  # the last word, in case it came during the last sleep
    if final is not None:
        status, seen = final, True
    if not seen and worker.poll() is not None:
        _remove(offers_file)
        _report("the dialog could not be shown (see cert-check.log)")
        _log_dialog_failed(state, offers)
        return OK
    _report_dialog_outcomes(offers, status or {}, profile)
    return OK


def _log_dialog_failed(state, offers):
    """FR-019: a dialog that was never shown is logged (dialog-failed), but not remembered."""
    for offer in offers:
        _try_log(state, offer["stage"], "dialog-failed", offer["pin"], offer["fingerprint"],
                 offer["notBefore"], "dialog")


def _report_dialog_outcomes(offers, status, profile):
    """One stderr line per offered stage: what happened before the start continues."""
    done = status.get("done", {})
    for offer in offers:
        stage = offer["stage"]
        outcome = done.get(stage)
        if outcome == "adopted":
            _report(f"stage {stage}: Accept given, certificate adopted")
        elif outcome in ("refused", "failed"):
            _report(f"stage {stage}: Accept given, adoption {outcome} - see cert-check.log")
        elif outcome == "rejected":
            _report(f"stage {stage}: rejected in the dialog (remembered)")
        elif outcome == "timed-out":
            _report(f"stage {stage}: the dialog timed out (remembered)")
        elif outcome == "dialog-failed":
            _report(f"stage {stage}: the dialog could not be shown (see cert-check.log)")
        elif outcome:
            _report(f"stage {stage}: no dialog, decided in another session")
        elif status.get("adopting") == stage:
            _report(f"adoption for stage {stage} in progress - reconnect when notified "
                    f"({reconnect_hint(profile.name)})")
        else:
            _report(f"stage {stage}: the dialog is still open; the start continues (a later "
                    f"'{BUTTON_ACCEPT}' is still adopted; then reconnect: "
                    f"{reconnect_hint(profile.name)})")


def cmd_dialog_worker(args):
    """The detached worker of contract C-3 step 5: holds the profile lock, asks one stage after
    the other, adopts on "Accept" (FR-011 re-verification included), writes its status file
    after every decision and removes its files at the end, also after a failure. Never writes to
    stdout."""
    status_file = os.path.abspath(args.dialog_worker)
    own_name = bool(_STATUS_FILE.fullmatch(os.path.basename(status_file)))
    offers_file = offers_path(status_file) if own_name else None
    state = lock = None
    try:
        if not own_name:
            raise UsageError("the status file must be named cert-dialog-<pid>.json")
        profile = load_profile(args)
        state = state_for(args, profile)
        if os.path.dirname(status_file) != os.path.abspath(state.directory):
            offers_file = None  # not ours to remove
            raise UsageError("the status file must be in the state directory")
        spec = _read_json(offers_file)
        _remove(offers_file)
        if not isinstance(spec, dict) or not isinstance(spec.get("offers"), list):
            raise StateError(f"no usable offers file {offers_file}")
        lock = state.adopt_lock(int(spec["lockFd"]))
        status = {"pending": [offer["stage"] for offer in spec["offers"]], "done": {}}
        _write_json(status_file, status)
        for offer in spec["offers"]:
            try:
                outcome = _decide(args, state, profile, offer, float(spec["parentDeadline"]),
                                  status, status_file)
            except Exception as e:  # noqa: BLE001 - one stage must not stop the others
                state.diag(f"dialog worker: stage {offer.get('stage')}: {type(e).__name__}: "
                           f"{one_line(e)}")
                outcome = "failed"
            status.pop("adopting", None)
            status["pending"].remove(offer["stage"])
            status["done"][offer["stage"]] = outcome
            _write_json(status_file, status)
    except Exception as e:  # noqa: BLE001 - the worker has no one to report to but the log
        if state is not None:
            try:
                state.diag(f"dialog worker: {type(e).__name__}: {one_line(e)}")
            except OSError:
                pass
    finally:
        if lock is not None:
            lock.release()
        if own_name:
            if os.path.exists(status_file):
                time.sleep(WORKER_GRACE)  # the waiting parent reads the final status
            _remove(status_file)
        if offers_file is not None:
            _remove(offers_file)
    return OK


def _decide(args, state, profile, offer, parent_deadline, status, status_file):
    """Shows the dialog for one offer and acts on the answer; returns the outcome. Under the
    held lock it first re-reads what another session may have decided meanwhile."""
    stage, fingerprint = offer["stage"], offer["fingerprint"]
    try:
        remembered = state.load_rejections().get(stage)
    except StateError:
        remembered = None
    if remembered and remembered.get("fingerprint") == fingerprint:
        _report(f"stage {stage}: {fingerprint} was rejected meanwhile in another session; "
                "no dialog")
        return "skipped-rejected"
    current = load_profile(args).stage(stage)
    if current is not None and current.pin == fingerprint:
        _report(f"stage {stage}: {fingerprint} is already pinned (adopted meanwhile in another "
                "session); no dialog")
        return "skipped-pinned"
    answer = show_dialog(offer)
    if answer == "accept":
        status["adopting"] = stage
        _write_json(status_file, status)
        try:
            code = accept_locked(args, stage, fingerprint, "dialog", state)
        except Exception as e:  # noqa: BLE001 - reported like a failed adoption
            _report(f"stage {stage}: adoption failed: {type(e).__name__}: {one_line(e)}")
            code = INTERNAL
        if code == OK:
            if time.time() > parent_deadline:  # the start did not wait for it: reconnect
                notify(notification_text(stage, profile.name))
            return "adopted"
        outcome = "refused" if code == REFUSED else "failed"
        notify(not_adopted_text(stage, outcome))
        return outcome
    outcome = {"reject": "rejected", "timeout": "timed-out"}.get(answer, "dialog-failed")
    _try_log(state, stage, outcome, offer["pin"], fingerprint, offer["notBefore"], "dialog")
    if outcome in REMEMBERED_OUTCOMES:
        try:
            state.remember_rejection(stage, fingerprint, offer["notBefore"], outcome)
        except (StateError, OSError) as e:
            state.diag(f"stage {stage}: rejection not remembered: {one_line(e)}")
    return outcome


# --- forget (contract C-4) -----------------------------------------------------------------------

def cmd_forget(args):
    profile = load_profile(args)
    state = state_for(args, profile)
    if profile.stage(args.forget) is None:
        return _refuse(f"unknown stage '{args.forget}'")
    removed = state.clear_rejection(args.forget)
    state.diag(f"stage {args.forget}: remembered rejection "
               f"{'removed' if removed else 'not present'} (--forget)")
    emit(f"Stage {args.forget}: "
         + ("remembered rejection removed; the dialog is offered again.\n" if removed
            else "nothing was remembered.\n"))
    return OK


# --- command line --------------------------------------------------------------------------------

class _Parser(argparse.ArgumentParser):
    """argparse that raises UsageError instead of printing and exiting."""

    def error(self, message):
        raise UsageError(message)


def build_parser():
    parser = _Parser(
        prog=PROG,
        allow_abbrev=False,
        description="Check the TLS certificates of an inubit-mcp profile against their pins and "
                    "adopt a changed certificate only after an explicit operator decision.",
        epilog="Exit status: 0 ok, 1 internal error, 2 usage error, 3 refused (nothing changed), "
               "4 failed and rolled back, 10 a certificate changed, 20 a server unreachable. "
               "Contract: specs/006-tls-pin-rotation/contracts/cli.md.")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--profile", metavar="NAME",
                        help="profile file ~/.config/inubit-mcp/NAME.yaml")
    source.add_argument("--config", metavar="PATH", help="explicit profile file")
    command = parser.add_mutually_exclusive_group()
    command.add_argument("--check", action="store_true",
                         help="read-only check of every server (exit 0 all ok, 10 a certificate "
                              "changed, 20 a server unreachable)")
    command.add_argument("--accept", nargs=2, metavar=("STAGE", "FINGERPRINT"),
                         help="adopt the certificate every server of the stage presents, after "
                              "re-verifying it (backup, trust store, pin, --check-config, rollback "
                              "on failure)")
    command.add_argument("--interactive", action="store_true",
                         help="start-up check for the launcher: dialog for changed stages, "
                              "always exit 0, nothing on stdout, stdin never read")
    command.add_argument("--forget", metavar="STAGE",
                         help="forget the remembered rejection of a stage (the dialog asks "
                              "again)")
    command.add_argument("--prune", metavar="STAGE",
                         help="list the superseded trust-store entries of a stage (remove them "
                              "with --yes)")
    command.add_argument("--dialog-worker", metavar="STATUSFILE", help=argparse.SUPPRESS)
    parser.add_argument("--json", action="store_true",
                        help="with --check: one JSON document instead of the text report")
    parser.add_argument("--by", choices=("cli", "claude"),
                        help="with --accept: who decided (default cli)")
    parser.add_argument("--yes", action="store_true",
                        help="with --prune: remove the listed entries (after a backup)")
    parser.add_argument("--state-dir", metavar="DIR",
                        help="state directory (default ~/.inubit-mcp/<profile.name>)")
    parser.add_argument("--java", metavar="PATH", help="overrides x-cert-check.java")
    parser.add_argument("--server-jar", metavar="PATH", help="overrides x-cert-check.serverJar")
    return parser


def parse_args(argv):
    """The parsed command line; raises UsageError for every rule argparse cannot express."""
    args = build_parser().parse_args(argv)
    commands = [name for name in ("check", "accept", "interactive", "forget", "prune",
                                  "dialog_worker")
                if getattr(args, name) not in (None, False)]
    if not commands:
        raise UsageError("one of --check, --accept, --interactive, --forget, --prune is required")
    args.command = commands[0]
    if args.profile is None and args.config is None:
        raise UsageError("one of --profile NAME or --config PATH is required")
    if args.profile is not None and not valid_profile_name(args.profile):
        raise UsageError(f"invalid profile name: expected {PROFILE_NAME.pattern}, "
                         f"except {', '.join(sorted(RESERVED_PROFILE_NAMES))}")
    for option, owner in (("json", "check"), ("by", "accept"), ("yes", "prune")):
        if getattr(args, option) not in (None, False) and args.command != owner:
            raise UsageError(f"--{option} is only valid with --{owner}")
    return args


# --- commands ------------------------------------------------------------------------------------

def worker_command(status_file, options):
    """The command line of the detached dialog worker (contract C-3 step 5): this file in
    isolated mode, the status file and the parent's --config/--state-dir/--java/--server-jar."""
    return [sys.executable, "-I", os.path.abspath(__file__), "--dialog-worker",
            str(status_file)] + list(options)


def run(args):
    """Runs the parsed command; returns the exit status."""
    return globals()["cmd_" + args.command](args)


def one_line(text):
    return " ".join(str(text).split())


_current_state = None  # the State of the running command, for the guard in main()


def use_state(state):
    """Registers the command's State, so main() can log an unexpected error there as well."""
    global _current_state
    _current_state = state
    return state


def _report(message):
    """One line on stderr and, when the state is known, in the diagnostic log (best effort)."""
    print(f"{PROG}: {message}", file=sys.stderr)
    if _current_state is not None:
        try:
            _current_state.diag(message)
        except Exception:  # noqa: BLE001 - the log must never hide the original error
            pass


def main(argv=None):
    """Entry point; returns the exit status and never raises. With --interactive every outcome
    is exit 0 (the launcher must start the server in any case, contract C-3)."""
    if argv is None:
        argv = sys.argv[1:]
    if "--interactive" in argv:
        return _main_interactive(argv)
    return _main(argv)


def _main_interactive(argv):
    """--interactive: stdout is the MCP channel, so everything (even --help) goes to stderr;
    SIGTERM/SIGHUP and any other exception end with exit 0 after one stderr line."""
    def terminated(signum, _frame):
        raise Interrupted(signal.Signals(signum).name)

    previous = {}
    try:
        if threading.current_thread() is threading.main_thread():
            for number in (signal.SIGTERM, signal.SIGHUP):
                previous[number] = signal.signal(number, terminated)
        with contextlib.redirect_stdout(sys.stderr):
            _main(argv)
    except BaseException as e:  # noqa: BLE001 - the launcher must start the server anyway
        try:
            _report(f"interrupted ({type(e).__name__}{': ' + one_line(e) if str(e) else ''}); "
                    "the start continues")
        except BaseException:  # noqa: BLE001
            pass
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)
    return OK


def _main(argv):
    try:
        args = parse_args(argv)
    except UsageError as e:
        print(f"{PROG}: {one_line(e)} (see --help)", file=sys.stderr)
        return USAGE
    except SystemExit as e:  # --help
        return e.code if isinstance(e.code, int) else USAGE
    try:
        return run(args)
    except ToolError as e:
        _report(one_line(e))
        return INTERNAL
    except Exception as e:  # noqa: BLE001 - the guard of the whole tool (contract: exit 1)
        _report(f"internal error: {type(e).__name__}: {one_line(e)}")
        return INTERNAL


if __name__ == "__main__":
    sys.exit(main())
