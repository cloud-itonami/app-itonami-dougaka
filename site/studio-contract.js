// The static app declares only the Murakumo generation protocol service.
// The canonical validator and consent broker are copied from the pinned
// wasm-webcomponent revision during the build, never reimplemented here.
export const generationOrigin = 'https://generation.murakumo.cloud';
const service = 'murakumo-generation';
const capability = 'media/generation';
const operation = (name, method, path, categories) => ({
  capability,
  service,
  operation: name,
  consent: 'each-operation',
  'data-categories': categories,
  endpoint: { origin: generationOrigin, method, path },
});

// Browser-local Higgsfield management state. These operations cause no fetch:
// the endpoint carries a browser-action and an inert origin that is never
// requested (clientRuntimeConnectSrc skips browser-action operations, so the
// page CSP is unchanged). The origin must still be a valid https origin that
// differs from the app origin, so the consent screen names the local service
// instead of implying a network call.
export const localOrigin = 'https://local.itonami.cloud';
export const localCapability = 'media/project';
export const localService = 'dougaka-local';
const localOperation = (name, categories) => ({
  capability: localCapability,
  service: localService,
  operation: name,
  consent: 'each-operation',
  'data-categories': categories,
  endpoint: { origin: localOrigin, 'browser-action': name },
});

export const runtimeDescriptor = Object.freeze({
  schema: 'kotoba.app.client-runtime.v1',
  target: 'browser',
  profile: 'trusted-page-v1',
  entry: 'index.html',
  'local-state': { adapter: 'localstorage', schema: 'itonami.dougaka.v1', version: 1 },
  workers: [],
  operations: [
    operation('catalog', 'GET', '/api/v1/generation/catalog', []),
    operation('submit', 'POST', '/api/v1/generation', ['prompt', 'model', 'generation-settings', 'access-token']),
    operation('status', 'GET', '/api/v1/generation/jobs/:jobId', ['job-id', 'access-token']),
    operation('cancel', 'DELETE', '/api/v1/generation/jobs/:jobId', ['job-id', 'access-token']),
    operation('artifact', 'GET', '/api/v1/generation/jobs/:jobId/artifact', ['job-id', 'access-token']),
    localOperation('project', ['project-name', 'project-settings']),
    localOperation('brand-kit', ['brand-name', 'brand-assets']),
    localOperation('canvas', ['canvas-state']),
    localOperation('marketing', ['marketing-copy', 'share-targets']),
  ],
});

export const requestedCapabilities = Object.freeze([capability, localCapability]);
export const hostSupportedOperations = Object.freeze(runtimeDescriptor.operations.map(
  ({ capability: cap, service: svc, operation: name }) => [cap, svc, name]
));
export const selector = (name) => ({ capability, service, operation: name });
export const localSelector = (name) => ({ capability: localCapability, service: localService, operation: name });
