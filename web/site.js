/* ============================================================
   PhoneServer — shared site scripts
   1. hamburger drawer for the legal pages ([data-hp-toggle])
   2. cookie consent banner shown on every page until accepted
   ============================================================ */
(function () {
  'use strict';

  /* ---------------- 1. hamburger slider drawer ---------------- */
  var toggle = document.querySelector('[data-hp-toggle]');
  var drawer = document.getElementById('hpDrawer');
  var scrim = document.getElementById('hpScrim');
  var closeBtn = document.getElementById('hpClose');

  if (toggle && drawer) {
    function isOpen() { return drawer.classList.contains('is-open'); }
    function setOpen(open) {
      drawer.classList.toggle('is-open', open);
      if (scrim) scrim.classList.toggle('is-open', open);
      document.body.classList.toggle('nav-open', open);
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
      if (open) {
        var first = drawer.querySelector('a, button');
        if (first) first.focus();
      } else {
        toggle.focus();
      }
    }
    toggle.addEventListener('click', function () { setOpen(!isOpen()); });
    if (scrim) scrim.addEventListener('click', function () { setOpen(false); });
    if (closeBtn) closeBtn.addEventListener('click', function () { setOpen(false); });
    Array.prototype.forEach.call(drawer.querySelectorAll('a'), function (a) {
      a.addEventListener('click', function () { setOpen(false); });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && isOpen()) setOpen(false);
    });
  }

  /* ---------------- 2. cookie consent banner ---------------- */
  var KEY = 'ps-cookie-consent';
  var accepted = null;
  try { accepted = window.localStorage.getItem(KEY); } catch (e) {}
  if (accepted) return;

  var hasCookieIcon = !!document.getElementById('n-cookie');
  var icon = hasCookieIcon
    ? '<svg class="ico" aria-hidden="true"><use href="#n-cookie"/></svg>'
    : '';

  var banner = document.createElement('div');
  banner.className = 'ck-banner';
  banner.setAttribute('role', 'region');
  banner.setAttribute('aria-label', 'Cookie notice');
  banner.innerHTML =
    '<div class="ck-inner">' +
      '<div class="ck-copy">' +
        '<b>' + icon + 'Essential storage only</b>' +
        '<p>No tracking, no analytics and no third-party cookies. This site only remembers your ' +
        'theme choice and this decision on your own device. ' +
        '<a href="cookies-policy.html">Read the cookies policy</a>.</p>' +
      '</div>' +
      '<div class="ck-actions">' +
        '<a class="ck-more" href="cookies-policy.html">Learn more</a>' +
        '<button class="ck-accept" type="button">Accept</button>' +
      '</div>' +
    '</div>';

  document.body.appendChild(banner);
  window.requestAnimationFrame(function () {
    window.requestAnimationFrame(function () { banner.classList.add('ck-in'); });
  });

  var acceptBtn = banner.querySelector('.ck-accept');
  if (acceptBtn) {
    acceptBtn.addEventListener('click', function () {
      try { window.localStorage.setItem(KEY, String(Date.now())); } catch (e) {}
      banner.classList.remove('ck-in');
      window.setTimeout(function () {
        if (banner.parentNode) banner.parentNode.removeChild(banner);
      }, 400);
    });
  }
})();
