import { createHash, createHmac } from "node:crypto";
import assert from "node:assert/strict";
import worker, {
  handle, addressGroup, RelayLimits, SQL, POW_BITS, TOKEN_TTL, powOk, makeToken, legacyOver, resetMemory, dailyMinutes,
  TTL_RENEW, TTL_OLD, ID_MINUTES, YOUNG_IP_MINUTES, LEGACY_ID_MINUTES, LEGACY_IP_MINUTES, LEGACY_POOL_MINUTES, DAILY_MINUTES,
  HOUR_SHARE, SOFT_SHARE, YOUNG_S, ISOLATE_RATE, REG_PER_IP_DAILY, REG_DAILY, plan,
} from "./worker.js";

const SECRET = "test-app-secret";
const ID = "a".repeat(64);
// 2027-01-15 08:00:00 UTC: 8 hours (28,800 s) into the day, at the start of an hour.
const NOW = 1_800_000_000;
const TO_HOUR = 3600;
const TO_DAY = 86400 - 28800;
const HOURLY = Math.ceil(DAILY_MINUTES / HOUR_SHARE);

const DAY = new Date(NOW * 1000).toISOString().slice(0, 10);

// In-memory stand-in for the Durable Object's SQLite storage: it answers exactly the statements in SQL.
// Rows are kept as "day|key" -> count. `written` counts rows written, as Cloudflare bills them.
class FakeSql {
  constructor() { this.rows = new Map(); this.fail = false; this.written = 0; }
  exec(q, ...b) {
    if (this.fail) throw new Error("storage down");
    const out = [];
    if (q === SQL.schema || q === SQL.meterSchema) {
      // nothing to do
    } else if (q === SQL.meterAdd) {
      this.months = this.months || new Map();
      this.months.set(b[0], (this.months.get(b[0]) || 0) + b[1]);
    } else if (q === SQL.meterRead) {
      const n = this.months && this.months.get(b[0]);
      if (n !== undefined) out.push({ mb: n });
    } else if (q === SQL.meterWipe) {
      for (const m of [...(this.months || new Map()).keys()]) if (m < b[0]) this.months.delete(m);
    } else if (q === SQL.wipe) {
      for (const key of [...this.rows.keys()]) if (key.split("|")[0] < b[0]) { this.rows.delete(key); this.written++; }
    } else if (q === SQL.read) {
      const [day, ...keys] = b;
      for (const k of new Set(keys)) if (this.rows.has(day + "|" + k)) out.push({ k, n: this.rows.get(day + "|" + k) });
    } else if (q === SQL.bump) {
      const key = b[0] + "|" + b[1];
      this.rows.set(key, (this.rows.get(key) || 0) + b[2]);
      this.written++;
    } else {
      throw new Error("unexpected SQL: " + q);
    }
    return { toArray: () => out };
  }
}

// Stand-in for the Durable Object namespace binding: one real RelayLimits object over FakeSql.
class FakeLimits {
  constructor() { this.sql = new FakeSql(); this.obj = null; this.calls = 0; }
  idFromName(name) { return name; }
  get(id) {
    assert.equal(id, "limits");
    return {
      fetch: async (url, init) => {
        this.calls++;
        if (this.sql.fail) throw new Error("object down");
        this.obj = this.obj || new RelayLimits({ storage: { sql: this.sql } });
        return this.obj.fetch(new Request(url, init));
      },
    };
  }
  keys() { return [...this.sql.rows.keys()]; }
}

const upstreamCalls = [];
globalThis.fetch = async (url, init) => {
  upstreamCalls.push({ url, init });
  return new Response(JSON.stringify({
    iceServers: [
      { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] },
      {
        urls: [
          "turn:turn.cloudflare.com:3478?transport=udp",
          "turn:turn.cloudflare.com:3478?transport=tcp",
          "turns:turn.cloudflare.com:5349?transport=tcp",
          "turns:turn.cloudflare.com:443?transport=tcp",
        ],
        username: "user123",
        credential: "cred456",
      },
    ],
  }), { status: 201, headers: { "content-type": "application/json" } });
};

// The per-instance speed bump is set out of the way here; its own test uses the real one.
function env(limits = new FakeLimits(), extra = {}) {
  return { TURN_KEY_ID: "kid", TURN_KEY_API_TOKEN: "tok", APP_SECRET: SECRET, LIMITS: limits, ISOLATE_RATE: "1000000", ...extra };
}

function sign(id, t, secret = SECRET) {
  return createHmac("sha256", secret).update(id + "." + t).digest("hex");
}

function req(body, method = "POST", path = "/turn", ip = "203.0.113.7") {
  return new Request("https://relay.example.workers.dev" + path, {
    method,
    headers: ip ? { "CF-Connecting-IP": ip } : {},
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
  });
}

async function call(e, body, opts = {}) {
  const res = await handle(req(body, opts.method, opts.path, opts.ip === undefined ? "203.0.113.7" : opts.ip), e, opts.now ?? NOW);
  return { status: res.status, data: await res.json() };
}

// A signed /turn body; tok and rn only when given.
function login(id, opts = {}) {
  const t = opts.now ?? NOW;
  const b = { id, t, sig: sign(id, t) };
  if (opts.tok !== undefined) b.tok = opts.tok;
  if (opts.rn !== undefined) b.rn = opts.rn;
  return b;
}

// A token the test Worker would have issued to an install that registered at `since` (default: long ago).
async function tokenFor(e, id, since = NOW - 30 * 86400, exp = NOW + TOKEN_TTL) {
  return makeToken(e, id, exp, since);
}

function hexId(i, pad = "c") { return i.toString(16).padStart(64, pad); }

let passed = 0;
async function test(name, fn) { resetMemory(); await fn(); passed++; console.log("ok  " + name); }

await test("health", async () => {
  const res = await worker.fetch(new Request("https://x/health"), {});
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
});

await test("unknown path is 404, wrong method is 405, and neither reads the body or asks the counts", async () => {
  const lim = new FakeLimits();
  upstreamCalls.length = 0;
  const r404 = req("x".repeat(5000), "POST", "/nope");
  const res = await handle(r404, env(lim), NOW);
  assert.equal(res.status, 404);
  assert.equal(r404.bodyUsed, false);
  assert.equal((await call(env(lim), null, { method: "GET" })).status, 405);
  assert.equal((await call(env(lim), null, { method: "PUT", path: "/register" })).status, 405);
  assert.equal((await call(env(lim), null, { method: "POST", path: "/health" })).status, 405);
  assert.equal(lim.calls, 0);
  assert.equal(upstreamCalls.length, 0);
});

await test("valid request: relay-only credentials; 30 minutes without rn, 10 with it", async () => {
  upstreamCalls.length = 0;
  const r = await call(env(), login(ID));
  assert.equal(r.status, 200);
  assert.equal(r.data.username, "user123");
  assert.equal(r.data.credential, "cred456");
  assert.equal(r.data.ttl, 1800);
  assert.equal(TTL_OLD, 1800);
  assert.equal(r.data.urls.length, 4);
  assert.ok(r.data.urls.every((u) => /^turns?:/.test(u)));
  assert.equal(upstreamCalls.length, 1);
  const c = upstreamCalls[0];
  assert.equal(c.url, "https://rtc.live.cloudflare.com/v1/turn/keys/kid/credentials/generate-ice-servers");
  assert.equal(c.init.headers.Authorization, "Bearer tok");
  assert.deepEqual(JSON.parse(c.init.body), { ttl: 1800 });
  for (const rn of [1, true]) {
    const s = await call(env(), login(ID, { rn }));
    assert.equal(s.status, 200);
    assert.equal(s.data.ttl, 600);
    assert.equal(TTL_RENEW, 600);
    assert.deepEqual(JSON.parse(upstreamCalls.at(-1).init.body), { ttl: 600 });
  }
});

await test("bad signature is 401, and never reaches the counts", async () => {
  const lim = new FakeLimits();
  const r = await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW, "wrong") });
  assert.equal(r.status, 401);
  assert.equal(r.data.error, "bad_sig");
  assert.equal(lim.calls, 0);
});

await test("timestamp outside 300 s is 401, inside is ok", async () => {
  assert.equal((await call(env(), login(ID, { now: NOW - 301 }))).status, 401);
  assert.equal((await call(env(), login(ID, { now: NOW + 301 }))).status, 401);
  assert.equal((await call(env(), login(ID, { now: NOW - 300 }))).status, 200);
});

await test("malformed bodies are 400, before any hashing or counting", async () => {
  const lim = new FakeLimits();
  assert.equal((await call(env(lim), "not json")).status, 400);
  assert.equal((await call(env(lim), "[1,2]")).status, 400);
  assert.equal((await call(env(lim), "null")).status, 400);
  assert.equal((await call(env(lim), { id: "xyz", t: NOW, sig: sign("xyz", NOW) })).status, 400);
  assert.equal((await call(env(lim), { id: ID, t: String(NOW), sig: sign(ID, NOW) })).status, 400);
  assert.equal((await call(env(lim), { id: ID, t: NOW, sig: "zz" })).status, 400);
  for (const rn of [0, 2, "1", null]) assert.equal((await call(env(lim), { ...login(ID), rn })).status, 400);
  assert.equal(lim.calls, 0);
});

await test("bodies over 1 KB are 413", async () => {
  upstreamCalls.length = 0;
  // No Content-Length: refused once the bytes read pass 1 KB.
  assert.equal((await call(env(), "x".repeat(2000))).status, 413);
  const big = JSON.stringify({ ...login(ID), pad: "x".repeat(1100) });
  assert.equal((await call(env(), big)).status, 413);
  // A Content-Length over 1 KB is refused before the body is read at all.
  const r = new Request("https://relay.example.workers.dev/turn", {
    method: "POST",
    headers: { "CF-Connecting-IP": "203.0.113.7", "content-length": "5000" },
    body: JSON.stringify(login(ID)),
  });
  const res = await handle(r, env(), NOW);
  assert.equal(res.status, 413);
  assert.equal(r.bodyUsed, false);
  for (const bad of ["abc", "-1", "1e9"]) {
    const rb = new Request("https://relay.example.workers.dev/turn", {
      method: "POST", headers: { "content-length": bad }, body: "{}",
    });
    assert.equal((await handle(rb, env(), NOW)).status, 413);
  }
  // Exactly 1 KB is still read.
  const ok = JSON.stringify(login(ID));
  const padded = ok.slice(0, -1) + ',"p":"' + "x".repeat(1024 - ok.length - 7) + '"}';
  assert.equal(new TextEncoder().encode(padded).length, 1024);
  const rok = new Request("https://relay.example.workers.dev/turn", {
    method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7", "content-length": "1024" }, body: padded,
  });
  assert.equal((await handle(rok, env(), NOW)).status, 200);
  assert.equal(upstreamCalls.length, 1);
});

// ---- Per install (M5: tied to the install token, stricter without one).

await test("per install with a token: 900 minutes a day (90 renewing logins, or 30 old ones)", async () => {
  assert.equal(ID_MINUTES, 900);
  const e = env();
  const tok = await tokenFor(e, ID);
  for (let i = 0; i < 90; i++) assert.equal((await call(e, login(ID, { tok, rn: 1 }))).status, 200, "login " + i);
  const r = await call(e, login(ID, { tok, rn: 1 }));
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "id_limit");
  assert.equal(r.data.retry, TO_DAY);
  // Another install is fine, and so is this one the next day.
  const other = "b".repeat(64);
  assert.equal((await call(e, login(other, { tok: await tokenFor(e, other), rn: 1 }))).status, 200);
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, login(ID, { tok, rn: 1, now: tomorrow }), { now: tomorrow })).status, 200);
  // Old (30 minute) logins: 30 of them.
  const e2 = env();
  for (let i = 0; i < 30; i++) assert.equal((await call(e2, login(ID, { tok }))).status, 200);
  assert.equal((await call(e2, login(ID, { tok }))).data.error, "id_limit");
});

await test("a family's day fits: 4 devices on one home network, 10 hours each of relayed calls", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  for (let d = 0; d < 4; d++) {
    const id = hexId(d, "5");
    let tok = await tokenFor(e, id);
    // 10 hours of relaying in renewing logins, all from the household's one address.
    for (let i = 0; i < 60; i++) {
      const t = NOW + i * 480;
      const r = await call(e, login(id, { tok, rn: 1, now: t }), { ip: "198.51.100.7", now: t });
      assert.equal(r.status, 200, "device " + d + " login " + i);
      tok = r.data.tok;
    }
  }
  assert.equal(lim.sql.rows.get(DAY + "|u:" + hexId(0, "5")), 600);
});

await test("per install without a token: 60 minutes a day (2 logins)", async () => {
  assert.equal(LEGACY_ID_MINUTES, 60);
  const e = env();
  for (let i = 0; i < 2; i++) assert.equal((await call(e, login(ID), { ip: "198.51.100." + (i + 1) })).status, 200);
  const r = await call(e, login(ID), { ip: "192.0.2.1" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "id_limit");
});

// ---- Per address group (M5: IPv4 /24, IPv6 /48).

await test("a shared network (carrier NAT, a school): installs with a token are counted by install, not by address", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  // 200 established installs behind one address, one old (30 minute) login each: 6000 minutes from one /24.
  for (let i = 0; i < 200; i++) {
    const id = hexId(i);
    assert.equal((await call(e, login(id, { tok: await tokenFor(e, id) }), { ip: "203.0.113.1" })).status, 200, "install " + i);
  }
  assert.ok(!lim.keys().some((k) => k.includes("|y:") || k.includes("|i:")));
});

await test("per address group, young installs only: 1440 minutes a day, across a whole /24", async () => {
  assert.equal(YOUNG_IP_MINUTES, 1440);
  const lim = new FakeLimits();
  const e = env(lim);
  // 48 installs registered an hour ago, each from its own address in 203.0.113.0/24, one old (30 minute) login each.
  for (let i = 0; i < 48; i++) {
    const id = hexId(i);
    assert.equal((await call(e, login(id, { tok: await tokenFor(e, id, NOW - 3600) }), { ip: "203.0.113." + (i + 1) })).status, 200);
  }
  const fresh = "d".repeat(64);
  const ftok = await tokenFor(e, fresh, NOW - 3600);
  const r = await call(e, login(fresh, { tok: ftok, rn: 1 }), { ip: "203.0.113.250" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "ip_limit");
  assert.equal(r.data.retry, TO_DAY);
  // An established install on the same /24 isn't touched by it (nor by the refusal remembered for young ones).
  const est = "e".repeat(64);
  assert.equal((await call(e, login(est, { tok: await tokenFor(e, est), rn: 1 }), { ip: "203.0.113.251" })).status, 200);
  // The next /24 is someone else, and the same one is fine the next day.
  assert.equal((await call(e, login(fresh, { tok: ftok, rn: 1 }), { ip: "203.0.114.1" })).status, 200);
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, login(fresh, { tok: ftok, rn: 1, now: tomorrow }), { ip: "203.0.113.9", now: tomorrow })).status, 200);
});

await test("the rows a login counts against: by install first", async () => {
  const keys = (rows) => rows.map((r) => r[0]);
  assert.deepEqual(keys(plan("tok", "I", "A", "08", 50000)), ["d", "h08", "u:I"]);
  assert.deepEqual(keys(plan("tok", "I", "A", "08", 50000, true)), ["d", "h08", "y:A", "u:I"]);
  assert.deepEqual(keys(plan("legacy", "I", "A", "08", 50000)), ["d", "h08", "g", "l:A", "u:I"]);
  assert.deepEqual(keys(plan("reg", "I", "A", "08", 50000)), ["rg", "r:A"]);
});

await test("per address group without a token: 120 minutes a day (4 logins), and a pool of 900 for everyone", async () => {
  assert.equal(LEGACY_IP_MINUTES, 120);
  assert.equal(LEGACY_POOL_MINUTES, 900);
  const lim = new FakeLimits();
  const e = env(lim);
  for (let i = 0; i < 4; i++) assert.equal((await call(e, login(hexId(i, "e")), { ip: "198.51.100." + i })).status, 200);
  const r = await call(e, login("f".repeat(64)), { ip: "198.51.100.200" });
  assert.equal(r.data.error, "ip_limit");
  // Logins with a token from the same group are not touched by the token-less count.
  const id = "9".repeat(64);
  assert.equal((await call(e, login(id, { tok: await tokenFor(e, id) }), { ip: "198.51.100.201" })).status, 200);
  // The pool: full means no token-less login anywhere, while logins with a token go on.
  const lim2 = new FakeLimits();
  const e2 = env(lim2);
  lim2.sql.rows.set(DAY + "|g", 900);
  const g = await call(e2, login(ID));
  assert.equal(g.status, 429);
  assert.equal(g.data.error, "global_limit");
  assert.equal((await call(e2, login(ID, { tok: await tokenFor(e2, ID) }))).status, 200);
});

await test("addresses are never stored as they are", async () => {
  const lim = new FakeLimits();
  await call(env(lim), login(ID), { ip: "203.0.113.99" });
  await call(env(lim), login("b".repeat(64)), { ip: "2001:db8:aa:bb::99" });
  for (const k of lim.keys()) {
    assert.ok(!k.includes("203.0.113") && !k.includes("2001") && !k.includes("db8"), k);
  }
  assert.equal(lim.keys().filter((k) => k.includes("|l:")).length, 2);
});

await test("address keys are keyed with the Worker secret, not a plain hash, and cover the /24", async () => {
  const ip = "203.0.113.42";
  const lim = new FakeLimits();
  await call(env(lim), login(ID), { ip });
  const stored = lim.keys().find((k) => k.includes("|l:")).split("|l:")[1];
  assert.match(stored, /^[0-9a-f]{32}$/);
  const sha = (x) => createHash("sha256").update(x).digest("hex").slice(0, 32);
  for (const guess of [ip, "ip:" + DAY + ":" + ip, DAY + ":" + ip, "ip:" + DAY + ":203.0.113.0/24"]) assert.notEqual(stored, sha(guess));
  assert.equal(stored, createHmac("sha256", "tok").update("ip:" + DAY + ":203.0.113.0/24").digest("hex").slice(0, 32));
  // Another secret gives another key for the same address.
  const lim2 = new FakeLimits();
  await call(env(lim2, { TURN_KEY_API_TOKEN: "other" }), login(ID), { ip });
  assert.notEqual(lim2.keys().find((k) => k.includes("|l:")).split("|l:")[1], stored);
});

await test("address groups: IPv4 by /24, IPv6 by /48", async () => {
  assert.equal(addressGroup("2001:DB8:0:0:1:2:3:4"), "2001:db8:0::/48");
  assert.equal(addressGroup("2001:db8::1"), "2001:db8:0::/48");
  assert.equal(addressGroup("2001:db8:1:2::"), "2001:db8:1::/48");
  assert.equal(addressGroup("fe80::1%eth0"), "fe80:0:0::/48");
  assert.equal(addressGroup("::ffff:203.0.113.7"), "203.0.113.0/24");
  assert.equal(addressGroup("203.0.113.7"), "203.0.113.0/24");
  assert.equal(addressGroup("203.0.113.255"), addressGroup("203.0.113.1"));
  assert.notEqual(addressGroup("203.0.113.7"), addressGroup("203.0.112.7"));
  assert.equal(addressGroup("2001:db8:1:2::1"), addressGroup("2001:db8:1:ffff::1"));
  assert.notEqual(addressGroup("2001:db8:1:2::1"), addressGroup("2001:db8:2:2::1"));
  assert.equal(addressGroup("999.1.1.1"), "999.1.1.1");
  assert.equal(addressGroup(null), "unknown");
});

await test("one IPv6 /48 shares one address budget for young installs, across its /64s", async () => {
  const e = env();
  for (let i = 0; i < 48; i++) {
    const id = hexId(i, "7");
    const ip = "2001:db8:1:" + (i + 1).toString(16) + "::" + (i * 7 + 1).toString(16);
    assert.equal((await call(e, login(id, { tok: await tokenFor(e, id, NOW - 3600) }), { ip })).status, 200);
  }
  const id = "8".repeat(64);
  const tok = await tokenFor(e, id, NOW - 3600);
  const r = await call(e, login(id, { tok }), { ip: "2001:db8:1:ffff:ffff:ffff:ffff:ffff" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "ip_limit");
  assert.equal((await call(e, login(id, { tok }), { ip: "2001:db8:2::1" })).status, 200);
});

await test("requests at the same moment can't go over a cap", async () => {
  const e = env();
  const tok = await tokenFor(e, ID);
  const same = await Promise.all(Array.from({ length: 120 }, () => call(e, login(ID, { tok, rn: 1 }))));
  assert.equal(same.filter((r) => r.status === 200).length, 90);
  assert.ok(same.filter((r) => r.status === 429).every((r) => r.data.error === "id_limit"));
  const e2 = env();
  const toks = await Promise.all(Array.from({ length: 70 }, (_, i) => tokenFor(e2, hexId(i, "9"), NOW - 3600)));
  const many = await Promise.all(toks.map((tok2, i) => call(e2, login(hexId(i, "9"), { tok: tok2 }), { ip: "198.51.100.20" })));
  assert.equal(many.filter((r) => r.status === 200).length, 48);
});

// ---- For everyone: the day, the hour, and "busy" (M5: a daily cost cap that degrades gracefully).

await test("the day's budget for everyone: 50,000 minutes, or DAILY_MINUTES", async () => {
  assert.equal(dailyMinutes({}), 50000);
  assert.equal(dailyMinutes({ DAILY_MINUTES: "6000" }), 6000);
  for (const bad of ["", "abc", "10", "-5", "1.5"]) assert.equal(dailyMinutes({ DAILY_MINUTES: bad }), 50000);
  // Sized to the free TURN allowance: 0.6 MB a relayed minute (about 80 kbps an allocation), at most 1,000 GB a month.
  assert.ok((DAILY_MINUTES * 0.6 * 31) / 1000 <= 1000);
  const lim = new FakeLimits();
  const e = env(lim);
  lim.sql.rows.set(DAY + "|d", DAILY_MINUTES - 10);
  const tok = await tokenFor(e, ID);
  // Room for one renewing login but not an old one.
  const old = await call(e, login(ID, { tok }));
  assert.equal(old.status, 429);
  assert.equal(old.data.error, "global_limit");
  assert.equal(old.data.retry, TO_DAY);
  resetMemory();
  assert.equal((await call(e, login(ID, { tok, rn: 1 }))).status, 200);
  resetMemory();
  assert.equal((await call(e, login(ID, { tok, rn: 1 }))).data.error, "global_limit");
  // The variable lowers it.
  const lim2 = new FakeLimits();
  lim2.sql.rows.set(DAY + "|d", 6000);
  const r = await call(env(lim2, { DAILY_MINUTES: "6000" }), login(ID, { tok, rn: 1 }));
  assert.equal(r.data.error, "global_limit");
  // And the next day starts over.
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, login(ID, { tok, rn: 1, now: tomorrow }), { now: tomorrow })).status, 200);
});

await test("no hour takes more than a quarter of the day's budget (well over 53 relaying at once)", async () => {
  assert.equal(HOURLY, 12500);
  assert.ok(Math.floor(HOURLY / 60) >= 200);
  const lim = new FakeLimits();
  const e = env(lim);
  const tok = await tokenFor(e, ID);
  lim.sql.rows.set(DAY + "|h08", HOURLY);
  const r = await call(e, login(ID, { tok, rn: 1 }));
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "global_limit");
  assert.equal(r.data.retry, TO_HOUR);
  // The next hour (09:00) is open again.
  const later = NOW + 3600;
  assert.equal((await call(e, login(ID, { tok, rn: 1, now: later }), { now: later })).status, 200);
  assert.equal(lim.sql.rows.get(DAY + "|h09"), 10);
  assert.equal(lim.sql.rows.get(DAY + "|d"), 10);
});

await test("busy (60% of the day or hour): token-less and new installs wait, established installs go on", async () => {
  assert.equal(SOFT_SHARE, 0.6);
  assert.equal(YOUNG_S, 2 * 86400);
  const lim = new FakeLimits();
  const e = env(lim);
  lim.sql.rows.set(DAY + "|h08", Math.ceil(HOURLY * 0.6));
  const legacy = await call(e, login(ID));
  assert.equal(legacy.status, 429);
  assert.equal(legacy.data.error, "busy");
  assert.equal(legacy.data.retry, TO_HOUR);
  const young = "b".repeat(64);
  const ytok = await tokenFor(e, young, NOW - 3600);
  const y = await call(e, login(young, { tok: ytok, rn: 1 }));
  assert.equal(y.data.error, "busy");
  const old = "c".repeat(64);
  assert.equal((await call(e, login(old, { tok: await tokenFor(e, old, NOW - YOUNG_S), rn: 1 }))).status, 200);
  // A token in the 1.36 form counts as registered when it was issued: 3 days ago is established.
  const v1 = "d".repeat(64);
  assert.equal((await call(e, login(v1, { tok: await makeToken(e, v1, NOW - 3 * 86400 + TOKEN_TTL), rn: 1 }))).status, 200);
  // Busy for the day: wait until the day turns.
  resetMemory();
  const lim2 = new FakeLimits();
  lim2.sql.rows.set(DAY + "|d", DAILY_MINUTES * 0.6);
  const d = await call(env(lim2), login(young, { tok: ytok, rn: 1 }));
  assert.equal(d.data.error, "busy");
  assert.equal(d.data.retry, TO_DAY);
});

// ---- Renewal (M5: shorter logins, renewed so long calls go on).

await test("renewal: a two-hour call is 12 renewing logins and 120 minutes, and the token keeps its registration time", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  const reg = await call(e, { ...login(ID), pow: solve(ID, NOW) }, { path: "/register" });
  assert.equal(reg.status, 200);
  let tok = reg.data.tok;
  assert.match(tok, new RegExp("^" + (NOW + TOKEN_TTL) + "\\." + NOW + "\\.[0-9a-f]{32}$"));
  // Each login ends after 600 s; the app renews about 2 minutes before.
  for (let i = 0; i < 12; i++) {
    const t = NOW + i * 480;
    const r = await call(e, login(ID, { tok, rn: 1, now: t }), { now: t });
    assert.equal(r.status, 200);
    assert.equal(r.data.ttl, 600);
    assert.equal(r.data.tok, await makeToken(e, ID, t + TOKEN_TTL, NOW));
    tok = r.data.tok;
  }
  assert.equal(lim.sql.rows.get(DAY + "|u:" + ID), 120);
  // A token can't be moved to another registration time.
  const forged = tok.replace("." + NOW + ".", "." + (NOW - 10 * 86400) + ".");
  assert.equal((await call(e, login(ID, { tok: forged, rn: 1 }))).data.error, "bad_token");
});

// ---- Cheap checks in front of the counts (M6).

await test("per instance: at most 60 POSTs a minute from one address group reach the checks", async () => {
  assert.equal(ISOLATE_RATE, 60);
  const lim = new FakeLimits();
  const e = env(lim);
  delete e.ISOLATE_RATE;
  for (let i = 0; i < 60; i++) assert.equal((await call(e, "junk", { ip: "192.0.2." + i })).status, 400);
  const r = await call(e, login(ID), { ip: "192.0.2.99" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "slow_down");
  assert.equal(r.data.retry, 60);
  assert.equal(lim.calls, 0);
  // Another group, and the same one a minute later, are fine.
  assert.equal((await call(e, login(ID), { ip: "192.0.3.1" })).status, 200);
  assert.equal((await call(e, login(ID, { now: NOW + 60 }), { ip: "192.0.2.1", now: NOW + 60 })).status, 200);
});

await test("the rate limiting binding is used when bound, and a broken one changes nothing", async () => {
  const keys = [];
  const e = env(new FakeLimits(), { RATE: { limit: async ({ key }) => { keys.push(key); return { success: keys.length <= 1 }; } } });
  assert.equal((await call(e, login(ID))).status, 200);
  const r = await call(e, login(ID));
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "slow_down");
  assert.deepEqual(keys, ["203.0.113.0/24", "203.0.113.0/24"]);
  const broken = env(new FakeLimits(), { RATE: { limit: async () => { throw new Error("down"); } } });
  assert.equal((await call(broken, login(ID))).status, 200);
});

await test("a refusal is remembered: the same install, group or busy relay isn't asked about again", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  const tok = await tokenFor(e, ID);
  lim.sql.rows.set(DAY + "|u:" + ID, ID_MINUTES);
  assert.equal((await call(e, login(ID, { tok, rn: 1 }))).data.error, "id_limit");
  const calls = lim.calls;
  for (let i = 0; i < 5; i++) {
    const r = await call(e, login(ID, { tok, rn: 1 }));
    assert.equal(r.data.error, "id_limit");
    assert.ok(r.data.retry > 0 && r.data.retry <= TO_DAY);
  }
  assert.equal(lim.calls, calls);
  // Someone else still asks, and gets a login.
  const other = "b".repeat(64);
  assert.equal((await call(e, login(other, { tok: await tokenFor(e, other), rn: 1 }))).status, 200);
  assert.equal(lim.calls, calls + 1);
  // Busy: remembered for token-less and new installs only.
  const lim2 = new FakeLimits();
  const e2 = env(lim2);
  lim2.sql.rows.set(DAY + "|d", DAILY_MINUTES * 0.6);
  assert.equal((await call(e2, login(ID))).data.error, "busy");
  const c2 = lim2.calls;
  assert.equal((await call(e2, login("c".repeat(64)))).data.error, "busy");
  assert.equal(lim2.calls, c2);
  assert.equal((await call(e2, login(other, { tok: await tokenFor(e2, other), rn: 1 }))).status, 200);
  assert.equal(lim2.calls, c2 + 1);
  // Remembered only until it ends.
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, login(ID, { tok, rn: 1, now: tomorrow }), { now: tomorrow })).status, 200);
});

await test("junk costs no Durable Object request, no TURN login and no row", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  upstreamCalls.length = 0;
  const tok = await tokenFor(e, ID);
  const junk = [
    [{ id: ID, t: NOW, sig: sign(ID, NOW, "x") }, {}],
    ["{", {}],
    [{ id: ID }, {}],
    [login(ID, { now: NOW - 9999 }), {}],
    [login(ID, { tok: "1.2" }), {}],
    [login(ID, { tok: tok.slice(0, -1) + "0" }), {}],
    [login("b".repeat(64), { tok }), {}],
    [{ ...login(ID), pow: 1 }, { path: "/register" }],
    [null, { method: "GET" }],
    [null, { method: "DELETE", path: "/x" }],
  ];
  for (const [b, o] of junk) assert.notEqual((await call(e, b, o)).status, 200);
  assert.equal(lim.calls, 0);
  assert.equal(lim.sql.written, 0);
  assert.equal(upstreamCalls.length, 0);
});

await test("each token login writes 3 rows (4 while young), a token-less one 5, a registration 2; earlier days are wiped", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  const tok = await tokenFor(e, ID);
  await call(e, login(ID, { tok, rn: 1 }));
  assert.equal(lim.sql.written, 3);
  await call(e, login(ID));
  assert.equal(lim.sql.written, 8);
  await call(e, { ...login(ID), pow: solve(ID, NOW) }, { path: "/register" });
  assert.equal(lim.sql.written, 10);
  const young = "b".repeat(64);
  await call(e, login(young, { tok: await tokenFor(e, young, NOW - 3600), rn: 1 }));
  assert.equal(lim.sql.written, 14);
  assert.equal(lim.calls, 4);
  const tomorrow = NOW + 86400;
  await call(e, login(ID, { tok, rn: 1, now: tomorrow }), { now: tomorrow });
  const day2 = new Date(tomorrow * 1000).toISOString().slice(0, 10);
  assert.equal(lim.keys().length, 3);
  assert.ok(lim.keys().every((k) => k.startsWith(day2 + "|")));
});

await test("the SQL runs on real SQLite", async () => {
  let DatabaseSync;
  try {
    ({ DatabaseSync } = await import("node:sqlite"));
  } catch {
    console.log("    (node:sqlite not in this Node, checked with the fake only)");
    return;
  }
  const db = new DatabaseSync(":memory:");
  const sql = { exec: (q, ...b) => {
    const st = db.prepare(q);
    const rows = /^SELECT/.test(q) ? st.all(...b) : (st.run(...b), []);
    return { toArray: () => rows };
  } };
  const obj = new RelayLimits({ storage: { sql } });
  const ip = "0".repeat(32);
  const q = { day: DAY, hour: 8, sec: 28800, id: ID, ip, kind: "tok", cost: 10 };
  for (let i = 0; i < 90; i++) assert.equal(obj.take(q).result, "ok");
  assert.deepEqual(obj.take(q), { result: "id", retry: TO_DAY });
  assert.equal(db.prepare("SELECT n FROM counts WHERE k = ?").get("u:" + ID).n, 900);
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM counts").get().c, 3);
  for (let i = 0; i < 2; i++) assert.equal(obj.take({ ...q, kind: "legacy", cost: 30, id: "b".repeat(64) }).result, "ok");
  assert.equal(obj.take({ ...q, kind: "legacy", cost: 30, id: "b".repeat(64) }).result, "id");
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM counts").get().c, 6);
  assert.equal(obj.take({ ...q, young: true, id: "c".repeat(64) }).result, "ok");
  assert.equal(db.prepare("SELECT n FROM counts WHERE k = ?").get("y:" + ip).n, 10);
  assert.equal(obj.take({ ...q, day: "2099-01-01" }).result, "ok");
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM counts").get().c, 3);
});

await test("the limits object refuses malformed questions", async () => {
  const lim = new FakeLimits();
  const stub = lim.get("limits");
  const good = { day: DAY, hour: 8, sec: 28800, id: ID, ip: "0".repeat(32), kind: "tok", cost: 10 };
  assert.equal((await stub.fetch("https://limits/take", { method: "POST", body: JSON.stringify(good) })).status, 200);
  for (const bad of [{ hour: 24 }, { sec: 86400 }, { cost: 0 }, { cost: 61 }, { kind: "x" }, { daily: 5 }, { ip: "zz" }]) {
    const r = await stub.fetch("https://limits/take", { method: "POST", body: JSON.stringify({ ...good, ...bad }) });
    assert.equal(r.status, 400, JSON.stringify(bad));
  }
  assert.equal((await stub.fetch("https://limits/take", { method: "POST", body: "nope" })).status, 400);
});

await test("no address header still counts (as one shared group)", async () => {
  const e = env();
  for (let i = 0; i < 4; i++) assert.equal((await call(e, login(hexId(i, "e")), { ip: null })).status, 200);
  assert.equal((await call(e, login("f".repeat(64)), { ip: null })).data.error, "ip_limit");
});

await test("no limits object bound, or a broken one: fails closed", async () => {
  const e = env();
  delete e.LIMITS;
  upstreamCalls.length = 0;
  const r = await call(e, login(ID));
  assert.equal(r.status, 503);
  assert.equal(r.data.error, "limits_unavailable");
  assert.equal(upstreamCalls.length, 0);
  const lim = new FakeLimits();
  lim.sql.fail = true;
  const r2 = await call(env(lim), login(ID));
  assert.equal(r2.status, 503);
  assert.equal(r2.data.error, "limits_unavailable");
  assert.equal(upstreamCalls.length, 0);
});

await test("missing secrets is 503", async () => {
  const e = env();
  delete e.TURN_KEY_API_TOKEN;
  assert.equal((await call(e, login(ID))).status, 503);
});

await test("upstream failure is 502", async () => {
  const saved = globalThis.fetch;
  globalThis.fetch = async () => new Response("{}", { status: 401 });
  assert.equal((await call(env(), login(ID))).status, 502);
  globalThis.fetch = saved;
});

// ---- Install tokens (relay.md "Install tokens").

function solve(id, t, bits = POW_BITS) {
  for (let n = 0; ; n++) {
    const d = createHash("sha256").update("huginn-relay pow v1:" + id + ":" + t + ":" + n).digest();
    let left = bits, ok = true;
    for (const b of d) {
      if (left <= 0) break;
      if (left >= 8) { if (b !== 0) { ok = false; break; } left -= 8; } else { ok = (b >> (8 - left)) === 0; break; }
    }
    if (ok) return n;
  }
}

async function register(e, id = ID, opts = {}) {
  const t = opts.now ?? NOW;
  return call(e, { id, t, sig: sign(id, t), pow: opts.pow ?? solve(id, t) }, { ...opts, path: "/register" });
}

await test("proof of work: the app's hash and the Worker's agree", async () => {
  const n = solve(ID, NOW);
  assert.equal(n, 465984);
  assert.equal(await powOk(ID, NOW, n), true);
  assert.equal(await powOk(ID, NOW + 1, n, 30), false);
  assert.equal(await powOk(ID, NOW, -1), false);
  assert.equal(await powOk(ID, NOW, 1.5), false);
  assert.equal(POW_BITS, 18);
});

await test("register: proof of work gives a token good for 7 days, carrying when it registered", async () => {
  const r = await register(env());
  assert.equal(r.status, 200);
  assert.equal(r.data.exp, NOW + TOKEN_TTL);
  assert.equal(TOKEN_TTL, 7 * 86400);
  assert.match(r.data.tok, /^\d+\.\d+\.[0-9a-f]{32}$/);
  assert.equal(r.data.tok, await makeToken(env(), ID, NOW + TOKEN_TTL, NOW));
  // Worker-signed with its own secret: another Worker secret gives another token.
  assert.notEqual(r.data.tok, await makeToken(env(new FakeLimits(), { TURN_KEY_API_TOKEN: "other" }), ID, NOW + TOKEN_TTL, NOW));
});

await test("register: no or wrong proof of work, or a bad signature, is refused", async () => {
  const e = env();
  const n = solve(ID, NOW);
  assert.equal((await call(e, login(ID), { path: "/register" })).status, 400);
  // The work is bound to the time: the same number a second later is (almost surely) no good.
  let wrong = n + 1;
  while (await powOk(ID, NOW, wrong)) wrong++;
  const r = await register(e, ID, { pow: wrong });
  assert.equal(r.status, 401);
  assert.equal(r.data.error, "bad_pow");
  assert.equal((await call(e, { id: ID, t: NOW, sig: sign(ID, NOW, "wrong"), pow: n }, { path: "/register" })).status, 401);
  assert.equal((await call(e, null, { path: "/register", method: "GET" })).status, 405);
});

await test("register: 20 a day per address group, 2000 a day for everyone, and logins go on", async () => {
  assert.equal(REG_PER_IP_DAILY, 20);
  assert.equal(REG_DAILY, 2000);
  const lim = new FakeLimits();
  const e = env(lim);
  const n = solve(ID, NOW);
  for (let i = 0; i < 20; i++) assert.equal((await register(e, ID, { pow: n, ip: "203.0.113." + i })).status, 200);
  const r = await register(e, ID, { pow: n, ip: "203.0.113.77" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "ip_reg_limit");
  assert.equal(r.data.retry, TO_DAY);
  assert.equal((await register(e, ID, { pow: n, ip: "198.51.100.30" })).status, 200);
  lim.sql.rows.set(DAY + "|rg", 2000);
  const full = await register(e, ID, { pow: n, ip: "198.51.100.31" });
  assert.equal(full.status, 429);
  assert.equal(full.data.error, "reg_limit");
  // A full day of registrations refuses new registrations only: logins still work.
  assert.equal((await call(e, login(ID), { ip: "192.0.2.31" })).status, 200);
  // Each registration writes 2 rows.
  resetMemory();
  const lim2 = new FakeLimits();
  await register(env(lim2), ID, { pow: n });
  assert.equal(lim2.sql.written, 2);
});

await test("a login with a token renews it, and its budget is not the token-less one", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  const tok = (await register(e)).data.tok;
  // Someone with APP_SECRET used up every login without a token today.
  lim.sql.rows.set(DAY + "|g", LEGACY_POOL_MINUTES);
  const old = await call(e, login("b".repeat(64)));
  assert.equal(old.status, 429);
  assert.equal(old.data.error, "global_limit");
  const later = NOW + 60;
  const r = await call(e, login(ID, { tok, now: later }), { now: later });
  assert.equal(r.status, 200);
  assert.equal(r.data.username, "user123");
  assert.equal(r.data.exp, later + TOKEN_TTL);
  assert.equal(r.data.tok, await makeToken(e, ID, later + TOKEN_TTL, NOW));
  // A login without a token gets no token back.
  const plain = await call(env(), login(ID));
  assert.equal(plain.data.tok, undefined);
  // A token in the 1.36 form still works, and comes back in the new form.
  const v1 = await makeToken(e, ID, NOW + 100);
  const r1 = await call(e, login(ID, { tok: v1 }), { ip: "192.0.2.5" });
  assert.equal(r1.status, 200);
  assert.equal(r1.data.tok, await makeToken(e, ID, NOW + TOKEN_TTL, NOW + 100 - TOKEN_TTL));
});

await test("tokens: another id's, forged or run out are refused", async () => {
  const e = env();
  const tok = (await register(e)).data.tok;
  const other = "b".repeat(64);
  const r = await call(e, login(other, { tok }));
  assert.equal(r.status, 401);
  assert.equal(r.data.error, "bad_token");
  const forged = tok.slice(0, -1) + (tok.endsWith("0") ? "1" : "0");
  assert.equal((await call(e, login(ID, { tok: forged }))).data.error, "bad_token");
  assert.equal((await call(e, login(ID, { tok: 5 }))).data.error, "bad_token");
  const after = NOW + TOKEN_TTL + 1;
  const x = await call(e, login(ID, { tok, now: after }), { now: after });
  assert.equal(x.status, 401);
  assert.equal(x.data.error, "token_expired");
});

await test("a stale time says the Worker's time", async () => {
  const r = await call(env(), login(ID, { now: NOW - 400 }));
  assert.equal(r.status, 401);
  assert.equal(r.data.error, "stale");
  assert.equal(r.data.now, NOW);
  // Corrected by that offset, the next try works.
  const t = NOW - 400 + (r.data.now - (NOW - 400));
  assert.equal((await call(env(), { id: ID, t, sig: sign(ID, t) })).status, 200);
});

await test("LEGACY_UNTIL ends logins without a token, never those with one", async () => {
  assert.equal(legacyOver({}, NOW), false);
  assert.equal(legacyOver({ LEGACY_UNTIL: "" }, NOW), false);
  assert.equal(legacyOver({ LEGACY_UNTIL: String(NOW + 1) }, NOW), false);
  const e = env(new FakeLimits(), { LEGACY_UNTIL: String(NOW) });
  const r = await call(e, login(ID));
  assert.equal(r.status, 403);
  assert.equal(r.data.error, "update_needed");
  const tok = (await register(e)).data.tok;
  assert.equal((await call(e, login(ID, { tok }))).status, 200);
});

await test("the month's relayed media: a token adds, the total comes back, bad reports change nothing", async () => {
  const e = env();
  const tok = (await tokenFor(e, ID));
  const one = await call(e, { ...login(ID, { tok }), mb: 200 }, { path: "/meter" });
  assert.equal(one.status, 200);
  assert.equal(one.data.mb, 200);
  assert.equal((await call(e, { ...login(ID, { tok }), mb: 50 }, { path: "/meter" })).data.mb, 250);
  // 0 only asks.
  assert.equal((await call(e, { ...login(ID, { tok }), mb: 0 }, { path: "/meter" })).data.mb, 250);
  // No token, too much at once, not a whole number: refused, nothing counted.
  assert.equal((await call(e, { ...login(ID), mb: 1 }, { path: "/meter" })).status, 400);
  assert.equal((await call(e, { ...login(ID, { tok }), mb: 5001 }, { path: "/meter" })).status, 400);
  assert.equal((await call(e, { ...login(ID, { tok }), mb: 1.5 }, { path: "/meter" })).status, 400);
  assert.equal((await call(e, { ...login(ID, { tok: "x".repeat(40) }), mb: 1 }, { path: "/meter" })).status, 401);
  assert.equal((await call(e, { ...login(ID, { tok }), mb: 0 }, { path: "/meter" })).data.mb, 250);
  assert.equal((await handle(req(null, "GET", "/meter"), e, NOW)).status, 405);
});

await test("the month's count starts over in a new month, and costs no TURN login", async () => {
  const e = env();
  const tok = await tokenFor(e, ID);
  const calls = upstreamCalls.length;
  await call(e, { ...login(ID, { tok }), mb: 700 }, { path: "/meter" });
  const later = NOW + 40 * 86400;
  const tok2 = await tokenFor(e, ID, later - 86400, later + TOKEN_TTL);
  const r = await call(e, { ...login(ID, { tok: tok2, now: later }), mb: 1 }, { path: "/meter", now: later });
  assert.equal(r.data.mb, 1);
  assert.equal(upstreamCalls.length, calls);
});

console.log(passed + " tests passed");
