const express = require('express'), crypto = require('crypto'), fs = require('fs'), path = require('path');
const B = require('./browser'); const ask = B.ask;
const FILE = path.join(__dirname, 'data.json');
let db = { users: [], sessions: {}, keys: [], providers: [], logs: [] };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch {}
let t; const save = () => { clearTimeout(t); t = setTimeout(() => fs.writeFile(FILE, JSON.stringify(db), () => {}), 300); };
const id = () => crypto.randomBytes(8).toString('hex');
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const hashPw = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
if (!db.secret) { db.secret = crypto.randomBytes(32).toString('hex'); save(); }
const KEY = crypto.createHash('sha256').update(process.env.SECRET_KEY || db.secret).digest();
const enc = t => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', KEY, iv), d = Buffer.concat([c.update(t, 'utf8'), c.final()]); return [iv, c.getAuthTag(), d].map(b => b.toString('base64')).join('.'); };
const dec = t => { const [iv, tag, d] = t.split('.').map(x => Buffer.from(x, 'base64')), c = crypto.createDecipheriv('aes-256-gcm', KEY, iv); c.setAuthTag(tag); return Buffer.concat([c.update(d), c.final()]).toString('utf8'); };
const prov = p => { let cookieList = null; try { if (p.cookies) cookieList = JSON.parse(dec(p.cookies)); } catch {} return { ...p, cookieList }; };
const parseCookies = raw => { if (!raw || !raw.trim()) return ''; let j; try { j = JSON.parse(raw); } catch { throw new Error('Cookies must be valid JSON (export with Cookie-Editor)'); } if (!Array.isArray(j) || !j.length) throw new Error('Cookies JSON must be a non-empty array'); return enc(JSON.stringify(j)); };
const app = express();
app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => { res.set('Access-Control-Allow-Origin', '*'); res.set('Access-Control-Allow-Headers', 'Authorization,Content-Type'); if (req.method === 'OPTIONS') return res.sendStatus(204); next(); });
app.use(express.static(path.join(__dirname, 'public')));

// ---------- auth ----------
app.post('/api/signup', (req, res) => {
  const { email, password } = req.body || {};
  if (!email || !password || password.length < 6) return res.status(400).json({ error: 'Valid email and 6+ char password required' });
  if (db.users.find(u => u.email === email.toLowerCase())) return res.status(409).json({ error: 'Email already registered' });
  const salt = id(), u = { id: id(), email: email.toLowerCase(), salt, hash: hashPw(password, salt), created: Date.now() };
  db.users.push(u); save(); res.json({ token: session(u.id), email: u.email });
});
app.post('/api/login', (req, res) => {
  const { email, password } = req.body || {};
  const u = db.users.find(x => x.email === (email || '').toLowerCase());
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
  const { name, url, inputSel, sendSel, responseSel, cookies } = req.body || {};
  let ck = ''; try { ck = parseCookies(cookies); } catch (e) { return res.status(400).json({ error: e.message }); }
  if (!name || !/^https?:\/\//.test(url || '')) return res.status(400).json({ error: 'Name and full URL (https://...) required' });
  const slug = name.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-|-$/g, '');
  if (db.providers.find(p => p.userId === req.uid && p.name === slug)) return res.status(409).json({ error: 'Provider name already used' });
  const p = { id: id(), userId: req.uid, name: slug, url, inputSel: inputSel || '', sendSel: sendSel || '', responseSel: responseSel || '', cookies: ck, created: Date.now() };
  db.providers.push(p); save(); res.json({ id: p.id, name: p.name });
});
app.put('/api/providers/:id/cookies', auth, (req, res) => {
  const p = db.providers.find(p => p.id === req.params.id && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Not found' });
  try { p.cookies = parseCookies(req.body.cookies); } catch (e) { return res.status(400).json({ error: e.message }); }
  p.lastTest = null; save(); res.json({ ok: 1 });
});
app.delete('/api/providers/:id', auth, (req, res) => { db.providers = db.providers.filter(p => !(p.id === req.params.id && p.userId === req.uid)); save(); res.json({ ok: 1 }); });

// ---------- api keys ----------
app.get('/api/keys', auth, (req, res) => res.json(db.keys.filter(k => k.userId === req.uid).map(({ hash, ...k }) => k)));
app.post('/api/keys', auth, (req, res) => {
  const key = 'rk_' + crypto.randomBytes(24).toString('hex');
  const k = { id: id(), userId: req.uid, name: (req.body.name || 'Untitled key').slice(0, 40), hash: sha(key), prefix: key.slice(0, 7) + '…' + key.slice(-4), created: Date.now(), lastUsed: null, requests: 0, revoked: false };
  db.keys.push(k); save(); res.json({ key, id: k.id });
});
app.delete('/api/keys/:id', auth, (req, res) => { const k = db.keys.find(k => k.id === req.params.id && k.userId === req.uid); if (k) k.revoked = true; save(); res.json({ ok: 1 }); });

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
function toPrompt(messages) {
  if (!Array.isArray(messages) || !messages.length) return '';
  if (messages.length === 1) return String(messages[0].content);
  return messages.map(m => `${m.role}: ${m.content}`).join('\n') + '\nassistant:';
}
async function run(userId, keyId, body, res) {
  const t0 = Date.now(), prompt = toPrompt(body.messages);
  const p = db.providers.find(p => p.userId === userId && p.name === body.model);
  const log = (ok, err) => { db.logs.push({ ts: Date.now(), userId, keyId, provider: body.model, ms: Date.now() - t0, ok, err: err || '', promptLen: prompt.length }); if (db.logs.length > 5000) db.logs.splice(0, 1000); save(); };
  if (!p) return res.status(404).json({ error: { message: `Provider "${body.model}" not found. Add it in the dashboard.` } });
  if (!prompt) return res.status(400).json({ error: { message: 'messages required' } });
  try {
    const text = await ask(prov(p), prompt); log(true);
    res.json({ id: 'chatcmpl-' + id(), object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: p.name,
      choices: [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: 'stop' }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } });
  } catch (e) { log(false, e.message); res.status(502).json({ error: { message: 'Provider failed: ' + e.message } }); }
}
app.post('/v1/chat/completions', (req, res) => {
  const key = db.keys.find(k => k.hash === sha((req.headers.authorization || '').replace('Bearer ', '')) && !k.revoked);
  if (!key) return res.status(401).json({ error: { message: 'Invalid API key' } });
  const m = Math.floor(Date.now() / 6e4), b = buckets[key.id] || (buckets[key.id] = { m, n: 0 });
  if (b.m !== m) { b.m = m; b.n = 0; } if (++b.n > 20) return res.status(429).json({ error: { message: 'Rate limit: 20 requests/min' } });
  key.lastUsed = Date.now(); key.requests++;
  run(key.userId, key.id, req.body || {}, res);
});
app.get('/v1/models', (req, res) => {
  const key = db.keys.find(k => k.hash === sha((req.headers.authorization || '').replace('Bearer ', '')) && !k.revoked);
  if (!key) return res.status(401).json({ error: { message: 'Invalid API key' } });
  res.json({ object: 'list', data: db.providers.filter(p => p.userId === key.userId).map(p => ({ id: p.name, object: 'model' })) });
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
  if (!req.body.debug) return run(req.uid, 'playground', req.body || {}, res);
  const p = db.providers.find(p => p.userId === req.uid && p.name === req.body.model);
  if (!p) return res.status(404).json({ error: 'Provider not found' });
  res.json(await ask(prov(p), toPrompt(req.body.messages), true));
});
app.post('/api/providers/:id/live', auth, async (req, res) => {
  const p = db.providers.find(p => p.id === req.params.id && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Not found' });
  try { res.json({ sid: await B.liveStart(prov(p), req.uid) }); } catch (e) { res.status(500).json({ error: e.message }); }
});
app.get('/api/live/:sid/frame', auth, async (req, res) => {
  try { res.set('Content-Type', 'image/jpeg').send(await B.liveFrame(req.params.sid, req.uid)); } catch (e) { res.status(404).json({ error: e.message }); }
});
app.post('/api/live/:sid/input', auth, async (req, res) => {
  try { await B.liveInput(req.params.sid, req.uid, req.body || {}); res.json({ ok: 1 }); } catch (e) { res.status(400).json({ error: e.message }); }
});
app.post('/api/live/:sid/save', auth, async (req, res) => {
  const p = db.providers.find(p => p.id === (req.body || {}).pid && p.userId === req.uid);
  if (!p) return res.status(404).json({ error: 'Provider not found' });
  try { const st = await B.liveSave(req.params.sid, req.uid); p.cookies = enc(JSON.stringify(st)); p.lastTest = null; save(); res.json({ ok: 1, cookies: st.cookies.length }); } catch (e) { res.status(400).json({ error: e.message }); }
});
app.delete('/api/live/:sid', auth, async (req, res) => { if (B.liveOwns(req.params.sid, req.uid)) await B.liveClose(req.params.sid); res.json({ ok: 1 }); });
app.get('/health', (_, res) => res.send('ok'));
app.listen(process.env.PORT || 10000, () => console.log('RelayAPI up'));
