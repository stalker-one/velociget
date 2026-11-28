package io.encodal.idm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files: downloads, every video / picture / audio file on the phone and a full folder browser (with hidden files).
 * List or grid view, sorting, multi-select, share, recycle bin.
 */
public class DeviceFilesActivity extends Activity {
    private static final int DOWNLOADS = 0, VIDEO = 1, IMAGE = 2, AUDIO = 3, FOLDERS = 4;
    private static final int K_VIDEO = 1, K_IMAGE = 2, K_AUDIO = 3, K_OTHER = 5, K_DIR = 6;
    private static final String[] TABS = {"Downloads", "Videos", "Images", "Audio", "Folders"};
    private static final String[] SORTS = {"Date", "Name", "Size"};

    private static final class M {
        String name = "", mime = "", folder = "", path = "";
        long size, date, dur, expires;
        Uri uri;
        File file;
        int kind;
        Bin.Entry bin;
        boolean sysTrash;

        String key() { return uri != null ? uri.toString() : file != null ? file.getAbsolutePath() : name; }
    }

    private int BG, CARD, TXT, SUB, LINE, FIELD, PRIMARY = 0xFF2563EB;
    private int tab = DOWNLOADS, sortKey = 0;
    private boolean asc, grid, hidden, binMode, selMode;
    private String openFolder; // media tabs: null = folder overview, "*" = all
    private File dir;          // folders tab: null = storage list
    private String query = "";
    private LinearLayout content, chips, headRight;
    private TextView title, sub;
    private final List<M> media = new ArrayList<>();
    private final Map<String, M> selected = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final android.util.LruCache<String, Bitmap> cache = new android.util.LruCache<>(160);
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
        sortKey = Prefs.getInt(this, "dsort", 0);
        asc = Prefs.getBool(this, "dasc", false);
        grid = Prefs.getBool(this, "dgrid", false);
        getWindow().setStatusBarColor(0xFF1D4ED8);
        getWindow().setNavigationBarColor(CARD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(6), dp(10));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1D4ED8, 0xFF3B82F6}));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        title = tv("Files", 19, Color.WHITE, true);
        sub = tv("Downloads, videos, pictures and audio", 11, 0xCCFFFFFF, false);
        sub.setSingleLine(true);
        tt.addView(title);
        tt.addView(sub);
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        headRight = new LinearLayout(this);
        headRight.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(headRight);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(dp(12), dp(10), dp(12), dp(2));
        hs.addView(chips);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        EditText search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
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
        drawHead();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) { reload(); }

    // ---------------------------------------------------------------- permissions

    private boolean allFiles() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
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

    private void askAllFiles() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---------------------------------------------------------------- header / chips

    private Ico hbtn(int kind, View.OnClickListener l) {
        Ico i = new Ico(this, kind);
        i.setOnClickListener(l);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return i;
    }

    private void drawHead() {
        headRight.removeAllViews();
        if (selMode) {
            headRight.addView(hbtn(Ico.SELECT, v -> selectAll()));
            if (binMode) {
                headRight.addView(hbtn(Ico.RESTORE, v -> binRestore(new ArrayList<>(selected.values()))));
                headRight.addView(hbtn(Ico.TRASH, v -> binPurge(new ArrayList<>(selected.values()))));
            } else {
                headRight.addView(hbtn(Ico.SHARE, v -> shareMany(new ArrayList<>(selected.values()))));
                headRight.addView(hbtn(Ico.TRASH, v -> deleteMany(new ArrayList<>(selected.values()))));
            }
            headRight.addView(hbtn(Ico.CLOSE, v -> endSelect()));
            title.setText(selected.size() + " selected");
            return;
        }
        if (binMode) {
            headRight.addView(hbtn(Ico.SELECT, v -> { selMode = true; drawHead(); render(); }));
            headRight.addView(hbtn(Ico.TRASH, v -> emptyBin()));
            headRight.addView(hbtn(Ico.CLOSE, v -> { binMode = false; openFolder = null; drawHead(); reload(); }));
            return;
        }
        headRight.addView(hbtn(Ico.SORT, v -> sortMenu()));
        headRight.addView(hbtn(grid ? Ico.LIST : Ico.GRID, v -> { grid = !grid; Prefs.putBool(this, "dgrid", grid); drawHead(); render(); }));
        headRight.addView(hbtn(Ico.SELECT, v -> { selMode = true; drawHead(); render(); }));
        headRight.addView(hbtn(Ico.RESTORE, v -> { binMode = true; selected.clear(); drawHead(); reload(); }).color(0xFFFFFFFF));
    }

    private void drawChips() {
        chips.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView c = tv(TABS[i], 13, i == tab ? Color.WHITE : TXT, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(16), dp(7), dp(16), dp(7));
            c.setBackground(rect(i == tab ? PRIMARY : CARD, 20, i == tab ? PRIMARY : LINE));
            c.setOnClickListener(v -> { tab = idx; openFolder = null; query = ""; binMode = false; endSelect(); drawChips(); drawHead(); reload(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            chips.addView(c, lp);
        }
    }

    private void sortMenu() {
        final String[] items = {"Date", "Name", "Size", asc ? "Order: oldest / A–Z / smallest first" : "Order: newest / Z–A / largest first"};
        new AlertDialog.Builder(this).setTitle("Sort by").setSingleChoiceItems(new String[]{"Date", "Name", "Size"}, sortKey, (d, w) -> {
            sortKey = w;
            Prefs.putInt(this, "dsort", w);
            d.dismiss();
            render();
        }).setPositiveButton(asc ? "Ascending ✓" : "Descending ✓", (d, w) -> { asc = !asc; Prefs.putBool(this, "dasc", asc); render(); }).show();
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        if (content == null) return;
        final int g = ++gen;
        if (binMode) { loadBin(g); return; }
        if (tab == FOLDERS) { render(); return; }
        if (!hasMedia()) { render(); return; }
        final int t = tab;
        final String dlPath = Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder(this) + "/";
        new Thread(() -> {
            List<M> list = new ArrayList<>();
            Uri base;
            String sel = null;
            String[] args = null;
            if (t == DOWNLOADS) {
                base = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                sel = "relative_path LIKE ? AND is_pending=0";
                args = new String[]{dlPath + "%"};
            } else base = t == VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : t == IMAGE ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            boolean hasDur = t == VIDEO || t == AUDIO;
            String[] cols = hasDur
                    ? new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path", "_data", "duration"}
                    : new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path", "_data"};
            try (Cursor c = getContentResolver().query(base, cols, sel, args, "date_modified DESC")) {
                while (c != null && c.moveToNext()) {
                    M m = new M();
                    m.name = c.getString(1) == null ? "" : c.getString(1);
                    m.mime = c.getString(2) == null ? "" : c.getString(2);
                    m.size = c.getLong(3);
                    m.date = c.getLong(4) * 1000L;
                    String rp = c.getString(5);
                    m.folder = rp == null || rp.isEmpty() ? "Other" : rp.replaceAll("/+$", "");
                    m.path = c.getString(6) == null ? "" : c.getString(6);
                    if (hasDur) m.dur = c.getLong(7);
                    m.uri = ContentUris.withAppendedId(base, c.getLong(0));
                    m.kind = t == VIDEO ? K_VIDEO : t == IMAGE ? K_IMAGE : t == AUDIO ? K_AUDIO : kindFor(m.name, m.mime);
                    list.add(m);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> { if (g != gen) return; media.clear(); media.addAll(list); render(); });
        }).start();
    }

    private void loadBin(final int g) {
        new Thread(() -> {
            List<M> list = new ArrayList<>();
            if (allFiles()) {
                for (Bin.Entry e : Bin.list()) {
                    M m = new M();
                    m.name = e.name;
                    m.file = e.file;
                    m.size = e.size;
                    m.date = e.time;
                    m.expires = e.time + Bin.KEEP_MS;
                    m.kind = e.dir ? K_DIR : kindFor(e.name, mimeOf(e.name));
                    m.mime = mimeOf(e.name);
                    m.bin = e;
                    list.add(m);
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                Uri[] tables = {MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI};
                int[] kinds = {K_VIDEO, K_IMAGE, K_AUDIO};
                for (int i = 0; i < tables.length; i++) {
                    Bundle q = new Bundle();
                    q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
                    try (Cursor c = getContentResolver().query(tables[i], new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "date_expires"}, q, null)) {
                        while (c != null && c.moveToNext()) {
                            M m = new M();
                            m.name = c.getString(1) == null ? "" : c.getString(1);
                            m.mime = c.getString(2) == null ? "" : c.getString(2);
                            m.size = c.getLong(3);
                            m.date = c.getLong(4) * 1000L;
                            m.expires = c.getLong(5) * 1000L;
                            m.uri = ContentUris.withAppendedId(tables[i], c.getLong(0));
                            m.kind = kinds[i];
                            m.sysTrash = true;
                            list.add(m);
                        }
                    } catch (Exception ignored) { }
                }
            }
            runOnUiThread(() -> { if (g != gen) return; media.clear(); media.addAll(list); render(); });
        }).start();
    }

    private List<M> sorted(List<M> in) {
        List<M> l = new ArrayList<>(in);
        final int dirSign = asc ? 1 : -1;
        Collections.sort(l, new Comparator<M>() {
            @Override public int compare(M a, M b) {
                if (a.kind == K_DIR && b.kind != K_DIR) return -1;
                if (a.kind != K_DIR && b.kind == K_DIR) return 1;
                int r;
                if (sortKey == 1) r = a.name.compareToIgnoreCase(b.name);
                else if (sortKey == 2) r = Long.compare(a.size, b.size);
                else r = Long.compare(a.date, b.date);
                return r * dirSign;
            }
        });
        return l;
    }

    // ---------------------------------------------------------------- render

    private void render() {
        content.removeAllViews();
        if (!selMode) title.setText(binMode ? "Recycle bin" : tab == FOLDERS ? "Folders" : openFolder == null || tab == DOWNLOADS ? TABS[tab] : openFolder.equals("*") ? "All " + TABS[tab].toLowerCase(Locale.US) : lastPart(openFolder));
        if (binMode) { binList(); return; }
        if (tab == FOLDERS) { folders(); return; }
        if (!hasMedia()) { permissionCard("Allow access to your videos, pictures and audio so they can be listed here.", "Allow access", v -> askMedia()); return; }
        if (media.isEmpty()) { empty(tab == DOWNLOADS ? "Nothing downloaded yet.\nFinished downloads appear here." : "Nothing found here yet."); return; }
        if (tab != DOWNLOADS && openFolder == null && query.isEmpty()) overview(); else list();
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
        r.addView(thumb(first, dp(54)), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        tx.addView(tv(name, 15, TXT, true));
        TextView s = tv(subText, 11, SUB, false);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tx.addView(s);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(chev(), new LinearLayout.LayoutParams(dp(18), dp(24)));
        r.setOnClickListener(v -> { openFolder = key; render(); });
        content.addView(r, rowLp());
    }

    private List<M> shownList() {
        List<M> shown = new ArrayList<>();
        for (M m : media) {
            if (openFolder != null && !openFolder.equals("*") && tab != DOWNLOADS && !openFolder.equals(m.folder)) continue;
            if (!query.isEmpty() && !m.name.toLowerCase(Locale.US).contains(query)) continue;
            shown.add(m);
        }
        return sorted(shown);
    }

    private void list() {
        final List<M> shown = shownList();
        long total = 0;
        for (M m : shown) total += m.size;
        sub.setText(shown.size() + " items · " + MainActivity.sizeText(total) + " · " + SORTS[sortKey] + (asc ? " ↑" : " ↓"));
        if (shown.isEmpty()) { empty("No matches."); return; }
        fill(shown, shown);
    }

    /** Draws the items as rows or as a 3-column grid. */
    private void fill(List<M> items, List<M> playlist) {
        if (!grid) {
            int n = 0;
            for (M m : items) {
                if (n++ > 700) break;
                content.addView(fileRow(m, playlist), rowLp());
            }
            return;
        }
        int cols = 3, gap = dp(6);
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(24) - gap * (cols - 1)) / cols;
        LinearLayout r = null;
        int n = 0;
        for (M m : items) {
            if (n >= 600) break;
            if (n % cols == 0) {
                r = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = gap;
                content.addView(r, rp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cell, cell);
            if (n % cols != cols - 1) cp.rightMargin = gap;
            r.addView(gridCell(m, playlist, cell), cp);
            n++;
        }
    }

    // ---------------------------------------------------------------- folders tab

    private void folders() {
        if (!allFiles() && Build.VERSION.SDK_INT >= 30) {
            permissionCard("To browse every folder of your phone, including hidden files and folders, Android needs \"All files access\" for this app.", "Allow all files access", v -> askAllFiles());
            return;
        }
        if (!hasMedia()) { permissionCard("Allow storage access to browse folders.", "Allow access", v -> askMedia()); return; }
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
            addDirRow(Environment.getExternalStorageDirectory(), "Internal storage");
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
            m.path = f.getAbsolutePath();
            m.size = f.length();
            m.date = f.lastModified();
            m.mime = mimeOf(f.getName());
            m.kind = f.isDirectory() ? K_DIR : kindFor(f.getName(), m.mime);
            items.add(m);
        }
        items = sorted(items);
        if (items.isEmpty()) { empty("This folder is empty."); return; }
        List<M> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (M m : items) (m.kind == K_DIR ? dirs : files).add(m);
        for (M m : dirs) content.addView(dirRow(m, items), rowLp());
        fill(files, items);
    }

    private View dirRow(final M m, final List<M> playlist) {
        File[] ch = m.file.listFiles();
        LinearLayout r = row();
        r.addView(icoBox(Ico.FOLDER, 0xFFF59E0B), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        tx.addView(tv(m.name, 15, TXT, true));
        tx.addView(tv((ch == null ? 0 : ch.length) + " items", 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(chev(), new LinearLayout.LayoutParams(dp(18), dp(24)));
        r.setOnClickListener(v -> { if (selMode) toggle(m); else { dir = m.file; query = ""; render(); } });
        r.setOnLongClickListener(v -> { startSelect(m); return true; });
        return r;
    }

    private void addDirRow(final File f, String label) {
        LinearLayout r = row();
        r.addView(icoBox(Ico.FOLDER, 0xFF3B82F6), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        tx.addView(tv(label, 15, TXT, true));
        tx.addView(tv(MainActivity.sizeText(f.getFreeSpace()) + " free of " + MainActivity.sizeText(f.getTotalSpace()), 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(chev(), new LinearLayout.LayoutParams(dp(18), dp(24)));
        r.setOnClickListener(v -> { dir = f; render(); });
        content.addView(r, rowLp());
    }

    // ---------------------------------------------------------------- recycle bin

    private void binList() {
        if (media.isEmpty()) { sub.setText("Deleted files stay here for 30 days"); empty("The recycle bin is empty."); return; }
        sub.setText(media.size() + " items · kept for 30 days");
        if (!allFiles()) {
            TextView n = tv("Items deleted without \"All files access\" are kept by Android itself.", 11, SUB, false);
            n.setPadding(dp(4), 0, 0, dp(6));
            content.addView(n);
        }
        List<M> l = sorted(media);
        fill(l, l);
    }

    private void binRestore(final List<M> items) {
        if (items.isEmpty()) return;
        List<Uri> sys = new ArrayList<>();
        int ok = 0;
        for (M m : items) {
            if (m.bin != null) { if (Bin.restore(this, m.bin)) ok++; }
            else if (m.sysTrash && m.uri != null) sys.add(m.uri);
        }
        if (!sys.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try { startIntentSenderForResult(MediaStore.createTrashRequest(getContentResolver(), sys, false).getIntentSender(), 92, null, 0, 0, 0); } catch (Exception e) { toast("Could not restore"); }
        }
        if (ok > 0) toast(ok + " restored");
        endSelect();
        reload();
    }

    private void binPurge(final List<M> items) {
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Delete " + items.size() + " item" + (items.size() > 1 ? "s" : "") + " forever?").setMessage("This cannot be undone.")
                .setPositiveButton("Delete forever", (d, w) -> {
                    List<Uri> sys = new ArrayList<>();
                    for (M m : items) {
                        if (m.bin != null) Bin.purge(m.bin);
                        else if (m.sysTrash && m.uri != null) sys.add(m.uri);
                    }
                    if (!sys.isEmpty() && Build.VERSION.SDK_INT >= 30) {
                        try { startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), sys).getIntentSender(), 93, null, 0, 0, 0); } catch (Exception e) { toast("Could not delete"); }
                    }
                    endSelect();
                    reload();
                }).setNegativeButton("Cancel", null).show();
    }

    private void emptyBin() {
        if (media.isEmpty()) { toast("The recycle bin is already empty"); return; }
        binPurge(new ArrayList<>(media));
    }

    // ---------------------------------------------------------------- selection, delete, share

    private void startSelect(M m) {
        selMode = true;
        selected.put(m.key(), m);
        drawHead();
        render();
    }

    private void endSelect() {
        selMode = false;
        selected.clear();
        drawHead();
        if (content != null) render();
    }

    private void toggle(M m) {
        String k = m.key();
        if (selected.containsKey(k)) selected.remove(k); else selected.put(k, m);
        if (selected.isEmpty()) { endSelect(); return; }
        title.setText(selected.size() + " selected");
        render();
    }

    private void selectAll() {
        List<M> src;
        if (binMode) src = media;
        else if (tab == FOLDERS) {
            src = new ArrayList<>();
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) for (File f : fs) {
                if (!hidden && f.getName().startsWith(".")) continue;
                M m = new M();
                m.name = f.getName(); m.file = f; m.path = f.getAbsolutePath(); m.size = f.length(); m.date = f.lastModified();
                m.mime = mimeOf(f.getName()); m.kind = f.isDirectory() ? K_DIR : kindFor(f.getName(), m.mime);
                src.add(m);
            }
        } else src = shownList();
        boolean all = selected.size() >= src.size();
        selected.clear();
        if (!all) for (M m : src) selected.put(m.key(), m);
        if (selected.isEmpty()) { endSelect(); return; }
        title.setText(selected.size() + " selected");
        render();
    }

    private void shareMany(List<M> items) {
        ArrayList<Uri> us = new ArrayList<>();
        for (M m : items) { Uri u = contentUri(m); if (u != null && m.kind != K_DIR) us.add(u); }
        if (us.isEmpty()) { toast("Nothing here can be shared"); return; }
        Intent i = new Intent(us.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (us.size() == 1) i.putExtra(Intent.EXTRA_STREAM, us.get(0)); else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void deleteMany(final List<M> items) {
        if (items.isEmpty()) return;
        String what = items.size() == 1 ? items.get(0).name : items.size() + " items";
        new AlertDialog.Builder(this).setTitle("Move " + what + " to the recycle bin?").setMessage("You can restore " + (items.size() == 1 ? "it" : "them") + " within 30 days.")
                .setPositiveButton("Move", (d, w) -> {
                    int ok = 0;
                    List<Uri> sys = new ArrayList<>();
                    for (M m : items) {
                        File f = m.file != null ? m.file : (!m.path.isEmpty() ? new File(m.path) : null);
                        if (allFiles() && f != null && f.exists()) { if (Bin.trash(this, f)) ok++; continue; }
                        if (m.uri != null) sys.add(m.uri);
                    }
                    if (!sys.isEmpty()) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            try { startIntentSenderForResult(MediaStore.createTrashRequest(getContentResolver(), sys, true).getIntentSender(), 94, null, 0, 0, 0); } catch (Exception e) { toast("Could not delete"); }
                        } else {
                            for (Uri u : sys) { try { getContentResolver().delete(u, null, null); ok++; } catch (Exception ignored) { } }
                        }
                    }
                    if (ok > 0) toast(ok + " moved to the recycle bin");
                    endSelect();
                    reload();
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req >= 91 && req <= 94) { endSelect(); reload(); }
    }

    // ---------------------------------------------------------------- rows

    private View selBadge(boolean on) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? PRIMARY : 0x66000000);
        g.setStroke(dp(2), Color.WHITE);
        f.setBackground(g);
        if (on) f.addView(new Ico(this, Ico.CHECK), new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private View fileRow(final M m, final List<M> playlist) {
        boolean on = selected.containsKey(m.key());
        LinearLayout r = row();
        if (on) r.setBackground(rect(Nav.dark(this) ? 0xFF1E3A8A : 0xFFDBEAFE, 14, PRIMARY));
        FrameLayout tf = new FrameLayout(this);
        tf.addView(thumb(m, dp(54)), new FrameLayout.LayoutParams(-1, -1));
        if (selMode) tf.addView(selBadge(on), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.START));
        r.addView(tf, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, dp(6), 0);
        TextView n = tv(m.name, 14, TXT, true);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tx.addView(n);
        StringBuilder s = new StringBuilder(MainActivity.sizeText(m.size));
        if (m.dur > 0) s.append(" · ").append(clock(m.dur / 1000));
        if (binMode && m.expires > 0) s.append(" · ").append(Math.max(0, (m.expires - System.currentTimeMillis()) / 86400000L)).append(" days left");
        else if (m.date > 0) s.append(" · ").append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(m.date)));
        tx.addView(tv(s.toString(), 11, SUB, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        if (!selMode) {
            Ico more = new Ico(this, Ico.MORE);
            more.color(SUB);
            more.setOnClickListener(v -> actions(m, playlist));
            r.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
        }
        r.setOnClickListener(v -> tap(m, playlist));
        r.setOnLongClickListener(v -> { if (!selMode) startSelect(m); return true; });
        return r;
    }

    private View gridCell(final M m, final List<M> playlist, int size) {
        boolean on = selected.containsKey(m.key());
        FrameLayout f = new FrameLayout(this);
        f.addView(thumb(m, size), new FrameLayout.LayoutParams(-1, -1));
        if (m.kind != K_IMAGE) {
            TextView n = tv(m.name, 10, Color.WHITE, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            n.setPadding(dp(6), dp(14), dp(6), dp(4));
            n.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
            f.addView(n, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }
        if (m.dur > 0) {
            TextView d = tv(clock(m.dur / 1000), 10, Color.WHITE, true);
            d.setPadding(dp(5), dp(1), dp(5), dp(1));
            d.setBackground(rect(0x99000000, 8, 0));
            FrameLayout.LayoutParams dp1 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            dp1.setMargins(0, dp(4), dp(4), 0);
            f.addView(d, dp1);
        }
        if (selMode) {
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
            bp.setMargins(dp(5), dp(5), 0, 0);
            f.addView(selBadge(on), bp);
            if (on) f.setForeground(new android.graphics.drawable.ColorDrawable(0x552563EB));
        }
        f.setOnClickListener(v -> tap(m, playlist));
        f.setOnLongClickListener(v -> { if (!selMode) startSelect(m); return true; });
        return f;
    }

    private View thumb(final M m, int px) {
        if (m.kind == K_AUDIO || m.kind == K_OTHER) {
            return icoBox(m.kind == K_AUDIO ? Ico.TRACKS : Ico.INFO, m.kind == K_AUDIO ? 0xFFA855F7 : 0xFF64748B);
        }
        if (m.kind == K_DIR) return icoBox(Ico.FOLDER, 0xFFF59E0B);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(rect(FIELD, 10, 0));
        iv.setClipToOutline(true);
        final String key = m.key() + "@" + px;
        Bitmap c = cache.get(key);
        if (c != null) { iv.setImageBitmap(c); return iv; }
        final int side = Math.max(160, px);
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                Size sz = new Size(side, side);
                if (m.file != null) bm = m.kind == K_VIDEO ? ThumbnailUtils.createVideoThumbnail(m.file, sz, null) : ThumbnailUtils.createImageThumbnail(m.file, sz, null);
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

    private View chev() {
        Ico c = new Ico(this, Ico.BACK).color(SUB);
        c.setRotation(180);
        return c;
    }

    private View icoBox(int kind, int color) {
        Ico t = new Ico(this, kind);
        t.color(color);
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

    // ---------------------------------------------------------------- open / actions

    private void tap(M m, List<M> playlist) {
        if (selMode) { toggle(m); return; }
        if (binMode) { actions(m, playlist); return; }
        open(m, playlist);
    }

    private static boolean visual(M m) { return m.kind == K_VIDEO || m.kind == K_IMAGE; }

    private void open(M m, List<M> playlist) {
        if (m.kind == K_DIR) { dir = m.file; render(); return; }
        Uri u = m.uri != null ? m.uri : Uri.fromFile(m.file);
        String mime = m.mime.isEmpty() ? (m.kind == K_VIDEO ? "video/*" : m.kind == K_AUDIO ? "audio/*" : m.kind == K_IMAGE ? "image/*" : "") : m.mime;
        if (m.kind == K_VIDEO || m.kind == K_AUDIO || m.kind == K_IMAGE || mime.startsWith("text/")) {
            Intent i = new Intent(this, PreviewActivity.class).putExtra("uri", u.toString()).putExtra("mime", mime).putExtra("title", m.name);
            if (m.kind != K_OTHER) {
                ArrayList<String> us = new ArrayList<>(), ns = new ArrayList<>(), ms = new ArrayList<>();
                int idx = 0;
                for (M g : playlist) {
                    boolean same = m.kind == K_AUDIO ? g.kind == K_AUDIO : visual(g) && visual(m);
                    if (!same) continue;
                    if (g == m) idx = us.size();
                    us.add((g.uri != null ? g.uri : Uri.fromFile(g.file)).toString());
                    ns.add(g.name);
                    ms.add(g.mime.isEmpty() ? (g.kind == K_VIDEO ? "video/*" : g.kind == K_AUDIO ? "audio/*" : "image/*") : g.mime);
                    if (us.size() >= 400) break;
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
        if (m.file == null) return null;
        try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"), new String[]{"_id"}, "_data=?", new String[]{m.file.getAbsolutePath()}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private void openWith(M m) {
        Uri u = contentUri(m);
        if (u == null) { toast("Android can't hand this file to another app from here"); return; }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(u, m.mime.isEmpty() ? "*/*" : m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"));
        } catch (Exception e) { toast("No app can open this file"); }
    }

    private void actions(final M m, final List<M> playlist) {
        final List<String> names = new ArrayList<>();
        if (binMode) { names.add("Restore"); names.add("Delete forever"); names.add("Details"); }
        else if (m.kind == K_DIR) { names.add("Open"); names.add("Select"); names.add("Details"); names.add("Move to recycle bin"); }
        else { names.add("Open"); names.add("Open with…"); names.add("Share"); names.add("Select"); names.add("Details"); names.add("Move to recycle bin"); }
        new AlertDialog.Builder(this).setTitle(m.name).setItems(names.toArray(new String[0]), (d, w) -> {
            String a = names.get(w);
            List<M> one = new ArrayList<>();
            one.add(m);
            if (a.equals("Open")) open(m, playlist);
            else if (a.startsWith("Open with")) openWith(m);
            else if (a.equals("Share")) shareMany(one);
            else if (a.equals("Select")) startSelect(m);
            else if (a.equals("Restore")) binRestore(one);
            else if (a.equals("Delete forever")) binPurge(one);
            else if (a.equals("Details")) {
                String path = !m.path.isEmpty() ? m.path : m.file != null ? m.file.getAbsolutePath() : m.folder;
                new AlertDialog.Builder(this).setTitle("Details").setMessage("Name: " + m.name + "\nWhere: " + path + "\nSize: " + MainActivity.sizeText(m.size)
                        + (m.mime.isEmpty() ? "" : "\nType: " + m.mime) + (m.date > 0 ? "\nModified: " + new Date(m.date) : "")).setPositiveButton("OK", null).show();
            } else deleteMany(one);
        }).show();
    }

    @Override
    public void onBackPressed() {
        if (selMode) { endSelect(); return; }
        if (binMode) { binMode = false; drawHead(); reload(); return; }
        if (tab == FOLDERS) {
            if (dir != null) {
                File p = dir.getParentFile();
                File internal = Environment.getExternalStorageDirectory();
                dir = dir.equals(internal) || p == null || p.getAbsolutePath().equals("/storage") ? null : p;
                render();
                return;
            }
        } else if (openFolder != null && tab != DOWNLOADS) {
            openFolder = null;
            render();
            return;
        }
        Nav.go(this, Nav.FILES, Nav.HOME);
    }

    // ---------------------------------------------------------------- helpers

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

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

    private static int kindFor(String name, String mime) {
        String n = name.toLowerCase(Locale.US);
        if (mime.startsWith("video/") || n.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp|m4v|ts|flv|wmv|mpg|mpeg)$")) return K_VIDEO;
        if (mime.startsWith("audio/") || n.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|amr|wma)$")) return K_AUDIO;
        if (mime.startsWith("image/") || n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|heif)$")) return K_IMAGE;
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
