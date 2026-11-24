'use strict';
// In-page "Download this video" bar with a quality list, in the style of IDM's video panel.
(() => {
  if (window.__velociget) return;
  window.__velociget = true;
  const ext = globalThis.browser ?? globalThis.chrome;
  const send = (m) => new Promise((res) => ext.runtime.sendMessage(m, (r) => res(r || { error: ext.runtime.lastError?.message })));

  const SITE_HOSTS = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|twitch\.tv|tiktok\.com|instagram\.com|facebook\.com|fb\.watch|twitter\.com|x\.com|reddit\.com|bilibili\.com|streamable\.com)$/i;
  const isSiteHost = SITE_HOSTS.test(location.hostname);
  const MIN_W = 240;
  const MIN_H = 135;

  let enabled = true;
  send({ type: 'settings' }).then((s) => { if (s && s.showVideoOverlay === false) enabled = false; });

  // ---- UI (closed shadow root so page CSS cannot touch it) ----------------------
  const host = document.createElement('div');
  host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;display:none;width:min(460px,calc(100vw - 16px));pointer-events:none;';
  const root = host.attachShadow({ mode: 'closed' });
  root.innerHTML = `
    <style>
      *{box-sizing:border-box}
      .wrap>*{pointer-events:auto}.wrap{display:flex;flex-direction:column;align-items:flex-end;font:12px/1.2 "Segoe UI",Tahoma,system-ui,sans-serif}
      .bar{display:flex;height:28px;border-radius:4px;overflow:hidden;border:1px solid #1d4f91;box-shadow:0 2px 8px rgba(0,0,0,.45);
           background:linear-gradient(#5aa0f2,#2b6fd0);color:#fff;cursor:pointer;user-select:none}
      .bar:hover{filter:brightness(1.08)}
      .main{display:flex;align-items:center;gap:7px;padding:0 11px 0 8px;font-weight:600;white-space:nowrap}
      .x{display:flex;align-items:center;padding:0 8px;border-left:1px solid rgba(255,255,255,.35);font-size:11px;opacity:.9}
      .x:hover{background:rgba(0,0,0,.25)}
      svg{width:15px;height:15px;fill:none;stroke:#fff;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round}
      .panel{display:none;width:100%;box-sizing:border-box;max-height:min(70vh,520px);overflow:auto;margin-top:4px;background:#fff;color:#1b1b1b;border:1px solid #8a9db8;border-radius:4px;box-shadow:0 6px 20px rgba(0,0,0,.4);overflow:hidden}
      .hd{padding:6px 10px;background:linear-gradient(#f6f8fb,#dfe6f0);border-bottom:1px solid #b7c3d4;font-weight:600;color:#25364d}
      .list{max-height:min(340px,60vh);overflow:auto;overscroll-behavior:contain}
      .row{display:flex;justify-content:space-between;gap:10px;padding:7px 10px;cursor:pointer;border-bottom:1px solid #eef1f5}
      .row:hover{background:#cde8ff}
      .row.off{opacity:.5;cursor:default}.row.off:hover{background:none}
      .row b{font-weight:600}.row span{color:#5b6b80;white-space:nowrap}
      .row.all{background:#f3f6fa;font-weight:600;color:#25364d;border-bottom:1px solid #c9d3e0}
      .row.item{display:block}.row.item b{display:block;white-space:nowrap;overflow:hidden;text-ellipsis:ellipsis;text-overflow:ellipsis}
      .row.item span{display:block;margin-top:2px;white-space:normal;font-size:11.5px}
      .tag.cd{background:#eef0f3;color:#44546a}.tag{margin-left:6px;font-size:10px;padding:1px 5px;border-radius:3px;background:#e3edfb;color:#245ab0;font-weight:600}
      .note{padding:8px 10px;color:#5b6b80;line-height:1.35}
      .ft{padding:6px 10px;background:#f6f8fb;border-top:1px solid #dbe2ec;color:#5b6b80;font-size:11px}
      .ft a{color:#245ab0;cursor:pointer;text-decoration:underline}
      .toast{display:none;margin-top:4px;max-width:340px;background:#1f2937;color:#fff;padding:7px 10px;border-radius:4px;box-shadow:0 3px 10px rgba(0,0,0,.4)}
      .toast.err{background:#9b1c1c}
    </style>
    <div class="wrap">
      <div class="bar" id="bar">
        <div class="main" id="main"><svg viewBox="0 0 24 24"><path d="M12 3v12m0 0l-4.5-4.5M12 15l4.5-4.5M5 20h14"/></svg><span>Download this video</span></div>
        <div class="x" id="x" title="Hide for this video">✕</div>
      </div>
      <div class="panel" id="panel"><div class="hd" id="hd">Choose quality</div><div class="list" id="list"></div><div class="ft" id="ft" hidden></div></div>
      <div class="toast" id="toast"></div>
    </div>`;
  const $ = (id) => root.getElementById(id);
  const panel = $('panel');
  const list = $('list');
  const toastEl = $('toast');

  let current = null;
  let hideTimer = null;
  let panelOpen = false;
  const dismissed = new WeakSet();

  const place = (v) => {
    const r = v.getBoundingClientRect();
    if (r.width < MIN_W || r.height < MIN_H || r.bottom < 40 || r.top > innerHeight - 40 || r.right < 0 || r.left > innerWidth) return false;
    host.style.left = Math.max(4, Math.min(innerWidth - 464, r.right - 470)) + 'px';
    host.style.top = Math.max(4, Math.min(innerHeight - 40, r.top + 10)) + 'px';
    return true;
  };
  // The page address of the video under the pointer. On YouTube's home / search / channel pages every thumbnail links to its own
  // watch page, so the list must be looked up for THAT address (not the address of the page being browsed).
  const YT_HOST = /(^|\.)(youtube\.com|youtube-nocookie\.com)$/i.test(location.hostname);
  // The page address of one video on any supported platform (stripped of tracking parameters).
  const permaUrl = (href) => {
    try {
      const u = new URL(href, location.href);
      const h = u.hostname.replace(/^(www|m|mobile|web)\./, '');
      const p = u.pathname;
      let m;
      if (/(^|\.)youtube\.com$/.test(h)) {
        const id = p === '/watch' ? u.searchParams.get('v') : (/^\/(shorts|live|embed)\/([\w-]{6,})/.exec(p) || [])[2];
        return id ? `https://www.youtube.com/watch?v=${id}` : '';
      }
      if (h === 'youtu.be') return /^\/[\w-]{6,}/.test(p) ? `https://www.youtube.com/watch?v=${p.slice(1, 12)}` : '';
      if (/tiktok\.com$/.test(h) && (m = /^(\/@[^/]+)?\/(video|photo)\/(\d+)/.exec(p))) return `https://www.tiktok.com${m[0]}`;
      if (/instagram\.com$/.test(h) && (m = /^(\/[^/]+)?\/(reels?|p|tv)\/([\w-]+)/.exec(p))) return `https://www.instagram.com${m[0]}/`;
      if (/(^|\.)(x|twitter)\.com$/.test(h) && (m = /^\/[^/]+\/status\/\d+/.exec(p))) return `https://x.com${m[0]}`;
      if (/(facebook\.com|fb\.watch)$/.test(h)) {
        if (p === '/watch/' || p === '/watch') return u.searchParams.get('v') ? `https://www.facebook.com/watch/?v=${u.searchParams.get('v')}` : '';
        if ((m = /^(\/[^/]+)?\/(reel|videos)\/(\d+)/.exec(p))) return `https://www.facebook.com${m[0]}`;
        if (h === 'fb.watch' && p.length > 2) return u.origin + p;
      }
      if (/reddit\.com$/.test(h) && /^\/r\/[^/]+\/comments\//.test(p)) return `https://www.reddit.com${p}`;
      return '';
    } catch { return ''; }
  };
  const ytWatchUrl = permaUrl;
  const permaIn = (root) => {
    for (const a of root.querySelectorAll ? root.querySelectorAll('a[href]') : []) { const u = permaUrl(a.getAttribute('href')); if (u) return u; }
    return '';
  };
  // Feeds (TikTok For You, Instagram, X ...) have no address in the video itself: look for the post's own link around it.
  const permaFromVideo = (v) => {
    if (!isSiteHost || !v) return '';
    let n = v;
    for (let i = 0; i < 7 && n && n.parentElement; i++) {
      n = n.parentElement;
      if (n.querySelectorAll('video').length > 1) break;
      const u = permaIn(n);
      if (u) return u;
    }
    return '';
  };
  const pageUrlAt = (x, y) => {
    if (!isSiteHost) return '';
    for (const el of document.elementsFromPoint(x, y)) {
      const a = el.closest && el.closest('a[href]');
      const u = a && permaUrl(a.getAttribute('href'));
      if (u) return u;
    }
    return '';
  };
  let hoverUrl = '';
  let autoOpened = false;
  const warmedUrls = new Set();
  const warm = (url) => {
    if (!url || warmedUrls.has(url)) return;
    warmedUrls.add(url);
    if (warmedUrls.size > 60) warmedUrls.delete(warmedUrls.values().next().value);
    send({ type: 'prefetch', url });
  };
  const showFor = (v, forMs = 0, url = '') => {
    // The list opens instantly: the engine starts looking at the video the moment the pointer is on it.
    if (!enabled || dismissed.has(v)) return;
    const prevKey = showFor.key || '';
    hoverUrl = url || permaFromVideo(v) || (isSiteHost ? permaUrl(location.href) : '') || '';
    if (!IS_TT) warm(hoverUrl || location.href);
    const tt = IS_TT ? ttFor(v) : null;
    const key = hoverUrl + '|' + (tt ? tt.id : '');
    showFor.key = key;
    // Another video under the pointer: drop the old list; after a short dwell this video's own qualities open by themselves.
    if (isSiteHost && key !== '|' && key !== prevKey) {
      if (panelOpen) closePanel();
      clearTimeout(showFor.dwell);
      showFor.dwell = setTimeout(() => { if (showFor.key === key && !panelOpen && host.style.display === 'block') { autoOpened = true; open(); } }, 450);
    }
    clearTimeout(hideTimer);
    current = v;
    if (place(v)) host.style.display = 'block';
    if (forMs) autoHide(forMs);
  };
  const hide = () => {
    host.style.display = 'none';
    closePanel();
  };
  const autoHide = (ms = 2600) => {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!panelOpen) hide(); }, ms);
  };
  const closePanel = () => { panel.style.display = 'none'; panelOpen = false; };

  const videoAt = (x, y) => {
    for (const el of document.elementsFromPoint(x, y)) if (el instanceof HTMLVideoElement) return el;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return v;
    }
    return null;
  };

  let tick = 0;
  document.addEventListener('mousemove', (e) => {
    const now = Date.now();
    if (now - tick < 100) return;
    tick = now;
    const v = videoAt(e.clientX, e.clientY);
    if (v) showFor(v, 2600, pageUrlAt(e.clientX, e.clientY));
  }, { passive: true, capture: true });
  // Thumbnails (before their preview video even exists): show the button and start the look-up straight away.
  document.addEventListener('mouseover', (e) => {
    if (!isSiteHost || !enabled) return;
    const a = e.target.closest && e.target.closest('a[href]');
    const url = a && ytWatchUrl(a.getAttribute('href'));
    if (!url) return;
    const thumb = a.querySelector('img, yt-image, video') || a;
    if (thumb.getBoundingClientRect().width >= MIN_W) showFor(thumb, 2600, url);
  }, { passive: true, capture: true });
  host.addEventListener('mouseenter', () => clearTimeout(hideTimer));
  host.addEventListener('mouseleave', () => autoHide(1200));
  document.addEventListener('play', (e) => { if (e.target instanceof HTMLVideoElement) showFor(e.target, 4000); }, true);
  addEventListener('scroll', () => { if (current && host.style.display === 'block' && !place(current)) hide(); }, { passive: true });
  addEventListener('resize', () => { if (current && host.style.display === 'block') place(current); }, { passive: true });

  // ---- helpers ------------------------------------------------------------------
  const toast = (text, err = false) => {
    toastEl.textContent = text;
    toastEl.className = 'toast' + (err ? ' err' : '');
    toastEl.style.display = 'block';
    clearTimeout(toast.t);
    toast.t = setTimeout(() => (toastEl.style.display = 'none'), err ? 7000 : 3500);
  };
  const mb = (n) => (n ? (n >= 1073741824 ? (n / 1073741824).toFixed(2) + ' GB' : n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB') : '');
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  const baseSetRows = (rows, header, footerHtml) => {
    $('hd').textContent = header || 'Choose quality';
    list.innerHTML = '';
    for (const r of rows) {
      const d = document.createElement('div');
      d.className = 'row' + (r.off ? ' off' : '');
      d.innerHTML = r.html;
      if (r.title) d.title = r.title;
      if (!r.off && r.onClick) d.onclick = r.onClick;
      if (r.note) d.className = 'note';
      if (r.cls) d.className += ' ' + r.cls;
      list.appendChild(d);
    }
    const ft = $('ft');
    ft.hidden = !footerHtml;
    ft.innerHTML = footerHtml || '';
    const a = ft.querySelector('a');
    if (a) a.onclick = () => send({ type: 'focus' });
    panel.style.display = 'block';
    panelOpen = true;
  };
  const setRows = baseSetRows;

  const addToApp = async (item) => {
    closePanel();
    const r = await send({ type: 'download', item: { ...item, title: item.title || document.title, referer: item.referer || location.href } });
    if (r.error) return toast(r.error, true);
    toast(r.duplicate ? 'Already in IDM By Encodal' : 'Sent to IDM By Encodal');
  };

  const siteRows = (info, pageUrl, referer) => {
    const rows = info.options.map((o) => ({
      html: `<b>${esc((o.ext || 'mp4').toUpperCase())} ${o.height}p${o.fps > 30 ? ' ' + Math.round(o.fps) : ''}${o.height >= 720 ? '<span class="tag">HD</span>' : ''}${o.codec ? `<span class="tag cd">${esc(o.codec)}</span>` : ''}</b><span>${o.available ? mb(o.size) : 'needs ffmpeg'}</span>`,
      off: !o.available,
      title: o.available ? '' : 'Install ffmpeg from IDM By Encodal → Options to download this quality',
      onClick: () => addToApp({ kind: 'ytdlp', url: pageUrl, referer, ytdlp: { height: o.height, fps: o.fps, vid: o.formatId, aid: o.audioId, mode: info.mode }, title: info.title }),
    }));
    if (info.audio) rows.push({ html: `<b>${esc(info.audio.label)}</b><span>${mb(info.audio.size)}</span>`, onClick: () => addToApp({ kind: 'ytdlp', url: pageUrl, referer, ytdlp: { audio: true, mode: info.mode }, title: info.title }) });
    return rows;
  };

  // Everything visible on the page, for the "Images, links and media" tab.
  // ---- everything the page holds: images (all sources), links and files ----------------------
  const IMG_EXT = /\.(jpe?g|png|gif|webp|avif|svg|bmp|ico|tiff?|jfif|heic|heif|apng)(\?|#|$)/i;
  const FILE_EXT = /\.(pdf|docx?|xlsx?|pptx?|odt|ods|odp|rtf|txt|csv|epub|mobi|zip|rar|7z|tar|gz|tgz|bz2|xz|iso|exe|msi|dmg|apk|torrent|mp3|m4a|wav|flac|aac|ogg|opus|wma|mp4|m4v|mkv|avi|mov|webm|wmv|flv|mpg|mpeg|3gp|psd|ai|eps|jar|bin|cab|deb|rpm|pkg|srt|vtt)(\?|#|$)/i;
  const collectAssets = () => {
    const abs = (u) => { try { return u && !/^(data|blob|javascript|about):/i.test(u) ? new URL(String(u).trim(), location.href).href : ''; } catch { return ''; } };
    const images = new Map();
    const addImg = (u, w = 0, h = 0, alt = '', strict = false) => {
      u = abs(u);
      if (!u || !/^https?:/i.test(u)) return;
      if (strict && !IMG_EXT.test(u)) return; // non-<img> sources must look like an image
      const o = images.get(u);
      if (!o) images.set(u, { url: u, w, h, alt });
      else if (w && !o.w) { o.w = w; o.h = h; }
    };
    // every candidate of a srcset (responsive sizes such as /_next/image?url=..&w=640 are separate files)
    const srcsetUrls = (set) => String(set || '').split(/,\s+(?=\S)|,$/).map((p) => p.trim().split(/\s+/)[0]).filter(Boolean);
    const addSet = (set, alt) => { for (const u of srcsetUrls(set)) addImg(u, 0, 0, alt); };
    const urlsIn = (css) => { const o = []; const t = String(css || ''); for (const m of t.matchAll(/url\(\s*["']?([^"')]+?)["']?\s*\)/g)) o.push(m[1]); for (const m of t.matchAll(/image-set\(([^)]*)\)/g)) for (const q of m[1].matchAll(/["']([^"']+)["']/g)) o.push(q[1]); return o; };

    // every element, including those inside open shadow roots
    const els = [];
    const walk = (root) => {
      for (const el of root.querySelectorAll('*')) {
        if (els.length > 25000) return;
        els.push(el);
        if (el.shadowRoot) walk(el.shadowRoot);
      }
    };
    walk(document);

    const linkMap = new Map();
    const addLink = (u, text) => { u = abs(u); if (!/^https?:/i.test(u) || linkMap.has(u)) return; linkMap.set(u, { url: u, text: String(text || '').trim().replace(/\s+/g, ' ').slice(0, 140) }); };
    const SKIP_ATTR = /^(class|id|style|d|points|viewbox|transform|aria-.*|role|type|rel|target|name|lang|dir|tabindex|width|height|alt|title)$/i;
    let styled = 0;
    for (const el of els) {
      const tag = el.tagName;
      if (tag === 'IMG') {
        addImg(el.currentSrc, el.naturalWidth, el.naturalHeight, el.alt);
        if (el.src !== el.currentSrc) addImg(el.src, 0, 0, el.alt);
        if (el.srcset) addSet(el.srcset, el.alt);
      } else if (tag === 'SOURCE' && el.srcset) addSet(el.srcset);
      else if (tag === 'VIDEO' && el.poster) addImg(el.poster);
      else if (tag === 'IMAGE' || tag === 'image') addImg(el.getAttribute('href') || el.getAttribute('xlink:href'), 0, 0, '', true);
      else if (tag === 'INPUT' && el.type === 'image') addImg(el.src);
      else if (tag === 'META' || tag === 'LINK') {
        const v = el.content || el.href;
        if (/image|icon|thumbnail/i.test((el.getAttribute('property') || '') + (el.name || '') + (el.rel || '') + (el.getAttribute('itemprop') || '') + (el.as || ''))) addImg(v, 0, 0, '', false);
      }
      // lazy-loading attributes and anything else that carries an image address
      for (const a of el.attributes) {
        const v = a.value;
        if (!v || v.length > 2000 || SKIP_ATTR.test(a.name)) continue;
        if (/srcset/i.test(a.name)) { addSet(v); continue; }
        if (a.name === 'style') { urlsIn(v).forEach((u) => addImg(u, 0, 0, '', false)); continue; }
        if (!/\s/.test(v) && /^data-(src|lazy|original|image|img|bg|background|full|large|thumb|poster)/i.test(a.name) && /^(https?:)?\/|^[\w.-]+\/[\w./%-]+/.test(v) && !/^data-.*(srcset)/i.test(a.name)) addImg(v, 0, 0, '', false);
        else if (!/\s/.test(v) && IMG_EXT.test(v)) addImg(v, 0, 0, '', true);
        else if (/^data-.*(bg|background|image|img|src)/i.test(a.name)) urlsIn(v).forEach((u) => addImg(u, 0, 0, '', true));
      }
      if (tag === 'A' || tag === 'AREA') { const h = el.href; if (h) addLink(h, el.innerText || el.title || el.getAttribute('aria-label') || (el.querySelector && (el.querySelector('img') || {}).alt)); }
      else if ((tag === 'IFRAME' || tag === 'EMBED' || tag === 'OBJECT') && FILE_EXT.test(el.src || el.data || '')) addLink(el.src || el.data, el.title);
      if (++styled <= 6000) {
        try {
          const cs = getComputedStyle(el);
          const bg = cs.backgroundImage;
          if (bg && bg !== 'none') urlsIn(bg).forEach((u) => addImg(u));
          if (cs.listStyleImage !== 'none') urlsIn(cs.listStyleImage).forEach((u) => addImg(u));
        } catch { /* ignore */ }
      }
    }
    // stylesheet rules (backgrounds of elements that are not on the page right now)
    try {
      let rules = 0;
      const scan = (list, base) => {
        for (const r of list || []) {
          if (++rules > 30000) return;
          if (r.style && r.style.cssText.includes('url(')) urlsIn(r.style.cssText).forEach((u) => addImg(new URL(u, base).href, 0, 0, '', true));
          if (r.cssRules && r.cssRules.length) scan(r.cssRules, base);
        }
      };
      for (const sh of document.styleSheets) { let l; try { l = sh.cssRules; } catch { continue; } scan(l, sh.href || location.href); }
    } catch { /* ignore */ }
    // what the browser already fetched (lazy-loaded and scripted images)
    try {
      for (const e of performance.getEntriesByType('resource')) if ((e.initiatorType === 'img' || e.initiatorType === 'image' || IMG_EXT.test(e.name)) && !/\.(js|css|json|woff2?)(\?|$)/i.test(e.name)) addImg(e.name, 0, 0, '', e.initiatorType !== 'img' && e.initiatorType !== 'image');
    } catch { /* ignore */ }
    // image addresses inside the page's scripts and JSON (infinite feeds, hydration data)
    try {
      const html = document.documentElement.innerHTML;
      if (html.length < 3e6) {
        const re = /https?:(?:\\?\/){2}[^\s"'<>\\)]+?\.(?:jpe?g|png|gif|webp|avif|svg)(?:\?[^\s"'<>\\)]*)?(?=["'<>\s\\),]|$)/gi;
        for (const m of html.replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&').replace(/\\\//g, '/').match(re) || []) addImg(m.replace(/&amp;/g, '&'));
      }
    } catch { /* ignore */ }
    // every resource of the page: what the browser loaded plus what the markup references
    const res = new Map();
    const addRes = (u, t) => { u = abs(u); if (/^https?:/i.test(u) && !res.has(u)) res.set(u, { url: u, t: t || '' }); };
    try { for (const e of performance.getEntriesByType('resource').slice(-6000)) addRes(e.name, e.initiatorType); } catch { /* ignore */ }
    for (const el of els) {
      const tag = el.tagName;
      if (tag === 'SCRIPT') addRes(el.src, 'script');
      else if (tag === 'LINK') addRes(el.href, /stylesheet/i.test(el.rel) ? 'css' : /icon|image/i.test(el.rel + el.as) ? 'img' : el.as || 'link');
      else if (tag === 'VIDEO' || tag === 'AUDIO') { addRes(el.currentSrc || el.src, tag.toLowerCase()); }
      else if (tag === 'SOURCE' || tag === 'TRACK') addRes(el.src, el.parentElement && el.parentElement.tagName === 'AUDIO' ? 'audio' : '');
      else if (tag === 'EMBED' || tag === 'OBJECT' || tag === 'IFRAME') addRes(el.src || el.data, 'embed');
      else if (tag === 'A' && el.href && (FILE_EXT.test(el.href) || el.hasAttribute('download'))) addRes(el.href, 'link');
    }
    const frame = window === window.top;
    return { images: [...images.values()], links: [...linkMap.values()], resources: [...res.values()], title: frame ? document.title : '', url: location.href, frame };
  };
  ext.runtime.onMessage.addListener((msg, _s, reply) => {
    if (msg && msg.type === 'vg-assets') reply(collectAssets());
  });
  // Scroll the page to the end so lazy-loaded images are fetched, then back (top frame only).
  ext.runtime.onMessage.addListener((msg, _s, reply) => {
    if (!msg || msg.type !== 'vg-scroll' || window !== window.top) return;
    (async () => {
      const start = window.scrollY;
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      let last = -1, same = 0;
      for (let i = 0; i < 45 && same < 3; i++) {
        window.scrollBy(0, Math.max(300, window.innerHeight * 0.9));
        await sleep(300);
        const h = document.documentElement.scrollHeight;
        if (window.scrollY + window.innerHeight >= h - 4) { same = h === last ? same + 1 : 0; }
        last = h;
      }
      window.scrollTo(0, start);
      reply({ ok: true });
    })();
    return true;
  });

  // "Download all links": anchors of the page, or only those inside the current selection.
  ext.runtime.onMessage.addListener((msg, _s, reply) => {
    if (msg && msg.type === 'vg-links') {
      const sel = msg.selection ? getSelection() : null;
      const out = [];
      for (const a of document.querySelectorAll('a[href]')) {
        if (sel && sel.rangeCount && !sel.containsNode(a, true)) continue;
        if (/^https?:/i.test(a.href)) out.push(a.href);
      }
      reply(out);
    }
  });

  // ---- URLs revealed by the page (see inject.js) and by the DOM ------------------------------
  const MEDIA_RE = /https?:(?:\\?\/){2}[^\s"'<>\\)]+?\.(?:mp4|m4v|webm|mkv|mov|flv|mp3|m4a|m3u8|mpd|srt|vtt|ass|ssa)(?:\?[^\s"'<>\\)]*)?(?=["'<>\s\\)]|$)/gi;
  const unescapeUrl = (u) => u.replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
  document.addEventListener('__vg_found', (e) => {
    try { const urls = JSON.parse(e.detail); if (Array.isArray(urls) && urls.length) send({ type: 'found', urls }); } catch { /* ignore */ }
  }, true);

  const domUrls = () => {
    const out = new Set();
    const add = (u) => { try { if (u && !u.startsWith('blob:') && !u.startsWith('data:')) out.add(new URL(u, location.href).href); } catch { /* ignore */ } };
    for (const el of document.querySelectorAll('video, audio')) { add(el.currentSrc); add(el.getAttribute('src')); }
    for (const el of document.querySelectorAll('source[src], track[src]')) add(el.getAttribute('src'));
    try { for (const u of JSON.parse(document.documentElement.getAttribute('data-vg-found') || '[]')) add(u); } catch { /* ignore */ }
    try {
      for (const e of performance.getEntriesByType('resource')) if (/\.(mp4|m4v|webm|mkv|mov|mp3|m4a|m3u8|mpd|srt|vtt|ass)(\?|#|$)/i.test(e.name)) add(e.name);
    } catch { /* resource timing unavailable */ }
    try {
      const html = document.documentElement.innerHTML;
      if (html.length < 6e6) for (const m of html.replace(/\\u002F/gi, '/').replace(/\\\//g, '/').match(MEDIA_RE) || []) add(unescapeUrl(m));
    } catch { /* ignore */ }
    return [...out].filter((u) => !/\.(ts|m4s)(\?|#|$)/i.test(u));
  };

  // Keep the extension's list current without waiting for a click: report new URLs shortly after load and when playback starts.
  const reported = new Set();
  const pushFound = () => {
    const fresh = domUrls().filter((u) => !reported.has(u));
    if (!fresh.length) return;
    fresh.forEach((u) => reported.add(u));
    send({ type: 'found', urls: fresh });
  };
  [800, 2500, 6000, 15000].forEach((ms) => setTimeout(pushFound, ms));
  // Poster / og:image / a frame of the playing video, used as a fallback thumbnail in the popup.
  let metaSent = '';
  const pushMeta = () => {
    try {
      const v = document.querySelector('video');
      const abs = (u) => { try { return u ? new URL(u, location.href).href : ''; } catch { return ''; } };
      let frame = '';
      if (v && v.readyState >= 2 && v.videoWidth) {
        try { const c = document.createElement('canvas'); c.width = 320; c.height = Math.round((320 * v.videoHeight) / v.videoWidth); c.getContext('2d').drawImage(v, 0, 0, c.width, c.height); frame = c.toDataURL('image/jpeg', 0.6); } catch { /* cross-origin video: canvas is tainted */ }
      }
      const m = { poster: abs(v && v.poster), og: abs((document.querySelector('meta[property="og:image"]') || {}).content), frame };
      const sig = m.poster + m.og + frame.length;
      if (sig !== metaSent) { metaSent = sig; send({ type: 'pagemeta', ...m }); }
    } catch { /* ignore */ }
  };
  [1200, 4000, 9000].forEach((ms) => setTimeout(pushMeta, ms));
  document.addEventListener('playing', () => setTimeout(pushMeta, 800), true);
  document.addEventListener('play', () => setTimeout(pushFound, 400), true);
  document.addEventListener('loadedmetadata', () => setTimeout(pushFound, 400), true);

  const clock = (n) => { n = Math.round(n || 0); if (!n) return ''; const h = Math.floor(n / 3600), m = Math.floor((n % 3600) / 60), x = n % 60, z = (v) => String(v).padStart(2, '0'); return h ? `${h}:${z(m)}:${z(x)}` : `${m}:${z(x)}`; };
  const SUB_RE = /\.(srt|vtt|ass|ssa)(\?|#|$)/i;
  const fmtDur = (s) => {
    s = Math.round(s || 0);
    if (!s) return '';
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h ? `${h} hours ${m} min` : m ? `${m} min` : `${s} sec`;
  };
  const nameOf = (u) => { try { return decodeURIComponent(new URL(u).pathname.split('/').pop() || ''); } catch { return u; } };

  /** One list entry per file, described like IDM: "MP4 file, 1 hours 13 min, quality 720p HD, 781 kbps". */
  const describe = (it, i) => {
    const ext = ((/\.([a-z0-9]{2,4})(\?|#|$)/i.exec(it.url) || [])[1] || (it.type || '').split('/')[1] || 'video').toUpperCase();
    const sub = SUB_RE.test(it.url) || it.sub;
    const parts = [`${ext} file`];
    var q = 0;
    if (!sub) {
      const dur = it.duration || (current && isFinite(current.duration) ? current.duration : 0);
      if (dur) parts.push(fmtDur(dur));
      var q = it.height || Number((/(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p?(?:[^0-9]|$)/i.exec(it.url) || [])[1]) || 0;
      if (q) parts.push(`quality ${q}p${q >= 720 ? ' HD' : ''}`);
      const kbps = it.bitRate ? Math.round(it.bitRate / 1000) : it.size > 0 && dur ? Math.round((it.size * 8) / dur / 1000) : 0;
      if (kbps) parts.push(`${kbps} kbps`);
    }
    if (it.size > 0) parts.push(mb(it.size).replace('.0 MB', ' MB'));
    const title = sub ? (it.fileName || nameOf(it.url)) : (document.title || it.fileName || nameOf(it.url));
    return { it, sub, q: q || 0, html: `<b>${i + 1}. ${esc(title.slice(0, 70))}</b><span>${esc(parts.join(', '))}</span>` };
  };

  const renderItems = (items, header, foot) => {
    const described = items.map(describe);
    const vids = described.filter((d) => !d.sub).sort((a, b) => b.q - a.q || (b.it.size || 0) - (a.it.size || 0));
    const subs = described.filter((d) => d.sub);
    const ordered = [...vids, ...subs].map((d, i) => describe({ ...d.it }, i)).map((d, i) => ({ ...d, n: i }));
    const rows = ordered.map((d) => ({ html: d.html, cls: 'item', onClick: () => addToApp(d.it.hls ? { url: d.it.url, kind: 'hls' } : { url: d.it.url, fileName: d.it.fileName }) }));
    if (ordered.length > 1) {
      rows.unshift({
        html: `<b>Download all</b><span>${ordered.length} files</span>`, cls: 'all',
        onClick: async () => {
          closePanel();
          for (const d of ordered) await send({ type: 'download', item: { url: d.it.url, fileName: d.it.fileName, kind: d.it.hls ? 'hls' : undefined, title: document.title, referer: location.href, noAsk: true } });
          toast(`Sent ${ordered.length} files to IDM By Encodal`);
        },
      });
    }
    setRows(rows, header, foot);
  };


  // ---- TikTok: the feed's own data tells which video is playing and lists all of its files ------------------------
  const IS_TT = /(^|\.)tiktok\.com$/i.test(location.hostname);
  const ttItems = new Map(); // id -> { id, user, desc, duration, variants }
  const keyOf = (u) => { try { const x = new URL(u); return x.hostname.split('.').slice(-2).join('.') + x.pathname; } catch { return ''; } };
  const ttByKey = new Map();
  document.addEventListener('__vg_tt', (e) => {
    try {
      for (const it of JSON.parse(e.detail)) {
        ttItems.set(it.id, it);
        for (const v of it.variants) { const k = keyOf(v.url); if (k) ttByKey.set(k, it.id); }
      }
      if (ttItems.size > 400) for (const k of [...ttItems.keys()].slice(0, 100)) ttItems.delete(k);
    } catch { /* ignore */ }
  });
  const ttFor = (v) => {
    if (!IS_TT || !v) return null;
    // 1. the file the player is playing
    const src = v.currentSrc || v.src || (v.querySelector && v.querySelector('source') && v.querySelector('source').src) || '';
    let id = src ? ttByKey.get(keyOf(src)) : '';
    // 2. the video's own address (video pages) or a link to the post next to it
    if (!id) { const m = /\/video\/(\d+)/.exec(hoverUrl || '') || /\/video\/(\d+)/.exec(permaFromVideo(v) || '') || /\/video\/(\d+)/.exec(location.pathname); if (m) id = m[1]; }
    // 3. the caption / author written around the video
    if (!id) {
      let n = v;
      for (let i = 0; i < 8 && n && n.parentElement && !id; i++) {
        n = n.parentElement;
        if (n.querySelectorAll('video').length > 1) break;
        const text = (n.innerText || '').slice(0, 3000);
        for (const it of ttItems.values()) {
          const d = it.desc.replace(/\s+/g, ' ').slice(0, 24);
          if (d.length >= 10 && text.replace(/\s+/g, ' ').includes(d)) { id = it.id; break; }
        }
      }
    }
    return (id && ttItems.get(id)) || null;
  };

  // ---- main action ---------------------------------------------------------------
  let busy = false;
  let openTok = 0;
  const open = async () => {
    if (panelOpen) return closePanel();
    const my = ++openTok;
    const setRows = (...a) => (my === openTok ? baseSetRows(...a) : undefined);
    busy = true;
    try {
      const pageUrl = (isSiteHost && hoverUrl) || location.href;
      // TikTok: list that video's own files straight from the feed data: no page is fetched, so there is nothing to wait for.
      const tt = ttFor(current);
      if (tt && tt.variants.length) {
        const safe = (t) => String(t || 'tiktok').replace(/[\\/:*?"<>|\r\n]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 90) || 'tiktok';
        const vs = [...tt.variants].sort((a, b) => (b.h - a.h) || (b.size - a.size) || (b.br - a.br));
        const rows = vs.map((v) => ({
          html: `<b>MP4 ${v.h ? v.h + 'p' : (v.label || 'video')}${v.h >= 720 ? '<span class="tag">HD</span>' : ''}${v.codec ? `<span class="tag cd">${esc(v.codec)}</span>` : ''}${v.label === 'download' ? '<span class="tag">original file</span>' : ''}</b><span>${[v.br ? Math.round(v.br / 1000) + ' kbps' : '', mb(v.size)].filter(Boolean).join(' · ')}</span>`,
          onClick: () => addToApp({ url: v.url, fileName: `${safe(tt.desc || tt.user)}.mp4`, title: tt.desc || tt.user, referer: location.href }),
        }));
        const link = tt.user ? `https://www.tiktok.com/@${tt.user}/video/${tt.id}` : '';
        if (link) rows.push({ html: '<b>Best quality through the site engine</b><span>slower</span>', onClick: () => addToApp({ kind: 'ytdlp', url: link, referer: link, ytdlp: {}, title: tt.desc || tt.user }) });
        return setRows(rows, ((tt.desc || ('@' + tt.user) || 'Choose quality').replace(/\s+/g, ' ').slice(0, 52)) + (tt.duration ? '  ·  ' + clock(tt.duration) : ''));
      }
      const merged = new Map();
      const add = (it) => { if (it && /^https?:/i.test(it.url) && !merged.has(it.url)) merged.set(it.url, it); else if (it && merged.has(it.url)) Object.assign(merged.get(it.url), { size: merged.get(it.url).size || it.size }); };

      // 1. instantly show whatever the browser has already seen
      const { items: sniffed = [] } = await send({ type: 'candidates' });
      sniffed.forEach(add);
      if (current) add({ url: current.currentSrc || current.src || '', type: 'video/mp4', size: 0 });
      domUrls().forEach((u) => add({ url: u, type: '', size: 0, hls: /\.m3u8(\?|#|$)/i.test(u), dash: /\.mpd(\?|#|$)/i.test(u), sub: SUB_RE.test(u) }));
      const files = () => [...merged.values()].filter((x) => !x.dash && !x.hls);
      const manifests = [...merged.values()].filter((x) => x.dash || x.hls);

      // Sites whose player streams in pieces (YouTube etc.) go through the site extractor.
      let siteError = '';
      // On streaming sites the files the player already loaded are listed at once; the (slow, often blocked) page extractor is only asked when there are none.
      const ytLike = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$/i.test(location.hostname);
      if (isSiteHost && (ytLike || (hoverUrl && hoverUrl !== location.href) || permaUrl(location.href) || !files().length)) {
        setRows([{ html: 'Looking for downloadable qualities…', note: true }], 'IDM By Encodal');
        const info = await send({ type: 'probe-site', url: pageUrl });
        if (info && !info.error && info.options && (info.options.length || info.audio)) {
          const foot = info.hasFfmpeg ? '' : 'Higher qualities need ffmpeg. <a>Open IDM By Encodal</a> → Options → Site videos.';
          return setRows(siteRows(info, pageUrl, pageUrl), ((info.title || 'Choose quality').slice(0, 52) + (info.duration ? '  ·  ' + clock(info.duration) : '')), foot);
        }
        if (ytLike || !files().length) return setRows([{ html: esc((info && info.error) || 'No video found on this page.'), note: true }], 'IDM By Encodal');
        // the extractor could not read the page (slow or blocked network): fall back to the files the player already loaded
      }

      if (files().length) {
        // Show the list now, then fill in size / resolution / duration as the app answers.
        renderItems(files(), 'Choose a download');
        const probed = await send({ type: 'probe-http', urls: files().map((x) => x.url), referer: pageUrl, details: true });
        const good = [];
        for (const r of (probed && probed.results) || []) {
          const it = merged.get(r.url);
          if (!it) continue;
          if (r.error) { good.push(it); continue; }
          if (/text\/html/i.test(r.contentType || '') && !it.sub) continue; // an error page, not a video
          good.push(Object.assign(it, { size: r.size > 0 ? r.size : it.size, fileName: r.fileName, type: r.contentType || it.type, height: r.height || 0, duration: r.duration || 0, bitRate: r.bitRate || 0 }));
        }
        if (good.length) return renderItems(good, 'Choose a download');
        if (!(probed && probed.results)) return;
      }

      // Streaming manifests (DASH .mpd / HLS .m3u8): the engine lists the qualities; the page URL is the referer.
      setRows([{ html: 'Looking for downloadable qualities…', note: true }], 'IDM By Encodal');
      for (const m of manifests.slice(-3).reverse()) {
        if (m.hls) {
          const p = await send({ type: 'probe', url: m.url });
          if (p && p.master && p.variants && p.variants.length) {
            return setRows([...p.variants].sort((a, b) => b.bandwidth - a.bandwidth).map((v) => ({ html: `<b>Stream ${esc(v.label)}</b><span>${Math.round(v.bandwidth / 1000)} kbps</span>`, onClick: () => addToApp({ url: m.url, kind: 'hls', hls: { variantUrl: v.url } }) })), 'Choose a download');
          }
        }
        const info = await send({ type: 'probe-site', url: m.url, referer: pageUrl });
        if (info && !info.error && info.options && (info.options.length || info.audio)) {
          const foot = info.hasFfmpeg ? '' : 'Higher qualities need ffmpeg. <a>Open IDM By Encodal</a> → Options → Site videos.';
          return setRows(siteRows(info, m.url, pageUrl), ((document.title || info.title || 'Choose quality').slice(0, 52) + (info.duration ? '  ·  ' + clock(info.duration) : '')), foot);
        }
        if (m.hls) return setRows([{ html: `<b>Stream</b><span>HLS</span>`, onClick: () => addToApp({ url: m.url, kind: 'hls' }) }], 'Choose a download');
      }

      // Last resort: let the engine look at the page itself.
      const info = await send({ type: 'probe-site', url: pageUrl });
      if (info && !info.error && info.options && (info.options.length || info.audio)) {
        const foot = info.hasFfmpeg ? '' : 'Higher qualities need ffmpeg. <a>Open IDM By Encodal</a> → Options → Site videos.';
        return setRows(siteRows(info, pageUrl, pageUrl), ((info.title || 'Choose quality').slice(0, 52) + (info.duration ? '  ·  ' + clock(info.duration) : '')), foot);
      }
      siteError = info && info.error;
      setRows([{ html: esc(siteError && !/^No video found|No downloadable video was found/.test(siteError) ? siteError : 'No downloadable video was detected yet. Let the video play for a few seconds, then click this button again.'), note: true }], 'IDM By Encodal');
    } catch (e) {
      setRows([{ html: esc(e.message || String(e)), note: true }], 'IDM By Encodal');
    } finally {
      busy = false;
    }
  };

  $('main').addEventListener('click', () => { if (panelOpen && autoOpened) { autoOpened = false; return; } autoOpened = false; open(); });
  $('x').addEventListener('click', (e) => { e.stopPropagation(); if (current) dismissed.add(current); hide(); });

  const attach = () => { if (document.documentElement && !host.isConnected) document.documentElement.appendChild(host); };
  attach();
  new MutationObserver(attach).observe(document, { childList: true });
})();
