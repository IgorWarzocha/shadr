// The real relay with disposable D1 and fake credentials. Never reads .dev.vars,
// .local.env or deployment state. This is development validation, not setup.
import { createServer } from "node:http";
import { readFile, readdir } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare } from "miniflare";

const source = await build({ entryPoints: ["src/worker.js"], bundle: true, write: false, format: "esm", platform: "browser" });
const runtime = new Miniflare({ workers: [{ config: {
  name: "inbox-capture", compatibilityDate: "2026-07-30",
  manifest: { mainModule: "worker.js", modules: { "worker.js": { type: "esm", contents: source.outputFiles[0].text } } },
  env: {
    PHONE_TOKEN: { type: "text", value: "capture-only-phone-token" },
    BOT_TOKEN: { type: "text", value: "capture-only-bot-token" },
    MODE: { type: "text", value: "local" },
    DB: { type: "d1", id: "capture-db" },
  },
} }] });

let server;
try {
  const db = await runtime.getD1Database("DB");
  for (const file of (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort()) {
    const migration = await readFile("migrations/" + file, "utf8");
    await db.batch(migration.split(";").map(sql => sql.trim()).filter(Boolean).map(sql => db.prepare(sql)));
  }
  server = createServer(async (request, response) => {
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 2_000_000) { response.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      const result = await runtime.dispatchFetch("http://127.0.0.1" + request.url, {
        method: request.method, headers: request.headers,
        body: ["GET", "HEAD"].includes(request.method) ? undefined : Buffer.concat(chunks),
      });
      response.writeHead(result.status, Object.fromEntries(result.headers));
      response.end(Buffer.from(await result.arrayBuffer()));
    } catch {
      response.writeHead(500).end();
    }
  });
  await new Promise((accept, reject) => {
    server.once("error", reject);
    server.listen(8799, "127.0.0.1", accept);
  });
  console.log("Real relay on loopback port 8799 with disposable D1 and fake credentials only.");
} catch (error) {
  server?.close();
  await runtime.dispose();
  throw error;
}
let closing = false;
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, async () => {
  if (closing) return;
  closing = true;
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
  await runtime.dispose();
});
