# Ad Network Manager

The Ad Network Manager is an **optional**, self-hosted advertising server that
runs inside PhoneServer. It is a separate HTTP server on its **own port**
(`8090`), with its own SQLite database, its own files and its own account
system. It never touches the port-8080 file server, its data or its password.

It is off until the owner enables it, and it works without any cloud service,
ad exchange, third-party SDK or external database. A site embeds one small
script from the phone; the phone chooses an approved creative, counts real
impressions/clicks and keeps the billing ledger.

- [1. What it is](#1-what-it-is)
- [2. Getting started](#2-getting-started)
- [3. Roles and workflow](#3-roles-and-workflow)
- [4. Sites and placements](#4-sites-and-placements)
- [5. Campaigns, creatives and targeting](#5-campaigns-creatives-and-targeting)
- [6. Embedding the SDK](#6-embedding-the-sdk)
- [7. Counting, fraud and billing](#7-counting-fraud-and-billing)
- [8. Dashboard](#8-dashboard)
- [9. HTTP API](#9-http-api)
- [10. Data, retention and backups](#10-data-retention-and-backups)
- [11. Security and privacy](#11-security-and-privacy)
- [12. Limits and troubleshooting](#12-limits-and-troubleshooting)
- [13. Source files](#13-source-files)

---

## 1. What it is

| Property | Value |
|---|---|
| Default port | **8090** (`AdNetworkManager.PORT`) |
| Enablement | Opt-in switch in **Ad Network** settings; not started by default |
| Startup | Started by `ServerService` via `AdNetworkManager.startIfEnabled()` |
| Server | `AdHttpServer` — HTTP/1.1, one request per connection, 4–24 worker threads |
| Database | `adnet.db` from `AdDb` (app-private databases directory) |
| Preferences | `adnet_prefs` — enabled flag and the one-time setup code |
| Creative images | `filesDir/adnet/creatives/` (`AdDelivery.creativesDir`) |
| Dashboard | `GET /` on port 8090 (noindex, strict CSP) |
| Public endpoints | `/sdk.js`, `/serve`, `/imp`, `/c`, `/media/<id>`, `/health` |
| Accounts | Internal admin / advertiser / publisher accounts (email + password) |
| Currency model | Integer **micros** (1 unit = 1,000,000 micros); currency label defaults to `USD` |

The ad server has a deliberately different auth model from the file server:
there is no access-password gate on ad delivery, because the SDK must be
public. Management endpoints use bearer session tokens, and public delivery is
protected by placement keys, site-domain checks, signed event tokens and rate
limits.

> The ad network is **not** a third-party advertising SDK. Ads are served from
> the owner's phone. The documentation site itself (`web/`) never embeds an ad
> tag, so its privacy statements about no third-party requests remain true.

---

## 2. Getting started

1. In the app, open **Settings → Ad Network** and enable it. Start the main
   server (port 8080) as usual; the ad server starts with it.
2. Note the address: `http://<phone-ip>:8090` and the **setup code** shown only
   on the phone.
3. Open `http://<phone-ip>:8090/` in a browser on the same network.
4. Choose **Set up administrator**, enter the setup code, then an email,
   display name, company (optional) and a password (10–128 characters with at
   least one letter and one digit).
5. The first admin can create publisher and advertiser accounts in
   **Users**, or enable **Allow sign-up** in **Settings** (disabled by default).
6. A publisher registers a site and a placement, copies the tag into the site.
   An advertiser creates a campaign, adds creatives, submits it for review. An
   admin approves the site/campaign/creatives.
7. Approved creatives start serving. Use **Reports**, **Billing** and
   **Admin → Fraud** to monitor delivery.

Useful checks:

```bash
# Public health probe (no auth)
curl http://<phone-ip>:8090/health

# The SDK file is public and cacheable for an hour
curl -I http://<phone-ip>:8090/sdk.js
```

The setup code is single-use: it is cleared after the first administrator is
created. It can be regenerated from the app if needed. `/api/setup/status`
reports `{"needs_setup":true|false}` so the dashboard can show the correct
first-run screen.

---

## 3. Roles and workflow

| Role | Can do |
|---|---|
| **admin** | Everything: users, credits/payouts, approve/reject sites and campaigns, view all reports, fraud review, platform settings |
| **publisher** | Register sites and placements, fetch embed tags, view own site/placement reports and earnings |
| **advertiser** | Create campaigns and creatives, upload images, submit for review, view own campaign reports and spend |

Typical approval flow:

```
publisher: register site ──► admin approves site
publisher: create placement ──► copy tag
advertiser: create campaign (draft) ──► add creatives ──► submit
admin: approve campaign (optionally approves pending creatives)
advertiser: pause/resume as needed ──► delivery starts/stops
```

- A new site created by a publisher is `pending`; an admin-created site is
  `approved` immediately. Delivery only selects placements whose site is
  `approved` and placement is `active`.
- Campaigns are editable only in `draft`, `rejected` or `paused` state. Any
  creative edit sends it back to `pending` review, so an approved ad cannot be
  swapped for different content.
- Rejected campaigns/creatives carry a `reject_reason`, which publishers and
  advertisers can see in their own records.

---

## 4. Sites and placements

A **site** is a registered domain, owned by one publisher (or the admin). A
**placement** is a named slot on that site with a format and, for banners, a
size.

### Site fields

| Field | Rules |
|---|---|
| `name` | 1–80 characters |
| `domain` | Normalised hostname such as `example.com`; scheme, path, port and `www.` are stripped; subdomains match at delivery |
| limit | 50 active sites per owner |
| uniqueness | One active registration per domain |

`AdSecurity.normalizeDomain` accepts a real hostname (labels with letters,
digits and hyphens) or exactly `localhost`. **Bare IP addresses are rejected**
— register a hostname even for LAN testing.

### Placement fields

| Field | Rules |
|---|---|
| `site_id` | Must belong to the caller (admins may target any site) |
| `name` | 1–80 characters |
| `format` | `banner`, `native` or `text` |
| `width` / `height` | Required for `banner`, 1–2000 px each |
| limit | 100 active placements per site |
| `key` | Generated `pl_` + 16 hex characters, unique and non-secret |

Common banner sizes used by the dashboard: 300×250, 728×90, 320×50, 300×600,
160×600, 970×250, 336×280, 468×60 and 320×100. Any dimensions in range are
accepted, but the creative must match the placement exactly for banners.

`GET /api/placements/:id/tag` returns:

```json
{
  "tag": "<div data-adnet-placement=\"pl_0123456789abcdef\"></div>\n<script async src=\"http://192.168.1.50:8090/sdk.js\"></script>",
  "base_url": "http://192.168.1.50:8090",
  "https_warning": true
}
```

`base_url` comes from the admin **public_base_url** setting, falling back to
the request `Host` header. Set it explicitly to the HTTPS origin when the
hosting page is HTTPS; otherwise the browser blocks the script as mixed
content.

---

## 5. Campaigns, creatives and targeting

### Campaign fields

| Field | Rules |
|---|---|
| `name` | 1–100 characters |
| `pricing` | `CPM` or `CPC` |
| `bid` | Decimal ≥ 0.001; CPM is per 1000 impressions, CPC is per click |
| `total_budget` | > 0 |
| `daily_budget` | Optional; 0 = no daily cap; cannot exceed total |
| `start` / `end` | ISO/date strings parsed by the dashboard; end is required and must be after start |
| `targeting` | JSON object (see below) |
| `advertiser_id` | Admin only; must reference an advertiser account |

Budget and balance are checked at delivery. A campaign stops serving when its
total budget is exhausted; it also requires the advertiser's prepaid balance
to be positive. An admin funds advertisers with **credit**; publishers are paid
with **payout** ledger entries.

### Creative fields

| Field | Rules |
|---|---|
| `campaign_id` | Must belong to the caller (admins may target any campaign) |
| `type` | `banner`, `native` or `text` |
| `title` | Required for native/text; max 60 (native) or 70 (text/banner) |
| `body` | Required for native/text; max 140 (native) or 120 (text/banner) |
| `cta` | Optional, max 24 characters |
| `click_url` | Required, full `http://` or `https://` URL, max 2048, no credentials or whitespace |
| `width` / `height` | Required for banners and must match the placement |
| limit | 30 creatives per campaign |
| image | PNG, JPEG, GIF or WebP, ≤ 2 MB, ≤ 4000×4000 px, exact banner dimensions |

Images are uploaded as a raw body to `POST /api/creatives/:id/image` and are
served only by database id (`/media/<creativeId>`), never by a client-supplied
file path. SVG is rejected. Files are content-sniffed rather than trusting the
filename or `Content-Type`.

### Targeting keys

`targeting` accepts only these keys; unknown keys are dropped:

| Key | Type / rules |
|---|---|
| `devices` | Up to 3 of `mobile`, `tablet`, `desktop` |
| `languages` | Up to 20 two-letter codes, e.g. `en` |
| `sites` | Up to 50 site ids |
| `hours` | 0–23 |
| `weekdays` | 1–7, Monday = 1 |
| `url_contains` | Up to 10 lower-case words using letters, digits and `. _ - /` |
| `url_excludes` | Up to 10, same character rules |
| `freq_cap_per_day` | 0–100; 0 = no cap |

Targeting is evaluated against the page URL (without query/fragment, lower-
cased and truncated), the device parsed from the user agent, the browser
language prefix, the local hour/weekday and the placement's site. Geo targeting
is intentionally absent: the app has no IP-location database, so it does not
pretend to geotarget.

Selection is a weighted random rotation among eligible creatives using effective
CPM (for CPC, the smoothed expected value is `bid × (clicks + 2) / (imps + 200)
× 1000`). Campaigns with many creatives do not get extra weight. If nothing is
eligible, `/serve` answers **204 No Content**; it never substitutes a fake ad.

---

## 6. Embedding the SDK

Minimal tag (one placement per page or several):

```html
<div data-adnet-placement="pl_0123456789abcdef"></div>
<script async src="http://192.168.1.50:8090/sdk.js"></script>
```

The script finds every `[data-adnet-placement]` element and requests
`/serve?p=<key>&u=<page>&l=<language>`. It creates the ad markup (image for
banners, title/body/CTA for native/text), opens the click-through through `/c`
and sends a viewable impression to `/imp` once the ad has been **at least 50%
visible for one second**. The self-contained form
`data-adnet-placement` with `data-placement` is also supported by the SDK.

Requirements and gotchas:

- The request needs an `Origin` or `Referer`; the host must match the site's
  registered domain (subdomains allowed). A bare IP page cannot pass this
  check — use a hostname.
- HTTPS pages must use an HTTPS ad-server base URL (`public_base_url`), or the
  browser blocks the SDK as mixed content.
- A strict Content-Security-Policy on the hosting page must allow the ad
  server in `script-src`, `img-src` and `connect-src`.
- The tag is not secret and does not include credentials. Abuse is limited by
  the domain check, placement key, signed event tokens and rate limits.
- Do not embed the tag on the documentation site: `web/` promises no
  third-party requests and no tracking.

Public delivery headers include permissive CORS (`Access-Control-Allow-Origin:
*`), `Cross-Origin-Resource-Policy: cross-origin` and `X-Content-Type-Options:
nosniff`. Creative media is additionally sandboxed with `Content-Security-Policy:
default-src 'none'; sandbox`.

---

## 7. Counting, fraud and billing

Every counted event must correspond to a real HTTP request carrying a
server-signed token. Nothing is simulated.

- `/serve` issues an impression token: `v1|nonce|campaign|creative|placement|
  site|issued|visitor`, HMAC-SHA256 signed with a per-install random secret.
- Impression tokens are valid for **6 hours**; click tokens for **24 hours**.
  A token can be used **once**; replays are recorded as invalid traffic.
- Impressions count only after the SDK's viewability rule (≥50% for 1s).
- Clicks require a prior valid impression for the same nonce, otherwise they
  are recorded as `no_impression`/`invalid_impression`.
- Billing is `micros`: CPM costs `bid / 1000` per valid impression; CPC costs
  the full `bid` per valid click. The publisher share is `rev_share_pct`
  (default **70%**). When a valid event is billed, the campaign's
  `spent_micros` and the advertiser's `balance_micros` are updated in the same
  transaction and the event stores both `cost_micros` and `pub_micros`.
- Invalid events are still stored (with `valid=0` and a `reason`) so reports
  show invalid traffic, but they are **never billed** and never earn the
  publisher.

Invalid-traffic rules (`AdFraud`):

| Rule | Trigger |
|---|---|
| Bot user agent | Missing/short UA or a known bot/crawler/headless/HTTP-client signature |
| Domain mismatch | Origin/Referer host does not belong to the placement's site |
| Impression flood | More than 120 impressions/min/IP |
| Visitor cap | More than 50 valid impressions/day/visitor/campaign (configurable) |
| Click flood | More than 10 clicks/min/IP (configurable) |
| Click too fast | Less than 500 ms between impression and click (configurable) |
| Repeat clicks | More than 3 valid clicks/hour/visitor/campaign (configurable) |

Admins can review flagged sites. `AdFraud.reviewSites` flags (never auto-bans) a
site whose 48-hour click-through rate exceeds 15% (with ≥100 impressions) or
whose invalid-traffic share exceeds 40% (with ≥50 events).

### Privacy-related delivery behaviour

- `DNT: 1` or `Sec-GPC: 1` suppresses the visitor id: no frequency capping or
  per-visitor fraud check for that request. It does not suppress the ad itself.
- The visitor id is a daily-rotating HMAC of `IP + User-Agent`; no raw IP is
  stored with the visitor id, no cookie is set, and the id cannot be correlated
  across days.
- Bots receive **204** and no ad.
- No fill returns **204**; no house/fake ad is inserted.

---

## 8. Dashboard

Open `http://<phone-ip>:8090/`. It is a self-contained static app
(`dashboard.html`, `dashboard.js`, `dashboard.css`) with a strict CSP:

```
default-src 'self'; img-src 'self' blob: data:; style-src 'self';
script-src 'self'; connect-src 'self'; frame-ancestors 'none';
base-uri 'none'; form-action 'self'
```

Consequently the dashboard has no inline scripts, inline styles or inline event
handlers; icons are inline SVG built in JavaScript. It is `noindex, nofollow`
and requires JavaScript (a `<noscript>` panel explains this).

Main areas:

- **Overview** — real totals, daily trend, top campaigns/sites.
- **Sites / Placements** — register sites, create placements and copy tags.
- **Campaigns / Creatives** — create, edit, submit, approve/reject, upload
  images, pause/resume.
- **Reports** — summary and breakdown (`campaign`, `site`, `placement`,
  `creative`, `day`, `country` where applicable) with CSV export.
- **Users** — admin-only; create/disable users, credit advertisers, record
  publisher payouts and delete users.
- **Billing** — ledger lines plus daily spend/earnings.
- **Admin** — platform settings, fraud review, overview.

The dashboard uses a session bearer token held by the browser. The API is
same-origin only (`connect-src 'self'`), so the token is not exposed to other
origins.

### Deleting a user

Admins can delete an advertiser or publisher from **Users**. This is permanent:

- the account cannot delete itself;
- the **last administrator** cannot be deleted;
- the user's `stats_daily` and `events` rows are purged first (for owned
  campaigns/sites);
- the user row is deleted, cascading to sites, placements, campaigns,
  creatives, billing rows and sessions;
- the deleted creative image files are removed (after IDs are looked up);
- the action is recorded in the audit log as `user.delete` with the email and
  id.

If you only want to block access, **disable** the account instead; disabling
also invalidates its sessions.

---

## 9. HTTP API

Base URL: `http://<phone-ip>:8090`. All `/api/*` management endpoints return
JSON and are same-origin in the dashboard. Authentication is
`Authorization: Bearer <session-token>` except for setup/login/signup.

### Public endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | `{"status":"ok"}` |
| `GET` | `/sdk.js` | JavaScript SDK (cache 1h) |
| `GET` | `/serve?p=<key>&u=<page>&l=<lang>` | Choose an ad; 204 on no fill |
| `POST` | `/imp?t=<token>` | Viewable impression beacon; 204 |
| `GET` | `/c?t=<token>` | Count click, then 302 to the creative URL |
| `GET` | `/media/<creativeId>` | Approved creative image |
| `OPTIONS` | public paths | CORS preflight |

### Auth and account

| Method | Path | Role | Purpose |
|---|---|---|---|
| `GET` | `/api/setup/status` | public | Whether the first admin is needed |
| `POST` | `/api/setup` | public | Create first admin with the phone's setup code |
| `POST` | `/api/auth/login` | public | Email + password → `{token,user}` |
| `POST` | `/api/auth/signup` | public if enabled | Self-service advertiser/publisher |
| `GET` | `/api/auth/me` | any | Current user |
| `POST` | `/api/auth/logout` | any | Revoke the current session |
| `POST` | `/api/auth/password` | any | Change password (revokes other sessions) |
| `GET` | `/api/meta` | any | Currency, rev share, signup flag, base URL |

Passwords use PBKDF2-HMAC-SHA256 with 60,000 iterations and a random salt;
sessions are 12-hour random bearer tokens stored as SHA-256 hashes. Login
attempts are limited per IP+email and per IP; unknown emails still run a dummy
hash so timing cannot reveal account existence.

### Users (admin only)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/users?role=` | List users; `role` optional |
| `POST` | `/api/users` | Create `{email,name,company,password,role}` |
| `PATCH` | `/api/users/:id` | Update name/company/status/password |
| `DELETE` | `/api/users/:id` | Delete user and owned data (see §8) |
| `POST` | `/api/users/:id/credit` | Top up/adjust an advertiser balance |
| `POST` | `/api/users/:id/payout` | Record a publisher payout |

Credit body: `{"kind":"topup"|"adjustment","amount":10.00,"debit":false,
"note":"..."}`. Payout body: `{"amount":5.00,"note":"..."}`; it cannot exceed
unpaid earnings and only applies to publishers.

### Sites and placements

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/sites` | List visible sites |
| `POST` | `/api/sites` | Create `{name,domain}` |
| `PATCH` | `/api/sites/:id` | Rename/re-domain (admin can set status/flags) |
| `DELETE` | `/api/sites/:id` | Soft-remove site and its placements |
| `GET` | `/api/placements?site_id=` | List placements |
| `POST` | `/api/placements` | Create `{site_id,name,format,width,height}` |
| `PATCH` | `/api/placements/:id` | Rename or set `active`/`paused` |
| `DELETE` | `/api/placements/:id` | Soft-remove placement |
| `GET` | `/api/placements/:id/tag` | Return embed tag and base URL |

### Campaigns and creatives

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/campaigns` | List visible campaigns |
| `POST` | `/api/campaigns` | Create campaign |
| `GET` | `/api/campaigns/:id` | Campaign detail |
| `PATCH` | `/api/campaigns/:id` | Edit editable campaigns |
| `POST` | `/api/campaigns/:id/submit` | Submit for review |
| `POST` | `/api/campaigns/:id/approve` | Admin approve (optional `approve_creatives`) |
| `POST` | `/api/campaigns/:id/reject` | Admin reject with `reason` |
| `POST` | `/api/campaigns/:id/pause` | Pause an approved campaign |
| `POST` | `/api/campaigns/:id/resume` | Resume a paused campaign |
| `GET` | `/api/creatives?campaign_id=` | List creatives |
| `POST` | `/api/creatives` | Create creative |
| `PATCH` | `/api/creatives/:id` | Edit text/URL (returns to pending) |
| `DELETE` | `/api/creatives/:id` | Delete creative and its image |
| `POST` | `/api/creatives/:id/image` | Raw image upload (≤2 MB) |
| `GET` | `/api/creatives/:id/image` | Fetch the image (owner/admin) |
| `POST` | `/api/creatives/:id/approve` | Admin approve |
| `POST` | `/api/creatives/:id/reject` | Admin reject with `reason` |

Example: create a publisher site and a banner placement.

```bash
BASE=http://192.168.1.50:8090
TOKEN=...   # from /api/auth/login

curl -s -X POST "$BASE/api/sites" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"My blog","domain":"blog.example.com"}'

curl -s -X POST "$BASE/api/placements" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"site_id":1,"name":"Sidebar","format":"banner","width":300,"height":250}'

curl -s "$BASE/api/placements/1/tag" -H "Authorization: Bearer $TOKEN"
```

### Reports and billing

| Method | Path | Parameters |
|---|---|---|
| `GET` | `/api/reports/summary` | `from`, `to`, `campaign_id`, `site_id` |
| `GET` | `/api/reports/breakdown` | above + `by` (`campaign`, `site`, `placement`, `creative`, `day`) |
| `GET` | `/api/reports/export` | same as breakdown; returns CSV download |
| `GET` | `/api/billing` | advertisers: own ledger/spend; publishers: own earnings; admin: all |

Dates are `YYYY-MM-DD` in UTC. The default range is the last 30 days; ranges
are capped at 366 days. Breakdown is capped at 500 rows ordered by impressions.
Advertisers never see publisher earnings; publishers never see advertiser spend
or campaign identities.

### Admin

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/admin/overview` | Platform totals |
| `GET` | `/api/admin/fraud` | Invalid-traffic summary and flagged sites |
| `GET` | `/api/admin/settings` | Current platform settings |
| `PUT` | `/api/admin/settings` | Update platform settings |

Settings keys: `rev_share_pct`, `currency`, `public_base_url`, `allow_signup`,
`trust_proxy`, `fraud_min_click_ms`, `fraud_repeat_clicks_hour`,
`fraud_ip_clicks_min`, `fraud_max_imps_visitor_day`, `retention_days`.

`trust_proxy` is off by default. When enabled, the client IP is taken from the
**last** `X-Forwarded-For` entry, which is the value appended by the nearest
proxy; never enable it when clients can set that header directly.

---

## 10. Data, retention and backups

`adnet.db` lives in the app's private databases directory and contains:

| Table | Purpose |
|---|---|
| `users` | Accounts, PBKDF2 password hash, role, status, prepaid balance (micros) |
| `sessions` | SHA-256 hashes of bearer tokens with expiry |
| `sites` | Domains, owner, moderation status, fraud flag/reason |
| `placements` | Format/size, public placement key, status |
| `campaigns` | Budget/pricing, spend, schedule, targeting JSON, review status |
| `creatives` | Text/URL, image metadata, review status |
| `events` | Every impression/click, validity, reason, cost and publisher share |
| `stats_daily` | Aggregated daily metrics per campaign/creative/placement/site |
| `fraud_log` | Daily counts per invalid-traffic reason |
| `billing` | Top-ups, adjustments, payouts and resulting balances |
| `audit` | Admin actions such as setup, create/delete user, approvals |
| `settings` | Platform configuration and the HMAC secret |

A background sweeper runs once a minute while the ad server is running:

- deletes expired sessions and expired rate-limit buckets;
- reviews sites for fraud every 10 minutes;
- every 60 minutes, deletes events older than `retention_days` (default 30),
  deletes fraud-log rows older than 90 days, ends campaigns whose `end_at` has
  passed, and removes creative image files with no database row (older than one
  hour, so in-flight uploads are safe).

Backups are **not** included in the `/api/v1` project-backup system. To back
up the ad network, copy `adnet.db` (plus its `-wal`/`-shm` sidecars while the
server is stopped) and `filesDir/adnet/creatives/` from an app-private backup
or rooted file manager. The `web/UPDATE_MANIFEST.txt` archive is source code,
not runtime data.

---

## 11. Security and privacy

- **Separate surface.** Port 8090, database, files, thread pool and prefs are
  independent of the port-8080 server. Enabling ads cannot weaken file-server
  authentication.
- **Management auth.** Bearer sessions expire after 12 hours; logout deletes
  the session. Admin-only routes check the role server-side. Password changes
  revoke all other sessions.
- **Setup.** The first admin requires the one-time code shown on the phone, and
  setup attempts are rate-limited.
- **Delivery checks.** Placement key format, site approval, placement active
  status, `Origin`/`Referer` domain match, bot filtering, signed single-use
  tokens, token expiry and event rate limits.
- **Input validation.** Email/domain/URL validation, length limits, numeric
  bounds, strict image sniffing and dimension checks, SQL with bound parameters
  (the only string interpolation is from a fixed allow-list of report
  groupings).
- **Framing and headers.** Header values are stripped of CR/LF, the dashboard
  sends a restrictive CSP plus `X-Frame-Options: DENY`, all responses send
  `X-Content-Type-Options: nosniff`, and API responses are `no-store`.
- **Privacy.** No cookies, no raw-IP visitor storage, daily-rotating visitor
  hash, DNT/GPC honoured for visitor tracking, no third-party requests and no
  cloud component.
- **Known limitations.** HTTP is plaintext on the LAN; there is no TLS in the
  app, no email verification/password reset, no CAPTCHA, and no guarantee that
  a determined attacker cannot generate invalid traffic. Treat it as a
  small/private ad server, not an ad exchange. For anything beyond a trusted
  network, terminate TLS at a reverse proxy and keep port 8090 off the public
  internet.

---

## 12. Limits and troubleshooting

| Limit | Value |
|---|---|
| Request header line / count | 8 KiB / 64 |
| Buffered `/api/*` body | 128 KiB |
| Creative image upload | 2 MiB (server hard cap 3 MiB) |
| Sites per owner | 50 |
| Placements per site | 100 |
| Creatives per campaign | 30 |
| Report range | 366 days |
| Breakdown rows | 500 |
| Serve rate | 90/min/IP |
| Impression beacon rate | 300/min/IP |
| Click rate | 60/min/IP |
| Management API rate | 300/min/user |
| Event retention | 30 days (configurable) |

Common causes:

| Symptom | Check |
|---|---|
| Tag shows nothing | Site approved? Placement active? Campaign approved and funded? Creative approved? |
| `403 Domain not allowed` | Page host vs. registered domain; bare IPs cannot match |
| `204` responses | No eligible campaign/creative (budget, schedule, targeting, no fill) |
| SDK blocked on HTTPS page | Set `public_base_url` to an HTTPS ad-server URL and update the tag |
| Browser blocks the script | Hosting page CSP must allow the ad-server origin |
| Ads serve but no impressions | Viewability rule (≥50% for 1s), or DNT/GPC suppressing visitor tracking only |
| Login fails | Password policy, account disabled, rate-limited after repeated attempts |
| Reports missing today | Counters aggregate by UTC day; today's partial row appears after the first event |
| Dashboard broken | JavaScript disabled, or a modified asset violating the CSP (no inline code/styles) |

---

## 13. Source files

| File | Role |
|---|---|
| `AdNetworkManager.java` | Opt-in lifecycle, port 8090, setup code, sweeper |
| `AdHttpServer.java` | HTTP/1.1 parsing/routing, limits, CSP and headers |
| `AdApi.java` | Management API, validation, account/site/campaign/creative logic |
| `AdDelivery.java` | `/sdk.js`, `/serve`, `/imp`, `/c`, `/media`, selection and billing |
| `AdSecurity.java` | PBKDF2 passwords, sessions, HMAC tokens, visitor hash, validation |
| `AdDb.java` | Schema, settings defaults, query helpers |
| `AdReports.java` | Summary/breakdown/CSV/billing queries and role scoping |
| `AdFraud.java` | Invalid-traffic rules and site flagging |
| `AdHttp.java` | Request/response objects, JSON helpers, status codes |
| `AdRateLimiter.java` | In-memory sliding-window limits |
| `assets/adnet/dashboard.html` | Dashboard shell (strict CSP, `<noscript>`) |
| `assets/adnet/dashboard.js` | Dashboard application, SVG icons, API client |
| `assets/adnet/dashboard.css` | Dashboard styles, responsive drawer navigation |
| `assets/adnet/sdk.js` | Public embed script and viewability beacon |

Related documentation: [API.md](API.md) (port 8080 data API),
[architecture.md](architecture.md), [storage.md](storage.md),
[security.md](security.md), [testing.md](testing.md) and
[platform-compatibility.md](platform-compatibility.md).
