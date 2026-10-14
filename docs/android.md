# shadr for Android

The phone sends its shared notifications to your inbox and lets assistants clear them. **Excluded apps** keeps selected apps' notifications entirely on the phone. Other private message previews are included, except content identified as authentication codes. After pairing and notification access, sending and clearing work without mandatory app selection or a waiting period. Android may refuse to clear some ongoing notifications.

## Validate and build a release

Requires Android SDK platform 35, build-tools 35.0.0 and Java 17 or newer. The checked-in wrapper pins Gradle 8.11.1 and verifies its published SHA-256. The app supports Android 8 and newer with Google Play services. Dependency versions are pinned in the Gradle files. Firebase client configuration comes from the paired relay at runtime. The same built APK works with each user's Firebase project, without a Google Services Gradle plugin or bundled `google-services.json`.

```sh
export ANDROID_HOME=/path/to/android-sdk
cd android
./gradlew :app:testReleaseUnitTest :app:lintRelease
```

Release is the normal build and the only variant with unit tests. The small boundary checks cover authentication-code omission, unsafe connection and QR inputs, and uncorrelated clearing commands. They do not prove that Android notification capture or cancellation works. Use the device flow below for that.

### Sign a release

Keep the release keystore outside the repository in a private directory. Create it once with `keytool`; passwords are entered at its prompts rather than placed in shell history:

```sh
umask 077
keytool -genkeypair -keystore /absolute/private/phone-inbox.jks \
  -alias phone-inbox -keyalg RSA -keysize 3072 -validity 10000
```

Supply `RELEASE_KEYSTORE` as that absolute file path, `RELEASE_KEY_ALIAS` as `phone-inbox`, and `RELEASE_STORE_PASSWORD` and `RELEASE_KEY_PASSWORD` through your private build environment or secret manager. Set all four variables or none. Do not put passwords in source, shared logs or command-line arguments. The build rejects incomplete configuration and missing keystore files.

Run `./gradlew :app:assembleRelease`. With the four variables supplied, the signed APK is `app/build/outputs/apk/release/app-release.apk`. Without them, the build intentionally produces `app-release-unsigned.apk`, which is not installable until signed. Preserve the same private key for every release upgrade. A production release key cannot update an existing debug-signed commissioning install; do not change or discard its signing identity implicitly.

Before distribution, verify the signature and confirm the manifest does not report `application-debuggable`:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk
```

The production signing certificate's SHA-256 fingerprint is `7fdeda42c1bb13c2ad4dc0010e11a73da53ca6406b1a9dc86109314e319a638a`. This public fingerprint identifies the release key, not the changing APK file hash.

Publish the verified signed APK as `shadr.apk` on a GitHub Release tied to the tested source tag. Include its SHA-256 checksum and any upgrade instructions. Download the published asset and compare its checksum before handing out the link. Never publish the keystore, private signing environment, debug APK or unsigned APK. Losing the release key prevents future updates under the same signing identity.

### Capture the release on an emulator

Use the signed release APK for [wording and screenshot review](../screenshots/README.md). Verify that the installed package is not debuggable and its window still has `SECURE`. Android's own screenshot command remains blocked. The emulator's supported host capture records the rendered display without changing the APK or its window flags:

```sh
mkdir -p .build/emulator-screenshot
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 emu screenrecord screenshot \
  "$PWD/.build/emulator-screenshot"
```

This writes a timestamped PNG on the emulator host. Use only a disposable emulator and fake pairing details. Release still rejects loopback HTTP, so its fixture must use an allowed private address. Follow the linked capture instructions rather than weakening release validation.

Build `./gradlew :app:assembleDebug` only for checks that specifically need debugging or loopback HTTP. Do not build a debug APK or run a second unit-test suite as part of ordinary release validation.

Debug builds are unsuitable for production credentials. Keep the original debug key and `ANDROID_USER_HOME` for emulator updates. This checkout uses `ANDROID_USER_HOME="$PWD/.toolchain/android-home"` from the `android` directory. Changing it can generate a different signing key. Do not uninstall a real phone app to bypass a signing mismatch.

## Connect your phone

Pairing QR codes contain your phone credentials. Keep the QR and any saved image private. Delete saved pairing images after use. Camera permission is requested only when you choose to scan. Importing an image uses Android's picker and needs no storage permission.

The normal connection uses a public HTTPS address, served through Cloudflare or Tailscale Funnel on an always-on computer or server. Your phone does not need Tailscale for either public option. A Funnel address such as `https://relay.example.ts.net:10000` works directly over the internet with your phone token; it does not need Cloudflare Access details.

1. Choose **Scan QR** to scan the code shown on your computer, or **Import QR image** to select a saved QR screenshot. The app checks the connection automatically and continues when your inbox accepts it. A failed connection leaves any existing settings unchanged.
2. Before allowing notification access, open **Excluded apps** if there are apps you want to keep private. Then allow **shadr** in Android's notification access settings. Return and complete **Let your assistant clear notifications**, including permission for brief, silent processing notifications. No permanent foreground notification or battery-exemption step is required.
3. The main screen shows the connection, **Last sent** and whether notifications can be cleared. **Send latest notifications to your assistant** sends the current notifications to your inbox.

On Android 13 and newer, a sideloaded app may show **App was denied access**. Choose **Open app settings**, then open **⋮ > Allow restricted settings** under **Settings > Apps > shadr**. Return to notification access settings and enable **shadr**. The app offers these instructions while access is missing; it cannot detect every manufacturer's restriction or grant access for you.

If you need to type the settings, choose **Enter details manually**, enter the **Inbox address** and **Pairing code**, then press **Connect**. Do not append `/notifications`. Include `https://` for a public Funnel `.ts.net` address: a hostname alone cannot distinguish Funnel from a private tailnet service. Explicit schemes and ports are preserved. Cloudflare Access details, when needed, come from the QR code rather than extra phone fields. Existing hidden credentials are retained only for the exact same endpoint, never copied to a changed address.

Both QR and manual connections authenticate through `GET /health` and save only after a bounded JSON response identifies the role as `phone`. An assistant token or login page is not accepted. This check uploads no notifications; after success, the app schedules background sending.

Private tailnet-only access is an advanced alternative. Only this option needs Tailscale on the phone, signed into the same account as the server. Recognised IPs in `100.64.0.0/10` and `.ts.net` hostnames without a scheme default to HTTP; other schemeless addresses default to HTTPS. If a private tailnet IP cannot be reached, check Tailscale and that the server is online. Public HTTPS connection failures do not imply that your phone needs a VPN. A rejected pairing code needs a new QR code, not a VPN change.

**Pause assistant clearing** on the main screen is off by default. Turn it on to stop new clearing actions; notifications still send. The choice saves immediately and survives restarts, upgrades and pairing again. **Settings > Connection** lets you connect to a different inbox. The app uses your phone's current system time zone, including after travel.

Upgrades retain credentials, pending acknowledgements, saved exclusions and any explicitly saved pause. Legacy clearing-only app-selection, package-protection, waiting-period and clearing-enable preferences are removed; they are not privacy exclusions. Stored authenticator overrides remain only to suppress secret content, not to prevent clearing. The application identity is unchanged. `adb install -r` upgrades an existing installation only when both APKs use the same signing key.

The shadr name does not change the existing `dev.howaboua.phonedigest` application ID, signing certificate, pairing format or private configuration paths. Existing Phone inbox installations upgrade in place without re-pairing. Relay service identifiers also stay unchanged so existing startup and recovery commands continue to work.

The app follows the system light or dark theme. Release builds block Android screenshots of the app. Emulator-host capture is available for fake-data review, as described above. Debug builds permit Android screenshots and loopback HTTP for local fixtures. Release validation rejects loopback HTTP.

Settings and the acknowledgement queue are kept in app-private storage, with Android backup disabled. They are not separately encrypted by this app. Anyone who controls the unlocked device, debugging session or operating system may read previews and credentials. Do not share screenshots of the setup screen.

The pairing format is a JSON object with `type: "phone-inbox"`, integer `version: 1`, an absolute `endpoint` URL and `phoneToken`. Optional `accessClientId` and `accessClientSecret` must both be present and nonblank, and are accepted only for HTTPS cloud connections. Unknown or duplicate fields are rejected. The complete payload is limited to 2,048 UTF-8 bytes. Images must be at most 16 MB and contain one clear QR code. A valid QR starts the authenticated connection check automatically; unsupported codes or unsuccessful checks leave the prior configuration unchanged. Unsolicited pairing intents and deep links are not accepted.

The app requests visibility of launcher apps and several system packages to resolve app names and populate **Excluded apps**. The picker also includes current notification packages and saved exclusions. It does not request unrestricted visibility of every installed package. Android may hide apps without a launcher until they have an active notification. If a shared notification's app label is unavailable, the package name is sent instead. Excluded notifications send neither. Notification access is powerful: assistants with access to your inbox can request clearing shared notifications, including bank, authenticator and system notifications. Your explicit exclusions, pause and Android's own restrictions stop those clearing actions.

## App privacy

**Excluded apps** is available before pairing and notification access, and later under **Settings**. It is empty by default. Selected packages stay in app-private preferences across restarts, upgrades and changes of inbox. The list never leaves the phone. Excluded notifications are removed before reading their content or serialising their app name, package or key. Their notification events do not trigger uploads. Queued commands cannot clear an excluded notification.

Choose exclusions before granting access to prevent an initial upload. Saving a new exclusion schedules a filtered snapshot. The previous inbox snapshot remains until a successful upload with a bound notification listener replaces it. Exclusions cannot recall data already sent, earlier receipts or assistant copies. An upload already in progress may finish before the choice is saved. The app shows when saving has completed.

## Clearing and pause

There are no mandatory package allowlists, hidden protected-app vetoes or clearing timers. The `paused` preference defaults to false. Older `live`, `allowClearing`, `installedAt`, `noise` and `protected` preferences do not carry restrictions into the new behaviour. An explicitly saved `paused: true` and the new `excludedApps` privacy choices are never reset by pairing.

Changing the inbox address or credentials clears operational commands and acknowledgements belonging to the old connection. An in-flight request keeps its original credentials, and a superseded response cannot change the new connection or clear notifications.

The reply's legacy `dryRun` field is parsed for compatibility but does not veto authenticated, correlated `dismiss` commands. `wouldDismiss` is never executed. While paused, pending commands remain operational records; already observed absence can still be acknowledged.

## Posting and cancellation

Pairing, non-excluded notification changes, saved exclusions, network changes and **Send latest notifications to your assistant** enqueue a send immediately, with no app-imposed debounce or delay. Changes during an in-flight send coalesce into at most one queued follow-up in the running process. A fresh trigger replaces work waiting for retry backoff instead of queuing behind its timer. WorkManager persists queued work, and app startup requests a fresh snapshot for recovery.

Assistant commands trigger a high-priority FCM data message containing only `type: sync`. The app shows a brief silent status notification and enqueues short expedited WorkManager work to fetch the commands, confirm cancellation and send results. Google acceptance is not phone confirmation. Android and connectivity can still delay delivery.

There is no idle polling, periodic snapshot heartbeat, permanent foreground service or app-held wake lock. Startup cancels legacy periodic work. Each active send has bounded retries. Only the app's exact operational notification channel, ID and tag are excluded from capture and event feedback. Other notifications from the same package are not given a clearing veto.

Authenticated health supplies Firebase options. They are saved with the connection and initialise the default Firebase app on process startup. Token callbacks are checked against the current connection and Firebase app. A nullable `pushToken` in each snapshot registers the current token or clears it when operational notifications are unavailable. Setup is ready only after registration has been uploaded successfully.

Network availability and VPN changes trigger a flush. Android does not expose the owning VPN package here, so the callback reacts to VPN changes generally, including Tailscale. Permit this app to run in the background. Keep Tailscale connected only if you chose private tailnet-only access.

Sends are full active-shade snapshots, not a journal of arrivals. Each snapshot reports `notificationAccessGranted` from Android's actual permission state separately from `listenerBound`. Access can be granted while notification access is still starting. When unbound, the phone sends an empty notification array, which the inbox must ignore rather than clear the shade. Failed sends do not discard acknowledgements. Each request reserves a new durable positive safe-integer sequence. On HTTP 409, the app reads authenticated `/health`, raises its sequence floor and retries once immediately with a fresh capture and new sequence. Another conflict follows normal failure recovery rather than looping. Capture and send share one process-wide lock.

Live requests are checked against the current shade before cancellation. Each response key must have a matching opaque `requestId` in the response's `requestIds` map. Commands without valid correlation are rejected before execution. The phone persists the ID with both cancellation intent and outcome. Distinct request IDs for a reused Android key remain distinct acknowledgements. These records exist only until the request is settled and acknowledged. The app does not keep a notification history or dismissal report.

Already absent keys are acknowledged as `not_found`. Cancellation intent is persisted before calling Android, and `applied` is reported only after the key is observed absent. Active work checks the batch for up to about five seconds. Notifications still present after the bounded attempt report `failed`, rather than remaining in an endless cancellation loop. A crash may repeat an acknowledgement, so the inbox handles outcomes idempotently by request ID. A successful response removes only the acknowledgements included in that request, preserving results completed while HTTP was in flight.

The app refuses snapshots over the inbox's size limits rather than silently truncate or clear items: 1,000 notifications, 2,000,000 bytes total, key 1,024 characters, package 255, app name 512, title 8,192 and text 32,768. Failed-post status is visible in setup. Fix the oversized notification or update the shared contract before retrying.

## Code redaction

When a notification contains recognised authentication-code wording or a numeric code candidate, its entire title and message content are suppressed before sending. This includes grouped numeric codes and letter-based codes in authentication context. Short times such as `18:30` remain readable. Conservative matching can also suppress harmless reference numbers and gate codes.

Known authenticator apps and stored authenticator overrides always have all notification title and message content suppressed, including secret letters. The notification key, package and app name still send, so assistants can clear those notifications. Content suppression is not a clearing restriction.

Pattern matching cannot guarantee that every secret is removed. The app uploads ordinary, expanded, multiline and messaging-style notification extras. It cannot recover content hidden by Android or the originating app.

## Validate on the Pixel

Run the Gradle gate above, then test with fake notifications and a non-production inbox:

1. Post normal, expanded and conversation notifications. Compare the uploaded shade with the phone, including older notifications. Clear one manually and confirm it leaves the next successful snapshot.
2. Send fake numeric and letter-based authentication codes from authenticator and chat apps. Verify their complete title and message content do not reach the inbox, while keys remain available for clearing. Send a short time and verify it remains readable.
3. Generate notifications continuously for several minutes. Confirm posts continue rather than waiting for silence. Then leave the phone idle and verify that full snapshots do not repeat. Repeat with operational notification permission denied to catch token-registration loops.
4. Disconnect and reconnect the phone's network. For private-tailnet hosting, also reconnect Tailscale. Confirm the next successful flush contains the complete current shade. Revoke notification access and confirm inbox health reports an unbound listener without clearing the inbox.
5. Ask the server to clear non-excluded ordinary, bank, authenticator and system notifications and a missing key. Verify all requested current keys are attempted without a hidden package veto. Turn on **Pause assistant clearing** and verify new clearing stops but sending continues; turn it off and verify pending clearing resumes. Exclude a test app and verify its entire notification entry disappears from the next bound snapshot, queued clearing cannot remove it from the phone, new notifications do not trigger uploads, and the choice survives a restart. Remove the exclusion and verify current notifications can be shared again.
6. Interrupt connectivity after a cancellation and restart the process. Verify the acknowledgement is uploaded later. Force HTTP 409 and confirm the next request uses a larger sequence. Verify ongoing notifications are not falsely reported dismissed.
7. Configure real FCM on the disposable relay, background the app and turn the screen off. Request a harmless dismissal and measure the time to its matching `applied` receipt without **Send latest notifications to your assistant** or a synthetic push intent. On a Google APIs emulator, repeat with `adb shell dumpsys deviceidle force-idle`, then restore normal idle state. A vanilla AOSP image cannot prove FCM delivery. Never use the real phone's relay tokens or database for fake emulator snapshots.

The [emulator flow](../screenshots/README.md) automates pairing, real relay uploads, phone-confirmed clearing, pause persistence and code-content omission. Run it when changing those behaviours or the UI, not for every documentation or test edit. Background timing, physical camera scanning and manufacturer-specific settings still need a real device. Report the observed result, not a test count.
