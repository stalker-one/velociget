'use strict';
/** ftp:// and ftps:// downloads (single connection, resumes with REST). */
const fsp = require('fs').promises;
const path = require('path');
const { Writable } = require('stream');
const names = require('./filename');

async function run(task) {
  const ftp = require('basic-ftp');
  const rec = task.rec;
  const u = new URL(rec.url);
  const client = new ftp.Client(60_000);
  const onAbort = () => client.close();
  task.signal.addEventListener('abort', onAbort);
  try {
    await client.access({
      host: u.hostname,
      port: Number(u.port) || 21,
      user: decodeURIComponent(u.username) || 'anonymous',
      password: decodeURIComponent(u.password) || 'anonymous@',
      secure: u.protocol === 'ftps:',
    });
    const remote = decodeURIComponent(u.pathname);
    const total = await client.size(remote);
    await task._reconcile({
      finalUrl: rec.url, total, resumable: true, etag: '', lastModified: '', contentType: '',
      fileName: names.sanitize(path.basename(remote) || 'download'),
    });
    const old = rec.segments.length === 1 && rec.segments[0].flushed > 0 ? rec.segments[0].flushed : 0;
    const startAt = old < total ? old : 0;
    const seg = { start: 0, end: total - 1, pos: startAt, flushed: startAt, done: false };
    rec.segments = [seg];

    task.setState('downloading');
    const fh = await fsp.open(rec.partPath, startAt ? 'r+' : 'w');
    let at = startAt;
    try {
      const out = new Writable({
        write(chunk, _enc, cb) {
          (async () => {
            await task.throttle.take(chunk.length, task.signal);
            await task.limiter.take(chunk.length, task.signal);
            await fh.write(chunk, 0, chunk.length, at);
            at += chunk.length;
            seg.pos = at;
            seg.flushed = at;
            task._window += chunk.length;
          })().then(() => cb(), cb);
        },
      });
      if (total > startAt) await client.downloadTo(out, remote, startAt);
      if (at !== total) throw new Error(`Size mismatch: expected ${total} bytes, received ${at}`);
      seg.done = true;
    } finally {
      await fh.close().catch(() => {});
    }
    task.setState('merging');
    await task._publish();
  } finally {
    task.signal.removeEventListener('abort', onAbort);
    client.close();
  }
}

module.exports = { run };
