# app-itonami-dougaka

**dougaka Studio は、Itonami のモデル基盤で画像・動画・声・音を制作する静的 dapp。**
画面・制作状態・timeline 計画は利用者のブラウザで動く。生成を選ぶと、canonical
`kotoba-client-runtime.js` の broker が操作ごとに Murakumo の接続先と送信内容を示し、
承認後にブラウザから `generation.murakumo.cloud` へ直接接続する。

公開先は `dougaka.itonami.app` を予定し、現時点では配信側の
`network-awai/cloud-itonami` で provisioning 中。この repo は中身と公開物の宣言
（`site/site.edn`）を持つ。旧 `itonami.cloud/sites/app/dougaka/` は別の配信面。

従来の timeline JSON → ffmpeg 組み立て計画も画面下部に残す。その計画機能は
完全にブラウザ内で動き、外部サービスへ送信しない。

> dougaka は名称の比喩で、提携や同一性を主張しない。

## 何があって、何が動くか

| 層 | ファイル | 状態 |
|---|---|---|
| 判断コア guest | `aiueos/dougaka_core.cljk`（ns `dougaka.guest`、`:effects #{}`） | **実装済み**：amu check / test が通れば wasm 化する |
| アプリ記述子 | `aiueos/dougaka.edn`（`:aiueos/kind :app`） | 記述のみ。aiueos の session/grant への配線は**未実装** |
| adapter | `src/dougaka/core.cljk` — 検証 + `build-plan` + ffmpeg argv | **実装済み・実測**（kbb） |
| 取り込み | `src/dougaka/ingest.cljk` — timeline JSON | **実装済み・実測**（kbb） |
| 書き出し | `src/dougaka/export.cljk` — 計画 JSON + ffmpeg 台本 + 要約 | **実装済み・実測**（kbb） |
| 制作画面 | `site/studio-host.js` + `studio-core.js` + `studio-contract.js` | **実装済み**：ローカル配信で五つのタブと同意・拒否を確認。実生成は未検証 |
| timeline 画面 | `src/dougaka/view.cljk` + `site/app.cljk` | **実装済み**：従来の計画機能を折りたたみ内に維持 |
| 公開物の宣言 | `site/site.edn`、`site/dougaka/page.cljk`、`site/vendor/`（+ `PROVENANCE.edn`） | 宣言済み。app.itonami.cloud への公開は cloud-itonami 側の作業 |
| timeline MCP | `bin/dougaka-mcp`（stdio）+ `src/dougaka/mcp.cljk` | **実装済み・実測**：validate / plan |
| 生成 MCP | `bin/dougaka-generation-mcp.mjs` | **実装済み・ローカル検査**：モデル、五つの生成、状態・中止・保存。トークンを使う実生成は未検証 |
| 分散状態 | ADR 0005 の `mon` / `ito` / `nuno` | **未実装**（設計のみ。docs/aiueos-dapp.md） |
| Murakumo 生成 | ブラウザから直接接続。トークンはタブのメモリのみ | カタログで self-hosted かつ production/verified のモデルだけを選ぶ。現在の voice は unverified で選択不可。`configured=false` の明示時は停止。動画は 1 ジョブ約 5 秒まで |

詳細な実測と「動かない」ものは [`docs/aiueos-dapp.md`](docs/aiueos-dapp.md)。

## 計画の形（本家との対応）

本家 `douga.ffmpeg/build-render-plan` と同形：

- `scenes` に対応する scene frame の無いものは落とす。voices は line 順。
- `segments[i] = {scene-index, frame-blob-key, voice-blob-keys, texts, speakers}`
- `width/height/fps` + 先頭の `bgm`。`output-mp4` は `nil`（計画だけ）。
- 検証は本家 DougakaGovernor の HARD のうち、ブラウザで決められる分だけ
  （scene 数 1..24、scene frame の有無、推定尺 120s 以内）。

## itonami agent から使う（MCP）

`bin/dougaka-mcp` は stdio の MCP サーバで、itonami agent（kotoba-lang/itonami-agent）や Hermes が profile の `config.yaml` の `mcp_servers` から起動する。

```yaml
mcp_servers:
  dougaka:
    command: kbb
    args: ["/abs/path/to/cloud-itonami/app-itonami-dougaka/bin/dougaka-mcp"]
```

- agent からは `mcp__dougaka__<tool>` という名前で見える。

| tool | 入力 | 返すもの |
|---|---|---|
| `dougaka_validate` | `text`（timeline JSON、5 MiB まで） | `ok`、`errors`（名前付きの理由） |
| `dougaka_plan` | `text`、`aspect?`（`portrait`／`landscape`） | `ok`、`errors`、`plan`、`summary`、`ffmpeg`（台本） |

この二つの tool は純粋で、送信・保存をしない。生成には別 MCP サーバを使う。

```yaml
mcp_servers:
  dougaka-generation:
    command: node
    args: ["/abs/path/to/cloud-itonami/app-itonami-dougaka/bin/dougaka-generation-mcp.mjs"]
    # 起動環境に MURAKUMO_GENERATION_TOKEN を generation scope で設定する。
    # 成果物のローカル保存には DOUGAKA_OUTPUT_DIR も設定する。
```

生成側は `dougaka_models`、`dougaka_generate_image/video/voice/music/sfx`、
`dougaka_job_status`、`dougaka_cancel_job`、`dougaka_save_artifact` を公開する。
モデル一覧は公開カタログを読み、生成時に再確認する。生成は外部計算を消費する。
保存は完了ジョブの SHA-256 が一致した場合のみ行い、同名ファイルを上書きしない。
ブラウザでの同意は agent の tool 実行を許可しない。tool の承認は別途必要。

入力不正（未知の引数、型、enum、5 MiB 超、空、読めないテキスト）は、JSON-RPC の error ではなく `isError: true` の結果で返す（`{"error": "<名前付きの理由>"}` の形）。

判定はブラウザと同じ adapter（`dougaka.core`）で、wasm guest は読み込まない。guest との一致は parity 表（`test/fixtures/parity.edn`、amu test・wasm parity・kbb の 3 経路）で担保している。itonami agent の peer run では MCP が無効なので、この tool はローカル run でだけ使える。

## 動かし方

west レイアウト（`<root>/cloud-itonami/app-itonami-dougaka` の隣に `<root>/kotoba-lang/…`）を前提にする。

```sh
npm test            # kbb: parity + core + 取り込み + 画面 + MCP
npm run check:guest # amu check: guest の検査
npm run test:guest  # amu test: guest の test-*（jvm-kir / js / wasm）
npm run build:guest # amu compile → aiueos/dougaka_core.wasm + sidecar
npm run build:site  # out/site/ に公開物を組む → 静的サーバで開く
npm run mcp         # stdio MCP サーバ（stdin に JSON-RPC を流す）
npm run mcp:generation # Murakumo 生成 MCP（トークンが必要）
```

## ライセンス

AGPL-3.0-or-later。`site/vendor/` の同梱物は各ライセンス（scittle: EPL-1.0、React: MIT）。出所と digest は `site/vendor/PROVENANCE.edn`。
