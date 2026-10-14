# Inbox API

One phone owns the shade. Every request needs `Authorization: Bearer TOKEN`. Cloud deployment additionally needs Access service-token headers. Responses are JSON with `Cache-Control: no-store`.

| Route | Token | Purpose |
| --- | --- | --- |
| `POST /notifications` | Phone | Replace the snapshot and receive dismissal commands |
| `GET /notifications` | Assistant | Read the latest valid shade, pending requests and dismissal log |
| `POST /dismiss` | Assistant | Queue currently present notification keys |
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

Optional `pushToken` registers the phone's current FCM token. It is either a non-whitespace printable ASCII string of 1 to 4,096 characters or null. Null clears registration, while omission preserves it for older clients. It is never returned to assistants. The phone clears registration when push setup or permission to show its operational notification is unavailable.

An unbound post updates the current phone health and sequence but does not replace the latest valid shade or its capture and receipt timestamps. A bound empty array really does mean an empty shade.

At most 1,000 notifications and 1,000 outcomes fit in one request. Notification keys and outcome request IDs must each be unique within their respective arrays. String limits in characters: key 1,024, request ID 128, package 255, app name 512, title 8,192, text 32,768. Bodies are limited to 2 MB. Do not silently truncate the notification set to meet a limit, as missing keys end pending delivery.

### Report command results

```json
{"key":"android-notification-key","status":"applied","requestId":"command-id-from-response"}
```

Outcome statuses are `applied`, `failed` and `not_found`. Persist the request ID with the command and echo it in the outcome. Retain outcomes until a successful post acknowledges them. If a response is lost, resend them with a fresh sequence. The relay records each matching request's first accepted outcome once. Unknown or expired request IDs do not create results. An old acknowledgement cannot consume a replacement request for a reused Android key. Older clients' `dismissed`, `protected` and `missing` statuses are normalised to `applied`, `failed` and `not_found` respectively.

Report `applied` only after observing the requested notification disappear following cancellation. Report `failed` when cancellation cannot be confirmed within the phone's bounded attempt, and `not_found` when the requested notification is already absent. Pausing assistant clearing leaves the request pending, not successfully dismissed.

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

The server delivers commands immediately, without a commissioning hold, package restrictions or a server opt-out. The phone's **Pause assistant clearing** control pauses cancellation. Its local **Excluded apps** list filters whole notifications before upload and blocks their cancellation, including commands queued before exclusion. Neither the list nor excluded notification metadata is part of the protocol. A subsequent bound snapshot replaces previously shared entries and ends pending delivery for missing keys. Exclusion does not retract earlier receipts or assistant copies. An unbound post never receives executable commands. Previously queued dry-run requests now deliver with their original request IDs, without requeueing. Historical receipts keep their original flags, but newly confirmed results use `dryRun: 0`.

## Queue dismissals

```json
{"keys":["android-notification-key"],"reason":"promotional noise"}
```

At most 100 unique keys, with a non-empty reason of at most 1,000 characters. Use a categorical reason, not copied message text.

```json
{
  "dryRun": false,
  "queued": ["android-notification-key"],
  "ignored": [],
  "requestIds": {"android-notification-key":"command-id"},
  "completed": [{"key":"android-notification-key","requestId":"command-id","result":"applied"}],
  "pending": [],
  "delivery": {"state":"sent"}
}
```

Any currently present notification key can queue, including bank and system apps. Missing and already queued keys are ignored. Retrying a queue request preserves the pending request ID and does not duplicate its log entry.

`POST /dismiss?wait=10` waits briefly for confirmation after sending a high-priority FCM wake hint. `wait` is an integer from 0 to 10 seconds, default 10. Google authentication and sending have a separate five-second maximum, so even `wait=0` can wait for the push attempt. Use a client timeout of at least 20 seconds. `completed` contains results available at response time. `pending` contains unresolved requests. Both correlate by key and request ID. Missing keys have no request ID and remain in `ignored`.

`delivery.state` is `sent`, `push_not_configured`, `push_token_missing`, `push_failed` or `not_needed`. `sent` means Google accepted the wake hint, never that Android applied a cancellation. A setup or delivery failure leaves the command pending and must be surfaced to the caller. The push contains only `data: {type: "sync"}`. Commands and results still travel over authenticated relay HTTP.

Queueing and command delivery record no successful dismissal. Commands remain pending until an accepted result or a bound snapshot showing the key gone or belonging to a different package. They do not expire with age. Pending is bounded to the latest valid shade's 1,000 keys. A push wakes the phone to fetch commands in its next successful POST response.

Shade absence stops delivery and records `not_found`, never `applied`. The first accepted result is final. Correlation remains available while the command is pending, even if its internal request log entry has aged out. Results are retained seven days from relay receipt, regardless of the phone's capture time.

## Read the shade

Start with `GET /notifications?view=compact&limit=50&offset=0`. It returns `notifications` containing only `app`, `title` and `key`, plus `capturedAt`, `receivedAt`, `status`, `total` and `nextOffset`. App names fall back to package names. Titles are limited to 160 Unicode code points. `limit` is 1 to 100 and `offset` is 0 to 1,000. `nextOffset` is null at the end. Pages are not a frozen snapshot. Capture time is not each notification's arrival time.

For one item's full body and correlated records, use `GET /notifications?key=URL_ENCODED_EXACT_KEY`. This returns the full shape below, filtered on the server. Do not combine `key` with compact or pagination options. Invalid views, repeated recognised parameters and invalid combinations return 400.

Bare `GET /notifications` downloads the full shade and seven days of receipts. Avoid it for routine assistant reads. Its fields are:

- `notifications`: the latest valid bound snapshot's notification array, without per-item history or inferred ages.
- `capturedAt`: that snapshot's phone capture timestamp, or null before a valid snapshot.
- `receivedAt`: that snapshot's relay receipt timestamp, or null before a valid snapshot.
- `pending`: queued requests with `key`, `package`, `requestedAt`, `reason`, `dryRun: 0`, and `requestId`.
- `dismissalLog`: seven days of completed results only. Entries contain `key`, `package`, `at`, `reason`, `dryRun` as 0 or 1, `result` and `requestId`. Results are `applied`, `failed` or `not_found`. Internal requested entries are never exposed as completed results. There is no actor inference or general removal history.
- `dryRun`: always false, retained for wire compatibility.
- `status`: `state`, `lastSeenAt` and `nextAction`, as described below.

Reads omit expired log entries. Accepted phone posts and queue writes prune expired log rows. Pending commands and the latest valid shade have no age-based deletion.

Migration 0004 converts existing live rows into the single JSON shade, preserves recent correlated requests and results, and drops the old per-item table and general history. If the old device record was unbound, its last valid shade timestamps cannot be recovered and become null until the next bound snapshot.

Migration 0005 preserves pending IDs and the shade, normalises legacy results and adds private push registration. Apply it before running the updated relay.

## Status

`GET /health` returns `state`, `lastSeenAt` and `nextAction`, plus `role` and `sequence` for the phone protocol. A connection test must verify `role: "phone"`, not merely HTTP 200.

Only phone-authenticated health includes `pushConfig`: `{projectId,applicationId,apiKey,senderId}` from `FCM_ANDROID_CONFIG`, or null when invalid or absent. The phone uses these public client options to initialise the default Firebase app. The private `FCM_SERVICE_ACCOUNT` and registration token never appear in health.

`lastSeenAt` is the latest accepted phone POST's relay receipt time, or null before the first POST. It can be newer than the shade's `receivedAt`.

| State | Observation | Fixed nextAction |
| --- | --- | --- |
| `ok` | Recent phone POST with a bound listener and push configured | Continue. |
| `not_paired` | No accepted phone POST yet | Pair the phone and send its first snapshot. |
| `phone_idle` | At least two minutes since a POST, without an overdue command | The phone is quiet; a new action needs phone confirmation. |
| `phone_offline` | A pending command has remained unconfirmed for at least two minutes | Check the phone connection. |
| `notification_access_revoked` | Latest POST explicitly reports permission false | Enable notification access on the phone. |
| `phone_not_ready` | Unbound listener, missing push configuration or no registered push token | Open shadr and check its status. |

No first POST does not prove pairing was never attempted. `phone_offline` does not prove the phone is powered off. Permission and setup failures take precedence over idle or overdue-command states. Idle silence is expected with event-driven uploads, not proof of disconnection. `ok` describes the latest observation, not a live connection guarantee. The relay defines no urgency, digest schedule, daytime cutoff or alert policy.

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
