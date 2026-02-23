"""
eve_automation.py — Template-matching EVE Online mission automation.

Standalone usage:
  python eve_automation.py --check          # verify templates load, scan screen
  python eve_automation.py --dry-run        # full cycle, log only (no clicks)
  python eve_automation.py --loop           # continuous production loop
  python eve_automation.py --click accept   # find + click one template then exit

Called from Java OCRBridge via:
  python eve_automation.py --click <key>    # single-action calls
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

TEMPLATES_DIR = SCRIPT_DIR.parent  / "templates"
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
    "close": "close_active_window",
    "complete_mission": "complete_mission",
    "repair": "repair",
    "get_repair_quote": "get_repair_quote",
    "repair_yes": "repair_yes",
    "undock": "undock",
    "ship_hangar": "ship_hangar",
}

# -----------------------------------------------------------------------------
# TemplateMatcher
# -----------------------------------------------------------------------------


class TemplateMatcher:
    """Loads PNG templates once and exposes find / click helpers."""

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
        """Human-readable summary of loaded vs missing templates."""
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
        """Return (x, y, w, h) of best match or None."""
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
        """Scan current screen for every loaded template. Used by --check."""
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

    # -- click -----------------------------------------------------------------

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
            pyautogui.click(cx, cy)
            log.info("Clicked '%s' at (%d,%d)", key, cx, cy)
            time.sleep(delay)
            return True
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
            pyautogui.click(cx, cy)
            log.info("Clicked '%s' at (%d,%d)", key, cx, cy)
            time.sleep(delay)
            return True
        log.warning("Could not find '%s' within %.1fs", key, timeout)
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
                    "[DRY RUN] Would click '%s' at %s", key, self.matcher.center(match)
                )
                return True
            log.info("[DRY RUN] '%s' not on screen right now", key)
            return False
        return self.matcher.click(key, **kwargs)

    def _wait_click(self, key: str, timeout: float = 8.0, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.wait_for(key, timeout=timeout)
            if match:
                log.info(
                    "[DRY RUN] Would click '%s' at %s", key, self.matcher.center(match)
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
        log.info("-- Step 2: Waiting for mission dialog (Accept/Decline visible) ...")
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
        log.info("-- Step 5: Clicking Track ...")
        if not self._wait_click("track", timeout=6.0):
            log.warning("   Track not found — may already be tracked.")
        time.sleep(0.3)
        log.info("-- Step 6: Closing dialog ...")
        if not self._wait_click("close", timeout=4.0):
            log.warning("   Close (X) not found — dialog may have closed already.")
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
# --check mode  (diagnostic — always prints, never hangs)
# -----------------------------------------------------------------------------


def cmd_check(threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)

    print("\n=== Template Status ===")
    print(matcher.status())

    print("\n=== Screen Scan (what is visible right now) ===")
    results = matcher.find_all_on_screen()
    found = {k: v for k, v in results.items() if v is not None}
    absent = [k for k, v in results.items() if v is None]
    if found:
        for key, match in sorted(found.items()):
            cx, cy = matcher.center(match)
            print(f"  FOUND   {key:<22s} centre=({cx},{cy})")
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
# --click mode  (single-action, called by OCRBridge.clickTemplate)
# -----------------------------------------------------------------------------


def cmd_click(key: str, dry_run: bool, threshold: float):
    """Find the named template and click it. Exit 0=clicked, 1=not found."""
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=6.0)
        if match:
            log.info("[DRY RUN] Would click '%s' at %s", key, matcher.center(match))
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found on screen", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_click(key, timeout=6.0)
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
  python eve_automation.py --check                  verify templates & scan screen
  python eve_automation.py --dry-run                full cycle, no clicks
  python eve_automation.py --loop                   continuous production loop
  python eve_automation.py --click accept           click Accept button once
  python eve_automation.py --click request_mission --dry-run
""",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Print template status and scan current screen, then exit",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Run full cycle but log clicks instead of executing them",
    )
    parser.add_argument(
        "--loop", action="store_true", help="Continuously monitor for missions and act"
    )
    parser.add_argument(
        "--run-once",
        action="store_true",
        help="Execute one mission cycle then exit (also the default)",
    )
    parser.add_argument(
        "--click",
        metavar="KEY",
        help="Find and click a single named template then exit",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.80,
        help="Template match confidence (default: 0.80)",
    )
    args = parser.parse_args()

    # --check: diagnostic, always prints immediately, never hangs
    if args.check:
        cmd_check(args.threshold)
        return

    # --click: single-action for Java bridge
    if args.click:
        cmd_click(args.click, dry_run=args.dry_run, threshold=args.threshold)
        return  # cmd_click calls sys.exit internally

    # Full automation modes
    bot = EVEAutomation(dry_run=args.dry_run)
    bot.matcher.threshold = args.threshold

    if args.loop:
        bot.run_loop()
    else:
        # --run-once or bare invocation
        result = bot.run_once()
        print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    main()
