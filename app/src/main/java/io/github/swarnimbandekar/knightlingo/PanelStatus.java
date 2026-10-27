package io.github.swarnimbandekar.knightlingo;

/** Smooths presentation only. Touch and board validation never use this state. Main thread only. */
final class PanelStatus {
    record Text(String label, String detail) {}
    private Text stable, waitingText;
    private long waitingSince = -1;

    Text update(long now, Text current, boolean waiting) {
        if (!waiting) {
            stable = current;
            waitingSince = -1;
            waitingText = null;
            return current;
        }
        if (waitingSince < 0) waitingSince = now;
        if (stable != null && now - waitingSince < 3000) return stable;
        // Keep one explanation through a wait instead of alternating internal wait reasons.
        if (waitingText == null) waitingText = current;
        return waitingText;
    }

    void reset() {
        stable = null;
        waitingText = null;
        waitingSince = -1;
    }
}
