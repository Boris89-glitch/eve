"""
eve_automation.py — Template-matching based EVE Online mission automation.

Flow (Phase 1):
  1. Find & click "Request Mission" button
  2. Wait for mission dialog to appear
  3. Run OCR on mission area to parse title/target/location
  4. Evaluate accept/decline rules (from mission_rules.json)
  5a. ACCEPT → click Accept → click Track → close dialog (X)
  5b. DECLINE → click Decline

Usage (standalone test):
  python eve_automation.py

Called from Java OCRBridge via:
  python eve_automation.py --run-once
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

# ── optional win32 for foreground window ─────────────────────────────────────
try:
    import win32gui, win32con
    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False

# ── local imports ─────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
from mission_reader import EVEMissionReader  # noqa: E402

# ─────────────────────────────────────────────────────────────────────────────
# Logging
# ─────────────────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("eve_automation")

# ─────────────────────────────────────────────────────────────────────────────
# Paths
# ─────────────────────────────────────────────────────────────────────────────

TEMPLATES_DIR = SCRIPT_DIR.parent / "eveMissions" / "templates"
RULES_FILE    = SCRIPT_DIR / "config" / "mission_rules.json"
COORDS_FILE   = SCRIPT_DIR.parent / "mission_coordinates.json"

# ─────────────────────────────────────────────────────────────────────────────
# Template names → file names (without extension)
# ─────────────────────────────────────────────────────────────────────────────

TEMPLATE_FILES = {
    "request_mission":  "request_mission",
    "accept":           "accept",
    "decline":          "decline",
    "track":            "track",
    "close":            "close_active_window",
    "complete_mission": "complete_mission",
    "repair":           "repair",
    "get_repair_quote": "get_repair_quote",
    "repair_yes":       "repair_yes",
    "undock":           "undock",
    "ship_hangar":      "ship_hangar",
}

# ─────────────────────────────────────────────────────────────────────────────
# TemplateMatcher
# ─────────────────────────────────────────────────────────────────────────────

class TemplateMatcher:
    """Loads templates once and exposes find/click helpers."""

    def __init__(self, templates_dir: Path, threshold: float = 0.80):
        self.threshold = threshold
        self.templates: Dict[str, np.ndarray] = {}
        self._load_all(templates_dir)

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
                        log.debug("Loaded template '%s' from %s", key, path.name)
                    break
            else:
                log.warning("Template not found for key '%s' in %s", key, directory)

    # ── screenshot helper ─────────────────────────────────────────────────────

    @staticmethod
    def screenshot_cv() -> np.ndarray:
        """Full-screen screenshot as BGR numpy array."""
        img = pyautogui.screenshot()
        return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)

    # ── find ──────────────────────────────────────────────────────────────────

    def find(
        self,
        key: str,
        screen: Optional[np.ndarray] = None,
        threshold: Optional[float] = None,
    ) -> Optional[Tuple[int, int, int, int]]:
        """
        Returns (x, y, w, h) of the best match or None.
        Coordinates are absolute screen pixels.
        """
        template = self.templates.get(key)
        if template is None:
            log.warning("Template '%s' not loaded — skipping find.", key)
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

    def center(self, match: Tuple[int, int, int, int]) -> Tuple[int, int]:
        x, y, w, h = match
        return (x + w // 2, y + h // 2)

    # ── wait_for ─────────────────────────────────────────────────────────────

    def wait_for(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        threshold: Optional[float] = None,
    ) -> Optional[Tuple[int, int, int, int]]:
        """Poll until template appears or timeout. Returns match or None."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            match = self.find(key, threshold=threshold)
            if match:
                return match
            time.sleep(interval)
        log.debug("Timeout waiting for '%s'", key)
        return None

    # ── click ─────────────────────────────────────────────────────────────────

    def click(self, key: str, screen: Optional[np.ndarray] = None,
              threshold: Optional[float] = None, delay: float = 0.3) -> bool:
        """Find template and click its centre. Returns True if clicked."""
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            pyautogui.click(cx, cy)
            log.info("Clicked '%s' at (%d,%d)", key, cx, cy)
            time.sleep(delay)
            return True
        return False

    def wait_and_click(self, key: str, timeout: float = 10.0,
                       interval: float = 0.5, delay: float = 0.3,
                       threshold: Optional[float] = None) -> bool:
        """Wait for template then click. Returns True if clicked."""
        match = self.wait_for(key, timeout=timeout, interval=interval,
                              threshold=threshold)
        if match:
            cx, cy = self.center(match)
            pyautogui.click(cx, cy)
            log.info("Clicked '%s' at (%d,%d)", key, cx, cy)
            time.sleep(delay)
            return True
        log.warning("Could not find '%s' within %.1fs", key, timeout)
        return False


# ─────────────────────────────────────────────────────────────────────────────
# Rule engine
# ─────────────────────────────────────────────────────────────────────────────

class RuleEngine:
    def __init__(self, rules_file: Path):
        self.accept_rules:  list = []
        self.decline_rules: list = []
        self._load(rules_file)

    def _load(self, path: Path):
        if not path.exists():
            log.warning("Rules file not found: %s — defaulting to DECLINE all", path)
            return
        with open(path) as f:
            data = json.load(f)
        self.accept_rules  = data.get("accept",  [])
        self.decline_rules = data.get("decline", [])
        log.info("Rules loaded: %d accept / %d decline",
                 len(self.accept_rules), len(self.decline_rules))

    @staticmethod
    def _matches(rule: dict, mission: dict) -> bool:
        checks = {
            "title_contains":    ("title",    False),
            "target_contains":   ("target",   False),
            "location_contains": ("location", False),
            "security_contains": ("security", False),
        }
        for rule_key, (field, _) in checks.items():
            pattern = rule.get(rule_key)
            if pattern is None:
                continue
            value = (mission.get(field) or "").lower()
            if pattern.lower() not in value:
                return False
        return True

    def evaluate(self, mission: dict) -> str:
        """Returns 'ACCEPT' or 'DECLINE'."""
        for rule in self.decline_rules:
            if self._matches(rule, mission):
                log.info("DECLINE — matched rule: %s", rule.get("comment", rule))
                return "DECLINE"
        for rule in self.accept_rules:
            if self._matches(rule, mission):
                log.info("ACCEPT  — matched rule: %s", rule.get("comment", rule))
                return "ACCEPT"
        log.info("DECLINE — no accept rule matched")
        return "DECLINE"


# ─────────────────────────────────────────────────────────────────────────────
# Mission area
# ─────────────────────────────────────────────────────────────────────────────

def load_mission_area() -> Tuple[int, int, int, int]:
    """Load calibrated mission OCR area from mission_coordinates.json."""
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


# ─────────────────────────────────────────────────────────────────────────────
# Main automation controller
# ─────────────────────────────────────────────────────────────────────────────

class EVEAutomation:
    """
    Drives the mission request → accept/decline → track flow using
    template matching for UI detection and OCR for mission data.
    """

    def __init__(self, dry_run: bool = False):
        self.dry_run  = dry_run
        self.matcher  = TemplateMatcher(TEMPLATES_DIR)
        self.rules    = RuleEngine(RULES_FILE)
        self.ocr      = EVEMissionReader()
        self.mission_area = load_mission_area()
        log.info("EVEAutomation ready. Templates dir: %s", TEMPLATES_DIR)
        log.info("Mission OCR area: %s", self.mission_area)
        if dry_run:
            log.info("DRY RUN — clicks will be logged but not executed")

    # ── helpers ───────────────────────────────────────────────────────────────

    def _click(self, key: str, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.find(key)
            if match:
                log.info("[DRY RUN] Would click '%s' at %s", key, self.matcher.center(match))
                return True
            return False
        return self.matcher.click(key, **kwargs)

    def _wait_click(self, key: str, timeout: float = 8.0, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.wait_for(key, timeout=timeout)
            if match:
                log.info("[DRY RUN] Would click '%s' at %s", key, self.matcher.center(match))
                return True
            return False
        return self.matcher.wait_and_click(key, timeout=timeout, **kwargs)

    # ── Step 1: Request mission ───────────────────────────────────────────────

    def step_request_mission(self) -> bool:
        """Click 'Request Mission'. Returns True if button was found and clicked."""
        log.info("── Step 1: Looking for 'Request Mission' button …")
        found = self._wait_click("request_mission", timeout=5.0)
        if not found:
            log.info("'Request Mission' not visible — dialog may already be open.")
        else:
            time.sleep(1.0)  # let dialog open
        return True  # proceed regardless; dialog might already be showing

    # ── Step 2: Read mission via OCR ─────────────────────────────────────────

    def step_read_mission(self) -> Optional[dict]:
        """Wait for mission dialog then OCR it. Returns mission dict or None."""
        log.info("── Step 2: Reading mission dialog via OCR …")

        # Wait until either Accept or Decline button is visible (dialog open)
        log.info("   Waiting for mission dialog (Accept/Decline buttons) …")
        deadline = time.time() + 12.0
        while time.time() < deadline:
            screen = self.matcher.screenshot_cv()
            if self.matcher.find("accept", screen=screen) or \
               self.matcher.find("decline", screen=screen):
                log.info("   Mission dialog detected.")
                break
            time.sleep(0.5)
        else:
            log.warning("   Mission dialog did not appear within timeout.")
            return None

        # Small delay for dialog to fully render text
        time.sleep(0.3)

        x, y, w, h = self.mission_area
        mission = self.ocr.read_mission(x, y, w, h)
        if mission:
            log.info("   OCR result: title='%s' target='%s' location='%s'",
                     mission.get("title"), mission.get("target"), mission.get("location"))
        else:
            log.warning("   OCR returned no mission data.")
        return mission

    # ── Step 3: Decide ───────────────────────────────────────────────────────

    def step_decide(self, mission: Optional[dict]) -> str:
        """Evaluate rules. Returns 'ACCEPT' or 'DECLINE'."""
        if not mission or mission.get("title") in (None, "Unknown"):
            log.info("── Step 3: No valid mission data → DECLINE (safe default)")
            return "DECLINE"
        log.info("── Step 3: Evaluating rules …")
        return self.rules.evaluate(mission)

    # ── Step 4a: Accept path ─────────────────────────────────────────────────

    def step_accept(self, mission: Optional[dict]) -> bool:
        """Click Accept, then Track, then close dialog."""
        title = (mission or {}).get("title", "Unknown")
        log.info("── Step 4: ACCEPTING mission '%s'", title)

        if not self._wait_click("accept", timeout=5.0):
            log.error("   Could not find Accept button!")
            return False

        time.sleep(0.5)

        # Track button — only visible after Accept on the confirmation/objective panel.
        # It may already be tracked if this is not the first mission, but clicking
        # it again is harmless.
        log.info("── Step 5: Clicking Track …")
        if not self._wait_click("track", timeout=6.0):
            log.warning("   Track button not found — mission may already be tracked.")

        time.sleep(0.3)

        # Close the mission dialog with the X button
        log.info("── Step 6: Closing mission dialog (X) …")
        if not self._wait_click("close", timeout=4.0):
            log.warning("   Close (X) button not found — dialog may have closed already.")

        return True

    # ── Step 4b: Decline path ────────────────────────────────────────────────

    def step_decline(self, mission: Optional[dict]) -> bool:
        """Click Decline."""
        title = (mission or {}).get("title", "Unknown")
        log.info("── Step 4: DECLINING mission '%s'", title)

        if not self._wait_click("decline", timeout=5.0):
            log.error("   Could not find Decline button!")
            return False

        return True

    # ── Full run ──────────────────────────────────────────────────────────────

    def run_once(self) -> dict:
        """
        Execute one complete mission request → decide → act cycle.

        Returns a result dict:
          { "decision": "ACCEPT"|"DECLINE"|"ERROR", "mission": {...}|None }
        """
        try:
            self.step_request_mission()
            mission  = self.step_read_mission()
            decision = self.step_decide(mission)

            if decision == "ACCEPT":
                ok = self.step_accept(mission)
            else:
                ok = self.step_decline(mission)

            return {
                "decision": decision if ok else "ERROR",
                "mission":  mission,
                "ok":       ok,
            }

        except Exception as e:
            log.exception("Unhandled error in run_once: %s", e)
            return {"decision": "ERROR", "mission": None, "ok": False}

    # ── Continuous loop ───────────────────────────────────────────────────────

    def run_loop(self, interval: float = 3.0):
        """
        Continuously monitor for the Request Mission button and act.
        Stops on KeyboardInterrupt.
        """
        log.info("Starting continuous mission loop (Ctrl+C to stop) …")
        while True:
            screen = self.matcher.screenshot_cv()

            # Only trigger when Request Mission is visible
            if self.matcher.find("request_mission", screen=screen):
                log.info("=== Mission opportunity detected ===")
                result = self.run_once()
                log.info("=== Result: %s ===", result.get("decision"))
                time.sleep(2.0)  # cooldown before next scan
            else:
                time.sleep(interval)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="EVE Mission Automation")
    parser.add_argument("--run-once",  action="store_true",
                        help="Execute one mission cycle then exit (used by Java)")
    parser.add_argument("--loop",      action="store_true",
                        help="Continuously monitor and act")
    parser.add_argument("--dry-run",   action="store_true",
                        help="Log actions but don't click")
    parser.add_argument("--threshold", type=float, default=0.80,
                        help="Template match confidence threshold (default 0.80)")
    args = parser.parse_args()

    bot = EVEAutomation(dry_run=args.dry_run)
    bot.matcher.threshold = args.threshold

    if args.run_once or args.loop is False and not args.loop:
        if args.loop:
            bot.run_loop()
        else:
            result = bot.run_once()
            print(json.dumps(result, indent=2, default=str))
    else:
        bot.run_loop()


if __name__ == "__main__":
    main()
