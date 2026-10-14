# Connect your assistant

The assistant reads the latest phone snapshot, requests dismissals and checks health. It does not need the phone token. Read [SKILL.md](../SKILL.md) for operating rules and [the API contract](api.md) for JSON fields.

## Use from Pi or another agent

The repository's `SKILL.md` is the consumer skill. It uses ordinary HTTP requests, with no model-specific SDK or assistant service. An agent needs an HTTP or command-execution tool and access to its private connection configuration. Its calls do not need npm, Node or the repository on the assistant machine. The shell examples use curl and jq.

In Pi, ask the agent to read this checkout's `SKILL.md` and follow its linked references, then ask for your current notifications. Loading the file is enough to supply the instructions, but commissioning below must first configure the endpoint and assistant credentials. Merely cloning this repository does not register the skill in Pi's global skill catalogue. Keep the linked documentation available rather than copying the skill file alone.

The same instructions work with other capable agents. The relay can stay online while the agent is stopped. On-demand reads need no always-on agent, and scheduled routines use your agent's own scheduler.

## Commission the consumer

From the setup checkout, create the assistant configuration before handing the connection over:

```sh
node scripts/configure-bot.js --endpoint https://relay.example.ts.net:10000 \
  --hosting always-on
```

Use the exact tested origin, or omit `--endpoint` when private `INBOX_URL` is already set. The command reads the existing `.dev.vars` and writes `~/.config/phone-inbox/bot.env`. It creates an owner-only file, exports only the assistant credential, and never rotates relay tokens or prints their values. `--credentials` selects a different private input file, `--output` selects the destination, and an existing output requires deliberate `--replace`. For Cloudflare, use `--hosting cloud` and an input file containing the assistant's Access credentials.

The generated file has bare values, without surrounding quotes:

```text
INBOX_URL=https://relay.example.ts.net:10000
HOSTING_MODE=always-on
BOT_TOKEN=REPLACE_WITH_THE_EXISTING_BARE_BOT_TOKEN
```

Do not copy the placeholder. Let the setup command write the existing token privately. Quotes in an old dotenv source are syntax, not part of the bearer credential. The exporter removes that syntax. Do not source or execute `bot.env` as shell code.

If the assistant runs elsewhere, create an owner-only `~/.config/phone-inbox/` directory there and transfer only `bot.env` through authenticated file transfer. Apply mode 0600 on Unix, or account-only ACLs on Windows, **before** the assistant uses it. Never transfer `.dev.vars`, which also contains the phone token. Give the assistant the absolute path on its own machine and this skill. Setup is unfinished until the file exists there and the assistant has verified its role with it.

1. Confirm the assistant configuration holds the exact tested client origin, hosting mode and `BOT_TOKEN`. Public HTTPS through Cloudflare or Tailscale Funnel is the default. Funnel requires bearer credentials only; the consumer and phone need internet, not Tailscale. For Cloudflare, also store its Access service credentials. The origin must not include a path. Restrict access to the assistant's account and do not paste credentials into chat.
2. Test that same public origin from outside the tailnet and confirm it rejects requests without bearer credentials. Do not substitute a successful private-backend check. For Cloudflare, missing Access credentials must also fail. A login page is not a healthy JSON response. Only an explicitly chosen private-tailnet setup should be tested using its private origin from joined clients.
3. Read authenticated `GET /health` and check `role: "bot"`. Before phone pairing, `not_paired` is expected.
4. After the human installs, pairs and grants notification access, read `GET /notifications?view=compact&limit=50`. Use the selected-key read only when a body or receipt is needed. If Android 13+ says **App was denied access**, have the human use **Settings > Apps > shadr > ⋮ > Allow restricted settings**, then enable notification access. The app's **Open app settings** button links to its information page. Never bypass the permission decision.
5. Compare a harmless, non-excluded notification with the phone and check snapshot timestamps. Request a real dismissal immediately and verify its phone-confirmed receipt. There is no mandatory app selection, hidden package veto, observation week or separate live-mode step. The human can use **Excluded apps** before granting access to keep selected apps entirely out of uploads and clearing; the list stays on the phone. Check that a manual clear also leaves the next bound snapshot.
6. Exercise **Pause assistant clearing** and resume. Uploads continue while clearing is paused. A queued request is not a completed cancellation, and Android can refuse to clear ongoing notifications.

The compact response contains `notifications`, `capturedAt`, `receivedAt`, `status`, `total` and `nextOffset`. Rows have `app`, a shortened `title` and `key`, without bodies or action logs. The timestamp is the snapshot capture time, not an item's arrival time. A selected-key or full response also contains `pending`, `dismissalLog` and the compatibility field `dryRun`, always false.

`POST /dismiss` requests a push wake and waits up to ten seconds for confirmation by default. It returns correlated `completed` and `pending` items. Only `result: "applied"` supports "Cleared". `failed` means cancellation was not confirmed, and `not_found` means the requested notification was absent. An accepted push is not a result. Report pending items honestly and surface missing push configuration or delivery failures. Seven-day action receipts do not preserve cleared message bodies and impose no delay before clearing.

## Choose a routine

Start on demand. If wanted, add a quiet daily summary at the human's chosen time and IANA timezone. Summarise the current shade and distinguish pending requests from confirmed receipts. Do not promise new-arrival history or escalate by notification age.

An optional hourly health check can use the assistant's existing scheduler. Agree when it should run and where actionable failures should appear. Keep successful checks silent and avoid repeated alerts for the same unresolved failure. This repository does not install a consumer scheduler or cron job.

## Health and next action

`GET /health` returns `state`, `lastSeenAt`, `nextAction`, `role` and `sequence`. The shade response also includes `status`.

| State | Next action |
| --- | --- |
| `ok` | Read the shade. Stay quiet unless asked or a chosen routine runs. |
| `phone_idle` | The phone has been quiet. Use the snapshot timestamps and require confirmation for a new action. Silence alone does not prove it is offline. |
| `not_paired` | Finish phone pairing and the first upload. |
| `phone_offline` | A command has remained unconfirmed for at least two minutes. Check connectivity and push setup. This does not prove the phone is powered off. |
| `notification_access_revoked` | Ask the human to restore Android notification access. |
| `phone_not_ready` | Check Firebase configuration, phone push registration and the app's setup status. |
| `relay_unreachable` | Check the hosting machine, network or deployment before blaming the phone. |
| `credentials_rejected` | Repair private bearer or Access credentials. Do not request phone permissions. |
| `setup_required` | Complete the local command configuration or correct its options. |

The last three states classify transport, authentication and setup failures on the assistant side. Use the safe header-file HTTP examples in [SKILL.md](../SKILL.md), with the origin from the assistant configuration. A missing config is `setup_required`, not an invitation to recover a token from chat. Public HTTPS failures do not call for installing Tailscale on the phone. For advanced private hosting only, check that the phone is online in the host's `tailscale status` before pairing.

In PC-at-login mode, an offline PC means the relay is unavailable. Wait for the PC to return rather than asking the human to turn on the phone. After reconnecting, the phone uploads its current shade. Notifications that came and went while the PC was off cannot be recovered.
