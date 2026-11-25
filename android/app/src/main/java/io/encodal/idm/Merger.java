package io.encodal.idm;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;

/** Puts a separate audio file into a video-only download (what ffmpeg does on the PC), using Android's own media tools. */
final class Merger {
    private Merger() { }

    private static void fetch(String url, String headers, File to) throws Exception {
        final long chunk = 8L * 1024 * 1024;
        long pos = 0, total = -1;
        try (FileOutputStream out = new FileOutputStream(to)) {
            while (total < 0 || pos < total) {
                HttpURLConnection c = Segmented.open(url, pos, pos + chunk - 1, 8, headers);
                int code = c.getResponseCode();
                if (code != 206 && code != 200) throw new Exception("Audio server answered " + code);
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) { }
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) { out.write(b, 0, n); pos += n; }
                }
                c.disconnect();
                if (code == 200 || total < 0) break; // no ranges: the whole file came at once
            }
        }
    }

    private static int track(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    static void merge(Context ctx, Item it) throws Exception {
        File audio = new File(ctx.getCacheDir(), "a" + it.id + ".bin");
        File out = new File(ctx.getCacheDir(), "m" + it.id + ".bin");
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = Uri.parse(it.uri);
        try {
            fetch(it.audioUrl, it.audioHeaders, audio);

            try (ParcelFileDescriptor vp = cr.openFileDescriptor(uri, "r")) {
                MediaExtractor ve = new MediaExtractor();
                MediaExtractor ae = new MediaExtractor();
                MediaMuxer mx = null;
                try {
                    ve.setDataSource(vp.getFileDescriptor());
                    ae.setDataSource(audio.getAbsolutePath());
                    int vt = track(ve, "video/"), at = track(ae, "audio/");
                    if (vt < 0 || at < 0) throw new Exception("no tracks");
                    ve.selectTrack(vt);
                    ae.selectTrack(at);
                    boolean webm = it.name.toLowerCase().endsWith(".webm");
                    mx = new MediaMuxer(out.getAbsolutePath(), webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int mv = mx.addTrack(ve.getTrackFormat(vt));
                    int ma = mx.addTrack(ae.getTrackFormat(at));
                    mx.start();

                    ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    long vt0 = ve.getSampleTime(), at0 = ae.getSampleTime();
                    while (vt0 >= 0 || at0 >= 0) {
                        boolean useV = at0 < 0 || (vt0 >= 0 && vt0 <= at0);
                        MediaExtractor ex = useV ? ve : ae;
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            if (useV) vt0 = -1; else at0 = -1;
                            continue;
                        }
                        bi.offset = 0;
                        bi.size = n;
                        bi.presentationTimeUs = ex.getSampleTime();
                        bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mx.writeSampleData(useV ? mv : ma, buf, bi);
                        ex.advance();
                        if (useV) vt0 = ex.getSampleTime(); else at0 = ex.getSampleTime();
                    }
                    mx.stop();
                } finally {
                    try { if (mx != null) mx.release(); } catch (Exception ignored) { }
                    ve.release();
                    ae.release();
                }
            }

            try (InputStream in = new FileInputStream(out); OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) throw new Exception("cannot write");
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
        } finally {
            audio.delete();
            out.delete();
        }
    }
}
