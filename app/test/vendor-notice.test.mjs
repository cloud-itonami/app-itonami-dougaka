// Vendor NOTICE propagation (AWAI-3332, Apache-2.0 §4(d)): the vendored
// kotoba runtime must ship the upstream NOTICE byte-for-byte, and PINS.json
// must pin its file name and license. Pure checks over a vendor directory
// so the negative controls below can run on temp copies without failing
// the real tree.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { cp, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const APP = fileURLToPath(new URL("../", import.meta.url));
const VENDOR = join(APP, "vendor");

// Byte-identical upstream kotoba-lang/kotoba NOTICE (364 bytes).
const UPSTREAM_NOTICE =
  "kotoba\n" +
  "Copyright 2026 Jun Kawasaki\n" +
  "\n" +
  "This product includes software developed as part of the kotoba-lang project\n" +
  "(https://github.com/kotoba-lang).\n" +
  "\n" +
  'Licensed under the Apache License, Version 2.0 (the "License");\n' +
  "you may not use this file except in compliance with the License.\n" +
  "You may obtain a copy of the License at\n" +
  "\n" +
  "    http://www.apache.org/licenses/LICENSE-2.0\n";

// sha256 (lowercase hex) of the upstream NOTICE, measured by the Organizer.
const UPSTREAM_NOTICE_SHA256 =
  "a263dafd3b98365765d784f139c44888ef6298d5b2b9ef6edbc5d84ffb86b0c9";

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

// Returns a list of problems with a vendor directory; [] means it passes.
async function vendorProblems(vendorDir) {
  const problems = [];
  let pins;
  try {
    pins = JSON.parse(await readFile(join(vendorDir, "PINS.json"), "utf8"));
  } catch (err) {
    return [`PINS.json is not readable JSON: ${err.message}`];
  }
  if (pins.license !== "Apache-2.0") {
    problems.push(`license must be exactly "Apache-2.0", got ${JSON.stringify(pins.license)}`);
  }
  if (pins.notice !== "NOTICE") {
    problems.push(`notice must be exactly "NOTICE", got ${JSON.stringify(pins.notice)}`);
  }
  const noticePath = join(vendorDir, "NOTICE");
  try {
    const info = await stat(noticePath);
    if (!info.isFile()) problems.push("NOTICE exists but is not a file");
  } catch {
    problems.push("NOTICE is missing");
  }
  if (!problems.includes("NOTICE is missing")) {
    const bytes = await readFile(noticePath);
    if (bytes.length === 0) {
      problems.push("NOTICE is empty");
    } else {
      const text = bytes.toString("utf8");
      if (!text.includes("Copyright 2026 Jun Kawasaki")) {
        problems.push("NOTICE does not contain \"Copyright 2026 Jun Kawasaki\"");
      }
      if (!text.includes("kotoba-lang")) {
        problems.push("NOTICE does not contain \"kotoba-lang\"");
      }
      if (text !== UPSTREAM_NOTICE) {
        problems.push("NOTICE is not byte-identical to the upstream 364-byte NOTICE");
      }
      const digest = sha256(bytes);
      if (digest !== UPSTREAM_NOTICE_SHA256) {
        problems.push(`NOTICE sha256 must be ${UPSTREAM_NOTICE_SHA256}, got ${digest}`);
      }
      if (Buffer.byteLength(text, "utf8") !== 364) {
        problems.push(`NOTICE must be 364 bytes, got ${Buffer.byteLength(text, "utf8")}`);
      }
    }
  }
  return problems;
}

async function tempVendorCopy(t) {
  const dir = await mkdtemp(join(tmpdir(), "vendor-notice-"));
  t.after(async () => rm(dir, { recursive: true, force: true }));
  const copy = join(dir, "vendor");
  await cp(VENDOR, copy, { recursive: true });
  return copy;
}

test("PINS.json pins license Apache-2.0 and notice NOTICE", async () => {
  const pins = JSON.parse(await readFile(join(VENDOR, "PINS.json"), "utf8"));
  assert.equal(pins.license, "Apache-2.0", "license is exactly Apache-2.0");
  assert.ok(pins.license, "license is not missing/empty");
  assert.equal(pins.notice, "NOTICE", "notice is exactly NOTICE");
  // The pinned notice name must resolve to an existing file with the
  // upstream sha256.
  const noticeInfo = await stat(join(VENDOR, pins.notice));
  assert.ok(noticeInfo.isFile(), `PINS notice "${pins.notice}" points to a file`);
  assert.equal(
    sha256(await readFile(join(VENDOR, pins.notice))),
    UPSTREAM_NOTICE_SHA256,
    "the file PINS notice points to has the upstream sha256",
  );
});

test("vendor NOTICE exists as a file and matches the upstream text", async () => {
  const info = await stat(join(VENDOR, "NOTICE"));
  assert.ok(info.isFile(), "NOTICE is a file");
  const bytes = await readFile(join(VENDOR, "NOTICE"));
  assert.ok(bytes.length > 0, "NOTICE is not empty");
  assert.equal(bytes.length, 364, "NOTICE is 364 bytes");
  assert.equal(sha256(bytes), UPSTREAM_NOTICE_SHA256, "NOTICE sha256 is the upstream sha256");
  const text = bytes.toString("utf8");
  assert.ok(text.includes("Copyright 2026 Jun Kawasaki"));
  assert.ok(text.includes("kotoba-lang"));
  assert.equal(text, UPSTREAM_NOTICE, "NOTICE is byte-identical to upstream");
  assert.deepEqual(await vendorProblems(VENDOR), [], "repo vendor dir passes");
});

test("negative control: deleting NOTICE would fail the check", async (t) => {
  const copy = await tempVendorCopy(t);
  await rm(join(copy, "NOTICE"));
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("NOTICE is missing")), `missing NOTICE reported: ${problems}`);
});

test("negative control: an empty license would fail the check", async (t) => {
  const copy = await tempVendorCopy(t);
  const pinsPath = join(copy, "PINS.json");
  const pins = JSON.parse(await readFile(pinsPath, "utf8"));
  pins.license = "";
  await writeFile(pinsPath, JSON.stringify(pins, null, 2) + "\n");
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("license")), `empty license reported: ${problems}`);
});

test("negative control: a missing license would fail the check", async (t) => {
  const copy = await tempVendorCopy(t);
  const pinsPath = join(copy, "PINS.json");
  const pins = JSON.parse(await readFile(pinsPath, "utf8"));
  delete pins.license;
  await writeFile(pinsPath, JSON.stringify(pins, null, 2) + "\n");
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("license")), `missing license reported: ${problems}`);
});

test("negative control: an empty NOTICE would fail the check", async (t) => {
  const copy = await tempVendorCopy(t);
  await writeFile(join(copy, "NOTICE"), "");
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("NOTICE is empty")), `empty NOTICE reported: ${problems}`);
});

test("negative control: flipping 1 byte in NOTICE would fail the sha256 check", async (t) => {
  const copy = await tempVendorCopy(t);
  const noticePath = join(copy, "NOTICE");
  const bytes = Buffer.from(await readFile(noticePath));
  bytes[0] ^= 1;
  await writeFile(noticePath, bytes);
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("sha256")), `sha256 mismatch reported: ${problems}`);
});

test("negative control: deleting the PINS notice key would fail the check", async (t) => {
  const copy = await tempVendorCopy(t);
  const pinsPath = join(copy, "PINS.json");
  const pins = JSON.parse(await readFile(pinsPath, "utf8"));
  delete pins.notice;
  await writeFile(pinsPath, JSON.stringify(pins, null, 2) + "\n");
  const problems = await vendorProblems(copy);
  assert.ok(problems.some((p) => p.includes("notice")), `missing notice reported: ${problems}`);
});
