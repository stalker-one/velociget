package io.encodal.idm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs downloads in the background, shows one progress notification and one notification per finished file. */
public class DownloadService extends Service {
    static final String ADD = "add", RESUME = "resume";
    static final List<Item> ITEMS = new CopyOnWriteArrayList<>();
    private static final String CH_PROGRESS = "progress", CH_DONE = "done";
    private static final int ID = 7;
    private final AtomicInteger running = new AtomicInteger();
    private final Object lock = new Object();
    private boolean watching;

    static Item find(int id) {
        for (Item i : ITEMS) if (i.id == id) return i;
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Store.load(this, ITEMS);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(ID, progress("Starting…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        if (intent != null && ADD.equals(intent.getAction()) && intent.getStringExtra("url") != null) {
            Item it = new Item(Store.NEXT_ID.getAndIncrement(), intent.getStringExtra("url"));
            it.connections = intent.getIntExtra("connections", 8);
            ITEMS.add(0, it);
            launch(it);
        } else if (intent != null && RESUME.equals(intent.getAction())) {
            Item it = find(intent.getIntExtra("id", -1));
            if (it != null && !it.active()) launch(it);
        }
        synchronized (lock) {
            if (running.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void launch(final Item it) {
        synchronized (lock) {
            running.incrementAndGet();
            it.state = Item.DOWNLOADING;
            if (!watching) {
                watching = true;
                new Thread(this::watch).start();
            }
        }
        new Thread(() -> {
            try {
                Segmented.run(getApplicationContext(), it);
            } finally {
                running.decrementAndGet();
                Store.save(getApplicationContext(), ITEMS);
                if (it.state == Item.DONE) finished(it, true);
                else if (it.state == Item.ERROR) finished(it, false);
            }
        }).start();
    }

    private void watch() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        int tick = 0;
        while (true) {
            synchronized (lock) {
                if (running.get() == 0) {
                    watching = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    return;
                }
            }
            long done = 0, total = 0;
            int count = 0;
            String text = "";
            for (Item i : ITEMS) {
                if (i.state == Item.DOWNLOADING) {
                    count++;
                    done += i.done();
                    total += Math.max(0, i.total);
                    text = i.name.isEmpty() ? i.url : i.name;
                }
            }
            if (count > 1) text = count + " downloads";
            nm.notify(ID, progress(text, (int) Math.min(Integer.MAX_VALUE, total / 1024), (int) Math.min(Integer.MAX_VALUE, done / 1024)));
            if (++tick % 3 == 0) Store.save(getApplicationContext(), ITEMS);
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        }
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progress(String text, int maxKb, int doneKb) {
        Notification.Builder b = new Notification.Builder(this, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("IDM By Encodal")
                .setContentText(text)
                .setContentIntent(open())
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (maxKb > 0) b.setProgress(maxKb, doneKb, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    private void finished(Item it, boolean ok) {
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? "Download complete" : "Download failed")
                .setContentText(ok ? it.name : (it.name.isEmpty() ? it.url : it.name) + " — " + it.error)
                .setContentIntent(open())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(100 + it.id, n);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
