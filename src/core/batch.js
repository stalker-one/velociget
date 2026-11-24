'use strict';

/**
 * Expands URL patterns for batch downloads:
 *   http://host/img[001-100].jpg   numeric range (zero padding follows the start value)
 *   http://host/[a-f].png          letter range
 *   http://host/file.{jpg,png}     alternatives
 * Several patterns can be combined; expansion is capped to protect against typos.
 */
function expandPattern(pattern, limit = 10000) {
  let results = [pattern];
  const re = /\[([^\]]+)\]|\{([^}]+)\}/;
  for (let guard = 0; guard < 8; guard++) {
    const next = [];
    let expanded = false;
    for (const s of results) {
      const m = re.exec(s);
      if (!m) {
        next.push(s);
        continue;
      }
      expanded = true;
      const values = m[1] !== undefined ? rangeValues(m[1]) : m[2].split(',');
      if (!values) {
        throw new Error(`Invalid range: ${m[0]}`);
      }
      for (const v of values) {
        next.push(s.slice(0, m.index) + v + s.slice(m.index + m[0].length));
        if (next.length > limit) throw new Error(`The pattern expands to more than ${limit} URLs`);
      }
    }
    results = next;
    if (!expanded) break;
  }
  return results;
}

function rangeValues(body) {
  let m = /^(\d+)-(\d+)(?::(\d+))?$/.exec(body);
  if (m) {
    const width = m[1].length;
    const a = Number(m[1]);
    const b = Number(m[2]);
    const step = Number(m[3] || 1) || 1;
    const out = [];
    if (a <= b) for (let i = a; i <= b; i += step) out.push(String(i).padStart(width, '0'));
    else for (let i = a; i >= b; i -= step) out.push(String(i).padStart(width, '0'));
    return out;
  }
  m = /^([a-zA-Z])-([a-zA-Z])$/.exec(body);
  if (m) {
    const a = m[1].charCodeAt(0);
    const b = m[2].charCodeAt(0);
    const out = [];
    for (let i = Math.min(a, b); i <= Math.max(a, b); i++) out.push(String.fromCharCode(i));
    return a <= b ? out : out.reverse();
  }
  return null;
}

module.exports = { expandPattern };
