(ns dougaka.server-test
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
            [dougaka.server :as server]))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (server/app
    (cond-> {:request-method method
             :uri uri
             :headers {"accept" "application/json"}}
      body (assoc :headers {"accept" "application/json" "content-type" "application/json"}
                  :body (java.io.ByteArrayInputStream. (.getBytes (j/write-value-as-string body))))))))

(defn- json-body [resp]
  (j/read-value (:body resp) j/keyword-keys-object-mapper))

(deftest health-and-ok
  (doseq [uri ["/" "/ok" "/health"]]
    (let [resp (req :get uri)]
      (is (= 200 (:status resp)))
      (is (= true (:ok (json-body resp))))
      (is (= "clj" (:impl (json-body resp)))))))

(deftest runs-and-xrpc
  (let [timeline {:videoId "v1"
                  :scenes [{:index 0}]
                  :assets [{:kind "scene" :blobKey "frame" :meta {:sceneIndex 0}}]}
        runs (req :post "/runs" {:assistant_id "render" :input {:timeline timeline}})
        xrpc (req :post "/xrpc/ai.gftd.apps.dougaka.render" timeline)]
    (is (= 200 (:status runs)))
    (is (= "planned" (:status (json-body runs))))
    (is (= 1 (:segmentCount (json-body runs))))
    (is (= 200 (:status xrpc)))
    (is (= "v1" (:videoId (json-body xrpc))))))

(deftest unknowns-404
  (is (= 404 (:status (req :post "/runs" {:assistant_id "nope" :input {}}))))
  (is (= 404 (:status (req :post "/xrpc/ai.gftd.apps.dougaka.unknownMethod" {})))))
