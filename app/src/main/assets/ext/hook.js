// Media Grabber - page hook (runs inside the page / player iframe itself, world: MAIN).
// Sees every fetch / XHR the player makes, including playlists with a fake extension or a wrong content-type,
// and requests the browser's webRequest API cannot attribute to a tab (workers). Reports to content.js via postMessage.
(() => {
  if (window.__mgHook) return;
  window.__mgHook = 1;
  const seen = new Set(), dirs = {};
  let probes = 0;
  const kindOf = (u) => {
    const m = /\.(m3u8|mpd|mp4|m4v|webm|mkv|vtt|srt|ass|ssa|ttml|dfxp)(?:[?#]|$)/i.exec(String(u).split('?')[0] + (/\?/.test(u) ? '?' : ''));
    if (!m) return '';
    const e = m[1].toLowerCase();
    return e === 'm3u8' ? 'hls' : e === 'mpd' ? 'dash' : /^(vtt|srt|ass|ssa|ttml|dfxp)$/.test(e) ? 'subtitle' : 'video';
  };
  const emit = (url, kind) => {
    try {
      const u = new URL(url, location.href).href;
      if (!/^https?:/i.test(u)) return;
      const k = kind + '|' + u;
      if (seen.has(k)) return;
      seen.add(k);
      window.postMessage({ __mg: 1, url: u, kind }, '*');
    } catch (e) { /* ignore */ }
  };
  // V79: the playlist text the player itself received (the CDN often refuses a second request from the extension, so the quality list could not be read)
  const sendText = (url, t) => {
    try {
      t = String(t || '').replace(/^\uFEFF/, '');
      if (!/^\s*(#EXTM3U|<\?xml[^>]*>\s*<MPD|<MPD)/i.test(t.slice(0, 200)) || t.length > 600000) return;
      const u = new URL(url, location.href).href;
      if (/^https?:/i.test(u)) window.postMessage({ __mg: 1, kind: 'pltext', url: u, text: t }, '*');
    } catch (e) { /* ignore */ }
  };
  const fromText = (url, t) => {
    t = String(t || '').replace(/^\uFEFF/, '').trimStart();
    if (/^#EXTM3U/.test(t)) emit(url, 'hls');
    else if (/^(<\?xml[^>]*>\s*)?<MPD[\s>]/i.test(t)) emit(url, 'dash');
    else if (/^WEBVTT/.test(t)) emit(url, 'subtitle');
  };
  const worthProbing = (url, ct, len) => {
    if (probes >= 120) return false;
    if (/(video|audio|image|font|javascript|css|html|json|wasm)/i.test(ct || '')) return false;
    if (len > 2 * 1024 * 1024) return false;
    let dir = url;
    try { const x = new URL(url, location.href); dir = x.origin + x.pathname.replace(/[^/]*$/, ''); } catch (e) { /* keep */ }
    if ((dirs[dir] || 0) >= 3) return false; // do not read hundreds of segments
    dirs[dir] = (dirs[dir] || 0) + 1; probes++;
    return true;
  };

  // ----- episode lists: single-page sites (kaa.lt ...) often draw no episode links; the list comes from the site's own API.
  // An answer counts when (a) the request is about THIS show (its name is in the path), or (b) it contains the episode you are on
  // plus at least 2 more. Entries that name another show are ignored, so "latest episodes" feeds do not leak in.
  const pathNow = () => location.pathname.split('/').filter(Boolean);
  const showNow = () => { const p = pathNow(); return p.length === 2 && /^(?:ep|episode)[-_]?\d/i.test(p[1]) ? p[0] : ''; };
  const SLUG_IN = /(?:^|\/)((?:ep|episode)[-_]?(\d+(?:\.\d+)?)[-_][a-z0-9]{3,10})\/?$/i;
  const epStore = { show: '', langs: new Map(), asked: new Set(), active: 0 }; // langs: lang -> Map(episode number -> entry); a show can have a different code per language // kept here: the page loads its list BEFORE the extension's content script is ready, which asks for it later
  const inv = [];                               // what the site asked for (path only): shown in the Diagnose report
  let invT = 0, epProbes = 0;
  const postInv = () => { try { window.postMessage({ __mg: 1, kind: 'xhrlog', hook: 2, rows: inv.slice() }, '*'); } catch (e) { /* ignore */ } };
  const logReq = (url, ct, len, eps, why) => {
    try {
      let p = ''; try { const u = new URL(url, location.href); p = u.host + u.pathname; } catch (e) { return; }
      if (/\.(?:js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|ts|m4s|mp4)$/i.test(p) || inv.length >= 40 || inv.some((r) => r.p === p)) return;
      inv.push({ p: p.slice(0, 160), ct: String(ct || '').split(';')[0].slice(0, 30), len: len || 0, eps: eps || 0, y: String(why || '').slice(0, 150) });
      clearTimeout(invT); invT = setTimeout(postInv, 900);
    } catch (e) { /* ignore */ }
  };
  // V62: kaa.lt (and sites built the same way) give only the code of an episode ("slug":"08db70") plus its number ("episode_number":3);
  // the address is /show/ep-<number>-<code>. Build it here, otherwise the list looks empty ("no episode codes inside").
  const BARE_CODE = /^[a-z0-9]{4,10}$/i;
  const NUM_KEYS = ['episode_number', 'episodeNumber', 'ep_number', 'episode_no', 'episodeNo', 'number', 'episode', 'ep', 'episode_string', 'episodeString'];
  const numOf = (o) => {
    for (const k of NUM_KEYS) {
      const v = o[k];
      if (typeof v === 'number' && isFinite(v) && v >= 0) return v;
      if (typeof v === 'string' && /^\s*\d+(?:\.\d+)?\s*$/.test(v)) return +v;
    }
    const t = /(?:episode|ep\.?)\s*(\d+(?:\.\d+)?)/i.exec(String(o.title || o.name || ''));
    return t ? +t[1] : NaN;
  };
  const keysSample = (o, d) => { // key names only (no values): helps to understand a site that still does not work
    if (!o || d > 6) return '';
    if (Array.isArray(o)) { for (const x of o) { if (x && typeof x === 'object' && !Array.isArray(x)) return Object.keys(x).slice(0, 12).join(','); } return ''; }
    if (typeof o === 'object') { for (const k in o) { const r = keysSample(o[k], d + 1); if (r) return k + '[' + r + ']'; } }
    return '';
  };
  const pagesOf = (j) => (j && Array.isArray(j.pages) ? j.pages.map((p) => +(p && p.number)).filter((n) => n > 0) : []);
  const apiHeaders = () => { const h = { accept: 'application/json' }; try { h['x-origin'] = location.host; h['x-timezone'] = Intl.DateTimeFormat().resolvedOptions().timeZone; h['x-client-ts'] = String(Date.now()); } catch (e) { /* ignore */ } return h; };
  const getJson = (href, cb) => { // the site's own API, same origin, same cookies
    const f = window.__mgFetch || window.fetch;
    if (!f) return;
    f.call(window, href, { credentials: 'same-origin', headers: apiHeaders() }).then((r) => (r && r.ok ? r.text() : '')).then((t) => { if (t) cb(t); }).catch(() => {});
  };
  // the list is bigger than one answer (the answer names its pages): ask for the other pages
  const morePages = (url, j, lang) => {
    const pg = pagesOf(j);
    if (pg.length < 2) return;
    let u; try { u = new URL(url, location.href); } catch (e) { return; }
    const cur = +u.searchParams.get('page') || 0;
    if (cur) epStore.asked.add(lang + '|' + cur);
    pg.slice(0, 40).forEach((n) => {
      const k = lang + '|' + n;
      if (epStore.asked.has(k)) return;
      epStore.asked.add(k);
      const x = new URL(u.href); x.searchParams.delete('ep'); x.searchParams.set('page', String(n));
      getJson(x.href, (t) => epListFrom(x.href, t, 'application/json', t.length));
    });
  };
  const publishEps = () => {
    const show = epStore.show; if (!show) return;
    const cur = (pathNow()[1] || '').toLowerCase();
    let pick = null;
    epStore.langs.forEach((m) => { if (!pick && [...m.values()].some((e) => e.slug.toLowerCase() === cur)) pick = m; });
    if (!pick && epStore.langs.size === 1) pick = epStore.langs.values().next().value;
    if (!pick || !pick.size) return;
    window.postMessage({ __mg: 1, kind: 'eplist', show, eps: [...pick.values()].slice(0, 3000) }, '*');
  };
  // the page asked the show's API but nothing usable came out (answer missed, other language): ask it ourselves, once or twice
  const activeAsk = () => {
    try {
      const show = showNow();
      if (!show || epStore.active >= 2) return;
      if (!inv.some((r) => r.p.indexOf('/api/show/' + show + '/') >= 0)) return; // only sites that really have this kind of API
      const cur = (pathNow()[1] || '').toLowerCase();
      if ([...epStore.langs.values()].some((m) => [...m.values()].some((e) => e.slug.toLowerCase() === cur))) return;
      epStore.active++;
      const base = location.origin + '/api/show/' + show;
      const ask = (langs) => [...new Set(langs)].slice(0, 6).forEach((l) => {
        const href = base + '/episodes?ep=1&lang=' + encodeURIComponent(l);
        getJson(href, (t) => epListFrom(href, t, 'application/json', t.length));
      });
      getJson(base + '/language', (t) => {
        const l = []; try { (function w(o, d) { if (!o || d > 5) return; if (typeof o === 'string') { if (/^[a-z]{2,3}-[A-Z]{2}$/.test(o)) l.push(o); } else if (typeof o === 'object') Object.keys(o).forEach((k) => w(o[k], d + 1)); })(JSON.parse(t), 0); } catch (e) { /* ignore */ }
        ask(l.concat(['ja-JP', 'en-US']));
      });
    } catch (e) { /* never break the page */ }
  };
  setTimeout(activeAsk, 3500); setTimeout(activeAsk, 9000);
  const epListFrom = (url, text, ct, len) => {
    try {
      const show = showNow();
      if (!show) return;                                   // not an episode page: nothing is read, nothing is logged
      if (epProbes >= 120) { logReq(url, ct, len, 0, 'skipped: probe limit (120) reached'); return; }
      if (!text || text.length > 3e6) { logReq(url, ct, len, 0, text ? 'skipped: answer bigger than 3 MB' : 'skipped: empty answer'); return; }
      if (!/^\s*[\[{]/.test(text)) { logReq(url, ct, len, 0, 'skipped: says JSON but is not'); return; }
      epProbes++;
      let path = '';
      try { path = new URL(url, location.href).pathname; } catch (e) { return; }
      let j; try { j = JSON.parse(text); } catch (e) { logReq(url, ct, len, 0, 'skipped: JSON could not be read'); return; }
      const found = [];
      (function walk(o, d) {
        if (!o || d > 10) return;
        if (Array.isArray(o)) { o.forEach((x) => { if (typeof x === 'string') { const m = SLUG_IN.exec(x); if (m) found.push({ slug: m[1], n: +m[2], title: '', show: '' }); } else walk(x, d + 1); }); return; }
        if (typeof o !== 'object') return;
        const title = String(o.title || o.name || o.episode_title || '').replace(/\s+/g, ' ').trim().slice(0, 200);
        const sh = (o.show && typeof o.show === 'object' && o.show.slug) || (typeof o.show_slug === 'string' && o.show_slug) || (typeof o.anime_slug === 'string' && o.anime_slug) || '';
        if (typeof o.slug === 'string' && BARE_CODE.test(o.slug) && !/^(?:ep|episode)[-_]?\d/i.test(o.slug)) { // {"slug":"08db70","episode_number":3}
          const n = numOf(o);
          if (isFinite(n)) found.push({ slug: 'ep-' + n + '-' + o.slug, n, title, show: sh });
        }
        for (const k in o) {
          const v = o[k];
          if (typeof v === 'string') { const m = SLUG_IN.exec(v); if (m) found.push({ slug: m[1], n: +m[2], title, show: sh }); }
          else if (v && typeof v === 'object') walk(v, d + 1);
        }
      })(j, 0);
      const mine = found.filter((f) => !f.show || f.show === show);
      const nums = new Set(mine.map((f) => f.n));
      const cur = (pathNow()[1] || '').toLowerCase();
      const aboutShow = path.split('/').indexOf(show) >= 0;
      const hasCur = mine.some((f) => f.slug.toLowerCase() === cur);
      let why = 'USED';
      if (!found.length) why = 'no episode codes inside' + (keysSample(j, 0) ? ' (fields: ' + keysSample(j, 0) + ')' : '');
      else if (!mine.length) why = 'episodes of another show only';
      else if (!(aboutShow || (hasCur && nums.size >= 3))) why = 'not about this show' + (hasCur ? ' (only ' + nums.size + ' episodes in it)' : ' and the episode you are on is not in it');
      logReq(url, ct, len, mine.length || found.length, why);
      if (why !== 'USED') return;
      if (epStore.show !== show) { epStore.show = show; epStore.langs = new Map(); epStore.asked = new Set(); epStore.active = 0; }
      let lang = ''; try { lang = new URL(url, location.href).searchParams.get('lang') || ''; } catch (e) { /* ignore */ }
      if (!epStore.langs.has(lang)) epStore.langs.set(lang, new Map());
      const bucket = epStore.langs.get(lang);
      mine.forEach((f) => { if (!bucket.has(f.n)) bucket.set(f.n, { slug: f.slug, n: f.n, title: f.title }); });
      publishEps();
      morePages(url, j, lang);
    } catch (e) { /* never break the page */ }
  };
  window.addEventListener('message', (e) => { // content.js asks once it is ready
    const d = e.data;
    if (e.source !== window || !d || d.__mg !== 2 || d.kind !== 'eplist-req') return;
    if (epStore.langs.size && epStore.show === showNow()) publishEps();
    postInv(); // even when empty: tells content.js that this hook is alive
  });

  const _fetch = window.fetch;
  window.__mgFetch = _fetch; // our own API calls use the page's original fetch (they must not be treated as the player's requests)
  if (_fetch) {
    window.fetch = function (input) {
      const p = _fetch.apply(this, arguments);
      try {
        const url = typeof input === 'string' ? input : (input && input.url) || String(input);
        const k = kindOf(url);
        if (k) { emit(url, k); if (k === 'hls' || k === 'dash') p.then((r) => { if (r && r.ok) r.clone().text().then((t) => sendText(url, t)).catch(() => {}); }).catch(() => {}); } // V79
        else p.then((r) => {
          if (!r || !r.ok) return;
          const ct = (r.headers.get('content-type') || '').toLowerCase();
          if (/json/.test(ct)) { if (showNow()) r.clone().text().then((t) => epListFrom(url, t, ct, t.length)).catch(() => {}); } else if (showNow()) logReq(url, ct, parseInt(r.headers.get('content-length'), 10) || 0, 0, 'not JSON');
          if (/mpegurl/.test(ct)) return emit(url, 'hls');
          if (/dash\+xml/.test(ct)) return emit(url, 'dash');
          if (!worthProbing(url, ct, parseInt(r.headers.get('content-length'), 10) || 0)) return;
          r.clone().text().then((t) => { fromText(url, t.slice(0, 1024)); sendText(url, t); }).catch(() => {});
        }).catch(() => {});
      } catch (e) { /* never break the page */ }
      return p;
    };
  }

  const _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    try {
      const u = String(url), k = kindOf(u);
      if (k) {
        emit(u, k);
        if (k === 'hls' || k === 'dash') this.addEventListener('load', () => { // V79
          try {
            if (this.status !== 200 && this.status !== 206) return;
            const rt = this.responseType;
            if (rt === '' || rt === 'text') sendText(u, String(this.responseText));
            else if (rt === 'arraybuffer' && this.response && this.response.byteLength < 600000) sendText(u, new TextDecoder().decode(new Uint8Array(this.response)));
          } catch (e) { /* ignore */ }
        });
      }
      else this.addEventListener('load', () => {
        try {
          if (this.status !== 200 && this.status !== 206) return;
          const ct = (this.getResponseHeader('content-type') || '').toLowerCase();
          if (/json/.test(ct)) { if (showNow()) { const rt0 = this.responseType; const tx = rt0 === '' || rt0 === 'text' ? String(this.responseText) : rt0 === 'json' ? JSON.stringify(this.response) : ''; epListFrom(u, tx, ct, tx.length); } } else if (showNow()) logReq(u, ct, 0, 0, 'not JSON');
          if (/mpegurl/.test(ct)) return emit(u, 'hls');
          if (/dash\+xml/.test(ct)) return emit(u, 'dash');
          const rt = this.responseType;
          const len = parseInt(this.getResponseHeader('content-length'), 10) || (rt === 'arraybuffer' && this.response ? this.response.byteLength : 0);
          if (!worthProbing(u, ct, len)) return;
          if (rt === '' || rt === 'text') { fromText(u, String(this.responseText).slice(0, 1024)); sendText(u, String(this.responseText)); }
          else if (rt === 'arraybuffer' && this.response) fromText(u, new TextDecoder().decode(new Uint8Array(this.response, 0, Math.min(1024, this.response.byteLength))));
        } catch (e) { /* ignore */ }
      });
    } catch (e) { /* never break the page */ }
    return _open.apply(this, arguments);
  };
})();
