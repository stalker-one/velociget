'use strict';
/**
 * The picked quality must be the quality that is downloaded.
 *
 * The failure this protects against: the quality menu offered "1080p - 5.84 GB", the user picked it,
 * and the app downloaded a ~0.67 GB file instead, because the site answered the later look-up with a
 * smaller picture and the fallback selector ("height <= 1080") happily accepted it.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { makeTask, waitSettled, tmpDir } = require('./helpers');
const ytdlp = require('../src/core/ytdlp');

const FAKE = path.join(__dirname, 'fixtures', 'fake-ytdlp-degraded.js');
const haveFfmpeg = spawnSync('ffmpeg', ['-version']).status === 0;

/** Real media, so joining video and audio works exactly like it does on a real download. */
function media(dir) {
  const v = path.join(dir, 'v.mp4');
  const low = path.join(dir, 'low.mp4');
  const a = path.join(dir, 'a.m4a');
  const mk = (out, size) => {
    const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', `testsrc=size=${size}:rate=30:duration=2`, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  };
  mk(v, '1920x1080');
  mk(low, '640x360');
  const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=duration=2', '-c:a', 'aac', '-movflags', '+faststart', a], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  return { v: fs.readFileSync(v), low: fs.readFileSync(low), a: fs.readFileSync(a) };
}

/** A file server that records which stream was really fetched. */
async function server(files, refuse = []) {
  const served = [];
  const s = http.createServer((req, res) => {
    const name = req.url.split('?')[0];
    served.push(name);
    if (refuse.includes(name)) { res.writeHead(403); return res.end(); }
    const body = files[name];
    if (!body) { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'video/mp4', 'accept-ranges': 'bytes' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; }
    h['content-length'] = end - start + 1;
    res.writeHead(status, h);
    res.end(body.subarray(start, end + 1));
  });
  await new Promise((ok) => s.listen(0, '127.0.0.1', ok));
  return { served, base: `http://127.0.0.1:${s.address().port}`, close: () => { s.closeAllConnections?.(); s.close(); } };
}

async function runPick(env = {}, { refuse = [] } = {}) {
  const dir = tmpDir('vg-q-');
  const m = media(dir);
  const files = { '/v1080': m.v, '/v720': m.low, '/v360': m.low, '/a140': m.a };
  const srv = await server(files, refuse);
  process.env.FAKE_BASE = srv.base;
  process.env.FAKE_COUNTER = path.join(dir, 'counter');
  process.env.FAKE_VSIZE = '5840000';
  process.env.FAKE_LOWSIZE = '670000';
  process.env.FAKE_ASIZE = '80000';
  delete process.env.FAKE_RENUMBER;
  delete process.env.FAKE_HIDE_HIGH;
  Object.assign(process.env, env);
  const out = path.join(dir, 'out');
  fs.mkdirSync(out, { recursive: true });
  const { task } = makeTask({
    url: 'https://www.youtube.com/watch?v=V_-YdNa622Q',
    kind: 'ytdlp',
    // what the extension sends when the user picks the 1080p row of the quality menu
    ytdlp: { height: 1080, vid: '137', aid: '140', mode: 'clean' },
    connections: 4,
  }, { downloadDir: out, ytdlpPath: FAKE, maxConnections: 4 });
  task.ctx.binDir = out;
  task.start();
  await waitSettled(task);
  srv.close();
  delete process.env.FAKE_BASE;
  delete process.env.FAKE_COUNTER;
  delete process.env.FAKE_RENUMBER;
  delete process.env.FAKE_HIDE_HIGH;
  return { task, served: srv.served, dir };
}

test('the picked 1080p is downloaded, even when the site answers the later look-up with less', { skip: haveFfmpeg ? false : 'ffmpeg is needed' }, async () => {
  const { task, served } = await runPick();
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.ok(served.includes('/v1080'), 'the selected 1080p stream was fetched: ' + served.join(','));
  assert.ok(!served.includes('/v360'), 'no smaller stream was fetched: ' + served.join(','));
});

test('the same quality under another format id is still the picked quality', { skip: haveFfmpeg ? false : 'ffmpeg is needed' }, async () => {
  const { task, served } = await runPick({ FAKE_RENUMBER: '1' });
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.ok(served.includes('/v1080'), 'the 1080p stream was fetched: ' + served.join(','));
  assert.ok(!served.includes('/v360'), 'no smaller stream was fetched: ' + served.join(','));
});

test('a fallback selector never answers with another resolution', () => {
  for (const sel of [ytdlp.selectorFor({ height: 1080, vid: '137', aid: '140' }, true), ytdlp.selectorFor({ height: 1080 }, false)]) {
    assert.ok(!/height<=/.test(sel), 'the selector may not accept a smaller picture: ' + sel);
  }
  const plain = ytdlp.plainSelector({ height: 1080 }, true, 'x');
  assert.ok(!/height<=/.test(plain), 'the plain-file selector may not accept a smaller picture: ' + plain);
});

test('when the picked quality cannot be had, the download fails instead of giving a smaller one', { skip: haveFfmpeg ? false : 'ffmpeg is needed' }, async () => {
  // the site only offers the small qualities and the address of the picked one is refused
  const { task, served } = await runPick({ FAKE_HIDE_HIGH: '1' }, { refuse: ['/v1080'] });
  assert.notEqual(task.rec.state, 'completed', 'a smaller quality was accepted instead of the picked one');
  assert.ok(!served.includes('/v360'), 'no smaller stream was fetched: ' + served.join(','));
  assert.ok(task.rec.error || task.rec.warning, 'the user is told what happened');
});

test('the quality that was downloaded is reported, not the one that was asked for', { skip: haveFfmpeg ? false : 'ffmpeg is needed' }, async () => {
  const { task } = await runPick();
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.equal(task.rec.ytMeta.gotHeight, 1080);
  assert.equal(task.rec.ytMeta.wantHeight, 1080);
  assert.ok(!/instead of the selected/.test(task.rec.warning || ''), 'no wrong warning: ' + task.rec.warning);
});
