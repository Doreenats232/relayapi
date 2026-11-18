package app.relay.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Map;

/** Scheduled calls. They live on the phone, so they ring even when the app is closed. */
public class Calls {
    static final String PREFS = "calls";

    /** Next time after `from` at hour:minute on an allowed weekday. days is a 7-bit mask (bit 0 = Sunday ... bit 6 = Saturday). */
    static long next(int hour, int minute, int days, long from) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 9; i++) {
            if (c.getTimeInMillis() > from + 1000) {
                int dow = c.get(Calendar.DAY_OF_WEEK) - 1; // 0 = Sunday
                if (days == 0 || (days & (1 << dow)) != 0) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return from + 24L * 3600 * 1000;
    }

    private static PendingIntent firePi(Context c, String id) {
        Intent i = new Intent(c, CallReceiver.class);
        i.setData(Uri.parse("relaycall://" + id));
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void arm(Context c, String id) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String js = sp.getString(id, null);
            if (js == null) return;
            JSONObject o = new JSONObject(js);
            if (!o.optBoolean("on", true)) { cancel(c, id); return; }
            int days = o.optInt("days", 0);
            long now = System.currentTimeMillis();
            long at = days == 0 ? o.optLong("at", 0) : next(o.optInt("hour", 8), o.optInt("minute", 0), days, now);
            if (at <= now) {
                if (days == 0) { o.put("on", false); sp.edit().putString(id, o.toString()).apply(); }
                return;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent show = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // an alarm-clock alarm keeps working in battery saver and Doze, which is what a call at a set time needs
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), firePi(c, id));
        } catch (Exception ignored) { }
    }

    static void cancel(Context c, String id) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(firePi(c, id));
        } catch (Exception ignored) { }
    }

    /** Puts every enabled call back (after a restart or an app update). */
    static void rearmAll(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) arm(c, e.getKey());
        } catch (Exception ignored) { }
    }
}
