(ns dougaka.murakumo-job
  "Pure request/response logic for murakumo.cloud's public generation API
  (`POST /api/v1/generation`, cloud-murakumo.public-generation) — the fleet
  that owns every GPU step of a dougaka production: the moving image for a
  shot (:video), the narration (:voice), the music bed (:music) and the
  one-shot cues (:sfx).

  No HTTP, no clock, no filesystem here — dougaka.murakumo is the JVM I/O
  side, the same .cljc/.clj split the rest of this repo uses. Keeping the
  bodies pure is what lets the shot -> job mapping be tested without a token
  or a GPU.

  Model ids are deliberately NOT hardcoded as defaults: murakumo resolves an
  omitted `model` to the function's own `:default` from murakumo.edn, so a
  fleet-side model swap reaches this pipeline without a release here
  (CLAUDE.md's murakumo-main rule, ADR-2607173100, applied to generation).
  A caller may still pin one explicitly."
  (:require [clojure.string :as str]
            [dougaka.kotobase :as kb]))

(def default-endpoint
  "generation.murakumo.cloud is the scoped generation proxy — the host whose
  gate accepts a `generation`-scope token. Measured 2026-07-29: the same token
  is 401 on murakumo.cloud (a different worker with its own signing secret),
  so this is not interchangeable with the other murakumo hostnames."
  "https://generation.murakumo.cloud/api/v1/generation")

(defn endpoint
  "Base generation endpoint. `override` (env/opt) wins so a staging fleet can
  be pointed at without touching callers."
  [override]
  (let [s (str/trim (str (or override "")))]
    (if (str/blank? s) default-endpoint (str/replace s #"/+$" ""))))

(defn job-url
  "Status URL for a submitted job id."
  [base job-id]
  (str (str/replace base #"/+$" "") "/jobs/" job-id))

(defn- ms [seconds]
  (long (Math/round (* 1000.0 (double (or seconds 0))))))

(defn- prune [m] (into {} (remove (comp nil? val)) m))

;; ── the fleet's actual accepted ranges (measured 2026-07-29 against
;; generation.murakumo.cloud; the server states these in its 400 bodies) ─────

(def dimension-step 32)
(def dimension-min 256)
(def dimension-max 1280)

(defn snap-dimension
  "px -> the nearest accepted size: a multiple of 32 within 256..1280.
  `params.width must be a multiple of 32 in 256..1280`. A vertical short's
  720 is NOT a multiple of 32, so asking for the project's own geometry is a
  400. We round to the nearest step (720 -> 736), so the clip is generated
  slightly WIDER than the frame and scaled down — never upscaled."
  [px]
  (let [v (long (Math/round (/ (double (or px dimension-min)) dimension-step)))
        v (* dimension-step v)]
    (max dimension-min (min dimension-max v))))

(def frames-min 9)
(def frames-max 121)

(defn snap-frames
  "n -> the nearest accepted frame count: 8n+1 within 9..121.
  `params.frames must be 8n+1 in 9..121`. At the fleet's ~24fps that caps one
  generated clip at about 5 seconds, which is why a longer shot holds its last
  frame rather than being generated end to end."
  [n]
  (let [n (long (max frames-min (min frames-max (long (or n frames-min)))))
        k (long (Math/round (/ (double (dec n)) 8.0)))]
    (max frames-min (min frames-max (inc (* 8 k))))))

(def generation-fps
  "The frame rate the fleet's video models actually emit (ltx-2.3 measured at
  24fps, ADR-2607171100). Used only to turn a shot's seconds into a frame
  request; the segment builder re-times whatever comes back to the project fps."
  24)

(def default-locale
  "BCP-47. Measured: \"ja\" and \"ja_JP\" are both rejected as unsupported."
  "ja-JP")

(defn video-body
  "A shot -> a text-to-video (or image-to-video, when :image-url is given)
  job body. `:prompt` is the shot's own art direction as written in the
  episode plan; nothing is invented here.

  Geometry and frame count are snapped to what the fleet accepts rather than
  passed through, so a plan written in project units (720x1280, 7s) does not
  have to know the model's constraints."
  [{:keys [prompt seconds width height seed model image-url]
    :or {width 720 height 1280}}]
  (prune
   {:type "video"
    :model model
    :input (prune {:prompt prompt :image image-url})
    :params (prune {:width (snap-dimension width)
                    :height (snap-dimension height)
                    ;; the fleet's video functions are frame-counted, not
                    ;; second-counted.
                    :frames (snap-frames
                             (when seconds
                               (long (Math/ceil (* generation-fps (double seconds))))))
                    :seed seed})}))

(defn voice-body
  "A subtitle line -> a narration job body. `:voice` carries the plan's role
  hint through to the fleet TTS so the cast stays distinguishable; `:locale`
  defaults to ja-JP because every episode catalog in this repo is Japanese."
  [{:keys [text voice locale model] :or {locale default-locale}}]
  (prune
   {:type "voice"
    :model model
    :input {:text text}
    :params (prune {:locale locale :voice voice})}))

(defn sound-body
  "A music bed or a one-shot cue. `kind` is :music or :sfx — the public API
  discriminates on `sound_kind`, and only \"music\" selects the music
  function, so anything else is dispatched as sfx by the server."
  [{:keys [prompt kind seconds loop? model] :or {kind :sfx}}]
  (prune
   {:type "sound"
    :model model
    :input {:prompt prompt}
    :params (prune {:sound_kind (name kind)
                    :duration_ms (when seconds (ms seconds))
                    :loop (boolean loop?)})}))

;; ── responses ───────────────────────────────────────────────────────────────

(defn- getv [m k] (or (get m k) (get m (name k))))

(defn submitted-id
  "Submit response -> job id, or nil when the fleet did not accept the job.
  Fail closed: a body with no jobId is not a job, however 200 it looked."
  [body]
  (let [id (str (or (getv body :jobId) ""))]
    (when-not (str/blank? id) id)))

(def terminal-statuses #{"done" "failed"})

(defn job-status [body] (str (or (getv body :status) "")))

(defn done? [body] (= "done" (job-status body)))
(defn failed? [body] (= "failed" (job-status body)))
(defn terminal? [body] (contains? terminal-statuses (job-status body)))

(defn- artifact-record [body]
  (or (first (or (getv body :artifacts) []))
      (when-let [u (getv body :artifactUrl)] {:url u})))

(defn rebase-host
  "Put `url`'s path onto `base`'s scheme+host.

  The fleet advertises artifact and status URLs on murakumo.cloud regardless
  of which host accepted the job, and that host is gated by a DIFFERENT
  signing secret — measured 2026-07-29: a generation-scope token that gets a
  202 on generation.murakumo.cloud is 401 on murakumo.cloud. Following the
  advertised URL verbatim would therefore fail on every artifact. We keep the
  path (it carries the job id) and speak to the host we are authenticated to."
  [base url]
  (let [u (str (or url ""))]
    (if (str/blank? u)
      nil
      (let [origin (second (re-find #"^(https?://[^/]+)" (str base)))
            path (or (second (re-find #"^https?://[^/]+(/.*)$" u)) u)]
        (if (and origin (str/starts-with? path "/")) (str origin path) u)))))

(defn artifact-url
  "Status response -> the first artifact's fetchable URL rebased onto `base`,
  or nil. Falls back to the top-level :artifactUrl the API also emits."
  [base body]
  (let [a (artifact-record body)]
    (rebase-host base (or (:url a) (getv a :url)))))

(defn artifact-sha256
  "Status response -> the artifact's declared sha2-256 hex, or nil.

  The API states integrity as `contentHash: \"sha256:<hex>\"`. Having it lets
  a download be verified rather than trusted, and — because kotobase names
  objects by exactly this digest — the fleet's content hash IS the CID the
  artifact will have in storage. Compute and storage agree on the name for
  free; no re-hash, no second naming scheme."
  [body]
  (let [a (artifact-record body)
        h (str (or (:contentHash a) (getv a :contentHash) ""))]
    (when-let [hex (second (re-find #"(?i)^sha256:([0-9a-f]{64})$" h))]
      (str/lower-case hex))))

(defn artifact-cid
  "Status response -> the artifact's raw CIDv1, derived from its declared
  sha2-256 (or taken verbatim if the API ever hands one back directly)."
  [body]
  (let [a (artifact-record body)
        cid (or (:cid a) (getv a :cid))]
    (if-not (str/blank? (str cid))
      (str cid)
      (kb/sha256-hex->cid (artifact-sha256 body)))))

(defn job-error
  "Status response -> a human-readable failure reason. Used for the log line
  that explains a degrade, so a silent placeholder never looks like success."
  [body]
  (let [e (getv body :error)]
    (cond
      (string? e) e
      (map? e) (str (or (getv e :message) e))
      :else (when (failed? body) "generation failed without a message"))))

(def cold-start-signatures
  "Fleet-side failures that mean 'the GPU worker is not up YET', not 'this job
  is impossible'. The :video function scales from zero (murakumo.edn
  `:scale {:min 0 ...}`), so the first job after an idle period races the
  worker's boot and comes back with the generation service unable to reach
  ComfyUI. Measured 2026-07-29: `[Errno 111] Connection refused` on a cold
  fleet, `[Errno 104] Connection reset by peer` mid-boot; the very next job
  ran to completion and produced a real clip. Degrading a nightly episode to
  a placeholder because it happened to be the one that woke the fleet is a
  bug, so these retry."
  ["connection refused" "connection reset" "errno 111" "errno 104"
   "timed out" "temporarily unavailable" "502" "503" "504"])

(defn cold-start? [message]
  (let [m (str/lower-case (str (or message "")))]
    (boolean (some #(str/includes? m %) cold-start-signatures))))

(defn poll-backoff-ms
  "Attempt index -> how long to wait before the next status read. Video jobs
  on this fleet run tens of seconds to minutes (ADR-2607171100: 94.6s for a
  49-frame ltx-2.3 clip), so a flat fast poll is pure waste; this ramps
  2s -> 15s and holds."
  [attempt]
  (min 15000 (+ 2000 (* 1000 (long (or attempt 0))))))
