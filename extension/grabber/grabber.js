'use strict';
const ext = globalThis.browser ?? globalThis.chrome;
const $ = (s) => document.querySelector(s);
const send = (m) => new Promise((res) => ext.runtime.sendMessage(m, (r) => res(r || { error: ext.runtime.lastError?.message })));
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const tabId = Number(new URLSearchParams(location.search).get('tab'));

// file formats per category
const CATS = [
  { id: 'all', name: 'All files' },
  { id: 'image', name: 'Images', ext: 'jpg jpeg jpe jfif png apng gif webp svg bmp tif tiff ico cur heic heif avif' },
  { id: 'video', name: 'Videos', ext: 'mp4 mkv avi mov wmv flv webm m4v mpeg mpg 3gp 3g2 ogv m3u8 mpd ts vob mts f4v' },
  { id: 'audio', name: 'Audio', ext: 'mp3 wav aac flac m4a ogg oga wma aiff aif opus mid midi amr' },
  { id: 'doc', name: 'Documents', ext: 'pdf doc docx docm dot dotx xls xlsx xlsm xlsb ppt pptx pptm odt ods odp rtf txt csv epub mobi azw3 pages numbers key' },
  { id: 'archive', name: 'Archives', ext: 'zip rar 7z tar gz tgz bz2 xz zst iso img lz cab' },
  { id: 'app', name: 'Apps', ext: 'exe msi apk aab xapk dmg pkg deb rpm appimage jar bat sh ipa' },
  { id: 'code', name: 'Code & data', ext: 'html htm xhtml css scss js mjs cjs jsx tsx json jsonld xml xsl sql py php java c cpp h hpp cs go rs rb yaml yml toml ini log md map wasm webmanifest' },
  { id: 'other', name: 'Other' },
  { id: 'net', name: 'More formats' },
  { id: 'links', name: 'Links' },
];
const EXT_CAT = {};
for (const c of CATS) for (const e of (c.ext || '').split(' ').filter(Boolean)) EXT_CAT[e] = c.id;
const HINT_CAT = { img: 'image', image: 'image', video: 'video', audio: 'audio', script: 'code', css: 'code', fetch: 'code', xmlhttprequest: 'code', beacon: 'other', font: 'other', track: 'doc' };
const extOf = (u) => { try { return (/\.([a-z0-9]{1,8})$/i.exec(new URL(u).pathname) || [])[1]?.toLowerCase() || ''; } catch { return ''; } };
const nameOf = (u) => { try { const p = new URL(u); return decodeURIComponent(p.pathname.split('/').pop() || '') || p.hostname; } catch { return u; } };
const catOf = (u, hint) => {
  const e = extOf(u);
  if (e === 'ts' && hint === 'script') return 'code';
  if (EXT_CAT[e]) return EXT_CAT[e];
  return HINT_CAT[hint] || 'other';
};

let page = { title: '', url: '' };
let items = [];            // every file: { url, cat, ext, w, h, text, size, kind }
let links = [];            // page links
let tab = new URLSearchParams(location.search).get('cat') || 'image';
let net = [];
const selected = new Set();
const dims = new Map();

async function frames() {
  try { const f = await ext.webNavigation.getAllFrames({ tabId }); if (f && f.length) return f.map((x) => x.frameId); } catch { /* permission missing */ }
  return [0];
}

async function load() {
  $('#page').textContent = 'Reading the page…';
  const t = await ext.tabs.get(tabId);
  const map = new Map();
  const linkMap = new Map();
  const put = (o) => {
    const cur = map.get(o.url);
    if (!cur) { map.set(o.url, o); return; }
    if (o.w && !cur.w) { cur.w = o.w; cur.h = o.h; }
    if (o.cat === 'image' && cur.cat !== 'image' && cur.hintOnly) { cur.cat = 'image'; }
    if (o.text && !cur.text) cur.text = o.text;
    cur.hintOnly = cur.hintOnly && o.hintOnly;
  };
  const collectP = send({ type: 'collect', tabId });
  send({ type: 'others', tabId }).then((o) => { net = (o.items || []).map((x) => ({ url: x.url, cat: 'net', ext: extOf(x.url), kind: x.type, size: x.size, text: nameOf(x.url) + (x.size ? ' · ' + (x.size / 1048576).toFixed(1) + ' MB' : '') + ' · ' + x.type })); render(); });
  const results = await Promise.all((await frames()).map((frameId) => ext.tabs.sendMessage(tabId, { type: 'vg-assets' }, { frameId }).catch(() => null)));
  for (const r of results) {
    if (!r) continue;
    for (const i of r.images) put({ url: i.url, cat: 'image', ext: extOf(i.url), w: i.w, h: i.h, text: i.alt });
    for (const x of r.resources || []) put({ url: x.url, cat: catOf(x.url, x.t), ext: extOf(x.url), hintOnly: !EXT_CAT[extOf(x.url)] });
    for (const l of r.links) {
      if (!linkMap.has(l.url)) linkMap.set(l.url, l);
      const e = extOf(l.url);
      if (EXT_CAT[e] && e !== 'html' && e !== 'htm') put({ url: l.url, cat: EXT_CAT[e], ext: e, text: l.text });
    }
  }
  // show the page's files right away; the detected streams join a moment later
  items = [...map.values()];
  links = [...linkMap.values()];
  page = { title: t.title, url: t.url };
  $('#page').textContent = t.title && t.title !== t.url ? t.title + ' — ' + t.url : t.url;
  render();
  const c = await collectP;
  for (const e of c.entries || []) for (const f of e.files || (e.url ? [{ url: e.url }] : [])) {
    const o = map.get(f.url);
    if (o) { o.cat = o.cat === 'other' || o.cat === 'code' ? 'video' : o.cat; o.size = f.size || o.size; o.text = o.text || e.title; o.kind = e.kind; }
    else map.set(f.url, { url: f.url, cat: /\.(mp3|m4a|aac|opus|ogg|flac|wav)(\?|$)/i.test(f.url) ? 'audio' : 'video', ext: extOf(f.url), size: f.size || 0, text: e.title, kind: e.kind });
  }
  if ((c.entries || []).length) { items = [...map.values()]; render(); }
}

async function scan() {
  const b = $('#scan');
  b.disabled = true; b.textContent = 'Scanning…';
  try { await ext.tabs.sendMessage(tabId, { type: 'vg-scroll' }, { frameId: 0 }); } catch { /* page not scriptable */ }
  await new Promise((r) => setTimeout(r, 600));
  await load();
  b.disabled = false; b.textContent = 'Scan whole page';
  toast(`Found ${items.length} files`);
}

const matches = (u, text) => !$('#q').value || (u + ' ' + (text || '')).toLowerCase().includes($('#q').value.toLowerCase());
const inTab = (t) => (t === 'all' ? items : t === 'links' ? links : t === 'net' ? net : items.filter((i) => i.cat === t));

function visible() {
  const ty = $('#ftype').value;
  let list = inTab(tab).filter((i) => matches(i.url, i.text)).filter((i) => !ty || (extOf(i.url) || 'other') === ty);
  if (tab === 'image') {
    const min = Number($('#minSize').value);
    list = list.filter((i) => { const d = dims.get(i.url) || [i.w || 0, i.h || 0]; return !min || !d[0] || Math.max(d[0], d[1]) >= min; });
    if ($('#sort').value === 'size') { const area = (i) => { const d = dims.get(i.url) || [i.w || 0, i.h || 0]; return d[0] * d[1]; }; list = [...list].sort((a, b) => area(b) - area(a)); }
  }
  return list;
}

function buildTabs() {
  const nav = $('#tabs');
  if (!nav.children.length) {
    for (const c of CATS) { const b = document.createElement('button'); b.dataset.t = c.id; b.innerHTML = `${esc(c.name)} <i id="n-${c.id}">0</i>`; b.onclick = () => { tab = c.id; render(); }; nav.appendChild(b); }
  }
  for (const c of CATS) {
    $('#n-' + c.id).textContent = inTab(c.id).length;
    nav.querySelector(`[data-t="${c.id}"]`).classList.toggle('on', c.id === tab);
  }
}

let renderToken = 0;
function render() {
  buildTabs();
  const grid = $('#grid');
  const list = visible();
  $('#sizeBox').hidden = $('#sortBox').hidden = tab !== 'image';
  const pool = inTab(tab);
  const types = [...new Set(pool.map((i) => extOf(i.url) || 'other'))].sort();
  const cur = $('#ftype').value;
  $('#ftype').innerHTML = '<option value="">All</option>' + types.map((x) => `<option ${x === cur ? 'selected' : ''}>${esc(x)}</option>`).join('');
  $('#count').textContent = list.length === pool.length ? `${list.length} shown` : `${list.length} of ${pool.length} shown`;
  grid.className = tab === 'image' ? 'imgs' : '';
  grid.innerHTML = '';
  if (!list.length) { grid.innerHTML = '<div class="empty">Nothing found here. Try "Scan whole page" to load lazy content.</div>'; refresh(); return; }
  const token = ++renderToken;
  let idx = 0;
  const batch = () => {
  if (token !== renderToken) return;
  const frag = document.createDocumentFragment();
  for (const it of list.slice(idx, idx += 80)) {
    const el = document.createElement('div');
    el.dataset.url = it.url;
    const on = selected.has(it.url);
    if (tab === 'image') {
      el.className = 'img' + (on ? ' sel' : '');
      el.innerHTML = `<input type="checkbox" ${on ? 'checked' : ''}><div class="pic"><img loading="lazy" decoding="async" referrerpolicy="no-referrer" src="${esc(it.url)}" alt=""></div><div class="meta"><b title="${esc(it.url)}">${esc(nameOf(it.url))}</b><span class="dim">${it.w ? it.w + ' × ' + it.h : '…'}</span></div>`;
      const img = el.querySelector('img');
      img.onload = () => { dims.set(it.url, [img.naturalWidth, img.naturalHeight]); el.querySelector('.dim').textContent = `${img.naturalWidth} × ${img.naturalHeight}`; };
      img.onerror = () => { el.querySelector('.pic').textContent = 'No preview'; el.querySelector('.dim').textContent = (extOf(it.url) || '').toUpperCase(); };
    } else {
      const catName = (CATS.find((c) => c.id === it.cat) || {}).name || '';
      el.className = 'row' + (on ? ' sel' : '');
      el.innerHTML = `<input type="checkbox" ${on ? 'checked' : ''}><div class="n"><b>${esc(it.text || nameOf(it.url))}</b><small>${esc(it.url)}</small></div>${tab === 'all' ? `<span class="tag cat">${esc(catName)}</span>` : ''}<span class="tag">${esc(extOf(it.url) || it.kind || 'file')}</span>`;
    }
    el.onclick = (e) => {
      const v = e.target.tagName === 'INPUT' ? e.target.checked : !selected.has(it.url);
      if (v) selected.add(it.url); else selected.delete(it.url);
      el.classList.toggle('sel', v);
      el.querySelector('input').checked = v;
      refresh();
    };
    frag.appendChild(el);
  }
  grid.appendChild(frag);
  if (idx < list.length) requestAnimationFrame(batch);
  };
  batch();
  refresh();
}

function refresh() {
  const vis = visible();
  const n = vis.filter((v) => selected.has(v.url)).length;
  $('#go').disabled = !n;
  $('#go').textContent = n ? `Download selected (${n})` : 'Download selected';
  $('#all').checked = vis.length > 0 && n === vis.length;
}

function toast(t) { const m = $('#msg'); m.textContent = t; m.hidden = false; setTimeout(() => (m.hidden = true), 2600); }

$('#q').oninput = render;
$('#minSize').onchange = render;
$('#ftype').onchange = render;
$('#sort').onchange = render;
$('#scan').onclick = scan;
$('#reload').onclick = load;
$('#all').onchange = (e) => { for (const v of visible()) (e.target.checked ? selected.add(v.url) : selected.delete(v.url)); render(); };
$('#go').onclick = async () => {
  const urls = visible().filter((v) => selected.has(v.url)).map((v) => v.url);
  $('#go').disabled = true;
  const r = await send({ type: 'download-many', tabId, urls });
  toast(r.error ? 'Failed: ' + r.error : `Sent ${urls.length} item${urls.length > 1 ? 's' : ''} to IDM By Encodal`);
  selected.clear();
  render();
};
load();
