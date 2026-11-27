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

    private static final String PLAY_JS = "(function(){try{var t=function(e){return (e.innerText||e.textContent||'').trim().toLowerCase()};"
            + "var c=document.querySelectorAll('button,a,div,span');for(var i=0;i<c.length&&i<1500;i++){var e=c[i],x=t(e);"
            + "if(x==='watch online'||x==='play now'||x==='play'){e.click();break}}"
            + "var sel='[class*=big-play],[class*=bigPlay],[class*=play-btn],[class*=playBtn],[class*=PlayBtn],[class*=play-icon],[class*=center-play],.vjs-big-play-button,.plyr__control--overlaid,.jw-icon-display,.art-state';"
            + "var b=document.querySelector(sel);if(b)b.click();"
            + "document.querySelectorAll('video').forEach(function(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){})})}catch(e){}})()";

    private static final String IMG_JS = "(function(){var o=[],s={};document.querySelectorAll('img').forEach(function(i){var u=i.currentSrc||i.src||'';"
            + "if(/tiktokcdn|byteimg|muscdn/.test(u)&&!/avt-|avatar|\\/obj\\/|100x100|200x200|cover-|tos-.*-i-.*100/.test(u)&&i.naturalWidth>=300&&!s[u]){s[u]=1;o.push(u)}});return JSON.stringify(o)})()";

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
        final List<String[]> api = Collections.synchronizedList(new java.util.ArrayList<String[]>()); // JSON the page loaded from its own API: {url, text, headers}
        final Set<String> imgs = Collections.synchronizedSet(new LinkedHashSet<String>());
        final String[] cookieUrl = {url};
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
        String hk = "";
        try (InputStream is = act.getAssets().open("hook.js")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            hk = bo.toString("UTF-8");
        } catch (Exception ignored) { }
        final String hook = hk;

        act.runOnUiThread(() -> {
            try {
                final WebView wv = new WebView(act);
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setUseWideViewPort(true);
                if (url.contains("tiktok.com")) s.setUserAgentString(Extractor.UA);
                ua[0] = s.getUserAgentString();
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onJson(String u, String t, String hd) {
                        if (u != null && t != null && api.size() < 80) api.add(new String[]{u, t, hd == null ? "" : hd});
                    }
                }, "IDMBridge");
                // The browser engine only plays and loads media for a page it considers visible, so the page sits at full size
                // BEHIND the app (the app's own background hides it) instead of being invisible.
                android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
                ((ViewGroup) act.getWindow().getDecorView()).addView(wv, 0, new FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels));
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        String l = r.getUrl().toString().toLowerCase(Locale.US);
                        return !(l.startsWith("http://") || l.startsWith("https://") || l.startsWith("about:") || l.startsWith("data:"));
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        if (media(u)) net.add(whole(u));
                        return null;
                    }

                    @Override
                    public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                        page[0] = u;
                        if (!hook.isEmpty() && u != null && u.startsWith("http")) v.evaluateJavascript(hook, null);
                    }

                    @Override
                    public void onPageFinished(WebView v, String u) {
                        page[0] = u;
                        if (!hook.isEmpty() && u != null && u.startsWith("http")) v.evaluateJavascript(hook, null);
                    }
                });
                final Handler h = new Handler(Looper.getMainLooper());
                final long t0 = SystemClock.elapsedRealtime();
                final boolean mb = url.contains("moviebox") || url.contains("aoneroom");
                final boolean ttk = url.contains("tiktok.com");
                final Runnable[] tick = new Runnable[1];
                tick[0] = () -> {
                    if (mb) wv.evaluateJavascript(PLAY_JS, null); // these pages only load the real file after "play" is pressed
                    if (ttk && SystemClock.elapsedRealtime() - t0 > 3000) wv.evaluateJavascript(IMG_JS, iv -> {
                        try {
                            JSONArray a = new JSONArray(String.valueOf(new JSONTokener(iv).nextValue()));
                            for (int i = 0; i < a.length(); i++) imgs.add(a.optString(i));
                        } catch (Exception ignored) { }
                    });
                    wv.evaluateJavascript(script, value -> {
                    long el = SystemClock.elapsedRealtime() - t0;
                    boolean hasTT = value != null && value.contains("\\\"tt\\\":\\\"{");
                    boolean found = !net.isEmpty() || !api.isEmpty() || (value != null && value.contains("\\\"u\\\":[\\\"http"));
                    boolean enough = mb ? (el > 9000 && (!api.isEmpty() || !net.isEmpty()) && el > 14000) : ttk ? (hasTT || (el > 8000 && imgs.size() >= 2)) : found;
                    if (enough || el > (mb ? 24000 : 12000)) {
                        result[0] = value;
                        cookieUrl[0] = page[0];
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
                };
                wv.loadUrl(url);
                h.postDelayed(tick[0], 2500);
            } catch (Exception e) {
                done.countDown();
            }
        });

        try { done.await(40, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }

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
                String tt = j.optString("tt");
                if (!tt.isEmpty()) {
                    String ck = CookieManager.getInstance().getCookie(page[0]);
                    in.fmts.clear();
                    if (Extractor.tiktokJson(tt, page[0], ck == null ? "" : ck, in) && !in.fmts.isEmpty()) return in;
                }
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

        String ck0 = CookieManager.getInstance().getCookie(cookieUrl[0]);
        String hdr = "Referer: " + page[0] + "\nUser-Agent: " + ua[0] + (ck0 == null || ck0.isEmpty() ? "" : "\nCookie: " + ck0);
        in.fmts.clear();
        Set<String> have = new LinkedHashSet<>();
        // files the page's own API handed out (MovieBox & co.: a list of qualities with sizes)
        int hlsLeft = 3;
        for (String[] e : new java.util.ArrayList<>(api)) {
            try {
                String abs = e[0].startsWith("http") ? e[0] : java.net.URI.create(page[0]).resolve(e[0]).toString();
                if (e[1].trim().startsWith("#EXTM3U")) {
                    if (hlsLeft-- > 0 && have.add(abs)) in.fmts.addAll(Hls.expand(abs, hdr));
                    continue;
                }
                for (Playinfo.Cand c : Playinfo.scan(e[1], abs)) {
                    String cu = whole(c.url);
                    if (!have.add(cu)) continue;
                    if (c.hls) {
                        if (hlsLeft-- > 0) in.fmts.addAll(Hls.expand(cu, hdr));
                        continue;
                    }
                    Extractor.Fmt f = new Extractor.Fmt();
                    f.url = cu;
                    f.headers = hdr;
                    f.size = c.size > 0 ? c.size : -1;
                    f.label = c.label.equals("Video") ? "" : c.label;
                    in.fmts.add(f);
                }
            } catch (Exception ignored) { }
        }
        for (String u : urls) {
            if (!have.add(u)) continue;
            Extractor.Fmt f = new Extractor.Fmt();
            f.url = u;
            f.headers = hdr;
            in.fmts.add(f);
        }
        List<Extractor.Fmt> toProbe = new java.util.ArrayList<>();
        for (Extractor.Fmt f : in.fmts) if (!f.hls) toProbe.add(f);
        Extractor.probeAll(toProbe);

        // same file seen twice (different signatures) is listed once
        Set<String> seen = new LinkedHashSet<>();
        List<Extractor.Fmt> keep = new java.util.ArrayList<>();
        for (Extractor.Fmt f : in.fmts) {
            String key = f.url.split("\\?")[0] + "|" + f.size;
            if (f.hls) keep.add(f); else if (f.size > 0 && seen.add(key)) keep.add(f);
        }
        // MovieBox & co.: the page also loads trailers and ads; when a big file is there, only list the big ones
        boolean bigOne = false;
        for (Extractor.Fmt f : keep) if (f.size >= 15L * 1024 * 1024 || f.hls) bigOne = true;
        if (bigOne && (url.contains("moviebox") || url.contains("aoneroom"))) {
            List<Extractor.Fmt> big = new java.util.ArrayList<>();
            for (Extractor.Fmt f : keep) if (f.size >= 15L * 1024 * 1024 || f.hls) big.add(f);
            keep = big;
        }
        in.fmts.clear();
        in.fmts.addAll(keep);
        Collections.sort(in.fmts, (x, y) -> Long.compare(y.size, x.size));
        int vi = 1, ai = 1;
        for (Extractor.Fmt f : in.fmts) {
            if (f.hls) continue;
            String path = f.url.split("\\?")[0];
            int d = path.lastIndexOf('.');
            f.ext = d >= 0 && path.length() - d <= 5 ? path.substring(d + 1).toLowerCase(Locale.US) : "mp4";
            f.audio = f.mime.startsWith("audio/") || f.ext.equals("mp3") || f.ext.equals("m4a");
            f.video = !f.audio;
            String q = f.label == null ? "" : f.label;
            f.label = f.audio ? "Audio " + ai++ + " · " + f.ext.toUpperCase(Locale.US) : (q.isEmpty() ? "Video " + vi++ : q) + " · " + f.ext.toUpperCase(Locale.US);
            f.detail = MainActivity.sizeText(f.size);
            f.name = Extractor.fileName((in.title.isEmpty() ? in.site : in.title) + (q.isEmpty() ? "" : " " + q), f.ext);
        }
        // photo posts whose data could not be read: take the big pictures the page shows
        boolean hasImg = false;
        for (Extractor.Fmt f : in.fmts) if (f.mime.startsWith("image/")) hasImg = true;
        if (!hasImg && imgs.size() >= 2) {
            int n = 1;
            for (String iu : new java.util.ArrayList<>(imgs)) {
                Extractor.Fmt f = new Extractor.Fmt();
                f.url = iu; f.headers = hdr; f.ext = "jpg"; f.mime = "image/jpeg"; f.video = false;
                f.label = "Image " + n; f.detail = "Picture";
                f.name = Extractor.fileName((in.title.isEmpty() ? "TikTok" : in.title) + " " + n, "jpg");
                n++;
                in.fmts.add(0, f);
            }
            java.util.Collections.sort(in.fmts.subList(0, n - 1), (x, y) -> x.label.compareTo(y.label));
        }
        if (in.fmts.isEmpty()) {
            in.note = "No video could be found. If the post is private or the site asks you to log in, tap \"Sign in\", log in once, then try again.";
        } else {
            in.note = Extractor.GRAB_HINT;
        }
        return in;
    }
}
