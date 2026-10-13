# Connect your bot

The bot reads the latest phone snapshot, requests agreed noise dismissals and checks health. It does not need the phone token. Read [SKILL.md](../SKILL.md) for operating rules and [the API contract](api.md) for JSON fields.

## Commission the consumer

1. Store the endpoint, hosting mode and `BOT_TOKEN` privately on the consumer machine. For Cloudflare, also store its Access service credentials. Restrict access to the bot's account. Do not paste credentials into chat.
2. Confirm the relay is reachable and rejects requests without bearer credentials. For Cloudflare, missing Access credentials must also fail. A login page is not a healthy JSON response.
3. Read authenticated `GET /health` and check `role: "bot"`. Before phone pairing, `not_paired` is expected.
4. After the human installs, pairs and grants notification access, read `GET /notifications`. Compare a harmless notification with the phone and check snapshot timestamps. Clear it manually and verify it leaves the next bound snapshot.
5. With a noise package approved on both ends, queue a harmless notification during dry-run. Confirm it remains on the phone. A protected package must not be queued or cancelled.
6. After both seven-day holds and explicit human approval, enable live mode on both ends and explicitly requeue a harmless noise item. Check the phone-confirmed outcome, not just the queue response.

The shade response contains `notifications`, `capturedAt`, `receivedAt`, `pending`, `dismissalLog`, `dryRun` and `status`. It is a snapshot, not notification history. Show pending dry-run suggestions as "Would dismiss". Only a phone-confirmed `dismissed` receipt supports "Dismissed". Seven-day action receipts do not preserve cleared message bodies.

## Choose a routine

Start on demand. If wanted, add a quiet daily summary at the human's chosen time and IANA timezone. Summarise the current shade and separate dry-run suggestions from confirmed dismissal receipts. Do not promise new-arrival history or escalate by notification age.

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

The last three states are local command results for transport, authentication and setup failures. Check with `npm --silent run status`. Remote checks accept `--endpoint`, `--credentials .env.bot` and `--hosting pc|always-on|cloud` after npm's `--` separator. The credentials file needs only `BOT_TOKEN` and optional Access credentials. Never copy the server's two-role `.dev.vars` to the consumer.

In PC-at-login mode, an offline PC means the relay is unavailable. Wait for the PC to return rather than asking the human to turn on the phone. After reconnecting, the phone uploads its current shade. Notifications that came and went while the PC was off cannot be recovered.
