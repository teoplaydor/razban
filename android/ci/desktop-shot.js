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
];

(async () => {
  fs.mkdirSync('desktop-shots', { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 }, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'error') console.log('[console.error]', m.text().slice(0, 200)); });
  for (const [r, name] of ROUTES) {
    try {
      await page.goto('http://127.0.0.1:8137/index.html#/' + r, { waitUntil: 'load', timeout: 20000 });
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
