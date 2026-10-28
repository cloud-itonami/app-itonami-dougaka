(ns lg-dougaka.graphs.health
  "dougaka `health` graph — liveness check (langgraph-clj port of
  lg_dougaka/graphs/health.py, ADR-2606280030).

  Topology (identical to the Python graph): START → :ping → END.
  The single node returns {:ok true}."
  (:require [langgraph.graph :as g]))

(defn node-ping
  "Liveness node — returns {:ok true} regardless of input state."
  [_state]
  {:ok true})

(defn build
  "Compile the health StateGraph (START → ping → END)."
  []
  (-> (g/state-graph)
      (g/add-node :ping node-ping)
      (g/set-entry-point :ping)
      (g/set-finish-point :ping)
      (g/compile-graph)))

(def GRAPH (build))
