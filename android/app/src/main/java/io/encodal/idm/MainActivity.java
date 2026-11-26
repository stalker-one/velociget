package io.encodal.idm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.widget.ImageView;
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
    private EditText fRef, fCookie, fHeaders, fUa, fUser, fPass;
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
        themeSeen = Prefs.theme(this);
        dark = themeSeen == 2 || (themeSeen == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        conns = Prefs.conns(this);
        Prefs.refresh(this);
        BG = dark ? 0xFF0B1220 : 0xFFF1F5F9;
        CARD = dark ? 0xFF131C2E : 0xFFFFFFFF;
        TXT = dark ? 0xFFE5EAF3 : 0xFF0F172A;
        SUB = dark ? 0xFF93A0B8 : 0xFF64748B;
        LINE = dark ? 0xFF22304A : 0xFFE2E8F0;
        FIELD = dark ? 0xFF0E1626 : 0xFFF8FAFC;
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(BG);

        Store.load(this, DownloadService.ITEMS);
        PyEngine.warm(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        setContentView(build());
        handleIntent(getIntent());
    }

    private boolean dark;
    private int themeSeen;

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Prefs.theme(this) != themeSeen) { recreate(); return; }
        h.post(tick);
    }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); Store.save(this, DownloadService.ITEMS); }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || !Prefs.getBool(this, "clip", true)) return;
        String u = clipboardUrl();
        if (u != null && !u.equals(lastClip) && !u.equals(urlField.getText().toString().trim())) {
            bannerUrl = u;
            bannerText.setText(shorten(u, 56));
            Resolver.prefetch(this, u);
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
        TextView gear = text("⚙", 22, Color.WHITE, false);
        gear.setPadding(dp(8), dp(2), dp(14), dp(2));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        head.addView(gear);
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
        urlField.addTextChangedListener(new android.text.TextWatcher() {
            private final Runnable fire = () -> {
                String u = firstUrl(urlField.getText().toString());
                if (u != null) Resolver.prefetch(MainActivity.this, u);
            };
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                h.removeCallbacks(fire);
                h.postDelayed(fire, 350);
            }
        });
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
        row.addView(pill("Link details", PRIMARY, false, v -> {
            String u = firstUrl(urlField.getText().toString());
            if (u == null) toast("Paste a link first"); else openDetails(u, false);
        }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        lc.addView(row, rp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        fRef = field("Referer (page address)", false);
        fCookie = field("Cookie", false);
        fUa = field("User agent", false);
        fHeaders = field("Custom headers, one per line (Name: value)", true);
        fUser = field("User name", false);
        fPass = field("Password", false);
        fPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        adv.addView(fRef); adv.addView(fCookie); adv.addView(fUa); adv.addView(fHeaders); adv.addView(fUser); adv.addView(fPass);
        row2.addView(pill("Batch / pattern", PRIMARY, false, v -> showBatch()));
        row2.addView(pill("Advanced ▾", SUB, false, v -> adv.setVisibility(adv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        lc.addView(row2, new LinearLayout.LayoutParams(-1, -2));
        lc.addView(adv, new LinearLayout.LayoutParams(-1, -2));

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

        totalSpeed.setText(sizeText((long) total) + "/s");
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
        String sizes = tot > 0 ? sizeText(got) + " of " + sizeText(tot) : sizeText(got);
        String stat, meta = "";
        int color = SUB, tint = PRIMARY;
        switch (it.state) {
            case Item.QUEUED: stat = "Waiting…"; break;
            case Item.DOWNLOADING:
                if (!it.phase.isEmpty()) { stat = it.phase; color = PRIMARY; }
                else if (it.stop) { stat = "Pausing…"; color = WARN; tint = WARN; }
                else { stat = tot > 0 ? (pct / 10) + "% · " + sizes : "Downloading · " + sizes; color = PRIMARY; }
                if (it.speed > 1) {
                    meta = sizeText((long) it.speed) + "/s";
                    if (tot > 0) meta += " · " + eta((long) ((tot - got) / it.speed)) + " left";
                    meta += " · " + it.connections + (it.connections == 1 ? " connection" : " connections");
                } else meta = "Connecting…";
                break;
            case Item.PAUSED: stat = (it.error.isEmpty() ? "Paused" : it.error) + " · " + (tot > 0 ? (pct / 10) + "% · " : "") + sizes; color = WARN; tint = WARN; break;
            case Item.DONE: stat = "Complete · " + sizeText(Math.max(tot, got)); color = GOOD; tint = GOOD; pct = 1000; meta = it.error.isEmpty() ? "Saved in Downloads/" + Prefs.folder(this) : it.error; break;
            case Item.ERROR: stat = it.error.isEmpty() ? "Failed" : it.error; color = BAD; tint = BAD; break;
            default: stat = ""; break;
        }
        c.status.setText(stat);
        c.status.setTextColor(color);
        c.meta.setText(meta);
        c.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        boolean indet = it.state == Item.DOWNLOADING && ((tot <= 0 && !it.stop) || !it.phase.isEmpty());
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
                case Item.QUEUED:
                    c.actions.addView(pill("Pause", WARN, false, v -> it.stop = true));
                    c.actions.addView(pill("Cancel", BAD, false, v -> cancel(it)));
                    break;
                case Item.PAUSED:
                    c.actions.addView(pill("Resume", PRIMARY, true, v -> resume(it)));
                    c.actions.addView(pill("Cancel", BAD, false, v -> cancel(it)));
                    break;
                case Item.ERROR:
                    c.actions.addView(pill("Retry", PRIMARY, true, v -> resume(it)));
                    c.actions.addView(pill("Remove", BAD, false, v -> cancel(it)));
                    break;
                case Item.DONE:
                    if (previewable(it.mime, it.name.contains(".") ? it.name.substring(it.name.lastIndexOf('.') + 1) : "")) {
                        c.actions.addView(pill("Preview", GOOD, true, v -> openPreview(it.uri, "", it.mime, it.name)));
                    }
                    c.actions.addView(pill("Open", PRIMARY, false, v -> openFile(it)));
                    c.actions.addView(pill("More", SUB, false, v -> more(it)));
                    break;
                default: break;
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void start(String raw) {
        String u = firstUrl(raw);
        if (u == null) { toast("Enter a valid http or https link"); return; }
        openDetails(u, true);
    }

    private void startDownload(String url, String headers, String name, String mime) {
        startDownload(url, headers, name, mime, "", "");
    }

    private void startDownload(String url, String headers, String name, String mime, String audio, String audioHeaders) {
        enqueue(url, headers, name, audio, audioHeaders);
        urlField.setText("");
        lastClip = url;
        banner.setVisibility(View.GONE);
        toast("Download started");
    }

    private void enqueue(String url, String headers, String name, String audio, String audioHeaders) {
        headers = (headers == null ? "" : headers) + advancedHeaders();
        Intent s = new Intent(this, DownloadService.class).setAction(DownloadService.ADD)
                .putExtra("url", url).putExtra("connections", conns);
        if (headers != null && !headers.isEmpty()) s.putExtra("headers", headers);
        if (name != null && !name.isEmpty()) s.putExtra("name", name);
        if (audio != null && !audio.isEmpty()) s.putExtra("audio", audio).putExtra("audioHeaders", audioHeaders);
        startForegroundService(s);
    }

    private void resume(Item it) {
        if (it.state == Item.ERROR && it.error.startsWith("This link is a web page")) {
            String u = it.url;
            cancel(it);
            openDetails(u, false);
            return;
        }
        if (it.state == Item.ERROR) it.error = "";
        startForegroundService(new Intent(this, DownloadService.class).setAction(DownloadService.RESUME).putExtra("id", it.id));
    }

    // ---------------------------------------------------------------- link details

    private static final Map<String, Bitmap> THUMBS = new HashMap<>();

    /** A grey bar with a light band sliding across it: shown while a link is being read. */
    static final class Shimmer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int base, hi;
        private final float radius;
        private float phase;
        private final ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);

        Shimmer(Context c, int base, int hi, float radius) {
            super(c);
            this.base = base;
            this.hi = hi;
            this.radius = radius;
            an.setDuration(1100);
            an.setRepeatCount(ValueAnimator.INFINITE);
            an.addUpdateListener(a -> { phase = (Float) a.getAnimatedValue(); invalidate(); });
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); an.start(); }
        @Override protected void onDetachedFromWindow() { an.cancel(); super.onDetachedFromWindow(); }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = Math.max(1, getWidth());
            float x = phase * w * 2 - w;
            p.setShader(new LinearGradient(x, 0, x + w, 0, new int[]{base, hi, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            r.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(r, radius, radius, p);
        }
    }

    private Shimmer bar(int heightDp, int widthPercent, int radiusDp, int bottomDp) {
        Shimmer s = new Shimmer(this, dark ? 0xFF1B2740 : 0xFFE8EDF4, dark ? 0xFF2C3E63 : 0xFFF8FAFD, dp(radiusDp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthPercent >= 100 ? -1 : (int) (getResources().getDisplayMetrics().widthPixels * 0.82f * widthPercent / 100f), dp(heightDp));
        lp.bottomMargin = dp(bottomDp);
        s.setLayoutParams(lp);
        return s;
    }

    private void skeletonHead(LinearLayout head) {
        head.removeAllViews();
        head.addView(bar(180, 100, 12, 12));
        head.addView(bar(18, 75, 6, 8));
        head.addView(bar(12, 45, 6, 4));
    }

    private void skeletonBody(final LinearLayout body) {
        body.removeAllViews();
        body.setTag("loading");
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(4));
        pp.topMargin = dp(14);
        body.addView(pb, pp);
        final String[] msgs = {"Reading the link…", "Finding every quality…", "Checking file sizes…", "Almost ready…"};
        final TextView st = text(msgs[0], 12, SUB, true);
        st.setPadding(0, dp(8), 0, dp(10));
        body.addView(st);
        for (int i = 0; i < 4; i++) body.addView(bar(58, 100, 12, 6));
        final int[] k = {0};
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!"loading".equals(body.getTag())) return;
                k[0] = Math.min(k[0] + 1, msgs.length - 1);
                st.setText(msgs[k[0]]);
                h.postDelayed(this, 1400);
            }
        }, 1400);
    }

    private boolean alive(AlertDialog d) {
        return !isFinishing() && d.isShowing();
    }

    private void openDetails(final String url, final boolean autoStartDirect) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        final LinearLayout headBox = new LinearLayout(this);
        headBox.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout bodyBox = new LinearLayout(this);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(headBox);
        box.addView(bodyBox);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(sv).create();
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(rect(CARD, 16, 0));
        skeletonHead(headBox);
        skeletonBody(bodyBox);

        // title and picture first (usually under a second) ...
        new Thread(() -> {
            try {
                final Extractor.Info m = Resolver.meta(this, url);
                runOnUiThread(() -> {
                    if (alive(dlg) && "loading".equals(bodyBox.getTag())) fillHeader(headBox, m, true);
                });
            } catch (Exception ignored) { }
        }).start();

        // ... then the full list of qualities
        new Thread(() -> {
            Extractor.Info got = null;
            String err = null;
            try {
                got = Resolver.get(this, url, false);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final Extractor.Info info = got;
            final String error = err;
            runOnUiThread(() -> {
                if (!alive(dlg)) return;
                if (info != null && autoStartDirect && info.direct && info.fmts.size() == 1) {
                    bodyBox.setTag("done");
                    dlg.dismiss();
                    Extractor.Fmt f = info.fmts.get(0);
                    startDownload(f.url, f.headers, null, null, "", "");
                    return;
                }
                bodyBox.setTag("done");
                if (info == null) {
                    headBox.removeAllViews();
                    bodyBox.removeAllViews();
                    bodyBox.addView(text("Could not read this link", 15, TXT, true));
                    TextView m = text(error, 12, BAD, false);
                    m.setPadding(0, dp(6), 0, dp(10));
                    bodyBox.addView(m);
                    LinearLayout fr = new LinearLayout(this);
                    fr.setGravity(Gravity.END);
                    fr.addView(pill("Sign in", WARN, false, v -> { dlg.dismiss(); startActivity(new Intent(this, BrowserActivity.class).putExtra("url", url)); }));
                    fr.addView(pill("Close", PRIMARY, true, v -> dlg.dismiss()));
                    bodyBox.addView(fr);
                    return;
                }
                fillHeader(headBox, info, false);
                fillBody(bodyBox, dlg, info);
            });
        }).start();
    }

    private void fillHeader(LinearLayout head, final Extractor.Info in, boolean partial) {
        if (partial && in.title.isEmpty() && in.thumb.isEmpty()) return;
        head.removeAllViews();
        if (!in.thumb.isEmpty()) {
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(rect(FIELD, 12, 0));
            iv.setClipToOutline(true);
            Bitmap cached = THUMBS.get(in.thumb);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setAlpha(0f);
                new Thread(() -> {
                    try {
                        final Bitmap bm = Extractor.bitmap(in.thumb, in.thumbReferer);
                        if (bm != null) {
                            THUMBS.put(in.thumb, bm);
                            runOnUiThread(() -> { iv.setImageBitmap(bm); iv.animate().alpha(1f).setDuration(250).start(); });
                        }
                    } catch (Exception ignored) { }
                }).start();
                iv.animate().alpha(1f).setDuration(1).start();
            }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(180));
            head.addView(iv, ip);
        }
        TextView title = text(in.title.isEmpty() ? in.url : in.title, 16, TXT, true);
        title.setPadding(0, dp(12), 0, 0);
        title.setTextIsSelectable(true);
        head.addView(title);

        StringBuilder sub = new StringBuilder(in.site);
        if (!in.uploader.isEmpty() && !in.uploader.equals(in.site)) sub.append(" · ").append(in.uploader);
        if (in.duration > 0) sub.append(" · ").append(clock(in.duration / 1000));
        TextView st = text(sub.toString(), 12, SUB, false);
        st.setPadding(0, dp(3), 0, 0);
        head.addView(st);

        if (!in.desc.isEmpty()) {
            final TextView ds = text(in.desc, 12, SUB, false);
            ds.setMaxLines(3);
            ds.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ds.setPadding(0, dp(8), 0, 0);
            ds.setOnClickListener(v -> ds.setMaxLines(ds.getMaxLines() > 3 ? 3 : 500));
            head.addView(ds);
        }
        head.setAlpha(0.2f);
        head.animate().alpha(1f).setDuration(220).start();
    }

    private TextView link(String label, int color, View.OnClickListener l) {
        TextView t = text(label, 12, color, true);
        t.setPadding(0, dp(6), dp(14), dp(2));
        t.setOnClickListener(l);
        return t;
    }

    private void fillBody(LinearLayout body, final AlertDialog dlg, final Extractor.Info in) {
        body.removeAllViews();
        if (!in.note.isEmpty()) {
            TextView n = text(in.note, 12, in.fmts.isEmpty() ? BAD : WARN, false);
            n.setPadding(0, dp(12), 0, 0);
            body.addView(n);
        }

        if (!in.fmts.isEmpty()) {
            TextView hd = text("Available downloads · " + in.fmts.size(), 13, SUB, true);
            hd.setPadding(0, dp(14), 0, dp(6));
            body.addView(hd);
            int idx = 0;
            for (final Extractor.Fmt f : in.fmts) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(10), dp(8));
                row.setBackground(rect(FIELD, 12, LINE));
                LinearLayout tx = new LinearLayout(this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.addView(text(f.label, 14, TXT, true));
                tx.addView(text(f.detail, 12, SUB, false));
                LinearLayout links = new LinearLayout(this);
                if (previewable(f.mime, f.ext)) links.addView(link("▶ Preview", PRIMARY, v -> openPreview(f.url, f.headers, f.mime, f.name)));
                links.addView(link("Copy URL", SUB, v -> copy("Direct URL", f.url)));
                tx.addView(links);
                row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(pill("Download", PRIMARY, true, v -> {
                    dlg.dismiss();
                    startDownload(f.url, f.headers, f.name, f.mime, f.audioUrl, f.audioHeaders);
                }));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(6);
                body.addView(row, rp);
                row.setAlpha(0f);
                row.setTranslationY(dp(10));
                row.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(idx, 10) * 35L).setDuration(220).start();
                idx++;
            }
        }

        LinearLayout copies = new LinearLayout(this);
        copies.setGravity(Gravity.END);
        if (!in.title.isEmpty()) copies.addView(pill("Copy title", SUB, false, v -> copy("Title", in.title)));
        if (!in.desc.isEmpty()) copies.addView(pill("Copy description", SUB, false, v -> copy("Description", in.desc)));
        copies.addView(pill("Copy link", SUB, false, v -> copy("Link", in.url)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        body.addView(copies, cp);

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.END);
        foot.addView(pill("Copy all details", PRIMARY, false, v -> copy("Details", allDetails(in))));
        if (in.fmts.isEmpty()) {
            foot.addView(pill("Sign in", WARN, false, v -> {
                dlg.dismiss();
                startActivity(new Intent(this, BrowserActivity.class).putExtra("url", in.url));
            }));
        }
        foot.addView(pill("Close", PRIMARY, true, v -> dlg.dismiss()));
        body.addView(foot, new LinearLayout.LayoutParams(-1, -2));
    }

    static boolean previewable(String mime, String ext) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.US);
        String e = ext == null ? "" : ext.toLowerCase(Locale.US);
        return m.startsWith("video/") || m.startsWith("audio/") || m.startsWith("image/") || m.startsWith("text/")
                || e.matches("mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|jpg|jpeg|png|gif|webp|bmp|txt|log|md|json|csv|xml");
    }

    private void openPreview(String uri, String headers, String mime, String title) {
        startActivity(new Intent(this, PreviewActivity.class).putExtra("uri", uri).putExtra("headers", headers == null ? "" : headers)
                .putExtra("mime", mime == null ? "" : mime).putExtra("title", title == null ? "" : title));
    }

    private void copy(String what, String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(what, text));
        toast(what + " copied");
    }

    private String allDetails(Extractor.Info in) {
        StringBuilder b = new StringBuilder();
        b.append("Title: ").append(in.title).append('\n');
        b.append("Site: ").append(in.site).append('\n');
        if (!in.uploader.isEmpty()) b.append("Uploader: ").append(in.uploader).append('\n');
        if (in.duration > 0) b.append("Duration: ").append(clock(in.duration / 1000)).append('\n');
        b.append("Link: ").append(in.url).append('\n');
        if (!in.thumb.isEmpty()) b.append("Thumbnail: ").append(in.thumb).append('\n');
        if (!in.desc.isEmpty()) b.append("\nDescription:\n").append(in.desc).append('\n');
        if (!in.fmts.isEmpty()) b.append("\nDownloads:\n");
        for (Extractor.Fmt f : in.fmts) {
            b.append("- ").append(f.label).append(" · ").append(f.detail).append('\n').append("  ").append(f.url).append('\n');
            if (!f.audioUrl.isEmpty()) b.append("  audio: ").append(f.audioUrl).append('\n');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- batch and advanced options

    private EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(SUB);
        e.setTextColor(TXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setBackground(rect(FIELD, 10, LINE));
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        if (multi) { e.setMinLines(3); e.setGravity(Gravity.TOP); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        else e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    private static String txt(EditText e) {
        return e == null ? "" : e.getText().toString().trim();
    }

    private String advancedHeaders() {
        StringBuilder b = new StringBuilder();
        if (!txt(fRef).isEmpty()) b.append("Referer: ").append(txt(fRef)).append('\n');
        if (!txt(fCookie).isEmpty()) b.append("Cookie: ").append(txt(fCookie)).append('\n');
        if (!txt(fUa).isEmpty()) b.append("User-Agent: ").append(txt(fUa)).append('\n');
        for (String line : txt(fHeaders).split("\n")) if (line.contains(":")) b.append(line.trim()).append('\n');
        if (!txt(fUser).isEmpty()) {
            String cred = txt(fUser) + ":" + fPass.getText().toString();
            b.append("Authorization: Basic ").append(android.util.Base64.encodeToString(cred.getBytes(), android.util.Base64.NO_WRAP)).append('\n');
        }
        return b.toString();
    }

    private static java.util.List<String> expand(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("\\[(\\d+)-(\\d+)\\]").matcher(s);
        if (m.find()) {
            int w = m.group(1).length();
            long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(2));
            if (b - a > 2000) b = a + 2000;
            for (long i = a; i <= b; i++) {
                out.addAll(expand(s.substring(0, m.start()) + String.format(Locale.US, "%0" + w + "d", i) + s.substring(m.end())));
            }
            return out;
        }
        m = Pattern.compile("\\[([a-z])-([a-z])\\]").matcher(s);
        if (m.find()) {
            for (char c = m.group(1).charAt(0); c <= m.group(2).charAt(0); c++) {
                out.addAll(expand(s.substring(0, m.start()) + c + s.substring(m.end())));
            }
            return out;
        }
        out.add(s);
        return out;
    }

    private void showBatch() {
        final EditText e = field("One address per line", true);
        e.setMinLines(7);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        TextView hint = text("Paste many links, one per line. Patterns work too:\nhttp://site.com/photo[001-020].jpg  or  file[a-e].zip", 12, SUB, false);
        box.addView(hint);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle("Batch download").setView(box)
                .setPositiveButton("Download all", (d, w) -> {
                    int n = 0;
                    for (String line : e.getText().toString().split("\n")) {
                        String u = firstUrl(line.trim());
                        if (u == null) continue;
                        for (String x : expand(u)) { enqueue(x, "", null, "", ""); n++; }
                    }
                    toast(n == 0 ? "No links found" : n + " downloads added");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void more(final Item it) {
        final String[] names = {"Share file", "Copy link", "Download again", "Delete…"};
        new AlertDialog.Builder(this).setTitle(it.name).setItems(names, (d, which) -> {
            if (which == 0) {
                try {
                    Intent s = new Intent(Intent.ACTION_SEND).setType(it.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(it.uri))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share file"));
                } catch (Exception e) { toast("The file is no longer in Downloads"); }
            } else if (which == 1) copy("Link", it.url);
            else if (which == 2) { enqueue(it.url, it.headers, null, "", ""); toast("Download started"); }
            else confirmDelete(it);
        }).show();
    }

    private static String clock(long s) {
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
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

    static String sizeText(long b) {
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
