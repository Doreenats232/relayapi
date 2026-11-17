package app.relay.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs the battery check every few minutes and when Android says the battery is low. */
public class BatteryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        try {
            BatteryWatch.check(c);
            if (c.getSharedPreferences(BatteryWatch.PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)) {
                BatteryWatch.schedule(c, BatteryWatch.EVERY);
            }
        } catch (Exception ignored) { }
    }
}
