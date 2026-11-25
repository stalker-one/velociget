package io.encodal.idm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLongArray;

/** One download and everything needed to pause, resume and show it. */
final class Item {
    static final int QUEUED = 0, DOWNLOADING = 1, PAUSED = 2, DONE = 3, ERROR = 4, CANCELED = 5;

    final int id;
    String url;
    volatile String name = "";
    volatile String mime = "application/octet-stream";
    volatile long total = -1;
    volatile int state = QUEUED;
    volatile String error = "";
    volatile int connections = 8;
    volatile boolean resumable;
    volatile String uri = "";
    volatile String headers = ""; // extra request headers (cookies, referer) needed by some sites
    volatile long[] segStart;
    volatile long[] segEnd;
    volatile AtomicLongArray segPos;
    long created = System.currentTimeMillis();
    long finishedAt;
    volatile boolean stop;
    volatile boolean cancel;
    volatile long singleDone; // used when the server does not allow ranges

    // screen-only values
    long lastDone;
    long lastTime;
    double speed;

    Item(int id, String url) {
        this.id = id;
        this.url = url;
    }

    long done() {
        AtomicLongArray p = segPos;
        long[] s = segStart;
        if (p == null || s == null) return 0;
        if (!resumable) return singleDone;
        long d = 0;
        for (int i = 0; i < s.length; i++) d += Math.max(0, p.get(i) - s[i]);
        return d;
    }

    boolean active() { return state == DOWNLOADING || state == QUEUED; }

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id).put("url", url).put("name", name).put("mime", mime).put("total", total)
                    .put("state", state).put("error", error).put("conns", connections).put("resumable", resumable)
                    .put("uri", uri).put("headers", headers).put("created", created).put("finished", finishedAt);
            if (segStart != null && segPos != null) {
                JSONArray a = new JSONArray(), b = new JSONArray(), c = new JSONArray();
                for (int i = 0; i < segStart.length; i++) { a.put(segStart[i]); b.put(segEnd[i]); c.put(segPos.get(i)); }
                o.put("ss", a).put("se", b).put("sp", c);
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static Item fromJson(JSONObject o) {
        try {
            Item it = new Item(o.getInt("id"), o.getString("url"));
            it.name = o.optString("name");
            it.mime = o.optString("mime", "application/octet-stream");
            it.total = o.optLong("total", -1);
            it.state = o.optInt("state");
            it.error = o.optString("error");
            it.connections = o.optInt("conns", 8);
            it.resumable = o.optBoolean("resumable");
            it.uri = o.optString("uri");
            it.headers = o.optString("headers");
            it.created = o.optLong("created");
            it.finishedAt = o.optLong("finished");
            if (o.has("ss")) {
                JSONArray a = o.getJSONArray("ss"), b = o.getJSONArray("se"), c = o.getJSONArray("sp");
                int n = a.length();
                it.segStart = new long[n];
                it.segEnd = new long[n];
                it.segPos = new AtomicLongArray(n);
                for (int i = 0; i < n; i++) { it.segStart[i] = a.getLong(i); it.segEnd[i] = b.getLong(i); it.segPos.set(i, c.getLong(i)); }
            }
            // a download that was running when the app was closed can be continued
            if (it.state == DOWNLOADING || it.state == QUEUED) it.state = PAUSED;
            return it;
        } catch (Exception e) {
            return null;
        }
    }
}
