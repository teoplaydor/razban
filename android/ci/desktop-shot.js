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
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
           '--ignore-gpu-blocklist', '--enable-webgl', '--enable-accelerated-2d-canvas'],
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 }, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 200)); });
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
