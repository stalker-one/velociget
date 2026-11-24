'use strict';
const path = require('path');
const { fetchFollow } = require('./http');

const PAGE_EXT = new Set(['', 'html', 'htm', 'php', 'asp', 'aspx', 'jsp', 'cgi']);
const MAX_HTML = 5 * 1024 * 1024;

function decodeEntities(s) {
  return s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

function extractLinks(html, base) {
  const out = new Set();
  const re = /\b(?:href|src|data-src|data-href|data-url)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/gi;
  let m;
  while ((m = re.exec(html))) {
    const raw = decodeEntities((m[1] ?? m[2] ?? m[3] ?? '').trim());
    if (!raw || /^(javascript|mailto|tel|data|blob):/i.test(raw) || raw.startsWith('#')) continue;
    try {
      const u = new URL(raw, base);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') continue;
      u.hash = '';
      out.add(u.toString());
    } catch {
      /* skip malformed */
    }
  }
  return [...out];
}

const extOf = (url) => path.extname(new URL(url).pathname).slice(1).toLowerCase();

async function fetchHtml(url, { headers, signal, dispatcher }) {
  const { res } = await fetchFollow(url, { headers: { ...headers, accept: 'text/html,*/*;q=0.5' }, signal, dispatcher });
  const type = String(res.headers['content-type'] || '');
  if (res.statusCode !== 200 || !/html|xml|text/i.test(type)) {
    res.body.destroy();
    return null;
  }
  const parts = [];
  let size = 0;
  for await (const c of res.body) {
    size += c.length;
    if (size > MAX_HTML) break;
    parts.push(c);
  }
  res.body.destroy();
  return Buffer.concat(parts).toString('utf8');
}

/**
 * Site grabber: crawl `startUrl` (up to `depth` link levels) and collect file links.
 * `exts` restricts to extensions (empty = any non-page file); `pattern` is an optional regex on the URL.
 */
async function grab({ startUrl, depth = 1, sameHost = true, exts = [], pattern = '', maxPages = 50, headers = {}, signal, dispatcher }) {
  const want = new Set(exts.map((e) => e.toLowerCase().replace(/^\./, '')).filter(Boolean));
  const rx = pattern ? new RegExp(pattern, 'i') : null;
  const host = new URL(startUrl).host;
  const seen = new Set();
  const files = new Set();
  const queue = [{ url: startUrl, d: 0 }];
  while (queue.length && seen.size < maxPages) {
    const { url, d } = queue.shift();
    if (seen.has(url)) continue;
    seen.add(url);
    let html;
    try {
      html = await fetchHtml(url, { headers, signal, dispatcher });
    } catch (e) {
      if (signal && signal.aborted) throw e;
      continue;
    }
    if (!html) continue;
    for (const link of extractLinks(html, url)) {
      const ext = extOf(link);
      const isPage = PAGE_EXT.has(ext);
      if (!isPage && (!want.size || want.has(ext)) && (!rx || rx.test(link))) files.add(link);
      else if (want.has(ext) && (!rx || rx.test(link))) files.add(link);
      else if (isPage && d < depth && (!sameHost || new URL(link).host === host)) queue.push({ url: link, d: d + 1 });
    }
  }
  return { files: [...files], pagesScanned: seen.size };
}

module.exports = { grab, extractLinks };
