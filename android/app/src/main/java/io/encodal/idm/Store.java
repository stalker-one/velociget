package io.encodal.idm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the list of downloads between runs of the app. */
final class Store {
    static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static boolean loaded;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("downloads", Context.MODE_PRIVATE);
    }

    static synchronized void load(Context c, List<Item> into) {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("items", "[]"));
            int max = 0;
            for (int i = 0; i < a.length(); i++) {
                Item it = Item.fromJson(a.getJSONObject(i));
                if (it == null) continue;
                max = Math.max(max, it.id);
                into.add(it);
            }
            NEXT_ID.set(max + 1);
        } catch (Exception ignored) { }
    }

    static synchronized void save(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                if (it.state == Item.CANCELED) continue;
                JSONObject o = it.toJson();
                if (o != null) a.put(o);
            }
            prefs(c).edit().putString("items", a.toString()).apply();
        } catch (Exception ignored) { }
    }
}
