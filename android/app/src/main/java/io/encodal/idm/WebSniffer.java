package io.encodal.idm;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opens the page in a hidden browser (like a real visitor), lets its scripts run, and watches which media files it loads.
 * This is what finds videos on Instagram, Facebook, X, Snapchat and other sites that build the page with JavaScript.
 */
final class WebSniffer {
    private WebSniffer() { }

    static boolean media(String u) {
        String l = u.toLowerCase(Locale.US);
        int q = l.indexOf('?');
        String path = q >= 0 ? l.substring(0, q) : l;
        return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".mov");
    }

    /** Remove byte-range parameters so a streamed piece becomes the whole file. */
    static String whole(String u) {
        int q = u.indexOf('?');
        if (q < 0) return u;
        StringBuilder b = new StringBuilder(u.substring(0, q));
        String sep = "?";
        for (String p : u.substring(q + 1).split("&")) {
            if (p.startsWith("bytestart=") || p.startsWith("byteend=")) continue;
            b.append(sep).append(p);
            sep = "&";
        }
        return b.toString();
    }

    static Extractor.Info sniff(final Activity act, final String url, Extractor.Info base) {
        final Extractor.Info in = base != null ? base : new Extractor.Info();
        if (in.url.isEmpty()) in.url = url;
        if (in.site.isEmpty()) {
            try { in.site = new URL(url).getHost().replaceFirst("^www\\.", ""); } catch (Exception ignored) { }
        }

        final Set<String> net = Collections.synchronizedSet(new LinkedHashSet<String>());
        final String[] result = new String[1];
        final String[] ua = {Extractor.UA};
        final String[] page = {url};
        final CountDownLatch done = new CountDownLatch(1);

        String js = "";
        try (InputStream is = act.getAssets().open("sniff.js")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            js = bo.toString("UTF-8");
        } catch (Exception e) {
            return in;
        }
        final String script = js;

        act.runOnUiThread(() -> {
            try {
                final WebView wv = new WebView(act);
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setUseWideViewPort(true);
                ua[0] = s.getUserAgentString();
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.setAlpha(0f);
                ((ViewGroup) act.getWindow().getDecorView()).addView(wv, new FrameLayout.LayoutParams(320, 480));
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        if (media(u)) net.add(whole(u));
                        return null;
                    }

                    @Override
                    public void onPageFinished(WebView v, String u) {
                        page[0] = u;
                    }
                });
                final Handler h = new Handler(Looper.getMainLooper());
                final long t0 = SystemClock.elapsedRealtime();
                final Runnable[] tick = new Runnable[1];
                tick[0] = () -> wv.evaluateJavascript(script, value -> {
                    long el = SystemClock.elapsedRealtime() - t0;
                    boolean found = !net.isEmpty() || (value != null && value.contains("\\\"u\\\":[\\\"http"));
                    if (found || el > 11000) {
                        result[0] = value;
                        try {
                            wv.stopLoading();
                            ((ViewGroup) wv.getParent()).removeView(wv);
                            wv.destroy();
                        } catch (Exception ignored) { }
                        done.countDown();
                    } else {
                        h.postDelayed(tick[0], 900);
                    }
                });
                wv.loadUrl(url);
                h.postDelayed(tick[0], 2500);
            } catch (Exception e) {
                done.countDown();
            }
        });

        try { done.await(25, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }

        Set<String> urls = new LinkedHashSet<>();
        try {
            if (result[0] != null && !result[0].equals("null")) {
                Object v = new JSONTokener(result[0]).nextValue();
                JSONObject j = new JSONObject(String.valueOf(v));
                if (in.title.isEmpty() || in.title.equalsIgnoreCase(in.site)) {
                    String t = j.optString("t");
                    if (!t.isEmpty()) in.title = t;
                }
                if (in.desc.isEmpty()) in.desc = j.optString("d");
                if (in.thumb.isEmpty()) in.thumb = j.optString("i");
                in.thumbReferer = page[0];
                JSONArray a = j.optJSONArray("u");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        String u = a.optString(i);
                        if (u.startsWith("//")) u = "https:" + u;
                        if (u.startsWith("http") && media(u)) urls.add(whole(u));
                    }
                }
            }
        } catch (Exception ignored) { }
        urls.addAll(net);

        String hdr = "Referer: " + page[0] + "\nUser-Agent: " + ua[0];
        in.fmts.clear();
        for (String u : urls) {
            Extractor.Fmt f = new Extractor.Fmt();
            f.url = u;
            f.headers = hdr;
            in.fmts.add(f);
        }
        Extractor.probeAll(in.fmts);

        // same file seen twice (different signatures) is listed once
        Set<String> seen = new LinkedHashSet<>();
        List<Extractor.Fmt> keep = new java.util.ArrayList<>();
        for (Extractor.Fmt f : in.fmts) {
            String key = f.url.split("\\?")[0] + "|" + f.size;
            if (f.size > 0 && seen.add(key)) keep.add(f);
        }
        in.fmts.clear();
        in.fmts.addAll(keep);
        Collections.sort(in.fmts, (x, y) -> Long.compare(y.size, x.size));
        int vi = 1, ai = 1;
        for (Extractor.Fmt f : in.fmts) {
            String path = f.url.split("\\?")[0];
            int d = path.lastIndexOf('.');
            f.ext = d >= 0 && path.length() - d <= 5 ? path.substring(d + 1).toLowerCase(Locale.US) : "mp4";
            f.audio = f.mime.startsWith("audio/") || f.ext.equals("mp3") || f.ext.equals("m4a");
            f.video = !f.audio;
            f.label = f.audio ? "Audio " + ai++ + " · " + f.ext.toUpperCase(Locale.US) : "Video " + vi++ + " · " + f.ext.toUpperCase(Locale.US);
            f.detail = MainActivity.sizeText(f.size);
            f.name = Extractor.fileName(in.title.isEmpty() ? in.site : in.title, f.ext);
        }
        if (in.fmts.isEmpty()) {
            in.note = "No video could be found. If the post is private or the site asks you to log in, tap \"Sign in\", log in once, then try again.";
        } else {
            in.note = Extractor.GRAB_HINT;
        }
        return in;
    }
}
