# docs.niord.org map

The application links out to the user manuals hosted at `https://docs.niord.org`. Which section id
opens which page and anchor is one map in code,
`ui-apps/admin/src/app/core/main-shell/help/help-urls.json`, the contract between the application
and the manuals: a guard here holds the code to the map, and the map is the file the guides' anchor
check reads. This file explains that contract, catalogues its keys, and records which chapter still
describes what the application does.

The manual pages are AsciiDoc output; their heading ids are the anchors. Their footers date them
individually: the editor manual is stamped 2017-05-30 and the admin manual 2017-06-06, and the
screenshots in those two show an older user interface — so in their sections, read "Matches" as "the
subject is still the same screen", not "the pictures are right". The sysadmin manual was last
refreshed 2023-11-22 and is the best-maintained of the three.

## How a screen finds its manual page

Three files do the work: `ui-apps/admin/src/app/core/main-shell/help/help-urls.json` is the map,
`help-url.service.ts` beside it resolves a section id to a URL through it (the map is exported there
as `HELP_URLS`), and `help-anchor.ts` renders the `?` link. A second renderer, the `?` in the top
navigation bar, uses the same service through
`ui-apps/admin/src/app/core/main-shell/header/services/help-menu.service.ts`.

| Surface                            | How its section is declared                                                                                                                                                                                                                                                                               |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A routed screen                    | `data: { help: { section: '<id>' } }` on the route. The service walks the active route tree and takes the **deepest** section, so a child overrides its parent and a child without one inherits.                                                                                                          |
| An admin or sysadmin section       | `helpSection` on the descriptor in `ui-apps/admin/src/app/features/admin/section-descriptors.ts`. `buildAdminAreaRoutes` copies it into the route data of the section route, its sub-routes and its trailing-param route, so one descriptor entry = one rail item = one card = one route = one help link. |
| A dialog or any non-routed surface | `<app-help-anchor section="<id>" />`. The explicit input wins over route data — the route underneath a modal still points at its own chapter.                                                                                                                                                             |
| The geometry editor                | Its `helpSection` input, defaulting to `position-editor`. No host overrides it, so the `?` inside the Areas and Charts geometry dialogs also lands on the editor manual's Position Editor chapter.                                                                                                        |

Resolution, in `urlForSection`:

- The id is looked up in `HELP_URLS`. **An unknown id resolves to nothing and the `?` disappears** —
  a missing entry costs a help affordance, never a broken page or an error. A section with no id at
  all (the SECOM section, below) shows no `?`.
- The path (`/editor-manual/manual.html#messages`) is put after `documentationUrl`, with the base's
  trailing slashes trimmed. Every path is relative to the base: the map has no off-site entries.
- With no `documentationUrl` at all, nothing resolves and **every** help affordance hides at once.

`documentationUrl` is the one piece of configuration: the environment file gives the build's value,
and the backend's `documentationUrl` web setting overrides it at runtime as an optional leaf of the
web-settings overlay, so repointing the whole help system at another host — a deployment's own copy
of the manuals — is a settings change, while the anchors stay fixed. See [platform.md](platform.md)
for the overlay itself.

### Where the keys live

In `help-urls.json`, and nowhere else: the same map in every build configuration, production
included, and in every country's build. Until it moved there, the map was the `helpUrls` block of
each environment file, which left a production build with five keys; `AppConfig.helpUrls` is now
deprecated and ignored, so a country file that still carries the block compiles and changes nothing
(`country-kit/API-CHANGES.md`, "The help paths are code"). The block is excluded from the
web-settings overlay as before, so its leaf names never collide with a setting's.

The map is JSON so that the guides' build can read it as it is: each value is a page of the guides
and an anchor that page must carry. `fe/scripts/help-urls.test.mjs` (`npm run test:guards`) holds
the code to it: it fails when a section id the code writes is not in the map, when a map key is used
nowhere, when a value is not a page and anchor, and when a section computed at runtime appears in a
place it does not know.

### Adding a help link

1. Pick the anchor in the guides. A target the map names needs an explicit id in the guides' source
   (`[[id]]`), so that rewording the heading does not move it.
2. Add the id to `help-urls.json`, with the page and anchor relative to the documentation base.
3. Declare the section: route `data`, a descriptor `helpSection`, or `section=` on the anchor, as a
   literal, which the guard reads. A section computed at runtime is listed in the guard's `COMPUTED`
   with where its ids come from.
4. Run `npm run test:guards`, and add the row to the key catalogue below.
5. Any spec that renders a component now hosting a help anchor must provide `APP_CONFIG` and a Router
   stub carrying both `events` and `routerState.snapshot.root` — the service reads both eagerly. See
   [testing.md](testing.md).

No country action is needed: the map reaches every country with the base product.

## Key catalogue

All 41 keys, each declared by a surface. Where the Anchor column names an anchor other than the
key, the guides carry that id; the ones the rewrite of the guides adds as explicit ids are marked
**new**.

### `editor-manual/manual.html`

| Key                              | Declared by                                                                 | Anchor                                                                            |
| -------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `home`                           | route `''`                                                                  | `home`                                                                            |
| `messages`                       | route `messages`                                                            | `messages` — the five view modes share it                                         |
| `message-details`                | message details dialog                                                      | `message-details`                                                                 |
| `message-tags`                   | tags dialog                                                                 | `message-tags`                                                                    |
| `print-messages`                 | print dialog                                                                | `print-messages`                                                                  |
| `e-mail-selection-action`        | mail dialog                                                                 | `e-mail-selection-action`                                                         |
| `report-action`                  | mailing-list reports dialog                                                 | `report-action`                                                                   |
| `sort-area-action`               | sort-area dialog                                                            | `sort-area-action`                                                                |
| `compare-messages`               | comparison dialog                                                           | `compare-messages` (the key was `compare-action`, an anchor the manual never had) |
| `import-action`                  | import dialog                                                               | `import-action`                                                                   |
| `update-selection-status-action` | status-update dialog                                                        | `update-selection-status-action`                                                  |
| `editor-start`                   | route `editor` (the start screen)                                           | `blank-editor-page`                                                               |
| `editor`                         | routes `editor/edit/:id`, `editor/edit`, `editor/copy/:id[/:referenceType]` | `editor`                                                                          |
| `status-editor-page`             | route `editor/status/:id`                                                   | `status-editor-page`                                                              |
| `comments-editor-page`           | route `editor/comments/:id`                                                 | `comments-editor-page`                                                            |
| `history-editor-page`            | route `editor/history/:id`                                                  | `history-editor-page`                                                             |
| `message-template-execution`     | route `editor/execute-template`                                             | `message-template-execution`                                                      |
| `position-editor`                | geometry editor default                                                     | `position-editor`                                                                 |

### `admin-manual/manual.html`

| Key                      | Declared by                                                                                                | Anchor                                                                                         |
| ------------------------ | ---------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `admin-manual`           | the area overview route                                                                                    | `admin-page` (was the manual root, with no anchor)                                             |
| `charts`                 | section descriptor                                                                                         | `charts`                                                                                       |
| `areas`                  | section descriptor                                                                                         | `areas`                                                                                        |
| `publications`           | section descriptor (covers the series list, `publications/:seriesId` and `publications/one-off/:seriesId`) | `publications`                                                                                 |
| `publication-categories` | section descriptor                                                                                         | `publication-categories` — **new**; the published manual has only `publication-categories-tab` |
| `sources`                | section descriptor                                                                                         | `sources`                                                                                      |
| `users-contacts`         | section descriptor                                                                                         | `users-contacts`                                                                               |
| `mailing-lists`          | section descriptor                                                                                         | `mailing-lists`                                                                                |

### `sysadmin-manual/manual.html`

| Key                        | Declared by             | Anchor                                                                                  |
| -------------------------- | ----------------------- | --------------------------------------------------------------------------------------- |
| `sysadmin-manual`          | the area overview route | `sysadmin-page` (was the manual root, with no anchor)                                   |
| `message-series`           | section descriptor      | `message-series`                                                                        |
| `domains`                  | section descriptor      | `domains`                                                                               |
| `promulgation-types`       | section descriptor      | `promulgation-types`                                                                    |
| `firing-schedules`         | section descriptor      | `firing-schedules`                                                                      |
| `firing-exercise-schedule` | route `schedule`        | `firing-exercise-schedule`, the editor manual's own chapter on the Firing Area Schedule |
| `dictionaries`             | section descriptor      | `dictionaries`                                                                          |
| `parameter-types`          | section descriptor      | `parameter-types`                                                                       |
| `script-resources`         | section descriptor      | `script-resources`                                                                      |
| `categories`               | section descriptor      | `categories`                                                                            |
| `print-reports`            | section descriptor      | `print-reports`                                                                         |
| `mail-queue`               | section descriptor      | `mail-queue`                                                                            |
| `integration`              | section descriptor      | `integration` — but see the reconciliation below                                        |
| `settings`                 | section descriptor      | `settings`                                                                              |
| `batch-jobs`               | section descriptor      | `batch-jobs`                                                                            |

The SECOM section (`/sysadmin/secom`) has no key and shows no help link: nothing about SECOM or
S-124 is in the guides yet, since neither is in production and the standards are still changing.
Its key comes back with its chapter.

## Reconciliation

| Marker                      | Meaning                                                                                |
| --------------------------- | -------------------------------------------------------------------------------------- |
| **Matches**                 | The chapter's subject is a screen this application has, and its substance still holds. |
| **Needs rewrite**           | The screen exists, but the chapter describes a different flow, location or mechanism.  |
| **Not in this application** | The chapter documents a feature this application does not have.                        |

### Editor manual

| Chapter                                                 | Application surface          | Marker                                                                                                                                                                                                                                                      | Source in `docs/`                                                                    |
| ------------------------------------------------------- | ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Introduction                                            | —                            | Matches                                                                                                                                                                                                                                                     | [../README.md](../README.md)                                                         |
| Editors Manual (domains, the four hierarchical roles)   | domain switcher, role gating | Matches                                                                                                                                                                                                                                                     | [features/shell-and-auth.md](features/shell-and-auth.md)                             |
| Home                                                    | `/` landing page             | Matches                                                                                                                                                                                                                                                     | [features/shell-and-auth.md](features/shell-and-auth.md)                             |
| Home › Out-of-Date Warning                              | the stale-build banner       | **Needs rewrite** — the chapter describes an HTML5 application cache and tells the user to clear the browser cache; the application detects a redeployed bundle and the remedy is a reload                                                                  | [features/shell-and-auth.md](features/shell-and-auth.md), [platform.md](platform.md) |
| Home › Login, Domain Selection, Language Selection      | header menus                 | Matches                                                                                                                                                                                                                                                     | [features/shell-and-auth.md](features/shell-and-auth.md)                             |
| Messages (view modes, filters, actions, details, tags)  | `/messages`                  | Matches                                                                                                                                                                                                                                                     | [features/messages.md](features/messages.md)                                         |
| Editor (fields, status/comments/history pages, actions) | `/editor/**`                 | Matches — the optional-field set is driven by the backend editor-fields configuration, which the chapter does not mention                                                                                                                                   | [features/editor.md](features/editor.md)                                             |
| Editor › Blank Editor Page                              | `/editor` start screen       | Matches; the start screen's `?` opens it (key `editor-start`)                                                                                                                                                                                               | [features/editor.md](features/editor.md)                                             |
| Position Editor                                         | the shared geometry editor   | Matches, except the documented **subtract geometries** action: set operations (subtract, intersect, union) are deliberately not offered                                                                                                                     | [ui-standards.md](ui-standards.md), [features/editor.md](features/editor.md)         |
| Message Template Execution                              | `/editor/execute-template`   | **Needs rewrite** — the chapter describes a template-selection dialog plus an execution dialog; the application has a full page reached from the start screen's template card or the in-editor launcher, which hands the composed message to the edit route | [features/editor.md](features/editor.md)                                             |

### Admin manual

| Chapter                                                                            | Application surface             | Marker                                                                                                                                                                                                     | Source in `docs/`                                            |
| ---------------------------------------------------------------------------------- | ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| Introduction, Administrators Manual                                                | —                               | Matches                                                                                                                                                                                                    | [features/shell-and-auth.md](features/shell-and-auth.md)     |
| Admin Page                                                                         | `/admin` overview               | Matches — rail plus launcher cards; the chapter's warning about per-domain admin rights over shared base data still applies                                                                                | [features/admin.md](features/admin.md)                       |
| Charts                                                                             | `/admin/charts`                 | Matches                                                                                                                                                                                                    | [features/admin.md](features/admin.md)                       |
| Areas                                                                              | `/admin/areas`                  | Matches                                                                                                                                                                                                    | [features/admin.md](features/admin.md)                       |
| Publications (categories tab, templates tab, publications tab, recording, release) | `/admin/publications`           | **Needs rewrite** — series and issues replace templates, recording and the release chain; the whole chapter is a different model                                                                           | [publications/domain-rules.md](publications/domain-rules.md) |
| Publications › Publication Categories tab                                          | `/admin/publication-categories` | **Needs rewrite** — categories are a section of their own, and the key `publication-categories` needs a chapter-level anchor to point at                                                                   | [publications/domain-rules.md](publications/domain-rules.md) |
| Sources                                                                            | `/admin/sources`                | Matches                                                                                                                                                                                                    | [features/admin.md](features/admin.md)                       |
| Users & Contacts                                                                   | `/admin/users`                  | Matches                                                                                                                                                                                                    | [features/admin.md](features/admin.md)                       |
| Mailing Lists (recipients only; defers the rest to the sysadmin manual)            | `/admin/mailing-lists`          | **Needs rewrite** — one section holds the whole feature, with mutations restricted to sysadmins and recipient management open to admins; the split across two manuals no longer matches a split of screens | [features/admin.md](features/admin.md)                       |

### Sysadmin manual

| Chapter                                                   | Application surface                                                   | Marker                                                                                                                                                                                                                                                                   | Source in `docs/`                                                                                          |
| --------------------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Introduction, System Administrators Manual, Sysadmin Page | `/sysadmin` overview                                                  | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Message Series                                            | `/sysadmin/series`                                                    | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Domains                                                   | `/sysadmin/domains`                                                   | Matches — including the create-the-client-in-Keycloak action. Worth adding: a domain's firing-schedule capability is backend-derived, is no domain field on either side, and is what gates the Schedule nav item — see [features/schedule.md](features/schedule.md)      | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Promulgation Types                                        | `/sysadmin/promulgation`                                              | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Firing Schedules                                          | `/sysadmin/schedules`                                                 | Matches for the schedule definition itself                                                                                                                                                                                                                               | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Firing Schedules › Maintain Firing Exercise Schedule      | `/schedule`                                                           | **Needs rewrite** — the editor-facing schedule is a top-level area gated by roles and the domain's firing-schedule capability, not a sub-page inside one domain. The screen's `?` opens the editor manual's `#firing-exercise-schedule` (key `firing-exercise-schedule`) | [features/schedule.md](features/schedule.md)                                                               |
| Dictionaries                                              | `/sysadmin/dictionaries`                                              | Matches — worth adding that the `web` and `message` dictionaries override interface text at runtime                                                                                                                                                                      | [features/sysadmin.md](features/sysadmin.md), [platform.md](platform.md)                                   |
| Parameter Types                                           | `/sysadmin/param-types`                                               | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Script Resources                                          | `/sysadmin/script-resources`                                          | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Categories (including templates)                          | `/sysadmin/categories`                                                | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Print Reports                                             | `/sysadmin/reports`                                                   | Matches; the publication document pipeline consumes these reports                                                                                                                                                                                                        | [features/sysadmin.md](features/sysadmin.md), [publications/domain-rules.md](publications/domain-rules.md) |
| Mail Queue                                                | `/sysadmin/mails`                                                     | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Integration (three deployment-specific import panels)     | `/sysadmin/niord-integration`                                         | **Not in this application** — the chapter's three import panels were not carried over. The screen behind this key is a different feature: a cross-domain list of server-to-server pull-integration records, shown only when a backend setting enables the section        | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Settings                                                  | `/sysadmin/settings`                                                  | Matches — and this is where `documentationUrl` is edited, which repoints every help link                                                                                                                                                                                 | [features/sysadmin.md](features/sysadmin.md), [platform.md](platform.md)                                   |
| Batch Jobs                                                | `/sysadmin/batch`                                                     | Matches                                                                                                                                                                                                                                                                  | [features/sysadmin.md](features/sysadmin.md)                                                               |
| Mailing Lists (full editor, triggers, recipients)         | `/admin/mailing-lists`                                                | **Needs rewrite** — the screen lives in the admin area; only the mutating half is sysadmin-only                                                                                                                                                                          | [features/admin.md](features/admin.md)                                                                     |
| Keycloak Integration                                      | no screen — background only, plus the create-client action in Domains | Matches as background                                                                                                                                                                                                                                                    | [features/shell-and-auth.md](features/shell-and-auth.md), [features/sysadmin.md](features/sysadmin.md)     |

### Development guide (`dev-guide-v3/guide.html`)

The guide is written for the backend project and its build. The current guide is v3; `dev-guide-v2`
is kept on the index as an archive entry.

| Chapter                                                                          | Marker                                                                                                                                                                                                                 | Source in `docs/`                                                                                                                                                                                                                                                                                                            |
| -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Introduction                                                                     | Matches (system overview)                                                                                                                                                                                              | [../README.md](../README.md)                                                                                                                                                                                                                                                                                                 |
| Java Coding Conventions                                                          | Not in this repository — backend                                                                                                                                                                                       | —                                                                                                                                                                                                                                                                                                                            |
| Webapp Coding Conventions (source organisation, application cache, overlay wars) | **Needs rewrite** — none of the three sub-sections describes this front end: the source layout is different, there is no application cache, and country differences are build configuration rather than a file overlay | [architecture.md](architecture.md), [angular-conventions.md](angular-conventions.md), [typescript-conventions.md](typescript-conventions.md), [coding-standards.md](coding-standards.md), [ui-standards.md](ui-standards.md), [styling-reference.md](styling-reference.md), [country-deployments.md](country-deployments.md) |
| Niord Development Set-Up                                                         | Matches for the backend; the front end's own set-up has no chapter                                                                                                                                                     | [../README.md](../README.md)                                                                                                                                                                                                                                                                                                 |
| Niord-Proxy Development Set-Up                                                   | Not in this repository — separate project                                                                                                                                                                              | —                                                                                                                                                                                                                                                                                                                            |
| Building Niord App as a Docker Container                                         | **Needs rewrite** for the front end, which builds to a static bundle and deploys on its own, not inside the application container                                                                                      | [../README.md](../README.md), [platform.md](platform.md)                                                                                                                                                                                                                                                                     |

### Integration documents

| Document                                                | Marker                                                                                                 | Source in `docs/`                                                                                          |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Niord Model (`model/model.html`)                        | Matches for messages and the GeoJSON extensions; the publications entities are newer than the document | [backend-api-notes.md](backend-api-notes.md), [publications/DATA-MODEL.md](publications/DATA-MODEL.md)     |
| Niord Public API (`public-api/api.html`)                | **Needs rewrite** — the public publication surface changed with the series and issue model             | [publications/API-CONTRACT.md](publications/API-CONTRACT.md), [backend-api-notes.md](backend-api-notes.md) |
| Message Presentation (`presentation/presentation.html`) | Matches — guidance for any client rendering messages, which this application is                        | [map-portrayal.md](map-portrayal.md)                                                                       |

### Screens with no chapter of their own

- **The Firing Area Schedule area.** Covered only as an `h4` inside a sysadmin chapter, which its
  `?` opens; the people who use it daily read the editor manual. See
  [features/schedule.md](features/schedule.md).
- **The Areas and Charts geometry dialogs.** Both embed the shared geometry editor without
  overriding `helpSection`, so their `?` sends an administrator to the editor manual's Position
  Editor chapter. Either the hosts pass a `helpSection` or the admin manual gains an anchor.
- **The Integration section.** The application's screen manages server-to-server pull integrations
  and is hidden unless a backend setting enables it; the chapter behind its `?` describes unrelated
  import panels.
- **Publication categories.** A section of its own, documented only as a tab of another chapter;
  its key points at `publication-categories`, which the rewrite of the guides adds.
- **SECOM.** Not in the guides yet, so the section has no key and no `?`.

## What a documentation pass should fix first

1. Every anchor `help-urls.json` names, as an explicit id in the guides' source, so a reworded heading
   cannot move it: `publication-categories`, `admin-page` and `sysadmin-page` are among them.
2. The Publications chapter, which describes a model the application no longer has. It is the largest
   single divergence and the feature currently going live; [publications/domain-rules.md](publications/domain-rules.md)
   is the source.
3. The Firing Area Schedule area, whose key `firing-exercise-schedule` pointed at a heading inside a
   sysadmin chapter: done, the screen has a chapter of its own in the editor manual
   (`#firing-exercise-schedule`), and the key points at it.
4. Mailing Lists, which needs to be one chapter describing one screen with two permission levels.
5. Template Execution, Out-of-Date Warning, and the Integration chapter, each describing a mechanism
   the application does not use.
