package io.encodal.idm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File manager for everything the app downloaded: category folders (Videos, Audio, Images…), search,
 * thumbnails, in-app player, share, rename, delete. Reads the same folder the downloads are saved to.
 */
public class FilesActivity extends Activity {
    private static final String[] CATS = {"Videos", "Audio", "Images", "Documents", "Archives", "Apps", "Other"};
    private static final String[] ICON = {"🎬", "🎵", "🖼", "📄", "🗜", "📦", "📁"};

    private static final class F {
        long id;
        String name, mime;
        long size, date;
        Uri uri;
        int cat;
    }

    private final List<F> all = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private LinearLayout content;
    private TextView title, path;
    private EditText search;
    private int current = -1;      // -1 = folder overview
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF1D4ED8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF1F5F9);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1D4ED8);
        bar.setPadding(dp(8), dp(10), dp(14), dp(10));
        TextView back = tv("←", 22, Color.WHITE, true);
        back.setPadding(dp(10), 0, dp(14), 0);
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        title = tv("Files", 18, Color.WHITE, true);
        path = tv("", 11, 0xCCFFFFFF, false);
        tt.addView(title);
        tt.addView(path);
        bar.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        search = new EditText(this);
        search.setHint("Search downloads");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(rect(Color.WHITE, 12, 0xFFE2E8F0));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { query = s.toString().trim().toLowerCase(Locale.US); render(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(12), dp(10), dp(12), dp(4));
        root.addView(search, sp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(6), dp(12), dp(20));
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private String relPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder(this) + "/";
    }

    private void load() {
        path.setText("Downloads/" + Prefs.folder(this));
        new Thread(() -> {
            List<F> list = new ArrayList<>();
            try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE,
                            MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED},
                    MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Downloads.IS_PENDING + "=0",
                    new String[]{relPath() + "%"}, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    F f = new F();
                    f.id = c.getLong(0);
                    f.name = c.getString(1);
                    f.mime = c.getString(2) == null ? "" : c.getString(2);
                    f.size = c.getLong(3);
                    f.date = c.getLong(4) * 1000L;
                    f.uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, f.id);
                    f.cat = category(f);
                    list.add(f);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> { all.clear(); all.addAll(list); render(); });
        }).start();
    }

    private static int category(F f) {
        String n = f.name == null ? "" : f.name.toLowerCase(Locale.US);
        String m = f.mime;
        if (m.startsWith("video/") || n.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp|m4v|ts)$")) return 0;
        if (m.startsWith("audio/") || n.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac)$")) return 1;
        if (m.startsWith("image/") || n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$")) return 2;
        if (n.matches(".*\\.(zip|rar|7z|tar|gz|bz2|xz)$")) return 4;
        if (n.endsWith(".apk")) return 5;
        if (m.startsWith("text/") || m.contains("pdf") || m.contains("word") || m.contains("sheet") || m.contains("presentation")
                || n.matches(".*\\.(pdf|docx?|xlsx?|pptx?|txt|csv|epub|json|xml)$")) return 3;
        return 6;
    }

    private void render() {
        content.removeAllViews();
        title.setText(current < 0 ? "Files" : CATS[current]);
        if (current < 0 && query.isEmpty()) overview(); else list();
    }

    private void overview() {
        if (all.isEmpty()) {
            TextView e = tv("Nothing downloaded yet.\nFinished downloads appear here, sorted into folders.", 14, 0xFF64748B, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(60), 0, 0);
            content.addView(e);
            return;
        }
        long total = 0;
        for (F f : all) total += f.size;
        content.addView(tv(all.size() + " files · " + MainActivity.sizeText(total), 13, 0xFF64748B, true));
        LinearLayout rowL = null;
        int shown = 0;
        for (int i = 0; i < CATS.length; i++) {
            int n = 0;
            long sz = 0;
            for (F f : all) if (f.cat == i) { n++; sz += f.size; }
            if (n == 0) continue;
            if (shown % 2 == 0) {
                rowL = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = dp(10);
                content.addView(rowL, rp);
            }
            final int ci = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(rect(Color.WHITE, 16, 0xFFE2E8F0));
            card.addView(tv(ICON[i], 28, Color.BLACK, false));
            card.addView(tv(CATS[i], 15, 0xFF0F172A, true));
            card.addView(tv(n + " · " + MainActivity.sizeText(sz), 12, 0xFF64748B, false));
            card.setOnClickListener(v -> { current = ci; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            cp.setMargins(shown % 2 == 0 ? 0 : dp(5), 0, shown % 2 == 0 ? dp(5) : 0, 0);
            rowL.addView(card, cp);
            shown++;
        }
        if (shown % 2 == 1 && rowL != null) rowL.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        content.addView(tv("Recent", 13, 0xFF64748B, true), withTop(14));
        int k = 0;
        for (F f : all) { if (k++ >= 6) break; content.addView(row(f), withTop(6)); }
    }

    private void list() {
        int n = 0;
        for (F f : all) {
            if (current >= 0 && f.cat != current) continue;
            if (!query.isEmpty() && !f.name.toLowerCase(Locale.US).contains(query)) continue;
            content.addView(row(f), withTop(6));
            n++;
        }
        if (n == 0) {
            TextView e = tv("No files here.", 14, 0xFF64748B, false);
            e.setPadding(0, dp(40), 0, 0);
            e.setGravity(Gravity.CENTER);
            content.addView(e);
        }
    }

    private LinearLayout.LayoutParams withTop(int d) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(d);
        return p;
    }

    private View row(final F f) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(6), dp(10));
        r.setBackground(rect(Color.WHITE, 14, 0xFFE2E8F0));
        final ImageView th = new ImageView(this);
        th.setScaleType(ImageView.ScaleType.CENTER_CROP);
        th.setBackground(rect(0xFFE2E8F0, 10, 0));
        th.setClipToOutline(true);
        th.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        r.addView(th, new LinearLayout.LayoutParams(dp(56), dp(56)));
        if (f.cat == 0 || f.cat == 2) {
            pool.execute(() -> {
                try {
                    final Bitmap bm = getContentResolver().loadThumbnail(f.uri, new Size(160, 160), null);
                    runOnUiThread(() -> th.setImageBitmap(bm));
                } catch (Exception ignored) { }
            });
        } else {
            th.setImageDrawable(null);
        }
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        TextView nm = tv((f.cat == 0 || f.cat == 2 ? "" : ICON[f.cat] + " ") + f.name, 14, 0xFF0F172A, true);
        nm.setMaxLines(2);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(nm);
        tx.addView(tv(MainActivity.sizeText(f.size) + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(f.date)), 11, 0xFF64748B, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = tv("⋮", 22, 0xFF475569, true);
        more.setPadding(dp(12), dp(6), dp(12), dp(6));
        more.setOnClickListener(v -> menu(f));
        r.addView(more);
        r.setOnClickListener(v -> play(f));
        r.setOnLongClickListener(v -> { menu(f); return true; });
        return r;
    }

    private void play(F f) {
        if (f.cat == 0 || f.cat == 1 || f.cat == 2 || f.mime.startsWith("text/")) {
            // the in-app player gets the whole list of that folder as a playlist
            ArrayList<String> uris = new ArrayList<>(), names = new ArrayList<>(), mimes = new ArrayList<>();
            int idx = 0;
            if (f.cat == 0 || f.cat == 1) {
                for (F g : all) if (g.cat == f.cat) {
                    if (g == f) idx = uris.size();
                    uris.add(g.uri.toString());
                    names.add(g.name);
                    mimes.add(g.mime);
                }
            }
            Intent i = new Intent(this, PreviewActivity.class).putExtra("uri", f.uri.toString()).putExtra("mime", f.mime).putExtra("title", f.name);
            if (uris.size() > 1) i.putStringArrayListExtra("pl_uris", uris).putStringArrayListExtra("pl_names", names)
                    .putStringArrayListExtra("pl_mimes", mimes).putExtra("pl_index", idx);
            startActivity(i);
        } else {
            openWith(f);
        }
    }

    private void openWith(F f) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime.isEmpty() ? "*/*" : f.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(final F f) {
        final String[] items = {"Play / open", "Open with…", "Share", "Rename", "Details", "Delete"};
        new AlertDialog.Builder(this).setTitle(f.name).setItems(items, (d, w) -> {
            if (w == 0) play(f);
            else if (w == 1) openWith(f);
            else if (w == 2) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(f.mime.isEmpty() ? "*/*" : f.mime)
                    .putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share file"));
            else if (w == 3) rename(f);
            else if (w == 4) new AlertDialog.Builder(this).setTitle("Details").setMessage("Name: " + f.name + "\nType: " + (f.mime.isEmpty() ? "unknown" : f.mime)
                    + "\nSize: " + MainActivity.sizeText(f.size) + " (" + f.size + " bytes)\nSaved: " + new Date(f.date)
                    + "\nFolder: Downloads/" + Prefs.folder(this)).setPositiveButton("OK", null).show();
            else confirmDelete(f);
        }).show();
    }

    private void rename(final F f) {
        final EditText e = new EditText(this);
        e.setText(f.name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename").setView(e).setPositiveButton("Rename", (d, w) -> {
            String n = e.getText().toString().replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
            if (n.isEmpty()) return;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, n);
                getContentResolver().update(f.uri, v, null, null);
                load();
            } catch (Exception ex) {
                Toast.makeText(this, "Android did not allow renaming this file", Toast.LENGTH_LONG).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(final F f) {
        new AlertDialog.Builder(this).setTitle("Delete file?").setMessage(f.name).setPositiveButton("Delete", (d, w) -> {
            try {
                getContentResolver().delete(f.uri, null, null);
                load();
            } catch (Exception ex) {
                Toast.makeText(this, "Android did not allow deleting this file. Delete it from the Files app.", Toast.LENGTH_LONG).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onBackPressed() {
        if (!query.isEmpty()) { search.setText(""); return; }
        if (current >= 0) { current = -1; render(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rect(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
