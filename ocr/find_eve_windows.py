# ocr/find_eve_windows.py
import win32gui
import win32con

def enum_window_callback(hwnd, windows):
    """Callback function to enumerate windows"""
    if win32gui.IsWindowVisible(hwnd):
        window_text = win32gui.GetWindowText(hwnd)
        class_name = win32gui.GetClassName(hwnd)
        
        # Check if this is an EVE Online window
        if "EVE" in window_text or class_name == "EveWindowClass":
            rect = win32gui.GetWindowRect(hwnd)
            windows.append({
                'title': window_text,
                'class': class_name,
                'handle': hwnd,
                'x': rect[0],
                'y': rect[1],
                'width': rect[2] - rect[0],
                'height': rect[3] - rect[1]
            })
    return True

def find_eve_windows():
    """Find all EVE Online client windows"""
    windows = []
    win32gui.EnumWindows(enum_window_callback, windows)
    return windows

if __name__ == "__main__":
    print("Searching for EVE Online windows...")
    eve_windows = find_eve_windows()
    
    if eve_windows:
        print(f"Found {len(eve_windows)} EVE window(s):")
        for i, window in enumerate(eve_windows):
            print(f"\nWindow {i+1}:")
            print(f"  Title: {window['title']}")
            print(f"  Position: ({window['x']}, {window['y']})")
            print(f"  Size: {window['width']}x{window['height']}")
            print(f"  Handle: {window['handle']}")
            
            # Calculate mission dialog area (adjust these percentages as needed)
            mission_x = window['x'] + int(window['width'] * 0.3)
            mission_y = window['y'] + int(window['height'] * 0.2)
            mission_width = int(window['width'] * 0.4)
            mission_height = int(window['height'] * 0.6)
            
            print(f"\n  Mission dialog area (estimated):")
            print(f"  OCR coordinates: {mission_x} {mission_y} {mission_width} {mission_height}")
    else:
        print("No EVE Online windows found. Please start EVE clients first.")