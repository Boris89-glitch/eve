# ocr/calibrate_mission_area.py
import tkinter as tk
import pyautogui
import json
from pathlib import Path
from PIL import Image, ImageTk

# This machine's screen layout (gitignored). Everything calibrated here is written into it;
# config/layout.example.json is the checked-in template. Java and Python both read layout.json.
LAYOUT_FILE = Path(__file__).resolve().parent.parent / "config" / "layout.json"


def _load_layout_file() -> dict:
    try:
        with open(LAYOUT_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def _save_layout_file(data: dict) -> None:
    LAYOUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(LAYOUT_FILE, "w") as f:
        json.dump(data, f, indent=2)


class CalibrationTool:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("EVE Mission Area Calibrator")
        self.root.attributes("-topmost", True)

        # Calibration variables
        self.x = tk.IntVar(value=444)
        self.y = tk.IntVar(value=90)
        self.width = tk.IntVar(value=890)
        self.height = tk.IntVar(value=200)

        # Last captured screenshot (PIL Image) for pixel picking
        self._last_screenshot = None

        self.setup_ui()
        self.preview_running = False

    def setup_ui(self):
        # Control frame
        control_frame = tk.Frame(self.root)
        control_frame.pack(pady=10)

        tk.Label(control_frame, text="X:").grid(row=0, column=0)
        tk.Entry(control_frame, textvariable=self.x, width=6).grid(row=0, column=1)

        tk.Label(control_frame, text="Y:").grid(row=0, column=2)
        tk.Entry(control_frame, textvariable=self.y, width=6).grid(row=0, column=3)

        tk.Label(control_frame, text="Width:").grid(row=1, column=0)
        tk.Entry(control_frame, textvariable=self.width, width=6).grid(row=1, column=1)

        tk.Label(control_frame, text="Height:").grid(row=1, column=2)
        tk.Entry(control_frame, textvariable=self.height, width=6).grid(row=1, column=3)

        # Buttons
        tk.Button(
            control_frame, text="Capture Preview", command=self.toggle_preview
        ).grid(row=2, column=0, columnspan=2, pady=5)

        tk.Button(control_frame, text="Test OCR", command=self.test_ocr).grid(
            row=2, column=2, columnspan=2, pady=5
        )

        tk.Button(
            control_frame, text="Save Coordinates", command=self.save_coordinates
        ).grid(row=3, column=0, columnspan=4, pady=5)

        # Preview label — click to pick pixel colour
        self.preview_label = tk.Label(self.root, cursor="crosshair")
        self.preview_label.pack()
        self.preview_label.bind("<Button-1>", self.on_preview_click)

        # Colour picker status bar
        picker_frame = tk.Frame(self.root)
        picker_frame.pack(fill=tk.X, padx=10)

        tk.Label(picker_frame, text="Click preview to pick colour →").pack(side=tk.LEFT)

        self.colour_swatch = tk.Label(
            picker_frame, text="        ", bg="#000000", width=8
        )
        self.colour_swatch.pack(side=tk.LEFT, padx=5)

        self.colour_label = tk.Label(
            picker_frame, text="RGB: —   HEX: —   Screen pos: —"
        )
        self.colour_label.pack(side=tk.LEFT)

        tk.Button(
            picker_frame,
            text="Save as detector colour",
            command=self.save_detector_colour,
        ).pack(side=tk.RIGHT, padx=5)

        # Holds the last picked colour for saving
        self._picked_rgb = None
        self._picked_screen_pos = None

        # OCR result
        self.result_text = tk.Text(self.root, height=10, width=60)
        self.result_text.pack(pady=10)

    # ------------------------------------------------------------------
    # Preview
    # ------------------------------------------------------------------

    def update_preview(self):
        if not self.preview_running:
            return

        self._last_screenshot = pyautogui.screenshot(
            region=(self.x.get(), self.y.get(), self.width.get(), self.height.get())
        )

        photo = ImageTk.PhotoImage(self._last_screenshot)
        self.preview_label.config(image=photo)
        self.preview_label.image = photo

        self.root.after(500, self.update_preview)

    def toggle_preview(self):
        self.preview_running = not self.preview_running
        if self.preview_running:
            self.update_preview()

    # ------------------------------------------------------------------
    # Colour picker
    # ------------------------------------------------------------------

    def on_preview_click(self, event):
        if self._last_screenshot is None:
            self.result_text.insert(
                1.0, "Start preview first, then click to pick a colour.\n"
            )
            return

        # event.x / event.y are pixel coords within the preview widget
        img_w, img_h = self._last_screenshot.size
        widget_w = self.preview_label.winfo_width()
        widget_h = self.preview_label.winfo_height()

        # Map widget coords → image coords (image may be smaller than widget)
        px = int(event.x * img_w / max(widget_w, 1))
        py = int(event.y * img_h / max(widget_h, 1))
        px = max(0, min(px, img_w - 1))
        py = max(0, min(py, img_h - 1))

        rgb = self._last_screenshot.getpixel((px, py))[:3]  # drop alpha if present
        hex_colour = "#{:02X}{:02X}{:02X}".format(*rgb)

        # Absolute screen position (for reference when hardcoding the check)
        screen_x = self.x.get() + px
        screen_y = self.y.get() + py

        self._picked_rgb = rgb
        self._picked_screen_pos = (screen_x, screen_y)

        # Update swatch and label
        self.colour_swatch.config(bg=hex_colour)
        self.colour_label.config(
            text=f"RGB: {rgb}   HEX: {hex_colour}   Screen pos: ({screen_x}, {screen_y})"
        )

    def save_detector_colour(self):
        if self._picked_rgb is None:
            self.result_text.insert(
                1.0, "Pick a colour first by clicking the preview.\n"
            )
            return

        r, g, b = self._picked_rgb
        sx, sy = self._picked_screen_pos

        # Load this machine's layout file so we can append to it
        data = _load_layout_file()

        data["dialog_detector"] = {
            "screen_x": sx,
            "screen_y": sy,
            "rgb": [r, g, b],
            "tolerance": 30,
            "comment": "Pixel colour check — red warning text in mission dialog",
        }

        _save_layout_file(data)

        self.result_text.insert(
            1.0,
            f"Saved detector: screen=({sx},{sy}) RGB=({r},{g},{b}) HEX=#{r:02X}{g:02X}{b:02X}\n"
            f"Written to {LAYOUT_FILE}\n",
        )

    # ------------------------------------------------------------------
    # OCR test
    # ------------------------------------------------------------------

    def test_ocr(self):
        from mission_reader import EVEMissionReader

        reader = EVEMissionReader()
        mission = reader.read_mission(
            self.x.get(), self.y.get(), self.width.get(), self.height.get()
        )

        self.result_text.delete(1.0, tk.END)
        if mission:
            self.result_text.insert(
                1.0,
                f"Mission Title: {mission['title']}\n"
                f"Objective: {mission['objective']}\n"
                f"Location: {mission['location']}\n"
                f"\nRaw Text:\n{mission['raw_text']}",
            )
        else:
            self.result_text.insert(1.0, "No mission detected")

    # ------------------------------------------------------------------
    # Save coordinates
    # ------------------------------------------------------------------

    def save_coordinates(self):
        data = _load_layout_file()

        data["mission_area"] = {
            "x": self.x.get(),
            "y": self.y.get(),
            "width": self.width.get(),
            "height": self.height.get(),
        }

        _save_layout_file(data)

        self.result_text.insert(1.0, f"Coordinates saved to {LAYOUT_FILE}\n")

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    app = CalibrationTool()
    app.run()
