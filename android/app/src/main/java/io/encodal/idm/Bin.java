package io.encodal.idm;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Recycle bin for files the app can reach directly (needs All files access). Deleted items wait here for 30 days. */
final class Bin {
    private Bin() { }

    static final class Entry {
        File file;
        String name, orig, key;
        long time, size;
        boolean dir;
    }

    static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), ".IDM-Trash");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile() { return new File(dir(), ".index.json"); }

    private static synchronized JSONObject load() {
        try (FileInputStream in = new FileInputStream(indexFile())) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void save(JSONObject o) {
        try (FileOutputStream out = new FileOutputStream(indexFile())) { out.write(o.toString().getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    static boolean trash(Context c, File f) {
        try {
            String key = System.currentTimeMillis() + "_" + (int) (Math.random() * 1000) + "_" + f.getName();
            File to = new File(dir(), key);
            if (!f.renameTo(to)) return false;
            JSONObject o = load();
            o.put(key, new JSONObject().put("orig", f.getAbsolutePath()).put("time", System.currentTimeMillis()).put("name", f.getName()));
            save(o);
            MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{f.getAbsolutePath(), to.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static List<Entry> list() {
        purgeOld();
        List<Entry> out = new ArrayList<>();
        JSONObject o = load();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            File f = new File(dir(), k);
            if (!f.exists()) continue;
            JSONObject e = o.optJSONObject(k);
            Entry x = new Entry();
            x.file = f;
            x.key = k;
            x.name = e == null ? f.getName() : e.optString("name", f.getName());
            x.orig = e == null ? "" : e.optString("orig");
            x.time = e == null ? 0 : e.optLong("time");
            x.dir = f.isDirectory();
            x.size = f.length();
            out.add(x);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    static boolean restore(Context c, Entry e) {
        try {
            File to = new File(e.orig);
            if (to.getParentFile() != null) to.getParentFile().mkdirs();
            if (to.exists()) to = new File(to.getParentFile(), "restored_" + to.getName());
            if (!e.file.renameTo(to)) return false;
            forget(e.key);
            MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{to.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    static void purge(Entry e) {
        deleteTree(e.file);
        forget(e.key);
    }

    private static void forget(String key) {
        JSONObject o = load();
        o.remove(key);
        save(o);
    }

    static void purgeOld() {
        try {
            JSONObject o = load();
            Iterator<String> it = o.keys();
            List<String> old = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject e = o.optJSONObject(k);
                if (e != null && System.currentTimeMillis() - e.optLong("time") > KEEP_MS) old.add(k);
            }
            for (String k : old) { deleteTree(new File(dir(), k)); o.remove(k); }
            if (!old.isEmpty()) save(o);
        } catch (Exception ignored) { }
    }

    static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        f.delete();
    }
}
