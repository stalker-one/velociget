'use strict';
/**
 * Reproduction of the reported bug:
 *   the quality menu shows "1080p ... 5.84 GB", the user picks it, and the app downloads a
 *   ~0.67 GB file instead. Uses the app's own runTask() with a yt-dlp stand-in that behaves like
 *   YouTube: the first listing is complete, later listings come back degraded.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { makeTask, waitSettled } = require('../test/helpers');

const V = 5_840_000;   // the "5.84 GB" 1080p stream
const LOW = 670_000;   // the ~0.67 GB file the user actually got
const A = 80_000;

/** Real media, so the video+audio join is exercised like it is on a real download. */
function realMedia(dir) {
  const v = path.join(dir, 'v.mp4');
  const a = path.join(dir, 'a.m4a');
  const low = path.join(dir, 'low.mp4');
  const mk = (out, size, extra) => {
    const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=1920x1080:rate=30:duration=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], { encoding: 'utf8' });
    if (r.status !== 0) throw new Error('ffmpeg: ' + r.stderr);
  };
  mk(v);
  mk(low);
  const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=duration=4', '-c:a', 'aac', '-movflags', '+faststart', a], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error('ffmpeg: ' + r.stderr);
  return { v: fs.readFileSync(v), a: fs.readFileSync(a), low: fs.readFileSync(low) };
}

async function main() {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-repro-'));
  const media = realMedia(out);
  const files = {
    '/v1080': media.v,
    '/v720': media.low,
    '/v360': media.low,
    '/a140': media.a,
  };
  const served = [];

  const server = http.createServer((req, res) => {
    const body = files[req.url.split('?')[0]];
    if (!body) { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    let start = 0, end = body.length - 1, status = 200;
    const h = { 'content-type': 'video/mp4', 'accept-ranges': 'bytes' };
    if (m) { start = +m[1]; if (m[2]) end = Math.min(+m[2], end); status = 206; h['content-range'] = `bytes ${start}-${end}/${body.length}`; }
    h['content-length'] = end - start + 1;
    served.push(req.url.split('?')[0]);
    res.writeHead(status, h);
    res.end(body.subarray(start, end + 1));
  });
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const base = `http://127.0.0.1:${server.address().port}`;

  process.env.FAKE_BASE = base;
  process.env.FAKE_COUNTER = path.join(out, 'counter');
  process.env.FAKE_VSIZE = String(V);
  process.env.FAKE_LOWSIZE = String(LOW);
  process.env.FAKE_ASIZE = String(A);

  const fake = path.join(__dirname, '..', 'test', 'fixtures', 'fake-ytdlp-degraded.js');
  const { task } = makeTask({
    url: 'https://www.youtube.com/watch?v=V_-YdNa622Q',
    kind: 'ytdlp',
    // what the extension sends when the user picks the 1080p row of the quality menu
    ytdlp: { height: 1080, vid: '137', aid: '140', mode: 'clean' },
    connections: 4,
  }, { downloadDir: out, ytdlpPath: fake, maxConnections: 4 });
  task.ctx.binDir = out;

  task.start();
  await waitSettled(task);

  const rec = task.rec;
  const got = served.filter((u) => u === '/v1080').length;
  console.log('--- RESULT -------------------------------------------------');
  console.log('state          :', rec.state, rec.error || '');
  console.log('warning        :', rec.warning || '(none)');
  console.log('ytMeta         :', JSON.stringify(rec.ytMeta));
  console.log('expected size  :', rec.ytMeta && rec.ytMeta.expected);
  console.log('downloaded     :', rec.downloaded);
  console.log('final size     :', rec.total, fs.existsSync(rec.savePath) ? fs.statSync(rec.savePath).size : '(no file)');
  console.log('streams served :', JSON.stringify(served.reduce((m, u) => (m[u] = (m[u] || 0) + 1, m), {})));
  const got1080 = served.includes('/v1080');
  const got360 = served.includes('/v360');
  console.log('VERDICT        :', got1080 && !got360 ? 'OK - the selected 1080p was downloaded' : 'BUG - a different quality was downloaded');
  console.log('------------------------------------------------------------');

  server.closeAllConnections?.();
  server.close();
  fs.rmSync(out, { recursive: true, force: true });
}

main().catch((e) => { console.error(e); process.exit(1); });
