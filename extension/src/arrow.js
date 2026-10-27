// Port of ArrowView.java: a source ring, a shaft and a head, plus a tinted target square.
// Pointer events pass straight through to the board.

const SVG = 'http://www.w3.org/2000/svg';

export function createArrow() {
  const svg = document.createElementNS(SVG, 'svg');
  Object.assign(svg.style, {
    position: 'fixed', inset: '0', width: '100vw', height: '100vh', pointerEvents: 'none',
    zIndex: '2147483646', opacity: '0.75', display: 'none',
  });
  svg.setAttribute('aria-hidden', 'true');
  document.documentElement.appendChild(svg);
  let drawn = '';

  return {
    update(state) {
      if (!state) { svg.style.display = 'none'; drawn = ''; return; }
      const { board, tip } = state;
      const cell = board.size / 8;
      const x = (file) => board.left + ((board.whiteBottom ? file.charCodeAt(0) - 97 : 104 - file.charCodeAt(0)) + 0.5) * cell;
      const y = (rank) => board.top + ((board.whiteBottom ? 56 - rank.charCodeAt(0) : rank.charCodeAt(0) - 49) + 0.5) * cell;
      const sx = x(tip.move[0]), sy = y(tip.move[1]), ex = x(tip.move[2]), ey = y(tip.move[3]);
      const key = [sx, sy, ex, ey, tip.capture].map((v) => (typeof v === 'number' ? v.toFixed(1) : v)).join();
      svg.style.display = '';
      if (key === drawn) return;
      drawn = key;
      const length = Math.hypot(ex - sx, ey - sy);
      const ux = (ex - sx) / length, uy = (ey - sy) / length;
      const head = cell * 0.35, half = cell / 2 - cell * 0.025, radius = cell * 0.06;
      const color = tip.capture ? '#ffc800' : '#58cc02';
      const stroke = cell * 0.12;
      const tipX = ex, tipY = ey;
      const baseX = ex - ux * head, baseY = ey - uy * head;
      svg.innerHTML = `
        <rect x="${ex - half}" y="${ey - half}" width="${half * 2}" height="${half * 2}" rx="${radius}"
          fill="${color}" fill-opacity=".35" stroke="${color}" stroke-opacity=".75" stroke-width="${cell * 0.025}"/>
        <circle cx="${sx}" cy="${sy}" r="${cell * 0.25}" fill="none" stroke="${color}" stroke-width="${stroke}"/>
        <line x1="${sx + ux * cell * 0.25}" y1="${sy + uy * cell * 0.25}" x2="${ex - ux * head * 0.6}" y2="${ey - uy * head * 0.6}"
          stroke="${color}" stroke-width="${stroke}" stroke-linecap="round"/>
        <polygon fill="${color}" points="${tipX},${tipY} ${baseX - uy * head * 0.55},${baseY + ux * head * 0.55} ${baseX + uy * head * 0.55},${baseY - ux * head * 0.55}"/>`;
    },
  };
}
