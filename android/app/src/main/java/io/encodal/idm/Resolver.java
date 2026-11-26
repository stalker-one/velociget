package io.encodal.idm;

import android.app.Activity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads links in the background and remembers the answer. The yt-dlp engine and the built-in readers run side by side,
 * and the first good answer wins, so nothing waits for a slow reader.
 */
final class Resolver {
    private Resolver() { }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final ExecutorService INNER = Executors.newCachedThreadPool();
    private static final Map<String, Future<Extractor.Info>> JOBS = new LinkedHashMap<>();
    private static final Map<String, Future<Extractor.Info>> METAS = new LinkedHashMap<>();
    private static final Map<String, Long> STAMP = new LinkedHashMap<>();
    private static final long TTL = 10 * 60 * 1000L;
    private static final String SITES = "youtube\\.com|youtu\\.be|tiktok\\.com|instagram\\.com|facebook\\.com|fb\\.watch|fb\\.com|twitter\\.com|x\\.com|snapchat\\.com|vimeo\\.com|dailymotion\\.com|reddit\\.com|pinterest\\.|twitch\\.tv|bilibili\\.com|soundcloud\\.com|likee\\.video|threads\\.net|linkedin\\.com";

    private static boolean knownSite(String url) {
        return url.toLowerCase(Locale.US).matches("^https?://([^/]*\\.)?(" + SITES + ").*");
    }

    private static boolean youtube(String url) {
        return url.toLowerCase(Locale.US).matches("^https?://([^/]*\\.)?(youtube\\.com|youtu\\.be).*");
    }

    private static Extractor.Info work(final Activity act, final String url) throws Exception {
        // 1. a plain file link answers in a fraction of a second
        if (!knownSite(url)) {
            try {
                Extractor.Info d = Extractor.directOnly(url);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }

        // 2. yt-dlp and the built-in reader at the same time
        final Future<Extractor.Info> py = INNER.submit(() -> PyEngine.probe(act, url));
        final Future<Extractor.Info> st = INNER.submit(() -> Extractor.resolve(url));
        final boolean yt = youtube(url);
        final boolean tt = url.toLowerCase(Locale.US).contains("tiktok.com");
        final long t0 = System.currentTimeMillis();
        Extractor.Info pyRes = null, stRes = null;
        Exception pyErr = null, stErr = null;
        boolean pyDone = false, stDone = false;
        long stAt = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (!pyDone && py.isDone()) {
                pyDone = true;
                try { pyRes = py.get(); } catch (Exception e) { pyErr = e; }
                if (pyRes != null && !pyRes.fmts.isEmpty() && !tt) { st.cancel(true); return pyRes; }
            }
            if (!stDone && st.isDone()) {
                stDone = true;
                stAt = now;
                try { stRes = st.get(); } catch (Exception e) { stErr = e; }
            }
            if (tt && pyDone && !stDone && pyRes != null && !pyRes.fmts.isEmpty() && now - t0 > 9000) return pyRes;
            if (tt && pyDone && stDone && (stRes == null || stRes.fmts.isEmpty()) && pyRes != null && !pyRes.fmts.isEmpty()) return pyRes;
            if (stRes != null && stRes.direct) { py.cancel(true); return stRes; }
            if (stRes != null && !stRes.fmts.isEmpty()) {
                // the built-in answer is good enough once yt-dlp had a short head start (YouTube: let yt-dlp finish, it lists more qualities)
                if (pyDone || (!yt && now - stAt > 1500) || (yt && now - t0 > 14000)) { py.cancel(true); return stRes; }
            }
            if (pyDone && stDone) break;
            if (now - t0 > 40000) break;
            Thread.sleep(40);
        }
        py.cancel(true);
        st.cancel(true);

        // 3. open the page like a browser and watch which media it loads
        Extractor.Info in = WebSniffer.sniff(act, url, stRes);
        if (in.fmts.isEmpty()) {
            String why = pyErr != null ? String.valueOf(pyErr.getCause() != null ? pyErr.getCause().getMessage() : pyErr.getMessage())
                    : stErr != null ? String.valueOf(stErr.getMessage()) : "";
            if (!why.isEmpty() && !why.equals("null")) in.note = why + "\n\n" + in.note;
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
        meta(url);
    }

    private static synchronized Future<Extractor.Info> meta(final String url) {
        Future<Extractor.Info> f = METAS.get(url);
        if (f == null) {
            f = INNER.submit(() -> Extractor.quick(url));
            METAS.put(url, f);
            while (METAS.size() > 12) METAS.remove(METAS.keySet().iterator().next());
        }
        return f;
    }

    /** Title, description and thumbnail only; usually ready within a second. */
    static Extractor.Info meta(Activity act, String url) throws Exception {
        try {
            return meta(url).get(8, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            synchronized (Resolver.class) { METAS.remove(url); }
            throw new Exception(String.valueOf(e.getCause()));
        }
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
