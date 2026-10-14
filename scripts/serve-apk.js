import { createServer } from "node:http";
import { open } from "node:fs/promises";
import { pipeline } from "node:stream";
import { createHash } from "node:crypto";
import { extname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { isTailnetAddress } from "../src/local-network.js";
import { readSettings } from "./client-settings.js";

export async function serveApk({ apk, host, port }) {
  if (!isTailnetAddress(host) && host !== "127.0.0.1") throw new Error("APK downloads must bind to a Tailscale IPv4 address or local testing address.");
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error("Choose a valid download port.");
  if (extname(apk).toLowerCase() !== ".apk") throw new Error("Choose an APK file, not a folder or configuration file.");
  const file = await open(apk, "r");
  let server;
  try {
    const info = await file.stat();
    if (!info.isFile() || info.size === 0) throw new Error("Choose a non-empty APK file.");
    const hash = createHash("sha256");
    for await (const chunk of file.createReadStream({ start: 0, end: info.size - 1, autoClose: false })) hash.update(chunk);
    const sha256 = hash.digest("hex");
    server = createServer((request, response) => {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      if (request.url !== "/shadr.apk" || !["GET", "HEAD"].includes(request.method)) {
        response.writeHead(404, { "Content-Type": "text/plain" });
        response.end("Not found");
        return;
      }
      response.writeHead(200, { "Content-Type": "application/vnd.android.package-archive",
        "Content-Disposition": 'attachment; filename="shadr.apk"', "Content-Length": info.size });
      if (request.method === "HEAD") { response.end(); return; }
      pipeline(file.createReadStream({ start: 0, end: info.size - 1, autoClose: false }), response, () => {
        // Client cancellation closes only its stream, never the shared APK descriptor.
      });
    });
    await new Promise((accept, reject) => {
      server.once("error", reject);
      server.listen(port, host, () => { server.removeListener("error", reject); accept(); });
    });
    return { server, sha256, close: async () => {
      const closed = new Promise((accept, reject) => server.close(error => error ? reject(error) : accept()));
      server.closeAllConnections();
      await closed;
      await file.close();
    } };
  } catch (error) {
    server?.close();
    await file.close();
    throw error;
  }
}

async function main() {
  const { values } = parseArgs({ options: { apk: { type: "string" }, bind: { type: "string" },
    port: { type: "string", default: "8788" }, help: { type: "boolean" } } });
  if (values.help) {
    console.log("Usage: npm run apk:serve -- [--apk path/to/signed.apk] [--bind tailnetIPv4] [--port 8788]\nServes only /shadr.apk, never the checkout or credentials. Stop with Ctrl+C.\nDefaults to the locally built signed release APK. No debug fallback.");
    return;
  }
  const root = fileURLToPath(new URL("../", import.meta.url));
  const local = await readSettings(resolve(root, ".local.env"), { optional: true });
  const host = values.bind ?? process.env.BIND_ADDRESS ?? local.BIND_ADDRESS;
  if (!host) throw new Error("Configure the computer's Tailscale address first, or supply --bind.");
  if (!/^\d+$/.test(values.port) || Number(values.port) < 1 || Number(values.port) > 65535) throw new Error("Choose a download port between 1 and 65535.");
  const apk = values.apk ? resolve(values.apk) : resolve(root, "android/app/build/outputs/apk/release/app-release.apk");
  const running = await serveApk({ apk, host, port: Number(values.port) });
  console.log(`Download: http://${host}:${values.port}/shadr.apk\nSHA-256: ${running.sha256}\nOnly the APK is shared. Stop this download server when installation is complete.`);
  let closing = false;
  const stop = async () => {
    if (closing) return;
    closing = true;
    try { await running.close(); } catch { process.exitCode = 1; }
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
