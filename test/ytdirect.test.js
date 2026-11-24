'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { makeTask, waitSettled, tmpDir } = require('./helpers');
const tools = require('../src/core/tools');

const skip = tools.findFfmpeg({}, null) ? false : 'ffmpeg is needed';

test('site video: video + audio streams are fetched with several connections and joined', { skip }, async () => {
  delete process.env.FAKE_TOKENFILE;
  const dir = tmpDir('vg-direct-');
  const v = path.join(dir, 'v.mp4');
  const a = path.join(dir, 'a.m4a');
  let r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=1280x720:rate=30:duration=20', '-c:v', 'libx264', '-b:v', '6M', '-minrate', '6M', '-maxrate', '6M', '-bufsize', '6M', '-x264-params', 'nal-hrd=cbr:force-cfr=1', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', v]);
  assert.equal(r.status, 0, String(r.stderr));
  r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=20', '-c:a', 'aac', '-movflags', '+faststart', a]);
  assert.equal(r.status, 0, String(r.stderr));
  const files = { '/v.mp4': fs.readFileSync(v), '/a.m4a': fs.readFileSync(a) };
  assert.ok(files['/v.mp4'].length > 8 * 1024 * 1024, 'video must be big enough to be split');
  const ranges = [];
  const server = http.createServer((req, res) => {
    const body = files[req.url.split('?')[0]];
    if (!body) { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'application/octet-stream', 'accept-ranges': 'bytes' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; ranges.push(req.url + ' ' + start); }
    h['content-length'] = end - start + 1;
    res.writeHead(status, h);
    const part = body.subarray(start, end + 1);
    let off = 0;
    const pump = () => { // paced, so the download lasts long enough to observe
      if (res.destroyed) return;
      if (off >= part.length) return res.end();
      res.write(part.subarray(off, off + 262144));
      off += 262144;
      setTimeout(pump, 120);
    };
    pump();
  });
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const base = `http://127.0.0.1:${server.address().port}`;
  process.env.FAKE_V = base + '/v.mp4';
  process.env.FAKE_A = base + '/a.m4a';
  process.env.FAKE_VSIZE = String(files['/v.mp4'].length);
  process.env.FAKE_ASIZE = String(files['/a.m4a'].length);

  const out = tmpDir('vg-direct-out-');
  const { task } = makeTask({ url: 'https://www.youtube.com/watch?v=abc', kind: 'ytdlp', ytdlp: { height: 720 }, connections: 8 }, {
    downloadDir: out, ytdlpPath: path.join(__dirname, 'fixtures', 'fake-ytdlp.js'), maxConnections: 8,
  });
  task.ctx.binDir = out;
  let sawSegments = 0;
  const poll = setInterval(() => { sawSegments = Math.max(sawSegments, task.rec.segments.length); }, 25);
  task.start();
  await waitSettled(task);
  clearInterval(poll);
  server.closeAllConnections?.();
  server.close();
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(task.rec.ytMeta.streamed, true, 'video and audio were joined while downloading');
  const final = task.rec.savePath;
  assert.ok(fs.existsSync(final) && /\.mp4$/.test(final));
  const probe = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', final], { encoding: 'utf8' });
  assert.match(probe.stdout, /video/);
  assert.match(probe.stdout, /audio/);
  const videoRanges = ranges.filter((x) => x.startsWith('/v.mp4'));
  assert.ok(new Set(videoRanges).size >= 4, 'several range requests for the video: ' + videoRanges.length);
  assert.ok(sawSegments >= 4, 'progress window gets connection segments: ' + sawSegments);
  for (let i = 0; i < 40 && fs.readdirSync(out).some((n) => n.startsWith('.')); i++) await new Promise((r) => setTimeout(r, 100)); // cleanup runs in the background
  assert.deepEqual(fs.readdirSync(out).filter((n) => n.startsWith('.')), [], 'work directory removed');
});

test('site video: an expiring link mid-download is refreshed and the download RESUMES (no restart)', { skip }, async () => {
  const dir = tmpDir('vg-direct2-');
  const v = path.join(dir, 'v.mp4');
  const a = path.join(dir, 'a.m4a');
  let r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=1280x720:rate=30:duration=20', '-c:v', 'libx264', '-b:v', '6M', '-minrate', '6M', '-maxrate', '6M', '-bufsize', '6M', '-x264-params', 'nal-hrd=cbr:force-cfr=1', '-pix_fmt', 'yuv420p', v]);
  assert.equal(r.status, 0, String(r.stderr));
  r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=20', '-c:a', 'aac', '-movflags', '+faststart', a]);
  assert.equal(r.status, 0, String(r.stderr));
  const files = { '/v.mp4': fs.readFileSync(v), '/a.m4a': fs.readFileSync(a) };
  let served = 0;
  let requests = 0;
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    const body = files[u.pathname];
    if (!body) { res.writeHead(404); return res.end(); }
    // the first link (k=1) is revoked after a few requests, like an expiring signed URL
    if (u.searchParams.get('k') === '1' && ++requests > 2) { res.writeHead(403); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'application/octet-stream', 'accept-ranges': 'bytes' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; }
    h['content-length'] = end - start + 1;
    res.writeHead(status, h);
    served += end - start + 1;
    res.end(body.subarray(start, end + 1));
  });
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const base = `http://127.0.0.1:${server.address().port}`;
  const out = tmpDir('vg-direct2-out-');
  const log = path.join(out, 'ytdlp.log');
  process.env.FAKE_V = base + '/v.mp4';
  process.env.FAKE_A = base + '/a.m4a';
  process.env.FAKE_VSIZE = String(files['/v.mp4'].length);
  process.env.FAKE_ASIZE = String(files['/a.m4a'].length);
  process.env.FAKE_TOKENFILE = path.join(out, 'tok');
  process.env.FAKE_YTDLP_LOG = log;
  const { task } = makeTask({ url: 'https://www.youtube.com/watch?v=abc', kind: 'ytdlp', ytdlp: { height: 720 }, connections: 4 }, {
    downloadDir: out, ytdlpPath: path.join(__dirname, 'fixtures', 'fake-ytdlp.js'), maxConnections: 4,
  });
  task.ctx.binDir = out;
  task.start();
  await waitSettled(task);
  server.closeAllConnections?.();
  server.close();
  delete process.env.FAKE_TOKENFILE;
  delete process.env.FAKE_YTDLP_LOG;
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const calls = fs.readFileSync(log, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  assert.ok(calls.filter((c) => c.includes('-J')).length >= 2, 'fresh links were requested');
  assert.ok(!calls.some((c) => c.includes('-o')), "yt-dlp's own downloader was not used (no restart from zero)");
  const size = files['/v.mp4'].length + files['/a.m4a'].length;
  assert.ok(served < size * 1.5, `data was not downloaded twice: served ${served} for ${size}`);
});

test('site video: the exact picked streams are looked for first; if they are not offered the closest plain files are used and the user is told', { skip }, async () => {
  const dir = tmpDir('vg-direct3-');
  const v = path.join(dir, 'v.mp4');
  const a = path.join(dir, 'a.m4a');
  let r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=30:duration=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', v]);
  assert.equal(r.status, 0, String(r.stderr));
  r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=duration=4', '-c:a', 'aac', '-movflags', '+faststart', a]);
  assert.equal(r.status, 0, String(r.stderr));
  const files = { '/v.mp4': fs.readFileSync(v), '/a.m4a': fs.readFileSync(a) };
  const server = http.createServer((req, res) => {
    const body = files[req.url.split('?')[0]];
    if (!body) { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'application/octet-stream', 'accept-ranges': 'bytes' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; }
    h['content-length'] = end - start + 1;
    res.writeHead(status, h);
    res.end(body.subarray(start, end + 1));
  });
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const base = `http://127.0.0.1:${server.address().port}`;
  process.env.FAKE_V = base + '/v.mp4';
  process.env.FAKE_A = base + '/a.m4a';
  process.env.FAKE_VSIZE = String(files['/v.mp4'].length);
  process.env.FAKE_ASIZE = String(files['/a.m4a'].length);
  const out = tmpDir('vg-direct3-out-');
  // the fake engine offers streams "v" + "a" only: asking for "zz" + "a" must not silently succeed as if it were exact
  const { task } = makeTask({ url: 'https://www.youtube.com/watch?v=abc', kind: 'ytdlp', ytdlp: { height: 360, vid: 'zz', aid: 'a' }, connections: 4 }, {
    downloadDir: out, ytdlpPath: path.join(__dirname, 'fixtures', 'fake-ytdlp.js'), maxConnections: 4,
  });
  task.ctx.binDir = out;
  task.start();
  await waitSettled(task);
  server.closeAllConnections?.();
  server.close();
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.match(task.rec.warning || '', /exact quality you picked/);
});
