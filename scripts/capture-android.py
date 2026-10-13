"""Exercise the debug app on an emulator and capture real light/dark screens.

Start scripts/visual-inbox.js first. This deliberately clears app data on the
selected emulator and uses only the fixture's public, fake credentials.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = "dev.howaboua.phonedigest"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5556")
parser.add_argument("--theme", choices=["light", "dark", "both"], default="both")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    raise SystemExit("Capture refuses physical devices: it clears emulator app data.")
sdk = Path(os.environ.get("ANDROID_HOME", ROOT / "android/.toolchain/sdk"))
ADB = [str(sdk / "platform-tools/adb"), "-s", args.serial]
OUT = ROOT / "screenshots"
OUT.mkdir(exist_ok=True)


def adb(*arguments, binary=False):
    return subprocess.check_output(ADB + list(arguments), timeout=30, text=not binary)


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/phone-inbox-ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/phone-inbox-ui.xml"))


def node(identifier, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        # Drawers/dialogs follow their partly obscured background in the hierarchy.
        for element in reversed(list(tree.iter("node"))):
            description = element.get("content-desc", "")
            if element.get("resource-id") == APP + ":id/" + identifier or element.get("text") == identifier or description == identifier or description.startswith(identifier + ", "):
                return element
        time.sleep(0.2)
    raise RuntimeError("UI control not found: " + identifier)


def tap(identifier):
    element = node(identifier)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", element.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def enter(identifier, value):
    tap(identifier)
    adb("shell", "input", "text", value)


def replace(identifier, value):
    tap(identifier)
    adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    adb("shell", "input", "text", value)


def capture(name, theme):
    # Settle insets, keyboard transitions and password-character masking.
    time.sleep(2)
    (OUT / (name + "-" + theme + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


fixture = json.loads((ROOT / "test/fixtures/pairing-v1.json").read_text())
with tempfile.TemporaryDirectory(prefix="phone-inbox-capture-") as directory:
    credentials = Path(directory) / ".dev.vars"
    credentials.write_text('PHONE_TOKEN="' + fixture["phoneToken"] + '"\n')
    credentials.chmod(0o600)
    qr_image = Path(directory) / "phone-inbox-fixture.gif"
    subprocess.run(["node", str(ROOT / "scripts/pair-phone.js"), "--endpoint", fixture["endpoint"],
                    "--credentials", str(credentials), "--output", str(qr_image)], check=True, timeout=30)
    adb("push", str(qr_image), "/sdcard/Download/phone-inbox-fixture.gif")

for theme in (["light", "dark"] if args.theme == "both" else [args.theme]):
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "cmd", "notification", "disallow_listener", APP + "/.ShadeListener")
    adb("shell", "pm", "clear", APP)
    adb("shell", "cmd", "uimode", "night", "no" if theme == "light" else "yes")
    adb("reverse", "tcp:8799", "tcp:8799")
    # Permission and theme changes can finish the old task asynchronously.
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    node("endpoint")
    capture("00-pairing-start", theme)
    tap("import_pairing")
    # Android 15 routes GetContent through Photo Picker before its file browser.
    if any(element.get("package") == "com.android.providers.media.module" for element in hierarchy().iter("node")):
        tap("More options")
        tap("Browse…")
    tap("Show roots")
    tap("Downloads")
    tap("phone-inbox-fixture.gif")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if "Pairing details imported" in node("feedback").get("text", ""):
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("The CLI QR did not import through Android's image-reading path")
    if node("endpoint").get("text") != fixture["endpoint"]:
        raise RuntimeError("Imported QR server address did not match the CLI fixture")
    capture("10-pairing-import", theme)
    # Import must not save credentials or advance setup until explicit confirmation.
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    if node("endpoint").get("text") == fixture["endpoint"]:
        raise RuntimeError("QR import persisted credentials before Test and connect")
    enter("endpoint", "http://127.0.0.1:8799")
    enter("phone_token", "capture-only-phone-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    capture("01-connect", theme)
    tap("test_connection")
    node("allow_access")
    capture("02-notification-access", theme)
    tap("allow_access")
    if not any(element.get("package") == "com.android.settings" for element in hierarchy().iter("node")):
        raise RuntimeError("Allow did not open Android notification access settings")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "cmd", "notification", "allow_listener", APP + "/.ShadeListener")
    node("Open inbox status")
    capture("03-done", theme)
    tap("Open inbox status")
    adb("shell", "cmd", "notification", "post", "-t", "Delivery update", "capture-fixture", "Your parcel is ready.")
    tap("post_now")
    # The background worker's successful post, not seeded preferences, supplies status.
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if "Last post: Not yet" not in node("status").get("text", ""):
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("No successful fixture post reached the status screen")
    request = urllib.request.Request("http://127.0.0.1:8799/capture-state",
                                     headers={"Authorization": "Bearer capture-only-phone-token"})
    with urllib.request.urlopen(request, timeout=10) as response:
        snapshot = json.load(response)["snapshot"]
    if snapshot.get("notificationAccessGranted") is not True:
        raise RuntimeError("The phone did not report its actual granted notification permission")
    # Granted access must survive a cold launch while the listener rebinds.
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    node("post_now")
    capture("04-status", theme)
    try:
        adb("shell", "settings", "put", "system", "font_scale", "2.0")
        adb("shell", "am", "force-stop", APP)
        adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
        node("post_now")
        node("settings")
        capture("09-large-text-status", theme)
    finally:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    tap("settings")
    tap("advanced_policy")
    node("Choose noise apps")
    capture("05-advanced", theme)
    tap("noise_packages")
    node("Noise apps")
    capture("06-noise-apps", theme)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("back")
    tap("edit_connection")
    replace("phone_token", "wrong-capture-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("test_connection")
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if "refused" in node("feedback").get("text", "").lower():
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("Wrong token did not produce a visible connection error")
    capture("07-connection-error", theme)
    tap("back")
    tap("edit_connection")
    replace("endpoint", "https://inbox.example.com")
    replace("phone_token", "capture-only-phone-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("Use Access credentials")
    enter("access_id", "capture-client-id")
    adb("shell", "input", "swipe", "540", "1250", "540", "450", "300")
    enter("access_secret", "capture-client-secret")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    capture("08-cloud-access", theme)
    print("Captured", theme, "setup, status, advanced, app picker, error and Access fields.")
