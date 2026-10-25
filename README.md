# app-itonami-dougaka

**dougaka Studio は、Itonami のモデル基盤で画像・動画・声・音を制作する静的 dapp。**
画面・制作状態・timeline 計画は利用者のブラウザで動く。生成を選ぶと、canonical
`kotoba-client-runtime.js` の broker が操作ごとに Murakumo の接続先と送信内容を示し、
承認後にブラウザから `generation.murakumo.cloud` へ直接接続する。

公開先は `dougaka.itonami.app` を予定し、既存 own-node IPFS/Kubo から
`out/site/` の静的バンドルを配る方針。この repo が中身と公開物の宣言
（`site/site.edn`）を持つ。現行候補 CID は own-node に pin し、そこから
書き出した CAR を別の Kubo に取り込んで 18 ファイルを照合済み。IPNS/DNSLink と独自ドメインの
HTTPS 読み戻しは未完了。旧 `itonami.cloud/sites/app/dougaka/`
は別の配信面で、公開判定には使わない。

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
| 公開物の宣言 | `site/site.edn`、`site/dougaka/page.cljk`、`site/vendor/`（+ `PROVENANCE.edn`） | 宣言済み。own-node pin と別 Kubo からの読戻し済み。独自ドメイン公開は未完了 |
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
`dougaka_job_status`、`dougaka_cancel_job`、`dougaka_save_artifact`、
`dougaka_generate_and_publish` を公開する。
モデル一覧は公開カタログを読み、生成時に再確認する。生成は外部計算を消費する。
保存は完了ジョブの SHA-256 が一致した場合のみ行い、同名ファイルを上書きしない。
ブラウザでの同意は agent の tool 実行を許可しない。tool の承認は別途必要。

### MCP/CLI による無人の生成と CID 発行

生成権限を絞った `MURAKUMO_GENERATION_TOKEN` と、既存ディレクトリ
`DOUGAKA_OUTPUT_DIR`、`DOUGAKA_RUN_DIR` を実行環境に設定する。
ローカル Kubo に発行する場合は `IPFS_PATH` を設定し、`ipfs id` が通ること。
`IPFS_BIN` は必要なときだけ指定する。既存の own-node publisher を使う場合は
`DOUGAKA_IPFS_PUBLISHER` に `publish_app_ipfs.py` のパス、
`DOUGAKA_IPFS_NODES` にその設定上の node 名（例: `xavier`）を指定する。
後者は `--document` で own-node へ pin、読み戻し、provider 告知を行う。
CLI は公開カタログでモデルを再確認し、生成、手動操作なしの状態確認、SHA-256
検証済み成果物の保存、Kubo への追加・再帰 pin・読み戻し・provider 告知を順に行う。

```json
{
  "request_id": "campaign-20261025-001",
  "mode": "image",
  "prompt": "A blue lantern on a quiet street",
  "model": "MODEL_FROM_CATALOG",
  "aspect": "16:9",
  "seed": 0
}
```

`npm run auto-publish -- /path/to/request.json` は `ipfs://{cid}` と SHA-256 を
含む receipt を出す。MCP の `dougaka_generate_and_publish` には同じ JSON を
引数として渡す。MCP はジョブが未完了なら `status: pending` と `retry_after_ms`
を返すので、agent は同じ `request_id` で呼び直す。host 側の tool 承認規則は
別途適用される。ブラウザ dapp の外部操作同意は省略しない。

ジャーナルは `request_id` を一度だけ使う。生成送信後の応答が失われた場合、
重複課金を避けるため自動再送を拒否する。通常経路は無人で進むが、この曖昧な
失敗は運用者がサービス側の履歴を調べる必要がある。発行先は**不変の IPFS CID**
のみで、IPNS/DNS と `dougaka.itonami.app` は変更しない。生成用 token がまだ
用意されていないため、実生成と公開の結合経路は未実測。

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
npm run auto-publish -- /path/to/request.json # 生成 → 検証 → IPFS CID 発行
```

`out/site/` の現行 18 ファイルを Kubo 0.41 で UnixFS に追加した候補 CID は
`bafybeia5mo45s6bq7i5v6l4qoa7dh55vv3djwhyn6a4fjveh3p2wlstsxi`。
旧候補 `bafybeiasmfbfm4633pi72sir7adsrbagnspck5meeoexo2x3afadagec3u` は
現行 main の `studio-core.js` より古く、hosted モデルの除外を含まないため、
公開候補から外した。
現行 CID は Xavier に再帰 pin し、Xavier から書き出した CAR の SHA-256
`88cf539dee08dd2379ec558088fac2fcc8e893ad6ddff493b3b356092b62e555`
を発行時の記録と照合した。別 Kubo に CAR を取り込み、現行ビルドと 18 ファイルを
全件照合した。P2P では `index.html` を取得したが、全件の P2P 取得は未完了。
index SHA-256
`2361fe743396556fcdee5af84d03d98208a22fdd151a1808e1f8f8afdea63068`
を確認した。これは独自ドメイン公開の証拠ではない。
IPNS/DNSLink と HTTPS の読み戻しを経て、本番経路の切替は workspace
`AGENTS.md` の別承認を受ける。

### dapp の配布経路

hApp の[配布手順](https://developer.holochain.org/get-started/3-packaging-and-distribution/)の
「不変のパッケージを発行し、公開カタログで発見し、更新可能な名前から
現在版を指す」構造を配布の参考にする。Dougaka は Holochain の hApp ではなく、
ブラウザが実行する静的 dapp である。

1. リポジトリの commit と `site/site.edn` から `out/site/` を組み、CID と
   ファイル digest を記録する。
2. own-node Kubo に CID を pin し、独立した読戻しで配布物を照合する。
3. Itonami の配布記録に commit、CID、検証範囲、pin 先を載せる。
4. リリース条件を満たした版だけを IPNS/DNSLink の安定名へ昇格し、
   `dougaka.itonami.app` で HTTPS 読戻しする。更新や復旧は安定名の参照先を
   検証済み CID に替える。

生成 MCP/CLI が発行するメディアの CID はアプリ本体の CID と別である。
ブラウザ内の外部操作は共有 broker の都度同意を通す。

## ライセンス

AGPL-3.0-or-later。`site/vendor/` の同梱物は各ライセンス（scittle: EPL-1.0、React: MIT）。出所と digest は `site/vendor/PROVENANCE.edn`。
