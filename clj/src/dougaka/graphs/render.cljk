(ns dougaka.graphs.render
  (:require [douga.ffmpeg :as plan]))

(defn- input-value [m & ks]
  (some #(get m %) ks))

(defn- video-id [input timeline]
  (or (input-value input :videoId :video-id :video_id "videoId" "video_id")
      (input-value timeline :videoId :video-id :video_id "videoId" "video_id")
      (str "dougaka-" (hash timeline))))

(defn make []
  (fn [input _thread-id]
    (let [timeline (or (input-value input :timeline "timeline") input {})
          render-plan (plan/build-render-plan timeline)
          id (video-id input timeline)]
      {:status "planned"
       :impl "clj"
       :videoId id
       :video_id id
       :renderPlan render-plan
       :render_plan render-plan
       :segmentCount (count (:segments render-plan))
       :segment_count (count (:segments render-plan))
       :output_mp4 nil
       :blob_key nil
       :blob_url nil
       :note "CLJ dougaka currently plans the ffmpeg assembly; mp4 encode/upload is intentionally not run in-process."})))

(defn handler [graph]
  (fn [input thread-id] (graph input thread-id)))
