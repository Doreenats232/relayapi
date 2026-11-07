package app.relay.assistant;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TaskReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        try {
            String id = in.getStringExtra("id"), ai = in.getStringExtra("ai"), prompt = in.getStringExtra("prompt");
            boolean daily = in.getBooleanExtra("daily", false);
            Intent open = new Intent(c, MainActivity.class);
            open.putExtra("go", "task");
            open.putExtra("ai", ai);
            open.putExtra("prompt", prompt);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id == null ? 0 : id.hashCode(), open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("tasks", "Scheduled asks", NotificationManager.IMPORTANCE_HIGH));
            String body = prompt == null ? "" : prompt;
            Notification n = new Notification.Builder(c, "tasks")
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Ask " + ai + "?")
                    .setContentText("Tap to send: " + (body.length() > 100 ? body.substring(0, 100) + "..." : body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id == null ? 1 : id.hashCode(), n);
            if (daily) {
                Intent next = new Intent(c, TaskReceiver.class);
                next.putExtra("id", id);
                next.putExtra("ai", ai);
                next.putExtra("prompt", prompt);
                next.putExtra("daily", true);
                PendingIntent p2 = PendingIntent.getBroadcast(c, id == null ? 0 : id.hashCode(), next,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 24L * 3600 * 1000, p2);
            }
        } catch (Exception ignored) { }
    }
}
