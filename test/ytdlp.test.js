'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { makeTask, waitSettled, waitUntil, tmpDir } = require('./helpers');
const tools = require('../src/core/tools');
const ytdlp = require('../src/core/ytdlp');
const { defaultSettings } = require('../src/core/settings');

const have = !!tools.findYtdlp({}, null) && !!tools.findFfmpeg({}, null);
const skip = have ? false : 'yt-dlp and ffmpeg are needed for these tests';

let fixtureDir;
function makeFixture() {
  if (fixtureDir) return fixtureDir;
  fixtureDir = tmpDir('vg-hls-');
  const r = spawnSync('ffmpeg', [
    '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'testsrc=size=320x240:rate=25:duration=6',
    '-f', 'lavfi', '-i', 'sine=frequency=440:duration=6',
    '-filter_complex', '[0:v]split=2[a][b];[b]scale=160:120[c]',
    '-map', '[a]', '-map', '[c]', '-map', '1:a',
    '-c:v', 'libx264', '-c:a', 'aac',
    '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod',
    '-var_stream_map', 'v:0,agroup:aud v:1,agroup:aud a:0,agroup:aud,name:audio',
    '-master_pl_name', 'master.m3u8',
    '-hls_segment_filename', path.join(fixtureDir, 's%v_%03d.ts'),
    path.join(fixtureDir, 'v%v.m3u8'),
  ]);
  if (r.status !== 0) throw new Error('ffmpeg fixture failed: ' + r.stderr);
  return fixtureDir;
}

function serveDir(dir) {
  const server = http.createServer((req, res) => {
    const f = path.join(dir, path.basename(req.url.split('?')[0]));
    if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'content-type': f.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t' });
    fs.createReadStream(f).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, url: (n) => `http://127.0.0.1:${server.address().port}/${n}`, close: () => { server.closeAllConnections?.(); server.close(); } })));
}

const env = () => ({ settings: { ...defaultSettings() }, binDir: null });
const streams = (file) => spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', file], { encoding: 'utf8' }).stdout.split(/\s+/).filter(Boolean).sort();

test('probe lists one entry per resolution, best first', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const info = await ytdlp.probe(env(), { url: s.url('master.m3u8') });
  assert.deepEqual(info.options.map((o) => o.height), [240, 120]);
  assert.equal(info.hasFfmpeg, true);
  assert.ok(info.options.every((o) => o.available));
  s.close();
});

test('downloads the chosen quality and merges separate audio + video', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task, dir } = makeTask({ url: s.url('master.m3u8'), kind: 'ytdlp', ytdlp: { height: 240 }, fileName: '' }, { ffmpegPath: '' });
  task.ctx.binDir = null;
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const out = path.join(dir, task.rec.fileName);
  assert.ok(fs.existsSync(out), out);
  assert.deepEqual(streams(out), ['audio', 'video']);
  const h = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=height', '-of', 'csv=p=0', out], { encoding: 'utf8' }).stdout.trim();
  assert.equal(h, '240');
  for (let i = 0; i < 40 && fs.readdirSync(dir).some((n) => n.startsWith('.velociget')); i++) await new Promise((r) => setTimeout(r, 100)); // cleanup runs in the background
  assert.deepEqual(fs.readdirSync(dir).filter((n) => n.startsWith('.velociget')), [], 'temporary work folder is removed');
  assert.ok(task.rec.total > 0 && task.rec.downloaded === task.rec.total);
  s.close();
});

test('lower quality is honored', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task, dir } = makeTask({ url: s.url('master.m3u8'), kind: 'ytdlp', ytdlp: { height: 120 } });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  const h = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=height', '-of', 'csv=p=0', path.join(dir, task.rec.fileName)], { encoding: 'utf8' }).stdout.trim();
  assert.equal(h, '120');
  s.close();
});

test('audio-only option saves just the audio track', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task, dir } = makeTask({ url: s.url('master.m3u8'), kind: 'ytdlp', ytdlp: { audio: true } });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.deepEqual(streams(path.join(dir, task.rec.fileName)), ['audio']);
  s.close();
});

test('pause stops the engine, resume finishes, nothing is left behind', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task, dir } = makeTask({ url: s.url('master.m3u8'), kind: 'ytdlp', ytdlp: { height: 240 }, speedLimit: 40_000 });
  task.start();
  await waitUntil(() => task.rec.downloaded > 0, 30_000);
  task.pause();
  await waitSettled(task);
  assert.equal(task.rec.state, 'paused');
  const mid = task.rec.downloaded;
  assert.ok(mid > 0);
  task.rec.speedLimit = 0;
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'completed', task.rec.error);
  assert.deepEqual(streams(path.join(dir, task.rec.fileName)), ['audio', 'video']);
  s.close();
});

test('cancel removes the partial work folder', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task, dir } = makeTask({ url: s.url('master.m3u8'), kind: 'ytdlp', ytdlp: { height: 240 }, speedLimit: 30_000 });
  task.start();
  await waitUntil(() => task.rec.downloaded > 0, 30_000);
  task.cancel();
  await waitSettled(task);
  await task.removePartial();
  assert.equal(task.rec.state, 'canceled');
  assert.deepEqual(fs.readdirSync(dir), []);
  s.close();
});

test('errors come back readable, not as a stack trace', { skip }, async () => {
  const s = await serveDir(makeFixture());
  const { task } = makeTask({ url: s.url('nothing-here.m3u8'), kind: 'ytdlp' });
  task.start();
  await waitSettled(task);
  assert.equal(task.rec.state, 'error');
  assert.ok(task.rec.error && task.rec.error.length < 300 && !/Traceback/.test(task.rec.error), task.rec.error);
  s.close();
});

test('selector falls back to a single stream when ffmpeg is missing', () => {
  // The fallback asks for THAT resolution, never "this resolution or lower": a fallback that may answer
  // with a smaller picture is how a picked 1080p silently became a 360p file.
  assert.match(ytdlp.selectorFor({ height: 1080 }, false), /^b\[height=1080\]/);
  assert.match(ytdlp.selectorFor({ height: 1080 }, true), /bv\*\[height=1080\]/);
  assert.ok(!ytdlp.selectorFor({}, false).includes('+'));
  assert.ok(!ytdlp.selectorFor({ height: 1080 }, true).includes('height<='));
  assert.ok(!ytdlp.plainSelector({ height: 1080 }, true, 'x').includes('height<='));
});

test('summarize marks merged-only qualities unavailable without ffmpeg', () => {
  const info = { title: 'T', formats: [
    { format_id: '18', vcodec: 'avc1', acodec: 'mp4a', height: 360, ext: 'mp4', filesize: 1000 },
    { format_id: '137', vcodec: 'avc1', acodec: 'none', height: 1080, ext: 'mp4', filesize: 5000 },
    { format_id: '140', vcodec: 'none', acodec: 'mp4a', ext: 'm4a', abr: 128, filesize: 700 },
  ] };
  const s = ytdlp.summarize(info, false);
  assert.deepEqual(s.options.map((o) => [o.height, o.available, o.needsMerge]), [[1080, false, true], [360, true, false]]);
  assert.equal(s.options[0].size, 5700);
  assert.equal(ytdlp.summarize(info, true).options[0].available, true);
});

test('cookie file uses the Netscape format', () => {
  const t = ytdlp.cookieFileText([{ name: 'SID', value: 'a b', domain: '.youtube.com', path: '/', secure: true, expirationDate: 1900000000.5 }, { name: 'x', value: 'y', domain: 'example.com' }, { name: '', domain: 'bad' }]);
  const rows = t.trim().split('\n');
  assert.equal(rows[0], '# Netscape HTTP Cookie File');
  assert.equal(rows[1], '.youtube.com\tTRUE\t/\tTRUE\t1900000000\tSID\ta b');
  assert.equal(rows[2], 'example.com\tFALSE\t/\tFALSE\t0\tx\ty');
  assert.equal(rows.length, 3);
});

test('site detection', () => {
  assert.equal(ytdlp.isSiteUrl('https://www.youtube.com/watch?v=x'), true);
  assert.equal(ytdlp.isSiteUrl('https://youtu.be/x'), true);
  assert.equal(ytdlp.isSiteUrl('https://example.com/a.zip'), false);
  assert.equal(ytdlp.isSiteUrl('https://notyoutube.com/x'), false);
});

test('summarize takes the exact size from clen when the engine gives none, and lists audio rows', () => {
  const info = { title: 't', duration: 100, formats: [
    { format_id: '308', url: 'https://r1.googlevideo.com/videoplayback?clen=2200000000&x=1', vcodec: 'vp09', acodec: 'none', height: 1440, fps: 60, ext: 'webm', protocol: 'https' },
    { format_id: '251', url: 'https://r1.googlevideo.com/videoplayback?clen=171000000', vcodec: 'none', acodec: 'opus', abr: 130, ext: 'webm', protocol: 'https' },
    { format_id: '140', url: 'https://r1.googlevideo.com/videoplayback?clen=90000000', vcodec: 'none', acodec: 'mp4a', abr: 128, ext: 'm4a', protocol: 'https' },
  ] };
  const s = ytdlp.summarize(info, true);
  assert.equal(s.options[0].size, 2200000000 + 171000000);
  assert.equal(s.audios.length, 2);
});

test('summarize carries the caption text, uploader, date and caption tracks', () => {
  const s = ytdlp.summarize({ title: 'T', duration: 10, description: 'hello #tag', extractor_key: 'TikTok', uploader: 'u', upload_date: '20261101', view_count: 1500,
    subtitles: { en: [{ ext: 'vtt', url: 'https://x/en.vtt' }, { ext: 'srt', url: 'https://x/en.srt' }] }, automatic_captions: { fr: [{ ext: 'vtt', url: 'https://x/fr.vtt' }] },
    formats: [{ format_id: '1', url: 'https://x/a.mp4', vcodec: 'avc1', acodec: 'aac', height: 720, ext: 'mp4', protocol: 'https' }] }, true);
  assert.equal(s.meta.description, 'hello #tag');
  assert.equal(s.meta.platform, 'TikTok');
  assert.equal(s.meta.uploadDate, '2026-11-01');
  assert.deepEqual(s.meta.captions.map((c) => [c.lang, c.ext, c.auto]), [['en', 'srt', false], ['fr', 'vtt', true]]);
});
