// Pure browser-local Higgsfield management state: project, brand-kit, canvas
// and marketing shapes held as caller JSON. No network and no Murakumo contact:
// values live in the descriptor-declared localstorage adapter under schema
// itonami.dougaka.v1. Storage is injected so node tests run without a browser.
export const LOCAL_SCHEMA = 'itonami.dougaka.v1';
export const LOCAL_KEY_PREFIX = `${LOCAL_SCHEMA}/local/`;
export const LOCAL_VALUE_MAX_CHARS = 262144;
export const DOMAINS = Object.freeze(['project', 'brand-kit', 'canvas', 'marketing']);

const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const isName = (value, max) => typeof value === 'string' && value.length >= 1 && value.length <= max;
const isId = value => typeof value === 'string' && /^[A-Za-z0-9._-]{1,64}$/.test(value);
const isHexColor = value => typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value);
const isSha256 = value => typeof value === 'string' && /^[0-9a-fA-F]{64}$/.test(value);

function checkSettings(settings) {
  if (settings === undefined) return [];
  if (!isRecord(settings)) return ['bad-settings'];
  if (Object.keys(settings).length > 16) return ['settings-too-large'];
  return Object.keys(settings).every(key => isId(key)) ? [] : ['bad-setting-key'];
}

function validateProject(value) {
  if (!isRecord(value)) return ['bad-project'];
  if (!isName(value.name, 120)) return ['missing-name: project.name is 1..120 chars'];
  const errors = checkSettings(value.settings);
  if (value.jobs !== undefined) {
    if (!Array.isArray(value.jobs) || value.jobs.length > 128
      || !value.jobs.every(job => typeof job === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(job))) {
      errors.push('bad-jobs: up to 128 job ids');
    }
  }
  return errors;
}

function validateBrandKit(value) {
  if (!isRecord(value)) return ['bad-brand-kit'];
  if (!isName(value.name, 120)) return ['missing-name: brand-kit.name is 1..120 chars'];
  const errors = [];
  for (const [field, check, reason] of [
    ['colors', list => Array.isArray(list) && list.length <= 32 && list.every(isHexColor), 'bad-color: #rrggbb up to 32'],
    ['fonts', list => Array.isArray(list) && list.length <= 32 && list.every(font => isName(font, 120)), 'bad-font'],
  ]) {
    if (value[field] !== undefined && !check(value[field])) errors.push(reason);
  }
  if (value.assets !== undefined) {
    if (!Array.isArray(value.assets) || value.assets.length > 128
      || !value.assets.every(asset => isRecord(asset) && isName(asset.label, 120)
        && (asset.hash === undefined || isSha256(asset.hash)))) {
      errors.push('bad-assets: up to 128 {label, hash?}');
    }
  }
  return errors;
}

function validateCanvas(value) {
  if (!isRecord(value)) return ['bad-canvas'];
  const errors = [];
  if (!Array.isArray(value.nodes) || value.nodes.length > 256
    || !value.nodes.every(node => isRecord(node) && isId(node.id) && isName(node.kind, 64)
      && (node.label === undefined || isName(node.label, 120)))) {
    errors.push('bad-nodes: up to 256 {id, kind, label?}');
  }
  const ids = new Set(Array.isArray(value.nodes) ? value.nodes.filter(isRecord).map(node => node.id) : []);
  if (ids.size !== (Array.isArray(value.nodes) ? value.nodes.length : -1)) errors.push('duplicate-node-id');
  if (!Array.isArray(value.edges) || value.edges.length > 512
    || !value.edges.every(edge => isRecord(edge) && typeof edge.from === 'string' && typeof edge.to === 'string')) {
    errors.push('bad-edges: up to 512 {from, to}');
  } else {
    for (const edge of value.edges) {
      if (!ids.has(edge.from) || !ids.has(edge.to)) { errors.push(`dangling-edge: ${edge.from} -> ${edge.to}`); break; }
    }
  }
  return errors;
}

function validateMarketing(value) {
  if (!isRecord(value)) return ['bad-marketing'];
  const errors = [];
  if (value.title !== undefined && !isName(value.title, 120)) errors.push('bad-title');
  if (value.copy !== undefined && (typeof value.copy !== 'string' || value.copy.length > 4000)) {
    errors.push('copy-too-long: 4000 chars');
  }
  if (value.targets !== undefined && (!Array.isArray(value.targets) || value.targets.length > 32
    || !value.targets.every(target => isName(target, 120)))) {
    errors.push('bad-targets: up to 32 names');
  }
  return errors;
}

const VALIDATORS = Object.freeze({
  project: validateProject,
  'brand-kit': validateBrandKit,
  canvas: validateCanvas,
  marketing: validateMarketing,
});

export function validateLocal(domain, value) {
  const validate = VALIDATORS[domain];
  if (!validate) return [`unknown-domain: ${domain}`];
  return validate(value);
}

export function createLocalStore(storage) {
  const key = domain => `${LOCAL_KEY_PREFIX}${domain}`;
  const read = (domain) => {
    if (!VALIDATORS[domain]) return { ok: false, errors: [`unknown-domain: ${domain}`] };
    let raw;
    try { raw = storage.getItem(key(domain)); } catch { return { ok: false, errors: ['storage-unavailable'] }; }
    if (raw == null) return { ok: true, value: null };
    let value;
    try { value = JSON.parse(raw); } catch { return { ok: false, errors: [`corrupt-state: ${domain}`] }; }
    const errors = validateLocal(domain, value);
    return errors.length ? { ok: false, errors } : { ok: true, value };
  };
  const write = (domain, value) => {
    if (!VALIDATORS[domain]) return { ok: false, errors: [`unknown-domain: ${domain}`] };
    const errors = validateLocal(domain, value);
    if (errors.length) return { ok: false, errors };
    let raw;
    try { raw = JSON.stringify(value); } catch { return { ok: false, errors: [`unserializable: ${domain}`] }; }
    if (raw.length > LOCAL_VALUE_MAX_CHARS) return { ok: false, errors: [`state-too-large: ${domain}`] };
    try { storage.setItem(key(domain), raw); } catch { return { ok: false, errors: ['storage-unavailable'] }; }
    return { ok: true };
  };
  return Object.freeze({ read, write });
}
