// ---- Login lifetime (relay.md "Renewal").
// A request with "rn": 1 comes from an app that renews its relay before the login ends (round 17 builds): it gets a
// 10 minute login and fetches the next one about 2 minutes before this one ends, so a long call goes on. Apps without
// "rn" (earlier builds) can't renew, so they keep the 30 minute login they were built for, and pay 3 times as much of
// every budget below for it.
export const TTL_RENEW = 600;
export const TTL_OLD = 1800;
const SKEW = 300;

// ---- What a login costs (relay.md "Limits").
// Every budget is counted in minutes of TURN login handed out (10 for a renewing login, 30 for an old one), not in
// logins: what a login can cost the TURN account grows with how long it lasts. The Worker can't see the bytes relayed;
// these minutes are the most it can bound. Round 18 (relay.md "On demand"): apps from 1.41 ask for a login only when a
// call needs the relay and renew it only while the relay carries the call, so these minutes count time spent relaying,
// not calls on Wi-Fi, Bluetooth or a direct path.
// Logins with an install token, per UTC day, keyed by the install the token was signed for:
export const ID_MINUTES = 900; //    per install: 15 hours of relaying (90 renewing logins, or 30 old ones)
// Address groups (IPv4 /24, IPv6 /48) never limit an established install with a token: a carrier's shared addresses
// (CGNAT), a school or an office can hold hundreds of households. Only installs registered less than YOUNG_S ago
// share one budget per address group, so fresh tokens farmed on one network can't take more than this:
export const YOUNG_IP_MINUTES = 1440; // per address group, for young installs only
// Logins without a token (apps before 1.36, or anyone with only APP_SECRET), per UTC day, much stricter, and in a pool
// of their own, so using it up never touches the logins of installs with a token:
export const LEGACY_ID_MINUTES = 60; //   per install: 2 logins
export const LEGACY_IP_MINUTES = 120; //  per address group: 4 logins
export const LEGACY_POOL_MINUTES = 900; // for everyone: 30 logins
// The owner can end that pool: set the Worker variable LEGACY_UNTIL to a Unix time (seconds); from then on a login
// without a token is refused with "update_needed". Not set: never.
// For everyone, per UTC day: the Worker variable DAILY_MINUTES (the owner's TURN budget), else this. Sized to the free
// TURN allowance (1,000 GB a month): a relayed sound call carries about 40 kbps each way, and an allocation is billed
// both for what it sends on to the peer's allocation and for what it hands to its own device, so about 80 kbps,
// 0.6 MB a minute. 50,000 minutes x 0.6 MB = 30 GB a day, 900 to 930 GB a month (relay.md "Limits").
export const DAILY_MINUTES = 50000;
// No hour may take more than a quarter of the day (12,500 minutes: about 208 allocations relaying at once), so a burst
// can't use up the day in minutes.
export const HOUR_SHARE = 4;
// Past this share of the day's or the hour's budget the relay is "busy": logins without a token, and installs
// registered less than YOUNG_S ago, are refused until the hour (or day) turns; established installs go on until the
// budget is used up. A flood of fresh installs can then never take the relay away from the households already using it.
export const SOFT_SHARE = 0.6;
export const YOUNG_S = 2 * 86400;
// New registrations: per address group and for everyone. A full day refuses new registrations, never logins (an install
// that can't register logs in without a token). An install registers once and its token is renewed by every login.
export const REG_PER_IP_DAILY = 20;
export const REG_DAILY = 2000;
// Rows written a day with every budget used up: 5000 renewing logins x at most 4 rows + 30 old logins x 5 + 2000
// registrations x 2 = about 24,200, and as many deleted the next day: about 48,400, under the free plan's 100,000.

// ---- Cheap checks in front of the counts (relay.md "Junk requests").
// Per Worker instance (isolate, in memory only, never stored): at most this many POSTs a minute from one address group
// reach the signature check and the counts. Each Cloudflare location runs its own instances, so this is a speed bump
// that costs nothing, not a hard cap; the counts in the Durable Object are the hard cap. Round 18: 60, so a carrier's
// shared addresses or a school (many households behind one group, each asking only when a call needs the relay) aren't
// slowed down.
export const ISOLATE_RATE = 60;
// A refusal from the counts is remembered in memory until it ends, so the same install, address group or busy relay
// is answered without asking the Durable Object again.
const MEMO_MAX = 10000;

// Leading zero bits of the registration hash (about 260,000 SHA-256 tries on average: about a tenth of a second on a
// desktop, well under a second on a phone). A speed bump for mass registration, not a wall: the per-address and daily
// registration caps are what hold.
export const POW_BITS = 18;
// A token is good for this long after it was issued (each login with it issues a new one).
export const TOKEN_TTL = 7 * 86400;
const MAX_BODY = 1024;
const HEX64 = /^[0-9a-f]{64}$/;
// "<exp>.<mac>" (1.36 to 1.4 Workers) or "<exp>.<since>.<mac>" (since: when the install first registered).
const TOKEN = /^(\d{1,12})(?:\.(\d{1,12}))?\.([0-9a-f]{32})$/;

const enc = new TextEncoder();

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

// Imported HMAC keys are kept for the life of the instance: importing costs more than signing.
const keyCache = new Map();
function hmacKey(secret) {
  let k = keyCache.get(secret);
  if (!k) {
    if (keyCache.size > 8) keyCache.clear();
    k = crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
    keyCache.set(secret, k);
  }
  return k;
}

async function verifySig(secret, id, t, sig) {
  return crypto.subtle.verify("HMAC", await hmacKey(secret), hexToBytes(sig), enc.encode(id + "." + t));
}

async function hmacHex(secret, text) {
  const mac = await crypto.subtle.sign("HMAC", await hmacKey(secret), enc.encode(text));
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// The registration's proof of work: SHA-256 of "huginn-relay pow v1:" + id + ":" + t + ":" + pow (pow in decimal)
// starts with POW_BITS zero bits. Binding t means work can't be stored up for later than the 300 s skew allows.
export async function powOk(id, t, pow, bits = POW_BITS) {
  if (!Number.isSafeInteger(pow) || pow < 0) return false;
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode("huginn-relay pow v1:" + id + ":" + t + ":" + pow)));
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

// A token carries when its install first registered (since), signed with it, so an install can't claim to be older.
// since undefined: the 1.36 form without it (only checked, never issued any more).
export async function makeToken(env, id, exp, since) {
  if (since === undefined) return exp + "." + (await hmacHex(tokenKey(env), "tok:" + id + ":" + exp)).slice(0, 32);
  return exp + "." + since + "." + (await hmacHex(tokenKey(env), "tok2:" + id + ":" + exp + ":" + since)).slice(0, 32);
}

// "ok", "bad" (not this Worker's, or not this id's) or "expired".
export async function checkToken(env, id, tok, nowSec) {
  return (await readToken(env, id, tok, nowSec)).v;
}

// {v, since}. A token in the older form counts as issued when it was (its exp less TOKEN_TTL), the most that is known.
export async function readToken(env, id, tok, nowSec) {
  const m = typeof tok === "string" ? tok.match(TOKEN) : null;
  if (!m) return { v: "bad" };
  const exp = Number(m[1]);
  const since = m[2] === undefined ? undefined : Number(m[2]);
  if ((await makeToken(env, id, exp, since)) !== tok) return { v: "bad" };
  if (exp <= nowSec || exp > nowSec + TOKEN_TTL + SKEW) return { v: "expired" };
  return { v: "ok", since: since === undefined ? exp - TOKEN_TTL : since };
}

// A body over MAX_BODY bytes is refused: by its Content-Length before anything is read, and otherwise
// (no length given) as soon as the bytes read pass it. Returns TOO_LARGE, null (not a JSON object) or the object.
const TOO_LARGE = Symbol("too_large");

function lengthTooLarge(request) {
  const len = request.headers.get("content-length");
  return len !== null && !(/^\d{1,7}$/.test(len.trim()) && Number(len.trim()) <= MAX_BODY);
}

async function readBody(request) {
  if (lengthTooLarge(request)) return TOO_LARGE;
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
    return body && typeof body === "object" && !Array.isArray(body) ? body : null;
  } catch {
    return null;
  }
}

// The address is never stored or logged. The count is kept under an HMAC of the address group and the day,
// keyed with a secret only the Worker has (TURN_KEY_API_TOKEN), so the stored key can't be reversed by trying
// every address. IPv4 addresses count by their /24 and IPv6 by their /48 (the most a home or a phone line is usually
// given), so one customer can't dodge the cap by moving around their own addresses, and renting addresses one by one
// gains little.
export function addressGroup(raw) {
  const ip = String(raw || "unknown").trim().toLowerCase().split("%")[0];
  if (!ip.includes(":")) {
    const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
    if (!m || m.slice(1).some((x) => Number(x) > 255)) return ip;
    return m.slice(1, 4).map(Number).join(".") + ".0/24";
  }
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
    return [n[6] >> 8, n[6] & 255, n[7] >> 8].join(".") + ".0/24";
  }
  return n.slice(0, 3).map((x) => x.toString(16)).join(":") + "::/48";
}

async function ipKey(secret, ip, day) {
  const mac = await crypto.subtle.sign("HMAC", await hmacKey(secret), enc.encode("ip:" + day + ":" + addressGroup(ip)));
  return [...new Uint8Array(mac)].slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// The day's budget for everyone: DAILY_MINUTES (a Worker variable) when it is a whole number from 60 up, else the default.
export function dailyMinutes(env) {
  const n = Number(env && env.DAILY_MINUTES);
  return Number.isSafeInteger(n) && n >= 60 ? n : DAILY_MINUTES;
}

// One SQLite-backed Durable Object keeps every daily count. All checking and counting for one request happens in one
// synchronous step inside it, so two requests at the same moment can't both slip under a cap.
// What each kind of request counts against: [row key, cap, the error's name]. Logins add their cost in minutes to each
// row; registrations add 1. Rows: "d" the day, "h<HH>" this hour, "u:" the install; for a token login of a young
// install also "y:" its address group; for logins without a token also "g" their pool and "l:" their address group.
// Round 18: a token login is keyed by its install first, and an established install is never counted by address.
export function plan(kind, id, ip, hour, daily, young = false) {
  if (kind === "reg") return [["rg", REG_DAILY, "reg"], ["r:" + ip, REG_PER_IP_DAILY, "ip_reg"]];
  const hourly = Math.ceil(daily / HOUR_SHARE);
  const rows = [["d", daily, "global"], ["h" + hour, hourly, "global"]];
  if (kind === "tok") return [...rows, ...(young ? [["y:" + ip, YOUNG_IP_MINUTES, "ip"]] : []), ["u:" + id, ID_MINUTES, "id"]];
  return [...rows, ["g", LEGACY_POOL_MINUTES, "global"], ["l:" + ip, LEGACY_IP_MINUTES, "ip"], ["u:" + id, LEGACY_ID_MINUTES, "id"]];
}
const READ_KEYS = 6;
export const SQL = {
  schema:
    "CREATE TABLE IF NOT EXISTS counts (day TEXT NOT NULL, k TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, k)) WITHOUT ROWID",
  // The month's relayed media in MB, as apps report it: one row a month, the last two kept.
  meterSchema: "CREATE TABLE IF NOT EXISTS meter (month TEXT PRIMARY KEY, mb INTEGER NOT NULL) WITHOUT ROWID",
  meterAdd: "INSERT INTO meter (month, mb) VALUES (?, ?) ON CONFLICT (month) DO UPDATE SET mb = mb + excluded.mb",
  meterRead: "SELECT mb FROM meter WHERE month = ?",
  meterWipe: "DELETE FROM meter WHERE month < ?",
  wipe: "DELETE FROM counts WHERE day < ?",
  read: "SELECT k, n FROM counts WHERE day = ? AND k IN (?, ?, ?, ?, ?, ?)",
  bump: "INSERT INTO counts (day, k, n) VALUES (?, ?, ?) ON CONFLICT (day, k) DO UPDATE SET n = n + excluded.n",
};

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-\d{2}$/;
// One report (relay.md "Relayed media"): apps report every 200 MB or so, so this is well over a real one.
export const METER_MAX_MB = 5000;
export const METER_DAY_MB = 100000;

export class RelayLimits {
  constructor(ctx) {
    this.sql = ctx.storage.sql;
    this.sql.exec(SQL.schema);
    this.sql.exec(SQL.meterSchema);
    this.wipedBefore = null;
  }

  // q: {day, hour (0 to 23), sec (seconds into the day), id, ip (address key), kind ("legacy", "tok" or "reg"),
  // cost (minutes, logins only), young (a token install registered less than YOUNG_S ago), daily}.
  // Returns {result: "ok" or the refusal's name, retry: seconds until it may work}.
  take(q) {
    const { day, id, ip } = q;
    const kind = q.kind || "legacy";
    const hour = String(q.hour ?? 0).padStart(2, "0");
    const sec = q.sec ?? 0;
    const daily = q.daily || DAILY_MINUTES;
    const cost = kind === "reg" ? 1 : q.cost || TTL_OLD / 60;
    if (this.wipedBefore === null || day > this.wipedBefore) {
      this.sql.exec(SQL.wipe, day);
      this.wipedBefore = day;
    }
    const rows = plan(kind, id, ip, hour, daily, q.young === true);
    const keys = rows.map((r) => r[0]);
    while (keys.length < READ_KEYS) keys.push(keys[0]);
    const have = new Map(this.sql.exec(SQL.read, day, ...keys).toArray().map((r) => [r.k, Number(r.n)]));
    const used = (k) => have.get(k) || 0;
    const toHour = 3600 - (sec % 3600);
    const toDay = 86400 - sec;
    if (kind !== "reg") {
      const hourly = Math.ceil(daily / HOUR_SHARE);
      // Used up for the day, or for this hour: nobody gets a login until it turns.
      if (used("d") + cost > daily) return { result: "global", retry: toDay };
      if (used("h" + hour) + cost > hourly) return { result: "global", retry: toHour };
      // Busy: only installs that registered before it got busy go on.
      const dayBusy = used("d") >= daily * SOFT_SHARE;
      const hourBusy = used("h" + hour) >= hourly * SOFT_SHARE;
      if ((dayBusy || hourBusy) && (kind === "legacy" || q.young)) return { result: "busy", retry: dayBusy ? toDay : toHour };
    }
    for (const [k, cap, err] of rows) {
      if (k === "d" || k[0] === "h") continue;
      if (used(k) + cost > cap) return { result: err, retry: toDay };
    }
    for (const [k] of rows) this.sql.exec(SQL.bump, day, k, cost);
    return { result: "ok", retry: 0 };
  }

  async fetch(request) {
    let body;
    try {
      body = JSON.parse(await request.text());
    } catch {
      return json(400, { error: "bad_request" });
    }
    const q = body && typeof body === "object" ? body : {};
    if (q.meter === true) {
      if (typeof q.month !== "string" || !MONTH.test(q.month) || !Number.isInteger(q.mb) || q.mb < 0 || q.mb > METER_MAX_MB
          || typeof q.day !== "string" || !DAY.test(q.day) || typeof q.id !== "string" || !HEX64.test(q.id)) {
        return json(400, { error: "bad_request" });
      }
      if (q.mb > 0) {
        // One install can't say more than METER_DAY_MB a day, so a stolen token can't end the month for everyone.
        const k = "m" + q.id;
        const row = this.sql.exec(SQL.read, q.day, k, k, k, k, k, k).toArray()[0];
        const allowed = Math.min(q.mb, METER_DAY_MB - (row ? Number(row.n) : 0));
        if (allowed > 0) {
          this.sql.exec(SQL.bump, q.day, k, allowed);
          this.sql.exec(SQL.meterWipe, q.month);
          this.sql.exec(SQL.meterAdd, q.month, allowed);
        }
      }
      const row = this.sql.exec(SQL.meterRead, q.month).toArray()[0];
      return json(200, { mb: row ? Number(row.mb) : 0 });
    }
    const kind = q.kind !== undefined ? q.kind : "legacy";
    if (typeof q.day !== "string" || !DAY.test(q.day) || typeof q.id !== "string" || !HEX64.test(q.id)
        || typeof q.ip !== "string" || !/^[0-9a-f]{32}$/.test(q.ip) || !["legacy", "tok", "reg"].includes(kind)
        || (q.hour !== undefined && !(Number.isInteger(q.hour) && q.hour >= 0 && q.hour < 24))
        || (q.sec !== undefined && !(Number.isInteger(q.sec) && q.sec >= 0 && q.sec < 86400))
        || (q.cost !== undefined && !(Number.isInteger(q.cost) && q.cost >= 1 && q.cost <= 60))
        || (q.daily !== undefined && !(Number.isSafeInteger(q.daily) && q.daily >= 60))) {
      return json(400, { error: "bad_request" });
    }
    return json(200, this.take({ ...q, kind, young: q.young === true }));
  }
}

// ---- In-memory, per instance (relay.md "Junk requests"). Never stored, gone when the instance ends.

// Address group -> [minute, count].
const rate = new Map();
// "<kind>|<what>|<subject>" -> [until (s), error, retry from then]: refusals the counts gave, until they end.
const memo = new Map();

export function resetMemory() {
  rate.clear();
  memo.clear();
}

// false when this address group already sent its share this minute (to this instance).
function rateOk(env, ip, nowSec) {
  const cap = Number(env.ISOLATE_RATE) > 0 ? Number(env.ISOLATE_RATE) : ISOLATE_RATE;
  const g = addressGroup(ip);
  const minute = Math.floor(nowSec / 60);
  const e = rate.get(g);
  if (!e || e[0] !== minute) {
    if (rate.size >= MEMO_MAX) rate.clear();
    rate.set(g, [minute, 1]);
    return true;
  }
  return ++e[1] <= cap;
}

function memoGet(keys, nowSec) {
  for (const k of keys) {
    const e = k && memo.get(k);
    if (e && e[0] > nowSec) return { result: e[1], retry: e[0] - nowSec };
  }
  return null;
}

function memoPut(key, nowSec, result, retry) {
  if (memo.size >= MEMO_MAX) memo.clear();
  memo.set(key, [nowSec + retry, result]);
}

// The memo keys a request is answered from, and the one a refusal is kept under. Install and address refusals are
// kept per kind and cost, since a cheaper login may still fit where a dearer one didn't.
function memoKeys(kind, id, ipk, young, cost) {
  // A young install's address group refusal is its own: it never applies to an established install there.
  const kc = kind + (kind === "tok" && young ? "y" : "") + cost;
  return {
    id: kc + "|id|" + id,
    ip: kc + "|ip|" + ipk,
    global: (kind === "reg" ? "reg" : kind === "legacy" ? "legacy" : "all") + "|global|",
    reg: "reg|reg|",
    ip_reg: "reg|ip_reg|" + ipk,
    busy: "busy",
    // Asked, in order: which refusals already known apply to this request.
    ask: kind === "reg"
      ? ["reg|reg|", "reg|ip_reg|" + ipk]
      : ["all|global|", kind === "legacy" ? "legacy|global|" : null, kind === "legacy" || young ? "busy" : null,
        kc + "|ip|" + ipk, kc + "|id|" + id],
  };
}

async function takeQuota(env, id, ip, nowSec, kind, cost, young) {
  const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
  const ipk = await ipKey(env.TURN_KEY_API_TOKEN, ip, day);
  const mk = memoKeys(kind, id, ipk, young, cost);
  const known = memoGet(mk.ask, nowSec);
  if (known) return known;
  const stub = env.LIMITS.get(env.LIMITS.idFromName("limits"));
  const sec = nowSec % 86400;
  const res = await stub.fetch("https://limits/take", {
    method: "POST",
    body: JSON.stringify({ day, hour: Math.floor(sec / 3600), sec, id, ip: ipk, kind, cost: cost || undefined, young, daily: dailyMinutes(env) }),
  });
  if (res.status !== 200) throw new Error("limits");
  const { result, retry } = await res.json();
  if (!["ok", "global", "busy", "ip", "id", "reg", "ip_reg"].includes(result) || !Number.isInteger(retry) || retry < 0) {
    throw new Error("limits");
  }
  // A legacy "global" may be its own pool only; a "global" for a token login is everyone's.
  if (result !== "ok") memoPut(result === "global" && kind === "tok" ? "all|global|" : mk[result], nowSec, result, retry);
  return { result, retry };
}

// What /turn and /register both check first: the body, the id, the time and the APP_SECRET signature.
// Returns the body, or a Response to send back. A stale time says the Worker's time ("now", whole seconds) so the
// app can correct its clock for one more try (the signature still needs APP_SECRET, so nothing new is exposed).
// Ordered cheapest first: nothing is read, hashed or counted for a request that fails an earlier check.
async function checkRequest(request, env, nowSec) {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN || !env.APP_SECRET) {
    return json(503, { error: "not_configured" });
  }
  if (lengthTooLarge(request)) return json(413, { error: "too_large" });
  if (!rateOk(env, request.headers.get("CF-Connecting-IP"), nowSec)) return json(429, { error: "slow_down", retry: 60 });
  // The Workers rate limiting binding, when the owner bound one (wrangler.toml): per Cloudflare location, free to ask.
  if (env.RATE && typeof env.RATE.limit === "function") {
    try {
      const { success } = await env.RATE.limit({ key: addressGroup(request.headers.get("CF-Connecting-IP")) });
      if (!success) return json(429, { error: "slow_down", retry: 60 });
    } catch {
      // A failing limiter costs only this speed bump; the counts still hold.
    }
  }
  const body = await readBody(request);
  if (body === TOO_LARGE) return json(413, { error: "too_large" });
  if (!body) return json(400, { error: "bad_request" });
  const { id, t, sig, rn } = body;
  if (typeof id !== "string" || !HEX64.test(id)) return json(400, { error: "bad_request" });
  if (typeof sig !== "string" || !HEX64.test(sig)) return json(400, { error: "bad_request" });
  if (!Number.isInteger(t)) return json(400, { error: "bad_request" });
  if (rn !== undefined && rn !== 1 && rn !== true) return json(400, { error: "bad_request" });
  if (Math.abs(nowSec - t) > SKEW) return json(401, { error: "stale", now: nowSec });
  if (!(await verifySig(env.APP_SECRET, id, t, sig))) return json(401, { error: "bad_sig" });
  // Without the limits object the caps can't be kept, so nothing is handed out:
  // the caps are the only thing guarding the TURN account.
  if (!env.LIMITS) return json(503, { error: "limits_unavailable" });
  return body;
}

async function quotaOr(env, request, id, nowSec, kind, cost = 0, young = false) {
  try {
    return await takeQuota(env, id, request.headers.get("CF-Connecting-IP") || "unknown", nowSec, kind, cost, young);
  } catch {
    return null;
  }
}

// A refusal: 429 with the error and how long until it may work ("retry", seconds). Apps that send "rn" wait that long;
// older ones wait until the next UTC day, as before.
function refused(q) {
  return json(429, { error: q.result === "busy" ? "busy" : q.result + "_limit", retry: q.retry });
}

async function handleRegister(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, t, pow } = body;
  if (!Number.isSafeInteger(pow) || pow < 0) return json(400, { error: "bad_request" });
  if (!(await powOk(id, t, pow))) return json(401, { error: "bad_pow" });
  const quota = await quotaOr(env, request, id, nowSec, "reg");
  if (quota === null) return json(503, { error: "limits_unavailable" });
  if (quota.result !== "ok") return refused(quota);
  const exp = nowSec + TOKEN_TTL;
  return json(200, { tok: await makeToken(env, id, exp, nowSec), exp });
}

// Logins without a token have ended (LEGACY_UNTIL set and passed).
export function legacyOver(env, nowSec) {
  const until = Number(env.LEGACY_UNTIL);
  return env.LEGACY_UNTIL !== undefined && env.LEGACY_UNTIL !== "" && Number.isFinite(until) && nowSec >= until;
}

// The month's relayed media (relay.md "Relayed media"): an install with a token adds what it moved ("mb", 0 only asks), and
// hears the total, so every app can fall back to the 720p copy before the free month is used. Never a login, never a count
// per install: one number a month.
async function handleMeter(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, tok, mb } = body;
  if (tok === undefined || !Number.isInteger(mb) || mb < 0 || mb > METER_MAX_MB) return json(400, { error: "bad_request" });
  const r = await readToken(env, id, tok, nowSec);
  if (r.v !== "ok") return json(401, { error: r.v === "expired" ? "token_expired" : "bad_token" });
  try {
    const month = new Date(nowSec * 1000).toISOString().slice(0, 7);
    const res = await env.LIMITS.get(env.LIMITS.idFromName("limits")).fetch("https://limits/meter", {
      method: "POST", body: JSON.stringify({ meter: true, month, day: month + new Date(nowSec * 1000).toISOString().slice(7, 10), id, mb }),
    });
    if (res.status !== 200) return json(503, { error: "limits_unavailable" });
    return json(200, await res.json());
  } catch {
    return json(503, { error: "limits_unavailable" });
  }
}

async function handleTurn(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, tok } = body;
  const ttl = body.rn === undefined ? TTL_OLD : TTL_RENEW;
  // With a token: it must be this Worker's, for this id, and not run out (else the app registers again).
  let kind = "legacy";
  let since = nowSec;
  if (tok === undefined && legacyOver(env, nowSec)) return json(403, { error: "update_needed" });
  if (tok !== undefined) {
    const r = await readToken(env, id, tok, nowSec);
    if (r.v !== "ok") return json(401, { error: r.v === "expired" ? "token_expired" : "bad_token" });
    kind = "tok";
    since = r.since;
  }
  const quota = await quotaOr(env, request, id, nowSec, kind, ttl / 60, kind === "tok" && nowSec - since < YOUNG_S);
  if (quota === null) return json(503, { error: "limits_unavailable" });
  if (quota.result !== "ok") return refused(quota);

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
        body: JSON.stringify({ ttl }),
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

  if (kind !== "tok") return json(200, { urls, username, credential, ttl });
  // A login renews the token (keeping when the install registered): an install that calls now and then never has to
  // register again.
  const exp = nowSec + TOKEN_TTL;
  return json(200, { urls, username, credential, ttl, tok: await makeToken(env, id, exp, since), exp });
}

const NOT_FOUND = JSON.stringify({ error: "not_found" });
const METHOD = JSON.stringify({ error: "method" });

// Paths and methods are checked before anything else: a wrong one costs one Worker request and nothing more (no body
// read, no hashing, no Durable Object request).
export async function handle(request, env, nowSec = Math.floor(Date.now() / 1000)) {
  const { pathname } = new URL(request.url);
  const post = request.method === "POST";
  if (pathname === "/turn" || pathname === "/register" || pathname === "/meter") {
    if (!post) return new Response(METHOD, { status: 405, headers: { "content-type": "application/json", allow: "POST" } });
    if (pathname === "/meter") return handleMeter(request, env, nowSec);
    return pathname === "/turn" ? handleTurn(request, env, nowSec) : handleRegister(request, env, nowSec);
  }
  if (pathname === "/health") {
    if (request.method !== "GET") return new Response(METHOD, { status: 405, headers: { "content-type": "application/json", allow: "GET" } });
    // Cacheable for a minute by anything in between; nothing secret in it.
    return new Response('{"ok":true}', { status: 200, headers: { "content-type": "application/json", "cache-control": "public, max-age=60" } });
  }
  return new Response(NOT_FOUND, { status: 404, headers: { "content-type": "application/json", "cache-control": "public, max-age=3600" } });
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
