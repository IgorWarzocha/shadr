"""Exercise the debug app on an emulator and capture real light/dark screens.

Start scripts/visual-inbox.js first. This deliberately clears app data on the
selected emulator and uses only the fixture's public, fake credentials.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
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
    if arguments and arguments[0] == "shell":
        # ADB joins shell arguments without preserving host-side argv quoting.
        arguments = ("shell", shlex.join(arguments[1:]))
    return subprocess.check_output(ADB + list(arguments), timeout=30, text=not binary)


def hierarchy():
    for _ in range(3):
        output = adb("shell", "uiautomator", "dump", "/sdcard/phone-inbox-ui.xml")
        if "dumped" in output:
            return ET.fromstring(adb("exec-out", "cat", "/sdcard/phone-inbox-ui.xml"))
        time.sleep(0.5)
    raise RuntimeError("Android could not capture a current UI hierarchy")


def node(identifier, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = hierarchy()
        # Drawers/dialogs follow their partly obscured background in the hierarchy.
        for element in reversed(list(tree.iter("node"))):
            description = element.get("content-desc", "")
            if element.get("resource-id") == APP + ":id/" + identifier or element.get("text", "").casefold() == identifier.casefold() or description == identifier or description.startswith(identifier + ", "):
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


def fresh_start(theme):
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "cmd", "notification", "disallow_listener", APP + "/.ShadeListener")
    adb("shell", "pm", "clear", APP)
    adb("shell", "cmd", "uimode", "night", "no" if theme == "light" else "yes")
    adb("reverse", "tcp:8799", "tcp:8799")
    # Permission and theme changes can finish the old task asynchronously.
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    node("scan_pairing")


def import_qr(filename):
    # Repeated Android 15 picker visits can leave DocumentsUI permanently
    # non-idle. Reset this emulator-only browser before opening each fixture.
    adb("shell", "pm", "clear", "com.google.android.documentsui")
    tap("import_pairing")
    # Android 15 routes GetContent through Photo Picker before its file browser.
    tap("More options")
    tap("Browse…")
    tap("Show roots")
    tap("Downloads")
    tap(filename)


def wait_feedback(fragment):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if fragment.lower() in node("feedback").get("text", "").lower():
            return
        time.sleep(0.2)
    raise RuntimeError("Expected plain connection feedback: " + fragment)


def cold_launch():
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")


def relay(path="/notifications", data=None):
    request = urllib.request.Request("http://127.0.0.1:8799" + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Authorization": "Bearer capture-only-bot-token", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def wait_shade(predicate, explanation, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        shade = relay()
        if predicate(shade):
            return shade
        time.sleep(0.4)
    raise RuntimeError(explanation)


def receipt(request_id):
    return wait_shade(lambda shade: any(entry["requestId"] == request_id and entry["result"] == "applied"
        for entry in shade["dismissalLog"]), "No phone-confirmed dismissal receipt arrived")


def background_clear(doze):
    tag = "fcm-doze" if doze else "fcm-screen-off"
    count = 20 if doze else 1
    for index in range(count):
        adb("shell", "cmd", "notification", "post", "-t", "Harmless push check", tag + "-" + str(index), "Clear this test notification.")
    shade = wait_shade(lambda value: sum(tag in item["key"] for item in value["notifications"]) == count, "Test notifications not uploaded")
    keys = [item["key"] for item in shade["notifications"] if tag in item["key"]]
    try:
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        adb("shell", "dumpsys", "battery", "unplug")
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(3)
        if doze:
            adb("shell", "dumpsys", "deviceidle", "force-idle")
            if "IDLE" != adb("shell", "dumpsys", "deviceidle", "get", "deep").strip():
                raise RuntimeError("Emulator did not enter forced Doze")
        started = time.monotonic()
        result = relay("/dismiss", {"keys": keys, "reason": "background push verification"})
        if result["delivery"]["state"] != "sent":
            raise RuntimeError("Real FCM push was not accepted: " + result["delivery"]["state"])
        ids = set(result["requestIds"].values())
        wait_shade(lambda value: ids <= {entry["requestId"] for entry in value["dismissalLog"] if entry["result"] == "applied"},
                   "Batch did not receive all phone-confirmed applied results")
        print(tag, count, "phone-confirmed applied in", round(time.monotonic() - started, 2), "seconds", flush=True)
    finally:
        adb("shell", "dumpsys", "deviceidle", "unforce")
        adb("shell", "dumpsys", "battery", "reset")
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")


# The same encoder as the CLI, with a debug-only loopback destination. The
# production CLI deliberately rejects loopback because it points at the phone.
with tempfile.TemporaryDirectory(prefix="phone-inbox-capture-") as directory:
    for filename, token in [("phone-inbox-fixture.gif", "capture-only-phone-token"),
                            ("phone-inbox-rejected.gif", "wrong-capture-token")]:
        payload = {"type": "phone-inbox", "version": 1, "endpoint": "http://127.0.0.1:8799", "phoneToken": token}
        encoded = subprocess.check_output(["node", "--input-type=module", "-e",
            "import qr from 'qr'; import {readFileSync} from 'node:fs'; process.stdout.write(qr(readFileSync(0,'utf8'),'gif',{ecc:'medium',border:4,scale:6}));"],
            input=json.dumps(payload).encode(), cwd=ROOT, timeout=30)
        qr_image = Path(directory) / filename
        qr_image.write_bytes(encoded)
        adb("push", str(qr_image), "/sdcard/Download/" + filename)

for theme in (["light", "dark"] if args.theme == "both" else [args.theme]):
    fresh_start(theme)
    if any(element.get("resource-id") in [APP + ":id/endpoint", APP + ":id/phone_token", APP + ":id/access_id", APP + ":id/access_secret"]
           and element.get("bounds") != "[0,0][0,0]" for element in hierarchy().iter("node")):
        raise RuntimeError("Initial pairing screen must hide manual and Access fields")
    manual_link = node("enter_manual")
    if manual_link.get("class") != "android.widget.TextView" or manual_link.get("clickable") != "true":
        raise RuntimeError("Manual entry must use a secondary text link")
    capture("00-pairing-start", theme)
    tap("scan_pairing")
    tap("WHILE USING THE APP")
    time.sleep(3)
    capture("11-camera-scan", theme)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("Enter details manually")
    if node("endpoint").get("text") not in ["", "Server address"] or node("phone_token").get("text") not in ["", "Phone token"]:
        raise RuntimeError("Fresh manual form must not prefill an address or token")
    if any("Include the port" in element.get("text", "") for element in hierarchy().iter("node")):
        raise RuntimeError("Manual form must not show the old port hint")
    capture("01-connect", theme)
    enter("endpoint", "http://127.0.0.1:8799")
    enter("phone_token", "capture-only-phone-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("test_connection")
    node("allow_access")
    # QR import must reach the same next step automatically, with no form submit.
    fresh_start(theme)
    import_qr("phone-inbox-rejected.gif")
    wait_feedback("Try a new QR code")
    cold_launch()
    node("scan_pairing")
    import_qr("phone-inbox-fixture.gif")
    node("allow_access")
    cold_launch()
    node("allow_access")
    capture("02-notification-access", theme)
    node("open_app_settings")
    tap("open_app_settings")
    if not any(element.get("package") == "com.android.settings" and element.get("text") == "Phone inbox"
               for element in hierarchy().iter("node")):
        raise RuntimeError("Restricted-settings recovery did not open this app's information page")
    capture("13-app-info", theme)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("allow_access")
    if not any(element.get("package") == "com.android.settings" for element in hierarchy().iter("node")):
        raise RuntimeError("Allow did not open Android notification access settings")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "cmd", "notification", "allow_listener", APP + "/.ShadeListener")
    node("allow_service_notifications")
    # Before granting permission, a registered token must not create an upload loop.
    time.sleep(10)
    capture("14-bot-requests", theme)
    before = relay("/health")["sequence"]
    time.sleep(20)
    if relay("/health")["sequence"] != before:
        raise RuntimeError("Idle phone uploaded repeatedly with status notifications disabled")
    tap("allow_service_notifications")
    tap("Allow")
    tap("retry_push")
    node("continue_setup")
    capture("03-done", theme)
    tap("continue_setup")
    if node("pause_clearing").get("checked") != "false":
        raise RuntimeError("Clearing must work by default after pairing")
    title = "Delivery update " + theme
    adb("shell", "cmd", "notification", "post", "-t", title, "capture-fixture", "Your parcel is ready.")
    # An event sends immediately without the former one-minute delay or Send now.
    shade = wait_shade(lambda shade: any(item["title"] == title for item in shade["notifications"]),
                       "The notification event did not send promptly")
    if shade["status"]["state"] != "ok":
        raise RuntimeError("Notification access did not produce a ready phone snapshot")
    item = next(item for item in shade["notifications"] if item["title"] == title)
    # com.android.shell is a system app. It must be clearable without an allowlist.
    queued = relay("/dismiss", {"keys": [item["key"]], "reason": "capture verification"})
    if queued["queued"] != [item["key"]] or queued["dryRun"]:
        raise RuntimeError("A new pairing could not queue a real dismissal immediately")
    request_id = queued["requestIds"][item["key"]]
    receipt(request_id)
    if theme == "light":
        background_clear(False)
        background_clear(True)
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(10)
        before = relay("/health")["sequence"]
        time.sleep(30)
        if relay("/health")["sequence"] != before:
            raise RuntimeError("Idle phone uploaded without a notification or command")
        print("Idle registration and ready-phone upload checks passed", flush=True)
    # Granted access must survive a cold launch while the listener rebinds.
    cold_launch()
    node("post_now")
    capture("04-status", theme)
    try:
        adb("shell", "settings", "put", "system", "font_scale", "2.0")
        cold_launch()
        node("post_now")
        node("settings")
        capture("09-large-text-status", theme)
    finally:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
    cold_launch()
    tap("pause_clearing")
    if node("pause_clearing").get("checked") != "true":
        raise RuntimeError("Pause was not enabled")
    capture("06-paused", theme)
    # Pausing must preserve uploads and hold cancellation, including across restart.
    cold_launch()
    if node("pause_clearing").get("checked") != "true":
        raise RuntimeError("Pause did not survive a cold launch")
    paused_title = "Keep this notification " + theme
    adb("shell", "cmd", "notification", "post", "-t", paused_title, "capture-paused", "Only clear after resuming.")
    shade = wait_shade(lambda shade: any(item["title"] == paused_title for item in shade["notifications"]),
                       "Pausing clearing incorrectly stopped uploads")
    paused_key = next(item["key"] for item in shade["notifications"] if item["title"] == paused_title)
    relay("/dismiss", {"keys": [paused_key], "reason": "pause verification"})
    paused_request_id = next(entry["requestId"] for entry in relay()["pending"] if entry["key"] == paused_key)
    received = relay()["receivedAt"]
    tap("post_now")
    held = wait_shade(lambda shade: shade["receivedAt"] != received, "Paused phone did not exchange")
    if not any(item["key"] == paused_key for item in held["notifications"]):
        raise RuntimeError("Paused notification was cleared")
    try:
        adb("shell", "settings", "put", "system", "font_scale", "2.0")
        cold_launch()
        node("pause_clearing")
        capture("10-large-text-paused", theme)
    finally:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
    cold_launch()
    tap("pause_clearing")
    tap("post_now")
    receipt(paused_request_id)
    # Real notification extras traverse the phone's OTP filter before this relay.
    otp_tag = "capture-otp-" + theme
    adb("shell", "cmd", "notification", "post", "-t", "Verification code", otp_tag, "Your OTP is AB7X92.")
    redacted = wait_shade(lambda shade: any(otp_tag in item["key"] for item in shade["notifications"]),
                          "OTP notification metadata did not reach the relay")
    otp = next(item for item in redacted["notifications"] if otp_tag in item["key"])
    if "AB7X92" in otp["title"] + otp["text"] or "Your OTP" in otp["text"]:
        raise RuntimeError("OTP content reached the relay")
    tap("settings")
    capture("05-settings", theme)
    tap("edit_connection")
    import_qr("phone-inbox-rejected.gif")
    wait_feedback("Try a new QR code")
    capture("07-connection-error", theme)
    # A failed replacement QR must leave the previous working connection intact.
    cold_launch()
    node("post_now")
    tap("settings")
    tap("edit_connection")
    tap("Enter details manually")
    preferences = ET.fromstring(adb("shell", "run-as", APP, "cat", "shared_prefs/inbox.xml"))
    saved = {element.get("name"): element.text for element in preferences.findall("string")}
    if saved.get("endpoint") != "http://127.0.0.1:8799" or saved.get("token") != "capture-only-phone-token":
        raise RuntimeError("Failed QR replaced the saved connection")
    if node("endpoint").get("text") not in ["", "Server address"] or node("phone_token").get("text") not in ["", "Phone token"]:
        raise RuntimeError("Retained loopback fixture settings must not prefill the manual form")
    enter("phone_token", "capture-only-phone-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    # Offline failures test guidance without contacting any public or tailnet host.
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        replace("endpoint", "https://relay.example.ts.net:10000")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        tap("test_connection")
        wait_feedback("Can't reach your inbox. Is your computer or server on?")
        capture("08-inbox-unreachable", theme)
        replace("endpoint", "http://100.64.0.1:8787")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        tap("test_connection")
        wait_feedback("Can't reach your server. Is Tailscale on and signed into the same account?")
        capture("12-tailscale-unreachable", theme)
        replace("endpoint", "http://relay.example.ts.net:8787")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        tap("test_connection")
        wait_feedback("Can't reach your server. Is Tailscale on and signed into the same account?")
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
    print("Captured", theme, "automatic pairing, real clearing, pause, OTP omission and plain errors.")
