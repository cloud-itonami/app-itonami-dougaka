# app-itonami-dougaka

**dougaka（動画家）は、timeline から ffmpeg の組み立て計画を作る dapp。**
timeline の JSON（scenes / assets / lines）を取り込み、順序付き segments
（frame / voice / text）と解像度・fps・bgm の計画を作り、ffmpeg の台本として
書き出す。すべて利用者のブラウザの中で動き、入力は送信されない（外部リクエストはゼロ）。

公開先は app.itonami.cloud（`network-awai/cloud-itonami` の `sites.edn`、`app` org）。
公開の配線はそちらの repo の作業で、この repo が持つのは中身と公開物の宣言（`site/site.edn`）。

本家 `cloud-itonami/dougaka`（動画生成サービス）の render 計画部と同形の
計画を作る。違うのは実行しないこと：mp4 エンコード・生成 AI・B2/Kotobase への
接続は持たない。計画だけ作って、手元の ffmpeg で焼くための台本を渡す。

> dougaka は名称の比喩で、提携や同一性を主張しない。

## 何があって、何が動くか

| 層 | ファイル | 状態 |
|---|---|---|
| 判断コア guest | `aiueos/dougaka_core.cljk`（ns `dougaka.guest`、`:effects #{}`） | **実装済み**：amu check / test が通れば wasm 化する |
| アプリ記述子 | `aiueos/dougaka.edn`（`:aiueos/kind :app`） | 記述のみ。aiueos の session/grant への配線は**未実装** |
| adapter | `src/dougaka/core.cljk` — 検証 + `build-plan` + ffmpeg argv | **実装済み・実測**（kbb） |
| 取り込み | `src/dougaka/ingest.cljk` — timeline JSON | **実装済み・実測**（kbb） |
| 書き出し | `src/dougaka/export.cljk` — 計画 JSON + ffmpeg 台本 + 要約 | **実装済み・実測**（kbb） |
| 画面 | `src/dougaka/view.cljk`（純 hiccup）+ `site/app.cljk`（Re-frame/Reagent、scittle） | **実装済み**：ローカル配信で確認 |
| 公開物の宣言 | `site/site.edn`、`site/dougaka/page.cljk`、`site/vendor/`（+ `PROVENANCE.edn`） | 宣言済み。app.itonami.cloud への公開は cloud-itonami 側の作業 |
| agent 統合（MCP） | `bin/dougaka-mcp`（stdio）+ `src/dougaka/mcp.cljk` — tool `dougaka_validate` / `dougaka_plan` | **実装済み・実測**：stdio テスト、itonami-agent の MCP クライアントで discover / call |
| 分散状態 | ADR 0005 の `mon` / `ito` / `nuno` | **未実装**（設計のみ。docs/aiueos-dapp.md） |
| 生成 AI・mp4 エンコード・本家 API 直結 | — | **未実装・対象外**（この dapp は計画だけ作る。`kotoba-client-runtime.js` が無いこともあるが、そもそも接続しない設計） |

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

しないこと:
- エンコード・送信・保存はしない。tool は渡されたテキストだけから結果を作り、呼び出しをまたいで何も覚えない。
- 生成 AI・本家 dougaka API には接続しない。

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
```

## ライセンス

AGPL-3.0-or-later。`site/vendor/` の同梱物は各ライセンス（scittle: EPL-1.0、React: MIT）。出所と digest は `site/vendor/PROVENANCE.edn`。
