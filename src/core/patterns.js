'use strict';
/** IDM-style wildcard lists: "*" matches anything; entries are compared case-insensitively. */
function globToRegex(g) {
  return new RegExp('^' + String(g).trim().replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$', 'i');
}

/** File-type list entry such as "mp4" or "r0*" against an extension without the dot. */
function extMatches(list, ext) {
  ext = String(ext || '').toLowerCase();
  if (!ext) return false;
  return (list || []).some((p) => (String(p).includes('*') ? globToRegex(p).test(ext) : String(p).toLowerCase() === ext));
}

/** "example.com" covers the site and its sub-domains; "*.example.com" covers the site and its sub-domains too. */
function hostMatches(list, host) {
  host = String(host || '').toLowerCase();
  return (list || []).some((raw) => {
    const p = String(raw).trim().toLowerCase();
    if (!p) return false;
    if (p.startsWith('*.') && !p.slice(2).includes('*') && host === p.slice(2)) return true;
    if (p.includes('*')) return globToRegex(p).test(host);
    return host === p || host.endsWith('.' + p);
  });
}

function addressMatches(list, url) {
  return (list || []).some((p) => String(p).trim() && globToRegex(p.includes('*') ? p : p + '*').test(url));
}

module.exports = { globToRegex, extMatches, hostMatches, addressMatches };
