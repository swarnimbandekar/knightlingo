package io.github.swarnimbandekar.knightlingo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ChessTouchService extends AccessibilityService {
    private static final String DUO = "com.duolingo";
    private static volatile ChessTouchService instance;
    private static String lastActivity = "";
    private static int lastMatchWindow = -1;
    enum Foreground { MATCH, PROMOTION, OTHER, UNKNOWN }
    private String activity = "";
    private int matchWindow = -1;
    private long windowChangedAt;

    static ChessTouchService current() { return instance; }
    long windowChangedAt() { return windowChangedAt; }

    static boolean enabled(Context context) {
        String setting = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) return false;
        ComponentName expected = new ComponentName(context, ChessTouchService.class);
        for (String name : setting.split(":")) if (expected.equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }

    @Override protected void onServiceConnected() {
        activity = lastActivity;
        matchWindow = lastMatchWindow;
        windowChangedAt = SystemClock.elapsedRealtime();
        instance = this;
        Log.i("ChessTip", "TOUCH_SERVICE_CONNECTED");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !DUO.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        String name = String.valueOf(event.getClassName());
        if (name.startsWith(DUO + ".") && name.endsWith("Activity")) {
            if (!activity.equals(name) || matchWindow != event.getWindowId()) windowChangedAt = SystemClock.elapsedRealtime();
            activity = name;
            matchWindow = ChessScreens.match(name) ? event.getWindowId() : -1;
            lastActivity = activity;
            lastMatchWindow = matchWindow;
            Log.i("ChessTip", "DUO_WINDOW " + name + " id=" + matchWindow);
        }
    }

    private boolean interactive() {
        return getSystemService(PowerManager.class).isInteractive() && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private AccessibilityNodeInfo focusedRoot() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getDisplayId() != Display.DEFAULT_DISPLAY || !window.isFocused()) continue;
            return window.getRoot();
        }
        return null;
    }

    private AccessibilityNodeInfo root() {
        if (!interactive()) return null;
        AccessibilityNodeInfo root = focusedRoot();
        return root != null && DUO.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()) ? root : null;
    }

    Foreground foreground() {
        if (!interactive()) return Foreground.OTHER;
        AccessibilityNodeInfo root = focusedRoot();
        if (root == null) return Foreground.UNKNOWN;
        if (!DUO.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return Foreground.OTHER;
        if (activity.isEmpty()) return Foreground.UNKNOWN;
        if (!ChessScreens.match(activity)) return Foreground.OTHER;
        return root.getWindowId() == matchWindow ? Foreground.MATCH : Foreground.PROMOTION;
    }

    boolean matchForeground() {
        return foreground() == Foreground.MATCH;
    }

    void tap(int x, int y, Consumer<Boolean> completion) {
        AccessibilityNodeInfo root = root();
        Rect bounds = new Rect();
        if (root != null) root.getBoundsInScreen(bounds);
        if (!matchForeground() || !bounds.contains(x, y)) { completion.accept(false); return; }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 30)).build();
        boolean queued = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { completion.accept(true); }
            @Override public void onCancelled(GestureDescription description) { completion.accept(false); }
        }, null);
        if (!queued) completion.accept(false);
    }

    boolean promotionWindow() {
        AccessibilityNodeInfo root = root();
        return root != null && ChessScreens.match(activity) && root.getWindowId() != matchWindow;
    }

    boolean choosePromotion(char piece) {
        AccessibilityNodeInfo root = root();
        if (root == null || !ChessScreens.match(activity) || root.getWindowId() == matchWindow) return false;
        Map<Character, AccessibilityNodeInfo> options = new HashMap<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 1500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String label = String.valueOf(node.getText()).trim().toLowerCase(java.util.Locale.ROOT);
            if (node.getText() == null) label = String.valueOf(node.getContentDescription()).trim().toLowerCase(java.util.Locale.ROOT);
            char kind = switch (label) {
                case "queen" -> 'q'; case "rook" -> 'r';
                case "bishop" -> 'b'; case "knight" -> 'n'; default -> '?';
            };
            if (kind != '?' && node.isVisibleToUser()) {
                AccessibilityNodeInfo target = node;
                for (int depth = 0; depth < 2 && !target.isClickable() && target.getParent() != null; depth++) target = target.getParent();
                if (target.isClickable()) {
                    if (options.containsKey(kind)) return false;
                    options.put(kind, target);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) queue.add(node.getChild(i));
        }
        AccessibilityNodeInfo target = options.get(piece);
        return options.size() == 4 && target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override public void onInterrupt() { Log.i("ChessTip", "TOUCH_SERVICE_INTERRUPTED"); }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        Log.i("ChessTip", "TOUCH_SERVICE_DISCONNECTED");
        super.onDestroy();
    }
}
