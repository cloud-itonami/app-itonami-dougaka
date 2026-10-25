// Unattended, resumable agent rail. This never runs in the browser origin.
import { createHash } from 'node:crypto';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rename, stat, open, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { buildGenerationRequest, modelsFor, modes } from '../site/studio-core.js';

const exec = promisify(execFile);
const requestIdPattern = /^[A-Za-z0-9_-]{1,64}$/;
const cidPattern = /^ba[a-z2-7]{20,120}$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function normalizeSpec(spec) {
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) throw Error('request must be an object');
  const keys = ['request_id', 'mode', 'prompt', 'model', 'seconds', 'aspect', 'seed'];
  for (const key of Object.keys(spec)) if (!keys.includes(key)) throw Error(`unknown argument: ${key}`);
  if (typeof spec.request_id !== 'string' || !requestIdPattern.test(spec.request_id)) throw Error('invalid request_id');
  if (!modes[spec.mode]) throw Error('invalid mode');
  if (typeof spec.prompt !== 'string' || !spec.prompt.trim() || spec.prompt.length > 12000) throw Error('invalid prompt');
  if (typeof spec.model !== 'string' || !spec.model.trim()) throw Error('invalid model');
  const normalized = { request_id: spec.request_id, mode: spec.mode,
    prompt: spec.prompt.trim(), model: spec.model.trim(),
    seconds: spec.seconds ?? 4, aspect: spec.aspect ?? '16:9', seed: spec.seed ?? 0 };
  buildGenerationRequest(normalized);
  return normalized;
}

export async function runAutoPublish(spec, { call, publish, journal, sleep = ms => new Promise(r => setTimeout(r, ms)),
  maxPolls = 120, pollMs = 10000 } = {}) {
  const input = normalizeSpec(spec);
  if (!call || !publish || !journal) throw Error('automation adapters are required');
  const release = journal.acquire ? await journal.acquire(input.request_id) : async () => {};
  try {
  const fingerprint = sha256(JSON.stringify(input));
  let state = await journal.read(input.request_id);
  if (state && state.fingerprint !== fingerprint) throw Error('request_id already belongs to different input');
  if (state?.phase === 'published') return state.receipt;
  if (!state) {
    const catalog = await call('dougaka_models', {});
    if (catalog.configured === false) throw Error('Murakumo generation is not configured');
    const model = modelsFor(catalog, input.mode).find(entry => entry.id === input.model);
    if (!model || !['production', 'verified'].includes(model.status))
      throw Error('model is not verified for this modality');
    // Persist this before spending compute. A lost submit response is ambiguous,
    // so a restart refuses to submit again under the same request id.
    state = { fingerprint, phase: 'submitting' };
    await journal.start(input.request_id, state);
    const submitted = await call(`dougaka_generate_${input.mode}`, {
      prompt: input.prompt, model: input.model, seconds: input.seconds,
      aspect: input.aspect, seed: input.seed,
    });
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(submitted?.job_id ?? ''))
      throw Error('generation response has no safe job id; do not resubmit');
    state = { ...state, phase: 'submitted', job_id: submitted.job_id };
    await journal.write(input.request_id, state);
  } else if (state.phase === 'submitting') {
    throw Error('submission outcome is ambiguous; inspect the service before resuming');
  }
  if (state.phase === 'submitted') {
    let detail;
    for (let n = 0; n < maxPolls; n++) {
      detail = await call('dougaka_job_status', { job_id: state.job_id });
      if (detail.status === 'done') break;
      if (['failed', 'cancelled', 'canceled'].includes(detail.status))
        throw Error(`generation ended: ${detail.status}`);
      if (n + 1 < maxPolls) await sleep(pollMs);
    }
    if (detail?.status !== 'done') throw Error('job is still running; retry with the same request_id');
    const artifact = await call('dougaka_save_artifact', { job_id: state.job_id });
    if (!artifact?.path || !/^[0-9a-f]{64}$/.test(artifact.sha256 ?? ''))
      throw Error('saved artifact has no verified path and digest');
    state = { ...state, phase: 'saved', artifact };
    await journal.write(input.request_id, state);
  }
  if (state.phase === 'saved') {
    const published = await publish(state.artifact.path, state.artifact.sha256);
    const receipt = { request_id: input.request_id, job_id: state.job_id,
      mode: input.mode, model: input.model, sha256: state.artifact.sha256,
      bytes: published.bytes, cid: published.cid, url: `ipfs://${published.cid}` };
    state = { ...state, phase: 'published', receipt };
    await journal.write(input.request_id, state);
    return receipt;
  }
  throw Error(`unsupported journal phase: ${state.phase}`);
  } finally { await release(); }
}

export function fileJournal(directory) {
  const root = resolve(directory);
  const path = id => join(root, `${id}.json`);
  const durableWrite = async (target, value) => {
    const handle = await open(target, 'wx', 0o600);
    try { await handle.writeFile(JSON.stringify(value) + '\n'); await handle.sync(); }
    finally { await handle.close(); }
  };
  return {
    async acquire(id) {
      const lock = join(root, `.${id}.lock`);
      const handle = await open(lock, 'wx', 0o600);
      try { await handle.writeFile(`${process.pid}\n`); }
      catch (error) { await handle.close(); await unlink(lock); throw error; }
      return async () => { await handle.close(); await unlink(lock); };
    },
    async read(id) {
      try { return JSON.parse(await readFile(path(id), 'utf8')); }
      catch (error) { if (error.code === 'ENOENT') return null; throw error; }
    },
    async start(id, value) { await durableWrite(path(id), value); },
    async write(id, value) {
      const temporary = join(root, `.${id}.${process.pid}.tmp`);
      await durableWrite(temporary, value);
      try { await rename(temporary, path(id)); }
      catch (error) { await unlink(temporary).catch(() => {}); throw error; }
    },
  };
}

export async function preflightPublisher(ipfsBin = process.env.IPFS_BIN || 'ipfs') {
  await exec(ipfsBin, ['id'], { timeout: 15000, maxBuffer: 1024 * 1024 });
}

export async function preflightPublication() {
  const script = process.env.DOUGAKA_IPFS_PUBLISHER?.trim();
  if (!script) {
    if (!process.env.IPFS_PATH || !(await stat(resolve(process.env.IPFS_PATH))).isDirectory())
      throw Error('IPFS_PATH must name an existing Kubo repository');
    await preflightPublisher();
    return;
  }
  if (!(await stat(resolve(script))).isFile()) throw Error('DOUGAKA_IPFS_PUBLISHER must name a file');
  if (!/^[a-z][a-z0-9-]*(?:,[a-z][a-z0-9-]*)*$/.test(process.env.DOUGAKA_IPFS_NODES ?? ''))
    throw Error('DOUGAKA_IPFS_NODES must list configured own-node names');
}

export async function publishConfigured(path, expected) {
  const script = process.env.DOUGAKA_IPFS_PUBLISHER?.trim();
  if (!script) return publishArtifact(path, expected);
  const file = resolve(path);
  const info = await stat(file);
  if (!info.isFile() || info.size > 100 * 1024 * 1024) throw Error('artifact must be a file of at most 100 MiB');
  if (sha256(await readFile(file)) !== expected) throw Error('saved artifact SHA-256 mismatch');
  const { stdout } = await exec('python3', [resolve(script), '--document', file,
    '--nodes', process.env.DOUGAKA_IPFS_NODES], { timeout: 600000, maxBuffer: 1024 * 1024 });
  const receipt = JSON.parse(stdout);
  const selectedNodes = process.env.DOUGAKA_IPFS_NODES.split(',');
  if (!cidPattern.test(receipt.cid ?? '') || receipt.sha256 !== expected || receipt.bytes !== info.size
      || !Array.isArray(receipt.pinned_on)
      || JSON.stringify(receipt.pinned_on) !== JSON.stringify(selectedNodes)
      || selectedNodes.some(node => receipt.provide?.[node] !== 'provided'))
    throw Error('own-node publisher receipt does not match the artifact');
  return { cid: receipt.cid, bytes: receipt.bytes };
}

export async function publishArtifact(path, expected, { ipfsBin = process.env.IPFS_BIN || 'ipfs' } = {}) {
  const file = resolve(path);
  const info = await stat(file);
  if (!info.isFile() || info.size > 100 * 1024 * 1024) throw Error('artifact must be a file of at most 100 MiB');
  if (sha256(await readFile(file)) !== expected) throw Error('saved artifact SHA-256 mismatch');
  const invoke = async (...args) => (await exec(ipfsBin, args, { timeout: 180000, maxBuffer: 1024 * 1024 })).stdout.trim();
  await preflightPublisher(ipfsBin);
  const cid = await invoke('add', '--cid-version=1', '--raw-leaves', '--chunker=size-1048576',
    '--pin=false', '--quiet', file);
  if (!cidPattern.test(cid)) throw Error('Kubo returned an unexpected CID');
  await invoke('pin', 'add', '--recursive', cid);
  const digest = createHash('sha256');
  await new Promise((ok, fail) => {
    const child = spawn(ipfsBin, ['cat', `/ipfs/${cid}`], {
      stdio: ['ignore', 'pipe', 'pipe'], signal: AbortSignal.timeout(120000),
    });
    let stderr = '';
    child.stdout.on('data', chunk => digest.update(chunk));
    child.stderr.on('data', chunk => { stderr += chunk.toString().slice(0, 300); });
    child.on('error', fail);
    child.on('close', code => code === 0 ? ok() : fail(Error(`Kubo readback failed: ${stderr}`)));
  });
  if (digest.digest('hex') !== expected) throw Error('Kubo readback SHA-256 mismatch');
  await invoke('routing', 'provide', '--recursive', cid);
  return { cid, bytes: info.size };
}
