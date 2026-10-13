import { join } from "node:path";

export const serviceLabel = "dev.howaboua.phone-inbox";
export const windowsTask = "Phone inbox";

function xml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&apos;");
}
function unitPath(value) {
  return value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("%", "%%");
}

export function startupDefinition({ platform, root, node, home, userId, linuxTemplate }) {
  for (const value of [root, node, home]) {
    if (typeof value !== "string" || !value || /[\u0000-\u001f\u007f]/u.test(value)) throw new Error("Unsupported startup path.");
  }
  if (platform === "linux") {
    if (!linuxTemplate) throw new Error("Missing Linux service template.");
    return { filename: "phone-digest.service", content: linuxTemplate
      .replaceAll("@PROJECT_DIR@", unitPath(root)).replaceAll("@NODE@", unitPath(node))
      .replaceAll("@WORKING_DIR@", root.replaceAll("%", "%%")) };
  }
  if (platform === "darwin") {
    const argumentsXml = [node, join(root, "scripts/dev.js")].map(value => `<string>${xml(value)}</string>`).join("");
    return { filename: `${serviceLabel}.plist`, content: `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>${serviceLabel}</string>
<key>ProgramArguments</key><array>${argumentsXml}</array>
<key>WorkingDirectory</key><string>${xml(root)}</string>
<key>EnvironmentVariables</key><dict><key>WRANGLER_SEND_METRICS</key><string>false</string><key>CI</key><string>true</string></dict>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>10</integer>
<key>Umask</key><integer>63</integer>
</dict></plist>
` };
  }
  if (platform === "win32") {
    if (!/^S-1-[0-9-]+$/.test(userId || "")) throw new Error("Could not identify the current Windows user.");
    const quote = value => "'" + value.replaceAll("'", "''") + "'";
    // A hidden PowerShell host waits for Node and propagates its exit code to Task Scheduler.
    const command = `& ${quote(node)} ${quote(root + "\\scripts\\dev.js")}; exit $LASTEXITCODE`;
    const encoded = Buffer.from(command, "utf16le").toString("base64");
    return { filename: "phone-inbox-task.xml", content: `<?xml version="1.0" encoding="UTF-8"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
<Triggers><LogonTrigger><Enabled>true</Enabled><UserId>${userId}</UserId></LogonTrigger></Triggers>
<Principals><Principal id="Owner"><UserId>${userId}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
<Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable><Enabled>true</Enabled><Hidden>true</Hidden><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure></Settings>
<Actions Context="Owner"><Exec><Command>powershell.exe</Command><Arguments>-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ${encoded}</Arguments><WorkingDirectory>${xml(root)}</WorkingDirectory></Exec></Actions>
</Task>
` };
  }
  throw new Error("Login startup supports Linux, macOS and Windows.");
}
