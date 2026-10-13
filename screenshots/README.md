# Android visual verification

These PNGs show the debug app on an Android 15 emulator with a Pixel 6 profile at 1080 × 2400. Each screen has a light and dark capture. All credentials and notification content are fake. The local fixture does not read the deployed inbox or its secrets.

| Files | Screen |
| --- | --- |
| `00-pairing-start-*.png` | Initial QR and manual connection choices |
| `01-connect-*.png` | Inbox address and masked phone token |
| `02-notification-access-*.png` | Android permission handoff |
| `03-done-*.png` | Setup complete |
| `04-status-*.png` | Main screen after a successful post and cold launch |
| `05-advanced-*.png` | Optional policy settings |
| `06-noise-apps-*.png` | Installed-app picker with icons and protected apps disabled |
| `07-connection-error-*.png` | Rejected token with a visible error |
| `08-cloud-access-*.png` | Optional Access credentials for an HTTPS inbox |
| `09-large-text-status-*.png` | Main screen at 200% font size |
| `10-pairing-import-*.png` | CLI-generated QR image imported as an unsaved draft |
| `11-camera-scan-*.png` | Camera preview after the Android permission prompt |

The v0.5 review passed in both themes, including QR image import, cold launches, 200% main-screen text and the Android settings handoff. Importing a QR does not save credentials before **Test and connect**. The Access secret and connection button remain reachable with the keyboard open. Debug and release unit suites passed with 45 tests each, alongside lint and both builds. Release screenshot blocking was checked on both setup and camera screens using a locally test-signed release build.

Camera permission, preview and return to setup were checked separately. Live camera decoding of a physical QR has not been verified. The scanner captures show the emulator's virtual room, not a real camera image.

## Reproduce

Requires a built [debug APK](../android/README.md), Node, Python 3, Android SDK tools and a running emulator. This procedure clears the app's data on the selected emulator. The capture script refuses physical-device serials.

On this server, the SDK and review AVD are under the ignored `android/.toolchain/` directory. From the project root:

```sh
export ANDROID_HOME="$PWD/android/.toolchain/sdk"
export ANDROID_USER_HOME="$PWD/android/.toolchain/android-home"
export ANDROID_AVD_HOME="$PWD/android/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd phone-digest-review -port 5556 \
  -no-window -no-audio -no-snapshot -gpu swiftshader -memory 2048 -cores 2
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

The script reproduces captures 00 to 10 using ADB reverse on port 8799. It generates a QR from fake credentials and imports it through Android's image picker. It then tests the connection, opens Android's notification access settings, grants access through ADB, posts a fake notification and verifies that the upload reports granted access. It also checks that a cold launch returns to the main screen and restores normal font size after the large-text capture. Use `--theme light` or `--theme dark` to capture one theme.

For capture 11, start the emulator with `-camera-back virtualscene`, open **Scan QR** from the connection screen and approve the camera permission. Capture each theme separately. This preview check does not prove QR decoding through the camera.

Review the PNGs for clipping, contrast, keyboard and system-bar overlap, and readable status. The fixture proves the local UI and HTTP path, not Cloudflare Access or Tailscale connectivity. Physical-device background scheduling and live dismissal checks remain in the Android README.

Release builds retain `FLAG_SECURE` and reject loopback HTTP. Only debug builds permit these captures and the local fixture. Do not use production credentials in screenshot-enabled builds.
