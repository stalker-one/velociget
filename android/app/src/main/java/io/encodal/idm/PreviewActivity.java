package io.encodal.idm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import android.widget.Toast;

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
    private PlayerView video;
    private Ico ccTop, auTop, moreTop;
    private View pipTop;
    private LinearLayout topWrap, strip;
    private HorizontalScrollView stripScroll;
    private TextView hwBtn;
    private boolean loop, shuffle, night, bgPlay = true;
    private int abState, abA, abB, aspect;
    private View nightView;
    private long sleepAt;
    private android.media.audiofx.Equalizer eq;
    private android.media.audiofx.LoudnessEnhancer boost;
    private boolean isImage, slide;
    private boolean webMode, inPip, bgOn, audioOnly, renderSeen;
    private BroadcastReceiver rx;

    private void startBg() {
        if (bgOn) return;
        bgOn = true;
        BgPlayService.target = PreviewActivity.class;
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (BgPlayService.CLOSE.equals(i.getAction())) { finish(); return; }
                if (video != null) { if (video.isPlaying()) video.pause(); else video.start(); }
                BgPlayService.refresh(PreviewActivity.this, title == null ? "Playing" : title, video != null && video.isPlaying());
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BgPlayService.TOGGLE);
        f.addAction(BgPlayService.CLOSE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(rx, f);
        BgPlayService.start(this);
        BgPlayService.refresh(this, title == null ? "Playing" : title, true);
    }
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
                if (abState == 2 && p >= abB) video.seekTo(abA);
                ((Ico) playBtn).setKind(video.isPlaying() ? Ico.PAUSE : Ico.PLAY);
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
        if (uri == null && getIntent().getData() != null) { // "Open with IDM By Encodal" from another app / file manager
            Uri d0 = getIntent().getData();
            uri = d0.toString();
            mime = getIntent().getType();
            String nm = d0.getLastPathSegment();
            try (android.database.Cursor c = getContentResolver().query(d0, new String[]{"_display_name"}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) nm = c.getString(0);
            } catch (Exception ignored) { }
            title = nm;
        }
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
        bar.setPadding(dp(4), dp(4), dp(4), dp(14));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        Ico close = new Ico(this, Ico.BACK);
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(42), dp(40)));
        TextView t = new TextView(this);
        t.setText(title == null || title.isEmpty() ? "Preview" : title);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView = t;
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (plUris != null && plUris.size() > 1) {
            TextView pv = new Ico(this, Ico.PREV);
            pv.setPadding(dp(14), dp(6), dp(14), dp(6));
            pv.setOnClickListener(v -> step(-1));
            bar.addView(pv, new LinearLayout.LayoutParams(dp(42), dp(40)));
            TextView nx = new Ico(this, Ico.NEXT);
            nx.setPadding(dp(14), dp(6), dp(14), dp(6));
            nx.setOnClickListener(v -> step(1));
            bar.addView(nx, new LinearLayout.LayoutParams(dp(42), dp(40)));
        }
        auTop = new Ico(this, Ico.TRACKS);
        auTop.setOnClickListener(v -> audioMenu());
        bar.addView(auTop, new LinearLayout.LayoutParams(dp(42), dp(40)));
        ccTop = new Ico(this, Ico.CC);
        ccTop.setOnClickListener(v -> subMenu());
        bar.addView(ccTop, new LinearLayout.LayoutParams(dp(42), dp(40)));
        hwBtn = new TextView(this);
        hwBtn.setText("HW");
        hwBtn.setTextColor(Color.WHITE);
        hwBtn.setTypeface(Typeface.DEFAULT_BOLD);
        hwBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hwBtn.setGravity(Gravity.CENTER);
        hwBtn.setOnClickListener(v -> {
            if (webMode) { toast("Already using the software (compatibility) player"); return; }
            new android.app.AlertDialog.Builder(this).setTitle("Decoder").setMessage("HW: the phone's hardware decoder (fast, battery friendly).\nSW: software decoder in the built-in browser engine. It plays more formats (VP9, AV1, WebM) but uses more battery.")
                    .setPositiveButton("Use SW", (d, w) -> webFallback(getIntent().getStringExtra("headers"), video == null ? 0 : video.getCurrentPosition() / 1000))
                    .setNegativeButton("Keep HW", null).show();
        });
        pipTop = hwBtn;
        bar.addView(hwBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        moreTop = new Ico(this, Ico.MORE);
        moreTop.setOnClickListener(v -> { stripScroll.setVisibility(stripScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); kick(); });
        bar.addView(moreTop, new LinearLayout.LayoutParams(dp(36), dp(40)));
        topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        topWrap.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        strip = new LinearLayout(this);
        buildStrip();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0x99000000);
        hs.addView(strip);
        hs.setTag("strip");
        topWrap.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        stripScroll = hs;
        hs.setVisibility(View.GONE);

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
        else { imageBar(); say("This file type cannot be previewed here.\nTap \"Open with…\" to use another app."); }
    }


    // ---- audio tracks, subtitles, playlist ----

    private TextView subView;

    private void trackMenu() {
        final java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        items.add("Audio track / language");
        acts.add(this::audioMenu);
        items.add("Subtitles");
        acts.add(this::subMenu);
        if (plUris != null && plUris.size() > 1) {
            items.add("Playlist (" + plUris.size() + " files)");
            acts.add(this::playlistMenu);
        }
        items.add("Playback speed");
        acts.add(() -> {
            final String[] n = {"0.5×", "0.75×", "1×  normal", "1.25×", "1.5×", "2×"};
            final float[] f = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
            new android.app.AlertDialog.Builder(this).setTitle("Speed").setItems(n, (d, i) -> { setSpeed(f[i]); toast("Speed " + f[i] + "×"); }).show();
        });
        new android.app.AlertDialog.Builder(this).setTitle("Player").setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
    }

    private static String langName(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return "";
        try { return new Locale(code).getDisplayLanguage(); } catch (Exception e) { return code; }
    }

    private void audioMenu() {
        if (player == null) return;
        try {
            MediaPlayer.TrackInfo[] ti = player.getTrackInfo();
            final java.util.List<Integer> idx = new java.util.ArrayList<>();
            final java.util.List<String> names = new java.util.ArrayList<>();
            int cur = -1;
            try { cur = player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO); } catch (Exception ignored) { }
            int checked = 0;
            for (int i = 0; i < ti.length; i++) {
                if (ti[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                String l = langName(ti[i].getLanguage());
                if (i == cur) checked = idx.size();
                names.add("Audio " + (idx.size() + 1) + (l.isEmpty() ? "" : " · " + l));
                idx.add(i);
            }
            if (idx.size() < 2) { toast(idx.isEmpty() ? "No audio track list in this file" : "This file has one audio track"); return; }
            new android.app.AlertDialog.Builder(this).setTitle("Audio / language").setSingleChoiceItems(names.toArray(new String[0]), checked, (d, w) -> {
                try {
                    int pos = video.getCurrentPosition();
                    player.selectTrack(idx.get(w));
                    video.seekTo(pos);
                    toast(names.get(w));
                } catch (Exception e) { toast("Could not switch the audio"); }
                d.dismiss();
            }).show();
        } catch (Exception e) {
            toast("Audio tracks are not available");
        }
    }

    private void subMenu() {
        if (player == null) return;
        final java.util.List<Integer> idx = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        names.add("Off");
        idx.add(-1);
        try {
            MediaPlayer.TrackInfo[] ti = player.getTrackInfo();
            for (int i = 0; i < ti.length; i++) {
                int ty = ti[i].getTrackType();
                if (ty != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT && ty != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) continue;
                String l = langName(ti[i].getLanguage());
                names.add("Subtitle " + idx.size() + (l.isEmpty() ? "" : " · " + l));
                idx.add(i);
            }
        } catch (Exception ignored) { }
        names.add("＋ Add subtitle file (.srt / .vtt)…");
        idx.add(-2);
        new android.app.AlertDialog.Builder(this).setTitle("Subtitles").setItems(names.toArray(new String[0]), (d, w) -> {
            int t = idx.get(w);
            if (t == -2) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 77);
            } else if (t == -1) {
                subtitleOff();
            } else {
                subtitleOn(t);
            }
        }).show();
    }

    private void ensureSubView() {
        if (subView != null) return;
        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        subView.setGravity(Gravity.CENTER);
        subView.setShadowLayer(6, 0, 0, Color.BLACK);
        subView.setBackgroundColor(0x66000000);
        subView.setPadding(dp(10), dp(4), dp(10), dp(4));
        subView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(150);
        stage.addView(subView, lp);
        player.setOnTimedTextListener((mp, text) -> runOnUiThread(() -> {
            String t = text == null ? "" : text.getText();
            subView.setText(t == null ? "" : t.trim());
            subView.setVisibility(t == null || t.trim().isEmpty() ? View.GONE : View.VISIBLE);
        }));
    }

    private int subTrack = -1;

    private void subtitleOn(int track) {
        try {
            ensureSubView();
            if (subTrack >= 0) { try { player.deselectTrack(subTrack); } catch (Exception ignored) { } }
            player.selectTrack(track);
            subTrack = track;
            toast("Subtitles on");
        } catch (Exception e) {
            toast("Could not show these subtitles");
        }
    }

    private void subtitleOff() {
        try {
            if (subTrack >= 0) player.deselectTrack(subTrack);
        } catch (Exception ignored) { }
        subTrack = -1;
        if (subView != null) subView.setVisibility(View.GONE);
        toast("Subtitles off");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null || data.getData() == null || player == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            String txt = bo.toString("UTF-8");
            if (txt.startsWith("\uFEFF")) txt = txt.substring(1);
            if (txt.startsWith("WEBVTT")) { // .vtt -> .srt
                txt = txt.replaceFirst("WEBVTT[^\n]*\n", "").replaceAll("(\\d\\d:\\d\\d:\\d\\d)\\.(\\d\\d\\d)", "$1,$2").replaceAll("(?m)^(\\d\\d:\\d\\d)\\.(\\d\\d\\d)", "00:$1,$2");
            }
            java.io.File f = new java.io.File(getCacheDir(), "sub.srt");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(txt.getBytes("UTF-8")); }
            ensureSubView();
            player.addTimedTextSource(f.getAbsolutePath(), MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);
            MediaPlayer.TrackInfo[] ti = player.getTrackInfo();
            int last = -1;
            for (int i = 0; i < ti.length; i++) if (ti[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) last = i;
            if (last >= 0) subtitleOn(last); else toast("Could not read that subtitle file");
        } catch (Exception e) {
            toast("Could not load the subtitle file");
        }
    }

    private void playlistMenu() {
        new android.app.AlertDialog.Builder(this).setTitle("Playlist").setSingleChoiceItems(plNames.toArray(new String[0]), plIdx, (d, w) -> {
            step(w - plIdx);
            d.dismiss();
        }).show();
    }

    /** Next / previous file of the playlist (files opened from the Files screen). */
    private void step(int d) {
        if (plUris == null) return;
        int n = plIdx + d;
        if (shuffle && plUris.size() > 1) { do { n = (int) (Math.random() * plUris.size()); } while (n == plIdx); }
        if (n < 0 || n >= plUris.size()) return;
        String nm = plMimes == null || n >= plMimes.size() ? "" : plMimes.get(n);
        boolean nextVideo = nm.startsWith("video/") || nm.startsWith("audio/");
        if (video == null || webMode || !nextVideo) { // pictures (or a change between picture and video): open the neighbour in a fresh screen
            Intent i = new Intent(getIntent()).putExtra("uri", plUris.get(n)).putExtra("mime", nm).putExtra("title", plNames.get(n)).putExtra("pl_index", n).putExtra("slide", slide);
            startActivity(i);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return;
        }
        plIdx = n;
        uri = plUris.get(n);
        mime = plMimes.get(n);
        title = plNames.get(n);
        titleView.setText(title);
        message.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        video.stopPlayback();
        if (subView != null) { stage.removeView(subView); subView = null; }
        subTrack = -1;
        video.setVideoURI(Uri.parse(uri));
    }


    // ---- MX-style controls: gestures, mute, speed, lock, rotate / full screen ----

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%02d:%02d", m, s % 60);
    }

    private TextView ctl(String t, View.OnClickListener l) {
        int k = "🔓".equals(t) ? Ico.UNLOCK : "🔊".equals(t) ? Ico.VOL : "⏪".equals(t) ? Ico.BACK10 : "⏸".equals(t) ? Ico.PAUSE
                : "⏩".equals(t) ? Ico.FWD10 : "⛶".equals(t) ? Ico.FULL : "cc".equals(t) ? Ico.CC : "list".equals(t) ? Ico.LIST : "prev".equals(t) ? Ico.PREV : "next".equals(t) ? Ico.NEXT : "aspect".equals(t) ? Ico.ASPECT : 0;
        TextView v;
        if (k != 0) {
            v = new Ico(this, k);
            v.setMinimumHeight(dp(44));
        } else {
            v = new TextView(this);
            v.setText(t);
            v.setTextColor(Color.WHITE);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setGravity(Gravity.CENTER);
        }
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setOnClickListener(x -> { l.onClick(x); kick(); });
        return v;
    }


    // ---- tool strip (MX-style) ----

    private final java.util.List<TextView> stripIcons = new java.util.ArrayList<>();

    private void toolItem(int kind, String label, View.OnClickListener l, String tag) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(dp(4), dp(8), dp(4), dp(8));
        Ico ic = new Ico(this, kind);
        ic.setTag(tag);
        it.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        it.addView(t, new LinearLayout.LayoutParams(dp(68), -2));
        it.setOnClickListener(v -> { l.onClick(v); kick(); });
        strip.addView(it, new LinearLayout.LayoutParams(dp(72), -2));
    }

    private void lit(String tag, boolean on) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = ((LinearLayout) strip.getChildAt(i)).getChildAt(0);
            if (tag.equals(c.getTag())) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(on ? 0xFF1D4ED8 : 0x00000000);
                c.setBackground(g);
            }
        }
    }

    private void buildStrip() {
        toolItem(Ico.MOON, "Night mode", v -> {
            night = !night;
            if (nightView == null) {
                nightView = new View(this);
                nightView.setBackgroundColor(0xAA000000);
                nightView.setClickable(false);
                stage.addView(nightView, 1, new FrameLayout.LayoutParams(-1, -1));
            }
            nightView.setVisibility(night ? View.VISIBLE : View.GONE);
            lit("night", night);
        }, "night");
        toolItem(Ico.SHUFFLE, "Shuffle", v -> {
            if (plUris == null || plUris.size() < 2) { toast("Open a folder with several files to shuffle"); return; }
            shuffle = !shuffle;
            lit("shuffle", shuffle);
            toast(shuffle ? "Shuffle on" : "Shuffle off");
        }, "shuffle");
        toolItem(Ico.LOOP, "Loop", v -> { loop = !loop; lit("loop", loop); toast(loop ? "Repeat this video" : "Repeat off"); }, "loop");
        toolItem(Ico.VOL, "Mute", v -> {
            muted = !muted;
            try { if (player != null) player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) { }
            ((Ico) ((LinearLayout) v).getChildAt(0)).setKind(muted ? Ico.MUTE : Ico.VOL);
            lit("mute", muted);
        }, "mute");
        toolItem(Ico.TIMER, "Sleep timer", v -> sleepMenu(), "sleep");
        toolItem(Ico.ABREP, "A - B repeat", v -> {
            if (video == null) return;
            if (abState == 0) { abA = video.getCurrentPosition(); abState = 1; toast("A set — tap again to set B"); }
            else if (abState == 1) { abB = video.getCurrentPosition(); if (abB <= abA + 500) { toast("B must be later than A"); return; } abState = 2; toast("Repeating A – B"); }
            else { abState = 0; toast("A – B repeat off"); }
            lit("ab", abState == 2);
        }, "ab");
        toolItem(Ico.FX, "Volume boost", v -> boostMenu(), "fx");
        toolItem(Ico.EQ, "Equalizer", v -> eqMenu(), "eq");
        toolItem(Ico.SPEED, "Speed", v -> {
            final String[] n = {"0.5×", "0.75×", "1×  normal", "1.25×", "1.5×", "2×"};
            final float[] f = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
            new android.app.AlertDialog.Builder(this).setTitle("Speed").setItems(n, (d, i) -> { setSpeed(f[i]); toast("Speed " + f[i] + "×"); }).show();
        }, "speed");
        toolItem(Ico.PIP, "Mini player", v -> {
            if (Build.VERSION.SDK_INT >= 26 && !webMode) { try { enterPip(); } catch (Exception e) { toast("Allow picture-in-picture for this app in Android settings"); } }
        }, "pip");
        toolItem(Ico.CAMERA, "Screenshot", v -> screenshot(), "shot");
        toolItem(Ico.FULL, "Rotate", v -> rotate(), "rot");
        toolItem(Ico.HEADSET, "Background play", v -> {
            bgPlay = !bgPlay;
            Prefs.putBool(this, "bgplay", bgPlay);
            lit("bg", bgPlay);
            toast(bgPlay ? "Keeps playing when you leave the app" : "Pauses when you leave the app");
        }, "bg");
        if (plUris != null && plUris.size() > 1) toolItem(Ico.LIST, "Playlist", v -> playlistMenu(), "pl");
        toolItem(Ico.SHARE, "Open with", v -> {
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime.isEmpty() ? "*/*" : mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"));
            } catch (Exception e) { toast("No app can open this file"); }
        }, "ow");
        bgPlay = Prefs.getBool(this, "bgplay", true);
        lit("bg", bgPlay);
    }

    private void sleepMenu() {
        final String[] n = {"Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "After this video"};
        new android.app.AlertDialog.Builder(this).setTitle("Sleep timer").setItems(n, (d, i) -> {
            progress.removeCallbacks(sleepRun);
            sleepAt = 0;
            lit("sleep", i != 0);
            if (i == 0) { toast("Sleep timer off"); return; }
            if (i == 5) { sleepAfter = true; toast("Will stop after this video"); return; }
            sleepAfter = false;
            sleepAt = System.currentTimeMillis() + i * 15L * 60000;
            progress.postDelayed(sleepRun, i * 15L * 60000);
            toast("Stops in " + n[i]);
        }).show();
    }

    private boolean sleepAfter;
    private final Runnable sleepRun = () -> { if (video != null) video.pause(); toast("Sleep timer: stopped"); finish(); };

    private void boostMenu() {
        if (player == null) return;
        final String[] n = {"Off", "+3 dB", "+6 dB", "+9 dB"};
        new android.app.AlertDialog.Builder(this).setTitle("Volume boost").setItems(n, (d, i) -> {
            try {
                if (boost == null) boost = new android.media.audiofx.LoudnessEnhancer(player.getAudioSessionId());
                boost.setTargetGain(i * 300);
                boost.setEnabled(i > 0);
                lit("fx", i > 0);
                toast("Volume boost " + n[i]);
            } catch (Exception e) { toast("Volume boost is not available on this phone"); }
        }).show();
    }

    private void eqMenu() {
        if (player == null) return;
        try {
            if (eq == null) eq = new android.media.audiofx.Equalizer(0, player.getAudioSessionId());
            final short np = eq.getNumberOfPresets();
            final String[] n = new String[np + 1];
            n[0] = "Off";
            for (short i = 0; i < np; i++) n[i + 1] = eq.getPresetName(i);
            new android.app.AlertDialog.Builder(this).setTitle("Equalizer").setItems(n, (d, i) -> {
                try {
                    if (i == 0) eq.setEnabled(false); else { eq.setEnabled(true); eq.usePreset((short) (i - 1)); }
                    lit("eq", i != 0);
                    toast("Equalizer: " + n[i]);
                } catch (Exception e) { toast("Equalizer error"); }
            }).show();
        } catch (Exception e) {
            toast("Equalizer is not available on this phone");
        }
    }

    private void screenshot() {
        if (video == null || webMode || Build.VERSION.SDK_INT < 26) { toast("Screenshot works with the normal player"); return; }
        try {
            final Bitmap bm = Bitmap.createBitmap(Math.max(2, video.getWidth()), Math.max(2, video.getHeight()), Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(video, bm, res -> {
                if (res != android.view.PixelCopy.SUCCESS) { toast("Could not capture this frame"); return; }
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "IDM_" + System.currentTimeMillis() + ".jpg");
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IDM By Encodal");
                    Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { bm.compress(Bitmap.CompressFormat.JPEG, 95, o); }
                    toast("Saved to Pictures/IDM By Encodal");
                } catch (Exception e) { toast("Could not save the screenshot"); }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            toast("Could not capture this frame");
        }
    }

    /** Rounded label button with a line icon (used for the player shortcuts). */
    private TextView pill(int kind, String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(" " + label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(20));
        t.setBackground(g);
        final Ico ic = new Ico(this, kind);
        t.setCompoundDrawablePadding(dp(2));
        final android.graphics.drawable.Drawable d = new android.graphics.drawable.Drawable() {
            @Override public void draw(Canvas c) {
                c.save();
                c.translate(getBounds().left, getBounds().top);
                ic.measure(View.MeasureSpec.makeMeasureSpec(getBounds().width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getBounds().height(), View.MeasureSpec.EXACTLY));
                ic.layout(0, 0, getBounds().width(), getBounds().height());
                ic.draw(c);
                c.restore();
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        d.setBounds(0, 0, dp(18), dp(18));
        t.setCompoundDrawables(d, null, null, null);
        t.setOnClickListener(x -> { l.onClick(x); kick(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
        lp.setMargins(dp(4), dp(4), dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void kick() {
        progress.removeCallbacks(hide);
        if (video != null && video.isPlaying()) progress.postDelayed(hide, 3500);
    }

    private void showControls(boolean on) {
        shown = on;
        if (locked) { unlockBtn.setVisibility(on ? View.VISIBLE : View.GONE); panel.setVisibility(View.GONE); topWrap.setVisibility(View.GONE); return; }
        panel.setVisibility(on ? View.VISIBLE : View.GONE);
        topWrap.setVisibility(on ? View.VISIBLE : View.GONE);
        unlockBtn.setVisibility(View.GONE);
        if (on) kick();
    }

    private void setSpeed(float sp) {
        speed = sp;
        try {
            if (player != null) player.setPlaybackParams(player.getPlaybackParams().setSpeed(sp));
        } catch (Exception ignored) { }
        if (speedBtn != null) speedBtn.setText(" " + (sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "×");
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
                : 0);
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
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        panel.setPadding(dp(12), dp(24), dp(12), dp(8));
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
        lockBtn = ctl("🔓", v -> { locked = true; showControls(false); toast("Locked — tap the lock to unlock"); });
        TextView prevB = ctl("prev", v -> step(-1));
        playBtn = ctl("⏸", v -> { if (video.isPlaying()) video.pause(); else video.start(); });
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1D4ED8, 0xFF3B82F6});
        pg.setShape(GradientDrawable.OVAL);
        playBtn.setBackground(pg);
        TextView nextB = ctl("next", v -> step(1));
        TextView asp = ctl("aspect", v -> {
            aspect = (aspect + 1) % 5;
            video.setMode(aspect);
            toast(new String[]{"Fit", "Fill (crop)", "Stretch", "16:9", "4:3"}[aspect]);
        });
        boolean multi = plUris != null && plUris.size() > 1;
        prevB.setAlpha(multi ? 1f : 0.35f);
        nextB.setAlpha(multi ? 1f : 0.35f);
        for (TextView x : new TextView[]{lockBtn, prevB, playBtn, nextB, asp}) {
            int sz = x == playBtn ? 54 : 44;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(sz), 1);
            if (x == playBtn) lp.setMargins(dp(6), 0, dp(6), 0);
            x.setPadding(dp(8), dp(8), dp(8), dp(8));
            r.addView(x, lp);
        }
        speedBtn = new TextView(this);
        muteBtn = new Ico(this, Ico.VOL);
        panel.addView(r);
        stage.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        unlockBtn = new Ico(this, Ico.LOCK);
        unlockBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        unlockBtn.setBackgroundColor(0x99000000);
        unlockBtn.setVisibility(View.GONE);
        unlockBtn.setOnClickListener(v -> { locked = false; showControls(true); });
        stage.addView(unlockBtn, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER_VERTICAL | Gravity.START));
        stage.addView(topWrap, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
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
        if (video != null) video.stopPlayback();
        if (webView != null) { try { webView.destroy(); } catch (Exception ignored) { } }
        if (bgOn) { try { unregisterReceiver(rx); } catch (Exception ignored) { } BgPlayService.stop(this); }
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
        audioOnly = audio;
        video = new PlayerView(this);
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
            startBg();
            renderSeen = false;
            if (!audio) progress.postDelayed(() -> {
                // picture never appeared (4K / HEVC / AV1 the phone cannot draw): switch to the compatibility player at the same second
                if (!renderSeen && !webMode && video != null && video.isPlaying() && mp.getVideoWidth() > 0) webFallback(headers, video.getCurrentPosition() / 1000);
            }, 3500);
            progress.removeCallbacks(tick);
            progress.post(tick);
            if (audio) say("♪");
        });
        video.setOnCompletionListener(mp -> {
            if (loop) { video.seekTo(0); video.start(); return; }
            if (sleepAfter) { finish(); return; }
            if (plUris != null && plUris.size() > 1 && (shuffle || plIdx < plUris.size() - 1)) step(1);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            if (uri.startsWith("http") && !cachedTry) {
                cachedTry = true;
                cacheAndPlay(headers);
                return true;
            }
            webFallback(headers);
            return true;
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == 3) renderSeen = true; // MEDIA_INFO_VIDEO_RENDERING_START
            // 860 / 862: the phone cannot decode the picture / the sound of this file (VP9, AV1, …) → use the built-in browser engine
            if ((what == 860 || what == 862) && !webMode) { webFallback(headers); return true; }
            return false;
        });
        try {
            if (uri.startsWith("http")) video.setVideoURI(Uri.parse(uri), headerMap(headers));
            else video.setVideoURI(Uri.parse(uri));
        } catch (Exception e) {
            say("Cannot start the preview.");
        }
    }

    /** Formats the phone's own player cannot decode (VP9 / AV1 .webm, some .mkv, Opus): the web engine plays them in software. */
    private void webFallback(final String headers) { webFallback(headers, 0); }

    private void webFallback(final String headers, int startSec) {
        if (webMode) return;
        webMode = true;
        try { video.stopPlayback(); } catch (Exception ignored) { }
        progress.removeCallbacks(tick);
        spinner.setVisibility(View.GONE);
        final android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.setBackgroundColor(Color.BLACK);
        android.webkit.WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        wv.setWebChromeClient(new android.webkit.WebChromeClient());
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView v, String u) {
                v.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.controls=true;v.autoplay=true;v.play&&v.play().catch(function(){});v.style.background='#000'}})()", null);
            }
        });
        stage.addView(wv, new FrameLayout.LayoutParams(-1, -1));
        Toast.makeText(this, "Compatibility player (software decoding)", Toast.LENGTH_SHORT).show();
        if (uri.startsWith("http")) wv.loadUrl(uri, headerMap(headers)); else wv.loadUrl(uri);
        if (startSec > 0) wv.postDelayed(() -> wv.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.currentTime=" + startSec + "}})()", null), 1500);
        webView = wv;
    }

    private android.webkit.WebView webView;

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

    /** Controls for pictures / text: just the top bar (title, counter, slideshow, share). */
    private void imageBar() {
        isImage = true;
        ccTop.setVisibility(View.GONE);
        auTop.setVisibility(View.GONE);
        pipTop.setVisibility(View.GONE);
        moreTop.setVisibility(View.GONE);
        if (plUris != null && plUris.size() > 1) {
            titleView.setText((plIdx + 1) + "/" + plUris.size() + "  " + (title == null ? "" : title));
            final Ico sl = new Ico(this, Ico.SLIDE);
            sl.color(slide ? 0xFF60A5FA : 0xFFFFFFFF);
            sl.setOnClickListener(v -> {
                slide = !slide;
                sl.color(slide ? 0xFF60A5FA : 0xFFFFFFFF);
                Toast.makeText(this, slide ? "Slideshow on — every 3 seconds" : "Slideshow off", Toast.LENGTH_SHORT).show();
                progress.removeCallbacks(slideTick);
                if (slide) progress.postDelayed(slideTick, 3000);
            });
            bar.addView(sl, bar.getChildCount() - 1, new LinearLayout.LayoutParams(dp(42), dp(40)));
            if (getIntent().getBooleanExtra("slide", false)) { slide = true; sl.color(0xFF60A5FA); progress.postDelayed(slideTick, 3000); }
        }
        stage.addView(topWrap, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    private final Runnable slideTick = new Runnable() {
        @Override public void run() {
            if (!slide) return;
            if (plUris != null && plIdx < plUris.size() - 1) step(1); else slide = false;
        }
    };

    private void showImage(final String headers) {
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(iv, 0, new FrameLayout.LayoutParams(-1, -1));
        imageBar();
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { topWrap.setVisibility(topWrap.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.2f && Math.abs(vx) > 700) { step(vx < 0 ? 1 : -1); return true; }
                return false;
            }
        });
        iv.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
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
        imageBar();
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
        // keep playing when the app goes to the background / screen turns off (sound continues; picture returns when you come back)

        if (webMode && webView != null) { webView.onResume(); }
    }

    @Override
    protected void onUserLeaveHint() {
        if (video != null && video.isPlaying() && !audioOnly && Build.VERSION.SDK_INT >= 26 && !webMode && Prefs.getBool(this, "autopip", true)) {
            try { enterPip(); } catch (Exception ignored) { }
        }
    }

    private void enterPip() {
        int w = 16, h = 9;
        if (player != null && player.getVideoWidth() > 0 && player.getVideoHeight() > 0) { w = player.getVideoWidth(); h = player.getVideoHeight(); }
        android.util.Rational r = new android.util.Rational(Math.max(1, Math.min(w, h * 2)), Math.max(1, Math.min(h, w * 2)));
        enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().setAspectRatio(r).build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, android.content.res.Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        inPip = in;
        if (topWrap != null) topWrap.setVisibility(in ? View.GONE : View.VISIBLE);
        if (panel != null && in) panel.setVisibility(View.GONE);
        if (!in) showControls(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        BgPlayService.target = PreviewActivity.class;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!Prefs.getBool(this, "bgplay", true) && !isInPictureInPictureMode() && video != null && video.isPlaying()) video.pause();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
