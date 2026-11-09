// Media Grabber - 0.0.362: parts stage = slow start (2 connections, +1 after 2 good pieces) + a failed piece goes back in the queue and halves the connections (S6; Bilibili 502 with 8 at once). 0.0.361: mgPartsFile = same parts stage for the plain file mode (downloadFile). 0.0.360: NEW first stage 'parts' = N parallel 4 MB pieces (setting partsN, default 1 = off). 0.0.356: mgWholeFile = last way with progress. 0.0.355: progress is now also sent to the app (report) + stats row. 0.0.353: read ONE big file (a DASH Representation that is only a BaseURL, e.g. Bilibili video / audio) in pieces.
// WHY: the old code asked for the whole file in ONE request and held it as one blob: on a phone one dropped connection = "Failed to fetch" for the
// whole file, and nothing was kept. Here every piece (4 MB) has its own retry (fetchRetry), so a single hiccup no longer kills the job.
// DESIGN: this file only ADDS. downloader.js calls fileBlob() (a small hook); if anything here fails or returns null, the OLD whole-file code runs.
// Stages (ordered list = data, tried one after the other, each in try / catch, a failure only writes a log line):
//   0) parts    : (0.0.360, only when partsN > 1) N workers take 4 MB pieces from a queue, bytes assembled in order; anything unusual = next stage
//   1) direct   : Range requests with fetchRetry (same headers / header modes as every other download)
//   2) page     : Range requests read through the video page (viaPageBin, own origin + cookies)   [NOT tested on a device]
//   3) (old)    : whole file in one request - lives in downloader.js, not here
// Setting: chunkFile (default on). Off = stage 3 only = exactly the 0.0.352 behaviour.
(function () {
  const CH = 4 * 1024 * 1024;
  const MAX_PIECES = 400000;

  const isCancel = (e) => !!e && (e.message === 'Cancelled' || e.name === 'AbortError');

  // each reader returns { buf: ArrayBuffer, total: number (-1 unknown), whole: boolean (server ignored Range and sent everything), last: boolean (a short piece that ends the file) }
  async function readDirect(url, a, b, tries) { // 0.0.362: optional 4th param (undefined = fetchRetry default 6 = old behaviour)
    const r = await fetchRetry(url, { headers: { Range: 'bytes=' + a + '-' + b } }, tries);
    const buf = await r.arrayBuffer();
    let total = -1, hasCR = false;
    try { const cr = r.headers.get('Content-Range'); if (cr) { hasCR = true; if (cr.indexOf('/') > 0) { const t = parseInt(cr.split('/')[1], 10); if (t > 0) total = t; } } } catch (e) { /* header not readable: total stays unknown */ }
    // 0.0.354: some paths answer a Range request with status 200 and NO Content-Range (the 0.0.353 code took that 4 MB piece for the whole file).
    // 200 without Content-Range: longer than asked = the server ignored Range (whole file); not longer = a normal piece, and a short one is the last.
    const plain200 = r.status === 200 && !hasCR;
    return { buf, total, whole: plain200 && buf.byteLength > CH, last: plain200 && buf.byteLength < CH };
  }
  async function readPage(url, a, b) {
    const r = await viaPageBin(url, { headers: { Range: 'bytes=' + a + '-' + b } });
    if (!r) throw new Error('the video page could not read the piece');
    const buf = await r.arrayBuffer();
    return { buf, total: -1, whole: buf.byteLength > CH, last: buf.byteLength < CH }; // longer than asked = the server ignored Range; a short piece = the end
  }
  const STAGES = [['direct', readDirect], ['page', readPage]];

  // 0.0.360: parallel pieces. Returns a Blob, throws (= next stage) on anything unusual, rethrows Cancelled.
  // Piece 0 is read first (learns the real total from Content-Range); total must be known and >= 2 pieces, and the server must really answer Range.
  async function readParts(url, label, n) {
    const ft0 = Date.now();
    try { report('run', 0, (label ? label + ': ' : '') + 'starting...'); } catch (e) { /* display only */ }
    await gate();
    const first = await readDirect(url, 0, CH - 1);
    if (first.whole || first.last) throw new Error('server ignores Range');
    const total = first.total;
    if (!(total > 0)) throw new Error('size unknown');
    if (total < 2 * CH) throw new Error('file too small for parts');
    if (first.buf.byteLength !== CH) throw new Error('first piece has a wrong size');
    const count = Math.ceil(total / CH);
    if (count > MAX_PIECES) throw new Error('too many pieces');
    const res = new Array(count);
    res[0] = new Blob([first.buf]);
    let got = first.buf.byteLength, next = 1, fail = null, active = 0;
    const workers = Math.max(1, Math.min(n, count - 1));
    let limit = Math.min(workers, 2), okRun = 0, streak = 0, bad = 0, inflight = 0;
    const show = () => {
      try {
        const sec = (Date.now() - ft0) / 1000 || 1, bps = got / sec;
        const left = bps ? fmtDur(Math.max(0, total - got) / bps) : '';
        status('Downloading... ' + fmtSize(got) + ' / ' + fmtSize(total));
        progress(got, total);
        const txt = (label ? label + ': ' : '') + fmtSize(got) + ' / ' + fmtSize(total) + ' - ' + Math.min(limit, workers) + ' parts' + (bps ? ' - ' + fmtSpeed(bps) : '') + (left ? ' - ' + left + ' left' : '');
        report('run', got / total, txt);
        setStats({ speed: fmtSpeed(bps), size: fmtSize(got), eta: left, seg: Math.round(got / total * 100) + '%', sub: label || 'File' });
      } catch (e) { /* display only */ }
    };
    show();
    // 0.0.362 (S6): slow start + adaptive. Was: ALL workers at once and one bad piece killed the stage (live Bilibili: 8 at once = "Failed to fetch" / 502, then the sequential stage got 502 too).
    // Now: start with 2 connections, +1 after 2 good pieces (up to the setting); a failed piece goes back in the queue, connections are halved (min 1), short pause; stage fails only after 4 bad pieces in a row.
    const retryQ = [];
    const idle = () => retryQ.length === 0 && next >= count && inflight === 0;
    async function worker(k) {
      try {
        while (!fail) {
          if (idle()) break;
          await gate();
          if (k >= limit || (retryQ.length === 0 && next >= count)) { await sleep(250); continue; } // not allowed yet / nothing to take: wait
          const i = retryQ.length ? retryQ.shift() : next++;
          if (i >= count) continue;
          inflight++;
          let pause = 0;
          try {
            const a = i * CH, b = Math.min(total, a + CH) - 1, want = b - a + 1;
            const r = await readDirect(url, a, b, 3);
            if (r.whole) { const he = new Error('server ignores Range'); he.hard = true; throw he; }
            if (r.last || r.buf.byteLength !== want) throw new Error('piece ' + i + ' has a wrong size (' + r.buf.byteLength + ' of ' + want + ' B)');
            if (r.total > 0 && r.total !== total) { const he = new Error('file size changed during download'); he.hard = true; throw he; }
            res[i] = new Blob([r.buf]);
            got += want; streak = 0;
            if (++okRun >= 2 && limit < workers) { limit++; okRun = 0; }
            show();
          } catch (e) {
            if (isCancel(e) || (e && e.hard)) throw e;
            retryQ.push(i); streak++; bad++; okRun = 0;
            const old = limit; limit = Math.max(1, Math.floor(limit / 2));
            dbg('dl', 'parts: piece ' + i + ' failed (' + String((e && e.message) || e).slice(0, 80) + '), connections ' + old + ' -> ' + limit);
            if (streak >= 4 || bad >= 40) throw e;
            pause = Math.min(8000, 1500 * streak);
          } finally { inflight--; }
          if (pause) await sleep(pause);
        }
      } catch (e) { if (!fail) fail = e; }
    }
    const jobs = [];
    for (let k = 0; k < workers; k++) jobs.push(worker(k));
    await Promise.all(jobs);
    if (fail) throw fail;
    for (let i = 0; i < count; i++) if (!res[i]) throw new Error('piece ' + i + ' missing');
    if (got !== total) throw new Error('incomplete: ' + got + ' of ' + total + ' B');
    return new Blob(res);
  }

  async function readAll(read, url, label) {
    const parts = [];
    let a = 0, total = -1;
    const ft0 = Date.now(); // 0.0.355: start of THIS file, for speed / ETA
    try { report('run', 0, (label ? label + ': ' : '') + 'starting...'); } catch (e) { /* display only */ }
    for (let n = 0; n < MAX_PIECES; n++) {
      await gate();
      let res;
      try { res = await read(url, a, a + CH - 1); }
      catch (e) {
        if (isCancel(e)) throw e;
        if (a > 0 && e && e.status === 416) break; // asked past the end of a file whose size was unknown: that is the end
        throw e;
      }
      const len = res.buf.byteLength;
      if (n < 2) dbg('dl', 'piece ' + n + ': got ' + len + ' B, whole=' + res.whole + ', total=' + res.total); // 0.0.354: shows in the log what the server really answers
      if (res.whole) { if (a > 0) throw new Error('server sent the whole file in the middle'); return new Blob([res.buf]); }
      if (len === 0) { if (a === 0) throw new Error('empty answer'); break; }
      parts.push(new Blob([res.buf]));
      a += len;
      if (res.total > 0) total = res.total;
      try {
        status('Downloading... ' + fmtSize(a) + (total > 0 ? ' / ' + fmtSize(total) : ''));
        progress(a, total > 0 ? total : 0);
      } catch (e) { /* display only */ }
      // 0.0.355: tell the app / on-page panel too (report = job-update message). Without this only the downloader page itself showed progress; the job card stayed empty.
      try {
        const sec = (Date.now() - ft0) / 1000 || 1, bps = a / sec;
        const left = total > 0 && bps ? fmtDur((total - a) / bps) : '';
        const txt = (label ? label + ': ' : '') + fmtSize(a) + (total > 0 ? ' / ' + fmtSize(total) : '') + (bps ? ' - ' + fmtSpeed(bps) : '') + (left ? ' - ' + left + ' left' : '');
        report('run', total > 0 ? a / total : -1, txt);
        setStats({ speed: fmtSpeed(bps), size: fmtSize(a), eta: left, seg: total > 0 ? Math.round(a / total * 100) + '%' : '-', sub: label || 'File' });
      } catch (e) { /* display only */ }
      if (res.last && total <= 0) break; // reader says this was the last piece (no size known)
      if (total > 0 && a >= total) break; // 0.0.354: a SHORT piece is NOT the end (a server may answer less than asked). End = size reached, an empty answer, or 416
    }
    if (!parts.length) throw new Error('nothing received');
    if (total > 0 && a < total) throw new Error('incomplete: ' + a + ' of ' + total + ' B'); // never hand over a half file: next stage / old way
    return new Blob(parts);
  }

  // Returns a Blob, or null (= caller uses the old whole-file request). Throws only for Cancelled.
  window.mgBigFile = async function (url, label) {
    let pn = 1;
    try { pn = parseInt(SET && SET.partsN, 10) || 1; } catch (e) { pn = 1; }
    if (pn > 1) {
      try {
        const b = await readParts(url, label, Math.min(pn, 16));
        if (b && b.size > 0) { dbg('dl', 'big file read in parts (' + Math.min(pn, 16) + '): ' + fmtSize(b.size)); return b; }
      } catch (e) {
        if (isCancel(e)) throw e;
        dbg('dl', 'big file: stage parts failed, sequential pieces instead (' + String((e && e.message) || e).slice(0, 120) + ')');
        try { await sleep(3000); await gate(); } catch (e2) { if (isCancel(e2)) throw e2; } // 0.0.362: let a throttling server calm down before the next stage
      }
    }
    for (const [name, read] of STAGES) {
      try {
        const b = await readAll(read, url, label);
        if (b && b.size > 0) { dbg('dl', 'big file read in pieces (' + name + '): ' + fmtSize(b.size)); return b; }
      } catch (e) {
        if (isCancel(e)) throw e;
        dbg('dl', 'big file: stage ' + name + ' failed (' + String((e && e.message) || e).slice(0, 120) + ')');
      }
    }
    return null;
  };

  // 0.0.361: plain file mode (downloadFile in downloader.js) - same parts stage, only when partsN > 1. Returns a Blob, or null (= caller runs its old stream code unchanged).
  // Always starts at byte 0 (the caller only asks when nothing was received yet). Throws only for Cancelled.
  window.mgPartsFile = async function (url, label) {
    let pn = 1;
    try { pn = parseInt(SET && SET.partsN, 10) || 1; } catch (e) { pn = 1; }
    if (pn <= 1) return null;
    try {
      const b = await readParts(url, label, Math.min(pn, 16));
      if (b && b.size > 0) { dbg('dl', 'plain file read in parts (' + Math.min(pn, 16) + '): ' + fmtSize(b.size)); return b; }
    } catch (e) {
      if (isCancel(e)) throw e;
      dbg('dl', 'plain file: parts failed, normal download instead (' + String((e && e.message) || e).slice(0, 120) + ')');
    }
    return null;
  };

  // 0.0.356: the LAST way (whole file in ONE request) now also shows progress. Same request as before (fetchRetry(url)), but the body is read as a stream
  // so report() / setStats() can run. Returns a Blob, or null on any problem (caller then runs the old, unchanged .blob() way). Throws only for Cancelled.
  window.mgWholeFile = async function (url, label) {
    try {
      const r = await fetchRetry(url);
      if (!r || !r.body || typeof r.body.getReader !== 'function') return null; // no stream support: old way
      let total = parseInt(r.headers.get('content-length'), 10) || 0;
      const enc = r.headers.get('content-encoding'); if (enc && enc !== 'identity') total = 0; // length is of the packed bytes: do not trust it for a percent
      const reader = r.body.getReader(), chunks = [], ft0 = Date.now();
      let got = 0, lastShow = 0;
      for (;;) {
        await gate();
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value); got += value.length;
        const now = Date.now();
        if (now - lastShow < 250) continue;
        lastShow = now;
        try {
          if (total > 0 && got > total) total = 0;
          const bps = got / ((now - ft0) / 1000 || 1), left = total > 0 && bps ? fmtDur((total - got) / bps) : '';
          status('Downloading... ' + fmtSize(got) + (total > 0 ? ' / ' + fmtSize(total) : ''));
          progress(got, total > 0 ? total : 0);
          report('run', total > 0 ? got / total : -1, (label ? label + ': ' : '') + fmtSize(got) + (total > 0 ? ' / ' + fmtSize(total) : '') + (bps ? ' - ' + fmtSpeed(bps) : '') + (left ? ' - ' + left + ' left' : ''));
          setStats({ speed: fmtSpeed(bps), size: fmtSize(got), eta: left, seg: total > 0 ? Math.round(got / total * 100) + '%' : '-', sub: label || 'File' });
        } catch (e) { /* display only */ }
      }
      if (!got || (total > 0 && got < total)) throw new Error('incomplete: ' + got + ' of ' + total + ' B');
      dbg('dl', 'whole file read with progress: ' + fmtSize(got));
      return new Blob(chunks);
    } catch (e) {
      if (isCancel(e) || (e && e.message === 'Cancelled')) throw e;
      dbg('dl', 'whole file with progress failed, plain way instead: ' + String((e && e.message) || e).slice(0, 100));
      return null;
    }
  };
})();
