package io.encodal.idm;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Turns a joined HLS stream (TS or fragmented MP4) into a normal MP4, without re-encoding. */
final class Remux {
    private Remux() { }

    static boolean toMp4(File in, File out) {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mx = null;
        boolean ok = false;
        try {
            ex.setDataSource(in.getAbsolutePath());
            int tc = ex.getTrackCount();
            int[] map = new int[tc];
            Arrays.fill(map, -1);
            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int added = 0;
            for (int i = 0; i < tc; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) {
                    map[i] = mx.addTrack(f);
                    ex.selectTrack(i);
                    added++;
                }
            }
            if (added == 0) return false;
            mx.start();
            ByteBuffer buf = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                int ti = ex.getSampleTrackIndex();
                if (ti >= 0 && ti < tc && map[ti] >= 0) {
                    bi.offset = 0;
                    bi.size = n;
                    bi.presentationTimeUs = ex.getSampleTime();
                    bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mx.writeSampleData(map[ti], buf, bi);
                }
                ex.advance();
            }
            mx.stop();
            ok = true;
        } catch (Throwable e) {
            ok = false;
        } finally {
            try { if (mx != null) mx.release(); } catch (Throwable ignored) { }
            ex.release();
            if (!ok) out.delete();
        }
        return ok;
    }
}
