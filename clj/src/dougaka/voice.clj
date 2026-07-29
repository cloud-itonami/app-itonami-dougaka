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

(def speaker-map
  "Role archetype (episodes/ カタログの :speaker ヒント) → VOICEVOX speaker id
  (VOICEVOX 0.25.2 の実測 /speakers、ADR-2607071300 設計カタログの演じ分け)。
  未知の role は default-speaker。id は env でなくここが SSoT — 変えるときは
  1 箇所。female young=四国めたん(2) / male young=玄野武宏(11) /
  male adult=青山龍星(13) / female adult=冥鳴ひまり(14) /
  narrator f=九州そら(16) m=剣崎雌雄(21) / girl=春日部つむぎ(8) /
  boy=白上虎太郎(12) / ai・機械=ずんだもん(3) / old=後鬼 人間ver.(27)。"
  {:woman-20s 2 :heroine 2 :daughter 2 :granddaughter 2 :student-f 8
   :girl 8 :student 8
   :man-20s 11 :boy 12
   :man-30s 13 :father 13 :boss 13 :driver 13 :guard 13
   :woman-30s 14
   ;; :narrator は dougaka-actor の実写/ミニドキュメンタリー catalog が実際に
   ;; 使っている綴り。map に無かったため 8 shot すべてが default(ずんだもん=
   ;; AI・機械役)に落ち、公開済みエピソードのナレーションがその声で読まれて
   ;; いた(2026-07-29 実測: 公開 mp4 の基本周波数が全ショット同一・測定上限
   ;; 張り付き)。地の文の既定は女性ナレーター。
   :narrator 16 :narration 16 :narrator-f 16 :narrator-m 21
   :ai 3
   :old-man 27 :owner 27})

(def fleet-voice-map
  "Role archetype → murakumo TTS(:voice function, 既定 cosyvoice2 ja)へ渡す
  voice 名。VOICEVOX が無い環境でも演じ分けを失わないための対応表 —
  speaker-map と同じ役語彙を、fleet 側の話者名に写す。未知の役は nil を返し、
  fleet 側の既定話者に委ねる(こちらで勝手に別の役を当てない)。"
  {:woman-20s "ja-female-young" :heroine "ja-female-young"
   :daughter "ja-female-young" :granddaughter "ja-female-young"
   :student-f "ja-girl" :girl "ja-girl" :student "ja-girl"
   :man-20s "ja-male-young" :boy "ja-boy"
   :man-30s "ja-male-adult" :father "ja-male-adult" :boss "ja-male-adult"
   :driver "ja-male-adult" :guard "ja-male-adult"
   :woman-30s "ja-female-adult"
   :narrator "ja-narrator-female" :narration "ja-narrator-female"
   :narrator-f "ja-narrator-female" :narrator-m "ja-narrator-male"
   :ai "ja-synthetic"
   :old-man "ja-elder"})

(defn fleet-voice
  "Shot の :speaker ヒント → murakumo TTS の voice 名 | nil(fleet 既定に委ねる)。"
  [speaker]
  (when (keyword? speaker) (get fleet-voice-map speaker)))

(defn speaker-id
  "Resolve a shot's :speaker hint → VOICEVOX speaker id (keyword via
  speaker-map, integer passes through, nil/unknown → default-speaker)."
  [speaker]
  (cond
    (integer? speaker) speaker
    (keyword? speaker) (get speaker-map speaker default-speaker)
    :else default-speaker))

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
  to silence). `:speaker` may be a role keyword (episodes/ カタログの演じ分け
  ヒント → speaker-map) or a raw VOICEVOX id. Returns `out` on success."
  [{:keys [text out speaker] :as opts}]
  (when (and text (seq (str/trim text)))
    (let [opts (assoc opts :speaker (speaker-id speaker))]
      (or (when-let [^bytes wav (voicevox-synth! opts)]
            (with-open [o (io/output-stream out)] (.write o wav))
            out)
          (say-synth! opts)))))

(defn pad-cmd
  "ffmpeg argv: pad/trim `voice-wav` to exactly `seconds` (apad fills the
  tail with silence; -t truncates an over-long line) → `out-wav`. Keeps
  douga's -shortest segment timing exact even with spoken audio."
  [voice-wav seconds out-wav]
  ["ffmpeg" "-y" "-loglevel" "error" "-i" voice-wav
   "-af" "apad" "-t" (str (double seconds))
   "-ar" "24000" "-ac" "1" out-wav])
