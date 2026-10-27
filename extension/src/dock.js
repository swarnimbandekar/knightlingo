// The "chess dock": a translucent pill with ♞ Tip and ▶ Auto-play circles and a two-line status,
// the same design as the Android overlay. Lives in a shadow root so Duolingo's CSS can't touch it.

const COLORS = { green: '#58cc02', blue: '#1cb0f6', yellow: '#ffc800', red: '#ff4b4b', subtle: '#52656d' };

const CSS = `
:host { all: initial; }
.dock {
  position: fixed; z-index: 2147483647; display: flex; align-items: center; gap: 8px;
  padding: 8px 14px 8px 8px; border-radius: 30px; box-sizing: border-box;
  background: rgba(19, 31, 36, .72); border: 1px solid rgba(255, 255, 255, .18);
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 6px 24px rgba(0, 0, 0, .35);
  font: 700 13px/1.25 "Nunito", "DIN Next Rounded", system-ui, -apple-system, "Segoe UI", sans-serif;
  color: #fff; user-select: none; touch-action: none; cursor: grab;
}
.dock.dragging { cursor: grabbing; }
.dock[hidden] { display: none; }
button {
  all: unset; box-sizing: border-box; width: 40px; height: 40px; flex: none; border-radius: 50%;
  display: grid; place-items: center; cursor: pointer; font-size: 20px; line-height: 1;
  background: #131f24; border: 2px solid #37464f; color: #1cb0f6; transition: filter .12s, background .12s;
}
button:hover { filter: brightness(1.15); }
button:active { filter: brightness(.8); }
button:focus-visible { outline: 2px solid #1cb0f6; outline-offset: 2px; }
.tip { color: #58cc02; border-color: #b0ed7a; font-size: 22px; }
.tip.on { background: #58cc02; color: #fff; }
.auto { font-size: 14px; }
.auto.on { background: #1cb0f6; border-color: #1cb0f6; color: #fff; }
.status { width: 150px; margin-left: 2px; cursor: pointer; text-shadow: 0 1px 4px rgba(0, 0, 0, .6); }
.label, .detail { overflow: hidden; text-overflow: ellipsis; }
.label { white-space: nowrap; }
.dot { margin-right: 4px; }
.detail { color: #93a5ad; font-size: 12px; margin-top: 3px; white-space: nowrap; }
.detail.emphasize { color: #fff; }
.expanded .detail { white-space: pre-line; }
`;

export function createDock({ onTip, onAuto, onExpand }) {
  const host = document.createElement('chess-tip-dock');
  const root = host.attachShadow({ mode: 'closed' });
  root.innerHTML = `<style>${CSS}</style>
    <div class="dock" hidden>
      <button class="tip" aria-label="Tip" aria-pressed="false" title="Tip · long-press to hide">♞</button>
      <button class="auto" aria-label="Start Auto-play" title="Auto-play">▶&#xFE0E;</button>
      <div class="status" role="status" title="Tap for details">
        <div class="label"><span class="dot">●</span><span class="text">Open a chess match</span></div>
        <div class="detail"></div>
      </div>
    </div>`;
  document.documentElement.appendChild(host);
  const dock = root.querySelector('.dock');
  const tip = root.querySelector('.tip');
  const auto = root.querySelector('.auto');
  const status = root.querySelector('.status');
  const dot = root.querySelector('.dot');
  const text = root.querySelector('.text');
  const detail = root.querySelector('.detail');

  let wanted = true;
  let position = null;
  chrome.storage.local.get(['dockWanted', 'dockPosition']).then((stored) => {
    if (stored.dockWanted === false) wanted = false;
    if (stored.dockPosition) position = stored.dockPosition;
    place();
  }).catch(() => {});

  function place() {
    const width = dock.offsetWidth || 270, height = dock.offsetHeight || 58;
    const x = position ? position.x : window.innerWidth - width - 16;
    const y = position ? position.y : 16;
    dock.style.left = Math.max(0, Math.min(window.innerWidth - width, x)) + 'px';
    dock.style.top = Math.max(0, Math.min(window.innerHeight - height, y)) + 'px';
  }
  window.addEventListener('resize', place);

  // Drag anywhere; a press that moves more than a few pixels is a drag, not a tap.
  let drag = null, suppressClick = false, holdTimer = 0;
  dock.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    const rect = dock.getBoundingClientRect();
    drag = { id: event.pointerId, dx: event.clientX - rect.left, dy: event.clientY - rect.top, sx: event.clientX, sy: event.clientY, moved: false };
    suppressClick = false;
    if (event.composedPath()[0] === tip) {
      holdTimer = setTimeout(() => { suppressClick = true; setWanted(false); }, 700);
    }
  });
  dock.addEventListener('pointermove', (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    if (!drag.moved && Math.hypot(event.clientX - drag.sx, event.clientY - drag.sy) < 5) return;
    if (!drag.moved) { drag.moved = true; clearTimeout(holdTimer); dock.setPointerCapture(event.pointerId); dock.classList.add('dragging'); }
    position = { x: event.clientX - drag.dx, y: event.clientY - drag.dy };
    place();
  });
  const end = (event) => {
    clearTimeout(holdTimer);
    if (!drag || drag.id !== event.pointerId) return;
    if (drag.moved) {
      suppressClick = true;
      dock.classList.remove('dragging');
      chrome.storage.local.set({ dockPosition: position }).catch(() => {});
    }
    drag = null;
  };
  dock.addEventListener('pointerup', end);
  dock.addEventListener('pointercancel', end);
  const guard = (handler) => (event) => {
    if (suppressClick) { suppressClick = false; event.preventDefault(); return; }
    handler();
  };
  tip.addEventListener('click', guard(onTip));
  auto.addEventListener('click', guard(onAuto));
  status.addEventListener('click', guard(onExpand));

  function setWanted(value) {
    wanted = value;
    chrome.storage.local.set({ dockWanted: value }).catch(() => {});
    if (!value) dock.hidden = true;
  }

  return {
    wanted: () => wanted,
    toggleWanted: () => setWanted(!wanted),
    setVisible(visible) {
      if (dock.hidden === !visible) return;
      dock.hidden = !visible;
      if (visible) requestAnimationFrame(place);
    },
    update({ label, detail: lines, expanded, dot: state, tipOn, autoOn, emphasize }) {
      dot.style.color = COLORS[state] || COLORS.subtle;
      if (text.textContent !== label) text.textContent = label;
      const shown = expanded ? lines : lines.split('\n')[0];
      if (detail.textContent !== shown) detail.textContent = shown;
      detail.hidden = !shown;
      detail.classList.toggle('emphasize', !!emphasize);
      dock.classList.toggle('expanded', !!expanded);
      tip.classList.toggle('on', tipOn);
      tip.setAttribute('aria-pressed', String(tipOn));
      auto.classList.toggle('on', autoOn);
      auto.textContent = autoOn ? '■' : '▶︎';
      auto.setAttribute('aria-label', autoOn ? 'Stop Auto-play' : 'Start Auto-play');
    },
  };
}
