#!/usr/bin/env python3
r"""Builds the IMC-17 recording kit ZIP for the Windows machine (T026-T028, option A of IMC-21).

Usage: python3 .apex/work/IMC-17/build-kit.py <output.zip>

Contents (all from the repository, nothing local such as the neutralize map):
  ANLEITUNG.txt, record-v74.ps1                  (CRLF line ends for Notepad)
  tools/record-fixtures.ps1, tools/neutralize.py (as committed)
  (the results go to C:\imc17-results on the Windows machine, never into a repository; IMC-24)
"""
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
FILES = [
    (HERE / "ANLEITUNG.txt", "ANLEITUNG.txt", True),
    (HERE / "record-v74.ps1", "record-v74.ps1", True),
    (ROOT / "tools" / "record-fixtures.ps1", "tools/record-fixtures.ps1", False),
    (ROOT / "tools" / "neutralize.py", "tools/neutralize.py", False),
]


def crlf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    out = Path(sys.argv[1])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as kit:
        for source, name, to_crlf in FILES:
            data = source.read_bytes()
            kit.writestr("imc17-kit/" + name, crlf(data) if to_crlf else data)
    print(f"written: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
