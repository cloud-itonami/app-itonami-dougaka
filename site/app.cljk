;; dougaka のブラウザ側。scittle が読む（site/site.edn の :sources の最後）。
;;
;; Re-frame が状態・event・subscription・effect を持ち、Reagent が描く
;; （cloud-itonami ADR-0052）。画面は dougaka.view/main の純関数で、SSR と同じもの。
;; DOM を直接いじるのは effect（保存・ダウンロード）の中だけ。
;;
;; ネットワークは使わない。fetch も XHR も無い。保存はこの端末の localStorage
;; だけで、読み書きは try で囲む（プライベートウィンドウ等で失敗しても動く）。

;; ns を宣言する —— scittle は各 script を同じ SCI 文脈で順に評価するので、
;; 宣言しないと直前の script（dougaka.view）の ns のまま評価される。
(ns dougaka.app
  (:require [clojure.edn :as edn]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [dougaka.core :as core]
            [dougaka.ingest :as ingest]
            [dougaka.export :as export]
            [dougaka.view :as view]))

(def storage-key "itonami.dougaka.v1")

;; ───────── effects / coeffects ─────────

(rf/reg-cofx
 :dougaka/stored
 (fn [cofx _]
   (assoc cofx :stored
          (try (some-> (.getItem js/localStorage storage-key) edn/read-string)
               (catch :default _ nil)))))

(rf/reg-fx
 :dougaka/persist
 (fn [saved]
   (try (if saved
          (.setItem js/localStorage storage-key (pr-str saved))
          (.removeItem js/localStorage storage-key))
        (catch :default _ nil))))

(rf/reg-fx
 :dougaka/download
 (fn [{:keys [filename mime body]}]
   (let [blob (js/Blob. #js [body] #js {:type mime})
         url (.createObjectURL js/URL blob)
         a (.createElement js/document "a")]
     (set! (.-href a) url)
     (set! (.-download a) filename)
     (.click a)
     (js/setTimeout #(.revokeObjectURL js/URL url) 1000))))

;; ───────── events ─────────

(rf/reg-event-fx
 :app/init
 [(rf/inject-cofx :dougaka/stored)]
 (fn [{stored :stored} _]
   (let [saved (when (map? stored) stored)]
     {:db (merge view/initial-model
                 (when (:import saved) {:import (:import saved)})
                 (when (:plan saved) {:plan (:plan saved)}))})))

(rf/reg-event-db :import/text (fn [m [_ v]] (assoc-in m [:import :text] v)))
(rf/reg-event-db :import/aspect (fn [m [_ v]] (assoc-in m [:import :aspect] v)))

(rf/reg-event-fx
 :import/run
 (fn [{m :db} _]
   (let [text (get-in m [:import :text])
         aspect (get-in m [:import :aspect])
         {:keys [timeline error]} (try (ingest/ingest text)
                                       (catch :default _ {:error "parse-failed: JSON ではありません"}))]
     (if error
       {:db (-> m
                (assoc-in [:import :error] error)
                (assoc :plan nil :report nil))}
       (let [{:keys [ok? errors]} (core/validate timeline)
             plan (when ok? (core/build-plan timeline {:aspect (or aspect "portrait")}))
             saved {:import {:text "" :aspect (or aspect "portrait") :error nil}
                    :plan plan}]
         {:db (-> m
                  (assoc-in [:import :error] nil)
                  (assoc-in [:import :text] "")
                  (assoc :plan plan :report {:errors errors}))
          :dougaka/persist {:import {:text "" :aspect (or aspect "portrait")}
                            :plan plan}})))))

(rf/reg-event-db
 :import/sample
 (fn [m _]
   (-> m
       (assoc-in [:import :text] ingest/sample-timeline)
       (assoc-in [:import :error] nil))))

(rf/reg-event-fx
 :export/plan
 (fn [{m :db} _]
   (when-let [plan (:plan m)]
     {:dougaka/download {:filename (str (get plan "video-id") "-plan.json")
                         :mime "application/json"
                         :body (export/plan-json plan)}})))

(rf/reg-event-fx
 :export/script
 (fn [{m :db} _]
   (when-let [plan (:plan m)]
     {:dougaka/download {:filename (str (get plan "video-id") "-assemble.sh")
                         :mime "text/x-sh"
                         :body (export/ffmpeg-script plan)}})))

(rf/reg-event-fx
 :clear/run
 (fn [_ _]
   {:db view/initial-model
    :dougaka/persist nil}))

;; ───────── subscription と描画 ─────────

(rf/reg-sub :model (fn [m _] m))

(defn- handlers
  "dougaka.view の h。ev は re-frame の event ベクタ。"
  [kind ev]
  (case kind
    :click (fn [_] (rf/dispatch ev))
    :value (fn [e] (rf/dispatch (conj ev (.. e -target -value))))
    nil))

(defn root []
  (let [model @(rf/subscribe [:model])]
    (view/main model {:h handlers})))

(rf/dispatch-sync [:app/init])
(rdom/render [root] (.getElementById js/document "app"))
