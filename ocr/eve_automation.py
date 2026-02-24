"""
eve_automation.py — Template-matching EVE Online mission automation.

Standalone usage:
  python eve_automation.py --check                   verify templates, scan screen
  python eve_automation.py --dry-run                 full cycle, log only (no clicks)
  python eve_automation.py --loop                    continuous production loop
  python eve_automation.py --click accept            find + left-click template, exit
  python eve_automation.py --right-click ship_hangar find + right-click template, exit

Called from Java OCRBridge via:
  python eve_automation.py --click <key>             left-click action
  python eve_automation.py --right-click <key>       right-click action
"""

import sys
import os
import json
import time
import logging
import argparse
from pathlib import Path
from typing import Optional, Tuple, Dict

import cv2
import numpy as np
import pyautogui
from PIL import Image

# Disable pyautogui's corner-of-screen failsafe — it can silently cancel clicks
# when the cursor is near any screen edge (common with EVE buttons at screen bottom).
pyautogui.FAILSAFE = False
# Remove pyautogui's built-in 0.1s pause between actions — we control timing ourselves.
pyautogui.PAUSE = 0.0

# -- optional win32 -----------------------------------------------------------
try:
    import win32gui, win32con

    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False

# -- local imports ------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
from mission_reader import EVEMissionReader  # noqa: E402

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("eve_automation")

# -----------------------------------------------------------------------------
# Paths
# -----------------------------------------------------------------------------

TEMPLATES_DIR = SCRIPT_DIR.parent / "templates"
RULES_FILE = SCRIPT_DIR / "config" / "mission_rules.json"
COORDS_FILE = SCRIPT_DIR.parent / "mission_coordinates.json"

# -----------------------------------------------------------------------------
# Template registry  (key -> filename without extension)
# -----------------------------------------------------------------------------

TEMPLATE_FILES = {
    "request_mission": "request_mission",
    "accept": "accept",
    "decline": "decline",
    "track": "track",
    "close_active_window": "close_active_window",
    "complete_mission": "complete_mission",
    "get_repair_quote": "get_repair_quote",
    "repair": "repair",
    "repair_yes": "repair_yes",
    "undock": "undock",
    "ship_hangar": "ship_hangar",
}

# -----------------------------------------------------------------------------
# TemplateMatcher
# -----------------------------------------------------------------------------


class TemplateMatcher:
    """Loads PNG templates once and exposes find / click / right-click helpers."""

    def __init__(self, templates_dir: Path, threshold: float = 0.80):
        self.threshold = threshold
        self.templates: Dict[str, np.ndarray] = {}
        self.templates_dir = templates_dir
        self._load_all(templates_dir)

    # -- loading ---------------------------------------------------------------

    def _load_all(self, directory: Path):
        if not directory.exists():
            log.warning("Templates dir not found: %s", directory)
            return
        for key, fname in TEMPLATE_FILES.items():
            for ext in (".png", ".jpg", ".bmp"):
                path = directory / (fname + ext)
                if path.exists():
                    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
                    if img is not None:
                        self.templates[key] = img
                        log.debug("Loaded template '%s' (%s)", key, path.name)
                    break
            else:
                log.warning(
                    "Template NOT FOUND: '%s' (expected: %s[.png/.jpg/.bmp])",
                    key,
                    directory / fname,
                )

    def status(self) -> str:
        loaded = sorted(self.templates.keys())
        missing = sorted(k for k in TEMPLATE_FILES if k not in self.templates)
        lines = [
            f"Templates dir : {self.templates_dir}",
            f"Dir exists    : {self.templates_dir.exists()}",
            f"Loaded  ({len(loaded):2d}): {', '.join(loaded) or 'none'}",
            f"Missing ({len(missing):2d}): {', '.join(missing) or 'none'}",
        ]
        return "\n".join(lines)

    # -- screenshot ------------------------------------------------------------

    @staticmethod
    def screenshot_cv() -> np.ndarray:
        img = pyautogui.screenshot()
        return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)

    # -- find ------------------------------------------------------------------

    def find(
        self,
        key: str,
        screen: Optional[np.ndarray] = None,
        threshold: Optional[float] = None,
    ) -> Optional[Tuple[int, int, int, int]]:
        """Return (x, y, w, h) of best match or None. Scans the FULL screen."""
        template = self.templates.get(key)
        if template is None:
            log.warning("Template '%s' not loaded — skipping.", key)
            return None
        if screen is None:
            screen = self.screenshot_cv()
        th = threshold if threshold is not None else self.threshold
        result = cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, max_loc = cv2.minMaxLoc(result)
        if max_val >= th:
            h, w = template.shape[:2]
            x, y = max_loc
            log.debug("Found '%s' at (%d,%d) conf=%.3f", key, x, y, max_val)
            return (x, y, w, h)
        log.debug("'%s' not found (best=%.3f < %.3f)", key, max_val, th)
        return None

    def find_all_on_screen(self) -> Dict[str, Optional[Tuple[int, int, int, int]]]:
        screen = self.screenshot_cv()
        return {key: self.find(key, screen=screen) for key in self.templates}

    def center(self, match: Tuple[int, int, int, int]) -> Tuple[int, int]:
        x, y, w, h = match
        return (x + w // 2, y + h // 2)

    # -- wait_for --------------------------------------------------------------

    def wait_for(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        threshold: Optional[float] = None,
    ) -> Optional[Tuple[int, int, int, int]]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            match = self.find(key, threshold=threshold)
            if match:
                return match
            time.sleep(interval)
        log.debug("Timeout waiting for '%s'", key)
        return None

    # -- left-click ------------------------------------------------------------

    def _do_click(self, cx: int, cy: int, button: str = "left", delay: float = 0.3):
        """
        Low-level click using win32api when available (most reliable with EVE),
        falling back to pyautogui.

        win32api sends WM_MOUSEMOVE + WM_LBUTTONDOWN/UP directly to the OS
        input queue — this works even when EVE is not the foreground window,
        and bypasses pyautogui's failsafe check.
        """
        try:
            import win32api, win32con

            # Move cursor
            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            if button == "right":
                win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTDOWN, cx, cy, 0, 0)
                time.sleep(0.05)
                win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTUP, cx, cy, 0, 0)
            else:
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
                time.sleep(0.05)
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            log.info("win32 %s-clicked at (%d,%d)", button, cx, cy)
        except ImportError:
            # Fallback: pyautogui (requires window focus)
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            if button == "right":
                pyautogui.rightClick(cx, cy)
            else:
                pyautogui.click(cx, cy)
            log.info("pyautogui %s-clicked at (%d,%d)", button, cx, cy)

        time.sleep(delay)

    def click(
        self,
        key: str,
        screen: Optional[np.ndarray] = None,
        threshold: Optional[float] = None,
        delay: float = 0.3,
    ) -> bool:
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="left", delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot click", key)
        return False

    def wait_and_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 0.3,
        threshold: Optional[float] = None,
    ) -> bool:
        match = self.wait_for(
            key, timeout=timeout, interval=interval, threshold=threshold
        )
        if match:
            cx, cy = self.center(match)
            log.info("Left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="left", delay=delay)
            return True
        log.warning("Could not find '%s' within %.1fs", key, timeout)
        return False

    # -- right-click -----------------------------------------------------------

    def right_click(
        self,
        key: str,
        screen: Optional[np.ndarray] = None,
        threshold: Optional[float] = None,
        delay: float = 0.3,
    ) -> bool:
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Right-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="right", delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot right-click", key)
        return False

    def wait_and_right_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 0.3,
        threshold: Optional[float] = None,
    ) -> bool:
        match = self.wait_for(
            key, timeout=timeout, interval=interval, threshold=threshold
        )
        if match:
            cx, cy = self.center(match)
            log.info("Right-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="right", delay=delay)
            return True
        log.warning("Could not right-click '%s' within %.1fs — not found", key, timeout)
        return False


# -----------------------------------------------------------------------------
# Rule engine
# -----------------------------------------------------------------------------


class RuleEngine:
    def __init__(self, rules_file: Path):
        self.accept_rules: list = []
        self.decline_rules: list = []
        self._load(rules_file)

    def _load(self, path: Path):
        if not path.exists():
            log.warning("Rules file not found: %s — defaulting to DECLINE all", path)
            return
        with open(path) as f:
            data = json.load(f)
        self.accept_rules = data.get("accept", [])
        self.decline_rules = data.get("decline", [])
        log.info(
            "Rules loaded: %d accept / %d decline",
            len(self.accept_rules),
            len(self.decline_rules),
        )

    @staticmethod
    def _matches(rule: dict, mission: dict) -> bool:
        for rule_key, field in [
            ("title_contains", "title"),
            ("target_contains", "target"),
            ("location_contains", "location"),
            ("security_contains", "security"),
        ]:
            pattern = rule.get(rule_key)
            if pattern is None:
                continue
            if pattern.lower() not in (mission.get(field) or "").lower():
                return False
        return True

    def evaluate(self, mission: dict) -> str:
        for rule in self.decline_rules:
            if self._matches(rule, mission):
                log.info("DECLINE — rule: %s", rule.get("comment", rule))
                return "DECLINE"
        for rule in self.accept_rules:
            if self._matches(rule, mission):
                log.info("ACCEPT  — rule: %s", rule.get("comment", rule))
                return "ACCEPT"
        log.info("DECLINE — no accept rule matched")
        return "DECLINE"


# -----------------------------------------------------------------------------
# Mission area helper
# -----------------------------------------------------------------------------


def load_mission_area() -> Tuple[int, int, int, int]:
    if COORDS_FILE.exists():
        with open(COORDS_FILE) as f:
            data = json.load(f)
        area = data.get("mission_area", {})
        return (
            area.get("x", 440),
            area.get("y", 92),
            area.get("width", 988),
            area.get("height", 336),
        )
    return (440, 92, 988, 336)


# -----------------------------------------------------------------------------
# EVEAutomation — full cycle controller
# -----------------------------------------------------------------------------


class EVEAutomation:
    def __init__(self, dry_run: bool = False):
        self.dry_run = dry_run
        self.matcher = TemplateMatcher(TEMPLATES_DIR)
        self.rules = RuleEngine(RULES_FILE)
        self.ocr = EVEMissionReader()
        self.mission_area = load_mission_area()
        log.info("EVEAutomation ready (dry_run=%s)", dry_run)
        log.info(
            "Templates loaded: %d / %d",
            len(self.matcher.templates),
            len(TEMPLATE_FILES),
        )
        log.info("Mission OCR area: x=%d y=%d w=%d h=%d", *self.mission_area)

    # -- click helpers ---------------------------------------------------------

    def _click(self, key: str, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.find(key)
            if match:
                log.info(
                    "[DRY RUN] Would left-click '%s' at %s",
                    key,
                    self.matcher.center(match),
                )
                return True
            log.info("[DRY RUN] '%s' not on screen", key)
            return False
        return self.matcher.click(key, **kwargs)

    def _right_click(self, key: str, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.find(key)
            if match:
                log.info(
                    "[DRY RUN] Would right-click '%s' at %s",
                    key,
                    self.matcher.center(match),
                )
                return True
            log.info("[DRY RUN] '%s' not on screen", key)
            return False
        return self.matcher.right_click(key, **kwargs)

    def _wait_click(self, key: str, timeout: float = 8.0, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.wait_for(key, timeout=timeout)
            if match:
                log.info(
                    "[DRY RUN] Would left-click '%s' at %s",
                    key,
                    self.matcher.center(match),
                )
                return True
            log.info("[DRY RUN] '%s' not found within %.1fs", key, timeout)
            return False
        return self.matcher.wait_and_click(key, timeout=timeout, **kwargs)

    # -- steps -----------------------------------------------------------------

    def step_request_mission(self) -> bool:
        log.info("-- Step 1: Looking for 'Request Mission' button ...")
        found = self._wait_click("request_mission", timeout=5.0)
        if not found:
            log.info("   Not visible — dialog may already be open.")
        else:
            time.sleep(1.0)
        return True

    def step_read_mission(self) -> Optional[dict]:
        log.info("-- Step 2: Waiting for mission dialog ...")
        deadline = time.time() + 12.0
        while time.time() < deadline:
            screen = self.matcher.screenshot_cv()
            if self.matcher.find("accept", screen=screen) or self.matcher.find(
                "decline", screen=screen
            ):
                log.info("   Mission dialog detected.")
                break
            time.sleep(0.5)
        else:
            log.warning("   Mission dialog did not appear within timeout.")
            return None

        time.sleep(0.3)
        x, y, w, h = self.mission_area
        mission = self.ocr.read_mission(x, y, w, h)
        if mission:
            log.info(
                "   OCR: title='%s' target='%s' location='%s'",
                mission.get("title"),
                mission.get("target"),
                mission.get("location"),
            )
        else:
            log.warning("   OCR returned no mission data.")
        return mission

    def step_decide(self, mission: Optional[dict]) -> str:
        if not mission or mission.get("title") in (None, "Unknown"):
            log.info("-- Step 3: No valid mission data -> DECLINE (safe default)")
            return "DECLINE"
        log.info("-- Step 3: Evaluating rules ...")
        return self.rules.evaluate(mission)

    def step_accept(self, mission: Optional[dict]) -> bool:
        log.info("-- Step 4: ACCEPTING '%s'", (mission or {}).get("title", "Unknown"))
        if not self._wait_click("accept", timeout=5.0):
            log.error("   Accept button not found!")
            return False
        time.sleep(0.5)

        log.info("-- Step 5: Clicking Track (if present) ...")
        self._wait_click("track", timeout=6.0)  # optional — no error if absent
        time.sleep(0.3)

        log.info("-- Step 6: Closing dialog ...")
        if not self._wait_click("close_active_window", timeout=4.0):
            log.warning("   Close button not found — dialog may have closed already.")
        return True

    def step_decline(self, mission: Optional[dict]) -> bool:
        log.info("-- Step 4: DECLINING '%s'", (mission or {}).get("title", "Unknown"))
        if not self._wait_click("decline", timeout=5.0):
            log.error("   Decline button not found!")
            return False
        return True

    # -- full cycle ------------------------------------------------------------

    def run_once(self) -> dict:
        try:
            self.step_request_mission()
            mission = self.step_read_mission()
            decision = self.step_decide(mission)
            ok = (
                self.step_accept(mission)
                if decision == "ACCEPT"
                else self.step_decline(mission)
            )
            return {
                "decision": decision if ok else "ERROR",
                "mission": mission,
                "ok": ok,
            }
        except Exception as e:
            log.exception("Unhandled error: %s", e)
            return {"decision": "ERROR", "mission": None, "ok": False}

    def run_loop(self, interval: float = 3.0):
        log.info("Continuous loop started — Ctrl+C to stop")
        while True:
            try:
                screen = self.matcher.screenshot_cv()
                if self.matcher.find("request_mission", screen=screen):
                    log.info("=== Mission opportunity detected ===")
                    result = self.run_once()
                    log.info("=== Result: %s ===", result.get("decision"))
                    time.sleep(2.0)
                else:
                    time.sleep(interval)
            except KeyboardInterrupt:
                log.info("Loop stopped by user.")
                break


# -----------------------------------------------------------------------------
# --check mode
# -----------------------------------------------------------------------------


def cmd_check(threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)

    print("\n=== Template Status ===")
    print(matcher.status())

    print("\n=== Screen Scan (visible right now) ===")
    results = matcher.find_all_on_screen()
    found = {k: v for k, v in results.items() if v is not None}
    absent = [k for k, v in results.items() if v is None]
    if found:
        for key, match in sorted(found.items()):
            cx, cy = matcher.center(match)
            print(f"  FOUND   {key:<25s} centre=({cx},{cy})")
    else:
        print("  (nothing matched on screen)")
    if absent:
        print(f"\n  Not on screen: {', '.join(sorted(absent))}")

    print("\n=== Mission Area ===")
    area = load_mission_area()
    print(f"  x={area[0]} y={area[1]} w={area[2]} h={area[3]}")
    print(
        f"  Coords file: {COORDS_FILE}  "
        f"({'exists' if COORDS_FILE.exists() else 'NOT FOUND — using defaults'})"
    )

    print("\n=== Rules ===")
    rules = RuleEngine(RULES_FILE)
    print(
        f"  Rules file: {RULES_FILE}  "
        f"({'exists' if RULES_FILE.exists() else 'NOT FOUND'})"
    )
    print(f"  Accept rules : {len(rules.accept_rules)}")
    print(f"  Decline rules: {len(rules.decline_rules)}")


# -----------------------------------------------------------------------------
# --click / --right-click single-action modes (called by OCRBridge)
# -----------------------------------------------------------------------------


def cmd_click(key: str, dry_run: bool, threshold: float):
    """Find the named template and left-click it. Exit 0=clicked, 1=not found."""
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=6.0)
        if match:
            log.info(
                "[DRY RUN] Would left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_click(key, timeout=6.0)
        sys.exit(0 if ok else 1)


def cmd_right_click(key: str, dry_run: bool, threshold: float):
    """Find the named template and right-click it. Exit 0=clicked, 1=not found."""
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=6.0)
        if match:
            log.info(
                "[DRY RUN] Would right-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_right_click(key, timeout=6.0)
        sys.exit(0 if ok else 1)


# -----------------------------------------------------------------------------
# CLI entry point
# -----------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="EVE Online mission automation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python eve_automation.py --check
  python eve_automation.py --dry-run
  python eve_automation.py --loop
  python eve_automation.py --click accept
  python eve_automation.py --right-click ship_hangar
  python eve_automation.py --click request_mission --dry-run
""",
    )
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--run-once", action="store_true")
    parser.add_argument(
        "--click", metavar="KEY", help="Left-click a template then exit"
    )
    parser.add_argument(
        "--right-click", metavar="KEY", help="Right-click a template then exit"
    )
    parser.add_argument(
        "--wait", metavar="KEY", help="Wait for template to appear, then exit"
    )
    parser.add_argument(
        "--wait-gone", metavar="KEY", help="Wait for template to disappear, then exit"
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=8.0,
        help="Timeout in seconds for --wait / --wait-gone",
    )
    parser.add_argument("--threshold", type=float, default=0.80)
    args = parser.parse_args()

    if args.check:
        cmd_check(args.threshold)
        return

    if args.click:
        cmd_click(args.click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "right_click", None):
        cmd_right_click(
            args.right_click, dry_run=args.dry_run, threshold=args.threshold
        )
        return

    if args.wait:
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        found = matcher.wait_for(args.wait, timeout=args.timeout)
        if found:
            log.info("'%s' appeared on screen.", args.wait)
            sys.exit(0)
        else:
            log.warning("'%s' did not appear within %.1fs.", args.wait, args.timeout)
            sys.exit(1)

    if getattr(args, "wait_gone", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        deadline = time.time() + args.timeout
        while time.time() < deadline:
            if matcher.find(args.wait_gone) is None:
                log.info("'%s' is gone from screen.", args.wait_gone)
                sys.exit(0)
            time.sleep(0.4)
        log.warning("'%s' still visible after %.1fs.", args.wait_gone, args.timeout)
        sys.exit(1)

    bot = EVEAutomation(dry_run=args.dry_run)
    bot.matcher.threshold = args.threshold

    if args.loop:
        bot.run_loop()
    else:
        result = bot.run_once()
        print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    main()
