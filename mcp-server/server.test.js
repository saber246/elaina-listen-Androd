import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const serverPath = fileURLToPath(new URL('./server.js', import.meta.url));
const testToken = 'a'.repeat(43);

async function connect(env, cleanup) {
  const client = new Client({ name: 'elaina-test', version: '1.0.0' });
  cleanup(() => client.close());
  await client.connect(new StdioClientTransport({ command: process.execPath, args: [serverPath], env }));
  return client;
}

test('stdio exports the 12 tools and denies calls without a token', async (t) => {
  const client = await connect({ ELAINA_MCP_TOKEN: '' }, (fn) => t.after(fn));
  const { tools } = await client.listTools();
  assert.deepEqual(tools.map(({ name }) => name).sort(), [
    'add_bookmark', 'delete_bookmark', 'extract_article', 'get_playback_status',
    'list_bookmarks', 'open_webpage', 'pause_reading', 'resume_reading',
    'seek_reading', 'set_speed', 'set_voice', 'start_reading',
  ]);
  assert.equal(tools.find(({ name }) => name === 'delete_bookmark').annotations.destructiveHint, true);
  const result = await client.callTool({ name: 'get_playback_status', arguments: {} });
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /ELAINA_MCP_TOKEN/);
});

test('stdio forwards valid arguments and preserves permission errors from Android', async (t) => {
  const requests = [];
  const bridge = createServer((socket) => {
    let input = '';
    socket.setEncoding('utf8');
    socket.on('data', (data) => {
      input += data;
      if (!input.includes('\n')) return;
      const request = JSON.parse(input);
      requests.push(request);
      const response = request.tool === 'list_bookmarks'
        ? { ok: false, error: 'Bookmarks permission not granted.' }
        : { ok: true, result: { speed: request.args.speed } };
      socket.end(JSON.stringify(response) + '\n');
    });
  });
  await new Promise((resolve) => bridge.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => bridge.close((error) => error ? reject(error) : resolve())));
  const client = await connect({
    ELAINA_MCP_TOKEN: testToken, ELAINA_MCP_PORT: String(bridge.address().port),
  }, (fn) => t.after(fn));
  const invalid = await client.callTool({ name: 'set_speed', arguments: { speed: 4 } });
  assert.equal(invalid.isError, true);
  assert.equal(requests.length, 0);
  const response = await client.callTool({ name: 'set_speed', arguments: { speed: 1.5 } });
  assert.deepEqual(response.structuredContent, { speed: 1.5 });
  assert.deepEqual(requests[0], { token: testToken, tool: 'set_speed', args: { speed: 1.5 } });
  const denied = await client.callTool({ name: 'list_bookmarks', arguments: {} });
  assert.equal(denied.isError, true);
  assert.equal(denied.content[0].text, 'Bookmarks permission not granted.');
});
