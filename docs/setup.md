# Relay setup and recovery

For installation with a bot, start with the [user guide](../README.md) and [agent runbook](../AGENTS.md). Commands below run from the repository root. The relay host needs Node 22 or newer and npm.

## Choose a public inbox

Public HTTPS is the default. Use the same public URL for the phone, pairing QR, bot and setup checks. An endpoint must be an origin such as `https://relay.example.ts.net:10000`, with no path, query or fragment. `/inbox` and `/notifications` are not valid endpoints. Give the inbox its own port or subdomain.

| Hosting | What to expect |
| --- | --- |
| Cloudflare Worker with D1 and Access | Available without your PC. Requires human account authorisation and any applicable billing. All requests need Access and role-specific bearer credentials. |
| Public self-hosted with Tailscale Funnel | An always-on host runs the relay and local D1. Funnel provides public HTTPS. Bearer tokens are the only client authentication, with no Cloudflare Access requirement. The phone and bot need only internet access. |

[Private tailnet-only hosting](#advanced-private-tailnet-only-hosting) is an advanced alternative, not a prerequisite for either public option.

## Set up with your bot

Give your setup agent the repository and ask it to follow [AGENTS.md](../AGENTS.md). It will prepare and test your chosen public endpoint, explain APK installation before showing a QR, then check the phone and consumer connection. You must handle account authorisation, billing, administrator approval, sideloading and Android permissions yourself. For Cloudflare, use [Cloudflare setup](#cloudflare-setup). For your own server, follow the next section.

### Public self-hosted with Tailscale Funnel

**Risk:** Funnel makes the URL reachable by anyone on the internet. HTTPS protects transport, but Funnel does not authenticate clients. The separate phone and bot bearer tokens protect the relay. Treat them as passwords, keep the host updated and expect public traffic to reach it. There is no Access layer or application rate limiter in this mode. The host and its service must remain available.

```sh
npm ci --ignore-scripts
npm rebuild workerd esbuild
node scripts/init-secrets.js
npm run db:init
```

Secret creation refuses to overwrite `.dev.vars`. It contains distinct phone and bot tokens. Restrict private files to your account. On Unix, use mode 0600 and `umask 077`. On Windows, restrict file access with account ACLs.

Create private `.local.env` with `BIND_ADDRESS` set to the host's tailnet IPv4 address and `HOSTING_MODE=always-on`. `PORT` defaults to 8787. Environment variables override that file. Keep the relay itself on this private bind with `MODE=local`. The launcher rejects public and wildcard binds. Funnel deliberately exposes the private backend through HTTPS; do not expose raw HTTP to the internet.

Use `npm run service:install -- --mode always-on`. Do not run a foreground relay on the same port. Reinstall if the checkout or Node executable moves. Restart after changing private configuration. Linux logged-out operation needs administrator-approved user lingering, which the installer checks but never enables itself.

Inspect `tailscale funnel status` first so you do not replace another service. With port 10000 reserved for this inbox and the backend on 8787, publish the root of that port:

```sh
tailscale funnel --bg --https=10000 "http://$(tailscale ip -4):8787"
```

Use your configured backend port if it differs. Approve any required Funnel authorisation. Do not use `--set-path`. Save the resulting `https://…ts.net:10000` origin as `INBOX_URL` in private `.local.env`. Pairing and status then use that public address rather than the backend IP. A public HTTPS Funnel URL works with phone and bot bearer tokens alone.

To remove this public entry point without disturbing other ports:

```sh
tailscale funnel --https=10000 off
```

This leaves the private relay service running. An intentionally configured HTTPS reverse proxy may also forward to the private bind. It must use a dedicated root endpoint and preserve the private upstream Host address accepted by local mode. Do not weaken the bind or host checks to make a proxy work.

### Test the endpoint the phone will use

Check the **public** URL, not the `100.x` backend, from a client outside the tailnet. A phone on mobile data with Tailscale off is a useful final check. Backend-only success is not a completed setup.

Create the bot configuration on the setup host, using the public `INBOX_URL` saved above, then check it. For a Funnel endpoint:

```sh
node scripts/configure-bot.js --hosting always-on
npm --silent run status -- --credentials ~/.config/phone-inbox/bot.env
```

The exporter creates an owner-only file containing the exact client origin, hosting mode, existing bot token and optional Access credentials. Values are bare and unquoted. It never exports the phone token or Firebase sender key. For Cloudflare use `--hosting cloud` and `--credentials` with the bot's Access configuration. An existing output requires deliberate `--replace`, not token rotation.

Status reports `state`, `lastSeenAt` and `nextAction` without printing credentials. A valid health response exits 0, even when the phone needs attention. Transport, authentication and setup failures exit 1. Before pairing, `not_paired` is expected. Also verify that unauthenticated requests fail and each bearer has the intended role on that same endpoint.

### Install the phone app first

Configure [Firebase push delivery](#firebase-push-delivery) before pairing. The same signed APK works with your Firebase project, without a per-project rebuild.

Use a signed release APK for ordinary use. Download it from GitHub Releases when a release has been published. Otherwise your setup agent can supply it as a mobile chat attachment or a temporary HTTPS download. Grok desktop chat cannot deliver a downloadable attachment. If no signed APK is available, follow [Android build instructions](android.md).

Open the APK on the phone and approve installation from that source. Keep the release signing identity stable for updates. Do not uninstall an existing app to bypass a signing mismatch, as that loses private settings.

On Android 13 and newer, a sideloaded app may show **App was denied access** when you enable notification access. Open **Settings > Apps > Phone inbox > ⋮ > Allow restricted settings**, then return to notification access and enable Phone inbox. The app also offers **Open app settings** to reach its information page. Android requires you to approve this yourself.

To serve a signed APK from the host:

```sh
npm run apk:serve -- --apk path/to/signed.apk
```

The server uses the tailnet address in `.local.env` and port 8788 by default. Only `/phone-inbox.apk` is served, with no directory listing. For an internet-only phone, your agent can temporarily expose this fixed APK route on a separate, unused Funnel port:

```sh
tailscale funnel --bg --https=8443 "http://$(tailscale ip -4):8788"
```

Open `https://YOUR-FUNNEL-HOST:8443/phone-inbox.apk` on the phone. This publishes the APK, not the relay credentials. After installation, run `tailscale funnel --https=8443 off` and stop the APK server. Do not replace the inbox's port 10000 or another existing service. `--bind` and `--port` override the APK server defaults. Omitting `--apk` selects the locally built signed release APK. If it is absent, the server fails rather than serving a debug build.

### Pair privately

For the normal public setup, the phone needs only internet access. Do not ask it to join a tailnet. Use the exact public origin already tested, including the Funnel port if present. Endpoints cannot include paths.

The QR contains a long-lived phone secret. Anyone who scans it can use those credentials. **Image output supports `.gif` only, not PNG.** Bots should generate a private file rather than print the QR into a tool result:

```sh
npm run pair -- --endpoint https://relay.example.ts.net:10000 --output phone.pairing.gif
```

This reads the existing `PHONE_TOKEN` from `.dev.vars`, writes an account-private GIF and prints only a confirmation, not the token or QR. It refuses to overwrite files. It does not rotate credentials or include the bot token. With the intended public `INBOX_URL` saved privately, `--endpoint` may be omitted.

If the setup bot runs on another machine, retrieve the file over its existing authenticated SSH connection. For example, from that machine:

```sh
umask 077
scp relay:/absolute/path/to/phone-digest/phone.pairing.gif ./phone.pairing.gif
chmod 600 ./phone.pairing.gif
```

Use your SSH host alias and actual path. Open the file locally, or send it as an authorised private mobile-chat attachment without reading its bytes into a tool result. Do not use `cat`, base64 output, image-inspection tools or a public download URL for this secret image. Delete both copies after pairing. No private transfer channel means stop and arrange one, not publish the QR.

A human at a private, unrecorded terminal can instead run `npm run pair` to display a terminal QR. A printed QR still reveals the credential even though the token is not written as plain text. It cannot be safely put in a logged bot tool result. Non-interactive terminal output is refused.

In Phone inbox, choose **Scan QR** or **Import QR image**. The app checks the connection and saves it automatically when the inbox accepts the phone's credentials. Only use a code from your trusted setup agent. Images are decoded on the phone without broad photo access. **Enter details manually** is available if you cannot use a QR.

For Cloudflare, use `--endpoint` with your public HTTPS origin and `--credentials` with a private file containing `PHONE_TOKEN`, `ACCESS_CLIENT_ID` and `ACCESS_CLIENT_SECRET`. The QR carries the extra credentials, so there are no Access fields to fill in on the phone. Funnel needs no Access credentials. Never pass secret values as command arguments.

After connecting, allow Phone inbox to show notifications and enable it in Android notification access settings. The app shows a quiet status notification while processing a command. Return to the app and use **Send now**. Compare a harmless notification with the bot's read of the shade. The app follows your system theme.

### Connect the bot

Hand over `~/.config/phone-inbox/bot.env`, created during endpoint checks. Transfer this bot-only file privately if the consumer runs elsewhere and give it the absolute path on that machine. See [consumer commissioning](consumer.md) for permissions and verification. Setup is not complete merely because a document lists the variables the bot needs.

Load [SKILL.md](../SKILL.md). The consumer can call ordinary HTTP with curl or its own HTTP client, without Node or this checkout. Start on demand. A daily summary at your chosen time and an hourly hosting-aware health check are optional.

Clearing is enabled immediately. There is no observation week, app allowlist or extra protection switch to configure. **Pause bot clearing** stops clearing while snapshots continue to send. Android may refuse to clear an ongoing notification; the app must not report it cleared until it is actually gone. Test a harmless real dismissal through the same public endpoint used for pairing and verify the phone-confirmed receipt.

Repeat that check with the app closed and the screen off. Do not use **Send now** to complete a background-delivery check. FCM acceptance is not proof that the phone woke or cleared anything.

## Firebase push delivery

Both hosting options use Firebase Cloud Messaging to wake the phone for bot commands. FCM itself is [no-cost on the Spark plan](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans). No paid plan is needed for FCM alone. The phone needs Google Play services. No notification text or clearing keys go into the Google push payload, only a `type: sync` wake hint.

Your setup bot should explain this before starting: it will use your Google account to create a free Firebase project, register the app and configure delivery. You may need to sign in or complete account verification. You will also install the APK and grant Android permissions. After you authorise setup, the bot handles the console and files through your existing signed-in browser. It should not hand you the following steps as chores or ask again for routine actions already authorised.

This console path was executed successfully on 13 October 2026:

1. Open the [Firebase console](https://console.firebase.google.com/). Choose **Get started by setting up a Firebase project**, or **Add project** for an existing account. Enter **Phone inbox** and retain an available project ID. Complete first-use Firebase terms as authorised by the user.
2. Choose **Continue**. Turn off **Enable Gemini in Firebase**, then continue and turn off **Enable Google Analytics for this project**. Choose **Create project**, wait for the ready screen and continue. Verify **Spark · No cost ($0/month)**. Do not attach a billing account.
3. On the project overview, choose **Add app**, then **Create an Android app**. Enter package **`dev.howaboua.phonedigest`** and nickname **Phone inbox**. Choose **Register app**, then **Download google-services.json**. No signing fingerprint was needed. Skip the generic wizard's Gradle and Analytics changes: the published APK reads runtime configuration and does not need rebuilding.
4. Open **Project settings > Cloud Messaging**. Verify **Firebase Cloud Messaging API (V1) Enabled**. It was enabled automatically in the verified setup. If disabled, enable **Firebase Cloud Messaging API**, service `fcm.googleapis.com`, in the selected project. Leave the legacy API disabled. Android delivery needs no Web Push certificate.
5. Open Google Cloud Console, select the same project, then **IAM & Admin > Service accounts > Create service account**. Name it **Phone inbox FCM sender**, with ID **phone-inbox-fcm-sender**. Choose **Create and continue**.
6. Under permissions, search for and select **Firebase Cloud Messaging API Admin**, role `roles/firebasecloudmessaging.admin`. Do not grant Editor, Owner or broad Firebase Admin. Continue, skip optional principals and choose **Done**.
7. Open that service account, then **Keys > Add key > Create new key > JSON > Create**. Download the JSON key. Verify the account's role in the project's IAM page. This key belongs on the relay host, not in the consuming bot's configuration or the APK.
8. Move both downloaded files into an owner-only directory. On Unix use mode 0700 for the directory and 0600 for files. On Windows use account-only ACLs. If the browser is on another machine, transfer just these files to the relay host through authenticated file transfer. Keep a private record of the project, actions and file locations, without printing credential contents.
9. Import on the relay host:

```sh
node scripts/configure-push.js --android /private/google-services.json \
  --account /private/service-account.json
```

The command checks the package and project, adds `FCM_ANDROID_CONFIG` and `FCM_SERVICE_ACCOUNT` to private `.dev.vars`, and preserves bearer credentials. It never prints the key. Existing FCM settings require deliberate `--replace`. Restart the self-hosted relay after importing.

For Cloudflare, import to a separate private file with `--output .env.fcm`, then upload those two secrets:

```sh
npx wrangler secret bulk .env.fcm --env production --config wrangler.private.jsonc
```

Do not deploy until the human has authorised that deployment. Firebase credentials are separate from Cloudflare Access and inbox bearer tokens. Remove redundant downloaded key copies after securing the relay's configuration. Revoke a compromised key in Google IAM, not by rotating inbox bearer tokens.

The phone obtains public Firebase client configuration from authenticated `/health`, registers with FCM and sends its registration token to the relay. The sender key stays on the relay. After changing Firebase projects, open Phone inbox and use **Send now** to fetch the new configuration and register again. Validate actual screen-off command delivery after setup. Missing configuration or a missing registration token is a setup failure, not a reason to fall back to polling.

## Cloudflare setup

Do not deploy local private configuration. Copy `wrangler.jsonc` to ignored `wrangler.private.jsonc` and use its named production environment.

1. Have the human authorise Cloudflare access. Create D1 with `npx wrangler d1 create inbox` and put its database ID in the private production config.
2. Protect the custom domain with an Access self-hosted application. Use a **Service Auth** policy for the intended service tokens. No public bypass. Keep workers.dev and preview URLs disabled.
3. Set production `ACCESS_TEAM` and `ACCESS_AUD` privately. Keep `MODE=cloud`.
4. Set separate bearer secrets with `npx wrangler secret put PHONE_TOKEN --env production --config wrangler.private.jsonc` and the matching command for `BOT_TOKEN`.
5. Run `npx wrangler d1 migrations apply inbox --remote --env production --config wrangler.private.jsonc`, then `npx wrangler deploy --env production --config wrangler.private.jsonc`.
6. Verify missing Access credentials fail and each authorised bearer has the correct role before pairing. Clients need `CF-Access-Client-Id` and `CF-Access-Client-Secret` as well as their bearer.

Access JWT signature, issuer, audience and expiry are verified by the Worker. Moving hosts starts a fresh database, which the next phone snapshot populates. There is no export or cross-host database migration feature.

## Advanced: private tailnet-only hosting

Choose this only when you explicitly want the phone and bot to join your tailnet. Unlike the default public setup, Tailscale must be installed, on and signed into the same account on the phone. Run `tailscale status` on the host and confirm that the intended phone is listed and online before pairing. Keep the device list private.

Use the same private backend preparation, but do not enable Funnel. Set `INBOX_URL` to the private root endpoint, such as `http://100.64.0.1:8787`, and pair and test that exact address from tailnet-connected clients. Do not treat a successful public check as proof that this different private setup works.

Use `npm run service:install -- --mode always-on` for an always-on host, or `--mode pc` for a PC that starts at login. When the PC is off, the phone retries and later uploads its current shade. Notifications that arrive and disappear meanwhile are not recoverable. There is no offline journal.

The installer supports Linux systemd user services, macOS LaunchAgents and Windows Scheduled Tasks. macOS and Windows require the user's logged-in session. Installation has not been verified on those operating systems. Linux always-on operation while logged out needs administrator-approved lingering.

## Recovery and validation

Read the short state and next action from health. If the PC is off, restore the relay before investigating phone permissions. Android background work is best-effort. An unbound listener reports health without clearing the last valid shade. Failed or replayed posts also leave it intact. The phone uses authenticated health to recover its sequence after reinstall.

Use these commands on the relay host. macOS commands use a shell. Windows commands work in PowerShell or Command Prompt.

| OS | Service status | Restart | Stop |
| --- | --- | --- | --- |
| Linux | `systemctl --user status phone-digest.service` | `systemctl --user restart phone-digest.service` | `systemctl --user stop phone-digest.service` |
| macOS | `launchctl print gui/$(id -u)/dev.howaboua.phone-inbox` | `launchctl kickstart -k gui/$(id -u)/dev.howaboua.phone-inbox` | `launchctl bootout gui/$(id -u)/dev.howaboua.phone-inbox` |
| Windows | `schtasks /Query /TN "Phone inbox" /V /FO LIST` | `schtasks /End /TN "Phone inbox"` then `schtasks /Run /TN "Phone inbox"` | `schtasks /End /TN "Phone inbox"` |

These stop commands do not remove login startup. After macOS `bootout`, run the installer again to load the service before using `kickstart`. On Linux, inspect recent operational logs with `journalctl --user -u phone-digest.service -n 30`. Treat logs as private even though the relay does not intentionally log tokens or notification bodies.

Run `npm run check` for real Worker and disposable-D1 checks, pairing-file privacy, the APK download boundary and a deployment dry-run. These checks do not prove a hosted Cloudflare or Funnel deployment. Android release and device checks are in [Android development](android.md). [The API contract](api.md) describes the wire format.

Before publishing, inspect staged files and history for private data. Publish source, not an archive of this working directory. Never include tokens, D1 state, private endpoints, pairing images or signing keys. Rotate exposed credentials even after deleting a leaked file.
