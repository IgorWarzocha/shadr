import { resolve, extname } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import encodeQR from "qr";
import { isLocalHost } from "../src/local-network.js";
import { readSettings, serverAddress } from "./client-settings.js";
import { writePrivate } from "./private-file.js";

const qrOptions = { ecc: "medium", border: 4 };

export function pairingPayload({ endpoint, phoneToken, accessClientId, accessClientSecret }) {
  if (typeof endpoint !== "string" || endpoint.length > 2048 || /[\\\s\u0000-\u001f\u007f]/u.test(endpoint)) {
    throw new Error("Use a complete server URL without spaces or backslashes.");
  }
  let url;
  try { url = new URL(endpoint); }
  catch { throw new Error("Use a complete server URL, including http:// or https://."); }
  if (url.username || url.password || url.search || url.hash || !["", "/"].includes(url.pathname) || url.port === "0") {
    throw new Error("Server URL must not contain credentials, a path, query or fragment, and needs a valid port.");
  }
  if (["localhost", "127.0.0.1", "[::1]", "0.0.0.0", "[::]"].includes(url.hostname) || url.hostname.startsWith("127.")) {
    throw new Error("Loopback addresses point to the phone itself. Configure a tailnet address or an HTTPS server.");
  }
  const tailnet = isLocalHost(url.hostname);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && tailnet)) {
    throw new Error("HTTP is only allowed for Tailscale addresses. Other servers require HTTPS.");
  }
  if (typeof phoneToken !== "string" || !phoneToken || phoneToken.length > 1024 || /[\s\u0000-\u001f\u007f]/u.test(phoneToken)) {
    throw new Error("Credentials need a non-empty PHONE_TOKEN without whitespace or control characters.");
  }
  const payload = { type: "phone-inbox", version: 1, endpoint: url.origin, phoneToken };
  if (accessClientId || accessClientSecret) {
    if (tailnet || url.protocol !== "https:") throw new Error("Access credentials are only for a non-tailnet HTTPS server.");
    for (const [value, limit] of [[accessClientId, 1024], [accessClientSecret, 2048]]) {
      if (typeof value !== "string" || !value.trim() || value.length > limit || /[\u0000-\u001f\u007f]/u.test(value)) {
        throw new Error("Supply both ACCESS_CLIENT_ID and ACCESS_CLIENT_SECRET as non-empty, single-line values.");
      }
    }
    payload.accessClientId = accessClientId;
    payload.accessClientSecret = accessClientSecret;
  }
  const encoded = JSON.stringify(payload);
  if (Buffer.byteLength(encoded, "utf8") > 2048) throw new Error("Pairing settings exceed the 2048-byte QR limit.");
  return encoded;
}

export async function pairPhone(args = process.argv.slice(2)) {
  const { values } = parseArgs({ args, options: {
    endpoint: { type: "string" }, credentials: { type: "string", default: ".dev.vars" },
    output: { type: "string" }, help: { type: "boolean" },
  } });
  if (values.help) {
    console.log("Usage: npm run pair -- [--endpoint https://inbox.example.com] [--credentials private.env] [--output private.gif]\n\nUses the existing PHONE_TOKEN. Defaults to the tailnet address in .local.env.\nWithout --output, displays a secret QR in an interactive terminal.\n--output saves a private GIF instead and refuses to overwrite any file.\nOptional ACCESS_CLIENT_ID and ACCESS_CLIENT_SECRET belong in the credentials file, never command arguments.");
    return;
  }
  if (!values.output && !process.stdout.isTTY) {
    throw new Error("The QR contains credentials. Run in an interactive terminal, or use --output private.gif instead of piping it into logs.");
  }
  if (values.output && extname(values.output).toLowerCase() !== ".gif") throw new Error("Use a .gif output file.");
  const credentials = await readSettings(values.credentials, { secret: true });
  const local = await readSettings(".local.env", { optional: true });
  const endpoint = serverAddress({ endpoint: values.endpoint, local });
  const payload = pairingPayload({ endpoint, phoneToken: credentials.PHONE_TOKEN,
    accessClientId: credentials.ACCESS_CLIENT_ID, accessClientSecret: credentials.ACCESS_CLIENT_SECRET });
  if (values.output) {
    await writePrivate(values.output, encodeQR(payload, "gif", { ...qrOptions, scale: 6 }));
    console.log("Saved a private pairing QR. Open it locally, then use Scan QR or Import QR image in Phone inbox.\nIt contains your phone credentials. Do not post it publicly. Delete it when finished.");
  } else {
    const qr = encodeQR(payload, "term", qrOptions);
    // Wrapping a QR changes its geometry and makes it unreadable.
    const width = encodeQR(payload, "raw", qrOptions).length * 2;
    if (process.stdout.columns && process.stdout.columns < width) {
      throw new Error(`Widen this terminal to at least ${width} columns, or use --output private.gif.`);
    }
    console.log("Private pairing QR. Anyone who can scan this receives your phone credentials.\nIn Phone inbox, choose Scan QR, then check the server and Test and connect.");
    console.log(qr);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  pairPhone().catch(error => {
    console.error(error.code === "EEXIST" ? "Output already exists. Choose a new filename or delete the previous QR." : error.message);
    process.exitCode = 1;
  });
}
