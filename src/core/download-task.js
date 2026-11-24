'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const { EventEmitter } = require('events');
const { fetchFollow, normalizeHeaders, dispatcherFor } = require('./http');
const { HttpError, FatalError, abortError, sleep, exists } = require('./util');
const { Throttle } = require('./throttle');
const names = require('./filename');

const MIN_SPLIT = 512 * 1024; // a segment is only split when both halves stay >= this
const MAX_RETRIES = 8;
const RETRY_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);
const PROGRESS_MS = 500;
const CHUNK = 16 * 1024 * 1024; // max bytes per ranged request (fewer round trips on high-latency links)
const WRITE_BATCH = 1024 * 1024; // chunks are coalesced into writes of about this size
const FATAL_FS = new Set(['ENOSPC', 'EACCES', 'EPERM', 'EISDIR', 'EMFILE', 'EROFS']);

function statusError(status) {
  const e = new HttpError(status);
  if (!RETRY_STATUS.has(status)) e.fatal = true;
  if (status === 401 || status === 407) e.message = `HTTP ${status}: authentication required`;
  else if (status === 403 || status === 404 || status === 410) e.message = `HTTP ${status}: the link is invalid or has expired`;
  return e;
}

/** Create a persisted record with defaults filled in. */
function newRecord(spec) {
  return {
    id: spec.id,
    kind: spec.kind || 'http', // 'http' | 'hls' | 'ytdlp'
    url: spec.url,
    finalUrl: null,
    referer: spec.referer || '',
    cookie: spec.cookie || '',
    userAgent: spec.userAgent || '',
    headers: spec.headers || {},
    pageTitle: spec.pageTitle || '',
    hls: spec.hls || null, // { variantUrl?, audioUrl? }
    fileName: spec.fileName || '',
    fileNameLocked: !!spec.fileName,
    description: spec.description || '',
    speedLimit: spec.speedLimit || 0, // bytes/second for this download only, 0 = none
    onComplete: spec.onComplete || 'none', // none | exit | sleep | hibernate | shutdown
    chunk: spec.chunk || 0, // bytes per ranged request (0 = default)
    ytdlp: spec.ytdlp || null, // { height } | { audio: true } for kind 'ytdlp'
    cookieList: spec.cookieList || null,
    ytMeta: null,
    workDir: '',
    lastTry: null,
    dir: spec.dir || '',
    autoDir: !spec.dir, // pick the category folder once the real file name is known
    savePath: '',
    partPath: '',
    category: spec.category || '',
    queueId: spec.queueId || 'main',
    connections: spec.connections || 0,
    state: 'paused', // queued | probing | downloading | merging | paused | completed | error | canceled
    error: null,
    warning: null,
    total: -1,
    downloaded: 0,
    resumable: false,
    etag: '',
    lastModified: '',
    contentType: '',
    segments: [],
    addedAt: Date.now(),
    completedAt: null,
  };
}

class DownloadTask extends EventEmitter {
  /**
   * @param rec  persisted record (see newRecord)
   * @param ctx  { throttle, settings: () => settings }
   */
  constructor(rec, ctx) {
    super();
    this.rec = rec;
    this.ctx = ctx;
    this.running = false;
    this.limiter = new Throttle(rec.speedLimit || 0);
    this.abort = null;
    this.speed = 0;
    this._window = 0;
    this._last = Date.now();
    this._timer = null;
    // Interrupted segments restart from the last byte known to be on disk.
    for (const s of rec.segments) s.pos = s.flushed;
  }

  get signal() {
    return this.abort.signal;
  }
  get settings() {
    return this.ctx.settings();
  }
  get throttle() {
    return this.ctx.throttle;
  }
  get dispatcher() {
    return dispatcherFor(this.settings.proxy);
  }

  baseHeaders() {
    const r = this.rec;
    return normalizeHeaders({
      'user-agent': r.userAgent || this.settings.userAgent || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) IDM By Encodal/0.1',
      referer: r.referer,
      cookie: r.cookie,
      ...r.headers,
    });
  }

  setState(state, error = null) {
    this.rec.state = state;
    this.rec.error = error;
    this.emit('state', this);
  }

  addBytes(n) {
    this._window += n;
  }

  // ---- lifecycle --------------------------------------------------------

  async start() {
    if (this.running) return;
    this.running = true;
    this.pauseRequested = false;
    this.cancelRequested = false;
    this.abort = new AbortController();
    this.rec.error = null;
    this.rec.warning = null;
    this.rec.lastTry = Date.now();
    this.limiter.setLimit(this.rec.speedLimit || 0);
    this.setState('probing');
    this._startTimer();
    try {
      if (this.rec.kind === 'hls') await require('./hls').run(this);
      else if (this.rec.kind === 'ytdlp') await require('./ytdlp').runTask(this);
      else if (this.rec.kind === 'ftp') await require('./ftp').run(this);
      else await this._runHttp();
      this.rec.completedAt = Date.now();
      this.setState('completed');
    } catch (e) {
      if (this.cancelRequested) this.setState('canceled');
      else if (this.pauseRequested) this.setState('paused');
      else this.setState('error', e && e.message ? e.message : String(e));
    } finally {
      this._stopTimer();
      this.running = false;
      this.speed = 0;
      this.emit('progress', this);
      this.emit('settled', this);
    }
  }

  pause() {
    if (!this.running) {
      if (this.rec.state === 'queued') this.setState('paused');
      return;
    }
    this.pauseRequested = true;
    this.abort.abort();
  }

  cancel() {
    if (!this.running) return;
    this.cancelRequested = true;
    this.abort.abort();
  }

  /** Remove partial files (not the finished file). */
  async removePartial() {
    const r = this.rec;
    if (r.workDir) await fsp.rm(r.workDir, { recursive: true, force: true }).catch(() => {});
    if (!r.savePath) return;
    const base = path.basename(r.savePath);
    const entries = await fsp.readdir(path.dirname(r.savePath)).catch(() => []);
    for (const n of entries) {
      if (n.startsWith(base + '.part') || n.startsWith(base + '.audio.part')) {
        await fsp.rm(path.join(path.dirname(r.savePath), n), { recursive: true, force: true }).catch(() => {});
      }
    }
  }

  _startTimer() {
    this._last = Date.now();
    this._window = 0;
    this._timer = setInterval(() => this._tick(), PROGRESS_MS);
  }
  _stopTimer() {
    clearInterval(this._timer);
    this._timer = null;
  }
  _tick() {
    const now = Date.now();
    const dt = Math.max(0.001, (now - this._last) / 1000);
    this._last = now;
    const inst = this._window / dt;
    this._window = 0;
    this.speed = this.speed ? this.speed * 0.75 + inst * 0.25 : inst;
    this.emit('progress', this);
  }

  get downloadedBytes() {
    const r = this.rec;
    if (r.kind === 'hls' || !r.segments.length) return r.downloaded;
    let n = 0;
    for (const s of r.segments) n += Math.max(0, s.flushed - s.start);
    return n;
  }

  snapshot() {
    const r = this.rec;
    const downloaded = this.downloadedBytes;
    return {
      id: r.id,
      kind: r.kind,
      url: r.url,
      referer: r.referer,
      fileName: r.fileName,
      dir: r.dir,
      savePath: r.savePath,
      category: r.category,
      queueId: r.queueId,
      state: r.state,
      error: r.error,
      warning: r.warning,
      total: r.total,
      downloaded: r.state === 'completed' && r.total > 0 ? r.total : downloaded,
      speed: Math.round(this.speed),
      eta: r.total > 0 && this.speed > 1 ? Math.round((r.total - downloaded) / this.speed) : null,
      resumable: r.resumable,
      connections: r.segments.filter((s) => s._active).length || r.nativeConns || 0,
      segments: r.segments.map((s) => ({ start: s.start, end: s.end, pos: s.flushed, done: !!s.done, active: !!s._active })),
      addedAt: r.addedAt,
      completedAt: r.completedAt,
      lastTry: r.lastTry,
      description: r.description,
      speedLimit: r.speedLimit,
      onComplete: r.onComplete,
      ytdlp: r.ytdlp,
      mergePct: r.mergePct,
    };
  }

  /** Plain-object form for the store (no runtime-only fields). */
  serialize() {
    return {
      ...this.rec,
      segments: this.rec.segments.map((s) => ({ start: s.start, end: s.end, pos: s.flushed, flushed: s.flushed, done: !!s.done })),
    };
  }

  // ---- plain HTTP download ---------------------------------------------

  async _runHttp() {
    const rec = this.rec;
    const headers = this.baseHeaders();
    const info = await probe(rec.url, headers, this.signal, this.dispatcher);
    // A web page instead of a file: hand it to the site extractor (video pages, players using blob: URLs).
    if (/^text\/html/i.test(info.contentType) && !/\.html?$/i.test(new URL(rec.url).pathname) && !rec.savePath) {
      rec.kind = 'ytdlp';
      rec.ytdlp = rec.ytdlp || {};
      return require('./ytdlp').runTask(this);
    }
    await this._reconcile(info);

    if (rec.total === 0) {
      await fsp.writeFile(rec.savePath, '');
      return;
    }

    this.setState('downloading');
    const fh = await fsp.open(rec.partPath, 'r+').catch(() => fsp.open(rec.partPath, 'w+'));
    try {
      if (rec.total > 0) {
        const st = await fh.stat();
        if (st.size !== rec.total) await fh.truncate(rec.total);
      }
      await this._transfer(fh, headers);
      const got = this.downloadedBytes;
      if (rec.total > 0 && got !== rec.total) {
        throw new Error(`Size mismatch: expected ${rec.total} bytes, received ${got}`);
      }
      await fh.truncate(got);
    } finally {
      await fh.close().catch(() => {});
    }
    this.setState('merging');
    await this._publish();
  }

  /** Decide file name/location and whether any partial data on disk can be reused. */
  async _reconcile(info) {
    const rec = this.rec;
    const settings = this.settings;
    rec.finalUrl = info.finalUrl;

    const havePart = rec.partPath && (await exists(rec.partPath));
    const reusable =
      havePart &&
      rec.segments.length > 0 &&
      rec.total === info.total &&
      rec.resumable === info.resumable &&
      (!rec.etag || rec.etag === info.etag) &&
      (!rec.lastModified || !info.lastModified || rec.lastModified === info.lastModified);
    if (!reusable) {
      if (havePart) await fsp.unlink(rec.partPath).catch(() => {});
      rec.segments = [];
    }
    rec.total = info.total;
    rec.resumable = info.resumable;
    rec.etag = info.etag;
    rec.lastModified = info.lastModified;
    rec.contentType = info.contentType;

    if (!rec.savePath) {
      const name = rec.fileNameLocked && rec.fileName ? names.sanitize(rec.fileName) : info.fileName || names.sanitize(rec.fileName);
      rec.category = rec.category || names.categoryFor(name);
      if (rec.autoDir) {
        rec.dir = settings.categorize ? path.join(settings.downloadDir, rec.category === 'General' ? '' : rec.category) : settings.downloadDir;
      }
      await fsp.mkdir(rec.dir, { recursive: true });
      rec.savePath = await names.uniquePath(rec.dir, name);
      rec.fileName = path.basename(rec.savePath);
      rec.partPath = rec.savePath + '.part';
    }

    if (!rec.segments.length) {
      const want = Math.max(1, Math.min(32, rec.connections || settings.maxConnections));
      if (rec.resumable && rec.total > 0 && rec.sequentialChunk > 0) {
        // In-order mode (used while the file is being joined with another stream as it arrives): many small
        // consecutive pieces, handed to the connections front to back, so the finished prefix grows at full speed.
        const piece = Math.max(MIN_SPLIT, Math.min(rec.sequentialChunk, Math.ceil(rec.total / (want * 3)))); // small files still keep every connection busy
        for (let start = 0; start < rec.total; start += piece) {
          const end = Math.min(rec.total - 1, start + piece - 1);
          rec.segments.push({ start, end, pos: start, flushed: start, done: false });
        }
      } else if (rec.resumable && rec.total > 0) {
        // Start all requested workers when the file is large enough to give each
        // worker a useful range. The old floor(total / (MIN_SPLIT * 2)) rule
        // silently reduced an 8-connection download to 4 connections for a
        // 4 MiB file, making the setting appear to be ignored.
        const n = Math.max(1, Math.min(want, Math.floor(rec.total / MIN_SPLIT)));
        const size = Math.floor(rec.total / n);
        for (let i = 0; i < n; i++) {
          const start = i * size;
          const end = i === n - 1 ? rec.total - 1 : start + size - 1;
          rec.segments.push({ start, end, pos: start, flushed: start, done: false });
        }
      } else {
        rec.segments.push({ start: 0, end: -1, pos: 0, flushed: 0, done: false });
      }
    }
    for (const s of rec.segments) {
      s.pos = s.flushed;
      s.done = s.end >= 0 && s.flushed > s.end;
    }
  }

  async _transfer(fh, headers) {
    const rec = this.rec;
    const maxConn = rec.resumable ? Math.max(1, Math.min(32, rec.connections || this.settings.maxConnections)) : 1;
    const running = new Map();
    let fatal = null;
    this._limit = maxConn; // lowered when the server pushes back (429/503): MDM-style "use only what the server allows"

    const launch = (seg) => {
      seg._active = true;
      const p = this._runSegment(seg, fh, headers).then(
        () => {
          seg.done = true;
        },
        (e) => {
          if (!fatal) {
            fatal = e;
            for (const s of rec.segments) if (s._ac) s._ac.abort();
          }
        },
      );
      const wrapped = p.finally(() => {
        seg._active = false;
        running.delete(seg);
      });
      running.set(seg, wrapped);
    };

    for (;;) {
      if (fatal || this.signal.aborted) break;
      for (const seg of rec.segments) {
        if (!seg.done && !seg._active && running.size < Math.min(maxConn, this._limit)) launch(seg);
      }
      // A free connection takes the second half of the largest running segment.
      if (running.size < Math.min(maxConn, this._limit) && rec.resumable && this._split()) continue;
      if (running.size === 0) break;
      await Promise.race(running.values());
    }
    await Promise.all(running.values());
    if (fatal) throw fatal;
    if (this.signal.aborted) throw abortError();
  }

  _split() {
    let victim = null;
    let most = 0;
    for (const s of this.rec.segments) {
      if (!s._active || s.done || s.end < 0 || s._reqEnd === undefined) continue;
      const remaining = s.end - s.pos + 1;
      if (remaining > most) {
        most = remaining;
        victim = s;
      }
    }
    if (!victim || victim._reqEnd === undefined) return false;
    const mid = Math.max(victim.pos + Math.floor(most / 2), victim._reqEnd + 1);
    if (victim.end - mid + 1 < MIN_SPLIT) return false;
    const seg = { start: mid, end: victim.end, pos: mid, flushed: mid, done: false };
    victim.end = mid - 1;
    const segs = this.rec.segments;
    segs.splice(segs.indexOf(victim) + 1, 0, seg);
    return true;
  }

  _segDone(seg) {
    return seg.end >= 0 && seg.pos > seg.end;
  }

  _retryable(e) {
    if (!e || e.fatal) return false;
    if (e instanceof HttpError) return RETRY_STATUS.has(e.status);
    if (e.code && FATAL_FS.has(e.code)) return false;
    return true;
  }

  async _runSegment(seg, fh, headers) {
    let attempt = 0;
    while (!this._segDone(seg)) {
      seg._ac = new AbortController();
      const onAbort = () => seg._ac.abort();
      this.signal.addEventListener('abort', onAbort, { once: true });
      const before = seg.flushed;
      try {
        const complete = await this._fetchSegment(seg, fh, headers);
        if (this._segDone(seg)) break;
        if (complete) {
          attempt = 0;
          continue;
        }
        throw new Error('Connection closed before the segment was complete');
      } catch (e) {
        if (this.signal.aborted || seg._ac.signal.aborted) throw e;
        if (!this._retryable(e)) throw e;
        if (e instanceof HttpError && (e.status === 429 || e.status === 503) && this._limit > 1 && Date.now() - (this._limitAt || 0) > 1500) {
          this._limitAt = Date.now();
          this._limit = Math.max(1, Math.floor(this._limit * 0.6));
        }
        if (seg.flushed > before) attempt = 0;
        if (++attempt > MAX_RETRIES) throw e;
        this.emit('retry', { seg, attempt, error: e });
        await sleep(Math.min(30_000, 400 * 2 ** (attempt - 1)), this.signal);
        if (!this.rec.resumable) {
          seg.pos = 0;
          seg.flushed = 0;
        } else {
          seg.pos = seg.flushed;
        }
      } finally {
        this.signal.removeEventListener('abort', onAbort);
      }
    }
  }

  /**
   * One HTTP request for (part of) a segment. Resumable downloads ask for at most CHUNK bytes at a
   * time. That bounds how long a connection is committed to a byte range, so another connection
   * can take the tail of the segment without the running request ever being abandoned half-way.
   * Resolves true when the requested range was fully received.
   */
  async _fetchSegment(seg, fh, baseHeaders) {
    const rec = this.rec;
    const headers = { ...baseHeaders, 'accept-encoding': 'identity' };
    const ranged = rec.resumable;
    let reqEnd = -1;
    if (ranged) {
      reqEnd = Math.min(seg.end, seg.pos + (this.rec.chunk || CHUNK) - 1);
      seg._reqEnd = reqEnd;
      headers.range = `bytes=${seg.pos}-${reqEnd}`;
      if (rec.etag) headers['if-range'] = rec.etag;
    }
    const { res } = await fetchFollow(rec.finalUrl || rec.url, {
      headers,
      signal: seg._ac.signal,
      dispatcher: this.dispatcher,
    });
    const st = res.statusCode;
    try {
      if (ranged) {
        if (st === 206) {
          const m = /bytes\s+(\d+)-/i.exec(res.headers['content-range'] || '');
          if (!m || Number(m[1]) !== seg.pos) throw new FatalError('The server returned an unexpected byte range');
        } else if (st === 200) {
          throw new FatalError('The file changed on the server, or the server stopped honoring range requests', 'FILE_CHANGED');
        } else {
          throw statusError(st);
        }
      } else if (st !== 200) {
        throw statusError(st);
      }

      // Receive in small pieces, write in big ones: far fewer syscalls, and seg.pos only ever
      // advances past bytes that are really on disk (so a resume never skips data).
      let at = seg.pos;
      let pending = [];
      let pendingLen = 0;
      let lastFlush = Date.now();
      const flush = async () => {
        if (!pendingLen) return;
        const buf = pending.length === 1 ? pending[0] : Buffer.concat(pending, pendingLen);
        pending = [];
        pendingLen = 0;
        lastFlush = Date.now();
        await fh.write(buf, 0, buf.length, at);
        at += buf.length;
        seg.pos = at;
        seg.flushed = at;
      };
      try {
        for await (const chunk of res.body) {
          await this.throttle.take(chunk.length, seg._ac.signal);
          await this.limiter.take(chunk.length, seg._ac.signal);
          pending.push(chunk);
          pendingLen += chunk.length;
          this._window += chunk.length;
          // big writes when data flows fast, but never hold bytes back for long (keeps progress smooth on slow links)
          if (pendingLen >= WRITE_BATCH || Date.now() - lastFlush > 250) await flush();
        }
        await flush();
      } catch (e) {
        await flush().catch(() => {}); // keep what already arrived
        throw e;
      }
    } finally {
      if (!res.body.destroyed) res.body.destroy();
    }
    if (!ranged) {
      // Unknown length: the end of the stream is the end of the file.
      seg.end = seg.pos - 1;
      rec.total = seg.pos;
      return true;
    }
    return seg.pos > reqEnd;
  }

  async _publish() {
    const rec = this.rec;
    let target = rec.savePath;
    if (await exists(target)) {
      target = await names.uniquePath(rec.dir, rec.fileName);
      rec.savePath = target;
      rec.fileName = path.basename(target);
    }
    await fsp.rename(rec.partPath, target);
    rec.partPath = '';
    if (rec.lastModified && this.settings.preserveTime) {
      const t = new Date(rec.lastModified);
      if (!Number.isNaN(t.getTime())) await fsp.utimes(target, t, t).catch(() => {});
    }
  }
}

/**
 * Ask the server about the resource with a 1-byte ranged GET: tells us the total size,
 * whether ranges really work, and the validators used to detect a changed file.
 */
async function probe(url, headers, signal, dispatcher) {
  const { res, url: finalUrl } = await fetchFollow(url, {
    headers: { ...headers, range: 'bytes=0-0', 'accept-encoding': 'identity' },
    signal,
    dispatcher,
  });
  const h = res.headers;
  const st = res.statusCode;
  let total = -1;
  let resumable = false;
  if (st === 206) {
    const m = /bytes\s+\d+-\d+\/(\d+|\*)/i.exec(h['content-range'] || '');
    if (m && m[1] !== '*') {
      total = Number(m[1]);
      resumable = true;
    } else if (h['content-length']) {
      total = -1;
    }
  } else if (st === 200) {
    total = h['content-length'] !== undefined ? Number(h['content-length']) : -1;
  } else if (st === 416) {
    total = 0;
  } else {
    try {
      res.body.destroy();
    } catch {
      /* ignore */
    }
    throw statusError(st);
  }
  await res.body.dump({ limit: 64 * 1024 }).catch(() => {});
  const etag = h.etag && !h.etag.startsWith('W/') ? h.etag : '';
  return {
    finalUrl,
    total,
    resumable,
    etag,
    lastModified: h['last-modified'] || '',
    contentType: h['content-type'] || '',
    fileName: names.chooseName({
      contentDisposition: h['content-disposition'],
      url: finalUrl,
      contentType: h['content-type'],
      fallback: names.fromUrl(url),
    }),
  };
}

module.exports = { DownloadTask, newRecord, probe, MIN_SPLIT };
