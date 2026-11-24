#!/usr/bin/env node
'use strict';
/**
 * Stand-in for yt-dlp that models YouTube's real behaviour: the FIRST listing (the one the quality
 * menu is built from) is complete, but a listing made later - when the download starts - comes back
 * changed. The exact format ids the user picked can therefore not be found any more, and a
 * "height <= N" selector silently resolves to a much lower quality.
 *
 * Env:
 *   FAKE_BASE          base URL of the file server
 *   FAKE_COUNTER       file used to count how many listings were made
 *   FAKE_VSIZE         size the 1080p stream announces
 *   FAKE_LOWSIZE       size the degraded (360p) stream announces
 *   FAKE_ASIZE         size the audio stream announces
 *   FAKE_RENUMBER=1    the later listing keeps the 1080p quality but under another format id
 *   FAKE_HIDE_HIGH=1   the later listing has no 1080p at all (the site only offers the low ones)
 *   FAKE_REFUSE_HIGH=1 the 1080p address answers 403 (the signature expired)
 */
const fs = require('fs');

const a = process.argv.slice(2);
const B = process.env.FAKE_BASE || '';
const V = Number(process.env.FAKE_VSIZE || 5840000);
const LOW = Number(process.env.FAKE_LOWSIZE || 670000);
const A = Number(process.env.FAKE_ASIZE || 80000);

if (a.includes('--version')) { console.log('2099.01.01-fake'); process.exit(0); }

const bump = () => {
  let n = 0;
  try { n = Number(fs.readFileSync(process.env.FAKE_COUNTER, 'utf8')) || 0; } catch { /* first call */ }
  n += 1;
  fs.writeFileSync(process.env.FAKE_COUNTER, String(n));
  return n;
};

const full = (id1080 = '137') => ([
  { format_id: id1080, vcodec: 'avc1.640028', acodec: 'none', height: 1080, fps: 30, ext: 'mp4', protocol: 'https', filesize: V, url: B + '/v1080', http_headers: {} },
  { format_id: '136', vcodec: 'avc1.4d401f', acodec: 'none', height: 720, fps: 30, ext: 'mp4', protocol: 'https', filesize: Math.round(V * 0.55), url: B + '/v720', http_headers: {} },
  { format_id: '18', vcodec: 'avc1.42001E', acodec: 'mp4a.40.2', height: 360, fps: 30, ext: 'mp4', protocol: 'https', filesize: LOW, url: B + '/v360', http_headers: {} },
  { format_id: '140', vcodec: 'none', acodec: 'mp4a.40.2', ext: 'm4a', protocol: 'https', abr: 128, filesize: A, url: B + '/a140', http_headers: {} },
]);

// what YouTube hands out when it is not in the mood: only the low qualities
const degraded = (withHigh) => {
  const out = [full()[2], full()[3]];
  if (withHigh) out.unshift({ ...full()[0], format_id: '299' });
  return out;
};

{
  const n = bump();
  const later = n > 1;
  let list;
  if (!later) list = full();
  else if (process.env.FAKE_RENUMBER) list = degraded(true);
  else if (process.env.FAKE_HIDE_HIGH) list = degraded(false);
  else list = degraded(false);

  const hasF = a.includes('-f');
  const sel = hasF ? a[a.indexOf('-f') + 1] : '';
  if (hasF) {
    // yt-dlp picks from the list it was given: exact ids first, then the first matching fallback
    const wanted = sel.split('/').map((x) => x.trim()).filter(Boolean);
    const pick = (spec) => {
      const ids = spec.match(/\b\d{2,3}\b/g);
      if (ids) {
        // "137+140" asks for the video stream 137 (and its audio separately)
        const m = list.find((f) => ids.includes(String(f.format_id)) && f.vcodec && f.vcodec !== 'none');
        return m ? [m] : null;
      }
      const exactH = spec.match(/height=(\d+)/);
      const limitH = spec.match(/height<=(\d+)/);
      const cands = exactH
        ? list.filter((f) => f.height && f.height === Number(exactH[1]) && f.acodec === 'none')
        : list.filter((f) => f.height && f.height <= Number(limitH ? limitH[1] : Infinity) && f.acodec === 'none');
      if (!cands.length) return null;
      cands.sort((x, y) => y.height - x.height);
      return [cands[0]];
    };
    let picked = null;
    for (const w of wanted) { const p = pick(w); if (p && p.length) { picked = p; break; } }
    if (!picked) {
      process.stderr.write('ERROR: [youtube] V_-YdNa622Q: Requested format is not available. Use --list-formats for a list of available formats\n');
      process.exit(1);
    }
    const v = picked[0];
    const aud = list.find((f) => f.acodec && f.acodec !== 'none' && f.vcodec === 'none');
    const out = { title: 'Complete AI Automation Course 2026', duration: 14795, formats: list };
    if (aud && v.acodec === 'none') { out.requested_formats = [v, aud]; out.format_id = v.format_id; out.height = v.height; out.ext = 'mp4'; }
    else { out.requested_formats = [v]; out.format_id = v.format_id; out.height = v.height; out.ext = v.ext; }
    fs.writeFileSync(process.env.FAKE_COUNTER + '.sel', v.url);
    if (a.includes('-J')) {
      console.log(JSON.stringify(out));
      process.exit(0);
    }
    // no -J: this is the download itself, so the selected stream is fetched below
    fs.writeFileSync(process.env.FAKE_COUNTER + '.sel', JSON.stringify({ video: v.url, audio: aud && v.acodec === 'none' ? aud.url : '' }));
  } else {
    fs.writeFileSync(process.env.FAKE_COUNTER + '.sel', JSON.stringify({ video: null, audio: null }));
    if (a.includes('-J')) {
      console.log(JSON.stringify({ title: 'Complete AI Automation Course 2026', duration: 14795, formats: list }));
      process.exit(0);
    }
    process.stderr.write('ERROR: [youtube] V_-YdNa622Q: Requested format is not available. Use --list-formats for a list of available formats\n');
    process.exit(1);
  }
}

// yt-dlp's own downloader: it fetches the stream it selected, like the real thing does
const tpl = a[a.indexOf('-o') + 1];
const out = tpl.replace('%(ext)s', 'mp4');
fs.mkdirSync(require('path').dirname(out), { recursive: true });
let sel = { video: null, audio: null };
try { sel = JSON.parse(fs.readFileSync(process.env.FAKE_COUNTER + '.sel', 'utf8')); } catch { /* nothing selected */ }
(async () => {
  try {
    if (!sel.video) { process.stderr.write('ERROR: [youtube] V_-YdNa622Q: Requested format is not available. Use --list-formats for a list of available formats\n'); process.exit(1); }
    const res = await fetch(sel.video);
    if (!res.ok) { process.stderr.write('ERROR: unable to download video data: HTTP Error ' + res.status + '\n'); process.exit(1); }
    const buf = Buffer.from(await res.arrayBuffer());
    fs.writeFileSync(out, buf);
    console.log(`VGP\t${buf.length}\t${buf.length}\tNA\t${out}`);
  } catch (e) {
    process.stderr.write('ERROR: ' + e.message + '\n');
    process.exit(1);
  }
  process.exit(0);
})();
