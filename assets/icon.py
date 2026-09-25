# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow", "httpx"]
# ///
"""Icon pipeline. `gen N` asks an image model (OpenRouter) for N full-bleed square artworks;
`ico FILE` masks one to a rounded tile and writes glance.ico + glance.png."""
import base64, os, sys, concurrent.futures as cf
from pathlib import Path
import httpx
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
PROMPT = (
    "Square app icon artwork for 'Glance', a small floating calendar widget that shows what you "
    "should be doing right now. Full-bleed square, artwork fills the entire canvas edge to edge, "
    "NO rounded corners, NO border, NO text, NO letters. Deep midnight-blue to violet gradient "
    "background. Centered: a frosted-glass rounded card (glassmorphism, soft blur, subtle white rim "
    "light) holding a single bold glowing horizontal progress bar in cyan-to-mint, with a small "
    "bright dot marking 'now'. Minimal, modern Windows 11 Fluent style, soft depth, high contrast, "
    "readable when scaled down to 32 pixels."
)

def gen(n: int, model: str = "google/gemini-3-pro-image-preview"):
    def one(i):
        r = httpx.post("https://openrouter.ai/api/v1/chat/completions", timeout=180,
                       headers={"Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}"},
                       json={"model": model, "modalities": ["image", "text"],
                             "messages": [{"role": "user", "content": PROMPT}]})
        r.raise_for_status()
        url = r.json()["choices"][0]["message"]["images"][0]["image_url"]["url"]
        out = HERE / f"candidate-{i}.png"
        out.write_bytes(base64.b64decode(url.split(",", 1)[1]))
        return out
    with cf.ThreadPoolExecutor(n) as ex:
        for p in ex.map(one, range(1, n + 1)):
            print(p)

def ico(src: str):
    im = Image.open(src).convert("RGBA")
    side = min(im.size)
    im = im.crop(((im.width - side) // 2, (im.height - side) // 2,
                  (im.width + side) // 2, (im.height + side) // 2)).resize((1024, 1024), Image.LANCZOS)
    def tile(img):
        mask = Image.new("L", img.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=img.width * 0.22, fill=255)
        img = img.copy()
        img.putalpha(mask)
        return img
    big = tile(im)
    # Taskbar sizes: zoom into the glass card so the bar survives at 16-48 px.
    small = tile(im.crop((200, 200, 824, 824)).resize((1024, 1024), Image.LANCZOS))
    big.resize((256, 256), Image.LANCZOS).save(HERE / "glance.png")
    frames = [(small if s <= 48 else big).resize((s, s), Image.LANCZOS) for s in (16, 24, 32, 48, 64, 128, 256)]
    frames[-1].save(HERE / "glance.ico", format="ICO", sizes=[f.size for f in frames], append_images=frames[:-1])
    print(HERE / "glance.ico")

if __name__ == "__main__":
    {"gen": lambda a: gen(int(a)), "ico": ico}[sys.argv[1]](sys.argv[2])
