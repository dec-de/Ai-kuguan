"""Verify built APKs do not contain local or environment voice service credentials.

Only prints artifact names and finding categories. Never prints credential values.
"""
from __future__ import annotations

import os
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROPERTY_NAMES = ("DASHSCOPE_API_KEY", "DEEPSEEK_API_KEY", "key")
ENV_NAMES = ("DASHSCOPE_API_KEY", "DEEPSEEK_API_KEY", "LLM_FALLBACK_API_KEY")
FORBIDDEN_FIELDS = (b"DASHSCOPE_API_KEY", b"LLM_API_KEY", b"LLM_FALLBACK_API_KEY")


def local_credentials() -> set[bytes]:
    values = {os.environ[name].encode("utf-8") for name in ENV_NAMES if os.environ.get(name)}
    local_file = ROOT / "secrets.properties"
    if local_file.exists():
        for line in local_file.read_text(encoding="utf-8", errors="replace").splitlines():
            name, separator, value = line.partition("=")
            if separator and name.strip() in PROPERTY_NAMES and value.strip():
                values.add(value.strip().encode("utf-8"))
    return {value for value in values if len(value) >= 6}


def check(path: Path, credentials: set[bytes]) -> list[str]:
    findings = []
    if not path.is_file():
        return ["artifact missing"]
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            data = archive.read(name)
            if any(value in data for value in credentials):
                findings.append(f"credential in {name}")
            if name.endswith(".dex") and any(field in data for field in FORBIDDEN_FIELDS):
                findings.append(f"legacy BuildConfig credential field in {name}")
    return findings


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: check-app-credentials.py APK [APK ...]", file=sys.stderr)
        return 2
    credentials = local_credentials()
    failed = False
    for argument in argv:
        path = Path(argument)
        findings = check(path, credentials)
        if findings:
            failed = True
            for finding in findings:
                print(f"{path}: {finding}")
        else:
            print(f"{path}: credential scan passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
