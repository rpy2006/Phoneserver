(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.PhoneServerSDK = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  class PhoneServerSDK {
    constructor(options) {
      options = options || {};
      this.baseUrl = String(options.baseUrl || '').replace(/\/$/, '');
      this.token = options.token || '';
      this.timeoutMs = Number(options.timeoutMs || 15000);
      if (!this.baseUrl) throw new Error('baseUrl is required');
    }

    setToken(token) { this.token = token || ''; }

    async request(path, opts) {
      opts = opts || {};
      const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
      const timer = controller ? setTimeout(() => controller.abort(), this.timeoutMs) : null;
      const headers = Object.assign({}, opts.headers || {});
      if (this.token) headers['Authorization'] = 'Bearer ' + this.token;
      if (opts.body && !(opts.body instanceof FormData) && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
      let res;
      try {
        res = await fetch(this.baseUrl + path, {
          method: opts.method || 'GET',
          headers,
          body: opts.body instanceof FormData ? opts.body : (opts.body ? JSON.stringify(opts.body) : undefined),
          signal: controller ? controller.signal : undefined
        });
      } catch (e) {
        if (e && e.name === 'AbortError') throw new Error('Request timed out');
        throw e;
      } finally {
        if (timer) clearTimeout(timer);
      }
      const text = await res.text();
      let data = text;
      try { data = text ? JSON.parse(text) : null; } catch (_) {}
      if (!res.ok) {
        const err = new Error((data && data.error) || ('HTTP ' + res.status));
        err.status = res.status;
        err.data = data;
        throw err;
      }
      return data;
    }

    listFiles(projectId) { return this.request('/projects/' + encodeURIComponent(projectId) + '/files'); }
    uploadFile(projectId, file, meta) {
      const fd = new FormData();
      fd.append('file', file);
      if (meta && meta.path) fd.append('path', meta.path);
      return this.request('/projects/' + encodeURIComponent(projectId) + '/files', { method: 'POST', body: fd });
    }
    downloadFile(projectId, fileId) { return this.request('/projects/' + encodeURIComponent(projectId) + '/files/' + encodeURIComponent(fileId) + '/download'); }
    createShare(payload) { return this.request('/sharing', { method: 'POST', body: payload || {} }); }
    listShares() { return this.request('/sharing'); }
    trackAdEvent(payload) { return this.request('/ads/events', { method: 'POST', body: payload || {} }); }
    registerWebhook(payload) { return this.request('/developer/webhooks', { method: 'POST', body: payload || {} }); }
    listWebhooks() { return this.request('/developer/webhooks'); }
    getUsage() { return this.request('/developer/usage'); }
  }
  return PhoneServerSDK;
}));
