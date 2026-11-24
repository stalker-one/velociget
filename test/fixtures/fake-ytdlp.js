#!/usr/bin/env node
'use strict';
// Stand-in for yt-dlp in the browser end-to-end test (the sandbox cannot reach YouTube).
const fs = require('fs');
const path = require('path');
const a = process.argv.slice(2);
if (process.env.FAKE_YTDLP_LOG) fs.appendFileSync(process.env.FAKE_YTDLP_LOG, JSON.stringify(a) + '\n');
if (a.includes('--version')) { console.log('2099.01.01-fake'); process.exit(0); }
if (a.includes('-J') && a.includes('-f') && process.env.FAKE_V) {
  // direct-stream answer: separate video + audio served over plain http (used by the multi-connection test)
  // FAKE_TOKENFILE: every call hands out a new signed link (?k=N), like a real site does
  let k = '';
  if (process.env.FAKE_TOKENFILE) { let n = 0; try { n = Number(fs.readFileSync(process.env.FAKE_TOKENFILE, 'utf8')) || 0; } catch { /* first call */ } fs.writeFileSync(process.env.FAKE_TOKENFILE, String(++n)); k = '?k=' + n; }
  console.log(JSON.stringify({ title: 'Direct Test', duration: 20, requested_formats: [
    { format_id: 'v', url: process.env.FAKE_V + k, protocol: 'https', ext: 'mp4', vcodec: 'avc1', acodec: 'none', filesize: Number(process.env.FAKE_VSIZE) },
    { format_id: 'a', url: process.env.FAKE_A + k, protocol: 'https', ext: 'm4a', vcodec: 'none', acodec: 'mp4a', filesize: Number(process.env.FAKE_ASIZE) },
  ] }));
  process.exit(0);
}
if (a.includes('-J')) {
  console.log(JSON.stringify({ title: 'Test Video From YouTube', duration: 60, formats: [
    { format_id: '137', vcodec: 'avc1.640028', acodec: 'none', height: 1080, ext: 'mp4', filesize: 3000000, fps: 30 },
    { format_id: '136', vcodec: 'avc1.4d401f', acodec: 'none', height: 720, ext: 'mp4', filesize: 1800000, fps: 30 },
    { format_id: '18', vcodec: 'avc1.42001E', acodec: 'mp4a.40.2', height: 360, ext: 'mp4', filesize: 700000, fps: 30 },
    { format_id: '140', vcodec: 'none', acodec: 'mp4a.40.2', ext: 'm4a', abr: 128, filesize: 500000 },
  ] }));
  process.exit(0);
}
const tpl = a[a.indexOf('-o') + 1];
const out = tpl.replace('%(ext)s', 'mp4');
fs.mkdirSync(path.dirname(out), { recursive: true });
const total = 3500000;
let done = 0;
const t = setInterval(() => {
  done += 700000;
  console.log(`VGP\t${Math.min(done, total)}\t${total}\tNA\t${out}`);
  if (done >= total) { clearInterval(t); fs.writeFileSync(out, Buffer.alloc(total, 1)); process.exit(0); }
}, 150);
