#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const repo = path.resolve(__dirname, "..");
const source = path.join(repo, "xrjs", "src", "eaglerxr-webxr.js");
const output = path.join(repo, "xrjs", "eaglerxr-webxr.js");
const sourceText = fs.readFileSync(source, "utf8").replace(/^\/\*[^\n]*\*\/\s*/, "");
const digest = crypto.createHash("sha256").update(sourceText).digest("hex");
const banner = "/* EaglerXR WebXR runtime; source-sha256=" + digest + " */\n";
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, banner + sourceText);
console.log(`[xrjs] wrote ${output} (${fs.statSync(output).size} bytes, sha256=${digest})`);
