import { mkdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { readSettings } from "./client-settings.js";
import { isTailnetAddress } from "../src/local-network.js";
import { startupDefinition, serviceLabel, windowsTask } from "./startup-definition.js";
import { writePrivate } from "./private-file.js";

async function installService() {
  const { values } = parseArgs({ options: { mode: { type: "string" }, help: { type: "boolean" } } });
  if (values.help) {
    console.log("Usage: npm run service:install -- --mode pc|always-on\nInstalls and starts a per-user login service on Linux, macOS or Windows.\nConfigure a private tailnet BIND_ADDRESS in .local.env first.\nLinux always-on hosting also needs user lingering. Other systems require a signed-in user.");
    return;
  }
  if (!["pc", "always-on"].includes(values.mode)) throw new Error("Choose --mode pc or --mode always-on.");
  const root = fileURLToPath(new URL("../", import.meta.url)).replace(/\/$/, "");
  const localPath = join(root, ".local.env");
  const local = await readSettings(localPath, { optional: true });
  const address = process.env.BIND_ADDRESS ?? local.BIND_ADDRESS;
  if (!isTailnetAddress(address || "")) throw new Error("Set BIND_ADDRESS to this computer's Tailscale IPv4 address in .local.env first.");
  const port = process.env.PORT ?? local.PORT ?? "8787";
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw new Error("PORT must be between 1 and 65535.");
  // Validate the files the service will use, not just this shell's temporary environment.
  const secrets = await readSettings(join(root, ".dev.vars"), { secret: true });
  if (!secrets.PHONE_TOKEN || !secrets.BOT_TOKEN || secrets.PHONE_TOKEN === secrets.BOT_TOKEN) {
    throw new Error("Set up distinct private phone and bot credentials before installing the service.");
  }
  const run = (program, args, options = {}) => execFileSync(program, args, { stdio: "inherit", ...options });
  if (process.platform === "linux" && values.mode === "always-on") {
    const linger = run("loginctl", ["show-user", String(process.getuid()), "--property=Linger", "--value"], { encoding: "utf8", stdio: "pipe" }).trim();
    if (linger !== "yes") throw new Error("Always-on hosting needs user lingering. Ask the user to enable it, or choose --mode pc.");
  }
  const userId = process.platform === "win32" ? run("whoami", ["/user", "/fo", "csv", "/nh"], { encoding: "utf8", stdio: "pipe" }).match(/S-1-[0-9-]+/)?.[0] : undefined;
  const definition = startupDefinition({ platform: process.platform, root, node: process.execPath, home: homedir(), userId,
    linuxTemplate: process.platform === "linux" ? await readFile(new URL("../ops/phone-digest.service", import.meta.url), "utf8") : undefined });
  const directory = process.platform === "linux"
    ? join(process.env.XDG_CONFIG_HOME || join(homedir(), ".config"), "systemd/user")
    : process.platform === "darwin" ? join(homedir(), "Library/LaunchAgents")
      : join(process.env.LOCALAPPDATA || join(homedir(), "AppData/Local"), "PhoneInbox");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const destination = join(directory, definition.filename);
  // Persist only the effective service settings, retaining any other private local values.
  let settings = "";
  try { settings = await readFile(localPath, "utf8"); } catch (error) { if (error.code !== "ENOENT") throw error; }
  for (const [key, value] of Object.entries({ BIND_ADDRESS: address, PORT: port, HOSTING_MODE: values.mode })) {
    const line = `${key}="${value}"`;
    const pattern = new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=.*$`, "m");
    settings = pattern.test(settings) ? settings.replace(pattern, line) : settings.replace(/\n?$/, "\n") + line + "\n";
  }
  await writePrivate(localPath, settings, { replace: true, userId });
  await writePrivate(destination, definition.content, { replace: true, userId });
  if (process.platform === "linux") {
    for (const args of [["daemon-reload"], ["enable", "--force", "phone-digest.service"], ["restart", "phone-digest.service"]]) run("systemctl", ["--user", ...args]);
    run("systemctl", ["--user", "is-active", "phone-digest.service"]);
  } else if (process.platform === "darwin") {
    const domain = `gui/${process.getuid()}`;
    const target = `${domain}/${serviceLabel}`;
    if (spawnSync("launchctl", ["print", target], { stdio: "ignore" }).status === 0) run("launchctl", ["bootout", target]);
    run("launchctl", ["bootstrap", domain, destination]);
    run("launchctl", ["kickstart", "-k", target]);
  } else {
    run("schtasks", ["/Create", "/TN", windowsTask, "/XML", destination, "/F"]);
    run("schtasks", ["/Run", "/TN", windowsTask]);
  }
  console.log(`Installed ${values.mode} hosting. Run npm --silent run status to check the inbox. Re-run this installer if the checkout or Node path changes.`);
  if (process.platform !== "linux" && values.mode === "always-on") console.log("Keep this user signed in and prevent the computer from sleeping.");
}

installService().catch(error => { console.error(error.message); process.exitCode = 1; });
