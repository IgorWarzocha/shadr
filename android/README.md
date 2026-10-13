# Phone inbox for Android

The phone uploads its complete active notification shade to your inbox. Private message previews are included. It never serves HTTP or classifies notifications. A bot can request dismissal, but the phone independently protects important packages and refuses anything outside your noise allowlist.

## Build and install

Requires Android SDK platform 35, build-tools 35.0.0 and Java 17 or newer. The checked-in wrapper pins Gradle 8.11.1 and verifies its published SHA-256. The app supports Android 8 and newer. Dependency versions are pinned in the Gradle files.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use a signed release build for ordinary use. Debug builds are debuggable and unsuitable for storing long-lived production credentials.

For debug upgrades, keep the original signing key and `ANDROID_USER_HOME`. Changing that directory can make Gradle generate a different debug key, which Android refuses as an update. The commissioning build in this checkout uses `ANDROID_USER_HOME="$PWD/.toolchain/android-home"` from the `android` directory. Do not uninstall the app to bypass a signing mismatch.

### Sign a release

Keep the release keystore outside the repository in a private directory. Create it once with `keytool`; passwords are entered at its prompts rather than placed in shell history:

```sh
umask 077
keytool -genkeypair -keystore /absolute/private/phone-inbox.jks \
  -alias phone-inbox -keyalg RSA -keysize 3072 -validity 10000
```

Supply `RELEASE_KEYSTORE` as that absolute file path, `RELEASE_KEY_ALIAS` as `phone-inbox`, and `RELEASE_STORE_PASSWORD` and `RELEASE_KEY_PASSWORD` through your private build environment or secret manager. Set all four variables or none. Do not put passwords in source, shared logs or command-line arguments. The build rejects incomplete configuration and missing keystore files.

Run `./gradlew :app:assembleRelease`. With the four variables supplied, the signed APK is `app/build/outputs/apk/release/app-release.apk`. Without them, the build intentionally produces `app-release-unsigned.apk`, which is not installable until signed. Preserve the same private key for every release upgrade. A production release key cannot update an existing debug-signed commissioning install; do not change or discard its signing identity implicitly.

## Connect your phone

Pairing QR codes contain your phone credentials. Keep the QR and any saved image private. Delete saved pairing images after use. Camera permission is requested only when you choose to scan. Importing an image uses Android's picker and needs no storage permission.

1. Choose **Scan QR** to scan the code shown on your computer, or **Import QR image** to select a saved QR screenshot. Imported details remain an editable draft. Nothing is saved or contacted until you press **Test and connect**. You can also enter the **Server address** and **Phone token** manually. Do not append `/notifications`. The scheme is optional: recognised tailnet IPs in `100.64.0.0/10` and `.ts.net` hostnames default to HTTP, other addresses to HTTPS. Explicit schemes are preserved. Connect Tailscale first for tailnet addresses.
2. If your HTTPS inbox uses Cloudflare Access, turn on **Use Access credentials** and enter both service-token fields. Ordinary HTTPS needs only the phone token.
3. Press **Test and connect**. The app checks authenticated `GET /health` and saves only after a bounded JSON response identifies the role as `phone`. A bot token or login page is not accepted. This test uploads no notifications.
4. Allow **Phone inbox** in Android's notification access settings, return to the app and finish setup.
5. The main screen shows connection access, the last successful post, items waiting and dry-run state. **Post now** queues a real upload.

**Settings > Advanced** contains live dismissal and app pickers for noise, extra protection and digit redaction. Apps are shown by name and icon. There is no package-name entry. Noise starts empty. Known banks and authenticators, all system apps and device administrators are always protected and cannot be selected as noise. The app uses your phone's current system time zone, including after travel. Previous manual time-zone overrides are removed on upgrade.

Upgrades retain credentials and custom protection. Existing noise entries remain usable only if current protection allows them. Default cross-app code redaction applies immediately, without a policy-review checkbox. The application identity is unchanged, so `adb install -r` upgrades an existing installation.

The app follows the system light or dark theme. Release builds block screenshots of the app. Debug builds allow screenshots for emulator checks and permit loopback HTTP for local fixtures. Release validation rejects loopback HTTP.

Settings and the acknowledgement queue are kept in app-private storage, with Android backup disabled. They are not separately encrypted by this app. Anyone who controls the unlocked device, debugging session or operating system may read previews and credentials. Do not share screenshots of the setup screen.

The pairing format is a JSON object with `type: "phone-inbox"`, integer `version: 1`, an absolute `endpoint` URL and `phoneToken`. Optional `accessClientId` and `accessClientSecret` must both be present and nonblank, and are accepted only for HTTPS cloud connections. Unknown or duplicate fields are rejected. The complete payload is limited to 2,048 UTF-8 bytes. Images must be at most 16 MB and contain one clear QR code. Unsupported or invalid codes show an error without replacing the current draft. The QR format does not prove which role a token has: the authenticated connection test must confirm the phone role before saving.

The app requests visibility of launcher apps and several system packages to resolve app names. If Android hides a package label, the package name is uploaded instead. It does not request unrestricted visibility of every installed package. Notification access is powerful: Android lets the listener read previews and cancel notifications. Local policy limits what this client actually cancels.

## Dry-run stays on until you choose otherwise

The phone refuses live cancellation for seven days from its first launch. After that, dry-run remains on indefinitely. Only enable **Live dismissal** after you have reviewed the first week's results. Clearing app data starts another seven-day hold.

Changing the inbox address or credentials clears pending dismissal commands, acknowledgements and the previous server live state. An in-flight request keeps its original credentials, and a superseded response cannot change the new connection or dismiss notifications.

The server must also return `dryRun: false`. The phone ignores cancellation requests when either side is in dry-run. The protocol has no dry-run-refusal outcome, so these requests are not falsely acknowledged as dismissed. The setup status shows that phone dry-run is still on.

## Posting and cancellation

The first notification change starts a fixed one-minute posting window. Later changes do not move that deadline. A WorkManager heartbeat runs every 15 minutes. Android can delay either under Doze, battery restrictions or loss of connectivity, so this is not a real-time guarantee.

Network availability and VPN changes trigger a flush. Android does not expose the owning VPN package here, so the callback reacts to VPN changes generally, including Tailscale. Keep Tailscale connected and permit this app to run in the background.

Posts are full active-shade snapshots, not a journal of arrivals. Each snapshot reports `notificationAccessGranted` from Android's actual permission state separately from `listenerBound`. Access can be granted while notification access is still starting. When unbound, the phone sends an empty notification array, which the inbox must ignore rather than clear the shade. Failed posts do not discard acknowledgements. Each request reserves a new durable positive safe-integer sequence, even on retry or HTTP 409. On 409, the app reads the authenticated `/health` sequence and raises its local floor before the next fresh capture. This also recovers after reinstall. Capture and send share one process-wide lock.

Live requests are checked against the current shade before cancellation. Each response key must have a matching opaque `requestId` in the response's `requestIds` map. Commands without valid correlation are rejected before execution. The phone persists the ID with both cancellation intent and outcome. Distinct request IDs for a reused Android key remain distinct acknowledgements. These records exist only until the request is settled and acknowledged. The app does not keep a notification history or dismissal report.

Gone keys are acknowledged as `missing`. Protected packages and packages outside the noise allowlist are acknowledged as `protected`, which means a local policy refusal. Cancellation intent is persisted before calling Android, and `dismissed` is reported only after the key is observed absent. Android can refuse cancellation of ongoing notifications. Those remain pending and are checked again on later posts. A crash may repeat an acknowledgement, so the inbox must handle outcomes idempotently by request ID, even after the notification or pending row has disappeared.

The app refuses snapshots over the inbox's size limits rather than silently truncate or clear items: 1,000 notifications, 2,000,000 bytes total, key 1,024 characters, package 255, app name 512, title 8,192 and text 32,768. Failed-post status is visible in setup. Fix the oversized notification or update the shared contract before retrying.

## Code redaction

By default, titles and text from every app have 4 to 8 digit code candidates replaced with `[code]`, including grouped forms such as `123 456` and `123-456`. Short times such as `18:30` remain readable. This privacy-first rule can also redact harmless numbers, including gate codes.

A clear login-code phrase, such as “verification code” or “OTP”, triggers removal of all Unicode digits from the title and text across any app. This also covers grouped and mixed letter-digit codes. Known authenticators and apps selected for digit redaction have all Unicode digits removed. These apps are also protected from dismissal. Built-in bank and authenticator recognition is a finite list, not comprehensive detection. Use the app pickers for additional protection.

Pattern matching cannot guarantee that every secret is removed. The app uploads ordinary, expanded, multiline and messaging-style notification extras. It cannot recover content hidden by Android or the originating app.

## Validate on the Pixel

Run the Gradle gate above, then test with an inbox in dry-run:

1. Post normal, expanded and conversation notifications. Compare the uploaded shade with the phone, including older notifications. Clear one manually and confirm it leaves the next successful snapshot.
2. Post an authenticator notification and code candidates from an ordinary messaging app. Verify code digits do not reach the inbox. Send a short time and verify it remains readable.
3. Generate notifications continuously for several minutes. Confirm posts continue rather than waiting for silence. Background the app and check heartbeat health, allowing for Android scheduling delays.
4. Disconnect and reconnect Tailscale. Confirm the next successful flush contains the complete current shade. Revoke notification access and confirm inbox health reports an unbound listener without clearing the inbox.
5. Ask the server to dismiss a noise package, protected package and missing key. During the first week, verify no cancellation occurs. After the hold and explicit approval, verify outcomes for live requests. Verify a protected package remains even if the server requests it directly.
6. Interrupt connectivity after a cancellation and restart the process. Verify the acknowledgement is uploaded later. Force HTTP 409 and confirm the next request uses a larger sequence. Verify ongoing notifications are not falsely reported dismissed.

Unit tests cover the week and opt-in gates, safe durable sequences, failed preference commits, replayed and deferred acknowledgements, request-ID parsing, both sides' dry-run gates, endpoint validation, built-in and custom protection, setup transitions and representative cross-app redaction. Real notification access, VPN callbacks, WorkManager timing and Android cancellation require the device checks above.
