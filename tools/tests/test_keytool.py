"""Password-less trust-store access through keytool (task T008, research R-5)."""

from __future__ import annotations

import datetime
import os
import re
import subprocess
import tempfile
import unittest
from unittest import mock

import support

FP_1 = "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:01:23:45:67:" \
    "89:AB:CD:EF:10:32:54:76:98:FF:01:02"
FP_2 = "BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:10:20:30:40:" \
    "50:60:70:80:90:A0:B0:C0:D0:E0:F0:03"
SHA1 = "58:5A:B3:6A:78:29:BA:65:BD:66:41:86:AD:62:35:DE:B0:FC:22:5A"
STORE_OPTIONS = ["-storetype", "PKCS12", "-storepass", "unused",
                 "-J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE",
                 "-J-Dkeystore.pkcs12.macAlgorithm=NONE"]
PEM = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIJAPfakeFakeFake\n-----END CERTIFICATE-----\n"

ENGLISH = f"""\
Keystore type: PKCS12
Keystore provider: SUN

Your keystore contains 2 entries

Alias name: dev-aa112233-20250101
Creation date: Jan 1, 2025
Entry type: trustedCertEntry

Owner: CN=selfsigned.test
Issuer: CN=selfsigned.test
Serial number: 1
Valid from: Wed Jan 01 00:00:00 UTC 2025 until: Sat Dec 30 00:00:00 UTC 2034
Certificate fingerprints:
\t SHA1: {SHA1}
\t SHA256: {FP_1}
Signature algorithm name: SHA256withRSA
Subject Public Key Algorithm: 2048-bit RSA key
Version: 1


*******************************************
*******************************************


Alias name: dev-bb223344-20261008
Creation date: Oct 8, 2026
Entry type: trustedCertEntry

Owner: CN=selfsigned.test
Issuer: CN=selfsigned.test
Serial number: 2
Valid from: Thu Oct 08 13:06:26 UTC 2026 until: Sun Oct 05 13:06:26 UTC 2036
Certificate fingerprints:
\t SHA1: {SHA1}
\t SHA256: {FP_2}
Signature algorithm name: SHA256withRSA
Subject Public Key Algorithm: 2048-bit RSA key
Version: 1


*******************************************
*******************************************


"""

GERMAN = f"""\
Keystore-Typ: PKCS12
Keystore-Provider: SUN

Keystore enthält 2 Einträge

Aliasname: dev-aa112233-20250101
Erstellungsdatum: 01.01.2025
Eintragstyp: trustedCertEntry

Eigentümer: CN=selfsigned.test
Aussteller: CN=selfsigned.test
Seriennummer: 1
Gültig von: Wed Jan 01 00:00:00 UTC 2025 bis: Sat Dec 30 00:00:00 UTC 2034
Zertifikatsfingerprints:
\t SHA1: {SHA1}
\t SHA256: {FP_1}
Signaturalgorithmusname: SHA256withRSA
Public-Key-Algorithmus von Subject: 2048-Bit-RSA-Schlüssel
Version: 1


*******************************************
*******************************************


Aliasname: dev-bb223344-20261008
Erstellungsdatum: 08.10.2026
Eintragstyp: trustedCertEntry

Eigentümer: CN=selfsigned.test
Aussteller: CN=selfsigned.test
Seriennummer: 2
Gültig von: Thu Oct 08 13:06:26 UTC 2026 bis: Sun Oct 05 13:06:26 UTC 2036
Zertifikatsfingerprints:
\t SHA1: {SHA1}
\t SHA256: {FP_2}
Signaturalgorithmusname: SHA256withRSA
Public-Key-Algorithmus von Subject: 2048-Bit-RSA-Schlüssel
Version: 1


*******************************************
*******************************************


"""


class FakeRunner:
    """Records every call; answers with the scripted (returncode, stdout, stderr)."""

    def __init__(self, returncode=0, stdout="", stderr=""):
        self.calls = []
        self.answer = subprocess.CompletedProcess([], returncode, stdout, stderr)

    def __call__(self, argv, input=None, timeout=None, env=None):
        self.calls.append({"argv": argv, "input": input, "timeout": timeout, "env": env})
        return subprocess.CompletedProcess(argv, self.answer.returncode, self.answer.stdout,
                                           self.answer.stderr)


class FakeKeytoolTest(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()

    def keytool(self, runner):
        return self.tool.Keytool("/jdk/bin/keytool", runner=runner)

    def test_list_english(self):
        runner = FakeRunner(stdout=ENGLISH)
        entries = self.keytool(runner).list_entries("/stores/dev.p12")
        self.assertEqual([("dev-aa112233-20250101", FP_1), ("dev-bb223344-20261008", FP_2)],
                         [(entry.alias, entry.fingerprint) for entry in entries])

    def test_list_german(self):
        entries = self.keytool(FakeRunner(stdout=GERMAN)).list_entries("/stores/dev.p12")
        self.assertEqual([("dev-aa112233-20250101", FP_1), ("dev-bb223344-20261008", FP_2)],
                         [(entry.alias, entry.fingerprint) for entry in entries])

    def test_list_argv(self):
        runner = FakeRunner(stdout=ENGLISH)
        self.keytool(runner).list_entries("/stores/dev.p12")
        [call] = runner.calls
        argv = call["argv"]
        self.assertIsInstance(argv, list, "argv only, never a shell command line")
        self.assertEqual("/jdk/bin/keytool", argv[0])
        self.assertIn("-J-Duser.language=en", argv)
        self.assertIn("-list", argv)
        self.assertIn("-v", argv)
        self.assertEqual("/stores/dev.p12", argv[argv.index("-keystore") + 1])
        self.assertEqual(STORE_OPTIONS, argv[argv.index("-storetype"):][:len(STORE_OPTIONS)])
        self.assertIsNone(call["input"])
        self.assertIsNotNone(call["timeout"])

    def test_empty_store(self):
        empty = ("Keystore type: PKCS12\nKeystore provider: SUN\n\n"
                 "Your keystore contains 0 entries\n\n")
        self.assertEqual([], self.keytool(FakeRunner(stdout=empty)).list_entries("/s.p12"))

    def test_entry_without_fingerprint_is_an_error(self):
        broken = "Alias name: dev-aa112233-20250101\nEntry type: trustedCertEntry\n"
        with self.assertRaises(self.tool.KeytoolError):
            self.keytool(FakeRunner(stdout=broken)).list_entries("/s.p12")

    def test_import_argv_and_pem_on_stdin(self):
        runner = FakeRunner(stdout="Certificate was added to keystore\n")
        self.keytool(runner).import_cert("/stores/dev.p12", PEM, "dev-bb223344-20261008")
        [call] = runner.calls
        argv = call["argv"]
        self.assertIn("-importcert", argv)
        self.assertIn("-noprompt", argv)
        self.assertEqual("dev-bb223344-20261008", argv[argv.index("-alias") + 1])
        self.assertEqual("/stores/dev.p12", argv[argv.index("-keystore") + 1])
        self.assertEqual(STORE_OPTIONS, argv[argv.index("-storetype"):][:len(STORE_OPTIONS)])
        self.assertNotIn("-file", argv, "the PEM stays in memory")
        self.assertEqual(PEM, call["input"])
        self.assertFalse(any("BEGIN CERTIFICATE" in part for part in argv))

    def test_delete_argv(self):
        runner = FakeRunner()
        self.keytool(runner).delete_entry("/stores/dev.p12", "dev-aa112233-20250101")
        [call] = runner.calls
        argv = call["argv"]
        self.assertIn("-delete", argv)
        self.assertIn("-noprompt", argv)
        self.assertEqual("dev-aa112233-20250101", argv[argv.index("-alias") + 1])
        self.assertEqual("/stores/dev.p12", argv[argv.index("-keystore") + 1])
        self.assertEqual(STORE_OPTIONS, argv[argv.index("-storetype"):][:len(STORE_OPTIONS)])

    def test_failure(self):
        runner = FakeRunner(returncode=1, stdout="keytool error: java.lang.Exception: "
                                                 "Keystore file does not exist: /s.p12\n")
        keytool = self.keytool(runner)
        for action in (lambda: keytool.list_entries("/s.p12"),
                       lambda: keytool.import_cert("/s.p12", PEM, "dev-bb223344-20261008"),
                       lambda: keytool.delete_entry("/s.p12", "dev-bb223344-20261008")):
            with self.assertRaises(self.tool.KeytoolError) as caught:
                action()
            self.assertIn("does not exist", str(caught.exception))
            self.assertNotIn("BEGIN CERTIFICATE", str(caught.exception))

    def test_runner_cannot_start(self):
        def missing(argv, input=None, timeout=None, env=None):
            raise FileNotFoundError(2, "No such file or directory", argv[0])

        with self.assertRaises(self.tool.KeytoolError):
            self.keytool(missing).list_entries("/s.p12")

    def test_minimal_environment(self):
        runner = FakeRunner(stdout=ENGLISH)
        secrets = {"INUBIT_ACME_DEV_PASSWORD": "secret-value", "INUBIT_ACME_DEV_USERNAME": "user",
                   "JAVA_TOOL_OPTIONS": "-Dsomething", "HOME": "/home/operator"}
        with mock.patch.dict(os.environ, secrets):
            keytool = self.keytool(runner)
            keytool.list_entries("/stores/dev.p12")
            keytool.import_cert("/stores/dev.p12", PEM, "dev-bb223344-20261008")
            keytool.delete_entry("/stores/dev.p12", "dev-bb223344-20261008")
        for call in runner.calls:
            env = call["env"]
            self.assertEqual({"PATH": "/jdk/bin:/usr/bin:/bin", "HOME": "/home/operator",
                              "LC_ALL": "C"}, env)

    def test_sibling_of_java(self):
        keytool = self.tool.Keytool.for_java("/jdk/Contents/Home/bin/java")
        self.assertEqual("/jdk/Contents/Home/bin/keytool", keytool.path)

    def test_alias(self):
        self.assertEqual("dev-bb223344-20261008",
                         self.tool.trust_alias("dev", FP_2, datetime.date(2026, 10, 8)))
        today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
        self.assertEqual(f"int-aa112233-{today}", self.tool.trust_alias("int", FP_1))


KEYTOOL = support.find_keytool()
OPENSSL = support.find_openssl()


@unittest.skipIf(KEYTOOL is None, "no keytool (set JAVA_HOME, or install a JDK found by "
                                  "/usr/libexec/java_home)")
@unittest.skipIf(OPENSSL is None, "openssl not found")
class RealKeytoolTest(unittest.TestCase):
    """Opt-in: runs only where a JDK's keytool is available."""

    def test_create_import_list_delete(self):
        from tls_fixtures import make_cert
        tool = support.load_tool()
        keytool = tool.Keytool(KEYTOOL)
        with tempfile.TemporaryDirectory() as tmp:
            first, _key, fp_first = make_cert(tmp, "selfsigned.test")
            second, _key, fp_second = make_cert(tmp, "selfsigned.test")
            store = os.path.join(tmp, "acme-dev-truststore.p12")
            for cert, fingerprint in ((first, fp_first), (second, fp_second)):
                with open(cert, encoding="ascii") as handle:
                    keytool.import_cert(store, handle.read(), tool.trust_alias("dev", fingerprint))
            entries = keytool.list_entries(store)
            self.assertEqual(sorted([fp_first, fp_second]),
                             sorted(entry.fingerprint for entry in entries))
            keytool.delete_entry(store, tool.trust_alias("dev", fp_first))
            self.assertEqual([fp_second], [entry.fingerprint
                                           for entry in keytool.list_entries(store)])
            # password-less: readable without any password (LibreSSL needs -nomacver, as the
            # store has no MAC at all; OpenSSL 3 only warns)
            result = subprocess.run([OPENSSL, "pkcs12", "-in", store, "-nokeys", "-passin",
                                     "pass:", "-nomacver"], stdin=subprocess.DEVNULL,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(1, result.stdout.count("-----BEGIN CERTIFICATE-----"))
            # no MAC: the PFX SEQUENCE has only version and authSafe (macData absent)
            parsed = subprocess.run([OPENSSL, "asn1parse", "-inform", "DER", "-in", store],
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                    timeout=30)
            self.assertEqual(0, parsed.returncode, parsed.stderr)
            self.assertEqual(2, len(re.findall(r":d=1\s", parsed.stdout)), parsed.stdout[:500])


if __name__ == "__main__":
    unittest.main()
