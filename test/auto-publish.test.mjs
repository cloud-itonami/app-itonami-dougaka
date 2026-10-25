import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { publishConfigured, runAutoPublish } from '../bin/dougaka-auto-publish-core.mjs';

const input = { request_id: 'example-1', mode: 'image', prompt: 'A blue lantern', model: 'flux-dev' };
function memoryJournal() {
  const entries = new Map();
  return { entries,
    async read(id) { return entries.get(id) ?? null; },
    async start(id, value) {
      if (entries.has(id)) throw Error('duplicate');
      entries.set(id, structuredClone(value));
    },
    async write(id, value) { entries.set(id, structuredClone(value)); },
  };
}

test('unattended rail submits once, resumes polling, then publishes the verified artifact', async () => {
  const journal = memoryJournal();
  const calls = [];
  let ready = false;
  const call = async (tool, args) => {
    calls.push(tool);
    if (tool === 'dougaka_models') return { configured: true, modalities: {
      image: [{ models: [{ id: 'flux-dev', status: 'production' }] }],
    } };
    if (tool === 'dougaka_generate_image') return { job_id: 'job_1' };
    if (tool === 'dougaka_job_status') return { status: ready ? 'done' : 'running' };
    if (tool === 'dougaka_save_artifact') return { path: '/verified/image.png', sha256: 'a'.repeat(64) };
    throw Error(`unexpected ${tool}`);
  };
  let publications = 0;
  const publish = async () => { publications++; return { cid: 'bafkexample', bytes: 10 }; };
  await assert.rejects(runAutoPublish(input, { call, publish, journal, maxPolls: 1 }), /still running/);
  assert.equal(journal.entries.get(input.request_id).phase, 'submitted');
  ready = true;
  const receipt = await runAutoPublish(input, { call, publish, journal, maxPolls: 1 });
  assert.equal(receipt.url, 'ipfs://bafkexample');
  assert.equal(calls.filter(name => name === 'dougaka_generate_image').length, 1);
  assert.equal(publications, 1);
  assert.deepEqual(await runAutoPublish(input, { call, publish, journal }), receipt);
  assert.equal(publications, 1);
});

test('ambiguous submission is never retried and request id cannot change input', async () => {
  const journal = memoryJournal();
  let submissions = 0;
  await assert.rejects(runAutoPublish(input, { journal, publish: async () => ({}),
    call: async tool => {
      if (tool === 'dougaka_models') return { modalities: { image: [{ models: [{ id: 'flux-dev', status: 'production' }] }] } };
      submissions++; throw Error('network lost after submit');
    } }), /network lost/);
  await assert.rejects(runAutoPublish(input, { journal, publish: async () => ({}),
    call: async () => { submissions++; return {}; } }), /ambiguous/);
  assert.equal(submissions, 1);
  await assert.rejects(runAutoPublish({ ...input, prompt: 'changed' }, { journal,
    publish: async () => ({}), call: async () => ({}) }), /different input/);
});

test('own-node publisher receipt must match the verified artifact', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dougaka-publisher-'));
  const previousScript = process.env.DOUGAKA_IPFS_PUBLISHER;
  const previousNodes = process.env.DOUGAKA_IPFS_NODES;
  try {
    const artifact = join(dir, 'artifact.bin');
    const script = join(dir, 'publisher.py');
    const bytes = Buffer.from('verified artifact');
    const expected = createHash('sha256').update(bytes).digest('hex');
    await writeFile(artifact, bytes);
    await writeFile(script, `import json\nprint(json.dumps({"cid":"bafkreibpanq3fh57ocwc4dicvssxv2ii6umidy6eqfsbwt6omzjod36gzu","sha256":"${expected}","bytes":${bytes.length},"pinned_on":["test-node"],"provide":{"test-node":"provided"}}))\n`);
    process.env.DOUGAKA_IPFS_PUBLISHER = script;
    process.env.DOUGAKA_IPFS_NODES = 'test-node';
    const receipt = await publishConfigured(artifact, expected);
    assert.equal(receipt.bytes, bytes.length);
    await assert.rejects(publishConfigured(artifact, '0'.repeat(64)), /SHA-256 mismatch/);
  } finally {
    if (previousScript === undefined) delete process.env.DOUGAKA_IPFS_PUBLISHER;
    else process.env.DOUGAKA_IPFS_PUBLISHER = previousScript;
    if (previousNodes === undefined) delete process.env.DOUGAKA_IPFS_NODES;
    else process.env.DOUGAKA_IPFS_NODES = previousNodes;
    await rm(dir, { recursive: true, force: true });
  }
});
