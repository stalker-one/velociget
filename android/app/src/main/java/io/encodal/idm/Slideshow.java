package io.encodal.idm;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a slideshow video on the phone from a TikTok photo post: one picture + sound, or many pictures that
 * slide and zoom automatically with the sound. Pictures are drawn into an H.264 video, the sound is converted
 * to AAC, both are joined into one MP4 in the downloads folder.
 */
final class Slideshow {
    interface Progress { void on(String text, int percent); }

    private static final int FPS = 24;

    static Uri make(Context ctx, List<String> imgUrls, String imgHdr, String audioUrl, String audioHdr, String name, Progress p) throws Exception {
        File dir = new File(ctx.getCacheDir(), "slides");
        dir.mkdirs();
        for (File f : dir.listFiles()) f.delete();

        // 1. pictures
        List<Bitmap> pics = new ArrayList<>();
        for (int i = 0; i < imgUrls.size(); i++) {
            p.on("Loading picture " + (i + 1) + " of " + imgUrls.size() + "…", 2 + 18 * i / imgUrls.size());
            try {
                byte[] b = bytes(imgUrls.get(i), imgHdr);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int ss = 1;
                while (Math.max(o.outWidth, o.outHeight) / ss > 1600) ss *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = ss;
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bm != null) pics.add(bm);
            } catch (Exception ignored) { }
        }
        if (pics.isEmpty()) throw new Exception("The pictures could not be loaded");

        // 2. sound (optional)
        File aac = null;
        long audioUs = 0;
        if (audioUrl != null && !audioUrl.isEmpty()) {
            try {
                p.on("Loading the sound…", 22);
                File raw = new File(dir, "sound.bin");
                try (FileOutputStream fo = new FileOutputStream(raw)) { fo.write(bytes(audioUrl, audioHdr)); }
                aac = new File(dir, "sound.m4a");
                int n = pics.size();
                // length of the video: many pictures -> 2.5..5 s each; one picture -> as long as the sound (max 60 s)
                long[] res = toAac(raw, aac, n == 1 ? 60_000_000L : (long) n * 5_000_000L, p);
                audioUs = res[0];
                if (audioUs <= 0) aac = null;
            } catch (Exception e) {
                aac = null;
            }
        }

        // 3. timeline
        int n = pics.size();
        double total, per;
        if (n == 1) {
            total = aac != null ? Math.min(60, audioUs / 1e6) : 8;
            total = Math.max(total, 4);
            per = total;
        } else {
            double a = aac != null ? audioUs / 1e6 : n * 3.5;
            per = Math.max(2.5, Math.min(5, a / n));
            total = n * per;
        }

        // 4. video
        boolean wide = pics.get(0).getWidth() > pics.get(0).getHeight();
        int W = wide ? 1280 : 720, H = wide ? 720 : 1280;
        File vid = new File(dir, "video.mp4");
        encodeVideo(pics, W, H, total, per, vid, p);

        // 5. join
        p.on("Joining picture and sound…", 94);
        File out = new File(dir, "slideshow.mp4");
        join(vid, aac, out, (long) (total * 1e6));

        // 6. into the downloads folder
        p.on("Saving…", 98);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder(ctx));
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri); InputStream is = new java.io.FileInputStream(out)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
        }
        ContentValues d = new ContentValues();
        d.put(MediaStore.Downloads.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, d, null, null);
        for (File f : dir.listFiles()) f.delete();
        p.on("Done", 100);
        return uri;
    }

    // ------------------------------------------------------------------ http

    private static byte[] bytes(String url, String hdr) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", Extractor.UA);
            if (hdr != null) for (String line : hdr.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                return bo.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("Too many redirects");
    }

    // ------------------------------------------------------------------ video

    private static void encodeVideo(List<Bitmap> pics, int W, int H, double total, double per, File out, Progress p) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", W, H);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean started = false, inDone = false, outDone = false;
        int frames = (int) Math.round(total * FPS), frame = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Bitmap canvasBm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(canvasBm);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int[] px = new int[W * H];
        byte[] yb = new byte[W * H], ub = new byte[W * H / 4], vb = new byte[W * H / 4];
        try {
            while (!outDone) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        long pts = frame * 1_000_000L / FPS;
                        if (frame >= frames) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            render(cv, pics, W, H, frame / (double) FPS, per, paint);
                            canvasBm.getPixels(px, 0, W, 0, 0, W, H);
                            yuv(px, W, H, yb, ub, vb);
                            Image img = enc.getInputImage(ii);
                            fill(img, yb, ub, vb, W, H);
                            enc.queueInputBuffer(ii, 0, W * H * 3 / 2, pts, 0);
                            frame++;
                            if (frame % 12 == 0) p.on("Making the video…", 40 + (int) (52.0 * frame / frames));
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    started = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            try { if (started) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }

    private static void render(Canvas cv, List<Bitmap> pics, int W, int H, double t, double per, Paint paint) {
        cv.drawColor(Color.BLACK);
        int n = pics.size();
        int idx = Math.min(n - 1, (int) (t / per));
        double local = t - idx * per;
        double tr = 0.6;
        if (n > 1 && idx > 0 && local < tr) {
            double q = local / tr;
            q = q * q * (3 - 2 * q); // ease
            draw(cv, pics.get(idx - 1), W, H, 1.0 + 0.05 * (per - local + tr) / per, -q * W, paint, 255 - (int) (255 * q));
            draw(cv, pics.get(idx), W, H, 1.0, (1 - q) * W, paint, 255);
        } else {
            draw(cv, pics.get(idx), W, H, 1.0 + 0.05 * Math.min(1, local / per), 0, paint, 255);
        }
    }

    private static void draw(Canvas cv, Bitmap b, int W, int H, double zoom, double dx, Paint paint, int alpha) {
        float s = (float) (Math.min(W / (double) b.getWidth(), H / (double) b.getHeight()) * zoom);
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate((float) ((W - b.getWidth() * s) / 2 + dx), (H - b.getHeight() * s) / 2f);
        paint.setAlpha(alpha);
        cv.drawBitmap(b, m, paint);
    }

    private static void yuv(int[] px, int w, int h, byte[] y, byte[] u, byte[] v) {
        int hw = w / 2;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = px[j * w + i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                y[j * w + i] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int k = (j / 2) * hw + i / 2;
                    u[k] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    v[k] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                }
            }
        }
    }

    private static void fill(Image img, byte[] y, byte[] u, byte[] v, int w, int h) {
        Image.Plane[] pl = img.getPlanes();
        plane(pl[0], y, w, h);
        plane(pl[1], u, w / 2, h / 2);
        plane(pl[2], v, w / 2, h / 2);
    }

    private static void plane(Image.Plane p, byte[] src, int w, int h) {
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        if (ps == 1) {
            for (int r = 0; r < h; r++) {
                b.position(r * rs);
                b.put(src, r * w, w);
            }
        } else {
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) b.put(r * rs + c * ps, src[r * w + c]);
        }
    }

    // ------------------------------------------------------------------ sound

    /** Any audio file (mp3, m4a, aac…) -> AAC in an .m4a. Returns {durationUs}. */
    private static long[] toAac(File in, File out, long limitUs, Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        int ti = -1;
        MediaFormat inF = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat tf = ex.getTrackFormat(i);
            String m = tf.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { ti = i; inF = tf; break; }
        }
        if (ti < 0) { ex.release(); return new long[]{0}; }
        ex.selectTrack(ti);
        long srcDur = inF.containsKey(MediaFormat.KEY_DURATION) ? inF.getLong(MediaFormat.KEY_DURATION) : 0;
        long want = srcDur > 0 ? Math.min(srcDur, limitUs) : limitUs;

        MediaCodec dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME));
        dec.configure(inF, null, null, 0);
        dec.start();
        MediaCodec enc = null;
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1, sr = 44100, ch = 2;
        boolean muxStarted = false, decIn = false, decOut = false, encInEos = false, encDone = false;
        long samples = 0, lastPts = 0;
        MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), ei = new MediaCodec.BufferInfo();
        try {
            while (!encDone) {
                if (!decIn) {
                    int ii = dec.dequeueInputBuffer(5_000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decIn = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                if (!decOut) {
                    int oi = dec.dequeueOutputBuffer(di, 5_000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (enc == null) {
                            MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch);
                            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                            ef.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
                            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
                            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            enc.start();
                        }
                    } else if (oi >= 0) {
                        if (enc == null) {
                            MediaFormat of = dec.getOutputFormat();
                            sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch);
                            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                            ef.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
                            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
                            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            enc.start();
                        }
                        ByteBuffer pcm = dec.getOutputBuffer(oi);
                        pcm.position(di.offset);
                        pcm.limit(di.offset + di.size);
                        while (pcm.hasRemaining() && !encInEos) {
                            int eix = enc.dequeueInputBuffer(5_000);
                            if (eix < 0) {
                                // let the encoder breathe
                                long[] r = drain(enc, mux, ei, track, muxStarted);
                                track = (int) r[0];
                                muxStarted = r[1] == 1;
                                continue;
                            }
                            ByteBuffer eb = enc.getInputBuffer(eix);
                            eb.clear();
                            int nbytes = Math.min(eb.remaining(), pcm.remaining());
                            ByteBuffer sl = pcm.slice();
                            sl.limit(nbytes);
                            eb.put(sl);
                            pcm.position(pcm.position() + nbytes);
                            long pts = samples * 1_000_000L / sr;
                            samples += nbytes / (2L * ch);
                            lastPts = pts;
                            if (pts >= want) {
                                enc.queueInputBuffer(eix, 0, nbytes, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                encInEos = true;
                            } else {
                                enc.queueInputBuffer(eix, 0, nbytes, pts, 0);
                            }
                        }
                        dec.releaseOutputBuffer(oi, false);
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || encInEos) decOut = true;
                    }
                }
                if (enc != null) {
                    if (decOut && !encInEos) {
                        int eix = enc.dequeueInputBuffer(5_000);
                        if (eix >= 0) {
                            enc.queueInputBuffer(eix, 0, 0, samples * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInEos = true;
                        }
                    }
                    int oi;
                    while ((oi = enc.dequeueOutputBuffer(ei, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        } else if (oi >= 0) {
                            ByteBuffer ob = enc.getOutputBuffer(oi);
                            if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                            if (ei.size > 0 && muxStarted) {
                                ob.position(ei.offset);
                                ob.limit(ei.offset + ei.size);
                                mux.writeSampleData(track, ob, ei);
                            }
                            enc.releaseOutputBuffer(oi, false);
                            if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { encDone = true; break; }
                        }
                    }
                }
                if (decOut && enc == null) break;
            }
        } finally {
            try { dec.stop(); } catch (Exception ignored) { }
            dec.release();
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); }
            try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
            mux.release();
            ex.release();
        }
        return new long[]{Math.max(lastPts, 0)};
    }

    /** Pulls finished AAC out of the encoder. Returns {track, started}. */
    private static long[] drain(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo ei, int track, boolean started) {
        int oi;
        while ((oi = enc.dequeueOutputBuffer(ei, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.getOutputFormat());
                mux.start();
                started = true;
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                if (ei.size > 0 && started) {
                    ob.position(ei.offset);
                    ob.limit(ei.offset + ei.size);
                    mux.writeSampleData(track, ob, ei);
                }
                enc.releaseOutputBuffer(oi, false);
            }
        }
        return new long[]{track, started ? 1 : 0};
    }

    // ------------------------------------------------------------------ join

    private static void join(File video, File audio, File out, long limitUs) throws Exception {
        MediaExtractor vx = new MediaExtractor();
        vx.setDataSource(video.getAbsolutePath());
        vx.selectTrack(0);
        MediaExtractor ax = null;
        if (audio != null && audio.exists() && audio.length() > 0) {
            ax = new MediaExtractor();
            ax.setDataSource(audio.getAbsolutePath());
            ax.selectTrack(0);
        }
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vt = mux.addTrack(vx.getTrackFormat(0));
        int at = ax != null ? mux.addTrack(ax.getTrackFormat(0)) : -1;
        mux.start();
        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean vDone = false, aDone = ax == null;
        while (!vDone || !aDone) {
            boolean useV = !vDone && (aDone || vx.getSampleTime() <= ax.getSampleTime());
            MediaExtractor e = useV ? vx : ax;
            int sz = e.readSampleData(buf, 0);
            long t = e.getSampleTime();
            if (sz < 0 || (!useV && t > limitUs)) {
                if (useV) vDone = true; else aDone = true;
                continue;
            }
            bi.offset = 0;
            bi.size = sz;
            bi.presentationTimeUs = t;
            bi.flags = (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mux.writeSampleData(useV ? vt : at, buf, bi);
            e.advance();
        }
        mux.stop();
        mux.release();
        vx.release();
        if (ax != null) ax.release();
    }
}
