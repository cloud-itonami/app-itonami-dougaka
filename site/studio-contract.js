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
  ],
});

export const requestedCapabilities = Object.freeze([capability]);
export const hostSupportedOperations = Object.freeze(runtimeDescriptor.operations.map(
  ({ operation: name }) => [capability, service, name]
));
export const selector = (name) => ({ capability, service, operation: name });
