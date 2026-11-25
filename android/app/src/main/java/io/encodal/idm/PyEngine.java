package io.encodal.idm;

import android.content.Context;
import android.webkit.CookieManager;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** The yt-dlp engine (the same one the Windows app uses), reached through Chaquopy. */
final class PyEngine {
    private PyEngine() { }

    private static synchronized Python py(Context c) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(c.getApplicationContext()));
        return Python.getInstance();
    }

    static String updPath(Context c) {
        File f = new File(c.getFilesDir(), "yt-dlp-update.zip");
        return f.exists() && f.length() > 500_000 ? f.getAbsolutePath() : null;
    }

    /** Start Python and load yt-dlp in the background so the first link is read without waiting. */
    static void warm(final Context c) {
        new Thread(() -> {
            try { py(c).getModule("engine").callAttr("warm", updPath(c)); } catch (Throwable ignored) { }
        }).start();
    }

    static String version(Context c) {
        try { return py(c).getModule("engine").callAttr("warm", updPath(c)).toString(); } catch (Throwable e) { return "unavailable"; }
    }

    /** Download the newest yt-dlp so new sites and fixes work without a new APK. */
    static void update(Context c) throws Exception {
        String url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
        File tmp = new File(c.getFilesDir(), "yt-dlp-update.tmp");
        HttpURLConnection h = Segmented.open(url, -1, -1, 8, "");
        if (h.getResponseCode() != 200) throw new Exception("Server answered " + h.getResponseCode());
        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            h.disconnect();
        }
        if (tmp.length() < 500_000) { tmp.delete(); throw new Exception("Update file looks wrong"); }
        File dst = new File(c.getFilesDir(), "yt-dlp-update.zip");
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new Exception("Cannot save the update");
    }

    /** Cookies from the in-app sign-in browser, written in the format yt-dlp reads. */
    private static File cookieFile(Context c, String url) {
        try {
            String host = new URL(url).getHost();
            StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
            boolean any = false;
            String h = host;
            while (h.contains(".")) {
                String ck = CookieManager.getInstance().getCookie("https://" + h);
                if (ck != null && !ck.isEmpty()) {
                    for (String part : ck.split(";")) {
                        int eq = part.indexOf('=');
                        if (eq <= 0) continue;
                        sb.append('.').append(h).append("\tTRUE\t/\tTRUE\t0\t").append(part.substring(0, eq).trim()).append('\t').append(part.substring(eq + 1).trim()).append('\n');
                        any = true;
                    }
                }
                h = h.substring(h.indexOf('.') + 1);
            }
            if (!any) return null;
            File f = new File(c.getCacheDir(), "cookies-" + System.nanoTime() + ".txt");
            try (PrintWriter w = new PrintWriter(f, "UTF-8")) { w.print(sb); }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean has(String codec) {
        return !codec.isEmpty() && !codec.equals("none") && !codec.equals("null");
    }

    private static String headerText(JSONObject hd) {
        if (hd == null) return "";
        StringBuilder b = new StringBuilder();
        for (Iterator<String> it = hd.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Range")) continue;
            b.append(k).append(": ").append(hd.optString(k)).append('\n');
        }
        return b.toString();
    }

    private static String codecName(String vc) {
        String l = vc.toLowerCase(Locale.US);
        if (l.startsWith("avc")) return "H.264";
        if (l.startsWith("hev") || l.startsWith("hvc")) return "H.265";
        if (l.startsWith("vp09") || l.startsWith("vp9")) return "VP9";
        if (l.startsWith("av01")) return "AV1";
        if (l.startsWith("vp8")) return "VP8";
        if (l.startsWith("mp4a")) return "AAC";
        if (l.startsWith("opus")) return "Opus";
        return l.split("\\.")[0].toUpperCase(Locale.US);
    }

    static Extractor.Info probe(Context c, String url) throws Exception {
        File ck = cookieFile(c, url);
        String json;
        try {
            PyObject r = py(c).getModule("engine").callAttr("probe", url, ck == null ? null : ck.getAbsolutePath(), updPath(c));
            json = r.toString();
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage());
            m = m.replaceAll("(?s)^.*?(ERROR: )", "").split("\n")[0];
            throw new Exception(m.length() > 220 ? m.substring(0, 220) : m);
        } finally {
            if (ck != null) ck.delete();
        }
        JSONObject j = new JSONObject(json);
        Extractor.Info in = new Extractor.Info();
        in.url = url;
        in.title = j.optString("title");
        in.desc = j.optString("desc");
        in.uploader = j.optString("uploader");
        in.site = j.optString("site");
        if (in.site.isEmpty()) in.site = new URL(url).getHost();
        in.thumb = j.optString("thumb");
        in.duration = (long) (j.optDouble("dur", 0) * 1000);

        final List<Extractor.Fmt> muxed = new ArrayList<>(), vonly = new ArrayList<>(), aonly = new ArrayList<>();
        final List<Double> abrOf = new ArrayList<>();
        JSONArray fa = j.optJSONArray("fmts");
        if (fa != null) {
            for (int i = 0; i < fa.length(); i++) {
                JSONObject o = fa.getJSONObject(i);
                String vc = o.optString("vc"), ac = o.optString("ac"), ext = o.optString("ext", "mp4");
                int h = o.optInt("h"), w = o.optInt("w");
                long size = o.optLong("fs", 0);
                Extractor.Fmt f = new Extractor.Fmt();
                f.url = o.optString("url");
                f.headers = headerText(o.optJSONObject("hd"));
                f.ext = ext;
                f.size = size > 0 ? size : -1;
                f.mime = ext.equals("m4a") ? "audio/mp4" : (has(vc) || !has(ac) ? "video/" : "audio/") + ext;
                String sz = size > 0 ? MainActivity.sizeText(size) : "size unknown";
                boolean video = has(vc), audio = has(ac);
                if (!video && !audio) { video = !ext.matches("mp3|m4a|opus|ogg|wav|flac|aac"); audio = video; }
                f.video = video;
                f.audio = audio;
                String id = o.optString("id");
                if (video) {
                    String q = h > 0 ? h + "p" : (w > 0 ? w + "px" : (o.optString("note").isEmpty() ? "Video" : o.optString("note")));
                    if (o.optInt("fps") > 30) q += o.optInt("fps");
                    String cn = has(vc) ? " · " + codecName(vc) : "";
                    f.label = q + " · " + ext.toUpperCase(Locale.US) + cn;
                    f.detail = sz + (audio ? " · video + audio" : " · video only") + (id.isEmpty() ? "" : " · " + id);
                    f.name = String.valueOf(h > 0 ? h : w);
                    (audio ? muxed : vonly).add(f);
                } else {
                    double abr = o.optDouble("abr", 0);
                    if (abr <= 0) abr = o.optDouble("tbr", 0);
                    f.label = "Audio" + (abr > 0 ? " · " + (int) abr + " kbps" : "") + " · " + ext.toUpperCase(Locale.US) + (has(ac) ? " · " + codecName(ac) : "");
                    f.detail = sz + (id.isEmpty() ? "" : " · " + id);
                    f.name = String.valueOf((int) abr);
                    aonly.add(f);
                }
            }
        }

        // video-only rows get the best matching sound track merged in on the phone
        for (Extractor.Fmt v : vonly) {
            Extractor.Fmt best = null;
            boolean mp4 = v.ext.matches("mp4|m4v|mov");
            for (Extractor.Fmt a : aonly) {
                boolean ok = mp4 ? a.ext.matches("m4a|mp4") : a.ext.matches("webm|opus|ogg");
                if (ok && (best == null || parseInt(a.name) > parseInt(best.name))) best = a;
            }
            if (best != null) {
                v.audioUrl = best.url;
                v.audioHeaders = best.headers;
                if (v.size > 0 && best.size > 0) v.size += best.size;
                v.detail = (v.size > 0 ? MainActivity.sizeText(v.size) : "size unknown") + " · video + audio merged";
                v.audio = true;
            }
        }
        Collections.sort(muxed, (x, y) -> Integer.compare(parseInt(y.name), parseInt(x.name)));
        Collections.sort(vonly, (x, y) -> {
            int c1 = Integer.compare(y.audioUrl.isEmpty() ? 0 : 1, x.audioUrl.isEmpty() ? 0 : 1);
            if (c1 != 0) return c1;
            return Integer.compare(parseInt(y.name), parseInt(x.name));
        });
        Collections.sort(aonly, (x, y) -> Integer.compare(parseInt(y.name), parseInt(x.name)));
        in.fmts.addAll(muxed);
        in.fmts.addAll(vonly);
        in.fmts.addAll(aonly);
        for (Extractor.Fmt f : in.fmts) {
            String suffix = f.video ? (f.audio && f.audioUrl.isEmpty() ? "" : "") : " (audio)";
            f.name = Extractor.fileName((in.title.isEmpty() ? in.site : in.title) + (f.video ? " (" + f.label.split(" · ")[0] + ")" : suffix), f.ext);
            if (f.mime.isEmpty()) f.mime = (f.video ? "video/" : "audio/") + f.ext;
        }
        return in;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
