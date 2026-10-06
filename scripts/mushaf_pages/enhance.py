"""Restoration of heavily JPEG-compressed mushaf page scans (Pillow only).

Steps
  1. de-artifact: shift / re-quantize / average (cancels 8x8 block ringing and blocking)
  2. chroma clean-up: colour speckle on neutral (black / grey / white) pixels -> neutral
  3. tint smoothing: median on flat grey / pink areas that have no black ink nearby
  4. upscale with a smooth resampler
  5. levels: dark-grey ink -> black, speckled near-white paper -> pure white
  6. unsharp mask for crisp stroke edges
"""
import io

from PIL import Image, ImageFilter

DEFAULTS = dict(
    shifts=4,          # NxN grid of block shifts for the de-artifact pass (0 = off)
    chroma_dead=6,     # |Cb/Cr - 128| below this is treated as neutral (0 = off)
    chroma_blur=0.8,
    tint_median=5,     # median size for flat tinted areas away from black ink (0 = off)
    tint_guard=5,      # neighbourhood (px) that must be free of black ink
    scale=1.5,         # upscale factor (220 dpi scan -> 330 dpi)
    black=72,          # input level mapped to pure black
    white=236,         # input level mapped to pure white
    gamma=1.0,
    usm_radius=1.5,
    usm_percent=70,
    usm_threshold=2,
)


def _average(images):
    """Pairwise 8-bit average (list length must be a power of two)."""
    while len(images) > 1:
        images = [Image.blend(images[i], images[i + 1], 0.5) for i in range(0, len(images), 2)]
    return images[0]


def deartifact(im, qtables, n):
    """Re-application of JPEG on shifted copies (Nosratinia): each shifted copy is
    re-quantized with the original tables, shifted back, and all are averaged.  Ringing
    and blocking depend on the 8x8 grid position so they average out; real edges stay."""
    if not n:
        return im
    w, h = im.size
    step = 8 // n
    offs = [(dx, dy) for dx in range(0, 8, step) for dy in range(0, 8, step)]
    pad = Image.new("RGB", (w + 16, h + 16), (255, 255, 255))
    pad.paste(im, (8, 8))
    outs = []
    for dx, dy in offs:
        if dx == 0 and dy == 0:
            outs.append(im)
            continue
        crop = pad.crop((8 - dx, 8 - dy, 8 - dx + w + 8, 8 - dy + h + 8))
        buf = io.BytesIO()
        crop.save(buf, "JPEG", qtables=qtables, subsampling=2)
        rec = Image.open(io.BytesIO(buf.getvalue())).convert("RGB")
        outs.append(rec.crop((dx, dy, dx + w, dy + h)))
    return _average(outs)


def clean_chroma(im, dead, blur):
    """Neutralise weak colour casts (JPEG chroma noise) while keeping real colours."""
    if not dead:
        return im
    y, cb, cr = im.convert("YCbCr").split()
    lut = []
    for v in range(256):
        d = v - 128
        a = abs(d)
        if a <= dead:
            k = 0.0
        elif a >= 2.5 * dead:
            k = 1.0
        else:
            k = (a - dead) / (1.5 * dead)
        lut.append(int(round(128 + d * k)))
    if blur:
        cb = cb.filter(ImageFilter.GaussianBlur(blur))
        cr = cr.filter(ImageFilter.GaussianBlur(blur))
    return Image.merge("YCbCr", (y, cb.point(lut), cr.point(lut))).convert("RGB")


def smooth_tints(im, size, guard, black):
    """Median-smooth flat tinted areas (grey letters, pink fills) only where there is no
    black ink in the neighbourhood, so black strokes and diacritics are never touched."""
    if not size:
        return im
    lum = im.convert("L")
    darkest = lum.filter(ImageFilter.MinFilter(2 * guard + 1))
    lo, hi = black + 10, black + 40
    mask = darkest.point([0 if v <= lo else 255 if v >= hi else int(255 * (v - lo) / (hi - lo)) for v in range(256)])
    mask = mask.filter(ImageFilter.GaussianBlur(1.0))
    return Image.composite(im.filter(ImageFilter.MedianFilter(size)), im, mask)


def levels_lut(black, white, gamma):
    lut = []
    span = float(white - black)
    for v in range(256):
        t = min(1.0, max(0.0, (v - black) / span))
        lut.append(int(round(255.0 * (t ** gamma))))
    return lut * 3


def enhance(im, qtables=None, **kw):
    p = dict(DEFAULTS)
    p.update(kw)
    im = im.convert("RGB")
    if qtables is not None:
        im = deartifact(im, qtables, p["shifts"])
    im = clean_chroma(im, p["chroma_dead"], p["chroma_blur"])
    im = smooth_tints(im, p["tint_median"], p["tint_guard"], p["black"])
    if p["scale"] != 1:
        im = im.resize((round(im.width * p["scale"]), round(im.height * p["scale"])), Image.BICUBIC)
    im = im.point(levels_lut(p["black"], p["white"], p["gamma"]))
    if p["usm_percent"]:
        im = im.filter(ImageFilter.UnsharpMask(p["usm_radius"], p["usm_percent"], p["usm_threshold"]))
    return im
