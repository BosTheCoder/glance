# /// script
# dependencies = ["pillow"]
# ///
"""Turn raw window captures (tools/screenshot.ps1) into the README images in docs/images/.
usage: uv run tools/compose.py <dir-with-shot-*.png>"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

src = Path(sys.argv[1])
out = Path(__file__).parent.parent / "docs" / "images"

def tile(name):
    im = Image.open(src / f"shot-{name}.png").convert("RGBA")
    im = im.crop((2, 2, im.width - 2, im.height - 3))   # the 1-2 px window edge picks up whatever is behind
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius=8, fill=255)
    im.putalpha(mask)
    return im

def board(names, pad=48, gap=32):
    tiles = [tile(n) for n in names]
    w = sum(t.width for t in tiles) + gap * (len(tiles) - 1) + 2 * pad
    h = max(t.height for t in tiles) + 2 * pad
    bg = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(bg)
    for y in range(h):   # midnight -> violet, same palette as the icon
        f = y / h
        d.line((0, y, w, y), fill=(int(10 + 60 * f), int(14 + 10 * f), int(48 + 80 * f), 255))
    x = pad
    for t in tiles:
        shadow = Image.new("RGBA", (t.width + 40, t.height + 40), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((20, 26, t.width + 20, t.height + 26), radius=10, fill=(0, 0, 0, 120))
        bg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(12)), (x - 20, pad - 20))
        bg.alpha_composite(t, (x, pad))
        x += t.width + gap
    return bg

# Each board is rebuilt only if all its captures are present, so you can refresh one image at a time.
boards = {
    "hero": ["collapsed", "expanded"],
    "themes": ["midnight", "ocean", "plum", "light"],
    "alerts": ["alerts"],   # a fresh --demo launch shows all three alert states at once
}
for name, shots in boards.items():
    if all((src / f"shot-{n}.png").exists() for n in shots):
        board(shots).save(out / f"{name}.png")
print(*sorted(out.iterdir()), sep="\n")
