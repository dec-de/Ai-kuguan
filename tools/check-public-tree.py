"""Fail if publishable Git files contain likely credentials or private hosts.

Only reports paths and line numbers; it never prints a suspected secret.
Run before publishing. This is a guard, not a substitute for manual review.
"""
from __future__ import annotations

import ipaddress
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if (ROOT / ".git").exists():
    NAMES = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).split(b"\0")
else:
    generated = {".gradle", ".kotlin", ".idea", "build", "__pycache__", ".pytest_cache"}
    NAMES = [
        str(path.relative_to(ROOT)).encode("utf-8")
        for path in ROOT.rglob("*")
        if path.is_file() and not (set(path.relative_to(ROOT).parts) & generated) and path.suffix != ".pyc"
    ]
PATTERNS = {
    "private domain": re.compile(rb"(?i)\b[a-z0-9.-]+\.(?:xyz)\b"),
    "personal path": re.compile(rb"(?i)(?:[a-z]:[/\\]users[/\\][^/\\\s]+|[/\\]users[/\\][^/\\\s]+)"),
    "credential": re.compile(rb"(?i)\b(?:AKID[A-Za-z0-9]{10,}|sk-[A-Za-z0-9._-]{20,}|Bearer\s+[A-Za-z0-9_-]{28,})\b"),
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "URL credentials": re.compile(rb"(?i)https?://[^\s/'\"]+:[^\s@/'\"]+@([a-z0-9.-]+)"),
}
IPV4 = re.compile(rb"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
SKIP_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".jar", ".zip", ".class"}
issues: list[tuple[str, int, str]] = []
binary_files: list[str] = []

for raw_name in NAMES:
    if not raw_name:
        continue
    name = raw_name.decode("utf-8", errors="surrogateescape")
    path = ROOT / name
    if not path.is_file():
        continue
    if path.suffix.lower() in SKIP_EXTENSIONS:
        binary_files.append(name)
        continue
    data = path.read_bytes()
    if b"\0" in data:
        binary_files.append(name)
        continue
    for number, line in enumerate(data.splitlines(), 1):
        for label, pattern in PATTERNS.items():
            found = pattern.search(line)
            if found and label == "URL credentials" and found.group(1).lower() in {b"example.com", b"example.org", b"example.net"}:
                continue
            if found:
                issues.append((name, number, label))
        for match in IPV4.finditer(line):
            try:
                address = ipaddress.ip_address(match.group().decode("ascii"))
            except ValueError:
                continue
            if not (address.is_private or address.is_loopback or address.is_link_local or address.is_multicast or address.is_reserved):
                issues.append((name, number, "public IPv4 address"))

if issues:
    for path, line, label in issues:
        print(f"{path}:{line}: {label}")
    raise SystemExit(f"public tree check failed: {len(issues)} finding(s)")
print(f"public tree check passed: {len(NAMES) - 1} candidate files; {len(binary_files)} binary files require separate review")
