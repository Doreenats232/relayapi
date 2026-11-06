package app.relay.assistant;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/** Lets the AI read the screen and tap/type/scroll in other apps, only while the in-app switch is on. */
public class RelayAccessibility extends AccessibilityService {
    static volatile RelayAccessibility inst = null;
    static volatile boolean ctlOn = false;

    @Override protected void onServiceConnected() { inst = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent i) { inst = null; return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }

    static String gate() {
        if (!ctlOn) return "Screen control is switched off in Relay settings.";
        if (inst == null) return "Accessibility is not enabled for Relay. Turn it on in Settings.";
        return null;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || sb.length() > 5000 || depth > 40) return;
        if (n.isVisibleToUser() && !n.isPassword()) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            String s = t != null && t.length() > 0 ? t.toString() : (d != null ? d.toString() : "");
            s = s.trim();
            if (!s.isEmpty()) {
                if (n.isClickable() || n.isCheckable()) sb.append("[button] ");
                else if (n.isEditable()) sb.append("[input] ");
                sb.append(s.length() > 300 ? s.substring(0, 300) : s).append('\n');
            } else if (n.isEditable()) sb.append("[empty input]\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) { collect(c, sb, depth + 1); c.recycle(); }
        }
    }

    static String read() {
        String g = gate(); if (g != null) return g;
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        if (root == null) return "Could not read the screen.";
        StringBuilder sb = new StringBuilder();
        inst.collect(root, sb, 0);
        String out = sb.toString().trim();
        return out.isEmpty() ? "The screen has no readable text." : out;
    }

    static String tap(String text) {
        String g = gate(); if (g != null) return g;
        if (text == null || text.trim().isEmpty()) return "Nothing to tap.";
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        if (root == null) return "Could not read the screen.";
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text.trim());
        String want = text.trim().toLowerCase(Locale.ROOT);
        AccessibilityNodeInfo pick = null;
        for (AccessibilityNodeInfo h : hits) {
            if (!h.isVisibleToUser() || h.isPassword()) continue;
            CharSequence t = h.getText(), d = h.getContentDescription();
            String s = String.valueOf(t != null ? t : d != null ? d : "").toLowerCase(Locale.ROOT);
            if (s.equals(want)) { pick = h; break; }
            if (pick == null) pick = h;
        }
        if (pick == null) return "Could not find \"" + text + "\" on the screen.";
        AccessibilityNodeInfo n = pick;
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n == null) return "\"" + text + "\" is not tappable.";
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "Tapped " + text : "The tap did not work.";
    }

    static String type(String text) {
        String g = gate(); if (g != null) return g;
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        if (root == null) return "Could not read the screen.";
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) f = firstEditable(root, 0);
        if (f == null) return "No text box found on the screen.";
        if (f.isPassword()) return "That is a password box, so I will not type into it.";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "Typed the text" : "Could not type there.";
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = scrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static String scroll(String dir) {
        String g = gate(); if (g != null) return g;
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        AccessibilityNodeInfo s = scrollable(root, 0);
        if (s == null) return "Nothing to scroll here.";
        boolean up = dir != null && dir.toLowerCase(Locale.ROOT).startsWith("up");
        return s.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ? "Scrolled " + (up ? "up" : "down") : "Could not scroll.";
    }

    static String global(String which) {
        String g = gate(); if (g != null) return g;
        int a = "home".equals(which) ? GLOBAL_ACTION_HOME : "recents".equals(which) ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_BACK;
        return inst.performGlobalAction(a) ? "Done" : "That did not work.";
    }
}
