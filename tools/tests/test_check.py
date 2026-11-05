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

    def test_marked_stage_superseded_entries(self):
        marked = support.marked_stage(self)
        fp_a, fp_b = marked["a"].fingerprint, marked["b"].fingerprint
        self.keytool.entries[self.stores["int"]] = [
            self.tool.TrustEntry("int-a", fp_a), self.tool.TrustEntry("int-b-old", fp_b),
            self.tool.TrustEntry("int-b-new", OTHER)]
        self.write([{"name": "int", "pin_per_server": True, "store": self.stores["int"],
                     "servers": [("a", f"https://int-a.example.test:{marked['a'].port}",
                                  {"pin": fp_a}),
                                 ("b", f"https://int-b.example.test:{marked['b'].port}",
                                  {"pin": OTHER})]}])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        self.assertEqual([{"alias": "int-b-old", "fingerprint": fp_b}],
                         self.stage(report, "int")["supersededTrustStoreEntries"])
        _code, text, _err = self.run_check(json_output=False)
        self.assertIn(f"      int-b-old  {fp_b}\n", text)
        self.assertNotIn("int-b-new", text)
        self.assertNotIn("      int-a  ", text)


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

    def shown(self, server_id, ip, fingerprint, name):
        return self.tool.Presented(server_id=server_id, ip=ip, fingerprint=fingerprint,
                                   subject=f"CN={name}.selfsigned.test",
                                   issuer=f"CN={name}.selfsigned.test",
                                   not_before="2026-11-03T08:00:00Z",
                                   not_after="2036-10-31T08:00:00Z", valid_now=True,
                                   pem="-----BEGIN CERTIFICATE-----")

    def marked_text(self, presented_b, b_tls=None, int_extra=None, dev_store=None, head="",
                    rejections=None):
        """check_text of a profile with a uniform stage dev and a marked stage int
        (int/a ok, int/b presents presented_b); the lines of the int block."""
        int_stage = {"name": "int", "pin_per_server": True, "store": self.stores["int"],
                     "servers": [("a", "https://int-a.example.test:8443", {"pin": MARKED_A}),
                                 ("b", "https://int-b.example.test:8443",
                                  b_tls or {"pin": MARKED_C})]}
        int_stage.update(int_extra or {})
        self.write([{"name": "dev", "pin": self.fp_a, "store": dev_store or self.stores["dev"],
                     "servers": [("node1", "https://dev-1.example.test:8443")]}, int_stage],
                   head=head)
        profile = self.tool.read_profile(self.profile)
        presented = {"dev/node1": self.shown("dev/node1", "203.0.113.11", self.fp_a, "dev-1"),
                     "int/a": self.shown("int/a", "203.0.113.21", MARKED_A, "int-a"),
                     "int/b": self.shown("int/b", "203.0.113.22", presented_b, "int-b")}
        stages = tuple(self.tool.evaluate_stage(stage, presented, None, (), None,
                                                rejections=rejections or {})
                       for stage in profile.stages)
        result = self.tool.CheckResult(profile=profile, checked_at="2026-11-03T10:00:00Z",
                                       stages=stages, exit_code=10)
        text = self.tool.check_text(result, "--profile acme")
        self.assertTrue(text.startswith("Stage dev  OK  (pin "), text)
        return ("Stage int" + text.split("Stage int", 1)[1]).splitlines()

    def test_marked_stage_text_report(self):
        offer = f"      offer: inubit-cert-check --profile acme --accept int/b {MARKED_D}"
        self.assertEqual([
            "Stage int  CHANGED  (one pin per server)",
            "  int/a        ok           203.0.113.21",
            "  int/b        changed      203.0.113.22",
            f"      old  {MARKED_C}",
            f"      new  {MARKED_D}",
            "      subject CN=int-b.selfsigned.test  issuer CN=int-b.selfsigned.test",
            "      valid 2026-11-03T08:00:00Z … 2036-10-31T08:00:00Z",
            offer,
            "  superseded trust-store entries: none"], self.marked_text(MARKED_D))

        lines = self.marked_text(MARKED_A)
        self.assertEqual([f"      new  {MARKED_A}",
                          "      subject CN=int-b.selfsigned.test  issuer CN=int-b.selfsigned.test",
                          "      valid 2026-11-03T08:00:00Z … 2036-10-31T08:00:00Z",
                          f"      offer: inubit-cert-check --profile acme --accept int/b {MARKED_A}",
                          "      note: this is the certificate expected for int/a"], lines[4:9])

        rejection = {"fingerprint": MARKED_D, "notBefore": None, "outcome": "rejected",
                     "at": "2026-11-03T09:00:00Z"}
        lines = self.marked_text(MARKED_D, rejections={"int/b": rejection})
        self.assertEqual([offer, f"      rejected earlier: {MARKED_D} (offered again only after "
                                 "--forget int/b)"], lines[7:9])

        problem = ("stage 'int': tls.pinnedCertificateSha256 is not allowed with "
                   "tls.pinPerServer; set it on each server")
        cases = (
            ("pin through alias", {"b_tls": {"alias": "pinB"},
                                   "head": f"x-acme-pin-b: &pinB\n  pinnedCertificateSha256: "
                                           f"{MARKED_C}\n"}),
            ("trust store not the stage's own", {"dev_store": self.stores["int"]}),
            ("configuration problem", {"int_extra": {"pin": MARKED_A}}))
        for reason, options in cases:
            with self.subTest(reason):
                lines = self.marked_text(MARKED_D, **options)
                self.assertEqual("Stage int  CHANGED  (one pin per server)", lines[0])
                self.assertEqual(f"      no offer: {reason}", lines[7])
                self.assertFalse([line for line in lines if "offer:" in line
                                  and "no offer:" not in line], lines)
                self.assertNotIn(SHARED_TEXT, "\n".join(lines))
                self.assertNotIn("no pin configured", "\n".join(lines))
                expected = [f"  configuration problem: {problem} - run --check-config"] \
                    if reason == "configuration problem" else []
                self.assertEqual(expected, [line for line in lines
                                            if line.startswith("  configuration problem:")])

    def test_uniform_stage_text_report_is_unchanged(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", "https://dev-1.example.test:8443")]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["int"],
             "servers": [("node1", "https://int-1.example.test:8443"),
                         ("node2", "https://int-2.example.test:8443"),
                         ("node3", "http://int-3.example.test:8080")]},
            {"name": "qa", "pin": self.fp_a, "store": self.stores["int"],
             "servers": [("node1", "https://qa-1.example.test:8443")]}])
        profile = self.tool.read_profile(self.profile)
        tool = self.tool
        presented = {
            "dev/node1": self.shown("dev/node1", "203.0.113.11", OTHER, "dev-1"),
            "int/node1": self.shown("int/node1", "203.0.113.21", self.fp_a, "int-1"),
            "int/node2": tool.Presented(server_id="int/node2", ip=None, error="connect"),
            "qa/node1": self.shown("qa/node1", "203.0.113.31", OTHER, "qa-1")}
        dev = tool.evaluate_stage(profile.stage("dev"), presented,
                                  {"fingerprint": OTHER, "notBefore": None,
                                   "outcome": "rejected", "at": "2026-11-03T09:00:00Z"},
                                  (tool.TrustEntry("dev-older", OTHER),), None)
        int_ = tool.evaluate_stage(profile.stage("int"), presented, None, None, "keytool failed")
        qa = tool.evaluate_stage(profile.stage("qa"), presented, None, (), None)
        result = tool.CheckResult(profile=profile, checked_at="2026-11-03T10:00:00Z",
                                  stages=(dev, int_, qa), exit_code=10)
        self.assertEqual(GOLDEN_006_TEXT % {"pin": self.fp_a, "other": OTHER,
                                            "short": tool.short_fingerprint(self.fp_a)},
                         tool.check_text(result, "--profile acme"))


MARKED_STAGE_KEYS = STAGE_KEYS | {"pinPerServer", "problem"}
MARKED_SERVER_KEYS = SERVER_KEYS | {"offer", "rejected", "expectedBy", "noOfferReason"}
NO_OFFER_REASONS = {"pin through alias", "trust store not the stage's own",
                    "configuration problem"}
MARKED_A = ":".join(["AA"] * 31 + ["01"])
MARKED_C = ":".join(["CC"] * 31 + ["03"])
MARKED_D = ":".join(["DD"] * 31 + ["04"])

# The --check text report of feature 006 for uniform stages, byte for byte (FR-007, SC-005).
GOLDEN_006_TEXT = """\
Stage dev  CHANGED  (pin %(short)s)
  dev/node1    changed      203.0.113.11
      old  %(pin)s
      new  %(other)s
      subject CN=dev-1.selfsigned.test  issuer CN=dev-1.selfsigned.test
      valid 2026-11-03T08:00:00Z … 2036-10-31T08:00:00Z
  superseded trust-store entries:
      dev-older  %(other)s
  offer: inubit-cert-check --profile acme --accept dev %(other)s  (rejected earlier in the dialog)
Stage int  UNREACHABLE  (pin %(short)s)
  int/node1    ok           203.0.113.21
  int/node2    unreachable  -  (connect)
  int/node3    skipped (no TLS)
  trust store not inspected: keytool failed
Stage qa  CHANGED  (pin %(short)s)
  qa/node1     changed      203.0.113.31
      old  %(pin)s
      new  %(other)s
      subject CN=qa-1.selfsigned.test  issuer CN=qa-1.selfsigned.test
      valid 2026-11-03T08:00:00Z … 2036-10-31T08:00:00Z
  superseded trust-store entries: none
  shared TLS settings — give the stage its own tls block first
"""

# The --check --json document of feature 006 for uniform stages, byte for byte (FR-007, SC-005).
GOLDEN_006_JSON = """{
  "profile": "acme",
  "checkedAt": "2026-11-03T10:00:00Z",
  "exitCode": 10,
  "stages": [
    {
      "stage": "dev",
      "pin": "%(pin)s",
      "status": "changed",
      "conflict": false,
      "offer": "%(other)s",
      "rejected": true,
      "pinSource": "stage",
      "ownTls": true,
      "supersededTrustStoreEntries": [
        {
          "alias": "dev-older",
          "fingerprint": "%(other)s"
        }
      ],
      "servers": [
        {
          "id": "dev/node1",
          "baseUrl": "https://dev-1.example.test:8443",
          "ip": "203.0.113.11",
          "status": "changed",
          "pinned": "%(pin)s",
          "presented": "%(other)s",
          "subject": "CN=dev-1.selfsigned.test",
          "issuer": "CN=dev-1.selfsigned.test",
          "notBefore": "2026-11-03T08:00:00Z",
          "notAfter": "2036-10-31T08:00:00Z",
          "validNow": true,
          "error": null
        }
      ]
    },
    {
      "stage": "int",
      "pin": "%(pin)s",
      "status": "unreachable",
      "conflict": false,
      "offer": null,
      "rejected": false,
      "pinSource": "stage",
      "ownTls": true,
      "supersededTrustStoreEntries": null,
      "servers": [
        {
          "id": "int/node1",
          "baseUrl": "https://int-1.example.test:8443",
          "ip": "203.0.113.21",
          "status": "ok",
          "pinned": "%(pin)s",
          "presented": "%(pin)s",
          "subject": "CN=int-1.selfsigned.test",
          "issuer": "CN=int-1.selfsigned.test",
          "notBefore": "2026-11-03T08:00:00Z",
          "notAfter": "2036-10-31T08:00:00Z",
          "validNow": true,
          "error": null
        },
        {
          "id": "int/node2",
          "baseUrl": "https://int-2.example.test:8443",
          "ip": null,
          "status": "unreachable",
          "pinned": "%(pin)s",
          "presented": null,
          "subject": null,
          "issuer": null,
          "notBefore": null,
          "notAfter": null,
          "validNow": null,
          "error": "connect"
        }
      ]
    }
  ],
  "skippedServers": [
    {
      "id": "int/node3",
      "reason": "no TLS"
    }
  ]
}
"""


class PinPerServerCheckTest(CheckTestCase):
    """--check of a stage with tls.pinPerServer (feature 014, T013, C-1′, research R-8)."""

    def setUp(self):
        super().setUp()
        self.marked = support.marked_stage(self)

    def url(self, node, port=None):
        port = self.marked[node].port if port is None else port
        return f"https://int-{node}.example.test:{port}"

    def serve_node_cert(self, node):
        """A further TlsServer presenting the certificate of `node`; its URL."""
        server = TlsServer(self.marked[node].cert, self.marked[node].key)
        self.addCleanup(server.close)
        return f"https://int-x.example.test:{server.port}"

    def fp(self, node):
        return self.marked[node].fingerprint

    def write_marked(self, servers, int_extra=None, dev_store=None, head=""):
        """dev: uniform stage, one ok server; int: marked stage with the given servers
        ((node, url, tls dict))."""
        int_stage = {"name": "int", "pin_per_server": True, "store": self.stores["int"],
                     "servers": servers}
        int_stage.update(int_extra or {})
        self.write([{"name": "dev", "pin": self.fp_a, "store": dev_store or self.stores["dev"],
                     "servers": [("node1", self.serve_a())]}, int_stage], head=head)

    def own_pins(self):
        return [("a", self.url("a"), {"pin": self.fp("a")}),
                ("b", self.url("b"), {"pin": self.fp("b")})]

    def server(self, stage, server_id):
        return next(server for server in stage["servers"] if server["id"] == server_id)

    def assertNoOfferFields(self, server):
        self.assertEqual((None, False, None),
                         (server["offer"], server["rejected"], server["noOfferReason"]))

    def test_distinct_certificates_are_ok_without_conflict(self):
        self.write_marked(self.own_pins())
        code, report, _err = self.run_check()
        self.assertEqual((0, 0), (code, report["exitCode"]))
        int_ = self.stage(report, "int")
        self.assertEqual(("ok", False, None, None),
                         (int_["status"], int_["conflict"], int_["offer"], int_["rejected"]))
        for node in ("a", "b"):
            server = self.server(int_, f"int/{node}")
            self.assertEqual(("ok", self.fp(node), self.fp(node), []),
                             (server["status"], server["pinned"], server["presented"],
                              server["expectedBy"]))
            self.assertNoOfferFields(server)

    def test_one_server_marked_stage(self):
        self.write_marked([("a", self.url("a"), {"pin": self.fp("a")})])
        code, report, err = self.run_check()
        self.assertEqual((0, ""), (code, err))
        int_ = self.stage(report, "int")
        self.assertEqual(("ok", False, True, None),
                         (int_["status"], int_["conflict"], int_["pinPerServer"], int_["problem"]))
        self.assertEqual([], int_["servers"][0]["expectedBy"])
        self.assertNoOfferFields(int_["servers"][0])

    def test_all_servers_same_certificate(self):
        self.write_marked([("a", self.url("a"), {"pin": self.fp("a")}),
                           ("b", self.serve_node_cert("a"), {"pin": self.fp("a")})])
        code, report, err = self.run_check()
        self.assertEqual((0, ""), (code, err))
        int_ = self.stage(report, "int")
        self.assertEqual(("ok", False), (int_["status"], int_["conflict"]))
        for server in int_["servers"]:
            self.assertEqual(("ok", []), (server["status"], server["expectedBy"]),
                             "no warning when every server presents its own pin")
            self.assertNoOfferFields(server)

    def test_server_presenting_other_servers_certificate_is_changed(self):
        self.write_marked([("a", self.serve_node_cert("b"), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": self.fp("b")})])
        code, report, _err = self.run_check()
        self.assertEqual((10, 10), (code, report["exitCode"]))
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", False), (int_["status"], int_["conflict"]))
        server_a = self.server(int_, "int/a")
        self.assertEqual(("changed", self.fp("a"), self.fp("b"), ["int/b"], self.fp("b")),
                         (server_a["status"], server_a["pinned"], server_a["presented"],
                          server_a["expectedBy"], server_a["offer"]))
        server_b = self.server(int_, "int/b")
        self.assertEqual(("ok", []), (server_b["status"], server_b["expectedBy"]))

    def test_changed_server_gets_offer(self):
        self.write_marked([("a", self.url("a"), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": OTHER})])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", False, None, None),
                         (int_["status"], int_["conflict"], int_["offer"], int_["rejected"]))
        server_b = self.server(int_, "int/b")
        self.assertEqual(("changed", OTHER, self.fp("b"), self.fp("b"), False, [], None),
                         (server_b["status"], server_b["pinned"], server_b["presented"],
                          server_b["offer"], server_b["rejected"], server_b["expectedBy"],
                          server_b["noOfferReason"]))
        server_a = self.server(int_, "int/a")
        self.assertEqual("ok", server_a["status"])
        self.assertNoOfferFields(server_a)

    def test_changed_and_unreachable(self):
        self.write_marked([("a", self.url("a", closed_port()), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": OTHER})])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", False, None), (int_["status"], int_["conflict"],
                                                   int_["offer"]))
        server_a = self.server(int_, "int/a")
        self.assertEqual(("unreachable", "connect", None, None, []),
                         (server_a["status"], server_a["error"], server_a["offer"],
                          server_a["noOfferReason"], server_a["expectedBy"]))
        self.assertEqual(("changed", self.fp("b")), (self.server(int_, "int/b")["status"],
                                                     self.server(int_, "int/b")["offer"]))

    def test_unreachable_only_exits_20(self):
        self.write_marked([("a", self.url("a", closed_port()), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": self.fp("b")})])
        code, report, _err = self.run_check()
        self.assertEqual(20, code)
        self.assertEqual("unreachable", self.stage(report, "int")["status"])
        self.assertNoOfferFields(self.server(self.stage(report, "int"), "int/a"))

    def test_no_offer_reasons(self):
        alias_head = f"x-acme-pin-b: &pinB\n  pinnedCertificateSha256: {OTHER}\n"
        cases = (
            ("pin through alias", {"servers": [("a", self.url("a"), {"pin": self.fp("a")}),
                                               ("b", self.url("b"), {"alias": "pinB"})]},
             alias_head, None),
            ("trust store not the stage's own", {}, "", self.stores["int"]),
            ("configuration problem", {"pin": self.fp("a")}, "", None))
        for reason, extra, head, dev_store in cases:
            with self.subTest(reason):
                servers = extra.pop("servers", [("a", self.url("a"), {"pin": self.fp("a")}),
                                                ("b", self.url("b"), {"pin": OTHER})])
                self.write_marked(servers, int_extra=extra, dev_store=dev_store, head=head)
                code, report, _err = self.run_check()
                self.assertEqual(10, code)
                int_ = self.stage(report, "int")
                server_b = self.server(int_, "int/b")
                self.assertEqual(("changed", None, False, reason),
                                 (server_b["status"], server_b["offer"], server_b["rejected"],
                                  server_b["noOfferReason"]))
                self.assertIn(server_b["noOfferReason"], NO_OFFER_REASONS)
                self.assertEqual(reason == "configuration problem", int_["problem"] is not None)
                self.assertEqual(reason != "trust store not the stage's own", int_["ownTls"])
                self.assertNoOfferFields(self.server(int_, "int/a"))

    def test_problem_reported(self):
        self.write_marked([("a", self.url("a"), {"pin": OTHER, "store": self.stores["int"]}),
                           ("b", self.url("b"), {"pin": OTHER})])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertIn("int/a: tls.trustStore is not allowed", int_["problem"])
        self.assertEqual(("changed", False, None), (int_["status"], int_["conflict"],
                                                   int_["offer"]))
        for node in ("a", "b"):
            server = self.server(int_, f"int/{node}")
            self.assertEqual(("changed", self.fp(node), None, "configuration problem"),
                             (server["status"], server["presented"], server["offer"],
                              server["noOfferReason"]), "servers are still compared")

    def test_rejected_per_server(self):
        self.write_marked([("a", self.url("a"), {"pin": OTHER}),
                           ("b", self.url("b"), {"pin": OTHER})])
        state = self.tool.State(self.state_dir, "acme")
        state.remember_rejection("int/b", self.fp("b"), None, "rejected")
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        server_b = self.server(int_, "int/b")
        self.assertEqual(state.rejection("int/b")["fingerprint"], server_b["offer"])
        self.assertEqual(("changed", self.fp("b"), True, None),
                         (server_b["status"], server_b["offer"], server_b["rejected"],
                          server_b["noOfferReason"]))
        server_a = self.server(int_, "int/a")
        self.assertEqual(("changed", self.fp("a"), False),
                         (server_a["status"], server_a["offer"], server_a["rejected"]))
        self.assertIsNone(int_["rejected"])

    def test_check_never_changes_profile_or_store(self):
        self.write_marked([("a", self.url("a"), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": OTHER})])
        files = [self.profile] + list(self.stores.values())
        before = [support.sha256_of(path) for path in files]
        code, _report, _err = self.run_check()
        self.assertEqual(10, code)
        self.assertEqual(before, [support.sha256_of(path) for path in files])
        self.assertTrue(all(call[0] == "list" for call in self.keytool.calls))

    def test_unmarked_stage_with_two_certificates_still_conflicts(self):
        self.write([{"name": "int", "pin": OTHER, "store": self.stores["int"],
                     "servers": [("a", self.url("a")), ("b", self.url("b"))]}])
        code, report, _err = self.run_check()
        self.assertEqual(10, code)
        int_ = self.stage(report, "int")
        self.assertEqual(("changed", True, None, False),
                         (int_["status"], int_["conflict"], int_["offer"], int_["rejected"]))
        self.assertEqual(STAGE_KEYS, set(int_))
        for server in int_["servers"]:
            self.assertEqual(SERVER_KEYS, set(server))

    def test_json_of_a_marked_stage(self):
        self.write_marked([("a", self.url("a"), {"pin": self.fp("a")}),
                           ("b", self.url("b"), {"pin": OTHER})])
        _code, report, _err = self.run_check()
        self.assertEqual(TOP_KEYS, set(report))
        int_ = self.stage(report, "int")
        self.assertEqual(MARKED_STAGE_KEYS, set(int_))
        self.assertEqual((True, None, "server", False, None, None, True, None),
                         (int_["pinPerServer"], int_["pin"], int_["pinSource"], int_["conflict"],
                          int_["offer"], int_["rejected"], int_["ownTls"], int_["problem"]))
        self.assertEqual(["int/a", "int/b"], [server["id"] for server in int_["servers"]])
        for server, pin in zip(int_["servers"], (self.fp("a"), OTHER)):
            self.assertEqual(MARKED_SERVER_KEYS, set(server))
            self.assertEqual(pin, server["pinned"], "its own pin")
            self.assertIsInstance(server["expectedBy"], list)
            self.assertIsInstance(server["rejected"], bool)
        dev = self.stage(report, "dev")
        self.assertEqual(STAGE_KEYS, set(dev), "no new key on a uniform stage")
        for server in dev["servers"]:
            self.assertEqual(SERVER_KEYS, set(server))
        self.assertNotIn("BEGIN CERTIFICATE", json.dumps(report))

    def test_uniform_stage_json_is_byte_identical_to_006(self):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": self.fp_a, "store": self.stores["dev"],
             "servers": [("node1", "https://dev-1.example.test:8443")]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["int"],
             "servers": [("node1", "https://int-1.example.test:8443"),
                         ("node2", "https://int-2.example.test:8443"),
                         ("node3", "http://int-3.example.test:8080")]}])
        profile = self.tool.read_profile(self.profile)
        tool = self.tool

        def shown(server_id, ip, fingerprint, name):
            return tool.Presented(server_id=server_id, ip=ip, fingerprint=fingerprint,
                                  subject=f"CN={name}.selfsigned.test",
                                  issuer=f"CN={name}.selfsigned.test",
                                  not_before="2026-11-03T08:00:00Z",
                                  not_after="2036-10-31T08:00:00Z", valid_now=True,
                                  pem="-----BEGIN CERTIFICATE-----")

        presented = {
            "dev/node1": shown("dev/node1", "203.0.113.11", OTHER, "dev-1"),
            "int/node1": shown("int/node1", "203.0.113.21", self.fp_a, "int-1"),
            "int/node2": tool.Presented(server_id="int/node2", ip=None, error="connect")}
        dev = tool.evaluate_stage(profile.stage("dev"), presented,
                                  {"fingerprint": OTHER, "notBefore": None,
                                   "outcome": "rejected", "at": "2026-11-03T09:00:00Z"},
                                  (tool.TrustEntry("dev-older", OTHER),), None)
        int_ = tool.evaluate_stage(profile.stage("int"), presented, None, None, "keytool failed")
        result = tool.CheckResult(profile=profile, checked_at="2026-11-03T10:00:00Z",
                                  stages=(dev, int_), exit_code=10)
        text = json.dumps(tool.check_json(result), indent=2, ensure_ascii=False) + "\n"
        self.assertEqual(GOLDEN_006_JSON % {"pin": self.fp_a, "other": OTHER}, text)


if __name__ == "__main__":
    unittest.main()
