import sys
import os
import json
import pytesseract
from PIL import Image, ImageEnhance, ImageFilter
import pyautogui
import win32gui
import win32con
import numpy as np
import cv2
from typing import Dict, Tuple, Optional


class EVEMissionReader:
    def __init__(self, tesseract_path: Optional[str] = None):
        """Initialize the mission reader with optional Tesseract path"""
        if tesseract_path:
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        else:
            # Common Tesseract installation paths
            common_paths = [
                r"C:\Program Files\Tesseract-OCR\tesseract.exe",
                r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
            ]
            for path in common_paths:
                if os.path.exists(path):
                    pytesseract.pytesseract.tesseract_cmd = path
                    break

        # Mission keywords for classification
        self.mission_keywords = {
            "burner": ["burner", "burners", "anomic", "team"],
            "recon": ["recon", "reconnaissance", "scout"],
            "annihilation": ["annihilation", "annie", "annihilate"],
            "blockade": ["blockade", "blockader"],
            "assault": ["assault"],
            "encounter": ["encounter"],
        }

    def capture_window(self) -> Image.Image:
        """Capture specific screen region"""
        try:
            screenshot = pyautogui.screenshot(region=(440, 92, 988, 336))
            return screenshot
        except Exception as e:
            print(f"Error capturing screen: {e}", file=sys.stderr)
            return None

    def preprocess_image(self, image: Image.Image) -> Image.Image:
        """Preprocess image for better OCR results"""
        # Convert to grayscale
        if image.mode != "L":
            image = image.convert("L")

        # Enhance contrast
        enhancer = ImageEnhance.Contrast(image)
        image = enhancer.enhance(2.0)

        # Apply sharpening filter
        image = image.filter(ImageFilter.SHARPEN)

        # Convert to OpenCV format for advanced preprocessing
        img_cv = np.array(image)

        # Apply thresholding to make text stand out
        _, img_cv = cv2.threshold(img_cv, 150, 255, cv2.THRESH_BINARY)

        # Noise removal
        img_cv = cv2.medianBlur(img_cv, 1)

        # Convert back to PIL
        return Image.fromarray(img_cv)

    def extract_mission_text(self, image: Image.Image) -> str:
        """Extract text from mission dialog using OCR"""
        try:
            # Preprocess image
            processed_image = self.preprocess_image(image)

            # OCR configuration
            custom_config = r"--oem 3 --psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,!?\'\"-:;() "

            # Extract text
            text = pytesseract.image_to_string(processed_image, config=custom_config)

            return text.strip()
        except Exception as e:
            print(f"Error during OCR: {e}", file=sys.stderr)
            return ""

    def parse_mission_details(self, text: str) -> Dict:
        """Parse mission details from OCR text"""
        lines = [line.strip() for line in text.split("\n") if line.strip()]

        if not lines:
            return None

        # Try to find mission title (usually the first few lines)
        title = "Unknown"
        objective = "Unknown"
        rewards = "Unknown"
        location = "Unknown"

        # Look for common mission title patterns
        for i, line in enumerate(lines[:15]):
            lower_line = line.lower()
            for mission_type, keywords in self.mission_keywords.items():
                if any(keyword in lower_line for keyword in keywords):
                    title = line
                    # Next lines might contain objective
                    if i + 1 < len(lines):
                        objective = lines[i + 3]
                    if i + 2 < len(lines):
                        location = lines[i + 5]
                    break
            if title != "Unknown":
                break

        # Determine mission type
        mission_type = self.classify_mission(title)

        return {
            "title": title,
            "location": location,
            "objective": objective,
            "rewards": rewards,
            "type": mission_type,
            "raw_text": text,
        }

    def classify_mission(self, title: str) -> str:
        """Classify mission type based on title"""
        lower_title = title.lower()

        for mission_type, keywords in self.mission_keywords.items():
            if any(keyword in lower_title for keyword in keywords):
                return mission_type.upper()

        return "OTHER"

    def read_mission(self, x: int, y: int, width: int, height: int) -> Dict:
        """Main method to read mission from screen region"""
        # Capture screen region
        image = self.capture_window(x, y, width, height)
        if not image:
            return None

        # Save debug image (optional)
        # image.save(f"debug_mission_{x}_{y}.png")

        # Extract text
        text = self.extract_mission_text(image)

        # Parse mission details
        mission_details = self.parse_mission_details(text)

        return mission_details


def main():
    if len(sys.argv) != 5:
        print("Usage: python mission_reader.py x y width height")
        print(json.dumps({"error": "Invalid arguments"}))
        sys.exit(1)

    try:
        x, y, width, height = map(int, sys.argv[1:5])

        # Initialize mission reader
        reader = EVEMissionReader()

        # Read mission
        mission = reader.read_mission(x, y, width, height)

        if mission:
            # Output as JSON for Java to parse
            print(json.dumps(mission))
        else:
            print(json.dumps({"error": "No mission detected"}))

    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()
