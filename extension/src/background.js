// Relays analysis requests from the Duolingo tab to the offscreen engine host and toggles the dock.
let creating = null;

async function ensureEngineHost() {
  const contexts = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] });
  if (contexts.length) return;
  creating ??= chrome.offscreen.createDocument({
    url: 'offscreen.html',
    reasons: ['WORKERS'],
    justification: 'Runs the Stockfish chess engine in a web worker.',
  }).finally(() => { creating = null; });
  await creating;
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type !== 'analyze' || message.target) return false;
  ensureEngineHost()
    .then(() => chrome.runtime.sendMessage({ target: 'offscreen', type: 'analyze', fen: message.fen }))
    .then((result) => respond(result || { error: 'No engine response' }))
    .catch((error) => respond({ error: String(error && error.message || error) }));
  return true;
});

chrome.action.onClicked.addListener((tab) => {
  if (tab.id != null) chrome.tabs.sendMessage(tab.id, { type: 'toggle-dock' }).catch(() => {});
});
