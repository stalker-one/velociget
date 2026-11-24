'use strict';
const os = require('os');
const path = require('path');
const crypto = require('crypto');

// The list Internet Download Manager ships with ("Automatically start downloading the following file types").
const CAPTURE_EXTENSIONS = (
  '3gp 7z aac ace aif apk arj asf avi bin bz2 exe gz gzip img iso lzh m4a m4v mkv mov mp3 mp4 mpa mpe mpeg mpg msi msu ogg ogv pdf plj pps ppt qt r0* r1* ra rar rm rmvb sea sit sitx tar tif tiff wav wma wmv z zip'
).split(' ');
const EXCLUDED_SITES = ['*.update.microsoft.com', 'download.windowsupdate.com', 'siteseal.thawte.com', 'ecom.cimetz.com', '*.voice2page.com'];
const FILE_TYPES_VERSION = 3;

function defaultSettings() {
  return {
    downloadDir: path.join(os.homedir(), 'Downloads'),
    categorize: true,
    maxConnections: 8, // per download, 1-32
    ytDirect: true, // site videos: download the streams with the multi-connection engine
    theme: 'system', // light | dark | system
    keepAwake: true, // keep the PC from sleeping while downloads run
    maxConcurrent: 6, // simultaneous downloads
    speedLimit: 0, // bytes/second, 0 = unlimited
    proxy: null, // { host, port, user, pass } (HTTP/HTTPS proxy)
    userAgent: '',
    preserveTime: true,
    ffmpegPath: '',
    ytdlpPath: '',
    denoPath: '',
    autoUpdateYtdlp: true,
    ytdlpCheckedAt: 0,
    ffmpegPrompted: false,

    // IDM-style dialogs
    showFileInfoDialog: true, // "Download File Info" before a captured download starts
    showProgressDialog: true, // per-download progress window
    showCompleteDialog: true,

    // Browser integration
    apiPort: 24680,
    apiToken: crypto.randomBytes(24).toString('hex'),
    captureEnabled: true,
    captureExtensions: CAPTURE_EXTENSIONS,
    minCaptureSize: 512 * 1024,
    excludedSites: EXCLUDED_SITES,
    excludedAddresses: [],
    fileTypesVersion: FILE_TYPES_VERSION,
    showVideoOverlay: true,

    // Desktop behaviour
    clipboardWatch: true,
    startWithWindows: false,
    notifyOnComplete: true,
    virusScanCommand: '', // e.g.  "C:\\Program Files\\...\\scan.exe" "%FILE%"

    queues: [
      {
        id: 'main',
        name: 'Main download queue',
        schedule: { enabled: false, startAt: '02:00', stopAt: '', days: [0, 1, 2, 3, 4, 5, 6] },
        postAction: 'none', // none | exit | sleep | hibernate | shutdown
      },
    ],
  };
}

module.exports = { defaultSettings, CAPTURE_EXTENSIONS, EXCLUDED_SITES, FILE_TYPES_VERSION };
