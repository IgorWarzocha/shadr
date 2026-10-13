// The local HTTP mode must never expose the inbox on a public or wildcard address.
export function isTailnetAddress(host) {
  if (!/^\d+\.\d+\.\d+\.\d+$/.test(host)) return false;
  const octets = host.split(".").map(Number);
  return octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127 &&
    octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255);
}

export function isLocalHost(host) {
  const magicDns = /^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+ts\.net$/i.test(host);
  return isTailnetAddress(host) || magicDns || ["localhost", "127.0.0.1", "[::1]"].includes(host);
}
