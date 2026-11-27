package io.encodal.idm;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom navigation bar shared by the main screens. */
final class Nav {
    private Nav() { }

    static final int HOME = 0, FILES = 1, BROWSER = 2, YOUTUBE = 3, MORE = 4;
    private static final int[] ICON = {Ico.DOWNLOAD, Ico.FOLDER, Ico.GLOBE, Ico.YT, Ico.COG};
    private static final String[] NAME = {"Downloads", "Files", "Browser", "YouTube", "More"};

    static boolean dark(Activity a) {
        int t = Prefs.theme(a);
        return t == 2 || (t == 0 && (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    static View bar(final Activity a, final int active) {
        boolean dk = dark(a);
        int bg = dk ? 0xFF131C2E : 0xFFFFFFFF, line = dk ? 0xFF22304A : 0xFFE2E8F0, off = dk ? 0xFF93A0B8 : 0xFF64748B, on = dk ? 0xFF60A5FA : 0xFF1D4ED8;
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        View ln = new View(a);
        ln.setBackgroundColor(line);
        wrap.addView(ln, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout row = new LinearLayout(a);
        row.setBackgroundColor(bg);
        row.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(a);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            Ico ic = new Ico(a, ICON[i]);
            ic.color(i == active ? on : (i == YOUTUBE ? 0xFFEF4444 : off));
            ic.setPadding(0, dp(a, 3), 0, dp(a, 3));
            if (i == active) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(dk ? 0x332563EB : 0x1F2563EB);
                g.setCornerRadius(dp(a, 14));
                ic.setBackground(g);
            }
            tab.addView(ic, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 32)));
            TextView lb = new TextView(a);
            lb.setText(NAME[i]);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            lb.setGravity(Gravity.CENTER);
            lb.setTextColor(i == active ? on : off);
            if (i == active) lb.setTypeface(Typeface.DEFAULT_BOLD);
            tab.addView(lb);
            tab.setOnClickListener(v -> go(a, active, idx));
            row.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        wrap.setElevation(dp(a, 6));
        return wrap;
    }

    static void go(Activity a, int active, int to) {
        if (to == active) return;
        Intent i;
        switch (to) {
            case HOME: i = new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); break;
            case FILES: i = new Intent(a, DeviceFilesActivity.class); break;
            case BROWSER: i = new Intent(a, BrowserActivity.class).putExtra("url", "https://www.google.com"); break;
            case YOUTUBE: i = new Intent(a, YouTubeActivity.class); break;
            default: i = new Intent(a, SettingsActivity.class); break;
        }
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
        if (active != HOME && to != BROWSER && to != YOUTUBE) a.finish();
    }
}
