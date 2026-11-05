"""Profile reader of inubit-cert-check (task T005; data-model "Profile view", research R-7)."""

from __future__ import annotations

import os
import tempfile
import textwrap
import unittest
from unittest import mock

import support

PIN_A = "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:01:23:45:67:" \
    "89:AB:CD:EF:10:32:54:76:98:FF:01:02"
PIN_B = "BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:10:20:30:40:" \
    "50:60:70:80:90:A0:B0:C0:D0:E0:F0:03"
PIN_C = "CC:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:21:31:41:51:" \
    "61:71:81:91:A1:B1:C1:D1:E1:F1:04:05"

BASIC = f"""\
# Profile for the tests; every name is fictitious.
profile:
  name: acme
  description: Test profile

credentials:
  envPrefix: INUBIT_ACME_TEST

terminology: {{ group: {{ singular: stage, plural: stages }},
               node: {{ singular: server, plural: servers }} }}
resultLimits: {{ maxItems: 100, maxChars: 50000 }}

x-cert-check:
  java: ~/jdk/bin/java
  serverJar: ~/lib/inubit-mcp-server.jar

groups:
  - name: dev
    write:
      enabled: true
    tls:
      trustStore: ~/.config/inubit-mcp/acme-dev-truststore.p12
      disableHostnameVerification: true
      pinnedCertificateSha256: {PIN_A.lower()}   # dev pin
    nodes:
      - name: node1
        baseUrl: https://dev-host.example.test:8443
  # A comment between the items.
  - name: int
    tls:
      trustStore: ~/.config/inubit-mcp/acme-int-truststore.p12
      pinnedCertificateSha256: "{PIN_B}"
    nodes:
      - name: node1
        baseUrl: https://int-1.example.test
        timeout: PT9S
      - name: node2
        baseUrl: https://int-2.example.test:9443/
#  - name: qa
#    tls: *sharedTls
#    nodes:
#      - name: node1
#        baseUrl: https://qa-1.example.test:8443
"""


class ReaderTestCase(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = os.path.join(self.tmp.name, "home")
        os.mkdir(self.home)
        patcher = mock.patch.dict(os.environ, {"HOME": self.home})
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, text, name="acme.yaml"):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(textwrap.dedent(text))
        return path

    def read(self, text):
        return self.tool.read_profile(self.write(text))

    def assertRefused(self, text, *fragments):
        with self.assertRaises(self.tool.ConfigError) as caught:
            self.read(text)
        message = str(caught.exception)
        for fragment in fragments:
            self.assertIn(fragment, message)
        return message

    def line_of(self, text, fragment):
        for number, line in enumerate(textwrap.dedent(text).split("\n"), 1):
            if fragment in line:
                return number
        raise AssertionError(fragment)


class BasicProfileTest(ReaderTestCase):

    def test_profile_fields(self):
        profile = self.read(BASIC)
        self.assertEqual("acme", profile.name)
        self.assertEqual("INUBIT_ACME_TEST", profile.env_prefix)
        self.assertEqual(os.path.join(self.home, "jdk/bin/java"), profile.java)
        self.assertEqual(os.path.join(self.home, "lib/inubit-mcp-server.jar"), profile.server_jar)
        self.assertEqual(["dev", "int"], [stage.name for stage in profile.stages])

    def test_servers(self):
        profile = self.read(BASIC)
        servers = [server for stage in profile.stages for server in stage.servers]
        self.assertEqual(["dev/node1", "int/node1", "int/node2"], [s.id for s in servers])
        self.assertEqual(("dev-host.example.test", 8443), (servers[0].host, servers[0].port))
        self.assertEqual(("int-1.example.test", 443), (servers[1].host, servers[1].port))
        self.assertEqual(("int-2.example.test", 9443), (servers[2].host, servers[2].port))
        self.assertEqual("https://dev-host.example.test:8443", servers[0].base_url)
        self.assertEqual(["dev", "int", "int"], [s.stage for s in servers])
        self.assertEqual(["node1", "node1", "node2"], [s.name for s in servers])

    def test_pins_normalized_and_lines(self):
        profile = self.read(BASIC)
        dev, int_ = profile.stages
        self.assertEqual(PIN_A, dev.pin)
        self.assertEqual(PIN_B, int_.pin)
        self.assertEqual(self.line_of(BASIC, "# dev pin"), dev.pin_line)
        self.assertEqual(self.line_of(BASIC, f'"{PIN_B}"'), int_.pin_line)
        self.assertEqual([PIN_B, PIN_B], [s.pin for s in int_.servers])

    def test_own_tls(self):
        dev, int_ = self.read(BASIC).stages
        self.assertEqual(("stage", True), (dev.pin_source, dev.own_tls))
        self.assertEqual(("stage", True), (int_.pin_source, int_.own_tls))
        self.assertEqual(os.path.join(self.home, ".config/inubit-mcp/acme-dev-truststore.p12"),
                         dev.trust_store)

    def test_stage_lookup(self):
        profile = self.read(BASIC)
        self.assertEqual("int", profile.stage("int").name)
        self.assertIsNone(profile.stage("qa"), "commented-out items do not exist")

    def test_single_quoted_pin_and_compact_sequences(self):
        profile = self.read(f"""\
            profile: {{name: acme}}
            groups:
            - name: dev
              tls:
                pinnedCertificateSha256: '{PIN_A}' # quoted
                trustStore: /stores/dev.p12
              nodes:
              - name: node1
                baseUrl: https://dev-host.example.test:8443
            """)
        stage = profile.stages[0]
        self.assertEqual((PIN_A, 5, "stage"), (stage.pin, stage.pin_line, stage.pin_source))
        self.assertEqual("/stores/dev.p12", stage.trust_store)

    def test_crlf_line_endings(self):
        path = self.write(BASIC.replace("\n", "\r\n"))
        profile = self.tool.read_profile(path)
        self.assertEqual(self.line_of(BASIC, "# dev pin"), profile.stages[0].pin_line)


class SkippedServerTest(ReaderTestCase):
    """http:// servers have no certificate: skipped, but listed (contract C-1 skippedServers)."""

    TEXT = BASIC.replace("baseUrl: https://int-2.example.test:9443/",
                         "baseUrl: http://int-2.example.test:8080\n        allowInsecureHttp: true"
                         "\n        tls:\n          pinnedCertificateSha256: " + PIN_C)

    def test_http_server_skipped(self):
        profile = self.read(self.TEXT)
        int_ = profile.stage("int")
        self.assertEqual(["int/node1"], [server.id for server in int_.servers])
        self.assertEqual([("int/node2", "http://int-2.example.test:8080", "no TLS")],
                         [(s.id, s.base_url, s.reason) for s in int_.skipped_servers])
        self.assertEqual(["int/node2"], [s.id for s in profile.skipped_servers])
        self.assertEqual(["dev/node1", "int/node1", "int/node2"], profile.server_ids())

    def test_skipped_server_does_not_change_the_pin_source(self):
        int_ = self.read(self.TEXT).stage("int")
        self.assertEqual(("stage", True), (int_.pin_source, int_.own_tls))

    def test_stage_with_http_servers_only(self):
        dev = self.read(BASIC.replace("https://dev-host", "http://dev-host")).stage("dev")
        self.assertEqual(((), ["dev/node1"]), (dev.servers, [s.id for s in dev.skipped_servers]))


class EnvPrefixTest(ReaderTestCase):

    def test_default_prefix_from_profile_name(self):
        profile = self.read("""\
            profile:
              name: acme-2
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test
            """)
        self.assertEqual("INUBIT_ACME_2", profile.env_prefix)
        self.assertIsNone(profile.java)
        self.assertIsNone(profile.server_jar)
        self.assertIsNone(profile.stages[0].pin)

    def test_flow_mappings_of_simple_settings(self):
        profile = self.read("""\
            profile: { name: acme }
            credentials: {envPrefix: "INUBIT_FLOW"}
            x-cert-check: { java: /jdk/bin/java, serverJar: '/lib/server.jar' }
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test
            """)
        self.assertEqual(("acme", "INUBIT_FLOW", "/jdk/bin/java", "/lib/server.jar"),
                         (profile.name, profile.env_prefix, profile.java, profile.server_jar))

    def test_expected_profile_name(self):
        path = self.write(BASIC)
        self.assertEqual("acme", self.tool.read_profile(path, expected_name="acme").name)
        with self.assertRaises(self.tool.ConfigError) as caught:
            self.tool.read_profile(path, expected_name="other")
        self.assertIn("profile.name", str(caught.exception))


class InheritanceTest(ReaderTestCase):

    def test_alias(self):
        text = f"""\
            profile:
              name: acme
            x-acme-tls: &sharedTls
              trustStore: ~/.config/inubit-mcp/acme-truststore.p12
              disableHostnameVerification: true
              pinnedCertificateSha256: {PIN_A}
            groups:
              - name: dev
                tls: *sharedTls
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
              - name: int
                tls: *sharedTls
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
            """
        dev, int_ = self.read(text).stages
        for stage in (dev, int_):
            self.assertEqual((PIN_A, "alias", None, False),
                             (stage.pin, stage.pin_source, stage.pin_line, stage.own_tls))
            self.assertEqual(os.path.join(self.home, ".config/inubit-mcp/acme-truststore.p12"),
                             stage.trust_store)

    def test_alias_used_by_one_stage_only_is_still_alias(self):
        dev = self.read(f"""\
            profile:
              name: acme
            x-dev-tls: &devTls
              trustStore: /stores/dev.p12
              pinnedCertificateSha256: {PIN_A}
            groups:
              - name: dev
                tls: *devTls
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
            """).stages[0]
        self.assertEqual(("alias", False), (dev.pin_source, dev.own_tls))

    def test_defaults(self):
        dev, int_ = self.read(f"""\
            profile:
              name: acme
            defaults:
              timeout: PT5S
              tls:
                trustStore: /stores/shared.p12
                pinnedCertificateSha256: {PIN_A}
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
              - name: int
                tls:
                  trustStore: /stores/int.p12
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
            """).stages
        self.assertEqual((PIN_A, "defaults", None, False),
                         (dev.pin, dev.pin_source, dev.pin_line, dev.own_tls))
        self.assertEqual("/stores/shared.p12", dev.trust_store)
        # field by field: the trust store from the stage, the pin from defaults
        self.assertEqual((PIN_A, "defaults", "/stores/int.p12", False),
                         (int_.pin, int_.pin_source, int_.trust_store, int_.own_tls))

    def test_server_override(self):
        text = f"""\
            profile:
              name: acme
            groups:
              - name: int
                tls:
                  trustStore: /stores/int.p12
                  pinnedCertificateSha256: {PIN_B}
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
                  - name: node2
                    baseUrl: https://int-2.example.test:8443
                    tls:
                      pinnedCertificateSha256: {PIN_C}
            """
        stage = self.read(text).stages[0]
        self.assertEqual(("server", False), (stage.pin_source, stage.own_tls))
        self.assertEqual(PIN_B, stage.pin)
        self.assertEqual([PIN_B, PIN_C], [server.pin for server in stage.servers])
        self.assertEqual(["/stores/int.p12", "/stores/int.p12"],
                         [server.trust_store for server in stage.servers])

    def test_server_trust_store_override_is_server_source(self):
        stage = self.read(f"""\
            profile:
              name: acme
            groups:
              - name: int
                tls:
                  trustStore: /stores/int.p12
                  pinnedCertificateSha256: {PIN_B}
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
                    tls:
                      trustStore: /stores/other.p12
            """).stages[0]
        self.assertEqual(("server", False), (stage.pin_source, stage.own_tls))
        self.assertEqual("/stores/other.p12", stage.servers[0].trust_store)

    def test_server_block_without_pin_or_store_keeps_stage_source(self):
        stage = self.read(f"""\
            profile:
              name: acme
            groups:
              - name: int
                tls:
                  trustStore: /stores/int.p12
                  pinnedCertificateSha256: {PIN_B}
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
                    tls:
                      disableHostnameVerification: true
            """).stages[0]
        self.assertEqual(("stage", True, PIN_B), (stage.pin_source, stage.own_tls,
                                                 stage.servers[0].pin))

    def test_trust_store_shared_by_two_stages(self):
        dev, int_ = self.read(f"""\
            profile:
              name: acme
            groups:
              - name: dev
                tls:
                  trustStore: ~/stores/shared.p12
                  pinnedCertificateSha256: {PIN_A}
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
              - name: int
                tls:
                  trustStore: {self.home}/stores/./shared.p12
                  pinnedCertificateSha256: {PIN_B}
                nodes:
                  - name: node1
                    baseUrl: https://int-1.example.test:8443
            """).stages
        self.assertEqual(("stage", False), (dev.pin_source, dev.own_tls))
        self.assertEqual(("stage", False), (int_.pin_source, int_.own_tls))

    def test_trust_store_inherited_from_defaults_is_not_own(self):
        stage = self.read(f"""\
            profile:
              name: acme
            defaults:
              tls:
                trustStore: /stores/dev-only.p12
            groups:
              - name: dev
                tls:
                  pinnedCertificateSha256: {PIN_A}
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
            """).stages[0]
        self.assertEqual(("stage", "/stores/dev-only.p12", False),
                         (stage.pin_source, stage.trust_store, stage.own_tls))

    def test_stage_without_trust_store_has_no_own_tls(self):
        stage = self.read(f"""\
            profile:
              name: acme
            groups:
              - name: dev
                tls:
                  pinnedCertificateSha256: {PIN_A}
                nodes:
                  - name: node1
                    baseUrl: https://dev-host.example.test:8443
            """).stages[0]
        self.assertEqual(("stage", False), (stage.pin_source, stage.own_tls))


class FailClosedTest(ReaderTestCase):

    def test_tab_indentation(self):
        message = self.assertRefused(BASIC.replace("      enabled: true", "\t  enabled: true"),
                                     "tab")
        self.assertIn(str(self.line_of(BASIC, "enabled: true")), message)

    def test_document_separator(self):
        self.assertRefused("---\n" + BASIC, "---")
        self.assertRefused(BASIC + "---\nprofile:\n  name: other\n", "---")

    def test_merge_key(self):
        self.assertRefused(BASIC.replace("    write:\n", "    <<: *base\n    write:\n"), "<<")

    def test_duplicate_stage(self):
        self.assertRefused(BASIC.replace("  - name: int", "  - name: dev"), "dev")

    def test_duplicate_server(self):
        self.assertRefused(BASIC.replace("      - name: node2", "      - name: node1"),
                           "node1")

    def test_unknown_shapes_inside_groups(self):
        node = "      - name: node1\n        baseUrl: https://h.example.test\n"
        cases = [
            ("groups as flow sequence", "groups: [ {name: dev} ]\n",
             "groups must be a non-empty block sequence"),
            ("group item scalar", "groups:\n  - dev\n", "each stage must be a block mapping"),
            ("nodes as mapping", "groups:\n  - name: dev\n    nodes:\n      node1:\n"
             "        baseUrl: https://dev-host.example.test\n",
             "nodes must be a non-empty block sequence"),
            ("node as flow mapping", "groups:\n  - name: dev\n    nodes:\n"
             "      - {name: node1, baseUrl: https://h.example.test}\n",
             "each server must be a block mapping"),
            ("tls as scalar", "groups:\n  - name: dev\n    tls: shared\n    nodes:\n" + node,
             "must be a block mapping or an alias"),
            ("tls as flow mapping", "groups:\n  - name: dev\n    tls: {pinnedCertificateSha256: "
             f"{PIN_A}}}\n    nodes:\n" + node, "must be a block mapping or an alias"),
            ("unknown tls key", "groups:\n  - name: dev\n    tls:\n      pin: x\n    nodes:\n"
             + node, "unknown key 'pin'"),
            ("name as mapping", "groups:\n  - name:\n      x: y\n    nodes:\n" + node,
             "must be a single-line value"),
            ("multi-line pin", "groups:\n  - name: dev\n    tls:\n"
             f"      pinnedCertificateSha256:\n        {PIN_A}\n    nodes:\n" + node,
             "must be on the line of its key"),
            ("anchor on a stage tls block", "groups:\n  - name: dev\n    tls: &devTls\n"
             f"      pinnedCertificateSha256: {PIN_A}\n    nodes:\n" + node
             + "  - name: int\n    tls: *devTls\n    nodes:\n"
             "      - name: node1\n        baseUrl: https://i.example.test\n",
             "anchors are only supported on top-level x-* keys"),
            ("undefined alias", "groups:\n  - name: dev\n    tls: *missing\n    nodes:\n" + node,
             "undefined alias '*missing'"),
            ("bad indentation", "groups:\n  - name: dev\n    nodes:\n" + node + "   oops: 1\n",
             "unexpected indentation"),
        ]
        for label, groups, fragment in cases:
            with self.subTest(label):
                self.assertRefused("profile:\n  name: acme\n" + groups, fragment)

    def test_no_groups(self):
        self.assertRefused("profile:\n  name: acme\n", "groups")
        self.assertRefused("profile:\n  name: acme\ngroups:\n", "groups")

    def test_profile_name(self):
        nodes = "groups:\n  - name: dev\n    nodes:\n      - name: n\n" \
                "        baseUrl: https://h.example.test\n"
        self.assertRefused(nodes, "profile.name")
        self.assertRefused("profile:\n  name: Acme\n" + nodes, "profile.name")
        self.assertRefused("profile:\n  name: audit\n" + nodes, "profile.name")

    def test_other_schemes(self):
        for scheme in ("ftp", "ldaps", "file"):
            with self.subTest(scheme=scheme):
                self.assertRefused(BASIC.replace("https://dev-host", f"{scheme}://dev-host"),
                                   "https")

    def test_duplicate_anchor_anywhere(self):
        self.assertRefused(BASIC.replace("    write:\n", "    write: &w\n").replace(
            "        timeout: PT9S\n", "        timeout: &w PT9S\n"), "'&w'", "twice")
        self.assertRefused(f"x-tls: &t\n  pinnedCertificateSha256: {PIN_A}\n"
                           + BASIC.replace("resultLimits:", "resultLimits: &t"), "'&t'")

    def test_alias_before_its_anchor(self):
        text = BASIC.replace(
            "    tls:\n      trustStore: ~/.config/inubit-mcp/acme-int-truststore.p12\n"
            f'      pinnedCertificateSha256: "{PIN_B}"\n', "    tls: *late\n")
        text += "x-late: &late\n  trustStore: /stores/int.p12\n" \
                f"  pinnedCertificateSha256: {PIN_B}\n"
        self.assertRefused(text, "*late", "before")

    def test_alias_to_an_anchor_outside_top_level_x_keys(self):
        text = BASIC.replace("    write:\n      enabled: true\n", "").replace(
            "    tls:\n      trustStore: ~/.config/inubit-mcp/acme-int-truststore.p12\n"
            f'      pinnedCertificateSha256: "{PIN_B}"\n', "    tls: *inner\n")
        text = text.replace("resultLimits: {", "defaults:\n  tls: &inner\n"
                            "    trustStore: /stores/int.p12\nresultLimits: {")
        self.assertRefused(text, "top-level")

    def test_ampersand_in_quotes_is_no_anchor(self):
        profile = self.read(BASIC.replace("description: Test profile",
                                          "description: \"R &D test\"")
                            + "x-notes: 'one &two'\n")
        self.assertEqual("acme", profile.name)

    def test_missing_base_url(self):
        self.assertRefused(BASIC.replace("        baseUrl: https://dev-host.example.test:8443\n",
                                         ""), "baseUrl")

    def test_malformed_pin(self):
        self.assertRefused(BASIC.replace(PIN_A.lower(), PIN_A[:-3]), "pinnedCertificateSha256")

    def test_missing_file(self):
        with self.assertRaises(self.tool.ConfigError):
            self.tool.read_profile(os.path.join(self.tmp.name, "missing.yaml"))

    def test_error_is_a_tool_error(self):
        self.assertTrue(issubclass(self.tool.ConfigError, self.tool.ToolError))


class PinPerServerTest(ReaderTestCase):
    """Marked stages, tls.pinPerServer (feature 014: FR-003, FR-004, research R-7)."""

    TEXT = f"""\
profile:
  name: acme
x-b-tls: &bTls
  pinnedCertificateSha256: {PIN_C}
groups:
  - name: dev
    tls:
      trustStore: /stores/dev.p12
      pinnedCertificateSha256: {PIN_A}
    nodes:
      - name: node1
        baseUrl: https://dev-host.example.test:8443
  - name: int
    tls:
      pinPerServer: true
      trustStore: /stores/int.p12
    nodes:
      - name: a
        baseUrl: https://int-a.example.test:8443
        tls:
          pinnedCertificateSha256: {PIN_B}   # int/a pin
      - name: b
        baseUrl: https://int-b.example.test:8443
        tls: *bTls
      - name: c
        baseUrl: http://int-c.example.test:8080
"""
    INT_TLS = "    tls:\n      pinPerServer: true\n      trustStore: /stores/int.p12\n"
    A_PIN = f"          pinnedCertificateSha256: {PIN_B}"

    def test_marked_inline(self):
        dev, int_ = self.read(self.TEXT).stages
        self.assertEqual((False, True), (dev.pin_per_server, int_.pin_per_server))

    def test_marked_through_alias(self):
        text = self.TEXT.replace(self.INT_TLS, "    tls: *intTls\n").replace(
            "groups:\n", "x-int-tls: &intTls\n  pinPerServer: true\n"
                         "  trustStore: /stores/int.p12\ngroups:\n")
        int_ = self.read(text).stage("int")
        self.assertTrue(int_.pin_per_server)
        # the trust store is not written in the stage's own inline block
        self.assertEqual(("/stores/int.p12", False, None),
                         (int_.trust_store, int_.own_tls, int_.problem))

    def test_false_or_absent_is_uniform(self):
        for text in (BASIC, self.TEXT.replace("pinPerServer: true", "pinPerServer: false"),
                     self.TEXT.replace("      pinPerServer: true\n", "")):
            with self.subTest(text=text[:40]):
                for stage in self.read(text).stages:
                    self.assertEqual((False, frozenset(), None),
                                     (stage.pin_per_server, stage.expected, stage.problem))

    def test_uniform_stage_unchanged(self):
        dev = self.read(self.TEXT).stage("dev")
        self.assertEqual(self.tool.Stage(
            name="dev", pin=PIN_A, pin_source="stage",
            pin_line=self.line_of(self.TEXT, f"pinnedCertificateSha256: {PIN_A}"),
            trust_store="/stores/dev.p12", own_tls=True,
            servers=(self.tool.Server(id="dev/node1", stage="dev", name="node1",
                                      base_url="https://dev-host.example.test:8443",
                                      host="dev-host.example.test", port=8443, pin=PIN_A,
                                      trust_store="/stores/dev.p12"),),
            server_ids=("dev/node1",)), dev)

    def test_marked_stage_view(self):
        int_ = self.read(self.TEXT).stage("int")
        self.assertEqual((None, "server", None, "/stores/int.p12", True, None),
                         (int_.pin, int_.pin_source, int_.pin_line, int_.trust_store,
                          int_.own_tls, int_.problem))
        self.assertEqual(frozenset({PIN_B, PIN_C}), int_.expected)
        # the http server needs no pin and is not part of the evaluation
        self.assertEqual(["int/c"], [server.id for server in int_.skipped_servers])

    def test_server_pin_source_and_line(self):
        a, b = self.read(self.TEXT).stage("int").servers
        self.assertEqual((PIN_B, "server", self.line_of(self.TEXT, "# int/a pin")),
                         (a.pin, a.pin_source, a.pin_line))
        self.assertEqual((PIN_C, "alias", None), (b.pin, b.pin_source, b.pin_line))
        text = self.TEXT.replace("        tls: *bTls\n",
                                 "        tls:\n          disableHostnameVerification: true\n")
        b = self.read(text).stage("int").servers[1]
        self.assertEqual((None, "stage", None), (b.pin, b.pin_source, b.pin_line))

    def test_own_tls_of_a_marked_stage(self):
        inherited = self.TEXT.replace("      trustStore: /stores/int.p12\n", "").replace(
            "groups:\n", "defaults:\n  tls:\n    trustStore: /stores/shared.p12\ngroups:\n")
        shared = self.TEXT.replace("/stores/dev.p12", "/stores/int.p12")
        for name, text in (("inherited from defaults.tls", inherited),
                           ("shared with another stage", shared)):
            with self.subTest(name):
                int_ = self.read(text).stage("int")
                self.assertEqual((True, False, None),
                                 (int_.pin_per_server, int_.own_tls, int_.problem))

    def test_problem(self):
        cases = (
            ("stage pin", self.TEXT.replace(
                "      trustStore: /stores/int.p12\n",
                f"      trustStore: /stores/int.p12\n      pinnedCertificateSha256: {PIN_A}\n"),
             ("stage 'int'", "tls.pinnedCertificateSha256", "not allowed")),
            ("stage pin through an alias", self.TEXT.replace(
                self.INT_TLS, "    tls: *intTls\n").replace(
                "groups:\n", "x-int-tls: &intTls\n  pinPerServer: true\n  trustStore: "
                             f"/stores/int.p12\n  pinnedCertificateSha256: {PIN_A}\ngroups:\n"),
             ("stage 'int'", "tls.pinnedCertificateSha256", "not allowed")),
            ("trust store on a server", self.TEXT.replace(
                self.A_PIN, "          trustStore: /stores/a.p12\n" + self.A_PIN),
             ("int/a", "tls.trustStore", "not allowed")),
            ("trust store on an http server", self.TEXT.replace(
                "        baseUrl: http://int-c.example.test:8080\n",
                "        baseUrl: http://int-c.example.test:8080\n"
                "        tls:\n          trustStore: /stores/c.p12\n"),
             ("int/c", "tls.trustStore", "not allowed")),
            ("https server without a pin", self.TEXT.replace("        tls: *bTls\n", ""),
             ("int/b", "tls.pinnedCertificateSha256")),
            ("pinPerServer on a server", self.TEXT.replace(
                self.A_PIN, "          pinPerServer: false\n" + self.A_PIN),
             ("int/a", "tls.pinPerServer", "only valid on a stage")),
            ("no trust store", self.TEXT.replace("      trustStore: /stores/int.p12\n", ""),
             ("stage 'int'", "tls.trustStore")),
            ("first broken rule", self.TEXT.replace(
                "      trustStore: /stores/int.p12\n",
                f"      pinnedCertificateSha256: {PIN_A}\n").replace(
                "        tls: *bTls\n", ""),
             ("stage 'int'", "tls.pinnedCertificateSha256", "not allowed")),
        )
        for name, text, fragments in cases:
            with self.subTest(name):
                dev, int_ = self.read(text).stages
                self.assertIsNone(dev.problem)
                self.assertIsNotNone(int_.problem)
                for fragment in fragments:
                    self.assertIn(fragment, int_.problem)
                self.assertEqual((None, "server"), (int_.pin, int_.pin_source))

    def test_flow_mapping_server_tls_fails_closed(self):
        self.assertRefused(self.TEXT.replace(
            "        tls:\n" + self.A_PIN, f"        tls: {{ pinnedCertificateSha256: {PIN_B} }}"),
            "server int/a.tls must be a block mapping or an alias")

    def test_non_boolean_value_fails_closed(self):
        for value in ("maybe", "1", "'yes'", "[true]"):
            with self.subTest(value):
                self.assertRefused(self.TEXT.replace("pinPerServer: true",
                                                     f"pinPerServer: {value}"),
                                   "stage int.tls.pinPerServer")


class PinPerServerAddressTest(ReaderTestCase):
    """The https addresses of a server of a marked stage: baseUrl, cli.url and e2e.soap.baseUrl,
    each node -> stage, as ConfigValidator.checkPinPerServer (V-6; feature 014, T029c, FR-004,
    FR-013, research R-13). int/c is the http server of PinPerServerTest.TEXT."""

    TEXT = PinPerServerTest.TEXT
    INT_TLS = PinPerServerTest.INT_TLS
    C_URL = "        baseUrl: http://int-c.example.test:8080\n"
    C_PIN = f"        tls:\n          pinnedCertificateSha256: {PIN_A}\n"
    NODE_CLI = "        cli:\n          url: {url}\n"
    NODE_SOAP = "        e2e:\n          soap:\n            baseUrl: {url}\n"
    STAGE_CLI = "    cli:\n      url: {url}\n"
    STAGE_SOAP = "    e2e:\n      soap:\n        baseUrl: {url}\n"
    HTTPS = "https://int-c.example.test:8443"
    HTTP = "http://int-c.example.test:8081"
    V6 = "int/c: tls.pinPerServer on stage 'int' needs tls.pinnedCertificateSha256 on this server"

    def text(self, node="", stage=""):
        return self.TEXT.replace(self.C_URL, self.C_URL + node).replace(
            self.INT_TLS, self.INT_TLS + stage)

    def test_an_https_address_needs_the_own_pin(self):
        cases = (
            ("node cli.url", self.NODE_CLI.format(url=self.HTTPS), ""),
            ("node cli.url, flow mapping", f"        cli: {{ url: '{self.HTTPS}' }}\n", ""),
            ("node e2e.soap.baseUrl", self.NODE_SOAP.format(url=self.HTTPS + "/soap"), ""),
            ("stage cli.url", "", self.STAGE_CLI.format(url=self.HTTPS)),
            ("stage e2e.soap.baseUrl", "", self.STAGE_SOAP.format(url=self.HTTPS)),
            ("upper-case scheme", self.NODE_CLI.format(url="HTTPS://int-c.example.test"), ""),
            ("own http cli.url, https SOAP", self.NODE_CLI.format(url=self.HTTP),
             self.STAGE_SOAP.format(url=self.HTTPS)),
        )
        for name, node, stage in cases:
            with self.subTest(name):
                int_ = self.read(self.text(node, stage)).stage("int")
                self.assertEqual(self.V6, int_.problem)
                self.assertIsNone(self.read(self.text(self.C_PIN + node, stage))
                                  .stage("int").problem, "the own pin satisfies the rule")

    def test_an_own_http_address_wins_over_the_stage(self):
        cases = (
            ("cli.url", self.NODE_CLI.format(url=self.HTTP), self.STAGE_CLI.format(url=self.HTTPS)),
            ("e2e.soap.baseUrl", self.NODE_SOAP.format(url=self.HTTP),
             self.STAGE_SOAP.format(url=self.HTTPS)),
            ("http only", self.NODE_CLI.format(url=self.HTTP) + self.NODE_SOAP.format(
                url=self.HTTP), ""),
        )
        for name, node, stage in cases:
            with self.subTest(name):
                self.assertIsNone(self.read(self.text(node, stage)).stage("int").problem)

    def test_an_address_without_url_needs_no_pin(self):
        node = "        cli:\n          timeout: PT9S\n        e2e:\n          soap: ~\n"
        self.assertIsNone(self.read(self.text(node)).stage("int").problem)

    def test_the_pin_of_an_http_server_is_expected(self):
        for name, node in (("with an https cli.url", self.NODE_CLI.format(url=self.HTTPS)),
                           ("http addresses only", "")):
            with self.subTest(name):
                int_ = self.read(self.text(self.C_PIN + node)).stage("int")
                self.assertIsNone(int_.problem)
                self.assertEqual(frozenset({PIN_A, PIN_B, PIN_C}), int_.expected)
                self.assertEqual(frozenset({PIN_A, PIN_B, PIN_C}),
                                 self.tool.kept_fingerprints(int_))
                self.assertEqual([("int/a", PIN_B), ("int/b", PIN_C), ("int/c", PIN_A)],
                                 list(self.tool._prune_expected(int_).items()))
                # still not contacted by --check
                self.assertEqual(["int/a", "int/b"], [server.id for server in int_.servers])
                self.assertEqual(["int/c"], [server.id for server in int_.skipped_servers])

    def docs_e2e_line(self):
        """The nested one-line flow mapping of e2e as docs/setup.md writes it, verbatim."""
        path = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "setup.md")
        with open(path, encoding="utf-8") as handle:
            lines = [line for line in handle.read().split("\n")
                     if line.strip().startswith("e2e: { soap: { baseUrl: https://")]
        self.assertEqual(1, len(lines), lines)
        self.assertEqual("    e2e: { soap: { baseUrl: https://inubit-int-1.example.test:8443 } }",
                         lines[0])
        return lines[0] + "\n"

    def test_nested_one_line_flow_mapping_of_e2e(self):
        # T029c r1: the form of docs/setup.md, at stage level as written, at node level indented
        line = self.docs_e2e_line()
        for name, node, stage in (("stage", "", line), ("node", "    " + line, "")):
            with self.subTest(name):
                self.assertEqual(self.V6, self.read(self.text(node, stage)).stage("int").problem)
                self.assertIsNone(self.read(self.text(self.C_PIN + node, stage))
                                  .stage("int").problem)
        own_http = "        e2e: { soap: { baseUrl: 'http://int-c.example.test:8081' }, " \
                   "x: [1, 2] }\n"
        self.assertIsNone(self.read(self.text(own_http, line)).stage("int").problem)

    def test_unreadable_address_shape_is_a_problem_of_the_marked_stage(self):
        # T029c r1: not a profile-wide error; the stage is unusable, other stages are not
        for name, node, stage, prefix, fragment in (
                ("node cli alias", "        cli: *nowhere\n", "", "int/c: cannot read cli.url",
                 "server int/c.cli must be a block mapping or a one-line flow mapping"),
                ("node cli flow on two lines",
                 "        cli: { url: https://int-c.example.test:8443,\n"
                 "               timeout: PT9S }\n", "", "int/c: cannot read cli.url",
                 "server int/c.cli must be a block mapping or a one-line flow mapping"),
                ("node e2e block scalar", "        e2e: |\n          soap\n", "",
                 "int/c: cannot read e2e.soap.baseUrl", "server int/c.e2e must be"),
                ("node cli.url alias", "        cli:\n          url: *nowhere\n", "",
                 "int/c: cannot read cli.url", "server int/c.cli.url must be a single-line"),
                ("stage soap list", "", "    e2e:\n      soap: [x]\n",
                 "stage 'int': cannot read e2e.soap.baseUrl", "stage int.e2e.soap must be"),
                ("stage cli alias", "", "    cli: *nowhere\n",
                 "stage 'int': cannot read cli.url", "stage int.cli must be")):
            with self.subTest(name):
                profile = self.read(self.text(node, stage))
                int_ = profile.stage("int")
                self.assertTrue(int_.problem.startswith(prefix + " here (line "), int_.problem)
                self.assertIn(fragment, int_.problem)
                self.assertEqual(["int/a", "int/b"], [server.id for server in int_.servers])
                dev = profile.stage("dev")
                self.assertEqual((PIN_A, None), (dev.pin, dev.problem))
                # the problem wins over a missing own pin, also with the pin present
                self.assertEqual(int_.problem.split(" (line ")[0],
                                 self.read(self.text(self.C_PIN + node, stage)).stage("int")
                                 .problem.split(" (line ")[0])

    def test_uniform_stage_does_not_read_the_addresses(self):
        uniform = self.TEXT.replace("      pinPerServer: true\n", "")
        for node, stage in (("        cli: *nowhere\n", "    e2e: [x]\n"),
                            (self.NODE_CLI.format(url=self.HTTPS),
                             self.STAGE_SOAP.format(url=self.HTTPS))):
            with self.subTest(node=node):
                int_ = self.read(uniform.replace(self.C_URL, self.C_URL + node).replace(
                    "      trustStore: /stores/int.p12\n",
                    "      trustStore: /stores/int.p12\n" + stage)).stage("int")
                self.assertEqual((False, frozenset(), None),
                                 (int_.pin_per_server, int_.expected, int_.problem))


if __name__ == "__main__":
    unittest.main()
