# Signed-release visual verification

These PNGs show the signed v0.7 release on an Android 15 Google APIs emulator with a Pixel 6 profile at 1080 × 2400. The APK is not debuggable and retains `FLAG_SECURE`. Screens are captured through the emulator's supported host-framebuffer command, not Android's screenshot API. No security flags or release endpoint rules are changed.

Captured app source: `54805d6`. APK SHA-256: `d3f210c36841de94cdb0aa1e91fc7b2ff6882dea61a3fe65dfa3a1c1bc9e6dab`.

Each screen has a light and dark capture. Notification content and pairing credentials are fake. The fixture runs the actual relay with disposable D1, test bearer tokens and real Firebase configuration. Its private address is not shown in the captures. It never connects to the deployed phone inbox or reads production bearer credentials or database state.

| Files | Screen |
| --- | --- |
| `00-pairing-start-*.png` | QR-first setup with a secondary manual-entry link |
| `01-connect-*.png` | Empty Inbox address and Pairing code fields |
| `02-notification-access-*.png` | Notification access with numbered restricted-settings help |
| `03-done-*.png` | Setup complete after allowing notifications |
| `04-status-*.png` | Connected main screen with the recovery send button |
| `05-settings-*.png` | Connection and Assistant clearing settings |
| `06-paused-*.png` | Pause assistant clearing enabled |
| `07-connection-error-*.png` | Rejected QR with a plain-language error |
| `08-inbox-unreachable-*.png` | Unreachable example HTTPS inbox |
| `09-large-text-status-*.png` | Main screen at 200% font size |
| `10-large-text-paused-*.png` | Paused main screen at 200% font size |
| `11-camera-scan-*.png` | Camera preview after the permission prompt |
| `12-tailscale-unreachable-*.png` | Unreachable example private hostname |
| `13-app-info-*.png` | Android app information opened from restricted-settings help |
| `14-bot-requests-*.png` | Let your assistant clear notifications, with one primary action and Back |

The focused capture flow checks automatic QR connection, manual entry, cold launches, permission handoffs and the automatic transition after allowing notifications. A fake notification settles the connected-status screen. After a rejected replacement QR, a cold launch must still return to the main screen. Offline error screens use example hostnames, not the fixture's private address. Normal font size and networking are restored afterwards.

The scanner shows the emulator's virtual room. Live camera decoding of a physical QR and Android's exact restricted-settings menu are not verified here. The default run focuses on wording and screenshots. Add `--verify-delivery --theme light` when changing delivery behaviour to check real FCM, a 20-notification forced-Doze batch, idle uploads, pause and authentication-code omission. These checks also passed on this signed release. The user separately reported successful locked-Pixel dismissal in about two seconds.

## Reproduce

Requires the actual [signed release APK](../docs/android.md), Node, Python 3, Android SDK tools, a Google APIs emulator and private [Firebase configuration](../docs/setup.md#firebase-push-delivery). The fixture must bind a tailnet IPv4 address reachable from the emulator. This does not require Tailscale inside the emulator. The capture script rejects physical devices and clears only the selected emulator's app data and DocumentsUI picker state.

On this server, the SDK and review AVD are under the ignored `android/.toolchain/` directory:

```sh
export ANDROID_HOME="$PWD/android/.toolchain/sdk"
export ANDROID_USER_HOME="$PWD/android/.toolchain/android-home"
export ANDROID_AVD_HOME="$PWD/android/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd phone-inbox-fcm -port 5556 \
  -no-window -no-audio -no-snapshot -gpu swiftshader -memory 2048 -cores 2 \
  -camera-back virtualscene
```

Install the signed release on the disposable emulator. If a debug APK with a different signature is installed, uninstall it from this emulator first. Never apply these commands to a physical phone.

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 install -r \
  android/app/build/outputs/apk/release/app-release.apk
node scripts/visual-inbox.js --bind "$(tailscale ip -4)" \
  --push-config /private/.env.fcm
```

In another terminal, run the capture workflow:

```sh
export CAPTURE_INBOX_URL="http://$(tailscale ip -4):8799"
python3 scripts/capture-android.py --serial emulator-5556
```

Create the private FCM env file with `scripts/configure-push.js --output /private/.env.fcm` and the Firebase JSON input paths. The fixture imports only its FCM bindings. Its listener accepts loopback or a tailnet IPv4 bind, never a public or wildcard address. The release capture workflow requires the tailnet address and does not use ADB reverse or debug-only loopback HTTP.

The capture function calls the supported emulator console command below and copies its PNG into the named screenshot file:

```sh
mkdir -p .build/emulator-screenshot
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 emu screenrecord screenshot \
  "$PWD/.build/emulator-screenshot"
```

Use `--theme light` or `--theme dark` for one theme. Review the PNGs for clipping, contrast, keyboard and system-bar overlap. This disposable private fixture is not a commissioning test of the real public endpoint.
