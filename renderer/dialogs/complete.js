'use strict';
/* global api */
const id = new URLSearchParams(location.search).get('id');
const $ = (s) => document.querySelector(s);
const GLYPH = { Video: '▶', Music: '♪', Compressed: 'Z', Documents: 'D', Programs: '▣', General: '•' };
const fmtSize = (n) => { if (!(n > 0)) return ''; const u = ['Bytes', 'KB', 'MB', 'GB']; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return `${i ? n.toFixed(2) : n} ${u[i]}`; };
let d = null;
api.get(id).then((x) => {
  if (!x) return api.closeWindow();
  d = x;
  const cat = d.category || 'General';
  $('#icon').className = 'bigicon cat-' + cat;
  $('#icon').textContent = GLYPH[cat] || '•';
  $('#name').textContent = d.fileName;
  $('#meta').textContent = `Download complete · ${fmtSize(d.total)}`;
  $('#path').textContent = d.savePath;
  $('#url').textContent = d.url;
});
const finish = async () => { if ($('#never').checked) await api.setSettings({ showCompleteDialog: false }); };
$('#open').onclick = async () => { await finish(); api.openPath(d.savePath); api.closeWindow(); };
$('#folder').onclick = async () => { await finish(); api.showInFolder(d.savePath); api.closeWindow(); };
$('#close').onclick = async () => { await finish(); api.closeWindow(); };
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') api.closeWindow(); });
