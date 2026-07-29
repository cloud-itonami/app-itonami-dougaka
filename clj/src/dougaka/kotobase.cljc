(ns dougaka.kotobase
  "Pure content-addressing for kotobase.net's first-party archive
  (`PUT /ipfs/:cid` / `GET /ipfs/:cid`, kotobase.archive-put).

  Every production artifact — each shot's clip, the narration, the bed, the
  cues, the finished episode — is named by the sha2-256 of its own bytes, so
  storage is idempotent (re-running a shot that produced identical bytes is a
  no-op) and a plan can carry CIDs instead of laptop-local paths.

  The CID is raw CIDv1: multibase 'b' + base32(lower, unpadded) over
  0x01 0x55 0x12 0x20 ++ digest. That is exactly what the archive endpoint
  re-derives and checks, so a wrong CID here fails closed at the server (422)
  rather than storing mislabelled bytes."
  (:require [clojure.string :as str]))

(def default-host "https://kotobase.net")

(def ^:private b32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn base32-encode
  "bytes (seq of unsigned ints) -> RFC 4648 lowercase base32, unpadded.

  Carries at most 12 bits in the accumulator (8 in, 5 out at a time), so a
  single reduce over the bytes emits one or two characters per step and the
  tail flushes whatever is left — no bignum, no padding, no interop."
  [bs]
  (let [[acc bits out]
        (reduce (fn [[acc bits ^StringBuilder out] b]
                  (let [acc (bit-or (bit-shift-left acc 8) (bit-and (long b) 0xff))
                        bits (+ bits 8)
                        ;; bits is now 8..12 -> emit one 5-bit group, maybe two
                        [acc bits] (loop [acc acc bits bits]
                                     (if (>= bits 5)
                                       (do (.append out (.charAt b32-alphabet
                                                                 (bit-and (unsigned-bit-shift-right acc (- bits 5)) 31)))
                                           (recur acc (- bits 5)))
                                       [acc bits]))]
                    [(bit-and acc (dec (bit-shift-left 1 (max bits 1)))) bits out]))
                [0 0 (StringBuilder.)]
                bs)]
    (when (pos? bits)
      (.append ^StringBuilder out
               (.charAt b32-alphabet (bit-and (bit-shift-left acc (- 5 bits)) 31))))
    (str out)))

(defn raw-cid
  "32-byte sha2-256 digest (seq of ints) -> bafkrei… raw CIDv1 string."
  [digest]
  (let [ds (vec digest)]
    (when-not (= 32 (count ds))
      (throw (ex-info "sha2-256 digest must be 32 bytes" {:length (count ds)})))
    (str "b" (base32-encode (into [0x01 0x55 0x12 0x20] ds)))))

(defn archive-url
  "CID -> the public, content-addressed URL it is served from."
  ([cid] (archive-url default-host cid))
  ([host cid] (str (str/replace (str (or host default-host)) #"/+$" "") "/ipfs/" cid)))

(defn host
  "Archive host, with an override (env/opt) taking precedence."
  [override]
  (let [s (str/trim (str (or override "")))]
    (if (str/blank? s) default-host (str/replace s #"/+$" ""))))

(def content-types
  "Production artifact kinds -> the content-type stored alongside the bytes.
  Everything here is outside kotobase.archive-put's web-serving allowlist, so
  it is served back as an opaque download — which is what a production
  intermediate should be. The finished episode still reaches viewers through
  aozora's own blob, not this archive."
  {:mp4 "video/mp4"
   :wav "audio/wav"
   :png "image/png"
   :srt "text/plain"
   :edn "text/plain"})
