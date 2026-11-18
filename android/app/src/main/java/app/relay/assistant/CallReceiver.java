package app.relay.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Fires when a scheduled call is due: sets up the next one and starts ringing. */
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        try {
            String id = in.getStringExtra("id");
            if (id == null) return;
            SharedPreferences sp = c.getSharedPreferences(Calls.PREFS, Context.MODE_PRIVATE);
            String js = sp.getString(id, null);
            if (js == null) return;
            JSONObject o = new JSONObject(js);
            if (!o.optBoolean("on", true)) return;
            if (o.optInt("days", 0) == 0) {
                o.put("on", false); // a one-time call is used up
                sp.edit().putString(id, o.toString()).apply();
            } else {
                Calls.arm(c, id); // repeating: line up the next one
            }
            RingService.start(c, "call", o.optString("ai"), o.optString("type", "audio"), o.optString("say"), o.optString("label"), id);
        } catch (Exception ignored) { }
    }
}
