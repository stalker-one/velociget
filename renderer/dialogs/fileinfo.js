'use strict';
/* global api */
const id = new URLSearchParams(location.search).get('id');
const $ = (s) => document.querySelector(s);
const CATS = ['General', 'Compressed', 'Documents', 'Music', 'Programs', 'Video'];
const GLYPH = { Video: '▶', Music: '♪', Compressed: 'Z', Documents: 'D', Programs: '▣', General: '•' };
const fmtSize = (n) => { if (n == null || n < 0) return ''; const u = ['Bytes', 'KB', 'MB', 'GB']; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return `${i ? n.toFixed(2) : n} ${u[i]}`; };
const catFor = (name) => {
  const e = (name.split('.').pop() || '').toLowerCase();
  const m = { Compressed: 'zip rar 7z gz tar bz2 xz iso cab', Documents: 'pdf doc docx xls xlsx ppt pptx txt rtf epub csv', Music: 'mp3 wav flac aac ogg m4a wma opus', Programs: 'exe msi apk dmg pkg deb rpm', Video: 'mp4 mkv avi mov wmv flv webm m4v mpg mpeg ts' };
  return Object.keys(m).find((k) => m[k].split(' ').includes(e)) || 'General';
};
const sep = (p) => (p.includes('\\') ? '\\' : '/');
let folder = '';
let fileName = '';
const join = () => folder + (folder && !/[\\/]$/.test(folder) ? sep(folder) : '') + fileName;
const split = (full) => { const i = Math.max(full.lastIndexOf('\\'), full.lastIndexOf('/')); return i < 0 ? ['', full] : [full.slice(0, i), full.slice(i + 1)]; };

(async () => {
  const d = await api.get(id);
  if (!d) return api.closeWindow();
  $('#url').value = d.url;
  $('#cat').innerHTML = CATS.map((c) => `<option>${c}</option>`).join('');
  const info = await api.probe(id);
  fileName = (info && info.fileName) || d.fileName || 'download';
  const cat = d.category || catFor(fileName);
  $('#cat').value = cat;
  folder = d.dir || (await api.categoryDir(cat));
  $('#saveas').value = join();
  $('#name').textContent = fileName;
  $('#icon').className = 'bigicon cat-' + cat;
  $('#icon').textContent = GLYPH[cat];
  $('#meta').textContent = info && info.error ? info.error : info && info.size > 0 ? fmtSize(info.size) : (info && info.note) || 'Size unknown';
  if (info && info.error) $('#meta').className = 'hint bad';
})();

$('#cat').onchange = async () => {
  folder = await api.categoryDir($('#cat').value);
  $('#saveas').value = join();
  $('#icon').className = 'bigicon cat-' + $('#cat').value;
  $('#icon').textContent = GLYPH[$('#cat').value];
};
$('#saveas').onchange = () => { [folder, fileName] = split($('#saveas').value.trim()); };
$('#browse').onclick = async () => { const dir = await api.chooseDir(); if (dir) { folder = dir; $('#saveas').value = join(); } };

const confirm = (start) => {
  [folder, fileName] = split($('#saveas').value.trim());
  const patch = { description: $('#desc').value.trim(), category: $('#cat').value };
  if (folder) patch.dir = folder;
  if (fileName) patch.fileName = fileName;
  api.confirm({ id, start, patch });
};
$('#start').onclick = () => confirm(true);
$('#later').onclick = () => confirm(false);
$('#cancel').onclick = () => api.cancelAsk(id);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') api.cancelAsk(id); if (e.key === 'Enter' && !e.target.matches('button')) confirm(true); });
