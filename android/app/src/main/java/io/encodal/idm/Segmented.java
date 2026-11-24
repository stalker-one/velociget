package io.encodal.idm;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Segmented HTTP download: Range requests on several connections, written into the Downloads folder. */
final class Segmented {
    static final class Item {
        volatile String name = "";
        volatile String url;
        volatile long total = -1;
        final AtomicLong done = new AtomicLong();
        volatile String status = "Starting";
        volatile boolean finished;
        volatile boolean cancelled;
        volatile int connections;
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) IDMByEncodal/0.1";

    private static HttpURLConnection open(String url, long from, long to, int redirects) throws Exception {
        for (int i = 0; i <= redirects; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (from >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? to : ""));
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new Exception("Too many redirects");
    }

    private static String nameFrom(HttpURLConnection c, String url) {
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
            try {
                if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
            } catch (Exception ignored) { }
            m = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return m.group(1);
        }
        try {
            String p = new URL(url).getPath();
            String last = p.substring(p.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return URLDecoder.decode(last, "UTF-8");
        } catch (Exception ignored) { }
        return "download-" + System.currentTimeMillis();
    }

    static void run(Context ctx, Item it, int wanted) {
        Uri out = null;
        ContentResolver cr = ctx.getContentResolver();
        try {
            // 1. size and Range support
            HttpURLConnection probe = open(it.url, 0, 0, 8);
            int code = probe.getResponseCode();
            long total = -1;
            boolean ranges = false;
            if (code == 206) {
                String cr0 = probe.getHeaderField("Content-Range");
                if (cr0 != null && cr0.contains("/")) {
                    try { total = Long.parseLong(cr0.substring(cr0.indexOf('/') + 1).trim()); ranges = true; } catch (Exception ignored) { }
                }
            } else if (code == 200) {
                total = probe.getContentLengthLong();
            } else {
                throw new Exception("Server answered " + code);
            }
            String name = nameFrom(probe, it.url).replaceAll("[\\\\/:*?\"<>|]+", "_");
            String mime = probe.getContentType() == null ? "application/octet-stream" : probe.getContentType().split(";")[0];
            probe.disconnect();
            it.name = name;
            it.total = total;
            int n = (ranges && total > 1_000_000) ? Math.max(1, Math.min(32, wanted)) : 1;
            it.connections = n;

            // 2. the file in Downloads
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IDM By Encodal");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (out == null) throw new Exception("Cannot create the file in Downloads");
            final ParcelFileDescriptor pfd = cr.openFileDescriptor(out, "rw");
            if (pfd == null) throw new Exception("Cannot open the file for writing");
            final FileChannel ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
            if (total > 0) ch.truncate(0);

            it.status = "Downloading";
            final Throwable[] failure = new Throwable[1];
            Thread[] ts = new Thread[n];
            final long seg = total > 0 ? (total + n - 1) / n : -1;
            for (int i = 0; i < n; i++) {
                final long from = n == 1 ? -1 : i * seg;
                final long to = n == 1 ? -1 : Math.min(total - 1, (i + 1) * seg - 1);
                ts[i] = new Thread(() -> {
                    try {
                        pull(it, ch, from, to);
                    } catch (Throwable e) {
                        failure[0] = e;
                        it.cancelled = true;
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) t.join();
            ch.close();
            pfd.close();
            if (failure[0] != null) throw new Exception(String.valueOf(failure[0].getMessage()));
            if (it.cancelled) throw new Exception("Cancelled");

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(out, done, null, null);
            it.status = "Complete";
            it.finished = true;
        } catch (Throwable e) {
            if (out != null) {
                try { cr.delete(out, null, null); } catch (Throwable ignored) { }
            }
            it.status = "Error: " + e.getMessage();
            it.finished = true;
        }
    }

    private static void pull(Item it, FileChannel ch, long from, long to) throws Exception {
        long pos = Math.max(0, from);
        int attempts = 0;
        while (!it.cancelled) {
            try {
                HttpURLConnection c = open(it.url, from < 0 ? -1 : pos, to, 8);
                int code = c.getResponseCode();
                if (from >= 0 && code != 206) throw new Exception("Server stopped allowing ranges (" + code + ")");
                if (code != 200 && code != 206) throw new Exception("Server answered " + code);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while (!it.cancelled && (r = in.read(buf)) > 0) {
                        ch.write(ByteBuffer.wrap(buf, 0, r), pos);
                        pos += r;
                        it.done.addAndGet(r);
                    }
                }
                c.disconnect();
                if (from < 0 || pos > to) return;
                throw new Exception("Connection ended early");
            } catch (Exception e) {
                if (++attempts > 6) throw e;
                Thread.sleep(1500L * attempts);
            }
        }
    }
}
