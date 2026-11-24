'use strict';
const ext = globalThis.browser ?? globalThis.chrome;
const msg = (t, ok) => { const m = document.getElementById('msg'); m.textContent = t; m.className = ok ? 'ok' : 'bad'; };
ext.storage.local.get(['port', 'token']).then((s) => { if (s.token) document.getElementById('code').placeholder = `Paired on port ${s.port}. Paste a new code to re-pair.`; });
document.getElementById('save').onclick = async () => {
  const m = /^(\d{2,5}):([0-9a-f]{16,})$/i.exec(document.getElementById('code').value.trim());
  if (!m) return msg('That does not look like a pairing code (expected port:token).', false);
  await ext.storage.local.set({ port: Number(m[1]), token: m[2] });
  ext.runtime.sendMessage({ type: 'ping' }, (r) => msg(r && r.ok ? 'Paired and connected.' : 'Saved, but the app did not answer. Is IDM By Encodal running?', !!(r && r.ok)));
};
