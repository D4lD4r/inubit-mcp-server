"""The system diagram cases of tools/record-fixtures.sh (feature 018, research D-1).

Offline only: the script is run with --list, or until it refuses a case before any network step,
or its define_case function is called from a copy without the final `main "$@"` line. No INUBIT
server is contacted and no credentials are needed. All sample values are synthetic.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
SCRIPT = TOOLS_DIR / "record-fixtures.sh"
BASH = shutil.which("bash")
CASES = ("model_export_systemdiagram", "export_systemdiagram")


def clean_env(**extra):
    env = {k: v for k, v in os.environ.items() if not k.startswith(("RECORD_", "INUBIT_"))}
    env.update(extra)
    return env


@unittest.skipIf(BASH is None, "bash not available")
class SystemDiagramCasesTest(unittest.TestCase):

    def run_script(self, *args, **env):
        return subprocess.run([BASH, str(SCRIPT), *args], capture_output=True, text=True,
                              stdin=subprocess.DEVNULL, env=clean_env(**env), timeout=60)

    def define(self, case, **env):
        """kind, path, command, zip entries and anonymizer options that define_case sets."""
        with tempfile.TemporaryDirectory() as tmp:
            lines = SCRIPT.read_text(encoding="utf-8").rstrip("\n").split("\n")
            self.assertEqual('main "$@"', lines[-1])
            library = Path(tmp, "record-fixtures-lib.sh")
            library.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
            probe = (f'source "{library}"; define_case "$1"; '
                     'printf "kind=%s\\npath=%s\\ncommand=%s\\n" "$kind" "$path" "$command"; '
                     'printf "zip=%s\\n" "${zip_entries[*]-}"; '
                     'printf "anon=%s\\n" "${anon_extra[*]-}"')
            result = subprocess.run([BASH, "-c", probe, "probe", case], capture_output=True,
                                    text=True, stdin=subprocess.DEVNULL, env=clean_env(**env),
                                    timeout=60)
        return result

    def test_list_names_both_cases_with_their_sample_variables(self):
        result = self.run_script("--list")

        self.assertEqual(0, result.returncode, result.stderr)
        lines = {line.split()[0]: line for line in result.stdout.splitlines() if line.strip()}
        self.assertIn("RECORD_SAMPLE_SYSTEMDIAGRAM", lines["model_export_systemdiagram"])
        self.assertIn("/ibis/rest/model/export/", lines["model_export_systemdiagram"])
        self.assertIn("RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP", lines["export_systemdiagram"])
        self.assertIn("systemdiagram", lines["export_systemdiagram"])

    def test_list_shows_the_given_samples(self):
        result = self.run_script("--list", RECORD_SAMPLE_SYSTEMDIAGRAM="Workflow-0001",
                                 RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP="GRP-01")

        self.assertIn("/ibis/rest/model/export/Workflow-0001", result.stdout)
        self.assertIn("GRP-01", result.stdout)

    def test_the_cases_are_not_in_the_nine_point_zero_catalogue(self):
        result = self.run_script("--list", RECORD_INUBIT_LINE="9.0")

        for case in CASES:
            self.assertNotIn(case, result.stdout)

    def test_the_rest_case_exports_the_named_system_diagram(self):
        result = self.define("model_export_systemdiagram",
                             RECORD_SAMPLE_SYSTEMDIAGRAM="Workflow-0001")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("kind=rest\n", result.stdout)
        self.assertIn("path=/ibis/rest/model/export/Workflow-0001\n", result.stdout)
        self.assertIn("zip=workflow/workflow.xml archive.properties\n", result.stdout)
        self.assertIn("anon=--partner-limit 5\n", result.stdout)

    def test_the_cli_case_exports_the_system_diagram_group(self):
        result = self.define("export_systemdiagram", RECORD_OWNER="OWNERS",
                             RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP="GRP-01")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("kind=cli\n", result.stdout)
        self.assertIn("command=export --exportWorkflowUser 'OWNERS' --exportWorkflowType "
                      "'systemdiagram' --exportWorkflowGroup 'GRP-01' --exportFile "
                      "'@EXPORT_FILE@'\n", result.stdout)
        self.assertIn("zip=archive.properties workflow/workflow.xml\n", result.stdout)
        self.assertIn("anon=--partner-limit 5\n", result.stdout)

    def test_missing_or_unsafe_samples_are_refused(self):
        cases = (
            ("model_export_systemdiagram", {}, "needs RECORD_SAMPLE_SYSTEMDIAGRAM"),
            ("export_systemdiagram", {"RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP": "GRP-01"},
             "needs RECORD_OWNER"),
            ("export_systemdiagram", {"RECORD_OWNER": "OWNERS"},
             "needs RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP"),
            ("model_export_systemdiagram", {"RECORD_SAMPLE_SYSTEMDIAGRAM": "a b"},
             "unsafe sample value"),
            ("export_systemdiagram", {"RECORD_OWNER": "OWNERS",
                                      "RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP": "GRP/01"},
             "unsafe sample value"),
        )
        for case, env, expected in cases:
            with self.subTest(case=case, expected=expected):
                result = self.define(case, **env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)

    def test_the_nine_point_zero_line_refuses_the_cases(self):
        result = self.define("model_export_systemdiagram", RECORD_INUBIT_LINE="9.0",
                             RECORD_SAMPLE_SYSTEMDIAGRAM="Workflow-0001")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("not in the 9.0 catalogue", result.stderr)


if __name__ == "__main__":
    unittest.main()
