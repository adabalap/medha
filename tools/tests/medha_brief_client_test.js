#!/usr/bin/env node
/**
 * Exercises the REAL samples/medha-brief.js against a stubbed fetch.
 *
 * This is the file every PWA copies, so a bug in it is a bug in all of them
 * at once — and unlike the page samples, it has branching logic worth
 * running: token storage that can throw, item normalisation, and error
 * mapping for five unrelated causes that all surface as "Failed to fetch".
 *
 * Run: node tools/tests/medha_brief_client_test.js
 */
const path = require("path");
const { pathToFileURL } = require("url");

let failures = 0;
let checks = 0;

function check(what, ok) {
  checks++;
  if (ok) console.log("  ok    " + what);
  else { console.log("  FAIL  " + what); failures++; }
}

function eq(what, actual, expected) {
  checks++;
  const a = JSON.stringify(actual), b = JSON.stringify(expected);
  if (a === b) console.log("  ok    " + what);
  else {
    console.log(`  FAIL  ${what}\n          expected: ${b}\n          actual:   ${a}`);
    failures++;
  }
}

async function throws(what, fn, code) {
  checks++;
  try {
    await fn();
    console.log(`  FAIL  ${what} (did not throw)`);
    failures++;
  } catch (e) {
    if (code && e.code !== code) {
      console.log(`  FAIL  ${what} (code was '${e.code}', expected '${code}')`);
      failures++;
    } else {
      console.log("  ok    " + what);
    }
  }
}

/** An in-memory Storage that behaves like the real one. */
function fakeStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    _dump: () => Object.fromEntries(m)
  };
}

/** Storage that throws on every access, like a private window. */
const hostileStorage = {
  getItem() { throw new Error("SecurityError"); },
  setItem() { throw new Error("SecurityError"); },
  removeItem() { throw new Error("SecurityError"); }
};

/** Records calls and replies from a scripted queue. */
function stubFetch(script) {
  const calls = [];
  global.fetch = async (url, opts = {}) => {
    calls.push({ url, method: opts.method || "GET", headers: opts.headers || {},
                 body: opts.body ? JSON.parse(opts.body) : undefined });
    const next = script.shift();
    if (!next) throw new Error("stub fetch: no scripted reply left");
    if (next.networkError) throw new TypeError("Failed to fetch");
    return {
      ok: next.status === undefined || (next.status >= 200 && next.status < 300),
      status: next.status || 200,
      headers: { get: (h) => (next.headers || {})[h] || null },
      json: async () => {
        if (next.noBody) throw new Error("no body");
        return next.body;
      }
    };
  };
  return calls;
}

async function run() {
  const mod = await import(
    pathToFileURL(path.join(__dirname, "..", "..", "samples", "medha-brief.js")).href
  );
  const { Medha, MedhaError } = mod;

  // ------------------------------------------------------------- pairing
  console.log("\nMedha — pairing");
  {
    const store = fakeStorage();
    const m = new Medha({ port: 8001, appKey: "journal", storage: store });
    check("starts unpaired", !m.paired);

    const calls = stubFetch([{ body: {
      token: "tok123", clientId: "journal", namespace: "journal",
      origin: "https://notes.example.com", capabilities: ["notify", "generate"]
    } }]);

    const s = await m.pair("418 902");
    eq("digits are extracted from formatted input", calls[0].body, { code: "418902" });
    eq("posts to /pair", calls[0].url, "http://127.0.0.1:8001/pair");
    // Pairing must NOT send a token; that is the whole point of the endpoint.
    check("no Authorization header on /pair", !calls[0].headers["Authorization"]);
    check("now paired", m.paired);
    eq("session carries the clientId", s.clientId, "journal");
    check("token persisted", JSON.parse(store._dump()["medha.token.journal"]).token === "tok123");
  }

  console.log("\nMedha — pairing rejects malformed codes before hitting the network");
  {
    const m = new Medha({ storage: fakeStorage() });
    stubFetch([]);  // any call would throw "no scripted reply"
    await throws("four digits", () => m.pair("1234"), "bad_code");
    await throws("empty", () => m.pair(""), "bad_code");
    await throws("letters only", () => m.pair("abcdef"), "bad_code");
  }

  // ---------------------------------------------------------- auth guard
  console.log("\nMedha — calls before pairing fail with a usable message");
  {
    const m = new Medha({ storage: fakeStorage() });
    stubFetch([]);
    await throws("postBrief", () => m.postBrief([{ title: "x" }]), "not_paired");
    await throws("notify", () => m.notify({ title: "x" }), "not_paired");
    await throws("readBrief", () => m.readBrief(), "not_paired");
  }

  // --------------------------------------------------------------- brief
  console.log("\nMedha — postBrief");
  {
    const store = fakeStorage();
    store.setItem("medha.token.a", JSON.stringify({ token: "T", clientId: "a" }));
    const m = new Medha({ appKey: "a", storage: store });

    const calls = stubFetch([{ body: { ok: true, items: 2, pageRows: 3, maxRows: 12 } }]);
    const res = await m.postBrief(
      [{ title: "One", text: "first" }, { title: "Two" }],
      { ttlSeconds: 3600 }
    );
    eq("PUTs to /widget/content", [calls[0].method, calls[0].url],
       ["PUT", "http://127.0.0.1:8001/widget/content"]);
    eq("sends bearer token", calls[0].headers["Authorization"], "Bearer T");
    eq("missing text becomes empty string", calls[0].body.items[1], { title: "Two", text: "" });
    eq("ttlSeconds passed through", calls[0].body.ttlSeconds, 3600);
    // The response reports what was KEPT; conflating that with what was sent
    // is how v0.9.0 hid the fact that it was dropping items.
    eq("returns the server's kept count", res.items, 2);
    eq("and both row numbers, which mean different things",
       [res.pageRows, res.maxRows], [3, 12]);
  }

  console.log("\nMedha — item normalisation happens before the request");
  {
    const store = fakeStorage();
    store.setItem("medha.token.a", JSON.stringify({ token: "T" }));
    const m = new Medha({ appKey: "a", storage: store });

    const calls = stubFetch([{ body: { ok: true, items: 5 } }]);
    await m.postBrief([
      { title: "  spaced  ", text: "  padded  " },
      { title: "" },                       // dropped: no title
      { title: "   " },                    // dropped: blank title
      null,                                // dropped: not an item
      { title: "a" }, { title: "b" }, { title: "c" }, { title: "d" }, { title: "e" }
    ]);
    const sent = calls[0].body.items;
    eq("blank and null entries dropped", sent.length, 5);
    eq("whitespace trimmed", sent[0], { title: "spaced", text: "padded" });
    eq("capped at five, keeping the first five valid ones",
       sent.map((i) => i.title), ["spaced", "a", "b", "c", "d"]);

    const calls2 = stubFetch([{ body: { ok: true, items: 1 } }]);
    await m.postBrief([{ title: "x".repeat(200), text: "y".repeat(500) }]);
    eq("title truncated to 80", calls2[0].body.items[0].title.length, 80);
    eq("text truncated to 200", calls2[0].body.items[0].text.length, 200);

    const calls3 = stubFetch([{ body: { ok: true, items: 0 } }]);
    await m.postBrief([]);
    eq("an empty array is sent as a withdrawal", calls3[0].body.items, []);

    stubFetch([]);
    await throws("a non-array is rejected locally", () => m.postBrief("nope"), "bad_request");
  }

  // -------------------------------------------------------- notifications
  console.log("\nMedha — notify");
  {
    const store = fakeStorage();
    store.setItem("medha.token.a", JSON.stringify({ token: "T" }));
    const m = new Medha({ appKey: "a", storage: store });

    const calls = stubFetch([{ body: { posted: true, id: "build" } }]);
    await m.notify({ id: "build", title: "Done", text: "green", ongoing: true,
                     progressCurrent: 40, progressMax: 100 });
    eq("POSTs to /notify", calls[0].url, "http://127.0.0.1:8001/notify");
    eq("progress carried", [calls[0].body.progressCurrent, calls[0].body.progressMax], [40, 100]);
    eq("silent defaults to true", calls[0].body.silent, true);
    eq("ongoing carried", calls[0].body.ongoing, true);

    const calls2 = stubFetch([{ body: { posted: true } }]);
    await m.notify({ title: "Plain" });
    eq("id defaults", calls2[0].body.id, "default");
    eq("ongoing defaults false", calls2[0].body.ongoing, false);
    eq("absent progress sent as -1", calls2[0].body.progressCurrent, -1);

    const calls3 = stubFetch([{ body: { posted: true } }]);
    await m.notify({ title: "Loud", silent: false });
    eq("silent:false is respected, not coerced", calls3[0].body.silent, false);

    stubFetch([]);
    await throws("title is required", () => m.notify({ text: "no title" }), "bad_request");

    // posted:false is a SUCCESS with bad news, not an error.
    stubFetch([{ body: { posted: false, reason: "notifications are disabled" } }]);
    const r = await m.notify({ title: "x" });
    check("posted:false resolves rather than throwing", r.posted === false);
    check("and carries the reason", /disabled/.test(r.reason));

    const calls4 = stubFetch([{ body: { cancelled: true } }]);
    await m.cancelNotification("a/b");
    eq("cancel id is URL-encoded", calls4[0].url, "http://127.0.0.1:8001/notify/a%2Fb");
  }

  // ---------------------------------------------------------- error paths
  console.log("\nMedha — errors say which of the causes it was");
  {
    const store = fakeStorage();
    store.setItem("medha.token.a", JSON.stringify({ token: "T" }));
    const m = new Medha({ appKey: "a", storage: store });

    stubFetch([{ status: 403, body: { error: "token is bound to X", code: "origin_mismatch" } }]);
    try { await m.readBrief(); } catch (e) {
      check("origin_mismatch keeps its code", e.code === "origin_mismatch");
      check("and explains what to do", /pair again/.test(e.message));
    }
    checks += 2;

    stubFetch([{ status: 403, body: { error: "lacks capability 'notify'", code: "forbidden" } }]);
    try { await m.notify({ title: "x" }); } catch (e) {
      check("forbidden points at the missing capability", /Post notifications/.test(e.message));
    }
    checks += 1;

    stubFetch([{ status: 429, body: { error: "rate limit reached", code: "rate_limited" },
                 headers: { "Retry-After": "7" } }]);
    try { await m.notify({ title: "x" }); } catch (e) {
      check("429 surfaces Retry-After", /retry in 7s/.test(e.message));
      check("with the rate_limited code", e.code === "rate_limited");
    }
    checks += 2;

    stubFetch([{ networkError: true }]);
    try { await m.readBrief(); } catch (e) {
      check("a bare network failure is expanded into causes", /Is Medha running/.test(e.message));
      check("including the file:// trap", /file:\/\//.test(e.message));
      check("coded unreachable", e.code === "unreachable");
    }
    checks += 3;

    // An error response with no JSON body must not mask the status.
    stubFetch([{ status: 500, noBody: true }]);
    try { await m.readBrief(); } catch (e) {
      check("a bodyless 500 still reports a status", e.status === 500);
    }
    checks += 1;
  }

  // ------------------------------------------------------------- storage
  console.log("\nMedha — storage that throws (private window) is survivable");
  {
    const m = new Medha({ appKey: "p", storage: hostileStorage });
    check("reports unpaired rather than throwing", m.paired === false);

    stubFetch([{ body: { token: "T2", clientId: "p" } }]);
    const s = await m.pair("111111");
    eq("pairing still returns a session", s.token, "T2");
    // The token survives in memory for this page view, so the app works
    // until reload rather than being unusable in a private tab.
    check("and the session is usable in memory", m.paired === true);

    const calls = stubFetch([{ body: { ok: true, items: 1 } }]);
    await m.postBrief([{ title: "works" }]);
    eq("authenticated call uses the in-memory token",
       calls[0].headers["Authorization"], "Bearer T2");
  }

  console.log("\nMedha — appKey isolates two apps on one origin");
  {
    const shared = fakeStorage();
    const a = new Medha({ appKey: "one", storage: shared });
    const b = new Medha({ appKey: "two", storage: shared });

    stubFetch([{ body: { token: "A", clientId: "one" } }]);
    await a.pair("111111");
    check("a is paired", a.paired);
    check("b is not", !b.paired);

    stubFetch([{ body: { token: "B", clientId: "two" } }]);
    await b.pair("222222");
    eq("tokens do not collide",
       [a.session.token, b.session.token], ["A", "B"]);

    a.forget();
    check("forgetting a leaves b alone", !a.paired && b.paired);
  }

  console.log("\nMedha — default storage is probed, not assumed");
  {
    // Every other test passes an explicit `storage`, so the default branch
    // -- the one every real PWA actually takes -- was never exercised. A
    // mutation replacing the probe with a bare `localStorage` reference
    // passed the whole suite.
    const originalDescriptor = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
    Object.defineProperty(globalThis, "localStorage", {
      configurable: true,
      get() { throw new Error("SecurityError: storage is disabled"); }
    });
    try {
      let constructed = true;
      let m;
      try {
        m = new Medha({ appKey: "probe" });   // no storage option at all
      } catch (_) {
        constructed = false;
      }
      check("constructing with hostile localStorage does not throw", constructed);
      if (constructed) {
        check("and reports unpaired", m.paired === false);
        stubFetch([{ body: { token: "T3", clientId: "probe" } }]);
        await m.pair("333333");
        check("pairing still works, held in memory", m.paired === true);
      }
    } finally {
      if (originalDescriptor) {
        Object.defineProperty(globalThis, "localStorage", originalDescriptor);
      } else {
        delete globalThis.localStorage;
      }
    }
  }

  console.log("\nMedha — health needs no token");
  {
    const m = new Medha({ storage: fakeStorage() });
    const calls = stubFetch([{ body: { status: "ok", modelLoaded: false, backend: "GPU" } }]);
    const h = await m.health();
    check("no Authorization header", !calls[0].headers || !calls[0].headers["Authorization"]);
    // The brief and notifications work with no model loaded; only inference
    // needs one. An integrator who gates on modelLoaded blocks themselves.
    check("works with modelLoaded false", h.modelLoaded === false);
  }

  console.log("\n--------------------------------------------------");
  if (failures === 0) {
    console.log(`medha_brief_client_test: all ${checks} checks passed`);
  } else {
    console.log(`medha_brief_client_test: ${failures} of ${checks} checks FAILED`);
    process.exit(1);
  }
}

// An unexpected throw must report as a failure naming where it happened,
// not as a bare stack trace. Mutation testing produced exactly that: three
// broken implementations killed the suite mid-run, and the output said
// nothing about which behaviour had regressed.
run().catch((e) => {
  console.log("\n--------------------------------------------------");
  console.log("  FAIL  the suite threw before finishing — a call that should");
  console.log("        have succeeded did not. Last completed section above.");
  console.log("        " + (e && e.stack ? e.stack.split("\n")[0] : String(e)));
  console.log(`medha_brief_client_test: ABORTED after ${checks} checks ` +
              `(${failures} already failed)`);
  process.exit(1);
});
