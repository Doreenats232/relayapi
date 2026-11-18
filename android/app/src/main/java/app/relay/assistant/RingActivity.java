package app.relay.assistant;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
                call ? new int[]{Color.parseColor("#14464a"), Color.parseColor("#0C0D14")} : new int[]{Color.parseColor("#5a0f18"), Color.parseColor("#0C0D14")});
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

    private void buildCall(LinearLayout root) {
        boolean video = "video".equals(type);
        String name = ai.isEmpty() ? "Relay" : Character.toUpperCase(ai.charAt(0)) + ai.substring(1);
        root.addView(text("Relay " + (video ? "video" : "audio") + " call", 15, Color.parseColor("#9AA3B8"), false));
        TextView nm = text(name, 36, Color.WHITE, true);
        nm.setPadding(0, dp(12), 0, dp(6));
        root.addView(nm);
        root.addView(text("Incoming call\u2026", 16, Color.parseColor("#9AA3B8"), false));

        int[] pal = {0xFF22C55E, 0xFF6366F1, 0xFFF59E0B, 0xFFEC4899, 0xFF0EA5E9, 0xFF14B8A6, 0xFFF97316};
        int h = 0;
        for (int k = 0; k < name.length(); k++) h = (h * 31 + name.charAt(k)) & 0x7fffffff;
        TextView av = text(name.substring(0, 1).toUpperCase(), 64, Color.WHITE, true);
        av.setBackground(oval(pal[h % pal.length]));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(170), dp(170));
        ap.topMargin = dp(48);
        root.addView(av, ap);

        if (!say.isEmpty()) {
            TextView s = text("\u201C" + (say.length() > 140 ? say.substring(0, 140) + "\u2026" : say) + "\u201D", 15, Color.parseColor("#C9CEDC"), false);
            s.setPadding(dp(16), dp(28), dp(16), 0);
            root.addView(s);
        }

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout dec = action("\u2715", "Decline", Color.parseColor("#E5484D"), v -> { RingService.stop(this); finish(); });
        LinearLayout ans = action(video ? "\uD83D\uDCF9" : "\uD83D\uDCDE", "Answer", Color.parseColor("#22C55E"), v -> answer());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(dec, lp);
        row.addView(ans, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
