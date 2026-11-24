'use strict';
/* global api */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const CATS = ['Compressed', 'Documents', 'Music', 'Programs', 'Video'];
const GLYPH = { Video: '▶', Music: '♪', Compressed: 'Z', Documents: 'D', Programs: '▣', General: '•' };
const RUNNING = new Set(['probing', 'downloading', 'merging']);

// ---- toolbar icons (own artwork) -----------------------------------------------------------------
const svg = (body) => `<svg viewBox="0 0 32 32">${body}</svg>`;
const circ = (c, inner) => svg(`<circle cx="16" cy="16" r="12.5" fill="${c}" stroke="rgba(0,0,0,.25)"/>${inner}`);
const ICONS = {
  add: circ('#35a853', '<path d="M16 9v14M9 16h14" stroke="#fff" stroke-width="3.2" stroke-linecap="round"/>'),
  resume: circ('#2f7de1', '<path d="M13 10l9 6-9 6z" fill="#fff"/>'),
  stop: circ('#e8892b', '<rect x="11" y="11" width="10" height="10" rx="1.5" fill="#fff"/>'),
  stopall: circ('#d64545', '<rect x="9.5" y="11" width="5" height="10" rx="1" fill="#fff"/><rect x="17.5" y="11" width="5" height="10" rx="1" fill="#fff"/>'),
  delete: svg('<path d="M9 11h14l-1.2 15H10.2z" fill="#8a94a6" stroke="#5f6b7f"/><rect x="7.5" y="8" width="17" height="3" rx="1" fill="#5f6b7f"/><rect x="13" y="5" width="6" height="3.5" rx="1" fill="#5f6b7f"/><path d="M13 14v9M16 14v9M19 14v9" stroke="#e9edf3" stroke-width="1.3"/>'),
  deletec: svg('<path d="M8 12h13l-1.1 14H9.1z" fill="#8a94a6" stroke="#5f6b7f"/><rect x="6.5" y="9" width="16" height="3" rx="1" fill="#5f6b7f"/><circle cx="23" cy="22" r="7" fill="#35a853" stroke="#fff" stroke-width="1.4"/><path d="M19.6 22.2l2.4 2.4 4-4.6" stroke="#fff" stroke-width="2.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'),
  options: svg('<circle cx="16" cy="16" r="9.5" fill="none" stroke="#6b7a90" stroke-width="4.5" stroke-dasharray="3.1 2.6"/><circle cx="16" cy="16" r="7" fill="#8b99ae" stroke="#5f6b7f"/><circle cx="16" cy="16" r="3" fill="#f0f0f0"/>'),
  scheduler: svg('<circle cx="16" cy="16" r="12" fill="#fff" stroke="#4a78c2" stroke-width="2.4"/><path d="M16 9v7l5 3" stroke="#2a3b57" stroke-width="2.2" fill="none" stroke-linecap="round"/>'),
  startq: svg('<rect x="4" y="5" width="24" height="22" rx="3" fill="#35a853" stroke="rgba(0,0,0,.25)"/><path d="M13 11l9 5-9 5z" fill="#fff"/><path d="M7 11h3M7 16h3M7 21h3" stroke="#fff" stroke-width="2" stroke-linecap="round"/>'),
  stopq: svg('<rect x="4" y="5" width="24" height="22" rx="3" fill="#d64545" stroke="rgba(0,0,0,.25)"/><rect x="13" y="12" width="9" height="9" rx="1" fill="#fff"/><path d="M7 11h3M7 16h3M7 21h3" stroke="#fff" stroke-width="2" stroke-linecap="round"/>'),
  grabber: svg('<circle cx="14" cy="14" r="8" fill="#e8f2ff" stroke="#4a78c2" stroke-width="2.6"/><path d="M20 20l8 8" stroke="#4a78c2" stroke-width="3.6" stroke-linecap="round"/><path d="M10.5 14h7M14 10.5v7" stroke="#4a78c2" stroke-width="1.8" stroke-linecap="round"/>'),
};
const TOOLBAR = [
  ['newMenu', 'add', 'New ▾'], ['resume', 'resume', 'Resume'], ['stop', 'stop', 'Stop'], ['stopAll', 'stopall', 'Stop All'], '|',
  ['delete', 'delete', 'Delete'], ['deleteCompleted', 'deletec', 'Delete C.'], '|',
  ['options', 'options', 'Options'], ['scheduler', 'scheduler', 'Scheduler'], ['startQueue', 'startq', 'Start Queue'], ['stopQueue', 'stopq', 'Stop Queue'], '|',
  ['grabber', 'grabber', 'Grabber'],
];

// ---- state ---------------------------------------------------------------------------------------------
let downloads = [];
let settings = null;
let selection = new Set();
let filter = { type: 'all', value: '' };
let sort = { key: 'addedAt', dir: -1 };
let query = '';
const collapsed = new Set(JSON.parse(localStorage.getItem('vg-collapsed') || '[]'));

const COLS = [
  { key: 'fileName', label: 'File name', w: 320 },
  { key: 'queueId', label: 'Q', w: 44 },
  { key: 'total', label: 'Size', w: 92, num: true },
  { key: 'progress', label: 'Status', w: 140 },
  { key: 'eta', label: 'Time left', w: 100 },
  { key: 'speed', label: 'Transfer rate', w: 124 },
  { key: 'lastTry', label: 'Last try date', w: 160 },
  { key: 'description', label: 'Description', w: 220 },
];
try {
  const saved = JSON.parse(localStorage.getItem('vg-cols2') || 'null');
  if (saved) COLS.forEach((c, i) => saved[i] && (c.w = saved[i]));
} catch { /* defaults */ }

// ---- formatting -----------------------------------------------------------------------------------------
const fmtSize = (n) => {
  if (n == null || n < 0) return '';
  const u = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < 4) { n /= 1024; i++; }
  return `${i ? n.toFixed(n < 10 ? 2 : 1) : Math.round(n)} ${u[i]}`;
};
const fmtRate = (n) => (n > 0 ? `${fmtSize(n).replace('Bytes', 'B')}/sec` : '');
const fmtEta = (s) => {
  if (s == null) return '';
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return d ? `${d} day ${h} hour` : h ? `${h} hour ${m} min` : m ? `${m} min ${sec} sec` : `${sec} sec`;
};
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const fmtDate = (t) => {
  if (!t) return '';
  const d = new Date(t), p = (n) => String(n).padStart(2, '0');
  return `${MON[d.getMonth()]} ${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())} ${d.getFullYear()}`;
};
const progressOf = (d) => (d.total > 0 ? Math.min(100, (d.downloaded / d.total) * 100) : d.state === 'completed' ? 100 : 0);
const statusText = (d) => {
  if (d.state === 'completed') return 'Complete';
  if (d.state === 'error') return 'Error';
  if (d.state === 'probing') return 'Connecting…';
  if (d.state === 'merging') return 'Merging…';
  if (d.state === 'queued') return 'Queued';
  if (d.downloaded > 0 && d.total > 0) return progressOf(d).toFixed(2) + '%';
  return d.state === 'paused' ? '' : d.state === 'canceled' ? 'Canceled' : '';
};
const catOf = (d) => d.category || 'General';

// ---- toolbar & tree ----------------------------------------------------------------------------------------
$('#toolbar').innerHTML =
  TOOLBAR.map((t) => (t === '|' ? '<span class="tsep"></span>' : `<button class="tbtn" data-cmd="${t[0]}">${ICONS[t[1]]}<span>${t[2]}</span></button>`)).join('') +
  '<input id="search" class="search" type="search" placeholder="Search…" />';
$('#toolbar').addEventListener('click', (e) => {
  const b = e.target.closest('.tbtn');
  if (b) runCommand(b.dataset.cmd);
});
$('#search').addEventListener('input', (e) => { query = e.target.value.trim().toLowerCase(); renderRows(); });

function renderTree() {
  const count = (fn) => downloads.filter(fn).length;
  const node = (type, value, label, n, lvl, key, hasKids) =>
    `<div class="node lvl${lvl} ${filter.type === type && filter.value === value ? 'active' : ''}" data-type="${type}" data-value="${esc(value)}">` +
    (hasKids ? `<span class="arrow" data-k="${key}">${collapsed.has(key) ? '▸' : '▾'}</span>` : '<span class="arrow"></span>') +
    `<span class="fld"></span>${esc(label)}<small>${n}</small></div>`;
  let h = '<div class="hd">Categories</div>';
  h += node('all', '', 'All Downloads', downloads.length, 0, 'all', true);
  if (!collapsed.has('all')) h += CATS.map((c) => node('cat', c, c, count((d) => catOf(d) === c), 1, c, false)).join('');
  h += node('unfinished', '', 'Unfinished', count((d) => d.state !== 'completed'), 0, 'unf', false);
  h += node('finished', '', 'Finished', count((d) => d.state === 'completed'), 0, 'fin', false);
  h += node('queues', '', 'Queues', settings?.queues.length || 1, 0, 'q', true);
  if (!collapsed.has('q')) h += (settings?.queues || []).map((q) => node('queue', q.id, q.name, count((d) => d.queueId === q.id), 1, q.id, false)).join('');
  if (renderTree.last !== h) { renderTree.last = h; $('#tree').innerHTML = h; }
}
$('#tree').addEventListener('click', (e) => {
  const arrow = e.target.closest('.arrow[data-k]');
  if (arrow) {
    const k = arrow.dataset.k;
    collapsed.has(k) ? collapsed.delete(k) : collapsed.add(k);
    localStorage.setItem('vg-collapsed', JSON.stringify([...collapsed]));
    return renderTree();
  }
  const n = e.target.closest('.node');
  if (!n || n.dataset.type === 'queues') return;
  filter = { type: n.dataset.type, value: n.dataset.value };
  renderTree();
  renderRows();
});

// ---- list --------------------------------------------------------------------------------------------------------
function visible() {
  let list = downloads.filter((d) => {
    if (filter.type === 'cat') return catOf(d) === filter.value;
    if (filter.type === 'unfinished') return d.state !== 'completed';
    if (filter.type === 'finished') return d.state === 'completed';
    if (filter.type === 'queue') return d.queueId === filter.value;
    return true;
  });
  if (query) list = list.filter((d) => (d.fileName + ' ' + d.url + ' ' + (d.description || '')).toLowerCase().includes(query));
  const k = sort.key;
  const val = (d) => (k === 'progress' ? progressOf(d) : d[k] ?? '');
  return list.sort((a, b) => (val(a) > val(b) ? 1 : val(a) < val(b) ? -1 : 0) * sort.dir);
}

function renderHead() {
  $('#cols').innerHTML = COLS.map((c) => `<col style="width:${c.w}px">`).join('');
  $('#head').innerHTML = COLS.map((c, i) => `<th data-sort="${c.key}" data-i="${i}" class="${c.num ? 'num' : ''}">${c.label}${sort.key === c.key ? (sort.dir > 0 ? ' ▲' : ' ▼') : ''}<span class="rz" data-i="${i}"></span></th>`).join('');
}
$('#head').addEventListener('click', (e) => {
  if (e.target.classList.contains('rz')) return;
  const k = e.target.closest('th')?.dataset.sort;
  if (!k) return;
  sort = { key: k, dir: sort.key === k ? -sort.dir : 1 };
  renderHead();
  renderRows();
});
$('#head').addEventListener('mousedown', (e) => {
  if (!e.target.classList.contains('rz')) return;
  const i = Number(e.target.dataset.i);
  const x0 = e.clientX, w0 = COLS[i].w;
  const move = (ev) => { COLS[i].w = Math.max(24, w0 + ev.clientX - x0); $('#cols').children[i].style.width = COLS[i].w + 'px'; };
  const up = () => { removeEventListener('mousemove', move); removeEventListener('mouseup', up); localStorage.setItem('vg-cols2', JSON.stringify(COLS.map((c) => c.w))); };
  addEventListener('mousemove', move);
  addEventListener('mouseup', up);
  e.preventDefault();
});

const rowEls = new Map();
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };

/** Keyed update: rows are created once and only changed cells are touched, so scrolling, hover and selection stay smooth. */
function renderRows() {
  const list = visible();
  $('#empty').hidden = list.length > 0;
  const tbody = $('#rows');
  const keep = new Set();
  let prev = null;
  for (const d of list) {
    keep.add(d.id);
    let tr = rowEls.get(d.id);
    if (!tr) {
      tr = document.createElement('tr');
      tr.dataset.id = d.id;
      tr.innerHTML = '<td></td><td></td><td class="num"></td><td class="stcell"></td><td></td><td></td><td></td><td></td>';
      tr._c = [];
      rowEls.set(d.id, tr);
    }
    const tds = tr.children;
    const st = statusText(d);
    const running = RUNNING.has(d.state);
    const showBar = d.state !== 'completed' && d.state !== 'error' && d.total > 0 && (d.downloaded > 0 || running);
    const cat = catOf(d);
    const c = [
      `<div class="fname" title="${esc(d.url)}"><span class="ico cat-${cat}">${GLYPH[cat]}</span><span class="nm"><b>${esc(d.fileName || d.url)}</b><small>${esc(hostOf(d.url))}</small></span></div>`,
      d.state !== 'completed' && d.queueId ? '<span class="qico" title="In a download queue"></span>' : '',
      fmtSize(d.total),
      null,
      running ? fmtEta(d.eta) : '',
      running ? fmtRate(d.speed) : '',
      fmtDate(d.lastTry),
      esc(d.description || d.error || ''),
    ];
    for (let i = 0; i < 8; i++) {
      if (i === 3) continue;
      if (tr._c[i] !== c[i]) { tds[i].innerHTML = c[i]; tr._c[i] = c[i]; }
    }
    // status cell: a persistent progress bar (width animates) or a chip
    const cell = tds[3];
    const kind = showBar ? 'bar' : 'chip:' + st + ':' + d.state;
    if (showBar) {
      if (tr._k !== 'bar') { cell.innerHTML = '<div class="prog"><i></i><b></b></div>'; tr._k = 'bar'; }
      const pct = progressOf(d);
      cell.firstChild.firstChild.style.width = pct + '%';
      cell.firstChild.lastChild.textContent = pct.toFixed(1) + '%';
      cell.firstChild.classList.toggle('busy', running);
      cell.title = '';
    } else if (tr._k !== kind) {
      tr._k = kind;
      cell.innerHTML = st ? `<span class="chip st-${st.replace(/[^A-Za-z]/g, '')}">${esc(st)}</span>` : d.state === 'paused' ? '<span class="chip st-Paused">Paused</span>' : '';
      cell.title = d.error || d.warning || '';
    }
    tr.classList.toggle('sel', selection.has(d.id));
    const expected = prev ? prev.nextSibling : tbody.firstChild;
    if (tr !== expected) tbody.insertBefore(tr, expected);
    prev = tr;
  }
  for (const [id, tr] of rowEls) if (!keep.has(id)) { tr.remove(); rowEls.delete(id); }
}

function renderStatus() {
  const active = downloads.filter((d) => RUNNING.has(d.state));
  $('#sbActive').textContent = `${active.length} active`;
  $('#sbSpeed').textContent = `${fmtRate(active.reduce((n, d) => n + d.speed, 0)) || '0 KB/sec'}`;
  $('#bSpeed').textContent = active.length ? `▼ ${$('#sbSpeed').textContent}` : '';
  const one = downloads.filter((d) => selection.has(d.id));
  $('#sbText').textContent = one.length === 1 ? one[0].error || one[0].warning || one[0].url : `${visible().length} downloads`;
}
let rafId = 0;
function scheduleRender() { if (!rafId) rafId = requestAnimationFrame(() => { rafId = 0; renderAll(); }); }
function renderAll() { renderTree(); renderRows(); renderStatus(); updateToolbar(); }

function updateToolbar() {
  const sel = selectedDownloads();
  const set = (cmd, on) => { const b = $(`.tbtn[data-cmd="${cmd}"]`); if (b) b.disabled = !on; };
  set('resume', sel.some((d) => ['paused', 'error', 'queued', 'canceled'].includes(d.state)));
  set('stop', sel.some((d) => RUNNING.has(d.state) || d.state === 'queued'));
  set('delete', sel.length > 0);
  set('stopAll', downloads.some((d) => RUNNING.has(d.state) || d.state === 'queued'));
  set('deleteCompleted', downloads.some((d) => d.state === 'completed'));
}

// ---- selection -------------------------------------------------------------------------------------------------
const selectedDownloads = () => downloads.filter((d) => selection.has(d.id));
const selectedIds = () => selectedDownloads().map((d) => d.id);
const act = (action, value) => api.action({ ids: selectedIds(), action, value });

$('#rows').addEventListener('click', (e) => {
  const tr = e.target.closest('tr');
  if (!tr) return;
  const id = tr.dataset.id;
  if (e.ctrlKey || e.metaKey) selection.has(id) ? selection.delete(id) : selection.add(id);
  else if (e.shiftKey && selection.size) {
    const ids = visible().map((d) => d.id);
    const last = ids.indexOf([...selection].pop());
    const [a, b] = [Math.min(last, ids.indexOf(id)), Math.max(last, ids.indexOf(id))];
    ids.slice(a, b + 1).forEach((x) => selection.add(x));
  } else selection = new Set([id]);
  renderRows(); renderStatus(); updateToolbar();
});
$('#listwrap').addEventListener('click', (e) => {
  if (e.target.id === 'listwrap' || e.target.id === 'empty') { selection.clear(); renderRows(); renderStatus(); updateToolbar(); }
});
$('#rows').addEventListener('dblclick', (e) => {
  const d = downloads.find((x) => x.id === e.target.closest('tr')?.dataset.id);
  if (!d) return;
  if (d.state === 'completed') api.openPath(d.savePath);
  else api.openProgress(d.id);
});

// ---- commands (toolbar, menu, keyboard) ----------------------------------------------------------------------------
const currentQueue = () => (filter.type === 'queue' ? filter.value : 'main');

function confirmRemove(withFile) {
  const ids = selectedIds();
  if (!ids.length) return;
  const done = selectedDownloads().filter((d) => d.state === 'completed').length;
  const msg = `Do you want to delete ${ids.length > 1 ? `these ${ids.length} downloads` : 'this download'} from the list?` + (withFile && done ? '\nThe downloaded files will also be deleted from disk.' : '');
  if (confirm(msg)) { act(withFile ? 'removeFile' : 'remove'); selection.clear(); }
}

function runCommand(cmd) {
  const one = selectedDownloads()[0];
  switch (cmd) {
    case 'addUrl': return openAdd();
    case 'newMenu': return showNewMenu();
    case 'batch': return openBatch();
    case 'grabber': return openGrabber();
    case 'scheduler': return openScheduler();
    case 'options': return openOptions();
    case 'extension': return openOptions('browser');
    case 'resume': return act('start');
    case 'stop': return act('pause');
    case 'stopAll': return api.pauseAll();
    case 'delete': return confirmRemove(false);
    case 'deleteCompleted': return confirm('Remove all completed downloads from the list? The files stay on disk.') && api.deleteCompleted();
    case 'startQueue': return api.startQueue(currentQueue());
    case 'stopQueue': return api.stopQueue(currentQueue());
    case 'open': return one?.state === 'completed' && api.openPath(one.savePath);
    case 'openFolder': return one?.savePath && api.showInFolder(one.savePath);
    case 'progress': return one && api.openProgress(one.id);
    case 'refresh': { if (!one) return; const u = prompt('New address for this download:', one.url); return u && act('refresh', u.trim()); }
    case 'copyUrl': return one && api.copy(one.url);
    default:
  }
}
api.onCommand(runCommand);
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, textarea, select')) return;
  if (e.key === 'Delete') confirmRemove(e.shiftKey);
  if (e.key === 'a' && e.ctrlKey) { e.preventDefault(); selection = new Set(visible().map((d) => d.id)); renderRows(); updateToolbar(); }
});

// ---- context menu ------------------------------------------------------------------------------------------------------
$('#rows').addEventListener('contextmenu', (e) => {
  const tr = e.target.closest('tr');
  if (!tr) return;
  e.preventDefault();
  if (!selection.has(tr.dataset.id)) { selection = new Set([tr.dataset.id]); renderRows(); renderStatus(); updateToolbar(); }
  const sel = selectedDownloads();
  const one = sel.length === 1 ? sel[0] : null;
  const items = [];
  if (one?.state === 'completed') items.push(['Open', () => api.openPath(one.savePath)]);
  if (one?.savePath) items.push(['Open folder', () => api.showInFolder(one.savePath)]);
  if (one && one.state !== 'completed') items.push(['Properties / progress window', () => api.openProgress(one.id)]);
  items.push('sep');
  if (sel.some((d) => ['paused', 'error', 'queued', 'canceled'].includes(d.state))) items.push(['Resume download', () => act('start')]);
  if (sel.some((d) => RUNNING.has(d.state) || d.state === 'queued')) items.push(['Stop download', () => act('pause')]);
  if (one) items.push(['Redownload', () => confirm('Download this file again from the beginning?') && act('redownload')]);
  if (one && one.state !== 'completed') items.push(['Refresh download address…', () => runCommand('refresh')]);
  if (one) items.push(['Copy address to clipboard', () => api.copy(one.url)]);
  if (settings?.queues.length > 1) { items.push('sep'); for (const q of settings.queues) items.push([`Move to queue: ${q.name}`, () => act('queue', q.id)]); }
  items.push('sep', ['Delete', () => confirmRemove(false)], ['Delete with file…', () => confirmRemove(true)]);
  showCtx(e.clientX, e.clientY, items);
});
function showCtx(x, y, items) {
  const ul = $('#ctx');
  ul.innerHTML = '';
  for (const it of items) {
    const li = document.createElement('li');
    if (it === 'sep') li.className = 'sep';
    else { li.textContent = it[0]; li.onclick = () => { hideCtx(); it[1](); }; }
    ul.appendChild(li);
  }
  ul.hidden = false;
  ul.style.left = Math.min(x, innerWidth - 220) + 'px';
  ul.style.top = Math.max(0, Math.min(y, innerHeight - ul.offsetHeight - 6)) + 'px';
}
const hideCtx = () => ($('#ctx').hidden = true);
document.addEventListener('click', hideCtx);
window.addEventListener('blur', hideCtx);

// ---- dialogs -----------------------------------------------------------------------------------------------------------------
$$('dialog .cancel').forEach((b) => (b.onclick = () => b.closest('dialog').close()));

function openAdd(url = '') {
  $('#addUrls').value = url;
  $('#addReferer').value = '';
  ['#addUser', '#addPass', '#addCookie', '#addHeaders'].forEach((q) => ($(q).value = ''));
  $('#dlgAdd').showModal();
  $('#addUrls').focus();
  $('#addUrls').select();
}
$('#formAdd').addEventListener('submit', async (e) => {
  e.preventDefault();
  const urls = $('#addUrls').value.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  if (!urls.length) return;
  $('#dlgAdd').close();
  const headers = {};
  for (const line of $('#addHeaders').value.split(/\r?\n/)) {
    const i = line.indexOf(':');
    if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  const user = $('#addUser').value;
  if (user) headers.Authorization = 'Basic ' + btoa(unescape(encodeURIComponent(`${user}:${$('#addPass').value}`)));
  const res = await api.add({ urls, ask: true, referer: $('#addReferer').value.trim(), cookie: $('#addCookie').value.trim(), headers });
  const bad = res.filter((r) => r.error);
  if (bad.length) alert(bad.map((b) => `${b.url}\n${b.error}`).join('\n\n'));
});
api.onAddUrl((url) => openAdd(url));

// "New" menu: one entry point for every kind of download
function showNewMenu(anchor) {
  const m = $('#newMenu');
  const b = anchor || document.querySelector('[data-cmd="newMenu"]');
  const r = b.getBoundingClientRect();
  m.style.left = r.left + 'px';
  m.style.top = r.bottom + 4 + 'px';
  m.hidden = false;
  setTimeout(() => document.addEventListener('click', () => (m.hidden = true), { once: true }), 0);
}
$('#newMenu').addEventListener('click', (e) => {
  const li = e.target.closest('li');
  if (!li) return;
  $('#newMenu').hidden = true;
  const k = li.dataset.new;
  if (k === 'http') { $('#dlgAdd h2').textContent = 'Enter new address to download'; return openAdd(); }
  if (k === 'batch') { $('#dlgAdd h2').textContent = 'Batch download: enter the links, one per line'; return openAdd(); }
  if (k === 'pattern') return openBatch();
  if (k === 'info') return openInfo();
  openVideo(k);
});

// Video / stream download: look the qualities up, pick one, download it
let vMode = 'video';
let vInfo = null;
let vInfoTitle = '';
function setVFile(o) {
  const ext = (o.ext || (o.req.ytdlp && o.req.ytdlp.audio ? 'm4a' : 'mp4'));
  $('#vFile').value = vInfoTitle ? `${vInfoTitle.replace(/[\\/:*?"<>|]+/g, '_').slice(0, 120)}.${ext}` : '';
}
function renderQList() {
  const sel = $('#vQuality');
  $('#vList').innerHTML = vInfo.map((o, i) => `<div class="qrow${o.off ? ' off' : ''}${String(i) === sel.value ? ' on' : ''}" data-i="${i}"><span class="qbadge">${esc(String(o.text.split(/\s/)[0]).slice(0, 7))}</span><span class="qtext">${esc(o.text)}</span></div>`).join('');
  setVFile(vInfo[Number(sel.value)] || vInfo[0]);
}
$('#vList').addEventListener('click', (e) => {
  const r = e.target.closest('.qrow');
  if (!r || r.classList.contains('off')) return;
  $('#vQuality').value = r.dataset.i;
  renderQList();
});
function openVideo(mode) {
  vMode = mode;
  vInfo = null;
  $('#vTitle').textContent = mode === 'stream' ? 'HLS / DASH stream download' : 'Video download';
  $('#vType').hidden = mode !== 'stream';
  $('#vLabel').firstChild.textContent = mode === 'stream' ? 'Playlist or manifest address' : 'Page address';
  $('#vUrl').placeholder = mode === 'stream' ? 'https://example.com/master.m3u8' : 'https://www.youtube.com/watch?v=…';
  $('#vUrl').value = '';
  $('#vResult').hidden = true;
  $('#vStatus').textContent = '';
  $('#vGo').disabled = true;
  $('#dlgVideo').showModal();
  $('#vUrl').focus();
}
const mbText = (n) => (n > 1e9 ? (n / 1e9).toFixed(2) + ' GB' : n > 0 ? (n / 1e6).toFixed(1) + ' MB' : '');
$('#vLoad').onclick = async () => {
  const url = $('#vUrl').value.trim();
  if (!/^https?:\/\//i.test(url)) { $('#vStatus').textContent = 'Enter a full address starting with http:// or https://'; return; }
  $('#vStatus').textContent = 'Looking up the qualities…';
  $('#vGo').disabled = true;
  $('#vResult').hidden = true;
  const dash = vMode === 'stream' && document.querySelector('input[name=vt]:checked').value === 'dash';
  const kind = vMode === 'video' || dash ? 'site' : 'hls';
  let info;
  try { info = await api.apiCall('POST /api/probe-media', { url, kind }); } catch (e) { info = { error: e.message }; }
  if (info.error) { $('#vStatus').textContent = info.error; return; }
  const opts = [];
  if (info.kind === 'site') {
    for (const o of info.options || []) opts.push({ text: [o.label, o.codec, o.ext && o.ext.toUpperCase(), mbText(o.size), o.available ? '' : '(needs ffmpeg)'].filter(Boolean).join('  ·  '), off: !o.available, ext: o.ext, req: { kind: 'ytdlp', url, pageTitle: info.title, ytdlp: { height: o.height, fps: o.fps, vid: o.formatId, aid: o.audioId, mode: info.mode } } });
    for (const au of info.audios || []) opts.push({ text: `Audio  ·  ${au.label.replace('Audio only ','')}  ·  ${mbText(au.size)}`, ext: au.ext, req: { kind: 'ytdlp', url, pageTitle: info.title, ytdlp: { audio: true, aid: au.formatId, mode: info.mode } } });
    if (info.audio && !(info.audios || []).length) opts.push({ text: `${info.audio.label}  ·  ${mbText(info.audio.size)}`, req: { kind: 'ytdlp', url, pageTitle: info.title, ytdlp: { audio: true, aid: info.audio.formatId, mode: info.mode } } });
    $('#vInfo').textContent = info.title ? `${info.title}${info.duration ? '  ·  ' + new Date(info.duration * 1000).toISOString().slice(info.duration >= 3600 ? 11 : 14, 19) : ''}` : '';
  } else if (info.kind === 'hls') {
    if (!info.master) opts.push({ text: 'Stream (single quality)', req: { kind: 'hls', url } });
    for (const v of info.variants || []) opts.push({ text: [v.label, Math.round(v.bandwidth / 1000) + ' kbps', v.lang, v.duration ? new Date(v.duration * 1000).toISOString().slice(11, 19) : '', v.size ? '~ ' + mbText(v.size) : ''].filter(Boolean).join('  ·  '), req: { kind: 'hls', url, hls: { variantUrl: v.url } } });
    $('#vInfo').textContent = '';
  }
  if (!opts.length) { $('#vStatus').textContent = 'No downloadable qualities were found at this address.'; return; }
  vInfo = opts;
  const sel = $('#vQuality');
  sel.innerHTML = '';
  opts.forEach((o, i) => { const el = document.createElement('option'); el.value = i; el.textContent = o.text; el.disabled = !!o.off; sel.appendChild(el); });
  sel.value = String(Math.max(0, opts.findIndex((o) => !o.off)));
  vInfoTitle = info.title || '';
  renderQList();
  $('#vResult').hidden = false;
  $('#vStatus').textContent = `${opts.length} found`;
  $('#vGo').disabled = false;
};
$('#vUrl').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('#vLoad').click(); } });
$('#formVideo').addEventListener('submit', async (e) => {
  if (e.submitter?.id !== 'vGo' || !vInfo) return;
  const o = vInfo[Number($('#vQuality').value)];
  if (!o) return;
  const r = await api.apiCall('POST /api/add', { ...o.req, fileName: $('#vFile').value.trim() || undefined, connections: Number($('#vSeg').value), ask: false, startNow: true });
  if (r && r.error) alert(r.error);
});

// ---- Link details: paste a link from any platform, see its frame, title, caption, subtitles and every quality ----
let linkInfo = null;
const fmtDur = (n) => (n ? new Date(n * 1000).toISOString().slice(n >= 3600 ? 11 : 14, 19) : '');
const fmtNum = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n));
const safeName = (t) => String(t || 'video').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 100);
function openInfo(url) {
  $('#iBody').hidden = true;
  $('#iStatus').textContent = '';
  $('#iUrl').value = url || '';
  $('#dlgInfo').showModal();
  if (url) $('#iGo').click(); else $('#iUrl').focus();
}
$('#quick').addEventListener('submit', (e) => { e.preventDefault(); const u = $('#quickUrl').value.trim(); if (!u) return; $('#quickUrl').value = ''; openInfo(u); });
$('#quickUrl').addEventListener('paste', () => setTimeout(() => { const u = $('#quickUrl').value.trim(); if (/^https?:\/\/\S+$/i.test(u)) $('#quick').requestSubmit(); }, 0));
$('#iUrl').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('#iGo').click(); } });
$('#iGo').onclick = async () => {
  const url = $('#iUrl').value.trim();
  if (!/^https?:\/\//i.test(url)) { $('#iStatus').textContent = 'Enter a full address starting with http:// or https://'; return; }
  $('#iStatus').textContent = 'Reading the link…';
  $('#iBody').hidden = true;
  $('#iGo').disabled = true;
  let info;
  try { info = await api.apiCall('POST /api/link-details', { url }); } catch (e) { info = { error: e.message }; }
  $('#iGo').disabled = false;
  if (info.error) { $('#iStatus').textContent = info.error; return; }
  linkInfo = { url, info, frame: '' };
  const m = info.meta || {};
  $('#iTitle').textContent = info.title || url;
  $('#iMeta').textContent = [m.platform, info.uploader, fmtDur(info.duration), m.uploadDate, m.views ? fmtNum(m.views) + ' views' : '', m.likes ? fmtNum(m.likes) + ' likes' : '', m.width && m.height ? `${m.width}×${m.height}` : ''].filter(Boolean).join('  ·  ');
  $('#iTags').innerHTML = (m.tags || []).map((t) => `<span class="chip">#${esc(t)}</span>`).join('');
  $('#iDesc').textContent = m.description || 'No description or caption text was published for this link.';
  const caps = m.captions || [];
  $('#iCaps').innerHTML = caps.length ? caps.map((c, i) => `<button type="button" class="chip cap" data-c="${i}" title="Download ${esc(c.name)}">${esc(c.lang)}${c.auto ? ' (auto)' : ''} ⬇</button>`).join('') : '<span class="hint">No caption tracks.</span>';
  const img = info.thumbData || '';
  for (const id of ['#iFrame', '#iThumb']) { if (img) $(id).src = img; else $(id).removeAttribute('src'); } $('#iFrameTag').textContent = 'Thumbnail';
  $('#iFrame').style.visibility = img ? 'visible' : 'hidden';
  $('#iSaveFrame').hidden = true;
  const rows = [];
  for (const o of info.options || []) rows.push({ text: [o.label, o.codec, o.ext && o.ext.toUpperCase(), mbText(o.size), o.available ? '' : '(needs ffmpeg)'].filter(Boolean).join('  ·  '), off: !o.available, ext: o.ext, req: { kind: 'ytdlp', url, pageTitle: info.title, ytdlp: { height: o.height, fps: o.fps, vid: o.formatId, aid: o.audioId, mode: info.mode } } });
  for (const au of info.audios || []) rows.push({ text: `Audio  ·  ${au.label.replace('Audio only ', '')}  ·  ${mbText(au.size)}`, ext: au.ext, req: { kind: 'ytdlp', url, pageTitle: info.title, ytdlp: { audio: true, aid: au.formatId, mode: info.mode } } });
  linkInfo.rows = rows;
  $('#iCount').textContent = `${rows.length} found`;
  $('#iList').innerHTML = rows.length ? rows.map((o, i) => `<div class="qrow${o.off ? ' off' : ''}"><span class="qbadge">${esc(o.text.split(/\s/)[0].slice(0, 7))}</span><span class="qtext">${esc(o.text)}</span><button type="button" class="qdl" data-i="${i}"${o.off ? ' disabled' : ''}>Download</button></div>`).join('') : '<div class="hint" style="padding:10px">No downloadable qualities (this link may only have a picture or text).</div>';
  $('#iBody').hidden = false;
  $('#iStatus').textContent = '';
  // the real frame arrives a moment later (it is cut from the video's own stream)
  try {
    const f = await api.apiCall('POST /api/link-frame', { url });
    if (f && f.dataUrl && linkInfo && linkInfo.url === url) {
      linkInfo.frame = f.dataUrl;
      $('#iFrame').src = f.dataUrl; $('#iFrame').style.visibility = 'visible';
      $('#iFrameTag').textContent = 'Frame from the video';
      $('#iSaveFrame').hidden = false;
    }
  } catch { /* thumbnail stays */ }
};
$('#iList').addEventListener('click', async (e) => {
  const b = e.target.closest('.qdl');
  if (!b || !linkInfo) return;
  const o = linkInfo.rows[Number(b.dataset.i)];
  b.disabled = true; b.textContent = 'Added';
  const r = await api.apiCall('POST /api/add', { ...o.req, fileName: `${safeName(linkInfo.info.title)}.${o.ext || (o.req.ytdlp.audio ? 'm4a' : 'mp4')}`, ask: false, startNow: true });
  if (r && r.error) { alert(r.error); b.disabled = false; b.textContent = 'Download'; }
});
$('#iCaps').addEventListener('click', async (e) => {
  const b = e.target.closest('.cap');
  if (!b || !linkInfo) return;
  const c = linkInfo.info.meta.captions[Number(b.dataset.c)];
  const r = await api.apiCall('POST /api/add', { kind: 'http', url: c.url, fileName: `${safeName(linkInfo.info.title)}.${c.lang}.${c.ext}`, ask: false, startNow: true });
  if (r && r.error) alert(r.error); else b.textContent = `${c.lang} ✓`;
});
const copyText = (t, btn) => { navigator.clipboard.writeText(t).then(() => { const o = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => (btn.textContent = o), 1200); }).catch(() => {}); };
$('#iCopyTitle').onclick = (e) => linkInfo && copyText(linkInfo.info.title || '', e.target);
$('#iCopyDesc').onclick = (e) => linkInfo && copyText((linkInfo.info.meta || {}).description || '', e.target);
const saveData = (dataUrl, name) => { const a = document.createElement('a'); a.href = dataUrl; a.download = name; a.click(); };
$('#iSaveThumb').onclick = () => linkInfo && linkInfo.info.thumbData && saveData(linkInfo.info.thumbData, `${safeName(linkInfo.info.title)}-thumbnail.jpg`);
$('#iSaveFrame').onclick = () => linkInfo && linkInfo.frame && saveData(linkInfo.frame, `${safeName(linkInfo.info.title)}-frame.jpg`);

// Batch
let batchUrls = [];
function openBatch() { $('#batchPattern').value = ''; $('#batchPreview').textContent = ''; $('#batchGo').disabled = true; $('#dlgBatch').showModal(); $('#batchPattern').focus(); }
$('#batchPattern').addEventListener('input', async (e) => {
  const r = await api.batchPreview(e.target.value);
  batchUrls = r.urls || [];
  $('#batchGo').disabled = !batchUrls.length || !/^https?:/i.test(batchUrls[0] || '');
  $('#batchPreview').textContent = r.error ? r.error : batchUrls.length > 8 ? `${batchUrls.slice(0, 4).join('\n')}\n…\n${batchUrls.slice(-3).join('\n')}\n\n${batchUrls.length} links` : batchUrls.join('\n');
});
$('#dlgBatch form').addEventListener('submit', async (e) => { if (e.submitter?.id === 'batchGo') await api.add({ urls: batchUrls, startNow: true }); });

// Site grabber
let grabFiles = [];
function openGrabber() { $('#grabList').innerHTML = ''; $('#grabStatus').textContent = ''; $('#grabGo').disabled = true; $('#dlgGrab').showModal(); }
$('#dlgGrab .cancel').addEventListener('click', () => api.grabCancel());
$('#grabScan').onclick = async () => {
  const startUrl = $('#grabUrl').value.trim();
  if (!/^https?:\/\//i.test(startUrl)) return ($('#grabStatus').textContent = 'Enter a full http(s) address.');
  $('#grabStatus').textContent = 'Scanning…';
  $('#grabScan').disabled = true;
  const r = await api.grab({ startUrl, depth: Number($('#grabDepth').value), sameHost: $('#grabSame').checked, exts: $('#grabExts').value.split(/[\s,]+/).filter(Boolean), pattern: $('#grabRegex').value.trim() });
  $('#grabScan').disabled = false;
  grabFiles = r.files || [];
  $('#grabStatus').textContent = r.error ? r.error : `${grabFiles.length} files found on ${r.pagesScanned} page(s)`;
  $('#grabList').innerHTML = grabFiles.map((u, i) => `<label><input type="checkbox" data-i="${i}" checked />${esc(u)}</label>`).join('');
  $('#grabAll').checked = true;
  $('#grabGo').disabled = !grabFiles.length;
};
$('#grabAll').onchange = (e) => $$('#grabList input').forEach((c) => (c.checked = e.target.checked));
$('#dlgGrab form').addEventListener('submit', async (e) => {
  if (e.submitter?.id !== 'grabGo') return;
  const urls = $$('#grabList input:checked').map((c) => grabFiles[Number(c.dataset.i)]);
  if (urls.length) await api.add({ urls, startNow: true, referer: $('#grabUrl').value.trim() });
});

// Scheduler
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
let editingQueue = null;
function loadQueue(id) {
  editingQueue = settings.queues.find((q) => q.id === id) || settings.queues[0];
  const q = editingQueue;
  $('#qSelect').innerHTML = settings.queues.map((x) => `<option value="${esc(x.id)}" ${x.id === q.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('');
  $('#qName').value = q.name;
  $('#qEnabled').checked = !!q.schedule?.enabled;
  $('#qStart').value = q.schedule?.startAt || '';
  $('#qStop').value = q.schedule?.stopAt || '';
  $('#qPost').value = q.postAction || 'none';
  $('#qDays').innerHTML = DAYS.map((d, i) => `<label><input type="checkbox" data-day="${i}" ${(q.schedule?.days || []).includes(i) ? 'checked' : ''}/>${d}</label>`).join('');
  $('#qDelete').disabled = q.id === 'main';
}
function openScheduler() { loadQueue(currentQueue()); $('#dlgSched').showModal(); }
$('#qSelect').onchange = (e) => loadQueue(e.target.value);
$('#qNew').onclick = async () => {
  const name = prompt('Name of the new queue:');
  if (!name) return;
  settings = await api.saveQueue({ id: 'q' + Date.now().toString(36), name });
  loadQueue(settings.queues[settings.queues.length - 1].id);
  renderTree();
};
$('#qDelete').onclick = async () => { if (confirm('Delete this queue? Its downloads move to the main queue.')) { settings = await api.deleteQueue(editingQueue.id); loadQueue('main'); renderTree(); } };
$('#qStartNow').onclick = () => api.startQueue(editingQueue.id);
$('#qStopNow').onclick = () => api.stopQueue(editingQueue.id);
$('#dlgSched form').addEventListener('submit', async (e) => {
  if (e.submitter?.id !== 'qSave') return;
  settings = await api.saveQueue({
    id: editingQueue.id, name: $('#qName').value.trim() || editingQueue.name, postAction: $('#qPost').value,
    schedule: { enabled: $('#qEnabled').checked, startAt: $('#qStart').value, stopAt: $('#qStop').value, days: $$('#qDays input:checked').map((c) => Number(c.dataset.day)) },
  });
  renderTree();
});

// Options
function showTab(name) {
  $$('#optTabs button').forEach((x) => x.classList.toggle('active', x.dataset.tab === name));
  $$('#dlgOptions .tabpane').forEach((t) => (t.hidden = t.dataset.tab !== name));
}
$$('#optTabs button').forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));

async function refreshTools() {
  const t = await api.toolStatus();
  $('#toolYtdlp').innerHTML = t.ytdlp ? `<b class="ok">Installed</b> — yt-dlp ${esc(t.ytdlp.version)}` : '<b class="bad">Not installed</b>';
  $('#toolFfmpeg').innerHTML = t.ffmpeg ? '<b class="ok">Installed</b>' : '<b class="bad">Not installed</b> — only lower qualities are available';
  $('#toolDeno').innerHTML = t.deno ? '<b class="ok">Installed</b>' : '<b class="bad">Not installed</b> — YouTube will not list any qualities';
  $('#installDeno').hidden = !!t.deno;
  $('#installFfmpeg').hidden = t.platform !== 'win32' || !!t.ffmpeg;
}
async function installTool(which, btn) {
  btn.disabled = true;
  $('#toolMsg').textContent = 'Working… this can take a minute.';
  const r = await api.installTool(which);
  $('#toolMsg').textContent = r.message;
  btn.disabled = false;
  refreshTools();
}
$('#installYtdlp').onclick = (e) => installTool('ytdlp', e.target);
$('#installFfmpeg').onclick = (e) => installTool('ffmpeg', e.target);
$('#installDeno').onclick = (e) => installTool('deno', e.target);

let addrList = [];
const DEFAULT_TYPES = '3gp 7z aac ace aif apk arj asf avi bin bz2 exe gz gzip img iso lzh m4a m4v mkv mov mp3 mp4 mpa mpe mpeg mpg msi msu ogg ogv pdf plj pps ppt qt r0* r1* ra rar rm rmvb sea sit sitx tar tif tiff wav wma wmv z zip';
const DEFAULT_SITES = '*.update.microsoft.com download.windowsupdate.com siteseal.thawte.com ecom.cimetz.com *.voice2page.com';
async function openOptions(tab = 'general') {
  settings = await api.getSettings();
  const info = await api.appInfo();
  for (const el of $$('#dlgOptions [data-key]')) {
    const v = settings[el.dataset.key];
    if (el.type === 'checkbox') el.checked = !!v;
    else if (el.dataset.list) { el.value = (v || []).join(el.dataset.split === 'ext' ? ' ' : '\n'); if (el.dataset.upper) el.value = el.value.toUpperCase(); }
    else el.value = v ?? '';
  }
  $('#optSpeed').value = Math.round((settings.speedLimit || 0) / 1024);
  $('#optMinSize').value = Math.round((settings.minCaptureSize || 0) / 1024);
  const p = settings.proxy || {};
  $('#pxHost').value = p.host || ''; $('#pxPort').value = p.port || ''; $('#pxUser').value = p.user || ''; $('#pxPass').value = p.pass || '';
  $('#optPair').value = info.pairingCode;
  addrList = [...(settings.excludedAddresses || [])];
  $('#addrCount').textContent = addrList.length ? `${addrList.length} address(es)` : '';
  showTab(tab);
  $('#dlgOptions').showModal();
  refreshTools();
}
$('#typesDefault').onclick = () => { $('[data-key=captureExtensions]').value = DEFAULT_TYPES.toUpperCase(); };
$('#sitesDefault').onclick = () => { $('[data-key=excludedSites]').value = DEFAULT_SITES; };
$('#addrEdit').onclick = () => { $('#addrList').value = addrList.join('\n'); $('#dlgAddr').showModal(); };
$('#dlgAddr form').addEventListener('submit', (e) => {
  if (e.submitter?.id !== 'addrOk') return;
  addrList = $('#addrList').value.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
  $('#addrCount').textContent = addrList.length ? `${addrList.length} address(es)` : '';
});
$('#optBrowse').onclick = async () => { const d = await api.chooseDir(); if (d) $('[data-key=downloadDir]').value = d; };
$('#optOpenExt').onclick = () => api.openExtensionFolder();
$('#optCopyPair').onclick = () => api.copy($('#optPair').value);
$('#dlgOptions form').addEventListener('submit', async (e) => {
  if (e.submitter?.id !== 'optSave') return;
  const patch = {};
  for (const el of $$('#dlgOptions [data-key]')) {
    if (el.type === 'checkbox') patch[el.dataset.key] = el.checked;
    else if (el.dataset.list) patch[el.dataset.key] = el.value.split(el.dataset.split === 'ext' ? /[\s,]+/ : /\r?\n/).map((s) => s.trim().replace(el.dataset.keepcase ? /^$/ : /^\./, '').toLowerCase()).filter(Boolean);
    else if (el.type === 'number') patch[el.dataset.key] = Number(el.value);
    else patch[el.dataset.key] = el.value.trim();
  }
  patch.excludedAddresses = addrList;
  patch.speedLimit = Math.max(0, Number($('#optSpeed').value) || 0) * 1024;
  patch.minCaptureSize = Math.max(0, Number($('#optMinSize').value) || 0) * 1024;
  patch.proxy = $('#pxHost').value.trim() && $('#pxPort').value ? { host: $('#pxHost').value.trim(), port: Number($('#pxPort').value), user: $('#pxUser').value, pass: $('#pxPass').value } : null;
  settings = await api.setSettings(patch);
  setTheme(settings.theme);
  syncLimit();
});

// Status-bar speed limit
function syncLimit() {
  const kb = String(Math.round((settings.speedLimit || 0) / 1024));
  const sel = $('#sbLimit');
  if (![...sel.options].some((o) => o.value === kb)) sel.add(new Option(`${kb} KB/s`, kb));
  sel.value = kb;
}
$('#sbLimit').onchange = async (e) => { settings = await api.setSettings({ speedLimit: Number(e.target.value) * 1024 }); };

// ---- boot --------------------------------------------------------------------------------------------------------------------
api.onUpdate((list) => { downloads = list; selection = new Set([...selection].filter((id) => list.some((d) => d.id === id))); scheduleRender(); });
(async () => {
  settings = await api.getSettings();
  setTheme(settings.theme);
  syncLimit();
  downloads = await api.list();
  renderHead();
  renderAll();
})();
