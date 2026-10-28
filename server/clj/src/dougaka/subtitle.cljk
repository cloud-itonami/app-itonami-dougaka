(ns dougaka.subtitle
  "Sidecar SRT from a flat shot list (pure — cumulative shot durations).
  Burning-in is a follow-up alongside img2vid (ADR-2607071500)."
  (:require [kotoba.lang.text :as str]))

(defn flat-shots
  "Episode plan {:scenes [{:shots [...]}]} → flat shot vector (minidrama
  committed-plan shape, ADR-2607071300)."
  [plan]
  (vec (for [sc (:scenes plan) sh (:shots sc)] sh)))

(defn- srt-ts
  "Seconds (double) → SRT timestamp HH:MM:SS,mmm."
  [t]
  (let [ms (long (Math/round (* (double t) 1000.0)))
        h  (quot ms 3600000)
        m  (quot (mod ms 3600000) 60000)
        s  (quot (mod ms 60000) 1000)
        f  (mod ms 1000)]
    #?(:clj  (format "%02d:%02d:%02d,%03d" h m s f)
       :cljs (letfn [(pad [n w] (.padStart (str n) w "0"))]
               (str (pad h 2) ":" (pad m 2) ":" (pad s 2) "," (pad f 3))))))

(defn srt
  "SRT text from the flat shot list; blank subtitles emit no cue."
  [shots]
  (->> shots
       (reduce (fn [{:keys [t i out]} {:keys [duration subtitle]}]
                 (let [d (double (or duration 0))
                       end (+ t d)]
                   {:t end
                    :i (inc i)
                    :out (if (and subtitle (seq subtitle))
                           (conj out (str i "\n" (srt-ts t) " --> " (srt-ts end)
                                          "\n" subtitle "\n"))
                           out)}))
               {:t 0.0 :i 1 :out []})
       :out
       (str/join "\n")))
