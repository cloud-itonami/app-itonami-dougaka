(ns dougaka.view
  "dougaka の画面 —— 状態 -> hiccup の純関数。

  同じ関数を 2 か所が描く:
  - **SSR**（site/dougaka/page.cljk）: 初期状態で 1 回描き、JS 無しでも
    「何の道具か・データは送信されない」が読める HTML にする。
  - **ブラウザ**（site/app.cljk）: Reagent が同じ hiccup を描き直す。

  イベントは引数の `h` から取る: `(h :click ev)` `(h :value ev)` が handler
  （または nil）を返す。SSR は常に nil を返す h を渡す。

  hiccup は React 安全に書く: :style を使わない、:class は文字列。
  見た目は DADS の class と、page.cljk の app-css だけで作る。"
  (:require [kotoba.lang.text :as text]
            [dougaka.core :as core]
            [dougaka.export :as export]))

(def initial-model
  {:import {:text "" :aspect "portrait" :error nil}
   :plan nil
   :report nil})

(defn- with-handler [m k f] (if f (assoc m k f) m))

(defn- btn
  ([h ev label] (btn h ev label {}))
  ([h ev label {:keys [type size disabled aria-label]}]
   [:button (with-handler (cond-> {:type "button" :class "dads-button"
                                   :data-type (or type "outline") :data-size (or size "sm")}
                            disabled (assoc :disabled true)
                            aria-label (assoc :aria-label aria-label))
              :on-click (h :click ev))
    label]))

(defn- text-area [h ev value opts]
  [:span {:class "dads-textarea"}
   [:textarea (with-handler (merge {:class "dads-textarea__textarea" :rows 10 :value (or value "")} opts)
                :on-change (h :value ev))]])

(defn- select-el [h ev value options opts]
  [:span {:class "dads-select"}
   [:span {:class "dads-select__control"}
    (into [:select (with-handler (merge {:class "dads-select__select" :data-size "sm"
                                         :value (str value)}
                                        opts)
                     :on-change (h :value ev))]
          (map (fn [[v label]] [:option {:value (str v)} label]) options))
    [:svg {:class "dads-select__chevron" :width 16 :height 16 :viewBox "0 0 24 24" :aria-hidden "true"}
     [:path {:d "M12 17L3 8L4 7L12 15L20 7L21 8L12 17Z" :fill "currentcolor"}]]]])

(defn- field [id label control & [support]]
  [:div {:class "dads-form-control-label" :data-size "sm"}
   [:label {:class "dads-form-control-label__label" :for id} label]
   (when support [:p {:class "dads-form-control-label__support-text"} support])
   [:div control]])

(defn- notice [tone text]
  [:p {:class "dougaka-notice" :data-tone tone} text])

(defn- plan-table [plan]
  [:div {:class "dougaka-table"}
   [:table {:class "dads-table"}
    [:thead [:tr [:th "scene"] [:th "frame"] [:th "voice / text"]]]
    (into [:tbody]
          (map-indexed
           (fn [i s]
             [:tr [:td {:class "dougaka-num"} (str (get s "scene-index"))]
              [:td [:code (get s "frame-blob-key")]]
              [:td (let [vs (get s "voice-blob-keys") ts (get s "texts")]
                     (if (seq vs)
                       (text/join ", " (map (fn [v t] (str v (when (seq t) (str "「" t "」")))) vs ts))
                       "（無音）"))]])
           (get plan "segments")))]])

(defn main
  "状態 -> hiccup。handlers は site/app.cljk の h 対応（SSR では空）。"
  ([model] (main model {}))
  ([model handlers]
   (let [h (or (:h handlers) (fn [_ _] nil))
         import (:import model)
         plan (:plan model)
         report (:report model)]
     [:div {:class "dougaka"}
      [:div {:class "dougaka-header"}
       [:h1 {:class "dads-heading"} "dougaka — 動画組み立てプラン"]
       [:p {:class "dougaka-lead"}
        "timeline の JSON から ffmpeg の組み立て計画（segments / 解像度 / 台本）を作る。"
        "ブラウザの中だけで動き、入力は送信されない（外部リクエストはゼロ）。mp4 のエンコードはしない。"]]
      [:section {:class "dougaka-panel"}
       [:h2 {:class "dads-heading"} "1. timeline を貼る"]
       (field "dougaka-text" "timeline JSON"
              (text-area h [:import/text] (:text import) {:id "dougaka-text"})
              "scenes / assets / lines を持つ JSON。本家 dougaka の render 入力と同形。")
       (field "dougaka-aspect" "アスペクト"
              (select-el h [:import/aspect] (:aspect import)
                         [["portrait" "縦 9:16（720x1280）"] ["landscape" "横 16:9（1280x720）"]] {}))
       [:div {:class "dougaka-row"}
        (btn h [:import/run] "計画を作る" {:type "primary"})
        (btn h [:import/sample] "サンプルを入れる" {})]
       (when-let [e (:error import)] (notice "error" e))]
      (when report
        [:section {:class "dougaka-panel"}
         [:h2 {:class "dads-heading"} "2. 検証"]
         (if (seq (:errors report))
           (into [:ul {:class "dougaka-errors"}]
                 (map (fn [e] [:li [:code e]]) (:errors report)))
           (notice "ok" "検証 OK。足りない scene frame はありません。"))])
      (when plan
        [:section {:class "dougaka-panel"}
         [:h2 {:class "dads-heading"} "3. 計画"]
         [:dl {:class "dougaka-facts"}
          [:dt "video-id"] [:dd [:code (get plan "video-id")]]
          [:dt "aspect"] [:dd (str (get plan "aspect") " " (get plan "width") "x" (get plan "height") " @" (get plan "fps") "fps")]
          [:dt "segments"] [:dd (str (get plan "segment-count"))]]
         (plan-table plan)
         [:h3 {:class "dads-heading"} "ffmpeg 台本（表示用）"]
         [:pre {:class "dougaka-pre"} (export/ffmpeg-script plan)]
         [:div {:class "dougaka-row"}
          (btn h [:export/plan] "計画 JSON を保存" {})
          (btn h [:export/script] "台本 .sh を保存" {})
          (btn h [:clear/run] "消去する" {})]])
      [:footer {:class "dougaka-footer"}
       [:p "計画はこのブラウザの中だけで作る。localStorage にだけ残り、「消去する」で消える。"
        "本家 dougaka（動画生成・mp4 エンコード）とは違い、生成 AI・B2・Kotobase への接続はない。"]]])))
