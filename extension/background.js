'use strict';
// Works in Chrome/Edge (service worker) and Firefox (event page).
const ext = globalThis.browser ?? globalThis.chrome;

const MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|flv|m3u8|mp3|m4a|aac|ogg|opus|wav)(\?|#|$)/i;
const MEDIA_TYPE = /^(video\/|audio\/|application\/(vnd\.apple\.mpegurl|x-mpegurl|mpegurl))/i;
const MEDIA_EXT2 = /\.(mp4|m4v|webm|mkv|mov|flv|avi|wmv|3gp|ogv|mpg|mpeg|mp3|m4a|aac|ogg|opus|wav|wma|flac)(\?|#|$)/i;
const SUB_EXT = /\.(srt|vtt|ass|ssa)(\?|#|$)/i;
const globRe = (g) => new RegExp('^' + String(g).trim().replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$', 'i');
const extMatches = (list, e) => (list || []).some((p) => (String(p).includes('*') ? globRe(p).test(e) : String(p).toLowerCase() === e));
const hostMatches = (list, h) => (list || []).some((raw) => {
  const p = String(raw).trim().toLowerCase();
  if (!p) return false;
  if (p.startsWith('*.') && !p.slice(2).includes('*') && h === p.slice(2)) return true;
  if (p.includes('*')) return globRe(p).test(h);
  return h === p || h.endsWith('.' + p);
});
const extOf = (u) => { try { return (/\.([a-z0-9]{1,5})$/i.exec(new URL(u).pathname) || [])[1]?.toLowerCase() || ''; } catch { return ''; } };
const SKIP_URL = /\.(ts|m4s)(\?|#|$)|googlevideo\.com|\/videoplayback(\?|$)/i; // fragments and chunked player streams, not whole files
const SITE_HOSTS = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|twitch\.tv|tiktok\.com|instagram\.com|facebook\.com|fb\.watch|twitter\.com|x\.com|reddit\.com|bilibili\.com|streamable\.com)$/i;
const isSite = (u) => { try { return SITE_HOSTS.test(new URL(u).hostname); } catch { return false; } };

// ---- settings & API ---------------------------------------------------------------

let cfg = { port: 24680, token: '', captureEnabled: true, captureExtensions: [], minCaptureSize: 0, excludedSites: [], showVideoOverlay: true };

async function loadPairing() {
  const s = await ext.storage.local.get(['port', 'token', 'remote']);
  cfg.port = s.port || cfg.port;
  cfg.token = s.token || '';
  if (s.remote) Object.assign(cfg, s.remote);
  const l = await ext.storage.local.get('captureOff');
  cfg.captureOff = !!l.captureOff;
}

async function api(path, body) {
  if (!cfg.token) await loadPairing();
  if (!cfg.token) throw new Error('Not paired. Open the extension options and paste the pairing code from IDM By Encodal → Options → Browser integration.');
  const r = await fetch(`http://127.0.0.1:${cfg.port}${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { authorization: `Bearer ${cfg.token}`, ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(json.error || `HTTP ${r.status}`);
  return json;
}

async function refreshRemoteSettings() {
  try {
    const st = await api('/api/status');
    cfg = { ...cfg, ...st.settings };
    await ext.storage.local.set({ remote: st.settings });
    cfg.captureOff = !!(await ext.storage.local.get('captureOff')).captureOff;
    return true;
  } catch {
    return false;
  }
}

ext.storage.onChanged.addListener((c, area) => {
  if (area === 'local' && c.captureOff) cfg.captureOff = !!c.captureOff.newValue;
  if (area === 'local' && (c.port || c.token)) loadPairing().then(refreshRemoteSettings);
});
loadPairing().then(refreshRemoteSettings);
setInterval(refreshRemoteSettings, 60_000);

// ---- media sniffing ----------------------------------------------------------------------------------
// The list is kept in storage.session, not in memory: Chrome stops this worker after ~30 s idle and would
// otherwise forget everything the page loaded by the time the user clicks the button.
const SESSION = ext.storage.session || ext.storage.local;
const MAX_AGE = 30 * 60 * 1000;
const tabKey = (id) => `m:${id}`;
const otherKey = (id) => `o:${id}`;
const chains = new Map();

const manifestKey = (id) => `hm:${id}`;
const MAX_MANIFEST_CHARS = 256 * 1024;
const MANIFEST_AGE = 10 * 60 * 1000;
async function readTab(tabId) {
  const r = await SESSION.get(tabKey(tabId));
  const now = Date.now();
  return (r[tabKey(tabId)] || []).filter((x) => now - x.at < MAX_AGE);
}

const sameRequestUrl = (a, b) => {
  try { const x = new URL(a); const y = new URL(b); x.hash = ''; y.hash = ''; return x.href === y.href; } catch { return false; }
};
const manifestHash = (text) => {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return (h >>> 0).toString(16);
};

async function readManifestBody(tabId, url) {
  const key = manifestKey(tabId);
  const data = await SESSION.get(key);
  const now = Date.now();
  const list = (data[key] || []).filter((x) => now - x.at < MANIFEST_AGE);
  const found = list.find((x) => sameRequestUrl(x.url, url));
  if (list.length !== (data[key] || []).length) await SESSION.set({ [key]: list });
  return found ? found.body : '';
}

async function saveManifestBody(tabId, url, body) {
  if (!Number.isInteger(tabId) || tabId < 0 || typeof body !== 'string' || body.length > MAX_MANIFEST_CHARS || !/^\uFEFF?\s*#EXTM3U/.test(body.slice(0, 64))) return false;
  let observed = [];
  // webRequest and the page's cloned fetch response can arrive in either order.
  for (let i = 0; i < 6; i++) {
    observed = await readTab(tabId);
    if (observed.some((x) => x.hls && sameRequestUrl(x.url, url))) break;
    await new Promise((resolve) => setTimeout(resolve, 80));
  }
  if (!observed.some((x) => x.hls && sameRequestUrl(x.url, url))) {
    // The page received a real playlist that the request sniffer never classified (odd content type, worker request): list it like XDM does, by its content.
    remember(tabId, { url, type: 'application/vnd.apple.mpegurl', size: 0, hls: true, at: Date.now() });
    await (chains.get(tabId) || Promise.resolve());
  }
  const key = manifestKey(tabId);
  const data = await SESSION.get(key);
  const list = (data[key] || []).filter((x) => x.url !== url && Date.now() - x.at < MANIFEST_AGE);
  list.push({ url, body, at: Date.now() });
  while (list.length > 3) list.shift();
  await SESSION.set({ [key]: list });
  return true;
}

/** Requests that did not look like media but could be (generic binary, unknown types): the "More formats" list. */
const NOT_MEDIA_TYPE = /^(text\/|application\/(json|javascript|x-javascript|xml|xhtml|wasm|manifest|ld\+json)|image\/|font\/|application\/font)/i;
function rememberOther(tabId, d) {
  if (tabId < 0) return;
  const h = (n) => d.responseHeaders?.find((x) => x.name.toLowerCase() === n)?.value || '';
  const type = h('content-type').split(';')[0].trim().toLowerCase();
  if (NOT_MEDIA_TYPE.test(type)) return;
  let size = Number(h('content-length')) || 0;
  const rg = /\/(\d+)$/.exec(h('content-range'));
  if (rg) size = Number(rg[1]);
  if (size && size < 200 * 1024) return;
  const item = { url: d.url, type: type || 'unknown', size, status: d.statusCode, kind: d.type, at: Date.now() };
  (chains.get('o' + tabId) || Promise.resolve()).then(async () => {
    const r = await SESSION.get(otherKey(tabId));
    const list = (r[otherKey(tabId)] || []).filter((x) => x.url !== item.url);
    list.push(item);
    while (list.length > 80) list.shift();
    await SESSION.set({ [otherKey(tabId)]: list });
  }).catch(() => {});
}

function remember(tabId, item) {
  if (tabId < 0) return;
  const next = (chains.get(tabId) || Promise.resolve()).then(async () => {
    const list = await readTab(tabId);
    if (list.some((x) => x.url === item.url)) return;
    list.push(item);
    while (list.length > 60) list.shift();
    await SESSION.set({ [tabKey(tabId)]: list });
    updateBadge(tabId, list.length);
    warm(tabId, item);
  }).catch(() => {});
  chains.set(tabId, next);
}

/** Probe freshly sniffed files in the background (4 at a time) so the popup opens with the answers already cached. */
const warmQueue = [];
let warming = 0;
function warm(tabId, item) {
  if (item.sub || item.dash) return;
  warmQueue.push({ tabId, item });
  pumpWarm();
}
function pumpWarm() {
  while (warming < 4 && warmQueue.length) {
    const { tabId, item } = warmQueue.shift();
    warming++;
    ext.tabs.get(tabId).then((t) => {
      const referer = (t && t.url) || '';
      return item.hls ? hlsInfo(tabId, item.url, referer) : fileInfo(item.url, referer, true);
    }).catch(() => {}).finally(() => { warming--; pumpWarm(); });
  }
}

function updateBadge(tabId, n) {
  try {
    ext.action.setBadgeText({ tabId, text: n ? String(n) : '' });
    ext.action.setBadgeBackgroundColor?.({ tabId, color: '#3b82f6' });
  } catch {
    /* the tab may already be gone */
  }
}

/** Requests made by Web Workers / service workers have no tab; attribute them to tabs of the same site. */
async function tabsForInitiator(initiator) {
  if (!initiator) return [];
  try {
    const tabs = await ext.tabs.query({});
    return tabs.filter((t) => { try { return new URL(t.url).origin === initiator; } catch { return false; } }).map((t) => t.id);
  } catch {
    return [];
  }
}

function classify(d) {
  const h = (n) => d.responseHeaders?.find((x) => x.name.toLowerCase() === n)?.value || '';
  const type = h('content-type').split(';')[0].trim().toLowerCase();
  const hls = /mpegurl/i.test(type) || /\.m3u8(\?|#|$)/i.test(d.url);
  const dash = /dash\+xml/i.test(type) || /\.mpd(\?|#|$)/i.test(d.url);
  if (hls || dash) return { url: d.url, type, size: 0, hls, dash, at: Date.now() };
  if (SUB_EXT.test(d.url) || /^(text\/vtt|application\/x-subrip)/i.test(type)) return { url: d.url, type, size: Number(h('content-length')) || 0, sub: true, at: Date.now() };
  if (SKIP_URL.test(d.url)) return null;
  let size = Number(h('content-length')) || 0;
  const range = /\/(\d+)$/.exec(h('content-range'));
  if (range) size = Number(range[1]);
  // Requests made by a <video>/<audio> element count even when the server sends a generic content type.
  // streaming sites often serve movies as generic binary from an API/CDN URL: accept a media extension anywhere in the address,
  // a media file name in Content-Disposition, or a large ranged binary response requested by a player
  const cd = h('content-disposition');
  const innerExt = /[/=&?]([^/=&?#]+)\.(mp4|m4v|webm|mkv|mov|flv|avi|wmv|3gp|ogv|mp3|m4a|aac|opus)(?:[&#/]|$)/i.test(d.url);
  const bigRanged = !!range && size >= 5 * 1024 * 1024 && /octet-stream|binary|^$|mp4|mpeg|matroska|webm/i.test(type) && ['media', 'xmlhttprequest', 'other'].includes(d.type);
  const looksMedia = MEDIA_TYPE.test(type) || MEDIA_EXT2.test(d.url) || innerExt || MEDIA_EXT2.test(cd.replace(/["';]+/g, ' ').trim() + ' ') || bigRanged || (d.type === 'media' && size >= 100 * 1024);
  if (!looksMedia) return null;
  if (size && size < 100 * 1024) return null; // ads, pings and sound effects
  return { url: d.url, type, size, hls: false, dash: false, at: Date.now() };
}

ext.webRequest.onResponseStarted.addListener(
  (d) => {
    if (d.statusCode !== 200 && d.statusCode !== 206) return;
    if (['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font', 'ping', 'csp_report'].includes(d.type)) return;
    const item = classify(d);
    if (!item) { rememberOther(d.tabId, d); return; }
    if (d.tabId >= 0) remember(d.tabId, item);
    else tabsForInitiator(d.initiator).then((ids) => ids.forEach((id) => remember(id, item)));
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders'],
);

ext.tabs.onRemoved.addListener((id) => SESSION.remove([tabKey(id), otherKey(id), manifestKey(id)]).catch(() => {}));
ext.tabs.onUpdated.addListener((id, info) => {
  if (info.status === 'loading' && info.url) {
    SESSION.remove([tabKey(id), otherKey(id), manifestKey(id)]).catch(() => {});
    updateBadge(id, 0);
  }
});

// ---- sending to the app ------------------------------------------------------------------

async function cookieHeader(url) {
  try {
    const list = await ext.cookies.getAll({ url });
    return list.map((c) => `${c.name}=${c.value}`).join('; ');
  } catch {
    return '';
  }
}

async function cookieList(url) {
  try {
    const list = await ext.cookies.getAll({ url });
    return list.map((c) => ({ name: c.name, value: c.value, domain: c.domain, path: c.path, secure: c.secure, expirationDate: c.expirationDate }));
  } catch {
    return [];
  }
}

async function send(item, tab) {
  const site = item.kind === 'ytdlp';
  const hlsOptions = item.variantUrl
    ? { variantUrl: item.variantUrl, ...(item.manifestBody ? { manifestBody: item.manifestBody } : {}) }
    : item.hls && typeof item.hls === 'object' ? { ...item.hls } : {};
  if (!site && (item.kind === 'hls' || item.hls || item.variantUrl) && !hlsOptions.manifestBody && tab?.id != null) {
    const captured = await readManifestBody(tab.id, item.url);
    if (captured) hlsOptions.manifestBody = captured;
  }
  const body = {
    url: item.url,
    referer: item.referer || tab?.url || '',
    userAgent: navigator.userAgent,
    pageTitle: item.title || tab?.title || '',
    fileName: item.fileName || '',
    kind: site ? 'ytdlp' : item.hls || item.kind === 'hls' ? 'hls' : 'http',
    hls: Object.keys(hlsOptions).length ? hlsOptions : undefined,
    ytdlp: site ? item.ytdlp || {} : undefined,
    ask: item.noAsk ? false : cfg.showFileInfoDialog !== false,
  };
  if (site) body.cookieList = [...(await cookieList(item.url)), ...(body.referer && body.referer !== item.url ? await cookieList(body.referer) : [])];
  else body.cookie = await cookieHeader(item.url);
  return api('/api/add', body);
}

function notify(msg) {
  try {
    ext.notifications?.create({ type: 'basic', iconUrl: 'icons/icon48.png', title: 'IDM By Encodal', message: msg });
  } catch {
    /* notifications permission is optional */
  }
}

// ---- download interception -----------------------------------------------------------------

function hostExcluded(url) {
  try {
    const h = new URL(url).hostname.toLowerCase();
    if ((cfg.excludedAddresses || []).some((p) => p.trim() && globRe(p.includes('*') ? p : p + '*').test(url))) return true;
    return hostMatches(cfg.excludedSites, h);
  } catch {
    return false;
  }
}

function shouldCapture(item) {
  if (!cfg.captureEnabled || cfg.captureOff || !cfg.token) return false;
  let u;
  try {
    u = new URL(item.finalUrl || item.url);
  } catch {
    return false;
  }
  if (!/^https?:$/.test(u.protocol)) return false; // blob:, data:, file: stay in the browser
  if (hostExcluded(u.href)) return false;
  const name = (item.filename || '').split(/[\\/]/).pop() || decodeURIComponent(u.pathname.split('/').pop() || '');
  const e = (name.split('.').pop() || '').toLowerCase();
  const typeMatch = e && extMatches(cfg.captureExtensions, e);
  const big = item.fileSize > 0 && item.fileSize >= (cfg.minCaptureSize || 0);
  if (item.fileSize > 0 && item.fileSize < (cfg.minCaptureSize || 0)) return false;
  return typeMatch || (big && !/^text\/html/i.test(item.mime || ''));
}

const handled = new Set();
ext.downloads.onCreated.addListener(async (item) => {
  if (handled.has(item.id) || item.byExtensionId) return;
  if (!shouldCapture(item)) return;
  handled.add(item.id);
  // Only take the download over if the app is actually reachable, otherwise let the browser finish it.
  const alive = await refreshRemoteSettings();
  if (!alive || !shouldCapture(item)) return;
  try {
    await ext.downloads.cancel(item.id);
    await ext.downloads.erase({ id: item.id });
  } catch {
    return;
  }
  try {
    await send({ url: item.finalUrl || item.url, referer: item.referrer, fileName: (item.filename || '').split(/[\\/]/).pop() });
  } catch (e) {
    notify(`Could not hand the download to IDM By Encodal: ${e.message}`);
  }
});

// ---- context menu ------------------------------------------------------------------------------

ext.runtime.onInstalled.addListener(() => {
  ext.contextMenus.create({ id: 'vg-link', title: 'Download with IDM By Encodal', contexts: ['link'] });
  ext.contextMenus.create({ id: 'vg-media', title: 'Download media with IDM By Encodal', contexts: ['video', 'audio', 'image'] });
  ext.contextMenus.create({ id: 'vg-assets', title: 'Show all images, links and media of this page', contexts: ['page', 'image', 'link'] });
  ext.contextMenus.create({ id: 'vg-links', title: 'Download all links on this page with IDM By Encodal', contexts: ['page', 'selection'] });
  ext.contextMenus.create({ id: 'vg-page', title: 'Download media on this page with IDM By Encodal', contexts: ['page'] });
});

ext.contextMenus.onClicked.addListener(async (info, tab) => {
  try {
    if (info.menuItemId === 'vg-link') await send({ url: info.linkUrl, referer: info.pageUrl }, tab);
    else if (info.menuItemId === 'vg-media') await send({ url: info.srcUrl, referer: info.pageUrl }, tab);
    else if (info.menuItemId === 'vg-assets') {
      ext.tabs.create({ url: ext.runtime.getURL('grabber/grabber.html?tab=' + tab.id) });
    } else if (info.menuItemId === 'vg-links') {
      // XDM-style "download all links": every link on the page (or in the selection) whose file type is one we take over
      const links = await ext.tabs.sendMessage(tab.id, { type: 'vg-links', selection: !!info.selectionText });
      const want = (links || []).filter((u) => { const e = extOf(u); return e && extMatches(cfg.captureExtensions, e); });
      const unique = [...new Set(want)].slice(0, 300);
      if (!unique.length) return notify('No links to downloadable files were found on this page.');
      await api('/api/add-many', { items: unique.map((u) => ({ url: u, referer: tab.url, userAgent: navigator.userAgent, startNow: true })) });
      notify(`Added ${unique.length} link${unique.length > 1 ? 's' : ''} to IDM By Encodal`);
    } else if (info.menuItemId === 'vg-page') {
      const best = await pickBest(tab.id);
      if (isSite(tab.url)) await send({ kind: 'ytdlp', url: tab.url, ytdlp: {}, title: tab.title }, tab);
      else if (best && (best.hls || best.dash)) await send({ kind: 'ytdlp', url: best.url, referer: tab.url, ytdlp: {}, title: tab.title }, tab);
      else if (best) await send(best, tab);
      else notify('No downloadable video found on this page yet. Play the video first.');
    }
  } catch (e) {
    notify(e.message);
  }
});

async function pickBest(tabId) {
  const list = await readTab(tabId);
  return list.sort((a, b) => (b.hls || b.dash ? 1 : 0) - (a.hls || a.dash ? 1 : 0) || b.size - a.size)[0];
}


// ---- caches + grouping for the popup list ------------------------------------------------------------
const cache = new Map();
const tabMeta = new Map();
const inflight = new Map();
/** Runs fn once per key; concurrent callers share the promise, results live `ttl` ms. */
function cached(key, fn, ttl = 10 * 60_000) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < ttl) return Promise.resolve(hit.value);
  if (inflight.has(key)) return inflight.get(key);
  const p = fn().then((value) => {
    if (!value || !value.error) cache.set(key, { at: Date.now(), value });
    if (cache.size > 200) cache.delete(cache.keys().next().value);
    return value;
  }).finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

async function hlsInfo(tabId, url, referer) {
  const manifestBody = await readManifestBody(tabId, url);
  const sig = manifestBody ? manifestHash(manifestBody) : 'refetch';
  return cached(`hls|${url}|${sig}`, () => api('/api/probe-media', {
    kind: 'hls', url, referer, userAgent: navigator.userAgent, cookie: '',
    ...(manifestBody ? { manifestBody } : {}),
  }), manifestBody ? 60_000 : 10 * 60_000);
}

async function siteInfo(url, referer) {
  referer = referer || url;
  return cached(`site|${url}|${referer}`, async () => {
    const jar = [...(await cookieList(url)), ...(referer !== url ? await cookieList(referer) : [])];
    return api('/api/probe-media', { kind: 'site', url, referer, userAgent: navigator.userAgent, cookieList: jar });
  });
}

async function fileInfo(url, referer, details) {
  const fast = await cached(`file|${url}`, async () => ({ url, ...(await api('/api/probe-media', { url, referer, userAgent: navigator.userAgent, cookie: await cookieHeader(url) })) }));
  if (!details || fast.error) return fast;
  const full = await cached(`file+|${url}`, async () => ({ url, ...(await api('/api/probe-media', { url, referer, userAgent: navigator.userAgent, cookie: await cookieHeader(url), details: true })) }), 10 * 60_000);
  return { ...fast, ...full };
}

const QUALITY_TOKEN = /[_\-.\s]?(2160|1440|1080|720|576|480|360|240|144)p?\b|[_\-.]?(uhd|fhd|hd|sd|low|medium|high)\b/gi;
function groupKey(u) {
  try {
    const x = new URL(u);
    const dir = x.pathname.split('/').slice(0, -1).join('/');
    const base = decodeURIComponent(x.pathname.split('/').pop() || '').replace(/\.[a-z0-9]{2,4}$/i, '').replace(QUALITY_TOKEN, '');
    return `${x.hostname}${dir}/${base.toLowerCase()}`;
  } catch {
    return u;
  }
}
const nameOf = (u) => { try { return decodeURIComponent(new URL(u).pathname.split('/').pop() || u); } catch { return u; } };

/** Instant, network-free list: sniffed items grouped into entries (one per video, qualities come later). */
async function buildEntries(tab) {
  const items = await readTab(tab.id);
  const entries = [];
  if (tab.url && isSite(tab.url)) entries.push({ id: 'site', kind: 'site', title: tab.title || 'This page\'s video', url: tab.url });
  const groups = new Map();
  for (const it of items.sort((a, b) => b.at - a.at)) {
    if (it.dash) entries.push({ id: 'd:' + it.url, kind: 'dash', title: tab.title || nameOf(it.url), url: it.url });
    else if (it.hls) entries.push({ id: 'h:' + it.url, kind: 'hls', title: tab.title || nameOf(it.url), url: it.url });
    else if (it.sub) entries.push({ id: 's:' + it.url, kind: 'sub', title: nameOf(it.url), url: it.url, files: [it] });
    else {
      const k = groupKey(it.url);
      if (!groups.has(k)) groups.set(k, { id: 'f:' + k, kind: 'files', title: nameOf(it.url), files: [] });
      groups.get(k).files.push(it);
    }
  }
  const vids = [...groups.values()];
  for (const g of vids) {
    const clean = nameOf(g.files[0].url).replace(/\.[a-z0-9]{2,4}$/i, '').replace(QUALITY_TOKEN, '').replace(/[_-]+/g, ' ').trim();
    g.title = vids.length === 1 && tab.title ? tab.title : clean || g.title;
  }
  // streaming sites name their files with hashes: use the page title and number them, like IDM's list
  const hashy = (t) => /^[0-9a-f]{12,}$/i.test(String(t).replace(/[\s_-]/g, '')) || /^\d{8,}$/.test(String(t));
  let n = 0;
  for (const g of vids) if (tab.title && hashy(g.title)) g.title = `${++n}. ${tab.title}`;
  const subs = entries.filter((e) => e.kind === 'sub');
  return [...entries.filter((e) => e.kind !== 'sub'), ...vids, ...subs];
}

const hdLabel = (h) => (h ? `${h}p${h >= 720 ? ' HD' : ''}` : '');
const fmtSize = (n) => (n >= 1073741824 ? (n / 1073741824).toFixed(2) + ' GB' : n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 0 ? Math.max(1, Math.round(n / 1024)) + ' KB' : '');
const fmtDur = (s) => { s = Math.round(s || 0); if (!s) return ''; const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60); return h ? `${h}h ${m}m` : m ? `${m} min` : `${s} sec`; };

const QUALITY_HEIGHTS = '2160|1440|1080|900|720|576|540|480|360|240|144';
function namedHeight(value) {
  const text = String(value || '').replace(/%([0-9a-f]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)));
  const marked = new RegExp(`(?:^|[^0-9])(${QUALITY_HEIGHTS})\\s*p(?:[^0-9]|$)`, 'i').exec(text)
    || new RegExp(`(?:quality|resolution|height|res)[^a-z0-9]{0,8}(${QUALITY_HEIGHTS})(?:p|[^0-9]|$)`, 'i').exec(text);
  return marked ? Number(marked[1]) : 0;
}
function normalizeHeight(value) {
  const h = Number(value) || 0;
  const standard = [144, 240, 360, 480, 540, 576, 720, 900, 1080, 1440, 2160];
  const nearest = standard.reduce((best, n) => Math.abs(n - h) < Math.abs(best - h) ? n : best, standard[0]);
  return Math.abs(nearest - h) <= Math.max(8, nearest * 0.01) ? nearest : h;
}

const clockDur = (n) => { n = Math.round(n || 0); const h = Math.floor(n / 3600), m = Math.floor((n % 3600) / 60), x = n % 60, z = (v) => String(v).padStart(2, '0'); return h ? `${h}:${z(m)}:${z(x)}` : `${m}:${z(x)}`; };
function siteOptions(info, url, referer) {
  const out = (info.options || []).map((o) => ({
    key: o.formatId || 'h' + o.height + (o.fps || ''), height: o.height,
    label: `${o.height}p${o.fps || ''}${o.height >= 720 ? ' HD' : ''}` + (o.available ? '' : ' · needs ffmpeg'),
    detail: [info.duration ? clockDur(info.duration) : '', o.detail, o.ext && o.ext.toUpperCase(), o.size && fmtSize(o.size)].filter(Boolean).join(' · '), size: o.size || 0, disabled: !o.available,
    req: { kind: 'ytdlp', url, referer, ytdlp: { height: o.height, fps: o.fps, vid: o.formatId, aid: o.audioId, mode: info.mode }, title: info.title },
  }));
  if (info.audio) out.push({ key: 'audio', height: 0, label: 'Audio only', detail: info.audio.size ? fmtSize(info.audio.size) : '', size: 0, req: { kind: 'ytdlp', url, referer, ytdlp: { audio: true, aid: info.audio.formatId, mode: info.mode }, title: info.title } });
  return out;
}

/** Fills in the qualities of one entry (network). Always resolves with { options, title?, error? }. */
async function enrichEntry(entry, tab, details) {
  const referer = tab.url;
  if (entry.kind === 'site' || entry.kind === 'dash') {
    const info = await siteInfo(entry.url, referer);
    if (!info || info.error || !((info.options || []).length || info.audio)) return { error: (info && info.error) || 'No video found' };
    return { title: info.title, options: siteOptions(info, entry.url, referer) };
  }
  if (entry.kind === 'hls') {
    const manifestBody = await readManifestBody(tab.id, entry.url);
    const p = await hlsInfo(tab.id, entry.url, referer);
    if (p && p.master && (p.variants || []).length) {
      return { options: [...p.variants].sort((a, b) => b.bandwidth - a.bandwidth).map((v) => ({
        key: v.url, height: v.height || 0, label: v.label || hdLabel(v.height) || 'Stream', detail: [`${Math.round(v.bandwidth / 1000)} kbps`, v.lang, v.duration ? fmtDur(v.duration) : '', v.size ? '~ ' + fmtSize(v.size) : '', 'HLS'].filter(Boolean).join(' · '), size: v.size || 0, duration: v.duration || 0,
        req: { kind: 'hls', url: entry.url, variantUrl: v.url, manifestBody, title: tab.title, referer },
      })) };
    }
    return { options: [{ key: 'hls', height: 0, label: 'Stream', detail: 'HLS', size: 0, req: { kind: 'hls', url: entry.url, manifestBody, title: tab.title, referer } }] };
  }
  // plain files: one option per file
  const infos = await Promise.all((entry.files || []).map((f) => fileInfo(f.url, referer, details)));
  return { options: fileOptions(entry, tab, infos) };
}

/** Options for plain files from whatever is known; `infos[i]` may be missing (then the sniffed size and the name are used). */
function fileOptions(entry, tab, infos) {
  const referer = tab.url;
  const opts = [];
  (entry.files || []).forEach((f, i) => {
    const r = infos[i];
    if (r && !r.error && /text\/html/i.test(r.contentType || '') && entry.kind !== 'sub') return; // an error page
    const size = (r && r.size > 0 ? r.size : f.size) || 0;
    // Explicit quality in the URL or the response filename beats probe dimensions (CDNs often report misleading ones).
    const h = normalizeHeight(namedHeight(f.url) || namedHeight(r && r.fileName) || (r && r.height) || 0);
    const ext = ((/\.([a-z0-9]{2,4})(\?|#|$)/i.exec(f.url) || /\.([a-z0-9]{2,4})$/i.exec(r && r.fileName || '') || [])[1] || 'file').toUpperCase();
    const kbps = r && r.bitRate ? Math.round(r.bitRate / 1000) : 0;
    opts.push({
      key: f.url, height: h, size,
      label: entry.kind === 'sub' ? ext + ' subtitles' : (hdLabel(h) || ext + ' file'),
      detail: [ext, r && fmtDur(r.duration), kbps && kbps + ' kbps', fmtSize(size)].filter(Boolean).join(' · '), duration: (r && r.duration) || 0,
      provisional: !r,
      req: { url: f.url, fileName: r && r.fileName, title: tab.title, referer },
    });
  });
  opts.sort((a, b) => b.height - a.height || b.size - a.size);
  return opts;
}

/** Answer without any network: cached probe results when there are some, the sniffed data otherwise. */
function quickOptions(entry, tab) {
  if (entry.kind !== 'files' && entry.kind !== 'sub') return null;
  const infos = (entry.files || []).map((f) => { const h = cache.get(`file+|${f.url}`) || cache.get(`file|${f.url}`); return h ? { ...(cache.get(`file|${f.url}`)?.value || {}), ...h.value } : null; });
  return { options: fileOptions(entry, tab, infos), full: infos.every((i) => i && i.duration !== undefined) };
}

// ---- messages from content script / popup ----------------------------------------------------------

ext.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    const tab = msg.tabId != null ? await ext.tabs.get(msg.tabId) : sender.tab || null;
    switch (msg.type) {
      case 'settings':
        await refreshRemoteSettings();
        return { ...cfg, token: undefined, paired: !!cfg.token, captureEnabled: cfg.captureEnabled && !cfg.captureOff };
      case 'candidates': {
        const tabId = tab?.id ?? msg.tabId;
        return { items: (await readTab(tabId)).sort((a, b) => b.at - a.at) };
      }
      case 'manifest-body': {
        if (!tab || !/^https?:\/\//i.test(msg.url || '')) return { ok: false };
        return { ok: await saveManifestBody(tab.id, msg.url, msg.body) };
      }
      case 'found': {
        // URLs a page script / the DOM revealed (JSON play-info, <source>, <track> …)
        const id = tab?.id ?? msg.tabId;
        for (const u of (msg.urls || []).slice(0, 80)) {
          if (!/^https?:\/\//i.test(u)) continue;
          if (/\.m3u8(\?|#|$)/i.test(u)) remember(id, { url: u, type: '', size: 0, hls: true, at: Date.now() });
          else if (/\.mpd(\?|#|$)/i.test(u)) remember(id, { url: u, type: '', size: 0, dash: true, at: Date.now() });
          else if (SUB_EXT.test(u)) remember(id, { url: u, type: '', size: 0, sub: true, at: Date.now() });
          else if (MEDIA_EXT2.test(u) && !SKIP_URL.test(u)) remember(id, { url: u, type: '', size: 0, at: Date.now() });
        }
        return { ok: true };
      }
      case 'probe-http': {
        const referer = msg.referer || tab?.url || '';
        const one = (u) => fileInfo(u, referer, !!msg.details);
        const out = [];
        const urls = (msg.urls || []).slice(0, 40);
        for (let i = 0; i < urls.length; i += 6) out.push(...(await Promise.all(urls.slice(i, i + 6).map(one))));
        return { results: out };
      }
      case 'pagemeta': {
        if (tab) tabMeta.set(tab.id, { poster: msg.poster || '', og: msg.og || '', frame: msg.frame || '' });
        return { ok: true };
      }
      case 'thumb': {
        // Order: engine thumbnail (YouTube etc.) -> a real frame of the file via ffmpeg -> the page's poster / og:image / playing frame
        const e = msg.entry;
        const meta = tabMeta.get(tab.id) || {};
        let img = '';
        if (e.kind === 'site' || e.kind === 'dash') {
          const info = await siteInfo(e.url, tab.url);
          img = (info && info.thumbnail) || '';
        }
        if (!img && e.kind !== 'sub') {
          const u = e.kind === 'files' ? (msg.url || e.files[0]?.url) : e.url;
          if (u) {
            const r = await cached(`thumb|${u}`, async () => api('/api/thumbnail', { url: u, referer: tab.url, userAgent: navigator.userAgent, cookie: await cookieHeader(u) }));
            img = (r && r.dataUrl) || '';
          }
        }
        if (!img) img = meta.frame || meta.poster || meta.og || '';
        return { img };
      }
      case 'others': {
        const r = await SESSION.get(otherKey(tab.id));
        return { items: (r[otherKey(tab.id)] || []).sort((a, b) => b.at - a.at) };
      }
      case 'collect':
        { const entries = await buildEntries(tab); for (const e of entries) e.pre = quickOptions(e, tab); return { entries, tab: { id: tab.id, url: tab.url, title: tab.title } }; }
      case 'enrich':
        return await enrichEntry(msg.entry, tab, !!msg.details);
      case 'prefetch': {
        // Warm the cache while the user is still looking at the video, so the list opens instantly.
        const u = msg.url || (tab && tab.url);
        if (u && isSite(u)) siteInfo(u, u).catch(() => {});
        return { ok: true };
      }
      case 'download-many': {
        const urls = [...new Set(msg.urls || [])].slice(0, 500);
        if (!urls.length) return { ok: true };
        return await api('/api/add-many', { items: urls.map((u) => ({ url: u, referer: tab.url, userAgent: navigator.userAgent, startNow: true })) });
      }
      case 'download':
        return await send(msg.item, tab);
      case 'probe-site':
        return await siteInfo(msg.url, msg.referer || msg.url);
      case 'probe':
        return await api('/api/probe-media', { url: msg.url, referer: tab?.url || '', userAgent: navigator.userAgent, cookie: await cookieHeader(msg.url) });
      case 'status':
        return await api('/api/status');
      case 'focus':
        return await api('/api/focus', {});
      case 'ping':
        return { ok: await refreshRemoteSettings() };
      default:
        return { error: 'unknown message' };
    }
  })().then(reply, (e) => reply({ error: e.message }));
  return true; // async response
});
