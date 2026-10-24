(ns dougaka.ingest
  "timeline テキストの取り込み — JSON を読む。EDN は読まない
  （ブラウザの scittle 文脈で edn/read-string に頼らないため）。
  返すのは {:timeline map} か {:error reason}。"
  (:require [kotoba.lang.text :as text]
            [dougaka.core :as core]))

(def max-input-bytes (* 5 1024 1024))

(defn- utf8-length [s]
  #?(:clj (alength (.getBytes ^String (str s) "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) (str s)))))

(defn parse-json
  "JSON テキスト -> 値（文字列キー）。失敗したら nil。
  JSON の parse はホストの `JSON.parse`（ブラウザと kbb）。JVM の分岐は
  持たない（mihari.ingest と同じ規律）。"
  [s]
  #?(:cljs (try (js->clj (js/JSON.parse s) :keywordize-keys false) (catch :default _ nil))
     :clj (throw (ex-info "dougaka.ingest/parse-json needs a JS host (browser or kbb)" {:input-length (count s)}))))

(defn ingest
  "timeline の JSON テキスト -> {:timeline :format :warnings} か {:error}。"
  [s]
  (cond
    (text/blank? s) {:error "empty-input: timeline JSON を貼り付けてください"}
    (> (utf8-length s) max-input-bytes) {:error "input-too-large: 5 MiB まで"}
    :else
    (let [v (parse-json s)]
      (cond
        (nil? v) {:error "unrecognised-input: JSON ではありません"}
        (not (map? v)) {:error "timeline-must-be-a-map"}
        :else {:timeline v :format "timeline-json"
               :warnings (let [{:keys [errors]} (core/validate v)]
                           (vec errors))}))))

(def sample-timeline
  "合成サンプル（実在の人・組織ではない）。"
  "{\"videoId\":\"sample-shotengai\",\"aspect\":\"portrait\",\"fps\":30,\"scenes\":[{\"index\":0},{\"index\":1}],\"assets\":[{\"kind\":\"scene\",\"blobKey\":\"frame-0\",\"meta\":{\"sceneIndex\":0}},{\"kind\":\"scene\",\"blobKey\":\"frame-1\",\"meta\":{\"sceneIndex\":1}}],\"lines\":[{\"sceneIndex\":0,\"lineIndex\":0,\"voiceBlobKey\":\"voice-0\",\"speaker\":\"left\",\"text\":\"開店前の商店街を歩く\"}]}")
