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
export const ID_MINUTES = 600; //    per install: 10 hours of relaying (60 renewing logins, or 20 old ones)
// Per origin: the address group an install registered from (signed into its token, so it stays with the install
// wherever it logs in from). A household registers its few devices from home or its own phone lines; tokens farmed on
// one network all carry that network's origin. 2400 minutes is 4 devices relaying 10 hours each, every day.
// Why these numbers: with the day's 50,000 minutes, 600 per install means 84 installs to use up the day (at 900 it was
// 56), and 2400 per origin means at least 21 different address groups, each registering installs and waiting 2 days
// (registrations are 20 a day per group). A family's devices relay only when no Wi-Fi, Bluetooth or direct path works,
// so 10 hours a device (five two-hour calls) is a whole day of relayed calls.
export const ORIGIN_MINUTES = 2400;
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
// Rows written a day with every budget used up: 5000 renewing logins x at most 5 rows + 30 old logins x 5 + 2000
// registrations x 2 + 1000 counted meter reports x 6 = about 35,150, and as many deleted later: about 70,300, under the
// free plan's 100,000.

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
const HEX32 = /^[0-9a-f]{32}$/;
// "<exp>.<mac>" (1.36 to 1.4 Workers), "<exp>.<since>.<mac>" (since: when the install first registered) or
// "<exp>.<since>.<origin>.<mac>" (origin: a keyed hash of the address group it registered from, 16 hex).
const TOKEN = /^(\d{1,12})(?:\.(\d{1,12}))?(?:\.([0-9a-f]{16}))?\.([0-9a-f]{32})$/;
// An expired token sent with /register keeps its registration time and origin for this long after it ran out.
export const KEEP_S = 30 * 86400;

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

// A token carries when its install first registered (since) and its origin, signed with them, so an install can't claim
// to be older or from somewhere else. since undefined: the 1.36 form; origin undefined: the form before origins (both
// only checked, never issued any more).
export async function makeToken(env, id, exp, since, origin) {
  if (since === undefined) return exp + "." + (await hmacHex(tokenKey(env), "tok:" + id + ":" + exp)).slice(0, 32);
  if (origin === undefined) return exp + "." + since + "." + (await hmacHex(tokenKey(env), "tok2:" + id + ":" + exp + ":" + since)).slice(0, 32);
  return exp + "." + since + "." + origin + "." + (await hmacHex(tokenKey(env), "tok3:" + id + ":" + exp + ":" + since + ":" + origin)).slice(0, 32);
}

// The origin an install's token carries: a keyed hash of the address group it registered from (or, for a token from
// before origins, first logged in from). Never the address itself; the key is a Worker secret.
export async function originOf(env, ip) {
  return (await hmacHex(tokenKey(env), "origin:" + addressGroup(ip))).slice(0, 16);
}

// "ok", "bad" (not this Worker's, or not this id's) or "expired".
export async function checkToken(env, id, tok, nowSec) {
  return (await readToken(env, id, tok, nowSec)).v;
}

// {v, exp, since, origin}. A token in the oldest form counts as issued when it was (its exp less TOKEN_TTL), the most
// that is known; one without an origin has origin undefined.
export async function readToken(env, id, tok, nowSec) {
  const m = typeof tok === "string" ? tok.match(TOKEN) : null;
  if (!m) return { v: "bad" };
  const exp = Number(m[1]);
  const since = m[2] === undefined ? undefined : Number(m[2]);
  const origin = m[3];
  if (origin !== undefined && since === undefined) return { v: "bad" };
  if ((await makeToken(env, id, exp, since, origin)) !== tok) return { v: "bad" };
  const r = { exp, since: since === undefined ? exp - TOKEN_TTL : since, origin };
  if (exp <= nowSec || exp > nowSec + TOKEN_TTL + SKEW) return { v: "expired", ...r };
  return { v: "ok", ...r };
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
  return (await hmacHex(secret, "ip:" + day + ":" + addressGroup(ip))).slice(0, 32);
}

// The day's row key for an origin: keyed and rotated daily like the address keys, so stored rows can't be linked across days.
async function originKey(secret, origin, day) {
  return (await hmacHex(secret, "o:" + day + ":" + origin)).slice(0, 32);
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
// install also "y:" its address group; for a token login "o:" its origin (the address group it registered from); for
// logins without a token also "g" their pool and "l:" their address group.
// Round 18: a token login is keyed by its install first, and an established install is never counted by the address it
// asks from, only by its origin.
export function plan(kind, id, ip, hour, daily, young = false, origin = undefined) {
  if (kind === "reg") return [["rg", REG_DAILY, "reg"], ["r:" + ip, REG_PER_IP_DAILY, "ip_reg"]];
  const hourly = Math.ceil(daily / HOUR_SHARE);
  const rows = [["d", daily, "global"], ["h" + hour, hourly, "global"]];
  if (kind === "tok") {
    return [...rows, ...(young ? [["y:" + ip, YOUNG_IP_MINUTES, "ip"]] : []),
      ...(origin ? [["o:" + origin, ORIGIN_MINUTES, "origin"]] : []), ["u:" + id, ID_MINUTES, "id"]];
  }
  return [...rows, ["g", LEGACY_POOL_MINUTES, "global"], ["l:" + ip, LEGACY_IP_MINUTES, "ip"], ["u:" + id, LEGACY_ID_MINUTES, "id"]];
}
const READ_KEYS = 6; // d, h, y:, o:, u: at most
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
  // meter rows: "<month>" the month's total, "<month>:<id>" one install's share of it.
  bump: "INSERT INTO counts (day, k, n) VALUES (?, ?, ?) ON CONFLICT (day, k) DO UPDATE SET n = n + excluded.n",
};

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-\d{2}$/;
// ---- The month's relayed media (relay.md "Relayed media"). APP_SECRET is in every download, so anyone can make
// installs and report made-up numbers; these bound what fakes can add, and the apps go by their own count first.
// One report: apps report every 200 MB or so, so this is well over a real one.
export const METER_MAX_MB = 5000;
// Only installs registered at least this long ago (YOUNG_S, 2 days) are counted; a younger one is told to wait (429).
// Per install: at most 10 GB a day (a heavy day of full-quality videos) and 50 GB a month.
export const METER_DAY_MB = 10000;
export const METER_MONTH_MB = 50000;
// Per address group (the one reporting), per day: 30 GB, a household's few devices on a heavy day.
export const METER_IP_DAY_MB = 30000;
// Counted reports per install and for everyone, per day, so reports stay inside the free plan's rows (each counted report
// writes 6 rows). Past them: 429 meter_limit until the next UTC day, and the app keeps what it hasn't said yet.
export const METER_REPORTS_ID = 100;
export const METER_REPORTS_DAILY = 1000;
// The 950 GB stop (relay.md "Relayed media"). A /turn login that says it is for files ("f": 1) is refused with
// month_limit once the month's count passes it.
export const METER_STOP_MB = 950000;

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
    this.wipe(day);
    // The month's stop, for logins that say they are for files: refused before anything is counted.
    if (q.files === true && kind !== "reg" && this.monthMb(q.month) >= METER_STOP_MB) return { result: "month", retry: 0 };
    const rows = plan(kind, id, ip, hour, daily, q.young === true, kind === "tok" ? q.o : undefined);
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

  wipe(day) {
    if (this.wipedBefore === null || day > this.wipedBefore) {
      this.sql.exec(SQL.wipe, day);
      this.wipedBefore = day;
    }
  }

  monthMb(key) {
    const row = this.sql.exec(SQL.meterRead, key).toArray()[0];
    return row ? Number(row.mb) : 0;
  }

  // q: {month, day, id, ip (address key), mb}. Returns {mb: the month's total} or {limit: true} (too many reports today).
  meter(q) {
    if (q.mb > 0) {
      this.wipe(q.day);
      const keys = ["m:" + q.id, "mn:" + q.id, "mi:" + q.ip, "mc"];
      const have = new Map(this.sql.exec(SQL.read, q.day, ...keys, keys[0], keys[0]).toArray().map((r) => [r.k, Number(r.n)]));
      const used = (k) => have.get(k) || 0;
      if (used("mc") >= METER_REPORTS_DAILY || used("mn:" + q.id) >= METER_REPORTS_ID) return { limit: true };
      const mine = q.month + ":" + q.id;
      const allowed = Math.min(q.mb, METER_DAY_MB - used("m:" + q.id), METER_IP_DAY_MB - used("mi:" + q.ip),
        METER_MONTH_MB - this.monthMb(mine));
      // Past a cap the excess is dropped and nothing is written.
      if (allowed > 0) {
        this.sql.exec(SQL.bump, q.day, "m:" + q.id, allowed);
        this.sql.exec(SQL.bump, q.day, "mi:" + q.ip, allowed);
        this.sql.exec(SQL.bump, q.day, "mn:" + q.id, 1);
        this.sql.exec(SQL.bump, q.day, "mc", 1);
        this.sql.exec(SQL.meterWipe, q.month);
        this.sql.exec(SQL.meterAdd, q.month, allowed);
        this.sql.exec(SQL.meterAdd, mine, allowed);
      }
    }
    return { mb: this.monthMb(q.month) };
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
          || typeof q.day !== "string" || !DAY.test(q.day) || typeof q.id !== "string" || !HEX64.test(q.id)
          || typeof q.ip !== "string" || !HEX32.test(q.ip)) {
        return json(400, { error: "bad_request" });
      }
      return json(200, this.meter(q));
    }
    const kind = q.kind !== undefined ? q.kind : "legacy";
    if (typeof q.day !== "string" || !DAY.test(q.day) || typeof q.id !== "string" || !HEX64.test(q.id)
        || typeof q.ip !== "string" || !/^[0-9a-f]{32}$/.test(q.ip) || !["legacy", "tok", "reg"].includes(kind)
        || (q.hour !== undefined && !(Number.isInteger(q.hour) && q.hour >= 0 && q.hour < 24))
        || (q.sec !== undefined && !(Number.isInteger(q.sec) && q.sec >= 0 && q.sec < 86400))
        || (q.cost !== undefined && !(Number.isInteger(q.cost) && q.cost >= 1 && q.cost <= 60))
        || (q.daily !== undefined && !(Number.isSafeInteger(q.daily) && q.daily >= 60))
        || (q.o !== undefined && !(typeof q.o === "string" && HEX32.test(q.o)))
        || (q.files !== undefined && !(q.files === true && typeof q.month === "string" && MONTH.test(q.month)))) {
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
function memoKeys(kind, id, ipk, young, cost, ok, month) {
  // A young install's address group refusal is its own: it never applies to an established install there.
  const kc = kind + (kind === "tok" && young ? "y" : "") + cost;
  return {
    id: kc + "|id|" + id,
    ip: kc + "|ip|" + ipk,
    origin: kind + cost + "|o|" + ok,
    month: "month|" + month,
    global: (kind === "reg" ? "reg" : kind === "legacy" ? "legacy" : "all") + "|global|",
    reg: "reg|reg|",
    ip_reg: "reg|ip_reg|" + ipk,
    busy: "busy",
    // Asked, in order: which refusals already known apply to this request.
    ask: kind === "reg"
      ? ["reg|reg|", "reg|ip_reg|" + ipk]
      : [month ? "month|" + month : null, "all|global|", kind === "legacy" ? "legacy|global|" : null,
        kind === "legacy" || young ? "busy" : null, kc + "|ip|" + ipk, ok ? kind + cost + "|o|" + ok : null, kc + "|id|" + id],
  };
}

// Seconds until the next UTC month starts.
function toMonth(nowSec) {
  const d = new Date(nowSec * 1000);
  return Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1) / 1000) - nowSec;
}

async function takeQuota(env, id, ip, nowSec, kind, cost, young, origin, files) {
  const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
  const month = files ? day.slice(0, 7) : undefined;
  const ipk = await ipKey(env.TURN_KEY_API_TOKEN, ip, day);
  const ok = origin ? await originKey(env.TURN_KEY_API_TOKEN, origin, day) : undefined;
  const mk = memoKeys(kind, id, ipk, young, cost, ok, month);
  const known = memoGet(mk.ask, nowSec);
  if (known) return known;
  const stub = env.LIMITS.get(env.LIMITS.idFromName("limits"));
  const sec = nowSec % 86400;
  const res = await stub.fetch("https://limits/take", {
    method: "POST",
    body: JSON.stringify({ day, hour: Math.floor(sec / 3600), sec, id, ip: ipk, kind, cost: cost || undefined, young,
      daily: dailyMinutes(env), o: ok, files: files || undefined, month }),
  });
  if (res.status !== 200) throw new Error("limits");
  let { result, retry } = await res.json();
  if (!["ok", "global", "busy", "ip", "id", "reg", "ip_reg", "origin", "month"].includes(result) || !Number.isInteger(retry) || retry < 0) {
    throw new Error("limits");
  }
  if (result === "month") retry = toMonth(nowSec);
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

async function quotaOr(env, request, id, nowSec, kind, cost = 0, young = false, origin = undefined, files = false) {
  try {
    return await takeQuota(env, id, request.headers.get("CF-Connecting-IP") || "unknown", nowSec, kind, cost, young, origin, files);
  } catch {
    return null;
  }
}

// A refusal: 429 with the error and how long until it may work ("retry", seconds). Apps that send "rn" wait that long;
// older ones wait until the next UTC day, as before.
// An origin's budget is an address budget to the apps: "ip_limit".
function refused(q) {
  const name = q.result === "origin" ? "ip" : q.result;
  return json(429, { error: name === "busy" ? "busy" : name + "_limit", retry: q.retry });
}

async function handleRegister(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, t, pow, tok } = body;
  if (!Number.isSafeInteger(pow) || pow < 0) return json(400, { error: "bad_request" });
  if (!(await powOk(id, t, pow))) return json(401, { error: "bad_pow" });
  const quota = await quotaOr(env, request, id, nowSec, "reg");
  if (quota === null) return json(503, { error: "limits_unavailable" });
  if (quota.result !== "ok") return refused(quota);
  // An install that sends its old token (run out less than KEEP_S ago, or still good) keeps when it first registered
  // and its origin, so a device that went a week without relaying isn't new again.
  let since = nowSec;
  let origin = await originOf(env, request.headers.get("CF-Connecting-IP") || "unknown");
  if (tok !== undefined) {
    const r = await readToken(env, id, tok, nowSec);
    if (r.v !== "bad" && r.exp > nowSec - KEEP_S && r.exp <= nowSec + TOKEN_TTL + SKEW) {
      since = Math.min(r.since, nowSec);
      if (r.origin !== undefined) origin = r.origin;
    }
  }
  const exp = nowSec + TOKEN_TTL;
  return json(200, { tok: await makeToken(env, id, exp, since, origin), exp });
}

// Logins without a token have ended (LEGACY_UNTIL set and passed).
export function legacyOver(env, nowSec) {
  const until = Number(env.LEGACY_UNTIL);
  return env.LEGACY_UNTIL !== undefined && env.LEGACY_UNTIL !== "" && Number.isFinite(until) && nowSec >= until;
}

// The month's relayed media (relay.md "Relayed media"): an install with a token adds what it moved ("mb", 0 only asks), and
// hears the total, so every app can fall back to the 720p copy before the free month is used. Never a login. Counted only
// from installs at least YOUNG_S old, within the METER_ caps above.
async function handleMeter(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, tok, mb } = body;
  if (tok === undefined || !Number.isInteger(mb) || mb < 0 || mb > METER_MAX_MB) return json(400, { error: "bad_request" });
  const r = await readToken(env, id, tok, nowSec);
  if (r.v !== "ok") return json(401, { error: r.v === "expired" ? "token_expired" : "bad_token" });
  // A young install's report waits until it is 2 days old: nothing is asked or written, and the app keeps it.
  if (mb > 0 && nowSec - r.since < YOUNG_S) return json(429, { error: "meter_wait", retry: r.since + YOUNG_S - nowSec });
  try {
    const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
    const ip = await ipKey(env.TURN_KEY_API_TOKEN, request.headers.get("CF-Connecting-IP") || "unknown", day);
    const res = await env.LIMITS.get(env.LIMITS.idFromName("limits")).fetch("https://limits/meter", {
      method: "POST", body: JSON.stringify({ meter: true, month: day.slice(0, 7), day, id, ip, mb }),
    });
    if (res.status !== 200) return json(503, { error: "limits_unavailable" });
    const a = await res.json();
    if (a.limit === true) return json(429, { error: "meter_limit", retry: 86400 - (nowSec % 86400) });
    if (!Number.isInteger(a.mb)) return json(503, { error: "limits_unavailable" });
    return json(200, { mb: a.mb });
  } catch {
    return json(503, { error: "limits_unavailable" });
  }
}

async function handleTurn(request, env, nowSec) {
  const body = await checkRequest(request, env, nowSec);
  if (body instanceof Response) return body;
  const { id, tok, f } = body;
  // "f": 1 says the login is for a relayed file (relay.md "Relayed media"); refused past the month's stop.
  if (f !== undefined && f !== 1 && f !== true) return json(400, { error: "bad_request" });
  const ttl = body.rn === undefined ? TTL_OLD : TTL_RENEW;
  // With a token: it must be this Worker's, for this id, and not run out (else the app registers again).
  let kind = "legacy";
  let since = nowSec;
  let origin;
  if (tok === undefined && legacyOver(env, nowSec)) return json(403, { error: "update_needed" });
  if (tok !== undefined) {
    const r = await readToken(env, id, tok, nowSec);
    if (r.v !== "ok") return json(401, { error: r.v === "expired" ? "token_expired" : "bad_token" });
    kind = "tok";
    since = r.since;
    // A token from before origins takes the address group it first logs in from.
    origin = r.origin !== undefined ? r.origin : await originOf(env, request.headers.get("CF-Connecting-IP") || "unknown");
  }
  const quota = await quotaOr(env, request, id, nowSec, kind, ttl / 60, kind === "tok" && nowSec - since < YOUNG_S, origin, f !== undefined);
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
  return json(200, { urls, username, credential, ttl, tok: await makeToken(env, id, exp, since, origin), exp });
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
