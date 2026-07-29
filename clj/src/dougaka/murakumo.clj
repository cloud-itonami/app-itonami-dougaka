(ns dougaka.murakumo
  "dougaka's binding of the shared murakumo generation client to this JVM host.

  Everything that was expensive to learn about the API — which host to talk
  to, rebasing the advertised artifact URL, the accepted geometry and frame
  ranges, digest verification, cold-start retry, the poll schedule — lives in
  murakumo.generation / murakumo.generation.client, so mangaka, animeka and
  yukkuri get it too instead of rediscovering it one 400 at a time. This
  namespace supplies only the four host primitives that genuinely differ
  between the JVM and nbb: JSON over HTTP, download, file digest, sleep.

  Auth: env MURAKUMO_GENERATION_TOKEN (scope `generation`). Read here, passed
  down, never logged or written to an artifact."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [murakumo.generation :as gen]
            [murakumo.generation.client :as client])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.nio.file Files]
           [java.time Duration]))

(def ^:private mapper (j/object-mapper {:decode-key-fn true}))

(defn token
  "Generation-scope bearer token, or nil. nil is a normal state, not an error:
  production degrades to its placeholder path and says so."
  []
  (let [t (str/trim (str (or (System/getenv "MURAKUMO_GENERATION_TOKEN") "")))]
    (when-not (str/blank? t) t)))

(defn base-url [] (gen/endpoint (System/getenv "MURAKUMO_GENERATION_URL")))

(defn- http ^HttpClient []
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) (.build)))

(defn request!
  "method+url(+body map) -> {:status int :body map}. A transport failure comes
  back as status 0 rather than an exception, so the client's own reporting
  stays the single place a failure is explained."
  [method url body token]
  (try
    (let [b (doto (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofSeconds 60))
              (.header "authorization" (str "Bearer " token)))
          _ (if body
              (doto b
                (.header "content-type" "application/json")
                (.method method (HttpRequest$BodyPublishers/ofString
                                 (j/write-value-as-string body))))
              (.method b method (HttpRequest$BodyPublishers/noBody)))
          resp (.send (http) (.build b) (HttpResponse$BodyHandlers/ofString))
          status (.statusCode resp)]
      {:status status
       :body (when (seq (.body resp))
               (try (j/read-value (.body resp) mapper) (catch Exception _ nil)))})
    (catch Exception e {:status 0 :error (.getMessage e)})))

(defn download! [url out token]
  (try
    (let [b (doto (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofMinutes 5)))
          _ (when token (.header b "authorization" (str "Bearer " token)))
          resp (.send (http) (.build b) (HttpResponse$BodyHandlers/ofInputStream))]
      (when (<= 200 (.statusCode resp) 299)
        (io/copy (.body resp) (io/file out))
        out))
    (catch Exception _ nil)))

(defn sha256-hex [f]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (Files/readAllBytes (.toPath (io/file f))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn opts
  "The injected seam, built once per call site."
  []
  {:base (base-url) :token (token)
   :request! request! :download! download! :sha256-hex sha256-hex
   :delete! (fn [p] (io/delete-file (io/file p) true))
   :sleep! (fn [ms] (Thread/sleep (long ms)))})

(defn shot-video!
  "A shot's own art direction -> a real moving clip. This is what replaces the
  solid-colour placeholder: the plan's :prompt was always written for a video
  model, it just had nowhere to go."
  [shot out] (client/video! shot (str out) (opts)))

(defn narration!
  "A subtitle line -> spoken audio on the fleet TTS, cast by the plan's role."
  [line out] (client/narration! line (str out) (opts)))

(defn music!
  "An episode-level mood line -> a bed for the whole cut."
  [m out] (client/music! m (str out) (opts)))

(defn sfx!
  "A cue description -> a one-shot effect."
  [c out] (client/sfx! c (str out) (opts)))
