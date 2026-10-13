# Android visual verification

These PNGs show the debug app on an Android 15 Google APIs emulator with a Pixel 6 profile at 1080 × 2400. Each screen has a light and dark capture. All visible credentials and notification content are fake. The development fixture runs the actual relay with disposable D1 and test bearer tokens. Real Firebase configuration supplies push delivery without connecting the emulator to the deployed phone inbox. This is not a substitute for commissioning the real public endpoint.

| Files | Screen |
| --- | --- |
| `00-pairing-start-*.png` | QR-first setup with a small manual-entry link |
| `01-connect-*.png` | Empty manual form after following the link, without a port hint |
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
| `14-bot-requests-*.png` | Brief status-notification permission for FCM command delivery |

The captures cover automatic QR connection, manual entry, cold launches, 200% text and Android's notification access and app-information handoffs. Importing a valid QR checks the connection and advances automatically. Rejected QR credentials leave the existing saved connection unchanged. Access credentials are never shown as form fields. Clearing works immediately without an observation period or per-app policy screen.

Camera permission, preview and return to setup are included. Live camera decoding of a physical QR has not been verified. The scanner captures show the emulator's virtual room, not a real camera image.

## Reproduce

Requires a built [debug APK](../docs/android.md), Node, Python 3, Android SDK tools, a Google APIs emulator and real [Firebase configuration](../docs/setup.md#firebase-push-delivery). A vanilla AOSP image has no Google Play services and cannot run this FCM flow. This procedure clears the app's data and resets the emulator's DocumentsUI file-picker state before QR imports, avoiding a stale Android 15 layout. The capture script refuses physical-device serials.

On this server, the SDK and review AVD are under the ignored `android/.toolchain/` directory. From the project root:

```sh
export ANDROID_HOME="$PWD/android/.toolchain/sdk"
export ANDROID_USER_HOME="$PWD/android/.toolchain/android-home"
export ANDROID_AVD_HOME="$PWD/android/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd phone-inbox-fcm -port 5556 \
  -no-window -no-audio -no-snapshot -gpu swiftshader -memory 2048 -cores 2 \
  -camera-back virtualscene
```

In another terminal, install the APK and start the fake inbox:

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
node scripts/visual-inbox.js --push-config /private/.env.fcm
```

Then run the captures from another terminal with `ANDROID_HOME` set:

```sh
python3 scripts/capture-android.py --serial emulator-5556
```

Create the private FCM env file with `scripts/configure-push.js --output /private/.env.fcm` and the Firebase JSON input paths. The fixture imports only its two FCM fields, never production bearer tokens or database state.

The script reproduces 30 captures using ADB reverse on port 8799. It generates fake QR images with the CLI's encoder, using a debug-only loopback destination. The production CLI rejects loopback destinations. The script imports the images through Android's picker and checks both automatic connection and rejection without replacing saved credentials. It verifies that the manual form does not prefill fresh or retained loopback fixture settings. With networking disabled, a public HTTPS Funnel address produces plain server guidance, while a private tailnet IP and HTTP `.ts.net` address produce Tailscale guidance. Tailscale on the phone is only for the advanced private-hosting option, never the public HTTPS default.

It also exercises manual connection, Android's camera permission and notification settings, then posts a harmless notification from a system package. Its event-driven upload, real bot dismissal and correlated phone-confirmed receipt go through the actual relay. Pausing must preserve uploads and prevent clearing, survive restart, then permit clearing when resumed. An OTP notification checks that code content is omitted before reaching the relay. Normal font size is restored after the 200% captures. Use `--theme light` or `--theme dark` to capture one theme.

The light-theme run also backgrounds the app, turns the screen off and requires real FCM delivery. It then forces Doze and clears a batch of 20 harmless notifications, checking every correlated `applied` receipt without **Send now**. It measures elapsed time rather than treating Google's accepted-send response as completion. Idle checks cover denied status-notification permission and a ready phone, guarding against repeated token-registration uploads. The app-information link is verified on the emulator, but restricted-settings denial and the exact recovery menu remain real-phone checks.

Review the PNGs for clipping, contrast, keyboard and system-bar overlap, and readable status. The fixture proves the local UI and HTTP path, not Cloudflare Access or Tailscale connectivity. Physical-device background scheduling and live dismissal checks remain in [Android development](../docs/android.md).

Release builds retain `FLAG_SECURE` and reject loopback HTTP. Only debug builds permit these captures and the local fixture. Do not use production credentials in screenshot-enabled builds.

The production-signed v0.6 APK was separately installed on the disposable emulator. Its signature verified, its package was not debuggable and its window carried `SECURE`. A screen capture showed a blank app surface, as intended.
