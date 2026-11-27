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
    static final String TOGGLE = "io.encodal.idm.YT_TOGGLE", CLOSE = "io.encodal.idm.YT_CLOSE";
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

    private static Notification build(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getBroadcast(c, 1, new Intent(TOGGLE).setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getBroadcast(c, 2, new Intent(CLOSE).setPackage(c.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("IDM By Encodal · background playback")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play", toggle).build())
                .addAction(new Notification.Action.Builder(null, "Close", close).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1))
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
