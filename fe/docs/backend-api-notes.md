# Backend API notes

The Niord REST API is a separate application with its own deployment lifecycle. What follows are the
wire contracts that are **not** obvious from a response body — the places where a reasonable
assumption produces a silent wrong answer, a 500, or a field that vanishes on save. Cross-cutting
rules first, then one section per endpoint family.

Every path is relative to `AppConfig.apiBaseUrl` (`ui-apps/admin/src/app/core/config/app-config.interface.ts`). The
value ends in `/rest` — the base product's environment files ship the origin-relative `/rest`
(through the dev proxy in development, on the deployment's own origin when deployed), and a
country's development or test environment may name its test backend's absolute `…/rest` — so a path written `/messages/search` is `…/rest/messages/search` on the server.

---

## Cross-cutting contracts

### Base URL, origins and file addresses

`apiBaseUrl` is an absolute, **cross-origin** URL only in such a country environment, so the
deployment it reaches must accept the dev origin cross-origin. Every other dev server
proxies `/rest` to the `be/` backend with `apiBaseUrl` set to `/rest` — see
[platform.md](platform.md) → _Dev servers: cross-origin or proxied_.

The API reports file addresses **root-relative** (`/rest/repo/file/…`), because that is what they are
on the server that serves them. A browser resolves those against the _page_, which under a
cross-origin configuration is a different host — the reader gets a 404, or the app's own index page
where a PDF was promised.
Resolve every such href and every copied link through `ApiUrlResolver.absoluteApiUrl()`
(`ui-apps/admin/src/app/core/config/absolute-api-url.ts`); it leaves already-absolute addresses untouched.

### Headers, roles and diagnosing 401/403

Two interceptors decorate requests whose URL falls inside `apiBaseUrl` or the WMS URL
(`ui-apps/admin/src/app/core/interceptors/`):

| Interceptor              | Adds                      | Notes                                                                                                  |
| ------------------------ | ------------------------- | ------------------------------------------------------------------------------------------------------ |
| `authTokenInterceptor`   | `Authorization: Bearer …` | Only when authenticated; refreshes first. A 401 drops the session to guest locally — never a redirect. |
| `niordDomainInterceptor` | `NiordDomain: <domainId>` | Only when a session domain is set.                                                                     |

Which URLs count as the backend, and why the match is `urlMatchesPrefix` rather than `startsWith`,
is in [platform.md](platform.md) → _Interceptor scoping_.

**Roles are per Keycloak client, and a client is a Niord domain.** A user is `editor` _in a domain_,
not globally: the token's `resource_access` map is keyed by `domainId`. Only a role on the base client
(`niord-client-master`) applies everywhere; a Keycloak realm role grants nothing, and a token issued to
a client outside `niord.oidc.allowed-clients` is answered 401. The helper semantics (`hasRole`, `hasRolesFor`, `adminIn`, `canManageUsers`) are in
[features/shell-and-auth.md](features/shell-and-auth.md) → _Roles_. Consequences on the wire:

- A **403 on an authenticated call usually means no `NiordDomain` header**, not a missing permission —
  the request arrived with no client for the backend to resolve roles against. Check that a session
  domain is set before blaming the user's rights.
- The same user can be an editor in one domain and read-only in the next. A permission answer is only
  valid for the current session domain, which is why switching domains is a full page reload.
- `hasRole('user')` is a synonym for "authenticated": the app assumes every signed-in account holds
  `user`, and the backend still requires it as a client role. A control whose write the backend refuses
  without that role asks `AuthFacade.hasDomainUserRole` instead: the client role `user` on the session
  domain's client or on the base client, as the backend grants it.

### Dates on the wire are epoch milliseconds

**Except on the publications endpoints, where they are ISO 8601 strings.** Those resources serialise a
`java.util.Date` as `"2019-12-31T22:59:59.999+00:00"` while the publications models type every date as
`number` — and a string in a numeric comparison is not an error, it is a wrong answer that looks like a
verdict (`"2019-…" <= Date.now()` is false, so a cut-off years past reads as one that has not happened).
The three publication API services therefore normalise **every** response at the boundary with
`withEpochDates` (`ui-apps/admin/src/app/shared/utils/epoch-dates.ts`), which replaces the ISO instant **carrying an
offset** in each **declared date field** (a fixed list of field names in the utility, at any depth) with
its epoch milliseconds; a date-time without an offset is left alone, because parsing one reads the
browser's clock, and free-form containers — `reportParams`, `detail`, `detailParams`, criteria documents,
name maps — are passed through untouched, so a value that merely looks like a timestamp stays content. The three reads that travel as `HttpResourceRequest`
descriptors pass the same function as the resource's `parse`. Requests need no conversion — the server
reads an epoch number into a `Date`. Nothing outside those services sees the string form, so the rest of
this section holds.

The backend serialises every other instant as an **epoch-millisecond number** — message dates, batch
timestamps, script-resource revisions. Some model types say `string` anyway
(`Message.publishDateFrom`, `publishDateTo`, `followUpDate`, `MessagePart.eventDates[]` in
`ui-apps/admin/src/app/features/messages/models/message.model.ts`); the annotation is a lie about runtime.

The editor's form round-trips those through `Date` and writes them back as **ISO strings**, so a
working copy and its loaded baseline can hold the same instant in two different types. Every
comparison, dedup and change signature must reduce both sides to epoch ms first — see `normalizeDate`
in `ui-apps/admin/src/app/features/editor/util/message-change.util.ts`. Never compare date fields as strings, and
never render a raw value without going through the shared formatting service.

### `?lang=` filters the descriptions out of the payload

Most catalogue endpoints accept `?lang=`, and it does more than pick a label: the backend's data
filter **returns only that language's `descs`**. Saving an entity fetched that way writes the
narrowed list back and drops every other language.

| Need                                                                        | Send                                                           |
| --------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Display only, in the active language                                        | `?lang=<code>`                                                 |
| A value that will be echoed back to the server, or saved                    | **no** `lang`                                                  |
| A value rendered per language (parameter-type LIST values, template labels) | **no** `lang`, or fetch once per configured language and merge |

**Omitting `lang` widens the answer only where the parameter has no server default.**
`GET /categories/search/{ids}` declares a default of `en`, so sending nothing there returns English
only — the opposite of the table's first column. That is why the template-execution view cannot just
drop the parameter: it fetches category/template details **once per configured language and merges the
`descs`** (`fetchTemplateDetailsAllLangs` in
`ui-apps/admin/src/app/features/editor/views/template-execution-view/template-execution-view.ts`).
`GET /areas/search/{ids}` has no default, so dropping the parameter there really does return every
language. Check the endpoint before relying on omission.

Verified instances: `AreasApiService.getByIds({ allLanguages: true })` drops the param on purpose
(`ui-apps/admin/src/app/features/messages/services/api/areas-api.service.ts`); `TemplatesApiService.parameterTypes()`
sends none; the publications admin list sends none because
it is what the series editor saves back. `LangHttpHelper` (`ui-apps/admin/src/app/shared/api/lang-http.helper.ts`)
is the one place that builds `lang` params and CSV-by-id reads.

**The public API reads the language as `lang` as well, and by no other name.** `/public/v1/messages`,
`/public/v1/message/{id}` and `/public/v1/publications` bind `lang`; a `language` parameter is dropped without
an error, and the answer then carries every stored language — on the public site's Danish message list about a
third more bytes, and a publication list whose title and link are whichever language was stored first. A row with
no text in the language asked for comes back in one it has, under that desc's own `lang`: the data filter does
this for every entity, and `IssuePublicationMapping` does it for a published issue. The public site still puts
the active language first when it renders, for an answer that carries more than one.

### Value objects ignore unknown fields

The backend VOs deserialise leniently: a field the VO does not declare is **silently discarded**, and
the save returns 200. A misspelled or renamed frontend field therefore looks like a save that worked
and a value that will not stick. The canonical example is in the model itself — a message part's
order is `indexNo`; sending `index` would make re-ordering never persist.

The same rule cuts the other way on export/import: round-tripping an export through a client model
loses any field the client does not carry, which is why exports are downloaded as the server's own
bytes rather than re-serialised.

### Absence: null bodies, missing keys, empty strings

- Several message reads answer an **empty body (204)** for an unknown id, not a 404 — `message/{id}`,
  `editable-message`, `copy-message-template`, `extract-message-publication`. The backend method simply
  returns `null`, and `HttpClient` emits `null` for an empty body, so **no error handler fires**.
  Callers must treat a `null` emission as a failed load; do not test for a status code.
- `GET /messages/search` answering `total: 0` is an _answer_, not a failure.
- Absent and `null` are not the same on the way in: undefined keys drop at serialisation, and
  inventing `null`s can mean "clear this" where the server distinguishes them. Follow each adapter's
  `toVo`/`fromVo` pair rather than spreading raw models onto the wire.

### Error shapes

Almost everything fails as a **bare HTTP status with a text body**. There is no application-wide
error envelope. The publications family is the exception: it answers with `{ code, message, … }` and
the code is the machine-readable half (see below). Read policy for the UI — inline error state for
reads, a toast for mutation failures — lives in [coding-standards.md](coding-standards.md).

### One-time tickets for downloads

The browser cannot attach a bearer token to a plain navigation, so the backend mints short-lived,
**single-use** tickets: `GET /tickets/ticket` returns one as `text/plain`, with an optional
`?role=` narrowing. Tickets expire in about a minute and are consumed on first use, so a URL carrying
one is not shareable.

A ticket carries exactly the roles named by its `role` parameters, and the backend refuses a role the
caller does not hold when asking (**401** without credentials, **403** otherwise). A ticket asked for
with no `role` identifies the user but grants no role at all, so ask for the role the download needs.

A ticket is also bound to the domain the `NiordDomain` header named when it was issued. When the
ticket is a request's only credential, as on a download, that domain is the request's domain whatever
header the request carries, so a ticket's roles never apply in another domain. Fetch the ticket with
the domain the download is for selected.

`AuthTicketService` deliberately does **no caching** — one fetch per download. `TicketedDownloadService`
is the only home for the pattern: `openTicketed(url, role?)` appends `?ticket=`/`&ticket=` and opens a
new tab, and `openPublic(url)` is the explicit no-ticket mode for anonymous endpoints (adding a ticket
there would misrepresent the endpoint's auth contract). Both live in `ui-apps/admin/src/app/core/auth/services/`.

---

## Messages — `/messages/*`

### `GET /messages/search`

The workhorse read, and the one that lies most quietly.

- **Unusable filters are dropped, not refused.** A filter the backend cannot resolve is ignored and a
  plausible corpus comes back instead of an error. `tag=` must be the tag's **uuid** (`tagId`), not
  its name; `query=` is free text, not a short id. The cheap guard when scripting against it: compare
  the answer's `total` with the count the filter's own source reports (a tag's `messageCount`).
- **`status` defaults to `PUBLISHED`** whenever nothing else constrains the query. An explicit
  `status` or `tag` lifts the default — so the endpoint being anonymous is _not_ what restricts it.
- **The uid is `id`.** `Message.id` is the UUID; `shortId` is the printed number and is absent until a
  message is numbered. There is no separate `uid` field on the public shape. Selection, tagging and
  bulk operations key on `id`.
- Page size is `maxSize`, page index is `page` (zero-based). `MessagesApiService.searchMessages` owns
  `lang`, `page`, `maxSize`, `viewMode` and `includeNoPos` and overwrites any same-named key in the
  caller's filter string. Map view asks for `viewMode=map` and a 1000-row page; other views take 200
  (`MessagesShellFacade.maxPageSize`).
- **`maxSize` has a ceiling of 1000** on every search built from request parameters (`messages/search`,
  and the report, mail and export searches before they set their own size): a larger value is cut to
  1000 and a value below 1 is raised to 1, silently. Ask for more than 1000 and you get 1000.
- **Callers without an identity get plain-word free text.** For a request with no token and no ticket,
  `query=` is matched as literal words (no wildcards, regular expressions, phrases or `AND`/`OR`/`NOT`)
  and cut to 256 characters, and `username`, `userType` and `comments` are ignored. A ticketed request
  (the print and export) keeps all three and the full query syntax, because the ticket names the user.
- The URL contract for filters — key order, repeated keys, which slices are emitted when — is owned by
  `messages-filter-params.serializer.ts` alone. See [features/messages.md](features/messages.md).

**Two meanings of "anonymous", on purpose.** The backend tells a guest from a known caller by one of
two tests, and a ticket without roles passes the first and not the second. The print and the export
therefore ask for a `user` ticket whenever the user holds that role in the session domain, and a
ticket with no role only for a guest or a signed-in user with no role there:

- **Identity:** the request names a user (`userService.currentUser() != null`), through a token or a
  ticket, with or without roles. It decides the `username`, `userType` and `comments` filters and the
  plain-word free text above, the tier a `publication=` filter resolves at, the details a message
  export carries (promulgations, the unacknowledged-comment count), the anonymous PDF limit (a caller
  without an identity takes one of a few concurrent permits and gets **503** when none is free) and
  the print parameters (`debug`, `param:<name>`, map thumbnails, and which report a PDF may name).
- **Role:** the caller holds the `user` role in the domain it names
  (`userService.isCallerInRole(USER)`; the `NiordDomain` header adds only the roles the token holds
  there, and a ticket carries only the roles it was asked for with). It decides the status floor
  (non-public statuses reach a user of the searched domain only), the public-only matching of
  `search-message-ids`, the public tier of `/publications/search` and `/publications/all`, and whether
  `/publications/publication/{ids}` and `/publication-issues/by-ids` withhold the link of an
  unreleased document.

**`publication=` refusals.** A publication that cannot be resolved to a message set answers **400 with
an error code starting `PUBLICATION`**. That refusal is about one filter chip, not about the list:
reported as a list failure it reads as "search is broken", and swallowed it reads as "this publication
is empty", which is worse because it looks like an answer. `publicationFilterErrorDetail`
(`ui-apps/admin/src/app/features/messages/services/publication-filter-error.ts`) matches the code **prefix** rather
than an enumeration, so a new refusal code keeps explaining itself.

Related: `GET /messages/search-message-ids?txt=&deleted=&lang=` is the id/short-id type-ahead. The
adapter always sends `deleted` explicitly rather than leaning on a server default it does not own.
Without the USER role (a guest, or a token without the `NiordDomain` header) it matches published,
cancelled and expired messages only and ignores `deleted=true`; `txt` is matched literally (`%` and `_`
are not wildcards) and `maxGroupCount` is kept within 1–50.

### Reading one message

| Call                                                      | Returns                                                                                                            |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `GET /messages/message/{idOrShortId}`                     | The public shape. Accepts a UUID or a short id. No `lang` → every language's `descs`. **Empty body when unknown.** |
| `GET /messages/editable-message/{id}`                     | The editor/system shape, including `editorFields`, `editRepoPath`, `revision`. **Empty body when unknown.**        |
| `GET /messages/new-message-template?mainType=&populate=`  | A blank editable draft.                                                                                            |
| `GET /messages/copy-message-template/{id}?referenceType=` | A copy as a new draft. Empty body when unknown.                                                                    |

`editorFields` is a server-derived `fieldId → shown` map (from the message's area, category and series
plus a system setting) and is **consume-only** — never sent back as an authority.

### Editor writes

- **Save dispatches on `created`**: `POST /messages/message` when the message has never been saved,
  `PUT /messages/message/{id}` afterwards. The first save assigns the permanent id and moves the
  temporary edit repository to the permanent one.
- **Status transitions PUT the raw status string** to `/messages/message/{id}/status` with an explicit
  `application/json` content type — an _unquoted_ body (`PUBLISHED`, not `"PUBLISHED"`). A query
  param is rejected 415, a JSON-quoted body 400 (no status of that name). A refused transition is
  409, naming the message and the two statuses.
- **Bulk status** is `PUT /messages/update-statuses` with rows whose field is named `messageId` but
  which carries the **UUID**, never the short id.
- **Promulgations must round-trip opaquely.** The backend stamps a Jackson `@class` discriminator on
  each promulgation; the request body's list is abstract, so stripping or normalising `@class` makes
  the save 500. Carry them verbatim and discriminate in the UI on `type.serviceId`.
- `POST /messages/adjust-editable-message` re-derives computed fields (areas, charts, title, source)
  for an in-flight draft; `?inclPromulgations=true` extends it to promulgation text.

### Publication HTML on a message

`POST /messages/extract-message-publication` and `POST /messages/update-message-publications` accept
**only a pruned body**: `{ descs: [{ lang, publication, internalPublication }] }`. Posting the whole
working message 500s — the message VO cannot round-trip every editor field. Seed one desc per
configured language even when the working copy has none, or an "update all languages" posts an empty
list and the rendered HTML never comes back. See `MessagesApiService.publicationTemplate`.

### Repository files and thumbnails

- `DELETE /repo/file/{path}` — the path is spliced in **un-re-encoded**. The route matches embedded
  slashes, and `encodeURIComponent` on the whole path would collapse them and double-encode what the
  backend already escaped. The response is `text/plain`.
- `GET /message-map-image/{uid}.png` — a `307` to the image file under `/repo/file/…`; when there is
  no image to show (no geometry, or the image cannot be drawn) the endpoint answers the placeholder
  itself: `200`, `image/png`, `X-Niord-Map-Image: placeholder`, expiring in 10 minutes. The answer
  never points at `/img/…`, so it loads through any front that forwards `/rest` alone, the dev
  server's proxy included. An unknown UID is a `404`. A generated image's file name carries the
  portrayal version (`map_256_<version>.png`), so a portrayal change gives every image a new name.
  Until the new image is drawn, the one drawn with the earlier portrayal is served with a 60-second
  expiry (a current image: 10 minutes) while the new one is made in the background; a message
  changed since its image was drawn is drawn while the request waits.
- `POST /message-map-image/{editRepoPath}/{revision}` — multipart thumbnail upload, answers `text/plain`
  with the new repository path.
- `PUT` to the same path — the client-captured map snapshot, sent as a base-64 **data-URL string** with
  an explicit `application/json` content type (a string body would otherwise default to `text/plain`
  and 415).
- `POST /messages/format-message-geometry?lang=&template=&format=` answers **`text/html`**, so JSON
  parsing must be disabled.

### The public API's `externalize` parameter — `/public/v1/*`

The anonymous endpoints the public site uses (`/public/v1/messages`, `/public/v1/message/{id}`,
**and** `/public/v1/publications`) take `externalize`, which defaults to `true`: every repository
link in the payload is then rewritten to an absolute URL on the backend's configured base URI —
another origin than the one serving the page. The public site sends `externalize=false` on all
three and gets three different shapes back, verified against a live backend: message HTML and
publication `link` values come root-relative (`/rest/repo/file/…`), while attachment paths come
**bare** (`messages/7/24/<uuid>/…`, no prefix at all). One function in the public site normalises
the three, and stored HTML can still carry an absolute link from a save made while `externalize`
was on, so the function also reduces such a link to its path — when it is on the site's own host or
on one of the backend's public hosts the country configuration lists (`backendHosts`). A repository
link on any other host belongs to another deployment and is left as written.

### The PDF reports are anonymous, and they read the administration search names

`GET /message-reports/report.pdf` and `GET /message-reports/message/{id}.pdf` are `@PermitAll`:
both answer an unauthenticated request with `application/pdf`, and the public site opens them
directly — that is where its two print buttons go, and the container allows exactly those two
paths, with no query on them but the parameters the site composes and no report but `public`.
Three things differ from the public API and each one is a wrong answer rather than an error:

- **The parameter names are the editor's.** `report.pdf` builds `MessageSearchParams` from the
  request, so an area is `area` (not the public API's `areaId`); an `areaId` sent here is ignored
  and the whole country comes back. The language is `lang` here as it is in the public API, and
  `mainType` is the same name in both, and repeats. The public site's `active` has no counterpart — and none is needed: the public
  read ignores it too, `/public/v1/messages` declaring no such parameter.
- **The status floor is the same, the publication scope is not.** An anonymous caller gets
  `PUBLISHED` messages only, exactly as the public API does. But the public API also restricts the
  search to the message series of the domains marked `publish` and `report.pdf` does not, so the
  same filter renders a **superset**: measured against a live backend, a Danish root-area list of
  211 messages came back as 254, the extra 43 being the NM Almanac, NM Annex and firing-area
  notices whose domains this deployment does not publish. Narrowing the request with a
  `messageSeries` per series the caller already holds closes the gap exactly — nothing missing,
  nothing extra — which is what the public site sends. An **absent** `messageSeries` is the
  unrestricted request, so the public site offers its list sheet only once the read keyed on the
  current filter has settled with a non-empty series set: a list still loading, one that came back
  empty and one that failed are not printable at all.
- **An anonymous list is rendered in the `public` report, one message in the standard report, and
  neither in any other.** For a caller the backend knows, `report` defaults to the standard
  report, whose list opens with a front page written for the editors: a heading, the search
  criteria with their internal ids, the number of messages and a table of contents, in English
  whatever the language. The backend creates a
  `public` report at start-up — the same template without that front page — and the public site
  names it on its list sheet. The catalogue (`GET /message-reports/reports`) also answers
  anonymously and offers a `draft` report, which renders every language variant of every message.
  The `public` report belongs to no domain, so the catalogue lists it to every caller, and the
  administration app's list print dialog offers it as "Public" next to the other two. For a caller
  the backend knows, `report` is resolved by name alone: whichever domain a report belongs to, the
  endpoint renders it, and a name it does not know gets the standard report. An anonymous caller
  — the public site's reader, and anyone who reaches `report.pdf` without a ticket on the
  administration host, where no proxy stands in front of it — gets the `public` report whether the
  request names it or names none, and a 403 for naming any other. Deleted on `/sysadmin/reports`,
  the `public` report is created again by the next anonymous list, and by the backend's next start;
  until then a signed-in request that names it gets the standard report. The public site's
  container requires `report=public` exactly once on the list and refuses every other value, and a
  second report meant for that site is allowed by name there and in the backend alike. One
  message has no front page in any report, so its address names none, and an anonymous caller of
  `message/{id}.pdf` gets what that address gets — the standard report — whether the request names
  it or names none, and a 403 for naming any other, the `draft` report among them; the refusal
  comes before the message is looked up. The container lets no `report` through on that path at
  all. `pageSize` and
  `pageOrientation` default to A4 portrait and take the page sizes and orientations a publication
  series can be given — the backend's `PageSize` and `PageOrientation` values, in any case;
  anything else is the default.

The dates on both documents are in the zone of a domain, settled before rendering starts, since a
known caller's document is rendered after the request has returned, on another thread: the
caller's current domain's, or — for an anonymous reader, who has none — the zone of the first active domain,
in sort order, that holds the series of one of the printed messages or of the requested series, and
failing that the first published domain with a zone. The sheet and the screen therefore agree on
the day of a message published near midnight, as long as that domain names the zone the site
shows.

**Only a few anonymous PDFs are made at a time.** A caller with no identity — no token and no
ticket, the public site's reader among them — takes one of `niord.reports.anonymous-pdf-concurrency`
permits (3 by default) while its PDF is made, on either endpoint. When none is free the answer is
**503** at once, a problem+json body with no `Retry-After`, before anything is searched or looked up;
the same request a moment later can succeed. Such a PDF is rendered in full before its first byte is
sent, and never with map thumbnails. A signed-in or ticketed caller takes no permit, and its PDF is
streamed as it is rendered. The public site opens both PDFs in a new tab, so its reader sees that
503 body there ([backlog.md](backlog.md), _The public site_).

`debug=true` on either endpoint returns the HTML the PDF is rendered from instead of the PDF —
the cheap way to see which messages a set of parameters actually selects — and the free-form
`param:<name>` values reach the template after everything else. Both are for a caller the backend
knows: signed in, or presenting a ticket issued to a signed-in user, as the administration app's
print downloads and the publication release do. For anyone else the backend ignores both, and the
public site's container refuses such a query before it leaves the container. A `param:` value is
text: the backend HTML-escapes it, so markup in it is printed rather than rendered, and it drops a
`param:` named like a value the endpoint sets itself (`pageSize`, `pageOrientation`,
`searchCriteria`, `frontPage`, `messages`, `mapThumbnails`, `areaHeadings`, `separatePageIds`,
`executionMode`), so only a report's own parameters — year, week, edition, notice reference —
reach the template. The debug HTML is still served with `Content-Security-Policy: sandbox`, and the
PDF renderer loads images and style sheets from the backend's own `baseUri` origin only.

**A PDF that shows a map image credits OpenStreetMap in its page footer.** When the document holds
an image from `/rest/message-map-image/` — a map thumbnail, generated or custom — every page carries
`© OpenStreetMap contributors` in small grey type beside the footer, on the right. The PDF renderer
adds it, not a template, so every template a deployment holds gets it: the bootstrap's, the
imported or edited ones and a country's. A PDF without a map image has no credit, and neither
has the `debug=true` HTML, which is the template's output before the renderer adds anything.

---

## Templates — `/templates/*`

`PUT /templates/execute` runs every `TEMPLATE` category on an inline message and returns the whole
re-rendered message. It carries **two different geometry contracts in one body**:

| Field                                   | Shape                                                                                                                                                 |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `message.parts[].geometry`              | Standard GeoJSON `FeatureCollection`.                                                                                                                 |
| `templateParams[].positions[].geometry` | A **nested serialised feature list**: `[{ coordinates: [{ coordinates: [lon, lat] }, …] }]`, with interior rings and the ring-closing vertex dropped. |

The second shape exists because the backend's position templates iterate it as a sequence
(`feature.coordinates`). Feeding raw GeoJSON there throws server-side and fails the whole execute and
preview. `template-execute-fields.ts` builds it via `serializeReadableCoordinates`; do not hand-roll a
second builder.

The backend refuses two things in that body. A template parameter string that contains the start of a
markup tag (`<` followed by a letter, `/`, `!` or `?`) gets a 400, because the templates print
parameters unescaped into HTML fields; a `<` that starts no tag, as in `< 2 m`, is plain text. An inline
`category` definition (the category editor's "Test…" preview) runs as sent only for a sysadmin; for
anyone else it counts only for its `id`, and without an `id` it gets a 403. A category `id` (inline or
as `categoryId`) that names no stored category gets a 404.

Language rules for this family: `GET /templates/parameter-types` is fetched **without** `lang` (a LIST
value's `descs` must stay complete because the chosen value object is echoed back and rendered per
message language), and template/category details are fetched once per configured language and merged.

`POST /templates/validate-light-character` and `…/validate-call-sign` answer with **`isValid`**, not
`valid` — the bean is named after its accessor. Reading the wrong key yields `undefined` for every
answer, which is indistinguishable from "invalid", so correct input would be rejected. The adapter
normalises it once.

---

## Geometry on the wire

**A GeoJSON feature's `properties` may never be `null`.** OpenLayers writes `properties: null` for a
feature with no properties, and the backend NPEs on it (a 500 from `templates/execute`). Every commit
path normalises to `{}` — `GeometryConversionService.olToGjFeature`
(`ui-apps/admin/src/app/shared/services/geometry-conversion.service.ts`) does it for edited features, and hand-built
features in the area, chart and report admin screens follow the same rule.

This is not a coordinate-precision problem: do **not** round coordinates to work around a geometry
rejection.

Other geometry facts: the wire projection is EPSG:4326 while OpenLayers works in EPSG:3857 — the
conversion service is the only boundary. A chart persists **one bare geometry** (two or more features
collapse to a `GeometryCollection`, zero clears it), which is why the chart editor has no per-feature
property panel.

---

## Catalogues — areas, categories, charts, sources, publications

Reads follow one shape: `GET /<entity>/search/<comma-separated ids>?lang=…`, built by
`LangHttpHelper.getByIdsCsv` (each id segment encoded, the commas literal; an empty id list
short-circuits without a request). `POST /areas/intersecting-areas?maxLevel=&lang=&domain=true`
returns the full area objects the editor stores verbatim.

The publication **picker** reads two catalogues at once: publication issues, and the rows still served
by the previous system, which cannot gain a status or a language flag because their payload is a
published XSD with a fixed field order. Both halves are asked, the answers are merged on the shared id
with **issues winning a collision**, and **a failing half is not an empty half** — only a failure on
both is a failure. The page-size parameter is `maxSize` on both halves; `limit` is read by neither, so
sending it silently leaves the server on its own default however large a page was asked for, and
nothing says the list was cut — page through instead. Id hydration applies **no status narrowing**:
a message citing a publication that was later withdrawn must still render what it cited.
See `ui-apps/admin/src/app/features/messages/services/api/publications-api.service.ts`.

**`maxSize` and `limit` are two parameters with two jobs**, and neither substitutes for the other:

| Parameter            | Job                                               | Where                                                                                                                              |
| -------------------- | ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `maxSize` (+ `page`) | Paging — a window into a total the answer reports | `messages/search`, both publication picker halves, `publication-issues/series/{id}`                                                |
| `limit`              | A plain cap on the result, no paging and no total | the catalogue `search` and `search/{ids}` reads (areas, categories, charts, message series, tags) and `publication-categories/all` |

The backend defaults `limit` to 1000 on the by-ids reads, so a caller that hydrates more ids than that
must pass its own. The catalogue type-aheads pass small caps on purpose (`areas/search` and
`categories/search` default to 10). Do not "fix" a caller by swapping one parameter for the other.

---

## Print, export and reports — `/message-reports/*`, `/message-io/*`

### The report catalogue

| Endpoint                              | Who may call it                                                                      | Returns                                                                                         |
| ------------------------------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| `GET /message-reports/reports`        | anyone — the endpoint requires no role; the list is scoped by the **session domain** | The reports this desk may print a **list** with: the public ones plus the current domain's own. |
| `GET /message-reports/detail-reports` | anyone — no role, and no domain scoping either                                       | The two built-in **single-message** reports (standard and draft), a fixed pair.                 |
| `GET /message-reports/all`            | sysadmin only — **403 for everyone else**                                            | The whole inventory, for report administration.                                                 |

Any picker outside the report-administration screen must use the domain-scoped list, never `/all`. The
publications series editor depends on this: a report is a precondition for activating a query-backed
series, and listing the sysadmin inventory there would leave every plain admin with an empty picker and
no way past the gate.

`?expandParams=true|false` (default `true`) expands `${year}` / `${week}` placeholders in the reports'
parameter defaults. It is a **boolean**: a non-boolean value — a number, say — parses as `false` and
silently disables expansion. Report **names are localised by the backend**, so a UI language switch
needs a fresh fetch, not a re-render.

### Printing

`GET /message-reports/report.pdf` (list and selection) and `GET /message-reports/message/{id}.pdf`
(single). Parameters: `report`, `pageSize`, `pageOrientation`, `mapThumbnails`, `lang`, `ticket`, and
each report parameter under a **`param:` prefix**. Selection printing creates a server-side temporary
tag from the selected ids and passes `tag=`; the filter query string is appended to the list and
selection URLs (never to the single-message one, whose id is in the path) and the backend prefers the
tag when present. See `ui-apps/admin/src/app/features/messages/services/message-print.service.ts`.

### Export and mail

`GET /message-io/export.zip?lang=&ticket=` plus either `tag=<tempTagId>` (selection) or the filter
query string (current search). The ZIP is an attachment, so the export navigates the current tab
rather than opening one.

The matching import is `POST /message-io/import` — multipart with **two** parts, the archive under
`file` and the import options as a JSON `data` part, answering a plain-text status line that must be
read as `text` (`ui-apps/admin/src/app/features/messages/dialogs/message-import-dialog/message-import-dialog.ts`).

`POST /message-mail/send` sends the mail: the recipients, the subject and the text travel in a JSON
body (`{ mailTo: [..], mailSubject, mailMessage }`), while the selection (`tag=<tempTagId>`) and
`lang` stay in the query, so a long text fits and none of the mail reaches an access log. The answer
is a plain-text status line, so it must be read as `text`; the default JSON response type turns a
real 200 into a parse failure. A missing, blank or malformed address answers 400 with a `detail`
naming it, and nothing is scheduled. The older `GET /message-mail/send`, which carried everything in
the query, is deprecated and goes in the next release; the admin app no longer calls it.

### The 1000-message caps

Two distinct caps, easily confused:

- The interactive print and mail endpoints (`/message-reports/report.pdf`, `/message-mail/send`)
  **truncate silently at 1000 messages**. Nothing in the response says the list was cut, so anything
  that must be complete cannot go through them.
- Publishing a publication issue **refuses** above 1000 resolved members with `409 MEMBER_LIMIT_EXCEEDED`
  naming the issue and the count, and the publish checklist carries a `MEMBER_LIMIT` row reporting
  "_n_ of 1000" before it gets that far. A publication is rendered in-process from its frozen member
  list, not through the print endpoint, so the refusal is a product limit rather than the truncation
  above.

---

## Publications — `/publication-series/*`, `/publication-issues/*`, `/publication-categories/*`

The domain rules live in [publications/domain-rules.md](publications/domain-rules.md); the full endpoint
reference is [publications/API-CONTRACT.md](publications/API-CONTRACT.md) and the model is
[publications/DATA-MODEL.md](publications/DATA-MODEL.md). Only the wire traps are repeated here.

- **Three series shapes, and the short names look alike.** `/publication-series/search-details` is the
  admin list and carries `status` and `importSource`;
  `/publication-series/series/{id}` is the lean shape (any authenticated user) and carries none of
  them, so a screen that needs to know whether a series is a draft must not read it there; the admin
  editor reads a third, `/publication-series/editable-series/{id}`, which is where the admin fields
  live. Note that `/publication-series/series/{id}` and `/publication-issues/series/{id}` are different
  resources — write the family prefix, always.
- **The admin list sends no `lang`** — it carries every configured language because it is what the
  editor saves back.
- **The issue list is an envelope, not an array.** `/publication-issues/series/{seriesId}` returns rows
  newest-first that include _synthesised_ missing and upcoming periods, plus a flag saying whether gaps
  were looked for at all.
- **Every date is epoch milliseconds by the time a model holds it**, and never rendered text. The wire
  is the exception this family owns: it answers ISO 8601 with an offset, and the API services convert it
  at the boundary — see _Dates on the wire_ above.
- **A status change is `PUT …/status` with the bare status as a JSON string body** (`"RETIRED"`) — a
  string, not an object.
- **Optimistic concurrency.** Series and issues carry a `version` filled by every read and _compared_
  by every write; it travels in the body where there is one and as `?version=` where the body is spoken
  for (status change, withdrawal, file calls). An **absent** version is the caller declining the check,
  so omit the parameter rather than sending it blank. A stale write is refused with `STALE_VERSION`,
  and the answer is to refetch and look at what moved — not to retry. An issue's version also moves when
  its child rows change, so two curators collide instead of overwriting each other.
- **This family has a real error envelope**: `{ code, message, status, fieldErrors, unacknowledgedWarnings, … }`.
  The `code` is the machine-readable half and the `message` is English prose for a log — never rendered.
  `ui-apps/admin/src/app/features/admin/sections/admin/publications/publication-errors.ts` maps codes to UI text and
  is the list of refusals worth naming. A bare 403 with **no** code means a missing role; `NOT_IN_DOMAIN`
  means the right role at the wrong desk, which the user can fix by switching domain.
- Rendered publication files are reported at **root-relative** addresses; resolve them through
  `ApiUrlResolver` like every other backend file.

---

## Settings — `/settings/*`

`GET /settings/web` is **anonymous** and returns a flat `{ key: value }` map of the settings marked
web-visible. It is fetched once at bootstrap, capped at 5 s, and every failure is swallowed — the app
boots on environment config alone. Values are overlaid onto `APP_CONFIG` **by key name** with type
coercion, because the backend emits some booleans as the strings `"true"`/`"false"`. Keys that match no
config leaf are ignored by construction, and a payload key is not necessarily the frontend key with the
same meaning — do not alias one onto another because the names look alike. The mechanism, its excluded
roots and its guardrails are documented in `ui-apps/admin/src/app/core/config/web-settings.ts` and in
[platform.md](platform.md).

The map always carries two booleans that are **computed, not stored**: `s124Enabled` and
`secomEnabled`, derived at each request from the running `niord.s124.enabled` and
`niord.secom.enabled` (`secomEnabled` is true only where the SECOM subsystem runs). They are JSON
`true`/`false`, never strings; no setting row and no Settings-page edit changes them, and a
`secomEnabled` row an older node registered is overridden by the computed value. The overlay writes
`s124Enabled` into `APP_CONFIG`; the admin section gates read `secomEnabled` and
`adminIntegrationPageEnabled` from the same map, fetched once per area mount.

The sysadmin surface is different: `GET /settings/editable-settings` (also the export URL, opened with
a ticket), `GET /settings/setting/{key}` — read only by the mail queue's purge note
(`mailDeleteAfterDays`); it answers 404 for a key with no row — and `PUT /settings/setting/{key}`,
whose path key must equal the body key. A setting's `type` is a raw wire string (`Boolean`, `Integer`, `Long`,
`Float`, `Double`, `Password`, `String`, `json`, or absent) and is deliberately not normalised; `value`
is whatever was serialised, including a parsed object for a `json` setting. No settings call carries
`lang` — settings are not localised. Edits apply on the next page load; the app does not poll.

---

## Dictionaries — `/dictionaries/*`

Dictionary entries are the runtime override layer for UI text.

- **Reads are anonymous**, which is what lets the override fetch resolve before login and before the
  domain is known. The editing screen lives in the sysadmin area.
- **Only two dictionaries reach the frontend**: `web` and `message`. `pdf` and `mail` are rendered
  server-side, and `template` is the value pool for LIST parameter types — none of them are UI text.
- **Their keys are a flat keyspace shared with the compiled translation bundle.** An override matches by
  exact key; a key that does not exist in the bundle overrides nothing, silently.
- **An override must preserve every interpolation token**, or it is refused and the compiled value
  stands — the guard is described in [platform.md](platform.md) → _The interpolation-token guard_.
- A blank or absent value in the requested language does **not** override — the compiled label stands.
- **`asAdded: true` on a translation** of `GET /dictionaries/dictionary/{name}/entries` means its value
  still equals what the backend's start-up added or adopted; the property is sent only when true and
  ignored on input. Such a translation does not override, and the key does not fall through to another
  language (see [platform.md](platform.md) → _The loader merges two sources_). An older backend sends no
  flag, which counts as false.
- **`GET /dictionaries/dictionary/{name}/key-users?key=`** (SYSADMIN, as delete is) answers who uses a
  key: `templates` (the script resource paths whose content names the key, sorted), `prefix` (the
  runtime-built prefix such as `msg.status.` the scan also used, when the key has one; absent otherwise), `readers` (ids of
  the backend code that reads the key, `RequiredTexts.Reader`) and `paramTypes` (the names of the
  parameter types whose LIST holds the entry, sorted; an older backend sends none, read as `[]`). The
  key is a query parameter encoded in full (`+` and `;` too, which Angular's default codec leaves bare
  and the backend would read a `+` as a space), so any key character survives; the app maps an id it
  does not know to a generic line. The Dictionaries page calls it before its delete confirm and opens
  the confirm even when the call fails or takes longer than five seconds.
- **`DELETE /dictionaries/dictionary/{name}/{key}` answers 409** when a parameter type lists the
  entry, before any write (no deleted mark is recorded); the problem document's `detail` names the
  types and the page's error toast carries it.
- **No dictionary call carries `lang`**: each entry's payload holds every language.
- Names and keys are URL-encoded everywhere, the per-dictionary JSON export included — a comma in a
  name would otherwise flip the backend into a multi-dictionary export.
- **The import is previewed, then chosen.** `POST /dictionaries/import-preview` (SYSADMIN, as the
  upload is) takes the file as the multipart part `file`, writes nothing, and answers JSON: `add`
  (the keys the deployment lacks: `dictionary`, `key`, `atonFilter`, `values` and `longValues` by
  language code, and `deleted` — the deployment records that an operator deleted the key),
  `overwrite` (the keys whose values differ: `dictionary`, `key`, `current` and `incoming`, each
  `{ atonFilter, values, longValues }`, and `basedOn`, `sha256:<hex>` of the row's values as the
  preview read them), `unchanged` (a count) and `refused` (`dictionary`, `key`, `reason`; an entry
  with no translation is refused, a blank value is not). `longValues` leaves out an empty long value,
  so a language in `incoming.values` with no `incoming.longValues` entry clears the row's long value.
  A malformed file answers 400 with the parser's message as the problem document's `detail`.
  `POST /dictionaries/upload-dictionaries` takes the same file plus two optional text fields, `add`
  and `overwrite`, each a JSON array: `add` of `{ "dictionary": …, "key": … }`, `overwrite` of
  `{ "dictionary": …, "key": …, "basedOn": … }` with `basedOn` optional. The job adds only the
  missing keys `add` names and overwrites only the keys `overwrite` names; an overwrite whose
  `basedOn` no longer matches the row is kept and logged as changed since the preview. An absent
  `overwrite` overwrites nothing; an absent `add` adds every missing key not recorded as deleted. The
  app always sends both, empty as `[]`, and `basedOn` wherever the preview gave one. A field that is
  not such an array answers 400 before any job starts, its reason in `detail`. An overwrite writes
  each language the file carries whole, value and long value (a long value the file lacks is
  cleared), keeps the other languages and replaces the AtoN filter. The answer is the job's start
  text, `Started batch job '…'`; a job that could not start answers 200 with `Error executing batch
job …` instead.

---

## Script resources — `/script-resources/*`

- **The import is previewed, then chosen.** `POST /script-resources/import-preview` (SYSADMIN, the
  class's role) takes the file as the multipart part `file`, writes nothing, and answers JSON, each
  list sorted by path: `add` (`path`, `type`, `content`, `listed` — the missing-path report lists
  the path), `overwrite` (`path`, `type`, `current` the deployment's row, `incoming` the file's,
  `basedOn` — `sha256:<hex>` of the row's content as the preview read it), `unchanged` (paths) and
  `refused` (`path`, `reason`: the backend's script check, the template check or a path it does not
  accept, reasons joined). Paths are compared case-insensitively, as `findByPath` and the template
  loader compare them. A malformed file answers 400 with the parser's message as the problem
  document's `detail`. `POST /script-resources/upload-script-resources` takes the same file plus two
  optional text fields, `add` and `overwrite`, each a JSON array: `add` of paths, `overwrite` of
  paths or `{ "path": …, "basedOn": … }`. The job adds only the missing paths `add` names and
  overwrites only the paths `overwrite` names; an overwrite whose `basedOn` no longer matches the
  row is kept and logged as changed since the preview, and a bare path overwrites as before. An
  absent `overwrite` overwrites nothing; an absent `add` adds every missing path. The app always
  sends both, empty as `[]`, and `{ path, basedOn }` wherever the preview gave a `basedOn`. A field
  of another shape answers 400 before any job starts, its reason in `detail`. The answer is the job's
  start text, `Started batch job '…'`; a job that could not start answers 200 with `Error executing
batch job …` instead.
- **History entries carry `source`**: `import` (an import, the bootstrap's included) or `editor`
  (the page's create and update); absent on entries written before it was recorded. `user` stays
  absent where none was recorded.

---

## Uploads

The shared uploader posts **one request per file**, each carrying a single part under the form field
name `file`, one at a time in pick order. Both of its targets accept that shape: `POST /messages/attachments/{editRepoPath}/{revision}`
iterates the file parts and answers JSON, and `POST /message-map-image/{editRepoPath}/{revision}` reads
`file` and answers `text/plain`. Both splice the repository path in **un-re-encoded**, for the reason
given under repository files above.

That one-part rule is the uploader's, not the API's: `POST /message-io/import` is multipart with two
parts (see Export and mail), and the script-resource and dictionary imports carry their `add` and
`overwrite` text fields beside the file (see Dictionaries and Script resources).

Upload **progress is not available**: Angular's fetch HTTP backend throws `NG02824` on
`reportUploadProgress`, and switching the whole app to the XHR backend for a percentage is not worth it
— the shared uploader shows an indeterminate bar (`ui-apps/admin/src/app/shared/components/file-upload/file-upload.ts`).
The uploader sets no headers of its own; auth and domain headers come from the interceptors.

---

## Firing schedules — `/firing-schedules/*`

`GET /firing-schedules/search-firing-area-periods?date=&lang=&query=&area=` returns every firing area
with its periods for **one day**; `date` is epoch milliseconds, `area` repeats once per selected
subtree, and `lang` drives the returned area descriptions. `PUT /firing-schedules/firing-area-periods?date=&lang=`
saves one area's periods for that day. `PUT /firing-schedules/update-firing-exercises` regenerates the
firing-exercise messages — the same job the server runs on an hourly schedule across every active
firing schedule, so it is a re-run rather than a one-way action ([features/schedule.md](features/schedule.md)).
It answers 200 when the run has finished, with `{ failedSchedules, failedAreas, failedCancellations }`
(all 0 for a clean run); each schedule runs under a lock, so a call during another run of the same
schedule waits for it. The `/firing-schedule/*` CRUD on the same resource is configuration and belongs to the
sysadmin area.

---

## Adding a call — checklist

1. Put it in the owning feature's `services/api/` adapter. Adapters are thin: URL, params, body shape,
   and the one or two normalisations the wire forces (see [architecture.md](architecture.md)).
2. Decide the `lang` question before writing the request — display only, or echoed back?
3. Check what "not found" looks like: a status, an empty list, or 200 with a `null` body.
4. If a date crosses the boundary, it is epoch ms in and normalised on the way out.
5. If the response is not JSON, set `responseType` — `text` for the several endpoints that answer
   `text/plain` or `text/html`.
6. If the user will download it, route it through `TicketedDownloadService`, never a hand-built URL.
7. Document the trap in a comment at the call site. The API is not versioned against this repository,
   and the comment is the only place a future reader will find out why the shape is odd.
