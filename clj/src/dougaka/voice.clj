(ns dougaka.voice
  "Voice leg (ADR-2607071500 follow-up「voice 実接続」) — a shot's subtitle
  line → spoken WAV, with an availability chain that never blocks production:

    1. VOICEVOX engine (env VOICEVOX_URL, default http://127.0.0.1:50021;
       speaker env VOICEVOX_SPEAKER, default 3 = ずんだもん) —
       /audio_query → /synthesis, the engine's canonical 2-step API.
    2. macOS `say` (voice env DOUGAKA_SAY_VOICE, default Kyoko) → ffmpeg
       convert to wav.
    3. nil — the caller keeps the existing silent leg (douga silent-audio),
       so a machine with no TTS still produces a playable episode.

  JVM-only I/O (JDK HttpClient — no extra deps), mirrors the repo convention
  (.clj for host I/O, .cljc for pure logic)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def default-voicevox-url
  (or (System/getenv "VOICEVOX_URL") "http://127.0.0.1:50021"))

(def default-speaker
  (or (some-> (System/getenv "VOICEVOX_SPEAKER") parse-long) 3))

(def default-say-voice
  (or (System/getenv "DOUGAKA_SAY_VOICE") "Kyoko"))

(defn- url-encode [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn audio-query-url [base text speaker]
  (str base "/audio_query?text=" (url-encode text) "&speaker=" speaker))

(defn synthesis-url [base speaker]
  (str base "/synthesis?speaker=" speaker))

(defn- http-post-bytes
  "POST → {:status :bytes} (byte-array body)."
  [url {:keys [body content-type]}]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (java.time.Duration/ofSeconds 30)))
        b (if content-type (.header b "Content-Type" content-type) b)
        req (.build (.method b "POST" (if body
                                        (HttpRequest$BodyPublishers/ofString body)
                                        (HttpRequest$BodyPublishers/noBody))))
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :bytes (.body resp)}))

(defn voicevox-synth!
  "text → wav bytes via a VOICEVOX engine, or nil on any failure (engine
  down / non-200) — never throws."
  [{:keys [text base speaker]
    :or {base default-voicevox-url speaker default-speaker}}]
  (try
    (let [q (http-post-bytes (audio-query-url base text speaker) {})]
      (when (= 200 (:status q))
        (let [s (http-post-bytes (synthesis-url base speaker)
                                 {:body (String. ^bytes (:bytes q) StandardCharsets/UTF_8)
                                  :content-type "application/json"})]
          (when (= 200 (:status s)) (:bytes s)))))
    (catch Exception _ nil)))

(defn say-synth!
  "text → wav file via macOS `say` + ffmpeg convert, or nil on failure."
  [{:keys [text voice out] :or {voice default-say-voice}}]
  (try
    (let [aiff (str out ".aiff")
          r1 (shell/sh "say" "-v" voice "-o" aiff text)]
      (when (zero? (:exit r1))
        (let [r2 (shell/sh "ffmpeg" "-y" "-loglevel" "error" "-i" aiff
                           "-ar" "24000" "-ac" "1" out)]
          (io/delete-file aiff true)
          (when (zero? (:exit r2)) out))))
    (catch Exception _ nil)))

(defn synth!
  "subtitle text → wav at `out`, via VOICEVOX → say → nil (caller falls back
  to silence). Returns `out` on success, nil otherwise."
  [{:keys [text out] :as opts}]
  (when (and text (seq (str/trim text)))
    (or (when-let [^bytes wav (voicevox-synth! opts)]
          (with-open [o (io/output-stream out)] (.write o wav))
          out)
        (say-synth! opts))))

(defn pad-cmd
  "ffmpeg argv: pad/trim `voice-wav` to exactly `seconds` (apad fills the
  tail with silence; -t truncates an over-long line) → `out-wav`. Keeps
  douga's -shortest segment timing exact even with spoken audio."
  [voice-wav seconds out-wav]
  ["ffmpeg" "-y" "-loglevel" "error" "-i" voice-wav
   "-af" "apad" "-t" (str (double seconds))
   "-ar" "24000" "-ac" "1" out-wav])
