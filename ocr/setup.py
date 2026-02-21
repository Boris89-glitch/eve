import sys
import subprocess
import platform


def install_packages():
    """Install required packages with proper versions for Python 3.13"""

    packages = [
        "pytesseract>=0.3.13",
        "Pillow>=10.4.0",
        "pyautogui>=0.9.54",
        "pywin32>=306",
        "opencv-python>=4.9.0.80",
        "numpy>=1.26.0",
    ]

    print(f"Python version: {platform.python_version()}")
    print("Installing required packages...")

    for package in packages:
        print(f"Installing {package}...")
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", package])
            print(f"✓ {package} installed successfully")
        except subprocess.CalledProcessError as e:
            print(f"✗ Failed to install {package}: {e}")
            return False

    return True


def check_tesseract():
    """Check if Tesseract is installed"""
    import os

    common_paths = [
        r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
    ]

    for path in common_paths:
        if os.path.exists(path):
            print(f"✓ Tesseract found at: {path}")
            return True

    print(
        "✗ Tesseract not found. Please install from: https://github.com/UB-Mannheim/tesseract/wiki"
    )
    return False


if __name__ == "__main__":
    print("=" * 50)
    print("EVE Bot OCR Setup")
    print("=" * 50)

    # Update pip first
    print("\nUpdating pip...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--upgrade", "pip"])

    # Install packages
    if install_packages():
        print("\n✓ All packages installed successfully!")
    else:
        print("\n✗ Some packages failed to install")
        sys.exit(1)

    # Check Tesseract
    print("\nChecking Tesseract OCR...")
    check_tesseract()

    print("\n" + "=" * 50)
    print("Setup complete!")
    print("\nNext steps:")
    print(
        "1. Install Tesseract OCR from: https://github.com/UB-Mannheim/tesseract/wiki"
    )
    print("2. Add Tesseract to your PATH or specify the path in the code")
    print("3. Run the bot with: python mission_reader.py <x> <y> <width> <height>")
    print("=" * 50)
