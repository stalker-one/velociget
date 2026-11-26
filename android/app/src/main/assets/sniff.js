(function () {
  var B = String.fromCharCode(92);
  var h = document.documentElement.outerHTML;
  h = h.split(B + '/').join('/').split(B + 'u0026').join('&').split('&amp;').join('&');
  var out = [], m, re = /https?:\/\/[^"'<>\s\\]+?\.(?:mp4|webm|m4a|mp3|mov)(?:\?[^"'<>\s\\]*)?/gi;
  while ((m = re.exec(h)) && out.length < 60) out.push(m[0]);
  var vs = document.querySelectorAll('video,source,audio');
  for (var i = 0; i < vs.length; i++) {
    var s = vs[i].currentSrc || vs[i].src;
    if (s && s.indexOf('blob:') !== 0) out.push(s);
  }
  function mt(p) {
    var e = document.querySelector('meta[property="' + p + '"],meta[name="' + p + '"]');
    return e ? e.content : '';
  }
  var tte = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
  return JSON.stringify({ t: mt('og:title') || document.title, d: mt('og:description') || mt('description'), i: mt('og:image'), u: out, tt: tte ? tte.textContent : '' });
})()
