package app.relay.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Rings like a phone call (kind "call") or sounds a loud alarm (kind "alarm"), with a full-screen
 * screen over the lock screen. Used for scheduled AI calls and for the low-battery wake-up.
 */
public class RingService extends Service {
    static final String ACTION_STOP = "app.relay.assistant.RING_STOP";
    static final String ACTION_ENDED = "app.relay.assistant.RING_ENDED";
    private static final int NOTIF_ID = 7001;
    private static final int MISSED_ID = 7002;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private Vibrator vib;
    private PowerManager.WakeLock wl;
    private AudioManager am;
    private int oldAlarmVol = -1;
    private BroadcastReceiver power;
    private boolean ringing = false, isCall = true;
    private String ai = "", type = "audio", say = "", label = "", id = "";

    /** Starts ringing. If the system refuses a background service, falls back to a plain full-screen notification. */
    static void start(Context c, String kind, String ai, String type, String say, String label, String id) {
        Intent i = new Intent(c, RingService.class);
        i.putExtra("kind", kind).putExtra("ai", ai).putExtra("type", type).putExtra("say", say).putExtra("label", label).putExtra("id", id);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                channels(c, nm);
                nm.notify(NOTIF_ID, build(c, "call".equals(kind), ai, type, say, label, id));
            } catch (Exception ignored) { }
        }
    }

    static void stop(Context c) {
        try {
            c.startService(new Intent(c, RingService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) { }
    }

    static void channels(Context c, NotificationManager nm) {
        NotificationChannel calls = new NotificationChannel("relay_calls", "Incoming AI calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(null, null);
        calls.enableVibration(false);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(calls);
        NotificationChannel alarm = new NotificationChannel("relay_alarm", "Battery wake-up alarm", NotificationManager.IMPORTANCE_HIGH);
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);
        nm.createNotificationChannel(new NotificationChannel("relay_missed", "Missed AI calls", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static Intent answerIntent(Context c, String ai, String type, String say, String id) {
        Intent a = new Intent(c, MainActivity.class);
        a.putExtra("go", "call").putExtra("ai", ai).putExtra("type", type).putExtra("say", say).putExtra("id", id);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return a;
    }

    static Notification build(Context c, boolean call, String ai, String type, String say, String label, String id) {
        String name = (ai == null || ai.isEmpty()) ? "Relay" : Character.toUpperCase(ai.charAt(0)) + ai.substring(1);
        Intent full = new Intent(c, RingActivity.class);
        full.putExtra("kind", call ? "call" : "alarm").putExtra("ai", ai).putExtra("type", type).putExtra("say", say).putExtra("label", label).putExtra("id", id);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(c, 11, full, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ansPi = PendingIntent.getActivity(c, 12, answerIntent(c, ai, type, say, id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent decPi = PendingIntent.getService(c, 13, new Intent(c, RingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean video = "video".equals(type);
        String title = call ? name : "Charge your phone";
        String text = call ? ("Relay " + (video ? "video" : "audio") + " call") : (say == null || say.isEmpty() ? "Plug in a charger now" : say);

        Notification.Builder b = new Notification.Builder(c, call ? "relay_calls" : "relay_alarm")
                .setSmallIcon(call ? android.R.drawable.sym_call_incoming : android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(call ? Notification.CATEGORY_CALL : Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi);
        if (call) {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    android.app.Person p = new android.app.Person.Builder().setName(name).setImportant(true).build();
                    b.setStyle(Notification.CallStyle.forIncomingCall(p, decPi, ansPi));
                    return b.build();
                } catch (Throwable ignored) {
                    b.setStyle(null);
                }
            }
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decPi).build());
            b.addAction(new Notification.Action.Builder(android.R.drawable.sym_action_call, "Answer", ansPi).build());
        } else {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "I'm awake", decPi).build());
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && ACTION_STOP.equals(in.getAction())) {
            end(false);
            return START_NOT_STICKY;
        }
        try {
            if (ringing) cleanup();
            String kind = in == null ? "call" : in.getStringExtra("kind");
            isCall = !"alarm".equals(kind);
            ai = in == null || in.getStringExtra("ai") == null ? "" : in.getStringExtra("ai");
            type = in == null || in.getStringExtra("type") == null ? "audio" : in.getStringExtra("type");
            say = in == null || in.getStringExtra("say") == null ? "" : in.getStringExtra("say");
            label = in == null || in.getStringExtra("label") == null ? "" : in.getStringExtra("label");
            id = in == null || in.getStringExtra("id") == null ? "" : in.getStringExtra("id");

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            channels(this, nm);
            Notification n = build(this, isCall, ai, type, say, label, id);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            ringing = true;

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:ring");
            wl.acquire(4 * 60 * 1000L);
            sound();
            shake();
            if (!isCall) {
                power = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) { end(false); }
                };
                IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(power, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(power, f);
            }
            h.postDelayed(() -> end(isCall), isCall ? 45000 : 180000); // an unanswered call becomes a missed call
        } catch (Exception e) {
            end(false);
        }
        return START_NOT_STICKY;
    }

    private void sound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(isCall ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (!isCall) {
                try {
                    oldAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0); // as loud as it goes
                } catch (Exception ignored) { }
            }
            mp = new MediaPlayer();
            mp.setDataSource(this, uri);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(isCall ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIC).build());
            mp.setLooping(true);
            mp.prepare();
            mp.start();
        } catch (Exception ignored) { }
    }

    private void shake() {
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pat = isCall ? new long[]{0, 900, 700} : new long[]{0, 1200, 500};
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(isCall ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIC).build();
            vib.vibrate(VibrationEffect.createWaveform(pat, 0), aa);
        } catch (Exception ignored) { }
    }

    private void cleanup() {
        h.removeCallbacksAndMessages(null);
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) { }
        mp = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
        try { if (am != null && oldAlarmVol >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarmVol, 0); } catch (Exception ignored) { }
        oldAlarmVol = -1;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        wl = null;
        try { if (power != null) unregisterReceiver(power); } catch (Exception ignored) { }
        power = null;
        ringing = false;
    }

    private void end(boolean missed) {
        boolean was = ringing;
        cleanup();
        try { stopForeground(true); } catch (Exception ignored) { }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIF_ID);
            if (missed && was && isCall) {
                String name = ai.isEmpty() ? "Relay" : Character.toUpperCase(ai.charAt(0)) + ai.substring(1);
                Intent open = new Intent(this, MainActivity.class);
                open.putExtra("go", "call").putExtra("ai", ai).putExtra("type", type).putExtra("say", say).putExtra("id", id);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(this, 14, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(MISSED_ID, new Notification.Builder(this, "relay_missed")
                        .setSmallIcon(android.R.drawable.sym_call_missed)
                        .setContentTitle("Missed call from " + name)
                        .setContentText("Tap to call back")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build());
            }
        } catch (Exception ignored) { }
        try { sendBroadcast(new Intent(ACTION_ENDED).setPackage(getPackageName())); } catch (Exception ignored) { }
        stopSelf();
    }

    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent in) { return null; }
}
