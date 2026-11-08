package app.relay.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int INK = Color.parseColor("#0C0D14");
    private WebView web;
    private LinearLayout splash;
    private TextView splashText;
    private Button retry, change;
    private SharedPreferences sp;
    private String base = "";
    private String pendingCode = null;
    private int bootId = 0;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer rec;
    private boolean lockOn = false, unlocked = false, authing = false;
    private long pausedAt = 0;
    private boolean fg = true;
    private ValueCallback<Uri[]> filePathCb;
    private String pendingGo = null;
    private String pendingShare = null;
    private String pendingTask = null;
    private boolean wantVoiceBg = false;
    private Uri camUri = null;
    private LinearLayout lockView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("relay", MODE_PRIVATE);
        base = sp.getString("server", "");
        lockOn = sp.getBoolean("lock", false);
        RelayAccessibility.ctlOn = sp.getBoolean("ctl", false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        web.setBackgroundColor(INK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // lets human voices play without a tap each time
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " RelayApp/1.0");
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                if (p.isCaptureEnabled() && android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "relay_" + System.currentTimeMillis() + ".jpg");
                        cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Relay");
                        camUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        Intent ci = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        ci.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, camUri);
                        startActivityForResult(ci, 56);
                        return true;
                    } catch (Exception e) {
                        filePathCb = null;
                        return false;
                    }
                }
                try {
                    startActivityForResult(p.createIntent(), 55);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = Uri.parse(base).getHost();
                if (host != null && host.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showProblem("Can't reach your relay. Check your internet connection.");
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(INK);
        splash.setPadding(60, 60, 60, 60);
        splashText = new TextView(this);
        splashText.setTextColor(Color.parseColor("#ECEEFB"));
        splashText.setTextSize(17);
        splashText.setGravity(Gravity.CENTER);
        splash.addView(splashText);
        retry = new Button(this);
        retry.setText("Try again");
        retry.setOnClickListener(v -> boot());
        change = new Button(this);
        change.setText("Change relay address");
        change.setOnClickListener(v -> askServer());
        splash.addView(retry);
        splash.addView(change);
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView = new LinearLayout(this);
        lockView.setOrientation(LinearLayout.VERTICAL);
        lockView.setGravity(Gravity.CENTER);
        lockView.setBackgroundColor(INK);
        TextView lt = new TextView(this);
        lt.setText("Relay is locked");
        lt.setTextColor(Color.parseColor("#ECEEFB"));
        lt.setTextSize(18);
        lt.setGravity(Gravity.CENTER);
        lockView.addView(lt);
        Button ub = new Button(this);
        ub.setText("Unlock");
        ub.setOnClickListener(v -> lockNow());
        lockView.addView(ub);
        lockView.setVisibility(View.GONE);
        root.addView(lockView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.getDefault());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { js("window.__spoke('" + id + "')"); }
                @Override public void onError(String id) { js("window.__spoke('" + id + "')"); }
                @Override public void onStop(String id, boolean interrupted) { js("window.__spoke('" + id + "')"); }
            });
            ttsReady = true;
        });

        handleIntent(getIntent());
        if (base.isEmpty()) askServer(); else boot();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        if (pendingCode != null && !base.isEmpty()) boot();
    }

    private void handleIntent(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d != null && "relayapp".equals(d.getScheme())) pendingCode = d.getQueryParameter("code");
        if (i != null && Intent.ACTION_SEND.equals(i.getAction()) && i.getStringExtra(Intent.EXTRA_TEXT) != null) {
            pendingShare = i.getStringExtra(Intent.EXTRA_TEXT);
            i.setAction(null);
            if (web != null) js("window.__go&&window.__go()");
        }
        if (i != null && "task".equals(i.getStringExtra("go")) && i.getStringExtra("ai") != null) {
            pendingTask = i.getStringExtra("ai") + "\n" + (i.getStringExtra("prompt") == null ? "" : i.getStringExtra("prompt"));
            i.removeExtra("go");
            if (web != null) js("window.__go&&window.__go()");
        }
        if (i != null && i.getStringExtra("go") != null) {
            pendingGo = i.getStringExtra("go");
            i.removeExtra("go");
            if (web != null) js("window.__go&&window.__go()");
        }
    }

    private void showSplash(String msg, boolean problem) {
        splashText.setText(msg);
        retry.setVisibility(problem ? View.VISIBLE : View.GONE);
        change.setVisibility(problem ? View.VISIBLE : View.GONE);
        splash.setVisibility(View.VISIBLE);
    }

    private void showProblem(String msg) { showSplash(msg, true); }

    private void askServer() {
        final EditText in = new EditText(this);
        in.setHint("https://your-relay.onrender.com");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(base);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Your relay address")
                .setMessage("Paste the web address of your RelayAPI site.")
                .setView(in)
                .setCancelable(!base.isEmpty())
                .setPositiveButton("Save", (d, w) -> {
                    String v = in.getText().toString().trim();
                    if (v.isEmpty()) { askServer(); return; }
                    if (!v.startsWith("http")) v = "https://" + v;
                    while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
                    base = v;
                    sp.edit().putString("server", base).apply();
                    boot();
                })
                .show();
    }

    /** Render's free tier sleeps: wait for the relay to wake up, then load the app page. */
    private void boot() {
        final int id = ++bootId;
        showSplash("Waking your relay…", false);
        final String target = base;
        new Thread(() -> {
            long end = System.currentTimeMillis() + 120000;
            boolean ok = false;
            while (System.currentTimeMillis() < end && id == bootId) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(target + "/healthz").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 200) { ok = true; break; }
                } catch (Exception ignored) { }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
            final boolean up = ok;
            ui.post(() -> {
                if (id != bootId) return;
                if (up) {
                    splash.setVisibility(View.GONE);
                    String url = target + "/app/";
                    if (pendingCode != null) { url += "?code=" + Uri.encode(pendingCode); pendingCode = null; }
                    web.loadUrl(url);
                } else showProblem("Your relay did not wake up. Check the address and your connection.");
            });
        }).start();
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** Functions the web app can call. */
    private class Bridge {
        @JavascriptInterface public String server() { return base; }

        @JavascriptInterface public String version() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return String.valueOf(pi.versionCode);
            } catch (Exception e) { return "0"; }
        }

        @JavascriptInterface public boolean lockOn() { return lockOn; }

        @JavascriptInterface public void setLock(boolean on) {
            lockOn = on;
            sp.edit().putBoolean("lock", on).apply();
            if (on) unlocked = true;
        }

        @JavascriptInterface public void setServer() { ui.post(MainActivity.this::askServer); }

        @JavascriptInterface public void googleSignIn() { openExternal(base + "/app/?handoff=1"); }

        @JavascriptInterface public void openExternalUrl(String url) { openExternal(url); }

        @JavascriptInterface public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("relay", text));
        }

        @JavascriptInterface public boolean voiceOk() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }

        @JavascriptInterface public void listen() {
            ui.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                    return;
                }
                startRec();
            });
        }

        @JavascriptInterface public void stopListen() {
            ui.post(() -> { if (rec != null) { rec.cancel(); rec.destroy(); rec = null; } });
        }

        @JavascriptInterface public void speak(final String id, final String text, final float pitch, final float rate, final int vi) {
            ui.post(() -> speakNow(id, text, pitch, rate, vi));
        }

        @JavascriptInterface public void stopSpeak() {
            ui.post(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface public boolean ctlOn() { return RelayAccessibility.ctlOn; }

        @JavascriptInterface public void setCtl(boolean on) {
            RelayAccessibility.ctlOn = on;
            sp.edit().putBoolean("ctl", on).apply();
        }

        @JavascriptInterface public boolean a11yConnected() { return RelayAccessibility.inst != null; }

        @JavascriptInterface public void openA11y() {
            ui.post(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }

        @JavascriptInterface public boolean hasCamera() { return android.os.Build.VERSION.SDK_INT >= 29; }

        @JavascriptInterface public void keepOn(final boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void scheduleTask(String id, String ai, String prompt, double atMs, boolean daily) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ai", ai); o.put("prompt", prompt); o.put("at", (long) atMs); o.put("daily", daily);
                getSharedPreferences("tasks", MODE_PRIVATE).edit().putString(id, o.toString()).apply();
                Intent i = new Intent(MainActivity.this, TaskReceiver.class);
                i.putExtra("id", id);
                i.putExtra("ai", ai);
                i.putExtra("prompt", prompt);
                i.putExtra("daily", daily);
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, id.hashCode(), i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (long) atMs, pi);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void cancelTask(String id) {
            try {
                getSharedPreferences("tasks", MODE_PRIVATE).edit().remove(id).apply();
                Intent i = new Intent(MainActivity.this, TaskReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, id.hashCode(), i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(pi);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void voiceBg(final boolean on) {
            ui.post(() -> {
                if (!on) { wantVoiceBg = false; stopService(new Intent(MainActivity.this, VoiceService.class)); return; }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startForegroundService(new Intent(MainActivity.this, VoiceService.class));
                else wantVoiceBg = true;
            });
        }

        @JavascriptInterface public String takeTask() { String g = pendingTask; pendingTask = null; return g == null ? "" : g; }

        @JavascriptInterface public String takeShare() { String g = pendingShare; pendingShare = null; return g == null ? "" : g; }

        @JavascriptInterface public String takeGo() { String g = pendingGo; pendingGo = null; return g == null ? "" : g; }

        @JavascriptInterface public void askNotifyPerm() {
            ui.post(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                }
            });
        }

        @JavascriptInterface public void notifyDone(final String title, final String text) {
            ui.post(() -> showDone(title, text));
        }

        @JavascriptInterface public String phone(String json) {
            try {
                return doAction(new JSONObject(json));
            } catch (Exception e) {
                return "Failed: " + e.getMessage();
            }
        }
    }

    private void showDone(String title, String text) {
        if (fg) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("replies", "Finished replies", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String body = text == null ? "" : text.trim();
            if (body.length() > 140) body = body.substring(0, 140) + "...";
            Notification n = new Notification.Builder(this, "replies")
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() % 100000), n);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onPause() {
        fg = false;
        if (!authing) pausedAt = System.currentTimeMillis();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fg = true;
        if (lockOn && !authing && (!unlocked || System.currentTimeMillis() - pausedAt > 30000)) lockNow();
    }

    private void lockNow() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            lockOn = false;
            sp.edit().putBoolean("lock", false).apply();
            lockView.setVisibility(View.GONE);
            return;
        }
        Intent i = km.createConfirmDeviceCredentialIntent("Relay", "Unlock to continue");
        if (i == null) { lockView.setVisibility(View.GONE); return; }
        unlocked = false;
        lockView.setVisibility(View.VISIBLE);
        authing = true;
        startActivityForResult(i, 9);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 56) {
            if (filePathCb != null) {
                if (res == RESULT_OK && camUri != null) filePathCb.onReceiveValue(new Uri[]{camUri});
                else {
                    filePathCb.onReceiveValue(null);
                    if (camUri != null) { try { getContentResolver().delete(camUri, null, null); } catch (Exception ignored) { } }
                }
                filePathCb = null;
            }
            camUri = null;
            return;
        }
        if (req == 55) {
            if (filePathCb != null) { filePathCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); filePathCb = null; }
            return;
        }
        if (req == 9) {
            authing = false;
            if (res == RESULT_OK) {
                unlocked = true;
                pausedAt = System.currentTimeMillis();
                lockView.setVisibility(View.GONE);
            }
        }
    }

    private String resolveNumber(JSONObject a) throws Exception {
        String n = a.optString("number", "").trim();
        if (n.matches(".*\\d{3,}.*")) return n;
        String name = a.optString("name", n).trim();
        if (name.isEmpty()) throw new Exception("No number or contact name given");
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ui.post(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 8));
            throw new Exception("Allow contacts access, then press Run again");
        }
        Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + name + "%"}, null);
        String first = null, exact = null;
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String dn = c.getString(0), num = c.getString(1);
                    if (first == null) first = num;
                    if (exact == null && dn != null && dn.equalsIgnoreCase(name)) exact = num;
                }
            } finally { c.close(); }
        }
        String r = exact != null ? exact : first;
        if (r == null) throw new Exception("No contact named " + name);
        return r;
    }

    private void js(final String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private void speakNow(String id, String text, float pitch, float rate, int vi) {
        if (!ttsReady) { js("window.__spoke('" + id + "')"); return; }
        try {
            List<Voice> vs = new ArrayList<>();
            String lang = Locale.getDefault().getLanguage();
            for (Voice v : tts.getVoices()) {
                if (v.getLocale().getLanguage().equals(lang) && !v.getFeatures().contains("notInstalled")) vs.add(v);
            }
            Collections.sort(vs, Comparator.comparing(Voice::getName));
            if (!vs.isEmpty()) tts.setVoice(vs.get(Math.abs(vi) % vs.size()));
        } catch (Exception ignored) { }
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        if (text.length() > 3900) text = text.substring(0, 3900);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) js("window.__spoke('" + id + "')");
    }

    private void startRec() {
        if (rec != null) { rec.destroy(); rec = null; }
        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int t, Bundle b) { }
            @Override public void onError(int code) { js("window.__heardErr('" + code + "')"); }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) js("window.__heard(" + JSONObject.quote(r.get(0)) + ",false)");
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) js("window.__heard(" + JSONObject.quote(r.get(0)) + ",true)");
                else js("window.__heardErr('7')");
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        rec.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 77) return;
        if (wantVoiceBg && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && perms.length > 0 && android.Manifest.permission.RECORD_AUDIO.equals(perms[0])) {
            wantVoiceBg = false;
            try { startForegroundService(new Intent(this, VoiceService.class)); } catch (Exception ignored) { }
        }
        if (code != 7) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startRec();
        else js("window.__heardErr('perm')");
    }

    @Override
    protected void onDestroy() {
        if (rec != null) rec.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private String doAction(JSONObject a) throws Exception {
        String act = a.optString("action");
        Intent i;
        switch (act) {
            case "open_app": {
                String want = a.optString("name").toLowerCase(Locale.ROOT).trim();
                if (want.isEmpty()) return "No app name given";
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
                ResolveInfo best = null;
                for (ResolveInfo r : apps) {
                    String l = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
                    if (l.equals(want)) { best = r; break; }
                    if (best == null && (l.contains(want) || want.contains(l))) best = r;
                }
                if (best == null) return "Could not find an app called " + a.optString("name");
                i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
                if (i == null) return "Could not open that app";
                launch(i);
                return "Opened " + best.loadLabel(pm);
            }
            case "open_url": {
                String u = a.optString("url");
                if (!(u.startsWith("http://") || u.startsWith("https://"))) u = "https://" + u;
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                return "Opened link";
            }
            case "dial":
                launch(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(resolveNumber(a)))));
                return "Dialer opened";
            case "sms":
                i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(resolveNumber(a))));
                i.putExtra("sms_body", a.optString("body"));
                launch(i);
                return "Message drafted. Press send in your messages app.";
            case "email":
                i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.optString("to")));
                i.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"));
                i.putExtra(Intent.EXTRA_TEXT, a.optString("body"));
                launch(i);
                return "Email draft opened";
            case "search":
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))));
                return "Searching";
            case "map":
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("query")))));
                return "Map opened";
            case "alarm":
                i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"));
                i.putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"));
                i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Relay"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                launch(i);
                return "Alarm set";
            case "timer":
                i = new Intent(AlarmClock.ACTION_SET_TIMER);
                i.putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60));
                i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Relay"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                launch(i);
                return "Timer started";
            case "settings": {
                String p = a.optString("page", "main").toLowerCase(Locale.ROOT);
                String sa;
                switch (p) {
                    case "wifi": sa = Settings.ACTION_WIFI_SETTINGS; break;
                    case "bluetooth": sa = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                    case "display": sa = Settings.ACTION_DISPLAY_SETTINGS; break;
                    case "sound": sa = Settings.ACTION_SOUND_SETTINGS; break;
                    case "battery": sa = Intent.ACTION_POWER_USAGE_SUMMARY; break;
                    case "apps": sa = Settings.ACTION_APPLICATION_SETTINGS; break;
                    case "location": sa = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                    default: sa = Settings.ACTION_SETTINGS;
                }
                launch(new Intent(sa));
                return "Settings opened";
            }
            case "share":
                i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, a.optString("text"));
                launch(Intent.createChooser(i, "Share"));
                return "Share sheet opened";
            case "read_screen": return RelayAccessibility.read();
            case "tap": return RelayAccessibility.tap(a.optString("text"));
            case "type": return RelayAccessibility.type(a.optString("text"));
            case "scroll": return RelayAccessibility.scroll(a.optString("direction"));
            case "back": return RelayAccessibility.global("back");
            case "home": return RelayAccessibility.global("home");
            default:
                return "Unknown action: " + act;
        }
    }

    private void launch(final Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
