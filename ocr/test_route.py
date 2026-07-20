"""
Live route-panel probe. Run it next to the bot (or on its own) while you undock and
watch what the route OCR actually sees, frame by frame:

    python ocr/test_route.py            # poll forever, every 2s
    python ocr/test_route.py --interval 1 --count 30
    python ocr/test_route.py --save      # also dump each capture to ocr/debug/

Each tick prints the parsed verdict (the exact thing clickSetDestinationByTemplate
gates on) plus the raw OCR text, so a "no route" can be told apart from a misread.
"""

import argparse
import time
from datetime import datetime
from pathlib import Path

from eve_automation import EVEMissionReader, layout_rect, read_route_panel

DEBUG_DIR = Path(__file__).parent / "debug"


def main() -> None:
    ap = argparse.ArgumentParser(description="Poll the EVE route panel and log what it reads.")
    ap.add_argument("--interval", type=float, default=2.0, help="seconds between reads (default 2)")
    ap.add_argument("--count", type=int, default=0, help="number of reads, 0 = until Ctrl+C")
    ap.add_argument("--save", action="store_true", help="save each capture to ocr/debug/")
    args = ap.parse_args()

    x, y, w, h = layout_rect("route_panel", 92, 340, 353, 183)
    print("route_panel region: x=%d y=%d w=%d h=%d" % (x, y, w, h))
    if args.save:
        DEBUG_DIR.mkdir(parents=True, exist_ok=True)

    reader = EVEMissionReader()
    reads = 0
    while args.count == 0 or reads < args.count:
        reads += 1
        stamp = datetime.now().strftime("%H:%M:%S")
        img = reader.capture_region(x, y, w, h)
        if img is None:
            print("[%s] #%d capture FAILED — no image from the region." % (stamp, reads))
        else:
            r = read_route_panel(img)
            verdict = "ROUTE SET" if r["route_set"] else "NO ROUTE"
            print("[%s] #%d %s | jumps=%s | destination=%s"
                  % (stamp, reads, verdict, r["jumps"], r["destination"]))
            print("        raw: %s" % " | ".join(
                ln.strip() for ln in (r.get("raw") or "").splitlines() if ln.strip()))
            if args.save:
                path = DEBUG_DIR / ("route_%s_%03d.png" % (datetime.now().strftime("%H%M%S"), reads))
                img.save(path)
                print("        saved: %s" % path)
        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nstopped.")
