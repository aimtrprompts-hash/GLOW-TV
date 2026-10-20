package app.glow.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private static final int WHITE = 0xFFFFFFFF, DIM = 0xB3FFFFFF;
    private static final String[] TABS = {"Home", "Apps", "Settings"};
    // First-run favourites (only the ones you have installed are used).
    private static final String[] PREFERRED = {"com.netflix.ninja", "com.amazon.firetv.youtube", "com.amazon.avod",
            "com.spotify.tv.android", "com.amazon.music.tv", "org.xbmc.kodi", "com.plexapp.android", "org.videolan.vlc"};

    private Prefs prefs;
    private WallpaperView wall;
    private final TextView[] tabViews = new TextView[3];
    private TextView weatherView, greetView, npTitle, npArtist, npState;
    private TextClock clock, date;
    private LinearLayout homeView, homeRow, appsList, settingsList, npWidget;
    private ScrollView appsView, settingsView;
    private ImageView npArt;
    private NowPlaying nowPlaying;
    private List<Apps.Entry> all = new ArrayList<>();
    private List<String> hiddenNow = new ArrayList<>();
    private boolean npStarted, dirty, resumed;
    private int tab, minutes;
    private long lastWeather;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            updateGreeting();
            if (++minutes % 30 == 0) fetchWeather();
            h.postDelayed(this, 60000);
        }
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
            if (title == null) { npWidget.setVisibility(View.GONE); return; }
            npWidget.setVisibility(View.VISIBLE);
            npTitle.setText(title);
            npArtist.setText(artist == null ? "" : artist);
            npArt.setImageBitmap(art);
            npState.setText(playing ? "Playing  ·  press OK to pause" : "Paused  ·  press OK to play");
        });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgs, f);
        loadApps();
        applyPrefs();
        render();
        showTab(0);
        h.post(() -> { View first = homeRow.getChildAt(0); if (first != null) first.requestFocus(); });
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
        if (System.currentTimeMillis() - lastWeather > 20 * 60 * 1000L) fetchWeather();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        wall.pause();
        h.removeCallbacks(minute);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pkgs);
        nowPlaying.stop();
        h.removeCallbacksAndMessages(null);
    }

    @Override public void onBackPressed() {
        if (tab != 0) { showTab(0); tabViews[0].requestFocus(); } else super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI construction

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        wall = new WallpaperView(this);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(24), dp(48), dp(20));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // top bar: tabs on the left, location + weather on the right
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = label(TABS[i], 20, WHITE);
            t.setPadding(dp(18), dp(8), dp(18), dp(8));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, f) -> { if (f) showTab(idx); styleTabs(); });
            tabViews[i] = t;
            bar.addView(t);
        }
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        weatherView = label("", 16, DIM);
        bar.addView(weatherView);
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout stage = new FrameLayout(this);
        col.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- Home tab
        homeView = new LinearLayout(this);
        homeView.setOrientation(LinearLayout.VERTICAL);
        homeView.setPadding(dp(8), dp(24), 0, 0);
        greetView = label("", 26, WHITE);
        clock = new TextClock(this);
        clock.setTextSize(84);
        clock.setTextColor(WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(6, 0, 2, 0x99000000);
        date = new TextClock(this);
        date.setTextSize(20);
        date.setTextColor(DIM);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        homeView.addView(greetView);
        homeView.addView(clock);
        homeView.addView(date);

        npWidget = new LinearLayout(this);
        npWidget.setGravity(Gravity.CENTER_VERTICAL);
        npWidget.setPadding(dp(12), dp(12), dp(20), dp(12));
        npWidget.setBackground(rect(0x66000000, 12));
        npWidget.setFocusable(true);
        npWidget.setClickable(true);
        npWidget.setVisibility(View.GONE);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackgroundColor(0x33FFFFFF);
        npWidget.addView(npArt, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);
        npTitle = label("", 18, WHITE);
        npArtist = label("", 15, DIM);
        npState = label("", 12, DIM);
        for (TextView t : new TextView[]{npTitle, npArtist}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(420));
        }
        texts.addView(npTitle);
        texts.addView(npArtist);
        texts.addView(npState);
        npWidget.addView(texts);
        npWidget.setOnFocusChangeListener((v, f) -> { pop(v, f, 1.04f); v.setBackground(rect(f ? 0x99000000 : 0x66000000, 12)); });
        npWidget.setOnClickListener(v -> nowPlaying.toggle());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = dp(18);
        homeView.addView(npWidget, nl);

        homeView.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(14), 0, dp(14));
        homeRow = new LinearLayout(this);
        homeRow.setClipChildren(false);
        hs.addView(homeRow);
        homeView.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        stage.addView(homeView, new FrameLayout.LayoutParams(-1, -1));

        // ---- Apps tab
        appsView = new ScrollView(this);
        appsView.setVerticalScrollBarEnabled(false);
        appsView.setClipChildren(false);
        appsView.setClipToPadding(false);
        appsView.setPadding(0, dp(10), 0, dp(10));
        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        appsList.setClipChildren(false);
        appsView.addView(appsList);
        stage.addView(appsView, new FrameLayout.LayoutParams(-1, -1));

        // ---- Settings tab
        settingsView = new ScrollView(this);
        settingsView.setVerticalScrollBarEnabled(false);
        settingsView.setPadding(dp(8), dp(16), 0, dp(10));
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsView.addView(settingsList);
        stage.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private View card(final Apps.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(e.pkg);
        final FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
        frame.setBackground(rect(0x22FFFFFF, 8));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(e.art);
        iv.setBackgroundColor(0x55000000);
        if (e.banner) iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        else { iv.setScaleType(ImageView.ScaleType.FIT_CENTER); iv.setPadding(dp(28), dp(14), dp(28), dp(14)); }
        frame.addView(iv, new FrameLayout.LayoutParams(dp(176), dp(99)));
        box.addView(frame);
        TextView t = label(e.label + (hiddenNow.contains(e.pkg) ? " (hidden)" : ""), 14, DIM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(176));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(6);
        box.addView(t, tl);
        box.setOnFocusChangeListener((v, f) -> { pop(v, f, 1.1f); frame.setBackground(rect(f ? 0xFFFFFFFF : 0x22FFFFFF, 8)); });
        box.setOnClickListener(v -> launch(e));
        box.setOnLongClickListener(v -> { menu(e); return true; });
        return box;
    }

    private View moreTile() {
        TextView t = label("All apps", 18, WHITE);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rect(0x33FFFFFF, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(182), dp(105));
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        t.setLayoutParams(lp);
        t.setOnFocusChangeListener((v, f) -> { pop(v, f, 1.1f); v.setBackground(rect(f ? 0x66FFFFFF : 0x33FFFFFF, 8)); });
        t.setOnClickListener(v -> {
            showTab(1);
            h.post(() -> {
                if (appsList.getChildCount() > 0) ((ViewGroup) appsList.getChildAt(0)).getChildAt(0).requestFocus();
            });
        });
        return t;
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
        homeRow.removeAllViews();
        for (String p : prefs.list("favs")) {
            Apps.Entry e = by.get(p);
            if (e != null && !hiddenNow.contains(p)) homeRow.addView(card(e));
        }
        homeRow.addView(moreTile());

        appsList.removeAllViews();
        List<Apps.Entry> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        LinearLayout row = null;
        int n = 0;
        for (Apps.Entry e : sorted) {
            if (n++ % 5 == 0) { row = new LinearLayout(this); row.setClipChildren(false); appsList.addView(row); }
            row.addView(card(e));
        }
    }

    private void focusPkg(final String pkg) {
        h.post(() -> {
            View root = tab == 0 ? homeRow : appsList;
            View v = root.findViewWithTag(pkg);
            if (v == null && tab == 0) v = homeRow.getChildAt(0);
            if (v != null) v.requestFocus();
        });
    }

    private void launch(Apps.Entry e) {
        try { startActivity(e.launch); }
        catch (Exception ex) { Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show(); }
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
            names.add("Add to Home");      acts.add(() -> setFav(e.pkg, true));
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

    // ---------------------------------------------------------------- tabs

    private void showTab(int i) {
        tab = i;
        homeView.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        appsView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        settingsView.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        if (i == 2) buildSettings(-1);
        styleTabs();
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            TextView t = tabViews[i];
            t.setBackground(rect(t.isFocused() ? 0x66FFFFFF : (i == tab ? 0x33FFFFFF : 0), 20));
        }
    }

    // ---------------------------------------------------------------- settings

    private void row(String text, final Act act) {
        final int idx = settingsList.getChildCount();
        TextView t = label(text, 20, WHITE);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rect(0x33000000, 10));
        t.setOnFocusChangeListener((v, f) -> v.setBackground(rect(f ? 0x66FFFFFF : 0x33000000, 10)));
        t.setOnClickListener(v -> act.go(idx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(680), -2);
        lp.bottomMargin = dp(8);
        settingsList.addView(t, lp);
    }

    private static String onOff(boolean b) { return b ? "On" : "Off"; }
    private int wallIndex() { return Math.min(prefs.num("wall", 0), WallpaperView.NAMES.length - 1); }

    private void buildSettings(int focus) {
        settingsList.removeAllViews();
        String name = prefs.str("name", ""), city = prefs.str("city", "");
        row("Wallpaper:   " + WallpaperView.NAMES[wallIndex()], i -> {
            prefs.put("wall", (wallIndex() + 1) % WallpaperView.NAMES.length);
            applyPrefs(); buildSettings(i);
        });
        row("Animated wallpaper:   " + onOff(prefs.flag("anim", true)), i -> {
            prefs.put("anim", !prefs.flag("anim", true));
            applyPrefs(); buildSettings(i);
        });
        row("Your name:   " + (name.isEmpty() ? "not set" : name), i -> ask("Your name", "name", i, false));
        row("Weather city:   " + (city.isEmpty() ? "Auto (from your internet connection)" : city),
                i -> ask("City (leave empty for automatic)", "city", i, true));
        row("Temperature:   " + (prefs.flag("f", false) ? "Fahrenheit" : "Celsius"), i -> {
            prefs.put("f", !prefs.flag("f", false));
            fetchWeather(); buildSettings(i);
        });
        row("24-hour clock:   " + onOff(prefs.flag("h24", false)), i -> {
            prefs.put("h24", !prefs.flag("h24", false));
            applyPrefs(); buildSettings(i);
        });
        row("Now playing:   " + (npStarted ? "Connected" : "Needs access  (press OK)"), i -> nowPlayingHelp());
        row("Reset favourites and hidden apps", i -> {
            prefs.remove("favs");
            prefs.remove("hidden");
            loadApps(); render(); buildSettings(i);
        });
        if (focus >= 0 && focus < settingsList.getChildCount()) settingsList.getChildAt(focus).requestFocus();
    }

    private void ask(String title, final String key, final int idx, final boolean weather) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(prefs.str(key, ""));
        new AlertDialog.Builder(this).setTitle(title).setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.put(key, in.getText().toString().trim());
                    applyPrefs(); buildSettings(idx);
                    if (weather) fetchWeather();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void nowPlayingHelp() {
        String comp = getPackageName() + "/" + NowPlayingService.class.getName();
        String msg = npStarted ? "Now playing is connected. Start music in Spotify, Amazon Music or similar and it will appear on Home."
                : "To show what's playing, Glow needs notification access.\n\nTry \"Open settings\". If your Fire TV has no such screen, run this on your Mac with ADB connected:\n\n"
                + "adb shell settings get secure enabled_notification_listeners\n\n"
                + "If that prints a value, put it plus a colon before the component below; if it prints null, use the component alone:\n\n"
                + "adb shell settings put secure enabled_notification_listeners " + comp;
        new AlertDialog.Builder(this).setTitle("Now playing").setMessage(msg)
                .setPositiveButton("Open settings", (d, w) -> {
                    try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                    catch (Exception ex) { Toast.makeText(this, "Not available on this device", Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- clock, greeting, weather

    private void applyPrefs() {
        wall.configure(wallIndex(), prefs.flag("anim", true));
        String f = prefs.flag("h24", false) ? "HH:mm" : "h:mm";
        clock.setFormat12Hour(f);
        clock.setFormat24Hour(f);
        updateGreeting();
    }

    private void updateGreeting() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening";
        String n = prefs.str("name", "");
        greetView.setText(n.isEmpty() ? g : g + ", " + n);
    }

    private void fetchWeather() {
        lastWeather = System.currentTimeMillis();
        Weather.fetch(prefs.str("city", ""), prefs.flag("f", false), text -> {
            if (!text.isEmpty()) weatherView.setText(text);
        });
    }

    // ---------------------------------------------------------------- helpers

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(4, 0, 2, 0x99000000);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void pop(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        v.setTranslationZ(focused ? dp(8) : 0);
    }
}
