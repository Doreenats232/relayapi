package app.relay.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;

/** Listens for an AI's name while Relay is closed. When it hears one, it posts a call-style notification that opens a voice call with that AI. */
public class WakeService extends Service {
    static final String PREFS = "wakebg";
    /** true while the Relay screen is open (the app listens by itself then, and two listeners cannot share the microphone) */
    static volatile boolean paused = false;
    static volatile boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private PowerManager.WakeLock wl;
    private boolean listening = false, pending = false;
    private long lastHit = 0, wlAt = 0;
    private int fails = 0;
    private JSONObject map = new JSONObject();

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("wake", "Listening for names", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel hit = new NotificationChannel("wake_hit", "An AI heard its name", NotificationManager.IMPORTANCE_HIGH);
        hit.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(hit);
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 6, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "wake")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Relay is listening for a name")
                .setContentText("Say an AI's name to start a call. Tap to open Relay.")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(79, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(79, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        load();
        running = true;
        fails = 0;
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private void load() {
        try {
            String js = getSharedPreferences(PREFS, MODE_PRIVATE).getString("json", "{}");
            JSONObject o = new JSONObject(js);
            map = o.optJSONObject("map") == null ? new JSONObject() : o.optJSONObject("map");
        } catch (Exception e) { map = new JSONObject(); }
    }

    private boolean enabled() {
        try { return new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString("json", "{}")).optBoolean("on", false); }
        catch (Exception e) { return false; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!enabled() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return; }
            long now = System.currentTimeMillis();
            if (wl == null || now - wlAt > 9 * 60 * 1000L) {
                try {
                    if (wl == null) wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:wake");
                    wl.acquire(10 * 60 * 1000L);
                    wlAt = now;
                } catch (Exception ignored) { }
            }
            if (paused || VoiceService.running) {
                kill();
            } else if (!listening && !pending) {
                schedule(100);
            }
            h.postDelayed(this, 1500);
        }
    };

    private final Runnable go = new Runnable() {
        @Override public void run() {
            pending = false;
            if (paused || VoiceService.running || !enabled()) return;
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(WakeService.this)) { stopSelf(); return; }
                if (sr == null) {
                    sr = SpeechRecognizer.createSpeechRecognizer(WakeService.this);
                    sr.setRecognitionListener(new Listener());
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                listening = true;
                sr.startListening(i);
            } catch (Exception e) {
                listening = false;
                kill();
                schedule(2000);
            }
        }
    };

    private void schedule(long ms) {
        if (pending) return;
        pending = true;
        h.postDelayed(go, ms);
    }

    private void kill() {
        h.removeCallbacks(go);
        pending = false;
        listening = false;
        try { if (sr != null) { sr.cancel(); sr.destroy(); } } catch (Exception ignored) { }
        sr = null;
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onResults(Bundle b) {
            listening = false;
            fails = 0;
            try {
                ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null) for (String t : l) {
                    String[] m = match(t);
                    if (m != null) { heard(m[0], m[1]); break; }
                }
            } catch (Exception ignored) { }
            schedule(250);
        }

        @Override public void onError(int e) {
            listening = false;
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return; }
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { fails = 0; schedule(200); return; }
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { schedule(1500); return; }
            if (++fails >= 4) { fails = 0; kill(); schedule(5000); return; }
            kill();
            schedule(1000);
        }
    }

    // ---- name matching (the same rules as the app: close spelling at the start, exact name elsewhere) ----
    private static final String[] FILL = {"hey", "hi", "ok", "okay", "yo", "hello", "oh", "hay"};

    static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return d.replaceAll("[^a-z0-9]+", " ").trim();
    }

    static int lev(String a, String b) {
        int m = a.length(), n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] p = new int[n + 1];
        for (int j = 0; j <= n; j++) p[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] c = new int[n + 1];
            c[0] = i;
            for (int j = 1; j <= n; j++) c[j] = Math.min(Math.min(p[j] + 1, c[j - 1] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            p = c;
        }
        return p[n];
    }

    private static boolean isFill(String t) {
        for (String f : FILL) if (f.equals(t)) return true;
        return false;
    }

    private static String clean(String s) {
        return s.replaceAll("^[,.:;!?\\s-]+", "").replaceAll("[,\\s]+$", "");
    }

    /** returns {ai, rest} or null */
    private String[] match(String text) {
        if (text == null) return null;
        String[] toks = text.trim().split("\\s+");
        if (toks.length == 0 || toks[0].isEmpty()) return null;
        String[] nt = new String[toks.length];
        for (int i = 0; i < toks.length; i++) nt[i] = norm(toks[i]);
        Iterator<String> it = map.keys();
        while (it.hasNext()) {
            String ai = it.next();
            JSONArray names = map.optJSONArray(ai);
            if (names == null) continue;
            for (int k = 0; k < names.length(); k++) {
                String n = norm(names.optString(k));
                if (n.isEmpty()) continue;
                int nw = n.split(" ").length, len = n.replace(" ", "").length();
                int tol = len >= 7 ? 2 : len >= 4 ? 1 : 0;
                int i = 0;
                while (i < 2 && i < nt.length && isFill(nt[i])) i++;
                if (i + nw <= nt.length) {
                    StringBuilder sb = new StringBuilder();
                    for (int q = i; q < i + nw; q++) sb.append(q > i ? " " : "").append(nt[q]);
                    if (lev(sb.toString(), n) <= tol) {
                        StringBuilder r = new StringBuilder();
                        for (int q = i + nw; q < toks.length; q++) r.append(r.length() > 0 ? " " : "").append(toks[q]);
                        return new String[]{ai, clean(r.toString())};
                    }
                }
                for (int j = 0; j + nw <= nt.length; j++) {
                    StringBuilder sb = new StringBuilder();
                    for (int q = j; q < j + nw; q++) sb.append(q > j ? " " : "").append(nt[q]);
                    if (sb.toString().equals(n)) {
                        StringBuilder r = new StringBuilder();
                        for (int q = 0; q < toks.length; q++) if (q < j || q >= j + nw) r.append(r.length() > 0 ? " " : "").append(toks[q]);
                        return new String[]{ai, clean(r.toString())};
                    }
                }
            }
        }
        return null;
    }

    private void heard(String ai, String rest) {
        long now = System.currentTimeMillis();
        if (now - lastHit < 8000) return;
        lastHit = now;
        try {
            Intent go = RingService.answerIntent(this, ai, "audio", rest, "wake");
            PendingIntent pi = PendingIntent.getActivity(this, 14, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String name = ai.isEmpty() ? "Relay" : Character.toUpperCase(ai.charAt(0)) + ai.substring(1);
            Notification n = new Notification.Builder(this, "wake_hit")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(name + " heard its name")
                    .setContentText(rest.isEmpty() ? "Tap to talk" : rest)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(78, n);
            try { startActivity(go); } catch (Exception ignored) { }
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        kill();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
