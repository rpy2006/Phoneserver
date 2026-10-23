/*! Ad Network SDK - lightweight, cookie-free. Embed:
 *  <div data-adnet-placement="pl_xxxxxxxxxxxxxxxx"></div>
 *  <script async src="https://YOUR-AD-SERVER/sdk.js"></script>
 * Sends only the page origin + path and browser language. Ads are drawn with textContent (never innerHTML). */
(function (w, d) {
  'use strict';
  var me = d.currentScript;
  if (!me || !me.src || w.__adnetLoaded) return;
  w.__adnetLoaded = 1;
  var base = new URL(me.src).origin;
  var KEY = /^pl_[a-f0-9]{16}$/;

  function el(tag, css, text) {
    var e = d.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }

  function beacon(url) {
    try {
      if (navigator.sendBeacon && navigator.sendBeacon(url)) return;
    } catch (e) {}
    try { fetch(url, { method: 'POST', keepalive: true, credentials: 'omit', mode: 'no-cors' }); } catch (e) {}
  }

  // An impression counts only after the ad was at least 50% visible for 1 second in a visible tab.
  function whenViewable(node, fire) {
    if (!('IntersectionObserver' in w)) return;
    var timer = null, done = false;
    var io = new IntersectionObserver(function (entries) {
      if (done) return;
      var e = entries[entries.length - 1];
      if (e.isIntersecting && e.intersectionRatio >= 0.5 && d.visibilityState === 'visible') {
        if (!timer) timer = setTimeout(function () { done = true; io.disconnect(); fire(); }, 1000);
      } else if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    }, { threshold: [0, 0.5, 1] });
    io.observe(node);
  }

  function link(token, css) {
    var a = el('a', css);
    a.href = base + '/c?t=' + encodeURIComponent(token);
    a.target = '_blank';
    a.rel = 'noopener sponsored nofollow';
    a.referrerPolicy = 'origin';
    return a;
  }

  function render(box, res) {
    var ad = res.ad, root = box.attachShadow ? box.attachShadow({ mode: 'open' }) : box;
    var wrap = el('div', 'font:14px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#1a1a1a;max-width:100%;box-sizing:border-box');
    var tag = el('div', 'font-size:10px;letter-spacing:.04em;text-transform:uppercase;color:#6b7280;margin:0 0 2px', 'Ad');
    wrap.appendChild(tag);
    var a = link(res.t, 'display:block;color:inherit;text-decoration:none');

    if (res.f === 'banner' && ad.img) {
      var img = el('img', 'display:block;max-width:100%;height:auto;border:0');
      img.src = base + ad.img;
      img.width = res.w; img.height = res.h;
      img.alt = ad.title || 'Advertisement';
      a.appendChild(img);
    } else if (res.f === 'native') {
      var card = el('div', 'display:flex;gap:12px;align-items:center;border:1px solid #e5e7eb;border-radius:10px;padding:12px;background:#fff');
      if (ad.img) {
        var im = el('img', 'width:96px;height:96px;object-fit:cover;border-radius:8px;flex:none');
        im.src = base + ad.img; im.alt = '';
        card.appendChild(im);
      }
      var col = el('div', 'min-width:0');
      col.appendChild(el('div', 'font-weight:600;margin-bottom:2px', ad.title));
      col.appendChild(el('div', 'color:#4b5563;font-size:13px', ad.body));
      if (ad.adv) col.appendChild(el('div', 'color:#6b7280;font-size:11px;margin-top:4px', 'Sponsored by ' + ad.adv));
      if (ad.cta) col.appendChild(el('span', 'display:inline-block;margin-top:8px;background:#2563eb;color:#fff;border-radius:6px;padding:5px 12px;font-size:13px', ad.cta));
      card.appendChild(col);
      a.appendChild(card);
    } else {
      a.appendChild(el('div', 'font-weight:600;color:#1d4ed8;text-decoration:underline', ad.title));
      a.appendChild(el('div', 'color:#374151', ad.body));
      if (ad.adv) a.appendChild(el('div', 'color:#6b7280;font-size:11px', ad.adv));
    }
    wrap.appendChild(a);
    root.appendChild(wrap);
    whenViewable(box, function () { beacon(base + '/imp?t=' + encodeURIComponent(res.t)); });
  }

  function load(box) {
    if (box.getAttribute('data-adnet-done')) return;
    var key = box.getAttribute('data-adnet-placement');
    if (!KEY.test(key || '')) return;
    box.setAttribute('data-adnet-done', '1');
    var url = base + '/serve?p=' + key + '&u=' + encodeURIComponent(location.origin + location.pathname) +
      '&l=' + encodeURIComponent(navigator.language || '');
    fetch(url, { credentials: 'omit', referrerPolicy: 'origin' }).then(function (r) {
      if (r.status !== 200) throw 0;
      return r.json();
    }).then(function (res) { render(box, res); }).catch(function () { box.style.display = 'none'; });
  }

  function scan() {
    var s = me.getAttribute('data-placement');
    if (s && !me.__adnetBox) {                       // <script data-placement="pl_..."> creates its own container
      me.__adnetBox = el('div');
      me.__adnetBox.setAttribute('data-adnet-placement', s);
      me.parentNode.insertBefore(me.__adnetBox, me);
    }
    var nodes = d.querySelectorAll('[data-adnet-placement]');
    for (var i = 0; i < nodes.length; i++) load(nodes[i]);
  }

  w.adnet = { scan: scan }; // single-page apps can call adnet.scan() after adding new containers
  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', scan); else scan();
})(window, document);
