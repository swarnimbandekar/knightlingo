// Hosts Stockfish (WASM, lite single-threaded) in a worker. Port of StockfishEngine.java: one search
// at a time, 1 s per move, and a fresh engine after any timeout so stale output is never reused.
const ENGINE = 'stockfish/stockfish-19-lite-single.js';
let engine = null;
let listeners = [];
let queue = Promise.resolve();

function start() {
  engine = new Worker(ENGINE);
  engine.onmessage = (event) => { for (const listener of [...listeners]) listener(String(event.data)); };
  return command('uci', 'uciok', 12000).then(() => {
    engine.postMessage('setoption name Hash value 32');
    engine.postMessage('setoption name Skill Level value 20');
    return command('isready', 'readyok', 12000);
  });
}

function command(line, token, timeout) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { remove(); reject(new Error(`Stockfish timed out waiting for ${token}`)); }, timeout);
    const listener = (text) => { if (text.startsWith(token)) { remove(); resolve(text); } };
    const remove = () => { clearTimeout(timer); listeners = listeners.filter((l) => l !== listener); };
    listeners.push(listener);
    engine.postMessage(line);
  });
}

function reset() {
  if (engine) engine.terminate();
  engine = null;
  listeners = [];
}

async function analyze(fen) {
  if (!engine) await start();
  let variation = '', depth = 0;
  const info = (text) => {
    if (!text.startsWith('info depth ') || !text.includes(' pv ')) return;
    depth = Number(text.split(' ')[2]);
    variation = text.slice(text.indexOf(' pv ') + 4);
  };
  listeners.push(info);
  try {
    engine.postMessage(`position fen ${fen}`);
    const best = await command('go movetime 1000', 'bestmove ', 8000);
    return { move: best.split(' ')[1], variation, depth };
  } finally {
    listeners = listeners.filter((l) => l !== info);
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.target !== 'offscreen' || message.type !== 'analyze') return false;
  queue = queue.then(() => analyze(message.fen))
    .then((result) => respond(result))
    .catch((error) => { reset(); respond({ error: String(error && error.message || error) }); });
  return true;
});
