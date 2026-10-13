# Phone inbox

Let your bot read what's on your Android notification shade without opening each app. Pair by QR, ask for a summary when you want one, and optionally let it dismiss apps you have explicitly marked as noise. Protected apps remain visible but cannot be dismissed. Dry-run is the default.

## Before you start

- Android 8 or newer, permission to install an APK and grant notification access. Google Play services are not required.
- Node 22 or newer and npm on the relay host. Building the APK also needs the [Android toolchain](android/README.md).
- Either a Cloudflare account with Workers, D1 and Access, or Tailscale on the host, phone and consumer machines. Account sign-in, billing and device permissions are human steps.
- A bot that can make authenticated HTTP requests and keep its credentials private. No bot scheduler is installed for you.

**Privacy:** notification access exposes private message previews. Protected means never dismiss, not never upload. Phone-side code redaction is on by default but cannot catch every secret and may also hide ordinary numbers. Android may hide sensitive content itself. The relay stores the latest snapshot, pending dismissal requests and seven days of action receipts. It is not a message archive. Keep the database, credentials and pairing QR private.

## Choose where it runs

| Hosting | What to expect |
| --- | --- |
| Cloudflare Worker with D1 and Access | Available without your PC. Requires human account authorisation and any applicable billing. All requests need Access and role-specific bearer credentials. |
| Always-on host over Tailscale | Private local relay with local D1. The host and its service must stay running. Linux logged-out operation needs administrator-approved user lingering. |
| Your PC at login over Tailscale | Starts when you log in. When the PC is off, the phone retries and later uploads its current shade. Notifications that arrive and disappear meanwhile are not recoverable. |

The login installer supports Linux systemd user services, macOS LaunchAgents and Windows Scheduled Tasks. macOS and Windows require the user's logged-in session. Their startup definitions have automated checks, but installation has not been verified on those operating systems. There is no offline notification journal.

## Set up with your bot

Give your setup agent this checkout and ask it to follow [AGENTS.md](AGENTS.md). It will prepare the private relay, explain APK installation before showing a QR, then check the phone and consumer connection. You must handle account authorisation, billing, administrator approval, sideloading and Android permissions yourself.

### Prepare a local relay

```sh
npm ci --ignore-scripts
npm rebuild workerd esbuild
node scripts/init-secrets.js
npm run db:init
```

Secret creation refuses to overwrite `.dev.vars`. It contains distinct phone and bot tokens. Restrict private files to your account. On Unix, use mode 0600 and `umask 077`. On Windows, restrict file access with account ACLs.

Create private `.local.env` with `BIND_ADDRESS` set to the host's tailnet IPv4 address and `HOSTING_MODE=pc` or `HOSTING_MODE=always-on`. `PORT` defaults to 8787. Environment variables override that file. Loopback is the default for local tests, not a phone connection. The launcher rejects public and wildcard binds. HTTP is only for loopback tests or Tailscale-encrypted routes. Do not expose the port publicly.

Run `npm run dev` for a foreground test. Use `npm run service:install -- --mode pc` for login startup, or `--mode always-on` for an always-on host. Do not run a second relay on the same port. Reinstall if the checkout or Node executable moves. Restart after changing private configuration. On Linux, always-on installation checks user lingering but does not enable it or request administrator privileges itself.

Check the connection without displaying credentials:

```sh
npm --silent run status
```

The output gives `state`, `lastSeenAt` and `nextAction`. Any valid relay health response exits 0, even if the phone needs attention. Network, authentication and setup failures exit 1. For a remote relay, use `--endpoint` and `--credentials .env.bot`, with `--hosting cloud` or the appropriate local hosting mode. The private `.env.bot` needs only `BOT_TOKEN` and optional Access credentials. `INBOX_URL` in `.local.env` can supply a remote endpoint.

### Install the phone app first

Use a signed release APK for ordinary use. Download it from GitHub Releases when a release has been published. Otherwise your setup agent can supply it as a mobile chat attachment or a private Tailscale download. Grok desktop chat cannot deliver a downloadable attachment. If no signed APK is available, follow [Android build instructions](android/README.md).

Open the APK on the phone and approve installation from that source. Keep the release signing identity stable for updates. Do not uninstall an existing app to bypass a signing mismatch, as that loses private settings.

To serve a signed APK privately from the host:

```sh
npm run apk:serve -- --apk path/to/signed.apk
```

The server uses the tailnet address in `.local.env` and port 8788 by default. Open its printed private link on the phone while Tailscale is connected. Only `/phone-inbox.apk` is served, with no directory listing. Stop the server after installation. `--bind` and `--port` override the defaults. Omitting `--apk` selects a debug build for commissioning and prints a warning. Do not use that default for production credentials.

### Pair privately

The QR contains a long-lived phone secret. Anyone who scans it can use those credentials. Do not show it in a shared transcript, recording or public screenshot.

```sh
npm run pair
```

This uses the existing `PHONE_TOKEN` in `.dev.vars` and the address in `.local.env`. It does not rotate tokens or include the bot token. Connect Tailscale on the phone first. In Phone inbox, choose **Scan QR**, inspect the imported address, then press **Test and connect**. Scanning fills the form only.

To import an image instead:

```sh
npm run pair -- --output phone.pairing.gif
```

Open it locally to scan or transfer it privately and choose **Import QR image**. The image is decoded on the phone without broad photo access. Delete it afterwards. The command refuses to overwrite files.

For Cloudflare, use `--endpoint` with your private HTTPS endpoint and `--credentials` with a private file containing `PHONE_TOKEN`, `ACCESS_CLIENT_ID` and `ACCESS_CLIENT_SECRET`. Never pass secret values as command arguments.

After the connection test, allow **Phone inbox** in Android notification access settings. Return to the app and use **Post now**. Compare a harmless notification with the bot's read of the shade. The app follows your system theme.

### Connect the bot

Give the consumer only `BOT_TOKEN` and, for Cloudflare, its Access credentials through private configuration. Load [SKILL.md](SKILL.md) and follow [consumer commissioning](docs/consumer.md). Start on demand. A daily summary at your chosen time and an hourly hosting-aware health check are optional.

Noise lists start empty. Choose noise apps on the phone and authorise the same packages in the relay's private `NOISE_PACKAGES` JSON array. Extra relay protection uses `PROTECTED_PACKAGES`. Tokens cannot edit these policies. Known banks, authenticators, system apps and device administrators are protected on the phone, but no bank list is exhaustive.

After at least seven days on both ends, live dismissals still require your approval. Set `LIVE_DISMISSALS="true"` privately, restart the relay and enable **Live dismissal** on the phone. Existing dry-run requests do not become live automatically. Explicitly requeue a harmless noise item and verify a phone-confirmed receipt.

## Cloudflare setup

Do not deploy local private configuration. Copy `wrangler.jsonc` to ignored `wrangler.private.jsonc` and use its named production environment.

1. Have the human authorise Cloudflare access. Create D1 with `npx wrangler d1 create inbox` and put its database ID in the private production config.
2. Protect the custom domain with an Access self-hosted application. Use a **Service Auth** policy for the intended service tokens. No public bypass. Keep workers.dev and preview URLs disabled.
3. Set production `ACCESS_TEAM` and `ACCESS_AUD` privately. Keep `MODE=cloud` and configure package policies there.
4. Set separate bearer secrets with `npx wrangler secret put PHONE_TOKEN --env production --config wrangler.private.jsonc` and the matching command for `BOT_TOKEN`.
5. Run `npx wrangler d1 migrations apply inbox --remote --env production --config wrangler.private.jsonc`, then `npx wrangler deploy --env production --config wrangler.private.jsonc`.
6. Verify missing Access credentials fail and each authorised bearer has the correct role before pairing. Clients need `CF-Access-Client-Id` and `CF-Access-Client-Secret` as well as their bearer.

Access JWT signature, issuer, audience and expiry are verified by the Worker. Moving hosts starts a fresh database and another server observation week. The next phone snapshot populates it. There is no export or database migration feature.

## Recovery and validation

Read the short state and next action from health. If the PC is off, restore the relay before investigating phone permissions. Android background work is best-effort. An unbound listener reports health without clearing the last valid shade. Failed or replayed posts also leave it intact. The phone uses authenticated health to recover its sequence after reinstall.

Use these commands on the relay host. macOS commands use a shell. Windows commands work in PowerShell or Command Prompt.

| OS | Service status | Restart | Stop |
| --- | --- | --- | --- |
| Linux | `systemctl --user status phone-digest.service` | `systemctl --user restart phone-digest.service` | `systemctl --user stop phone-digest.service` |
| macOS | `launchctl print gui/$(id -u)/dev.howaboua.phone-inbox` | `launchctl kickstart -k gui/$(id -u)/dev.howaboua.phone-inbox` | `launchctl bootout gui/$(id -u)/dev.howaboua.phone-inbox` |
| Windows | `schtasks /Query /TN "Phone inbox" /V /FO LIST` | `schtasks /End /TN "Phone inbox"` then `schtasks /Run /TN "Phone inbox"` | `schtasks /End /TN "Phone inbox"` |

These stop commands do not remove login startup. After macOS `bootout`, run the installer again to load the service before using `kickstart`. On Linux, inspect recent operational logs with `journalctl --user -u phone-digest.service -n 30`. Treat logs as private even though the relay does not intentionally log tokens or notification bodies.

Run `npm run check` for relay tests and a deployment dry-run. Android build and device checks are in [android/README.md](android/README.md). [The API contract](docs/api.md) describes the wire format.

Before publishing, inspect staged files and history for private data. Publish source, not an archive of this working directory. Never include tokens, D1 state, private endpoints, pairing images or signing keys. Rotate exposed credentials even after deleting a leaked file.
