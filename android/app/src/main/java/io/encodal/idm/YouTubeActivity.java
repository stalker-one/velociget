package io.encodal.idm;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * YouTube inside the app: ads and pop-ups removed, keeps playing in the background (screen off / other app),
 * mini player (picture-in-picture), and a Download button for the video that is open.
 */
public class YouTubeActivity extends Activity {
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final String[] BLOCK_HOSTS = {"doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "2mdn.net", "imasdk.googleapis.com", "ads.youtube.com", "static.doubleclick.net"};
    private static final String[] BLOCK_PATHS = {"/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/youtubei/v1/player/ad_break", "ad_break", "/api/stats/atr",
            "/pagead/viewthroughconversion", "adformat=", "&ad_type=", "/ads/ga-audiences"};

    private static final String JS = "(function(){if(window.__idm)return;window.__idm=1;"
            + "var st=document.createElement('style');st.textContent="
            + "'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-ad-slot-renderer,ytm-banner-promo-renderer,"
            + "ytm-statement-banner-renderer,ytm-mealbar-promo-renderer,ytm-upsell-dialog-renderer,ytm-display-ad-renderer,ytm-in-feed-ad-layout-renderer,"
            + "ytm-brand-video-singleton-renderer,ytm-rich-item-renderer:has(ytm-ad-slot-renderer),.ytp-ad-overlay-container,.ytp-ad-module,.video-ads,#player-ads,"
            + "ytm-pivot-bar-item-renderer:has(a[href*=\"/premium\"]),.ytm-open-app-banner,.open-app-banner,ytm-app-banner-renderer,ytm-consent-bump-v2-renderer{display:none!important}"
            + "html.idm-pip video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}"
            + "html.idm-pip body *{contain:none!important;transform:none!important;filter:none!important}"
            + "html.idm-pip body{overflow:hidden!important}"
            + "html.idm-pip .ytp-chrome-top,html.idm-pip .ytp-chrome-bottom,html.idm-pip .ytp-gradient-top,html.idm-pip .ytp-gradient-bottom,html.idm-pip .ytp-pause-overlay,html.idm-pip .ytp-ce-element,html.idm-pip .ytp-cued-thumbnail-overlay,html.idm-pip .player-controls-background-container,html.idm-pip .player-controls-content,html.idm-pip .ytp-overlays-container,html.idm-pip .ytp-spinner,html.idm-pip .ytp-bezel,html.idm-pip .ytp-autonav-endscreen,html.idm-pip .ytp-endscreen-content,html.idm-pip [class*=player-controls]{display:none!important}"
            + "html.idm-pip body>*{visibility:hidden!important}html.idm-pip video{visibility:visible!important}"
            + "@media (max-height:320px) and (max-width:600px){html video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}"
            + "html body *{contain:none!important;transform:none!important;filter:none!important}"
            + "html body{overflow:hidden!important}"
            + "html .ytp-chrome-top,html .ytp-chrome-bottom,html .ytp-gradient-top,html .ytp-gradient-bottom,html .ytp-pause-overlay,html .ytp-ce-element,html .ytp-cued-thumbnail-overlay,html .player-controls-background-container,html .player-controls-content,html .ytp-overlays-container,html .ytp-spinner,html .ytp-bezel,html .ytp-autonav-endscreen,html .ytp-endscreen-content,html [class*=player-controls]{display:none!important}"
            + "html body>*{visibility:hidden!important}html video{visibility:visible!important}}';"
            + "document.documentElement.appendChild(st);"
            // the page believes it is always visible, so it never pauses in the background
            + "try{Object.defineProperty(document,'hidden',{get:function(){return false}});Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});}catch(e){}"
            + "try{Object.defineProperty(document,'webkitHidden',{get:function(){return false}});Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'}});document.hasFocus=function(){return true};}catch(e){}"
            + "['visibilitychange','webkitvisibilitychange','blur','freeze','pause'].forEach(function(n){document.addEventListener(n,function(e){if(n==='pause'&&e.target&&e.target.tagName==='VIDEO'){if((Date.now()-(window.__lt||0))<1500){window.__userPause=1;return}if((window.__bg||window.__pip)&&!window.__userPause&&window.__wasPlaying){setTimeout(function(){try{e.target.play()}catch(x){}},50)}return}if(n==='blur'&&e.target!==document&&e.target!==window)return;e.stopImmediatePropagation()},true)});"
            + "window.addEventListener('blur',function(e){if(e.target===window)e.stopImmediatePropagation()},true);"
            + "window.addEventListener('pagehide',function(e){e.stopImmediatePropagation()},true);"
            + "['touchstart','mousedown','click'].forEach(function(n){window.addEventListener(n,function(){window.__lt=Date.now()},true)});"
            + "document.addEventListener('play',function(e){if(e.target&&e.target.tagName==='VIDEO')window.__userPause=0},true);"
            + "function q(s){return document.querySelector(s)}"
            + "function tick(){try{var p=q('.html5-video-player'),v=q('video.html5-main-video')||q('video');"
            + "var ad=p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')||q('.ytp-ad-player-overlay,.ytp-ad-text'));"
            + "if(ad&&v){v.muted=true;if(isFinite(v.duration)&&v.duration>0){v.currentTime=v.duration}v.playbackRate=16;window.__adSeen=1}"
            + "else if(v&&window.__adSeen){v.muted=false;v.playbackRate=1;window.__adSeen=0}"
            + "var sk=q('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad button,button[class*=skip-ad]');if(sk)sk.click();"
            + "var cl=q('.ytp-ad-overlay-close-button,.ytp-ad-overlay-close-container');if(cl)cl.click();"
            + "var dd=q('ytm-upsell-dialog-renderer button,ytm-mealbar-promo-renderer button[aria-label*=\"ismiss\"],ytm-mealbar-promo-renderer .mealbar-promo-renderer-dismiss-button button,tp-yt-paper-dialog #dismiss-button button,.ytm-confirm-dialog-renderer button');"
            + "if(dd&&!/Sign in|Subscribe/i.test(dd.textContent||''))dd.click();"
            + "if(v){if(!v.paused)window.__wasPlaying=1;if((window.__bg||window.__pip)&&v.paused&&window.__wasPlaying&&!window.__userPause&&!v.ended)v.play()}"
            + "if(window.IDMYT&&v){IDMYT.state(!v.paused&&!v.ended,v.videoWidth||0,v.videoHeight||0);IDMYT.pos(v.currentTime||0)}}catch(e){}}"
            + "setInterval(tick,350);})();";

    private WebView wv;
    private TextView titleView;
    private ProgressBar bar;
    private volatile boolean playing;
    private volatile int vw = 16, vh = 9;
    private LinearLayout top;
    private String pageTitle = "YouTube";
    private BroadcastReceiver rx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0F0F0F);
        getWindow().setNavigationBarColor(0xFF0F0F0F);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F0F0F);

        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(6), dp(8), dp(6));
        top.setBackgroundColor(0xFF0F0F0F);
        top.addView(ibtn(Ico.BACK, 0xFFFFFFFF, v -> onBackPressed()));
        Ico logo = new Ico(this, Ico.YT);
        logo.color(0xFFFF2D2D);
        top.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(32)));
        titleView = new TextView(this);
        titleView.setText("YouTube · no ads");
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(10), 0, dp(6), 0);
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ibtn(Ico.DOWNLOAD, 0xFF60A5FA, v -> download()));
        headBtn = ibtn(Ico.HEADSET, bgWanted() ? 0xFF34D399 : 0xFF9CA3AF, v -> goBackgroundAudio());
        top.addView(headBtn);
        top.addView(ibtn(Ico.PIP, 0xFFFFFFFF, v -> enterPip()));
        top.addView(ibtn(Ico.REFRESH, 0xFFFFFFFF, v -> wv.loadUrl("https://m.youtube.com/")));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(2)));

        // a WebView that never believes it is hidden: that is what keeps YouTube playing with the screen off
        wv = new WebView(this) {
            @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(View.VISIBLE); }

            @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, View.VISIBLE); }
        };
        root.addView(wv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(MOBILE_UA);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        if (Build.VERSION.SDK_INT >= 26) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void pos(double t) { lastPos = t; }

            @JavascriptInterface
            public void state(boolean p, int w, int h) {
                boolean changed = p != playing;
                playing = p;
                if (w > 0 && h > 0) { vw = w; vh = h; }
                if (changed) runOnUiThread(() -> {
                    BgPlayService.refresh(YouTubeActivity.this, pageTitle, playing);
                    pipParams();
                    if (playing && bgWanted()) prefetchAudio();
                });
            }
        }, "IDMYT");
        wv.setWebChromeClient(new WebChromeClient() {
            private View custom;
            private CustomViewCallback cb;

            @Override public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public void onReceivedTitle(WebView v, String t) {
                if (t != null && !t.isEmpty()) {
                    pageTitle = t.replace(" - YouTube", "");
                    titleView.setText(pageTitle);
                    BgPlayService.refresh(YouTubeActivity.this, pageTitle, playing);
                }
            }

            @Override public void onShowCustomView(View v, CustomViewCallback c) {
                custom = v;
                cb = c;
                ((ViewGroup) getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(-1, -1));
                top.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override public void onHideCustomView() {
                if (custom != null) ((ViewGroup) getWindow().getDecorView()).removeView(custom);
                custom = null;
                if (cb != null) cb.onCustomViewHidden();
                top.setVisibility(View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(0);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString().toLowerCase(Locale.US);
                return !(u.startsWith("http://") || u.startsWith("https://")); // never jump out to the YouTube app
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString().toLowerCase(Locale.US);
                String host = r.getUrl().getHost() == null ? "" : r.getUrl().getHost().toLowerCase(Locale.US);
                boolean block = false;
                for (String h : BLOCK_HOSTS) if (host.contains(h)) { block = true; break; }
                if (!block && (host.contains("youtube.com") || host.contains("googlevideo.com"))) {
                    for (String p : BLOCK_PATHS) if (u.contains(p)) { block = true; break; }
                }
                return block ? new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0])) : null;
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                v.evaluateJavascript(JS, null);
            }

            @Override public void onPageFinished(WebView v, String u) {
                v.evaluateJavascript(JS, null);
            }
        });

        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (BgPlayService.CLOSE.equals(a)) { finish(); return; }
                if (BgPlayService.NEXT.equals(a)) {
                    wv.evaluateJavascript("(function(){var b=document.querySelector('.ytp-next-button,button[aria-label*=Next],.player-controls-middle button[aria-label*=Next]');if(b&&b.offsetParent!==null){b.click();return}var v=document.querySelector('video');if(v&&v.duration)v.currentTime=v.duration-0.2})()", null);
                    return;
                }
                if (BgPlayService.MUTE.equals(a)) {
                    ytMuted = !ytMuted;
                    try { if (bgMp != null) bgMp.setVolume(ytMuted ? 0f : 1f, ytMuted ? 0f : 1f); } catch (Exception ignored) { }
                    wv.evaluateJavascript("var v=document.querySelector('video');if(v)v.muted=" + ytMuted, null);
                    pushState();
                    return;
                }
                if (BgPlayService.LOOP.equals(a)) {
                    ytLoop = !ytLoop;
                    wv.evaluateJavascript("var v=document.querySelector('video');if(v)v.loop=" + ytLoop, null);
                    pushState();
                    return;
                }
                if (nativeOn && bgMp != null) {
                    try { if (bgMp.isPlaying()) bgMp.pause(); else bgMp.start(); } catch (Exception ignored) { }
                    playing = bgMp.isPlaying();
                    BgPlayService.refresh(YouTubeActivity.this, pageTitle, playing);
                    return;
                }
                wv.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){if(v.paused){window.__userPause=0;v.play()}else{window.__userPause=1;v.pause()}}})()", null);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BgPlayService.TOGGLE);
        f.addAction(BgPlayService.CLOSE);
        f.addAction(BgPlayService.NEXT);
        f.addAction(BgPlayService.MUTE);
        f.addAction(BgPlayService.LOOP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(rx, f);

        BgPlayService.target = YouTubeActivity.class;
        BgPlayService.start(this);
        String start = getIntent().getStringExtra("url");
        wv.loadUrl(start == null || start.isEmpty() ? "https://m.youtube.com/" : start);
    }

    private Ico headBtn;
    private boolean ytMuted, ytLoop;
    private String ytMode = "audio";
    private void pushState() { BgPlayService.state(this, ytMode, ytMuted, ytLoop); }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        if (in.getBooleanExtra("pipnow", false)) { in.removeExtra("pipnow"); enterPip(); }
    }

    /** Background AUDIO mode: leave the app now, sound only. */
    private void goBackgroundAudio() {
        ytMode = "audio";
        Prefs.putBool(this, "bgplay", true);
        headBtn.color(0xFF34D399);
        pushState();
        Toast.makeText(this, "Background audio — keeps playing with the screen off", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private Ico ibtn(int kind, int color, View.OnClickListener l) {
        Ico i = new Ico(this, kind);
        i.color(color);
        i.setOnClickListener(l);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        return i;
    }

    private boolean bgWanted() { return Prefs.getBool(this, "bgplay", true); }

    private void toggleBg() {
        boolean on = !bgWanted();
        Prefs.putBool(this, "bgplay", on);
        headBtn.color(on ? 0xFF34D399 : 0xFF9CA3AF);
        Toast.makeText(this, on ? "Background play ON — keeps playing when you leave the app" : "Background play OFF", Toast.LENGTH_SHORT).show();
    }

    private TextView btn(String t, int color, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(4), dp(12), dp(4));
        v.setOnClickListener(l);
        return v;
    }

    private void download() {
        String u = wv.getUrl();
        if (u == null || !(u.contains("watch") || u.contains("shorts") || u.contains("youtu.be"))) {
            Toast.makeText(this, "Open a video first, then tap ⬇", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(u)).putExtra("details", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void pipParams() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PictureInPictureParams.Builder pb = new PictureInPictureParams.Builder().setAspectRatio(new Rational(Math.max(1, Math.min(vw, vh * 2)), Math.max(1, vh)));
            java.util.ArrayList<android.app.RemoteAction> acts = new java.util.ArrayList<>();
            acts.add(new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                    playing ? "Pause" : "Play", "Play / pause",
                    android.app.PendingIntent.getBroadcast(this, 5, new Intent(BgPlayService.TOGGLE).setPackage(getPackageName()), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT)));
            pb.setActions(acts);
            if (Build.VERSION.SDK_INT >= 31) pb.setAutoEnterEnabled(playing);
            setPictureInPictureParams(pb.build());
        } catch (Exception ignored) { }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) { Toast.makeText(this, "Mini player needs Android 8 or newer", Toast.LENGTH_SHORT).show(); return; }
        try {
            ytMode = "pip";
            pushState();
            // style the page for the small window BEFORE it shrinks: no flash of YouTube controls, no waiting for the callback
            wv.evaluateJavascript("window.__pip=1;window.__userPause=0;document.documentElement.classList.add('idm-pip');var v=document.querySelector('video');if(v&&v.paused)v.play()", null);
            pipParams();
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(Math.max(1, Math.min(vw, vh * 2)), Math.max(1, vh))).build());
        } catch (Exception e) {
            Toast.makeText(this, "Allow picture-in-picture for this app in Android settings", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        if (playing && Prefs.getBool(this, "autopip", true) && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode()) {
            try { enterPip(); } catch (Exception ignored) { }
        }
    }

    // ---- background playback: the page's own player is unreliable with the screen off, so the sound moves to a native player ----
    private MediaPlayer bgMp;
    private volatile boolean nativeOn, stopped, resolving;
    private volatile double lastPos;
    private String audioFor = "", audioUrl = "", audioHdr = "";

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        if (wv != null) {
            wv.evaluateJavascript("window.__bg=1", null);
            wv.onResume();
            wv.resumeTimers();
        }
        if (bgWanted() && playing && !nativeOn && !resolving) startNative();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        if (wv != null) wv.evaluateJavascript("window.__bg=0", null);
        if (nativeOn) stopNative(true);
    }

    private volatile boolean prefetching;

    /** Finds the audio stream of the current video in the background so that going to the background is instant. */
    private void prefetchAudio() {
        final String page = wv == null ? null : wv.getUrl();
        if (page == null || page.equals(audioFor) || prefetching || !(page.contains("watch") || page.contains("youtu.be") || page.contains("shorts"))) return;
        prefetching = true;
        new Thread(() -> {
            try {
                Extractor.Info in = PyEngine.probe(this, page);
                Extractor.Fmt best = null;
                for (Extractor.Fmt f : in.fmts) {
                    if (!f.audio || f.hls || f.url.isEmpty()) continue;
                    boolean m4a = f.ext.equals("m4a") || f.ext.equals("mp4");
                    boolean bm = best != null && (best.ext.equals("m4a") || best.ext.equals("mp4"));
                    if (best == null || (m4a && !bm) || (m4a == bm && f.size > best.size)) best = f;
                }
                if (best == null) { lastErr = "no audio stream found"; }
                else { audioUrl = best.url; audioHdr = best.headers; audioFor = page; lastErr = ""; }
            } catch (Throwable e) {
                lastErr = String.valueOf(e.getMessage());
            }
            prefetching = false;
        }).start();
    }

    private volatile String lastErr = "";

    private void startNative() {
        final String page = wv == null ? null : wv.getUrl();
        if (page == null || !(page.contains("watch") || page.contains("youtu.be") || page.contains("shorts"))) return;
        resolving = true;
        final double at0 = lastPos;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (!page.equals(audioFor) && System.currentTimeMillis() - t0 < 25000) {
                if (!prefetching) prefetchAudio();
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                if (!prefetching && !page.equals(audioFor) && !lastErr.isEmpty()) break;
            }
            if (!page.equals(audioFor)) {
                resolving = false;
                final String why = lastErr;
                runOnUiThread(() -> Toast.makeText(this, "Background audio could not start (" + why + ") — the video keeps playing in the page instead", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> beginNative(Math.max(lastPos, at0)));
        }).start();
    }

    private void beginNative(double pos) {
        resolving = false;
        if (!stopped || nativeOn || isInPictureInPictureMode()) return; // user came back / mini window is showing the video
        try {
            bgMp = new MediaPlayer();
            bgMp.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            java.util.Map<String, String> hm = new java.util.HashMap<>();
            for (String line : audioHdr.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) hm.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            bgMp.setDataSource(this, Uri.parse(audioUrl), hm);
            bgMp.setOnPreparedListener(m -> {
                if (!stopped) { try { m.release(); } catch (Exception ignored) { } bgMp = null; return; }
                try { m.seekTo((int) (pos * 1000)); m.start(); } catch (Exception ignored) { }
                nativeOn = true;
                playing = true;
                if (wv != null) wv.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){window.__userPause=1;v.pause()}})()", null);
                BgPlayService.refresh(this, pageTitle, true);
            });
            bgMp.setOnCompletionListener(m -> { nativeOn = false; playing = false; BgPlayService.refresh(this, pageTitle, false); });
            bgMp.setOnErrorListener((m, w, e) -> { stopNative(false); return true; });
            bgMp.prepareAsync();
        } catch (Exception e) {
            stopNative(false);
        }
    }

    /** Back in the app (or the native player failed): hand playback back to the page at the same second. */
    private void stopNative(boolean resume) {
        double pos = lastPos;
        boolean was = nativeOn && bgMp != null && bgMp.isPlaying();
        try { if (bgMp != null) { pos = bgMp.getCurrentPosition() / 1000.0; bgMp.release(); } } catch (Exception ignored) { }
        bgMp = null;
        nativeOn = false;
        if (wv != null) {
            final String js = "(function(){var v=document.querySelector('video');if(v){" + (resume ? "v.currentTime=" + pos + ";" : "") + "window.__userPause=" + (was || !resume ? 0 : 1) + ";" + (was || !resume ? "v.play();" : "") + "}})()";
            wv.evaluateJavascript(js, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pipParams();
        if (wv != null) { wv.onResume(); wv.resumeTimers(); } // never pause the page
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        top.setVisibility(inPip ? View.GONE : View.VISIBLE);
        bar.setVisibility(View.GONE);
        if (!inPip) { ytMode = "audio"; pushState(); }
        final String js = "window.__pip=" + (inPip ? 1 : 0) + ";window.__userPause=0;document.documentElement.classList." + (inPip ? "add" : "remove") + "('idm-pip');window.dispatchEvent(new Event('resize'));var v=document.querySelector('video');if(v&&" + inPip + "&&v.paused&&!window.__userPause)v.play()";
        wv.evaluateJavascript(js, null);
        wv.postDelayed(() -> { if (wv != null) wv.evaluateJavascript(js, null); }, 400);
        wv.postDelayed(() -> { if (wv != null) wv.evaluateJavascript(js, null); }, 1200);
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    // no onPause on the web view: that is what keeps the sound going when the screen turns off

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        try { if (bgMp != null) bgMp.release(); } catch (Exception ignored) { }
        nativeOn = false;
        BgPlayService.stop(this);
        if (wv != null) { ((ViewGroup) wv.getParent()).removeView(wv); wv.destroy(); wv = null; }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
