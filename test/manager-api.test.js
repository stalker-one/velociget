'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { makeData, sha, tmpDir, fileServer, waitUntil } = require('./helpers');
const { DownloadManager } = require('../src/core/manager');
const { createApiServer } = require('../src/core/server');

async function newManager(settings = {}) {
  const dataDir = tmpDir('vget-data-');
  const downloadDir = tmpDir('vget-dl-');
  const m = new DownloadManager({ dataDir, settings: { downloadDir, categorize: false, ...settings } });
  await m.init();
  return { m, dataDir, downloadDir };
}

test('queue never runs more than maxConcurrent downloads at once', async () => {
  const data = makeData(3 * 1024 * 1024);
  const srv = await fileServer(data, { chunkDelay: 10 });
  const { m, downloadDir } = await newManager({ maxConcurrent: 2 });
  let peak = 0;
  m.on('progress', () => {
    peak = Math.max(peak, m.list().filter((d) => ['downloading', 'probing', 'merging'].includes(d.state)).length);
  });
  for (let i = 0; i < 5; i++) m.add({ url: srv.url(`/file.bin?n=${i}`), fileName: `f${i}.bin` });
  await waitUntil(() => m.list().every((d) => d.state === 'completed'), 30_000);
  assert.ok(peak <= 2 && peak >= 1, `peak concurrency was ${peak}`);
  for (let i = 0; i < 5; i++) assert.equal(sha(fs.readFileSync(path.join(downloadDir, `f${i}.bin`))), sha(data));
  await m.shutdown();
  await srv.close();
});

test('state survives a restart; interrupted downloads come back paused and resume', async () => {
  const data = makeData(20 * 1024 * 1024);
  const srv = await fileServer(data, { chunkDelay: 15 });
  const { m, dataDir, downloadDir } = await newManager();
  const { id } = m.add({ url: srv.url(), fileName: 'big.bin', connections: 4 });
  await waitUntil(() => m.get(id).downloadedBytes > 3 * 1024 * 1024);
  await m.shutdown();

  const m2 = new DownloadManager({ dataDir, settings: { downloadDir, categorize: false } });
  await m2.init();
  const again = m2.list()[0];
  assert.equal(again.state, 'paused');
  assert.ok(again.downloaded > 0);
  m2.start(again.id);
  await waitUntil(() => m2.get(again.id).rec.state === 'completed', 30_000);
  assert.equal(sha(fs.readFileSync(path.join(downloadDir, 'big.bin'))), sha(data));
  await m2.shutdown();
  await srv.close();
});

test('duplicate URLs are not added twice', async () => {
  const srv = await fileServer(makeData(800 * 1024));
  const { m } = await newManager();
  const a = m.add({ url: srv.url(), startNow: false });
  const b = m.add({ url: srv.url(), startNow: false });
  assert.equal(b.duplicate, true);
  assert.equal(a.id, b.id);
  await m.shutdown();
  await srv.close();
});

test('scheduler starts and stops a queue at the configured minute', async () => {
  const srv = await fileServer(makeData(8 * 1024 * 1024), { chunkDelay: 30 });
  const { m } = await newManager();
  const { id } = m.add({ url: srv.url(), startNow: false, queueId: 'main' });
  m.saveQueue({ id: 'main', schedule: { enabled: true, startAt: '03:15', stopAt: '03:45', days: [0, 1, 2, 3, 4, 5, 6] } });
  const day = new Date(2026, 0, 7, 3, 15, 5);
  m._schedulerTick(day);
  await waitUntil(() => ['queued', 'probing', 'downloading'].includes(m.get(id).rec.state));
  m._schedulerTick(new Date(2026, 0, 7, 3, 45, 1));
  await waitUntil(() => m.get(id).rec.state === 'paused');
  await m.shutdown();
  await srv.close();
});

test('API: token, origin and host checks', async () => {
  const data = makeData(900 * 1024);
  const srv = await fileServer(data);
  const { m, downloadDir } = await newManager();
  const api = createApiServer(m);
  const port = await api.listen(0);
  const base = `http://127.0.0.1:${port}`;
  const token = m.getSettings().apiToken;
  const post = (p, body, headers = {}) =>
    fetch(base + p, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });

  assert.equal((await fetch(base + '/api/ping')).status, 200);
  assert.equal((await post('/api/add', { url: srv.url() })).status, 401, 'no token');
  assert.equal((await post('/api/add', { url: srv.url() }, { authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await post('/api/add', { url: srv.url() }, { authorization: `Bearer ${token}`, origin: 'https://evil.example' })).status, 403, 'web page origin');
  const bad = await post('/api/add', { url: 'file:///etc/passwd' }, { authorization: `Bearer ${token}` });
  assert.equal(bad.status, 500);

  const pre = await fetch(base + '/api/add', { method: 'OPTIONS', headers: { origin: 'chrome-extension://abcdefghijklmnop', 'access-control-request-method': 'POST' } });
  assert.equal(pre.status, 204);
  assert.equal(pre.headers.get('access-control-allow-origin'), 'chrome-extension://abcdefghijklmnop');

  const ok = await post('/api/add', { url: srv.url(), fileName: 'viaapi.bin', referer: 'https://x.test/' }, { authorization: `Bearer ${token}`, origin: 'chrome-extension://abcdefghijklmnop' });
  assert.equal(ok.status, 200);
  const { id } = await ok.json();
  await waitUntil(() => m.get(id).rec.state === 'completed');
  assert.equal(sha(fs.readFileSync(path.join(downloadDir, 'viaapi.bin'))), sha(data));

  const manifestBody = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhttps://cdn.example/high.m3u8\n';
  const hls = await post('/api/probe-media', {
    kind: 'hls', url: 'http://127.0.0.1:1/expired-master.m3u8', manifestBody,
  }, { authorization: `Bearer ${token}`, origin: 'chrome-extension://abcdefghijklmnop' });
  assert.equal(hls.status, 200);
  const hlsInfo = await hls.json();
  assert.equal(hlsInfo.master, true);
  assert.equal(hlsInfo.variants[0].label, '1080p');

  // Host header must be loopback (DNS-rebinding defence).
  const http = require('http');
  const status = await new Promise((resolve) => {
    http.get({ host: '127.0.0.1', port, path: '/api/ping', headers: { host: 'evil.example:80' } }, (r) => resolve(r.statusCode));
  });
  assert.equal(status, 403);

  await api.close();
  await m.shutdown();
  await srv.close();
});
