"""Capture the signed release on an emulator in light and dark themes.

Start scripts/visual-inbox.js first. This deliberately clears app data on the
selected emulator and uses only the fixture's public, fake credentials.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import urllib.request
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = "dev.howaboua.phonedigest"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5556")
parser.add_argument("--theme", choices=["light", "dark", "both"], default="both")
parser.add_argument("--verify-delivery", action="store_true", help="Also verify real FCM, Doze, idle uploads, pause and code omission")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    raise SystemExit("Capture refuses physical devices: it clears emulator app data.")
sdk = Path(os.environ.get("ANDROID_HOME", ROOT / "android/.toolchain/sdk"))
ADB = [str(sdk / "platform-tools/adb"), "-s", args.serial]
OUT = ROOT / "screenshots"
OUT.mkdir(exist_ok=True)
INBOX = os.environ.get("CAPTURE_INBOX_URL", "")
address = urlparse(INBOX)
if (address.scheme != "http" or address.port != 8799 or address.path or address.query or address.fragment
        or address.username or address.password or not address.hostname
        or ipaddress.ip_address(address.hostname) not in ipaddress.ip_network("100.64.0.0/10")):
    raise SystemExit("CAPTURE_INBOX_URL must name the disposable fixture's private tailnet IPv4 address on port 8799.")


def adb(*arguments):
    if arguments and arguments[0] == "shell":
        # ADB joins shell arguments without preserving host-side argv quoting.
        arguments = ("shell", shlex.join(arguments[1:]))
    return subprocess.check_output(ADB + list(arguments), timeout=30, text=True)


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
    # Emulator-console framebuffer capture is outside Android's screenshot API.
    # The signed APK retains FLAG_SECURE and is not modified for this workflow.
    with tempfile.TemporaryDirectory(prefix="phone-inbox-frame-") as directory:
        adb("emu", "screenrecord", "screenshot", directory)
        frames = list(Path(directory).glob("*.png"))
        if len(frames) != 1:
            raise RuntimeError("Emulator did not produce exactly one screenshot")
        (OUT / (name + "-" + theme + ".png")).write_bytes(frames[0].read_bytes())
    print("Captured", name, theme, flush=True)


def fresh_start(theme):
    if "DEBUGGABLE" in adb("shell", "dumpsys", "package", APP):
        raise RuntimeError("Capture requires the installed signed release, not a debug APK")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", APP)
    adb("shell", "cmd", "notification", "disallow_listener", APP + "/.ShadeListener")
    adb("shell", "pm", "clear", APP)
    adb("shell", "cmd", "uimode", "night", "no" if theme == "light" else "yes")
    # Permission and theme changes can finish the old task asynchronously.
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", APP + "/.SetupActivity")
    node("scan_pairing")
    if "SECURE" not in adb("shell", "dumpsys", "window", "windows"):
        raise RuntimeError("Release capture requires the secure app window")


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
    request = urllib.request.Request(INBOX + path,
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


def save_exclusions():
    tap("save_exclusions")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if node("save_exclusions").get("enabled") == "true":
            if not any("Saved." in element.get("text", "") for element in hierarchy().iter("node")):
                raise RuntimeError("Exclusions did not report a successful save")
            return
        time.sleep(0.2)
    raise RuntimeError("Exclusions did not finish saving")


def wait_sent_feedback():
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if not any(element.get("resource-id") == APP + ":id/feedback" for element in hierarchy().iter("node")):
            return
        time.sleep(0.2)
    raise RuntimeError("Send feedback did not settle before navigating")


def verify_exclusions(theme):
    # Shell is a real non-launcher package, discovered through its active notifications.
    # Keep a command locally pending before exclusion, then prove it cannot clear.
    tag = "privacy-before-" + theme
    adb("shell", "cmd", "notification", "post", "-t", "Private test notification", tag, "Keep this on the phone.")
    shade = wait_shade(lambda value: any(tag in item["key"] for item in value["notifications"]), "Privacy fixture was not uploaded before exclusion")
    key = next(item["key"] for item in shade["notifications"] if tag in item["key"])
    tap("pause_clearing")
    if args.verify_delivery:
        queued = relay("/dismiss", {"keys": [key], "reason": "exclusion boundary verification"})
        received = relay()["receivedAt"]
        tap("post_now")
        wait_shade(lambda value: value["receivedAt"] != received, "Paused command was not fetched")
        if key not in adb("shell", "cmd", "notification", "list"):
            raise RuntimeError("Paused privacy fixture was cleared")
        # The main-screen refresh removes the send feedback and moves Settings upward.
        wait_sent_feedback()
    tap("settings")
    tap("excluded_apps")
    enter("app_search", "Shell")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    if node("com.android.shell").get("checked") != "false":
        raise RuntimeError("App exclusion must be opt-in")
    tap("com.android.shell")
    save_exclusions()
    capture("15-excluded-apps", theme)
    wait_shade(lambda value: all(item["package"] != "com.android.shell" for item in value["notifications"])
               and not any(item["key"] == key for item in value["pending"]), "Excluded app remained in the latest inbox or pending queue")
    tap("back")
    tap("back")
    tap("pause_clearing")
    cold_launch()
    node("post_now")
    if key not in adb("shell", "cmd", "notification", "list"):
        raise RuntimeError("Excluded app was cleared by a previously queued command")
    tap("settings")
    tap("excluded_apps")
    enter("app_search", "Shell")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    if node("com.android.shell").get("checked") != "true":
        raise RuntimeError("App exclusion did not survive a cold launch")
    tap("back")
    tap("back")
    # Wait for startup-triggered work to settle, then excluded events must stay local.
    time.sleep(10)
    before = relay("/health")["sequence"]
    hidden_tag = "privacy-new-" + theme
    adb("shell", "cmd", "notification", "post", "-t", "Entirely private", hidden_tag, "No content or metadata may leave.")
    time.sleep(10)
    if relay("/health")["sequence"] != before:
        raise RuntimeError("Excluded notification triggered an upload")
    received = relay()["receivedAt"]
    tap("post_now")
    shared = wait_shade(lambda value: value["receivedAt"] != received, "Manual filtered snapshot did not arrive")
    if any(item["package"] == "com.android.shell" for item in shared["notifications"]):
        raise RuntimeError("Excluded notification content or metadata reached the inbox")
    if hidden_tag in json.dumps(shared):
        raise RuntimeError("Never-shared excluded key leaked through an outcome or metadata")
    wait_sent_feedback()
    tap("settings")
    tap("excluded_apps")
    enter("app_search", "Shell")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("com.android.shell")
    save_exclusions()
    restored = wait_shade(lambda value: any(hidden_tag in item["key"] for item in value["notifications"]), "Removing exclusion did not restore sharing")
    if not any(item["key"] == key for item in restored["notifications"]):
        raise RuntimeError("Stale excluded command cleared the notification after sharing resumed")
    tap("back")
    tap("back")
    print("Exclusions: whole-entry privacy, local event silence, restart and opt-in restoration passed", flush=True)
    if args.verify_delivery:
        print("Exclusions: previously queued command could not clear the private notification", flush=True)


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


# The CLI encoder with fake pairing credentials for the disposable relay.
# Release endpoint validation is unchanged.
with tempfile.TemporaryDirectory(prefix="phone-inbox-capture-") as directory:
    for filename, token in [("phone-inbox-fixture.gif", "capture-only-phone-token"),
                            ("phone-inbox-rejected.gif", "wrong-capture-token")]:
        payload = {"type": "phone-inbox", "version": 1, "endpoint": INBOX, "phoneToken": token}
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
    tap("excluded_apps")
    node("app_search")
    # A choice must be possible before any pairing or notification permission.
    if not any(element.get("checkable") == "true" for element in hierarchy().iter("node")):
        raise RuntimeError("Pre-permission app picker contains no selectable apps")
    enter("app_search", "Clock")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("com.google.android.deskclock")
    save_exclusions()
    capture("16-exclusions-before-setup", theme)
    tap("back")
    cold_launch()
    tap("scan_pairing")
    tap("WHILE USING THE APP")
    time.sleep(3)
    capture("11-camera-scan", theme)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("Enter details manually")
    if node("endpoint").get("text") not in ["", "Inbox address"] or node("phone_token").get("text") not in ["", "Pairing code"]:
        raise RuntimeError("Fresh manual form must not prefill an address or token")
    if any("Include the port" in element.get("text", "") for element in hierarchy().iter("node")):
        raise RuntimeError("Manual form must not show the old port hint")
    capture("01-connect", theme)
    enter("endpoint", INBOX)
    enter("phone_token", "capture-only-phone-token")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("test_connection")
    node("allow_access")
    tap("excluded_apps")
    enter("app_search", "Clock")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    if node("com.google.android.deskclock").get("checked") != "true":
        raise RuntimeError("Pre-permission exclusion did not survive restart and pairing")
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
    if not any(element.get("package") == "com.android.settings" and element.get("text") == "shadr"
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
    # Allow token registration to settle before the permission-step capture.
    time.sleep(10)
    capture("14-bot-requests", theme)
    if args.verify_delivery:
        before = relay("/health")["sequence"]
        time.sleep(20)
        if relay("/health")["sequence"] != before:
            raise RuntimeError("Idle phone uploaded repeatedly with status notifications disabled")
    tap("allow_service_notifications")
    tap("Allow")
    node("continue_setup")
    capture("03-done", theme)
    tap("continue_setup")
    if node("pause_clearing").get("checked") != "false":
        raise RuntimeError("Clearing must work by default after pairing")
    title = "Delivery update " + theme
    adb("shell", "cmd", "notification", "post", "-t", title, "capture-fixture", "Your parcel is ready.")
    # Use fake notification content to settle the connected-status copy.
    shade = wait_shade(lambda shade: any(item["title"] == title for item in shade["notifications"]),
                       "The notification event did not send promptly")
    if shade["status"]["state"] != "ok":
        raise RuntimeError("Notification access did not produce a ready phone snapshot")
    if args.verify_delivery:
        item = next(item for item in shade["notifications"] if item["title"] == title)
        queued = relay("/dismiss", {"keys": [item["key"]], "reason": "capture verification"})
        receipt(queued["requestIds"][item["key"]])
        background_clear(False)
        background_clear(True)
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(10)
        before = relay("/health")["sequence"]
        time.sleep(30)
        if relay("/health")["sequence"] != before:
            raise RuntimeError("Idle phone uploaded without a notification or command")
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
    # Verify the displayed pause choice survives a restart.
    cold_launch()
    if node("pause_clearing").get("checked") != "true":
        raise RuntimeError("Pause did not survive a cold launch")
    if args.verify_delivery:
        paused_title = "Keep this notification " + theme
        adb("shell", "cmd", "notification", "post", "-t", paused_title, "capture-paused", "Only clear after resuming.")
        shade = wait_shade(lambda value: any(item["title"] == paused_title for item in value["notifications"]),
                           "Pausing clearing incorrectly stopped uploads")
        paused_key = next(item["key"] for item in shade["notifications"] if item["title"] == paused_title)
        queued = relay("/dismiss", {"keys": [paused_key], "reason": "pause verification"})
        paused_request_id = queued["requestIds"][paused_key]
        received = relay()["receivedAt"]
        tap("post_now")
        held = wait_shade(lambda value: value["receivedAt"] != received, "Paused phone did not exchange")
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
    if args.verify_delivery:
        receipt(paused_request_id)
        otp_tag = "capture-otp-" + theme
        adb("shell", "cmd", "notification", "post", "-t", "Verification code", otp_tag, "Your OTP is AB7X92.")
        redacted = wait_shade(lambda value: any(otp_tag in item["key"] for item in value["notifications"]),
                             "OTP notification metadata did not reach the relay")
        otp = next(item for item in redacted["notifications"] if otp_tag in item["key"])
        if "AB7X92" in otp["title"] + otp["text"] or "Your OTP" in otp["text"]:
            raise RuntimeError("OTP content reached the relay")
    verify_exclusions(theme)
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
    # Offline failures test guidance without contacting any public or tailnet host.
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        replace("endpoint", "https://relay.example.ts.net:10000")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        tap("test_connection")
        wait_feedback("Can't reach your inbox. Is your computer or server on?")
        capture("08-inbox-unreachable", theme)
        replace("endpoint", "http://relay.example.ts.net:8787")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        tap("test_connection")
        wait_feedback("Can't reach your server. Is Tailscale on and signed into the same account?")
        capture("12-tailscale-unreachable", theme)
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
    print("Captured signed release", theme, "pairing, permission handoffs, status, pause and plain errors.")
