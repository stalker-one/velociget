package io.encodal.idm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/** All app settings in one screen. */
public class SettingsActivity extends Activity {
    private int BG, CARD, TXT, SUB, LINE, FIELD;
    private static final int PRIMARY = 0xFF2563EB;
    private EditText folder, uaField;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int th = Prefs.theme(this);
        boolean dark = th == 2 || (th == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        BG = dark ? 0xFF0B1220 : 0xFFF1F5F9;
        CARD = dark ? 0xFF131C2E : 0xFFFFFFFF;
        TXT = dark ? 0xFFE5EAF3 : 0xFF0F172A;
        SUB = dark ? 0xFF93A0B8 : 0xFF64748B;
        LINE = dark ? 0xFF22304A : 0xFFE2E8F0;
        FIELD = dark ? 0xFF0E1626 : 0xFFF8FAFC;
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(CARD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(12), dp(18), dp(14));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1D4ED8, 0xFF3B82F6}));
        TextView back = text("‹", 30, Color.WHITE, true);
        back.setPadding(dp(14), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        head.addView(text("Settings", 20, Color.WHITE, true));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(28));
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Nav.bar(this, Nav.MORE));

        // app update
        LinearLayout up = section(body, "App update");
        String vn = "";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView upt = text("Installed version " + vn + ". Check for a newer version and install it from here.", 12, SUB, false);
        upt.setPadding(0, dp(4), 0, dp(8));
        up.addView(upt);
        up.addView(button("Check for updates", v -> startActivity(new Intent(this, UpdateActivity.class))));
        LinearLayout perm = section(body, "Storage access");
        TextView pt = text("Give the app All files access to browse every folder and show hidden files in Files → Folders.", 12, SUB, false);
        pt.setPadding(0, dp(4), 0, dp(8));
        perm.addView(pt);
        perm.addView(button("Open access settings", v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        }));

        // downloads
        LinearLayout d = section(body, "Downloads");
        label(d, "Default connections per file");
        chips(d, new String[]{"4", "8", "16", "32"}, indexOf(new int[]{4, 8, 16, 32}, Prefs.conns(this)),
                i -> Prefs.putInt(this, "conns", new int[]{4, 8, 16, 32}[i]));
        label(d, "Downloads at the same time");
        chips(d, new String[]{"1", "2", "3", "5"}, indexOf(new int[]{1, 2, 3, 5}, Prefs.parallel(this)),
                i -> Prefs.putInt(this, "parallel", new int[]{1, 2, 3, 5}[i]));
        label(d, "Speed limit");
        final int[] lim = {0, 512, 1024, 2048, 5120};
        chips(d, new String[]{"Off", "512K", "1 MB", "2 MB", "5 MB"}, indexOf(lim, Prefs.getInt(this, "limit", 0)),
                i -> Prefs.putInt(this, "limit", lim[i]));
        toggle(d, "Download only on Wi-Fi", "Pauses new downloads on mobile data", "wifi", false);
        label(d, "Save folder (inside Downloads)");
        folder = new EditText(this);
        folder.setText(Prefs.folder(this));
        folder.setSingleLine(true);
        folder.setTextColor(TXT);
        folder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        folder.setBackground(rect(FIELD, 10, LINE));
        folder.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(6);
        d.addView(folder, fp);

        label(d, "Custom user agent (optional)");
        uaField = new EditText(this);
        uaField.setText(Prefs.getStr(this, "ua", ""));
        uaField.setHint("Leave empty for the default");
        uaField.setHintTextColor(SUB);
        uaField.setSingleLine(true);
        uaField.setTextColor(TXT);
        uaField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        uaField.setBackground(rect(FIELD, 10, LINE));
        uaField.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
        up.topMargin = dp(6);
        d.addView(uaField, up);

        // appearance & alerts
        LinearLayout a = section(body, "Appearance and alerts");
        label(a, "Theme");
        chips(a, new String[]{"System", "Light", "Dark"}, Prefs.theme(this), i -> Prefs.putInt(this, "theme", i));
        toggle(a, "Detect copied links", "Offer a copied link on the home screen", "clip", true);
        toggle(a, "Notify when finished", "One notification for every finished or failed file", "notify", true);

        // engine
        LinearLayout en = section(body, "Download engine");
        final TextView ver0 = text("yt-dlp · checking…", 13, SUB, false);
        ver0.setPadding(0, dp(6), 0, dp(8));
        en.addView(ver0);
        new Thread(() -> {
            final String v = PyEngine.version(this);
            runOnUiThread(() -> ver0.setText("yt-dlp " + v + " — reads YouTube, TikTok, Instagram, Facebook, X and 1000+ sites"));
        }).start();
        en.addView(button("Update engine", v -> {
            Toast.makeText(this, "Updating…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try { PyEngine.update(this); msg = "Updated. Close and reopen the app to use it."; }
                catch (Exception e) { msg = "Update failed: " + e.getMessage(); }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }).start();
        }));

        // about
        LinearLayout ab = section(body, "About");
        String ver = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Exception ignored) { }
        ab.addView(text("IDM By Encodal " + ver, 14, TXT, true));
        TextView sub = text("Segmented downloads on up to 32 connections, resume, link details and quality list. Made by Encodal.", 12, SUB, false);
        sub.setPadding(0, dp(4), 0, dp(8));
        ab.addView(sub);
        LinearLayout r = new LinearLayout(this);
        r.addView(button("Website", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://idm-by-encodal.vercel.app/")))));
        r.addView(button("Clear history", v -> new AlertDialog.Builder(this)
                .setTitle("Clear finished downloads?")
                .setMessage("Removes finished and failed items from the list. Files stay in Downloads.")
                .setPositiveButton("Clear", (x, y) -> {
                    Store.load(this, DownloadService.ITEMS);
                    for (Item it : DownloadService.ITEMS) if (it.state == Item.DONE || it.state == Item.ERROR) DownloadService.ITEMS.remove(it);
                    Store.save(this, DownloadService.ITEMS);
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show()));
        ab.addView(r);
        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (folder != null) Prefs.putStr(this, "folder", folder.getText().toString());
        if (uaField != null) { Prefs.putStr(this, "ua", uaField.getText().toString()); Prefs.refresh(this); }
    }

    private int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private LinearLayout section(LinearLayout body, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(rect(CARD, 14, LINE));
        c.addView(text(title, 15, TXT, true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12);
        body.addView(c, p);
        return c;
    }

    private void label(LinearLayout c, String s) {
        TextView t = text(s, 12, SUB, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(14);
        c.addView(t, p);
    }

    private void chips(LinearLayout c, String[] labels, int selected, IntConsumer on) {
        final LinearLayout row = new LinearLayout(this);
        final TextView[] ts = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = text(labels[i], 13, TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                for (int k = 0; k < ts.length; k++) paint(ts[k], k == idx);
                on.accept(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (i < labels.length - 1) lp.rightMargin = dp(8);
            row.addView(t, lp);
            ts[i] = t;
            paint(t, i == selected);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(6);
        c.addView(row, rp);
    }

    private void paint(TextView t, boolean on) {
        t.setBackground(rect(on ? PRIMARY : FIELD, 10, on ? PRIMARY : LINE));
        t.setTextColor(on ? Color.WHITE : TXT);
    }

    private void toggle(LinearLayout c, String title, String sub, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(title, 14, TXT, true));
        tx.addView(text(sub, 12, SUB, false));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.getBool(this, key, def));
        sw.setOnCheckedChangeListener((v, on) -> Prefs.putBool(this, key, on));
        row.addView(sw);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        c.addView(row, p);
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = text(label, 13, PRIMARY, true);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(rect(FIELD, 18, LINE));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(8);
        t.setLayoutParams(p);
        return t;
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
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }
}
