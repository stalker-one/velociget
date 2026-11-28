package io.encodal.idm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the app (and so the YouTube page's sound) alive while the screen is off or another app is open. */
public class BgPlayService extends Service {
    static final String TOGGLE = "io.encodal.idm.YT_TOGGLE", CLOSE = "io.encodal.idm.YT_CLOSE",
            NEXT = "io.encodal.idm.YT_NEXT", MUTE = "io.encodal.idm.YT_MUTE", LOOP = "io.encodal.idm.YT_LOOP", PIP = "io.encodal.idm.YT_PIP";
    /** "audio" = background audio only (screen free / app closed); "pip" = floating mini window with video. */
    static volatile String mode = "audio";
    static volatile boolean muted, looping;
    private static final String CH = "idm_play";
    private static final int ID = 7711;
    static volatile String title = "YouTube";
    static volatile boolean playing = true;
    static volatile Class<?> target = YouTubeActivity.class;
    private android.os.PowerManager.WakeLock lock;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, BgPlayService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BgPlayService.class));
    }

    static void refresh(Context c, String t, boolean p) {
        title = t == null || t.isEmpty() ? "YouTube" : t;
        playing = p;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ID, build(c));
    }

    static void state(Context c, String m, boolean mu, boolean lp) {
        mode = m; muted = mu; looping = lp;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ID, build(c));
    }

    private static PendingIntent bc(Context c, int code, String act) {
        return PendingIntent.getBroadcast(c, code, new Intent(act).setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action act(Context c, int res, String label, PendingIntent pi) {
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, res), label, pi).build();
    }

    private static Notification build(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean pip = "pip".equals(mode);
        return new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(pip ? "Mini window playing" : "Background playing · audio only")
                .setSubText("IDM By Encodal")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(act(c, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", bc(c, 1, TOGGLE)))
                .addAction(act(c, android.R.drawable.ic_media_next, "Next", bc(c, 3, NEXT)))
                .addAction(act(c, muted ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_lock_silent_mode_off, muted ? "Unmute" : "Mute", bc(c, 4, MUTE)))
                .addAction(act(c, android.R.drawable.ic_menu_rotate, looping ? "Loop: on" : "Loop", bc(c, 5, LOOP)))
                .addAction(act(c, pip ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_slideshow, pip ? "Close" : "Mini window", pip ? bc(c, 6, CLOSE) : PendingIntent.getActivity(c, 7,
                        new Intent(c, target).putExtra("pipnow", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Background playback", NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (lock == null) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "idm:youtube");
            lock.acquire(6 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
