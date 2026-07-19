(ns lg-dougaka.graphs.render
  "dougaka `render` graph — clj twin of lg_dougaka/graphs/render.py (ADR-2606280030).

  Faithful langgraph-clj port of the 5-node pipeline:

      START → render → upload_video → write_record → cleanup → audit → END

  Each node fn takes the state map and returns a partial update map (default
  channel reducer = replace), exactly like the Python `_node_*` coroutines.
  State keys mirror the Python TypedDict verbatim (snake_case keywords, so the
  scene-building logic is byte-faithful to the wire field names):

      :video_id :timeline :temp_dir :output_mp4 :blob_key :blob_url :error

  DEVIATIONS (documented, not silent):
    * langgraph-clj has no RetryPolicy — the Python `retry_policy=RetryPolicy(...)`
      on render/upload/write is dropped (topology + behaviour otherwise identical).
    * The actual mp4 render (kotodama video_compose SDK = TTS + image-gen + PIL
      compositor + ffmpeg), the B2 S3v4-signed upload (boto3) and the kotoba
      record INSERT (psycopg) are native/Python-only integrations with no clj
      twin. They are kept as INJECTABLE BOUNDARY fns (the actor swap pattern):
      `*render-fn*` / `*upload-fn*` / `*write-record-fn*`. Defaults fail-open
      exactly like the Python (skip-on-no-creds for upload; non-fatal for
      write_record), so the graph runs end-to-end under bb. Wire a real renderer
      by rebinding the vars (or via env when the SDK is reachable)."
  (:require [langgraph.graph :as g]
            [lg-dougaka.audit :as audit]
            [clojure.string :as str]))

(defn- truncate [s n] (subs s 0 (min n (count s))))

;; ── scene reconstruction (faithful port of _build_scenes_for_compose) ───────
(defn build-scenes-for-compose
  "Reconstruct the nested scene+lines structure for the video_compose renderer.
  Groups timeline lines by scene_index, sorts by line_index, and projects each
  into {:speaker :text :emotion}. Pure — verbatim port of the Python helper."
  [timeline]
  (let [raw-scenes (or (:scenes timeline) [])
        raw-lines (or (:lines timeline) [])
        lines-by-scene (reduce (fn [acc line]
                                 (let [si (or (:scene_index line) (:sceneIndex line) 0)]
                                   (update acc si (fnil conj []) line)))
                               {} raw-lines)]
    (mapv (fn [scene]
            (let [idx (or (:index scene) (:scene_index scene) 0)
                  scene-lines (sort-by #(or (:line_index %) (:lineIndex %) 0)
                                       (get lines-by-scene idx []))]
              {:location (or (:location scene) "")
               :action (or (:action scene) "")
               :lines (mapv (fn [l] {:speaker (or (:speaker l) "left")
                                     :text (or (:text l) "")
                                     :emotion (or (:emotion l) "normal")})
                            scene-lines)}))
          raw-scenes)))

;; ── injectable boundaries (default fail-open, mirroring the Python try/except) ──
(defn ^:dynamic *render-fn*
  "Boundary: scenes + temp-dir → path to the produced mp4 (or throws). Default is
  the unconfigured boundary — there is no pure-clj TTS/image/ffmpeg renderer, so
  it signals 'not configured' (the node turns this into a graph :error, exactly
  like a Python render_video exception). Rebind to a real renderer to produce mp4."
  [_scenes _temp-dir _opts]
  (throw (ex-info "render_video: external renderer not configured (kotodama video_compose SDK boundary)" {})))

(defn ^:dynamic *upload-fn*
  "Boundary: (mp4-path b2-key) → presigned URL string (or throws). Default throws
  (no S3v4 signer in pure clj); only invoked when B2 creds are present — with no
  creds the node skips upload, faithful to render.py."
  [_mp4-path _b2-key]
  (throw (ex-info "upload_video: external B2 uploader not configured (boto3 S3v4 boundary)" {})))

(defn ^:dynamic *write-record-fn*
  "Boundary: row-map → side-effect INSERT. Default no-op (non-fatal), mirroring
  the Python write_record which swallows failures."
  [_row]
  nil)

;; ── nodes ───────────────────────────────────────────────────────────────────
(defn node-render
  "Build scenes from the timeline and invoke the renderer boundary → mp4.
  Short-circuits on a pre-existing :error (like every Python node)."
  [state]
  (if (:error state)
    {}
    (let [timeline (or (:timeline state) {})]
      (if (empty? timeline)
        {:error "timeline is empty"}
        (let [temp-dir (str (System/getProperty "java.io.tmpdir") "/dougaka_" (System/nanoTime))
              output-mp4 (str temp-dir "/output.mp4")
              scenes (build-scenes-for-compose timeline)]
          (.mkdirs (java.io.File. temp-dir))
          (if (empty? scenes)
            {:error "no scenes in timeline" :temp_dir temp-dir}
            (try
              (let [{:keys [murakumo-tts-url murakumo-image-url]} (audit/config state)
                    produced (*render-fn* scenes output-mp4
                                          {:tts-url murakumo-tts-url
                                           :image-url murakumo-image-url})
                    path (or produced output-mp4)]
                (if (.exists (java.io.File. path))
                  {:temp_dir temp-dir :output_mp4 path}
                  {:error "render produced no output file" :temp_dir temp-dir}))
              (catch Exception e
                {:error (truncate (str "render: " (.getMessage e)) 400)
                 :temp_dir temp-dir}))))))))

(defn node-upload-video
  "Upload output.mp4 to B2 under renders/{video_id}/output.mp4. Skips (returns
  empty blob key/url) when creds are absent — faithful to render.py."
  [state]
  (if (or (:error state) (not (:output_mp4 state)))
    {}
    (let [{:keys [b2-key-id b2-app-key b2-endpoint-url b2-bucket]} (audit/config state)]
    (if-not (and (seq b2-key-id) (seq b2-app-key))
      {:blob_key "" :blob_url ""}
      (let [video-id (or (:video_id state) "unknown")
            b2-key (str "renders/" video-id "/output.mp4")]
        (try
          (let [presigned (*upload-fn* (:output_mp4 state) b2-key)
                blob-url (or presigned (str b2-endpoint-url "/" b2-bucket "/" b2-key))]
            {:blob_key b2-key :blob_url blob-url})
          (catch Exception e
            {:error (truncate (str "upload: " (.getMessage e)) 300)})))))))

(defn node-write-record
  "INSERT a vertex_dougaka_render row (non-fatal). Faithful to render.py: the
  status is derived from :error, the vertex_id is the at:// URI, failures are
  swallowed and the node returns {}."
  [state]
  (let [video-id (or (:video_id state) "")
        status (if (:error state) "error" "done")
        error-msg (or (:error state) "")
        created-at (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
                            (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC))
        vertex-id (str "at://dougaka.etzhayyim.com/com.etzhayyim.apps.dougaka.render/" video-id)]
    (try
      (*write-record-fn*
       {:vertex_id vertex-id
        :video_id video-id
        :actor_did (:app-did (audit/config state))
        :org_did "anon"
        :blob_key (or (:blob_key state) "")
        :blob_url (or (:blob_url state) "")
        :status status
        :error_msg (if (seq error-msg) (truncate error-msg 500) "")
        :created_at created-at})
      (catch Exception e
        (binding [*out* *err*] (println (str "write_record non-fatal: " (.getMessage e))))))
    {}))

(defn node-cleanup
  "Remove the temp directory (best-effort, non-fatal)."
  [state]
  (let [temp-dir (:temp_dir state)]
    (when (and temp-dir (.isDirectory (java.io.File. temp-dir)))
      (try
        (doseq [f (reverse (file-seq (java.io.File. temp-dir)))] (.delete f))
        (catch Exception e
          (binding [*out* *err*] (println (str "cleanup temp_dir failed: " (.getMessage e))))))))
  {})

(defn node-audit
  "Fire-and-forget OCEL audit event (faithful to render.py emit_audit_bg)."
  [state]
  (audit/emit-audit-bg
   state
   {:actor (:app-did (audit/config state))
    :activity "dougaka.render"
    :object-id (str "render:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
    :object-type "dougaka.render"
    :attributes {:videoId (:video_id state)
                 :blobKey (:blob_key state)
                 :ok (not (boolean (:error state)))}})
  {})

;; ── graph ─────────────────────────────────────────────────────────────────
(defn build
  "Compile the render StateGraph (render → upload_video → write_record →
  cleanup → audit), topology identical to render.py."
  []
  (-> (g/state-graph)
      (g/add-node :render node-render)
      (g/add-node :upload_video node-upload-video)
      (g/add-node :write_record node-write-record)
      (g/add-node :cleanup node-cleanup)
      (g/add-node :audit node-audit)
      (g/set-entry-point :render)
      (g/add-edge :render :upload_video)
      (g/add-edge :upload_video :write_record)
      (g/add-edge :write_record :cleanup)
      (g/add-edge :cleanup :audit)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
