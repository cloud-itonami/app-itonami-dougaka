# ai-gftd-dougaka

**動画家 (dougaka) — "one who makes moving pictures".** The name is a metaphor
and says nothing about what is in here, so, plainly: this repo is a **Clojure
video-production service for `dougaka.gftd.ai`** — an HTTP graph server that
turns a timeline into an ffmpeg *render plan*, plus an offline executor that
turns a committed episode plan into a **real vertical mp4 + sidecar SRT**.

It replaces the Python `lg_dougaka` LangGraph runtime.

## Where the boundary is

`README.md.edn` is this repo's machine record (a `:readme/*` datom set, not
prose). The same boundary in prose:

| repo | owns |
|---|---|
| **`cloud-itonami/ai-gftd-dougaka`** (here) | the HTTP surface, the two graphs, and the offline production pipeline |
| `cloud-itonami/app-dougaka` | the *earlier* `lg_dougaka` port of the same service — a separate `lg_dougaka.*` namespace tree with its own `/runs` + `/xrpc` surface |
| `kotoba-lang/douga` | the craft library — timeline → ffmpeg command builders |
| `kotoba-lang/comfyui` | the shared ComfyUI gateway (keyframes) |
| `kotoba-lang/murakumo-generation` | the shared murakumo generation client (video / voice / music / sfx) |

Both this repo and `app-dougaka` serve a `render` graph over `/runs` and
`/xrpc`. This one is the CLJ edition its own README calls the replacement;
neither repo currently says which host resolves to which. Check
`appview/*/wrangler.jsonc` before assuming.

## What is actually in here

```
clj/src/dougaka/server.cljc          HTTP surface (reitit-ring + http-kit), EDN or JSON
clj/src/dougaka/graphs/registry.cljc nsid → graph handler table
clj/src/dougaka/graphs/render.cljc   render graph — timeline → ffmpeg render plan (plans only)
clj/src/dougaka/pipeline.clj         offline executor — episode plan → real mp4 (subtitles burned in) + SRT + legs.edn (runs ffmpeg)
clj/resources/generation.edn         which fleet model each leg asks for (catalog defaults + their :status)
clj/src/dougaka/comfy.cljc           keyframes via the shared ComfyUI gateway (degrades to a placeholder)
clj/src/dougaka/murakumo.clj         murakumo fleet generation: video / voice / music / sfx
clj/src/dougaka/voice.clj            narration: VOICEVOX → macOS `say` → silence
clj/src/dougaka/subtitle.cljc        sidecar SRT from a flat shot list (pure)
clj/src/dougaka/kotobase.cljc        pure raw-CIDv1 content addressing
clj/src/dougaka/archive.clj          kotobase.net archive I/O (mirror, never a gate)
clj/test/                            7 test namespaces — 29 tests / 130 assertions (one runs ffmpeg)
```

`legs.edn` (written next to the mp4, 2026-08-22) is the producer's own report of
what each generative step did — per-shot `:murakumo` / `:comfy` / `:placeholder`
for the image, `:murakumo` / `:local` / `:silent` for the voice, bed, cues — in
the shape `kotoba-lang/loop-ka-production` grades with `record --legs`. The
loop reads that file, not stdout; a cut of flat cards still prints `video :`.

**Burned-in subtitles and two aspect ratios (2026-08-22).** The last pass
burns the SRT into the picture (`douga.ffmpeg/burn-subtitles-cmd`, libass,
lower third) so a short watched with sound off still says its line — the
MoneyPrinterTurbo-style presentation; the sidecar SRT stays canonical and
`legs.edn` says `:subtitles :burned` or `:sidecar` (ffmpeg without libass).
`--aspect portrait|landscape` (or `DOUGAKA_ASPECT`) picks 720x1280 or
1280x720; `--no-burn` keeps the cut flat. The suite runs ffmpeg for real and
checks the burn by pixel stddev of a frame (flat card ≈0 before, >5 after).

**Every fleet leg now states its model.** The gateway refuses a request
without one (`murakumo_billing_underspecified`, measured 2026-08-22) and the
shared client only sends what it is given — so every fleet leg had been
degrading without saying why. `resources/generation.edn` carries the public
catalog's defaults and their `:status`; `DOUGAKA_*_MODEL` env or a per-shot
`:video-model` / `:voice-model` overrides. Whether a leg then RUNS is the
fleet's: the same day the ledger refused the proxy's own service token at
`/infer/spend` (401), which fails every billed generation closed, not just
ours — see the comment in that file.

**The two legs are deliberately different.** The server *plans* and never
encodes; `dougaka.pipeline` is the explicit offline encode leg where ffmpeg
lives. Every generative step degrades rather than failing, and the result map
names which leg degraded, so a placeholder never reads as success.

## Start here

**`docs/operator-quickstart.md`** — every command in it was run against this
tree and its output recorded, including the things that do not work.

## Known gaps (measured 2026-08-19, not fixed here)

- **`/xrpc/:nsid` is not behind `LG_API_KEY`; `/runs` is.** With the key set,
  `POST /runs` without it returns 401, while `POST /xrpc/ai.gftd.apps.dougaka.render`
  without it returns 200 and runs the same graph. Reproduction: quickstart steps 4 and 6.
- **`appview/ai-gftd-wasm-dougaka-dgk4k7x2/wrangler.jsonc` cannot deploy as
  written.** It names `"main": "src/app.cljc"` and an assets directory
  `./svelte/build`; neither exists in this repo — the appview directory holds
  exactly two files, that config and `magatama.jsonld`.
- **The `:dev` alias is not portable.** `clj/README.md` documents
  `clojure -M:dev -m dougaka.pipeline`, but `:dev` overrides `douga` with the
  relative `../../../kotoba-lang/douga` (resolves only inside the west
  checkout) and `comfyui` with an absolute path under one developer's home.
  Outside that layout it fails at classpath build. Use the plain form — see
  quickstart step 8.
- **`fabric/` is a scaffold, not a governor.** `guardian.kotoba` is
  `(defn main [] 6)`, `host-policy.edn` is `{:host-caps [] :default :deny}`,
  and `evidence.edn` records `:verified true :result 6` for exactly that.
- **90 build artifacts are tracked in git** — `clj/.cpcache/` and
  `clj/target/dougaka/demo-1/` (including committed mp4s). `.gitignore` covers
  neither.
