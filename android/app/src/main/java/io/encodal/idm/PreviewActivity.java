package io.encodal.idm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Plays a video or song, shows a picture or a text file, from a link (before downloading) or from the downloaded file. */
public class PreviewActivity extends Activity {
    private FrameLayout stage;
    private ProgressBar spinner;
    private TextView message;
    private VideoView video;
    private String uri, mime, title;
    private boolean cachedTry;
    private java.util.ArrayList<String> plUris, plNames, plMimes;
    private int plIdx;
    private TextView titleView;
    private MediaPlayer player;
    private boolean muted, locked, shown = true, fullscreen, seeking;
    private float speed = 1f;
    private int swipeSeekMs;
    private final Handler progress = new Handler(Looper.getMainLooper());
    private LinearLayout panel, bar;
    private SeekBar seek;
    private TextView cur, dur, hud, playBtn, muteBtn, speedBtn, lockBtn, unlockBtn;
    private final Runnable hide = () -> showControls(false);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (video != null && !seeking) {
                int d = video.getDuration(), p = video.getCurrentPosition();
                if (d > 0) { seek.setMax(d); seek.setProgress(p); dur.setText(clock(d)); }
                cur.setText(clock(p));
                playBtn.setText(video.isPlaying() ? "⏸" : "▶");
            }
            progress.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        uri = getIntent().getStringExtra("uri");
        mime = getIntent().getStringExtra("mime");
        title = getIntent().getStringExtra("title");
        final String headers = getIntent().getStringExtra("headers");
        plUris = getIntent().getStringArrayListExtra("pl_uris");
        plNames = getIntent().getStringArrayListExtra("pl_names");
        plMimes = getIntent().getStringArrayListExtra("pl_mimes");
        plIdx = getIntent().getIntExtra("pl_index", 0);
        if (uri == null) { finish(); return; }
        if (mime == null) mime = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        close.setPadding(dp(14), dp(6), dp(14), dp(6));
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        TextView t = new TextView(this);
        t.setText(title == null || title.isEmpty() ? "Preview" : title);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView = t;
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (plUris != null && plUris.size() > 1) {
            TextView pv = new TextView(this);
            pv.setText("⏮");
            pv.setTextColor(Color.WHITE);
            pv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            pv.setPadding(dp(10), dp(6), dp(10), dp(6));
            pv.setOnClickListener(v -> step(-1));
            bar.addView(pv);
            TextView nx = new TextView(this);
            nx.setText("⏭");
            nx.setTextColor(Color.WHITE);
            nx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            nx.setPadding(dp(10), dp(6), dp(10), dp(6));
            nx.setOnClickListener(v -> step(1));
            bar.addView(nx);
        }
        TextView open = new TextView(this);
        open.setText("Open with…");
        open.setTextColor(0xFF93C5FD);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setPadding(dp(14), dp(6), dp(14), dp(6));
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime.isEmpty() ? "*/*" : mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { say("No app can open this file"); }
        });
        bar.addView(open);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        spinner = new ProgressBar(this);
        stage.addView(spinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        message = new TextView(this);
        message.setTextColor(0xFFCBD5E1);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        message.setVisibility(View.GONE);
        stage.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setContentView(root);

        String ext = "";
        String name = (title == null ? "" : title).toLowerCase(Locale.US);
        int d = name.lastIndexOf('.');
        if (d >= 0) ext = name.substring(d + 1);
        String m = mime.toLowerCase(Locale.US);
        if (m.startsWith("video/") || m.startsWith("audio/") || ext.matches("mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac")) playMedia(headers, m.startsWith("audio/") || ext.matches("mp3|m4a|aac|ogg|opus|wav|flac"));
        else if (m.startsWith("image/") || ext.matches("jpg|jpeg|png|gif|webp|bmp")) showImage(headers);
        else if (m.startsWith("text/") || ext.matches("txt|log|md|json|csv|xml")) showText(headers);
        else say("This file type cannot be previewed here.\nTap \"Open with…\" to use another app.");
    }

    /** Next / previous file of the playlist (files opened from the Files screen). */
    private void step(int d) {
        if (plUris == null || video == null) return;
        int n = plIdx + d;
        if (n < 0 || n >= plUris.size()) return;
        plIdx = n;
        uri = plUris.get(n);
        mime = plMimes.get(n);
        title = plNames.get(n);
        titleView.setText(title);
        message.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        video.stopPlayback();
        video.setVideoURI(Uri.parse(uri));
    }


    // ---- MX-style controls: gestures, mute, speed, lock, rotate / full screen ----

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%02d:%02d", m, s % 60);
    }

    private TextView ctl(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setOnClickListener(x -> { l.onClick(x); kick(); });
        return v;
    }

    private void kick() {
        progress.removeCallbacks(hide);
        if (video != null && video.isPlaying()) progress.postDelayed(hide, 3500);
    }

    private void showControls(boolean on) {
        shown = on;
        if (locked) { unlockBtn.setVisibility(on ? View.VISIBLE : View.GONE); panel.setVisibility(View.GONE); if (fullscreen) bar.setVisibility(View.GONE); return; }
        panel.setVisibility(on ? View.VISIBLE : View.GONE);
        bar.setVisibility(fullscreen && !on ? View.GONE : View.VISIBLE);
        unlockBtn.setVisibility(View.GONE);
        if (on) kick();
    }

    private void setSpeed(float sp) {
        speed = sp;
        try {
            if (player != null) player.setPlaybackParams(player.getPlaybackParams().setSpeed(sp));
        } catch (Exception ignored) { }
        if (speedBtn != null) speedBtn.setText((sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "×");
    }

    private void toast(String s) {
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        hud.removeCallbacks(hudOff);
        hud.postDelayed(hudOff, 700);
    }

    private final Runnable hudOff = () -> hud.setVisibility(View.GONE);

    private void rotate() {
        fullscreen = !fullscreen;
        setRequestedOrientation(fullscreen ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        immersive(fullscreen);
    }

    private void immersive(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_VISIBLE);
    }

    private void seekBy(int ms) {
        if (video == null) return;
        int t = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + ms));
        video.seekTo(t);
        toast((ms > 0 ? "+" : "−") + Math.abs(ms / 1000) + "s   " + clock(t));
    }

    private void attachControls(boolean audio) {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int vmax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        // overlay that catches touches: tap = show/hide, double tap = ±10 s, swipes = brightness / volume / seek
        final View touch = new View(this);
        stage.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        hud.setTypeface(Typeface.DEFAULT_BOLD);
        hud.setBackgroundColor(0x99000000);
        hud.setPadding(dp(18), dp(10), dp(18), dp(10));
        hud.setVisibility(View.GONE);
        stage.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            float startBright, seekDelta;
            int startVol, mode; // 1 brightness, 2 volume, 3 seek

            @Override public boolean onDown(MotionEvent e) {
                mode = 0;
                seekDelta = 0;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                startBright = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
                startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) { showControls(!shown); return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (locked) return true;
                float x = e.getX() / Math.max(1, touch.getWidth());
                if (x < 0.35f) seekBy(-10000);
                else if (x > 0.65f) seekBy(10000);
                else if (video.isPlaying()) { video.pause(); showControls(true); } else video.start();
                return true;
            }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (locked || a == null || video == null) return false;
                float tx = b.getX() - a.getX(), ty = b.getY() - a.getY();
                if (mode == 0) {
                    if (Math.abs(tx) < dp(14) && Math.abs(ty) < dp(14)) return true;
                    mode = Math.abs(ty) > Math.abs(tx) ? (a.getX() < touch.getWidth() / 2f ? 1 : 2) : 3;
                }
                float h = Math.max(1, touch.getHeight());
                if (mode == 1) {
                    float v = Math.max(0.02f, Math.min(1f, startBright - ty / h * 1.2f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v;
                    getWindow().setAttributes(lp);
                    toast("☀ Brightness " + Math.round(v * 100) + "%");
                } else if (mode == 2) {
                    int v = Math.max(0, Math.min(vmax, Math.round(startVol - ty / h * vmax * 1.2f)));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    toast((v == 0 ? "🔇" : "🔊") + " Volume " + Math.round(v * 100f / vmax) + "%");
                } else {
                    seekDelta = tx / Math.max(1, touch.getWidth()) * 120000f;
                    swipeSeekMs = Math.abs(seekDelta) > 500 ? (int) seekDelta : 0;
                    int t = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + (int) seekDelta));
                    toast((seekDelta >= 0 ? "+" : "−") + Math.abs(Math.round(seekDelta / 1000)) + "s   " + clock(t));
                }
                return true;
            }
        });
        touch.setOnTouchListener((v, e) -> {
            boolean r = gd.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                finishSwipe();
            }
            return r;
        });

        // bottom panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xB3000000);
        panel.setPadding(dp(10), dp(6), dp(10), dp(8));
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        cur = new TextView(this);
        cur.setTextColor(Color.WHITE);
        cur.setText("00:00");
        dur = new TextView(this);
        dur.setTextColor(Color.WHITE);
        dur.setText("00:00");
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s2, int p, boolean user) { if (user) cur.setText(clock(p)); }
            public void onStartTrackingTouch(SeekBar s2) { seeking = true; progress.removeCallbacks(hide); }
            public void onStopTrackingTouch(SeekBar s2) { seeking = false; if (video != null) video.seekTo(s2.getProgress()); kick(); }
        });
        t.addView(cur);
        t.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        t.addView(dur);
        panel.addView(t);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        lockBtn = ctl("🔓", v -> { locked = true; showControls(false); toast("Locked — tap 🔒 to unlock"); });
        muteBtn = ctl("🔊", v -> {
            muted = !muted;
            try { if (player != null) player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) { }
            muteBtn.setText(muted ? "🔇" : "🔊");
        });
        TextView back = ctl("⏪", v -> seekBy(-10000));
        playBtn = ctl("⏸", v -> { if (video.isPlaying()) video.pause(); else video.start(); });
        playBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        TextView fwd = ctl("⏩", v -> seekBy(10000));
        final float[] steps = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        speedBtn = ctl("1×", v -> {
            int i = 0;
            for (int k = 0; k < steps.length; k++) if (steps[k] == speed) i = k;
            setSpeed(steps[(i + 1) % steps.length]);
            toast("Speed " + speed + "×");
        });
        speedBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        TextView rot = ctl("⛶", v -> rotate());
        for (TextView x : new TextView[]{lockBtn, muteBtn, back, playBtn, fwd, speedBtn, rot}) r.addView(x, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(r);
        stage.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        unlockBtn = new TextView(this);
        unlockBtn.setText("🔒");
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        unlockBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        unlockBtn.setBackgroundColor(0x99000000);
        unlockBtn.setVisibility(View.GONE);
        unlockBtn.setOnClickListener(v -> { locked = false; showControls(true); });
        stage.addView(unlockBtn, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START));
        if (audio) panel.setVisibility(View.VISIBLE);
        kick();
    }

    private void finishSwipe() {
        // the gesture detector applies the seek on release through this hook
        if (swipeSeekMs != 0 && video != null) {
            video.seekTo(Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + swipeSeekMs)));
            swipeSeekMs = 0;
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) { rotate(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        progress.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private Map<String, String> headerMap(String h) {
        Map<String, String> map = new HashMap<>();
        if (h != null) {
            for (String line : h.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return map;
    }

    private void say(String s) {
        spinner.setVisibility(View.GONE);
        message.setText(s);
        message.setVisibility(View.VISIBLE);
    }

    private void playMedia(String headers, boolean audio) {
        video = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        stage.addView(video, 0, lp);
        attachControls(audio);
        video.setOnPreparedListener(mp -> {
            player = mp;
            spinner.setVisibility(View.GONE);
            mp.setLooping(false);
            try { mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) { }
            if (speed != 1f) setSpeed(speed);
            video.start();
            progress.removeCallbacks(tick);
            progress.post(tick);
            if (audio) say("♪");
        });
        video.setOnCompletionListener(mp -> { if (plUris != null && plIdx < plUris.size() - 1) step(1); });
        video.setOnErrorListener((mp, what, extra) -> {
            if (uri.startsWith("http") && !cachedTry) {
                cachedTry = true;
                cacheAndPlay(headers);
                return true;
            }
            say("This file cannot be played here (the format may not be supported by your phone, or the link expired).\nTap \"Open with…\" or download it first.");
            return true;
        });
        try {
            if (uri.startsWith("http")) video.setVideoURI(Uri.parse(uri), headerMap(headers));
            else video.setVideoURI(Uri.parse(uri));
        } catch (Exception e) {
            say("Cannot start the preview.");
        }
    }

    /** Some servers refuse to stream to the phone's player: fetch the file into the cache with the right headers and play that. */
    private void cacheAndPlay(final String headers) {
        spinner.setVisibility(View.VISIBLE);
        message.setText("Loading preview…");
        message.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                final java.io.File f = new java.io.File(getCacheDir(), "preview.mp4");
                final long chunk = 8L * 1024 * 1024, limit = 80L * 1024 * 1024;
                long pos = 0, total = -1;
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    while (total < 0 || pos < total) {
                        java.net.HttpURLConnection c = Segmented.open(uri, pos, pos + chunk - 1, 8, headers);
                        int code = c.getResponseCode();
                        if (code != 206 && code != 200) throw new Exception("HTTP " + code);
                        String cr = c.getHeaderField("Content-Range");
                        if (cr != null && cr.contains("/")) {
                            try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) { }
                        } else if (code == 200) {
                            total = c.getContentLengthLong();
                        }
                        if (total > limit) { c.disconnect(); runOnUiThread(() -> say("This file is too big to preview before downloading.\nDownload it, then preview it.")); return; }
                        try (InputStream in = c.getInputStream()) {
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) { out.write(b, 0, n); pos += n; }
                        }
                        c.disconnect();
                        if (code == 200) break;
                    }
                }
                runOnUiThread(() -> {
                    message.setVisibility(View.GONE);
                    video.stopPlayback();
                    video.setVideoPath(f.getAbsolutePath());
                });
            } catch (Exception e) {
                runOnUiThread(() -> say("This file cannot be previewed (" + e.getMessage() + ").\nTap \"Open with…\" or download it."));
            }
        }).start();
    }

    private void showImage(final String headers) {
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(iv, 0, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            try {
                final Bitmap bm;
                if (uri.startsWith("http")) {
                    bm = Extractor.bitmap(uri, headerMap(headers).get("Referer"));
                } else {
                    try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        byte[] data = bo.toByteArray();
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 2400);
                        o.inJustDecodeBounds = false;
                        bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    }
                }
                runOnUiThread(() -> {
                    if (bm == null) { say("This picture cannot be shown."); return; }
                    spinner.setVisibility(View.GONE);
                    iv.setImageBitmap(bm);
                });
            } catch (Exception e) {
                runOnUiThread(() -> say("This picture cannot be shown."));
            }
        }).start();
    }

    private void showText(final String headers) {
        final TextView tv = new TextView(this);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        stage.addView(sv, 0, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            try {
                InputStream in;
                if (uri.startsWith("http")) {
                    java.net.HttpURLConnection c = Segmented.open(uri, -1, -1, 8, headers);
                    in = c.getInputStream();
                } else {
                    in = getContentResolver().openInputStream(Uri.parse(uri));
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 60000) bo.write(buf, 0, n);
                in.close();
                final String text = bo.toString("UTF-8");
                runOnUiThread(() -> { spinner.setVisibility(View.GONE); tv.setText(text); });
            } catch (Exception e) {
                runOnUiThread(() -> say("This text cannot be shown."));
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
