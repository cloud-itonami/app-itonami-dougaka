(ns dougaka.pipeline-test
  "The legs receipt is what loop-ka-production grades. Its shape is the contract:
  the per-shot kind vectors stay under :video / :voice, and the mp4 path must
  NOT overwrite :video (that vector is the evidence the verdict reads)."
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.pipeline :as pipeline]))

(def out
  {:video "target/x/ep-1.mp4" :srt "target/x/ep-1.srt" :shots 3 :dir "target/x"
   :legs {:video [:murakumo :placeholder :comfy] :voice [:local :silent :local]
          :sfx [2] :bed false :overlays 1}
   :artifacts [{:file "target/x/ep-1.mp4" :cid "bafy..."}]
   :cid "bafy..."})

(deftest legs-receipt-keeps-the-evidence-vectors
  (let [r (pipeline/legs-receipt {:episode-id "ep-1"} out)]
    (testing "per-shot kinds survive under the keys the verdict reads"
      (is (= [:murakumo :placeholder :comfy] (:video r)))
      (is (= [:local :silent :local] (:voice r)))
      (is (= [2] (:sfx r)))
      (is (false? (:bed r)))
      (is (= 1 (:overlays r))))
    (testing "outputs ride along under their own keys"
      (is (= "ep-1" (:episode-id r)))
      (is (= "target/x/ep-1.mp4" (:mp4 r)))
      (is (= "target/x/ep-1.srt" (:srt r)))
      (is (= 3 (:shots r)))
      (is (= "bafy..." (:cid r)))
      (is (= 1 (count (:artifacts r)))))))
