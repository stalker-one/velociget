package io.encodal.idm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a link: direct files, web pages with video, TikTok and YouTube. Returns title, thumbnail and every downloadable quality. */
final class Extractor {
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final String GRAB_HINT = "Found by reading the page. If this is only a trailer or a preview, tap \"Browse & grab\", open the episode you want and press play: the app captures its real file.";
    private static final String YT_UA = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    static final class Fmt {
        String label = "", detail = "", url = "", ext = "", mime = "", headers = "", name = "", audioUrl = "", audioHeaders = "";
        long size = -1;
        int rank;
        boolean video, audio, hls;
    }

    static final class Info {
        String url = "", site = "", title = "", desc = "", thumb = "", uploader = "", note = "", thumbReferer = "";
        long duration;
        boolean direct;
        boolean resumable;
        final List<Fmt> fmts = new ArrayList<>();
    }

    private static final class Resp {
        int code;
        String url = "", type = "", disp, cookie = "";
        long length = -1, total = -1;
        byte[] body;
    }

    // ------------------------------------------------------------------ http

    private static String join(Map<String, String> jar) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    private static void applyHeaders(HttpURLConnection c, String extra) {
        if (extra == null) return;
        for (String line : extra.split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
    }

    private static Resp fetch(String url, String extra, boolean readBody, boolean range, String postJson) throws Exception {
        return fetch(url, extra, readBody, range, postJson, 4_000_000);
    }

    private static Resp fetch(String url, String extra, boolean readBody, boolean range, String postJson, int max) throws Exception {
        Map<String, String> jar = new LinkedHashMap<>();
        String cur = url;
        for (int hop = 0; hop < 9; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (!jar.isEmpty()) c.setRequestProperty("Cookie", join(jar));
            applyHeaders(c, extra);
            if (range) c.setRequestProperty("Range", "bytes=0-0");
            if (postJson != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(postJson.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String sc : h.getValue()) {
                        String kv = sc.split(";", 2)[0];
                        int eq = kv.indexOf('=');
                        if (eq > 0) jar.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
            }
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null && postJson == null) {
                cur = new URL(new URL(cur), loc).toString();
                c.disconnect();
                continue;
            }
            Resp r = new Resp();
            r.code = code;
            r.url = cur;
            r.type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
            r.disp = c.getHeaderField("Content-Disposition");
            r.length = c.getContentLengthLong();
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                try { r.total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) { }
            }
            boolean texty = r.type.isEmpty() || r.type.startsWith("text/") || r.type.contains("json") || r.type.contains("xml");
            if (readBody && (postJson != null || texty)) {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
                    in.close();
                    r.body = bo.toByteArray();
                }
            }
            r.cookie = join(jar);
            c.disconnect();
            return r;
        }
        throw new Exception("Too many redirects");
    }

    static Bitmap bitmap(String url, String referer) throws Exception {
        Resp r = fetch(url, referer == null || referer.isEmpty() ? "" : "Referer: " + referer, false, false, null);
        HttpURLConnection c = (HttpURLConnection) new URL(r.url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 6_000_000) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Math.max(1, o.outWidth / 900);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ entry

    static String getText(String url, String headers) throws Exception {
        Resp r = fetch(url, headers, true, false, null, 3_000_000);
        return r.body == null ? "" : new String(r.body, "UTF-8");
    }

    /** Title, description and thumbnail only, in well under a second, so the dialog can show something while formats are still loading. */
    static Info quick(String url) throws Exception {
        Info in = new Info();
        in.url = url;
        String host = new URL(url).getHost().toLowerCase(Locale.US);
        in.site = host.startsWith("www.") ? host.substring(4) : host;
        String api = null;
        if (host.endsWith("youtube.com") || host.equals("youtu.be")) {
            api = "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8");
            Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(url);
            if (m.find()) in.thumb = "https://i.ytimg.com/vi/" + m.group(1) + "/hqdefault.jpg";
        } else if (host.endsWith("tiktok.com")) {
            api = "https://www.tiktok.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8");
        }
        if (api != null) {
            try {
                Resp r = fetch(api, "", true, false, null, 200_000);
                if (r.body != null && r.code == 200) {
                    JSONObject j = new JSONObject(new String(r.body, "UTF-8"));
                    in.title = j.optString("title");
                    in.uploader = j.optString("author_name");
                    if (!j.optString("thumbnail_url").isEmpty()) in.thumb = j.optString("thumbnail_url");
                    if (!in.title.isEmpty()) return in;
                }
            } catch (Exception ignored) { }
        }
        Resp r = fetch(url, "", true, false, null, 350_000);
        in.thumbReferer = r.url;
        if (r.body == null) {
            in.title = nameFrom(r.disp, r.url);
            return in;
        }
        String html = new String(r.body, "UTF-8");
        in.title = first(meta(html, "og:title"), meta(html, "twitter:title"), tagTitle(html));
        in.desc = first(meta(html, "og:description"), meta(html, "twitter:description"), meta(html, "description"));
        in.thumb = first(absolute(r.url, first(meta(html, "og:image"), meta(html, "twitter:image"))), in.thumb);
        in.uploader = first(meta(html, "og:site_name"), in.uploader);
        return in;
    }

    /** Returns details for a plain file link, or null when the link is a web page. Does not download the file. */
    static Info directOnly(String url) throws Exception {
        Resp r = fetch(url, "", false, false, null);
        if (r.code >= 400) return null;
        boolean texty = r.type.isEmpty() || r.type.startsWith("text/") || r.type.contains("json") || r.type.contains("xml");
        if (texty) return null;
        Info in = new Info();
        in.url = url;
        in.site = new URL(url).getHost().replaceFirst("^www\\.", "");
        return directInfo(r, in);
    }

    private static Info directInfo(Resp r, Info in) throws Exception {
        Resp p = fetch(r.url, "", false, true, null);
        Fmt f = new Fmt();
        f.url = r.url;
        f.mime = r.type.split(";")[0].trim();
        f.size = p.total > 0 ? p.total : r.length;
        in.resumable = p.code == 206 && p.total > 0;
        f.name = nameFrom(r.disp, r.url);
        f.ext = extOf(f.name);
        f.label = "Original file";
        f.detail = (f.size > 0 ? MainActivity.sizeText(f.size) : "Unknown size") + (in.resumable ? " · resumable" : "");
        f.video = f.mime.startsWith("video/");
        in.fmts.add(f);
        in.title = f.name;
        in.direct = true;
        return in;
    }

    static Info resolve(String url) throws Exception {
        Info in = new Info();
        in.url = url;
        String host = new URL(url).getHost().toLowerCase(Locale.US);
        in.site = host.startsWith("www.") ? host.substring(4) : host;

        if (host.endsWith("youtube.com") || host.equals("youtu.be")) {
            try {
                youtube(url, in);
            } catch (Exception e) {
                in.note = "YouTube did not answer: " + e.getMessage();
            }
            if (!in.fmts.isEmpty()) return in;
        }

        Resp r = fetch(url, "", true, false, null);
        if (r.code >= 400) throw new Exception(r.code == 403 ? "The site refused the request (403)" : r.code == 404 ? "Not found (404)" : "Server answered " + r.code);
        boolean texty = r.type.isEmpty() || r.type.startsWith("text/") || r.type.contains("json") || r.type.contains("xml");

        if (!texty) return directInfo(r, in);

        String html = r.body == null ? "" : new String(r.body, "UTF-8");
        in.title = first(meta(html, "og:title"), meta(html, "twitter:title"), tagTitle(html));
        in.desc = first(meta(html, "og:description"), meta(html, "twitter:description"), meta(html, "description"));
        in.thumb = absolute(r.url, first(meta(html, "og:image"), meta(html, "twitter:image")));
        in.thumbReferer = r.url;
        in.uploader = first(meta(html, "og:site_name"), "");

        if (host.contains("tiktok.com") && tiktok(html, r, in)) return in;

        // generic page: collect media links
        String h = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        Set<String> found = new LinkedHashSet<>();
        for (String key : new String[]{"og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream", "og:audio"}) {
            String v = meta(h, key);
            if (!v.isEmpty()) found.add(absolute(r.url, v));
        }
        Matcher m = Pattern.compile("<(?:video|source)[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(h);
        while (m.find()) found.add(absolute(r.url, m.group(1)));
        m = Pattern.compile("https?://[^\"'\\s<>\\\\]+?\\.(?:mp4|webm|m4a|mp3|mov)(?:\\?[^\"'\\s<>\\\\]*)?", Pattern.CASE_INSENSITIVE).matcher(h);
        while (m.find() && found.size() < 16) found.add(m.group());

        String hdr = "Referer: " + r.url + (r.cookie.isEmpty() ? "" : "\nCookie: " + r.cookie);
        boolean playlist = false;
        for (String u : found) {
            if (u.isEmpty() || u.startsWith("blob:") || u.startsWith("data:")) continue;
            if (u.toLowerCase(Locale.US).contains(".m3u8")) { playlist = true; continue; }
            Fmt f = new Fmt();
            f.url = u;
            f.headers = hdr;
            in.fmts.add(f);
        }
        probeAll(in.fmts);
        int i = 1;
        for (Fmt f : in.fmts) {
            String ext = extOf(f.url.split("\\?")[0]);
            f.ext = ext.isEmpty() ? "mp4" : ext;
            f.audio = f.mime.startsWith("audio/") || f.ext.matches("mp3|m4a");
            f.video = !f.audio;
            f.label = (f.audio ? "Audio" : "Video") + " " + i++ + " · " + f.ext.toUpperCase(Locale.US);
            f.detail = f.size > 0 ? MainActivity.sizeText(f.size) : "Unknown size";
            f.name = fileName(in.title, f.ext);
        }
        if (!in.fmts.isEmpty()) in.note = GRAB_HINT;
        if (in.fmts.isEmpty()) {
            in.note = playlist
                    ? "This page streams its video as a playlist (HLS), which the Android app cannot download yet."
                    : "No downloadable file was found on this page. Open the real video or file link and try again.";
        }
        return in;
    }

    static void probeAll(final List<Fmt> list) {
        if (list.isEmpty()) return;
        ExecutorService ex = Executors.newFixedThreadPool(6);
        List<Future<?>> fs = new ArrayList<>();
        for (final Fmt f : list) {
            fs.add(ex.submit(() -> {
                try {
                    Resp r = fetch(f.url, f.headers, false, true, null);
                    if (r.code != 200 && r.code != 206) { f.size = -2; return; }
                    f.mime = r.type.split(";")[0].trim();
                    if (f.mime.startsWith("text/") || f.mime.contains("json")) { f.size = -2; return; }
                    f.size = r.total > 0 ? r.total : r.length;
                } catch (Exception e) {
                    f.size = -2;
                }
            }));
        }
        for (Future<?> fu : fs) {
            try { fu.get(14, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        ex.shutdownNow();
        List<Fmt> keep = new ArrayList<>();
        for (Fmt f : list) if (f.size != -2) keep.add(f);
        list.clear();
        list.addAll(keep);
    }

    // ------------------------------------------------------------------ TikTok

    private static boolean tiktok(String html, Resp page, Info in) {
        try {
            int k = html.indexOf("__UNIVERSAL_DATA_FOR_REHYDRATION__");
            if (k < 0) return false;
            int s = html.indexOf('>', k) + 1;
            int e = html.indexOf("</script>", s);
            return tiktokJson(html.substring(s, e), in.url, page.cookie, in);
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads TikTok's page data: videos, photo posts / slideshows (every image) and the sound. */
    static boolean tiktokJson(String json, String pageUrl, String cookie, Info in) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject scope = root.optJSONObject("__DEFAULT_SCOPE__");
            if (scope == null) return false;
            JSONObject det = scope.optJSONObject("webapp.video-detail");
            if (det == null) return false;
            JSONObject info = det.optJSONObject("itemInfo");
            JSONObject item = info == null ? null : info.optJSONObject("itemStruct");
            if (item == null) return false;
            JSONObject video = item.optJSONObject("video");
            JSONObject post = item.optJSONObject("imagePost");
            JSONArray imgs = post == null ? null : post.optJSONArray("images");
            boolean photos = imgs != null && imgs.length() > 0;
            if (video == null && !photos) return false;

            String desc = item.optString("desc", "");
            in.title = desc.isEmpty() ? (photos ? "TikTok photos" : "TikTok video") : desc;
            in.desc = "";
            JSONObject au = item.optJSONObject("author");
            if (au != null) in.uploader = au.optString("nickname") + (au.optString("uniqueId").isEmpty() ? "" : " (@" + au.optString("uniqueId") + ")");
            in.thumbReferer = "https://www.tiktok.com/";
            if (video != null) {
                in.thumb = first(video.optString("cover"), video.optString("originCover"), in.thumb);
                in.duration = video.optLong("duration", 0);
            }

            String hdr = "Referer: https://www.tiktok.com/\nUser-Agent: " + UA + (cookie == null || cookie.isEmpty() ? "" : "\nCookie: " + cookie);
            Set<String> seen = new LinkedHashSet<>();
            final List<Fmt> list = new ArrayList<>();
            if (photos) {
                for (int i = 0; i < imgs.length(); i++) {
                    JSONObject im = imgs.optJSONObject(i);
                    JSONObject iu = im == null ? null : im.optJSONObject("imageURL");
                    JSONArray ul = iu == null ? null : iu.optJSONArray("urlList");
                    if (ul == null || ul.length() == 0) continue;
                    String u = ul.optString(0);
                    if (u.isEmpty() || !seen.add(u)) continue;
                    Fmt f = new Fmt();
                    f.url = u;
                    f.headers = hdr;
                    f.ext = u.toLowerCase(Locale.US).contains(".png") ? "png" : u.toLowerCase(Locale.US).contains(".webp") ? "webp" : "jpeg";
                    f.mime = "image/" + (f.ext.equals("jpeg") ? "jpeg" : f.ext);
                    f.label = "Image " + (list.size() + 1);
                    f.name = fileName(in.title + " " + (list.size() + 1), f.ext.equals("jpeg") ? "jpg" : f.ext);
                    f.rank = 1000 - list.size();
                    list.add(f);
                }
                if (!list.isEmpty() && in.thumb.isEmpty()) in.thumb = list.get(0).url;
            }
            if (video != null) {
                JSONArray br = video.optJSONArray("bitrateInfo");
                if (br != null) {
                    for (int i = 0; i < br.length(); i++) {
                        JSONObject b = br.optJSONObject(i);
                        if (b == null) continue;
                        JSONObject pa = b.optJSONObject("PlayAddr");
                        if (pa == null) continue;
                        JSONArray ul = pa.optJSONArray("UrlList");
                        if (ul == null || ul.length() == 0) continue;
                        String u = ul.optString(0);
                        if (u.isEmpty() || !seen.add(u)) continue;
                        Fmt f = new Fmt();
                        f.url = u;
                        f.headers = hdr;
                        f.video = true;
                        f.audio = true;
                        f.ext = "mp4";
                        f.size = pa.optLong("DataSize", -1);
                        int w = pa.optInt("Width"), hh = pa.optInt("Height");
                        String codec = b.optString("CodecType", "").toLowerCase(Locale.US);
                        String cn = codec.contains("264") ? "H.264" : (codec.contains("265") || codec.contains("bytevc1")) ? "H.265" : codec.toUpperCase(Locale.US);
                        f.label = (w > 0 ? w + "×" + hh : b.optString("GearName", "Video")) + (cn.isEmpty() ? "" : " · " + cn);
                        f.rank = Math.max(w, hh);
                        list.add(f);
                    }
                }
                String pu = video.optString("playAddr", "");
                if (!pu.isEmpty() && seen.add(pu)) {
                    Fmt f = new Fmt();
                    f.url = pu; f.headers = hdr; f.video = true; f.audio = true; f.ext = "mp4";
                    f.label = video.optInt("width") > 0 ? video.optInt("width") + "×" + video.optInt("height") : "Original";
                    list.add(f);
                }
                String du = video.optString("downloadAddr", "");
                if (!du.isEmpty() && seen.add(du)) {
                    Fmt f = new Fmt();
                    f.url = du; f.headers = hdr; f.video = true; f.audio = true; f.ext = "mp4";
                    f.label = "With TikTok watermark";
                    f.rank = -1;
                    list.add(f);
                }
            }
            JSONObject music = item.optJSONObject("music");
            String mu = music == null ? "" : music.optString("playUrl", "");
            if (!mu.isEmpty() && seen.add(mu)) {
                Fmt f = new Fmt();
                f.url = mu; f.headers = hdr; f.audio = true; f.ext = "mp3"; f.mime = "audio/mpeg";
                String mt = music.optString("title", "sound");
                f.label = "Sound · " + mt;
                f.name = fileName(mt, "mp3");
                f.rank = -2;
                list.add(f);
            }
            if (list.isEmpty()) return false;

            probeAll(list);
            if (list.isEmpty()) {
                in.note = "TikTok did not allow the download right now. Try again in a moment.";
                return true;
            }
            Collections.sort(list, (a, b) -> Integer.compare(b.rank, a.rank));
            for (Fmt f : list) {
                boolean img = f.mime.startsWith("image/");
                boolean snd = f.rank == -2;
                String kind = img ? "image" : snd ? "audio only" : "video + audio";
                f.detail = (f.size > 0 ? MainActivity.sizeText(f.size) : "Unknown size") + " · " + kind;
                if (!img && !snd) {
                    f.name = fileName(in.title, "mp4");
                    f.mime = "video/mp4";
                }
            }
            in.fmts.addAll(list);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ YouTube

    private static void youtube(String url, Info in) throws Exception {
        String id = null;
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(url);
        if (m.find()) id = m.group(1);
        if (id == null) throw new Exception("not a video link");

        JSONObject client = new JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", "1.60.19")
                .put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("androidSdkVersion", 32)
                .put("osName", "Android").put("osVersion", "12L").put("hl", "en");
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true);
        String hdr = "User-Agent: " + YT_UA + "\nX-YouTube-Client-Name: 28\nX-YouTube-Client-Version: 1.60.19\nOrigin: https://www.youtube.com";
        Resp r = fetch("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", hdr, true, false, body.toString());
        if (r.body == null) throw new Exception("empty answer");
        JSONObject j = new JSONObject(new String(r.body, "UTF-8"));

        JSONObject vd = j.optJSONObject("videoDetails");
        if (vd != null) {
            in.title = vd.optString("title");
            in.desc = vd.optString("shortDescription");
            in.uploader = vd.optString("author");
            in.duration = vd.optLong("lengthSeconds", 0) * 1000;
            JSONObject th = vd.optJSONObject("thumbnail");
            JSONArray ta = th == null ? null : th.optJSONArray("thumbnails");
            if (ta != null && ta.length() > 0) in.thumb = ta.getJSONObject(ta.length() - 1).optString("url");
        }
        if (in.thumb.isEmpty()) in.thumb = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        JSONObject ps = j.optJSONObject("playabilityStatus");
        JSONObject sd = j.optJSONObject("streamingData");
        if (sd == null) {
            in.note = ps != null && !ps.optString("reason").isEmpty() ? ps.optString("reason") : "YouTube did not return downloadable streams for this video.";
            return;
        }

        List<Fmt> muxed = new ArrayList<>(), vids = new ArrayList<>(), auds = new ArrayList<>();
        collect(sd.optJSONArray("formats"), in, hdr, muxed, vids, auds, true);
        collect(sd.optJSONArray("adaptiveFormats"), in, hdr, muxed, vids, auds, false);
        Collections.sort(muxed, (a, b) -> Long.compare(b.size, a.size));
        Collections.sort(vids, (a, b) -> Long.compare(b.size, a.size));
        Collections.sort(auds, (a, b) -> Long.compare(b.size, a.size));
        in.fmts.addAll(muxed);
        in.fmts.addAll(vids);
        in.fmts.addAll(auds);
        if (in.fmts.isEmpty()) in.note = "YouTube did not return downloadable streams for this video.";
        else if (muxed.isEmpty()) in.note = "YouTube serves its best quality as separate video and audio files. Pick a video row for the picture and an audio row for the sound.";
    }

    private static void collect(JSONArray a, Info in, String hdr, List<Fmt> muxed, List<Fmt> vids, List<Fmt> auds, boolean isMuxed) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            String mt = o.optString("mimeType");
            String mime = mt.split(";")[0].trim();
            Fmt f = new Fmt();
            f.url = o.optString("url");
            f.headers = hdr;
            f.mime = mime;
            f.size = o.optLong("contentLength", -1);
            boolean audio = mime.startsWith("audio/");
            f.audio = audio || isMuxed;
            f.video = !audio;
            String sub = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "mp4";
            f.ext = audio ? (sub.equals("mp4") ? "m4a" : sub) : sub;
            String codec = mt.contains("avc1") ? "H.264" : mt.contains("vp9") || mt.contains("vp09") ? "VP9" : mt.contains("av01") ? "AV1" : mt.contains("opus") ? "Opus" : mt.contains("mp4a") ? "AAC" : "";
            String size = f.size > 0 ? MainActivity.sizeText(f.size) : "Unknown size";
            if (audio) {
                f.label = "Audio · " + (o.optInt("averageBitrate", o.optInt("bitrate")) / 1000) + " kbps";
                f.detail = f.ext.toUpperCase(Locale.US) + " · " + codec + " · " + size;
                auds.add(f);
            } else {
                String q = o.optString("qualityLabel", o.optInt("height") + "p");
                f.label = q + (o.optInt("fps") > 30 ? "" : "");
                f.detail = f.ext.toUpperCase(Locale.US) + " · " + codec + " · " + size + (isMuxed ? " · video + audio" : " · video only");
                (isMuxed ? muxed : vids).add(f);
            }
            f.name = fileName(in.title + (audio ? " (audio)" : " (" + f.label + ")"), f.ext);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String fileName(String title, String ext) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "download";
        if (t.length() > 110) t = t.substring(0, 110).trim();
        return t + "." + ext;
    }

    private static String nameFrom(String cd, String url) {
        try {
            if (cd != null) {
                Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
                m = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m.find()) return m.group(1);
            }
            String p = new URL(url).getPath();
            String last = p.substring(p.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return URLDecoder.decode(last, "UTF-8");
        } catch (Exception ignored) { }
        return "download";
    }

    private static String extOf(String name) {
        int d = name.lastIndexOf('.');
        return d >= 0 && d < name.length() - 1 && name.length() - d <= 6 ? name.substring(d + 1).toLowerCase(Locale.US) : "";
    }

    private static String first(String... s) {
        for (String x : s) if (x != null && !x.trim().isEmpty()) return x.trim();
        return "";
    }

    private static String absolute(String base, String u) {
        if (u == null || u.isEmpty()) return "";
        try { return new URL(new URL(base), u).toString(); } catch (Exception e) { return u; }
    }

    private static String tagTitle(String html) {
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? unesc(m.group(1)).trim() : "";
    }

    private static String meta(String html, String key) {
        String q = Pattern.quote(key);
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)\\s*=\\s*[\"']" + q + "[\"'][^>]*?content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (m.find()) return unesc(m.group(1));
        m = Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?(?:property|name)\\s*=\\s*[\"']" + q + "[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? unesc(m.group(1)) : "";
    }

    private static String unesc(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
