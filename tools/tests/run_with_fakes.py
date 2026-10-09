#!/usr/bin/python3 -I
"""Test-only entry point: runs tools/inubit-cert-check.py with scripted dialog/notification fakes.

Usage: run_with_fakes.py FAKES.json [arguments of the tool ...]

The tool has no option or environment variable that could answer a dialog (research R-12); the
process-level tests start the tool through this wrapper instead. It loads the tool, replaces
show_dialog and notify by fakes scripted in FAKES.json, then calls main(<remaining arguments>) and
exits with its return code. This file lives in tools/tests/ and is never installed.

FAKES.json:

  {
    "dialog": {"dev": {"answer": "accept", "delay": 1.0}},
    "record": "/path/of/a/calls.jsonl",
    "budget": 3.0,
    "keytool": {"/path/acme-dev-truststore.p12": [["dev-aaaaaaaa-20250101", "AA:..."]]}
  }

  dialog   answer per stage, returned unchanged by the fake show_dialog after the optional delay
           in seconds; a stage without an entry gets "dialog-failed" (the dialog could not be
           shown, which never changes anything)
  record   optional; every fake call appends one JSON line {"call": ..., ...} to this file
  budget   optional; replaces the start budget START_BUDGET (seconds)
  keytool  optional; the entries of the in-memory fake trust stores
  lock_delay            optional; seconds the parent waits before it tries the profile lock
  check_config_delay    optional; seconds the fake --check-config takes
  check_config_returncode  optional; exit status of the fake --check-config (default 0)
  break_worker          optional; the worker command exits at once without doing anything
  worker_command_raises optional; building the worker command raises KeyboardInterrupt

Recorded as well: every Popen of the tool ({"call": "popen", ...}: environment keys, standard
streams, session, pass_fds), every flock(LOCK_UN) ({"call": "unlock"}), and in the worker the
mode of the offers file ({"call": "worker_start"}).

Always installed: every host name resolves to 127.0.0.1, keytool and the server's --check-config
are in-process fakes (support.FakeKeytool, support.FakeCheckConfig), and any java or keytool
process the tool would start is recorded as {"call": "process"} and refused. So no test starts a
JVM or shows a real dialog.

The tool builds the command line of its detached dialog worker with worker_command(); the wrapper
replaces it, so the worker is started through this wrapper as well and sees the same fakes. A tool
with a dialog worker but without worker_command() is refused (the worker would show real dialogs).
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402  (same directory; -I keeps it off the path otherwise)

FAILED = "dialog-failed"


def stage_of(stage_info):
    """The stage name of whatever the tool passes to show_dialog (mapping or object)."""
    if isinstance(stage_info, dict):
        return stage_info.get("stage") or stage_info.get("name")
    return getattr(stage_info, "stage", None) or getattr(stage_info, "name", None)


def recorder(path):
    def record(entry):
        if not path:
            return
        line = (json.dumps(entry, sort_keys=True) + "\n").encode("utf-8")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.write(fd, line)
        finally:
            os.close(fd)
    return record


class _Proxy:
    """A module stand-in: the given attributes replaced, everything else from the real one."""

    def __init__(self, real, **overrides):
        self._real = real
        self.__dict__.update(overrides)

    def __getattr__(self, name):
        return getattr(self._real, name)


def _same_file(fd, path):
    try:
        a, b = os.fstat(fd), os.stat(path)
    except OSError:
        return False
    return (a.st_dev, a.st_ino, a.st_rdev) == (b.st_dev, b.st_ino, b.st_rdev)


def _stream_name(value):
    import subprocess
    return {subprocess.DEVNULL: "DEVNULL", subprocess.PIPE: "PIPE", None: "inherit"}.get(
        value, "other")


def install_fakes(tool, fakes, fakes_path=None):
    """Replace the dialog and notification functions of the loaded tool module by fakes."""
    if hasattr(tool, "cmd_dialog_worker") and not hasattr(tool, "worker_command"):
        # a detached worker started without the wrapper would show real dialogs
        raise RuntimeError("the tool has a dialog worker but no worker_command() to redirect")
    answers = fakes.get("dialog", {})
    record = recorder(fakes.get("record"))

    def fake_show_dialog(stage_info, *args, **kwargs):
        stage = stage_of(stage_info)
        scripted = answers.get(stage) or {}
        status = _status_file_argument(sys.argv)
        record({"call": "show_dialog", "stage": stage, "pid": os.getpid(),
                "sid": os.getsid(0), "info": stage_info if isinstance(stage_info, dict) else None,
                "stdin_devnull": _same_file(0, os.devnull),
                "stdout_devnull": _same_file(1, os.devnull),
                "stderr_devnull": _same_file(2, os.devnull),
                "status_mode": oct(os.stat(status).st_mode & 0o777)
                if status and os.path.exists(status) else None})
        delay = float(scripted.get("delay", 0))
        if delay > 0:
            time.sleep(delay)
        return scripted.get("answer", FAILED)

    def fake_notify(text, *args, **kwargs):
        record({"call": "notify", "text": text, "pid": os.getpid()})

    tool.show_dialog = fake_show_dialog
    tool.notify = fake_notify
    if "budget" in fakes:
        tool.START_BUDGET = float(fakes["budget"])
    tool.resolve_host = support.loopback_resolver
    keytool = support.FakeKeytool(tool, {store: [tool.TrustEntry(alias, fingerprint)
                                                 for alias, fingerprint in entries]
                                         for store, entries in fakes.get("keytool", {}).items()})

    def fake_make_keytool(java):
        record({"call": "make_keytool", "pid": os.getpid()})
        return keytool

    tool.make_keytool = fake_make_keytool
    def on_check_config(argv):
        record({"call": "check_config", "pid": os.getpid()})
        time.sleep(float(fakes.get("check_config_delay", 0)))

    check_config = support.FakeCheckConfig(
        tool, returncode=int(fakes.get("check_config_returncode", 0)), on_call=on_check_config)
    tool.check_config_runner = check_config
    if fakes.get("lock_delay"):
        real_try_lock = tool.State.try_lock

        def delayed_try_lock(state):
            time.sleep(float(fakes["lock_delay"]))
            return real_try_lock(state)

        tool.State.try_lock = delayed_try_lock
    real_subprocess, real_fcntl = tool.subprocess, tool.fcntl

    def recording_popen(argv, *args, **kwargs):
        env = kwargs.get("env")
        record({"call": "popen", "pid": os.getpid(), "argv": list(argv),
                "env_keys": sorted(env) if env is not None else None,
                "stdin": _stream_name(kwargs.get("stdin")),
                "stdout": _stream_name(kwargs.get("stdout")),
                "stderr": _stream_name(kwargs.get("stderr")),
                "start_new_session": kwargs.get("start_new_session", False),
                "pass_fds": list(kwargs.get("pass_fds", ()))})
        return real_subprocess.Popen(argv, *args, **kwargs)

    def recording_flock(fd, operation):
        if operation & real_fcntl.LOCK_UN:
            record({"call": "unlock", "pid": os.getpid(), "fd": fd})
        return real_fcntl.flock(fd, operation)

    tool.subprocess = _Proxy(real_subprocess, Popen=recording_popen)
    tool.fcntl = _Proxy(real_fcntl, flock=recording_flock)
    real_run_process = tool.run_process

    def guarded_run_process(argv, *args, **kwargs):
        if os.path.basename(argv[0]) in ("java", "keytool", "osascript"):
            record({"call": "process", "argv0": argv[0], "pid": os.getpid()})
            raise OSError(1, "refused by run_with_fakes")
        return real_run_process(argv, *args, **kwargs)

    tool.run_process = guarded_run_process
    if fakes_path is not None and hasattr(tool, "worker_command"):
        wrapper = str(Path(__file__).resolve())

        def fake_worker_command(status_file, options=()):
            if fakes.get("worker_command_raises"):
                raise KeyboardInterrupt("worker_command_raises")
            if fakes.get("break_worker"):
                return [sys.executable, "-I", "-c", "pass"]
            return [sys.executable, "-I", wrapper, str(fakes_path), "--dialog-worker",
                    str(status_file)] + list(options)

        tool.worker_command = fake_worker_command
    return tool


def main(argv):
    if len(argv) < 2:
        print("usage: run_with_fakes.py FAKES.json [arguments of the tool ...]", file=sys.stderr)
        return 2
    fakes_path = argv[1]
    with open(fakes_path, encoding="utf-8") as handle:
        fakes = json.load(handle)
    tool = install_fakes(support.load_tool(), fakes, fakes_path)
    status = _status_file_argument(argv)
    if status:
        offers = status[:-len(".json")] + ".offers.json"
        recorder(fakes.get("record"))({
            "call": "worker_start", "pid": os.getpid(),
            "offers_mode": oct(os.stat(offers).st_mode & 0o777) if os.path.exists(offers)
            else None})
    return tool.main(argv[2:])


def _status_file_argument(argv):
    """The STATUSFILE of --dialog-worker in argv, or None."""
    if "--dialog-worker" in argv:
        index = argv.index("--dialog-worker")
        if index + 1 < len(argv):
            return argv[index + 1]
    return None


if __name__ == "__main__":
    sys.exit(main(sys.argv))
