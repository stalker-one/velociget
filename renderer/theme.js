'use strict';
// Light / dark / follow-Windows. The choice lives in localStorage so every window of the app picks it up.
(function () {
  const mq = matchMedia('(prefers-color-scheme: dark)');
  const read = () => { try { return localStorage.getItem('vg-theme') || 'system'; } catch { return 'system'; } };
  const apply = () => {
    const t = read();
    document.documentElement.dataset.theme = t === 'system' ? (mq.matches ? 'dark' : 'light') : t;
  };
  window.setTheme = (t) => { try { localStorage.setItem('vg-theme', t || 'system'); } catch { /* ignore */ } apply(); };
  mq.addEventListener('change', apply);
  addEventListener('storage', apply);
  apply();
})();
