// Runs on your RelayAPI site only (needs the relayapi-app meta tag). Bridges the page <-> extension.
if (document.querySelector('meta[name="relayapi-app"]')) {
  const say = o => window.postMessage({ relayapi: o.relayapi, ...o }, '*');
  window.addEventListener('message', e => {
    const d = e.data;
    if (e.source !== window || !d || typeof d.relayapi !== 'string') return;
    if (d.relayapi === 'ping') say({ relayapi: 'ready' });
    if (d.relayapi === 'connect')
      chrome.runtime.sendMessage({ type: 'connect', url: d.url, pid: d.pid, token: d.token, appOrigin: location.origin }, () => {});
  });
  chrome.runtime.onMessage.addListener(m => { if (m.type === 'done') say({ relayapi: 'done', ok: m.ok, error: m.error, pid: m.pid }); });
  say({ relayapi: 'ready' });
}
