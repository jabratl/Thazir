// Media Grabber - background service worker
// Watches network responses, classifies media, stores them per tab, analyses playlists,
// pushes updates to the on-page UI and starts download jobs.
importScripts('shared.js', 'sites.js');
try { mgCapsSave(); } catch (e) { /* only informative */ }

// Android Chromium browsers (Kiwi, Edge Canary...) lack some desktop-only APIs. Stub them so the worker never dies at load.
const _noopEv = { addListener() {}, removeListener() {}, hasListener() { return false; } };
function _stub(name, shape) {
  try {
    if (!chrome[name]) chrome[name] = shape;
    else for (const k of Object.keys(shape)) if (!chrome[name][k]) chrome[name][k] = shape[k];
  } catch (e) { /* read-only: ignore */ }
}
_stub('commands', { onCommand: _noopEv });
_stub('contextMenus', { create() {}, onClicked: _noopEv });
_stub('notifications', { create() {}, clear() {}, onClicked: _noopEv });
_stub('alarms', { get: async () => null, create() {}, onAlarm: _noopEv });
// phones: lighter defaults (one stream job at a time, fewer parallel segment downloads) unless the user changed them
try {
  chrome.runtime.getPlatformInfo().then((p) => {
    if (p.os !== 'android') return;
    chrome.storage.sync.get(['maxJobs', 'concurrency']).then((r) => {
      const o = {};
      if (r.maxJobs == null) o.maxJobs = 1;
      if (r.concurrency == null) o.concurrency = 4;
      if (Object.keys(o).length) chrome.storage.sync.set(o);
    });
  }).catch(() => {});
} catch (e) { /* optional */ }

const SUB_EXT = new Set(['vtt', 'srt', 'ass', 'ssa', 'ttml', 'dfxp']);
const VID_EXT = new Set(['mp4', 'm4v', 'webm', 'mkv', 'mov', 'flv', 'avi', 'ogv', '3gp']);
const AUD_EXT = new Set(['mp3', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wav', 'flac']);
const SEGMENT_EXT = new Set(['ts', 'm4s', 'cmfv', 'cmfa']);
const SUB_CT = ['text/vtt', 'application/x-subrip', 'application/ttml+xml', 'text/srt'];
const NOISE_PARAMS = ['bytestart', 'byteend', 'range', 'rn', 'rbuf', 'start', 'end', 'begin', 'sq', 'cmsg'];

// ---------- settings (cached) ----------
let S = { ...DEFAULTS };
let settingsReady = chrome.storage.sync.get(null).then((s) => { S = { ...DEFAULTS, ...s }; });
chrome.storage.onChanged.addListener((c, area) => {
  if (area !== 'sync') return;
  settingsReady = chrome.storage.sync.get(null).then((s) => { S = { ...DEFAULTS, ...s }; });
});

function siteDisabled(url) {
  const h = hostOf(url);
  return !!h && (S.disabledSites || []).some((d) => d && (h === d || h.endsWith('.' + d)));
}

function classify(url, ct, hint) {
  ct = (ct || '').toLowerCase().split(';')[0].trim();
  const ext = extOf(url);
  const extra = (S.extraExts || '').toLowerCase().split(/[\s,]+/).filter(Boolean);
  if (SEGMENT_EXT.has(ext) || ct === 'video/mp2t' || ct === 'video/iso.segment') return null;
  if (ext === 'm3u8' || /mpegurl/.test(ct)) return 'hls';
  if (ext === 'mpd' || /dash\+xml/.test(ct)) return 'dash';
  if (SUB_EXT.has(ext) || SUB_CT.includes(ct)) return 'subtitle';
  if (ct.startsWith('video/')) return 'video';
  if (ct.startsWith('audio/')) return 'audio';
  if (VID_EXT.has(ext) || extra.includes(ext)) return 'video';
  if (AUD_EXT.has(ext)) return 'audio';
  if (/(subtitle|caption|timedtext|\/subs?\/|\/cc\/)/i.test(url) && ['json', 'xml', 'txt', 'ttml2'].includes(ext)) return 'subtitle';
  return hint || null;
}

function normKey(url) {
  try {
    const u = new URL(url);
    NOISE_PARAMS.forEach((p) => u.searchParams.delete(p));
    u.hash = '';
    return u.href;
  } catch (e) { return url; }
}

function header(headers, name) {
  const h = (headers || []).find((x) => x.name.toLowerCase() === name);
  return h ? h.value : '';
}

// ---------- header spoofing for our own fetches ----------
let ruleSeq = 1000;
const SKIP_H = new Set(['host', 'connection', 'content-length', 'content-type', 'accept', 'accept-encoding', 'accept-language', 'user-agent', 'cookie', 'range', 'if-range', 'if-none-match', 'if-modified-since', 'cache-control', 'pragma', 'upgrade-insecure-requests', 'priority', 'dnt', 'te', 'referer', 'origin']);
const hdrCache = {}; // host -> {ref, origin, extra, t}: headers the real player sent to that host
let hdrSaveT;
const hdrReady = chrome.storage.session.get('mgHdr').then((r) => { Object.assign(hdrCache, r.mgHdr || {}); }).catch(() => {});
chrome.webRequest.onBeforeSendHeaders.addListener((d) => {
  if (d.tabId < 0 || (d.initiator && d.initiator.startsWith('chrome-extension://'))) return;
  const ext = extOf(d.url);
  if (['js', 'css', 'html', 'htm', 'svg', 'ico', 'woff', 'woff2', 'ttf', 'otf', 'json', 'map', 'wasm'].includes(ext) && !((ext === 'css' || ext === 'ico') && (d.type === 'xmlhttprequest' || d.type === 'media'))) return; // V74n: a player's own css/ico request is a disguised segment
  const host = hostOf(d.url);
  if (!host || (S.blockAds && isAdUrl(d.url))) return;
  const playlist = ext === 'm3u8' || ext === 'mpd' || SUB_EXT.has(ext) || VID_EXT.has(ext) || AUD_EXT.has(ext);
  const old = hdrCache[host];
  if (old && !playlist && Date.now() - old.t < 600000) return; // segments: one sample per host is enough
  const h = {};
  for (const x of d.requestHeaders || []) h[x.name.toLowerCase()] = x.value;
  const extra = {};
  for (const k of Object.keys(h)) if (!SKIP_H.has(k) && !k.startsWith('sec-')) extra[k] = h[k];
  hdrCache[host] = { ref: h.referer || '', origin: h.origin || '', extra, t: Date.now() };
  if (!old) dbg('hdr', `${host}: referer=${h.referer ? 'yes' : 'none'}${h.referer ? '(' + hostOf(h.referer) + ')' : ''} origin=${h.origin ? 'yes' : 'none'}${h.origin ? '(' + hostOf(h.origin) + ')' : ''} extra=[${Object.keys(extra).join(',')}]`);
  const ks = Object.keys(hdrCache);
  if (ks.length > 40) ks.sort((a, b) => hdrCache[a].t - hdrCache[b].t).slice(0, ks.length - 40).forEach((k) => delete hdrCache[k]);
  clearTimeout(hdrSaveT);
  hdrSaveT = setTimeout(() => chrome.storage.session.set({ mgHdr: hdrCache }).catch(() => {}), 500);
}, { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other'] }, ['requestHeaders', 'extraHeaders']);

async function withReferer(url, ref, fn) {
  if (/[*^|]/.test(url) || /^data:/i.test(url)) return fn(); // V82: a data: URL (playlist handed over by siteMax.js) needs no header rule
  await hdrReady;
  const ent = hdrCache[hostOf(url)];
  if (!ent && !ref) return fn();
  const id = ruleSeq++;
  if (ruleSeq > 1000000) ruleSeq = 1000;
  const mk = (headers) => ({
    id, priority: 1,
    action: { type: 'modifyHeaders', requestHeaders: headers },
    condition: { urlFilter: '|' + url, initiatorDomains: [chrome.runtime.id], resourceTypes: ['xmlhttprequest'] }
  });
  const plain = () => [{ header: 'referer', operation: 'set', value: ref }, { header: 'origin', operation: 'set', value: new URL(ref).origin }];
  try {
    try { await chrome.declarativeNetRequest.updateSessionRules({ addRules: [mk(ent ? ruleHeaders(ent) : plain())] }); }
    catch (e) { // a recorded header was refused: retry with Referer/Origin only
      await chrome.declarativeNetRequest.updateSessionRules({ addRules: [mk(ent ? ruleHeaders(ent, true) : plain())] });
    }
  } catch (e) { /* ignore, try without */ }
  try { return await fn(); }
  finally { chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id] }).catch(() => {}); }
}

async function fetchText(url, ref, ms = 9000) {
  return withReferer(url, ref, async () => {
    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), ms);
    try {
      const r = await fetch(url, { credentials: 'include', signal: ac.signal });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return await r.text();
    } finally { clearTimeout(t); }
  });
}

// ---------- storage (serialised) ----------
let queue = Promise.resolve();
const tkey = (tabId) => 'tab_' + tabId;
const emptyRec = () => ({ title: '', pageUrl: '', hint: null, children: [], items: [] });

let notifyTimers = {};
function notify(tabId) {
  clearTimeout(notifyTimers[tabId]);
  notifyTimers[tabId] = setTimeout(() => {
    chrome.tabs.sendMessage(tabId, { type: 'items-changed' }).catch(() => {});
  }, 120);
}

async function setBadge(tabId, rec) {
  const n = rec.items.filter((i) => !i.hidden).length;
  try {
    await chrome.action.setBadgeBackgroundColor({ tabId, color: '#4f8cff' });
    await chrome.action.setBadgeText({ tabId, text: n ? String(n) : '' });
  } catch (e) { /* tab closed */ }
}

function addItems(tabId, meta, newItems) {
  queue = queue.then(async () => {
    const key = tkey(tabId);
    const rec = (await chrome.storage.session.get(key))[key] || emptyRec();
    if (meta.title) rec.title = meta.title;
    if (meta.pageUrl && (!rec.pageUrl || meta.top)) rec.pageUrl = meta.pageUrl;
    let changed = false;
    const fresh = [];
    for (let it of newItems) {
      if (it.kind === 'subtitle') {
        if (isThumbSub(it.url)) continue; // seek-bar preview track, not a subtitle
        if (!it.lang || !it.label) { const si = subInfo(it.url); it = { ...it, lang: it.lang || si.lang, label: it.label || si.label }; }
      }
      const k = normKey(it.url) + '|' + it.kind;
      const ex = rec.items.find((x) => x.key === k);
      if (ex) {
        for (const f of ['size', 'label', 'lang', 'ref']) if (!ex[f] && it[f]) { ex[f] = it[f]; changed = true; }
        if (ex.refused) { delete ex.refused; changed = true; } // V76: the same address answered 200 later: it works
        continue;
      }
      const o = { ...it, key: k, time: Date.now() };
      if ((rec.children || []).includes(normKey(it.url))) o.hidden = true;
      rec.items.push(o);
      markOldStreams(rec.items, o);
      fresh.push(o);
      changed = true;
    }
    if (!changed && !meta.title) return;
    await chrome.storage.session.set({ [key]: rec });
    await setBadge(tabId, rec);
    notify(tabId);
    for (const o of fresh) dbg('detect', `${o.kind}${o.hidden ? ' (hidden)' : ''} ${o.url}`);
    for (const o of fresh) if ((o.kind === 'hls' || o.kind === 'dash') && !o.hidden) analyze(tabId, o.key);
  }).catch(() => {});
  return queue;
}

function patchRec(tabId, fn) {
  queue = queue.then(async () => {
    const key = tkey(tabId);
    const rec = (await chrome.storage.session.get(key))[key];
    if (!rec) return;
    fn(rec);
    await chrome.storage.session.set({ [key]: rec });
    await setBadge(tabId, rec);
    notify(tabId);
  }).catch(() => {});
  return queue;
}

async function getRec(tabId) {
  const key = tkey(tabId);
  return (await chrome.storage.session.get(key))[key] || emptyRec();
}

// ---------- playlist analysis (quality list, DRM check, duration) ----------
function pageFetchTop(tabId, url, ms = 9000) { // fallback: let the page itself fetch the playlist (same cookies / origin as its own player)
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('timeout')), ms);
    chrome.tabs.sendMessage(tabId, { type: 'fetch-text', url }, { frameId: 0 }).then((r) => { clearTimeout(t); if (r && r.ok) res(r.text); else rej(new Error((r && r.err) || 'page fetch failed')); }).catch((e) => { clearTimeout(t); rej(e); });
  });
}
// V73b: the same, but asked in EVERY frame of the tab (the video player usually lives in an iframe with its own origin / cookies, which the top page cannot copy)
async function pageFetchAll0(tabId, url, ms = 9000) {
  const run = chrome.scripting.executeScript({ target: { tabId, allFrames: true }, args: [url], func: async (u) => { try { const r = await fetch(u, { credentials: 'include' }); if (!r.ok) return { ok: false, err: 'HTTP ' + r.status }; return { ok: true, text: await r.text() }; } catch (e) { return { ok: false, err: String((e && e.message) || e) }; } } });
  const res = await Promise.race([run, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);
  const hit = (res || []).map((x) => x && x.result).find((x) => x && x.ok && x.text);
  if (hit) return hit.text;
  const bad = (res || []).map((x) => x && x.result && x.result.err).find(Boolean);
  throw new Error(bad || 'no frame could read it');
}
// V74n: the same for a media SEGMENT (bytes, not text): some CDNs answer 403 to every header the extension can fake, but not to the player's own frame
async function pageFetchBin0(tabId, url, range, ms = 15000) {
  const run = chrome.scripting.executeScript({ target: { tabId, allFrames: true }, args: [url, range || ''], func: async (u, rng) => { try { const r = await fetch(u, { credentials: 'include', ...(rng ? { headers: { Range: rng } } : {}) }); if (!r.ok) return { ok: false, err: 'HTTP ' + r.status }; const b = new Uint8Array(await r.arrayBuffer()); let s = ''; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000)); return { ok: true, b64: btoa(s) }; } catch (e) { return { ok: false, err: String((e && e.message) || e) }; } } });
  const res = await Promise.race([run, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);
  const hit = (res || []).map((x) => x && x.result).find((x) => x && x.ok && x.b64);
  if (hit) return hit.b64;
  const bad = (res || []).map((x) => x && x.result && x.result.err).find(Boolean);
  throw new Error(bad || 'no frame could read it');
}
// V77a: scripting.executeScript only reaches the MAIN frame in MGB, so the player iframe (e.g. kwik.cx) was never asked. content.js of every frame answers fetch-text-frame / fetch-bin-frame (first real answer wins).
async function askFrames(tabId, type, url, range, ms) {
  const r = await Promise.race([chrome.tabs.sendMessage(tabId, { type, url, range: range || '' }), new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);
  if (r && r.ok) return r;
  throw new Error('no frame could read it');
}
async function pageFetchAll(tabId, url, ms = 9000) {
  try { return await pageFetchAll0(tabId, url, ms); }
  catch (e0) { try { const r = await askFrames(tabId, 'fetch-text-frame', url, '', ms); dbg('net', 'playlist read by a player frame'); return r.text; } catch (e1) { throw e0; } }
}
async function pageFetchBin(tabId, url, range, ms = 15000) {
  try { return await pageFetchBin0(tabId, url, range, ms); }
  catch (e0) { try { const r = await askFrames(tabId, 'fetch-bin-frame', url, range, ms); if (r.b64) return r.b64; } catch (e1) { /* keep first error */ } throw e0; }
}
async function pageFetch(tabId, url, ms = 9000) { // chain: top page -> every frame of the tab
  try { return await pageFetchTop(tabId, url, ms); } catch (e1) { dbg('net', 'top page could not read the playlist (' + (e1.message || e1) + '), asking every frame'); return await pageFetchAll(tabId, url, ms); }
}
// V79: playlist text captured from the player's own request (hook.js -> content.js -> here); analyze() reads it first, so no second request is needed
const plText = new Map();
const plKey = (u) => normKey(u);
function plPut(url, text) {
  plText.set(plKey(url), text);
  if (plText.size > 60) plText.delete(plText.keys().next().value);
}
// V80: a single-quality (media) playlist has no RESOLUTION line, so no resolution / size was shown (animepahe: kwik uwu.m3u8). Both are now measured:
// size = bytes of 3 sample segments (HEAD) scaled to the whole length; resolution = read from the H.264 header (SPS) inside the first segment (AES-128 handled);
// if that fails the resolution is guessed from the bit rate. Shown with "~" because it is an estimate.
function bitReader(u) {
  let p = 0;
  const bit = () => { const v = (u[p >> 3] >> (7 - (p & 7))) & 1; p++; return v; };
  const bits = (n) => { let v = 0; while (n-- > 0) v = v * 2 + bit(); return v; };
  const ue = () => { let z = 0; while (!bit() && z < 32) z++; return z ? (Math.pow(2, z) - 1) + bits(z) : 0; };
  const se = () => { const k = ue(); return k & 1 ? (k + 1) / 2 : -(k / 2); };
  return { bit, bits, ue, se, left: () => u.length * 8 - p };
}
function parseSps(nal) { // nal: bytes after the 00 00 01 start code, first byte = NAL header; returns {w, h} or null
  const u = []; // remove emulation prevention bytes (00 00 03)
  for (let i = 0; i < nal.length; i++) { if (i >= 2 && nal[i] === 3 && nal[i - 1] === 0 && nal[i - 2] === 0) continue; u.push(nal[i]); }
  const r = bitReader(Uint8Array.from(u.slice(1, 80)));
  const prof = r.bits(8); r.bits(16); r.ue();
  if ([100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].includes(prof)) {
    if (r.ue() === 3) r.bit();
    r.ue(); r.ue(); r.bit();
    if (r.bit()) return null; // scaling lists: not parsed
  }
  r.ue();
  const poc = r.ue();
  if (poc === 0) r.ue(); else if (poc === 1) { r.bit(); r.se(); r.se(); const n = r.ue(); if (n > 8) return null; for (let i = 0; i < n; i++) r.se(); }
  r.ue(); r.bit();
  const wm = r.ue() + 1, hm = r.ue() + 1, fmo = r.bit();
  if (!fmo) r.bit();
  r.bit();
  let cl = 0, cr = 0, ct = 0, cb = 0;
  if (r.bit()) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue(); }
  const w = wm * 16 - 2 * (cl + cr), h = (2 - fmo) * hm * 16 - 2 * (2 - fmo) * (ct + cb);
  return w > 0 && h > 0 && w < 8200 && h < 8200 ? { w, h } : null;
}
function spsFromTs(u) { // u: MPEG-TS bytes -> {w, h} from the first H.264 SPS found
  let s = 0;
  if (u[0] !== 0x47) { for (let i = 0; i < Math.min(u.length - 376, 8192); i++) if (u[i] === 0x47 && u[i + 188] === 0x47 && u[i + 376] === 0x47) { s = i; break; } }
  const by = {};
  for (let p = s; p + 188 <= u.length; p += 188) {
    if (u[p] !== 0x47) continue;
    const pid = ((u[p + 1] & 0x1f) << 8) | u[p + 2], afc = (u[p + 3] >> 4) & 3;
    let o = 4; if (afc === 2) continue; if (afc === 3) o += 1 + u[p + 4];
    if (o >= 188) continue;
    (by[pid] || (by[pid] = [])).push(u.subarray(p + o, p + 188));
  }
  for (const pid of Object.keys(by)) {
    const n = by[pid].reduce((a, c) => a + c.length, 0), v = new Uint8Array(n); let q = 0;
    for (const c of by[pid]) { v.set(c, q); q += c.length; }
    for (let i = 0; i + 4 < v.length; i++) if (v[i] === 0 && v[i + 1] === 0 && v[i + 2] === 1 && (v[i + 3] & 0x1f) === 7 && !(v[i + 3] & 0x80)) { const wh = parseSps(v.subarray(i + 3, i + 3 + 100)); if (wh) return wh; }
  }
  return null;
}
async function headLen(url, ref) { // size of one file without downloading it
  return withReferer(url, ref, async () => {
    const ac = new AbortController(), t = setTimeout(() => ac.abort(), 7000);
    try {
      let r = await fetch(url, { method: 'HEAD', credentials: 'include', signal: ac.signal });
      let n = r.ok ? parseInt(r.headers.get('content-length'), 10) || 0 : 0;
      if (!n) { r = await fetch(url, { credentials: 'include', headers: { Range: 'bytes=0-0' }, signal: ac.signal }); const m = (r.headers.get('content-range') || '').match(/\/(\d+)$/); n = m ? +m[1] : 0; try { r.body && r.body.cancel(); } catch (e) { /* ignore */ } }
      return n;
    } catch (e) { return 0; } finally { clearTimeout(t); }
  });
}
async function fetchBytes(url, ref, max, ms = 12000) { // the first `max` bytes
  return withReferer(url, ref, async () => {
    const ac = new AbortController(), t = setTimeout(() => ac.abort(), ms);
    try {
      const r = await fetch(url, { credentials: 'include', headers: { Range: 'bytes=0-' + (max - 1) }, signal: ac.signal });
      if (!r.ok || !r.body) return null;
      const rd = r.body.getReader(), parts = []; let n = 0;
      while (n < max) { const { value, done } = await rd.read(); if (done) break; parts.push(value); n += value.length; }
      try { rd.cancel(); ac.abort(); } catch (e) { /* ignore */ }
      const out = new Uint8Array(Math.min(n, max)); let q = 0;
      for (const c of parts) { const k = Math.min(c.length, out.length - q); out.set(c.subarray(0, k), q); q += k; if (q >= out.length) break; }
      return out;
    } catch (e) { return null; } finally { clearTimeout(t); }
  });
}
async function segResolution(seg, ref) { // {w, h} of the first segment, or null
  let u = await fetchBytes(seg.url, ref, 393216);
  if (!u || u.length < 400) return null;
  if (seg.key && seg.key.uri) { // AES-128: decrypt the first blocks (a picture header in front of the data is skipped first)
    let off = 0;
    if (u[0] === 0x89 && u[1] === 0x50) { let p = 8; while (p + 12 <= u.length) { const len = ((u[p] << 24) | (u[p + 1] << 16) | (u[p + 2] << 8) | u[p + 3]) >>> 0, t = String.fromCharCode(u[p + 4], u[p + 5], u[p + 6], u[p + 7]); p += 12 + len; if (t === 'IEND') { off = p; break; } } }
    else if (u[0] === 0xff && u[1] === 0xd8) { for (let i = 2; i < u.length - 1; i++) if (u[i] === 0xff && u[i + 1] === 0xd9) { off = i + 2; break; } }
    const n = Math.floor((u.length - off) / 16) * 16;
    if (n < 160) return null;
    const kb = await fetchBytes(seg.key.uri, ref, 16);
    if (!kb || kb.length !== 16) return null;
    const hex = seg.key.iv ? String(seg.key.iv).replace(/^0x/i, '').padStart(32, '0') : '';
    const iv = hex ? Uint8Array.from(hex.match(/../g).map((x) => parseInt(x, 16))) : (() => { const v = new Uint8Array(16); new DataView(v.buffer).setUint32(12, seg.seq >>> 0); return v; })();
    const key = await crypto.subtle.importKey('raw', kb, { name: 'AES-CBC' }, false, ['encrypt', 'decrypt']);
    const ct = u.slice(off, off + n);
    const pad = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv: ct.slice(n - 16) }, key, new Uint8Array(0))); // an encrypted padding block lets a partial stream be decrypted
    const all = new Uint8Array(n + pad.length); all.set(ct, 0); all.set(pad, n);
    u = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, key, all));
  } else if (u[0] === 0x89 || (u[0] === 0xff && u[1] === 0xd8)) { /* picture header, not encrypted: the TS scan below finds the sync bytes */ }
  return spsFromTs(u);
}
async function probeMedia(url, text, ref) {
  const out = {};
  const segs = parseMedia(text, url).segs;
  if (!segs.length) return out;
  const total = segs.reduce((a, s) => a + (s.dur || 0), 0);
  const idx = [0, Math.floor(segs.length / 2), Math.max(0, segs.length - 2)].filter((v, i, a) => a.indexOf(v) === i);
  let by = 0, du = 0;
  for (const i of idx) { const n = await headLen(segs[i].url, ref); if (n) { by += n; du += segs[i].dur || 0; } }
  if (by && du && total) { out.estSize = Math.round(by / du * total); out.estBw = Math.round(out.estSize * 8 / total); }
  try { const wh = await segResolution(segs[0], ref); if (wh) { out.estH = stdLabel(wh.w, wh.h).lh; out.estRes = wh.w + 'x' + wh.h; } } catch (e) { dbg('net', 'resolution probe failed: ' + (e.message || e)); }
  if (!out.estH && out.estBw) { const l = stdLabel(0, 0, '', '', out.estBw).lh; if (l) { out.estH = l; out.estGuess = 1; } }
  return out;
}
async function analyze(tabId, itemKey) {
  const rec = await getRec(tabId);
  const item = rec.items.find((i) => i.key === itemKey);
  if (!item) return;
  const ref = item.ref || rec.pageUrl;
  let info;
  try {
    let text;
    text = plText.get(plKey(item.url)); // V79
    if (!text) { try { text = await fetchText(item.url, ref); } catch (e1) { text = await pageFetch(tabId, item.url); } }
    if (item.kind === 'hls') {
      info = hlsInfo(text, item.url);
      if (!info.master && info.dur > 0 && !info.live && !info.protected) { try { Object.assign(info, await probeMedia(item.url, text, ref)); } catch (e) { /* optional */ } } // V80
      if (info.master && info.variants[0]) { // peek into the best variant for duration / DRM
        try {
          const have = info.variants.find((v) => plText.has(plKey(v.url))) || info.variants[0]; // V79: a variant the player itself loaded needs no new request
          const sub = hlsInfo(plText.get(plKey(have.url)) || await fetchText(have.url, ref), have.url);
          info.dur = sub.dur; info.segs = sub.segs; info.live = sub.live;
          info.protected = info.protected || sub.protected;
        } catch (e) { /* optional */ }
      }
    } else info = dashInfo(text);
  } catch (e) {
    info = { error: String(e.message || e) };
  }
  await patchRec(tabId, (r) => {
    const it = r.items.find((i) => i.key === itemKey);
    if (!it) return;
    it.info = info;
    if ((S.minDurationSec || 0) > 0 && info.dur > 0 && !info.live && info.dur < S.minDurationSec) it.hidden = true; // too short (ad / preview)
    for (const sb of info.subs || []) {
      const k = normKey(sb.uri) + '|subtitle';
      if (!r.items.some((x) => x.key === k)) r.items.push({ url: sb.uri, kind: 'subtitle', label: sb.name, lang: sb.lang, ref: it.ref, key: k, time: Date.now() });
    }
    if (info.children && info.children.length) {
      const kids = info.children.map(normKey);
      r.children = Array.from(new Set([...(r.children || []), ...kids]));
      r.items.forEach((x) => { if (x !== it && kids.includes(normKey(x.url))) x.hidden = true; });
    }
  });
}

// ---------- body sniffing: playlists / subtitles served with a wrong content-type or a fake extension ----------
const AMBIG_CT = new Set(['', 'text/plain', 'application/octet-stream', 'binary/octet-stream', 'application/force-download', 'application/x-download']);
const SKIP_EXT = new Set(['js', 'css', 'html', 'htm', 'svg', 'ico', 'woff', 'woff2', 'ttf', 'otf', 'json', 'map', 'wasm']);
const sniffState = {}; // tabId -> {n, dirs}
async function sniffHead(url, ref) {
  return withReferer(url, ref, async () => {
    const ac = new AbortController();
    const to = setTimeout(() => ac.abort(), 6000);
    try {
      const r = await fetch(url, { credentials: 'include', headers: { Range: 'bytes=0-1023' }, signal: ac.signal });
      if (!r.ok || !r.body) return null;
      const rd = r.body.getReader();
      const { value } = await rd.read();
      try { rd.cancel(); ac.abort(); } catch (e) { /* ignore */ }
      return value ? value.slice(0, 1024) : null; // V78: raw bytes (text and file signatures are both checked by the caller)
    } catch (e) { return null; } finally { clearTimeout(to); }
  });
}
// V78: file signature of a media file whose address / content-type say nothing (what MAX Video Downloader finds by reading the first bytes)
function magicKind(u) {
  if (u.length >= 12 && u[4] === 0x66 && u[5] === 0x74 && u[6] === 0x79 && u[7] === 0x70) return { kind: 'video', ext: 'mp4' }; // ftyp
  if (u.length >= 4 && u[0] === 0x1a && u[1] === 0x45 && u[2] === 0xdf && u[3] === 0xa3) return { kind: 'video', ext: 'webm' };
  if (u.length >= 4 && u[0] === 0x46 && u[1] === 0x4c && u[2] === 0x56 && u[3] === 1) return { kind: 'video', ext: 'flv' };
  if (u.length >= 4 && u[0] === 0x4f && u[1] === 0x67 && u[2] === 0x67 && u[3] === 0x53) return { kind: 'audio', ext: 'ogg' };
  if (u.length >= 3 && u[0] === 0x49 && u[1] === 0x44 && u[2] === 0x33) return { kind: 'audio', ext: 'mp3' };
  if (u.length >= 4 && u[0] === 0x66 && u[1] === 0x4c && u[2] === 0x61 && u[3] === 0x43) return { kind: 'audio', ext: 'flac' };
  return null;
}
const OCTET_CT = new Set(['', 'application/octet-stream', 'binary/octet-stream', 'application/force-download', 'application/x-download']);
function maybeSniff(d, ct) {
  if (!['xmlhttprequest', 'other', 'media'].includes(d.type)) return;
  ct = (ct || '').toLowerCase().split(';')[0].trim();
  // V78: a playlist can be sent as image/*, text/html or xml (a disguised address + content-type); such a body is small, so only small ones are probed
  const disguised = (/^image\//.test(ct) && ct !== 'image/svg+xml') || ct === 'text/html' || ct === 'application/xml' || ct === 'text/xml';
  if (!AMBIG_CT.has(ct) && !disguised) return;
  if (SKIP_EXT.has(extOf(d.url)) || SEGMENT_EXT.has(extOf(d.url))) return;
  const cr = header(d.responseHeaders, 'content-range').match(/\/(\d+)$/);
  const len = cr ? parseInt(cr[1], 10) : (parseInt(header(d.responseHeaders, 'content-length'), 10) || 0);
  const big = len > 2 * 1024 * 1024;
  if (big && !OCTET_CT.has(ct)) return; // V78: a big untyped file is probed for a video signature (below); big html / image bodies are not
  if (disguised && len > 300 * 1024) return;
  let dir = d.url;
  try { const u = new URL(d.url); dir = u.origin + u.pathname.replace(/[^/]*$/, ''); } catch (e) { /* keep */ }
  const st = sniffState[d.tabId] || (sniffState[d.tabId] = { n: 0, dirs: {} });
  if (st.n >= 60 || (st.dirs[dir] || 0) >= 2) return; // do not probe hundreds of segments
  st.dirs[dir] = (st.dirs[dir] || 0) + 1; st.n++;
  const ref = d.documentUrl || d.initiator || '';
  sniffHead(d.url, ref).then(async (bytes) => {
    if (!bytes || !bytes.length) return;
    const head = new TextDecoder('utf-8', { fatal: false }).decode(bytes).replace(/^\uFEFF/, '').trimStart();
    let kind = /^#EXTM3U/.test(head) ? 'hls' : /^<\?xml[^>]*>\s*<MPD|^<MPD/i.test(head) ? 'dash' : /^WEBVTT/.test(head) ? 'subtitle' : '';
    let sz = 0, why = 'content';
    if (!kind && big) { // V78: file signature (mp4 / webm / flv / ogg / mp3 / flac) of a big file with no useful address or content-type
      const mk = magicKind(bytes);
      if (mk && len >= Math.max(1, S.minSizeKB || 0) * 1024) { kind = mk.kind; sz = len; why = 'file signature ' + mk.ext; }
    }
    if (!kind) return;
    const tab = await chrome.tabs.get(d.tabId).catch(() => null);
    if (!tab || siteDisabled(tab.url)) return;
    dbg('sniff', `${kind} found by ${why}: ${d.url}`);
    addItems(d.tabId, { title: tab.title, pageUrl: tab.url, top: true }, [{ url: d.url, kind, ct, size: sz, ref: ref || tab.url }]);
  });
}
chrome.tabs.onRemoved.addListener((id) => { delete sniffState[id]; delete uiTabFor[id]; });
chrome.tabs.onUpdated.addListener((id, info) => { if (info.status === 'loading' && info.url) delete sniffState[id]; });

// ---------- network capture ----------
const netLogged = new Set();
// V76: the player's OWN request for a stream was refused (burnt / wrong token): flag that item so Best / batch do not take it while a newer one exists
function markRefused(tabId, url) {
  queue = queue.then(async () => {
    const key = tkey(tabId), rec = (await chrome.storage.session.get(key))[key];
    if (!rec || !rec.items) return;
    const k = normKey(url); let ch = false;
    for (const x of rec.items) if (!x.refused && normKey(x.url) === k) { x.refused = true; ch = true; }
    if (!ch) return;
    await chrome.storage.session.set({ [key]: rec }); notify(tabId);
  }).catch(() => {});
}
chrome.webRequest.onHeadersReceived.addListener(
  (d) => {
    if (d.tabId < 0) return;
    if (d.initiator && d.initiator.startsWith('chrome-extension://')) return; // our own downloads
    if (d.statusCode >= 400) { // log blocked media requests so the Debug log shows what the site refused
      const x = extOf(d.url);
      if (['m3u8', 'mpd', 'mp4', 'vtt', 'srt', 'ts', 'm4s', 'key', 'webm'].includes(x) && !netLogged.has(d.statusCode + d.url)) {
        netLogged.add(d.statusCode + d.url); if (netLogged.size > 300) netLogged.clear();
        dbg('net', `page request got HTTP ${d.statusCode}: ${d.url}`);
      }
      if ([401, 403, 410].includes(d.statusCode) && ['m3u8', 'mpd', 'mp4'].includes(extOf(d.url))) markRefused(d.tabId, d.url); // V76
      return;
    }
    if (d.statusCode !== 200 && d.statusCode !== 206 && d.statusCode !== 304) return;
    const ct = header(d.responseHeaders, 'content-type');
    settingsReady.then(() => {
      if (!S.enabled) return;
      const kind = classify(d.url, ct);
      if (!kind) { maybeSniff(d, ct); return; }
      if (S.blockAds && (isAdUrl(d.url) || (d.initiator && isAdHost(hostOf(d.initiator))))) return;

      let size = 0;
      const cr = header(d.responseHeaders, 'content-range');
      const m = cr.match(/\/(\d+)$/);
      if (m) size = parseInt(m[1], 10);
      else if (d.statusCode === 200) size = parseInt(header(d.responseHeaders, 'content-length'), 10) || 0;
      if ((kind === 'video' || kind === 'audio') && size && size < (S.minSizeKB || 0) * 1024) return;

      chrome.tabs.get(d.tabId).then((tab) => {
        if (siteDisabled(tab.url)) return;
        addItems(d.tabId, { title: tab.title, pageUrl: tab.url, top: true }, [
          { url: d.url, kind, ct: ct.split(';')[0], size, ref: d.documentUrl || d.initiator || tab.url }
        ]);
      }).catch(() => {});
    });
  },
  { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other', 'object', 'sub_frame', 'main_frame'] },
  ['responseHeaders']
);

// ---------- downloads ----------
const active = new Map(); // downloadId -> {tabId, key, retried, item, rec}

// ---------- job list for the Queue page (kept in session storage, survives service-worker sleep) ----------
let jobsChain = Promise.resolve();
function trackJob(key, patch) {
  if (!key) return;
  jobsChain = jobsChain.then(async () => {
    const r = (await chrome.storage.session.get('mgJobs')).mgJobs || {};
    const j = r[key] || { key, started: Date.now(), title: baseName(String(key).replace(/^ext\|/, '')) };
    if (patch.pct === -1) delete patch.pct; // "-1" = keep the old percentage (pause notices)
    Object.assign(j, patch, { updated: Date.now() });
    r[key] = j;
    const ks = Object.keys(r);
    if (ks.length > 60) ks.sort((a, b) => r[a].updated - r[b].updated).slice(0, ks.length - 60).forEach((k) => delete r[k]);
    await chrome.storage.session.set({ mgJobs: r });
    if (patch.state === 'done' && j.epUrl) { // remember finished episodes so batches can skip them next time
      const d = new Set((await chrome.storage.local.get('mgDone')).mgDone || []);
      d.add(j.epUrl);
      await chrome.storage.local.set({ mgDone: [...d].slice(-5000) });
    }
  }).catch(() => {});
}

const jobMeta = {}; // key -> {title, kind} so the on-page Downloads list can name every job
// V63: a pop-up opened by a helper (sniff) tab is closed - but never one of this extension's own pages. On some browsers (Android) a tab made by the
// extension gets the front tab as its opener, and while a helper tab is shown the downloader tab was closed at once ("the downloader does not open").
const EXT_ORIGIN = chrome.runtime.getURL('');
const isExtUrl = (u) => !!u && String(u).startsWith(EXT_ORIGIN);
chrome.tabs.onCreated && chrome.tabs.onCreated.addListener((t) => {
  if (!t || t.openerTabId == null || uiTabFor[t.openerTabId] == null) return;
  if (isExtUrl(t.pendingUrl) || isExtUrl(t.url) || running.has(t.id)) return;
  setTimeout(async () => { // the address may still be empty at this moment: look again before closing
    try {
      const x = await chrome.tabs.get(t.id);
      if (isExtUrl(x.pendingUrl) || isExtUrl(x.url) || running.has(t.id)) return;
      if (uiTabFor[t.openerTabId] == null) return;
      chrome.tabs.remove(t.id).catch(() => {});
    } catch (e) { /* already gone */ }
  }, 400);
});
const uiTabFor = {}; // helper (sniff) tab -> tab that started the batch, so progress shows on the original page
function sendJob(tabId, key, state, pct, text) {
  if (tabId != null && uiTabFor[tabId] != null) tabId = uiTabFor[tabId];
  if (state === 'error' && text && !/Cancelled/i.test(text) && !/ - (Blocked|The site|The link|Cloudflare|Network|DRM|The server|Disk)/.test(text)) {
    const h = diagnose(text);
    dbg('job', `${key} -> ${text}`);
    if (h) text = text + ' - ' + h;
  }
  trackJob(key, { state, pct, text, tab: tabId });
  if (!Number.isInteger(tabId) || tabId < 0) return;
  try { chrome.tabs.sendMessage(tabId, { type: 'job-update', key, state, pct, text, title: (jobMeta[key] || {}).title, kind: (jobMeta[key] || {}).kind }, { frameId: 0 }).catch(() => {}); } catch (e) { /* tab gone */ }
}

function nameVars(rec, item, extra = {}) {
  let title = rec.title || baseName(item.url);
  if (S.cleanTitles !== false) { try { title = cleanFullTitle(title, hostOf(rec.pageUrl || item.url), titleNames(S.titleStrip)) || title; } catch (e) { /* keep the plain title */ } } // V73: no website name in the file name
  return {
    title,
    site: hostOf(rec.pageUrl || item.url),
    name: baseName(item.url).replace(/\.[a-z0-9]{2,5}$/i, ''),
    kind: item.kind, res: '', lang: item.lang || item.label || '',
    book: rec.book || '', chapter: rec.chapter || '', index: rec.index || '', ...extra
  };
}

// Stream jobs run through a small queue (Settings: parallel jobs) so "Download all" does not open 20 tabs at once
const jobQueue = [];
const running = new Map(); // downloader tabId (or 'off' for the hidden document) -> job key
const jobParams = new Map(), jobState = {}; // key -> how the job was started / last state it reported (used to restart a job whose tab got closed)
const offJobs = new Set(), offClosing = new Set(); // keys of jobs running in the hidden document / being wrapped up
const offDl = new Map(); // browser download id -> job key (files the hidden document handed to the browser)
function openJob(p) {
  jobQueue.push(p);
  if (p.key) {
    jobState[p.key] = 'queued'; // becomes 'run' once the downloader itself reports progress
    trackJob(p.key, { params: Object.fromEntries(Object.entries(p).filter(([k]) => k[0] !== '_')) }); // for Retry on the Queue page
    sendJob(+p.tab, p.key, 'run', 0, p._note || (running.size >= (S.maxJobs || 2) ? 'Queued...' : 'Opening downloader...'));
  }
  pumpQueue();
}

// ---------- hidden background downloads (offscreen document: no tab to close by accident) ----------
async function hasOffscreen() {
  try { return (await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] })).length > 0; } catch (e) { return false; }
}
let hostP = null; // several jobs may start at the same moment: they all wait for the same single createDocument()
function ensureHost() {
  if (!hostP) hostP = (async () => {
    if (!(await hasOffscreen())) await chrome.offscreen.createDocument({ url: 'offhost.html', reasons: ['BLOBS'], justification: 'Download and join stream segments in the background so no tab has to stay open.' });
  })().finally(() => { hostP = null; });
  return hostP;
}
// V64: V63 made one failed start of the hidden document final (offBroken = true for the whole session), so every later download went to a hidden
// downloader tab, which is the one that ended in Retry / Resume. A failed start now only sends THAT job to a tab; the next job tries the hidden document again.
const withTimeout = (pr, ms, what) => Promise.race([pr, new Promise((_, rej) => setTimeout(() => rej(new Error(what + ' timed out')), ms))]);
async function startOffscreen(p) {
  if (!chrome.offscreen || !p.key || p._noOff) return false;
  try {
    try { await withTimeout(ensureHost(), 20000, 'hidden document'); }
    catch (e0) { // V65: one recovery try: drop a stuck document and make a fresh one
      dbg('dl', 'hidden document did not start, trying once more: ' + (e0.message || e0)); hostP = null;
      try { if (!offJobs.size) await chrome.offscreen.closeDocument(); } catch (x) { /* none */ }
      await withTimeout(ensureHost(), 20000, 'hidden document (2nd)');
    }
    const qs = new URLSearchParams(Object.fromEntries(Object.entries(p).filter(([k]) => k[0] !== '_'))).toString();
    let ok = false;
    for (let i = 0; i < 5 && !ok; i++) { // the host script may need a moment to start listening
      try { ok = !!(await chrome.runtime.sendMessage({ type: 'off-start', key: p.key, qs }))?.ok; } catch (e) { /* retry */ }
      if (!ok) await new Promise((r) => setTimeout(r, 150));
    }
    if (!ok) throw new Error('host did not answer');
    offJobs.add(p.key);
    const wk = p.key; // V65 watchdog: if the hidden document never reports progress, the job is moved to a normal downloader tab
    setTimeout(() => {
      if (jobState[wk] === 'queued' && offJobs.has(wk) && !offClosing.has(wk) && (p._r || 0) < 2) {
        dbg('dl', 'hidden document gave no progress in 60s, using a downloader tab: ' + wk);
        chrome.runtime.sendMessage({ type: 'off-end', key: wk }).catch(() => {});
        offJobs.delete(wk); running.delete('off:' + wk);
        openJob({ ...p, _noOff: 1, _r: (p._r || 0) + 1, _note: 'Background start was slow - opening the downloader...' });
      }
    }, 60000);
    return true;
  } catch (e) { dbg('dl', 'offscreen start failed, using a tab for this job: ' + (e.message || e)); return false; }
}
async function endOffscreenJob(key) {
  // a removed frame drops its blobs, so wait until the browser has finished writing this job's files
  const t0 = Date.now();
  while (Date.now() - t0 < 15 * 60e3) {
    let busy = false;
    for (const [id, k] of offDl) if (k === key) { const [d] = await chrome.downloads.search({ id }).catch(() => []); if (d && d.state === 'in_progress') busy = true; }
    if (!busy) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  let left = 1;
  try { left = ((await chrome.runtime.sendMessage({ type: 'off-end', key })) || {}).left ?? 1; } catch (e) { /* host gone */ }
  for (const [id, k] of offDl) if (k === key) offDl.delete(id);
  const finished = jobState[key] === 'done';
  offJobs.delete(key); offClosing.delete(key); running.delete('off:' + key);
  const starting = [...running.keys()].some((k) => typeof k === 'string' && k.startsWith('off:')); // V64: a job that is just starting must not lose its document
  if ((!offJobs.size || left === 0) && !starting) { try { await chrome.offscreen.closeDocument(); } catch (e) { /* already gone */ } }
  if (finished && S.notify && !running.size && !jobQueue.length) notifyUser('Media Grabber', 'Download finished');
  pumpQueue();
}
// the hidden frames only have chrome.runtime: they ask us to call the other APIs for them
async function offRpc(m) {
  const a = (m.args || [])[0];
  if (/^storage\.(local|sync|session)$/.test(m.api)) return { value: await chrome.storage[m.api.split('.')[1]][m.method](a) };
  if (m.api === 'dnr') return { value: await chrome.declarativeNetRequest.updateSessionRules(a) };
  if (m.api === 'downloads' && /^(pause|resume|cancel)$/.test(m.method || '')) { await chrome.downloads[m.method](a); return { value: null }; } // 0.0.369 (N1)
  if (m.api === 'downloads') { const id = await chrome.downloads.download(a); offDl.set(id, m.key || ''); return { value: id }; }
  return { error: 'unknown api ' + m.api };
}
chrome.downloads.onChanged.addListener((d) => { if (offDl.has(d.id)) chrome.runtime.sendMessage({ type: 'off-dl-changed', delta: d }).catch(() => {}); });
async function pumpQueue() {
  while (jobQueue.length && running.size < (S.maxJobs || 2)) {
    const p = jobQueue.shift();
    if (p.key) jobParams.set(p.key, p);
    const needsUi = p.auto !== '1' && p.mode !== 'file' && p.mode !== 'subtitle'; // quality picker lives in the downloader page
    if ((S.bgDownload !== false || p._bg) && !needsUi && chrome.offscreen && p.key) {
      running.set('off:' + p.key, p.key); // reserve the slot before awaiting
      if (await startOffscreen(p)) continue;
      running.delete('off:' + p.key);
    }
    const u = chrome.runtime.getURL('downloader.html') + '?' + new URLSearchParams(Object.fromEntries(Object.entries(p).filter(([k]) => k[0] !== '_')));
    let id = null;
    for (let tryN = 0; tryN < 2 && id == null; tryN++) { // V63: second try with the tab in front (some mobile browsers refuse a hidden new tab)
      try {
        if (S.jobWindow === 'window' && tryN === 0) id = (await chrome.windows.create({ url: u, type: 'popup', width: 540, height: 460, focused: p.manual === '1' })).tabs[0].id;
        else id = (await chrome.tabs.create({ url: u, active: p.manual === '1' || tryN > 0 })).id;
      } catch (e) { dbg('dl', 'downloader tab did not open (try ' + (tryN + 1) + '): ' + String((e && e.message) || e).slice(0, 120)); }
    }
    if (id == null) { if (p.key) sendJob(+p.tab, p.key, 'error', 0, 'The downloader page could not be opened - press Retry'); continue; }
    running.set(id, p.key || '');
    if (p.key) { // a hidden tab that never starts (frozen in the background): bring it to the front once
      const k = p.key, tid = id;
      setTimeout(() => { if (jobState[k] === 'queued' && running.get(tid) === k) { dbg('dl', 'downloader tab did not start in 25s, showing it: ' + k); chrome.tabs.update(tid, { active: true }).catch(() => {}); } }, 25000);
    }
  }
}
function freeJob(key) {
  if (offJobs.has(key)) { if (!offClosing.has(key)) { offClosing.add(key); endOffscreenJob(key); } return; }
  for (const [id, k] of running) if (k === key) { running.delete(id); break; }
  pumpQueue();
}
chrome.tabs.onRemoved.addListener((id) => {
  if (!running.has(id)) return;
  const key = running.get(id); running.delete(id);
  const p = key && jobParams.get(key);
  if (p && jobState[key] === 'run' && (p._r || 0) < 2) { // the tab was closed mid-download: carry on in the hidden document
    dbg('dl', 'downloader tab closed while running, restarting in the background: ' + key);
    openJob({ ...p, auto: '1', _r: (p._r || 0) + 1, _bg: 1, _note: 'Downloader tab was closed - restarting in the background...' });
  } else {
    if (p && jobState[key] === 'queued' && p.auto === '1' && (p._r || 0) < 2) { // V65: retry by itself, the other way round (hidden document <-> tab)
      dbg('dl', 'downloader tab closed before start, retrying: ' + key);
      openJob({ ...p, _noOff: p._noOff ? 0 : 1, _r: (p._r || 0) + 1, _note: 'Downloader was closed - trying again...' });
    } else if (p && jobState[key] === 'queued') sendJob(+p.tab, key, 'error', 0, 'Downloader was closed before the download started');
    pumpQueue();
  }
});

async function directDownload(tabId, rec, item, fallback) {
  const ext = guessExt(item);
  const filename = buildFileName(S, nameVars(rec, item, { suffix: item.kind === 'audio' ? 'audio' : '' }), ext);
  let id;
  try {
    id = await chrome.downloads.download({ url: item.url, filename, conflictAction: 'uniquify' });
  } catch (e) { fallback(); return; }
  active.set(id, { tabId, key: item.key, fallback, title: filename.split('/').pop(), kind: item.kind, src: item.url, tt: nameVars(rec, item).title });
  sendJob(tabId, item.key, 'run', 0, 'Starting...');
  pollDownload(id);
}

async function pollDownload(id) {
  const a = active.get(id);
  if (!a) return;
  const [dl] = await chrome.downloads.search({ id });
  if (!dl) { active.delete(id); return; }
  if (dl.state === 'complete') {
    sendJob(a.tabId, a.key, 'done', 1, 'Saved');
    addHistory({ id, title: a.title, kind: a.kind, site: hostOf(a.src), src: a.src, size: dl.fileSize || dl.totalBytes || 0, ik: a.key || '', tt: a.tt || '' });
    active.delete(id);
    return;
  }
  if (dl.state === 'interrupted') {
    active.delete(id);
    if (['SERVER_FORBIDDEN', 'SERVER_UNAUTHORIZED', 'SERVER_BAD_CONTENT', 'SERVER_FAILED', 'NETWORK_FAILED'].includes(dl.error) && a.fallback) {
      sendJob(a.tabId, a.key, 'run', 0, 'Retrying with page headers...');
      a.fallback();
    } else sendJob(a.tabId, a.key, 'error', 0, 'Failed: ' + dl.error);
    return;
  }
  const pct = dl.totalBytes > 0 ? dl.bytesReceived / dl.totalBytes : 0;
  sendJob(a.tabId, a.key, 'run', pct, (dl.paused ? 'Paused - ' : '') + (dl.totalBytes > 0 ? `${fmtSize(dl.bytesReceived)} / ${fmtSize(dl.totalBytes)}` : fmtSize(dl.bytesReceived)));
  setTimeout(() => pollDownload(id), 700);
}

// V72: one subtitle, any format in -> srt / vtt / txt / original out. Several ways to get the text (the first that works wins):
// 1) the background fetch with the page as referer, 2) the same without cookies, 3) a fetch inside the page itself (cookies, blob: links), 4) the downloader page.
const SUB_FMTS = ['srt', 'vtt', 'txt', 'orig'];
function normFmt(f) { if (f === true) return SUB_FMTS.includes(S.subFmt) ? S.subFmt : 'srt'; if (f === false) return 'orig'; return SUB_FMTS.includes(f) ? f : (SUB_FMTS.includes(S.subFmt) ? S.subFmt : 'srt'); }
async function subFetch(tabId, item, ref) {
  if (typeof item.text === 'string' && item.text) return item.text; // captured from the player
  let err = null;
  try { return await fetchText(item.url, ref, 15000); } catch (e) { err = e; }
  try { // some servers refuse cookies
    const ac = new AbortController(); const t = setTimeout(() => ac.abort(), 12000);
    try { const r = await fetch(item.url, { credentials: 'omit', signal: ac.signal }); if (r.ok) return await r.text(); } finally { clearTimeout(t); }
  } catch (e) { err = err || e; }
  try { // ask the page itself (works for blob: links and for links that need the page's login)
    const r = await chrome.tabs.sendMessage(tabId, { type: 'fetch-text', url: item.url }, { frameId: 0 });
    if (r && r.ok && r.text) return r.text;
    if (r && r.err) err = err || new Error(r.err);
  } catch (e) { err = err || e; }
  throw err || new Error('could not read the subtitle');
}
async function saveSubText(text, rec, item, fmt) {
  const isVtt = /^\uFEFF?WEBVTT/.test(text);
  const srcExt = isVtt ? 'vtt' : (extOf(item.url) && !/^(php|html?|aspx?|json|xml)$/.test(extOf(item.url)) ? extOf(item.url) : (/^\s*[\[{]/.test(text) ? 'json' : /<tt[\s>]|<transcript|<timedtext/i.test(text.slice(0, 600)) ? 'ttml' : 'txt'));
  const out = subConvert(text, fmt, srcExt, S.subDelayMs); // 0.0.351 (V82)
  if (fmt !== 'orig' && !out.converted) dbg('subs', 'format not understood, saved as it is: ' + item.url.slice(0, 120));
  const filename = buildFileName(S, nameVars(rec, item, { suffix: item.lang || item.label || '' }), out.ext);
  await chrome.downloads.download({ url: 'data:text/plain;charset=utf-8,' + encodeURIComponent('\uFEFF' + out.text), filename, conflictAction: 'uniquify' });
  return out;
}
async function subtitleJob(tabId, rec, item, fmt) {
  fmt = normFmt(fmt);
  const ref = item.ref || rec.pageUrl;
  const viaPage = () => openJob({ url: item.url, mode: 'subtitle', title: rec.title || baseName(item.url), ref, tab: tabId, key: item.key, lang: item.lang || item.label || '', fmt });
  if (extOf(item.url) === 'm3u8') return viaPage();
  try {
    const text = await subFetch(tabId, item, ref);
    try {
      const out = await saveSubText(text, rec, item, fmt);
      sendJob(tabId, item.key, 'done', 1, out.converted || fmt === 'orig' ? 'Saved .' + out.ext : 'Saved (original format)');
    } catch (e2) { // some mobile browsers refuse this: let the downloader page save it with a plain link instead
      dbg('subs', 'chrome.downloads refused the subtitle, using the downloader page: ' + (e2.message || e2));
      return viaPage();
    }
  } catch (e) {
    dbg('subs', 'background read failed (' + (e.message || e) + '), trying the downloader page');
    if (item.text) return sendJob(tabId, item.key, 'error', 0, 'Failed: ' + (e.message || e));
    return viaPage(); // last way: the downloader page reads it with the page headers
  }
}

// V72: save every subtitle of the page at once (one file per language, thumbnail tracks skipped, same links not twice)
async function subtitlesAll(tabId, fmt) {
  await settingsReady;
  const rec = await getRec(tabId);
  const seen = new Set(), list = [];
  for (const it of rec.items) {
    if (it.kind !== 'subtitle' || it.hidden || isThumbSub(it.url)) continue;
    const k = normKey(it.url); if (seen.has(k)) continue; seen.add(k); list.push(it);
  }
  if (!list.length) return 0;
  for (const it of list.slice(0, 60)) await subtitleJob(tabId, rec, { ...it, lang: it.lang || subInfo(it.url).lang || it.label || '' }, fmt);
  return list.length;
}
// V72: subtitles read from the video player itself (text tracks the page loaded: works for blob: links and for subtitles that have no file link)
async function subtitlesCaptured(tabId, tracks, fmt) {
  await settingsReady;
  const rec = await getRec(tabId);
  let n = 0;
  for (const t of (tracks || []).slice(0, 20)) {
    if (!t || !t.cues || !t.cues.length) continue;
    const text = cuesToVtt(t.cues.map((c) => ({ s: +c.s, e: +c.e, t: String(c.t || '') })).filter((c) => c.t && isFinite(c.s) && isFinite(c.e)));
    const lang = t.lang || (t.label || '').slice(0, 24);
    const item = { url: (rec.pageUrl || 'https://page.local/') + '#captured-' + (lang || n), kind: 'subtitle', lang, label: t.label || lang, key: 'cap|' + (lang || n), text, ref: rec.pageUrl || '' };
    try {
      const f = normFmt(fmt);
      const out = await saveSubText(text, rec, { ...item, url: (lang || 'subtitle') + '.vtt' }, f);
      n++; dbg('subs', 'captured ' + (t.label || lang) + ' as .' + out.ext);
    } catch (e) { dbg('subs', 'capture save failed: ' + (e.message || e)); }
  }
  return n;
}


// V72: runs INSIDE the page (every frame): reads the subtitle tracks the video player already loaded (works for blob: links and tracks without a file link)
async function captureTracksFn() {
  const out = [];
  for (const v of Array.from(document.querySelectorAll('video'))) {
    for (const tt of Array.from(v.textTracks || [])) {
      if (tt.kind !== 'subtitles' && tt.kind !== 'captions') continue;
      const prev = tt.mode;
      if (prev === 'disabled') tt.mode = 'hidden'; // makes the browser load the cues without showing them
      for (let i = 0; i < 25 && (!tt.cues || !tt.cues.length); i++) await new Promise((r) => setTimeout(r, 160));
      const cues = tt.cues ? Array.from(tt.cues).map((c) => ({ s: c.startTime, e: c.endTime, t: c.text != null ? String(c.text) : '' })).filter((c) => c.t) : [];
      if (prev === 'disabled') tt.mode = prev;
      if (cues.length) out.push({ label: tt.label || '', lang: tt.language || '', cues });
    }
  }
  return out;
}
async function subtitlesFromPlayer(tabId, fmt) {
  const res = await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, func: captureTracksFn });
  const seen = new Set(), tracks = [];
  for (const r of res || []) for (const t of (r && r.result) || []) { const k = (t.lang || t.label) + '|' + t.cues.length; if (seen.has(k)) continue; seen.add(k); tracks.push(t); }
  const n = await subtitlesCaptured(tabId, tracks, fmt);
  return { n, found: tracks.length };
}

// V71: "Download again?" - an item counts as already downloaded only when a finished download of it is in the history AND the file is still on the disk.
// If the file was deleted / moved the answer is "no" and the download runs exactly as before. Any problem here = "no" (never blocks a download).
async function findDuplicate(tabId, key) {
  await settingsReady;
  if (S.dupAsk === false) return null;
  const rec = await getRec(tabId);
  const item = rec && rec.items && rec.items.find((i) => i.key === key);
  if (!item || item.kind === 'subtitle' || (item.info && item.info.protected)) return null;
  const title = nameVars(rec, item).title, site = hostOf(item.ref || rec.pageUrl || item.url);
  const hist = (await chrome.storage.local.get('mgHist')).mgHist || [];
  for (const e of hist.slice(0, 300)) {
    if (!e || !(e.id > 0)) continue; // id 0 = saved with a plain link: the file cannot be checked
    if (!(e.ik === key || (e.tt && e.tt === title && e.site === site))) continue;
    try {
      const [d] = await chrome.downloads.search({ id: e.id });
      if (d && d.state === 'complete' && d.exists !== false) return { title: e.title || title, time: e.time || 0, size: e.size || 0 };
    } catch (x) { /* cannot check: treat as not downloaded */ }
  }
  return null;
}

const autoSubSent = new Set();

// V73: pick + verify the subtitles that go with a video. For every wanted language the candidates are tried in order:
// language known from the page / file name first, then unknown ones. Each file is READ first: preview (thumbnail) lists and files in another language are skipped and the next one is tried.
// If nothing matches: "any" saves the best other language, "none" saves nothing. If no file could be read at all, the old way (downloader page) is used as the last step.
async function autoSubChain(tabId, rec, list, pref) {
  const items = [];
  for (const sb of list) {
    if (!sb || isThumbSub(sb.url) || autoSubSent.has(tabId + '|' + sb.key)) continue;
    items.push({ sb, lang: subLangGuess(sb), forced: subInfo(sb.url).forced || /forced/i.test(sb.label || '') });
    autoSubSent.add(tabId + '|' + sb.key);
  }
  if (!items.length) return;
  const check = S.subCheck !== false, used = new Set(), savedLangs = new Set();
  let readAny = false;
  const ref = (c) => c.sb.ref || rec.pageUrl;
  const tryOne = async (c, want, allowOther) => {
    let text; try { text = await subFetch(tabId, c.sb, ref(c)); readAny = true; } catch (e) { dbg('subs', 'could not read ' + String(c.sb.url).slice(0, 100)); return false; }
    let real = c.lang;
    if (check) {
      if (looksThumbText(text)) { dbg('subs', 'skipped a preview (thumbnail) track: ' + String(c.sb.url).slice(0, 100)); used.add(c); return false; }
      if (!real) real = detectSubLang(text);
      if (want && real && real !== want) return false;
      if (want && !real && !allowOther) return false;
      if (!want && real && savedLangs.has(real)) return false;
    }
    used.add(c); savedLangs.add(real || want || '');
    await subtitleJob(tabId, rec, { ...c.sb, text, lang: real || want || c.sb.lang || '' }, true);
    return true;
  };
  const order = (want) => items.filter((c) => !used.has(c)).sort((x, y) => ((x.forced ? 1 : 0) - (y.forced ? 1 : 0)) || ((x.lang === want ? 0 : x.lang ? 2 : 1) - (y.lang === want ? 0 : y.lang ? 2 : 1)));
  let saved = 0;
  if (pref.length) {
    for (const w of pref) {
      if (saved >= 5) break;
      let ok = false;
      for (const c of order(w).filter((x) => !x.lang || x.lang === w).slice(0, 8)) { if (await tryOne(c, w, !check)) { ok = true; break; } }
      if (ok) saved++;
    }
    if (!saved && S.subFallback !== 'none') { for (const c of order('').slice(0, 6)) { if (await tryOne(c, '', true)) { saved++; break; } } dbg('subs', saved ? 'wanted language not found, saved another one' : 'wanted language not found'); }
  } else {
    for (const c of order('').slice(0, 12)) { if (saved >= 5) break; if (await tryOne(c, '', true)) saved++; }
  }
  if (!saved && !readAny && items[0]) await subtitleJob(tabId, rec, items[0].sb, true); // nothing could be read here: the downloader page tries with the page headers
}
// V77: the helper tab of a batch episode used to be closed 15s after the job was queued. The video needs minutes (a subtitle only seconds), and some browsers (Quetta)
// refuse the first token, so the player in that tab may still have to ask again. It is now kept until its job is finished (or 3h at most).
function closeSniffTab(tab, key) {
  const t0 = Date.now();
  const tick = () => {
    const act = jobQueue.some((j) => j.key === key) || offJobs.has(key) || ['queued', 'run'].includes(jobState[key]);
    if (Date.now() - t0 > 3 * 3600 * 1000 || (!act && Date.now() - t0 > 20000)) { chrome.tabs.remove(tab).catch(() => {}); delete uiTabFor[tab]; return; }
    setTimeout(tick, 5000);
  };
  setTimeout(tick, 15000);
}
async function startJob(msg) {
  await settingsReady;
  const { key, variant, srt } = msg;
  let rec = await getRec(msg.tabId); // the page the stream was seen on (for a sniffed episode: the helper tab)
  const tabId = msg.uiTab != null ? msg.uiTab : msg.tabId; // ...but progress goes to the page the user is looking at
  let item = rec.items.find((i) => i.key === key);
  if (!item && msg.itemUrl) item = { url: msg.itemUrl, kind: msg.kind || 'video', key: 'ext|' + msg.itemUrl, ref: msg.ref || '' };
  if (!item) return;
  if (msg.title) rec = { ...rec, title: msg.title, book: msg.book || '', chapter: msg.chapter || '', index: msg.index || '' }; // per-episode title (batch mode)
  const ref = item.ref || rec.pageUrl || '';
  if (item.info && item.info.protected) return sendJob(tabId, item.key, 'error', 0, 'DRM protected - cannot be downloaded');
  jobMeta[item.key] = { title: nameVars(rec, item).title, kind: item.kind };
  trackJob(item.key, { title: nameVars(rec, item).title, kind: item.kind, state: 'run', pct: 0, text: 'Starting...', tab: tabId, started: Date.now(), epUrl: msg.epUrl || '' });
  // subtitles found on the page go next to the video with the same base name
  // V68 (batch only, wrapped): subtitles that were written in the episode page itself, same language rule as below
  // V73: subtitles are chosen by a chain (metadata -> file name -> address -> the text inside the file) and checked before saving
  if (S.autoSubs && !msg.noSubs && item.kind !== 'subtitle') {
    try {
      const pref = (S.subLangs || '').toLowerCase().split(/[\s,]+/).filter(Boolean).map(normLang);
      if (msg.extraSubs && msg.extraSubs.length) autoSubChain(tabId, rec, msg.extraSubs, pref).catch((e) => dbg('subs', 'page subtitles skipped: ' + (e.message || e)));
      if (!msg.itemUrl || (msg.epUrl && msg.uiTab != null && msg.uiTab !== msg.tabId)) autoSubChain(tabId, rec, rec.items.filter((x) => x.kind === 'subtitle'), pref).catch((e) => dbg('subs', 'subtitles skipped: ' + (e.message || e)));
    } catch (e) { dbg('subs', 'subtitles skipped: ' + (e.message || e)); }
  }

  if (item.kind === 'video' || item.kind === 'audio') {
    const slow = () => openJob({ url: item.url, mode: 'file', title: nameVars(rec, item).title, ref, tab: tabId, key: item.key, ext: guessExt(item), kind: item.kind, book: rec.book || '', chapter: rec.chapter || '', index: rec.index || '' });
    return directDownload(tabId, rec, item, slow);
  }
  if (item.kind === 'subtitle') return subtitleJob(tabId, rec, item, msg.fmt || (srt === false ? 'orig' : true));
  // hls / dash
  sendJob(tabId, item.key, 'run', 0, 'Opening downloader...');
  openJob({
    url: item.url, mode: item.kind === 'dash' ? 'dash' : 'video', title: nameVars(rec, item).title,
    ref, tab: tabId, key: item.key, variant: variant || 'best', cap: msg.cap || '', auto: msg.manual ? '' : '1', manual: msg.manual ? '1' : '', clip: msg.clip || '', kind: item.kind, book: rec.book || '', chapter: rec.chapter || '', index: rec.index || ''
  });
}

// ---------- episode batch: find the video inside each episode page and queue it ----------
const EP_MEDIA_RE = /https?:\/\/[^"'\s<>\\)]+?\.(?:m3u8|mpd|mp4|webm|mkv)(?:\?[^"'\s<>\\)]*)?/gi;
const EP_ATTR_RE = /(?:src|file|source|url|video_url|videoUrl|hls|hlsUrl|stream|stream_url|streamUrl|contentUrl|manifest|playlist|data-src|data-video|data-hls|data-url)["']?\s*[:=]\s*["']([^"']+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^"']*)?)["']/gi;
function mediaFromHtml(html, base) {
  html = html.replace(/\\\//g, '/').replace(/\\u002F/gi, '/').replace(/&amp;/g, '&').replace(/&#x2F;/gi, '/');
  const seen = new Set(), out = [];
  const add = (u) => {
    let x; try { x = absUrl(u, base); } catch (e) { return; }
    if (!/^https?:/i.test(x) || seen.has(x) || (S.blockAds && isAdUrl(x))) return;
    if (/(preview|trailer|teaser|sample|\/ads?\/)/i.test(x)) return;
    seen.add(x); out.push(x);
  };
  (html.match(EP_MEDIA_RE) || []).forEach(add);
  let m; EP_ATTR_RE.lastIndex = 0;
  while ((m = EP_ATTR_RE.exec(html))) add(m[1]);
  const rank = (u) => { const e = extOf(u); return e === 'm3u8' ? 0 : e === 'mpd' ? 1 : 2; };
  return out.sort((a, b) => rank(a) - rank(b));
}
// V68: subtitles written in an episode page (<track> tags or .vtt/.srt/.ass links), so the batch can save them next to the video like it does for the player-tab path
function subsFromHtml(html, base) {
  html = String(html || '').replace(/\\\//g, '/').replace(/&amp;/g, '&');
  const out = [], seen = new Set();
  const add = (u, lang, label) => {
    let x; try { x = absUrl(u, base); } catch (e) { return; }
    if (!/^https?:/i.test(x) || seen.has(x) || isThumbSub(x) || (S.blockAds && isAdUrl(x))) return;
    seen.add(x); out.push({ url: x, lang: lang || '', label: label || '', kind: 'subtitle', key: 'ext|' + x, ref: base });
  };
  html.replace(/<track\b[^>]*>/gi, (t) => {
    const g = (n) => { const m = new RegExp('\\b' + n + '\\s*=\\s*["\']([^"\']*)["\']', 'i').exec(t); return m ? m[1] : ''; };
    const k = g('kind'); if (k && !/subtitles|captions/i.test(k)) return '';
    if (g('src')) add(g('src'), g('srclang'), g('label'));
    return '';
  });
  (html.match(/https?:\/\/[^"'\s<>\\)]+?\.(?:vtt|srt|ass)(?:\?[^"'\s<>\\)]*)?/gi) || []).forEach((u) => add(u));
  return out.slice(0, 12);
}
function iframesFromHtml(html, base) {
  const out = [];
  html.replace(/<iframe\b[^>]*?\b(?:data-lazy-src|data-src|src)=["']([^"']+)["']/gi, (_, u) => { try { const x = absUrl(u.replace(/&amp;/g, '&'), base); if (/^https?:/i.test(x) && !isAdUrl(x)) out.push(x); } catch (e) { /* skip */ } return ''; });
  return out;
}
const batchState = {}; // tabId -> {stop}

// Many sites (animixplay and the like) load their player with JavaScript, so the episode HTML has no video link.
// Fallback: open the episode in a background tab, let the network sniffer see the stream, press play, then download.
let sniffShown = 0; // only one helper tab at a time is brought to the front (several at once would fight for focus)
// Fallbacks that do not depend on the content script or on webRequest (some browsers, e.g. Quetta, deliver neither reliably to a background tab):
// press play from outside, and read the stream address straight out of the page (video element + the resources the page loaded).
async function forcePlay(tabId, attempt = 0) {
  try {
    await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, args: [attempt], func: (n) => {
      const fire = (el) => { try {
        const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
        const o = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, isPrimary: true };
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach((t) => el.dispatchEvent(t.startsWith('pointer') && window.PointerEvent ? new PointerEvent(t, o) : new MouseEvent(t, o)));
      } catch (e) { /* ignore */ } };
      const shown = (b) => { const r = b.getBoundingClientRect(); return r.width > 4 && r.height > 4; };
      try { document.querySelectorAll('video').forEach((v) => { v.muted = true; const p = v.play(); if (p && p.catch) p.catch(() => {}); }); } catch (e) { /* ignore */ }
      ['.player-btn', '#player .player-btn', '.jw-icon-display', '.vjs-big-play-button', '.plyr__control--overlaid', '.play-btn', '.play-button', '.btn-play', '.fp-play', '[aria-label="Play"]', '[title="Play"]', '.art-state', '.dplayer-play-icon', '[class*="big-play" i]', '[class*="play-icon" i]', '[class*="player-btn" i]']
        .forEach((q) => { try { document.querySelectorAll(q).forEach((b) => { if (shown(b)) { fire(b); try { b.click(); } catch (e) { /* ignore */ } } }); } catch (e) { /* ignore */ } });
      if (window !== window.top) return;
      // Many sites (animixplay and similar) only create the player iframe after a tap on the big player box / poster: tap the biggest 16:9-ish box
      try {
        const bad = /(^|[\s_-])(ad|ads|banner|comment|disqus|sponsor|popup|modal)([\s_-]|$)/i;
        const c = [...document.querySelectorAll('[id*="player" i],[class*="player" i],[class*="poster" i],[class*="overlay" i],[class*="cover" i],[id*="video" i],[class*="video" i],[class*="embed" i],[class*="watch" i],iframe')]
          .filter((e) => { const r = e.getBoundingClientRect(), a = r.width / (r.height || 1); return r.width >= 180 && r.height >= 100 && a > 1.2 && a < 2.6 && !bad.test((e.id || '') + ' ' + String(e.className || '')); })
          .sort((x, y) => (y.clientWidth * y.clientHeight) - (x.clientWidth * x.clientHeight)).slice(0, 4);
        if (c.length) {
          const t = c[n % c.length];
          try { t.scrollIntoView({ block: 'center' }); } catch (e) { /* ignore */ }
          fire(t);
          const r = t.getBoundingClientRect(), u = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
          if (u && u !== t) fire(u);
        }
        document.querySelectorAll('button,div,span,a,i').forEach((b) => { // a visible "Play" style control (never a real link to another page)
          if (b.children.length > 2) return;
          const t = (b.textContent || '').trim();
          if (t.length > 14 || !/^(\u25B6|play|watch|watch now|play now|start)/i.test(t) || !shown(b)) return;
          const h = b.tagName === 'A' ? (b.getAttribute('href') || '') : '#';
          if (h && !/^(#|javascript)/i.test(h)) return;
          fire(b);
        });
      } catch (e) { /* ignore */ }
    } });
  } catch (e) { /* page not scriptable */ }
}
async function probeTab(tabId, ref) {
  try {
    const res = await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, func: () => {
      const out = [];
      try { document.querySelectorAll('video,audio,source').forEach((v) => { if (v.currentSrc) out.push(v.currentSrc); if (v.src) out.push(v.src); }); } catch (e) { /* ignore */ }
      try { performance.getEntriesByType('resource').forEach((e) => out.push(e.name)); } catch (e) { /* ignore */ }
      return out;
    } });
    const seen = new Set(), list = [];
    res.forEach((r) => (r.result || []).forEach((u) => {
      if (!/^https?:/i.test(u) || seen.has(u)) return; seen.add(u);
      if (!/\.(m3u8|mpd|mp4|webm|mkv)(\?|#|$)/i.test(u) || (S.blockAds && isAdUrl(u)) || /(preview|trailer|teaser|sample|\/ads?\/)/i.test(u)) return;
      list.push(u);
    }));
    const rank = (u) => { const e = extOf(u); return e === 'm3u8' ? 0 : e === 'mpd' ? 1 : 2; };
    list.sort((a, b) => rank(a) - rank(b)); // stable: the first playlist the page asked for is the master one
    return list[0] ? { url: list[0], kind: extOf(list[0]) === 'm3u8' ? 'hls' : extOf(list[0]) === 'mpd' ? 'dash' : 'video', ref } : null;
  } catch (e) { return null; }
}
async function sniffEpisodeTab(mainTab, ep, say, label, opt = {}) {
  let tab;
  try { tab = await chrome.tabs.create({ url: ep.url, active: false }); }
  catch (e) { try { tab = await chrome.tabs.create({ url: ep.url, active: true }); } catch (e2) { return null; } } // V65: some browsers refuse a hidden tab
  uiTabFor[tab.id] = mainTab;
  const limit = (S.sniffSeconds || 25) * 1000 * (opt.mul || 1), showAt = opt.showAt || 4000, t0 = Date.now();
  let firstHit = 0, plays = 0, shown = false, probes = 0, reloaded = false;
  const rank = (i) => (i.kind === 'hls' ? 0 : i.kind === 'dash' ? 1 : 2);
  try {
    while (Date.now() - t0 < limit) {
      if (batchState[mainTab] && batchState[mainTab].stop) break;
      await new Promise((r) => setTimeout(r, 800));
      const el = Date.now() - t0;
      if (!firstHit && el > 1500 && el > plays * 3500) { plays++; chrome.tabs.sendMessage(tab.id, { type: 'try-play' }).catch(() => {}); forcePlay(tab.id, plays); }
      if (!firstHit && !shown && el > showAt && sniffShown === 0) { // some players refuse to start in a hidden tab: show it for the rest of the wait
        shown = true; sniffShown++; chrome.tabs.update(tab.id, { active: true }).catch(() => {});
      }
      say(`${label}: waiting for the player... ${Math.round(el / 1000)}s`);
      const rec = await getRec(tab.id);
      const c = rec.items.filter((i) => !i.hidden && ['hls', 'dash', 'video'].includes(i.kind) && !(i.info && i.info.protected));
      if (!c.length) {
        if (el > 2500 && el > probes * 2400) { // the sniffer saw nothing: read it from the page itself
          probes++;
          const pr = await probeTab(tab.id, ep.url);
          if (pr) { dbg('batch', `${ep.label}: stream read from the page (${pr.kind})`); return { tab: tab.id, direct: pr, front: shown }; }
        }
        continue;
      }
      if (!firstHit) firstHit = Date.now();
      if (Date.now() - firstHit < 2500) continue; // give playlist analysis and subtitles a moment to arrive
      // V62: never take a banner clip; when a long stream exists, short plain videos (ads) are dropped; a master playlist (it has the quality list)
      // wins over one of its variant playlists that has the same length (the old order could pick one single variant, e.g. the lowest quality)
      let cc = c.filter((i) => !/\/bnr\/\d/i.test(i.url || ''));
      if (!cc.length) cc = c;
      const durOf = (i) => (i.info && i.info.dur) || 0;
      const longest = Math.max(0, ...cc.map(durOf));
      if (longest >= 300) cc = cc.filter((i) => !(i.kind === 'video' && durOf(i) < 120));
      cc.sort((a, b) => ((a.refused ? 1 : 0) - (b.refused ? 1 : 0)) || (Math.round(durOf(b) / 5) - Math.round(durOf(a) / 5)) || ((b.info && b.info.master ? 1 : 0) - (a.info && a.info.master ? 1 : 0)) || rank(a) - rank(b) || (b.size || 0) - (a.size || 0));
      if (cc[0].refused && !reloaded && Date.now() - firstHit > 4000) { reloaded = true; firstHit = Date.now(); dbg('batch', `${label}: only a refused (403) stream, reloading the player for a new one`); chrome.tabs.reload(tab.id).catch(() => {}); continue; } // V77 fallback
      if (cc[0].refused && Date.now() - firstHit < 9000) continue; // V76: only a refused (403) stream so far: give the player time to ask again with a new token
      return { tab: tab.id, key: cc[0].key, front: shown };
    }
  } finally {
    if (shown) { sniffShown--; chrome.tabs.update(mainTab, { active: true }).catch(() => {}); } // back to the page that shows the batch
  }
  chrome.tabs.remove(tab.id).catch(() => {});
  delete uiTabFor[tab.id];
  return null;
}
// unfinished episodes survive a browser restart (storage.local), like the chapter queue
async function setBatchPend(host, eps, cap, miss) {
  try {
    const r = (await chrome.storage.local.get('mgBatchPend')).mgBatchPend || {};
    if (eps && eps.length) r[host] = { eps, cap: cap || '', miss: miss || '', t: Date.now() }; else delete r[host];
    await chrome.storage.local.set({ mgBatchPend: r });
  } catch (e) { /* optional */ }
}
// V67: site memory. For each site we count which way found the video: the page HTML, or the player tab (and whether the tab had to be shown).
// Next time the way that worked is tried first. Only a hint: the old order is still there as the second / third try, and it is switched off by "siteMemory".
let siteMemQ = Promise.resolve();
async function siteMemGet(host) {
  try { if (!host || S.siteMemory === false) return null; return ((await chrome.storage.local.get('mgSiteMem')).mgSiteMem || {})[host] || null; } catch (e) { return null; }
}
function siteMemNote(host, path, front) {
  if (!host || S.siteMemory === false) return;
  siteMemQ = siteMemQ.then(async () => {
    const all = (await chrome.storage.local.get('mgSiteMem')).mgSiteMem || {};
    const m = all[host] || { html: 0, sniff: 0, front: 0 };
    m[path] = Math.min(50, (m[path] || 0) + 1);
    if (front) m.front = Math.min(50, (m.front || 0) + 1);
    m.t = Date.now(); all[host] = m;
    const hs = Object.keys(all);
    if (hs.length > 60) hs.sort((a, b) => (all[a].t || 0) - (all[b].t || 0)).slice(0, hs.length - 60).forEach((h) => delete all[h]);
    await chrome.storage.local.set({ mgSiteMem: all });
  }).catch(() => { /* optional */ });
}
// Looking for a stream by opening the episode in a tab needs the tab to start its player (some players only start when the tab is
// visible), so two of these at the same time starve each other. They take turns; episodes whose video is in the page HTML still run in parallel.
let sniffQ = Promise.resolve();
function sniffSerial(fn) { const run = sniffQ.then(fn, fn); sniffQ = run.catch(() => {}); return run; }

// ---------- batch live state (storage.local): the page panel AND the Downloads page read this, so progress, the quality question and the
// per-episode status are visible even where tab messages do not reach the page (the messages are still sent as well) ----------
let liveSt = null, liveChain = Promise.resolve();
function liveSet(patch, ep) {
  if (!liveSt) return;
  Object.assign(liveSt, patch || {});
  if (ep) liveSt.eps[ep[0]] = { ...(liveSt.eps[ep[0]] || {}), ...ep[1] };
  liveSt.t = Date.now();
  const snap = JSON.parse(JSON.stringify(liveSt));
  liveChain = liveChain.then(() => chrome.storage.local.set({ mgBatchLive: snap })).catch(() => {});
}
// a batch that was running when the browser stopped the extension: say so (Resume brings back the unfinished episodes)
chrome.storage.local.get('mgBatchLive').then((r) => {
  const b = r.mgBatchLive;
  if (b && b.running && !liveSt) { b.running = false; b.ask = null; b.interrupted = true; b.text = 'The batch was interrupted (the browser stopped the extension). Press Resume to continue with the unfinished episodes.'; chrome.storage.local.set({ mgBatchLive: b }).catch(() => {}); }
}).catch(() => {});

// ---------- batch quality: one quality for the whole batch; what to do for an episode that does not have it ----------
const askWait = {}; // question id -> resolve (answer comes from the page panel: batch-ask-answer)
const MISS_MODES = ['near', 'low', 'high', 'ask', 'skip'];
// st = { tabId, cap, mode, chain }. Returns { cap, note } to download, or { skip: true, why } to leave the episode out.
async function batchChoose(st, ep, url, kind, ref) {
  const want = parseInt(st.cap, 10) || 0;
  if (!want || kind !== 'hls') return { cap: st.cap };   // Best, or not a stream with qualities: nothing to decide
  let heights = [];
  try {
    const text = await fetchText(url, ref, 12000);
    if (/#EXT-X-STREAM-INF/.test(text)) heights = hlsInfo(text, url).variants.map((v) => qh(v));
  } catch (e) { return { cap: st.cap }; }                // cannot read it here: the downloader decides, as before
  const avail = Array.from(new Set(heights.filter((x) => x > 0))).sort((a, b) => b - a);
  if (!avail.length) return { cap: st.cap };
  const list = avail.map((x) => x + 'p').join(', ');
  let r = pickHeight(avail, want, st.mode === 'ask' ? 'near' : st.mode);
  if (r.exact) return { cap: st.cap, note: `${r.h}p` };
  if (st.mode === 'ask') {
    const ans = await askQuality(st, ep, want, avail, r.h);
    if (ans === 'again') return batchChoose(st, ep, url, kind, ref);   // "same for the rest" was ticked while this one waited
    if (ans && ans.h === 0) r = { h: 0, skip: true };
    else if (ans && ans.h) r = { h: ans.h, exact: false };
    // no answer in time: the closest one (r) is used
  }
  if (r.skip) return { skip: true, why: `${want}p is not available in this episode (it has ${list}). Skipped by your choice - change "If missing" and use Retry failed.` };
  dbg('batch', `${ep.label}: ${want}p missing (has ${list}), using ${r.h}p`);
  return { cap: String(r.h), note: `${r.h}p (${want}p missing)` };
}
function askQuality(st, ep, want, avail, def) {
  st.chain = (st.chain || Promise.resolve()).catch(() => {}).then(async () => {
    if (st.mode !== 'ask') return 'again';
    const id = 'q' + Date.now() + Math.random().toString(36).slice(2, 6);
    chrome.tabs.update(st.tabId, { active: true }).catch(() => {}); // the question is in the page you started the batch from
    liveSet({ ask: { id, label: ep.label, want, avail, def }, text: `${ep.label}: ${want}p is not available - choose a quality` });
    return new Promise((res) => {
      askWait[id] = res;
      setTimeout(() => { if (askWait[id]) { delete askWait[id]; res(null); } }, 90000);
      chrome.tabs.sendMessage(st.tabId, { type: 'batch-ask', id, label: ep.label, want, avail, def }, { frameId: 0 })
        .catch(() => { if (askWait[id]) { delete askWait[id]; res(null); } });
    }).then((ans) => {
      liveSet({ ask: null });
      if (ans && ans.mode && MISS_MODES.includes(ans.mode)) st.mode = ans.mode;
      return ans;
    });
  });
  return st.chain;
}

// V66: a failed episode is sorted into a reason (plain words + what to do next). Only used for messages; it never changes what the batch does.
function failInfo(why, isQuality) {
  const w = String(why || '');
  if (isQuality) return { cat: 'quality', text: 'The quality you chose is not available', act: 'Retry and pick "closest" or "lower" for missing qualities' };
  const m = /HTTP (\d{3})/.exec(w), code = m ? +m[1] : 0;
  if (/drm|widevine|encrypted|license/i.test(w)) return { cat: 'drm', text: 'The video is protected (DRM)', act: 'It cannot be saved by this extension' };
  if (code === 403 || code === 401 || code === 451) return { cat: 'blocked', text: 'The site refused the request', act: 'Open the site in a tab and log in / solve its check, then Retry' };
  if (code === 429 || code === 503) return { cat: 'blocked', text: 'The site asked us to slow down', act: 'Wait a few minutes, raise "Delay between requests" in Settings, then Retry' };
  if (code === 404 || code === 410) return { cat: 'page', text: 'The episode page was not found', act: 'Check that the episode still exists on the site' };
  if (/did not open|abort|failed to fetch|network|timeout|timed out/i.test(w)) return { cat: 'network', text: 'The episode page did not load (connection problem)', act: 'Check your internet connection, then Retry' };
  if (/no video stream appeared/i.test(w)) return { cat: 'nostream', text: 'No video was found in the player', act: 'Retry; if it repeats, raise the player wait time in Settings' };
  return { cat: 'other', text: 'The video could not be found', act: 'Retry; use Diagnose in the panel if it repeats' };
}

async function batchEpisodes(tabId, episodes, cap, missPolicy) {
  await settingsReady;
  batchState[tabId] = { stop: false };
  const keepAlive = setInterval(() => { try { chrome.runtime.getPlatformInfo(); } catch (e) { /* ignore */ } }, 20000);
  liveSt = { tab: tabId, host: '', total: episodes.length, idx: 0, ok: 0, cap, mode: missPolicy || 'near', running: true, interrupted: false, text: 'Starting the batch...', title: (episodes[0] && episodes[0].book) || '', started: Date.now(), ask: null, eps: {} };
  episodes.forEach((e, i) => { liveSt.eps[e.url] = { label: e.label, n: i + 1, st: 'wait' }; });
  const say = (text) => { liveSet({ text }); chrome.tabs.sendMessage(tabId, { type: 'batch-status', text }, { frameId: 0 }).catch(() => {}); };
  const ep$ = (url, state, extra = {}) => { liveSet({}, [url, { st: state, ...extra }]); chrome.tabs.sendMessage(tabId, { type: 'batch-ep', url, state, ...extra }, { frameId: 0 }).catch(() => {}); };
  const stopped = () => !!(batchState[tabId] && batchState[tabId].stop);
  let host = ''; try { host = new URL(episodes[0].url).hostname; } catch (e) { /* ignore */ }
  liveSet({ host });
  const total = episodes.length, missEps = [];
  let ok = 0, skipped = 0;
  const noq = [];
  const qst = { tabId, cap, mode: MISS_MODES.includes(missPolicy) ? missPolicy : 'near', chain: Promise.resolve() };
  const doneSet = new Set(S.skipDone ? ((await chrome.storage.local.get('mgDone')).mgDone || []) : []);
  episodes.forEach((e) => ep$(e.url, doneSet.has(e.url) && total > 1 ? 'skip' : 'wait'));
  let left = episodes.map((e) => e.url);
  const keep = () => setBatchPend(host, episodes.filter((e) => left.includes(e.url)), cap, qst.mode);
  await keep();
  const jitter = () => (S.requestDelayMs ? new Promise((r) => setTimeout(r, S.requestDelayMs * (0.7 + Math.random() * 0.6))) : null); // not a fixed beat: looks less like a bot

  // find the video of ONE episode and queue its download. true = queued
  async function one(ep, i, opt) {
    const lab = `Episode ${i + 1}/${total}`;
    liveSet({ idx: i + 1 });
    say(`${lab}: looking for video...`);
    ep$(ep.url, 'find');
    const mem = opt.mul ? null : await siteMemGet(host); // V67: only the first pass; the longer passes work as before
    const memSkip = !!(mem && (mem.sniff || 0) >= 2 && !mem.html), memFront = !!(mem && (mem.front || 0) >= 2);
    if (memSkip) dbg('batch', `${ep.label}: remembered - this site needs the player tab, page read skipped (first pass)`);
    let found = null, ref = ep.url;
    if (/\.(m3u8|mpd|mp4|webm|mkv)(\?|#|$)/i.test(ep.url)) { found = ep.url; ref = ep.ref || ''; } // a pasted direct link: nothing to look for
    else if (!opt.skipHtml && !memSkip) {
      try {
        const html = await fetchText(ep.url, ep.url, 15000);
        let list = mediaFromHtml(html, ep.url);
        try { ep._subs = subsFromHtml(html, ep.url); } catch (e) { ep._subs = []; } // V68
        if (!list.length) { // one level of player iframes
          for (const fr of iframesFromHtml(html, ep.url).slice(0, 4)) {
            try {
              const fh = await fetchText(fr, ep.url, 15000); list = mediaFromHtml(fh, fr);
              if (list.length) { ref = fr; try { ep._subs = (ep._subs || []).concat(subsFromHtml(fh, fr)); } catch (e) { /* optional */ } break; }
            } catch (e) { /* next iframe */ }
          }
        }
        found = list[0] || null;
        if (found) siteMemNote(host, 'html', false);
      } catch (e) { ep._why = 'the episode page did not open (' + String((e && e.message) || e).slice(0, 60) + ')'; }
    }
    const job = { title: ep.title || ep.label, book: ep.book || '', chapter: ep.label, index: i + 1, variant: 'best', cap, epUrl: ep.url };
    if (!found) {
      ep$(ep.url, 'sniff');
      const s = await sniffSerial(() => sniffEpisodeTab(tabId, ep, say, lab, memFront && !opt.showAt ? { ...opt, showAt: 1500 } : opt));
      if (s) siteMemNote(host, 'sniff', !!s.front);
      if (s && s.direct) {
        dbg('batch', `${ep.label}: found in the opened page${opt.mul ? ' (second try)' : ''}`);
        const q = await batchChoose(qst, ep, s.direct.url, s.direct.kind, s.direct.ref);
        dbg('batch', `${ep.label}: quality step done, queueing`);
        if (q.skip) { chrome.tabs.remove(s.tab).catch(() => {}); delete uiTabFor[s.tab]; ep._why = q.why; ep._noq = true; return false; }
        job.cap = q.cap;
        ep$(ep.url, 'queued', { key: 'ext|' + s.direct.url, q: q.note || '' });
        await startJob({ ...job, tabId: s.tab, uiTab: tabId, itemUrl: s.direct.url, kind: s.direct.kind, ref: s.direct.ref });
        closeSniffTab(s.tab, 'ext|' + s.direct.url);
        return true;
      }
      if (s) {
        dbg('batch', `${ep.label}: found by sniffing the page${opt.mul ? ' (second try)' : ''}`);
        let q = { cap };
        try {
          const srec = await getRec(s.tab), sit = srec.items.find((x) => x.key === s.key);
          if (sit) q = await batchChoose(qst, ep, sit.url, sit.kind, sit.ref || srec.pageUrl || '');
        } catch (e) { /* keep the chosen quality as it is */ }
        if (q.skip) { chrome.tabs.remove(s.tab).catch(() => {}); delete uiTabFor[s.tab]; ep._why = q.why; ep._noq = true; return false; }
        job.cap = q.cap;
        ep$(ep.url, 'queued', { key: s.key, q: q.note || '' });
        await startJob({ ...job, tabId: s.tab, uiTab: tabId, key: s.key });
        closeSniffTab(s.tab, s.key);
        return true;
      }
      ep._why = (ep._why ? ep._why + '; ' : '') + 'no video stream appeared in ' + (S.sniffSeconds || 25) + 's while the player was open (raise the wait in Settings, or the site hides it)';
      return false;
    }
    const kind = extOf(found) === 'm3u8' ? 'hls' : extOf(found) === 'mpd' ? 'dash' : 'video';
    dbg('batch', `${ep.label}: ${kind} ${found}`);
    const q = await batchChoose(qst, ep, found, kind, ref);
    if (q.skip) { ep._why = q.why; ep._noq = true; return false; }
    job.cap = q.cap;
    ep$(ep.url, 'queued', { key: 'ext|' + found, q: q.note || '' });
    await startJob({ ...job, tabId, itemUrl: found, kind, ref, extraSubs: ep._subs || [] });
    return true;
  }
  const queued = async (ep) => { ok++; liveSet({ ok }); left = left.filter((u) => u !== ep.url); await keep(); };
  const failed = (ep, i) => {
    if (ep._noq) { const fi = failInfo(ep._why, true); ep._cat = fi.cat; noq.push(ep); ep$(ep.url, 'noq', { why: ep._why || '', plain: fi.text, cat: fi.cat }); dbg('batch', `${ep.label}: skipped - ${ep._why}`); return; }
    const fi = failInfo(ep._why, false); ep._cat = fi.cat;
    missEps.push({ ep, i }); ep$(ep.url, 'miss', { why: ep._why || '', plain: fi.text, cat: fi.cat }); dbg('batch', `${ep.label}: no video found in ${String(ep.url).split('?')[0]}${ep._why ? ' - ' + ep._why : ''}`);
  };

  // several episodes are searched at the same time (the downloads themselves still follow "Stream jobs at the same time")
  const par = Math.max(1, Math.min(4, +S.batchParallel || 1)); // default 1: one episode at a time, in order
  let nextI = 0;
  const worker = async () => {
    while (!stopped()) {
      const i = nextI++;
      if (i >= total) break;
      const ep = episodes[i];
      if (total > 1 && doneSet.has(ep.url)) { skipped++; left = left.filter((u) => u !== ep.url); continue; }
      if (await one(ep, i, {})) await queued(ep); else failed(ep, i);
      await jitter();
    }
  };
  await Promise.all(Array.from({ length: Math.min(par, total) }, worker));

  // second chance for the ones without a video: much longer wait, and the player tab is shown early (some players only start when visible)
  if (!stopped() && S.batchRetryMiss !== false && missEps.length) {
    for (const pass of [{ skipHtml: true, mul: 2, showAt: 3000, n: 'Second' }, { skipHtml: false, mul: 3, showAt: 1500, n: 'Third' }]) { // V65: a third pass (page read again + longest wait + tab in front at once)
      if (stopped() || !missEps.length) break;
      say(`${pass.n} try for ${missEps.length} episode(s): waiting longer...`);
      for (const m of missEps.slice()) {
        if (stopped()) break;
        if (await one(m.ep, m.i, pass)) { missEps.splice(missEps.indexOf(m), 1); await queued(m.ep); }
        else { const fi = failInfo(m.ep._why, false); m.ep._cat = fi.cat; ep$(m.ep.url, 'miss', { why: m.ep._why || '', plain: fi.text, cat: fi.cat }); }
        await jitter();
      }
    }
  }
  clearInterval(keepAlive);
  const wasStopped = stopped();
  delete batchState[tabId];
  const miss = missEps.map((m) => m.ep.label);
  // finished normally: only the episodes without a video stay in "resume"; stopped: everything that was not reached
  if (!wasStopped) left = left.filter((u) => missEps.some((m) => m.ep.url === u) || noq.some((e) => e.url === u));
  await keep();
  // V66: group the failures by reason, each with a suggested action (wrapped: if anything here fails, the batch still ends as before)
  let summary = [];
  try {
    const g = {};
    [...missEps.map((m) => m.ep), ...noq].forEach((e) => { const fi = failInfo(e._why, !!e._noq); (g[fi.cat] = g[fi.cat] || { cat: fi.cat, text: fi.text, act: fi.act, labels: [] }).labels.push(e.label); });
    summary = Object.values(g).map((x) => ({ cat: x.cat, n: x.labels.length, text: x.text, act: x.act, labels: x.labels.slice(0, 6) }));
  } catch (e) { summary = []; }
  liveSet({ running: false, ask: null, stopped: wasStopped, miss: miss.length, noq: noq.length, skipped, ok, summary });
  chrome.tabs.sendMessage(tabId, { type: 'batch-end', stopped: wasStopped, ok, skipped, miss: miss.length, noq: noq.length }, { frameId: 0 }).catch(() => {});
  say(`Episodes: ${ok} queued` + (skipped ? `, ${skipped} skipped (already downloaded)` : '') + (miss.length ? `, ${miss.length} without a video: ${miss.slice(0, 3).join(', ')}${miss.length > 3 ? '...' : ''}` : '') + (noq.length ? `, ${noq.length} skipped (chosen quality missing): ${noq.slice(0, 3).map((e) => e.label).join(', ')}${noq.length > 3 ? '...' : ''}` : ''));
  // V66: one summary notification when a whole batch has been searched (uses the existing "Notify" setting)
  try {
    if (S.notify && !wasStopped && total > 1) {
      const bad = miss.length + noq.length;
      notifyUser('Media Grabber', `${ok} episode(s) queued` + (skipped ? `, ${skipped} already downloaded` : '') + (bad ? `, ${bad} need attention` + (summary[0] ? ' - ' + summary[0].text + '. ' + summary[0].act : '') : '') + '.');
    }
  } catch (e) { /* optional */ }
}

// ---------- messages ----------
function notifyUser(title, message) {
  try { chrome.notifications.create({ type: 'basic', iconUrl: 'icon128.png', title, message }); } catch (e) { /* optional */ }
}
const mangaWaiters = {}; // id -> respond (chapter queue waits for each CBZ to finish)
const mangaTabs = {};    // tabId -> id
chrome.tabs.onRemoved.addListener((tid) => { const id = mangaTabs[tid]; if (!id) return; delete mangaTabs[tid]; const w = mangaWaiters[id]; if (w) { dbgc('manga', 'save-incomplete', 'chapter tab closed before it finished'); delete mangaWaiters[id]; try { w({ ok: false }); } catch (e) { /* closed */ } } });

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  if (!msg || !msg.type) return;
  // extension pages (popup opened as a tab, e.g. on Android) name the page they work on in msg.tabId
  const fromExt = !!(sender.url && sender.url.startsWith(chrome.runtime.getURL('')));
  const tabId = (fromExt && msg.tabId != null) ? msg.tabId : (sender.tab ? sender.tab.id : msg.tabId);

  if (msg.type === 'playlist-text' && sender.tab) { // V79
    plPut(msg.url, String(msg.text || ''));
    const tid = sender.tab.id, k = normKey(msg.url);
    setTimeout(async () => { // an item that was analysed before the text arrived (and failed / has no quality list) is analysed again
      try {
        const rec = await getRec(tid);
        for (const it of rec.items) if ((it.kind === 'hls' || it.kind === 'dash') && normKey(it.url) === k && (!it.info || it.info.error)) analyze(tid, it.key);
        for (const it of rec.items) if (it.kind === 'hls' && it.info && !it.info.error && it.info.master && !it.info.dur) analyze(tid, it.key); // master seen, variant text came later
      } catch (e) { /* ignore */ }
    }, 400);
    return;
  }
  if (msg.type === 'dom-items' && sender.tab) {
    settingsReady.then(() => {
      if (!S.enabled || siteDisabled(sender.tab.url)) return;
      const items = [];
      for (const it of msg.items) {
        const kind = classify(it.url, '', it.kind);
        if (S.blockAds && !/^data:/i.test(it.url) && isAdUrl(it.url)) continue; // V82: data: playlists are never an ad address
        if (kind) items.push({ url: it.url, kind, label: it.label || '', lang: it.lang || '', ref: it.ref || sender.tab.url });
      }
      if (items.length) addItems(sender.tab.id, { title: sender.tab.title, pageUrl: sender.tab.url, top: true }, items);
    });
    return;
  }
  if (msg.type === 'video-info' && sender.tab) {
    patchRec(sender.tab.id, (r) => { r.hint = { ...(r.hint || {}), ...msg.info }; });
    return;
  }
  if (msg.type === 'get-state') {
    Promise.all([settingsReady, getRec(tabId)]).then(([, rec]) => respond({ rec, settings: S }));
    return true;
  }
  if (msg.type === 'download-all') {
    getRec(tabId).then(async (rec) => {
      for (const it of rec.items) {
        if (it.hidden || (it.info && it.info.protected)) continue;
        if (msg.subs === false && it.kind === 'subtitle') continue;
        await startJob({ tabId, key: it.key, variant: 'best', srt: true, noSubs: true });
      }
    });
    return;
  }
  if (msg.type === 'dup-check') { // V71: was this item already saved AND is the file still on the disk? (answer only, nothing is started)
    findDuplicate(tabId, msg.key).then((d) => respond({ dup: d }), () => respond({ dup: null }));
    return true;
  }
  if (msg.type === 'subs-all') { subtitlesAll(tabId, msg.fmt).then((n) => respond({ n }), () => respond({ n: 0 })); return true; }
  if (msg.type === 'subs-capture') { subtitlesFromPlayer(tabId, msg.fmt).then(respond, (e) => respond({ n: 0, found: 0, err: String((e && e.message) || e) })); return true; }
  if (msg.type === 'subs-captured') { subtitlesCaptured(tabId, msg.tracks, msg.fmt).then((n) => respond({ n }), () => respond({ n: 0 })); return true; }
  if (msg.type === 'start-job') { startJob({ ...msg, tabId }); return; }
  if (msg.type === 'batch-episodes') { batchEpisodes(tabId, (msg.episodes || []).slice(0, 300), msg.cap || '', msg.miss || ''); return; }
  if (msg.type === 'batch-ask-answer') { const w = askWait[msg.id]; if (w) { delete askWait[msg.id]; w(msg); } return; }
  if (msg.type === 'follow-check') { checkFollows(true).then(respond, () => respond({ n: 0 })); return true; }
  if (msg.type === 'whoami') { respond({ tab: sender.tab ? sender.tab.id : null }); return; }
  if (msg.type === 'batch-resume') { // from the Downloads page: continue the unfinished episodes of the last batch
    (async () => {
      const b = (await chrome.storage.local.get('mgBatchLive')).mgBatchLive;
      const pend = ((await chrome.storage.local.get('mgBatchPend')).mgBatchPend || {})[b && b.host];
      if (b && !batchState[b.tab] && pend && pend.eps && pend.eps.length) batchEpisodes(b.tab, pend.eps.slice(0, 300), pend.cap || '', pend.miss || '');
    })();
    return;
  }
  if (msg.type === 'batch-stop') { if (batchState[tabId]) batchState[tabId].stop = true; return; }
  if (msg.type === 'grab-pages') { // MGB S55: native panel manga block (CBZ / ZIP / PDF / EPUB / Read / Pick pages)
    chrome.tabs.sendMessage(tabId, { type: 'grab-pages', how: msg.how || 'cbz' }, { frameId: 0 }).catch(() => {});
    return;
  }
  if (msg.type === 'open-panel' || msg.type === 'toggle-panel') {
    chrome.tabs.sendMessage(tabId, { type: msg.type }, { frameId: 0 }).catch(() => {});
    return;
  }
  if (msg.type === 'page-fetch-bin') { pageFetchBin(+msg.tab, msg.url, msg.range).then((b64) => respond({ ok: true, b64 }), (e) => respond({ ok: false, err: String((e && e.message) || e) })); return true; } // V74n
  if (msg.type === 'page-fetch-text') { pageFetch(+msg.tab, msg.url, 12000).then((text) => respond({ ok: true, text }), (e) => respond({ ok: false, err: String((e && e.message) || e) })); return true; } // V73b: downloader asks the page when the server refuses the extension
  if (msg.type === 'off-rpc') { offRpc(msg).then(respond, (e) => respond({ error: String((e && e.message) || e) })); return true; }
  if (msg.type === 'job-retry') {
    chrome.storage.session.get('mgJobs').then((r) => { const j = (r.mgJobs || {})[msg.key]; if (j && j.params) openJob({ ...j.params, _r: 0 }); });
    return;
  }
  if (msg.type === 'job-update') { // from downloader page
    if (msg.key) jobState[msg.key] = msg.state;
    if (msg.state === 'done' || msg.state === 'error') freeJob(msg.key);
    if (msg.state === 'done' && S.notify && !running.size && !jobQueue.length) notifyUser('Media Grabber', 'Download finished');
    sendJob(msg.tab, msg.key, msg.state, msg.pct, msg.text);
    return;
  }
  if (msg.type === 'job-cancel') {
    chrome.runtime.sendMessage({ type: 'cancel-job', key: msg.key }).catch(() => {});
    const qi = jobQueue.findIndex((j) => j.key === msg.key); // D6: a queued job that has not started yet is dropped
    if (qi >= 0) { jobQueue.splice(qi, 1); delete jobState[msg.key]; trackJob(msg.key, { state: 'error', text: 'Cancelled' }); }
    for (const [id, a] of active) if (a.key === msg.key) { chrome.downloads.cancel(id).catch(() => {}); active.delete(id); trackJob(msg.key, { state: 'error', text: 'Cancelled' }); }
    return;
  }
  if (msg.type === 'open-player') { chrome.tabs.create({ url: chrome.runtime.getURL('player.html') + '?' + new URLSearchParams({ url: msg.url || '', title: msg.title || '', ref: msg.ref || '' }) }); return; }
  if (msg.type === 'schedule-job') { addSched(msg.hhmm, msg.job).then(() => respond && respond({ ok: true })).catch(() => respond && respond({ ok: false })); return true; }
  if (msg.type === 'sched-cancel') {
    chrome.alarms.clear('mg-sched-' + msg.id);
    chrome.storage.local.get('mgSched').then((r) => chrome.storage.local.set({ mgSched: (r.mgSched || []).filter((x) => x.id !== msg.id) }));
    return;
  }
  if (msg.type === 'job-stop') { // live recording: stop and save what was recorded
    chrome.runtime.sendMessage({ type: 'stop-job', key: msg.key }).catch(() => {});
    trackJob(msg.key, { text: 'Stopping - saving the recording...' });
    return;
  }
  if (msg.type === 'job-pause') { // from the Queue page: msg.on true = pause, false = resume
    chrome.runtime.sendMessage({ type: 'pause-job', key: msg.key, on: !!msg.on }).catch(() => {});
    for (const [id, a] of active) if (a.key === msg.key) { (msg.on ? chrome.downloads.pause(id) : chrome.downloads.resume(id)).catch(() => {}); a.paused = !!msg.on; }
    trackJob(msg.key, { paused: !!msg.on, text: msg.on ? 'Paused' : 'Resumed' });
    return;
  }
  if (msg.type === 'reanalyze') { analyze(tabId, msg.key); return; }
  if (msg.type === 'jobs-get') {
    chrome.storage.session.get('mgJobs').then((r) => respond(Object.values(r.mgJobs || {}).filter((j) => j.tab === tabId && j.state).map((j) => ({ key: j.key, state: j.state, pct: j.pct, text: j.text, title: j.title, kind: j.kind, paused: !!j.paused, updated: j.updated || 0 })))).catch(() => respond([]));
    return true;
  }
  if (msg.type === 'job-remove') { jobsChain = jobsChain.then(async () => { const r = (await chrome.storage.session.get('mgJobs')).mgJobs || {}; delete r[msg.key]; await chrome.storage.session.set({ mgJobs: r }); }); return; }
  if (msg.type === 'jobs-clear') { jobsChain = jobsChain.then(async () => { const r = (await chrome.storage.session.get('mgJobs')).mgJobs || {}; for (const k of Object.keys(r)) if (r[k].state !== 'run') delete r[k]; await chrome.storage.session.set({ mgJobs: r }); }); return; }
  if (msg.type === 'open-manga') {
    const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
    if (msg.wait) mangaWaiters[id] = respond;
    chrome.storage.session.set({ ['manga_' + id]: { images: msg.images, title: msg.title, ref: msg.ref, auto: msg.auto, autoClose: !!msg.autoClose, book: msg.book || '', chapter: msg.chapter || '', index: msg.index || '', meta: msg.meta || null, task: msg.task || null, expected: msg.expected || 0, tab: sender.tab ? sender.tab.id : null } })
      .then(() => chrome.tabs.create({ url: chrome.runtime.getURL('manga.html') + '?id=' + id, active: !msg.autoClose }))
      .then((t) => { mangaTabs[t.id] = id; })
      .catch(() => { const w = mangaWaiters[id]; if (w) { delete mangaWaiters[id]; w({ ok: false }); } });
    return !!msg.wait;
  }
  if (msg.type === 'site-probe') { // quick facts about a gallery / chapter list (page count, title, chapters) for the on-page button
    (async () => {
      await settingsReady;
      const ad = S.siteAdapters !== false && MG_SITES.match(msg.url);
      if (!ad) return respond({ ok: false });
      const ctx = { text: async (u) => { const r = await fetch(u, { credentials: 'include' }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }, json: async (u) => JSON.parse(await ctx.text(u)) };
      try { respond({ ok: true, name: ad.name, label: ad.label, ...(await ad.probe(msg.url, ctx)) }); }
      catch (e) { respond({ ok: true, name: ad.name, label: ad.label, pages: 0, err: String(e.message || e) }); }
    })();
    return true;
  }
  if (msg.type === 'manga-done') {
    if (msg.missing > 0 || msg.ok === false) dbgc('manga', msg.ok === false ? 'save-incomplete' : 'pages-missing-saved', `chapter done: ok=${msg.ok !== false} missing=${msg.missing || 0}${mangaWaiters[msg.id] ? '' : ' (nobody waiting, auto notice not shown)'}`);
    const w = mangaWaiters[msg.id]; if (w) { delete mangaWaiters[msg.id]; try { w({ ok: msg.ok !== false, missing: msg.missing || 0 }); } catch (e) { /* closed */ } } return;
  }
  if (msg.type === 'notify') { settingsReady.then(() => { if (S.notify) notifyUser(msg.title || 'Media Grabber', msg.text || ''); }); return; }
  if (msg.type === 'open-options') { chrome.runtime.openOptionsPage(); return; }
  if (msg.type === 'clear') {
    chrome.storage.session.remove(tkey(tabId));
    chrome.action.setBadgeText({ tabId, text: '' }).catch(() => {});
    notify(tabId);
    return;
  }
  if (msg.type === 'play-scan') { // players on phones start late: press play three times and rescan at the end
    const to = (m) => chrome.tabs.sendMessage(tabId, m).catch(() => {});
    to({ type: 'try-play' });
    setTimeout(() => to({ type: 'try-play' }), 2200);
    setTimeout(() => to({ type: 'scan' }), 4500);
    return;
  }
  if (msg.type === 'rescan') {
    chrome.tabs.sendMessage(tabId, { type: 'scan' }).catch(() => {});
    return;
  }
});

// ---------- follow list: look for new chapters on followed manga pages ----------
async function checkFollows(force) {
  await settingsReady;
  if (!S.followHours && !force) return { n: 0 };
  const r = (await chrome.storage.local.get('mgFollow')).mgFollow || {};
  let total = 0;
  for (const k of Object.keys(r)) {
    const f = r[k];
    if (!force && Date.now() - (f.checked || 0) < S.followHours * 3600e3) continue;
    try {
      const chs = chaptersFromHtml(await fetchText(f.url, f.url, 20000), f.url);
      const seen = new Set(f.seen || []);
      const fresh = chs.length >= 3 ? chs.filter((c) => !seen.has(c.url)) : [];
      if (fresh.length) {
        f.unread = (f.unread || []).concat(fresh).slice(-100);
        fresh.forEach((c) => seen.add(c.url));
        f.seen = [...seen].slice(-3000);
        total += fresh.length;
        dbg('follow', `${f.title}: ${fresh.length} new`);
        if (S.notify) try { chrome.notifications.create('mg-follow-' + Date.now(), { type: 'basic', iconUrl: 'icon128.png', title: f.title || 'Media Grabber', message: `${fresh.length} new chapter(s): ${fresh.slice(-3).map((c) => c.label).join(', ')}` }); } catch (e) { /* optional */ }
      }
      f.error = '';
    } catch (e) { f.error = String(e.message || e); dbg('follow', `${f.title}: ${f.error}`); }
    f.checked = Date.now();
    if (S.requestDelayMs) await new Promise((x) => setTimeout(x, S.requestDelayMs));
  }
  const cur = (await chrome.storage.local.get('mgFollow')).mgFollow || {};
  for (const k of Object.keys(r)) if (cur[k]) cur[k] = r[k]; // keep unfollows made while checking
  await chrome.storage.local.set({ mgFollow: cur });
  return { n: total };
}
chrome.alarms.get('mg-follow').then((a) => { if (!a) chrome.alarms.create('mg-follow', { periodInMinutes: 30 }); });
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'mg-follow') checkFollows(false); });
chrome.notifications.onClicked.addListener((id) => { if (String(id).startsWith('mg-follow-')) { chrome.tabs.create({ url: chrome.runtime.getURL('follow.html') }); chrome.notifications.clear(id); } });

// ---------- shortcut + context menu ----------
chrome.commands.onCommand.addListener(async (cmd, tab0) => {
  if (cmd === 'open-sidepanel') { // must be called straight from the shortcut (user gesture)
    try { if (tab0 && tab0.id != null && chrome.sidePanel) await chrome.sidePanel.open({ tabId: tab0.id }); } catch (e) { /* not supported */ }
    return;
  }
  if (cmd !== 'toggle-panel' && cmd !== 'grab-pages') return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab) chrome.tabs.sendMessage(tab.id, { type: cmd === 'grab-pages' ? 'grab-pages' : 'toggle-panel' }, { frameId: 0 }).catch(() => {});
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: 'mg-grab', title: 'Grab this media with Media Grabber',
    contexts: ['link', 'video', 'audio']
  });
  chrome.contextMenus.create({ id: 'mg-side', title: 'Open Media Grabber side panel', contexts: ['action'] });
});
chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === 'mg-side' && tab && chrome.sidePanel) { chrome.sidePanel.open({ tabId: tab.id }).catch(() => {}); return; }
  if (info.menuItemId !== 'mg-grab' || !tab) return;
  const url = info.srcUrl || info.linkUrl;
  if (!url || !/^https?:/i.test(url)) return;
  await settingsReady;
  const kind = classify(url, '', info.mediaType === 'audio' ? 'audio' : 'video');
  await addItems(tab.id, { title: tab.title, pageUrl: tab.url, top: true }, [{ url, kind, ref: info.frameUrl || tab.url }]);
  chrome.tabs.sendMessage(tab.id, { type: 'open-panel' }, { frameId: 0 }).catch(() => {});
});

// ---------- site module: MediaSource off (script is registered only for the sites listed in Settings) ----------
async function syncNoMse() {
  try {
    await settingsReady;
    const doms = String(S.msOffSites || '').split(/\s+/).map((x) => x.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '').replace(/^\*?\.?/, '')).filter((x) => /^[a-z0-9.-]+\.[a-z]{2,}$/.test(x));
    const matches = [...new Set(doms.flatMap((d) => ['*://' + d + '/*', '*://*.' + d + '/*']))];
    try { await chrome.scripting.unregisterContentScripts({ ids: ['mg-nomse'] }); } catch (e) { /* none registered */ }
    if (!matches.length || S.siteModules === false) return;
    await chrome.scripting.registerContentScripts([{ id: 'mg-nomse', matches, js: ['siteNoMse.js'], world: 'MAIN', runAt: 'document_start', allFrames: true, persistAcrossSessions: true }]);
  } catch (e) { dbg('bg', 'MediaSource-off sync failed: ' + (e.message || e)); }
}
chrome.runtime.onInstalled.addListener(() => { syncNoMse(); });
chrome.runtime.onStartup.addListener(() => { syncNoMse(); });
chrome.storage.onChanged.addListener((c, a) => { if (a === 'sync' && (c.msOffSites || c.siteModules)) setTimeout(syncNoMse, 300); });

// ---------- scheduler: start a download at a chosen time ----------
async function addSched(hhmm, job) {
  const [h, mi] = String(hhmm || '').split(':').map(Number);
  if (!isFinite(h) || !isFinite(mi)) throw new Error('bad time');
  const d = new Date(); d.setSeconds(0, 0); d.setHours(h, mi);
  if (d.getTime() <= Date.now() + 5000) d.setDate(d.getDate() + 1); // time already passed today: tomorrow
  const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
  const list = (await chrome.storage.local.get('mgSched')).mgSched || [];
  list.push({ id, when: d.getTime(), ...job });
  await chrome.storage.local.set({ mgSched: list.slice(-50) });
  chrome.alarms.create('mg-sched-' + id, { when: d.getTime() });
}
chrome.alarms.onAlarm.addListener(async (a) => {
  if (!a.name || !a.name.startsWith('mg-sched-')) return;
  const id = a.name.slice(9);
  const list = (await chrome.storage.local.get('mgSched')).mgSched || [];
  const e = list.find((x) => x.id === id);
  if (!e) return;
  await chrome.storage.local.set({ mgSched: list.filter((x) => x.id !== id) });
  await settingsReady;
  startJob({ tabId: -1, itemUrl: e.itemUrl, kind: e.kind, ref: e.ref, title: e.title, variant: e.variant || 'best', noSubs: true });
  if (S.notify) notifyUser('Media Grabber', 'Scheduled download started');
});

// ---------- cleanup ----------
chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === 'loading' && info.url) { // top-level navigation
    chrome.storage.session.remove(tkey(tabId));
    chrome.action.setBadgeText({ tabId, text: '' }).catch(() => {});
  }
});
chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove(tkey(tabId)));
