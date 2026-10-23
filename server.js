const express = require('express'), crypto = require('crypto'), fs = require('fs'), path = require('path');
const B = require('./browser'); const ask = B.ask;
const FILE = path.join(__dirname, 'data.json');
let db = { users: [], sessions: {}, keys: [], providers: [], logs: [], chats: {} };
// saved chat per (user:provider:conversation) so chats survive restarts; lives in the same Turso blob as everything else
B.setStore({ get: k => (db.chats[k] || {}).url || null, set: (k, url) => { db.chats[k] = { url, ts: Date.now() }; save(); }, del: k => { if (db.chats[k]) { delete db.chats[k]; save(); } } });
const TURSO_URL = process.env.TURSO_URL || process.env.TURSO_DATABASE_URL, TURSO_TOKEN = process.env.TURSO_TOKEN || process.env.TURSO_AUTH_TOKEN; // same names as the vault project work too
const TURSO = TURSO_URL && TURSO_TOKEN ? require('@libsql/client').createClient({ url: TURSO_URL, authToken: TURSO_TOKEN }) : null;
async function loadDb() {
  if (TURSO) {
    await TURSO.execute('CREATE TABLE IF NOT EXISTS relayapi_kv (k TEXT PRIMARY KEY, v TEXT)');
    const r = await TURSO.execute({ sql: 'SELECT v FROM relayapi_kv WHERE k = ?', args: ['db'] });
    if (r.rows[0]) Object.assign(db, JSON.parse(r.rows[0].v));
    console.log('Storage: Turso (persistent)');
  } else {
    try { Object.assign(db, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch {}
    console.log('Storage: local file — WIPED on every Render restart. Set TURSO_URL + TURSO_TOKEN to persist.');
  }
}
const persist = () => TURSO
  ? TURSO.execute({ sql: 'INSERT OR REPLACE INTO relayapi_kv (k, v) VALUES (?, ?)', args: ['db', JSON.stringify(db)] }).catch(e => console.log('Save failed:', e.message))
  : new Promise(r => fs.writeFile(FILE, JSON.stringify(db), r));
let t; const save = () => { clearTimeout(t); t = setTimeout(persist, 500); };
const flush = async () => { clearTimeout(t); await persist(); };
const id = () => crypto.randomBytes(8).toString('hex');
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const hashPw = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
let KEY; const getKey = () => KEY || (KEY = crypto.createHash('sha256').update(process.env.SECRET_KEY || db.secret).digest());
const enc = t => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', getKey(), iv), d = Buffer.concat([c.update(t, 'utf8'), c.final()]); return [iv, c.getAuthTag(), d].map(b => b.toString('base64')).join('.'); };
const dec = t => { const [iv, tag, d] = t.split('.').map(x => Buffer.from(x, 'base64')), c = crypto.createDecipheriv('aes-256-gcm', getKey(), iv); c.setAuthTag(tag); return Buffer.concat([c.update(d), c.final()]).toString('utf8'); };
const prov = p => { let cookieList = null; try { if (p.cookies) cookieList = JSON.parse(dec(p.cookies)); } catch {} return { ...p, cookieList }; };
const buildState = (raw, storageRaw, url) => {
  let cookies = [], ls = [];
  if (raw && raw.trim()) {
    let j; try { j = JSON.parse(raw); } catch { throw new Error('Cookies must be valid JSON (Cookie-Editor → Export → JSON)'); }
    if (!Array.isArray(j)) throw new Error('Cookies JSON must be a list (starts with [ )');
    const SS = { no_restriction: 'None', none: 'None', lax: 'Lax', strict: 'Strict' };
    cookies = j.filter(c => c && c.name && c.domain).slice(0, 400).map(c => { const sameSite = SS[String(c.sameSite || '').toLowerCase()], e = Number(c.expirationDate || c.expires);
      return { name: String(c.name), value: String(c.value ?? ''), domain: String(c.domain), path: String(c.path || '/'), expires: e > 0 ? Math.floor(e) : -1, httpOnly: !!c.httpOnly, secure: !!c.secure || sameSite === 'None', ...(sameSite ? { sameSite } : {}) }; });
  }
  if (storageRaw && storageRaw.trim()) {
    let o; try { o = JSON.parse(storageRaw); } catch { throw new Error('Local storage must be valid JSON (from copy(JSON.stringify(localStorage)))'); }
    if (!o || typeof o !== 'object' || Array.isArray(o)) throw new Error('Local storage JSON must look like {"key":"value"}');
    ls = Object.entries(o).slice(0, 300).map(([name, v]) => ({ name, value: (typeof v === 'string' ? v : JSON.stringify(v)).slice(0, 20000) }));
  }
  if (!cookies.length && !ls.length) return '';
  let origin = ''; try { origin = new URL(url).origin; } catch {}
  return enc(JSON.stringify({ cookies, origins: ls.length && origin ? [{ origin, localStorage: ls }] : [] }));
};
const app = express();
app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => { res.set('Access-Control-Allow-Origin', '*'); res.set('Access-Control-Expose-Headers', 'X-Relay-Mode'); res.set('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-Conversation-Id'); if (req.method === 'OPTIONS') return res.sendStatus(204); next(); });
app.use(express.static(path.join(__dirname, 'public')));

// ---------- auth ----------
app.post('/api/signup', (req, res) => {
  const { email, password } = req.body || {};
  if (!email || !password || password.length < 6) return res.status(400).json({ error: 'Valid email and 6+ char password required' });
  if (db.users.find(u => u.email === email.toLowerCase())) return res.status(409).json({ error: 'Email already registered' });
  const salt = id(), u = { id: id(), email: email.toLowerCase(), salt, hash: hashPw(password, salt), created: Date.now() };
  db.users.push(u); save(); res.json({ token: session(u.id), email: u.email });
});
const GCID = process.env.GOOGLE_CLIENT_ID || '130548633634-ph56vpk62f83a3djqlr0spik6crc6vf4.apps.googleusercontent.com'; // public client ID, safe to ship
app.get('/api/config', (req, res) => res.json({ googleClientId: GCID, storage: TURSO ? 'turso' : 'file' }));
app.post('/api/google', async (req, res) => {
  if (!GCID) return res.status(400).json({ error: 'Google sign-in is not configured' });
  try {
    const r = await fetch((process.env.GOOGLE_TOKENINFO_URL || 'https://oauth2.googleapis.com/tokeninfo') + '?id_token=' + encodeURIComponent((req.body || {}).credential || ''));
    const g = await r.json();
    if (!r.ok || g.aud !== GCID || !/accounts\.google\.com$/.test(g.iss || '') || String(g.email_verified) !== 'true' || +g.exp < Date.now() / 1000) return res.status(401).json({ error: 'Google sign-in failed' });
    const em = String(g.email).toLowerCase(); let u = db.users.find(x => x.email === em);
    if (!u) { u = { id: id(), email: em, google: true, created: Date.now() }; db.users.push(u); }
    u.name = g.name || u.name; u.picture = g.picture || u.picture; save();
    res.json({ token: session(u.id), email: u.email, name: u.name, picture: u.picture });
  } catch (e) { res.status(502).json({ error: 'Could not reach Google' }); }
});
app.post('/api/login', (req, res) => {
  const { email, password } = req.body || {};
  const u = db.users.find(x => x.email === (email || '').toLowerCase());
  if (u && !u.salt) return res.status(401).json({ error: 'This account uses Google sign-in' });
  if (!u || hashPw(password || '', u.salt) !== u.hash) return res.status(401).json({ error: 'Wrong email or password' });
  res.json({ token: session(u.id), email: u.email });
});
function session(uid) { const tk = crypto.randomBytes(24).toString('hex'); db.sessions[sha(tk)] = uid; save(); return tk; }
function auth(req, res, next) {
  const tk = (req.headers.authorization || '').replace('Bearer ', '');
  const uid = db.sessions[sha(tk)]; if (!uid) return res.status(401).json({ error: 'Not logged in' });
  req.uid = uid; next();
}

// ---------- providers ----------
app.get('/api/providers', auth, (req, res) => res.json(db.providers.filter(p => p.userId === req.uid).map(({ cookies, ...p }) => ({ ...p, hasSession: !!cookies }))));
app.post('/api/providers', auth, (req, res) => {
  const { name, url, cookies } = req.body || {}, cl = v => /^(blank|auto|none|optional|n\/a)\b|press enter|auto-detect/i.test(String(v || '').trim()) ? '' : String(v || '').trim(), inputSel = cl(req.body.inputSel), sendSel = cl(req.body.sendSel), responseSel = cl(req.body.responseSel);
  let ck = ''; try { ck = buildState(cookies, req.body.storage, url); } catch (e) { return res.status(400).json({ error: e.message }); }
  if (!name || !/^https?:\/\//.test(url || '')) return res.status(400).json({ error: 'Name and full URL (https://...) required' });
  const slug = name.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-|-$/g, '');
  if (db.providers.find(p => p.userId === req.uid && p.name === slug)) return res.status(409).json({ error: 'Provider name already used' });
  const p = { id: id(), userId: req.uid, name: slug, url, inputSel: inputSel || '', sendSel: sendSel || '', responseSel: responseSel || '', cookies: ck, created: Date.now() };
  db.providers.push(p); save(); res.json({ id: p.id, name: p.name });
});
app.put('/api/providers/:id/cookies', auth, (req, res) => {
  const p = db.providers.find(p => p.id === req.params.id && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Not found' });
  try { p.cookies = buildState(req.body.cookies, req.body.storage, p.url); } catch (e) { return res.status(400).json({ error: e.message }); }
  p.lastTest = null; save(); res.json({ ok: 1 });
});
app.delete('/api/providers/:id', auth, (req, res) => { db.providers = db.providers.filter(p => !(p.id === req.params.id && p.userId === req.uid)); for (const k of Object.keys(db.chats)) if (k.split(':')[1] === req.params.id) delete db.chats[k]; save(); res.json({ ok: 1 }); });

// ---------- api keys ----------
app.get('/api/keys', auth, (req, res) => res.json(db.keys.filter(k => k.userId === req.uid).map(({ hash, ...k }) => k)));
app.post('/api/keys', auth, (req, res) => {
  const pv = db.providers.find(p => p.id === (req.body || {}).providerId && p.userId === req.uid);
  if (!pv) return res.status(400).json({ error: 'Pick the provider this key is for' });
  const key = 'rk_' + crypto.randomBytes(24).toString('hex');
  const k = { id: id(), userId: req.uid, providerId: pv.id, name: (req.body.name || 'Untitled key').slice(0, 40), hash: sha(key), prefix: key.slice(0, 7) + '…' + key.slice(-4), created: Date.now(), lastUsed: null, requests: 0, revoked: false };
  db.keys.push(k); save(); res.json({ key, id: k.id });
});
app.delete('/api/keys/:id', auth, async (req, res) => {
  const k = db.keys.find(k => k.id === req.params.id && k.userId === req.uid);
  if (!k) return res.json({ ok: 1, chat: 'none' });
  k.revoked = true; save();
  // the key's chat goes with it: delete it on the provider's site (waits up to 20s, then finishes in the background)
  const jobs = db.providers.filter(p => p.userId === k.userId && (!k.providerId || p.id === k.providerId)).map(p => ({ p, ck: `${p.userId}:${p.id}:key-${k.id}` })).filter(x => B.hasChat(x.ck)).map(x => B.deleteChat(x.p, x.ck));
  const all = Promise.all(jobs).then(r => r.includes('deleted') ? 'deleted' : r.includes('kept') ? 'kept' : 'none').catch(() => 'kept');
  const chat = await Promise.race([all, new Promise(r => setTimeout(() => r('pending'), 20000))]);
  res.json({ ok: 1, chat });
});

// ---------- stats / logs ----------
app.get('/api/stats', auth, (req, res) => {
  const logs = db.logs.filter(l => l.userId === req.uid), day = 864e5, now = Date.now();
  const days = Array.from({ length: 7 }, (_, i) => { const s = now - (6 - i) * day; return { d: new Date(s).toISOString().slice(5, 10), n: logs.filter(l => l.ts > s - day / 2 && l.ts <= s + day / 2).length }; });
  const ok = logs.filter(l => l.ok);
  res.json({ total: logs.length, success: logs.length ? Math.round(ok.length / logs.length * 100) : 100, avgMs: ok.length ? Math.round(ok.reduce((a, l) => a + l.ms, 0) / ok.length) : 0, keys: db.keys.filter(k => k.userId === req.uid && !k.revoked).length, providers: db.providers.filter(p => p.userId === req.uid).length, days });
});
app.get('/api/logs', auth, (req, res) => res.json(db.logs.filter(l => l.userId === req.uid).slice(-100).reverse()));

// ---------- gateway ----------
const buckets = {};
const txt = c => Array.isArray(c) ? c.map(x => typeof x === 'string' ? x : (x && x.text) || '').join('\n') : String(c == null ? '' : c);
const toolsNote = tools => (Array.isArray(tools) && tools.length)
  ? '[Tools you can use. To call a tool, reply with ONLY a JSON object like {"tool_calls":[{"name":"TOOL_NAME","arguments":{...}}]} and nothing else. When you do not need a tool, answer normally.]\n' + JSON.stringify(tools.map(t => t.function || t)) + '\n\n'
  : '';
const fmtMsg = m => m.role === 'tool' ? `[tool result${m.name ? ' from ' + m.name : ''}]\n${txt(m.content)}`
  : (m.role === 'assistant' && m.tool_calls ? `assistant: ${txt(m.content)} ${JSON.stringify({ tool_calls: m.tool_calls.map(c => ({ name: c.function && c.function.name, arguments: c.function && c.function.arguments })) })}` : `${m.role}: ${txt(m.content)}`);
function toPrompt(messages, tools) {
  if (!Array.isArray(messages) || !messages.length) return '';
  const n = toolsNote(tools);
  if (messages.length === 1 && !n) return txt(messages[0].content);
  return n + messages.map(fmtMsg).join('\n') + '\nassistant:';
}
// what a chat that is already open on the site still needs: the system prompt/tools when they changed, plus everything after the last assistant reply (user messages AND tool results)
const sysSent = {};
function deltaOf(body, ck) {
  const ms = Array.isArray(body.messages) ? body.messages : [];
  let i = ms.length; while (i > 0 && ms[i - 1].role !== 'assistant') i--;
  const tail = ms.slice(i).filter(m => m.role !== 'system');
  const sys = ms.filter(m => m.role === 'system').map(m => txt(m.content)).join('\n\n'), note = toolsNote(body.tools), h = sha(sys + '|' + note);
  const head = sysSent[ck] !== h && (sys || note) ? (sys ? '[Instructions for the rest of this chat — keep following them]\n' + sys + '\n\n' : '') + note : '';
  const msg = tail.length === 1 && tail[0].role === 'user' ? txt(tail[0].content) : tail.map(fmtMsg).join('\n');
  return { text: head + msg, h };
}
function parseTools(text, tools) {
  if (!Array.isArray(tools) || !tools.length) return null;
  const m = String(text).replace(/```(?:json)?/g, '').match(/\{[\s\S]*"tool_calls"[\s\S]*\}/); if (!m) return null;
  try { const j = JSON.parse(m[0]); if (!Array.isArray(j.tool_calls) || !j.tool_calls.length) return null;
    return j.tool_calls.map(c => ({ id: 'call_' + id(), type: 'function', function: { name: c.name, arguments: typeof c.arguments == 'string' ? c.arguments : JSON.stringify(c.arguments || {}) } })); } catch { return null; }
}
const lastUserOf = ms => String(([...(ms || [])].reverse().find(m => m && m.role === 'user') || {}).content || '');
const convOf = v => String(v || '').replace(/[^\w-]/g, '').slice(0, 64) || undefined;
async function run(userId, keyId, body, res, o = {}) {
  const t0 = Date.now(), prompt = toPrompt(body.messages, body.tools), info = {};
  const p = o.provider || db.providers.find(p => p.userId === userId && p.name === body.model);
  const log = (ok, err) => { db.logs.push({ ts: Date.now(), userId, keyId, provider: p ? p.name : body.model, ms: Date.now() - t0, ok, err: err || '', promptLen: prompt.length, how: info.how || '' }); if (db.logs.length > 1500) db.logs.splice(0, 500); save(); };
  if (!p) return res.status(404).json({ error: { message: `Provider "${body.model}" not found. Add it in the dashboard.` } });
  if (!prompt) return res.status(400).json({ error: { message: 'messages required' } });
  try {
    const cv = o.conv || convOf(body.conversation), ck = `${p.userId}:${p.id}:${cv}`, d = deltaOf(body, ck);
    const text = await ask(prov(p), { full: prompt, last: d.text || lastUserOf(body.messages) }, false, cv, info); log(true); sysSent[ck] = d.h;
    res.set('X-Relay-Mode', info.how || '');
    const tc = parseTools(text, body.tools);
    res.json({ id: 'chatcmpl-' + id(), object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: p.name,
      choices: [{ index: 0, message: tc ? { role: 'assistant', content: null, tool_calls: tc } : { role: 'assistant', content: text }, finish_reason: tc ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } });
  } catch (e) { log(false, e.message); res.status(502).json({ error: { message: 'Provider failed: ' + e.message } }); }
}
app.post('/v1/chat/completions', (req, res) => {
  const key = db.keys.find(k => k.hash === sha((req.headers.authorization || '').replace('Bearer ', '')) && !k.revoked);
  if (!key) return res.status(401).json({ error: { message: 'Invalid API key' } });
  const m = Math.floor(Date.now() / 6e4), b = buckets[key.id] || (buckets[key.id] = { m, n: 0 });
  if (b.m !== m) { b.m = m; b.n = 0; } if (++b.n > 20) return res.status(429).json({ error: { message: 'Rate limit: 20 requests/min' } });
  key.lastUsed = Date.now(); key.requests++;
  const bound = key.providerId ? db.providers.find(p => p.id === key.providerId && p.userId === key.userId) : null;
  if (key.providerId && !bound) return res.status(404).json({ error: { message: 'The provider this key belongs to was deleted. Revoke this key and create a new one.' } });
  run(key.userId, key.id, req.body || {}, res, { provider: bound || undefined, conv: 'key-' + key.id }); // one persistent chat per key; "model" and any conversation id from the client are ignored
});
app.get('/v1/models', (req, res) => {
  const key = db.keys.find(k => k.hash === sha((req.headers.authorization || '').replace('Bearer ', '')) && !k.revoked);
  if (!key) return res.status(401).json({ error: { message: 'Invalid API key' } });
  res.json({ object: 'list', data: db.providers.filter(p => p.userId === key.userId && (!key.providerId || p.id === key.providerId)).map(p => ({ id: p.name, object: 'model' })) });
});
app.post('/api/providers/:id/test', auth, async (req, res) => {
  const p = db.providers.find(p => p.id === req.params.id && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Not found' });
  const t0 = Date.now(), r = await ask(prov(p), 'Reply with only the word: PONG', true);
  r.pass = !!(r.ok && /pong/i.test(r.text)); r.ms = Date.now() - t0; p.lastTest = { ok: r.pass, ts: Date.now() };
  db.logs.push({ ts: Date.now(), userId: req.uid, keyId: 'test', provider: p.name, ms: r.ms, ok: r.ok, err: r.error || '', promptLen: 29 }); save();
  res.json(r);
});
app.post('/api/playground', auth, async (req, res) => {
  if (!req.body.debug) return run(req.uid, 'playground', req.body || {}, res, { conv: 'playground' });
  const p = db.providers.find(p => p.userId === req.uid && p.name === req.body.model);
  if (!p) return res.status(404).json({ error: 'Provider not found' });
  res.json(await ask(prov(p), { full: toPrompt(req.body.messages), last: lastUserOf(req.body.messages) }, true, 'playground'));
});
app.post('/api/playground/reset', auth, async (req, res) => { // "New chat" button: the only way the playground chat of a provider is ever replaced
  const p = db.providers.find(p => p.userId === req.uid && p.name === (req.body || {}).model);
  if (!p) return res.status(404).json({ error: 'Provider not found' });
  await B.forget(`${p.userId}:${p.id}:playground`); delete sysSent[`${p.userId}:${p.id}:playground`]; res.json({ ok: 1 });
});
app.post('/api/providers/:id/live', auth, async (req, res) => {
  const p = db.providers.find(p => p.id === req.params.id && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Not found' });
  try { res.json({ sid: await B.liveStart(prov(p), req.uid) }); } catch (e) { res.status(500).json({ error: e.message }); }
});
app.post('/api/live/:sid/save', auth, async (req, res) => {
  const p = db.providers.find(p => p.id === (req.body || {}).pid && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Provider not found' });
  try { const st = await B.liveSave(req.params.sid, req.uid); p.cookies = enc(JSON.stringify(st)); p.lastTest = null; save(); res.json({ ok: 1, cookies: st.cookies.length, info: B.sessionInfo(st), url: st.url }); } catch (e) { res.status(400).json({ error: e.message }); }
});
app.delete('/api/live/:sid', auth, async (req, res) => { if (B.liveOwns(req.params.sid, req.uid)) await B.liveClose(req.params.sid); res.json({ ok: 1 }); });
app.get('/health', (_, res) => res.send('ok'));
const server = require('http').createServer(app), wss = new (require('ws').WebSocketServer)({ noServer: true });
server.on('upgrade', (req, sock, head) => {
  const u = new URL(req.url, 'http://x'), sid = u.searchParams.get('sid'), uid = db.sessions[sha(u.searchParams.get('t') || '')];
  if (u.pathname !== '/live' || !uid || !B.liveOwns(sid, uid)) return sock.destroy();
  wss.handleUpgrade(req, sock, head, ws => B.liveAttach(sid, uid, ws).catch(() => ws.close()));
});
loadDb().then(() => {
  if (!db.secret) { db.secret = crypto.randomBytes(32).toString('hex'); save(); }
  server.listen(process.env.PORT || 10000, () => console.log('RelayAPI up'));
}).catch(e => { console.error('FATAL: could not load storage —', e.message); process.exit(1); });
process.on('SIGTERM', async () => { await flush(); process.exit(0); });
