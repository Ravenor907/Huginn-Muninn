import { createHash, createHmac } from "node:crypto";
import assert from "node:assert/strict";
import worker, { handle, addressGroup, RelayLimits, SQL } from "./worker.js";

const SECRET = "test-app-secret";
const ID = "a".repeat(64);
const NOW = 1_800_000_000;

const DAY = new Date(NOW * 1000).toISOString().slice(0, 10);

// In-memory stand-in for the Durable Object's SQLite storage: it answers exactly the statements in SQL.
// Rows are kept as "day|key" -> count. `written` counts rows written, as Cloudflare bills them.
class FakeSql {
  constructor() { this.rows = new Map(); this.fail = false; this.written = 0; }
  exec(q, ...b) {
    if (this.fail) throw new Error("storage down");
    const out = [];
    if (q === SQL.schema) {
      // nothing to do
    } else if (q === SQL.wipe) {
      for (const key of [...this.rows.keys()]) if (key.split("|")[0] < b[0]) { this.rows.delete(key); this.written++; }
    } else if (q === SQL.read) {
      const [day, ...keys] = b;
      for (const k of keys) if (this.rows.has(day + "|" + k)) out.push({ k, n: this.rows.get(day + "|" + k) });
    } else if (q === SQL.bump) {
      const key = b[0] + "|" + b[1];
      this.rows.set(key, (this.rows.get(key) || 0) + 1);
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

function env(limits = new FakeLimits()) {
  return { TURN_KEY_ID: "kid", TURN_KEY_API_TOKEN: "tok", APP_SECRET: SECRET, LIMITS: limits };
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

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log("ok  " + name); }

await test("health", async () => {
  const res = await worker.fetch(new Request("https://x/health"), {});
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
});

await test("unknown path is 404, wrong method is 405", async () => {
  assert.equal((await call(env(), {}, { path: "/nope" })).status, 404);
  assert.equal((await call(env(), null, { method: "GET" })).status, 405);
});

await test("valid request returns relay-only credentials", async () => {
  upstreamCalls.length = 0;
  const r = await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 200);
  assert.equal(r.data.username, "user123");
  assert.equal(r.data.credential, "cred456");
  assert.equal(r.data.ttl, 1800);
  assert.equal(r.data.urls.length, 4);
  assert.ok(r.data.urls.every((u) => /^turns?:/.test(u)));
  assert.equal(upstreamCalls.length, 1);
  const c = upstreamCalls[0];
  assert.equal(c.url, "https://rtc.live.cloudflare.com/v1/turn/keys/kid/credentials/generate-ice-servers");
  assert.equal(c.init.headers.Authorization, "Bearer tok");
  assert.deepEqual(JSON.parse(c.init.body), { ttl: 1800 });
});

await test("bad signature is 401", async () => {
  const r = await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW, "wrong") });
  assert.equal(r.status, 401);
});

await test("timestamp outside 300 s is 401, inside is ok", async () => {
  assert.equal((await call(env(), { id: ID, t: NOW - 301, sig: sign(ID, NOW - 301) })).status, 401);
  assert.equal((await call(env(), { id: ID, t: NOW + 301, sig: sign(ID, NOW + 301) })).status, 401);
  assert.equal((await call(env(), { id: ID, t: NOW - 300, sig: sign(ID, NOW - 300) })).status, 200);
});

await test("malformed bodies are 400", async () => {
  assert.equal((await call(env(), "not json")).status, 400);
  assert.equal((await call(env(), { id: "xyz", t: NOW, sig: sign("xyz", NOW) })).status, 400);
  assert.equal((await call(env(), { id: ID, t: String(NOW), sig: sign(ID, NOW) })).status, 400);
});

await test("bodies over 1 KB are 413", async () => {
  upstreamCalls.length = 0;
  // No Content-Length: refused once the bytes read pass 1 KB.
  assert.equal((await call(env(), "x".repeat(2000))).status, 413);
  const big = JSON.stringify({ id: ID, t: NOW, sig: sign(ID, NOW), pad: "x".repeat(1100) });
  assert.equal((await call(env(), big)).status, 413);
  // A Content-Length over 1 KB is refused before the body is read at all.
  const r = new Request("https://relay.example.workers.dev/turn", {
    method: "POST",
    headers: { "CF-Connecting-IP": "203.0.113.7", "content-length": "5000" },
    body: JSON.stringify({ id: ID, t: NOW, sig: sign(ID, NOW) }),
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
  const ok = JSON.stringify({ id: ID, t: NOW, sig: sign(ID, NOW) });
  const padded = ok.slice(0, -1) + ',"p":"' + "x".repeat(1024 - ok.length - 7) + '"}';
  assert.equal(new TextEncoder().encode(padded).length, 1024);
  const rok = new Request("https://relay.example.workers.dev/turn", {
    method: "POST", headers: { "CF-Connecting-IP": "203.0.113.7", "content-length": "1024" }, body: padded,
  });
  assert.equal((await handle(rok, env(), NOW)).status, 200);
  assert.equal(upstreamCalls.length, 1);
});

await test("per id cap of 30 per day", async () => {
  const e = env();
  for (let i = 0; i < 30; i++) {
    assert.equal((await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 200);
  }
  const r = await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "id_limit");
  const other = "b".repeat(64);
  assert.equal((await call(e, { id: other, t: NOW, sig: sign(other, NOW) })).status, 200);
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, { id: ID, t: tomorrow, sig: sign(ID, tomorrow) }, { now: tomorrow })).status, 200);
});

await test("per address cap of 60 per day, whatever the ids", async () => {
  const e = env();
  for (let i = 0; i < 60; i++) {
    const id = i.toString(16).padStart(64, "c");
    assert.equal((await call(e, { id, t: NOW, sig: sign(id, NOW) })).status, 200);
  }
  const fresh = "d".repeat(64);
  const r = await call(e, { id: fresh, t: NOW, sig: sign(fresh, NOW) });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "ip_limit");
  // Another address is still fine, and so is the same one the next day.
  assert.equal((await call(e, { id: fresh, t: NOW, sig: sign(fresh, NOW) }, { ip: "198.51.100.9" })).status, 200);
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, { id: fresh, t: tomorrow, sig: sign(fresh, tomorrow) }, { now: tomorrow })).status, 200);
});

await test("addresses are never stored as they are", async () => {
  const lim = new FakeLimits();
  await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW) }, { ip: "203.0.113.99" });
  await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW) }, { ip: "2001:db8:aa:bb::99" });
  for (const k of lim.keys()) {
    assert.ok(!k.includes("203.0.113") && !k.includes("2001") && !k.includes("db8"), k);
  }
  assert.equal(lim.keys().filter((k) => k.includes("|i:")).length, 2);
});

await test("address keys are keyed with the Worker secret, not a plain hash", async () => {
  const ip = "203.0.113.42";
  const lim = new FakeLimits();
  await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW) }, { ip });
  const stored = lim.keys().find((k) => k.includes("|i:")).split("|i:")[1];
  assert.match(stored, /^[0-9a-f]{32}$/);
  const sha = (x) => createHash("sha256").update(x).digest("hex").slice(0, 32);
  for (const guess of [ip, "ip:" + DAY + ":" + ip, DAY + ":" + ip]) assert.notEqual(stored, sha(guess));
  assert.equal(stored, createHmac("sha256", "tok").update("ip:" + DAY + ":" + ip).digest("hex").slice(0, 32));
  // Another secret gives another key for the same address.
  const lim2 = new FakeLimits();
  const e2 = env(lim2);
  e2.TURN_KEY_API_TOKEN = "other";
  await call(e2, { id: ID, t: NOW, sig: sign(ID, NOW) }, { ip });
  assert.notEqual(lim2.keys().find((k) => k.includes("|i:")).split("|i:")[1], stored);
});

await test("IPv6 addresses group by /48", async () => {
  assert.equal(addressGroup("2001:DB8:0:0:1:2:3:4"), "2001:db8:0::/48");
  assert.equal(addressGroup("2001:db8::1"), "2001:db8:0::/48");
  assert.equal(addressGroup("2001:db8:1:2::"), "2001:db8:1::/48");
  assert.equal(addressGroup("fe80::1%eth0"), "fe80:0:0::/48");
  assert.equal(addressGroup("::ffff:203.0.113.7"), "203.0.113.7");
  assert.equal(addressGroup("203.0.113.7"), "203.0.113.7");
  assert.equal(addressGroup("2001:db8:1:2::1"), addressGroup("2001:db8:1:ffff::1"));
  assert.notEqual(addressGroup("2001:db8:1:2::1"), addressGroup("2001:db8:2:2::1"));
});

await test("one IPv6 /48 shares one address cap, across its /64s", async () => {
  const e = env();
  for (let i = 0; i < 60; i++) {
    const id = i.toString(16).padStart(64, "7");
    const ip = "2001:db8:1:" + (i + 1).toString(16) + "::" + (i * 7 + 1).toString(16);
    assert.equal((await call(e, { id, t: NOW, sig: sign(id, NOW) }, { ip })).status, 200);
  }
  const id = "8".repeat(64);
  const r = await call(e, { id, t: NOW, sig: sign(id, NOW) }, { ip: "2001:db8:1:ffff:ffff:ffff:ffff:ffff" });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "ip_limit");
  assert.equal((await call(e, { id, t: NOW, sig: sign(id, NOW) }, { ip: "2001:db8:2::1" })).status, 200);
});

await test("requests at the same moment can't go over a cap", async () => {
  const e = env();
  const same = await Promise.all(Array.from({ length: 45 }, () => call(e, { id: ID, t: NOW, sig: sign(ID, NOW) })));
  assert.equal(same.filter((r) => r.status === 200).length, 30);
  assert.ok(same.filter((r) => r.status === 429).every((r) => r.data.error === "id_limit"));
  const e2 = env();
  const many = await Promise.all(Array.from({ length: 90 }, (_, i) => {
    const id = i.toString(16).padStart(64, "9");
    return call(e2, { id, t: NOW, sig: sign(id, NOW) }, { ip: "198.51.100.20" });
  }));
  assert.equal(many.filter((r) => r.status === 200).length, 60);
});

await test("each login writes 3 rows; earlier days are wiped", async () => {
  const lim = new FakeLimits();
  const e = env(lim);
  await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(lim.sql.written, 3);
  await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(lim.sql.written, 6);
  assert.equal(lim.calls, 2);
  const tomorrow = NOW + 86400;
  await call(e, { id: ID, t: tomorrow, sig: sign(ID, tomorrow) }, { now: tomorrow });
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
  const ipk = "0".repeat(32);
  for (let i = 0; i < 30; i++) assert.equal(obj.take(DAY, ID, ipk), "ok");
  assert.equal(obj.take(DAY, ID, ipk), "id");
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM counts").get().c, 3);
  assert.equal(obj.take("2099-01-01", ID, ipk), "ok");
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM counts").get().c, 3);
});

await test("no address header still counts (as one shared address)", async () => {
  const e = env();
  for (let i = 0; i < 60; i++) {
    const id = i.toString(16).padStart(64, "e");
    assert.equal((await call(e, { id, t: NOW, sig: sign(id, NOW) }, { ip: null })).status, 200);
  }
  const id = "f".repeat(64);
  assert.equal((await call(e, { id, t: NOW, sig: sign(id, NOW) }, { ip: null })).data.error, "ip_limit");
});

await test("global cap of 3000 per day", async () => {
  const lim = new FakeLimits();
  lim.sql.rows.set(DAY + "|g", 3000);
  const r = await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "global_limit");
});

await test("no limits object bound, or a broken one: fails closed", async () => {
  const e = env();
  delete e.LIMITS;
  upstreamCalls.length = 0;
  const r = await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 503);
  assert.equal(r.data.error, "limits_unavailable");
  assert.equal(upstreamCalls.length, 0);
  const lim = new FakeLimits();
  lim.sql.fail = true;
  const r2 = await call(env(lim), { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r2.status, 503);
  assert.equal(r2.data.error, "limits_unavailable");
  assert.equal(upstreamCalls.length, 0);
});

await test("missing secrets is 503", async () => {
  const e = env();
  delete e.TURN_KEY_API_TOKEN;
  assert.equal((await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 503);
});

await test("upstream failure is 502", async () => {
  const saved = globalThis.fetch;
  globalThis.fetch = async () => new Response("{}", { status: 401 });
  assert.equal((await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 502);
  globalThis.fetch = saved;
});

console.log(passed + " tests passed");
