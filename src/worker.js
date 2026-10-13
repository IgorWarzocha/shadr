import { createRemoteJWKSet, jwtVerify } from "jose";
import { body, snapshot, dismissal, HttpError } from "./contract.js";
import { device, ingest, queue, readInbox } from "./inbox.js";
import { health } from "./health.js";
import { isLocalHost } from "./local-network.js";

let accessKeys;
let accessIssuer;
async function authenticate(request, env) {
  if (!env.PHONE_TOKEN || !env.BOT_TOKEN || env.PHONE_TOKEN === env.BOT_TOKEN) {
    throw new HttpError(503, "Distinct bearer tokens must be configured");
  }
  if (!["cloud", "local"].includes(env.MODE)) throw new HttpError(503, "Invalid runtime mode");
  if (env.MODE === "local") {
    const host = new URL(request.url).hostname;
    if (!isLocalHost(host)) {
      throw new HttpError(503, "Local mode requires a tailnet or loopback address");
    }
  }
  if (env.MODE === "cloud") {
    if (!env.ACCESS_TEAM || !/^[a-z0-9-]+$/.test(env.ACCESS_TEAM) || !env.ACCESS_AUD) {
      throw new HttpError(503, "Access must be configured");
    }
    const issuer = `https://${env.ACCESS_TEAM}.cloudflareaccess.com`;
    if (accessIssuer !== issuer) {
      accessIssuer = issuer;
      accessKeys = createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`));
    }
    try {
      await jwtVerify(request.headers.get("Cf-Access-Jwt-Assertion") || "", accessKeys,
        { issuer, audience: env.ACCESS_AUD, algorithms: ["RS256"] });
    } catch { throw new HttpError(401, "Cloudflare Access required"); }
  }
  const token = request.headers.get("Authorization");
  if (token === `Bearer ${env.PHONE_TOKEN}`) return "phone";
  if (token === `Bearer ${env.BOT_TOKEN}`) return "bot";
  throw new HttpError(401, "Bearer token required");
}
function json(value, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
export default {
  async fetch(request, env) {
    try {
      const role = await authenticate(request, env);
      const path = new URL(request.url).pathname;
      const route = `${request.method} ${path}`;
      const now = new Date();
      if (route === "GET /health") {
        const current = await device(env.DB);
        return json({ ...health(current, now), role, sequence: current.sequence });
      }
      if (route === "POST /notifications") {
        if (role !== "phone") throw new HttpError(403, "Phone token required");
        return json(await ingest(env.DB, snapshot(await body(request)), now));
      }
      if (["GET /notifications", "POST /dismiss"].includes(route)) {
        if (role !== "bot") throw new HttpError(403, "Bot token required");
        if (route === "POST /dismiss") return json(await queue(env.DB, dismissal(await body(request)), now));
        const inbox = await readInbox(env.DB, now);
        return json(inbox);
      }
      throw new HttpError(404, "Not found");
    } catch (error) {
      if (error instanceof HttpError) {
        const states = {
          400: ["invalid_request", "Check the request."],
          401: ["credentials_rejected", "Check the configured credentials."],
          403: ["credentials_rejected", "Check the configured credentials."],
          404: ["not_found", "Check the endpoint."],
          409: ["stale_sequence", "Read health and retry with a newer sequence."],
          413: ["invalid_request", "Reduce the request size."],
        };
        const [state, nextAction] = states[error.status] || ["relay_unavailable", "Try again later."];
        return json({ state, nextAction }, error.status);
      }
      // Never log request bodies or notification content.
      console.error("Inbox request failed", error.name);
      return json({ state: "relay_unavailable", nextAction: "Try again later." }, 500);
    }
  },
};
