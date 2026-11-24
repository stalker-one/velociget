'use strict';
/**
 * Walks the failure modes of "download the quality that was picked" and prints what the user is told.
 * Run: node scripts/scenarios-quality.js
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { makeTask, waitSettled } = require('../test/helpers');

const FAKE = path.join(__dirname, '..', 'test', 'fixtures', 'fake-ytdlp-degraded.js');

function media(dir) {
  const v = path.join(dir, 'v.mp4');
  const a = path.join(dir, 'a.m4a');
  let r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=1920x1080:rate=30:duration=2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', v], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(r.stderr);
  r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=duration=2', '-c:a', 'aac', '-movflags', '+faststart', a], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(r.stderr);
  return { v: fs.readFileSync(v), a: fs.readFileSync(a) };
}

async function scenario(name, { env = {}, refuse = [] } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-sc-'));
  const m = media(dir);
  const served = [];
  const srv = http.createServer((req, res) => {
    const p = req.url.split('?')[0];
    served.push(p);
    if (refuse.includes(p)) { res.writeHead(403); return res.end(); }
    const body = { '/v1080': m.v, '/v720': m.v, '/v360': m.v, '/a140': m.a }[p];
    if (!body) { res.writeHead(404); return res.end(); }
    const r = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'video/mp4', 'accept-ranges': 'bytes' };
    if (r) { start = +r[1]; if (r[2]) end = Math.min(+r[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; }
    h['content-length'] = end - start + 1;
    res.writeHead(status, h);
    res.end(body.subarray(start, end + 1));
  });
  await new Promise((ok) => srv.listen(0, '127.0.0.1', ok));
  process.env.FAKE_BASE = `http://127.0.0.1:${srv.address().port}`;
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
    ytdlp: { height: 1080, fps: 0, vid: '137', aid: '140', mode: 'clean' },
    connections: 4,
  }, { downloadDir: out, ytdlpPath: FAKE, maxConnections: 4 });
  task.ctx.binDir = out;
  task.start();
  await waitSettled(task);
  srv.close();
  for (const k of ['FAKE_BASE', 'FAKE_COUNTER', 'FAKE_RENUMBER', 'FAKE_HIDE_HIGH']) delete process.env[k];
  fs.rmSync(dir, { recursive: true, force: true });
  console.log('=== ' + name);
  console.log('  state   :', task.rec.state);
  console.log('  notice  :', task.rec.warning || '(none)');
  console.log('  error   :', task.rec.error || '(none)');
  console.log('  fetched :', [...new Set(served)].join(', ') || '(nothing)');
  console.log('');
}

(async () => {
  await scenario('1. the site answers the later look-up with less (the reported case)', {});
  await scenario('2. the picked stream is offered under another format id', { env: { FAKE_RENUMBER: '1' } });
  await scenario('3. the picked address is dead and the site only offers small ones', { env: { FAKE_HIDE_HIGH: '1' }, refuse: ['/v1080'] });
})().catch((e) => { console.error(e); process.exit(1); });
