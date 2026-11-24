'use strict';
// Runs at document_start so it can catch page-world playlist events before video playback begins.
(() => {
  const MAX_CHARS = 256 * 1024;
  document.addEventListener('__vg_manifest', (event) => {
    let value;
    try { value = JSON.parse(event.detail); } catch { return; }
    if (!value || typeof value.url !== 'string' || typeof value.body !== 'string' || value.body.length > MAX_CHARS) return;
    if (!/^https?:\/\//i.test(value.url) || !/^\uFEFF?\s*#EXTM3U/.test(value.body.slice(0, 64))) return;
    try {
      const api = globalThis.browser ?? globalThis.chrome;
      const result = api.runtime.sendMessage({ type: 'manifest-body', url: value.url, body: value.body });
      result?.catch?.(() => {});
    } catch { /* extension may be reloaded */ }
  }, true);
})();
