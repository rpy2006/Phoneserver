# Ad Network Manager

The Ad Network Manager is an **optional**, self-hosted advertising server that
runs inside PhoneServer. It is a separate HTTP server on its **own port**
(`8090`), with its own encrypted JSON data store, its own files and its own
account system. It never touches the port-8080 file server, its data or its password.

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
  - [Conversion tracking](#conversion-tracking)
  - [Experiments, geography, payouts and email](#experiments-geography-payouts-and-email)
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
| Data store | encrypted `adnet/adnet.json` from `AdDb` (app-private files directory) |
| Preferences | `adnet_prefs` — enabled flag and the one-time setup code |
| Creative images | `filesDir/adnet/creatives/` (`AdDelivery.creativesDir`) |
| Dashboard | `GET /` on port 8090 (noindex, strict CSP) |
| Public endpoints | `/sdk.js`, `/serve`, `/imp`, `/c`, `/media/<id>`, `/conv`, `/postback`, `/health` |
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
| **admin** | Everything: users, credits/payouts, approve/reject sites and campaigns, view all reports, manage experiments/geo/summary mail, fraud review, platform settings |
| **publisher** | Register sites and placements, fetch embed tags, view own site/placement/geo reports, payout statements and earnings |
| **advertiser** | Create campaigns, creatives and experiments, upload images, submit for review, view own campaign/conversion/geo reports and spend |

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
| `conversion_event` | Optional event name filter (max 40, `A-Z 0-9 . _ -`) |
| `conversion_value` | Optional default conversion value in currency units |
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
language prefix, the local hour/weekday and the placement's site. Country is
resolved offline for **reporting** only; there is no country targeting key, so
the app does not pretend to offer geo targeting.

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

### Conversion tracking and attribution

A click token can be used as the attribution key for a conversion:

- `GET /conv?t=<click-token>&e=<event>&v=<value>&tx=<transaction>` records a
  browser pixel conversion.
- `GET /postback?t=…&e=…&v=…&tx=…&sig=…` records a server-to-server conversion.
  `sig` is lowercase hex HMAC-SHA256 over `t|e|v|tx`, using the advertiser's
  per-account key (shown in the dashboard).
- The click must exist, be valid and be inside
  `conversion_attribution_days` (default 30). The same `tx` inside
  `conversion_dedupe_hours` (default 24) is ignored.
- `v` is optional; without it the campaign default conversion value applies.
  Ledger entries are stored in micros.
- Admins can approve or reject a conversion; approval updates daily stats and
  country aggregates, rejection leaves them unchanged. Invalid conversions are
  kept for audit but never billed.
- Conversion tracking can be disabled in **Settings → conversion tracking**.

### Experiments, geography, payouts and email

- **Experiments** split one campaign between 2–5 creatives with weights while
  running. Metrics (CTR, clicks or conversions) are calculated per variant; an
  admin or advertiser can start, pause, finish and pick a winner (manually or
  automatically by the configured metric).
- **Geography** uses an offline IPv4 CSV database uploaded by an admin
  (`start,end,CC` or `CIDR,CC`, up to 12 MB). Reports show country, impressions,
  clicks, conversions, spend and publisher earnings; exports are CSV. Private
  addresses are not stored and unknown publics appear as `ZZ`. No raw IP is kept.
- **Payout statements** summarise a publisher's valid earnings for a date range
  and export as CSV. Only the owning publisher and admins can download them.
- **Daily email summary** is optional. Admins configure SMTP (STARTTLS, implicit
  TLS or none), recipients and a UTC hour; the sweeper sends at most one summary
  per day and records success/failure history. The SMTP password is stored in
  the encrypted data store and never returned by the API.

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
  `creative`, `day`, `country`) with CSV export.
- **Conversions** — attribution ledger, pixel/postback snippets, postback key
  and admin approval/rejection.
- **Geography** — country report and CSV export.
- **Experiments** — create A/B/C variants, start/pause/finish and choose a
  winner.
- **Payouts** — publisher statements and CSV statements for admins/publishers.
- **Users** — admin-only; create/disable users, credit advertisers, record
  publisher payouts and delete users.
- **Billing** — ledger lines plus daily spend/earnings.
- **Admin** — platform settings, geo database upload, daily summary history,
  fraud review, overview.

The dashboard uses a session bearer token held by the browser. The API is
same-origin only (`connect-src 'self'`), so the token is not exposed to other
origins.

### Deleting a user

Admins can delete an advertiser or publisher from **Users**. This is permanent:

- the account cannot delete itself;
- the **last administrator** cannot be deleted;
- the user's `stats_daily` and `events` rows are purged first (for owned
  campaigns/sites), and payout statements are removed;
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
| `GET` | `/conv?t=<token>&e=&v=&tx=` | Browser pixel conversion |
| `GET` | `/postback?t=<token>&e=&v=&tx=&sig=` | Signed server-to-server conversion |
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
| `GET` | `/api/reports/geo` | country breakdown (`from`, `to`) |
| `GET` | `/api/reports/geo/export` | country CSV download |
| `GET` | `/api/billing` | advertisers: own ledger/spend; publishers: own earnings; admin: all |

Dates are `YYYY-MM-DD` in UTC. The default range is the last 30 days; ranges
are capped at 366 days. Breakdown is capped at 500 rows ordered by impressions.
Advertisers never see publisher earnings; publishers never see advertiser spend
or campaign identities.

### Conversions, experiments, payouts and geography

| Method | Path | Role | Purpose |
|---|---|---|---|
| `GET` | `/api/conversions` | any (scoped) | Conversion ledger (`from`, `to`, `status`, `campaign_id`) |
| `GET` | `/api/conversions/postback-key` | advertiser | Per-account postback signing key |
| `POST` | `/api/conversions/:id/approve` | admin | Mark a conversion valid and update stats |
| `POST` | `/api/conversions/:id/reject` | admin | Mark a conversion invalid (never billed) |
| `GET|POST` | `/api/experiments` | advertiser/admin | List or create an experiment |
| `GET|PATCH` | `/api/experiments/:id` | advertiser/admin | Read or update name/metric/end |
| `POST` | `/api/experiments/:id/start` | advertiser/admin | Start a draft/paused experiment |
| `POST` | `/api/experiments/:id/pause` | advertiser/admin | Pause a running experiment |
| `POST` | `/api/experiments/:id/finish` | advertiser/admin | Complete an experiment |
| `POST` | `/api/experiments/:id/winner` | advertiser/admin | Pick `{creative_id}` or auto by metric |
| `GET|POST` | `/api/payouts` | admin; publisher read | List or create payout statements |
| `GET` | `/api/payouts/:id/export` | owner/admin | Download statement CSV |
| `GET` | `/api/reports/geo` | any (scoped) | Country breakdown |
| `GET` | `/api/reports/geo/export` | any (scoped) | Country CSV |
| `GET` | `/api/admin/geo` | admin | Geo database status |
| `POST` | `/api/admin/geo` | admin | Upload geo CSV (12 MiB cap, `X-Geo-Version`) |
| `GET` | `/api/admin/summary` | admin | Summary mail status and history |
| `POST` | `/api/admin/summary/send` | admin | Send the summary now |

### Admin

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/admin/overview` | Platform totals |
| `GET` | `/api/admin/audit?limit=50` | Recent administrator activity (maximum 200) |
| `GET` | `/api/admin/fraud` | Invalid-traffic summary and flagged sites |
| `GET` | `/api/admin/settings` | Current platform settings |
| `PUT` | `/api/admin/settings` | Update platform settings |

Settings keys: `rev_share_pct`, `currency`, `public_base_url`, `allow_signup`,
`trust_proxy`, `fraud_min_click_ms`, `fraud_repeat_clicks_hour`,
`fraud_ip_clicks_min`, `fraud_max_imps_visitor_day`, `retention_days`,
`conversion_tracking`, `conversion_attribution_days`,
`conversion_dedupe_hours`, `email_enabled`, `email_recipients`,
`email_hour_utc`, `smtp_host`, `smtp_port`, `smtp_security`, `smtp_username`,
`smtp_password`, `smtp_from`. The password is write-only.

`trust_proxy` is off by default. When enabled, the client IP is taken from the
**last** `X-Forwarded-For` entry, which is the value appended by the nearest
proxy; never enable it when clients can set that header directly.

---

## 10. Data, retention and backups

The ad network uses one encrypted, atomically written JSON document at
`filesDir/adnet/adnet.json` (legacy `adnet.db` is imported once, then renamed
`adnet.db.migrated`). It contains collections for:

| Collection | Purpose |
|---|---|
| `users` | Accounts, PBKDF2 password hash, role, status, prepaid balance (micros) |
| `sessions` | SHA-256 hashes of bearer tokens with expiry |
| `sites` | Domains, owner, moderation status, fraud flag/reason |
| `placements` | Format/size, public placement key, status |
| `campaigns` | Budget/pricing, spend, schedule, targeting, review status |
| `creatives` | Text/URL, image metadata, review status |
| `experiments` | Campaign, variants/weights, metric, lifecycle, winner |
| `events` | Every impression/click/conversion, validity, reason, cost and share |
| `conversions` | Attribution ledger, transaction id, value, review status |
| `stats_daily` | Aggregated daily metrics per campaign/creative/placement/site |
| `geo_daily` | Country aggregates for impressions, clicks, conversions and money |
| `fraud_log` | Daily counts per invalid-traffic reason |
| `billing` | Top-ups, adjustments, payouts and resulting balances |
| `payout_reports` | Publisher statements and status |
| `daily_summaries` | Daily email attempt, recipients, result and error |
| `audit` | Admin actions such as setup, create/delete user, approvals |
| `settings` | Platform configuration, HMAC and postback secrets |

A background sweeper runs once a minute while the ad server is running:

- deletes expired sessions and expired rate-limit buckets;
- reviews sites for fraud every 10 minutes;
- every 60 minutes, deletes events older than `retention_days` (default 30),
  deletes fraud-log rows older than 90 days, ends campaigns whose `end_at` has
  passed, finishes expired experiments, sends the daily summary when due,
  removes orphaned payout/conversion data where applicable, and removes creative
  image files with no database row (older than one hour, so in-flight uploads
  are safe).

Backups are **not** included in the `/api/v1` project-backup system. To back
up the ad network, copy `filesDir/adnet/adnet.json`, `filesDir/adnet/geo.csv`
(if present), `filesDir/adnet/creatives/` and the `adnet_prefs` preference file
from an app-private backup or rooted file manager while the ad server is
stopped. The `web/UPDATE_MANIFEST.txt` archive is source code,
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
  bounds, strict image sniffing and dimension checks, fixed report grouping
  expressions and collection names, atomic JSON writes and transactional
  rollback on failure.
- **Framing and headers.** Header values are stripped of CR/LF, the dashboard
  sends a restrictive CSP plus `X-Frame-Options: DENY`, all responses send
  `X-Content-Type-Options: nosniff`, and API responses are `no-store`.
- **Privacy.** No cookies, no raw-IP visitor storage, country derived offline
  and stored without the address, daily-rotating visitor hash, DNT/GPC honoured
  for visitor tracking, no third-party requests and no cloud component. SMTP
  credentials and postback secrets are held in the encrypted data store.
- **Conversions.** Pixels require a valid click token; postbacks are HMAC-signed
  with a per-advertiser key, transaction ids are deduplicated, and only admins
  can move a conversion between invalid and valid states.
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
| Geo database upload | 12 MiB |
| Sites per owner | 50 |
| Placements per site | 100 |
| Creatives per campaign | 30 |
| Variants per experiment | 2–5 |
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
| Conversions rejected | Missing/expired click, wrong token, duplicate `tx`, attribution window, or tracking disabled |
| Postback `403` | Signature must be HMAC-SHA256 of `t|e|v|tx` with this advertiser's dashboard key |
| Geo report all `ZZ` | Upload a geo CSV in **Settings → Offline geography database** |
| Summary email not sent | Check `email_enabled`, recipients, SMTP settings and the history error |
| Dashboard broken | JavaScript disabled, or a modified asset violating the CSP (no inline code/styles) |

---

## 13. Source files

| File | Role |
|---|---|
| `AdNetworkManager.java` | Opt-in lifecycle, port 8090, setup code, sweeper |
| `AdHttpServer.java` | HTTP/1.1 parsing/routing, limits, CSP and headers |
| `AdApi.java` | Management API, validation, account/site/campaign/creative logic |
| `AdDelivery.java` | `/sdk.js`, `/serve`, `/imp`, `/c`, `/media`, selection and billing |
| `AdConversion.java` | `/conv`, signed `/postback`, click attribution, dedupe and review |
| `AdExperimentEngine.java` | Experiment lifecycle, variant metrics and winner selection |
| `AdGeo.java` | Offline IPv4 CSV/CIDR import and country lookup |
| `AdGeoReport.java` | Country breakdown and CSV export |
| `AdPayouts.java` | Publisher statements and CSV export |
| `AdSummaryMailer.java` | SMTP client, scheduled/manual summary and history |
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
