# ocr/calibrate_mission_area.py
import tkinter as tk
import pyautogui
from PIL import Image, ImageTk
import threading
import time


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

        self.setup_ui()
        self.preview_running = False

    def setup_ui(self):
        # Control frame
        control_frame = tk.Frame(self.root)
        control_frame.pack(pady=10)

        # Position controls
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

        # Preview label
        self.preview_label = tk.Label(self.root)
        self.preview_label.pack()

        # OCR result
        self.result_text = tk.Text(self.root, height=10, width=60)
        self.result_text.pack(pady=10)

    def update_preview(self):
        if not self.preview_running:
            return

        # Capture screen region
        screenshot = pyautogui.screenshot(
            region=(self.x.get(), self.y.get(), self.width.get(), self.height.get())
        )

        # Convert to PhotoImage
        photo = ImageTk.PhotoImage(screenshot)
        self.preview_label.config(image=photo)
        self.preview_label.image = photo

        # Schedule next update
        self.root.after(500, self.update_preview)

    def toggle_preview(self):
        self.preview_running = not self.preview_running
        if self.preview_running:
            self.update_preview()

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
                f"Location {mission['location']}\n"
                f"\nRaw Text:\n{mission['raw_text']}",
            )
        else:
            self.result_text.insert(1.0, "No mission detected")

    def save_coordinates(self):
        import json

        with open("mission_coordinates.json", "w") as f:
            json.dump(
                {
                    "mission_area": {
                        "x": self.x.get(),
                        "y": self.y.get(),
                        "width": self.width.get(),
                        "height": self.height.get(),
                    }
                },
                f,
                indent=2,
            )
        self.result_text.insert(1.0, "Coordinates saved to mission_coordinates.json\n")

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    app = CalibrationTool()
    app.run()
