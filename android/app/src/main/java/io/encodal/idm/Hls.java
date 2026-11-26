package io.encodal.idm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HLS (.m3u8) playlists: parsing, quality list and helpers for the downloader. */
final class Hls {
    private Hls() { }

    static final class Seg {
        String url, keyUrl, iv;
        double dur;
        long seq;
    }

    static final class Variant {
        String url, name = "";
        long bw;
        int w, h;
    }

    static final class Playlist {
        boolean master;
        final List<Seg> segs = new ArrayList<>();
        final List<Variant> variants = new ArrayList<>();
        String mapUrl;
        double total;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    static byte[] bytes(String url, String headers, int max) throws Exception {
        HttpURLConnection c = Segmented.open(url, -1, -1, 8, headers);
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new Exception("Server answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    bo.write(b, 0, n);
                    if (bo.size() > max) break;
                }
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static String text(String url, String headers) throws Exception {
        return new String(bytes(url, headers, 4_000_000), "UTF-8");
    }

    private static Map<String, String> attrs(String s) {
        Map<String, String> m = new HashMap<>();
        Matcher x = ATTR.matcher(s);
        while (x.find()) {
            String v = x.group(2);
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
            m.put(x.group(1), v);
        }
        return m;
    }

    private static String abs(String base, String u) throws Exception {
        return new URL(new URL(base), u).toString();
    }

    static Playlist parse(String base, String text) throws Exception {
        Playlist p = new Playlist();
        String keyUrl = null, keyIv = null;
        long seq = 0;
        Variant pend = null;
        double dur = 0;
        for (String raw : text.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                p.master = true;
                pend = new Variant();
                Map<String, String> a = attrs(l.substring(18));
                try { pend.bw = Long.parseLong(a.containsKey("AVERAGE-BANDWIDTH") ? a.get("AVERAGE-BANDWIDTH") : a.get("BANDWIDTH")); } catch (Exception ignored) { }
                String res = a.get("RESOLUTION");
                if (res != null && res.contains("x")) {
                    try {
                        pend.w = Integer.parseInt(res.split("x")[0]);
                        pend.h = Integer.parseInt(res.split("x")[1]);
                    } catch (Exception ignored) { }
                }
                if (a.get("NAME") != null) pend.name = a.get("NAME");
                continue;
            }
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(22).trim()); } catch (Exception ignored) { }
                continue;
            }
            if (l.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attrs(l.substring(11));
                String method = a.get("METHOD") == null ? "NONE" : a.get("METHOD");
                if (method.equals("NONE")) { keyUrl = null; keyIv = null; }
                else if (method.equals("AES-128")) {
                    keyUrl = a.get("URI") == null ? null : abs(base, a.get("URI"));
                    keyIv = a.get("IV");
                } else {
                    throw new Exception("This stream uses protected (DRM) encryption and cannot be downloaded");
                }
                continue;
            }
            if (l.startsWith("#EXT-X-MAP:")) {
                String u = attrs(l.substring(11)).get("URI");
                if (u != null) p.mapUrl = abs(base, u);
                continue;
            }
            if (l.startsWith("#EXTINF:")) {
                try { dur = Double.parseDouble(l.substring(8).split(",")[0].trim()); } catch (Exception ignored) { dur = 0; }
                continue;
            }
            if (l.startsWith("#")) continue;
            String url = abs(base, l);
            if (pend != null) {
                pend.url = url;
                p.variants.add(pend);
                pend = null;
                continue;
            }
            Seg s = new Seg();
            s.url = url;
            s.dur = dur;
            s.keyUrl = keyUrl;
            s.iv = keyIv;
            s.seq = seq++;
            p.segs.add(s);
            p.total += dur;
            dur = 0;
        }
        return p;
    }

    private static String clock(long s) {
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** One row per quality of a playlist, with an estimated size. */
    static List<Extractor.Fmt> expand(final String url, final String headers) throws Exception {
        Playlist p = parse(url, text(url, headers));
        List<Extractor.Fmt> out = new ArrayList<>();
        if (!p.master) {
            Extractor.Fmt f = rowFor(url, headers, 0, 0, 0, p.total, "Stream");
            out.add(f);
            return out;
        }
        Collections.sort(p.variants, (a, b) -> Long.compare(b.bw, a.bw));
        List<Variant> vs = p.variants.size() > 10 ? p.variants.subList(0, 10) : p.variants;
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Future<Double>> fut = new ArrayList<>();
        for (final Variant v : vs) {
            fut.add(ex.submit(() -> parse(v.url, text(v.url, headers)).total));
        }
        for (int i = 0; i < vs.size(); i++) {
            double total = 0;
            try { total = fut.get(i).get(12, TimeUnit.SECONDS); } catch (Exception ignored) { }
            Variant v = vs.get(i);
            out.add(rowFor(v.url, headers, v.bw, v.w, v.h, total, v.name));
        }
        ex.shutdownNow();
        return out;
    }

    private static Extractor.Fmt rowFor(String url, String headers, long bw, int w, int h, double total, String name) {
        Extractor.Fmt f = new Extractor.Fmt();
        f.url = url;
        f.headers = headers;
        f.hls = true;
        f.ext = "mp4";
        f.mime = "video/mp4";
        f.video = true;
        f.audio = true;
        f.label = (h > 0 ? h + "p" : (name == null || name.isEmpty() ? "Stream" : name)) + " · HLS";
        long est = bw > 0 && total > 0 ? (long) (bw / 8.0 * total) : -1;
        f.size = est;
        f.name = String.valueOf(Math.max(h, 0));
        f.detail = (est > 0 ? "~" + MainActivity.sizeText(est) + " · " : "") + (total > 0 ? clock((long) total) + " · " : "") + "stream, saved as MP4";
        return f;
    }
}
