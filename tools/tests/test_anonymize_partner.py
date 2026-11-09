"""Partner Management neutralization of tools/anonymize.py (feature 018, research D-1).

A system diagram's workflow.xml carries a PartnerManagementSettings property map with partner
names, partner codes, host names, logins and passwords. Its exact XML encoding is not known before
the first recording, so these tests feed several plausible encodings (Property maps, plain
elements, Field attributes, an escaped XML string) and check that every value in the subtree is
replaced by a synthetic one, except the small allowlist of harmless values. All values here are
synthetic.
"""

from __future__ import annotations

import base64
import contextlib
import importlib.util
import io
import re
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
MODULE_NAME = "anonymize_under_test"


def load_anonymize():
    """A fresh module object of tools/anonymize.py."""
    spec = importlib.util.spec_from_file_location(MODULE_NAME, str(TOOLS_DIR / "anonymize.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[MODULE_NAME] = module  # dataclasses look up the module of their annotations
    spec.loader.exec_module(module)
    return module


# Originals that must never survive (all synthetic stand-ins for customer data).
AES_ONE = "AES-c2VjcmV0LW9uZS1zZWNyZXQtb25lLXNlY3JldA=="
AES_TWO = "AES-dHdvLXR3by10d28tdHdvLXR3by10d28tdHdvLXR3bw=="
AESG_ONE = "AESGb3JpZ2luYWwtZ2NtLXZhbHVlLW9yaWdpbmFs"
LEGACY = "XyZ0ldEnc0ded"
PLAIN_PW = "hunter2plain"
PARTNER_IDS = ["9900000000001", "9900000000002"]
PARTNER_NAMES = ["Zeta Netz GmbH", "Omikron Energie AG"]
HOST = "sftp.zeta-netz.invalid"
LOGIN = "zetaftp"
WF_A, WF_B = "Zeta_Send_Invoice", "Zeta_Receive_Orders"
FILE_NAME = "INVOIC_*.edi"
WEB_SERVICE = "WebService protocol".split()[0]  # a protocol token that stays
ORIGINALS = [AES_ONE, AES_TWO, AESG_ONE, LEGACY, PLAIN_PW, HOST, LOGIN, WF_A, WF_B, FILE_NAME,
             "zeta-netz", "Zeta", "Omikron", *PARTNER_IDS, *PARTNER_NAMES]


def property_map_document(partner_count: int = 2) -> str:
    """The encoding of a technical workflow's property maps (nested <Property type="Map">)."""
    partners = []
    for i in range(partner_count):
        pid = PARTNER_IDS[i] if i < 2 else f"98000000000{i:02d}"
        pname = PARTNER_NAMES[i] if i < 2 else f"Partner Firma {i}"
        partners.append(f"""
        <Property name="Partner" type="Map">
          <Property name="Fields" type="Map">
            <Property name="Id" type="String">{pid}</Property>
            <Property name="Name" type="String">{pname}</Property>
            <Property name="Active" type="Boolean">true</Property>
          </Property>
          <Property name="ReferencedTWFs" type="List">
            <Property name="ReferencedTWF" type="Map" protocol="FTP">
              <Property name="Workflow" type="String">{WF_A}</Property>
              <Property name="ProtocolSettings" type="Map">
                <Property name="ServerName" type="String">{HOST}</Property>
                <Property name="Port" type="Integer">22</Property>
                <Property name="Login" type="String">{LOGIN}</Property>
                <Property name="Password" type="Password" encrypted="true">{AES_ONE}</Property>
                <Property name="FileName" type="String">{FILE_NAME}</Property>
                <Property name="Auth" type="String">KeyAndPassword</Property>
                <Property name="SSLKeyStore" type="String">zeta-keys.p12</Property>
              </Property>
              <Property name="AdditionalParameterSettings" type="Map">
                <Property name="FTPProtocol" type="String">SFTP</Property>
                <Property name="FTPAuth" type="String">NONE</Property>
                <Property name="WSSecurityUsername" type="String">{LOGIN}</Property>
                <Property name="WSSecurityPassword" type="Password">{PLAIN_PW}</Property>
              </Property>
            </Property>
            <Property name="ReferencedTWF" type="Map" protocol="{WEB_SERVICE}">
              <Property name="Workflow" type="String">{WF_B}</Property>
              <Property name="ProtocolSettings" type="Map">
                <Property name="URL" type="String">https://{HOST}/soap/invoice</Property>
                <Property name="Password" type="Password" encrypted="true">{AES_TWO}</Property>
                <Property name="Token" type="Password" encrypted="true">{AESG_ONE}</Property>
                <Property name="OldPassword" type="Password" encrypted="true">{LEGACY}</Property>
                <Property name="Timeout" type="Integer">123456</Property>
              </Property>
            </Property>
          </Property>
        </Property>""")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<IBISWorkflow version="5.3">
  <Workflows>
    <WorkflowGroup workflowType="systemdiagram">
      <WorkflowGroupName>GRP-01</WorkflowGroupName>
      <Workflow version="head" workflowType="systemdiagram">
        <WorkflowName>Workflow-0001</WorkflowName>
        <Property name="PartnerManagementSettings" type="Map">
          <Property name="PartnerFields" type="List">
            <Property name="0" type="String">Id</Property>
            <Property name="1" type="String">Name</Property>
          </Property>
          <Property name="ReferencableTWFs" type="List">
            <Property name="0" type="String">{WF_A}</Property>
            <Property name="1" type="String">{WF_B}</Property>
          </Property>
          <Property name="Partners" type="List">{''.join(partners)}
          </Property>
        </Property>
      </Workflow>
    </WorkflowGroup>
  </Workflows>
</IBISWorkflow>
"""


def element_document() -> str:
    """Plain elements, partner maps keyed by data, values in attributes."""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Root>
  <PartnerManagementSettings>
    <ReferencableTWFs><TWF>{WF_A}</TWF></ReferencableTWFs>
    <Partners>
      <Partner name="{PARTNER_NAMES[0]}">
        <Fields>
          <Field name="Id" value="{PARTNER_IDS[0]}"/>
          <Field name="Name" value="{PARTNER_NAMES[0]}"/>
        </Fields>
        <ReferencedTWFs>
          <Entry name="{WF_A}" protocol="FTP">
            <ProtocolSettings>
              <ServerName>{HOST}</ServerName>
              <Login>{LOGIN}</Login>
              <Setting name="Password" type="Password" encrypted="true" value="{AES_ONE}"/>
            </ProtocolSettings>
          </Entry>
        </ReferencedTWFs>
      </Partner>
    </Partners>
  </PartnerManagementSettings>
</Root>
"""


def escaped_document() -> str:
    """The whole PartnerManagementSettings stored as an escaped XML string."""
    inner = (f'<Map><Entry name="Partners"><Map><Entry name="Id">{PARTNER_IDS[1]}</Entry>'
             f'<Entry name="Name">{PARTNER_NAMES[1]}</Entry>'
             f'<Entry name="Password" type="Password" encrypted="true">{AES_TWO}</Entry>'
             f'</Map></Entry></Map>')
    escaped = inner.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<Root><Property '
            f'name="PartnerManagementSettings" type="String">{escaped}</Property></Root>\n')


FORMAT = "ZetaFormat"


def recorded_form_document(field_type: str = "String") -> str:
    """The form recorded on 2026-11-09 (research D-2 facts): PartnerFields declare the fields with
    a fieldType attribute, and every ReferencedTWF carries a format attribute."""
    entries = "".join(f"""
                <Property format="{FORMAT}" name="ReferencedTWF" protocol="{WEB_SERVICE}"
                          referencableTWFId="{n}" type="Map">
                  <Property name="ServerName">https://{HOST}/soap</Property>
                </Property>""" for n in (1, 2))
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Root>
  <Property name="PartnerManagementSettings" type="Map">
    <Property name="PartnerFields" type="List">
      <Property fieldType="{field_type}" name="Id" showInTable="true"/>
      <Property fieldType="{field_type}" name="Name" showInTable="true"/>
    </Property>
    <Property name="ReferencableTWFs" type="List">
      <Property name="{WF_A}" id="1" moduleId="7"/>
      <Property name="{WF_B}" id="2" moduleId="7"/>
    </Property>
    <Property name="Partners" type="List">
      <Property name="Partner" type="Map">
        <Property name="Fields" type="Map">
          <Property name="Id">{PARTNER_IDS[0]}</Property>
          <Property name="Name">{PARTNER_NAMES[0]}</Property>
        </Property>
        <Property name="ReferencedTWFs" type="List">{entries}
        </Property>
      </Property>
    </Property>
  </Property>
</Root>
"""


def assert_no_original(test: unittest.TestCase, text: str, originals=ORIGINALS):
    for original in originals:
        test.assertNotIn(original.lower(), text.lower(), "an original value survived")


class PartnerNeutralizationTest(unittest.TestCase):

    def setUp(self):
        self.anonymize = load_anonymize()

    def run_anonymizer(self, text, state=None, partner_limit=None):
        anonymizer = self.anonymize.Anonymizer(state or self.anonymize.State(),
                                               partner_limit=partner_limit)
        return anonymizer.anonymize(text, "text")

    def test_every_partner_value_is_replaced_by_its_synthetic_form(self):
        out = self.run_anonymizer(property_map_document())

        assert_no_original(self, out)
        self.assertIn('<Property name="Id" type="String">ACME-0001</Property>', out)
        self.assertIn('<Property name="Id" type="String">ACME-0002</Property>', out)
        self.assertIn('<Property name="Name" type="String">ACME Partner 1</Property>', out)
        self.assertIn('<Property name="Name" type="String">ACME Partner 2</Property>', out)
        self.assertRegex(out, r'name="ServerName" type="String">host\d+\.example\.test<')
        self.assertRegex(out, r'name="URL" type="String">https://host\d+\.example\.test/path\d+<')
        self.assertRegex(out, r'name="Login" type="String">login\d+<')
        self.assertRegex(out, r'name="WSSecurityUsername" type="String">login\d+<')
        self.assertRegex(out, r'name="Workflow" type="String">Workflow-\d{4}<')
        self.assertRegex(out, r'name="0" type="String">Workflow-\d{4}<')
        self.assertRegex(out, r'name="FileName" type="String">FileName-\d+<')
        self.assertRegex(out, r'name="Auth" type="String">Auth-\d+<')

    def test_structure_and_harmless_values_stay(self):
        out = self.run_anonymizer(property_map_document())

        for kept in ('<Property name="PartnerManagementSettings" type="Map">',
                     '<Property name="ReferencedTWF" type="Map" protocol="FTP">',
                     f'protocol="{WEB_SERVICE}"', 'name="Port" type="Integer">22<',
                     'name="Active" type="Boolean">true<', 'name="FTPProtocol" type="String">SFTP<',
                     'name="FTPAuth" type="String">NONE<', 'type="Password" encrypted="true"',
                     '<WorkflowName>Workflow-0001</WorkflowName>'):
            self.assertIn(kept, out)
        # six digits are not on the allowlist
        self.assertNotIn(">123456<", out)
        self.assertRegex(out, r'name="Timeout" type="Integer">Timeout-\d+<')

    def test_secrets_keep_their_form_and_equality_but_nothing_of_the_original(self):
        doc = property_map_document()
        out = self.run_anonymizer(doc)

        aes = re.findall(r'name="Password" type="Password" encrypted="true">(AES-[^<]*)<', out)
        self.assertEqual(4, len(aes))           # AES_ONE twice, AES_TWO twice
        self.assertEqual(2, len(set(aes)))      # equal stays equal, different stays different
        for value in aes:
            self.assertRegex(value, r"^AES-[A-Za-z0-9+/]{24,}={0,2}$")
        self.assertRegex(out, r'name="Token" type="Password" encrypted="true">AESG[A-Za-z0-9+/=]{24,}<')
        self.assertRegex(out, r'name="OldPassword" type="Password" encrypted="true">legacy-secret-\d+<')
        self.assertRegex(out, r'name="WSSecurityPassword" type="Password">legacy-secret-\d+<')
        self.assertRegex(out, r'name="SSLKeyStore" type="String">legacy-secret-\d+<')

    def test_secret_placeholders_are_not_derived_from_the_original(self):
        short = property_map_document().replace(AES_ONE, "AES-YWJj")
        long = property_map_document().replace(AES_ONE, "AES-" + "QUJD" * 40)

        self.assertEqual(self.run_anonymizer(short), self.run_anonymizer(long))

    def test_the_same_original_gets_the_same_placeholder_across_runs_with_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "state.json")
            state = self.anonymize.State.load(path)
            first = self.run_anonymizer(property_map_document(), state)
            state.save(path)
            # only the second partner: a fresh run would number it 1
            second = self.run_anonymizer(escaped_document(), self.anonymize.State.load(path))
        fresh = self.run_anonymizer(escaped_document())

        self.assertIn("ACME-0002", second)
        self.assertIn("ACME Partner 2", second)
        self.assertIn("ACME-0001", fresh)
        aes_two = re.findall(r'"Password" type="Password" encrypted="true">(AES-[^<]*)<', first)[1]
        self.assertIn(aes_two, second)

    def test_other_encodings_are_neutralized_too(self):
        for name, doc in (("elements", element_document()), ("escaped", escaped_document())):
            with self.subTest(name):
                out = self.run_anonymizer(doc)
                assert_no_original(self, out)
                self.assertIn("ACME-000", out)
                self.assertIn("ACME Partner", out)

        out = self.run_anonymizer(element_document())
        self.assertIn('<Field name="Id" value="ACME-0001"', out)
        self.assertRegex(out, r'<Entry name="Workflow-\d{4}" protocol="FTP">')
        self.assertRegex(out, r"<ServerName>host\d+\.example\.test</ServerName>")
        self.assertRegex(out, r"<Login>login\d+</Login>")
        self.assertRegex(out, r'encrypted="true" value="AES-[A-Za-z0-9+/=]{24,}"')
        # the partner key equals the partner name: it gets the same placeholder
        self.assertIn('<Partner name="ACME Partner 1">', out)

    def test_a_data_like_name_attribute_is_replaced(self):
        doc = ('<Root><Property name="PartnerManagementSettings" type="Map">'
               '<Property name="Zeta Netz GmbH" type="String">x1</Property>'
               '<Property name="Keep.This_Key-1" type="String">true</Property>'
               '</Property></Root>')
        out = self.run_anonymizer(doc)

        self.assertNotIn("Zeta", out)
        self.assertIn('name="Keep.This_Key-1"', out)

    def test_the_partner_limit_keeps_the_first_partners(self):
        doc = property_map_document(partner_count=7)

        limited = self.run_anonymizer(doc, partner_limit=5)
        unlimited = self.run_anonymizer(doc)

        self.assertEqual(5, limited.count('<Property name="Partner" type="Map">'))
        self.assertEqual(7, unlimited.count('<Property name="Partner" type="Map">'))
        self.assertIn("ACME-0005", limited)
        self.assertNotIn("ACME-0006", limited)

    def test_a_document_without_partner_management_is_unchanged(self):
        doc = '<?xml version="1.0"?>\n<a>\n  <b x="1">text</b>\n</a>\n'

        self.assertEqual(doc, self.run_anonymizer(doc))

    def test_a_document_with_partner_management_that_is_not_xml_is_refused(self):
        doc = '<a><Property name="PartnerManagementSettings">' + PARTNER_NAMES[0] + "</a"

        with self.assertRaises(SystemExit) as raised:
            self.run_anonymizer(doc)
        self.assertNotIn("Zeta", str(raised.exception))
        self.assertIn("PartnerManagementSettings", str(raised.exception))

    def test_field_type_stays_and_format_is_a_generic_value(self):
        out = self.run_anonymizer(recorded_form_document())

        assert_no_original(self, out, ORIGINALS + [FORMAT])
        self.assertIn('<Property fieldType="String" name="Id" showInTable="true" />', out)
        self.assertIn('<Property fieldType="String" name="Name" showInTable="true" />', out)
        formats = re.findall(r'format="([^"]*)"', out)
        self.assertEqual(["format-1", "format-1"], formats)  # equal stays equal
        self.assertRegex(out, r'<Property name="Workflow-\d{4}" id="1" moduleId="7"')
        self.assertIn('referencableTWFId="2"', out)

    def test_placeholders_survive_the_other_text_rules(self):
        out = self.run_anonymizer(property_map_document())

        self.assertNotIn("inubit.example.test", out)


class PartnerVerifyTest(unittest.TestCase):

    def setUp(self):
        self.anonymize = load_anonymize()

    def verify(self, *paths):
        with contextlib.redirect_stderr(io.StringIO()) as report:
            code = self.anonymize.verify(list(paths), self.anonymize.Anonymizer(
                self.anonymize.State()))
        return code, report.getvalue()

    def test_a_raw_partner_subtree_fails_without_printing_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp, "raw.xml")
            raw.write_text(property_map_document(), encoding="utf-8")

            code, report = self.verify(raw)

        self.assertEqual(1, code)
        self.assertRegex(report, r"raw\.xml:\d+: remaining partner")
        assert_no_original(self, report)

    def test_unknown_values_fail_even_without_known_rules(self):
        doc = ('<Root><Property name="PartnerManagementSettings" type="Map">\n'
               '<Property name="Note" type="String">plain words</Property>\n'
               '</Property></Root>\n')
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp, "raw.xml")
            raw.write_text(doc, encoding="utf-8")

            code, report = self.verify(raw)

        self.assertEqual(1, code)
        self.assertIn("raw.xml:2: remaining partner", report)

    def test_a_secret_with_a_real_looking_aes_value_fails(self):
        doc = ('<Root><Property name="PartnerManagementSettings" type="Map">'
               f'<Property name="Password" type="Password" encrypted="true">{AES_ONE}</Property>'
               '</Property></Root>')
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp, "raw.xml")
            raw.write_text(doc, encoding="utf-8")

            code, report = self.verify(raw)

        self.assertEqual(1, code)
        self.assertNotIn(AES_ONE, report)

    def test_neutralized_documents_pass_also_inside_a_zip(self):
        anonymizer = self.anonymize.Anonymizer(self.anonymize.State())
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for name, doc in (("maps", property_map_document()), ("elements", element_document()),
                              ("escaped", escaped_document())):
                path = Path(tmp, name + ".xml")
                path.write_text(anonymizer.anonymize(doc, "text"), encoding="utf-8")
                paths.append(path)
            archive = Path(tmp, "out.zip")
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("workflow/workflow.xml", paths[0].read_text(encoding="utf-8"))
            paths.append(archive)

            code, report = self.verify(*paths)

        self.assertEqual(0, code, report)

    def verify_neutralized(self, field_type):
        out = self.anonymize.Anonymizer(self.anonymize.State()).anonymize(
            recorded_form_document(field_type), "text")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "out.xml")
            path.write_text(out, encoding="utf-8")
            return self.verify(path)

    def test_kept_field_types_pass_when_they_look_like_a_type(self):
        for field_type in ("String", "x", "java.lang.String", "Plain_Text2", "A" + "b" * 40):
            with self.subTest(field_type):
                code, report = self.verify_neutralized(field_type)

                self.assertEqual(0, code, report)

    def test_kept_field_types_fail_when_they_do_not_look_like_a_type(self):
        for field_type in (PARTNER_NAMES[0], "fieldType-1", "1String", "_String", "",
                           "A" + "b" * 41, "Zeta/Netz"):
            with self.subTest(field_type):
                code, report = self.verify_neutralized(field_type)

                self.assertEqual(1, code)
                self.assertRegex(report, r"out\.xml:\d+: remaining partner field type")
                if field_type:
                    self.assertNotIn(field_type, report)

    def test_a_raw_subtree_inside_a_zip_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp, "raw.zip")
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("workflow/workflow.xml", element_document())

            code, report = self.verify(archive)

        self.assertEqual(1, code)
        self.assertIn("raw.zip!workflow/workflow.xml:", report)


class PartnerCommandLineTest(unittest.TestCase):

    def setUp(self):
        self.anonymize = load_anonymize()

    def main(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            try:
                code = self.anonymize.main(list(args))
            except SystemExit as exit_:
                code = exit_.code
        message = code if isinstance(code, str) else ""
        return code, out.getvalue() + err.getvalue() + message

    def test_zip_keep_with_a_partner_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp, "in.zip"), Path(tmp, "out.zip")
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("workflow/workflow.xml", property_map_document(partner_count=7))
                archive.writestr("archive.properties", "sourceVersion=8.1.17\n")
            state = Path(tmp, "state.json")

            code, printed = self.main("--state", str(state), "--partner-limit", "5",
                                      "--zip-keep", "workflow/workflow.xml",
                                      "--zip-keep", "archive.properties",
                                      str(source), str(target))
            with zipfile.ZipFile(target) as archive:
                workflow = archive.read("workflow/workflow.xml").decode("utf-8")
            verify_code, verify_printed = self.main("--verify", str(target))

        self.assertEqual(0, code, printed)
        self.assertEqual(5, workflow.count('<Property name="Partner" type="Map">'))
        assert_no_original(self, workflow)
        assert_no_original(self, printed + verify_printed)
        self.assertEqual(0, verify_code, verify_printed)

    def test_partner_limit_needs_a_positive_number(self):
        code, printed = self.main("--partner-limit", "0", "in.xml")

        self.assertNotEqual(0, code)
        self.assertIn("--partner-limit", printed)

    def test_a_refused_document_writes_no_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp, "in.xml"), Path(tmp, "out.xml")
            source.write_text('<a name="PartnerManagementSettings">' + PARTNER_NAMES[0],
                              encoding="utf-8")

            code, printed = self.main(str(source), str(target))

            self.assertFalse(target.exists())
        self.assertNotEqual(0, code)
        assert_no_original(self, printed)


class SecretPlaceholderFormTest(unittest.TestCase):

    def test_aes_placeholders_decode_to_a_synthetic_marker(self):
        anonymize = load_anonymize()
        out = anonymize.Anonymizer(anonymize.State()).anonymize(property_map_document(), "text")
        value = re.search(r'encrypted="true">AES-([^<]*)<', out).group(1)

        self.assertTrue(base64.b64decode(value).startswith(b"synthetic-"))


if __name__ == "__main__":
    unittest.main()
