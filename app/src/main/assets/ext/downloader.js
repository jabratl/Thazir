// Media Grabber - download job page: HLS, DASH, HLS subtitles, files that need page headers.
const $ = (s) => document.querySelector(s);
const $sl = () => $('#stopLive') || { hidden: true }; // Stop & save button: absent in some host pages, must never break startup
initTheme();
const Q = new URLSearchParams(location.search);
const SRC = Q.get('url'), TITLE = Q.get('title') || 'video', REF = Q.get('ref') || '', MODE = Q.get('mode') || 'video';
const LANG = Q.get('lang') || '', EXT = Q.get('ext') || 'mp4', SRC_TAB = parseInt(Q.get('tab'), 10), KEY = Q.get('key') || '';
const AUTO = Q.get('auto') === '1', WANT = Q.get('variant') || 'best', CAP = parseInt(Q.get('cap'), 10) || 0;
function capH(list) { // best variant that fits the preferred max height (list is sorted best-first)
  const cap = CAP || parseInt(SET && SET.maxHeight, 10) || 0;
  if (!cap) return list[0];
  const L = (x) => qh(x) || 0; // quality height (720 = "720p", 536 stays 536), see qh in shared.js
  return list.find((x) => L(x) && L(x) <= cap) || list.filter((x) => L(x)).pop() || list[0];
}
let SET = { ...DEFAULTS };
const AUDIO_ONLY = () => WANT === 'audio' || SET.audioOnly === true; // D4
let cancelled = false, paused = false;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let t0 = Date.now(), totalBytes = 0, lastReport = 0;
let SLOW = false;
let ALLMODE = false, ALLAUDIO = true, ALLFINAL = false, userCancelled = false, QH = '';
let CONC = 6, CONC_MAX = 6, okRun = 0, lastDown = 0; // adaptive parallelism
let ABORT = new AbortController(); // cancelling aborts requests that are in flight, so it stops at once
let LAST = null, RUNNING = false; // LAST = what "Resume" re-runs
const RES = {}, SAVED = {};       // downloaded segments / saved files kept between a stop and a resume
const ICO = { pause: '<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4.2" height="14" rx="1.2"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.2"/></svg>', play: '<svg viewBox="0 0 24 24"><path d="M8 5.5v13a1 1 0 001.5.86l10.5-6.5a1 1 0 000-1.72L9.5 4.64A1 1 0 008 5.5z"/></svg>' };
function setState(c, badge) { $('#card').className = 'card ' + c; $('#badge').textContent = badge; }
window.addEventListener('beforeunload', (e) => { if (RUNNING) { e.preventDefault(); e.returnValue = ''; } });
let QL = ''; // quality being downloaded, e.g. "1080p Full HD"
const qName = (v) => { const [n, d] = qLabel(v.res ? parseInt(v.res.split('x')[1], 10) || 0 : 0, v.bw); return n + ' ' + d; };

function report(state, pct, text) {
  if (!KEY) return;
  const now = Date.now();
  if (state === 'run' && now - lastReport < 400) return;
  lastReport = now;
  if (QL && state === 'run') text = QL + ' \u00b7 ' + text;
  else if (QL && state === 'done') text = 'Saved \u00b7 ' + QL;
  chrome.runtime.sendMessage({ type: 'job-update', tab: SRC_TAB, key: KEY, state, pct, text }).catch(() => {});
}
function status(msg, err) { const s = $('#status'); s.textContent = (paused && !err ? 'Paused \u00b7 ' : '') + (QL && !err ? '[' + QL + '] ' : '') + msg; s.className = err ? 'err' : ''; }
function progress(v, max) {
  const b = $('#bar'); $('#prog').hidden = false;
  if (max > 0) { b.max = max; b.value = v; const p = Math.round((v / max) * 100); $('#pct').textContent = p + '%'; document.title = p + '% - ' + TITLE; }
  else { b.removeAttribute('value'); $('#pct').textContent = ''; document.title = TITLE; }
  if (!paused && RUNNING) setState('run', 'Downloading');
}
function setStats(o) { for (const [k, id] of [['speed', 'sSpeed'], ['size', 'sSize'], ['eta', 'sEta'], ['seg', 'sSeg']]) if (o[k] != null) $('#' + id).textContent = o[k] || '-'; if (o.sub != null) $('#sub').textContent = o.sub; }
function addTitle(t) { const h = document.createElement('h4'); h.textContent = t; $('#options').append(h); }
function addBtn(text, fn, primary) {
  const b = document.createElement('button'); b.textContent = text; if (primary) b.className = 'primary'; b.onclick = fn; $('#options').append(b); return b;
}
function fail(e) {
  RUNNING = false; $('#cancel').hidden = true; $('#pause').hidden = true; paused = false;
  const isCancel = e.message === 'Cancelled' || e.name === 'AbortError';
  document.querySelectorAll('#options [data-r]').forEach((x) => x.remove());
  if (isCancel) {
    setState('err', 'Stopped');
    status('Stopped. What was already downloaded is kept - press Resume to continue.');
    report('error', 0, 'Stopped - can be resumed');
  } else {
    const tail = e.url ? ' [' + (QL ? QL + ', ' : '') + (() => { try { const u = new URL(e.url); return u.pathname.split('/').slice(-2).join('/'); } catch (x) { return ''; } })() + ']' : (QL ? ' [' + QL + ']' : '');
    setState('err', 'Failed');
    const h403 = e.status === 403 ? ' - The video server refuses downloads that do not come from its player. Open the episode page, press Play until the video starts, then download again from there.' : ''; // V74n
    status('Error: ' + e.message + tail + h403, true); report('error', 0, 'Failed: ' + e.message + tail);
  }
  if (LAST) addBtn('Resume (keeps what is downloaded)', resume, true).dataset.r = '1';
  if (!isCancel) {
    const info = ['Media Grabber ' + chrome.runtime.getManifest().version, 'Error: ' + e.message, 'Quality: ' + (QL || '?'), 'Failing URL: ' + (e.url || '?'), 'Master: ' + SRC, 'Variant playlist: ' + (PLURL || '?'), 'Referer: ' + (REF || 'none'), 'Header mode: ' + HMODES[HSTRAT], 'Recorded hosts: ' + (Object.keys(RECORDED).join(', ') || 'none'), 'Gentle mode: ' + SLOW, 'UA: ' + navigator.userAgent].join('\n');
    dbg('dl', 'FAIL ' + info.replace(/\n/g, ' | '));
    addBtn('Copy debug info', () => navigator.clipboard.writeText(info).then(() => status('Debug info copied. Paste it in the chat.', false))).dataset.r = '1';
  }
}
async function resume() {
  $('#options').textContent = '';
  ABORT = new AbortController(); cancelled = false; userCancelled = false; paused = false; SLOW = false;
  setPausedUi();
  report('run', -1, 'Resuming...');
  status('Resuming...');
  try { await LAST(); } catch (e) { fail(e); }
}

// Referer/Origin rule for every request this tab makes (rule id is unique per tab).
// Hosts the real player never talked to (e.g. the CDN of a lower quality) have no recorded headers, so they get this generic rule.
// If such a host answers 403, the header mode is rotated: 0 = full page URL as Referer, 1 = origin-only Referer (what a browser really sends cross-site),
// 2 = headers borrowed from a host the player did use, 3 = no Referer/Origin at all.
let HSTRAT = 1, RULE_ID = null, RULE_COND = null, RECORDED = {}, PLURL = '';
const HMODES = ['full-referer', 'origin-referer', 'borrowed-headers', 'no-referer'];
function pickBorrowed(host) {
  const dom = (h) => String(h).split('.').slice(-2).join('.');
  const keys = Object.keys(RECORDED).filter((k) => k !== host);
  if (!keys.length) return null;
  keys.sort((a, b) => ((dom(b) === dom(host)) - (dom(a) === dom(host))) || ((RECORDED[b].t || 0) - (RECORDED[a].t || 0)));
  return RECORDED[keys[0]];
}
async function applyGeneric(host) {
  if (!REF || RULE_ID == null) return;
  let o = ''; try { o = new URL(REF).origin; } catch (e) { return; }
  const rm = (h) => ({ header: h, operation: 'remove' }), st = (h, v) => ({ header: h, operation: 'set', value: v });
  const put = (hdrs) => chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [RULE_ID], addRules: [{ id: RULE_ID, priority: 1, action: { type: 'modifyHeaders', requestHeaders: hdrs }, condition: RULE_COND }] });
  let h;
  if (HSTRAT === 0) h = [st('referer', REF), st('origin', o)];
  else if (HSTRAT === 1) h = [st('referer', o + '/'), st('origin', o)];
  else if (HSTRAT === 2) { const b = pickBorrowed(host || hostOf(SRC)); h = b ? ruleHeaders(b, false) : [st('referer', o + '/'), rm('origin')]; }
  else h = [rm('referer'), rm('origin')];
  try { await put(h); }
  catch (e) {
    try { const b = HSTRAT === 2 && pickBorrowed(host || hostOf(SRC)); await put(b ? ruleHeaders(b, true) : [st('referer', o + '/'), st('origin', o)]); }
    catch (e2) { console.warn('header rule failed', e2); }
  }
}
async function setupHeaders() {
  if (!REF) return;
  try {
    const me = await chrome.tabs.getCurrent();
    RULE_ID = 2000000 + (me ? me.id : Math.floor(Math.random() * 1e5));
    RULE_COND = { initiatorDomains: [chrome.runtime.id], resourceTypes: ['xmlhttprequest', 'media', 'other'], ...(me ? { tabIds: [me.id] } : {}) };
    await applyGeneric();
  } catch (e) { console.warn('header rule failed', e); }
}
let hChain = Promise.resolve();
function nextHeaderMode(seen, host) { // called after a 403; only the first failing request of a "generation" switches the mode
  hChain = hChain.then(async () => {
    if (HSTRAT !== seen) return;
    HSTRAT = (HSTRAT + 1) % HMODES.length;
    dbg('dl', `403 from ${host}: switching header mode to ${HMODES[HSTRAT]}`);
    await applyGeneric(host);
  }).catch(() => {});
  return hChain;
}

// Per-host rules built from the headers the real player sent (recorded by the background script); they win over the generic rule above
async function setupRecordedHeaders() {
  try {
    const hdr = (await chrome.storage.session.get('mgHdr')).mgHdr || {};
    RECORDED = hdr;
    const me = await chrome.tabs.getCurrent();
    const base = 4000000 + ((me ? me.id : Math.floor(Math.random() * 1e4)) % 20000) * 50;
    const hosts = Object.keys(hdr).slice(0, 49);
    await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: Array.from({ length: 50 }, (_, i) => base + i) });
    let n = 0;
    for (const host of hosts) {
      const cond = { requestDomains: [host], initiatorDomains: [chrome.runtime.id], resourceTypes: ['xmlhttprequest', 'media', 'other'], ...(me ? { tabIds: [me.id] } : {}) };
      const rule = (minimal) => ({ id: base + n, priority: 2, action: { type: 'modifyHeaders', requestHeaders: ruleHeaders(hdr[host], minimal) }, condition: cond });
      try { await chrome.declarativeNetRequest.updateSessionRules({ addRules: [rule(false)] }); n++; }
      catch (e) { try { await chrome.declarativeNetRequest.updateSessionRules({ addRules: [rule(true)] }); n++; } catch (e2) { /* skip host */ } }
    }
    dbg('dl', `${n} recorded header rule(s) applied`);
  } catch (e) { console.warn('recorded header rules failed', e); }
}

async function gate() { while (paused && !cancelled) await sleep(200); if (cancelled) throw new Error('Cancelled'); }

// The master playlist is often signed (?token=...) while its variant playlists and segments carry no query.
// Players get away with that, but a server may reject the lower qualities the player never asked for: retry with the master's query.
const SRC_QS = (() => { try { return new URL(SRC).search; } catch (e) { return ''; } })();
function withMasterQuery(url) {
  try {
    if (!SRC_QS) return url;
    const u = new URL(url), s = new URL(SRC);
    if (u.search || u.host !== s.host) return url;
    u.search = SRC_QS;
    return u.href;
  } catch (e) { return url; }
}
// V74n: last way for a segment the CDN refuses (403 in every header mode): let the video page / player frame download it with its own origin and cookies.
// PGBIN: 0 = not tried, 1 = works (then used first), -1 = gave up (3 failures and never a success)
let PGBIN = 0, PGBIN_FAIL = 0;
async function viaPageBin(url, opts) {
  if (PGBIN < 0 || !Number.isInteger(SRC_TAB)) return null;
  try {
    const pr = await chrome.runtime.sendMessage({ type: 'page-fetch-bin', tab: SRC_TAB, url, range: (opts.headers && opts.headers.Range) || '' });
    if (pr && pr.ok && pr.b64) {
      const bin = atob(pr.b64), u8 = new Uint8Array(bin.length);
      for (let k = 0; k < bin.length; k++) u8[k] = bin.charCodeAt(k);
      if (PGBIN !== 1) dbg('dl', 'segments are read through the video page: ' + hostOf(url));
      PGBIN = 1; return new Response(u8, { status: 200 });
    }
    if (PGBIN !== 1 && ++PGBIN_FAIL >= 3) PGBIN = -1;
    dbg('dl', 'page could not read the segment either: ' + ((pr && pr.err) || 'no answer'));
  } catch (pe) { if (PGBIN !== 1 && ++PGBIN_FAIL >= 3) PGBIN = -1; }
  return null;
}

// V78: some CDNs hide each segment behind a fake picture header (PNG / JPEG / GIF / BMP / WEBP bytes, then the real .ts or fMP4 data).
// Players cut it off; here it is cut off before saving, so the merged file plays. Only runs when the data starts with a picture signature.
function stripFakeHead(buf) {
  try {
    const u = new Uint8Array(buf), n = u.length;
    if (n < 64) return buf;
    const pic = (u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47) || (u[0] === 0xff && u[1] === 0xd8 && u[2] === 0xff) ||
      (u[0] === 0x47 && u[1] === 0x49 && u[2] === 0x46 && u[3] === 0x38) || (u[0] === 0x42 && u[1] === 0x4d) ||
      (u[0] === 0x52 && u[1] === 0x49 && u[2] === 0x46 && u[3] === 0x46 && u[8] === 0x57 && u[9] === 0x45 && u[10] === 0x42 && u[11] === 0x50);
    if (!pic) return buf;
    const lim = Math.min(n - 8, 262144);
    const box = (i) => { // fMP4 box start: size + known type
      const t = String.fromCharCode(u[i + 4], u[i + 5], u[i + 6], u[i + 7]);
      if (!/^(ftyp|styp|moof|moov|sidx|emsg)$/.test(t)) return false;
      const sz = ((u[i] << 24) | (u[i + 1] << 16) | (u[i + 2] << 8) | u[i + 3]) >>> 0;
      return sz >= 8 && i + sz <= n;
    };
    for (let i = 4; i < lim; i++) {
      if (u[i] === 0x47 && i + 376 < n && u[i + 188] === 0x47 && u[i + 376] === 0x47) return buf.slice(i); // MPEG-TS: sync byte every 188 bytes
      if (u[i + 4] === 0x66 || u[i + 4] === 0x73 || u[i + 4] === 0x6d || u[i + 4] === 0x65) { if (box(i)) return buf.slice(i); }
    }
  } catch (e) { /* keep the data as it is */ }
  return buf;
}

// V78: for an ENCRYPTED segment the picture sits in front of the encrypted data: its end is found from the picture itself (PNG: IEND chunk, JPEG: end marker)
function stripFakePic(buf) {
  try {
    const u = new Uint8Array(buf), n = u.length;
    let off = 0;
    if (u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47) { // PNG: walk the chunks to IEND
      let p = 8;
      while (p + 12 <= n) {
        const len = ((u[p] << 24) | (u[p + 1] << 16) | (u[p + 2] << 8) | u[p + 3]) >>> 0;
        const t = String.fromCharCode(u[p + 4], u[p + 5], u[p + 6], u[p + 7]);
        p += 12 + len;
        if (t === 'IEND') { off = p; break; }
      }
    } else if (u[0] === 0xff && u[1] === 0xd8 && u[2] === 0xff) {
      for (let i = 2; i < Math.min(n - 1, 262144); i++) if (u[i] === 0xff && u[i + 1] === 0xd9) { off = i + 2; break; }
    }
    if (off > 0 && off < n && (n - off) % 16 === 0) return buf.slice(off);
  } catch (e) { /* keep */ }
  return buf;
}

async function fetchRetry(url, opts = {}, tries = 6) {
  const alt = withMasterQuery(url);
  if (opts.bin && PGBIN === 1) { const pr = await viaPageBin(url, opts); if (pr) return pr; }
  for (let i = 0; i < tries; i++) {
    await gate();
    const u = i % 2 === 1 && alt !== url ? alt : url; // alternate plain / signed on retries
    const seen = HSTRAT;
    try {
      const r = await fetch(u, { credentials: 'include', signal: ABORT.signal, ...opts });
      if (!r.ok) { const er = new Error('HTTP ' + r.status + ' from ' + hostOf(u)); er.status = r.status; er.url = u; throw er; }
      return r;
    } catch (e) {
      if (cancelled || e.name === 'AbortError' || e.message === 'Cancelled') throw Object.assign(new Error('Cancelled'), { url: u });
      if (e.status === 403 || e.status === 429 || e.status >= 500) SLOW = true; // CDN is pushing back: go gentler
      if ((e.status === 429 || e.status >= 500) && Date.now() - lastDown > 4000 && CONC > 2) { CONC--; lastDown = Date.now(); okRun = 0; dbg('dl', 'server pushing back, parallel downloads -> ' + CONC); }
      if (!e.url) e.url = u;
      if (i === tries - 1 && e.status === 403 && opts.bin) { const pr = await viaPageBin(url, opts); if (pr) return pr; }
      if (i === tries - 1 && e.status === 403 && !opts.noPage && Number.isInteger(SRC_TAB) && /\.(?:m3u8|mpd|vtt)(?:[?#]|$)/i.test(url)) { // V73b: last way for playlists - let the video page (its frames) read it with its own cookies / origin
        try { const pr = await chrome.runtime.sendMessage({ type: 'page-fetch-text', tab: SRC_TAB, url }); if (pr && pr.ok && pr.text) { dbg('dl', 'playlist read through the video page: ' + hostOf(url)); return new Response(pr.text, { status: 200 }); } dbg('dl', 'page could not read it either: ' + ((pr && pr.err) || 'no answer')); } catch (pe) { /* fall through to the error */ }
      }
      if (i === tries - 1 || cancelled) { dbg('dl', `${e.message} for ${url} (referer set: ${REF || 'none'}, header mode: ${HMODES[HSTRAT]})`); throw e; }
      let switched = false;
      if (e.status === 403 && REF) { await nextHeaderMode(seen, hostOf(u)); switched = HSTRAT !== seen; }
      await sleep(switched ? 150 : (SLOW ? 1200 : 600) * (i + 1));
    }
  }
}

// ---------- saving ----------
async function save(blob, ext, suffix) {
  const filename = buildFileName(SET, { title: TITLE, site: hostOf(REF || SRC), name: baseName(SRC).replace(/\.[a-z0-9]{2,5}$/i, ''), kind: MODE, res: QH, lang: LANG, suffix, book: Q.get('book') || '', chapter: Q.get('chapter') || '', index: Q.get('index') || '' }, ext);
  const url = URL.createObjectURL(blob);
  let id;
  LAST_DL_ID = 0;
  try { id = await chrome.downloads.download({ url, filename, conflictAction: 'uniquify' }); LAST_DL_ID = id; }
  catch (e) { // browsers without chrome.downloads (or that refuse blob URLs / sub-folders): a normal link download
    dbg('dl', 'chrome.downloads failed (' + (e.message || e) + '), saving with a link');
    const a = document.createElement('a');
    a.href = url; a.download = filename.split('/').pop();
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 120000);
    addHistory({ id: 0, title: a.download, kind: MODE === 'video' ? 'stream' : MODE, site: hostOf(REF || SRC), src: REF || SRC, size: blob.size });
    return a.download;
  }
  addHistory({ id, title: filename.split('/').pop(), kind: MODE === 'video' ? 'stream' : MODE, site: hostOf(REF || SRC), src: REF || SRC, size: blob.size, ik: KEY, tt: TITLE });
  const onCh = (d) => { if (d.id === id && d.state && d.state.current !== 'in_progress') { chrome.downloads.onChanged.removeListener(onCh); setTimeout(() => URL.revokeObjectURL(url), 5000); } };
  chrome.downloads.onChanged.addListener(onCh);
  return filename;
}

let HINT = '';
function finish(text) {
  if (HINT) { text += ' - ' + HINT; HINT = ''; }
  if (ALLMODE && !ALLFINAL) { status(text); return; }
  $('#cancel').hidden = true; $('#pause').hidden = true;
  RUNNING = false; paused = false; setState('done', 'Done'); if (!$('#prog').hidden) { progress(1, 1); setStats({ eta: 'Done' }); }
  status(text);
  report('done', 1, 'Saved');
  if (SET.autoClose && !window.MG_OFFSCREEN) setTimeout(() => window.close(), 3500); // a hidden document must stay until the browser has written the file
}

// ---------- disk-backed segment store (OPFS) ----------
// Segments are written to the extension's private file system instead of piling up in RAM. So a 5 GB stream does not need
// 5 GB of memory, and after a failure / closed window / browser restart the next try continues where the last one stopped.
// Anything that goes wrong here switches to plain memory, so a download never fails because of the store.
const Disk = {
  off: !(typeof navigator !== 'undefined' && navigator.storage && navigator.storage.getDirectory),
  async base() { return (await navigator.storage.getDirectory()).getDirectoryHandle('mg-seg', { create: true }); },
  async dir(id, create = true) { return (await this.base()).getDirectoryHandle(id, { create }); },
  id(s) { // short stable id for a stream (the signed ?query of a URL changes between tries, so callers leave it out)
    let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); h1 = Math.imul(h1 ^ c, 2654435761); h2 = Math.imul(h2 ^ c, 1597334677); }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
  },
  async put(id, name, buf) { // -> a Blob (a File on disk when possible)
    if (!this.off) {
      try {
        const fh = await (await this.dir(id)).getFileHandle(String(name), { create: true });
        const w = await fh.createWritable(); await w.write(buf); await w.close(); // atomic: the file only appears when complete
        return await fh.getFile();
      } catch (e) { this.off = true; dbg('dl', 'disk store off, using memory: ' + (e.message || e)); }
    }
    return new Blob([buf]);
  },
  async get(id, name) { try { const f = await (await (await this.dir(id, false)).getFileHandle(String(name))).getFile(); return f.size ? f : null; } catch (e) { return null; } },
  async restore(id, total) { // segments an earlier try already saved: {index: File}
    const got = {};
    if (this.off) return got;
    try {
      for await (const [n, h] of (await this.dir(id, false)).entries()) {
        if (h.kind === 'file' && /^\d+$/.test(n) && +n < total) { const f = await h.getFile(); if (f.size) got[+n] = f; }
      }
    } catch (e) { /* nothing saved yet */ }
    return got;
  },
  async meta(id, obj) { // small note beside the segments (what stream, which format): lets a crashed live recording be recovered later
    if (this.off) return;
    try { const fh = await (await this.dir(id)).getFileHandle('meta.json', { create: true }); const w = await fh.createWritable(); await w.write(JSON.stringify(obj)); await w.close(); } catch (e) { /* optional */ }
  },
  async drop(id) { try { await (await this.base()).removeEntry(id, { recursive: true }); } catch (e) { /* already gone */ } },
  async purge() { // leftovers of old, abandoned downloads (older than 3 days)
    if (this.off) return;
    try {
      const base = await this.base(), old = [];
      for await (const [n, h] of base.entries()) {
        if (h.kind !== 'directory') continue;
        let newest = 0;
        for await (const [, f] of h.entries()) if (f.kind === 'file') newest = Math.max(newest, (await f.getFile()).lastModified);
        if (!newest || Date.now() - newest > 3 * 86400e3) old.push(n);
      }
      for (const n of old) await base.removeEntry(n, { recursive: true });
    } catch (e) { /* optional */ }
  }
};
let LAST_DL_ID = 0;
function dropWhenSaved(dk, id) { // the saved file is read from the store: only delete it once the browser has finished writing
  if (!dk) return;
  if (!id) { setTimeout(() => Disk.drop(dk), 120000); return; }
  const onCh = (d) => { if (d.id === id && d.state && d.state.current === 'complete') { chrome.downloads.onChanged.removeListener(onCh); Disk.drop(dk); } };
  chrome.downloads.onChanged.addListener(onCh);
}

// ---------- segment download ----------
const segExt = (pl) => { const e = extOf(pl.segs[0].url); return pl.map || ['m4s', 'mp4', 'cmfv', 'cmfa'].includes(e) ? 'mp4' : e === 'aac' ? 'aac' : e === 'mp3' ? 'mp3' : 'ts'; };
const hexToBytes = (h) => Uint8Array.from(h.replace(/^0x/i, '').padStart(32, '0').match(/../g).map((x) => parseInt(x, 16)));
const seqToIv = (n) => { const iv = new Uint8Array(16); new DataView(iv.buffer).setUint32(12, n); return iv; };
const rangeHdr = (r) => (r ? { Range: `bytes=${r.start}-${r.end}` } : {});

function speedText(done, total, label) {
  const sec = (Date.now() - t0) / 1000 || 1;
  const bps = totalBytes / sec;
  const remain = done ? ((total - done) / done) * sec : 0;
  return `${label}: ${done}/${total} segments - ${fmtSize(totalBytes)} - ${fmtSpeed(bps)}${remain ? ' - ' + fmtDur(remain) + ' left' : ''}`;
}

async function downloadSegments(pl, label) {
  const total = pl.segs.length;
  if (!total) throw new Error('Playlist is empty');
  const rk = label + '|' + pl.segs[0].url + '|' + total;
  const dk = Disk.id(label + '|' + pl.segs[0].url.split('?')[0] + '|' + total);
  const isNew = !RES[rk];
  const parts = RES[rk] || (RES[rk] = new Array(total)); // a resumed run skips the parts it already has
  if (isNew) { // ...and so does a run after a failure / closed window: the segments are still on disk
    const got = await Disk.restore(dk, total);
    for (const k of Object.keys(got)) parts[+k] = got[k];
    if (Object.keys(got).length) { status(`Continuing: ${Object.keys(got).length} of ${total} segments are already saved`); dbg('dl', `resume: ${Object.keys(got).length}/${total} segments found on disk`); }
  }
  const keys = new Map();
  let done = parts.filter(Boolean).length, next = 0;
  const baseDone = done;
  // MPEG-TS -> MP4 while the download is still running (segment by segment, strictly in order), so there is almost nothing left to do at the end
  let conv = null;
  if (segExt(pl) === 'ts' && SET.convertMp4 && !(SET.convertMaxMB > 0) && await loadMux()) {
    conv = { tx: new muxjs.mp4.Transmuxer({ keepOriginalTimestamps: true }), out: [], first: true, next: 0, chain: Promise.resolve(), err: null };
    conv.tx.on('data', (s) => { conv.out.push(conv.first ? new Blob([s.initSegment, s.data]) : new Blob([s.data])); conv.first = false; });
  }
  function pumpConv() {
    if (!conv || conv.err) return Promise.resolve();
    conv.chain = conv.chain.then(async () => {
      try {
        while (!conv.err && parts[conv.next]) {
          conv.tx.push(new Uint8Array(await parts[conv.next].arrayBuffer()));
          conv.tx.flush(); // one fragment per segment: keeps memory flat, however long the video is
          conv.next++;
          if (conv.next % 8 === 0) await sleep(0);
        }
      } catch (e) { conv.err = e; dbg('dl', 'convert-while-downloading failed, will convert at the end: ' + (e.message || e)); }
    });
    return conv.chain;
  }
  pumpConv();
  t0 = Date.now(); totalBytes = 0;

  const getKey = (uri) => {
    if (!keys.has(uri)) keys.set(uri, fetchRetry(uri).then((r) => r.arrayBuffer()).then((b) => crypto.subtle.importKey('raw', b, { name: 'AES-CBC' }, false, ['decrypt'])));
    return keys.get(uri);
  };
  async function work(i) {
    if (parts[i]) return;
    const s = pl.segs[i];
    if (SLOW) await sleep(250);
    let buf = await (await fetchRetry(s.url, { headers: rangeHdr(s.range), bin: true })).arrayBuffer();
    if (s.key) {
      const k = await getKey(s.key.uri);
      buf = stripFakePic(buf); // V78
      buf = await crypto.subtle.decrypt({ name: 'AES-CBC', iv: s.key.iv ? hexToBytes(s.key.iv) : seqToIv(s.seq) }, k, buf);
    }
    buf = stripFakeHead(buf); // V78
    totalBytes += buf.byteLength;
    if (SET.speedLimitKBps > 0) { // bandwidth limit: wait until the average speed is back under the limit
      const ahead = (totalBytes / (SET.speedLimitKBps * 1024)) * 1000 - (Date.now() - t0);
      if (ahead > 40) await sleep(Math.min(ahead, 8000));
    }
    parts[i] = await Disk.put(dk, i, buf);
    pumpConv();
    done++;
    if (++okRun >= 40 && CONC < CONC_MAX) { CONC++; okRun = 0; } // server is fine again: speed back up
    progress(done, total);
    const t = speedText(done, total, label);
    status(t);
    { const sec = (Date.now() - t0) / 1000 || 1, fresh = done - baseDone, rem = fresh ? ((total - done) / fresh) * sec : 0;
      setStats({ speed: fmtSpeed(totalBytes / sec), size: fmtSize(totalBytes), eta: rem ? fmtDur(rem) : '', seg: done + ' / ' + total, sub: label }); }
    report('run', done / total, t.replace(label + ': ', ''));
  }
  let initBlob = null;
  if (pl.map) initBlob = RES[rk + 'init'] || (RES[rk + 'init'] = (await Disk.get(dk, 'init')) || await Disk.put(dk, 'init', await (await fetchRetry(pl.map.url, { headers: rangeHdr(pl.map.range), bin: true })).arrayBuffer()));

  // a few bad segments no longer kill the whole job: they are collected and retried at the end
  const failed = [];
  const fatal = (e) => cancelled || e.message === 'Cancelled';
  CONC_MAX = Math.max(1, Math.min(SET.concurrency || 6, total)); CONC = CONC_MAX; okRun = 0;
  const workers = Array.from({ length: CONC_MAX }, async (_, w) => {
    while (true) {
      while (w >= CONC && next < total && !cancelled) await sleep(300); // adaptive: surplus workers wait
      const i = next++;
      if (i >= total) break;
      try { await work(i); }
      catch (e) {
        if (fatal(e)) { cancelled = true; throw e; }
        failed.push({ i, e });
        if (failed.length >= 30 && failed.length > done) { cancelled = true; throw e; } // mostly failing: give up early
      }
    }
  });
  await Promise.all(workers);
  for (let pass = 1; failed.length && pass <= 3; pass++) {
    status(`Retrying ${failed.length} failed segment(s) (round ${pass}/3)...`);
    await sleep(2000 * pass);
    for (const f of failed.splice(0)) {
      try { await work(f.i); }
      catch (e) { if (fatal(e)) { cancelled = true; throw e; } failed.push({ i: f.i, e }); }
    }
  }
  if (failed.length) { const e = failed[0].e; e.message = `${failed.length} segment(s) could not be downloaded (${e.message})`; throw e; }

  const ext = segExt(pl);
  let converted = null;
  if (conv) { await pumpConv(); if (!conv.err && conv.next === total) converted = conv.out; }
  return { parts, initBlob, ext, rk, dk, converted };
}

async function loadMux() {
  if (window.muxjs) return true;
  return new Promise((res) => {
    const s = document.createElement('script');
    s.src = chrome.runtime.getURL('lib/mux.min.js');
    s.onload = () => res(!!window.muxjs); s.onerror = () => res(false);
    document.head.append(s);
  });
}

const fmtWait = (s) => (!isFinite(s) || s < 1 ? '' : s >= 60 ? Math.floor(s / 60) + 'm ' + Math.round(s % 60) + 's' : Math.round(s) + 's');
function convProgress(label, f, t0) { // percentage + time left while converting, so a big file does not look frozen
  const left = f > 0.02 ? (Date.now() - t0) / f * (1 - f) / 1000 : NaN;
  const txt = `${label} ${Math.round(f * 100)}%` + (fmtWait(left) ? ` (about ${fmtWait(left)} left)` : '');
  status(txt);
  report('run', 0.97 + 0.02 * f, txt);
}
async function toMp4(parts) { // MPEG-TS -> fragmented MP4 pieces (remuxFmp4 below turns them into a seekable file)
  const tx = new muxjs.mp4.Transmuxer({ keepOriginalTimestamps: true });
  const out = [];
  let first = true;
  // results are kept as Blobs (the browser can park them on disk) instead of one huge pile of arrays on the JS heap
  tx.on('data', (seg) => {
    if (first) { out.push(new Blob([seg.initSegment, seg.data])); first = false; }
    else out.push(new Blob([seg.data]));
  });
  const t0 = Date.now();
  for (let i = 0; i < parts.length; i++) {
    tx.push(new Uint8Array(await parts[i].arrayBuffer()));
    tx.flush(); // per segment: one giant fragment would keep the whole video in memory
    if (i % 4 === 3 || i === parts.length - 1) { convProgress('Converting to MP4...', (i + 1) / parts.length, t0); await sleep(0); }
  }
  tx.flush();
  if (!out.length) throw new Error('MP4 conversion produced no data');
  return out;
}

// Fragmented MP4 (what HLS-fMP4 / DASH / converted TS give) -> normal MP4 with a full index, so the player shows the
// real duration and can seek forward / backward. If anything goes wrong we fall back to the plain joined file.
async function makeSeekable(pieces) {
  try {
    status('Making the video seekable...');
    const t0 = Date.now();
    return await remuxFmp4(pieces, async (f) => { convProgress('Making the video seekable...', f, t0); await sleep(0); });
  } catch (e) {
    dbg('dl', 'remux failed, saving joined fragments: ' + (e.message || e));
    return new Blob(pieces, { type: 'video/mp4' });
  }
}

async function saveSegments(r, suffix, isAudio) {
  let ext = r.ext, blob;
  const mb = r.parts.reduce((a, p) => a + (p.size || 0), 0) / 1048576;
  const tooBig = ext === 'ts' && SET.convertMaxMB > 0 && mb > SET.convertMaxMB;
  if (tooBig) { // converting this much in the browser takes minutes: save the .ts at once, ffmpeg turns it into MP4 in seconds
    dbg('dl', `skipping MP4 conversion: ${Math.round(mb)} MB > ${SET.convertMaxMB} MB`);
    HINT = 'Saved as .ts because it is large (no slow conversion). MP4 in seconds: ffmpeg -i "file.ts" -c copy "file.mp4"';
  }
  if (ext === 'ts' && SET.convertMp4 && !tooBig && (r.converted || await loadMux())) {
    if (!r.converted) status('Converting to MP4...');
    try { blob = await makeSeekable(r.converted || await toMp4(r.parts)); ext = 'mp4'; } catch (e) { blob = null; }
  } else if (ext === 'mp4') {
    blob = await makeSeekable(r.initBlob ? [r.initBlob, ...r.parts] : r.parts);
  }
  if (!blob) blob = new Blob(r.initBlob ? [r.initBlob, ...r.parts] : r.parts, { type: ext === 'ts' ? 'video/mp2t' : 'video/mp4' });
  if (isAudio && ext === 'mp4') ext = 'm4a';
  const saved = await save(blob, ext, suffix || (ALLMODE ? QH : ''));
  dropWhenSaved(r.dk, LAST_DL_ID);
  if (r.rk) { delete RES[r.rk]; delete RES[r.rk + 'init']; }
  return saved;
}

// Video and audio kept apart by the stream -> ONE mp4 (the media is only re-packaged, never re-encoded: no quality loss).
async function piecesOf(r) { // fragmented-MP4 pieces of a downloaded stream, or null when this format cannot be re-packaged here
  if (r.ext === 'mp4') return r.initBlob ? [r.initBlob, ...r.parts] : r.parts;
  if (r.ext === 'ts' && SET.convertMp4 && (r.converted || await loadMux())) return r.converted || await toMp4(r.parts);
  return null;
}
async function mergeAndSave(v, a, suffix) {
  const vp = await piecesOf(v), ap = await piecesOf(a);
  if (!vp || !ap) { dbg('dl', `cannot merge: video is ${v.ext}, audio is ${a.ext}`); return null; }
  status('Merging video and audio...');
  const t0 = Date.now();
  const blob = await mergeMedia(vp, ap, async (f) => { convProgress('Merging video and audio...', f, t0); await sleep(0); await gate(); });
  const saved = await save(blob, 'mp4', suffix || (ALLMODE ? QH : ''));
  for (const r of [v, a]) { dropWhenSaved(r.dk, LAST_DL_ID); if (r.rk) { delete RES[r.rk]; delete RES[r.rk + 'init']; } }
  return saved;
}

function mergeHint(files, dir = '') {
  const out = files[0].replace(/\.[a-z0-9]{2,4}$/i, '') + '.merged.mp4';
  const cmd = `ffmpeg -i "${files[0]}" -i "${files[1]}" -c copy "${out}"`;
  addBtn('Copy ffmpeg merge command', () => navigator.clipboard.writeText(cmd));
  // one-click scripts saved next to the two files (needs ffmpeg installed; double-click to merge)
  const mk = (name, text) => chrome.downloads.download({ url: 'data:text/plain;charset=utf-8,' + encodeURIComponent(text), filename: dir + name, conflictAction: 'uniquify' }).catch(() => {});
  addBtn('Save merge script (.bat, Windows)', () => mk('merge.bat', `@echo off\r\ncd /d "%~dp0"\r\n${cmd}\r\npause\r\n`));
  addBtn('Save merge script (.sh, Mac/Linux)', () => mk('merge.sh', `#!/bin/sh\ncd "$(dirname "$0")"\n${cmd}\n`));
}

// ---------- HLS ----------
async function mergeVtt(pl) {
  const texts = new Array(pl.segs.length);
  let next = 0, done = 0;
  await Promise.all(Array.from({ length: Math.min(6, pl.segs.length) }, async () => {
    while (true) { const i = next++; if (i >= pl.segs.length) break; texts[i] = await (await fetchRetry(pl.segs[i].url)).text(); progress(++done, pl.segs.length); }
  }));
  const body = texts.map((t) => t.replace(/^﻿?WEBVTT[^\n]*\n(?:[^\n]+\n)*\n?/, '').trim()).filter(Boolean).join('\n\n');
  return 'WEBVTT\n\n' + body + '\n';
}

const SUBFMT = Q.get('fmt') || 'srt';
async function offerSubtitle(vtt, auto) {
  const doSrt = () => save(new Blob(['\uFEFF' + vttToSrt(vtt)], { type: 'text/plain' }), 'srt', LANG);
  const doVtt = () => save(new Blob(['\uFEFF' + vtt], { type: 'text/vtt' }), 'vtt', LANG);
  const doTxt = () => save(new Blob(['\uFEFF' + subConvert(vtt, 'txt', 'vtt').text], { type: 'text/plain' }), 'txt', LANG);
  if (auto) { await (SUBFMT === 'vtt' || SUBFMT === 'orig' ? doVtt : SUBFMT === 'txt' ? doTxt : doSrt)(); return finish('Subtitle saved.'); }
  status('Subtitle ready.');
  addTitle('Save subtitle');
  addBtn('Save .srt', doSrt, true);
  addBtn('Save .vtt', doVtt);
  addBtn('Save .txt (text only)', doTxt);
}

async function runSubtitle(url, auto) {
  $('#options').textContent = '';
  status('Downloading subtitle...');
  const text = await (await fetchRetry(url)).text();
  if (text.includes('#EXTM3U')) return offerSubtitle(await mergeVtt(parseMedia(text, url)), auto);
  if (/^\uFEFF?WEBVTT/.test(text) && (auto ? ['srt', 'vtt', 'txt', 'orig'].includes(SUBFMT) : true)) return offerSubtitle(text, auto);
  const srcExt = extOf(url) && !/^(php|html?|aspx?)$/.test(extOf(url)) ? extOf(url) : (/^\s*[\[{]/.test(text) ? 'json' : 'txt');
  const out = subConvert(text, auto ? SUBFMT : 'srt', srcExt, SET.subDelayMs); // 0.0.351 (V82)
  if (!auto && out.converted) { // not WebVTT (ASS, TTML, JSON ...): let the person choose the format
    const cues = subCuesFrom(text, srcExt);
    status('Subtitle ready.'); addTitle('Save subtitle');
    addBtn('Save .srt', () => save(new Blob(['\uFEFF' + cuesToSrt(cues)], { type: 'text/plain' }), 'srt', LANG), true);
    addBtn('Save .vtt', () => save(new Blob(['\uFEFF' + cuesToVtt(cues)], { type: 'text/vtt' }), 'vtt', LANG));
    addBtn('Save .txt (text only)', () => save(new Blob(['\uFEFF' + cuesToTxt(cues)], { type: 'text/plain' }), 'txt', LANG));
    addBtn('Save original', () => save(new Blob([text], { type: 'text/plain' }), srcExt || 'txt', LANG));
    return;
  }
  await save(new Blob(['\uFEFF' + out.text], { type: 'text/plain' }), out.ext, LANG);
  finish(out.converted ? 'Subtitle saved (.' + out.ext + ').' : 'Subtitle saved (original format).');
}


// ---------- clip: download only a part of an HLS video ----------
const toSec = (t) => { t = String(t || '').trim(); if (!t) return null; const p = t.split(':').map(Number); if (p.some((x) => !isFinite(x) || x < 0)) return NaN; return p.reduce((a, x) => a * 60 + x, 0); };
let CLIP = (() => { const m = /^(\d+(?:\.\d+)?)-(\d*(?:\.\d+)?)$/.exec(Q.get('clip') || ''); return m ? { s: parseFloat(m[1]), e: m[2] === '' ? Infinity : parseFloat(m[2]) } : null; })();
function readClip() {
  const f = document.getElementById('clipFrom'); if (!f) return;
  const a = toSec(f.value), b = toSec(document.getElementById('clipTo').value);
  if (Number.isNaN(a) || Number.isNaN(b)) throw new Error('Clip time not understood. Use 90, 1:30 or 1:02:30.');
  if (a == null && b == null) { CLIP = null; return; }
  const s = a || 0, e = b == null ? Infinity : b;
  if (e <= s) throw new Error('The clip end must be after its start.');
  CLIP = { s, e };
}
function clipPl(pl) {
  if (!CLIP) return pl;
  let t = 0; const out = [];
  for (const sg of pl.segs) { const a = t, b = t + (sg.dur || 0); t = b; if (b > CLIP.s && a < CLIP.e) out.push(sg); }
  if (!out.length) throw new Error('The chosen time range has no video in it.');
  return { ...pl, segs: out };
}
const clipSuffix = () => (CLIP ? 'clip ' + Math.round(CLIP.s) + 's-' + (CLIP.e === Infinity ? 'end' : Math.round(CLIP.e) + 's') : '');
function addClipInputs() {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;gap:8px;align-items:center;margin:2px 2px 10px;color:var(--mut);font-size:13px;flex-wrap:wrap';
  box.innerHTML = '<span>Only a part (optional): from</span><input id="clipFrom" placeholder="0:00" size="7"><span>to</span><input id="clipTo" placeholder="end" size="7"><small style="flex-basis:100%">Use 90, 1:30 or 1:02:30. The cut is at segment boundaries, so it is accurate to a few seconds.</small>';
  box.querySelectorAll('input').forEach((i) => { i.style.cssText = 'background:var(--panel2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:6px 8px;font:inherit'; });
  if (CLIP) { box.querySelector('#clipFrom').value = CLIP.s || ''; box.querySelector('#clipTo').value = CLIP.e === Infinity ? '' : CLIP.e; }
  $('#options').append(box);
}

// ---------- live HLS recording ----------
// A live playlist has no #EXT-X-ENDLIST: it keeps growing. We poll it, save every new segment to disk and, when the stream ends
// (or you press Stop & save, or the time limit is reached) turn the segments into one file the normal way.
let LIVE_STOP = false;
const isLivePl = (t) => !/#EXT-X-ENDLIST/.test(t) && !/#EXT-X-PLAYLIST-TYPE:\s*VOD/i.test(t);

async function recordLive(plUrl, firstText, label, S) {
  const dk = Disk.id('live|' + label + '|' + plUrl.split('?')[0] + '|' + S.t0);
  const parts = [], seen = new Set(), keys = new Map();
  let text = firstText, idx = 0, initBlob = null, ext = 'ts', badPolls = 0, skipped = 0, lastNew = Date.now(), metaAt = 0;
  const note = (force) => { // keeps the recovery note fresh (at most every 5 s); never throws
    if (!force && Date.now() - metaAt < 5000) return;
    metaAt = Date.now();
    Disk.meta(dk, { live: true, label, ext, title: TITLE, src: REF || SRC, t0: S.t0, upd: Date.now(), n: idx, bytes: S.bytes });
  };
  const getKey = (uri) => {
    if (!keys.has(uri)) keys.set(uri, fetchRetry(uri).then((r) => r.arrayBuffer()).then((b) => crypto.subtle.importKey('raw', b, { name: 'AES-CBC' }, false, ['decrypt'])));
    return keys.get(uri);
  };
  while (true) {
    const pl = parseMedia(text, plUrl);
    if (pl.segs.length) ext = segExt(pl);
    if (pl.map && !initBlob) initBlob = await Disk.put(dk, 'init', await (await fetchRetry(pl.map.url, { headers: rangeHdr(pl.map.range), bin: true })).arrayBuffer());
    for (const s of pl.segs) {
      if (S.stop()) break;
      const id = s.seq + '|' + s.url.split('?')[0] + (s.range ? '|' + s.range.start : '');
      if (seen.has(id)) continue;
      seen.add(id);
      try {
        let buf = await (await fetchRetry(s.url, { headers: rangeHdr(s.range) }, 4)).arrayBuffer();
        if (s.key) {
          const k = await getKey(s.key.uri);
          buf = stripFakePic(buf); // V78
          buf = await crypto.subtle.decrypt({ name: 'AES-CBC', iv: s.key.iv ? hexToBytes(s.key.iv) : seqToIv(s.seq) }, k, buf);
        }
        buf = stripFakeHead(buf); // V78
        S.bytes += buf.byteLength;
        parts.push(await Disk.put(dk, idx++, buf));
        S.segs++; lastNew = Date.now(); note(idx === 1);
      } catch (e) {
        if (cancelled || e.message === 'Cancelled') throw e;
        skipped++; dbg('dl', 'live: segment skipped (' + (e.message || e) + ')');
      }
      S.tick();
    }
    if (S.stop() || /#EXT-X-ENDLIST/.test(text)) break;
    const td = parseFloat((/#EXT-X-TARGETDURATION:(\d+(?:\.\d+)?)/.exec(text) || [])[1]) || 6;
    if (Date.now() - lastNew > Math.max(45000, td * 8000)) { dbg('dl', 'live: no new segments for a while, treating the stream as ended'); break; }
    const until = Date.now() + Math.max(1500, Math.min(td * 500, 5000));
    while (Date.now() < until && !S.stop()) { await gate(); await sleep(250); S.tick(); }
    if (S.stop()) break;
    try { text = await (await fetchRetry(plUrl, {}, 3)).text(); badPolls = 0; }
    catch (e) {
      if (cancelled || e.message === 'Cancelled') throw e;
      if (++badPolls >= 4) { dbg('dl', 'live: playlist is gone, treating the stream as ended'); break; }
    }
  }
  if (!parts.length) throw new Error('Nothing was recorded: the live stream gave no segments.');
  note(true);
  return { parts, initBlob, ext, rk: null, dk, converted: null, skipped };
}

async function runLive(plUrl, text, audioTrack) {
  LIVE_STOP = false;
  $sl().hidden = false; $('#cancel').hidden = false; $('#pause').hidden = false;
  const maxMin = Math.max(0, +SET.liveMaxMin || 0);
  const S = {
    t0: Date.now(), bytes: 0, segs: 0,
    stop() { return LIVE_STOP || (maxMin > 0 && Date.now() - this.t0 > maxMin * 60000); },
    tick() {
      const el = (Date.now() - this.t0) / 1000;
      const msg = 'Recording live - ' + fmtDur(el) + ' - ' + this.segs + ' segments - ' + fmtSize(this.bytes);
      status(msg + (maxMin ? ' (limit ' + maxMin + ' min)' : ''));
      progress(0, 0);
      setStats({ speed: 'LIVE', size: fmtSize(this.bytes), eta: maxMin ? fmtDur(Math.max(0, maxMin * 60 - el)) : '', seg: String(this.segs), sub: fmtDur(el) });
      report('run', 0, msg);
    }
  };
  status('Recording live stream... press "Stop & save" when you have enough.');
  let v, a = null;
  try {
    const jobs = [recordLive(plUrl, text, 'Live video', S)];
    if (audioTrack) jobs.push((async () => recordLive(audioTrack.uri, await (await fetchRetry(audioTrack.uri)).text(), 'Live audio', S))());
    [v, a] = await Promise.all(jobs);
  } catch (e) { LIVE_STOP = true; $sl().hidden = true; throw e; }
  $sl().hidden = true; $('#pause').hidden = true; $('#cancel').hidden = true;
  setState('run', 'Saving'); status('Saving the recording...'); progress(0, 0);
  const vk = plUrl + '#video', ak = audioTrack ? audioTrack.uri + '#audio' : '';
  let merged = null;
  if (a && SET.mergeAV !== false) {
    try { merged = await mergeAndSave(v, a, ''); } catch (e) { if (e && e.message === 'Cancelled') throw e; dbg('dl', 'live merge failed, saving video and audio as two files: ' + (e.message || e)); }
  }
  if (merged) SAVED[vk] = SAVED[ak] = merged;
  else {
    SAVED[vk] = await saveSegments(v, '', false);
    if (a) SAVED[ak] = await saveSegments(a, 'audio ' + audioTrack.name, true);
  }
  const mins = fmtDur((Date.now() - S.t0) / 1000);
  finish('Finished: live recording of ' + mins + ' saved' + (a && !merged ? ' (video and audio as two files)' : '') + '.' + (v.skipped ? ' ' + v.skipped + ' segment(s) were missed.' : ''));
}

async function runVideo(plUrl, text, audioTrack) {
  $('#options').textContent = '';
  $('#cancel').hidden = false; $('#pause').hidden = false;
  PLURL = plUrl;
  const pl = clipPl(parseMedia(text, plUrl));
  RUNNING = true; setState('run', 'Downloading');
  if (isLivePl(text) && !ALLMODE) return runLive(plUrl, text, audioTrack); // live stream: record it
  const vk = plUrl + '#video';
  const ak = audioTrack ? audioTrack.uri + '#audio' : '';
  const audioPl = async () => clipPl(parseMedia(await (await fetchRetry(audioTrack.uri)).text(), audioTrack.uri));
  if (audioTrack && SET.mergeAV !== false && !ALLMODE && !SAVED[vk] && !SAVED[ak]) {
    // separate audio track: download both, then join them into ONE mp4
    const v = await downloadSegments(pl, 'Video');
    cancelled = false;
    const a = await downloadSegments(await audioPl(), 'Audio');
    let merged = null;
    try { merged = await mergeAndSave(v, a, clipSuffix()); }
    catch (e) { if (e && e.message === 'Cancelled') throw e; dbg('dl', 'merge failed, saving video and audio as two files: ' + (e.message || e)); }
    if (merged) SAVED[vk] = SAVED[ak] = merged;
    else { SAVED[vk] = await saveSegments(v, clipSuffix(), false); SAVED[ak] = await saveSegments(a, 'audio ' + audioTrack.name, true); }
  }
  if (!SAVED[vk]) { const v = await downloadSegments(pl, 'Video'); SAVED[vk] = await saveSegments(v, clipSuffix(), false); }
  const files = [SAVED[vk]];
  if (audioTrack) {
    cancelled = false;
    if (!SAVED[ak]) {
      const a = await downloadSegments(await audioPl(), 'Audio');
      SAVED[ak] = await saveSegments(a, 'audio ' + audioTrack.name, true);
    }
    files.push(SAVED[ak]);
  }
  const uniq = [...new Set(files)];
  finish(uniq.length > 1 ? 'Finished: video and audio saved as two files.' : files.length > 1 ? 'Finished: video and audio merged into one MP4.' : 'Finished. Check your Downloads folder.');
  if (uniq.length > 1 && !ALLMODE) mergeHint(uniq.map((f) => f.split('/').pop()), uniq[0].includes('/') ? uniq[0].slice(0, uniq[0].lastIndexOf('/') + 1) : '');
}

// D4: HLS with a separate audio track: only the audio playlist is downloaded and saved as audio (no video data).
async function runAudioOnly(track) {
  $('#options').textContent = '';
  $('#cancel').hidden = false; $('#pause').hidden = false;
  PLURL = track.uri;
  RUNNING = true; setState('run', 'Downloading'); cancelled = false;
  const a = await downloadSegments(clipPl(parseMedia(await (await fetchRetry(track.uri)).text(), track.uri)), 'Audio');
  SAVED[track.uri + '#audio'] = await saveSegments(a, '', true);
  finish('Finished: audio saved. Check your Downloads folder.');
}

function startVariant(v, audios, all = []) {
  const fn = async () => {
    LAST = ALLMODE ? LAST : fn;
    try { readClip(); } catch (e) { status(e.message, true); return false; }
    // try the chosen quality first; if the server refuses its playlist, fall back to the nearest quality that works
    const order = ALLMODE ? [v] : [v, ...all.filter((x) => x !== v).sort((x, y) => Math.abs(x.bw - v.bw) - Math.abs(y.bw - v.bw))];
    let t = '', pick = null, lastErr = null;
    for (const c of order) {
      try { t = await (await fetchRetry(c.url, {}, c === v ? 4 : 2)).text(); pick = c; break; }
      catch (e) {
        lastErr = e;
        const nx = order[order.indexOf(c) + 1];
        if (nx) status(`${c.res ? c.res.split('x')[1] + 'p' : 'This quality'} refused (${e.message}). Trying ${nx.res ? nx.res.split('x')[1] + 'p' : 'another quality'}...`);
      }
    }
    if (!pick) { fail(lastErr || new Error('playlist not available')); return false; }
    if (pick !== v) status(`Chosen quality was refused by the server, downloading ${pick.res ? pick.res.split('x')[1] + 'p' : 'the next one'} instead.`);
    QL = qName(pick);
    QH = pick.res ? (parseInt(pick.res.split('x')[1], 10) || 0) + 'p' : '';
    if (QH === '0p') QH = '';
    document.title = QL + ' - ' + TITLE;
    try {
      const group = audios.filter((a) => a.group === pick.audio);
      const want = document.getElementById('wantAudio');
      const useAudio = ALLMODE ? ALLAUDIO : (want ? want.checked : true);
      const aOnly = AUDIO_ONLY(); // D4
      const track = (useAudio || aOnly) && group.length ? (group.find((a) => a.def) || group[0]) : null;
      if (aOnly && track && !isLivePl(t)) { await runAudioOnly(track); return true; }
      if (aOnly) HINT = 'This stream has no separate audio track, so the video was saved. Downloads > file menu > Extract audio (.m4a) makes the audio file.';
      await runVideo(pick.url, t, track);
      return true;
    } catch (e) { fail(e); return false; }
  };
  return fn;
}

async function runAll(variants, audios) {
  const wa = document.getElementById('wantAudio');
  const audioWanted = wa ? wa.checked : true;
  ALLMODE = true; ALLFINAL = false;
  LAST = () => runAll(variants, audios);
  const okList = [], bad = [];
  for (let i = 0; i < variants.length; i++) {
    if (userCancelled) break;
    cancelled = false; totalBytes = 0; SLOW = false;
    ALLAUDIO = audioWanted && i === 0; // audio track is the same for every quality: save it once
    const v = variants[i];
    status(`Quality ${i + 1} of ${variants.length}...`);
    const good = await startVariant(v, audios, variants)();
    (good ? okList : bad).push(v.res ? v.res.split('x')[1] + 'p' : (v.bw / 1e6).toFixed(1) + ' Mbps');
  }
  ALLFINAL = true;
  if (okList.length) finish(`Finished: ${okList.length} of ${variants.length} qualities saved (${okList.join(', ')}).` + (bad.length ? ' Failed: ' + bad.join(', ') + '.' : ''));
  else status('No quality could be downloaded.', true);
}

async function showVariants(text) {
  const { variants, media } = parseMaster(text, SRC);
  variants.sort((a, b) => b.bw - a.bw);
  const audios = media.filter((m) => m.type === 'AUDIO' && m.uri);
  if (AUTO) {
    const v = WANT === 'best' ? capH(variants) : (variants.find((x) => x.url === WANT) || variants[0]);
    return startVariant(v, audios, variants)();
  }
  status('Choose what to download');
  if (audios.length) {
    const lab = document.createElement('label'), cb = document.createElement('input');
    cb.type = 'checkbox'; cb.id = 'wantAudio'; cb.checked = true;
    lab.append(cb, SET.mergeAV !== false ? ' This stream has a separate audio track - download it too (joined into the video as one MP4 when possible)' : ' This stream has a separate audio track - download it too (saved as a second file)');
    $('#options').append(lab);
  }
  addClipInputs();
  // duration from one variant playlist, so every quality can show an estimated file size (bandwidth x duration)
  let dur = 0;
  for (const v of variants.slice(0, 3)) {
    try { dur = hlsInfo(await (await fetchRetry(v.url, {}, 2)).text(), v.url).dur || 0; if (dur) break; } catch (e) { /* try the next one */ }
  }
  addTitle('Video quality' + (dur ? ' (' + fmtDur(dur) + ' long)' : ''));
  let totalEst = 0;
  variants.forEach((v) => {
    const h = v.res ? parseInt(v.res.split('x')[1], 10) || 0 : 0;
    const [n, d] = qLabel(h, v.bw), sz = qSize(v, dur);
    if (v.bw && dur) totalEst += v.bw * dur / 8;
    const label = [n + ' ' + d, v.res || '', v.bw ? (v.bw / 1e6).toFixed(1) + ' Mbps' : '', sz ? '~' + sz : ''].filter(Boolean).join('  |  ');
    addBtn(label, startVariant(v, audios, variants), true);
  });
  if (variants.length > 1) addBtn(`Download ALL ${variants.length} qualities (separate files)` + (totalEst ? ' - ~' + fmtSize(Math.round(totalEst)) : ''), () => runAll(variants, audios));
  const subs = media.filter((m) => m.type === 'SUBTITLES' && m.uri);
  if (subs.length) {
    addTitle('Subtitles');
    subs.forEach((s) => addBtn(s.name + (s.lang ? ` (${s.lang})` : ''), () => runSubtitle(s.uri, false).catch(fail)));
  }
}

// ---------- plain file (needs page headers) ----------
const FST = { chunks: [], got: 0 };
async function downloadFile() {
  LAST = downloadFile; RUNNING = true; setState('run', 'Downloading');
  $('#cancel').hidden = false; $('#pause').hidden = false;
  const r = await fetchRetry(SRC, FST.got ? { headers: { Range: `bytes=${FST.got}-` } } : {});
  if (FST.got && r.status !== 206) { FST.chunks = []; FST.got = 0; } // server ignored the range: start over
  const len = parseInt(r.headers.get('content-length'), 10) || 0;
  const total = len ? FST.got + len : 0;
  const reader = r.body.getReader();
  const base = FST.got; t0 = Date.now();
  while (true) {
    await gate();
    const { done, value } = await reader.read();
    if (done) break;
    FST.chunks.push(value); FST.got += value.length; totalBytes = FST.got - base;
    progress(FST.got, total);
    const bps = totalBytes / ((Date.now() - t0) / 1000 || 1);
    const t = `${fmtSize(FST.got)}${total ? ' / ' + fmtSize(total) : ''} - ${fmtSpeed(bps)}`;
    status('Downloading: ' + t); report('run', total ? FST.got / total : 0, t);
    setStats({ speed: fmtSpeed(bps), size: fmtSize(FST.got), eta: total && bps ? fmtDur((total - FST.got) / bps) : '', seg: total ? Math.round(FST.got / total * 100) + '%' : '-', sub: 'File' });
  }
  await save(new Blob(FST.chunks), EXT, Q.get('kind') === 'audio' ? 'audio' : '');
  FST.chunks = []; FST.got = 0;
  finish('Finished. Check your Downloads folder.');
}

// ---------- DASH ----------
const kids = (el, n) => [...el.children].filter((c) => c.localName === n);
const kid = (el, n) => kids(el, n)[0];
function fill(tpl, v) {
  return tpl.replace(/\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\d+)d)?\$/g, (m, k, w) => {
    const x = String({ RepresentationID: v.id, Bandwidth: v.bw, Number: v.num, Time: v.time }[k]);
    return w ? x.padStart(+w, '0') : x;
  }).replace(/\$\$/g, '$');
}

function parseMpd(text, mpdUrl) {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  const mpd = doc.documentElement;
  if (mpd.getAttribute('type') === 'dynamic') throw new Error('Live DASH streams are not supported.');
  const period = kid(mpd, 'Period');
  if (!period) throw new Error('Invalid MPD');
  const baseOf = (el, parent) => { const b = kid(el, 'BaseURL'); return b ? new URL(b.textContent.trim(), parent).href : parent; };
  const pBase = baseOf(period, baseOf(mpd, mpdUrl));
  const pDur = isoDur(period.getAttribute('duration')) || isoDur(mpd.getAttribute('mediaPresentationDuration'));
  const reps = [];
  for (const as of kids(period, 'AdaptationSet')) {
    if (kid(as, 'ContentProtection')) throw new Error('DRM-protected stream (ContentProtection). It cannot be downloaded.');
    const aBase = baseOf(as, pBase);
    for (const r of kids(as, 'Representation')) {
      if (kid(r, 'ContentProtection')) throw new Error('DRM-protected stream. It cannot be downloaded.');
      const get = (n) => r.getAttribute(n) || as.getAttribute(n) || '';
      const mime = (get('mimeType') || get('contentType')).toLowerCase();
      const type = mime.startsWith('video') ? 'video' : mime.startsWith('audio') ? 'audio' : '';
      if (!type) continue;
      const rep = { id: r.getAttribute('id'), bw: parseInt(r.getAttribute('bandwidth'), 10) || 0, h: parseInt(get('height'), 10) || 0, lh: stdLabel(parseInt(get('width'), 10) || 0, parseInt(get('height'), 10) || 0, '', '', parseInt(r.getAttribute('bandwidth'), 10) || 0).lh, type, mime, lang: get('lang') };
      const rBase = baseOf(r, aBase);
      const tpl = kid(r, 'SegmentTemplate') || kid(as, 'SegmentTemplate');
      const list = kid(r, 'SegmentList') || kid(as, 'SegmentList');
      if (tpl) {
        const ts = parseInt(tpl.getAttribute('timescale'), 10) || 1;
        const start = tpl.getAttribute('startNumber') != null ? parseInt(tpl.getAttribute('startNumber'), 10) : 1;
        const media = tpl.getAttribute('media'), init = tpl.getAttribute('initialization');
        const segs = [];
        const tl = kid(tpl, 'SegmentTimeline');
        if (tl) {
          let t = 0, num = start;
          for (const s of kids(tl, 'S')) {
            if (s.getAttribute('t') != null) t = parseInt(s.getAttribute('t'), 10);
            const d = parseInt(s.getAttribute('d'), 10);
            let rep2 = parseInt(s.getAttribute('r') || '0', 10);
            if (rep2 < 0) rep2 = pDur ? Math.ceil((pDur * ts - t) / d) - 1 : 0;
            for (let i = 0; i <= rep2; i++) { segs.push(new URL(fill(media, { id: rep.id, bw: rep.bw, num, time: t }), rBase).href); t += d; num++; }
          }
        } else {
          const d = parseInt(tpl.getAttribute('duration'), 10);
          if (!d || !pDur) throw new Error('Cannot work out DASH segment count.');
          const n = Math.ceil(pDur / (d / ts));
          for (let i = 0; i < n; i++) segs.push(new URL(fill(media, { id: rep.id, bw: rep.bw, num: start + i, time: i * d }), rBase).href);
        }
        rep.init = init ? new URL(fill(init, { id: rep.id, bw: rep.bw, num: 0, time: 0 }), rBase).href : null;
        rep.segs = segs;
      } else if (list) {
        const ini = kid(list, 'Initialization');
        rep.init = ini && ini.getAttribute('sourceURL') ? new URL(ini.getAttribute('sourceURL'), rBase).href : null;
        rep.segs = kids(list, 'SegmentURL').map((s) => new URL(s.getAttribute('media'), rBase).href);
      } else rep.file = rBase;
      reps.push(rep);
    }
  }
  return reps;
}

// D3: prefer mp4 or webm video reps. Only a filter: no match = the list as it was. WebM + a separate audio rep is ignored (the join step writes mp4).
function preferBox(list, hasAudio) {
  const p = String((SET && SET.preferContainer) || 'any');
  if (p !== 'mp4' && p !== 'webm') return list;
  if (p === 'webm' && hasAudio && SET.mergeAV !== false) return list;
  const m = list.filter((r) => String(r.mime || '').includes(p));
  return m.length ? m : list;
}

// 0.0.353 hook: a single big file (DASH BaseURL, Bilibili) is read in pieces by bigfile.js; any failure / null / setting off = the old whole-file request below.
async function fileBlob(url, label) {
  try {
    if (SET.chunkFile !== false && typeof mgBigFile === 'function') { const b = await mgBigFile(url, label); if (b) return b; }
  } catch (e) { if (e && (e.message === 'Cancelled' || e.name === 'AbortError')) throw e; dbg('dl', 'pieces failed, whole file instead: ' + String((e && e.message) || e).slice(0, 100)); }
  try { if (SET.chunkFile !== false && typeof mgWholeFile === 'function') { const b = await mgWholeFile(url, label); if (b) return b; } } // 0.0.356: same whole-file request, but with progress; null = plain way below
  catch (e) { if (e && (e.message === 'Cancelled' || e.name === 'AbortError')) throw e; dbg('dl', 'whole file with progress failed: ' + String((e && e.message) || e).slice(0, 100)); }
  return await (await fetchRetry(url)).blob();
}

async function runDash(text) {
  $('#options').textContent = '';
  $('#cancel').hidden = false; $('#pause').hidden = false;
  const reps = parseMpd(text, SRC);
  let vids = reps.filter((r) => r.type === 'video').sort((a, b) => (b.h - a.h) || (b.bw - a.bw));
  const auds = reps.filter((r) => r.type === 'audio').sort((a, b) => b.bw - a.bw);
  vids = preferBox(vids, auds.length > 0); // D3: container preference (any = unchanged)
  const dashAudioOnly = AUDIO_ONLY() && !!auds[0]; // D4: only the audio representation is downloaded
  const v = dashAudioOnly ? null : ((WANT !== 'best' && vids.find((r) => r.id === WANT)) || capH(vids));
  const files = [];
  LAST = () => runDash(text); RUNNING = true; setState('run', 'Downloading');
  const skOf = (rep, suffix) => SRC + '#' + rep.id + suffix;
  let mergedAll = false;
  if (v && auds[0] && SET.mergeAV !== false && !SAVED[skOf(v, '')] && !SAVED[skOf(auds[0], 'audio')]) {
    // video and audio are separate representations: download both, then join them into ONE mp4
    const got = [];
    for (const [rep, suffix] of [[v, ''], [auds[0], 'audio']]) {
      cancelled = false;
      if (rep.file) { status('Downloading ' + (suffix || 'video') + '...'); got.push({ rep, suffix, blob: await fileBlob(rep.file, suffix ? 'Audio' : 'Video') }); }
      else got.push({ rep, suffix, r: await downloadSegments({ segs: rep.segs.map((u) => ({ url: u })), map: rep.init ? { url: rep.init, range: null } : null }, suffix || 'Video') });
    }
    let merged = null;
    try {
      const pcs = [];
      for (const g of got) pcs.push(g.blob ? [g.blob] : await piecesOf({ ...g.r, ext: 'mp4' }));
      status('Merging video and audio...');
      const t0 = Date.now();
      const out = await mergeMedia(pcs[0], pcs[1], async (f) => { convProgress('Merging video and audio...', f, t0); await sleep(0); await gate(); });
      merged = await save(out, 'mp4', '');
      for (const g of got) if (g.r) { dropWhenSaved(g.r.dk, LAST_DL_ID); if (g.r.rk) { delete RES[g.r.rk]; delete RES[g.r.rk + 'init']; } }
    } catch (e) { if (e && e.message === 'Cancelled') throw e; dbg('dl', 'merge failed, saving video and audio as two files: ' + (e.message || e)); }
    if (merged) { SAVED[skOf(v, '')] = SAVED[skOf(auds[0], 'audio')] = merged; mergedAll = true; }
    else for (const g of got) {
      const sk = skOf(g.rep, g.suffix);
      SAVED[sk] = g.blob ? await save(g.blob, g.rep.type === 'audio' ? 'm4a' : 'mp4', g.suffix) : await saveSegments({ ...g.r, ext: 'mp4' }, g.suffix, g.rep.type === 'audio');
    }
  }
  for (const [rep, suffix] of [[v, ''], [auds[0], dashAudioOnly ? '' : 'audio']]) {
    if (!rep) continue;
    cancelled = false;
    const sk = SRC + '#' + rep.id + suffix;
    if (!SAVED[sk]) {
      if (rep.file) {
        status('Downloading ' + (suffix || 'video') + '...');
        SAVED[sk] = await save(await fileBlob(rep.file, rep.type === 'audio' ? 'Audio' : 'Video'), rep.type === 'audio' ? 'm4a' : 'mp4', suffix);
      } else {
        const r = await downloadSegments({ segs: rep.segs.map((u) => ({ url: u })), map: rep.init ? { url: rep.init, range: null } : null }, suffix || 'Video');
        SAVED[sk] = await saveSegments({ ...r, ext: 'mp4' }, suffix, rep.type === 'audio');
      }
    }
    files.push(SAVED[sk]);
  }
  const uniq = [...new Set(files)];
  finish(uniq.length > 1 ? 'Finished: video and audio saved as two files.' : mergedAll || files.length > 1 ? 'Finished: video and audio merged into one MP4.' : 'Finished. Check your Downloads folder.');
  if (uniq.length > 1) mergeHint(uniq.map((f) => f.split('/').pop()), uniq[0].includes('/') ? uniq[0].slice(0, uniq[0].lastIndexOf('/') + 1) : '');
}

// ---------- control ----------
function setPausedUi() {
  const p = $('#pause');
  p.innerHTML = (paused ? ICO.play : ICO.pause) + '<span>' + (paused ? 'Resume' : 'Pause') + '</span>';
  if (RUNNING || paused) setState(paused ? 'paused' : 'run', paused ? 'Paused' : 'Downloading');
}
function doCancel(silent) {
  cancelled = true; userCancelled = true; paused = false;
  try { ABORT.abort(); } catch (e) { /* ignore */ }
  if (!silent) status('Cancelling...');
}
$('#cancel').onclick = () => { doCancel(); report('error', 0, 'Cancelled'); };
function setPaused(v) {
  paused = v; setPausedUi();
  status(paused ? 'Pausing - finishing the parts already on the way...' : 'Resumed');
  if (KEY) chrome.runtime.sendMessage({ type: 'job-update', tab: SRC_TAB, key: KEY, state: 'run', pct: -1, text: paused ? 'Paused' : 'Resumed' }).catch(() => {});
}
$('#pause').onclick = () => setPaused(!paused);
$sl().onclick = () => { LIVE_STOP = true; $sl().hidden = true; status('Stopping - saving what was recorded...'); };
chrome.runtime.onMessage.addListener((m) => { if (m && m.type === 'stop-job' && m.key === KEY) { LIVE_STOP = true; $sl().hidden = true; } });
setPausedUi();
chrome.runtime.onMessage.addListener((m) => { if (m && m.type === 'pause-job' && m.key === KEY) setPaused(!!m.on); });
chrome.runtime.onMessage.addListener((m) => { if (m && m.type === 'cancel-job' && m.key === KEY) { doCancel(true); status('Cancelled', true); report('error', 0, 'Cancelled'); } });

(async function main() {
  $('#title').textContent = TITLE;
  SET = await getSettings();
  Disk.purge(); // forget leftovers of abandoned downloads
  // keep this tab from being frozen while it works in the background
  try { navigator.locks.request('media-grabber-job', () => new Promise(() => {})); } catch (e) { /* optional */ }
  await setupHeaders();
  await setupRecordedHeaders();
  try {
    if (MODE === 'file') return await downloadFile();
    if (MODE === 'subtitle') return await runSubtitle(SRC, true);
    const text = await (await fetchRetry(SRC)).text();
    if (MODE === 'dash' || /<MPD\b/.test(text)) { LAST = () => runDash(text); return await runDash(text); }
    if (!text.includes('#EXTM3U')) throw new Error('This is not an HLS playlist.');
    if (text.includes('#EXT-X-STREAM-INF')) return showVariants(text);
    LAST = () => runVideo(SRC, text, null);
    if (Q.get('manual') === '1' && !isLivePl(text)) {
      status('Choose a part to download, or leave it empty for the whole video');
      addClipInputs();
      addBtn('Download', () => { try { readClip(); } catch (e) { return status(e.message, true); } LAST().catch(fail); }, true);
      return;
    }
    await runVideo(SRC, text, null);
  } catch (e) { fail(e); }
})();
