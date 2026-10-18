const K = id => 'p' + id;
const get = async id => (await chrome.storage.session.get(K(id)))[K(id)];

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (m.type === 'connect' && sender.tab) {
    chrome.tabs.create({ url: m.url, active: true }).then(async t => {
      await chrome.storage.session.set({ [K(t.id)]: { pid: m.pid, token: m.token, appOrigin: m.appOrigin, appTabId: sender.tab.id, appWinId: sender.tab.windowId } });
      reply({ ok: true });
    });
    return true;
  }
  if (m.type === 'finish' && sender.tab) { finish(sender.tab.id).then(reply); return true; }
});
chrome.action.onClicked.addListener(tab => finish(tab.id)); // toolbar icon also works
chrome.tabs.onRemoved.addListener(id => chrome.storage.session.remove(K(id)));
chrome.tabs.onUpdated.addListener(async (id, info) => { if (info.status === 'complete' && await get(id)) banner(id); });

function banner(tabId) {
  chrome.scripting.executeScript({ target: { tabId }, func: () => {
    if (document.getElementById('relayapi-banner')) return;
    const b = document.createElement('div'); b.id = 'relayapi-banner';
    b.style.cssText = 'position:fixed;bottom:18px;left:50%;transform:translateX(-50%);z-index:2147483647;background:#12151e;color:#eef1f8;border:1px solid #6366f1;border-radius:14px;padding:10px 12px 10px 16px;font:14px system-ui;display:flex;gap:12px;align-items:center;box-shadow:0 10px 40px #000a';
    b.innerHTML = '<span>⚡ <b>RelayAPI</b> — log in / pass the check, then:</span>';
    const btn = document.createElement('button'); btn.textContent = '✔ Done — send to RelayAPI';
    btn.style.cssText = 'background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;border:0;border-radius:9px;padding:8px 14px;font-weight:600;cursor:pointer';
    btn.onclick = () => { btn.textContent = 'Sending…'; btn.disabled = true; chrome.runtime.sendMessage({ type: 'finish' }, r => { if (!r || !r.ok) { btn.disabled = false; btn.textContent = '✘ ' + ((r && r.error) || 'Failed') + ' — retry'; } }); };
    b.appendChild(btn); document.documentElement.appendChild(b);
  } }).catch(() => {});
}

async function finish(tabId) {
  const o = await get(tabId); if (!o) return { ok: false, error: 'No pending connection' };
  try {
    const tab = await chrome.tabs.get(tabId), host = new URL(tab.url).hostname, base = host.split('.').slice(-2).join('.');
    const [a, b] = await Promise.all([chrome.cookies.getAll({ url: tab.url }), chrome.cookies.getAll({ domain: base })]);
    const map = new Map(); [...a, ...b].forEach(c => map.set(c.domain + '|' + c.path + '|' + c.name, c));
    const ss = { no_restriction: 'None', lax: 'Lax', strict: 'Strict' };
    const cookies = [...map.values()].map(c => ({ name: c.name, value: c.value, domain: c.domain, path: c.path, expires: c.expirationDate ? Math.floor(c.expirationDate) : -1, httpOnly: c.httpOnly, secure: c.secure, sameSite: ss[c.sameSite] || 'Lax' }));
    const [{ result }] = await chrome.scripting.executeScript({ target: { tabId }, func: () => ({ ls: Object.entries(localStorage), ua: navigator.userAgent, origin: location.origin }) });
    const state = { cookies, origins: [{ origin: result.origin, localStorage: result.ls.map(([name, value]) => ({ name, value })) }] };
    const r = await fetch(o.appOrigin + '/api/providers/' + o.pid + '/session', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + o.token }, body: JSON.stringify({ state, ua: result.ua }) });
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || 'Server error ' + r.status);
    await chrome.storage.session.remove(K(tabId));
    chrome.tabs.remove(tabId).catch(() => {});
    chrome.tabs.update(o.appTabId, { active: true }).catch(() => {});
    chrome.windows.update(o.appWinId, { focused: true }).catch(() => {});
    chrome.tabs.sendMessage(o.appTabId, { type: 'done', ok: true, pid: o.pid }).catch(() => {});
    return { ok: true };
  } catch (e) {
    chrome.tabs.sendMessage(o.appTabId, { type: 'done', ok: false, error: e.message, pid: o.pid }).catch(() => {});
    return { ok: false, error: e.message };
  }
}
