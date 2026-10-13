# Android visual verification

These PNGs show the debug app on an Android 15 emulator with a Pixel 6 profile at 1080 × 2400. Each screen has a light and dark capture. All credentials and notification content are fake. The development fixture runs the actual relay with disposable D1, never the deployed inbox or its secrets. It is not a substitute for commissioning the real public endpoint.

| Files | Screen |
| --- | --- |
| `00-pairing-start-*.png` | QR-first setup with manual details hidden |
| `01-connect-*.png` | Manual connection after opening the disclosure |
| `02-notification-access-*.png` | Android permission handoff |
| `03-done-*.png` | Setup complete |
| `04-status-*.png` | Main screen after a successful send and cold launch |
| `05-settings-*.png` | Connection settings without app-policy controls |
| `06-paused-*.png` | User-paused clearing while uploads continue |
| `07-connection-error-*.png` | Rejected QR with a plain-language error |
| `08-inbox-unreachable-*.png` | Unreachable inbox with a useful next step |
| `09-large-text-status-*.png` | Main screen at 200% font size |
| `10-large-text-paused-*.png` | Paused main screen at 200% font size |
| `11-camera-scan-*.png` | Camera preview after the Android permission prompt |
| `12-tailscale-unreachable-*.png` | Unreachable tailnet address with Tailscale guidance |
| `13-app-info-*.png` | Android app information opened from restricted-settings help |

The v0.6 captures cover automatic QR connection, manual entry, cold launches, 200% text and Android's notification access and app-information handoffs. Importing a valid QR checks the connection and advances automatically. Rejected QR credentials leave the existing saved connection unchanged. Access credentials are never shown as form fields. Clearing works immediately without an observation period or per-app policy screen.

Camera permission, preview and return to setup are included. Live camera decoding of a physical QR has not been verified. The scanner captures show the emulator's virtual room, not a real camera image.

## Reproduce

Requires a built [debug APK](../docs/android.md), Node, Python 3, Android SDK tools and a running emulator. This procedure clears the app's data on the selected emulator. The capture script refuses physical-device serials.

On this server, the SDK and review AVD are under the ignored `android/.toolchain/` directory. From the project root:

```sh
export ANDROID_HOME="$PWD/android/.toolchain/sdk"
export ANDROID_USER_HOME="$PWD/android/.toolchain/android-home"
export ANDROID_AVD_HOME="$PWD/android/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd phone-digest-review -port 5556 \
  -no-window -no-audio -no-snapshot -gpu swiftshader -memory 2048 -cores 2 \
  -camera-back virtualscene
```

In another terminal, install the APK and start the fake inbox:

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
node scripts/visual-inbox.js
```

Then run the captures from another terminal with `ANDROID_HOME` set:

```sh
python3 scripts/capture-android.py --serial emulator-5556
```

The script reproduces all 28 captures using ADB reverse on port 8799. It generates fake QR images with the CLI's encoder, using a debug-only loopback destination. The production CLI rejects loopback destinations. The script imports the images through Android's picker and checks both automatic connection and rejection without replacing saved credentials. Separate unreachable loopback and tailnet addresses exercise the general and Tailscale-specific error messages. Tailscale on the phone is only for the advanced private-hosting option, never the public HTTPS default.

It also exercises manual connection, Android's camera permission and notification settings, then posts a harmless notification from a system package. Its event-driven upload, real bot dismissal and correlated phone-confirmed receipt go through the actual relay. Pausing must preserve uploads and prevent clearing, survive restart, then permit clearing when resumed. An OTP notification checks that code content is omitted before reaching the relay. Normal font size is restored after the 200% captures. Use `--theme light` or `--theme dark` to capture one theme.

The test uses **Send now** to collect queued commands deterministically. The protocol has no push channel: an idle phone receives bot commands on its next exchange, and Android can delay background work. The app-information link is verified on the emulator, but restricted-settings denial and the exact recovery menu remain real-phone checks.

Review the PNGs for clipping, contrast, keyboard and system-bar overlap, and readable status. The fixture proves the local UI and HTTP path, not Cloudflare Access or Tailscale connectivity. Physical-device background scheduling and live dismissal checks remain in [Android development](../docs/android.md).

Release builds retain `FLAG_SECURE` and reject loopback HTTP. Only debug builds permit these captures and the local fixture. Do not use production credentials in screenshot-enabled builds.

The production-signed v0.6 APK was separately installed on the disposable emulator. Its signature verified, its package was not debuggable and its window carried `SECURE`. A screen capture showed a blank app surface, as intended.
