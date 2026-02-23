import sys
import os
import re
import json
import pytesseract
from PIL import Image, ImageEnhance, ImageFilter
import pyautogui
import numpy as np
import cv2
from typing import Dict, Optional


class EVEMissionReader:

    # Known EVE burner / special ship names to help target extraction
    KNOWN_SHIPS = [
        "Enyo",
        "Ishkur",
        "Jaguar",
        "Wolf",
        "Vengeance",
        "Retribution",
        "Hawk",
        "Harpy",
        "Daredevil",
        "Dramiel",
        "Cruor",
        "Succubus",
        "Worm",
        "Astero",
        "Garmur",
        "Orthrus",
    ]

    def __init__(self, tesseract_path: Optional[str] = None):
        if tesseract_path:
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        else:
            for path in [
                r"C:\Program Files\Tesseract-OCR\tesseract.exe",
                r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
            ]:
                if os.path.exists(path):
                    pytesseract.pytesseract.tesseract_cmd = path
                    break

        self.mission_keywords = {
            "BURNER": ["burner", "anomic"],
            "RECON": ["recon", "reconnaissance"],
            "ANNIHILATION": ["annihilation"],
            "BLOCKADE": ["blockade"],
            "ASSAULT": ["assault"],
            "ENCOUNTER": ["encounter"],
        }

    # ------------------------------------------------------------------
    # Screen capture
    # ------------------------------------------------------------------

    def capture_region(
        self, x: int, y: int, width: int, height: int
    ) -> Optional[Image.Image]:
        try:
            return pyautogui.screenshot(region=(x, y, width, height))
        except Exception as e:
            print(f"Capture error: {e}", file=sys.stderr)
            return None

    # ------------------------------------------------------------------
    # Image preprocessing
    # ------------------------------------------------------------------

    def preprocess_image(self, image: Image.Image) -> Image.Image:
        if image.mode != "L":
            image = image.convert("L")

        image = ImageEnhance.Contrast(image).enhance(2.0)
        image = image.filter(ImageFilter.SHARPEN)

        img_cv = np.array(image)
        _, img_cv = cv2.threshold(img_cv, 150, 255, cv2.THRESH_BINARY)
        img_cv = cv2.medianBlur(img_cv, 1)

        return Image.fromarray(img_cv)

    # ------------------------------------------------------------------
    # OCR
    # ------------------------------------------------------------------

    def extract_text(self, image: Image.Image) -> str:
        try:
            processed = self.preprocess_image(image)
            config = (
                r"--oem 3 --psm 6 "
                r"-c tessedit_char_whitelist="
                r"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                r"0123456789.,!?\'\"- :;()"
            )
            return pytesseract.image_to_string(processed, config=config).strip()
        except Exception as e:
            print(f"OCR error: {e}", file=sys.stderr)
            return ""

    # ------------------------------------------------------------------
    # Text cleaning helpers
    # ------------------------------------------------------------------

    def clean_line(self, s: str) -> str:
        """Remove non-ASCII junk and collapse whitespace."""
        s = re.sub(r"[^\x20-\x7E]", "", s)
        s = re.sub(r"\s+", " ", s)
        return s.strip()

    def clean_location(self, raw: str) -> str:
        """
        Extract system name and security from noisy location line.
        Input:  "- lacation-0.2ROIR-Y(LowSecWarning!)"
        Output: "ROIR-Y -0.2 LowSec"
        """
        raw = self.clean_line(raw)

        # 1. Pull security value first (e.g. -0.2 or 0.4)
        sec_match = re.search(r"-?\d\.\d", raw)
        security = sec_match.group(0) if sec_match else None

        # 2. Sec label
        sec_label = ""
        if re.search(r"low\s*sec", raw, re.I):
            sec_label = "LowSec"
        elif re.search(r"null\s*sec", raw, re.I):
            sec_label = "NullSec"
        elif re.search(r"high\s*sec", raw, re.I):
            sec_label = "HighSec"

        # 3. Strip noise before searching for system name so words like
        #    "Warning", "LowSec", "lacation" don't get matched instead.
        stripped = re.sub(r"-?0\.\d+", "", raw)  # remove security number
        stripped = re.sub(  # remove known noise words
            r"(?i)(low|null|high|sec|warning|location|lacation|division|security)",
            "",
            stripped,
        )
        stripped = re.sub(
            r"[^A-Z0-9\-\s]", "", stripped
        )  # letters, digits, hyphen only
        stripped = re.sub(r"\s+", " ", stripped).strip()

        #    EVE null/low system names look like: ROIR-Y, GE-8JV, J123456
        #    High-sec names are plain words: Jita, Amarr, Dodixie
        sys_match = re.search(
            r"\b([A-Z0-9]{2,6}-[A-Z0-9]{1,4})\b"  # null/low: ROIR-Y
            r"|([A-Z][a-z]{2,15})\b",  # high-sec: Jita
            stripped,
        )
        if sys_match:
            system = sys_match.group(1) or sys_match.group(2)
        else:
            system = "Unknown"

        parts = [p for p in [system, security, sec_label] if p]
        return " ".join(parts)

    def clean_title(self, raw: str) -> str:
        """
        Extract just the mission title portion.
        Input:  "LaraMesi? AnomicTeamObjectives"
        Output: "Anomic Team"
        """
        raw = self.clean_line(raw)

        # Strip anything before the first mission keyword
        keywords_flat = [kw for kws in self.mission_keywords.values() for kw in kws]
        for kw in keywords_flat:
            match = re.search(r"(?i)(" + re.escape(kw) + r"[a-zA-Z\s]*)", raw)
            if match:
                # Insert space before capitals to split CamelCase: AnomicTeam -> Anomic Team
                title = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", match.group(1))
                # Remove trailing noise words
                title = re.sub(
                    r"\s*(objectives?|level|division|security)\s*.*$",
                    "",
                    title,
                    flags=re.I,
                )
                return self.clean_line(title)

        return raw

    def extract_ship_target(self, text: str) -> Optional[str]:
        """
        Find the target ship name from OCR text.
        Handles common OCR typos: Destray, Destray, Desttoy, DestraytheEnyo etc.
        Returns e.g. "Enyo", "Jaguar"
        """
        # Pattern: destroy variant + optional spaces/the + ship name
        pattern = re.compile(r"(?i)des[a-z]{0,5}(?:\s+the\s+|\s+|the)([A-Z][a-zA-Z]+)")
        match = pattern.search(text)
        if match:
            candidate = match.group(1)
            for ship in self.KNOWN_SHIPS:
                if (
                    ship.lower() in candidate.lower()
                    or candidate.lower() in ship.lower()
                ):
                    return ship
            return candidate  # unknown ship, return as read

        # Fallback: direct known-ship scan
        for ship in self.KNOWN_SHIPS:
            if re.search(r"\b" + re.escape(ship) + r"\b", text, re.I):
                return ship

        return None

    # ------------------------------------------------------------------
    # Parsing
    # ------------------------------------------------------------------

    def parse_mission_details(self, text: str) -> Optional[Dict]:
        lines = [self.clean_line(l) for l in text.split("\n") if l.strip()]

        if not lines:
            return None

        raw_title = "Unknown"
        raw_objective = "Unknown"
        raw_location = "Unknown"

        for i, line in enumerate(lines[:15]):
            lower = line.lower()
            for kws in self.mission_keywords.values():
                if any(kw in lower for kw in kws):
                    raw_title = line
                    if i + 3 < len(lines):
                        raw_objective = lines[i + 3]
                    if i + 5 < len(lines):
                        raw_location = lines[i + 5]
                    break
            if raw_title != "Unknown":
                break

        title = self.clean_title(raw_title)
        location = self.clean_location(raw_location)
        target = self.extract_ship_target(text) or "Unknown"
        mission_type = self.classify_mission(raw_title)

        # Extract security from the cleaned location string (e.g. "ROIR-Y -0.2 LowSec")
        # rather than the raw OCR line where the leading zero is often dropped/mangled.
        sec_match = re.search(r"-?\d\.\d+", location)
        security = sec_match.group(0) if sec_match else "Unknown"

        # Objective line is mostly gate/moon noise — ship target is the useful part.
        # Keep it but clean it so Java can log it; real decision uses target field.
        objective = self.clean_line(raw_objective)

        return {
            "title": title,
            "objective": objective,
            "target": target,
            "location": location,
            "security": security,
            "type": mission_type,
            "raw_text": text,
        }

    def classify_mission(self, title: str) -> str:
        lower = title.lower()
        for mission_type, keywords in self.mission_keywords.items():
            if any(kw in lower for kw in keywords):
                return mission_type
        return "OTHER"

    # ------------------------------------------------------------------
    # Dialog detection — pixel colour check
    # ------------------------------------------------------------------

    def is_mission_dialog_open(self, text: str) -> bool:
        """
        Returns True if the OCR text looks like a mission dialog.
        Checks for:
          - Agent name line: two capitalised words followed by "Level"
            e.g. "Lara Mesi Level 4" (OCR may merge to "LaraMesi")
          - OR the word "Location" anywhere in the text
          - OR the word "Objective" anywhere in the text
        """
        lower = text.lower()

        if "location" in lower or "objective" in lower:
            return True

        # Agent name pattern: two runs of letters (possibly merged) + "level"
        if re.search(r"[A-Za-z]{2,}\s?[A-Za-z]{2,}\s*(level|lvl)", lower):
            return True

        return False

    # ------------------------------------------------------------------
    # Main entry
    # ------------------------------------------------------------------

    def read_mission(self, x: int, y: int, width: int, height: int) -> Optional[Dict]:
        image = self.capture_region(x, y, width, height)
        if not image:
            return None

        text = self.extract_text(image)
        if not text:
            return None

        if not self.is_mission_dialog_open(text):
            return None  # no mission dialog detected in OCR text
        return self.parse_mission_details(text)


# ---------------------------------------------------------------------------
# CLI — called by Java OCRBridge
# ---------------------------------------------------------------------------


def main():
    if len(sys.argv) != 5:
        print(json.dumps({"error": "Usage: mission_reader.py x y width height"}))
        sys.exit(1)

    try:
        x, y, width, height = map(int, sys.argv[1:5])
        reader = EVEMissionReader()
        mission = reader.read_mission(x, y, width, height)

        if mission:
            print(json.dumps(mission))
        else:
            print(json.dumps({"error": "No mission detected"}))

    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()
