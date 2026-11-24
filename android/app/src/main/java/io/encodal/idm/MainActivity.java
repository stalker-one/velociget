package io.encodal.idm;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private EditText url;
    private EditText conns;
    private LinearLayout list;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 600); }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F4F7FB"));

        TextView head = new TextView(this);
        head.setText("IDM By Encodal");
        head.setTextColor(Color.WHITE);
        head.setTextSize(20);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(dp(18), dp(40), dp(18), dp(16));
        head.setBackgroundColor(Color.parseColor("#1D4ED8"));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(14), dp(16), dp(6));
        url = new EditText(this);
        url.setHint("Paste a file link (https://…)");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(url, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(this);
        lbl.setText("Connections");
        row.addView(lbl);
        conns = new EditText(this);
        conns.setText("8");
        conns.setInputType(InputType.TYPE_CLASS_NUMBER);
        conns.setEms(3);
        row.addView(conns);
        Button paste = new Button(this);
        paste.setText("Paste");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) url.setText(firstUrl(d.getItemAt(0).getText().toString()));
        });
        row.addView(paste);
        Button go = new Button(this);
        go.setText("Download");
        go.setOnClickListener(v -> start(url.getText().toString()));
        row.addView(go);
        form.addView(row);
        root.addView(form);

        TextView note = new TextView(this);
        note.setText("Direct files only. Files go to Downloads/IDM By Encodal. You can also share a link to this app from your browser.");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#55677D"));
        note.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), dp(12));
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String u = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) u = firstUrl(i.getStringExtra(Intent.EXTRA_TEXT));
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) u = i.getData().toString();
        if (u != null && !u.isEmpty()) { url.setText(u); start(u); }
    }

    private static String firstUrl(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : s.trim();
    }

    private void start(String u) {
        u = firstUrl(u);
        if (!u.startsWith("http")) { url.setError("Enter a full link starting with http"); return; }
        int n = 8;
        try { n = Math.max(1, Math.min(32, Integer.parseInt(conns.getText().toString().trim()))); } catch (Exception ignored) { }
        Intent s = new Intent(this, DownloadService.class);
        s.putExtra("url", u);
        s.putExtra("connections", n);
        startForegroundService(s);
        url.setText("");
    }

    private static String size(long n) {
        if (n < 0) return "?";
        if (n >= 1L << 30) return String.format("%.2f GB", n / 1073741824.0);
        if (n >= 1L << 20) return String.format("%.1f MB", n / 1048576.0);
        return Math.max(1, n / 1024) + " KB";
    }

    private void refresh() {
        list.removeAllViews();
        for (Segmented.Item i : DownloadService.ITEMS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), Color.parseColor("#DBE3EE"));
            card.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            TextView t = new TextView(this);
            t.setText(i.name.isEmpty() ? i.url : i.name);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            card.addView(t);
            long done = i.done.get();
            TextView s = new TextView(this);
            s.setTextSize(12);
            s.setText(i.status + "  ·  " + size(done) + " / " + size(i.total) + (i.connections > 0 ? "  ·  " + i.connections + " connection" + (i.connections == 1 ? "" : "s") : ""));
            card.addView(s);
            if (!i.finished) {
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                if (i.total > 0) { pb.setMax(1000); pb.setProgress((int) (done * 1000 / i.total)); } else pb.setIndeterminate(true);
                card.addView(pb);
            }
            list.addView(card, lp);
        }
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
