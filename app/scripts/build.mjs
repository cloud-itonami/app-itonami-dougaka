// Static build: dist/ is the whole dougaka Studio UI for the Tauri v3 shell.
// No bundler and no dependency beyond Node. The UI is the repo's own
// site/ (studio-host.js + studio-*.js/css, scittle sources) and src/
// (dougaka .cljk), copied verbatim; only the pinned client runtime, the
// pinned kotoba.lang.text source and the DADS stylesheet come from
// app/vendor/ (sha-verified against vendor/PINS.json, which records the
// site/site.edn pins). The shell (src/index.html) mirrors site/dougaka/
// page.cljk: same mount ids, same script order, same CSP — the web site
// build (scripts/build-site.cljk) stays canonical for the web; this build
// exists because the Tauri shell needs a dist/ without kbb/sibling
// checkouts at APK build time.
import { mkdir, copyFile, readFile, writeFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve, join, dirname } from "node:path";
import { createHash } from "node:crypto";

export const TAURI = "3.0.0-alpha.6";
// Same policy as site/dougaka/page.cljk, plus the Tauri IPC bridge.
// connect-src names exactly the declared generation origin (studio-host.js
// asserts this against the client runtime descriptor); scittle needs
// 'unsafe-eval'/'unsafe-inline', media previews need blob:.
export const CSP = "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src blob:; connect-src ipc: http://ipc.localhost https://generation.murakumo.cloud; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";

const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");

export async function build(appRoot = fileURLToPath(new URL("../", import.meta.url))) {
  const repo = resolve(appRoot, "..");
  const out = resolve(appRoot, "dist");
  const meta = JSON.parse(await readFile(resolve(appRoot, "package.json"), "utf8"));

  const pins = JSON.parse(await readFile(resolve(appRoot, "vendor/PINS.json"), "utf8"));
  for (const [name, digest] of Object.entries(pins.files)) {
    if (sha(await readFile(resolve(appRoot, "vendor", name))) !== digest) throw new Error(`canonical runtime pin drift: ${name}`);
  }

  const conf = JSON.parse(await readFile(resolve(appRoot, "src-tauri/tauri.conf.json"), "utf8"));
  const expect = (what, got, want) => { if (got !== want) throw new Error(`${what} is ${JSON.stringify(got)}, package.json gives ${JSON.stringify(want)}`); };
  expect("tauri.conf.json identifier", conf.identifier, meta.itonami?.id);
  expect("tauri.conf.json productName", conf.productName, meta.itonami?.title);
  expect("tauri.conf.json window title", conf.app?.windows?.[0]?.title, meta.itonami?.title);
  expect("tauri.conf.json version", conf.version, meta.version);
  expect("tauri.conf.json csp", conf.app?.security?.csp, CSP);

  const sources = {};
  const copy = async (from, to, sourceKey = null) => {
    const bytes = await readFile(from);
    if (sourceKey) sources[sourceKey] = sha(bytes);
    await mkdir(dirname(to), { recursive: true });
    await writeFile(to, bytes);
  };

  await rm(out, { recursive: true, force: true });
  await mkdir(out, { recursive: true });

  // Scittle vendor stack (site/vendor, in site.edn order).
  const vendor = ["react.production.min.js", "react-dom.production.min.js", "scittle.js", "scittle.reagent.js", "scittle.re-frame.js"];
  for (const name of vendor) await copy(resolve(repo, "site/vendor", name), join(out, "vendor", name), `site/vendor/${name}`);

  // Pinned runtime + text + DADS stylesheet (app/vendor, pin-verified above).
  await copy(resolve(appRoot, "vendor/kotoba-client-runtime.js"), join(out, "kotoba-client-runtime.js"));
  await copy(resolve(appRoot, "vendor/kotoba-client-consent.css"), join(out, "kotoba-client-consent.css"));
  await copy(resolve(appRoot, "vendor/dds.css"), join(out, "dds.css"));
  await copy(resolve(appRoot, "vendor/kotoba/lang/text.cljs"), join(out, "kotoba/lang/text.cljs"));

  // Repo's own UI sources: ClojureScript (.cljk -> .cljs) and studio modules.
  for (const name of ["core", "ingest", "export", "view"]) {
    await copy(resolve(repo, `src/dougaka/${name}.cljk`), join(out, `dougaka/${name}.cljs`), `src/dougaka/${name}.cljk`);
  }
  await copy(resolve(repo, "site/app.cljk"), join(out, "app.cljs"), "site/app.cljk");
  for (const name of ["studio-contract.js", "studio-core.js", "studio-local.js", "studio-host.js", "studio.css"]) {
    await copy(resolve(repo, "site", name), join(out, name), `site/${name}`);
  }

  const index = (await readFile(resolve(appRoot, "src/index.html"), "utf8"))
    .replaceAll("{{CSP}}", CSP)
    .replaceAll("{{TITLE}}", meta.itonami.title.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])));
  if (index.includes("{{")) throw new Error("index.html has an unfilled placeholder");
  // External-request zero (scripts/build-site.cljk rule): every src/href is relative.
  const refs = [...index.matchAll(/(?:src|href)="([^"]*)"/g)].map((m) => m[1]);
  const bad = refs.filter((r) => !r.startsWith("./"));
  if (bad.length) throw new Error(`external or absolute reference: ${JSON.stringify(bad)}`);
  await writeFile(join(out, "index.html"), index);

  const names = ["index.html", "dds.css", "studio.css", "kotoba-client-consent.css", "studio-host.js", "studio-contract.js", "studio-core.js", "studio-local.js", "kotoba-client-runtime.js", "kotoba/lang/text.cljs", "dougaka/core.cljs", "dougaka/ingest.cljs", "dougaka/export.cljs", "dougaka/view.cljs", "app.cljs", ...vendor.map((v) => `vendor/${v}`)];
  const files = {};
  for (const name of names) {
    if (!existsSync(join(out, name))) throw new Error(`dist is missing ${name}`);
    files[name] = sha(await readFile(join(out, name)));
  }
  const release = { schema: "awai.app.artifact.v1", id: meta.itonami.id, source: meta.itonami.source, title: meta.itonami.title, version: meta.version, tauri: TAURI, runtimePins: pins, sources, files };
  await writeFile(join(out, "release.json"), JSON.stringify(release, null, 2) + "\n");
  return { release };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { release } = await build();
  console.log(`${release.title} (${release.id}) static build: ${Object.keys(release.files).length} pinned files from ${Object.keys(release.sources).length} sources`);
}
