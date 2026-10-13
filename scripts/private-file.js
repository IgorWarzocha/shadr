import { open, rename, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execute = promisify(execFile);

export function windowsPrivacyCommands(path, userId) {
  if (!/^S-1-[0-9-]+$/.test(userId || "")) throw new Error("Could not identify the current Windows user.");
  const literal = value => "'" + value.replaceAll("'", "''") + "'";
  const check = `$a=Get-Acl -LiteralPath ${literal(path)}; $r=$a.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]); ` +
    `if (!$a.AreAccessRulesProtected -or $r.Count -ne 1 -or $r[0].IdentityReference.Value -ne ${literal(userId)} ` +
    `-or $r[0].AccessControlType -ne 'Allow' -or ($r[0].FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::FullControl) -ne [System.Security.AccessControl.FileSystemRights]::FullControl) { exit 1 }`;
  return [
    ["icacls", [path, "/inheritance:r", "/grant:r", `*${userId}:(F)`]],
    ["powershell.exe", ["-NoProfile", "-NonInteractive", "-EncodedCommand", Buffer.from(check, "utf16le").toString("base64")]],
  ];
}

export async function writePrivate(path, content, { replace = false, userId } = {}) {
  const destination = resolve(path);
  const temporary = replace ? destination + ".tmp" : destination;
  const file = await open(temporary, "wx", 0o600);
  let complete = false;
  try {
    if (process.platform === "win32") {
      const sid = userId ?? (await execute("whoami", ["/user", "/fo", "csv", "/nh"])).stdout.match(/S-1-[0-9-]+/)?.[0];
      // An empty file may inherit a broad ACL. Protect and verify it before writing any secret bytes.
      for (const [program, args] of windowsPrivacyCommands(temporary, sid)) await execute(program, args);
    }
    await file.writeFile(content);
    complete = true;
  } finally {
    await file.close();
    if (!complete) await rm(temporary, { force: true });
  }
  if (replace) {
    try { await rename(temporary, destination); }
    finally { await rm(temporary, { force: true }); }
  }
}
