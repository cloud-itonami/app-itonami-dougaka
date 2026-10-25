#!/usr/bin/env node
// One autonomous generation → verified artifact → immutable IPFS publication.
import { readFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { handle } from './dougaka-generation-mcp.mjs';
import { fileJournal, preflightPublication, publishConfigured, runAutoPublish } from './dougaka-auto-publish-core.mjs';

export async function main(args = process.argv.slice(2)) {
  if (args.length !== 1) throw Error('usage: dougaka-auto-publish.mjs REQUEST.json');
  if (!process.env.MURAKUMO_GENERATION_TOKEN?.trim()) throw Error('MURAKUMO_GENERATION_TOKEN is required');
  for (const key of ['DOUGAKA_OUTPUT_DIR', 'DOUGAKA_RUN_DIR']) {
    const value = process.env[key];
    if (!value || !(await stat(resolve(value))).isDirectory()) throw Error(`${key} must name an existing directory`);
  }
  await preflightPublication();
  const input = JSON.parse(await readFile(resolve(args[0]), 'utf8'));
  const call = async (name, parameters) => {
    const response = await handle({ jsonrpc: '2.0', id: 1, method: 'tools/call',
      params: { name, arguments: parameters } });
    const result = response?.result;
    if (!result || result.isError) throw Error(result?.structuredContent?.error
      ?? result?.content?.[0]?.text ?? 'MCP call failed');
    return result.structuredContent;
  };
  return runAutoPublish(input, { call, publish: publishConfigured,
    journal: fileJournal(process.env.DOUGAKA_RUN_DIR) });
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  main().then(receipt => process.stdout.write(`${JSON.stringify(receipt)}\n`), error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
