// Shared helpers: used by background.js (importScripts), content.js, popup.js, options.js, downloader.js

const DEFAULTS = {
  enabled: true,
  showPill: true,          // floating button with item count
  showOverlay: true,       // button on top of <video> players
  mgbSiteModes: {},        // MGB S57: per-site button mode { host: 0 smart | 1 grabber only | 2 native only }
  nativePanel: true,       // MGB S51: tapping the page button opens the native Files detected! panel (not for manga / episode pages)
  pillSide: 'right',
  pillTop: 45,             // % from top
  overlayCorner: 'tl',     // tl | tr
  minSizeKB: 50,           // ignore tiny media (tracking pixels, beeps)
  nameTemplate: '{title}', // {title} {site} {name} {res} {date} {time} {kind} {lang}
  subfolder: '',           // inside the browser's Downloads folder; may use {series} {season} {site} ...
  libraryMode: 'off',      // off | plex: episodes become  Show/Season 01/Show - S01E05.ext  (Plex / Jellyfin / Emby / Kodi naming)
  bgDownload: true,        // run stream downloads in a hidden background document: no extra tab, closing tabs cannot stop them
  jobWindow: 'tab',        // tab = background tab, window = small popup window
  autoClose: true,         // close the job tab when finished
  concurrency: 6,
  maxJobs: 2,              // stream jobs running at the same time (Download all queue)
  extraExts: '',           // extra video extensions, comma separated
  siteRules: '',           // one per line: domain = css selector of the page images
  requestDelayMs: 0,       // pause between page/chapter requests (some sites block fast scrapers)
  maxHeight: 0,            // preferred max video height for "best" (0 = highest available)
  audioOnly: false,        // D4: streams are saved as audio only when they carry a separate audio track (DASH / HLS); off = as before
  batchOrganize: false,    // D5: batch items go in a sub-folder named after the show / page, file name gets "01 - " (Plex naming wins)
  subDelayMs: 0,           // 0.0.351 (V82): move every SAVED subtitle by this many ms (+ later / - earlier). 0 = off = nothing changes
  preferContainer: 'any', // D3: DASH video pick: any | mp4 | webm (a preference between equal heights, never a conversion)
  notify: true,            // desktop notification when downloads finish
  blockAds: true,          // ignore ad videos / ad banners / sponsored links
  mergeAV: true,           // video + separate audio -> ONE mp4 (re-packaged, not re-encoded)
  convertMp4: true,        // TS -> MP4 when lib/mux.min.js is present
  autoSubs: true,          // download the page's subtitles next to a video, same file name (.en.srt)
  subFmt: 'srt',           // V72: format of automatically saved subtitles: srt | vtt | txt | orig (original file)
  subLangs: 'en',          // preferred subtitle languages for auto-download, comma separated (en, bn, es ...). Empty = first 5 found
  subCheck: true,          // V73: look INSIDE each subtitle file (language + preview/thumbnail track) before saving it, and try the next one when it is the wrong one
  subFallback: 'any',      // V73: when none of the wanted languages is found: any = save the best other one | none = save nothing
  cleanTitles: true,       // V73: remove the website name from file names / series names ("Site - Naruto Episode 5" -> "Naruto Episode 5")
  titleStrip: '',          // V73: extra site names / words to remove from titles, comma separated
  titleSource: 'auto',     // V73: where the series name is read first: auto | page (tab title) | schema (page data) | h1 (big heading). The others stay as fallbacks
  batchParallel: 1,        // episode batch: how many episodes are searched at the same time (1-4)
  batchRetryMiss: true,    // episode batch: second, longer try for episodes whose video was not found
  autoNetRetry: true,      // V69: when the connection comes back, retry (once) the batch episodes that failed only because of the connection (Downloads page open)
  dupAsk: true,            // V71: ask "Download again?" when you download something that is already saved (and its file is still on the disk)
  siteMemory: true,        // V67: remember per site which way found the video (page HTML / player tab / tab in front) and start with it next time
  fullTitles: false,       // show long titles in full (wrapped) instead of cutting them with ...
  sniffSeconds: 25,        // episode batch: how long to wait for the player of each episode (tab sniffing)
  skipDone: true,          // skip chapters already downloaded when several are selected
  followHours: 6,          // check followed manga for new chapters every N hours (0 = off)
  convertMaxMB: 0,         // HLS .ts bigger than this many MB is saved as .ts without the (slow) MP4 conversion; 0 = always convert
  minDurationSec: 0,       // hide HLS/DASH streams shorter than this many seconds (ads, previews). 0 = off
  liveMaxMin: 120,         // live HLS recording: stop and save after this many minutes (0 = until you press Stop)
  siteModules: true,       // site modules: TikTok, Douyin, Vimeo, SoundCloud, Bandcamp (siteHook.js) + Bilibili, iQIYI, Facebook, Instagram, TwitCasting, Showroom, CNTV, Weverse (siteMax.js, V82) readers + Telegram Web button
  directToNative: false,   // 0.0.369 (N1): a single plain http(s) file is fetched by the app's native downloader (disk, resume, parts). Off = the old engine download (default)
  chunkFile: true,         // 0.0.353: a single big file (Bilibili DASH video / audio) is downloaded in 4 MB pieces, each with its own retry. Off = the old one-request download
  partsN: 1,               // 0.0.360: big single files: 2..16 = that many 4 MB pieces are fetched at the same time (first stage of bigfile.js). 1 = off (pieces one after the other, as 0.0.353)
  siteMaxFetch: true,      // 0.0.348: Facebook / Instagram reader reads the page's own HTML again after an address change (one same-origin request, min 1.5 s apart). Off = only the HTML already in the page
  msOffSites: 'ok.ru',     // sites where MediaSource is switched off so the player falls back to a downloadable URL (one domain per line)
  speedLimitKBps: 0,       // bandwidth limit for stream downloads in KB/s per download (0 = unlimited)
  theme: 'auto',           // auto | dark | light (settings, history, pages screen, reader)
  siteAdapters: true,      // site adapters: hitomi.la, MangaDex, nhentai page lists + generic script scan (fallbacks stay on either way)
  padChapters: true,       // "Chapter 5" -> "Chapter 005" in file names so they sort correctly
  siteFormats: '',         // one per line: domain = cbz|zip|pdf|epub   (default format for that site)
  disabledSites: []
};

// Gallery pages: a grid of small thumbnails, each linking to a numbered viewer page (/g/ID/1/, /gallery/ID/page/2 ...).
// paths = pathname of each candidate's link. Returns the indexes that form the biggest consecutive 1..N run.
const VIEWER_PATH = /^(.*\/)(?:page[-_]?)?(\d{1,4})\/?$/i;
function galleryRun(paths) {
  const groups = new Map();
  paths.forEach((p, i) => {
    const m = VIEWER_PATH.exec(p || '');
    if (!m || !/\/(g|gallery|galleries|viewer|view|read|reader|online|p|doujin|manga)\//i.test(m[1])) return;
    if (!groups.has(m[1])) groups.set(m[1], []);
    groups.get(m[1]).push({ i, n: +m[2] });
  });
  let best = [];
  groups.forEach((arr) => {
    const nums = new Set(arr.map((x) => x.n));
    if (arr.length >= 3 && nums.size >= 3 && nums.has(1) && nums.has(2) && nums.has(3) && arr.length > best.length) best = arr;
  });
  const seen = new Set();
  return best.filter((x) => (seen.has(x.n) ? false : seen.add(x.n))).map((x) => x.i);
}

// How many pages does the gallery say it has? ("44 pages", "Pages: 44"). Only meant for the small info block of a page, never the whole body.
function expectedFromText(t) {
  t = String(t || '').replace(/\s+/g, ' ');
  const m = /(?:^|\D)(\d{1,4})\s*pages?\b/i.exec(t) || /\bpages?\s*[:\-]?\s*(\d{1,4})\b/i.exec(t);
  const n = m ? +m[1] : 0;
  return n >= 3 && n <= 2000 ? n : 0;
}
// Viewer links of the thumbnails that ARE on the page (.../g/ID/1/ .. /19/) + the real total  ->  the viewer links of the pages that are missing.
// Returns [{ n, url }] sorted by n. Nothing when the links do not look like numbered pages or nothing is missing.
const VIEWER_FILL = /^(.*\/(?:page[-_]?)?)(\d{1,4})(\/?)$/i;
function galleryFill(urls, total) {
  total = +total || 0;
  if (!total || !urls || urls.length < 3) return [];
  const groups = new Map();
  for (const u of urls) {
    let x; try { x = new URL(u); } catch (e) { continue; }
    const m = VIEWER_FILL.exec(x.pathname); if (!m) continue;
    const key = x.origin + m[1] + '|' + m[3] + '|' + x.search;
    if (!groups.has(key)) groups.set(key, { origin: x.origin, pre: m[1], slash: m[3], search: x.search, nums: new Set() });
    groups.get(key).nums.add(+m[2]);
  }
  let best = null;
  groups.forEach((g) => { if (!best || g.nums.size > best.nums.size) best = g; });
  if (!best || best.nums.size < 3 || !best.nums.has(1) || total > 2000 || total < best.nums.size) return [];
  const out = [];
  for (let n = 1; n <= total; n++) if (!best.nums.has(n)) out.push({ n, url: best.origin + best.pre + n + best.slash + best.search });
  return out;
}
// the number of a viewer link (.../19/ -> 19), 0 when it has none
function viewerNum(u) { try { const m = VIEWER_FILL.exec(new URL(u).pathname); return m ? +m[2] : 0; } catch (e) { return 0; } }

// ---------- chapter links from raw HTML (service worker has no DOMParser) ----------
const CH_RE_BG = /(chapter|chap\.?|ch\.?|episode|ep\.?|part|vol(?:ume)?\.?|\u8A71|\u8BDD|\u7AE0)\s*[-_.:#]?\s*\d+/i;
const CH_HREF_BG = /\/(chapter|chap|ch|episode|ep|read|viewer|view)[-_/]*\d+|[-_/](chapter|chap|ch|episode|ep)[-_]?\d+/i;
function chaptersFromHtml(html, base) {
  const out = [], seen = new Set(), here = base.split('#')[0];
  let host = ''; try { host = new URL(base).hostname; } catch (e) { return out; }
  const dec = (x) => x.replace(/&amp;/g, '&').replace(/&#39;|&apos;/g, "'").replace(/&quot;/g, '"').replace(/&nbsp;/g, ' ');
  html.replace(/<a\b[^>]*?\bhref=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi, (_, h, inner) => {
    if (out.length >= 800) return '';
    let u; try { u = new URL(dec(h), base); } catch (e) { return ''; }
    if (!/^https?:/.test(u.protocol) || u.hostname !== host) return '';
    const hh = u.href.split('#')[0];
    if (seen.has(hh) || hh === here) return '';
    const txt = dec(inner.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim().slice(0, 90);
    if (!(CH_RE_BG.test(txt) || CH_HREF_BG.test(u.pathname)) || isAdUrl(hh)) return '';
    seen.add(hh);
    out.push({ url: hh, label: txt || decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || hh) });
    return '';
  });
  return out;
}

// ---------- friendly failure hints ----------
function diagnose(t) {
  t = String(t || '');
  const rules = [
    [/DRM/i, 'DRM-protected: cannot be downloaded.'],
    [/\b40[13]\b|FORBIDDEN|UNAUTHORIZED/i, 'Blocked by the site (403/401). Log in, reload the page, play the video again, then retry - the link/token probably expired.'],
    [/\b(429|503)\b|too many/i, 'The site is rate-limiting you. Raise "Delay between requests" in Settings (e.g. 800) and retry.'],
    [/\b(404|410)\b|NOT_FOUND/i, 'The link is gone (404/410). Reload the page and grab it again.'],
    [/cloudflare|just a moment|challenge/i, 'Cloudflare check. Open the site normally, pass the check, then retry.'],
    [/not an image|BAD_CONTENT/i, 'The server sent something that is not the file (login or error page?). Open the link in a tab to check.'],
    [/NETWORK|Failed to fetch|timeout|aborted/i, 'Network problem or timeout. Check your connection / VPN and retry.'],
    [/QUOTA|DISK|FULL/i, 'Disk full or blocked by the browser. Free some space.']
  ];
  for (const [re, h] of rules) if (re.test(t)) return h;
  return '';
}

// ---------- debug log (chrome.storage.local, last 200 lines; shown in Settings) ----------
let _dbgChain = Promise.resolve();
function dbg(area, msg) {
  _dbgChain = _dbgChain.then(async () => {
    const r = (await chrome.storage.local.get('mgLog')).mgLog || [];
    r.push(new Date().toISOString().slice(5, 19).replace('T', ' ') + ' [' + area + '] ' + String(msg).slice(0, 400));
    await chrome.storage.local.set({ mgLog: r.slice(-200) });
  }).catch(() => {});
}

// S89.0: log line with a stable code. Diagnose rules read "code=<id>" (e.g. code=403-blocked), never the English text after it.
// Format: "<time> [area] code=<id> <detail>". Same area+code more than 5 times in 10 s is collapsed into one "repeats=N" line (a bad chapter must not push every other line out of the 200 line log).
const _dbgcSeen = new Map();
function mgCode(c) { return String(c == null ? '' : c).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'unknown'; }
// S89.1: HTTP status -> stable code used by the manga logs and the rules (401 / 403 / 404 / 429 / 5xx, anything else "http-NNN")
function mgHttpCode(st) {
  st = +st || 0;
  if (st === 401) return '401-login';
  if (st === 403) return '403-blocked';
  if (st === 404 || st === 410) return '404-gone';
  if (st === 429) return '429-slow-down';
  if (st >= 500 && st < 600) return '5xx-server';
  return 'http-' + st;
}
function dbgc(area, code, detail) {
  const c = mgCode(code), k = area + '|' + c, now = Date.now();
  let e = _dbgcSeen.get(k);
  if (!e || now - e.t > 10000) {
    if (e && e.skip > 0) dbg(area, 'code=' + c + ' repeats=' + e.skip);
    e = { t: now, n: 0, skip: 0 };
    _dbgcSeen.set(k, e);
    if (_dbgcSeen.size > 100) _dbgcSeen.delete(_dbgcSeen.keys().next().value);
  }
  if (++e.n > 5) { e.skip++; return; }
  dbg(area, 'code=' + c + (detail ? ' ' + String(detail) : ''));
}

// ---------- theme (auto/dark/light) for extension pages ----------
function applyTheme(t) {
  const dark = t === 'dark' || (t !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
}
const TX_SEL = '.nm,.name,#now,.it .n,.jn > span:first-child';
function applyFullTitles(on) { try { document.documentElement.classList.toggle('tx-all', !!on); } catch (e) { /* ignore */ } }
// V70: small accessibility helper for the extension's own pages (never runs inside web pages). Adds a screen-reader name to icon-only buttons / selects / inputs
// from their tooltip or placeholder, and a page language. Only adds missing attributes; never changes layout or behaviour.
function initA11y() {
  try {
    if (!/^(chrome|moz)-extension:$/.test(location.protocol)) return;
    if (!document.documentElement.lang) document.documentElement.lang = 'en';
    let t = 0;
    const run = () => {
      t = 0;
      document.querySelectorAll('button[title]:not([aria-label]),select[title]:not([aria-label]),a[title]:not([aria-label])').forEach((e) => { if (!(e.textContent || '').trim() || e.tagName === 'SELECT') e.setAttribute('aria-label', e.getAttribute('title')); });
      document.querySelectorAll('input[placeholder]:not([aria-label])').forEach((e) => { if (!e.labels || !e.labels.length) e.setAttribute('aria-label', e.getAttribute('placeholder')); });
    };
    const later = () => { if (!t) t = setTimeout(run, 250); };
    run();
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) { /* ignore */ }
}

async function initTheme() {
  initA11y();
  try { const s = await getSettings(); applyTheme(s.theme); applyFullTitles(s.fullTitles); } catch (e) { /* ignore */ }
  try { // tap / click a title to read it in full (tap again to shorten)
    document.addEventListener('click', (e) => {
      const t = e.target && e.target.closest && e.target.closest(TX_SEL);
      if (t && !e.target.closest('button,a,input,select,textarea,label,summary')) t.classList.toggle('tx-open');
    });
  } catch (e) { /* ignore */ }
  try { chrome.storage.onChanged.addListener((c, a) => { if (a === 'sync' && c.fullTitles) applyFullTitles(c.fullTitles.newValue); }); } catch (e) { /* ignore */ }
  try { chrome.storage.onChanged.addListener((c, a) => { if (a === 'sync' && c.theme) applyTheme(c.theme.newValue || 'auto'); }); } catch (e) { /* ignore */ }
}

// ---------- chapter numbers: "Chapter 5" -> "Chapter 005" ----------
function padNumbers(label, width = 3) {
  return String(label || '').replace(/(\d+)(\.\d+)?/g, (m, a, b) => (a.length < width && a.length <= 4 ? a.padStart(width, '0') : a) + (b || ''));
}

// "example.com = pdf" lines -> format for this host (or '')
function siteFormat(rules, host) {
  for (const l of String(rules || '').split('\n')) {
    const i = l.indexOf('=');
    if (i < 0) continue;
    const d = l.slice(0, i).trim().toLowerCase(), f = l.slice(i + 1).trim().toLowerCase();
    if (d && ['cbz', 'zip', 'pdf', 'epub'].includes(f) && (host === d || host.endsWith('.' + d))) return f;
  }
  return '';
}

async function getSettings() {
  const s = await chrome.storage.sync.get(null);
  return { ...DEFAULTS, ...s };
}

// ---------- small utils ----------
function sanitize(name, max = 100) {
  return (name || 'media')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max)
    .replace(/[. ]+$/, '') || 'media';
}

function extOf(url) {
  try {
    const m = new URL(url).pathname.match(/\.([a-z0-9]{2,5})$/i);
    return m ? m[1].toLowerCase() : '';
  } catch (e) { return ''; }
}

function baseName(url) {
  try {
    const p = new URL(url).pathname.split('/').filter(Boolean).pop() || new URL(url).hostname;
    return decodeURIComponent(p);
  } catch (e) { return url; }
}

function hostOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
}

// Crash recovery of live recordings: the recorder keeps a small meta.json beside the segments it saved on disk.
// Given that meta and the file names found in the folder, say what a recovered file would be (or null = nothing usable).
function liveRecoverPlan(meta, names) {
  if (!meta || meta.live !== true || !Array.isArray(names)) return null;
  const segs = names.filter((n) => /^\d+$/.test(n)).sort((a, b) => +a - +b);
  if (!segs.length) return null;
  let ext = ['ts', 'mp4', 'aac', 'mp3'].includes(meta.ext) ? meta.ext : 'ts';
  const audio = /audio/i.test(meta.label || '');
  if (audio && ext === 'mp4') ext = 'm4a';
  const init = ext === 'mp4' || ext === 'm4a' ? names.includes('init') : false;
  const stamp = new Date(meta.t0 || 0).toISOString().slice(0, 16).replace('T', ' ').replace(':', '-');
  const name = sanitize((meta.title || 'live recording') + ' (recovered ' + stamp + (audio ? ' audio' : '') + ')') + '.' + ext;
  return { ext, init, segs, name, mime: ext === 'ts' ? 'video/mp2t' : ext === 'aac' ? 'audio/aac' : ext === 'mp3' ? 'audio/mpeg' : ext === 'm4a' ? 'audio/mp4' : 'video/mp4' };
}

function fmtSize(n) {
  if (!n) return '';
  const u = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i > 1 ? 1 : 0) + ' ' + u[i];
}

function fmtDur(s) {
  s = Math.round(s || 0);
  if (!s) return '';
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(x)}` : `${m}:${p(x)}`;
}

function fmtSpeed(bps) {
  return bps ? fmtSize(bps) + '/s' : '';
}

// ---------- file names ----------
// What the background knows about this tab's jobs -> what the on-page panel shows. Page messages can get lost (page reloaded /
// navigated, service worker restarted), so the panel asks now and then; a newer or a final (done / error) answer wins.
function mergeJobSnapshot(local, snap, now) {
  for (const s of snap || []) {
    if (!s || !s.key) continue;
    const l = local[s.key];
    const fin = s.state === 'done' || s.state === 'error';
    if (l && (s.updated || 0) <= (l.t || 0) && !(fin && l.state === 'run')) continue;
    local[s.key] = { state: s.state, pct: s.pct != null ? s.pct : (l && l.pct), text: s.text, paused: !!s.paused, title: s.title || (l && l.title), kind: s.kind || (l && l.kind), t: s.updated || now };
  }
  return local;
}

// ---------- episode batch helpers (pure, unit-tested) ----------
// Page title -> series name: "Watch Quiet Please! Episode 5 English Subbed/Dubbed - Site" -> "Quiet Please!"
// (the episode of the page you happen to be on must not end up in every file name)
function seriesName(title) {
  const orig = String(title || '').replace(/\s+/g, ' ').trim();
  let t = orig.replace(/\s+[-\u2013\u2014\u2022\u00B7\u00BB]\s+.*$/, '').replace(/\s*\|.*$/, ''); // "Show - Site" / "Show | Site" (a dash inside a word stays)
  const cut = t.replace(/[\s:,\-\u2013\u2014(\[]*\b(?:episode|eps?\.?|chapter|chap\.?|ch\.?|\u00E9pisode|folge|cap[i\u00ED]tulo)\s*#?\d+(?:\.\d+)?\b.*$/i, '');
  if (cut.trim()) t = cut;
  t = t.replace(/^(?:watch|read|download|stream)\s+/i, '')
       .replace(/[\s(\[]*\b(?:english\s*)?(?:sub(?:bed|titled)?|dub(?:bed)?)(?:\s*[\/&,]\s*(?:sub(?:bed|titled)?|dub(?:bed)?))*\b[\s)\]]*$/i, '')
       .replace(/\s*\b(?:online|free|hd|full)\b\s*$/i, '')
       .replace(/[\s:,\-\u2013\u2014|]+$/, '').trim();
  return t || orig;
}

// "5-12, 15, 20-" -> [5..12, 15, 20..max]. An open end (20-) needs max. Returns [] when nothing valid is in the text.
function parseNumList(text, max) {
  const out = new Set();
  const norm = String(text || '').replace(/\s*(?:\u2013|\u2014|\bto\b)\s*/gi, '-').replace(/\s*-\s*/g, '-');
  for (const part of norm.split(/[,;\s]+/).filter(Boolean)) {
    const m = /^(\d+(?:\.\d+)?)?(?:-|\.\.)(\d+(?:\.\d+)?)?$/.exec(part);
    if (m && (m[1] || m[2])) {
      const a = m[1] != null ? +m[1] : 1, b = m[2] != null ? +m[2] : max;
      if (b == null || !isFinite(b) || b - a > 2000) continue;
      for (let n = Math.floor(a); n <= Math.floor(b); n++) out.add(n);
    } else if (/^\d+(?:\.\d+)?$/.test(part)) out.add(+part);
  }
  return [...out].sort((x, y) => x - y);
}
// the episode / chapter number of a list entry: "Episode 12", "Ep.5 - Title", "S2E7" -> 12, 5, 7
function epNumOf(label, url) {
  const L = String(label || '');
  let m = /\bS\d+\s*E(\d+)/i.exec(L) || /(?:episode|ep\.?|chapter|chap\.?|ch\.?|#)\s*[-_.:]?\s*(\d+(?:\.\d+)?)/i.exec(L);
  if (m) return +m[1];
  m = L.match(/(\d+(?:\.\d+)?)(?!.*\d)/);
  if (m) return +m[1];
  try {
    const pth = decodeURIComponent(new URL(url).pathname).replace(/\.(m3u8|mpd|mp4|webm|mkv|html?|php)$/i, '');
    m = pth.match(/(?:episode|ep|chapter|chap|ch)[-_/]*(\d+(?:\.\d+)?)/i) || pth.match(/(\d+(?:\.\d+)?)(?!.*\d)/);
  } catch (e) { m = null; }
  return m ? +m[1] : null;
}
// "https://x/anime-episode-{n}" + [1,2,3] -> three URLs ({nn} / {nnn} = zero padded)
function expandPattern(line, nums) {
  return nums.map((n) => line.replace(/\{(n{1,4})\}/g, (m, k) => String(n).padStart(k.length, '0')));
}
// the URL of an episode page -> pattern with {n} where the number is, and that number: "https://x/ep-5/" -> {pattern, n:5}
function patternFromUrl(url) {
  let u; try { u = new URL(url); } catch (e) { return null; }
  // some sites put a random code after the number (/ep-5-ad7134): the episode is 5, the code is not a number to count with
  const hm = /((?:episode|ep|chapter|chap|ch)[-_]?)(\d+(?:\.\d+)?)([-_](?=[a-z0-9]*\d)(?=[a-z0-9]*[a-z])[a-z0-9]{3,10})\/?$/i.exec(u.pathname);
  if (hm) {
    const cut = hm.index + hm[1].length;
    return { pattern: u.origin + u.pathname.slice(0, cut) + '{n}' + hm[3][0] + '{h}' + u.search, n: +hm[2], hashed: true };
  }
  const re = /(\d+)(?!.*\d)/; // last number of the path (then of the query)
  let m = re.exec(u.pathname);
  if (m) { const i = m.index; return { pattern: u.origin + u.pathname.slice(0, i) + '{' + 'n'.repeat(m[1].length > 1 && m[1][0] === '0' ? m[1].length : 1) + '}' + u.pathname.slice(i + m[1].length) + u.search, n: +m[1] }; }
  m = re.exec(u.search);
  if (m) { const i = m.index; return { pattern: u.origin + u.pathname + u.search.slice(0, i) + '{n}' + u.search.slice(i + m[1].length), n: +m[1] }; }
  return null;
}
// season for media-server names (Plex / Jellyfin: "Show - S02E05"): "S2E5", "Season 2", "2nd Season", "Show 2"
function seasonOf(book, chapter) {
  const t = String(chapter || '') + ' | ' + String(book || '');
  let m = /\bS(\d{1,2})\s*E\d+/i.exec(t) || /\bseason\s*(\d{1,2})\b/i.exec(t) || /\b(\d{1,2})(?:st|nd|rd|th)\s*season\b/i.exec(t) || /\bs(\d{1,2})\b/i.exec(String(book || ''));
  return m ? +m[1] : 1;
}
function sxeOf(book, chapter) {
  const n = epNumOf(chapter, '');
  if (n == null) return '';
  const e = Math.floor(n), frac = n % 1 ? String(n).slice(String(n).indexOf('.')) : '';
  return 'S' + String(seasonOf(book, chapter)).padStart(2, '0') + 'E' + String(e).padStart(e >= 100 ? 3 : 2, '0') + frac;
}

function applyTemplate(tpl, v) {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const map = {
    title: v.title, site: v.site, name: v.name, kind: v.kind, res: v.res, lang: v.lang,
    book: v.book || '', chapter: v.chapter || '', series: seriesBase(v.book || v.title),
    episode: v.chapter ? padNumbers((String(v.chapter).match(/(\d+(?:\.\d+)?)(?!.*\d)/) || ['', ''])[1]) : '',
    index: v.index ? String(v.index).padStart(3, '0') : '',
    season: v.chapter || v.book ? String(seasonOf(v.book, v.chapter)).padStart(2, '0') : '', sxe: sxeOf(v.book, v.chapter),
    date: `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`,
    time: `${p(d.getHours())}-${p(d.getMinutes())}-${p(d.getSeconds())}`
  };
  return tpl.replace(/\{(\w+)\}/g, (m, k) => (map[k] == null ? '' : map[k]));
}

// Show name without a trailing season marker: "Show Season 2" / "Show 2nd Season" / "Show S2" -> "Show"
function seriesBase(t) {
  const n = seriesName(String(t || '').replace(/[\s._-]*\bS\d{1,2}\s*E\d+.*$/i, '')).replace(/[\s:,\-\u2013\u2014]*\b(?:season\s*\d{1,2}|\d{1,2}(?:st|nd|rd|th)\s*season|s\d{1,2})\s*$/i, '').trim();
  return n || seriesName(t);
}

// Library (Plex / Jellyfin / Emby / Kodi) naming: Show/Season 01/Show - S01E05. Returns null when the item is not clearly an
// episode (movies, clips, anything without an episode number) so those keep the normal naming.
function libraryPlan(v) {
  const src = String(v.chapter || v.title || '');
  if (!src) return null;
  if (!v.chapter && !/\bS\d{1,2}\s*E\d+|\b(?:episode|ep\.?|chapter|chap\.?|ch\.?)\s*#?\s*\d|#\s*\d/i.test(src)) return null;
  const n = epNumOf(src, '');
  if (n == null) return null;
  const series = sanitize(seriesBase(v.book || v.title), 80);
  if (!/[^\W\d_]/.test(series.replace(/\b(?:episode|ep|chapter|chap|ch)\b/gi, ''))) return null; // no real show name ("Episode 4"): keep normal naming
  const sxe = sxeOf(v.book || '', src);
  if (!sxe) return null;
  const season = seasonOf(v.book || '', src);
  return { folder: series + '/Season ' + String(season).padStart(2, '0'), base: series + ' - ' + sxe };
}

// settings + info -> "Folder/Name.suffix.ext"
function buildFileName(S, v, ext) {
  const lib = S.libraryMode === 'plex' && !['pages', 'image', 'audio'].includes(v.kind) ? libraryPlan(v) : null;
  // D5: batch organizer (OFF by default). Needs an index + a show / page name, so single downloads and Plex-named episodes are never touched.
  const org = S.batchOrganize === true && !lib && v.index && v.book && !['pages', 'image'].includes(v.kind);
  let base = lib ? sanitize(lib.base) : sanitize(applyTemplate(S.nameTemplate || '{title}', v));
  if (org && !/\{index\}/.test(S.nameTemplate || '')) base = String(v.index).padStart(2, '0') + ' - ' + base;
  const sub = /\{\w+\}/.test(S.subfolder || '') ? applyTemplate(S.subfolder, v) : (S.subfolder || '');
  const orgDir = org && !/\{(series|book)\}/.test(S.subfolder || '') ? [sanitize(seriesBase(v.book), 50)] : [];
  const folder = [...sub.split(/[\\/]/).map((x) => sanitize(x, 50)).filter((x) => x && x !== 'media'), ...orgDir.filter((x) => x), ...(lib ? lib.folder.split('/') : [])].join('/');
  const suf = v.suffix ? '.' + sanitize(v.suffix, 30) : '';
  return (folder ? folder + '/' : '') + base + suf + '.' + ext;
}

// title + optional suffix + extension  ->  safe file name (no template)
function makeName(title, suffix, ext) {
  const s = suffix ? '.' + sanitize(suffix, 30) : '';
  return sanitize(title) + s + '.' + ext;
}

function guessExt(it) {
  const e = extOf(it.url);
  if (e && !['php', 'html', 'asp', 'aspx', 'jsp'].includes(e)) return e;
  const map = { 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/x-matroska': 'mkv', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a', 'audio/aac': 'aac', 'audio/ogg': 'ogg', 'audio/webm': 'weba' };
  return map[it.ct] || (it.kind === 'audio' ? 'mp3' : 'mp4');
}

// ---------- copy-as commands ----------
function q(s) { return '"' + String(s).replace(/(["\\$`])/g, '\\$1') + '"'; }

function buildCommands(item, ctx) {
  const ref = ctx.referer || '';
  const out = sanitize(ctx.title || baseName(item.url), 80);
  const ext = item.kind === 'audio' ? 'm4a' : 'mp4';
  const ytdlp = `yt-dlp ${ref ? '--referer ' + q(ref) + ' ' : ''}-o ${q(out + '.%(ext)s')} ${q(item.url)}`;
  const ffmpeg = `ffmpeg ${ref ? '-referer ' + q(ref) + ' ' : ''}-i ${q(item.url)} -c copy ${q(out + '.' + ext)}`;
  const curl = `curl -L ${ref ? '-e ' + q(ref) + ' ' : ''}-o ${q(out + '.' + (guessExt(item) === 'm3u8' ? 'ts' : guessExt(item)))} ${q(item.url)}`;
  return { ytdlp, ffmpeg, curl };
}

// ---------- HLS (m3u8) ----------
const absUrl = (u, base) => new URL(u, base).href;

function parseAttrs(s) {
  const o = {};
  const re = /([A-Z0-9-]+)=("([^"]*)"|[^,]*)/g;
  let m;
  while ((m = re.exec(s))) o[m[1]] = m[3] !== undefined ? m[3] : m[2];
  return o;
}

// "Quality label" of a stream variant (1080, 720, 480, 360, 240...): what people mean by "720p". Read from RESOLUTION (cinematic crops such as
// 1920x800 still count as 1080), else from a name / address that says "720p", else guessed from the bandwidth. est = true when guessed.
function stdLabel(w, h, name, url, bw) {
  const fromV = (v) => (v >= 3600 ? 4320 : v >= 1800 ? 2160 : v >= 1260 ? 1440 : v >= 900 ? 1080 : v >= 600 ? 720 : v >= 420 ? 480 : v >= 300 ? 360 : v >= 200 ? 240 : 144);
  w = +w || 0; h = +h || 0;
  if (w || h) return { lh: fromV(w && h > w ? w : Math.max(h, Math.round(w * 9 / 16))), est: false };
  const t = String(name || '') + ' ' + String(url || '').split('?')[0];
  let m = /(?:^|[^0-9])(4320|2160|1440|1080|720|480|360|240|144)\s*p(?![a-z0-9])/i.exec(t) || /(?:^|[\s\/_.-])(4320|2160|1440|1080|720|480|360|240|144)(?=[\/_.-]|$)/.exec(t);
  if (m) return { lh: +m[1], est: false };
  bw = +bw || 0;
  if (!bw) return { lh: 0, est: true };
  return { lh: bw >= 4.5e6 ? 1080 : bw >= 2.2e6 ? 720 : bw >= 1.1e6 ? 480 : bw >= 0.55e6 ? 360 : 240, est: true };
}
function parseMaster(text, base) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const variants = [];
  const media = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (l.startsWith('#EXT-X-STREAM-INF:')) {
      const a = parseAttrs(l.slice(18));
      let j = i + 1;
      while (j < lines.length && lines[j].startsWith('#')) j++;
      if (lines[j]) {
        const bw = parseInt(a.BANDWIDTH || a['AVERAGE-BANDWIDTH'] || '0', 10), res = a.RESOLUTION || '';
        const [rw, rh] = res.split('x').map((x) => parseInt(x, 10) || 0);
        const lab = stdLabel(rw, rh, a.NAME, lines[j], bw);
        variants.push({ url: absUrl(lines[j], base), bw, res, h: rh || 0, lh: lab.lh, est: lab.est, audio: a.AUDIO || '' });
      }
    } else if (l.startsWith('#EXT-X-MEDIA:')) {
      const a = parseAttrs(l.slice(13));
      media.push({
        type: a.TYPE, group: a['GROUP-ID'], name: a.NAME || a.LANGUAGE || 'track',
        lang: a.LANGUAGE || '', def: a.DEFAULT === 'YES',
        uri: a.URI ? absUrl(a.URI, base) : null
      });
    }
  }
  return { variants, media };
}

function parseRange(spec, prevEnd) {
  const [len, off] = spec.split('@');
  const start = off !== undefined ? parseInt(off, 10) : prevEnd || 0;
  return { start, end: start + parseInt(len, 10) - 1 };
}

// true if some #EXT-X-KEY / SESSION-KEY is real DRM (not plain AES-128)
function hlsProtected(text) {
  for (const l of text.split(/\r?\n/)) {
    if (!/^#EXT-X-(SESSION-)?KEY:/.test(l)) continue;
    const a = parseAttrs(l.slice(l.indexOf(':') + 1));
    if (a.METHOD === 'NONE') continue;
    if (a.METHOD !== 'AES-128') return true;
    if (a.KEYFORMAT && a.KEYFORMAT !== 'identity') return true;
  }
  return false;
}

function parseMedia(text, base) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const out = { segs: [], map: null };
  let seq = 0, key = null, pendingRange = null, curDur = 0;
  const lastEnd = new Map();
  for (const l of lines) {
    if (l.startsWith('#EXT-X-MEDIA-SEQUENCE:')) seq = parseInt(l.split(':')[1], 10) || 0;
    else if (l.startsWith('#EXT-X-KEY:')) {
      const a = parseAttrs(l.slice(11));
      if (a.METHOD === 'NONE') key = null;
      else if (a.METHOD === 'AES-128' && a.URI && (!a.KEYFORMAT || a.KEYFORMAT === 'identity')) key = { uri: absUrl(a.URI, base), iv: a.IV || null };
      else throw new Error('DRM-protected stream (' + a.METHOD + '). It cannot be downloaded.');
    } else if (l.startsWith('#EXT-X-MAP:')) {
      const a = parseAttrs(l.slice(11));
      out.map = { url: absUrl(a.URI, base), range: a.BYTERANGE ? parseRange(a.BYTERANGE, 0) : null };
    } else if (l.startsWith('#EXTINF:')) curDur = parseFloat(l.slice(8)) || 0;
    else if (l.startsWith('#EXT-X-BYTERANGE:')) pendingRange = l.slice(17);
    else if (!l.startsWith('#')) {
      const u = absUrl(l, base);
      let range = null;
      if (pendingRange) {
        range = parseRange(pendingRange, lastEnd.get(u));
        lastEnd.set(u, range.end + 1);
        pendingRange = null;
      }
      out.segs.push({ url: u, key, seq: seq++, range, dur: curDur });
      curDur = 0;
    }
  }
  return out;
}

// quick summary shown in the page panel
function hlsInfo(text, base) {
  const info = { master: false, variants: [], children: [], protected: hlsProtected(text), live: false, dur: 0, segs: 0 };
  if (/#EXT-X-STREAM-INF/.test(text)) {
    const { variants, media } = parseMaster(text, base);
    info.master = true;
    const seen = new Set();
    info.variants = variants.sort((a, b) => b.bw - a.bw).map((v) => ({
      url: v.url, bw: v.bw, res: v.res, h: v.res ? parseInt(v.res.split('x')[1], 10) || 0 : 0, lh: v.lh || 0
    })).filter((v) => { const k = v.h ? 'h' + v.h : v.lh || v.bw; if (seen.has(k)) return false; seen.add(k); return true; });
    info.children = variants.map((v) => v.url).concat(media.filter((m) => m.uri && m.type !== 'SUBTITLES').map((m) => m.uri));
    info.subs = media.filter((m) => m.type === 'SUBTITLES' && m.uri).map((m) => ({ uri: m.uri, name: m.name, lang: m.lang }));
    info.audioTracks = media.filter((m) => m.type === 'AUDIO' && m.uri).length;
    info.subTracks = media.filter((m) => m.type === 'SUBTITLES' && m.uri).length;
  } else {
    let dur = 0, segs = 0;
    for (const l of text.split(/\r?\n/)) {
      if (l.startsWith('#EXTINF:')) { dur += parseFloat(l.slice(8)) || 0; segs++; }
    }
    info.dur = dur; info.segs = segs;
    info.live = !/#EXT-X-ENDLIST/.test(text);
  }
  return info;
}

// ---------- batch quality: which height to take when the chosen one is missing ----------
// heights: the heights an episode offers; want: the height chosen for the whole batch (0 = Best); policy: near | low | high | skip.
// near = closest height (a tie goes to the lower one: smaller file); low = the next lower one, never higher unless nothing lower exists;
// high = the next higher one, never lower unless nothing higher exists; skip = no download for that episode.
function pickHeight(heights, want, policy) {
  const hs = Array.from(new Set((heights || []).map((x) => +x || 0).filter((x) => x > 0))).sort((a, b) => b - a);
  want = +want || 0;
  if (!hs.length || !want) return { h: 0, exact: true };
  if (hs.includes(want)) return { h: want, exact: true };
  const lo = hs.filter((x) => x < want)[0] || 0;                    // nearest lower
  const hi = hs.filter((x) => x > want).reverse()[0] || 0;          // nearest higher
  if (policy === 'skip') return { h: 0, exact: false, skip: true, lo, hi };
  let h;
  if (policy === 'low') h = lo || hi;
  else if (policy === 'high') h = hi || lo;
  else h = (lo && hi) ? ((want - lo) <= (hi - want) ? lo : hi) : (lo || hi);
  return { h, exact: false, lo, hi };
}

// ---------- DASH (light regex summary, no DOMParser in service workers) ----------
function isoDur(s) {
  const m = /^P(?:(\d+)D)?T?(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(s || '');
  if (!m) return 0;
  return (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0);
}

function dashInfo(text) {
  const info = { variants: [], audioReps: 0, protected: /<ContentProtection/i.test(text), live: /type\s*=\s*"dynamic"/i.test(text), dur: 0 };
  const md = /mediaPresentationDuration\s*=\s*"([^"]+)"/.exec(text);
  if (md) info.dur = isoDur(md[1]);
  const attr = (s, n) => { const m = new RegExp('\\b' + n + '\\s*=\\s*"([^"]*)"').exec(s); return m ? m[1] : ''; };
  const sets = text.split(/<AdaptationSet\b/i).slice(1);
  const seen = new Set();
  for (const set of sets) {
    const head = set.slice(0, set.indexOf('>'));
    const setMime = (attr(head, 'mimeType') || attr(head, 'contentType')).toLowerCase();
    const reps = set.match(/<Representation\b[^>]*>/gi) || [];
    for (const r of reps) {
      const mime = (attr(r, 'mimeType') || setMime).toLowerCase();
      const h = parseInt(attr(r, 'height') || attr(head, 'height'), 10) || 0;
      const bw = parseInt(attr(r, 'bandwidth'), 10) || 0;
      if (mime.startsWith('audio')) { info.audioReps++; continue; }
      if (!mime.startsWith('video') && !h) continue;
      const k = h || bw;
      if (seen.has(k)) continue;
      seen.add(k);
      info.variants.push({ id: attr(r, 'id'), h, bw, res: h ? (attr(r, 'width') || attr(head, 'width') || '?') + 'x' + h : '' });
    }
  }
  info.variants.sort((a, b) => (b.h - a.h) || (b.bw - a.bw));
  return info;
}

// ---------- WebVTT -> SRT ----------
function vttToSrt(vtt) {
  vtt = vtt.replace(/\r/g, '').replace(/^\uFEFF/, '');
  const re = /(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})/;
  const p2 = (n) => String(n).padStart(2, '0');
  const out = [];
  let n = 0;
  for (const block of vtt.split(/\n{2,}/)) {
    const lines = block.split('\n');
    const ti = lines.findIndex((l) => l.includes('-->'));
    if (ti < 0) continue;
    const m = lines[ti].match(re);
    if (!m) continue;
    let text = lines.slice(ti + 1).join('\n')
      .replace(/<(?!\/?(?:i|b|u)>)[^>]*>/g, '')
      .replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
      .trim();
    if (!text) continue;
    n++;
    const t1 = `${p2(m[1] || 0)}:${p2(m[2])}:${m[3]},${m[4]}`;
    const t2 = `${p2(m[5] || 0)}:${p2(m[6])}:${m[7]},${m[8]}`;
    out.push(`${n}\n${t1} --> ${t2}\n${text}`);
  }
  return out.join('\n\n') + '\n';
}

// ---------- V72: universal subtitle converter (VTT / SRT / ASS / SSA / TTML / DFXP / SBV / JSON3 / generic JSON) -> cues -> SRT / VTT / TXT ----------
function subDecode(t) {
  return String(t).replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&');
}
function subTs(str) { // "1:02:03.456" / "00:00:03,5" / "12.5s" / "10000t" -> seconds
  str = String(str == null ? '' : str).trim();
  let m = /^(\d+(?:\.\d+)?)s$/.exec(str); if (m) return +m[1];
  m = /^(\d+(?:\.\d+)?)ms$/.exec(str); if (m) return +m[1] / 1000;
  m = /^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,:](\d{1,3}))?$/.exec(str);
  if (m) return (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) + (m[4] ? +('0.' + m[4].padEnd(3, '0')) : 0);
  m = /^\d+(?:\.\d+)?$/.exec(str); if (m) return +str;
  return NaN;
}
function subCuesFrom(text, extHint) {
  text = String(text || '').replace(/^\uFEFF/, '').replace(/\r/g, '');
  const cues = [], push = (a, b, t) => { t = String(t || '').replace(/\n{3,}/g, '\n\n').trim(); if (t && isFinite(a) && isFinite(b)) cues.push({ s: a, e: b, t }); };
  const head = text.slice(0, 400).trim();
  // JSON (YouTube json3 / generic cue arrays)
  if (/^[\[{]/.test(head)) {
    try {
      const j = JSON.parse(text);
      if (j && Array.isArray(j.events)) { // youtube json3
        j.events.forEach((ev) => { if (ev && ev.segs) push((ev.tStartMs || 0) / 1000, ((ev.tStartMs || 0) + (ev.dDurationMs || 2000)) / 1000, ev.segs.map((x) => x.utf8 || '').join('')); });
        return cues;
      }
      const arr = Array.isArray(j) ? j : (j.body || j.cues || j.subtitles || j.captions || j.transcript || j.segments || j.lines || (j.data && (j.data.cues || j.data.subtitles)) || []);
      if (Array.isArray(arr)) {
        arr.forEach((c) => {
          if (!c || typeof c !== 'object') return;
          const t = c.text || c.content || c.line || c.caption || c.sentence || c.utf8 || c.value || '';
          let a = c.start != null ? c.start : c.from != null ? c.from : c.startTime != null ? c.startTime : c.begin != null ? c.begin : c.s != null ? c.s : c.offset;
          let b = c.end != null ? c.end : c.to != null ? c.to : c.endTime != null ? c.endTime : c.e;
          a = typeof a === 'string' ? subTs(a) : +a; 
          if (b == null && c.dur != null) b = a + +c.dur; else if (b == null && c.duration != null) b = a + +c.duration;
          b = typeof b === 'string' ? subTs(b) : +b;
          if (a > 100000 && b > 100000) { a /= 1000; b /= 1000; } // milliseconds
          push(a, b, t);
        });
        if (cues.length) return cues;
      }
    } catch (e) { /* not json, try the others */ }
  }
  // TTML / DFXP / SMPTE-TT / YouTube timedtext xml
  if (/<tt[\s>]|<tt:tt|<transcript|<timedtext|<body[\s>]/i.test(head + text.slice(400, 1200)) && /<(?:tt:)?p[\s>]|<text[\s>]/i.test(text)) {
    const tickRate = +((/ttp:tickRate="(\d+)"/.exec(text) || [])[1] || 0);
    const conv = (v) => { if (/^\d+t$/.test(v) && tickRate) return parseInt(v, 10) / tickRate; return subTs(v); };
    const re = /<(?:tt:)?p\b([^>]*)>([\s\S]*?)<\/(?:tt:)?p>/gi; let m;
    while ((m = re.exec(text))) {
      const at = m[1], g = (n) => { const x = new RegExp('\\b' + n + '="([^"]*)"').exec(at); return x ? x[1] : ''; };
      const a = conv(g('begin')); let b = conv(g('end')); if (!isFinite(b)) b = a + conv(g('dur'));
      push(a, b, subDecode(m[2].replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')));
    }
    if (!cues.length) { // youtube <text start dur>
      const r2 = /<text\b([^>]*)>([\s\S]*?)<\/text>/gi;
      while ((m = r2.exec(text))) { const st = +((/start="([\d.]+)"/.exec(m[1]) || [])[1]), du = +((/dur="([\d.]+)"/.exec(m[1]) || [])[1] || 2); push(st, st + du, subDecode(m[2].replace(/<[^>]+>/g, ''))); }
    }
    if (cues.length) return cues;
  }
  // ASS / SSA
  if (/\[Script Info\]|\[Events\]/i.test(text)) {
    let fmt = null;
    text.split('\n').forEach((ln) => {
      if (/^Format:/i.test(ln) && !fmt && /Start/i.test(ln)) fmt = ln.slice(7).split(',').map((x) => x.trim().toLowerCase());
      const m = /^Dialogue:\s*(.*)$/i.exec(ln); if (!m || !fmt) return;
      const parts = m[1].split(','), ti = fmt.indexOf('text'), si = fmt.indexOf('start'), ei = fmt.indexOf('end');
      const body = parts.slice(ti).join(',');
      push(subTs(parts[si]), subTs(parts[ei]), body.replace(/\{[^}]*\}/g, '').replace(/\\N/gi, '\n').replace(/\\h/g, ' '));
    });
    if (cues.length) return cues;
  }
  // SBV (YouTube) : 0:00:01.000,0:00:03.000
  if (/^\d+:\d{2}:\d{2}\.\d{3},\d+:\d{2}:\d{2}\.\d{3}/m.test(text)) {
    text.split(/\n{2,}/).forEach((b) => { const l = b.split('\n'), m = /^(\S+?),(\S+)$/.exec(l[0] || ''); if (m) push(subTs(m[1]), subTs(m[2]), l.slice(1).join('\n')); });
    if (cues.length) return cues;
  }
  // WebVTT / SRT (also SRT with "." or "," in the time, VTT with short mm:ss times)
  const re = /((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})/;
  text.split(/\n{2,}/).forEach((block) => {
    const lines = block.split('\n'), ti = lines.findIndex((l) => l.includes('-->')); if (ti < 0) return;
    const m = lines[ti].match(re); if (!m) return;
    const t = lines.slice(ti + 1).join('\n').replace(/<(?!\/?(?:i|b|u)>)[^>]*>/g, '');
    push(subTs(m[1]), subTs(m[2]), subDecode(t));
  });
  return cues;
}
function subFmtTime(sec, comma) {
  sec = Math.max(0, sec); const ms = Math.round(sec * 1000), h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, s = Math.floor(ms / 1000) % 60, f = ms % 1000;
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(h)}:${p(m)}:${p(s)}${comma ? ',' : '.'}${p(f, 3)}`;
}
function cuesToSrt(cues) { return cues.map((c, i) => `${i + 1}\n${subFmtTime(c.s, true)} --> ${subFmtTime(c.e, true)}\n${c.t}`).join('\n\n') + '\n'; }
function cuesToVtt(cues) { return 'WEBVTT\n\n' + cues.map((c) => `${subFmtTime(c.s)} --> ${subFmtTime(c.e)}\n${c.t}`).join('\n\n') + '\n'; }
function cuesToTxt(cues) { return cues.map((c) => c.t.replace(/<[^>]+>/g, '').replace(/\n/g, ' ')).join('\n') + '\n'; }
// text of any subtitle file -> { text, ext } in the wanted format ('srt' | 'vtt' | 'txt' | 'orig'); never throws, falls back to the original
// 0.0.351 (V82): move all cue times by delayMs (+ later / - earlier); cues never start before 0; a cue fully before 0 is dropped. The original text is never changed, only the saved copy.
function subShiftCues(cues, delayMs) {
  const d = (+delayMs || 0) / 1000;
  if (!d) return cues;
  return cues.map((c) => ({ ...c, s: Math.max(0, c.s + d), e: Math.max(0, c.e + d) })).filter((c) => c.e > c.s);
}
function subConvert(text, fmt, srcExt, delayMs) {
  const dly = +delayMs || 0;
  if (dly && (fmt === 'orig' || !fmt)) fmt = /^(vtt)$/i.test(srcExt || '') ? 'vtt' : 'srt'; // a shifted file has to be rewritten, so "original" becomes srt / vtt
  if (fmt === 'orig' || !fmt) return { text, ext: srcExt || 'txt', converted: false };
  let cues = subCuesFrom(text, srcExt);
  if (!cues.length) return { text, ext: srcExt || 'txt', converted: false };
  if (dly) cues = subShiftCues(cues, dly);
  if (fmt === 'vtt') return { text: cuesToVtt(cues), ext: 'vtt', converted: true, n: cues.length };
  if (fmt === 'txt') return { text: cuesToTxt(cues), ext: 'txt', converted: true, n: cues.length };
  return { text: cuesToSrt(cues), ext: 'srt', converted: true, n: cues.length };
}

// ---------- "What works in this browser": feature check (read-only, changes no behaviour) ----------
// Kiwi / Quetta / Firefox-Android lack some Chrome APIs. The extension already falls back when they are missing; this only SHOWS which ones.
function mgCapabilities(api) {
  const c = api || (typeof chrome !== 'undefined' ? chrome : {});
  const fn = (o, f) => !!(o && typeof o[f] === 'function');
  const nav = typeof navigator !== 'undefined' ? navigator : {};
  return [
    { k: 'sidePanel', label: 'Side panel', ok: fn(c.sidePanel, 'open'), no: 'Not available - use the popup or the on-page panel.' },
    { k: 'downloadsShow', label: 'Show in folder button', ok: fn(c.downloads, 'show'), no: 'Not available - saved files are listed without the button.' },
    { k: 'offscreen', label: 'Hidden background downloads', ok: fn(c.offscreen, 'createDocument'), no: 'Not available - downloads use a small tab instead.' },
    { k: 'opfs', label: 'Big-file temp storage (OPFS)', ok: !!(nav.storage && typeof nav.storage.getDirectory === 'function'), no: 'Not available - very large streams may use more memory.' },
    { k: 'dnr', label: 'Header rules (declarativeNetRequest)', ok: fn(c.declarativeNetRequest, 'updateDynamicRules') || fn(c.declarativeNetRequest, 'updateSessionRules'), no: 'Not available - some sites that check the Referer may refuse.' },
    { k: 'webRequest', label: 'Network watching (webRequest)', ok: !!(c.webRequest && c.webRequest.onCompleted), no: 'Not available - detection relies on the page only.' },
    { k: 'scripting', label: 'Script injection', ok: fn(c.scripting, 'executeScript'), no: 'Not available.' },
    { k: 'notifications', label: 'Notifications', ok: !!(c.notifications && typeof c.notifications.create === 'function'), no: 'Not available - no finished-download notification.' },
    { k: 'contextMenus', label: 'Right-click menu', ok: !!(c.contextMenus && typeof c.contextMenus.create === 'function'), no: 'Not available.' },
    { k: 'alarms', label: 'Alarms (scheduler)', ok: !!(c.alarms && typeof c.alarms.create === 'function'), no: 'Not available - scheduled work may not run.' },
  ];
}
function mgCapsText(list) { return (list || []).map((x) => (x.ok ? 'yes  ' : 'NO   ') + x.label).join('\n'); }
// Pages that can see chrome.downloads / sidePanel save the answer, so the on-page "Diagnose" (a content script, which cannot) can print it too.
function mgCapsSave() {
  try {
    const list = mgCapabilities().map((x) => ({ k: x.k, label: x.label, ok: x.ok }));
    chrome.storage.local.set({ mgCaps: { t: Date.now(), list } });
    return list;
  } catch (e) { return null; }
}

// ---------- V67 health check of the stored settings (read-only; only reports) ----------
// A hand-edited or imported file can leave a wrong type in a setting (e.g. text where a number belongs). Returns [{key, value}] for such keys.
function mgHealthSettings(stored, defaults) {
  const ENUM = { theme: ['auto', 'dark', 'light'], pillSide: ['left', 'right'], overlayCorner: ['tl', 'tr'], libraryMode: ['off', 'plex'], jobWindow: ['tab', 'window'], subFmt: ['srt', 'vtt', 'txt', 'orig'], subFallback: ['any', 'none'], titleSource: ['auto', 'page', 'schema', 'h1'] };
  const bad = [];
  for (const k of Object.keys(stored || {})) {
    if (!defaults || !(k in defaults)) continue;
    const v = stored[k], d = defaults[k];
    let ok = true;
    if (Array.isArray(d)) ok = Array.isArray(v) && v.every((x) => typeof x === 'string');
    else if (typeof d === 'number') ok = (typeof v === 'number' || (typeof v === 'string' && v.trim() !== '')) && isFinite(+v) && (+v >= 0 || k === 'subDelayMs'); // selects store numbers as text (subDelayMs may be negative)
    else if (typeof d === 'boolean') ok = typeof v === 'boolean';
    else if (typeof d === 'string') ok = typeof v === 'string' && (!ENUM[k] || ENUM[k].includes(v));
    if (!ok) { let t = ''; try { t = JSON.stringify(v); } catch (e) { t = String(v); } bad.push({ key: k, value: String(t).slice(0, 40) }); }
  }
  return bad;
}

if (typeof module !== 'undefined') module.exports = { mgHealthSettings, libraryPlan, seriesBase, liveRecoverPlan, mergeJobSnapshot, seriesName, parseNumList, epNumOf, expandPattern, patternFromUrl, seasonOf, sxeOf, galleryRun, expectedFromText, galleryFill, viewerNum, chaptersFromHtml, diagnose, padNumbers, siteFormat, sanitize, extOf, vttToSrt, subCuesFrom, subConvert, subShiftCues, cuesToSrt, cuesToVtt, cuesToTxt, makeName, hlsInfo, dashInfo, buildFileName, parseMedia, parseMaster, hlsProtected, isoDur };


// ---------- recorded page headers: replay the headers the real player sent (Referer / Origin / Authorization / X-...) ----------
function ruleHeaders(ent, minimal) {
  let ref = ent.ref; // 0.0.221: Referer and Origin must come from the same frame; a Referer of the top page next to the player's Origin is refused by the CDN (403)
  try { if (ref && ent.origin && /^https?:/i.test(ent.origin) && new URL(ref).origin !== ent.origin) ref = ent.origin.replace(/\/+$/, '') + '/'; } catch (e) { /* keep the recorded one */ }
  const out = [
    ref ? { header: 'referer', operation: 'set', value: ref } : { header: 'referer', operation: 'remove' },
    ent.origin ? { header: 'origin', operation: 'set', value: ent.origin } : { header: 'origin', operation: 'remove' }
  ];
  if (!minimal) for (const k of Object.keys(ent.extra || {})) out.push({ header: k, operation: 'set', value: String(ent.extra[k]).slice(0, 2000) });
  return out;
}

// ---------- ad filtering (used by background + content) ----------
const AD_SUFFIX = ['doubleclick.net','googlesyndication.com','googleadservices.com','google-analytics.com','adsterra.com','exoclick.com','exosrv.com','juicyads.com','trafficjunky.net','trafficjunky.com','popads.net','popcash.net','propellerads.com','onclickads.net','clickadu.com','hilltopads.net','a-ads.com','ad-maven.com','admaven.com','adcash.com','revcontent.com','mgid.com','taboola.com','outbrain.com','bidvertiser.com','adnxs.com','adsrvr.org','rubiconproject.com','pubmatic.com','criteo.com','criteo.net','amazon-adsystem.com','moatads.com','tsyndicate.com','realsrv.com','magsrv.com','plugrush.com','adspyglass.com','ero-advertising.com','trafficstars.com','vidoomy.com','spotx.tv','springserve.com','adform.net','smartadserver.com','teads.tv','richads.com','adtng.com','adskeeper.com','clksite.com','aclib.net','yllix.com','cpmstar.com','media.net','adk2.co','bngpt.com','jads.co','pushance.com','notifpush.com','animeapp.org'];
function isAdHost(h) {
  h = (h || '').toLowerCase();
  return AD_SUFFIX.some((s) => h === s || h.endsWith('.' + s)) || /^(ads?|adserver\d*|banners?|syndication|adx|pixel)\./.test(h);
}
const AD_PATH = /(\/bnr\/\d|\/vast[\/?._-]|\/vast$|\/vmap|preroll|midroll|\/adserve|\/ads?\/|\/adv\/|[?&]ad_?(id|unit|type|slot)=|\/banners?\/|\/sponsor)/i;
function isAdUrl(u) {
  try { const x = new URL(u); return isAdHost(x.hostname) || AD_PATH.test(x.pathname + x.search); } catch (e) { return false; }
}
const AD_CLASS = /(^|[\s_\-])(ads?|adv|advert|adverts|advertis\w*|adsbygoogle|adbox|adslot|adwrap\w*|banner\w*|sponsor\w*|affiliate|popunder|ad-playing|vjs-ad|ima-ad|jw-flag-ads|video-ads|preroll|ad-container)([\s_\-]|$)/i;
function isAdEl(el) {
  let p = el;
  for (let d = 0; p && p.nodeType === 1 && d < 6; d++, p = p.parentElement) {
    const t = p.tagName;
    if (t === 'BODY' || t === 'HTML' || t === 'MAIN' || t === 'ARTICLE') break;
    const cls = p.getAttribute('class') || '';
    if (t === 'INS' && /adsbygoogle/.test(cls)) return true;
    if (t === 'IFRAME' && isAdUrl(p.getAttribute('src') || '')) return true;
    if (AD_CLASS.test(cls + ' ' + (p.id || ''))) return true;
    if (p.hasAttribute('data-ad') || p.hasAttribute('data-ad-slot') || p.hasAttribute('data-ad-client')) return true;
    if (t === 'A' && /sponsored/i.test(p.getAttribute('rel') || '')) return true;
  }
  return false;
}

// ---------- download history (chrome.storage.local, newest first, max 500) ----------
async function addHistory(e) {
  try {
    const r = await chrome.storage.local.get('mgHist');
    const a = r.mgHist || [];
    a.unshift({ ...e, time: Date.now() });
    await chrome.storage.local.set({ mgHist: a.slice(0, 500) });
  } catch (err) { /* history is optional */ }
}


// ---------- subtitle helpers (language from file name, thumbnail tracks are not subtitles) ----------
const THUMB_VTT = /(thumbnail|thumbs?|sprite|storyboard)[^/]*\.(vtt|srt)$|\/(thumbnails?|thumbs|previews?|storyboards?)\//i;
function isThumbSub(url) {
  try { return THUMB_VTT.test(new URL(url).pathname); } catch (e) { return THUMB_VTT.test(String(url)); }
}
const LANG_NAMES = { english: 'en', eng: 'en', en: 'en', portuguese: 'pt', por: 'pt', pt: 'pt', spanish: 'es', spa: 'es', es: 'es', arabic: 'ar', ara: 'ar', ar: 'ar', french: 'fr', fre: 'fr', fra: 'fr', fr: 'fr', german: 'de', ger: 'de', deu: 'de', de: 'de', italian: 'it', ita: 'it', it: 'it', russian: 'ru', rus: 'ru', ru: 'ru', polish: 'pl', pol: 'pl', pl: 'pl', bengali: 'bn', bangla: 'bn', ben: 'bn', bn: 'bn', hindi: 'hi', hin: 'hi', hi: 'hi', indonesian: 'id', ind: 'id', turkish: 'tr', tur: 'tr', japanese: 'ja', jpn: 'ja', jap: 'ja', ja: 'ja', korean: 'ko', kor: 'ko', ko: 'ko', chinese: 'zh', chi: 'zh', zho: 'zh', zh: 'zh', vietnamese: 'vi', vie: 'vi', thai: 'th', tha: 'th', malay: 'ms', may: 'ms', msa: 'ms' };
function normLang(l) { l = String(l || '').toLowerCase().split(/[-_]/)[0]; return LANG_NAMES[l] || l; }
function subInfo(url) {
  const n = baseName(url).replace(/\.[a-z0-9]+$/i, '');
  const parts = n.toLowerCase().split(/[^a-z]+/).filter(Boolean);
  let lang = '';
  for (let i = parts.length - 1; i >= 0; i--) if (LANG_NAMES[parts[i]]) { lang = LANG_NAMES[parts[i]]; break; }
  return { lang, forced: /forced/i.test(n), cc: /(^|[^a-z])(cc|sdh)([^a-z]|$)/i.test(n), label: n.replace(/^track[_-]?\d+[_-]?/i, '').replace(/[_-]+/g, ' ').trim() };
}

const LANG_FULL = { en: 'English', pt: 'Portuguese', es: 'Spanish', ar: 'Arabic', fr: 'French', de: 'German', it: 'Italian', ru: 'Russian', pl: 'Polish', bn: 'Bengali', hi: 'Hindi', id: 'Indonesian', tr: 'Turkish', ja: 'Japanese', ko: 'Korean', zh: 'Chinese', vi: 'Vietnamese', th: 'Thai', ms: 'Malay' };
function subTitle(it) { // readable subtitle name: "Arabic" instead of "track_3_ara.vtt"
  const si = subInfo(it.url), lg = normLang(it.lang || si.lang);
  let n = it.label || si.label || baseName(it.url);
  if (lg && LANG_FULL[lg] && n.replace(/[^a-z]/gi, '').length <= 3) n = LANG_FULL[lg] + (si.forced ? ' (Forced)' : '') + (si.cc ? ' (CC)' : '');
  return n;
}


// ---------- V73: titles without the website name + subtitle language / preview-track checks (pure helpers, unit-tested) ----------
const TITLE_SEP = /\s+(?:[-\u2013\u2014\u2022\u00B7\u00BB]|::)\s+|\s*[|\u2022\u00B7\u00BB]\s*/;
const TITLE_GENERIC = new Set(['watch', 'stream', 'streaming', 'download', 'read', 'free', 'online', 'anime', 'animes', 'movie', 'movies', 'tv', 'cartoon', 'cartoons', 'drama', 'dramas', 'manga', 'hd', 'english', 'sub', 'subs', 'subbed', 'dub', 'dubbed', 'subtitle', 'subtitles', 'home', 'official', 'site', 'website', 'and', 'for', 'your', 'episodes']);
const EP_ONLY_RE = /^\s*(?:(?:episode|eps?\.?|chapter|chap\.?|ch\.?|\u00E9pisode|folge|cap[i\u00ED]tulo)\s*#?\s*\d+(?:\.\d+)?|s\d{1,2}\s*e\d+)\b/i;
const EP_MARK_RE = /\b(?:episode|eps?\.?|chapter|chap\.?|ch\.?)\s*#?\d+|\bS\d+\s*E\d+/i;
function normAlnum(t) { return String(t || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ''); }
// "www.kickassanime.am" -> "kickassanime", "x.co.uk" -> "x"
function hostCore(host) {
  const h = String(host || '').toLowerCase().replace(/^www\d*\./, '').split(':')[0];
  const p = h.split('.').filter(Boolean);
  if (p.length < 2) return normAlnum(p[0] || '');
  let i = p.length - 2;
  if (p.length >= 3 && ['co', 'com', 'org', 'net', 'gov', 'ac', 'edu'].includes(p[p.length - 2]) && p[p.length - 1].length === 2) i = p.length - 3;
  return normAlnum(p[i]);
}
// true when a piece of a page title is the website (its name, its tagline "Watch Anime Online Free", a name the user listed) and not the show
function isSiteSeg(seg, host, names) {
  const raw = String(seg || '').trim();
  if (!raw) return true;
  const n = normAlnum(raw);
  const extra = (names || []).map(normAlnum).filter((x) => x.length >= 3);
  if (extra.some((x) => n === x)) return true;
  if (/^[\w-]+(?:\.[\w-]+)*\.[a-z]{2,}$/i.test(raw)) { // a bare address: "OnePiece.to"
    const c = hostCore(raw), core0 = hostCore(host);
    if (c && (c === core0 || extra.includes(c))) return true;
  }
  const words = raw.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean).filter((w) => !TITLE_GENERIC.has(w));
  const res = words.join('');
  if (!res) return !EP_ONLY_RE.test(raw); // only tagline words ("Watch Anime Online Free")
  if (extra.some((x) => res === x)) return true;
  const core = hostCore(host);
  if (core.length >= 3 && (res === core || (res.length >= 4 && core.includes(res) && res.length >= core.length * 0.5))) return true;
  return false;
}
function splitTitle(t) { return String(t || '').replace(/\s+/g, ' ').trim().split(TITLE_SEP).map((x) => x.trim()).filter(Boolean); }
// the SHOW name of a page title, whichever side the website name is on: "Site - Naruto Episode 5" / "Naruto Episode 5 | Site" / "Naruto - Episode 5 - Site" -> "Naruto"
function smartSeries(raw, host, names) {
  const orig = String(raw || '').replace(/\s+/g, ' ').trim();
  const parts = splitTitle(orig);
  if (parts.length < 2) return seriesName(orig);
  const good = parts.filter((x) => !isSiteSeg(x, host, names) && !EP_ONLY_RE.test(x));
  const pick = good.find((x) => EP_MARK_RE.test(x)) || good[0];
  if (!pick) return seriesName(parts.find((x) => !EP_ONLY_RE.test(x)) || orig);
  const s = seriesName(pick);
  return s && /[\p{L}\p{N}]/u.test(s) ? s : seriesName(orig);
}
// a full file title without the website: "Watch Naruto Episode 5 English Subbed - Site" -> "Naruto Episode 5". Nothing left = the original text.
function cleanFullTitle(raw, host, names) {
  const orig = String(raw || '').replace(/\s+/g, ' ').trim();
  const parts = splitTitle(orig);
  if (!parts.length) return orig;
  const tidy = (x) => x.replace(/^(?:watch|stream|download|read)\s+/i, '').replace(/[\s(\[]*\b(?:english\s*)?(?:sub(?:bed|titled)?|dub(?:bed)?)(?:\s*[\/&,]\s*(?:sub(?:bed|titled)?|dub(?:bed)?))*\b[\s)\]]*$/i, '').replace(/\s*\b(?:online|free|hd|full)\b\s*$/i, '').replace(/[\s:,\-\u2013\u2014|]+$/, '').trim();
  let keep = parts.length > 1 ? parts.filter((x) => !isSiteSeg(x, host, names)) : parts.slice();
  if (parts.length > 1 && !keep.some((x) => !EP_ONLY_RE.test(x))) { // only "Episode 5" is left: the show itself has the website's name (onepiece.to) - keep it
    const alt = parts.find((x) => !EP_ONLY_RE.test(x) && !isSiteSeg(x, '', []) && !/^[\w-]+(?:\.[\w-]+)*\.[a-z]{2,}$/i.test(x));
    if (alt) keep = parts.filter((x) => x === alt || keep.includes(x));
  }
  keep = keep.map(tidy).filter((x) => /[\p{L}\p{N}]/u.test(x));
  if (!keep.length) return orig;
  return keep.join(' - ');
}
function titleNames(strip) { return String(strip || '').split(/[,\n;]+/).map((x) => x.trim()).filter(Boolean); }
function validSeries(s, host, names) { s = String(s || '').trim(); return s.length >= 2 && /[\p{L}\p{N}]/u.test(s) && !isSiteSeg(s, host, names) && !EP_ONLY_RE.test(s); }

// ----- subtitles: which language, is it a seek-bar preview track -----
// language from the metadata the page gave (srclang, label), then the file name, then a whole /en/ folder or ?lang=en in the address
function subLangGuess(sb) {
  const first = (t) => { const parts = String(t || '').toLowerCase().split(/[^a-z]+/).filter(Boolean); for (const w of parts) if (LANG_NAMES[w]) return LANG_NAMES[w]; return ''; };
  let l = normLang(sb && sb.lang); if (l && (LANG_FULL[l] || LANG_NAMES[l])) return l;
  l = first(sb && sb.label); if (l) return l;
  l = subInfo((sb && sb.url) || '').lang; if (l) return l;
  try {
    const u = new URL(sb.url);
    for (const k of ['lang', 'language', 'locale', 'srclang', 'hl', 'sub_lang', 'l']) { const v = u.searchParams.get(k); if (v && LANG_NAMES[normLang(v)]) return normLang(v); }
    for (const seg of decodeURIComponent(u.pathname).toLowerCase().split('/')) if (seg && LANG_NAMES[seg]) return LANG_NAMES[seg];
  } catch (e) { /* not an address */ }
  return '';
}
// true when the file is a seek-bar preview list (cues that point to picture tiles) and not subtitles
function looksThumbText(text) {
  const t = String(text || '');
  if (/#xywh=\d+,\d+,\d+,\d+/.test(t)) return true;
  const lines = t.split(/\r?\n/).filter((x) => x.trim() && !/-->|^WEBVTT|^\d+$|^NOTE/.test(x.trim()));
  if (lines.length < 3) return false;
  const img = lines.slice(0, 40).filter((x) => /\.(?:jpe?g|png|webp|gif|avif)(?:[?#]|\s*$)/i.test(x.trim())).length;
  return img >= 3 && img >= Math.min(lines.length, 40) * 0.6;
}
const LANG_WORDS = {
  en: 'the and you that what this with for have are was not but your his her they will just know don get can all out now like going here there from how then them well about would think want one see yes no it is to of in i a me we do he she'.split(' '),
  es: 'que de no el la los las un una es por con para como pero lo su al se mi te tu si yo ya eso esto muy est\u00E1 bien tengo quiero vamos hay'.split(' '),
  pt: 'que de n\u00E3o o a os as um uma \u00E9 por com para como mas se eu voc\u00EA est\u00E1 isso muito ent\u00E3o tenho quero vamos tem n\u00F3s'.split(' '),
  fr: 'que de ne pas le la les un une est je tu il nous vous qui dans pour avec sur c\'est \u00E7a mais oui non suis tr\u00E8s fait'.split(' '),
  de: 'der die das und ist nicht ich du er sie wir ihr ein eine mit f\u00FCr auf dass was bin hast habe aber auch noch ja nein'.split(' '),
  it: 'che di non il la le un una \u00E8 per con come ma io tu lui noi voi sono questo molto anche gli dei sei'.split(' '),
  id: 'yang dan di ini itu aku kamu tidak ada dengan untuk dari saya kita apa dia akan sudah bisa jangan mau'.split(' '),
  tr: 'bir ve bu de da ne ben sen o biz var yok i\u00E7in ama mi \u00E7ok de\u011Fil evet hay\u0131r'.split(' ')
};
// the language of subtitle TEXT (script first, then common words for Latin scripts). '' = not sure.
function detectSubLang(text) {
  let cues = []; try { cues = subCuesFrom(text, '') || []; } catch (e) { cues = []; }
  const sample = cues.slice(0, 80).map((c) => String(c.t || '').replace(/<[^>]+>/g, ' ')).join(' ').slice(0, 6000) || String(text || '').slice(0, 4000);
  const cnt = (re) => (sample.match(re) || []).length;
  const ar = cnt(/[\u0600-\u06FF]/g), ru = cnt(/[\u0400-\u04FF]/g), han = cnt(/[\u4E00-\u9FFF]/g), kana = cnt(/[\u3040-\u30FF]/g), ko = cnt(/[\uAC00-\uD7AF]/g), th = cnt(/[\u0E00-\u0E7F]/g), hi = cnt(/[\u0900-\u097F]/g), bn = cnt(/[\u0980-\u09FF]/g), lat = cnt(/[A-Za-z\u00C0-\u017F]/g);
  const tot = ar + ru + han + kana + ko + th + hi + bn + lat;
  if (tot < 20) return '';
  const top = [['ar', ar], ['ru', ru], ['ko', ko], ['th', th], ['hi', hi], ['bn', bn], [kana > han * 0.1 || kana > 10 ? 'ja' : 'zh', han + kana]].sort((a, b) => b[1] - a[1])[0];
  if (top[1] > tot * 0.4) return top[0];
  if (lat < tot * 0.6) return '';
  const words = sample.toLowerCase().split(/[^a-z\u00C0-\u017F']+/).filter(Boolean);
  if (words.length < 15) return '';
  const set = {}; for (const k of Object.keys(LANG_WORDS)) set[k] = new Set(LANG_WORDS[k]);
  const sc = Object.keys(set).map((k) => [k, words.filter((w) => set[k].has(w)).length]).sort((a, b) => b[1] - a[1]);
  if (sc[0][1] < words.length * 0.12 || sc[0][1] < sc[1][1] * 1.25) return '';
  return sc[0][0];
}

// ---------- quality labels: tell the user which button is which ----------
// V74p: a page can hold several players over time (episode / server switched without reloading): each has its own Referer (player address)
// and its own signed stream. A newer stream from another player makes the earlier ones "old" (still listed, never picked first):
// their signed addresses expire, the CDN then answers 403 for every segment although the playlist still loads.
function markOldStreams(items, o) {
  const isS = (x) => x && (x.kind === 'hls' || x.kind === 'dash') && !x.hidden;
  if (!isS(o) || !o.ref) return false;
  let ch = false;
  for (const x of items) if (x !== o && isS(x) && x.ref && x.ref !== o.ref && !x.old) { x.old = true; ch = true; }
  if (o.old) { delete o.old; ch = true; }
  return ch;
}
// order for "Best": newest player first; but a current stream much shorter than an old one (a preview / an ad) does not win
function cmpStreams(a, b) {
  if (!!a.refused !== !!b.refused) return a.refused ? 1 : -1; // V76: the player itself got 403 for it
  const da = (a.info && a.info.dur) || 0, db = (b.info && b.info.dur) || 0;
  const close = !da || !db || Math.min(da, db) >= 0.5 * Math.max(da, db);
  if (!!a.old !== !!b.old && close) return a.old ? 1 : -1;
  return 0;
}

// V74o: sites do not only offer 1080/720/480... (donghuastream: 536p, 352p, 216p). A height close to a standard one keeps the standard name,
// any other height is shown as it is, so the button says what you really get.
const STD_H = [4320, 2160, 1440, 1080, 720, 480, 360, 240, 144];
function stdNear(h) { return STD_H.find((s) => Math.abs(h - s) <= Math.max(4, s * 0.02)) || 0; }
function isStdH(h) { return !!stdNear(+h || 0); }
// the height used to compare / cap / pick qualities: the real height when it is not a standard one (536), else the standard label (lh).
// Portrait videos and cinematic crops (1920x800) keep the standard label, like before.
function qh(v) {
  const h = +(v && v.h) || 0, l = +(v && v.lh) || 0, w = v && v.res ? parseInt(String(v.res).split('x')[0], 10) || 0 : 0;
  if (h > 0 && !isStdH(h) && (!l || h <= l * 1.6) && (!w || (w >= h && w <= h * 2.05))) return h;
  return l || h;
}
function qLabel(h, bw) {
  if (h > 0) {
    const band = h >= 2000 ? 'Ultra HD' : h >= 1400 ? 'Quad HD' : h >= 1000 ? 'Full HD' : h >= 700 ? 'HD' : h >= 450 ? 'Standard' : 'Data saver';
    const s = stdNear(h);
    return [s === 4320 ? '8K' : s === 2160 ? '4K' : (s || h) + 'p', band];
  }
  return [bw ? (bw / 1e6).toFixed(1) + ' Mbps' : 'Default', bw >= 3e6 ? 'High' : bw >= 1e6 ? 'Medium' : 'Low'];
}
function qSize(v, dur) { return v && v.bw && dur ? fmtSize(Math.round(v.bw * dur / 8)) : ''; }
function qRows(variants, dur, esc) {
  const vs = variants.slice(0, 5);
  return '<div class="qs">' + vs.map((v, i) => {
    const [n, d] = qLabel(v.h, v.bw), sz = qSize(v, dur);
    const badge = i === 0 ? '<em>BEST</em>' : (i === vs.length - 1 && vs.length > 1 ? '<em class="g">SMALLEST</em>' : '');
    return `<button class="q${i ? '' : ' p'}" data-a="dl" data-v="${esc(v.url || v.id)}"><b>${esc(n)}</b><span class="qn">${esc(d)}</span>${badge}${sz ? `<span class="qz">~${esc(sz)}</span>` : ''}<span class="qd">&#8681;</span></button>`;
  }).join('') + '</div>';
}

function nameSize(n, base) { // long names: wrap and shrink a little so the whole name stays readable
  const l = String(n || '').length;
  return Math.max(base - 3, base - Math.floor(Math.max(0, l - 28) / 22));
}
// V74a: the same spacing / radius / type tokens as theme.css, as a string for the on-page panel (shadow DOM cannot read theme.css)
const MG_TOKENS = ':host{--s1:4px;--s2:8px;--s3:12px;--s4:16px;--s5:24px;--r-sm:10px;--r-md:14px;--r-lg:20px;--fs-xs:11px;--fs-sm:12px;--fs-md:13.5px;--fs-lg:16px;--fs-xl:20px;--tap:40px}@media (pointer:coarse){:host{--tap:44px}}';
if (typeof module !== 'undefined') Object.assign(module.exports, { mgCode, mgHttpCode, markOldStreams, cmpStreams, qLabel, qh, isStdH, mgCapabilities, mgCapsText, hostCore, isSiteSeg, smartSeries, cleanFullTitle, titleNames, validSeries, subLangGuess, looksThumbText, detectSubLang, subInfo, normLang });
