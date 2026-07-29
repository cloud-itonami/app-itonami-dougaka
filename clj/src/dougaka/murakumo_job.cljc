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
  (:require [clojure.string :as str]))

(def default-endpoint "https://api.murakumo.cloud/api/v1/generation")

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

(defn video-body
  "A shot -> a text-to-video (or image-to-video, when :image-url is given)
  job body. `:prompt` is the shot's own art direction as written in the
  episode plan; nothing is invented here."
  [{:keys [prompt seconds width height fps seed model image-url]
    :or {width 720 height 1280 fps 30}}]
  (prune
   {:type "video"
    :model model
    :input (prune {:prompt prompt :image image-url})
    :params (prune {:width width
                    :height height
                    ;; the fleet's video functions are frame-counted, not
                    ;; second-counted; a shot's duration is authoritative.
                    :frames (when seconds (long (Math/ceil (* fps (double seconds)))))
                    :seed seed})}))

(defn voice-body
  "A subtitle line -> a narration job body. `:voice` carries the plan's role
  hint through to the fleet TTS so the cast stays distinguishable; `:locale`
  defaults to Japanese because every episode catalog in this repo is ja."
  [{:keys [text voice locale model] :or {locale "ja"}}]
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

(defn artifact-url
  "Status response -> the first artifact's fetchable URL, or nil.

  The server hands back `{:cid :url}` records; the url is already absolute
  (its own artifact gateway). We prefer it over re-deriving one so that a
  gateway move on the fleet side does not strand this caller."
  [body]
  (let [arts (or (getv body :artifacts) [])
        {:keys [url cid] :as a} (first arts)
        url (or url (getv a :url)) cid (or cid (getv a :cid))]
    (cond
      (not (str/blank? (str url))) (str url)
      (not (str/blank? (str cid))) (str cid)
      :else nil)))

(defn artifact-cid [body]
  (let [a (first (or (getv body :artifacts) []))
        cid (or (:cid a) (getv a :cid))]
    (when-not (str/blank? (str cid)) (str cid))))

(defn job-error
  "Status response -> a human-readable failure reason. Used for the log line
  that explains a degrade, so a silent placeholder never looks like success."
  [body]
  (let [e (getv body :error)]
    (cond
      (string? e) e
      (map? e) (str (or (getv e :message) e))
      :else (when (failed? body) "generation failed without a message"))))

(defn poll-backoff-ms
  "Attempt index -> how long to wait before the next status read. Video jobs
  on this fleet run tens of seconds to minutes (ADR-2607171100: 94.6s for a
  49-frame ltx-2.3 clip), so a flat fast poll is pure waste; this ramps
  2s -> 15s and holds."
  [attempt]
  (min 15000 (+ 2000 (* 1000 (long (or attempt 0))))))
