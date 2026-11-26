(function () {
  if (window.__idmHook) return;
  window.__idmHook = 1;
  function send(u, t, h) {
    try {
      if (t && t.length < 3000000 && t.indexOf('http') >= 0) IDMBridge.onJson(String(u), t, h || '');
    } catch (e) {}
  }
  function hdrs(h) {
    var s = '';
    try {
      if (!h) return s;
      if (typeof h.forEach === 'function' && !Array.isArray(h)) {
        h.forEach(function (v, k) { s += k + ': ' + v + '\n'; });
      } else if (Array.isArray(h)) {
        h.forEach(function (p) { s += p[0] + ': ' + p[1] + '\n'; });
      } else {
        for (var k in h) s += k + ': ' + h[k] + '\n';
      }
    } catch (e) {}
    return s;
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function (i, init) {
      var u = (typeof i === 'string') ? i : (i && i.url);
      var h = hdrs((init && init.headers) || (i && i.headers));
      return of.apply(this, arguments).then(function (r) {
        try {
          var c = (r.headers && r.headers.get('content-type')) || '';
          if (/json|text|mpegurl/i.test(c)) r.clone().text().then(function (t) { send(u, t, h); });
        } catch (e) {}
        return r;
      });
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send, xh = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function (m, u) { this.__u = u; this.__h = ''; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) { this.__h = (this.__h || '') + k + ': ' + v + '\n'; return xh.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var x = this;
    x.addEventListener('load', function () {
      try {
        var c = x.getResponseHeader('content-type') || '';
        if (/json|text|mpegurl/i.test(c) && (x.responseType === '' || x.responseType === 'text')) send(x.__u, x.responseText, x.__h);
      } catch (e) {}
    });
    return xs.apply(this, arguments);
  };
})();
