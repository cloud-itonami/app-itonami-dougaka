(ns dougaka.pipeline
  "Offline production executor (ADR-2607071500) — fills the gap
  dougaka.graphs.render deliberately leaves ('mp4 encode/upload is
  intentionally not run in-process'): a minidrama committed episode plan (the
  work order, ADR-2607071300) → per-shot keyframes (dougaka.comfy;
  placeholder-degrading) → douga.ffmpeg command builders (ADR-2607023000) →
  ffmpeg → a real vertical H.264 mp4 + sidecar SRT.

  The server keeps planning only; THIS entrypoint is the explicit offline
  encode leg (ffmpeg lives here); every GENERATIVE step runs on the murakumo
  fleet and every artifact is stored on kotobase.net.

    compute  = murakumo.cloud  — dougaka.murakumo
      shot :prompt  -> :video (ltx-2.3 et al) -> a real moving clip
      shot :subtitle-> :voice (cosyvoice2 ja) -> narration, cast by role
      episode mood  -> :music (ace-step)      -> a bed under the whole cut
      shot cue      -> :sfx   (stable-audio)  -> a one-shot at its timestamp
    storage  = kotobase.net    — dougaka.archive
      every artifact PUT under the sha2-256 CID of its own bytes, so a
      production is reproducible from CIDs instead of a laptop's target/.

  Degrade order is honest and per-leg: a shot with no fleet clip falls back
  to the keyframe path (ComfyUI pod, then a deterministic solid-colour frame),
  narration falls back to VOICEVOX -> `say` -> silence, and the bed/cues are
  simply absent. The pipeline ALWAYS yields a playable video — but the result
  map says which legs degraded, so a placeholder never reads as success.

  Usage: clojure -M:dev -m dougaka.pipeline               ; 3-shot demo plan
         clojure -M:dev -m dougaka.pipeline <plan.edn> [out-dir]"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [douga.ffmpeg :as ff]
            [dougaka.archive :as archive]
            [dougaka.comfy :as comfy]
            [dougaka.murakumo :as murakumo]
            [dougaka.subtitle :as subtitle]
            [dougaka.voice :as voice])
  (:import [java.util Base64])
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
    (with-open [o (io/output-stream path)]
      (.write o (.decode (Base64/getDecoder) ^String (:image-b64 render))))
    (let [n (long (or seed 0))
          ch (fn [k] (+ 140 (mod (* 53 (+ n k)) 100)))
          rgb (format "0x%02X%02X%02X" (ch 0) (ch 7) (ch 13))]
      (sh! ["ffmpeg" "-y" "-loglevel" "error"
            "-f" "lavfi" "-i" (str "color=c=" rgb ":size=" width "x" height
                                   ":duration=0.04")
            "-frames:v" "1" path])))
  path)

(def vertical {:width 720 :height 1280 :fps 30})

(defn- motion-segment-cmd
  "Animate a reviewed still with deterministic 2.5D camera motion. Keeps the
  output stream shape identical to douga.ffmpeg/scene-segment-cmd."
  [frame audio out {:keys [width height fps]} seconds motion]
  (let [frames (long (Math/ceil (* (double seconds) fps)))
        z-step (/ 0.12 (max 1 (dec frames)))
        zoom (case motion
               :pull-back (format "max(1.0,1.12-on*%.9f)" z-step)
               :pan-left "1.12"
               :pan-right "1.12"
               (format "min(1.12,1.0+on*%.9f)" z-step))
        x (case motion
            :pan-left (str "(iw-iw/zoom)*(1-on/" (max 1 (dec frames)) ")")
            :pan-right (str "(iw-iw/zoom)*on/" (max 1 (dec frames)))
            "(iw-iw/zoom)/2")
        y "(ih-ih/zoom)/2"
        vf (str "scale=" width ":" height ":force_original_aspect_ratio=increase,"
                "crop=" width ":" height ","
                "zoompan=z='" zoom "':x='" x "':y='" y "':d=" frames
                ":s=" width "x" height ":fps=" fps ",setsar=1")]
    ["ffmpeg" "-y" "-loop" "1" "-i" frame "-i" audio
     "-vf" vf "-t" (str (double seconds)) "-r" (str fps)
     "-c:v" "libx264" "-pix_fmt" "yuv420p" "-c:a" "aac" "-shortest" out]))

(def bed-gain
  "Linear gain for the music bed. The bed exists to stop the ~4-5s holes
  between narration lines from being dead air (measured on the published
  2026-07-28/29 episodes: 8 silence gaps, 38 of 60 seconds), not to compete
  with the voice."
  0.16)

(defn bed-prompt
  "Episode plan -> the mood line handed to the fleet's music function. Uses
  what the plan already states about itself (genre + logline); no house style
  is invented here."
  [plan]
  (->> [(:genre plan) (:logline plan) (:title plan)]
       (map #(str/trim (str (or % ""))))
       (remove str/blank?)
       (str/join " / ")))

(defn plan-duration
  "Total planned seconds — the bed is asked for exactly this long."
  [shots]
  (reduce + 0.0 (map #(double (or (:duration %) 2.0)) shots)))

(defn cue-overlays
  "Shots -> the SFX overlay list, each cue placed at its own shot's start.
  A shot opts in by carrying :sfx (a short description of the sound); shots
  without one contribute nothing, so a plan that never asks for effects pays
  no fleet time."
  [shots sfx-files]
  (->> shots
       (map-indexed (fn [i s] [i s]))
       (keep (fn [[i s]]
               (when-let [f (get sfx-files i)]
                 {:file f
                  :at-sec (reduce + 0.0 (map #(double (or (:duration %) 2.0))
                                             (take i shots)))
                  :gain (double (or (:sfx-gain s) 0.7))})))
       vec))

(defn- shot-clip!
  "Shot -> a generated moving clip on the fleet, or nil (degrade to keyframe).
  Skipped entirely when the plan pins its own :image-path, which means the
  operator already chose the image for this shot."
  [{:keys [prompt duration image-path video-model]} i dims clip]
  (when-not image-path
    (murakumo/shot-video! {:prompt prompt
                           :seconds (double (or duration 2.0))
                           :width (:width dims) :height (:height dims)
                           :fps (:fps dims) :seed i :model video-model}
                          (str clip))))

(defn- narration!
  "Shot -> a narration wav padded to exactly the shot duration, or nil.
  murakumo TTS first (so the cast survives on a machine with no VOICEVOX),
  then the existing local chain. Either way the role in the plan decides the
  voice — :narrator no longer falls through to the AI/machine speaker."
  [{:keys [subtitle speaker voice? voice-model] :or {voice? true}} secs spoken wav]
  (when voice?
    (let [fleet (murakumo/narration! {:text subtitle
                                      :voice (voice/fleet-voice speaker)
                                      :model voice-model}
                                     (str spoken))]
      (when (or fleet (voice/synth! {:text subtitle :speaker speaker :out (str spoken)}))
        (sh! (voice/pad-cmd (str spoken) secs (str wav)))
        (if fleet :murakumo :local)))))

(defn produce!
  "Episode plan → a produced episode. opts:
    :dir        output dir (default target/dougaka/<episode-id>)
    :render-fn  keyframe fallback (fn [shot-spec] -> gateway render result) —
                default dougaka.comfy/render-keyframe (env pod, stub offline)
    :dims       {:width :height :fps} (default vertical 720x1280@30)
    :bed?       ask the fleet for a music bed (default true)
    :archive?   mirror every artifact to kotobase.net (default true)

  Returns {:video :srt :shots :dir :legs :artifacts}, where :legs records what
  each generative step actually did (:murakumo / :local / :placeholder / nil)
  so a caller can refuse to publish a degraded cut."
  [plan & [{:keys [dir render-fn dims bed? archive?]
            :or {render-fn comfy/render-keyframe dims vertical
                 bed? true archive? true}}]]
  (let [shots (subtitle/flat-shots plan)
        _ (when (empty? shots) (throw (ex-info "plan has no shots" {:plan plan})))
        dir (io/file (or dir (str "target/dougaka/" (or (:episode-id plan) "episode"))))
        _ (.mkdirs dir)
        legs (atom {:video [] :voice [] :sfx []})
        sfx-files (atom {})
        segs (vec
              (map-indexed
               (fn [i {:keys [prompt duration subtitle speaker image-path motion sfx] :as shot}]
                 (let [frame (io/file dir (format "shot_%05d.png" i))
                       clip  (io/file dir (format "clip_%05d.mp4" i))
                       wav   (io/file dir (format "shot_%05d.wav" i))
                       seg   (io/file dir (format "seg_%05d.mp4" i))
                       spoken (io/file dir (format "voice_%05d.wav" i))
                       secs  (double (or duration 2.0))
                       generated (shot-clip! shot i dims clip)]
                   ;; ── image leg ─────────────────────────────────────────
                   (let [r (when (and (not generated) (not image-path))
                             (render-fn {:prompt prompt :seed i
                                         :width (:width dims) :height (:height dims)}))]
                     (when-not generated
                       (if image-path
                         (io/copy (io/file image-path) frame)
                         (write-keyframe! r (str frame) {:seed i
                                                         :width (:width dims)
                                                         :height (:height dims)})))
                     (swap! legs update :video conj
                            (cond generated :murakumo
                                  image-path :operator-image
                                  (= "image/png" (:mime r)) :comfy
                                  :else :placeholder)))
                   ;; ── voice leg ─────────────────────────────────────────
                   (let [got (narration! shot secs spoken wav)]
                     (when-not got
                       (sh! (ff/silent-audio-cmd (str wav)
                                                 {:seconds secs :sample-rate 24000})))
                     (swap! legs update :voice conj (or got :silent)))
                   ;; ── cue leg (opt-in per shot) ─────────────────────────
                   (when sfx
                     (let [f (io/file dir (format "sfx_%05d.wav" i))]
                       (when (murakumo/sfx! {:prompt sfx :seconds (min 4.0 secs)} (str f))
                         (swap! sfx-files assoc i (str f))
                         (swap! legs update :sfx conj i))))
                   ;; ── assemble the segment ──────────────────────────────
                   (sh! (cond
                          generated (ff/video-segment-cmd (str clip) (str seg)
                                                          {:duration-sec secs
                                                           :width (:width dims)
                                                           :height (:height dims)
                                                           :fps (:fps dims)
                                                           :audio-path (str wav)})
                          motion (motion-segment-cmd (str frame) (str wav) (str seg)
                                                     dims secs motion)
                          :else (ff/scene-segment-cmd (str frame) (str wav) (str seg) dims)))
                   ;; concat demuxer resolves entries relative to the list
                   ;; file (which sits in the same dir) — write basenames
                   (.getName seg)))
               shots))
        list-file (io/file dir "concat.txt")
        _ (spit list-file (ff/concat-list-text segs))
        cut (io/file dir (str (or (:episode-id plan) "episode") "-cut.mp4"))
        out (io/file dir (str (or (:episode-id plan) "episode") ".mp4"))
        srt (io/file dir (str (or (:episode-id plan) "episode") ".srt"))]
    (spit srt (subtitle/srt shots))
    (sh! (ff/concat-segments-cmd (str list-file) (str cut)))
    ;; ── bed + cues over the finished cut ──────────────────────────────────
    (let [bed (when bed?
                (let [f (io/file dir "bgm.wav")]
                  (when (murakumo/music! {:prompt (bed-prompt plan)
                                          :seconds (plan-duration shots)}
                                         (str f))
                    {:file (str f) :loop? true :gain bed-gain :at-sec 0})))
          overlays (into (vec (remove nil? [bed])) (cue-overlays shots @sfx-files))]
      (sh! (ff/audio-overlay-mix-cmd (str cut) overlays (str out)))
      (let [artifacts (when archive?
                        (archive/put-all!
                         (concat [[out :mp4] [srt :srt]]
                                 (when bed [[(:file bed) :wav]])
                                 (map (fn [f] [f :wav]) (vals @sfx-files)))))]
        {:video (str out) :srt (str srt) :shots (count shots) :dir (str dir)
         :legs (assoc @legs :bed (boolean bed) :overlays (count overlays))
         :artifacts (vec artifacts)
         :cid (some (fn [a] (when (= (str out) (:file a)) (:cid a))) artifacts)}))))

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
