// Port of MoveTip.java: turns an engine move into the short explanation shown in the dock.
import { Chess } from 'chess.js';

const NAMES = { p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king' };
const GLYPHS = { p: '♟︎', n: '♞', b: '♝', r: '♜', q: '♛', k: '♚' };

/** @returns {{move, title, glyphTitle, feature, continuation, capture} | null} */
export function explain(fen, result) {
  const board = new Chess(fen);
  const uci = result.move || '';
  let move;
  try {
    move = board.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] });
  } catch {
    return null;
  }
  const type = move.piece;
  const pieceName = NAMES[type];
  const capture = !!move.captured;
  let feature;
  if (move.flags.includes('k') || move.flags.includes('q')) feature = 'Castle to move the king and develop the rook.';
  else if (move.flags.includes('e')) feature = 'Capture the opponent’s pawn en passant.';
  else if (move.captured) feature = `Capture the opponent’s ${NAMES[move.captured]}.`;
  else if ((type === 'n' || type === 'b') && (move.from[1] === '1' || move.from[1] === '8')) feature = `Develop the ${pieceName} to open up its activity.`;
  else if (type === 'p' && 'de'.includes(move.to[0])) feature = 'Advance a pawn to a central file.';
  else feature = 'The engine’s top-rated move in this position.';
  if (board.isCheckmate()) feature = 'This move delivers checkmate.';
  else if (board.inCheck()) feature = 'This move checks the opponent’s king.';
  if (move.promotion) feature = `Promote the pawn to a ${NAMES[move.promotion]}.`;
  const variation = (result.variation || '').split(' ');
  const continuation = variation.length >= 3 ? `Expected ${variation[1]} → ${variation[2]}` : `Analysis depth ${result.depth}`;
  return {
    move: uci,
    title: `${pieceName} ${move.from} → ${move.to}`,
    glyphTitle: `${GLYPHS[type]} ${move.from} → ${move.to}`,
    feature, continuation, capture,
  };
}

/** True when playing {@code uci} from {@code fen} leaves the opponent without a legal move. */
export function endsGame(fen, uci) {
  try {
    const board = new Chess(fen);
    board.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] });
    return board.moves().length === 0;
  } catch {
    return false;
  }
}
