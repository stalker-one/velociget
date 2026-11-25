package io.encodal.idm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The whole screen: link box, connection chips, and one live card per download. */
public class MainActivity extends Activity {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    private int BG, CARD, TXT, SUB, LINE, FIELD;
    private static final int PRIMARY = 0xFF2563EB, GOOD = 0xFF16A34A, BAD = 0xFFDC2626, WARN = 0xFFD97706;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Integer, Card> cards = new HashMap<>();
    private EditText urlField;
    private TextView totalSpeed, summary, clear, empty, bannerText;
    private LinearLayout list, banner;
    private TextView[] chips;
    private final int[] chipValues = {4, 8, 16, 32};
    private int conns = 8;
    private String lastClip = "", bannerUrl;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 500);
        }
    };

    private final class Card {
        final Item it;
        final LinearLayout root = new LinearLayout(MainActivity.this);
        final LinearLayout actions = new LinearLayout(MainActivity.this);
        final TextView badge = new TextView(MainActivity.this);
        final TextView name = new TextView(MainActivity.this);
        final TextView status = new TextView(MainActivity.this);
        final TextView meta = new TextView(MainActivity.this);
        final ProgressBar bar;
        int shownState = -99;
        boolean shownStop;
        boolean indeterminate;

        Card(Item it) {
            this.it = it;
            bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        BG = dark ? 0xFF0B1220 : 0xFFF1F5F9;
        CARD = dark ? 0xFF131C2E : 0xFFFFFFFF;
        TXT = dark ? 0xFFE5EAF3 : 0xFF0F172A;
        SUB = dark ? 0xFF93A0B8 : 0xFF64748B;
        LINE = dark ? 0xFF22304A : 0xFFE2E8F0;
        FIELD = dark ? 0xFF0E1626 : 0xFFF8FAFC;
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(BG);

        Store.load(this, DownloadService.ITEMS);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        setContentView(build());
        handleIntent(getIntent());
    }

    private boolean dark;

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); Store.save(this, DownloadService.ITEMS); }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) return;
        String u = clipboardUrl();
        if (u != null && !u.equals(lastClip) && !u.equals(urlField.getText().toString().trim())) {
            bannerUrl = u;
            bannerText.setText(shorten(u, 56));
            banner.setVisibility(View.VISIBLE);
        }
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String u = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) u = firstUrl(t.toString());
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            u = i.getData().toString();
        }
        if (u != null && u.startsWith("http")) {
            urlField.setText(u);
            urlField.setSelection(u.length());
            Toast.makeText(this, "Link ready — tap Start download", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(14), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1D4ED8, 0xFF3B82F6});
        head.setBackground(g);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = text("IDM By Encodal", 20, Color.WHITE, true);
        TextView st = text("Fast segmented downloads", 12, 0xCCFFFFFF, false);
        titles.addView(t);
        titles.addView(st);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        totalSpeed = text("0 B/s", 15, Color.WHITE, true);
        totalSpeed.setPadding(dp(12), dp(6), dp(12), dp(6));
        totalSpeed.setBackground(rect(0x33FFFFFF, 20, 0));
        head.addView(totalSpeed);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // link card
        LinearLayout lc = card();
        lc.addView(text("New download", 13, SUB, true));
        urlField = new EditText(this);
        urlField.setHint("Paste a direct download link");
        urlField.setHintTextColor(SUB);
        urlField.setTextColor(TXT);
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSingleLine(true);
        urlField.setBackground(rect(FIELD, 10, LINE));
        urlField.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(8);
        lc.addView(urlField, fp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(pill("Paste", PRIMARY, false, v -> {
            String u = clipboardUrl();
            if (u == null) { toast("No link in the clipboard"); return; }
            urlField.setText(u);
            urlField.setSelection(u.length());
        }));
        row.addView(pill("Clear", SUB, false, v -> urlField.setText("")));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        lc.addView(row, rp);

        TextView cl = text("Connections per file", 12, SUB, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(12);
        lc.addView(cl, cp);
        LinearLayout cr = new LinearLayout(this);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        chips = new TextView[chipValues.length];
        for (int i = 0; i < chipValues.length; i++) {
            final int val = chipValues[i];
            TextView c = text(String.valueOf(val), 14, TXT, true);
            c.setGravity(Gravity.CENTER);
            c.setOnClickListener(v -> { conns = val; styleChips(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.rightMargin = i < chipValues.length - 1 ? dp(8) : 0;
            cr.addView(c, lp);
            chips[i] = c;
        }
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.topMargin = dp(6);
        lc.addView(cr, crp);
        styleChips();

        TextView go = text("Start download", 15, Color.WHITE, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(rect(PRIMARY, 12, 0));
        go.setOnClickListener(v -> start(urlField.getText().toString().trim()));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(46));
        gp.topMargin = dp(14);
        lc.addView(go, gp);
        body.addView(lc);

        // clipboard banner
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(14), dp(10), dp(8), dp(10));
        banner.setBackground(rect(dark ? 0xFF14284D : 0xFFEAF2FF, 12, dark ? 0xFF1E3A6B : 0xFFBFD7FF));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.addView(text("Link copied", 12, PRIMARY, true));
        bannerText = text("", 12, TXT, false);
        bannerText.setSingleLine(true);
        bt.addView(bannerText);
        banner.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(pill("Use", PRIMARY, true, v -> {
            if (bannerUrl != null) { urlField.setText(bannerUrl); urlField.setSelection(bannerUrl.length()); lastClip = bannerUrl; }
            banner.setVisibility(View.GONE);
        }));
        banner.addView(pill("✕", SUB, false, v -> { if (bannerUrl != null) lastClip = bannerUrl; banner.setVisibility(View.GONE); }));
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(10);
        body.addView(banner, bp);

        // summary row
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        summary = text("Downloads", 14, TXT, true);
        sr.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        clear = text("Clear finished", 13, PRIMARY, true);
        clear.setPadding(dp(8), dp(6), dp(8), dp(6));
        clear.setOnClickListener(v -> clearFinished());
        sr.addView(clear);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(18);
        srp.bottomMargin = dp(4);
        body.addView(sr, srp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, new LinearLayout.LayoutParams(-1, -2));

        empty = text("No downloads yet\nPaste a link above, or share one from your browser.", 13, SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(36), dp(16), dp(36));
        body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void styleChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean on = chipValues[i] == conns;
            chips[i].setBackground(rect(on ? PRIMARY : FIELD, 10, on ? PRIMARY : LINE));
            chips[i].setTextColor(on ? Color.WHITE : TXT);
        }
    }

    private Card makeCard(Item it) {
        Card c = new Card(it);
        c.root.setOrientation(LinearLayout.VERTICAL);
        c.root.setPadding(dp(14), dp(14), dp(14), dp(10));
        c.root.setBackground(rect(CARD, 14, LINE));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        c.badge.setGravity(Gravity.CENTER);
        c.badge.setTextColor(Color.WHITE);
        c.badge.setTypeface(Typeface.DEFAULT_BOLD);
        c.badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        top.addView(c.badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, 0, 0);
        c.name.setTextColor(TXT);
        c.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.name.setTypeface(Typeface.DEFAULT_BOLD);
        c.name.setMaxLines(2);
        c.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        c.name.setOnLongClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link", it.url));
            lastClip = it.url;
            toast("Link copied");
            return true;
        });
        c.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tx.addView(c.name);
        tx.addView(c.status);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        c.root.addView(top);

        c.bar.setMax(1000);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(6));
        bp.topMargin = dp(12);
        c.root.addView(c.bar, bp);

        c.meta.setTextColor(SUB);
        c.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(8);
        c.root.addView(c.meta, mp);

        c.actions.setOrientation(LinearLayout.HORIZONTAL);
        c.actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(4);
        c.root.addView(c.actions, ap);
        return c;
    }

    // ---------------------------------------------------------------- live refresh

    private void refresh() {
        long now = SystemClock.elapsedRealtime();
        java.util.List<Item> items = DownloadService.ITEMS;

        for (Iterator<Map.Entry<Integer, Card>> ci = cards.entrySet().iterator(); ci.hasNext(); ) {
            Map.Entry<Integer, Card> e = ci.next();
            if (!items.contains(e.getValue().it)) {
                list.removeView(e.getValue().root);
                ci.remove();
            }
        }

        double total = 0;
        int active = 0, done = 0, pos = 0;
        for (Item it : items) {
            Card c = cards.get(it.id);
            if (c == null) {
                c = makeCard(it);
                cards.put(it.id, c);
            }
            if (list.getChildAt(pos) != c.root) {
                if (c.root.getParent() != null) ((ViewGroup) c.root.getParent()).removeView(c.root);
                list.addView(c.root, pos, margins());
            }
            pos++;
            bind(c, now);
            if (it.state == Item.DOWNLOADING) { total += it.speed; active++; }
            else if (it.state == Item.QUEUED) active++;
            if (it.state == Item.DONE) done++;
        }

        totalSpeed.setText(size((long) total) + "/s");
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        summary.setText(items.isEmpty() ? "Downloads" : "Downloads · " + active + " active · " + done + " done");
        clear.setVisibility(done > 0 ? View.VISIBLE : View.GONE);
    }

    private void bind(Card c, long now) {
        Item it = c.it;
        long got = it.done();
        long tot = it.total;

        if (it.state == Item.DOWNLOADING) {
            if (it.lastTime == 0) { it.lastTime = now; it.lastDone = got; }
            else if (now - it.lastTime >= 500) {
                double inst = (got - it.lastDone) * 1000.0 / (now - it.lastTime);
                it.speed = it.speed == 0 ? inst : it.speed * 0.6 + inst * 0.4;
                it.lastDone = got;
                it.lastTime = now;
            }
        } else {
            it.speed = 0;
            it.lastTime = 0;
        }

        String nm = it.name.isEmpty() ? shorten(it.url, 60) : it.name;
        if (!nm.contentEquals(c.name.getText())) {
            c.name.setText(nm);
            String[] b = badge(it.name.isEmpty() ? it.url : it.name, it.mime);
            c.badge.setText(b[0]);
            c.badge.setBackground(rect(Color.parseColor(b[1]), 12, 0));
        }

        int pct = tot > 0 ? (int) Math.min(1000, got * 1000 / tot) : 0;
        String sizes = tot > 0 ? size(got) + " of " + size(tot) : size(got);
        String stat, meta = "";
        int color = SUB, tint = PRIMARY;
        switch (it.state) {
            case Item.QUEUED: stat = "Waiting…"; break;
            case Item.DOWNLOADING:
                if (it.stop) { stat = "Pausing…"; color = WARN; tint = WARN; }
                else { stat = tot > 0 ? (pct / 10) + "% · " + sizes : "Downloading · " + sizes; color = PRIMARY; }
                if (it.speed > 1) {
                    meta = size((long) it.speed) + "/s";
                    if (tot > 0) meta += " · " + eta((long) ((tot - got) / it.speed)) + " left";
                    meta += " · " + it.connections + (it.connections == 1 ? " connection" : " connections");
                } else meta = "Connecting…";
                break;
            case Item.PAUSED: stat = "Paused · " + (tot > 0 ? (pct / 10) + "% · " : "") + sizes; color = WARN; tint = WARN; break;
            case Item.DONE: stat = "Complete · " + size(Math.max(tot, got)); color = GOOD; tint = GOOD; pct = 1000; meta = "Saved in Downloads/IDM By Encodal"; break;
            case Item.ERROR: stat = it.error.isEmpty() ? "Failed" : it.error; color = BAD; tint = BAD; break;
            default: stat = ""; break;
        }
        c.status.setText(stat);
        c.status.setTextColor(color);
        c.meta.setText(meta);
        c.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        boolean indet = it.state == Item.DOWNLOADING && tot <= 0 && !it.stop;
        if (indet != c.indeterminate) { c.bar.setIndeterminate(indet); c.indeterminate = indet; }
        c.bar.setProgressTintList(ColorStateList.valueOf(tint));
        c.bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0xFF22304A : 0xFFE2E8F0));
        if (!indet) c.bar.setProgress(pct);

        if (c.shownState != it.state || c.shownStop != it.stop) {
            c.shownState = it.state;
            c.shownStop = it.stop;
            c.actions.removeAllViews();
            switch (it.state) {
                case Item.DOWNLOADING:
                    if (!it.stop) { c.actions.addView(pill("Pause", WARN, false, v -> it.stop = true)); c.actions.addView(pill("Cancel", BAD, false, v -> cancel(it))); }
                    break;
                case Item.QUEUED: c.actions.addView(pill("Cancel", BAD, false, v -> cancel(it))); break;
                case Item.PAUSED:
                    c.actions.addView(pill("Resume", PRIMARY, true, v -> resume(it)));
                    c.actions.addView(pill("Cancel", BAD, false, v -> cancel(it)));
                    break;
                case Item.ERROR:
                    c.actions.addView(pill("Retry", PRIMARY, true, v -> resume(it)));
                    c.actions.addView(pill("Remove", BAD, false, v -> cancel(it)));
                    break;
                case Item.DONE:
                    c.actions.addView(pill("Open", GOOD, true, v -> openFile(it)));
                    c.actions.addView(pill("Delete", BAD, false, v -> confirmDelete(it)));
                    break;
                default: break;
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void start(String raw) {
        String u = firstUrl(raw);
        if (u == null) { toast("Enter a valid http or https link"); return; }
        Intent s = new Intent(this, DownloadService.class).setAction(DownloadService.ADD)
                .putExtra("url", u).putExtra("connections", conns);
        startForegroundService(s);
        urlField.setText("");
        lastClip = u;
        banner.setVisibility(View.GONE);
        toast("Download started");
    }

    private void resume(Item it) {
        if (it.state == Item.ERROR) it.error = "";
        startForegroundService(new Intent(this, DownloadService.class).setAction(DownloadService.RESUME).putExtra("id", it.id));
    }

    /** Cancel a running download, or drop a paused / failed one together with its partial file. */
    private void cancel(Item it) {
        if (it.active()) {
            it.cancel = true;
            it.stop = true;
            return;
        }
        removeFile(it);
        DownloadService.ITEMS.remove(it);
        Store.save(this, DownloadService.ITEMS);
        refresh();
    }

    private void removeFile(Item it) {
        if (it.uri == null || it.uri.isEmpty()) return;
        try {
            ContentResolver cr = getContentResolver();
            cr.delete(Uri.parse(it.uri), null, null);
        } catch (Exception ignored) { }
    }

    private void confirmDelete(final Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete download")
                .setMessage(it.name)
                .setPositiveButton("Delete file", (d, w) -> {
                    removeFile(it);
                    DownloadService.ITEMS.remove(it);
                    Store.save(this, DownloadService.ITEMS);
                    refresh();
                })
                .setNeutralButton("Remove from list", (d, w) -> {
                    DownloadService.ITEMS.remove(it);
                    Store.save(this, DownloadService.ITEMS);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearFinished() {
        for (Item it : DownloadService.ITEMS) if (it.state == Item.DONE) DownloadService.ITEMS.remove(it);
        Store.save(this, DownloadService.ITEMS);
        refresh();
    }

    private void openFile(Item it) {
        try {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it.uri), it.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(v);
        } catch (ActivityNotFoundException e) {
            toast("No app can open this file");
        } catch (Exception e) {
            toast("The file is no longer in Downloads");
        }
    }

    // ---------------------------------------------------------------- helpers

    private String clipboardUrl() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            return t == null ? null : firstUrl(t.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstUrl(String s) {
        if (s == null) return null;
        Matcher m = URL.matcher(s);
        return m.find() ? m.group() : null;
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private static String eta(long s) {
        if (s < 0) s = 0;
        if (s >= 3600) return (s / 3600) + "h " + String.format(Locale.US, "%02d", (s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + String.format(Locale.US, "%02d", s % 60) + "s";
        return s + "s";
    }

    /** {label, color} for the little file-type tile. */
    private static String[] badge(String name, String mime) {
        String ext = "";
        String n = name;
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) ext = n.substring(dot + 1).toLowerCase(Locale.US);
        if (mime.startsWith("video/") || ext.matches("mp4|mkv|webm|mov|avi|m4v|ts")) return new String[]{"VIDEO", "#DC2626"};
        if (mime.startsWith("audio/") || ext.matches("mp3|m4a|aac|flac|wav|ogg|opus")) return new String[]{"AUDIO", "#9333EA"};
        if (mime.startsWith("image/") || ext.matches("jpg|jpeg|png|gif|webp|svg")) return new String[]{"IMAGE", "#0D9488"};
        if (ext.matches("zip|rar|7z|tar|gz|xz|bz2")) return new String[]{"ZIP", "#D97706"};
        if (ext.equals("apk")) return new String[]{"APK", "#16A34A"};
        if (ext.equals("pdf")) return new String[]{"PDF", "#E11D48"};
        if (ext.matches("exe|msi|dmg|iso")) return new String[]{ext.toUpperCase(Locale.US), "#475569"};
        if (ext.matches("doc|docx|xls|xlsx|ppt|pptx|txt")) return new String[]{"DOC", "#2563EB"};
        return new String[]{ext.isEmpty() ? "FILE" : ext.substring(0, Math.min(4, ext.length())).toUpperCase(Locale.US), "#64748B"};
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(rect(CARD, 14, LINE));
        return c;
    }

    private LinearLayout.LayoutParams margins() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        return p;
    }

    private TextView pill(String label, int color, boolean filled, View.OnClickListener l) {
        TextView t = text(label, 13, filled ? Color.WHITE : color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (filled) t.setBackground(rect(color, 18, 0));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
