(ns dougaka.pipeline-test
  "The legs receipt is what loop-ka-production grades. Its shape is the contract:
  the per-shot kind vectors stay under :video / :voice, and the mp4 path must
  NOT overwrite :video (that vector is the evidence the verdict reads).

  The integration test at the bottom runs ffmpeg for real. It is not skipped
  when ffmpeg is missing — a node without ffmpeg cannot produce, and a green
  suite there would be the silence this workspace keeps finding."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [dougaka.murakumo :as murakumo]
            [dougaka.pipeline :as pipeline]))

(def out
  {:video "target/x/ep-1.mp4" :srt "target/x/ep-1.srt" :shots 3 :dir "target/x"
   :dims {:width 720 :height 1280 :fps 30}
   :legs {:video [:murakumo :placeholder :comfy] :voice [:local :silent :local]
          :sfx [2] :bed false :overlays 1 :subtitles :burned}
   :artifacts [{:file "target/x/ep-1.mp4" :cid "bafy..."}]
   :cid "bafy..."})

(deftest legs-receipt-keeps-the-evidence-vectors
  (let [r (pipeline/legs-receipt {:episode-id "ep-1"} out)]
    (testing "per-shot kinds survive under the keys the verdict reads"
      (is (= [:murakumo :placeholder :comfy] (:video r)))
      (is (= [:local :silent :local] (:voice r)))
      (is (= [2] (:sfx r)))
      (is (false? (:bed r)))
      (is (= 1 (:overlays r)))
      (is (= :burned (:subtitles r))))
    (testing "outputs ride along under their own keys"
      (is (= "ep-1" (:episode-id r)))
      (is (= "target/x/ep-1.mp4" (:mp4 r)))
      (is (= "target/x/ep-1.srt" (:srt r)))
      (is (= 3 (:shots r)))
      (is (= {:width 720 :height 1280 :fps 30} (:dims r)))
      (is (= "bafy..." (:cid r)))
      (is (= 1 (count (:artifacts r)))))))

(deftest dims-for-offers-the-two-shapes
  (is (= pipeline/vertical (pipeline/dims-for nil)))
  (is (= pipeline/vertical (pipeline/dims-for "portrait")))
  (is (= pipeline/landscape (pipeline/dims-for :landscape)))
  (is (= pipeline/landscape (pipeline/dims-for "16:9")))
  (is (= pipeline/vertical (pipeline/dims-for "sideways")) "unknown is portrait, not an error")
  (is (= [1280 720] ((juxt :width :height) pipeline/landscape))))

(deftest parse-args-takes-positionals-and-flags-in-any-order
  (is (= {:plan-path "p.edn" :out-dir "o" :aspect "portrait" :burn-subtitles? true}
         (pipeline/parse-args ["p.edn" "o"])))
  (is (= {:plan-path "p.edn" :out-dir "o" :aspect "landscape" :burn-subtitles? false}
         (pipeline/parse-args ["--aspect" "landscape" "p.edn" "--no-burn" "o"])))
  (is (= {:plan-path nil :out-dir nil :aspect "portrait" :burn-subtitles? true}
         (pipeline/parse-args []))))

(deftest model-for-resolves-explicit-then-config-and-states-the-catalog-default
  (let [cfg (murakumo/read-generation-config)]
    (testing "the checked-in defaults are the 2026-08-22 catalog defaults"
      (is (= "minimax-h3" (get-in cfg [:video :model])))
      (is (= "ace-step" (get-in cfg [:music :model])))
      (is (= "musicgen-small" (get-in cfg [:sfx :model])))
      (is (= "cosyvoice2" (get-in cfg [:voice :model]))))
    (testing "explicit wins; blank explicit falls through; no config -> nil"
      (is (= "wan2.2-ti2v-5b" (murakumo/model-for :video "wan2.2-ti2v-5b" cfg)))
      (is (= "minimax-h3" (murakumo/model-for :video "  " cfg)))
      (is (= "minimax-h3" (murakumo/model-for :video nil cfg)))
      (is (nil? (murakumo/model-for :video nil nil))
          "nothing chose -> nil, and the gateway's 400 is the honest outcome"))))

;; ── integration: ffmpeg for real ───────────────────────────────────────────

(defn- gray-stddev
  "Decode one frame at `t` seconds to 8-bit gray and return the pixel stddev.
  A solid placeholder card is ~0; burned text is > 0."
  [mp4 t]
  (let [{:keys [exit out]} (shell/sh "ffmpeg" "-v" "error" "-ss" (str t) "-i" mp4
                                     "-frames:v" "1" "-f" "rawvideo" "-pix_fmt" "gray" "-"
                                     :out-enc :bytes)]
    (when (zero? exit)
      (let [^bytes b out
            n (alength b)
            xs (map #(bit-and (aget b %) 0xff) (range n))
            mean (/ (double (reduce + xs)) (max 1 n))]
        (Math/sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) xs)) (max 1 n)))))))

(def two-shot-plan
  {:episode-id "burn-it"
   :title "burn test"
   :scenes [{:seq 0 :setting "s"
             :shots [{:seq 0 :prompt "p0" :duration 1.0 :subtitle "ここに字幕が焼かれる" :voice? false}
                     {:seq 1 :prompt "p1" :duration 1.0 :subtitle "second line" :voice? false}]}]})

(deftest produce-burns-the-subtitles-into-the-picture
  (is (zero? (:exit (shell/sh "ffmpeg" "-version"))) "ffmpeg must exist; this suite does not skip")
  (let [dir (str "target/test-burn-" (System/currentTimeMillis))
        r (pipeline/produce! two-shot-plan {:dir dir :bed? false :archive? false
                                            :render-fn (fn [_] {:mime "stub"})})
        legs (:legs r)]
    (testing "a playable cut came out, with both shots and the sidecar"
      (is (.isFile (io/file (:video r))))
      (is (.isFile (io/file (:srt r))))
      (is (= 2 (:shots r)))
      (is (= [:placeholder :placeholder] (:video legs)))
      (is (= [:silent :silent] (:voice legs)))
      (is (= :burned (:subtitles legs))))
    (testing "the burn changed the pixels: solid card before, text after"
      (let [before (gray-stddev (str dir "/burn-it-mix.mp4") 0.5)
            after (gray-stddev (:video r) 0.5)]
        (is (some? before)) (is (some? after))
        (is (< before 1.0) (str "placeholder card should be flat, stddev=" before))
        (is (> after 5.0) (str "burned subtitle should add contrast, stddev=" after))))
    (testing "--no-burn keeps the cut flat and says :sidecar"
      (let [r2 (pipeline/produce! two-shot-plan {:dir (str dir "-nb") :bed? false :archive? false
                                                 :burn-subtitles? false
                                                 :render-fn (fn [_] {:mime "stub"})})]
        (is (= :sidecar (get-in r2 [:legs :subtitles])))
        (is (< (gray-stddev (:video r2) 0.5) 1.0))))
    (testing "landscape dims flow through to the file"
      (let [r3 (pipeline/produce! two-shot-plan {:dir (str dir "-ls") :bed? false :archive? false
                                                 :dims pipeline/landscape
                                                 :render-fn (fn [_] {:mime "stub"})})
            probe (:out (shell/sh "ffprobe" "-v" "error" "-select_streams" "v:0"
                                  "-show_entries" "stream=width,height" "-of" "csv=p=0" (:video r3)))]
        (is (= "1280,720" (str/trim probe)))
        (is (= {:width 1280 :height 720 :fps 30} (:dims r3)))))))
