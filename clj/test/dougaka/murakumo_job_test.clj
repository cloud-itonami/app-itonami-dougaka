(ns dougaka.murakumo-job-test
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.murakumo-job :as job]))

(deftest endpoint-resolution
  (testing "defaults to the public generation endpoint"
    (is (= job/default-endpoint (job/endpoint nil)))
    (is (= job/default-endpoint (job/endpoint "   "))))
  (testing "an override wins and loses its trailing slashes"
    (is (= "https://staging.example/api/v1/generation"
           (job/endpoint "https://staging.example/api/v1/generation//"))))
  (testing "job status url"
    (is (= "https://h/api/v1/generation/jobs/abc"
           (job/job-url "https://h/api/v1/generation/" "abc")))))

(deftest video-body-shape
  (let [b (job/video-body {:prompt "pre-dawn market" :seconds 7 :fps 30})]
    (testing "the shot's own art direction is what gets sent, unedited"
      (is (= "pre-dawn market" (get-in b [:input :prompt]))))
    (testing "duration becomes a frame count at the project's fps"
      (is (= 210 (get-in b [:params :frames]))))
    (testing "vertical short is the default geometry"
      (is (= [720 1280] [(get-in b [:params :width]) (get-in b [:params :height])])))
    (testing "no model is pinned, so a fleet-side swap reaches us for free"
      (is (not (contains? b :model)))))
  (testing "an explicit model is honoured, and an image ref makes it i2v"
    (let [b (job/video-body {:prompt "p" :seconds 2 :model "wan2.2-ti2v-5b"
                             :image-url "https://x/y.png"})]
      (is (= "wan2.2-ti2v-5b" (:model b)))
      (is (= "https://x/y.png" (get-in b [:input :image])))))
  (testing "a fractional frame count rounds up — a shot is never shorter than planned"
    (is (= 75 (get-in (job/video-body {:prompt "p" :seconds 2.5 :fps 30})
                      [:params :frames])) "exact stays exact")
    (is (= 76 (get-in (job/video-body {:prompt "p" :seconds 2.51 :fps 30})
                      [:params :frames])) "2.51s*30 = 75.3 -> 76, not 75")))

(deftest voice-body-shape
  (let [b (job/voice-body {:text "市場の朝は、夜のうちに始まる。" :voice "ja-narrator-female"})]
    (is (= "voice" (:type b)))
    (is (= "市場の朝は、夜のうちに始まる。" (get-in b [:input :text])))
    (is (= "ja-narrator-female" (get-in b [:params :voice])))
    (is (= "ja" (get-in b [:params :locale])) "every catalog in this repo is ja"))
  (testing "an unmapped role sends no voice at all rather than guessing one"
    (is (not (contains? (get (job/voice-body {:text "t"}) :params) :voice)))))

(deftest sound-body-shape
  (testing "music is the only sound_kind that selects the music function"
    (is (= "music" (get-in (job/sound-body {:prompt "p" :kind :music}) [:params :sound_kind])))
    (is (= "sfx" (get-in (job/sound-body {:prompt "p"}) [:params :sound_kind]))))
  (testing "seconds become duration_ms; a bed asks to loop"
    (let [b (job/sound-body {:prompt "p" :kind :music :seconds 60 :loop? true})]
      (is (= 60000 (get-in b [:params :duration_ms])))
      (is (true? (get-in b [:params :loop]))))))

(deftest submit-response-fails-closed
  (testing "a jobId is what makes a response a job"
    (is (= "abc" (job/submitted-id {:jobId "abc"})))
    (is (= "abc" (job/submitted-id {"jobId" "abc"})) "string keys too"))
  (testing "no jobId is not a job, however 200 it looked"
    (is (nil? (job/submitted-id {:status "queued"})))
    (is (nil? (job/submitted-id {:jobId ""})))
    (is (nil? (job/submitted-id nil)))))

(deftest status-and-artifacts
  (is (job/done? {:status "done"}))
  (is (job/failed? {:status "failed"}))
  (is (job/terminal? {:status "failed"}))
  (is (not (job/terminal? {:status "running"})))
  (testing "the server's own absolute url is preferred over re-deriving one"
    (is (= "https://gw/ipfs/bafk1"
           (job/artifact-url {:artifacts [{:cid "bafk1" :url "https://gw/ipfs/bafk1"}]}))))
  (testing "a record with only a cid still yields something to fetch"
    (is (= "bafk1" (job/artifact-url {:artifacts [{:cid "bafk1"}]})))
    (is (= "bafk1" (job/artifact-cid {:artifacts [{:cid "bafk1"}]}))))
  (testing "done with no artifact is not success"
    (is (nil? (job/artifact-url {:status "done" :artifacts []}))))
  (testing "a failure carries a reason for the degrade log"
    (is (= "out of vram" (job/job-error {:status "failed" :error {:message "out of vram"}})))
    (is (some? (job/job-error {:status "failed"})))
    (is (nil? (job/job-error {:status "running"})))))

(deftest poll-backoff-ramps-and-holds
  (is (= 2000 (job/poll-backoff-ms 0)))
  (is (= 7000 (job/poll-backoff-ms 5)))
  (is (= 15000 (job/poll-backoff-ms 100)) "held, so a long video job is not hammered")
  (is (apply <= (map job/poll-backoff-ms (range 20))) "monotonic"))
