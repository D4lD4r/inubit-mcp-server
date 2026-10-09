"""State directory of inubit-cert-check: logs, remembered rejections, lock (task T007, C-6)."""

from __future__ import annotations

import datetime
import json
import os
import select
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import support

FP_OLD = "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:01:23:45:67:" \
    "89:AB:CD:EF:10:32:54:76:98:FF:01:02"
FP_NEW = "BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:10:20:30:40:" \
    "50:60:70:80:90:A0:B0:C0:D0:E0:F0:03"
NOW = datetime.datetime(2026, 10, 8, 15, 31, 10, tzinfo=datetime.timezone.utc)

HOLD_LOCK = """
import fcntl, sys, time
handle = open(sys.argv[1], "a")
fcntl.flock(handle, fcntl.LOCK_EX)
print("locked", flush=True)
time.sleep(float(sys.argv[2]))
"""


ADOPT_LOCK = """
import sys, time
sys.path.insert(0, sys.argv[1])
import support
state = support.load_tool().State(sys.argv[2], "acme")
lock = state.adopt_lock(int(sys.argv[3]))
print("adopted", flush=True)
time.sleep(float(sys.argv[4]))
"""

REMEMBER_MANY = """
import sys
sys.path.insert(0, sys.argv[1])
import support
state = support.load_tool().State(sys.argv[2], "acme")
for i in range(int(sys.argv[4])):
    state.remember_rejection(sys.argv[3] + str(i), "AA" * 32, None, "rejected")
"""


def mode_of(path):
    return stat.S_IMODE(os.stat(path).st_mode)


def read_line(child, timeout=10):
    """The child's next stdout line, failing instead of hanging when it never comes."""
    ready, _w, _x = select.select([child.stdout], [], [], timeout)
    if not ready:
        raise AssertionError(f"no output from the child process within {timeout} s")
    return child.stdout.readline().strip()


class StateTestCase(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        old_umask = os.umask(0o022)
        self.addCleanup(os.umask, old_umask)
        self.directory = os.path.join(self.tmp.name, "home", ".inubit-mcp", "acme")
        self.state = self.tool.State(self.directory, "acme")

    def read(self, path):
        with open(path, encoding="utf-8") as handle:
            return handle.read()


class DirectoryTest(StateTestCase):

    def test_created_owner_only(self):
        self.assertFalse(os.path.exists(self.directory), "nothing is created before it is needed")
        self.state.ensure()
        self.assertEqual(0o700, mode_of(self.directory))

    def test_missing_parents_created_owner_only(self):
        self.state.ensure()
        for path in (os.path.join(self.tmp.name, "home"),
                     os.path.join(self.tmp.name, "home", ".inubit-mcp")):
            with self.subTest(path=path):
                self.assertEqual(0o700, mode_of(path))

    def test_file_names(self):
        self.assertEqual(os.path.join(self.directory, "cert-changes.log"), self.state.changes_log)
        self.assertEqual(os.path.join(self.directory, "cert-rejections.json"),
                         self.state.rejections_file)
        self.assertEqual(os.path.join(self.directory, "cert-check.log"), self.state.diag_log)
        self.assertEqual(os.path.join(self.directory, "cert-check.lock"), self.state.lock_file)
        self.assertEqual(os.path.join(self.directory, "cert-dialog-4711.json"),
                         self.state.dialog_status_file(4711))

    def test_default_directory(self):
        home = os.path.join(self.tmp.name, "home")
        with mock.patch.dict(os.environ, {"HOME": home}):
            self.assertEqual(os.path.join(home, ".inubit-mcp", "acme"),
                             self.tool.default_state_dir("acme"))

    def test_files_owner_only(self):
        self.state.log_change("dev", "adopted", FP_OLD, FP_NEW, "2026-10-08T13:06:26Z", "cli")
        self.state.diag("hello")
        self.state.remember_rejection("dev", FP_NEW, "2026-10-08T13:06:26Z", "rejected")
        lock = self.state.try_lock()
        self.addCleanup(lock.release)
        for path in (self.state.changes_log, self.state.diag_log, self.state.rejections_file,
                     self.state.lock_file):
            with self.subTest(path=os.path.basename(path)):
                self.assertEqual(0o600, mode_of(path))


class ChangeLogTest(StateTestCase):

    def test_line_format(self):
        self.state.log_change("dev", "adopted", FP_OLD, FP_NEW, "2026-10-08T13:06:26Z", "dialog",
                              now=NOW)
        self.assertEqual(
            f"2026-10-08T15:31:10Z profile=acme stage=dev outcome=adopted old={FP_OLD} "
            f"new={FP_NEW} notBefore=2026-10-08T13:06:26Z by=dialog\n",
            self.read(self.state.changes_log))

    def test_all_outcomes_and_actors(self):
        for outcome in ("adopted", "rejected", "timed-out", "dialog-failed", "failed", "pruned"):
            for by in ("dialog", "claude", "cli"):
                self.state.log_change("int", outcome, FP_OLD, FP_NEW, "2026-10-08T13:06:26Z", by,
                                      now=NOW)
        self.assertEqual(18, len(self.read(self.state.changes_log).splitlines()))

    def test_unknown_outcome_or_actor(self):
        with self.assertRaises(ValueError):
            self.state.log_change("dev", "accepted", FP_OLD, FP_NEW, None, "cli")
        with self.assertRaises(ValueError):
            self.state.log_change("dev", "adopted", FP_OLD, FP_NEW, None, "operator")
        with self.assertRaises(ValueError):
            self.state.log_change("dev", "adopted", "AA BB", FP_NEW, None, "cli")
        self.assertFalse(os.path.exists(self.state.changes_log))

    def test_missing_values(self):
        self.state.log_change("dev", "failed", FP_OLD, None, None, "cli", now=NOW)
        self.assertEqual("2026-10-08T15:31:10Z profile=acme stage=dev outcome=failed "
                         f"old={FP_OLD} new=- notBefore=- by=cli\n",
                         self.read(self.state.changes_log))

    def test_appended_never_truncated(self):
        self.state.ensure()
        with open(self.state.changes_log, "w", encoding="utf-8") as handle:
            handle.write("earlier line\n")
        self.state.log_change("dev", "rejected", FP_OLD, FP_NEW, "2026-10-08T13:06:26Z", "dialog")
        self.state.log_change("dev", "timed-out", FP_OLD, FP_NEW, "2026-10-08T13:06:26Z",
                              "dialog")
        lines = self.read(self.state.changes_log).splitlines()
        self.assertEqual(3, len(lines))
        self.assertEqual("earlier line", lines[0])
        self.assertIn("outcome=rejected", lines[1])
        self.assertIn("outcome=timed-out", lines[2])


class DiagnosticLogTest(StateTestCase):

    def test_one_line_per_message(self):
        self.state.diag("first\nsecond", now=NOW)
        self.assertEqual("2026-10-08T15:31:10Z first second\n", self.read(self.state.diag_log))

    def test_rotated_at_one_mebibyte(self):
        self.state.ensure()
        old = "x" * 1023 + "\n"
        with open(self.state.diag_log, "w", encoding="utf-8") as handle:
            handle.write(old * 1024)
        with open(self.state.diag_log + ".1", "w", encoding="utf-8") as handle:
            handle.write("older rotation\n")
        self.state.diag("after rotation", now=NOW)
        self.assertEqual("2026-10-08T15:31:10Z after rotation\n", self.read(self.state.diag_log))
        self.assertEqual(old * 1024, self.read(self.state.diag_log + ".1"))
        self.assertEqual(0o600, mode_of(self.state.diag_log))

    def test_not_rotated_below_the_limit(self):
        self.state.ensure()
        with open(self.state.diag_log, "w", encoding="utf-8") as handle:
            handle.write("y" * 1000 + "\n")
        self.state.diag("more")
        self.assertFalse(os.path.exists(self.state.diag_log + ".1"))
        self.assertEqual(2, len(self.read(self.state.diag_log).splitlines()))


class RejectionTest(StateTestCase):

    def test_empty_without_file(self):
        self.assertEqual({}, self.state.load_rejections())
        self.assertIsNone(self.state.rejection("dev"))

    def test_schema(self):
        self.state.remember_rejection("dev", FP_NEW, "2026-10-08T13:06:26Z", "rejected", now=NOW)
        self.state.remember_rejection("int", FP_OLD, "2026-10-07T00:00:00Z", "timed-out", now=NOW)
        with open(self.state.rejections_file, encoding="utf-8") as handle:
            stored = json.load(handle)
        self.assertEqual({
            "dev": {"fingerprint": FP_NEW, "notBefore": "2026-10-08T13:06:26Z",
                    "outcome": "rejected", "at": "2026-10-08T15:31:10Z"},
            "int": {"fingerprint": FP_OLD, "notBefore": "2026-10-07T00:00:00Z",
                    "outcome": "timed-out", "at": "2026-10-08T15:31:10Z"},
        }, stored)
        self.assertEqual(stored, self.state.load_rejections())
        self.assertEqual(FP_NEW, self.state.rejection("dev")["fingerprint"])

    def test_only_rejections_and_timeouts_are_remembered(self):
        for outcome in ("adopted", "dialog-failed", "failed"):
            with self.assertRaises(ValueError):
                self.state.remember_rejection("dev", FP_NEW, None, outcome)
        self.assertFalse(os.path.exists(self.state.rejections_file))

    def test_clear(self):
        self.state.remember_rejection("dev", FP_NEW, None, "rejected")
        self.state.remember_rejection("int", FP_NEW, None, "rejected")
        self.assertTrue(self.state.clear_rejection("dev"))
        self.assertFalse(self.state.clear_rejection("dev"))
        self.assertEqual(["int"], list(self.state.load_rejections()))

    def test_save_is_atomic(self):
        self.state.remember_rejection("dev", FP_NEW, None, "rejected")
        before = self.read(self.state.rejections_file)
        with mock.patch.object(self.tool.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.state.remember_rejection("int", FP_NEW, None, "rejected")
        self.assertEqual(before, self.read(self.state.rejections_file))
        self.assertEqual(["cert-rejections.json", "cert-rejections.json.lock"],
                         sorted(os.listdir(self.directory)), "no temporary file left behind")

    def test_lock_file_of_the_store(self):
        self.state.remember_rejection("dev", FP_NEW, None, "rejected")
        self.assertEqual(self.state.rejections_file + ".lock", self.state.rejections_lock_file)
        self.assertEqual(0o600, mode_of(self.state.rejections_lock_file))
        self.assertLessEqual(self.tool.REJECTIONS_LOCK_TIMEOUT, 2)

    def test_busy_store_gives_up_quickly(self):
        self.state.remember_rejection("dev", FP_NEW, None, "rejected")
        before = self.read(self.state.rejections_file)
        LockTest.hold_lock(self, 60, self.state.rejections_lock_file)
        self.tool.REJECTIONS_LOCK_TIMEOUT = 0.3
        started = time.monotonic()
        with self.assertRaises(self.tool.StateBusy):
            self.state.clear_rejection("dev")
        with self.assertRaises(self.tool.StateBusy):
            self.state.remember_rejection("int", FP_NEW, None, "rejected")
        self.assertLess(time.monotonic() - started, 2.0)
        self.assertEqual(before, self.read(self.state.rejections_file))

    def test_not_blocked_by_the_profile_lock(self):
        LockTest.hold_lock(self, 60)
        started = time.monotonic()
        self.state.remember_rejection("dev", FP_NEW, None, "rejected")
        self.assertTrue(self.state.clear_rejection("dev"))
        self.assertLess(time.monotonic() - started, 1.0)

    def test_concurrent_updates_are_serialized(self):
        self.state.ensure()
        child = subprocess.Popen([sys.executable, "-I", "-c", REMEMBER_MANY,
                                  str(support.TESTS_DIR), self.directory, "b", "30"],
                                 stdin=subprocess.DEVNULL)
        for i in range(30):
            self.state.remember_rejection(f"a{i}", FP_NEW, None, "rejected")
        self.assertEqual(0, child.wait(timeout=60))
        stored = self.state.load_rejections()
        self.assertEqual(60, len(stored), sorted(stored))

    def test_unreadable_store(self):
        self.state.ensure()
        for content in ("{not json", "[]", '{"dev": "x"}', '{"dev": {"fingerprint": 1}}'):
            with self.subTest(content=content):
                with open(self.state.rejections_file, "w", encoding="utf-8") as handle:
                    handle.write(content)
                with self.assertRaises(self.tool.StateError):
                    self.state.load_rejections()


class AtomicWriteTest(StateTestCase):

    def test_symlink_kept_and_mode_preserved(self):
        target = os.path.join(self.tmp.name, "real", "acme.yaml")
        os.makedirs(os.path.dirname(target))
        with open(target, "w", encoding="utf-8") as handle:
            handle.write("old\n")
        os.chmod(target, 0o640)
        link = os.path.join(self.tmp.name, "acme.yaml")
        os.symlink(target, link)
        self.tool.write_atomically(link, b"new\n")
        self.assertTrue(os.path.islink(link))
        self.assertEqual("new\n", self.read(target))
        self.assertEqual(0o640, mode_of(target))
        self.assertEqual(["acme.yaml"], os.listdir(os.path.dirname(target)))

    def test_new_file_owner_only(self):
        path = os.path.join(self.tmp.name, "new.json")
        self.tool.write_atomically(path, b"{}")
        self.assertEqual(0o600, mode_of(path))

    def test_directory_synced_after_rename(self):
        synced = []

        def fsync(fd):
            synced.append(stat.S_ISDIR(os.fstat(fd).st_mode))
            return real_fsync(fd)

        real_fsync = os.fsync
        path = os.path.join(self.tmp.name, "file")
        with mock.patch.object(self.tool.os, "fsync", side_effect=fsync):
            self.tool.write_atomically(path, b"data")
        self.assertEqual([False, True], synced)


class LockTest(StateTestCase):

    def hold_lock(self, seconds, path=None):
        """A child process that holds the lock for `seconds`; returns once it holds it."""
        self.state.ensure()
        child = subprocess.Popen([sys.executable, "-I", "-c", HOLD_LOCK,
                                  path or self.state.lock_file, str(seconds)],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, text=True)
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        self.addCleanup(child.stdout.close)
        self.assertEqual("locked", read_line(child))
        return child

    def hand_over(self, seconds):
        """Takes the lock, starts a child that adopts it via its inherited fd, detaches it."""
        lock = self.state.try_lock()
        self.assertIsNotNone(lock)
        child = subprocess.Popen([sys.executable, "-I", "-c", ADOPT_LOCK, str(support.TESTS_DIR),
                                  self.directory, str(lock.fileno()), str(seconds)],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, text=True,
                                 pass_fds=[lock.fileno()])
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        self.addCleanup(child.stdout.close)
        lock.detach()
        self.assertIsNone(lock.fileno())
        self.assertEqual("adopted", read_line(child))
        return child

    def test_hand_over_keeps_the_lock_until_the_child_dies(self):
        child = self.hand_over(60)
        self.assertIsNone(self.state.try_lock(), "detach must not unlock")
        child.kill()
        child.wait()
        lock = self.state.try_lock()
        self.assertIsNotNone(lock)
        lock.release()

    def test_hand_over_released_when_the_child_ends(self):
        self.hand_over(0.3)
        lock = self.state.acquire_lock(timeout=10)
        self.assertIsNotNone(lock)
        lock.release()

    def test_adopt_refuses_another_file(self):
        self.state.ensure()
        other = os.path.join(self.directory, "other")
        fd = os.open(other, os.O_RDWR | os.O_CREAT, 0o600)
        self.addCleanup(os.close, fd)
        with self.assertRaises(self.tool.StateError):
            self.state.adopt_lock(fd)

    def test_non_blocking_fails_while_held_and_succeeds_after_kill(self):
        child = self.hold_lock(60)
        self.assertIsNone(self.state.try_lock())
        child.kill()
        child.wait()
        lock = self.state.try_lock()
        self.assertIsNotNone(lock)
        lock.release()
        lock.release()  # idempotent

    def test_blocking_with_limit_returns_busy(self):
        self.hold_lock(60)
        started = time.monotonic()
        self.assertIsNone(self.state.acquire_lock(timeout=0.5))
        elapsed = time.monotonic() - started
        self.assertGreaterEqual(elapsed, 0.45)
        self.assertLess(elapsed, 2.0)

    def test_blocking_waits_for_release(self):
        self.hold_lock(0.5)
        lock = self.state.acquire_lock(timeout=10)
        self.assertIsNotNone(lock)
        lock.release()

    def test_default_limit_is_30_seconds(self):
        self.assertEqual(30, self.tool.LOCK_TIMEOUT)

    def test_context_manager_and_exclusive_within_process(self):
        with self.state.try_lock() as lock:
            self.assertIsNotNone(lock)
            self.assertIsNone(self.state.try_lock())
        again = self.state.try_lock()
        self.assertIsNotNone(again)
        again.release()


if __name__ == "__main__":
    unittest.main()
