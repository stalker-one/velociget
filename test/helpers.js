'use strict';
const http = require('http');
const os = require('os');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { DownloadTask, newRecord } = require('../src/core/download-task');
const { Throttle } = require('../src/core/throttle');
const { defaultSettings } = require('../src/core/settings');

function makeData(size, seed = 1) {
  const b = Buffer.alloc(size);
  let x = seed;
  for (let i = 0; i < size; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff;
    b[i] = (x >> 16) & 255;
  }
  return b;
}

const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

function tmpDir(prefix = 'vget-') {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

/** Range-capable file server with knobs for the failure modes a download manager must survive. */
function fileServer(data, opts = {}) {
  const log = [];
  let drops = opts.drops || 0;
  const server = http.createServer((req, res) => {
    log.push({ url: req.url, range: req.headers.range, headers: req.headers });
    if (req.url.startsWith('/missing')) {
      res.writeHead(404);
      return res.end();
    }
    if (req.url.startsWith('/redirect')) {
      res.writeHead(302, { location: '/file.bin' });
      return res.end();
    }
    const headers = { 'content-type': 'application/octet-stream', etag: '"v1"' };
    if (opts.disposition) headers['content-disposition'] = opts.disposition;
    let start = 0;
    let end = data.length - 1;
    let status = 200;
    const ranges = opts.ranges !== false;
    if (ranges) headers['accept-ranges'] = 'bytes';
    if (req.headers.range && ranges) {
      const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range);
      if (m) {
        start = Number(m[1]);
        if (m[2]) end = Math.min(Number(m[2]), data.length - 1);
        status = 206;
        headers['content-range'] = `bytes ${start}-${end}/${data.length}`;
      }
    }
    headers['content-length'] = end - start + 1;
    res.writeHead(status, headers);
    const body = data.subarray(start, end + 1);
    if (drops > 0 && body.length > 1_000_000) {
      drops--;
      res.write(body.subarray(0, Math.floor(body.length / 3)));
      setTimeout(() => res.destroy(), 20);
      return;
    }
    let off = 0;
    const CH = 64 * 1024;
    const pump = () => {
      if (res.destroyed) return;
      if (off >= body.length) return res.end();
      const ok = res.write(body.subarray(off, off + CH));
      off += CH;
      const next = () => (opts.chunkDelay ? setTimeout(pump, opts.chunkDelay) : setImmediate(pump));
      if (ok) next();
      else res.once('drain', next);
    };
    pump();
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({ server, log, port, url: (p = '/file.bin') => `http://127.0.0.1:${port}${p}`, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }) });
    });
  });
}

function makeTask(spec, overrides = {}) {
  const dir = spec.dir || tmpDir();
  const settings = { ...defaultSettings(), downloadDir: dir, categorize: false, ...overrides };
  const rec = newRecord({ id: 't' + Math.random().toString(36).slice(2), ...spec, dir: spec.dir || dir });
  const task = new DownloadTask(rec, { throttle: overrides.throttle || new Throttle(0), settings: () => settings });
  return { task, dir, settings };
}

const waitSettled = (task) => new Promise((r) => task.once('settled', r));

async function waitUntil(fn, ms = 10_000, step = 10) {
  const t0 = Date.now();
  while (!fn()) {
    if (Date.now() - t0 > ms) throw new Error('waitUntil timed out');
    await new Promise((r) => setTimeout(r, step));
  }
}

module.exports = { makeData, sha, tmpDir, fileServer, makeTask, waitSettled, waitUntil };
