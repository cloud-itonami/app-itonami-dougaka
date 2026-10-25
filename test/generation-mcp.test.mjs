import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../bin/dougaka-generation-mcp.mjs';

test('generation MCP exposes five modalities and job lifecycle', async () => {
  const response = await handle({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert.deepEqual(response.result.tools.map(tool => tool.name), [
    'dougaka_models', 'dougaka_generate_image', 'dougaka_generate_video',
    'dougaka_generate_voice', 'dougaka_generate_music', 'dougaka_generate_sfx',
    'dougaka_job_status', 'dougaka_cancel_job', 'dougaka_save_artifact',
  ]);
});

test('invalid job ids fail before any network request', async () => {
  const response = await handle({ jsonrpc: '2.0', id: 2, method: 'tools/call',
    params: { name: 'dougaka_cancel_job', arguments: { job_id: '../bad' } } });
  assert.equal(response.result.isError, true);
  assert.match(response.result.content[0].text, /invalid job_id/);
});
