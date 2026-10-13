import { importPKCS8, SignJWT } from "jose";

let cachedToken;
const singleLine = (value, max = 1024) => typeof value === "string" && value.length > 0 &&
  value.length <= max && !/[\s\x00-\x1f\x7f]/.test(value);

export function pushConfig(env) {
  try {
    const value = JSON.parse(env.FCM_ANDROID_CONFIG);
    const { projectId, applicationId, apiKey, senderId } = value;
    if (![projectId, applicationId, apiKey, senderId].every(value => singleLine(value)) ||
      !/^[a-z][a-z0-9-]{4,62}$/.test(projectId) || !/^\d+$/.test(senderId) ||
      !/^1:\d+:android:[a-f0-9]+$/.test(applicationId) || !applicationId.startsWith(`1:${senderId}:android:`)) return null;
    return { projectId, applicationId, apiKey, senderId };
  } catch { return null; }
}

function serviceAccount(env) {
  try {
    const account = JSON.parse(env.FCM_SERVICE_ACCOUNT);
    const config = pushConfig(env);
    if (!config || account.type !== "service_account" || account.project_id !== config.projectId ||
      !singleLine(account.client_email) || !account.client_email.endsWith(".iam.gserviceaccount.com") ||
      typeof account.private_key !== "string" || account.private_key.length > 16384 ||
      !account.private_key.startsWith("-----BEGIN PRIVATE KEY-----")) return null;
    return account;
  } catch { return null; }
}

export const pushConfigured = env => serviceAccount(env) !== null;

async function accessToken(env, account, signal) {
  if (cachedToken?.account === env.FCM_SERVICE_ACCOUNT && cachedToken.expiresAt > Date.now()) {
    return cachedToken.value;
  }
  const key = await importPKCS8(account.private_key, "RS256");
  const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
    .setProtectedHeader({ alg: "RS256" }).setIssuer(account.client_email)
    .setAudience("https://oauth2.googleapis.com/token").setIssuedAt().setExpirationTime("1h").sign(key);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", signal,
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!response.ok) { await response.body?.cancel(); throw new Error("Push authentication failed"); }
  const token = await response.json();
  if (!singleLine(token.access_token, 8192) || !Number.isFinite(token.expires_in) || token.expires_in <= 60) {
    throw new Error("Invalid push authentication response");
  }
  cachedToken = { account: env.FCM_SERVICE_ACCOUNT, value: token.access_token,
    expiresAt: Date.now() + (Math.min(token.expires_in, 3600) - 60) * 1000 };
  return cachedToken.value;
}

export async function sendPush(env, token, signal) {
  const account = serviceAccount(env);
  if (!account) return { state: "push_not_configured" };
  if (!token) return { state: "push_token_missing" };
  const controller = new AbortController();
  const abort = () => controller.abort();
  const timer = setTimeout(abort, 5000);
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  try {
    const bearer = await accessToken(env, account, controller.signal);
    const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
      method: "POST", signal: controller.signal,
      headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token, data: { type: "sync" }, android: { priority: "HIGH" } } }),
    });
    const accepted = response.ok;
    if (response.status === 401) cachedToken = undefined;
    await response.body?.cancel();
    return { state: accepted ? "sent" : "push_failed" };
  } catch {
    // Provider bodies and exceptions can contain credentials. Report only a static state.
    return { state: "push_failed" };
  } finally {
    clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}
