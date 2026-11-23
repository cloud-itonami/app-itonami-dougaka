import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CSP } from "../scripts/build.mjs";

const appRoot = resolve(fileURLToPath(new URL("../", import.meta.url)));
const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");

test("vendor pins match the vendored files (site/site.edn pins)", async () => {
  const pins = JSON.parse(await readFile(join(appRoot, "vendor/PINS.json"), "utf8"));
  for (const [name, digest] of Object.entries(pins.files)) {
    assert.equal(sha(await readFile(join(appRoot, "vendor", name))), digest, name);
  }
  // The two client-runtime files must be the exact site.edn pins.
  assert.equal(pins.files["kotoba-client-runtime.js"], "7f760128c3a58098123c31ec3dd34b9c871932e3933f2c952facc4ab0e647212");
  assert.equal(pins.files["kotoba-client-consent.css"], "eb1caab0619c5ca4428df637ae140bc3774582fa3f0b95a44bbd39b27b6b9086");
});

test("dist carries the real Studio UI, not a blueprint viewer", async () => {
  const index = await readFile(join(appRoot, "dist/index.html"), "utf8");
  for (const ref of ["./studio-host.js", "./studio.css", "./dougaka/core.cljs", "./app.cljs", "./kotoba/lang/text.cljs"]) {
    assert.ok(index.includes(`src="${ref}"`) || index.includes(`href="${ref}"`), ref);
  }
  // Mount ids the real UI needs (site/dougaka/page.cljk contract).
  assert.ok(index.includes('id="dougaka-studio"'));
  assert.ok(index.includes('id="app"'));
  // Every referenced local file exists in dist.
  const refs = [...index.matchAll(/(?:src|href)="\.\/([^"]*)"/g)].map((m) => m[1]);
  assert.ok(refs.length >= 12, `expected many refs, got ${refs.length}`);
  for (const ref of refs) assert.ok(existsSync(join(appRoot, "dist", ref)), ref);
  // studio-host.js imports resolve inside dist.
  const host = await readFile(join(appRoot, "dist/studio-host.js"), "utf8");
  for (const mod of ["./kotoba-client-runtime.js", "./studio-contract.js", "./studio-core.js", "./studio-local.js"]) {
    assert.ok(host.includes(mod), mod);
    assert.ok(existsSync(join(appRoot, "dist", mod.slice(2))), mod);
  }
  const release = JSON.parse(await readFile(join(appRoot, "dist/release.json"), "utf8"));
  assert.equal(release.id, "network.itonami.dougaka");
  assert.equal(release.tauri, "3.0.0-alpha.6");
});

test("THIRD-PARTY-LICENSES.md attributes every vendored file", async () => {
  const bundle = await readFile(join(appRoot, "vendor/THIRD-PARTY-LICENSES.md"), "utf8");
  const sections = [
    ["vendor/kotoba-client-runtime.js", "Apache-2.0", "kotoba-lang/kotoba"],
    ["vendor/kotoba-client-consent.css", "Apache-2.0", "kotoba-lang/kotoba"],
    ["vendor/kotoba/lang/text.cljs", "Apache-2.0", "kotoba-lang/text"],
    ["vendor/dds.css", "MIT", "digital-go-jp/design-system-example-components-html"],
  ];
  for (const [file, spdx, upstream] of sections) {
    const heading = `## ${file}`;
    const at = bundle.indexOf(heading);
    assert.ok(at >= 0, `missing section ${heading}`);
    const body = bundle.slice(at, bundle.indexOf("\n## ", at + 1) === -1 ? undefined : bundle.indexOf("\n## ", at + 1));
    assert.ok(body.includes(spdx), `${file} section names ${spdx}`);
    assert.ok(body.includes(upstream), `${file} section names ${upstream}`);
  }
});

test("CSP is single-sourced and names only the declared generation origin", async () => {
  const conf = JSON.parse(await readFile(join(appRoot, "src-tauri/tauri.conf.json"), "utf8"));
  assert.equal(conf.app.security.csp, CSP);
  const connect = CSP.split("connect-src")[1].split(";")[0].trim().split(/\s+/);
  assert.deepEqual(connect.sort(), ["https://generation.murakumo.cloud", "http://ipc.localhost", "ipc:", "'self'"].sort());
  assert.ok(!CSP.includes("local.itonami.cloud"), "inert local origin stays out of CSP");
  const index = await readFile(join(appRoot, "dist/index.html"), "utf8");
  assert.ok(index.includes(CSP));
});

test("studio-host CSP assertion matches the descriptor network origins", async () => {
  // Regression: the pinned clientRuntimeConnectSrc includes the inert
  // local origin, but only method-bearing operations are communication
  // destinations, so the host must expect generation only and the CSP
  // must not name the inert local origin (device acceptance found the
  // old single-origin literal throwing on load).
  const runtime = await import(join(appRoot, "dist/kotoba-client-runtime.js"));
  const contract = await import(join(appRoot, "..", "site/studio-contract.js"));
  assert.equal(
    runtime.clientRuntimeConnectSrc(contract.runtimeDescriptor, contract.requestedCapabilities),
    `connect-src ${contract.generationOrigin} ${contract.localOrigin}`,
    "pinned runtime derives both origins (including inert local)",
  );
  const networkOrigins = [...new Set(contract.runtimeDescriptor.operations
    .filter((op) => op.endpoint?.method)
    .map((op) => op.endpoint.origin))];
  assert.deepEqual(networkOrigins, [contract.generationOrigin], "only generation is a network destination");
  const host = await readFile(join(appRoot, "dist/studio-host.js"), "utf8");
  assert.ok(host.includes("networkOrigins"), "host asserts network origins only");
});
