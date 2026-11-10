// Media Grabber - 0.0.369 (N1): a single plain http(s) file can be fetched by the app's NATIVE downloader (disk, resume, parts)
// instead of being read into memory here. Used only when setting directToNative is ON (default OFF) - see the guarded hook in downloader.js downloadFile().
// mgNativeFile(o) returns { ok: true, id, size } when the native downloader finished the file, or null = "not handled / failed before the file was done":
// the caller then runs the old engine code unchanged. It throws Error('Cancelled') only when the user cancelled.
// o = { url, ref, filename, isPaused(), isCancelled(), onState(text) }
async function mgNativeFile(o) {
  if (!o || !/^https?:\/\//i.test(o.url || '') || !chrome.downloads || typeof chrome.downloads.download !== 'function') return null;
  const early = []; let id = 0, finalState = '', err = '', size = 0;
  const onCh = (d) => {
    if (!d) return;
    if (!id) { early.push(d); return; }
    if (d.id === id) apply(d);
  };
  const apply = (d) => {
    if (d.totalBytes && d.totalBytes.current > 0) size = d.totalBytes.current;
    if (d.error && d.error.current) err = d.error.current;
    if (d.state && (d.state.current === 'complete' || d.state.current === 'interrupted')) finalState = d.state.current;
  };
  chrome.downloads.onChanged.addListener(onCh);
  try {
    const req = { url: o.url, filename: o.filename, conflictAction: 'uniquify' };
    if (o.ref) req.headers = [{ name: 'Referer', value: o.ref }];
    try { id = await chrome.downloads.download(req); } catch (e) { return null; } // native refused: old path
    if (!id) return null;
    for (const d of early) if (d.id === id) apply(d);
    let wasPaused = false;
    while (!finalState) {
      if (o.isCancelled && o.isCancelled()) {
        try { await chrome.downloads.cancel(id); } catch (e) { /* ignore */ }
        throw new Error('Cancelled');
      }
      const p = !!(o.isPaused && o.isPaused());
      if (p !== wasPaused) {
        wasPaused = p;
        try { await (p ? chrome.downloads.pause(id) : chrome.downloads.resume(id)); } catch (e) { /* ignore */ }
      }
      await new Promise((r) => setTimeout(r, 300));
    }
    if (finalState === 'complete') return { ok: true, id, size };
    if (err === 'USER_CANCELED') throw new Error('Cancelled');
    try { await chrome.downloads.cancel(id); } catch (e) { /* ignore: cleans the half file */ }
    if (typeof dbg === 'function') dbg('dl', 'native downloader failed (' + (err || '?') + '), engine download');
    return null;
  } finally { try { chrome.downloads.onChanged.removeListener(onCh); } catch (e) { /* ignore */ } }
}
