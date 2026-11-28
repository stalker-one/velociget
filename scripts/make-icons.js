'use strict';
// Generates PNG icons without any image dependency: rounded gradient square with a download arrow.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function crc32(buf) {
  let c, crc = ~0;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return ~crc >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function png(size) {
  const SS = 4; // supersampling for smooth edges
  const raw = Buffer.alloc((size * 4 + 1) * size);
  const r = size * 0.22;
  const inRound = (x, y) => {
    const cx = Math.min(Math.max(x, r), size - r), cy = Math.min(Math.max(y, r), size - r);
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
  };
  const inArrow = (x, y) => {
    const u = x / size, v = y / size;
    const shaft = u > 0.43 && u < 0.57 && v > 0.2 && v < 0.55;
    const head = v >= 0.5 && v <= 0.72 && Math.abs(u - 0.5) <= (0.72 - v) * 1.05 + 0.0;
    const row = v > 0.77 && v < 0.85;
    const segs = row && ((u > 0.26 && u < 0.43) || (u > 0.47 && u < 0.56) || (u > 0.60 && u < 0.74));
    return shaft || head || segs;
  };
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      let a = 0, ar = 0, rr = 0, gg = 0, bb = 0;
      for (let sy = 0; sy < SS; sy++) for (let sx = 0; sx < SS; sx++) {
        const px = x + (sx + 0.5) / SS, py = y + (sy + 0.5) / SS;
        if (!inRound(px, py)) continue;
        a++;
        if (inArrow(px, py)) { ar++; rr += 255; gg += 255; bb += 255; }
        else {
          const t = (px + py) / (2 * size);
          // brand gradient: deep indigo -> indigo -> cyan
          const c0 = [30, 27, 75], c1 = [79, 70, 229], c2 = [34, 211, 238];
          const k = t < 0.5 ? t * 2 : (t - 0.5) * 2, a0 = t < 0.5 ? c0 : c1, a1 = t < 0.5 ? c1 : c2;
          rr += a0[0] + (a1[0] - a0[0]) * k; gg += a0[1] + (a1[1] - a0[1]) * k; bb += a0[2] + (a1[2] - a0[2]) * k;
        }
      }
      const o = y * (size * 4 + 1) + 1 + x * 4;
      const n = SS * SS;
      raw[o] = a ? Math.round(rr / a) : 0;
      raw[o + 1] = a ? Math.round(gg / a) : 0;
      raw[o + 2] = a ? Math.round(bb / a) : 0;
      raw[o + 3] = Math.round((a / n) * 255);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

const root = path.join(__dirname, '..');
fs.mkdirSync(path.join(root, 'build'), { recursive: true });
fs.mkdirSync(path.join(root, 'extension', 'icons'), { recursive: true });
fs.writeFileSync(path.join(root, 'build', 'icon.png'), png(512));
for (const s of [16, 32, 48, 128]) fs.writeFileSync(path.join(root, 'extension', 'icons', `icon${s}.png`), png(s));
// Windows icon: PNG-compressed entries (Vista+), used by the exe, the taskbar, the tray and the installer.
const sizes = [16, 24, 32, 48, 64, 128, 256];
const imgs = sizes.map((n) => png(n));
const head = Buffer.alloc(6); head.writeUInt16LE(1, 2); head.writeUInt16LE(sizes.length, 4);
let offset = 6 + 16 * sizes.length;
const dir = sizes.map((n, i) => {
  const e = Buffer.alloc(16);
  e[0] = n === 256 ? 0 : n; e[1] = n === 256 ? 0 : n; e.writeUInt16LE(1, 4); e.writeUInt16LE(32, 6);
  e.writeUInt32LE(imgs[i].length, 8); e.writeUInt32LE(offset, 12);
  offset += imgs[i].length;
  return e;
});
fs.writeFileSync(path.join(root, 'build', 'icon.ico'), Buffer.concat([head, ...dir, ...imgs]));
console.log('icons written');
