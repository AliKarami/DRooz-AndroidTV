#!/usr/bin/env python3
"""Replace personal identifiers in a captured site page with fixed placeholders.

Usage: DRTV_TOKEN=... DRTV_UID=... DRTV_USER=... DRTV_PASS=... sanitize_fixture.py IN OUT
Exits 1 if any real value survives sanitization.
"""
import os
import re
import sys

RULES = [
    (r"(/json/m3u8/\d+/\d+/)[0-9a-f]{30,}(/master\.m3u8\?key=)[0-9a-f]+(&expire=)\d+", r"\1USERTOKEN\2KEY\g<3>1800000000"),
    (r"(/dln/)[0-9a-f]{30,}/", r"\1USERTOKEN/"),
    (r"(name='recaptchaPass' value=')[0-9a-f]{32}\d{10}", r"\g<1>000000000000000000000000000000001800000000"),
    (r"09\d\d\*\*\*\d{4}", "0912***0000"),
    (r"\d{4}\*\*\*09\d\d", "0000***0912"),
    (r"halt-(video|audio)-([0-9a-f]+)-\d+-", r"halt-\1-\2-100001-"),
    (r"(avatar/)\d+_\d+", r"\g<1>100001_0"),
    (r'(name="password" value=")[^"]+"', r'\g<1>secret"'),
    (r"(/play/)[0-9a-f]{64}\d{10}/", r"\g<1>" + "0" * 64 + "1800000000/"),
]


def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    secrets = {k: os.environ[k] for k in ("DRTV_TOKEN", "DRTV_UID", "DRTV_USER", "DRTV_PASS")}
    with open(src, encoding="utf-8") as f:
        text = f.read()
    for pattern, repl in RULES:
        text = re.sub(pattern, repl, text)
    text = text.replace(secrets["DRTV_TOKEN"], "USERTOKEN")
    text = text.replace(secrets["DRTV_USER"], "testuser")
    text = re.sub(r"(?<!\d)" + re.escape(secrets["DRTV_UID"]) + r"(?!\d)", "100001", text)
    leaks = [k for k, v in secrets.items() if k != "DRTV_PASS" and v in text]
    if re.search(r'value="' + re.escape(secrets["DRTV_PASS"]) + '"', text):
        leaks.append("DRTV_PASS")
    if leaks:
        print(f"{src}: still contains {leaks}", file=sys.stderr)
        return 1
    with open(dst, "w", encoding="utf-8") as f:
        f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
