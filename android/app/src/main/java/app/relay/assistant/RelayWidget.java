package app.relay.assistant;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class RelayWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.relay_widget);
            v.setOnClickPendingIntent(R.id.w_text, open(c, "chat", 1));
            v.setOnClickPendingIntent(R.id.w_rooms, open(c, "rooms", 2));
            m.updateAppWidget(id, v);
        }
    }

    private static PendingIntent open(Context c, String go, int code) {
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra("go", go);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
