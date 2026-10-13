# Inbox API

One phone owns the shade. Every request needs `Authorization: Bearer TOKEN`. Cloud deployment additionally needs Access service-token headers. Responses are JSON with `Cache-Control: no-store`.

| Route | Token | Purpose |
| --- | --- | --- |
| `POST /notifications` | Phone | Replace the snapshot and receive dismissal commands |
| `GET /notifications` | Bot | Read the latest valid shade, pending requests and dismissal log |
| `POST /dismiss` | Bot | Queue currently present notification keys |
| `GET /health` | Either | Read concise status and phone protocol metadata |

There is no export endpoint or general notification history.

## Post a snapshot

```json
{
  "sequence": 1,
  "capturedAt": "2026-10-13T16:00:00.000Z",
  "listenerBound": true,
  "notificationAccessGranted": true,
  "lastListenerAt": "2026-10-13T16:00:00.000Z",
  "timeZone": "UTC",
  "notifications": [
    {
      "key": "android-notification-key",
      "package": "example.app",
      "appName": "Example",
      "title": "Sender or title",
      "text": "Notification preview"
    }
  ],
  "outcomes": []
}
```

`sequence` is a positive, durable, strictly increasing JavaScript-safe integer. Capture and send through one serial owner on the phone. Every attempt uses a new sequence, including a retry after an uncertain response. Read `GET /health` for sequence recovery after reinstall. Do not replay an old shade. A transactional sequence winner controls the entire update. Stale submissions return 409 without changing health, shade, pending commands or receipts.

Timestamps are ISO 8601. `lastListenerAt` is the last successful contact with the listener, including an active-notification query. It may be null only when unbound and cannot exceed capture time. `capturedAt` cannot be more than five minutes in the future.

`notificationAccessGranted` reports the actual Android notification-access permission, independently of listener binding. Older phones may omit it, which means unknown, not revoked. A bound listener cannot report permission false.

An unbound post updates the current phone health and sequence but does not replace the latest valid shade or its capture and receipt timestamps. A bound empty array really does mean an empty shade.

At most 1,000 notifications and 1,000 outcomes fit in one request. Notification keys and outcome request IDs must each be unique within their respective arrays. String limits in characters: key 1,024, request ID 128, package 255, app name 512, title 8,192, text 32,768. Bodies are limited to 2 MB. Do not silently truncate the notification set to meet a limit, as missing keys end pending delivery.

### Report command results

```json
{"key":"android-notification-key","status":"missing","requestId":"command-id-from-response"}
```

Outcome statuses are `dismissed`, `missing` and `protected`. Persist the request ID with the command and echo it in the outcome. Retain outcomes until a successful post acknowledges them. If a response is lost, resend them with a fresh sequence. The relay records each matching request's first accepted outcome once. Unknown or expired request IDs do not create results. An old acknowledgement cannot consume a replacement request for a reused Android key.

Report `dismissed` only after confirming cancellation, not merely issuing an Android cancellation call. A missing key is not an error. Pausing bot clearing on the phone is not a successful dismissal.

For a live request, the POST response is:

```json
{
  "dismiss": ["android-notification-key"],
  "wouldDismiss": [],
  "dryRun": false,
  "requestIds": {"android-notification-key":"command-id-from-response"}
}
```

Only `dismiss` contains executable commands. Each command has a distinct opaque ID in `requestIds`. Refuse a command without its ID. `wouldDismiss` is informational and must never cancel anything. Pending commands retain their IDs across delivery retries.

The server delivers commands immediately, without a commissioning hold, package restrictions or a server opt-out. The phone's **Pause bot clearing** control pauses cancellation. An unbound post never receives executable commands. Previously queued dry-run requests now deliver with their original request IDs, without requeueing. Historical receipts keep their original flags, but newly confirmed results use `dryRun: 0`.

## Queue dismissals

```json
{"keys":["android-notification-key"],"reason":"promotional noise"}
```

At most 100 unique keys, with a non-empty reason of at most 1,000 characters. Use a categorical reason, not copied message text.

```json
{"dryRun":false,"queued":["android-notification-key"],"ignored":[]}
```

Any currently present notification key can queue, including bank and system apps. Missing and already queued keys are ignored. Retrying a queue request preserves the pending request ID and does not duplicate its log entry.

Queueing and command delivery record no successful dismissal. Commands remain pending until an accepted result or a bound snapshot showing the key gone or belonging to a different package. They do not expire with age. Pending is bounded to the latest valid shade's 1,000 keys. Commands arrive in the next successful phone POST response, not through a push channel.

Shade absence stops delivery but is never recorded as a dismissal. A later result can still reconcile against its retained request entry. Correlation remains available while the command is pending, even if its request log entry has aged out, or for seven days from a retained request entry. Results are retained seven days from relay receipt, regardless of the phone's capture time.

## Read the shade

`GET /notifications` returns exactly:

- `notifications`: the latest valid bound snapshot's notification array, without per-item history or inferred ages.
- `capturedAt`: that snapshot's phone capture timestamp, or null before a valid snapshot.
- `receivedAt`: that snapshot's relay receipt timestamp, or null before a valid snapshot.
- `pending`: queued requests with `key`, `package`, `requestedAt`, `reason`, `dryRun: 0`, and `requestId`.
- `dismissalLog`: seven days of `requested` entries and phone-reported `dismissed`, `missing` or `protected` results. Entries contain `key`, `package`, `at`, `reason`, `dryRun` as 0 or 1, `event` and `requestId`. There is no actor inference or general removal history.
- `dryRun`: always false, retained for wire compatibility.
- `status`: `state`, `lastSeenAt` and `nextAction`, as described below.

Reads omit expired log entries. Accepted phone posts and queue writes prune expired log rows. Pending commands and the latest valid shade have no age-based deletion.

Migration 0004 converts existing live rows into the single JSON shade, preserves recent correlated requests and results, and drops the old per-item table and general history. If the old device record was unbound, its last valid shade timestamps cannot be recovered and become null until the next bound snapshot.

## Status

`GET /health` returns `state`, `lastSeenAt` and `nextAction`, plus `role` and `sequence` for the phone protocol. A connection test must verify `role: "phone"`, not merely HTTP 200.

`lastSeenAt` is the latest accepted phone POST's relay receipt time, or null before the first POST. It can be newer than the shade's `receivedAt`.

| State | Observation | Fixed nextAction |
| --- | --- | --- |
| `ok` | Recent phone POST with a bound listener | Continue. |
| `not_paired` | No accepted phone POST yet | Pair the phone and send its first snapshot. |
| `phone_offline` | At least four hours since an accepted POST | Check the phone connection. |
| `notification_access_revoked` | Recent POST explicitly reports permission false | Enable notification access on the phone. |
| `phone_not_ready` | Recent POST reports an unbound listener with permission true or unknown | Open Phone inbox and check its status. |

No first POST does not prove pairing was never attempted. `phone_offline` does not prove the phone is powered off. Staleness takes precedence over older permission reports. `ok` describes the latest observation, not a live connection guarantee. The relay defines no urgency, digest schedule, daytime cutoff or alert policy.

## Errors

Errors return only `{state,nextAction}`, without credentials, notification content or technical diagnostics.

| HTTP | State | Fixed nextAction |
| --- | --- | --- |
| 400 | `invalid_request` | Check the request. |
| 401 or 403 | `credentials_rejected` | Check the configured credentials. |
| 404 | `not_found` | Check the endpoint. |
| 409 | `stale_sequence` | Read health and retry with a newer sequence. |
| 413 | `invalid_request` | Reduce the request size. |
| 500 or 503 | `relay_unavailable` | Try again later. |

A network failure has no relay response. The calling client must classify it as `relay_unreachable` and choose an action appropriate to the hosting arrangement.
