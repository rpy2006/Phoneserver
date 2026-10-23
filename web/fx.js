/* ============================================================
   PhoneServer — fx.js
   Progressive enhancement layer for the documentation site.

   Libraries (vendored in web/vendor/, served first-party, no CDN):
     - Lenis   — smooth scrolling (fine pointers, motion allowed)
     - Anime   — micro-interactions (drawer stagger, count-up, press)
     - Three   — one subtle wireframe visual in the guide hero

   Rules:
     - every feature is optional: if a library fails to load, the
       page keeps working with plain CSS / native scrolling
     - prefers-reduced-motion disables all of it (CSS + JS guards)
     - save-data and low-core devices skip the heavy pieces
     - Three.js is lazy-loaded only when the hero is near the
       viewport on a wide screen
   ============================================================ */
(function () {
  'use strict';

  var D = document, W = window;
  var mqReduce = W.matchMedia ? W.matchMedia('(prefers-reduced-motion: reduce)') : null;
  var saveData = !!(navigator.connection && navigator.connection.saveData);

  function reduced() { return !!(mqReduce && mqReduce.matches); }
  function idle(fn) {
    if (W.requestIdleCallback) W.requestIdleCallback(fn, { timeout: 2500 });
    else setTimeout(fn, 220);
  }
  function closest(e, sel) {
    var t = e.target;
    while (t && t !== D) { if (t.matches && t.matches(sel)) return t; t = t.parentNode; }
    return null;
  }
  function loadLib(name) {
    return new Promise(function (res, rej) {
      var s = D.createElement('script');
      s.src = 'vendor/' + name;
      s.async = true;
      s.onload = function () { res(); };
      s.onerror = function () { rej(new Error(name)); };
      D.head.appendChild(s);
    });
  }

  /* ---------------------------------------------------------
     1. Lenis — smooth scrolling (desktop pointers only;
        touch keeps native scrolling for battery and feel)
     --------------------------------------------------------- */
  function initLenis() {
    if (reduced() || saveData) return;
    var fine = W.matchMedia ? W.matchMedia('(pointer: fine)').matches : true;
    if (!fine) return;
    idle(function () {
      loadLib('lenis.min.js').then(function () {
        if (typeof W.Lenis !== 'function' || reduced()) return;
        var lenis = new W.Lenis({ duration: 0.85, smoothWheel: true, wheelMultiplier: 1 });
        function raf(t) { lenis.raf(t); W.requestAnimationFrame(raf); }
        W.requestAnimationFrame(raf);
        W.__psLenis = lenis;
        /* in-page anchors scroll through Lenis; page scripts that
           already handle the click (e.g. docs nav) set defaultPrevented */
        D.addEventListener('click', function (e) {
          if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey) return;
          var a = closest(e, 'a[href^="#"]');
          if (!a) return;
          var hash = a.getAttribute('href');
          if (!hash || hash === '#') return;
          var target = D.querySelector(hash);
          if (!target) return;
          e.preventDefault();
          lenis.scrollTo(target, { offset: -72 });
          if (W.history && W.history.pushState) W.history.pushState(null, '', hash);
        });
      }).catch(function () { /* native scroll stays */ });
    });
  }

  /* ---------------------------------------------------------
     2. Anime — micro-interactions, skipped when motion is off
     --------------------------------------------------------- */
  function countUp(el, A) {
    var end = parseFloat(el.getAttribute('data-count'));
    if (isNaN(end)) return;
    var suffix = el.getAttribute('data-suffix') || '';
    var obj = { v: 0 };
    A({
      targets: obj, v: end, duration: 520, easing: 'easeOutExpo',
      update: function () { el.textContent = Math.round(obj.v) + suffix; }
    });
  }

  function initAnime() {
    if (reduced()) return;
    idle(function () {
      loadLib('anime.min.js').then(function () {
        var A = W.anime;
        if (!A || reduced()) return;

        /* press feedback on small buttons */
        D.addEventListener('click', function (e) {
          var b = closest(e, '.yp-copy-btn, .copy, .ck-accept, .ck-more');
          if (!b) return;
          A({ targets: b, scale: [1, 0.94, 1], duration: 180, easing: 'easeOutExpo' });
        });

        /* hero stat count-up (guide) — values stay correct without JS */
        var stats = D.querySelectorAll('[data-count]');
        if (stats.length && 'IntersectionObserver' in W) {
          var io = new IntersectionObserver(function (entries) {
            entries.forEach(function (x) {
              if (x.isIntersecting) { countUp(x.target, A); io.unobserve(x.target); }
            });
          }, { threshold: 0.4 });
          stats.forEach(function (s) { io.observe(s); });
        }

        /* drawer link stagger when a slider drawer opens */
        ['ypDrawer', 'hpDrawer'].forEach(function (id) {
          var dr = D.getElementById(id);
          if (!dr || !W.MutationObserver) return;
          var mo = new MutationObserver(function () {
            if (!dr.classList.contains('is-open')) return;
            var links = dr.querySelectorAll('nav a');
            if (!links.length) return;
            A({
              targets: Array.prototype.slice.call(links),
              translateX: [-14, 0], opacity: [0.4, 1],
              duration: 220, delay: A.stagger(20), easing: 'easeOutExpo'
            });
          });
          mo.observe(dr, { attributes: true, attributeFilter: ['class'] });
        });
      }).catch(function () { /* CSS-only UI stays */ });
    });
  }

  /* ---------------------------------------------------------
     3. Three — one subtle wireframe "rack" in the guide hero.
        Lazy, wide screens only, ~30 fps, pauses when hidden,
        re-colours on theme change, never captures input.
     --------------------------------------------------------- */
  function initThree() {
    var mount = D.querySelector('[data-fx-3d]');
    if (!mount || reduced() || saveData) return;
    if (W.innerWidth < 920) return;
    if (navigator.hardwareConcurrency && navigator.hardwareConcurrency < 2) return;
    if (!('IntersectionObserver' in W)) return;

    var io = new IntersectionObserver(function (entries) {
      if (entries[0] && entries[0].isIntersecting) {
        io.disconnect();
        idle(function () {
          loadLib('three.min.js').then(function () {
            if (W.THREE && !reduced()) buildRack(W.THREE, mount);
          }).catch(function () { /* rings / static hero stays */ });
        });
      }
    }, { rootMargin: '300px' });
    io.observe(mount);
  }

  function buildRack(THREE, mount) {
    var w = mount.clientWidth || 420, h = mount.clientHeight || 420;
    var renderer;
    try {
      renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' });
    } catch (err) { return; }
    renderer.setPixelRatio(Math.min(W.devicePixelRatio || 1, 2));
    renderer.setSize(w, h);
    renderer.domElement.style.display = 'block';
    mount.appendChild(renderer.domElement);

    var scene = new THREE.Scene();
    var camera = new THREE.PerspectiveCamera(38, w / h, 0.1, 60);
    camera.position.set(5.2, 3.4, 6.8);
    camera.lookAt(0, 0.2, 0);

    function token(name, fallback) {
      var v = getComputedStyle(D.documentElement).getPropertyValue(name).trim();
      return new THREE.Color(v || fallback);
    }
    function palette() {
      return { accent: token('--yp-accent', '#c23a00'), line: token('--yp-line', '#e1e6ee') };
    }
    var pal = palette();

    var group = new THREE.Group();
    var box = new THREE.BoxGeometry(3.1, 0.7, 2.0);
    var edges = new THREE.EdgesGeometry(box);
    var units = [];
    for (var i = 0; i < 3; i++) {
      var mat = new THREE.LineBasicMaterial({
        color: i === 1 ? pal.accent.clone() : pal.line.clone(),
        transparent: true, opacity: i === 1 ? 0.95 : 0.75
      });
      var seg = new THREE.LineSegments(edges, mat);
      seg.position.y = i * 0.95 - 0.95;
      group.add(seg);
      units.push(mat);
    }
    var grid = new THREE.GridHelper(11, 11, pal.line.clone(), pal.line.clone());
    grid.position.y = -1.65;
    grid.material.transparent = true;
    grid.material.opacity = 0.45;
    scene.add(grid, group);

    var running = true, rafId = 0, last = 0, acc = 0;
    function frame(now) {
      rafId = W.requestAnimationFrame(frame);
      if (!running) return;
      var dt = Math.min((now - last) / 1000 || 0, 0.1);
      last = now;
      acc += dt;
      if (acc < 1 / 30) return;          /* cap at ~30 fps */
      acc = 0;
      group.rotation.y += 0.12 * dt;
      renderer.render(scene, camera);
    }
    rafId = W.requestAnimationFrame(frame);

    function onResize() {
      var nw = mount.clientWidth, nh = mount.clientHeight;
      if (!nw || !nh) return;
      camera.aspect = nw / nh; camera.updateProjectionMatrix();
      renderer.setSize(nw, nh);
    }
    function onVisibility() { running = !D.hidden; last = 0; }
    function onTheme() {
      pal = palette();
      units.forEach(function (m, i) { m.color.copy(i === 1 ? pal.accent : pal.line); });
      grid.material.color.copy(pal.line);
    }
    function dispose() {
      running = false;
      W.cancelAnimationFrame(rafId);
      W.removeEventListener('resize', onResize);
      W.removeEventListener('pagehide', dispose);
      D.removeEventListener('visibilitychange', onVisibility);
      W.removeEventListener('ps:theme', onTheme);
      if (renderer.domElement.parentNode === mount) mount.removeChild(renderer.domElement);
      box.dispose(); edges.dispose();
      units.forEach(function (m) { m.dispose(); });
      grid.geometry.dispose(); grid.material.dispose();
      renderer.dispose();
    }
    W.addEventListener('resize', onResize, { passive: true });
    W.addEventListener('pagehide', dispose);
    D.addEventListener('visibilitychange', onVisibility);
    W.addEventListener('ps:theme', onTheme);
  }

  /* --------------------------------------------------------- */
  function start() {
    if (reduced()) return;          /* CSS fallbacks already cover this */
    initLenis();
    initAnime();
    initThree();
  }
  if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', start);
  else start();
})();
