package io.encodal.idm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every video, picture and audio file on the phone (grouped by folder) plus a full folder browser with hidden files.
 * Videos / Images / Audio use the media library; "Folders" reads the storage directly and needs "All files access".
 */
public class DeviceFilesActivity extends Activity {
    private static final int VIDEO = 0, IMAGE = 1, AUDIO = 2, FOLDERS = 3, K_OTHER = 3, K_DIR = 4;
    private static final String[] TABS = {"Videos", "Images", "Audio", "Folders"};

    private static final class M {
        String name = "", mime = "", folder = "";
        long size, date, dur;
        Uri uri;
        File file;
        int kind;
    }

    private int BG, CARD, TXT, SUB, LINE, FIELD, PRIMARY = 0xFF2563EB;
    private int tab = VIDEO;
    private boolean hidden;
    private String openFolder; // media tabs: null = folder overview
    private File dir;          // folders tab: null = storage list
    private String query = "";
    private LinearLayout content, chips;
    private TextView title, sub;
    private final List<M> media = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final android.util.LruCache<String, Bitmap> cache = new android.util.LruCache<>(120);
    private int gen;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dk = Nav.dark(this);
        BG = dk ? 0xFF0B1220 : 0xFFF1F5F9;
        CARD = dk ? 0xFF131C2E : 0xFFFFFFFF;
        TXT = dk ? 0xFFE5EAF3 : 0xFF0F172A;
        SUB = dk ? 0xFF93A0B8 : 0xFF64748B;
        LINE = dk ? 0xFF22304A : 0xFFE2E8F0;
        FIELD = dk ? 0xFF0E1626 : 0xFFF8FAFC;
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(CARD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(14), dp(10), dp(14));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1D4ED8, 0xFF3B82F6}));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        title = tv("Files", 20, Color.WHITE, true);
        sub = tv("All videos, pictures and audio on this phone", 12, 0xCCFFFFFF, false);
        tt.addView(title);
        tt.addView(sub);
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mine = tv("Downloads", 12, Color.WHITE, true);
        mine.setPadding(dp(12), dp(7), dp(12), dp(7));
        mine.setBackground(rect(0x33FFFFFF, 20, 0));
        mine.setOnClickListener(v -> startActivity(new Intent(this, FilesActivity.class)));
        head.addView(mine);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        chips = new LinearLayout(this);
        chips.setPadding(dp(12), dp(10), dp(12), dp(2));
        root.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        EditText search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        search.setBackground(rect(CARD, 12, LINE));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { query = s.toString().trim().toLowerCase(Locale.US); render(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(12), dp(8), dp(12), dp(4));
        root.addView(search, sp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Nav.bar(this, Nav.FILES));
        setContentView(root);
        if (!hasMedia()) askMedia();
        drawChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        reload();
    }

    private boolean allFiles() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMedia() {
        if (allFiles()) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askMedia() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_AUDIO}, 100);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
    }

    private void drawChips() {
        chips.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView c = tv(TABS[i], 13, i == tab ? Color.WHITE : TXT, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(14), dp(8), dp(14), dp(8));
            c.setBackground(rect(i == tab ? PRIMARY : CARD, 20, i == tab ? PRIMARY : LINE));
            c.setOnClickListener(v -> { tab = idx; openFolder = null; query = ""; drawChips(); reload(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = dp(6);
            chips.addView(c, lp);
        }
    }

    private void reload() {
        if (content == null) return;
        final int g = ++gen;
        if (tab == FOLDERS) { render(); return; }
        if (!hasMedia()) { render(); return; }
        final int t = tab;
        new Thread(() -> {
            List<M> list = new ArrayList<>();
            Uri base = t == VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : t == IMAGE ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] cols = t == IMAGE
                    ? new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path"}
                    : new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path", "duration"};
            try (Cursor c = getContentResolver().query(base, cols, null, null, "date_modified DESC")) {
                while (c != null && c.moveToNext()) {
                    M m = new M();
                    m.name = c.getString(1) == null ? "" : c.getString(1);
                    m.mime = c.getString(2) == null ? "" : c.getString(2);
                    m.size = c.getLong(3);
                    m.date = c.getLong(4) * 1000L;
                    String rp = c.getString(5);
                    m.folder = rp == null || rp.isEmpty() ? "Other" : rp.replaceAll("/+$", "");
                    if (t != IMAGE) m.dur = c.getLong(6);
                    m.uri = ContentUris.withAppendedId(base, c.getLong(0));
                    m.kind = t;
                    list.add(m);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> { if (g != gen) return; media.clear(); media.addAll(list); render(); });
        }).start();
    }

    private void render() {
        content.removeAllViews();
        title.setText(tab == FOLDERS ? "Folders" : openFolder == null ? TABS[tab] : openFolder.equals("*") ? "All " + TABS[tab].toLowerCase(Locale.US) : lastPart(openFolder));
        if (tab == FOLDERS) { folders(); return; }
        if (!hasMedia()) { permissionCard("Allow access to your videos, pictures and audio so they can be listed here.", "Allow access", v -> askMedia()); return; }
        if (media.isEmpty()) { empty("Nothing found here yet."); return; }
        if (openFolder == null && query.isEmpty()) overview(); else list();
    }

    private void overview() {
        Map<String, List<M>> g = new LinkedHashMap<>();
        for (M m : media) { List<M> l = g.get(m.folder); if (l == null) { l = new ArrayList<>(); g.put(m.folder, l); } l.add(m); }
        long total = 0;
        for (M m : media) total += m.size;
        sub.setText(media.size() + " items · " + g.size() + " folders · " + MainActivity.sizeText(total));
        addFolderRow("All " + TABS[tab].toLowerCase(Locale.US), media.size() + " items", media.get(0), "*");
        for (Map.Entry<String, List<M>> e : g.entrySet()) {
            addFolderRow(lastPart(e.getKey()), e.getValue().size() + " items · " + e.getKey(), e.getValue().get(0), e.getKey());
        }
    }

    private void addFolderRow(String name, String subText, final M first, final String key) {
        LinearLayout r = row();
        r.addView(thumb(first, true), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        tx.addView(tv(name, 15, TXT, true));
        TextView s = tv(subText, 11, SUB, false);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tx.addView(s);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(tv("›", 22, SUB, false));
        r.setOnClickListener(v -> { openFolder = key; render(); });
        content.addView(r, rowLp());
    }

    private void list() {
        final List<M> shown = new ArrayList<>();
        for (M m : media) {
            if (openFolder != null && !openFolder.equals("*") && !openFolder.equals(m.folder)) continue;
            if (!query.isEmpty() && !m.name.toLowerCase(Locale.US).contains(query)) continue;
            shown.add(m);
        }
        long total = 0;
        for (M m : shown) total += m.size;
        sub.setText(shown.size() + " items · " + MainActivity.sizeText(total));
        if (shown.isEmpty()) { empty("No matches."); return; }
        int n = 0;
        for (final M m : shown) {
            if (n++ > 600) break;
            content.addView(fileRow(m, shown), rowLp());
        }
    }

    // ---- folders tab (needs all-files access) ----

    private void folders() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            permissionCard("To browse every folder of your phone, including hidden files and folders, Android needs \"All files access\" for this app.", "Allow all files access", v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            });
            return;
        }
        if (!hasMedia()) { permissionCard("Allow storage access to browse folders.", "Allow access", v -> askMedia()); return; }
        // toolbar: path + hidden switch
        LinearLayout tb = new LinearLayout(this);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        TextView pth = tv(dir == null ? "Storage" : dir.getAbsolutePath(), 12, SUB, true);
        pth.setSingleLine(true);
        pth.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tb.addView(pth, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hid = tv(hidden ? "Hidden files: ON" : "Hidden files: OFF", 12, hidden ? Color.WHITE : TXT, true);
        hid.setPadding(dp(12), dp(6), dp(12), dp(6));
        hid.setBackground(rect(hidden ? PRIMARY : CARD, 16, hidden ? PRIMARY : LINE));
        hid.setOnClickListener(v -> { hidden = !hidden; render(); });
        tb.addView(hid);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.bottomMargin = dp(8);
        content.addView(tb, tp);
        sub.setText(hidden ? "Showing hidden files" : "Hidden files are not shown");

        if (dir == null) {
            File[] roots = new File("/storage").listFiles();
            File internal = Environment.getExternalStorageDirectory();
            addDirRow(internal, "Internal storage");
            if (roots != null) for (File r : roots) {
                String n = r.getName();
                if (n.equals("emulated") || n.equals("self") || !r.isDirectory() || !r.canRead()) continue;
                addDirRow(r, "SD card / USB · " + n);
            }
            return;
        }
        File[] fs = dir.listFiles();
        if (fs == null) { empty("This folder can't be read."); return; }
        List<M> items = new ArrayList<>();
        for (File f : fs) {
            if (!hidden && f.getName().startsWith(".")) continue;
            if (!query.isEmpty() && !f.getName().toLowerCase(Locale.US).contains(query)) continue;
            M m = new M();
            m.name = f.getName();
            m.file = f;
            m.size = f.length();
            m.date = f.lastModified();
            m.kind = f.isDirectory() ? K_DIR : kindOf(f.getName());
            m.mime = mimeOf(f.getName());
            items.add(m);
        }
        Collections.sort(items, (a, b2) -> a.kind == K_DIR && b2.kind != K_DIR ? -1 : a.kind != K_DIR && b2.kind == K_DIR ? 1 : a.name.compareToIgnoreCase(b2.name));
        if (items.isEmpty()) { empty("This folder is empty."); return; }
        final List<M> playable = new ArrayList<>();
        for (M m : items) if (m.kind == VIDEO || m.kind == AUDIO) playable.add(m);
        int n = 0;
        for (final M m : items) {
            if (n++ > 800) break;
            if (m.kind == K_DIR) {
                File[] ch = m.file.listFiles();
                int cnt = ch == null ? 0 : ch.length;
                LinearLayout r = row();
                TextView ic = tv("📁", 26, TXT, false);
                ic.setGravity(Gravity.CENTER);
                r.addView(ic, new LinearLayout.LayoutParams(dp(54), dp(54)));
                LinearLayout tx = new LinearLayout(this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.setPadding(dp(12), 0, dp(6), 0);
                tx.addView(tv(m.name, 15, TXT, true));
                tx.addView(tv(cnt + " items", 11, SUB, false));
                r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
                r.addView(tv("›", 22, SUB, false));
                r.setOnClickListener(v -> { dir = m.file; query = ""; render(); });
                r.setOnLongClickListener(v -> { actions(m, playable); return true; });
                content.addView(r, rowLp());
            } else {
                content.addView(fileRow(m, playable), rowLp());
            }
        }
    }

    private void addDirRow(final File f, String label) {
        LinearLayout r = row();
        TextView ic = tv("💾", 26, TXT, false);
        ic.setGravity(Gravity.CENTER);
        r.addView(ic, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        tx.addView(tv(label, 15, TXT, true));
        tx.addView(tv(MainActivity.sizeText(f.getFreeSpace()) + " free of " + MainActivity.sizeText(f.getTotalSpace()), 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(tv("›", 22, SUB, false));
        r.setOnClickListener(v -> { dir = f; render(); });
        content.addView(r, rowLp());
    }

    // ---- rows ----

    private View fileRow(final M m, final List<M> playlist) {
        LinearLayout r = row();
        r.addView(thumb(m, false), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        TextView n = tv(m.name, 14, TXT, true);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tx.addView(n);
        StringBuilder s = new StringBuilder(MainActivity.sizeText(m.size));
        if (m.dur > 0) s.append(" · ").append(clock(m.dur / 1000));
        if (m.date > 0) s.append(" · ").append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(m.date)));
        tx.addView(tv(s.toString(), 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = tv("⋮", 22, SUB, true);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> actions(m, playlist));
        r.addView(more, new LinearLayout.LayoutParams(dp(36), dp(44)));
        r.setOnClickListener(v -> open(m, playlist));
        r.setOnLongClickListener(v -> { actions(m, playlist); return true; });
        return r;
    }

    private View thumb(final M m, boolean folderStyle) {
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(rect(FIELD, 10, LINE));
        iv.setClipToOutline(true);
        if (m.kind == AUDIO || (m.kind == K_OTHER)) {
            return glyph(m.kind == AUDIO ? "🎵" : "📄");
        }
        final String key = String.valueOf(m.uri != null ? m.uri : m.file);
        Bitmap c = cache.get(key);
        if (c != null) { iv.setImageBitmap(c); return iv; }
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                Size sz = new Size(160, 160);
                if (m.file != null) bm = m.kind == VIDEO ? ThumbnailUtils.createVideoThumbnail(m.file, sz, null) : ThumbnailUtils.createImageThumbnail(m.file, sz, null);
                else bm = getContentResolver().loadThumbnail(m.uri, sz, null);
            } catch (Exception ignored) { }
            if (bm != null) {
                cache.put(key, bm);
                final Bitmap fb = bm;
                runOnUiThread(() -> iv.setImageBitmap(fb));
            }
        });
        return iv;
    }

    private View glyph(String g) {
        TextView t = tv(g, 24, TXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rect(FIELD, 10, LINE));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(8), dp(8));
        r.setBackground(rect(CARD, 14, LINE));
        return r;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(7);
        return p;
    }

    private void empty(String msg) {
        TextView e = tv(msg, 14, SUB, false);
        e.setGravity(Gravity.CENTER);
        e.setPadding(0, dp(60), 0, 0);
        content.addView(e);
    }

    private void permissionCard(String msg, String btn, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(20), dp(24), dp(20), dp(20));
        c.setBackground(rect(CARD, 16, LINE));
        TextView m = tv(msg, 14, TXT, false);
        m.setGravity(Gravity.CENTER);
        c.addView(m);
        TextView b = tv(btn, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rect(PRIMARY, 12, 0));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(46));
        bp.topMargin = dp(16);
        c.addView(b, bp);
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---- actions ----

    private void open(M m, List<M> playlist) {
        if (m.kind == K_DIR) { dir = m.file; render(); return; }
        Uri u = m.uri != null ? m.uri : Uri.fromFile(m.file);
        String mime = m.mime.isEmpty() ? (m.kind == VIDEO ? "video/*" : m.kind == AUDIO ? "audio/*" : m.kind == IMAGE ? "image/*" : "") : m.mime;
        if (m.kind == VIDEO || m.kind == AUDIO || m.kind == IMAGE || mime.startsWith("text/")) {
            Intent i = new Intent(this, PreviewActivity.class).putExtra("uri", u.toString()).putExtra("mime", mime).putExtra("title", m.name);
            if (m.kind == VIDEO || m.kind == AUDIO) {
                ArrayList<String> us = new ArrayList<>(), ns = new ArrayList<>(), ms = new ArrayList<>();
                int idx = 0;
                for (M g : playlist) {
                    if (g.kind != m.kind) continue;
                    if (g == m) idx = us.size();
                    us.add((g.uri != null ? g.uri : Uri.fromFile(g.file)).toString());
                    ns.add(g.name);
                    ms.add(g.mime.isEmpty() ? (g.kind == VIDEO ? "video/*" : "audio/*") : g.mime);
                    if (us.size() >= 300) break;
                }
                if (us.size() > 1) i.putStringArrayListExtra("pl_uris", us).putStringArrayListExtra("pl_names", ns).putStringArrayListExtra("pl_mimes", ms).putExtra("pl_index", idx);
            }
            startActivity(i);
        } else {
            openWith(m);
        }
    }

    private Uri contentUri(M m) {
        if (m.uri != null) return m.uri;
        try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"), new String[]{"_id"}, "_data=?", new String[]{m.file.getAbsolutePath()}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private void openWith(M m) {
        Uri u = contentUri(m);
        if (u == null) { Toast.makeText(this, "Android can't hand this file to another app from here", Toast.LENGTH_LONG).show(); return; }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(u, m.mime.isEmpty() ? "*/*" : m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"));
        } catch (Exception e) { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show(); }
    }

    private void actions(final M m, final List<M> playlist) {
        final List<String> names = new ArrayList<>(Arrays.asList(m.kind == K_DIR ? new String[]{"Open", "Details", "Delete folder"} : new String[]{"Open", "Open with…", "Share", "Details", "Delete"}));
        new AlertDialog.Builder(this).setTitle(m.name).setItems(names.toArray(new String[0]), (d, w) -> {
            String a = names.get(w);
            if (a.equals("Open")) open(m, playlist);
            else if (a.startsWith("Open with")) openWith(m);
            else if (a.equals("Share")) {
                Uri u = contentUri(m);
                if (u == null) { Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show(); return; }
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(m.mime.isEmpty() ? "*/*" : m.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"));
            } else if (a.equals("Details")) {
                String path = m.file != null ? m.file.getAbsolutePath() : m.folder;
                new AlertDialog.Builder(this).setTitle("Details").setMessage("Name: " + m.name + "\nWhere: " + path + "\nSize: " + MainActivity.sizeText(m.size)
                        + (m.mime.isEmpty() ? "" : "\nType: " + m.mime) + (m.date > 0 ? "\nModified: " + new Date(m.date) : "")).setPositiveButton("OK", null).show();
            } else delete(m);
        }).show();
    }

    private void delete(final M m) {
        if (m.file != null) {
            new AlertDialog.Builder(this).setTitle("Delete " + m.name + "?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> { deleteTree(m.file); render(); })
                    .setNegativeButton("Cancel", null).show();
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(m.uri)).getIntentSender(), 91, null, 0, 0, 0);
            } catch (Exception e) { Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show(); }
        } else {
            try { getContentResolver().delete(m.uri, null, null); reload(); } catch (Exception e) { Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        f.delete();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == 91) reload();
    }

    @Override
    public void onBackPressed() {
        if (tab == FOLDERS) {
            if (dir != null) {
                File p = dir.getParentFile();
                File internal = Environment.getExternalStorageDirectory();
                dir = dir.equals(internal) || p == null || p.getAbsolutePath().equals("/storage") ? null : p;
                render();
                return;
            }
        } else if (openFolder != null) {
            openFolder = null;
            render();
            return;
        }
        Nav.go(this, Nav.FILES, Nav.HOME);
    }

    // ---- helpers ----

    private static String lastPart(String p) {
        String s = p.replaceAll("/+$", "");
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String mimeOf(String name) {
        int d = name.lastIndexOf('.');
        if (d < 0) return "";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(d + 1).toLowerCase(Locale.US));
        return m == null ? "" : m;
    }

    private static int kindOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp|m4v|ts|flv|wmv|mpg|mpeg)$")) return VIDEO;
        if (n.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|amr|wma)$")) return AUDIO;
        if (n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|heif)$")) return IMAGE;
        return K_OTHER;
    }

    private static String clock(long s) {
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable rect(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
