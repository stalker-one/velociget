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
import android.widget.MediaController;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        uri = getIntent().getStringExtra("uri");
        mime = getIntent().getStringExtra("mime");
        title = getIntent().getStringExtra("title");
        final String headers = getIntent().getStringExtra("headers");
        if (uri == null) { finish(); return; }
        if (mime == null) mime = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
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
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
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
        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setOnPreparedListener(mp -> {
            spinner.setVisibility(View.GONE);
            mp.setLooping(false);
            video.start();
            if (audio) say("♪");
        });
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
