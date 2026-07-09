#!/usr/bin/env python3
"""
Ship-template match tester.

When boarding for a mission the bot resolves ONE required ship (e.g. ship_worm)
and then locates it in the hangar with matcher.find(), gated by a single fixed
threshold (TemplateMatcher default 0.83). If the required ship scores below that
bar it does NOT board a "close enough" ship -- it quits the mission and requests
a new one (see repair_ship_flow / quit_mission_and_request_new). The one real
danger is a DIFFERENT ship scoring above 0.83, because several of ours share the
Daredevil hull (worm/sansha/angel/sk) and their icons look alike.

This tool shows, for the hangar on screen RIGHT NOW, how strongly EVERY ship
template matches -- so you can see the correct ship win and check that no other
ship also clears the bar (which would risk boarding the wrong hull).

USAGE
    # Open the ship hangar in EVE (with whatever ships you have), then:
    python ocr/test_ship_templates.py

    # Test a different bar than the live 0.83:
    python ocr/test_ship_templates.py --threshold 0.90

    # Score saved screenshots instead of the live screen:
    python ocr/test_ship_templates.py --dir path/to/hangar_shots
"""
import argparse
import sys
from pathlib import Path

import cv2

# Reuse the real matcher + mappings so this stays in sync with production.
from eve_automation import (
    TemplateMatcher,
    MISSION_TEMPLATE_TO_SHIP,
    load_ship_mission_map,
    TEMPLATES_DIR,
)

IMG_EXTS = (".png", ".jpg", ".jpeg", ".bmp")


def ship_keys() -> list:
    """Every ship template the bot may board, from both resolvers, deduped and
    in a stable order."""
    keys = []
    for _dialog, ship_key, _kw in MISSION_TEMPLATE_TO_SHIP:
        if ship_key not in keys:
            keys.append(ship_key)
    for entry in load_ship_mission_map().get("ship_missions", []):
        k = entry.get("ship_template")
        if k and k not in keys:
            keys.append(k)
    return keys


def conf_at(matcher, key, screen):
    """Best match plus the best match on a DIFFERENT icon.

    Returns (best_conf, best_center, other_conf, other_center):
      best_*  -- the template's own ship (highest peak).
      other_* -- the highest peak elsewhere on screen, i.e. how well this
                 template matches the WRONG hull. This is the score that decides
                 the 'ran out of ships' case: if the real ship is gone, the bot
                 sees only this, and boards the wrong ship if it clears the bar.
    Confidences are 0.0 / centers None when the template is missing/too big."""
    template = matcher.templates.get(key)
    if template is None:
        return 0.0, None, 0.0, None
    th, tw = template.shape[:2]
    if screen.shape[0] < th or screen.shape[1] < tw:
        return 0.0, None, 0.0, None
    result = cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED)
    _, best, _, best_loc = cv2.minMaxLoc(result)
    bx, by = best_loc
    center = (bx + tw // 2, by + th // 2)
    # Suppress the winning icon's whole footprint (1.5x the template each side so
    # the shoulder of the self-peak can't leak into an adjacent icon), then re-scan
    # for the best remaining peak -- that peak sits on a genuinely different ship.
    sy, sx = int(th * 1.5), int(tw * 1.5)
    y0, y1 = max(0, by - sy), min(result.shape[0], by + sy)
    x0, x1 = max(0, bx - sx), min(result.shape[1], bx + sx)
    result[y0:y1, x0:x1] = -1.0
    _, other, _, other_loc = cv2.minMaxLoc(result)
    other_center = (other_loc[0] + tw // 2, other_loc[1] + th // 2)
    return float(best), center, float(other), other_center


def report(name, screen, keys, matcher, threshold):
    rows = []
    for k in keys:
        best, bloc, other, oloc = conf_at(matcher, k, screen)
        rows.append((k, best, bloc, other, oloc))
    rows.sort(key=lambda r: r[1], reverse=True)

    print(f"\n=== {name}  (bar = {threshold:.2f}) ===")
    print(f"  {'':4} {'own':>5} {'wrong':>6}  {'template':<14} own-icon")
    risky = []  # ships whose template matches a WRONG hull above the bar
    for k, best, bloc, other, oloc in rows:
        bar = "#" * int(round(best * 30))
        flag = "PASS" if best >= threshold else "MISS"
        where = f"({bloc[0]},{bloc[1]})" if bloc else "(missing/too big)"
        warn = "  <-- WRONG-HULL RISK" if other >= threshold else ""
        if other >= threshold:
            risky.append((k, other, oloc))
        print(f"  {flag} {best:5.3f} {other:6.3f}  {k:<14} {where}"
              f"  {bar}{warn}")

    print("\n  own   = match on this ship's own icon (should be high when present)")
    print("  wrong = best match on a DIFFERENT icon = score if this ship is GONE")
    if not risky:
        margin = min((threshold - r[3]) for r in rows if r[3] is not None) \
            if rows else 0.0
        print(f"  -> No template clears {threshold:.2f} on a wrong hull "
              f"(closest wrong-match is {threshold - margin:.3f}). When a ship runs "
              f"out its template falls below the bar and the bot STOPS instead of "
              f"boarding a look-alike. Safe. Headroom: {margin:.3f}.")
    else:
        for k, other, oloc in risky:
            at = f"({oloc[0]},{oloc[1]})" if oloc else "?"
            print(f"  -> RISK: '{k}' scores {other:.3f} on the WRONG hull at {at}. "
                  f"If '{k}' runs out, the bot would board that ship instead of "
                  f"stopping. Raise the bar above {other:.3f} or recrop '{k}' with a "
                  f"distinctive detail (e.g. ship-name text).")
    return risky


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", help="score saved screenshots in this folder "
                                  "instead of capturing the live screen")
    ap.add_argument("--threshold", type=float, default=0.83,
                    help="pass/fail bar to evaluate (default 0.83, the live value)")
    args = ap.parse_args()

    matcher = TemplateMatcher(TEMPLATES_DIR)
    keys = ship_keys()
    missing = [k for k in keys if k not in matcher.templates]
    print(f"Ship templates: {', '.join(keys)}")
    if missing:
        print(f"NOT loaded (add {', '.join(m + '.png' for m in missing)} to templates/): "
              f"{', '.join(missing)}")

    if args.dir:
        d = Path(args.dir)
        files = sorted(p for p in d.iterdir() if p.suffix.lower() in IMG_EXTS) \
            if d.exists() else []
        if not files:
            print(f"No images in {d}.")
            return 0
        for p in files:
            img = cv2.imread(str(p), cv2.IMREAD_COLOR)
            if img is None:
                print(f"\n{p.name}: unreadable, skipping.")
                continue
            report(p.name, img, keys, matcher, args.threshold)
    else:
        img = matcher.screenshot_cv()
        report("LIVE screen", img, keys, matcher, args.threshold)

    return 0


if __name__ == "__main__":
    sys.exit(main())
