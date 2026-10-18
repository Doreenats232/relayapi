// RelayAPI PC Agent — runs the AI-website automation in YOUR real Chrome (home IP, visible window).
// First run:  node agent.js --server https://YOUR-APP.onrender.com --token YOUR_TOKEN   (saved afterwards)
const { chromium } = require('playwright-core'), WebSocket = require('ws'), path = require('path'), os = require('os'), fs = require('fs');
const CFG = path.join(os.homedir(), '.relayapi-agent.json'), PROFILE = path.join(os.homedir(), '.relayapi-chrome-profile');
const a = process.argv.slice(2), arg = k => { const i = a.indexOf('--' + k); return i >= 0 ? a[i + 1] : undefined; };
let cfg = {}; try { cfg = JSON.parse(fs.readFileSync(CFG, 'utf8')); } catch {}
const SERVER = (arg('server') || cfg.server || '').replace(/\/$/, ''), TOKEN = arg('token') || cfg.token;
if (!SERVER || !TOKEN) { console.log('Usage: node agent.js --server https://your-app.onrender.com --token YOUR_TOKEN'); process.exit(1); }
fs.writeFileSync(CFG, JSON.stringify({ server: SERVER, token: TOKEN }));

let ctx = null;
async function getCtx() {
  if (ctx) return ctx;
  ctx = await chromium.launchPersistentContext(PROFILE, { channel: 'chrome', headless: false, viewport: null });
  ctx.on('close', () => { ctx = null; });
  return ctx;
}
async function run(job) {
  const trace = [], shots = [], T0 = Date.now(); let page, raw = '', selInfo = '';
  const step = (n, ok, d = '') => { trace.push({ step: n, ok, detail: String(d).slice(0, 300), t: Date.now() - T0 }); console.log((ok ? '  ✔ ' : '  ✘ ') + n + (d ? ' — ' + String(d).slice(0, 120) : '')); };
  const shot = async l => { if (!job.dbg || !page) return; try { shots.push({ label: l, img: (await page.screenshot({ type: 'jpeg', quality: 45 })).toString('base64') }); } catch {} };
  try {
    page = await (await getCtx()).newPage();
    await page.goto(job.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    step('Page loaded', true, 'final URL: ' + page.url());
    const sel = job.inputSel || 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"]', box = page.locator(sel).first();
    const chal = () => page.evaluate(() => /verify(ing)? (you are )?human|just a moment|checking your browser|captcha|puzzle|slider|verification/i.test(document.body.innerText)).catch(() => false);
    const t0 = Date.now(); let warned = false;
    while (!(await box.isVisible().catch(() => false))) { // waits up to 3 min so YOU can pass any check in the Chrome window
      if (Date.now() - t0 > 180000) throw new Error('Text box never appeared — check/login not completed within 3 minutes');
      if (!warned && Date.now() - t0 > 4000 && await chal()) { warned = true; step('Human check — complete it in the Chrome window', true, 'waiting up to 3 min'); await shot('Human check'); }
      await page.waitForTimeout(1000);
    }
    await page.waitForTimeout(800); await shot('1. Page ready');
    selInfo = await box.evaluate(e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.placeholder ? ' placeholder="' + e.placeholder + '"' : '')).catch(() => '');
    step('Text box found', true, selInfo);
    const before = await page.evaluate(() => document.body.innerText);
    await box.click(); await page.keyboard.insertText(job.prompt); await page.waitForTimeout(400);
    step('Message typed', true, job.prompt.length + ' chars'); await shot('2. Message typed');
    if (job.sendSel) await page.click(job.sendSel); else await page.keyboard.press('Enter');
    step('Message sent', true);
    const bl = new Set(before.split('\n').map(s => s.trim())), pl = new Set(job.prompt.split('\n').map(s => s.trim()));
    const read = async () => {
      if (job.responseSel) { const els = page.locator(job.responseSel), n = await els.count(); return n ? (await els.nth(n - 1).innerText()).trim() : ''; }
      raw = await page.evaluate(() => document.body.innerText);
      return raw.split('\n').map(s => s.trim()).filter(l => l && !bl.has(l) && !pl.has(l)).join('\n');
    };
    let last = '', stable = 0, result = ''; const t1 = Date.now();
    while (Date.now() - t1 < 120000) {
      await page.waitForTimeout(1000); const cur = await read().catch(() => '');
      if (cur && cur === last) { if (++stable >= 4) { result = cur; break; } } else { stable = 0; last = cur; }
    }
    result = result || last; if (!result) throw new Error('Timed out waiting for reply');
    step('Reply detected', true, result.length + ' chars'); await shot('3. Reply received');
    return { ok: true, text: result, raw: raw.slice(-4000), selector: selInfo, trace, shots };
  } catch (e) { step('Failed', false, e.message); await shot('Failure state'); return { ok: false, error: e.message, raw: raw.slice(-4000), selector: selInfo, trace, shots };
  } finally { if (page) await page.close().catch(() => {}); }
}
let chain = Promise.resolve();
function connect() {
  const ws = new WebSocket(SERVER.replace(/^http/, 'ws') + '/agent?token=' + encodeURIComponent(TOKEN));
  ws.on('open', () => console.log('✔ Connected to ' + SERVER + ' — leave this window open.'));
  ws.on('message', raw => { let d; try { d = JSON.parse(raw); } catch { return; }
    if (d.t !== 'job') return;
    chain = chain.then(async () => { console.log('Job: ' + d.url); const r = await run(d); ws.readyState === 1 && ws.send(JSON.stringify({ t: 'result', id: d.id, ...r })); }); });
  ws.on('close', c => { console.log('Disconnected' + (c === 1006 ? '' : ' (' + c + ')') + ' — retrying in 3s'); setTimeout(connect, 3000); });
  ws.on('error', e => console.log('Connection error: ' + e.message));
}
connect();
