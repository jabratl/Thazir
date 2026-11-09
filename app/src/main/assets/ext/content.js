// Media Grabber - content script: DOM detection + on-page download button, panel, video overlay, recorder
(() => {
  if (window.__mediaGrabberLoaded) return;
  window.__mediaGrabberLoaded = true;
  const IS_TOP = window === window.top;
  if (!IS_TOP && isAdHost(location.hostname)) return; // never run inside ad frames
  let S = { ...DEFAULTS };
  let rec = { items: [], title: '', hint: null };
  const jobs = {};
  let sent = new Set();
  const live = () => { try { return !!chrome.runtime.id; } catch (e) { return false; } };
  const send = (m) => new Promise((r) => { try { chrome.runtime.sendMessage(m, (x) => { void chrome.runtime.lastError; r(x); }); } catch (e) { r(null); } });
  const siteMode = () => { const m = S.mgbSiteModes; if (!m || typeof m !== 'object') return -1; const h = location.hostname.toLowerCase().replace(/^www\./, ''); for (const k in m) { if (h === k || h.endsWith('.' + k)) { const v = Number(m[k]); return v >= 0 ? v : -1; } } return -1; }; // MGB S57
  const siteOff = () => !S.enabled || (S.disabledSites || []).some((d) => d && (location.hostname === d || location.hostname.endsWith('.' + d)));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const visible = () => rec.items.filter((i) => !i.hidden);

  // ================= detection =================
  const abs = (u, base) => {
    try { const x = new URL(u, base || location.href).href; return /^https?:/i.test(x) ? x : null; } catch (e) { return null; }
  };
  const MEDIA_LINK = /\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd|vtt|srt)(\?|#|$)/i;

  function scan() {
    if (siteOff() || !live()) return;
    const items = [];
    document.querySelectorAll('video, audio').forEach((el) => {
      const kind = el.tagName === 'VIDEO' ? 'video' : 'audio';
      if (S.blockAds && isAdEl(el)) { markAd(el); return; }
      const srcs = [el.currentSrc, el.getAttribute('src')];
      el.querySelectorAll('source').forEach((s) => srcs.push(s.getAttribute('src')));
      srcs.forEach((s) => { const u = s && abs(s); if (u && !adUrl(u)) items.push({ url: u, kind, ref: location.href }); });
      el.querySelectorAll('track').forEach((t) => {
        const k = (t.kind || 'subtitles').toLowerCase();
        const src = t.getAttribute('src');
        if ((k === 'subtitles' || k === 'captions') && src) {
          const u = abs(src);
          if (u) items.push({ url: u, kind: 'subtitle', label: t.label || '', lang: t.srclang || '', ref: location.href });
        }
      });
    });
    document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[property="og:audio"],meta[name="twitter:player:stream"]').forEach((m) => {
      const u = abs(m.content);
      if (u && MEDIA_LINK.test(u)) items.push({ url: u, kind: /\.(mp3|m4a|aac|ogg|wav|flac)/i.test(u) ? 'audio' : 'video', ref: location.href });
    });
    let n = 0;
    document.querySelectorAll('a[href]').forEach((a) => {
      if (n > 150 || !MEDIA_LINK.test(a.getAttribute('href') || '')) return;
      const u = abs(a.getAttribute('href'));
      if (u && !adUrl(u) && !(S.blockAds && isAdEl(a))) { n++; items.push({ url: u, kind: 'video', ref: location.href }); }
    });
    const fresh = items.filter((i) => { const k = i.kind + '|' + i.url; if (sent.has(k)) return false; sent.add(k); return true; });
    if (fresh.length) send({ type: 'dom-items', items: fresh });
    videoInfo();
    const cnt = countPages();
    probeSite();
    const chs = findChapters();
    const sig = (a) => a.map((x) => x.url).sort().join('|');
    let chg = false;
    if (sig(chs) !== sig(chapters)) { chapters = chs; chg = true; }
    const mgChg = cnt !== mangaN; // MGB S55
    if (cnt !== mangaN || chg || adsN !== adsShown) { mangaN = cnt; adsShown = adsN; renderAll(); if (chg || mgChg) pubNative(); }
  }

  // MGB S53: the native Files detected panel reads the episode / chapter list from here (storage.local mgNativeCh)
  function pubNative() {
    if (!IS_TOP) return;
    try {
      let book = '';
      try { book = (S.cleanTitles !== false ? pickSeriesTitle('video') : seriesName(document.title || 'video')) || ''; } catch (e) { book = document.title || ''; }
      chrome.storage.local.set({ mgNativeMg: { url: location.href, t: Date.now(), has: mangaN >= 3 || !!siteTask, n: mangaN >= 3 ? mangaN : ((siteTask && siteTask.pages) || 0), site: siteTask ? (siteTask.label || '') : '' } }); // MGB S55: manga block of the native panel
      chrome.storage.local.set({ mgNativeCh: { url: location.href, t: Date.now(), book, chapters: chapters.slice(0, 300).map((c) => ({ url: c.url, label: c.label })) } });
    } catch (e) { /* optional */ }
  }

  // media seen by hook.js (fetch / XHR inside this page or player iframe)
  // episode list the site loaded from its own API (hook.js): kept per show, merged into the batch list
  // episode slugs written into the page itself (server-rendered state). Counts only when the episode you are on is among them.
  function scanInlineEps() {
    try {
      if (!IS_TOP || siteOff() || apiCh.size >= 3) return;
      const p = location.pathname.split('/').filter(Boolean);
      if (p.length !== 2 || !/^(?:ep|episode)[-_]?\d/i.test(p[1])) return;
      let text = '', n = 0;
      document.querySelectorAll('script:not([src])').forEach((sc) => { const t = sc.textContent || ''; if (n < 4e6 && t.length < 3e6) { text += '\n' + t; n += t.length; } });
      const re = /(?:^|["'\\\/,\s\[])((?:ep|episode)[-_]?(\d+(?:\.\d+)?)[-_][a-z0-9]{3,10})(?=["'\\\/,\s\]\}])/gi;
      const found = new Map(); let m;
      while ((m = re.exec(text))) { const num = +m[2]; if (!found.has(num)) found.set(num, m[1]); }
      inlineN = found.size;
      if (found.size >= 3 && [...found.values()].some((s2) => s2.toLowerCase() === p[1].toLowerCase())) {
        onEpList({ show: p[0], eps: [...found.entries()].map(([num, slug]) => ({ slug, n: num, title: '' })) });
      }
    } catch (e) { /* ignore */ }
  }
  const askEpList = () => { try { window.postMessage({ __mg: 2, kind: 'eplist-req' }, '*'); } catch (e) { /* ignore */ } };
  let apiCh = new Map(), apiShow = '', apiInv = [], inlineN = 0, hookAlive = false, apiAdded = 0;
  function onEpList(d) {
    if (!IS_TOP || siteOff() || !d.show || !Array.isArray(d.eps)) return;
    const cur = location.pathname.split('/').filter(Boolean)[0] || '';
    if (d.show !== cur) return;
    if (apiShow !== d.show) { apiCh = new Map(); apiShow = d.show; }
    let add = 0;
    d.eps.forEach((x) => {
      if (!x || typeof x.slug !== 'string' || typeof x.n !== 'number' || apiCh.has(x.n)) return;
      apiCh.set(x.n, { n: x.n, url: location.origin + '/' + d.show + '/' + x.slug, label: 'Episode ' + x.n + (x.title && !/^\s*(?:episode|ep\.?)\s*\d+(?:\.\d+)?\s*$/i.test(x.title) ? ' - ' + x.title : '') });
      add++;
    });
    if (add) { try { scan(); } catch (e) { /* ignore */ } }
  }
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (e.source === window && d && d.__mg === 1 && d.kind === 'eplist') { onEpList(d); return; }
    if (e.source === window && d && d.__mg === 1 && d.kind === 'xhrlog') { hookAlive = true; if (Array.isArray(d.rows)) apiInv = d.rows.slice(0, 40); return; }
    if (e.source === window && d && d.__mg === 1 && d.kind === 'pltext' && d.url && typeof d.text === 'string' && !siteOff() && live()) { // V79: playlist text the player received
      const pk = 'pt|' + d.url; if (sent.has(pk)) return; sent.add(pk);
      send({ type: 'playlist-text', url: d.url, text: d.text }); return;
    }
    if (e.source !== window || !d || d.__mg !== 1 || !d.url || !d.kind || siteOff() || !live()) return;
    if (d.site && S.siteModules === false) return;
    const inl = !!(d.site && d.inline && /^data:/i.test(d.url) && d.url.length < 400000); // V82: siteMax.js hands over a playlist as a data: URL (bilibili / iQIYI / Facebook)
    const u = inl ? d.url : abs(d.url);
    if (!u || (!inl && adUrl(u))) return;
    const k = d.kind + '|' + (inl ? u.length + ':' + u.slice(-64) : u);
    if (sent.has(k)) return;
    sent.add(k);
    send({ type: 'dom-items', items: [{ url: u, kind: d.kind, ref: location.href, label: d.site && d.label ? String(d.label).slice(0, 80) : '', lang: d.site && d.lang ? String(d.lang).slice(0, 40) : '' }] });
  });
  if (window === window.top) { setTimeout(scanInlineEps, 1800); setTimeout(scanInlineEps, 7000); askEpList(); let lastPath = location.pathname; setInterval(() => { if (location.pathname !== lastPath) { lastPath = location.pathname; askEpList(); } }, 1500); }

  let lastInfo = '';
  function videoInfo() {
    const vs = [...document.querySelectorAll('video')].filter((v) => v.videoWidth);
    if (!vs.length) return;
    vs.sort((a, b) => b.videoWidth * b.videoHeight - a.videoWidth * a.videoHeight);
    const v = vs[0];
    const info = { poster: v.poster ? abs(v.poster) : '', dur: isFinite(v.duration) ? Math.round(v.duration) : 0, w: v.videoWidth, h: v.videoHeight };
    const s = JSON.stringify(info);
    if (s === lastInfo) return;
    lastInfo = s;
    send({ type: 'video-info', info });
  }

  let timer;
  let pendingScan = false;
  const later = () => { clearTimeout(timer); timer = setTimeout(() => { if (document.hidden) pendingScan = true; else scan(); }, 700); };
  document.addEventListener('visibilitychange', () => { if (!document.hidden && pendingScan) { pendingScan = false; scan(); } });
  new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('play', later, true);
  document.addEventListener('loadedmetadata', later, true);


  // ================= page images (manga / webtoon / gallery) =================
  const LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-url', 'data-echo', 'data-full', 'data-large-file', 'data-orig-file'];
  const DROP = ['w', 'width', 'h', 'height', 'resize', 'fit', 'quality', 'crop', 'size'];
  let doneCh = new Set(), chState = {}, chStop = false, chRunning = false, chProg = null;
  try { chrome.storage.local.get('mgDone').then((r) => { doneCh = new Set(r.mgDone || []); }); } catch (e) { /* ignore */ }
  let tabSel = 'all', secOpen = { subtitle: false };
  let mangaN = 0, chapters = [], chSel = new Set(), chView = false, adsN = 0, adsShown = 0;
  // an episode page whose list could not be read: the picker still opens, with the pattern ready in "More ways"
  const epPageGuess = () => IS_TOP && !chapters.length && !siteOff() && !!patternFromUrl(location.href) && /(episode|eps?|chapter|chap|folge|cap[i\u00ED]tulo)[-_/. ]*\d+/i.test(location.pathname + location.search);
  const canBatch = () => chapters.length > 0 || epPageGuess();
  let siteTask = null, siteProbeUrl = '', siteChapters = []; // site adapter for this page: { name, label, pages }
  const adSeen = new WeakSet();
  const markAd = (el) => { if (!adSeen.has(el)) { adSeen.add(el); adsN++; } };
  const adUrl = (u) => !!(S.blockAds && isAdUrl(u));
  function bestFromSrcset(ss) {
    let best = null, bw = -1;
    (ss || '').split(',').forEach((part) => {
      const [u, d] = part.trim().split(/\s+/);
      if (!u) return;
      const w = d ? parseFloat(d) * (/x$/.test(d) ? 1000 : 1) : 0;
      if (w > bw) { bw = w; best = u; }
    });
    return best;
  }
  function unresize(u) {
    try {
      const x = new URL(u);
      x.pathname = x.pathname.replace(/-\d{2,4}x\d{2,4}(?=\.\w{3,4}$)/, '');
      DROP.forEach((p) => x.searchParams.delete(p));
      return x.href;
    } catch (e) { return u; }
  }
  function imgUrl(img, base) {
    let u = null;
    const pic = img.closest('picture');
    u = bestFromSrcset(img.getAttribute('srcset') || img.getAttribute('data-srcset') || (pic && [...pic.querySelectorAll('source')].map((s) => s.getAttribute('srcset')).filter(Boolean).join(',')));
    if (!u) for (const a of LAZY) { const v = img.getAttribute(a); if (v && !/^data:/.test(v)) { u = v; break; } }
    if (!u) u = img.currentSrc || img.getAttribute('src');
    return u && abs(u, base);
  }
  const bigEnough = (img) => { const r = img.getBoundingClientRect(); return (img.naturalWidth >= 300 && img.naturalHeight >= 300) || (r.width >= 250 && r.height >= 250); };
  const AD_DIMS = [[300,250],[728,90],[970,90],[970,250],[320,50],[320,100],[160,600],[300,600],[468,60],[336,280],[250,250],[120,600],[300,100],[300,50],[234,60],[125,125]];
  function adSized(img) { const r = img.getBoundingClientRect(); const w = Math.round(r.width), h = Math.round(r.height); return AD_DIMS.some(([a, b]) => Math.abs(w - a) <= 2 && Math.abs(h - b) <= 2); }
  function isAdImg(img) {
    if (!S.blockAds) return false;
    const u = img.currentSrc || img.getAttribute('src') || '';
    const a = img.closest('a[href]');
    return isAdEl(img) || adSized(img) || (u && /^https?:/i.test(u) && isAdUrl(u)) || !!(a && /^https?:/i.test(a.href) && isAdUrl(a.href));
  }
  // keep only the images that live together in the reader container (drops ads, related-thumbnails, sidebars)
  function mainCluster(imgs) {
    if (imgs.length < 4) return imgs;
    const groups = new Map();
    imgs.forEach((im) => { let p = im.parentElement; for (let d = 0; p && d < 5; d++, p = p.parentElement) { if (!groups.has(p)) groups.set(p, new Set()); groups.get(p).add(im); } });
    const need = Math.ceil(imgs.length * 0.7);
    let best = null;
    groups.forEach((set, p) => { if (set.size >= need && (!best || set.size < best.set.size)) best = { p, set }; });
    return best ? imgs.filter((im) => best.set.has(im)) : imgs;
  }
  function siteSel() {
    for (const l of (S.siteRules || '').split('\n')) {
      const i = l.indexOf('=');
      if (i < 0) continue;
      const d = l.slice(0, i).trim().toLowerCase();
      if (d && (location.hostname === d || location.hostname.endsWith('.' + d))) return l.slice(i + 1).trim();
    }
    return '';
  }
  // an image whose real address sits in data-src / data-original ... and has not been loaded by the page yet
  const lazyPending = (im) => { const cur = im.currentSrc || im.getAttribute('src') || ''; return (!cur || /^data:/.test(cur)) && LAZY.some((a) => { const v = im.getAttribute(a); return v && !/^data:/.test(v); }); };
  let galHrefs = []; // viewer links of the thumbnails found (used to fill in pages the page does not show)
  // how many pages the page itself says the gallery has (small info blocks only)
  function expectedPages() {
    const boxes = document.querySelectorAll('#info, .info, #tags, .tags, .gallery-info, .gallery_info, [class*="gallery-meta"], [class*="meta-info"], .num-pages');
    for (const b of boxes) { const n = expectedFromText((b.innerText || b.textContent || '').slice(0, 3000)); if (n) return n; }
    const og = document.querySelector('meta[property="og:description"], meta[name="description"]');
    return og ? expectedFromText((og.content || '').slice(0, 400)) : 0;
  }
  // viewer links for pages that are not in the DOM (lazy "show more" lists, paged thumbnails). Needs the page to state its total.
  function galleryFillList() { return galHrefs.length >= 3 ? galleryFill(galHrefs, expectedPages()) : []; }
  // small thumbnails that link to /g/ID/1/, /g/ID/2/ ... (hentaifox, nhentai-style galleries)
  function galleryThumbs() {
    const imgs = [], paths = [], hrefs = [];
    for (const im of document.images) {
      const a = im.closest('a[href]');
      if (!a || !/^https?:/i.test(a.href)) continue;
      const r = im.getBoundingClientRect();
      if (!lazyPending(im) && (Math.max(r.width, im.naturalWidth) < 70 || Math.max(r.height, im.naturalHeight) < 70)) continue;
      let p = ''; try { p = new URL(a.href).pathname; } catch (e) { continue; }
      if (S.blockAds && (isAdImg(im) || isAdUrl(a.href))) continue;
      imgs.push(im); paths.push(p); hrefs.push(a.href);
    }
    const run = galleryRun(paths);
    galHrefs = run.map((i) => hrefs[i]);
    return run.map((i) => imgs[i]);
  }
  function candImages() {
    const rule = siteSel();
    if (rule) { try { return [...document.querySelectorAll(rule)].filter((el) => el.tagName === 'IMG'); } catch (e) { /* bad selector, fall back */ } }
    const gal = galleryThumbs();
    if (gal.length >= 3) return gal;
    galHrefs = [];
    const ok = [];
    [...document.images].filter(bigEnough).forEach((im) => { if (isAdImg(im)) markAd(im); else ok.push(im); });
    return mainCluster(ok);
  }
  // site adapters (sites.js): pages whose images are not in the DOM (hitomi.la ...). Asked once per address, answer cached.
  function probeSite() {
    if (!IS_TOP || S.siteAdapters === false) { if (siteTask) { siteTask = null; renderAll(); } return; }
    const here = location.href.split('#')[0];
    if (here === siteProbeUrl) return;
    siteProbeUrl = here; siteChapters = [];
    const ad = MG_SITES.match(here);
    if (!ad) { if (siteTask) { siteTask = null; renderAll(); } return; }
    const resolvable = !!MG_SITES.match(here, true);
    siteTask = resolvable ? { name: ad.name, label: ad.label, pages: 0 } : null;
    if (siteTask) renderAll();
    send({ type: 'site-probe', url: here }).then((r) => {
      if (!r || !r.ok || siteProbeUrl !== here) return;
      if (siteTask) { siteTask.pages = r.pages || 0; }
      if (r.chapters && r.chapters.length) siteChapters = r.chapters;
      scan();
      renderAll();
      pubNative(); // MGB S55
    });
  }
  let cpSig = '', cpVal = 0;
  function countPages() {
    if (!IS_TOP) return 0;
    let done = 0; const imgs = document.images;
    for (let i = 0; i < imgs.length; i++) if (imgs[i].complete) done++;
    const sig = imgs.length + ':' + done + ':' + innerWidth;
    if (sig === cpSig) return cpVal; // nothing changed, skip the expensive layout reads
    cpSig = sig; cpVal = candImages().length + galleryFillList().length;
    return cpVal;
  }

  // ---------- chapter list detection + fetching chapters in the background ----------
  const CH_RE = /(chapter|chap\.?|ch\.?|episode|eps?\.?|epis[o\u00F3]dio|\u00E9pisode|folge|cap[i\u00ED]tulo|b[o\u00F6]l[u\u00FC]m|t\u1EADp|\u0441\u0435\u0440\u0438\u044F|\u96C6|\uD654|part|vol(?:ume)?\.?|\u0985\u09A7\u09CD\u09AF\u09BE\u09DF|\u8A71|\u8BDD|\u7AE0)\s*[-_.:#]?\s*\d+/i;
  const CH_HREF = /\/(chapter|chap|ch|episode|ep|read|viewer|view)[-_/]*\d+|[-_/](chapter|chap|ch|episode|ep)[-_]?\d+/i;
  function findChapters() {
    if (!IS_TOP || siteOff()) return [];
    const out = [], seen = new Set(), here = location.href.split('#')[0];
    document.querySelectorAll('a[href]').forEach((a) => {
      if (out.length >= 600) return;
      const h = a.href;
      if (!/^https?:/i.test(h)) return;
      const hh = h.split('#')[0];
      if (seen.has(hh) || hh === here) return;
      let u; try { u = new URL(h); } catch (e) { return; }
      if (u.hostname !== location.hostname) return;
      const txt = (a.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 240);
      if (!(CH_RE.test(txt) || CH_HREF.test(u.pathname))) return;
      if (S.blockAds && (isAdEl(a) || isAdUrl(h))) return;
      seen.add(hh);
      let label = txt;
      if (!label) { try { label = decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || hh); } catch (e) { label = hh; } }
      if (/^(?:ep\.?\s*|e\s*)?\d{1,4}(?:\.\d)?$/i.test(label)) label = 'Episode ' + label.replace(/^\D+/, ''); // a bare "3" on a button is not a file name
      out.push({ url: hh, label });
    });
    // numbered buttons ("1 2 3 ... 12") whose addresses only differ in the number: the usual anime episode grid
    if (out.length < 3) {
      const groups = new Map();
      document.querySelectorAll('a[href]').forEach((a) => {
        const t = (a.textContent || '').replace(/\s+/g, ' ').trim();
        const m = /^(?:ep\.?\s*|e\s*)?(\d{1,4}(?:\.\d)?)$/i.exec(t);
        if (!m || !/^https?:/i.test(a.href)) return;
        const hh = a.href.split('#')[0];
        let u; try { u = new URL(hh); } catch (e) { return; }
        if (u.hostname !== location.hostname || (S.blockAds && (isAdEl(a) || isAdUrl(hh)))) return;
        const p = patternFromUrl(hh);
        if (!p) return;
        if (!groups.has(p.pattern)) groups.set(p.pattern, new Map());
        groups.get(p.pattern).set(hh, 'Episode ' + m[1]);
      });
      let best = null;
      groups.forEach((g) => { if (g.size >= 3 && (!best || g.size > best.size)) best = g; });
      if (best) { out.length = 0; best.forEach((label, url) => { if (url !== here) out.push({ url, label }); }); }
    }
    // the page you are on is part of the list too (it was skipped above: it links to itself)
    const curP = patternFromUrl(here);
    if (curP && out.length >= 2 && !out.some((x) => x.url === here)) {
      const same = out.filter((x) => { const p = patternFromUrl(x.url); return p && p.pattern === curP.pattern; }).length;
      if (same >= Math.max(2, out.length * 0.6)) {
        // does the number in the address mean the episode number? (/ep-5 yes, ?id=9 maybe not)
        const agree = out.filter((x) => { const p = patternFromUrl(x.url); return p && epNumOf(x.label, '') === p.n; }).length >= out.length * 0.6;
        let n = curP.n;
        if (!agree) n = epNumOf(document.title || '', ''); // then the page title is the better source
        if (n != null && !out.some((x) => epNumOf(x.label, '') === n)) {
          const lab = (out.find((x) => /\d/.test(x.label)) || { label: '' }).label;
          const pre = /^(.*?\d+(?:\.\d+)?)/.exec(lab);
          out.push({ url: here, label: pre ? pre[1].replace(/(\d+(?:\.\d+)?)$/, String(n)) : 'Episode ' + n });
        }
      }
    }
    if (apiCh.size && apiShow === (location.pathname.split('/').filter(Boolean)[0] || '')) { // list from the site's own API: adds what the page does not show as links
      const have = new Set(out.map((x) => x.url)), haveN = new Set(out.map((x) => epNumOf(x.label, x.url)).filter((n) => n != null));
      let ad = 0; apiCh.forEach((c) => { if (!have.has(c.url) && !haveN.has(c.n)) { out.push({ url: c.url, label: c.label, n: c.n }); ad++; } }); apiAdded = ad;
    }
    if (out.length < 3 && siteChapters.length >= 3) return siteChapters.slice();
    if (out.length < 3) return [];
    const num = (x) => { if (x.n != null) return x.n; const m = x.label.match(/(\d+(?:\.\d+)?)(?!.*\d)/); return m ? parseFloat(m[1]) : NaN; };
    if (out.every((x) => !isNaN(num(x)))) out.sort((x, y) => num(x) - num(y));
    return out;
  }
  async function chapterImages(url) {
    const r = await fetch(url, { credentials: 'include' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
    let imgs = [...doc.querySelectorAll('img')].filter((im) => {
      const u = imgUrl(im, url);
      if (!u || adUrl(u)) return false;
      if (/logo|avatar|icon|emoji|sprite|banner|loading|placeholder|favicon|\/ads?\//i.test(u + ' ' + (im.getAttribute('class') || '') + ' ' + (im.id || ''))) return false;
      const w = +im.getAttribute('width') || 0;
      if (w && w < 250) return false;
      return !(S.blockAds && isAdEl(im));
    });
    const rule = new URL(url).hostname === location.hostname ? siteSel() : '';
    if (rule) { try { imgs = [...doc.querySelectorAll(rule)].filter((el) => el.tagName === 'IMG'); } catch (e) { imgs = mainCluster(imgs); } } else imgs = mainCluster(imgs);
    let urls = imgs.map((im) => imgUrl(im, url));
    if (urls.length < 2) { // some readers keep the page list in a script (JSON array)
      const found = new Set();
      doc.querySelectorAll('script:not([src])').forEach((sc) => {
        (sc.textContent.match(/https?:\\?\/\\?\/[^"'\s\\]+?\.(?:jpe?g|png|webp|avif)/gi) || []).forEach((x) => found.add(x.replace(/\\\//g, '/')));
      });
      const arr = [...found].filter((x) => !adUrl(x) && !/logo|avatar|icon|banner|thumb/i.test(x));
      if (arr.length > urls.length) urls = arr;
    }
    urls = Array.from(new Set(urls.filter(Boolean)));
    if (urls.length < 3) { // looks like one image per page: follow the page list / next links
      try { const multi = await multiPage(doc, url); if (multi.length > urls.length) urls = multi; } catch (e) { /* keep what we have */ }
    }
    return urls.map((found) => ({ found, orig: unresize(found), link: '' }));
  }
  const BAD_IMG = /logo|avatar|icon|emoji|sprite|banner|loading|placeholder|favicon|\/ads?\//i;
  function pageImg(doc, url) {
    const sels = ['#image-container img', 'img#image', '.reader img', '#reader img', '.viewer img', '#viewer img', '#content img', 'main img', 'article img', 'img'];
    const ok = (im) => { const u = imgUrl(im, url); return u && !adUrl(u) && !BAD_IMG.test(u + ' ' + (im.getAttribute('class') || '') + ' ' + (im.id || '')) && !(S.blockAds && isAdEl(im)); };
    for (const s of sels) for (const im of doc.querySelectorAll(s)) if (ok(im)) return imgUrl(im, url);
    const og = doc.querySelector('meta[property="og:image"]');
    return og && og.content ? abs(og.content, url) : null;
  }
  function selectPages(doc, url) { // <select> whose options are 1,2,3... or "Page 3" or "3 / 24"
    for (const sel of doc.querySelectorAll('select')) {
      const opts = [...sel.options].filter((o) => o.value && !/^(#|javascript)/i.test(o.value));
      if (opts.length < 3) continue;
      const numeric = opts.filter((o) => /^\s*(page|p\.?|\u0e2b\u0e19\u0e49\u0e32)?\s*\d+(\s*(\/|of)\s*\d+)?\s*$/i.test(o.textContent)).length;
      if (numeric < opts.length * 0.7) continue;
      const list = opts.map((o) => abs(o.value, url)).filter(Boolean).map((u) => u.split('#')[0]);
      if (list.every((u) => { try { return new URL(u).hostname === new URL(url).hostname; } catch (e) { return false; } })) return Array.from(new Set(list));
    }
    return [];
  }
  function nextPage(doc, url) {
    let el = doc.querySelector('a[rel~="next"], link[rel~="next"]');
    if (!el) el = [...doc.querySelectorAll('a[href]')].find((a) => /(^|[\s_-])next([\s_-]|$)/i.test((a.getAttribute('class') || '') + ' ' + (a.id || '')) && !/chapter|episode|\bch\b/i.test(a.textContent));
    const h = el && el.getAttribute('href');
    return h && !/^(#|javascript)/i.test(h) ? abs(h, url).split('#')[0] : null;
  }
  const chTok = (u) => { try { const m = new URL(u).pathname.match(/(chapter|chap|ch|episode|ep)[-_/]*(\d+(?:\.\d+)?)/i); return m ? m[2] : ''; } catch (e) { return ''; } };
  async function multiPage(doc0, url) {
    const getDoc = async (u) => { const r = await fetch(u, { credentials: 'include' }); if (!r.ok) throw new Error('HTTP ' + r.status); return new DOMParser().parseFromString(await r.text(), 'text/html'); };
    const out = [];
    const first = pageImg(doc0, url);
    const list = selectPages(doc0, url);
    if (list.length >= 3) { // 1) page dropdown: fetch all pages, 4 at a time
      const res = new Array(list.length);
      let next = 0;
      await Promise.all(Array.from({ length: 4 }, async () => {
        while (true) {
          const i = next++;
          if (i >= list.length) break;
          for (let t = 0; t < 2 && !res[i]; t++) { try { res[i] = list[i].split('#')[0] === url.split('#')[0] && first ? first : pageImg(await getDoc(list[i]), list[i]); } catch (e) { /* retry once */ } }
        }
      }));
      return Array.from(new Set(res.filter(Boolean)));
    }
    // 2) follow "next" links until the chapter changes / loops / 400 pages
    const seenPages = new Set([url.split('#')[0]]), tok = chTok(url);
    if (first) out.push(first);
    let cur = doc0, curUrl = url;
    for (let i = 0; i < 400; i++) {
      const n = nextPage(cur, curUrl);
      if (!n || seenPages.has(n)) break;
      try { if (new URL(n).hostname !== location.hostname) break; } catch (e) { break; }
      if (tok && chTok(n) && chTok(n) !== tok) break;
      seenPages.add(n);
      try { cur = await getDoc(n); } catch (e) { break; }
      curUrl = n;
      const im = pageImg(cur, n);
      if (!im || out.includes(im)) break;
      out.push(im);
    }
    if (out.length < 3) { // 3) numbered page URLs: .../page-1, .../page/1, ?page=1
      const m = url.match(/^(.*[?&](?:page|pg|p)=)(\d+)(.*)$/i) || url.match(/^(.*\/(?:page|pg|p)[-_]?)(\d+)(\/?(?:[?#].*)?)$/i);
      if (m) {
        for (let n = +m[2] + 1; n < +m[2] + 400; n++) {
          const u = m[1] + n + m[3];
          try { const d = await getDoc(u); const im = pageImg(d, u); if (!im || out.includes(im)) break; out.push(im); } catch (e) { break; }
        }
      }
    }
    return out;
  }
  // unfinished chapter queue survives a browser restart (storage.local)
  let pend = null;
  const here0 = () => location.href.split('#')[0];
  async function getPending() { try { const r = (await chrome.storage.local.get('mgPending')).mgPending || {}; return r[location.hostname] || null; } catch (e) { return null; } }
  async function setPending(urls, fmt) {
    try {
      const r = (await chrome.storage.local.get('mgPending')).mgPending || {};
      if (urls && urls.length) r[location.hostname] = { page: here0(), urls, fmt, t: Date.now() }; else delete r[location.hostname];
      await chrome.storage.local.set({ mgPending: r });
    } catch (e) { /* optional */ }
  }
  const pendCount = () => (pend && pend.page === here0() ? pend.urls.filter((u) => chapters.some((c) => c.url === u)).length : 0);

  // tags / artist / language ... from the page, for ComicInfo.xml (Komga, Kavita, Tachiyomi read it)
  function pageMeta() {
    const grab = (re) => {
      const out = new Set();
      document.querySelectorAll('a[href]').forEach((a) => {
        if (out.size >= 40 || !re.test(a.getAttribute('href') || '') || (S.blockAds && isAdEl(a))) return;
        const t = (a.textContent || '').replace(/\s+/g, ' ').replace(/\s*\(?\d[\d.,]*\s*[kKmM]?\)?\s*$/, '').trim();
        if (t && t.length < 40) out.add(t);
      });
      return [...out];
    };
    const md = document.querySelector('meta[property="og:description"],meta[name="description"]');
    return {
      tags: grab(/\/tags?\//i), artists: grab(/\/artists?\//i), authors: grab(/\/authors?\//i), groups: grab(/\/(groups?|circles?)\//i),
      parodies: grab(/\/(parod(y|ies)|series)\//i), characters: grab(/\/characters?\//i), languages: grab(/\/languages?\//i),
      summary: md && md.content ? md.content.slice(0, 600) : ''
    };
  }

  async function toggleFollow(root) {
    try {
      const r = (await chrome.storage.local.get('mgFollow')).mgFollow || {}, k = here0();
      if (r[k]) { delete r[k]; toast(root, 'Unfollowed', 2500); }
      else {
        r[k] = { url: k, title: (S.cleanTitles !== false ? smartSeries(document.title || 'manga', location.hostname, siteNamesHere()) : seriesName(document.title || 'manga')) || k, host: location.hostname, seen: chapters.map((c) => c.url), unread: [], checked: Date.now(), added: Date.now() };
        toast(root, S.followHours ? `Following: you get a notification for new chapters (checked every ${S.followHours} h)` : 'Following, but checking is off (Settings: "Check followed manga every")', 4500);
      }
      await chrome.storage.local.set({ mgFollow: r });
    } catch (e) { toast(root, 'Could not save'); }
  }

  async function runChapters(root, mode, only) {
    let picks = only ? chapters.filter((c) => c.url === only) : chapters.filter((c) => chSel.has(c.url));
    if (!picks.length) return toast(root, 'Select chapters first');
    if (chRunning) return toast(root, 'A download is already running');
    if (mode !== 'read' && S.skipDone && picks.length > 1) {
      const before = picks.length;
      picks = picks.filter((c) => !doneCh.has(c.url));
      if (!picks.length) return toast(root, `All ${before} are already downloaded (untick "Skip chapters already downloaded" in Settings to force)`, 6000);
      if (picks.length < before) toast(root, `Skipping ${before - picks.length} already downloaded`, 3000);
    }
    chRunning = true; chStop = false;
    if (mode !== 'read') picks.forEach((p) => { chState[p.url] = 'queued'; });
    renderPill();
    if (mode === 'read') picks = picks.slice(0, 1);
    const book = (S.cleanTitles !== false ? smartSeries(document.title || 'manga', location.hostname, siteNamesHere()) : seriesName(document.title || 'manga')) || 'manga';
    const fmt = siteFormat(S.siteFormats, location.hostname) || 'cbz';
    const bookMeta = pageMeta();
    chProg = { i: 0, n: picks.length, label: '' };
    let left = picks.map((p) => p.url);
    if (mode !== 'read') setPending(left, fmt);
    let ok = 0, fail = 0, miss = 0;
    for (let i = 0; i < picks.length; i++) {
      const c = picks[i];
      if (chStop) break;
      if (i > 0 && S.requestDelayMs) await new Promise((r) => setTimeout(r, S.requestDelayMs));
      chState[c.url] = 'run'; chProg = { i: i + 1, n: picks.length, label: c.label }; renderPill();
      toast(root, `Chapter ${i + 1}/${picks.length}: ${c.label}`, 6000);
      const cLabel = S.padChapters ? padNumbers(c.label) : c.label;
      try {
        const adTask = S.siteAdapters !== false && MG_SITES.match(c.url, true);
        let images = [];
        if (adTask) { try { images = await chapterImages(c.url); } catch (e) { /* the adapter will find the pages */ } }
        else images = await chapterImages(c.url);
        if (!images.length && !adTask) throw new Error('no images');
        const wait = mode !== 'read';
        const res = await Promise.race([
          send({ type: 'open-manga', images, task: adTask ? { name: adTask.name, url: c.url } : null, title: book + ' - ' + cLabel, book, chapter: cLabel, index: i + 1, meta: bookMeta, ref: c.url, auto: wait ? fmt : 'read', autoClose: wait, wait }),
          new Promise((r) => setTimeout(() => r(null), 6 * 60 * 1000))
        ]);
        if (wait && !(res && res.ok)) throw new Error('not saved');
        miss += (res && res.missing) || 0;
        if (wait) { doneCh.add(c.url); try { chrome.storage.local.set({ mgDone: [...doneCh].slice(-5000) }); } catch (e) { /* ignore */ } }
        ok++; chState[c.url] = 'done'; left = left.filter((u) => u !== c.url); if (mode !== 'read') setPending(left, fmt); renderPill();
      } catch (e) { if (!c._retry) { c._retry = true; i--; continue; } chState[c.url] = 'fail'; fail++; dbg('chapter', `${c.label}: ${e.message}`); renderPill(); }
    }
    picks.forEach((p) => { if (chState[p.url] === 'queued' || chState[p.url] === 'run') delete chState[p.url]; });
    chRunning = false; chProg = null; if (mode !== 'read') { await setPending(left, fmt); pend = await getPending(); } renderPill();
    if (ok) send({ type: 'notify', title: 'Media Grabber', text: `${ok} chapter(s) saved` + (fail ? `, ${fail} failed` : '') + (miss ? `, ${miss} pages missing` : '') });
    toast(root, (chStop ? 'Stopped: ' : 'Done: ') + `${ok} ok, ${fail} failed` + (miss ? `, ${miss} pages missing` : ''), 6000);
  }
  function collectImages() {
    const out = [], seen = new Set();
    const add = (found, link) => { if (!found || seen.has(found)) return; seen.add(found); out.push({ found, orig: unresize(found), link: link || '' }); };
    // a thumbnail that links to another page (gallery -> viewer page) can be resolved to the full image later
    const linkOf = (img) => { const a = img.closest('a[href]'); const h = a && a.href; return h && /^https?:/i.test(h) && !/\.(jpe?g|png|webp|gif|avif)(\?|$)/i.test(h) && h.split('#')[0] !== location.href.split('#')[0] ? h : ''; };
    candImages().forEach((img) => add(imgUrl(img), linkOf(img)));
    const fills = galleryFillList();
    if (fills.length) {
      fills.forEach((f) => { if (!seen.has(f.url)) { seen.add(f.url); out.push({ found: f.url, orig: f.url, link: f.url, filled: true }); } });
      if (out.every((x) => viewerNum(x.link))) out.sort((a, b) => viewerNum(a.link) - viewerNum(b.link));
      dbg('manga', `gallery: ${out.length - fills.length} thumbnails on the page + ${fills.length} pages filled in from the page's total (${expectedPages()})`);
    }
    document.querySelectorAll('[style*="url("]').forEach((el) => {
      const r = el.getBoundingClientRect(); const m = /url\(["']?([^"')]+)/.exec(el.getAttribute('style'));
      if (m && r.width >= 250 && r.height >= 250 && !/^data:/.test(m[1])) add(abs(m[1]));
    });
    return out;
  }
  const MORE_RE = /^(?:show|view|load|see|display)\s*(?:all|more)(?:\s+(?:pages|thumbs|thumbnails|images|chapters))?\.?$|^more\s+(?:pages|thumbnails|thumbs|images)$|^all\s+pages$/i;
  async function expandAll() { // "Show all" / "Load more" buttons that only reveal part of the page list
    let clicked = 0;
    for (let round = 0; round < 8; round++) {
      const before = document.images.length;
      const els = [...document.querySelectorAll('button, [role="button"], a[href^="#"], a[href^="javascript"], .load-more, .show-all')].filter((el) => {
        const t = (el.textContent || '').replace(/\s+/g, ' ').trim();
        return t.length < 40 && MORE_RE.test(t) && el.offsetParent !== null && !el.disabled;
      });
      if (!els.length) break;
      els.slice(0, 2).forEach((el) => { try { el.click(); clicked++; } catch (e) { /* ignore */ } });
      await new Promise((r) => setTimeout(r, 900));
      if (document.images.length <= before && round > 0) break;
    }
    if (clicked) dbg('manga', `pressed ${clicked} "show all / load more" button(s)`);
  }
  async function autoScroll() { // trigger lazy loading, then come back
    const y0 = scrollY;
    let last = -1;
    for (let i = 0; i < 120; i++) {
      scrollBy(0, Math.round(innerHeight * 0.8));
      await new Promise((r) => setTimeout(r, 140));
      if (scrollY === last) break;
      last = scrollY;
    }
    await new Promise((r) => setTimeout(r, 500));
    scrollTo(0, y0);
  }
  async function startManga(how, root) {
    const ad = S.siteAdapters !== false && MG_SITES.match(location.href, true);
    toast(root, ad ? `Reading the ${ad.label} page list...` : 'Loading all pages...');
    await expandAll();
    if (!ad) await autoScroll(); // adapter pages do not need it; the pages screen scrolls later only if the adapter fails
    let images = collectImages();
    if (!images.length && !ad) {
      const g = MG_SITES.genericPages(document.documentElement.innerHTML, location.href); // images hidden in page scripts / JSON
      if (g.length) images = g;
    }
    if (!images.length && !ad) return toast(root, 'No page images found');
    if (how === 'cbz') how = siteFormat(S.siteFormats, location.hostname) || 'cbz'; // per-site default (Alt+Shift+M / CBZ button)
    send({ type: 'open-manga', images, task: ad ? { name: ad.name, url: location.href.split('#')[0] } : null, title: document.title, meta: pageMeta(), ref: location.href, auto: how, expected: expectedPages() });
  }


  // ================= helpers for the pages screen (fetch / canvas / screenshot from the real page) =================
  const toDataUrl = (blob) => new Promise((res, rej) => { const f = new FileReader(); f.onload = () => res(f.result); f.onerror = rej; f.readAsDataURL(blob); });
  function findImg(url) {
    const norm = (u) => { try { return new URL(u, location.href).href; } catch (e) { return u; } };
    return [...document.images].find((i) => [imgUrl(i), i.currentSrc, i.src].some((x) => x && (norm(x) === url || unresize(norm(x)) === url)));
  }
  async function canvasBlob(img) {
    const draw = (im) => { const cv = document.createElement('canvas'); cv.width = im.naturalWidth; cv.height = im.naturalHeight; cv.getContext('2d').drawImage(im, 0, 0); return new Promise((r) => cv.toBlob(r, 'image/png')); };
    try { if (img.complete && img.naturalWidth) { const b = await draw(img); if (b) return b; } } catch (e) { /* tainted, retry with CORS */ }
    const im = new Image(); im.crossOrigin = 'anonymous';
    await new Promise((res, rej) => { im.onload = res; im.onerror = () => rej(new Error('cors-load')); im.src = img.currentSrc || img.src; });
    return draw(im);
  }
  chrome.runtime.onMessage.addListener((msg, sender, respond) => {
    if (msg && !IS_TOP && (msg.type === 'fetch-text-frame' || msg.type === 'fetch-bin-frame')) { // V77a: the video player usually lives in a cross-origin iframe; only a frame that can really read the url answers (a failure stays silent so another frame can win)
      fetch(msg.url, { credentials: 'include', ...(msg.range ? { headers: { Range: msg.range } } : {}) }).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return msg.type === 'fetch-text-frame' ? r.text().then((text) => ({ ok: true, text })) : r.blob().then(toDataUrl).then((d) => ({ ok: true, b64: String(d).split(',')[1] || '' })); })
        .then((x) => respond(x)).catch(() => respond(null));
      return true;
    }
    if (!msg || !IS_TOP) return;
    if (msg.type === 'mg-fetch') {
      fetch(msg.url, { credentials: 'include' }).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); })
        .then(toDataUrl).then((data) => respond({ ok: true, data })).catch((e) => respond({ ok: false, err: String(e.message || e) }));
      return true;
    }
    if (msg.type === 'fetch-text') {
      fetch(msg.url, { credentials: 'include' }).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })
        .then((text) => respond({ ok: true, text })).catch((e) => respond({ ok: false, err: String(e.message || e) }));
      return true;
    }
    if (msg.type === 'mg-canvas') {
      const img = findImg(msg.url);
      if (!img) { respond({ ok: false, err: 'image element not found' }); return; }
      canvasBlob(img).then(toDataUrl).then((data) => respond({ ok: true, data })).catch((e) => respond({ ok: false, err: 'canvas blocked (' + (e.message || e) + ')' }));
      return true;
    }
    if (msg.type === 'mg-collect') { // fallback for the pages screen: scroll the real page so lazy images load, then list them
      expandAll().then(() => autoScroll()).then(() => respond({ ok: true, images: collectImages() })).catch((e) => respond({ ok: false, err: String(e.message || e) }));
      return true;
    }
    if (msg.type === 'mg-restore') { [pillHost, ovHost].forEach((h) => h && (h.style.visibility = '')); return; }
    if (msg.type === 'mg-pos') { // scroll so the element's y-offset is at the top of the viewport, report its rectangle
      const img = findImg(msg.url);
      if (!img) { respond({ ok: false, err: 'image element not found' }); return; }
      [pillHost, ovHost].forEach((h) => h && (h.style.visibility = 'hidden')); // keep our own UI out of screenshots
      const absTop = img.getBoundingClientRect().top + scrollY;
      scrollTo(0, Math.max(0, absTop + (msg.offset || 0)));
      setTimeout(() => {
        const r = img.getBoundingClientRect();
        respond({ ok: true, top: r.top, left: r.left, width: r.width, height: r.height, vh: innerHeight, dpr: devicePixelRatio });
      }, 350);
      return true;
    }
  });

  // ================= state =================
  async function refresh() {
    const r = await send({ type: 'get-state' });
    if (!r) return;
    rec = r.rec; S = r.settings;
    renderAll();
  }

  function toast(shadow, t, ms = 1200) {
    const el = shadow.querySelector('.toast');
    if (!el) return;
    el.textContent = t; el.classList.add('on');
    setTimeout(() => el.classList.remove('on'), ms);
  }

  function copyText(t) {
    try { if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(t); } catch (e) { /* fallback */ }
    const ta = document.createElement('textarea');
    ta.value = t; ta.style.cssText = 'position:fixed;opacity:0';
    document.body.append(ta); ta.select(); document.execCommand('copy'); ta.remove();
  }

  // ================= shared UI bits =================
  const CSS = `
  :host{all:initial}
  *{box-sizing:border-box;font-family:Inter,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-font-smoothing:antialiased}
  .wrap{position:fixed;inset:0;pointer-events:none;z-index:2147483647;color:#eef0f6;
    --a:#5b8dff;--b:#8b5cff;--ok:#3fd07f;--err:#ff6b85;--tx:#eef0f6;--mu:#98a1b3;--ln:rgba(255,255,255,.09);--ease:cubic-bezier(.2,.8,.2,1)}
  .wrap>*{pointer-events:auto}
  button{font:inherit;font-size:12.5px;font-weight:600;cursor:pointer;border:0;color:var(--tx);background:rgba(255,255,255,.09);border-radius:12px;padding:8px 13px;line-height:1.1;display:inline-flex;align-items:center;justify-content:center;gap:6px;transition:background .18s var(--ease),transform .12s var(--ease),box-shadow .18s,filter .18s;-webkit-tap-highlight-color:transparent}
  button:hover{background:rgba(255,255,255,.17)}
  button:active{transform:scale(.95)}
  button:focus-visible{outline:2px solid var(--a);outline-offset:2px}
  button.p{background:linear-gradient(135deg,var(--a),var(--b));box-shadow:0 6px 18px -6px rgba(91,141,255,.7)}
  button.p:hover{filter:brightness(1.12)}
  svg{width:16px;height:16px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;flex:none}
  @media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
  `;
  const IC = {
    dl: '<svg viewBox="0 0 24 24"><path d="M12 3v12m0 0l-5-5m5 5l5-5M4 20h16"/></svg>',
    play: '<svg viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><path d="M8 5.5v13a1 1 0 001.5.86l10.5-6.5a1 1 0 000-1.72L9.5 4.64A1 1 0 008 5.5z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><rect x="6" y="5" width="4.2" height="14" rx="1.2"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.2"/></svg>',
    bolt: '<svg viewBox="0 0 24 24"><path d="M13 2L4 14h7l-1 8 9-12h-7z"/></svg>',
    all: '<svg viewBox="0 0 24 24"><path d="M12 3v10m0 0l-4-4m4 4l4-4M5 17h14M7 21h10"/></svg>',
    refresh: '<svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 10-2.3 5.7M20 4v7h-7"/></svg>',
    gear: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3h0a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5h0a1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8v0a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    more: '<svg viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
  };
  const ICON = IC.dl;

  // ================= floating pill + panel (top frame) =================
  let pillHost, pillRoot, open = false;

  function ensurePill() {
    if (pillHost || !IS_TOP) return;
    pillHost = document.createElement('div');
    pillHost.setAttribute('data-mgb-pill', '1'); // MGB S54: lets the native video button hide while this button shows
    pillHost.style.cssText = 'all:initial';
    pillRoot = pillHost.attachShadow({ mode: 'open' });
    pillRoot.innerHTML = `<style>${CSS}${typeof MG_TOKENS === 'string' ? MG_TOKENS : ''}
      /* ---- floating button ---- */
      .pill{position:fixed;width:48px;height:48px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;cursor:grab;user-select:none;touch-action:none;
        background:linear-gradient(135deg,#5b8dff,#8b5cff);box-shadow:0 10px 28px -8px rgba(91,110,255,.75),0 0 0 1px rgba(255,255,255,.18) inset;
        opacity:.94;transition:opacity .2s,transform .25s var(--ease),box-shadow .25s;animation:pop .45s var(--ease) both}
      .pill svg{width:22px;height:22px;stroke-width:2.3}
      .pill:hover{opacity:1;transform:scale(1.08);box-shadow:0 14px 34px -8px rgba(91,110,255,.9),0 0 0 1px rgba(255,255,255,.25) inset}
      .pill:active{transform:scale(.95)}
      .pill.r{right:14px}.pill.l{left:14px}
      .pill .n{position:absolute;top:-5px;right:-5px;min-width:20px;height:20px;border-radius:10px;background:#ff4d6d;border:2px solid #14161d;font-size:11px;line-height:16px;text-align:center;padding:0 4px;font-weight:700;color:#fff}
      .pill.busy::after{content:"";position:absolute;inset:-5px;border-radius:50%;border:2px solid rgba(139,92,255,.7);animation:ring 1.4s ease-out infinite}
      .pill.busy svg{animation:bob 1s ease-in-out infinite}
      @keyframes ring{from{opacity:.9;transform:scale(.88)}to{opacity:0;transform:scale(1.35)}}
      @keyframes bob{50%{transform:translateY(2px)}}
      @keyframes pop{from{opacity:0;transform:scale(.6)}to{opacity:.94;transform:none}}
      /* ---- mini progress card ---- */
      .mini{position:fixed;display:none;min-width:170px;max-width:250px;padding:10px 12px;border-radius:14px;background:rgba(20,22,29,.88);backdrop-filter:blur(16px) saturate(1.4);border:1px solid var(--ln);color:var(--tx);font-size:12px;font-weight:600;box-shadow:0 14px 34px -10px rgba(0,0,0,.6);cursor:pointer;transform:translateY(-8px)}
      .mini.on{display:block;animation:slide .25s var(--ease) both}.mini.r{right:72px}.mini.l{left:72px}
      @keyframes slide{from{opacity:0;margin-left:8px}to{opacity:1;margin-left:0}}
      .mini .t{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-bottom:7px}
      .bar{height:6px;border-radius:6px;background:rgba(255,255,255,.1);overflow:hidden}
      .bar i{display:block;height:100%;width:0;border-radius:6px;background:linear-gradient(90deg,#5b8dff,#8b5cff,#5b8dff);background-size:200% 100%;animation:shine 2s linear infinite;transition:width .4s var(--ease)}
      .paused .bar i{animation:none;background:#f5b94a}
      @keyframes shine{from{background-position:200% 0}to{background-position:-200% 0}}
      /* ---- panel ---- */
      .panel{position:fixed;top:50%;transform:translateY(-50%);width:min(420px,calc(100vw - 96px));max-height:82vh;display:none;flex-direction:column;overflow:hidden;font-size:13px;color:var(--tx);
        background:linear-gradient(180deg,rgba(28,31,41,.94),rgba(16,18,24,.96));backdrop-filter:blur(22px) saturate(1.5);
        border:1px solid var(--ln);border-radius:24px;box-shadow:0 30px 80px -10px rgba(0,0,0,.7),0 0 0 1px rgba(91,141,255,.07)}
      .panel.on{display:flex;animation:pin .3s var(--ease) both}.panel.r{right:74px}.panel.l{left:74px}
      @keyframes pin{from{opacity:0;transform:translateY(-47%) scale(.96)}to{opacity:1;transform:translateY(-50%) scale(1)}}
      .hd{display:flex;align-items:center;gap:9px;padding:16px 16px 8px}
      .hd b{font-size:16px;font-weight:750;letter-spacing:-.015em;white-space:nowrap}
      .hd .c{background:rgba(255,255,255,.08);color:var(--mu);border-radius:99px;padding:2px 9px;font-size:11px;font-weight:700}.hd .c:empty{display:none}
      .sp{flex:1}
      .lg{width:11px;height:11px;border-radius:50%;background:linear-gradient(135deg,#5b8dff,#8b5cff);box-shadow:0 0 14px #5b8dff;flex:none}
      button.ic{padding:7px;width:32px;height:32px;background:transparent;color:var(--mu);border-radius:10px}button.ic:hover{background:rgba(255,255,255,.1);color:#fff}
      .tb{display:flex;gap:7px;padding:6px 16px 10px;align-items:center}.tb button{flex:none;white-space:nowrap;padding:9px 13px}
      button.ps{flex:1;background:rgba(91,141,255,.14);color:#a9c3ff;box-shadow:inset 0 0 0 1px rgba(91,141,255,.4)}button.ps:hover{background:rgba(91,141,255,.26)}
      .pg{padding:0 18px 4px;color:var(--mu);font-size:12px;line-height:1.35;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
      .sm{padding:2px 16px 8px;display:flex;gap:6px;flex-wrap:wrap;font-size:11px}
      .chip{background:rgba(255,255,255,.07);color:#c5cbd8;padding:3px 10px;border-radius:99px;font-weight:600}
      .list{overflow-y:auto;padding:4px 12px 12px;scroll-behavior:smooth;overscroll-behavior:contain}
      .list::-webkit-scrollbar{width:6px}.list::-webkit-scrollbar-thumb{background:rgba(255,255,255,.14);border-radius:6px}
      .row{display:flex;gap:12px;padding:12px;margin:8px 0;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.06);border-radius:18px;animation:rin .3s var(--ease) both;transition:border-color .2s,background .2s,transform .2s var(--ease)}
      .row:hover{border-color:rgba(91,141,255,.5);background:rgba(255,255,255,.06)}
      @keyframes rin{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
      .th{flex:none;width:76px;height:76px;border-radius:14px;background:linear-gradient(135deg,#2b3040,#1f232e) center/cover;display:flex;align-items:center;justify-content:center;color:#8b94a3;font-size:16px;font-weight:700;box-shadow:inset 0 0 0 1px rgba(255,255,255,.06)}
      .mn{flex:1;min-width:0}
      .nm{font-size:14px;font-weight:700;line-height:1.3;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
      .mt{display:flex;flex-wrap:wrap;gap:6px;margin:5px 0 9px;align-items:center;font-size:11px;color:var(--mu)}
      .tag{background:rgba(91,141,255,.18);color:#a9c3ff;border-radius:7px;padding:2px 7px;font-size:10px;letter-spacing:.05em;font-weight:800}
      .tag.w{background:rgba(255,107,133,.16);color:#ff9db0}
      .ac{display:flex;gap:6px;flex-wrap:wrap}.ac button{padding:8px 13px}
      .more{display:none;gap:5px;flex-wrap:wrap;margin-top:8px}.more.on{display:flex;animation:rin .2s var(--ease) both}.more button{font-size:11.5px;padding:6px 10px;background:rgba(255,255,255,.06)}.more input.tm{background:rgba(255,255,255,.06);color:inherit;border:1px solid rgba(255,255,255,.14);border-radius:8px;padding:5px 8px;font:inherit;font-size:11.5px}
      .pr{margin-top:9px;display:none}.pr.on{display:block}
      .pt{font-size:11.5px;color:var(--mu);margin-top:6px;display:flex;justify-content:space-between;align-items:center;gap:8px;font-variant-numeric:tabular-nums}
      .pt .t{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .pt.err{color:var(--err)}.pt.ok{color:var(--ok)}
      .pb{display:flex;gap:4px;flex:none}
      button.ib{width:28px;height:28px;padding:0;border-radius:50%;background:rgba(255,255,255,.1)}button.ib svg{width:13px;height:13px}
      button.ib.pz{background:rgba(91,141,255,.25);color:#cfe0ff}
      .empty{padding:34px 18px;text-align:center;color:var(--mu);line-height:1.6}
      .ft{padding:10px 16px;background:rgba(0,0,0,.2);color:#6f7889;font-size:11px;display:flex;justify-content:space-between}
      .ft a{color:#9db9ff;cursor:pointer}.ft a:hover{text-decoration:underline}
      .tabs{display:flex;gap:3px;padding:4px;margin:2px 0 8px;background:rgba(255,255,255,.05);border-radius:15px;position:sticky;top:0;z-index:3;backdrop-filter:blur(12px)}
      .tabs button{flex:1;background:transparent;color:var(--mu);padding:7px 8px;border-radius:11px}
      .tabs button small{opacity:.7;margin-left:3px}
      .tabs button.on{background:rgba(91,141,255,.24);color:#fff;box-shadow:inset 0 0 0 1px rgba(91,141,255,.5)}
      .sec{display:flex;align-items:center;gap:7px;margin:14px 4px 4px;font-size:11px;font-weight:800;letter-spacing:.09em;text-transform:uppercase;color:var(--mu);cursor:pointer;user-select:none}
      .sec small{background:rgba(255,255,255,.08);border-radius:99px;padding:0 8px;letter-spacing:0}.sec i{margin-left:auto;font-style:normal;font-size:13px}
      .pair{margin:-2px 4px 4px;padding:8px 12px;border-radius:13px;font-size:12px;color:#9fe3b6;background:rgba(63,208,127,.1)}
      .row.cm{padding:10px 12px;margin:5px 0;border-radius:14px;gap:8px}
      .row.cm .mn{display:flex;flex-direction:column}.row.cm .nm{font-size:13px}.row.cm .mt{margin:2px 0 7px}.row.cm .ac button{padding:6px 12px}
      .qs{display:flex;flex-direction:column;gap:6px;width:100%}
      .q{display:flex;align-items:center;gap:10px;width:100%;text-align:left;padding:10px 13px;border-radius:13px;background:rgba(255,255,255,.06);box-shadow:inset 0 0 0 1px rgba(255,255,255,.05)}
      .q:hover{background:rgba(91,141,255,.2);box-shadow:inset 0 0 0 1px rgba(91,141,255,.55)}
      .q b{font-size:14px;min-width:50px}.q .qn{flex:1;font-size:12px;opacity:.7;font-weight:500}
      .q .qz{font-size:12px;opacity:.85;font-variant-numeric:tabular-nums}.q .qd{font-size:15px;opacity:.9;display:flex}
      .q em{font-style:normal;font-size:9.5px;font-weight:800;letter-spacing:.06em;padding:2px 6px;border-radius:6px;background:rgba(255,255,255,.2)}
      .q em.g{background:rgba(63,208,127,.22);color:#9fe3b6}
      .chb{position:sticky;top:0;z-index:2;display:flex;gap:5px;flex-wrap:wrap;background:rgba(20,22,29,.96);padding:6px 0}
      .chb button{padding:6px 10px;font-size:12px}
      .ch{display:flex;gap:9px;align-items:center;padding:7px 10px;border-radius:11px;background:rgba(255,255,255,.04);margin:4px 0;cursor:pointer;transition:background .15s}.ch:hover{background:rgba(255,255,255,.08)}
      .ch input{accent-color:#5b8dff;width:15px;height:15px}.ch span{flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
      .toast{position:absolute;left:50%;bottom:44px;transform:translateX(-50%) translateY(6px);background:rgba(8,9,12,.92);border:1px solid var(--ln);padding:7px 14px;border-radius:12px;font-size:12px;font-weight:600;opacity:0;transition:.25s var(--ease);pointer-events:none;white-space:nowrap}.toast.on{opacity:1;transform:translateX(-50%)}
      /* ---- v28: tabs, cards, downloads, batch ---- */
      .seg{display:flex;gap:4px;margin:0 16px 8px;padding:4px;background:rgba(255,255,255,.05);border-radius:14px}
      .seg button{flex:1;background:transparent;color:var(--mu);padding:8px 10px;border-radius:10px}
      .seg button small{background:rgba(255,255,255,.1);border-radius:99px;padding:1px 7px;font-size:10.5px;margin-left:2px}.seg button small:empty{display:none}
      .seg button.on{background:rgba(91,141,255,.22);color:#fff;box-shadow:inset 0 0 0 1px rgba(91,141,255,.5)}
      .seg button.on small{background:rgba(91,141,255,.45)}
      .row.v{padding:11px;gap:12px}
      .row.v .th{position:relative;width:112px;height:66px;border-radius:12px;flex:none;overflow:hidden}
      .th .dur{position:absolute;right:5px;bottom:5px;background:rgba(0,0,0,.72);color:#fff;font-size:10.5px;font-weight:700;padding:1px 6px;border-radius:6px;font-variant-numeric:tabular-nums}
      .th .ty{position:absolute;left:5px;top:5px;background:rgba(91,141,255,.9);color:#fff;font-size:9.5px;font-weight:800;letter-spacing:.05em;padding:1px 6px;border-radius:6px}
      .th .pl{display:flex;opacity:.85}
      .dlr{display:flex;gap:6px;align-items:stretch;width:100%}
      .sel{position:relative;flex:1;min-width:104px;display:flex}
      .sel select{appearance:none;-webkit-appearance:none;width:100%;font:inherit;font-size:12px;font-weight:600;color:var(--tx);background:rgba(255,255,255,.08);border:0;border-radius:12px;padding:9px 28px 9px 11px;cursor:pointer;text-overflow:ellipsis;box-shadow:inset 0 0 0 1px rgba(255,255,255,.06)}
      .sel select:hover{background:rgba(255,255,255,.13)}
      .sel select option{background:#1b1e27;color:#eef0f6}
      .sel::after{content:"";position:absolute;right:11px;top:50%;width:6px;height:6px;border-right:2px solid var(--mu);border-bottom:2px solid var(--mu);transform:translateY(-70%) rotate(45deg);pointer-events:none}
      .dlr .dlb{flex:none;padding:9px 14px}
      .dlr .ic2{flex:none;width:36px;padding:0;background:rgba(255,255,255,.07)}
      .empty{display:flex;flex-direction:column;align-items:center;gap:8px}.empty svg{width:34px;height:34px;opacity:.55;margin-bottom:4px}.empty b{color:var(--tx);font-size:14px}.empty span{max-width:290px}.empty button{margin-top:8px}
      .jh{display:flex;align-items:center;justify-content:space-between;color:var(--mu);font-size:12px;padding:2px 4px 4px;font-weight:600}
      button.ghost{background:transparent;color:#9db9ff;padding:5px 8px;font-size:12px}button.ghost:hover{background:rgba(255,255,255,.08)}
      .jr{padding:12px 13px;margin:7px 0;background:rgba(255,255,255,.045);border:1px solid rgba(255,255,255,.06);border-radius:16px;animation:rin .28s var(--ease) both}
      .jr.run{border-color:rgba(91,141,255,.4)}.jr.paused{border-color:rgba(245,185,74,.45)}
      .jt{display:flex;align-items:center;gap:8px;margin-bottom:9px}.jt b{flex:1;min-width:0;font-size:13px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .jt .jp{font-weight:800;font-variant-numeric:tabular-nums;font-size:13px}
      .jr.done .bar i{animation:none;background:var(--ok)}.jr.error .bar i{animation:none;background:var(--err)}
      .bh{position:sticky;top:0;z-index:4;background:linear-gradient(180deg,rgba(24,26,34,.98),rgba(24,26,34,.92));padding:2px 0 8px;margin:0 -2px;backdrop-filter:blur(10px)}
      .bt{display:flex;align-items:center;gap:8px;padding:2px 0 8px}.bt b{flex:1;font-size:14px}.bt .cn2{background:rgba(91,141,255,.2);color:#b7ccff;border-radius:99px;padding:2px 10px;font-size:11.5px;font-weight:700}
      .srch{width:100%;font:inherit;font-size:13px;color:var(--tx);background:rgba(255,255,255,.07);border:0;border-radius:12px;padding:9px 12px;outline:none;box-shadow:inset 0 0 0 1px rgba(255,255,255,.07)}
      .srch:focus{box-shadow:inset 0 0 0 1px rgba(91,141,255,.7)}.srch::placeholder{color:#7d8696}
      .bq{display:flex;gap:5px;flex-wrap:wrap;margin-top:8px;align-items:center}.bq button{padding:5px 10px;font-size:11.5px;border-radius:99px;background:rgba(255,255,255,.07)}
      .bq .hint{color:#6f7889;font-size:11px;margin-left:auto}
      .mw{margin:6px 0 8px;font-size:12.5px}.mw summary{cursor:pointer;color:#9aa6bd;padding:5px 2px;user-select:none}.mw summary:hover{color:#dfe6f5}
      .mw .mwr{display:flex;gap:6px;margin-top:6px;align-items:center}.mw .mwr .srch{flex:1;min-width:0}.mw textarea.srch{margin-top:6px;resize:vertical;min-height:56px;font-family:inherit;line-height:1.35}
      .mw .mwr button{padding:7px 12px;font-size:12px;border-radius:99px;white-space:nowrap}.mw .tip{color:#6f7889;font-size:11px;margin-top:5px}
      .ch{padding:8px 10px;border-radius:12px}.ch .cl{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .ch .bg{flex:none}
      .bg{font-size:10.5px;font-weight:800;letter-spacing:.03em;padding:2px 8px;border-radius:99px;background:rgba(255,255,255,.08);color:var(--mu);white-space:nowrap;font-variant-numeric:tabular-nums}
      .bg.run{background:rgba(91,141,255,.2);color:#b7ccff}.bg.ok{background:rgba(63,208,127,.16);color:#7fe2a9}.bg.err{background:rgba(255,107,133,.16);color:#ff9db0}.bg.warn{background:rgba(245,185,74,.18);color:#f5c76a}
      .bf{position:sticky;bottom:0;z-index:4;padding:10px 0 2px;margin:8px -2px 0;background:linear-gradient(0deg,rgba(20,22,29,.99) 70%,rgba(20,22,29,0))}
      .bf .r0{display:flex;margin-bottom:6px}.bf .r0 .sel{flex:1}.bf .r0 select:disabled{opacity:.45;cursor:default}
      .bf .r1{display:flex;gap:6px;align-items:stretch}.bf .r1 .sel{flex:none;width:92px}.bf .r1 button{flex:1;padding:10px 12px}
      .bf .r2{display:flex;gap:2px;flex-wrap:wrap;justify-content:center;margin-top:6px}
      @media (pointer:coarse){button{min-height:40px}.tb button{min-height:42px}.row:not(.v) .th{width:64px;height:64px}button.ib{width:36px;height:36px}}


      /* ---- v30: compact download queue + small hints ---- */
      .jh{display:flex;align-items:center;justify-content:space-between;margin:10px 4px 2px;font-size:11px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--mu)}
      .jh em{font-style:normal;background:rgba(255,255,255,.09);border-radius:99px;padding:0 7px;margin-left:5px;letter-spacing:0}
      .jhb{display:flex;gap:2px;text-transform:none;letter-spacing:0}
      .jc{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:0 8px;margin:4px 0;padding:8px 8px 0 12px;border-radius:14px;background:rgba(255,255,255,.045);border:1px solid rgba(255,255,255,.06);overflow:hidden;transition:border-color .2s}
      .jc.run{border-color:rgba(91,141,255,.35)}.jc.paused{border-color:rgba(245,185,74,.4)}.jc.error{border-color:rgba(255,107,133,.35)}
      .jn{font-size:13px;font-weight:650;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
      .js{display:flex;gap:6px;align-items:baseline;font-size:11.5px;color:var(--mu);min-width:0;margin-top:1px}
      .js b{font-variant-numeric:tabular-nums;color:var(--tx);font-weight:750}
      .js span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
      .jc.error .js span{color:var(--err)}.jc.done .js span{color:var(--ok)}.jc.paused .js span{color:#f5c76a}
      .jx{display:flex;gap:4px}
      button.jb{width:32px;height:32px;min-height:32px;padding:0;border-radius:50%;background:rgba(255,255,255,.08)}
      button.jb svg{width:14px;height:14px}
      button.jb:hover{background:rgba(91,141,255,.3)}
      .jbar{grid-column:1/-1;height:3px;margin:8px -8px 0 -12px;background:rgba(255,255,255,.07)}
      .jbar i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--a),var(--b));transition:width .45s var(--ease)}
      .jc.paused .jbar i{background:#f5b94a}.jc.done .jbar i{background:var(--ok)}.jc.error .jbar i{background:var(--err)}
      .qh{display:flex;align-items:center;gap:4px;margin-top:6px;font-size:11px;color:var(--mu)}
      /* ---- v29 UI polish: stable layout, one scroll area, phone bottom-sheet ---- */
      .panel{max-height:min(82vh,82dvh)}
      .hd,.tb,.seg,.pg,.sm,.ft{flex:none}
      .list{flex:1 1 auto;min-height:0;scroll-behavior:auto;-webkit-overflow-scrolling:touch;padding-bottom:calc(14px + env(safe-area-inset-bottom,0px))}
      .hd{padding:14px 14px 6px}
      .tb{gap:6px;padding:4px 14px 8px}
      .tb button{flex:1 1 0;min-width:0;padding:10px 8px;white-space:nowrap}
      .tb button.p{flex:1.25 1 0}
      .tb button.ic{flex:none;width:40px;padding:0}
      .tb .lb{overflow:hidden;text-overflow:ellipsis}
      .seg{margin:0 14px 6px}
      .pg{padding:2px 16px 2px;-webkit-line-clamp:1;font-weight:600;color:var(--tx);opacity:.85}
      .pg:empty{display:none}
      .sm{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none;padding:4px 14px 8px;-webkit-overflow-scrolling:touch}
      .sm::-webkit-scrollbar{display:none}
      .sm:empty{display:none}
      .chip{flex:none;white-space:nowrap}
      .row,.jr{animation:none}
      .list.fresh .row,.list.fresh .jr{animation:rin .28s var(--ease) both}
      .row .src{opacity:.75;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .row.v .nm{-webkit-line-clamp:2}
      .row .ac,.row .dlr{min-width:0}
      .ft{padding:10px 16px calc(10px + env(safe-area-inset-bottom,0px));border-top:1px solid rgba(255,255,255,.05);background:rgba(0,0,0,.25)}
      .grab{display:none}
      .tb .lgl{display:none}.tb .sml{display:inline}
      @media (max-width:640px),(pointer:coarse) and (max-width:900px){
        .panel{top:auto;bottom:0;left:0;right:0;width:auto;max-height:min(86vh,86dvh);transform:none;border-radius:24px 24px 0 0;border-width:1px 0 0;
          background:#12141b;backdrop-filter:none;-webkit-backdrop-filter:none;box-shadow:0 -18px 60px -12px rgba(0,0,0,.75)}
        .panel.r,.panel.l{left:0;right:0}
        .panel.on{animation:pinm .28s var(--ease) both}
        @keyframes pinm{from{opacity:0;transform:translateY(28px)}to{opacity:1;transform:none}}
        .grab{display:block;width:40px;height:4px;border-radius:4px;background:rgba(255,255,255,.22);margin:8px auto 0;flex:none}
        .wrap.open .pill,.wrap.open .mini{display:none}
        .toast{bottom:64px}
        .tb .lgl{display:none}.tb .sml{display:inline}
        .row.v{display:grid;grid-template-columns:96px minmax(0,1fr);gap:6px 12px;align-items:start}
        .row.v .mn{display:contents}
        .row.v .th{grid-row:1/3;width:96px;height:58px}
        .row.v .nm,.row.v .mt{grid-column:2;margin:0}
        .row.v .ac,.row.v .more,.row.v .pr{grid-column:1/-1}
        .row.v .pr{margin-top:2px}
        .sel,.dlr .sel{min-width:0}
        .dlr .dlb{padding:10px 14px}
      }
      @media (max-width:380px){.tb .lb.hide-xs{display:none}.th{width:60px!important}.row.v .th{width:92px!important;height:56px!important}}

      /* ---- v41 polish: colour, download button, cards, "..." menu ---- */
      .wrap{--a:#5f8bff;--b:#9a63ff;--c:#2fe0c0;--warm:#f5b94a}
      .panel{border-radius:26px;border-color:rgba(255,255,255,.11);box-shadow:0 34px 90px -12px rgba(0,0,0,.78),0 0 0 1px rgba(124,120,255,.14),0 0 70px -24px rgba(139,92,255,.45)}
      .hd b{background:linear-gradient(135deg,#fff 30%,#b9c7ff);-webkit-background-clip:text;background-clip:text;color:transparent}
      .lg{box-shadow:0 0 16px var(--a),0 0 4px var(--b)}
      button.p{background:linear-gradient(135deg,#4f8cff 0%,#7a5cff 55%,#b05cff 100%);box-shadow:0 10px 24px -10px rgba(122,92,255,.85),inset 0 1px 0 rgba(255,255,255,.26)}
      .dlr .dlb,.ac .dlb{position:relative;overflow:hidden;gap:6px;min-height:42px;padding:11px 12px;border-radius:14px;font-size:13px;font-weight:750;letter-spacing:.01em}
      .dlb::after{content:"";position:absolute;inset:0;background:linear-gradient(105deg,transparent 35%,rgba(255,255,255,.28) 50%,transparent 65%);transform:translateX(-120%);transition:transform .6s var(--ease);pointer-events:none}
      .dlb:hover::after{transform:translateX(120%)}
      .dlb svg{width:17px;height:17px;stroke-width:2.4;transition:transform .2s var(--ease)}
      .dlb:hover svg{transform:translateY(2px)}
      .dlb.sent,.dlb.sent:hover{background:linear-gradient(135deg,#1db97a,#2fe0c0);box-shadow:0 10px 24px -10px rgba(47,224,192,.8),inset 0 1px 0 rgba(255,255,255,.3)}
      .dlr .sel{flex:1 1 0;min-width:84px}
      .dlr .ic2{width:38px;min-height:42px;border-radius:14px;background:rgba(255,255,255,.08);box-shadow:inset 0 0 0 1px rgba(255,255,255,.07)}
      .sel select{min-height:42px;border-radius:14px;padding:11px 30px 11px 13px;background:rgba(255,255,255,.075)}
      .row{background:linear-gradient(180deg,rgba(255,255,255,.065),rgba(255,255,255,.03));border-radius:20px;border-color:rgba(255,255,255,.075);position:relative;overflow:hidden}
      .row:hover{border-color:rgba(122,120,255,.55);box-shadow:0 14px 34px -18px rgba(122,100,255,.55)}
      .row.v::before{content:"";position:absolute;left:0;top:14px;bottom:14px;width:3px;border-radius:0 3px 3px 0;background:linear-gradient(var(--a),var(--b))}
      .row[data-kind=dash].v::before{background:linear-gradient(#2fe0c0,#4f8cff)}
      .row[data-kind=video].v::before{background:linear-gradient(#4f8cff,#2fe0c0)}
      .row[data-kind=audio]::before{content:"";position:absolute;left:0;top:14px;bottom:14px;width:3px;border-radius:0 3px 3px 0;background:linear-gradient(#f5b94a,#ff6b85)}
      .th{box-shadow:inset 0 0 0 1px rgba(255,255,255,.08),0 8px 18px -10px rgba(0,0,0,.8)}
      .th .ty{background:linear-gradient(135deg,#6b7cff,#9a63ff);box-shadow:0 4px 10px -4px rgba(120,100,255,.8)}
      .row[data-kind=dash] .th .ty{background:linear-gradient(135deg,#1fc9b0,#3a86ff)}
      .row[data-kind=video] .th .ty{background:linear-gradient(135deg,#3a86ff,#1fc9b0)}
      .nm{letter-spacing:-.005em}
      .tag{background:rgba(124,120,255,.2);color:#c3ccff}
      /* the "..." menu: grouped, tidy, with a labelled clip box */
      .more{background:rgba(0,0,0,.24);border:1px solid rgba(255,255,255,.065);border-radius:16px;padding:10px;gap:7px;margin-top:10px}
      .more .mh{flex:1 0 100%;font-size:10px;font-weight:800;letter-spacing:.09em;text-transform:uppercase;color:var(--mu);margin:3px 2px 0}.more .mh:first-child{margin-top:0}
      .more .mh em{font-style:normal;text-transform:none;letter-spacing:0;font-weight:600;opacity:.8}
      .more .grp{display:flex;flex-wrap:wrap;gap:6px;flex:1 0 100%;align-items:center}
      .more button{border-radius:10px;padding:7px 11px;background:rgba(255,255,255,.075);box-shadow:inset 0 0 0 1px rgba(255,255,255,.05)}
      .more button.acc{background:linear-gradient(135deg,rgba(95,139,255,.3),rgba(154,99,255,.3));color:#e4dcff;box-shadow:inset 0 0 0 1px rgba(154,120,255,.55)}
      .more button.acc:hover{filter:brightness(1.2)}
      .more input{min-height:36px;width:76px;text-align:center;font:inherit;font-size:12.5px;font-weight:600;color:var(--tx);background:rgba(255,255,255,.07);border:0;border-radius:10px;padding:6px 8px;outline:none;box-shadow:inset 0 0 0 1px rgba(255,255,255,.09)}
      .more input:focus{box-shadow:inset 0 0 0 1px rgba(124,120,255,.9),0 0 0 3px rgba(124,120,255,.18)}
      .more input::placeholder{color:#7d8696;font-weight:500}
      .more input.tm{width:auto;min-width:104px;border:0}
      .more .to{color:var(--mu);font-size:12px}
      .jc.run{border-color:rgba(122,120,255,.45)}
      .jbar{height:4px}
      .jbar i,.bar i{background:linear-gradient(90deg,#4f8cff,#9a63ff,#2fe0c0,#9a63ff,#4f8cff);background-size:300% 100%}
      .seg button.on,.tabs button.on{background:linear-gradient(135deg,rgba(95,139,255,.3),rgba(154,99,255,.28));box-shadow:inset 0 0 0 1px rgba(154,120,255,.55)}
      .tb button.ps{background:rgba(95,139,255,.13);box-shadow:inset 0 0 0 1px rgba(95,139,255,.42)}

      /* ---- v42: docked preview ---- */
      .pvbox{flex:none;margin:2px 14px 8px;padding:10px;border-radius:18px;background:rgba(0,0,0,.36);border:1px solid rgba(154,120,255,.42);display:flex;flex-direction:column;gap:8px;box-shadow:0 14px 34px -18px rgba(122,100,255,.6)}
      .pvbox[hidden]{display:none}
      .pvh{display:flex;align-items:center;gap:8px}.pvh b{flex:1;min-width:0;font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
      .pvv{width:100%;max-height:26vh;border-radius:12px;background:#000;display:block}
      .pvm{font-size:11.5px;color:#f5c76a;line-height:1.4}.pvm:empty{display:none}
      .pvc{display:flex;gap:6px;align-items:center;flex-wrap:wrap}.pvc b{font-variant-numeric:tabular-nums;font-size:12.5px;min-width:44px;text-align:center}
      .pvc2{display:flex;gap:6px}.pvc2 .dlb{flex:1}
      .th[data-a=play]{cursor:pointer}.th[data-a=play]:hover{box-shadow:inset 0 0 0 1px rgba(154,120,255,.8),0 8px 18px -10px rgba(0,0,0,.8)}
      .row.pvon{border-color:rgba(154,120,255,.7)}
      /* long titles: cut with ... by default; Aa button / tap on a title / v button show the full text */
      .xp{flex:none;border:0;background:transparent;color:inherit;opacity:.6;font-size:12px;line-height:1;padding:6px 7px;border-radius:8px;cursor:pointer;transition:transform .15s,opacity .15s}
      .xp:hover{opacity:1;background:rgba(255,255,255,.1)}
      .ch.open .xp{transform:rotate(180deg);opacity:1}
      .ic.aa{font-size:12px;font-weight:800;letter-spacing:-.02em}.ic.aa.on{background:rgba(91,141,255,.28);opacity:1}
      .panel.wt .cl,.panel.wt .jn,.panel.wt .nm,.ch.open .cl,.jc.open .jn,.row.open .nm{white-space:normal!important;overflow:visible!important;text-overflow:clip!important;display:block!important;-webkit-line-clamp:unset!important;overflow-wrap:anywhere;word-break:break-word}
      .panel.wt .ch,.ch.open{align-items:flex-start}
      .jn,.row .nm{cursor:pointer}
      .dg{position:absolute;inset:0;z-index:30;display:none;flex-direction:column;gap:8px;padding:14px;background:#12151c;border-radius:inherit}
      .dg.on{display:flex}.dgh b{font-size:14px}.dgh span{display:block;font-size:11.5px;color:var(--mu);margin-top:2px}
      .dg textarea{flex:1;min-height:0;width:100%;resize:none;font:11px/1.45 ui-monospace,Menlo,Consolas,monospace;color:var(--tx);background:rgba(255,255,255,.06);border:1px solid var(--ln);border-radius:10px;padding:10px;box-sizing:border-box}
      .dgb{display:flex;gap:8px}.dgb button{flex:1}.panel .toast{z-index:40}

      /* ---- V74c: calmer panel. Same structure and actions; flatter surface, tokens for spacing / radius, no glow or shine ---- */
      .panel{width:min(400px,calc(100vw - 96px));border-radius:var(--r-lg);background:#14171e;-webkit-backdrop-filter:none;backdrop-filter:none;border-color:rgba(255,255,255,.1);box-shadow:0 24px 64px -16px rgba(0,0,0,.65)}
      .hd{padding:var(--s3) var(--s3) var(--s2) var(--s4);gap:var(--s2)}
      .hd b{background:none;-webkit-background-clip:border-box;background-clip:border-box;color:var(--tx);font-size:15px;font-weight:700}
      .lg{box-shadow:0 0 8px rgba(91,141,255,.7)}
      button.ic{width:34px;height:34px;border-radius:var(--r-sm)}
      .tb{padding:var(--s1) var(--s4) var(--s3);gap:var(--s2)}
      .tb button{border-radius:var(--r-sm)}
      .seg{margin:0 var(--s4) var(--s2)}
      .pg{padding:0 var(--s4);font-size:var(--fs-sm)}
      .sm{padding:var(--s1) var(--s4) var(--s2)}
      .list{padding:var(--s1) var(--s3) calc(var(--s4) + env(safe-area-inset-bottom,0px))}
      .row{border-radius:var(--r-md)}
      .row:hover{box-shadow:none}
      .row.v::before,.row[data-kind=audio]::before{display:none}
      .dlb::after{display:none}
      .dlr .dlb,.ac .dlb,.dlr .ic2,.sel select{border-radius:var(--r-sm)}
      .more{border-radius:var(--r-md)}
      .ft{padding:var(--s2) var(--s4) calc(var(--s2) + env(safe-area-inset-bottom,0px))}
      @media (pointer:coarse){button.ic{width:var(--tap);height:var(--tap)}.hd{padding-right:var(--s2)}}
      @media (max-width:640px),(pointer:coarse) and (max-width:900px){
        .panel{width:auto;border-radius:var(--r-lg) var(--r-lg) 0 0;border-width:1px 0 0;box-shadow:0 -18px 60px -12px rgba(0,0,0,.75)}
        .grab{width:100%;height:24px;margin:0;background:none;position:relative;cursor:pointer}
        .grab::after{content:"";position:absolute;left:50%;top:10px;width:40px;height:4px;margin-left:-20px;border-radius:4px;background:rgba(255,255,255,.24)}
        .hd{padding-top:0}
        .wrap.open::before{content:"";position:fixed;inset:0;background:rgba(0,0,0,.4);animation:scrim .25s var(--ease) both}
        @keyframes scrim{from{opacity:0}to{opacity:1}}
      }

      /* ---- V74d: video row card + "..." menu. CSS only: markup, data-a names and handlers are unchanged ---- */
      /* card = 2-column grid on every width: thumbnail + name / info on top, quality + Download + "..." on the FULL card width below */
      .row.v{display:grid;grid-template-columns:auto minmax(0,1fr);gap:0 var(--s3);align-items:start;padding:var(--s3)}
      .row.v .mn{display:contents}
      .row.v .th{grid-column:1;grid-row:1/3;width:96px;height:58px}
      .row.v .nm,.row.v .mt{grid-column:2;margin:0}
      .row.v .mt{margin-top:var(--s1)}
      .row.v .ac,.row.v .more,.row.v .pr{grid-column:1/-1}
      .row.v .ac{margin-top:var(--s3)}
      .row.v .pr{margin-top:var(--s2)}
      .row.v .dlr .ic2{width:var(--tap);min-height:var(--tap)}
      .dlr .dlb,.sel select{min-height:var(--tap)}
      .row:has(.more.on) .ic2{background:rgba(95,139,255,.3);box-shadow:inset 0 0 0 1px rgba(95,139,255,.5)}
      /* the "..." menu: labelled groups, buttons in an even grid instead of a ragged wrap */
      .more{margin-top:var(--s3);padding:var(--s3);gap:var(--s2)}
      .more .mh{margin:var(--s2) 2px 0;font-size:10.5px}
      .more .mh:first-child{margin-top:0}
      .more .grp{gap:var(--s2)}
      .more .grp:not(:has(input)){display:grid;grid-template-columns:repeat(auto-fit,minmax(92px,1fr))}
      .more button{min-height:36px;padding:var(--s2) var(--s3);border-radius:var(--r-sm);font-size:var(--fs-sm);justify-content:center}
      .more .grp:has(input){align-items:stretch}
      .more input{min-height:36px}
      @media (pointer:coarse){.more button,.more input{min-height:var(--tap)}button.ib{width:40px;height:40px}}

      /* ---- V74e: batch / chapters view and Downloads (jobs) view. CSS only: markup, data-a names and handlers are unchanged ---- */
      /* batch header (sticky): solid surface, selection buttons as an even row */
      .bh{background:#14171e;border-bottom:1px solid rgba(255,255,255,.06);padding:var(--s1) 0 var(--s3);margin:0}
      .bt{gap:var(--s2);padding:var(--s1) 0 var(--s2)}
      .bt .cn2{font-variant-numeric:tabular-nums}
      .srch{min-height:40px;border-radius:var(--r-sm)}
      .bq{gap:6px;margin-top:var(--s2)}
      .bq button{flex:1 1 auto;min-height:34px;padding:var(--s1) var(--s3);border-radius:var(--r-sm);font-size:var(--fs-sm)}
      .bq .hint{flex:1 0 100%;margin:0;text-align:right}
      /* "more ways to pick": a clear expandable row */
      .mw summary{display:flex;align-items:center;justify-content:space-between;gap:var(--s2);min-height:40px;padding:var(--s2) var(--s3);list-style:none;background:rgba(255,255,255,.045);border-radius:var(--r-sm)}
      .mw summary::-webkit-details-marker{display:none}
      .mw summary::after{content:"\\25BE";flex:none;transition:transform .2s var(--ease)}
      .mw[open] summary::after{transform:rotate(180deg)}
      /* episode rows: bigger, selected ones are visible at a glance */
      .ch{min-height:44px;gap:var(--s3);padding:var(--s2) var(--s3);border-radius:var(--r-sm);margin:var(--s1) 0}
      .ch:has(input:checked){background:rgba(95,139,255,.13);box-shadow:inset 0 0 0 1px rgba(95,139,255,.35)}
      .ch input{width:18px;height:18px;flex:none}
      /* batch footer (sticky): solid, full width, safe-area aware */
      .list:has(.bf){padding-bottom:0}
      .bf{background:#14171e;border-top:1px solid rgba(255,255,255,.07);margin:var(--s2) calc(var(--s3) * -1) 0;padding:var(--s3) var(--s3) calc(var(--s3) + env(safe-area-inset-bottom,0px))}
      .bf .r1 button{min-height:var(--tap);border-radius:var(--r-sm)}
      .bf .r2{gap:var(--s1);margin-top:var(--s2)}
      .bf .r2 button{flex:1 1 auto;min-height:36px}
      /* Downloads view */
      .jh{margin:var(--s4) var(--s1) var(--s1)}
      .jhb button{min-height:36px}
      .jc{border-radius:var(--r-md);margin:var(--s2) 0;padding:var(--s3) var(--s2) 0 var(--s3)}
      .jn{font-size:var(--fs-md)}
      .js{margin-top:var(--s1)}
      .js b{font-size:var(--fs-md)}
      .js span::before{content:"";display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:6px;background:currentColor;vertical-align:middle}
      .jc.run .js span{color:#b7ccff}
      button.jb{width:36px;height:36px;min-height:36px}
      .jbar{margin:var(--s3) calc(var(--s2) * -1) 0 calc(var(--s3) * -1)}
      @media (pointer:coarse){
        .bq .hint{display:none}
        .bq button,.srch,.mw summary,.jhb button{min-height:var(--tap)}
        .ch{min-height:52px}.ch input{width:22px;height:22px}
        .bf .r2 button{min-height:var(--tap)}
        button.jb{width:var(--tap);height:var(--tap);min-height:var(--tap)}
      }
      /* ---- V74m: batch / chapters view on phones - more room for the episode list. CSS only: markup, data-a names and handlers are unchanged ---- */
      @media (max-width:640px),(pointer:coarse) and (max-width:900px){
        .panel:has(.bh){max-height:min(94vh,94dvh)}
        /* while picking episodes the tool row, tabs, hint lines and footer links are not needed: the arrow in the title goes back */
        .panel:has(.bh) :is(.tb,.seg,.pg,.sm,.ft){display:none}
        .panel:has(.bh) .hd{padding:var(--s2) var(--s4) 0}
        .panel:has(.bh) .grab{margin-top:6px}
        /* compact sticky header: title, filter, one scrolling row of selection buttons */
        .bh{padding:0 0 var(--s2)}
        .bt{padding:0 0 var(--s2)}
        .bt b{min-width:0;font-size:var(--fs-md);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .srch{min-height:38px}
        .bq{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none;-webkit-overflow-scrolling:touch;margin-top:var(--s2)}
        .bq::-webkit-scrollbar{display:none}
        .bq button{flex:none;white-space:nowrap;min-height:36px}
        .bq .hint{display:none}
        /* episode rows a little tighter so more fit on the screen */
        .ch{min-height:48px;margin:2px 0}
        /* footer: 2 rows instead of 3 (quality / Videos / CBZ, then "if missing" + Follow / Read) */
        .bf{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:var(--s2);padding:var(--s2) var(--s3) calc(var(--s2) + env(safe-area-inset-bottom,0px))}
        .bf .r1{grid-column:1/-1;grid-row:1}
        .bf .r0{grid-column:1;grid-row:2;margin:0;min-width:0}
        .bf .r2{grid-column:2;grid-row:2;margin:0;flex-wrap:nowrap;justify-content:flex-end;align-items:center}
        .bf .r2:has(button:nth-child(3)){grid-column:1/-1;grid-row:3;flex-wrap:wrap;justify-content:center}
        .bf .r0 select{min-height:38px;padding-top:6px;padding-bottom:6px;font-size:var(--fs-sm)}
        .bf .r2 button{flex:none;min-height:40px;white-space:nowrap}
      }
    </style>
    <div class="wrap">
      <div class="pill" part="pill" title="Media Grabber (Alt+Shift+D)">${ICON}<span class="n"></span></div>
      <div class="mini"><div class="t"></div><div class="bar"><i></i></div></div>
      <div class="panel">
        <div class="grab" data-a="close"></div>
        <div class="hd"><span class="lg"></span><b>Media Grabber</b><span class="c"></span><span class="sp"></span>
          <button class="ic aa" data-a="wt" title="Show full titles">Aa</button>
          <button class="ic" data-a="settings" title="Settings">${IC.gear}</button>
          <button class="ic" data-a="close" title="Close">${IC.x}</button></div>
        <div class="tb">
          <button class="p" data-a="best" title="Download the best item">${IC.bolt}<span class="lb">Best</span></button>
          <button class="ps" data-a="playscan" title="Press play on the page (muted) and scan again">${IC.play}<span class="lb"><span class="lgl">Play &amp; scan</span><span class="sml">Scan</span></span></button>
          <button data-a="all" title="Download everything in this list (queued, subtitles as .srt)">${IC.all}<span class="lb">All</span></button>
          <button class="ic" data-a="rescan" title="Scan again">${IC.refresh}</button></div>
        <div class="seg"><button class="on" data-a="view" data-k="media">Detected <small class="s1"></small></button><button data-a="view" data-k="jobs">Downloads <small class="s2"></small></button></div>
        <div class="pg"></div><div class="sm"></div><div class="pvbox" hidden><div class="pvh"><b class="pvt"></b><button class="ic" data-a="pvclose" title="Close preview">${IC.x}</button></div><video class="pvv" controls playsinline preload="metadata"></video><div class="pvm"></div><div class="pvc"><button data-a="pvfrom" title="Use the current time as the clip start">Start here</button><b class="pvf">-</b><button data-a="pvto" title="Use the current time as the clip end">End here</button><b class="pvtt">-</b></div><div class="pvc2"><button class="p dlb" data-a="pvclip">${IC.dl}<span>Download this part</span></button><button data-a="pvopen" title="Open in the extension's own player tab">Built-in player</button></div></div><div class="list"></div>
        <div class="ft"><span>Media Grabber by MohmShawon</span><a data-a="diag" title="Make a report of what the extension sees on this page">Diagnose</a><a data-a="hidesite">Hide on this site</a></div>
        <div class="toast"></div>
      </div>
    </div>`;
    
document.documentElement.append(pillHost);
    const pill = pillRoot.querySelector('.pill');

    // drag (vertical) + side snap
    let drag = null;
    pill.addEventListener('pointerdown', (e) => { drag = { y: e.clientY, x: e.clientX, moved: false }; pill.setPointerCapture(e.pointerId); pill.style.cursor = 'grabbing'; });
    pill.addEventListener('pointermove', (e) => {
      if (!drag) return;
      if (Math.abs(e.clientY - drag.y) + Math.abs(e.clientX - drag.x) > 4) drag.moved = true;
      if (drag.moved) { pill.style.top = Math.min(innerHeight - 52, Math.max(0, e.clientY - 24)) + 'px'; drag.x2 = e.clientX; }
    });
    pill.addEventListener('pointerup', () => {
      pill.style.cursor = 'grab';
      if (drag && drag.moved) {
        const side = (drag.x2 || innerWidth) < innerWidth / 2 ? 'left' : 'right';
        const top = Math.round((parseFloat(pill.style.top) / innerHeight) * 100);
        try { chrome.storage.sync.set({ pillSide: side, pillTop: top }); } catch (e) { /* ignore */ }
      } else if ((siteMode() < 0 ? S.nativePanel !== false : siteMode() !== 1) && !open && ((visible().length && !chapters.length) || mangaN >= 3 || siteTask)) send({ type: 'native-panel' }); // MGB S51, S55: manga pages open the native panel too
      else setOpen(!open);
      drag = null;
    });

    pillRoot.querySelector('.mini').addEventListener('click', () => setOpen(true));
    pillRoot.addEventListener('click', (e) => {
      const b = e.target.closest('[data-a]');
      if (b) onAction(b, pillRoot, e);
      else {
        const t = e.target.closest && e.target.closest('.jn,.row .nm');
        const el = t && t.closest('.jc,.row');
        if (el) { el.classList.toggle('open'); const k = xpKey(el); if (k) { if (el.classList.contains('open')) xpOpen.add(k); else xpOpen.delete(k); } }
      }
    });
    pillRoot.addEventListener('input', (e) => {
      const t = e.target;
      if (t.dataset && t.dataset.a === 'rngin') rngTxt = t.value;
      else if (t.dataset && t.dataset.a === 'lnkin') lnkTxt = t.value;
      else if (t.dataset && t.dataset.a === 'nmsin') lnkNums = t.value;
      else if (t.dataset && t.dataset.a === 'nmin') lnkName = t.value;
      if (t.dataset && t.dataset.a === 'chfilter') { chFilter = t.value; renderPill(); const n = pillRoot.querySelector('.srch'); if (n) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); } }
    });
    ['keydown', 'keyup', 'keypress'].forEach((ev) => pillRoot.addEventListener(ev, (e) => { if (e.key !== 'Escape' && e.target && e.target.matches && e.target.matches('input,textarea')) e.stopPropagation(); }));
    pillRoot.addEventListener('change', (e) => { const t = e.target; if (t.dataset && t.dataset.a === 'epcap') { epCap = t.value; renderPill(); } else if (t.dataset && t.dataset.a === 'epmiss') { epMiss = t.value; try { chrome.storage.local.set({ mgBatchMiss: epMiss }); } catch (e) { /* optional */ } } });
    document.addEventListener('pointerdown', (e) => { if (open && !e.composedPath().includes(pillHost)) setOpen(false); }, true);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && open) setOpen(false); });
    document.addEventListener('fullscreenchange', renderAll);
  }

  function setOpen(v) {
    if (!v && pillRoot) { try { closePreview(pillRoot); } catch (e) { /* ignore */ } }
    open = v;
    if (v) syncJobs();
    if (!pillRoot) return;
    pillRoot.querySelector('.panel').classList.toggle('on', open);
    pillRoot.querySelector('.wrap').classList.toggle('open', open);
    if (open) { lastViewKey = ''; send({ type: 'rescan' }); refresh(); }
  }

  function thumbFor(it) {
    const h = rec.hint || {};
    if ((it.kind === 'hls' || it.kind === 'dash' || it.kind === 'video') && h.poster) return `style="background-image:url('${esc(h.poster)}')"`;
    return '';
  }
  const ORDER = { hls: 0, dash: 1, video: 2, audio: 3, subtitle: 4 };

  function sortedItems() {
    return visible().slice().sort((a, b) =>
      ((a.info && a.info.protected) - (b.info && b.info.protected)) || (ORDER[a.kind] - ORDER[b.kind]) || cmpStreams(a, b) || ((b.size || 0) - (a.size || 0)) || (b.time - a.time));
  }

  // ----- v28 panel state -----
  // ---------- "Diagnose this page": one text report to copy when something does not work ----------
  let diagOn = false;
  const cleanU = (u) => { try { const x = new URL(u, location.href); return x.origin + x.pathname; } catch (e) { return String(u || '').slice(0, 120); } }; // no ?token=... in reports
  async function buildDiag() {
    const L = [];
    const mf = (() => { try { return chrome.runtime.getManifest().version; } catch (e) { return '?'; } })();
    L.push(`Media Grabber ${mf} - page report`, `Time: ${new Date().toISOString().slice(0, 19)}Z`, `Browser: ${navigator.userAgent}`);
    L.push(`Touch screen: ${navigator.maxTouchPoints > 0 ? 'yes' : 'no'}, window ${innerWidth}x${innerHeight}`, `Page: ${cleanU(location.href)}`, `Title: ${(document.title || '').slice(0, 140)}`);
    L.push(`Extension on this site: ${siteOff() ? 'OFF (switched off for this site or everywhere)' : 'on'}`);
    const frames = [...document.querySelectorAll('iframe')].map((f) => cleanU(f.src || f.getAttribute('data-src') || 'about:blank'));
    L.push(`Iframes: ${frames.length}${frames.slice(0, 8).map((f) => '\n  - ' + f).join('')}`);
    const vids = [...document.querySelectorAll('video')];
    L.push(`Video elements: ${vids.length}${vids.slice(0, 4).map((v) => `\n  - ${v.currentSrc ? (v.currentSrc.startsWith('blob:') ? 'blob (a stream, see Detected below)' : cleanU(v.currentSrc)) : 'no source yet'}, ${Math.round(v.duration || 0)}s, ${v.paused ? 'paused' : 'playing'}`).join('')}`);
    const items = rec.items || [], kinds = {};
    items.forEach((i) => { kinds[i.kind] = (kinds[i.kind] || 0) + 1; });
    L.push(`Detected items: ${items.length} (${Object.keys(kinds).map((k) => k + ' ' + kinds[k]).join(', ') || 'none'})`);
    items.slice(0, 10).forEach((i) => L.push(`  - ${i.kind}${i.hidden ? ' (hidden)' : ''}${i.old ? ' (older player)' : ''}${i.info && i.info.protected ? ' DRM' : ''} ${cleanU(i.url)}${i.ref ? '  [from ' + hostOf(i.ref) + ']' : ''}`));
    const anchors = [...document.querySelectorAll('a[href]')];
    const chA = anchors.filter((a) => { try { const u = new URL(a.href); return u.hostname === location.hostname && CH_HREF.test(u.pathname); } catch (e) { return false; } });
    const pat = patternFromUrl(location.href);
    askEpList(); await new Promise((r) => setTimeout(r, 300)); // fresh answer from hook.js
    const pPath = location.pathname.split('/').filter(Boolean);
    const epShape = pPath.length === 2 && /^(?:ep|episode)[-_]?\d/i.test(pPath[1]);
    const cNums = (chapters || []).map((c) => (c.n != null ? c.n : epNumOf(c.label, c.url))).filter((n) => n != null && !isNaN(n));
    let gapTxt = '';
    if (cNums.length >= 3) {
      const set = new Set(cNums.map((n) => Math.floor(n))), lo = Math.min(...set), hi = Math.max(...set), miss = [];
      for (let i = lo; i <= hi && i - lo < 5000; i++) if (!set.has(i)) miss.push(i);
      const rg = [];
      for (let i = 0; i < miss.length;) { let j = i; while (j + 1 < miss.length && miss[j + 1] === miss[j] + 1) j++; rg.push(j > i ? miss[i] + '-' + miss[j] : '' + miss[i]); i = j + 1; }
      gapTxt = `numbers ${lo}..${hi}, ${rg.length ? 'missing ' + rg.slice(0, 10).join(', ') + (rg.length > 10 ? ' ...' : '') : 'no gaps'}`;
    }
    const hereP = location.href.split('#')[0], inList = (chapters || []).some((c) => String(c.url).split('#')[0] === hereP);
    const reqRows = apiInv.slice(0, 25).map((r) => `\n    - ${r.p}  ${r.ct || '?'}${r.eps ? '  [' + r.eps + ' episode entries]' : ''}${r.y ? '  -> ' + r.y : ''}`).join('');
    const anyEps = apiInv.some((r) => r.eps > 0);
    let verdict;
    if (siteOff()) verdict = 'The extension is off for this site.';
    else if (!epShape) verdict = 'This address is not like /show-name/ep-3-abc123, so the API reader stays off here. Open an episode page and run Diagnose again.';
    else if (!hookAlive) verdict = 'hook.js did not answer. Reload the page once (the extension must be active before the page loads), then run Diagnose again.';
    else if (apiCh.size) verdict = apiAdded ? `OK - the site's own list is in use (${apiAdded} episodes added that the page shows no link for).` : 'OK - the site list was read, but the page already shows all of those episodes as links.';
    else if (anyEps) verdict = 'A request held episode codes but was not used (the reason is on its line above). Send this report.';
    else verdict = 'No request held episode codes. The list may come in another form (HTML or GraphQL as text) or in a request made before the extension loaded. Reload once and try again; if it stays empty, send this report.';
    L.push('Episode list check:');
    L.push(`  Address like /show/ep-N-code: ${epShape ? 'yes (show ' + pPath[0] + ')' : 'no - the API reader works only on such addresses'}`);
    L.push(`  hook.js (sees the page's own requests): ${hookAlive ? 'answering' : 'no answer'}`);
    L.push(`  From the site's own API / page data: ${apiCh.size}${apiCh.size ? ' (show ' + apiShow + ', ' + apiAdded + ' added to the list)' : ' (none seen)'}; episode codes written in the page: ${inlineN}`);
    L.push(`  Final list: ${chapters.length}${gapTxt ? ' (' + gapTxt + ')' : ''}; the episode you are on is in it: ${inList ? 'yes' : 'no'}`);
    L.push(`  JSON requests on this page (path only, ${apiInv.length}):${reqRows || ' none seen'}`);
    L.push(`  Verdict: ${verdict}`);
    L.push(`Episode list: ${chapters.length} found (${anchors.length} links on the page, ${chA.length} look like episodes${pat ? '; address pattern ' + pat.pattern.replace(/^https?:\/\/[^/]+/, '') + (pat.hashed ? ' = random code in every address' : '') : ''})`);
    chapters.slice(0, 3).concat(chapters.length > 3 ? chapters.slice(-1) : []).forEach((c) => L.push(`  - ${String(c.label).slice(0, 90)}  ->  ${cleanU(c.url).replace(location.origin, '')}`));
    try { // MGB S84: Novel / Manga check (page side only; the app report has the native side)
      const paras = [...document.querySelectorAll('p')].filter((p) => (p.textContent || '').trim().length > 60);
      const txtLen = paras.reduce((n, p) => n + (p.textContent || '').length, 0);
      const nextA = [...anchors].some((a) => /^(next|next chapter|chapter next|>|›|»)$/i.test((a.textContent || '').trim()) || /next/i.test(a.rel || ''));
      const novelLike = /novel|chapter|read|book|story/i.test(location.pathname) && paras.length >= 5;
      L.push('Novel check:', `  Page looks like a novel chapter: ${novelLike ? 'yes' : 'no'} (${paras.length} text paragraphs, about ${txtLen} characters)`, `  Chapter-like links on the page: ${chA.length}; a "Next" link: ${nextA ? 'yes' : 'no'}`);
      L.push(novelLike && !chA.length && !nextA ? '  Novel verdict: text found but no chapter list / Next link. Open the chapter LIST page in the Novel grabber, or use "Pick chapter text on the page".' : (novelLike ? '  Novel verdict: OK, the Novel grabber can read this page.' : '  Novel verdict: this is probably not a novel page (fine if you are not grabbing a novel).'));
      const imgs = [...document.querySelectorAll('img')];
      const bigI = imgs.filter((i) => (i.naturalWidth || i.width || 0) >= 400 && (i.naturalHeight || i.height || 0) >= 400).length;
      const lazyI = imgs.filter((i) => !i.currentSrc && (i.dataset.src || i.dataset.lazySrc || i.getAttribute('data-original'))).length;
      const nm = (await chrome.storage.local.get('mgNativeMg')).mgNativeMg || {};
      const nmHere = nm.url === location.href;
      L.push('Manga check:', `  Images on the page: ${imgs.length}, large (page-like): ${bigI}, not loaded yet (lazy): ${lazyI}`, `  Pages counted by the grabber: ${mangaN}${nmHere ? '; native panel got has=' + !!nm.has + ' n=' + (nm.n || 0) + (nm.site ? ' site=' + nm.site : '') : '; native panel has no record for this page'}`);
      L.push(bigI >= 3 && mangaN < 3 && !nmHere ? '  Manga verdict: large images exist but the grabber counted fewer than 3 pages. Scroll the whole chapter once (lazy images) and run Diagnose again.' : (mangaN >= 3 ? '  Manga verdict: OK, pages were found.' : '  Manga verdict: this is probably not a manga page.'));
    } catch (e) { L.push('Novel / Manga check failed: ' + String(e && e.message || e).slice(0, 80)); }
    const st = {};
    Object.values(epSt).forEach((e) => { st[e.st] = (st[e.st] || 0) + 1; });
    if (Object.keys(st).length) L.push(`Batch: ${Object.keys(st).map((k) => k + ' ' + st[k]).join(', ')}`);
    Object.entries(epSt).filter(([, e]) => e.st === 'miss').slice(0, 6).forEach(([u, e]) => L.push(`  - no video: ${cleanU(u).replace(location.origin, '')}  because ${e.why || 'unknown'}`));
    const js = {};
    Object.values(jobs).forEach((j) => { js[j.state] = (js[j.state] || 0) + 1; });
    L.push(`Downloads: ${Object.keys(js).map((k) => k + ' ' + js[k]).join(', ') || 'none'}`);
    L.push(`Settings: blockAds=${S.blockAds} mergeAV=${S.mergeAV} fullTitles=${S.fullTitles} sniffSeconds=${S.sniffSeconds} maxJobs=${S.maxJobs} concurrency=${S.concurrency}`);
    try { // every setting that differs from the default, plus what this browser supports (saved by the popup / settings page / background)
      const ch = Object.keys(DEFAULTS).filter((k) => JSON.stringify(S[k]) !== JSON.stringify(DEFAULTS[k])).map((k) => `${k}=${String(typeof S[k] === 'object' ? JSON.stringify(S[k]) : S[k]).replace(/\s+/g, ' ').slice(0, 80)}`);
      L.push(`Changed settings: ${ch.length ? ch.join(', ') : 'none (all default)'}`);
      const cp = (await chrome.storage.local.get('mgCaps')).mgCaps;
      L.push(cp && cp.list ? 'Browser features:\n' + cp.list.map((x) => `  ${x.ok ? 'yes' : 'NO '} ${x.label}`).join('\n') : 'Browser features: not checked yet (open Settings > Backup & debug once)');
    } catch (e) { /* ignore */ }
    try { const log = (await chrome.storage.local.get('mgLog')).mgLog || []; L.push('', 'Recent log (newest last):', ...log.slice(-30).map((x) => '  ' + x.replace(/\?\S{12,}/g, '?...'))); } catch (e) { /* ignore */ }
    return L.join('\n');
  }
  async function showDiag() {
    if (!pillRoot) return;
    diagOn = true;
    const panel = pillRoot.querySelector('.panel');
    let dg = panel.querySelector('.dg');
    if (!dg) {
      dg = document.createElement('div'); dg.className = 'dg';
      dg.innerHTML = '<div class="dgh"><b>Page report</b><span>Copy this and send it when something does not work. Links are shortened (no tokens).</span></div><textarea readonly></textarea><div class="dgb"><button class="p" data-a="dgcopy">Copy report</button><button data-a="dgclose">Close</button></div>';
      panel.append(dg);
    }
    const ta = dg.querySelector('textarea');
    dg.classList.add('on'); ta.value = 'Collecting...';
    ta.value = await buildDiag();
  }
  const xpOpen = new Set(); // titles the user opened (kept across re-renders)
  const xpKey = (el) => (el && (el.dataset.k || el.dataset.key || (el.querySelector && el.querySelector('[data-u]') ? el.querySelector('[data-u]').dataset.u : ''))) || '';
  let view = 'media', chFilter = '', epSt = {}, epCap = '', epMiss = 'near', epPend = null, lastChk = '', batchOn = false, rT = 0, myTab = null, liveB = null;
  let mwOpen = false, rngTxt = '', lnkTxt = '', lnkNums = '', lnkName = ''; // "More ways to pick" (by number / pasted links / pattern)
  let lastViewKey = '', freshT = 0;
  function restoreScroll(list, y) { const sb = list.style.scrollBehavior; list.style.scrollBehavior = 'auto'; list.scrollTop = y; list.style.scrollBehavior = sb; }
  const renderSoon = () => { if (rT) return; rT = setTimeout(() => { rT = 0; renderPill(); }, 220); };
  // ask the background what it knows about this tab's jobs: a page that was reloaded / navigated (episode switch on a single-page site)
  // or a restarted service worker must not leave the panel showing old progress
  async function syncJobs() {
    if (!IS_TOP) return;
    try {
      const snap = await chrome.runtime.sendMessage({ type: 'jobs-get' });
      if (Array.isArray(snap) && snap.length) { mergeJobSnapshot(jobs, snap, Date.now()); renderSoon(); }
    } catch (e) { /* extension reloaded */ }
  }
  if (IS_TOP) setInterval(() => { if (!document.hidden && (open || batchOn || Object.values(jobs).some((j) => j.state === 'run'))) syncJobs(); }, 2500);
  setTimeout(syncJobs, 1200);
  (async () => { try { const v = (await chrome.storage.local.get('mgBatchMiss')).mgBatchMiss; if (v) epMiss = v; } catch (e) { /* optional */ } })();
  async function getEpPend() { try { const r = (await chrome.storage.local.get('mgBatchPend')).mgBatchPend || {}; return r[location.hostname] || null; } catch (e) { return null; } }
  const epPendCount = () => (epPend ? epPend.eps.filter((e) => chapters.some((c) => c.url === e.url)).length : 0);
  const filteredChapters = () => { const q = chFilter.trim().toLowerCase(); return q ? chapters.filter((c) => c.label.toLowerCase().includes(q)) : chapters; };
  const jobTitle = (k, j) => j.title || (rec.items.find((i) => i.key === k) || {}).title || rec.title || String(k).replace(/^ext\|/, '').split('?')[0].split('/').pop();

  // status badge of one episode / chapter in the picker: [text, class]
  function epBadge(c) {
    const e = epSt[c.url];
    if (e) {
      const j = e.key && jobs[e.key];
      if (j) {
        if (j.state === 'run') return j.paused ? ['Paused', 'warn'] : [Math.round((j.pct || 0) * 100) + '%', 'run'];
        if (j.state === 'done') return ['Done' + (e.q ? ' \u00b7 ' + e.q : ''), 'ok'];
        if (j.state === 'error') return [/Cancelled/i.test(j.text || '') ? 'Stopped' : 'Failed', 'err'];
      }
      const m = { wait: ['Waiting', ''], find: ['Finding video...', 'run'], sniff: ['Opening player...', 'run'], queued: ['Queued' + (e.q ? ' \u00b7 ' + e.q : ''), 'run'], noq: ['Quality missing', 'err', e.plain || e.why || ''], miss: ['No video found', 'err', e.plain || e.why || ''], skip: ['Done before', 'ok'] }[e.st];
      if (m) return m;
    }
    const st = chState[c.url];
    if (st === 'fail') return ['Failed', 'err'];
    if (st === 'run') return ['Saving...', 'run'];
    if (st === 'queued') return ['Queued', ''];
    if (doneCh.has(c.url)) return ['Done', 'ok'];
    return null;
  }

  function rowHTML(it) {
    const info = it.info || {};
    const isSub = it.kind === 'subtitle', isAud = it.kind === 'audio', isVid = !isSub && !isAud;
    const dur = info.dur || (rec.hint && rec.hint.dur && !isSub ? rec.hint.dur : 0);
    const meta = [];
    if (isAud) meta.push('<span class="tag">AUDIO</span>');
    if (isSub) meta.push('<span class="tag">SUBTITLE</span>');
    if (info.protected) meta.push('<span class="tag w">DRM</span>');
    if (info.live) meta.push('<span class="tag w">LIVE</span>');
    let srcLbl = '';
    try { const u = new URL(it.url); srcLbl = u.hostname.replace(/^www\./, '') + ' / ' + (u.pathname.split('/').filter(Boolean).pop() || ''); } catch (e) { /* ignore */ }
    const res = info.variants && info.variants[0] && info.variants[0].h ? info.variants[0].h + 'p' : (info.estH ? (info.estGuess ? '~' : '') + info.estH + 'p' : '');
    if (res) meta.push(res);
    if (isAud && dur) meta.push(fmtDur(dur));
    if (it.size && !(it.kind === 'hls' || it.kind === 'dash')) meta.push(fmtSize(it.size));
    else if (info.estSize) meta.push('~' + fmtSize(info.estSize)); // V80
    if (isSub) {
      const si = subInfo(it.url), lg = normLang(it.lang || si.lang);
      if (lg) meta.push(`<span class="tag">${esc(lg.toUpperCase())}</span>`);
      if (si.forced) meta.push('<span class="tag w">FORCED</span>');
      if (si.cc) meta.push('<span class="tag">CC</span>');
    }
    const name = isSub ? subTitle(it) : isAud ? baseName(it.url) : (rec.title || baseName(it.url));
    const more = `<button class="ic2" data-a="more" title="More options">${IC.more}</button>`;
    const vars = (it.kind === 'hls' || it.kind === 'dash') && info.variants && info.variants.length ? info.variants.slice(0, 8) : null;
    let acts = '';
    if (info.protected) acts = '<span style="color:#ff9db0;font-size:11.5px">Protected stream (DRM) - cannot be downloaded</span>';
    else if (isSub) acts = '<button class="p" data-a="sub" data-fmt="srt" title="Save as .srt (works with VLC, Plex, most players)">.srt</button><button data-a="sub" data-fmt="vtt" title="Save as .vtt">.vtt</button><button data-a="sub" data-fmt="orig" title="Save the file exactly as the site has it">Original</button><button data-a="more" title="More options">' + IC.more + '</button>';
    else if (vars) {
      const opts = ['<option value="best">Best (auto)</option>'].concat(vars.map((v, i) => {
        const [n, d] = qLabel(v.h, v.bw), sz = qSize(v, dur);
        const label = [n + (d ? ' ' + d : ''), sz ? '~' + sz : '', i === vars.length - 1 && vars.length > 1 ? 'smallest' : ''].filter(Boolean).join(' · ');
        return `<option value="${esc(v.url || v.id)}">${esc(label)}</option>`;
      })).join('');
      acts = `<div class="dlr"><label class="sel"><select data-a="qsel" title="Choose the quality">${opts}</select></label><button class="p dlb" data-a="dl" data-v="sel">${IC.dl}<span>Download</span></button>${more}</div>`;
    } else acts = `<div class="dlr"><button class="p dlb" data-a="dl" data-v="best" style="flex:1">${IC.dl}<span>Download${it.size ? ' · ' + fmtSize(it.size) : ''}</span></button>${more}</div>`;

    if ((it.kind === 'hls' || it.kind === 'dash') && !vars && info.error) acts += `<div class="qh">Quality list not readable <button class="ghost" data-a="reanalyze">Retry</button></div>`;
    const canWatch = !isSub && it.kind !== 'dash' && !info.protected;
    const th = isSub ? '' : `<div class="th" ${canWatch ? 'data-a="play" title="Preview"' : ''} ${thumbFor(it)}>${thumbFor(it) ? '' : `<span class="pl">${isAud ? '&#9835;' : IC.play}</span>`}${isVid ? `<span class="ty">${it.kind === 'hls' ? 'HLS' : esc(it.kind.toUpperCase())}</span>` : ''}${isVid && dur ? `<span class="dur">${fmtDur(dur)}</span>` : ''}</div>`;
    const canClip = it.kind === 'hls' && !info.protected, canSched = !isSub && !info.protected;
    const subMore = isSub ? `<span class="mh">Save subtitle as</span><div class="grp"><button data-a="sub" data-fmt="txt" title="Only the words, no times">.txt (text only)</button><button data-a="sub" data-fmt="srt">.srt</button><button data-a="sub" data-fmt="vtt">.vtt</button><button data-a="sub" data-fmt="orig">Original</button></div>` : '';
    const moreHtml = subMore + (canWatch ? `<span class="mh">Watch first</span><div class="grp"><button class="acc" data-a="play" title="Play it here, find the part you want, then download only that">${IC.play}<span>Preview here</span></button><button data-a="player" title="Open in the extension's own player tab">Built-in player</button></div>` : '') + `<span class="mh">Copy &amp; open</span><div class="grp"><button data-a="cp" data-w="url">Copy URL</button><button data-a="cp" data-w="ytdlp">yt-dlp</button><button data-a="cp" data-w="ffmpeg">ffmpeg</button><button data-a="cp" data-w="curl">cURL</button><button data-a="open">Open</button></div>`
      + (canClip ? `<span class="mh">Download only a part${dur ? ' <em>(video is ' + fmtDur(dur) + ')</em>' : ''}</span><div class="grp"><input class="cf" type="text" inputmode="text" autocomplete="off" placeholder="0:00" aria-label="From"><span class="to">to</span><input class="ct" type="text" inputmode="text" autocomplete="off" placeholder="end" aria-label="To"><button class="acc dlb2" data-a="clipgo" title="Uses the quality chosen above. Times: 90, 1:30 or 1:02:30">${IC.dl}<span>Download clip</span></button><button data-a="clip" title="Choose quality and part on a bigger page">Full page</button></div>` : '')
      + (canSched ? `<span class="mh">Start later</span><div class="grp"><input class="tm" type="time" title="Start time" aria-label="Start time"><button data-a="sched" title="Starts this download at the chosen time (the browser must be running)">Schedule</button></div>` : '');
    return `<div class="row${isSub ? ' cm' : ''}${isVid ? ' v' : ''}${xpOpen.has(it.key) ? ' open' : ''}" data-key="${esc(it.key)}" data-kind="${esc(it.kind)}">
      ${th}
      <div class="mn"><div class="nm" title="${esc(it.url)}">${esc(name)}</div>
        <div class="mt">${meta.join('<span>&middot;</span>')}${!isSub && srcLbl ? `<span class="src" title="${esc(it.url)}">${esc(srcLbl)}</span>` : ''}</div>
        <div class="ac">${acts}</div>
        <div class="more">${moreHtml}</div>
        <div class="pr"><div class="bar"><i></i></div><div class="pt"><span class="t"></span><span class="pb"><button class="ib pz" data-a="pause" title="Pause / resume">${IC.pause}</button><button class="ib" data-a="cancel" title="Cancel">${IC.x}</button></span></div></div>
      </div></div>`;
  }

  function renderAll() {
    renderPill();
    renderOverlay();
  }

  // ---- compact download queue (Downloads tab) ----
  const jClean = (j) => String(j.text || '').replace(/^(Paused|Resumed)\s*-?\s*/, '');
  function jobCard(j) {
    const pct = Math.round(Math.max(0, Math.min(1, j.pct || 0)) * 100), run = j.state === 'run', k = esc(j.k);
    const st = run ? (j.paused ? 'paused' : 'run') : (j.state || '');
    const btns = run
      ? `<button class="jb" data-a="jpause" data-k="${k}" title="${j.paused ? 'Resume' : 'Pause'}">${j.paused ? IC.play : IC.pause}</button><button class="jb" data-a="jcancel" data-k="${k}" title="Cancel">${IC.x}</button>`
      : `<button class="jb" data-a="jretry" data-k="${k}" title="Download again">${IC.refresh}</button><button class="jb" data-a="jremove" data-k="${k}" title="Remove from list">${IC.x}</button>`;
    const sub = run ? (j.paused ? 'Paused' : jClean(j) || 'Working...') : (j.state === 'done' ? 'Saved' : jClean(j) || 'Stopped');
    return `<div class="jc ${st}${xpOpen.has(j.k) ? ' open' : ''}" data-k="${k}" data-sig="${st}"><div class="jm"><div class="jn" title="${esc(jobTitle(j.k, j))}">${esc(jobTitle(j.k, j))}</div><div class="js"><b>${run ? pct + '%' : ''}</b><span>${esc(sub)}</span></div></div><div class="jx">${btns}</div><div class="jbar"><i style="width:${run ? pct : j.state === 'done' ? 100 : pct}%"></i></div></div>`;
  }
  function jobsHTML() {
    const all = Object.entries(jobs).map(([k, j]) => ({ k, ...j }));
    if (!all.length) return `<div class="empty">${IC.dl}<b>No downloads yet</b><span>Start a download and it shows up here with live progress, pause and resume.</span></div>`;
    const act = all.filter((j) => j.state === 'run'), fin = all.filter((j) => j.state !== 'run');
    const allPaused = act.length && act.every((j) => j.paused);
    let h = '';
    if (act.length) h += `<div class="jh"><span>Active <em>${act.length}</em></span><span class="jhb">${act.length > 1 ? `<button class="ghost" data-a="jpauseall">${allPaused ? 'Resume all' : 'Pause all'}</button><button class="ghost" data-a="jcancelall">Cancel all</button>` : ''}</span></div>` + act.map(jobCard).join('');
    if (fin.length) h += `<div class="jh"><span>Finished <em>${fin.length}</em></span><span class="jhb"><button class="ghost" data-a="jclear">Clear</button></span></div>` + fin.map(jobCard).join('');
    return h;
  }
  // refresh a card in place so buttons never get replaced under the finger while progress ticks
  function patchJobs(list) {
    const cards = [...list.querySelectorAll('.jc')];
    const all = Object.entries(jobs).map(([k, j]) => ({ k, ...j }));
    const order = all.filter((j) => j.state === 'run').concat(all.filter((j) => j.state !== 'run'));
    if (cards.length !== order.length) return false;
    for (let n = 0; n < order.length; n++) {
      const j = order[n], el = cards[n];
      const st = j.state === 'run' ? (j.paused ? 'paused' : 'run') : (j.state || '');
      if (el.dataset.k !== j.k || el.dataset.sig !== st) return false;
    }
    order.forEach((j, n) => {
      const el = cards[n], run = j.state === 'run', pct = Math.round(Math.max(0, Math.min(1, j.pct || 0)) * 100);
      el.querySelector('.jbar i').style.width = (j.state === 'done' ? 100 : pct) + '%';
      const jn = el.querySelector('.jn'), tt = jobTitle(j.k, j); if (jn && jn.textContent !== tt) { jn.textContent = tt; jn.title = tt; } // the real title can arrive after the first paint
      const jsp = el.querySelector('.js'); const sub = run ? (j.paused ? 'Paused' : jClean(j) || 'Working...') : (j.state === 'done' ? 'Saved' : jClean(j) || 'Stopped');
      jsp.querySelector('b').textContent = run ? pct + '%' : '';
      jsp.querySelector('span').textContent = sub;
    });
    return true;
  }

  function batchHTML() {
    const vis = filteredChapters();
    const nSel = chapters.filter((c) => chSel.has(c.url)).length;
    const bad = chapters.filter((c) => { const b = epBadge(c); return b && b[1] === 'err'; }).length;
    const resumeN = epPendCount() || pendCount();
    const capHs = new Set([1080, 720, 480, 360, 240]); // the usual ones + whatever this page's streams really offer (536p, 352p ...)
    (rec.items || []).forEach((i) => ((i.info && i.info.variants) || []).forEach((v) => { const q = qh(v); if (q > 0) capHs.add(q); }));
    if (+epCap > 0) capHs.add(+epCap);
    const caps = [['', 'Best']].concat([...capHs].sort((a, b) => b - a).map((h) => [String(h), h + 'p']));
    const rows = vis.map((c) => {
      const b = epBadge(c);
      return `<label class="ch${xpOpen.has(c.url) ? ' open' : ''}"><input type="checkbox" data-a="chk" data-u="${esc(c.url)}" ${chSel.has(c.url) ? 'checked' : ''}><span class="cl" title="${esc(c.label)}&#10;${esc(c.url)}">${esc(c.label)}</span><button class="xp" data-a="xp" data-u="${esc(c.url)}" title="Show the full title">&#9662;</button>${b ? `<span class="bg ${b[1]}"${b[2] ? ` title="${esc(b[2])}"` : ''}>${esc(b[0])}</span>` : ''}<button class="ic" data-a="chone" data-u="${esc(c.url)}" title="Open in the reader" style="width:28px;height:28px;padding:5px">&#128214;</button></label>`;
    }).join('') || `<div class="empty"><span>${chapters.length ? 'Nothing matches this filter.' : 'No episode list found on this page. Use "More ways to pick" above: the address pattern is filled in, change the numbers and press Download.'}</span></div>`;
    const lv = liveB && liveB.tab === myTab ? liveB : null;
    const liveLine = lv ? `<div class="tip" style="margin:6px 0;padding:8px 10px;border-radius:10px;background:rgba(91,141,255,.14)"><b>${lv.running ? 'Batch running' : lv.interrupted ? 'Batch interrupted' : 'Batch finished'}</b> &middot; ${lv.ok || 0} queued of ${lv.total}${lv.running ? ' &middot; now: episode ' + (lv.idx || 0) : ''}<br><span style="opacity:.85">${esc(lv.text || '')}</span></div>` : '';
    return `${liveLine}<div class="bh"><div class="bt"><button class="ic" data-a="chback" title="Back">&larr;</button><b>Select episodes / chapters</b><span class="cn2">${nSel} / ${chapters.length}</span></div>
      <input class="srch" data-a="chfilter" placeholder="Filter, e.g. 12 or ep 5..." value="${esc(chFilter)}">
      <div class="bq"><button data-a="chall">All</button><button data-a="chnone">None</button><button data-a="chinv">Invert</button><button data-a="chrev">Reverse</button><button data-a="chnew" title="Select only the ones not downloaded yet">New only</button><span class="hint">Shift+click = range</span></div></div>
      <details class="mw" ${mwOpen ? 'open' : ''}><summary data-a="mwtoggle">More ways to pick (by number, pasted links, pattern)</summary>
        <div class="mwr"><input class="srch" data-a="rngin" placeholder="By number: 5-12, 15, 20-" value="${esc(rngTxt)}"><button data-a="chrange" title="Select the episodes with these numbers">Select</button></div>
        <textarea class="srch" data-a="lnkin" rows="3" placeholder="Paste episode page links or direct video links, one per line.&#10;Or a pattern: https://site.com/watch/show-episode-{n}">${esc(lnkTxt)}</textarea>
        <div class="mwr"><input class="srch" data-a="nmsin" placeholder="Numbers for {n}: 1-12" value="${esc(lnkNums)}"><button data-a="lnkfill" title="Build the pattern from this page's address">From this page</button></div>
        <div class="mwr"><input class="srch" data-a="nmin" placeholder="Series name (optional)" value="${esc(lnkName)}"><button class="p" data-a="lnkgo">Download</button></div>
        <div class="tip">Works with the quality above. Direct .m3u8 / .mp4 links are downloaded as they are.</div></details>
      ${rows}
      <div class="bf"><div class="r0"><label class="sel"><select data-a="epmiss" ${epCap ? '' : 'disabled'} title="What to do when an episode does not have the chosen quality">${[['near', 'If missing: closest quality'], ['low', 'If missing: lower, never higher'], ['high', 'If missing: higher, never lower'], ['ask', 'If missing: ask me'], ['skip', 'If missing: skip episode']].map(([v, t]) => `<option value="${v}" ${epMiss === v ? 'selected' : ''}>${t}</option>`).join('')}</select></label></div><div class="r1"><label class="sel"><select data-a="epcap" title="Video quality for the batch">${caps.map(([v, t]) => `<option value="${v}" ${epCap === v ? 'selected' : ''}>${t}</option>`).join('')}</select></label><button class="p" data-a="chvid" title="Find the video in each selected episode and download it">${IC.play}<span>Videos${nSel ? ' (' + nSel + ')' : ''}</span></button><button data-a="chdl" title="Save each selected chapter as one CBZ">${IC.dl}<span>CBZ${nSel ? ' (' + nSel + ')' : ''}</span></button></div>
      <div class="r2">${batchOn || chRunning ? '<button class="ghost" data-a="chstop">Stop</button>' : ''}${bad ? `<button class="ghost" data-a="chretry">Retry failed (${bad})</button>` : ''}${resumeN ? `<button class="ghost" data-a="chresume">Resume (${resumeN})</button>` : ''}<button class="ghost" data-a="follow">&#9734; Follow</button><button class="ghost" data-a="chread">Read</button></div></div>`;
  }

  // V71: "Download again?" - asked only when the same item was saved before AND its file is still on the disk. Any problem = just download.
  async function dupGuard(rootEl, key, go) {
    let d = null;
    try { const r = await send({ type: 'dup-check', key }); d = r && r.dup; } catch (e) { d = null; }
    if (!d || !rootEl || !rootEl.appendChild) return go();
    const old = rootEl.querySelector('.mgdup'); if (old) old.remove();
    const box = document.createElement('div');
    box.className = 'mgdup';
    box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.55);font:14px system-ui,sans-serif';
    const card = document.createElement('div');
    card.style.cssText = 'width:min(340px,90vw);background:#1b1e27;color:#eef0f6;border-radius:16px;padding:16px;box-shadow:0 10px 40px rgba(0,0,0,.5)';
    card.setAttribute('role', 'alertdialog');
    const t1 = document.createElement('div'); t1.style.cssText = 'font-weight:700;margin-bottom:4px'; t1.textContent = 'Download again?';
    const t2 = document.createElement('div'); t2.style.cssText = 'color:#a9b0c0;font-size:12px;margin-bottom:12px;word-break:break-word';
    t2.textContent = 'You already downloaded "' + (d.title || 'this file') + '"' + (d.time ? ' on ' + new Date(d.time).toLocaleString() : '') + ' and it is still on your device.';
    const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:8px';
    const mk = (label, primary, fn) => {
      const b = document.createElement('button'); b.textContent = label;
      b.style.cssText = 'flex:1;padding:10px 12px;border:0;border-radius:12px;font:inherit;font-weight:600;cursor:pointer;color:' + (primary ? '#10131a' : '#eef0f6') + ';background:' + (primary ? '#5cd6a9' : 'rgba(255,255,255,.1)');
      b.addEventListener('click', () => { box.remove(); fn(); });
      row.appendChild(b);
    };
    mk('Cancel', false, () => {});
    mk('Download again', true, go);
    card.append(t1, t2, row); box.appendChild(card); rootEl.appendChild(box);
  }

  // An episode does not have the quality chosen for the batch: ask which one to use (answer goes back to the background; no answer in 90 s = closest)
  const askDone = new Set();
  function askQuality(m) {
    if (askDone.has(m.id) || (pillRoot && pillRoot.querySelector('.mgask'))) return; // already shown / answered (the question arrives by message and by storage)
    ensurePill();
    if (!pillRoot) return send({ type: 'batch-ask-answer', id: m.id, h: m.def || 0 });
    pillHost.style.display = '';
    const old = pillRoot.querySelector('.mgask'); if (old) old.remove();
    const box = document.createElement('div');
    box.className = 'mgask';
    box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.55);font:14px system-ui,sans-serif';
    const near = [...(m.avail || [])].sort((a, b) => Math.abs(a - m.want) - Math.abs(b - m.want) || a - b);
    const card = document.createElement('div');
    card.style.cssText = 'width:min(340px,90vw);background:#1b1e27;color:#eef0f6;border-radius:16px;padding:16px;box-shadow:0 10px 40px rgba(0,0,0,.5)';
    card.innerHTML = `<div style="font-weight:700;margin-bottom:4px">${esc(m.label)}: ${m.want}p is not available</div>
      <div style="color:#a9b0c0;font-size:12px;margin-bottom:10px">Available here: ${near.map((h) => h + 'p').join(', ')}. Which one should be downloaded?</div>
      <div class="qb" style="display:flex;flex-direction:column;gap:6px"></div>
      <label style="display:flex;gap:8px;align-items:center;margin-top:10px;font-size:12px;color:#a9b0c0"><input type="checkbox" class="all"> Do the same for the rest of this batch</label>`;
    const qb = card.querySelector('.qb');
    const done = (h) => {
      const all = card.querySelector('.all').checked;
      const mode = !all ? '' : h === 0 ? 'skip' : h < m.want ? 'low' : 'high';
      box.remove(); askDone.add(m.id);
      send({ type: 'batch-ask-answer', id: m.id, h, mode });
    };
    const mk = (label, h, primary) => {
      const b = document.createElement('button');
      b.textContent = label;
      b.style.cssText = 'padding:10px 12px;border:0;border-radius:12px;font:inherit;font-weight:600;cursor:pointer;color:' + (primary ? '#10131a' : '#eef0f6') + ';background:' + (primary ? '#5cd6a9' : 'rgba(255,255,255,.1)');
      b.addEventListener('click', () => done(h));
      qb.appendChild(b);
    };
    near.forEach((h, i) => mk(`Download ${h}p` + (i === 0 ? ' (closest)' : h > m.want ? ' (higher)' : ' (lower)'), h, i === 0));
    mk('Skip this episode', 0, false);
    box.appendChild(card);
    pillRoot.appendChild(box);
    setTimeout(() => { if (box.isConnected) box.remove(); }, 90000);
  }


  // V73: the SHOW name for batch file names, read with a chain of fallbacks (the first one that gives a real show name, not the website, wins):
  // page data (JSON-LD partOfSeries) -> tab title -> og:title -> big heading (h1) -> address. Settings > Saving > "Series name is read from" puts one of them first.
  function siteNamesHere() {
    const out = titleNames(S.titleStrip);
    try {
      for (const sel of ['meta[property="og:site_name"]', 'meta[name="application-name"]', 'meta[name="apple-mobile-web-app-title"]']) { const m = document.querySelector(sel); if (m && m.content) out.push(m.content.trim()); }
    } catch (e) { /* optional */ }
    return out;
  }
  function pickSeriesTitle(fallback) {
    const host = location.hostname, names = siteNamesHere();
    const fromTxt = (t) => { try { return t ? smartSeries(String(t), host, names) : ''; } catch (e) { return ''; } };
    const src = {
      schema: () => {
        try {
          for (const sc of document.querySelectorAll('script[type="application/ld+json"]')) {
            let j; try { j = JSON.parse(sc.textContent); } catch (e) { continue; }
            for (const o of [].concat(j, (j && j['@graph']) || [])) {
              if (!o || typeof o !== 'object') continue;
              const ps = o.partOfSeries || (o.partOfSeason && o.partOfSeason.partOfSeries);
              if (ps && ps.name) return String(ps.name).trim();
              if (/Series/.test([].concat(o['@type'] || []).join(' ')) && o.name) return String(o.name).trim();
            }
          }
        } catch (e) { /* optional */ }
        return '';
      },
      page: () => fromTxt(document.title),
      og: () => { const m = document.querySelector('meta[property="og:title"]'); return fromTxt(m && m.content); },
      h1: () => { const h = document.querySelector('h1'); return fromTxt(h && h.textContent); },
      url: () => { try { const sl = decodeURIComponent(location.pathname).split('/').filter(Boolean).reverse().find((x) => /[a-z]{3}/i.test(x)); return sl ? fromTxt(seriesName(sl.replace(/[-_+.]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()))) : ''; } catch (e) { return ''; } }
    };
    const first = ['schema', 'page', 'h1'].includes(S.titleSource) ? S.titleSource : 'schema';
    const order = [first, ...['schema', 'page', 'og', 'h1', 'url'].filter((k) => k !== first)];
    for (const k of order) {
      let v = ''; try { v = src[k](); } catch (e) { v = ''; }
      if (validSeries(v, host, names)) return v;
    }
    return fromTxt(document.title) || fallback;
  }

  function startEpisodes(root, picks, nameOverride) {
    if (!picks.length) return toast(root, 'Select episodes first');
    const bookT = nameOverride || (S.cleanTitles !== false ? pickSeriesTitle('video') : seriesName(document.title || 'video')) || 'video'; // V73: never the website name
    picks.forEach((c) => { epSt[c.url] = { st: 'wait' }; });
    batchOn = true;
    send({ type: 'batch-episodes', cap: epCap, miss: epMiss, episodes: picks.map((c) => ({ url: c.url, label: c.label, book: bookT, title: bookT + ' - ' + (S.padChapters ? padNumbers(c.label) : c.label) })) });
    toast(root, `Finding videos in ${picks.length} episode(s)...`, 4000);
    renderPill();
  }

  function renderPill() {
    if (!IS_TOP) return;
    const items = visible();
    const show = diagOn || (!siteOff() && (siteMode() < 0 ? S.showPill : siteMode() !== 2) && (items.length || mangaN >= 3 || siteTask || chapters.length >= 3 || Object.keys(jobs).length) && !document.fullscreenElement);
    if (!show) { if (pillHost) pillHost.style.display = 'none'; return; }
    ensurePill();
    pillHost.style.display = '';
    const pill = pillRoot.querySelector('.pill');
    const side = S.pillSide === 'left' ? 'l' : 'r';
    pill.className = 'pill ' + side + (Object.values(jobs).some((j) => j.state === 'run') ? ' busy' : '');
    pill.style.top = Math.round((S.pillTop / 100) * innerHeight) + 'px';
    pillRoot.querySelector('.panel').classList.toggle('r', side === 'r');
    pillRoot.querySelector('.panel').classList.toggle('l', side === 'l');
    pillRoot.querySelector('.panel').classList.toggle('wt', !!S.fullTitles);
    const aaB = pillRoot.querySelector('[data-a=wt]'); if (aaB) { aaB.classList.toggle('on', !!S.fullTitles); aaB.title = S.fullTitles ? 'Titles are shown in full (tap to shorten)' : 'Show full titles'; }
    pillRoot.querySelector('.n').textContent = items.length || mangaN || (siteTask && siteTask.pages) || chapters.length || '';
    paintMini(side);
    if (!open) return;
    const nRun = Object.values(jobs).filter((j) => j.state === 'run').length;
    pillRoot.querySelector('.c').textContent = items.length ? `(${items.length})` : '';
    pillRoot.querySelector('.s1').textContent = items.length || '';
    pillRoot.querySelector('.s2').textContent = nRun || '';
    pillRoot.querySelectorAll('.seg button').forEach((x) => x.classList.toggle('on', x.dataset.k === view));
    const list = pillRoot.querySelector('.list');
    const vkey = view + '|' + ((chView && canBatch()) ? 'b' : 'm') + '|' + tabSel;
    const changed = vkey !== lastViewKey;
    lastViewKey = vkey;
    if (changed) { list.classList.add('fresh'); clearTimeout(freshT); freshT = setTimeout(() => list.classList.remove('fresh'), 500); }
    const st = changed ? 0 : list.scrollTop;
    const pg = pillRoot.querySelector('.pg'), sm = pillRoot.querySelector('.sm');
    if (view === 'jobs') {
      pg.style.display = sm.style.display = 'none';
      if (!(!changed && patchJobs(list))) { list.innerHTML = jobsHTML(); restoreScroll(list, st); }
      return;
    }
    pg.style.display = sm.style.display = (chView && canBatch()) ? 'none' : '';
    pg.textContent = rec.title || document.title;
    const vids = items.filter((i) => ['hls', 'dash', 'video'].includes(i.kind) && !(i.info && i.info.protected)).length;
    sm.innerHTML = [vids ? `<span class="chip">&#9654; Video ${vids}</span>` : '', (mangaN >= 3 || siteTask) ? `<span class="chip">&#128214; Pages ${mangaN >= 3 ? mangaN : (siteTask.pages || siteTask.label)}</span>` : '', chapters.length ? `<span class="chip">Episodes / chapters ${chapters.length}</span>` : '', adsN ? `<span class="chip">&#128683; Ads skipped ${adsN}</span>` : ''].join('');
    const sorted = sortedItems();
    // keep the "more" menus and quality choices open across re-renders
    const openMore = new Set([...list.querySelectorAll('.more.on')].map((m) => m.closest('.row').dataset.key));
    const picked = {};
    list.querySelectorAll('.row select[data-a=qsel]').forEach((x) => { picked[x.closest('.row').dataset.key] = x.value; });
    const typed = {};
    list.querySelectorAll('.row .more input').forEach((x) => { if (!x.value) return; const k = x.closest('.row').dataset.key; typed[k] = typed[k] || {}; typed[k][x.className] = x.value; });
    const mrow = (mangaN >= 3 || siteTask) ? `<div class="row"><div class="th">&#128214;</div><div class="mn"><div class="nm">Page images (${mangaN >= 3 ? mangaN : (siteTask.pages || 'all')})</div><div class="mt"><span class="tag">MANGA</span> ${siteTask ? `reads the ${esc(siteTask.label)} page list, original quality` : 'loads all pages, original quality'}</div><div class="ac"><button class="p" data-a="mg" data-w="cbz">CBZ</button><button data-a="mg" data-w="zip">ZIP</button><button data-a="mg" data-w="pdf">PDF</button><button data-a="mg" data-w="epub">EPUB</button><button class="p" data-a="mg" data-w="read">Read</button><button data-a="mg" data-w="view">Pick pages</button></div></div></div>` : '';
    const crow = canBatch() ? `<div class="row"><div class="mn"><div class="nm">${chapters.length ? `Episodes / chapters (${chapters.length})` : 'Episode page: batch download'}</div><div class="mt"><span class="tag">BATCH</span> pick many: videos or CBZ, with status for each</div><div class="ac"><button class="p" data-a="chopen">Select &amp; download</button></div></div></div>` : '';
    if (chView && canBatch()) {
      const fo = pillRoot.activeElement && pillRoot.activeElement.classList && pillRoot.activeElement.classList.contains('srch');
      const act = pillRoot.activeElement, fa = act && act.dataset && act.dataset.a, ss = act && act.selectionStart, se = act && act.selectionEnd;
      list.innerHTML = batchHTML();
      void fo;
      if (fa && /^(rngin|lnkin|nmsin|nmin)$/.test(fa)) { const n = list.querySelector(`[data-a="${fa}"]`); if (n) { n.focus(); try { n.setSelectionRange(ss, se); } catch (e) { /* ignore */ } } }
    } else {
      const G = { video: sorted.filter((i) => ['hls', 'dash', 'video'].includes(i.kind)), audio: sorted.filter((i) => i.kind === 'audio'), subtitle: sorted.filter((i) => i.kind === 'subtitle') };
      const kinds = Object.keys(G).filter((k) => G[k].length);
      const NAMES = { video: 'Video', audio: 'Audio', subtitle: 'Subtitles' };
      if (tabSel !== 'all' && !G[tabSel].length) tabSel = 'all';
      const tabs = kinds.length > 1 ? `<div class="tabs">${['all'].concat(kinds).map((k) => `<button class="${tabSel === k ? 'on' : ''}" data-a="tab" data-k="${k}">${k === 'all' ? 'All' : NAMES[k]} <small>${k === 'all' ? sorted.length : G[k].length}</small></button>`).join('')}</div>` : '';
      const pair = () => { // which subtitles travel with the video (same rule as the downloader)
        if (!S.autoSubs || !G.subtitle.length) return '';
        const pref = (S.subLangs || '').toLowerCase().split(/[\s,]+/).filter(Boolean).map(normLang);
        const langs = [...new Set(G.subtitle.map((s) => normLang(s.lang || subInfo(s.url).lang)).filter(Boolean))];
        let use = pref.length ? langs.filter((l) => pref.includes(l)) : langs.slice(0, 5);
        if (!use.length) use = langs.slice(0, 1);
        return use.length ? `<div class="pair">&#128172; Saved with the video: ${use.map((l) => esc(LANG_FULL[l] || l.toUpperCase())).join(', ')}</div>` : '';
      };
      const capHint = () => { // the page has subtitles inside the player but no subtitle file link: offer reading them from the player
        if (G.subtitle.length) return '';
        let has = false; try { has = Array.from(document.querySelectorAll('video')).some((v) => Array.from(v.textTracks || []).some((t) => t.kind === 'subtitles' || t.kind === 'captions')); } catch (e) { has = false; }
        return has ? '<div class="pair" style="color:inherit;background:rgba(255,255,255,.05)">&#128172; This player has subtitles but no file link. <button data-a="subcap" data-fmt="srt">Save subtitles from player</button></div>' : '';
      };
      const secHTML = (k) => {
        if (!G[k].length || (tabSel !== 'all' && tabSel !== k)) return '';
        const open = tabSel === k || secOpen[k] !== false;
        const head = tabSel === 'all' && kinds.length > 1 ? `<div class="sec" data-a="sec" data-k="${k}"><span>${NAMES[k]}</span><small>${G[k].length}</small><i>${open ? '&#9662;' : '&#9656;'}</i></div>` : '';
        const subBar = k === 'subtitle' ? `<div class="pair" style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;color:inherit;background:rgba(255,255,255,.05)"><span style="flex:1 0 100%;font-size:11px;opacity:.75">All subtitles of this page</span><button class="p" data-a="suball" data-fmt="srt">All .srt</button><button data-a="suball" data-fmt="vtt">All .vtt</button><button data-a="suball" data-fmt="txt">All .txt</button><button data-a="subcap" data-fmt="srt" title="Reads the subtitles the video player has already loaded (for subtitles that have no file link)">From player</button></div>` : '';
        return head + (open ? subBar + G[k].map(rowHTML).join('') + (k === 'video' ? pair() + capHint() : '') : '');
      };
      const empty = `<div class="empty">${IC.dl}<b>No video found yet</b><span>Play the video for a second (skip ads), or let Media Grabber press play for you.</span><button class="p" data-a="playscan">${IC.play}<span>Play &amp; scan</span></button></div>`;
      list.innerHTML = mrow + crow + (sorted.length ? tabs + ['video', 'audio', 'subtitle'].map(secHTML).join('') : ((mrow || crow) ? '' : empty));
    }
    restoreScroll(list, st);
    openMore.forEach((k) => { const r = [...list.children].find((x) => x.dataset && x.dataset.key === k); if (r) r.querySelector('.more').classList.add('on'); });
    Object.keys(picked).forEach((k) => { const r = [...list.children].find((x) => x.dataset && x.dataset.key === k); const sel = r && r.querySelector('select[data-a=qsel]'); if (sel) sel.value = picked[k]; });
    Object.keys(typed).forEach((k) => { const r = [...list.children].find((x) => x.dataset && x.dataset.key === k); if (r) Object.keys(typed[k]).forEach((c) => { const i = r.querySelector('.more input.' + c); if (i) i.value = typed[k][c]; }); });
    Object.keys(jobs).forEach(paintJob);
  }

  // small progress box next to the button while the panel is closed
  function paintMini(side) {
    const mini = pillRoot.querySelector('.mini');
    const running = Object.values(jobs).filter((j) => j.state === 'run');
    let text = '', pct = 0;
    if (chProg) { text = `Chapter ${chProg.i}/${chProg.n}${chProg.label ? ': ' + chProg.label : ''}`; pct = chProg.n ? (chProg.i - 1) / chProg.n : 0; }
    else if (running.length) { pct = running.reduce((a, j) => a + (j.pct || 0), 0) / running.length; text = `${running.length} download${running.length > 1 ? 's' : ''} - ${Math.round(pct * 100)}%`; }
    const on = !open && !!text;
    mini.className = 'mini ' + side + (on ? ' on' : '');
    if (!on) return;
    mini.style.top = pillRoot.querySelector('.pill').style.top;
    mini.querySelector('.t').textContent = text;
    mini.querySelector('.bar i').style.width = Math.round(pct * 100) + '%';
  }

  function paintJob(key) {
    if (!pillRoot) return;
    const row = [...pillRoot.querySelectorAll('.row')].find((r) => r.dataset.key === key);
    const j = jobs[key];
    if (!row || !j) return;
    const pr = row.querySelector('.pr');
    pr.classList.add('on');
    row.querySelector('.bar i').style.width = Math.round((j.pct || 0) * 100) + '%';
    const t = row.querySelector('.pt');
    t.className = 'pt' + (j.state === 'error' ? ' err' : j.state === 'done' ? ' ok' : '');
    row.querySelector('.t').textContent = j.text || '';
    const run = j.state === 'run';
    row.querySelector('.pb').style.display = run ? '' : 'none';
    row.querySelector('.pr').classList.toggle('paused', run && !!j.paused);
    const pz = row.querySelector('[data-a=pause]');
    pz.innerHTML = j.paused ? IC.play : IC.pause;
    pz.title = j.paused ? 'Resume' : 'Pause';
    if (run && j.paused) row.querySelector('.t').textContent = 'Paused - ' + String(j.text || '').replace(/^(Paused|Resumed)\s*-?\s*/, '');
  }

  function onAction(b, root, ev) {
    const a = b.dataset.a;
    const row = b.closest('.row');
    const it = row && rec.items.find((i) => i.key === row.dataset.key);
    const ctx = it ? { referer: it.ref || rec.pageUrl, title: rec.title } : null;
    if (a === 'view') { view = b.dataset.k; renderPill(); }
    else if (a === 'diag') showDiag();
    else if (a === 'dgclose') { diagOn = false; const dg = pillRoot.querySelector('.dg'); if (dg) dg.classList.remove('on'); renderPill(); }
    else if (a === 'dgcopy') { const ta = pillRoot.querySelector('.dg textarea'); Promise.resolve(copyText(ta.value)).then(() => toast(root, 'Report copied')).catch(() => toast(root, 'Select the text and copy it')); }
    else if (a === 'wt') { S.fullTitles = !S.fullTitles; try { chrome.storage.sync.set({ fullTitles: S.fullTitles }); } catch (e) { /* ignore */ } renderPill(); }
    else if (a === 'xp') {
      if (ev) ev.preventDefault();
      const el = b.closest('.ch,.jc,.row'), k = xpKey(el);
      if (el) { el.classList.toggle('open'); if (k) { if (el.classList.contains('open')) xpOpen.add(k); else xpOpen.delete(k); } }
    }
    else if (a === 'jpause') { const j = jobs[b.dataset.k]; if (j) { j.paused = !j.paused; send({ type: 'job-pause', key: b.dataset.k, on: j.paused }); renderPill(); } }
    else if (a === 'jcancel') send({ type: 'job-cancel', key: b.dataset.k });
    else if (a === 'jretry') { const k = b.dataset.k, j = jobs[k] || {}, itm = rec.items.find((i) => i.key === k); send({ type: 'start-job', key: k, variant: 'best', ...(itm ? {} : { itemUrl: String(k).replace(/^ext\|/, ''), kind: j.kind || 'hls', ref: location.href }) }); toast(root, 'Retrying...'); }
    else if (a === 'jremove') { delete jobs[b.dataset.k]; send({ type: 'job-remove', key: b.dataset.k }); renderPill(); }
    else if (a === 'jpauseall') { const run = Object.entries(jobs).filter(([, j]) => j.state === 'run'), on = !run.every(([, j]) => j.paused); run.forEach(([k, j]) => { if (!!j.paused !== on) { j.paused = on; send({ type: 'job-pause', key: k, on }); } }); renderPill(); }
    else if (a === 'jcancelall') { Object.entries(jobs).forEach(([k, j]) => { if (j.state === 'run') send({ type: 'job-cancel', key: k }); }); toast(root, 'Cancelling all...'); }
    else if (a === 'reanalyze') { send({ type: 'reanalyze', key: row && row.dataset.key }); toast(root, 'Reading the playlist...'); }
    else if (a === 'jclear') { Object.keys(jobs).forEach((k) => { if (jobs[k].state !== 'run') { delete jobs[k]; send({ type: 'job-remove', key: k }); } }); renderPill(); }
    else if (a === 'tab') { tabSel = b.dataset.k; renderPill(); }
    else if (a === 'sec') { secOpen[b.dataset.k] = secOpen[b.dataset.k] === false; renderPill(); }
    else if (a === 'chk') {
      const u = b.dataset.u;
      if (ev && ev.shiftKey && lastChk && lastChk !== u) {
        const vis = filteredChapters().map((c) => c.url), i1 = vis.indexOf(lastChk), i2 = vis.indexOf(u);
        if (i1 >= 0 && i2 >= 0) vis.slice(Math.min(i1, i2), Math.max(i1, i2) + 1).forEach((x) => (b.checked ? chSel.add(x) : chSel.delete(x)));
      } else b.checked ? chSel.add(u) : chSel.delete(u);
      lastChk = u; renderPill();
    }
    else if (a === 'chopen') {
      chView = true;
      if (!chapters.length) { const p = patternFromUrl(location.href); if (p && !p.hashed && !lnkTxt) { lnkTxt = p.pattern; lnkNums = `${p.n}-${p.n + 11}`; } mwOpen = true; }
      renderPill();
    }
    else if (a === 'chback') { chView = false; renderPill(); }
    else if (a === 'chall') { filteredChapters().forEach((x) => chSel.add(x.url)); renderPill(); }
    else if (a === 'chinv') { filteredChapters().forEach((x) => (chSel.has(x.url) ? chSel.delete(x.url) : chSel.add(x.url))); renderPill(); }
    else if (a === 'challdl') { chapters.forEach((x) => chSel.add(x.url)); runChapters(root, 'cbz'); }
    else if (a === 'chnone') { filteredChapters().forEach((x) => chSel.delete(x.url)); renderPill(); }
    else if (a === 'chnew') { chSel.clear(); chapters.forEach((x) => { if (!doneCh.has(x.url)) chSel.add(x.url); }); renderPill(); }
    else if (a === 'chrev') { chapters.reverse(); renderPill(); }
    else if (a === 'chdl') runChapters(root, 'cbz');
    else if (a === 'chstop') { chStop = true; send({ type: 'batch-stop' }); toast(root, 'Stopping after the current one...'); }
    else if (a === 'chretry') {
      const bad = chapters.filter((x) => { const bd = epBadge(x); return bd && bd[1] === 'err'; });
      if (!bad.length) return toast(root, 'Nothing failed');
      if (bad.some((x) => epSt[x.url])) startEpisodes(root, bad.filter((x) => epSt[x.url]));
      else { chSel.clear(); bad.forEach((x) => chSel.add(x.url)); runChapters(root, 'cbz'); }
    }
    else if (a === 'chread') runChapters(root, 'read');
    else if (a === 'chvid') startEpisodes(root, chapters.filter((c) => chSel.has(c.url)));
    else if (a === 'mwtoggle') mwOpen = !mwOpen;
    else if (a === 'chrange') {
      const nums = chapters.map((c) => epNumOf(c.label, c.url)).filter((n) => n != null);
      const want = new Set(parseNumList(rngTxt, nums.length ? Math.max(...nums) : undefined));
      if (!want.size) return toast(root, 'Type numbers like 5-12, 15, 20-');
      chSel.clear(); chapters.forEach((c) => { const n = epNumOf(c.label, c.url); if (n != null && want.has(Math.floor(n)) && (n === Math.floor(n) || want.has(n))) chSel.add(c.url); });
      toast(root, chSel.size ? `Selected ${chSel.size}` : 'No episode with those numbers in the list', 3000); renderPill();
    }
    else if (a === 'lnkfill') {
      const p = patternFromUrl(location.href);
      if (!p) return toast(root, 'No episode number found in this page address');
      if (p.hashed) return toast(root, 'This site puts a random code in every episode address, so a pattern cannot work. Use the episode list on the page or paste the links.', 6000);
      lnkTxt = p.pattern; lnkNums = `${p.n}-${p.n + 11}`; mwOpen = true; renderPill();
    }
    else if (a === 'lnkgo') {
      const picks = [], seen = new Set();
      const add = (url, label) => { if (/^https?:\/\//i.test(url) && !seen.has(url) && picks.length < 300) { seen.add(url); picks.push({ url, label }); } };
      const nums = parseNumList(lnkNums);
      let plain = 0;
      for (const line of lnkTxt.split(/\r?\n/).map((x) => x.trim()).filter(Boolean)) {
        if (/\{n{1,4}\}/.test(line)) {
          if (!nums.length) return toast(root, 'Add the numbers for {n}, like 1-12 (an open end like 5- does not work here)');
          const urls = expandPattern(line, nums);
          urls.forEach((u, k) => add(u, 'Episode ' + nums[k]));
        } else { // a plain link: number from the address when it has one (episode pages); direct video links just get 1, 2, 3...
          plain++; const tok = line.split(/\s+/)[0], direct = /\.(m3u8|mpd|mp4|webm|mkv)(\?|#|$)/i.test(tok);
          const n = direct ? null : epNumOf('', tok); add(tok, 'Episode ' + (n != null ? n : plain));
        }
      }
      if (!picks.length) return toast(root, 'Paste links (one per line) or a pattern with {n}');
      startEpisodes(root, picks, lnkName.trim());
    }
    else if (a === 'chresume' && epPendCount()) { if (epPend.cap != null) epCap = epPend.cap; if (epPend.miss) epMiss = epPend.miss; startEpisodes(root, chapters.filter((c) => epPend.eps.some((e) => e.url === c.url))); }
    else if (a === 'chresume') { chSel.clear(); (pend ? pend.urls : []).forEach((u) => { if (chapters.some((c) => c.url === u)) chSel.add(u); }); if (chSel.size) runChapters(root, 'cbz'); else toast(root, 'Nothing to resume here'); }
    else if (a === 'follow') toggleFollow(root);
    else if (a === 'chone') runChapters(root, 'read', b.dataset.u);
    else if (a === 'close') setOpen(false);
    else if (a === 'mg') startManga(b.dataset.w, root);
    else if (a === 'playscan') { send({ type: 'play-scan' }); toast(root, 'Pressing play on the page...', 3000); [1800, 3500, 6000].forEach((ms) => setTimeout(() => { sent = new Set(); scan(); refresh(); }, ms)); }
    else if (a === 'rescan') { sent = new Set(); scan(); setTimeout(refresh, 700); }
    else if (a === 'settings') send({ type: 'open-options' });
    else if (a === 'hidesite') {
      const list = (S.disabledSites || []).concat(location.hostname);
      chrome.storage.sync.set({ disabledSites: Array.from(new Set(list)) });
    } else if (a === 'all') { send({ type: 'download-all' }); toast(root, 'Queued everything');
    } else if (a === 'best') {
      const best = sortedItems().find((i) => ['hls', 'dash', 'video'].includes(i.kind) && !(i.info && i.info.protected));
      if (best) dupGuard(root, best.key, () => send({ type: 'start-job', key: best.key, variant: 'best' })); else toast(root, 'Nothing downloadable yet');
    } else if (a === 'dl' && it) {
      let v = b.dataset.v || 'best';
      if (v === 'sel') { const sel = row.querySelector('select[data-a=qsel]'); v = sel ? sel.value : 'best'; }
      dupGuard(root, it.key, () => {
        send({ type: 'start-job', key: it.key, variant: v });
        toast(root, 'Download started - see the Downloads tab', 2200);
        const lb = b.querySelector('span');
        if (lb && b.classList.contains('dlb')) { const old = lb.textContent; lb.textContent = 'Started'; b.classList.add('sent'); setTimeout(() => { lb.textContent = old; b.classList.remove('sent'); }, 1600); }
      });
    }
    else if (a === 'sub' && it) send({ type: 'start-job', key: it.key, fmt: b.dataset.fmt || (b.dataset.srt === '0' ? 'orig' : 'srt') });
    else if (a === 'suball') { chrome.runtime.sendMessage({ type: 'subs-all', fmt: b.dataset.fmt || 'srt' }).then((r) => toast(root, r && r.n ? 'Saving ' + r.n + ' subtitle file' + (r.n > 1 ? 's' : '') + '...' : 'No subtitle found', 2400)).catch(() => {}); }
    else if (a === 'subcap') {
      toast(root, 'Reading subtitles from the player...', 3000);
      chrome.runtime.sendMessage({ type: 'subs-capture', fmt: b.dataset.fmt || 'srt' }).then((r) => toast(root, r && r.n ? 'Saved ' + r.n + ' subtitle file' + (r.n > 1 ? 's' : '') + ' from the player' : 'The player has no readable subtitle yet. Turn subtitles ON in the player, play a few seconds, then try again.', r && r.n ? 2600 : 5200)).catch(() => toast(root, 'Could not read the player', 2400));
    }
    else if (a === 'more') row.querySelector('.more').classList.toggle('on');
    else if (a === 'pause' && it) { const j = jobs[it.key] || (jobs[it.key] = {}); j.paused = !j.paused; send({ type: 'job-pause', key: it.key, on: j.paused }); paintJob(it.key); }
    else if (a === 'cancel' && it) send({ type: 'job-cancel', key: it.key });
    else if (a === 'open' && it) window.open(it.url, '_blank');
    else if (a === 'play' && it) openPreview(it, root);
    else if (a === 'player' && it) openInPlayer(it);
    else if (a === 'pvclose') closePreview(root);
    else if (a === 'pvopen' && pv.item) openInPlayer(pv.item);
    else if (a === 'pvfrom' || a === 'pvto') pvMark(a === 'pvfrom' ? 'from' : 'to', root);
    else if (a === 'pvclip' && pv.item) {
      const st = pv.from == null ? 0 : pv.from, en = pv.to == null ? Infinity : pv.to;
      if (pv.from == null && pv.to == null) { toast(root, 'Play, then press "Start here" and "End here"', 2800); return; }
      if (en <= st) { toast(root, 'The end must be after the start', 2400); return; }
      const rw = [...root.querySelectorAll('.row')].find((x) => x.dataset.key === pv.item.key), qs = rw && rw.querySelector('select[data-a=qsel]');
      send({ type: 'start-job', key: pv.item.key, variant: qs ? qs.value : 'best', clip: Math.floor(st) + '-' + (en === Infinity ? '' : Math.ceil(en)) });
      toast(root, 'Clip download started - see the Downloads tab', 2600);
    }
    else if (a === 'clipgo' && it) {
      const gi = (c) => row.querySelector('.more input.' + c);
      const t1 = toSecT(gi('cf').value), t2 = toSecT(gi('ct').value);
      if (Number.isNaN(t1) || Number.isNaN(t2)) { toast(root, 'Time not understood. Use 90, 1:30 or 1:02:30', 2800); return; }
      if (t1 == null && t2 == null) { toast(root, 'Type a start and/or an end time first', 2400); return; }
      const st = t1 || 0, en = t2 == null ? Infinity : t2;
      if (en <= st) { toast(root, 'The end must be after the start', 2400); return; }
      const qs = row.querySelector('select[data-a=qsel]');
      send({ type: 'start-job', key: it.key, variant: qs ? qs.value : 'best', clip: st + '-' + (en === Infinity ? '' : en) });
      toast(root, 'Clip download started - see the Downloads tab', 2600);
    }
    else if (a === 'clip' && it) { send({ type: 'start-job', key: it.key, variant: 'best', manual: true }); toast(root, 'Opening the quality / clip page...', 2200); }
    else if (a === 'sched' && it) {
      const tm = row.querySelector('.more input.tm');
      if (!tm || !tm.value) { if (tm) tm.focus(); toast(root, 'Pick a start time first', 1800); return; }
      send({ type: 'schedule-job', hhmm: tm.value, job: { itemUrl: it.url, kind: it.kind, ref: it.ref || rec.pageUrl || '', title: rec.title || '', variant: 'best' } });
      toast(root, 'Scheduled for ' + tm.value + ' - see the Queue page', 2600);
    }
    else if (a === 'cp' && it) {
      const c = buildCommands(it, ctx);
      copyText({ url: it.url, ytdlp: c.ytdlp, ffmpeg: c.ffmpeg, curl: c.curl }[b.dataset.w]);
      toast(root, 'Copied');
    }
  }

  // ---- docked preview: plays in the page itself (same cookies / referer as the site), so it works where a separate player tab cannot ----
  let pv = { item: null, from: null, to: null };
  const fmtT = (t) => { t = Math.max(0, Math.floor(t)); const h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), s = t % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0'); };
  function pvMsg(root, t) { const m = root.querySelector('.pvm'); if (m) m.textContent = t || ''; }
  function openPreview(it, root) {
    const box = root.querySelector('.pvbox'), vid = box.querySelector('.pvv');
    document.querySelectorAll('video').forEach((x) => { try { x.pause(); } catch (e) { /* ignore */ } }); // the site's own player would play over it
    pv = { item: it, from: null, to: null };
    root.querySelectorAll('.row.pvon').forEach((x) => x.classList.remove('pvon'));
    const rw = [...root.querySelectorAll('.row')].find((x) => x.dataset.key === it.key); if (rw) rw.classList.add('pvon');
    box.hidden = false;
    box.querySelector('.pvt').textContent = rec.title || baseName(it.url);
    box.querySelector('.pvf').textContent = '-'; box.querySelector('.pvtt').textContent = '-';
    const clip = it.kind === 'hls';
    box.querySelector('.pvc').style.display = clip ? '' : 'none';
    box.querySelector('[data-a=pvclip]').style.display = clip ? '' : 'none';
    pvMsg(root, 'Loading...');
    vid.onloadedmetadata = () => pvMsg(root, '');
    vid.onplaying = () => pvMsg(root, '');
    vid.onerror = () => pvMsg(root, 'This browser cannot play it inside the page (blocked by the site, or HLS is not supported here). Try "Built-in player".');
    vid.src = it.url; vid.load(); vid.play().catch(() => {});
    box.scrollIntoView && box.scrollIntoView({ block: 'nearest' });
  }
  function closePreview(root) {
    const box = root.querySelector('.pvbox'), vid = box && box.querySelector('.pvv');
    if (vid) { try { vid.pause(); } catch (e) { /* ignore */ } vid.removeAttribute('src'); vid.load(); }
    if (box) box.hidden = true;
    root.querySelectorAll('.row.pvon').forEach((x) => x.classList.remove('pvon'));
    pv = { item: null, from: null, to: null };
  }
  function pvMark(which, root) {
    const vid = root.querySelector('.pvv'); if (!pv.item || !vid) return;
    const t = vid.currentTime || 0; pv[which] = t;
    root.querySelector(which === 'from' ? '.pvf' : '.pvtt').textContent = fmtT(t);
    const rw = [...root.querySelectorAll('.row')].find((x) => x.dataset.key === pv.item.key), inp = rw && rw.querySelector('.more input.' + (which === 'from' ? 'cf' : 'ct'));
    if (inp) inp.value = fmtT(t); // the row's own clip boxes follow, so "Download clip" there works too
  }
  function openInPlayer(it) { send({ type: 'open-player', url: it.url, title: rec.title || '', ref: it.ref || rec.pageUrl || '' }); }

  const toSecT = (t) => { t = String(t || '').trim(); if (!t) return null; const p = t.split(':').map(Number); if (p.length > 3 || p.some((x) => !isFinite(x) || x < 0)) return NaN; return p.reduce((a, x) => a * 60 + x, 0); };

  // ================= overlay on <video> (all frames) =================
  let ovHost, ovRoot, ovVideo = null, ovHover = false, hideT, lastMove = 0, recorder = null, recStart = 0, recTick;

  function ensureOverlay() {
    if (ovHost) return;
    ovHost = document.createElement('div');
    ovHost.style.cssText = 'all:initial';
    ovRoot = ovHost.attachShadow({ mode: 'open' });
    ovRoot.innerHTML = `<style>${CSS}
      .ov{position:fixed;display:none;gap:7px;align-items:center}
      .ov.on{display:flex;animation:ovin .22s var(--ease) both}
      @keyframes ovin{from{opacity:0;transform:translateY(-6px) scale(.96)}to{opacity:1;transform:none}}
      .ov button{padding:9px 15px 9px 12px;border-radius:99px;background:rgba(18,20,27,.78);backdrop-filter:blur(14px) saturate(1.5);box-shadow:0 8px 24px -8px rgba(0,0,0,.65),inset 0 0 0 1px rgba(255,255,255,.12);font-weight:650;font-size:13px}
      .ov button:hover{background:rgba(30,33,44,.9)}
      .ov button.p{background:linear-gradient(135deg,#5b8dff,#8b5cff);box-shadow:0 10px 26px -8px rgba(91,110,255,.85),inset 0 0 0 1px rgba(255,255,255,.2)}
      .ov button.p:hover{filter:brightness(1.12)}
      .ov .cn{opacity:.85;font-weight:600}
      .ov .rec{color:#ff8da1}.ov .rec.live{background:#e5253f;color:#fff;animation:blink 1.2s infinite}
      @keyframes blink{50%{opacity:.7}}
    </style><div class="wrap"><div class="ov"><button class="p" data-a="ovdl">${ICON}<span>Download</span><span class="cn"></span></button><button class="rec" data-a="ovrec" title="Record what this player shows (for streams that cannot be downloaded)">&#9210; Rec</button></div></div>`;
    
document.documentElement.append(ovHost);
    const ov = ovRoot.querySelector('.ov');
    ov.addEventListener('pointerenter', () => { ovHover = true; clearTimeout(hideT); });
    ov.addEventListener('pointerleave', () => { ovHover = false; scheduleHide(); });
    ovRoot.addEventListener('click', (e) => {
      const b = e.target.closest('[data-a]');
      if (!b) return;
      e.stopPropagation();
      if (b.dataset.a === 'ovdl') send({ type: 'open-panel' });
      if (b.dataset.a === 'ovrec') recorder ? stopRec() : startRec(ovVideo);
    });
  }

  function videoAt(x, y) {
    const seen = new Set();
    const look = (root) => {
      for (const el of root.elementsFromPoint(x, y)) {
        if (seen.has(el)) continue;
        seen.add(el);
        if (el.tagName === 'VIDEO') return el;
        if (el.shadowRoot) { const v = look(el.shadowRoot); if (v) return v; }
      }
      return null;
    };
    return look(document);
  }

  const canRec = (v) => !!(v && (v.captureStream || v.mozCaptureStream) && window.MediaRecorder);

  function showOverlayFor(v) {
    const r = v.getBoundingClientRect();
    if (r.width < 200 || r.height < 110) return;
    const n = visible().length;
    if (!n && !canRec(v)) return;
    ensureOverlay();
    ovVideo = v;
    clearTimeout(hideT);
    const ov = ovRoot.querySelector('.ov');
    ov.classList.add('on');
    ovRoot.querySelector('[data-a=ovdl]').style.display = n ? '' : 'none';
    ovRoot.querySelector('.cn').textContent = n > 1 ? `(${n})` : '';
    ovRoot.querySelector('.rec').style.display = canRec(v) ? '' : 'none';
    placeOverlay();
    scheduleHide();
  }

  function placeOverlay() {
    if (!ovVideo || !ovRoot) return;
    const ov = ovRoot.querySelector('.ov');
    if (!ov.classList.contains('on')) return;
    const r = ovVideo.getBoundingClientRect();
    ov.style.top = Math.max(8, r.top + 10) + 'px';
    if (S.overlayCorner === 'tr') { ov.style.left = ''; ov.style.right = Math.max(8, innerWidth - r.right + 10) + 'px'; }
    else { ov.style.right = ''; ov.style.left = Math.max(8, r.left + 10) + 'px'; }
    requestAnimationFrame(placeOverlay);
  }

  function scheduleHide() {
    clearTimeout(hideT);
    hideT = setTimeout(() => {
      if (ovHover || recorder || !ovRoot) return;
      ovRoot.querySelector('.ov').classList.remove('on');
    }, 2600);
  }

  document.addEventListener('mousemove', (e) => {
    if (!(siteMode() < 0 ? S.showOverlay : siteMode() === 1) || siteOff()) return;
    const now = Date.now();
    if (now - lastMove < 120) return;
    lastMove = now;
    const v = videoAt(e.clientX, e.clientY);
    if (v) showOverlayFor(v);
  }, { capture: true, passive: true });

  // ================= recorder (fallback for blob/MSE streams) =================
  function startRec(v) {
    if (!canRec(v)) return;
    try {
      const stream = (v.captureStream || v.mozCaptureStream).call(v);
      const mime = ['video/mp4;codecs=avc1,opus', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find((m) => MediaRecorder.isTypeSupported(m));
      const recExt = /mp4/.test(mime) ? 'mp4' : 'webm';
      const chunks = [];
      recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 8e6 });
      recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
      recorder.onstop = () => {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob(chunks, { type: recExt === 'mp4' ? 'video/mp4' : 'video/webm' }));
        a.download = makeName(document.title || 'recording', 'rec', recExt);
        document.body.append(a); a.click();
        setTimeout(() => { a.remove(); URL.revokeObjectURL(a.href); }, 4000);
      };
      v.addEventListener('ended', stopRec, { once: true });
      v.addEventListener('pause', () => recorder && recorder.state === 'recording' && recorder.pause());
      v.addEventListener('play', () => recorder && recorder.state === 'paused' && recorder.resume());
      recorder.start(1000);
      if (v.paused) v.play().catch(() => {});
      recStart = Date.now();
      const b = ovRoot.querySelector('.rec');
      b.classList.add('live');
      recTick = setInterval(() => { b.textContent = '⏹ Stop ' + fmtDur((Date.now() - recStart) / 1000 || 1); }, 500);
    } catch (e) { recorder = null; }
  }
  function stopRec() {
    if (!recorder) return;
    try { recorder.stop(); } catch (e) { /* already stopped */ }
    recorder = null;
    clearInterval(recTick);
    const b = ovRoot.querySelector('.rec');
    b.classList.remove('live'); b.innerHTML = '&#9210; Rec';
    scheduleHide();
  }

  function renderOverlay() { if (!visible().length && ovRoot && !recorder) ovRoot.querySelector('.ov').classList.remove('on'); }

  // ================= try to start the player (used by "Play & scan" and the episode batch) =================
  function tryPlay() {
    const roots = [document];
    const walk = (r) => r.querySelectorAll('*').forEach((e) => { if (e.shadowRoot) { roots.push(e.shadowRoot); walk(e.shadowRoot); } });
    try { walk(document); } catch (e) { /* ignore */ }
    const qa = (s) => roots.flatMap((r) => { try { return [...r.querySelectorAll(s)]; } catch (e) { return []; } });
    const shown = (el) => { const r = el.getBoundingClientRect(); return r.width > 4 && r.height > 4; };
    const fire = (el) => { // real-looking pointer + mouse events, a bare .click() is ignored by many players
      try {
        const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
        const o = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, isPrimary: true };
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach((t) => el.dispatchEvent(t.startsWith('pointer') && window.PointerEvent ? new PointerEvent(t, o) : new MouseEvent(t, o)));
        if (el.click) el.click();
      } catch (e) { /* ignore */ }
    };
    const vids = qa('video');
    vids.forEach((v) => { try { v.muted = true; v.volume = 0; v.setAttribute('playsinline', ''); const p = v.play(); if (p && p.catch) p.catch(() => {}); } catch (e) { /* ignore */ } });
    ['.player-btn', '.jw-icon-display', '.jw-display-icon-container', '.vjs-big-play-button', '.plyr__control--overlaid', '.play-btn', '.play-button', '.btn-play', '.fp-play', '.ytp-large-play-button', '[aria-label="Play"]', '[title="Play"]', '.jwplayer .jw-icon-playback', '.pjsplay', '.loading-play',
      '.art-state', '.dplayer-play-icon', '.dplayer-mobile-play', '.mejs__overlay-play', '.fluid_initial_play', '.vds-play-button', 'media-play-button', '[class*="big-play" i]', '[class*="play-icon" i]', '[class*="PlayButton"]', '[data-plyr="play"]']
      .forEach((s) => qa(s).forEach((b) => { if (shown(b)) fire(b); }));
    setTimeout(() => { // still paused after that: tap the player itself (what a finger would do)
      vids.filter((v) => v.paused && shown(v)).sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight).slice(0, 1).forEach((v) => { fire(v); try { v.play().catch(() => {}); } catch (e) { /* ignore */ } });
    }, 600);
  }

  // ================= messages / settings =================
  chrome.runtime.onMessage.addListener((msg) => {
    if (!msg) return;
    if (msg.type === 'scan') { sent = new Set(); scan(); }
    else if (msg.type === 'try-play') { tryPlay(); setTimeout(() => { sent = new Set(); scan(); }, 1500); }
    else if (msg.type === 'items-changed') refresh();
    else if (msg.type === 'batch-status' && IS_TOP) { ensurePill(); if (pillRoot) toast(pillRoot, msg.text, 6000); }
    else if (msg.type === 'toggle-panel' && IS_TOP) { refresh().then(() => { ensurePill(); if (pillHost) { pillHost.style.display = ''; setOpen(!open); } }); }
    else if (msg.type === 'grab-pages' && IS_TOP) { ensurePill(); if (pillRoot) startManga(/^(cbz|zip|pdf|epub|read|view)$/.test(msg.how || '') ? msg.how : 'cbz', pillRoot); } // MGB S55: msg.how from the native panel
    else if (msg.type === 'diagnose' && IS_TOP) { diagOn = true; refresh().then(() => { ensurePill(); if (pillHost) { pillHost.style.display = ''; setOpen(true); showDiag(); } }); }
    else if (msg.type === 'open-panel' && IS_TOP) { refresh().then(() => { ensurePill(); if (pillHost) { pillHost.style.display = ''; setOpen(true); } }); }
    else if (msg.type === 'batch-ep' && IS_TOP) { epSt[msg.url] = { st: msg.state, key: msg.key || (epSt[msg.url] || {}).key, why: msg.why || '', plain: msg.plain || '', q: msg.q || (epSt[msg.url] || {}).q || '' }; renderSoon(); }
    else if (msg.type === 'batch-ask' && IS_TOP) { askQuality(msg); }
    else if (msg.type === 'batch-end' && IS_TOP) { batchOn = false; getEpPend().then((p) => { epPend = p; renderPill(); }); }
    else if (msg.type === 'job-update' && IS_TOP) {
      const prevJ = jobs[msg.key];
      jobs[msg.key] = { t: Date.now(), state: msg.state, pct: msg.pct >= 0 ? msg.pct : (jobs[msg.key] || {}).pct, text: msg.text, paused: msg.state === 'run' && (/^Paused/.test(msg.text || '') ? true : /^Resumed/.test(msg.text || '') ? false : !!(jobs[msg.key] || {}).paused) };
      if (msg.title) jobs[msg.key].title = msg.title; else if (prevJ && prevJ.title) jobs[msg.key].title = prevJ.title;
      if (msg.kind) jobs[msg.key].kind = msg.kind; else if (prevJ && prevJ.kind) jobs[msg.key].kind = prevJ.kind;
      if (msg.state !== 'run' && msg.state !== 'error' && msg.state !== 'done') delete jobs[msg.key];
      renderSoon();
    }
  });
  // ---- live batch state written by the background: progress, per-episode status and the quality question show up even when tab messages do not arrive ----
  const applyLive = (b) => {
    if (!IS_TOP || !b || myTab == null || b.tab !== myTab) return;
    if (liveB && b.t && liveB.t && b.t < liveB.t) return;
    liveB = b;
    Object.keys(b.eps || {}).forEach((u) => { const e = b.eps[u]; const o = epSt[u] || {}; epSt[u] = { st: e.st, key: e.key || o.key, why: e.why || '', plain: e.plain || '', q: e.q || o.q || '' }; });
    const wasOn = batchOn; batchOn = !!b.running;
    if (b.ask) askQuality(b.ask);
    else if (pillRoot) { const o = pillRoot.querySelector('.mgask'); if (o) o.remove(); }
    if (b.running || wasOn) { ensurePill(); if (pillHost && b.running) pillHost.style.display = ''; }
    if (wasOn && !batchOn) getEpPend().then((p) => { epPend = p; renderPill(); });
    renderSoon();
  };
  if (IS_TOP) {
    chrome.runtime.sendMessage({ type: 'whoami' }).then((r) => {
      myTab = r && r.tab != null ? r.tab : null;
      chrome.storage.local.get('mgBatchLive').then((v) => { const b = v.mgBatchLive; if (b && b.tab === myTab && (b.running || b.interrupted)) applyLive(b); }).catch(() => {});
    }).catch(() => {});
    chrome.storage.onChanged.addListener((c, area) => { if (area === 'local' && c.mgBatchLive) applyLive(c.mgBatchLive.newValue); });
  }
  chrome.storage.onChanged.addListener((c, area) => {
    if (area !== 'sync') return;
    for (const k in c) S[k] = c[k].newValue === undefined ? DEFAULTS[k] : c[k].newValue;
    try { document.documentElement.dataset.mgSite = S.siteModules === false ? 'off' : 'on'; document.documentElement.dataset.mgMaxFetch = S.siteMaxFetch === false ? 'off' : 'on'; } catch (e) { /* ignore */ }
    renderAll();
  });

  (async () => {
    try { S = await getSettings(); } catch (e) { /* ignore */ }
    try { document.documentElement.dataset.mgSite = S.siteModules === false ? 'off' : 'on'; document.documentElement.dataset.mgMaxFetch = S.siteMaxFetch === false ? 'off' : 'on'; } catch (e) { /* ignore */ }
    pend = await getPending();
    epPend = await getEpPend();
    scan();
    refresh();
  })();
})();
