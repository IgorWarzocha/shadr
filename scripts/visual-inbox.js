// Local emulator fixture only. Never reads deployment secrets or the real inbox.
import { createServer } from "node:http";
import { health } from "../src/health.js";

const token = "capture-only-phone-token";
let snapshot = null;
const server = createServer(async (request, response) => {
  response.setHeader("Content-Type", "application/json");
  response.setHeader("Cache-Control", "no-store");
  const reply = (status, body) => { response.writeHead(status); response.end(JSON.stringify(body)); };
  if (request.headers.authorization !== `Bearer ${token}`) {
    reply(401, { state: "credentials_rejected", nextAction: "Use the documented fake capture token." });
    return;
  }
  if (request.method === "GET" && request.url === "/health") {
    reply(200, { role: "phone", ...health({ lastPostAt: snapshot?.capturedAt ?? null,
      listenerBound: Number(Boolean(snapshot?.listenerBound)),
      notificationAccessGranted: snapshot ? Number(snapshot.notificationAccessGranted) : null }),
      sequence: snapshot?.sequence ?? 0 });
    return;
  }
  if (request.method === "GET" && request.url === "/capture-state") {
    reply(200, { snapshot });
    return;
  }
  if (request.method === "POST" && request.url === "/notifications") {
    const chunks = [];
    let size = 0;
    try {
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 2_000_000) { reply(413, { error: "Too large" }); return; }
        chunks.push(chunk);
      }
      const value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      if (!Array.isArray(value.notifications) || !Number.isSafeInteger(value.sequence)) throw new Error("Invalid snapshot");
      snapshot = value;
      reply(200, { dryRun: true, dismiss: [], wouldDismiss: [], requestIds: {} });
    } catch { reply(400, { error: "Invalid capture snapshot" }); }
    return;
  }
  reply(404, { error: "Not found" });
});
server.listen(8799, "127.0.0.1", () => console.log("Emulator fixture listening on loopback port 8799; fake credentials only."));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close());
