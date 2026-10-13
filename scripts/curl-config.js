import { readFile } from "node:fs/promises";
import { parseEnv } from "node:util";

const role = process.argv[2];
if (!["phone", "bot"].includes(role)) throw new Error("Usage: node scripts/curl-config.js phone|bot");
const vars = parseEnv(await readFile(".dev.vars", "utf8"));
const token = vars[role === "phone" ? "PHONE_TOKEN" : "BOT_TOKEN"];
if (!/^[a-f0-9]{64}$/.test(token || "")) throw new Error("Missing or unexpected token format");
// Pipe directly to curl --config -, never into a terminal transcript.
process.stdout.write(`header = "Authorization: Bearer ${token}"\n`);
