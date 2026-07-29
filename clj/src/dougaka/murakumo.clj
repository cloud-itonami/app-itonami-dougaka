(ns dougaka.murakumo
  "JVM I/O for murakumo.cloud generation — submit, poll to terminal, fetch the
  artifact bytes. Pure bodies/parsing live in dougaka.murakumo-job.

  Every GPU step of a production runs here: the moving image for a shot, the
  narration, the music bed and the cues. Nothing in this namespace throws at
  the caller — an unreachable fleet, a missing token or a failed job all
  return nil with a stderr line saying which, so dougaka.pipeline can degrade
  to its placeholder exactly as it always has (the never-throw honesty rule)
  instead of losing a night's episode to a 500.

  Auth: a generation-scope capability token, env MURAKUMO_GENERATION_TOKEN.
  It is read from the environment and sent as a Bearer header; it is never
  logged, written to an artifact, or persisted here.

  JDK HttpClient only — no new dependency, same convention as dougaka.voice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dougaka.murakumo-job :as job]
            [jsonista.core :as j])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private mapper (j/object-mapper {:decode-key-fn true}))

(defn token
  "Generation-scope bearer token, or nil when the operator has not provisioned
  one. nil is a normal state, not an error: production degrades."
  []
  (let [t (str/trim (str (or (System/getenv "MURAKUMO_GENERATION_TOKEN") "")))]
    (when-not (str/blank? t) t)))

(defn base-url []
  (job/endpoint (System/getenv "MURAKUMO_GENERATION_URL")))

(defn- client ^HttpClient []
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) (.build)))

(defn- warn [& xs]
  (binding [*out* *err*] (apply println "[murakumo]" xs)))

(defn- request-json
  "method+url(+body) -> parsed json map, or nil (warned). Never throws."
  [method url body tok]
  (try
    (let [b (HttpRequest/newBuilder (URI/create url))
          _ (.timeout b (Duration/ofSeconds 60))
          _ (.header b "authorization" (str "Bearer " tok))
          _ (when body
              (.header b "content-type" "application/json")
              (.method b method (HttpRequest$BodyPublishers/ofString (j/write-value-as-string body))))
          _ (when-not body (.method b method (HttpRequest$BodyPublishers/noBody)))
          resp (.send (client) (.build b) (HttpResponse$BodyHandlers/ofString))
          status (.statusCode resp)]
      (if (<= 200 status 299)
        (j/read-value (.body resp) mapper)
        (do (warn method url "->" status
                  (if (= 401 status)
                    "(MURAKUMO_GENERATION_TOKEN missing or not generation-scope)"
                    ""))
            nil)))
    (catch Exception e
      (warn method url "-> unreachable:" (.getMessage e))
      nil)))

(defn submit!
  "Job body -> job id, or nil. Fails closed on a 200 that carries no jobId."
  [body tok]
  (some-> (request-json "POST" (base-url) body tok) job/submitted-id))

(defn await-job!
  "Poll a job to a terminal state. Returns the terminal status body, or nil on
  timeout/unreachable. `:timeout-ms` bounds a single shot's wait so one stuck
  job cannot hold the whole episode."
  [job-id tok {:keys [timeout-ms] :or {timeout-ms 600000}}]
  (let [url (job/job-url (base-url) job-id)
        deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop [attempt 0]
      (let [body (request-json "GET" url nil tok)]
        (cond
          (and body (job/terminal? body)) body
          (> (System/currentTimeMillis) deadline)
          (do (warn "job" job-id "still" (some-> body job/job-status)
                    "after" (quot timeout-ms 1000) "s — giving up")
              nil)
          :else (do (Thread/sleep (job/poll-backoff-ms attempt))
                    (recur (inc attempt))))))))

(defn- download!
  "artifact url -> file at `out`, returns out. nil on failure (warned)."
  [url out tok]
  (try
    (let [b (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofMinutes 5)))
          _ (when tok (.header b "authorization" (str "Bearer " tok)))
          resp (.send (client) (.build b) (HttpResponse$BodyHandlers/ofInputStream))]
      (if (<= 200 (.statusCode resp) 299)
        (do (io/copy (.body resp) (io/file out)) out)
        (do (warn "artifact" url "->" (.statusCode resp)) nil)))
    (catch Exception e (warn "artifact" url "-> " (.getMessage e)) nil)))

(defn generate!
  "Body -> a local file at `out`, or nil.

  The whole submit/poll/download round trip, with every failure mode folded
  into a warned nil: no token, fleet unreachable, job failed, artifact
  missing, download failed. `label` names the step in the warning so a
  degraded episode says WHICH shot lost its image.

  Returns {:file out :cid <artifact cid or nil> :job-id id} on success."
  [body out {:keys [label timeout-ms] :or {label "job"}}]
  (if-let [tok (token)]
    (if-let [id (submit! body tok)]
      (let [status (await-job! id tok {:timeout-ms (or timeout-ms 600000)})]
        (cond
          (nil? status) nil
          (job/failed? status) (do (warn label "job" id "failed:" (job/job-error status)) nil)
          :else
          (if-let [url (job/artifact-url status)]
            (when (download! url out tok)
              {:file (str out) :cid (job/artifact-cid status) :job-id id})
            (do (warn label "job" id "done but produced no artifact") nil))))
      (do (warn label "was not accepted by the fleet") nil))
    (do (warn label "skipped — no MURAKUMO_GENERATION_TOKEN in env") nil)))

;; ── production steps ────────────────────────────────────────────────────────

(defn shot-video!
  "A shot's own art direction -> a real moving clip. This is what replaces the
  solid-colour placeholder: the plan's :prompt was always written for a video
  model, it just had nowhere to go."
  [{:keys [prompt seconds width height fps seed model image-url]} out]
  (generate! (job/video-body {:prompt prompt :seconds seconds :width width
                              :height height :fps fps :seed seed :model model
                              :image-url image-url})
             out {:label (str "video shot " seed) :timeout-ms 900000}))

(defn narration!
  "A subtitle line -> spoken audio on the fleet TTS (cosyvoice2 ja by default,
  resolved fleet-side). `voice` carries the plan's role so the cast stays
  distinguishable."
  [{:keys [text voice model]} out]
  (generate! (job/voice-body {:text text :voice voice :model model})
             out {:label "narration" :timeout-ms 300000}))

(defn music!
  "An episode-level mood line -> a music bed."
  [{:keys [prompt seconds model]} out]
  (generate! (job/sound-body {:prompt prompt :kind :music :seconds seconds
                              :loop? true :model model})
             out {:label "bgm" :timeout-ms 600000}))

(defn sfx!
  "A cue description -> a one-shot effect."
  [{:keys [prompt seconds model]} out]
  (generate! (job/sound-body {:prompt prompt :kind :sfx :seconds (or seconds 2.0)
                              :model model})
             out {:label "sfx" :timeout-ms 300000}))
