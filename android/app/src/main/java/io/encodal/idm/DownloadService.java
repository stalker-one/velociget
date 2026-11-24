package io.encodal.idm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps downloads running while the app is in the background and shows their progress in a notification. */
public class DownloadService extends Service {
    static final List<Segmented.Item> ITEMS = new CopyOnWriteArrayList<>();
    private static final String CHANNEL = "downloads";
    private static final int ID = 7;
    private volatile int running;
    private volatile boolean watching;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, build("Starting…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (intent != null && intent.getStringExtra("url") != null) {
            final Segmented.Item it = new Segmented.Item();
            it.url = intent.getStringExtra("url");
            final int conns = intent.getIntExtra("connections", 8);
            ITEMS.add(0, it);
            running++;
            new Thread(() -> {
                try {
                    Segmented.run(getApplicationContext(), it, conns);
                } finally {
                    running--;
                }
            }).start();
        }
        if (!watching) {
            watching = true;
            new Thread(this::watch).start();
        }
        return START_NOT_STICKY;
    }

    private void watch() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        while (running > 0) {
            long done = 0, total = 0;
            String text = "";
            for (Segmented.Item i : ITEMS) {
                if (!i.finished) {
                    done += i.done.get();
                    total += Math.max(0, i.total);
                    text = i.name.isEmpty() ? i.url : i.name;
                }
            }
            nm.notify(ID, build(text, (int) Math.min(Integer.MAX_VALUE, total / 1024), (int) Math.min(Integer.MAX_VALUE, done / 1024)));
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        }
        watching = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification build(String text, int maxKb, int doneKb) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("IDM By Encodal")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (maxKb > 0) b.setProgress(maxKb, doneKb, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
