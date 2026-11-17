const ENDPOINTS = [
  { method: "GET", path: "/health", body: "" },
  { method: "GET", path: "/", body: "" },
  { method: "GET", path: "/system", body: "" },
  { method: "GET", path: "/tokens", body: "" },
  { method: "POST", path: "/tokens", body: '{"name":"ci","projectId":null,"canWrite":true}' },
  { method: "PATCH", path: "/tokens/{id}", body: '{"role":"editor"}' },
  { method: "DELETE", path: "/tokens/{id}", body: "" },
  { method: "GET", path: "/projects", body: "" },
  { method: "POST", path: "/projects", body: '{"name":"Example","description":""}' },
  { method: "GET", path: "/projects/{id}", body: "" },
  { method: "PATCH", path: "/projects/{id}", body: '{"name":"Renamed"}' },
  { method: "DELETE", path: "/projects/{id}", body: "" },
  { method: "GET", path: "/projects/{id}/stats", body: "" },
  { method: "GET", path: "/projects/{id}/usage", body: "" },
  { method: "GET", path: "/projects/{id}/databases", body: "" },
  { method: "POST", path: "/projects/{id}/databases", body: '{"name":"app"}' },
  { method: "GET", path: "/projects/{id}/databases/{db}", body: "" },
  { method: "DELETE", path: "/projects/{id}/databases/{db}", body: "" },
  { method: "GET", path: "/projects/{id}/databases/{db}/tables", body: "" },
  { method: "GET", path: "/projects/{id}/databases/{db}/tables/{table}", body: "" },
  { method: "POST", path: "/projects/{id}/databases/{db}/query", body: '{"sql":"SELECT * FROM users LIMIT 10","limit":10,"offset":0}' },
  { method: "POST", path: "/projects/{id}/databases/{db}/transaction", body: '{"statements":["CREATE TABLE demo (id INTEGER PRIMARY KEY, name TEXT)"]}' },
  { method: "GET", path: "/projects/{id}/databases/{db}/export", body: "" },
  { method: "POST", path: "/projects/{id}/databases/{db}/import", body: "binary upload" },
  { method: "GET", path: "/projects/{id}/files", body: "" },
  { method: "POST", path: "/projects/{id}/files", body: "multipart file upload" },
  { method: "GET", path: "/projects/{id}/files/{fileId}", body: "" },
  { method: "PATCH", path: "/projects/{id}/files/{fileId}", body: '{"name":"new-name.txt"}' },
  { method: "DELETE", path: "/projects/{id}/files/{fileId}", body: "" },
  { method: "GET", path: "/projects/{id}/files/{fileId}/download", body: "" },
  { method: "GET", path: "/projects/{id}/folders", body: "" },
  { method: "POST", path: "/projects/{id}/folders", body: '{"path":"docs"}' },
  { method: "DELETE", path: "/projects/{id}/folders", body: '{"path":"docs"}' },
  { method: "GET", path: "/projects/{id}/backups", body: "" },
  { method: "POST", path: "/projects/{id}/backups", body: '{"name":"manual"}' },
  { method: "POST", path: "/projects/{id}/backups/import", body: "binary upload" },
  { method: "GET", path: "/projects/{id}/backups/{backupId}", body: "" },
  { method: "DELETE", path: "/projects/{id}/backups/{backupId}", body: "" },
  { method: "POST", path: "/projects/{id}/backups/{backupId}/restore", body: '{}' },
  { method: "POST", path: "/projects/{id}/backups/{backupId}/validate", body: '{}' },
  { method: "GET", path: "/projects/{id}/backups/{backupId}/download", body: "" },
  { method: "GET", path: "/mail/accounts", body: "" },
  { method: "POST", path: "/mail/accounts", body: '{"name":"VPS mail","email":"me@example.com","username":"me@example.com","password":"APP_PASSWORD","smtpHost":"smtp.example.com","smtpPort":587,"smtpSecurity":"starttls","imapHost":"imap.example.com","imapPort":993,"imapSecurity":"tls"}' },
  { method: "GET", path: "/mail/accounts/{id}", body: "" },
  { method: "PATCH", path: "/mail/accounts/{id}", body: '{"fromName":"PhoneServer"}' },
  { method: "DELETE", path: "/mail/accounts/{id}", body: "" },
  { method: "POST", path: "/mail/accounts/{id}/test", body: '{}' },
  { method: "POST", path: "/mail/accounts/{id}/sync", body: '{"limit":50}' },
  { method: "GET", path: "/mail/accounts/{id}/messages?folder=INBOX", body: "" },
  { method: "POST", path: "/mail/accounts/{id}/send", body: '{"to":"you@example.net","subject":"Hello","text":"Sent from PhoneServer"}' },
  { method: "POST", path: "/mail/accounts/{id}/drafts", body: '{"to":"you@example.net","subject":"Draft","text":"Saved locally"}' },
  { method: "GET", path: "/mail/messages/{id}", body: "" },
  { method: "POST", path: "/mail/messages/{id}/read", body: '{"seen":true}' },
  { method: "DELETE", path: "/mail/messages/{id}", body: "" },
  { method: "GET", path: "/mail/messages/{id}/attachments/{index}", body: "" }
];

const $ = (id) => document.getElementById(id);

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[c]);
}

function highlight(value) {
  let text = escapeHtml(value);
  text = text.replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="kw">$1</span>$2');
  text = text.replace(/(&quot;[^&]*?&quot;)/g, '<span class="str">$1</span>');
  text = text.replace(/\b(true|false|null)\b/g, '<span class="kw">$1</span>');
  text = text.replace(/\b(-?\d+(?:\.\d+)?)\b/g, '<span class="num">$1</span>');
  return text;
}

function renderEndpoints() {
  const table = $("endpointTable");
  table.innerHTML = ENDPOINTS.map((item) => {
    return '<div class="endpoint-row"><span class="method">' + item.method +
      '</span><span class="endpoint-path"><code>' + escapeHtml(item.path) + "</code></span></div>";
  }).join("");

  const select = $("endpointSelect");
  select.innerHTML = ENDPOINTS.map((item, index) => {
    return '<option value="' + index + '">' + item.method + " " + escapeHtml(item.path) + "</option>";
  }).join("");
  select.addEventListener("change", () => {
    const item = ENDPOINTS[Number(select.value)];
    $("bodyInput").value = item.body;
  });
  $("bodyInput").value = ENDPOINTS[0].body;
}

function renderBaseUrl() {
  let base = "http://<device-ip>:8080/api/v1";
  if (window.PhoneServerDocs && window.PhoneServerDocs.baseUrl) {
    base = window.PhoneServerDocs.baseUrl();
  }
  $("baseUrlInput").value = base;
}

function fillPath(path) {
  return path.replace(/\{[^}]+\}/g, () => {
    const value = prompt("Replace path parameter", "");
    return value === null ? "" : encodeURIComponent(value);
  });
}

function buildRequest() {
  const item = ENDPOINTS[Number($("endpointSelect").value)];
  const base = $("baseUrlInput").value.trim().replace(/\/$/, "");
  const url = base + fillPath(item.path);
  const authType = $("authType").value;
  const credential = $("credentialInput").value;
  const headers = {};
  let body = "";
  if (item.body && !["GET"].includes(item.method)) {
    body = $("bodyInput").value;
    if (body.trim()) headers["Content-Type"] = "application/json";
  }
  if (authType === "bearer" && credential) headers.Authorization = "Bearer " + credential;
  if (authType === "password" && credential) headers["X-Auth-Password"] = credential;
  return { url, method: item.method, headers, body };
}

function curlFor(request) {
  let command = "curl -X " + request.method;
  for (const key of Object.keys(request.headers)) {
    command += " -H '" + key + ": " + request.headers[key].replace(/'/g, "'\\''") + "'";
  }
  if (request.body) command += " -d '" + request.body.replace(/'/g, "'\\''") + "'";
  return command + " '" + request.url.replace(/'/g, "'\\''") + "'";
}

function showResponse(text) {
  $("responseOutput").textContent = text;
}

window.PhoneServerDocsCallback = {
  onTryResult: function (json) {
    showResponse(json);
  }
};

function sendRequest() {
  const request = buildRequest();
  showResponse("Sending " + request.method + " " + request.url + "...");
  if (window.PhoneServerDocs && window.PhoneServerDocs.tryApi) {
    window.PhoneServerDocs.tryApi(JSON.stringify(request));
    return;
  }
  fetch(request.url, { method: request.method, headers: request.headers, body: request.body || undefined })
    .then(async (response) => {
      const text = await response.text();
      showResponse("HTTP " + response.status + " " + response.statusText + "\n\n" + text.slice(0, 200000));
    })
    .catch((error) => showResponse("Request failed: " + error.message));
}

function setupSearch() {
  const input = $("searchInput");
  input.addEventListener("input", () => {
    const term = input.value.trim().toLowerCase();
    document.querySelectorAll(".doc-section").forEach((section) => {
      const text = section.textContent.toLowerCase();
      section.classList.toggle("hidden", term.length > 0 && !text.includes(term));
    });
  });
}

function setupNavigation() {
  const links = Array.from(document.querySelectorAll(".sidebar nav a"));
  links.forEach((link) => link.addEventListener("click", () => {
    document.getElementById("menuButton").click();
  }));
  const sections = links.map((link) => document.querySelector(link.getAttribute("href"))).filter(Boolean);
  if (!("IntersectionObserver" in window)) return;
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      links.forEach((link) => link.classList.toggle("active", link.getAttribute("href") === "#" + entry.target.id));
    });
  }, { rootMargin: "-20% 0px -70% 0px" });
  sections.forEach((section) => observer.observe(section));
}

function loadChangelog() {
  fetch("changelog.md")
    .then((response) => response.text())
    .then((text) => { $("changelogContent").textContent = text; })
    .catch(() => { $("changelogContent").textContent = "Changelog is bundled with the app."; });
}

document.addEventListener("DOMContentLoaded", () => {
  renderEndpoints();
  renderBaseUrl();
  setupSearch();
  setupNavigation();
  loadChangelog();
  $("sendRequestButton").addEventListener("click", sendRequest);
  $("copyCurlButton").addEventListener("click", () => {
    const command = curlFor(buildRequest());
    if (navigator.clipboard) navigator.clipboard.writeText(command);
    showResponse(command);
  });
  $("menuButton").addEventListener("click", () => $("sidebar").classList.toggle("open"));
  document.querySelectorAll("pre code").forEach((block) => {
    block.innerHTML = highlight(block.textContent);
  });
});
