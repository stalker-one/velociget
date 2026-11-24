'use strict';
/**
 * Joins a video file and an audio file WHILE they are still downloading, so nothing is left to do when the last byte arrives.
 *
 * The two downloads run in in-order mode (see DownloadTask: `sequentialChunk`), so each file's finished prefix grows steadily
 * from the front. A tiny HTTP server on 127.0.0.1 hands those growing prefixes to ffmpeg as two live inputs ("-c copy" into
 * the output file). Works for streams that can be read front to back (fragmented MP4, WebM - what YouTube-style sites serve).
 * Any problem simply reports failure and the caller joins the finished files the classic way.
 */
const http = require('http');
const fsp = require('fs/promises');
const { spawn } = require('child_process');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Bytes of the file that are on disk without gaps, counted from byte 0. */
function prefixOf(rec) {
  let n = 0;
  for (const g of rec.segments) {
    if (g.end < 0) return n;
    if (g.done || g.flushed > g.end) { n += g.end - g.start + 1; continue; }
    n += Math.max(0, g.flushed - g.start);
    break;
  }
  return n;
}

/**
 * @param sources [{ rec, isDone(), paths: [partPath, finalPath] }, ...] exactly two: video then audio
 * @returns { finished: Promise<boolean>, abort() }
 */
function start({ sources, out, ffmpegPath, signal, onProgress, duration }) {
  const fds = new Map();
  let stopped = false;
  const open = async (i) => {
    if (fds.has(i)) return fds.get(i);
    for (const p of sources[i].paths()) {
      if (!p) continue;
      const fh = await fsp.open(p, 'r').catch(() => null);
      if (fh) { fds.set(i, fh); return fh; }
    }
    return null;
  };

  const server = http.createServer(async (req, res) => {
    const i = Number((/^\/s(\d)$/.exec(req.url.split('?')[0]) || [])[1]);
    const src = sources[i];
    if (!src) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'content-type': 'application/octet-stream', 'accept-ranges': 'none', 'cache-control': 'no-store' });
    let off = 0;
    const buf = Buffer.allocUnsafe(1 << 20);
    try {
      while (!stopped && !res.destroyed) {
        const total = src.rec.total;
        const avail = src.isDone() ? total : prefixOf(src.rec);
        if (total > 0 && off >= total && src.isDone()) break;
        if (avail > off) {
          const fh = await open(i);
          if (!fh) { await sleep(30); continue; }
          const { bytesRead } = await fh.read(buf, 0, Math.min(buf.length, avail - off), off);
          if (!bytesRead) { await sleep(30); continue; }
          off += bytesRead;
          if (!res.write(Buffer.from(buf.subarray(0, bytesRead)))) await new Promise((r) => res.once('drain', r));
        } else {
          await sleep(25);
        }
      }
      if (process.env.VG_DEBUG_STREAM) process.stderr.write(`[serve s${i}] end off=${off} total=${src.rec.total} done=${src.isDone()} stopped=${stopped} destroyed=${res.destroyed}\n`);
      res.end();
    } catch { res.destroy(); }
  });

  let child = null;
  const finished = new Promise((resolve) => {
    server.on('error', () => resolve(false));
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      const args = ['-y', '-nostdin', '-nostats', '-loglevel', 'error', '-progress', 'pipe:1',
        '-probesize', '8M', '-analyzeduration', '5M',
        '-i', `http://127.0.0.1:${port}/s0`, '-i', `http://127.0.0.1:${port}/s1`,
        '-map', '0:v:0', '-map', '1:a:0', '-c', 'copy', out];
      child = spawn(ffmpegPath, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      let buf = '';
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (d) => {
        buf += d;
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i).trim();
          buf = buf.slice(i + 1);
          const t = /^out_time_(?:us|ms)=(\d+)/.exec(line);
          if (t && onProgress) onProgress(Number(t[1]) / 1e6);
        }
      });
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (d) => { if (process.env.VG_DEBUG_STREAM) process.stderr.write('[ffmpeg] ' + d); });
      child.on('error', () => resolve(false));
      child.on('close', (code) => resolve(code === 0));
    });
  }).then(async (ok) => {
    stopped = true;
    server.close();
    for (const fh of fds.values()) await fh.close().catch(() => {});
    if (!ok) await fsp.rm(out, { force: true }).catch(() => {});
    return ok;
  });

  const abort = () => {
    stopped = true;
    try { child && child.kill('SIGKILL'); } catch { /* ignore */ }
  };
  if (signal) signal.addEventListener('abort', abort, { once: true });
  return { finished, abort };
}

module.exports = { start, prefixOf };
