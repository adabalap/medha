#!/usr/bin/env node
/**
 * Tests the SSE event-framing logic inside readSse() in
 * app/src/main/assets/webapp/index.html — specifically, that an event
 * straddling a network chunk boundary reassembles correctly rather than
 * being silently corrupted, which is exactly the bug a prior version of
 * this file had (see the comment above readSse in the real file).
 *
 * readSse() itself takes a real fetch() Response and can't be driven
 * without a browser, so this re-extracts just the inner buffering loop as
 * a standalone function and drives it with synthetic chunk boundaries —
 * including boundaries a real network stream would rarely produce on its
 * own (mid-JSON-token, mid-terminator), which is the point: those are
 * exactly the cases worth testing deliberately rather than hoping for.
 *
 * Run: node tools/tests/webapp_sse_test.js
 */
const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "..", "app/src/main/assets/webapp/index.html");
const html = fs.readFileSync(htmlPath, "utf8");

// Confirm the real file still frames events the same way before testing a
// reimplementation of that inner loop against it — if this indexOf/split
// shape ever changes in index.html, this assertion is the tripwire.
if (!html.includes('buf.indexOf("\\n\\n")') || !html.includes('lines[n].indexOf("data: ")')) {
  console.error(
    "webapp_sse_test: readSse's framing logic in index.html no longer matches " +
      "what this test exercises — update both together."
  );
  process.exit(1);
}

function parseSseChunks(rawChunks) {
  const events = [];
  let buf = "";
  rawChunks.forEach((chunk) => {
    buf += chunk;
    let i;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const evt = buf.slice(0, i);
      buf = buf.slice(i + 2);
      const lines = evt.split("\n");
      for (let n = 0; n < lines.length; n++) {
        if (lines[n].indexOf("data: ") !== 0) continue;
        const raw = lines[n].slice(6);
        if (raw === "[DONE]") {
          events.push("__DONE__");
          continue;
        }
        try {
          events.push(JSON.parse(raw));
        } catch (_) {
          /* keep-alive or malformed line; ignore, matching the real code */
        }
      }
    }
  });
  return events;
}

let failures = 0;
let total = 0;
function check(label, actual, expected) {
  total++;
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    failures++;
    console.log("FAIL:", label);
    console.log("  expected:", e);
    console.log("  actual:  ", a);
  }
}

check("single complete event in one chunk", parseSseChunks(['data: {"a":1}\n\n']), [{ a: 1 }]);
check(
  "two events in one chunk",
  parseSseChunks(['data: {"a":1}\n\ndata: {"a":2}\n\n']),
  [{ a: 1 }, { a: 2 }]
);
check(
  "event split across two network chunks reassembles correctly",
  parseSseChunks(['data: {"a":1,"tex', 't":"hello"}\n\n']),
  [{ a: 1, text: "hello" }]
);
check(
  "JSON payload with an embedded newline round-trips intact",
  parseSseChunks(["data: " + JSON.stringify({ text: "line1\nline2" }) + "\n\n"]),
  [{ text: "line1\nline2" }]
);
check(
  "[DONE] sentinel is recognized",
  parseSseChunks(['data: {"a":1}\n\ndata: [DONE]\n\n']),
  [{ a: 1 }, "__DONE__"]
);
check(
  "the split falls exactly between the two newlines of the terminator",
  parseSseChunks(['data: {"a":1}\n', '\ndata: {"a":2}\n\n']),
  [{ a: 1 }, { a: 2 }]
);
check(
  "a non-data line (comment/keep-alive) is ignored, not treated as an event",
  parseSseChunks([": keep-alive\n\ndata: {\"a\":1}\n\n"]),
  [{ a: 1 }]
);
check(
  "three-way split of a single event still reassembles",
  parseSseChunks(["dat", 'a: {"a":', "42}\n\n"]),
  [{ a: 42 }]
);

console.log(`\nwebapp_sse_test: ${total} checks, ${failures} failed`);
process.exit(failures > 0 ? 1 : 0);
