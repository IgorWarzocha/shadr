import { parseArgs } from "node:util";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { clientSettings } from "./client-settings.js";

const phoneStates = new Set(["ok", "not_paired", "phone_offline", "notification_access_revoked", "phone_not_ready"]);

function unreachable(hostingMode) {
  return { state: "relay_unreachable", lastSeenAt: null, nextAction: hostingMode === "pc"
    ? "Wait until the PC is on and signed in, then check again."
    : "Check that the inbox host and its network connection are running." };
}

async function statusBody(response) {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("No status body");
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > 65_536) throw new Error("Status response too large");
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

export async function checkInboxStatus(settings, request = fetch) {
  try {
    const response = await request(settings.endpoint + "/health", {
      headers: settings.headers, redirect: "error", signal: AbortSignal.timeout(10_000),
    });
    if (response.status === 401 || response.status === 403) {
      await response.body?.cancel();
      return { state: "credentials_rejected", lastSeenAt: null,
        nextAction: "Check the bot credentials and any Access credentials saved for this inbox." };
    }
    if (!response.ok) { await response.body?.cancel(); return unreachable(settings.hostingMode); }
    const result = await statusBody(response);
    if (result.role !== "bot") return { state: "credentials_rejected", lastSeenAt: null,
      nextAction: "Use the bot credentials for inbox checks, not the phone credentials." };
    if (!phoneStates.has(result.state) || typeof result.nextAction !== "string" || result.nextAction.length > 500 ||
      !(result.lastSeenAt === null || (typeof result.lastSeenAt === "string" && Number.isFinite(Date.parse(result.lastSeenAt))))) {
      return unreachable(settings.hostingMode);
    }
    return { state: result.state, lastSeenAt: result.lastSeenAt, nextAction: result.nextAction };
  } catch { return unreachable(settings.hostingMode); }
}

export async function inboxStatus(args = process.argv.slice(2)) {
  const { values } = parseArgs({ args, options: {
    endpoint: { type: "string" }, credentials: { type: "string", default: ".dev.vars" },
    hosting: { type: "string" }, help: { type: "boolean" },
  } });
  if (values.help) {
    console.log("Usage: npm --silent run status -- [--endpoint URL] [--credentials .env.bot] [--hosting pc|always-on|cloud]\nPrints a short JSON state and next action. Credentials stay in private files.");
    return;
  }
  let settings;
  try { settings = await clientSettings(values); }
  catch {
    console.log(JSON.stringify({ state: "setup_required", lastSeenAt: null,
      nextAction: "Complete inbox setup and check its private bot configuration." }));
    process.exitCode = 1;
    return;
  }
  const result = await checkInboxStatus(settings);
  console.log(JSON.stringify(result));
  if (!phoneStates.has(result.state)) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  inboxStatus().catch(() => {
    console.log(JSON.stringify({ state: "setup_required", lastSeenAt: null,
      nextAction: "Check the inbox command options and private configuration." }));
    process.exitCode = 1;
  });
}
