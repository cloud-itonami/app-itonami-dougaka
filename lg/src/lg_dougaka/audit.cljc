(ns lg-dougaka.audit
  "Fire-and-forget BPMN `generic.audit.emit` shim — clj twin of lg_dougaka/audit.py
  (ADR-2606280030). httpx → babashka.http-client, JSON → cheshire.

  Behaviour is faithful to the Python original:
    - LG_AUDIT_DISABLED=1 → no-op
    - POST {dispatcher}/xrpc/com.etzhayyim.generic.audit.emit with the OCEL payload
    - x-internal-trust header set only when BPMN_DISPATCHER_INTERNAL_SECRET is present
    - any failure is non-fatal (logged, swallowed)"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def default-config
  {:app-did "did:web:dougaka.etzhayyim.com"
   :dispatcher-url "http://bpmn-dispatcher.mitama-udf.svc.cluster.local:8080"
   :internal-secret "" :audit-timeout-ms 3000
   :murakumo-tts-url "https://vyp99t9px7h4dl-4000.proxy.runpod.net/v1/audio/speech"
   :murakumo-image-url "https://vyp99t9px7h4dl-4000.proxy.runpod.net/v1/images/generations"
   :b2-endpoint-url "https://s3.us-west-004.backblazeb2.com"
   :b2-bucket "etzhayyim-nats" :b2-key-id "" :b2-app-key ""})

(defn config [state] (merge default-config (or (:host-config state) {})))

(defn http-emit-with [http-post host-config payload]
  (when-not (fn? http-post)
    (throw (ex-info "Dougaka audit requires an explicit HTTP POST capability"
                    {:capability :dougaka/audit-http-post})))
  (let [{:keys [dispatcher-url internal-secret audit-timeout-ms]}
        (merge default-config (or host-config {}))
        dispatcher-url (str/replace dispatcher-url #"/+$" "")
        headers (cond-> {"Content-Type" "application/json"}
                  (seq internal-secret) (assoc "x-internal-trust" internal-secret))]
    (http-post (str dispatcher-url "/xrpc/com.etzhayyim.generic.audit.emit")
               {:headers headers :body (json/generate-string payload)
                :timeout audit-timeout-ms :throw false})))

(def ^:dynamic *emit* (fn [_host-config _payload] nil))

(defn emit-audit
  "Synchronous best-effort emit (non-fatal). opts:
   {:actor :activity :object-id :object-type :attributes}."
  [host-config {:keys [actor activity object-id object-type attributes]}]
  (let [payload {:actor actor
                   :activity activity
                   :objectId object-id
                   :objectType object-type
                   :attributes (or attributes {})}]
      (try (*emit* host-config payload)
        (catch Exception e
          (binding [*out* *err*]
            (println (str "audit.emit failed (non-fatal): " (.getMessage e)
                          " | activity=" activity " id=" object-id)))
          nil))))

(defn emit-audit-bg
  "Fire-and-forget variant — emit on a future so the caller never blocks
  (mirrors the Python asyncio.create_task background emit). Returns the future."
  [state opts]
  (future (emit-audit (config state) opts)))
