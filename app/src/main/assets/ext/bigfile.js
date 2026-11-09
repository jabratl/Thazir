// Media Grabber - 0.0.353: read ONE big file (a DASH Representation that is only a BaseURL, e.g. Bilibili video / audio) in pieces.
// WHY: the old code asked for the whole file in ONE request and held it as one blob: on a phone one dropped connection = "Failed to fetch" for the
// whole file, and nothing was kept. Here every piece (4 MB) has its own retry (fetchRetry), so a single hiccup no longer kills the job.
// DESIGN: this file only ADDS. downloader.js calls fileBlob() (a small hook); if anything here fails or returns null, the OLD whole-file code runs.
// Stages (ordered list = data, tried one after the other, each in try / catch, a failure only writes a log line):
//   1) direct   : Range requests with fetchRetry (same headers / header modes as every other download)
//   2) page     : Range requests read through the video page (viaPageBin, own origin + cookies)   [NOT tested on a device]
//   3) (old)    : whole file in one request - lives in downloader.js, not here
// Setting: chunkFile (default on). Off = stage 3 only = exactly the 0.0.352 behaviour.
(function () {
  const CH = 4 * 1024 * 1024;
  const MAX_PIECES = 20000;

  const isCancel = (e) => !!e && (e.message === 'Cancelled' || e.name === 'AbortError');

  // each reader returns { buf: ArrayBuffer, total: number (-1 unknown), whole: boolean (server ignored Range and sent everything) }
  async function readDirect(url, a, b) {
    const r = await fetchRetry(url, { headers: { Range: 'bytes=' + a + '-' + b } });
    const buf = await r.arrayBuffer();
    let total = -1;
    try { const cr = r.headers.get('Content-Range'); if (cr && cr.indexOf('/') > 0) { const t = parseInt(cr.split('/')[1], 10); if (t > 0) total = t; } } catch (e) { /* header not readable: total stays unknown */ }
    return { buf, total, whole: r.status === 200 };
  }
  async function readPage(url, a, b) {
    const r = await viaPageBin(url, { headers: { Range: 'bytes=' + a + '-' + b } });
    if (!r) throw new Error('the video page could not read the piece');
    const buf = await r.arrayBuffer();
    return { buf, total: -1, whole: buf.byteLength > CH }; // longer than asked = the server ignored Range
  }
  const STAGES = [['direct', readDirect], ['page', readPage]];

  async function readAll(read, url) {
    const parts = [];
    let a = 0, total = -1;
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
      if (res.whole) { if (a > 0) throw new Error('server sent the whole file in the middle'); return new Blob([res.buf]); }
      if (len === 0) { if (a === 0) throw new Error('empty answer'); break; }
      parts.push(new Blob([res.buf]));
      a += len;
      if (res.total > 0) total = res.total;
      try {
        status('Downloading... ' + fmtSize(a) + (total > 0 ? ' / ' + fmtSize(total) : ''));
        progress(a, total > 0 ? total : 0);
      } catch (e) { /* display only */ }
      if (total > 0 ? a >= total : len < CH) break;
    }
    if (!parts.length) throw new Error('nothing received');
    return new Blob(parts);
  }

  // Returns a Blob, or null (= caller uses the old whole-file request). Throws only for Cancelled.
  window.mgBigFile = async function (url) {
    for (const [name, read] of STAGES) {
      try {
        const b = await readAll(read, url);
        if (b && b.size > 0) { dbg('dl', 'big file read in pieces (' + name + '): ' + fmtSize(b.size)); return b; }
      } catch (e) {
        if (isCancel(e)) throw e;
        dbg('dl', 'big file: stage ' + name + ' failed (' + String((e && e.message) || e).slice(0, 120) + ')');
      }
    }
    return null;
  };
})();
