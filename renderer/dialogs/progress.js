'use strict';
/* global api */
const id = new URLSearchParams(location.search).get('id');
const $ = (s) => document.querySelector(s);
const fmtSize = (n) => { if (n == null || n < 0) return '—'; const u = ['Bytes', 'KB', 'MB', 'GB', 'TB']; let i = 0; while (n >= 1024 && i < 4) { n /= 1024; i++; } return `${i ? n.toFixed(2) : Math.round(n)} ${u[i]}`; };
const fmtEta = (s) => { if (s == null) return ''; const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60; return h ? `${h} hour ${m} min` : m ? `${m} min ${x} sec` : `${x} sec`; };
const LABEL = { queued: 'Waiting in queue', probing: 'Connecting…', downloading: 'Receiving data…', merging: 'Finishing (joining streams)…', paused: 'Paused', completed: 'Complete', error: 'Error', canceled: 'Canceled' };
let detailsOn = true;
let wired = false;
let last = null;
const prevPos = new Map(); // segment start -> { pos, t } for per-connection speed
const rowEls = new Map();
let sized = false;

function draw(d) {
  const c = $('#segs');
  const g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  if (d.state === 'merging') $('#conn').textContent = 'Joining video and audio…';
  if (!d.segments.length || d.total <= 0) return;
  const W = c.width, H = c.height, y = 4, h = H - 8;
  const X = (b) => (b / d.total) * W;
  // track
  g.fillStyle = '#e3e9f2';
  g.beginPath(); g.roundRect ? g.roundRect(0, y, W, h, 4) : g.rect(0, y, W, h); g.fill();
  g.save();
  g.beginPath(); g.roundRect ? g.roundRect(0, y, W, h, 4) : g.rect(0, y, W, h); g.clip();
  // received data: one continuous green fill (neighbouring pieces are merged so there are no seams or stripes)
  const runs = d.segments.filter((s) => s.pos > s.start).map((s) => [s.start, s.pos]).sort((p, q) => p[0] - q[0]);
  const merged = [];
  for (const r of runs) {
    const last = merged[merged.length - 1];
    if (last && r[0] <= last[1] + 1) last[1] = Math.max(last[1], r[1]); else merged.push([r[0], r[1]]);
  }
  g.fillStyle = '#22a35a';
  for (const [a0, a1] of merged) g.fillRect(X(a0), y, Math.max(1, X(a1) - X(a0)), h);
  // live connections: a blue marker at the point each one is writing (width is fixed, not the size of the piece)
  g.fillStyle = '#2563eb';
  for (const s of d.segments) if (s.active && !s.done) g.fillRect(Math.max(0, Math.min(W - 3, X(s.pos) - 1)), y, 3, h);
  g.restore();
  const live = d.segments.filter((x) => x.active && !x.done).length || d.connections || 0;
  $('#conn').textContent = d.state === 'downloading' ? `${live} connection${live === 1 ? '' : 's'} active` : `${d.connections || 0} connection${d.connections === 1 ? '' : 's'}`;
}

/** One line per connection: status, bytes received and current speed (like XDM / IDM "details"). */
function drawTable(d) {
  const box = $('#crows');
  const segs = d.segments.filter((x) => x.end >= 0 && !x.done && x.active);  // finished parts leave the table; only live/waiting connections are listed
  const now = performance.now();
  const keep = new Set();
  segs.forEach((s, i) => {
    const key = String(s.start);
    keep.add(key);
    let r = rowEls.get(key);
    if (!r) {
      r = document.createElement('div');
      r.className = 'crow';
      r.innerHTML = '<span></span><span></span><span></span><span></span>';
      rowEls.set(key, r);
    }
    const got = Math.max(0, s.pos - s.start);
    const p = prevPos.get(key);
    let sp = r._sp || 0;
    if (p && now - p.t >= 400) { sp = s.active && !s.done ? Math.max(0, (s.pos - p.pos) / ((now - p.t) / 1000)) : 0; r._sp = sp; prevPos.set(key, { pos: s.pos, t: now }); }
    else if (!p) prevPos.set(key, { pos: s.pos, t: now });
    const c = r.children;
    const st = s.done ? 'Finished' : s.active ? 'Downloading' : d.state === 'downloading' ? 'Waiting' : 'Idle';
    r.className = 'crow ' + (s.done ? 'done' : s.active ? 'act' : '');
    c[0].textContent = String(i + 1);
    c[1].textContent = st;
    c[2].textContent = fmtSize(got);
    c[3].textContent = s.active && !s.done && sp > 0 ? fmtSize(sp) + '/s' : '';
    if (r.parentNode !== box || box.children[i] !== r) box.insertBefore(r, box.children[i] || null);
  });
  for (const [k, r] of rowEls) if (!keep.has(k)) { r.remove(); rowEls.delete(k); prevPos.delete(k); }
}

function render(d) {
  last = d;
  const pct = d.total > 0 ? Math.min(100, (d.downloaded / d.total) * 100) : d.state === 'completed' ? 100 : 0;
  document.title = `${pct.toFixed(1)}% ${d.fileName || ''}`;
  $('#url').textContent = d.url;
  $('#st').textContent = (LABEL[d.state] || d.state) + (d.state === 'merging' && d.mergePct != null ? ` ${d.mergePct}%` : '') + (d.warning && d.state === 'downloading' ? ' — ' + d.warning : '');
  $('#size').textContent = fmtSize(d.total);
  $('#dl').textContent = `${fmtSize(d.downloaded)}${d.total > 0 ? ` (${pct.toFixed(0)}%)` : ''}`;
  $('#rate').textContent = d.speed > 0 ? `${fmtSize(d.speed)}/sec` : '';
  $('#eta').textContent = ['downloading', 'probing'].includes(d.state) ? fmtEta(d.eta) : '';
  $('#resume').textContent = d.kind === 'http' ? (d.resumable ? 'Yes' : 'No') : 'Yes';
  $('#fill').style.width = pct + '%';
  $('#pct').textContent = pct.toFixed(1) + '%';
  $('#msg').textContent = d.error || '';
  $('#pause').textContent = ['paused', 'error', 'canceled'].includes(d.state) ? 'Resume' : 'Pause';
  $('#pause').disabled = ['completed', 'merging'].includes(d.state);
  draw(d);
  drawTable(d);
  if (!sized) { sized = true; requestAnimationFrame(() => api.resizeWindow(document.getElementById('root').offsetHeight)); }
  if (!wired) {
    wired = true;
    $('#useLimit').checked = d.speedLimit > 0;
    if (d.speedLimit > 0) $('#limitKb').value = Math.round(d.speedLimit / 1024);
    const r = document.querySelector(`input[name=after][value=${d.onComplete || 'none'}]`);
    if (r) r.checked = true;
  }
}

document.querySelectorAll('#tabs button').forEach((b) => (b.onclick = () => {
  document.querySelectorAll('#tabs button').forEach((x) => x.classList.toggle('active', x === b));
  document.querySelectorAll('.tabpane').forEach((p) => (p.hidden = p.dataset.t !== b.dataset.t));
}));
$('#toggle').onclick = () => {
  detailsOn = !detailsOn;
  $('#details').hidden = !detailsOn;
  $('#toggle').textContent = detailsOn ? '<< Hide details' : 'Show details >>';
  requestAnimationFrame(() => api.resizeWindow(document.getElementById('root').offsetHeight));
};
$('#pause').onclick = () => api.action({ ids: [id], action: ['paused', 'error', 'canceled'].includes(last?.state) ? 'start' : 'pause' });
$('#cancel').onclick = async () => {
  if (last && last.state !== 'completed') await api.action({ ids: [id], action: 'pause' });
  api.closeWindow();
};
$('#applyLimit').onclick = () => api.patch({ id, patch: { speedLimit: $('#useLimit').checked ? Math.max(1, Number($('#limitKb').value) || 1) * 1024 : 0 } });
document.querySelectorAll('input[name=after]').forEach((r) => (r.onchange = () => api.patch({ id, patch: { onComplete: r.value } })));
api.getSettings().then((s) => { $('#optComplete').checked = !!s.showCompleteDialog; });
$('#optComplete').onchange = (e) => api.setSettings({ showCompleteDialog: e.target.checked });

api.onUpdate((list) => { const d = list.find((x) => x.id === id); if (d) render(d); });
api.get(id).then((d) => (d ? render(d) : api.closeWindow()));
