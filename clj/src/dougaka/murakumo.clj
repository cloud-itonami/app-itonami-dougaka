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

(defn- sha256-hex [f]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (java.nio.file.Files/readAllBytes (.toPath (io/file f))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- download!
  "artifact url -> file at `out`, returns out. nil on failure (warned).

  When the job declared a contentHash, the written bytes are verified against
  it and a mismatch is discarded — a truncated download must not be muxed into
  an episode and stored under a name that does not describe it."
  [url out tok expect-sha]
  (try
    (let [b (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofMinutes 5)))
          _ (when tok (.header b "authorization" (str "Bearer " tok)))
          resp (.send (client) (.build b) (HttpResponse$BodyHandlers/ofInputStream))]
      (if-not (<= 200 (.statusCode resp) 299)
        (do (warn "artifact" url "->" (.statusCode resp)) nil)
        (do (io/copy (.body resp) (io/file out))
            (let [got (when expect-sha (sha256-hex out))]
              (if (and expect-sha (not= got expect-sha))
                (do (warn "artifact" url "digest mismatch — discarded"
                          (str "(expected " (subs expect-sha 0 12) "…, got "
                               (subs (str got) 0 (min 12 (count (str got)))) "…)"))
                    (io/delete-file (io/file out) true)
                    nil)
                out)))))
    (catch Exception e (warn "artifact" url "-> " (.getMessage e)) nil)))

(def cold-start-retries
  "How many times a cold-fleet failure is retried before the leg degrades.
  The worker takes tens of seconds to boot, so two more attempts spaced by
  cold-start-wait-ms covers it without turning a genuinely broken job into a
  five-minute stall."
  2)

(def cold-start-wait-ms 45000)

(defn- attempt!
  "One submit/poll/download round trip. Returns {:ok result} | {:cold msg} |
  {:dead reason} so the caller can tell 'not up yet' from 'will never work'."
  [body out tok label timeout-ms]
  (if-let [id (submit! body tok)]
    (let [status (await-job! id tok {:timeout-ms (or timeout-ms 600000)})]
      (cond
        (nil? status) {:dead "no terminal status"}
        (job/failed? status)
        (let [msg (job/job-error status)]
          (if (job/cold-start? msg)
            {:cold (str "job " id ": " msg)}
            {:dead (str "job " id " failed: " msg)}))
        :else
        (if-let [url (job/artifact-url (base-url) status)]
          (if (download! url out tok (job/artifact-sha256 status))
            {:ok {:file (str out) :cid (job/artifact-cid status) :job-id id}}
            {:dead (str "job " id ": artifact download failed")})
          {:dead (str "job " id " done but produced no artifact")})))
    {:dead "not accepted by the fleet"}))

(defn generate!
  "Body -> a local file at `out`, or nil.

  The whole submit/poll/download round trip, with every failure mode folded
  into a warned nil: no token, fleet unreachable, job failed, artifact
  missing, download failed. `label` names the step in the warning so a
  degraded episode says WHICH shot lost its image.

  A cold-fleet failure is retried rather than degraded — see
  dougaka.murakumo-job/cold-start-signatures for why.

  Returns {:file out :cid <artifact cid or nil> :job-id id} on success."
  [body out {:keys [label timeout-ms] :or {label "job"}}]
  (if-let [tok (token)]
    (loop [tries-left cold-start-retries]
      (let [r (attempt! body out tok label timeout-ms)]
        (cond
          (:ok r) (:ok r)
          (and (:cold r) (pos? tries-left))
          (do (warn label (:cold r) "— fleet is still waking, retrying in"
                    (quot cold-start-wait-ms 1000) "s")
              (Thread/sleep cold-start-wait-ms)
              (recur (dec tries-left)))
          :else (do (warn label (or (:dead r) (:cold r))) nil))))
    (do (warn label "skipped — no MURAKUMO_GENERATION_TOKEN in env") nil)))

;; ── production steps ────────────────────────────────────────────────────────

(defn shot-video!
  "A shot's own art direction -> a real moving clip. This is what replaces the
  solid-colour placeholder: the plan's :prompt was always written for a video
  model, it just had nowhere to go."
  [{:keys [prompt seconds width height seed model image-url]} out]
  (generate! (job/video-body {:prompt prompt :seconds seconds :width width
                              :height height :seed seed :model model
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
