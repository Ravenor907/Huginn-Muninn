// Logins last 30 minutes: the apps fetch a new one for each call (reused until 5 minutes before it ends).
const TTL = 1800;
const SKEW = 300;
const PER_ID_DAILY = 30;
// Per internet address (CF-Connecting-IP), so one place can't use up everyone's daily cap with made-up ids.
// A household shares one address, so this is a few installs' worth.
const PER_IP_DAILY = 60;
// Round 14 (relay.md "Install tokens"): an install registers its id once, with a few seconds' proof of work, and gets a
// short-lived token signed by the Worker (renewed with every login). Logins with a token count only against that
// install, its address and a free-plan safety cap far above normal use. Requests without a token (apps before 1.36,
// which only know APP_SECRET) share a pool of their own, so whoever pulls APP_SECRET out of an app can use up only
// that pool, never the logins of installs with a token.
const LEGACY_DAILY = 1000;
const TOKEN_DAILY = 12000;
// New registrations: per address and for everyone. A full day refuses new registrations, never logins.
const REG_PER_IP_DAILY = 6;
const REG_DAILY = 2000;
// Every cap full: (12,000 + 1000) logins x 3 rows + 2000 registrations x 2 rows = 43,000 rows written a day, and as many
// deleted the next day, under the free plan's 100,000.
// Leading zero bits of the registration hash (about 260,000 SHA-256 tries on average, well under a second on a phone).
export const POW_BITS = 18;
// A token is good for this long after it was issued (each login with it issues a new one).
export const TOKEN_TTL = 7 * 86400;
const MAX_BODY = 1024;
const HEX64 = /^[0-9a-f]{64}$/;
const TOKEN = /^(\d{1,12})\.([0-9a-f]{32})$/;

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

async function hmacHex(secret, text) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, enc.encode(text));
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// The registration's proof of work: SHA-256 of "huginn-relay pow v1:" + id + ":" + t + ":" + pow (pow in decimal)
// starts with POW_BITS zero bits. Binding t means work can't be stored up for later than the 300 s skew allows.
export async function powOk(id, t, pow, bits = POW_BITS) {
  if (!Number.isSafeInteger(pow) || pow < 0) return false;
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode("huginn-relay pow v1:" + id + ":" + t + ":" + pow)));
  let left = bits;
  for (const b of d) {
    if (left <= 0) return true;
    if (left >= 8) {
      if (b !== 0) return false;
      left -= 8;
    } else {
      return (b >> (8 - left)) === 0;
    }
  }
  return left <= 0;
}

// The key tokens are signed with: TOKEN_KEY when set, else derived from TURN_KEY_API_TOKEN (a Worker-only secret),
// so no new secret has to be set up. Changing either makes every install register again, nothing worse.
function tokenKey(env) {
  return env.TOKEN_KEY || "relay token v1:" + env.TURN_KEY_API_TOKEN;
}

export async function makeToken(env, id, exp) {
  return exp + "." + (await hmacHex(tokenKey(env), "tok:" + id + ":" + exp)).slice(0, 32);
}

// "ok", "bad" (not this Worker's, or not this id's) or "expired".
export async function checkToken(env, id, tok, nowSec) {
  const m = typeof tok === "string" ? tok.match(TOKEN) : null;
  if (!m) return "bad";
  const exp = Number(m[1]);
  if ((await makeToken(env, id, exp)) !== tok) return "bad";
  if (exp <= nowSec || exp > nowSec + TOKEN_TTL + SKEW) return "expired";
  return "ok";
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
// Each login writes 3 rows (its pool, this install, this address); each registration 2 (all registrations, this
// address). Rows from earlier days are deleted the first time the object sees a new day.
// What each kind of request counts against: [row key, daily cap, the error's name]. The address and install rows are
// shared by logins with and without a token; the pools are not.
export function plan(kind, id, ip) {
  if (kind === "reg") return [["rg", REG_DAILY, "reg"], ["r:" + ip, REG_PER_IP_DAILY, "ip_reg"]];
  const pool = kind === "tok" ? ["t", TOKEN_DAILY, "global"] : ["g", LEGACY_DAILY, "global"];
  return [pool, ["i:" + ip, PER_IP_DAILY, "ip"], ["u:" + id, PER_ID_DAILY, "id"]];
}
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

  // kind: "legacy" (no token), "tok" (a login with a good token) or "reg" (a registration).
  take(day, id, ip, kind = "legacy") {
    if (this.wipedBefore === null || day > this.wipedBefore) {
      this.sql.exec(SQL.wipe, day);
      this.wipedBefore = day;
    }
    const rows = plan(kind, id, ip);
    const keys = rows.map((r) => r[0]);
    while (keys.length < 3) keys.push(keys[0]);
    const have = new Map(this.sql.exec(SQL.read, day, ...keys).toArray().map((r) => [r.k, Number(r.n)]));
    for (const [k, cap, err] of rows) if ((have.get(k) || 0) >= cap) return err;
    for (const [k] of rows) this.sql.exec(SQL.bump, day, k);
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
    const kind = body && body.kind !== undefined ? body.kind : "legacy";
    if (typeof day !== "string" || !DAY.test(day) || typeof id !== "string" || !HEX64.test(id) || typeof ip !== "string" || !/^[0-9a-f]{32}$/.test(ip)
        || !["legacy", "tok", "reg"].includes(kind)) {
      return json(400, { error: "bad_request" });
    }
    return json(200, { result: this.take(day, id, ip, kind) });
  }
}

async function takeQuota(env, id, ip, nowSec, kind) {
  const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
  const stub = env.LIMITS.get(env.LIMITS.idFromName("limits"));
  const res = await stub.fetch("https://limits/take", {
    method: "POST",
    body: JSON.stringify({ day, id, ip: await ipKey(env.TURN_KEY_API_TOKEN, ip, day), kind }),
  });
  if (res.status !== 200) throw new Error("limits");
  const { result } = await res.json();
  if (!["ok", "global", "ip", "id", "reg", "ip_reg"].includes(result)) throw new Error("limits");
  return result;
}

// What /turn and /register both check first: the body, the id, the time and the APP_SECRET signature.
// Returns the body, or a Response to send back. A stale time says the Worker's time ("now", whole seconds) so the
// app can correct its clock for one more try (the signature still needs APP_SECRET, so nothing new is exposed).
async function checkRequest(request, env, nowSec) {
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
  if (Math.abs(nowSec - t) > SKEW) return json(401, { error: "stale", now: nowSec });
  if (!(await verifySig(env.APP_SECRET, id, t, sig))) return json(401, { error: "bad_sig" });
  // Without the limits object the caps can't be kept, so nothing is handed out:
  // the daily caps are the only thing guarding the TURN account.
  if (!env.LIMITS) return json(503, { error: "limits_unavailable" });
  return body;
}

async function quotaOr(env, request, id, nowSec, kind) {
  try {
    return await takeQuota(env, id, request.headers.get("CF-Connecting-IP") || "unknown", nowSec, kind);
  } catch {
    return null;
  }
}

async function handleRegister(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, t, pow } = body;
  if (!Number.isSafeInteger(pow) || pow < 0) return json(400, { error: "bad_request" });
  if (!(await powOk(id, t, pow))) return json(401, { error: "bad_pow" });
  const quota = await quotaOr(env, request, id, nowSec, "reg");
  if (quota === null) return json(503, { error: "limits_unavailable" });
  if (quota !== "ok") return json(429, { error: quota + "_limit" });
  const exp = nowSec + TOKEN_TTL;
  return json(200, { tok: await makeToken(env, id, exp), exp });
}

async function handleTurn(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, tok } = body;
  // With a token: it must be this Worker's, for this id, and not run out (else the app registers again).
  let kind = "legacy";
  if (tok !== undefined) {
    const v = await checkToken(env, id, tok, nowSec);
    if (v !== "ok") return json(401, { error: v === "expired" ? "token_expired" : "bad_token" });
    kind = "tok";
  }
  const quota = await quotaOr(env, request, id, nowSec, kind);
  if (quota === null) return json(503, { error: "limits_unavailable" });
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

  if (kind !== "tok") return json(200, { urls, username, credential, ttl: TTL });
  // A login renews the token: an install that calls now and then never has to register again.
  const exp = nowSec + TOKEN_TTL;
  return json(200, { urls, username, credential, ttl: TTL, tok: await makeToken(env, id, exp), exp });
}

export async function handle(request, env, nowSec = Math.floor(Date.now() / 1000)) {
  const { pathname } = new URL(request.url);
  if (pathname === "/health") {
    return request.method === "GET" ? json(200, { ok: true }) : json(405, { error: "method" });
  }
  if (pathname === "/turn") {
    return request.method === "POST" ? handleTurn(request, env, nowSec) : json(405, { error: "method" });
  }
  if (pathname === "/register") {
    return request.method === "POST" ? handleRegister(request, env, nowSec) : json(405, { error: "method" });
  }
  return json(404, { error: "not_found" });
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
