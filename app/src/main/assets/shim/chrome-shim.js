// MGB chrome.* shim (S4). Wrapped by ContentInjector as:  <this file>(window, CFG)  -> chrome object (or null).
// CFG = { token, kind: 'content' | 'ext', base, id, manifest }
// Everything is forwarded to Kotlin (ChromeBridge) over the MGBBridge WebMessageListener as JSON:
//   out: { t: token, i: id, a: 'storage.local.get', p: [args] }       in: { i, r } | { i, err } | { e: event, p: [args], q?: reqId }
(function (G, CFG) {
  'use strict';
  var bridge = G.MGBBridge;
  if (!bridge || typeof bridge.postMessage !== 'function') return null;
  var post = bridge.postMessage.bind(bridge);
  var isExt = CFG.kind === 'ext';
  var GAPS = isExt && CFG.gaps === true; // 0.0.429 (S4): installed extensions only (the built-in Media Grabber pages never get the extra stubs)
  var seq = 0, pending = {}, listeners = {}, lastError;
  var slice = Array.prototype.slice;

  function send(obj) { obj.t = CFG.token; obj.x = CFG.id; post(JSON.stringify(obj)); } // 0.0.108: x = extension id (ChromeBridge routes token / storage / events per extension)
  function call(api, args) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = [resolve, reject];
      try { send({ i: id, a: api, p: args }); } catch (e) { delete pending[id]; reject(e); }
    });
  }
  function log(m) { try { send({ i: 0, a: '__log', p: [String(m)] }); } catch (e) { /* ignore */ } }

  // callback OR promise, like chrome.* in MV3
  function wrap(fn) {
    return function () {
      var args = slice.call(arguments), cb = null;
      if (args.length && typeof args[args.length - 1] === 'function') cb = args.pop();
      var pr = fn.apply(null, args);
      if (!cb) return pr;
      pr.then(function (r) {
        lastError = undefined;
        try { cb(r); } catch (e) { log('cb: ' + (e && e.stack || e)); }
      }, function (e) {
        lastError = { message: String((e && e.message) || e) };
        try { cb(); } catch (x) { log('cb: ' + (x && x.stack || x)); }
        lastError = undefined;
      });
    };
  }
  function method(api) { return wrap(function () { return call(api, slice.call(arguments)); }); }

  function makeEvent(name, notify) {
    var set = listeners[name] = listeners[name] || [];
    return {
      addListener: function (fn) {
        if (typeof fn !== 'function') return;
        if (set.indexOf(fn) < 0) set.push(fn);
        if (notify) call('event.add', [name].concat(slice.call(arguments, 1))).then(null, function () { /* ignore */ });
      },
      removeListener: function (fn) { var i = set.indexOf(fn); if (i >= 0) set.splice(i, 1); },
      hasListener: function (fn) { return set.indexOf(fn) >= 0; },
      hasListeners: function () { return set.length > 0; }
    };
  }

  function fire(name, args) {
    var set = (listeners[name] || []).slice();
    for (var i = 0; i < set.length; i++) { try { set[i].apply(null, args); } catch (e) { log('listener ' + name + ': ' + (e && e.stack || e)); } }
  }

  function deliverMessage(args, reqId) {
    var set = (listeners['runtime.onMessage'] || []).slice();
    var done = false, asyncResp = false;
    function respond(v) {
      if (done) return;
      done = true;
      try { send({ i: 0, a: '__reply', p: [reqId, v === undefined ? null : v] }); } catch (e) { /* ignore */ }
    }
    if (!set.length) { respond(); return; }
    for (var k = 0; k < set.length; k++) {
      try {
        var r = set[k](args[0], args[1] || {}, respond);
        if (r === true) asyncResp = true;
        else if (r && typeof r.then === 'function') { asyncResp = true; r.then(respond, function () { respond(); }); }
      } catch (e) { log('onMessage: ' + (e && e.stack || e)); }
    }
    if (!asyncResp && !done) respond();
  }

  // ---------- ports (0.0.111, S1): runtime.connect / runtime.onConnect / tabs.connect ----------
  var NO_RECV = 'Could not establish connection. Receiving end does not exist.';
  var ports = {};
  function newPortId() { return 'p' + (++seq) + Math.random().toString(36).slice(2, 10) + Date.now().toString(36); }
  function makePort(pid, name, sender) {
    var port = { name: String(name || ''), sender: sender, _dead: false };
    port.onMessage = makeEvent('port.' + pid + '.onMessage', false);
    port.onDisconnect = makeEvent('port.' + pid + '.onDisconnect', false);
    port.postMessage = function (m) {
      if (port._dead) throw new Error('Attempting to use a disconnected port object');
      call('port.post', [pid, m === undefined ? null : m]).then(null, function () { /* port gone */ });
    };
    port.disconnect = function () {
      if (port._dead) return;
      port._dead = true; delete ports[pid];
      call('port.disconnect', [pid]).then(null, function () { /* ignore */ });
      delete listeners['port.' + pid + '.onMessage']; delete listeners['port.' + pid + '.onDisconnect'];
    };
    ports[pid] = port;
    return port;
  }
  function closePort(pid, err) {
    var port = ports[pid];
    if (!port || port._dead) return;
    port._dead = true; delete ports[pid];
    if (err) lastError = { message: String(err) };
    fire('port.' + pid + '.onDisconnect', [port]);
    lastError = undefined;
    delete listeners['port.' + pid + '.onMessage']; delete listeners['port.' + pid + '.onDisconnect'];
  }
  function incomingPort(info) {
    if (!info || !info.id) return;
    if (!(listeners['runtime.onConnect'] || []).length) { call('port.disconnect', [info.id, true]).then(null, function () { /* ignore */ }); return; }
    var port = makePort(info.id, info.name, info.sender);
    fire('runtime.onConnect', [port]);
  }

  function onNative(ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (!m) return;
    if (m.x && m.x !== CFG.id) return; // 0.0.108: answers / events of another extension in the same frame
    if (m.e) {
      var a = m.p || [];
      if (m.e === 'runtime.onMessage' && m.q) { deliverMessage(a, m.q); return; }
      if (m.e === 'runtime.onConnect') { incomingPort(a[0]); return; }
      if (m.e === 'port.msg') { var pp = ports[a[0]]; if (pp && !pp._dead) fire('port.' + a[0] + '.onMessage', [a[1], pp]); return; }
      if (m.e === 'port.disc') { closePort(a[0], a[1]); return; }
      fire(m.e, a);
      if (m.e === 'storage.onChanged' && a[1]) fire('storage.' + a[1] + '.onChanged', [a[0]]);
      return;
    }
    var p = pending[m.i];
    if (!p) return;
    delete pending[m.i];
    if (m.err) p[1](new Error(m.err)); else p[0](m.r === null ? undefined : m.r);
  }
  if (typeof bridge.addEventListener === 'function') bridge.addEventListener('message', onNative);
  else bridge.onmessage = onNative;

  // ---------- runtime ----------
  var manifest = CFG.manifest || {};
  var runtime = {
    id: CFG.id,
    getURL: function (p) { return CFG.base + String(p == null ? '' : p).replace(/^\/+/, ''); },
    getManifest: function () { return JSON.parse(JSON.stringify(manifest)); },
    sendMessage: wrap(function (a, b) {
      // chrome.runtime.sendMessage([extensionId,] message [, options]): own id or an id-shaped first string is dropped
      var msg = a;
      if (typeof a === 'string' && arguments.length >= 2 && (a === CFG.id || /^[a-p]{32}$/.test(a))) msg = b;
      return call('runtime.sendMessage', [msg === undefined ? null : msg]);
    }),
    onMessage: makeEvent('runtime.onMessage', isExt),
    // 0.0.108: events / calls many extensions touch at load time; a missing one threw and stopped the whole content script
    onConnect: makeEvent('runtime.onConnect', isExt), // 0.0.111: announced so a stopped worker wakes for a port
    onConnectExternal: makeEvent('runtime.onConnectExternal', false),
    onMessageExternal: makeEvent('runtime.onMessageExternal', false),
    onSuspend: makeEvent('runtime.onSuspend', false),
    onSuspendCanceled: makeEvent('runtime.onSuspendCanceled', false),
    onUpdateAvailable: makeEvent('runtime.onUpdateAvailable', false),
    reload: function () { /* not supported */ },
    requestUpdateCheck: wrap(function () { return Promise.resolve({ status: 'no_update' }); }),
    connect: function (a, b) {
      // 0.0.111 (S1): real long-lived port (Kotlin: ExtApi ports). Another extension id = no receiver (external connections are not supported)
      var info = (typeof a === 'object' && a) ? a : (b || {});
      var pid = newPortId();
      var port = makePort(pid, info.name, undefined);
      if (typeof a === 'string' && a !== CFG.id) {
        setTimeout(function () { closePort(pid, NO_RECV); }, 0);
        return port;
      }
      call('runtime.connect', [pid, String(info.name || '')]).then(null, function (e) { closePort(pid, String((e && e.message) || e)); });
      return port;
    }
  };
  Object.defineProperty(runtime, 'lastError', { get: function () { return lastError; }, enumerable: true });

  // ---------- storage ----------
  function area(n) {
    var p = 'storage.' + n + '.';
    return {
      get: wrap(function (k) { return call(p + 'get', [k === undefined ? null : k]); }),
      set: wrap(function (o) { return call(p + 'set', [o]); }),
      remove: wrap(function (k) { return call(p + 'remove', [k]); }),
      clear: wrap(function () { return call(p + 'clear', []); }),
      getBytesInUse: wrap(function (k) { return call(p + 'getBytesInUse', [k === undefined ? null : k]); }),
      onChanged: makeEvent('storage.' + n + '.onChanged', false)
    };
  }
  var storage = { local: area('local'), sync: area('sync'), session: area('session'), onChanged: makeEvent('storage.onChanged', false) };
  // 0.0.118 (S6): session storage is closed for content scripts until a trusted page calls setAccessLevel
  storage.session.setAccessLevel = wrap(function (o) { return call('storage.session.setAccessLevel', [o || {}]); });
  storage.session.QUOTA_BYTES = 10485760;

  // ---------- i18n (S15): messages come from the extension's _locales (CFG.messages, built by Kotlin) ----------
  var msgs = CFG.messages || {};
  var i18n = {
    getMessage: function (key, subs) {
      var k = String(key).toLowerCase();
      if (k === '@@extension_id') return CFG.id;
      if (k === '@@ui_locale') return ((G.navigator && G.navigator.language) || 'en').replace('-', '_');
      if (k === '@@bidi_dir') return 'ltr';
      if (k === '@@bidi_reversed_dir') return 'rtl';
      if (k === '@@bidi_start_edge') return 'left';
      if (k === '@@bidi_end_edge') return 'right';
      var e = msgs[k];
      if (!e) return '';
      var s = String(e.m || '');
      s = s.replace(/\$([A-Za-z0-9_@]+)\$/g, function (x, n) { var c = e.p && e.p[n.toLowerCase()]; return c === undefined ? '' : c; });
      var a = subs === undefined ? [] : (Array.isArray(subs) ? subs : [subs]);
      s = s.replace(/\$([1-9])/g, function (x, d) { return a[d - 1] !== undefined ? String(a[d - 1]) : ''; });
      return s.replace(/\$\$/g, '$');
    },
    getUILanguage: function () { return (G.navigator && G.navigator.language) || 'en'; },
    getAcceptLanguages: wrap(function () { return Promise.resolve([(G.navigator && G.navigator.language) || 'en']); }),
    detectLanguage: wrap(function () { return Promise.resolve({ isReliable: false, languages: [] }); })
  };

  var chrome = { runtime: runtime, storage: storage, i18n: i18n, __mgbLog: log };
  chrome.extension = { getURL: runtime.getURL, inIncognitoContext: false, getBackgroundPage: function () { return null; }, getViews: function () { return []; },
    isAllowedIncognitoAccess: wrap(function () { return Promise.resolve(false); }), isAllowedFileSchemeAccess: wrap(function () { return Promise.resolve(false); }) };

  // ---------- extension pages only: everything else is forwarded to Kotlin ----------
  if (isExt) {
    runtime.getPlatformInfo = wrap(function () { return Promise.resolve({ os: 'android', arch: 'arm64', nacl_arch: 'arm' }); });
    runtime.openOptionsPage = method('runtime.openOptionsPage');
    runtime.getContexts = method('runtime.getContexts');
    runtime.onInstalled = makeEvent('runtime.onInstalled', true);
    runtime.onStartup = makeEvent('runtime.onStartup', true);
    var NS = {
      tabs: [['sendMessage', 'create', 'update', 'getCurrent', 'remove', 'get', 'query', 'reload', 'captureVisibleTab', 'executeScript'], ['onRemoved', 'onUpdated', 'onCreated', 'onActivated']],
      downloads: [['search', 'download', 'show', 'resume', 'removeFile', 'pause', 'cancel', 'erase', 'open'], ['onChanged', 'onCreated']],
      declarativeNetRequest: [['updateSessionRules', 'updateDynamicRules', 'getSessionRules', 'getDynamicRules', 'getEnabledRulesets', 'updateEnabledRulesets', 'isRegexSupported', 'setExtensionActionOptions'], []], // 0.0.119 (S5)
      scripting: [['executeScript', 'insertCSS', 'registerContentScripts', 'updateContentScripts', 'unregisterContentScripts', 'getRegisteredContentScripts'], []],
      sidePanel: [['open', 'setOptions', 'setPanelBehavior'], []],
      action: [['setBadgeText', 'setBadgeBackgroundColor', 'setBadgeTextColor', 'setIcon', 'setTitle', 'setPopup', 'getBadgeText', 'getBadgeBackgroundColor', 'getBadgeTextColor', 'getTitle', 'getPopup', 'enable', 'disable', 'isEnabled', 'openPopup'], ['onClicked']], // 0.0.112 (S2)
      notifications: [['create', 'clear', 'update'], ['onClicked', 'onClosed', 'onButtonClicked']],
      contextMenus: [['create', 'update', 'remove', 'removeAll'], ['onClicked']],
      alarms: [['create', 'get', 'getAll', 'clear', 'clearAll'], ['onAlarm']],
      windows: [['create', 'get', 'getCurrent', 'update'], []],
      permissions: [['contains', 'request', 'getAll'], []],
      offscreen: [['createDocument', 'closeDocument', 'hasDocument'], []],
      commands: [['getAll'], ['onCommand']],
      webRequest: [[], ['onBeforeRequest', 'onBeforeSendHeaders', 'onSendHeaders', 'onHeadersReceived', 'onCompleted', 'onErrorOccurred']], // 0.0.119 (S5): onBeforeRequest / onBeforeSendHeaders / onSendHeaders / onErrorOccurred get events
      cookies: [['get', 'getAll', 'set', 'remove', 'getAllCookieStores'], ['onChanged']] // N1: Novel grabber
    };
    Object.keys(NS).forEach(function (ns) {
      var o = chrome[ns] = {};
      NS[ns][0].forEach(function (m) { o[m] = method(ns + '.' + m); });
      NS[ns][1].forEach(function (e) { o[e] = makeEvent(ns + '.' + e, true); });
    });
    // N1: a function cannot cross the JSON bridge -> executeScript sends the source text of `func` (Kotlin runs it in the tab).
    function injection(inj) {
      var o = {}, k; inj = inj || {};
      for (k in inj) o[k] = inj[k];
      if (typeof o.func === 'function') o.func = '(' + o.func.toString() + ')';
      return o;
    }
    // 0.0.111: chrome.tabs.connect(tabId, {name, frameId}) = port to the content scripts of that tab
    chrome.tabs.connect = function (tabId, info) {
      info = info || {};
      var pid = newPortId();
      var port = makePort(pid, info.name, undefined);
      call('tabs.connect', [tabId, pid, String(info.name || ''), info.frameId === undefined ? null : info.frameId]).then(null, function (e) { closePort(pid, String((e && e.message) || e)); });
      return port;
    };
    // 0.0.430 (S5): installed extensions only; handlers are "ext.<api>" in ExtApi2.kt
    if (GAPS) {
      ['duplicate', 'move', 'goBack', 'goForward', 'getZoom', 'setZoom', 'getZoomSettings', 'setZoomSettings', 'highlight', 'discard'].forEach(function (m) { chrome.tabs[m] = method('tabs.' + m); });
      chrome.tabs.onMoved = makeEvent('tabs.onMoved', true);
      ['getAll', 'getLastFocused', 'remove'].forEach(function (m) { chrome.windows[m] = method('windows.' + m); });
    }
    // 0.0.431 (S6): installed extensions only
    if (GAPS) {
      chrome.runtime.reload = function () { call('runtime.reload', []).then(null, function () { /* ignore */ }); };
      chrome.search = { query: method('search.query') };
      chrome.browsingData = {};
      ['remove', 'removeCache', 'removeCookies', 'removeDownloads', 'removeFileSystems', 'removeFormData', 'removeHistory', 'removeIndexedDB', 'removeLocalStorage', 'removePasswords', 'removeServiceWorkers', 'removeWebSQL', 'removeAppcache', 'settings']
        .forEach(function (m) { chrome.browsingData[m] = method('browsingData.' + m); });
      var q = { QUOTA_BYTES: 102400, QUOTA_BYTES_PER_ITEM: 8192, MAX_ITEMS: 512, MAX_WRITE_OPERATIONS_PER_HOUR: 1800, MAX_WRITE_OPERATIONS_PER_MINUTE: 120 };
      Object.keys(q).forEach(function (k) { storage.sync[k] = q[k]; });
      storage.local.QUOTA_BYTES = 10485760;
      // webNavigation: real main-frame events from Kotlin; the url filter of addListener(fn, {url:[...]}) is applied here (Chrome UrlFilter)
      var wnMatch = function (conds, url) {
        var u; try { u = new URL(url); } catch (e) { return false; }
        if (!Array.isArray(conds) || !conds.length) return true;
        var parts = { host: u.hostname.toLowerCase(), path: u.pathname, query: u.search.replace(/^\?/, ''), url: u.href };
        return conds.some(function (f) {
          f = f || {};
          var ok = true;
          ['host', 'path', 'query', 'url'].forEach(function (p) {
            var v = parts[p], lc = p === 'host', e = f[p + 'Equals'], c = f[p + 'Contains'], a = f[p + 'Prefix'], z = f[p + 'Suffix'];
            if (lc) { e = e === undefined ? e : String(e).toLowerCase(); c = c === undefined ? c : String(c).toLowerCase(); a = a === undefined ? a : String(a).toLowerCase(); z = z === undefined ? z : String(z).toLowerCase(); }
            if (e !== undefined && v !== e) ok = false;
            if (c !== undefined && v.indexOf(c) < 0) ok = false;
            if (a !== undefined && v.indexOf(a) !== 0) ok = false;
            if (z !== undefined && v.slice(-String(z).length) !== z) ok = false;
          });
          if (f.schemes && f.schemes.indexOf(u.protocol.replace(':', '')) < 0) ok = false;
          if (f.ports) {
            var port = Number(u.port || (u.protocol === 'https:' ? 443 : 80));
            if (!f.ports.some(function (r) { return Array.isArray(r) ? port >= r[0] && port <= r[1] : port === r; })) ok = false;
          }
          try {
            if (f.urlMatches !== undefined && !new RegExp(f.urlMatches).test(u.href)) ok = false;
            if (f.originAndPathMatches !== undefined && !new RegExp(f.originAndPathMatches).test(u.origin + u.pathname)) ok = false;
          } catch (e) { ok = false; }
          return ok;
        });
      };
      var wnEvent = function (name) {
        var set = listeners[name] = listeners[name] || [];
        return {
          addListener: function (fn, filter) {
            if (typeof fn !== 'function') return;
            for (var i = 0; i < set.length; i++) if (set[i]._fn === fn) return;
            var conds = filter && filter.url;
            var w = function (d) { if (!conds || wnMatch(conds, d && d.url)) return fn.apply(null, arguments); };
            w._fn = fn; set.push(w);
            call('event.add', [name]).then(null, function () { /* ignore */ });
          },
          removeListener: function (fn) { for (var i = 0; i < set.length; i++) if (set[i]._fn === fn) { set.splice(i, 1); return; } },
          hasListener: function (fn) { for (var i = 0; i < set.length; i++) if (set[i]._fn === fn) return true; return false; },
          hasListeners: function () { return set.length > 0; }
        };
      };
      chrome.webNavigation = { getFrame: method('webNavigation.getFrame'), getAllFrames: method('webNavigation.getAllFrames') };
      ['onBeforeNavigate', 'onCommitted', 'onDOMContentLoaded', 'onCompleted', 'onErrorOccurred', 'onHistoryStateUpdated', 'onReferenceFragmentUpdated'].forEach(function (n) { chrome.webNavigation[n] = wnEvent('webNavigation.' + n); });
      ['onCreatedNavigationTarget', 'onTabReplaced'].forEach(function (n) { chrome.webNavigation[n] = ev('webNavigation.' + n); });
    }
    chrome.scripting.executeScript = wrap(function (inj) { return call('scripting.executeScript', [injection(inj)]); });
    // MV2 style chrome.tabs.executeScript(tabId, {code|file}) -> same path
    chrome.tabs.executeScript = wrap(function (tabId, d) {
      d = d || {};
      var inj = { target: { tabId: tabId } };
      if (d.code) inj.func = '(function(){ return eval(' + JSON.stringify(String(d.code)) + '); })';
      else if (d.file) inj.files = [d.file];
      return call('scripting.executeScript', [injection(inj)]).then(function (r) { return (r || []).map(function (x) { return x.result; }); });
    });
  }

  // ---------- 0.0.413 compat layer ----------
  // APIs that Chrome has and this bridge has not. Many extensions touch them while they load (a listener, a stub call);
  // a missing one threw "undefined" and the whole extension stopped. Rules: JS only, added only where the name is still
  // missing (every real handler above or in Kotlin wins), nothing here fakes data an extension could act on wrongly:
  // lists are empty, "not supported" is a rejected promise with Chrome's wording, events simply never fire.
  function noop() { /* nothing */ }
  function rej(msg) { return function () { return Promise.reject(new Error(msg)); }; }
  function stub(v) { return wrap(function () { return Promise.resolve(typeof v === 'function' ? v.apply(null, arguments) : v); }); }
  function ev(name) { return makeEvent(name, false); }
  function fill(o, spec) { Object.keys(spec).forEach(function (k) { if (o[k] === undefined) o[k] = spec[k]; }); return o; }
  function ns(name, spec) { if (!chrome[name]) chrome[name] = {}; return fill(chrome[name], spec); }
  var NOHOST = 'Specified native messaging host not found.';

  fill(runtime, {
    setUninstallURL: stub(undefined),
    restart: noop,
    restartAfterDelay: noop,
    getPackageDirectoryEntry: wrap(rej('Not supported')),
    sendNativeMessage: wrap(rej(NOHOST)),
    connectNative: function () {
      var pid = newPortId(), port = makePort(pid, '', undefined);
      setTimeout(function () { closePort(pid, NOHOST); }, 0);
      return port;
    },
    onBrowserUpdateAvailable: ev('runtime.onBrowserUpdateAvailable'),
    onRestartRequired: ev('runtime.onRestartRequired')
  });
  fill(chrome.extension, { sendMessage: runtime.sendMessage, onMessage: runtime.onMessage, onRequest: ev('extension.onRequest'), onRequestExternal: ev('extension.onRequestExternal') });
  if (!storage.managed) storage.managed = { get: stub(function () { return {}; }), getBytesInUse: stub(0), onChanged: ev('storage.managed.onChanged') };

  if (isExt) {
    // MV2 names: browserAction is the same object as action (same arguments, same handlers)
    if (!chrome.browserAction && chrome.action) chrome.browserAction = chrome.action;
    ns('pageAction', { show: stub(undefined), hide: stub(undefined), setTitle: stub(undefined), setIcon: stub(undefined), setPopup: stub(undefined), getTitle: stub(''), getPopup: stub(''), onClicked: ev('pageAction.onClicked') });
    if (chrome.action) fill(chrome.action, { onUserSettingsChanged: ev('action.onUserSettingsChanged'), getUserSettings: stub(function () { return { isOnToolbar: true }; }) });
    if (chrome.sidePanel) fill(chrome.sidePanel, { getOptions: stub(function () { return { enabled: true }; }), getPanelBehavior: stub(function () { return { openPanelOnActionClick: false }; }) });
    if (chrome.declarativeNetRequest) fill(chrome.declarativeNetRequest, { getMatchedRules: stub(function () { return { rulesMatchedInfo: [] }; }), onRuleMatchedDebug: ev('declarativeNetRequest.onRuleMatchedDebug') });
    if (chrome.webRequest) fill(chrome.webRequest, { handlerBehaviorChanged: stub(undefined), MAX_HANDLER_BEHAVIOR_CHANGED_CALLS_PER_10_MINUTES: 20, onAuthRequired: ev('webRequest.onAuthRequired'), onBeforeRedirect: ev('webRequest.onBeforeRedirect'), onResponseStarted: ev('webRequest.onResponseStarted') });

    // tabs / windows: the rest of the common surface (one window, so most events never fire)
    fill(chrome.tabs, {
      TAB_ID_NONE: -1,
      getSelected: wrap(function () { return chrome.tabs.query({ active: true, currentWindow: true }).then(function (r) { return r && r[0]; }); }),
      detectLanguage: stub('und'),
      insertCSS: wrap(function (a, b) { // MV2 chrome.tabs.insertCSS([tabId,] {code|file}) -> scripting.insertCSS
        var num = typeof a === 'number', d = (num ? b : a) || {}, inj = { target: {} };
        if (num) inj.target.tabId = a;
        if (d.code) inj.css = String(d.code); else if (d.file) inj.files = [d.file];
        return call('scripting.insertCSS', [inj]).then(function () { return undefined; });
      }),
      onMoved: ev('tabs.onMoved'), onReplaced: ev('tabs.onReplaced'), onHighlighted: ev('tabs.onHighlighted'), onHighlightChanged: ev('tabs.onHighlightChanged'),
      onAttached: ev('tabs.onAttached'), onDetached: ev('tabs.onDetached'), onZoomChange: ev('tabs.onZoomChange')
    });
    ns('windows', {
      WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
      getAll: stub(function () { return [{ id: 1, focused: true, incognito: false, type: 'normal', state: 'normal', alwaysOnTop: false }]; }),
      getLastFocused: wrap(function () { return call('windows.getCurrent', []); }),
      remove: stub(undefined),
      onCreated: ev('windows.onCreated'), onRemoved: ev('windows.onRemoved'), onFocusChanged: ev('windows.onFocusChanged'), onBoundsChanged: ev('windows.onBoundsChanged')
    });

    // webNavigation: main frame only, built from tabs.onUpdated (loading + url = before navigate / committed, complete = DOM / completed).
    // The tabs.onUpdated listener is added only when an extension adds its first webNavigation listener. URL filters are ignored.
    var WN = ['onBeforeNavigate', 'onCommitted', 'onDOMContentLoaded', 'onCompleted', 'onErrorOccurred', 'onCreatedNavigationTarget', 'onHistoryStateUpdated', 'onReferenceFragmentUpdated', 'onTabReplaced'];
    var wn = ns('webNavigation', { getFrame: stub(null), getAllFrames: stub(function () { return []; }) });
    var wnOn = false, wnUrl = {};
    function wnFire(n, tabId, url) { fire('webNavigation.' + n, [{ tabId: tabId, url: url, frameId: 0, parentFrameId: -1, processId: -1, timeStamp: Date.now() }]); }
    function wnStart() {
      if (wnOn) return;
      wnOn = true;
      chrome.tabs.onUpdated.addListener(function (tabId, change, tab) {
        change = change || {};
        if (Object.keys(wnUrl).length > 500) wnUrl = {};
        var url = change.url || (tab && tab.url) || wnUrl[tabId] || '';
        if (change.url) wnUrl[tabId] = change.url;
        if (change.status === 'loading' && change.url) { wnFire('onBeforeNavigate', tabId, url); wnFire('onCommitted', tabId, url); }
        else if (change.status === 'complete' && url) { wnFire('onDOMContentLoaded', tabId, url); wnFire('onCompleted', tabId, url); }
      });
    }
    WN.forEach(function (n) {
      if (GAPS) return; // 0.0.431 (S6): installed extensions get real webNavigation events (block above)
      var e = wn[n] = wn[n] || ev('webNavigation.' + n), add = e.addListener;
      e.addListener = function () { wnStart(); return add.apply(e, arguments); };
    });

    // management: only this extension is visible
    var selfInfo = function () {
      return { id: CFG.id, name: manifest.name || '', shortName: manifest.short_name || manifest.name || '', description: manifest.description || '', version: manifest.version || '',
        mayDisable: true, enabled: true, isApp: false, type: 'extension', installType: 'normal', homepageUrl: manifest.homepage_url || '',
        optionsUrl: manifest.options_page ? runtime.getURL(manifest.options_page) : '',
        permissions: (manifest.permissions || []).filter(function (x) { return typeof x === 'string'; }), hostPermissions: manifest.host_permissions || [] };
    };
    ns('management', {
      getSelf: stub(selfInfo), getAll: stub(function () { return [selfInfo()]; }),
      get: wrap(function (id) { return id === CFG.id ? Promise.resolve(selfInfo()) : Promise.reject(new Error('Failed to get extension. No extension with id ' + id)); }),
      getPermissionWarningsById: stub(function () { return []; }),
      setEnabled: wrap(rej('Not supported')), uninstallSelf: wrap(rej('Not supported')),
      onInstalled: ev('management.onInstalled'), onUninstalled: ev('management.onUninstalled'), onEnabled: ev('management.onEnabled'), onDisabled: ev('management.onDisabled')
    });

    // present but empty: the browser has these (history, bookmarks, ...) natively, the bridge does not expose them yet
    ns('idle', { queryState: stub('active'), setDetectionInterval: stub(undefined), onStateChanged: ev('idle.onStateChanged') });
    ns('history', { search: stub(function () { return []; }), getVisits: stub(function () { return []; }), addUrl: stub(undefined), deleteUrl: stub(undefined), deleteRange: stub(undefined), deleteAll: stub(undefined), onVisited: ev('history.onVisited'), onVisitRemoved: ev('history.onVisitRemoved') });
    ns('bookmarks', {
      getTree: stub(function () { return [{ id: '0', title: '', children: [] }]; }), get: stub(function () { return []; }), getChildren: stub(function () { return []; }),
      getRecent: stub(function () { return []; }), getSubTree: stub(function () { return []; }), search: stub(function () { return []; }),
      create: wrap(rej('Not supported')), update: wrap(rej('Not supported')), move: wrap(rej('Not supported')), remove: wrap(rej('Not supported')), removeTree: wrap(rej('Not supported')),
      onCreated: ev('bookmarks.onCreated'), onRemoved: ev('bookmarks.onRemoved'), onChanged: ev('bookmarks.onChanged'), onMoved: ev('bookmarks.onMoved')
    });
    ns('topSites', { get: stub(function () { return []; }) });
    ns('sessions', { MAX_SESSION_RESULTS: 25, getRecentlyClosed: stub(function () { return []; }), getDevices: stub(function () { return []; }), restore: wrap(rej('Not supported')), onChanged: ev('sessions.onChanged') });
    ns('omnibox', { setDefaultSuggestion: stub(undefined), onInputStarted: ev('omnibox.onInputStarted'), onInputChanged: ev('omnibox.onInputChanged'), onInputEntered: ev('omnibox.onInputEntered'), onInputCancelled: ev('omnibox.onInputCancelled') });
    ns('identity', {
      getRedirectURL: function (path) { return 'https://' + CFG.id + '.chromiumapp.org/' + String(path || '').replace(/^\/+/, ''); },
      getProfileUserInfo: stub(function () { return { email: '', id: '' }; }),
      launchWebAuthFlow: wrap(rej('Not supported')), getAuthToken: wrap(rej('Not supported')), removeCachedAuthToken: stub(undefined),
      onSignInChanged: ev('identity.onSignInChanged')
    });
  }

  // ---------- 0.0.429 (S4): safety net for INSTALLED extensions ----------
  // chrome.* names that Chrome has and this bridge has not: added only where the name is still missing (real handlers always win).
  // A method answers like Chrome does for an API that is not available: the promise rejects / runtime.lastError is set with
  // "Not supported in Thazir: <api>" (never fake data, never a throw); an event is an object that never fires.
  // Every call of such a method is counted (message "__miss", max 1 per api per 5 s) -> Report center "Extension API gaps".
  if (GAPS) {
    var missAt = {}, UNS = 'Not supported in Thazir: ';
    var miss = function (api) {
      var now = Date.now();
      if (missAt[api] && now - missAt[api] < 5000) return;
      if (Object.keys(missAt).length > 300) missAt = {};
      missAt[api] = now;
      try { send({ i: 0, a: '__miss', p: [api] }); } catch (e) { /* ignore */ }
    };
    var unsup = function (api) { return wrap(function () { miss(api); return Promise.reject(new Error(UNS + api)); }); };
    var CSET = { m: ['get', 'set', 'clear'], e: ['onChange'] }; // chrome.types.ChromeSetting
    var GAP = {
      'tabs': { m: ['group', 'ungroup'] },
      'tabGroups': { m: ['get', 'query', 'update', 'move'], e: ['onCreated', 'onUpdated', 'onMoved', 'onRemoved'], c: { TAB_GROUP_ID_NONE: -1 } },
      'browsingData': { m: ['remove', 'removeCache', 'removeCookies', 'removeDownloads', 'removeFormData', 'removeHistory', 'removeLocalStorage', 'removePasswords', 'removeServiceWorkers', 'removeIndexedDB', 'removeFileSystems', 'removeWebSQL', 'removeAppcache', 'settings'] },
      'search': { m: ['query'] },
      'clipboard': { m: ['setImageData'], e: ['onClipboardDataChanged'] },
      'tts': { m: ['speak', 'stop', 'pause', 'resume', 'isSpeaking', 'getVoices'], e: ['onEvent'] },
      'power': { m: ['requestKeepAwake', 'releaseKeepAwake', 'reportActivity'] },
      'privacy.network': CSET, 'privacy.services': CSET, 'privacy.websites': CSET,
      'proxy': { e: ['onProxyError'] }, 'proxy.settings': CSET,
      'readingList': { m: ['addEntry', 'query', 'removeEntry', 'updateEntry'], e: ['onEntryAdded', 'onEntryRemoved', 'onEntryUpdated'] },
      'userScripts': { m: ['register', 'unregister', 'getScripts', 'update', 'configureWorld', 'resetWorldConfiguration'] },
      'debugger': { m: ['attach', 'detach', 'sendCommand', 'getTargets'], e: ['onEvent', 'onDetach'] },
      'gcm': { m: ['register', 'unregister', 'send'], e: ['onMessage', 'onMessagesDeleted', 'onSendError'], c: { MAX_MESSAGE_SIZE: 4096 } },
      'system.cpu': { m: ['getInfo'] }, 'system.memory': { m: ['getInfo'] },
      'system.storage': { m: ['getInfo', 'ejectDevice', 'getAvailableCapacity'], e: ['onAttached', 'onDetached'] },
      'system.display': { m: ['getInfo', 'getDisplayLayout'], e: ['onDisplayChanged'] },
      'downloads': { m: ['acceptDanger', 'setShelfEnabled', 'getFileIcon', 'setUiOptions'], e: ['onErased', 'onDeterminingFilename'] },
      'notifications': { m: ['getAll', 'getPermissionLevel'], e: ['onPermissionLevelChanged', 'onShowSettings'] },
      'permissions': { m: ['remove'], e: ['onAdded', 'onRemoved'] },
      'scripting': { m: ['removeCSS'] },
      'runtime': { m: ['getBackgroundPage'] }
    };
    Object.keys(GAP).forEach(function (path) {
      var o = chrome, parts = path.split('.'), s = GAP[path], i;
      for (i = 0; i < parts.length; i++) {
        if (o[parts[i]] === undefined) o[parts[i]] = {};
        o = o[parts[i]];
        if (!o || typeof o !== 'object' && typeof o !== 'function') return; // a real non-object member: leave it
      }
      (s.m || []).forEach(function (m) { if (o[m] === undefined) o[m] = unsup(path + '.' + m); });
      (s.e || []).forEach(function (e) { if (o[e] === undefined) o[e] = ev(path + '.' + e); });
      Object.keys(s.c || {}).forEach(function (k) { if (o[k] === undefined) o[k] = s.c[k]; });
    });
  }

  try { send({ i: 0, a: '__hello', p: [CFG.kind] }); } catch (e) { return null; }
  try { G.addEventListener('pagehide', function () { try { send({ i: 0, a: '__bye', p: [] }); } catch (e) { /* ignore */ } }); } catch (e) { /* ignore */ }
  return chrome;
})
