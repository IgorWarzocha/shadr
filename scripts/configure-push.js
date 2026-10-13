import { readFile, stat } from "node:fs/promises";
import { createPrivateKey } from "node:crypto";
import { parseArgs, parseEnv } from "node:util";
import { pushConfig, pushConfigured } from "../src/push.js";
import { writePrivate } from "./private-file.js";

const { values } = parseArgs({ options: {
  android: { type: "string" }, account: { type: "string" },
  output: { type: "string", default: ".dev.vars" },
  replace: { type: "boolean" }, help: { type: "boolean" },
} });

async function main() {
  if (values.help) {
    console.log("Usage: node scripts/configure-push.js --android /private/google-services.json --account /private/service-account.json [--output .dev.vars] [--replace]\nImports Firebase configuration without printing credentials or changing bearer tokens. --replace permits replacing existing FCM settings. Restart the relay afterwards.");
    return;
  }
  if (!values.android || !values.account) throw new Error("Supply both --android and --account file paths.");
  if (process.platform !== "win32" && ((await stat(values.account)).mode & 0o077)) {
    throw new Error("Restrict the service account file to mode 0600 before importing it.");
  }
  let android, account;
  try {
    android = JSON.parse(await readFile(values.android, "utf8"));
    account = JSON.parse(await readFile(values.account, "utf8"));
  } catch { throw new Error("Could not read Firebase JSON files. Check their paths and format."); }
  const clients = Array.isArray(android?.client) ? android.client.filter(client =>
    client?.client_info?.android_client_info?.package_name === "dev.howaboua.phonedigest") : [];
  if (clients?.length !== 1) throw new Error("Register exactly one Android client with package dev.howaboua.phonedigest.");
  const client = clients[0];
  const config = {
    projectId: android.project_info?.project_id,
    applicationId: client.client_info?.mobilesdk_app_id,
    apiKey: client.api_key?.[0]?.current_key,
    senderId: android.project_info?.project_number,
  };
  const env = {
    FCM_ANDROID_CONFIG: JSON.stringify(config),
    FCM_SERVICE_ACCOUNT: JSON.stringify({ type: account?.type, project_id: account?.project_id,
      client_email: account?.client_email, private_key: account?.private_key }),
  };
  if (!pushConfig(env) || !pushConfigured(env) || config.applicationId.split(":")[1] !== config.senderId) {
    throw new Error("Firebase client and service account must contain valid configuration for the same project.");
  }
  try {
    if (createPrivateKey(account.private_key).asymmetricKeyType !== "rsa") throw new Error();
  } catch { throw new Error("The service account must contain a valid RSA private key."); }
  if (Object.values(env).some(value => /['\r\n]/.test(value))) throw new Error("Firebase values cannot be encoded safely.");
  let existing = "", exists = false;
  try {
    if (process.platform !== "win32" && ((await stat(values.output)).mode & 0o077)) {
      throw new Error("Restrict the destination configuration to mode 0600 first.");
    }
    existing = await readFile(values.output, "utf8");
    exists = true;
  } catch (error) { if (error.code !== "ENOENT") throw error; }
  const parsed = parseEnv(existing);
  if (!values.replace && (parsed.FCM_ANDROID_CONFIG !== undefined || parsed.FCM_SERVICE_ACCOUNT !== undefined)) {
    throw new Error("FCM configuration already exists. Use --replace only to deliberately replace it.");
  }
  const retained = existing.split(/\r?\n/).filter(line => !/^\s*(?:export\s+)?FCM_(?:ANDROID_CONFIG|SERVICE_ACCOUNT)\s*=/.test(line)).join("\n");
  const content = retained + (retained && !retained.endsWith("\n") ? "\n" : "") +
    Object.entries(env).map(([name, value]) => `${name}='${value}'\n`).join("");
  await writePrivate(values.output, content, { replace: exists });
  console.log("Saved private FCM configuration. Bearer credentials are unchanged. Restart the relay and verify actual phone delivery.");
}

main().catch(error => {
  // JSON and provider errors can quote private input. Only our own validation messages are safe.
  console.error(error.code ? "Could not access Firebase configuration files. Check paths and permissions." : error.message);
  process.exitCode = 1;
});
