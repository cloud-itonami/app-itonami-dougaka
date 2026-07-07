(ns dougaka.voice-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dougaka.voice :as voice]))

(deftest voicevox-urls
  (is (= "http://vv:50021/audio_query?text=%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1%E3%81%AF&speaker=3"
         (voice/audio-query-url "http://vv:50021" "こんにちは" 3)))
  (is (= "http://vv:50021/synthesis?speaker=3"
         (voice/synthesis-url "http://vv:50021" 3))))

(deftest pad-cmd-holds-exact-shot-duration
  (let [cmd (voice/pad-cmd "v.wav" 3 "out.wav")]
    (is (= "out.wav" (last cmd)))
    (is (some #{"apad"} (map #(last (str/split % #",")) cmd)))
    (is (= "3.0" (nth cmd (inc (.indexOf ^java.util.List cmd "-t")))))))

(deftest synth-skips-blank-lines
  (is (nil? (voice/synth! {:text "   " :out "/tmp/never.wav"}))))

(deftest speaker-id-resolution
  (is (= 2 (voice/speaker-id :woman-20s)))
  (is (= 21 (voice/speaker-id :narrator-m)))
  (is (= 42 (voice/speaker-id 42)) "raw id passes through")
  (is (= voice/default-speaker (voice/speaker-id :unknown-role)))
  (is (= voice/default-speaker (voice/speaker-id nil))))
