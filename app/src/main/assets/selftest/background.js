// Thazir self-test (S11): calls each supported chrome.* API once and stores the result in storage.local "selftest".
(function () {
  var R = [], done = false;
  function add(n, s, m) { R.push({ n: n, s: s, m: String(m || '').slice(0, 90) }); }
  function isNo(e) { return /Not supported|Not implemented/i.test(String((e && e.message) || e)); }
  function T(name, fn) {
    return new Promise(function (res) {
      var fin = false;
      var tm = setTimeout(function () { if (!fin) { fin = true; add(name, 'fail', 'timeout'); res(); } }, 4000);
      function ok(v) { if (fin) return; fin = true; clearTimeout(tm); if (v === true || v === undefined) add(name, 'pass'); else add(name, 'fail', v); res(); }
      function bad(e) { if (fin) return; fin = true; clearTimeout(tm); add(name, isNo(e) ? 'nosup' : 'fail', (e && e.message) || e); res(); }
      try { Promise.resolve(fn()).then(ok, bad); } catch (e) { bad(e); }
    });
  }
  function has(path) { var o = chrome, p = path.split('.'); for (var i = 0; i < p.length; i++) { if (o == null) return false; o = o[p[i]]; } return typeof o !== 'undefined'; }
  function isArr(v, what) { return Array.isArray(v) ? true : what + ' is not an array'; }
  var tests = [
    ['storage.local roundtrip', function () { return chrome.storage.local.set({ k1: { a: 1 } }).then(function () { return chrome.storage.local.get('k1'); }).then(function (r) { return r.k1 && r.k1.a === 1 ? true : 'wrong value'; }); }],
    ['storage.session roundtrip', function () { return chrome.storage.session.set({ k2: 2 }).then(function () { return chrome.storage.session.get('k2'); }).then(function (r) { return r.k2 === 2 ? true : 'wrong value'; }); }],
    ['storage.sync roundtrip', function () { return chrome.storage.sync.set({ k3: 3 }).then(function () { return chrome.storage.sync.get('k3'); }).then(function (r) { return r.k3 === 3 ? true : 'wrong value'; }); }],
    ['runtime manifest / id / getURL', function () { var m = chrome.runtime.getManifest(); return m && m.manifest_version === 3 && chrome.runtime.id && /^chrome-extension:\/\/|^https:\/\//.test(chrome.runtime.getURL('a.js')) ? true : 'bad values'; }],
    ['runtime.getPlatformInfo', function () { return chrome.runtime.getPlatformInfo().then(function (p) { return p && p.os ? true : 'no os'; }); }],
    ['alarms create / get / clear', function () { return chrome.alarms.create('st', { delayInMinutes: 5 }), chrome.alarms.get('st').then(function (a) { if (!a) return 'alarm missing'; return chrome.alarms.clear('st').then(function () { return true; }); }); }],
    ['contextMenus create / removeAll', function () { return new Promise(function (res, rej) { chrome.contextMenus.create({ id: 'st1', title: 'x', contexts: ['page'] }, function () { var e = chrome.runtime.lastError; if (e) return rej(e); chrome.contextMenus.removeAll(function () { res(true); }); }); }); }],
    ['notifications.getAll', function () { return chrome.notifications.getAll().then(function (o) { return o && typeof o === 'object' ? true : 'no object'; }); }],
    ['tabs.query', function () { return chrome.tabs.query({}).then(function (t) { return isArr(t, 'tabs'); }); }],
    ['windows.getAll', function () { return chrome.windows.getAll().then(function (t) { return isArr(t, 'windows'); }); }],
    ['windows.getCurrent', function () { return chrome.windows.getCurrent().then(function (w) { return w && typeof w.id !== 'undefined' ? true : 'no window'; }); }],
    ['cookies.getAll', function () { return chrome.cookies.getAll({ domain: 'example.com' }).then(function (c) { return isArr(c, 'cookies'); }); }],
    ['downloads.search', function () { return chrome.downloads.search({}).then(function (d) { return isArr(d, 'downloads'); }); }],
    ['declarativeNetRequest dynamic rule', function () {
      var rule = { id: 9001, priority: 1, action: { type: 'block' }, condition: { urlFilter: 'selftest-never.invalid', resourceTypes: ['main_frame'] } };
      return chrome.declarativeNetRequest.updateDynamicRules({ addRules: [rule] }).then(function () { return chrome.declarativeNetRequest.getDynamicRules(); }).then(function (r) {
        var okk = Array.isArray(r) && r.some(function (x) { return x.id === 9001; });
        return chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [9001] }).then(function () { return okk ? true : 'rule not listed'; });
      });
    }],
    ['action.setBadgeText', function () { return chrome.action.setBadgeText({ text: 'T' }).then(function () { return true; }); }],
    ['i18n.getUILanguage', function () { return typeof chrome.i18n.getUILanguage() === 'string' ? true : 'not a string'; }],
    ['commands.getAll', function () { return chrome.commands.getAll().then(function (c) { return isArr(c, 'commands'); }); }],
    ['permissions.getAll', function () { return chrome.permissions.getAll().then(function (p) { return p && Array.isArray(p.permissions) ? true : 'bad shape'; }); }],
    ['scripting.getRegisteredContentScripts', function () { return chrome.scripting.getRegisteredContentScripts().then(function (s) { return isArr(s, 'scripts'); }); }],
    ['webNavigation events exist', function () { return has('webNavigation.onCommitted.addListener') && has('webNavigation.onCompleted.addListener') ? true : 'missing'; }],
    ['offscreen.createDocument exists', function () { return has('offscreen.createDocument') ? true : 'missing'; }],
    ['search.query exists', function () { return has('search.query') ? true : 'missing'; }],
    ['browsingData.remove exists', function () { return has('browsingData.remove') ? true : 'missing'; }]
  ];
  var css = [
    ['CSS nesting (this WebView)', function () { return shadowW('p{&{width:9px}}', '9px'); }],
    ['CSS @layer (this WebView)', function () { return shadowW('@layer a{p{width:9px}}', '9px'); }],
    ['CSS color-mix (this WebView)', function () { return typeof CSS !== 'undefined' && CSS.supports('color', 'color-mix(in srgb, red 50%, blue)') ? true : 'not supported'; }],
    ['prefers-color-scheme readable', function () { return typeof matchMedia === 'function' ? true : 'no matchMedia'; }]
  ];
  function shadowW(text, want) {
    if (typeof document === 'undefined' || !document.body) return 'no document';
    var d = document.createElement('div'); document.body.appendChild(d);
    var sr = d.attachShadow({ mode: 'open' }), st = document.createElement('style'); st.textContent = text; sr.appendChild(st);
    var p = document.createElement('p'); sr.appendChild(p);
    var w = getComputedStyle(p).width; d.remove();
    return w === want ? true : 'width ' + w;
  }
  var chain = Promise.resolve();
  tests.concat(css).forEach(function (t) { chain = chain.then(function () { return T(t[0], t[1]); }); });
  chain.then(function () {
    var out = JSON.stringify({ v: 1, t: Date.now(), r: R });
    return chrome.storage.local.set({ selftest: out });
  }).catch(function () { });
})();
