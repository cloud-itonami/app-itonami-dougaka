(ns dougaka.edge
  "dougaka.itonami.cloud —— the origin the x402 discovery gate needed.

  ## What this serves, and what it deliberately does not

  Two paths, both request-shaped:

    GET  /.well-known/x402                        the price book, as x402 reads it
    POST /xrpc/cloud.itonami.apps.dougaka.render  a timeline -> an ffmpeg plan
    GET  /xrpc/cloud.itonami.apps.dougaka.health  liveness

  It does NOT render video. The finished short is a production job measured in
  hours and x402 v1 proxies a request expecting a response; that mismatch is
  recorded in the actor's pricing.edn `:discovery-document` gate and is why the
  only SKU here is the plan. `dougaka.graphs.render`'s own note says the same
  thing from the other side: *mp4 encode/upload is intentionally not run
  in-process*.

  ## One implementation of the plan, not two

  `douga.ffmpeg/build-render-plan` is required directly rather than reimplemented
  in JS. It is a pure function over a timeline and its single reader conditional
  already carries a `:cljs` branch, so the same code answers here and in the JVM
  server. A second copy would be a second thing to fix.

  ## Payment is the facilitator's job, and this origin is open

  x402.nexus gates and proxies; this worker does not check for payment. So a
  caller who knows the origin gets a plan without paying. That is stated rather
  than hidden: the price is denominated on base-sepolia (testnet), nothing of
  value is being protected yet, and the honest fix when it is — a shared secret
  the gateway forwards, as murakumo does with `x-murakumo-x402-usd-micros` — is
  a change to make when the price stops being play money, not before."
  (:require [douga.ffmpeg :as plan]
            [dougaka.offer :as offer]))

(def ^:private json-headers
  #js {"content-type" "application/json; charset=utf-8"
       "cache-control" "no-store"})

(defn- json [body status]
  (js/Response. (js/JSON.stringify (clj->js body)) #js {:status status :headers json-headers}))

(defn- render-plan
  "timeline -> the same shape dougaka.graphs.render returns on the JVM.

  `:status \"planned\"` and not `\"rendered\"`: the plan is the product. A caller
  that reads this as a finished video would be reading a promise nothing here
  makes."
  [timeline]
  (let [p (plan/build-render-plan (or timeline {}))]
    {:status "planned"
     :impl "cljs-edge"
     :renderPlan p
     :segmentCount (count (:segments p))
     :note "plan only — mp4 encode/upload is intentionally not run in-process"}))

(defn- handle [^js request]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)
        method (.-method request)]
    (cond
      (and (= "GET" method) (= "/.well-known/x402" path))
      (js/Promise.resolve (json (offer/document) 200))

      (and (= "GET" method) (= "/xrpc/cloud.itonami.apps.dougaka.health" path))
      (js/Promise.resolve (json {:ok true :status "ok"
                                 :app "dougaka.itonami.cloud" :impl "cljs-edge"} 200))

      (and (= "POST" method) (= "/xrpc/cloud.itonami.apps.dougaka.render" path))
      (-> (.json request)
          (.then (fn [body]
                   (let [m (js->clj body :keywordize-keys true)]
                     (json (render-plan (or (:timeline m) m)) 200))))
          ;; A body that is not JSON is the caller's error and says so. Returning
          ;; a plan for `{}` would answer a malformed request with a real-looking
          ;; product.
          (.catch (fn [_] (json {:error "expected a JSON body: {\"timeline\": …}"} 400))))

      :else
      (js/Promise.resolve (json {:error "not found"
                                 :serves ["/.well-known/x402"
                                          "/xrpc/cloud.itonami.apps.dougaka.render"
                                          "/xrpc/cloud.itonami.apps.dougaka.health"]}
                                404)))))

(def app #js {:fetch (fn [request _env _ctx] (handle request))})
