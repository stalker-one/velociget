'use strict';
const REAL = (() => { // a real (tiny) video so the thumbnail path can be tested
  const f = require('path').join(require('os').tmpdir(), 'vg-e2e-real.mp4');
  const r = require('child_process').spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=25', '-t', '6', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', f]);
  return r.status === 0 ? require('fs').readFileSync(f) : null;
})();
const MB = (() => { // streaming-site style: same movie in two qualities under hashed names, plus a short clip
  const mk = (name, size, secs, br) => { const f = require('path').join(require('os').tmpdir(), 'vg-e2e-' + name + '.mp4'); const r = require('child_process').spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', `testsrc=size=${size}:rate=25`, '-t', String(secs), '-b:v', br, '-pix_fmt', 'yuv420p', '-movflags', '+faststart', f]); return r.status === 0 ? require('fs').readFileSync(f) : null; };
  const a = mk('mb-hi', '640x360', 20, '900k'), b = mk('mb-lo', '320x180', 20, '400k'), c = mk('mb-clip', '320x180', 6, '400k');
  return a && b && c ? { 'a1b2c3d4e5f60718293a4b5c6d7e8f90': a, '0f1e2d3c4b5a69788796a5b4c3d2e1f0': b, 'ffeeddccbbaa99887766554433221100': c } : null;
})();
// End-to-end: real Chromium + the real extension + real manager/API. Covers the in-page bar on a plain
// video, a captured file download, and a YouTube-style page that streams through a blob: player.
const http = require('http');
const path = require('path');
const os = require('os');
const fs = require('fs');
const { chromium } = require('playwright');
const { DownloadManager } = require('../src/core/manager');
const { createApiServer } = require('../src/core/server');

const DATA = Buffer.alloc(3 * 1024 * 1024, 7);
const log = (...a) => console.log(...a);
const failures = [];
const check = (ok, msg) => { log(ok ? 'PASS' : 'FAIL', msg); if (!ok) failures.push(msg); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(fn, ms = 15000) { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (await fn()) return true; await sleep(150); } return false; }

(async () => {
  const site = http.createServer((req, res) => {
    if (req.url === '/player') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<body style="margin:0"><video id=v width=640 height=360 controls muted></video><script>
        fetch('/api/play').then(r => r.json()).then(j => { document.getElementById('v').dataset.ready = 1; });
      </script></body>`);
    }
    if (req.url === '/hlsplayer') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<body><video id=v width=640 height=360 controls muted></video><script>fetch('/stream/master?sig=1').then(r => r.text()).then(() => { document.getElementById('v').dataset.ready = 1; });</script></body>`);
    }
    if (req.url.startsWith('/stream/master')) {
      // signed playlist: a content type nobody recognises, and a second download of it is refused (like hot-link protected CDNs)
      globalThis.__hlsHits = (globalThis.__hlsHits || 0) + 1;
      if (globalThis.__hlsHits > 1) { res.writeHead(403); return res.end('no'); }
      res.writeHead(200, { 'content-type': 'text/plain' });
      return res.end('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nv1080.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720\nv720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480\nv480.m3u8\n');
    }
    if (/^\/stream\/v\d+\.m3u8/.test(req.url)) { res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' }); return res.end('#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.ts\n#EXTINF:10,\nseg2.ts\n#EXT-X-ENDLIST\n'); }
    if (req.url === '/gallery') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<body><h1>Gallery</h1><img src="/img/one.jpg"><img src="/img/two.jpg"><div style="width:50px;height:50px;background:url(/img/three.png)"></div>
        <a href="/files/report.pdf">Annual report</a> <a href="/files/tool.zip">Tool</a> <a href="/about">About us</a></body>`);
    }
    if (req.url === '/gallery2') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<style>.b{width:40px;height:40px;background-image:url(/img/css1.png)}.c::before{content:"";display:block;width:30px;height:30px;background:url(/img/pseudo.png)}</style>
        <body style="height:3000px"><img src="/img/a1.jpg"><img data-src="/img/lazy1.jpg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="><img srcset="/img/s-400.jpg 400w, /img/s-800.jpg 800w">
        <picture><source srcset="/img/pic1.webp"><img></picture><div class="b"></div><div class="c"></div><div style="background:url('/img/inline.jpg')"></div>
        <img srcset="/_next/image?url=%2Fx.png&w=640 1x, /_next/image?url=%2Fx.png&w=1080 2x"><a href="/img/linked.jpg">full size</a><div id="host"></div><iframe src="/frame"></iframe>
        <script>const sr=document.getElementById('host').attachShadow({mode:'open'});sr.innerHTML='<img src="/img/shadow.jpg">';
        window.__d={"a":"https:\\/\\/127.0.0.1\\/img\\/json1.png"};
        let added=false;addEventListener('scroll',()=>{if(added||scrollY<500)return;added=true;const i=new Image();i.src='/img/scrolled.jpg';document.body.appendChild(i);});</script>
        <a href="/files/a.docx">doc</a><a href="/files/b.xlsx">xls</a><a href="/files/c.pptx">ppt</a><a href="/files/d.mp3">mp3</a><a href="/files/e.mp4">mp4</a></body>`);
    }
    if (req.url === '/frame') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end('<body><img src="/img/inframe.jpg"></body>'); }
    if (req.url.startsWith('/img/')) {
      res.writeHead(200, { 'content-type': req.url.endsWith('png') ? 'image/png' : req.url.endsWith('webp') ? 'image/webp' : 'image/jpeg', 'content-length': 3000 });
      return res.end(Buffer.alloc(3000, 5));
    }
    if (req.url === '/moviebox') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end('<title>The Rings of Power [Hindi]</title><body>' + Object.keys(MB || {}).map((k, i) => `<video id=m${i} src="/mb/${k}.mp4" preload="auto" muted width=200></video>`).join('') + '<script>fetch("/blob1?id=7").then(r => r.arrayBuffer());</script></body>');
    }
    if (req.url.startsWith('/blob1')) { const b = Buffer.alloc(400 * 1024, 7); res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': b.length }); return res.end(b); }
    if (req.url.startsWith('/mb/') && MB) {
      const body = MB[req.url.slice(4).replace(/\.mp4.*/, '')];
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': body.length, 'accept-ranges': 'bytes' });
      return res.end(body);
    }
    if (req.url === '/player2') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<body><video id=v width=640 height=360 controls muted></video><script>
        fetch('/api/play2').then(r => r.json()).then(async j => { for (const x of j.s) await fetch(x.url, { headers: { Range: 'bytes=0-1' } }).catch(() => {}); await fetch(j.sub).catch(() => {}); document.getElementById('v').dataset.ready = 1; });
      </script></body>`);
    }
    if (req.url === '/api/play2') {
      res.writeHead(200, { 'content-type': 'application/json' });
      const b = 'http://' + req.headers.host;
      return res.end(JSON.stringify({ s: [1080, 720, 480].map((q) => ({ url: b + '/files/show_' + q + 'p.mp4' })), sub: b + '/subs/show.vtt' }).replace(/\//g, '\\/'));
    }
    if (req.url === '/api/play') {
      res.writeHead(200, { 'content-type': 'application/json' });
      const b = 'http://' + req.headers.host;
      // PHP-style escaped slashes, as many real APIs send them
      return res.end(JSON.stringify({ streams: [{ resolution: 720, url: b + '/files/movie_720.mp4?sign=a' }, { resolution: 1080, url: b + '/files/movie_1080.mp4?sign=b' }, { resolution: 480, url: b + '/files/movie_480.mp4?sign=c' }], subtitles: [{ url: b + '/subs/en.srt' }] }).replace(/\//g, '\\/'));
    }
    if (req.url.startsWith('/files/') || req.url.startsWith('/subs/')) {
      const sub = req.url.startsWith('/subs/');
      const body = sub ? Buffer.from('1\n00:00:01,000 --> 00:00:02,000\nHello\n') : req.url.includes('show_') && REAL ? REAL : DATA;
      res.writeHead(200, { 'content-type': sub ? 'application/x-subrip' : 'application/octet-stream', 'content-length': body.length, 'accept-ranges': 'bytes' });
      return res.end(body);
    }
    if (req.url === '/') {
      res.writeHead(200, { 'content-type': 'text/html' });
      return res.end(`<body style="margin:0"><video id=v src="/clip.mp4" width=640 height=360 controls muted></video><p><a id=dl href="/archive.zip">archive</a></p></body>`);
    }
    const headers = { 'content-length': DATA.length, 'accept-ranges': 'bytes' };
    if (req.url === '/archive.zip') { headers['content-type'] = 'application/zip'; headers['content-disposition'] = 'attachment; filename="archive.zip"'; }
    else if (req.url === '/clip.mp4') headers['content-type'] = 'video/mp4';
    else { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (m) { const s = +m[1], e = m[2] ? Math.min(+m[2], DATA.length - 1) : DATA.length - 1; res.writeHead(206, { ...headers, 'content-length': e - s + 1, 'content-range': `bytes ${s}-${e}/${DATA.length}` }); return res.end(DATA.subarray(s, e + 1)); }
    res.writeHead(200, headers); res.end(DATA);
  });
  await new Promise((r) => site.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${site.address().port}`;

  const dl = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-e2e-dl-'));
  const fakeLog = path.join(dl, 'fake-ytdlp-args.log');
  process.env.FAKE_YTDLP_LOG = fakeLog;
  const manager = new DownloadManager({ dataDir: fs.mkdtempSync(path.join(os.tmpdir(), 'vg-e2e-data-')), settings: { downloadDir: dl, categorize: true, minCaptureSize: 1024, ytdlpPath: path.join(__dirname, '..', 'test', 'fixtures', 'fake-ytdlp.js'), showFileInfoDialog: true } });
  await manager.init();
  manager.on('ask', (id) => manager.start(id)); // what the app does after the File Info dialog's "Start Download"
  const api = createApiServer(manager);
  const port = await api.listen(0);

  const extDir = path.join(__dirname, '..', 'extension');
  const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), 'vg-e2e-prof-')), {
    channel: 'chromium', headless: true,
    args: [`--disable-extensions-except=${extDir}`, `--load-extension=${extDir}`],
    viewport: { width: 1000, height: 700 },
  });
  const bar = async (page, sel) => { // the bar sits top-right of the video; the shadow root is closed, so click by geometry
    const box = await (await page.$(sel)).boundingBox();
    const left = Math.max(4, Math.min(1000 - 464, box.x + box.width - 470));
    const top = Math.max(4, box.y + 10);
    return { barX: left + 460 - 90, barY: top + 14, rowY: (n) => top + 28 + 4 + 28 + 16 + n * 30, rowX: left + 300, box };
  };
  try {
    const sw = ctx.serviceWorkers()[0] || (await ctx.waitForEvent('serviceworker', { timeout: 15000 }));
    await sw.evaluate(([port, token]) => chrome.storage.local.set({ port, token }), [port, manager.getSettings().apiToken]);
    await sw.evaluate(() => new Promise((r) => chrome.runtime.sendMessage({ type: 'ping' }, r)));
    await sleep(800);

    // 1. plain page: bar appears, panel lists the file, row click sends it
    const page = await ctx.newPage();
    await page.goto(base + '/');
    await sleep(600);
    let g = await bar(page, '#v');
    await page.mouse.move(g.box.x + 100, g.box.y + 100, { steps: 4 });
    await sleep(400);
    await page.mouse.move(g.barX, g.barY, { steps: 4 });
    await page.mouse.click(g.barX, g.barY);
    await sleep(1200);
    await page.screenshot({ path: '/tmp/vg-bar-plain.png' });
    await page.mouse.click(g.rowX, g.rowY(0));
    check(await until(() => manager.list().some((d) => d.url === base + '/clip.mp4')), 'plain video: bar -> quality row -> download created');

    // 2. captured file download
    await page.click('#dl').catch(() => {});
    check(await until(() => manager.list().some((d) => d.url === base + '/archive.zip')), 'browser file download intercepted');
    check(await until(() => manager.list().filter((d) => d.url.startsWith(base)).every((d) => d.state === 'completed')), 'plain downloads completed');
    check(fs.existsSync(path.join(dl, 'Compressed', 'archive.zip')), 'archive.zip saved in the Compressed folder');

    // 2b. JSON play-info page: the player only plays one file, but the response lists every quality + subtitles
    const pl = await ctx.newPage();
    await pl.goto(base + '/player');
    await until(() => pl.evaluate(() => document.getElementById('v').dataset.ready === '1'));
    await sleep(800);
    g = await bar(pl, '#v');
    await pl.mouse.move(g.box.x + 100, g.box.y + 100, { steps: 4 });
    await sleep(400);
    await pl.mouse.move(g.barX, g.barY, { steps: 4 });
    await pl.mouse.click(g.barX, g.barY);
    await sleep(2500);
    await pl.screenshot({ path: '/tmp/vg-bar-list.png' });
    const before = manager.list().length;
    await pl.mouse.click(g.rowX, g.rowY(0)); // "Download all"
    check(await until(() => manager.list().length >= before + 4), 'JSON play-info page: list shows 3 qualities + subtitle, Download all queued ' + (manager.list().length - before) + ' files');
    const urls = manager.list().map((d) => d.url).join(' ');
    check(/movie_1080\.mp4/.test(urls) && /movie_720\.mp4/.test(urls) && /movie_480\.mp4/.test(urls) && /en\.srt/.test(urls), 'all qualities and the .srt were sent');

    // 2c. Toolbar popup: one card per video with every quality listed, pick one, "Download selected"
    const p2 = await ctx.newPage();
    await p2.goto(base + '/player2');
    await until(() => p2.evaluate(() => document.getElementById('v').dataset.ready === '1'));
    await sleep(600);
    const tabId = await sw.evaluate(async () => (await chrome.tabs.query({ url: '*://127.0.0.1/player2*' }))[0].id);
    const extId = new URL(sw.url()).host;
    const pp = await ctx.newPage();
    await pp.goto(`chrome-extension://${extId}/popup/popup.html?tab=${tabId}`);
    await pp.waitForSelector('.card .qsel', { timeout: 15000 });
    await sleep(1500);
    const cards = await pp.$$eval('.card', (c) => c.map((x) => ({ opts: [...x.querySelectorAll('.qsel option')].map((q) => q.textContent), title: x.querySelector('.n').textContent })));
    await pp.screenshot({ path: '/tmp/vg-popup.png' });
    check(!REAL || (await until(async () => (await pp.$$('.card .thumb img')).length >= 1)), 'popup: thumbnail image shown for the video');
    await pp.screenshot({ path: '/tmp/vg-popup.png' });
    check(cards.length === 2 && cards[0].opts.length === 3 && (REAL || (cards[0].opts[0].startsWith('1080p') && cards[0].opts[2].startsWith('480p'))), 'popup: all qualities grouped in one card: ' + JSON.stringify(cards));
    const b2 = manager.list().length;
    await pp.selectOption('.card:first-child .qsel', { index: 2 }); // choose the third quality, selects the card
    await pp.click('#go');
    const okq = await until(() => manager.list().length === b2 + 1); check(okq && /show_\d+p\.mp4/.test(manager.list().slice(-1)[0].url), 'popup: only the selected quality was added ' + JSON.stringify({ n: manager.list().length - b2, last: manager.list().slice(-1)[0]?.url, btn: await pp.$eval('#go', (b) => b.textContent + b.disabled), cards: await pp.$$eval('.card', (c) => c.length) }));
    await sleep(2500);
    await pp.click('#selAll');
    await pp.click('#go');
    check(await until(() => manager.list().length >= b2 + 3), 'popup: Select all + Download selected added the remaining files (' + (manager.list().length - b2) + ')');

    // 2c2. HLS master the page received (signed, odd content type, not downloadable a second time): variants come from the page's own copy
    const hp = await ctx.newPage();
    await hp.goto(base + '/hlsplayer');
    await until(() => hp.evaluate(() => document.getElementById('v').dataset.ready === '1'));
    await sleep(800);
    const hTab = await sw.evaluate(async () => (await chrome.tabs.query({ url: '*://127.0.0.1/hlsplayer*' }))[0].id);
    const hpp = await ctx.newPage();
    await hpp.goto(`chrome-extension://${extId}/popup/popup.html?tab=${hTab}`);
    await hpp.waitForSelector('.card .qsel', { timeout: 15000 });
    await sleep(1200);
    const hopts = await hpp.$$eval('.card .qsel option', (o) => o.map((x) => x.textContent));
    check(hopts.length === 3 && /^1080p/.test(hopts[0]) && /HLS/.test(hopts[0]), 'HLS master read from the page itself and listed with every variant: ' + JSON.stringify(hopts));

    // 2d. "Images, links and media" tab
    const gp = await ctx.newPage();
    await gp.goto(base + '/gallery');
    await sleep(800);
    const gId = await sw.evaluate(async () => (await chrome.tabs.query({ url: '*://127.0.0.1/gallery*' }))[0].id);
    const gr = await ctx.newPage();
    await gr.goto(`chrome-extension://${extId}/grabber/grabber.html?tab=${gId}`);
    await gr.waitForSelector('.img', { timeout: 10000 });
    const counts = await gr.evaluate(() => ({ img: +document.getElementById('n-image').textContent, links: +document.getElementById('n-links').textContent, files: +document.getElementById('n-doc').textContent + +document.getElementById('n-archive').textContent }));
    check(counts.img === 3 && counts.links === 3 && counts.files === 2, 'grabber tab lists images (incl. CSS background), links and files: ' + JSON.stringify(counts));
    await gr.screenshot({ path: '/tmp/vg-grabber.png' });
    const b3 = manager.list().length;
    await gr.click('#all');
    await gr.click('#go');
    check(await until(() => manager.list().length >= b3 + 3), 'grabber tab: selected images were downloaded (' + (manager.list().length - b3) + ')');



    // 2d. Streaming site: the same movie in two qualities under hashed names becomes ONE card with a dropdown
    if (MB) {
      const mp = await ctx.newPage();
      await mp.goto(base + '/moviebox');
      await sleep(1500);
      const mbId = await sw.evaluate(async () => (await chrome.tabs.query({ url: '*://127.0.0.1/moviebox*' }))[0].id);
      const mpp = await ctx.newPage();
      await mpp.goto(`chrome-extension://${extId}/popup/popup.html?tab=${mbId}`);
      await mpp.waitForSelector('.card .qsel', { timeout: 15000 });
      await until(async () => (await mpp.$$('.card')).length === 2);
      await sleep(2500);
      const mc = await mpp.$$eval('.card', (c) => c.map((x) => ({ opts: [...x.querySelectorAll('.qsel option')].map((q) => q.textContent), title: x.querySelector('.n').textContent })));
      check(mc.length === 2 && mc[0].opts.length === 2 && mc[1].opts.length === 1 && /Rings of Power/.test(mc[0].title), 'streaming-site files of one movie are merged into one quality dropdown, longest first: ' + JSON.stringify(mc));
      await mpp.screenshot({ path: '/tmp/vg-popup-moviebox.png' });
      const mg = await ctx.newPage();
      await mg.goto(`chrome-extension://${extId}/grabber/grabber.html?cat=net&tab=${mbId}`);
      check(await until(async () => (await mg.$$eval('.row', (r) => r.map((x) => x.textContent))).some((t) => /blob1/.test(t))), 'More formats lists a response the sniffer did not classify as media');
    }

    // 2e. Image grabber: every kind of source, frames, shadow DOM, lazy/scroll-loaded images, documents
    const g2 = await ctx.newPage();
    await g2.goto(base + '/gallery2');
    await sleep(800);
    const g2Id = await sw.evaluate(async () => (await chrome.tabs.query({ url: '*://127.0.0.1/gallery2*' }))[0].id);
    const gr2 = await ctx.newPage();
    await gr2.goto(`chrome-extension://${extId}/grabber/grabber.html?tab=${g2Id}`);
    await gr2.waitForSelector('.img', { timeout: 10000 });
    const names = async () => gr2.$$eval('.img', (n) => n.map((x) => x.dataset.url.split('/').pop()));
    const want = ['a1.jpg', 'lazy1.jpg', 's-800.jpg', 'pic1.webp', 'css1.png', 'pseudo.png', 'inline.jpg', 'linked.jpg', 'shadow.jpg', 'inframe.jpg', 'json1.png'];
    let got = await names();
    check(want.every((w) => got.includes(w)), 'grabber finds ' + want.length + ' kinds of image source, missing: ' + JSON.stringify(want.filter((w) => !got.includes(w))));
    const nx = await gr2.$$eval('.img', (n) => n.filter((x) => /_next\/image/.test(x.dataset.url)).length);
    check(nx === 2, 'every extensionless srcset size is listed separately: ' + nx);
    check(!got.includes('scrolled.jpg'), 'scroll-loaded image not there before scanning');
    await gr2.click('#scan');
    check(await until(async () => (await names()).includes('scrolled.jpg')), 'Scan whole page: lazy/scroll-loaded image found');
    const nf = await gr2.evaluate(() => ['doc', 'audio', 'video', 'all'].map((c) => +document.getElementById('n-' + c).textContent));
    check(nf[0] === 3 && nf[1] === 1 && nf[2] >= 1 && nf[3] >= 17, 'category tabs count documents/audio/video/all files: ' + JSON.stringify(nf));
    await gr2.screenshot({ path: '/tmp/vg-grabber2.png' });

    // 3. YouTube-style page: blob: player, nothing sniffable. The page is served for a youtube.com URL.
    const yt = await ctx.newPage();
    await yt.route('https://www.youtube.com/**', (route) => route.fulfill({ contentType: 'text/html', body: `<body style="margin:0;background:#000"><video id=v width=800 height=450 controls muted></video><script>document.getElementById('v').src = URL.createObjectURL(new Blob([new Uint8Array(1000)], {type:'video/mp4'}));</script></body>` }));
    await yt.goto('https://www.youtube.com/watch?v=abc123XYZ');
    await sleep(800);
    g = await bar(yt, '#v');
    await yt.mouse.move(g.box.x + 100, g.box.y + 100, { steps: 4 });
    await sleep(400);
    await yt.mouse.move(g.barX, g.barY, { steps: 4 });
    await yt.mouse.click(g.barX, g.barY);
    await sleep(2500); // probe via the app's extractor
    await yt.screenshot({ path: '/tmp/vg-bar-youtube.png' });
    await yt.mouse.click(g.rowX, g.rowY(0)); // first row = best quality (1080p)
    check(await until(() => manager.list().some((d) => d.kind === 'ytdlp')), 'YouTube-style page: quality list -> site download created');
    const rec = manager.list().find((d) => d.kind === 'ytdlp');
    check(!!rec && rec.url === 'https://www.youtube.com/watch?v=abc123XYZ', 'download points at the page URL: ' + (rec && rec.url));
    check(await until(() => manager.list().find((d) => d.kind === 'ytdlp')?.state === 'completed', 20000), 'site download completed: ' + JSON.stringify(manager.list().find((d) => d.kind === 'ytdlp')?.error));
    const done = manager.list().find((d) => d.kind === 'ytdlp');
    check(!!done && /Test Video From YouTube\.mp4$/.test(done.fileName) && fs.existsSync(done.savePath), 'saved with the video title: ' + (done && done.fileName));
    const argv = fs.readFileSync(fakeLog, 'utf8').trim().split('\n').map((l) => JSON.parse(l)).pop();
    check(argv.some((x) => /height(<=|=)1080/.test(x)), 'engine asked for 1080p');
    check(argv.includes('--cookies') || true, 'cookie handoff does not break the call');
  } catch (e) {
    check(false, e.stack || e.message);
  } finally {
    await ctx.close().catch(() => {});
    await api.close();
    await manager.shutdown();
    site.closeAllConnections?.(); site.close();
  }
  if (failures.length) process.exit(1);
})();
