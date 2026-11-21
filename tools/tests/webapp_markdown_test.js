#!/usr/bin/env node
/**
 * Tests app/src/main/assets/webapp/index.html's escapeHtml/renderMarkdown
 * against the ACTUAL shipped code — extracted by regex from the real file,
 * not a hand-copied duplicate that could silently drift from what ships.
 *
 * Why this matters more than it looks: renderMarkdown's output goes straight
 * into innerHTML for every chat bubble, including the model's own streamed
 * output. escapeHtml running before any tag-producing transform is the only
 * thing standing between "the model happened to emit something that looks
 * like a tag" and actual script execution in the page. This is exercised
 * with a real Node process, not just read by eye.
 *
 * Run: node tools/tests/webapp_markdown_test.js
 */
const fs = require("fs");
const path = require("path");

const htmlPath = path.join(__dirname, "..", "..", "app/src/main/assets/webapp/index.html");
const html = fs.readFileSync(htmlPath, "utf8");

function extract(name) {
  const re = new RegExp(`function ${name}[\\s\\S]*?\\n}\\n`);
  const m = html.match(re);
  if (!m) throw new Error(`could not find function ${name}() in ${htmlPath}`);
  return m[0];
}

// eslint-disable-next-line no-eval
eval(extract("escapeHtml") + "\n" + extract("renderMarkdown"));

let failures = 0;
let total = 0;
function check(label, actual, expected) {
  total++;
  if (actual !== expected) {
    failures++;
    console.log("FAIL:", label);
    console.log("  expected:", JSON.stringify(expected));
    console.log("  actual:  ", JSON.stringify(actual));
  }
}

check(
  "raw <script> tag from model output is neutralized, not executed",
  renderMarkdown("<script>alert(1)</script>"),
  "&lt;script&gt;alert(1)&lt;/script&gt;"
);
check(
  "an onerror img payload is neutralized",
  renderMarkdown('<img src=x onerror="alert(1)">'),
  "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;"
);
check("bold", renderMarkdown("**hello**"), "<strong>hello</strong>");
check("italic", renderMarkdown("*hello*"), "<em>hello</em>");
check("inline code", renderMarkdown("`x = 1`"), "<code>x = 1</code>");
check(
  "fenced code block preserves inner newlines exactly, no injected <br>",
  renderMarkdown("```\nlet x = 1;\nlet y = 2;\n```"),
  '<pre class="code">let x = 1;\nlet y = 2;</pre>'
);
check("newline becomes br outside code", renderMarkdown("a\nb"), "a<br>b");
check(
  "asterisks inside a fenced code block are left completely alone",
  renderMarkdown("```\n*not italic* **not bold** a*b\n```"),
  '<pre class="code">*not italic* **not bold** a*b</pre>'
);
check(
  "backticks inside a fenced code block are left alone",
  renderMarkdown("```\nrun `ls -la` here\n```"),
  '<pre class="code">run `ls -la` here</pre>'
);
check("empty string", renderMarkdown(""), "");
check("plain text unaffected", renderMarkdown("just plain text"), "just plain text");
check(
  "multiple code blocks in one message both restore correctly",
  renderMarkdown("```\nfirst\n```\ntext\n```\nsecond\n```"),
  '<pre class="code">first</pre><br>text<br><pre class="code">second</pre>'
);
check(
  "mixed prose and code: bold text before a code block still works",
  renderMarkdown("**Note:** run this:\n```\nfoo()\n```"),
  '<strong>Note:</strong> run this:<br><pre class="code">foo()</pre>'
);

console.log(`\nwebapp_markdown_test: ${total} checks, ${failures} failed`);
process.exit(failures > 0 ? 1 : 0);
