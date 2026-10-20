package app.glow.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("glow", Context.MODE_PRIVATE); }

    boolean has(String k) { return sp.contains(k); }
    void remove(String k) { sp.edit().remove(k).apply(); }

    List<String> list(String k) {
        String s = sp.getString(k, "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }
    void saveList(String k, List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(','); b.append(s); }
        sp.edit().putString(k, b.toString()).apply();
    }

    String str(String k, String d) { return sp.getString(k, d); }
    boolean flag(String k, boolean d) { return sp.getBoolean(k, d); }
    int num(String k, int d) { return sp.getInt(k, d); }
    void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
}
