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
  (:require [clojure.string :as str]
            [murakumo.generation.digest :as digest]))

;; The CID math is shared with the generation client on purpose: murakumo
;; states an artifact's integrity as the same sha2-256 digest kotobase names
;; objects by, so a generated artifact must not get two different names from
;; two different base32 implementations.
(def raw-cid digest/raw-cid)
(def sha256-hex->cid digest/sha256-hex->cid)


(def default-host "https://kotobase.net")

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
