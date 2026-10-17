const { chromium } = require('playwright-core');
let browser; const queues = new Map();
async function getBrowser() {
  if (!browser || !browser.isConnected())
    browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  return browser;
}
// one request at a time per provider (each gets a fresh page = fresh chat)
function ask(p, prompt, dbg) {
  const prev = queues.get(p.id) || Promise.resolve();
  const run = prev.catch(() => {}).then(() => doAsk(p, prompt, dbg));
  queues.set(p.id, run);
  return run;
}
async function doAsk(p, prompt, dbg) {
  const trace = [], shots = [], T0 = Date.now();
  const step = (name, ok, detail = '') => trace.push({ step: name, ok, detail: String(detail).slice(0, 300), t: Date.now() - T0 });
  let ctx, page, raw = '', selInfo = '';
  const shot = async label => { if (!dbg || !page) return; try { shots.push({ label, img: (await page.screenshot({ type: 'jpeg', quality: 45 })).toString('base64') }); } catch {} };
  try {
    ctx = await (await getBrowser()).newContext({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
      viewport: { width: 1280, height: 800 } });
    if (p.cookieList) {
      const ck = p.cookieList.map(c => { const o = { name: c.name, value: String(c.value), path: c.path || '/', secure: !!c.secure, httpOnly: !!c.httpOnly };
        if (c.domain) o.domain = c.domain; else o.url = p.url;
        const e = c.expirationDate || c.expires; if (e && e > 0) o.expires = Math.floor(e);
        const ss = String(c.sameSite || '').toLowerCase(); if (ss === 'no_restriction' || ss === 'none') o.sameSite = 'None'; else if (ss === 'lax') o.sameSite = 'Lax'; else if (ss === 'strict') o.sameSite = 'Strict';
        return o; });
      await ctx.addCookies(ck).catch(e => step('Cookie warning', false, e.message));
      step('Session cookies loaded', true, ck.length + ' cookies');
    }
    page = await ctx.newPage();
    await page.goto(p.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    step('Page loaded', true, 'final URL: ' + page.url() + ' — title: ' + await page.title().catch(() => ''));
    if (p.cookieList && /login|signin|sign-in|auth/i.test(page.url())) step('Login wall?', false, 'Redirected to a login page — session cookies expired or invalid');
    await page.waitForTimeout(3500);
    await shot('1. Page loaded');
    const sel = p.inputSel || 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"]';
    const box = page.locator(sel).first();
    const count = await page.locator(sel).count();
    await box.waitFor({ state: 'visible', timeout: 25000 });
    selInfo = await box.evaluate(e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className == 'string' ? '.' + e.className.trim().split(/\s+/).slice(0, 2).join('.') : '') + (e.placeholder ? ' placeholder="' + e.placeholder + '"' : ''));
    step('Text box found', true, `${count} match(es) for "${sel}"; using first: ${selInfo}`);
    const before = await page.evaluate(() => document.body.innerText);
    await box.click();
    await page.keyboard.insertText(prompt);
    await page.waitForTimeout(400);
    step('Message typed', true, `${prompt.length} chars`);
    await shot('2. Message typed into their text box');
    if (p.sendSel) await page.click(p.sendSel); else await page.keyboard.press('Enter');
    step('Message sent', true, p.sendSel ? 'clicked ' + p.sendSel : 'pressed Enter');
    const beforeLines = new Set(before.split('\n').map(s => s.trim()));
    const promptLines = new Set(prompt.split('\n').map(s => s.trim()));
    const read = async () => {
      if (p.responseSel) {
        const els = page.locator(p.responseSel), n = await els.count();
        return n ? (await els.nth(n - 1).innerText()).trim() : '';
      }
      const after = await page.evaluate(() => document.body.innerText);
      raw = after;
      return after.split('\n').map(s => s.trim()).filter(l => l && !beforeLines.has(l) && !promptLines.has(l)).join('\n');
    };
    let last = '', stable = 0, result = ''; const t1 = Date.now();
    while (Date.now() - t1 < 90000) {
      await page.waitForTimeout(1000);
      const cur = await read().catch(() => '');
      if (cur && cur === last) { if (++stable >= 4) { result = cur; break; } } else { stable = 0; last = cur; }
    }
    if (!result && last) result = last;
    if (!result) throw new Error('Timed out waiting for reply (no new text appeared)');
    step('Reply detected', true, `${result.length} chars, stable for 4s, took ${Math.round((Date.now() - t1) / 1000)}s`);
    await shot('3. Reply received');
    return dbg ? { ok: true, text: result, raw: raw.slice(-4000), selector: selInfo, trace, shots } : result;
  } catch (e) {
    step('Failed', false, e.message);
    await shot('Failure state');
    if (dbg) return { ok: false, error: e.message, raw: raw.slice(-4000), selector: selInfo, trace, shots };
    throw e;
  } finally { if (ctx) await ctx.close().catch(() => {}); }
}
module.exports = { ask };
