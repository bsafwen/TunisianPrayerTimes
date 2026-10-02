"""Build the mushaf page set for the Android app from the scanned Qaloun PDF.

Each PDF page holds one heavily JPEG-compressed scan.  Every scan is restored (see
enhance.py) and written as one lossy WebP at the scan's native resolution, plus a
pages.json manifest.  Requires Pillow and PyMuPDF.

    python build_pages.py Quran_Qaloun.pdf out_dir
    python build_pages.py Quran_Qaloun.pdf out_dir --scale 1.5 --quality 40
"""
import argparse
import io
import json
import os
import time
from concurrent.futures import ProcessPoolExecutor

import pymupdf
from PIL import Image

from enhance import enhance

_doc = None


def name(i):
    return "page_%03d.webp" % (i + 1)


def process(job):
    global _doc
    i, src_pdf, dst, scale, quality = job
    path = os.path.join(dst, name(i))
    if not os.path.exists(path):
        if _doc is None:
            _doc = pymupdf.open(src_pdf)
        xref = _doc[i].get_images(full=True)[0][0]
        src = Image.open(io.BytesIO(_doc.extract_image(xref)["image"]))
        # restore at 1.5x, then resample: edges come out smoother than restoring at 1x directly
        page = enhance(src, src.quantization)
        size = (round(src.width * scale), round(src.height * scale))
        if size != page.size:
            page = page.resize(size, Image.LANCZOS)
        tmp = path + ".tmp"
        page.save(tmp, "WEBP", quality=quality, method=6)
        os.replace(tmp, path)
    with Image.open(path) as im:
        return i, im.width, im.height, os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pdf")
    ap.add_argument("out_dir")
    ap.add_argument("--scale", type=float, default=1.0, help="output size relative to the scan (default 1.0)")
    ap.add_argument("--quality", type=int, default=40, help="WebP quality (default 40)")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    doc = pymupdf.open(args.pdf)
    total = doc.page_count
    box = [round(doc[0].rect.width), round(doc[0].rect.height)]
    jobs = [(i, args.pdf, args.out_dir, args.scale, args.quality) for i in range(total)]
    t = time.time()
    rows = []
    with ProcessPoolExecutor(args.workers) as ex:
        for row in ex.map(process, jobs):
            rows.append(row)
            if len(rows) % 50 == 0 or len(rows) == total:
                print("%d/%d pages  %.0fs  %.1f MB" % (len(rows), total, time.time() - t, sum(r[3] for r in rows) / 2 ** 20), flush=True)
    manifest = dict(
        source=os.path.basename(args.pdf),
        format="webp",
        count=total,
        # every PDF page has this size (pt) with the scan scaled to the full height and centred
        # horizontally: fit-height + centre in a box of this aspect reproduces the PDF layout
        pageBox=box,
        pages=[dict(n=i + 1, file=name(i), width=w, height=h, bytes=n) for i, w, h, n in rows],
    )
    with open(os.path.join(args.out_dir, "pages.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)


if __name__ == "__main__":
    main()
