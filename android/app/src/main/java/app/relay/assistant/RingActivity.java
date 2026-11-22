package app.relay.assistant;

import android.app.Activity;
import android.app.KeyguardManager;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The full-screen part: a call-style "incoming call" screen, or the big charge-your-phone alarm. Shows over the lock screen. */
public class RingActivity extends Activity {
    private BroadcastReceiver ended;
    private boolean call = true;
    private String ai = "", type = "audio", say = "", id = "";

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout action(String glyph, String caption, int color, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView b = text(glyph, 30, Color.WHITE, true);
        b.setBackground(oval(color));
        b.setOnClickListener(l);
        col.addView(b, new LinearLayout.LayoutParams(dp(78), dp(78)));
        TextView c = text(caption, 14, Color.parseColor("#C9CEDC"), false);
        c.setPadding(0, dp(10), 0, 0);
        col.addView(c);
        return col;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        Intent in = getIntent();
        call = !"alarm".equals(in.getStringExtra("kind"));
        ai = in.getStringExtra("ai") == null ? "" : in.getStringExtra("ai");
        type = in.getStringExtra("type") == null ? "audio" : in.getStringExtra("type");
        say = in.getStringExtra("say") == null ? "" : in.getStringExtra("say");
        id = in.getStringExtra("id") == null ? "" : in.getStringExtra("id");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(64), dp(24), dp(56));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                call ? new int[]{Color.parseColor("#0B1419"), Color.parseColor("#0A1014")} : new int[]{Color.parseColor("#5a0f18"), Color.parseColor("#0C0D14")});
        root.setBackground(bg);

        if (call) buildCall(root); else buildAlarm(root);
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ended = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { finish(); }
        };
        IntentFilter f = new IntentFilter(RingService.ACTION_ENDED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ended, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(ended, f);
    }

    /** The round picture: the AI's own logo (cached favicon), or its first letter on a colour until the logo is there. */
    private View avatar(String name) {
        int[] pal = {0xFF22C55E, 0xFF6366F1, 0xFFF59E0B, 0xFFEC4899, 0xFF0EA5E9, 0xFF14B8A6, 0xFFF97316};
        int h = 0;
        for (int k = 0; k < name.length(); k++) h = (h * 31 + name.charAt(k)) & 0x7fffffff;
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(oval(pal[h % pal.length]));
        final TextView letter = text(name.substring(0, 1).toUpperCase(), 80, Color.WHITE, true);
        f.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(dp(64), dp(64), dp(64), dp(64));
        img.setVisibility(View.GONE);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final String host = IconCache.hostFor(this, ai);
        if (!host.isEmpty()) {
            new Thread(() -> {
                final Bitmap bm = IconCache.get(getApplicationContext(), host);
                if (bm == null) return;
                runOnUiThread(() -> {
                    f.setBackground(oval(Color.WHITE));
                    letter.setVisibility(View.GONE);
                    img.setImageBitmap(bm);
                    img.setVisibility(View.VISIBLE);
                });
            }).start();
        }
        return f;
    }

    private FrameLayout circleBtn(int icon, int color) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(oval(color));
        f.setClipChildren(false);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        f.addView(iv, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        return f;
    }

    private LinearLayout column(View btn, TextView cap) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.addView(btn, new LinearLayout.LayoutParams(dp(74), dp(74)));
        cap.setPadding(0, dp(10), 0, 0);
        col.addView(cap);
        return col;
    }

    /** Like WhatsApp: the green button hops to hint that it can be swiped; drag it up to accept, let go early and it springs back. */
    private void swipeToAnswer(final View btn, final View cap) {
        final float need = dp(120);
        final ObjectAnimator hint = ObjectAnimator.ofFloat(btn, "translationY", 0f, -dp(18), 0f);
        hint.setDuration(1400);
        hint.setRepeatCount(ValueAnimator.INFINITE);
        hint.start();
        btn.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0 = e.getRawY();
                        hint.cancel();
                        btn.setTranslationY(0f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = Math.min(0f, e.getRawY() - y0);
                        btn.setTranslationY(dy);
                        cap.setAlpha(Math.max(0f, 1f + dy / need));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = e.getRawY() - y0;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && -dy >= need) { answer(); return true; }
                        btn.animate().translationY(0f).setDuration(240).setInterpolator(new OvershootInterpolator()).start();
                        cap.animate().alpha(1f).setDuration(200).start();
                        btn.postDelayed(() -> hint.start(), 900);
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private void buildCall(LinearLayout root) {
        boolean video = "video".equals(type);
        String name = ai.isEmpty() ? "Relay" : Character.toUpperCase(ai.charAt(0)) + ai.substring(1);
        root.setClipChildren(false);
        TextView nm = text(name, 30, Color.WHITE, true);
        nm.setPadding(0, dp(8), 0, dp(6));
        root.addView(nm);
        root.addView(text("Relay " + (video ? "video" : "voice") + " call", 17, Color.parseColor("#AEB6C1"), false));

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(250), dp(250));
        ap.topMargin = dp(70);
        root.addView(avatar(name), ap);

        if (!say.isEmpty()) {
            TextView s = text("\u201C" + (say.length() > 140 ? say.substring(0, 140) + "\u2026" : say) + "\u201D", 15, Color.parseColor("#C9CEDC"), false);
            s.setPadding(dp(16), dp(28), dp(16), 0);
            root.addView(s);
        }

        TextView snooze = text("Call me back in 10 min", 15, Color.WHITE, false);
        GradientDrawable snp0 = new GradientDrawable();
        snp0.setCornerRadius(dp(30));
        snp0.setColor(Color.parseColor("#2B3136"));
        snooze.setBackground(snp0);
        snooze.setPadding(dp(22), dp(10), dp(22), dp(10));
        snooze.setOnClickListener(v -> {
            Calls.snooze(this, ai, type, say, 10);
            RingService.stop(this);
            android.widget.Toast.makeText(this, "I will call you back in 10 minutes", android.widget.Toast.LENGTH_SHORT).show();
            finish();
        });
        LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        snp.topMargin = dp(18);
        snp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(snooze, snp);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        final TextView capAns = text("Swipe up to accept", 14, Color.parseColor("#AEB6C1"), false);
        FrameLayout decB = circleBtn(R.drawable.ic_call_end, 0xFFE8153D);
        decB.setOnClickListener(v -> { RingService.stop(this); finish(); });
        FrameLayout ansB = circleBtn(video ? R.drawable.ic_videocam : R.drawable.ic_call, 0xFF22A559);
        FrameLayout msgB = circleBtn(R.drawable.ic_chat, 0xFF2B3136);
        msgB.setOnClickListener(v -> {
            RingService.stop(this);
            try {
                Intent mi = new Intent(this, MainActivity.class);
                mi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(mi);
            } catch (Exception ignored) { }
            finish();
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.addView(column(decB, text("Decline", 14, Color.parseColor("#AEB6C1"), false)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(ansB, capAns), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        row.addView(column(msgB, text("Message", 14, Color.parseColor("#AEB6C1"), false)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        swipeToAnswer(ansB, capAns);
    }

    private void buildAlarm(LinearLayout root) {
        root.setGravity(Gravity.CENTER);
        root.addView(text("\uD83D\uDD0B", 72, Color.WHITE, false));
        TextView big = text("CHARGE YOUR PHONE", 40, Color.WHITE, true);
        big.setPadding(0, dp(18), 0, dp(14));
        root.addView(big);
        String sub = (say.isEmpty() ? "Your battery is low." : say) + "\nYou must have slept off. Plug in a charger now.";
        root.addView(text(sub, 20, Color.parseColor("#FFD6D9"), false));
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView stop = text("I'M AWAKE \u2013 STOP", 20, Color.WHITE, true);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(40));
        pill.setColor(Color.parseColor("#22C55E"));
        stop.setBackground(pill);
        stop.setOnClickListener(v -> { RingService.stop(this); finish(); });
        root.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
    }

    private void answer() {
        RingService.stop(this);
        final Intent go = RingService.answerIntent(this, ai, type, say, id);
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { startActivity(go); finish(); }
                    @Override public void onDismissCancelled() { finish(); }
                    @Override public void onDismissError() { finish(); }
                });
                return;
            }
        } catch (Exception ignored) { }
        startActivity(go);
        finish();
    }

    @Override
    public void onBackPressed() {
        RingService.stop(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        try { if (ended != null) unregisterReceiver(ended); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
