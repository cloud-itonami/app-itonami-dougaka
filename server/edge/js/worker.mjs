/**
 * dougaka.itonami.cloud — the origin the actor's :discovery-document gate asks
 * for. Mechanism only: HTTP in, HTTP out, and one Kotoba guest instantiation.
 *
 * ## Why this file is .mjs when the workspace says write ClojureScript
 *
 * A Cloudflare Worker entry has to be JS, and the ban is on writing LOGIC in a
 * hand-written .mjs. There is no logic here. The offer document is computed by
 * `kotoba/offer.kotoba`, compiled to wasm32-browser by amu; this file reads the
 * request, calls one export, and writes the response — the same host/guest
 * split amu's own runtime/dom-driver.mjs uses, and the same reason: effects
 * belong to the host.
 *
 * The alternative was ClojureScript, which needs shadow-cljs, which needs a
 * JVM. Measured 2026-09-01 on this machine: that build waited 40 minutes
 * behind other sessions' `kbb -M:test` under load 628 and then timed out.
 * `amu compile --target wasm32-browser` needed none of it — proven by putting
 * failing stubs for java/javac/clojure/clj on PATH and watching it still emit
 * a .wasm.
 *
 * ## The module arrives as an import, not as bytes
 *
 * Workers refuse `WebAssembly.compile` at runtime, so the .wasm comes through
 * the bundler as a `WebAssembly.Module` and is handed to `instantiateKotoba`
 * that way. amu's host documents exactly this, measured against a deployed
 * Worker on 2026-08-31.
 *
 * ## What this origin does NOT serve, and why the document says so
 *
 * The render endpoint. `douga.ffmpeg/build-render-plan` is 524 lines and has
 * not been migrated to Kotoba, so the price book carries `:plan/served? false`
 * and the generated document advertises no priced resource at all. An origin
 * that advertised it would be repeating murakumo's 2026-07-31 incident: a path
 * advertised, paid for, verified, and then answered with "upstream is not
 * configured".
 */
import { instantiateKotoba } from "./browser-host.mjs";
import offerModule from "./offer.wasm";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

// One instance per request. `instantiateKotoba` opens a fixed fuel budget that
// is spent and never replenished, so a shared instance would answer for a
// while and then start trapping — the failure would look like a bad request
// rather than an exhausted one.
async function offerDocument() {
  const inst = await instantiateKotoba(offerModule, { allowCapabilities: [] });
  return inst.instance.exports.document();
}

function json(body, status) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

export default {
  async fetch(request) {
    const { pathname } = new URL(request.url);
    const method = request.method;

    if (method === "GET" && pathname === "/.well-known/x402") {
      // Passed through as the guest produced it: re-encoding a document that
      // was generated from the price book would put a second formatter between
      // the price and the buyer.
      return new Response(await offerDocument(), { status: 200, headers: JSON_HEADERS });
    }

    if (method === "GET" && pathname === "/xrpc/cloud.itonami.apps.dougaka.health") {
      return json({ ok: true, status: "ok", app: "dougaka.itonami.cloud",
                    impl: "kotoba-wasm32-browser" }, 200);
    }

    if (pathname === "/xrpc/cloud.itonami.apps.dougaka.render") {
      // 501 and not 404: the route exists, is priced in the actor's price book,
      // and is deliberately not advertised. A 404 would say it was never a
      // thing; this says what is missing and where that is recorded.
      return json({
        error: "not implemented here",
        detail: "the render plan builder (douga.ffmpeg/build-render-plan, 524 lines) "
              + "has not been migrated to Kotoba; a single-file guest cannot reach "
              + "the stdlib and the project linker is JVM-bound, which Q9 forbids",
        priced: "pricing.edn :render-plan, :plan/served? false",
        advertised: false,
      }, 501);
    }

    return json({ error: "not found",
                  serves: ["/.well-known/x402",
                           "/xrpc/cloud.itonami.apps.dougaka.health"] }, 404);
  },
};
