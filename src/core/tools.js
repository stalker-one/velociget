'use strict';
/**
 * External helper programs: yt-dlp (site video extractor) and ffmpeg (merging / remuxing).
 * They are looked up in this order: path configured in Options, the app's own bin folder, PATH.
 */
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const os = require('os');
const { spawn, spawnSync } = require('child_process');
const { fetchFollow, dispatcherFor } = require('./http');
const { FatalError } = require('./util');

const exe = (n) => (process.platform === 'win32' ? `${n}.exe` : n);

/** Returns trimmed stdout of `bin <args>` when it exits 0, else null. */
function works(bin, args) {
  try {
    const r = spawnSync(bin, args, { timeout: 20_000, windowsHide: true, encoding: 'utf8' });
    return r.status === 0 ? String(r.stdout || '').trim() || 'ok' : null;
  } catch {
    return null;
  }
}

function findYtdlp(settings, binDir) {
  const candidates = [settings.ytdlpPath, binDir && path.join(binDir, exe('yt-dlp')), 'yt-dlp'].filter(Boolean);
  for (const c of candidates) {
    if (c !== 'yt-dlp' && !fs.existsSync(c)) continue;
    const v = works(c, ['--version']);
    if (v) return { path: c, version: v };
  }
  return null;
}

function findFfmpeg(settings, binDir) {
  if (settings.ffmpegPath === 'disabled') return null;
  const candidates = [settings.ffmpegPath, binDir && path.join(binDir, exe('ffmpeg')), 'ffmpeg'].filter(Boolean);
  for (const c of candidates) {
    if (c !== 'ffmpeg' && !fs.existsSync(c)) continue;
    if (works(c, ['-version'])) return c;
  }
  return null;
}

/** ffprobe lives next to ffmpeg; it reads resolution / duration / bitrate from the first bytes of a remote file. */
function findFfprobe(settings, binDir) {
  const ff = findFfmpeg(settings, binDir);
  if (!ff) return null;
  const cand = ff === 'ffmpeg' ? 'ffprobe' : path.join(path.dirname(ff), exe('ffprobe'));
  return cand === 'ffprobe' || fs.existsSync(cand) ? (works(cand, ['-version']) ? cand : null) : null;
}

/** { width, height, duration, bitRate } of a remote media file, or null. Never throws. */
function probeMedia(ffprobe, url, headers = {}) {
  return new Promise((resolve) => {
    const hdr = Object.entries(headers).filter(([, v]) => v).map(([k, v]) => `${k}: ${v}`).join('\r\n');
    const args = ['-v', 'error', '-show_entries', 'stream=codec_type,width,height:format=duration,bit_rate', '-of', 'json'];
    if (hdr) args.push('-headers', hdr + '\r\n');
    args.push('-i', url);
    let out = '';
    let p;
    try {
      p = spawn(ffprobe, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
      return resolve(null);
    }
    const t = setTimeout(() => p.kill('SIGKILL'), 9000);
    p.stdout.on('data', (d) => (out += d));
    p.on('error', () => { clearTimeout(t); resolve(null); });
    p.on('close', () => {
      clearTimeout(t);
      try {
        const j = JSON.parse(out);
        const v = (j.streams || []).find((x) => x.codec_type === 'video' && x.height) || {};
        resolve({ width: v.width || 0, height: v.height || 0, duration: Number(j.format && j.format.duration) || 0, bitRate: Number(j.format && j.format.bit_rate) || 0, hasVideo: !!v.height });
      } catch {
        resolve(null);
      }
    });
  });
}

/** One JPEG frame (about 320 px wide) of a remote video/stream via ffmpeg, as a Buffer; null when it cannot be read. */
function grabFrame(ffmpeg, url, headers = {}, at = 4, width = 320) {
  return new Promise((resolve) => {
    const hdr = Object.entries(headers).filter(([, v]) => v).map(([k, v]) => `${k}: ${v}`).join('\r\n');
    const args = ['-hide_banner', '-loglevel', 'error', '-rw_timeout', '8000000'];
    if (hdr) args.push('-headers', hdr + '\r\n');
    args.push('-ss', String(at), '-i', url, '-frames:v', '1', '-vf', `scale=${width}:-2`, '-q:v', '6', '-f', 'mjpeg', 'pipe:1');
    const chunks = [];
    let p;
    try {
      p = spawn(ffmpeg, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
      return resolve(null);
    }
    const t = setTimeout(() => p.kill('SIGKILL'), 12000);
    p.stdout.on('data', (d) => chunks.push(d));
    p.on('error', () => { clearTimeout(t); resolve(null); });
    p.on('close', () => {
      clearTimeout(t);
      const b = Buffer.concat(chunks);
      resolve(b.length > 200 ? b : null);
    });
  });
}

/** Deno (or node/bun) runs YouTube's player script so yt-dlp can unlock the real video streams. */
function findDeno(settings, binDir) {
  const candidates = [settings.denoPath, binDir && path.join(binDir, exe('deno')), 'deno'].filter(Boolean);
  for (const c of candidates) {
    if (c !== 'deno' && !fs.existsSync(c)) continue;
    if (works(c, ['--version'])) return c;
  }
  return null;
}

/** Node 20+ on PATH is an acceptable fallback runtime for yt-dlp. */
function findNode() {
  const v = works('node', ['--version']);
  const m = v && /v?(\d+)\./.exec(v);
  return m && Number(m[1]) >= 20 ? 'node' : null;
}

async function downloadTo(url, dest, { signal, settings } = {}) {
  const tmp = dest + '.download';
  await fsp.mkdir(path.dirname(dest), { recursive: true });
  const { res } = await fetchFollow(url, { signal, dispatcher: dispatcherFor(settings && settings.proxy), headers: { 'user-agent': 'IDMByEncodal' } });
  if (res.statusCode !== 200) {
    await res.body.dump({ limit: 65536 }).catch(() => {});
    throw new Error(`Download failed: HTTP ${res.statusCode}`);
  }
  const out = fs.createWriteStream(tmp);
  await new Promise((resolve, reject) => {
    res.body.on('error', reject);
    out.on('error', reject);
    out.on('finish', resolve);
    res.body.pipe(out);
  });
  await fsp.rename(tmp, dest);
}

const YTDLP_URL = {
  win32: 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe',
  darwin: 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos',
  linux: 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux',
};

async function installYtdlp(binDir, opts = {}) {
  const url = YTDLP_URL[process.platform];
  if (!url) throw new Error('Automatic yt-dlp install is not available on this platform');
  const dest = path.join(binDir, exe('yt-dlp'));
  await downloadTo(url, dest, opts);
  if (process.platform !== 'win32') await fsp.chmod(dest, 0o755);
  const v = works(dest, ['--version']);
  if (!v) throw new Error('The downloaded yt-dlp does not run on this computer');
  return { path: dest, version: v };
}

/** `yt-dlp -U`: replaces the standalone binary with the newest release. */
function updateYtdlp(ytdlpPath) {
  return new Promise((resolve) => {
    const p = spawn(ytdlpPath, ['-U'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.stderr.on('data', (d) => (out += d));
    const t = setTimeout(() => p.kill(), 120_000);
    p.on('close', (code) => {
      clearTimeout(t);
      resolve({ ok: code === 0, message: out.trim().split(/\r?\n/).slice(-2).join(' ') });
    });
    p.on('error', (e) => {
      clearTimeout(t);
      resolve({ ok: false, message: e.message });
    });
  });
}

const DENO_ZIP = {
  win32: 'https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip',
  linux: 'https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip',
  darwin: process.arch === 'arm64'
    ? 'https://github.com/denoland/deno/releases/latest/download/deno-aarch64-apple-darwin.zip'
    : 'https://github.com/denoland/deno/releases/latest/download/deno-x86_64-apple-darwin.zip',
};

function extractZip(zip, into) {
  let r = spawnSync('tar', ['-xf', zip, '-C', into], { windowsHide: true }); // bsdtar: Windows 10+, macOS
  if (r.status === 0) return;
  r = spawnSync('unzip', ['-o', '-q', zip, '-d', into], { windowsHide: true }); // Linux
  if (r.status !== 0) throw new Error('Could not extract the downloaded archive (tar/unzip failed)');
}

async function installDeno(binDir, opts = {}) {
  const url = DENO_ZIP[process.platform];
  if (!url) throw new Error('Automatic Deno install is not available on this platform');
  const work = await fsp.mkdtemp(path.join(os.tmpdir(), 'vg-deno-'));
  try {
    const zip = path.join(work, 'deno.zip');
    await downloadTo(url, zip, opts);
    extractZip(zip, work);
    const bin = path.join(work, exe('deno'));
    if (!fs.existsSync(bin)) throw new Error('deno was not found in the downloaded archive');
    await fsp.mkdir(binDir, { recursive: true });
    const dest = path.join(binDir, exe('deno'));
    await fsp.copyFile(bin, dest);
    if (process.platform !== 'win32') await fsp.chmod(dest, 0o755);
    if (!works(dest, ['--version'])) throw new Error('The downloaded Deno does not run on this computer');
    return { path: dest };
  } finally {
    await fsp.rm(work, { recursive: true, force: true }).catch(() => {});
  }
}

const FFMPEG_ZIP = 'https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip';

async function installFfmpeg(binDir, opts = {}) {
  if (process.platform !== 'win32') throw new Error('Install ffmpeg with your package manager (apt, brew, …)');
  const work = await fsp.mkdtemp(path.join(os.tmpdir(), 'vg-ffmpeg-'));
  try {
    const zip = path.join(work, 'ffmpeg.zip');
    await downloadTo(FFMPEG_ZIP, zip, opts);
    // tar.exe ships with Windows 10 1803+ and extracts zip archives.
    const r = spawnSync('tar', ['-xf', zip, '-C', work], { windowsHide: true });
    if (r.status !== 0) throw new Error('Could not extract ffmpeg (tar.exe failed)');
    const found = {};
    const walk = async (d) => {
      for (const e of await fsp.readdir(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) await walk(p);
        else if (/^(ffmpeg|ffprobe)\.exe$/i.test(e.name)) found[e.name.toLowerCase()] = p;
      }
    };
    await walk(work);
    if (!found['ffmpeg.exe']) throw new Error('ffmpeg.exe was not found in the downloaded archive');
    await fsp.mkdir(binDir, { recursive: true });
    for (const n of ['ffmpeg.exe', 'ffprobe.exe']) if (found[n]) await fsp.copyFile(found[n], path.join(binDir, n));
    return { path: path.join(binDir, 'ffmpeg.exe') };
  } finally {
    await fsp.rm(work, { recursive: true, force: true }).catch(() => {});
  }
}

/** Kill a child process and everything it started (yt-dlp.exe is a launcher for a second process). */
function killTree(child) {
  if (!child || child.exitCode !== null || !child.pid) return;
  if (process.platform === 'win32') {
    try {
      spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    } catch {
      /* already gone */
    }
  } else {
    try {
      process.kill(-child.pid, 'SIGKILL'); // child was started detached = its own process group
    } catch {
      try {
        child.kill('SIGKILL');
      } catch {
        /* already gone */
      }
    }
  }
}

module.exports = { exe, findYtdlp, findFfmpeg, findDeno, findNode, findFfprobe, probeMedia, grabFrame, installDeno, installYtdlp, updateYtdlp, installFfmpeg, killTree, FatalError };
