'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');
const { fetchFollow } = require('./http');
const { HttpError, FatalError, abortError, sleep, exists } = require('./util');
const names = require('./filename');

const SEGMENT_RETRIES = 5;
const MAX_MANIFEST_CHARS = 256 * 1024;
const isManifestBody = (text) => typeof text === 'string' && text.length <= MAX_MANIFEST_CHARS && /^\uFEFF?\s*#EXTM3U/.test(text.slice(0, 64));

// ---- playlist parsing -----------------------------------------------------

function parseAttrs(s) {
  const out = {};
  const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
  let m;
  while ((m = re.exec(s))) out[m[1]] = m[2].replace(/^"|"$/g, '');
  return out;
}

function parseMaster(text, base) {
  const lines = text.split(/\r?\n/).map((l) => l.trim());
  const variants = [];
  const audio = {};
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (l.startsWith('#EXT-X-MEDIA:')) {
      const a = parseAttrs(l.slice(13));
      if (a.TYPE === 'AUDIO' && a.URI) audio[a['GROUP-ID']] = audio[a['GROUP-ID']] || { uri: new URL(a.URI, base).toString(), name: a.NAME, lang: a.LANGUAGE || '' };
    } else if (l.startsWith('#EXT-X-STREAM-INF:')) {
      const a = parseAttrs(l.slice(18));
      let j = i + 1;
      while (j < lines.length && (!lines[j] || lines[j].startsWith('#'))) j++;
      if (j >= lines.length) break;
      const res = a.RESOLUTION ? a.RESOLUTION.split('x').map(Number) : null;
      variants.push({
        uri: new URL(lines[j], base).toString(),
        bandwidth: Number(a.BANDWIDTH || a['AVERAGE-BANDWIDTH'] || 0),
        width: res ? res[0] : 0,
        height: res ? res[1] : 0,
        codecs: a.CODECS || '',
        audio: a.AUDIO || '',
      });
      i = j;
    }
  }
  return { variants, audio };
}

function parseMedia(text, base) {
  const lines = text.split(/\r?\n/).map((l) => l.trim());
  const segments = [];
  let key = null;
  let map = null;
  let duration = 0;
  let byterange = null;
  let seq = 0;
  let endList = false;
  let lastRangeEnd = 0;
  for (const l of lines) {
    if (!l) continue;
    if (l.startsWith('#EXT-X-MEDIA-SEQUENCE:')) seq = Number(l.split(':')[1]) || 0;
    else if (l.startsWith('#EXT-X-KEY:')) {
      const a = parseAttrs(l.slice(11));
      key = a.METHOD === 'NONE' ? null : { method: a.METHOD, uri: a.URI ? new URL(a.URI, base).toString() : null, iv: a.IV || null, format: a.KEYFORMAT || 'identity' };
    } else if (l.startsWith('#EXT-X-MAP:')) {
      const a = parseAttrs(l.slice(11));
      map = { uri: new URL(a.URI, base).toString(), byterange: a.BYTERANGE || null };
    } else if (l.startsWith('#EXTINF:')) duration = parseFloat(l.slice(8)) || 0;
    else if (l.startsWith('#EXT-X-BYTERANGE:')) byterange = l.slice(17);
    else if (l === '#EXT-X-ENDLIST') endList = true;
    else if (!l.startsWith('#')) {
      let range = null;
      if (byterange) {
        const [len, off] = byterange.split('@').map(Number);
        const start = Number.isFinite(off) ? off : lastRangeEnd;
        range = { start, end: start + len - 1 };
        lastRangeEnd = start + len;
        byterange = null;
      }
      segments.push({ uri: new URL(l, base).toString(), duration, key, map, range, seq: seq + segments.length });
      duration = 0;
    }
  }
  return { segments, endList };
}

async function fetchText(ctx, url) {
  const { res, url: finalUrl } = await fetchFollow(url, { headers: ctx.headers, signal: ctx.signal, dispatcher: ctx.dispatcher });
  if (res.statusCode !== 200) {
    await res.body.dump({ limit: 65536 }).catch(() => {});
    throw new HttpError(res.statusCode);
  }
  return { text: await res.body.text(), url: finalUrl };
}

/** Variants of a master playlist (used by the browser extension to offer a quality choice). */
async function listVariants(url, headers = {}, signal, manifestBody = '') {
  const ctx = { headers, signal, dispatcher: undefined };
  const { text, url: finalUrl } = isManifestBody(manifestBody)
    ? { text: manifestBody, url }
    : await fetchText(ctx, url);
  if (!/#EXT-X-STREAM-INF/.test(text)) return { master: false, variants: [] };
  const m = parseMaster(text, finalUrl);
  const variants = m.variants
    .sort((a, b) => b.height - a.height || b.bandwidth - a.bandwidth)
    .map((v) => ({
      url: v.uri,
      bandwidth: v.bandwidth,
      height: v.height,
      label: v.height ? `${v.height}p` : `${Math.round(v.bandwidth / 1000)} kbps`,
      lang: (m.audio[v.audio] && m.audio[v.audio].lang) || '',
      duration: 0,
      size: 0,
    }));
  // Duration (and so an estimated size, like XDM's "~ 553.6 MB") from each variant's own playlist; best effort, never blocks the list for long.
  await Promise.all(variants.map(async (v) => {
    try {
      const r = await Promise.race([fetchText(ctx, v.url), new Promise((_, rej) => setTimeout(() => rej(new Error('t')), 4000))]);
      const media = parseMedia(r.text, r.url);
      v.duration = Math.round(media.segments.reduce((n, g) => n + (g.duration || 0), 0));
      v.size = v.duration && v.bandwidth ? Math.round((v.bandwidth * v.duration) / 8) : 0;
    } catch { /* playlist not reachable a second time: the list still shows bandwidth */ }
  }));
  return { master: true, variants };
}

function pickVariant(variants, preferredUrl) {
  if (preferredUrl) {
    const hit = variants.find((v) => v.uri === preferredUrl);
    if (hit) return hit;
  }
  return [...variants].sort((a, b) => b.height - a.height || b.bandwidth - a.bandwidth)[0];
}

// ---- download -----------------------------------------------------------------

function runFfmpeg(bin, args, signal) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, ['-y', '-loglevel', 'error', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
    let err = '';
    p.stderr.on('data', (d) => (err += d));
    const onAbort = () => p.kill();
    signal.addEventListener('abort', onAbort, { once: true });
    p.on('error', reject);
    p.on('close', (code) => {
      signal.removeEventListener('abort', onAbort);
      if (signal.aborted) reject(abortError());
      else if (code === 0) resolve();
      else reject(new Error(`ffmpeg failed: ${err.trim().slice(-300)}`));
    });
  });
}

function ivFor(seg) {
  if (seg.key.iv) return Buffer.from(seg.key.iv.replace(/^0x/i, '').padStart(32, '0'), 'hex');
  const iv = Buffer.alloc(16);
  iv.writeUInt32BE(Math.floor(seg.seq / 2 ** 32) >>> 0, 8);
  iv.writeUInt32BE(seg.seq >>> 0, 12);
  return iv;
}

function assertSupported(segments) {
  for (const s of segments) {
    const k = s.key;
    if (!k) continue;
    if (k.method !== 'AES-128' || (k.format && k.format !== 'identity')) {
      throw new FatalError('This stream uses DRM or an unsupported encryption scheme and cannot be downloaded', 'DRM');
    }
  }
}

/** Download all segments of one media playlist and concatenate them into `dest`. */
async function downloadMedia(task, ctx, media, dest, label) {
  const rec = task.rec;
  const { segments } = media;
  assertSupported(segments);
  const workDir = dest + '.parts';
  await fsp.mkdir(workDir, { recursive: true });
  const keyCache = new Map();
  const fetchKey = async (uri) => {
    if (!keyCache.has(uri)) {
      keyCache.set(
        uri,
        (async () => {
          const { res } = await fetchFollow(uri, { headers: ctx.headers, signal: ctx.signal, dispatcher: ctx.dispatcher });
          if (res.statusCode !== 200) throw new HttpError(res.statusCode, `Could not fetch the decryption key (HTTP ${res.statusCode})`);
          return Buffer.from(await res.body.arrayBuffer());
        })(),
      );
    }
    return keyCache.get(uri);
  };

  const download = async (uri, range) => {
    const headers = { ...ctx.headers, 'accept-encoding': 'identity' };
    if (range) headers.range = `bytes=${range.start}-${range.end}`;
    const { res } = await fetchFollow(uri, { headers, signal: ctx.signal, dispatcher: ctx.dispatcher });
    if (res.statusCode !== 200 && res.statusCode !== 206) {
      await res.body.dump({ limit: 65536 }).catch(() => {});
      const e = new HttpError(res.statusCode);
      if (![408, 425, 429, 500, 502, 503, 504].includes(res.statusCode)) e.fatal = true;
      throw e;
    }
    const parts = [];
    for await (const chunk of res.body) {
      await task.throttle.take(chunk.length, ctx.signal);
      await task.limiter.take(chunk.length, ctx.signal);
      parts.push(chunk);
      task.addBytes(chunk.length);
    }
    return Buffer.concat(parts);
  };

  const withRetry = async (fn) => {
    for (let attempt = 1; ; attempt++) {
      try {
        return await fn();
      } catch (e) {
        if (ctx.signal.aborted || e.fatal || attempt >= SEGMENT_RETRIES) throw e;
        await sleep(300 * 2 ** (attempt - 1), ctx.signal);
      }
    }
  };

  // Unique init-segments (fMP4) come first.
  const maps = [];
  for (const s of segments) if (s.map && !maps.find((m) => m.uri === s.map.uri)) maps.push(s.map);
  const jobs = [
    ...maps.map((m, i) => ({ file: `init${i}`, uri: m.uri, range: m.byterange ? (() => { const [l, o = 0] = m.byterange.split('@').map(Number); return { start: o, end: o + l - 1 }; })() : null, key: null })),
    ...segments.map((s, i) => ({ file: String(i).padStart(6, '0'), uri: s.uri, range: s.range, key: s.key, seg: s })),
  ];

  let doneCount = 0;
  let bytes = 0;
  const concurrency = Math.max(1, Math.min(32, rec.connections || task.settings.maxConnections));
  let next = 0;
  const update = () => {
    rec.downloaded = bytes;
    rec.total = doneCount ? Math.round((bytes / doneCount) * jobs.length) : -1;
  };

  // Resume: finished pieces are on disk already.
  for (const j of jobs) {
    const st = await fsp.stat(path.join(workDir, j.file + '.seg')).catch(() => null);
    if (st) {
      j.done = true;
      doneCount++;
      bytes += st.size;
    }
  }
  update();

  let failure = null;
  const worker = async () => {
    while (!failure) {
      const j = jobs[next++];
      if (!j) return;
      if (j.done) continue;
      try {
        let data = await withRetry(() => download(j.uri, j.range));
        if (j.key) {
          const key = await fetchKey(j.key.uri);
          const d = crypto.createDecipheriv('aes-128-cbc', key, ivFor(j.seg));
          data = Buffer.concat([d.update(data), d.final()]);
        }
        const tmp = path.join(workDir, j.file + '.tmp');
        await fsp.writeFile(tmp, data);
        await fsp.rename(tmp, path.join(workDir, j.file + '.seg'));
        doneCount++;
        bytes += data.length;
        update();
      } catch (e) {
        failure = failure || e;
        return;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, worker));
  if (failure) throw failure;
  if (ctx.signal.aborted) throw abortError();

  task.setState('merging');
  const out = await fsp.open(dest, 'w');
  try {
    for (const j of jobs) {
      const buf = await fsp.readFile(path.join(workDir, j.file + '.seg'));
      await out.write(buf);
    }
  } finally {
    await out.close();
  }
  await fsp.rm(workDir, { recursive: true, force: true });
  return label;
}

async function run(task) {
  const rec = task.rec;
  const ctx = { headers: { ...task.baseHeaders() }, signal: task.signal, dispatcher: task.dispatcher };

  const capturedManifest = rec.hls && rec.hls.manifestBody;
  let { text, url } = isManifestBody(capturedManifest)
    ? { text: capturedManifest, url: rec.url }
    : await fetchText(ctx, rec.url);
  let audioUrl = rec.hls && rec.hls.audioUrl ? rec.hls.audioUrl : null;
  if (/#EXT-X-STREAM-INF/.test(text)) {
    const master = parseMaster(text, url);
    if (!master.variants.length) throw new Error('The playlist lists no playable streams');
    const chosen = pickVariant(master.variants, rec.hls && rec.hls.variantUrl);
    if (!audioUrl && chosen.audio && master.audio[chosen.audio]) audioUrl = master.audio[chosen.audio].uri;
    ({ text, url } = await fetchText(ctx, chosen.uri));
  }
  const media = parseMedia(text, url);
  if (!media.segments.length) throw new Error('The playlist contains no segments');
  if (!media.endList) throw new FatalError('Live streams are not supported', 'LIVE');
  assertSupported(media.segments);

  const hasMap = media.segments.some((s) => s.map);
  const ffmpeg = require('./tools').findFfmpeg(task.settings, task.ctx.binDir);
  const finalExt = ffmpeg || hasMap ? '.mp4' : '.ts';

  // Name the output file once.
  if (!rec.savePath) {
    let base = rec.fileNameLocked && rec.fileName ? names.sanitize(rec.fileName) : names.sanitize(rec.pageTitle || names.fromUrl(rec.url) || 'video');
    base = base.replace(/\.(m3u8?|mpd|ts|mp4)$/i, '');
    const name = base + finalExt;
    rec.category = rec.category || 'Video';
    if (rec.autoDir) {
      rec.dir = task.settings.categorize ? path.join(task.settings.downloadDir, 'Video') : task.settings.downloadDir;
    }
    await fsp.mkdir(rec.dir, { recursive: true });
    rec.savePath = await names.uniquePath(rec.dir, name);
    rec.fileName = path.basename(rec.savePath);
    rec.partPath = rec.savePath + '.part';
  }

  task.setState('downloading');
  const raw = rec.partPath + '.video';
  await downloadMedia(task, ctx, media, raw, 'video');

  let audioFile = null;
  if (audioUrl) {
    if (!ffmpeg) {
      rec.warning = 'The stream has a separate audio track. Install ffmpeg (set its path in Options) to merge it.';
    } else {
      const a = await fetchText(ctx, audioUrl);
      const audioMedia = parseMedia(a.text, a.url);
      if (audioMedia.endList && audioMedia.segments.length) {
        audioFile = rec.savePath + '.audio.part';
        await downloadMedia(task, ctx, audioMedia, audioFile, 'audio');
      }
    }
  }

  task.setState('merging');
  if (ffmpeg && (!hasMap || audioFile)) {
    const args = ['-i', raw];
    if (audioFile) args.push('-i', audioFile);
    args.push('-c', 'copy', '-f', 'mp4', rec.partPath);
    try {
      await runFfmpeg(ffmpeg, args, task.signal);
      await fsp.unlink(raw).catch(() => {});
    } catch (e) {
      if (task.signal.aborted) throw e;
      // Keep the downloaded stream as it is rather than failing a finished download.
      await fsp.rename(raw, rec.partPath);
      rec.warning = 'ffmpeg could not convert the stream to MP4, so it was saved as a raw .ts file.';
      if (!hasMap) rec.savePath = await names.uniquePath(rec.dir, path.basename(rec.savePath).replace(/\.mp4$/i, '.ts'));
      rec.fileName = path.basename(rec.savePath);
    }
    if (audioFile) await fsp.unlink(audioFile).catch(() => {});
  } else {
    await fsp.rename(raw, rec.partPath);
  }
  const st = await fsp.stat(rec.partPath);
  rec.total = rec.downloaded = st.size;
  if (await exists(rec.savePath)) {
    rec.savePath = await names.uniquePath(rec.dir, rec.fileName);
    rec.fileName = path.basename(rec.savePath);
  }
  await fsp.rename(rec.partPath, rec.savePath);
  rec.partPath = '';
}

module.exports = { run, listVariants, parseMaster, parseMedia };
