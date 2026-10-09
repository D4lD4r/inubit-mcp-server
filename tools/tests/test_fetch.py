"""Fingerprints and the certificate fetch of inubit-cert-check (task T006, research R-10)."""

from __future__ import annotations

import hashlib
import os
import re
import socket
import ssl
import tempfile
import time
import unittest
from unittest import mock

import support

OPENSSL = support.find_openssl()
NEEDS_OPENSSL = unittest.skipIf(OPENSSL is None, "openssl not found")
if OPENSSL is not None:
    from tls_fixtures import BlackHole, EchoServer, TlsServer, closed_port, make_cert

HOST = "node1.example.test"
ISO_UTC = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ")
FP = "AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:01:23:45:67:" \
    "89:AB:CD:EF:10:32:54:76:98:FF:01:02"


def loopback_resolver(host, port):
    """Resolves every test host name to 127.0.0.1 (no DNS query leaves the machine)."""
    return "127.0.0.1"


def sha256_of_pem(path):
    with open(path, encoding="ascii") as handle:
        der = ssl.PEM_cert_to_DER_cert(handle.read())
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i:i + 2] for i in range(0, 64, 2))


class NormalizeTest(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()

    def test_accepted_forms(self):
        bare = FP.replace(":", "")
        for text in (FP, FP.lower(), bare, bare.lower(), " " + FP + "\n",
                     FP[:10].lower() + FP[10:]):
            with self.subTest(text=text):
                self.assertEqual(FP, self.tool.normalize_fingerprint(text))

    def test_rejected_forms(self):
        bare = FP.replace(":", "")
        for text in ("", bare[:-1], bare + "0", FP[:-1], FP + ":00", bare[:-1] + "G",
                     FP.replace("AA", "ZZ", 1), FP.replace(":", "", 1), FP.replace(":", "-"),
                     None):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    self.tool.normalize_fingerprint(text)


class X509OutputTest(unittest.TestCase):
    """The parser of `openssl x509 -noout -fingerprint -sha256 -subject -issuer -dates`."""

    LIBRESSL = (f"SHA256 Fingerprint={FP}\n"
                "subject= /CN=selfsigned.test/O=Acme Test\n"
                "issuer= /CN=selfsigned.test/O=Acme Test\n"
                "notBefore=Oct  9 04:05:27 2026 GMT\n"
                "notAfter=Oct 16 04:05:27 2036 GMT\n")
    LIBRESSL_NEW = (f"SHA256 Fingerprint={FP}\n"
                    "subject=CN=selfsigned.test, O=Acme Test\n"
                    "issuer=CN=selfsigned.test, O=Acme Test\n"
                    "notBefore=Oct  9 04:05:27 2026 GMT\n"
                    "notAfter=Oct 16 04:05:27 2036 GMT\n")
    OPENSSL3 = (f"sha256 Fingerprint={FP}\n"
                "subject=CN = selfsigned.test, O = Acme Test\n"
                "issuer=CN = selfsigned.test, O = Acme Test\n"
                "notBefore=Oct  9 04:05:27 2026 GMT\n"
                "notAfter=Oct 16 04:05:27 2036 GMT\n")
    ISO_DATES = (f"sha256 Fingerprint={FP}\n"
                 "subject=CN = selfsigned.test, O = Acme Test\n"
                 "issuer=CN = selfsigned.test, O = Acme Test\n"
                 "notBefore=2026-10-09 04:05:27Z\n"
                 "notAfter=2036-10-16 04:05:27Z\n")

    def test_samples(self):
        tool = support.load_tool()
        for label, text in (("LibreSSL", self.LIBRESSL), ("LibreSSL new", self.LIBRESSL_NEW),
                            ("OpenSSL 3", self.OPENSSL3), ("ISO dates", self.ISO_DATES)):
            with self.subTest(label):
                info = tool.parse_x509_output(text)
                self.assertEqual(FP, info["fingerprint"])
                self.assertEqual("CN=selfsigned.test, O=Acme Test", info["subject"])
                self.assertEqual("CN=selfsigned.test, O=Acme Test", info["issuer"])
                self.assertEqual("2026-10-09T04:05:27Z", info["notBefore"])
                self.assertEqual("2036-10-16T04:05:27Z", info["notAfter"])

    def test_incomplete_output(self):
        tool = support.load_tool()
        for missing in ("Fingerprint", "subject", "issuer", "notBefore", "notAfter"):
            with self.subTest(missing=missing):
                text = "\n".join(line for line in self.OPENSSL3.splitlines()
                                 if missing not in line)
                with self.assertRaises(ValueError):
                    tool.parse_x509_output(text)

    def test_first_certificate(self):
        tool = support.load_tool()
        first = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"
        second = "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----"
        text = f"CONNECTED(00000003)\n---\n 0 s:/CN=a\n{first}\n 1 s:/CN=b\n{second}\n---\n"
        self.assertEqual(first + "\n", tool.first_certificate(text))
        self.assertIsNone(tool.first_certificate("CONNECTED(00000003)\nno peer certificate\n"))

    def test_connect_address(self):
        tool = support.load_tool()
        self.assertEqual("127.0.0.1:8443", tool.connect_address("127.0.0.1", 8443))
        self.assertEqual("[::1]:443", tool.connect_address("::1", 443))


@NEEDS_OPENSSL
class FetchTest(unittest.TestCase):

    def setUp(self):
        self.tool = support.load_tool()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log = []

    def server(self, port, host=HOST):
        return self.tool.Server(id="dev/node1", stage="dev", name="node1",
                                base_url=f"https://{host}:{port}", host=host, port=port)

    def fetch(self, port, host=HOST, resolver=loopback_resolver, deadline=None, runner=None,
              log=None):
        return self.tool.fetch_certificate(self.server(port, host), deadline=deadline,
                                           resolver=resolver, log=log or self.log.append,
                                           runner=runner)

    def test_presented_certificate(self):
        cert, key, fingerprint = make_cert(self.tmp.name, "selfsigned.test")
        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port)
            names = list(tls.sni_names)
        self.assertIsNone(result.error)
        self.assertTrue(result.reachable)
        self.assertEqual(fingerprint, result.fingerprint)
        self.assertEqual(sha256_of_pem(cert), result.fingerprint)
        self.assertEqual("CN=selfsigned.test", result.subject)
        self.assertEqual("CN=selfsigned.test", result.issuer)
        self.assertRegex(result.not_before, ISO_UTC)
        self.assertRegex(result.not_after, ISO_UTC)
        self.assertTrue(result.valid_now)
        self.assertEqual("127.0.0.1", result.ip)
        self.assertEqual("dev/node1", result.server_id)
        self.assertEqual([HOST], names, "the host name is sent as SNI")
        self.assertTrue(result.pem.startswith("-----BEGIN CERTIFICATE-----"))

    def test_pem_never_logged_nor_shown(self):
        state_dir = os.path.join(self.tmp.name, "state")
        state = self.tool.State(state_dir, "acme")
        cert, key, fingerprint = make_cert(self.tmp.name, "selfsigned.test")
        temp_before = set(os.listdir(tempfile.gettempdir()))
        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port, log=state.diag)
        with EchoServer() as echo:
            self.assertEqual("tls", self.fetch(echo.port, log=state.diag).error)
        with BlackHole() as hole:
            self.assertEqual("timeout", self.fetch(hole.port, log=state.diag,
                                                   deadline=time.monotonic() + 1).error)
        body = result.pem.splitlines()[1]
        self.assertNotIn("BEGIN CERTIFICATE", repr(result))
        self.assertNotIn(body, repr(result))
        self.assertEqual(["cert-check.log"], os.listdir(state_dir))
        with open(state.diag_log, encoding="utf-8") as handle:
            log = handle.read()
        self.assertIn(fingerprint, log, "the success is logged (without the PEM)")
        self.assertIn("(tls)", log)
        self.assertIn("(timeout)", log)
        self.assertNotIn("BEGIN CERTIFICATE", log)
        self.assertNotIn(body, log)
        for name in set(os.listdir(tempfile.gettempdir())) - temp_before:
            path = os.path.join(tempfile.gettempdir(), name)
            if os.path.isfile(path) and os.access(path, os.R_OK):
                with open(path, "rb") as handle:
                    self.assertNotIn(b"BEGIN CERTIFICATE", handle.read(1 << 20), path)

    def test_minimal_environment_for_openssl(self):
        cert, key, _fingerprint = make_cert(self.tmp.name, "selfsigned.test")
        seen = []

        def recording(argv, input=None, timeout=None, env=None):
            seen.append(env)
            return self.tool.run_process(argv, input=input, timeout=timeout, env=env)

        secrets = {"INUBIT_ACME_DEV_PASSWORD": "secret-value", "INUBIT_ACME_DEV_USERNAME": "u",
                   "OPENSSL_CONF": "/nonexistent.cnf"}
        with mock.patch.dict(os.environ, secrets), TlsServer(cert, key) as tls:
            self.assertIsNone(self.fetch(tls.port, runner=recording).error)
        self.assertEqual(2, len(seen))
        for env in seen:
            self.assertEqual({"PATH", "HOME", "LC_ALL"}, set(env))
            self.assertEqual("C", env["LC_ALL"])
            self.assertNotIn("secret-value", env.values())

    def test_handshake_finishing_after_the_deadline_is_a_timeout(self):
        cert, key, _fingerprint = make_cert(self.tmp.name, "selfsigned.test")
        deadline = time.monotonic() + 1.0
        calls = []

        def late(argv, input=None, timeout=None, env=None):
            calls.append(argv[1])
            result = self.tool.run_process(argv, input=input, timeout=timeout, env=env)
            time.sleep(max(0.0, deadline - time.monotonic()) + 0.1)
            return result

        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port, runner=late, deadline=deadline)
        self.assertEqual("timeout", result.error)
        self.assertEqual(["s_client"], calls, "no x509 step after the deadline")
        self.assertLess(time.monotonic() - deadline, 0.5)

    def test_x509_step_within_the_deadline(self):
        cert, key, _fingerprint = make_cert(self.tmp.name, "selfsigned.test")
        deadline = time.monotonic() + 2.0
        limits = []

        def slow_x509(argv, input=None, timeout=None, env=None):
            if argv[1] == "x509":
                limits.append(timeout - (deadline - time.monotonic()))
                time.sleep(max(0.0, deadline - time.monotonic()) + 0.1)
            return self.tool.run_process(argv, input=input, timeout=timeout, env=env)

        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port, runner=slow_x509, deadline=deadline)
        self.assertEqual("timeout", result.error, "finishing after the deadline counts")
        self.assertEqual(1, len(limits))
        self.assertLessEqual(limits[0], 0.01, "x509 never gets more than the time left")

    def test_child_environment(self):
        with mock.patch.dict(os.environ, {"HOME": "/home/operator", "SECRET_PASSWORD": "x"}):
            self.assertEqual({"PATH": "/jdk/bin:/usr/bin:/bin", "HOME": "/home/operator",
                              "LC_ALL": "C"}, self.tool.child_environment(["/jdk/bin"]))
            self.assertEqual("/usr/bin:/bin", self.tool.child_environment()["PATH"])

    def test_swapped_certificate(self):
        cert, key, _old = make_cert(self.tmp.name, "selfsigned.test")
        cert2, key2, new = make_cert(self.tmp.name, "selfsigned.test")
        with TlsServer(cert, key) as tls:
            tls.swap_cert(cert2, key2)
            self.assertEqual(new, self.fetch(tls.port).fingerprint)

    def test_not_yet_valid(self):
        cert, key, _fingerprint = make_cert(self.tmp.name, "future.test", days=30,
                                            not_before_offset=86400)
        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port)
        self.assertIsNone(result.error)
        self.assertFalse(result.valid_now)

    def test_expired(self):
        cert, key, _fingerprint = make_cert(self.tmp.name, "expired.test", days=1,
                                            not_before_offset=-10 * 86400)
        with TlsServer(cert, key) as tls:
            result = self.fetch(tls.port)
        self.assertIsNone(result.error)
        self.assertFalse(result.valid_now)

    def test_timeout_within_limit(self):
        with BlackHole() as hole:
            started = time.monotonic()
            result = self.fetch(hole.port)
            elapsed = time.monotonic() - started
        self.assertEqual("timeout", result.error)
        self.assertFalse(result.reachable)
        self.assertIsNone(result.fingerprint)
        self.assertEqual("127.0.0.1", result.ip)
        self.assertLess(elapsed, 5.5)

    def test_deadline_is_absolute(self):
        with BlackHole() as hole:
            started = time.monotonic()
            result = self.fetch(hole.port, deadline=started + 1.0)
            elapsed = time.monotonic() - started
        self.assertEqual("timeout", result.error)
        self.assertLess(elapsed, 1.5)

    def test_slow_resolver_counts_as_timeout(self):
        def slow(host, port):
            time.sleep(3)
            return "127.0.0.1"

        started = time.monotonic()
        result = self.fetch(closed_port(), resolver=slow, deadline=started + 1.0)
        self.assertEqual("timeout", result.error)
        self.assertLess(time.monotonic() - started, 1.5)

    def test_connection_refused(self):
        result = self.fetch(closed_port())
        self.assertEqual("connect", result.error)
        self.assertEqual("127.0.0.1", result.ip)

    def test_dns_failure(self):
        def no_such_host(host, port):
            raise socket.gaierror(socket.EAI_NONAME, "nodename nor servname provided")

        result = self.fetch(443, host="does-not-exist.invalid", resolver=no_such_host)
        self.assertEqual("dns", result.error)
        self.assertIsNone(result.ip)

    def test_not_tls(self):
        with EchoServer() as echo:
            result = self.fetch(echo.port)
        self.assertEqual("tls", result.error)

    def test_default_resolver_with_ip_literal(self):
        self.assertEqual("127.0.0.1", self.tool.resolve_host("127.0.0.1", 443))

    def test_openssl_lookup(self):
        self.assertEqual(OPENSSL, self.tool.find_openssl())


if __name__ == "__main__":
    unittest.main()
