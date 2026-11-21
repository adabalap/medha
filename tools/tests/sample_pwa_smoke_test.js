#!/usr/bin/env node
/**
 * Executes the REAL samples/notify-pwa/index.html script inside a jsdom
 * Window and checks it runs top to bottom and wires every control.
 *
 * Same reasoning as webapp_smoke_test.js: an uncaught error partway through
 * a <script> silently stops everything after it while the page still looks
 * fine. This sample is the thing a third-party developer copies, so it
 * failing that way is worse than the bundled demo failing — they would
 * assume Medha's API was broken, not the example.
 *
 * It also pins the two behaviours the sample is *about*:
 *   - localStorage access is wrapped, so a private-mode browser does not
 *     halt the script; and
 *   - a file:// origin disables pairing up front rather than failing later
 *     with a confusing network error.
 *
 * Requires `jsdom` (npm install in this directory).
 * Run: node tools/tests/sample_pwa_smoke_test.js
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

const htmlPath = path.join(__dirname, "..", "..", "samples/notify-pwa/index.html");
const html = fs.readFileSync(htmlPath, "utf8");

let failures = 0;
function fail(msg) {
  failures++;
  console.log("FAIL:", msg);
}

function load(url, opts) {
  const scriptErrors = [];
  const dom = new JSDOM(html, {
    runScripts: "dangerously",
    url,
    pretendToBeVisual: true,
    beforeParse(window) {
      window.fetch = () => Promise.reject(new Error("stubbed - not testing real network"));
      if (opts && opts.breakStorage) {
        // A private-mode browser throws on access rather than returning
        // null. The sample must survive it, because a person testing a
        // notification API in a private tab is entirely plausible.
        Object.defineProperty(window, "localStorage", {
          get() { throw new Error("SecurityError: storage is disabled"); }
        });
      }
      window.addEventListener("error", (e) => {
        scriptErrors.push(e.error ? e.error.stack || String(e.error) : String(e.message));
      });
    }
  });
  return { dom, scriptErrors };
}

// ------------------------------- served over http -------------------------------

{
  const { dom, scriptErrors } = load("https://notes.example.com/");
  const doc = dom.window.document;

  if (scriptErrors.length) {
    fail("script threw while executing:\n    " + scriptErrors.join("\n    "));
  }

  const expectHandler = (id, prop) => {
    const el = doc.getElementById(id);
    if (!el) { fail(`#${id} is missing from the sample`); return; }
    if (typeof el[prop] !== "function") {
      fail(`#${id}.${prop} was never wired — the handler assignment never ran`);
    }
  };

  ["check", "pair", "forget", "post", "progress", "cancel",
   "briefPut", "briefGet", "briefClear"]
    .forEach((id) => expectHandler(id, "onclick"));

  // Unpaired, the three posting controls must be disabled: an enabled button
  // that always 401s is a worse experience than a disabled one.
  ["post", "progress", "cancel", "briefPut", "briefGet", "briefClear"].forEach((id) => {
    const el = doc.getElementById(id);
    if (el && !el.disabled) fail(`#${id} should be disabled before pairing`);
  });

  const pairOut = doc.getElementById("pairOut");
  if (!pairOut || !/not paired/i.test(pairOut.textContent)) {
    fail(`pairOut should say "Not paired." initially, got: ${pairOut && pairOut.textContent}`);
  }
}

// ------------------------------- private mode -------------------------------

{
  const { dom, scriptErrors } = load("https://notes.example.com/", { breakStorage: true });
  if (scriptErrors.length) {
    fail("script threw when localStorage is unavailable (private mode):\n    " +
      scriptErrors.join("\n    "));
  }
  const el = dom.window.document.getElementById("pair");
  if (!el || typeof el.onclick !== "function") {
    fail("#pair was never wired when localStorage throws");
  }
}

// ------------------------------- file:// origin -------------------------------

{
  const { dom, scriptErrors } = load("file:///tmp/index.html");
  if (scriptErrors.length) {
    fail("script threw on a file:// origin:\n    " + scriptErrors.join("\n    "));
  }
  const doc = dom.window.document;
  const pair = doc.getElementById("pair");
  if (pair && !pair.disabled) {
    fail("pairing must be disabled on a file:// origin (Origin: null cannot be bound)");
  }
  const out = doc.getElementById("pairOut");
  if (!out || !/file:\/\//.test(out.textContent)) {
    fail("the file:// case should explain itself, got: " + (out && out.textContent));
  }
}

console.log(
  failures === 0
    ? "sample_pwa_smoke_test: script executed cleanly in all three contexts, 0 failures"
    : `sample_pwa_smoke_test: ${failures} failure(s)`
);
process.exit(failures === 0 ? 0 : 1);
