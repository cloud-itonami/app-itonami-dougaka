import test from 'node:test';
import assert from 'node:assert/strict';
import { artifactDigest, buildGenerationRequest, jobIdFrom, modelsFor } from '../site/studio-core.js';
import { generationOrigin, runtimeDescriptor, requestedCapabilities } from '../site/studio-contract.js';

test('Murakumo catalog determines selectable modes without a bundled model guess', () => {
  assert.deepEqual(modelsFor(null, 'video'), []);
  const catalog = { configured: true, modalities: { video: [{ models: [
    { id: 'wan2.2-ti2v-5b', status: 'verified' }, { id: 'broken-model', status: 'broken' },
  ] }] } };
  assert.deepEqual(modelsFor(catalog, 'video'), [
    { id: 'wan2.2-ti2v-5b', status: 'verified' }, { id: 'broken-model', status: 'broken' },
  ]);
  assert.deepEqual(modelsFor({ ...catalog, configured: false }, 'video'), []);
  const { configured: _ignored, ...oldCatalog } = catalog;
  assert.equal(modelsFor(oldCatalog, 'video').length, 2);
});

test('generation requests have Murakumo modality shapes', () => {
  const base = { prompt: '夕方の海岸を走る', model: 'wan2.2-ti2v-5b', seconds: 4, aspect: '9:16', seed: 7 };
  assert.deepEqual(buildGenerationRequest({ ...base, mode: 'video' }), {
    type: 'video', model: base.model, input: { prompt: base.prompt },
    params: { frames: 97, seed: 7 },
  });
  assert.deepEqual(buildGenerationRequest({ ...base, mode: 'voice' }), {
    type: 'voice', model: base.model, input: { text: base.prompt }, params: { locale: 'ja-JP' },
  });
  assert.equal(buildGenerationRequest({ ...base, mode: 'music' }).params.sound_kind, 'music');
  assert.equal(buildGenerationRequest({ ...base, mode: 'sfx' }).params.sound_kind, 'sfx');
  assert.equal(buildGenerationRequest({ ...base, mode: 'image' }).params.width, 736);
  assert.throws(() => buildGenerationRequest({ ...base, mode: 'video', model: '../x' }), /モデル/);
  assert.throws(() => buildGenerationRequest({ ...base, mode: 'video', prompt: '' }), /プロンプト/);
  assert.throws(() => buildGenerationRequest({ ...base, mode: 'video', seconds: 6 }), /5 秒/);
});

test('job and artifact identities fail closed', () => {
  assert.equal(jobIdFrom({ jobId: 'job_123' }), 'job_123');
  assert.equal(jobIdFrom({ jobId: '../admin' }), null);
  assert.equal(artifactDigest({ artifacts: [{ contentHash: `sha256:${'a'.repeat(64)}` }] }), 'a'.repeat(64));
  assert.equal(artifactDigest({ artifacts: [{ contentHash: 'md5:123' }] }), null);
});

test('all external operations are bounded to the independent generation origin', () => {
  assert.deepEqual(requestedCapabilities, ['media/generation']);
  assert.deepEqual(runtimeDescriptor.operations.map(x => [x.operation, x.endpoint.method, x.endpoint.path]), [
    ['catalog', 'GET', '/api/v1/generation/catalog'],
    ['submit', 'POST', '/api/v1/generation'],
    ['status', 'GET', '/api/v1/generation/jobs/:jobId'],
    ['cancel', 'DELETE', '/api/v1/generation/jobs/:jobId'],
    ['artifact', 'GET', '/api/v1/generation/jobs/:jobId/artifact'],
  ]);
  assert.ok(runtimeDescriptor.operations.every(x => x.endpoint.origin === generationOrigin && x.consent === 'each-operation'));
});
