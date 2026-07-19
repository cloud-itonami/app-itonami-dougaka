#!/usr/bin/env bb
;; lg-dougaka test runner (repo rule: run_tests.clj, NOT .sh; ADR-2606280030).
;; Usage: from 60-apps/etzhayyim-project-dougaka/lg/  →  bb run_tests.clj
;; Uses the scoped bb.edn (src + tests on the classpath; langgraph-clj dep).
(require '[clojure.test :as t]
         '[lg-dougaka.test-smoke]
         '[lg-dougaka.server :as server]
         '[org.httpkit.server :as httpkit])

(defn host-config []
  {:port (Integer/parseInt (or (System/getenv "PORT") "8000"))
   :api-key (or (System/getenv "LG_API_KEY") "")})

(defn serve! []
  (let [{:keys [port api-key]} (host-config)
        stop (server/run-server-with httpkit/run-server port api-key)]
    (println (str "lg-dougaka listening on :" port
                  " graphs=" (vec (keys server/GRAPHS))))
    stop))

(let [{:keys [fail error]} (t/run-tests 'lg-dougaka.test-smoke)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
