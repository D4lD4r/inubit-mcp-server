"""--check: read-only comparison of presented certificates with the pins (task T014, C-1)."""

from __future__ import annotations

import contextlib
import fcntl
import io
import json
import os
import re
import tempfile
import time
import unittest
from unittest import mock

import support

OPENSSL = support.find_openssl()
if OPENSSL is not None:
    from tls_fixtures import BlackHole, TlsServer, closed_port, make_cert

OTHER = "CC:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:21:31:41:51:61:71:81:91:A1:B1:C1:D1:" \
        "E1:F1:04:05"
TOP_KEYS = {"profile", "checkedAt", "exitCode", "stages", "skippedServers"}
STAGE_KEYS = {"stage", "pin", "status", "conflict", "offer", "rejected", "pinSource", "ownTls",
              "supersededTrustStoreEntries", "servers"}
SERVER_KEYS = {"id", "baseUrl", "ip", "status", "pinned", "presented", "subject", "issuer",
               "notBefore", "notAfter", "validNow", "error"}
SHARED_TEXT = "shared TLS settings — give the stage its own tls block first"


@unittest.skipIf(OPENSSL is None, "openssl not found")
class CheckTestCase(unittest.TestCase):

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
        self.state_dir = os.path.join(self.tmp.name, "state")
        self.profile = os.path.join(self.tmp.name, "acme.yaml")
        self.stores = {}
        for stage in ("dev", "int"):
            path = os.path.join(self.tmp.name, f"acme-{stage}-truststore.p12")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(f"store {stage}\n")
            self.stores[stage] = path
        self.keytool = support.FakeKeytool(self.tool, {
            self.stores["dev"]: [self.tool.TrustEntry("dev-old", self.fp_a)],
            self.stores["int"]: [self.tool.TrustEntry("int-old", self.fp_a)]})
        self.tool.make_keytool = lambda java: self.keytool

    def serve(self, cert, key):
        server = TlsServer(cert, key)
        self.addCleanup(server.close)
        return f"https://node{server.port}.example.test:{server.port}"

    def serve_a(self):
        return self.serve(self.cert_a, self.key_a)

    def serve_b(self):
        return self.serve(self.cert_b, self.key_b)

    def hole(self):
        hole = BlackHole()
        self.addCleanup(hole.close)
        return f"https://hole.example.test:{hole.port}"

    def write(self, stages, **kwargs):
        support.write_profile(self.profile, stages, **kwargs)

    def two_stages(self, dev_servers, int_servers, dev_pin=None, int_pin=None):
        self.write([
            {"name": "dev", "pin": dev_pin or self.fp_a, "store": self.stores["dev"],
             "servers": dev_servers},
            {"name": "int", "pin": int_pin or self.fp_a, "store": self.stores["int"],
             "servers": int_servers}])

    def run_check(self, *extra, json_output=True):
        argv = ["--config", self.profile, "--state-dir", self.state_dir, "--check"]
        argv += ["--json"] if json_output else []
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.tool.main(argv + list(extra))
        if json_output and out.getvalue():
            return code, json.loads(out.getvalue()), err.getvalue()
        return code, out.getvalue(), err.getvalue()

    def stage(self, report, name):
        return next(stage for stage in report["stages"] if stage["stage"] == name)


class StatusTest(CheckTestCase):

    def test_all_ok(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a()),
                                                       ("node2", self.serve_a())])
        code, report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertEqual(0, report["exitCode"])
        self.assertEqual("acme", report["profile"])
        self.assertRegex(report["checkedAt"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        for stage in report["stages"]:
            self.assertEqual("ok", stage["status"])
            self.assertEqual(["ok"] * len(stage["servers"]),
                             [server["status"] for server in stage["servers"]])
            self.assertIsNone(stage["offer"])

    def test_changed(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        dev = self.stage(report, "dev")
        self.assertEqual(("changed", False, self.fp_b, False),
                         (dev["status"], dev["conflict"], dev["offer"], dev["rejected"]))
        server = dev["servers"][0]
        self.assertEqual(("dev/node1", "changed", self.fp_a, self.fp_b, "127.0.0.1"),
                         (server["id"], server["status"], server["pinned"], server["presented"],
                          server["ip"]))
        self.assertEqual("CN=selfsigned.test", server["subject"])
        self.assertTrue(server["validNow"])
        self.assertIsNone(server["error"])
        self.assertEqual("ok", self.stage(report, "int")["status"])

    def test_unreachable(self):
        self.two_stages([("node1", f"https://gone.example.test:{closed_port()}")],
                        [("node1", self.serve_a())])
        code, report, _err = self.run_check()
        self.assertEqual(20, code)
        dev = self.stage(report, "dev")
        self.assertEqual("unreachable", dev["status"])
        self.assertEqual(("unreachable", "connect", None),
                         (dev["servers"][0]["status"], dev["servers"][0]["error"],
                          dev["servers"][0]["presented"]))
        self.assertIsNone(dev["offer"])

    def test_changed_wins_over_unreachable_and_blocks_the_offer(self):
        self.two_stages([("node1", self.serve_a())],
                        [("node1", self.serve_b()),
                         ("node2", f"https://gone.example.test:{closed_port()}")])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", False, None), (int_["status"], int_["conflict"],
                                                   int_["offer"]))

    def test_conflict(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_b()),
                                                       ("node2", self.serve_a())],
                        int_pin=OTHER)
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", True, None), (int_["status"], int_["conflict"],
                                                  int_["offer"]))
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("conflict", text)

    def test_parallel_with_hard_limit(self):
        self.two_stages([("node1", self.hole())], [("node1", self.hole()),
                                                    ("node2", self.hole())])
        started = time.monotonic()
        code, report, _err = self.run_check()
        self.assertLessEqual(time.monotonic() - started, 6.0)
        self.assertEqual(20, code)
        errors = [server["error"] for stage in report["stages"] for server in stage["servers"]]
        self.assertEqual(["timeout"] * 3, errors)

    def test_stage_without_pin(self):
        self.write([{"name": "dev", "store": self.stores["dev"],
                     "servers": [("node1", self.serve_a())]}])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        dev = self.stage(report, "dev")
        self.assertEqual((None, "changed", None, False),
                         (dev["pin"], dev["status"], dev["offer"], dev["ownTls"]))
        self.assertEqual("changed", dev["servers"][0]["status"])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("no pin configured", text)


class OwnTlsTest(CheckTestCase):
    """A stage without its own pin and trust store never gets an offer."""

    def assertNoOffer(self, report, stage_name, pin_source):
        stage = self.stage(report, stage_name)
        self.assertEqual(("changed", pin_source, False, None),
                         (stage["status"], stage["pinSource"], stage["ownTls"], stage["offer"]))

    def test_alias(self):
        head = (f"x-acme-tls: &sharedTls\n  trustStore: {self.stores['dev']}\n"
                f"  pinnedCertificateSha256: {self.fp_a}\n")
        self.write([{"name": "dev", "tls": "*sharedTls", "servers": [("node1", self.serve_b())]},
                    {"name": "int", "tls": "*sharedTls", "servers": [("node1", self.serve_a())]}],
                   head=head)
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        self.assertNoOffer(report, "dev", "alias")
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn(SHARED_TEXT, text)
        self.assertNotIn("--accept dev", text)

    def test_shared_trust_store(self):
        self.write([
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", self.serve_b())]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", self.serve_a())]}])
        _code, report, _err = self.run_check()
        self.assertNoOffer(report, "dev", "stage")
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn(SHARED_TEXT, text)

    def test_defaults(self):
        head = (f"defaults:\n  tls:\n    trustStore: {self.stores['dev']}\n"
                f"    pinnedCertificateSha256: {self.fp_a}\n")
        self.write([{"name": "dev", "servers": [("node1", self.serve_b())]}], head=head)
        _code, report, _err = self.run_check()
        self.assertNoOffer(report, "dev", "defaults")

    def test_server_override(self):
        url = self.serve_b()
        port = url.rsplit(":", 1)[1]
        self.write([{"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
                     "servers": [("node1", url)]}])
        with open(self.profile, encoding="utf-8") as handle:
            text = handle.read()
        text = text.replace(f"        baseUrl: {url}\n", f"        baseUrl: {url}\n"
                            f"        tls:\n          pinnedCertificateSha256: {OTHER}\n")
        with open(self.profile, "w", encoding="utf-8") as handle:
            handle.write(text)
        _code, report, _err = self.run_check()
        self.assertNoOffer(report, "dev", "server")
        self.assertEqual(OTHER, self.stage(report, "dev")["servers"][0]["pinned"], port)


class RejectionTest(CheckTestCase):

    def test_rejected_offer(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        state = self.tool.State(self.state_dir, "acme")
        state.remember_rejection("dev", self.fp_b, None, "rejected")
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        dev = self.stage(report, "dev")
        self.assertEqual(("changed", self.fp_b, True), (dev["status"], dev["offer"],
                                                        dev["rejected"]))
        self.assertIsNotNone(state.rejection("dev"), "kept while the stage is changed")

    def test_other_fingerprint_remembered(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        self.tool.State(self.state_dir, "acme").remember_rejection("dev", OTHER, None,
                                                                   "rejected")
        _code, report, _err = self.run_check()
        dev = self.stage(report, "dev")
        self.assertEqual((self.fp_b, False), (dev["offer"], dev["rejected"]))

    def test_cleared_when_ok_again(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        state = self.tool.State(self.state_dir, "acme")
        state.remember_rejection("dev", self.fp_b, None, "timed-out")
        code, report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertFalse(self.stage(report, "dev")["rejected"])
        self.assertIsNone(state.rejection("dev"))

    def test_busy_store_skips_the_clear(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        state = self.tool.State(self.state_dir, "acme")
        state.remember_rejection("dev", self.fp_b, None, "rejected")
        self.tool.REJECTIONS_LOCK_TIMEOUT = 0.2
        with open(state.rejections_lock_file, "a") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            code, _report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertIsNotNone(state.rejection("dev"))
        with open(state.diag_log, encoding="utf-8") as handle:
            self.assertIn("rejection", handle.read())


class TrustStoreTest(CheckTestCase):

    def test_superseded_entries(self):
        self.keytool.entries[self.stores["dev"]] = [
            self.tool.TrustEntry("dev-old", self.fp_a), self.tool.TrustEntry("dev-older", OTHER)]
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        code, report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertEqual([{"alias": "dev-older", "fingerprint": OTHER}],
                         self.stage(report, "dev")["supersededTrustStoreEntries"])
        self.assertEqual([], self.stage(report, "int")["supersededTrustStoreEntries"])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("dev-older", text)

    def test_without_cert_check_settings(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", self.serve_b())]}], java=None)
        self.tool.make_keytool = None  # must not be needed
        code, report, _err = self.run_check()
        self.assertEqual(10, code, "exit code unaffected")
        self.assertIsNone(self.stage(report, "dev")["supersededTrustStoreEntries"])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("trust store not inspected: ", text)

    def test_unreadable_store(self):
        self.keytool.broken.add(self.stores["dev"])
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        code, report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertIsNone(self.stage(report, "dev")["supersededTrustStoreEntries"])
        self.assertEqual([], self.stage(report, "int")["supersededTrustStoreEntries"])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("trust store not inspected: ", text)

    def test_listing_in_parallel_with_a_limit(self):
        self.keytool.list_delay = 1.0
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        started = time.monotonic()
        code, _report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertLess(time.monotonic() - started, 1.9, "the two stores are listed in parallel")
        self.assertEqual([15, 15], self.keytool.timeouts)

    def test_listing_is_a_separate_step(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        profile = self.tool.read_profile(self.profile)
        state = self.tool.State(self.state_dir, "acme")
        result = self.tool.run_check(profile, state, list_store=False)
        self.assertEqual(0, result.exit_code)
        self.assertEqual([], self.keytool.calls, "no keytool on the start-up path")


class OutputTest(CheckTestCase):

    def test_json_keys(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a()),
                                                       ("node2", self.hole())])
        _code, report, _err = self.run_check()
        self.assertEqual(TOP_KEYS, set(report))
        for stage in report["stages"]:
            self.assertEqual(STAGE_KEYS, set(stage))
            for server in stage["servers"]:
                self.assertEqual(SERVER_KEYS, set(server))
        self.assertNotIn("BEGIN CERTIFICATE", json.dumps(report))

    def test_text_report(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        code, text, _err = self.run_check(json_output=False)
        self.assertEqual(10, code)
        for fragment in ("Stage dev", "CHANGED", "dev/node1", "changed", "127.0.0.1",
                         self.fp_a, self.fp_b, "subject CN=selfsigned.test",
                         "issuer CN=selfsigned.test", "valid ", f"--accept dev {self.fp_b}",
                         "Stage int", "OK", "int/node1"):
            self.assertIn(fragment, text)
        self.assertNotIn("BEGIN CERTIFICATE", text)

    def test_validity_warnings(self):
        for label, days, offset, warning in (
                ("expired", 1, -10 * 86400, "WARNING: the new certificate has expired"),
                ("future", 30, 86400, "WARNING: the new certificate is not yet valid")):
            with self.subTest(label):
                cert, key, _fingerprint = make_cert(self.tmp.name, f"{label}.test", days=days,
                                                    not_before_offset=offset)
                self.two_stages([("node1", self.serve(cert, key))], [("node1", self.serve_a())])
                _code, text, _err = self.run_check(json_output=False)
                self.assertIn(warning, text)
                self.assertNotIn("not valid now", text)

    def test_skipped_http_server(self):
        self.write([{"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
                     "servers": [("node1", self.serve_a()),
                                 ("node2", f"http://plain.example.test:{closed_port()}")]}])
        code, report, _err = self.run_check()
        self.assertEqual(0, code, "the http server is not contacted")
        self.assertEqual([{"id": "dev/node2", "reason": "no TLS"}], report["skippedServers"])
        self.assertEqual(["dev/node1"], [s["id"] for s in self.stage(report, "dev")["servers"]])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("dev/node2", text)
        self.assertIn("skipped (no TLS)", text)

    def test_stage_with_http_servers_only(self):
        self.write([{"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
                     "servers": [("node1", f"http://plain.example.test:{closed_port()}")]}])
        code, report, _err = self.run_check()
        self.assertEqual(0, code)
        self.assertEqual(("ok", []), (self.stage(report, "dev")["status"],
                                      self.stage(report, "dev")["servers"]))
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn("no TLS servers", text)

    def test_offer_repeats_the_options(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        code, text, _err = self.run_check("--server-jar", "/lib/my server.jar",
                                          json_output=False)
        self.assertEqual(10, code)
        self.assertIn(f"--config {self.profile} --state-dir {self.state_dir} "
                      f"--server-jar '/lib/my server.jar' --accept dev {self.fp_b}", text)

    def test_read_only(self):
        self.two_stages([("node1", self.serve_b())], [("node1", self.serve_a())])
        files = [self.profile] + list(self.stores.values())
        before = [support.sha256_of(path) for path in files]
        self.run_check()
        self.run_check(json_output=False)
        self.assertEqual(before, [support.sha256_of(path) for path in files])
        self.assertTrue(all(call[0] == "list" for call in self.keytool.calls))

    def test_profile_option(self):
        directory = os.path.join(self.home, ".config", "inubit-mcp")
        os.makedirs(directory)
        self.profile = os.path.join(directory, "acme.yaml")
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.tool.main(["--profile", "acme", "--state-dir", self.state_dir, "--check"])
        self.assertEqual(0, code, err.getvalue())
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.tool.main(["--profile", "other", "--state-dir", self.state_dir,
                                   "--check"])
        self.assertEqual(1, code)

    def test_unreadable_profile(self):
        with open(self.profile, "w", encoding="utf-8") as handle:
            handle.write("profile:\n\tname: acme\n")
        code, out, err = self.run_check()
        self.assertEqual((1, ""), (code, out))
        self.assertEqual(1, len(err.splitlines()))
        self.assertTrue(re.search(r"tab", err))

    def test_default_state_directory(self):
        self.two_stages([("node1", self.serve_a())], [("node1", self.serve_a())])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = self.tool.main(["--config", self.profile, "--check"])
        self.assertEqual(0, code)
        self.assertTrue(os.path.isdir(os.path.join(self.home, ".inubit-mcp", "acme")))


if __name__ == "__main__":
    unittest.main()
