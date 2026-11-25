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
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Segmented HTTP download: Range requests on several connections, written into the Downloads folder, with pause and resume. */
final class Segmented {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) IDMByEncodal/0.1";

    private static HttpURLConnection open(String url, long from, long to, int redirects, String headers) throws Exception {
        for (int i = 0; i <= redirects; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (String line : headers.split("\n")) {
                    int i = line.indexOf(':');
                    if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
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

    /** Ask the server for size and Range support, create the file in Downloads and split it into segments. */
    private static void prepare(Context ctx, Item it) throws Exception {
        HttpURLConnection probe = open(it.url, 0, 0, 8, it.headers);
        int code = probe.getResponseCode();
        long total = -1;
        boolean ranges = false;
        if (code == 206) {
            String cr0 = probe.getHeaderField("Content-Range");
            if (cr0 != null && cr0.contains("/")) {
                try { total = Long.parseLong(cr0.substring(cr0.indexOf('/') + 1).trim()); ranges = total > 0; } catch (Exception ignored) { }
            }
        } else if (code == 200) {
            total = probe.getContentLengthLong();
        } else {
            probe.disconnect();
            throw new Exception(code == 403 ? "The site refused the download (403)" : code == 404 ? "File not found (404)" : "Server answered " + code);
        }
        String name = (it.name.isEmpty() ? nameFrom(probe, it.url) : it.name).replaceAll("[\\\\/:*?\"<>|]+", "_");
        String ct = probe.getContentType();
        String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
        probe.disconnect();
        if (mime.startsWith("text/html")) throw new Exception("This link is a web page, not a file");

        int n = (ranges && total > 1_000_000) ? Math.max(1, Math.min(32, it.connections)) : 1;
        it.resumable = ranges;
        it.name = name;
        it.mime = mime;
        it.total = total;
        it.connections = n;

        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder(ctx));
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri out = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("Cannot create the file in Downloads");
        it.uri = out.toString();

        long[] s = new long[n], e = new long[n];
        long seg = ranges ? (total + n - 1) / n : 0;
        for (int i = 0; i < n; i++) {
            s[i] = ranges ? i * seg : 0;
            e[i] = ranges ? Math.min(total - 1, (i + 1) * seg - 1) : Long.MAX_VALUE;
        }
        AtomicLongArray pos = new AtomicLongArray(n);
        for (int i = 0; i < n; i++) pos.set(i, s[i]);
        it.segStart = s;
        it.segEnd = e;
        it.segPos = pos;
        it.singleDone = 0;
    }

    static void run(Context ctx, Item it) {
        ContentResolver cr = ctx.getContentResolver();
        it.stop = false;
        it.cancel = false;
        it.error = "";
        it.state = Item.DOWNLOADING;
        try {
            if (it.segStart == null || it.uri.isEmpty()) prepare(ctx, it);
            if (!it.resumable) {
                // a server without ranges can only start over
                for (int i = 0; i < it.segPos.length(); i++) it.segPos.set(i, it.segStart[i]);
                it.singleDone = 0;
            }
            final Uri uri = Uri.parse(it.uri);
            final ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new Exception("Cannot open the file for writing");
            final FileChannel ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
            if (!it.resumable) ch.truncate(0);

            final int n = it.segStart.length;
            final Throwable[] failure = new Throwable[1];
            Thread[] ts = new Thread[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                ts[i] = new Thread(() -> {
                    try {
                        pull(it, ch, idx);
                    } catch (Throwable e) {
                        if (failure[0] == null) failure[0] = e;
                        it.stop = true;
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) t.join();
            ch.close();
            pfd.close();

            if (it.cancel) {
                try { cr.delete(uri, null, null); } catch (Throwable ignored) { }
                it.state = Item.CANCELED;
            } else if (failure[0] != null) {
                it.error = String.valueOf(failure[0].getMessage());
                it.state = Item.ERROR;
            } else if (it.stop) {
                it.state = Item.PAUSED;
            } else {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                it.state = Item.DONE;
                it.finishedAt = System.currentTimeMillis();
            }
        } catch (Throwable e) {
            if (it.cancel) {
                try { if (!it.uri.isEmpty()) cr.delete(Uri.parse(it.uri), null, null); } catch (Throwable ignored) { }
                it.state = Item.CANCELED;
            } else {
                it.error = String.valueOf(e.getMessage());
                it.state = Item.ERROR;
            }
        }
    }

    private static void pull(Item it, FileChannel ch, int idx) throws Exception {
        final boolean ranged = it.resumable;
        long pos = it.segPos.get(idx);
        final long end = it.segEnd[idx];
        int attempts = 0;
        final byte[] buf = new byte[64 * 1024];
        final int segs = Math.max(1, it.segStart.length);
        while (!it.stop && (!ranged || pos <= end)) {
            try {
                HttpURLConnection c = open(it.url, ranged ? pos : -1, ranged ? end : -1, 8, it.headers);
                int code = c.getResponseCode();
                if (ranged && code != 206) throw new Exception("The server stopped allowing ranges (" + code + ")");
                if (code != 200 && code != 206) throw new Exception("Server answered " + code);
                long before = pos;
                final long t0 = System.nanoTime();
                long sent = 0;
                try (InputStream in = c.getInputStream()) {
                    int r;
                    while (!it.stop && (r = in.read(buf)) > 0) {
                        ch.write(ByteBuffer.wrap(buf, 0, r), pos);
                        pos += r;
                        it.segPos.set(idx, pos);
                        if (!ranged) it.singleDone = pos;
                        sent += r;
                        long cap = Prefs.limitBps / segs;
                        if (cap > 0) {
                            long aheadMs = (sent * 1000L / cap) - (System.nanoTime() - t0) / 1000000L;
                            if (aheadMs > 2) Thread.sleep(aheadMs);
                        }
                    }
                }
                c.disconnect();
                if (pos > before) attempts = 0;
                if (it.stop) return;
                if (!ranged) return;        // the whole file arrived
                if (pos > end) return;
                throw new Exception("Connection ended early");
            } catch (Exception e) {
                if (it.stop) return;
                if (++attempts > 6) throw e;
                if (!ranged) { pos = 0; it.segPos.set(idx, 0); it.singleDone = 0; ch.truncate(0); }
                Thread.sleep(1200L * attempts);
            }
        }
    }
}
