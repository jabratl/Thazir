/* 0.0.434 (S9): CSS compat for OLD WebView. Added ONLY when ExtCssCompat.needed() (WebView < 120 or ext_css On).
   Rewrites style text that scripts add: native nesting -> flat rules, @layer -> flattened (source order kept),
   @property -> initial-value declarations. Fail-open: any error = the original CSS is used untouched. */
(function () {
  'use strict';
  var MAXC = 64, MAXCH = 2000000, cache = new Map(), cacheCh = 0;

  // ---------------------------------------------------------------- tokenizer helpers
  // index of the first top-level char in set (not inside string / comment / () / []), or -1
  function skipStr(s, i) { var q = s[i]; i++; while (i < s.length) { var c = s[i]; if (c === '\\') i += 2; else if (c === q) return i + 1; else i++; } return i; }
  function skipCom(s, i) { var e = s.indexOf('*/', i + 2); return e < 0 ? s.length : e + 2; }
  function findTop(s, i, set) {
    var dp = 0, n = s.length;
    while (i < n) {
      var c = s[i];
      if (c === '"' || c === "'") { i = skipStr(s, i); continue; }
      if (c === '/' && s[i + 1] === '*') { i = skipCom(s, i); continue; }
      if (c === '\\') { i += 2; continue; }
      if (c === '(' || c === '[') dp++;
      else if (c === ')' || c === ']') { if (dp > 0) dp--; }
      else if (dp === 0 && set.indexOf(c) >= 0) return i;
      i++;
    }
    return -1;
  }
  function matchBrace(s, i) { // s[i] === '{' ; returns index of matching '}' or -1
    var d = 0, n = s.length;
    while (i < n) {
      var c = s[i];
      if (c === '"' || c === "'") { i = skipStr(s, i); continue; }
      if (c === '/' && s[i + 1] === '*') { i = skipCom(s, i); continue; }
      if (c === '\\') { i += 2; continue; }
      if (c === '{') d++;
      else if (c === '}') { d--; if (d === 0) return i; }
      i++;
    }
    return -1;
  }
  function stripCom(s) { // comments out (keeps /*! */ nothing; not needed for layout)
    if (s.indexOf('/*') < 0) return s;
    var o = '', i = 0, n = s.length;
    while (i < n) {
      var c = s[i];
      if (c === '"' || c === "'") { var j = skipStr(s, i); o += s.slice(i, j); i = j; continue; }
      if (c === '/' && s[i + 1] === '*') { i = skipCom(s, i); continue; }
      o += c; i++;
    }
    return o;
  }
  function splitComma(s) {
    var out = [], i = 0, st = 0;
    for (;;) {
      var k = findTop(s, i, ',');
      if (k < 0) { out.push(s.slice(st)); break; }
      out.push(s.slice(st, k)); st = i = k + 1;
    }
    return out.map(function (x) { return x.trim(); }).filter(Boolean);
  }

  // ---------------------------------------------------------------- parser -> nodes
  // node: {k:'decl', t} | {k:'rule', sel, body} | {k:'at', name, pre, body|null} | {k:'raw', t}
  function parse(s) {
    var out = [], i = 0, n = s.length;
    while (i < n) {
      while (i < n && /\s/.test(s[i])) i++;
      if (i >= n) break;
      if (s[i] === ';') { i++; continue; }
      var e = findTop(s, i, ';{}');
      if (e < 0) { var tail = s.slice(i).trim(); if (tail) out.push({ k: 'decl', t: tail }); break; }
      var ch = s[e];
      if (ch === '}') { var d0 = s.slice(i, e).trim(); if (d0) out.push({ k: 'decl', t: d0 }); i = e + 1; continue; }
      var pre = s.slice(i, e).trim();
      if (ch === ';') { if (pre) out.push(pre[0] === '@' ? atNode(pre, null) : { k: 'decl', t: pre }); i = e + 1; continue; }
      // ch === '{'
      var cl = matchBrace(s, e);
      if (cl < 0) throw new Error('unbalanced');
      var inner = s.slice(e + 1, cl);
      if (/^--[^\s:]*\s*:/.test(pre)) { // custom property whose value has braces: keep as declaration
        var semi = findTop(s, cl + 1, ';}');
        var end2 = semi < 0 ? n : semi;
        out.push({ k: 'decl', t: s.slice(i, end2).trim() });
        i = semi < 0 ? n : (s[semi] === ';' ? semi + 1 : semi);
        continue;
      }
      if (pre[0] === '@') out.push(atNode(pre, inner));
      else out.push({ k: 'rule', sel: pre, body: parse(inner) });
      i = cl + 1;
    }
    return out;
  }
  function atNode(pre, inner) {
    var m = /^@([\w-]+)\s*([\s\S]*)$/.exec(pre) || ['', '', ''];
    return { k: 'at', name: m[1].toLowerCase(), pre: m[2].trim(), raw: pre, body: inner === null ? null : (/^(media|supports|container|layer|document|scope|starting-style)$/i.test(m[1]) ? parse(inner) : null), inner: inner };
  }

  // ---------------------------------------------------------------- selector resolve
  function repAmp(sel, par) { // replace & outside strings
    var o = '', i = 0, n = sel.length;
    while (i < n) {
      var c = sel[i];
      if (c === '"' || c === "'") { var j = skipStr(sel, i); o += sel.slice(i, j); i = j; continue; }
      if (c === '\\') { o += sel.slice(i, i + 2); i += 2; continue; }
      if (c === '&') { o += par; i++; continue; }
      o += c; i++;
    }
    return o;
  }
  function hasAmp(sel) { return repAmp(sel, '\u0001') !== sel; }
  function resolve(selText, parents) { // parents = array of selector strings or null
    var parts = splitComma(selText);
    if (!parents) return parts;
    var out = [];
    for (var a = 0; a < parts.length; a++) {
      for (var b = 0; b < parents.length; b++) {
        var p = parts[a];
        out.push(hasAmp(p) ? repAmp(p, parents[b]) : parents[b] + ' ' + p);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- flatten
  function flatten(nodes, parents, st) {
    var out = '', run = '';
    function flushRun() {
      if (run && parents) out += parents.join(',') + '{' + run + '}';
      else if (run) out += run; // top level declarations (inside @font-face etc. handled elsewhere)
      run = '';
    }
    for (var i = 0; i < nodes.length; i++) {
      var nd = nodes[i];
      if (nd.k === 'decl') { run += nd.t + ';'; continue; }
      if (nd.k === 'rule') {
        flushRun();
        if (parents) st.changed = true;
        var sels = resolve(nd.sel, parents);
        out += flatten(nd.body, sels, st);
        continue;
      }
      if (nd.k === 'at') {
        if (nd.name === 'layer') {
          flushRun();
          st.changed = true;
          if (nd.body) out += flatten(nd.body, parents, st); // block: contents stay, layer name dropped; statement: dropped
          continue;
        }
        if (nd.name === 'property') {
          flushRun();
          st.changed = true;
          var nm = nd.pre, ini = null, inh = false, ms;
          var rx = /(^|;)\s*(syntax|inherits|initial-value)\s*:\s*([^;]*)/gi;
          while ((ms = rx.exec(nd.inner || ''))) {
            var key = ms[2].toLowerCase(), val = ms[3].trim();
            if (key === 'initial-value') ini = val; else if (key === 'inherits') inh = /^true$/i.test(val);
          }
          if (nm && ini !== null && ini !== '') st.props.push({ n: nm, v: ini, inh: inh });
          continue;
        }
        if (nd.body && /^(media|supports|container|document|scope|starting-style)$/.test(nd.name)) {
          flushRun();
          if (parents) st.changed = true;
          var inner = flatten(nd.body, parents, st);
          if (inner) out += '@' + nd.name + ' ' + nd.pre + '{' + inner + '}';
          continue;
        }
        flushRun();
        out += nd.inner === null ? nd.raw + ';' : nd.raw + '{' + nd.inner + '}'; // @keyframes, @font-face, @import ... as is
        continue;
      }
    }
    flushRun();
    return out;
  }

  // ---------------------------------------------------------------- public rewrite
  function rewrite(css) {
    if (typeof css !== 'string' || css.length < 4) return css;
    var hit = cache.get(css);
    if (hit !== undefined) return hit;
    var res = css;
    try {
      var text = stripCom(css);
      var st = { changed: false, props: [] };
      var out = flatten(parse(text), null, st);
      if (st.changed) {
        var head = '';
        for (var i = 0; i < st.props.length; i++) {
          var p = st.props[i];
          head += (p.inh ? ':root,:host' : '*,::before,::after,::backdrop') + '{' + p.n + ':' + p.v + '}';
        }
        res = head + out;
      }
    } catch (e) { res = css; }
    put(css, res);
    return res;
  }
  function put(k, v) {
    if (cache.size >= MAXC || cacheCh + k.length + v.length > MAXCH) { cache.clear(); cacheCh = 0; }
    if (k.length + v.length > MAXCH) return;
    cache.set(k, v); cacheCh += k.length + v.length;
  }
  function rewriteList(css) { // top-level statements of the rewritten css (for insertRule)
    var r = rewrite(css), out = [], i = 0, n = r.length;
    try {
      while (i < n) {
        var e = findTop(r, i, ';{');
        if (e < 0) { var t = r.slice(i).trim(); if (t) out.push(t); break; }
        if (r[e] === ';') { out.push(r.slice(i, e + 1).trim()); i = e + 1; continue; }
        var c = matchBrace(r, e);
        if (c < 0) return [css];
        out.push(r.slice(i, c + 1).trim()); i = c + 1;
      }
    } catch (x) { return [css]; }
    return out;
  }

  if (typeof window === 'undefined' || !window.document) { // node test
    if (typeof module !== 'undefined' && module.exports) module.exports = { rewrite: rewrite, rewriteList: rewriteList };
    return;
  }
  if (window.__mgbCssCompat) return;
  try { Object.defineProperty(window, '__mgbCssCompat', { value: 1 }); } catch (e) { return; }

  // ---------------------------------------------------------------- hooks
  var done = new WeakSet();
  function isStyle(n) { return n && n.nodeType === 1 && n.nodeName === 'STYLE'; }
  function fixStyle(el) {
    try {
      if (!isStyle(el) || done.has(el)) return;
      var t = el.textContent;
      if (t) { var r = rewrite(t); if (r !== t) setText.call(el, r); }
      done.add(el);
    } catch (e) { }
  }
  function fixTree(root) {
    try {
      if (!root) return;
      if (isStyle(root)) { fixStyle(root); return; }
      if (root.querySelectorAll) { var l = root.querySelectorAll('style'); for (var i = 0; i < l.length; i++) fixStyle(l[i]); }
    } catch (e) { }
  }
  var tcDesc = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
  var setText = tcDesc && tcDesc.set ? tcDesc.set : function (v) { this.textContent = v; };

  function wrapNode(proto, name, argFix) {
    var o = proto[name];
    if (typeof o !== 'function') return;
    proto[name] = function () {
      try { argFix(this, arguments); } catch (e) { }
      var r = o.apply(this, arguments);
      try { for (var i = 0; i < arguments.length; i++) { var a = arguments[i]; if (a && a.nodeType) fixTree(a); } } catch (e) { }
      return r;
    };
  }
  function textInto(self, args) { // text node appended into a STYLE element
    if (!isStyle(self)) return;
    for (var i = 0; i < args.length; i++) { var a = args[i]; if (a && a.nodeType === 3) { var r = rewrite(a.data); if (r !== a.data) a.data = r; } }
  }
  ['appendChild', 'insertBefore', 'replaceChild'].forEach(function (m) { wrapNode(Node.prototype, m, textInto); });
  [Element.prototype, DocumentFragment.prototype].forEach(function (p) {
    ['append', 'prepend', 'after', 'before', 'replaceWith', 'replaceChildren'].forEach(function (m) { if (p.hasOwnProperty(m)) wrapNode(p, m, function () { }); });
  });
  ['after', 'before', 'replaceWith'].forEach(function (m) { try { if (!Element.prototype.hasOwnProperty(m) && ChildNode && ChildNode.prototype[m]) wrapNode(ChildNode.prototype, m, function () { }); } catch (e) { } });

  // style.textContent / innerText = css
  try {
    var hs = HTMLStyleElement.prototype;
    if (tcDesc && tcDesc.set) Object.defineProperty(hs, 'textContent', { configurable: true, enumerable: tcDesc.enumerable, get: tcDesc.get, set: function (v) { try { v = rewrite(String(v)); } catch (e) { } tcDesc.set.call(this, v); } });
    var itd = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'innerText');
    if (itd && itd.set) Object.defineProperty(hs, 'innerText', { configurable: true, enumerable: itd.enumerable, get: itd.get, set: function (v) { try { v = rewrite(String(v)); } catch (e) { } itd.set.call(this, v); } });
  } catch (e) { }

  // innerHTML / insertAdjacentHTML that contain <style
  try {
    var ihd = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
    if (ihd && ihd.set) Object.defineProperty(Element.prototype, 'innerHTML', { configurable: true, enumerable: ihd.enumerable, get: ihd.get, set: function (v) { ihd.set.call(this, v); try { if (/<style/i.test(String(v))) fixTree(this); } catch (e) { } } });
    var iah = Element.prototype.insertAdjacentHTML;
    if (typeof iah === 'function') Element.prototype.insertAdjacentHTML = function (pos, html) { var r = iah.apply(this, arguments); try { if (/<style/i.test(String(html))) fixTree(/^(before|after)/i.test(pos) ? this.parentNode : this); } catch (e) { } return r; };
  } catch (e) { }

  // constructed sheets + insertRule
  try {
    var sp = CSSStyleSheet.prototype;
    if (typeof sp.replaceSync === 'function') { var rs = sp.replaceSync; sp.replaceSync = function (t) { try { t = rewrite(String(t)); } catch (e) { } return rs.call(this, t); }; }
    if (typeof sp.replace === 'function') { var rp = sp.replace; sp.replace = function (t) { try { t = rewrite(String(t)); } catch (e) { } return rp.call(this, t); }; }
    var ir = sp.insertRule;
    if (typeof ir === 'function') sp.insertRule = function (rule, idx) {
      var parts;
      try { parts = rewriteList(String(rule)); } catch (e) { parts = null; }
      if (!parts || (parts.length === 1 && parts[0] === String(rule))) return ir.apply(this, arguments);
      var at = idx === undefined ? 0 : idx;
      for (var i = 0; i < parts.length; i++) { try { ir.call(this, parts[i], at); at++; } catch (e) { } }
      return idx || 0;
    };
  } catch (e) { }
})();
