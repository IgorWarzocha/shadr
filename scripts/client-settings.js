import { readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { parseEnv } from "node:util";
import { isLocalHost } from "../src/local-network.js";

export async function readSettings(path, { optional = false, secret = false } = {}) {
  try {
    if (secret && process.platform !== "win32" && ((await stat(path)).mode & 0o077)) {
      throw new Error("Credentials file is readable by other users. Run chmod 600 on that file first.");
    }
    return parseEnv(await readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT" && optional) return {};
    if (error.code === "ENOENT") throw new Error("Credentials file is missing. Initialise the inbox with node scripts/init-secrets.js first.");
    throw error;
  }
}

export function serverAddress({ endpoint, local = {}, environment = process.env } = {}) {
  const port = environment.PORT ?? local.PORT ?? "8787";
  const supplied = endpoint ?? environment.INBOX_URL ?? local.INBOX_URL;
  if (!supplied && (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535)) {
    throw new Error("PORT must be between 1 and 65535.");
  }
  const value = supplied ?? `http://${environment.BIND_ADDRESS ?? local.BIND_ADDRESS ?? "127.0.0.1"}:${port}`;
  if (typeof value !== "string" || value.length > 2048 || /[\\\s\u0000-\u001f\u007f]/u.test(value)) {
    throw new Error("Use a complete server URL without spaces or backslashes.");
  }
  let url;
  try { url = new URL(value); }
  catch { throw new Error("Use a complete server URL, including http:// or https://."); }
  if (url.username || url.password || url.search || url.hash || !["", "/"].includes(url.pathname) || url.port === "0") {
    throw new Error("Server URL must not contain credentials, a path, query or fragment, and needs a valid port.");
  }
  if (url.protocol !== "https:" && !(url.protocol === "http:" && isLocalHost(url.hostname))) {
    throw new Error("HTTP is only allowed for Tailscale or local testing. Other servers require HTTPS.");
  }
  return url.origin;
}

export async function clientSettings({ role = "bot", endpoint, credentials = ".dev.vars", hosting,
  directory = process.cwd(), environment = process.env } = {}) {
  if (!["phone", "bot"].includes(role)) throw new Error("Choose phone or bot credentials.");
  const local = await readSettings(resolve(directory, ".local.env"), { optional: true });
  const secrets = await readSettings(resolve(directory, credentials), { secret: true });
  const url = serverAddress({ endpoint, local, environment });
  const mode = hosting ?? environment.HOSTING_MODE ?? local.HOSTING_MODE ??
    (isLocalHost(new URL(url).hostname) ? "always-on" : "cloud");
  if (!["pc", "always-on", "cloud"].includes(mode)) throw new Error("Choose pc, always-on or cloud hosting.");
  const token = secrets[role === "phone" ? "PHONE_TOKEN" : "BOT_TOKEN"];
  if (typeof token !== "string" || !token || token.length > 1024 || /[\s\u0000-\u001f\u007f]/u.test(token)) {
    throw new Error(`Supply a valid ${role === "phone" ? "PHONE_TOKEN" : "BOT_TOKEN"} in the private credentials file.`);
  }
  const id = secrets.ACCESS_CLIENT_ID || "";
  const secret = secrets.ACCESS_CLIENT_SECRET || "";
  if (Boolean(id) !== Boolean(secret) || /[\u0000-\u001f\u007f]/u.test(id + secret)) {
    throw new Error("Supply both Access credentials as single-line values, or leave both empty.");
  }
  if (id && (new URL(url).protocol !== "https:" || isLocalHost(new URL(url).hostname))) {
    throw new Error("Access credentials are only for a non-tailnet HTTPS server.");
  }
  return { endpoint: url, hostingMode: mode, headers: {
    Authorization: `Bearer ${token}`,
    ...(id ? { "CF-Access-Client-Id": id, "CF-Access-Client-Secret": secret } : {}),
  } };
}
