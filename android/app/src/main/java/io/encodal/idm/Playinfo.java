package io.encodal.idm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds media links inside the JSON a streaming site's own API returns ("play info" / "episode detail").
 * Sites like MovieBox do not put the real file in the page; the app asks an API and gets a list of qualities.
 */
final class Playinfo {
    static final class Cand {
        String url = "", label = "", detail = "", ext = "", mime = "";
        long size = -1;
        boolean hls;
    }

    private static final Pattern MEDIA = Pattern.compile("(?i)\\.(mp4|m4v|mkv|webm|mp3|m4a|mov|m3u8)(\\?|$|#)");
    private static final Pattern RAW = Pattern.compile("https?:[\\\\/]{2}[^\"'\\s<>\\\\]+(?:\\\\/[^\"'\\s<>\\\\]+)*\\.(?:mp4|m4v|mkv|webm|mp3|m4a|mov|m3u8)[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);

    static boolean isMedia(String u) {
        return u != null && u.startsWith("http") && MEDIA.matcher(u).find();
    }

    /** Every media link in the JSON, with whatever quality / size the neighbouring keys say. */
    static List<Cand> scan(String text, String base) {
        Map<String, Cand> out = new LinkedHashMap<>();
        try {
            String t = text.trim();
            Object root = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
            walk(root, out, 0);
        } catch (Exception e) {
            Matcher m = RAW.matcher(text);
            while (m.find()) add(out, m.group().replace("\\/", "/").replace("\\u0026", "&"), null);
        }
        return new ArrayList<>(out.values());
    }

    private static void walk(Object o, Map<String, Cand> out, int depth) {
        if (depth > 12 || out.size() > 200) return;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Iterator<String> it = j.keys();
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = j.opt(k);
                if (v instanceof String) {
                    String s = ((String) v).replace("\\/", "/");
                    if (isMedia(s)) add(out, s, j);
                } else walk(v, out, depth + 1);
            }
        }
    }

    private static void add(Map<String, Cand> out, String u, JSONObject ctx) {
        if (out.containsKey(u)) return;
        Cand c = new Cand();
        c.url = u;
        String path = u.split("[?#]")[0].toLowerCase(Locale.US);
        c.ext = path.substring(path.lastIndexOf('.') + 1);
        c.hls = c.ext.equals("m3u8");
        if (c.hls) c.ext = "mp4";
        String q = "";
        if (ctx != null) {
            for (String k : new String[]{"resolutions", "resolution", "quality", "definition", "height", "name", "format", "label"}) {
                String v = ctx.optString(k, "");
                if (!v.isEmpty() && !v.equals("null") && v.length() < 24) { q = v; break; }
            }
            if (!q.isEmpty() && q.matches("\\d{3,4}")) q += "p";
            for (String k : new String[]{"size", "filesize", "fileSize", "bytes", "length", "contentLength"}) {
                long v = ctx.optLong(k, -1);
                if (v > 1000) { c.size = v; break; }
            }
        }
        c.label = q.isEmpty() ? (c.hls ? "Stream" : "Video") : q;
        out.put(u, c);
    }
}
