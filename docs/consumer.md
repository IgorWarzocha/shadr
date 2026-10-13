# Connect your bot

The bot reads the latest phone snapshot, requests dismissals and checks health. It does not need the phone token. Read [SKILL.md](../SKILL.md) for operating rules and [the API contract](api.md) for JSON fields.

## Commission the consumer

1. Store the exact tested client origin, hosting mode and `BOT_TOKEN` privately on the consumer machine. Public HTTPS through Cloudflare or Tailscale Funnel is the default. Funnel requires bearer credentials only; the consumer and phone need internet, not Tailscale. For Cloudflare, also store its Access service credentials. The origin must not include a path. Restrict access to the bot's account and do not paste credentials into chat.
2. Test that same public origin from outside the tailnet and confirm it rejects requests without bearer credentials. Do not substitute a successful private-backend check. For Cloudflare, missing Access credentials must also fail. A login page is not a healthy JSON response. Only an explicitly chosen private-tailnet setup should be tested using its private origin from joined clients.
3. Read authenticated `GET /health` and check `role: "bot"`. Before phone pairing, `not_paired` is expected.
4. After the human installs, pairs and grants notification access, read `GET /notifications`. If Android 13+ says **App was denied access**, have the human use **Settings > Apps > Phone inbox > ⋮ > Allow restricted settings**, then enable notification access. The app's **Open app settings** button links to its information page. Never bypass the permission decision.
5. Compare a harmless notification with the phone and check snapshot timestamps. Request a real dismissal immediately and verify its phone-confirmed receipt. There is no package allowlist, protected-app veto, observation week or separate live-mode step. Check that a manual clear also leaves the next bound snapshot.
6. Exercise **Pause bot clearing** and resume. Uploads continue while clearing is paused. A queued request is not a completed cancellation, and Android can refuse to clear ongoing notifications.

The shade response contains `notifications`, `capturedAt`, `receivedAt`, `pending`, `dismissalLog`, `dryRun` and `status`. It is a snapshot, not notification history. The compatibility field `dryRun` is always false. Only a phone-confirmed `dismissed` receipt supports "Cleared". Seven-day action receipts do not preserve cleared message bodies and impose no delay before clearing.

## Choose a routine

Start on demand. If wanted, add a quiet daily summary at the human's chosen time and IANA timezone. Summarise the current shade and distinguish pending requests from confirmed receipts. Do not promise new-arrival history or escalate by notification age.

An optional hourly health check can use the bot's existing scheduler. Agree when it should run and where actionable failures should appear. Keep successful checks silent and avoid repeated alerts for the same unresolved failure. This repository does not install a consumer scheduler or cron job.

## Health and next action

`GET /health` returns `state`, `lastSeenAt`, `nextAction`, `role` and `sequence`. The shade response also includes `status`.

| State | Next action |
| --- | --- |
| `ok` | Read the shade. Stay quiet unless asked or a chosen routine runs. |
| `not_paired` | Finish phone pairing and the first upload. |
| `phone_offline` | Check phone connectivity and the last successful upload. Android can delay background work. |
| `notification_access_revoked` | Ask the human to restore Android notification access. |
| `phone_not_ready` | Ask the human to open the app and finish setup. |
| `relay_unreachable` | Check the hosting machine, network or deployment before blaming the phone. |
| `credentials_rejected` | Repair private bearer or Access credentials. Do not request phone permissions. |
| `setup_required` | Complete the local command configuration or correct its options. |

The last three states are local command results for transport, authentication and setup failures. Check the actual public endpoint, for example:

```sh
npm --silent run status -- --endpoint https://relay.example.ts.net:10000 \
  --credentials .env.bot --hosting always-on
```

Use the exact origin in the phone's QR, not the example. Cloudflare uses its public origin and `--hosting cloud`. The credentials file needs only `BOT_TOKEN` and optional Cloudflare Access credentials. Never copy the server's two-role `.dev.vars` to the consumer. Public HTTPS failures do not call for installing Tailscale on the phone. For advanced private hosting only, check that the phone is online in the host's `tailscale status` before pairing.

In PC-at-login mode, an offline PC means the relay is unavailable. Wait for the PC to return rather than asking the human to turn on the phone. After reconnecting, the phone uploads its current shade. Notifications that came and went while the PC was off cannot be recovered.
