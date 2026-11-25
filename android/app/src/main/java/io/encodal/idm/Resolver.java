package io.encodal.idm;

import android.app.Activity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Reads links in the background and remembers the answer, so the details open instantly when a link was already prefetched. */
final class Resolver {
    private Resolver() { }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Map<String, Future<Extractor.Info>> JOBS = new LinkedHashMap<>();
    private static final Map<String, Long> STAMP = new LinkedHashMap<>();
    private static final long TTL = 10 * 60 * 1000L;
    private static final String SITES = "youtube\\.com|youtu\\.be|tiktok\\.com|instagram\\.com|facebook\\.com|fb\\.watch|fb\\.com|twitter\\.com|x\\.com|snapchat\\.com|vimeo\\.com|dailymotion\\.com|reddit\\.com|pinterest\\.|twitch\\.tv|bilibili\\.com|soundcloud\\.com|likee\\.video|threads\\.net|linkedin\\.com";

    private static boolean knownSite(String url) {
        return url.toLowerCase(Locale.US).matches("^https?://([^/]*\\.)?(" + SITES + ").*");
    }

    private static Extractor.Info work(Activity act, String url) throws Exception {
        Exception first = null;
        // 1. a plain file link: answer in a fraction of a second
        if (!knownSite(url)) {
            try {
                Extractor.Info d = Extractor.directOnly(url);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }
        // 2. the yt-dlp engine (same as the Windows app)
        try {
            Extractor.Info in = PyEngine.probe(act, url);
            if (!in.fmts.isEmpty()) return in;
        } catch (Throwable e) {
            first = new Exception(String.valueOf(e.getMessage()));
        }
        // 3. built-in readers (TikTok, YouTube, plain pages)
        Extractor.Info in = null;
        try {
            in = Extractor.resolve(url);
            if (in.direct || !in.fmts.isEmpty()) return in;
        } catch (Exception e) {
            if (first == null) first = e;
        }
        // 4. open the page like a browser and watch which media it loads
        in = WebSniffer.sniff(act, url, in);
        if (in.fmts.isEmpty() && first != null && first.getMessage() != null && !first.getMessage().equals("null")) {
            in.note = first.getMessage() + "\n\n" + in.note;
        }
        return in;
    }

    private static synchronized Future<Extractor.Info> job(final Activity act, final String url, boolean force) {
        Long t = STAMP.get(url);
        Future<Extractor.Info> f = JOBS.get(url);
        if (f != null && !force && t != null && System.currentTimeMillis() - t < TTL) return f;
        f = POOL.submit(() -> work(act, url));
        JOBS.put(url, f);
        STAMP.put(url, System.currentTimeMillis());
        while (JOBS.size() > 12) {
            String old = JOBS.keySet().iterator().next();
            JOBS.remove(old);
            STAMP.remove(old);
        }
        return f;
    }

    static void prefetch(Activity act, String url) {
        job(act, url, false);
    }

    static Extractor.Info get(Activity act, String url, boolean force) throws Exception {
        Future<Extractor.Info> f = job(act, url, force);
        try {
            Extractor.Info in = f.get();
            if (in.fmts.isEmpty()) forget(url); // never keep an empty answer
            return in;
        } catch (ExecutionException e) {
            forget(url);
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : new Exception(String.valueOf(c));
        }
    }

    static synchronized void forget(String url) {
        JOBS.remove(url);
        STAMP.remove(url);
    }
}
