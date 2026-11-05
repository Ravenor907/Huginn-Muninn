#!/usr/bin/env python3
"""Draws code/shared/docs/banner.png: Huginn's gold raven and Muninn's silver one facing each other.
Needs the fonts from code/shared/tools/get-fonts.sh (CI fetches them). Usage: make-banner.py OUT.png"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

W, H = 1600, 600
BG = (14, 15, 17)
GOLD_HI, GOLD_LO = (236, 204, 132), (184, 146, 72)
SILV_HI, SILV_LO = (240, 243, 247), (176, 183, 192)
# Paths are from the code/ folder, wherever this is run from.
CODE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")

# Background: dark, with a soft warm-to-cool glow behind the words so they stand out.
c = Image.new("RGB", (W, H), BG)
glow = Image.new("RGB", (W, H), BG)
gd = ImageDraw.Draw(glow)
gd.ellipse([W / 2 - 520, 40, W / 2 + 520, H - 40], fill=(34, 33, 31))
c = Image.blend(c, glow.filter(ImageFilter.GaussianBlur(120)), 1.0)

gold = Image.open(os.path.join(CODE, "huginn/qml/assets/raven-gold.png")).convert("RGBA").resize((330, 330), Image.LANCZOS)
silver = ImageOps.mirror(Image.open(os.path.join(CODE, "shared/site/raven.png")).convert("RGBA").resize((330, 330), Image.LANCZOS))  # faces left, toward the middle
c.paste(gold, (110, 125), gold)
c.paste(silver, (W - 110 - 330, 125), silver)

def font(name, size, weight=None):
    f = ImageFont.truetype(os.path.join(CODE, "huginn/fonts", name), size)
    if weight:
        try:
            f.set_variation_by_axes([weight])
        except Exception:
            pass
    return f

title = font("Cinzel.ttf", 104, 800)
amp = font("Cinzel.ttf", 64, 600)
runes = font("NotoSansRunic-Regular.ttf", 30)
tag = font("Barlow-SemiBold.ttf", 40)
sub = font("Barlow-Medium.ttf", 30)

def width(text, f, spacing):
    return sum(ImageDraw.Draw(c).textlength(ch, font=f) for ch in text) + spacing * (len(text) - 1)

def draw(y, text, f, top, bottom, spacing=0, shadow=True):
    """Centered text with a top-to-bottom color fade and a soft drop shadow for contrast."""
    w = width(text, f, spacing)
    x0 = (W - w) / 2
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    x = x0
    for ch in text:
        md.text((x, y), ch, font=f, fill=255)
        x += md.textlength(ch, font=f) + spacing
    if shadow:
        sh = mask.filter(ImageFilter.GaussianBlur(6)).point(lambda v: int(v * 0.8))
        c.paste(Image.new("RGB", (W, H), (0, 0, 0)), (0, 4), sh)
    box = mask.getbbox() or (0, 0, W, H)
    fade = Image.new("RGB", (W, H))
    fd = ImageDraw.Draw(fade)
    span = max(1, box[3] - box[1])
    for row in range(H):
        t = min(1, max(0, (row - box[1]) / span))
        fd.line([(0, row), (W, row)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    c.paste(fade, (0, 0), mask)

draw(92, "HUGINN", title, GOLD_HI, GOLD_LO, 8)
draw(214, "&", amp, (210, 205, 195), (160, 155, 145), 0)
draw(276, "MUNINN", title, SILV_HI, SILV_LO, 8)

d = ImageDraw.Draw(c)
d.line([(560, 418), (1040, 418)], fill=(70, 66, 58), width=2)
draw(430, "ᚺᚢᚷᛁᚾᚾ · ᛗᚢᚾᛁᚾᚾ", runes, (150, 144, 132), (150, 144, 132), 8, shadow=False)
draw(482, "Private messages, calls and file sharing", tag, (238, 234, 226), (214, 208, 196))
draw(534, "Windows, Linux, Android and Google TV", sub, (186, 180, 168), (186, 180, 168))
c.save(sys.argv[1] if len(sys.argv) > 1 else os.path.join(CODE, "shared/docs/banner.png"), optimize=True)
