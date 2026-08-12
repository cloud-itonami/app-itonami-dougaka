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
  "Defaults merged under any caller-supplied `:host-config`.

  `:murakumo-tts-url` / `:murakumo-image-url` are unset (\"\") on purpose, the
  same way `:b2-key-id` / `:b2-app-key` are: they name an endpoint this process
  does not own, so the host injects them or the capability stays unconfigured.

  They previously defaulted to `https://vyp99t9px7h4dl-4000.proxy.runpod.net`,
  an ephemeral RunPod pod id. RunPod releases those hostnames and can reassign
  them to another tenant, so the default named a host someone else may come to
  own — an unacceptable default for a value that is handed to a renderer as the
  place to send narration text and image prompts.

  These are NOT routed through the `murakumo-main` alias (ADR-2607173100), and
  that is a deliberate decision rather than an omission. The alias is a *chat*
  entry: it resolves to `/v1/chat/completions`, and says nothing about speech
  synthesis or image generation. The fleet's actual generation API is a
  different shape on a different host — an authenticated enqueue/poll
  (`POST /api/v1/generation` on generation.murakumo.cloud, which needs a
  generation-scope bearer token), not an OpenAI-compatible `/v1/audio/speech`
  or `/v1/images/generations`. Pointing these keys at the alias endpoint would
  produce a URL that resolves but cannot serve these paths, which is worse than
  an obviously-empty default. Wiring dougaka to the real generation API is a
  separate piece of work: it needs a token, the enqueue/poll state machine, and
  a `*render-fn*` that actually exists (today's default throws).

  Note that nothing calls these URLs at present — `render/*render-fn*` is an
  unimplemented boundary that throws unless the host rebinds it, so the stale
  hostname was reachable only by a renderer nobody has written yet."
  {:app-did "did:web:dougaka.etzhayyim.com"
   :dispatcher-url "http://bpmn-dispatcher.mitama-udf.svc.cluster.local:8080"
   :internal-secret "" :audit-timeout-ms 3000
   :murakumo-tts-url "" :murakumo-image-url ""
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
