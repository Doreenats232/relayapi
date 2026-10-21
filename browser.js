const { chromium } = require('playwright-core');
const crypto = require('crypto');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
let browser; const queues = new Map();
const HINTS = { 'chat.deepseek.com': '.ds-markdown' }; // known reply containers (used only if present on the page)
const BOILER = /^(AI-generated, for reference only|Copy|Regenerate|Share|Like|Dislike|Retry|Read aloud|Edit message)$/i;
const tidy = lines => lines.filter((l, i) => l && !BOILER.test(l) && l !== lines[i - 1]);
const cl = v => { v = String(v || '').trim(); return /^(blank|auto|none|optional|n\/a)\b|press enter|auto-detect/i.test(v) ? '' : v; }; // ignore placeholder text pasted into selector fields
const DEFAULT_SEL = 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"]';
const inputSel = p => cl(p.inputSel) || DEFAULT_SEL;

// ---- warm browser state: reuse sessions, keep chats open, pre-load a spare page ----
const MAX_PAGES = +process.env.MAX_PAGES || 3, CONV_TTL = 15 * 60e3, SPARE_TTL = 5 * 60e3, STABLE_MS = +process.env.STABLE_MS || 2000;
const ctxs = new Map();   // providerId -> { ctx, sig }
const convs = new Map();  // "user:provider:conversation" -> { page, pid, last }
const spares = new Map(); // providerId -> { promise, at }
// saved chats: conversation key -> chat page URL, so a chat survives restarts, idle closes and evictions. server.js plugs in the real (Turso-backed) store.
let store = { get: () => null, set: () => {}, del: () => {} };
const pathOf = u => { try { return new URL(u).pathname.replace(/\/+$/, ''); } catch { return ''; } };
const LOGINISH = /login|signin|sign-in|sign_in|auth/i;
const isChatUrl = (p, u) => { const a = pathOf(u); return !!a && a !== pathOf(p.url) && !LOGINISH.test(a); }; // true once the site moved to a real chat URL
async function settle(page, max = 8000) { // let a reopened chat finish rendering its history before we measure it
  let last = -1, same = 0; const t0 = Date.now();
  while (Date.now() - t0 < max && same < 3) {
    await page.waitForTimeout(300);
    const n = await page.evaluate(() => document.body.innerText.length).catch(() => -1);
    if (n === last && n > 0) same++; else { same = 0; last = n; }
  }
}
async function forget(ckey) { const c = convs.get(ckey); convs.delete(ckey); store.del(ckey); if (c) await c.page.close().catch(() => {}); }
const sigOf = p => crypto.createHash('sha1').update((p.cookies || '') + '|' + (p.ua || '') + '|' + p.url).digest('hex');

async function getBrowser() {
  if (!browser || !browser.isConnected()) {
    ctxs.clear(); convs.clear(); spares.clear();
    browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
    browser.on('disconnected', () => { ctxs.clear(); convs.clear(); spares.clear(); });
  }
  return browser;
}
async function dropProvider(pid) {
  const c = ctxs.get(pid); ctxs.delete(pid); spares.delete(pid);
  for (const [k, v] of convs) if (v.pid === pid) convs.delete(k);
  if (c) await c.ctx.close().catch(() => {});
}
async function getCtx(p, step) {
  const sig = sigOf(p), cur = ctxs.get(p.id);
  if (cur && cur.sig === sig) { step('Warm session reused', true); return cur.ctx; }
  if (cur) await dropProvider(p.id);
  const ctx = await (await getBrowser()).newContext({
    userAgent: p.ua || UA, viewport: { width: 1280, height: 800 },
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
  if (process.env.BLOCK_ASSETS !== '0') await ctx.route('**/*', r => ['image', 'media', 'font'].includes(r.request().resourceType()) ? r.abort() : r.continue()).catch(() => {}); // faster loads on a slow server
  ctx.on('close', () => { if (ctxs.get(p.id) && ctxs.get(p.id).ctx === ctx) ctxs.delete(p.id); });
  ctxs.set(p.id, { ctx, sig });
  return ctx;
}
function evictOne() {
  for (const [pid, s] of spares) { spares.delete(pid); s.promise.then(pg => pg && pg.close().catch(() => {})).catch(() => {}); return true; }
  let old = null; for (const [k, v] of convs) if (!old || v.last < old[1].last) old = [k, v];
  if (old) { convs.delete(old[0]); old[1].page.close().catch(() => {}); return true; }
  return false;
}
const makeRoom = () => { while (convs.size + spares.size >= MAX_PAGES && evictOne()); };
async function openPage(p, ctx, url) {
  const page = await ctx.newPage();
  try { await page.goto(url || p.url, { waitUntil: 'domcontentloaded', timeout: 45000 }); } catch (e) { await page.close().catch(() => {}); throw e; }
  return page;
}
function prewarm(p) { // load the next page in the background so the next new chat starts instantly
  if (spares.has(p.id) || convs.size + spares.size >= MAX_PAGES) return;
  const entry = { at: Date.now() };
  entry.promise = (async () => {
    const page = await openPage(p, await getCtx(p, () => {}));
    try { await page.locator(inputSel(p)).first().waitFor({ state: 'visible', timeout: 25000 }); await page.waitForTimeout(1200); } catch (e) { await page.close().catch(() => {}); throw e; }
    return page;
  })();
  entry.promise.catch(() => { if (spares.get(p.id) === entry) spares.delete(p.id); });
  spares.set(p.id, entry);
}
async function takeSpare(p) {
  const e = spares.get(p.id); if (!e) return null; spares.delete(p.id);
  try { const page = await e.promise; if (page && !page.isClosed()) return page; } catch {}
  return null;
}
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of convs) if (now - v.last > CONV_TTL || v.page.isClosed()) { convs.delete(k); v.page.close().catch(() => {}); }
  for (const [pid, s] of spares) if (now - s.at > SPARE_TTL) { spares.delete(pid); s.promise.then(pg => pg && pg.close().catch(() => {})).catch(() => {}); }
}, 30000).unref();

// one request at a time per provider. `conv` keeps one chat open on the site across requests.
function ask(p, prompt, dbg, conv, info) {
  const prev = queues.get(p.id) || Promise.resolve();
  const run = prev.catch(() => {}).then(() => doAsk(p, prompt, dbg, conv, true, info || {}));
  queues.set(p.id, run);
  return run;
}
const counts = lines => { const m = new Map(); for (const l of lines.map(s => s.trim())) m.set(l, (m.get(l) || 0) + 1); return m; };

async function doAsk(p, prompt, dbg, conv, retry = true, info = {}) {
  const full = typeof prompt === 'string' ? prompt : prompt.full, lastMsg = typeof prompt === 'string' ? prompt : (prompt.last || prompt.full);
  const trace = [], shots = [], T0 = Date.now(), ckey = conv ? `${p.userId}:${p.id}:${conv}` : '';
  const step = (name, ok, detail = '') => trace.push({ step: name, ok, detail: String(detail).slice(0, 300), t: Date.now() - T0 });
  let page, raw = '', selInfo = '', warm = false, restored = false, keep = false, ok = false;
  const shot = async label => { if (!dbg || !page) return; try { shots.push({ label, img: (await page.screenshot({ type: 'jpeg', quality: 45 })).toString('base64') }); } catch {} };
  try {
    const ctx = await getCtx(p, step);
    if (ckey && convs.has(ckey)) { const c = convs.get(ckey); if (!c.page.isClosed()) { page = c.page; warm = true; info.how = 'continued chat'; c.last = Date.now(); step('Continuing the open chat', true, 'no page reload'); } else convs.delete(ckey); }
    if (!page && ckey && store.get(ckey)) { // saved chat from before a restart / idle close: reopen that exact chat instead of starting a new one
      const saved = store.get(ckey); let pg = null;
      makeRoom();
      try { pg = await openPage(p, ctx, saved); } catch (e) { step('Saved chat failed to load', false, e.message.split('\n')[0]); }
      if (pg && pathOf(pg.url()) === pathOf(saved)) { page = pg; warm = restored = true; info.how = 'continued chat (restored)'; step('Reopened the saved chat', true, 'same chat as before'); }
      else if (pg) {
        const now = pg.url(); await pg.close().catch(() => {});
        if (LOGINISH.test(pathOf(now))) throw new Error('Redirected to a login page — session expired. Use "Log in" on the provider to sign in again (the saved chat is kept).');
        store.del(ckey); info.stale = true; step('Saved chat no longer exists — starting a new one', false, now);
      } else throw new Error('Could not open the saved chat (site slow or unreachable) — it is kept, try again');
    }
    if (!page) { page = await takeSpare(p); if (page) { info.how = 'new chat (pre-loaded page)'; step('Used pre-loaded page', true, 'ready instantly'); } }
    if (!page) {
      makeRoom(); page = await openPage(p, ctx); info.how = info.stale ? 'new chat (old one went stale)' : 'new chat (loaded page)';
      step('Page loaded', true, 'final URL: ' + page.url() + ' — title: ' + await page.title().catch(() => ''));
      if (p.cookieList && /login|signin|sign-in|auth/i.test(page.url())) step('Login wall?', false, 'Redirected to a login page — session expired or invalid');
    }
    if (ckey && (!warm || restored)) { convs.set(ckey, { page, pid: p.id, last: Date.now() }); }
    keep = !!ckey;
    const sel = inputSel(p), box = page.locator(sel).first();
    await box.waitFor({ state: 'visible', timeout: 25000 });
    if (restored) await settle(page); else if (!warm) await page.waitForTimeout(800);
    await shot(warm ? '1. Open chat' : '1. Page ready');
    selInfo = await box.evaluate(e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className == 'string' ? '.' + e.className.trim().split(/\s+/).slice(0, 2).join('.') : '') + (e.placeholder ? ' placeholder="' + e.placeholder + '"' : ''));
    step('Text box found', true, `using ${selInfo}`);
    const text = warm ? lastMsg : full;
    let rsel = cl(p.responseSel), hinted = false;
    if (!rsel) { try { rsel = HINTS[new URL(p.url).hostname] || ''; hinted = !!rsel; } catch {} }
    const n0 = rsel ? await page.locator(rsel).count() : 0;
    const before = await page.evaluate(() => document.body.innerText);
    const bc = counts(before.split('\n')), pc = counts(text.split('\n'));
    await box.click(); await page.keyboard.insertText(text); await page.waitForTimeout(250);
    step('Message typed', true, `${text.length} chars` + (warm && text !== full ? ' (conversation continues, history not resent)' : ''));
    await shot('2. Message typed');
    const sendSel = cl(p.sendSel); let sent = false;
    if (sendSel) { try { await page.click(sendSel, { timeout: 4000 }); sent = true; } catch (e) { step('Send button selector unusable — pressing Enter instead', false, e.message.split('\n')[0]); } }
    if (!sent) await page.keyboard.press('Enter');
    step('Message sent', true, sent ? 'clicked ' + sendSel : 'pressed Enter');
    let src = 'auto-detect (new page text)';
    const read = async () => {
      if (rsel) {
        const els = page.locator(rsel), n = await els.count();
        if (n > n0) { src = 'selector ' + rsel; return (await els.nth(n - 1).innerText()).trim(); }
        if (!hinted || n0 > 0) return '';
      }
      src = 'auto-detect (new page text)';
      raw = await page.evaluate(() => document.body.innerText);
      const b = new Map(bc), q = new Map(pc), out = [];
      for (const l of raw.split('\n').map(s => s.trim())) { if (!l) continue; if (b.get(l) > 0) { b.set(l, b.get(l) - 1); continue; } if (q.get(l) > 0) { q.set(l, q.get(l) - 1); continue; } out.push(l); }
      return tidy(out).join('\n');
    };
    let last = '', lastChange = Date.now(), result = ''; const t1 = Date.now();
    while (Date.now() - t1 < 120000) {
      await page.waitForTimeout(400);
      const cur = await read().catch(() => '');
      if (cur !== last) { last = cur; lastChange = Date.now(); }
      else if (cur && Date.now() - lastChange >= STABLE_MS) { result = cur; break; }
    }
    if (!result && last) result = last;
    if (!result) throw new Error('Timed out waiting for reply (no new text appeared)');
    step('Reply detected', true, `${result.length} chars via ${src}, took ${((Date.now() - t1) / 1000).toFixed(1)}s`);
    if (ckey) { // remember which chat this is so it survives restarts / idle closes
      let u = page.url(); if (!warm) for (let i = 0; i < 8 && !isChatUrl(p, u); i++) { await page.waitForTimeout(400); u = page.url(); }
      if (isChatUrl(p, u) && store.get(ckey) !== u) store.set(ckey, u);
    }
    await shot('3. Reply received');
    ok = true;
    return dbg ? { ok: true, text: result, raw: raw.slice(-4000), selector: selInfo, trace, shots } : result;
  } catch (e) {
    if (warm && retry) { // the kept page went stale: drop the page and retry once by reopening the SAME saved chat (a new chat with full history only if none is saved)
      convs.delete(ckey); keep = false; info.stale = true;
      if (page) await page.close().catch(() => {});
      return doAsk(p, prompt, dbg, conv, false, info);
    }
    if (warm && ckey) store.del(ckey); // failed twice on this chat: forget it so the next request starts a fresh one instead of being stuck
    if (page) { const chal = await page.evaluate(() => /verify(ing)? (you are )?human|just a moment|checking your browser|captcha/i.test(document.body.innerText)).catch(() => false);
      if (chal || /__cf_chl|challenge/i.test(page.url())) step('Human verification detected', false, 'Open Live view on this provider, complete the check yourself, then Save session'); }
    step('Failed', false, e.message);
    await shot('Failure state');
    if (ckey) { convs.delete(ckey); keep = false; }
    if (dbg) return { ok: false, error: e.message, raw: raw.slice(-4000), selector: selInfo, trace, shots };
    throw e;
  } finally {
    if (page && !keep) await page.close().catch(() => {});
    if (ok && !conv) prewarm(p);
  }
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
  const opts = { userAgent: p.ua || UA, viewport: { width: LW, height: LH } };
  if (p.cookieList && !Array.isArray(p.cookieList)) opts.storageState = p.cookieList;
  const ctx = await (await getBrowser()).newContext(opts);
  if (Array.isArray(p.cookieList)) await ctx.addCookies(normCookies(p.cookieList, p.url)).catch(() => {});
  const page = await ctx.newPage();
  page.goto(p.url, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  const sid = crypto.randomBytes(8).toString('hex'), s = { ctx, page, owner };
  touch(sid, s); live.set(sid, s); return sid;
}
function liveGet(sid, owner) { const s = live.get(sid); if (!s || s.owner !== owner) throw new Error('Live session ended'); touch(sid, s); return s; }
const LW = 1024, LH = 640;
const CAST = { low: { quality: 35, maxWidth: 720, maxHeight: 450 }, high: { quality: 60, maxWidth: 1024, maxHeight: 640 } };
const clamp = (v, m) => Math.max(0, Math.min(m, +v || 0));
async function liveAttach(sid, owner, ws) {
  const s = liveGet(sid, owner), { page, ctx } = s;
  const send = o => ws.readyState === 1 && ws.send(JSON.stringify(o));
  const cdp = await ctx.newCDPSession(page);
  cdp.on('Page.screencastFrame', f => {
    cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
    if (ws.readyState === 1 && ws.bufferedAmount < 250000) ws.send(Buffer.from(f.data, 'base64')); // drop frames when the link is slow
  });
  let curQ = '';
  const cast = async q => { q = q === 'high' ? 'high' : 'low'; if (q === curQ) return; curQ = q;
    await cdp.send('Page.stopScreencast').catch(() => {});
    await cdp.send('Page.startScreencast', { format: 'jpeg', everyNthFrame: 1, ...CAST[q] }); };
  await cast('low');
  const onNav = fr => { if (fr === page.mainFrame()) send({ t: 'url', url: page.url() }); };
  page.on('framenavigated', onNav); send({ t: 'url', url: page.url() });
  let btn = 0, kq = Promise.resolve();
  const mouse = (type, d, button, buttons, extra) => cdp.send('Input.dispatchMouseEvent', { type, x: clamp(d.x, LW), y: clamp(d.y, LH), button, buttons, ...extra }).catch(() => {});
  ws.on('message', raw => {
    let d; try { d = JSON.parse(raw); } catch { return; }
    touch(sid, s);
    const bn = d.b === 2 ? 'right' : 'left';
    if (d.t === 'move') mouse('mouseMoved', d, btn ? (btn === 2 ? 'right' : 'left') : 'none', btn);
    else if (d.t === 'down') { btn = d.b === 2 ? 2 : 1; mouse('mousePressed', d, bn, btn, { clickCount: 1 }); }
    else if (d.t === 'up') { mouse('mouseReleased', d, bn, 0, { clickCount: 1 }); btn = 0; }
    else if (d.t === 'wheel') mouse('mouseWheel', d, 'none', btn, { deltaX: 0, deltaY: Math.max(-1500, Math.min(1500, +d.dy || 0)) });
    else if (d.t === 'quality') cast(d.q).catch(() => {});
    else kq = kq.then(() => handle(page, d)).catch(() => {});
  });
  ws.on('close', () => { page.off('framenavigated', onNav); cdp.send('Page.stopScreencast').catch(() => {}); cdp.detach().catch(() => {}); });
}
async function handle(page, d) {
  if (d.t === 'text') await page.keyboard.type(String(d.text).slice(0, 2000), { delay: 8 });
  else if (d.t === 'key') await page.keyboard.press(String(d.key).slice(0, 20));
  else if (d.t === 'nav') { let u = String(d.url || '').trim(); if (u && !/^https?:\/\//i.test(u)) u = 'https://' + u; if (u) page.goto(u, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {}); }
  else if (d.t === 'back') await page.goBack({ timeout: 15000 }).catch(() => {});
  else if (d.t === 'reload') page.reload({ timeout: 45000 }).catch(() => {});
}
async function liveSave(sid, owner) { const s = liveGet(sid, owner), st = await s.ctx.storageState(); await liveClose(sid); return st; }
async function liveClose(sid) { const s = live.get(sid); if (!s) return; clearTimeout(s.timer); live.delete(sid); await s.ctx.close().catch(() => {}); }
module.exports = { ask, forget, setStore: s => { store = s; }, liveStart, liveAttach, liveSave, liveClose, liveOwns: (sid, o) => live.get(sid)?.owner === o };

