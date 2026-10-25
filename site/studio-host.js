import {
  clientRuntimeConnectSrc, clientRuntimeProblems, createClientConsentHost,
  createClientOperationBroker,
} from './kotoba-client-runtime.js';
import { generationOrigin, hostSupportedOperations, requestedCapabilities,
  runtimeDescriptor, selector } from './studio-contract.js';
import { artifactDigest, buildGenerationRequest, jobIdFrom, modelsFor, modes } from './studio-core.js';

const root = document.getElementById('dougaka-studio');
if (!root) throw new Error('Dougaka Studio mount is missing');
const problems = clientRuntimeProblems(runtimeDescriptor, requestedCapabilities);
if (problems.length) throw new Error(`Dougaka runtime declaration invalid: ${JSON.stringify(problems)}`);
if (clientRuntimeConnectSrc(runtimeDescriptor, requestedCapabilities)
    !== `connect-src ${generationOrigin}`) throw new Error('Dougaka CSP does not match declared services');

// The static verified page owns the host policy. Passing the declaration as an
// eligibility ceiling never grants consent: the canonical modal asks again for
// every catalog, submission, status, cancellation and artifact request.
const consentHost = createClientConsentHost();
const broker = createClientOperationBroker({
  runtime: runtimeDescriptor, requestedCapabilities,
  approvedOperations: runtimeDescriptor.operations, hostSupportedOperations, consentHost,
});
const state = { mode: 'image', catalog: null, token: '', jobs: [], selected: null, objectUrl: null };
const $ = id => root.querySelector(`#${id}`);
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
};

root.innerHTML = `<div class="studio-shell">
  <header class="studio-header"><div><p class="studio-kicker">ITONAMI / DOUGAKA</p>
    <h1>Studio</h1><p>画像・動画・声・音を Itonami のモデル基盤でつくる。</p></div>
    <span class="studio-badge">ブラウザから直接接続 · 操作ごとに確認</span></header>
  <nav class="studio-tabs" aria-label="制作モード"></nav>
  <div class="studio-grid"><section class="studio-card studio-compose" aria-label="生成設定">
    <div class="studio-card-head"><h2 id="studio-mode-title">画像をつくる</h2><span id="studio-model-count"></span></div>
    <div class="studio-control"><label for="studio-token">Murakumo access token</label>
      <div class="studio-inline"><input id="studio-token" type="password" autocomplete="off" spellcheck="false" placeholder="このタブだけで保持">
      <button id="studio-connect" type="button">設定</button><button id="studio-disconnect" type="button">解除</button></div>
      <small>トークンは保存しません。外部操作の前に送信先と内容を確認できます。</small></div>
    <div class="studio-control"><label for="studio-model">モデル</label>
      <div class="studio-inline"><select id="studio-model"><option value="">カタログを読み込んでください</option></select>
      <button id="studio-catalog" type="button">モデルを更新</button></div></div>
    <div class="studio-control"><label id="studio-prompt-label" for="studio-prompt">プロンプト</label>
      <textarea id="studio-prompt" rows="7" maxlength="12000" placeholder="被写体、動き、光、画角を記述"></textarea></div>
    <div class="studio-settings"><div class="studio-control"><label for="studio-aspect">画面比</label>
      <select id="studio-aspect"><option value="16:9">横 16:9</option><option value="9:16">縦 9:16</option><option value="1:1">正方形 1:1</option></select></div>
      <div class="studio-control"><label for="studio-seconds">長さ（秒）</label><input id="studio-seconds" type="number" min="1" max="120" value="4"></div>
      <div class="studio-control"><label for="studio-seed">seed</label><input id="studio-seed" type="number" min="0" max="2147483647" value="0"></div></div>
    <button id="studio-generate" class="studio-primary" type="button">生成ジョブを送る</button>
    <p id="studio-message" class="studio-message" role="status">モデルカタログを読み込むと制作を始められます。</p>
  </section><section class="studio-card studio-results" aria-label="ジョブと作品">
    <div class="studio-card-head"><h2>このタブの制作</h2><span id="studio-job-count">0 件</span></div>
    <div id="studio-jobs" class="studio-jobs"><p class="studio-empty">生成した作品がここに並びます。</p></div>
    <div class="studio-actions"><button id="studio-refresh" type="button" disabled>状況を更新</button>
      <button id="studio-artifact" type="button" disabled>結果を確認</button>
      <button id="studio-cancel" type="button" disabled>ジョブを中止</button></div>
    <div id="studio-preview" class="studio-preview" aria-live="polite"></div>
  </section></div>
  <p class="studio-footnote">Murakumo のジョブは外部サービス上で実行されます。生成と結果取得は毎回確認を求めます。画面内の履歴はこのタブを閉じると消えます。</p>
</div>`;

const setMessage = (message, error = false) => {
  const node = $('studio-message');
  node.textContent = message;
  node.dataset.tone = error ? 'error' : 'info';
};
const errorText = error => {
  const message = error instanceof Error ? error.message : String(error);
  return message.includes('client operation was not approved')
    ? '操作を取り消しました。Murakumo には送信していません。' : message;
};
const jobPath = (job, suffix = '') => `${generationOrigin}/api/v1/generation/jobs/${encodeURIComponent(job.id)}${suffix}`;

async function invoke(name, url, method = 'GET', body = undefined) {
  const headers = { accept: 'application/json' };
  if (state.token && name !== 'catalog') headers.authorization = `Bearer ${state.token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const input = new Request(url, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    mode: 'cors', credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer',
  });
  return broker.invoke(selector(name), { input });
}

async function jsonResponse(response) {
  if (response.ok && response.status === 204) return {};
  let body;
  try { body = await response.json(); } catch { throw new Error(`HTTP ${response.status}: JSON 応答がありません`); }
  if (!response.ok) {
    const reason = body?.error?.message ?? body?.error ?? body?.message ?? response.statusText;
    throw new Error(`HTTP ${response.status}: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`);
  }
  return body;
}

function renderTabs() {
  const nav = root.querySelector('.studio-tabs');
  nav.replaceChildren();
  for (const [id, mode] of Object.entries(modes)) {
    const button = el('button', id === state.mode ? 'studio-tab is-active' : 'studio-tab', mode.label);
    button.type = 'button'; button.dataset.mode = id;
    button.setAttribute('aria-current', id === state.mode ? 'page' : 'false');
    button.addEventListener('click', () => { state.mode = id; renderMode(); });
    nav.append(button);
  }
}

function renderMode() {
  renderTabs();
  $('studio-mode-title').textContent = `${modes[state.mode].label}をつくる`;
  $('studio-prompt-label').textContent = state.mode === 'voice' ? '読み上げるテキスト' : 'プロンプト';
  $('studio-prompt').placeholder = state.mode === 'voice' ? '読み上げる文章を入力' : '被写体、動き、光、画角を記述';
  $('studio-seconds').disabled = ['image', 'voice'].includes(state.mode);
  $('studio-seconds').max = state.mode === 'video' ? '5' : '120';
  $('studio-aspect').disabled = ['voice', 'music', 'sfx'].includes(state.mode);
  const models = modelsFor(state.catalog, state.mode);
  const select = $('studio-model'); const previous = select.value;
  select.replaceChildren();
  select.append(el('option', null, models.length ? 'モデルを選択' : '対応モデルがありません'));
  select.firstChild.value = '';
  for (const model of models) {
    const opt = el('option', null, `${model.id} · ${model.status}`);
    opt.value = model.id;
    opt.disabled = !['production', 'verified'].includes(model.status);
    select.append(opt);
  }
  if (models.some(model => model.id === previous && ['production', 'verified'].includes(model.status)))
    select.value = previous;
  else {
    const preferred = models.find(model => ['production', 'verified'].includes(model.status));
    select.value = preferred?.id ?? '';
  }
  $('studio-model-count').textContent = `${models.filter(model => ['production', 'verified'].includes(model.status)).length} 候補モデル`;
  $('studio-generate').disabled = !select.value || !state.token;
}

function renderJobs() {
  $('studio-job-count').textContent = `${state.jobs.length} 件`;
  const list = $('studio-jobs'); list.replaceChildren();
  if (!state.jobs.length) list.append(el('p', 'studio-empty', '生成した作品がここに並びます。'));
  for (const job of state.jobs) {
    const button = el('button', job.id === state.selected ? 'studio-job is-selected' : 'studio-job');
    button.type = 'button';
    button.append(el('strong', null, `${modes[job.mode].label} · ${job.model}`));
    button.append(el('span', 'studio-job-status', job.status));
    button.append(el('small', null, job.prompt.slice(0, 100)));
    button.addEventListener('click', () => {
      state.selected = job.id;
      if (state.objectUrl) URL.revokeObjectURL(state.objectUrl);
      state.objectUrl = null;
      $('studio-preview').replaceChildren();
      renderJobs();
    });
    list.append(button);
  }
  const selected = state.jobs.find(job => job.id === state.selected);
  $('studio-refresh').disabled = !selected;
  $('studio-cancel').disabled = !selected || ['done', 'failed', 'cancelled', 'canceled'].includes(selected.status);
  $('studio-artifact').disabled = !selected || selected.status !== 'done' || !artifactDigest(selected.detail);
}

async function run(button, action) {
  button.disabled = true;
  try { await action(); }
  catch (error) { setMessage(errorText(error), true); }
  finally { renderMode(); renderJobs(); }
}

$('studio-connect').addEventListener('click', () => {
  state.token = $('studio-token').value.trim(); $('studio-token').value = '';
  setMessage(state.token ? 'トークンをこのタブのメモリに設定しました。' : 'トークンを入力してください。', !state.token);
  renderMode();
});
$('studio-disconnect').addEventListener('click', () => {
  state.token = ''; $('studio-token').value = ''; setMessage('トークンを解除しました。'); renderMode();
});
$('studio-catalog').addEventListener('click', event => run(event.currentTarget, async () => {
  const catalog = await jsonResponse(await invoke('catalog', `${generationOrigin}/api/v1/generation/catalog`));
  state.catalog = catalog; renderMode();
  const message = catalog.configured === false
    ? 'Murakumo の生成サービスは未設定です。モデルは選択できません。'
    : catalog.configured === true
      ? 'モデルカタログを取得しました。実行はジョブ応答で確認します。'
      : 'モデル一覧を取得しました。サービスの実行可否は応答に無いため、生成ジョブの結果で確認します。';
  setMessage(message, catalog.configured === false);
}));
$('studio-model').addEventListener('change', renderMode);
$('studio-generate').addEventListener('click', event => run(event.currentTarget, async () => {
  const model = $('studio-model').value;
  if (!modelsFor(state.catalog, state.mode).some(entry => entry.id === model && ['production', 'verified'].includes(entry.status)))
    throw new Error('公開カタログで利用可能なモデルを選択してください');
  if (!state.token) throw new Error('Murakumo access token を設定してください');
  const prompt = $('studio-prompt').value.trim();
  const body = buildGenerationRequest({ mode: state.mode, prompt, model,
    seconds: $('studio-seconds').value, aspect: $('studio-aspect').value, seed: $('studio-seed').value });
  const result = await jsonResponse(await invoke('submit', `${generationOrigin}/api/v1/generation`, 'POST', body));
  const id = jobIdFrom(result);
  if (!id) throw new Error('ジョブ ID のない応答です。再送せず Murakumo 側の履歴を確認してください');
  state.jobs.unshift({ id, mode: state.mode, model, prompt, status: result.status ?? 'submitted', detail: result });
  state.selected = id; renderJobs(); setMessage(`ジョブ ${id} を受け付けました。状況の更新は手動です。`);
}));
$('studio-refresh').addEventListener('click', event => run(event.currentTarget, async () => {
  const job = state.jobs.find(item => item.id === state.selected);
  if (!job) return;
  const detail = await jsonResponse(await invoke('status', jobPath(job)));
  job.status = String(detail.status ?? 'unknown'); job.detail = detail;
  renderJobs(); setMessage(`ジョブ ${job.id}: ${job.status}`);
}));
$('studio-cancel').addEventListener('click', event => run(event.currentTarget, async () => {
  const job = state.jobs.find(item => item.id === state.selected);
  if (!job) return;
  await jsonResponse(await invoke('cancel', jobPath(job), 'DELETE'));
  job.status = 'cancelled'; renderJobs(); setMessage(`ジョブ ${job.id} を中止しました。`);
}));
$('studio-artifact').addEventListener('click', event => run(event.currentTarget, async () => {
  const job = state.jobs.find(item => item.id === state.selected);
  if (!job || job.status !== 'done') return;
  const expected = artifactDigest(job.detail);
  if (!expected) throw new Error('成果物の SHA-256 が無いため取得を止めました');
  const response = await invoke('artifact', jobPath(job, '/artifact'));
  if (!response.ok) throw new Error(`成果物を取得できません: HTTP ${response.status}`);
  const bytes = await response.arrayBuffer();
  const actual = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    byte => byte.toString(16).padStart(2, '0')).join('');
  if (actual !== expected) throw new Error('成果物の SHA-256 が一致しません');
  if (state.objectUrl) URL.revokeObjectURL(state.objectUrl);
  const type = job.mode === 'image' ? 'image/png' : job.mode === 'video' ? 'video/mp4' : 'audio/wav';
  state.objectUrl = URL.createObjectURL(new Blob([bytes], { type }));
  const preview = $('studio-preview'); preview.replaceChildren();
  const media = el(job.mode === 'image' ? 'img' : job.mode === 'video' ? 'video' : 'audio');
  media.src = state.objectUrl; media.controls = true;
  if (job.mode === 'video') media.playsInline = true;
  preview.append(media);
  const link = el('a', 'studio-download', '検証済み成果物を保存');
  link.href = state.objectUrl;
  link.download = `${job.id}.${job.mode === 'image' ? 'png' : job.mode === 'video' ? 'mp4' : 'wav'}`;
  preview.append(link); setMessage(`ジョブ ${job.id} の成果物を SHA-256 で検証しました。`);
}));

window.addEventListener('pagehide', () => {
  state.token = '';
  if (state.objectUrl) URL.revokeObjectURL(state.objectUrl);
  consentHost.destroy();
}, { once: true });
renderMode(); renderJobs();
