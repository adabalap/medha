#!/usr/bin/env node
/**
 * Executes the REAL app/src/main/assets/webapp/index.html script inside a
 * real DOM/Window (via jsdom) and checks two things a syntax check or a
 * pure-function unit test cannot:
 *
 * 1. The script runs top to bottom without throwing. A script tag that
 *    throws an uncaught error partway through execution silently stops
 *    running everything after that point — including every event-handler
 *    wire-up below it — while the page still renders fine, because the HTML
 *    and CSS are unaffected. That exact failure mode shipped once already:
 *    `var history = [];` at the top level of the script collides with
 *    `window.history`, a real, non-configurable, getter-only browser global.
 *    Node has no such global, so `node --check` and every extracted-function
 *    test in this suite passed cleanly; only an actual Window catches it.
 * 2. Every interactive control (Send, Ingest, the RAG toggle, tab buttons,
 *    Copy) actually has its handler attached afterward — the direct,
 *    checkable symptom of "the page loaded but nothing happens when I use
 *    it" that a person would otherwise only discover on a real device.
 *
 * Requires `jsdom` (`npm install` in this directory first — see README or
 * run.sh, which skips this test with a clear message if node_modules isn't
 * present, exactly like it does when kotlinc or node themselves are missing).
 *
 * Run: node tools/tests/webapp_smoke_test.js
 */
let JSDOM;
try {
  ({ JSDOM } = require("jsdom"));
} catch (e) {
  console.log("jsdom not installed — run `npm install` in tools/tests/ first. Skipping.");
  process.exit(0);
}

const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "..", "app/src/main/assets/webapp/index.html");
let html = fs.readFileSync(htmlPath, "utf8");
// Simulate exactly what LocalServer.serveAsset does for the token placeholder.
html = html.replace("__MEDHA_TOKEN__", "test-token-for-smoke-test");

let failures = 0;
function fail(msg) {
  failures++;
  console.log("FAIL:", msg);
}

const scriptErrors = [];
const dom = new JSDOM(html, {
  runScripts: "dangerously",
  url: "http://127.0.0.1:8001/",
  pretendToBeVisual: true,
  beforeParse(window) {
    // fetch/clipboard don't exist in jsdom; stub them so the test verifies
    // OUR script's correctness, not the absence of browser APIs jsdom
    // doesn't implement. Real network behavior is covered by the Kotlin
    // side's own tests and docs/TESTING.md's on-device checks, not here.
    window.fetch = () => Promise.reject(new Error("stubbed - not testing real network calls"));
    window.navigator.clipboard = { writeText: () => Promise.resolve() };
    window.onerror = (msg, src, line, col, err) => {
      scriptErrors.push(`${msg} (line ${line}, col ${col})`);
    };
  }
});

setTimeout(() => {
  const doc = dom.window.document;

  if (scriptErrors.length > 0) {
    scriptErrors.forEach((e) => fail("uncaught script error during execution: " + e));
  }

  const expectHandler = (id, prop) => {
    const el = doc.getElementById(id);
    if (!el) {
      fail(`element #${id} does not exist in the page`);
      return;
    }
    if (typeof el[prop] !== "function") {
      fail(`#${id}.${prop} was never wired up — its handler assignment never ran`);
    }
  };

  expectHandler("chatSend", "onclick");
  expectHandler("chatClear", "onclick");
  expectHandler("optRag", "onchange");
  expectHandler("ingest", "onclick");
  expectHandler("refreshColls", "onclick");
  expectHandler("copySnippet", "onclick");

  const tabs = doc.querySelectorAll("#apiTabs .tab");
  if (tabs.length !== 4) {
    fail(`expected 4 API reference tabs, found ${tabs.length}`);
  } else {
    tabs.forEach((t, i) => {
      if (typeof t.onclick !== "function") fail(`API tab #${i} was never wired up`);
    });
  }

  if (!doc.getElementById("snippetBody").textContent) {
    fail("snippetBody is empty — showSnippet('curl-chat') never ran at boot");
  }

  console.log(
    failures === 0
      ? "webapp_smoke_test: script executed cleanly, all controls wired, 0 failures"
      : `webapp_smoke_test: ${failures} failure(s)`
  );
  process.exit(failures > 0 ? 1 : 0);
}, 300);
