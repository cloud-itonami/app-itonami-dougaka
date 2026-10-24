# app-itonami-dougaka — aiueos dapp 設計と実測

設計の正本。README は「今この repo に何が在って、何が動くか」だけを書く。
判断の理由と未実装の境界はここに書く。

## 設計

1 repo = 1 スライス（JUN-116）。中身は mihari / dandori と同じ形：

- `aiueos/dougaka_core.cljk` — 判断コア guest（pure、`:effects #{}`）。
  aspect/dims と scene 数・尺・shot の gate だけを持つ。i64 のみ。
- `src/dougaka/core.cljk` — adapter。guest と同名・同結果の判定 + 計画モデル
  （validate / build-plan / ffmpeg argv）。ブラウザと kbb の両方で走る。
- `src/dougaka/ingest.cljk` / `export.cljk` — timeline JSON の取り込みと
  計画 JSON・ffmpeg 台本・要約の書き出し。純関数。
- `src/dougaka/view.cljk` + `site/app.cljk` — 純 hiccup と Re-frame/Reagent。
  SSR とブラウザが同じ関数を描く（ADR-0052）。
- `site/site.edn` + `site/dougaka/page.cljk` — 公開物の宣言と殻。
  生成器（network-awai/cloud-itonami）が読む。
- `bin/dougaka-mcp` + `src/dougaka/mcp.cljk` — stdio MCP（pure tools）。

## 本家との境界

本家 `cloud-itonami/dougaka` は HTTP グラフサーバ + オフライン ffmpeg 実行で、
murakumo fleet・ComfyUI・VOICEVOX・B2・Kotobase に触る。この dapp はそのうち
**計画部（timeline → render plan）の純関数のみ** をブラウザに載せる。

| 本家にある | dapp にある | dapp に無い |
|---|---|---|
| render plan の形 | 同形の plan（segments/width/height/fps） | — |
| ffmpeg 実行 | argv の表示・台本の保存 | 実行 |
| murakumo / ComfyUI / VOICEVOX | 無し | 無し（対象外） |
| B2 / Kotobase への接続 | 無し | 無し（対象外） |
| `/xrpc` の auth 境界 | 無し（ローカルに鍵を持たない） | — |

本家の既知の gap（`/xrpc` が `LG_API_KEY` の裏に無い）は、この dapp には無い。
鍵を持たず、送信もしないからである。

## 未実装

- **aiueos の session／grant への配線** — dandori と同じ段階。画面の判定は
  adapter で、guest との一致は parity fixture で担保する。
- **分散状態**（ADR 0005 の mon／ito／nuno）— 設計のみ。今は 1 ブラウザの
  localStorage に閉じる。
- **生成 AI・mp4 エンコード・本家 API への直接接続** — 対象外。この dapp は
  計画だけ作る道具で、共通ブローカー（`kotoba-client-runtime.js`）の有無に
  かかわらず接続しない。

## 実測

`npm test`（kbb）の結果をここに記録する。wasm / site / MCP の実測も同じく。

- adapter + MCP: `npm test`（kbb）— **21 tests / 92 assertions / 0 failures**
  （parity 21行 + core + ingest + view + MCP + stdio 統合）。
- guest: `amu test` — **12/12 passed**（4 test-* × :jvm-kir :js :wasm）。
  `amu check` は `:effects #{}` で green。
- wasm parity: `node test/guest_parity.mjs` — **21/21 rows match**
  （`dougaka_core.wasm` 1,567 bytes + provenance/publication sidecar）。
- site: `npm run build:site` — `out/site/index.html` 78,218 chars +
  5 vendor + 6 scittle。`src`/`href` はすべて相対（外部リクエストゼロの検査 green）。
  本文中の `http` は 1 件だけ inline CSS 内の DADS コメント URL（取得されない）。
- MCP: stdio 3 行（initialize → initialized → tools/call）で `dougaka_validate` が
  `{"ok":false,"errors":["missing-scene-frames: 0"]}` を返し、exit 0（実測）。
