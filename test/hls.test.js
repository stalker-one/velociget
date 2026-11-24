'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { makeData, sha, makeTask, waitSettled } = require('./helpers');

function hlsServer({ encrypted = false, drm = false, live = false } = {}) {
  const key = crypto.randomBytes(16);
  const plain = Array.from({ length: 10 }, (_, i) => makeData(120_000 + i * 1000, i + 5));
  const segs = plain.map((p, i) => {
    if (!encrypted) return p;
    const iv = Buffer.alloc(16);
    iv.writeUInt32BE(i, 12); // media sequence number, starting at 0
    const c = crypto.createCipheriv('aes-128-cbc', key, iv);
    return Buffer.concat([c.update(p), c.final()]);
  });
  const server = http.createServer((req, res) => {
    const u = req.url;
    if (u === '/master.m3u8') {
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' });
      return res.end('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhigh.m3u8\n');
    }
    if (u === '/low.m3u8') {
      res.writeHead(200);
      return res.end('#EXTM3U\n#EXTINF:4,\nlow0.ts\n#EXT-X-ENDLIST\n');
    }
    if (u === '/high.m3u8') {
      let body = '#EXTM3U\n#EXT-X-TARGETDURATION:4\n';
      if (encrypted) body += '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n';
      if (drm) body += '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"\n';
      segs.forEach((_, i) => (body += `#EXTINF:4,\nseg${i}.ts\n`));
      if (!live) body += '#EXT-X-ENDLIST\n';
      res.writeHead(200);
      return res.end(body);
    }
    if (u === '/key.bin') return res.end(key);
    const m = /^\/seg(\d+)\.ts$/.exec(u);
    if (m) {
      res.writeHead(200, { 'content-type': 'video/mp2t' });
      return res.end(segs[Number(m[1])]);
    }
    res.writeHead(404);
    res.end();
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, plain, port: server.address().port })));
}

const noFfmpeg = { ffmpegPath: 'disabled' };
const closeSrv = (s) => {
  s.server.closeAllConnections?.();
  s.server.close();
};

test('HLS: picks the best variant, downloads segments in order', async (t) => {
  const s = await hlsServer();
  const { task, dir } = makeTask({ url: `http://127.0.0.1:${s.port}/master.m3u8`, kind: 'hls', fileName: 'clip', connections: 4 }, noFfmpeg);
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const files = fs.readdirSync(dir);
  const out = files.find((f) => f.startsWith('clip.') && !f.endsWith('.part'));
  assert.ok(out, `no output in ${files}`);
  assert.equal(sha(fs.readFileSync(path.join(dir, out))), sha(Buffer.concat(s.plain)));
  assert.equal(files.filter((f) => f.includes('.part')).length, 0, 'work files are cleaned up');
  closeSrv(s);
});

test('HLS: AES-128 segments are decrypted', async () => {
  const s = await hlsServer({ encrypted: true });
  const { task, dir } = makeTask({ url: `http://127.0.0.1:${s.port}/high.m3u8`, kind: 'hls', fileName: 'enc' }, noFfmpeg);
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const out = fs.readdirSync(dir).find((f) => f.startsWith('enc.'));
  assert.equal(sha(fs.readFileSync(path.join(dir, out))), sha(Buffer.concat(s.plain)));
  closeSrv(s);
});

test('HLS: DRM-protected streams are refused with a clear message', async () => {
  const s = await hlsServer({ drm: true });
  const { task } = makeTask({ url: `http://127.0.0.1:${s.port}/high.m3u8`, kind: 'hls' }, noFfmpeg);
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'error');
  assert.match(task.rec.error, /DRM/);
  closeSrv(s);
});

test('HLS: live streams are refused', async () => {
  const s = await hlsServer({ live: true });
  const { task } = makeTask({ url: `http://127.0.0.1:${s.port}/high.m3u8`, kind: 'hls' }, noFfmpeg);
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'error');
  assert.match(task.rec.error, /Live/);
  closeSrv(s);
});

test('HLS: variant list for the extension quality menu', async () => {
  const s = await hlsServer();
  const { listVariants } = require('../src/core/hls');
  const r = await listVariants(`http://127.0.0.1:${s.port}/master.m3u8`);
  assert.equal(r.master, true);
  assert.deepEqual(r.variants.map((v) => v.label), ['720p', '180p']);
  closeSrv(s);
});

test('HLS: a captured master body supplies variants without refetching a signed URL', async () => {
  const { listVariants } = require('../src/core/hls');
  const body = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhigh.m3u8\n';
  const r = await listVariants('http://127.0.0.1:1/expired-master.m3u8', {}, undefined, body);
  assert.equal(r.master, true);
  assert.deepEqual(r.variants.map((v) => v.label), ['1080p', '360p']);
});

test('HLS: download uses a captured master body after the original URL expires', async () => {
  const s = await hlsServer();
  const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhigh.m3u8\n';
  const { task, dir } = makeTask({
    url: `http://127.0.0.1:${s.port}/expired-master.m3u8`, kind: 'hls', fileName: 'captured',
    hls: { manifestBody: master },
  }, noFfmpeg);
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const out = fs.readdirSync(dir).find((f) => f.startsWith('captured.') && !f.endsWith('.part'));
  assert.ok(out);
  assert.equal(sha(fs.readFileSync(path.join(dir, out))), sha(Buffer.concat(s.plain)));
  closeSrv(s);
});

test('HLS: if ffmpeg cannot convert, the raw stream is kept and a warning is set', async () => {
  const { spawnSync } = require('child_process');
  if (spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' }).status !== 0) return; // ffmpeg not installed here
  const s = await hlsServer();
  const { task, dir } = makeTask({ url: `http://127.0.0.1:${s.port}/high.m3u8`, kind: 'hls', fileName: 'fallback' });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.match(task.rec.warning || '', /ffmpeg/);
  const out = fs.readdirSync(dir).find((f) => f.startsWith('fallback.'));
  assert.ok(out.endsWith('.ts'), out);
  assert.equal(sha(fs.readFileSync(path.join(dir, out))), sha(Buffer.concat(s.plain)));
  closeSrv(s);
});
