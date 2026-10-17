const { chromium } = require('playwright-core');
let browser; const queues = new Map();
async function getBrowser() {
  if (!browser || !browser.isConnected())
    browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  return browser;
}
// one request at a time per provider (each gets a fresh page = fresh chat)
function ask(p, prompt) {
  const prev = queues.get(p.id) || Promise.resolve();
  const run = prev.catch(() => {}).then(() => doAsk(p, prompt));
  queues.set(p.id, run);
  return run;
}
async function doAsk(p, prompt) {
  const ctx = await (await getBrowser()).newContext({
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
    viewport: { width: 1280, height: 800 } });
  const page = await ctx.newPage();
  try {
    await page.goto(p.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(3500);
    const sel = p.inputSel || 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"]';
    const box = page.locator(sel).first();
    await box.waitFor({ state: 'visible', timeout: 25000 });
    const before = await page.evaluate(() => document.body.innerText);
    await box.click();
    await page.keyboard.insertText(prompt);
    await page.waitForTimeout(300);
    if (p.sendSel) await page.click(p.sendSel); else await page.keyboard.press('Enter');
    const beforeLines = new Set(before.split('\n').map(s => s.trim()));
    const promptLines = new Set(prompt.split('\n').map(s => s.trim()));
    const read = async () => {
      if (p.responseSel) {
        const els = page.locator(p.responseSel);
        const n = await els.count();
        return n ? (await els.nth(n - 1).innerText()).trim() : '';
      }
      const after = await page.evaluate(() => document.body.innerText);
      return after.split('\n').map(s => s.trim())
        .filter(l => l && !beforeLines.has(l) && !promptLines.has(l)).join('\n');
    };
    let last = '', stable = 0; const t0 = Date.now();
    while (Date.now() - t0 < 90000) {
      await page.waitForTimeout(1000);
      const cur = await read().catch(() => '');
      if (cur && cur === last) { if (++stable >= 4) return cur; } else { stable = 0; last = cur; }
    }
    if (last) return last;
    throw new Error('Timed out waiting for reply');
  } finally { await ctx.close().catch(() => {}); }
}
module.exports = { ask };
