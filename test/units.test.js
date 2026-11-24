'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const names = require('../src/core/filename');
const { expandPattern } = require('../src/core/batch');
const { parseMaster, parseMedia } = require('../src/core/hls');
const { extractLinks } = require('../src/core/grabber');

test('content-disposition parsing', () => {
  assert.equal(names.fromContentDisposition('attachment; filename="a b.zip"'), 'a b.zip');
  assert.equal(names.fromContentDisposition("attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf"), '€ rates.pdf');
  assert.equal(names.fromContentDisposition('attachment; filename=plain.txt'), 'plain.txt');
  assert.equal(names.fromContentDisposition('inline'), null);
});

test('sanitize handles Windows-hostile names', () => {
  assert.equal(names.sanitize('a<b>c:d?.txt'), 'a_b_c_d_.txt');
  assert.equal(names.sanitize('CON.txt'), '_CON.txt');
  assert.equal(names.sanitize('trailing. '), 'trailing');
  assert.equal(names.sanitize(''), 'download');
  assert.ok(names.sanitize('x'.repeat(400) + '.mp4').length <= 200);
  assert.ok(names.sanitize('x'.repeat(400) + '.mp4').endsWith('.mp4'));
});

test('categories', () => {
  assert.equal(names.categoryFor('movie.MKV'), 'Video');
  assert.equal(names.categoryFor('setup.exe'), 'Programs');
  assert.equal(names.categoryFor('paper.pdf'), 'Documents');
  assert.equal(names.categoryFor('data.bin'), 'General');
});

test('batch patterns', () => {
  assert.deepEqual(expandPattern('http://x/i[08-11].jpg'), ['http://x/i08.jpg', 'http://x/i09.jpg', 'http://x/i10.jpg', 'http://x/i11.jpg']);
  assert.deepEqual(expandPattern('http://x/[a-c]'), ['http://x/a', 'http://x/b', 'http://x/c']);
  assert.deepEqual(expandPattern('http://x/f.{jpg,png}'), ['http://x/f.jpg', 'http://x/f.png']);
  assert.equal(expandPattern('http://x/[1-3]/[a-b]').length, 6);
  assert.throws(() => expandPattern('http://x/[1-99999]'), /more than/);
  assert.throws(() => expandPattern('http://x/[zz]'), /Invalid range/);
});

test('HLS master and media playlists', () => {
  const master = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,AUDIO="aud"
high/index.m3u8`;
  const m = parseMaster(master, 'https://cdn.test/v/master.m3u8');
  assert.equal(m.variants.length, 2);
  assert.equal(m.variants[1].uri, 'https://cdn.test/v/high/index.m3u8');
  assert.equal(m.variants[1].height, 720);
  assert.equal(m.audio.aud.uri, 'https://cdn.test/v/audio/en.m3u8');

  const media = parseMedia(`#EXTM3U
#EXT-X-MEDIA-SEQUENCE:7
#EXT-X-KEY:METHOD=AES-128,URI="k.bin",IV=0x00000000000000000000000000000001
#EXTINF:4.0,
a.ts
#EXTINF:4.0,
b.ts
#EXT-X-ENDLIST`, 'https://cdn.test/v/high/index.m3u8');
  assert.equal(media.endList, true);
  assert.equal(media.segments.length, 2);
  assert.equal(media.segments[1].seq, 8);
  assert.equal(media.segments[0].key.uri, 'https://cdn.test/v/high/k.bin');
});

test('link extraction resolves relative URLs and skips junk', () => {
  const links = extractLinks(
    `<a href="/a.zip">x</a><img src='b.png'><a href="javascript:void(0)"><a href="#top"><a href="mailto:a@b.c"><a href="https://o.test/c?x=1&amp;y=2">`,
    'https://site.test/dir/page.html',
  );
  assert.deepEqual(links.sort(), ['https://o.test/c?x=1&y=2', 'https://site.test/a.zip', 'https://site.test/dir/b.png']);
});

test('explainError points YouTube format failures at the Deno helper', () => {
  const { explainError } = require('../src/core/ytdlp');
  const m = explainError('WARNING: n challenge solving failed: no javascript runtime\nERROR: Requested format is not available', 1);
  assert.match(String(m), /Deno/);
});

test('IDM-style wildcard lists', () => {
  const { extMatches, hostMatches } = require('../src/core/patterns');
  const { CAPTURE_EXTENSIONS, EXCLUDED_SITES } = require('../src/core/settings');
  assert.ok(extMatches(CAPTURE_EXTENSIONS, 'mp4') && extMatches(CAPTURE_EXTENSIONS, 'r07') && extMatches(CAPTURE_EXTENSIONS, 'R1'));
  assert.ok(!extMatches(CAPTURE_EXTENSIONS, 'html'));
  assert.ok(hostMatches(EXCLUDED_SITES, 'a.update.microsoft.com') && hostMatches(EXCLUDED_SITES, 'update.microsoft.com'));
  assert.ok(hostMatches(EXCLUDED_SITES, 'download.windowsupdate.com') && !hostMatches(EXCLUDED_SITES, 'example.com'));
});

test('explainError does not ask to install Deno when a runtime exists', () => {
  const { explainError } = require('../src/core/ytdlp');
  const raw = 'WARNING: n challenge solving failed\nERROR: Requested format is not available';
  assert.match(explainError(raw, 1, { jsRuntime: false }), /Install Deno/);
  const m = explainError(raw, 1, { jsRuntime: true });
  assert.doesNotMatch(m, /Install Deno/);
  assert.match(m, /did not hand out video streams/);
});

test('YouTube look-ups start without browser User-Agent/Referer/cookies, later modes add them', () => {
  const { commonArgs } = require('../src/core/ytdlp');
  const env = { settings: { proxy: null, userAgent: '' }, binDir: '/nonexistent' };
  const spec = { url: 'https://www.youtube.com/watch?v=x', userAgent: 'Mozilla/5.0 Chrome', referer: 'https://www.youtube.com/' };
  const clean = commonArgs(env, spec, '/tmp/c.txt').args;
  assert.ok(!clean.includes('--user-agent') && !clean.includes('--referer') && !clean.includes('--cookies'));
  assert.ok(commonArgs(env, { ...spec, mode: 'cookies' }, '/tmp/c.txt').args.includes('--cookies'));
  assert.ok(commonArgs(env, { ...spec, mode: 'fallback' }, null).args.join(' ').includes('player_client='));
  const other = commonArgs(env, { url: 'https://vimeo.com/1', userAgent: 'UA', referer: 'R' }, '/tmp/c.txt').args;
  assert.ok(other.includes('--user-agent') && other.includes('--referer') && other.includes('--cookies'));
});

test('video options: one entry per resolution/fps/codec with exact format ids, and the selector uses them', () => {
  const y = require('../src/core/ytdlp');
  const f = (id, h, fps, vc, ext, size) => ({ format_id: id, height: h, fps, vcodec: vc, acodec: 'none', ext, filesize: size });
  const info = { title: 't', formats: [
    f('299', 1080, 60, 'avc1.64002a', 'mp4', 5e9), f('303', 1080, 60, 'vp9', 'webm', 4.4e9), f('399', 1080, 60, 'av01.0.09M.08', 'mp4', 2.9e9),
    f('298', 720, 60, 'avc1.4d4020', 'mp4', 2.5e9), f('136', 720, 30, 'avc1.4d401f', 'mp4', 2.3e9),
    { format_id: '140', vcodec: 'none', acodec: 'mp4a.40.2', ext: 'm4a', abr: 128, filesize: 4e7 },
    { format_id: '251', vcodec: 'none', acodec: 'opus', ext: 'webm', abr: 130, filesize: 4e7 },
  ] };
  const s = y.summarize(info, true);
  assert.deepEqual(s.options.slice(0, 3).map((o) => [o.label, o.codec, o.formatId]), [['1080p60', 'H.264', '299'], ['1080p60', 'AV1', '399'], ['1080p60', 'VP9', '303']]);
  assert.equal(s.options[0].audioId, '140');
  assert.equal(s.options.find((o) => o.codec === 'VP9').audioId, '251');
  assert.equal(s.options.find((o) => o.height === 720 && !o.fps).label, '720p');
  const sel = y.selectorFor({ height: 1080, vid: '299', aid: '140' }, true);
  assert.ok(sel.startsWith('299+140/299/'), sel);
  assert.ok(/\[vcodec\^=avc1\]/.test(sel), 'generic fallback prefers H.264 so it never silently switches to AV1');
});

test('qualities that YouTube lists without a download address are not offered', () => {
  const y = require('../src/core/ytdlp');
  const info = { title: 't', duration: 100, formats: [
    { format_id: '137', vcodec: 'avc1.640028', acodec: 'none', height: 1080, ext: 'mp4', filesize: 5e9 }, // no url: needs a token
    { format_id: '136', vcodec: 'avc1.4d401f', acodec: 'none', height: 720, ext: 'mp4', filesize: 2e9, url: 'https://x/136' },
    { format_id: '140', vcodec: 'none', acodec: 'mp4a', ext: 'm4a', abr: 128, filesize: 1e6, url: 'https://x/140' },
  ] };
  const s = y.summarize(info, true);
  assert.deepEqual(s.options.map((o) => o.height), [720]);
  assert.equal(s.hiddenHeight, 360);
});
