'use strict';
const { app, BrowserWindow, Tray, Menu, ipcMain, dialog, shell, clipboard, Notification, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');
const { DownloadManager } = require('../src/core/manager');
const { createApiServer } = require('../src/core/server');
const { expandPattern } = require('../src/core/batch');
const { grab } = require('../src/core/grabber');
const { dispatcherFor } = require('../src/core/http');
const { probe } = require('../src/core/download-task');
const tools = require('../src/core/tools');
const names = require('../src/core/filename');

const ICON = (() => {
  const dir = path.join(__dirname, '..', 'build');
  const ico = path.join(dir, 'icon.ico');
  return process.platform === 'win32' && fs.existsSync(ico) ? ico : path.join(dir, 'icon.png');
})();
const R = (...p) => path.join(__dirname, '..', 'renderer', ...p);

let win = null;
let tray = null;
let manager = null;
let api = null;
let quitting = false;
let lastClip = '';
let pushTimer = null;
let grabAbort = null;
const dialogs = new Map(); // `${kind}:${id}` -> BrowserWindow
const confirmedAsk = new Set(); // File Info dialogs closed through Start/Later (not cancelled)

if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}
app.setAppUserModelId('io.encodal.idm');
// Keep downloads, settings and the browser pairing code from the app's former names.
try {
  const base = app.getPath('appData');
  const newData = path.join(base, 'IDM By Encodal');
  if (!fs.existsSync(newData)) {
    const legacy = ['Downloader By Encodal', 'VelociGet'].map((n) => path.join(base, n)).find((d) => fs.existsSync(d));
    if (legacy) app.setPath('userData', legacy);
  }
} catch { /* first run */ }
app.on('second-instance', () => showWindow());

const extensionDir = () => (app.isPackaged ? path.join(process.resourcesPath, 'extension') : path.join(__dirname, '..', 'extension'));
const prefs = () => ({ contextIsolation: true, nodeIntegration: false, sandbox: true, preload: path.join(__dirname, 'preload.js') });

// ---- windows -------------------------------------------------------------------------------

function showWindow() {
  if (!win || win.isDestroyed()) createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
  pushNow();
}

function createWindow() {
  win = new BrowserWindow({
    width: 1020, height: 640, minWidth: 760, minHeight: 420,
    show: !process.argv.includes('--hidden'),
    icon: ICON, title: 'IDM By Encodal', backgroundColor: '#f0f0f0',
    webPreferences: prefs(),
  });
  win.loadFile(R('index.html'));
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      win.hide();
    }
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

/** Small IDM-style dialog windows: file info, per-download progress, download complete. */
function openDialog(kind, id, size) {
  const key = `${kind}:${id}`;
  const existing = dialogs.get(key);
  if (existing && !existing.isDestroyed()) {
    existing.show();
    existing.focus();
    return existing;
  }
  const w = new BrowserWindow({
    width: size[0], height: size[1], useContentSize: true, resizable: false, minimizable: kind === 'progress', maximizable: false,
    show: false, icon: ICON, title: 'IDM By Encodal', backgroundColor: '#f0f0f0',
    parent: kind === 'fileinfo' && win && !win.isDestroyed() && win.isVisible() ? win : undefined,
    webPreferences: prefs(),
  });
  w.setMenu(null);
  w.loadFile(R('dialogs', `${kind}.html`), { query: { id } });
  w.once('ready-to-show', () => w.show());
  w.on('closed', () => {
    dialogs.delete(key);
    if (kind === 'fileinfo' && !confirmedAsk.delete(id)) dropPristine(id); // closed with the X = cancel
  });
  w.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  dialogs.set(key, w);
  return w;
}
/** A download that was only created for the File Info dialog and never started. */
async function dropPristine(id) {
  const t = manager && manager.get(id);
  if (t && t.rec.state === 'paused' && !t.rec.savePath && !t.rec.downloaded && !t.running) {
    await manager.remove(id);
    pushNow();
  }
}
const openProgress = (id) => manager.getSettings().showProgressDialog && openDialog('progress', id, [560, 600]);
const closeDialog = (kind, id) => {
  const w = dialogs.get(`${kind}:${id}`);
  if (w && !w.isDestroyed()) w.close();
};

// ---- menu ---------------------------------------------------------------------------------------

function buildMenu() {
  const cmd = (c) => () => { showWindow(); win.webContents.send('ui:cmd', c); };
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      {
        label: 'Tasks',
        submenu: [
          { label: 'Add new download…', accelerator: 'CmdOrCtrl+N', click: cmd('addUrl') },
          { label: 'Add batch download from pattern…', click: cmd('batch') },
          { label: 'Run site grabber…', click: cmd('grabber') },
          { type: 'separator' },
          { label: 'Scheduler…', click: cmd('scheduler') },
          { type: 'separator' },
          { label: 'Exit', click: () => app.quit() },
        ],
      },
      {
        label: 'File',
        submenu: [
          { label: 'Open', click: cmd('open') },
          { label: 'Open folder', click: cmd('openFolder') },
          { label: 'Properties…', click: cmd('progress') },
          { type: 'separator' },
          { label: 'Refresh download address…', click: cmd('refresh') },
          { label: 'Copy address', click: cmd('copyUrl') },
        ],
      },
      {
        label: 'Downloads',
        submenu: [
          { label: 'Resume', click: cmd('resume') },
          { label: 'Stop', click: cmd('stop') },
          { label: 'Stop all', click: cmd('stopAll') },
          { type: 'separator' },
          { label: 'Delete', accelerator: 'Delete', click: cmd('delete') },
          { label: 'Delete completed', click: cmd('deleteCompleted') },
          { type: 'separator' },
          { label: 'Start queue', click: cmd('startQueue') },
          { label: 'Stop queue', click: cmd('stopQueue') },
          { type: 'separator' },
          { label: 'Options…', accelerator: 'CmdOrCtrl+O', click: cmd('options') },
        ],
      },
      { label: 'View', submenu: [{ role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }] },
      {
        label: 'Help',
        submenu: [
          { label: 'Install the browser extension…', click: () => { showWindow(); win.webContents.send('ui:cmd', 'extension'); } },
          { label: 'Update the site video engine (yt-dlp)', click: () => updateYtdlpNow(true) },
          { type: 'separator' },
          { label: `About IDM By Encodal ${app.getVersion()}`, click: () => dialog.showMessageBox({ message: `IDM By Encodal ${app.getVersion()}`, detail: 'Multi-connection download manager with browser integration.' }) },
        ],
      },
    ]),
  );
}

// ---- push updates to the UI (throttled) ---------------------------------------------------------

function broadcast() {
  const list = manager.list();
  for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed() && w.isVisible()) w.webContents.send('dm:update', list);
}
function pushNow() {
  broadcast();
  updateAwake();
  updateTrayTooltip();
}
let awakeId = null;
function updateAwake() {
  const want = manager.getSettings().keepAwake !== false && manager.list().some((d) => ['probing', 'downloading', 'merging'].includes(d.state));
  const { powerSaveBlocker } = require('electron');
  if (want && awakeId === null) awakeId = powerSaveBlocker.start('prevent-app-suspension');
  else if (!want && awakeId !== null) { powerSaveBlocker.stop(awakeId); awakeId = null; }
}
function schedulePush() {
  if (pushTimer) return;
  pushTimer = setTimeout(() => {
    pushTimer = null;
    pushNow();
  }, 350);
}

const fmtSpeed = (bps) => (bps > 1048576 ? `${(bps / 1048576).toFixed(1)} MB/s` : `${Math.round(bps / 1024)} KB/s`);
function updateTrayTooltip() {
  if (!tray) return;
  const active = manager.list().filter((d) => ['downloading', 'probing'].includes(d.state));
  tray.setToolTip(active.length ? `IDM By Encodal - ${active.length} active, ${fmtSpeed(active.reduce((n, d) => n + d.speed, 0))}` : 'IDM By Encodal');
}

// ---- tray ----------------------------------------------------------------------------------------------

function createTray() {
  const img = fs.existsSync(ICON) ? nativeImage.createFromPath(ICON).resize({ width: 16, height: 16 }) : nativeImage.createEmpty();
  tray = new Tray(img);
  const build = () => {
    const limit = manager.getSettings().speedLimit;
    const limits = [['Unlimited', 0], ['2 MB/s', 2048], ['1 MB/s', 1024], ['500 KB/s', 500], ['100 KB/s', 100]];
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: 'Open IDM By Encodal', click: showWindow },
        { label: 'Add URL from clipboard', click: () => { showWindow(); win.webContents.send('ui:add-url', clipboard.readText().trim()); } },
        { type: 'separator' },
        { label: 'Resume all', click: () => manager.startAll() },
        { label: 'Stop all', click: () => manager.pauseAll() },
        { label: 'Speed limit', submenu: limits.map(([label, kb]) => ({ label, type: 'radio', checked: limit === kb * 1024, click: () => manager.setSettings({ speedLimit: kb * 1024 }) })) },
        { type: 'separator' },
        { label: 'Exit', click: () => app.quit() },
      ]),
    );
  };
  build();
  manager.on('settings', build);
  tray.on('click', showWindow);
}

// ---- completion, post actions, clipboard ---------------------------------------------------------------

const ACTION_LABEL = { exit: 'Exit IDM By Encodal', sleep: 'Put the computer to sleep', hibernate: 'Hibernate the computer', shutdown: 'Shut down the computer' };

async function runPostAction(action, what) {
  if (!ACTION_LABEL[action]) return;
  const shown = dialog.showMessageBox({ type: 'question', buttons: ['Cancel', 'Do it now'], defaultId: 0, cancelId: 0, title: 'Download finished', message: `${what} has finished.`, detail: `${ACTION_LABEL[action]} in 60 seconds unless you cancel.` });
  const choice = await Promise.race([shown, new Promise((r) => setTimeout(() => r({ response: 1 }), 60_000))]);
  if (choice.response !== 1) return;
  if (action === 'exit') return app.quit();
  if (process.platform !== 'win32') return;
  const cmds = { shutdown: ['shutdown', ['/s', '/t', '0']], hibernate: ['shutdown', ['/h']], sleep: ['rundll32.exe', ['powrprof.dll,SetSuspendState', '0,1,0']] };
  const [c, args] = cmds[action];
  await manager.shutdown();
  execFile(c, args, () => {});
}

function onCompleted(id) {
  const t = manager.get(id);
  if (!t) return;
  const rec = t.rec;
  closeDialog('progress', id);
  const s = manager.getSettings();
  if (s.showCompleteDialog) openDialog('complete', id, [460, 210]);
  else if (s.notifyOnComplete && Notification.isSupported()) {
    const n = new Notification({ title: 'Download complete', body: rec.fileName, icon: ICON });
    n.on('click', () => shell.showItemInFolder(rec.savePath));
    n.show();
  }
  if (rec.onComplete && rec.onComplete !== 'none') runPostAction(rec.onComplete, rec.fileName);
}

function startClipboardWatcher() {
  lastClip = clipboard.readText().trim();
  setInterval(() => {
    const s = manager.getSettings();
    if (!s.clipboardWatch) return;
    const text = clipboard.readText().trim();
    if (!text || text === lastClip) return;
    lastClip = text;
    if (text.length > 2048 || /\s/.test(text) || !/^https?:\/\//i.test(text)) return;
    let ext = '';
    try { ext = path.extname(new URL(text).pathname).slice(1).toLowerCase(); } catch { return; }
    const site = require('../src/core/ytdlp').isSiteUrl(text);
    if (!require('../src/core/patterns').extMatches(s.captureExtensions, ext) && ext !== 'm3u8' && !site) return;
    if (!Notification.isSupported()) return;
    const n = new Notification({ title: 'Download link detected', body: `${text.slice(0, 140)}\nClick to download`, icon: ICON });
    n.on('click', () => { showWindow(); win.webContents.send('ui:add-url', text); });
    n.show();
  }, 1000).unref();
}

// ---- site-video tools (yt-dlp / ffmpeg) ---------------------------------------------------------------------

const toolStatus = () => {
  const s = manager.getSettings();
  return { ytdlp: tools.findYtdlp(s, manager.binDir), ffmpeg: tools.findFfmpeg(s, manager.binDir), deno: tools.findDeno(s, manager.binDir) || (tools.findNode() ? 'node' : null), platform: process.platform };
};

async function updateYtdlpNow(showResult) {
  const s = manager.getSettings();
  const found = tools.findYtdlp(s, manager.binDir);
  let msg;
  try {
    if (!found) {
      const r = await tools.installYtdlp(manager.binDir, { settings: s });
      msg = `Site video engine installed (yt-dlp ${r.version}).`;
    } else if (found.path === 'yt-dlp') {
      msg = 'yt-dlp was found on your PATH; update it with your package manager (pip install -U yt-dlp).';
    } else {
      const r = await tools.updateYtdlp(found.path);
      msg = r.ok ? `yt-dlp: ${r.message || 'up to date'}` : `Update failed: ${r.message}`;
    }
    manager.setSettings({ ytdlpCheckedAt: Date.now() });
  } catch (e) {
    msg = `Could not install/update yt-dlp: ${e.message}`;
  }
  if (showResult) dialog.showMessageBox({ message: msg });
  return msg;
}

async function bootstrapTools() {
  const s = manager.getSettings();
  const found = tools.findYtdlp(s, manager.binDir);
  try {
    if (!found) await updateYtdlpNow(false);
    else if (s.autoUpdateYtdlp && found.path !== 'yt-dlp' && Date.now() - (s.ytdlpCheckedAt || 0) > 3 * 86400_000) await updateYtdlpNow(false);
  } catch {
    /* offline: try again next start */
  }
  // YouTube's player script needs a JavaScript runtime; without one yt-dlp only sees thumbnails.
  try {
    if (!toolStatus().deno) await tools.installDeno(manager.binDir, { settings: manager.getSettings() });
  } catch {
    /* offline: Options → Site videos has a button, and the next start retries */
  }
  // Windows has no ffmpeg by default; HD site videos (video + audio streams) need it to be merged.
  const t = toolStatus();
  if (process.platform === 'win32' && !t.ffmpeg && !manager.getSettings().ffmpegPrompted) {
    manager.setSettings({ ffmpegPrompted: true });
    const r = await dialog.showMessageBox({
      type: 'question', buttons: ['Install now', 'Later'], defaultId: 0, cancelId: 1,
      title: 'IDM By Encodal', message: 'Install ffmpeg for HD videos?',
      detail: 'YouTube and similar sites deliver HD video and audio separately. ffmpeg (about 90 MB) joins them. Without it only lower qualities are available. You can also install it later from Options → Site videos.',
    });
    if (r.response === 0) {
      try { await tools.installFfmpeg(manager.binDir, { settings: manager.getSettings() }); dialog.showMessageBox({ message: 'ffmpeg installed.' }); } catch (e) { dialog.showErrorBox('IDM By Encodal', `ffmpeg install failed: ${e.message}`); }
    }
  }
}

// ---- IPC ----------------------------------------------------------------------------------------------------

function registerIpc() {
  const h = (name, fn) => ipcMain.handle(name, async (_e, arg) => fn(arg));

  h('dm:list', () => manager.list());
  h('dm:get', (id) => manager.get(id)?.snapshot() || null);

  h('dm:apiCall', async ({ route, body }) => {
    // only the look-up and add calls the "New" dialogs need
    if (!['POST /api/probe-media', 'POST /api/link-details', 'POST /api/link-frame', 'POST /api/add', 'POST /api/add-many'].includes(route)) throw new Error('Not allowed');
    return await api.call(route, body);
  });

  h('dm:add', (p) => {
    const urls = (p.urls || []).map((u) => String(u).trim()).filter(Boolean);
    const single = urls.length === 1;
    return urls.map((url) => {
      try {
        return manager.add({
          url, dir: p.dir || '', fileName: single ? p.fileName || '' : '', queueId: p.queueId || 'main',
          startNow: p.startNow !== false, ask: single && p.ask === true, referer: p.referer || '', connections: p.connections || 0, cookie: p.cookie || '', headers: p.headers || {},
        });
      } catch (e) {
        return { error: e.message, url };
      }
    });
  });

  h('dm:action', async ({ ids = [], action, value }) => {
    for (const id of ids) {
      if (action === 'start') { manager.start(id); openProgress(id); }
      else if (action === 'pause') manager.pause(id);
      else if (action === 'remove') await manager.remove(id, { deleteFile: false });
      else if (action === 'removeFile') await manager.remove(id, { deleteFile: true });
      else if (action === 'refresh') manager.refreshUrl(id, value);
      else if (action === 'queue') manager.moveToQueue(id, value);
      else if (action === 'redownload') { const t = manager.get(id); if (t) { await t.removePartial(); Object.assign(t.rec, { segments: [], downloaded: 0, savePath: '', partPath: '', state: 'paused', completedAt: null }); manager.start(id); } }
    }
    pushNow();
    return true;
  });
  h('dm:deleteCompleted', async () => {
    for (const d of manager.list()) if (d.state === 'completed') await manager.remove(d.id);
    pushNow();
  });
  h('dm:startAll', () => manager.startAll());
  h('dm:pauseAll', () => manager.pauseAll());
  h('dm:patch', ({ id, patch }) => manager.patch(id, patch));

  // "Download File Info" result
  h('dm:confirm', ({ id, start, patch }) => {
    if (patch) manager.patch(id, patch);
    confirmedAsk.add(id);
    closeDialog('fileinfo', id);
    if (start) { manager.start(id); openProgress(id); }
    pushNow();
  });
  h('dm:cancelAsk', (id) => closeDialog('fileinfo', id)); // the 'closed' handler drops the download
  h('dm:probe', async (id) => {
    const t = manager.get(id);
    if (!t) return null;
    const r = t.rec;
    if (r.kind === 'ytdlp') return { size: -1, fileName: r.pageTitle ? names.sanitize(r.pageTitle) + (r.ytdlp && r.ytdlp.audio ? '.m4a' : '.mp4') : '', note: r.ytdlp && r.ytdlp.height ? `${r.ytdlp.height}p video` : r.ytdlp && r.ytdlp.audio ? 'Audio only' : 'Best quality' };
    if (r.kind === 'hls') return { size: -1, fileName: names.sanitize(r.pageTitle || 'video') + '.mp4', note: 'HLS stream' };
    if (r.kind === 'ftp') return { size: -1, fileName: names.sanitize(decodeURIComponent(new URL(r.url).pathname.split('/').pop() || 'download')), note: 'FTP file' };
    try {
      const s = manager.getSettings();
      const info = await probe(r.url, t.baseHeaders(), AbortSignal.timeout(20_000), dispatcherFor(s.proxy));
      return { size: info.total, fileName: info.fileName, resumable: info.resumable };
    } catch (e) {
      return { error: e.message };
    }
  });
  h('dm:category-dir', (cat) => {
    const s = manager.getSettings();
    return s.categorize && cat !== 'General' ? path.join(s.downloadDir, cat) : s.downloadDir;
  });

  h('settings:get', () => manager.getSettings());
  h('settings:set', (patch) => {
    const next = manager.setSettings(patch);
    app.setLoginItemSettings({ openAtLogin: !!next.startWithWindows, args: ['--hidden'] });
    return next;
  });

  h('queue:save', (q) => (manager.saveQueue(q), manager.getSettings()));
  h('queue:delete', (id) => (manager.deleteQueue(id), manager.getSettings()));
  h('queue:start', (id) => manager.startQueue(id));
  h('queue:stop', (id) => manager.stopQueue(id));

  h('batch:preview', (pattern) => {
    try { return { urls: expandPattern(pattern) }; } catch (e) { return { error: e.message }; }
  });
  h('grab:run', async (opts) => {
    grabAbort?.abort();
    grabAbort = new AbortController();
    try { return await grab({ ...opts, signal: grabAbort.signal, dispatcher: dispatcherFor(manager.getSettings().proxy) }); } catch (e) { return { error: e.message, files: [] }; }
  });
  h('grab:cancel', () => grabAbort?.abort());

  h('tools:status', () => toolStatus());
  h('tools:install', async (which) => {
    const s = manager.getSettings();
    try {
      if (which === 'ytdlp') return { ok: true, message: await updateYtdlpNow(false) };
      if (which === 'deno') { await tools.installDeno(manager.binDir, { settings: s }); return { ok: true, message: 'Deno installed.' }; }
      if (which === 'ffmpeg') { await tools.installFfmpeg(manager.binDir, { settings: s }); return { ok: true, message: 'ffmpeg installed.' }; }
    } catch (e) {
      return { ok: false, message: e.message };
    }
    return { ok: false, message: 'Unknown tool' };
  });

  h('dialog:dir', async () => {
    const r = await dialog.showOpenDialog(BrowserWindow.getFocusedWindow() || win, { properties: ['openDirectory', 'createDirectory'] });
    return r.canceled ? null : r.filePaths[0];
  });
  h('dialog:progress', (id) => { openDialog('progress', id, [500, 400]); });
  h('shell:open', (p) => (p ? shell.openPath(p) : null));
  h('shell:show', (p) => (p ? shell.showItemInFolder(p) : null));
  h('app:info', () => {
    const s = manager.getSettings();
    return { pairingCode: `${s.apiPort}:${s.apiToken}`, extensionDir: extensionDir(), version: app.getVersion(), platform: process.platform };
  });
  h('app:openExtensionFolder', () => shell.openPath(extensionDir()));
  h('app:copy', (text) => clipboard.writeText(String(text)));
  h('win:resize', (height) => {
    const w = BrowserWindow.getFocusedWindow();
    if (w && w !== win) w.setContentSize(w.getContentSize()[0], Math.max(120, Math.min(900, Math.round(height))));
  });
  h('win:close', () => BrowserWindow.getFocusedWindow()?.close());
}

// ---- lifecycle -----------------------------------------------------------------------------------------------

app.whenReady().then(async () => {
  manager = new DownloadManager({ dataDir: app.getPath('userData') });
  await manager.init();
  manager.on('update', schedulePush);
  manager.on('progress', schedulePush);
  manager.on('removed', pushNow);
  manager.on('added', (id, o = {}) => {
    pushNow();
    if (!o.ask && o.startNow !== false) openProgress(id);
  });
  manager.on('ask', (id) => {
    if (manager.getSettings().showFileInfoDialog) openDialog('fileinfo', id, [520, 330]);
    else { manager.start(id); openProgress(id); }
  });
  manager.on('completed', onCompleted);
  manager.on('queue-finished', (q) => runPostAction(q.postAction, `"${q.name}"`));

  registerIpc();
  buildMenu();
  api = createApiServer(manager, { onFocus: showWindow });
  try {
    await api.listen();
  } catch (e) {
    dialog.showErrorBox('IDM By Encodal', `The browser-integration port ${manager.getSettings().apiPort} is in use (${e.code}). Change it in Options, then restart.`);
  }
  createWindow();
  createTray();
  startClipboardWatcher();
  app.setLoginItemSettings({ openAtLogin: !!manager.getSettings().startWithWindows, args: ['--hidden'] });
  bootstrapTools();
});

app.on('window-all-closed', () => { /* keep running in the tray */ });

app.on('before-quit', (e) => {
  if (quitting) return;
  quitting = true;
  e.preventDefault();
  (async () => {
    try {
      await manager?.shutdown();
      await api?.close();
    } finally {
      app.exit(0);
    }
  })();
});
