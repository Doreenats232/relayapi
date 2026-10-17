const { chromium } = require('playwright-core');
const crypto = require('crypto');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
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
      viewport: { width: 1280, height: 800 },
      ...(p.cookieList && !Array.isArray(p.cookieList) ? { storageState: p.cookieList } : {}) });
    if (p.cookieList && !Array.isArray(p.cookieList)) step('Saved browser session loaded', true, (p.cookieList.cookies || []).length + ' cookies');
    if (Array.isArray(p.cookieList)) {
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
    if (page) { const chal = await page.evaluate(() => /verify(ing)? (you are )?human|just a moment|checking your browser|captcha/i.test(document.body.innerText)).catch(() => false);
      if (chal || /__cf_chl|challenge/i.test(page.url())) step('Human verification detected', false, 'Open Live view on this provider, complete the check yourself, then Save session'); }
    step('Failed', false, e.message);
    await shot('Failure state');
    if (dbg) return { ok: false, error: e.message, raw: raw.slice(-4000), selector: selInfo, trace, shots };
    throw e;
  } finally { if (ctx) await ctx.close().catch(() => {}); }
}

// ---------- live view (human completes captcha / login) ----------
const live = new Map();
const normCookies = (list, url) => list.map(c => { const o = { name: c.name, value: String(c.value), path: c.path || '/', secure: !!c.secure, httpOnly: !!c.httpOnly };
  if (c.domain) o.domain = c.domain; else o.url = url;
  const e = c.expirationDate || c.expires; if (e && e > 0) o.expires = Math.floor(e);
  const ss = String(c.sameSite || '').toLowerCase(); if (ss === 'no_restriction' || ss === 'none') o.sameSite = 'None'; else if (ss === 'lax') o.sameSite = 'Lax'; else if (ss === 'strict') o.sameSite = 'Strict';
  return o; });
const touch = (sid, s) => { clearTimeout(s.timer); s.timer = setTimeout(() => liveClose(sid), 10 * 60 * 1000); };
async function liveStart(p, owner) {
  for (const [k, v] of live) if (v.owner === owner) await liveClose(k);
  const opts = { userAgent: UA, viewport: { width: 1280, height: 800 } };
  if (p.cookieList && !Array.isArray(p.cookieList)) opts.storageState = p.cookieList;
  const ctx = await (await getBrowser()).newContext(opts);
  if (Array.isArray(p.cookieList)) await ctx.addCookies(normCookies(p.cookieList, p.url)).catch(() => {});
  const page = await ctx.newPage();
  page.goto(p.url, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  const sid = crypto.randomBytes(8).toString('hex'), s = { ctx, page, owner };
  touch(sid, s); live.set(sid, s); return sid;
}
function liveGet(sid, owner) { const s = live.get(sid); if (!s || s.owner !== owner) throw new Error('Live session ended'); touch(sid, s); return s; }
async function liveFrame(sid, owner) { return liveGet(sid, owner).page.screenshot({ type: 'jpeg', quality: 55 }); }
async function liveInput(sid, owner, d) {
  const { page } = liveGet(sid, owner);
  if (d.type === 'click') await page.mouse.click(Math.max(0, +d.x), Math.max(0, +d.y));
  else if (d.type === 'text') await page.keyboard.type(String(d.text).slice(0, 2000), { delay: 15 });
  else if (d.type === 'key') await page.keyboard.press(String(d.key).slice(0, 20));
  else if (d.type === 'scroll') await page.mouse.wheel(0, +d.dy || 0);
}
async function liveSave(sid, owner) { const s = liveGet(sid, owner), st = await s.ctx.storageState(); await liveClose(sid); return st; }
async function liveClose(sid) { const s = live.get(sid); if (!s) return; clearTimeout(s.timer); live.delete(sid); await s.ctx.close().catch(() => {}); }
module.exports = { ask, liveStart, liveFrame, liveInput, liveSave, liveClose, liveOwns: (sid, o) => live.get(sid)?.owner === o };

