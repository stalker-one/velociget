'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const background = fs.readFileSync(path.join(root, 'extension/background.js'), 'utf8');
const popup = fs.readFileSync(path.join(root, 'extension/popup/popup.js'), 'utf8');
const inject = fs.readFileSync(path.join(root, 'extension/inject.js'), 'utf8');

function extract(source, startToken, endToken, exportName) {
  const start = source.indexOf(startToken);
  const end = source.indexOf(endToken, start);
  assert.notEqual(start, -1, `missing ${startToken}`);
  assert.notEqual(end, -1, `missing ${endToken}`);
  const context = {};
  vm.runInNewContext(`${source.slice(start, end)}\nglobalThis.__exported = ${exportName};`, context);
  return context.__exported;
}

const fileOptions = extract(
  background,
  'const hdLabel =',
  '\n/** Answer without any network:',
  'fileOptions',
);
const optionIdentity = extract(
  popup,
  'const optionIdentity =',
  '\n\n(async () =>',
  'optionIdentity',
);
const fragmentThreshold = extract(
  popup,
  'const likelyPlayerFragmentThreshold =',
  '\n\n(async () =>',
  'likelyPlayerFragmentThreshold',
);

test('explicit URL and response filename qualities beat misleading probe dimensions', () => {
  const entry = {
    kind: 'files',
    files: [
      { url: 'https://cdn.example/movie?quality=1080', size: 1200 },
      { url: 'https://cdn.example/movie?quality=720', size: 800 },
      { url: 'https://cdn.example/movie?quality=480', size: 400 },
      { url: 'https://cdn.example/opaque', size: 200 },
      { url: 'https://cdn.example/cropped', size: 300 },
    ],
  };
  const infos = [480, 480, 480, 480, 478].map((height, i) => ({
    height,
    duration: 4200,
    contentType: 'video/mp4',
    fileName: i === 3 ? 'episode_1080p.mkv' : '',
  }));
  const options = fileOptions(entry, { url: 'https://page.example/watch' }, infos);
  assert.deepEqual(Array.from(options, (o) => o.height), [1080, 1080, 720, 480, 480]);
  assert.match(options[0].label, /1080p/);
  assert.match(options[2].label, /720p/);
  assert.ok(options.slice(3).every((o) => o.label.includes('480p')));
});

test('popup keeps distinct quality targets even when they share the same page URL and metadata', () => {
  const options = ['480', '720', '1080'].map((height) => ({
    key: `format-${height}`,
    height: Number(height),
    req: {
      kind: 'ytdlp',
      url: 'https://video.example/watch?id=episode-1',
      ytdlp: { vid: `format-${height}` },
    },
  }));
  const identities = options.map(optionIdentity);
  assert.equal(new Set(identities).size, 3);
  assert.equal(optionIdentity(options[0]), optionIdentity({ ...options[0] }));
});

test('short stream fragments are filtered only when a full-length title and many fragments coexist', () => {
  assert.equal(fragmentThreshold([4413, 300, 150, 300, 120, 180, 90, 300, 120]), 600);
  assert.equal(fragmentThreshold([300, 150, 300, 120, 180, 90, 300, 120]), 0);
  assert.equal(fragmentThreshold([4413, 4400, 4380]), 0);
});

async function runInjectedFetch(url, contentType, bodyText) {
  const events = [];
  const bytes = new TextEncoder().encode(bodyText);
  const response = {
    ok: true,
    bodyUsed: false,
    url,
    headers: { get: (name) => name === 'content-type' ? contentType : null },
    clone: () => ({ body: { getReader: () => {
      let sent = false;
      return { read: async () => sent ? { done: true } : (sent = true, { done: false, value: bytes }), cancel: async () => {} };
    } } }),
  };
  const sandbox = {
    window: { fetch: () => Promise.resolve(response) },
    document: {},
    location: { href: 'https://page.example/watch' },
    EventTarget: { prototype: { dispatchEvent: (event) => events.push(event) } },
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init.detail; } },
    TextDecoder,
    TextEncoder,
    URL,
  };
  vm.runInNewContext(inject, sandbox);
  await sandbox.window.fetch(url);
  await new Promise((resolve) => setTimeout(resolve, 0));
  return events;
}

test('page hook relays only a valid HLS playlist body received by the player', async () => {
  const body = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\n1080/index.m3u8\n';
  const events = await runInjectedFetch('https://cdn.example/master.m3u8?sig=1', 'application/vnd.apple.mpegurl', body);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, '__vg_manifest');
  assert.deepEqual(JSON.parse(events[0].detail), { url: 'https://cdn.example/master.m3u8?sig=1', body });
});

test('page hook never reports player JSON as an HLS playlist', async () => {
  const json = JSON.stringify({ formats: [
    { url: 'https://cdn.example/1080.mp4', quality: '1080p' },
    { url: 'https://cdn.example/720.mp4', quality: '720p' },
  ] });
  const events = await runInjectedFetch('https://cdn.example/api/play-info', 'application/json', json);
  assert.equal(events.length, 0);
});
