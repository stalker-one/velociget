# IDM By Encodal

A multi-connection download manager for Windows with a browser extension (Chrome, Edge, Firefox).
Original implementation: it reproduces the *features* of download managers like IDM from their public
behaviour; it contains no IDM code and was not produced by decompiling IDM.

## Features

| Area | What it does |
|---|---|
| Speed | Up to 32 connections per file, dynamic segmentation (idle connections take over the tail of the slowest segment), global speed limiter |
| Reliability | Pause/resume across app restarts, retries with back-off, detects a changed file (ETag/size) and restarts instead of corrupting, expired-link refresh ("Refresh download address") |
| Video | IDM-style **Download this video** bar over videos with a quality list (sizes, HD tags). **YouTube, Vimeo, Twitch, TikTok, Instagram, X and any `blob:` player** go through the yt-dlp site engine; plain files and HLS (`.m3u8`, AES-128) are downloaded directly. Separate video+audio streams are merged with ffmpeg |
| Windows | Menu bar, big toolbar, category tree, 8 resizable columns, **Download File Info** dialog, per-download **progress window** (connection bars, per-download speed limiter, action on completion), **Download complete** dialog, tray, queues, scheduler |
| Browser integration | Takes over downloads by file type/size, right-click "Download with IDM By Encodal", forwards cookies/referer/user-agent so logged-in downloads work, per-site exclusions |
| Organisation | Auto categories (Video, Music, Documents, Compressed, Programs), queues, scheduler (start/stop times, days), action when a queue finishes (exit/sleep/hibernate/shutdown) |
| Tools | Batch patterns (`img[001-100].jpg`), site grabber (crawl a page for files), clipboard link detection, tray icon, proxy, run a command (e.g. antivirus) after each download, start with Windows |

## Run it

```bash
npm install
npm run icons       # generates build/icon.png and the extension icons
npm start           # launches the app
npm test            # engine tests (segmenting, resume, HLS, site engine, queue, API security)
npm run dist        # builds the Windows installer (run on Windows)
```

Requires Node 22+ for tests; the app bundles its own runtime (Electron 45).

## Install the browser extension

1. In the app: **Options → Browser integration → Open extension folder** (or use the `extension/` folder).
2. Chrome/Edge: open `chrome://extensions` / `edge://extensions`, enable *Developer mode*, **Load unpacked**, pick the folder.
   Firefox: `about:debugging` → *This Firefox* → **Load Temporary Add-on** → `manifest.json`
   (for a permanent install, sign the zip from `npm run pack:ext` at addons.mozilla.org).
3. Open the extension's options page and paste the **pairing code** from the app (once).

## Security model

The extension talks to the app over `127.0.0.1` only. Requests need a secret bearer token (the pairing code),
browser requests are accepted only from extension origins, and the `Host` header must be loopback, so a
website cannot drive your downloader (including via DNS rebinding).

## Known limits (honest list)

- **DRM** streams (Widevine/PlayReady/FairPlay, SAMPLE-AES) are refused with a clear message, by design.
- **Live** HLS streams and **DASH (.mpd)** are not supported yet. Video sites that feed a `blob:` player via MSE only work if the
  extension saw the underlying `.mp4`/`.m3u8` request; press play first.
- YouTube and similar sites change constantly. IDM By Encodal installs and auto-updates **yt-dlp** for this (Options → Site videos). If a site stops working, click *Install / update* first. Only download content you have the right to download; this tool does not bypass DRM.
- Not implemented: FTP, SOCKS proxies, NTLM auth, Windows
  shell integration beyond tray/startup, auto-update, code signing.
- Verified here: the engine on Linux with automated tests (including the real yt-dlp + ffmpeg against a local stream with separate audio and video), the extension in real Chromium, and every window in Electron under a virtual display. **Not verified: live YouTube** (the build sandbox cannot reach it; a stand-in engine was used for the browser test). **Not verified: the Windows installer build and Windows-specific actions
  (sleep/hibernate/shutdown, start-with-Windows)**, and Firefox.

## Site video notes
- YouTube needs the **Deno** helper (Options → Site videos → Install; installed automatically on first start). Without it yt-dlp reports "Requested format is not available".
- Players that stream through DASH (`.mpd`) or HLS (`.m3u8`) are detected while the video plays; the extension keeps what it saw in `storage.session`, so it survives the browser stopping its background worker. If the bar says nothing was detected, let the video play a few seconds and click it again.
- DRM-protected streams (Widevine/PlayReady) are not supported. Only download content you have the right to save.

## Video list (like IDM's popup)
Clicking the bar lists every file the page offers — each quality with resolution, duration, bitrate and size, plus subtitle files (.srt/.vtt/.ass) — with **Download all**. Sources: requests the browser made, `<video>/<source>/<track>` elements, and URLs inside the page's own JSON/API responses (read by `extension/inject.js`, which runs in the page). Resolution/duration/bitrate need ffprobe (installed with ffmpeg).
File types, excluded sites (wildcards such as `*.update.microsoft.com`) and excluded addresses are in Options → File types; defaults are IDM's list.

## Compared with XDM (open source, GPL — studied for its feature list only; no code copied)
Covered: segmented acceleration with dynamic re-splitting of the biggest remaining piece, resume + refresh address, HLS/DASH (via yt-dlp/ffmpeg) with audio+video merge, browser extension with a per-page video list (format, quality, size, **thumbnail**), scheduler/queues/shutdown, speed limiter, proxy, clipboard monitor, batch links, FTP/FTPS, custom headers/login/cookies for a link, "download all links on this page", light/dark theme, keep-PC-awake.
Not covered: HDS streams, NTLM/Kerberos sign-in, proxy auto-config scripts, built-in MP3/MP4 device converter, translations.
Speed: loopback benchmark `node scripts/bench.js 600 16` (≈550 MB/s, engine is not the limit). Real speed is set by your connection and the server; the engine uses 16 connections per file by default, 16 MB ranged requests and 1 MB coalesced disk writes.

## Direct multi-connection site videos
For YouTube-style sites the app reads the direct stream URLs from yt-dlp (`-J`), downloads video and audio through the segmented engine (several Range connections each), then merges with ffmpeg. If that is not possible it falls back to yt-dlp's own downloader. Toggle: Options → Site videos → "Direct multi-connection download" (`ytDirect`).

## Extension: quality dropdown and Images & links tab
Each detected video is one card with a thumbnail and a quality dropdown. "Images & links" opens a separate tab listing every image, link, file and media item on the page, with filters and bulk download.

## Website
`website/index.html` is a standalone product page.

## Image grabber coverage
The Images & links tab reads `<img>` (src, srcset, lazy `data-*` attributes), `<picture>`, video posters, inline/CSS/stylesheet backgrounds (including `::before`/`::after`), SVG `<image>`, links to image files, open shadow DOM, every iframe, the browser's resource-timing list, and image URLs inside page scripts/JSON. "Scan whole page" scrolls the page first so lazy-loaded images are fetched. Filters: name, min size, format, sort by size. The Files tab lists documents, archives, audio and video links.

## Resume instead of restart (site videos)
If a stream link is refused or expires part-way (HTTP 403/410, resets), the finished parts stay on disk, fresh links are requested from yt-dlp, and the download continues where it stopped. yt-dlp's own downloader is used only if nothing moves for three rounds. The join step (ffmpeg) reports a percentage and no longer rewrites the file for "faststart".

## Adaptive connections
When a server answers 429/503 to extra connections the engine lowers its connection count (about 40% fewer each time) and keeps going, instead of hammering a server that only allows a few. Ideas compared against Mister Download Manager's README: re-balancing until the last byte (already here), using only as many connections as the server allows (added), resilience on shaky connections (resume-with-fresh-links, added).

## Exact quality, codec and per-connection view
Site-video qualities are listed per resolution / frame rate / codec (for example `1080p60 · H.264 + AAC · MP4 · 5.0 GB`) and the download uses the exact format ids, so the size shown is the size you get. The generic fallback prefers H.264, never AV1. The progress window lists every connection with its status, bytes received and speed.

## Connections, merge and streaming-site pages
The connection setting is the TOTAL for a site video: it is split between the video and audio streams by size, so 8 means 8. The progress window reads "N connections active · X of Y parts finished" (parts grow as the engine splits the biggest remaining piece; that is not the connection count). Joining video and audio shows its percentage, and deleting the temporary parts happens in the background after the file is complete. On streaming sites whose files have hash names, entries are named after the page title and numbered; a page-level extractor failure (403) is hidden while sniffed files are listed.

## Streaming sites (MovieBox-style)
Players on these sites fetch the movie as several files with hash names, one per quality. The popup probes each file, treats files whose duration matches (within 2 s) as the same video, and merges them into ONE card with a quality dropdown (longest video first, subtitles last). Detection also accepts a media extension anywhere in the address, a media file name in Content-Disposition, and large ranged binary responses requested by a player.

## Blocked pages and "More formats"
For pages that answer yt-dlp with HTTP 403 (Cloudflare-style), the probe retries once with `--impersonate chrome` (needs a yt-dlp build with curl_cffi; the official Windows build includes it). The popup's **More formats** button opens a list of every large or unknown-type response the page received (generic binary streams that did not look like media), so a movie that was not auto-recognised can still be downloaded, like XDM's list.

## HLS playlists the page itself received
Many streaming sites sign their playlist URLs so a second download of the master playlist is refused. The page hook (`inject.js`) hands the playlist *text* the player received to the extension (`manifest-relay.js` → `manifest-body`), which sends it with the request, so the quality list (resolution, bitrate, audio language, duration, estimated size like XDM's `~ 553.6 MB`) and the download itself work without fetching the playlist again. Playlists are recognised by their `#EXTM3U` content, not only by URL or content type. Files named inside the player's own JSON responses (play-info documents) are still listed, because sites such as MovieBox only request the quality that is playing but name every quality there; scripts and HTML are scanned only for images in the grabber.

## Joining video and audio while downloading
For site videos with separate video and audio streams, both files are downloaded **in order** (consecutive pieces handed to the connections front to back) and `src/core/streammerge.js` feeds the finished part of each file to ffmpeg through a tiny local HTTP server, so the joined file is almost complete when the last byte arrives (no multi-minute "Finishing" step for multi-GB videos). If a stream cannot be read front to back, or a link expires and the download has to be resumed, it silently falls back to joining the finished files. When multi-connection mode is not possible at all (segmented streams, no ffmpeg) the progress window now says why next to the status instead of silently using one connection. Turn the streaming join off with the `ytStreamMerge: false` setting if ever needed.

## Downloading exactly the quality you picked
The streams you pick in the list (`vid`/`aid` ids) are requested exactly, with every YouTube player type in turn, before the app ever settles for anything else. If none offers them as plain files, the closest plain-file quality is used and the progress window says so. HLS variants (different bitrates under the same resolution) are never chosen silently. Segmented streams that must go through yt-dlp use parallel fragment downloads (shown as connections).

**The file that arrives is the quality that was picked.** Three rules make that true:

* **The download uses the streams behind the row that was clicked.** The quality list is built from a full format list whose addresses were checked (`dropRefused`); the download starts from those same addresses instead of asking the site a second time. A site that answers the second question with less (a bot check, a fresh page, a different player type) can therefore no longer decide what is saved.
* **A fallback never answers with another resolution.** The fallback selectors ask for `height=1080`, never `height<=1080`, so a smaller picture can never slip in under the name of the picked one. When the site renumbers the streams, the same quality is looked for by resolution, frame rate and codec.
* **A smaller quality is never saved silently.** If the picked quality is not on offer, the download stops with a plain sentence ("The site does not offer the 1080p stream for this video right now...") instead of writing a smaller file. The resolution of the finished file is checked against the pick afterwards, and the result reports what really arrived.

Only streams that can really be downloaded are offered. YouTube also lists streams that need a token the page did not hand out (they have no address); those used to appear in the list (for example a 5.84 GB 1080p) and then failed at download time, so a lower quality was saved. They are now hidden, and while higher qualities are being hidden the app tries further player types (`alt`: tv/mweb) before settling.

Every YouTube stream offered in the quality list is also asked for its first byte before the list is shown (`dropRefused`), and the exact streams you pick are taken from that same full format list at download time instead of through yt-dlp's own format selection, so what is listed is what downloads. Those checks are made a few at a time: a burst of dozens of range requests makes the site answer the next look-up thinner, which is exactly how a picked quality used to disappear between the list and the download.

## The "New" menu
The toolbar's **New ▾** button (like XDM's) opens one menu for every kind of download: HTTP / web download, Video download (page address → quality list → download), HLS / DASH stream (playlist or manifest → variants, bitrate, size, language → download), Batch download (many links, one per line) and Batch from a pattern. The connection bar in the progress window now shows one block for all not-yet-started pieces instead of dozens of tiny ones, so it matches the number of live connections.

## Real sizes and the Video dialog
Quality sizes shown are the server's own numbers (yt-dlp sizes, confirmed with a `Range: bytes=0-0` probe that reads Content-Range). There is no bitrate×duration estimate any more, so a 1440p60 VP9 stream shows its true size instead of an inflated one. New → Video download lists every quality as a clickable row, with an editable File name and a Segments choice (4–32). The window has a branded header showing the live total speed.

## Link details
Paste any link into the bar at the top of the window (or New → Link details). The window shows the thumbnail and a real frame cut from the video, the title, the published caption/description text, uploader, date, counts, hashtags, caption (subtitle) tracks you can download, and every quality and audio format with its size and a Download button. It uses yt-dlp, so it works for the sites yt-dlp supports (YouTube, TikTok, Instagram, Facebook, X, Snapchat and many more). Private or login-only posts need the browser's cookies (the extension supplies them); a site can still refuse a link.

## Android app
`android/` is a native app with the same engine as the desktop app (yt-dlp, through Chaquopy): paste or share a link and it shows the thumbnail, title, description and every quality (with video+audio merged on the phone), previews video, audio, images and text before and after download, and downloads on up to 32 connections with pause/resume, a queue, speed limit, Wi-Fi-only, batch and pattern downloads, custom headers/cookies/login, and a settings screen. Not in Android: HLS/DASH streams, scheduler, browser extension. The APK is signed with a fixed key (android/app/idm.keystore) so updates install over older builds. Only arm64-v8a and x86_64 are built (Python 3.12). The release workflow builds it (`gradle -p android assembleDebug`).
