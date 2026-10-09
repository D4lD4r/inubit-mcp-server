"""--accept and --prune (tasks T017, T018; contracts C-2, C-5)."""

from __future__ import annotations

import contextlib
import io
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

import support

OPENSSL = support.find_openssl()
if OPENSSL is not None:
    from tls_fixtures import TlsServer, closed_port, make_cert

OTHER = "CC:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:21:31:41:51:61:71:81:91:A1:B1:C1:D1:" \
        "E1:F1:04:05"
PLACEHOLDER = "cert-check-placeholder"
BACKUP = re.compile(r"\.bak-\d{8}-\d{6}(?:-\d+)?$")
SHARED_TEXT = "shared TLS settings — give the stage its own tls block first"


def mode_of(path):
    return stat.S_IMODE(os.stat(path).st_mode)


FakeCheckConfig = support.FakeCheckConfig


@unittest.skipIf(OPENSSL is None, "openssl not found")
class AcceptTestCase(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.certs = tempfile.TemporaryDirectory()
        cls.cert_a, cls.key_a, cls.fp_a = make_cert(cls.certs.name, "selfsigned.test")
        cls.cert_b, cls.key_b, cls.fp_b = make_cert(cls.certs.name, "selfsigned.test")

    @classmethod
    def tearDownClass(cls):
        cls.certs.cleanup()

    def setUp(self):
        self.tool = support.load_tool()
        self.tool.resolve_host = support.loopback_resolver
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = os.path.join(self.tmp.name, "home")
        os.mkdir(self.home)
        patcher = mock.patch.dict(os.environ, {"HOME": self.home})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.config_dir = os.path.join(self.tmp.name, "config")
        self.store_dir = os.path.join(self.tmp.name, "stores")
        os.mkdir(self.config_dir)
        os.mkdir(self.store_dir)
        self.addCleanup(os.chmod, self.config_dir, 0o700)
        self.addCleanup(os.chmod, self.store_dir, 0o700)
        self.profile = os.path.join(self.config_dir, "acme.yaml")
        self.state_dir = os.path.join(self.tmp.name, "state")
        self.state = self.tool.State(self.state_dir, "acme")
        self.stores = {}
        for stage in ("dev", "int"):
            path = os.path.join(self.store_dir, f"acme-{stage}-truststore.p12")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(f"store {stage} v1\n")
            os.chmod(path, 0o600)
            self.stores[stage] = path
        self.keytool = support.FakeKeytool(self.tool, {
            self.stores["dev"]: [self.tool.TrustEntry("dev-aaaaaaaa-20250101", self.fp_a)],
            self.stores["int"]: [self.tool.TrustEntry("int-aaaaaaaa-20250101", self.fp_a)]})
        self.tool.make_keytool = lambda java: self.keytool
        self.check_config = FakeCheckConfig(self.tool)
        self.tool.check_config_runner = self.check_config

    def serve(self, cert, key):
        server = TlsServer(cert, key)
        self.addCleanup(server.close)
        return f"https://node{server.port}.example.test:{server.port}"

    def standard(self, dev_pin_raw=None, dev_servers=None, int_servers=None, **kwargs):
        """dev pins A but presents B (changed); int pins and presents A."""
        dev_servers = dev_servers or [("node1", self.serve(self.cert_b, self.key_b))]
        int_servers = int_servers or [("node1", self.serve(self.cert_a, self.key_a)),
                                      ("node2", self.serve(self.cert_a, self.key_a))]
        support.write_profile(self.profile, [
            {"name": "dev", "pin_raw": dev_pin_raw or self.fp_a, "store": self.stores["dev"],
             "servers": dev_servers},
            {"name": "int", "pin": self.fp_a, "store": self.stores["int"],
             "servers": int_servers}], **kwargs)

    def files(self):
        return [self.profile] + list(self.stores.values())

    def snapshot(self):
        return {path: support.sha256_of(path) for path in self.files()}

    def run_tool(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.tool.main(["--config", self.profile, "--state-dir", self.state_dir]
                                  + list(argv))
        return code, out.getvalue(), err.getvalue()

    def accept(self, fingerprint=None, stage="dev", *extra):
        return self.run_tool("--accept", stage, fingerprint or self.fp_b, *extra)

    def backups(self):
        found = []
        for directory in (self.config_dir, self.store_dir):
            found += [os.path.join(directory, name) for name in sorted(os.listdir(directory))
                      if BACKUP.search(name)]
        return found

    def change_log(self):
        try:
            with open(self.state.changes_log, encoding="utf-8") as handle:
                return handle.read().splitlines()
        except FileNotFoundError:
            return []

    def assertRefused(self, result, before, *fragments):
        code, out, err = result
        self.assertEqual(3, code, err)
        self.assertEqual(before, self.snapshot(), "nothing changed")
        self.assertEqual([], self.backups())
        self.assertEqual([], [line for line in self.change_log() if "outcome=adopted" in line])
        self.assertFalse(any(call[0] == "import" for call in self.keytool.calls))
        for fragment in fragments:
            self.assertIn(fragment, err)
        return out, err


SLOW_ACCEPT = r"""
import json, sys, time
sys.path.insert(0, sys.argv[1])
import support
with open(sys.argv[2], encoding="utf-8") as handle:
    spec = json.load(handle)
tool = support.load_tool()
tool.resolve_host = support.loopback_resolver
keytool = support.FakeKeytool(tool, {store: [tool.TrustEntry(a, f) for a, f in items]
                                     for store, items in spec["entries"].items()})
tool.make_keytool = lambda java: keytool

def slow(argv, input=None, timeout=None, env=None):
    open(spec["marker"], "w").close()
    time.sleep(60)

tool.check_config_runner = slow
if spec.get("restore_marker"):
    real_write = tool.write_atomically
    with open(spec["profile"], "rb") as handle:
        original = handle.read()

    def slow_restore(path, data, **kwargs):
        if data == original:  # the rollback writes the original bytes back
            open(spec["restore_marker"], "w").close()
            time.sleep(2)
        return real_write(path, data, **kwargs)

    tool.write_atomically = slow_restore
sys.exit(tool.main(spec["argv"]))
"""


class RefusalTest(AcceptTestCase):

    def test_unknown_stage(self):
        self.standard()
        self.assertRefused(self.accept(stage="qa"), self.snapshot(), "qa")

    def test_malformed_fingerprint(self):
        self.standard()
        before = self.snapshot()
        for fingerprint in ("XYZ", self.fp_b[:-1], self.fp_b + ":00"):
            with self.subTest(fingerprint=fingerprint):
                self.assertRefused(self.accept(fingerprint), before, "fingerprint")

    def test_pin_from_alias(self):
        head = (f"x-acme-tls: &sharedTls\n  trustStore: {self.stores['dev']}\n"
                f"  pinnedCertificateSha256: {self.fp_a}\n")
        support.write_profile(self.profile, [
            {"name": "dev", "tls": "*sharedTls",
             "servers": [("node1", self.serve(self.cert_b, self.key_b))]}], head=head)
        self.assertRefused(self.accept(), self.snapshot(), SHARED_TEXT)

    def test_shared_trust_store(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", self.serve(self.cert_b, self.key_b))]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", self.serve(self.cert_a, self.key_a))]}])
        self.assertRefused(self.accept(), self.snapshot(), SHARED_TEXT)

    def test_one_server_presents_another_certificate(self):
        self.standard(dev_servers=[("node1", self.serve(self.cert_b, self.key_b)),
                                   ("node2", self.serve(self.cert_a, self.key_a))])
        self.assertRefused(self.accept(), self.snapshot(), "dev/node2")

    def test_one_server_unreachable(self):
        self.standard(dev_servers=[("node1", self.serve(self.cert_b, self.key_b)),
                                   ("node2", f"https://gone.example.test:{closed_port()}")])
        self.assertRefused(self.accept(), self.snapshot(), "dev/node2", "connect")

    def test_conflict(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": OTHER, "store": self.stores["dev"],
             "servers": [("node1", self.serve(self.cert_b, self.key_b)),
                         ("node2", self.serve(self.cert_a, self.key_a))]}])
        self.assertRefused(self.accept(), self.snapshot(), "dev/node2")

    def test_without_cert_check_settings(self):
        self.standard(java=None)
        self.assertRefused(self.accept(), self.snapshot(), "x-cert-check")

    def test_backup_directory_not_writable(self):
        self.standard()
        before = self.snapshot()
        os.chmod(self.config_dir, 0o500)
        self.assertRefused(self.accept(), before, "backup")

    def test_store_directory_not_writable(self):
        self.standard()
        before = self.snapshot()
        os.chmod(self.store_dir, 0o500)
        self.assertRefused(self.accept(), before, "backup")

    def test_state_directory_cannot_be_created(self):
        self.standard()
        before = self.snapshot()
        parent = os.path.join(self.tmp.name, "readonly")
        os.mkdir(parent, 0o500)
        self.addCleanup(os.chmod, parent, 0o700)
        self.state_dir = os.path.join(parent, "state")
        self.assertRefused(self.accept(), before, "state")

    def test_change_log_not_appendable(self):
        self.standard()
        self.state.ensure()
        with open(self.state.changes_log, "w", encoding="utf-8") as handle:
            handle.write("earlier\n")
        os.chmod(self.state.changes_log, 0o400)
        before = self.snapshot()
        self.assertRefused(self.accept(), before, "change log")

    def test_profile_changed_during_adoption(self):
        self.standard()
        stores = [support.sha256_of(path) for path in self.stores.values()]
        real = self.tool.write_atomically

        def concurrent_edit(path, data, **kwargs):
            if os.path.realpath(path) == os.path.realpath(self.profile):
                with open(self.profile, "a", encoding="utf-8") as handle:
                    handle.write("# edited meanwhile\n")
            return real(path, data, **kwargs)

        self.tool.write_atomically = concurrent_edit
        code, _out, err = self.accept()
        self.assertEqual(3, code, err)
        self.assertIn("profile changed during adoption", err)
        with open(self.profile, encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn("# edited meanwhile", text)
        self.assertIn(self.fp_a, text)
        self.assertNotIn(self.fp_b, text)
        self.assertEqual(stores, [support.sha256_of(path) for path in self.stores.values()],
                         "the trust store import is undone")
        self.assertEqual([], self.backups())
        self.assertEqual([], self.change_log())

    def test_profile_changed_and_undoing_the_import_fails(self):
        self.standard()
        real = self.tool.write_atomically
        store = os.path.realpath(self.stores["dev"])

        def edit_and_fail(path, data, **kwargs):
            if os.path.realpath(path) == os.path.realpath(self.profile):
                with open(self.profile, "a", encoding="utf-8") as handle:
                    handle.write("# edited meanwhile\n")
            elif os.path.realpath(path) == store:
                raise OSError(30, "Read-only file system")
            return real(path, data, **kwargs)

        self.tool.write_atomically = edit_and_fail
        code, _out, err = self.accept()
        self.assertEqual(4, code, err)
        [store_backup] = [path for path in self.backups()
                          if os.path.dirname(path) == self.store_dir]
        self.assertIn(store_backup, err)
        [line] = self.change_log()
        self.assertIn("outcome=failed", line)

    def test_profile_changed_before_the_import(self):
        self.standard()
        stores = [support.sha256_of(path) for path in self.stores.values()]
        real_fetch = self.tool.fetch_all

        def fetch_and_edit(*args, **kwargs):
            result = real_fetch(*args, **kwargs)
            with open(self.profile, "a", encoding="utf-8") as handle:
                handle.write("# edited meanwhile\n")
            return result

        self.tool.fetch_all = fetch_and_edit
        code, _out, err = self.accept()
        self.assertEqual(3, code, err)
        self.assertIn("profile changed during adoption", err)
        self.assertFalse(any(call[0] == "import" for call in self.keytool.calls))
        self.assertEqual(stores, [support.sha256_of(path) for path in self.stores.values()])
        self.assertEqual([], self.backups())

    def test_refusal_reaches_the_diagnostic_log(self):
        self.standard()
        self.accept(stage="qa")
        with open(self.state.diag_log, encoding="utf-8") as handle:
            self.assertIn("refused", handle.read())

    def test_lock_busy_too_long(self):
        self.standard()
        before = self.snapshot()
        self.tool.LOCK_TIMEOUT = 0.3
        lock = self.state.try_lock()
        self.addCleanup(lock.release)
        self.assertRefused(self.accept(), before, "lock")


class SuccessTest(AcceptTestCase):

    def lines(self, path):
        with open(path, encoding="utf-8") as handle:
            return handle.read().split("\n")

    def test_golden_pin_lines(self):
        cases = [
            ("unquoted", self.fp_a, self.fp_b),
            ("double-quoted", f'"{self.fp_a}"', f'"{self.fp_b}"'),
            ("single-quoted", f"'{self.fp_a}'", f"'{self.fp_b}'"),
            ("comment", f"{self.fp_a.lower()}   # dev pin, see ticket", f"{self.fp_b}   # dev pin, "
                                                                        "see ticket"),
            ("quoted with comment", f'"{self.fp_a}" # rotated yearly',
             f'"{self.fp_b}" # rotated yearly'),
        ]
        for label, old, new in cases:
            with self.subTest(label):
                self.keytool.entries[self.stores["dev"]] = [
                    self.tool.TrustEntry("dev-aaaaaaaa-20250101", self.fp_a)]
                for path in self.backups():
                    os.unlink(path)
                self.standard(dev_pin_raw=old)
                os.chmod(self.profile, 0o640)
                before = self.lines(self.profile)
                code, _out, err = self.accept()
                self.assertEqual(0, code, err)
                after = self.lines(self.profile)
                changed = [(a, b) for a, b in zip(before, after) if a != b]
                self.assertEqual(len(before), len(after))
                self.assertEqual([(f"      pinnedCertificateSha256: {old}",
                                   f"      pinnedCertificateSha256: {new}")], changed)
                self.assertEqual(0o640, mode_of(self.profile))

    def test_backups(self):
        self.standard()
        originals = {path: open(path, "rb").read() for path in self.files()}
        code, out, err = self.accept()
        self.assertEqual(0, code, err)
        backups = self.backups()
        self.assertEqual(2, len(backups))
        by_dir = {os.path.dirname(path): path for path in backups}
        profile_backup = by_dir[self.config_dir]
        store_backup = by_dir[self.store_dir]
        self.assertTrue(os.path.basename(profile_backup).startswith("acme.yaml.bak-"))
        self.assertTrue(os.path.basename(store_backup).startswith("acme-dev-truststore.p12.bak-"))
        self.assertEqual(originals[self.profile], open(profile_backup, "rb").read())
        self.assertEqual(originals[self.stores["dev"]], open(store_backup, "rb").read())
        for path in backups:
            self.assertEqual(0o600, mode_of(path))
            self.assertIn(path, out)
        self.assertEqual(originals[self.stores["int"]], open(self.stores["int"], "rb").read())

    def test_import_and_log(self):
        self.standard()
        self.state.remember_rejection("dev", self.fp_b, None, "rejected")
        code, out, err = self.accept()
        self.assertEqual(0, code, err)
        imports = [call for call in self.keytool.calls if call[0] == "import"]
        alias = self.tool.trust_alias("dev", self.fp_b)
        self.assertEqual([("import", self.stores["dev"], alias)], imports)
        self.assertRegex(alias, r"^dev-[0-9a-f]{8}-\d{8}$")
        imported = self.tool.parse_x509_output(support._x509_text(self.keytool.imported_pems[0]))
        self.assertEqual(self.fp_b, imported["fingerprint"])
        [line] = self.change_log()
        self.assertRegex(line, rf"^\S+Z profile=acme stage=dev outcome=adopted old={self.fp_a} "
                               rf"new={self.fp_b} notBefore=\S+Z by=cli$")
        self.assertIsNone(self.state.rejection("dev"))
        self.assertIn("/mcp -> inubit-acme -> Reconnect, or start a new session (desktop Code tab)",
                      " ".join(out.split()))
        self.assertIn("--prune dev", out)
        reread = self.tool.read_profile(self.profile)
        self.assertEqual((self.fp_b, self.fp_a), (reread.stage("dev").pin,
                                                  reread.stage("int").pin))

    def test_import_before_the_pin_is_written(self):
        self.standard()
        order = []
        real_write = self.tool.write_atomically
        real_import = self.keytool.import_cert

        def write(path, data, **kwargs):
            order.append("write " + os.path.basename(path))
            return real_write(path, data, **kwargs)

        def import_cert(store, pem, alias):
            order.append("import")
            return real_import(store, pem, alias)

        self.tool.write_atomically = write
        self.keytool.import_cert = import_cert
        code, _out, err = self.accept()
        self.assertEqual(0, code, err)
        self.assertEqual(["import", "write acme.yaml"], order)

    def test_no_prune_hint_without_superseded_entries(self):
        self.standard()
        self.keytool.entries[self.stores["dev"]] = [
            self.tool.TrustEntry("dev-bbbbbbbb-20260101", self.fp_b)]
        code, out, err = self.accept()
        self.assertEqual(0, code, err)
        self.assertNotIn("--prune", out)
        self.assertNotIn("superseded", out)

    def test_by_claude(self):
        self.standard()
        code, _out, err = self.accept(None, "dev", "--by", "claude")
        self.assertEqual(0, code, err)
        self.assertTrue(self.change_log()[0].endswith(" by=claude"))

    def test_no_import_when_already_trusted(self):
        self.standard()
        self.keytool.entries[self.stores["dev"]].append(
            self.tool.TrustEntry("dev-bbbbbbbb-20260101", self.fp_b))
        code, _out, err = self.accept(self.fp_b.replace(":", "").lower())
        self.assertEqual(0, code, err)
        self.assertFalse(any(call[0] == "import" for call in self.keytool.calls))

    def test_check_config_runner(self):
        self.standard()
        secrets = {"INUBIT_ACME_DEV_PASSWORD": "real-secret", "INUBIT_ACME_DEV_USERNAME": "me",
                   "JAVA_TOOL_OPTIONS": "-Dx=y"}
        with mock.patch.dict(os.environ, secrets):
            code, _out, err = self.accept()
        self.assertEqual(0, code, err)
        [call] = self.check_config.calls
        self.assertEqual(["/jdk/bin/java", f"-Duser.home={self.home}", "-jar",
                          "/lib/inubit-mcp-server.jar", "--config", self.profile,
                          "--check-config"], call["argv"])
        self.assertEqual(60, call["timeout"])
        env = call["env"]
        expected = {f"INUBIT_ACME_{stage}_{kind}": PLACEHOLDER
                    for stage in ("DEV", "INT") for kind in ("USERNAME", "PASSWORD")}
        self.assertEqual(set(expected) | {"HOME", "PATH", "LC_ALL"}, set(env))
        for key, value in expected.items():
            self.assertEqual(value, env[key])
        self.assertEqual(self.home, env["HOME"])
        self.assertTrue(env["PATH"].startswith("/jdk/bin:"))
        self.assertNotIn("real-secret", env.values())

    def test_quoted_pin_key(self):
        for key in ('"pinnedCertificateSha256"', "'pinnedCertificateSha256'"):
            with self.subTest(key=key):
                for path in self.backups():
                    os.unlink(path)
                self.keytool.entries[self.stores["dev"]] = [
                    self.tool.TrustEntry("dev-aaaaaaaa-20250101", self.fp_a)]
                self.standard()
                with open(self.profile, encoding="utf-8") as handle:
                    text = handle.read()
                text = text.replace("      pinnedCertificateSha256: ", f"      {key}: ", 1)
                with open(self.profile, "w", encoding="utf-8") as handle:
                    handle.write(text)
                self.assertTrue(self.tool.read_profile(self.profile).stage("dev").own_tls)
                code, _out, err = self.accept()
                self.assertEqual(0, code, err)
                with open(self.profile, encoding="utf-8") as handle:
                    self.assertIn(f"      {key}: {self.fp_b}\n", handle.read())

    def test_log_failure_after_success(self):
        self.standard()

        def no_log(*args, **kwargs):
            raise OSError(28, "No space left on device")

        self.tool.State.log_change = no_log
        code, out, err = self.accept()
        self.assertEqual(0, code, err)
        self.assertIn("adopted", out)
        for backup in self.backups():
            self.assertIn(backup, out)
        self.assertIn("audit", err)

    def test_suggestions_repeat_the_options(self):
        self.standard()
        code, out, err = self.run_tool("--java", "/opt/my jdk/bin/java", "--accept", "dev",
                                       self.fp_b)
        self.assertEqual(0, code, err)
        self.assertIn(f"--state-dir {self.state_dir}", out)
        self.assertIn("--java '/opt/my jdk/bin/java'", out)

    def test_already_pinned(self):
        self.standard(dev_servers=[("node1", self.serve(self.cert_a, self.key_a))])
        before = self.snapshot()
        code, out, err = self.accept(self.fp_a)
        self.assertEqual(0, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())
        self.assertIn("already", out)

    def test_second_accept_waits_for_the_lock(self):
        self.standard()
        lock = self.state.try_lock()
        results = []
        worker = threading.Thread(target=lambda: results.append(self.accept()))
        started = time.monotonic()
        worker.start()
        time.sleep(1.0)
        self.assertEqual([], results, "waits while the lock is held")
        lock.release()
        worker.join(30)
        self.assertGreaterEqual(time.monotonic() - started, 1.0)
        self.assertEqual(0, results[0][0], results[0][2])


class RollbackTest(AcceptTestCase):

    def assertRolledBack(self, before):
        code, _out, err = self.accept()
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot(), "profile and trust store restored")
        self.assertEqual(2, len(self.backups()), "backups are kept")
        [line] = self.change_log()
        self.assertIn("outcome=failed", line)
        self.assertIn(f"new={self.fp_b}", line)
        return err

    def test_pin_write_fails(self):
        self.standard()
        before = self.snapshot()
        real = self.tool.write_atomically
        failed = []

        def first_write_fails(path, data, **kwargs):
            if os.path.realpath(path) == os.path.realpath(self.profile) and not failed:
                failed.append(path)
                raise OSError(28, "No space left on device")
            return real(path, data, **kwargs)

        self.tool.write_atomically = first_write_fails
        self.assertRolledBack(before)
        self.assertEqual(1, len(failed))

    def test_restore_fails(self):
        self.standard()
        self.check_config.returncode = 1  # fails after the pin was written
        real = self.tool.write_atomically
        writes = []

        def only_the_edit_succeeds(path, data, **kwargs):
            if os.path.realpath(path) == os.path.realpath(self.profile):
                writes.append(path)
                if len(writes) > 1:
                    raise OSError(30, "Read-only file system")
            return real(path, data, **kwargs)

        self.tool.write_atomically = only_the_edit_succeeds
        code, _out, err = self.accept()
        self.assertEqual(4, code, err)
        self.assertIn("RESTORE FAILED", err)
        for backup in self.backups():
            self.assertIn(backup, err)

    def test_other_stage_pin_changed(self):
        self.standard()
        before = self.snapshot()

        def change_int(_argv):
            with open(self.profile, encoding="utf-8") as handle:
                text = handle.read()
            with open(self.profile, "w", encoding="utf-8") as handle:
                handle.write(text.replace(self.fp_a, OTHER))  # dev's line holds fp_b by now

        self.tool.check_config_runner = FakeCheckConfig(self.tool, on_call=change_int)
        self.assertIn("int", self.assertRolledBack(before))

    def test_writer_writes_another_pin(self):
        self.standard()
        before = self.snapshot()
        real = self.tool.replace_pin_line
        self.tool.replace_pin_line = lambda text, line, old, new: real(text, line, old, OTHER)
        self.assertRolledBack(before)

    def test_import_that_adds_nothing(self):
        self.standard()
        before = self.snapshot()
        self.keytool.import_adds = False
        self.assertIn("trust store", self.assertRolledBack(before))

    def test_interrupted_check_config(self):
        self.standard()
        before = self.snapshot()

        def interrupt(_argv):
            raise KeyboardInterrupt()

        self.tool.check_config_runner = FakeCheckConfig(self.tool, on_call=interrupt)
        self.assertRolledBack(before)

    def test_signals_during_the_change(self):
        for signal_number in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
            with self.subTest(signal=signal_number):
                self.signal_during_check_config(signal_number)

    def test_second_signal_during_the_restore(self):
        self.signal_during_check_config(signal.SIGTERM, second_signal=True)

    def signal_during_check_config(self, signal_number, second_signal=False):
        for path in self.backups():
            os.unlink(path)
        if os.path.exists(self.state.changes_log):
            os.unlink(self.state.changes_log)
        self.standard()
        before = self.snapshot()
        marker = os.path.join(self.tmp.name, "in-check-config")
        restore_marker = os.path.join(self.tmp.name, "in-restore")
        for path in (marker, restore_marker):
            if os.path.exists(path):
                os.unlink(path)
        spec = os.path.join(self.tmp.name, "spec.json")
        with open(spec, "w", encoding="utf-8") as handle:
            json.dump({"marker": marker, "profile": self.profile,
                       "restore_marker": restore_marker if second_signal else None,
                       "entries": {
                store: [[e.alias, e.fingerprint] for e in entries]
                for store, entries in self.keytool.entries.items()},
                "argv": ["--config", self.profile, "--state-dir", self.state_dir,
                         "--accept", "dev", self.fp_b]}, handle)
        child = subprocess.Popen([sys.executable, "-I", "-c", SLOW_ACCEPT,
                                  str(support.TESTS_DIR), spec], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        self.addCleanup(child.kill)
        deadline = time.monotonic() + 30
        while not os.path.exists(marker) and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(os.path.exists(marker), "the child reached the configuration check")
        child.send_signal(signal_number)
        if second_signal:
            deadline = time.monotonic() + 30
            while not os.path.exists(restore_marker) and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(os.path.exists(restore_marker), "the child is restoring")
            child.send_signal(signal.SIGTERM)
        _out, err = child.communicate(timeout=30)
        self.assertEqual(4, child.returncode, err)
        self.assertEqual(before, self.snapshot())
        [line] = self.change_log()
        self.assertIn("outcome=failed", line)

    def test_import_fails(self):
        self.standard()
        self.keytool.fail_on.add("import")
        self.assertRolledBack(self.snapshot())

    def test_config_check_fails(self):
        self.standard()
        self.check_config.returncode = 1
        self.assertIn("check-config", self.assertRolledBack(self.snapshot()))

    def test_server_ids_differ(self):
        self.standard()
        self.check_config.ids = ["dev/node1", "int/node1"]
        self.assertRolledBack(self.snapshot())

    def test_reread_fails(self):
        self.standard()
        real = self.tool.read_profile
        calls = []

        def flaky(path, expected_name=None):
            calls.append(path)
            with open(path, encoding="utf-8") as handle:
                if self.fp_b in handle.read():  # the verification after the change
                    raise self.tool.ConfigError("unexpected content")
            return real(path, expected_name)

        self.tool.read_profile = flaky
        self.check_config.ids = None
        self.tool.check_config_runner = FakeCheckConfig(self.tool, ids=[
            "dev/node1", "int/node1", "int/node2"])
        self.assertRolledBack(self.snapshot())

    def test_log_failure_during_rollback(self):
        self.standard()
        before = self.snapshot()
        self.keytool.fail_on.add("import")

        def no_log(*args, **kwargs):
            raise OSError(28, "No space left on device")

        self.tool.State.log_change = no_log
        code, _out, err = self.accept()
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertIn("restored", err)
        self.assertIn("audit", err)

    def test_store_lacks_the_new_entry(self):
        self.standard()
        self.keytool.fail_on.add("list-after-change")
        self.assertRolledBack(self.snapshot())


class CheckConfigOutputTest(unittest.TestCase):
    """The summary format of the server's --check-config (ConfigSummary.render)."""

    SAMPLE = (
        "Configuration: /profiles/acme.yaml\n"
        "Profile: acme\n"
        "Terminology: stage/stages, server/servers\n"
        "Credential variables: INUBIT_ACME_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD\n"
        "Stage dev:\n"
        "  Server dev/node1: read-only, cli: unavailable, username ← INUBIT_ACME_DEV_USERNAME,"
        " password ← INUBIT_ACME_DEV_PASSWORD\n"
        "Stage int (production):\n"
        "  Server int/node2: read-only, cli: unavailable, username ← X, password ← Y\n"
        "Warnings:\n"
        "  - int/node2: baseUrl uses http:// (allowInsecureHttp: true)\n"
        "Result: OK\n")

    def run_with(self, stdout, returncode=0):
        tool = support.load_tool()
        tool.check_config_runner = lambda argv, input=None, timeout=None, env=None: \
            subprocess.CompletedProcess(argv, returncode, stdout, "")
        profile = tool.Profile(path="/profiles/acme.yaml", name="acme", env_prefix="INUBIT_ACME",
                               java="/jdk/bin/java", server_jar="/lib/server.jar", stages=())
        return tool, lambda: tool.run_check_config(profile, profile.path)

    def test_server_ids(self):
        _tool, run = self.run_with(self.SAMPLE)
        self.assertEqual(["dev/node1", "int/node2"], run())

    def test_other_terminology(self):
        sample = self.SAMPLE.replace("  Server ", "  Node ").replace("Stage ", "Group ")
        _tool, run = self.run_with(sample.replace("  Node dev/node1", "  Test system dev/node1"))
        self.assertEqual(["dev/node1", "int/node2"], run())

    def test_failed(self):
        for stdout, code in ((self.SAMPLE.replace("Result: OK", "Result: FAILED (1 error(s))"),
                              1), (self.SAMPLE, 1), (self.SAMPLE.replace("Result: OK\n", ""), 0)):
            tool, run = self.run_with(stdout, code)
            with self.assertRaises(tool.ConfigCheckFailed):
                run()


class PruneTest(AcceptTestCase):

    def setUp(self):
        super().setUp()
        self.keytool.entries[self.stores["dev"]] = [
            self.tool.TrustEntry("dev-cccccccc-20240101", OTHER),
            self.tool.TrustEntry("dev-aaaaaaaa-20250101", self.fp_a)]

    def prune(self, *extra):
        return self.run_tool("--prune", "dev", *extra)

    def simple(self, **kwargs):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", "https://dev-host.example.test:8443")]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["int"],
             "servers": [("node1", "https://int-1.example.test:8443")]}], **kwargs)

    def assertPruneRefused(self, *extra):
        before = self.snapshot()
        code, _out, err = self.prune(*extra)
        self.assertEqual(3, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())
        self.assertFalse(any(call[0] == "delete" for call in self.keytool.calls))
        return err

    def test_refuses_alias_with_and_without_yes(self):
        head = (f"x-acme-tls: &sharedTls\n  trustStore: {self.stores['dev']}\n"
                f"  pinnedCertificateSha256: {self.fp_a}\n")
        support.write_profile(self.profile, [
            {"name": "dev", "tls": "*sharedTls",
             "servers": [("node1", "https://dev-host.example.test:8443")]}], head=head)
        for extra in ((), ("--yes",)):
            with self.subTest(extra=extra):
                self.assertIn(SHARED_TEXT, self.assertPruneRefused(*extra))

    def test_refuses_defaults_and_server_override(self):
        head = (f"defaults:\n  tls:\n    trustStore: {self.stores['dev']}\n"
                f"    pinnedCertificateSha256: {self.fp_a}\n")
        support.write_profile(self.profile, [
            {"name": "dev", "servers": [("node1", "https://dev-host.example.test:8443")]}],
            head=head)
        for extra in ((), ("--yes",)):
            with self.subTest(extra=extra):
                self.assertPruneRefused(*extra)

    def test_refuses_shared_store_with_and_without_yes(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", "https://dev-host.example.test:8443")]},
            {"name": "int", "pin": OTHER, "store": self.stores["dev"],
             "servers": [("node1", "https://int-1.example.test:8443")]}])
        for extra in ((), ("--yes",)):
            with self.subTest(extra=extra):
                self.assertIn(SHARED_TEXT, self.assertPruneRefused(*extra))

    def test_list_only(self):
        self.simple()
        before = self.snapshot()
        code, out, err = self.prune()
        self.assertEqual(0, code, err)
        self.assertIn("dev-cccccccc-20240101", out)
        self.assertIn(OTHER, out)
        self.assertNotIn("dev-aaaaaaaa-20250101", out)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())
        self.assertEqual([], self.change_log())
        self.assertEqual(["list"], [call[0] for call in self.keytool.calls])

    def test_yes_refuses_without_the_pinned_entry(self):
        self.simple()
        self.keytool.entries[self.stores["dev"]] = [
            self.tool.TrustEntry("dev-cccccccc-20240101", OTHER)]
        self.assertIn("pinned", self.assertPruneRefused("--yes"))

    def test_yes_removes_superseded_entries(self):
        self.simple()
        self.keytool.entries[self.stores["dev"]].append(
            self.tool.TrustEntry("dev-dddddddd-20230101", self.fp_b))
        original = open(self.stores["dev"], "rb").read()
        code, out, err = self.prune("--yes")
        self.assertEqual(0, code, err)
        deleted = [call[2] for call in self.keytool.calls if call[0] == "delete"]
        self.assertEqual(["dev-cccccccc-20240101", "dev-dddddddd-20230101"], deleted)
        self.assertEqual([self.fp_a], [e.fingerprint
                                       for e in self.keytool.entries[self.stores["dev"]]])
        [backup] = self.backups()
        self.assertEqual(self.store_dir, os.path.dirname(backup))
        self.assertEqual(original, open(backup, "rb").read())
        self.assertEqual(0o600, mode_of(backup))
        self.assertIn(backup, out)
        log = self.change_log()
        self.assertEqual(2, len(log))
        self.assertIn(f"outcome=pruned old={OTHER} new={self.fp_a}", log[0])
        self.assertIn(f"outcome=pruned old={self.fp_b} new={self.fp_a}", log[1])

    def test_yes_nothing_to_remove(self):
        self.simple()
        self.keytool.entries[self.stores["dev"]] = [
            self.tool.TrustEntry("dev-aaaaaaaa-20250101", self.fp_a)]
        before = self.snapshot()
        code, _out, err = self.prune("--yes")
        self.assertEqual(0, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertEqual([], self.backups())

    def test_yes_restores_when_the_pinned_entry_is_gone(self):
        self.simple()
        before = self.snapshot()
        keytool = self.keytool
        real_delete = keytool.delete_entry

        def delete_too_much(store, alias):
            real_delete(store, alias)
            keytool.entries[store] = []

        keytool.delete_entry = delete_too_much
        code, _out, err = self.prune("--yes")
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertIn("outcome=failed", self.change_log()[-1])

    def test_yes_restores_when_a_delete_keeps_the_entry(self):
        self.simple()
        before = self.snapshot()
        self.keytool.delete_removes = False
        code, _out, err = self.prune("--yes")
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertIn("outcome=failed", self.change_log()[-1])

    def test_yes_restores_when_interrupted(self):
        self.simple()
        before = self.snapshot()
        keytool = self.keytool
        real_delete = keytool.delete_entry

        def interrupted(store, alias):
            real_delete(store, alias)
            raise KeyboardInterrupt()

        keytool.delete_entry = interrupted
        code, _out, err = self.prune("--yes")
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot())
        self.assertIn("outcome=failed", self.change_log()[-1])

    def test_yes_restores_when_a_delete_fails(self):
        self.simple()
        before = self.snapshot()
        self.keytool.fail_on.add("delete")
        code, _out, err = self.prune("--yes")
        self.assertEqual(4, code, err)
        self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
