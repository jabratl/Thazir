// Media Grabber - offscreen document shim.
// An offscreen document only has chrome.runtime. The downloader page also uses storage, downloads and
// declarativeNetRequest, so those calls are forwarded to the service worker (background.js: off-rpc).
(() => {
  const JOB = new URLSearchParams(location.search).get('key') || '';
  const rpc = (api, method, args) => chrome.runtime.sendMessage({ type: 'off-rpc', api, method, args, key: JOB }).then((r) => {
    if (!r) throw new Error('background did not answer');
    if (r.error) throw new Error(r.error);
    return r.value;
  });
  const area = (n) => ({ get: (k) => rpc('storage.' + n, 'get', [k == null ? null : k]), set: (o) => rpc('storage.' + n, 'set', [o]), remove: (k) => rpc('storage.' + n, 'remove', [k]) });
  const listeners = new Set();
  const put = (k, v) => { try { if (!chrome[k]) Object.defineProperty(chrome, k, { value: v, configurable: true, writable: true }); } catch (e) { try { chrome[k] = v; } catch (e2) { /* ignore */ } } };
  const noop = { addListener() {}, removeListener() {}, hasListener() { return false; } };
  put('storage', { local: area('local'), sync: area('sync'), session: area('session'), onChanged: noop });
  put('tabs', { getCurrent: async () => undefined }); // there is no tab here: header rules apply to the whole extension instead
  put('declarativeNetRequest', { updateSessionRules: (o) => rpc('dnr', 'updateSessionRules', [o]) });
  put('downloads', { download: (o) => rpc('downloads', 'download', [o]), pause: (i) => rpc('downloads', 'pause', [i]), resume: (i) => rpc('downloads', 'resume', [i]), cancel: (i) => rpc('downloads', 'cancel', [i]), onChanged: { addListener: (f) => listeners.add(f), removeListener: (f) => listeners.delete(f) } });
  chrome.runtime.onMessage.addListener((m) => { if (m && m.type === 'off-dl-changed') listeners.forEach((f) => { try { f(m.delta); } catch (e) { /* ignore */ } }); });
  // chrome.runtime.getManifest does not exist in an offscreen document: read manifest.json instead (sync, once)
  if (typeof chrome.runtime.getManifest !== 'function') {
    let mf = null;
    chrome.runtime.getManifest = () => {
      if (mf) return mf;
      try { const x = new XMLHttpRequest(); x.open('GET', chrome.runtime.getURL('manifest.json'), false); x.send(); mf = JSON.parse(x.responseText); } catch (e) { mf = { version: '?', version_name: '' }; }
      return mf;
    };
  }
  window.MG_OFFSCREEN = true;
})();
