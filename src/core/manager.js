'use strict';
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const { spawn } = require('child_process');
const { DownloadTask, newRecord } = require('./download-task');
const { Throttle } = require('./throttle');
const { JsonStore } = require('./store');
const { defaultSettings } = require('./settings');

const ACTIVE = new Set(['probing', 'downloading', 'merging']);

class DownloadManager extends EventEmitter {
  constructor({ dataDir, settings } = {}) {
    super();
    this.dataDir = dataDir;
    this.binDir = dataDir ? path.join(dataDir, 'bin') : null;
    this.tasks = new Map();
    this.settings = { ...defaultSettings(), ...(settings || {}) };
    this.throttle = new Throttle(this.settings.speedLimit);
    this.downloadsStore = new JsonStore(path.join(dataDir, 'downloads.json'), () => ({ downloads: [] }));
    this.settingsStore = new JsonStore(path.join(dataDir, 'settings.json'), () => ({}));
    this._firedMinute = new Map();
    this._schedTimer = null;
  }

  async init() {
    const saved = await this.settingsStore.load();
    this.settings = { ...defaultSettings(), ...this.settings, ...saved };
    // Settings saved by an older build get the IDM default file types and excluded sites once.
    if ((saved.fileTypesVersion || 0) < defaultSettings().fileTypesVersion) {
      const d = defaultSettings();
      this.settings.captureExtensions = d.captureExtensions;
      this.settings.excludedSites = [...new Set([...(saved.excludedSites || []), ...d.excludedSites])];
      this.settings.fileTypesVersion = d.fileTypesVersion;
      if (saved.maxConcurrent === 3) this.settings.maxConcurrent = d.maxConcurrent; // old default -> bulk saves run in parallel
      if (saved.maxConnections === 16) this.settings.maxConnections = d.maxConnections; // old default -> requested 8-connection default
    }
    // Keep a stable API token across runs.
    if (!saved.apiToken) this._saveSettings();
    this.throttle.setLimit(this.settings.speedLimit);

    const data = await this.downloadsStore.load();
    for (const rec of data.downloads || []) {
      if (ACTIVE.has(rec.state) || rec.state === 'queued') rec.state = 'paused'; // interrupted by exit
      this._register(new DownloadTask(rec, this._ctx()));
    }
    this._schedTimer = setInterval(() => this._schedulerTick(), 15_000);
    this._schedTimer.unref?.();
  }

  async shutdown() {
    clearInterval(this._schedTimer);
    for (const t of this.tasks.values()) if (t.running) t.pause();
    await Promise.all([...this.tasks.values()].map((t) => t._runPromise).filter(Boolean));
    await new Promise((r) => setTimeout(r, 100));
    this._saveDownloads();
    await this.downloadsStore.flush();
    await this.settingsStore.flush();
  }

  _ctx() {
    return { throttle: this.throttle, settings: () => this.settings, binDir: this.binDir };
  }

  _register(task) {
    this.tasks.set(task.rec.id, task);
    task.on('state', () => {
      this._saveDownloads();
      this.emit('update', task.rec.id);
    });
    task.on('progress', () => this.emit('progress', task.rec.id));
    task.on('settled', () => this._onSettled(task));
  }

  // ---- settings -----------------------------------------------------------------

  getSettings() {
    return this.settings;
  }

  setSettings(patch) {
    const next = { ...this.settings, ...patch };
    next.maxConnections = clamp(Math.round(next.maxConnections) || 8, 1, 32);
    next.maxConcurrent = clamp(Math.round(next.maxConcurrent) || 3, 1, 16);
    next.speedLimit = Math.max(0, Math.round(next.speedLimit) || 0);
    this.settings = next;
    this.throttle.setLimit(next.speedLimit);
    this._saveSettings();
    this.emit('settings', next);
    this._pump();
    return next;
  }

  _saveSettings() {
    this.settingsStore.save(() => this.settings);
  }

  // ---- downloads ----------------------------------------------------------------

  /**
   * @param spec { url, referer, cookie, userAgent, headers, fileName, dir, category, queueId,
   *               kind ('http'|'hls'), hls, pageTitle, connections, startNow }
   */
  add(spec) {
    const url = String(spec.url || '').trim();
    let parsed;
    try {
      parsed = new URL(url);
    } catch {
      throw new Error(`Invalid URL: ${url}`);
    }
    if (!/^(https?|ftps?):$/.test(parsed.protocol)) throw new Error('Only http://, https://, ftp:// and ftps:// links are supported');

    for (const t of this.tasks.values()) {
      const sameQuality = JSON.stringify(t.rec.ytdlp || null) === JSON.stringify(spec.ytdlp || null);
      if (t.rec.url === url && sameQuality && t.rec.state !== 'completed' && t.rec.state !== 'canceled') {
        if (spec.startNow !== false && t.rec.state !== 'downloading') this.start(t.rec.id);
        return { id: t.rec.id, duplicate: true };
      }
    }

    const kind =
      spec.kind || (/^ftps?:$/.test(parsed.protocol) ? 'ftp' : /\.m3u8(\?|$)/i.test(parsed.pathname + parsed.search) ? 'hls' : require('./ytdlp').isSiteUrl(url) ? 'ytdlp' : 'http');
    const rec = newRecord({ ...spec, url, kind, id: crypto.randomUUID() });
    if (!this.settings.queues.find((q) => q.id === rec.queueId)) rec.queueId = 'main';
    if (rec.dir) rec.autoDir = false;
    const task = new DownloadTask(rec, this._ctx());
    this._register(task);
    this._saveDownloads();
    this.emit('added', rec.id, { ask: !!spec.ask, startNow: spec.startNow !== false });
    if (spec.ask) {
      // The UI shows "Download File Info" and starts (or drops) the download from there.
      rec.state = 'paused';
      this.emit('update', rec.id);
      this.emit('ask', rec.id);
    } else if (spec.startNow === false) {
      rec.state = 'paused';
      this.emit('update', rec.id);
    } else this.start(rec.id);
    return { id: rec.id, duplicate: false };
  }

  get(id) {
    return this.tasks.get(id);
  }

  list() {
    return [...this.tasks.values()].map((t) => t.snapshot());
  }

  start(id) {
    const t = this.tasks.get(id);
    if (!t || t.running || t.rec.state === 'completed') return;
    t.setState('queued');
    this._pump();
  }

  pause(id) {
    this.tasks.get(id)?.pause();
  }

  startAll() {
    for (const t of this.tasks.values()) if (['paused', 'error'].includes(t.rec.state)) this.start(t.rec.id);
  }

  pauseAll() {
    for (const t of this.tasks.values()) t.pause();
  }

  async remove(id, { deleteFile = false } = {}) {
    const t = this.tasks.get(id);
    if (!t) return;
    if (t.running) {
      t.cancel();
      await new Promise((r) => t.once('settled', r));
    }
    if (t.rec.state !== 'completed') await t.removePartial();
    if (deleteFile && t.rec.savePath) await fsp.unlink(t.rec.savePath).catch(() => {});
    this.tasks.delete(id);
    this._saveDownloads();
    this.emit('removed', id);
  }

  /** Edit properties of a download (only some can change once it has started). */
  patch(id, p) {
    const t = this.tasks.get(id);
    if (!t) return null;
    const r = t.rec;
    if ('speedLimit' in p) {
      r.speedLimit = Math.max(0, Math.round(Number(p.speedLimit) || 0));
      t.limiter.setLimit(r.speedLimit);
    }
    if ('onComplete' in p && ['none', 'exit', 'sleep', 'hibernate', 'shutdown'].includes(p.onComplete)) r.onComplete = p.onComplete;
    if ('description' in p) r.description = String(p.description || '').slice(0, 500);
    if (!r.savePath && !r.workDir) {
      if (p.dir) {
        r.dir = p.dir;
        r.autoDir = false;
      }
      if (p.fileName) {
        r.fileName = require('./filename').sanitize(p.fileName);
        r.fileNameLocked = true;
      }
      if (p.category) r.category = p.category;
    }
    this._saveDownloads();
    this.emit('update', id);
    return t.snapshot();
  }

  /** Replace the address of an expired link and restart. */
  refreshUrl(id, url, extra = {}) {
    const t = this.tasks.get(id);
    if (!t) return;
    t.rec.url = url;
    t.rec.finalUrl = null;
    Object.assign(t.rec, extra);
    this.start(id);
  }

  moveToQueue(id, queueId) {
    const t = this.tasks.get(id);
    if (t && this.settings.queues.find((q) => q.id === queueId)) {
      t.rec.queueId = queueId;
      this._saveDownloads();
      this.emit('update', id);
    }
  }

  _pump() {
    let running = 0;
    for (const t of this.tasks.values()) if (t.running) running++;
    for (const t of this.tasks.values()) {
      if (running >= this.settings.maxConcurrent) break;
      if (t.rec.state === 'queued' && !t.running) {
        t._runPromise = t.start();
        running++;
      }
    }
  }

  _onSettled(task) {
    this._saveDownloads();
    if (task.rec.state === 'completed') {
      this.emit('completed', task.rec.id);
      this._virusScan(task);
    }
    this._pump();
    this._checkQueueFinished(task.rec.queueId);
  }

  _virusScan(task) {
    const cmd = (this.settings.virusScanCommand || '').trim();
    if (!cmd || !task.rec.savePath) return;
    const file = task.rec.savePath;
    // The command is user-configured; the file name is passed as a single, quoted argument.
    const full = cmd.includes('%FILE%') ? cmd.split('%FILE%').join(`"${file}"`) : `${cmd} "${file}"`;
    try {
      const p = spawn(full, { shell: true, stdio: 'ignore', detached: true });
      p.on('error', () => {});
      p.unref();
    } catch {
      /* scanning is best effort */
    }
  }

  // ---- queues & scheduler -----------------------------------------------------

  getQueues() {
    return this.settings.queues;
  }

  saveQueue(queue) {
    const queues = [...this.settings.queues];
    const i = queues.findIndex((q) => q.id === queue.id);
    if (i >= 0) queues[i] = { ...queues[i], ...queue };
    else queues.push({ id: crypto.randomUUID().slice(0, 8), name: 'New queue', schedule: { enabled: false, startAt: '', stopAt: '', days: [0, 1, 2, 3, 4, 5, 6] }, postAction: 'none', ...queue });
    this.setSettings({ queues });
  }

  deleteQueue(id) {
    if (id === 'main') return;
    for (const t of this.tasks.values()) if (t.rec.queueId === id) t.rec.queueId = 'main';
    this.setSettings({ queues: this.settings.queues.filter((q) => q.id !== id) });
  }

  startQueue(queueId) {
    for (const t of this.tasks.values()) {
      if (t.rec.queueId === queueId && ['paused', 'error'].includes(t.rec.state)) this.start(t.rec.id);
    }
  }

  stopQueue(queueId) {
    for (const t of this.tasks.values()) if (t.rec.queueId === queueId) t.pause();
  }

  _schedulerTick(now = new Date()) {
    const hhmm = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    for (const q of this.settings.queues) {
      const s = q.schedule;
      if (!s || !s.enabled || !(s.days || []).includes(now.getDay())) continue;
      const key = `${q.id}:${now.toDateString()}:${hhmm}`;
      if (this._firedMinute.get(q.id) === key) continue;
      if (s.startAt && s.startAt === hhmm) {
        this._firedMinute.set(q.id, key);
        this.startQueue(q.id);
      } else if (s.stopAt && s.stopAt === hhmm) {
        this._firedMinute.set(q.id, key);
        this.stopQueue(q.id);
      }
    }
  }

  _checkQueueFinished(queueId) {
    const q = this.settings.queues.find((x) => x.id === queueId);
    if (!q || !q.postAction || q.postAction === 'none') return;
    const members = [...this.tasks.values()].filter((t) => t.rec.queueId === queueId);
    if (!members.length) return;
    const pending = members.some((t) => ACTIVE.has(t.rec.state) || t.rec.state === 'queued');
    const anyCompleted = members.some((t) => t.rec.state === 'completed');
    if (!pending && anyCompleted && members.every((t) => ['completed', 'error', 'canceled'].includes(t.rec.state))) {
      this.emit('queue-finished', q);
    }
  }

  _saveDownloads() {
    this.downloadsStore.save(() => ({ downloads: [...this.tasks.values()].map((t) => t.serialize()) }));
  }
}

const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));

module.exports = { DownloadManager };
