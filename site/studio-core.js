// Pure request decisions shared by the browser studio and its tests.
export const modes = Object.freeze({
  image: { type: 'image', catalog: 'image', label: '画像' },
  video: { type: 'video', catalog: 'video', label: '動画' },
  voice: { type: 'voice', catalog: 'voice', label: 'ナレーション' },
  music: { type: 'sound', catalog: 'music', label: '音楽' },
  sfx: { type: 'sound', catalog: 'sfx', label: '効果音' },
});

const clean = value => String(value ?? '').trim();
const modelId = model => clean(model?.id ?? model?.['id']);
const modelStatus = model => clean(model?.status ?? model?.['status']);

export function modelsFor(catalog, mode) {
  if (catalog?.configured === false) return [];
  const entries = catalog?.modalities?.[modes[mode]?.catalog] ?? [];
  if (!Array.isArray(entries)) return [];
  return entries.flatMap(entry => Array.isArray(entry?.models) ? entry.models : [])
    .filter(model => modelId(model))
    .map(model => ({ id: modelId(model), status: modelStatus(model) || 'unverified' }));
}

export function buildGenerationRequest({ mode, prompt, model, seconds = 4, aspect = '16:9', seed = 0 }) {
  const text = clean(prompt);
  const id = clean(model);
  if (!modes[mode]) throw new Error('未対応の生成種別です');
  if (!text || text.length > 12000) throw new Error('プロンプトを 1〜12000 文字で入力してください');
  if (!id || id.length > 128 || !/^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/.test(id)
      || id.split('/').some(part => part === '.' || part === '..')) throw new Error('モデルを選択してください');
  if (!Number.isInteger(Number(seed)) || Number(seed) < 0 || Number(seed) > 2147483647) throw new Error('seed は 0〜2147483647 の整数です');
  const n = Number(seconds);
  if (!Number.isFinite(n) || n < 1 || n > 120) throw new Error('長さは 1〜120 秒で指定してください');
  if (!['16:9', '9:16', '1:1'].includes(aspect)) throw new Error('画面比を選択してください');
  switch (mode) {
    case 'image': return { type: 'image', model: id, input: { prompt: text },
      params: { model: id, width: aspect === '9:16' ? 736 : aspect === '1:1' ? 1024 : 1280,
        height: aspect === '9:16' ? 1280 : aspect === '1:1' ? 1024 : 736, steps: 25, seed: Number(seed) } };
    case 'video': {
      // The measured Murakumo contract accepts 8n+1 frames, up to 121.
      // A longer clip needs multiple jobs and editing, so do not imply it fits.
      if (n > 5) throw new Error('動画は 1 ジョブ 5 秒までです');
      const frames = Math.max(9, Math.min(121, 1 + 8 * Math.round((Math.ceil(24 * n) - 1) / 8)));
      return { type: 'video', model: id, input: { prompt: text }, params: { frames, seed: Number(seed) } };
    }
    case 'voice': return { type: 'voice', model: id, input: { text }, params: { locale: 'ja-JP' } };
    case 'music':
    case 'sfx': return { type: 'sound', model: id, input: { prompt: text },
      params: { sound_kind: mode, duration_ms: Math.round(n * 1000), seed: Number(seed), loop: mode === 'music' } };
  }
}

export function jobIdFrom(body) {
  const id = clean(body?.jobId ?? body?.job_id);
  return /^[A-Za-z0-9_-]{1,128}$/.test(id) ? id : null;
}

export function artifactDigest(job) {
  const record = Array.isArray(job?.artifacts) ? job.artifacts[0] : null;
  const hash = clean(record?.contentHash ?? record?.content_hash);
  return /^sha256:[0-9a-f]{64}$/i.test(hash) ? hash.slice(7).toLowerCase() : null;
}
