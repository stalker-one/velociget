'use strict';
const http = require('http');
const crypto = require('crypto');
const hls = require('./hls');
const { probe } = require('./download-task');
const ytdlp = require('./ytdlp');
const tools = require('./tools');
const { normalizeHeaders, dispatcherFor } = require('./http');

const EXTENSION_ORIGIN = /^(chrome|moz|safari-web)-extension:\/\/[a-z0-9-]+$/i;
const LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\]):\d+$/i;
const MAX_BODY = 2 * 1024 * 1024;

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const parts = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('Request too large'), { status: 413 }));
        req.destroy();
      } else parts.push(c);
    });
    req.on('end', () => {
      try {
        resolve(parts.length ? JSON.parse(Buffer.concat(parts).toString('utf8')) : {});
      } catch {
        reject(Object.assign(new Error('Invalid JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function pick(o, keys) {
  const out = {};
  for (const k of keys) if (o[k] !== undefined) out[k] = o[k];
  return out;
}

/**
 * Local API used by the browser extension.
 *
 * Security model: bound to 127.0.0.1, requires a bearer token (shown in the app's Options and
 * pasted once into the extension), accepts browser requests only from extension origins and
 * only for a localhost Host header. A random web page therefore can neither call it (origin
 * check, token) nor reach it through DNS rebinding (host check).
 */
function createApiServer(manager, { onFocus } = {}) {
  const handlers = {
    'GET /api/ping': async () => ({ app: 'velociget', version: 1 }),

    'GET /api/status': async () => {
      const list = manager.list();
      return {
        active: list.filter((d) => ['probing', 'downloading', 'merging'].includes(d.state)).length,
        speed: list.reduce((n, d) => n + (d.speed || 0), 0),
        settings: pick(manager.getSettings(), ['captureEnabled', 'captureExtensions', 'minCaptureSize', 'excludedSites', 'excludedAddresses', 'showVideoOverlay', 'showFileInfoDialog']),
      };
    },

    'POST /api/add': async (body) => {
      const spec = pick(body, ['url', 'referer', 'cookie', 'cookieList', 'userAgent', 'headers', 'fileName', 'pageTitle', 'kind', 'hls', 'ytdlp', 'ask', 'startNow', 'queueId', 'connections']);
      return manager.add(spec);
    },

    'POST /api/add-many': async (body) => {
      const items = Array.isArray(body.items) ? body.items.slice(0, 500) : [];
      const results = [];
      for (const it of items) {
        try {
          results.push(manager.add(pick(it, ['url', 'referer', 'cookie', 'cookieList', 'userAgent', 'headers', 'fileName', 'pageTitle', 'kind', 'hls', 'ytdlp', 'startNow', 'queueId'])));
        } catch (e) {
          results.push({ error: e.message });
        }
      }
      return { results };
    },

    // Quality list for HLS master playlists / basic info for plain files.
    'POST /api/thumbnail': async (body) => {
      const url = String(body.url || '');
      if (!/^https?:\/\//i.test(url)) throw new Error('Bad URL');
      const ff = tools.findFfmpeg(manager.getSettings(), manager.binDir);
      if (!ff) return { error: 'ffmpeg is not installed' };
      let b = await tools.grabFrame(ff, url, { 'User-Agent': body.userAgent, Referer: body.referer, Cookie: body.cookie }, 4);
      if (!b) b = await tools.grabFrame(ff, url, { 'User-Agent': body.userAgent, Referer: body.referer, Cookie: body.cookie }, 0); // very short clips
      return b ? { dataUrl: 'data:image/jpeg;base64,' + b.toString('base64') } : { error: 'No frame' };
    },
    // Everything about a link in one answer: title, caption text, uploader, counts, caption tracks, every quality and the thumbnail.
    'POST /api/link-details': async (body) => {
      const url = String(body.url || '').trim();
      if (!/^https?:\/\//i.test(url)) throw Object.assign(new Error('Enter a full address starting with http:// or https://'), { status: 400 });
      const env = { settings: manager.getSettings(), binDir: manager.binDir };
      if (!tools.findYtdlp(env.settings, env.binDir)) return { error: 'The site video engine is not installed yet. Open IDM By Encodal → Options → Site videos.' };
      let info;
      try { info = await ytdlp.probe(env, { url }); } catch (e) { return { error: e.message }; }
      const { streams, ...pub } = info;
      let thumbData = '';
      if (/^https?:\/\//i.test(info.thumbnail || '')) {
        try {
          const ac = new AbortController();
          const t = setTimeout(() => ac.abort(), 8000);
          const r = await fetch(info.thumbnail, { signal: ac.signal, headers: { 'user-agent': 'Mozilla/5.0', referer: url } });
          const type = String(r.headers.get('content-type') || '').split(';')[0];
          const buf = Buffer.from(await r.arrayBuffer());
          clearTimeout(t);
          if (r.ok && /^image\//.test(type) && buf.length < 4e6) thumbData = `data:${type};base64,${buf.toString('base64')}`;
        } catch { /* the thumbnail is optional */ }
      }
      return { kind: 'site', ...pub, thumbData };
    },
    // A real frame of the video (grabbed from one of its own streams), shown beside the thumbnail.
    'POST /api/link-frame': async (body) => {
      const url = String(body.url || '');
      const last = ytdlp.lastProbe(url);
      const ff = tools.findFfmpeg(manager.getSettings(), manager.binDir);
      if (!last || !ff) return { error: 'No frame available' };
      const vids = (last.streams || []).filter((f) => f.url && f.vcodec && f.vcodec !== 'none' && !f.fragments && /^https?/i.test(f.protocol || 'https'));
      const pool = vids.filter((f) => f.height >= 240).sort((a, b) => a.height - b.height);
      const f = pool[0] || vids[0];
      if (!f) return { error: 'No frame available' };
      const at = Math.max(0, Math.min(30, Math.floor((last.duration || 20) * 0.1)));
      let b = await tools.grabFrame(ff, f.url, f.http_headers || {}, at, 640);
      if (!b) b = await tools.grabFrame(ff, f.url, f.http_headers || {}, 0, 640);
      return b ? { dataUrl: 'data:image/jpeg;base64,' + b.toString('base64') } : { error: 'No frame available' };
    },
    'POST /api/probe-media': async (body) => {
      const headers = normalizeHeaders({ 'user-agent': body.userAgent, referer: body.referer, cookie: body.cookie });
      const url = String(body.url || '');
      if (body.kind === 'site') {
        if (!/^https?:\/\//i.test(url)) throw Object.assign(new Error('Invalid URL'), { status: 400 });
        const env = { settings: manager.getSettings(), binDir: manager.binDir };
        if (!tools.findYtdlp(env.settings, env.binDir)) return { kind: 'site', missing: true, error: 'The site video engine is not installed yet. Open IDM By Encodal → Options → Site videos.' };
        try {
          const info = await ytdlp.probe(env, { url, referer: body.referer, userAgent: body.userAgent, cookieList: Array.isArray(body.cookieList) ? body.cookieList.slice(0, 300) : null });
          const { streams, ...pub } = info; // the addresses stay in the app: they are what the download uses
          return { kind: 'site', ...pub };
        } catch (e) {
          return { kind: 'site', error: e.message };
        }
      }
      if (/\.m3u8(\?|$)/i.test(url) || body.kind === 'hls') {
        const v = await hls.listVariants(url, headers, undefined, body.manifestBody);
        return { kind: 'hls', ...v };
      }
      const info = await probe(url, headers, undefined, dispatcherFor(manager.getSettings().proxy));
      const out = { kind: 'http', size: info.total, resumable: info.resumable, fileName: info.fileName, contentType: info.contentType };
      // Resolution / duration / bitrate (like IDM's "quality 720p HD, 781 kbps") when ffprobe is available.
      if (body.details && /video|audio|mp4|octet|mpeg|matroska|webm/i.test(info.contentType + ' ' + info.fileName)) {
        const fp = tools.findFfprobe(manager.getSettings(), manager.binDir);
        if (fp) {
          const m = await tools.probeMedia(fp, info.finalUrl || url, { 'User-Agent': body.userAgent, Referer: body.referer, Cookie: body.cookie });
          if (m) Object.assign(out, m);
        }
      }
      return out;
    },

    'POST /api/focus': async () => {
      onFocus?.();
      return { ok: true };
    },
  };

  const server = http.createServer(async (req, res) => {
    const send = (status, obj) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(obj));
    };
    try {
      if (!LOCAL_HOST.test(req.headers.host || '')) return send(403, { error: 'Forbidden host' });
      const origin = req.headers.origin;
      if (origin) {
        if (!EXTENSION_ORIGIN.test(origin)) return send(403, { error: 'Forbidden origin' });
        res.setHeader('Access-Control-Allow-Origin', origin);
        res.setHeader('Vary', 'Origin');
        res.setHeader('Access-Control-Allow-Headers', 'authorization, content-type');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Max-Age', '600');
      }
      if (req.method === 'OPTIONS') {
        res.writeHead(204);
        return res.end();
      }
      const route = `${req.method} ${new URL(req.url, 'http://localhost').pathname}`;
      const handler = handlers[route];
      if (!handler) return send(404, { error: 'Not found' });
      if (route !== 'GET /api/ping') {
        const auth = req.headers.authorization || '';
        const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
        if (!token || !safeEqual(token, manager.getSettings().apiToken)) return send(401, { error: 'Invalid token' });
      }
      const body = req.method === 'POST' ? await readJson(req) : {};
      return send(200, await handler(body));
    } catch (e) {
      send(e.status || 500, { error: e.message || 'Server error' });
    }
  });

  return {
    /** In-app use (the app's own windows): same handlers, no token needed. */
    call: (route, body) => { const h = handlers[route]; if (!h) throw new Error('Unknown call'); return h(body || {}); },
    server,
    listen(port = manager.getSettings().apiPort) {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '127.0.0.1', () => resolve(server.address().port));
      });
    },
    close: () => new Promise((r) => server.close(r)),
  };
}

module.exports = { createApiServer };
