// Render the SAME React dist at desktop width (≥768px → Sidebar layout, not the
// mobile shell) in headless Chromium and screenshot every route. The bridge runs
// in mock mode in a bare browser (no window.chrome.webview), so data is demo —
// but the LAYOUT/visual is the real desktop look. Answers "как выглядит на компе"
// remotely, with no WPF/WebView2 needed.
const { chromium } = require('playwright');
const fs = require('fs');

const ROUTES = [
  ['home', '01-home'],
  ['servers', '02-servers'],
  ['stats', '03-stats'],
  ['settings', '04-settings'],
  ['about', '05-about'],
  // `?notif=demo` seeds the in-app notification cards (update + kill-switch) so
  // the screenshot captures the NotificationCenter look. The param is preview-only.
  ['home', '06-notifications', 'notif=demo'],
];

(async () => {
  fs.mkdirSync('desktop-shots', { recursive: true });
  // Enable SOFTWARE WebGL so the 3D (three.js) globe actually renders in headless
  // CI (default headless Chromium has no GL → our 2D fallback would show instead).
  const browser = await chromium.launch({
    args: ['--use-gl=swiftshader', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
           '--ignore-gpu-blocklist', '--enable-webgl', '--disable-gpu-sandbox'],
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 }, deviceScaleFactor: 1 });
  page.on('console', m => { const t = m.text(); if (m.type() === 'error' || t.indexOf('globe3d') >= 0) console.log('[console.' + m.type() + ']', t.slice(0, 240)); });
  // Report WebGL availability + renderer so we know whether the 3D globe (vs the
  // 2D fallback) is what we're screenshotting.
  await page.goto('http://127.0.0.1:8137/index.html#/home', { waitUntil: 'load', timeout: 20000 });
  const gl = await page.evaluate(() => {
    try { const c = document.createElement('canvas'); const g = c.getContext('webgl') || c.getContext('experimental-webgl');
      if (!g) return 'NO_WEBGL';
      const dbg = g.getExtension('WEBGL_debug_renderer_info');
      return 'WEBGL_OK renderer=' + (dbg ? g.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : '?');
    } catch (e) { return 'WEBGL_ERR ' + e.message; }
  });
  console.log('[webgl-probe] ' + gl);
  for (const [r, name, query] of ROUTES) {
    try {
      const url = 'http://127.0.0.1:8137/index.html' + (query ? '?' + query : '') + '#/' + r;
      await page.goto(url, { waitUntil: 'load', timeout: 20000 });
      await page.waitForTimeout(2800); // let fonts + framer settle
      await page.screenshot({ path: `desktop-shots/${name}.png` });
      const txt = (await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 200))) || '';
      console.log(`shot ${name}: ${txt}`);
    } catch (e) {
      console.log(`FAIL ${name}: ${e.message}`);
    }
  }
  await browser.close();
})();
