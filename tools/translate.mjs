#!/usr/bin/env node
// Klic Android localization sync (§10.5).
//
// Parses res/values/strings.xml (English source of truth), finds keys missing in
// each target language, batches them to the Gemini API and writes the target
// strings.xml files back deterministically (source key order, no churn).
//
//   node tools/translate.mjs            # translate missing keys only (idempotent)
//   node tools/translate.mjs --force    # re-translate everything
//
// API key: env GEMINI_API_KEY, falling back to ../klic-assets/env/.translate.env
// relative to the repo root (the workspace-level file OUTSIDE this repo). The key
// must NEVER be committed — this repository is public.
//
// Adding a language = add one entry to LANGS and rerun.

import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const LANGS = [
  {
    code: "ru",
    dir: "values-ru",
    name: "Russian",
    style:
      "Use a friendly, informal-but-polite register (address the user with lowercase «вы»; " +
      "never stiff or bureaucratic). Natural, concise mobile-app Russian.",
  },
  {
    code: "zh-CN",
    dir: "values-zh-rCN",
    name: "Simplified Chinese (zh-Hans)",
    style: "Use Simplified Chinese, concise and natural mobile-app phrasing.",
  },
];

const GLOSSARY =
  'Product glossary: "Klic" is the product name — NEVER translate or transliterate it. ' +
  'Keep technical trade names as-is: Chrome, GitHub, TestFlight, Wi-Fi, HD, PDF, QR, Android, iOS.';

const BATCH_SIZE = 60;
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const sourcePath = join(repoRoot, "app/src/main/res/values/strings.xml");
const force = process.argv.includes("--force");

// ── API key / model ─────────────────────────────────────────────────────────

function apiKey() {
  if (process.env.GEMINI_API_KEY) return process.env.GEMINI_API_KEY.trim();
  const envFile = join(repoRoot, "..", "klic-assets", "env", ".translate.env");
  if (existsSync(envFile)) {
    for (const line of readFileSync(envFile, "utf8").split("\n")) {
      const m = line.match(/^\s*GEMINI_API_KEY\s*=\s*(.+?)\s*$/);
      if (m) return m[1];
    }
  }
  console.error("GEMINI_API_KEY not set and ../klic-assets/env/.translate.env not found — aborting.");
  process.exit(1);
}

const MODEL = process.env.GEMINI_MODEL || "gemini-3-flash-preview";
const ENDPOINT = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`;

// ── strings.xml parsing / writing ───────────────────────────────────────────

/** Parses <string name="k">v</string> entries, returns ordered [key, rawValue]. */
function parseStrings(xml) {
  const entries = [];
  const re = /<string name="([^"]+)"(?:[^>]*)>([\s\S]*?)<\/string>/g;
  let m;
  while ((m = re.exec(xml)) !== null) entries.push([m[1], m[2]]);
  return entries;
}

/** Android resource text → plain text for the translator. */
function decode(v) {
  return v
    .replace(/\\n/g, "\n")
    .replace(/\\'/g, "'")
    .replace(/\\"/g, '"')
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">");
}

/** Plain translated text → Android resource text. */
function encode(v) {
  return v
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/\n/g, "\\n");
}

function writeTarget(dir, ordered) {
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"];
  for (const [key, value] of ordered) {
    lines.push(`    <string name="${key}">${value}</string>`);
  }
  lines.push("</resources>", "");
  const outDir = join(repoRoot, "app/src/main/res", dir);
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "strings.xml"), lines.join("\n"));
}

// ── Gemini ──────────────────────────────────────────────────────────────────

async function translateBatch(key, lang, batch) {
  const prompt = [
    `You translate Android app UI strings from English to ${lang.name} for "Klic", a private messenger (chats, voice/video calls).`,
    GLOSSARY,
    lang.style,
    "Rules:",
    "- Preserve ALL positional placeholders exactly: %1$s, %2$s, %1$d, %% etc.",
    "- Preserve line breaks.",
    "- Keys are stable identifiers giving context (settings_*, call_*, chat_*, …).",
    "- UPPERCASE section labels stay short and uppercase where the target script has case.",
    'Respond with ONLY a JSON object mapping each key to its translation, e.g. {"key": "translation"}.',
    "",
    "Strings to translate:",
    JSON.stringify(Object.fromEntries(batch), null, 2),
  ].join("\n");

  let res;
  for (let attempt = 1; ; attempt++) {
    res = await fetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.2, responseMimeType: "application/json" },
      }),
    });
    if (res.ok) break;
    const body = await res.text();
    // Rate limits / transient server errors: wait and retry.
    if ((res.status === 429 || res.status >= 500) && attempt < 8) {
      const wait = res.status === 429 ? 30_000 : 5_000 * attempt;
      process.stdout.write(`(HTTP ${res.status}, retry in ${wait / 1000}s) `);
      await new Promise((r) => setTimeout(r, wait));
      continue;
    }
    throw new Error(`Gemini HTTP ${res.status}: ${body.slice(0, 300)}`);
  }
  const data = await res.json();
  const parts = data?.candidates?.[0]?.content?.parts ?? [];
  let text = parts.map((p) => p.text ?? "").join("");
  text = text.trim().replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    // Model occasionally emits trailing junk — take the first balanced object.
    const start = text.indexOf("{");
    let depth = 0;
    let end = -1;
    let inString = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (inString) {
        if (ch === "\\") i++;
        else if (ch === '"') inString = false;
      } else if (ch === '"') inString = true;
      else if (ch === "{") depth++;
      else if (ch === "}") {
        depth--;
        if (depth === 0) { end = i; break; }
      }
    }
    if (start === -1 || end === -1) throw new Error("Unparseable model response");
    parsed = JSON.parse(text.slice(start, end + 1));
  }
  const out = {};
  for (const [k] of batch) {
    if (typeof parsed[k] === "string" && parsed[k].length > 0) out[k] = parsed[k];
  }
  return out;
}

// ── Main ────────────────────────────────────────────────────────────────────

const key = apiKey();
const source = parseStrings(readFileSync(sourcePath, "utf8"));
console.log(`Source: ${source.length} strings; model: ${MODEL}`);

for (const lang of LANGS) {
  const targetPath = join(repoRoot, "app/src/main/res", lang.dir, "strings.xml");
  const existing = new Map(
    !force && existsSync(targetPath) ? parseStrings(readFileSync(targetPath, "utf8")) : [],
  );

  const missing = source.filter(([k]) => k !== "app_name" && !existing.has(k));
  console.log(`[${lang.code}] existing: ${existing.size}, to translate: ${missing.length}`);

  const translated = new Map(existing);
  const flush = () => {
    // Deterministic output: source order; only translated keys are written, so a
    // rerun picks up exactly the keys that are still missing.
    const ordered = source
      .filter(([k]) => k !== "app_name" && translated.has(k))
      .map(([k]) => [k, translated.get(k)]);
    writeTarget(lang.dir, ordered);
  };
  for (let i = 0; i < missing.length; i += BATCH_SIZE) {
    const batch = missing.slice(i, i + BATCH_SIZE).map(([k, v]) => [k, decode(v)]);
    process.stdout.write(`[${lang.code}] batch ${i / BATCH_SIZE + 1}/${Math.ceil(missing.length / BATCH_SIZE)}… `);
    const result = await translateBatch(key, lang, batch);
    for (const [k, v] of Object.entries(result)) translated.set(k, encode(v));
    flush(); // incremental — a crash/rate-limit never loses finished batches
    const failed = batch.filter(([k]) => !result[k]).map(([k]) => k);
    console.log(`ok (${Object.keys(result).length}/${batch.length})` +
      (failed.length ? ` MISSING: ${failed.join(", ")}` : ""));
  }
  flush();
  console.log(`[${lang.code}] wrote ${translated.size} strings → res/${lang.dir}/strings.xml`);
}
