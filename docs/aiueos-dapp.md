# app-itonami-dougaka — aiueos dapp 設計と実測

設計と検証境界。現在の機能一覧は README を参照する。

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
- `bin/dougaka-mcp` + `src/dougaka/mcp.cljk` — timeline 用の純粋な stdio MCP。
- `site/studio-*.js` — ブラウザの制作 UI。canonical broker の固定 commit を
  `site/site.edn` で pin し、生成サービスへの操作を一件ずつ承認する。
- `bin/dougaka-generation-mcp.mjs` — 別の、効果を持つ生成 MCP。tool 承認と
  ブラウザ同意は互いに流用しない。
- `bin/dougaka-auto-publish.mjs` — scoped token を使う agent/CLI の無人経路。
  成果物を検証し、own-node Kubo で不変 CID を発行する。ドメインは変更しない。

## timeline 計画と制作の境界

本家 `cloud-itonami/dougaka` は HTTP グラフサーバ + オフライン ffmpeg 実行を持つ。
この dapp はその計画部をブラウザに残し、画像・動画・声・音については
Murakumo の公開 generation API に直接接続する。dapp origin は静的資産のみを配る。

| 本家にある | dapp にある | dapp に無い |
|---|---|---|
| render plan の形 | 同形の plan（segments/width/height/fps） | — |
| ffmpeg 実行 | argv の表示・台本の保存 | 実行 |
| Murakumo generation | 操作ごとに同意する直接接続 | origin 上の API / proxy |
| B2 / Kotobase への接続 | 無し | 無し（対象外） |
| `/xrpc` の auth 境界 | 無し（ローカルに鍵を持たない） | — |

Murakumo の access token はブラウザのタブ内メモリにのみ保持する。入力した token を
アプリ origin に送らない。生成サービスが `configured=false` を明示した場合は
モデルを選択可能にしない。2026-10-25 の live catalog は `configured` を返さないため、
その場合は実行可否を未確認と表示し、ジョブ応答で判断する。

## 未実装

- **aiueos の session／grant への配線** — dandori と同じ段階。画面の判定は
  adapter で、guest との一致は parity fixture で担保する。
- **分散状態**（ADR 0005 の mon／ito／nuno）— 設計のみ。今は 1 ブラウザの
  localStorage に閉じる。
- **Higgsfield 固有の管理機能の完全互換** — project/brand/canvas/marketing
  等は現時点で未実装。Itonami の production/verified モデルに限って制作する。
- **ライブ生成と独自ドメイン公開** — トークン付き生成・実成果物は未検証。
  own-node IPFS/Kubo 向け現行静的候補の CID は pin、CAR 経由の全件照合、
  P2P での index 読戻しまで確認した。
  IPNS、DNSLink と HTTPS のブラウザ readback を通るまで独自ドメイン公開済みと扱わない。
- **MCP/CLI の結合経路** — ローカルの状態遷移と再送防止はテスト済み。
  generation-scope token が無いため、実ジョブから CID 発行までの実測は未完。

## 実測

`npm test`（kbb）の結果をここに記録する。wasm / site / MCP の実測も同じく。

- adapter + MCP: `npm test`（kbb）— **21 tests / 92 assertions / 0 failures**
  （parity 21行 + core + ingest + view + MCP + stdio 統合）。
- guest: `amu test` — **12/12 passed**（4 test-* × :jvm-kir :js :wasm）。
  `amu check` は `:effects #{}` で green。
- wasm parity: `node test/guest_parity.mjs` — **21/21 rows match**
  （`dougaka_core.wasm` 1,567 bytes + provenance/publication sidecar）。
- site: 今回の Studio 候補は app 側 build と配信側 generator の両方で生成。
  `src`/`href` は相対、canonical broker と consent CSS は pinned commit と digest で照合。
  CSP は `connect-src https://generation.murakumo.cloud` のみを許可。
- 静的配布: 現行 18 ファイルの候補 CID `bafybeia5mo45s6bq7i5v6l4qoa7dh55vv3djwhyn6a4fjveh3p2wlstsxi`（Xavier pin、CAR 経由で全件照合、P2P は index 読戻しまで）
  を own-node に再帰 pin。別の一時 Kubo から P2P で取得し、元の `out/site/` と
  18/18 ファイルがバイト一致。`index.html` SHA-256 は
  `2361fe743396556fcdee5af84d03d98208a22fdd151a1808e1f8f8afdea63068`。
  独自ドメインの HTTPS と実生成は未確認。
- MCP: stdio 3 行（initialize → initialized → tools/call）で `dougaka_validate` が
  `{"ok":false,"errors":["missing-scene-frames: 0"]}` を返し、exit 0（実測）。
