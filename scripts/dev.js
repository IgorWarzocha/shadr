import { readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { parseEnv } from "node:util";
import { fileURLToPath } from "node:url";
import { isTailnetAddress } from "../src/local-network.js";

const root = fileURLToPath(new URL("../", import.meta.url));
let settings = {};
try { settings = parseEnv(await readFile(new URL("../.local.env", import.meta.url), "utf8")); }
catch (error) { if (error.code !== "ENOENT") throw error; }
const address = process.env.BIND_ADDRESS ?? settings.BIND_ADDRESS ?? "127.0.0.1";
const port = process.env.PORT ?? settings.PORT ?? "8787";
if (!isTailnetAddress(address) && address !== "127.0.0.1") throw new Error("BIND_ADDRESS must be a loopback or tailnet IPv4 address");
if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw new Error("PORT must be between 1 and 65535");

process.umask(0o077);
const child = spawn(process.execPath, [
  fileURLToPath(new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url)),
  "dev", "--local", "--ip", address, "--port", port, "--inspector-port", "0",
], { cwd: root, stdio: "inherit" });
const signals = ["SIGINT", "SIGTERM"];
const handlers = signals.map(signal => {
  const handler = () => child.kill(signal);
  process.on(signal, handler);
  return [signal, handler];
});
child.on("error", error => { console.error("Unable to start Wrangler:", error.message); process.exitCode = 1; });
child.on("close", code => {
  for (const [signal, handler] of handlers) process.removeListener(signal, handler);
  process.exitCode = code ?? 1;
});
