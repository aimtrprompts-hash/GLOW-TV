package app.glow.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
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

    private Prefs prefs;
    private WallpaperView wall;
    private final FrameLayout[] layers = new FrameLayout[4];
    private final ImageView[] topButtons = new ImageView[3];
    private LinearLayout dock, appsList, wallList, settingsList, npCard;
    private TextView greetView, weatherPlace, weatherTemp, npTitle, npArtist, npTime;
    private TextClock clock, date;
    private ImageView npArt, npPlay;
    private ProgressLine npBar;
    private NowPlaying nowPlaying;
    private List<Apps.Entry> all = new ArrayList<>();
    private List<String> hiddenNow = new ArrayList<>();
    private boolean npStarted, dirty, resumed;
    private int layer, minutes;
    private long lastWeather;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            updateGreeting();
            if (++minutes % 30 == 0) fetchWeather();
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
        buildUi();
        nowPlaying = new NowPlaying(this, (title, artist, art, playing) -> {
            if (title == null) { npCard.setVisibility(View.GONE); return; }
            npCard.setVisibility(View.VISIBLE);
            npTitle.setText(title);
            npArtist.setText(artist == null ? "" : artist);
            npArt.setImageBitmap(art);
            npPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            updateProgress();
        });
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
        updateGreeting();
        h.removeCallbacks(minute);
        h.postDelayed(minute, 60000);
        h.removeCallbacks(progress);
        h.post(progress);
        if (System.currentTimeMillis() - lastWeather > 20 * 60 * 1000L) fetchWeather();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        wall.pause();
        h.removeCallbacks(minute);
        h.removeCallbacks(progress);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pkgs);
        nowPlaying.stop();
        h.removeCallbacksAndMessages(null);
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

        // top-left: greeting, big clock, date
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greetView = label("", 30, DIM);
        greetView.setLetterSpacing(0.12f);
        clock = new TextClock(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(160));
        clock.setTextColor(WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(sz(8), 0, sz(2), 0x99000000);
        clock.setIncludeFontPadding(false);
        date = new TextClock(this);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(38));
        date.setTextColor(DIM);
        date.setShadowLayer(sz(6), 0, sz(2), 0x99000000);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        tl.addView(greetView);
        tl.addView(clock);
        tl.addView(date);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(sz(96), sz(56), 0, 0);
        home.addView(tl, tlp);

        // top-right: three round buttons, then location + weather
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.VERTICAL);
        tr.setGravity(Gravity.END);
        LinearLayout btns = new LinearLayout(this);
        int[] icons = {R.drawable.ic_apps, R.drawable.ic_wallpaper, R.drawable.ic_settings};
        for (int i = 0; i < 3; i++) {
            final int target = i + 1;
            ImageView bt = roundButton(icons[i], 96, () -> showLayer(target));
            topButtons[i] = bt;
            btns.addView(bt, lp(sz(96), sz(96), i == 0 ? 0 : sz(20), 0, 0, 0));
        }
        weatherPlace = label("", 34, WHITE);
        weatherPlace.setGravity(Gravity.END);
        weatherTemp = label("", 28, DIM);
        weatherTemp.setGravity(Gravity.END);
        tr.addView(btns, lp(-2, -2, 0, 0, 0, 0));
        tr.addView(weatherPlace, lp(-2, -2, 0, sz(28), 0, 0));
        tr.addView(weatherTemp, lp(-2, -2, 0, 0, 0, 0));
        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        trp.setMargins(0, sz(48), sz(96), 0);
        home.addView(tr, trp);

        // bottom: now-playing card above the app dock
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);

        npCard = new LinearLayout(this);
        npCard.setGravity(Gravity.CENTER_VERTICAL);
        npCard.setPadding(sz(24), sz(24), sz(32), sz(24));
        npCard.setBackground(rect(GLASS, 40));
        npCard.setVisibility(View.GONE);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackgroundColor(0x33FFFFFF);
        round(npArt, 22);
        npCard.addView(npArt, new LinearLayout.LayoutParams(sz(168), sz(168)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(sz(32), 0, sz(24), 0);
        npTitle = label("", 44, WHITE);
        npTitle.setTypeface(Typeface.DEFAULT_BOLD);
        npArtist = label("", 32, DIM);
        for (TextView t : new TextView[]{npTitle, npArtist}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
        }
        LinearLayout barRow = new LinearLayout(this);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        npBar = new ProgressLine(this);
        barRow.addView(npBar, new LinearLayout.LayoutParams(0, sz(8), 1f));
        npTime = label("", 24, DIM);
        barRow.addView(npTime, lp(-2, -2, sz(20), 0, 0, 0));
        info.addView(npTitle);
        info.addView(npArtist);
        info.addView(barRow, lp(-1, -2, 0, sz(16), 0, 0));
        npCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView prev = roundButton(R.drawable.ic_prev, 104, () -> nowPlaying.previous());
        npPlay = roundButton(R.drawable.ic_pause, 104, () -> nowPlaying.toggle());
        ImageView next = roundButton(R.drawable.ic_next, 104, () -> nowPlaying.next());
        npCard.addView(prev, lp(sz(104), sz(104), 0, 0, 0, 0));
        npCard.addView(npPlay, lp(sz(104), sz(104), sz(16), 0, 0, 0));
        npCard.addView(next, lp(sz(104), sz(104), sz(16), 0, 0, 0));
        bottom.addView(npCard, new LinearLayout.LayoutParams(sz(1300), -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(sz(24), sz(20), sz(24), sz(10));
        hs.setBackground(rect(GLASS, 44));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        hs.addView(dock);
        bottom.addView(hs, lp(-1, -2, 0, sz(24), 0, 0));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(sz(80), 0, sz(80), sz(48));
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

    private View card(final Apps.Entry e, final boolean alwaysLabel, int w, int hgt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(sz(10), sz(10), sz(10), sz(4));
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(e.pkg);
        final FrameLayout frame = new FrameLayout(this);
        frame.setPadding(sz(4), sz(4), sz(4), sz(4));
        frame.setBackground(rect(0x00FFFFFF, 20));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(e.art);
        round(iv, 16);
        if (e.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0x33FFFFFF);
            iv.setPadding(sz(w / 4), sz(hgt / 6), sz(w / 4), sz(hgt / 6));
        }
        boolean hid = hiddenNow.contains(e.pkg);
        if (hid) iv.setAlpha(0.45f);
        frame.addView(iv, new FrameLayout.LayoutParams(sz(w), sz(hgt)));
        box.addView(frame);
        final TextView t = label(e.label + (hid ? "  (hidden)" : ""), 28, WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(sz(w));
        t.setGravity(Gravity.CENTER);
        t.setAlpha(alwaysLabel ? 0.85f : 0f);
        box.addView(t, lp(-2, -2, 0, sz(8), 0, 0));
        box.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.1f);
            frame.setBackground(rect(focused ? 0xFFFFFFFF : 0x00FFFFFF, 20));
            if (alwaysLabel) t.setAlpha(focused ? 1f : 0.85f);
            else t.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
        });
        box.setOnClickListener(view -> launch(e));
        box.setOnLongClickListener(view -> { menu(e); return true; });
        return box;
    }

    private View moreTile() {
        TextView t = label("All apps", 34, WHITE);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rect(0x33FFFFFF, 16));
        t.setLayoutParams(lp(sz(340), sz(191), sz(14), sz(14), sz(14), sz(14)));
        t.setOnFocusChangeListener((view, focused) -> {
            pop(view, focused, 1.1f);
            view.setBackground(rect(focused ? ACCENT : 0x33FFFFFF, 16));
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
        dirty = false;
    }

    private void render() {
        hiddenNow = prefs.list("hidden");
        Map<String, Apps.Entry> by = new HashMap<>();
        for (Apps.Entry e : all) by.put(e.pkg, e);
        dock.removeAllViews();
        for (String p : prefs.list("favs")) {
            Apps.Entry e = by.get(p);
            if (e != null && !hiddenNow.contains(p)) dock.addView(card(e, false, 340, 191));
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
            row.addView(card(e, true, 296, 167));
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
        wall.setPhoto(bmp);
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
        row(settingsList, "24-hour clock:   " + onOff(prefs.flag("h24", false)), idx -> {
            prefs.put("h24", !prefs.flag("h24", false));
            applyPrefs();
            buildSettings(idx);
        });
        row(settingsList, "Moving colour wallpapers:   " + onOff(prefs.flag("anim", true)), idx -> {
            prefs.put("anim", !prefs.flag("anim", true));
            applyWallpaper();
            buildSettings(idx);
        });
        row(settingsList, "Now playing:   " + (npStarted ? "Connected" : "Needs access  (press OK)"),
                idx -> nowPlayingHelp());
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

    private void nowPlayingHelp() {
        String comp = getPackageName() + "/" + NowPlayingService.class.getName();
        String msg = npStarted
                ? "Now playing is connected. Start music in Spotify, Amazon Music or similar and it will appear on Home."
                : "To show what's playing, Glow needs notification access.\n\nTry \"Open settings\". If your Fire TV has no such screen, run this on your Mac with ADB connected:\n\n"
                + "adb shell settings get secure enabled_notification_listeners\n\n"
                + "If that prints a value, put it plus a colon before the component below; if it prints null, use the component alone:\n\n"
                + "adb shell settings put secure enabled_notification_listeners " + comp;
        new AlertDialog.Builder(this).setTitle("Now playing").setMessage(msg)
                .setPositiveButton("Open settings", (d, w) -> {
                    try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                    catch (Exception ex) { toast("Not available on this device"); }
                })
                .setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- wallpaper, clock, greeting, weather, progress

    private void applyWallpaper() {
        String key = prefs.str("wallpaper", "g0");
        if (key.startsWith("g")) {
            int i = 0;
            try { i = Integer.parseInt(key.substring(1)); } catch (NumberFormatException ignored) {}
            if (i < 0 || i >= WallpaperView.NAMES.length) i = 0;
            wall.setPhoto(null);
            wall.configure(i, prefs.flag("anim", true));
        } else {
            Wallpapers.load(this, key, (bmp, err) -> { if (bmp != null) wall.setPhoto(bmp); });
        }
    }

    private void applyPrefs() {
        String f = prefs.flag("h24", false) ? "HH:mm" : "h:mm";
        clock.setFormat12Hour(f);
        clock.setFormat24Hour(f);
        updateGreeting();
    }

    private void updateGreeting() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
        String n = prefs.str("name", "");
        greetView.setText((n.isEmpty() ? g : g + ", " + n).toUpperCase());
    }

    private void fetchWeather() {
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

    // ---------------------------------------------------------------- helpers

    /** All sizes are written for a 1920-pixel-wide screen and scaled to the real one. */
    private int sz(int px) { return Math.round(px * getResources().getDisplayMetrics().widthPixels / 1920f); }

    private TextView label(String s, int px, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sz(px));
        t.setTextColor(color);
        t.setShadowLayer(sz(6), 0, sz(2), 0x99000000);
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
