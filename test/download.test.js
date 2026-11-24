'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { makeData, sha, fileServer, makeTask, waitSettled, waitUntil } = require('./helpers');
const { Throttle } = require('../src/core/throttle');

const DATA = makeData(24 * 1024 * 1024);

test('segmented download is byte-exact and uses several connections', async () => {
  const srv = await fileServer(DATA);
  const { task, dir } = makeTask({ url: srv.url(), connections: 8 });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const out = fs.readFileSync(path.join(dir, 'file.bin'));
  assert.equal(sha(out), sha(DATA));
  const ranged = srv.log.filter((l) => l.range && l.range !== 'bytes=0-0');
  assert.ok(ranged.length >= 8, `expected >= 8 ranged requests, got ${ranged.length}`);
  assert.ok(!fs.existsSync(path.join(dir, 'file.bin.part')));
  await srv.close();
});

test('idle connections take over the tail of the slowest segment (dynamic splitting)', async () => {
  const srv = await fileServer(DATA, { chunkDelay: 2 });
  const { task, dir } = makeTask({ url: srv.url(), connections: 2 }, { maxConnections: 2 });
  task.start();
  await waitSettled(task);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'file.bin'))), sha(DATA));
  await srv.close();
});

test('falls back to a single connection when the server ignores Range', async () => {
  const srv = await fileServer(DATA.subarray(0, 4 * 1024 * 1024), { ranges: false });
  const { task, dir } = makeTask({ url: srv.url(), connections: 8 });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(task.rec.resumable, false);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'file.bin'))), sha(DATA.subarray(0, 4 * 1024 * 1024)));
  await srv.close();
});

test('recovers from dropped connections mid-transfer', async () => {
  const srv = await fileServer(DATA, { drops: 4 });
  const { task, dir } = makeTask({ url: srv.url(), connections: 4 });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'file.bin'))), sha(DATA));
  await srv.close();
});

test('pause then resume continues from saved offsets instead of restarting', async () => {
  const srv = await fileServer(DATA, { chunkDelay: 12 });
  const { task, dir, settings } = makeTask({ url: srv.url(), connections: 4 });
  task.start();
  await waitUntil(() => task.downloadedBytes > 4 * 1024 * 1024);
  task.pause();
  await waitSettled(task);
  assert.equal(task.rec.state, 'paused');
  const savedBefore = task.downloadedBytes;
  assert.ok(savedBefore > 0 && savedBefore < DATA.length);

  // Simulate an app restart: rebuild the task from its serialized record.
  const { DownloadTask } = require('../src/core/download-task');
  const copy = JSON.parse(JSON.stringify(task.serialize()));
  const task2 = new DownloadTask(copy, { throttle: new Throttle(0), settings: () => settings });
  const requestsBefore = srv.log.length;
  srv.log.length = 0;
  task2.start();
  await waitSettled(task2);
  assert.equal(task2.rec.state, 'completed', task2.rec.error);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'file.bin'))), sha(DATA));
  const starts = srv.log.filter((l) => l.range && l.range !== 'bytes=0-0').map((l) => Number(/bytes=(\d+)-/.exec(l.range)[1]));
  assert.ok(starts.every((s) => s > 0 || copy.segments[0].flushed === 0), 'resume must not re-request bytes already on disk');
  assert.ok(requestsBefore > 0);
  await srv.close();
});

test('a changed file on the server is not silently stitched together', async () => {
  const srv = await fileServer(DATA, { chunkDelay: 12 });
  const { task, dir, settings } = makeTask({ url: srv.url(), connections: 2 });
  task.start();
  await waitUntil(() => task.downloadedBytes > 2 * 1024 * 1024);
  task.pause();
  await waitSettled(task);
  await srv.close();

  const other = makeData(24 * 1024 * 1024, 99);
  const srv2 = await fileServer(other, { chunkDelay: 0 });
  const { DownloadTask } = require('../src/core/download-task');
  const copy = JSON.parse(JSON.stringify(task.serialize()));
  copy.url = srv2.url();
  copy.etag = '"v-old"'; // validator no longer matches
  const task2 = new DownloadTask(copy, { throttle: new Throttle(0), settings: () => settings });
  task2.start();
  await waitSettled(task2);
  assert.equal(task2.rec.state, 'completed', task2.rec.error);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'file.bin'))), sha(other), 'must restart from zero and match the new file');
  await srv2.close();
});

test('global speed limit is respected', async () => {
  const small = makeData(2 * 1024 * 1024);
  const srv = await fileServer(small);
  const throttle = new Throttle(1024 * 1024); // 1 MB/s
  const { task } = makeTask({ url: srv.url(), connections: 4 }, { throttle });
  const t0 = Date.now();
  task.start();
  await waitSettled(task);
  const dt = Date.now() - t0;
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.ok(dt >= 1500, `2 MB at 1 MB/s should take >= 1.5s, took ${dt}ms`);
  await srv.close();
});

test('follows redirects, picks the file name from Content-Disposition, sanitizes it', async () => {
  const small = makeData(1024 * 1024);
  const srv = await fileServer(small, { disposition: `attachment; filename*=UTF-8''r%C3%A9sum%C3%A9%3A1.bin` });
  const { task, dir } = makeTask({ url: srv.url('/redirect') });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(task.rec.fileName, 'résumé_1.bin');
  assert.ok(fs.existsSync(path.join(dir, 'résumé_1.bin')));
  await srv.close();
});

test('does not overwrite an existing file', async () => {
  const small = makeData(600 * 1024);
  const srv = await fileServer(small);
  const { task, dir } = makeTask({ url: srv.url() });
  fs.writeFileSync(path.join(dir, 'file.bin'), 'existing');
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.fileName, 'file (1).bin');
  assert.equal(fs.readFileSync(path.join(dir, 'file.bin'), 'utf8'), 'existing');
  await srv.close();
});

test('404 fails fast with a clear message and no retries', async () => {
  const srv = await fileServer(DATA);
  const { task } = makeTask({ url: srv.url('/missing') });
  const t0 = Date.now();
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'error');
  assert.match(task.rec.error, /404/);
  assert.ok(Date.now() - t0 < 2000);
  await srv.close();
});

test('forwards Referer, Cookie and User-Agent from the browser', async () => {
  const small = makeData(700 * 1024);
  const srv = await fileServer(small);
  const { task } = makeTask({ url: srv.url(), referer: 'https://example.com/page', cookie: 'sid=abc', userAgent: 'TestUA/1' });
  task.start();
  await waitSettled(task);
  const h = srv.log[srv.log.length - 1].headers;
  assert.equal(h.referer, 'https://example.com/page');
  assert.equal(h.cookie, 'sid=abc');
  assert.equal(h['user-agent'], 'TestUA/1');
  await srv.close();
});

test('backs off to what the server allows when it answers 429 to extra connections', async () => {
  const http = require('http');
  const data = DATA.subarray(0, 16 * 1024 * 1024);
  let active = 0, peak = 0, refused = 0;
  const server = http.createServer((req, res) => {
    if (active >= 2) { refused++; res.writeHead(429, { 'retry-after': '1' }); return res.end(); }
    active++; peak = Math.max(peak, active);
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = data.length - 1, st = 200;
    const h = { 'accept-ranges': 'bytes', 'content-type': 'application/octet-stream' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); st = 206; h['content-range'] = `bytes ${start}-${end}/${data.length}`; }
    h['content-length'] = end - start + 1;
    res.writeHead(st, h);
    res.on('close', () => { active--; });
    const part = data.subarray(start, end + 1);
    let off = 0;
    const pump = () => { if (res.destroyed) return; if (off >= part.length) return res.end(); res.write(part.subarray(off, off + 131072)); off += 131072; setTimeout(pump, 4); };
    pump();
  });
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const { task, dir } = makeTask({ url: `http://127.0.0.1:${server.address().port}/f.bin`, connections: 16 }, { maxConnections: 16 });
  task.start();
  await waitSettled(task);
  server.closeAllConnections?.();
  server.close();
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(sha(fs.readFileSync(path.join(dir, 'f.bin'))), sha(data));
  assert.ok(refused < 60, `stopped hammering the server: ${refused} refusals`);
});
