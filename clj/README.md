# dougaka.gftd.ai — Clojure edition

CLJ graph server replacing the old Python `lg_dougaka` LangGraph runtime.

Surface:

- `GET /health`, `/ok`
- `POST /runs` with `assistant_id: "render"`
- `POST /xrpc/ai.gftd.apps.dougaka.render`

The active CLJ render graph currently performs deterministic timeline ->
ffmpeg-plan assembly and returns the plan. It does not run ffmpeg, upload to B2,
or write the D1 render log in-process.

```bash
clojure -M:test
PORT=8000 clojure -M:run

# Internal live-action demo using a reviewed local keyframe
clojure -M:dev -m dougaka.pipeline \
  examples/india-ludo-live-action-internal.edn \
  target/dougaka/india-ludo-live-action-internal-v1
```

Offline episode shots may provide `:image-path` instead of invoking the
configured image gateway. This is intended for reviewed internal-demo assets;
the path is copied into the run directory before ffmpeg assembly.
