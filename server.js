const express = require('express'), crypto = require('crypto'), fs = require('fs'), path = require('path');
const { ask } = require('./browser');
const FILE = path.join(__dirname, 'data.json');
let db = { users: [], sessions: {}, keys: [], providers: [], logs: [] };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch {}
let t; const save = () => { clearTimeout(t); t = setTimeout(() => fs.writeFile(FILE, JSON.stringify(db), () => {}), 300); };
const id = () => crypto.randomBytes(8).toString('hex');
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const hashPw = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
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
app.get('/api/providers', auth, (req, res) => res.json(db.providers.filter(p => p.userId === req.uid)));
app.post('/api/providers', auth, (req, res) => {
  const { name, url, inputSel, sendSel, responseSel } = req.body || {};
  if (!name || !/^https?:\/\//.test(url || '')) return res.status(400).json({ error: 'Name and full URL (https://...) required' });
  const slug = name.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-|-$/g, '');
  if (db.providers.find(p => p.userId === req.uid && p.name === slug)) return res.status(409).json({ error: 'Provider name already used' });
  const p = { id: id(), userId: req.uid, name: slug, url, inputSel: inputSel || '', sendSel: sendSel || '', responseSel: responseSel || '', created: Date.now() };
  db.providers.push(p); save(); res.json(p);
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
    const text = await ask(p, prompt); log(true);
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
app.post('/api/playground', auth, (req, res) => run(req.uid, 'playground', req.body || {}, res));
app.get('/health', (_, res) => res.send('ok'));
app.listen(process.env.PORT || 10000, () => console.log('RelayAPI up'));
