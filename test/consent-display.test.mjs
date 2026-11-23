// Consent display regression tests (device-observed defects 2026-11-23):
//  1. browser-action (local) consents must not show the inert
//     https://local.itonami.cloud origin as a network "Endpoint:" — the
//     no-send line takes its place (逆向き固定: endpoint present => fail).
//  2. Real network consents keep their endpoint line unchanged.
//  3. The modal open must not fire synchronously inside the click dispatch
//     (main/UI thread stall / Android WebView ANR), and the whole
//     save -> consent -> deny path must complete under a 200 ms dialog cap
//     without any localStorage write before an explicit Allow.
//
// The dialog/broker under test are the real vendored runtime
// (app/vendor/kotoba-client-runtime.js, byte-identical to dist); only the
// DOM is faked. The vendored file itself is never modified.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createClientConsentHost,
  createClientOperationBroker,
} from '../app/vendor/kotoba-client-runtime.js';
import {
  generationOrigin, hostSupportedOperations, localOrigin, localSelector,
  localService, requestedCapabilities, runtimeDescriptor, selector,
} from '../site/studio-contract.js';
import { createLocalStore } from '../site/studio-local.js';
import {
  NO_SEND_LINE, installConsentDisplayFix, rewriteLocalConsentScopeText,
} from '../site/studio-consent.js';

// --- minimal fake DOM for the vendored consent host -------------------------
class MouseEvent {}
class FakeElement {
  constructor(tag, doc) {
    this.tagName = tag.toUpperCase(); this.ownerDocument = doc;
    this.children = []; this.className = ''; this.textContent = '';
    this.type = ''; this.open = false; this.isConnected = false;
    this.focused = false; this._listeners = new Map();
  }
  append(...nodes) { for (const n of nodes) { this.children.push(n); n.isConnected = true; } }
  setAttribute(k, v) { (this._attrs ??= new Map()).set(k, String(v)); }
  addEventListener(t, fn) { this._listeners.set(t, [...(this._listeners.get(t) ?? []), fn]); }
  dispatch(t, ev = {}) { for (const fn of this._listeners.get(t) ?? []) fn(ev); }
  querySelector(sel) {
    const walk = (el) => {
      for (const c of el.children) {
        if (sel.startsWith('.') ? c.className === sel.slice(1) : c.tagName === sel.toUpperCase()) return c;
        const r = walk(c); if (r) return r;
      }
      return null;
    };
    return walk(this);
  }
  showModal() { this.open = true; }
  close() { if (this.open) { this.open = false; this.dispatch('close'); } }
  focus() { this.focused = true; }
  remove() { this.isConnected = false; }
}
class FakeDocument {
  constructor() {
    this.defaultView = { MouseEvent };
    this.body = new FakeElement('body', this); this.body.isConnected = true;
  }
  createElement(tag) { return new FakeElement(tag, this); }
  querySelector(sel) { return this.body.querySelector(sel); }
}

const tick = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitForDialog(dialog, capMs = 200) {
  const t0 = performance.now();
  while (!dialog.open) {
    assert.ok(performance.now() - t0 < capMs, `dialog did not open within ${capMs} ms`);
    await tick(1);
  }
  return performance.now() - t0;
}

function setup() {
  const doc = new FakeDocument();
  globalThis.document = doc;
  const consentHost = createClientConsentHost();
  const dialog = doc.body.children.find((e) => e.tagName === 'DIALOG');
  assert.ok(dialog, 'consent dialog was created');
  installConsentDisplayFix({ dialog, localOrigin });
  const scope = dialog.querySelector('.kotoba-client-consent-scope');
  const buttons = dialog.children.filter((e) => e.tagName === 'BUTTON');
  const [deny, allow] = buttons;
  const data = new Map();
  const countingStorage = {
    writes: 0,
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => { countingStorage.writes += 1; data.set(k, String(v)); },
    removeItem: (k) => { data.delete(k); },
  };
  const localStore = createLocalStore(countingStorage);
  const localOp = (domain) => async ({ payload }) => {
    if (payload?.mode === 'get') return localStore.read(domain);
    if (payload?.mode === 'set') return localStore.write(domain, payload?.value);
    return { ok: false, errors: ['unknown-mode'] };
  };
  const broker = createClientOperationBroker({
    runtime: runtimeDescriptor, requestedCapabilities,
    approvedOperations: runtimeDescriptor.operations, hostSupportedOperations,
    consentHost,
    browserAdapters: { [localService]: { project: localOp('project') } },
  });
  return { doc, dialog, scope, deny, allow, broker, countingStorage };
}

test('rewriteLocalConsentScopeText swaps the inert endpoint for a no-send line', () => {
  const local = [
    'Service: dougaka-local', 'Operation: project', 'Capability: media/project',
    `Endpoint: ${localOrigin}`, 'Action: project',
    'Data categories: project-name, project-settings', 'Delivery redirects: (none)',
  ].join('\n');
  const rewritten = rewriteLocalConsentScopeText(local, localOrigin);
  assert.ok(!rewritten.includes(localOrigin), `endpoint leaked: ${rewritten}`);
  assert.ok(!rewritten.includes('Endpoint:'), 'Endpoint line must be gone for browser-action');
  assert.ok(rewritten.includes(NO_SEND_LINE), `no-send line missing: ${rewritten}`);
  assert.ok(rewritten.includes('Service: dougaka-local'));
  assert.ok(rewritten.includes('Action: project'));

  const network = [
    'Service: murakumo-generation', 'Operation: catalog', 'Capability: media/generation',
    `Endpoint: ${generationOrigin}`, 'Action: GET /api/v1/generation/catalog',
    'Data categories: (none)', 'Delivery redirects: (none)',
  ].join('\n');
  assert.equal(rewriteLocalConsentScopeText(network, localOrigin), network,
    'network scope text must stay byte-identical');
});

test('browser-action consent shows no endpoint and an explicit no-send line', async () => {
  const { dialog, scope, deny, broker, countingStorage } = setup();
  const payload = JSON.parse('{"mode":"set","value":{"name":"秋の短編"}}');
  const started = broker.invoke(localSelector('project'), payload);
  // The modal must not open synchronously inside the click dispatch.
  assert.equal(dialog.open, false, 'dialog opened synchronously during input dispatch');
  const openedInMs = await waitForDialog(dialog, 200);
  assert.ok(openedInMs < 200, `dialog opened too slowly: ${openedInMs.toFixed(1)} ms`);
  const shown = scope.textContent;
  // 逆向き固定: if the endpoint leaks back in, this fails.
  assert.ok(!shown.includes(localOrigin) && !shown.includes('local.itonami.cloud'),
    `endpoint present in browser-action consent: ${shown}`);
  assert.ok(!shown.includes('Endpoint:'), `Endpoint line present: ${shown}`);
  assert.ok(shown.includes(NO_SEND_LINE), `no-send line missing: ${shown}`);
  assert.equal(countingStorage.writes, 0, 'no localStorage write before an explicit Allow');
  deny.dispatch('click');
  await assert.rejects(started, /client operation was not approved/);
  assert.equal(countingStorage.writes, 0, 'Deny must not write anything');
});

test('save -> consent -> Allow writes locally only, after the modal opens', async () => {
  const { dialog, allow, broker, countingStorage } = setup();
  const payload = JSON.parse('{"mode":"set","value":{"name":"秋の短編"}}');
  const started = broker.invoke(localSelector('project'), payload);
  assert.equal(dialog.open, false, 'dialog opened synchronously during input dispatch');
  await waitForDialog(dialog, 200);
  const event = new MouseEvent();
  Object.assign(event, { isTrusted: true, currentTarget: allow });
  allow.dispatch('click', event);
  const result = await started;
  assert.deepEqual(result, { ok: true });
  assert.equal(countingStorage.writes, 1, 'Allow must perform exactly the local write');
});

test('network consent keeps its endpoint line unchanged', async () => {
  const { dialog, scope, deny, broker } = setup();
  const started = broker.invoke(selector('catalog'), {
    input: new Request(`${generationOrigin}/api/v1/generation/catalog`),
  });
  await waitForDialog(dialog, 200);
  const shown = scope.textContent;
  assert.ok(shown.includes(`Endpoint: ${generationOrigin}`), `endpoint missing: ${shown}`);
  assert.ok(!shown.includes(NO_SEND_LINE), 'network consent must not claim no-send');
  deny.dispatch('click');
  await assert.rejects(started, /client operation was not approved/);
});
