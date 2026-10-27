package io.github.swarnimbandekar.knightlingo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TipService extends Service {
    static final String RESULT_CODE = "capture.resultCode";
    static final String RESULT_DATA = "capture.data";
    private static final String STOP = "io.github.swarnimbandekar.knightlingo.STOP";
    private static final String TAG = "ChessTip";
    private static final String CHANNEL = "chesstip_capture";
    static volatile boolean running;
    static volatile boolean tipOn;
    static volatile boolean autoPlaying;
    static volatile long frames;
    static volatile int frameWidth;
    static volatile int frameHeight;
    static volatile boolean frameHasContent;
    static volatile String lastError;
    static volatile String coachStatus = "Before game screen connection";
    private volatile String coachDetail = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread imageThread;
    private Handler imageHandler;
    private WindowManager windows;
    private LinearLayout panel;
    private Button toggle;
    private Button autoButton;
    private TextView frameLabel;
    private ArrowView arrow;
    private WindowManager.LayoutParams panelParams;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private long lastFrameCheck;
    private final Object frameLock = new Object();
    private Image held;
    private final Runnable heartbeat = this::processHeld;
    private volatile boolean releasing;
    private BoardVision vision;
    private GameTracker tracker;
    private volatile BoardVision.Observation observedBoard;
    private volatile String observedFen;
    private volatile boolean observedWhiteBottom;
    private volatile boolean reliable;
    private volatile long observedAt;
    private volatile boolean myTurn;
    private final ExecutorService engineWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean();
    private volatile StockfishEngine engine;
    private record Analysis(String fen, MoveTip.Tip tip) {}
    private volatile Analysis cached;
    private TextView reasonLabel;
    private boolean expanded;
    private volatile boolean capturedVisible = true;
    private volatile int probeX = -1, probeY = -1;
    private volatile boolean wholeScreenDetected;
    private final AutoPlay auto = new AutoPlay();
    private final PanelStatus panelStatus = new PanelStatus();
    private long statusWindowAt = -1;
    private volatile AutoPlay.Position autoPosition;
    private boolean observedTerminal;
    private boolean autoButtonActive;
    private String promotionHandledFen;
    private String inputWait = "";
    private long inputWaitAt = -1;
    private volatile String visionIssue = "";
    private volatile long unreadableSince = -1;
    private long failedReadMillis;
    private long lastVisionIssueAt;
    private long boardReads;
    private boolean panelInteracting;
    private final Runnable autoLoop = new Runnable() {
        @Override public void run() {
            if (!running || releasing) return;
            runAuto();
            showCurrentTip();
            main.postDelayed(this, auto.enabled() ? 100 : 250);
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            Log.i(TAG, "CAPTURE_STOPPED_BY_SYSTEM");
            stopSelf();
        }

        @Override public void onCapturedContentResize(int width, int height) {
            if (imageHandler != null) imageHandler.post(() -> resizeCapture(width, height));
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            capturedVisible = visible;
            Log.i(TAG, "CAPTURE_VISIBILITY " + visible);
            if (!visible) {
                reliable = false;
                autoPosition = null;
                if (auto.enabled()) waitForInput("Waiting for the game screen.\nWe’ll continue when you return.", SystemClock.elapsedRealtime());
                coachStatus = "Return to the Duolingo screen";
                coachDetail = "";
                showCurrentTip();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen connection status", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (!Settings.canDrawOverlays(this)) {
            fail("Display-over-other-apps permission is required. Allow it in the app and try again.", null);
            return START_NOT_STICKY;
        }
        Intent captureData = intent.getParcelableExtra(RESULT_DATA, Intent.class);
        if (captureData == null) {
            fail("Please restart the screen connection.", null);
            return START_NOT_STICKY;
        }
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(
                    intent.getIntExtra(RESULT_CODE, 0), captureData);
            projection.registerCallback(projectionCallback, main);
            frames = 0;
            frameWidth = 0;
            frameHeight = 0;
            frameHasContent = false;
            tipOn = false;
            autoPlaying = false;
            lastError = null;
            coachStatus = "Waiting for the first screen";
            coachDetail = "";
            vision = new BoardVision(getAssets().open("pieces.bin"));
            android.content.SharedPreferences saved = getSharedPreferences("game", MODE_PRIVATE);
            tracker = new GameTracker(saved.getString("fen", null), saved.getBoolean("whiteBottom", true));
            releasing = false;
            imageThread = new HandlerThread("ChessTip-Frames");
            imageThread.start();
            imageHandler = new Handler(imageThread.getLooper());
            Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            reader = newReader(bounds.width(), bounds.height());
            display = projection.createVirtualDisplay("ChessTip-ConnectionTest", bounds.width(), bounds.height(),
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, imageHandler);
            running = true;
            showPanel();
            main.postDelayed(autoLoop, 200);
            Log.i(TAG, "CAPTURE_STARTED");
        } catch (RuntimeException | IOException error) {
            fail("Screen connection failed. Please restart the connection.", error);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, TipService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Knightlingo · Chess coach")
                .setContentText("Receiving screen · Long-press the Tip button to stop")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private ImageReader newReader(int width, int height) {
        ImageReader result = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        result.setOnImageAvailableListener(source -> {
            try {
                Image latest = source.acquireLatestImage();
                if (latest == null) return;
                // Keep the newest frame. The screen stops producing frames once it is still, so the
                // last frame of a move must stay available for the heartbeat to read again.
                synchronized (frameLock) { closeHeld(); held = latest; }
                processHeld();
            } catch (IllegalStateException error) {
                if (!releasing) Log.w(TAG, "Frame reader closed while resizing", error);
            }
        }, imageHandler);
        return result;
    }

    private void closeHeld() {
        if (held == null) return;
        try { held.close(); } catch (RuntimeException ignored) { }
        held = null;
    }

    private void processHeld() {
        if (releasing) return;
        synchronized (frameLock) {
            if (held == null) return;
            long now = SystemClock.elapsedRealtime();
            // A full-screen board search costs ~2 s; while no board is visible (result screen,
            // menus) back off so the phone is not kept at full CPU.
            long interval = Math.max(autoPlaying ? 250 : 500, Math.min(3000, failedReadMillis * 2));
            imageHandler.removeCallbacks(heartbeat);
            if (now - lastFrameCheck < interval) {
                imageHandler.postDelayed(heartbeat, interval - (now - lastFrameCheck));
                return;
            }
            lastFrameCheck = now;
            try {
                Image image = held;
                frameWidth = image.getWidth();
                frameHeight = image.getHeight();
                frameHasContent = hasVisiblePixels(image);
                frames++;
                if (frames == 1) Log.i(TAG, "FRAME_READY " + frameWidth + "x" + frameHeight + " content=" + frameHasContent);
                readBoard(image);
            } catch (IllegalStateException error) {
                if (!releasing) Log.w(TAG, "Frame invalid while reading", error);
                held = null;
            } catch (RuntimeException error) {
                Log.e(TAG, "BOARD_READ_ERROR", error);
            }
            // A still screen sends no new frames; keep re-reading the last one so the board stays
            // confirmed and the tip never goes stale in the middle of a game.
            imageHandler.postDelayed(heartbeat, interval);
        }
    }

    private void readBoard(Image image) {
        if (!capturedVisible) return;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        BoardVision.Frame frame = new BoardVision.Frame() {
            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int rgb(int x, int y) {
                int offset = y * plane.getRowStride() + x * plane.getPixelStride();
                return (pixels.get(offset) & 255) << 16 | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255;
            }
        };
        // Only the selected Duolingo task may be captured. A full-display capture includes our
        // mint button, then our arrows, creating a recognition feedback loop. Fail with a stable
        // instruction instead of repeatedly hiding and repainting a move.
        if (tipOn && probeX >= 0 && probeX < frame.width() && probeY >= 0 && probeY < frame.height()) {
            int pixel = frame.rgb(probeX, probeY);
            if (Math.abs(((pixel >> 16) & 255) - android.graphics.Color.red(Ui.PROBE)) < 8
                    && Math.abs(((pixel >> 8) & 255) - android.graphics.Color.green(Ui.PROBE)) < 8
                    && Math.abs((pixel & 255) - android.graphics.Color.blue(Ui.PROBE)) < 8) {
                wholeScreenDetected = true;
            }
        }
        if (wholeScreenDetected) {
            reliable = false;
            autoPosition = null;
            coachStatus = "Full-screen sharing was selected";
            main.post(this::showCurrentTip);
            return;
        }
        long readStarted = SystemClock.elapsedRealtime();
        boardReads++;
        BoardVision.Observation observation = vision.read(frame);
        long readMillis = SystemClock.elapsedRealtime() - readStarted;
        failedReadMillis = observation == null ? readMillis : 0;
        if (observation != null) {
            android.view.WindowMetrics metrics = windows.getMaximumWindowMetrics();
            Rect screen = metrics.getBounds();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            float scale = screen.width() / (float) image.getWidth();
            int captureHeight = Math.round(image.getHeight() * scale);
            int offset = Math.abs(captureHeight - (screen.height() - insets.top - insets.bottom)) <= 3
                    || Math.abs(captureHeight - (screen.height() - insets.top)) <= 3 ? insets.top : 0;
            observation = new BoardVision.Observation(Math.round(observation.left() * scale),
                    Math.round(observation.top() * scale) + offset, Math.round(observation.size() * scale),
                    observation.cells(), observation.error(), observation.selectedCell(), observation.uncertain());
        }
        observedAt = SystemClock.elapsedRealtime();
        reliable = tracker.accept(observation);
        if (!reliable) {
            if (unreadableSince < 0) unreadableSince = observedAt;
            autoPosition = null;
            coachStatus = observation == null ? "Reading the board" : "Confirming the move sequence";
            coachDetail = observation == null ? "Close anything covering\nthe board" : "Connect from the first screen\nof a new game";
            String issue = observation == null ? vision.lastIssue : "Stabilizing the board or confirming legal moves";
            long now = SystemClock.elapsedRealtime();
            if (!issue.equals(visionIssue) || now - lastVisionIssueAt > 5000) {
                Log.i(TAG, "BOARD_WAIT " + issue + " reads=" + boardReads + " readMs=" + readMillis);
                lastVisionIssueAt = now;
            }
            visionIssue = issue;
            main.post(this::showCurrentTip);
            return;
        }
        unreadableSince = -1;
        if (!visionIssue.isEmpty()) { Log.i(TAG, "BOARD_RECOVERED reads=" + boardReads + " readMs=" + readMillis); visionIssue = ""; }
        observedBoard = observation;
        observedWhiteBottom = tracker.whiteBottom();
        myTurn = tracker.myTurn();
        String fen = tracker.fen();
        if (!fen.equals(observedFen)) {
            observedFen = fen;
            observedTerminal = tracker.terminal();
            getSharedPreferences("game", MODE_PRIVATE).edit().putString("fen", fen).putBoolean("whiteBottom", observedWhiteBottom).apply();
            Log.i(TAG, "BOARD_CONFIRMED " + tracker.sideName() + " " + fen);
        }
        coachStatus = observedTerminal ? "Game over" : myTurn ? "Your turn" : "Opponent’s turn";
        coachDetail = "";
        autoPosition = new AutoPlay.Position(fen, observedWhiteBottom, myTurn, observedAt,
                observation.left(), observation.top(), observation.size(), observation.selectedCell(), observedTerminal);
        main.post(this::showCurrentTip);
        if ((tipOn || autoPlaying) && myTurn && !observedTerminal) requestAnalysis();
    }

    private void requestAnalysis() {
        String fen = observedFen;
        Analysis previous = cached;
        if (!running || !reliable || !myTurn || fen == null || previous != null && fen.equals(previous.fen()) || !analyzing.compareAndSet(false, true)) return;
        engineWorker.execute(() -> {
            try {
                if (engine == null) engine = new StockfishEngine(new File(getApplicationInfo().nativeLibraryDir, "libstockfish.so"));
                if (releasing) return;
                StockfishEngine.Result result = engine.analyze(fen);
                MoveTip.Tip tip = MoveTip.explain(fen, result);
                if (tip != null && !releasing) {
                    cached = new Analysis(fen, tip);
                    lastError = null;
                    Log.i(TAG, "ENGINE_BESTMOVE " + result.move() + " depth=" + result.depth());
                } else if (!releasing) {
                    Log.w(TAG, "ENGINE_NO_TIP move=" + result.move() + " fen=" + fen);
                }
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (!releasing) {
                    lastError = "Engine hiccup · Retrying";
                    Log.e(TAG, "ENGINE_ERROR", error);
                    // A timed-out or crashed engine may still emit stale output; start a clean one next time.
                    if (engine != null) { engine.close(); engine = null; }
                }
            } finally {
                analyzing.set(false);
                if (releasing && engine != null) engine.close();
                main.post(() -> { runAuto(); showCurrentTip(); });
            }
        });
    }

    private void showCurrentTip() {
        if (!running || releasing || frameLabel == null) return;
        long now = SystemClock.elapsedRealtime();
        Analysis analysis = cached;
        boolean fresh = now - observedAt < 1500;
        boolean ready = tipOn && reliable && fresh && myTurn && inputWait.isEmpty() && analysis != null && observedFen != null && observedFen.equals(analysis.fen());
        boolean waiting = auto.enabled() && (!inputWait.isEmpty() || auto.waitingForBoard() || auto.waitingForMove());
        AutoPlay.Position position = autoPosition;
        ChessTouchService touch = ChessTouchService.current();
        long windowAt = touch == null ? 0 : touch.windowChangedAt();
        if (statusWindowAt != windowAt || !capturedVisible || wholeScreenDetected || !inputWait.isEmpty()) {
            panelStatus.reset();
            statusWindowAt = windowAt;
        }
        boolean colorsKnown = position != null && capturedVisible && !wholeScreenDetected
                && now - position.seenAt() < 1500
                && (touch == null || position.seenAt() > windowAt);
        String players = colorsKnown ? (position.whiteBottom() ? "♔ " : "♚ ") : "";
        String label = players + (auto.waitingForNextGame() ? "Next game" : waiting ? "Confirming" : coachStatus);
        String detail = wholeScreenDetected ? "Disconnect, then reconnect with ‘Single app → Duolingo’."
                : !inputWait.isEmpty() ? inputWait : auto.promotionPending() ? "Confirming the promotion piece.\nYou can also choose it manually."
                : auto.waitingForNextGame() ? "The game is over.\nOpen a new game to continue."
                : auto.waitingForBoard() ? "Re-reading the board.\nAuto-play will continue when it is confirmed." + boardHint(now)
                : auto.waitingForMove() ? "Checking that the move was applied.\nRetrying if needed."
                : ready ? pieceMove(analysis.tip()) + "\n" + analysis.tip().feature() + "\n" + analysis.tip().continuation()
                : lastError != null ? lastError : !auto.enabled() && !auto.note().isEmpty() ? auto.note()
                : !tipOn ? (auto.enabled() ? "Auto-play in progress" : "Turn on Tip to see the suggested move")
                : reliable && myTurn ? "Calculating the suggested move…" : coachDetail;
        PanelStatus.Text shown = panelStatus.update(now, new PanelStatus.Text(label, detail), waiting || !reliable || !fresh);
        // Dot colour carries the state at a glance; the text stays to two short lines unless expanded.
        int dot = wholeScreenDetected || lastError != null ? Ui.RED : ready ? Ui.GREEN
                : waiting || tipOn && !reliable ? Ui.YELLOW : auto.enabled() ? Ui.BLUE : tipOn ? Ui.GREEN : Ui.SUBTLE;
        android.text.SpannableString title = new android.text.SpannableString("● " + shown.label());
        title.setSpan(new android.text.style.ForegroundColorSpan(dot), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!android.text.TextUtils.equals(frameLabel.getText(), title)) {
            frameLabel.setText(title);
            Log.i(TAG, "PANEL_STATUS " + shown.label().replace('\n', '|'));
        }
        String compact = shown.detail();
        if (!expanded) {
            int line = compact.indexOf('\n');
            if (line >= 0) compact = compact.substring(0, line);
        }
        if (!android.text.TextUtils.equals(reasonLabel.getText(), compact)) reasonLabel.setText(compact);
        reasonLabel.setMaxLines(expanded ? 8 : 1);
        reasonLabel.setTextColor(ready ? Ui.TEXT : Ui.MUTED);
        reasonLabel.setVisibility(compact.isEmpty() ? View.GONE : View.VISIBLE);
        if (ready) {
            ensureArrow();
            if (arrow != null) arrow.update(observedBoard, analysis.tip(), observedWhiteBottom);
        } else if (arrow != null) arrow.update(null, null, true);
        updateAutoButton();
    }

    /** After a few seconds without a readable board, say why so a stall is never silent. */
    private String boardHint(long now) {
        long since = unreadableSince;
        String issue = visionIssue;
        if (since < 0 || now - since < 4000 || issue.isEmpty()) return "";
        if (issue.startsWith("cell=")) {
            String[] cell = issue.substring(5, issue.indexOf(' ')).split(",");
            int row = Integer.parseInt(cell[0]), col = Integer.parseInt(cell[1]);
            char file = (char) (observedWhiteBottom ? 'a' + col : 'h' - col);
            char rank = (char) (observedWhiteBottom ? '8' - row : '1' + row);
            return "\nToo many unclear squares, e.g. " + file + rank + ".";
        }
        if (issue.startsWith("Stabilizing")) return "\nThe moves on screen don’t match yet.";
        return "\n" + issue + ".";
    }

    private void runAuto() {
        if (!running || releasing || !auto.enabled() || panelInteracting) return;
        long now = SystemClock.elapsedRealtime();
        if (!capturedVisible || wholeScreenDetected) {
            waitForInput(wholeScreenDetected ? "Reconnect the screen with\n‘Single app → Duolingo’." : "Waiting for the game screen.\nWe’ll continue when you return.", now);
            return;
        }
        ChessTouchService touch = ChessTouchService.current();
        if (touch == null) {
            waitForInput(ChessTouchService.enabled(this) ? "Reconnecting the touch service…" : "Enable Auto-play touch permission\nin the app.", now);
            return;
        }
        ChessTouchService.Foreground state = touch.foreground();
        if (state != ChessTouchService.Foreground.MATCH && !(auto.promotionPending() && state == ChessTouchService.Foreground.PROMOTION)) {
            waitForInput("Waiting for the chess game screen.\nAuto-play will continue when it opens.", now);
            return;
        }
        AutoPlay.Position position = autoPosition;
        if (position != null && position.seenAt() <= touch.windowChangedAt()) {
            waitForInput("Re-checking the board\non the new game screen.", now);
            return;
        }
        if (!inputWait.isEmpty()) Log.i(TAG, "AUTO_INPUT_READY " + state);
        inputWait = "";
        inputWaitAt = -1;
        if (auto.promotionPending() && !java.util.Objects.equals(promotionHandledFen, auto.pendingFen())
                && touch.choosePromotion(auto.promotionPiece())) promotionHandledFen = auto.pendingFen();
        Analysis analysis = cached;
        AutoPlay.Action action = auto.next(now, position,
                analysis == null ? null : analysis.fen(), analysis == null ? null : analysis.tip(), true);
        autoPlaying = auto.enabled();
        if (action == null) return;
        if (panelCovers(action.fromX(), action.fromY()) || panelCovers(action.toX(), action.toY())) {
            auto.defer(action, now);
            waitForInput("Move the panel outside\nthe chessboard to continue.", now);
            return;
        }
        Log.i(TAG, "AUTO_TAP_MOVE " + action.move() + " selected=" + action.sourceSelected() + " attempt=" + action.attempt());
        Runnable destination = () -> {
            if (!auto.pending(action) || releasing) return;
            if (panelInteracting || !capturedVisible || wholeScreenDetected || panelCovers(action.toX(), action.toY())) {
                finishMove(action, false);
                return;
            }
            touch.tap(action.toX(), action.toY(), success -> finishMove(action, success));
        };
        if (action.sourceSelected()) destination.run();
        else touch.tap(action.fromX(), action.fromY(), success -> {
            if (!auto.pending(action)) return;
            if (!success) finishMove(action, false);
            else main.postDelayed(destination, 300);
        });
    }

    private boolean panelCovers(int x, int y) {
        int[] location = new int[2];
        panel.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + panel.getWidth(), location[1] + panel.getHeight()).contains(x, y);
    }

    private void finishMove(AutoPlay.Action action, boolean success) {
        boolean pending = auto.pending(action);
        auto.complete(action, success, SystemClock.elapsedRealtime());
        autoPlaying = auto.enabled();
        if (success && pending) Log.i(TAG, "AUTO_MOVE_SENT " + action.move());
        else if (pending) Log.i(TAG, "AUTO_TOUCH_WAIT " + action.move());
        showCurrentTip();
    }

    private void waitForInput(String message, long now) {
        auto.waitForInput(now);
        if (inputWaitAt < 0) { inputWaitAt = now; Log.i(TAG, "AUTO_WAIT_INPUT " + message); }
        inputWait = message;
    }

    private void toggleAuto() {
        if (auto.enabled()) { pauseAuto("Auto-play stopped · You can play manually"); return; }
        auto.start(SystemClock.elapsedRealtime());
        panelStatus.reset();
        autoPlaying = true;
        inputWait = "";
        inputWaitAt = -1;
        promotionHandledFen = null;
        requestAnalysis();
        Log.i(TAG, "AUTO_STARTED");
        runAuto();
        showCurrentTip();
    }

    private void pauseAuto(String message) {
        if (!auto.enabled()) return;
        auto.pause(message);
        panelStatus.reset();
        autoPlaying = false;
        inputWait = "";
        inputWaitAt = -1;
        Log.i(TAG, "AUTO_PAUSED " + message);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        showCurrentTip();
    }

    private void updateAutoButton() {
        if (autoButton == null) return;
        if (autoButtonActive != auto.enabled()) {
            autoButtonActive = auto.enabled();
            styleAutoButton();
        }
    }

    private void styleAutoButton() {
        autoButton.setText(autoButtonActive ? "■" : "▶︎");
        autoButton.setContentDescription(autoButtonActive ? "Stop Auto-play" : "Start Auto-play");
        if (autoButtonActive) Ui.setFlatRound(autoButton, Ui.BLUE, 0, Ui.TEXT);
        else Ui.setFlatRound(autoButton, Ui.BG, Ui.LINE, Ui.BLUE);
    }

    /** The Tip button always keeps its PROBE rim; see {@link #updateProbe()}. */
    private void styleToggle(boolean on) {
        toggle.setStateDescription(on ? "On" : "Off");
        if (on) Ui.setFlatRound(toggle, Ui.GREEN, Ui.PROBE, Ui.TEXT);
        else Ui.setFlatRound(toggle, Ui.BG, Ui.PROBE, Ui.GREEN);
    }

    private static boolean hasVisiblePixels(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int stride = plane.getRowStride();
        int step = plane.getPixelStride();
        for (int row = 1; row <= 8; row++) {
            for (int column = 1; column <= 8; column++) {
                int offset = (image.getHeight() * row / 9) * stride + (image.getWidth() * column / 9) * step;
                if (offset + 2 < pixels.limit()
                        && ((pixels.get(offset) & 255) > 20 || (pixels.get(offset + 1) & 255) > 20 || (pixels.get(offset + 2) & 255) > 20)) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void resizeCapture(int width, int height) {
        if (releasing || display == null || width <= 0 || height <= 0) return;
        if (reader != null && reader.getWidth() == width && reader.getHeight() == height) return;
        autoPosition = null;
        main.post(() -> { if (auto.enabled()) waitForInput("The screen size changed.\nRe-checking the board.", SystemClock.elapsedRealtime()); });
        ImageReader previous = reader;
        synchronized (frameLock) { closeHeld(); }
        reader = newReader(width, height);
        display.resize(width, height, getResources().getDisplayMetrics().densityDpi);
        display.setSurface(reader.getSurface());
        if (previous != null) previous.close();
        Log.i(TAG, "CAPTURE_RESIZED " + width + "x" + height);
    }

    /**
     * The "chess dock": a translucent pill holding two round buttons (♞ Tip, ▶ Auto-play) and a
     * two-line status. Tapping the status expands the full explanation.
     */
    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        android.graphics.drawable.GradientDrawable glass = Ui.background((Ui.BG & 0xffffff) | 0xb8000000, 30, this);
        glass.setStroke(Ui.dp(this, 1), 0x2effffff);
        panel.setBackground(glass);

        toggle = dockButton("♞", 22);
        toggle.setContentDescription("Tip");
        styleToggle(false);
        panel.addView(toggle, dockParams(0));
        autoButton = dockButton("▶︎", 15);
        styleAutoButton();
        autoButton.setOnClickListener(view -> toggleAuto());
        panel.addView(autoButton, dockParams(8));

        LinearLayout status = Ui.column(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(Ui.dp(this, 136), -2);
        statusParams.leftMargin = Ui.dp(this, 10);
        panel.addView(status, statusParams);
        frameLabel = Ui.text(this, "● Waiting for screen", 13, Ui.TEXT, true);
        reasonLabel = Ui.text(this, "", 12, Ui.MUTED, false);
        for (TextView label : new TextView[]{frameLabel, reasonLabel}) {
            label.setIncludeFontPadding(false);
            label.setLineSpacing(Ui.dp(this, 2), 1f);
            label.setShadowLayer(4, 0, 1, 0x99000000);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setOnClickListener(view -> { expanded = !expanded; panelStatus.reset(); showCurrentTip(); });
        }
        frameLabel.setSingleLine(true);
        status.addView(frameLabel);
        LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(-1, -2);
        reasonParams.topMargin = Ui.dp(this, 3);
        status.addView(reasonLabel, reasonParams);

        panelParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.LEFT;
        panelParams.x = Math.max(0, windows.getMaximumWindowMetrics().getBounds().width() - Ui.dp(this, 264));
        panelParams.y = Ui.dp(this, 12);
        windows.addView(panel, panelParams);
        toggle.setOnClickListener(view -> setTip(!tipOn));
        toggle.setOnLongClickListener(view -> {
            stopSelf();
            return true;
        });
        DragListener drag = new DragListener();
        for (View surface : new View[]{panel, status, toggle, autoButton, frameLabel, reasonLabel}) {
            surface.setClickable(true);
            surface.setOnTouchListener(drag);
        }
        panel.addOnLayoutChangeListener((view, a, b, c, d, e, f, g, h) -> { keepPanelOnScreen(); updateProbe(); });
        panel.post(this::updateProbe);
        Log.i(TAG, "OVERLAY_VISIBLE");
    }

    /** "knight b8 → c6" becomes "♞ b8 → c6" for the compact dock line. */
    private static String pieceMove(MoveTip.Tip tip) {
        String title = tip.title();
        int space = title.indexOf(' ');
        String glyph = switch (space < 0 ? "" : title.substring(0, space)) {
            case "king" -> "♚";
            case "queen" -> "♛";
            case "rook" -> "♜";
            case "bishop" -> "♝";
            case "knight" -> "♞";
            case "pawn" -> "♟︎";
            default -> null;
        };
        return glyph == null ? title : glyph + title.substring(space);
    }

    /** The dock's width follows its content; never let it slide past the screen edge. */
    private void keepPanelOnScreen() {
        if (panel == null || !panel.isAttachedToWindow()) return;
        int limit = windows.getMaximumWindowMetrics().getBounds().width() - panel.getWidth();
        if (panelParams.x <= limit) return;
        panelParams.x = Math.max(0, limit);
        windows.updateViewLayout(panel, panelParams);
    }

    /** A flat, round 40dp button. */
    private Button dockButton(String glyph, int size) {
        Button button = Ui.button(this, glyph, false);
        button.setAllCaps(false);
        button.setTextSize(size);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private LinearLayout.LayoutParams dockParams(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        params.leftMargin = Ui.dp(this, leftMargin);
        return params;
    }

    private final class DragListener implements android.view.View.OnTouchListener {
        private float startX, startY;
        private int originalX, originalY;
        private boolean moved;

        @Override public boolean onTouch(android.view.View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                panelInteracting = true;
                startX = event.getRawX();
                startY = event.getRawY();
                originalX = panelParams.x;
                originalY = panelParams.y;
                moved = false;
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;
                if (Math.hypot(dx, dy) > ViewConfiguration.get(TipService.this).getScaledTouchSlop()) {
                    moved = true;
                    view.cancelLongPress();
                }
                if (moved) {
                    android.view.WindowMetrics metrics = windows.getMaximumWindowMetrics();
                    Rect bounds = metrics.getBounds();
                    android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    panelParams.x = Math.max(0, Math.min(bounds.width() - panel.getWidth(), originalX + (int) dx));
                    panelParams.y = Math.max(0, Math.min(bounds.height() - insets.top - insets.bottom - panel.getHeight(), originalY + (int) dy));
                    windows.updateViewLayout(panel, panelParams);
                    panel.post(TipService.this::updateProbe);
                    return true;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                panelInteracting = false;
                if (moved) {
                    view.setPressed(false);
                    Log.i(TAG, "PANEL_MOVED x=" + panelParams.x + " y=" + panelParams.y);
                    return true;
                }
            }
            return false;
        }
    }

    private void setTip(boolean enabled) {
        panelStatus.reset();
        tipOn = enabled;
        styleToggle(enabled);
        if (enabled) requestAnalysis();
        if (!enabled && arrow != null) {
            if (arrow.isAttachedToWindow()) windows.removeView(arrow);
            arrow = null;
        }
        showCurrentTip();
        Log.i(TAG, "TIP_" + (enabled ? "ON" : "OFF"));
    }

    private void updateProbe() {
        if (toggle == null || !toggle.isAttachedToWindow()) return;
        int[] point = new int[2];
        toggle.getLocationOnScreen(point);
        // The opaque PROBE rim sits on the circle's left edge, level with its centre.
        probeX = point[0] + Math.max(1, Ui.dp(this, .5f));
        probeY = point[1] + toggle.getHeight() / 2;
    }

    private void ensureArrow() {
        if (arrow == null) {
            arrow = new ArrowView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.alpha = .7f;
            params.alpha = Math.min(params.alpha, getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch());
            try {
                windows.addView(arrow, params);
            } catch (RuntimeException error) {
                fail("Please check the display-over-other-apps permission again.", error);
                return;
            }
        }
    }

    private void fail(String message, Throwable error) {
        lastError = message;
        Log.e(TAG, message, error);
        stopSelf();
    }

    @Override public synchronized void onDestroy() {
        releasing = true;
        auto.pause("");
        autoPlaying = false;
        running = false;
        tipOn = false;
        reliable = false;
        engineWorker.shutdownNow();
        if (engine != null) engine.close();
        if (arrow != null) {
            if (arrow.isAttachedToWindow()) windows.removeViewImmediate(arrow);
            arrow = null;
        }
        if (panel != null) {
            if (panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
            panel = null;
        }
        if (projection != null) projection.unregisterCallback(projectionCallback);
        if (display != null) display.release();
        if (imageHandler != null) imageHandler.removeCallbacksAndMessages(null);
        synchronized (frameLock) { closeHeld(); }
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (imageThread != null) imageThread.quitSafely();
        main.removeCallbacksAndMessages(null);
        frameLabel = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(TAG, "SERVICE_STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
