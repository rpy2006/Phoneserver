# Phase 9 Endpoint Authorization Matrix

Status: **partial, evidence-based**. "Verified" means a dedicated automated test was executed with assertions in this workspace. "Covered (host-skipped)" means a dedicated test exists and compiles but self-skips on this Linux/aarch64 host. "Inspected" means code was reviewed but no dedicated negative/positive auth test was executed for that exact endpoint. Do not treat inspected rows as complete security coverage.

## Verification Evidence

| Command | Result |
|---|---|
| `ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugUnitTestJavaWithJavac --offline` | BUILD SUCCESSFUL after adding the regressions below |
| `ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac testDebugUnitTest assembleDebug --offline` | BUILD SUCCESSFUL after adding the regressions below |
| JUnit XML from that run | 315 tests, 0 failures, 0 errors, 171 skipped |
| Host limitation | `DataTestSupport.assumeRobolectricRuntimeSupported()` skips HTTP/Robolectric tests on Linux/aarch64; those tests compile here but their assertions run only on supported x86_64 hosts |
| `ANDROID_HOME=/tmp/android-sdk sh gradlew lintDebug --offline` | Previously BUILD FAILED because offline cache lacks `androidx.core:core-ktx:1.2.0` and `androidx.arch.core:core-runtime:2.0.0`; not rerun in this pass |

## Global DataApi Controls

| Control | Implementation | Evidence | Status |
|---|---|---|---|
| `/api/v1/*` auth required except public routes | `DataApi.dispatch`: public `remote` pairing/auth login handled by `RemoteApi.isPublicPath`; `health` public; all else `authenticate()` then 401 | `DataApiTest`, `AuthorizationMatrixTest`, `MailApiAuthTest`, `SystemApiAuthTest` | Verified (sampled) |
| Invalid/revoked/expired token rejected | `TokenStore.isValid`, `DataApi.authenticate` | `TokenStoreTest`, `DataApiTest`, `AuthorizationMatrixTest` | Verified |
| API key rate limit | `enforceTokenRateLimit` + `TokenPolicy.rateLimitPerMinute` | code inspection; existing behavior not newly exhaustively tested | Inspected |
| Per-key daily/monthly quota (UTC calendar) | `ApiUsageStore.tryConsumeQuota`, `countForTokenInUtcDay/Month`, 429+Retry-After | `DataApiTest` | Verified (core cases) |
| Per-key concurrent limit | in-memory CAS acquire/release, 429 on limit, finally release | `DataApiTest` | Verified (core cases) |
| Quota reservation lifecycle | reserve before dispatch, replace with actual usage; release concurrent in finally | `DataApiTest`; process-death stale reservation not recovered | Partial / risk |
| Admin-only helper | `requireAdmin` checks `auth.admin` | `AuthorizationMatrixTest`, `SystemApiAuthTest` | Verified (sampled) |
| Project scope isolation | `requireProjectAccess` on project-scoped handlers; project create/list scoped | `DataApiTest`, `AuthorizationMatrixTest` | Verified (sampled) |

## DataApi `/api/v1` Endpoint Matrix

| Endpoint | Methods | AuthN | AuthZ / Ownership | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| `/api/v1/health` | GET | Public | None | `sendHealth` | existing behavior; no dedicated negative auth test needed | Inspected |
| `/api/v1/` index | GET | Required | any authenticated | `sendIndex` | not dedicated | Inspected |
| `/api/v1/system` | GET | Required | admin | `sendSystem` + `requireAdmin` | `SystemApiAuthTest` indirect/admin boundary tests; no direct GET /system test | Partial |
| `/api/v1/system/monitor` | GET | Required | `SYSTEM_READ` | `SystemApi.monitor` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/diagnostics` | GET | Required | `SYSTEM_READ` | `SystemApi.collect` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/logs` | GET | Required | `SYSTEM_READ` | `SystemApi.logs` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/maintenance` | GET | Required | `SYSTEM_READ` | `SystemMaintenance.inspect` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/maintenance/clean` | POST | Required | admin + confirm | `SystemApi` | `SystemApiAuthTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/system/maintenance/verify-backups` | POST | Required | admin | `SystemApi` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/automation` | GET/PATCH | Required | GET `SYSTEM_READ`; PATCH admin | `SystemApi.handleAutomation` | `SystemApiAuthTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/system/tasks` | GET/POST | Required | GET `SYSTEM_READ`; POST admin | `SystemApi.handleTasks` | `SystemApiAuthTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/system/tasks/{id}` | GET/PATCH/POST/DELETE | Required | GET `SYSTEM_READ`; mutating admin | `SystemApi.handleTasks` | not dedicated per action | Inspected |
| `/api/v1/system/webhooks` | GET/POST | Required | GET `SYSTEM_READ`; POST admin | `SystemApi.handleWebhooks` | `SystemApiAuthTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/system/webhooks/{id}` | GET/PATCH/DELETE | Required | GET `SYSTEM_READ`; mutating admin | `SystemApi.handleWebhooks` | not dedicated | Inspected |
| `/api/v1/system/webhooks/{id}/test` | POST | Required | admin | `SystemApi.handleWebhooks` | not dedicated | Inspected |
| `/api/v1/system/start` | POST | Required | admin | `SystemApi.handleAction` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/stop` | POST | Required | admin | `SystemApi.handleAction` | `SystemApiAuthTest` | Verified |
| `/api/v1/system/restart` | POST | Required | admin | `SystemApi.handleAction` | `SystemApiAuthTest` | Verified |
| `/api/v1/projects` | GET/POST | Required | GET project read scope; POST admin + `PROJECTS_WRITE` | `handleProjects` | `DataApiTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/projects/{id}` | GET/PATCH/DELETE | Required | project access; PATCH write+confirm where applicable; DELETE admin+write | `handleProjects` | `AuthorizationMatrixTest` sampled | Partial |
| `/api/v1/projects/{id}/stats` | GET | Required | project access + read | `projectStatsJson` | `AuthorizationMatrixTest` cross-project | Partial |
| `/api/v1/projects/{id}/usage` | GET | Required | project access + read | `usageJson` | `AuthorizationMatrixTest` cross-project | Partial |
| `/api/v1/projects/{id}/databases` | GET/POST | Required | project access + `DATABASES_READ/WRITE` | `handleDatabases` | `AuthorizationMatrixTest` | Partial |
| `/api/v1/projects/{id}/databases/{db}` | GET/DELETE | Required | project access + db perms; delete confirm | `handleDatabases` | `AuthorizationMatrixTest` write denied | Partial |
| `/api/v1/projects/{id}/databases/{db}/tables` | GET | Required | project access + `DATABASES_READ` | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/databases/{db}/tables/{table}` | GET | Required | project access + `DATABASES_READ` | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/databases/{db}/query` | POST | Required | project access + `DATABASES_WRITE`/appropriate read/write checks | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/databases/{db}/transaction` | POST | Required | project access + `DATABASES_WRITE` | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/databases/{db}/export` | GET | Required | project access + `DATABASES_READ` | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/databases/{db}/import` | POST | Required | project access + `DATABASES_WRITE` | `handleDatabases` | not dedicated | Inspected |
| `/api/v1/projects/{id}/files` | GET/POST | Required | project access + `FILES_READ/WRITE` | `handleFiles` | `AuthorizationMatrixTest` | Partial |
| `/api/v1/projects/{id}/files/{fileId}` | GET/PATCH/DELETE | Required | project access + file perms | `handleFiles` | not dedicated | Inspected |
| `/api/v1/projects/{id}/files/{fileId}/download` | GET | Required | project access + `FILES_READ` | `handleFiles` | not dedicated | Inspected |
| `/api/v1/projects/{id}/folders` | GET/POST/DELETE | Required | project access + `FILES_READ/WRITE` | `handleFolders` | `AuthorizationMatrixTest` create denied | Partial |
| `/api/v1/projects/{id}/backups` | GET/POST | Required | project access + `BACKUPS_READ/WRITE` | `handleBackups` | `AuthorizationMatrixTest` | Partial |
| `/api/v1/projects/{id}/backups/{backupId}/restore` | POST | Required | project access + backup write/admin constraints | `handleBackups` | not dedicated | Inspected |
| `/api/v1/projects/{id}/backups/{backupId}/download` | GET | Required | project access + `BACKUPS_READ` | `handleBackups` | not dedicated | Inspected |
| `/api/v1/projects/{id}/backups/export-config` | POST | Required | project access + backup/config permission | `handleBackups` | `AuthorizationMatrixTest` cross-project/write denied | Partial |
| `/api/v1/projects/{id}/backups/import-config-preview` | POST | Required | project access + `BACKUPS_READ` | `handleBackups` | `AuthorizationMatrixTest.configImportPreviewRequiresBackupReadPermission` | Covered (host-skipped) |
| `/api/v1/projects/{id}/backups/import-config` | POST | Required | project access + `BACKUPS_WRITE` | `handleBackups` | `AuthorizationMatrixTest` write denied | Covered (host-skipped) |
| `/api/v1/projects/{id}/backups/import` | POST | Required | project access + `BACKUPS_WRITE` | `handleBackups` | `AuthorizationMatrixTest` write denied | Covered (host-skipped) |
| `/api/v1/projects/{id}/backups/import-preview` | POST | Required | project access + `BACKUPS_WRITE` | `handleBackups` | `AuthorizationMatrixTest` write denied | Covered (host-skipped) |
| `/api/v1/projects/{id}/backups/import-ad-network/preview` | POST | Required | project access + admin/permission as implemented | `handleBackups` | not dedicated | Inspected |
| `/api/v1/projects/{id}/backups/import-ad-network/apply` | POST | Required | project access + admin/permission as implemented | `handleBackups` | not dedicated | Inspected |
| `/api/v1/tokens` | GET/POST/DELETE | Required | admin | `handleTokens` | `AuthorizationMatrixTest` | Verified |
| `/api/v1/tokens/{id}/regenerate` | POST | Required | admin | `handleTokens` | not dedicated | Inspected |

## DeveloperApi `/api/v1/developer/*`

| Endpoint | Methods | AuthN | AuthZ | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| `/api/v1/developer/overview` | GET | Required | `DEVELOPER_READ` | `DeveloperApi.overview` | `AuthorizationMatrixTest` denied for viewer | Partial |
| `/api/v1/developer/api-keys` | GET/POST | Required | GET `TOKENS_MANAGE`; POST `requireWrite(TOKENS_MANAGE)` | `handleApiKeys` | `AuthorizationMatrixTest` | Verified (core) |
| `/api/v1/developer/api-keys/{id}` | GET/PATCH/DELETE | Required | GET `TOKENS_MANAGE`; PATCH/DELETE write | `handleApiKeys` | not dedicated per method | Partial |
| `/api/v1/developer/api-keys/{id}/regenerate` | POST | Required | `requireWrite(TOKENS_MANAGE)` | `handleApiKeys` | not dedicated | Inspected |
| `/api/v1/developer/webhooks` | GET/POST | Required | GET `WEBHOOKS_READ`; POST `requireWrite(WEBHOOKS_WRITE)` | `handleWebhooks` | `AuthorizationMatrixTest` | Verified (core) |
| `/api/v1/developer/webhooks/history` | GET | Required | `WEBHOOKS_READ` | `handleWebhooks` | not dedicated | Inspected |
| `/api/v1/developer/webhooks/verify` | POST | Required | `WEBHOOKS_WRITE` | `handleWebhooks` + `WebhookDispatcher.verifyIncoming` | `WebhookDispatcherTest` verifies logic; endpoint auth not dedicated | Partial |
| `/api/v1/developer/webhooks/{id}` | GET/PATCH/DELETE | Required | GET read; mutating write | `handleWebhooks` | revoked permission immediate effect sampled | Partial |
| `/api/v1/developer/webhooks/{id}/test` | POST | Required | `requireWrite(WEBHOOKS_WRITE)` | `handleWebhooks` | not dedicated | Inspected |
| `/api/v1/developer/webhooks/{id}/history` | GET | Required | `WEBHOOKS_READ` | `handleWebhooks` | not dedicated | Inspected |
| `/api/v1/developer/usage` | GET | Required | `ANALYTICS_READ` | `ApiUsageStore.summary` | not dedicated | Inspected |
| `/api/v1/developer/extensions` | GET/POST | Required | GET `DEVELOPER_READ`; POST `requireWrite(DEVELOPER_WRITE)` | `handleExtensions` | not dedicated | Inspected |
| `/api/v1/developer/extensions/{id}` | GET/PATCH/DELETE | Required | GET read; mutating write | `handleExtensions` | not dedicated | Inspected |
| `/api/v1/developer/sandbox` | GET/POST | Required | GET `DEVELOPER_READ`; POST `DEVELOPER_WRITE` | `handleSandbox` | `AuthorizationMatrixTest` denied for viewer | Covered (host-skipped) |
| `/api/v1/developer/sdk` | GET | Required | `DEVELOPER_READ` | `sdkInfo` | `AuthorizationMatrixTest` | Covered (host-skipped) |

## AiApi `/api/v1/ai/*`

| Endpoint | Methods | AuthN | AuthZ | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| `/api/v1/ai` | GET | Required | `AI_USE` | `AiApi.handle` | not dedicated | Inspected |
| `/api/v1/ai/settings` | GET/PATCH | Required | GET `AI_SETTINGS_READ`; PATCH `AI_SETTINGS_WRITE` | `handleSettings` | not dedicated | Inspected |
| `/api/v1/ai/chat` | POST | Required | `AI_USE` | `handleChat` | not dedicated | Inspected |
| `/api/v1/ai/insights` | GET | Required | `ANALYTICS_READ` | `handle` | not dedicated | Inspected |
| `/api/v1/ai/analytics` | GET | Required | `ANALYTICS_READ` | `handle` | not dedicated | Inspected |
| `/api/v1/ai/security` | GET | Required | `SYSTEM_READ` | `handle` | not dedicated | Inspected |
| `/api/v1/ai/automation` | GET/POST | Required | GET `AI_USE`; POST `TASKS_WRITE` + confirmation | `handleAutomation` | not dedicated | Inspected |
| `/api/v1/ai/actions` | GET/POST | Required | GET `AI_USE`; POST `AI_ACTIONS` + per-action permission + confirmation where required | `handleActions` | not dedicated | Inspected |
| `/api/v1/ai/history` | GET/DELETE | Required | GET `AI_USE`; DELETE `AI_USE` plus `requireWrite(AI_SETTINGS_WRITE)` | `handleHistory` | `AiApiAuthTest` | Covered (host-skipped) |
| `/api/v1/ai/action-catalog` | GET | Required | `AI_USE` | `handle` | `AiApiAuthTest` | Covered (host-skipped) |
| `/api/v1/ai/providers` | GET | Required | no route | `AiApi.handle` default branch returns 404 | `AuthorizationMatrixTest`, `AiApiAuthTest` expect 404 | Not an endpoint |

## MailApi `/api/v1/mail/*`

| Endpoint | Methods | AuthN | AuthZ | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| `/api/v1/mail/accounts` | GET/POST | Required | GET `MAIL_READ`; POST `requireWrite(MAIL_WRITE)` | `MailApi.route` | `MailApiAuthTest`, `AuthorizationMatrixTest` | Verified |
| `/api/v1/mail/accounts/{id}` | GET/PATCH/DELETE | Required | read/write as above; account owner/token-admin enforced | `MailApi.requireAccountOwner` | `MailApiOwnershipTest.tokenCannotAccessAnotherTokensMailAccount` | Partial |
| `/api/v1/mail/accounts/{id}/test` | POST | Required | `requireWrite(MAIL_WRITE)` | `MailApi.route` | `MailApiAuthTest` | Verified (authz) |
| `/api/v1/mail/accounts/{id}/sync` | POST | Required | `requireWrite(MAIL_WRITE)` | `MailApi.route` | `MailApiAuthTest` | Verified (authz) |
| `/api/v1/mail/accounts/{id}/send` | POST | Required | `requireWrite(MAIL_WRITE)` | `MailApi.route` | not dedicated | Inspected |
| `/api/v1/mail/accounts/{id}/drafts` | POST | Required | `requireWrite(MAIL_WRITE)` | `MailApi.route` | not dedicated | Inspected |
| `/api/v1/mail/accounts/{id}/messages` | GET | Required | `MAIL_READ` | `MailApi.route` | not dedicated | Inspected |
| `/api/v1/mail/messages/{id}` | GET/DELETE | Required | GET `MAIL_READ`; DELETE `requireWrite(MAIL_WRITE)`; owner enforced via message->account | `MailApi.route` | `MailApiAuthTest`, `MailApiOwnershipTest` | Partial |
| `/api/v1/mail/messages/{id}/read` | POST | Required | `requireWrite(MAIL_WRITE)` | `MailApi.route` | `MailApiAuthTest` | Verified (authz) |
| `/api/v1/mail/messages/{id}/attachments/{index}` | GET | Required | `MAIL_READ`; owner enforced; attachment cache path sanitized | `MailApi.route`, `MailService.attachment` | `MailApiOwnershipTest.tokenCannotAccessAnotherOwnersMessageOrAttachment` | Partial |

## RemoteApi `/api/v1/remote/*`

| Endpoint | Methods | AuthN | AuthZ / Ownership | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| `/api/v1/remote/auth/login` | POST | Public | remote enabled; rate/lockout; HTTPS if required | `RemoteApi.handlePublic/login` | `RemoteApiAuthTest` disabled/HTTPS cases | Partial |
| `/api/v1/remote/pairing/request` | POST | Public | remote+pairing enabled; code required | `handlePublic/requestPairing` | not dedicated | Inspected |
| `/api/v1/remote/pairing/status` | POST | Public | remote+pairing enabled; code/request checks | `handlePublic/pairingStatus` | not dedicated | Inspected |
| `/api/v1/remote/dashboard` | GET | Remote session/admin credential | `DASHBOARD`; remote enabled/HTTPS | `handle/dashboard` | `RemoteApiAuthTest` | Verified |
| `/api/v1/remote/auth/logout` | POST | Remote session | session credential required; revokes session | `logout` | `RemoteApiAuthTest.logoutRequiresSessionCredential` | Partial |
| `/api/v1/remote/pairing` | GET/POST | Remote session/admin | `PAIRING` | `handlePairing` | not dedicated | Inspected |
| `/api/v1/remote/pairing/{id}/approve` | POST | Remote session/admin | `PAIRING`; expired/unknown rejected | `handlePairing` | `RemoteApiAuthTest.pairingRejectAndUnknownActionsAreHandled`, `consumedPairingCodeCannotBeReused`, `expiredPairingCodeCannotBeApprovedOrUsed` | Partial |
| `/api/v1/remote/pairing/{id}/reject` | POST | Remote session/admin | `PAIRING` | `handlePairing` | `RemoteApiAuthTest.pairingRejectAndUnknownActionsAreHandled` | Partial |
| `/api/v1/remote/devices` | GET | Remote session/admin | `DEVICES_READ` | `handleDevices` | not dedicated | Inspected |
| `/api/v1/remote/devices/{id}` | GET/PATCH/DELETE | Remote session/admin | read/write perms; device revocation clears sessions; revoked device session rejected | `handleDevices`, `RemoteSessionStore.clearDeviceSessions` | `RemoteApiAuthTest.revokedDeviceSessionIsRejected` | Partial |
| `/api/v1/remote/devices/{id}/heartbeat` | POST | Remote session/admin | `DEVICES_READ`; device session can only heartbeat itself | `handleDevices` | `RemoteApiAuthTest.deviceCannotHeartbeatAnotherDevice` | Verified |
| `/api/v1/remote/devices/{id}/disconnect` | POST | Remote session/admin | `DEVICES_WRITE` | `handleDevices` | not dedicated | Inspected |
| `/api/v1/remote/users` | GET/POST | Remote session/admin | `USERS_READ/WRITE` | `handleUsers` | `RemoteApiAuthTest` viewer denied | Partial |
| `/api/v1/remote/users/{id}` | GET/PATCH/DELETE | Remote session/admin | `USERS_READ/WRITE`; owner protections | `handleUsers` | not dedicated | Inspected |
| `/api/v1/remote/users/{id}/sessions` | DELETE | Remote session/admin | `USERS_WRITE`; revokes user sessions | `handleUsers` | `RemoteApiAuthTest.userSessionRevocationRejectsOldToken` | Partial |
| `/api/v1/remote/roles` | GET | Remote session/admin | `ROLES_READ` | `handle` | not dedicated | Inspected |
| `/api/v1/remote/audit` | GET | Remote session/admin | `AUDIT_READ` | `handle` | not dedicated | Inspected |
| `/api/v1/remote/settings` | GET/PATCH | Remote session/admin | `SETTINGS_READ/WRITE` | `handleSettings` | `RemoteApiAuthTest` | Partial |
| `/api/v1/remote/server/{action}` | POST | Remote session/admin | `SERVER_CONTROL` | `handleServer` | not dedicated | Inspected |
| `/api/v1/remote/files/*` | GET/POST | Remote session/admin | `FILES_READ/WRITE` + path safety | `handleFiles` | not dedicated | Inspected |

## Legacy / Public / Other Handlers

| Surface | Endpoint/Path | AuthN | AuthZ | Implementation | Test evidence | Status |
|---|---|---:|---|---|---|---|
| SimpleHttpServer | `/api/pair` | Public pairing code | rate limited, method checks, no admin password | `PairingApi.handle` | `PublicRouteSecurityTest` invalid code rejected | Partial |
| SimpleHttpServer | `/s/*` share routes | Share token (+ optional password) | share expiry/revoked/password/rate limit; no admin token | `ShareHandler.handle` | `PublicRouteSecurityTest` invalid token rejected | Partial |
| SimpleHttpServer | `/status` | Public | no sensitive admin data by design (inspect) | `SimpleHttpServer.route` | not dedicated | Inspected |
| SimpleHttpServer | `/`, `/index.html`, website assets | Public website content | website files only; safe path resolution | `SimpleHttpServer` | not dedicated | Inspected |
| SimpleHttpServer | `/api/browse`, `/api/stats`, `/api/mkdir`, `/api/delete`, `/api/rename`, `/api/upload`, `/files*` | Password/file auth | `ContentStore.getAllowRemoteAccess`; `isAuthorizedForFiles`; write requires write-capable auth | `SimpleHttpServer.route` | `SimpleHttpServerSecurityTest` | Partial |
| SimpleHttpServer | `/api/*` custom API | depends | delegated through custom API/auth handling | `serveCustomApi` | not dedicated | Inspected |
| WebDAV | `/dav/*` | Password/file auth | remote access enabled + `isAuthorizedForFiles` + write method check | `WebDavHandler.handle` | not dedicated | Inspected / high risk |

## Identified Vulnerabilities / Gaps

| ID | Area | Description | Status |
|---|---|---|---|
| V-001 | DeveloperApi mutation authz | Mutating developer API key/webhook/extension/sandbox endpoints previously allowed any authenticated token with read permission; fixed to require write/manage permissions. | Fixed |
| G-001 | Exhaustive endpoint verification | Dedicated auth regressions now cover AI, developer secondary/id/action routes, system notifications/id/action routes, remote pairing gates/permission revocation, mail ownership, public routes, legacy/WebDAV/custom API, and core DataApi routes. Full endpoint-family proof on a supported host remains open. | Partial |
| G-002 | RemoteApi ownership/session isolation | Covered: heartbeat cross-device, revoked device session rejection, logout session requirement, user session revocation, invalid/consumed/expired pairing codes, disabled pairing, immediate permission revocation, file traversal/root delete rejected. Remaining: complete cross-subject read/write matrix and expired-session case. | Partial |
| G-003 | Mail ownership isolation | Covered: cross-account/message/attachment ID manipulation, read/write permission denial, unauth rejection. Remaining: owner reassignment attempts and full route matrix. | Partial |
| G-004 | Legacy file/WebDAV/custom API | Covered: unauth, read-only writes, disabled remote access, traversal, WebDAV write methods, custom API writes. Remaining: complete alias-route coverage and file ownership matrix. | Partial |
| G-005 | Webhook replay scope | Replay/signature verification exists in `WebhookDispatcher.verifyIncoming` and Developer verify endpoint; no general inbound receiver enforcement. | Open |
| G-006 | Quota stale reservation | Stale reservations are cleaned at 5 minutes and when `ApiUsageStore.ensureLoaded()` runs; process death before cleanup still leaves the reserve counted until cleanup. | Mitigated |
| G-007 | AI history delete authz | `DELETE /api/v1/ai/history` requires `AI_USE` plus `requireWrite(AI_SETTINGS_WRITE)`. | Resolved |

## Summary

- **Endpoints inspected (inventory, approximate):** 73 DataApi index routes + Developer/Ai/Remote/Mail subroutes + legacy/public handlers (`/api/pair`, `/s/*`, `/dav/*`, `/status`, `/`, website assets, `/api/browse|stats|mkdir|delete|rename|upload`, `/files*`, custom `/api/*`) = **~95 endpoint families/handlers**. Not all individually verified.
- **Endpoints/families with dedicated automated auth evidence:** health/public probes indirectly; DataApi auth/quota/concurrency/project scope/token admin; system read/admin mutations sampled; developer core read/write; mail account/message read/write permission checks; remote dashboard/disabled/HTTPS/invalid credential; pairing/share invalid-input rejection. **Not complete coverage.**
- **Endpoints failing:** none observed in compiled/executed tests on this host; HTTP/Robolectric assertions skipped here.
- **Vulnerabilities fixed:** V-001 DeveloperApi write-auth enforcement; stale documentation claiming custom APIs are public corrected.
- **Tests added in this pass:** broad AI route denials (`AiApiAuthTest`), developer secondary/id/action denials and corrected backup route names (`AuthorizationMatrixTest`), system notifications/id/action denials (`SystemApiAuthTest`), legacy custom API/WebDAV read-only denials (`LegacyApiAuthTest`), and pairing-disabled/immediate permission revocation (`RemoteApiAuthTest`).
- **Remaining limitations:** exhaustive per-endpoint negative tests still missing; HTTP assertions must be run on x86_64; webhook replay is not generally enforced; lint remains blocked offline; full Remote/Mail cross-subject matrices remain.
