package io.encodal.idm;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Downloads an HLS stream: all parts at the same time, decrypts them if needed, joins them and saves an MP4 in Downloads. */
final class HlsDownloader {
    private HlsDownloader() { }

    private static final class Stopped extends Exception { }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    private static void createEntry(Context ctx, Item it) throws Exception {
        String name = it.name.isEmpty() ? "stream-" + it.id : it.name;
        name = name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "") + ".mp4";
        name = name.replaceAll("[\\\\/:*?\"<>|]+", "_");
        it.name = name;
        it.mime = "video/mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder(ctx));
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri out = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("Cannot create the file in Downloads");
        it.uri = out.toString();
    }

    private static byte[] ivFor(Hls.Seg s) {
        byte[] iv = new byte[16];
        if (s.iv != null) {
            String h = s.iv.replaceFirst("^0[xX]", "");
            while (h.length() < 32) h = "0" + h;
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        } else {
            long q = s.seq;
            for (int i = 15; i >= 8; i--) { iv[i] = (byte) (q & 0xFF); q >>= 8; }
        }
        return iv;
    }

    private static long segment(Item it, Hls.Seg s, File f, Map<String, byte[]> keys) throws Exception {
        if (f.exists() && f.length() > 0) return f.length();
        Exception last = null;
        for (int a = 0; a < 5; a++) {
            if (it.stop) throw new Stopped();
            try {
                byte[] data = Hls.bytes(s.url, it.headers, 80_000_000);
                if (s.keyUrl != null) {
                    byte[] k = keys.get(s.keyUrl);
                    if (k == null) {
                        k = Hls.bytes(s.keyUrl, it.headers, 4096);
                        keys.put(s.keyUrl, k);
                    }
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(ivFor(s)));
                    data = c.doFinal(data);
                }
                File part = new File(f.getPath() + ".part");
                try (FileOutputStream o = new FileOutputStream(part)) { o.write(data); }
                if (!part.renameTo(f)) throw new Exception("Cannot save a part");
                return data.length;
            } catch (Stopped e) {
                throw e;
            } catch (Exception e) {
                last = e;
                Thread.sleep(800L * (a + 1));
            }
        }
        throw last;
    }

    static void run(final Context ctx, final Item it) {
        final ContentResolver cr = ctx.getContentResolver();
        it.stop = false;
        it.cancel = false;
        it.error = "";
        it.state = Item.DOWNLOADING;
        final File dir = new File(ctx.getCacheDir(), "hls" + it.id);
        try {
            Hls.Playlist pl = Hls.parse(it.url, Hls.text(it.url, it.headers));
            if (pl.master) {
                Hls.Variant best = null;
                for (Hls.Variant v : pl.variants) if (best == null || v.bw > best.bw) best = v;
                if (best == null) throw new Exception("The playlist is empty");
                pl = Hls.parse(best.url, Hls.text(best.url, it.headers));
            }
            if (pl.segs.isEmpty()) throw new Exception("The stream has no video parts");
            final List<Hls.Seg> segs = pl.segs;
            final String mapUrl = pl.mapUrl;
            it.resumable = false;
            if (it.segStart == null) {
                it.segStart = new long[]{0};
                it.segEnd = new long[]{Long.MAX_VALUE};
                it.segPos = new AtomicLongArray(1);
            }
            if (it.uri.isEmpty()) createEntry(ctx, it);
            dir.mkdirs();

            final int n = segs.size();
            final AtomicInteger next = new AtomicInteger(0), doneCount = new AtomicInteger(0);
            final AtomicLong bytes = new AtomicLong(0);
            final Throwable[] failure = new Throwable[1];
            final Map<String, byte[]> keys = new ConcurrentHashMap<>();
            int threads = Math.max(1, Math.min(8, it.connections));
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                ts[t] = new Thread(() -> {
                    while (!it.stop && failure[0] == null) {
                        int i = next.getAndIncrement();
                        if (i >= n) break;
                        try {
                            long len = segment(it, segs.get(i), new File(dir, i + ".seg"), keys);
                            bytes.addAndGet(len);
                            int d = doneCount.incrementAndGet();
                            it.singleDone = bytes.get();
                            it.total = Math.max(bytes.get(), bytes.get() * n / Math.max(1, d));
                        } catch (Stopped e) {
                            break;
                        } catch (Throwable e) {
                            if (failure[0] == null) failure[0] = e;
                            it.stop = true;
                        }
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) t.join();

            if (it.cancel) {
                deleteDir(dir);
                try { cr.delete(Uri.parse(it.uri), null, null); } catch (Throwable ignored) { }
                it.state = Item.CANCELED;
            } else if (failure[0] != null) {
                it.error = String.valueOf(failure[0].getMessage());
                it.state = Item.ERROR;
            } else if (it.stop) {
                it.state = Item.PAUSED;
            } else {
                it.phase = "Saving the video…";
                File joined = new File(ctx.getCacheDir(), "hls" + it.id + ".joined");
                File mp4 = new File(ctx.getCacheDir(), "hls" + it.id + ".mp4");
                try {
                    try (FileOutputStream o = new FileOutputStream(joined)) {
                        if (mapUrl != null) o.write(Hls.bytes(mapUrl, it.headers, 8_000_000));
                        byte[] buf = new byte[65536];
                        for (int i = 0; i < n; i++) {
                            try (InputStream in = new FileInputStream(new File(dir, i + ".seg"))) {
                                int r;
                                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                            }
                        }
                    }
                    boolean ok = Remux.toMp4(joined, mp4);
                    File outFile = ok ? mp4 : joined;
                    Uri uri = Uri.parse(it.uri);
                    if (!ok) {
                        // could not convert: keep the joined stream, which most players still open
                        String ts2 = it.name.replaceAll("\\.mp4$", "") + ".ts";
                        ContentValues nv = new ContentValues();
                        nv.put(MediaStore.Downloads.DISPLAY_NAME, ts2);
                        nv.put(MediaStore.Downloads.MIME_TYPE, "video/mp2t");
                        cr.update(uri, nv, null, null);
                        it.name = ts2;
                        it.mime = "video/mp2t";
                        it.error = "Saved as a .ts stream (your phone could not convert it to MP4)";
                    }
                    try (InputStream in = new FileInputStream(outFile); OutputStream os = cr.openOutputStream(uri, "wt")) {
                        if (os == null) throw new Exception("Cannot write the file");
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    it.state = Item.DONE;
                    it.finishedAt = System.currentTimeMillis();
                    if (it.total < it.singleDone) it.total = it.singleDone;
                } finally {
                    joined.delete();
                    mp4.delete();
                    it.phase = "";
                }
                deleteDir(dir);
            }
        } catch (Throwable e) {
            if (it.cancel) {
                try { if (!it.uri.isEmpty()) cr.delete(Uri.parse(it.uri), null, null); } catch (Throwable ignored) { }
                deleteDir(dir);
                it.state = Item.CANCELED;
            } else {
                it.error = String.valueOf(e.getMessage());
                it.state = Item.ERROR;
            }
        }
    }
}
