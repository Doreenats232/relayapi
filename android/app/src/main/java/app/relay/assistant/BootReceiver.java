package app.relay.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Map;

/** Puts scheduled asks back after the phone restarts. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        try {
            SharedPreferences sp = c.getSharedPreferences("tasks", Context.MODE_PRIVATE);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            long now = System.currentTimeMillis(), day = 24L * 3600 * 1000;
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                long at = o.getLong("at");
                boolean daily = o.getBoolean("daily");
                if (at <= now) {
                    if (!daily) { sp.edit().remove(e.getKey()).apply(); continue; }
                    at += ((now - at) / day + 1) * day;
                }
                Intent i = new Intent(c, TaskReceiver.class);
                i.putExtra("id", e.getKey());
                i.putExtra("ai", o.getString("ai"));
                i.putExtra("prompt", o.getString("prompt"));
                i.putExtra("daily", daily);
                PendingIntent pi = PendingIntent.getBroadcast(c, e.getKey().hashCode(), i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) { }
    }
}
