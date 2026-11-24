'use strict';
// Runs in the page's MAIN world. Capture only HLS playlist text that the page itself successfully
// received. The extension's isolated-world relay forwards it so the local manager can list variants
// even when a signed playlist URL cannot be fetched a second time.
(() => {
  const FLAG = '__vgHlsHook';
  if (window[FLAG]) return;
  try { Object.defineProperty(window, FLAG, { value: true }); } catch { return; }

  const MAX_CHARS = 256 * 1024;
  const EVENT = '__vg_manifest';
  const PATH = /\.m3u8(?:$|[/;])/i;
  const MPEGURL = /mpegurl/i;
  const SIGNATURE = /^\uFEFF?\s*#EXTM3U/;
  const NativeURL = URL;
  const NativeEventTarget = EventTarget.prototype;
  const NativeCustomEvent = CustomEvent;
  const NativeTextDecoder = TextDecoder;
  const addListener = NativeEventTarget.addEventListener;
  const dispatch = NativeEventTarget.dispatchEvent;
  const stringify = JSON.stringify;

  const MAYBE_TEXT = /^(text\/(plain|html)?|application\/(octet-stream|binary|x-binary|force-download))?$/i; // playlists are often served with a generic type; only the first bytes are read, and only when it starts with #EXTM3U
  const looksHls = (url, type) => {
    if (MPEGURL.test(type || '')) return true;
    if (MAYBE_TEXT.test((type || '').split(';')[0].trim())) return true;
    try { return PATH.test(new NativeURL(url, location.href).pathname); } catch { return false; }
  };
  const report = (url, body) => {
    if (!url || typeof body !== 'string' || body.length > MAX_CHARS || !SIGNATURE.test(body.slice(0, 64))) return;
    try { dispatch.call(document, new NativeCustomEvent(EVENT, { detail: stringify({ url, body }) })); } catch { /* detached document */ }
  };
  const readCapped = async (response) => {
    const reader = response.body && response.body.getReader();
    if (!reader) return null;
    const decoder = new NativeTextDecoder();
    let text = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      text += decoder.decode(value, { stream: true });
      if (text.length > MAX_CHARS || (text.length >= 64 && !SIGNATURE.test(text.slice(0, 64)))) {
        reader.cancel().catch(() => {});
        return null;
      }
    }
    return text + decoder.decode();
  };

  const nativeFetch = window.fetch;
  if (typeof nativeFetch === 'function') {
    window.fetch = function (...args) {
      const promise = nativeFetch.apply(this, args);
      try {
        promise.then((response) => {
          if (!response || !response.ok || response.bodyUsed || !looksHls(response.url, response.headers.get('content-type'))) return;
          const length = Number(response.headers.get('content-length'));
          if (length > MAX_CHARS) return;
          const url = response.url;
          readCapped(response.clone()).then((body) => report(url, body), () => {});
        }, () => {});
      } catch { /* never alter the page's fetch result */ }
      return promise;
    };
  }

  const xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  const nativeSend = xhrProto && xhrProto.send;
  if (typeof nativeSend === 'function') {
    xhrProto.send = function (...args) {
      addListener.call(this, 'load', () => {
        try {
          if (this.status < 200 || this.status >= 300) return;
          if (!looksHls(this.responseURL, this.getResponseHeader('content-type'))) return;
          let body = null;
          if (this.responseType === '' || this.responseType === 'text') body = this.responseText;
          else if (this.responseType === 'arraybuffer' && this.response && this.response.byteLength <= MAX_CHARS) body = new NativeTextDecoder().decode(this.response);
          report(this.responseURL, body);
        } catch { /* ignore non-text responses */ }
      });
      return nativeSend.apply(this, args);
    };
  }
})();

// ---- alternate files named inside the player's own JSON (play-info documents) -------------------------
// Players often fetch a JSON document that lists every quality (mp4 / m3u8 / mpd) and subtitle, while the
// browser itself only requests the one being played. Those URLs are read from such responses (never from
// scripts or HTML) and handed to the extension through a DOM event, as XDM-style "all qualities" lists need.
(() => {
  if (window.__vgJsonHook) return;
  try { Object.defineProperty(window, '__vgJsonHook', { value: true }); } catch { return; }
  const RE = /https?:(?:\\?\/){2}[^\s"'<>\\)]+?\.(?:mp4|m4v|webm|mkv|mov|flv|mp3|m4a|m3u8|mpd|srt|vtt|ass|ssa)(?:\?[^\s"'<>\\)]*)?(?=["'<>\s\\)]|$)/gi;
  const clean = (u) => u.replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
  const seen = new Set();
  let queue = [];
  let timer = 0;
  const flush = () => {
    timer = 0;
    if (!queue.length) return;
    const urls = queue;
    queue = [];
    try {
      const all = JSON.parse(document.documentElement.getAttribute('data-vg-found') || '[]').concat(urls).slice(-200);
      document.documentElement.setAttribute('data-vg-found', JSON.stringify(all));
    } catch { /* ignore */ }
    try { document.dispatchEvent(new CustomEvent('__vg_found', { detail: JSON.stringify(urls) })); } catch { /* ignore */ }
  };
  const scan = (text) => {
    if (typeof text !== 'string' || text.length > 4e6) return;
    const found = text.replace(/\\u002F/gi, '/').replace(/\\\//g, '/').match(RE);
    if (!found) return;
    for (const raw of found) {
      const u = clean(raw);
      if (seen.has(u)) continue;
      seen.add(u);
      queue.push(u);
    }
    if (queue.length && !timer) timer = setTimeout(flush, 150);
  };
  const jsonish = (type) => /json|text\/plain|javascript/i.test(type || '');
  const of = window.fetch;
  if (typeof of === 'function') {
    window.fetch = function (...a) {
      const p = of.apply(this, a);
      p.then((res) => { try { if (jsonish(res.headers.get('content-type'))) res.clone().text().then(scan, () => {}); } catch { /* ignore */ } }, () => {});
      return p;
    };
  }
  const proto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  const nativeSend = proto && proto.send;
  if (typeof nativeSend === 'function') {
    proto.send = function (...a) {
      this.addEventListener('load', () => {
        try { if ((this.responseType === '' || this.responseType === 'text') && jsonish(this.getResponseHeader('content-type'))) scan(this.responseText); } catch { /* ignore */ }
      });
      return nativeSend.apply(this, a);
    };
  }
})();

// ---- TikTok: the player's own feed data (id, caption and every encoded file of each video) ---------------------------
// TikTok's feed requests list, for every video, its caption and all its files. Reading that is instant (nothing is
// re-fetched from TikTok) and tells the extension exactly which video is under the pointer and which files it has.
(() => {
  if (!/(^|\.)tiktok\.com$/i.test(location.hostname) || window.__vgTikTok) return;
  try { Object.defineProperty(window, '__vgTikTok', { value: true }); } catch { return; }
  const sent = new Set();
  const itemsOf = (root) => {
    const out = [];
    let nodes = 0;
    const walk = (o, d) => {
      if (!o || typeof o !== 'object' || d > 9 || ++nodes > 40000) return;
      if (Array.isArray(o)) { for (const x of o) walk(x, d + 1); return; }
      const v = o.video;
      if (v && typeof v === 'object' && o.id && (v.bitrateInfo || v.playAddr || v.downloadAddr)) {
        const variants = [];
        const seen = new Set();
        const add = (url, h, w, size, br, codec, label) => {
          if (!url || typeof url !== 'string' || !/^https?:/i.test(url) || seen.has(url)) return;
          seen.add(url);
          variants.push({ url, h: Number(h) || 0, w: Number(w) || 0, size: Number(size) || 0, br: Number(br) || 0, codec: codec || '', label: label || '' });
        };
        for (const b of v.bitrateInfo || []) {
          const pa = b.PlayAddr || {};
          const gear = /(\d{3,4})/.exec(b.GearName || '');
          add((pa.UrlList || [])[0], pa.Height || (gear && gear[1]) || 0, pa.Width, pa.DataSize, b.Bitrate, /265|hevc/i.test(b.CodecType || '') ? 'H.265' : 'H.264', b.QualityType);
        }
        add(v.downloadAddr, v.height, v.width, 0, 0, '', 'download');
        add(v.playAddr, v.height, v.width, 0, 0, '', 'play');
        const a = o.author;
        out.push({ id: String(o.id), user: typeof a === 'string' ? a : (a && (a.uniqueId || a.nickname)) || '', desc: String(o.desc || ''), duration: Number(v.duration || (o.music && 0)) || 0, w: Number(v.width) || 0, h: Number(v.height) || 0, variants });
        return;
      }
      for (const k in o) walk(o[k], d + 1);
    };
    walk(root, 0);
    return out;
  };
  const emit = (root) => {
    try {
      const fresh = itemsOf(root).filter((i) => i.variants.length && !sent.has(i.id + ':' + i.variants.length));
      if (!fresh.length) return;
      fresh.forEach((i) => sent.add(i.id + ':' + i.variants.length));
      document.dispatchEvent(new CustomEvent('__vg_tt', { detail: JSON.stringify(fresh.slice(0, 60)) }));
    } catch { /* ignore */ }
  };
  const fromText = (t) => { try { emit(JSON.parse(t)); } catch { /* not JSON */ } };
  const f0 = window.fetch;
  if (typeof f0 === 'function') {
    window.fetch = function (...a) {
      const p = f0.apply(this, a);
      p.then((res) => { try { if (/json/i.test(res.headers.get('content-type') || '')) res.clone().text().then(fromText, () => {}); } catch { /* ignore */ } }, () => {});
      return p;
    };
  }
  const xs = window.XMLHttpRequest && window.XMLHttpRequest.prototype.send;
  if (xs) {
    window.XMLHttpRequest.prototype.send = function (...a) {
      this.addEventListener('load', () => { try { if ((this.responseType === '' || this.responseType === 'text') && /json/i.test(this.getResponseHeader('content-type') || '')) fromText(this.responseText); } catch { /* ignore */ } });
      return xs.apply(this, a);
    };
  }
  const boot = () => { const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__') || document.getElementById('SIGI_STATE'); if (el) fromText(el.textContent); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
