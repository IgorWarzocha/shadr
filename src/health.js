const actions = {
  ok: "Continue.",
  not_paired: "Pair the phone and send its first snapshot.",
  phone_offline: "Check the phone connection.",
  phone_idle: "The phone is quiet; a new action needs phone confirmation.",
  notification_access_revoked: "Enable notification access on the phone.",
  phone_not_ready: "Open Phone inbox and check its status.",
};

export function health(device, now = new Date(), pushReady = false) {
  let state = "ok";
  if (!device.lastPostAt) state = "not_paired";
  else if (device.notificationAccessGranted === 0) state = "notification_access_revoked";
  else if (!device.listenerBound || !pushReady || !device.pushToken) state = "phone_not_ready";
  else if (device.pendingSince && now - Date.parse(device.pendingSince) >= 2 * 60_000) state = "phone_offline";
  else if (now - Date.parse(device.lastPostAt) >= 2 * 60_000) state = "phone_idle";
  return { state, lastSeenAt: device.lastPostAt, nextAction: actions[state] };
}
