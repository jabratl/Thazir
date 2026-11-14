/* 0.0.434 (S9): CSS compat for OLD WebView. Added ONLY when ExtCssCompat.needed() (WebView < 120 or ext_css On).
   Rewrites style text that scripts add: native nesting -> flat rules, @layer -> flattened (source order kept),
   @property -> initial-value declarations. Fail-open: any error = the original CSS is used untouched. */
(function () {
  'use strict';
  var STATS = { n: 0, ms: 0 };
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

  // ---------------------------------------------------------------- colours / units / inset (S10), declaration level only
  var NAMED = { black: [0, 0, 0, 1], white: [255, 255, 255, 1], transparent: [0, 0, 0, 0], red: [255, 0, 0, 1], blue: [0, 0, 255, 1], green: [0, 128, 0, 1] };
  function g2l(c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
  function l2g(c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055; }
  function oklab2lin(L, a, b) {
    var l_ = L + 0.3963377774 * a + 0.2158037573 * b, m_ = L - 0.1055613458 * a - 0.0638541728 * b, s_ = L - 0.0894841775 * a - 1.2914855480 * b;
    var l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
    return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s];
  }
  function lin2oklab(r, g, b) {
    var l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b), m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b), s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    return [0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s];
  }
  function lab2lin(L, a, b) { // CIE Lab (D50) -> linear sRGB
    var fy = (L + 16) / 116, fx = a / 500 + fy, fz = fy - b / 200, e = 216 / 24389, k = 24389 / 27;
    var xr = fx * fx * fx > e ? fx * fx * fx : (116 * fx - 16) / k, yr = L > k * e ? fy * fy * fy : L / k, zr = fz * fz * fz > e ? fz * fz * fz : (116 * fz - 16) / k;
    var X = xr * 0.96422, Y = yr, Z = zr * 0.82521;
    var x = 0.9554734527042182 * X - 0.0230985368742614 * Y + 0.0632593086610217 * Z, y = -0.0283697069632081 * X + 1.0099953980813041 * Y + 0.0210413989244203 * Z, z = 0.0123140016883199 * X - 0.0205076964334779 * Y + 1.3303659366080753 * Z;
    return [3.2409699419045226 * x - 1.537383177570094 * y - 0.4986107602930034 * z, -0.9692436362808796 * x + 1.8759675015077202 * y + 0.0415550574071756 * z, 0.0556300796969936 * x - 0.2039769588889765 * y + 1.0569715142428786 * z];
  }
  function num(t, pct) { // "12", "12%", "none", "30deg" -> number (pct = value of 100%), null if not literal
    t = String(t).trim().toLowerCase();
    if (t === 'none') return 0;
    var m = /^([+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?)(%|deg|grad|rad|turn)?$/.exec(t);
    if (!m) return null;
    var v = parseFloat(m[1]), u = m[2];
    if (u === '%') return pct === undefined ? null : v / 100 * pct;
    if (u === 'grad') return v * 0.9;
    if (u === 'rad') return v * 180 / Math.PI;
    if (u === 'turn') return v * 360;
    return v;
  }
  function alphaOf(t) { if (t === undefined) return 1; var a = num(t, 1); return a === null ? null : Math.max(0, Math.min(1, a)); }
  function splitSlash(args) { var k = findTop(args, 0, '/'); return k < 0 ? [args, undefined] : [args.slice(0, k), args.slice(k + 1)]; }
  function enc(lin, a) { return [Math.max(0, Math.min(1, l2g(Math.max(0, Math.min(1, lin[0]))))), Math.max(0, Math.min(1, l2g(Math.max(0, Math.min(1, lin[1]))))), Math.max(0, Math.min(1, l2g(Math.max(0, Math.min(1, lin[2]))))), a]; } // -> [r,g,b 0..1, a]
  function parseColor(t) { // -> [r,g,b 0..1 gamma encoded, a] or null
    t = String(t).trim();
    var low = t.toLowerCase(), m;
    if (NAMED[low]) { var n = NAMED[low]; return [n[0] / 255, n[1] / 255, n[2] / 255, n[3]]; }
    if ((m = /^#([0-9a-f]{3,8})$/i.exec(t))) {
      var h = m[1];
      if (h.length === 3 || h.length === 4) h = h.replace(/./g, function (c) { return c + c; });
      if (h.length !== 6 && h.length !== 8) return null;
      return [parseInt(h.substr(0, 2), 16) / 255, parseInt(h.substr(2, 2), 16) / 255, parseInt(h.substr(4, 2), 16) / 255, h.length === 8 ? parseInt(h.substr(6, 2), 16) / 255 : 1];
    }
    if (!(m = /^([a-z-]+)\(([\s\S]*)\)$/i.exec(t))) return null;
    var fn = m[1].toLowerCase(), sp = splitSlash(m[2]), parts = sp[0].trim().split(/[\s,]+/).filter(Boolean), al, v;
    if (fn === 'color-mix') return parseMix(m[2]);
    if (fn === 'rgb' || fn === 'rgba') {
      if (parts.length === 4 && sp[1] === undefined) { sp[1] = parts[3]; parts.length = 3; }
      if (parts.length !== 3 || parts.join('').indexOf('from') >= 0) return null;
      al = alphaOf(sp[1]); if (al === null) return null;
      v = parts.map(function (p) { var x = num(p, 255); return x === null ? null : Math.max(0, Math.min(255, x)) / 255; });
      return v.indexOf(null) >= 0 ? null : [v[0], v[1], v[2], al];
    }
    if (parts.length !== 3 || /^from$/i.test(parts[0])) return null;
    al = alphaOf(sp[1]); if (al === null) return null;
    var a0, a1, a2;
    if (fn === 'oklch') { a0 = num(parts[0], 1); a1 = num(parts[1], 0.4); a2 = num(parts[2]); if (a0 === null || a1 === null || a2 === null) return null; var hr = a2 * Math.PI / 180; return enc(oklab2lin(a0, a1 * Math.cos(hr), a1 * Math.sin(hr)), al); }
    if (fn === 'oklab') { a0 = num(parts[0], 1); a1 = num(parts[1], 0.4); a2 = num(parts[2], 0.4); if (a0 === null || a1 === null || a2 === null) return null; return enc(oklab2lin(a0, a1, a2), al); }
    if (fn === 'lab') { a0 = num(parts[0], 100); a1 = num(parts[1], 125); a2 = num(parts[2], 125); if (a0 === null || a1 === null || a2 === null) return null; return enc(lab2lin(a0, a1, a2), al); }
    if (fn === 'lch') { a0 = num(parts[0], 100); a1 = num(parts[1], 150); a2 = num(parts[2]); if (a0 === null || a1 === null || a2 === null) return null; var h2 = a2 * Math.PI / 180; return enc(lab2lin(a0, a1 * Math.cos(h2), a1 * Math.sin(h2)), al); }
    if (fn === 'hwb') {
      a0 = num(parts[0]); a1 = num(parts[1], 100); a2 = num(parts[2], 100); if (a0 === null || a1 === null || a2 === null) return null;
      var w = a1 / 100, bk = a2 / 100; if (w + bk >= 1) { var gr = w / (w + bk); return [gr, gr, gr, al]; }
      var hh = ((a0 % 360) + 360) % 360 / 60, c = [0, 0, 0];
      var X = 1 - Math.abs(hh % 2 - 1), q = Math.floor(hh);
      c = [[1, X, 0], [X, 1, 0], [0, 1, X], [0, X, 1], [X, 0, 1], [1, 0, X]][q % 6];
      return [c[0] * (1 - w - bk) + w, c[1] * (1 - w - bk) + w, c[2] * (1 - w - bk) + w, al];
    }
    if (fn === 'color') {
      if (!/^srgb$/i.test(parts[0])) return null; // only srgb
      return null;
    }
    return null;
  }
  function parseMix(args) { // args of color-mix( ... ) -> [r,g,b,a] or null (null = leave the declaration as it is)
    var parts = splitComma(args);
    if (parts.length !== 3) return null;
    var sm = /^in\s+(srgb|oklab)$/i.exec(parts[0].trim());
    if (!sm) return null;
    var space = sm[1].toLowerCase(), cols = [], pc = [];
    for (var i = 1; i < 3; i++) {
      var t = parts[i].trim(), pm = /^([\s\S]*?)\s+([\d.]+%)$/.exec(t) || /^([\d.]+%)\s+([\s\S]*)$/.exec(t), cs = t, p = null;
      if (pm) { var isFirst = /^[\d.]+%$/.test(pm[1]); cs = isFirst ? pm[2] : pm[1]; p = parseFloat(isFirst ? pm[1] : pm[2]); }
      var c = parseColor(cs); if (!c) return null;
      cols.push(c); pc.push(p);
    }
    var p1 = pc[0], p2 = pc[1];
    if (p1 === null && p2 === null) { p1 = 50; p2 = 50; } else if (p1 === null) p1 = 100 - p2; else if (p2 === null) p2 = 100 - p1;
    var sum = p1 + p2; if (!(sum > 0)) return null;
    var am = sum < 100 ? sum / 100 : 1; p1 /= sum; p2 /= sum;
    function toS(c) { if (space === 'srgb') return [c[0], c[1], c[2]]; var l = lin2oklab(g2l(c[0]), g2l(c[1]), g2l(c[2])); return l; }
    var s1 = toS(cols[0]), s2 = toS(cols[1]), a1 = cols[0][3], a2 = cols[1][3];
    var a = a1 * p1 + a2 * p2, mix = [0, 0, 0];
    for (var k = 0; k < 3; k++) mix[k] = a === 0 ? 0 : (s1[k] * a1 * p1 + s2[k] * a2 * p2) / a;
    var rgb = space === 'srgb' ? mix : (function () { var l = oklab2lin(mix[0], mix[1], mix[2]); return [l2g(Math.max(0, Math.min(1, l[0]))), l2g(Math.max(0, Math.min(1, l[1]))), l2g(Math.max(0, Math.min(1, l[2])))]; })();
    return [Math.max(0, Math.min(1, rgb[0])), Math.max(0, Math.min(1, rgb[1])), Math.max(0, Math.min(1, rgb[2])), a * am];
  }
  function fmt(c) {
    var r = Math.round(c[0] * 255), g = Math.round(c[1] * 255), b = Math.round(c[2] * 255), a = Math.round(c[3] * 1000) / 1000;
    return a >= 1 ? 'rgb(' + r + ',' + g + ',' + b + ')' : 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }
  var FNRX = /(^|[^\w-])(color-mix|oklch|oklab|lch|lab|hwb)\(/gi;
  function convColors(val, st) { // -> {v: converted text, fb: static fallback colour or null}
    if (!FNRX.test(val)) { FNRX.lastIndex = 0; return { v: val, fb: null }; }
    FNRX.lastIndex = 0;
    var out = '', i = 0, fb = null, m;
    while ((m = FNRX.exec(val))) {
      var st0 = m.index + m[1].length, op = st0 + m[2].length;
      if (st0 < i) continue;
      var d = 0, j = op, n = val.length;
      for (; j < n; j++) { var c = val[j]; if (c === '"' || c === "'") { j = skipStr(val, j) - 1; continue; } if (c === '(') d++; else if (c === ')') { d--; if (d === 0) break; } }
      if (j >= n) break;
      var whole = val.slice(st0, j + 1), rgb = null;
      try { rgb = parseColor(whole); } catch (e) { rgb = null; }
      if (rgb) { out += val.slice(i, st0) + fmt(rgb); i = j + 1; FNRX.lastIndex = j + 1; st.n++; }
      else if (/^color-mix/i.test(whole) && fb === null) { // first colour literal -> static fallback before the original declaration
        var pr = splitComma(whole.slice(op - st0 + 1, whole.length - 1)), c1 = pr[1] ? pr[1].trim().replace(/\s+[\d.]+%$/, '').replace(/^[\d.]+%\s+/, '') : '', pc = null;
        try { pc = parseColor(c1); } catch (e) { pc = null; }
        if (pc) fb = fmt(pc);
        FNRX.lastIndex = j + 1;
      } else FNRX.lastIndex = j + 1;
    }
    FNRX.lastIndex = 0;
    return { v: out + val.slice(i), fb: fb };
  }
  function splitWs(s) { var o = [], d = 0, cur = ''; for (var i = 0; i < s.length; i++) { var c = s[i]; if (c === '(') d++; else if (c === ')') d--; if (/\s/.test(c) && d === 0) { if (cur) o.push(cur); cur = ''; } else cur += c; } if (cur) o.push(cur); return o; }
  function fixDecl(t, st) { // one declaration text -> one or more declarations text (each ends with ';')
    var i = t.indexOf(':');
    if (i < 1) return t + ';';
    var prop = t.slice(0, i).trim(), val = t.slice(i + 1).trim(), imp = '', mi = /\s*!important\s*$/i.exec(val);
    if (mi) { imp = ' !important'; val = val.slice(0, mi.index); }
    var v0 = val, pre = '';
    if (/[a-z]\(/i.test(val) && /color-mix|oklch|oklab|lch|lab|hwb/i.test(val)) {
      var r = convColors(val, st); val = r.v;
      if (r.fb && val.indexOf('color-mix') >= 0) pre = prop + ':' + r.fb + imp + ';';
    }
    if (/(dvh|svh|lvh|dvw|svw|lvw|dvi|svi|lvi|dvb|svb|lvb|dvmin|svmin|lvmin|dvmax|svmax|lvmax)\b/i.test(val)) {
      val = val.replace(/(\d)(?:dvh|svh|lvh|dvb|svb|lvb)\b/gi, '$1vh').replace(/(\d)(?:dvw|svw|lvw|dvi|svi|lvi)\b/gi, '$1vw').replace(/(\d)(?:dvmin|svmin|lvmin)\b/gi, '$1vmin').replace(/(\d)(?:dvmax|svmax|lvmax)\b/gi, '$1vmax');
    }
    var o;
    if (prop.toLowerCase() === 'inset') {
      var p = splitWs(val);
      if (p.length >= 1 && p.length <= 4 && !/^(inherit|initial|unset|revert|revert-layer)$/i.test(val)) {
        var tp = p[0], rt = p.length > 1 ? p[1] : p[0], bt = p.length > 2 ? p[2] : p[0], lf = p.length > 3 ? p[3] : rt;
        o = 'top:' + tp + imp + ';right:' + rt + imp + ';bottom:' + bt + imp + ';left:' + lf + imp + ';';
        st.n++; return pre + o;
      }
    }
    if (val === v0 && !pre) return t + ';';
    st.n++;
    return pre + prop + ':' + val + imp + ';';
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
      if (nd.k === 'decl') { var fd = fixDecl(nd.t, st); if (fd !== nd.t + ';') st.changed = true; run += fd; continue; }
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
      var st = { changed: false, props: [], n: 0 }, t0 = Date.now();
      var out = flatten(parse(text), null, st);
      if (st.changed) {
        var head = '';
        for (var i = 0; i < st.props.length; i++) {
          var p = st.props[i];
          head += (p.inh ? ':root,:host' : '*,::before,::after,::backdrop') + '{' + p.n + ':' + p.v + '}';
        }
        res = head + out;
        STATS.n++; STATS.ms += Date.now() - t0;
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
  try { Object.defineProperty(window, '__mgbCssCompat', { value: 1 }); Object.defineProperty(window, '__mgbCssStats', { value: STATS }); } catch (e) { return; }

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
