package app.glow.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Apps {
    static final class Entry {
        final String pkg, label;
        final Intent launch;
        final Drawable art, icon;
        final boolean banner;
        Entry(String pkg, String label, Intent launch, Drawable art, boolean banner, Drawable icon) {
            this.pkg = pkg; this.label = label; this.launch = launch; this.art = art; this.banner = banner;
            this.icon = icon;
        }
    }

    /** Every launchable app (TV apps first), one entry per package, excluding Glow itself. */
    static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> map = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || map.containsKey(p)) continue;
                Intent li = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(p, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Drawable b = ri.activityInfo.loadBanner(pm);
                Drawable icon = ri.loadIcon(pm);
                Drawable art = b != null ? b : icon;
                map.put(p, new Entry(p, String.valueOf(ri.loadLabel(pm)), li, art, b != null, icon));
            }
        }
        return new ArrayList<>(map.values());
    }
}
