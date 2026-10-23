(function () {
  'use strict';
  var S = { token: localStorage.getItem('adnet_token') || '', user: null, meta: null, onHash: null };
  var app = document.getElementById('app');
  var SIZES = [[300, 250], [728, 90], [320, 50], [300, 600], [160, 600], [970, 250], [336, 280], [468, 60], [320, 100]];
  var toastTimer;

  // ------------------------------------------------------------ DOM + text helpers (all values become text nodes, never HTML)
  function add(e, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(e, x); }); return; }
    e.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  }
  function h(tag, attrs) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) return;
      if (k === 'class') e.className = v;
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), v);
      else if (k === 'value') e.value = v;
      else if (k === 'checked') e.checked = !!v;
      else e.setAttribute(k, v === true ? '' : v);
    });
    for (var i = 2; i < arguments.length; i++) add(e, arguments[i]);
    return e;
  }
  function clear(e) { while (e.firstChild) e.removeChild(e.firstChild); return e; }
  function money(m) {
    var c = (S.meta && S.meta.currency) || 'USD';
    try { return new Intl.NumberFormat(undefined, { style: 'currency', currency: c, minimumFractionDigits: 2, maximumFractionDigits: 4 }).format((m || 0) / 1e6); }
    catch (e) { return ((m || 0) / 1e6).toFixed(2) + ' ' + c; }
  }
  function num(n) { return new Intl.NumberFormat().format(n || 0); }
  function pct(a, b) { return b ? (a * 100 / b).toFixed(2) + '%' : '0.00%'; }
  function dt(ms) { return ms ? new Date(ms).toLocaleDateString() : '-'; }
  function ymd(d) { return d.toISOString().slice(0, 10); }
  function chip(s) {
    if (!s) return document.createTextNode('-');
    return h('span', { class: 'chip ' + s }, String(s));
  }
  function roleLabel(r) { return r === 'admin' ? 'Administrator' : r === 'advertiser' ? 'Advertiser' : r === 'publisher' ? 'Publisher' : r; }

  // ------------------------------------------------------------ toast + errors
  function toast(msg, kind) {
    var t = document.getElementById('toast');
    clear(t);
    t.appendChild(h('span', { class: 'dot', 'aria-hidden': 'true' }));
    t.appendChild(h('span', { class: 'msg' }, msg));
    t.className = 'show ' + (kind || 'info');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, kind === 'err' ? 4200 : 2600);
  }
  function errorText(e) {
    if (!e) return 'Something went wrong. Please try again.';
    if (e.name === 'AbortError') return 'The ad server did not respond in time. Check the phone and try again.';
    if (navigator.onLine === false) return 'You are offline. Connect to the same network as PhoneServer and try again.';
    if (e.network) return 'Cannot reach the ad server. Make sure PhoneServer is running and that you are on the same network.';
    return e.message || 'Something went wrong. Please try again.';
  }
  function fail(e) {
    if (e && e.session) return;
    toast(errorText(e), 'err');
  }
  function statusMessage(code) {
    if (code === 400) return 'The server rejected that request. Check the values and try again.';
    if (code === 401) return 'Please sign in again.';
    if (code === 403) return 'You do not have permission to do that.';
    if (code === 404) return 'That item could not be found. It may have been removed.';
    if (code === 409) return 'That conflicts with the current state. Refresh and try again.';
    if (code === 413) return 'That file is too large.';
    if (code === 429) return 'Too many requests. Wait a minute and try again.';
    if (code >= 500) return 'The ad server hit an error. Check PhoneServer and try again.';
    return 'Request failed (' + code + ').';
  }
  function httpError(code, msg) {
    var e = new Error(msg || statusMessage(code));
    e.status = code;
    return e;
  }
  function clearSession() { S.token = ''; S.user = null; localStorage.removeItem('adnet_token'); }

  // ------------------------------------------------------------ API
  function api(method, path, body, raw) {
    var o = { method: method, headers: {} };
    var ctrl = null, timer = null;
    if (window.AbortController) {
      ctrl = new AbortController();
      o.signal = ctrl.signal;
      timer = setTimeout(function () { ctrl.abort(); }, 25000);
    }
    if (S.token) o.headers.Authorization = 'Bearer ' + S.token;
    if (body !== undefined) {
      if (raw) { o.body = body; o.headers['Content-Type'] = body.type || 'application/octet-stream'; }
      else { o.body = JSON.stringify(body); o.headers['Content-Type'] = 'application/json'; }
    }
    function done() { if (timer) clearTimeout(timer); }
    return fetch(path, o).then(function (r) {
      done();
      if (r.status === 401 && S.token) {
        clearSession();
        var ex = new Error('Your session expired. Please sign in again.');
        ex.session = true;
        setTimeout(function () { boot('Session expired. Please sign in again.'); }, 0);
        throw ex;
      }
      if ((r.headers.get('content-type') || '').indexOf('application/json') >= 0) {
        return r.json().then(function (j) {
          if (!r.ok) throw httpError(r.status, j && j.error);
          return j;
        }, function () {
          if (!r.ok) throw httpError(r.status);
          return {};
        });
      }
      if (!r.ok) {
        return r.text().then(function (t) {
          throw httpError(r.status, t && t.length < 300 ? t.trim() : '');
        }, function () { throw httpError(r.status); });
      }
      return r;
    }, function (err) {
      done();
      if (err && err.name === 'AbortError') return Promise.reject(err);
      if (navigator.onLine === false) { var off = new Error('You are offline.'); off.network = true; throw off; }
      var n = new Error('Network error');
      n.network = true;
      throw n;
    });
  }
  function qs(o) {
    var p = [];
    Object.keys(o).forEach(function (k) { if (o[k] !== '' && o[k] != null) p.push(encodeURIComponent(k) + '=' + encodeURIComponent(o[k])); });
    return p.length ? '?' + p.join('&') : '';
  }

  // ------------------------------------------------------------ theme
  function applyTheme() {
    var t = localStorage.getItem('adnet_theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', t);
  }
  function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme');
    localStorage.setItem('adnet_theme', cur === 'dark' ? 'light' : 'dark');
    applyTheme();
  }
  applyTheme();

  // ------------------------------------------------------------ UI primitives
  function table(cols, rows, empty) {
    if (!rows || !rows.length) return h('div', { class: 'empty' }, empty || 'Nothing here yet.');
    var th = h('tr');
    cols.forEach(function (c) { th.appendChild(h('th', { class: c.num ? 'num' : '' }, c.t)); });
    var tb = h('tbody');
    rows.forEach(function (r) {
      var tr = h('tr');
      cols.forEach(function (c) {
        var td = h('td', { class: (c.num ? 'num ' : '') + (c.t ? '' : 'actions') });
        if (c.t) td.setAttribute('data-label', c.t);
        var v = c.f ? c.f(r) : (r[c.k] == null ? '-' : r[c.k]);
        add(td, v);
        tr.appendChild(td);
      });
      tb.appendChild(tr);
    });
    return h('div', { class: 'tablewrap' }, h('table', null, h('thead', null, th), tb));
  }
  function btn(label, fn, cls) { return h('button', { class: 'btn sm ' + (cls || 'ghost'), type: 'button', onclick: fn }, label); }
  var ICONS = {
    sun: ['M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8', 'M12 2v2', 'M12 20v2', 'M4.9 4.9l1.4 1.4', 'M17.7 17.7l1.4 1.4', 'M2 12h2', 'M20 12h2', 'M4.9 19.1l1.4-1.4', 'M17.7 6.3l1.4-1.4'],
    moon: ['M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z'],
    account: ['M20 21a8 8 0 0 0-16 0', 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8'],
    signout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
    menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
    close: ['M6 6l12 12', 'M18 6L6 18']
  };
  function svgIcon(paths) {
    var ns = 'http://www.w3.org/2000/svg';
    var s = document.createElementNS(ns, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('width', '18');
    s.setAttribute('height', '18');
    s.setAttribute('fill', 'none');
    s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2');
    s.setAttribute('stroke-linecap', 'round');
    s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true');
    paths.forEach(function (d) {
      var p = document.createElementNS(ns, 'path');
      p.setAttribute('d', d);
      s.appendChild(p);
    });
    return s;
  }
  function toolBtn(label, iconPaths, fn) {
    return h('button', { class: 'btn ghost tool', type: 'button', title: label, 'aria-label': label, onclick: fn },
      h('span', { class: 'glyph' }, svgIcon(iconPaths)), h('span', { class: 'lbl' }, label));
  }
  function themeButton() {
    var b = h('button', { class: 'btn ghost tool', type: 'button', title: 'Toggle theme', 'aria-label': 'Toggle theme' },
      h('span', { class: 'glyph' }), h('span', { class: 'lbl' }, 'Theme'));
    function paint() {
      var g = b.firstChild;
      clear(g);
      g.appendChild(svgIcon(document.documentElement.getAttribute('data-theme') === 'dark' ? ICONS.sun : ICONS.moon));
    }
    b.addEventListener('click', function () { toggleTheme(); paint(); });
    paint();
    return b;
  }
  function card(title) {
    var args = Array.prototype.slice.call(arguments);
    var c = h('div', { class: 'card' });
    if (typeof args[0] === 'string' && args[0]) { c.appendChild(h('h2', null, args.shift())); }
    args.forEach(function (x) { add(c, x); });
    return c;
  }
  function statCard(label, value) { return h('div', { class: 'stat' }, h('b', null, value), h('span', null, label)); }
  function loadingBlock(msg) {
    return h('div', { class: 'loading' }, h('span', { class: 'spin', 'aria-hidden': 'true' }), h('span', null, msg || 'Loading…'));
  }
  function errorBox(e, retry) {
    return h('div', { class: 'card panel-error', role: 'alert' },
      h('h2', null, 'Could not load this page'),
      h('p', { class: 'muted' }, errorText(e)),
      e && e.status === 403 ? h('p', { class: 'hint' }, 'Your account may not have access to this section.') : null,
      retry ? h('div', { class: 'row end' }, h('button', { class: 'btn', type: 'button', onclick: retry }, 'Try again')) : null);
  }
  function guard(box, promise, render, retry) {
    var seq = (box.__seq || 0) + 1;
    box.__seq = seq;
    clear(box);
    box.appendChild(loadingBlock());
    return Promise.resolve(promise).then(function (data) {
      if (!document.contains(box) || box.__seq !== seq) return;
      clear(box);
      try { render(data); }
      catch (e) { clear(box); box.appendChild(errorBox(e, retry)); }
    }, function (e) {
      if (e && e.session) return;
      if (!document.contains(box) || box.__seq !== seq) return;
      clear(box);
      box.appendChild(errorBox(e, retry));
    });
  }

  // ------------------------------------------------------------ modal
  function trapFocus(e, root) {
    var els = root.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])');
    if (!els.length) return;
    var first = els[0], last = els[els.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  var MODALS = [];
  function modal(title, body) {
    var prev = document.activeElement;
    var ov = null, closed = false;
    var closeBtn = h('button', { class: 'iconbtn', type: 'button', 'aria-label': 'Close dialog', onclick: function () { close(); } }, '×');
    var c = h('div', { class: 'card modal-card' }, h('div', { class: 'modal-head' }, h('h2', null, title), closeBtn), h('div', { class: 'modal-body' }, body));
    ov = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, c);
    function topmost() { return MODALS[MODALS.length - 1] === ov; }
    function onKey(e) {
      if (!topmost()) return;
      if (e.key === 'Escape') { e.preventDefault(); close(); }
      else if (e.key === 'Tab') trapFocus(e, c);
    }
    function close() {
      if (closed) return;
      closed = true;
      var i = MODALS.indexOf(ov);
      if (i >= 0) MODALS.splice(i, 1);
      document.removeEventListener('keydown', onKey);
      if (!MODALS.length) document.body.classList.remove('no-scroll');
      if (ov.parentNode) ov.parentNode.removeChild(ov);
      if (prev && prev.focus && document.contains(prev)) { try { prev.focus(); } catch (e) {} }
    }
    ov.addEventListener('mousedown', function (e) { if (e.target === ov) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(ov);
    MODALS.push(ov);
    document.body.classList.add('no-scroll');
    setTimeout(function () {
      var f = c.querySelector('.modal-body input:not([type=hidden]),.modal-body select,.modal-body textarea,.modal-body button');
      if (f && f.focus) { try { f.focus(); } catch (e) {} }
    }, 30);
    return close;
  }
  function confirmModal(msg, label) {
    return new Promise(function (resolve) {
      function finish(v) { close(); resolve(v); }
      var close = modal('Please confirm', h('div', null,
        h('p', { class: 'confirm-msg' }, msg),
        h('div', { class: 'row end' },
          h('button', { class: 'btn ghost', type: 'button', onclick: function () { finish(false); } }, 'Cancel'),
          h('button', { class: 'btn danger', type: 'button', onclick: function () { finish(true); } }, label || 'Continue'))));
    });
  }
  function confirmDo(msg, fn) {
    confirmModal(msg, 'Continue').then(function (ok) { if (ok) fn(); });
  }

  // ------------------------------------------------------------ forms
  function formBody(fields, submitLabel, onSubmit, close) {
    var inputs = {}, wraps = {}, errs = {}, err = h('div', { class: 'form-error', role: 'alert' });
    var grid = h('div', { class: 'fields' });

    fields.forEach(function (f, idx) {
      var id = 'fld_' + idx + '_' + f.k;
      var inp, wrap;
      if (f.type === 'select') {
        inp = h('select', { id: id });
        (f.opts || []).forEach(function (o) { inp.appendChild(h('option', { value: o[0] }, o[1])); });
        if (f.val != null) inp.value = String(f.val);
      } else if (f.type === 'textarea') {
        inp = h('textarea', { id: id, rows: f.rows || 3, maxlength: f.maxlen || null }, f.val || '');
      } else if (f.type === 'checks') {
        inp = h('div', { class: 'checkgroup', id: id });
        inp.boxes = (f.opts || []).map(function (o) {
          var cb = h('input', { type: 'checkbox', value: o[0], checked: (f.val || []).indexOf(o[0]) >= 0 });
          inp.appendChild(h('label', { class: 'check' }, cb, o[1]));
          return cb;
        });
      } else if (f.type === 'checkbox') {
        inp = h('input', { type: 'checkbox', id: id, checked: !!f.val });
      } else {
        inp = h('input', {
          id: id, type: f.type || 'text', value: f.val == null ? '' : f.val,
          autocomplete: f.autocomplete || (f.type === 'password' ? 'new-password' : 'off'),
          placeholder: f.ph || null, maxlength: f.maxlen || null,
          min: f.min != null ? f.min : null, max: f.max != null ? f.max : null,
          step: f.type === 'number' ? 'any' : null
        });
      }
      if (f.req && f.type !== 'checks' && f.type !== 'checkbox') inp.setAttribute('required', '');
      inputs[f.k] = inp;
      var msg = h('div', { class: 'fielderr' });
      errs[f.k] = msg;
      if (f.type === 'checks') {
        wrap = h('fieldset', { class: 'field' }, h('legend', null, f.t), inp,
          f.hint ? h('div', { class: 'hint' }, f.hint) : null, msg);
      } else if (f.type === 'checkbox') {
        wrap = h('div', { class: 'field' }, h('label', { class: 'check' }, inp, f.t),
          f.hint ? h('div', { class: 'hint' }, f.hint) : null, msg);
      } else {
        wrap = h('div', { class: 'field' },
          h('label', { for: id }, f.t, f.req ? h('span', { class: 'req', 'aria-hidden': 'true' }, ' *') : null),
          inp, f.hint ? h('div', { class: 'hint' }, f.hint) : null, msg);
      }
      wraps[f.k] = wrap;
      grid.appendChild(wrap);
    });

    function mark(f, text) {
      var w = wraps[f.k];
      if (!w) return;
      w.classList.add('invalid');
      errs[f.k].textContent = text;
    }
    function focusable(f, i) {
      if (f.type === 'checks') return i.boxes && i.boxes[0];
      return i && i.focus ? i : null;
    }
    function collect() {
      var v = {}, firstBad = null;
      fields.forEach(function (f) {
        var w = wraps[f.k], i = inputs[f.k], val;
        w.classList.remove('invalid');
        errs[f.k].textContent = '';
        if (f.type === 'checkbox') val = i.checked;
        else if (f.type === 'checks') val = i.boxes ? i.boxes.filter(function (b) { return b.checked; }).map(function (b) { return b.value; }) : [];
        else if (f.type === 'file') val = i.files[0] || null;
        else val = String(i.value == null ? '' : i.value).trim();
        v[f.k] = val;

        function bad(text) {
          mark(f, text);
          if (!firstBad) firstBad = { f: f, i: i };
        }
        if (f.req) {
          var empty = f.type === 'checks' ? !val.length : f.type === 'checkbox' ? !val : f.type === 'file' ? !val : val === '';
          if (empty) { bad('This field is required.'); return; }
        }
        if (f.type === 'email' && val && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(val)) { bad('Enter a valid email address.'); return; }
        if (f.type === 'number' && val !== '') {
          var n = Number(val);
          if (!isFinite(n)) { bad('Enter a valid number.'); return; }
          if (f.min != null && n < f.min) { bad('Must be at least ' + f.min + '.'); return; }
          if (f.max != null && n > f.max) { bad('Must be at most ' + f.max + '.'); return; }
        }
        if (f.check) {
          var m = f.check(val, v);
          if (m) { bad(m); return; }
        }
      });
      err.textContent = '';
      if (firstBad) {
        var target = focusable(firstBad.f, firstBad.i);
        if (target && target.focus) { try { target.focus(); } catch (e) {} }
        try { wraps[firstBad.f.k].scrollIntoView({ block: 'center' }); } catch (e) {}
        return null;
      }
      return v;
    }

    var go = h('button', { class: 'btn', type: 'submit' }, submitLabel);
    var form = h('form', { novalidate: true }, grid, err, h('div', { class: 'form-actions' }, go));
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (go.disabled) return;
      var v = collect();
      if (!v) return;
      go.disabled = true;
      go.textContent = 'Working…';
      Promise.resolve().then(function () { return onSubmit(v); }).then(function (r) {
        go.disabled = false;
        go.textContent = submitLabel;
        if (close) close();
        return r;
      }, function (x) {
        err.textContent = errorText(x);
        go.disabled = false;
        go.textContent = submitLabel;
        if (x && x.session && close) close();
      });
    });
    return form;
  }
  function formModal(title, fields, submitLabel, onSubmit, done) {
    var slot = h('div');
    var close = modal(title, slot);
    slot.appendChild(formBody(fields, submitLabel, function (v) {
      return Promise.resolve(onSubmit(v)).then(function (r) { if (done) done(r); return r; });
    }, close));
  }

  // ------------------------------------------------------------ clipboard
  function fallbackCopy(text) {
    var ta = h('textarea', { class: 'sr-only', 'aria-hidden': 'true' });
    ta.value = text;
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
    return ok;
  }
  function copyText(text, cb) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { cb(true); }, function () { cb(fallbackCopy(text)); });
    } else {
      cb(fallbackCopy(text));
    }
  }
  function copyButton(text) {
    var b = h('button', { class: 'btn sm', type: 'button' }, 'Copy');
    b.addEventListener('click', function () {
      copyText(text, function (ok) {
        if (!ok) { toast('Could not copy automatically - select the code instead', 'err'); return; }
        toast('Copied', 'ok');
        b.textContent = 'Copied';
        setTimeout(function () { b.textContent = 'Copy'; }, 1600);
      });
    });
    return b;
  }
  function imageProblem(file) {
    if (!file) return 'Choose an image file.';
    if (['image/png', 'image/jpeg', 'image/gif', 'image/webp'].indexOf(file.type) < 0) return 'Use a PNG, JPEG, GIF or WebP image.';
    if (file.size > 2 * 1024 * 1024) return 'That image is larger than 2 MB. Compress it and try again.';
    return null;
  }

  // ------------------------------------------------------------ boot + auth
  function boot(notice) {
    if (S.onHash) { window.removeEventListener('hashchange', S.onHash); S.onHash = null; }
    if (S.onMq) {
      var old = S.onMq;
      if (old.mq.removeEventListener) old.mq.removeEventListener('change', old.fn);
      else if (old.mq.removeListener) old.mq.removeListener(old.fn);
      S.onMq = null;
    }
    clear(app);
    app.appendChild(h('div', { class: 'boot' }, h('span', { class: 'spin', 'aria-hidden': 'true' }), h('span', null, 'Loading dashboard…')));
    if (notice) setTimeout(function () { toast(notice, notice === 'Signed out' ? 'ok' : 'err'); }, 0);
    api('GET', '/api/setup/status').then(function (s) {
      if (s.needs_setup) return authView(true);
      if (!S.token) return authView(false);
      return api('GET', '/api/meta').then(function (m) {
        S.meta = m;
        S.user = m.user || null;
        if (!S.user || !NAV[S.user.role]) throw new Error('This account cannot use the dashboard.');
        shell();
      }, function (e) {
        if (e && e.session) return;
        fatal(e);
      });
    }, function (e) {
      if (e && e.session) return;
      fatal(e);
    });
  }
  function fatal(e) {
    clear(app);
    document.title = 'Ad Network';
    app.appendChild(h('div', { class: 'auth-wrap' },
      h('div', { class: 'card panel-error', role: 'alert' },
        h('h1', null, 'Cannot load the dashboard'),
        h('p', { class: 'muted' }, errorText(e)),
        h('p', { class: 'hint' }, 'Open the Ad Network screen in PhoneServer, make sure it is running, and check that this browser is on the same network as the phone.'),
        h('div', { class: 'row' }, h('button', { class: 'btn', type: 'button', onclick: function () { boot(); } }, 'Try again')))));
  }
  function authView(setup) {
    clear(app);
    document.title = (setup ? 'Set up' : 'Sign in') + ' · Ad Network';
    var fields = setup
      ? [
        { k: 'setup_code', t: 'Setup code', req: true, hint: 'Shown in PhoneServer under Ad Network.' },
        { k: 'name', t: 'Your name', req: true, autocomplete: 'name' },
        { k: 'email', t: 'Email', type: 'email', req: true, autocomplete: 'username' },
        { k: 'password', t: 'Password', type: 'password', req: true, hint: 'At least 10 characters with letters and digits.', autocomplete: 'new-password' }
      ]
      : [
        { k: 'email', t: 'Email', type: 'email', req: true, autocomplete: 'username' },
        { k: 'password', t: 'Password', type: 'password', req: true, autocomplete: 'current-password' }
      ];
    var form = formBody(fields, setup ? 'Create administrator' : 'Sign in', function (v) {
      return api('POST', setup ? '/api/setup' : '/api/auth/login', v).then(function (r) {
        S.token = r.token;
        localStorage.setItem('adnet_token', r.token);
        boot();
      });
    });
    app.appendChild(h('div', { class: 'auth-wrap' },
      h('div', { class: 'auth' },
        h('div', { class: 'auth-head' },
          h('div', { class: 'auth-title' }, h('span', { class: 'brandmark', 'aria-hidden': 'true' }, 'AN'), h('h1', null, 'Ad Network')),
          themeButton()),
        h('div', { class: 'card' },
          h('h2', null, setup ? 'First-time setup' : 'Sign in'),
          h('p', { class: 'muted' }, setup ? 'Enter the setup code shown on the phone, then create the administrator account.' : 'Sign in to manage your ad network.'),
          form,
          !setup ? h('p', { class: 'hint' }, 'Need an account? Ask the administrator.') : null),
        h('p', { class: 'auth-foot muted' }, 'Local ad server · your data stays on your device'))));
  }
  function logout() {
    var t = S.token;
    clearSession();
    if (t) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + t } }).catch(function () {});
    boot('Signed out');
  }

  // ------------------------------------------------------------ shell + navigation
  var NAV = {
    admin: [['overview', 'Overview'], ['users', 'Users'], ['sites', 'Sites'], ['campaigns', 'Campaigns'], ['approvals', 'Creatives'], ['reports', 'Reports'], ['billing', 'Billing'], ['fraud', 'Fraud'], ['settings', 'Settings']],
    advertiser: [['campaigns', 'Campaigns'], ['reports', 'Reports'], ['billing', 'Billing']],
    publisher: [['sites', 'My sites'], ['reports', 'Reports'], ['billing', 'Earnings']]
  };
  var VIEWS = {};
  function shell() {
    clear(app);
    var items = NAV[S.user.role];
    var navEl = h('nav', { class: 'nav', 'aria-label': 'Sections' });
    items.forEach(function (it) {
      var b = h('button', { type: 'button', onclick: function () { setNav(false); go(it[0]); } }, it[1]);
      b.setAttribute('data-v', it[0]);
      navEl.appendChild(b);
    });
    var titleEl = h('h1');
    var contentEl = h('div', { class: 'content' });
    function labelOf(v) {
      var hit = items.filter(function (i) { return i[0] === v; })[0];
      return hit ? hit[1] : 'Ad Network';
    }
    function render(v) {
      if (!VIEWS[v] || !items.some(function (i) { return i[0] === v; })) v = items[0][0];
      Array.prototype.forEach.call(navEl.children, function (b) {
        if (b.getAttribute('data-v') === v) b.setAttribute('aria-current', 'page');
        else b.removeAttribute('aria-current');
      });
      titleEl.textContent = labelOf(v);
      document.title = labelOf(v) + ' · Ad Network';
      clear(contentEl);
      try { VIEWS[v](contentEl); }
      catch (e) { contentEl.appendChild(errorBox(e, function () { render(v); })); }
      try { window.scrollTo(0, 0); } catch (e) {}
    }
    function go(v) {
      if (location.hash.slice(1) === v) render(v);
      else location.hash = v;
    }
    if (S.onHash) window.removeEventListener('hashchange', S.onHash);
    S.onHash = function () { setNav(false); render(location.hash.slice(1)); };
    window.addEventListener('hashchange', S.onHash);

    var topActions = h('div', { class: 'top-actions' },
      S.user.role === 'advertiser' ? h('span', { class: 'chip balance' }, 'Balance ' + money(S.user.balance_micros)) : null,
      themeButton(),
      toolBtn('Account', ICONS.account, account),
      toolBtn('Sign out', ICONS.signout, logout));

    var menuIcon = h('span', { class: 'glyph' }, svgIcon(ICONS.menu));
    var menuBtn = h('button', {
      class: 'btn ghost tool menu', type: 'button', 'aria-label': 'Open menu',
      'aria-controls': 'sidenav', 'aria-expanded': 'false',
      onclick: function () { setNav(!side.classList.contains('open')); }
    }, menuIcon);
    var scrim = h('div', { class: 'scrim' });
    scrim.addEventListener('click', function () { setNav(false, true); });
    var side = h('aside', { class: 'side', id: 'sidenav', 'aria-label': 'Sections' },
      h('div', { class: 'sidehead' },
        h('div', { class: 'brand' }, h('span', { class: 'brandmark', 'aria-hidden': 'true' }, 'AN'), h('span', null, 'Ad Network')),
        h('button', { class: 'iconbtn sideclose', type: 'button', 'aria-label': 'Close menu', onclick: function () { setNav(false, true); } }, '×')),
      navEl,
      h('div', { class: 'sidefoot' },
        h('div', { class: 'who' }, h('b', null, S.user.name || S.user.email), h('span', null, roleLabel(S.user.role)))));
    var mq = window.matchMedia('(max-width:860px)');
    function setNav(open, focusMenu) {
      open = !!open && mq.matches;
      side.classList.toggle('open', open);
      scrim.classList.toggle('show', open);
      menuBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
      menuBtn.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
      clear(menuIcon);
      menuIcon.appendChild(svgIcon(open ? ICONS.close : ICONS.menu));
      if (open) {
        side.removeAttribute('inert');
        document.body.classList.add('no-scroll');
        var first = navEl.querySelector('button');
        if (first) first.focus();
      } else {
        if (mq.matches) side.setAttribute('inert', ''); else side.removeAttribute('inert');
        if (!document.querySelector('.modal')) document.body.classList.remove('no-scroll');
        if (focusMenu) menuBtn.focus();
      }
    }
    side.addEventListener('keydown', function (e) {
      if (!side.classList.contains('open')) return;
      if (e.key === 'Escape') { e.preventDefault(); setNav(false, true); return; }
      if (e.key !== 'Tab') return;
      var f = side.querySelectorAll('button');
      if (!f.length) return;
      var first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    function syncNavMode() { setNav(false); }
    if (mq.addEventListener) mq.addEventListener('change', syncNavMode);
    else if (mq.addListener) mq.addListener(syncNavMode);
    S.onMq = { mq: mq, fn: syncNavMode };
    syncNavMode();

    app.appendChild(h('div', { class: 'shell' },
      side,
      h('main', { class: 'main' },
        h('header', { class: 'topbar' },
          menuBtn,
          h('div', { class: 'titles' }, titleEl, h('div', { class: 'sub' }, roleLabel(S.user.role))),
          topActions),
        contentEl)));
    app.appendChild(scrim);

    var want = location.hash.slice(1);
    render(items.some(function (i) { return i[0] === want; }) ? want : items[0][0]);
  }
  function account() {
    formModal('Account - ' + S.user.email, [
      { k: 'old_password', t: 'Current password', type: 'password', req: true, autocomplete: 'current-password' },
      { k: 'new_password', t: 'New password', type: 'password', req: true, hint: 'At least 10 characters with letters and digits.', autocomplete: 'new-password' }
    ], 'Change password', function (v) { return api('POST', '/api/auth/password', v); }, function () { toast('Password changed', 'ok'); });
  }

  // ------------------------------------------------------------ overview (admin)
  VIEWS.overview = function (box) {
    function load() {
      guard(box, api('GET', '/api/admin/overview'), function (o) {
        var t = o.today, a = o.all_time;
        box.appendChild(h('div', { class: 'stats' },
          statCard('Pending campaigns', num(o.pending_campaigns)), statCard('Pending creatives', num(o.pending_creatives)),
          statCard('Pending sites', num(o.pending_sites)), statCard('Flagged sites', num(o.flagged_sites)),
          statCard('Advertisers', num(o.advertisers)), statCard('Publishers', num(o.publishers))));
        box.appendChild(card('Today (UTC)', h('div', { class: 'stats' },
          statCard('Ads served', num(t.served)), statCard('Valid impressions', num(t.imps)), statCard('Valid clicks', num(t.clicks)),
          statCard('Invalid events', num(t.invalid)), statCard('Advertiser spend', money(t.spend)), statCard('Publisher earnings', money(t.earnings)))));
        box.appendChild(card('All time', h('div', { class: 'stats' },
          statCard('Impressions', num(a.imps)), statCard('Clicks', num(a.clicks)), statCard('CTR', pct(a.clicks, a.imps)),
          statCard('Spend', money(a.spend)), statCard('Publisher earnings', money(a.earnings)), statCard('Platform margin', money(a.spend - a.earnings)))));
        box.appendChild(h('p', { class: 'footnote' }, 'All figures come from server-verified ad requests, viewable impressions and clicks. Nothing is estimated or sample data.'));
      }, load);
    }
    load();
  };

  // ------------------------------------------------------------ users (admin)
  VIEWS.users = function (box) {
    function newUser() {
      formModal('New user', [
        { k: 'name', t: 'Name', req: true, maxlen: 80 },
        { k: 'email', t: 'Email', type: 'email', req: true },
        { k: 'company', t: 'Company (optional)', maxlen: 80 },
        { k: 'role', t: 'Role', type: 'select', opts: [['advertiser', 'Advertiser'], ['publisher', 'Publisher'], ['admin', 'Administrator']] },
        { k: 'password', t: 'Initial password', type: 'password', req: true, hint: 'At least 10 characters with letters and digits.' }
      ], 'Create user', function (v) { return api('POST', '/api/users', v); }, function () { toast('User created', 'ok'); load(); });
    }
    function load() {
      guard(box, api('GET', '/api/users'), function (rows) {
        box.appendChild(h('div', { class: 'toolbar' },
          h('p', { class: 'hint' }, 'Advertisers fund campaigns, publishers show ads, administrators approve and settle payments.'),
          h('button', { class: 'btn', type: 'button', onclick: newUser }, 'New user')));
        box.appendChild(card('Accounts', table([
          { t: 'Name', f: function (r) { return r.name + (r.company ? ' (' + r.company + ')' : ''); } },
          { t: 'Email', k: 'email' },
          { t: 'Role', f: function (r) { return chip(r.role); } },
          { t: 'Status', f: function (r) { return chip(r.status); } },
          { t: 'Balance / unpaid', num: true, f: function (r) { return r.role === 'advertiser' ? money(r.balance_micros) : r.role === 'publisher' ? money(r.earned_micros - r.paid_micros) : '-'; } },
          { t: '', f: function (r) {
            var a = h('div', { class: 'row' });
            a.appendChild(btn(r.status === 'active' ? 'Disable' : 'Enable', function () {
              api('PATCH', '/api/users/' + r.id, { status: r.status === 'active' ? 'disabled' : 'active' })
                .then(function () { toast(r.status === 'active' ? 'User disabled' : 'User enabled', 'ok'); load(); }).catch(fail);
            }));
            a.appendChild(btn('Reset password', function () {
              formModal('Reset password - ' + r.email, [{ k: 'password', t: 'New password', type: 'password', req: true }], 'Reset', function (v) {
                return api('PATCH', '/api/users/' + r.id, v);
              }, function () { toast('Password reset - user was signed out', 'ok'); });
            }));
            if (r.role === 'advertiser') a.appendChild(btn('Add funds', function () {
              formModal('Add funds - ' + r.email, [
                { k: 'amount', t: 'Amount received', type: 'number', req: true, min: 0.01 },
                { k: 'note', t: 'Note (payment reference)', maxlen: 200 }
              ], 'Record top-up', function (v) {
                return api('POST', '/api/users/' + r.id + '/credit', { amount: v.amount, note: v.note, kind: 'topup' });
              }, function () { toast('Top-up recorded', 'ok'); load(); });
            }));
            if (r.role === 'publisher') a.appendChild(btn('Record payout', function () {
              formModal('Record payout - ' + r.email, [
                { k: 'amount', t: 'Amount paid out', type: 'number', req: true, min: 0.01, hint: 'Unpaid earnings: ' + money(r.earned_micros - r.paid_micros) },
                { k: 'note', t: 'Note (payment reference)', maxlen: 200 }
              ], 'Record payout', function (v) {
                return api('POST', '/api/users/' + r.id + '/payout', v);
              }, function () { toast('Payout recorded', 'ok'); load(); });
            }));
            if (r.id !== S.user.id) a.appendChild(btn('Delete', function () {
              confirmModal('Delete ' + r.name + ' (' + r.email + ')? Their sites, placements, campaigns, creatives, billing records and activity history are removed, and they are signed out. This cannot be undone.', 'Delete user').then(function (ok) {
                if (!ok) return;
                api('DELETE', '/api/users/' + r.id).then(function () { toast('User deleted', 'ok'); load(); }).catch(fail);
              });
            }, 'danger'));
            return a;
          } }], rows, 'No accounts yet. Create the first user.')));
      }, load);
    }
    load();
  };

  // ------------------------------------------------------------ sites + placements
  VIEWS.sites = function (box) {
    var admin = S.user.role === 'admin';
    function addSite() {
      function open(owners) {
        var f = [
          { k: 'name', t: 'Site name', req: true, maxlen: 80 },
          { k: 'domain', t: 'Domain', req: true, maxlen: 253, hint: 'Like example.com - no https://, path or port. Ads load only on this domain and its subdomains.' }
        ];
        if (owners) f.unshift({ k: 'owner_id', t: 'Publisher', type: 'select', opts: owners });
        formModal('Add website', f, 'Add website', function (v) {
          if (!owners) delete v.owner_id; else v.owner_id = Number(v.owner_id);
          return api('POST', '/api/sites', v);
        }, function () { toast('Website added', 'ok'); load(); });
      }
      if (!admin) return open(null);
      api('GET', '/api/users?role=publisher').then(function (u) {
        if (!u.length) { toast('Create a publisher account first', 'err'); return; }
        open(u.map(function (p) { return [p.id, p.name + ' - ' + p.email]; }));
      }).catch(fail);
    }
    function load() {
      guard(box, api('GET', '/api/sites'), function (rows) {
        box.appendChild(h('div', { class: 'toolbar' },
          h('p', { class: 'hint' }, admin ? 'Approve new sites before their placements can earn. Ads only load on the registered domain and its subdomains.' : 'New sites are reviewed before ads can be shown on them.'),
          h('button', { class: 'btn', type: 'button', onclick: addSite }, 'Add website')));
        box.appendChild(card('Websites', table([
          { t: 'Site', f: function (r) { return r.name; } },
          { t: 'Domain', k: 'domain' },
          admin ? { t: 'Owner', k: 'owner_email' } : null,
          { t: 'Status', f: function (r) { return h('span', null, chip(r.status), r.flagged ? h('span', { class: 'chip rejected', title: r.flag_reason }, 'flagged') : null); } },
          { t: 'Placements', num: true, k: 'placements' },
          { t: '', f: function (r) {
            var a = h('div', { class: 'row' });
            a.appendChild(btn('Placements & tags', function () { placements(r); }, 'btn'));
            if (admin && r.status !== 'approved') a.appendChild(btn('Approve', function () {
              api('PATCH', '/api/sites/' + r.id, { status: 'approved' }).then(function () { toast('Site approved', 'ok'); load(); }).catch(fail);
            }));
            if (admin && r.status !== 'rejected') a.appendChild(btn('Reject', function () {
              api('PATCH', '/api/sites/' + r.id, { status: 'rejected' }).then(function () { toast('Site rejected', 'ok'); load(); }).catch(fail);
            }));
            a.appendChild(btn('Remove', function () {
              confirmDo('Remove ' + r.domain + ' and its placements? Past reports are kept.', function () {
                api('DELETE', '/api/sites/' + r.id).then(function () { toast('Site removed', 'ok'); load(); }).catch(fail);
              });
            }, 'danger'));
            return a;
          } }].filter(Boolean), rows, 'No websites yet. Add one to get started.')));
      }, load);
    }
    load();
  };

  function placements(site) {
    var body = h('div');
    modal('Placements - ' + site.domain, body);
    function newPlacement() {
      var sizeOpts = SIZES.map(function (s) { return [s[0] + 'x' + s[1], s[0] + ' x ' + s[1]]; });
      formModal('New placement', [
        { k: 'name', t: 'Placement name', req: true, maxlen: 80, hint: 'For you, e.g. "Homepage banner".' },
        { k: 'format', t: 'Ad format', type: 'select', opts: [['banner', 'Banner (image)'], ['native', 'Native (title, text, image)'], ['text', 'Text']] },
        { k: 'size', t: 'Banner size', type: 'select', opts: sizeOpts, hint: 'Only used for banner placements.' }
      ], 'Create placement', function (v) {
        var p = { site_id: site.id, name: v.name, format: v.format };
        if (v.format === 'banner') { var s = v.size.split('x'); p.width = Number(s[0]); p.height = Number(s[1]); }
        return api('POST', '/api/placements', p);
      }, function () { toast('Placement created', 'ok'); draw(); });
    }
    function draw() {
      clear(body);
      body.appendChild(h('div', { class: 'toolbar' },
        h('p', { class: 'hint' }, 'Ads load only on ' + site.domain + ' and its subdomains.'),
        h('button', { class: 'btn', type: 'button', onclick: newPlacement }, 'New placement')));
      var holder = h('div');
      body.appendChild(holder);
      guard(holder, api('GET', '/api/placements' + qs({ site_id: site.id })), function (rows) {
        holder.appendChild(table([
          { t: 'Name', k: 'name' },
          { t: 'Format', f: function (r) { return r.format + (r.width ? ' ' + r.width + 'x' + r.height : ''); } },
          { t: 'Status', f: function (r) { return chip(r.status); } },
          { t: '', f: function (r) {
            var a = h('div', { class: 'row' });
            a.appendChild(btn('Get tag', function () { tagModal(r); }, 'btn'));
            a.appendChild(btn(r.status === 'active' ? 'Pause' : 'Resume', function () {
              api('PATCH', '/api/placements/' + r.id, { status: r.status === 'active' ? 'paused' : 'active' })
                .then(function () { toast('Placement updated', 'ok'); draw(); }).catch(fail);
            }));
            a.appendChild(btn('Remove', function () {
              confirmDo('Remove this placement? Its tag will stop showing ads.', function () {
                api('DELETE', '/api/placements/' + r.id).then(function () { toast('Placement removed', 'ok'); draw(); }).catch(fail);
              });
            }, 'danger'));
            return a;
          } }], rows, 'No placements yet. Create one to get an embed tag.'));
      }, draw);
    }
    draw();
  }

  function tagModal(p) {
    var slot = h('div');
    modal('Embed tag - ' + p.name, slot);
    function load() {
      clear(slot);
      slot.appendChild(loadingBlock('Loading tag…'));
      api('GET', '/api/placements/' + p.id + '/tag').then(function (t) {
        clear(slot);
        var pre = h('pre', { class: 'code' }, t.tag);
        var copy = copyButton(t.tag);
        slot.appendChild(h('p', { class: 'hint' }, 'Paste this where the ad should appear. Ads load only on the registered domain and its subdomains.'));
        slot.appendChild(h('div', { class: 'codeblock' }, pre, h('div', { class: 'row' }, copy)));
        if (t.https_warning) {
          slot.appendChild(h('div', { class: 'notice warn' }, 'This address is not HTTPS. Websites served over HTTPS cannot load it. Set a public HTTPS address in the Ad Network screen on the phone so the tag uses it.'));
        }
        slot.appendChild(h('div', { class: 'notice' }, 'Script address: ' + t.base_url + '/sdk.js'));
        slot.appendChild(h('p', { class: 'hint' }, 'If your site sends a Content-Security-Policy, allow this address in script-src, img-src and connect-src.'));
      }, function (e) {
        if (e && e.session) return;
        clear(slot);
        slot.appendChild(errorBox(e, load));
      });
    }
    load();
  }

  // ------------------------------------------------------------ campaigns + creatives
  function campaignFields(c, advertisers) {
    var t = (c && c.targeting) || {};
    var f = [
      { k: 'name', t: 'Campaign name', val: c && c.name, req: true, maxlen: 100 },
      { k: 'pricing', t: 'Pricing model', type: 'select', opts: [['CPM', 'CPM - pay per 1,000 viewable impressions'], ['CPC', 'CPC - pay per valid click']], val: c && c.pricing },
      { k: 'bid', t: 'Bid', type: 'number', req: true, min: 0.001, val: c ? c.bid_micros / 1e6 : '', hint: 'Price per 1,000 impressions (CPM) or per click (CPC). Minimum 0.001.' },
      { k: 'total_budget', t: 'Total budget', type: 'number', req: true, min: 0.001, val: c ? c.total_budget_micros / 1e6 : '' },
      { k: 'daily_budget', t: 'Daily budget', type: 'number', val: c && c.daily_budget_micros ? c.daily_budget_micros / 1e6 : '', min: 0, hint: 'Blank means no daily limit.', check: function (val, all) { if (val !== '' && all.total_budget !== '' && Number(val) > Number(all.total_budget)) return 'Daily budget cannot exceed the total budget.'; } },
      { k: 'start', t: 'Start date', type: 'date', val: c ? c.start_date : '' },
      { k: 'end', t: 'End date (inclusive)', type: 'date', val: c ? c.end_date : '', check: function (val, all) { if (val && all.start && val <= all.start) return 'End date must be after the start date.'; } },
      { k: 'devices', t: 'Devices (none = all)', type: 'checks', opts: [['mobile', 'Mobile'], ['tablet', 'Tablet'], ['desktop', 'Desktop']], val: t.devices },
      { k: 'languages', t: 'Languages, comma-separated (e.g. en,hi)', val: (t.languages || []).join(',') },
      { k: 'hours', t: 'Hours of day, e.g. 8-22 or 9,10,11 (server time)', val: hoursText(t.hours) },
      { k: 'weekdays', t: 'Days of week (none = all)', type: 'checks', opts: [['1', 'Mon'], ['2', 'Tue'], ['3', 'Wed'], ['4', 'Thu'], ['5', 'Fri'], ['6', 'Sat'], ['7', 'Sun']], val: (t.weekdays || []).map(String) },
      { k: 'url_contains', t: 'Only pages whose path contains (comma-separated)', val: (t.url_contains || []).join(',') },
      { k: 'url_excludes', t: 'Never on pages whose path contains', val: (t.url_excludes || []).join(',') },
      { k: 'freq_cap', t: 'Max views per visitor per day', type: 'number', min: 0, val: t.freq_cap_per_day || '', hint: 'Blank means unlimited.' }
    ];
    if (advertisers) f.unshift({ k: 'advertiser_id', t: 'Advertiser', type: 'select', opts: advertisers });
    return f;
  }
  function hoursText(a) { return (a || []).join(','); }
  function parseHours(s) {
    var out = [];
    String(s || '').split(',').forEach(function (p) {
      p = p.trim(); if (!p) return;
      var m = /^(\d{1,2})-(\d{1,2})$/.exec(p);
      if (m) { for (var i = Number(m[1]); i <= Number(m[2]); i++) out.push(i); } else out.push(Number(p));
    });
    return out;
  }
  function csvList(s) { return String(s || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean); }
  function campaignPayload(v) {
    var p = {
      name: v.name, pricing: v.pricing, bid: v.bid, total_budget: v.total_budget, daily_budget: v.daily_budget || 0,
      start: v.start || null, end: v.end || null,
      targeting: { devices: v.devices, languages: csvList(v.languages), hours: parseHours(v.hours), weekdays: v.weekdays.map(Number), url_contains: csvList(v.url_contains), url_excludes: csvList(v.url_excludes), freq_cap_per_day: Number(v.freq_cap) || 0 }
    };
    if (v.advertiser_id) p.advertiser_id = Number(v.advertiser_id);
    return p;
  }

  VIEWS.campaigns = function (box) {
    var admin = S.user.role === 'admin';
    function newCampaign() {
      var open = function (adv) {
        formModal('New campaign', campaignFields(null, adv), 'Create campaign', function (v) {
          return api('POST', '/api/campaigns', campaignPayload(v));
        }, function () { toast('Campaign created', 'ok'); load(); });
      };
      if (!admin) return open(null);
      api('GET', '/api/users?role=advertiser').then(function (u) {
        if (!u.length) { toast('Create an advertiser account first', 'err'); return; }
        open(u.map(function (a) { return [a.id, a.name + ' - ' + a.email]; }));
      }).catch(fail);
    }
    function load() {
      guard(box, api('GET', '/api/campaigns'), function (rows) {
        box.appendChild(h('div', { class: 'toolbar' },
          h('p', { class: 'hint' }, 'Campaigns need at least one creative before they can be submitted for approval.'),
          h('button', { class: 'btn', type: 'button', onclick: newCampaign }, 'New campaign')));
        box.appendChild(card('Campaigns', table([
          { t: 'Campaign', k: 'name' },
          admin ? { t: 'Advertiser', k: 'advertiser_email' } : null,
          { t: 'Status', f: function (r) { return h('span', { title: r.reject_reason || '' }, chip(r.status)); } },
          { t: 'Pricing', f: function (r) { return r.pricing + ' ' + money(r.bid_micros); } },
          { t: 'Spent / budget', num: true, f: function (r) { return money(r.spent_micros) + ' / ' + money(r.total_budget_micros); } },
          { t: 'Impr.', num: true, f: function (r) { return num(r.imps); } },
          { t: 'Clicks', num: true, f: function (r) { return num(r.clicks); } },
          { t: 'CTR', num: true, f: function (r) { return pct(r.clicks, r.imps); } },
          { t: '', f: function (r) {
            var a = h('div', { class: 'row' });
            a.appendChild(btn('Creatives', function () { creatives(r); }, 'btn'));
            if (/^(draft|rejected|paused)$/.test(r.status)) a.appendChild(btn('Edit', function () {
              formModal('Edit campaign', campaignFields(r, null), 'Save', function (v) {
                var p = campaignPayload(v); delete p.advertiser_id;
                return api('PATCH', '/api/campaigns/' + r.id, p);
              }, function () { toast('Campaign saved', 'ok'); load(); });
            }));
            if (/^(draft|rejected)$/.test(r.status)) a.appendChild(btn('Submit for approval', function () {
              api('POST', '/api/campaigns/' + r.id + '/submit').then(function () { toast('Campaign submitted for approval', 'ok'); load(); }).catch(fail);
            }));
            if (r.status === 'approved') a.appendChild(btn('Pause', function () {
              api('POST', '/api/campaigns/' + r.id + '/pause').then(function () { toast('Campaign paused', 'ok'); load(); }).catch(fail);
            }));
            if (r.status === 'paused') a.appendChild(btn('Resume', function () {
              api('POST', '/api/campaigns/' + r.id + '/resume').then(function () { toast('Campaign resumed', 'ok'); load(); }).catch(fail);
            }));
            if (admin && r.status === 'pending') {
              a.appendChild(btn('Approve', function () {
                api('POST', '/api/campaigns/' + r.id + '/approve', { approve_creatives: true }).then(function () { toast('Campaign approved', 'ok'); load(); }).catch(fail);
              }, 'btn'));
              a.appendChild(btn('Reject', function () {
                formModal('Reject campaign', [{ k: 'reason', t: 'Reason shown to the advertiser', req: true, maxlen: 300 }], 'Reject', function (v) {
                  return api('POST', '/api/campaigns/' + r.id + '/reject', v);
                }, function () { toast('Campaign rejected', 'ok'); load(); });
              }, 'danger'));
            }
            return a;
          } }].filter(Boolean), rows, 'No campaigns yet. Create one to get started.')));
      }, load);
    }
    load();
  };

  function preview(cr) {
    var img = h('img', { class: 'thumb', alt: '' });
    img.addEventListener('error', function () {
      var fallback = h('span', { class: 'muted' }, 'preview unavailable');
      if (img.parentNode) img.parentNode.replaceChild(fallback, img);
    });
    api('GET', '/api/creatives/' + cr.id + '/image').then(function (r) { return r.blob(); })
      .then(function (b) { img.src = URL.createObjectURL(b); }).catch(function () {});
    return img;
  }
  function uploadImage(file, url, done) {
    var problem = imageProblem(file);
    if (problem) { toast(problem, 'err'); return; }
    api('POST', url, file, true).then(function () { toast('Image uploaded - awaiting review', 'ok'); done(); }).catch(fail);
  }
  function creatives(c) {
    var body = h('div');
    modal('Creatives - ' + c.name, body);
    function draw() {
      clear(body);
      body.appendChild(h('div', { class: 'toolbar' },
        h('p', { class: 'hint' }, 'Creatives are reviewed before they run. Editing one sends it back for approval.'),
        h('button', { class: 'btn', type: 'button', onclick: function () { creativeForm(c, draw); } }, 'Add creative')));
      var holder = h('div');
      body.appendChild(holder);
      guard(holder, api('GET', '/api/creatives' + qs({ campaign_id: c.id })), function (rows) {
        var admin = S.user.role === 'admin';
        holder.appendChild(table([
          { t: 'Preview', f: function (r) { return r.has_image ? preview(r) : h('span', { class: 'muted' }, r.type === 'banner' ? 'no image yet' : '-'); } },
          { t: 'Type', f: function (r) { return r.type + (r.type === 'banner' ? ' ' + r.width + 'x' + r.height : ''); } },
          { t: 'Text', f: function (r) { return (r.title || '') + (r.body ? ' - ' + r.body : ''); } },
          { t: 'Status', f: function (r) { return h('span', { title: r.reject_reason || '' }, chip(r.status)); } },
          { t: '', f: function (r) {
            var a = h('div', { class: 'row' });
            var file = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif,image/webp', class: 'sr-only', 'aria-label': 'Creative image' });
            file.addEventListener('change', function () {
              if (file.files[0]) uploadImage(file.files[0], '/api/creatives/' + r.id + '/image', draw);
            });
            a.appendChild(file);
            a.appendChild(btn(r.has_image ? 'Replace image' : 'Upload image', function () { file.click(); }));
            a.appendChild(btn('Edit', function () {
              formModal('Edit creative (needs re-approval)', [
                { k: 'title', t: 'Title', val: r.title, maxlen: 70 },
                { k: 'body', t: 'Text', val: r.body, maxlen: 140 },
                { k: 'cta', t: 'Button text', val: r.cta, maxlen: 24 },
                { k: 'click_url', t: 'Destination URL', val: r.click_url, req: true, check: function (val) { if (!/^https?:\/\/\S+$/i.test(String(val || '').trim())) return 'Enter a full http:// or https:// address.'; } }
              ], 'Save', function (v) { return api('PATCH', '/api/creatives/' + r.id, v); }, function () { toast('Creative saved - awaiting review', 'ok'); draw(); });
            }));
            if (admin && r.status !== 'approved') a.appendChild(btn('Approve', function () {
              api('POST', '/api/creatives/' + r.id + '/approve').then(function () { toast('Creative approved', 'ok'); draw(); }).catch(fail);
            }, 'btn'));
            if (admin && r.status !== 'rejected') a.appendChild(btn('Reject', function () {
              formModal('Reject creative', [{ k: 'reason', t: 'Reason', req: true, maxlen: 300 }], 'Reject', function (v) {
                return api('POST', '/api/creatives/' + r.id + '/reject', v);
              }, function () { toast('Creative rejected', 'ok'); draw(); });
            }, 'danger'));
            a.appendChild(btn('Delete', function () {
              confirmDo('Delete this creative?', function () {
                api('DELETE', '/api/creatives/' + r.id).then(function () { toast('Creative deleted', 'ok'); draw(); }).catch(fail);
              });
            }, 'danger'));
            return a;
          } }], rows, 'No creatives yet. Add a banner, native or text ad.'));
      }, draw);
    }
    draw();
  }
  function creativeForm(c, done) {
    var sizeOpts = SIZES.map(function (s) { return [s[0] + 'x' + s[1], s[0] + ' x ' + s[1]]; });
    formModal('New creative', [
      { k: 'type', t: 'Type', type: 'select', opts: [['banner', 'Banner (image)'], ['native', 'Native (title, text, optional image)'], ['text', 'Text ad']] },
      { k: 'size', t: 'Banner size', type: 'select', opts: sizeOpts, hint: 'Banners only. The uploaded image must be exactly this size.' },
      { k: 'title', t: 'Title / headline', maxlen: 70, check: function (val, all) { if (all.type !== 'banner' && !String(val || '').trim()) return 'A title is required for native and text ads.'; } },
      { k: 'body', t: 'Description text', maxlen: 140, check: function (val, all) { if (all.type !== 'banner' && !String(val || '').trim()) return 'Description text is required for native and text ads.'; } },
      { k: 'cta', t: 'Button text (optional)', maxlen: 24 },
      { k: 'click_url', t: 'Destination URL', req: true, maxlen: 2048, hint: 'Where the ad takes people when clicked.', check: function (val) { if (!/^https?:\/\/\S+$/i.test(String(val || '').trim())) return 'Enter a full http:// or https:// address.'; } },
      { k: 'file', t: 'Image', type: 'file', hint: 'PNG, JPEG, GIF or WebP, max 2 MB. Required for banners.' }
    ], 'Add creative', function (v) {
      if (v.file) {
        var problem = imageProblem(v.file);
        if (problem) throw new Error(problem);
      }
      var p = { campaign_id: c.id, type: v.type, title: v.title, body: v.body, cta: v.cta, click_url: v.click_url };
      if (v.type === 'banner') { var s = v.size.split('x'); p.width = Number(s[0]); p.height = Number(s[1]); }
      return api('POST', '/api/creatives', p).then(function (r) {
        if (!v.file) return null;
        return api('POST', '/api/creatives/' + r.id + '/image', v.file, true).catch(function (e) {
          toast('Creative saved, but the image failed: ' + errorText(e), 'err');
        });
      });
    }, done);
  }

  VIEWS.approvals = function (box) {
    function load() {
      guard(box, api('GET', '/api/creatives' + qs({ status: 'pending' })), function (rows) {
        box.appendChild(card('Creatives waiting for review', table([
          { t: 'Preview', f: function (r) { return r.has_image ? preview(r) : '-'; } },
          { t: 'Campaign', k: 'campaign_name' },
          { t: 'Type', f: function (r) { return r.type + (r.type === 'banner' ? ' ' + r.width + 'x' + r.height : ''); } },
          { t: 'Text', f: function (r) { return (r.title || '') + (r.body ? ' - ' + r.body : ''); } },
          { t: 'Destination', f: function (r) { return h('a', { href: r.click_url, target: '_blank', rel: 'noopener noreferrer' }, r.click_url.length > 40 ? r.click_url.slice(0, 40) + '…' : r.click_url); } },
          { t: '', f: function (r) {
            return h('div', { class: 'row' },
              btn('Approve', function () {
                api('POST', '/api/creatives/' + r.id + '/approve').then(function () { toast('Creative approved', 'ok'); load(); }).catch(fail);
              }, 'btn'),
              btn('Reject', function () {
                formModal('Reject creative', [{ k: 'reason', t: 'Reason', req: true, maxlen: 300 }], 'Reject', function (v) {
                  return api('POST', '/api/creatives/' + r.id + '/reject', v);
                }, function () { toast('Creative rejected', 'ok'); load(); });
              }, 'danger'));
          } }], rows, 'No creatives are waiting for review.')));
      }, load);
    }
    load();
  };

  // ------------------------------------------------------------ reports
  function chart(days) {
    var ns = 'http://www.w3.org/2000/svg', W = 600, H = 160, pad = 16;
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'chart');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', 'Daily impressions and clicks');
    if (!days.length) return svg;
    var mi = Math.max.apply(null, days.map(function (d) { return d.imps; })) || 1;
    var mc = Math.max.apply(null, days.map(function (d) { return d.clicks; })) || 1;
    var bw = (W - pad * 2) / days.length;
    days.forEach(function (d, i) {
      var bh = (H - pad * 2) * d.imps / mi;
      var r = document.createElementNS(ns, 'rect');
      r.setAttribute('class', 'bar');
      r.setAttribute('x', pad + i * bw + 1);
      r.setAttribute('width', Math.max(1, bw - 2));
      r.setAttribute('y', H - pad - bh);
      r.setAttribute('height', bh);
      var tt = document.createElementNS(ns, 'title');
      tt.textContent = d.date + ': ' + num(d.imps) + ' impressions, ' + num(d.clicks) + ' clicks';
      r.appendChild(tt);
      svg.appendChild(r);
      var ch = (H - pad * 2) * d.clicks / mc;
      var c = document.createElementNS(ns, 'rect');
      c.setAttribute('class', 'bar2');
      c.setAttribute('x', pad + i * bw + bw / 2 - 1);
      c.setAttribute('width', 2);
      c.setAttribute('y', H - pad - ch);
      c.setAttribute('height', ch);
      svg.appendChild(c);
    });
    var t1 = document.createElementNS(ns, 'text');
    t1.setAttribute('x', pad);
    t1.setAttribute('y', 10);
    t1.textContent = 'Impressions (blue, max ' + num(mi) + ')  Clicks (green, max ' + num(mc) + ')';
    svg.appendChild(t1);
    return svg;
  }
  VIEWS.reports = function (box) {
    var role = S.user.role;
    var to = new Date(), from = new Date(Date.now() - 29 * 864e5);
    var byOpts = role === 'publisher'
      ? [['day', 'Day'], ['site', 'Site'], ['placement', 'Placement']]
      : [['day', 'Day'], ['campaign', 'Campaign'], ['creative', 'Creative'], ['site', 'Site'], ['placement', 'Placement']];
    var inFrom = h('input', { type: 'date', id: 'rep_from', value: ymd(from) });
    var inTo = h('input', { type: 'date', id: 'rep_to', value: ymd(to) });
    var inBy = h('select', { id: 'rep_by' }, byOpts.map(function (o) { return h('option', { value: o[0] }, o[1]); }));
    var inCamp = h('select', { id: 'rep_campaign' }, h('option', { value: '' }, 'All campaigns'));
    var inSite = h('select', { id: 'rep_site' }, h('option', { value: '' }, 'All sites'));
    var filterErr = h('div', { class: 'form-error', role: 'alert' });
    var runBtn = h('button', { class: 'btn', type: 'button', onclick: run }, 'Run report');
    var out = h('div');

    function filters() { return { from: inFrom.value, to: inTo.value, campaign_id: inCamp.value, site_id: inSite.value }; }

    function run() {
      var q = filters();
      var problem = '';
      if (!q.from || !q.to) problem = 'Choose both a start and an end date.';
      else if (q.from > q.to) problem = 'The start date must be on or before the end date.';
      filterErr.textContent = problem;
      if (problem) return;

      runBtn.disabled = true;
      clear(out);
      out.appendChild(loadingBlock('Running report…'));
      Promise.all([
        api('GET', '/api/reports/summary' + qs(q)),
        api('GET', '/api/reports/breakdown' + qs(Object.assign({ by: inBy.value }, q)))
      ]).then(function (res) {
        runBtn.disabled = false;
        if (!document.contains(out)) return;
        clear(out);
        var t = res[0].total, rows = res[1];
        var cards = [
          statCard('Ads served', num(t.served)), statCard('Valid impressions', num(t.imps)),
          statCard('Valid clicks', num(t.clicks)), statCard('CTR', pct(t.clicks, t.imps)),
          statCard('Invalid impressions', num(t.invalid_imps)), statCard('Invalid clicks', num(t.invalid_clicks))
        ];
        if (role !== 'publisher') cards.push(statCard('Spend', money(t.spend_micros)), statCard('Effective CPM', money(t.imps ? t.spend_micros * 1000 / t.imps : 0)));
        if (role !== 'advertiser') cards.push(statCard(role === 'admin' ? 'Publisher earnings' : 'Estimated revenue', money(t.earnings_micros)));
        out.appendChild(h('div', { class: 'stats' }, cards));
        out.appendChild(card('Daily trend', chart(res[0].days)));
        var cols = [
          { t: byOpts.filter(function (o) { return o[0] === inBy.value; })[0][1], k: 'label' },
          { t: 'Served', num: true, f: function (r) { return num(r.served); } },
          { t: 'Impr.', num: true, f: function (r) { return num(r.imps); } },
          { t: 'Clicks', num: true, f: function (r) { return num(r.clicks); } },
          { t: 'CTR', num: true, f: function (r) { return pct(r.clicks, r.imps); } },
          { t: 'Invalid', num: true, f: function (r) { return num(r.invalid_imps + r.invalid_clicks); } }
        ];
        if (role !== 'publisher') cols.push({ t: 'Spend', num: true, f: function (r) { return money(r.spend_micros); } });
        if (role !== 'advertiser') cols.push({ t: role === 'admin' ? 'Earnings' : 'Est. revenue', num: true, f: function (r) { return money(r.earnings_micros); } });
        out.appendChild(card(null, h('div', { class: 'toolbar' },
          h('h2', null, 'Breakdown'),
          btn('Export CSV', function () {
            api('GET', '/api/reports/export' + qs(Object.assign({ by: inBy.value }, filters()))).then(function (r) { return r.blob(); }).then(function (b) {
              var url = URL.createObjectURL(b);
              var a = h('a', { href: url, download: 'adnet-' + inBy.value + '.csv' });
              document.body.appendChild(a); a.click(); a.remove();
              setTimeout(function () { URL.revokeObjectURL(url); }, 5000);
            }).catch(fail);
          })), table(cols, rows, 'No tracked activity in this period.')));
        out.appendChild(h('p', { class: 'footnote' }, 'Impressions count only after an ad was at least half visible for one second. Invalid traffic is shown but is never charged or paid. Days are UTC.'));
      }, function (e) {
        runBtn.disabled = false;
        if (e && e.session) return;
        if (!document.contains(out)) return;
        clear(out);
        out.appendChild(errorBox(e, run));
      });
    }

    var bar = card(null,
      h('div', { class: 'fields' },
        h('div', { class: 'field' }, h('label', { for: 'rep_from' }, 'From'), inFrom),
        h('div', { class: 'field' }, h('label', { for: 'rep_to' }, 'To'), inTo),
        h('div', { class: 'field' }, h('label', { for: 'rep_by' }, 'Group by'), inBy),
        role !== 'publisher' ? h('div', { class: 'field' }, h('label', { for: 'rep_campaign' }, 'Campaign'), inCamp) : null,
        role !== 'advertiser' ? h('div', { class: 'field' }, h('label', { for: 'rep_site' }, 'Site'), inSite) : null),
      filterErr,
      h('div', { class: 'row end' }, runBtn));
    box.appendChild(bar);
    box.appendChild(out);

    var jobs = [];
    function optionJob(url, fn) {
      return api('GET', url).then(fn, function (e) { fail(e); });
    }
    if (role !== 'publisher') jobs.push(optionJob('/api/campaigns', function (cs) {
      cs.forEach(function (c) { inCamp.appendChild(h('option', { value: c.id }, c.name)); });
    }));
    if (role !== 'advertiser') jobs.push(optionJob('/api/sites', function (ss) {
      ss.forEach(function (s) { inSite.appendChild(h('option', { value: s.id }, s.domain)); });
    }));
    Promise.all(jobs).then(run);
  };

  // ------------------------------------------------------------ billing
  VIEWS.billing = function (box) {
    var admin = S.user.role === 'admin';
    var holder = h('div');
    if (admin) {
      var sel = h('select', { 'aria-label': 'Account' }, h('option', { value: '' }, 'Platform ledger (all accounts)'));
      sel.addEventListener('change', function () { draw(sel.value); });
      box.appendChild(card('Account', sel));
      api('GET', '/api/users').then(function (us) {
        us.filter(function (u) { return u.role !== 'admin'; }).forEach(function (u) {
          sel.appendChild(h('option', { value: u.id }, u.name + ' - ' + u.email + ' (' + u.role + ')'));
        });
      }).catch(fail);
    }
    box.appendChild(holder);
    function draw(uid) {
      guard(holder, api('GET', '/api/billing' + qs({ user_id: uid })), function (b) {
        var s = b.summary, cards = [];
        if (s.platform_margin_micros !== undefined) {
          cards = [
            statCard('Advertiser spend', money(s.advertiser_spend_micros)), statCard('Publisher earnings', money(s.publisher_earnings_micros)),
            statCard('Platform margin', money(s.platform_margin_micros)), statCard('Advertiser balances', money(s.advertiser_balances_micros)),
            statCard('Paid out', money(s.paid_out_micros))
          ];
        } else if (s.balance_micros !== undefined) {
          cards = [statCard('Balance', money(s.balance_micros)), statCard('Total spent', money(s.total_spent_micros))];
        } else if (s.earned_micros !== undefined) {
          cards = [statCard('Earned', money(s.earned_micros)), statCard('Paid out', money(s.paid_micros)), statCard('Unpaid', money(s.unpaid_micros))];
        }
        holder.appendChild(h('div', { class: 'stats' }, cards));
        holder.appendChild(card('Billing records', table([
          { t: 'Date', f: function (r) { return dt(r.ts); } },
          { t: 'Type', f: function (r) { return chip(r.type); } },
          { t: 'Details', k: 'description' },
          { t: 'Amount', num: true, f: function (r) { return money(r.amount_micros); } }
        ], b.lines, 'No billing activity yet.')));
        if (S.user.role === 'advertiser') holder.appendChild(h('p', { class: 'footnote' }, 'Funds are added by the administrator after payment is received. Spend is charged only for valid impressions or clicks.'));
        if (S.user.role === 'publisher') holder.appendChild(h('p', { class: 'footnote' }, 'Earnings are your revenue share of valid traffic on your sites. Payouts are recorded by the administrator.'));
      }, function () { draw(uid); });
    }
    draw('');
  };

  // ------------------------------------------------------------ fraud + settings (admin)
  VIEWS.fraud = function (box) {
    function load() {
      guard(box, api('GET', '/api/admin/fraud'), function (f) {
        box.appendChild(card('Sites flagged for review', table([
          { t: 'Domain', k: 'domain' },
          { t: 'Owner', k: 'owner_email' },
          { t: 'Why', k: 'flag_reason' },
          { t: '', f: function (r) {
            return h('div', { class: 'row' },
              btn('Looks fine - clear flag', function () {
                api('PATCH', '/api/sites/' + r.id, { flagged: false }).then(function () { toast('Flag cleared', 'ok'); load(); }).catch(fail);
              }),
              btn('Reject site', function () {
                api('PATCH', '/api/sites/' + r.id, { status: 'rejected', flagged: false }).then(function () { toast('Site rejected', 'ok'); load(); }).catch(fail);
              }, 'danger'));
          } }], f.flagged_sites, 'No sites are flagged.')));
        box.appendChild(card('Invalid traffic by reason (7 days)', table([
          { t: 'Reason', k: 'reason' },
          { t: 'Events', num: true, f: function (r) { return num(r.n); } }
        ], f.reasons_7d, 'No invalid traffic detected.')));
        box.appendChild(card('Sites with invalid traffic (7 days)', table([
          { t: 'Site', k: 'domain' },
          { t: 'Invalid impr.', num: true, f: function (r) { return num(r.invalid_imps); } },
          { t: 'Invalid clicks', num: true, f: function (r) { return num(r.invalid_clicks); } },
          { t: 'Valid impr.', num: true, f: function (r) { return num(r.imps); } },
          { t: 'Valid clicks', num: true, f: function (r) { return num(r.clicks); } }
        ], f.invalid_by_site_7d, 'Nothing to show.')));
        box.appendChild(h('p', { class: 'footnote' }, 'Rules: bot user-agents, domain mismatch, forged/expired/replayed tokens, clicks without a viewable impression, clicks faster than the minimum delay, repeat clicks, per-IP click and impression floods, and per-visitor daily caps. Flagged sites are never banned automatically.'));
      }, load);
    }
    load();
  };
  VIEWS.settings = function (box) {
    function load() {
      guard(box, api('GET', '/api/admin/settings'), function (s) {
        var fields = [
          { k: 'rev_share_pct', t: 'Publisher revenue share (%)', type: 'number', val: s.rev_share_pct, min: 0, max: 100 },
          { k: 'currency', t: 'Currency code (e.g. USD)', val: s.currency, maxlen: 3 },
          { k: 'public_base_url', t: 'Public base URL for tags', val: s.public_base_url, maxlen: 300, hint: 'Needed for HTTPS sites, e.g. https://ads.example.com.' },
          { k: 'allow_signup', t: 'Allow self sign-up (advertisers and publishers)', type: 'checkbox', val: s.allow_signup === '1' },
          { k: 'trust_proxy', t: 'Trust X-Forwarded-For from my reverse proxy', type: 'checkbox', val: s.trust_proxy === '1' },
          { k: 'fraud_min_click_ms', t: 'Min. time between impression and click (ms)', type: 'number', val: s.fraud_min_click_ms, min: 0 },
          { k: 'fraud_repeat_clicks_hour', t: 'Max valid clicks per visitor per campaign per hour', type: 'number', val: s.fraud_repeat_clicks_hour, min: 0 },
          { k: 'fraud_ip_clicks_min', t: 'Max clicks per IP per minute', type: 'number', val: s.fraud_ip_clicks_min, min: 0 },
          { k: 'fraud_max_imps_visitor_day', t: 'Max valid impressions per visitor per campaign per day', type: 'number', val: s.fraud_max_imps_visitor_day, min: 0 },
          { k: 'retention_days', t: 'Keep raw events for (days)', type: 'number', val: s.retention_days, min: 1 }
        ];
        box.appendChild(card('Settings', formBody(fields, 'Save settings', function (v) {
          var p = {};
          Object.keys(v).forEach(function (k) { p[k] = typeof v[k] === 'boolean' ? (v[k] ? '1' : '0') : String(v[k]); });
          return api('PUT', '/api/admin/settings', p).then(function () {
            toast('Settings saved', 'ok');
            return api('GET', '/api/meta').then(function (m) { S.meta = m; });
          });
        })));
      }, load);
    }
    load();
  };

  window.addEventListener('online', function () {
    if (app.querySelector('.panel-error')) boot();
  });

  boot();
})();
