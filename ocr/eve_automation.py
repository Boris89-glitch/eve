"""
eve_automation.py — Template-matching EVE Online mission automation.

Standalone usage:
  python eve_automation.py --check                   verify templates, scan screen
  python eve_automation.py --dry-run                 full cycle, log only (no clicks)
  python eve_automation.py --loop                    continuous production loop
  python eve_automation.py --click accept            find + left-click template, exit
  python eve_automation.py --right-click ship_hangar find + right-click template, exit
  python eve_automation.py --e-click stargate        find + hold-E left-click template, exit

Called from Java OCRBridge via:
  python eve_automation.py --click <key>             left-click action
  python eve_automation.py --right-click <key>       right-click action
  python eve_automation.py --double-click <key>      double-click action
  python eve_automation.py --e-click <key>           hold-E + left-click action
  python eve_automation.py --repair-ship <title>     find ship for mission title, repair it,
                                                     then right-click → Board Ship to activate
"""

import sys
import os
import json
import time
import logging
import argparse
from pathlib import Path
from typing import Optional, Tuple, Dict, List

import cv2
import numpy as np
import pyautogui
from PIL import Image

# Disable pyautogui's corner-of-screen failsafe
pyautogui.FAILSAFE = False
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
SHIP_MAP_FILE = SCRIPT_DIR / "config" / "ship_mission_map.json"
COORDS_FILE = SCRIPT_DIR.parent / "mission_coordinates.json"

# -----------------------------------------------------------------------------
# Template registry  (key -> filename without extension)
# -----------------------------------------------------------------------------

TEMPLATE_FILES = {
    # UI buttons / dialogs
    "request_mission": "request_mission",
    "accept": "accept",
    "decline": "decline",
    "track": "track",
    "untrack": "untrack",  # visible when mission is already tracked
    "close_active_window": "close_active_window",
    "complete_mission": "complete_mission",
    # Repair flow
    "get_repair_quote": "get_repair_quote",
    "repair": "repair",
    "repair_yes": "repair_yes",
    "already_repaired": "already_repaired",  # "nothing to repair" dialog — skip repair, proceed to activation
    "board_ship": "board_ship",  # context menu item — right-click ship → Board Ship
    # Hangar / navigation
    "undock": "undock",
    "ship_hangar": "ship_hangar",
    # Travel / returning
    "set_destination": "set_destination",
    "stargate": "stargate",
    "warp_to_location": "warp_to_location",
    "activate_gate": "activate_gate",
    "dock": "dock",
    "ok": "ok",
    # Agent interaction
    "agent_icon": "agent_icon",  # agent portrait — double-click fallback
    # Mission dialog detection templates — crop these from the mission dialog window.
    # Name them mission_<target>.png. They are matched against the open dialog to
    # identify the mission type without relying on OCR text parsing.
    "mission_vengeance": "mission_vengeance",  # → ship_vengeance/ship_garmur
    "mission_enyo": "mission_enyo",  # → ship_garmur
    "mission_hawk": "mission_hawk",  # → ship_garmur
    "mission_jaguar": "mission_jaguar",  # → ship_garmur
    "mission_worm": "mission_worm",  # → ship_worm
    "mission_garmur": "mission_garmur",  # → ship_garmur
    "mission_dramiel": "mission_dramiel",  # → ship_angel
    "mission_succubus": "mission_succubus",  # → ship_garmur
    "mission_sansha": "mission_sansha",  # → ship_sansha
    "mission_angel": "mission_angel",  # → ship_angel
    "mission_infested": "mission_infested",  # → ship_sk  (Infested Ruins)
    "mission_recon1": "mission_recon1",  # → ship_sk  (Recon part 1)
    # Ship templates — one per ship icon in your hangar
    "ship_garmur": "ship_garmur",
    "ship_vengeance": "ship_vengeance",
    "ship_worm": "ship_worm",
    "ship_sansha": "ship_sansha",
    "ship_angel": "ship_angel",
    "ship_sk": "ship_sk",
}

# Maps mission dialog template keys → ship template keys.
# Checked in order; first match wins.
MISSION_TEMPLATE_TO_SHIP = [
    ("mission_vengeance", "ship_vengeance"),# can we do this in garmur?
    ("mission_jaguar", "ship_garmur"),
    ("mission_enyo", "ship_garmur"),
    ("mission_hawk", "ship_garmur"),
    ("mission_worm", "ship_worm"),
    ("mission_garmur", "ship_garmur"),
    ("mission_dramiel", "ship_angel"),
    ("mission_succubus", "ship_sansha"),
    ("mission_sansha", "ship_sansha"),
    ("mission_angel", "ship_angel"),
    ("mission_infested", "ship_sk"),
    ("mission_recon1", "ship_sk"),
]

# -----------------------------------------------------------------------------
# Ship → Mission mapping
# -----------------------------------------------------------------------------


def load_ship_mission_map() -> dict:
    """Load ship_mission_map.json. Returns dict with 'ship_missions' list."""
    if SHIP_MAP_FILE.exists():
        with open(SHIP_MAP_FILE) as f:
            return json.load(f)
    log.warning("ship_mission_map.json not found — will use default ship template.")
    return {"ship_missions": [], "default_ship_template": "ship_garmur"}


def resolve_ship_via_dialog_templates(matcher: "TemplateMatcher") -> Optional[str]:
    """
    Scan the current screen for mission dialog templates (mission_*).
    Returns the ship template key for the first match, or None if nothing matched.
    This is the primary identification method — no OCR involved.
    """
    screen = matcher.screenshot_cv()
    for dialog_key, ship_key in MISSION_TEMPLATE_TO_SHIP:
        if dialog_key not in matcher.templates:
            continue  # template image not provided yet — skip silently
        match = matcher.find(dialog_key, screen=screen)
        if match:
            log.info("Dialog template matched: '%s' → ship: '%s'", dialog_key, ship_key)
            return ship_key
    return None


def resolve_ship_template(mission_target: str, ship_map: dict) -> str:
    """
    Fallback: resolve ship from OCR target/title string via ship_mission_map.json.
    Only called when dialog template matching found nothing.
    """
    target_lower = mission_target.lower() if mission_target else ""
    for entry in ship_map.get("ship_missions", []):
        for pattern in entry.get("title_contains", []):
            if pattern.lower() in target_lower:
                log.info(
                    "Ship resolved via OCR fallback: '%s' → template '%s' (matched '%s')",
                    mission_target,
                    entry["ship_template"],
                    pattern,
                )
                return entry["ship_template"]
    default = ship_map.get("default_ship_template", "ship_vengeance")
    log.info("No OCR rule matched '%s' — using default: %s", mission_target, default)
    return default


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
                log.debug(
                    "Template not found: '%s' (expected: %s[.png/.jpg/.bmp])",
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
        """Return (x, y, w, h) of best match or None."""
        template = self.templates.get(key)
        if template is None:
            log.debug("Template '%s' not loaded — skipping.", key)
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
        interval: float = 0.2,
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

    def wait_for_any(
        self,
        keys: list,
        timeout: float = 10.0,
        interval: float = 0.2,
    ):
        """Poll for any of several templates; return (key, match) for the first hit."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            screen = self.screenshot_cv()
            for key in keys:
                match = self.find(key, screen=screen)
                if match:
                    return key, match
            time.sleep(interval)
        return None, None

    # -- low-level click -------------------------------------------------------

    def _do_click(self, cx: int, cy: int, button: str = "left", delay: float = 0.3):
        try:
            import win32api, win32con

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
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            if button == "right":
                pyautogui.rightClick(cx, cy)
            else:
                pyautogui.click(cx, cy)
            log.info("pyautogui %s-clicked at (%d,%d)", button, cx, cy)
        time.sleep(delay)

    def _do_double_click(self, cx: int, cy: int, delay: float = 0.3):
        """Double-click at given screen coordinates."""
        try:
            import win32api, win32con

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.08)  # inter-click gap
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            log.info("win32 double-clicked at (%d,%d)", cx, cy)
        except ImportError:
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.doubleClick(cx, cy)
            log.info("pyautogui double-clicked at (%d,%d)", cx, cy)
        time.sleep(delay)

    def _do_e_click(self, cx: int, cy: int, delay: float = 1.5):
        """Hold E, left-click at (cx, cy), release E. Used for jump/warp interactions."""
        VK_E = 0x45
        try:
            import win32api, win32con

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            win32api.keybd_event(VK_E, 0, 0, 0)          # key down
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_E, 0, win32con.KEYEVENTF_KEYUP, 0)  # key up
            log.info("win32 E+left-clicked at (%d,%d)", cx, cy)
        except ImportError:
            pyautogui.keyDown('e')
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            pyautogui.keyUp('e')
            log.info("pyautogui E+left-clicked at (%d,%d)", cx, cy)
        time.sleep(delay)

    # -- click helpers ---------------------------------------------------------

    def click(self, key: str, screen=None, threshold=None, delay: float = 0.3) -> bool:
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="left", delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot click", key)
        return False

    def double_click(
        self, key: str, screen=None, threshold=None, delay: float = 0.3
    ) -> bool:
        """Find template and double-click its centre."""
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Double-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_double_click(cx, cy, delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot double-click", key)
        return False

    def right_click(
        self, key: str, screen=None, threshold=None, delay: float = 0.3
    ) -> bool:
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Right-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_click(cx, cy, button="right", delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot right-click", key)
        return False

    def wait_and_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 0.3,
        threshold=None,
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

    def wait_and_double_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 0.3,
        threshold=None,
    ) -> bool:
        match = self.wait_for(
            key, timeout=timeout, interval=interval, threshold=threshold
        )
        if match:
            cx, cy = self.center(match)
            log.info("Double-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_double_click(cx, cy, delay=delay)
            return True
        log.warning("Could not find '%s' within %.1fs for double-click", key, timeout)
        return False

    def wait_and_right_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 0.3,
        threshold=None,
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

    def e_click(self, key: str, screen=None, threshold=None, delay: float = 1.5) -> bool:
        """Find template and hold-E left-click its centre."""
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("E+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_e_click(cx, cy, delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot E+click", key)
        return False

    def wait_and_e_click(
        self,
        key: str,
        timeout: float = 10.0,
        interval: float = 0.5,
        delay: float = 1.5,
        threshold=None,
    ) -> bool:
        match = self.wait_for(
            key, timeout=timeout, interval=interval, threshold=threshold
        )
        if match:
            cx, cy = self.center(match)
            log.info("E+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_e_click(cx, cy, delay=delay)
            return True
        log.warning("Could not E+click '%s' within %.1fs — not found", key, timeout)
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
            ("objective_contains", "objective"),
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
# Ship repair + activation flow
# -----------------------------------------------------------------------------


def cmd_repair_ship(mission_title: str, dry_run: bool, threshold: float):
    """
    Full ship select + repair flow for the ship matching mission_title:
      0. Click Ship Hangar to open the hangar view
      1. Find ship icon via template matching
      2. Right-click ship icon → Get Repair Quote → Repair All → Yes
      3. Right-click ship icon → Board Ship to make it the active ship
    Exit 0 = success, 1 = ship template not found.
    """
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    ship_map = load_ship_mission_map()

    # Primary: scan the open mission dialog for a known visual template
    ship_template = resolve_ship_via_dialog_templates(matcher)
    if ship_template:
        log.info("Ship identified via dialog template: %s", ship_template)
    else:
        # Fallback: use OCR-parsed target/title string
        log.info(
            "No dialog template matched — falling back to OCR string: '%s'",
            mission_title,
        )
        ship_template = resolve_ship_template(mission_title, ship_map)

    if ship_template not in matcher.templates:
        log.error(
            "Ship template '%s' not loaded — add %s.png to /templates/",
            ship_template,
            ship_template,
        )
        sys.exit(1)

    log.info("=== Repair + Activate flow for mission: '%s' ===", mission_title)
    log.info("Using ship template: %s", ship_template)

    if dry_run:
        match = matcher.find(ship_template)
        if match:
            cx, cy = matcher.center(match)
            log.info(
                "[DRY RUN] Would right-click '%s' at (%d,%d) for repair",
                ship_template,
                cx,
                cy,
            )
            log.info(
                "[DRY RUN] Would then right-click '%s' at (%d,%d) → Board Ship to activate",
                ship_template,
                cx,
                cy,
            )
            sys.exit(0)
        else:
            log.warning(
                "[DRY RUN] Ship template '%s' not found on screen", ship_template
            )
            sys.exit(1)

    # --- Step 0: Open Ship Hangar ---
    log.info("Step 0: Clicking 'Ship Hangar' to open hangar view...")
    if not matcher.wait_and_click("ship_hangar", timeout=5.0, delay=1.0):
        log.error("'Ship Hangar' not found — cannot open hangar")
        sys.exit(1)

    # --- Step 1: Find ship icon and lock its coordinates ---
    log.info("Step 1: Locating ship icon '%s'...", ship_template)
    ship_match = matcher.wait_for(ship_template, timeout=6.0)
    if not ship_match:
        log.error("Ship icon '%s' not found — cannot repair", ship_template)
        sys.exit(1)
    ship_cx, ship_cy = matcher.center(ship_match)
    log.info(
        "Ship icon locked at (%d,%d) — will reuse for repair and activation",
        ship_cx,
        ship_cy,
    )

    # --- Step 2: Right-click the locked coordinates → context menu ---
    log.info("Step 2: Right-clicking ship icon to open context menu...")
    matcher._do_click(ship_cx, ship_cy, button="right", delay=0.8)

    # --- Step 3: Click "Get Repair Quote" ---
    log.info("Step 3: Clicking 'Get Repair Quote'...")
    if not matcher.wait_and_click("get_repair_quote", timeout=5.0, delay=1.2):
        log.error("'Get Repair Quote' not found in context menu")
        sys.exit(1)

    # --- Step 4: Click "Repair" (Repair All) — unless ship is already fully repaired ---
    log.info("Step 4: Checking for 'already repaired' notice or 'Repair' button...")
    if matcher.find("already_repaired"):
        log.info(
            "Ship already fully repaired — skipping repair steps, proceeding to activation."
        )
    else:
        if not matcher.wait_and_click("repair", timeout=6.0, delay=0.8):
            log.error("'Repair' button not found in repair dialog")
            sys.exit(1)

        # --- Step 5: Confirm with "Yes" ---
        log.info("Step 5: Clicking 'Yes' to confirm repair...")
        if not matcher.wait_and_click("repair_yes", timeout=5.0, delay=0.6):
            log.warning(
                "'repair_yes' not found — repair may have completed without confirmation"
            )

    log.info("Repair complete. Waiting for dialog to settle...")
    time.sleep(1.0)

    # --- Step 6: Right-click the SAME locked coordinates → Board Ship ---
    log.info(
        "Step 6: Right-clicking ship icon at (%d,%d) to open context menu...",
        ship_cx,
        ship_cy,
    )
    matcher._do_click(ship_cx, ship_cy, button="right", delay=0.8)

    log.info("Step 6b: Clicking 'Board Ship'...")
    if not matcher.wait_and_click("board_ship", timeout=1.0, delay=0.6):
        log.warning("'Board Ship' not found in context menu — falling back to 'Undock'")
        if not matcher.wait_and_click("undock", timeout=1.0, delay=0.6):
            log.error("'Undock' not found either — cannot activate ship")
            sys.exit(1)

    log.info("=== Ship repaired and activated successfully ===")
    sys.exit(0)


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
        self.ship_map = load_ship_mission_map()
        log.info("EVEAutomation ready (dry_run=%s)", dry_run)
        log.info(
            "Templates loaded: %d / %d",
            len(self.matcher.templates),
            len(TEMPLATE_FILES),
        )
        log.info("Mission OCR area: x=%d y=%d w=%d h=%d", *self.mission_area)

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

    def _double_click(self, key: str, **kwargs) -> bool:
        if self.dry_run:
            match = self.matcher.find(key)
            if match:
                log.info(
                    "[DRY RUN] Would double-click '%s' at %s",
                    key,
                    self.matcher.center(match),
                )
                return True
            log.info("[DRY RUN] '%s' not on screen", key)
            return False
        return self.matcher.double_click(key, **kwargs)

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

    def step_request_mission(self) -> bool:
        """
        Try to click 'Request Mission' button.
        If not found, fall back to double-clicking the agent_icon.
        """
        log.info("-- Step 1: Looking for 'Request Mission' button ...")
        found = self._wait_click("request_mission", timeout=5.0)
        if not found:
            log.info(
                "   'Request Mission' not visible — trying agent_icon double-click fallback..."
            )
            if not self._double_click("agent_icon", timeout=5.0):
                log.info("   Agent icon also not found — dialog may already be open.")
            else:
                time.sleep(1.2)
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

        # Click Accept non-blocking — if not found, assume already accepted.
        accepted = self._click("accept")
        if not accepted:
            log.info("   Accept button not found — assuming mission already accepted.")
        time.sleep(0.5)

        # Track / Untrack logic:
        #   If "untrack" is visible → already tracked, skip.
        #   If "track" is visible   → click it.
        #   If neither              → assume past tracking step, continue.
        log.info("-- Step 5: Checking Track state ...")
        screen = self.matcher.screenshot_cv()
        if self.matcher.find("untrack", screen=screen):
            log.info("   'untrack' visible — mission already tracked, skipping.")
        elif self.matcher.wait_for("track", timeout=6.0):
            log.info("   'track' visible — clicking Track.")
            self._click("track")
            time.sleep(0.3)
        else:
            log.info(
                "   Neither 'track' nor 'untrack' found — assuming past tracking step."
            )

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
    print("\n=== Ship → Mission Mapping ===")
    ship_map = load_ship_mission_map()
    for entry in ship_map.get("ship_missions", []):
        loaded = entry["ship_template"] in matcher.templates
        status = "✓ loaded" if loaded else "✗ MISSING template"
        print(
            f"  {entry['ship_template']:<20s} → {', '.join(entry['title_contains'])}  [{status}]"
        )
    print(f"  Default: {ship_map.get('default_ship_template', 'ship_garmur')}")


# -----------------------------------------------------------------------------
# Single-action modes (called by OCRBridge)
# -----------------------------------------------------------------------------


def cmd_click(key: str, dry_run: bool, threshold: float):
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


def cmd_double_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=6.0)
        if match:
            log.info(
                "[DRY RUN] Would double-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_double_click(key, timeout=6.0)
        sys.exit(0 if ok else 1)


def cmd_right_click(key: str, dry_run: bool, threshold: float):
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


def cmd_e_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=6.0)
        if match:
            log.info(
                "[DRY RUN] Would E+left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_e_click(key, timeout=6.0)
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
  python eve_automation.py --double-click agent_icon
  python eve_automation.py --right-click ship_hangar
  python eve_automation.py --repair-ship "Anomic Team - Guristas"
  python eve_automation.py --repair-ship "Anomic Team - Guristas" --dry-run
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
        "--double-click", metavar="KEY", help="Double-click a template then exit"
    )
    parser.add_argument(
        "--right-click", metavar="KEY", help="Right-click a template then exit"
    )
    parser.add_argument(
        "--e-click", metavar="KEY", help="Hold E + left-click a template then exit"
    )
    parser.add_argument(
        "--repair-ship",
        metavar="MISSION_TITLE",
        help="Repair + activate the ship for the given mission title, then exit",
    )
    parser.add_argument(
        "--wait", metavar="KEY", help="Wait for template to appear, then exit"
    )
    parser.add_argument(
        "--wait-gone", metavar="KEY", help="Wait for template to disappear, then exit"
    )
    parser.add_argument(
        "--find-any",
        nargs="+",
        metavar="KEY",
        help="Take one screenshot, check keys in order, print first match and exit 0; exit 1 if none found",
    )
    parser.add_argument(
        "--wait-any",
        nargs="+",
        metavar="KEY",
        help="Poll for any of the given templates; print matched key and exit 0; exit 1 on timeout",
    )
    parser.add_argument(
        "--wait-click",
        metavar="KEY",
        help="Wait for template to appear then left-click it; exit 0 on success, 1 on timeout",
    )
    parser.add_argument("--timeout", type=float, default=8.0)
    parser.add_argument("--threshold", type=float, default=0.80)
    args = parser.parse_args()

    if args.check:
        cmd_check(args.threshold)
        return

    if args.click:
        cmd_click(args.click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "double_click", None):
        cmd_double_click(
            args.double_click, dry_run=args.dry_run, threshold=args.threshold
        )
        return

    if getattr(args, "right_click", None):
        cmd_right_click(
            args.right_click, dry_run=args.dry_run, threshold=args.threshold
        )
        return

    if getattr(args, "e_click", None):
        cmd_e_click(args.e_click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "repair_ship", None):
        cmd_repair_ship(
            args.repair_ship, dry_run=args.dry_run, threshold=args.threshold
        )
        return

    if getattr(args, "find_any", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        screen = matcher.screenshot_cv()
        for key in args.find_any:
            if matcher.find(key, screen=screen):
                print(key)
                sys.exit(0)
        sys.exit(1)

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
            time.sleep(0.2)
        log.warning("'%s' still visible after %.1fs.", args.wait_gone, args.timeout)
        sys.exit(1)

    if getattr(args, "wait_any", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        key, _ = matcher.wait_for_any(args.wait_any, timeout=args.timeout)
        if key:
            print(key)
            sys.exit(0)
        log.warning("None of %s appeared within %.1fs.", args.wait_any, args.timeout)
        sys.exit(1)

    if getattr(args, "wait_click", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        match = matcher.wait_for(args.wait_click, timeout=args.timeout)
        if match:
            cx, cy = matcher.center(match)
            matcher._do_click(cx, cy)
            sys.exit(0)
        log.warning("'%s' did not appear within %.1fs.", args.wait_click, args.timeout)
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
