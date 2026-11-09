// Media Grabber - extra site readers (V82). Runs inside the page (world: MAIN, document_start, all frames).
// Ideas ported from MAX Video Downloader: it reads what the SITE itself already knows (player state, API answers, embedded JSON)
// instead of guessing from network traffic. Supported here: Bilibili, iQIYI (iq.com), Facebook, Instagram, Vimeo player page,
// TwitCasting, Showroom, CNTV, Weverse.
// SAFE BY DESIGN: this file only REPORTS finds through the same postMessage channel as siteHook.js ({__mg:1, site:1, ...}).
// It never changes a download path, never touches the old detectors, every step is in try/catch. If it finds nothing or breaks,
// all the old detection (webRequest, hook.js, siteHook.js, DOM scan) keeps working exactly as before.
// The "Site modules" setting (data-mg-site on <html>) switches it off together with siteHook.js.
(() => {
  if (typeof window === 'undefined' || window.__mgMax) return;
  window.__mgMax = 1;
  const host = (location.hostname || '').toLowerCase();
  const is = (d) => host === d || host.endsWith('.' + d);
  const off = () => { try { return document.documentElement.dataset.mgSite === 'off'; } catch (e) { return false; } };
  const seen = new Set();
  let emitted = 0;
  const CAP = 80;

  const norm = (u) => {
    u = String(u || '').trim();
    if (!u) return '';
    if (/^data:/i.test(u)) return u;
    if (u.startsWith('//')) u = 'https:' + u;
    if (!/^https?:/i.test(u)) { try { u = new URL(u, location.href).href; } catch (e) { return ''; } }
    return u;
  };
  const kindOf = (u) => {
    const p = String(u).split('#')[0];
    if (/\.m3u8(\?|$)/i.test(p)) return 'hls';
    if (/\.mpd(\?|$)/i.test(p)) return 'dash';
    if (/\.(mp3|m4a|aac|ogg|wav|flac)(\?|$)/i.test(p)) return 'audio';
    if (/\.(vtt|srt)(\?|$)/i.test(p)) return 'subtitle';
    return 'video';
  };
  // a facebook / instagram link with bytestart / byteend is ONE slice of the file, not the whole file
  const wholeFile = (u) => {
    try { const x = new URL(u); if (!x.searchParams.has('bytestart') && !x.searchParams.has('byteend')) return u; x.searchParams.delete('bytestart'); x.searchParams.delete('byteend'); return x.href; } catch (e) { return u; }
  };
  const hash = (s) => { let h = 0; for (let i = 0; i < s.length; i++) { h = (h << 5) - h + s.charCodeAt(i); h |= 0; } return Math.abs(h).toString(16); };
  const dataUrl = (mime, text) => 'data:' + mime + ';base64,' + btoa(unescape(encodeURIComponent(text)));
  const xmlEsc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  // x = { label, lang, inline } ; inline = the url is a data: URL that carries the playlist itself (content.js lets it through)
  const emit = (url, kind, x) => {
    x = x || {};
    const u = norm(url);
    if (!u || off() || emitted >= CAP) return;
    kind = kind || kindOf(u);
    const k = kind + '|' + (x.inline ? hash(u) : u);
    if (seen.has(k)) return;
    seen.add(k); emitted++;
    try { window.postMessage({ __mg: 1, site: 1, url: u, kind, label: x.label || '', lang: x.lang || '', inline: x.inline ? 1 : 0 }, '*'); } catch (e) { /* never break the page */ }
  };

  function walk(o, fn, depth = 0, budget = { n: 6000 }) {
    if (!o || typeof o !== 'object' || depth > 12 || budget.n-- <= 0) return;
    if (Array.isArray(o)) { for (const x of o) walk(x, fn, depth + 1, budget); return; }
    fn(o);
    for (const k in o) { const v = o[k]; if (v && typeof v === 'object') walk(v, fn, depth + 1, budget); }
  }
  const tryJson = (t) => {
    if (typeof t !== 'string') return null;
    let s = t.trim();
    if (s.startsWith('for (;;);')) s = s.slice(9).trim();
    if (!(s.startsWith('{') || s.startsWith('['))) return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  };

  // ======================= Bilibili =======================
  // The page keeps the stream list in window.__playinfo__ (or the player state). Video and audio are separate files, so a small
  // DASH manifest is written here (one file per quality) and the normal DASH downloader joins them. Dolby / FLAC tracks are left out
  // on purpose (not plain AAC: the join step would not be able to play them).
  function biliMpd(d) {
    const dash = d && d.dash;
    if (!dash || typeof dash !== 'object') return null;
    const link = (t) => {
      const b = t && (t.baseUrl || t.base_url);
      if (typeof b === 'string' && /^https?:/i.test(b)) return b;
      const bk = t && (t.backupUrl || t.backup_url);
      return Array.isArray(bk) && typeof bk[0] === 'string' && /^https?:/i.test(bk[0]) ? bk[0] : '';
    };
    const per = new Map(); // one video per quality id, H.264 first (plays everywhere)
    (Array.isArray(dash.video) ? dash.video : []).forEach((v) => {
      const u = link(v); if (!u) return;
      const avc = /^avc/i.test(String(v.codecs || '')) || Number(v.codecid || v.codecId) === 7;
      const cur = per.get(v.id);
      if (!cur || (avc && !cur.avc)) per.set(v.id, { v, u, avc });
    });
    const auds = (Array.isArray(dash.audio) ? dash.audio : []).map((a) => ({ a, u: link(a) })).filter((x) => x.u);
    if (!per.size && !auds.length) return null;
    const secs = Math.round(Number(dash.duration) || (Number(d.timelength) || 0) / 1000) || 0;
    const dur = secs ? `PT${secs}S` : '';
    const rep = (id, t, u, extra) => `<Representation id="${xmlEsc(id)}" bandwidth="${Number(t.bandwidth) || 0}" codecs="${xmlEsc(t.codecs || '')}" mimeType="${xmlEsc(t.mimeType || t.mime_type || extra)}"${t.width ? ` width="${Number(t.width)}"` : ''}${t.height ? ` height="${Number(t.height)}"` : ''}${t.frameRate || t.frame_rate ? ` frameRate="${xmlEsc(t.frameRate || t.frame_rate)}"` : ''}><BaseURL>${xmlEsc(u)}</BaseURL></Representation>`;
    let vx = '', ax = '';
    per.forEach((p, q) => { vx += rep('v' + q, p.v, p.u, 'video/mp4'); });
    auds.forEach((x, i) => { ax += rep('a' + (x.a.id != null ? x.a.id : i), x.a, x.u, 'audio/mp4'); });
    return '<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"' + (dur ? ` mediaPresentationDuration="${dur}"` : '') + '><Period' + (dur ? ` duration="${dur}"` : '') + '>'
      + (vx ? `<AdaptationSet contentType="video">${vx}</AdaptationSet>` : '') + (ax ? `<AdaptationSet contentType="audio">${ax}</AdaptationSet>` : '') + '</Period></MPD>';
  }
  function biliState() {
    try {
      const a = window.__playinfo__ && window.__playinfo__.data;
      if (a && a.dash) return a;
      const pu = window.__initialState && window.__initialState.player && window.__initialState.player.playUrl;
      const b = pu && (pu.value || pu._value || pu._rawValue);
      return b && b.dash ? b : null;
    } catch (e) { return null; }
  }
  function biliEmit(d) {
    try { const m = biliMpd(d); if (m) emit(dataUrl('application/dash+xml', m), 'dash', { inline: 1, label: 'Bilibili' }); } catch (e) { /* ignore */ }
  }
  function biliApi(j) { // answers of the player APIs: ordinary video, bangumi / series
    if (!j) return;
    const d = j.data || j.result || null;
    if (!d) return;
    biliEmit(d.dash ? d : (d.video_info && d.video_info.dash ? d.video_info : d));
  }

  // ======================= iQIYI (iq.com) =======================
  // window.__playerdata__ holds the playlist text itself (m3u8 string) and the subtitle files; no extra request is needed.
  function iqEmit(root, withSubs) {
    const dash = root && (root.__dash || root);
    const data = dash && dash.data;
    const prog = data && data.program;
    if (!prog) return;
    const v = Array.isArray(prog.video) ? prog.video.find((x) => x && typeof x.m3u8 === 'string' && x.m3u8.trim()) : null;
    if (v) emit(dataUrl('application/vnd.apple.mpegurl', v.m3u8), 'hls', { inline: 1, label: 'iQIYI' });
    const base = data.dstl;
    if (withSubs !== false && typeof base === 'string' && base && Array.isArray(prog.stl)) {
      prog.stl.forEach((s) => {
        if (!s || typeof s._name !== 'string' || !s._name.trim()) return;
        ['srt', 'webvtt'].forEach((f) => {
          if (typeof s[f] !== 'string' || !s[f]) return;
          try { emit(new URL(s[f], base).href, 'subtitle', { lang: s._name.trim(), label: s._name.trim() }); } catch (e) { /* skip */ }
        });
      });
    }
  }
  function iqPoll() {
    if (!/\/play\//.test(location.href)) return;
    const end = Date.now() + 10000;
    const t = () => {
      try { const p = window.__playerdata__; if (p && p.__dash && p.__dash.data) { iqEmit(p, true); return; } } catch (e) { /* ignore */ }
      if (Date.now() < end) setTimeout(t, 250);
    };
    t();
  }

  // ======================= Facebook / Instagram =======================
  // The video data sits inside <script> JSON of the page. The page is read again (same address, same cookies) when the address changes,
  // because Facebook / Instagram change the address without reloading.
  const META_MARK = ['dash_manifest', 'manifest_xml', 'dash_manifest_xml_string', 'video_dash_manifest', 'browser_native_', 'progressive_url', 'hls_playlist'];
  const META_DASH_KEYS = new Set(['dash_manifest', 'manifest_xml', 'dash_manifest_xml_string', 'video_dash_manifest']);
  const META_URL_KEYS = { dash_manifest_url: 'dash', manifest_url: 'dash', hd_src: 'video', sd_src: 'video', browser_native_hd_url: 'video', browser_native_sd_url: 'video', progressive_url: 'video', playable_url_quality_hd: 'video', playable_url: 'video', hls_playlist_url: 'hls' };
  const has = (t, arr) => { for (const m of arr) if (t.includes(m)) return true; return false; };
  function metaInline(xml) {
    const s = String(xml || '').trim();
    if (!s.startsWith('<')) return;
    emit(dataUrl('application/dash+xml', s), 'dash', { inline: 1, label: 'Facebook / Instagram' });
  }
  function metaWalk(o) {
    walk(o, (n) => {
      for (const [k, v] of Object.entries(n)) {
        if (META_DASH_KEYS.has(k) && typeof v === 'string' && v) metaInline(v);
        if ((k === 'progressive_urls' || k === 'progressiveUrls') && Array.isArray(v)) v.forEach((i) => { if (i && typeof i.progressive_url === 'string' && i.progressive_url) emit(wholeFile(i.progressive_url), 'video'); });
        if (k === 'video_versions' && Array.isArray(v)) v.forEach((i) => { if (i && typeof i.url === 'string' && i.url) emit(wholeFile(i.url), 'video'); });
        if (META_URL_KEYS[k] && typeof v === 'string' && v) {
          if (META_URL_KEYS[k] === 'dash' && v.trim().startsWith('<')) metaInline(v);
          else emit(META_URL_KEYS[k] === 'video' ? wholeFile(v) : v, META_URL_KEYS[k]);
        }
      }
    });
  }
  function metaScanHtml(html) {
    if (typeof html !== 'string' || !has(html, META_MARK)) return;
    const re = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
      const t = m[1];
      if (!t || !has(t, META_MARK)) continue;
      const j = tryJson(t);
      if (j) metaWalk(j);
    }
  }
  function metaScanDom() {
    try { document.querySelectorAll('script').forEach((s) => { const t = s.textContent || ''; if (t.length > 200 && has(t, META_MARK)) { const j = tryJson(t); if (j) metaWalk(j); } }); } catch (e) { /* ignore */ }
  }
  let metaLast = '', metaAc = null, metaT = 0;
  async function metaFetchPage() {
    const href = location.href;
    if (href === metaLast || off()) return;
    // 0.0.348 (Thazir): this is the ONLY network call of siteMax.js (the page's own address, same origin). Settings > "Facebook / Instagram: read the page again" (siteMaxFetch) switches it off.
    const mf = document.documentElement.dataset.mgMaxFetch;
    if (mf === 'off') return;
    if (mf === undefined) { setTimeout(metaFetchPage, 1500); return; } // the setting is not read yet
    if (Date.now() - metaT < 1500) return; // never hammer the site
    metaLast = href; metaT = Date.now();
    try { if (metaAc) metaAc.abort(); } catch (e) { /* ignore */ }
    metaAc = new AbortController();
    try {
      const r = await fetch(href, { method: 'GET', credentials: 'same-origin', headers: { Accept: 'text/html' }, signal: metaAc.signal });
      if (!r.ok) return;
      metaScanHtml(await r.text());
    } catch (e) { /* aborted / blocked: ignore */ }
  }

  // ======================= API answers (fetch / XHR) =======================
  // handler = { test(url) -> bool, parse(json, text, url) }
  const HANDLERS = [];
  const parseBody = (hs, text, url) => { const j = tryJson(text); hs.forEach((h) => { try { h.parse(j, text, url); } catch (e) { /* ignore */ } }); };
  function installHooks() {
    if (!HANDLERS.length) return;
    const _fetch = window.fetch;
    if (_fetch) {
      window.fetch = function (input) {
        const p = _fetch.apply(this, arguments);
        try {
          const url = typeof input === 'string' ? input : (input && input.url) || String(input);
          const hs = HANDLERS.filter((h) => h.test(url));
          if (hs.length) p.then((r) => { if (r && r.ok) r.clone().text().then((t) => parseBody(hs, t, url)).catch(() => {}); }).catch(() => {});
        } catch (e) { /* never break the page */ }
        return p;
      };
    }
    const _open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, url) {
      try {
        const u = String(url), hs = HANDLERS.filter((h) => h.test(u));
        if (hs.length) this.addEventListener('load', () => {
          try {
            if (this.responseType === '' || this.responseType === 'text') parseBody(hs, this.responseText, u);
            else if (this.responseType === 'json' && this.response) parseBody(hs, JSON.stringify(this.response), u);
          } catch (e) { /* ignore */ }
        });
      } catch (e) { /* never break the page */ }
      return _open.apply(this, arguments);
    };
  }

  // ---- site handlers ----
  if (is('bilibili.com') || is('bilibili.tv')) {
    HANDLERS.push({ test: (u) => /\/x\/player\/(wbi\/)?playurl|\/pgc\/player\/web\/playurl/.test(u), parse: (j) => biliApi(j) });
  }
  if (is('iq.com') || is('iqiyi.com')) {
    HANDLERS.push({ test: (u) => /intl-api\.iq\.com\/.*\/dash(\?|$)/.test(u), parse: (j) => iqEmit(j, false) });
    HANDLERS.push({ test: (u) => u.includes('video.iqiyi.com/videos'), parse: (j) => { ((j && j.d) || []).forEach((o) => [o && o.URL, o && o.URL2].forEach((s) => { if (typeof s === 'string' && s) emit(s, 'video'); })); } });
  }
  if (is('twitcasting.tv')) { // live: one HLS address per quality
    HANDLERS.push({ test: (u) => u.includes('twitcasting.tv/streamserver.php') || /^\/?streamserver\.php/.test(u), parse: (j) => { const s = j && j['tc-hls'] && j['tc-hls'].streams; if (s && typeof s === 'object') Object.values(s).forEach((x) => { if (typeof x === 'string' && /^https?:\/\//i.test(x)) emit(x, 'hls', { label: 'TwitCasting' }); }); } });
  }
  if (is('showroom-live.com')) {
    HANDLERS.push({ test: (u) => u.includes('/api/live/streaming_url'), parse: (j) => { ((j && j.streaming_url_list) || []).forEach((s) => { const a = s && s.url; if (typeof a !== 'string' || !a.startsWith('http')) return; emit(a, (s.type === 'dash' || a.includes('.mpd')) ? 'dash' : 'hls', { label: 'Showroom' }); }); } });
  }
  if (is('cntv.cn')) {
    HANDLERS.push({ test: (u) => /cntv\.cn\/api\/getHttpVideoInfo/.test(u), parse: (j, t) => { const h = (j && j.hls_url) || ((/"hls_url"\s*:\s*"([^"]+)"/.exec(t || '') || [])[1] || '').replace(/\\\//g, '/'); if (typeof h === 'string' && /^https?:\/\//i.test(h)) emit(h, 'hls', { label: 'CNTV' }); } });
  }
  if (is('weverse.io')) {
    HANDLERS.push({ test: (u) => /\/weverse\/wevweb\/video\/.*\/playInfo/.test(u), parse: (j) => {
      const pb = j && j.playback;
      if (typeof pb !== 'string') return;
      let m; const r1 = /nvod:m3u="([^"]+)"/g;
      while ((m = r1.exec(pb)) !== null) emit(m[1].replace(/&amp;/g, '&'), 'hls', { label: 'Weverse' });
      const r2 = /<BaseURL[^>]*>([^<]+)<\/BaseURL>/g;
      while ((m = r2.exec(pb)) !== null) { const u = m[1].trim().replace(/&amp;/g, '&'); if (/\.(mp4|m4v|mkv|webm|mov)(\?|$)/i.test(u.split('#')[0]) && /^https?:/i.test(u)) emit(u, 'video', { label: 'Weverse' }); }
    } });
  }
  try { installHooks(); } catch (e) { /* ignore */ }

  // ---- page state readers ----
  function vimeoPoll() { // player page: window.playerConfig (same addresses siteHook.js reports, so nothing shows twice)
    if (host !== 'player.vimeo.com' || !/^\/video\/\d+/.test(location.pathname)) return;
    const wait = [0, 25, 50, 100, 200, 400, 800, 1200, 2000, 3000, 5000];
    let i = 0;
    const t = () => {
      try {
        const c = window.playerConfig, f = c && ((c.video && c.video.files) || (c.request && c.request.files));
        if (f && typeof f === 'object') {
          const h = f.hls && f.hls.cdns;
          if (h) { const x = h[f.hls.default_cdn] || Object.values(h)[0]; if (x && x.url) emit(x.url, 'hls'); }
          const tt = c.request && c.request.text_tracks;
          (Array.isArray(tt) ? tt : []).forEach((s) => { if (s && typeof s.url === 'string') { try { emit(new URL(s.url, 'https://player.vimeo.com').href, 'subtitle', { lang: s.lang || '', label: s.label || '' }); } catch (e) { /* skip */ } } });
          return;
        }
      } catch (e) { /* ignore */ }
      if (i < wait.length) setTimeout(t, wait[i++]);
    };
    t();
  }

  let lastHref = '';
  function onPage(first) {
    try {
      if (off()) return;
      const href = location.href;
      if (!first && href === lastHref) return;
      lastHref = href;
      if (is('bilibili.com') || is('bilibili.tv')) {
        const end = Date.now() + 10000;
        const t = () => { const d = biliState(); if (d) { biliEmit(d); return; } if (Date.now() < end) setTimeout(t, 250); };
        t();
      }
      if (is('iq.com')) iqPoll();
      if (is('facebook.com') || is('instagram.com')) { metaScanDom(); metaFetchPage(); }
      vimeoPoll();
    } catch (e) { /* ignore */ }
  }
  const need = is('bilibili.com') || is('bilibili.tv') || is('iq.com') || is('facebook.com') || is('instagram.com') || host === 'player.vimeo.com';
  if (need) {
    onPage(true);
    document.addEventListener('DOMContentLoaded', () => onPage(true));
    window.addEventListener('load', () => setTimeout(() => { if (is('facebook.com') || is('instagram.com')) metaScanDom(); }, 1200));
    setInterval(() => onPage(false), 1200); // single-page sites change the address without a reload
  }

  try { if (typeof module !== 'undefined' && module.exports) module.exports = { biliMpd, iqEmit, metaWalk, metaScanHtml, wholeFile, dataUrl }; } catch (e) { /* not a test run */ }
})();
