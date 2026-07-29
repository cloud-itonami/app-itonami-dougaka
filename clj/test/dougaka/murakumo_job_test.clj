(ns dougaka.murakumo-job-test
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.kotobase :as kb]
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
    (testing "duration becomes a frame count the fleet accepts"
      (is (= 121 (get-in b [:params :frames]))))
    (testing "vertical short geometry, snapped to the fleet's 32px grid"
      (is (= [736 1280] [(get-in b [:params :width]) (get-in b [:params :height])])))
    (testing "no model is pinned, so a fleet-side swap reaches us for free"
      (is (not (contains? b :model)))))
  (testing "an explicit model is honoured, and an image ref makes it i2v"
    (let [b (job/video-body {:prompt "p" :seconds 2 :model "wan2.2-ti2v-5b"
                             :image-url "https://x/y.png"})]
      (is (= "wan2.2-ti2v-5b" (:model b)))
      (is (= "https://x/y.png" (get-in b [:input :image])))))
  (testing "a fractional duration still lands on an accepted frame count"
    (is (= 65 (get-in (job/video-body {:prompt "p" :seconds 2.6}) [:params :frames])))))

(deftest voice-body-shape
  (let [b (job/voice-body {:text "市場の朝は、夜のうちに始まる。" :voice "ja-narrator-female"})]
    (is (= "voice" (:type b)))
    (is (= "市場の朝は、夜のうちに始まる。" (get-in b [:input :text])))
    (is (= "ja-narrator-female" (get-in b [:params :voice])))
    (is (= "ja-JP" (get-in b [:params :locale])) "every catalog in this repo is ja"))
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

(deftest artifact-host-is-rebased-onto-the-authenticated-base
  (testing "the advertised host is replaced, the job-id path is kept"
    (is (= "https://generation.murakumo.cloud/api/v1/generation/jobs/abc/artifact"
           (job/rebase-host "https://generation.murakumo.cloud/api/v1/generation"
                            "https://murakumo.cloud/api/v1/generation/jobs/abc/artifact"))))
  (testing "a blank url stays nil rather than becoming the bare origin"
    (is (nil? (job/rebase-host "https://h/api" nil)))
    (is (nil? (job/rebase-host "https://h/api" ""))))
  (testing "a relative url is left alone"
    (is (= "not-a-url" (job/rebase-host "https://h/api" "not-a-url")))))

(deftest artifact-integrity-and-naming
  (let [body {:artifacts [{:kind "sound-wav"
                           :url "https://murakumo.cloud/api/v1/generation/jobs/j1/artifact"
                           :bytes 144044
                           :contentHash "sha256:6cd744be6fa6c41884893d4b9f610e3d918bfcdad9505b66aaf389668274ea2f"}]}]
    (testing "the declared digest is what a download gets verified against"
      (is (= "6cd744be6fa6c41884893d4b9f610e3d918bfcdad9505b66aaf389668274ea2f"
             (job/artifact-sha256 body))))
    (testing "that same digest IS the storage name — one name across both planes"
      (is (= (kb/sha256-hex->cid (job/artifact-sha256 body)) (job/artifact-cid body)))
      (is (clojure.string/starts-with? (job/artifact-cid body) "bafkrei")))
    (testing "the url is rebased, not followed verbatim"
      (is (= "https://gen.example/api/v1/generation/jobs/j1/artifact"
             (job/artifact-url "https://gen.example/api/v1/generation" body)))))
  (testing "a malformed or absent contentHash yields no name, not a wrong one"
    (is (nil? (job/artifact-sha256 {:artifacts [{:contentHash "md5:abc"}]})))
    (is (nil? (job/artifact-cid {:artifacts [{:contentHash "sha256:short"}]}))))
  (testing "the top-level artifactUrl is honoured when the array is empty"
    (is (= "https://g/api/v1/generation/jobs/j2/artifact"
           (job/artifact-url "https://g/api/v1/generation"
                             {:artifacts [] :artifactUrl "https://murakumo.cloud/api/v1/generation/jobs/j2/artifact"})))))

(deftest status-and-artifacts
  (is (job/done? {:status "done"}))
  (is (job/failed? {:status "failed"}))
  (is (job/terminal? {:status "failed"}))
  (is (not (job/terminal? {:status "running"})))
  (testing "an explicit cid is taken verbatim"
    (is (= "bafk1" (job/artifact-cid {:artifacts [{:cid "bafk1"}]}))))
  (testing "done with no artifact is not success"
    (is (nil? (job/artifact-url "https://g/api" {:status "done" :artifacts []}))))
  (testing "a failure carries a reason for the degrade log"
    (is (= "out of vram" (job/job-error {:status "failed" :error {:message "out of vram"}})))
    (is (some? (job/job-error {:status "failed"})))
    (is (nil? (job/job-error {:status "running"})))))

(deftest poll-backoff-ramps-and-holds
  (is (= 2000 (job/poll-backoff-ms 0)))
  (is (= 7000 (job/poll-backoff-ms 5)))
  (is (= 15000 (job/poll-backoff-ms 100)) "held, so a long video job is not hammered")
  (is (apply <= (map job/poll-backoff-ms (range 20))) "monotonic"))

(deftest fleet-accepted-ranges
  (testing "720 is not a multiple of 32 — the project's own width would 400"
    (is (= 736 (job/snap-dimension 720)) "rounds up: generate slightly wider, then scale down — never upscale")
    (is (zero? (mod (job/snap-dimension 720) 32))))
  (testing "1280 is already valid and is the ceiling"
    (is (= 1280 (job/snap-dimension 1280)))
    (is (= 1280 (job/snap-dimension 4096))))
  (testing "clamped at the floor, never below 256"
    (is (= 256 (job/snap-dimension 10)))
    (is (= 256 (job/snap-dimension nil))))
  (testing "frames must be 8n+1 in 9..121"
    (is (= 121 (job/snap-frames 120)) "120 is rejected; 121 is the nearest 8n+1")
    (is (= 97 (job/snap-frames 97)))
    (is (= 9 (job/snap-frames 1)))
    (is (= 121 (job/snap-frames 10000)))
    (is (every? #(zero? (mod (dec (job/snap-frames %)) 8)) (range 1 200)))
    (is (every? #(<= 9 (job/snap-frames %) 121) (range 1 200)))))

(deftest video-body-obeys-the-fleet-contract
  (let [b (job/video-body {:prompt "p" :seconds 7})]
    (is (= 736 (get-in b [:params :width])) "snapped up from the project's 720")
    (is (= 1280 (get-in b [:params :height])))
    (is (= 121 (get-in b [:params :frames]))
        "7s at the fleet's 24fps exceeds the cap; the segment holds the rest"))
  (let [b (job/video-body {:prompt "p" :seconds 2})]
    (is (= 49 (get-in b [:params :frames])) "2s*24 = 48 -> 49, the nearest 8n+1")))

(deftest voice-locale-is-bcp47
  (testing "\"ja\" and \"ja_JP\" are rejected by the fleet; ja-JP is the default"
    (is (= "ja-JP" (get-in (job/voice-body {:text "t"}) [:params :locale]))))
  (testing "an explicit locale still wins"
    (is (= "en-US" (get-in (job/voice-body {:text "t" :locale "en-US"}) [:params :locale])))))

(deftest cold-fleet-is-not-a-dead-job
  (testing "the exact messages measured against a cold fleet on 2026-07-29"
    (is (job/cold-start? "<urlopen error [Errno 111] Connection refused>"))
    (is (job/cold-start? "[Errno 104] Connection reset by peer")))
  (testing "gateway-class transients too"
    (is (job/cold-start? "upstream returned 503"))
    (is (job/cold-start? "request timed out")))
  (testing "a real refusal must NOT be retried into a five-minute stall"
    (is (not (job/cold-start? "out of vram")))
    (is (not (job/cold-start? "params.width must be a multiple of 32 in 256..1280")))
    (is (not (job/cold-start? nil)))
    (is (not (job/cold-start? "")))))
