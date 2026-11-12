import test from 'node:test';
import assert from 'node:assert/strict';
import { DOMAINS, LOCAL_KEY_PREFIX, LOCAL_VALUE_MAX_CHARS, createLocalStore, validateLocal } from '../site/studio-local.js';

const memStorage = () => {
  const map = new Map();
  return {
    getItem: key => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: key => { map.delete(key); },
  };
};

test('local domains validate their Higgsfield management shapes', () => {
  assert.deepEqual(validateLocal('project', { name: '秋の短編' }), []);
  assert.deepEqual(validateLocal('brand-kit', { name: 'GFTD', colors: ['#112233'] }), []);
  assert.deepEqual(validateLocal('canvas', { nodes: [{ id: 'a', kind: 'image' }], edges: [] }), []);
  assert.deepEqual(validateLocal('marketing', { title: '予告', targets: ['web'] }), []);
  assert.ok(validateLocal('project', {}).some(e => e.startsWith('missing-name')));
  assert.ok(validateLocal('project', { name: '' }).length > 0);
  assert.ok(validateLocal('brand-kit', { name: 'x', colors: ['red'] }).some(e => e.startsWith('bad-color')));
  assert.ok(validateLocal('canvas', { nodes: [{ id: 'a', kind: 'image' }], edges: [{ from: 'a', to: 'ghost' }] })
    .some(e => e.startsWith('dangling-edge')));
  assert.ok(validateLocal('canvas', { nodes: 'nope', edges: [] }).some(e => e.startsWith('bad-nodes')));
  assert.ok(validateLocal('marketing', { copy: 'x'.repeat(4001) }).some(e => e.startsWith('copy-too-long')));
  assert.deepEqual(validateLocal('no-such-domain', {}), ['unknown-domain: no-such-domain']);
});

test('local store round-trips per domain under the descriptor schema prefix', () => {
  const store = createLocalStore(memStorage());
  for (const domain of DOMAINS) assert.equal(store.read(domain).ok, true);
  assert.deepEqual(store.read('project'), { ok: true, value: null });
  const value = { name: '秋の短編', settings: { aspect: '9:16' } };
  assert.deepEqual(store.write('project', value), { ok: true });
  assert.deepEqual(store.read('project'), { ok: true, value });
  assert.ok(!store.write('project', { name: '' }).ok);
  assert.deepEqual(store.read('project'), { ok: true, value });
  assert.deepEqual(store.write('no-such-domain', {}), { ok: false, errors: ['unknown-domain: no-such-domain'] });
});

test('local store fails closed on corrupt, oversized, or unavailable storage', () => {
  const storage = memStorage();
  const store = createLocalStore(storage);
  storage.setItem(`${LOCAL_KEY_PREFIX}canvas`, '{broken json');
  assert.deepEqual(store.read('canvas'), { ok: false, errors: ['corrupt-state: canvas'] });
  const big = { name: 'big', settings: { blob: 'x'.repeat(LOCAL_VALUE_MAX_CHARS) } };
  assert.ok(!store.write('project', big).ok);
  const failing = createLocalStore({ getItem: () => { throw new Error('denied'); },
    setItem: () => { throw new Error('denied'); }, removeItem: () => {} });
  assert.deepEqual(failing.read('project'), { ok: false, errors: ['storage-unavailable'] });
  assert.deepEqual(failing.write('project', { name: 'x' }), { ok: false, errors: ['storage-unavailable'] });
});
