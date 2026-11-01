// Logins last 30 minutes: the apps fetch a new one for each call (reused until 5 minutes before it ends).
const TTL = 1800;
const SKEW = 300;
const PER_ID_DAILY = 30;
const GLOBAL_DAILY = 3000;
// Per internet address (CF-Connecting-IP), so one place can't use up everyone's daily cap with made-up ids.
// A household shares one address, so this is a few installs' worth.
const PER_IP_DAILY = 60;
const MAX_BODY = 1024;
const HEX64 = /^[0-9a-f]{64}$/;

function json(status, data) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

async function verifySig(secret, id, t, sig) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  return crypto.subtle.verify("HMAC", key, hexToBytes(sig), enc.encode(id + "." + t));
}

// A body over MAX_BODY bytes is refused: by its Content-Length before anything is read, and otherwise
// (no length given) as soon as the bytes read pass it. Returns TOO_LARGE, null (not a JSON object) or the object.
const TOO_LARGE = Symbol("too_large");

async function readBody(request) {
  const len = request.headers.get("content-length");
  if (len !== null && !(/^\d{1,7}$/.test(len.trim()) && Number(len.trim()) <= MAX_BODY)) return TOO_LARGE;
  const bytes = new Uint8Array(MAX_BODY);
  let size = 0;
  if (request.body) {
    const reader = request.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (size + value.length > MAX_BODY) {
        await reader.cancel().catch(() => {});
        return TOO_LARGE;
      }
      bytes.set(value, size);
      size += value.length;
    }
  }
  try {
    const body = JSON.parse(new TextDecoder().decode(bytes.subarray(0, size)));
    return body && typeof body === "object" ? body : null;
  } catch {
    return null;
  }
}

// The address is never stored or logged. The count is kept under an HMAC of the address group and the day,
// keyed with a secret only the Worker has (TURN_KEY_API_TOKEN), so the stored key can't be reversed by trying
// every address. IPv6 addresses count by their /48 (the most a home or a phone line is usually given), so one
// customer can't dodge the cap by moving around their own addresses.
export function addressGroup(raw) {
  const ip = String(raw || "unknown").trim().toLowerCase().split("%")[0];
  if (!ip.includes(":")) return ip;
  let text = ip;
  const v4 = text.match(/^(.*:)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const b = v4.slice(2).map(Number);
    if (b.some((x) => x > 255)) return ip;
    text = v4[1] + ((b[0] << 8) | b[1]).toString(16) + ":" + ((b[2] << 8) | b[3]).toString(16);
  }
  const halves = text.split("::");
  if (halves.length > 2) return ip;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const fill = 8 - head.length - tail.length;
  if (halves.length === 2 ? fill < 1 : fill !== 0) return ip;
  const groups = [...head, ...Array(Math.max(fill, 0)).fill("0"), ...tail];
  if (!groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return ip;
  const n = groups.map((g) => parseInt(g, 16));
  // An IPv4 address written as IPv6 (::ffff:a.b.c.d) counts as that IPv4 address.
  if (n.slice(0, 5).every((x) => x === 0) && n[5] === 0xffff) {
    return [n[6] >> 8, n[6] & 255, n[7] >> 8, n[7] & 255].join(".");
  }
  return n.slice(0, 3).map((x) => x.toString(16)).join(":") + "::/48";
}

async function ipKey(secret, ip, day) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, enc.encode("ip:" + day + ":" + addressGroup(ip)));
  return [...new Uint8Array(mac)].slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// One SQLite-backed Durable Object keeps every daily count. All counting for one login happens in one
// synchronous step inside it, so two requests at the same moment can't both slip under a cap.
// Each login writes 3 rows (everyone, this install, this address). Rows from earlier days are deleted
// the first time the object sees a new day.
export const SQL = {
  schema:
    "CREATE TABLE IF NOT EXISTS counts (day TEXT NOT NULL, k TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, k)) WITHOUT ROWID",
  wipe: "DELETE FROM counts WHERE day < ?",
  read: "SELECT k, n FROM counts WHERE day = ? AND k IN (?, ?, ?)",
  bump: "INSERT INTO counts (day, k, n) VALUES (?, ?, 1) ON CONFLICT (day, k) DO UPDATE SET n = n + 1",
};

const DAY = /^\d{4}-\d{2}-\d{2}$/;

export class RelayLimits {
  constructor(ctx) {
    this.sql = ctx.storage.sql;
    this.sql.exec(SQL.schema);
    this.wipedBefore = null;
  }

  take(day, id, ip) {
    if (this.wipedBefore === null || day > this.wipedBefore) {
      this.sql.exec(SQL.wipe, day);
      this.wipedBefore = day;
    }
    const g = "g";
    const u = "u:" + id;
    const i = "i:" + ip;
    const have = new Map(this.sql.exec(SQL.read, day, g, u, i).toArray().map((r) => [r.k, Number(r.n)]));
    if ((have.get(g) || 0) >= GLOBAL_DAILY) return "global";
    if ((have.get(i) || 0) >= PER_IP_DAILY) return "ip";
    if ((have.get(u) || 0) >= PER_ID_DAILY) return "id";
    for (const k of [g, u, i]) this.sql.exec(SQL.bump, day, k);
    return "ok";
  }

  async fetch(request) {
    let body;
    try {
      body = JSON.parse(await request.text());
    } catch {
      return json(400, { error: "bad_request" });
    }
    const { day, id, ip } = body || {};
    if (typeof day !== "string" || !DAY.test(day) || typeof id !== "string" || !HEX64.test(id) || typeof ip !== "string" || !/^[0-9a-f]{32}$/.test(ip)) {
      return json(400, { error: "bad_request" });
    }
    return json(200, { result: this.take(day, id, ip) });
  }
}

async function takeQuota(env, id, ip, nowSec) {
  const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
  const stub = env.LIMITS.get(env.LIMITS.idFromName("limits"));
  const res = await stub.fetch("https://limits/take", {
    method: "POST",
    body: JSON.stringify({ day, id, ip: await ipKey(env.TURN_KEY_API_TOKEN, ip, day) }),
  });
  if (res.status !== 200) throw new Error("limits");
  const { result } = await res.json();
  if (!["ok", "global", "ip", "id"].includes(result)) throw new Error("limits");
  return result;
}

async function handleTurn(request, env, nowSec) {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN || !env.APP_SECRET) {
    return json(503, { error: "not_configured" });
  }

  const body = await readBody(request);
  if (body === TOO_LARGE) return json(413, { error: "too_large" });
  if (!body) return json(400, { error: "bad_request" });
  const { id, t, sig } = body;
  if (typeof id !== "string" || !HEX64.test(id)) return json(400, { error: "bad_request" });
  if (typeof sig !== "string" || !HEX64.test(sig)) return json(400, { error: "bad_request" });
  if (!Number.isInteger(t)) return json(400, { error: "bad_request" });
  if (Math.abs(nowSec - t) > SKEW) return json(401, { error: "stale" });
  if (!(await verifySig(env.APP_SECRET, id, t, sig))) return json(401, { error: "bad_sig" });

  // Without the limits object the caps can't be kept, so nothing is handed out:
  // the daily caps are the only thing guarding the TURN account.
  if (!env.LIMITS) return json(503, { error: "limits_unavailable" });
  let quota;
  try {
    quota = await takeQuota(env, id, request.headers.get("CF-Connecting-IP") || "unknown", nowSec);
  } catch {
    return json(503, { error: "limits_unavailable" });
  }
  if (quota !== "ok") return json(429, { error: quota + "_limit" });

  let upstream;
  try {
    upstream = await fetch(
      "https://rtc.live.cloudflare.com/v1/turn/keys/" +
        encodeURIComponent(env.TURN_KEY_ID) +
        "/credentials/generate-ice-servers",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + env.TURN_KEY_API_TOKEN,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: TTL }),
      },
    );
  } catch {
    return json(502, { error: "upstream" });
  }
  if (upstream.status !== 201 && upstream.status !== 200) return json(502, { error: "upstream" });

  let data;
  try {
    data = await upstream.json();
  } catch {
    return json(502, { error: "upstream" });
  }

  const servers = Array.isArray(data && data.iceServers) ? data.iceServers : [];
  const urls = [];
  let username = null;
  let credential = null;
  for (const s of servers) {
    const list = Array.isArray(s.urls) ? s.urls : [s.urls];
    const turn = list.filter((u) => typeof u === "string" && /^turns?:/.test(u));
    if (turn.length && s.username && s.credential && (!username || s.username === username)) {
      urls.push(...turn);
      username = username || s.username;
      credential = credential || s.credential;
    }
  }
  if (!urls.length || !username || !credential) return json(502, { error: "upstream" });

  return json(200, { urls, username, credential, ttl: TTL });
}

export async function handle(request, env, nowSec = Math.floor(Date.now() / 1000)) {
  const { pathname } = new URL(request.url);
  if (pathname === "/health") {
    return request.method === "GET" ? json(200, { ok: true }) : json(405, { error: "method" });
  }
  if (pathname === "/turn") {
    return request.method === "POST" ? handleTurn(request, env, nowSec) : json(405, { error: "method" });
  }
  return json(404, { error: "not_found" });
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
