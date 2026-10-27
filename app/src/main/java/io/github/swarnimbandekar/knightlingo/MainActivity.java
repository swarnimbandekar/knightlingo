package io.github.swarnimbandekar.knightlingo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 40, REQUEST_NOTIFICATION = 41;
    private enum Pending { NONE, OVERLAY, TOUCH, NOTIFICATION }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView overlayStatus, touchStatus, notificationStatus, screenStatus, setupHint, progressLabel;
    private View progressFill;
    private Button startButton, manualButton;
    private Pending pending = Pending.NONE;
    private boolean preparing, prepareTouch, resumed, captureRequested;
    private long connectWaitAt;
    private String hint = "Already granted permissions will be skipped automatically.";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (preparing && pending == Pending.NONE) advanceSetup();
            handler.postDelayed(this, 800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.column(this);
        scroll.addView(content);
        Ui.insets(this, content, 24, 22);
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(Ui.chip(this, "KNIGHTLINGO", Ui.GREEN));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = Ui.dp(this, 8);
        badges.addView(Ui.chip(this, "v" + versionName(), Ui.MUTED), gap);
        Ui.add(content, badges, 0);

        LinearLayout status = Ui.column(this);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 20));
        status.setBackground(Ui.card(this));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.label(this, "Setup", Ui.BLUE), new LinearLayout.LayoutParams(0, -2, 1));
        progressLabel = Ui.text(this, "", 13, Ui.MUTED, true);
        heading.addView(progressLabel);
        Ui.add(status, heading, 0);
        // Duolingo-style progress bar: a rounded track with a green fill.
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.background(Ui.LINE, 9999, this));
        track.setWeightSum(3);
        progressFill = new View(this);
        progressFill.setBackground(Ui.background(Ui.GREEN, 9999, this));
        track.addView(progressFill, new LinearLayout.LayoutParams(0, -1, 0));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 16));
        trackParams.topMargin = Ui.dp(this, 10);
        status.addView(track, trackParams);
        overlayStatus = Ui.text(this, "", 15, Ui.TEXT, false);
        touchStatus = Ui.text(this, "", 15, Ui.TEXT, false);
        notificationStatus = Ui.text(this, "", 15, Ui.MUTED, false);
        screenStatus = Ui.text(this, "", 14, Ui.MUTED, false);
        Ui.add(status, overlayStatus, 16);
        Ui.add(status, touchStatus, 10);
        Ui.add(status, notificationStatus, 10);
        Ui.add(status, screenStatus, 14);
        Ui.add(content, status, 20);

        startButton = Ui.button(this, "Prepare and connect", true);
        startButton.setOnClickListener(view -> startSetup(true));
        Ui.add(content, startButton, 20);
        setupHint = Ui.text(this, hint, 14, Ui.MUTED, false);
        setupHint.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.add(content, setupHint, 10);
        manualButton = Ui.button(this, "Connect Tip only", false);
        manualButton.setOnClickListener(view -> startSetup(false));
        Ui.add(content, manualButton, 14);
        Button stop = Ui.button(this, "Disconnect", false);
        Ui.setStyle(stop, Ui.BG, Ui.LINE, Ui.LINE, Ui.RED);
        stop.setOnClickListener(view -> {
            preparing = false;
            stopService(new Intent(this, TipService.class));
            refreshStatus();
        });
        Ui.add(content, stop, 12);

        LinearLayout how = Ui.column(this);
        how.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 20));
        how.setBackground(Ui.card(this));
        Ui.add(how, Ui.label(this, "How it works", Ui.BLUE), 0);
        String[] steps = {
                "Share ‘Single app → Duolingo’ when asked.",
                "On the game screen, switch TIP and Auto-play on or off.",
                "Stop Auto-play any time to play by hand.",
                "Drag the panel to move it. Tap buttons briefly."};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView number = Ui.text(this, String.valueOf(i + 1), 14, Ui.BLUE, true);
            number.setGravity(Gravity.CENTER);
            number.setIncludeFontPadding(false);
            GradientDrawable dot = Ui.background(Ui.BG, 9999, this);
            dot.setStroke(Ui.dp(this, 2), Ui.LINE);
            number.setBackground(dot);
            row.addView(number, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.leftMargin = Ui.dp(this, 12);
            row.addView(Ui.text(this, steps[i], 15, Ui.TEXT, false), textParams);
            Ui.add(how, row, 12);
        }
        Ui.add(content, how, 24);
        TextView footer = Ui.text(this,
                "Built for Oscar, friends and random games in portrait dark mode. "
                        + "Auto-play waits on the result screen and resumes with the next game. "
                        + "Screen sharing is approved each time.\n\n"
                        + "Everything is processed on your phone. Images and video are never stored or sent.\n\n"
                        + "Knightlingo is an independent app, not affiliated with Duolingo.",
                13, Ui.MUTED, false);
        footer.setTypeface(Ui.weight(500));
        Ui.add(content, footer, 18);
        setContentView(scroll);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "?";
        }
    }

    private void startSetup(boolean requireTouch) {
        if (TipService.running || captureRequested) return;
        preparing = true;
        prepareTouch = requireTouch;
        connectWaitAt = 0;
        pending = Pending.NONE;
        advanceSetup();
        refreshStatus();
    }

    private void advanceSetup() {
        if (!preparing || !resumed || pending != Pending.NONE || captureRequested) return;
        if (!Settings.canDrawOverlays(this)) {
            pending = Pending.OVERLAY;
            hint = "Enable ‘Display over other apps’ for Knightlingo, then return here to continue.";
            refreshStatus();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (prepareTouch && !ChessTouchService.enabled(this)) {
            pending = Pending.TOUCH;
            hint = "Go to Accessibility → Installed apps → Knightlingo Auto-play, enable it, then return here.";
            refreshStatus();
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (prepareTouch && ChessTouchService.current() == null) {
            if (connectWaitAt == 0) connectWaitAt = SystemClock.elapsedRealtime();
            hint = "Touch permission is granted. Waiting for the service to connect…";
            if (SystemClock.elapsedRealtime() - connectWaitAt > 10000) {
                preparing = false;
                hint = "The touch service is taking too long to connect. Try the Connect button again shortly.";
            }
            refreshStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            pending = Pending.NOTIFICATION;
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
            return;
        }
        preparing = false;
        captureRequested = true;
        hint = "In the sharing dialog, select ‘Single app → Duolingo’.";
        Log.i("ChessTip", "SETUP_READY touch=" + prepareTouch);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATION) { pending = Pending.NONE; advanceSetup(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAPTURE) return;
        captureRequested = false;
        if (result != RESULT_OK || data == null) {
            hint = "Screen connection cancelled. Use Connect to start again.";
            refreshStatus();
            return;
        }
        Intent service = new Intent(this, TipService.class)
                .putExtra(TipService.RESULT_CODE, result).putExtra(TipService.RESULT_DATA, data);
        startForegroundService(service);
        handler.postDelayed(this::refreshStatus, 400);
    }

    private void refreshStatus() {
        if (startButton == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean touch = ChessTouchService.enabled(this);
        boolean connected = ChessTouchService.current() != null;
        overlayStatus.setText(overlay ? "✓ Display over other apps · Ready" : "○ Display over other apps · Permission needed");
        overlayStatus.setTextColor(overlay ? Ui.GREEN : Ui.TEXT);
        touchStatus.setText(touch ? connected ? "✓ Auto-play touch · Ready" : "◌ Auto-play touch · Allowed, connecting" : "○ Auto-play touch · Permission needed");
        touchStatus.setTextColor(touch && connected ? Ui.GREEN : Ui.TEXT);
        notificationStatus.setText(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "✓ Status notifications · Allowed" : "○ Status notifications · Off (connection still available)");
        int ready = (overlay ? 1 : 0) + (touch && connected ? 1 : 0) + (TipService.running ? 1 : 0);
        ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = ready;
        progressFill.requestLayout();
        progressLabel.setText(ready + " / 3");
        progressLabel.setTextColor(ready == 3 ? Ui.GREEN : Ui.MUTED);
        screenStatus.setTextColor(TipService.running ? Ui.GREEN : Ui.MUTED);
        screenStatus.setText(TipService.running ? "✓ Screen connected · " + TipService.coachStatus
                : TipService.lastError != null ? TipService.lastError : "○ Duolingo screen sharing · Allow when connecting");
        startButton.setEnabled(!preparing && !captureRequested && !TipService.running);
        startButton.setText(TipService.running ? "Connected" : preparing ? "Preparing…"
                : overlay && touch && connected ? "Connect to Duolingo" : "Prepare and connect");
        setupHint.setText(hint);
        manualButton.setVisibility(touch && connected ? View.GONE : View.VISIBLE);
        manualButton.setEnabled(!preparing && !captureRequested && !TipService.running);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pending == Pending.OVERLAY || pending == Pending.TOUCH) {
            boolean allowed = pending == Pending.OVERLAY ? Settings.canDrawOverlays(this) : ChessTouchService.enabled(this);
            pending = Pending.NONE;
            if (!allowed) {
                preparing = false;
                hint = "Permission is still needed. Use Connect to continue setup, or connect Tip only.";
            }
        }
        Log.i("ChessTip", "SETUP_CHECK overlay=" + Settings.canDrawOverlays(this) + " touchEnabled="
                + ChessTouchService.enabled(this) + " touchConnected=" + (ChessTouchService.current() != null));
        handler.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
