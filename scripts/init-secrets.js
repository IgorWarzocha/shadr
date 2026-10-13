import { randomBytes } from "node:crypto";
import { writePrivate } from "./private-file.js";

// Exclusive creation prevents accidental token rotation on reruns.
const phone = randomBytes(32).toString("hex");
const bot = randomBytes(32).toString("hex");
await writePrivate(".dev.vars", `PHONE_TOKEN=${phone}\nBOT_TOKEN=${bot}\n`);
console.log("Created private .dev.vars with distinct tokens. Keep them out of chat.");
