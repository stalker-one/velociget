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

    // ---------------- VLC engine (all formats) ----------------
    private org.videolan.libvlc.LibVLC lib;
    private org.videolan.libvlc.MediaPlayer vlc;
    private android.os.ParcelFileDescriptor vlcPfd;
    private boolean vlcOn, vlcReady, vlcAttached;
    private int vlcStart, vlcLen;
    private Runnable vlcReadyCb, vlcErrorCb;

    public boolean isVlc() { return vlcOn; }
    public int getVideoW() { return vw; }
    public int getVideoH() { return vh; }

    /** Plays the file with libVLC (any container / codec). Returns false if the engine could not start. */
    public boolean playVlc(Uri u, Map<String, String> h, int startMs, Runnable ready, Runnable error) {
        try {
            release();
            vlcReadyCb = ready; vlcErrorCb = error; vlcStart = startMs; vlcReady = false;
            java.util.ArrayList<String> o = new java.util.ArrayList<>();
            o.add("--network-caching=3000");
            o.add("--audio-time-stretch");
            o.add("--no-drop-late-frames");
            o.add("--no-skip-frames");
            lib = new org.videolan.libvlc.LibVLC(getContext().getApplicationContext(), o);
            vlc = new org.videolan.libvlc.MediaPlayer(lib);
            org.videolan.libvlc.Media m;
            String sc = u.getScheme();
            if ("content".equals(sc)) {
                vlcPfd = getContext().getContentResolver().openFileDescriptor(u, "r");
                m = new org.videolan.libvlc.Media(lib, vlcPfd.getFileDescriptor());
            } else if (sc == null || "file".equals(sc)) {
                m = new org.videolan.libvlc.Media(lib, u.getPath() != null && sc == null ? Uri.fromFile(new java.io.File(u.toString())) : u);
            } else {
                m = new org.videolan.libvlc.Media(lib, u);
                if (h != null) for (Map.Entry<String, String> e : h.entrySet()) {
                    String k = e.getKey().toLowerCase();
                    if (k.equals("user-agent")) m.addOption(":http-user-agent=" + e.getValue());
                    else if (k.equals("referer") || k.equals("referrer")) m.addOption(":http-referrer=" + e.getValue());
                    else if (k.equals("cookie")) m.addOption(":http-cookie=" + e.getValue());
                }
            }
            m.setHWDecoderEnabled(true, false);
            vlc.setMedia(m);
            m.release();
            vlcOn = true;
            vlc.setEventListener(ev -> {
                switch (ev.type) {
                    case org.videolan.libvlc.MediaPlayer.Event.Playing:
                        if (!vlcReady) {
                            vlcReady = true;
                            vlcLen = (int) vlc.getLength();
                            if (vlcStart > 0) vlc.setTime(vlcStart);
                            post(() -> { if (vlcReadyCb != null) vlcReadyCb.run(); });
                        }
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.Vout:
                        try {
                            org.videolan.libvlc.interfaces.IMedia.VideoTrack t = vlc.getCurrentVideoTrack();
                            if (t != null && t.width > 0) { vw = t.width; vh = t.height; post(this::requestLayout); }
                        } catch (Throwable ignored) { }
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.LengthChanged:
                        vlcLen = (int) vlc.getLength();
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        wantPlay = false;
                        post(() -> { if (onCompletion != null) onCompletion.onCompletion(null); });
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                        post(() -> { if (vlcErrorCb != null) vlcErrorCb.run(); });
                        break;
                    default:
                }
            });
            if (surfaceReady) vlcAttach();
            vlc.play();
            wantPlay = true;
            return true;
        } catch (Throwable e) {
            vlcRelease();
            return false;
        }
    }

    private void vlcAttach() {
        if (vlc == null || vlcAttached) return;
        try {
            org.videolan.libvlc.interfaces.IVLCVout v = vlc.getVLCVout();
            v.setVideoView(this);
            v.attachViews();
            v.setWindowSize(Math.max(1, getWidth()), Math.max(1, getHeight()));
            vlcAttached = true;
        } catch (Throwable ignored) { }
    }

    private void vlcDetach() {
        if (vlc == null || !vlcAttached) return;
        try { vlc.getVLCVout().detachViews(); } catch (Throwable ignored) { }
        vlcAttached = false;
    }

    private void vlcRelease() {
        vlcOn = false; vlcReady = false;
        try { if (vlc != null) { vlc.setEventListener(null); vlcDetach(); vlc.stop(); vlc.release(); } } catch (Throwable ignored) { }
        try { if (lib != null) lib.release(); } catch (Throwable ignored) { }
        try { if (vlcPfd != null) vlcPfd.close(); } catch (Exception ignored) { }
        vlc = null; lib = null; vlcPfd = null;
    }

    public void vlcRate(float r) { try { if (vlc != null) vlc.setRate(r); } catch (Throwable ignored) { } }
    public void vlcVolume(int pct) { try { if (vlc != null) vlc.setVolume(pct); } catch (Throwable ignored) { } }

    /** names[i] / ids[i] pairs; ids[0] of the subtitle list can be -1 = off. */
    public java.util.List<String> vlcTracks(boolean audio, java.util.List<Integer> ids) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            org.videolan.libvlc.MediaPlayer.TrackDescription[] t = audio ? vlc.getAudioTracks() : vlc.getSpuTracks();
            if (t != null) for (org.videolan.libvlc.MediaPlayer.TrackDescription d : t) { names.add(d.name); ids.add(d.id); }
        } catch (Throwable ignored) { }
        return names;
    }

    public int vlcCurrentTrack(boolean audio) {
        try { return audio ? vlc.getAudioTrack() : vlc.getSpuTrack(); } catch (Throwable e) { return -1; }
    }

    public void vlcSelect(boolean audio, int id) {
        try { if (audio) vlc.setAudioTrack(id); else vlc.setSpuTrack(id); } catch (Throwable ignored) { }
    }

    public void vlcAddSubtitle(Uri u) {
        try { vlc.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, u, true); } catch (Throwable ignored) { }
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
        vlcRelease();
        prepared = false;
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
    }

    public void start() {
        wantPlay = true;
        if (vlcOn) { try { vlc.play(); } catch (Throwable ignored) { } return; }
        if (mp != null && prepared) { try { mp.start(); } catch (Exception ignored) { } }
    }

    public void pause() {
        wantPlay = false;
        if (vlcOn) { try { vlc.pause(); } catch (Throwable ignored) { } return; }
        if (mp != null && prepared) { try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { } }
    }

    public boolean isPlaying() {
        if (vlcOn) { try { return vlc.isPlaying(); } catch (Throwable e) { return false; } }
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public int getDuration() {
        if (vlcOn) { try { long l = vlc.getLength(); return (int) (l > 0 ? l : vlcLen); } catch (Throwable e) { return vlcLen; } }
        try { return mp != null && prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public int getCurrentPosition() {
        if (vlcOn) { try { return (int) Math.max(0, vlc.getTime()); } catch (Throwable e) { return 0; } }
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public void seekTo(int ms) {
        if (vlcOn) { try { vlc.setTime(ms); } catch (Throwable ignored) { } return; }
        if (mp != null && prepared) { try { mp.seekTo(ms); } catch (Exception ignored) { } } else seekAfter = ms;
    }

    public void stopPlayback() { release(); }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        vlcAttach();
        if (mp != null) { try { mp.setDisplay(h); } catch (Exception ignored) { } }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        if (vlcOn && vlc != null) { try { vlc.getVLCVout().setWindowSize(w, hh); } catch (Throwable ignored) { } }
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        vlcDetach();
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
