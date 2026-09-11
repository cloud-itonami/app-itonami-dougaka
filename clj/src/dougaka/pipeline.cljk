(ns dougaka.pipeline
  "Offline production executor (ADR-2607071500) — fills the gap
  dougaka.graphs.render deliberately leaves ('mp4 encode/upload is
  intentionally not run in-process'): a minidrama committed episode plan (the
  work order, ADR-2607071300) → per-shot keyframes (dougaka.comfy;
  placeholder-degrading) → douga.ffmpeg command builders (ADR-2607023000) →
  ffmpeg → a real vertical H.264 mp4 + sidecar SRT.

  The server keeps planning only; THIS entrypoint is the explicit offline
  encode leg (fleet/laptop, where ffmpeg lives). A stub/SVG keyframe (no GPU)
  is replaced by a deterministic solid-color frame so the pipeline ALWAYS
  yields a playable video — the same never-throw honesty rule as
  comfyui.gateway's placeholder.

  Usage: clojure -M:dev -m dougaka.pipeline               ; 3-shot demo plan
         clojure -M:dev -m dougaka.pipeline <plan.edn> [out-dir]"
  (:require [clojure.edn :as edn]
            [kotoba.lang.text :as str]
            [douga.ffmpeg :as ff]
            [dougaka.comfy :as comfy]
            [dougaka.subtitle :as subtitle]
            [dougaka.voice :as voice]
            [kotoba.util :as kutil]))
  (:gen-class))

(defn- sh! [[bin & args :as cmd]]
  (let [{:keys [exit err] :as r} (apply shell/sh cmd)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " bin " " (first args))
                      {:cmd cmd :exit exit :err err})))
    r))

(defn- write-keyframe!
  "Write a rendered keyframe to `path`. Real gateway PNGs are decoded from
  b64; stub/SVG renders fall back to a deterministic solid-color frame
  (commas in lavfi args break filter parsing, so hex RGB, not hsv())."
  [render path {:keys [width height seed] :or {width 720 height 1280}}]
  (if (= "image/png" (:mime render))
    (with-open [o (kutil/output-stream path)]
      (.write o (kutil/base64-decode (:image-b64 render))))
    (let [n (long (or seed 0))
          ch (fn [k] (+ 140 (mod (* 53 (+ n k)) 100)))
          rgb (format "0x%02X%02X%02X" (ch 0) (ch 7) (ch 13))]
      (sh! ["ffmpeg" "-y" "-loglevel" "error"
            "-f" "lavfi" "-i" (str "color=c=" rgb ":size=" width "x" height
                                   ":duration=0.04")
            "-frames:v" "1" path])))
  path)

(def vertical {:width 720 :height 1280 :fps 30})

(defn produce!
  "Episode plan → {:video :srt :shots :dir}. opts:
    :dir        output dir (default target/dougaka/<episode-id>)
    :render-fn  (fn [shot-spec] -> gateway render result) — default
                dougaka.comfy/render-keyframe (env-configured pod, stub offline)
    :dims       {:width :height :fps} (default vertical 720x1280@30)"
  [plan & [{:keys [dir render-fn dims]
            :or {render-fn comfy/render-keyframe dims vertical}}]]
  (let [shots (subtitle/flat-shots plan)
        _ (when (empty? shots) (throw (ex-info "plan has no shots" {:plan plan})))
        dir (kutil/file (or dir (str "target/dougaka/" (or (:episode-id plan) "episode"))))
        _ (kutil/make-parents dir)
        segs (vec
              (map-indexed
               (fn [i {:keys [prompt duration subtitle speaker]}]
                 (let [frame (kutil/file dir (format "shot_%05d.png" i))
                       wav   (kutil/file dir (format "shot_%05d.wav" i))
                       seg   (kutil/file dir (format "seg_%05d.mp4" i))
                       secs  (double (or duration 2.0))
                       r (render-fn {:prompt prompt :seed i
                                     :width (:width dims) :height (:height dims)})]
                   (write-keyframe! r (str frame) {:seed i
                                                   :width (:width dims)
                                                   :height (:height dims)})
                   ;; voice leg (ADR-2607071500 follow-up): 台詞があれば TTS
                   ;; (VOICEVOX → say)、apad/-t で尺を shot duration ぴったりに
                   ;; 保つ (douga の segment cmd は -shortest なので audio 長 =
                   ;; shot 長)。TTS 不達は従来どおり無音に degrade
                   (let [spoken (kutil/file dir (format "voice_%05d.wav" i))]
                     (if (voice/synth! {:text subtitle :speaker speaker :out (str spoken)})
                       (sh! (voice/pad-cmd (str spoken) secs (str wav)))
                       (sh! (ff/silent-audio-cmd (str wav)
                                                 {:seconds secs :sample-rate 24000}))))
                   (sh! (ff/scene-segment-cmd (str frame) (str wav) (str seg) dims))
                   ;; concat demuxer resolves entries relative to the list
                   ;; file (which sits in the same dir) — write basenames
                   (.getName seg)))
               shots))
        list-file (kutil/file dir "concat.txt")
        _ (spit list-file (ff/concat-list-text segs))
        out (kutil/file dir (str (or (:episode-id plan) "episode") ".mp4"))
        srt (kutil/file dir (str (or (:episode-id plan) "episode") ".srt"))]
    (spit srt (subtitle/srt shots))
    (sh! (ff/concat-segments-cmd (str list-file) (str out)))
    {:video (str out) :srt (str srt) :shots (count shots) :dir (str dir)}))

(def demo-plan
  {:episode-id "demo-1"
   :title "終電を逃した二人（デモ）"
   :scenes [{:seq 0 :setting "駅"
             :shots [{:seq 0 :prompt "vertical anime, empty station platform at night" :duration 3 :subtitle "終電、行っちゃった"}
                     {:seq 1 :prompt "vertical anime, two people look at each other" :duration 3 :subtitle "……歩く？"}]}
            {:seq 1 :setting "夜道"
             :shots [{:seq 0 :prompt "vertical anime, walking under street lights" :duration 4 :subtitle "たまには、いいか"}]}]})

(defn -main [& args]
  (let [plan (if (seq args) (edn/read-string (slurp (first args))) demo-plan)
        out (produce! plan {:dir (second args)})]
    (println "video :" (:video out))
    (println "srt   :" (:srt out))
    (println "shots :" (:shots out))
    (let [probe (shell/sh "ffprobe" "-v" "error" "-show_entries"
                          "format=duration:stream=codec_name,width,height"
                          "-of" "default=nw=1" (:video out))]
      (print (:out probe)))))
