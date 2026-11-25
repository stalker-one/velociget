package io.encodal.idm;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings. */
final class Prefs {
    static volatile long limitBps;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int getInt(Context c, String k, int d) { return sp(c).getInt(k, d); }
    static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); refresh(c); }
    static boolean getBool(Context c, String k, boolean d) { return sp(c).getBoolean(k, d); }
    static void putBool(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    static String getStr(Context c, String k, String d) { return sp(c).getString(k, d); }
    static void putStr(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    static int conns(Context c) { return getInt(c, "conns", 8); }
    static int parallel(Context c) { return getInt(c, "parallel", 3); }
    static int theme(Context c) { return getInt(c, "theme", 0); } // 0 system, 1 light, 2 dark

    static String folder(Context c) {
        String f = getStr(c, "folder", "IDM By Encodal").replaceAll("[\\\\:*?\"<>|]+", "_").replaceAll("^/+|/+$", "").trim();
        return f.isEmpty() ? "IDM By Encodal" : f;
    }

    static void refresh(Context c) { limitBps = getInt(c, "limit", 0) * 1024L; }
}
