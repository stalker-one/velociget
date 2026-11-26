import json
import os
import sys


def _load(upd):
    # an updated yt-dlp downloaded by the app wins over the one bundled in the APK
    if upd and os.path.exists(upd) and upd not in sys.path:
        sys.path.insert(0, upd)


def warm(upd=None):
    _load(upd)
    import yt_dlp
    from yt_dlp.version import __version__
    return __version__


def _s(v):
    return v if isinstance(v, str) else ""


def probe(url, cookiefile=None, upd=None):
    _load(upd)
    import yt_dlp

    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "socket_timeout": 25,
        "source_address": "0.0.0.0",  # IPv4 only: some mobile networks stall on IPv6
        "extractor_args": {"youtube": {"skip": ["hls", "dash", "translated_subs"]}},
        "extractor_retries": 1,
        "ignoreconfig": True,
        "cachedir": False,
        "check_formats": False,
    }
    if cookiefile and os.path.exists(cookiefile):
        opts["cookiefile"] = cookiefile

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if info.get("entries"):
            entries = [e for e in info["entries"] if e]
            if entries:
                info = entries[0]
        raw = info.get("formats") or ([info] if info.get("url") else [])
        fmts = []
        for f in raw:
            u = f.get("url")
            proto = _s(f.get("protocol")) or "https"
            hls = proto in ("m3u8", "m3u8_native")
            if not u or (proto not in ("http", "https") and not hls):
                continue
            if hls and _s(f.get("vcodec")) == "none":
                continue
            headers = {}
            try:
                headers = dict(ydl._calc_headers(dict(f)))
            except Exception:
                headers = dict(f.get("http_headers") or info.get("http_headers") or {})
            fmts.append({
                "id": _s(f.get("format_id")),
                "url": u,
                "ext": _s(f.get("ext")),
                "w": f.get("width") or 0,
                "h": f.get("height") or 0,
                "fps": f.get("fps") or 0,
                "vc": _s(f.get("vcodec")),
                "ac": _s(f.get("acodec")),
                "fs": f.get("filesize") or f.get("filesize_approx") or 0,
                "abr": f.get("abr") or 0,
                "tbr": f.get("tbr") or 0,
                "note": _s(f.get("format_note")),
                "hd": headers,
                "hls": hls,
            })
        return json.dumps({
            "title": _s(info.get("title")),
            "desc": _s(info.get("description")),
            "uploader": _s(info.get("uploader")) or _s(info.get("channel")),
            "site": _s(info.get("extractor_key")),
            "thumb": _s(info.get("thumbnail")),
            "dur": info.get("duration") or 0,
            "page": _s(info.get("webpage_url")),
            "fmts": fmts,
        })
