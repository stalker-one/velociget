package io.encodal.idm;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Map;

/**
 * Video surface on top of MediaPlayer (same calls as VideoView). Unlike VideoView it does NOT release the player when the
 * screen goes off or the app is left: the picture is just detached and the sound keeps going.
 */
public class PlayerView extends SurfaceView implements SurfaceHolder.Callback {
    private MediaPlayer mp;
    private boolean prepared, wantPlay, surfaceReady;
    private int vw, vh, seekAfter = -1, mode;

    /** 0 fit, 1 fill (crop), 2 stretch, 3 16:9, 4 4:3 */
    public void setMode(int m) { mode = m; requestLayout(); }
    public int getMode() { return mode; }
    private Uri uri;
    private Map<String, String> headers;
    private String path;
    private MediaPlayer.OnPreparedListener onPrepared;
    private MediaPlayer.OnCompletionListener onCompletion;
    private MediaPlayer.OnErrorListener onError;
    private MediaPlayer.OnInfoListener onInfo;

    public PlayerView(Context c) {
        super(c);
        getHolder().addCallback(this);
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) { onPrepared = l; }
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) { onCompletion = l; }
    public void setOnErrorListener(MediaPlayer.OnErrorListener l) { onError = l; }
    public void setOnInfoListener(MediaPlayer.OnInfoListener l) { onInfo = l; }

    public void setVideoURI(Uri u) { setVideoURI(u, null); }

    public void setVideoURI(Uri u, Map<String, String> h) {
        uri = u; headers = h; path = null;
        open();
    }

    public void setVideoPath(String p) {
        path = p; uri = null; headers = null;
        open();
    }

    private void open() {
        release();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setScreenOnWhilePlaying(true);
            if (path != null) mp.setDataSource(path); else mp.setDataSource(getContext(), uri, headers);
            mp.setOnVideoSizeChangedListener((m, w, h) -> { vw = w; vh = h; requestLayout(); });
            mp.setOnPreparedListener(m -> {
                prepared = true;
                vw = m.getVideoWidth(); vh = m.getVideoHeight();
                requestLayout();
                if (onPrepared != null) onPrepared.onPrepared(m);
                if (seekAfter > 0) { m.seekTo(seekAfter); seekAfter = -1; }
            });
            mp.setOnCompletionListener(m -> { wantPlay = false; if (onCompletion != null) onCompletion.onCompletion(m); });
            mp.setOnErrorListener((m, w, e) -> { prepared = false; return onError != null && onError.onError(m, w, e); });
            mp.setOnInfoListener((m, w, e) -> onInfo != null && onInfo.onInfo(m, w, e));
            if (surfaceReady) mp.setDisplay(getHolder());
            mp.prepareAsync();
        } catch (Exception e) {
            if (onError != null) onError.onError(mp, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    private void release() {
        prepared = false;
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
    }

    public void start() {
        wantPlay = true;
        if (mp != null && prepared) { try { mp.start(); } catch (Exception ignored) { } }
    }

    public void pause() {
        wantPlay = false;
        if (mp != null && prepared) { try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { } }
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public int getDuration() {
        try { return mp != null && prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public int getCurrentPosition() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public void seekTo(int ms) {
        if (mp != null && prepared) { try { mp.seekTo(ms); } catch (Exception ignored) { } } else seekAfter = ms;
    }

    public void stopPlayback() { release(); }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (mp != null) { try { mp.setDisplay(h); } catch (Exception ignored) { } }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        if (mp != null) { try { mp.setDisplay(null); } catch (Exception ignored) { } } // sound carries on without a picture
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int sw = MeasureSpec.getSize(wSpec), sh = MeasureSpec.getSize(hSpec);
        int w = getDefaultSize(vw, wSpec), h = getDefaultSize(vh, hSpec);
        if (vw > 0 && vh > 0) {
            float ar = mode == 3 ? 16f / 9f : mode == 4 ? 4f / 3f : (float) vw / vh;
            if (mode == 2) { w = sw; h = sh; }
            else if (mode == 1) { if (sw * 1f / sh > ar) { w = sw; h = (int) (sw / ar); } else { h = sh; w = (int) (sh * ar); } }
            else { if (sw / ar > sh) { h = sh; w = (int) (sh * ar); } else { w = sw; h = (int) (sw / ar); } }
        }
        setMeasuredDimension(w, h);
    }
}
