#!/usr/bin/env python3
"""Generate the TV launcher banner and the square launcher icon.

Reproducible: run from the repo root with Pillow >= 10 built with raqm
(libraqm + fribidi), which shapes Persian (joined letters, right-to-left):

    python3 -m venv /tmp/bannervenv && /tmp/bannervenv/bin/pip install pillow
    /tmp/bannervenv/bin/python scripts/make_banner.py

Outputs:
    app/src/main/res/drawable-xhdpi/banner.png     640x360 px (320x180 dp)
    app/src/main/res/mipmap-xhdpi/ic_launcher.png   96x96 px (48 dp)
    app/src/main/res/mipmap-xxxhdpi/ic_launcher.png 192x192 px (48 dp)
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
FONT = RES / "font/vazirmatn_extrabold.ttf"

BG = (0x0B, 0x0B, 0x0F)
RED = (0xE5, 0x09, 0x14)
WHITE = (0xF5, 0xF5, 0xF7)
WORDMARK = "دی‌ال‌روز"  # «دی‌ال‌روز» with zero-width non-joiners

if not features.check("raqm"):
    raise SystemExit("Pillow lacks raqm: Persian would render unshaped. Install a Pillow wheel with raqm.")


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT), size, layout_engine=ImageFont.Layout.RAQM)


def fit_font(text: str, max_w: int, max_h: int, start: int) -> ImageFont.FreeTypeFont:
    size = start
    while size > 8:
        f = font(size)
        l, t, r, b = f.getbbox(text, direction="rtl", language="fa")
        if r - l <= max_w and b - t <= max_h:
            return f
        size -= 2
    raise SystemExit("text does not fit")


def draw_centered(d: ImageDraw.ImageDraw, text: str, f, cx: float, cy: float) -> tuple:
    l, t, r, b = f.getbbox(text, direction="rtl", language="fa")
    x = cx - (l + r) / 2
    y = cy - (t + b) / 2
    d.text((x, y), text, font=f, fill=WHITE, direction="rtl", language="fa")
    return (x + l, y + t, x + r, y + b)


def banner() -> None:
    w, h = 640, 360
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    # Wordmark inside the safe centre (~74% width, ~45% height), nudged up to leave room for the bar.
    f = fit_font(WORDMARK, int(w * 0.74), int(h * 0.45), 200)
    _, _, _, bottom = draw_centered(d, WORDMARK, f, w / 2, h / 2 - 18)
    # Red accent bar under the wordmark, anchored to the right (RTL reading start).
    bar_w, bar_h = 168, 14
    by = min(bottom + 26, h - 60)
    d.rounded_rectangle((w / 2 - bar_w / 2, by, w / 2 + bar_w / 2, by + bar_h), radius=bar_h / 2, fill=RED)
    out = RES / "drawable-xhdpi/banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, optimize=True)
    print("wrote", out.relative_to(ROOT))


def icon(px: int, folder: str) -> None:
    scale = 4  # supersample for smooth rounded corners
    s = px * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=s * 0.22, fill=BG)
    glyph = "د"
    f = fit_font(glyph, int(s * 0.56), int(s * 0.5), int(s * 0.9))
    draw_centered(d, glyph, f, s / 2, s * 0.44)
    bw, bh = s * 0.44, s * 0.075
    by = s * 0.76
    d.rounded_rectangle((s / 2 - bw / 2, by, s / 2 + bw / 2, by + bh), radius=bh / 2, fill=RED)
    img = img.resize((px, px), Image.LANCZOS)
    out = RES / folder / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, optimize=True)
    print("wrote", out.relative_to(ROOT))


if __name__ == "__main__":
    banner()
    icon(96, "mipmap-xhdpi")
    icon(192, "mipmap-xxxhdpi")
