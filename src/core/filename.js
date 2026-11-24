'use strict';
const path = require('path');
const { exists } = require('./util');

const CATEGORIES = {
  Compressed: ['zip', 'rar', '7z', 'gz', 'tar', 'bz2', 'xz', 'tgz', 'iso', 'cab', 'z', 'lz', 'zst'],
  Documents: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'rtf', 'odt', 'ods', 'epub', 'mobi', 'csv'],
  Music: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'wma', 'opus', 'mid', 'midi', 'aiff'],
  Programs: ['exe', 'msi', 'msix', 'apk', 'dmg', 'pkg', 'deb', 'rpm', 'bat', 'appx', 'jar'],
  Video: ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'mpg', 'mpeg', 'ts', '3gp'],
};

const MIME_EXT = {
  'video/mp4': '.mp4',
  'video/webm': '.webm',
  'video/x-matroska': '.mkv',
  'video/quicktime': '.mov',
  'video/x-flv': '.flv',
  'video/mp2t': '.ts',
  'audio/mpeg': '.mp3',
  'audio/mp4': '.m4a',
  'audio/aac': '.aac',
  'audio/ogg': '.ogg',
  'audio/wav': '.wav',
  'audio/webm': '.weba',
  'application/pdf': '.pdf',
  'application/zip': '.zip',
  'application/x-rar-compressed': '.rar',
  'application/x-7z-compressed': '.7z',
  'application/vnd.apple.mpegurl': '.m3u8',
  'application/x-mpegurl': '.m3u8',
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/gif': '.gif',
  'image/webp': '.webp',
};

const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

/** Header values arrive as latin1 strings; recover UTF-8 names when that is what was sent. */
function fixMojibake(s) {
  if (!/[\u0080-ÿ]/.test(s)) return s;
  try {
    const buf = Buffer.from(s, 'latin1');
    const dec = buf.toString('utf8');
    return dec.includes('�') ? s : dec;
  } catch {
    return s;
  }
}

function fromContentDisposition(cd) {
  if (!cd) return null;
  let m = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(cd);
  if (m) {
    try {
      return decodeURIComponent(m[2].trim().replace(/^"|"$/g, ''));
    } catch {
      /* fall through */
    }
  }
  m = /filename\s*=\s*"((?:[^"\\]|\\.)*)"/i.exec(cd) || /filename\s*=\s*([^;]+)/i.exec(cd);
  if (m) return fixMojibake(m[1].trim().replace(/\\(.)/g, '$1'));
  return null;
}

function fromUrl(url) {
  try {
    const u = new URL(url);
    const last = u.pathname.split('/').filter(Boolean).pop();
    if (last) return decodeURIComponent(last);
  } catch {
    /* ignore */
  }
  return null;
}

/** Make a name safe for Windows (and everything else). */
function sanitize(name, fallback = 'download') {
  let n = String(name || '').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').trim();
  n = n.replace(/[. ]+$/, '');
  if (!n) n = fallback;
  if (RESERVED.test(n)) n = '_' + n;
  if (n.length > 200) {
    const ext = path.extname(n).slice(0, 20);
    n = n.slice(0, 200 - ext.length) + ext;
  }
  return n;
}

function chooseName({ contentDisposition, url, contentType, fallback }) {
  let name = fromContentDisposition(contentDisposition) || fromUrl(url) || fallback || 'download';
  name = sanitize(name);
  if (!path.extname(name) && contentType) {
    const ext = MIME_EXT[contentType.split(';')[0].trim().toLowerCase()];
    if (ext) name += ext;
  }
  return name;
}

function categoryFor(name) {
  const ext = path.extname(name).slice(1).toLowerCase();
  for (const [cat, list] of Object.entries(CATEGORIES)) if (list.includes(ext)) return cat;
  return 'General';
}

/** Returns a path in `dir` that collides with neither a file nor a partial download. */
async function uniquePath(dir, name) {
  const ext = path.extname(name);
  const base = name.slice(0, name.length - ext.length);
  let candidate = path.join(dir, name);
  for (let i = 1; (await exists(candidate)) || (await exists(candidate + '.part')); i++) {
    candidate = path.join(dir, `${base} (${i})${ext}`);
  }
  return candidate;
}

module.exports = {
  CATEGORIES,
  fromContentDisposition,
  fromUrl,
  sanitize,
  chooseName,
  categoryFor,
  uniquePath,
};
