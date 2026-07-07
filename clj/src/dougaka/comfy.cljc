(ns dougaka.comfy
  "ComfyUI keyframe rendering for dougaka (動画家) — facade over
  `comfyui.gateway` with dougaka's config, the same pattern as mangaka.comfy /
  animeka.comfy (ADR-2607011900 correction: config map + thin facade over the
  shared gateway, no third copy-paste).

  dougaka renders VERTICAL (720x1280) shot keyframes for short video /
  mini-drama production (ADR-2607071500). With no COMFY_POD_URL (falling back
  to COMFYUI_URL) it returns a placeholder, so the pipeline runs end-to-end
  without a GPU. img2vid (SVD/LTXV host binding) is the recorded follow-up —
  the node contracts exist in comfyui.nodes.diffusion but carry no host :fn
  yet; R0 motion comes from dougaka.assemble (per-shot stills → ffmpeg)."
  (:require [comfyui.gateway :as g]))

(def default-ckpt #?(:clj (or (System/getenv "DOUGAKA_DEFAULT_CKPT") "animagine-xl-4.0.safetensors")
                     :cljs "animagine-xl-4.0.safetensors"))

(def ^:private placeholder {:width 720 :height 1280 :label "dougaka keyframe (stub)"})

(def ^:private cfg
  {:node-type "DougakaKSampler" :default-ckpt default-ckpt
   :default-width 720 :default-height 1280})

(defn render-via-gateway
  "Renders `spec` via the OpenAI-images-compatible ComfyUI pod. `gw-cfg`
  supplies {:base :api-key :http-post} — injected so the success-parse and
  error-fallback branches are testable without a live pod. Any failure
  degrades to a placeholder; never throws."
  [gw-cfg spec]
  (g/render-via-gateway (assoc gw-cfg :require-api-key? false :placeholder placeholder) spec))

(defn gateway-render
  "Calls the dougaka ComfyUI pod with env config + the real http-kit
  transport."
  [spec]
  (g/gateway-render-from-env
   {:url-vars ["COMFY_POD_URL" "COMFYUI_URL"] :key-vars ["COMFY_POD_API_KEY" "COMFYUI_API_KEY"]
    :require-api-key? false :placeholder placeholder}
   spec))

(def ksampler-node (g/ksampler-node (assoc cfg :gateway-render gateway-render)))
(def save-node g/save-node)
(defn registry [] (g/registry ksampler-node))

(defn keyframe-workflow
  "API-format workflow (EDN) for one vertical txt2img shot keyframe."
  [spec]
  (g/workflow cfg spec))

(defn fresh-scratch
  "A throwaway in-memory conn for one workflow run's exec cache + run
  history."
  []
  (g/fresh-scratch))

(defn run
  "Execute `workflow` against the dougaka registry on a fresh scratch conn."
  [workflow]
  (g/run (registry) workflow))

(defn render-keyframe
  "One vertical shot keyframe from a prompt → gateway render result
  ({:image-b64 :mime :source :seed})."
  [{:keys [prompt seed width height model]
    :or {width 720 height 1280}}]
  (gateway-render {:prompt prompt :seed seed :width width :height height
                   :model (or model default-ckpt)}))
