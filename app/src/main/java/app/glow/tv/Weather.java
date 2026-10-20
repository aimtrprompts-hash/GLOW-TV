package app.glow.tv;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Location + current weather. Free, keyless services: ipwho.is (IP location), open-meteo.com. */
final class Weather {
    interface Callback { void result(String text); }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    static void fetch(final String city, final boolean fahrenheit, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String r;
                try { r = load(city, fahrenheit); } catch (Exception e) { r = ""; }
                final String out = r;
                UI.post(new Runnable() { @Override public void run() { cb.result(out); } });
            }
        }).start();
    }

    private static String load(String city, boolean f) throws Exception {
        double lat, lon;
        String place;
        if (city != null && !city.trim().isEmpty()) {
            JSONObject g = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(city.trim(), "UTF-8")));
            JSONObject r = g.getJSONArray("results").getJSONObject(0);
            lat = r.getDouble("latitude");
            lon = r.getDouble("longitude");
            place = r.getString("name");
            if (r.has("country")) place += ", " + r.getString("country");
        } else {
            JSONObject g = new JSONObject(get("https://ipwho.is/"));
            if (!g.optBoolean("success", false)) throw new IOException("ip lookup failed");
            lat = g.getDouble("latitude");
            lon = g.getDouble("longitude");
            place = g.optString("city", "") + ", " + g.optString("country", "");
        }
        JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon + "&current=temperature_2m,weather_code&temperature_unit="
                + (f ? "fahrenheit" : "celsius")));
        JSONObject cur = w.getJSONObject("current");
        long t = Math.round(cur.getDouble("temperature_2m"));
        return place + "\n" + t + "°" + (f ? "F" : "C") + "  " + describe(cur.getInt("weather_code"));
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "GlowTV/1.0");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        } finally { c.disconnect(); }
    }

    private static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "";
    }
}
