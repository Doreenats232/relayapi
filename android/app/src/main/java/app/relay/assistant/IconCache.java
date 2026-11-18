package app.relay.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;

/** AI logos for the incoming-call screen: the same favicon the app's lists show, kept on the phone so it is there even with no signal. */
final class IconCache {
    private static final String PREFS = "icons";

    private IconCache() { }

    /** Called by the web app with {"deepseek":"chat.deepseek.com", ...}. Remembers it and downloads any logo that is not cached yet. */
    static void save(Context c, String json) {
        try {
            final JSONObject o = new JSONObject(json);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("map", o.toString()).apply();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String host = o.optString(it.next());
                    if (!host.isEmpty()) get(app, host);
                }
            }).start();
        } catch (Exception ignored) { }
    }

    static String hostFor(Context c, String ai) {
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("map", "{}"));
            if (o.has(ai)) return o.optString(ai);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equalsIgnoreCase(ai)) return o.optString(k);
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** The cached logo, or downloads it (short timeout). Call from a background thread. */
    static Bitmap get(Context c, String host) {
        File f = new File(c.getCacheDir(), "ico_" + host.replaceAll("[^A-Za-z0-9.-]", "_") + ".png");
        try {
            if (f.exists() && f.length() > 0) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) return b;
            }
            HttpURLConnection h = (HttpURLConnection) new URL("https://www.google.com/s2/favicons?domain=" + URLEncoder.encode(host, "UTF-8") + "&sz=128").openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(4000);
            if (h.getResponseCode() != 200) return null;
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] data = bo.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b != null) {
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
