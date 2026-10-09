"""Throw-away certificates and local network servers for the tests of inubit-cert-check.

Everything listens on 127.0.0.1 only and is generated per test in a temporary directory:

  make_cert(tmpdir, cn, days, not_before_offset)  self-signed RSA-2048 certificate + key
  TlsServer(cert, key)    answers TLS handshakes, records the SNI name of each, swap_cert()
  BlackHole()             accepts TCP connections and never answers (for timeouts)
  EchoServer()            plain TCP, echoes what it receives (a server that does not speak TLS)
  closed_port()           a port with nothing listening (connection refused)
"""

from __future__ import annotations

import datetime
import os
import socket
import ssl
import subprocess
import tempfile
import threading

from support import find_openssl

LOOPBACK = "127.0.0.1"
POLL = 0.1  # seconds; how often the server threads look at their stop flag


class FixtureError(Exception):
    """A fixture could not be built (e.g. openssl failed)."""


def _openssl(args, cwd):
    openssl = find_openssl()
    if openssl is None:
        raise FixtureError("openssl not found")
    result = subprocess.run([openssl] + list(args), cwd=cwd, stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        raise FixtureError(f"openssl {args[0]} failed: {result.stderr.strip()[:300]}")
    return result.stdout


def _config(directory, cn):
    """A minimal openssl configuration, so no system openssl.cnf influences the certificate."""
    path = os.path.join(directory, "openssl.cnf")
    with open(path, "w", encoding="ascii") as handle:
        handle.write(
            "[ req ]\ndistinguished_name = dn\nprompt = no\n"
            f"[ dn ]\nCN = {cn}\n"
            "[ ca ]\ndefault_ca = ca_default\n"
            "[ ca_default ]\ndatabase = ./index.txt\nserial = ./serial\nnew_certs_dir = .\n"
            "default_md = sha256\npolicy = policy_any\nunique_subject = no\n"
            "email_in_dn = no\n"
            "[ policy_any ]\ncommonName = supplied\n")
    return path


def _asn1_time(moment):
    return moment.strftime("%Y%m%d%H%M%SZ")


def make_cert(tmpdir, cn, days=3650, not_before_offset=0):
    """A new self-signed certificate: (PEM path, key path, SHA-256 fingerprint "AA:BB:...").

    not_before_offset shifts notBefore by that many seconds from now (negative: in the past);
    notAfter is notBefore + days. With offset 0 the certificate comes from `openssl req -x509`.
    """
    directory = tempfile.mkdtemp(prefix="cert-", dir=str(tmpdir))
    config = _config(directory, cn)
    cert = os.path.join(directory, "cert.pem")
    key = os.path.join(directory, "key.pem")
    if not_before_offset == 0:
        _openssl(["req", "-x509", "-nodes", "-newkey", "rsa:2048", "-config", config,
                  "-keyout", key, "-out", cert, "-days", str(days)], directory)
    else:
        start = datetime.datetime.now(datetime.timezone.utc) \
            + datetime.timedelta(seconds=not_before_offset)
        end = start + datetime.timedelta(days=days)
        request = os.path.join(directory, "request.pem")
        with open(os.path.join(directory, "index.txt"), "w", encoding="ascii"):
            pass
        with open(os.path.join(directory, "serial"), "w", encoding="ascii") as handle:
            handle.write("01\n")
        _openssl(["req", "-new", "-nodes", "-newkey", "rsa:2048", "-config", config,
                  "-keyout", key, "-out", request], directory)
        _openssl(["ca", "-batch", "-config", config, "-selfsign", "-keyfile", key,
                  "-in", request, "-out", cert, "-notext",
                  "-startdate", _asn1_time(start), "-enddate", _asn1_time(end)], directory)
    output = _openssl(["x509", "-in", cert, "-noout", "-fingerprint", "-sha256"], directory)
    fingerprint = output.strip().split("=", 1)[1].strip().upper()
    return cert, key, fingerprint


class _Listener:
    """A TCP listener on 127.0.0.1 with an ephemeral port and an accept loop in a daemon thread."""

    def __init__(self):
        self._stop = threading.Event()
        self._connections = []
        self._lock = threading.Lock()
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._socket.bind((LOOPBACK, 0))
        self._socket.listen(16)
        self._socket.settimeout(POLL)
        self.host = LOOPBACK
        self.port = self._socket.getsockname()[1]
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()

    def _accept_loop(self):
        while not self._stop.is_set():
            try:
                connection, _address = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self._lock:
                self._connections.append(connection)
            threading.Thread(target=self._handle, args=(connection,), daemon=True).start()

    def _handle(self, connection):  # pragma: no cover - overridden
        pass

    def close(self):
        self._stop.set()
        self._socket.close()
        with self._lock:
            connections, self._connections = self._connections, []
        for connection in connections:
            try:
                connection.close()
            except OSError:
                pass
        self._thread.join(timeout=2)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class TlsServer(_Listener):
    """Answers TLS handshakes with the given certificate; records the SNI name of each handshake
    (None when the client sent none) in sni_names."""

    def __init__(self, cert, key):
        self.sni_names = []
        self._context = self._make_context(cert, key)
        super().__init__()

    def _make_context(self, cert, key):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        context.sni_callback = self._on_sni
        return context

    def _on_sni(self, _ssl_object, server_name, _context):
        with self._lock:
            self.sni_names.append(server_name)
        return None

    def swap_cert(self, cert, key):
        """Present another certificate from the next handshake on."""
        context = self._make_context(cert, key)
        with self._lock:
            self._context = context

    def _handle(self, connection):
        with self._lock:
            context = self._context
        connection.settimeout(5)
        try:
            with context.wrap_socket(connection, server_side=True) as tls:
                while tls.recv(1024):
                    pass
        except (OSError, ssl.SSLError):
            pass
        finally:
            try:
                connection.close()
            except OSError:
                pass


class BlackHole(_Listener):
    """Accepts TCP connections and never sends a byte (the client runs into its timeout)."""

    def _handle(self, connection):
        self._stop.wait()


class EchoServer(_Listener):
    """Plain TCP: echoes what it receives; a TLS client sees garbage instead of a ServerHello."""

    def _handle(self, connection):
        connection.settimeout(5)
        try:
            while True:
                data = connection.recv(4096)
                if not data:
                    return
                connection.sendall(data)
        except OSError:
            pass
        finally:
            try:
                connection.close()
            except OSError:
                pass


def closed_port():
    """A TCP port on 127.0.0.1 with nothing listening (connecting is refused)."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.bind((LOOPBACK, 0))
    port = probe.getsockname()[1]
    probe.close()
    return port
