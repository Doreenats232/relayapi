package app.relay.assistant;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

/** A quick-settings tile ("Call an AI") that starts a call with the AI chosen in Relay, without opening the app first. */
public class CallTile extends TileService {
    private JSONObject cfg() {
        try { return new JSONObject(getSharedPreferences("quick", MODE_PRIVATE).getString("json", "{}")).optJSONObject("tile"); }
        catch (Exception e) { return null; }
    }

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        JSONObject c = cfg();
        String ai = c == null ? "" : c.optString("ai", "");
        boolean video = c != null && "video".equals(c.optString("type", "audio"));
        t.setLabel(ai.isEmpty() ? "Call an AI" : (video ? "Video " : "Call ") + Character.toUpperCase(ai.charAt(0)) + ai.substring(1));
        t.setState(ai.isEmpty() ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        try { t.setIcon(Icon.createWithResource(this, R.drawable.ic_call)); } catch (Exception ignored) { }
        t.updateTile();
    }

    @Override
    public void onClick() {
        JSONObject c = cfg();
        final Intent in;
        if (c == null || c.optString("ai", "").isEmpty()) {
            in = new Intent(this, MainActivity.class);
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            in = RingService.answerIntent(this, c.optString("ai"), c.optString("type", "audio"), "", "tile");
        }
        final Runnable go = () -> {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 21, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                } else {
                    startActivityAndCollapse(in);
                }
            } catch (Exception ignored) { }
        };
        if (isLocked()) unlockAndRun(go); else go.run();
    }
}
