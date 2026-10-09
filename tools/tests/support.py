"""Shared helpers for the tests of tools/inubit-cert-check.py.

The tool's file name contains a hyphen, so it cannot be imported with a plain import statement;
load_tool() loads it from its path. Every test works in temporary directories only and never
touches the operator's real configuration or state (~/.config/inubit-mcp, ~/.inubit-mcp).
"""

from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
import sys
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
TOOL_PATH = TESTS_DIR.parent / "inubit-cert-check.py"
MODULE_NAME = "inubit_cert_check"


def load_tool():
    """A fresh module object of the tool (each call loads it again, so patches do not leak)."""
    spec = importlib.util.spec_from_file_location(MODULE_NAME, str(TOOL_PATH))
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {TOOL_PATH}")
    module = importlib.util.module_from_spec(spec)
    # registered before it runs: dataclasses look up the module of their annotations
    sys.modules[MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


def find_openssl():
    """The openssl binary the tool prefers (/usr/bin/openssl first), or None."""
    if os.access("/usr/bin/openssl", os.X_OK):
        return "/usr/bin/openssl"
    return shutil.which("openssl")


def find_keytool():
    """keytool of $JAVA_HOME, else of /usr/libexec/java_home (macOS), else None."""
    homes = []
    if os.environ.get("JAVA_HOME"):
        homes.append(os.environ["JAVA_HOME"])
    if os.access("/usr/libexec/java_home", os.X_OK):
        try:
            result = subprocess.run(["/usr/libexec/java_home"], stdin=subprocess.DEVNULL,
                                    capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.SubprocessError):
            result = None
        if result is not None and result.returncode == 0 and result.stdout.strip():
            homes.append(result.stdout.strip())
    for home in homes:
        candidate = os.path.join(home, "bin", "keytool")
        if os.access(candidate, os.X_OK):
            return candidate
    return None


# --- profiles and fakes shared by test_check.py and test_accept.py ------------------------------

def write_profile(path, stages, java="/jdk/bin/java", server_jar="/lib/inubit-mcp-server.jar",
                  head=""):
    """Writes a profile file. stages: list of dicts with name, servers [(node, baseUrl)], and
    either tls (raw text after "tls: ", e.g. "*sharedTls") or pin/store (inline block; pin_raw
    overrides the text after "pinnedCertificateSha256: ")."""
    lines = ["# Test profile; every name is fictitious.", "profile:", "  name: acme", ""]
    if java is not None:
        lines += ["x-cert-check:", f"  java: {java}", f"  serverJar: {server_jar}", ""]
    if head:
        lines += head.rstrip("\n").split("\n") + [""]
    lines.append("groups:")
    for stage in stages:
        lines.append(f"  - name: {stage['name']}")
        if "tls" in stage:
            lines.append(f"    tls: {stage['tls']}")
        elif stage.get("pin") or stage.get("store") or stage.get("pin_raw"):
            lines.append("    tls:")
            if stage.get("store"):
                lines.append(f"      trustStore: {stage['store']}")
            pin = stage.get("pin_raw") or stage.get("pin")
            if pin:
                lines.append(f"      pinnedCertificateSha256: {pin}")
        lines.append("    nodes:")
        for node, url in stage["servers"]:
            lines += [f"      - name: {node}", f"        baseUrl: {url}"]
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return path


def sha256_of(path):
    import hashlib
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def loopback_resolver(host, port):
    """Resolves every test host name to 127.0.0.1 (no DNS query leaves the machine)."""
    return "127.0.0.1"


class FakeKeytool:
    """Trust stores in memory, keyed by path; the store files get one line per change, so a
    restore can be checked byte by byte. calls records (action, store, alias)."""

    def __init__(self, tool, entries=None, broken=(), fail_on=()):
        self.tool = tool
        self.entries = {store: list(items) for store, items in (entries or {}).items()}
        self.broken = set(broken)
        self.fail_on = set(fail_on)  # "import", "delete", "list-after-change"
        self.calls = []
        self.timeouts = []
        self.list_delay = 0.0
        self.import_adds = True  # False: an import that reports success but adds nothing
        self.delete_removes = True  # False: a delete that reports success but keeps the entry
        self.imported_pems = []
        self.changed = False

    def _check(self, action, store):
        if store in self.broken or action in self.fail_on \
                or (action == "list" and self.changed and "list-after-change" in self.fail_on):
            raise self.tool.KeytoolError(f"keytool -{action} failed: injected failure")

    def _touch(self, store, text):
        with open(store, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")

    def list_entries(self, store, timeout=None):
        self.calls.append(("list", store, None))
        self.timeouts.append(timeout)
        if self.list_delay:
            import time
            time.sleep(self.list_delay)
        self._check("list", store)
        return list(self.entries.get(store, []))

    def import_cert(self, store, pem, alias):
        self.calls.append(("import", store, alias))
        self._check("import", store)
        info = self.tool.parse_x509_output(_x509_text(pem))
        if self.import_adds:
            self.entries.setdefault(store, []).append(self.tool.TrustEntry(alias,
                                                                           info["fingerprint"]))
        self.imported_pems.append(pem)
        self.changed = True
        self._touch(store, f"import {alias}")

    def delete_entry(self, store, alias):
        self.calls.append(("delete", store, alias))
        self._check("delete", store)
        if self.delete_removes:
            self.entries[store] = [e for e in self.entries.get(store, []) if e.alias != alias]
        self.changed = True
        self._touch(store, f"delete {alias}")


def _x509_text(pem):
    result = subprocess.run([find_openssl(), "x509", "-noout", "-fingerprint", "-sha256",
                             "-subject", "-issuer", "-dates"], input=pem, capture_output=True,
                            text=True, timeout=30)
    return result.stdout


class FakeCheckConfig:
    """Stands in for `java -Duser.home=... -jar <serverJar> --config <file> --check-config`:
    lists the server ids of the file it is given, like the server's summary. on_call(argv) runs
    first (to simulate side effects)."""

    def __init__(self, tool, returncode=0, ids=None, on_call=None):
        self.tool = tool
        self.returncode = returncode
        self.ids = ids
        self.on_call = on_call
        self.calls = []

    def __call__(self, argv, input=None, timeout=None, env=None):
        self.calls.append({"argv": argv, "env": env, "timeout": timeout})
        if self.on_call is not None:
            self.on_call(argv)
        config = argv[argv.index("--config") + 1]
        ids = self.ids if self.ids is not None else self.tool.read_profile(config).server_ids()
        lines = ["Configuration: " + config, "Profile: acme"]
        for server_id in ids:
            lines.append(f"Stage {server_id.split('/')[0]}:")
            lines.append(f"  Server {server_id}: read-only, cli: unavailable, "
                         f"username ? X, password ? Y")
        lines.append("Result: OK" if self.returncode == 0 else "Result: FAILED (1 error(s))")
        return subprocess.CompletedProcess(argv, self.returncode, "\n".join(lines) + "\n", "")
