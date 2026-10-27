package io.github.swarnimbandekar.knightlingo;

import static org.junit.Assert.*;
import org.junit.Test;

public class PanelStatusTest {
    private static final PanelStatus.Text OPPONENT = new PanelStatus.Text("You: White · Opponent: Black\nOpponent’s turn", "");
    private static final PanelStatus.Text OWN = new PanelStatus.Text("You: White · Opponent: Black\nYour turn", "Calculating the suggested move…");
    private static final PanelStatus.Text WAIT = new PanelStatus.Text("Auto-play · Confirming", "Re-reading the board.");

    @Test public void repeatedBriefReadingGapsKeepTheOpponentTextAndColors() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        for (long now = 100; now <= 10000; now += 100) {
            boolean readingGap = now % 1000 >= 700;
            assertEquals(OPPONENT, display.update(now, readingGap ? WAIT : OPPONENT, readingGap));
        }
    }

    @Test public void sustainedWaitAppearsAfterThreeSecondsWithOneStableExplanation() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        assertEquals(OPPONENT, display.update(100, WAIT, true));
        assertEquals(OPPONENT, display.update(3099, WAIT, true));
        assertEquals(WAIT, display.update(3100, WAIT, true));
        PanelStatus.Text movement = new PanelStatus.Text("Auto-play · Confirming", "Checking that the move was applied.");
        assertEquals(WAIT, display.update(3200, movement, true));
        assertEquals(OWN, display.update(3300, OWN, false));
    }

    @Test public void confirmedTurnAndPlayerColorChangesAreImmediate() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        display.update(100, WAIT, true);
        assertEquals(OWN, display.update(200, OWN, false));
        PanelStatus.Text black = new PanelStatus.Text("You: Black · Opponent: White\nOpponent’s turn", "");
        assertEquals(black, display.update(300, black, false));
    }

    @Test public void resetDropsThePreviousGameAndAllowsImmediateInstructions() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        display.reset();
        assertEquals(WAIT, display.update(100, WAIT, true));
        assertEquals(OWN, display.update(200, OWN, false));
        display.reset();
        PanelStatus.Text permission = new PanelStatus.Text("Waiting for screen connection", "Please reconnect the screen.");
        assertEquals(permission, display.update(300, permission, true));
    }
}
