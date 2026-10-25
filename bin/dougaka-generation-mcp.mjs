#!/usr/bin/env node
// Separate effectful MCP rail. Browser consent does not authorize these calls.
// Operators provide a generation-scope token to this process at launch.
import { createHash } from 'node:crypto';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { buildGenerationRequest, jobIdFrom, modelsFor, modes, artifactDigest } from '../site/studio-core.js';
import { fileJournal, preflightPublication, publishConfigured, runAutoPublish } from './dougaka-auto-publish-core.mjs';

const origin = 'https://generation.murakumo.cloud';
const token = process.env.MURAKUMO_GENERATION_TOKEN?.trim() ?? '';
const outputDir = process.env.DOUGAKA_OUTPUT_DIR?.trim() ?? '';
const text = value => ({ type: 'text', text: JSON.stringify(value) });
const result = value => ({ content: [text(value)], structuredContent: value, isError: false });
const fail = message => ({ content: [text({ error: message })], isError: true });
const idPattern = /^[A-Za-z0-9_-]{1,128}$/;
const string = (description, extra = {}) => ({ type: 'string', description, ...extra });

const tools = [
  { name: 'dougaka_models', description: 'Read the public Murakumo generation catalog and execution availability.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
  ...Object.entries(modes).map(([mode, info]) => ({
    name: `dougaka_generate_${mode}`,
    description: `Submit one ${info.label} generation job to Murakumo. Requires MURAKUMO_GENERATION_TOKEN and a self-hosted production or verified model. This consumes remote compute.`,
    inputSchema: { type: 'object', properties: {
      prompt: string(mode === 'voice' ? 'Text to speak.' : 'Generation prompt.', { maxLength: 12000 }),
      model: string('Model id listed by dougaka_models.'),
      seconds: { type: 'number', minimum: 1, maximum: mode === 'video' ? 5 : 120 },
      aspect: string('Image layout.', { enum: ['16:9', '9:16', '1:1'] }),
      seed: { type: 'integer', minimum: 0, maximum: 2147483647 },
    }, required: ['prompt', 'model'], additionalProperties: false },
  })),
  { name: 'dougaka_job_status', description: 'Read a Murakumo generation job by id.',
    inputSchema: { type: 'object', properties: { job_id: string('Opaque job id.') }, required: ['job_id'], additionalProperties: false } },
  { name: 'dougaka_cancel_job', description: 'Cancel a Murakumo generation job. This is an external state change.',
    inputSchema: { type: 'object', properties: { job_id: string('Opaque job id.') }, required: ['job_id'], additionalProperties: false } },
  { name: 'dougaka_save_artifact', description: 'Download a finished artifact, verify SHA-256, and write it to DOUGAKA_OUTPUT_DIR. Requires an existing writable output directory.',
    inputSchema: { type: 'object', properties: { job_id: string('Opaque job id.') }, required: ['job_id'], additionalProperties: false } },
  { name: 'dougaka_generate_and_publish', description: 'Unattended generation, verified download, local Kubo pin, readback and public IPFS provider announcement. Requires a scoped token and existing output, journal and Kubo directories. A request_id is never resubmitted after an ambiguous outcome. This does not change DNS or IPNS.',
    inputSchema: { type: 'object', properties: {
      request_id: string('Unique idempotency journal key; reuse it to resume the same request.'),
      mode: string('Generation mode.', { enum: Object.keys(modes) }),
      prompt: string('Prompt or speech text.', { maxLength: 12000 }),
      model: string('Self-hosted production or verified model id.'),
      seconds: { type: 'number', minimum: 1, maximum: 120 },
      aspect: string('Image layout.', { enum: ['16:9', '9:16', '1:1'] }),
      seed: { type: 'integer', minimum: 0, maximum: 2147483647 },
    }, required: ['request_id', 'mode', 'prompt', 'model'], additionalProperties: false },
  },
];

function exactArgs(args, allowed, required = []) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw Error('arguments must be an object');
  for (const key of Object.keys(args)) if (!allowed.includes(key)) throw Error(`unknown argument: ${key}`);
  for (const key of required) if (typeof args[key] !== 'string' || !args[key].trim()) throw Error(`missing or invalid: ${key}`);
}

function jobId(value) {
  if (typeof value !== 'string' || !idPattern.test(value)) throw Error('invalid job_id');
  return value;
}

function requireToken() {
  if (!token) throw Error('MURAKUMO_GENERATION_TOKEN is not configured');
}

async function request(path, method = 'GET', body = undefined, binary = false) {
  const headers = { accept: binary ? '*/*' : 'application/json' };
  if (path !== '/api/v1/generation/catalog') {
    requireToken();
    headers.authorization = `Bearer ${token}`;
  }
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(new URL(path, origin), {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual', signal: AbortSignal.timeout(binary ? 120000 : 30000),
  });
  if (response.status >= 300 && response.status < 400) throw Error(`unexpected redirect: HTTP ${response.status}`);
  if (!response.ok) throw Error(`Murakumo returned HTTP ${response.status}`);
  if (binary) return response;
  if (response.status === 204) return {};
  return response.json();
}

async function call(name, args) {
  if (name === 'dougaka_generate_and_publish') {
    requireToken();
    for (const key of ['DOUGAKA_OUTPUT_DIR', 'DOUGAKA_RUN_DIR']) {
      const value = process.env[key];
      if (!value || !(await stat(resolve(value))).isDirectory()) throw Error(`${key} must name an existing directory`);
    }
    await preflightPublication();
    const subcall = async (tool, parameters) => {
      const response = await call(tool, parameters);
      if (response.isError) throw Error(response.structuredContent?.error
        ?? response.content?.[0]?.text ?? 'MCP call failed');
      return response.structuredContent;
    };
    const journal = fileJournal(process.env.DOUGAKA_RUN_DIR);
    try {
      return result(await runAutoPublish(args, { call: subcall, publish: publishConfigured,
        journal, maxPolls: 4, pollMs: 5000 }));
    } catch (error) {
      if (error.message !== 'job is still running; retry with the same request_id') throw error;
      const saved = await journal.read(args.request_id);
      return result({ status: 'pending', request_id: args.request_id,
        job_id: saved.job_id, retry_after_ms: 5000 });
    }
  }
  if (name === 'dougaka_models') {
    exactArgs(args, []);
    const catalog = await request('/api/v1/generation/catalog');
    return result({ configured: catalog.configured ?? null, execute: catalog.execute ?? 'unknown', modalities: catalog.modalities });
  }
  if (name.startsWith('dougaka_generate_')) {
    const mode = name.slice('dougaka_generate_'.length);
    if (!modes[mode]) throw Error('unknown tool');
    exactArgs(args, ['prompt', 'model', 'seconds', 'aspect', 'seed'], ['prompt', 'model']);
    requireToken();
    const catalog = await request('/api/v1/generation/catalog');
    if (catalog.configured === false) throw Error('Murakumo generation is not configured');
    const model = modelsFor(catalog, mode).find(entry => entry.id === args.model);
    if (!model || !['production', 'verified'].includes(model.status)) throw Error('model is not verified for this modality');
    const body = buildGenerationRequest({ mode, prompt: args.prompt, model: args.model,
      seconds: args.seconds ?? 4, aspect: args.aspect ?? '16:9', seed: args.seed ?? 0 });
    const submitted = await request('/api/v1/generation', 'POST', body);
    const id = jobIdFrom(submitted);
    if (!id) throw Error('response has no safe job id; do not retry without checking Murakumo history');
    return result({ job_id: id, status: submitted.status ?? 'submitted', mode, model: args.model });
  }
  if (['dougaka_job_status', 'dougaka_cancel_job', 'dougaka_save_artifact'].includes(name)) {
    exactArgs(args, ['job_id'], ['job_id']);
    const id = jobId(args.job_id);
    const path = `/api/v1/generation/jobs/${encodeURIComponent(id)}`;
    if (name === 'dougaka_job_status') {
      const status = await request(path);
      return result({ job_id: id, status: status.status ?? 'unknown', artifacts: status.artifacts ?? [], error: status.error ?? null });
    }
    if (name === 'dougaka_cancel_job') {
      await request(path, 'DELETE');
      return result({ job_id: id, cancel_requested: true });
    }
    if (!outputDir) throw Error('DOUGAKA_OUTPUT_DIR is not configured');
    const status = await request(path);
    if (status.status !== 'done') throw Error('job is not done');
    const expected = artifactDigest(status);
    if (!expected) throw Error('artifact has no SHA-256');
    const response = await request(`${path}/artifact`, 'GET', undefined, true);
    const declaredLength = Number(response.headers.get('content-length') ?? 0);
    if (declaredLength > 100 * 1024 * 1024) throw Error('artifact exceeds 100 MiB limit');
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length > 100 * 1024 * 1024) throw Error('artifact exceeds 100 MiB limit');
    const actual = createHash('sha256').update(bytes).digest('hex');
    if (actual !== expected) throw Error('artifact SHA-256 mismatch');
    const type = response.headers.get('content-type')?.split(';')[0]?.trim() ?? '';
    const ext = { 'image/png': 'png', 'image/jpeg': 'jpg', 'video/mp4': 'mp4', 'audio/wav': 'wav', 'audio/flac': 'flac', 'audio/mpeg': 'mp3' }[type];
    if (!ext) throw Error('unsupported artifact content type');
    const target = join(resolve(outputDir), `${id}.${ext}`);
    // Exclusive create protects a previously reviewed output from replacement.
    let already_exists = false;
    try { await writeFile(target, bytes, { flag: 'wx', mode: 0o600 }); }
    catch (error) {
      if (error.code !== 'EEXIST') throw error;
      if (createHash('sha256').update(await readFile(target)).digest('hex') !== expected)
        throw Error('existing artifact SHA-256 mismatch');
      already_exists = true;
    }
    return result({ job_id: id, path: target, bytes: bytes.length, sha256: actual,
      content_type: type, already_exists });
  }
  throw Error('unknown tool');
}

function rpc(id, value) { return { jsonrpc: '2.0', id, result: value }; }
function rpcError(id, code, message) { return { jsonrpc: '2.0', id, error: { code, message } }; }

export async function handle(req) {
  if (!req || typeof req !== 'object' || Array.isArray(req)) return rpcError(null, -32600, 'invalid request');
  const id = req.id;
  if (!Object.hasOwn(req, 'id') || String(req.method ?? '').startsWith('notifications/')) return null;
  if (req.method === 'initialize') return rpc(id, { protocolVersion: req.params?.protocolVersion ?? '2024-11-05',
    capabilities: { tools: {} }, serverInfo: { name: 'dougaka-generation', version: '0.1.0' },
    instructions: 'Effectful Murakumo generation tools. Each call is separate from browser consent.' });
  if (req.method === 'ping') return rpc(id, {});
  if (req.method === 'tools/list') return rpc(id, { tools });
  if (req.method === 'tools/call') {
    try { return rpc(id, await call(req.params?.name, req.params?.arguments ?? {})); }
    catch (error) { return rpc(id, fail(error instanceof Error ? error.message : 'operation failed')); }
  }
  return rpcError(id, -32601, 'method not found');
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of lines) {
    let output;
    try { output = await handle(JSON.parse(line)); }
    catch { output = rpcError(null, -32700, 'parse error'); }
    if (output) process.stdout.write(`${JSON.stringify(output)}\n`);
  }
}
