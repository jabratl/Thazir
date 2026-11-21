import { findNextInfo, extractPage, scriptFallback, fromString, longestString, jsonPick, findMain, esc, bestText } from './lib/extract.js';
import { buildEpub, makeCss } from './lib/epub.js';
import { buildTxt, buildTxtZip, buildDocx } from './lib/export.js';
import * as C from './lib/catalog.js';
import * as H from './lib/headings.js';

const $ = id => document.getElementById(id);
const PRESETS = [{
  re: /(^|\.)webnovel\.com$/,
  set: { inTab: true, fetchMode: 'fast', renderWait: 0, delay: 150, conc: 2, speed: '', titleMode: 'toc', perVol: 100, stopLocked: true,
    contentSel: 'div.chapter_content', removeSel: 'i.para-comment_num, i.para-comment, div.j_bottom_comment_area, div.user-links-wrap, div.g_ad_ph, [class*="cha-tip"], [class*="cha-hint"]' },
  note: 'Webnovel.com detected → fast preset applied (plain HTTP like WebToEpub, no tab rendering, 2 parallel, 150 ms). Press “Find catalog”. If chapters come back empty, switch Fetch method to Auto.'
}];
const qs = new URLSearchParams(location.search);
let tab = null, tabInfo = null, tabDoc = null, toc = [], cancel = false;
const FIELDS = ['mode', 'maxCh', 'delay', 'contentSel', 'nextSel', 'removeSel', 'replRules', 'author', 'lang', 'optImages', 'optConvert', 'optTab',
  'fontSize', 'lineHeight', 'align', 'indent', 'titleMode', 'tocFilter',
  'fetchMode', 'renderWait', 'pageRe', 'apiUrl', 'apiBody', 'apiFrom', 'apiTo', 'apiPath', 'apiTitle', 'conc', 'perVol', 'autoOrder', 'stopLocked', 'speed', 'apiFast', 'apiTpl', 'inTab', 'outFmt', 'smartOn', 'smartLang', 'smartPre', 'smartSuf', 'smartSplit', 'useCache', 'imgCompress', 'imgMax', 'imgType', 'stripNum', 'fileName'];
const log = (m, cls = '') => { const d = document.createElement('div'); d.textContent = m; if (cls) d.className = cls; $('log').append(d); $('log').scrollTop = 1e9; };

// Thazir: blob -> chrome.downloads (DownloadsApi reads blob: urls from this window) so the file lands in the Thazir download folder.
async function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const later = () => setTimeout(() => URL.revokeObjectURL(url), 600000);
  try {
    if (chrome.downloads && chrome.downloads.download) {
      const id = await chrome.downloads.download({ url, filename: name, saveAs: false });
      later();
      await addHistory({ id: id || 0, title: name.split('/').pop(), kind: 'novel', site: host(srcUrlNow()), src: srcUrlNow(), size: blob.size });
      return;
    }
  } catch (e) { log('Save through Thazir downloads failed (' + ((e && e.message) || e) + ') - trying a direct link.', 'warn'); }
  const a = document.createElement('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove(); later();
}
const srcUrlNow = () => { const e = document.getElementById('srcUrl'); return (e && e.value) || ''; };
// same record V77 writes, so the file shows in the Thazir Downloads screen (Saved files) with Open / Show / Share
async function addHistory(e) {
  try {
    const r = await chrome.storage.local.get('mgHist'), a = r.mgHist || [];
    a.unshift({ ...e, time: Date.now() });
    await chrome.storage.local.set({ mgHist: a.slice(0, 500) });
  } catch { }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const host = u => { try { return new URL(u).hostname; } catch { return 'default'; } };
const norm = u => { try { const x = new URL(u); x.hash = ''; return x.href; } catch { return u; } };

async function snap(opts) {
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  opts = opts || {};
  if (opts.scroll) { let last = -1; for (let i = 0; i < 25; i++) { window.scrollTo(0, document.body.scrollHeight); await sleep(250); const h = document.body.scrollHeight; if (h === last && i > 2) break; last = h; } window.scrollTo(0, 0); }
  if (opts.expand) {
    const re = /load\s*more|show\s*(all|more)|view\s*(all|more)|more\s*chapters?|all\s*chapters?|see\s*all|expand|table of contents|chapter list|chapters|সব\s*দেখ|আরও|更多|展开|展開|全部|目录|目錄/i, done = new WeakSet();
    for (let r = 0; r < 40; r++) {
      let progressed = false;
      for (const el of document.querySelectorAll('button,[role=button],[role=tab],a[href="#"],a[href^="javascript"],summary,[class*=load-more],[class*=loadmore],[class*=show-more],[class*=view-all]')) {
        if (done.has(el) || el.offsetParent === null) continue;
        const t = (el.textContent || el.getAttribute('aria-label') || '').trim(); if (t.length > 40 || !re.test(t)) continue;
        const before = document.querySelectorAll('a[href]').length;
        try { el.click(); } catch { }
        await sleep(900);
        if (document.querySelectorAll('a[href]').length <= before) done.add(el); else progressed = true;
      }
      window.scrollTo(0, document.body.scrollHeight); await sleep(350);
      if (!progressed) break;
    }
    document.querySelectorAll('details').forEach(d => { d.open = true; });
    window.scrollTo(0, 0);
  }
  if (opts.waitSel) { for (let i = 0; i < 40; i++) { try { if (document.querySelector(opts.waitSel)) break; } catch { break; } await sleep(250); } }
  if (opts.settle) await sleep(opts.settle);
  const extra = [];
  const collect = (root, d) => {
    if (d > 4) return;
    for (const el of root.querySelectorAll('*')) {
      if (el.shadowRoot) { extra.push(el.shadowRoot.innerHTML); collect(el.shadowRoot, d + 1); }
      if (el.tagName === 'IFRAME') { try { const dd = el.contentDocument; if (dd && dd.body) { extra.push(dd.body.innerHTML); collect(dd, d + 1); } } catch { } }
    }
  };
  collect(document, 0);
  let html = document.documentElement.outerHTML;
  if (extra.length) { const ins = '<div id="__w2e_extra">' + extra.map(x => '<div>' + x + '</div>').join('') + '</div>', i = html.lastIndexOf('</body>'); html = i >= 0 ? html.slice(0, i) + ins + html.slice(i) : html + ins; }
  const m = n => { const e = document.querySelector(n); return e ? (e.content || '') : ''; };
  return {
    html, url: location.href, title: document.title,
    links: [...document.querySelectorAll('a[href]')].map(a => ({ href: a.href, text: ((a) => { const c = s => (s || '').replace(/\s+/g, ' ').trim(); let t = c(a.textContent); for (const k of ['title', 'aria-label', 'data-title']) { const x = c(a.getAttribute(k)); if (x && x.length > t.length && x.length <= 140 && (!t || x.includes(t))) t = x; } if (t.length <= 16) { const row = a.closest('li,tr,dd,dt,.chapter,.chapter-item,.item'); if (row && row.querySelectorAll('a[href]').length === 1) { let rt = c(row.textContent); for (let i = 0; i < 3; i++) rt = rt.replace(/\s*(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\bVIP\b|\bNEW\b|\bHOT\b|最新|更新)\s*$/i, '').trim(); if (rt.length > t.length && rt.length <= 140 && rt.startsWith(t)) t = rt; } } return t; })(a).slice(0, 120) })).filter(l => /^https?:/.test(l.href)),
    author: m('meta[name="author"]') || m('meta[property="article:author"]'), image: m('meta[property="og:image"]'),
    desc: m('meta[property="og:description"]') || m('meta[name="description"]'), lang: document.documentElement.lang || '',
    site: m('meta[property="og:site_name"]')
  };
}

async function init() {
  try {
    let id = +qs.get('tabId');
    if (!id) { // Thazir: the grabber is a full-screen window (no tab of its own) -> use the page the user was reading
      const act = await chrome.tabs.query({ active: true });
      const t = (act || []).find(x => /^https?:/i.test(x.url || ''));
      if (!t) throw new Error('Open a novel / chapter page in a tab first (or paste a URL).');
      id = t.id;
    }
    tab = await chrome.tabs.get(id);
    const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: id }, func: snap, args: [{}] });
    tabInfo = result; tabDoc = new DOMParser().parseFromString(result.html, 'text/html');
    $('srcUrl').value = result.url;
    $('title').value = (result.title || '').split(/\s[|–—]\s|[_|｜]/)[0].trim();
    $('author').value = result.author || result.site || '';
    $('coverUrl').value = result.image || '';
    $('desc').value = (result.desc || '').slice(0, 500);
    $('lang').value = (result.lang || 'en').split('-')[0];
    $('status').textContent = host(result.url);
    const saved = (await chrome.storage.local.get('s:' + host(result.url)))['s:' + host(result.url)] || {};
    for (const k of FIELDS) if (k in saved) { const e = $(k); e.type === 'checkbox' ? e.checked = saved[k] : e.value = saved[k]; }
    smartUI();
    const pre = PRESETS.find(p => p.re.test(host(result.url)));
    if (pre && !saved.mode) {
      for (const [k, v] of Object.entries(pre.set)) { const e = $(k); e.type === 'checkbox' ? e.checked = v : e.value = v; }
      $('mode').value = 'toc'; log(pre.note, 'ok');
    }
    buildToc(result.links);
    const nSel = toc.filter(t => t.checked).length;
    if (!saved.mode && nSel >= 15) { $('mode').value = 'toc'; log(`This looks like a book / index page: ${nSel} chapter links auto-selected → “Chapter list” mode.`, 'ok'); }
  } catch (e) { log('Could not read the source tab (chrome:// pages are blocked). Paste a URL manually. ' + e.message, 'warn'); $('status').textContent = 'manual'; }
  onMode();
}

function buildToc(links) {
  const seen = new Set(), list = [];
  for (const l of links) { const h = norm(l.href); if (seen.has(h) || !l.text) continue; seen.add(h); list.push({ href: h, text: l.text, checked: false }); }
  toc = list; autoDetect(); renderToc();
}
function autoDetect() {
  const groups = new Map();
  for (const t of toc) {
    try {
      const u = new URL(t.href), last = u.pathname.split('/').pop();
      const key = u.origin + u.pathname.replace(/[^/]*$/, '') + (/\d/.test(last) ? '#' : '');
      (groups.get(key) || groups.set(key, []).get(key)).push(t);
    } catch { }
  }
  let best = []; for (const g of groups.values()) if (g.length > best.length) best = g;
  toc.forEach(t => { t.checked = false; t.grp = false; });
  if (best.length >= 3) best.forEach(t => { t.checked = true; t.grp = true; });
  populateRange();
}
// ---- first / last chapter range (WebToEpub style) ----
let lastIdx = -1;
function base() { const g = toc.filter(t => t.grp); return ($('tocOnly').checked && g.length) ? g : toc; }
function populateRange() {
  const B = base(), f = $('rngFrom'), t = $('rngTo'); f.textContent = ''; t.textContent = '';
  B.forEach((x, i) => { const label = `${i + 1}. ${x.text.slice(0, 40)}`; f.add(new Option(label, i)); t.add(new Option(label, i)); });
  const idx = B.map((x, i) => x.checked ? i : -1).filter(i => i >= 0);
  f.value = idx.length ? idx[0] : 0; t.value = idx.length ? idx[idx.length - 1] : Math.max(0, B.length - 1);
}
function applyRange() {
  const B = base(); if (!B.length) return;
  let a = +$('rngFrom').value, b = +$('rngTo').value; if (a > b) [a, b] = [b, a];
  toc.forEach(x => x.checked = false); B.forEach((x, i) => { x.checked = i >= a && i <= b; }); renderToc();
}
function shown() {
  const B = base(), f = $('tocFilter').value.trim(); if (!f) return B;
  let re; try { re = new RegExp(f, 'i'); } catch { re = null; }
  return B.filter(t => re ? re.test(t.text + ' ' + t.href) : (t.text + t.href).toLowerCase().includes(f.toLowerCase()));
}
function renderToc() {
  const box = $('tocList'); box.textContent = '';
  const vis = shown();
  for (const t of vis) {
    const l = document.createElement('label'), c = document.createElement('input'), s = document.createElement('span');
    c.type = 'checkbox'; c.checked = t.checked; c.onchange = () => { t.checked = c.checked; count(); };
    c.onclick = e => { const i = vis.indexOf(t); if (e.shiftKey && lastIdx >= 0) { const [a, b] = [Math.min(lastIdx, i), Math.max(lastIdx, i)]; for (let k = a; k <= b; k++) vis[k].checked = c.checked; lastIdx = i; renderToc(); return; } lastIdx = i; };
    s.textContent = (t.locked ? '🔒 ' : '') + t.text; s.title = t.href; l.append(c, s); box.append(l);
  }
  count(vis.length);
}
function count(n) { $('tocCount').textContent = `${toc.filter(t => t.checked).length} selected · ${n ?? shown().length} shown · ${toc.length} links total`; }
function onMode() { $('tocCard').hidden = $('mode').value !== 'toc'; $('apiCard').hidden = $('mode').value !== 'api'; }

$('mode').onchange = onMode;
$('tocFilter').oninput = renderToc;
$('btnAuto').onclick = () => { autoDetect(); renderToc(); };
$('btnAll').onclick = () => { shown().forEach(t => t.checked = true); renderToc(); };
$('btnNone').onclick = () => { toc.forEach(t => t.checked = false); renderToc(); };
$('btnRev').onclick = () => { toc.reverse(); populateRange(); renderToc(); };
$('rngFrom').onchange = $('rngTo').onchange = applyRange;
$('tocOnly').onchange = () => { populateRange(); renderToc(); };

// ---------- fetching ----------
function decode(buf, ct) {
  let cs = (/charset=([\w-]+)/i.exec(ct || '') || [])[1];
  if (!cs) { const head = new TextDecoder('latin1').decode(buf.slice(0, 2048)); cs = (/<meta[^>]+charset=["']?([\w-]+)/i.exec(head) || [])[1]; }
  try { return new TextDecoder(cs || 'utf-8').decode(buf); } catch { return new TextDecoder('utf-8').decode(buf); }
}
// ---------- image compression (like WebToEpub "compress images") ----------
const IMGOPT = { compress: false, max: 1080, type: 'jpg' };
let STRIP = false, SMART = null;
function isAnimated(b, mime) {
  if (mime === 'image/gif') return true;
  if (mime === 'image/png') { for (let i = 8; i < Math.min(b.length - 4, 4096); i++) if (b[i] === 0x61 && b[i + 1] === 0x63 && b[i + 2] === 0x54 && b[i + 3] === 0x4C) return true; }
  if (mime === 'image/webp') { for (let i = 12; i < Math.min(b.length - 4, 200); i++) if (b[i] === 0x41 && b[i + 1] === 0x4E && b[i + 2] === 0x49 && b[i + 3] === 0x4D) return true; }
  return false;
}
async function shrink(bytes, mime) {
  const bmp = await createImageBitmap(new Blob([bytes], { type: mime }));
  const k = Math.min(1, IMGOPT.max / Math.max(bmp.width, bmp.height)), w = Math.max(1, Math.round(bmp.width * k)), h = Math.max(1, Math.round(bmp.height * k));
  const c = new OffscreenCanvas(w, h), g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, w, h); g.drawImage(bmp, 0, 0, w, h);
  const type = IMGOPT.type === 'webp' ? 'image/webp' : 'image/jpeg';
  return { mime: type, bytes: new Uint8Array(await (await c.convertToBlob({ type, quality: 0.85 })).arrayBuffer()), resized: k < 1 };
}

// ---------- chapter cache: resume after a crash / update an ongoing novel / retry only the failed ones ----------
const openDb = () => new Promise((res, rej) => { const q = indexedDB.open('w2e', 1); q.onupgradeneeded = () => { q.result.createObjectStore('ch'); q.result.createObjectStore('img'); }; q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); });
let _db = null;
const idb = async (store, mode, fn) => { _db = _db || await openDb(); return new Promise((res, rej) => { const tx = _db.transaction(store, mode), rq = fn(tx.objectStore(store)); tx.oncomplete = () => res(rq && rq.result); tx.onerror = tx.onabort = () => rej(tx.error); }); };
const cacheGet = (store, key) => idb(store, 'readonly', s => s.get(key)).catch(() => null);
const cachePut = (store, key, val) => idb(store, 'readwrite', s => s.put(val, key)).catch(() => { });
const cacheClear = async () => { await idb('ch', 'readwrite', s => s.clear()); await idb('img', 'readwrite', s => s.clear()); };

// ---------- Cloudflare-style partitioned cookies (as WebToEpub does) + smarter retry ----------
const cfDone = new Set();
async function fixCookies(url) {
  const h = host(url); if (cfDone.has(h) || !chrome.cookies) return; cfDone.add(h);
  try {
    const dom = h.split('.').slice(-2).join('.'), all = (await chrome.cookies.getAll({ domain: dom, partitionKey: {} })).filter(c => c.partitionKey); let n = 0;
    for (const c of all) {
      const d = c.domain.replace(/^\./, '');
      await chrome.cookies.set({ url: `http${c.secure ? 's' : ''}://${d}${c.path || '/'}`, name: c.name, value: c.value, domain: c.domain, path: c.path, secure: c.secure, httpOnly: c.httpOnly, ...(c.expirationDate ? { expirationDate: c.expirationDate } : {}) }); n++;
    }
    if (n) log(`Copied ${n} protected cookie(s) for ${dom} so fetch can use them.`, 'ok');
  } catch { }
}
const WAITS = [5, 15, 30, 60];
async function waitSec(sec, msg) { log(msg, 'warn'); for (let s = 0; s < sec && !cancel; s++) await sleep(1000); }
async function fetchDoc(url) {
  let err;
  for (let i = 0; i <= WAITS.length; i++) {
    if (i && cancel) break;
    try {
      const r = await fetch(url, { credentials: 'include' });
      if (!r.ok) { const e = new Error('HTTP ' + r.status); e.status = r.status; throw e; }
      return new DOMParser().parseFromString(decode(await r.arrayBuffer(), r.headers.get('content-type')), 'text/html');
    } catch (e) {
      err = e; const st = e.status;
      if (st === 404 || st === 410 || st === 401) break;
      if (st === 403 && i === 0) { await fixCookies(url); await sleep(400); continue; }
      if (i < WAITS.length) { const w = (st === 429 || st >= 500) ? WAITS[i] : 2 + i * 2; await waitSec(w, `  ${st ? 'HTTP ' + st : 'network error'} — waiting ${w}s then retrying (${i + 1}/${WAITS.length})`); }
    }
  }
  if (err && err.status === 403) err.message += ' — open this site in the browser, pass the Cloudflare/robot check, then press Build again (finished chapters are cached)';
  throw err;
}
function sniff(b) {
  if (b[0] === 0xFF && b[1] === 0xD8) return 'image/jpeg';
  if (b[0] === 0x89 && b[1] === 0x50) return 'image/png';
  if (b[0] === 0x47 && b[1] === 0x49) return 'image/gif';
  if (b[0] === 0x52 && b[8] === 0x57 && b[9] === 0x45) return 'image/webp';
  if (b[4] === 0x66 && b[5] === 0x74 && b[8] === 0x61 && b[9] === 0x76) return 'image/avif';
  if (new TextDecoder().decode(b.slice(0, 200)).includes('<svg')) return 'image/svg+xml';
  return '';
}
async function toJpeg(bytes, mime) {
  const bmp = await createImageBitmap(new Blob([bytes], { type: mime }));
  const c = new OffscreenCanvas(bmp.width, bmp.height), g = c.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); g.drawImage(bmp, 0, 0);
  return new Uint8Array(await (await c.convertToBlob({ type: 'image/jpeg', quality: 0.9 })).arrayBuffer());
}
async function getImage(url, convert) {
  try {
    const r = await fetch(url, { credentials: 'include' });
    if (!r.ok) return null;
    let bytes = new Uint8Array(await r.arrayBuffer());
    if (bytes.length < 300) return null;
    let mime = (r.headers.get('content-type') || '').split(';')[0].trim();
    if (!mime.startsWith('image/')) mime = sniff(bytes);
    const s = sniff(bytes); if (s) mime = s;
    if (!mime) return null;
    if (!['image/jpeg', 'image/png', 'image/gif', 'image/svg+xml'].includes(mime)) {
      if (!convert) return null;
      bytes = await toJpeg(bytes, mime); mime = 'image/jpeg';
    }
    if (IMGOPT.compress && mime !== 'image/svg+xml' && !isAnimated(bytes, mime)) {
      try { const z = await shrink(bytes, mime); if (z.resized || z.bytes.length < bytes.length) { bytes = z.bytes; mime = z.mime; } } catch { }
    }
    return { mime, bytes };
  } catch { return null; }
}
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/svg+xml': 'svg', 'image/webp': 'webp' };
async function pool(items, n, fn) { let i = 0; await Promise.all(Array.from({ length: n }, async () => { while (i < items.length && !cancel) await fn(items[i++]); })); }
async function loadImages(imgs, store, o) {
  const todo = []; for (let i = imgs.done || 0; i < imgs.list.length; i++) if (!store.has(i)) todo.push(i);
  imgs.done = imgs.list.length;
  await pool(todo, 4, async i => {
    const r = await getImage(imgs.list[i], o.convert);
    store.set(i, r ? { name: `img${i + 1}.${EXT[r.mime]}`, mime: r.mime, bytes: r.bytes } : null);
  });
}

// ---------- rendered (real browser) loading ----------
let rtab = null, rlock = Promise.resolve();
// Android / mobile Chromium browsers cannot open a hidden background tab -> the page would pop up visibly.
const CAN_HIDE = !/Android|Mobile/i.test(navigator.userAgent);
const mayRender = o => o.fetchMode === 'rendered' || (o.fetchMode !== 'fast' && CAN_HIDE);
let selfTabId = null; chrome.tabs.getCurrent().then(t => { if (t) selfTabId = t.id; }).catch(() => { });
const backToStudio = () => { if (selfTabId != null) chrome.tabs.update(selfTabId, { active: true }).catch(() => { }); else chrome.windows.update(1, { focused: true }).catch(() => { }); }; // Thazir: window was tucked away while the page is shown
function renderDoc(url, o) { const run = rlock.then(() => renderDoc0(url, o)); rlock = run.catch(() => { }); return run; }
async function renderDoc0(url, o) {
  if (!rtab) rtab = await chrome.tabs.create({ url: 'about:blank', active: false });
  await new Promise(res => {
    const f = (id, info) => { if (id === rtab.id && info.status === 'complete') { clearTimeout(t); chrome.tabs.onUpdated.removeListener(f); res(); } };
    const t = setTimeout(() => { chrome.tabs.onUpdated.removeListener(f); res(); }, 30000);
    chrome.tabs.onUpdated.addListener(f); chrome.tabs.update(rtab.id, { url }); if (!CAN_HIDE) setTimeout(backToStudio, 300);
  });
  const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: rtab.id }, func: snap, args: [{ scroll: true, expand: !!o.expand, waitSel: o.expand ? '' : o.contentSel, settle: o.renderWait }] });
  if (!CAN_HIDE) backToStudio();
  return new DOMParser().parseFromString(result.html, 'text/html');
}
function weak(d, o) {
  let root = null; if (o.contentSel) { try { root = d.querySelector(o.contentSel); } catch { } }
  root = root || findMain(d);
  return (root.textContent || '').trim().length < 200 && !scriptFallback(d);
}
function autoTitle(o, d = 0) {
  if (!o || typeof o !== 'object' || d > 5) return '';
  for (const k of ['title', 'chapter_title', 'chapterTitle', 'chapter_name', 'chapterName', 'name']) if (typeof o[k] === 'string' && o[k].length < 150) return o[k];
  for (const v of Object.values(o)) { const t = autoTitle(v, d + 1); if (t) return t; }
  return '';
}
$('btnDetect').onclick = async () => {
  const box = $('apiList'); if (!tab) return box.textContent = 'Open this from a page tab first.';
  box.textContent = 'Scanning network calls of the source tab…';
  try {
    const [{ result: urls }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => [...new Set(performance.getEntriesByType('resource').filter(e => ['fetch', 'xmlhttprequest'].includes(e.initiatorType)).map(e => e.name))].slice(-60) });
    box.textContent = ''; let found = 0;
    for (const u of urls) {
      try {
        const r = await fetch(u, { credentials: 'include' }); if (!r.ok) continue;
        const hit = longestString(await r.json(), 300); if (!hit) continue; found++;
        const b = document.createElement('button'); b.textContent = `${u.slice(0, 90)}  →  ${hit.path} (${hit.value.length} chars)`; b.title = u;
        b.onclick = () => {
          let t = u; const nums = [...tabInfo.url.matchAll(/\d+/g)].map(x => x[0]), last = nums.pop();
          if (last && t.includes(last)) t = t.replace(new RegExp(last + '(?!.*' + last + ')'), '{n}');
          $('apiUrl').value = t; $('apiPath').value = hit.path; if (last) $('apiFrom').value = last;
          log('API selected. Check that {n} is in the right place, then set From/To.', 'ok');
        };
        box.append(b);
      } catch { }
    }
    if (!found) box.textContent = 'No JSON call with chapter text found. Try Fetch method = “Rendered browser”.';
  } catch (e) { box.textContent = 'Detect failed: ' + e.message; }
};

// ---------- cover ----------
function wrap(g, text, max) {
  const out = []; let cur = '';
  for (let w of text.split(/\s+/)) {
    while (g.measureText(w).width > max) { let k = w.length; while (k > 1 && g.measureText(w.slice(0, k)).width > max) k--; if (cur) { out.push(cur); cur = ''; } out.push(w.slice(0, k)); w = w.slice(k); }
    const t = cur ? cur + ' ' + w : w;
    if (g.measureText(t).width > max && cur) { out.push(cur); cur = w; } else cur = t;
  }
  if (cur) out.push(cur); return out;
}
async function makeCover(title, author) {
  const W = 1200, H = 1800, c = new OffscreenCanvas(W, H), g = c.getContext('2d');
  const hue = [...title].reduce((a, ch) => a + ch.charCodeAt(0), 0) % 360;
  const gr = g.createLinearGradient(0, 0, W, H); gr.addColorStop(0, `hsl(${hue},55%,28%)`); gr.addColorStop(1, `hsl(${(hue + 50) % 360},60%,12%)`);
  g.fillStyle = gr; g.fillRect(0, 0, W, H);
  g.strokeStyle = 'rgba(255,255,255,.35)'; g.lineWidth = 6; g.strokeRect(70, 70, W - 140, H - 140);
  g.fillStyle = '#fff'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = 'bold 110px system-ui,sans-serif';
  const lines = wrap(g, title, W - 260).slice(0, 7), lh = 135, y0 = H * 0.42 - (lines.length - 1) * lh / 2;
  lines.forEach((l, i) => g.fillText(l, W / 2, y0 + i * lh));
  g.font = '56px system-ui,sans-serif'; g.fillStyle = 'rgba(255,255,255,.85)'; g.fillText(author || '', W / 2, H - 240);
  const b = await c.convertToBlob({ type: 'image/jpeg', quality: 0.92 });
  return { mime: 'image/jpeg', bytes: new Uint8Array(await b.arrayBuffer()) };
}

// ---------- cover chain (0.0.484) ----------
// Every step is tried in turn. A step that fails only moves on to the next one (finally: the generated cover, then no cover);
// nothing in here is allowed to stop a build or an export.
const COVER_MIN = 240;   // shortest side (px) of a "real" cover; smaller pictures (logos, icons) are kept only as a last resort
const _cvMsg = e => (e && e.message) || String(e);
const _cvTime = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);
async function coverNorm(bytes, mime) {   // -> { mime, bytes, w, h } as JPEG / PNG, or null
  if (!bytes || bytes.length < 300) return null;
  const s = sniff(bytes); if (s) mime = s;
  if (!mime || !String(mime).startsWith('image/')) return null;
  if (mime !== 'image/jpeg' && mime !== 'image/png') { bytes = await toJpeg(bytes, mime); mime = 'image/jpeg'; }
  const bmp = await createImageBitmap(new Blob([bytes], { type: mime }));   // throws when the file is not a real picture
  const w = bmp.width, h = bmp.height; if (bmp.close) bmp.close();
  if (!w || !h) return null;
  return { mime, bytes, w, h };
}
async function coverAnon(url) {          // no cookies, no Referer: some sites refuse the browser's own sign-in / referrer
  const ac = new AbortController(), t = setTimeout(() => ac.abort(), 15000);
  try {
    const r = await fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer', signal: ac.signal });
    if (!r.ok) return null;
    return { bytes: new Uint8Array(await r.arrayBuffer()), mime: (r.headers.get('content-type') || '').split(';')[0].trim() };
  } finally { clearTimeout(t); }
}
async function coverViaPage(url) {       // fetch from inside the novel page itself: right Referer + cookies (hotlink protection)
  if (!tab || !tab.id) return null;
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: async u => {
      try {
        const r = await fetch(u, { credentials: 'include' }); if (!r.ok) return null;
        const buf = new Uint8Array(await r.arrayBuffer()); if (buf.length > 8e6) return null;
        let s = ''; for (let i = 0; i < buf.length; i += 8192) s += String.fromCharCode.apply(null, buf.subarray(i, i + 8192));
        return { b64: btoa(s), type: r.headers.get('content-type') || '' };
      } catch (e) { return null; }
    }, args: [url]
  });
  if (!result || !result.b64) return null;
  const bin = atob(result.b64), b = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
  return { bytes: b, mime: (result.type || '').split(';')[0].trim() };
}
function coverCandidates(o) {            // the typed / found URL first, then every other picture the page offers as its cover
  const base = (tabInfo && tabInfo.url) || o.url || '', seen = new Set(), out = [];
  const add = u => { try { if (!u || /^data:/i.test(u)) return; const h = new URL(String(u).trim(), base).href; if (/^https?:/i.test(h) && !seen.has(h)) { seen.add(h); out.push(h); } } catch { } };
  add(o.coverUrl);
  try { if (tabInfo) add(tabInfo.image); } catch { }
  try {
    const d = tabDoc;
    if (d) {
      const q = (sel, fn) => { try { d.querySelectorAll(sel).forEach(fn); } catch { } };
      q('meta[property="og:image"],meta[property="og:image:url"],meta[property="og:image:secure_url"],meta[name="twitter:image"],meta[name="twitter:image:src"],meta[itemprop="image"]', e => add(e.getAttribute('content')));
      q('link[rel="image_src"]', e => add(e.getAttribute('href')));
      q('script[type="application/ld+json"]', e => {
        try {
          const walk = x => { if (!x || typeof x !== 'object') return; if (Array.isArray(x)) return x.forEach(walk); if (x.image) (Array.isArray(x.image) ? x.image : [x.image]).forEach(v => add(typeof v === 'string' ? v : v && (v.url || v.contentUrl))); Object.values(x).forEach(v => { if (v && typeof v === 'object') walk(v); }); };
          walk(JSON.parse(e.textContent));
        } catch { }
      });
      q('img[class*="cover" i],img[id*="cover" i],img[alt*="cover" i],img[class*="book" i],img[class*="poster" i],img[class*="thumb" i],img[itemprop="image"],[itemprop="image"] img',
        e => add(e.getAttribute('data-src') || e.getAttribute('data-original') || e.getAttribute('data-lazy-src') || e.currentSrc || e.getAttribute('src')));
    }
  } catch { }
  return out.slice(0, 5);
}
async function resolveCover(o) {         // -> { mime, bytes, w, h, how } or null.  Never throws.
  const say = (t, c) => { try { log('Cover: ' + t, c || ''); } catch { } };
  try {
    // 1) a picture chosen by hand
    try {
      const f = $('coverFile') && $('coverFile').files && $('coverFile').files[0];
      if (f) {
        const c = await coverNorm(new Uint8Array(await f.arrayBuffer()), f.type);
        if (c) { c.how = 'the picture you chose'; say('using ' + c.how + '.', 'ok'); return c; }
        say('the chosen file is not a usable picture, trying the page cover.', 'warn');
      }
    } catch (e) { say('the chosen file failed (' + _cvMsg(e) + '), trying the page cover.', 'warn'); }
    // 2) the page's own cover: normal fetch -> without cookies / Referer -> fetched from inside the page
    let weak = null;
    const steps = [
      ['site', async u => { const r = await _cvTime(getImage(u, true), 20000); return r ? coverNorm(r.bytes, r.mime) : null; }],
      ['site, no cookies', async u => { const r = await coverAnon(u); return r ? coverNorm(r.bytes, r.mime) : null; }],
      ['site, through the page', async u => { const r = await _cvTime(coverViaPage(u), 20000); return r ? coverNorm(r.bytes, r.mime) : null; }]
    ];
    for (const u of coverCandidates(o)) {
      let got = false;
      for (const [name, fn] of steps) {
        let c = null; try { c = await fn(u); } catch { c = null; }
        if (!c) continue;
        got = true; c.how = name;
        if (Math.min(c.w, c.h) >= COVER_MIN) { say(`found (${name}) ${c.w}x${c.h}.`, 'ok'); return c; }
        if (!weak || c.w * c.h > weak.w * weak.h) weak = c;
        break;
      }
      if (!got) say('could not load ' + (() => { try { return new URL(u).host + new URL(u).pathname.slice(-24); } catch { return u.slice(0, 40); } })() + ', next one.', 'warn');
    }
    if (weak) { weak.how = 'small picture from the page'; say(`only a small picture found (${weak.w}x${weak.h}), using it.`, 'warn'); return weak; }
    // 3) generated cover  4) no cover
    say('no usable cover on the page, generated cover used.', 'warn');
    try {
      const g = await makeCover(o.title, o.author);
      let n = null; try { n = await coverNorm(g.bytes, g.mime); } catch { }
      n = n || { mime: g.mime, bytes: g.bytes, w: 1200, h: 1800 }; n.how = 'generated cover'; return n;
    } catch (e) { say('could not make a cover (' + _cvMsg(e) + '), the book will have no cover.', 'warn'); return null; }
  } catch (e) { say('cover step failed (' + _cvMsg(e) + ').', 'warn'); return null; }
}
{ // "Test cover" button: shows what the build would use, without building
  const bc = $('btnCover'); let prev = '';
  if (bc) bc.onclick = async () => {
    const msg = $('coverMsg'), img = $('coverPrev');
    try {
      if (msg) msg.textContent = 'Checking…';
      const c = await resolveCover(readOpts());
      if (!c) { if (msg) msg.textContent = 'No cover could be made.'; if (img) img.hidden = true; return; }
      if (prev) URL.revokeObjectURL(prev);
      prev = URL.createObjectURL(new Blob([c.bytes], { type: c.mime }));
      if (img) { img.src = prev; img.hidden = false; }
      if (msg) msg.textContent = `${c.how} · ${c.w}x${c.h}`;
    } catch (e) { if (msg) msg.textContent = 'Test failed: ' + _cvMsg(e); }
  };
}

// ---------- options ----------
function smartUI() {
  const on = $('smartOn').checked; $('smartOpts').hidden = !on; $('smartCustom').hidden = !on || $('smartLang').value !== 'custom';
  if (on) smartTest();
}
function smartTest() {
  const out = $('smartOut'), lines = $('smartTest').value.split('\n').map(x => x.trim()).filter(Boolean).slice(0, 12);
  const m = H.makeMatcher({ lang: $('smartLang').value, pre: $('smartPre').value, suf: $('smartSuf').value });
  if (!m) { out.textContent = 'Custom mode: type a prefix (e.g. Chapter) and/or a suffix (e.g. 章).'; return; }
  out.textContent = lines.length ? lines.map(l => { const r = m(l); return r ? `✓ ${l}   →   title: “${r.title || r.full}”` : `✗ ${l}`; }).join('\n') : 'Optional: paste a few lines here to test.';
}
for (const id of ['smartOn', 'smartLang']) $(id).addEventListener('change', smartUI);
for (const id of ['smartPre', 'smartSuf', 'smartTest']) $(id).addEventListener('input', smartTest);
function readOpts() {
  const o = {
    url: $('srcUrl').value.trim(), mode: $('mode').value, maxCh: Math.max(1, +$('maxCh').value || 50), delay: Math.max(0, +$('delay').value || 0),
    title: $('title').value.trim() || 'Untitled', author: $('author').value.trim(), language: $('lang').value.trim() || 'en',
    coverUrl: $('coverUrl').value.trim(), series: $('series').value.trim(), description: $('desc').value.trim(), titleMode: $('titleMode').value,
    contentSel: $('contentSel').value.trim(), nextSel: $('nextSel').value.trim(), removeSel: $('removeSel').value.trim(),
    images: $('optImages').checked, convert: $('optConvert').checked, optTab: $('optTab').checked,
    repl: $('replRules').value.split('\n').map(l => l.split('=>')).filter(p => p.length >= 2 && p[0].trim()).map(p => [p[0].trim(), p.slice(1).join('=>').trim()]),
    outFmt: $('outFmt').value, inTab: $('inTab').checked, useCache: $('useCache').checked, imgCompress: $('imgCompress').checked, imgMax: Math.max(200, +$('imgMax').value || 1080), imgType: $('imgType').value, stripNum: $('stripNum').checked, fileName: $('fileName').value.trim(),
    apiFast: $('apiFast').checked, apiTpl: $('apiTpl').value.trim(),
    fetchMode: $('fetchMode').value, renderWait: Math.max(0, +$('renderWait').value || 0), pageRe: $('pageRe').value.trim(),
    apiUrl: $('apiUrl').value.trim(), apiBody: $('apiBody').value, apiFrom: $('apiFrom').value, apiTo: $('apiTo').value, apiPath: $('apiPath').value.trim(), apiTitle: $('apiTitle').value.trim(),
    conc: Math.min(4, Math.max(1, +$('conc').value || 1)), perVol: Math.max(0, +$('perVol').value || 0), autoOrder: $('autoOrder').checked,
    smart: $('smartOn').checked ? { lang: $('smartLang').value, pre: $('smartPre').value, suf: $('smartSuf').value, split: $('smartSplit').checked } : null,
    css: { fontSize: +$('fontSize').value, lineHeight: +$('lineHeight').value, align: $('align').value, indent: $('indent').checked }
  };
  return o;
}
async function saveOpts(o) {
  const s = {}; for (const k of FIELDS) { const e = $(k); s[k] = e.type === 'checkbox' ? e.checked : e.value; }
  await chrome.storage.local.set({ ['s:' + host(o.url)]: s });
}

// ---------- catalog discovery ----------
let sources = [];
const clean1 = s => (s || '').replace(/\s+/g, ' ').trim();
function addSource(name, items, force) {
  if (!items) return;
  const seen = new Set(), list = [];
  for (const x of items) { const h = norm(x.href); if (!h || seen.has(h)) continue; seen.add(h); list.push({ href: h, text: clean1(x.text) || h.split('/').filter(Boolean).pop(), locked: !!x.locked }); }
  if (list.length >= 3 || (force && list.length)) sources.push({ name, items: list });
}
function refreshSel() {
  const sel = $('catSrc'); sel.textContent = '';
  sources.forEach((s, i) => sel.add(new Option(`${s.name} — ${s.items.length}`, i)));
  $('catSrcRow').hidden = sources.length < 1;
}
function useSource(i) {
  const s = sources[i]; if (!s) return;
  let items = s.items.map(x => ({ ...x }));
  if ($('autoOrder').checked) { const r = C.orderList(items); items = r.list; if (r.mode !== 'kept') log(`  ordered by chapter number (${r.mode})`); }
  toc = items.map(x => ({ href: x.href, text: x.text, checked: true, grp: true, locked: !!x.locked }));
  lastIdx = -1; populateRange(); renderToc();
  $('mode').value = 'toc'; onMode();
  const g = C.gaps(toc);
  $('findInfo').textContent = `${toc.length} chapters from “${s.name}”` + (g.length ? ` · ⚠ ${g.length} number(s) missing, e.g. ${g.slice(0, 8).join(', ')}${g.length > 8 ? '…' : ''}` : ' · no gaps found') + (toc.some(t => t.locked) ? ` · 🔒 ${toc.filter(t => t.locked).length} locked` : '');
}
// ---------- merge chapter links from several open tabs (e.g. tab 1 = chapters 1-50, tab 2 = 51-100) ----------
let tabRows = [];
async function scanTabs() {
  const box = $('tabList'); $('tabPanel').hidden = false; box.textContent = 'Scanning open tabs…'; tabRows = [];
  const tabs = (await chrome.tabs.query({})).filter(t => /^https?:/.test(t.url || '') && t.id !== selfTabId).sort((a, b) => a.windowId - b.windowId || a.index - b.index);
  for (const t of tabs) {
    let grp = [], url = t.url;
    try {
      const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: t.id }, func: () => ({ url: location.href, links: [...document.querySelectorAll('a[href]')].map(a => ({ href: a.href, text: ((a) => { const c = s => (s || '').replace(/\s+/g, ' ').trim(); let t = c(a.textContent); for (const k of ['title', 'aria-label', 'data-title']) { const x = c(a.getAttribute(k)); if (x && x.length > t.length && x.length <= 140 && (!t || x.includes(t))) t = x; } if (t.length <= 16) { const row = a.closest('li,tr,dd,dt,.chapter,.chapter-item,.item'); if (row && row.querySelectorAll('a[href]').length === 1) { let rt = c(row.textContent); for (let i = 0; i < 3; i++) rt = rt.replace(/\s*(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\bVIP\b|\bNEW\b|\bHOT\b|最新|更新)\s*$/i, '').trim(); if (rt.length > t.length && rt.length <= 140 && rt.startsWith(t)) t = rt; } } return t; })(a).slice(0, 120) })).filter(l => /^https?:/.test(l.href) && l.text) }) });
      const seen = new Set(), list = []; url = result.url;
      for (const l of result.links) { const h = l.href.split('#')[0]; if (!seen.has(h)) { seen.add(h); list.push({ href: h, text: l.text }); } }
      grp = C.bestGroup(list);
    } catch { }
    tabRows.push({ t, grp, url });
  }
  const cnt = new Map(); tabRows.filter(r => r.grp.length >= 3).forEach(r => { const o = new URL(r.url).origin; cnt.set(o, (cnt.get(o) || 0) + 1); });
  const top = [...cnt].sort((a, b) => b[1] - a[1])[0]; box.textContent = '';
  tabRows.forEach((r, i) => {
    const l = document.createElement('label'), c = document.createElement('input'), s = document.createElement('span');
    c.type = 'checkbox'; c.checked = !!top && r.grp.length >= 3 && new URL(r.url).origin === top[0]; r.cb = c;
    s.textContent = `${(r.t.title || r.url).slice(0, 48)} — ${new URL(r.url).hostname} — ${r.grp.length} chapter links`; s.title = r.url;
    l.append(c, s); box.append(l);
  });
  if (!tabRows.length) box.textContent = 'No other http(s) tabs are open. Open the catalog pages (e.g. chapters 1-50, 51-100) in separate tabs first.';
}
$('btnTabs').onclick = () => scanTabs().catch(e => { $('tabList').textContent = 'Failed: ' + e.message; });
$('btnTabsClose').onclick = () => { $('tabPanel').hidden = true; };
$('btnTabsGo').onclick = () => {
  const picked = tabRows.filter(r => r.cb && r.cb.checked && r.grp.length);
  if (!picked.length) return log('Tick at least one tab that has chapter links.', 'warn');
  const items = picked.flatMap(r => r.grp);
  addSource(`Open tabs (${picked.length})`, items, true); refreshSel();
  const idx = sources.length - 1; $('catSrc').value = idx; useSource(idx);
  $('tabPanel').hidden = true; backToStudio();
  log(`Merged ${picked.length} tab(s): ${items.length} links → ${toc.length} unique chapters. Pick the range, choose the output format, then Build.`, 'ok');
};
$('catSrc').onchange = () => useSource(+$('catSrc').value);

async function loadForCatalog(url, o) {
  const isTab = tab && tabInfo && norm(url) === norm(tabInfo.url);
  const enough = d => C.bestGroup(C.docLinks(d, url)).length >= 8;
  if (C.webnovelId(url)) return (isTab && tabDoc) ? tabDoc : await fetchDoc(url);
  if (isTab && o.optTab) {
    if (enough(tabDoc)) return tabDoc;
    try {
      log('Expanding the open tab (scroll + “load more” clicks)…');
      const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: snap, args: [{ scroll: true, expand: true, settle: o.renderWait }] });
      return new DOMParser().parseFromString(result.html, 'text/html');
    } catch (e) { log('Tab expand failed: ' + e.message, 'warn'); return tabDoc; }
  }
  let d = null;
  if (o.fetchMode !== 'rendered') { try { d = await fetchDoc(url); } catch (e) { log('Fast fetch failed: ' + e.message, 'warn'); } }
  if (!d || (mayRender(o) && !enough(d))) {
    log('Loading the page in a real browser tab (JS / load-more)…');
    try { d = await renderDoc(url, { ...o, expand: true }); } catch (e) { if (!d) throw e; }
  }
  return d;
}
async function paginateCatalog(doc, url, o, first) {
  const have = new Set(first.map(x => x.href)), shapes = new Set(first.map(x => C.shape(x.href))), out = [], pages = new Set([norm(url)]);
  let d = doc, u = url;
  for (let i = 0; i < 80 && !cancel; i++) {
    const nx = C.findPager(d, u); if (!nx || pages.has(norm(nx))) break; pages.add(norm(nx));
    try { d = o.fetchMode === 'rendered' ? await renderDoc(nx, { ...o, expand: false }) : await fetchDoc(nx); } catch (e) { log('✗ catalog page ' + nx + ': ' + e.message, 'warn'); break; }
    u = nx;
    const g = C.bestGroup(C.docLinks(d, u)).filter(x => !have.has(x.href) && shapes.has(C.shape(x.href)));
    if (!g.length) break;
    g.forEach(x => { have.add(x.href); out.push(x); });
    log(`  catalog page ${i + 2}: +${g.length}`); $('findInfo').textContent = `Searching… ${first.length + out.length} so far`;
    await sleep(Math.min(o.delay, 600));
  }
  return out;
}
async function pageFetch(url) {
  const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, args: [url], func: async u => {
    const c = (document.cookie.match(/(?:^|; )_csrfToken=([^;]*)/) || [])[1] || '';
    try { const r = await fetch(u.replace('{csrf}', c), { credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } }); return { ok: r.ok, status: r.status, text: await r.text() }; }
    catch (e) { return { ok: false, text: '', err: String(e) }; } } });
  return result;
}
async function fetchText(u) {
  try { if (tab && tabInfo && new URL(u).origin === new URL(tabInfo.url).origin) { const r = await pageFetch(u); if (r && r.ok) return r.text; } } catch { }
  const r = await fetch(u, { credentials: 'include' }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.text();
}
async function webnovelSource(url, o) {
  const id = C.webnovelId(url); if (!id) return 0;
  const u0 = new URL(url), origin = /(^|\.)webnovel\.com$/.test(u0.hostname) ? 'https://www.webnovel.com' : u0.origin, cat = `${origin}/book/${id}/catalog`; let items = [];
  if (origin !== u0.origin) log('  mobile site (m.webnovel.com) → using www.webnovel.com for the catalog and chapters');
  log(`Webnovel.com detected (book ${id}) — reading its catalog…`);
  try { items = C.webnovelFromDoc(new DOMParser().parseFromString(await fetchText(cat), 'text/html'), cat, id); } catch (e) { log('  catalog fetch: ' + e.message, 'warn'); }
  if (items.length < 3 && mayRender(o)) {
    log('  loading the catalog page in a real browser tab…');
    try { items = C.webnovelFromDoc(await renderDoc(cat, { ...o, expand: true }), cat, id); } catch (e) { log('  ' + e.message, 'warn'); }
  }
  if (items.length < 3) {
    try { const r = await pageFetch(`${origin}/go/pcm/chapter/get-chapter-list?_csrfToken={csrf}&bookId=${id}`); if (r && r.ok) items = C.webnovelFromJson(JSON.parse(r.text), origin, id); } catch { }
  }
  addSource('Webnovel catalog', items); return items.length;
}
async function probe(doc, url, o) {
  if (await webnovelSource(url, o) >= 3) return;
  const g = C.bestGroup(C.docLinks(doc, url));
  addSource('Page links', g);
  addSource('Dropdown (select)', C.selectLinks(doc, url));
  addSource('Embedded page data', C.embedded(doc, url));
  if (g.length >= 3) { const more = await paginateCatalog(doc, url, o, g); if (more.length) addSource('Page links (all catalog pages)', [...g, ...more]); }
  const jobs = [C.blogger(doc, url), C.wordpress(doc, url), C.madara(doc, url), C.novelAjax(doc, url)];
  for (const r of await Promise.allSettled(jobs)) if (r.status === 'fulfilled') for (const s of r.value) addSource(s.name, s.items);
  if (tab && tabInfo && norm(url) === norm(tabInfo.url)) {
    try {
      const [{ result: urls }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => [...new Set(performance.getEntriesByType('resource').filter(e => ['fetch', 'xmlhttprequest'].includes(e.initiatorType)).map(e => e.name))].slice(-60) });
      for (const s of await C.apiScan(urls, url)) addSource(s.name, s.items);
    } catch { }
  }
}
const best = () => Math.max(0, ...sources.map(s => s.items.length));
async function discover() {
  const o = readOpts(); if (!/^https?:/.test(o.url)) return log('Enter a valid http(s) URL first.', 'err');
  $('btnFind').disabled = true; sources = []; cancel = false; $('log').textContent = ''; $('findInfo').textContent = 'Searching…';
  try {
    let url = o.url, doc = await loadForCatalog(url, o), depth = 0; const tried = new Set([norm(url)]);
    for (;;) {
      await probe(doc, url, o);
      if (best() >= 8 || depth >= 2) break;
      const cand = C.findCatalogLinks(doc, url).find(c => !tried.has(norm(c.url)));
      if (!cand) break;
      log(`→ Looks like a chapter page. Catalog candidate: ${cand.url}  (${cand.why})`);
      tried.add(norm(cand.url)); url = cand.url; depth++;
      try { doc = await loadForCatalog(url, o); } catch (e) { log('✗ ' + e.message, 'err'); break; }
    }
    if (best() < 8) { log('Trying sitemap…'); try { addSource('Sitemap (guess — check the list)', await C.sitemap(url)); } catch { } }
    if (!sources.length) {
      const p = C.patternFromUrl(o.url);
      if (p) { $('apiUrl').value = p.template; $('apiFrom').value = p.n; $('apiTo').value = p.n + 99; log(`Pattern guess filled in API mode: ${p.template} (From ${p.n}). Switch Mode → “API / URL pattern” if the chapter numbers are sequential.`, 'warn'); }
      throw new Error('No chapter list found. Try: Fetch method = Rendered browser, paste URLs below, or use “Follow Next” mode.');
    }
    sources.sort((a, b) => (/Sitemap/.test(a.name) - /Sitemap/.test(b.name)) || b.items.length - a.items.length);
    for (const s of sources) log(`• ${s.name}: ${s.items.length}`);
    refreshSel(); useSource(0);
    if (depth > 0) {   // fill book info from the catalog page
      $('srcUrl').value = url;
      const mt = n => (doc.querySelector(n) || {}).content || '', h1 = doc.querySelector('h1');
      const t = clean1(h1 ? h1.textContent : (mt('meta[property="og:title"]') || doc.title).split(/\s[|–—-]\s/)[0]); if (t) $('title').value = t;
      try { const im = mt('meta[property="og:image"]'); if (im) $('coverUrl').value = new URL(im, url).href; } catch { }
      const d = mt('meta[property="og:description"]') || mt('meta[name="description"]'); if (d) $('desc').value = d.slice(0, 500);
      const au = mt('meta[name="author"]') || mt('meta[property="article:author"]'); if (au) $('author').value = au;
    }
    log(`Catalog ready: ${toc.length} chapters selected. Check the list, then press Build EPUB.`, 'ok');
  } catch (e) { log('Error: ' + e.message, 'err'); $('findInfo').textContent = 'Nothing found'; }
  if (rtab) { chrome.tabs.remove(rtab.id).catch(() => { }); rtab = null; }
  $('btnFind').disabled = false;
}
$('btnFind').onclick = discover;
$('btnNoLock').onclick = () => { toc.forEach(t => { if (t.locked) t.checked = false; }); renderToc(); };
$('btnExport').onclick = () => {
  const t = toc.filter(x => x.checked).map((x, i) => `${i + 1}\t${x.text}\t${x.href}`).join('\n'); if (!t) return;
  saveBlob(new Blob([t], { type: 'text/plain' }), 'chapter-list.txt');
};
$('btnPaste').onclick = () => {
  const items = [];
  for (const l of $('tocPaste').value.split('\n').map(x => x.trim()).filter(Boolean)) {
    const m = /(https?:\/\/\S+)/.exec(l); if (!m) continue;
    const text = l.replace(m[1], '').replace(/^[\s|,;\t-]+|[\s|,;\t-]+$/g, '');
    items.push({ href: m[1], text: text || m[1].split('/').filter(Boolean).pop() });
  }
  if (!items.length) return log('No URLs found in the pasted text.', 'warn');
  sources = []; addSource('Pasted URLs', items, true); refreshSel(); useSource(0); log(`${items.length} pasted URLs loaded.`, 'ok');
};

// ---------- build ----------
function setBusy(b) { $('btnBuild').disabled = b; $('btnCancel').disabled = !b; }
$('btnCancel').onclick = () => { cancel = true; log('Cancelling… (will build what is collected so far)', 'warn'); };
const sameOrigin = u => { try { return !!(tab && tabInfo && new URL(u).origin === new URL(tabInfo.url).origin); } catch { return false; } };
async function tabFetchDoc(u) {   // fetch from inside the source tab: same cookies / headers as a normal visit
  const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, args: [u], func: async u => {
    const r = await fetch(u, { credentials: 'include' }); if (!r.ok) return { status: r.status };
    const buf = await r.arrayBuffer(); let cs = (/charset=([\w-]+)/i.exec(r.headers.get('content-type') || '') || [])[1];
    if (!cs) cs = (/<meta[^>]+charset=["']?([\w-]+)/i.exec(new TextDecoder('latin1').decode(buf.slice(0, 2048))) || [])[1];
    let t; try { t = new TextDecoder(cs || 'utf-8').decode(buf); } catch { t = new TextDecoder('utf-8').decode(buf); }
    return { html: t }; } });
  if (!result || !result.html) { const e = new Error('HTTP ' + (result && result.status)); e.status = result && result.status; throw e; }
  return new DOMParser().parseFromString(result.html, 'text/html');
}
async function loadDoc(u, o) {
  if (o.optTab && tabDoc && tabInfo && norm(u) === norm(tabInfo.url)) return tabDoc;
  if (o.fetchMode === 'rendered') return renderDoc(u, o);
  const d = (o.inTab && sameOrigin(u)) ? await tabFetchDoc(u).catch(() => fetchDoc(u)) : await fetchDoc(u);
  if (o.fetchMode === 'auto' && weak(d, o)) {
    if (!mayRender(o)) { log('  ⚠ little text via fast fetch, but this browser cannot hide tabs, so auto-render is skipped. Use “Pick chapter text”, or choose Fetch method = Rendered.', 'warn'); return d; }
    log('  ↻ almost no text via fast fetch — reloading in a real browser tab', 'warn'); return renderDoc(u, o);
  }
  return d;
}
// ---- tap the chapter text on the real page -> CSS selector ----
function pickerFn() {
  return new Promise(resolve => {
    const bar = document.createElement('div'); bar.textContent = '👆 Tap / click the chapter TEXT (a paragraph). Esc = cancel';
    bar.style.cssText = 'position:fixed;z-index:2147483647;left:0;right:0;top:0;padding:10px;background:#4f46e5;color:#fff;font:14px sans-serif;text-align:center';
    document.documentElement.append(bar);
    let last = null; const hl = e => { if (last) last.style.outline = ''; last = e.target; if (last !== bar) last.style.outline = '3px solid #f59e0b'; };
    const ok = c => c && !/\d{3,}|^css-|^jsx-|^sc-|^js-|^_/.test(c);
    const uniq = s => { try { return document.querySelectorAll(s).length === 1; } catch { return false; } };
    const cssPath = el => {
      const parts = []; let e = el;
      for (let d = 0; e && e !== document.body && d < 4; d++, e = e.parentElement) {
        let s = e.tagName.toLowerCase(); const cls = [...e.classList].filter(ok).slice(0, 2);
        if (e.id && !/\d{4,}/.test(e.id)) s = '#' + CSS.escape(e.id); else if (cls.length) s += cls.map(c => '.' + CSS.escape(c)).join('');
        parts.unshift(s); const sel = parts.join(' > '); if (uniq(sel)) return sel;
      }
      return parts.join(' > ');
    };
    const done = v => { document.removeEventListener('click', h, true); document.removeEventListener('mouseover', hl, true); document.removeEventListener('touchstart', hl, true); document.removeEventListener('keydown', k, true); if (last) last.style.outline = ''; bar.remove(); resolve(v); };
    const h = ev => {
      ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation();
      let t = ev.target; while (t && /^(P|SPAN|A|EM|B|I|STRONG|BR|FONT|U|SMALL|H[1-6]|LI)$/.test(t.tagName) && t.parentElement && t.parentElement !== document.body) t = t.parentElement;
      if (last) last.style.outline = '';
      done({ selector: cssPath(t), len: (t.innerText || '').length, preview: (t.innerText || '').trim().slice(0, 140) });
    };
    const k = e => { if (e.key === 'Escape') done(null); };
    document.addEventListener('click', h, true); document.addEventListener('mouseover', hl, true); document.addEventListener('touchstart', hl, { capture: true, passive: true }); document.addEventListener('keydown', k, true);
  });
}
$('btnPick').onclick = async () => {
  if (!tab) return log('Open Studio from the chapter page tab first (the page you want to pick from).', 'warn');
  log('Switching to the page — tap the chapter text there…', 'ok');
  try {
    await chrome.tabs.update(tab.id, { active: true });
    const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: pickerFn });
    backToStudio();
    if (!result) return log('Pick cancelled.', 'warn');
    $('contentSel').value = result.selector;
    log(`🎯 Content selector set: ${result.selector}  (${result.len} chars) — “${result.preview}…”`, 'ok');
    log('Press “Test 1 chapter” to check it on a real chapter. If only part of the text was captured, pick again and tap the wider block.', 'ok');
  } catch (e) { backToStudio(); log('Pick failed: ' + e.message, 'err'); }
};
$('btnTest').onclick = async () => {
  const o = readOpts(); let url = o.url;
  if (o.mode === 'toc') { const f = toc.find(t => t.checked); if (f) url = f.href; }
  log(`🧪 Testing: ${url}`);
  try {
    const doc = await loadDoc(url, o);
    const r = extractPage(doc, url, { contentSelector: o.contentSel, removeSel: o.removeSel, repl: o.repl, images: false }, { map: new Map(), list: [] });
    const plain = r.body.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    log(`  found via: ${r.via} → ${r.rootPath}`, 'ok'); log(`  ${r.textLen} chars · title: ${r.title}`, 'ok');
    log('  starts: ' + plain.slice(0, 250)); log('  ends:   ' + plain.slice(-150));
  } catch (e) { log('✗ ' + e.message, 'err'); }
  closeRenderTab();
};
async function exportOther(o, chapters) {
  const size = o.perVol > 0 ? o.perVol : chapters.length, parts = Math.ceil(chapters.length / size), label = { txt: 'TXT', docx: 'DOCX', zip: 'ZIP (one .txt per chapter)' }[o.outFmt];
  log(`Packing ${label}…`);
  let cv = null;
  if (o.outFmt === 'docx' || o.outFmt === 'zip') { try { cv = await resolveCover(o); } catch { cv = null; } }   // 0.0.484: cover page in DOCX, cover.jpg in ZIP (never stops the export)
  for (let p = 0; p < parts; p++) {
    const chs = chapters.slice(p * size, (p + 1) * size);
    const name = parts > 1 ? `${o.title} - Vol ${String(p + 1).padStart(2, '0')} (Ch ${p * size + 1}-${p * size + chs.length})` : o.title;
    let blob, ext;
    if (o.outFmt === 'txt') { blob = buildTxt({ title: name, author: o.author, chapters: chs }); ext = '.txt'; }
    else if (o.outFmt === 'docx') { blob = await buildDocx({ title: name, author: o.author, chapters: chs, cover: cv }); ext = '.docx'; }
    else { blob = await buildTxtZip({ chapters: chs, cover: cv }); ext = '.zip'; }
    await saveBlob(blob, fileNameFor(o, name, p, parts, size, chs.length).replace(/[\\/:*?"<>|]+/g, '_').slice(0, 120) + ext);
    log(`Done${parts > 1 ? ` (part ${p + 1}/${parts})` : ''}: ${chs.length} chapters, ${(blob.size / 1048576).toFixed(2)} MB (${ext})`, 'ok');
    if (parts > 1) await sleep(700);
  }
}
function closeRenderTab() { if (rtab) { chrome.tabs.remove(rtab.id).catch(() => { }); rtab = null; } }
$('btnBuild').onclick = build;
$('btnClearCache').onclick = async () => { try { await cacheClear(); log('Chapter cache cleared.', 'ok'); } catch (e) { log('Could not clear cache: ' + e.message, 'err'); } };
$('speed').onchange = () => { const p = { safe: [1, 800], fast: [3, 250], turbo: [4, 80] }[$('speed').value]; if (p) { $('conc').value = p[0]; $('delay').value = p[1]; } };
const LOCKED = /unlock (this )?chapter|chapter (is )?locked|locked chapter|advanced chapter|privilege|to unlock|fast pass|subscribe to (read|unlock)|log\s*in to (read|unlock)|members only|paywall|vip only/i;
const idsOf = h => h.match(/\d{6,}/g) || [];
const chapId = h => idsOf(h).pop() || '', bookId = h => { const m = idsOf(h); return m.length > 1 ? m[0] : (C.webnovelId(h) || ''); };
$('btnLearn').onclick = async () => {
  const box = $('learnList'); if (!tab || !tabInfo) return box.textContent = 'Open Studio from a chapter-reading tab first.';
  box.textContent = 'Scanning the network calls of the open chapter tab…';
  try {
    const [{ result: urls }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => [...new Set(performance.getEntriesByType('resource').filter(e => ['fetch', 'xmlhttprequest'].includes(e.initiatorType)).map(e => e.name))].slice(-60) });
    box.textContent = ''; let found = 0;
    const nums = [...new Set(tabInfo.url.match(/\d{8,}/g) || [])], chap = nums[nums.length - 1], book = nums.length > 1 ? nums[0] : '';
    for (const u of urls) {
      try {
        const r = await pageFetch(u); if (!r || !r.ok) continue;
        const text = bestText(JSON.parse(r.text)); if (text.length < 300) continue; found++;
        let t = u.replace(/(_csrfToken=)[^&]+/, '$1{csrf}').replace(/[&?]_=\d+/, '');
        if (chap) t = t.split(chap).join('{id}'); if (book) t = t.split(book).join('{bid}');
        const b = document.createElement('button'); b.textContent = `${t.slice(0, 100)}  (${text.length} chars)`; b.title = u;
        b.onclick = () => { $('apiTpl').value = t; $('apiFast').checked = true; log('Fast API template set. Press Build — it falls back to normal loading if the API gives no text.', 'ok'); };
        box.append(b);
      } catch { }
    }
    if (!found) box.textContent = 'No JSON call carrying chapter text was found. Open a chapter in the reader, wait until the text shows, then try again.';
  } catch (e) { box.textContent = 'Failed: ' + e.message; }
};
function fileNameFor(o, name, p, parts, size, n) {
  if (!o.fileName) return name;
  const vol = parts > 1 ? `Vol ${String(p + 1).padStart(2, '0')}` : '', range = parts > 1 ? `Ch ${p * size + 1}-${p * size + n}` : '';
  return o.fileName.replace(/%Title%/g, o.title).replace(/%Author%/g, o.author || '').replace(/%Vol%/g, vol).replace(/%Range%/g, range).replace(/%Date%/g, new Date().toISOString().slice(0, 10)).replace(/\s+/g, ' ').trim() || name;
}
const setTitle = (c, t) => {
  if (STRIP) { let s = ''; if (SMART) { const m = SMART(t); if (m && m.title) s = m.title; } else s = t.replace(/^\s*(?:第\s*[\d〇零一二三四五六七八九十百千两]+\s*[章节回话卷集篇]|chapter\s*\d+|ch\.?\s*\d+)\s*[:：\-–—.、]?\s*/i, '').trim(); if (s) t = s; }
  c.title = t; c.body = /^\s*<h[1-6]>/.test(c.body) ? c.body.replace(/^\s*<h[1-6]>.*?<\/h[1-6]>/s, `<h2>${esc(t)}</h2>`) : `<h2>${esc(t)}</h2>\n` + c.body; };

// Final chapter title.  smart/toc: catalog text when it is a real title, otherwise the fullest title the chapter page offers
// ("第242章" + page "第242章 堂姐，我好像要突破了" -> the full one).  auto: best title the page offers.  number: "Chapter N".
function applyFinalTitle(c, i, t, cands) {
  const o = readOpts(); let want;
  if (o.titleMode === 'number') want = `Chapter ${i + 1}`;
  else if (!t) return;                                   // single page: keep the page's own title
  else {
    const toc = o.titleMode === 'auto' ? '' : (t && t.text) || '';
    want = H.pickTitle(toc, (cands && cands.length) ? cands : [c.title], o.title) || toc || c.title;
  }
  if (want && (want !== c.title || STRIP || !/^\s*<h[1-6]>/.test(c.body))) setTitle(c, want);
}

async function build() {
  const o = readOpts(); if (!/^https?:/.test(o.url)) return log('Enter a valid http(s) URL.', 'err');
  await saveOpts(o); cancel = false; setBusy(true); $('log').textContent = '';
  IMGOPT.compress = o.imgCompress; IMGOPT.max = o.imgMax; IMGOPT.type = o.imgType; STRIP = o.stripNum;
  SMART = o.smart ? H.makeMatcher(o.smart) : null;
  if (o.smart && !SMART) log('Smart headings: Custom mode needs a prefix or a suffix — feature skipped.', 'warn');
  await fixCookies(o.url);
  if (o.mode === 'single' && tabInfo && norm(o.url) === norm(tabInfo.url) && toc.filter(t => t.checked).length >= 15)
    log('⚠ This page has many chapter links — it looks like an index page. Press “Find catalog” or switch Mode to “Chapter list” to get the whole book.', 'warn');
  const imgs = { map: new Map(), list: [], done: 0 }, store = new Map(), chapters = [];
  const eo = { contentSelector: o.contentSel, removeSel: o.removeSel, repl: o.repl, images: o.images, headingMatch: SMART || undefined };
  const getDoc = u => loadDoc(u, o);
  const handle = async (doc, u, merge = false) => {
    const r = extractPage(doc, u, merge ? { ...eo, noHeading: true } : eo, imgs);
    if (r.textLen < 80 && !r.hasImg) log(`⚠ very little text found at ${u} — try a content selector`, 'warn');
    await loadImages(imgs, store, o);
    if (merge && chapters.length) { chapters[chapters.length - 1].body += '\n' + r.body; log(`  + next page of the same chapter merged (${r.textLen} chars)`); return; }
    chapters.push({ title: r.title, body: r.body, url: u });
    applyFinalTitle(chapters[chapters.length - 1], chapters.length - 1, null, r.titles);
    log(`✓ ${chapters.length}. ${chapters[chapters.length - 1].title} (${r.textLen} chars)`, 'ok');
    $('bar').value = chapters.length;
  };
  try {
    if (o.mode === 'single') {
      $('bar').max = 1; await handle(await getDoc(o.url), o.url);
    } else if (o.mode === 'toc') {
      const list = toc.filter(t => t.checked); if (!list.length) throw new Error('Select at least one link.');
      $('bar').max = list.length; $('bar').value = 0;
      const res = new Array(list.length), bad = []; let done = 0, lockedN = 0, lockStreak = 0;
      const apiChapter = async t => {
        const u = o.apiTpl.replace(/\{id\}/g, chapId(t.href)).replace(/\{bid\}/g, bookId(t.href));
        let json; try { json = JSON.parse(await fetchText(u)); } catch { return null; }
        const text = bestText(json, o.apiPath || '');
        if (text.length < 80) return null;
        if (text.length < 700 && LOCKED.test(text)) { const e = new Error('locked / paid chapter'); e.locked = true; throw e; }
        const title = H.pickTitle(t.text, [autoTitle(json)], o.title) || t.text || autoTitle(json) || 'Chapter';
        const body = `<h2>${esc(title)}</h2>\n` + fromString(text, t.href, eo, imgs);
        await loadImages(imgs, store, o);
        return { title, body, url: t.href, len: text.length };
      };
      const sig = JSON.stringify(['v7', o.contentSel, o.removeSel, o.repl, o.images, o.convert, o.apiFast ? o.apiTpl : '', IMGOPT]);
      const cachedIdx = new Set(), seenFp = new Set(); let cachedN = 0;
      const applyTitle = (c, i, t) => applyFinalTitle(c, i, t, c.titles);
      const cacheChapter = async (raw, t) => {
        const body = raw.body.replace(/@@IMG(\d+)@@/g, (m, k) => { const u = imgs.list[+k], s = store.get(+k); if (s) cachePut('img', u, { mime: s.mime, bytes: s.bytes }); return `@@IMGU:${encodeURIComponent(u)}@@`; });
        await cachePut('ch', norm(t.href), { title: raw.title, titles: raw.titles, body, sig, at: Date.now() });
      };
      const restoreChapter = async rec => {
        const urls = [...new Set([...rec.body.matchAll(/@@IMGU:([^@]+)@@/g)].map(m => decodeURIComponent(m[1])))].filter(u => !imgs.map.has(u));
        const got = await Promise.all(urls.map(u => cacheGet('img', u)));
        urls.forEach((u, k) => { if (imgs.map.has(u)) return; const idx = imgs.list.length; imgs.list.push(u); imgs.map.set(u, idx); const r = got[k]; store.set(idx, r ? { name: `img${idx + 1}.${EXT[r.mime] || 'jpg'}`, mime: r.mime, bytes: r.bytes } : null); });
        return rec.body.replace(/@@IMGU:([^@]+)@@/g, (m, e) => `@@IMG${imgs.map.get(decodeURIComponent(e))}@@`);
      };
      const one = async (i, force) => {
        const t = list[i];
        if (o.useCache && !force) {
          const rec = await cacheGet('ch', norm(t.href));
          if (rec && rec.sig === sig) { const c = { title: rec.title, titles: rec.titles, body: await restoreChapter(rec), url: t.href }; applyTitle(c, i, t); res[i] = c; lockStreak = 0; cachedIdx.add(i); cachedN++; log(`♻ ${i + 1}/${list.length} ${c.title} (from cache)`, 'ok'); return; }
        }
        if (o.apiFast && o.apiTpl && !force) {
          const ac = await apiChapter(t);
          if (ac) { const raw = { title: ac.title, body: ac.body }; if (o.titleMode === 'number') setTitle(ac, `Chapter ${i + 1}`); else if (STRIP) setTitle(ac, ac.title); if (o.useCache) await cacheChapter(raw, t); res[i] = ac; lockStreak = 0; log(`✓ ${i + 1}/${list.length} ${ac.title} (API, ${ac.len} chars)`, 'ok'); return; }
          log(`  ↻ API gave no text for ${t.text || t.href} — using normal loading`, 'warn');
        }
        const doc = force ? await renderDoc(t.href, o) : await getDoc(t.href);
        const r = extractPage(doc, t.href, eo, imgs);
        if (r.textLen < 80 && !r.hasImg && !force) throw new Error('almost no text found');
        const fp = r.body.replace(/^\s*<h[1-6]>.*?<\/h[1-6]>/s, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 200);
        if (fp.length > 60 && seenFp.has(fp)) throw new Error(`same text as another chapter — the real chapter text was not found (matched: ${r.via} → ${r.rootPath}). Use “🎯 Pick chapter text” or set a Content selector, then “Clear chapter cache” and Build again`);
        seenFp.add(fp);
        await loadImages(imgs, store, o);
        const raw = { title: r.title, titles: r.titles, body: r.body }, c = { ...raw, url: t.href };
        applyFinalTitle(c, i, t, r.titles);
        if (r.textLen < 700 && LOCKED.test(r.body.replace(/<[^>]+>/g, ' '))) { const e = new Error('locked / paid chapter'); e.locked = true; throw e; }
        if (o.useCache) await cacheChapter(raw, t);
        res[i] = c; lockStreak = 0;
        log(`✓ ${i + 1}/${list.length} ${c.title} (${r.textLen} chars)`, 'ok');
      };
      await pool(list.map((_, i) => i), o.conc, async i => {
        try { await one(i, false); } catch (e) {
          if (e.locked) {
            lockedN++; log(`🔒 ${list[i].text || list[i].href}: locked / paid — skipped`, 'warn');
            if (++lockStreak >= 5 && o.stopLocked && !cancel) { cancel = true; log('5 locked chapters in a row → stopping early (the rest are probably locked too). Building what was collected.', 'warn'); }
          } else { bad.push(i); log(`✗ ${list[i].href}: ${e.message}`, 'err'); }
        }
        $('bar').value = ++done;
        if (!cachedIdx.has(i)) await sleep(o.delay * (0.7 + Math.random() * 0.6));
      });
      if (bad.length && !cancel && mayRender(o)) {
        log(`Retrying ${bad.length} failed chapter(s) in a real browser tab…`, 'warn'); bad.sort((a, b) => a - b); let fixed = 0;
        for (const i of bad) { if (cancel) break; try { await one(i, true); fixed++; } catch (e) { log(`✗ still failed: ${list[i].href}: ${e.message}`, 'err'); } await sleep(o.delay); }
        log(`Retry: ${fixed}/${bad.length} recovered.`, fixed ? 'ok' : 'warn');
      }
      chapters.push(...res.filter(Boolean));
      if (lockedN) log(`🔒 ${lockedN} locked chapter(s) skipped. They need coins/privilege on the site — unlock them in your own account (log in in this browser), then run again.`, 'warn');
      const miss = list.length - chapters.length; if (cachedN) log(`♻ ${cachedN} chapter(s) were reused from the cache.`, 'ok');
      if (miss) { log(`⚠ ${miss} chapter(s) are missing from the EPUB (see red lines above).`, 'warn'); if (o.useCache) log('Tip: press Build again — finished chapters are cached, only the missing ones are downloaded.', 'ok'); }
    } else if (o.mode === 'api') {
      if (!/\{n(:\d+)?\}/.test(o.apiUrl)) throw new Error('API URL must contain {n} (the chapter number).');
      const from = parseInt(o.apiFrom) || 1, to = Math.min(parseInt(o.apiTo) || from, from + 5000);
      $('bar').max = to - from + 1;
      for (let n = from; n <= to && !cancel; n++) {
        const fill = s => s.replace(/\{n(?::(\d+))?\}/g, (m, w) => w ? String(n).padStart(+w, '0') : String(n));
        const u = fill(o.apiUrl);
        try {
          const init = { credentials: 'include' };
          if (o.apiBody.trim()) { const b = fill(o.apiBody.trim()); init.method = 'POST'; init.body = b; init.headers = { 'Content-Type': b.startsWith('{') ? 'application/json' : 'application/x-www-form-urlencoded' }; }
          const r = await fetch(u, init); if (!r.ok) throw new Error('HTTP ' + r.status);
          const text = decode(await r.arrayBuffer(), r.headers.get('content-type'));
          let json = null; try { json = JSON.parse(text); } catch { }
          if (!json) { await handle(new DOMParser().parseFromString(text, 'text/html'), u); }
          else {
            const hit = o.apiPath ? { value: jsonPick(json, o.apiPath) } : longestString(json, 100);
            if (!hit || typeof hit.value !== 'string' || !hit.value) throw new Error('no text at that JSON path');
            const title = String((o.apiTitle ? jsonPick(json, o.apiTitle) : autoTitle(json)) || `Chapter ${n}`);
            const body = `<h2>${esc(title)}</h2>\n` + fromString(hit.value, u, eo, imgs);
            await loadImages(imgs, store, o);
            chapters.push({ title, body, url: u });
            log(`✓ ${chapters.length}. ${title} (${hit.value.length} chars)`, 'ok');
            $('bar').value = chapters.length;
          }
        } catch (e) { log(`✗ #${n} ${u}: ${e.message}`, 'err'); }
        await sleep(o.delay * (0.7 + Math.random() * 0.6));
      }
    } else {
      $('bar').max = o.maxCh; const visited = new Set(); let url = o.url, merge = false; const origin = new URL(url).origin;
      while (url && chapters.length < o.maxCh && !cancel) {
        visited.add(norm(url));
        let doc; try { doc = await getDoc(url); } catch (e) { log(`✗ ${url}: ${e.message}`, 'err'); break; }
        await handle(doc, url, merge);
        const info = findNextInfo(doc, url, o.nextSel), nx = info && info.url;
        merge = false; try { merge = !!(info && o.pageRe && new RegExp(o.pageRe, 'i').test(info.text)); } catch { }
        if (!nx) { log('No “next” link found — end of series.'); break; }
        if (visited.has(norm(nx))) { log('Next link loops back — stopping.'); break; }
        if (new URL(nx).origin !== origin) { log('Next link leaves the site — stopping.', 'warn'); break; }
        url = nx; await sleep(o.delay * (0.7 + Math.random() * 0.6));
      }
    }
    if (!chapters.length) throw new Error('Nothing was collected.');
    if (SMART && o.smart.split) {
      const before = chapters.length, out = H.splitChapters(chapters, SMART, { strip: o.stripNum });
      chapters.splice(0, chapters.length, ...out);
      log(out.length !== before ? `Smart headings: ${before} page(s) split into ${out.length} chapter(s).` : 'Smart headings: no repeated headings found, nothing to split.', out.length !== before ? 'ok' : 'warn');
    }
    if (o.outFmt && o.outFmt !== 'epub') await exportOther(o, chapters);
    else {
    log('Packing EPUB…');
    let cover = null;
    try { cover = await resolveCover(o); } catch (e) { log('Cover chain failed (' + _cvMsg(e) + '), using the old cover step.', 'warn'); }
    if (!cover) { try { if (o.coverUrl) cover = await getImage(o.coverUrl, o.convert); } catch { } }   // previous behaviour
    if (!cover) { try { cover = await makeCover(o.title, o.author); } catch { } }                      // previous behaviour
    const size = o.perVol > 0 ? o.perVol : chapters.length, parts = Math.ceil(chapters.length / size);
    for (let p = 0; p < parts; p++) {
      const chs = chapters.slice(p * size, (p + 1) * size), used = new Set();
      for (const c of chs) c.body = c.body.replace(/<img src="@@IMG(\d+)@@"([^>]*)\/>/g, (m, i, rest) => {
        const s = store.get(+i); if (!s) return ''; used.add(+i); return `<img src="../images/${s.name}"${rest}/>`;
      });
      const files = [...used].map(i => store.get(i));
      const name = parts > 1 ? `${o.title} - Vol ${String(p + 1).padStart(2, '0')} (Ch ${p * size + 1}-${p * size + chs.length})` : o.title;
      const blob = await buildEpub({
        meta: { title: name, author: o.author, language: o.language, description: o.description, series: o.series || (parts > 1 ? o.title : ''), source: o.url },
        chapters: chs, images: files, cover, css: makeCss(o.css)
      });
      await saveBlob(blob, fileNameFor(o, name, p, parts, size, chs.length).replace(/[\\/:*?"<>|]+/g, '_').slice(0, 120) + '.epub');
      log(`Done${parts > 1 ? ` (part ${p + 1}/${parts})` : ''}: ${chs.length} chapters, ${files.length} images, ${(blob.size / 1048576).toFixed(2)} MB`, 'ok');
      if (parts > 1) await sleep(700);
    }
    }
  } catch (e) { log('Error: ' + e.message, 'err'); }
  if (rtab) { chrome.tabs.remove(rtab.id).catch(() => { }); rtab = null; }
  setBusy(false);
}
init();
window.__w2e = { log, setTab: n => document.body.setAttribute('data-tab', n) };
