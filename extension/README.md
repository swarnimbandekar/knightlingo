# Knightlingo for Chrome

*Chess tips for Duolingo. Knightlingo is an independent project, not affiliated with Duolingo.*

The Chrome version of the Android app: same board reading, tracking, Stockfish tips, dock and
Auto-play, running on Duolingo's web chess (`duolingo.com/chess-matches`).

| Android app | Extension |
|---|---|
| MediaProjection screen capture | Pixels read straight from Duolingo's board `<canvas>`, so the dock and arrow never get in the way |
| `BoardVision.java` + `pieces.bin` | `src/vision.js`, same algorithm and templates |
| `GameTracker.java` (chesslib) | `src/tracker.js` (chess.js) |
| `MoveTip.java`, `AutoPlay.java` | `src/movetip.js`, `src/autoplay.js` |
| Stockfish binary | Stockfish 19 lite (WASM) in an offscreen document (`src/offscreen.js`) |
| Accessibility taps | Pointer events dispatched on the board canvas |
| Floating dock, `ArrowView` | `src/dock.js`, `src/arrow.js` |

## Build and load

```bash
npm install
npm run build
```

Then in Chrome: open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked**, and
pick the `extension/dist` folder. Open a chess match on duolingo.com; the dock appears at the top
right. The toolbar icon (or a long press on ♞) hides and shows the dock.

## Test

```bash
npm test
```

Runs the JS port against the Android test screenshots and scenarios. Drop a PNG of the web board
canvas at `test/fixtures/web-board.png` (White at the bottom, after 1.e4) to also test the web art.

Stockfish is GPLv3; its licence ships in `dist/stockfish/COPYING.txt`.
