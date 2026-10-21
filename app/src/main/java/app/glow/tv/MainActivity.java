package app.glow.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    interface Act { void go(int idx); }

    private static final int WHITE = 0xFFFFFFFF, DIM = 0xB3FFFFFF, GLASS = 0x73101018, ACCENT = 0xFF3B6CF6;
    private static final int HOME = 0, APPS = 1, WALL = 2, SETTINGS = 3;
    // First-run favourites (only the ones you have installed are used).
    private static final String[] PREFERRED = {"com.netflix.ninja", "com.amazon.firetv.youtube", "com.amazon.avod",
            "com.spotify.tv.android", "com.amazon.music.tv", "org.xbmc.kodi", "com.plexapp.android", "org.videolan.vlc"};

    // Fire TV system tiles that make poor first-run favourites.
    private static final Set<String> JUNK = new HashSet<>(java.util.Arrays.asList("Home", "Screensaver",
            "Downloads", "Fire TV Profile UI", "Appstore", "Amazon Kids", "Amazon Live Shopping", "Settings"));

    private static final long ROTATE_MS = 90000;
    // Size of the now-playing card: 1.0 = original, smaller = smaller card. Change this one number to taste.
    private static final float NP_SCALE = 0.3f;

    private Prefs prefs;
    private WallpaperView wall;
    private final FrameLayout[] layers = new FrameLayout[4];
    private final ImageView[] topButtons = new ImageView[3];
    private LinearLayout dock, appsList, wallList, settingsList;
    private FrameLayout npCard;
    private TextView greetView, weatherPlace, weatherTemp, npTitle, npArtist, npSource, npTime, npHint;
    private TextClock clock, date;
    private ImageView npArt, npBlur, npPlay;
    private ProgressLine npBar;
    private EqualizerView npEq;
    private GradientDrawable npBorder;
    private ValueAnimator accentAnim;
    private Bitmap npArtShown;
    private NowPlaying.Track npLast;
    private int npAccent = ACCENT;
    private Typeface fLight, fReg, fMed;
    private NowPlaying nowPlaying;
    private List<Apps.Entry> all = new ArrayList<>();
    private List<String> hiddenNow = new ArrayList<>();
    private boolean npStarted, dirty, resumed;
    private int layer, minutes, rotateIdx;
    private long lastWeather;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable rotateTask = new Runnable() {
        @Override public void run() { rotateNext(false); }
    };
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            updateGreeting();
            if (++minutes % 30 == 0 && extras()) fetchWeather();
            h.postDelayed(this, 60000);
        }
    };
    private final Runnable progress = new Runnable() {
        @Override public void run() { updateProgress(); h.postDelayed(this, 1000); }
    };
    private final BroadcastReceiver pkgs = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; if (resumed) { loadApps(); render(); } }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        loadFonts();
        buildUi();
        nowPlaying = new NowPlaying(this, t -> showTrack(t));
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgs, f);
        loadApps();
        applyPrefs();
        applyWallpaper();
        render();
        showLayer(HOME);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        wall.resume();
        if (dirty) { loadApps(); render(); }
        if (!npStarted) npStarted = nowPlaying.start();
        updateNpHint();
        updateGreeting();
        h.removeCallbacks(minute);
        h.postDelayed(minute, 60000);
        h.removeCallbacks(progress);
        h.post(progress);
        if (extras() && System.currentTimeMillis() - lastWeather > 20 * 60 * 1000L) fetchWeather();
        h.removeCallbacks(rotateTask);
        if ("rotate".equals(prefs.str("wallpaper", ""))) h.postDelayed(rotateTask, ROTATE_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        wall.pause();
        h.removeCallbacks(minute);
        h.removeCallbacks(progress);
        h.removeCallbacks(rotateTask);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pkgs);
        nowPlaying.stop();
        h.removeCallbacksAndMessages(null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME) && layer != HOME) showLayer(HOME);
    }

    @Override public void onBackPressed() {
        if (layer != HOME) {
            final int from = layer;
            showLayer(HOME);
            h.post(() -> topButtons[from - 1].requestFocus());
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- UI construction

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        wall = new WallpaperView(this);
        root.addView(wall, match());
        for (int i = 0; i < 4; i++) {
            layers[i] = new FrameLayout(this);
            root.addView(layers[i], match());
        }
        FrameLayout home = layers[HOME];

        // top-left: a quiet clock and date (the greeting only shows when "Greeting & weather" is on)
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greetView = label("", 24, DIM);
        greetView.setLetterSpacing(0.16f);
        clock = new TextClock(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(132));
        clock.setTextColor(WHITE);
        clock.setTypeface(fLight);
        clock.setShadowLayer(sz(10), 0, sz(2), 0x66000000);
        clock.setIncludeFontPadding(false);
        date = new TextClock(this);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(32));
        date.setTextColor(DIM);
        date.setTypeface(fReg);
        date.setShadowLayer(sz(6), 0, sz(2), 0x66000000);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        tl.addView(greetView);
        tl.addView(clock);
        tl.addView(date, lp(-2, -2, sz(4), sz(2), 0, 0));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(sz(96), sz(56), 0, 0);
        home.addView(tl, tlp);

        // top-right: three small round buttons, then (optionally) location + weather
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.VERTICAL);
        tr.setGravity(Gravity.END);
        LinearLayout btns = new LinearLayout(this);
        int[] icons = {R.drawable.ic_apps, R.drawable.ic_wallpaper, R.drawable.ic_settings};
        for (int i = 0; i < 3; i++) {
            final int target = i + 1;
            ImageView bt = roundButton(icons[i], 80, () -> showLayer(target));
            topButtons[i] = bt;
            btns.addView(bt, lp(sz(80), sz(80), i == 0 ? 0 : sz(18), 0, 0, 0));
        }
        weatherPlace = label("", 30, WHITE);
        weatherPlace.setGravity(Gravity.END);
        weatherTemp = label("", 26, DIM);
        weatherTemp.setGravity(Gravity.END);
        tr.addView(btns, lp(-2, -2, 0, 0, 0, 0));
        tr.addView(weatherPlace, lp(-2, -2, 0, sz(24), 0, 0));
        tr.addView(weatherTemp, lp(-2, -2, 0, 0, 0, 0));
        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        trp.setMargins(0, sz(52), sz(96), 0);
        home.addView(tr, trp);

        // bottom: now-playing card above the app dock
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(buildNowPlayingCard(), new LinearLayout.LayoutParams(np(1240), -2));

        npHint = label("Turn on Now playing  -  press OK", 26, WHITE);
        npHint.setPadding(sz(30), sz(14), sz(30), sz(14));
        npHint.setBackground(glass(30));
        npHint.setFocusable(true);
        npHint.setClickable(true);
        npHint.setOnFocusChangeListener((view, focused) -> view.setBackground(focused ? rect(ACCENT, 30) : glass(30)));
        npHint.setOnClickListener(view -> nowPlayingHelp());
        bottom.addView(npHint, lp(-2, -2, 0, sz(14), 0, 0));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(sz(20), sz(18), sz(20), sz(8));
        hs.setBackground(glass(40));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        hs.addView(dock);
        bottom.addView(hs, lp(-1, -2, 0, sz(22), 0, 0));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(sz(96), 0, sz(96), sz(52));
        home.addView(bottom, bp);

        // full-screen panels
        appsList = overlayList(layers[APPS], "All apps", "Hold OK on an app to pin it to Home, move it or hide it");
        wallList = overlayList(layers[WALL], "Wallpaper", "Pick a style or a photo. Press Back when done");
        settingsList = overlayList(layers[SETTINGS], "Settings", "Press Back when done");

        setContentView(root);
    }

    private LinearLayout overlayList(FrameLayout layerView, String title, String hint) {
        layerView.setBackgroundColor(0xCC0A0A0F);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(sz(96), sz(64), sz(96), sz(40));
        col.addView(label(title, 56, WHITE));
        col.addView(label(hint, 28, DIM), lp(-2, -2, 0, sz(6), 0, sz(24)));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, sz(12), 0, sz(12));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        layerView.addView(col, match());
        layerView.setVisibility(View.GONE);
        return list;
    }

    private void showLayer(int l) {
        layer = l;
        for (int i = 0; i < 4; i++) layers[i].setVisibility(i == l ? View.VISIBLE : View.GONE);
        if (l == WALL) buildWall(-1);
        if (l == SETTINGS) buildSettings(-1);
        final int target = l;
        h.post(() -> {
            if (target == HOME) {
                View first = dock.getChildAt(0);
                if (first != null) first.requestFocus();
            } else {
                layers[target].requestFocus();
            }
        });
    }

    private View card(final Apps.Entry e, final boolean alwaysLabel, int w, int hgt, boolean useIcon) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(sz(14), sz(10), sz(14), sz(14));
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(e.pkg);
        final FrameLayout frame = new FrameLayout(this);
        frame.setPadding(sz(4), sz(4), sz(4), sz(4));
        frame.setBackground(ring(false, 22));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(useIcon ? e.icon : e.art);
        round(iv, 18);
        if (!useIcon && e.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0x26FFFFFF);
            int px = useIcon ? w / 7 : w / 4, py = useIcon ? hgt / 7 : hgt / 6;
            iv.setPadding(sz(px), sz(py), sz(px), sz(py));
        }
        boolean hid = hiddenNow.contains(e.pkg);
        if (hid) iv.setAlpha(0.45f);
        frame.addView(iv, new FrameLayout.LayoutParams(sz(w), sz(hgt)));
        box.addView(frame, new LinearLayout.LayoutParams(-2, -2));
        final TextView t = label(e.label + (hid ? "  (hidden)" : ""), 26, WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(sz(Math.max(w, 190)));
        t.setGravity(Gravity.CENTER);
        t.setAlpha(alwaysLabel ? 0.85f : 0f);
        box.addView(t, lp(-2, -2, 0, sz(8), 0, 0));
        box.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.08f);
            frame.setBackground(ring(focused, 22));
            if (alwaysLabel) t.setAlpha(focused ? 1f : 0.85f);
            else t.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
        });
        box.setOnClickListener(view -> launch(e));
        box.setOnLongClickListener(view -> { menu(e); return true; });
        return box;
    }

    private View moreTile() {
        View t;
        if (iconDock()) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(R.drawable.ic_apps);
            iv.setColorFilter(WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(sz(44), sz(44), sz(44), sz(44));
            t = iv;
        } else {
            TextView tv = label("All apps", 32, WHITE);
            tv.setGravity(Gravity.CENTER);
            t = tv;
        }
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rect(0x26FFFFFF, 18));
        t.setLayoutParams(lp(sz(dockW()), sz(dockH()), sz(18), sz(14), sz(18), sz(14)));
        t.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.08f);
            view.setBackground(focused ? ring(true, 18) : rect(0x26FFFFFF, 18));
        });
        t.setOnClickListener(view -> showLayer(APPS));
        return t;
    }

    private ImageView roundButton(int icon, int sizePx, final Runnable action) {
        final ImageView b = new ImageView(this);
        b.setImageResource(icon);
        int pad = sz(sizePx / 4);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(circle(0x55FFFFFF));
        b.setColorFilter(WHITE);
        b.setFocusable(true);
        b.setClickable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(sz(sizePx), sz(sizePx)));
        b.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.12f);
            b.setBackground(circle(focused ? WHITE : 0x55FFFFFF));
            b.setColorFilter(focused ? 0xFF000000 : WHITE);
        });
        b.setOnClickListener(view -> action.run());
        return b;
    }

    // ---------------------------------------------------------------- data -> views

    private void loadApps() {
        all = Apps.load(this);
        if (!prefs.has("favs")) {
            Set<String> have = new HashSet<>();
            for (Apps.Entry e : all) have.add(e.pkg);
            List<String> d = new ArrayList<>();
            for (String p : PREFERRED) if (have.contains(p)) d.add(p);
            for (int i = 0; d.isEmpty() && i < Math.min(6, all.size()); i++) d.add(all.get(i).pkg);
            prefs.saveList("favs", d);
        }
        if (!prefs.has("seeded2")) {
            List<String> favs = prefs.list("favs");
            List<String> hid = prefs.list("hidden");
            List<Apps.Entry> byName = new ArrayList<>(all);
            Collections.sort(byName, (a, b) -> a.label.compareToIgnoreCase(b.label));
            for (Apps.Entry e : byName) {
                if (favs.size() >= 8) break;
                if (e.banner && !JUNK.contains(e.label) && !favs.contains(e.pkg) && !hid.contains(e.pkg)) favs.add(e.pkg);
            }
            prefs.saveList("favs", favs);
            prefs.put("seeded2", true);
        }
        dirty = false;
    }

    private void render() {
        hiddenNow = prefs.list("hidden");
        Map<String, Apps.Entry> by = new HashMap<>();
        for (Apps.Entry e : all) by.put(e.pkg, e);
        dock.removeAllViews();
        for (String p : prefs.list("favs")) {
            Apps.Entry e = by.get(p);
            if (e != null && !hiddenNow.contains(p)) dock.addView(card(e, false, dockW(), dockH(), iconDock()));
        }
        dock.addView(moreTile());

        appsList.removeAllViews();
        List<Apps.Entry> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        LinearLayout row = null;
        int n = 0;
        for (Apps.Entry e : sorted) {
            if (n++ % 5 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                appsList.addView(row);
            }
            row.addView(card(e, true, 296, 167, false));
        }
    }

    private void focusPkg(final String pkg) {
        h.post(() -> {
            View root = layer == HOME ? dock : appsList;
            View v = root.findViewWithTag(pkg);
            if (v == null && layer == HOME) v = dock.getChildAt(0);
            if (v != null) v.requestFocus();
        });
    }

    private void launch(Apps.Entry e) {
        try { startActivity(e.launch); }
        catch (Exception ex) { toast("Can't open " + e.label); }
    }

    // ---------------------------------------------------------------- long-press menu

    private void menu(final Apps.Entry e) {
        final boolean fav = prefs.list("favs").contains(e.pkg);
        final boolean hid = prefs.list("hidden").contains(e.pkg);
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (fav) {
            names.add("Move left");        acts.add(() -> move(e.pkg, -1));
            names.add("Move right");       acts.add(() -> move(e.pkg, 1));
            names.add("Remove from Home"); acts.add(() -> setFav(e.pkg, false));
        } else if (!hid) {
            names.add("Pin to Home");      acts.add(() -> setFav(e.pkg, true));
        }
        names.add(hid ? "Unhide app" : "Hide app");
        acts.add(() -> setHidden(e.pkg, !hid));
        new AlertDialog.Builder(this).setTitle(e.label)
                .setItems(names.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
    }

    private void move(String pkg, int d) {
        List<String> favs = prefs.list("favs");
        int i = favs.indexOf(pkg), j = i + d;
        if (i < 0 || j < 0 || j >= favs.size()) return;
        Collections.swap(favs, i, j);
        prefs.saveList("favs", favs);
        render();
        focusPkg(pkg);
    }

    private void setFav(String pkg, boolean on) {
        List<String> favs = prefs.list("favs");
        favs.remove(pkg);
        if (on) {
            favs.add(pkg);
            List<String> hid = prefs.list("hidden");
            if (hid.remove(pkg)) prefs.saveList("hidden", hid);
        }
        prefs.saveList("favs", favs);
        render();
        focusPkg(pkg);
    }

    private void setHidden(String pkg, boolean on) {
        List<String> hid = prefs.list("hidden");
        hid.remove(pkg);
        if (on) {
            hid.add(pkg);
            List<String> favs = prefs.list("favs");
            favs.remove(pkg);
            prefs.saveList("favs", favs);
        }
        prefs.saveList("hidden", hid);
        render();
        focusPkg(pkg);
    }

    // ---------------------------------------------------------------- rows (wallpaper + settings)

    private void row(final LinearLayout list, String text, final Act act) {
        final int idx = list.getChildCount();
        TextView t = label(text, 34, WHITE);
        t.setPadding(sz(36), sz(24), sz(36), sz(24));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rect(0x33000000, 24));
        t.setOnFocusChangeListener((view, focused) -> view.setBackground(rect(focused ? ACCENT : 0x33000000, 24)));
        t.setOnClickListener(view -> act.go(idx));
        list.addView(t, lp(sz(1300), -2, 0, 0, 0, sz(14)));
    }

    private void focusRow(LinearLayout list, int idx) {
        if (idx >= 0 && idx < list.getChildCount()) list.getChildAt(idx).requestFocus();
    }

    private static String mark(String cur, String key) { return cur.equals(key) ? "●   " : "      "; }
    private static String onOff(boolean v) { return v ? "On" : "Off"; }

    private void buildWall(int focus) {
        wallList.removeAllViews();
        String cur = prefs.str("wallpaper", "g0");
        for (int i = 0; i < WallpaperView.NAMES.length; i++) {
            final String key = "g" + i;
            row(wallList, mark(cur, key) + "Style:  " + WallpaperView.NAMES[i], idx -> {
                prefs.put("wallpaper", key);
                applyWallpaper();
                buildWall(idx);
            });
        }
        row(wallList, mark(cur, "photo") + "Photo from the internet  (OK = get a new one)", idx -> {
            toast("Downloading a photo...");
            Wallpapers.fetch(this, "https://picsum.photos/1920/1080?random=" + System.currentTimeMillis(),
                    (bmp, err) -> onPhoto(bmp, err, idx));
        });
        row(wallList, "      Photo from a link...", idx -> askLink(idx));
        for (final String name : Wallpapers.assetNames(this)) {
            final String key = "asset:" + name;
            row(wallList, mark(cur, key) + "My photo:  " + niceName(name), idx -> {
                prefs.put("wallpaper", key);
                applyWallpaper();
                buildWall(idx);
            });
        }
        if (Wallpapers.assetNames(this).size() > 1) {
            row(wallList, mark(cur, "rotate") + "Rotate through all my photos  (every 90 seconds)", idx -> {
                prefs.put("wallpaper", "rotate");
                applyWallpaper();
                buildWall(idx);
            });
        }
        focusRow(wallList, focus);
    }

    private static String niceName(String file) {
        int dot = file.lastIndexOf('.');
        String s = dot > 0 ? file.substring(0, dot) : file;
        return s.replace('_', ' ').replace('-', ' ');
    }

    private void onPhoto(Bitmap bmp, String err, int idx) {
        if (bmp == null) { toast("Couldn't use that photo" + (err == null ? "" : ": " + err)); return; }
        prefs.put("wallpaper", "photo");
        wall.setPhoto(bmp, true);
        if (layer == WALL) buildWall(idx);
    }

    private void askLink(final int idx) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("https://...");
        new AlertDialog.Builder(this).setTitle("Link to a picture").setView(in)
                .setPositiveButton("Download", (d, w) -> {
                    String url = in.getText().toString().trim();
                    if (url.isEmpty()) return;
                    toast("Downloading...");
                    Wallpapers.fetch(this, url, (bmp, err) -> onPhoto(bmp, err, idx));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void buildSettings(int focus) {
        settingsList.removeAllViews();
        row(settingsList, "Dock style:   " + (iconDock() ? "Icons" : "Tiles"), idx -> {
            prefs.put("dock", iconDock() ? "tiles" : "icons");
            render();
            buildSettings(idx);
        });
        row(settingsList, "Moving wallpapers:   " + onOff(prefs.flag("anim", true)), idx -> {
            prefs.put("anim", !prefs.flag("anim", true));
            applyWallpaper();
            buildSettings(idx);
        });
        row(settingsList, "24-hour clock:   " + onOff(prefs.flag("h24", false)), idx -> {
            prefs.put("h24", !prefs.flag("h24", false));
            applyPrefs();
            buildSettings(idx);
        });
        row(settingsList, "Greeting & weather:   " + onOff(extras()), idx -> {
            prefs.put("extras", !extras());
            applyPrefs();
            if (extras()) fetchWeather();
            buildSettings(idx);
        });
        if (extras()) {
            String name = prefs.str("name", ""), city = prefs.str("city", "");
            row(settingsList, "Your name:   " + (name.isEmpty() ? "not set" : name),
                    idx -> ask("Your name", "name", idx, false));
            row(settingsList, "Weather city:   " + (city.isEmpty() ? "Automatic (from your internet connection)" : city),
                    idx -> ask("City (leave empty for automatic)", "city", idx, true));
            row(settingsList, "Temperature:   " + (prefs.flag("f", false) ? "Fahrenheit" : "Celsius"), idx -> {
                prefs.put("f", !prefs.flag("f", false));
                fetchWeather();
                buildSettings(idx);
            });
        }
        row(settingsList, "Now playing:   " + (npStarted ? "Connected" : "Needs access  (press OK)"),
                idx -> nowPlayingHelp());
        row(settingsList, "Open Fire TV settings", idx -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ex) { toast("Couldn't open Fire TV settings"); }
        });
        row(settingsList, "Reset pinned and hidden apps", idx -> {
            prefs.remove("favs");
            prefs.remove("hidden");
            loadApps();
            render();
            buildSettings(idx);
        });
        focusRow(settingsList, focus);
    }

    private void ask(String title, final String key, final int idx, final boolean weather) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(prefs.str(key, ""));
        new AlertDialog.Builder(this).setTitle(title).setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.put(key, in.getText().toString().trim());
                    applyPrefs();
                    buildSettings(idx);
                    if (weather) fetchWeather();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void updateNpHint() { npHint.setVisibility(npStarted ? View.GONE : View.VISIBLE); }

    private void tryStartNowPlaying() {
        if (!npStarted) npStarted = nowPlaying.start();
        updateNpHint();
        if (layer == SETTINGS) buildSettings(-1);
    }

    /** Works once the one-time ADB permission has been given; otherwise returns false. */
    private boolean enableListener() {
        try {
            String flat = new ComponentName(this, NowPlayingService.class).flattenToString();
            String cur = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (cur == null || cur.isEmpty()) cur = flat;
            else if (!cur.contains(flat)) cur = cur + ":" + flat;
            android.provider.Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", cur);
        } catch (SecurityException e) {
            return false;
        }
        toast("Turning on Now playing...");
        h.postDelayed(() -> tryStartNowPlaying(), 1500);
        h.postDelayed(() -> tryStartNowPlaying(), 4000);
        return true;
    }

    private void nowPlayingHelp() {
        if (npStarted) { toast("Now playing is connected. Play something in Spotify and it will appear on Home."); return; }
        if (enableListener()) return;
        String msg = "One-time step needed. On your Mac, with ADB connected to this Firestick, run:\n\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\n\n"
                + "Then come back here and press OK on Now playing again.";
        new AlertDialog.Builder(this).setTitle("Now playing").setMessage(msg)
                .setPositiveButton("Try the settings screen", (d, w) -> openAccessSettings())
                .setNegativeButton("Close", null).show();
    }

    private void openAccessSettings() {
        try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return; }
        catch (Exception ignored) {}
        try {
            startActivity(new Intent().setClassName("com.android.settings",
                    "com.android.settings.Settings$NotificationAccessSettingsActivity"));
            return;
        } catch (Exception ignored) {}
        toast("This Fire TV has no such screen. Use the ADB step instead.");
    }

    // ---------------------------------------------------------------- wallpaper, clock, greeting, weather, progress

    private void applyWallpaper() {
        h.removeCallbacks(rotateTask);
        wall.setAnimated(prefs.flag("anim", true));
        final boolean first = !prefs.has("wallpaper");
        String key = prefs.str("wallpaper", "g0");
        if (key.startsWith("g")) {
            int i = 0;
            try { i = Integer.parseInt(key.substring(1)); } catch (NumberFormatException ignored) {}
            if (i < 0 || i >= WallpaperView.NAMES.length) i = 0;
            wall.setPhoto(null);
            wall.configure(i, prefs.flag("anim", true));
            if (first) {
                // First run: try a nice photo from the internet instead of the plain colours.
                Wallpapers.fetch(this, "https://picsum.photos/1920/1080?random=" + System.currentTimeMillis(), (bmp, err) -> {
                    if (bmp != null && !prefs.has("wallpaper")) { prefs.put("wallpaper", "photo"); wall.setPhoto(bmp, true); }
                });
            }
        } else if (key.equals("rotate")) {
            rotateNext(true);
        } else {
            Wallpapers.load(this, key, (bmp, err) -> { if (bmp != null) wall.setPhoto(bmp, true); });
        }
    }

    /** Shows the next of "my photos" and schedules the one after it. */
    private void rotateNext(final boolean instant) {
        List<String> names = Wallpapers.assetNames(this);
        if (names.isEmpty()) return;
        String k = "asset:" + names.get(rotateIdx++ % names.size());
        Wallpapers.load(this, k, (bmp, err) -> {
            if (bmp != null && "rotate".equals(prefs.str("wallpaper", ""))) wall.setPhoto(bmp, !instant);
        });
        h.removeCallbacks(rotateTask);
        if (resumed || instant) h.postDelayed(rotateTask, ROTATE_MS);
    }

    private void applyPrefs() {
        String f = prefs.flag("h24", false) ? "HH:mm" : "h:mm";
        clock.setFormat12Hour(f);
        clock.setFormat24Hour(f);
        updateGreeting();
        applyExtras();
    }

    private void updateGreeting() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
        String n = prefs.str("name", "");
        greetView.setText((n.isEmpty() ? g : g + ", " + n).toUpperCase());
    }

    private void fetchWeather() {
        if (!extras()) return;
        lastWeather = System.currentTimeMillis();
        Weather.fetch(prefs.str("city", ""), prefs.flag("f", false), text -> {
            if (text.isEmpty()) return;
            String[] p = text.split("\n", 2);
            weatherPlace.setText(p[0]);
            weatherTemp.setText(p.length > 1 ? p[1] : "");
        });
    }

    private void updateProgress() {
        if (nowPlaying == null || npCard.getVisibility() != View.VISIBLE) return;
        long d = nowPlaying.duration(), p = nowPlaying.position();
        npBar.set(d > 0 ? Math.min(1f, p / (float) d) : 0f);
        npTime.setText(d > 0 ? fmt(p) + " / " + fmt(d) : fmt(p));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---------------------------------------------------------------- look & feel helpers

    private int npPx(int px) { return Math.round(px * NP_SCALE); }
    private int np(int px) { return sz(npPx(px)); }

    private boolean extras() { return prefs.flag("extras", false); }
    private boolean iconDock() { return !"tiles".equals(prefs.str("dock", "icons")); }
    private int dockW() { return iconDock() ? 150 : 340; }
    private int dockH() { return iconDock() ? 150 : 191; }

    private void applyExtras() {
        int v = extras() ? View.VISIBLE : View.GONE;
        greetView.setVisibility(v);
        weatherPlace.setVisibility(v);
        weatherTemp.setVisibility(v);
    }

    /** Uses Manrope if you drop Manrope-Light/Regular/Medium.ttf into assets/fonts, otherwise the system fonts. */
    private void loadFonts() {
        fLight = font("Light", Typeface.create("sans-serif-light", Typeface.NORMAL));
        fReg = font("Regular", Typeface.create("sans-serif", Typeface.NORMAL));
        fMed = font("Medium", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private Typeface font(String weight, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/Manrope-" + weight + ".ttf"); }
        catch (Exception e) { return fallback; }
    }

    // ---------------------------------------------------------------- now-playing card

    private View buildNowPlayingCard() {
        npCard = new FrameLayout(this);
        npCard.setVisibility(View.GONE);
        npCard.setBackgroundColor(0xFF0C0C12);
        round(npCard, 40);

        // soft colour wash taken from the album art, dimmed so the text stays readable
        npBlur = new ImageView(this);
        npBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npBlur.setAlpha(0.7f);
        npCard.addView(npBlur, match());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x660A0A12, 0xAD0A0A12}));
        npCard.addView(shade, match());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(np(28), np(28), np(36), np(28));

        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackgroundColor(0x26FFFFFF);
        round(npArt, 26);
        row.addView(npArt, new LinearLayout.LayoutParams(np(184), np(184)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(np(36), 0, np(28), 0);
        LinearLayout src = new LinearLayout(this);
        src.setGravity(Gravity.CENTER_VERTICAL);
        npEq = new EqualizerView(this);
        src.addView(npEq, new LinearLayout.LayoutParams(np(30), np(24)));
        npSource = label("", Math.max(npPx(21), 16), DIM);
        npSource.setLetterSpacing(0.18f);
        src.addView(npSource, lp(-2, -2, np(14), 0, 0, 0));
        npTitle = label("", Math.max(npPx(46), 22), WHITE);
        npTitle.setTypeface(fMed);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        npTitle.setMarqueeRepeatLimit(-1);
        npTitle.setSelected(true);   // keeps a long title scrolling without needing focus
        npArtist = label("", Math.max(npPx(30), 18), DIM);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout barRow = new LinearLayout(this);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        npBar = new ProgressLine(this);
        barRow.addView(npBar, new LinearLayout.LayoutParams(0, np(6), 1f));
        npTime = label("", Math.max(npPx(22), 16), DIM);
        barRow.addView(npTime, lp(-2, -2, np(20), 0, 0, 0));
        info.addView(src);
        info.addView(npTitle, lp(-1, -2, 0, np(10), 0, 0));
        info.addView(npArtist);
        info.addView(barRow, lp(-1, -2, 0, np(18), 0, 0));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView prev = npButton(R.drawable.ic_prev, 88, false, () -> nowPlaying.previous());
        npPlay = npButton(R.drawable.ic_pause, 112, true, () -> nowPlaying.toggle());
        ImageView next = npButton(R.drawable.ic_next, 88, false, () -> nowPlaying.next());
        row.addView(prev, lp(np(88), np(88), 0, 0, 0, 0));
        row.addView(npPlay, lp(np(112), np(112), np(18), 0, 0, 0));
        row.addView(next, lp(np(88), np(88), np(18), 0, 0, 0));
        npCard.addView(row, new FrameLayout.LayoutParams(-1, -2));

        // thin glass edge, tinted with the album colour
        View edge = new View(this);
        npBorder = new GradientDrawable();
        npBorder.setColor(0x00000000);
        npBorder.setCornerRadius(np(40));
        npBorder.setStroke(Math.max(2, np(2)), 0x40FFFFFF);
        edge.setBackground(npBorder);
        npCard.addView(edge, match());
        applyAccent(ACCENT);
        return npCard;
    }

    private ImageView npButton(int icon, int sizePx, final boolean primary, final Runnable action) {
        final ImageView b = new ImageView(this);
        b.setImageResource(icon);
        int pad = np(sizePx / 4);
        b.setPadding(pad, pad, pad, pad);
        b.setFocusable(true);
        b.setClickable(true);
        styleNp(b, primary, false);
        b.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.1f);
            styleNp(b, primary, focused);
        });
        b.setOnClickListener(view -> action.run());
        return b;
    }

    private void styleNp(ImageView b, boolean primary, boolean focused) {
        b.setBackground(circle(focused ? WHITE : primary ? npAccent : 0x33FFFFFF));
        b.setColorFilter(focused || primary ? 0xFF0B0B10 : WHITE);
    }

    private void showTrack(NowPlaying.Track t) {
        if (t == null) {
            npLast = null;
            npEq.setPlaying(false);
            if (npCard.getVisibility() == View.VISIBLE) {
                npCard.animate().cancel();
                npCard.animate().alpha(0f).translationY(sz(20)).setDuration(220).withEndAction(() -> {
                    if (npLast == null) npCard.setVisibility(View.GONE);
                }).start();
            }
            return;
        }
        npLast = t;
        npTitle.setText(t.title);
        String line = t.artist == null ? "" : t.artist;
        if (t.album != null && !t.album.equals(t.title)) line = line.isEmpty() ? t.album : line + "  \u00B7  " + t.album;
        npArtist.setText(line);
        String src = t.source == null ? "" : t.source.toUpperCase();
        npSource.setText(t.playing ? src : (src.isEmpty() ? "PAUSED" : src + "  \u00B7  PAUSED"));
        npEq.setPlaying(t.playing);
        npPlay.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (t.art != npArtShown) {
            npArtShown = t.art;
            npArt.setImageBitmap(t.art);
            if (t.art != null) {
                try { npBlur.setImageBitmap(tiny(t.art)); } catch (RuntimeException e) { npBlur.setImageDrawable(null); }
                animateAccent(accentOf(t.art));
            } else {
                npBlur.setImageDrawable(null);
                animateAccent(ACCENT);
            }
        }
        if (npCard.getVisibility() != View.VISIBLE) {
            npCard.setAlpha(0f);
            npCard.setTranslationY(sz(20));
            npCard.setVisibility(View.VISIBLE);
        }
        if (npCard.getAlpha() < 1f || npCard.getTranslationY() != 0f) {
            npCard.animate().cancel();
            npCard.animate().alpha(1f).translationY(0f).setDuration(280).start();
        }
        updateProgress();
    }

    /** A 10x10 copy of the art; stretched up it becomes a soft blur for almost no cost. */
    private static Bitmap tiny(Bitmap b) {
        Bitmap mid = Bitmap.createScaledBitmap(b, 48, 48, true);
        return Bitmap.createScaledBitmap(mid, 10, 10, true);
    }

    /** Picks the most colourful, reasonably bright colour in the art. */
    private static int accentOf(Bitmap src) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(src, 24, 24, true);
            float[] hsv = new float[3];
            float[] wgt = new float[12], sr = new float[12], sg = new float[12], sb = new float[12];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = s.getPixel(x, y);
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.2f || hsv[2] < 0.25f) continue;
                    int k = Math.min(11, (int) (hsv[0] / 30f));
                    float w = hsv[1] * hsv[2];
                    wgt[k] += w;
                    sr[k] += Color.red(p) * w;
                    sg[k] += Color.green(p) * w;
                    sb[k] += Color.blue(p) * w;
                }
            }
            int best = -1;
            for (int k = 0; k < 12; k++) if (wgt[k] > 0 && (best < 0 || wgt[k] > wgt[best])) best = k;
            if (best < 0) return 0xFF9AA0B8;   // grey / black-and-white art
            int c = Color.rgb(Math.round(sr[best] / wgt[best]), Math.round(sg[best] / wgt[best]),
                    Math.round(sb[best] / wgt[best]));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.max(hsv[1], 0.45f);
            hsv[2] = Math.min(0.95f, Math.max(hsv[2], 0.7f));
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return ACCENT;
        }
    }

    private void animateAccent(int to) {
        if (accentAnim != null) accentAnim.cancel();
        accentAnim = ValueAnimator.ofArgb(npAccent, to);
        accentAnim.setDuration(450);
        accentAnim.addUpdateListener(a -> applyAccent((Integer) a.getAnimatedValue()));
        accentAnim.start();
    }

    private void applyAccent(int c) {
        npAccent = c;
        if (npBar != null) npBar.setColor(c);
        if (npEq != null) npEq.setColor(c);
        if (npBorder != null) npBorder.setStroke(Math.max(2, sz(2)), (c & 0x00FFFFFF) | 0x66000000);
        if (npPlay != null && !npPlay.isFocused()) styleNp(npPlay, true, false);
    }

    // ---------------------------------------------------------------- helpers

    /** All sizes are written for a 1920-pixel-wide screen and scaled to the real one. */
    private int sz(int px) { return Math.round(px * getResources().getDisplayMetrics().widthPixels / 1920f); }

    private TextView label(String s, int px, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(px));
        t.setTextColor(color);
        t.setTypeface(fReg);
        t.setShadowLayer(sz(5), 0, sz(1), 0x66000000);
        return t;
    }

    private static FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }

    private static LinearLayout.LayoutParams lp(int w, int hh, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hh);
        p.setMargins(l, t, r, b);
        return p;
    }

    private GradientDrawable rect(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(sz(radiusPx));
        return g;
    }

    /** Focus ring for app tiles: a soft white outline with a faint fill. */
    private GradientDrawable ring(boolean focused, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(sz(radiusPx));
        g.setColor(focused ? 0x26FFFFFF : 0x00FFFFFF);
        if (focused) g.setStroke(Math.max(2, sz(3)), 0xF2FFFFFF);
        return g;
    }

    /** Frosted-glass panel: dark translucent fill with a hairline edge. */
    private GradientDrawable glass(int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x80101018);
        g.setCornerRadius(sz(radiusPx));
        g.setStroke(Math.max(1, sz(2)), 0x24FFFFFF);
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void round(View v, int radiusPx) {
        final float r = sz(radiusPx);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private void pop(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        v.setTranslationZ(focused ? sz(10) : 0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
