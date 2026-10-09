"""Start-up dialog: --interactive, the dialog worker and --forget (tasks T023-T025, C-3, C-4).

No test shows a real dialog or notification: the unit tests hand show_dialog/notify a fake
runner, and the process tests run the tool through run_with_fakes.py.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import support

OPENSSL = support.find_openssl()
if OPENSSL is not None:
    from tls_fixtures import BlackHole, TlsServer, make_cert

WRAPPER = str(support.TESTS_DIR / "run_with_fakes.py")
OSASCRIPT = "/usr/bin/osascript"
PIN = "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:01:23:45:67:89:AB:CD:EF:10:32:54:76:" \
      "98:FF:01:02"
NEW = "BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:10:20:30:40:50:60:70:80:90:A0:B0:C0:" \
      "D0:E0:F0:03"
OTHER = "CC:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:21:31:41:51:61:71:81:91:A1:B1:C1:D1:" \
        "E1:F1:04:05"
PREFILLED = b'{"jsonrpc":"2.0","id":1,"method":"initialize"}\n'


def offer(valid_now=True, not_before="2026-10-08T13:06:26Z", not_after="2036-10-05T13:06:26Z"):
    return {"stage": "dev", "profile": "acme", "pin": PIN, "fingerprint": NEW,
            "notBefore": not_before, "notAfter": not_after, "validNow": valid_now,
            "servers": [{"id": "dev/node1", "ip": "203.0.113.10"},
                        {"id": "dev/node2", "ip": "203.0.113.11"}]}


class FakeRunner:
    def __init__(self, returncode=0, stdout="", error=None):
        self.calls = []
        self.returncode, self.stdout, self.error = returncode, stdout, error

    def __call__(self, argv, input=None, timeout=None, env=None):
        self.calls.append({"argv": argv, "input": input, "timeout": timeout, "env": env})
        if self.error is not None:
            raise self.error
        return subprocess.CompletedProcess(argv, self.returncode, self.stdout, "")


class DialogTest(unittest.TestCase):
    """T023: osascript command line, result parsing, dialog and notification texts."""

    def setUp(self):
        self.tool = support.load_tool()

        def no_osascript(argv, *args, **kwargs):
            raise AssertionError(f"a test tried to run {argv[0]}")

        self.tool.dialog_runner = no_osascript

    def test_applescript_strings(self):
        self.assertEqual('"plain"', self.tool.applescript_string("plain"))
        self.assertEqual('"say \\"hi\\" \\\\ there"',
                         self.tool.applescript_string('say "hi" \\ there'))
        self.assertEqual('"one" & linefeed & "two"', self.tool.applescript_string("one\ntwo"))

    def test_osascript_argv(self):
        runner = FakeRunner(stdout="button returned:Reject, gave up:false\n")
        self.tool.show_dialog(offer(), runner=runner)
        [call] = runner.calls
        argv = call["argv"]
        self.assertIsInstance(argv, list, "argv only, never a shell")
        self.assertEqual([OSASCRIPT, "-e"], argv[:2])
        self.assertEqual(3, len(argv))
        script = argv[2]
        self.assertTrue(script.startswith("display dialog "))
        self.assertIn('buttons {"Reject", "Accept"}', script)
        self.assertIn('default button "Reject"', script)
        self.assertIn("giving up after 60", script)
        self.assertNotIn("cancel button", script)
        self.assertIn('with title "INUBIT certificate changed - stage dev (profile acme)"',
                      script)
        self.assertIn('"Server dev/node1 (203.0.113.10) presents a new certificate."', script)
        self.assertIsNone(call["input"], "stdin is /dev/null")
        self.assertGreater(call["timeout"], 60)
        self.assertEqual({"PATH", "HOME", "LC_ALL"}, set(call["env"]))

    def test_escaped_values(self):
        info = offer()
        info["servers"] = [{"id": 'dev/node1" & do shell script "x', "ip": "1\\2"}]
        runner = FakeRunner(stdout="button returned:Reject, gave up:false\n")
        self.tool.show_dialog(info, runner=runner)
        script = runner.calls[0]["argv"][2]
        self.assertIn('dev/node1\\" & do shell script \\"x', script)
        self.assertIn("1\\\\2", script)

    def test_result_parsing(self):
        cases = [
            (0, "button returned:Accept, gave up:false\n", "accept"),
            (0, "button returned:Reject, gave up:false\n", "reject"),
            (0, "button returned:, gave up:true\n", "timeout"),
            (0, "button returned:Accept, gave up:true\n", "dialog-failed"),
            (0, "button returned:OK, gave up:false\n", "dialog-failed"),
            (0, "", "dialog-failed"),
            (1, "button returned:Accept, gave up:false\n", "dialog-failed"),
            (1, "execution error: User canceled. (-128)", "dialog-failed"),
        ]
        for returncode, stdout, expected in cases:
            with self.subTest(stdout=stdout, returncode=returncode):
                self.assertEqual(expected, self.tool.parse_dialog_result(returncode, stdout))
                runner = FakeRunner(returncode=returncode, stdout=stdout)
                self.assertEqual(expected, self.tool.show_dialog(offer(), runner=runner))

    def test_dialog_cannot_run(self):
        for error in (FileNotFoundError(2, "No such file", OSASCRIPT),
                      subprocess.TimeoutExpired(OSASCRIPT, 90)):
            with self.subTest(error=type(error).__name__):
                self.assertEqual("dialog-failed",
                                 self.tool.show_dialog(offer(), runner=FakeRunner(error=error)))

    def test_title(self):
        self.assertEqual("INUBIT certificate changed - stage dev (profile acme)",
                         self.tool.dialog_title(offer()))

    def test_text(self):
        text = self.tool.dialog_text(offer())
        lines = text.split("\n")
        self.assertEqual([
            "Server dev/node1 (203.0.113.10) presents a new certificate.",
            "Server dev/node2 (203.0.113.11) presents a new certificate.",
            "",
            "Old (pin): AA:11:22:33...98:FF:01:02",  # first and last 4 pairs
            "New:       BB:22:33:44...D0:E0:F0:03",
            "valid from: 2026-10-08 13:06:26 UTC   until: 2036-10-05 13:06:26 UTC",
            "",
            "Old, full:",
            "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:",
            "01:23:45:67:89:AB:CD:EF:10:32:54:76:98:FF:01:02",
            "New, full:",
            "BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:",
            "10:20:30:40:50:60:70:80:90:A0:B0:C0:D0:E0:F0:03",
            "",
            "Accept only if the change is expected (e.g. a redeploy).",
        ], lines)
        self.assertNotIn("WARNING", text)

    def test_expired_warning(self):
        text = self.tool.dialog_text(offer(False, "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"))
        self.assertIn("\nWARNING: The new certificate has expired.\n", text)
        self.assertNotIn("not yet valid", text)

    def test_not_yet_valid_warning(self):
        text = self.tool.dialog_text(offer(False, "2099-01-01T00:00:00Z", "2100-01-01T00:00:00Z"))
        self.assertIn("\nWARNING: The new certificate is not yet valid.\n", text)
        self.assertNotIn("expired", text)

    def test_notification(self):
        text = self.tool.notification_text("dev", "acme")
        self.assertEqual("New certificate for stage dev adopted - reconnect inubit-acme "
                         "(/mcp -> Reconnect, or start a new session)", text)
        self.assertEqual("Accept for stage dev not adopted (refused) - see cert-check.log",
                         self.tool.not_adopted_text("dev", "refused"))
        runner = FakeRunner()
        self.tool.notify(text, runner=runner)
        [call] = runner.calls
        self.assertEqual([OSASCRIPT, "-e"], call["argv"][:2])
        self.assertEqual(f'display notification "{text}" with title "inubit-cert-check"',
                         call["argv"][2])
        self.assertIsNone(call["input"])

    def test_all_texts_are_ascii(self):
        texts = [self.tool.dialog_title(offer()), self.tool.dialog_text(offer()),
                 self.tool.dialog_text(offer(False, "2020-01-01T00:00:00Z",
                                             "2021-01-01T00:00:00Z")),
                 self.tool.dialog_text(offer(False, "2099-01-01T00:00:00Z",
                                             "2100-01-01T00:00:00Z")),
                 self.tool.notification_text("dev", "acme"),
                 self.tool.not_adopted_text("dev", "refused"),
                 self.tool.not_adopted_text("dev", "failed")]
        for text in texts:
            self.assertTrue(text.isascii(), text)
        runner = FakeRunner(stdout="button returned:Reject, gave up:false\n")
        self.tool.show_dialog(offer(), runner=runner)
        self.tool.notify(self.tool.notification_text("dev", "acme"), runner=runner)
        for call in runner.calls:
            self.assertTrue(all(part.isascii() for part in call["argv"]), call["argv"])

    def test_notification_failure_is_harmless(self):
        self.assertFalse(self.tool.notify("x", runner=FakeRunner(error=OSError(2, "missing"))))
        self.assertFalse(self.tool.notify("x", runner=FakeRunner(returncode=1)))


@unittest.skipIf(OPENSSL is None, "openssl not found")
class InteractiveTestCase(unittest.TestCase):
    """T024/T025: the tool as the launcher starts it, through run_with_fakes.py."""

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
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = os.path.join(self.tmp.name, "home")
        os.mkdir(self.home)
        self.profile = os.path.join(self.tmp.name, "acme.yaml")
        self.state_dir = os.path.join(self.tmp.name, "state")
        self.state = self.tool.State(self.state_dir, "acme")
        self.record = os.path.join(self.tmp.name, "calls.jsonl")
        self.stores = {}
        for stage in ("dev", "int"):
            path = os.path.join(self.tmp.name, f"acme-{stage}-truststore.p12")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(f"store {stage}\n")
            self.stores[stage] = path
        self.addCleanup(self.wait_for_workers)

    # helpers ---------------------------------------------------------------------------------

    def serve(self, cert, key):
        server = TlsServer(cert, key)
        self.addCleanup(server.close)
        return f"https://node{server.port}.example.test:{server.port}"

    def serve_a(self):
        return self.serve(self.cert_a, self.key_a)

    def serve_b(self):
        return self.serve(self.cert_b, self.key_b)

    def write(self, dev_servers=None, int_servers=None, dev_pin=None, dev_store=None):
        support.write_profile(self.profile, [
            {"name": "dev", "pin": dev_pin or self.fp_a, "store": dev_store or self.stores["dev"],
             "servers": dev_servers or [("node1", self.serve_a())]},
            {"name": "int", "pin": self.fp_a, "store": self.stores["int"],
             "servers": int_servers or [("node1", self.serve_a())]}])

    def changed_dev(self, **kwargs):
        """dev pins A and presents B: a dialog is needed."""
        self.write(dev_servers=[("node1", self.serve_b())], **kwargs)

    def snapshot(self):
        return [support.sha256_of(path) for path in [self.profile] + list(self.stores.values())]

    def start_interactive(self, dialog=None, budget=None, argv=None, **knobs):
        """Starts the tool as the launcher does (stdin a pipe with unread bytes); returns a
        handle for finish()."""
        fakes = {"dialog": dialog or {}, "record": self.record, "keytool": {
            self.stores["dev"]: [["dev-aaaaaaaa-20250101", self.fp_a]],
            self.stores["int"]: [["int-aaaaaaaa-20250101", self.fp_a]]}}
        fakes.update(knobs)
        if budget is not None:
            fakes["budget"] = budget
        handle, fakes_path = tempfile.mkstemp(prefix="fakes-", suffix=".json", dir=self.tmp.name)
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(fakes, stream)
        read_end, write_end = os.pipe()
        self.addCleanup(os.close, read_end)
        self.addCleanup(os.close, write_end)
        os.write(write_end, PREFILLED)
        env = {"HOME": self.home, "PATH": "/usr/bin:/bin"}
        started = time.monotonic()
        process = subprocess.Popen(
            [sys.executable, "-I", WRAPPER, fakes_path] + (argv or [
                "--config", self.profile, "--state-dir", self.state_dir, "--interactive"]),
            stdin=read_end, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.addCleanup(process.kill)
        return process, read_end, started

    def finish(self, handle):
        process, read_end, started = handle
        out, err = process.communicate(timeout=90)
        elapsed = time.monotonic() - started
        self.assertEqual(b"", out, "nothing on stdout (the MCP channel)")
        self.assertEqual(0, process.returncode, err)
        ready, _w, _x = select.select([read_end], [], [], 0)
        self.assertTrue(ready)
        self.assertEqual(PREFILLED, os.read(read_end, 4096), "stdin is never read")
        return err.decode("utf-8"), elapsed

    def interactive(self, dialog=None, budget=None, argv=None, **knobs):
        return self.finish(self.start_interactive(dialog, budget, argv, **knobs))

    def calls(self, kind=None):
        try:
            with open(self.record, encoding="utf-8") as handle:
                entries = [json.loads(line) for line in handle if line.strip()]
        except FileNotFoundError:
            return []
        return [entry for entry in entries if kind is None or entry["call"] == kind]

    def change_log(self):
        try:
            with open(self.state.changes_log, encoding="utf-8") as handle:
                return handle.read().splitlines()
        except FileNotFoundError:
            return []

    def diag(self):
        try:
            with open(self.state.diag_log, encoding="utf-8") as handle:
                return handle.read()
        except FileNotFoundError:
            return ""

    def worker_files(self):
        try:
            return [name for name in os.listdir(self.state_dir) if name.startswith("cert-dialog-")]
        except FileNotFoundError:
            return []

    def wait_for_workers(self, timeout=30):
        """Until every dialog worker has finished (it removes its files at the end)."""
        deadline = time.monotonic() + timeout
        while self.worker_files() and time.monotonic() < deadline:
            time.sleep(0.1)
        for entry in self.calls("show_dialog"):
            while time.monotonic() < deadline:
                try:
                    os.kill(entry["pid"], 0)
                except OSError:
                    break
                time.sleep(0.1)

    def assertNoDialog(self):
        self.assertEqual([], self.calls("show_dialog"))


class NoDialogTest(InteractiveTestCase):

    def test_all_ok(self):
        self.write()
        _err, elapsed = self.interactive()
        self.assertLessEqual(elapsed, 6.0)
        self.assertNoDialog()
        self.assertEqual([], self.calls("make_keytool"), "no keytool on the start-up path")
        self.assertEqual([], self.calls("check_config"))
        self.assertEqual([], self.calls("process"))
        self.assertEqual([], self.worker_files())

    def test_unreachable_server(self):
        hole = BlackHole()
        self.addCleanup(hole.close)
        self.write(int_servers=[("node1", self.serve_a()),
                                ("node2", f"https://hole.example.test:{hole.port}")])
        _err, elapsed = self.interactive()
        self.assertLessEqual(elapsed, 6.5)
        self.assertNoDialog()
        self.assertIn("int/node2", self.diag())

    def test_remembered_rejection(self):
        self.changed_dev()
        self.state.remember_rejection("dev", self.fp_b, None, "rejected")
        before = self.snapshot()
        err, elapsed = self.interactive(dialog={"dev": {"answer": "accept"}})
        self.assertLessEqual(elapsed, 6.0)
        self.assertNoDialog()
        self.assertEqual(1, len(err.splitlines()), err)
        self.assertIn("rejected earlier", err)
        self.assertIn("--forget dev", err)
        self.assertEqual(before, self.snapshot())

    def test_stage_without_own_tls(self):
        self.changed_dev(dev_store=self.stores["int"])  # shared trust store
        before = self.snapshot()
        self.interactive(dialog={"dev": {"answer": "accept"}})
        self.assertNoDialog()
        self.assertEqual(before, self.snapshot())
        self.assertIn("stage dev", self.diag())
        self.assertEqual([], self.change_log())

    def test_lock_held_by_another_session(self):
        self.changed_dev()
        lock = self.state.try_lock()
        self.addCleanup(lock.release)
        before = self.snapshot()
        _err, elapsed = self.interactive(dialog={"dev": {"answer": "accept"}})
        self.assertLessEqual(elapsed, 6.0)
        self.assertNoDialog()
        self.assertIn("dialog open in another session", self.diag())
        self.assertEqual(before, self.snapshot())

    def test_internal_error(self):
        with open(self.profile, "w", encoding="utf-8") as handle:
            handle.write("profile:\n\tname: acme\n")
        err, _elapsed = self.interactive()
        self.assertEqual(1, len(err.splitlines()), err)
        self.assertTrue(err.startswith("inubit-cert-check: "))

    def test_usage_error(self):
        err, _elapsed = self.interactive(argv=["--interactive", "--profile", "Bad_Name"])
        self.assertEqual(1, len(err.splitlines()), err)


class DialogOutcomeTest(InteractiveTestCase):

    def test_accept(self):
        self.changed_dev()
        _err, elapsed = self.interactive(dialog={"dev": {"answer": "accept", "delay": 1.0}})
        self.assertLess(elapsed, 15)
        [line] = self.change_log()  # written before the parent returned
        self.assertRegex(line, rf"stage=dev outcome=adopted old={self.fp_a} new={self.fp_b} "
                               r"notBefore=\S+ by=dialog$")
        self.assertEqual(self.fp_b, self.tool.read_profile(self.profile).stage("dev").pin)
        self.assertEqual([], self.calls("notify"), "no notification when the start waited")
        [dialog] = self.calls("show_dialog")
        self.assertEqual((self.fp_b, self.fp_a), (dialog["info"]["fingerprint"],
                                                  dialog["info"]["pin"]))
        self.assertEqual(["dev/node1"], [s["id"] for s in dialog["info"]["servers"]])
        self.assertNotEqual(os.getsid(0), dialog["sid"], "the worker runs in a new session")
        self.assertNotEqual(os.getpid(), dialog["pid"])
        self.assertTrue(dialog["stdout_devnull"], "the worker's stdout is /dev/null")

    def test_reject(self):
        self.changed_dev()
        before = self.snapshot()
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.wait_for_workers()
        self.assertEqual(before, self.snapshot())
        [line] = self.change_log()
        self.assertIn("outcome=rejected", line)
        self.assertIn("by=dialog", line)
        self.assertEqual(("rejected", self.fp_b),
                         (self.state.rejection("dev")["outcome"],
                          self.state.rejection("dev")["fingerprint"]))

    def test_timeout(self):
        self.changed_dev()
        before = self.snapshot()
        self.interactive(dialog={"dev": {"answer": "timeout"}})
        self.wait_for_workers()
        self.assertEqual(before, self.snapshot())
        self.assertIn("outcome=timed-out", self.change_log()[0])
        self.assertEqual("timed-out", self.state.rejection("dev")["outcome"])

    def test_dialog_failed(self):
        self.changed_dev()
        before = self.snapshot()
        self.interactive(dialog={"dev": {"answer": "dialog-failed"}})
        self.wait_for_workers()
        self.assertEqual(before, self.snapshot())
        self.assertIn("outcome=dialog-failed", self.change_log()[0])
        self.assertIsNone(self.state.rejection("dev"), "not remembered: never seen")

    def test_late_accept(self):
        self.changed_dev()
        before = self.snapshot()
        err, elapsed = self.interactive(dialog={"dev": {"answer": "accept", "delay": 5.0}},
                                        budget=3.0)
        self.assertLess(elapsed, 4.5, "the start continues after the budget")
        self.assertIn("the dialog is still open", err)
        self.assertIn("/mcp -> inubit-acme -> Reconnect, or start a new session (desktop Code tab)",
                      err)
        self.assertEqual(before, self.snapshot(), "nothing adopted yet")
        self.assertEqual([], self.change_log())
        deadline = time.monotonic() + 20
        while not self.calls("notify") and time.monotonic() < deadline:
            time.sleep(0.2)
        self.wait_for_workers()
        [line] = self.change_log()
        self.assertIn("outcome=adopted", line)
        self.assertIn("by=dialog", line)
        [note] = self.calls("notify")
        self.assertEqual("New certificate for stage dev adopted - reconnect inubit-acme "
                         "(/mcp -> Reconnect, or start a new session)", note["text"])
        self.assertEqual(self.fp_b, self.tool.read_profile(self.profile).stage("dev").pin)

    def test_worker_cleans_up(self):
        self.changed_dev()
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.wait_for_workers()
        self.assertEqual([], self.worker_files(), "status and offers files are removed")
        self.assertIsNotNone(self.state.try_lock(), "the worker released the lock")


class WorkerTest(InteractiveTestCase):
    """Review 3: decisions of other sessions, robustness, process setup, notifications."""

    def test_two_sessions_decide_once(self):
        self.changed_dev()
        first = self.start_interactive(dialog={"dev": {"answer": "reject", "delay": 1.0}})
        time.sleep(0.3)
        # the second session checks while the first dialog is open, but takes the lock only
        # after the first worker has finished
        second = self.start_interactive(dialog={"dev": {"answer": "accept"}}, lock_delay=4.0)
        self.finish(first)
        self.finish(second)
        self.wait_for_workers()
        self.assertEqual(1, len(self.calls("show_dialog")), "one dialog for one decision")
        self.assertEqual(1, len(self.change_log()))
        self.assertIn("outcome=rejected", self.change_log()[0])
        self.assertIn("rejected meanwhile", self.diag())

    def test_already_pinned_by_another_session(self):
        self.changed_dev()
        handle = self.start_interactive(dialog={"dev": {"answer": "accept"}}, lock_delay=3.0)
        time.sleep(1.5)  # the check is done; another session adopts before the lock is taken
        with open(self.profile, encoding="utf-8") as stream:
            text = stream.read()
        with open(self.profile, "w", encoding="utf-8") as stream:
            stream.write(text.replace(self.fp_a, self.fp_b, 1))
        self.finish(handle)
        self.wait_for_workers()
        self.assertNoDialog()
        self.assertIn("already pinned", self.diag())
        self.assertEqual([], self.change_log())

    def test_worker_never_starts(self):
        self.changed_dev()
        err, elapsed = self.interactive(dialog={"dev": {"answer": "accept"}}, break_worker=True)
        self.assertLess(elapsed, 6)
        self.assertIn("the dialog could not be shown (see cert-check.log)", err)
        self.assertEqual([], self.worker_files(), "the parent removes the offers file")
        lock = self.state.try_lock()
        self.assertIsNotNone(lock)
        lock.release()
        [line] = self.change_log()
        self.assertIn("stage=dev outcome=dialog-failed", line)
        self.assertTrue(line.endswith(" by=dialog"))
        self.assertIsNone(self.state.rejection("dev"), "not remembered")

    def test_worker_fails_on_an_unreadable_profile(self):
        self.changed_dev()
        handle = self.start_interactive(dialog={"dev": {"answer": "accept"}}, lock_delay=2.0)
        time.sleep(1.0)  # the check is done; the profile becomes unreadable for the worker
        with open(self.profile, "a", encoding="utf-8") as stream:
            stream.write("\tbroken: true\n")
        err, _elapsed = self.finish(handle)
        self.wait_for_workers()
        self.assertNoDialog()
        self.assertIn("the dialog could not be shown (see cert-check.log)", err)
        [line] = self.change_log()
        self.assertIn("stage=dev outcome=dialog-failed", line)
        self.assertIsNone(self.state.rejection("dev"))
        self.assertEqual([], self.worker_files())

    def test_other_remembered_fingerprint_still_gets_a_dialog(self):
        self.changed_dev()
        self.state.remember_rejection("dev", OTHER, None, "rejected")
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.wait_for_workers()
        self.assertEqual(1, len(self.calls("show_dialog")))

    def test_worker_with_unreadable_profile_removes_the_offers_file(self):
        os.makedirs(self.state_dir)
        with open(self.profile, "w", encoding="utf-8") as stream:
            stream.write("profile:\n\tname: acme\n")
        status = os.path.join(self.state_dir, "cert-dialog-4242.json")
        offers = os.path.join(self.state_dir, "cert-dialog-4242.offers.json")
        with open(offers, "w", encoding="utf-8") as stream:
            stream.write("{}")
        result = subprocess.run([sys.executable, "-I", WRAPPER, self.fakes_file(), "--config",
                                 self.profile, "--state-dir", self.state_dir, "--dialog-worker",
                                 status], stdin=subprocess.DEVNULL, capture_output=True,
                                timeout=30)
        self.assertEqual(b"", result.stdout)
        self.assertFalse(os.path.exists(offers))
        self.assertFalse(os.path.exists(status))

    def fakes_file(self):
        path = os.path.join(self.tmp.name, "plain-fakes.json")
        with open(path, "w", encoding="utf-8") as stream:
            json.dump({"record": self.record}, stream)
        return path

    def test_adoption_in_progress_at_the_deadline(self):
        self.changed_dev()
        err, elapsed = self.interactive(dialog={"dev": {"answer": "accept", "delay": 0.5}},
                                        budget=3.0, check_config_delay=4.0)
        self.assertLess(elapsed, 4.5)
        self.assertIn("adoption for stage dev in progress - reconnect when notified", err)
        self.assertIn("/mcp -> inubit-acme -> Reconnect, or start a new session (desktop Code tab)",
                      err)
        deadline = time.monotonic() + 20
        while not self.calls("notify") and time.monotonic() < deadline:
            time.sleep(0.2)
        self.wait_for_workers()
        [note] = self.calls("notify")
        self.assertEqual("New certificate for stage dev adopted - reconnect inubit-acme "
                         "(/mcp -> Reconnect, or start a new session)", note["text"])
        self.assertIn("outcome=adopted", self.change_log()[0])

    def test_failed_adoption_notifies(self):
        self.changed_dev()
        before = self.snapshot()
        err, _elapsed = self.interactive(dialog={"dev": {"answer": "accept"}},
                                         check_config_returncode=1)
        self.wait_for_workers()
        self.assertIn("stage dev: Accept given, adoption failed - see cert-check.log", err)
        self.assertEqual(before, self.snapshot())
        [note] = self.calls("notify")
        self.assertEqual("Accept for stage dev not adopted (failed) - see cert-check.log",
                         note["text"])

    def test_refused_adoption_notifies(self):
        self.changed_dev()
        self.state.ensure()
        with open(self.state.changes_log, "w", encoding="utf-8") as stream:
            stream.write("earlier\n")
        os.chmod(self.state.changes_log, 0o400)
        err, _elapsed = self.interactive(dialog={"dev": {"answer": "accept"}})
        self.wait_for_workers()
        self.assertIn("stage dev: Accept given, adoption refused - see cert-check.log", err)
        [note] = self.calls("notify")
        self.assertEqual("Accept for stage dev not adopted (refused) - see cert-check.log",
                         note["text"])

    def test_process_setup(self):
        self.changed_dev()
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.wait_for_workers()
        [popen] = self.calls("popen")
        self.assertEqual(["HOME", "LC_ALL", "PATH"], popen["env_keys"])
        self.assertEqual(("DEVNULL", "DEVNULL", "DEVNULL", True),
                         (popen["stdin"], popen["stdout"], popen["stderr"],
                          popen["start_new_session"]))
        self.assertEqual(1, len(popen["pass_fds"]))
        [dialog] = self.calls("show_dialog")
        self.assertTrue(dialog["stdin_devnull"])
        self.assertTrue(dialog["stdout_devnull"])
        self.assertTrue(dialog["stderr_devnull"])
        self.assertEqual("0o600", dialog["status_mode"])
        [start] = self.calls("worker_start")
        self.assertEqual("0o600", start["offers_mode"])
        parent = popen["pid"]
        self.assertEqual([], [u for u in self.calls("unlock") if u["pid"] == parent],
                         "the parent never unlocks the handed-over lock")

    def test_parent_terminated_while_waiting(self):
        self.changed_dev()
        for signal_name in ("SIGTERM", "SIGHUP"):
            with self.subTest(signal=signal_name):
                handle = self.start_interactive(dialog={"dev": {"answer": "reject",
                                                                "delay": 4.0}})
                deadline = time.monotonic() + 15
                while not self.calls("show_dialog") and time.monotonic() < deadline:
                    time.sleep(0.1)
                handle[0].send_signal(getattr(signal, signal_name))
                err, elapsed = self.finish(handle)
                self.assertLess(elapsed, 8)
                self.wait_for_workers()
                self.assertEqual([], self.worker_files())
                os.unlink(self.record)
                self.state.clear_rejection("dev")

    def test_interrupted_handover(self):
        self.changed_dev()
        err, _elapsed = self.interactive(dialog={"dev": {"answer": "accept"}},
                                         worker_command_raises=True)
        self.assertEqual([], self.worker_files(), "the offers file is removed")
        lock = self.state.try_lock()
        self.assertIsNotNone(lock, "the lock is released")
        lock.release()
        self.assertNoDialog()


class ForgetTest(InteractiveTestCase):
    """T025."""

    def forget(self, stage):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), \
                mock.patch.dict(os.environ, {"HOME": self.home}):
            code = self.tool.main(["--config", self.profile, "--state-dir", self.state_dir,
                                   "--forget", stage])
        return code, out.getvalue(), err.getvalue()

    def test_forget(self):
        self.write()
        self.state.remember_rejection("dev", self.fp_b, None, "rejected")
        self.state.remember_rejection("int", self.fp_b, None, "timed-out")
        code, out, err = self.forget("dev")
        self.assertEqual(0, code, err)
        self.assertEqual(1, len(out.splitlines()))
        self.assertIsNone(self.state.rejection("dev"))
        self.assertIsNotNone(self.state.rejection("int"))
        self.assertIn("dev", self.diag())

    def test_nothing_remembered(self):
        self.write()
        self.assertEqual(0, self.forget("dev")[0])

    def test_unknown_stage(self):
        self.write()
        code, _out, err = self.forget("qa")
        self.assertEqual(3, code)
        self.assertIn("qa", err)

    def test_dialog_offered_again(self):
        self.changed_dev()
        self.state.remember_rejection("dev", self.fp_b, None, "rejected")
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.assertNoDialog()
        self.assertEqual(0, self.forget("dev")[0])
        self.interactive(dialog={"dev": {"answer": "reject"}})
        self.wait_for_workers()
        self.assertEqual(1, len(self.calls("show_dialog")))


if __name__ == "__main__":
    unittest.main()
