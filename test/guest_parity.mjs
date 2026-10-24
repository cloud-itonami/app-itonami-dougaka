// guest parity — test/fixtures/parity.edn を、コンパイル済みの wasm guest
// （aiueos/dougaka_core.wasm）で回す。adapter 側（test/dougaka/parity_test.cljk）
// と同じ表なので、両方が通れば guest と adapter は表の全行で数値一致している。
//
// 実行 host は kototama-browser-host-v1（kotoba-lang/amu/runtime/browser-host.mjs
// の typed host）。vector 引数を持つので typed-value ABI を通す。digest は
// publication sidecar の sha256 で固定し、違う wasm なら読み込まない。
//
//   node test/guest_parity.mjs [path/to/amu/runtime/browser-host.mjs]
//
// 既定の host パスは west レイアウト（このリポジトリから ../../kotoba-lang/amu）。
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const hostPath = resolve(process.argv[2]
  ?? process.env.AMU_BROWSER_HOST
  ?? resolve(root, "../../kotoba-lang/amu/runtime/browser-host.mjs"));
const { instantiateKotoba } = await import(pathToFileURL(hostPath).href);

// --- parity.edn は EDN の小さな部分集合（vector / map / string / int / keyword）。
function readEdn(text) {
  const src = text.replace(/;[^\n]*/g, "");
  let i = 0;
  const ws = () => { while (i < src.length && /[\s,]/.test(src[i])) i++; };
  const value = () => {
    ws();
    const c = src[i];
    if (c === "[") {
      i++; const out = [];
      for (;;) { ws(); if (src[i] === "]") { i++; return out; } out.push(value()); }
    }
    if (c === "{") {
      i++; const out = {};
      for (;;) {
        ws(); if (src[i] === "}") { i++; return out; }
        const k = value(); out[k] = value();
      }
    }
    if (c === "\"") {
      const end = src.indexOf("\"", i + 1);
      const s = src.slice(i + 1, end); i = end + 1; return s;
    }
    if (c === ":") {
      const m = /^:[^\s,\]}]+/.exec(src.slice(i)); i += m[0].length; return m[0].slice(1);
    }
    const m = /^-?\d+/.exec(src.slice(i));
    if (!m) throw new Error(`unexpected EDN at ${i}: ${src.slice(i, i + 20)}`);
    i += m[0].length; return BigInt(m[0]);
  };
  return value();
}

const rows = readEdn(readFileSync(resolve(root, "test/fixtures/parity.edn"), "utf8"));
const publication = readFileSync(resolve(root, "aiueos/dougaka_core.wasm.publication.edn"), "utf8");
const expectedSha256 = /:name "dougaka_core\.wasm", :sha256 "([0-9a-f]{64})"/.exec(publication)[1];
const bytes = readFileSync(resolve(root, "aiueos/dougaka_core.wasm"));
const k = await instantiateKotoba(bytes, { expectedSha256 });

let failures = 0;
for (const row of rows) {
  const f = k.instance.exports[row.fn];
  if (typeof f !== "function") { console.error(`missing export: ${row.fn}`); failures++; continue; }
  const args = row.args.map(a => Array.isArray(a) ? k.typedValues.vectorI64(a) : a);
  const got = BigInt.asIntN(64, BigInt(f(...args)));
  if (got !== row.expect) {
    failures++;
    console.error(`FAIL ${row.fn} ${JSON.stringify(row.args, (_, v) => typeof v === "bigint" ? Number(v) : v)} expected ${row.expect} got ${got}`);
  }
}
console.log(`guest parity: ${rows.length - failures}/${rows.length} rows match (wasm sha256 ${k.sha256})`);
if (failures > 0) process.exitCode = 1;
