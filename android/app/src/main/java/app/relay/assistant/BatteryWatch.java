package app.relay.assistant;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;

import org.json.JSONObject;

/** Watches the battery (also with the app closed): a nudge at the first level, a loud wake-up alarm at the second if the phone is not being used. */
public class BatteryWatch {
    static final String PREFS = "battery";
    private static final int NOTIF = 7010;
    static final long EVERY = 5 * 60 * 1000L;

    static JSONObject get(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", sp.getBoolean("enabled", false));
            o.put("warn", sp.getInt("warn", 30));
            o.put("alarm", sp.getInt("alarm", 15));
            o.put("alarmOn", sp.getBoolean("alarmOn", true));
            o.put("idle", sp.getInt("idle", 10));
            o.put("mode", sp.getString("mode", "alarm"));
            o.put("ai", sp.getString("ai", ""));
            o.put("repeat", sp.getInt("repeat", 10));
        } catch (Exception ignored) { }
        return o;
    }

    static void set(Context c, JSONObject o) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean("enabled", o.optBoolean("enabled", false));
        e.putInt("warn", o.optInt("warn", 30));
        e.putInt("alarm", o.optInt("alarm", 15));
        e.putBoolean("alarmOn", o.optBoolean("alarmOn", true));
        e.putInt("idle", Math.max(1, o.optInt("idle", 10)));
        e.putString("mode", o.optString("mode", "alarm"));
        e.putString("ai", o.optString("ai", ""));
        e.putInt("repeat", Math.max(2, o.optInt("repeat", 10)));
        e.putBoolean("warned", false).putBoolean("critWarned", false).putInt("rings", 0).putLong("lastRing", 0).putLong("offSince", 0);
        e.apply();
        if (o.optBoolean("enabled", false)) schedule(c, 30 * 1000L); else cancel(c);
    }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 4711, new Intent(c, BatteryReceiver.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, long delay) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi(c));
        } catch (Exception ignored) { }
    }

    static void cancel(Context c) {
        try { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(pi(c)); } catch (Exception ignored) { }
    }

    /** Level, charging flag, and whether the screen is on right now. */
    static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            Intent st = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int lvl = st == null ? -1 : st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = st == null ? 100 : st.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = st == null ? 0 : st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            o.put("level", scale > 0 && lvl >= 0 ? Math.round(lvl * 100f / scale) : -1);
            o.put("charging", plugged != 0);
            o.put("screenOn", ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive());
        } catch (Exception ignored) { }
        return o;
    }

    static void check(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean("enabled", false)) return;
            JSONObject s = status(c);
            int pct = s.optInt("level", -1);
            if (pct < 0) return;
            boolean charging = s.optBoolean("charging"), on = s.optBoolean("screenOn");
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = sp.edit();

            long off = sp.getLong("offSince", 0);
            if (on) off = 0; else if (off == 0) off = now;
            e.putLong("offSince", off);

            int warn = sp.getInt("warn", 30), alarm = sp.getInt("alarm", 15);
            if (charging || pct > warn + 3) {
                e.putBoolean("warned", false).putBoolean("critWarned", false).putInt("rings", 0);
            } else {
                if (pct <= warn && !sp.getBoolean("warned", false)) {
                    notifyLow(c, pct, false);
                    e.putBoolean("warned", true);
                }
                if (pct <= alarm && on && !sp.getBoolean("critWarned", false)) {
                    notifyLow(c, pct, true);
                    e.putBoolean("critWarned", true);
                }
                boolean idleLongEnough = !on && off > 0 && now - off >= sp.getInt("idle", 10) * 60000L;
                boolean spaced = now - sp.getLong("lastRing", 0) >= sp.getInt("repeat", 10) * 60000L;
                if (sp.getBoolean("alarmOn", true) && pct <= alarm && idleLongEnough && spaced && sp.getInt("rings", 0) < 3) {
                    e.putLong("lastRing", now).putInt("rings", sp.getInt("rings", 0) + 1);
                    String ai = sp.getString("ai", "");
                    String msg = "Battery is at " + pct + "%";
                    if ("call".equals(sp.getString("mode", "alarm")) && !ai.isEmpty()) {
                        RingService.start(c, "call", ai, "audio",
                                msg + ". I may have fallen asleep. Wake me up and firmly tell me to charge my phone now.", "Battery alert", "battery");
                    } else {
                        RingService.start(c, "alarm", "", "", msg, "Battery", "battery");
                    }
                }
            }
            e.apply();
        } catch (Exception ignored) { }
    }

    static void notifyLow(Context c, int pct, boolean critical) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("relay_battery", "Battery reminders", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(c, 15, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTIF, new Notification.Builder(c, "relay_battery")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentTitle(critical ? "Battery at " + pct + "% \u2013 charge it now" : "Battery at " + pct + "%")
                    .setContentText("Please charge your phone.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) { }
    }
}
