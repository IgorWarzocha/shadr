# App wording for approval

This records the v0.7 wording changes, before the app was renamed shadr and app privacy exclusions were added. The table preserves the strings reviewed at that point.

Use **assistant** throughout the app. Protocol names such as `BOT_TOKEN`, the `bot` role and `bot.env` are unchanged.

Keep manual sending as a recovery action after a connection problem or setup change. Notifications are normally sent automatically. The button now says exactly what it sends and to whom.

## Every changed app string

Repeated labels are listed once. Line breaks below match the screen. The ready-screen sentence is followed by the current clearing status, as before.

| Where | Old | New |
| --- | --- | --- |
| Clearing setup title | Enable bot requests | Let your assistant clear notifications |
| Clearing setup explanation | Allow a brief, silent notification when a bot request is being processed. Phone inbox won't keep a permanent notification or poll while idle. | Phone inbox shows a brief, silent notification while your assistant clears notifications. |
| Settings and unfinished-setup link | Bot request setup | Assistant clearing |
| Inbox setup incomplete | Push notifications aren't set up for this inbox yet. Finish Firebase setup on your server, then try again. | Your inbox isn't ready yet. Ask your assistant to finish setup, then try again. |
| Assistant connection failed | Couldn't enable bot requests. Check your connection and try again. | Couldn't connect to your assistant. Check your internet connection and try again. |
| Assistant connection in progress | Connecting bot requests to this phone… | Connecting to your assistant |
| Manual send button | Send now | Send latest notifications to your assistant |
| Sending feedback | Sending to your inbox… | Sending notifications to your assistant |
| Connected status | Connected<br>Notifications send in the background. | Connected<br>Notifications are sent in the background. |
| Ready-screen sentence | Notifications will send in the background. | Notifications are sent in the background. |
| Pause switch | Pause bot clearing | Pause assistant clearing |
| Paused status | Bot clearing is paused. Notifications still send. | Clearing is paused. Notifications are still sent. |
| Clearing available | Bots can clear your notifications. | Your assistant can clear your notifications. |
| Missing notification access | Notification access needed<br>Allow access to send your notifications. | Notification access needed<br>Allow access to share notifications with your assistant. |
| Status-notification permission needed | Notifications need to be enabled<br>Enable bot requests to finish setup. | Allow Phone inbox notifications<br>Open Assistant clearing to finish setup. |
| Assistant setup needed | Bot requests aren't ready<br>Open bot request setup to finish connecting. | Your assistant isn't connected yet<br>Open Assistant clearing to finish setup. |
| Pairing introduction | Scan the QR code from your inbox to connect. | Scan the QR code from your assistant to connect. |
| Manual address label and empty-field hint | Server address | Inbox address |
| Manual credential label and empty-field hint | Phone token | Pairing code |
| Invalid manual details | These details don't look right. Try a new QR code or check the server address. | These details don't look right. Try a new QR code or check the inbox address. |
| Notification-access explanation | Phone inbox needs access to send your notifications to your inbox. | Allow access so your assistant can read and clear notifications. |
| Restricted-settings help | If Android says 'App was denied access', open Settings > Apps > Phone inbox > ⋮ > Allow restricted settings. Then enable notification access. | If Android says 'App was denied access':<br>1. Tap Open app settings.<br>2. Open the three-dot menu and tap Allow restricted settings.<br>3. Return here and allow notification access. |
| Android notification-channel name | Bot request status | Assistant request status |
| Processing notification | Processing a bot request | Processing your assistant's request |
| Paused notification | Bot clearing is paused | Assistant clearing is paused |
| Finished notification | Bot request processed | Assistant request processed |

## Removed and conditional controls

- **Open app settings** is removed from the assistant-clearing screen. It remains beside the restricted-settings instructions, where it is needed.
- **Allow notifications** is the only primary action while permission is missing. Granting permission continues setup automatically.
- **Try again** appears only when permission is already granted but the assistant connection is not ready. It no longer competes with **Allow notifications**.
- **Back** remains a secondary action.
- Manual-entry hints contain words only, not example addresses or raw IPs. Existing saved connection details are still preserved.

The user guide and setup documentation mirror these labels and replace the prose terms **bot**, **bots** and **Bot** with **assistant**, **assistants** and **Assistant**. Technical identifiers and command names are unchanged.
