import { mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { clientSettings, readSettings } from "./client-settings.js";
import { writePrivate } from "./private-file.js";

const { values } = parseArgs({ options: {
  endpoint: { type: "string" }, credentials: { type: "string", default: ".dev.vars" },
  hosting: { type: "string" }, output: { type: "string", default: join(homedir(), ".config/phone-inbox/bot.env") },
  replace: { type: "boolean" }, help: { type: "boolean" },
} });

async function main() {
  if (values.help) {
    console.log("Usage: node scripts/configure-bot.js [--endpoint URL] [--credentials private.env] [--hosting always-on|pc|cloud] [--output PATH] [--replace]\nCreates owner-only bot.env with the tested INBOX_URL and existing bot credentials, never the phone token. Does not rotate relay secrets. Existing output is refused unless --replace is explicit.");
    return;
  }
  const local = await readSettings(".local.env", { optional: true });
  const source = await readSettings(values.credentials, { secret: true });
  const endpoint = values.endpoint ?? process.env.INBOX_URL ?? source.INBOX_URL ?? local.INBOX_URL;
  if (!endpoint) throw new Error("Set INBOX_URL or --endpoint to the exact tested client origin. A backend-only address is not a public setup.");
  const settings = await clientSettings({ ...values, endpoint });
  const entries = {
    INBOX_URL: settings.endpoint, HOSTING_MODE: settings.hostingMode,
    BOT_TOKEN: settings.headers.Authorization.slice("Bearer ".length),
    ...(settings.headers["CF-Access-Client-Id"] ? {
      ACCESS_CLIENT_ID: settings.headers["CF-Access-Client-Id"],
      ACCESS_CLIENT_SECRET: settings.headers["CF-Access-Client-Secret"],
    } : {}),
  };
  if (Object.values(entries).some(value => /[\s"'#]/u.test(value))) {
    throw new Error("Bot configuration needs literal, unquoted values without whitespace, quotes or # characters.");
  }
  const output = resolve(values.output);
  await mkdir(dirname(output), { recursive: true, mode: 0o700 });
  await writePrivate(output, Object.entries(entries).map(([key, value]) => `${key}=${value}\n`).join(""), { replace: values.replace });
  console.log(`Bot configuration saved: ${output}\nGive the consuming bot this path and SKILL.md. Transfer only this bot-only file privately if the bot runs elsewhere. Tokens were not printed or rotated.`);
}

main().catch(error => {
  console.error(error.code === "EEXIST" ? "Bot configuration already exists. Inspect its purpose before explicitly replacing it with --replace." : error.message);
  process.exitCode = 1;
});
