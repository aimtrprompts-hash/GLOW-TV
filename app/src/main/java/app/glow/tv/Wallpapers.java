package app.glow.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Loads wallpaper photos: bundled in assets/wallpapers, or downloaded into the app's own storage. */
final class Wallpapers {
    interface Done { void got(Bitmap b, String error); }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String FILE = "wallpaper.jpg";

    static List<String> assetNames(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String[] l = c.getAssets().list("wallpapers");
            if (l != null) {
                for (String n : l) {
                    String s = n.toLowerCase();
                    if (s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp")) out.add(n);
                }
            }
        } catch (IOException ignored) {}
        return out;
    }

    /** key is "photo" (the downloaded picture) or "asset:<file name>". */
    static void load(final Context c, final String key, final Done cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                String err = null;
                try { b = decode(c, key); } catch (Exception e) { err = e.getMessage(); } catch (OutOfMemoryError e) { err = "picture too big"; }
                final Bitmap fb = b;
                final String fe = err;
                UI.post(new Runnable() { @Override public void run() { cb.got(fb, fe); } });
            }
        }).start();
    }

    /** Downloads a picture from a link, keeps it as the current photo, and returns it. */
    static void fetch(final Context c, final String url, final Done cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                String err = null;
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(20000);
                    con.setInstanceFollowRedirects(true);
                    con.setRequestProperty("User-Agent", "GlowTV/1.0");
                    File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
                    InputStream in = con.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > 25000000L) throw new IOException("file too big");
                    }
                    out.close();
                    in.close();
                    con.disconnect();
                    File dest = new File(c.getFilesDir(), FILE);
                    dest.delete();
                    if (!tmp.renameTo(dest)) throw new IOException("could not save");
                    b = decode(c, "photo");
                    if (b == null) throw new IOException("that link is not a picture");
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "picture too big";
                }
                final Bitmap fb = b;
                final String fe = err;
                UI.post(new Runnable() { @Override public void run() { cb.got(fb, fe); } });
            }
        }).start();
    }

    private static InputStream open(Context c, String key) throws IOException {
        if (key.startsWith("asset:")) return c.getAssets().open("wallpapers/" + key.substring(6));
        return new FileInputStream(new File(c.getFilesDir(), FILE));
    }

    private static Bitmap decode(Context c, String key) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = open(c, key);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int s = 1;
        while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        in = open(c, key);
        Bitmap b = BitmapFactory.decodeStream(in, null, d);
        in.close();
        return b;
    }
}
