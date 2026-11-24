'use strict';
const ext = globalThis.browser ?? globalThis.chrome;
const send = (m) => new Promise((res) => ext.runtime.sendMessage(m, (r) => res(r || { error: ext.runtime.lastError?.message })));
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const optionIdentity = (o) => {
  const req = o.req || {};
  const ytdlp = req.ytdlp || {};
  if (o.key != null && o.key !== '') return `key:${o.key}`;
  return [req.kind || '', req.url || '', req.variantUrl || req.hls?.variantUrl || '', ytdlp.vid || ytdlp.formatId || ytdlp.aid || '', ytdlp.audio ? 'audio' : '', req.fileName || ''].join('|');
};
const likelyPlayerFragmentThreshold = (durations) => {
  const values = durations.filter((d) => Number.isFinite(d) && d > 0);
  const longest = Math.max(0, ...values);
  const limit = Math.min(10 * 60, longest * 0.25);
  const shortCount = values.filter((d) => d < limit).length;
  return longest >= 30 * 60 && values.length >= 6 && shortCount >= 4 ? limit : 0;
};

(async () => {
  const q = new URLSearchParams(location.search).get('tab'); // lets tests open the popup as a normal tab
  const tab = q ? await ext.tabs.get(Number(q)) : (await ext.tabs.query({ active: true, currentWindow: true }))[0];
  // everything starts at once: pairing info, app ping and the (network-free) entry list
  const settingsP = send({ type: 'settings' });
  const pingP = send({ type: 'ping' });
  const collectP = send({ type: 'collect', tabId: tab.id });
  if (tab.id != null) send({ type: 'prefetch', tabId: tab.id });
  const settings = await settingsP;
  let alive = true; // assume the app is up; the pill and buttons settle when the ping returns
  $('#conn').textContent = 'Checking…';
  $('#unpaired').hidden = !!settings.paired;
  $('#openOpts').onclick = () => ext.runtime.openOptionsPage();
  $('#focus').onclick = async () => { await send({ type: 'focus' }); window.close(); };
  $('#assets').onclick = () => { ext.tabs.create({ url: ext.runtime.getURL('grabber/grabber.html?tab=' + tab.id) }); window.close(); };
  $('#more').onclick = () => { ext.tabs.create({ url: ext.runtime.getURL('grabber/grabber.html?cat=net&tab=' + tab.id) }); window.close(); };
  $('#cap').checked = !!settings.captureEnabled;
  $('#cap').onchange = (e) => ext.storage.local.set({ captureOff: !e.target.checked });
  pingP.then((ping) => {
    alive = !!ping.ok;
    $('#conn').textContent = alive ? 'Connected' : settings.paired ? 'App not running' : 'Not paired';
    $('#conn').className = 'pill ' + (alive ? 'ok' : 'bad');
    $('#cap').disabled = !alive;
    refresh();
  });

  const list = $('#list');
  const state = new Map(); // entry id -> { entry, card, options, chosen, checked }
  const go = $('#go');

  const refresh = () => {
    const picked = [...state.values()].filter((s) => s.checked && s.chosen != null && s.options?.length);
    go.disabled = !alive || !picked.length;
    go.textContent = picked.length > 1 ? `Download selected (${picked.length})` : 'Download selected';
    $('#selAll').checked = state.size > 0 && [...state.values()].every((s) => s.checked);
    for (const s of state.values()) s.card.classList.toggle('on', !!s.checked);
  };

  const renderOptions = (s) => {
    const box = s.card.querySelector('.body');
    if (s.error) { box.innerHTML = `<div class="err">${esc(s.error)}</div>`; return; }
    // one drop-down per video: "1080p HD · MP4 · 5.04 GB", highest quality first
    box.innerHTML = '<select class="qsel" aria-label="Quality"></select>';
    const sel = box.firstChild;
    s.options.forEach((o, i) => {
      const opt = document.createElement('option');
      opt.value = String(i);
      opt.disabled = !!o.disabled;
      opt.textContent = [o.label, o.detail].filter(Boolean).join('  ·  ') + (o.disabled ? '  (needs ffmpeg)' : '');
      sel.appendChild(opt);
    });
    sel.value = String(s.chosen ?? 0);
    sel.onchange = () => { s.chosen = Number(sel.value); s.checked = true; s.card.querySelector('.head input').checked = true; refresh(); };
  };

  const apply = (s, r, keepChoice) => {
    if (r.error) {
      // the page-level extractor failing (blocked, unsupported) is not an error for the user while the sniffed files are listed
      if (s.entry.kind === 'site' && state.size > 1) { s.card.remove(); state.delete(s.entry.id); refresh(); return; }
      s.error = /403|forbidden/i.test(r.error) ? 'This site blocks automatic extraction. Play the video for a few seconds and its files will appear here, or open More formats to see every response the page received.' : r.error;
      s.options = []; s.card.querySelector('.ttl small').textContent = 'Not available'; renderOptions(s); refresh(); return;
    }
    s.error = '';
    const prev = keepChoice && s.options?.[s.chosen]?.key;
    { const seen = new Set(); s.options = (r.options || []).filter((o) => { const k = optionIdentity(o); if (seen.has(k)) return false; seen.add(k); return true; }); } // remove only duplicate download targets; site formats can share one page URL
    const idx = prev ? s.options.findIndex((o) => o.key === prev) : -1;
    s.chosen = idx >= 0 ? idx : Math.max(0, s.options.findIndex((o) => !o.disabled));
    if (r.title && s.entry.kind !== 'files') s.card.querySelector('.n').textContent = r.title;
    s.card.querySelector('.ttl small').textContent = s.options.length > 1 ? `${s.options.length} qualities` : s.options.length === 1 ? '1 quality' : '';
    renderOptions(s);
    refresh();
  };

  // After every file's duration is known: files of the SAME video in different qualities (different hashed URLs)
  // become one card with a quality dropdown, the longest video first, subtitles last.
  const suppressedSegments = new Set();
  let pending = 0;
  let cTimer = 0;
  const queueConsolidate = () => { clearTimeout(cTimer); cTimer = setTimeout(consolidate, 150); };
  const consolidate = () => {
    const cards = [...state.values()].filter((s) => s.entry.kind === 'files' && s.options.length && s.options[0].duration);
    cards.sort((a, b) => a.options[0].duration - b.options[0].duration);
    let group = [];
    const flush = () => {
      if (group.length > 1) {
        const head = group.reduce((a, b) => ((b.options[0].size || 0) > (a.options[0].size || 0) ? b : a));
        const all = [];
        const seenIds = new Set();
        for (const g of group) for (const o of g.options) {
          const key = optionIdentity(o);
          if (!seenIds.has(key) && !all.some((x) => x.height === o.height && x.height > 0 && Math.abs((x.size || 1) - (o.size || 1)) / Math.max(1, x.size || 1) < 0.01 && !x.req?.ytdlp)) { seenIds.add(key); all.push(o); }
        }
        all.sort((a, b) => b.height - a.height || b.size - a.size);
        head.options = all;
        head.chosen = 0;
        head.checked = group.some((g) => g.checked);
        head.card.querySelector('.head input').checked = head.checked;
        head.card.querySelector('.ttl small').textContent = `${all.length} qualities`;
        for (const g of group) if (g !== head) { suppressedSegments.add(g.entry.id); g.card.remove(); state.delete(g.entry.id); }
        renderOptions(head);
      }
      group = [];
    };
    for (const s of cards) {
      const d = s.options[0].duration;
      if (group.length && Math.abs(d - group[group.length - 1].options[0].duration) > 2) flush();
      group.push(s);
    }
    flush();
    // Some streaming players expose the full movie plus many short MP4 chunks as separate URLs.
    // Keep short clips available in More formats, but don't clutter the main video list when a
    // full-length title and a large batch of fragment-like files are clearly present together.
    if (pending === 0) {
      const files = [...state.values()].filter((s) => s.entry.kind === 'files' && s.options.length && s.options[0].duration > 0);
      const shortLimit = likelyPlayerFragmentThreshold(files.map((s) => s.options[0].duration));
      if (shortLimit) {
        const fragments = files.filter((s) => s.options[0].duration < shortLimit);
        for (const s of fragments) {
          suppressedSegments.add(s.entry.id);
          s.card.remove();
          state.delete(s.entry.id);
        }
      }
    }
    const rank = (s) => (s.entry.kind === 'sub' ? 3 : s.entry.kind === 'site' && s.error ? 2 : 0);
    const ordered = [...state.values()].sort((a, b) => rank(a) - rank(b) || (b.options?.[0]?.duration || 0) - (a.options?.[0]?.duration || 0) || (b.options?.[0]?.size || 0) - (a.options?.[0]?.size || 0));
    let n = 0;
    const numbered = ordered.filter((s) => s.entry.kind === 'files' && /^\d+\. /.test(s.entry.title)).length > 1;
    for (const s of ordered) {
      list.appendChild(s.card);
      if (numbered && s.entry.kind === 'files' && /^\d+\. /.test(s.entry.title)) s.card.querySelector('.n').textContent = `${++n}. ${s.entry.title.replace(/^\d+\. /, '')}`;
    }
    refresh();
  };

  const addCard = (entry, single) => {
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `<div class="head"><input type="checkbox"><div class="thumb"><span class="ph">${entry.kind === 'sub' ? 'CC' : '▶'}</span></div><div class="ttl"><div class="n" title="${esc(entry.url || '')}">${esc(entry.title)}</div><small>${entry.kind === 'sub' ? 'Subtitles' : 'Checking qualities…'}</small></div></div><div class="body"><div class="skel"></div></div>`;
    list.appendChild(card);
    const s = { entry, card, options: [], chosen: null, checked: single };
    card.querySelector('.head input').checked = single;
    card.querySelector('.head input').onchange = (e) => { s.checked = e.target.checked; refresh(); };
    state.set(entry.id, s);
    if (entry.kind !== 'sub') {
      send({ type: 'thumb', tabId: tab.id, entry }).then((t) => {
        if (!t || !t.img) return;
        const im = new Image();
        im.onload = () => { const box = card.querySelector('.thumb'); box.replaceChildren(im); box.classList.add('has'); };
        im.src = t.img;
      });
    }
    // show what is already known at once (cached probe or the sniffed size), richer details follow
    if (entry.pre && entry.pre.options && entry.pre.options.length) { apply(s, { options: entry.pre.options }, !!entry.pre.full); if (entry.pre.full) requestAnimationFrame(() => queueConsolidate()); }
    if (entry.pre && entry.pre.full) return; // the answer was already complete
    send({ type: 'enrich', tabId: tab.id, entry, details: false }).then((r) => {
      apply(s, r, !!(entry.pre && entry.pre.options && entry.pre.options.length));
      if (!r.error && entry.kind === 'files') {
        pending++;
        send({ type: 'enrich', tabId: tab.id, entry, details: true }).then((d) => { if (!d.error) apply(s, d, true); }).finally(() => { pending--; queueConsolidate(); });
      }
    });
  };

  let shown = 0;
  const addNew = (entries) => {
    const fresh = entries.filter((e) => !state.has(e.id) && !suppressedSegments.has(e.id));
    if (fresh.length) list.querySelector('.empty')?.remove();
    fresh.forEach((e) => addCard(e, state.size === 0 && entries.length === 1));
    shown += fresh.length;
    refresh();
  };
  list.innerHTML = '<div class="empty">Looking for videos on this page…</div>';
  addNew((await collectP).entries || []);
  if (!shown) list.innerHTML = `<div class="empty">${alive ? 'Nothing detected yet. Press play on the video, wait a few seconds, then open this popup again.' : 'Start the app and pair it to see downloads.'}</div>`;
  // late finds (the page may still be reporting what it found): poll briefly, quickly at first
  (async () => {
  for (let i = 0, delay = 200; i < 14; i++, delay = Math.min(900, delay + 100)) {
    await new Promise((r) => setTimeout(r, delay));
    addNew((await send({ type: 'collect', tabId: tab.id })).entries || []);
    if (i === 4 && !shown) { const ok = await pingP; if (!ok.ok) break; }
  }
  })();

  $('#selAll').onchange = (e) => {
    for (const s of state.values()) { s.checked = e.target.checked; s.card.querySelector('.head input').checked = e.target.checked; }
    refresh();
  };

  go.onclick = async () => {
    const picked = [...state.values()].filter((s) => s.checked && s.options?.[s.chosen]);
    go.disabled = true;
    go.textContent = 'Sending…';
    let ok = 0, fail = '';
    for (const s of picked) {
      const o = s.options[s.chosen];
      const r = await send({ type: 'download', tabId: tab.id, item: { ...o.req, noAsk: picked.length > 1 } });
      if (r.error) fail = r.error; else ok++;
    }
    go.innerHTML = fail ? 'Failed' : `<span class="done">✓</span> Added ${ok}`;
    go.title = fail;
    setTimeout(refresh, 2200);
  };
})();
