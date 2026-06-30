(ns dougaka.graphs.registry
  (:require [dougaka.graphs.render :as render]))

(defn build []
  (let [render-g (render/make)]
    {"ai.gftd.apps.dougaka.health"
     {:assistant :health
      :handler (fn [_ _] {:ok true :status "ok" :app "dougaka.gftd.ai" :impl "clj"
                          :graphs ["health" "render"]})}

     "ai.gftd.apps.dougaka.render"
     {:assistant :render :handler (render/handler render-g)}}))

(defn nsids [registry] (vec (sort (keys registry))))

(defn resolve-entry [registry ident]
  (or (get registry ident)
      (some (fn [[_ entry]] (when (= (name (:assistant entry)) ident) entry)) registry)))
