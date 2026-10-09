"""Command line skeleton of inubit-cert-check (task T003, contract C-1 ... C-5 "Common options")."""

from __future__ import annotations

import contextlib
import io
import os
import stat
import sys
import tempfile
import unittest
from unittest import mock

import support


def run_main(tool, argv):
    """main(argv) with stdout and stderr captured: (exit code, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = tool.main(argv)
    return code, out.getvalue(), err.getvalue()


class FileTest(unittest.TestCase):

    def test_shebang_isolated_system_python(self):
        with open(support.TOOL_PATH, encoding="utf-8") as handle:
            self.assertEqual("#!/usr/bin/python3 -I\n", handle.readline())

    def test_executable(self):
        mode = stat.S_IMODE(os.stat(support.TOOL_PATH).st_mode)
        self.assertEqual(0o755, mode)


class ExitCodeTest(unittest.TestCase):

    def test_constants(self):
        tool = support.load_tool()
        self.assertEqual((0, 1, 2, 3, 4, 10, 20),
                         (tool.OK, tool.INTERNAL, tool.USAGE, tool.REFUSED, tool.ROLLED_BACK,
                          tool.CHANGED, tool.UNREACHABLE))


class UsageTest(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config = os.path.join(self.tmp.name, "acme.yaml")

    def assertUsageError(self, argv):
        code, out, err = run_main(self.tool, argv)
        self.assertEqual(self.tool.USAGE, code, err)
        self.assertEqual("", out)
        self.assertTrue(err.strip(), "usage errors are explained on stderr")

    def test_no_arguments(self):
        self.assertUsageError([])

    def test_command_required(self):
        self.assertUsageError(["--config", self.config])

    def test_profile_or_config_required(self):
        self.assertUsageError(["--check"])

    def test_profile_and_config_exclusive(self):
        self.assertUsageError(["--profile", "acme", "--config", self.config, "--check"])

    def test_commands_exclusive(self):
        self.assertUsageError(["--config", self.config, "--check", "--accept", "dev", "AA" * 32])
        self.assertUsageError(["--config", self.config, "--check", "--forget", "dev"])
        self.assertUsageError(["--config", self.config, "--prune", "dev", "--accept", "dev",
                               "AA" * 32])

    def test_profile_name_pattern(self):
        for name in ("Acme", "acme_1", "-acme", "a" * 33, "", "../acme", "audit"):
            with self.subTest(name=name):
                self.assertUsageError(["--profile", name, "--check"])

    def test_options_only_with_their_command(self):
        self.assertUsageError(["--config", self.config, "--forget", "dev", "--json"])
        self.assertUsageError(["--config", self.config, "--check", "--by", "cli"])
        self.assertUsageError(["--config", self.config, "--check", "--yes"])
        self.assertUsageError(["--config", self.config, "--forget", "dev", "--yes"])

    def test_accept_needs_stage_and_fingerprint(self):
        self.assertUsageError(["--config", self.config, "--accept", "dev"])

    def test_by_dialog_is_internal(self):
        self.assertUsageError(["--config", self.config, "--accept", "dev", "AA" * 32,
                               "--by", "dialog"])

    def test_dialog_worker_needs_profile_or_config(self):
        self.assertUsageError(["--dialog-worker", os.path.join(self.tmp.name, "s.json")])

    def test_no_abbreviations(self):
        self.assertUsageError(["--conf", self.config, "--check"])
        self.assertUsageError(["--config", self.config, "--chec"])
        self.assertUsageError(["--config", self.config, "--interact"])

    def test_help(self):
        code, out, _err = run_main(self.tool, ["--help"])
        self.assertEqual(0, code)
        for option in ("--check", "--json", "--accept", "--by", "--interactive", "--forget",
                       "--prune", "--yes", "--profile", "--config", "--state-dir", "--java",
                       "--server-jar"):
            self.assertIn(option, out)
        self.assertNotIn("--dialog-worker", out, "the worker option is hidden")

    def test_help_texts_match_the_contract(self):
        _code, out, _err = run_main(self.tool, ["--help"])
        text = " ".join(out.split())
        for fragment in (
                "read-only check of every server (exit 0 all ok, 10 a certificate changed, "
                "20 a server unreachable)",
                "--json", "adopt the certificate every server of the stage presents",
                "start-up check for the launcher: dialog for changed stages, always exit 0, "
                "nothing on stdout",
                "forget the remembered rejection of a stage",
                "list the superseded trust-store entries of a stage (remove them with --yes)",
                "3 refused (nothing changed)", "4 failed and rolled back",
                "~/.config/inubit-mcp/NAME.yaml", "~/.inubit-mcp/<profile.name>"):
            self.assertIn(fragment, text)


class ProfilePathTest(unittest.TestCase):

    def test_profile_file_below_config_directory(self):
        tool = support.load_tool()
        with tempfile.TemporaryDirectory() as home, mock.patch.dict(os.environ, {"HOME": home}):
            self.assertEqual(os.path.join(home, ".config", "inubit-mcp", "acme.yaml"),
                             tool.profile_path("acme"))


class WorkerCommandTest(unittest.TestCase):

    def test_worker_command_line(self):
        tool = support.load_tool()
        options = ["--config", "/profiles/acme.yaml", "--state-dir", "/state"]
        self.assertEqual([sys.executable, "-I", str(support.TOOL_PATH), "--dialog-worker",
                          "/state/cert-dialog-1.json"] + options,
                         tool.worker_command("/state/cert-dialog-1.json", options))


class InternalErrorTest(unittest.TestCase):

    def test_unexpected_exception_is_one_line_and_exit_1(self):
        tool = support.load_tool()

        def explode(_args):
            raise RuntimeError("boom\nsecond line")

        tool.run = explode
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = run_main(tool, ["--config", os.path.join(tmp, "acme.yaml"),
                                             "--check"])
        self.assertEqual(tool.INTERNAL, code)
        self.assertEqual("", out)
        self.assertEqual(1, len(err.splitlines()), err)
        self.assertIn("boom", err)
        self.assertTrue(err.startswith("inubit-cert-check: "), err)


    def test_internal_error_also_in_diagnostic_log(self):
        tool = support.load_tool()
        with tempfile.TemporaryDirectory() as tmp:
            state = tool.State(os.path.join(tmp, "state"), "acme")

            def explode(_args):
                tool.use_state(state)
                raise KeyError("missing")

            tool.run = explode
            code, _out, err = run_main(tool, ["--config", os.path.join(tmp, "acme.yaml"),
                                              "--check"])
            self.assertEqual(tool.INTERNAL, code)
            with open(state.diag_log, encoding="utf-8") as handle:
                lines = handle.read().splitlines()
        self.assertEqual(1, len(lines))
        self.assertIn("internal error: KeyError", lines[0])
        self.assertIn("internal error: KeyError", err)


class InteractiveExitTest(unittest.TestCase):
    """--interactive never fails the launcher (contract C-3): exit 0 after one stderr line."""

    def setUp(self):
        self.tool = support.load_tool()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def assertQuietZero(self, argv):
        code, out, err = run_main(self.tool, argv)
        self.assertEqual(0, code, err)
        self.assertEqual("", out)
        self.assertEqual(1, len(err.splitlines()), err)
        self.assertTrue(err.startswith("inubit-cert-check: "), err)

    def test_usage_error(self):
        self.assertQuietZero(["--interactive", "--profile", "Not_Valid"])
        self.assertQuietZero(["--interactive", "--check", "--profile", "acme"])
        self.assertQuietZero(["--interactive"])

    def test_configuration_error(self):
        def unreadable(_args):
            raise self.tool.ConfigError("cannot read the profile file")

        self.tool.run = unreadable
        self.assertQuietZero(["--interactive", "--config",
                              os.path.join(self.tmp.name, "missing.yaml")])

    def test_help_goes_to_stderr(self):
        code, out, err = run_main(self.tool, ["--interactive", "--help"])
        self.assertEqual((0, ""), (code, out))
        self.assertIn("--interactive", err)

    def test_base_exception(self):
        def interrupted(_args):
            raise KeyboardInterrupt()

        self.tool.run = interrupted
        self.assertQuietZero(["--interactive", "--config", os.path.join(self.tmp.name, "a.yaml")])

    def test_internal_error(self):
        def explode(_args):
            raise RuntimeError("boom")

        self.tool.run = explode
        self.assertQuietZero(["--interactive", "--config", os.path.join(self.tmp.name, "a.yaml")])


class RunWithFakesTest(unittest.TestCase):
    """The test-only wrapper replaces dialog and notification by scripted fakes."""

    def test_install_fakes(self):
        import run_with_fakes
        with tempfile.TemporaryDirectory() as tmp:
            record = os.path.join(tmp, "calls.jsonl")
            tool = run_with_fakes.install_fakes(support.load_tool(), {
                "dialog": {"dev": {"answer": "accept", "delay": 0.2}}, "record": record})
            self.assertEqual("accept", tool.show_dialog({"stage": "dev"}))
            self.assertEqual("dialog-failed", tool.show_dialog({"stage": "int"}))
            tool.notify("Stage dev")
            with open(record, encoding="utf-8") as handle:
                calls = [line for line in handle.read().splitlines() if line]
            self.assertEqual(3, len(calls))
            self.assertIn('"notify"', calls[2])

    def test_worker_runs_through_the_wrapper(self):
        import run_with_fakes
        tool = run_with_fakes.install_fakes(support.load_tool(), {}, "/tmp/fakes.json")
        argv = tool.worker_command("/state/s.json", ["--config", "/p/acme.yaml"])
        self.assertEqual([sys.executable, "-I", run_with_fakes.__file__, "/tmp/fakes.json",
                          "--dialog-worker", "/state/s.json", "--config", "/p/acme.yaml"],
                         argv)

    def test_fails_loudly_without_worker_command(self):
        import types
        import run_with_fakes
        tool = types.SimpleNamespace(cmd_dialog_worker=lambda args: 1, main=lambda argv: 0)
        with self.assertRaises(RuntimeError):
            run_with_fakes.install_fakes(tool, {}, "/tmp/fakes.json")


if __name__ == "__main__":
    unittest.main()
