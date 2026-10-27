// Builds the unpacked extension into dist/: bundles the scripts, copies Stockfish and its GPL
// licence, and renders the toolbar icons from the shared logo.
import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { Resvg } from '@resvg/resvg-js';

const out = 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(`${out}/stockfish`, { recursive: true });
mkdirSync(`${out}/icons`, { recursive: true });

await build({
  entryPoints: { content: 'src/content.js', background: 'src/background.js', offscreen: 'src/offscreen.js' },
  outdir: out,
  bundle: true,
  format: 'iife',
  target: 'chrome116',
  loader: { '.bin': 'binary' },
  legalComments: 'none',
});

cpSync('static', out, { recursive: true });
for (const file of ['stockfish-19-lite-single.js', 'stockfish-19-lite-single.wasm']) {
  cpSync(`node_modules/stockfish/bin/${file}`, `${out}/stockfish/${file}`);
}
cpSync('node_modules/stockfish/Copying.txt', `${out}/stockfish/COPYING.txt`);

// Toolbar and store icons, rendered from the shared logo (branding/logo.svg).
const logo = readFileSync('../branding/logo.svg');
for (const size of [16, 32, 48, 128]) {
  const png = new Resvg(logo, { fitTo: { mode: 'width', value: size } }).render().asPng();
  writeFileSync(`${out}/icons/${size}.png`, png);
}
console.log('Built extension into', out);
