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

    def capture_region(self, x, y, width, height) -> Optional[Image.Image]:
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
        img_cv = cv2.medianBlur(img_cv, 3)
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
    # Helpers
    # ------------------------------------------------------------------

    def clean_line(self, s: str) -> str:
        s = re.sub(r"[^\x20-\x7E]", "", s)
        return re.sub(r"\s+", " ", s).strip()

    def extract_title_from_line(self, line: str) -> str:
        """
        Extract mission title from the line that follows the agent name line.
        Input:  "7 LaraMesi DuoofDeathObjectives"
        Output: "Duo of Death"
        """
        # Strip leading noise (digits, punctuation)
        line = re.sub(r"^[\W\d]+", "", line).strip()
        # The first token is the agent name (no spaces), title is the rest
        parts = line.split(" ", 1)
        title = parts[1] if len(parts) == 2 else parts[0]
        # Remove trailing "Objectives" or "Objective"
        title = re.sub(r"\s*objectives?\s*$", "", title, flags=re.I).strip()
        # Insert space at every lowercase->uppercase boundary (CamelCase split)
        title = re.sub(r"([a-z])([A-Z])", r"\1 \2", title)
        # Fix OCR-merged prepositions: "Duoof" -> "Duo of", "Heartof" -> "Heart of"
        title = re.sub(
            r"([A-Z][a-z]+)(of|the|and|in|at|to|for|with|a)(?=[A-Z]|\b)",
            r"\1 \2 ",
            title,
            flags=re.I,
        )
        return title.strip()

    def clean_location(self, raw: str) -> str:
        upper = self.clean_line(raw).upper()
        # Strip noise words and security decimal
        upper = re.sub(
            r"\b(LOW|NULL|HIGH|SEC|WARNING|LOCATION|LACATION|DIVISION|SECURITY)\b",
            " ", upper,
        )
        upper = re.sub(r"-?\d+\.\d+", " ", upper)
        upper = re.sub(r"[^A-Z0-9\-\s]", " ", upper)
        upper = re.sub(r"\s+", " ", upper).strip()
        # Null-sec system name: e.g. J0C-E, P33-LV
        null_match = re.search(r"\b([A-Z0-9]{2,6}-[A-Z0-9]{1,4})\b", upper)
        if null_match:
            return null_match.group(1)
        # Named system: e.g. JITA, AMARR
        named_match = re.search(r"\b([A-Z]{3,})\b", upper)
        return named_match.group(1) if named_match else "Unknown"

    def extract_ship_target(self, text: str) -> Optional[str]:
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
            return candidate
        for ship in self.KNOWN_SHIPS:
            if re.search(r"\b" + re.escape(ship) + r"\b", text, re.I):
                return ship
        return None

    def classify_mission(self, title: str) -> str:
        lower = title.lower()
        for mission_type, keywords in self.mission_keywords.items():
            if any(kw in lower for kw in keywords):
                return mission_type
        return "OTHER"

    # ------------------------------------------------------------------
    # Parsing — extracts title from ANY mission, not just burners
    # ------------------------------------------------------------------

    def parse_mission_details(self, text: str) -> Optional[Dict]:
        lines = [self.clean_line(l) for l in text.split("\n") if l.strip()]
        if not lines:
            return None

        title = "Unknown"
        location = "Unknown"
        objective = "Unknown"

        # Find the agent name line: contains "Level" followed by a digit.
        # The very next line contains "AgentName MissionTitleObjectives".
        for i, line in enumerate(lines):
            if re.search(r"level\s*\d", line, re.I):
                if i + 1 < len(lines):
                    title = self.extract_title_from_line(lines[i + 1])
                # Location: find line with "location"/"lacation" keyword; decimal as fallback
                for offset in range(2, 9):
                    if i + offset < len(lines):
                        candidate = lines[i + offset]
                        if re.search(r"locat", candidate, re.I):
                            location = self.clean_location(candidate)
                            break
                        if re.search(r"\d\.\d", candidate):
                            location = self.clean_location(candidate)
                            break
                # Objective is typically 3 lines after agent line
                if i + 3 < len(lines):
                    objective = self.clean_line(lines[i + 3])
                break

        if title == "Unknown":
            return None  # couldn't find agent line at all

        target = self.extract_ship_target(text) or "Unknown"
        mission_type = self.classify_mission(title)
        sec_match = re.search(r"-?\d\.\d+", location)
        security = sec_match.group(0) if sec_match else "Unknown"

        return {
            "title": title,
            "objective": objective,
            "target": target,
            "location": location,
            "security": security,
            "type": mission_type,
            "raw_text": text,
        }

    # ------------------------------------------------------------------
    # Dialog detection
    # ------------------------------------------------------------------

    def is_mission_dialog_open(self, text: str) -> bool:
        lower = text.lower()
        if "location" in lower or "objective" in lower:
            return True
        if re.search(r"[A-Za-z]{2,}\s?[A-Za-z]{2,}\s*(level|lvl)", lower):
            return True
        return False

    # ------------------------------------------------------------------
    # Main entry
    # ------------------------------------------------------------------

    def read_mission(self, x, y, width, height) -> Optional[Dict]:
        image = self.capture_region(x, y, width, height)
        if not image:
            return None
        text = self.extract_text(image)
        if not text:
            return None
        if not self.is_mission_dialog_open(text):
            return None
        return self.parse_mission_details(text)


# ---------------------------------------------------------------------------
# CLI
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
