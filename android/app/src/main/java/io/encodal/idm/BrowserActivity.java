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
import org.json.JSONTokener;

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
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (WebSniffer.media(u)) consider(u, false);
                return null;
            }

            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                page = u;
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
        if (u.toLowerCase(Locale.US).contains(".m3u8")) return;
        if (!force && !WebSniffer.media(u)) return;
        if (!seen.add(u)) return;
        runOnUiThread(() -> {
            Extractor.Fmt f = new Extractor.Fmt();
            f.url = u;
            String ck = CookieManager.getInstance().getCookie(u);
            f.headers = "Referer: " + page + "\nUser-Agent: " + ua + (ck == null || ck.isEmpty() ? "" : "\nCookie: " + ck);
            String path = u.split("\\?")[0];
            int d = path.lastIndexOf('.');
            f.ext = d >= 0 && path.length() - d <= 5 ? path.substring(d + 1).toLowerCase(Locale.US) : "mp4";
            f.audio = f.ext.equals("mp3") || f.ext.equals("m4a");
            f.video = !f.audio;
            String title = wv == null ? "" : wv.getTitle();
            f.name = Extractor.fileName(title == null || title.isEmpty() ? "video" : title, f.ext);
            caps.add(0, f);
            grabBtn.setText("Grab (" + caps.size() + ")");
            if (open != null && open.isShowing()) probeAndShow();
        });
    }

    private void showGrab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.addView(label("Captured files", 16, 0xFF0F172A, true));
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
        synchronized (caps) { for (Extractor.Fmt f : caps) if (f.size == -1 && f.mime.isEmpty()) todo.add(f); }
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
        for (final Extractor.Fmt f : copy) {
            if (f.size == -2) continue;
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
            row.addView(label(f.name, 14, 0xFF0F172A, true));
            row.addView(label((f.size > 0 ? MainActivity.sizeText(f.size) : "checking size…") + " · " + (file.length() > 34 ? file.substring(0, 33) + "…" : file), 12, 0xFF64748B, false));
            LinearLayout acts = new LinearLayout(this);
            acts.setGravity(Gravity.END);
            acts.addView(action("▶ Preview", 0xFF2563EB, false, v -> startActivity(new Intent(this, PreviewActivity.class)
                    .putExtra("uri", f.url).putExtra("headers", f.headers).putExtra("mime", f.mime).putExtra("title", f.name))));
            acts.addView(action("Copy URL", 0xFF64748B, false, v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url", f.url));
                Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
            }));
            acts.addView(action("Download", 0xFF2563EB, true, v -> {
                Intent s = new Intent(this, DownloadService.class).setAction(DownloadService.ADD)
                        .putExtra("url", f.url).putExtra("connections", Prefs.conns(this))
                        .putExtra("headers", f.headers).putExtra("name", f.name);
                startForegroundService(s);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            }));
            row.addView(acts);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            openList.addView(row, lp);
        }
        if (shown == 0) {
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
