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
    "close": "close",  # generic close button — clicked once after declining a faction mission
    "complete_mission": "complete_mission",
    "not_complete": "not_complete",  # mission accepted but not yet completable
    "completed": "completed",  # mission done, ready to turn in
    # Repair flow
    "get_repair_quote": "get_repair_quote",
    "repair": "repair",
    "repair_yes": "repair_yes",
    "already_repaired": "already_repaired",  # "nothing to repair" dialog — skip repair, proceed to activation
    "board_ship": "board_ship",  # context menu item — right-click ship → Board Ship
    "quit_mission": "quit_mission",  # quit/abandon mission button in journal
    # Hangar / navigation
    "undock": "undock",
    "ship_hangar": "ship_hangar",
    # Travel / returning
    "set_destination": "set_destination",
    "stargate": "stargate",
    "warp_to_location": "warp_to_location",
    "activate_gate": "activate_gate",
    "jump": "jump",              # context menu item — right-click stargate → Jump
    "mission_start": "mission_start",  # context menu item — right-click mission gate → Activate Gate
    "keep_at_range": "keep_at_range",  # HUD indicator confirming keep-at-range is active
    "range": "range",  # keep at range button
    "orbiting": "orbiting",            # HUD indicator confirming orbit is active
    "orbit": "orbit",            # orbit target button
    "warp_active": "warp_active",
    "warp_active2": "warp_active2",
    "warp_active3": "warp_active3",
    "dock": "dock",
    "dock_safe": "dock_safe",  # safe dock option in Locations submenu (insta-dock)
    "abort": "abort",          # abort undock button — visible during undock animation
    "undocking": "undocking",  # undocking text — visible during undock animation
    "ok": "ok",
    "fleet_yes": "fleet_yes",
    "fleet_no": "fleet_no",
    # Capsule / self-destruct (ship destroyed recovery)
    "capsule": "capsule",
    "destruct": "destruct",
    "yes_destruct": "yes_destruct",  # self-destruct confirmation "Yes" button
    "eject": "eject",  # "Eject" right-click menu item — only offered in a ship, absent in a pod
    "no_ship": "no_ship",  # tiny module sliver — visible in a ship, missing in a pod (cheap pod pre-check)
    "no_ship2": "no_ship2",  # second module sliver variant — checked alongside no_ship
    # Agent interaction
    "agent_icon": "agent_icon",  # agent portrait — double-click fallback
    "agent_standings": "agent_standings",  # standings info button in agent dialog
    "standings_refused": "standings_refused",  # agent refusal message shown when standing < 2 (no mission given)
    "agent_portrait": "agent_portrait",          # opportunities screen agent portrait — right-click target
    "agent_lara_mesi": "agent_lara_mesi",        # "Agent: Lara Mesi" submenu entry — hover target
    "agent_set_destination": "agent_set_destination",  # "Set Destination" context menu item — click target
    "active": "active",  # 'Active' tab in the Agency window (alt+J) — click before right-clicking the agent portrait
    "enemy": "enemy",          # enemy icon in local chat — indicates hostile in system
    "enemy2": "enemy2",        # alternate enemy icon in local chat
    "warp_bubble1": "warp_bubble1",  # warp bubble on undock — bubble camp indicator
    "warp_bubble2": "warp_bubble2",  # warp bubble variant — bubble camp indicator
    "bubble_text": "bubble_text",    # warp disruption bubble text — checked on warp/jump initiation
    "locations": "locations",  # locations submenu in right-click context menu (hover target)
    "general": "general",      # 'General' overview tab — clicked periodically in space to reset the preset
    # Safe-spot & system-detection templates for enemy evasion
    "jcv_spot": "jcv_spot",      # JCV safe spot bookmark in Locations submenu
    "kly_spot": "kly_spot",      # KLY safe spot bookmark in Locations submenu
    "safe_spot": "safe_spot",    # Generic safe spot bookmark in Locations submenu
    "7rm_gate": "7rm_gate",      # 7RM stargate template
    "kly_system": "kly_system",  # KLY-C0 system name in UI
    "jcv_system": "jcv_system",  # J-CIJV system name in UI
    "kly_next": "kly_next",      # KLY-C0 next-system indicator on route
    "jcv_next": "jcv_next",      # J-CIJV next-system indicator on route
    # Mission dialog detection templates — crop these from the mission dialog window.
    # Name them mission_<target>.png. They are matched against the open dialog to
    # identify the mission type without relying on OCR text parsing.
    "mission_vengeance": "mission_vengeance",  # → ship_garmur
    "mission_enyo": "mission_enyo",  # → ship_garmur
    "mission_hawk": "mission_hawk",  # → ship_garmur
    "mission_jaguar": "mission_jaguar",  # → ship_garmur
    "mission_worm": "mission_worm",  # → ship_worm
    "mission_sansha": "mission_sansha",  # → ship_sansha
    "mission_angel": "mission_angel",  # → ship_angel
    "mission_infested": "mission_infested",  # → ship_sk  (Infested Ruins)
    "mission_scarlet": "mission_scarlet",  # → ship_sk  (Dread Pirate Scarlet, boss Anire)
    "mission_recon1": "mission_recon1",  # → ship_garmur  (Recon part 1)
    # Ship templates — one per ship icon in your hangar
    "ship_garmur": "ship_garmur",
    "ship_worm": "ship_worm",
    "ship_sansha": "ship_sansha",
    "ship_angel": "ship_angel",
    "ship_sk": "ship_sk",
    # EVE-O Preview thumbnails — clicked to switch client focus
    "gravitar": "gravitar",
    "meeshayl_lancer": "meeshayl_lancer",
    # Combat templates
    "launcher": "launcher",
    "kin_damage": "kin_damage",
    "exp_damage": "exp_damage",
    "inf_damage": "inf_damage",
    "target_jaguar": "target_jaguar",
    "target_vengeance": "target_vengeance",
    "target_inquisitor": "target_inquisitor",
    "locked_vengeance": "locked_vengeance",
    "locked_inquisitor": "locked_inquisitor",
    "locked_jaguar": "locked_jaguar",
    "locked_enyo": "locked_enyo",
    "locked_navitas": "locked_navitas",
    "locked_hawk": "locked_hawk",
    "locked_bantam": "locked_bantam",
    "locked_worm": "locked_worm",
    "target_enyo": "target_enyo",
    "target_navitas": "target_navitas",
    "em_damage": "em_damage",
    "target_hawk": "target_hawk",
    "target_bantam": "target_bantam",    # Hawk support frigates — overview row
    "target_sansha": "target_sansha",    # Sansha burner — overview row
    "target_angel": "target_angel",      # Angel burner — overview row
    "target_worm": "target_worm",        # Worm burner — overview row
    "target_hive": "target_hive",        # Infested Ruins — overview row
    "target_hive1": "target_hive1",
    "target_hive2": "target_hive2",
    "target_hive3": "target_hive3",
    "hive": "hive",
    "lock_target": "lock_target",
    "jamed": "jamed",  # ECM jam indicator — Angel combat
    # Dread Pirate Scarlet — boss "Anire"
    "anire": "anire",                # Anire overview/target row — double-click to select, ctrl-click to lock
    "locked_anire": "locked_anire",  # Anire locked-target bracket — gone = destroyed
    "approach": "approach",          # right-click menu "Approach" item — used on locked_anire
    # Recon mission — dock at home station to complete
    "home_station": "home_station",
    # Pod sitting in the station hangar — confirms an emergency redock ended in a pod death
    "pod_station": "pod_station",
    # Faction agent (every-2h kernite check + run)
    "faction_agent": "faction_agent",  # faction agent portrait — double-click to open his convo
    "soe_station": "soe_station",       # SOE station in space — right-click → Dock for the kernite mission
    "kernite": "kernite",               # 'Resources for War' (kernite) offer dialog template — accept
}

# Maps mission dialog template keys → ship template keys.
# Checked in order; first match wins.
MISSION_TEMPLATE_TO_SHIP = [
    # (dialog_key, ship_key, ocr_keywords or None)
    # When ocr_keywords is set, the OCR text must contain at least one keyword
    # (substring, case-insensitive) for the match to be accepted.
    ("mission_recon1",    "ship_garmur", ["recon"]),
    ("mission_vengeance", "ship_garmur", None),
    ("mission_jaguar",    "ship_garmur", None),
    ("mission_enyo",      "ship_garmur", None),
    ("mission_hawk",      "ship_garmur", None),
    ("mission_worm",      "ship_worm",   None),
    ("mission_sansha",    "ship_sansha",  None),
    ("mission_angel",     "ship_angel",   None),
    ("mission_infested",  "ship_sk",     ["infested", "hive", "ruins"]),
    ("mission_scarlet",   "ship_sk",     None),
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


def resolve_ship_via_dialog_templates(
    matcher: "TemplateMatcher", mission_title: str = ""
) -> tuple[Optional[str], Optional[str]]:
    """
    Scan the current screen for mission dialog templates (mission_*).
    Returns (ship_template_key, dialog_key) for the first match,
    or (None, None) if nothing matched.

    When an entry has ocr_keywords, the OCR mission_title must contain at least
    one keyword (substring, case-insensitive) for the match to be accepted.
    """
    screen = matcher.screenshot_cv()
    title_lower = mission_title.lower() if mission_title else ""
    for dialog_key, ship_key, ocr_keywords in MISSION_TEMPLATE_TO_SHIP:
        if dialog_key not in matcher.templates:
            continue  # template image not provided yet — skip silently
        match = matcher.find(dialog_key, screen=screen)
        if match:
            if ocr_keywords and title_lower:
                if not any(kw in title_lower for kw in ocr_keywords):
                    log.warning(
                        "Dialog template '%s' matched visually but OCR text '%s' "
                        "contains none of %s — skipping",
                        dialog_key, mission_title, ocr_keywords,
                    )
                    continue
            log.info("Dialog template matched: '%s' → ship: '%s'", dialog_key, ship_key)
            return ship_key, dialog_key
    return None, None


def resolve_ship_template(mission_target: str, ship_map: dict):
    """
    Fallback: resolve ship from OCR target/title string via ship_mission_map.json.
    Only called when dialog template matching found nothing.

    Returns the ship template key, or None if no rule matched. We deliberately do
    NOT fall back to a default ship — leaving station in a random ship for an
    unrecognised mission is wrong; the caller must quit the mission instead.
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
    log.warning(
        "No dialog template and no OCR rule matched '%s' — cannot pick a ship, "
        "mission must be quit (refusing to use a default ship).",
        mission_target,
    )
    return None


# -----------------------------------------------------------------------------
# TemplateMatcher
# -----------------------------------------------------------------------------


class TemplateMatcher:
    """Loads PNG templates once and exposes find / click / right-click helpers."""

    def __init__(self, templates_dir: Path, threshold: float = 0.83):
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

        # Auto-reject mission templates: any 'mission_reject_*' image in the
        # templates dir is loaded under its filename stem, no registry entry
        # needed. classify_offer matches these to decline an offer immediately
        # (no OCR). Drop a PNG in to add another unwanted mission.
        for ext in (".png", ".jpg", ".bmp"):
            for path in sorted(directory.glob("mission_reject_*" + ext)):
                key = path.stem
                if key in self.templates:
                    continue
                img = cv2.imread(str(path), cv2.IMREAD_COLOR)
                if img is not None:
                    self.templates[key] = img
                    log.debug("Loaded auto-reject template '%s' (%s)", key, path.name)

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
        log.debug("'%s' not found (best=%.3f < threshold %.3f)", key, max_val, th)
        return None

    def find_all(
        self,
        key: str,
        screen: Optional[np.ndarray] = None,
        threshold: Optional[float] = None,
    ) -> list[Tuple[int, int, int, int]]:
        """Return list of (x, y, w, h) for ALL matches above threshold.
        Uses non-max suppression to avoid overlapping detections."""
        template = self.templates.get(key)
        if template is None:
            log.debug("Template '%s' not loaded — skipping.", key)
            return []
        if screen is None:
            screen = self.screenshot_cv()
        th = threshold if threshold is not None else self.threshold
        h, w = template.shape[:2]
        result = cv2.matchTemplate(screen, template, cv2.TM_CCOEFF_NORMED)
        locs = np.where(result >= th)
        # Collect all candidates
        candidates = []
        for pt_y, pt_x in zip(*locs):
            candidates.append((int(pt_x), int(pt_y), w, h, float(result[pt_y, pt_x])))
        if not candidates:
            log.info("find_all('%s'): no matches (threshold %.3f)", key, th)
            return []
        # Non-max suppression: sort by confidence descending, discard overlapping
        candidates.sort(key=lambda c: c[4], reverse=True)
        kept: list[Tuple[int, int, int, int]] = []
        for cx, cy, cw, ch, conf in candidates:
            # Skip if too close to an already-kept match (within half template size)
            too_close = False
            for kx, ky, kw, kh in kept:
                if abs(cx - kx) < cw * 0.5 and abs(cy - ky) < ch * 0.5:
                    too_close = True
                    break
            if not too_close:
                kept.append((cx, cy, cw, ch))
                log.debug("find_all('%s'): kept match at (%d,%d) conf=%.3f", key, cx, cy, conf)
        log.info("find_all('%s'): found %d distinct matches", key, len(kept))
        return kept

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
        """Hold E, left-click twice at (cx, cy), release E. Used for jump/warp interactions."""
        VK_E = 0x45
        SCAN_E = 0x12  # hardware scan code for E
        try:
            import win32api, win32con, win32gui

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            # Ensure the EVE window under the cursor has focus so keybd_event registers.
            hwnd = win32gui.WindowFromPoint((cx, cy))
            if hwnd:
                win32gui.SetForegroundWindow(hwnd)
                time.sleep(0.1)
            win32api.keybd_event(VK_E, SCAN_E, 0, 0)          # key down
            time.sleep(0.15)
            # Verify E is held before first click
            e_state1 = win32api.GetAsyncKeyState(VK_E)
            log.info("E key state before click 1: %s (held=%s)", e_state1, e_state1 < 0)
            # First click
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            time.sleep(0.05)
            # Verify E is held before second click
            e_state2 = win32api.GetAsyncKeyState(VK_E)
            log.info("E key state before click 2: %s (held=%s)", e_state2, e_state2 < 0)
            # Second click (key still held)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_E, SCAN_E, win32con.KEYEVENTF_KEYUP, 0)  # key up
            log.info("win32 E+left-clicked (x2) at (%d,%d) [key_states: %s, %s]", cx, cy, e_state1, e_state2)
        except ImportError:
            pyautogui.keyDown('e')
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            pyautogui.keyUp('e')
            log.info("pyautogui E+left-clicked (x2) at (%d,%d)", cx, cy)
        time.sleep(delay)

    def _do_w_click(self, cx: int, cy: int, delay: float = 1.5):
        """Hold W, left-click twice at (cx, cy), release W. Used for keep-at-range commands."""
        VK_W = 0x57
        SCAN_W = 0x11  # hardware scan code for W
        try:
            import win32api, win32con

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            win32api.keybd_event(VK_W, SCAN_W, 0, 0)          # key down
            time.sleep(0.15)
            # First click
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            # Second click (key still held)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_W, SCAN_W, win32con.KEYEVENTF_KEYUP, 0)  # key up
            log.info("win32 W+left-clicked (x2) at (%d,%d)", cx, cy)
        except ImportError:
            pyautogui.keyDown('w')
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            pyautogui.keyUp('w')
            log.info("pyautogui W+left-clicked (x2) at (%d,%d)", cx, cy)
        time.sleep(delay)

    def _do_q_click(self, cx: int, cy: int, delay: float = 1.5):
        """Hold Q, left-click twice at (cx, cy), release Q. Used for orbit commands."""
        VK_Q = 0x51
        SCAN_Q = 0x10  # hardware scan code for Q
        try:
            import win32api, win32con

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            win32api.keybd_event(VK_Q, SCAN_Q, 0, 0)          # key down
            time.sleep(0.15)
            # Verify Q is held before first click
            q_state1 = win32api.GetAsyncKeyState(VK_Q)
            log.info("Q key state before click 1: %s (held=%s)", q_state1, q_state1 < 0)
            # First click
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            # Verify Q is held before second click
            q_state2 = win32api.GetAsyncKeyState(VK_Q)
            log.info("Q key state before click 2: %s (held=%s)", q_state2, q_state2 < 0)
            # Second click (key still held)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_Q, SCAN_Q, win32con.KEYEVENTF_KEYUP, 0)  # key up
            log.info("win32 Q+left-clicked (x2) at (%d,%d) [key_states: %s, %s]", cx, cy, q_state1, q_state2)
        except ImportError:
            pyautogui.keyDown('q')
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            pyautogui.keyUp('q')
            log.info("pyautogui Q+left-clicked (x2) at (%d,%d)", cx, cy)
        time.sleep(delay)

    def _do_ctrl_click(self, cx: int, cy: int, delay: float = 0.3):
        """Hold Ctrl, left-click at (cx, cy), release Ctrl. Used for locking targets."""
        VK_CONTROL = 0x11
        try:
            import win32api, win32con

            win32api.SetCursorPos((cx, cy))
            time.sleep(0.05)
            win32api.keybd_event(VK_CONTROL, 0, 0, 0)          # key down
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, cx, cy, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_CONTROL, 0, win32con.KEYEVENTF_KEYUP, 0)  # key up
            log.info("win32 Ctrl+left-clicked at (%d,%d)", cx, cy)
        except ImportError:
            pyautogui.keyDown('ctrl')
            pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.05)
            pyautogui.click(cx, cy)
            pyautogui.keyUp('ctrl')
            log.info("pyautogui Ctrl+left-clicked at (%d,%d)", cx, cy)
        time.sleep(delay)

    def _do_key_press(self, keys: str, delay: float = 0.3):
        """Press each character in *keys* sequentially."""
        try:
            import win32api, win32con

            for k in keys:
                vk = ord(k.upper())
                win32api.keybd_event(vk, 0, 0, 0)
                time.sleep(0.05)
                win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
                time.sleep(0.1)
            log.info("win32 key-pressed '%s'", keys)
        except ImportError:
            for k in keys:
                pyautogui.press(k)
                time.sleep(0.1)
            log.info("pyautogui key-pressed '%s'", keys)
        time.sleep(delay)

    def _do_shift_key_press(self, keys: str, delay: float = 0.3):
        """Hold Shift and press each character in *keys* sequentially."""
        VK_SHIFT = 0x10
        try:
            import win32api, win32con

            win32api.keybd_event(VK_SHIFT, 0, 0, 0)  # Shift down
            time.sleep(0.05)
            for k in keys:
                vk = ord(k.upper())
                win32api.keybd_event(vk, 0, 0, 0)
                time.sleep(0.05)
                win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
                time.sleep(0.1)
            win32api.keybd_event(VK_SHIFT, 0, win32con.KEYEVENTF_KEYUP, 0)  # Shift up
            log.info("win32 Shift+key-pressed '%s'", keys)
        except ImportError:
            pyautogui.keyDown('shift')
            for k in keys:
                pyautogui.press(k)
                time.sleep(0.1)
            pyautogui.keyUp('shift')
            log.info("pyautogui Shift+key-pressed '%s'", keys)
        time.sleep(delay)

    def _do_ctrl_key_press(self, keys: str, delay: float = 0.3):
        """Hold Ctrl and press each character in *keys* sequentially."""
        VK_CONTROL = 0x11
        VK_MAP = {"space": 0x20}
        try:
            import win32api, win32con

            win32api.keybd_event(VK_CONTROL, 0, 0, 0)  # Ctrl down
            time.sleep(0.05)
            for k in keys.split("+"):
                vk = VK_MAP.get(k.lower()) or ord(k.upper())
                win32api.keybd_event(vk, 0, 0, 0)
                time.sleep(0.05)
                win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
                time.sleep(0.1)
            win32api.keybd_event(VK_CONTROL, 0, win32con.KEYEVENTF_KEYUP, 0)  # Ctrl up
            log.info("win32 Ctrl+key-pressed '%s'", keys)
        except ImportError:
            pyautogui.keyDown('ctrl')
            for k in keys.split("+"):
                pyautogui.press(k)
                time.sleep(0.1)
            pyautogui.keyUp('ctrl')
            log.info("pyautogui Ctrl+key-pressed '%s'", keys)
        time.sleep(delay)

    def _do_alt_key_press(self, key: str, delay: float = 0.3):
        """Hold Alt and press a key (e.g. 'f1' for Alt+F1)."""
        VK_MENU = 0x12  # Alt key
        VK_MAP = {"f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73}
        try:
            import win32api, win32con

            vk = VK_MAP.get(key.lower())
            if vk is None:
                vk = ord(key.upper())
            win32api.keybd_event(VK_MENU, 0, 0, 0)  # Alt down
            time.sleep(0.05)
            win32api.keybd_event(vk, 0, 0, 0)
            time.sleep(0.05)
            win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
            time.sleep(0.05)
            win32api.keybd_event(VK_MENU, 0, win32con.KEYEVENTF_KEYUP, 0)  # Alt up
            log.info("win32 Alt+%s sent", key)
        except ImportError:
            pyautogui.hotkey('alt', key)
            log.info("pyautogui Alt+%s sent", key)
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
    def ctrl_click(self, key: str, screen=None, threshold=None, delay: float = 0.3) -> bool:
        """Find template and hold-Ctrl left-click its centre (lock target)."""
        match = self.find(key, screen=screen, threshold=threshold)
        if match:
            cx, cy = self.center(match)
            log.info("Ctrl+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_ctrl_click(cx, cy, delay=delay)
            return True
        log.warning("'%s' not found on screen — cannot Ctrl+click", key)
        return False

    def ctrl_click_all(self, key: str, threshold=None, delay: float = 0.3) -> int:
        """Find ALL matches of template and Ctrl+click each one (lock all targets).
        Returns the number of targets clicked."""
        matches = self.find_all(key, threshold=threshold)
        if not matches:
            log.warning("'%s' not found on screen — cannot Ctrl+click any", key)
            return 0
        for i, match in enumerate(matches):
            cx, cy = self.center(match)
            log.info("Ctrl+click-all '%s' [%d/%d] at (%d,%d)", key, i + 1, len(matches), cx, cy)
            self._do_ctrl_click(cx, cy, delay=delay)
            time.sleep(0.3)  # brief pause between locks
        return len(matches)

    def wait_and_w_click(
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
            log.info("W+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_w_click(cx, cy, delay=delay)
            return True
        log.warning("Could not W+click '%s' within %.1fs — not found", key, timeout)
        return False

    def wait_and_q_click(
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
            log.info("Q+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_q_click(cx, cy, delay=delay)
            return True
        log.warning("Could not Q+click '%s' within %.1fs — not found", key, timeout)
        return False

    def wait_and_ctrl_click(
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
            log.info("Ctrl+left-clicking '%s' at (%d,%d)", key, cx, cy)
            self._do_ctrl_click(cx, cy, delay=delay)
            return True
        log.warning("Could not Ctrl+click '%s' within %.1fs — not found", key, timeout)
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


def quit_mission_and_request_new(matcher) -> bool:
    """
    Abandon the current mission and ask the agent for a new one:
      quit_mission -> Yes (repair_yes) -> request_mission.
    Returns True if the quit button was found and clicked, False otherwise.
    """
    if matcher.wait_and_click("quit_mission", timeout=2.0, delay=1.0):
        log.info("Clicked 'Quit Mission' — confirming with Yes...")
        matcher.wait_and_click("repair_yes", timeout=2.0, delay=1.0)
        log.info("Clicking 'Request Mission' to get a new offer...")
        matcher.wait_and_click("request_mission", timeout=2.0, delay=1.0)
        return True
    log.error("'quit_mission' template not found — cannot abandon mission")
    return False


def repair_ship_flow(matcher: "TemplateMatcher", mission_title: str, dry_run: bool = False) -> dict:
    """
    Full ship select + repair flow for the ship matching mission_title, using a
    caller-supplied matcher (CLI spawn OR resident daemon — the daemon reuses its
    already-loaded templates so there's no per-call process/import cost):
      0. Click Ship Hangar to open the hangar view
      1. Find ship icon via template matching
      2. Right-click ship icon → Get Repair Quote → Repair All → Yes
      3. Right-click ship icon → Board Ship to make it the active ship
    Returns a result dict; does NOT print or exit (the caller surfaces it):
      {"ship_not_found": True} | {"failed": True} | {"ship_template": k[, "dialog_matched": k]}
    """
    ship_map = load_ship_mission_map()

    # Primary: scan the open mission dialog for a known visual template
    ship_template, dialog_matched = resolve_ship_via_dialog_templates(matcher, mission_title)
    if ship_template:
        log.info("Ship identified via dialog template: %s", ship_template)
    else:
        # Fallback: use OCR-parsed target/title string
        log.info(
            "No dialog template matched — falling back to OCR string: '%s'",
            mission_title,
        )
        ship_template = resolve_ship_template(mission_title, ship_map)

    if ship_template is None:
        # No dialog template and no OCR rule matched — we must NOT leave station in
        # a default/random ship. Quit the mission and request a new one instead.
        log.warning(
            "No ship could be resolved for mission '%s' — quitting mission and requesting new one",
            mission_title,
        )
        quit_mission_and_request_new(matcher)
        return {"ship_not_found": True}

    if ship_template not in matcher.templates:
        log.error(
            "Ship template '%s' not loaded — add %s.png to /templates/",
            ship_template,
            ship_template,
        )
        return {"failed": True}

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
            result = {"ship_template": ship_template}
            if dialog_matched:
                result["dialog_matched"] = dialog_matched
            return result
        else:
            log.warning(
                "[DRY RUN] Ship template '%s' not found on screen", ship_template
            )
            return {"failed": True}

    # --- Step 0: Open Ship Hangar (only if the ship isn't already on screen) ---
    # The ship hangar is usually already open — the ship icon is right there. Check for it
    # first; if it's visible the hangar is open, so skip the hangar click and its 1s settle.
    # Only open the hangar when no ship is detected.
    log.info("Step 0: Checking whether ship icon '%s' is already visible...", ship_template)
    ship_match = matcher.find(ship_template)
    if ship_match:
        log.info("Ship icon already visible — hangar open, skipping hangar click.")
    else:
        log.info("Ship not visible — clicking 'Ship Hangar' to open hangar view...")
        if not matcher.wait_and_click("ship_hangar", timeout=1.0, delay=1.0):
            log.error("'Ship Hangar' not found — cannot open hangar")
            return {"failed": True}
        # --- Step 1: Find ship icon now that the hangar is open ---
        log.info("Step 1: Locating ship icon '%s'...", ship_template)
        ship_match = matcher.wait_for(ship_template, timeout=2.0)

    if not ship_match:
        log.warning("Ship icon '%s' not found — quitting mission and requesting new one", ship_template)
        quit_mission_and_request_new(matcher)
        return {"ship_not_found": True}
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
    if not matcher.wait_and_click("get_repair_quote", timeout=2.0, delay=0.6):
        log.error("'Get Repair Quote' not found in context menu")
        return {"failed": True}

    # --- Step 4: Click "Repair" (Repair All) — unless ship is already fully repaired ---
    # The repair-quote dialog needs a server round-trip to populate the "Repair All"
    # button, and sometimes the dialog fails to open at all. Instead of a single fixed
    # wait, instantly re-check for the button; if it isn't there, re-issue the
    # right-click → Get Repair Quote sequence and check again, looping until it shows.
    # Bounded by MAX_REPAIR_ATTEMPTS so a station with no repair facility can't spin
    # forever and block the other clients.
    MAX_REPAIR_ATTEMPTS = 8
    log.info("Step 4: Looking for 'already repaired' notice or 'Repair' button (retrying)...")
    repaired_already = False
    repair_clicked = False
    for attempt in range(1, MAX_REPAIR_ATTEMPTS + 1):
        if matcher.find("already_repaired"):
            log.info(
                "Ship already fully repaired — skipping repair steps, proceeding to activation."
            )
            repaired_already = True
            break
        if matcher.click("repair", delay=0.8):
            log.info("'Repair' button clicked (attempt %d).", attempt)
            repair_clicked = True
            break
        # Button not on screen yet — re-open the quote dialog and check again.
        log.warning(
            "'Repair' button not visible (attempt %d/%d) — re-requesting quote...",
            attempt,
            MAX_REPAIR_ATTEMPTS,
        )
        matcher._do_click(ship_cx, ship_cy, button="right", delay=0.8)
        matcher.wait_and_click("get_repair_quote", timeout=2.0, delay=0.6)

    if not repaired_already and not repair_clicked:
        log.error("'Repair' button not found after %d attempts", MAX_REPAIR_ATTEMPTS)
        return {"failed": True}

    if repair_clicked:
        # --- Step 5: Confirm with "Yes" or "OK" ---
        log.info("Step 5: Clicking 'Yes'/'OK' to confirm repair...")
        confirm_key, confirm_match = matcher.wait_for_any(
            ["repair_yes", "ok"], timeout=2.0
        )
        if confirm_match:
            cx, cy = matcher.center(confirm_match)
            log.info("Confirming repair via '%s' at (%d,%d)", confirm_key, cx, cy)
            matcher._do_click(cx, cy, button="left", delay=0.6)
        else:
            log.warning(
                "Neither 'repair_yes' nor 'ok' found — repair may have completed without confirmation"
            )

    log.info("Repair complete. Waiting for dialog to settle...")
    time.sleep(0.5)

    # --- Step 6: Right-click the SAME locked coordinates → Board Ship ---
    log.info(
        "Step 6: Right-clicking ship icon at (%d,%d) to open context menu...",
        ship_cx,
        ship_cy,
    )
    matcher._do_click(ship_cx, ship_cy, button="right", delay=0.8)

    log.info("Step 6b: Clicking 'Board Ship'...")
    if not matcher.wait_and_click("board_ship", timeout=1.0, delay=0.6):
        log.warning("'Board Ship' not found in context menu — ship may already be active.")

    log.info("=== Ship repaired and activated successfully ===")
    result = {"ship_template": ship_template}
    if dialog_matched:
        result["dialog_matched"] = dialog_matched
    return result


def cmd_repair_ship(mission_title: str, dry_run: bool, threshold: float):
    """CLI/spawn-path entry: build a matcher, run the flow, and preserve the original
    stdout/exit contract (result JSON on stdout, exit 1 on hard failure)."""
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    result = repair_ship_flow(matcher, mission_title, dry_run=dry_run)
    if result.get("failed"):
        sys.exit(1)
    print(json.dumps(result), flush=True)
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
        found = self._wait_click("request_mission", timeout=1.0)
        if not found:
            log.info(
                "   'Request Mission' not visible — trying agent_icon double-click fallback..."
            )
            if not self._double_click("agent_icon", timeout=1.0):
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
        elif self.matcher.wait_for("track", timeout=1.0):
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
        if not self._wait_click("decline", timeout=1.0):
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


def cmd_click(key: str, dry_run: bool, threshold: float, offset_y: int = 0):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    match = matcher.wait_for(key, timeout=2.0)
    if not match:
        log.warning("Could not find '%s' within 2.0s", key)
        sys.exit(1)
    cx, cy = matcher.center(match)
    cy += offset_y
    if dry_run:
        log.info("[DRY RUN] Would left-click '%s' at (%d,%d)", key, cx, cy)
    else:
        log.info("Left-clicking '%s' at (%d,%d)", key, cx, cy)
        matcher._do_click(cx, cy, button="left", delay=0.3)
    sys.exit(0)


def cmd_double_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would double-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_double_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_right_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would right-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_right_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_e_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would E+left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_e_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_ctrl_click_all(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        matches = matcher.find_all(key)
        if matches:
            for i, m in enumerate(matches):
                log.info("[DRY RUN] Would Ctrl+click '%s' [%d] at %s", key, i + 1, matcher.center(m))
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        count = matcher.ctrl_click_all(key)
        # Print the count to stdout so Java can read it
        print(str(count), flush=True)
        sys.exit(0 if count > 0 else 1)


def cmd_ctrl_r(dry_run: bool):
    if dry_run:
        log.info("[DRY RUN] Would press Ctrl+R (reload modules)")
        sys.exit(0)
    VK_CONTROL = 0x11
    VK_R = 0x52
    try:
        import win32api, win32con
        win32api.keybd_event(VK_CONTROL, 0, 0, 0)          # Ctrl down
        time.sleep(0.05)
        win32api.keybd_event(VK_R, 0, 0, 0)                # R down
        time.sleep(0.05)
        win32api.keybd_event(VK_R, 0, win32con.KEYEVENTF_KEYUP, 0)       # R up
        time.sleep(0.05)
        win32api.keybd_event(VK_CONTROL, 0, win32con.KEYEVENTF_KEYUP, 0) # Ctrl up
        log.info("win32 Ctrl+R sent (reload modules)")
    except ImportError:
        import pyautogui
        pyautogui.hotkey('ctrl', 'r')
        log.info("pyautogui Ctrl+R sent (reload modules)")
    sys.exit(0)


def cmd_w_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would W+left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_w_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_q_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would Q+left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_q_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_ctrl_click(key: str, dry_run: bool, threshold: float):
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    if dry_run:
        match = matcher.wait_for(key, timeout=2.0)
        if match:
            log.info(
                "[DRY RUN] Would Ctrl+left-click '%s' at %s", key, matcher.center(match)
            )
            sys.exit(0)
        else:
            log.warning("[DRY RUN] Template '%s' not found", key)
            sys.exit(1)
    else:
        ok = matcher.wait_and_ctrl_click(key, timeout=2.0)
        sys.exit(0 if ok else 1)


def cmd_key_press(keys: str, dry_run: bool):
    if dry_run:
        log.info("[DRY RUN] Would press keys: %s", keys)
        sys.exit(0)
    matcher = TemplateMatcher(TEMPLATES_DIR)
    matcher._do_key_press(keys, delay=0.3)
    sys.exit(0)


def cmd_shift_key_press(keys: str, dry_run: bool):
    if dry_run:
        log.info("[DRY RUN] Would Shift+press keys: %s", keys)
        sys.exit(0)
    matcher = TemplateMatcher(TEMPLATES_DIR)
    matcher._do_shift_key_press(keys, delay=0.3)
    sys.exit(0)


def cmd_ctrl_key_press(keys: str, dry_run: bool):
    if dry_run:
        log.info("[DRY RUN] Would Ctrl+press keys: %s", keys)
        sys.exit(0)
    matcher = TemplateMatcher(TEMPLATES_DIR)
    matcher._do_ctrl_key_press(keys, delay=0.3)
    sys.exit(0)


def cmd_alt_key_press(key: str, dry_run: bool):
    if dry_run:
        log.info("[DRY RUN] Would Alt+press key: %s", key)
        sys.exit(0)
    matcher = TemplateMatcher(TEMPLATES_DIR)
    matcher._do_alt_key_press(key, delay=0.3)
    sys.exit(0)


# -----------------------------------------------------------------------------
# Persistent daemon mode
#
# Java starts ONE long-lived `python eve_automation.py --daemon` process and
# sends one JSON command per line on stdin; the daemon replies with one JSON
# line on stdout ({"ok": bool, "value": ...}). All log output goes to stderr,
# so stdout carries responses only. The matcher (and all templates) are loaded
# ONCE at startup, eliminating the ~1s-per-call interpreter/import cost that
# made every spawn-per-action slow.
# -----------------------------------------------------------------------------

# Snappy post-click pause for plain clicks issued from the daemon (the slow
# part used to be process spawn, not this; kept small but non-zero so EVE
# registers the click).
DAEMON_CLICK_DELAY = 0.15


def _send_ctrl_r():
    VK_CONTROL = 0x11
    VK_R = 0x52
    try:
        import win32api, win32con
        win32api.keybd_event(VK_CONTROL, 0, 0, 0)
        time.sleep(0.05)
        win32api.keybd_event(VK_R, 0, 0, 0)
        time.sleep(0.05)
        win32api.keybd_event(VK_R, 0, win32con.KEYEVENTF_KEYUP, 0)
        time.sleep(0.05)
        win32api.keybd_event(VK_CONTROL, 0, win32con.KEYEVENTF_KEYUP, 0)
    except ImportError:
        pyautogui.hotkey("ctrl", "r")


def daemon_dispatch(matcher: "TemplateMatcher", reader_box: dict, msg: dict) -> dict:
    """Execute one daemon command and return {"ok": ..., "value": ...}."""
    cmd = msg.get("cmd")
    key = msg.get("key")
    timeout = float(msg.get("timeout", 2.0))

    if cmd == "ping":
        return {"ok": True, "value": "pong"}

    if cmd == "click":
        match = matcher.wait_for(key, timeout=timeout)
        if not match:
            return {"ok": True, "value": False}
        cx, cy = matcher.center(match)
        cy += int(msg.get("offset_y", 0))
        matcher._do_click(cx, cy, button="left", delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}

    if cmd == "click_pos":
        matcher._do_click(int(msg["x"]), int(msg["y"]), button="left", delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}

    if cmd == "right_click_pos":
        matcher._do_click(int(msg["x"]), int(msg["y"]), button="right", delay=0.3)
        return {"ok": True, "value": True}

    if cmd == "drag_pos":
        # Press-hold-drag in empty space: rotates the EVE camera a little, forcing a
        # fresh frame render so a stale/idle-dimmed screen re-paints (helps template
        # detection of set_destination etc.). Does NOT move the ship (that needs a
        # double-click). dx/dy default to a small sideways nudge.
        x, y = int(msg["x"]), int(msg["y"])
        dx, dy = int(msg.get("dx", 40)), int(msg.get("dy", 0))
        pyautogui.moveTo(x, y, duration=0.05)
        pyautogui.mouseDown(button="left")
        pyautogui.moveTo(x + dx, y + dy, duration=0.15)
        pyautogui.mouseUp(button="left")
        log.info("pyautogui drag from (%d,%d) by (%d,%d)", x, y, dx, dy)
        return {"ok": True, "value": True}

    if cmd == "double_click":
        return {"ok": True, "value": matcher.wait_and_double_click(key, timeout=timeout, delay=0.2)}
    if cmd == "right_click":
        return {"ok": True, "value": matcher.wait_and_right_click(key, timeout=timeout, delay=0.2)}
    if cmd == "ctrl_click":
        return {"ok": True, "value": matcher.wait_and_ctrl_click(key, timeout=timeout)}
    if cmd == "e_click":
        return {"ok": True, "value": matcher.wait_and_e_click(key, timeout=timeout)}
    if cmd == "w_click":
        return {"ok": True, "value": matcher.wait_and_w_click(key, timeout=timeout)}
    if cmd == "q_click":
        return {"ok": True, "value": matcher.wait_and_q_click(key, timeout=timeout)}
    if cmd == "ctrl_click_all":
        return {"ok": True, "value": matcher.ctrl_click_all(key)}

    if cmd == "hover":
        match = matcher.wait_for(key, timeout=timeout)
        if not match:
            return {"ok": True, "value": False}
        cx, cy = matcher.center(match)
        try:
            import win32api
            win32api.SetCursorPos((cx, cy))
        except ImportError:
            pyautogui.moveTo(cx, cy, duration=0.1)
        time.sleep(0.3)
        return {"ok": True, "value": True}

    if cmd == "key_press":
        matcher._do_key_press(msg["keys"], delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}
    if cmd == "shift_key_press":
        matcher._do_shift_key_press(msg["keys"], delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}
    if cmd == "ctrl_key_press":
        matcher._do_ctrl_key_press(msg["keys"], delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}
    if cmd == "alt_key_press":
        matcher._do_alt_key_press(msg["key"], delay=DAEMON_CLICK_DELAY)
        return {"ok": True, "value": True}

    if cmd == "ctrl_r":
        _send_ctrl_r()
        return {"ok": True, "value": True}

    if cmd == "pod_check":
        # Two-stage pod detection. Returns True if podded, False if still in a ship.
        # No clicking in empty screen centre — that disrupts combat (the old eject
        # probe right-clicked (960,540) and false-positived on combat clutter).
        #
        # Stage 1 (cheap, passive): the "no_ship"/"no_ship2" templates are tiny module
        # slivers that are only on screen while in a ship. If either is still visible
        # we're definitely in a ship — return immediately.
        #
        # Stage 2 (active): the sliver may false-miss mid-combat (e.g. an overheated
        # module changes its look). So if the sliver is MISSING, right-click the
        # ship/capsule HUD at fixed coordinates (770,860) and look for the "Eject"
        # menu item — offered only from a ship. (The "capsule" HUD template was
        # unreliable, so we probe the fixed location directly instead.) If Eject is
        # present we're still in a ship; if it's missing, we've been podded. After the
        # eject check, the menu is dismissed with a left-click in empty space at screen
        # centre — NOT on the menu itself, which could select an entry (EVE never uses
        # Escape; menus are dismissed with a left-click in space).
        sliver_screen = matcher.screenshot_cv()
        for sliver in ("no_ship", "no_ship2"):
            if matcher.find(sliver, screen=sliver_screen) is not None:
                log.info("pod_check: '%s' sliver visible -> in a ship (skip eject probe)", sliver)
                return {"ok": True, "value": False}  # module sliver visible → in a ship
        px, py = 770, 860
        log.info("pod_check: no_ship/no_ship2 slivers MISSING -> right-click (%d,%d), confirm via eject", px, py)
        matcher._do_click(px, py, button="right", delay=0.3)
        time.sleep(0.3)
        eject_found = matcher.find("eject") is not None
        # Dismiss the menu by clicking away in empty space (screen centre), with a small
        # settle delay so the menu is gone before combat resumes.
        matcher._do_click(960, 540, button="left", delay=0.3)
        log.info("pod_check: eject %s -> verdict %s",
                 "found (in ship)" if eject_found else "MISSING (podded)",
                 "IN SHIP" if eject_found else "PODDED")
        return {"ok": True, "value": (not eject_found)}

    if cmd == "find_any":
        screen = matcher.screenshot_cv()
        for k in msg.get("keys", []):
            if matcher.find(k, screen=screen):
                return {"ok": True, "value": k}
        return {"ok": True, "value": None}

    if cmd == "find_pos":
        match = matcher.find(key)
        if match:
            cx, cy = matcher.center(match)
            return {"ok": True, "value": "%d,%d" % (cx, cy)}
        return {"ok": True, "value": None}

    if cmd == "wait":
        return {"ok": True, "value": matcher.wait_for(key, timeout=timeout) is not None}

    if cmd == "wait_gone":
        deadline = time.time() + timeout
        while time.time() < deadline:
            if matcher.find(key) is None:
                return {"ok": True, "value": True}
            time.sleep(0.2)
        return {"ok": True, "value": False}

    if cmd == "wait_any":
        k, _ = matcher.wait_for_any(msg.get("keys", []), timeout=timeout)
        return {"ok": True, "value": k}

    if cmd == "wait_click":
        match = matcher.wait_for(key, timeout=timeout)
        if match:
            cx, cy = matcher.center(match)
            matcher._do_click(cx, cy, button="left", delay=DAEMON_CLICK_DELAY)
            return {"ok": True, "value": True}
        return {"ok": True, "value": False}

    if cmd == "classify_offer":
        # Template-first mission classification: one screenshot, check the known
        # mission dialog templates. Returns the matched dialog key (e.g.
        # "mission_hawk") or null. ocr_keywords gating is intentionally skipped —
        # the Java caller OCRs the matched offer afterwards for the distance check.
        #
        # The scan is restricted to the offer-dialog region when one is supplied
        # (matchTemplate cost scales with search area — full screen is ~9x slower
        # than the dialog crop). Each find is guarded so a crop too small for a
        # given template silently falls back to the full screen for that key.
        screen = matcher.screenshot_cv()
        search = screen
        region = msg.get("region")
        if region:
            sh, sw = screen.shape[:2]
            x0 = max(0, min(int(region["x"]), sw))
            y0 = max(0, min(int(region["y"]), sh))
            x1 = max(x0, min(int(region["x"]) + int(region["w"]), sw))
            y1 = max(y0, min(int(region["y"]) + int(region["h"]), sh))
            if x1 > x0 and y1 > y0:
                search = screen[y0:y1, x0:x1]
        # Auto-reject templates first: a match means decline immediately (no OCR).
        # Signalled to Java with a 'reject:' prefix on the matched key.
        for reject_key in sorted(k for k in matcher.templates if k.startswith("mission_reject_")):
            try:
                hit = matcher.find(reject_key, screen=search)
            except Exception:
                hit = matcher.find(reject_key, screen=screen)  # crop smaller than template
            if hit:
                return {"ok": True, "value": "reject:" + reject_key}
        for dialog_key, _ship_key, _kw in MISSION_TEMPLATE_TO_SHIP:
            if dialog_key not in matcher.templates:
                continue
            try:
                hit = matcher.find(dialog_key, screen=search)
            except Exception:
                hit = matcher.find(dialog_key, screen=screen)  # crop smaller than template
            if hit:
                return {"ok": True, "value": dialog_key}
        return {"ok": True, "value": None}

    if cmd == "read_mission":
        if "reader" not in reader_box:
            reader_box["reader"] = EVEMissionReader()
        mission = reader_box["reader"].read_mission(
            int(msg["x"]), int(msg["y"]), int(msg["w"]), int(msg["h"])
        )
        return {"ok": True, "value": mission}

    if cmd == "repair_ship":
        # Full repair + activate flow on the resident matcher — no spawn/import cost.
        # value is the result dict: ship_not_found / failed / {ship_template[,dialog_matched]}.
        result = repair_ship_flow(matcher, msg.get("title", ""))
        return {"ok": True, "value": result}

    if cmd == "shutdown":
        return {"ok": True, "value": "bye"}

    return {"ok": False, "error": "unknown cmd: %s" % cmd}


def run_daemon(threshold: float = 0.80, verbose: bool = False):
    # Quiet by default: the per-action INFO echoes (clicks, dialog matched, pod verdict)
    # duplicate the Java-side state logs. Raise to WARNING unless --verbose is passed, so
    # warnings/errors still surface. Java passes --verbose when -Docr.verbose=true.
    if not verbose:
        log.setLevel(logging.WARNING)
    matcher = TemplateMatcher(TEMPLATES_DIR, threshold=threshold)
    reader_box: dict = {}  # lazily holds an EVEMissionReader for OCR commands
    log.info("Daemon ready — %d templates loaded.", len(matcher.templates))
    # Announce readiness on stdout so Java knows the daemon is up.
    sys.stdout.write(json.dumps({"ok": True, "value": "ready"}) + "\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception as e:
            sys.stdout.write(json.dumps({"ok": False, "error": "bad json: %s" % e}) + "\n")
            sys.stdout.flush()
            continue
        try:
            resp = daemon_dispatch(matcher, reader_box, msg)
        except Exception as e:
            log.exception("daemon command failed: %s", msg)
            resp = {"ok": False, "error": str(e)}
        sys.stdout.write(json.dumps(resp, default=str) + "\n")
        sys.stdout.flush()
        if msg.get("cmd") == "shutdown":
            break


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
    parser.add_argument("--daemon", action="store_true",
                        help="Run as a persistent daemon: read JSON commands from stdin, "
                             "reply with JSON on stdout. Templates load once.")
    parser.add_argument("--verbose", action="store_true",
                        help="Daemon: keep INFO-level per-action logs (clicks, dialog match, "
                             "pod verdict). Off by default — only WARNING+ is logged.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--run-once", action="store_true")
    parser.add_argument(
        "--click", metavar="KEY", help="Left-click a template then exit"
    )
    parser.add_argument(
        "--offset-y", type=int, default=0, metavar="PX",
        help="Y offset in pixels applied to --click (positive = downward)",
    )
    parser.add_argument(
        "--double-click", metavar="KEY", help="Double-click a template then exit"
    )
    parser.add_argument(
        "--right-click", metavar="KEY", help="Right-click a template then exit"
    )
    parser.add_argument(
        "--right-click-pos", nargs=2, type=int, metavar=("X", "Y"),
        help="Right-click at fixed screen coordinates then exit"
    )
    parser.add_argument(
        "--click-pos", nargs=2, type=int, metavar=("X", "Y"),
        help="Left-click at fixed screen coordinates then exit"
    )
    parser.add_argument(
        "--hover", metavar="KEY", help="Find template and move mouse to its centre (no click), then exit"
    )
    parser.add_argument(
        "--e-click", metavar="KEY", help="Hold E + left-click a template then exit"
    )
    parser.add_argument(
        "--ctrl-r", action="store_true", help="Hold Ctrl and press R (reload modules), then exit"
    )
    parser.add_argument(
        "--w-click", metavar="KEY", help="Hold W + left-click a template then exit (keep at range)"
    )
    parser.add_argument(
        "--q-click", metavar="KEY", help="Hold Q + left-click a template then exit (orbit)"
    )
    parser.add_argument(
        "--ctrl-click", metavar="KEY", help="Hold Ctrl + left-click a template then exit (lock target)"
    )
    parser.add_argument(
        "--ctrl-click-all", metavar="KEY", help="Find ALL matches of template and Ctrl+click each (lock all targets)"
    )
    parser.add_argument(
        "--key-press", metavar="KEYS", help="Press each character in KEYS sequentially then exit"
    )
    parser.add_argument(
        "--shift-key-press", metavar="KEYS", help="Hold Shift and press each character in KEYS sequentially then exit"
    )
    parser.add_argument(
        "--ctrl-key-press", metavar="KEYS", help="Hold Ctrl and press KEYS (e.g. 'space') then exit"
    )
    parser.add_argument(
        "--alt-key-press", metavar="KEY", help="Hold Alt and press KEY (e.g. 'f1') then exit"
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
        "--find-pos",
        metavar="KEY",
        help="Find template and print 'x,y' centre coordinates to stdout; exit 1 if not found",
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

    if args.daemon:
        run_daemon(threshold=args.threshold, verbose=args.verbose)
        return

    if args.check:
        cmd_check(args.threshold)
        return

    if args.click:
        cmd_click(args.click, dry_run=args.dry_run, threshold=args.threshold, offset_y=args.offset_y)
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

    if getattr(args, "right_click_pos", None):
        x, y = args.right_click_pos
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        if args.dry_run:
            log.info("[DRY RUN] Would right-click at (%d,%d)", x, y)
        else:
            log.info("Right-clicking at (%d,%d)", x, y)
            matcher._do_click(x, y, button="right", delay=0.3)
        sys.exit(0)
        return

    if getattr(args, "click_pos", None):
        x, y = args.click_pos
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        if args.dry_run:
            log.info("[DRY RUN] Would left-click at (%d,%d)", x, y)
        else:
            log.info("Left-clicking at (%d,%d)", x, y)
            matcher._do_click(x, y, button="left", delay=0.3)
        sys.exit(0)
        return

    if getattr(args, "hover", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        match = matcher.wait_for(args.hover, timeout=2.0)
        if not match:
            log.warning("Could not find '%s' within 2.0s for hover", args.hover)
            sys.exit(1)
        cx, cy = matcher.center(match)
        if args.dry_run:
            log.info("[DRY RUN] Would hover '%s' at (%d,%d)", args.hover, cx, cy)
        else:
            log.info("Hovering '%s' at (%d,%d)", args.hover, cx, cy)
            try:
                import win32api
                win32api.SetCursorPos((cx, cy))
            except ImportError:
                pyautogui.moveTo(cx, cy, duration=0.1)
            time.sleep(0.5)
        sys.exit(0)
        return

    if getattr(args, "e_click", None):
        cmd_e_click(args.e_click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "ctrl_r", False):
        cmd_ctrl_r(dry_run=args.dry_run)
        return

    if getattr(args, "w_click", None):
        cmd_w_click(args.w_click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "q_click", None):
        cmd_q_click(args.q_click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "ctrl_click", None):
        cmd_ctrl_click(args.ctrl_click, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "ctrl_click_all", None):
        cmd_ctrl_click_all(args.ctrl_click_all, dry_run=args.dry_run, threshold=args.threshold)
        return

    if getattr(args, "key_press", None):
        cmd_key_press(args.key_press, dry_run=args.dry_run)
        return

    if getattr(args, "shift_key_press", None):
        cmd_shift_key_press(args.shift_key_press, dry_run=args.dry_run)
        return

    if getattr(args, "ctrl_key_press", None):
        cmd_ctrl_key_press(args.ctrl_key_press, dry_run=args.dry_run)
        return

    if getattr(args, "alt_key_press", None):
        cmd_alt_key_press(args.alt_key_press, dry_run=args.dry_run)
        return

    if getattr(args, "repair_ship", None):
        cmd_repair_ship(
            args.repair_ship, dry_run=args.dry_run, threshold=args.threshold
        )
        return

    if getattr(args, "find_pos", None):
        matcher = TemplateMatcher(TEMPLATES_DIR, threshold=args.threshold)
        match = matcher.find(args.find_pos)
        if match:
            cx, cy = matcher.center(match)
            print(f"{cx},{cy}")
            sys.exit(0)
        sys.exit(1)

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
