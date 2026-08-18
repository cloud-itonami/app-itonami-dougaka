# app-dougaka

**動画家 (dougaka) — the video-render application.** The name is a metaphor
("one who makes moving pictures") and does not say what the repo contains, so:
this repo is the **HTTP server and LangGraph state machine that turns a
timeline into a rendered mp4**, ported from a Python LangGraph service to
`langgraph-clj` (ADR-2606280030).

It is a *standalone app artifact*: it owns the request surface and the graph
topology, and it owns none of the craft.

## Where the boundary is

`README.edn` is the machine record for this repo; the same boundary in prose:

| repo | owns |
|---|---|
| **`cloud-itonami/app-dougaka`** (here) | the HTTP surface, the two graphs, the node topology |
| `cloud-itonami/ai-gftd-dougaka` | the production family this app serves |
| `kotoba-lang/douga` | the craft library — video-assembly (動画) planning |

## What is actually in here

```
lg/src/lg_dougaka/server.cljc           HTTP surface (pure {:status :body} fns + httpkit wiring)
lg/src/lg_dougaka/graphs/health.cljc    health graph:  START → ping → END
lg/src/lg_dougaka/graphs/render.cljc    render graph:  render → upload_video → write_record → cleanup → audit
lg/src/lg_dougaka/audit.cljc            fire-and-forget OCEL audit emit (non-fatal)
lg/tests/lg_dougaka/test_smoke.cljc     clojure.test suite (23 tests / 58 assertions)
lg/run_tests.clj                        test runner  (repo rule: .clj, not .sh)
```

HTTP surface:

| method | path | notes |
|---|---|---|
| `GET` | `/ok`, `/health` | liveness; returns the graph names |
| `POST` | `/runs` | invoke `GRAPHS[assistant_id]`; gated by `x-api-key` when `LG_API_KEY` is set |
| `POST` | `/xrpc/{nsid}` | NSID → graph (`com.etzhayyim.apps.dougaka.render` → `render`); unauthenticated |

## What is deliberately *not* implemented here

Three boundaries in `graphs/render.cljc` are injectable vars whose defaults do
not do the work. This is the actor swap pattern, not an oversight — each names
an integration with no pure-clj twin:

- `*render-fn*` — TTS + image-gen + compositor + ffmpeg. **Default throws**, so
  an unconfigured deployment reports `render: … external renderer not configured`
  rather than silently producing nothing.
- `*upload-fn*` — B2 S3v4-signed upload. Only reached when B2 credentials are
  present; with no credentials `upload_video` skips and returns empty
  `blob_key` / `blob_url`.
- `*write-record-fn*` — the kotoba record INSERT. **Default is a no-op** and
  failures are swallowed, faithful to the Python original.

`audit/default-config` leaves `:murakumo-tts-url` / `:murakumo-image-url`
unset on purpose; the docstring there records why they are *not* routed through
the `murakumo-main` alias (the alias is a chat entry and says nothing about
speech synthesis or image generation).

`langgraph-clj` has no `RetryPolicy`, so the Python `retry_policy=` on
render/upload/write is dropped. Topology and behaviour are otherwise identical.

## Running it

See **[docs/operator-quickstart.md](docs/operator-quickstart.md)** — every
command there has been executed against this tree, with its real output
recorded. It also documents one reproducible defect: **the render graph cannot
be driven over HTTP today** (the JSON body arrives with string keys and every
node reads keyword keys, so `render` short-circuits with `timeline is empty`).
The graph itself is fine when driven in-process.

## Licence

Apache License 2.0 with the etzhayyim Charter Compliance Rider v3.1 — see
`NOTICE`. Note that `NOTICE` points at a `CHARTER-RIDER.md` that is not present
in this repo; the rider lives with the charter ADRs named there
(ADR-2605192200 + ADR-2606062100 + ADR-2606082400).
