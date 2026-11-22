# Shell and access

The application shell is the chrome every area renders inside: header, the optional guest and update
banners, `<main>`, footer, and the global toast host (`ui-apps/admin/src/app/core/main-shell/layout/layout.html`). It owns
who may enter which area, which desk (domain) the session is sitting at, which language the UI
speaks, and where the help buttons point. The shell lives entirely in `ui-apps/admin/src/app/core/`; the landing
page is a feature (`ui-apps/admin/src/app/features/home/`).

## Areas and who may enter them

| Area                 | Path        | Required role                         | Notes                                                                                                                                                                                                          |
| -------------------- | ----------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Home / landing       | `/`         | none                                  | Public. Also the fallback for unknown URLs.                                                                                                                                                                    |
| Messages             | `/messages` | none                                  | Fully public, including all five views — see [messages.md](./messages.md).                                                                                                                                     |
| Editor               | `/editor`   | `user`, `editor`, `admin`, `sysadmin` | A plain `user` may open and proof-read unpublished messages and comment; every mutating action is gated on `editor` and above — see [editor.md](./editor.md). The nav item is stricter than the route (below). |
| Firing Area Schedule | `/schedule` | `editor`, `admin`, `sysadmin`         | The nav item additionally requires the session domain's `firingSchedule` capability; the route itself does not — see [schedule.md](./schedule.md).                                                             |
| Admin                | `/admin`    | `admin`, `sysadmin`                   | Sections have their own gates on top — see [admin.md](./admin.md).                                                                                                                                             |
| Sysadmin             | `/sysadmin` | `sysadmin`                            | A separate shell and route tree from Admin — see [sysadmin.md](./sysadmin.md).                                                                                                                                 |

Role lists live in one place, `ui-apps/admin/src/app/core/auth/area-roles.ts`, and are consumed both by the route
guard and by `MenuService`, so a nav item never appears for a route the guard would refuse.
There is **no implicit hierarchy**: the guard asks `some(role)`, so `admin` and `sysadmin` are
spelled out in every list that admits them.

**The menu is deliberately stricter than the guard for Editor.** The route admits
`EDITOR_VIEW_ROLES` (`user` included), while `MenuService.canSeeEditor` uses `EDITOR_AREA_ROLES`
(`editor`, `admin`, `sysadmin`) — a plain `user` therefore gets no Editor nav item and reaches the
read-only proof-read/comment view only by deep link or by following a message row into the editor.
Anyone widening editor read access has to change `MenuService` as well as `area-roles.ts`;
changing the role list alone leaves the area unreachable from the header.

Routes opt in declaratively:

```ts
{ path: 'admin', loadChildren: …, canMatch: [roleGuard], data: { rolesRequired: ADMIN_AREA_ROLES } }
```

`roleGuard` (`ui-apps/admin/src/app/core/auth/guards/role.guard.ts`) has two distinct denial paths, and both are
covered by end-to-end specs (`ui-apps/admin/e2e/role-split.spec.ts`, `ui-apps/admin/e2e/route-guards.spec.ts`):

- **authenticated but unauthorized** — an access-denied toast and a redirect to `/`;
- **anonymous** — a Keycloak login redirect with the current URL as the return target.

It is a `CanMatch` guard, not `CanActivate`: a refused route falls through to the `**` wildcard
(home) instead of raising. Every area is lazily loaded, home included, so the OpenLayers stack the
landing map pulls in stays out of the initial bundle.

The development and local builds additionally expose `/dev/geo-editor`, a standalone sandbox for the
geometry editor; the route is not registered when `environment.production` is `true`, as it is in the
production and the test builds (its lazy chunk is still emitted, and nothing can reach it).

**Frontend role gating is an affordance, never a security control.** The backend re-checks roles on
every call and narrows results by the domain header regardless of what the UI offers.

## Roles

Niord has four application roles: `user`, `editor`, `admin`, `sysadmin`. The backend grants them
from two Keycloak **client (resource) roles** only:

- a role on the **base client** (`niord-client-master` by default) — it applies in every domain;
- a role on the per-domain Keycloak client **whose client id is the Niord `domainId`** — it applies
  only while the `NiordDomain` header names that domain, and only when the domain is stored and
  active.

A Keycloak **realm role** grants nothing, even one named like a Niord role: a realm role is shared
by every client of the realm. The backend also accepts only tokens issued to the configured clients
(`niord-web` and the base client by default, `niord.oidc.allowed-clients`), so a token from any
other client of the realm is answered 401.

`AuthFacade.hasRole(role)` answers as the backend does on a request in the session domain: the
client role on the session domain's client or on the base client (`BASE_CLIENT_ID`,
`niord-client-master`, the backend's default; a deployment that sets `niord.oidc.base-client` to
another client is not read). Like the backend, it reads no realm role. The same person can be an
editor at one desk and read-only at another, and the answer flips the moment the session domain
changes; a role on the base client holds at every desk, also with no session domain.

The non-obvious helpers in `ui-apps/admin/src/app/core/auth/facades/auth.facade.ts`:

| Helper                  | Rule                                                                                                                                                                                                                                                                                                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `hasRole('user')`       | A synonym for `isAuthenticated()` — the app assumes every signed-in account holds `user`; the backend still requires it as a client role. Routes and the header menu read it.                                                                                                                                                                                       |
| `hasDomainUserRole`     | The backend's `user` client role in the session domain, on its client or on the base client. Gates what a `user`-role endpoint serves: the Status filter's non-public statuses, and the Actions menu's and the message cog's selection print and export, report, tag actions and tag manager, the tag picker's Manage link, and the User filter's directory search. |
| `hasRolesFor(domainId)` | Does the user hold **any** client role on that domain? Deliberately no realm-wide fast path. Decides which domains count as editable and whether the switcher draws a pencil or an eye.                                                                                                                                                                             |
| `adminIn(domainId)`     | Administers a **named other** domain (the `admin` or `sysadmin` client role on that domain; no realm role, and no role on the base client, which the backend applies to the header's domain only). Used where an action moves work to a desk the user is not sitting at.                                                                                            |
| `canManageUsers`        | A verbatim `manage-users` client-role check on Keycloak's own `realm-management` client — no realm fallback, no session-domain fallback. Gates the admin Users section.                                                                                                                                                                                             |

`hasAnyRole(roles)` is the single helper behind both the route guard and the menu.

`AuthFacade` injects `DomainService`, not `DomainFacade` — `DomainFacade` reads back from
`AuthFacade`, and injecting it here would close a cycle.

## Session domain

Niord is multi-tenant by domain. The **session domain** is chosen in the header, persisted per
browser, sent on every backend request as the `NiordDomain` header (including anonymous requests),
and is the basis for role checks, default map view, default message sort and the capability flags
(`atons`, `templates`, `publish`, `firingSchedule`). It also owns the clock: every displayed instant
is formatted in the domain's `timeZone`, and a date control with no zone available disables itself
rather than guess — see [coding-standards.md](../coding-standards.md) → _Dates and time zones_. The
**search domain** is a separate, per-search override owned by the Messages filter; see
[messages.md](./messages.md).

Bootstrap picks the initial session domain in strict order (`DomainService.bootstrap`):

1. a `?domain=<id>` query parameter on the first load that matches a known domain (the Messages
   Domain filter is `searchDomain` on the address, so it never selects the session domain);
2. the id persisted in `localStorage`;
3. the first domain in the catalogue.

The catalogue fetch is capped at 5 s and a failure or timeout leaves the catalogue empty — a domains
outage must not block boot. The shell then shows a notice under the header on every page, "Could
not load domains" with _Retry_ (`DomainBootstrapBanner`, `core.domain.bootstrap.errorTitle`), since
every list is empty for that reason alone. Retry reloads the app, as a desk change does, and the
persisted id is kept through the failure, so a reload that reads the catalogue lands on the same
desk. With no domain resolved the switcher hides and no
domain header is sent. The two kinds of
domain-derived gate then behave oppositely: the message-support checks (`supportsMainType`,
`supportsType`) fall open, so everything is offered, while every capability flag reads `false`, so
capability-gated affordances disappear — the Schedule nav item among them. A domains outage
therefore boots a usable app with a thinner header, not a broken one.

**Switching the session domain reloads the application** onto the same address, query string
included (`DomainFacade.selectSessionDomain`). This is deliberate: every domain-scoped slice — filter
state, selection, paged results, per-domain catalogues, and any unsaved editor draft — has to start
clean, and nothing in memory survives to be re-derived. Losing the draft is a tenant change, not a
bug: the message being written belongs to the desk that was left. What the reload exists to clear
lives in memory, never in the address: a screen that carries the reader's place in the URL — an archive
filter, a saved message search — comes back up where the reader was, under the new desk.

A page **addressed inside one desk** says so on its route, `data: { onDeskChange: '<address>' }`, and the
reload goes there instead — read off the deepest activated route, a child's declaration winning over its
parent's, so nothing in `core/` knows which pages those are. The publication detail addresses use it to
leave for their section's front page, an address whose record belongs to the desk being left meaning
nothing under the desk just chosen. A caller may pass `{ keepAddress: true }` to override any
declaration, which is how the foreign-domain refusal's "switch to the owning desk" stays on the page it
is offering to unlock.

Domain names are localized by the backend, so the catalogue is re-fetched on a language change —
inside `untracked()`, for the reason given in
[angular-conventions.md](../angular-conventions.md) → _Effects_.

## Authentication

Keycloak (`keycloak-js`, kept on the same major as the deployed Keycloak server, 26.x) is
configured with exactly three
values per deployment — `url`, `realm`, `clientId` (defaults `niord` / `niord-web`) — under
`keycloak` in the environment file. Those three keys are never overridable from the runtime settings
overlay; see [platform.md](../platform.md). A deployment may replace them before Angular boots:
`main.ts` fetches `GET {apiBaseUrl}/settings/keycloak` and `createRuntimeConfig` writes a non-empty
`url`, `realm` or `clientId` from the answer over the build's. That is how a deployment on the
cluster's shared Keycloak, with a realm per country (`niord-dk`, `niord-gl`), signs in at
`https://login.…/auth` while its build still says `/auth`. A backend without the endpoint (a 404)
leaves the build's values in place at once. Any other failure (a 5xx, a network error, no answer
within two seconds) is retried, three attempts one second apart, about eight seconds in all; after
the last the build's values stand, with a console warning (`fetchKeycloakConfig` in
`core/config/web-settings.ts`).

The client is built by a factory behind the `KEYCLOAK` injection token, so every consumer injects it
and unit specs and end-to-end fixtures can substitute a pre-authenticated stub. The stub path is
gated on `!production`, so a production build cannot be subverted by seeding the window key.

### Anonymous browsing is a product feature

The app initializes with `onLoad: 'check-sso'` — never `login-required`. An unauthenticated visitor
gets the public message browser read-only, with no bearer token attached and no forced login.
**Making login mandatory is a regression.**

Silent SSO needs one static asset at the app origin, `ui-apps/admin/public/silent-check-sso.html`, which loads
`ui-apps/admin/public/silent-check-sso.js` (a single `parent.postMessage` line). The script is a separate file, not
inline, so the Content-Security-Policy can stay at `script-src 'self'`.

### Degraded authentication — the rules that must not regress

| Situation                                       | Behaviour                                                                                                                                                                                |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `keycloak.init()` fails or exceeds the 8 s cap  | Continue as an anonymous guest, `authFallback = 'unreachable'`, log to console. A late-settling init is left unawaited so it cannot clobber the guest session.                           |
| Token refresh fails, or the backend answers 401 | `sessionExpired()` — drop to anonymous **locally**, `authFallback = 'expired'`. Not for a background read ([platform.md → Token refresh and 401](../platform.md#token-refresh-and-401)). |
| Either of the above                             | **Never** `window.location.reload()` and **never** `keycloak.logout()`.                                                                                                                  |

Both redirect-based recoveries loop: reloading against a slow or unstable identity provider produces
blank reload cycles (the third-party-cookie probe alone can block ~10 s), and a full logout redirect
triggered by an on-load 401 bounces through the provider and re-bootstraps into the same failure.
The API is public, so dropping to guest keeps the app usable and the user re-authenticates when they
choose. `ui-apps/admin/e2e/auth-fallback.spec.ts` blocks the identity provider outright and asserts exactly this.

The shell renders one warning strip between header and `<main>` whenever `authFallback()` is set,
with a Login button and `role="status"`. Two messages distinguish the causes,
`shared.auth.sessionExpired` and `shared.auth.loginUnavailable`. A clean not-logged-in state shows
no banner.

### Tokens and identity

Before every request to the Niord backend, `authTokenInterceptor` refreshes the access token if it
would expire within 60 s, then attaches `Authorization: Bearer`. Anonymous sessions and non-Niord
URLs short-circuit before any of that. A background read is the exception: it is never refreshed
for and never ends the session
([platform.md → Token refresh and 401](../platform.md#token-refresh-and-401)).

Role checks read an immutable **session snapshot** (`identity`, `realmRoles`, `resourceRoles`) rather
than the live token, so the snapshot is re-captured after every successful refresh — otherwise a
mid-session role change would stay invisible until a full reload.

Login, logout and _Edit account_ are all full-page redirects to Keycloak; the app never touches
credentials, and all password/profile editing happens in Keycloak's own hosted account pages.

Downloads that leave the app (PDF, exports) cannot carry a bearer header, so they use a short-lived
single-use ticket instead — `AuthTicketService` / `TicketedDownloadService`, with an explicit
no-ticket mode for public endpoints. See [backend-api-notes.md](../backend-api-notes.md).

`<app-user-field>` is the shared control for picking a person from the Keycloak directory. Its value
is the **username** while the chip shows a display name; since there is no get-by-username endpoint,
an externally written username (a URL, a restored named filter) shows as a stub immediately and is
best-effort upgraded by searching for it and taking an exact match. The field disables itself when
unauthenticated and offers a "me" shortcut.

## Header

Left to right: brand logo (a link to `/`), nav links, then the action triggers.

- **Nav links** come from `MenuService`, which reads the same role lists in `area-roles.ts` that gate
  the routes — never more permissive than the guard, and for Editor deliberately stricter (see
  _Areas and who may enter them_). Messages is always present; Editor, Schedule, Admin and Sysadmin
  appear per role, and Schedule additionally per domain capability.
- **Help** — a link to the manual page for the deepest active route section (see below).
- **Domain switcher** — hidden when the catalogue is empty. The trigger shows the session domain's
  name and a colour disc filled with the domain colour, whose glyph flips black or white by
  perceived brightness. Each row carries a pencil (the user holds a role there) or an eye
  (view-only); the selected row is bold with a tinted background.
- **Account menu** — anonymous: a bare Login action. Authenticated: the display name, with _Edit
  `<name>`_ (Keycloak account management) and _Logout_.
- **Language menu** — one row per configured language, flag plus label, in the configured order;
  selecting one loads the bundle before switching, then persists the choice. The language list and
  its order are country configuration, not a hard-coded pair — see
  [country-deployments.md](../country-deployments.md).
- **Deployment-mode badge** — a red badge under the logo on any non-production deployment, so nobody
  mistakes test data for production. The runtime `mode` setting decides (any trimmed value other
  than `production` shows the badge, labelled with the mode uppercased); a blank or absent mode falls
  back to the build-time `production` flag and a translated default label. Read once at
  construction — like the rest of the settings overlay, a change needs a reload.

Below `lg` the nav collapses into a burger that opens **one flat list** — nav, help, account and
language entries, separated, each keeping its own icon so the section is identifiable without
submenus. The domain switcher is deliberately _not_ in that list: it keeps its own trigger beside the
burger, because it is the one piece of chrome that says which data set is being edited.

## Footer

The footer is the country's branding and nothing else: a copyright link (`footer.copyright`,
`footer.link`), a centre logo (`main.footer.links.center.*`, its image path included), a link to the
administration app's own accessibility statement (`footer.accessibility`, `footer.accessibilityLink`,
shown only with both set) and one button per legal document (`footer.disclaimer`, `footer.cookies`),
each shown only when the country's
branding file `assets/branding/<lang>.json` sets its key. `BrandingService` records which keys the
branding layer set; the footer reads that, not the merged translation, because the backend's web
dictionary carries footer keys of its own that reach every build as Dictionary overrides. With
nothing branded there is no footer row, so the base product's build shows only the Niord mark in the
header. A deployment still rewords a branded key through the Dictionary without a code change.

Both buttons open one legal-document dialog on the shared `<app-dialog>`. It fetches
`assets/legal/<doc>.<lang>.html` in the selected language, then the deployment's default, then
English, then the other branding languages, treats a 200 that carries the application's own index
page as missing, binds the document through `[innerHTML]` so Angular sanitises it, and sets `lang` on
the body when the document's language is not the page's.

The centre logo is a plain `<img>`, not `NgOptimizedImage`, so its `src` can swap on a language
change without the init-locked dimensions forcing element recreation.

## Update banner

`<app-update-banner>` (`ui-apps/admin/src/app/core/version/`) sits under the header on every page once
`BuildVersionService` knows that the running build is no longer the deployed one, and offers a reload.
It lives in the shell, not on one page, because a deploy lands under whatever page an open tab shows —
and is typically noticed as a click into a view the old build can no longer load. Three things raise
it:

- The deployed `index.html` names a different hashed `main-*.js` than the running document. It is
  probed at start-up and again each time the tab comes back into view — one probe at a time, and none
  once the banner shows.
- A navigation failed because its lazily-loaded route could not be fetched: the deploy removed the
  old build's chunks. The router's navigation error handler reports it; the navigation itself stays
  where it was.
- The service worker has a newer version ready, or declares the version the tab runs unrecoverable.

This is the only sanctioned update path. The invariant it belongs to is narrow: the app never reloads
itself to recover from an authentication failure (see the degraded-authentication rules). A reload
happens only where it is the explicit action — a domain switch, this banner, the landing map's
`@error` retry.

## Landing page

`/` is a full-bleed map of all published messages with a compact welcome panel floating near the top:
logo, welcome heading, the product name, and a Login button when anonymous.

- The message fetch is keyed on the active language (the backend localizes each message) and asks for
  up to 1000 published messages. Beyond that count the map silently truncates — a "showing N of
  total" affordance is on the [backlog](../backlog.md).
- A failed fetch leaves the map without messages and says so in the welcome panel ("Error fetching
  data", with _Retry_); the panel never blanks.
- The map is `@defer (on idle)` so the panel and logo paint without waiting on OpenLayers. Its
  placeholder is the map's own water colour, which is exactly why the `@error` branch is mandatory:
  Angular never retries a defer block, so without it a failed chunk fetch would read as a
  successfully loaded map showing open sea.

## Help buttons

Two wiring modes, both resolving through `HelpUrlService`:

| Mode     | Usage                                           | Section source                                                                                                                                        |
| -------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Routed   | `<app-help-anchor />`                           | The deepest `data: { help: { section } }` in the active route tree. Child routes inherit it from a parent, which is why it is declared once per area. |
| Explicit | `<app-help-anchor section="message-details" />` | For non-routed surfaces — dialogs such as message details, print, mail, compare, import, sort-area, tags and status update.                           |

The header's help item resolves the same way as the routed anchor. How a section id becomes a URL —
the `HELP_URLS` lookup (`core/main-shell/help/help-urls.json`), the `documentationUrl` base, and why
the anchor hides itself when the id resolves to nothing — is the contract in
[docs-niord-org-map.md](../docs-niord-org-map.md), together with the catalogue of every key.

## Page titles

Route `title` values are **translation keys**, not literal titles. `I18nTitleStrategy` walks the
active route tree, collects every `title` down the chain, and composes
`appName - segment - segment` (for example `Niord - Messages - Table`). Home carries no title key, so
it is simply `Niord`.

`translate()` is synchronous and the first navigation on a cold load can beat the bundle fetch, so
the strategy stores the last key list and re-applies it on every translation load. That one
subscription heals the cold-load case, a language switch, and a runtime dictionary refresh alike —
without it, a raw key could end up baked into the tab title with nothing to fix it.

## Deep links and redirects

| Input                        | Result                                                                                                                          |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `/messages?messageId={id}`   | Opens the [message details dialog](./messages.md#message-details-dialog) over the messages list.                                |
| `/#/message/{id}`            | Forwarded to `/messages?messageId={id}` by `ui-apps/admin/src/app/core/guards/legacy-hash-redirect.guard.ts` on the home route. |
| `?domain={id}` on first load | Wins over the persisted session domain (only on the initial load). The Messages Domain filter is `?searchDomain=`, not this.    |
| Any unknown path             | Redirected to home by the `**` route.                                                                                           |

Both message forms are share and bookmark URLs that stay in circulation. The message links the
backend writes into mails and tweets are `/messages?messageId={id}` on the address of its
`adminAppUrl` setting, or on `baseUri` when that setting is empty. Older mails and tweets carry the
hash form `/#/message/{id}` on `baseUri`. Where that is the backend's own address, the backend
redirects its root to `adminAppUrl` and the browser keeps the fragment across the redirect, so the
link arrives at this application's root either way. Under path routing such a URL parses as path `/`
plus a fragment and would otherwise land on a bare home page. Any change to the shareable-message
surface has to keep both forms working.

## Browser storage owned by the shell

The shell owns two keys — the persisted session domain and the persisted UI language. The full key
inventory, the stores, and the guard rule every access follows are in
[platform.md](../platform.md) → _Browser storage_.

## Related

- Bootstrap order, configuration overlay, environments and deployment: [platform.md](../platform.md)
- Layer boundaries, data flow and shared primitives: [architecture.md](../architecture.md)
- Error and notification policy: [coding-standards.md](../coding-standards.md)
- Auth fixtures and the role-based end-to-end specs: [testing.md](../testing.md)
