# Operator quickstart — app-dougaka

Every command below was executed against this tree
(`65c0f15`, macOS, `babashka v1.12.218`) and the output shown is the output it
produced. Where a step reveals a defect, the defect is written down rather than
smoothed over.

All commands run from **`lg/`**, which carries its own scoped `bb.edn`
(`:paths ["src" "tests"]`, one external dep: `langgraph-clj`).

```bash
cd lg
```

## 1. Run the test suite

```bash
bb run_tests.clj
```

```
Testing lg-dougaka.test-smoke

Ran 23 tests containing 58 assertions.
0 failures, 0 errors.
```

Exit status is `0` on green, `1` on any failure or error. This is the gate to
run before and after any change — if it is not green *before* you start, do not
attribute the failure to your change.

## 2. Start the server

`server/-main` deliberately throws: it will not invent a server capability, a
port or an API key. A host supplies them. Under `bb`, `org.httpkit.server` is
built in, so a local host is one expression:

```bash
PORT=8791 LG_API_KEY=dev-key bb -e '
(require (quote [lg-dougaka.server :as server]) (quote [org.httpkit.server :as httpkit]))
(server/run-server-with httpkit/run-server (Integer/parseInt (System/getenv "PORT")) (System/getenv "LG_API_KEY"))
(println "lg-dougaka listening on" (System/getenv "PORT") (vec (keys server/GRAPHS)))
@(promise)'
```

```
lg-dougaka listening on 8791 [health render]
```

Startup takes a few seconds (`bb` resolves the `langgraph-clj` git dep on first
run). Leaving `LG_API_KEY` unset or empty makes `/runs` **unauthenticated** —
`check-api-key` only rejects when a key is configured.

## 3. Liveness

```bash
curl -sS http://127.0.0.1:8791/health     # /ok is the same handler
```

```json
{"ok":true,"graphs":["health","render"]}
```

`200`. Use this as the readiness probe: it reports the graph names actually
registered, so a deployment that lost a graph is visible here.

## 4. Invoke a graph — `POST /runs`

```bash
curl -sS -X POST http://127.0.0.1:8791/runs \
  -H 'content-type: application/json' -H 'x-api-key: dev-key' \
  -d '{"assistant_id":"health","input":{}}'
```

```json
{"host-config":{},"ok":true}
```

`200`. The response is the final graph state, so `:host-config` (injected by the
handler) is visible in it.

Wrong key:

```bash
curl -sS -X POST http://127.0.0.1:8791/runs \
  -H 'content-type: application/json' -H 'x-api-key: wrong' \
  -d '{"assistant_id":"health","input":{}}'
```

```json
{"detail":"invalid api key"}
```

`401`.

Unknown graph:

```bash
curl -sS -X POST http://127.0.0.1:8791/runs \
  -H 'content-type: application/json' -H 'x-api-key: dev-key' \
  -d '{"assistant_id":"nope","input":{}}'
```

```json
{"error":"unknown graph: nope"}
```

`404`.

## 5. The XRPC surface

`POST /xrpc/{nsid}` maps an NSID to a graph and is **not** key-gated. Only
`com.etzhayyim.apps.dougaka.render` is mapped:

```bash
curl -sS -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.dougaka.unknown \
  -H 'content-type: application/json' -d '{}'
```

```json
{"error":"unknown nsid: com.etzhayyim.apps.dougaka.unknown"}
```

`404`. An unmapped path is `{"error":"not found"}`, also `404`.

## 6. Drive the render graph (in-process)

The renderer boundary is unconfigured by default, and says so:

```bash
bb -e '
(require (quote [lg-dougaka.graphs.render :as render]) (quote [langgraph.graph :as g]))
(def timeline {:scenes [{:index 0 :location "studio" :action "intro"}]
               :lines  [{:scene_index 0 :line_index 0 :speaker "left" :text "hello" :emotion "normal"}]})
(prn (render/build-scenes-for-compose timeline))
(prn (select-keys (g/invoke render/GRAPH {:video_id "demo-1" :timeline timeline}) [:error :output_mp4 :blob_key]))'
```

```clojure
[{:location "studio", :action "intro", :lines [{:speaker "left", :text "hello", :emotion "normal"}]}]
{:error "render: render_video: external renderer not configured (kotodama video_compose SDK boundary)"}
```

The scene projection is pure and works; the graph reaches `*render-fn*` and
turns its refusal into a graph `:error` instead of a crash.

Bind a renderer and the pipeline completes end to end:

```bash
bb -e '
(require (quote [lg-dougaka.graphs.render :as render]) (quote [langgraph.graph :as g]))
(def timeline {:scenes [{:index 0 :location "studio" :action "intro"}]
               :lines  [{:scene_index 0 :line_index 0 :speaker "left" :text "hello" :emotion "normal"}]})
(binding [render/*render-fn* (fn [_scenes out _opts] (spit out "fake-mp4") out)]
  (prn (select-keys (g/invoke render/GRAPH {:video_id "demo-1" :timeline timeline})
                    [:error :output_mp4 :blob_key :blob_url])))'
```

```clojure
{:output_mp4 "/var/folders/.../dougaka_1001063731800500/output.mp4", :blob_key "", :blob_url ""}
```

No `:error`. `blob_key` / `blob_url` are empty because no B2 credentials are
configured, which is the documented skip — not a failure.

## 7. Known defect — the render graph is unreachable over HTTP

**The same timeline sent over HTTP does not render.** It short-circuits on the
first node while the timeline is plainly present in the echoed state:

```bash
curl -sS -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.dougaka.render \
  -H 'content-type: application/json' \
  -d '{"video_id":"demo-1","timeline":{"scenes":[{"index":0,"location":"studio","action":"intro"}],"lines":[{"scene_index":0,"line_index":0,"speaker":"left","text":"hello","emotion":"normal"}]}}'
```

```json
{"video_id":"demo-1","timeline":{"scenes":[…],"lines":[…]},"host-config":{},"error":"timeline is empty"}
```

`200` — the request is *accepted*, and the failure is reported inside the state.

Cause: `server/read-json-body` calls `json/parse-string` without keywordizing,
so the state arrives with **string** keys, while every node in
`graphs/render.cljc` reads **keyword** keys (`(:timeline state)`,
`(:video_id state)`). `node-render` therefore sees no timeline and returns
`{:error "timeline is empty"}`. `POST /runs` with `assistant_id: render` has
the same shape of problem.

This is not a one-character fix: `runs-handler` and `xrpc-handler` read the
*envelope* with string keys on purpose (`(get body "assistant_id")`,
`NSID-MAP`), so keywordizing the whole body would break the envelope while
fixing the state. The seam has to be drawn between envelope and state, and the
fix needs a test that fails first — the current suite exercises the handlers
and the graph, but never the two joined by a JSON body, which is exactly why
this survived.

The `health` graph is unaffected: it ignores its input.

## 8. Stopping

The one-liner in step 2 parks on `@(promise)`; `Ctrl-C` stops it, or kill the
`bb` process by the PID you started. Nothing is written outside
`java.io.tmpdir`, and `node-cleanup` removes its own temp directory.
