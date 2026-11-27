package io.encodal.idm;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** "Check for updates": reads the latest release from GitHub, downloads the APK and hands it to the system installer. */
public class UpdateActivity extends Activity {
    private static final String API = "https://api.github.com/repos/stalker-one/velociget/releases?per_page=10";
    private int BG, CARD, TXT, SUB, LINE, PRIMARY = 0xFF2563EB;
    private TextView status, detail, action, current;
    private ProgressBar bar;
    private String apkUrl = "";
    private long apkSize;
    private BroadcastReceiver rx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dk = Nav.dark(this);
        BG = dk ? 0xFF0B1220 : 0xFFF1F5F9;
        CARD = dk ? 0xFF131C2E : 0xFFFFFFFF;
        TXT = dk ? 0xFFE5EAF3 : 0xFF0F172A;
        SUB = dk ? 0xFF93A0B8 : 0xFF64748B;
        LINE = dk ? 0xFF22304A : 0xFFE2E8F0;
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(dk ? 0xFF131C2E : 0xFFFFFFFF);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(12), dp(16), dp(14));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1D4ED8, 0xFF3B82F6}));
        TextView back = t("←", 22, Color.WHITE, true);
        back.setPadding(dp(12), 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        head.addView(t("App update", 19, Color.WHITE, true));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(26), dp(20), dp(22));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(CARD);
        cg.setCornerRadius(dp(18));
        cg.setStroke(1, LINE);
        card.setBackground(cg);
        TextView logo = t("⬇", 30, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1D4ED8, 0xFF60A5FA});
        lg.setCornerRadius(dp(22));
        logo.setBackground(lg);
        card.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        status = t("Checking for updates…", 18, TXT, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        card.addView(status);
        current = t("Installed: " + installedName(), 13, SUB, false);
        current.setGravity(Gravity.CENTER);
        current.setPadding(0, dp(6), 0, 0);
        card.addView(current);
        detail = t("", 13, SUB, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(10), 0, 0);
        card.addView(detail);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(6));
        bp.topMargin = dp(16);
        card.addView(bar, bp);
        action = t("Check again", 15, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable();
        ag.setColor(PRIMARY);
        ag.setCornerRadius(dp(14));
        action.setBackground(ag);
        action.setOnClickListener(v -> onAction());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(48));
        alp.topMargin = dp(20);
        card.addView(action, alp);
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView note = t("Updates come from the project's release page. Your downloads and settings are kept. Android will ask you to confirm the install.", 12, SUB, false);
        note.setPadding(dp(6), dp(14), dp(6), 0);
        body.addView(note);
        root.addView(Nav.bar(this, Nav.MORE));
        setContentView(root);

        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) startActivity(confirm);
                } else if (st == PackageInstaller.STATUS_SUCCESS) {
                    status.setText("Updated");
                } else if (st != -1) {
                    status.setText("Install did not finish");
                    detail.setText(String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
                    action.setText("Try again");
                    action.setEnabled(true);
                }
            }
        };
        IntentFilter f = new IntentFilter("io.encodal.idm.INSTALL_DONE");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(rx, f);
        check();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private long installedCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; }
    }

    private String installedName() {
        try { return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (build " + installedCode() + ")"; } catch (Exception e) { return ""; }
    }

    private void onAction() {
        if (apkUrl.isEmpty()) { check(); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            detail.setText("Allow \"Install unknown apps\" for IDM By Encodal, then come back and tap Update.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        download();
    }

    private void check() {
        apkUrl = "";
        status.setText("Checking for updates…");
        detail.setText("");
        bar.setVisibility(View.GONE);
        action.setEnabled(false);
        action.setAlpha(0.5f);
        action.setText("Checking…");
        new Thread(() -> {
            String err = null;
            long remoteCode = 0;
            String remoteName = "", url = "", notes = "";
            long size = 0;
            try {
                JSONArray rels = new JSONArray(read(API));
                outer:
                for (int i = 0; i < rels.length(); i++) {
                    JSONObject r = rels.getJSONObject(i);
                    JSONArray as = r.optJSONArray("assets");
                    if (as == null) continue;
                    String apk = "", ver = "";
                    long sz = 0;
                    for (int k = 0; k < as.length(); k++) {
                        JSONObject a = as.getJSONObject(k);
                        String n = a.optString("name");
                        if (n.endsWith(".apk")) { apk = a.optString("browser_download_url"); sz = a.optLong("size"); }
                        if (n.equals("android-version.json")) ver = a.optString("browser_download_url");
                    }
                    if (apk.isEmpty()) continue;
                    url = apk;
                    size = sz;
                    notes = r.optString("name");
                    if (!ver.isEmpty()) {
                        try {
                            JSONObject v = new JSONObject(read(ver));
                            remoteCode = v.optLong("versionCode");
                            remoteName = v.optString("versionName");
                        } catch (Exception ignored) { }
                    }
                    break outer;
                }
                if (url.isEmpty()) err = "No Android release was found yet.";
            } catch (Exception e) {
                err = "Could not reach the update server (" + e.getMessage() + ").";
            }
            final String fe = err, fu = url, fn = remoteName;
            final long fc = remoteCode, fs = size;
            runOnUiThread(() -> {
                action.setEnabled(true);
                action.setAlpha(1f);
                if (fe != null) {
                    status.setText("Can't check right now");
                    detail.setText(fe);
                    action.setText("Check again");
                    return;
                }
                boolean newer = fc > installedCode();
                if (fc == 0) {
                    status.setText("Latest build available");
                    detail.setText("Size " + MainActivity.sizeText(fs) + ". Install it to make sure you have the newest version.");
                    apkUrl = fu; apkSize = fs;
                    action.setText("Download and install");
                } else if (newer) {
                    status.setText("Update available");
                    detail.setText("New version " + (fn.isEmpty() ? "" : "v" + fn + " ") + "(" + MainActivity.sizeText(fs) + ")");
                    apkUrl = fu; apkSize = fs;
                    action.setText("Update now");
                } else {
                    status.setText("You're up to date");
                    detail.setText("This is the newest version.");
                    action.setText("Check again");
                }
            });
        }).start();
    }

    private String read(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "IDM-By-Encodal");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private void download() {
        action.setEnabled(false);
        action.setAlpha(0.5f);
        action.setText("Downloading…");
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        status.setText("Downloading update");
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "IDM-By-Encodal");
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : apkSize;
                long got = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        got += n;
                        final long g = got, tt = total;
                        runOnUiThread(() -> {
                            if (tt > 0) bar.setProgress((int) (g * 1000 / tt));
                            detail.setText(MainActivity.sizeText(g) + (tt > 0 ? " of " + MainActivity.sizeText(tt) : ""));
                        });
                    }
                }
                runOnUiThread(() -> { status.setText("Installing…"); detail.setText("Confirm the install when Android asks."); });
                install(f);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Download failed");
                    detail.setText(String.valueOf(e.getMessage()));
                    action.setEnabled(true);
                    action.setAlpha(1f);
                    action.setText("Try again");
                });
            }
        }).start();
    }

    private void install(File f) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("idm.apk", 0, f.length())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent("io.encodal.idm.INSTALL_DONE").setPackage(getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(this, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        }
    }

    private TextView t(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
