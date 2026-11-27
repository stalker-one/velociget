package io.encodal.idm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A browser with a video grabber, like the one in desktop download managers.
 * Sign in, open an episode or a post and press play; every video or audio file the page loads is captured.
 * Tap "Grab" to see them with their sizes and download, preview or copy any of them.
 */
public class BrowserActivity extends Activity {
    private WebView wv;
    private TextView grabBtn;
    private final List<Extractor.Fmt> caps = Collections.synchronizedList(new ArrayList<Extractor.Fmt>());
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
    private final Handler h = new Handler(Looper.getMainLooper());
    private String page = "", ua = Extractor.UA;
    private AlertDialog open;
    private LinearLayout openList;
    private String apiUrl = "", apiHdr = "", hookJs = "";
    private final Set<String> tiktokDone = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean busy;
    private TextView epBtn;
    private boolean showClips;

    private static final String SCAN = "(function(){var o=[];document.querySelectorAll('video,audio,source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&s.indexOf('blob:')!==0)o.push(s)});return JSON.stringify(o)})()";

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            if (wv == null) return;
            wv.evaluateJavascript(SCAN, value -> {
                try {
                    if (value != null && !value.equals("null")) {
                        JSONArray a = new JSONArray(String.valueOf(new JSONTokener(value).nextValue()));
                        for (int i = 0; i < a.length(); i++) consider(a.optString(i), true);
                    }
                } catch (Exception ignored) { }
            });
            h.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF1D4ED8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1D4ED8);
        bar.setPadding(dp(14), dp(8), dp(8), dp(8));
        TextView t = new TextView(this);
        t.setText("Browse · play · Grab");
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        grabBtn = new TextView(this);
        grabBtn.setText("Grab (0)");
        grabBtn.setTextColor(0xFF1D4ED8);
        grabBtn.setTypeface(Typeface.DEFAULT_BOLD);
        grabBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(18));
        grabBtn.setBackground(g);
        grabBtn.setOnClickListener(v -> showGrab());
        bar.addView(grabBtn);
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(Color.WHITE);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setPadding(dp(16), dp(8), dp(16), dp(8));
        done.setOnClickListener(v -> finish());
        bar.addView(done);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        ua = s.getUserAgentString();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        try (InputStream in = getAssets().open("hook.js")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            hookJs = bo.toString("UTF-8");
        } catch (Exception ignored) { }
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onJson(String url, String text, String hdrs) { json(url, text, hdrs); }
        }, "IDMBridge");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Apps like TikTok try to open "snssdk1233://..." — a web view cannot, and shows an error. Stay on the web page.
                return !isWeb(r.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (WebSniffer.media(u)) consider(u, false);
                else if (u.split("[?#]")[0].toLowerCase(Locale.US).endsWith(".m3u8")) consider(u, true);
                return null;
            }

            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                page = u;
                if (!hookJs.isEmpty() && isWeb(u)) v.evaluateJavascript(hookJs, null);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (!hookJs.isEmpty() && isWeb(u)) v.evaluateJavascript(hookJs, null);
                rememberTitle();
                readTikTok(u);
            }
        });
        root.addView(wv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        String u = getIntent().getStringExtra("url");
        wv.loadUrl(u == null || u.isEmpty() ? "https://www.google.com" : u);
        h.postDelayed(scan, 2500);
    }

    /** A media file seen by the page. {@code force} accepts links without a file extension (they come from a video tag). */
    private void consider(final String raw, final boolean force) {
        if (raw == null || !raw.startsWith("http")) return;
        final String u = WebSniffer.whole(raw);
        if (!force && !WebSniffer.media(u)) return;
        if (!seen.add(u)) return;
        if (u.toLowerCase(Locale.US).contains(".m3u8")) { hls(u, null); return; }
        runOnUiThread(() -> {
            Extractor.Fmt f = new Extractor.Fmt();
            f.url = u;
            f.label = "Video";
            String ck = CookieManager.getInstance().getCookie(u);
            f.headers = "Referer: " + page + "\nUser-Agent: " + ua + (ck == null || ck.isEmpty() ? "" : "\nCookie: " + ck);
            String path = u.split("\\?")[0];
            int d = path.lastIndexOf('.');
            f.ext = d >= 0 && path.length() - d <= 5 ? path.substring(d + 1).toLowerCase(Locale.US) : "mp4";
            f.audio = f.ext.equals("mp3") || f.ext.equals("m4a");
            f.video = !f.audio;
            rememberTitle();
            f.name = Extractor.fileName(curTitle, f.ext);
            caps.add(0, f);
            grabBtn.setText("Grab (" + caps.size() + ")");
            if (open != null && open.isShowing()) probeAndShow();
        });
    }

    private static boolean isWeb(String u) {
        String l = u == null ? "" : u.toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("about:") || l.startsWith("data:") || l.startsWith("blob:") || l.startsWith("javascript:");
    }

    private volatile String curTitle = "video";

    private String title() { return curTitle; }

    private void rememberTitle() {
        String t = wv == null ? "" : wv.getTitle();
        if (t != null && !t.isEmpty() && !t.startsWith("http")) curTitle = t;
    }

    private String hdrFor(String u, String extra) {
        String ck = CookieManager.getInstance().getCookie(u);
        String h = "Referer: " + page + "\nUser-Agent: " + ua + (ck == null || ck.isEmpty() ? "" : "\nCookie: " + ck);
        if (extra != null) for (String line : extra.split("\n")) {
            String l = line.toLowerCase(Locale.US);
            if (line.trim().isEmpty() || l.startsWith("cookie:") || l.startsWith("referer:") || l.startsWith("user-agent:")) continue;
            h += "\n" + line.trim();
        }
        return h;
    }

    /** JSON the page loaded from its own API (called from the page, on a background thread). */
    private void json(final String url, final String text, final String hdrs) {
        if (url == null || text == null) return;
        final String abs = url.startsWith("http") ? url : (page.contains("://") ? java.net.URI.create(page).resolve(url).toString() : url);
        if (!abs.startsWith("http")) return;
        if (text.trim().startsWith("#EXTM3U")) {
            if (seen.add(abs)) hls(abs, hdrs);
            return;
        }
        List<Playinfo.Cand> cs = Playinfo.scan(text, abs);
        if (cs.isEmpty()) return;
        apiUrl = abs;
        apiHdr = hdrs == null ? "" : hdrs;
        final String ttl = title();
        for (Playinfo.Cand c : cs) addCand(c, "", ttl, hdrs);
    }

    private void addCand(final Playinfo.Cand c, final String prefix, final String ttl, final String hdrs) {
        if (!seen.add(c.url)) return;
        final String hd = hdrFor(c.url, hdrs);
        if (c.hls) { hlsRows(c.url, hd, prefix, ttl); return; }
        runOnUiThread(() -> {
            Extractor.Fmt f = new Extractor.Fmt();
            f.url = c.url;
            f.headers = hd;
            f.ext = c.ext;
            f.audio = c.ext.equals("mp3") || c.ext.equals("m4a");
            f.video = !f.audio;
            f.size = c.size > 0 ? c.size : -1;
            f.label = prefix + c.label;
            f.detail = "from the site's API";
            f.name = Extractor.fileName(ttl + (prefix.isEmpty() ? "" : " " + prefix.trim()) + (c.label.equals("Video") ? "" : " " + c.label), c.ext);
            caps.add(0, f);
            changed();
        });
    }

    private void hls(final String u, final String hdrs) {
        hlsRows(u, hdrFor(u, hdrs), "", title());
    }

    private void hlsRows(final String u, final String hd, final String prefix, final String ttl) {
        new Thread(() -> {
            try {
                List<Extractor.Fmt> rows = Hls.expand(u, hd);
                for (Extractor.Fmt f : rows) {
                    f.label = prefix + f.label;
                    f.name = Extractor.fileName(ttl + (prefix.isEmpty() ? "" : " " + prefix.trim()) + " " + f.label.replace(prefix, "").replace(" · HLS", ""), "mp4");
                }
                runOnUiThread(() -> { caps.addAll(0, rows); changed(); });
            } catch (Exception ignored) { }
        }).start();
    }

    private void changed() {
        grabBtn.setText("Grab (" + caps.size() + ")");
        if (open != null && open.isShowing()) probeAndShow();
    }

    /** TikTok pages carry the post as JSON (video, photos, sound). Read it from the loaded page. */
    private void readTikTok(final String u) {
        if (u == null || !u.contains("tiktok.com") || wv == null) return;
        domImages(u);
        wv.evaluateJavascript("(function(){var e=document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');return e?e.textContent:''})()", value -> {
            try {
                if (value == null || value.length() < 10) return;
                final String js = String.valueOf(new JSONTokener(value).nextValue());
                if (js.length() < 10 || !tiktokDone.add(u)) return;
                new Thread(() -> {
                    Extractor.Info in = new Extractor.Info();
                    in.url = u;
                    String ck = CookieManager.getInstance().getCookie(u);
                    if (Extractor.tiktokJson(js, u, ck == null ? "" : ck, in) && !in.fmts.isEmpty()) {
                        for (Extractor.Fmt f : in.fmts) f.label = f.label + " · " + f.detail;
                        runOnUiThread(() -> {
                            for (Extractor.Fmt f : in.fmts) if (seen.add(f.url)) caps.add(f);
                            changed();
                        });
                    }
                }).start();
            } catch (Exception ignored) { }
        });
    }

    /** Slideshow / photo posts: if the page data is not readable, take the big pictures shown on the page. */
    private void domImages(final String u) {
        if (!u.contains("/photo/") && !u.contains("/video/")) return;
        wv.postDelayed(() -> {
            if (wv == null) return;
            wv.evaluateJavascript("(function(){var o=[],s={};document.querySelectorAll('img').forEach(function(i){var u=i.currentSrc||i.src||'';"
                    + "if(/tiktokcdn|byteimg|muscdn/.test(u)&&!/avt-|avatar|\\/obj\\/|100x100|200x200|cover-|tos-.*-i-.*100/.test(u)&&i.naturalWidth>=300&&!s[u]){s[u]=1;o.push(u)}});return JSON.stringify(o)})()", v -> {
                try {
                    String raw = String.valueOf(new JSONTokener(v).nextValue());
                    JSONArray a = new JSONArray(raw);
                    String hd = hdrFor(u, null);
                    final List<Extractor.Fmt> add = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        String iu = a.optString(i);
                        if (iu.isEmpty() || seen.contains(iu)) continue;
                        Extractor.Fmt f = new Extractor.Fmt();
                        f.url = iu; f.headers = hd; f.ext = "jpg"; f.mime = "image/jpeg";
                        f.label = "Image " + (add.size() + 1);
                        f.name = Extractor.fileName(curTitle + " " + (add.size() + 1), "jpg");
                        add.add(f);
                    }
                    if (add.size() >= 2) runOnUiThread(() -> { for (Extractor.Fmt f : add) if (seen.add(f.url)) caps.add(f); changed(); });
                } catch (Exception ignored) { }
            });
        }, 2500);
    }

    /** Replays the captured API call for episode 1, 2, 3 … (and seasons) and lists every file. */
    private void allEpisodes() {
        if (busy) return;
        if (apiUrl.isEmpty()) {
            Toast.makeText(this, "Open an episode and press play first, so the app can learn the site's API.", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        Toast.makeText(this, "Looking for every episode…", Toast.LENGTH_SHORT).show();
        final String base = apiUrl, hdr = hdrFor(base, apiHdr), ttl = title();
        new Thread(() -> {
            try {
                String epKey = null, seKey = null;
                for (String k : new String[]{"ep", "episode", "episodeId", "epNum"}) if (base.matches(".*[?&]" + k + "=\\d+.*")) { epKey = k; break; }
                for (String k : new String[]{"se", "season", "seasonId"}) if (base.matches(".*[?&]" + k + "=\\d+.*")) { seKey = k; break; }
                if (epKey == null) {
                    runOnUiThread(() -> Toast.makeText(this, "This site's API has no episode number to step through. Open each episode and press play.", Toast.LENGTH_LONG).show());
                    return;
                }
                int seMax = seKey == null ? 1 : 12;
                int added = 0;
                for (int se = seKey == null ? 0 : 1; se <= seMax && wv != null; se++) {
                    String su = seKey == null ? base : base.replaceAll("([?&]" + seKey + "=)\\d+", "$1" + se);
                    int miss = 0;
                    boolean any = false;
                    for (int ep = 1; ep <= 100 && miss < 2 && wv != null; ep++) {
                        String eu = su.replaceAll("([?&]" + epKey + "=)\\d+", "$1" + ep);
                        List<Playinfo.Cand> cs;
                        try { cs = Playinfo.scan(Extractor.getText(eu, hdr), eu); } catch (Exception e) { cs = new ArrayList<>(); }
                        if (cs.isEmpty()) { miss++; continue; }
                        miss = 0;
                        any = true;
                        String pre = (seKey == null ? "" : "S" + se + " · ") + "E" + ep + " · ";
                        for (Playinfo.Cand c : cs) { addCand(c, pre, ttl, apiHdr); added++; }
                    }
                    if (!any && se > 1) break;
                    if (!any && se == 1) break;
                }
                final int n = added;
                runOnUiThread(() -> Toast.makeText(this, n == 0 ? "No more episodes found. Open each one and press play." : "Found " + n + " files", Toast.LENGTH_LONG).show());
            } finally {
                busy = false;
            }
        }).start();
    }

    private void showGrab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.addView(label("Captured files", 16, 0xFF0F172A, true));
        epBtn = action("All episodes", 0xFF2563EB, true, v -> allEpisodes());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.topMargin = dp(8);
        box.addView(epBtn, elp);
        openList = new LinearLayout(this);
        openList.setOrientation(LinearLayout.VERTICAL);
        box.addView(openList);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        open = new AlertDialog.Builder(this).setView(sv).create();
        open.show();
        probeAndShow();
    }

    private void probeAndShow() {
        render();
        final List<Extractor.Fmt> todo = new ArrayList<>();
        synchronized (caps) { for (Extractor.Fmt f : caps) if (f.size == -1 && f.mime.isEmpty() && !f.hls) todo.add(f); }
        if (todo.isEmpty()) return;
        new Thread(() -> {
            Extractor.probeAll(new ArrayList<>(todo));
            runOnUiThread(() -> { if (open != null && open.isShowing()) render(); });
        }).start();
    }

    private void render() {
        if (openList == null) return;
        openList.removeAllViews();
        int shown = 0;
        List<Extractor.Fmt> copy;
        synchronized (caps) { copy = new ArrayList<>(caps); }
        Collections.sort(copy, (x, y) -> Long.compare(y.size, x.size));
        epBtn.setVisibility(apiUrl.isEmpty() || page.contains("tiktok.com") ? View.GONE : View.VISIBLE);
        // the real files first; the many small preview clips / trailers a page loads are tucked away
        List<Extractor.Fmt> mains = new ArrayList<>(), clips = new ArrayList<>();
        java.util.Set<String> dup = new java.util.HashSet<>();
        for (Extractor.Fmt f : copy) {
            if (f.size == -2) continue;
            if (!dup.add(f.url.split("\\?")[0] + "|" + f.size)) continue;
            boolean small = f.video && !f.audio ? false : false;
            if (f.hls || f.mime.startsWith("image/") || (f.audio && !f.video) || f.size < 0 || f.size >= 15L * 1024 * 1024) mains.add(f); else clips.add(f);
        }
        final int mainCount = mains.size();
        List<Extractor.Fmt> ordered = new ArrayList<>(mains);
        if (showClips) ordered.addAll(clips);
        for (final Extractor.Fmt f : ordered) {
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFF1F5F9);
            g.setCornerRadius(dp(12));
            row.setBackground(g);
            String file = f.url.split("\\?")[0];
            file = file.substring(file.lastIndexOf('/') + 1);
            String kind = f.audio && !f.video ? "" : f.hls ? "" : f.size > 0 && f.size < 15L * 1024 * 1024 ? " · short clip" : f.size >= 15L * 1024 * 1024 ? " · full video" : "";
            row.addView(label((f.label.isEmpty() ? f.name : f.label) + kind, 14, 0xFF0F172A, true));
            String sz = f.size > 0 ? (f.hls ? "~" : "") + MainActivity.sizeText(f.size) : (f.hls ? "stream" : "checking size…");
            row.addView(label(sz + " · " + (f.hls ? "saved as MP4" : (file.length() > 34 ? file.substring(0, 33) + "…" : file)), 12, 0xFF64748B, false));
            LinearLayout acts = new LinearLayout(this);
            acts.setGravity(Gravity.END);
            if (!f.hls) acts.addView(action("▶ Preview", 0xFF2563EB, false, v -> startActivity(new Intent(this, PreviewActivity.class)
                    .putExtra("uri", f.url).putExtra("headers", f.headers).putExtra("mime", f.mime).putExtra("title", f.name))));
            acts.addView(action("Copy URL", 0xFF64748B, false, v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url", f.url));
                Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
            }));
            acts.addView(action("Download", 0xFF2563EB, true, v -> {
                Intent s = new Intent(this, DownloadService.class).setAction(DownloadService.ADD)
                        .putExtra("url", f.url).putExtra("connections", Prefs.conns(this))
                        .putExtra("headers", f.headers).putExtra("name", f.name).putExtra("hls", f.hls);
                startForegroundService(s);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            }));
            row.addView(acts);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            openList.addView(row, lp);
        }
        if (!clips.isEmpty()) {
            TextView tg = action(showClips ? "▲ Hide " + clips.size() + " short clips / trailers" : "▼ Show " + clips.size() + " short clips / trailers (not the full video)", 0xFF64748B, false, v -> { showClips = !showClips; render(); });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = dp(8);
            openList.addView(tg, Math.min(mainCount, openList.getChildCount()), tp);
        }
        if (shown == 0 && clips.isEmpty()) {
            TextView e = label("Nothing captured yet.\nOpen the episode or post and press play, then tap Grab again.", 13, 0xFF64748B, false);
            e.setPadding(0, dp(12), 0, dp(12));
            openList.addView(e);
        }
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView action(String s, int color, boolean filled, View.OnClickListener l) {
        TextView t = label(s, 13, filled ? Color.WHITE : color, true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        if (filled) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(dp(16));
            t.setBackground(g);
        }
        t.setOnClickListener(l);
        return t;
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(scan);
        if (wv != null) { wv.destroy(); wv = null; }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
