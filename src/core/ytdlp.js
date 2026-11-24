'use strict';
/**
 * Site video extractor, backed by yt-dlp. Used for pages where the video is not a plain file:
 * YouTube, Vimeo, and any player that feeds a blob: URL through Media Source Extensions.
 * yt-dlp does the site-specific work (which changes constantly); this module handles quality
 * listing, progress, pause/resume, cancel and moving the finished file into place.
 */
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const tools = require('./tools');
const names = require('./filename');
const { FatalError, abortError } = require('./util');

/** Hosts we send to the extractor without trying a plain HTTP download first. */
const SITE_HOSTS = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|twitch\.tv|tiktok\.com|instagram\.com|facebook\.com|fb\.watch|twitter\.com|x\.com|reddit\.com|soundcloud\.com|bilibili\.com|streamable\.com)$/i;

function isSiteUrl(url) {
  try {
    return SITE_HOSTS.test(new URL(url).hostname);
  } catch {
    return false;
  }
}

function proxyUrl(p) {
  if (!p || !p.host || !p.port) return null;
  const auth = p.user ? `${encodeURIComponent(p.user)}:${encodeURIComponent(p.pass || '')}@` : '';
  return `http://${auth}${p.host}:${p.port}`;
}

/** Netscape cookie file, the format yt-dlp reads. */
function cookieFileText(list) {
  const lines = ['# Netscape HTTP Cookie File'];
  for (const c of list || []) {
    if (!c || !c.name || !c.domain) continue;
    const sub = c.domain.startsWith('.') ? 'TRUE' : 'FALSE';
    const exp = c.expirationDate ? Math.floor(c.expirationDate) : 0;
    const clean = (s) => String(s).replace(/[\t\r\n]/g, '');
    lines.push([clean(c.domain), sub, clean(c.path || '/'), c.secure ? 'TRUE' : 'FALSE', exp, clean(c.name), clean(c.value)].join('\t'));
  }
  return lines.join('\n') + '\n';
}

function supportsRemoteComponents(bin) {
  const m = bin && /^(\d{4})\.(\d{2})\.(\d{2})/.exec(bin.version || '');
  return !!m && Number(m[1] + m[2] + m[3]) >= 20251112;
}

function commonArgs(env, spec, cookiePath) {
  const { settings, binDir } = env;
  const a = ['--ignore-config', '--no-playlist', '--no-colors', '--newline', '--retries', '10', '--fragment-retries', '10', '--socket-timeout', '30'];
  const px = proxyUrl(settings.proxy);
  if (px) a.push('--proxy', px);
  // YouTube is picky: yt-dlp must use its own client identity (a browser User-Agent / Referer makes it answer with
  // thumbnails only) and exported cookies can make it worse. For YouTube we start clean and add cookies only on demand.
  const yt = SITE_HOSTS_YT.test(spec.url || '');
  const mode = spec.mode || (yt ? 'clean' : 'cookies');
  const ua = spec.userAgent || settings.userAgent;
  if (!yt && mode === 'impersonate') a.push('--impersonate', 'chrome'); // TLS/browser fingerprint of Chrome (needs curl_cffi in the yt-dlp build)
  else if (!yt) {
    if (ua) a.push('--user-agent', ua);
    if (spec.referer) a.push('--referer', spec.referer);
  } else if (settings.userAgent) a.push('--user-agent', settings.userAgent);
  if (cookiePath && (!yt || mode === 'cookies')) a.push('--cookies', cookiePath);
  const ff = tools.findFfmpeg(settings, binDir);
  if (ff && ff !== 'ffmpeg') a.push('--ffmpeg-location', ff);
  // YouTube needs a JavaScript runtime to solve its player challenge; without one only thumbnails are offered.
  const deno = tools.findDeno(settings, binDir);
  if (deno) a.push('--js-runtimes', deno === 'deno' ? 'deno' : `deno:${deno}`);
  else if (tools.findNode()) a.push('--js-runtimes', 'node');
  // yt-dlp >= 2025.11.12 may fetch its challenge-solver script (yt-dlp-ejs) on demand when it is not bundled.
  if (supportsRemoteComponents(tools.findYtdlp(settings, binDir))) a.push('--remote-components', 'ejs:github');
  // Second attempt for YouTube: a player client that needs neither the JS challenge nor a PO token.
  if (yt && mode === 'fallback') a.push('--extractor-args', 'youtube:player_client=android_vr,web_safari');
  if (yt && mode === 'alt') a.push('--extractor-args', 'youtube:player_client=tv,mweb,web_embedded');
  return { args: a, ffmpeg: !!ff, jsRuntime: !!(deno || tools.findNode()) };
}

function run(bin, args, { signal, timeoutMs = 0, onLine } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(bin, args, { windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    let buf = '';
    const feed = (chunk) => {
      buf += chunk;
      let i;
      while ((i = buf.search(/\r?\n/)) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(buf[i] === '\r' && buf[i + 1] === '\n' ? i + 2 : i + 1);
        onLine && onLine(line);
      }
    };
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (d) => {
      if (onLine) feed(d);
      else out += d;
    });
    child.stderr.on('data', (d) => {
      err += d;
      if (err.length > 20000) err = err.slice(-10000);
    });
    const kill = () => tools.killTree(child);
    const timer = timeoutMs ? setTimeout(kill, timeoutMs) : null;
    const onAbort = () => kill();
    if (signal) {
      if (signal.aborted) kill();
      else signal.addEventListener('abort', onAbort, { once: true });
    }
    child.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      signal && signal.removeEventListener('abort', onAbort);
      if (signal && signal.aborted) return reject(abortError());
      resolve({ code, out, err });
    });
  });
}

const RUNTIME_HINT = /javascript runtime|challenge solving|n challenge|jsc|signature solving/i;
const RUNTIME_MSG = 'YouTube needs the Deno helper to unlock video streams. Open IDM By Encodal → Options → Site videos → Install Deno, then try again.';
const SIGNIN_MSG = 'YouTube asked for a sign-in check before it hands out the streams. Open the video in the browser while you are signed in (with the IDM By Encodal extension), then start the download again.';
const RELOAD_MSG = 'The site asked for the page to be reloaded. Open the video again in the browser and start the download once more.';

/** Turn yt-dlp's stderr into one readable sentence. */
function explainError(err, code, ctx = {}) {
  const text = String(err);
  const lines = text.split(/\r?\n/).filter(Boolean);
  const e = [...lines].reverse().find((l) => /^ERROR:/i.test(l)) || lines[lines.length - 1] || `yt-dlp exited with code ${code}`;
  let msg = e.replace(/^ERROR:\s*(\[[^\]]+\]\s*)?/i, '').replace(/^[\w-]{6,}:\s*/, '').trim();
  // The site refused to hand out the streams. These two reasons need a different reaction than a missing format.
  if (/sign in to confirm|not a bot|login required/i.test(text)) return SIGNIN_MSG;
  if (/the page needs to be reloaded/i.test(text)) return RELOAD_MSG;
  if (/requested format is not available|only images are available|no video formats/i.test(msg)) {
    // The video itself is fine - it was listed a moment ago - but this quality is not on offer right now.
    if (ctx.picked) {
      return `The site does not offer the ${ctx.picked} stream for this video right now. The download was stopped instead of saving a different quality - try again in a few minutes, or pick another quality.`;
    }
    const tail = [...new Set(lines.filter((l) => /^(ERROR|WARNING):|\[youtube\]|\[jsc|deno|ejs/i.test(l)))]
      .map((l) => l.replace(/^ERROR:\s*/i, '').replace(/^WARNING:\s*/i, '').trim())
      .filter((l) => l && !/^Use --list-formats/i.test(l))
      .slice(-2).join(' · ').replace(/\s+/g, ' ').slice(0, 240) + (ctx.logFile ? ` (full log: ${ctx.logFile})` : '');
    return RUNTIME_HINT.test(text)
      ? (ctx.jsRuntime
        ? `YouTube did not hand out video streams for this page (tried without cookies, with your browser's cookies and with other player types).${tail ? ' Details: ' + tail : ''}`
        : RUNTIME_MSG)
      : 'The site did not offer a downloadable format for this video. Update yt-dlp (Options → Site videos), and if the video needs a login, open it while signed in.' + (tail ? ' (' + tail + ')' : '');
  }
  if (/sign in to confirm|not a bot|cookies/i.test(msg)) msg += ' (open the video while signed in, in the browser with the IDM By Encodal extension)';
  if (/unsupported url/i.test(msg)) msg = 'No downloadable video was found on this page yet. Play the video for a few seconds and try again.';
  if (/ffmpeg|ffprobe/i.test(msg) && /not (found|installed)/i.test(msg)) msg += ' — install ffmpeg from Options';
  if (/drm/i.test(msg)) msg = 'This video is protected by DRM and cannot be downloaded.';
  return msg;
}

const hasV = (f) => f.vcodec && f.vcodec !== 'none';
const hasA = (f) => f.acodec && f.acodec !== 'none';
// YouTube often reports no size for VP9/AV1 streams: estimate from the bitrate and the duration so no row shows a wrong (audio-only) size.
let curDuration = 0;
// Sizes are the server's own numbers (checked per stream, see dropRefused). A bitrate x duration estimate was tried and removed: it was several times too large.
// googlevideo addresses carry the exact byte length of the file (clen=...): use it when the engine reports no size (common for VP9/AV1).
const clenOf = (f) => { const m = /[?&]clen=(\d+)/.exec(f.url || ''); return m ? Number(m[1]) : 0; };
const sizeOf = (f) => Number(f.filesize || clenOf(f) || f.filesize_approx || 0) || 0;
const premium = (f) => /premium/i.test(f.format_note || '') || /premium/i.test(f.format || '');

const fam = (f) => (/^avc1|h264/i.test(f.vcodec || '') ? 'h264' : /^vp0?9/i.test(f.vcodec || '') ? 'vp9' : /^av0?1/i.test(f.vcodec || '') ? 'av1' : (f.vcodec || '').split('.')[0] || 'video');
const FAM_LABEL = { h264: 'H.264', vp9: 'VP9', av1: 'AV1' };
const FAM_RANK = { h264: 0, av1: 1, vp9: 2 };

/** One stream exactly as the site offered it, in the shape the downloader needs. */
const streamOf = (f) => ({
  format_id: String(f.format_id || ''),
  id: String(f.format_id || ''),
  url: f.url || '',
  ext: f.ext || '',
  vcodec: f.vcodec || '',
  acodec: f.acodec || '',
  height: f.height || 0,
  fps: Math.round(f.fps || 0) > 30 ? Math.round(f.fps) : 0,
  protocol: f.protocol || '',
  filesize: Number(f.filesize || clenOf(f) || 0),
  filesize_approx: Number(f.filesize_approx || 0),
  tbr: Number(f.tbr || 0),
  http_headers: f.http_headers || {},
  fragments: !!f.fragments,
  fam: fam(f),
});

/** A plain file the downloader can fetch directly (no HLS fragments, no manifest). */
const isPlainStream = (f) => !!(f && f.url && !f.fragments && /^https?:\/\//i.test(f.url) && (!f.protocol || /^https?$/.test(f.protocol)));

/**
 * A short, readable reason for a notice. The raw engine output stays in the log; the user gets one clause.
 */
function shortWhy(why) {
  const s = String(why || '');
  if (!s) return 'the site did not hand out the stream';
  if (/sign in to confirm|not a bot|login required/i.test(s)) return 'the site asked for a sign-in check';
  if (/the page needs to be reloaded/i.test(s)) return 'the site asked for the page to be reloaded';
  if (/not offered by this player type/i.test(s)) return 'the site did not offer the picked streams any more';
  if (/requested format is not available|no video formats|only images/i.test(s)) return 'the site did not offer that stream any more';
  if (/segmented|fragment/i.test(s)) return 'the stream is delivered in fragments';
  if (/could not list the streams/i.test(s)) return 'the site did not list the streams';
  if (/unreadable answer/i.test(s)) return 'the site gave an unreadable answer';
  return s.replace(/\s*\(.*\)\s*$/, '').slice(0, 140);
}

/**
 * Condense yt-dlp's format list into one entry per (resolution, frame rate, codec), best first, plus audio-only.
 * Every entry carries the exact format ids so what is downloaded is what was listed (same codec, same size).
 */
/** The descriptive part of a look-up: caption text, uploader, date, counts and the caption (subtitle) tracks. */
function metaOf(info) {
  const pickTrack = (arr) => (Array.isArray(arr) ? arr.find((x) => x.ext === 'srt' && x.url) || arr.find((x) => x.ext === 'vtt' && x.url) || arr.find((x) => x.url) : null);
  const captions = [];
  for (const [lang, arr] of Object.entries(info.subtitles || {})) {
    const t = pickTrack(arr);
    if (t) captions.push({ lang, name: t.name || lang, ext: t.ext || 'vtt', url: t.url, auto: false });
  }
  const have = new Set(captions.map((c) => c.lang));
  let auto = 0;
  for (const [lang, arr] of Object.entries(info.automatic_captions || {})) {
    if (auto >= 12 || have.has(lang)) continue;
    const t = pickTrack(arr);
    if (t) { captions.push({ lang, name: t.name || lang, ext: t.ext || 'vtt', url: t.url, auto: true }); auto++; }
  }
  const d = String(info.upload_date || '');
  return {
    description: String(info.description || '').slice(0, 4000),
    platform: info.extractor_key || info.extractor || '',
    pageUrl: info.webpage_url || info.original_url || '',
    uploader: info.uploader || info.channel || info.uploader_id || '',
    uploadDate: /^\d{8}$/.test(d) ? `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6)}` : '',
    views: Number(info.view_count) || 0,
    likes: Number(info.like_count) || 0,
    comments: Number(info.comment_count) || 0,
    width: Number(info.width) || 0,
    height: Number(info.height) || 0,
    tags: Array.isArray(info.tags) ? info.tags.slice(0, 15) : [],
    live: !!info.is_live,
    captions,
  };
}

function summarize(info, hasFfmpeg) {
  curDuration = Number(info.duration) || 0;
  const all = info.formats && info.formats.length ? info.formats : [info];
  // YouTube lists streams that cannot actually be downloaded (they need a token the page did not hand out: no address). Only offer what can be fetched.
  const hasAddr = (f) => !!(f.url || f.manifest_url || f.fragments);
  const formats = all.some(hasAddr) ? all.filter(hasAddr) : all;
  const topOf = (list) => Math.max(0, ...list.filter((f) => hasV(f) && f.height).map((f) => f.height));
  const hiddenHeight = Math.max(0, topOf(all) - topOf(formats));
  const audio = formats.filter((f) => hasA(f) && !hasV(f)).sort((a, b) => (b.abr || 0) - (a.abr || 0) || sizeOf(b) - sizeOf(a));
  const bestAudio = audio.find((f) => f.ext === 'm4a') || audio[0];
  const bestOpus = audio.find((f) => /opus/i.test(f.acodec || '')) || bestAudio;
  const groups = new Map();
  for (const f of formats) {
    if (!hasV(f) || !f.height) continue;
    const fps = Math.round(f.fps || 0) > 30 ? Math.round(f.fps) : 0; // 30 fps and below is the unmarked default
    const key = `${f.height}|${fps}|${fam(f)}|${premium(f) ? 'p' : ''}`;
    const cur = groups.get(key);
    if (!cur || (f.ext === 'mp4' && cur.ext !== 'mp4') || (f.ext === cur.ext && sizeOf(f) > sizeOf(cur))) groups.set(key, f);
  }
  const options = [...groups.values()]
    .sort((a, b) => b.height - a.height || (Math.round(b.fps || 0) > 30 ? Math.round(b.fps) : 0) - (Math.round(a.fps || 0) > 30 ? Math.round(a.fps) : 0) || (FAM_RANK[fam(a)] ?? 9) - (FAM_RANK[fam(b)] ?? 9))
    .map((f) => {
      const merge = !hasA(f);
      const a = merge ? (fam(f) === 'vp9' ? bestOpus : bestAudio) : null;
      const size = sizeOf(f) + (a ? sizeOf(a) : 0);
      const fps = Math.round(f.fps || 0) > 30 ? Math.round(f.fps) : 0;
      const ext = merge ? (fam(f) === 'vp9' || f.ext === 'webm' ? 'mkv' : 'mp4') : f.ext || 'mp4';
      const codec = FAM_LABEL[fam(f)] || fam(f);
      const acodec = merge ? (a && /opus/i.test(a.acodec || '') ? 'Opus' : 'AAC') : /opus/i.test(f.acodec || '') ? 'Opus' : 'AAC';
      return {
        height: f.height, fps, label: `${f.height}p${fps ? fps : ''}${premium(f) ? ' Premium' : ''}`, codec, detail: `${codec} + ${acodec}`, ext, size, needsMerge: merge, available: !merge || hasFfmpeg,
        formatId: String(f.format_id || ''), audioId: a ? String(a.format_id || '') : '',
      };
    });
  // Without ffmpeg only combined streams can be saved; mark the rest so the UI can explain.
  return {
    title: info.title || '',
    duration: info.duration || 0,
    thumbnail: info.thumbnail || '',
    uploader: info.uploader || info.channel || '',
    meta: metaOf(info),
    options,
    audio: bestAudio ? { label: `Audio only (${bestAudio.ext || 'm4a'})`, ext: bestAudio.ext || 'm4a', size: sizeOf(bestAudio), formatId: String(bestAudio.format_id || '') } : null,
    audios: audio.slice(0, 8).map((f) => ({ label: `Audio only (${f.ext || 'm4a'}, ${/opus/i.test(f.acodec || '') ? 'Opus' : 'AAC'}${f.abr ? ' ' + Math.round(f.abr) + 'k' : ''})`, ext: f.ext || 'm4a', size: sizeOf(f), formatId: String(f.format_id || '') })),
    hasFfmpeg,
    hiddenHeight,
    // the streams behind the list, with the addresses the site handed out: the download uses these
    // so the file that arrives is the one that was offered and shown
    streams: formats.map(streamOf),
  };
}

/**
 * The streams that belong to the picked quality: the exact ids when they are still listed, otherwise
 * the ones with the same quality (same resolution, frame rate and codec) under a different id.
 * The ids of a video can change between two look-ups, the quality does not.
 */
function pickStreams(info, req) {
  const list = info && info.streams;
  if (!list || !list.length) return null;
  const byId = (id) => (id ? list.find((f) => String(f.id) === String(id)) || null : null);
  if (req && req.audio) {
    const a = byId(req.aid) || list.find((f) => f.acodec && f.acodec !== 'none' && !f.vcodec) || list.find((f) => f.acodec && f.acodec !== 'none') || null;
    return a ? { video: null, audio: a } : null;
  }
  let v = byId(req && req.vid);
  let a = byId(req && req.aid);
  if (!v && req && req.height) {
    const same = list.filter((f) => f.vcodec && f.vcodec !== 'none' && Number(f.height || 0) === Number(req.height));
    v = same.find((f) => req.fps && Number(f.fps || 0) === Number(req.fps)) || same[0] || null;
  }
  if (!a) a = list.find((f) => f.acodec && f.acodec !== 'none' && !f.vcodec && f.ext === 'm4a') || list.find((f) => f.acodec && f.acodec !== 'none' && !f.vcodec) || null;
  if (!v && !a) return null;
  return { video: v || null, audio: a || null };
}

const SITE_HOSTS_YT = /^https?:\/\/([\w-]+\.)?(youtube\.com|youtu\.be|youtube-nocookie\.com)\//i;
const PROBE_TTL = 30 * 60_000; // the addresses a site hands out stay valid for hours, so the list can be reused
const probeCache = new Map();   // key -> { at, value }
const probeLast = new Map();    // url -> the last list that really worked (kept even after the cache expires)

/** The last list that worked for this page, so a download can reuse the addresses it was shown. */
function lastProbe(url) {
  return probeLast.get(url) || null;
}

/** Keeps the raw yt-dlp output of the last failed look-up so problems can be diagnosed. */
async function writeLog(env, logs) {
  try {
    const dir = path.join(env.binDir, '..', 'logs');
    await fsp.mkdir(dir, { recursive: true });
    const file = path.join(dir, 'ytdlp-last.log');
    await fsp.writeFile(file, logs.join('\n\n'));
    return file;
  } catch {
    return '';
  }
}

/**
 * "Has an address" is not enough: ask each candidate stream for its first byte. Streams the server refuses are dropped from
 * the list, so only qualities that really download are offered. (If nothing at all answers, the network is the problem: keep all.)
 *
 * The requests are made a few at a time: a burst of dozens of range requests looks like an attack to the site and makes
 * the NEXT look-up (the one the download makes) come back thinner - which is how a picked quality used to disappear.
 */
async function dropRefused(info, signal) {
  const formats = (info.formats || []).filter((f) => f.url && /^https?$/.test(f.protocol || '') && !f.fragments && /googlevideo\.com/i.test(f.url));
  if (!formats.length || typeof fetch !== 'function') return;
  const list = formats.slice(0, 80);
  const results = [];
  let next = 0;
  const worker = async () => {
    while (next < list.length) {
      if (signal && signal.aborted) return;
      const f = list[next++];
      const ac = new AbortController();
      const t = setTimeout(() => ac.abort(), 3000);
      const onAbort = () => ac.abort();
      if (signal) signal.addEventListener('abort', onAbort, { once: true });
      try {
        const res = await fetch(f.url, { headers: { ...(f.http_headers || {}), range: 'bytes=0-0' }, signal: ac.signal });
        res.body && res.body.cancel && res.body.cancel().catch(() => {});
        const ok = res.status === 200 || res.status === 206;
        // The real size of the file, from the server itself (Content-Range: bytes 0-0/<total>). The engine's own figure is often
        // missing or an estimate from the nominal bitrate, which can be several times too large.
        const cr = /\/(\d+)\s*$/.exec(res.headers.get('content-range') || '');
        const total = cr ? Number(cr[1]) : res.status === 200 ? Number(res.headers.get('content-length') || 0) : 0;
        const size = total > 0 ? total : clenOf(f);
        if (ok && size > 0) { f.filesize = size; f._exact = true; }
        results.push([f, ok]);
      } catch { results.push([f, null]); } finally { clearTimeout(t); signal && signal.removeEventListener('abort', onAbort); }
    }
  };
  await Promise.all(Array.from({ length: 12 }, worker));
  if (!results.some(([, ok]) => ok === true)) return; // nothing answered (no network from here): do not hide anything
  for (const [f, ok] of results) if (ok === false) { f.url = ''; f._refused = true; }
}

/** List the downloadable qualities of a page. `env` = { settings, binDir }. */
async function probe(env, spec, signal) {
  const bin = tools.findYtdlp(env.settings, env.binDir);
  if (!bin) throw new FatalError('The site video engine (yt-dlp) is not installed. Install it from Options → Site videos.', 'NO_YTDLP');
  const key = spec.url + '|' + (spec.cookieList ? spec.cookieList.length : 0);
  const hit = probeCache.get(key);
  if (hit && Date.now() - hit.at < PROBE_TTL) return hit.value;

  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), 'vg-probe-'));
  try {
    let cookiePath = null;
    if (spec.cookieList && spec.cookieList.length) {
      cookiePath = path.join(tmp, 'cookies.txt');
      await fsp.writeFile(cookiePath, cookieFileText(spec.cookieList));
    }
    const yt = SITE_HOSTS_YT.test(spec.url);
    const modes = yt ? ['clean', ...(cookiePath ? ['cookies'] : []), 'fallback', 'alt'] : ['cookies', 'impersonate'];
    const logs = [];
    const attempt = async (mode) => {
      const { args, ffmpeg, jsRuntime } = commonArgs(env, { ...spec, mode }, cookiePath);
      const r = await run(bin.path, [...args, '-J', '--ignore-no-formats-error', '--no-write-subs', '--no-write-auto-subs', '--socket-timeout', yt ? '10' : '25', '--extractor-retries', '1', ...(yt ? ['--extractor-args', 'youtube:skip=translated_subs'] : []), '--', spec.url], { signal, timeoutMs: 60_000 });
      logs.push(`### attempt: ${mode}  exit=${r.code}\n${r.err}`);
      if (r.code !== 0) return { r, jsRuntime, ffmpeg, mode };
      let info;
      try {
        info = JSON.parse(r.out);
      } catch {
        throw new Error('The site engine returned an unreadable answer');
      }
      if (yt) await dropRefused(info, signal).catch(() => {});
      return { r, jsRuntime, ffmpeg, mode, value: { ...summarize(info, ffmpeg), mode } };
    };
    const empty = (x) => !x.value || (!x.value.options.length && !x.value.audio);
    let res;
    for (const m of modes) {
      if (m === 'impersonate' && !/403|forbidden|cloudflare|challenge|timed out|timeout|curl/i.test(res && res.r ? res.r.err || '' : '')) continue; // only worth a second try when the site refused us
      const x = await attempt(m);
      if (!res) res = x;
      if (!empty(x)) {
        // Better player type = higher top quality that can really be downloaded; keep looking only while higher qualities are being hidden.
        const top = (y) => (y.value && y.value.options[0] ? y.value.options[0].height : 0);
        if (empty(res) || top(x) > top(res)) res = x;
        if (!(x.value.hiddenHeight > 0)) break;
      }
    }
    const logFile = await writeLog(env, logs);
    if (res.r.code !== 0) throw new Error(explainError(res.r.err, res.r.code, { jsRuntime: res.jsRuntime, logFile }));
    const value = res.value;
    if (!value.options.length && !value.audio) {
      throw new Error(explainError('ERROR: Requested format is not available\n' + logs.join('\n'), 1, { jsRuntime: res.jsRuntime, logFile }));
    }
    probeCache.set(key, { at: Date.now(), value });
    probeLast.set(spec.url, value);
    if (probeCache.size > 50) probeCache.delete(probeCache.keys().next().value);
    return value;
  } finally {
    await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
  }
}

/**
 * Format selector for the requested quality, taking the presence of ffmpeg into account.
 * The generic part asks for THAT resolution, never for "this resolution or lower": a fallback that
 * may answer with a smaller picture is how a picked 1080p silently turns into a 360p file.
 */
function selectorFor(req, hasFfmpeg) {
  if (req && req.audio) return req.aid ? `${req.aid}/ba[ext=m4a]/ba/b` : 'ba[ext=m4a]/ba/b';
  const h = req && req.height;
  // exact formats first (what the list showed), generic height-based choices only as a fallback
  const exact = req && req.vid ? (req.aid ? `${req.vid}+${req.aid}/${req.vid}` : req.vid) : '';
  let generic;
  if (!hasFfmpeg) generic = h ? `b[height=${h}]/b` : 'b';
  else generic = h
    ? `bv*[height=${h}][vcodec^=avc1]+ba[ext=m4a]/bv*[height=${h}][ext=mp4]+ba[ext=m4a]/bv*[height=${h}]+ba/b[height=${h}]/b`
    : 'bv*[vcodec^=avc1]+ba[ext=m4a]/bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b';
  return exact ? `${exact}/${generic}` : generic;
}

/** Same choice as selectorFor but only streams that are plain files (YouTube also lists HLS variants with different bitrates). */
function plainSelector(req, hasFfmpeg, fallbackSelector) {
  const h = req && req.height;
  if (!h || (req && req.audio)) return fallbackSelector;
  const P = '[protocol=https]';
  return hasFfmpeg
    ? `bv*[height=${h}][vcodec^=avc1]${P}+ba[ext=m4a]${P}/bv*[height=${h}]${P}+ba${P}/b[height=${h}]${P}`
    : `b[height=${h}]${P}`;
}

const PROGRESS = 'download:VGP\t%(progress.downloaded_bytes)s\t%(progress.total_bytes)s\t%(progress.total_bytes_estimate)s\t%(progress.filename)s';
const num = (s) => {
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? n : 0;
};

/**
 * The same quality as the one that was picked, found in a fresh format list: the video stream with the
 * same resolution, frame rate and codec, plus its audio. Used when the site renumbered the streams
 * between the listing and the download - the quality stays the same, only the id changes.
 */
function matchQuality(list, picked) {
  if (!picked || !picked.video || !Array.isArray(list)) return null;
  const want = picked.video;
  const fpsOf = (f) => (Math.round(f.fps || 0) > 30 ? Math.round(f.fps) : 0);
  const v = list.find((f) => hasV(f)
    && Number(f.height || 0) === Number(want.height || 0)
    && fpsOf(f) === Number(want.fps || 0)
    && fam(f) === (want.fam || fam(want)));
  if (!v) return null;
  const out = [v];
  if (!hasA(v)) {
    const a = (picked.audio && list.find((f) => String(f.format_id) === String(picked.audio.id)))
      || list.find((f) => hasA(f) && !hasV(f) && f.ext === 'm4a')
      || list.find((f) => hasA(f) && !hasV(f));
    if (!a) return null;
    out.push(a);
  }
  return out;
}


/**
 * Multi-connection path for site videos: ask yt-dlp only for the direct stream URLs, download them with our own
 * segmented engine (many connections per file, like the download manager does for any other file), then join
 * video + audio with ffmpeg. Returns false when the site only offers fragmented streams or anything goes wrong
 * before data flows, so the caller falls back to yt-dlp's own downloader.
 */
const sleep = (ms, signal) => new Promise((resolve) => {
  const t = setTimeout(resolve, ms);
  if (signal) signal.addEventListener('abort', () => { clearTimeout(t); resolve(); }, { once: true });
});

/** Ask yt-dlp for fresh direct stream addresses (they are signed and expire, or get refused mid-download). */
async function fetchFormats(task, ctx) {
  ctx.fail = '';
  const req = task.rec.ytdlp || {};
  const exact = ctx.exact || '';
  const yt = SITE_HOSTS_YT.test(task.rec.url || '');
  // The list the user picked from came from one player client; YouTube hands out different streams per client. Look for the EXACT
  // streams that were picked with every client in turn before ever settling for something else.
  const modes = ctx.argsFor ? [...new Set([req.mode, ...(yt ? ['clean', 'cookies', 'fallback', 'alt'] : [])].filter(Boolean))] : [null];
  const tryOne = async (mode, selector, strictIds) => {
    const args = mode && ctx.argsFor ? ctx.argsFor(mode) : ctx.args;
    // Exact streams are taken straight from the full format list (the same list the quality menu was built from) instead of
    // asking yt-dlp to select them: its selection drops streams the list still shows and then reports "format not available".
    const argv = strictIds ? [...args, '-J', '--ignore-no-formats-error', '--no-playlist', '--', task.rec.url] : [...args, '-f', selector, '-J', '--no-playlist', '--', task.rec.url];
    const r = await run(ctx.bin.path, argv, { signal: task.signal, timeoutMs: 180_000 });
    if (r.code !== 0) return { fail: 'the site engine could not list the streams (' + String(r.err || '').split('\n').filter(Boolean).pop() + ')' };
    let info;
    try { info = JSON.parse(r.out); } catch { return { fail: 'unreadable answer from the site engine' }; }
    let fmts;
    if (strictIds) {
      const list = info.formats || [];
      fmts = strictIds.map((id) => list.find((f) => String(f.format_id) === id));
      // The ids can change between two look-ups of the same video. The quality stays the same, so look for it by
      // resolution, frame rate and codec before giving up on the streams the user picked.
      if (fmts.some((f) => !f)) {
        const alt = matchQuality(list, ctx.picked);
        if (alt) fmts = alt;
        else return { fail: 'the picked streams are not offered by this player type', list };
      }
    } else fmts = (info.requested_formats && info.requested_formats.length ? info.requested_formats : [info]).filter(Boolean);
    if (!fmts.length || fmts.length > 2) return { fail: 'unsupported stream layout' };
    for (const f of fmts) {
      if (!f.url || !/^https?$/.test(f.protocol || '') || f.fragments) return { fail: `this quality is a segmented stream (${f.protocol || 'unknown'}), not a plain file` };
    }
    return { cur: { fmts, duration: Number(info.duration) || 0, mode } };
  };
  if (exact) {
    const ids = exact.split('+');
    let last = '';
    for (const m of modes) {
      const x = await tryOne(m, exact, ids);
      if (x.cur) { ctx.mode = m; return x.cur; }
      last = x.fail;
    }
    ctx.fail = last;
    ctx.exactMissing = true;
    return null; // the caller decides whether a different quality is acceptable
  }
  const x = await tryOne(modes[0], ctx.selector, null);
  if (x.cur) { ctx.mode = modes[0]; return x.cur; }
  ctx.fail = x.fail;
  return null;
}

/**
 * Downloads the chosen streams through the segmented engine. If a link is refused or expires part-way
 * (HTTP 403/410, resets) the finished parts are KEPT, fresh links are requested and the download carries on
 * from where it stopped. yt-dlp's own downloader is only used when the streams cannot be fetched this way at all.
 */
async function tryDirect(task, ctx) {
  const { DownloadTask, newRecord } = require('./download-task');
  const rec = task.rec;
  let cur = null;

  // Step 1: the streams behind the row the user clicked. Their addresses were checked when the list was
  // built, so using them downloads exactly the quality that was offered - no second look-up, no guess.
  const picked = ctx.picked;
  if (picked && picked.video && isPlainStream(picked.video)) {
    const fmts = [picked.video];
    if (picked.audio && isPlainStream(picked.audio)) fmts.push(picked.audio);
    if (fmts.length === 1 && picked.video.acodec && picked.video.acodec !== 'none') fmts.length = 1;
    cur = { fmts, duration: ctx.duration || 0, mode: ctx.mode };
  }

  // Step 2: the site did not hand out those addresses any more - ask again, with every player type.
  if (!cur) cur = await fetchFormats(task, ctx);
  if (!cur && ctx.exactMissing) {
    // None of the player types offers the exact streams as plain files: take the closest plain-file quality and say so.
    const why = shortWhy(ctx.fail);
    const pick = rec.ytdlp && rec.ytdlp.height ? rec.ytdlp.height + 'p' : 'picked';
    ctx.exact = '';
    ctx.selector = plainSelector(rec.ytdlp, ctx.ffmpeg, ctx.selector);
    cur = await fetchFormats(task, ctx);
    if (cur) {
      const got = cur.fmts.find((f) => f.height) || {};
      const gotLabel = got.height ? got.height + 'p' : 'the closest quality';
      // Say what really happens: the file that arrives is the one that is named here.
      rec.warning = got.height && rec.ytdlp && rec.ytdlp.height && got.height !== rec.ytdlp.height
        ? `The site did not hand out the ${pick} stream (${why}). Downloading ${gotLabel} instead - that is a different quality than the one you picked.`
        : `The exact quality you picked is not offered as a plain file right now (${why}). Downloading ${gotLabel} instead.`;
      rec.ytMeta = rec.ytMeta || {};
      rec.ytMeta.exactMissing = why;
    } else { ctx.fail = why; ctx.exactMissing = false; }
  }
  if (!cur) { rec.warning = /segmented/.test(ctx.fail) ? 'This stream is delivered in fragments (' + ctx.fail.replace(/^this quality is a segmented stream /, '') + '): they are downloaded in parallel instead.' : 'Multi-connection mode is not available for this video: ' + shortWhy(ctx.fail) + '. Using the single-stream downloader.'; return false; }
  if (cur.fmts.length > 1 && !ctx.ffmpeg) { rec.warning = 'Multi-connection mode needs ffmpeg to join video and audio. Install it from Options.'; return false; }
  // The resolution that is really downloaded is remembered, so the result can be checked against the pick.
  const gotHeight = (cur.fmts.find((f) => f.height) || {}).height || 0;
  const wantHeight = (rec.ytdlp && rec.ytdlp.height) || 0;
  if (wantHeight && gotHeight && gotHeight !== wantHeight) {
    rec.warning = `Downloading ${gotHeight}p instead of the selected ${wantHeight}p: the site did not hand out the ${wantHeight}p stream.`;
  }
  rec.ytMeta = rec.ytMeta || {};
  rec.ytMeta.gotHeight = gotHeight;
  rec.ytMeta.wantHeight = wantHeight;
  const duration = cur.duration;
  const saved = (rec.direct = rec.direct || {});
  const conns = Math.max(1, Math.min(32, rec.connections || task.settings.maxConnections));
  let last = 0;
  let stalls = 0;
  let subs = [];
  let streamOut = '';
  let streamed = false;
  const expectedOf = (fmts) => fmts.reduce((n, f) => n + (f.filesize || f.filesize_approx || 0), 0);

  // The user's connection setting is the TOTAL: split it between the streams by size (audio is small), never more.
  const split = (fmts) => {
    if (fmts.length === 1) return [conns];
    const sz = fmts.map((f) => f.filesize || f.filesize_approx || 0);
    const aShare = sz[0] + sz[1] > 0 ? sz[1] / (sz[0] + sz[1]) : 0.1;
    const a = Math.min(Math.max(1, Math.round(conns * aShare)), Math.max(1, conns - 1));
    return [Math.max(1, conns - a), a];
  };
  const build = (fmts, sequential) => { const share = split(fmts); return fmts.map((f, i) => {
    const id = String(f.format_id || i);
    const savePath = path.join(rec.workDir, `part${i}.${f.ext || 'bin'}`);
    const gv = /googlevideo\.com/i.test(f.url);
    const sr = newRecord({
      id: `${rec.id}-${i}`, kind: 'http', url: f.url, dir: rec.workDir,
      headers: f.http_headers || {}, connections: share[i],
      speedLimit: rec.speedLimit ? Math.ceil(rec.speedLimit / fmts.length) : 0,
    });
    sr.savePath = savePath;
    sr.partPath = savePath + '.part';
    sr.fileName = path.basename(savePath);
    sr.fileNameLocked = true;
    sr.autoDir = false;
    sr.chunk = gv ? 10 * 1024 * 1024 : 0; // YouTube throttles very large ranges
    if (sequential) sr.sequentialChunk = gv ? 10 * 1024 * 1024 : 16 * 1024 * 1024;
    const base = saved[id];
    if (base && !base.done) Object.assign(sr, { total: base.total, resumable: base.resumable, etag: '', lastModified: '', segments: base.segments || [] });
    return { id, f, savePath, done: !!(base && base.done), t: new DownloadTask(sr, task.ctx) };
  }); };

  const sync = () => {
    let dl = 0;
    let tot = 0;
    let off = 0;
    const segs = [];
    for (const s of subs) {
      const sr = s.t.rec;
      const total = sr.total > 0 ? sr.total : s.f.filesize || s.f.filesize_approx || 0;
      dl += s.done ? total : s.t.downloadedBytes;
      tot += total;
      if (!s.done) for (const g of sr.segments) {
        if (g.end < 0) continue;
        const prev = segs[segs.length - 1];
        // hundreds of small in-order pieces: neighbouring finished ones are shown as one block
        if (g.done && prev && prev.done && prev._base === s.id) { prev.end = off + g.end; prev.pos = prev.flushed = off + g.end + 1; continue; }
        const waiting = (x) => !x.done && !x._active && x.flushed === x.start;
        const cand = { start: off + g.start, end: off + g.end, pos: off + g.flushed, flushed: off + g.flushed, done: !!g.done, _active: !!g._active, _base: s.id };
        // the not-yet-started pieces ahead of the connections are one block, so the bar shows the real connections, not hundreds of pieces
        if (waiting(cand) && prev && prev._base === s.id && waiting(prev)) { prev.end = cand.end; continue; }
        segs.push(cand);
      }
      saved[s.id] = { done: s.done, total: sr.total, resumable: sr.resumable, segments: sr.segments.map((g) => ({ start: g.start, end: g.end, pos: g.flushed, flushed: g.flushed, done: !!g.done })) };
      off += Math.max(0, total);
    }
    rec.segments = segs;
    rec.total = tot || expectedOf(cur.fmts) || -1;
    rec.downloaded = dl;
    rec.resumable = true;
    if (dl > last) task.addBytes(dl - last);
    last = Math.max(last, dl);
    return dl;
  };

  task.setState('downloading');
  for (let round = 0; round < 8; round++) {
    // Join video and audio while they download (first round of a fresh download only); anything unusual falls back to joining afterwards.
    const wantStream = round === 0 && cur.fmts.length === 2 && ctx.ffmpegPath && task.settings.ytStreamMerge !== false && !Object.keys(saved).length;
    subs = build(cur.fmts, wantStream);
    let stream = null;
    if (wantStream) {
      const vExt = String(cur.fmts[0].ext || ''), aExt = String(cur.fmts[1].ext || '');
      streamOut = path.join(rec.workDir, 'video.' + (vExt === 'mp4' && aExt === 'm4a' ? 'mp4' : 'mkv'));
      stream = require('./streammerge').start({
        sources: subs.map((s) => ({ rec: s.t.rec, isDone: () => s.done, paths: () => [s.t.rec.partPath, s.t.rec.savePath] })),
        out: streamOut, ffmpegPath: ctx.ffmpegPath, signal: task.signal,
        onProgress: (sec) => { if (rec.state === 'merging' && duration > 0) { rec.mergePct = Math.min(99, Math.round((sec / duration) * 100)); task.emit('progress', task); } },
      });
    }
    const timer = setInterval(sync, 150);
    const stop = () => subs.forEach((s) => s.t.pause());
    task.signal.addEventListener('abort', stop, { once: true });
    const before = last;
    try {
      await Promise.all(subs.filter((s) => !s.done).map(async (s) => {
        await s.t.start();
        if (s.t.rec.state === 'completed') s.done = true;
      }));
    } finally {
      clearInterval(timer);
      task.signal.removeEventListener('abort', stop);
    }
    sync();
    if (task.signal.aborted) { if (stream) stream.abort(); throw new Error('aborted'); }
    if (subs.every((s) => s.done)) {
      if (stream) {
        // the join has been running all along: only the last bytes are left
        task.setState('merging'); rec.mergePct = 99; task.speed = 0;
        let t;
        streamed = await Promise.race([stream.finished, new Promise((r) => { t = setTimeout(() => r(false), 120_000); })]);
        clearTimeout(t);
        if (!streamed) { stream.abort(); await stream.finished.catch(() => {}); }
      }
      break;
    }
    if (stream) { stream.abort(); await stream.finished.catch(() => {}); stream = null; }
    // A link failed part-way. Keep everything already on disk and ask for fresh links.
    stalls = last > before ? 0 : stalls + 1;
    if (stalls >= 3 || round === 7) {
      // nothing moves any more: only now hand over to yt-dlp's own downloader
      rec.segments = []; rec.downloaded = 0; rec.direct = {};
      for (const s of subs) await fsp.rm(s.savePath + '.part', { force: true }).catch(() => {});
      return false;
    }
    task.setState('probing');
    await sleep(Math.min(6000, 1000 * (round + 1)), task.signal);
    if (task.signal.aborted) throw new Error('aborted');
    const fresh = await fetchFormats(task, ctx).catch(() => null);
    if (fresh && fresh.fmts.length === cur.fmts.length) cur = { fmts: fresh.fmts, duration: fresh.duration || duration };
    task.setState('downloading');
  }

  if (streamed) {
    (rec.ytMeta = rec.ytMeta || {}).streamed = true;
    for (const s of subs) fsp.rm(s.savePath, { force: true }).catch(() => {});
  } else if (subs.length === 1) {
    await fsp.rename(subs[0].savePath, path.join(rec.workDir, 'video.' + (subs[0].f.ext || 'mp4')));
  } else {
    task.setState('merging');
    rec.mergePct = 0;
    task.speed = 0;
    const mergeStart = Date.now();
    const merged = async (ext) => {
      const out = path.join(rec.workDir, 'video.' + ext);
      // no "+faststart": it rewrites the whole multi-GB file a second time and looks like a hang
      const args = ['-y', '-nostdin', '-nostats', '-loglevel', 'error', '-progress', 'pipe:1', '-i', subs[0].savePath, '-i', subs[1].savePath, '-map', '0:v:0', '-map', '1:a:0', '-c', 'copy', out];
      const m = await run(ctx.ffmpegPath, args, {
        signal: task.signal,
        onLine: (line) => {
          const t = /^out_time_(?:us|ms)=(\d+)/.exec(line);
          if (t && duration > 0) { rec.mergePct = Math.min(99, Math.round((Number(t[1]) / 1e6 / duration) * 100)); task.speed = 0; task.emit('progress', task); }
        },
      });
      return m.code === 0;
    };
    if (!(await merged('mp4')) && !(await merged('mkv'))) throw new Error('Joining video and audio with ffmpeg failed');
    for (const s of subs) fsp.rm(s.savePath, { force: true }).catch(() => {}); // deleting multi-GB parts is not worth waiting for
  }
  rec.mergePct = undefined;
  rec.direct = {};
  return true;
}

/** Task runner: called by DownloadTask.start() for records with kind 'ytdlp'. */
async function runTask(task) {
  const rec = task.rec;
  const env = { settings: task.settings, binDir: task.ctx.binDir };
  const bin = tools.findYtdlp(env.settings, env.binDir);
  if (!bin) throw new FatalError('The site video engine (yt-dlp) is not installed. Install it from Options → Site videos.', 'NO_YTDLP');

  const meta = (rec.ytMeta = rec.ytMeta || {});
  const ffmpeg = !!tools.findFfmpeg(env.settings, env.binDir);
  const req = rec.ytdlp || {};

  // The list of qualities and the streams behind it. The same look-up that filled the menu is used for the
  // download, so the file that arrives is the one that was offered. If the fresh look-up fails, the last list
  // that worked for this page is used: the addresses in it were checked when the list was built.
  let info = null;
  if (!meta.title || !meta.streams) {
    try {
      info = await probe(env, { url: rec.url, referer: rec.referer, userAgent: rec.userAgent, cookieList: rec.cookieList }, task.signal);
    } catch (e) {
      const last = lastProbe(rec.url);
      if (!last || !last.streams || !last.streams.length) throw e;
      info = last;
    }
    if (!meta.title) {
      meta.title = info.title || rec.pageTitle || 'video';
      const opt = req.audio ? info.audio : (info.options.find((o) => req.vid && o.formatId === req.vid) || info.options.find((o) => o.height === req.height) || info.options[0]);
      meta.expected = opt ? opt.size : 0;
      meta.audio = !!req.audio;
      meta.ext = opt && opt.ext;
      meta.duration = info.duration || 0;
    }
    meta.streams = pickStreams(info, req);
  }
  const picked = meta.streams || null;

  if (!rec.dir) {
    rec.dir = task.settings.categorize ? path.join(task.settings.downloadDir, rec.audioOnly || (rec.ytdlp && rec.ytdlp.audio) ? 'Music' : 'Video') : task.settings.downloadDir;
  }
  await fsp.mkdir(rec.dir, { recursive: true });
  if (!rec.fileName) {
    rec.fileName = names.sanitize(meta.title, 'video') + '.' + (meta.audio ? meta.ext || 'm4a' : 'mp4');
    rec.category = rec.category || (meta.audio ? 'Music' : 'Video');
  }
  rec.workDir = rec.workDir || path.join(rec.dir, `.velociget-${rec.id.slice(0, 8)}`);
  await fsp.mkdir(rec.workDir, { recursive: true });
  rec.total = meta.expected || -1;
  rec.resumable = true;

  let cookiePath = null;
  if (rec.cookieList && rec.cookieList.length) {
    cookiePath = path.join(rec.workDir, 'cookies.txt');
    await fsp.writeFile(cookiePath, cookieFileText(rec.cookieList));
  }
  const { args } = commonArgs(env, { url: rec.url, referer: rec.referer, userAgent: rec.userAgent, mode: rec.ytdlp && rec.ytdlp.mode }, cookiePath);
  const limit = Math.max(0, rec.speedLimit || 0) || task.settings.speedLimit || 0;
  const argv = [
    ...args,
    '-f', selectorFor(rec.ytdlp, ffmpeg),
    '-o', path.join(rec.workDir, 'video.%(ext)s'),
    '--merge-output-format', 'mp4',
    '--concurrent-fragments', String(Math.max(1, Math.min(16, rec.connections || task.settings.maxConnections))),
    '--progress-template', PROGRESS,
    ...(limit ? ['--limit-rate', String(limit)] : []),
    ...(task.settings.preserveTime ? [] : ['--no-mtime']),
    '--', rec.url,
  ];

  task.setState('downloading');
  const streams = new Map(); // file name -> { dl, total }
  let counted = 0;
  const onLine = (line) => {
    if (line.startsWith('VGP\t')) {
      const [, dl, total, est, file] = line.split('\t');
      const s = { dl: num(dl), total: num(total) || num(est) };
      streams.set(file, s);
      let sum = 0;
      let known = 0;
      for (const v of streams.values()) {
        sum += v.dl;
        known += Math.max(v.total, v.dl);
      }
      if (sum > counted) task.addBytes(sum - counted);
      counted = Math.max(counted, sum);
      rec.downloaded = Math.max(rec.downloaded || 0, sum);
      rec.total = Math.max(meta.expected || 0, known) || -1;
      if (rec.total > 0 && rec.downloaded > rec.total) rec.total = rec.downloaded;
    } else if (/^\[(Merger|VideoRemuxer|ExtractAudio|FixupM4a|Fixup)/i.test(line)) {
      if (rec.state !== 'merging') task.setState('merging');
    }
  };

  const selector = selectorFor(rec.ytdlp, ffmpeg);
  let viaEngine = false;
  let directCtx = null;
  if (task.settings.ytDirect !== false) {
    try {
      const rq = rec.ytdlp || {};
      const exact = !rq.audio && rq.vid ? (rq.aid ? `${rq.vid}+${rq.aid}` : rq.vid) : '';
      const argsFor = (mode) => commonArgs(env, { url: rec.url, referer: rec.referer, userAgent: rec.userAgent, mode }, cookiePath).args;
      directCtx = { bin, args, selector, ffmpeg, exact, exactIds: exact, argsFor, picked, duration: (info && info.duration) || meta.duration || 0, ffmpegPath: tools.findFfmpeg(env.settings, env.binDir) };
      viaEngine = await tryDirect(task, directCtx);
    } catch (e) {
      if (task.signal.aborted) {
        if (cookiePath) await fsp.rm(cookiePath, { force: true }).catch(() => {});
        throw e;
      }
      viaEngine = false; // anything unexpected: use yt-dlp's own downloader
    }
    if (!viaEngine) task.setState('downloading');
  }

  if (!viaEngine) {
    // single-stream downloader: same player type that listed the streams, exact streams first, plain files before HLS variants
    if (directCtx && directCtx.mode && directCtx.mode !== (rec.ytdlp && rec.ytdlp.mode)) argv.splice(0, args.length, ...directCtx.argsFor(directCtx.mode));
    if (SITE_HOSTS_YT.test(rec.url || '') && rec.ytdlp && rec.ytdlp.height && !rec.ytdlp.audio) {
      const i = argv.indexOf('-f');
      const plain = plainSelector(rec.ytdlp, ffmpeg, selector);
      // The exact ids stay in the chain: they are the streams that were picked, and they come first.
      const exactSel = directCtx && directCtx.exactIds;
      argv[i + 1] = [exactSel, plain, selector].filter(Boolean).join('/');
    }
    rec.nativeConns = directCtx && /segmented/.test(directCtx.fail || '') ? Math.max(1, Math.min(16, rec.connections || task.settings.maxConnections)) : 1;
    let result;
    try {
      result = await run(bin.path, argv, { signal: task.signal, onLine });
    } finally {
      if (cookiePath) await fsp.rm(cookiePath, { force: true }).catch(() => {});
    }
    if (result.code !== 0) {
      const pickedLabel = rec.ytdlp && rec.ytdlp.height ? rec.ytdlp.height + 'p' : '';
      const e = new Error(explainError(result.err, result.code, { jsRuntime: !!tools.findDeno(task.settings, env.binDir) || !!tools.findNode(), picked: pickedLabel }));
      throw e;
    }
  } else if (cookiePath) {
    await fsp.rm(cookiePath, { force: true }).catch(() => {});
  }

  // Locate the finished file: video.<ext>, not an intermediate format file or a partial.
  const files = (await fsp.readdir(rec.workDir)).filter((n) => /^video\.[a-z0-9]+$/i.test(n) && !/\.(part|ytdl|temp)$/i.test(n));
  let best = null;
  for (const n of files) {
    const st = await fsp.stat(path.join(rec.workDir, n));
    if (!best || st.size > best.size) best = { name: n, size: st.size };
  }
  if (!best) throw new Error('The download finished but no output file was produced');
  const ext = path.extname(best.name);
  const stem = path.basename(rec.fileName, path.extname(rec.fileName));
  const target = await names.uniquePath(rec.dir, stem + ext);
  await fsp.rename(path.join(rec.workDir, best.name), target);
  fsp.rm(rec.workDir, { recursive: true, force: true }).catch(() => {}); // cleanup runs in the background: the download is already complete
  rec.workDir = '';
  rec.savePath = target;
  rec.fileName = path.basename(target);
  rec.total = rec.downloaded = best.size;
  rec.cookieList = null;
  await verifyQuality(task, target);
}

/**
 * Look at the file that was actually written and compare it with the quality that was picked.
 * If they differ the user is told plainly - a silently different picture is worse than a clear note.
 */
async function verifyQuality(task, file) {
  const rec = task.rec;
  const want = rec.ytdlp && rec.ytdlp.height;
  if (!want || (rec.ytdlp && rec.ytdlp.audio)) return;
  const fp = tools.findFfprobe(task.settings, task.ctx.binDir);
  if (!fp) return;
  const m = await tools.probeMedia(fp, file, {}).catch(() => null);
  if (!m || !m.height) return;
  rec.ytMeta = rec.ytMeta || {};
  rec.ytMeta.gotHeight = m.height;
  if (m.height !== Number(want)) {
    rec.warning = `Downloaded ${m.height}p instead of the selected ${want}p.`;
  } else if (rec.warning && /instead of the selected/.test(rec.warning)) {
    rec.warning = ''; // the file has the quality that was asked for after all
  }
}

module.exports = { probe, runTask, isSiteUrl, summarize, selectorFor, plainSelector, cookieFileText, explainError, commonArgs, SITE_HOSTS, pickStreams, lastProbe };
