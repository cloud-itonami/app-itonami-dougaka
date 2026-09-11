# Operator quickstart — ai-gftd-dougaka

Every command below was run against this tree on **2026-08-19** (commit
`2b9ac89`, macOS 25.3, Temurin JVM, ffmpeg 8 from Homebrew) and the recorded
output is what it actually printed. Steps that fail are written down as
failing.

Two things you can drive independently:

- **the graph server** — steps 1–7. Plans an ffmpeg assembly, never encodes.
- **the offline pipeline** — steps 8–10. Actually encodes an mp4.

Prerequisites: `clojure` CLI, a JVM, network (the first run fetches maven and
git deps), and `ffmpeg` on `PATH` for the pipeline steps only.

---

## 1. Tests

```bash
cd clj
clojure -M:test
```

```
Ran 24 tests containing 85 assertions.
0 failures, 0 errors.
```

If this is not green, stop — everything below assumes it is.

## 2. Start the server

`PORT` defaults to 8000. `LG_API_KEY` is optional; **set it here so step 4 can
show the auth boundary**, because when it is unset every route is open.

```bash
cd clj
LG_API_KEY=walkthrough-key PORT=8793 clojure -M:run
```

```
dougaka.gftd.ai (clj) listening on :8793
```

## 3. Health

The surface answers EDN by default and JSON when you ask for it — one handler,
content-negotiated on `accept` / `content-type`.

```bash
curl -sS http://127.0.0.1:8793/health
curl -sS -H 'accept: application/json' http://127.0.0.1:8793/health
```

```clojure
{:ok true, :status "ok", :app "dougaka.gftd.ai", :impl "clj",
 :graphs ["health" "render"],
 :nsids ["ai.gftd.apps.dougaka.health" "ai.gftd.apps.dougaka.render"]}
```
```json
{"ok":true,"status":"ok","app":"dougaka.gftd.ai","impl":"clj","graphs":["health","render"],"nsids":["ai.gftd.apps.dougaka.health","ai.gftd.apps.dougaka.render"]}
```

`GET /` and `GET /ok` are the same handler.

## 4. Render a timeline over `/runs` — and see the key enforced

```bash
TL='{"videoId":"quickstart-1","scenes":[{"index":0},{"index":1}],
     "assets":[{"kind":"scene","blobKey":"frame-0","meta":{"sceneIndex":0}},
               {"kind":"scene","blobKey":"frame-1","meta":{"sceneIndex":1}}]}'

# correct key → 200
curl -sS -X POST http://127.0.0.1:8793/runs \
  -H 'content-type: application/json' -H 'accept: application/json' \
  -H 'x-api-key: walkthrough-key' \
  -d "{\"assistant_id\":\"render\",\"input\":{\"timeline\":$TL}}"
```

```
200  status=planned  videoId=quickstart-1  segmentCount=2  output_mp4=null
segments: [{"scene-index":0,"frame-blob-key":"frame-0","voice-blob-keys":[],"texts":[],"speakers":[]},
           {"scene-index":1,"frame-blob-key":"frame-1","voice-blob-keys":[],"texts":[],"speakers":[]}]
```

`output_mp4` is `null` on purpose: **the server plans, it does not encode.**

```bash
# wrong key → 401 ;  no key header at all → 401
curl -sS -X POST http://127.0.0.1:8793/runs -H 'content-type: application/json' \
  -H 'accept: application/json' -H 'x-api-key: wrong' -d '{"assistant_id":"render","input":{}}'
```
```
401  {"error":"invalid api key"}
401  {"error":"invalid api key"}
```

`assistant_id` also resolves the health graph (`"health"` → 200), and `nsid`
may be sent instead of `assistant_id`.

## 5. Errors from `/runs`

```bash
curl -sS ... -d '{"assistant_id":"nope","input":{}}'     # 404 {"error":"unknown graph/nsid: nope"}
curl -sS ... -d '{not json'                              # 400 {"error":"Unexpected character ('n' ...)"}
```

## 6. `/xrpc` — the same graph, **not behind the key**

```bash
# note: no x-api-key header, and LG_API_KEY is still set on the server
curl -sS -X POST http://127.0.0.1:8793/xrpc/ai.gftd.apps.dougaka.render \
  -H 'content-type: application/json' -H 'accept: application/json' \
  -d '{"videoId":"quickstart-2","scenes":[{"index":0}],
       "assets":[{"kind":"scene","blobKey":"frame-0","meta":{"sceneIndex":0}}]}'
```

```
200  status=planned  videoId=quickstart-2  segmentCount=1
```

**This is the gap, not a feature to rely on:** step 4 refused the same graph
without a key and this did not. `runs-handler` calls `api-key-ok?`;
`xrpc-handler` does not (`clj/src/dougaka/server.cljk`). Anything fronting this
service must not assume `LG_API_KEY` closes the render graph.

Unknown nsid → `404 {"error":"unknown graph/nsid: …"}`; unknown path →
`404 {"error":"not found"}`; `OPTIONS` → `204` with the CORS headers.

## 7. EDN in, EDN out

```bash
curl -sS -X POST http://127.0.0.1:8793/xrpc/ai.gftd.apps.dougaka.render \
  -H 'content-type: application/edn' -H 'accept: application/edn' \
  -d '{:videoId "edn-1" :scenes [{:index 0}]
       :assets [{:kind "scene" :blobKey "f0" :meta {:sceneIndex 0}}]}'
```

```clojure
{:renderPlan {:segments [{:scene-index 0, :frame-blob-key "f0", ...}],
              :bgm-blob-key nil, :width 1280, :height 720, :fps 30}, ...}
```

Stop the server with Ctrl-C when you are done.

## 8. The offline pipeline — a real mp4

**Do not use the `clojure -M:dev` form printed in `clj/README.md`.** Outside
the west checkout it cannot resolve its own dependency:

```
Error building classpath. Local lib io.github.kotoba-lang/douga not found: ../../../kotoba-lang/douga
```

The plain form works anywhere, because the git deps in `deps.edn` are already
pinned:

```bash
cd clj
clojure -M -m dougaka.pipeline \
  examples/india-ludo-live-action-internal.edn target/qs-probe
```

```
[murakumo] bgm skipped — no generation token
[kotobase] skipped …motion-v2.mp4 — no KOTOBASE_ARCHIVE_TOKEN in env
[kotobase] skipped …motion-v2.srt — no KOTOBASE_ARCHIVE_TOKEN in env
video : target/qs-probe/india-ludo-live-action-internal-motion-v2.mp4
srt   : target/qs-probe/india-ludo-live-action-internal-motion-v2.srt
shots : 3
codec_name=h264   width=720   height=1280   codec_name=aac   duration=9.042667
```

That is a real 1.8 MB vertical H.264/AAC file plus a 3-cue SRT, produced with
**no fleet token, no ComfyUI pod and no VOICEVOX** — the degrade legs are the
point. The skip lines are the honest report: with
`MURAKUMO_GENERATION_TOKEN` and `KOTOBASE_ARCHIVE_TOKEN` set, those legs run
instead.

## 9. The silence in step 8 is deliberate — prove the narration chain

Every shot in that example plan carries `:voice? false`, so its audio track is
digital silence, and measuring it says so:

```bash
cd clj/target/qs-probe
ffmpeg -hide_banner -i shot_00000.wav -af volumedetect -f null - 2>&1 | grep mean_volume
```
```
mean_volume: -91.0 dB          # all three shots, byte-identical
```

Flip the flag and the `VOICEVOX → macOS say → silence` chain runs for real:

```bash
cd clj
sed 's/:voice? false/:voice? true/g' \
  examples/india-ludo-live-action-internal.edn > /tmp/qs-voiced.edn
clojure -M -m dougaka.pipeline /tmp/qs-voiced.edn target/qs-voiced
```

```
[murakumo] narration skipped — no generation token     ← rung 1, once per shot (3×)
[murakumo] narration skipped — no generation token
[murakumo] narration skipped — no generation token
```
```bash
cd target/qs-voiced && ls voice_*.wav
for w in shot_0000*.wav; do ffmpeg -hide_banner -i $w -af volumedetect -f null - 2>&1 | grep mean_volume; done
```
```
voice_00000.wav  voice_00001.wav  voice_00002.wav
mean_volume: -26.0 dB
mean_volume: -25.2 dB
mean_volume: -26.4 dB          # three *distinct* wavs — spoken, not silence
```

`-91 dB` vs `-26 dB` is the check worth keeping: it distinguishes "the
pipeline ran" from "the pipeline produced narration". No VOICEVOX was running,
so this is the `say` rung — with a VOICEVOX engine on `VOICEVOX_URL`, rung 2
is taken instead and roles are cast through `dougaka.voice/speaker-map`.

## 10. What this quickstart does **not** cover

- **Deploying the appview.** `appview/ai-gftd-wasm-dougaka-dgk4k7x2/wrangler.jsonc`
  names `"main": "src/app.cljc"` and assets `./svelte/build`, and this repo
  contains neither — the directory holds only that config and
  `magatama.jsonld`. No deploy was attempted.
- **The fleet and archive legs end to end.** Both were exercised only in their
  skip branch; nothing here proves a live murakumo generation or a kotobase
  `PUT /ipfs/:cid`.
- **`fabric/`.** `guardian.kotoba` is `(defn main [] 6)` against an empty
  host-capability policy. There is no governor to walk.
