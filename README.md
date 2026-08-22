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
clj/src/dougaka/pipeline.clj         offline executor — episode plan → real mp4 + SRT + legs.edn (runs ffmpeg)
clj/src/dougaka/comfy.cljc           keyframes via the shared ComfyUI gateway (degrades to a placeholder)
clj/src/dougaka/murakumo.clj         murakumo fleet generation: video / voice / music / sfx
clj/src/dougaka/voice.clj            narration: VOICEVOX → macOS `say` → silence
clj/src/dougaka/subtitle.cljc        sidecar SRT from a flat shot list (pure)
clj/src/dougaka/kotobase.cljc        pure raw-CIDv1 content addressing
clj/src/dougaka/archive.clj          kotobase.net archive I/O (mirror, never a gate)
clj/test/                            7 test namespaces — 25 tests / 96 assertions
```

`legs.edn` (written next to the mp4, 2026-08-22) is the producer's own report of
what each generative step did — per-shot `:murakumo` / `:comfy` / `:placeholder`
for the image, `:murakumo` / `:local` / `:silent` for the voice, bed, cues — in
the shape `kotoba-lang/loop-ka-production` grades with `record --legs`. The
loop reads that file, not stdout; a cut of flat cards still prints `video :`.

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
