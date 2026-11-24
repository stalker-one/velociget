'use strict';
// Drives the real app: Add URL -> File Info dialog -> progress window -> complete window -> list. Screenshots to /tmp.
const { _electron: electron } = require('playwright');
const http = require('http');
const path = require('path');
const fs = require('fs');
const os = require('os');

const out = (n) => `/tmp/vg-${n}.png`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, msg) => { console.log(ok ? 'PASS' : 'FAIL', msg); if (!ok) failures.push(msg); };

(async () => {
  const data = Buffer.alloc(8 * 1024 * 1024, 3);
  const srv = http.createServer((req, res) => {
    if (req.url === '/hls/master.m3u8') { res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' }); return res.end('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nv720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\nv480.m3u8\n'); }
    if (/^\/hls\/v\d+\.m3u8/.test(req.url)) { res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' }); return res.end('#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\ns1.ts\n#EXT-X-ENDLIST\n'); }
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    const s = m ? +m[1] : 0, e = m && m[2] ? Math.min(+m[2], data.length - 1) : data.length - 1;
    const type = req.url.endsWith('.mp4') ? 'video/mp4' : req.url.endsWith('.pdf') ? 'application/pdf' : 'application/zip';
    res.writeHead(m ? 206 : 200, { 'content-length': e - s + 1, 'accept-ranges': 'bytes', 'content-type': type, ...(m ? { 'content-range': `bytes ${s}-${e}/${data.length}` } : {}) });
    let off = s; const pump = () => { if (res.destroyed) return; if (off > e) return res.end(); const n = Math.min(65536, e - off + 1); res.write(data.subarray(off, off + n)); off += n; setTimeout(pump, 160); }; pump();
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-app-'));
  const dl = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-app-dl-'));

  const app = await electron.launch({ executablePath: require('electron'), args: ['--no-sandbox', `--user-data-dir=${home}`, path.join(__dirname, '..')], env: { ...process.env, HOME: home } });
  const win = await app.firstWindow();
  const errors = [];
  const watch = (w) => { w.on('pageerror', (e) => errors.push(e.message)); w.on('console', (m) => m.type() === 'error' && errors.push(m.text())); };
  watch(win);
  app.on('window', watch);
  await win.waitForSelector('.tbtn');
  await win.evaluate((d) => window.api.setSettings({ downloadDir: d, categorize: true, clipboardWatch: false }), dl);

  const find = async (name, ms = 15000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const w = app.windows().find((x) => x.url().includes(name));
      if (w) return w;
      await sleep(100);
    }
    return null;
  };

  // 0. The "New" menu offers every kind of download, and the stream dialog lists HLS variants
  await win.click('.tbtn[data-cmd=newMenu]');
  check((await win.$$eval('#newMenu li', (l) => l.length)) === 6, 'New menu lists 6 download types');
  await win.click('#newMenu li[data-new=stream]');
  await win.fill('#vUrl', `${base}/hls/master.m3u8`);
  await win.click('#vLoad');
  await win.waitForSelector('#vResult:not([hidden])', { timeout: 15000 }).catch(() => {});
  check((await win.$$eval('#vQuality option', (o) => o.length)) >= 1, 'stream dialog lists the playlist qualities');
  await win.click('#dlgVideo .cancel');
  // 1. Add URL -> File Info
  // paste bar: a link opens the details window and starts reading it
  await win.fill('#quickUrl', 'https://example.invalid/video/1');
  await win.press('#quickUrl', 'Enter');
  await win.waitForSelector('#dlgInfo[open]', { timeout: 5000 });
  await win.waitForFunction(() => document.querySelector('#iStatus').textContent.length > 0, null, { timeout: 60000 });
  check((await win.inputValue('#iUrl')) === 'https://example.invalid/video/1', 'paste bar opens Link details with the link');
  await win.click('#dlgInfo .cancel');
  await win.click('.tbtn[data-cmd=newMenu]');
  await win.click('#newMenu li[data-new=http]');
  await win.fill('#addUrls', `${base}/holiday-clip.mp4`);
  await win.click('#addOk');
  const info = await find('fileinfo.html');
  check(!!info, 'File Info window opened');
  await info.waitForFunction(() => document.querySelector('#name').textContent !== 'Looking up the file…');
  await sleep(300);
  await info.screenshot({ path: out('fileinfo') });
  check((await info.textContent('#name')) === 'holiday-clip.mp4', 'file name detected: ' + (await info.textContent('#name')));
  check((await info.inputValue('#saveas')).endsWith('Video/holiday-clip.mp4'), 'saved into the Video category folder: ' + (await info.inputValue('#saveas')));
  check((await info.textContent('#meta')).includes('8.00 MB'), 'size shown: ' + (await info.textContent('#meta')));

  // 2. Start -> progress window
  await info.click('#start');
  const prog = await find('progress.html');
  check(!!prog, 'progress window opened');
  await prog.waitForFunction(() => document.querySelector('#dl').textContent.includes('%') && parseFloat(document.querySelector("#pct").textContent) > 8, null, { timeout: 15000 });
  await sleep(300);
  await prog.screenshot({ path: out('progress') });
  const status = await prog.textContent('#st');
  check(/Receiving|Connecting/.test(status), 'progress status: ' + status);

  // 3. completes -> progress closes, complete window opens
  const done = await find('complete.html', 30000);
  check(!!done, 'complete window opened');
  await sleep(300);
  await done.screenshot({ path: out('complete') });
  check(fs.existsSync(path.join(dl, 'Video', 'holiday-clip.mp4')) && fs.statSync(path.join(dl, 'Video', 'holiday-clip.mp4')).size === data.length, 'file saved completely');
  await done.click('#close').catch(() => {});

  // 4. more rows for a representative main-window screenshot
  await win.evaluate((b) => window.api.add({ urls: [`${b}/setup-installer.zip`, `${b}/annual-report.pdf`], startNow: true }), base);
  await win.evaluate((b) => window.api.add({ urls: [`${b}/lecture-recording.mp4`], startNow: false }), base);
  await sleep(2500);
  await win.click('#rows tr >> nth=1');
  await win.screenshot({ path: out('main') });
  check((await win.$$('#rows tr')).length === 4, 'four rows in the list');
  check((await win.$$('#head th')).length === 8, 'eight IDM columns');

  // 5. options: site videos tab
  await win.click('.tbtn[data-cmd=options]');
  await win.click('[data-tab=site]');
  await sleep(600);
  await win.screenshot({ path: out('options') });
  check(errors.length === 0, 'no renderer errors ' + (errors.length ? JSON.stringify(errors) : ''));

  await app.close();
  srv.closeAllConnections?.(); srv.close();
  if (failures.length) process.exit(1);
})().catch((e) => { console.error('SMOKE FAIL', e.stack || e.message); process.exit(1); });
