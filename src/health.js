const actions = {
  ok: "Continue.",
  not_paired: "Pair the phone and send its first snapshot.",
  phone_offline: "Check the phone connection.",
  notification_access_revoked: "Enable notification access on the phone.",
  phone_not_ready: "Open Phone inbox and check its status.",
};

export function health(device, now = new Date()) {
  let state = "ok";
  if (!device.lastPostAt) state = "not_paired";
  else if (now - Date.parse(device.lastPostAt) >= 4 * 3600_000) state = "phone_offline";
  else if (device.notificationAccessGranted === 0) state = "notification_access_revoked";
  else if (!device.listenerBound) state = "phone_not_ready";
  return { state, lastSeenAt: device.lastPostAt, nextAction: actions[state] };
}
