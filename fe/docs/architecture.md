# Architecture

How the code is organised, which direction dependencies run, and the seams that let the
application grow without new coupling. Angular idiom (signals, `httpResource`, control flow,
Transloco usage) is in [angular-conventions.md](./angular-conventions.md); bootstrap order,
configuration, build configurations and the service worker are in [platform.md](./platform.md);
component and styling rules are in [ui-standards.md](./ui-standards.md).

The application is a standalone-component, zoneless, signal-first Angular app. Its only UI
dependencies are `@angular/cdk` and `@angular/aria` (behaviour), `@ng-icons/lucide` (icons),
`ol` (maps), `@tiptap/*` (rich text), `@codemirror/*` (code) and `@turf/buffer` (geometry).
Do not add a UI component library: the shared wrappers in `ui-apps/admin/src/app/shared/` are the component
foundation, and a second library would fork the styling, accessibility and translation rules.

## The four layers

| Layer                                     | Owns                                              | May import                               |
| ----------------------------------------- | ------------------------------------------------- | ---------------------------------------- |
| `ui-apps/admin/src/app/core/`             | App-wide infrastructure, one instance per app     | `shared/`                                |
| `ui-apps/admin/src/app/shared/`           | Reusable building blocks with no domain knowledge | `core/`                                  |
| `ui-apps/admin/src/app/features/`         | Domain areas, lazily routed                       | `core/`, `shared/`, `features/messages/` |
| `ui-apps/admin/src/app/country-specific/` | The neutral defaults a country's files replace    | `core/`, `shared/`, `features/`          |

### `core/`

Singletons the whole application depends on: `auth/` (Keycloak session, roles, guards, one-time
download tickets), `config/` (`APP_CONFIG` and the runtime settings overlay), `domain/` (the
tenant the user works in, its capabilities and time zone), `i18n/` (translation loading and the
runtime dictionary overrides), `interceptors/` (auth token, domain header), `guards/` (old
bookmark redirect), `main-shell/` (header, footer, menus, help anchors, title strategy, language
and locale services), `notifications/` (the toast store) and `version/` (deploy-skew banner).

`core/` holds no feature screens and no domain rules. Global error listeners are registered in
`ui-apps/admin/src/app/app.config.ts`, not in `core/` — see [coding-standards.md](./coding-standards.md) for
the error policy.

### `shared/`

Twelve buckets, no barrel files — everything is imported by deep relative path (the one deliberate
barrel is the country API, below, and only country code imports it):
`api/` (HTTP helpers such as `LangHttpHelper`), `components/`, `dialogs/`, `directives/`,
`forms/`, `icons/`, `models/`, `pipes/`, `services/`, `testing/`, `utils/` (and a single-file
`util/` that is an accident, not a category — put new helpers in `utils/`).

`shared/` carries no feature screens and no feature services. `shared/models/` is the exception by
design: the wire models plus the domain vocabularies and rules every area agrees on — message
status and its allowed transitions (`message-status-transitions.ts`), main types, tag-type icons.
A shared component that needs feature-owned data takes it through an injection token
(see [Extension points](#extension-points)).

### `features/`

Five areas, each lazily loaded from `ui-apps/admin/src/app/app.routes.ts`:

| Folder      | Area                                                                     |
| ----------- | ------------------------------------------------------------------------ |
| `home/`     | The public landing page and its map                                      |
| `messages/` | Message search, list/grid/map/details views, tags, filters — and the hub |
| `editor/`   | Message authoring, templates, promulgation editing                       |
| `schedule/` | The firing-area schedule                                                 |
| `admin/`    | Both administration areas: `/admin` and `/sysadmin`                      |

A feature owns its routes, its components, its facades and its API services. Feature behaviour
is documented per area under [docs/features/](./features/).

### `country-specific/`

`default/` holds the neutral files a build may replace: `language.config.ts` (the configured
language list and its order), `country.providers.ts` (`provideCountry()`: the country's hook
registrations — a renderer and an editor for a promulgation service of its own, say — and its
component slots), `country.routes.ts` (the country's own pages, loaded lazily after every base
route) and `country.menu.ts` (their main-menu entries); the last two are empty. A country's set-up
replaces each default for which its repository has a file at the seam's path, and the brand-token
stylesheet with its own; nothing else varies.
See [country-deployments.md](./country-deployments.md).

**The country API.** Country code lives in a country repository: it imports the base product only
through `@niord/admin/api` (`ui-apps/admin/src/public-api.ts`) and, in specs,
`@niord/admin/api/testing` (`ui-apps/admin/src/testing-api.ts`), and otherwise stays inside its own
folders; the public site has the same pair, `@niord/info/api` and `@niord/info/api/testing`. Each
application's pair is its one deliberate barrel: a list of `export { … } from`
statements, each under a contract comment, of exactly what a country uses — the hook tokens, the slot
registry, the types its seam files are written against, and the few services, pipes and functions
its components need. Each export is a promise kept across versions (`country-kit/API-CHANGES.md`).
Base product code never imports them, so the barrel can neither close an import cycle nor become a
shortcut between the base product's own folders. Lint enforces both directions: the country lint
(`country-kit/lib/country-lint.cjs`, applied by the kit's generated lint configuration) holds
country code to the API, and `eslint.config.js` bans the API everywhere in the base product.

## Import boundaries

The boundaries are enforced by `no-restricted-imports` blocks in `eslint.config.js`, not by
convention:

| Code under                                                                | Must not import                                 |
| ------------------------------------------------------------------------- | ----------------------------------------------- |
| `ui-apps/admin/src/app/shared/**`                                         | `features/**`                                   |
| `ui-apps/admin/src/app/core/**`                                           | `features/**`                                   |
| `features/admin`, `features/editor`, `features/home`, `features/schedule` | each other                                      |
| `features/messages/**`                                                    | `admin`, `editor`, `home`, `schedule`           |
| the whole base product                                                    | `@niord/admin/api`, `@niord/info/api`           |
| a country's files, in its repository                                      | the base product but through `@niord/<app>/api` |

The last row is the country lint's rule (`niord-country/imports`, from `country-kit/lib/country-lint.cjs`),
which the kit's generated lint configuration applies in a country's workspace and which resolves
each relative path against the country's folders; see [The country API](#country-specific).

The feature-to-feature blocks pair a `**/features/**` glob with a `^(\.\./)+(…)/` regex, because
the sibling-relative spelling `../editor/…` carries no `features/` path segment and the glob alone
cannot see it. The `shared/` and `core/` blocks need only the glob — an import from there into a
feature always contains a literal `features/` segment. Add both forms when you add a sibling
boundary.

**`features/messages/` is the one sanctioned hub.** The other four features build on it — the
`Message` model, the message API services, the map layers, the details dialog — and the
dependency is one-way: `messages/` must never import a feature that depends on it. When a
`messages/` primitive proves broadly reusable, promote it into `shared/` rather than widening
the hub.

When `shared/` or `core/` needs something a feature owns, **invert the dependency with an
injection token**. That is the only sanctioned escape, and the existing tokens are the pattern
to copy.

## Data flow: API service → facade → component

1. **API service** (`*-api.service.ts`, `providedIn: 'root'`) owns the HTTP call _and_ the
   wire↔model mapping. Wire shapes are file-private `*Vo` interfaces; the exported types are the
   UI models. There is no separate adapter layer to go looking for. Backend contract quirks belong
   in a doc comment on the service — see [backend-api-notes.md](./backend-api-notes.md).
   `ui-apps/admin/src/app/features/messages/services/api/sources-api.service.ts` is the reference shape.
2. **Facade** turns that into signals for one screen: `loading`, `error`, the rows, and the
   mutation methods that refetch afterwards. Facades are `@Injectable()` **without**
   `providedIn: 'root'` and are provided by the component or route that owns the screen, so the
   state dies with the screen. The exceptions are deliberate and few: `AuthFacade`, `DomainFacade`
   and the four messages facades that outlive a single view mode
   (`ui-apps/admin/src/app/features/messages/services/{message-list,messages-filter,messages-shell,named-filters}.facade.ts`).
3. **Component** renders signals. A component or a shared wrapper that finds itself calling an
   API or reshaping DTOs is a layering smell — push it down.
4. **Writes** map the form model back to a DTO explicitly, then refetch through the facade.

Three rules make this hold up in practice:

- **Which GET mechanism** — reactive data-load GETs use `httpResource`, imperative
  fetch-on-action GETs stay on `HttpClient`, and `rxResource` is reserved for the three cases
  that need a `timer()`/debounce or a fan-out inside the resource. The `hasValue()` guard is
  mandatory. Full rule and idioms: [angular-conventions.md](./angular-conventions.md).
- **Gate a fetch on the view that renders it**, and put the gate _inside_ the trigger so no
  request is issued — a template `@if` only hides the result. Where the rows must survive the
  gate, complete with `EMPTY` rather than emitting an empty value, which would blank the table
  on the way back. Worked example with the reasoning inline:
  `ui-apps/admin/src/app/features/admin/sections/admin/publications/series-detail.facade.ts`.
- **Refetch on language change.** The backend translates payloads per request via `lang=`, so any
  facade reading server-translated data includes the displayed language in its trigger key —
  a language switch then trips the same refresh path a filter change does. This is the page's
  `ModelLanguageSelection.value()` for admin catalogues with a model-language picker, and
  `LanguageService.selectedLanguage()` for views following the UI language.
  `ui-apps/admin/src/app/features/admin/sections/admin/sources/sources.facade.ts` is the small model-language version;
  `ui-apps/admin/src/app/features/messages/services/message-list.facade.ts` the debounced one.

## State

Signals are the default for everything local and derived. Shared application state lives in
signals inside a facade, and only when it is genuinely shared.

**No server data is held beyond the view that shows it.** There is no client-side cache, store
or catalogue service keeping an HTTP result alive across components, pages or areas — and
therefore nothing anyone has to remember to invalidate. A cache is correct only while every writer
remembers to invalidate it, and most admin catalogues can also be rewritten by a backend import job,
where no frontend writer exists at all. A fetch added to a component is correct by construction,
and another user's edit or an administrator's change can never leave a picker silently stale for
longer than the view that read it.

The mechanism is **component lifetime = data lifetime**: every fetch lives in the component (or
its per-instance facade) that renders the result, runs when that component is created and after
its own writes, and dies with it. Most drawer editors gate their content on the model
(`[visible]="model() !== undefined"` on the drawer, `@if (model())` around its body), so the
content is created when a record is opened and torn down when the model clears —
`admin/sections/admin/sources/source-editor.html` is the reference shape. A screen whose drawer
holds heavy per-open state additionally keeps an explicit `…Mounted` signal, set when the drawer
opens and cleared on `(hidden)`, so the editor is destroyed rather than merely emptied
(`admin/sections/admin/publications/publications-page.ts` and `one-off-detail.ts` are the
examples). Either way the drawer's two close events differ and the rule depends on the
distinction: `closed` clears the model and starts the slide-out animation, `hidden` fires once the
animation has finished.

Two things this does not forbid: a facade keeping its own rows visible while its own re-read of
the same thing is in flight (replaced when the answer lands, dropped if the read fails), and a
router-state seed that the following GET replaces.

Reviewer check: grep the area for a service or component holding an HTTP result that outlives the
view showing it, and for any fetch in a component that is mounted while not visible.

## Forms

Edit flows use **Signal Forms** (`@angular/forms/signals`) exclusively. Reusable controls are
value-model custom inputs implementing `FormValueControl`. Most live in `ui-apps/admin/src/app/shared/forms/`;
a control only one area needs stays with that area (`features/admin/components/`,
`features/messages/components/`, `core/auth/components/`, `core/domain/components/`).
Save is gated by a computed over net change, validity and in-flight state. Every schema constraint
must be bound with `[formField]` so the constraint actually reaches the control that carries it.
Details and traps: [angular-conventions.md](./angular-conventions.md) and
[ui-standards.md](./ui-standards.md).

## Extension points

### Promulgation registries

A promulgation channel is a plugin, registered through three symmetric multi-provider tokens:

| Token                        | Declared in                                      | Renders                        |
| ---------------------------- | ------------------------------------------------ | ------------------------------ |
| `PROMULGATION_RENDERERS`     | `features/messages/promulgation/`                | The read-side view             |
| `PROMULGATION_EDITORS`       | `features/editor/promulgation/`                  | The editor form                |
| `PROMULGATION_ADMIN_PLUGINS` | `features/admin/sections/sysadmin/promulgation/` | The type-configuration section |

Rules that hold for all three:

- **Dispatch is by `serviceId`**, never by the payload's class discriminator.
- **An unregistered `serviceId` renders nothing.** That is a first-class case, not an error — it
  is what lets a deployment ship a channel the core build knows nothing about.
- **Register at the root injector only** (`ui-apps/admin/src/app/app.config.ts`). Multi-providers do not merge
  across injector levels, so a route- or component-level provider would silently shadow the
  root registrations instead of adding to them.

Country builds contribute renderers and editors additively through `provideCountry()` in their
`country.providers.ts`, which is empty in the default build: `PROMULGATION_RENDERERS` and
`PROMULGATION_EDITORS` are the two country hooks the country API exports. `PROMULGATION_ADMIN_PLUGINS`
is not: core registers the admin plugins and the token is not exported, so a country's own service
has no admin section; one would need a new seam
([country-deployments.md](./country-deployments.md) → _Configuration and behaviour_).

### Tokens for shared controls that need feature data

`shared/` cannot import `features/`, so the data comes in through a token the feature binds:

| Token                      | Default when unbound                   | Bound by                              |
| -------------------------- | -------------------------------------- | ------------------------------------- |
| `MESSAGE_TAGS_DATA_SOURCE` | Root factory returning empty results   | The messages route                    |
| `AREA_GEOMETRY_SOURCE`     | Unbound — the "From area" tab hides    | `ui-apps/admin/src/app/app.config.ts` |
| `TAG_SELECTION_STORE`      | Root stub (declared in `core/domain/`) | The messages route                    |

A root default that no-ops rather than throws is deliberate: the control still injects in a test
fixture or in a feature that never binds the real implementation. Copy this pattern rather than
reaching across a boundary.

One token is declared but unbound: `MESSAGE_TAGS_DIALOG_OPENER` resolves to its root no-op
everywhere, so the manage-tags affordance on `<app-message-tags-field>` does nothing outside a
host that provides a real opener. `MessageTagsDialogService` implements the interface but is not
registered against the token — wiring it is a [backlog](./backlog.md) item, not a described
behaviour.

## The admin area shell

Both administration areas run on one shell. `ui-apps/admin/src/app/features/admin/section-descriptors.ts`
declares `ADMIN_AREA` and `SYSADMIN_AREA` as lists of sections (path, label key, description key,
icon, help section), and `buildAdminAreaRoutes()` in `area-routes.ts` projects a descriptor into
shell + overview + one child route per section. **One descriptor entry is one rail item, one
overview card and one route**, so they cannot drift apart.

Adding a section is a descriptor entry plus a `loadComponent` override keyed by the section path
in `admin.routes.ts` / `sysadmin.routes.ts`. A section with no override registers a stub at its
final URL, so the deep link is stable before the screen exists.

An override may carry more than a loader:

- **`paramSegment`** registers `${path}/:${param}` on the _same_ component (`publications/:seriesId`).
- **`subRoutes`** add a sibling screen with its own component under the same section
  (`publications/one-off/:seriesId`). They carry the section's route `data` — so the rail stays
  highlighted and the help anchor resolves — but not its `canDeactivate` guard, which belongs to
  the section's own component.
- **`canDeactivate`** guards the section against leaving with unsaved edits.

Every route `buildAdminAreaRoutes` makes — the section, its sub-routes and its param child — carries
`drawerUnsavedChangesGuard`, so the rail, browser Back or a link out of a section asks while a drawer
there holds changes. The shell route would not do: moving between sections deactivates only the
section routes. Where the override has its own guards, one combined guard runs the drawer guard
first and the own guards after it, in turn, each only when the one before said yes. A plain array
would not do: the router calls all the guards of an array at once, and two that both ask would both
open the discard dialog, which has one slot — the second replaces the first, whose answer never
comes, and the navigation hangs with no dialog on screen.

Two traps live here:

- **Route order.** Sub-routes must be registered before the param child, which matches any single
  segment and would otherwise swallow the sub-route's literal first segment. `buildAdminAreaRoutes`
  already orders them; keep it that way.
- **Param routes are siblings, not children.** The list navigates with
  `router.navigate([id], { relativeTo: route })` and the detail goes back with `['..']`. A
  relative `['..', id]` resolves one level too high and matches nothing. Deep-link-only e2e specs
  do not catch this — every master/detail section needs one click-through test
  (`ui-apps/admin/e2e/admin-publications.spec.ts` guards it explicitly).

**Section gates are visibility, not protection.** A descriptor may carry `settingGate`, a key of
the anonymous `/settings/web` map: a `web: true` setting (`adminIntegrationPageEnabled`) or a value
the backend computes from its running properties (`secomEnabled`). `SectionGatesService` (provided
at the area shell) reads that map once on mount, when the area has a gated section, and resolves
every distinct key from it; the rail entry and overview card appear only when the key resolves
truthy (`true` or `'true'`), and stay hidden before resolution, on a fetch error, or when the key is
missing. The route is always registered, so a deep link always resolves.

**The shape of a section.** Almost every non-tree section is the same: a client-filtered,
client-paged list (`features/admin/utils/client-pager.ts`) inside `<app-admin-section-page>`
chrome, a create/edit drawer editor, a confirm-delete, and a cog menu carrying export/import where
the backend has those endpoints. The drawer stays in the template and its content is gated on the
model — that is where the data-lifetime rule becomes visible (see [State](#state)). A section that drills into a
record which draws its own header sets `[showHeader]="false"` while that record is open, so only
one header per level is on screen.

**The master-detail tree scaffold** carries the hierarchical sections (Areas, Categories) and is
deliberately admin-local rather than in `shared/`:

| Piece                                               | Role                                                                                   |
| --------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `components/master-detail-tree-admin/`              | Presentational shell: toolbar + two-pane grid, editor via `ng-content`                 |
| `shell/hierarchical-admin-page.base.ts`             | Controller base: selection, dirty state, move/delete/reparent, keyed off a `keyPrefix` |
| `shell/hierarchical-tree-state.ts`                  | Reactive tree loading: roots, refetch on language change and on `refresh()`            |
| `shell/hierarchical-admin-unsaved-changes.guard.ts` | The leave guard, registered on both routes                                             |

Call `hierarchicalTreeState(...)` from a facade **field initialiser**, not from a base class — the
closure has to capture the subclass's already-initialised API field. Reuse the scaffold for any
future hierarchical CRUD tree.

**Naming.** Section pages live under `features/admin/sections/<admin|sysadmin>/<path>/`, with
shared machinery at the admin module root. View components are named by role —
`<entity>-editor`, `<entity>-details`, `<entity>-picker` — never by presentation mechanism, so a
screen can change surface without a rename. Mechanism words belong only to shared primitives whose
role _is_ the mechanism (`app-drawer`).

Section behaviour is documented in [features/admin.md](./features/admin.md) and
[features/sysadmin.md](./features/sysadmin.md).

## The map layer

Every map surface in the application is one component: `<app-ol-map>`. It lives with the rest of
the map kit in `shared-map/`, a folder that belongs to neither application and that both compile —
the public site draws the same maps, and one source is what keeps them the same. Layers are
content-projected components that register themselves with `MapShellService`; the switcher and the
coordinate readout read the same shell.

**What the kit holds, and how to import it.** `ol-map`, `map-shell.service`, `tile-load-gate`,
`ol-layer-switcher`, `map-projection.service`, `map-styles`, `hit-detection` (what a pointer is
on: the hit tolerance, and the unfilled surfaces it lies inside), `map-hover` (when a hover is
resolved and when it ends), `css-tokens`, `tile-sources` (the deployment's tile addresses, below)
and `layers/{ol-tile-layer, ol-openseamap-layer, ol-wms-layer}`, plus the portrayal definition
under `shared-map/portrayal/` — `portrayal.json`, which `map-styles` imports and so bundles, and the
S-124 symbol files beside it, which each application's build copies into its own
`assets/portrayal/`. The alias `@niord/map/*` names the folder, and a call site names the file it
wants — `import { OlMap } from '@niord/map/ol-map'`. There is no barrel on purpose: one would re-export the
whole kit, so importing the map shell would drag the WMS layer and the switcher into the same chunk.
The country APIs are the one exception to the no-barrel rule, and for country code alone (see
[`country-specific/`](#country-specific)); a country file does not import `@niord/map/*` at all.

**How a message is drawn is the kit's too.** `map-styles` holds `styleForMessageFeature()` — the
IHO S-124 portrayal — and the numbered coordinate pin and its name label, so both applications draw
a warning with the same marks and a mariner reads the same chart whichever is in front of them.
Every portrayal value it draws with — the colours, the symbols and their anchors, the sizes, the
pen, and which style a message gets — comes from `portrayal/portrayal.json`, the one definition,
which the backend's map-image renderer reads as well; `map-styles` holds only the rules that turn
it into OpenLayers styles. It takes what an application knows through its
`MessageFeatureStyleContext` (outline mode, selection, hover, whether the halos are enabled at all)
and reads the feature's own `mainType`, `messageType`, `parentFeatureIds` and co-location properties
off the OpenLayers feature. Its `MainType` is the kit's own two-literal union; the administration
application's model keeps its own spelling of it and the two need no conversion. As with the
message-HTML classes below, the backend holds the two sides together: Maven copies
`shared-map/portrayal/` into its base library, and its `PortrayalDefinitionTest` and
`MapImagePlannerTest` fail on a definition, a symbol or a golden fixture its renderer cannot use,
so a change to the folder runs Backend CI as well as both applications' checks. The rules
themselves are in [map-portrayal.md](./map-portrayal.md).

**What stays in `ui-apps/admin/`, and why.** `base-map-layers` (it injects `AuthFacade` and
`APP_CONFIG`), `wms-layer-visibility` (it takes both), `map-view-defaults` (it takes the session
domain's defaults), `ol-coordinate-readout` (administration chrome, and the sole user of two
surface utilities), the geometry editor and its layers (which style themselves and are deliberately
outside the portrayal), the co-location stamping, and every message layer — a layer knows a session,
a language and a search result, which the kit must not. They reach the kit through the alias like
any other caller. The readout also keeps its `from 'ol'` barrel import; the module-path rule below
binds the kit, not the applications.

**The kit's dependencies are `@angular/*`, `ol/*` and `rxjs`, and nothing else** — ESLint says so
for `shared-map/**`. Anything an application owns arrives as an input, never as an injection (bar
the one exception below): the layer switcher takes its heading as a required input rather than
translating a key — a layer's `name` and the tile layer's `attributions` take their words the same
way, which is the one sanctioned exception to translation ownership
([angular-conventions.md](./angular-conventions.md) → _Translation ownership_) — and the WMS layer's
HTTP client is optional because only its protected mode needs one. OpenLayers is imported by module
path here (`ol/Map`, `ol/View`), never through the package barrel.

**One injection is sanctioned: the tile addresses.** `tile-sources.ts` declares `MAP_TILE_SOURCES`
— the OpenStreetMap and OpenSeaMap tile URL templates a deployment draws its base map from — with a
root default of `{}`, so a map with nothing provided draws from OpenLayers' own OpenStreetMap
address and the public OpenSeaMap server, both over https. The addresses are the deployment's and
the same for every map an application draws, so each application provides the token once in its
`app.config.ts` instead of binding a `url` on every tile layer: the administration app with getters
over `APP_CONFIG.map` (the web-settings overlay writes the backend's `osmUrl` and `openSeaMapUrl`
into that object after the providers are built, so a copy would miss them), the public site with a
getter over `AppConfigService.osmUrl`, which takes the setting only as an `https:` template with
`{z}`, `{x}` and `{y}` and has no seamark address because the site draws no OpenSeaMap. A layer's
`url` input still wins over the token, and a blank value counts as unset at every step. The file
imports nothing but `@angular/core`, because both applications provide it from their initial chunk,
which must not pull OpenLayers in. Lint cannot tell one injection from another, so the exception is
kept by review: nothing else an application owns reaches the kit this way.

**A map behaviour or performance change is made in the kit and nowhere else, and it is measured
for both applications.** Both run the kit's specs and both lint the folder, so a change to it is
gated twice; a tuning that helps one application and costs the other has to be seen before it
ships, not after. The public site's first-visit transfer is the measurement that fails loudest
(`npm run bundle:info`).

- **Inherit-or-create.** `OlMap` provides `MapShellService` through a factory that reuses an
  ancestor's instance if there is one (`skipSelf`, optional) and creates its own otherwise. That is
  what lets a page mount the layer switcher as a sibling of the map. The consequence:
  **a nested map — a preview inside a page, a dialog inside a view — must provide its own
  `MapShellService`**, or its base and preview layers register onto the host map (symptoms: extra
  entries in the switcher, an opaque base layer over the host's overlays). Before adding a map
  inside a screen that already has one, grep both components' `providers` for `MapShellService`.
- **Pre-mount layer queue.** Projected layers register during their own constructor effects, which
  run before the parent's `ngAfterViewInit`; `setMap()` flushes the queued layers onto the map.
- **Attached once its element has room.** The map is built and handed to its shell in
  `ngAfterViewInit`, but it is attached to its element only when the element has a width and a
  height: at once if it has them then, otherwise the moment the kit's ResizeObserver reports them.
  While the element has none — a page that sizes its map after the first render, a hidden tab, a
  page being left — the map is not measured and keeps its last size, because OpenLayers warns "No
  map visible" each time it measures an element without room. The observer watches the host, one
  level above the container OpenLayers observes, so attaching from its callback raises no
  ResizeObserver loop error.
- **Tile-load gate.** `installTileLoadGate(map)` wraps OpenLayers' internal tile-queue drain so
  tiles stay idle while the view animates or interacts and for 150 ms after the gesture ends —
  roughly an order of magnitude fewer requests on a multi-step zoom, because OL's own
  per-frame reprioritisation then discards the obsolete intermediate tiles for free. There is no
  public hook for this, so the internal access is guarded: a future OpenLayers rename degrades to
  "no gating" instead of throwing. The settle window must end with an explicit `map.render()` or a
  static map has nothing to drain the queue.
- **Reduced motion.** A reader whose system asks for less motion
  (`prefers-reduced-motion: reduce`, read when the map is built) gets every interaction and
  control with its animation at zero: a wheel, double-click, pinch, keyboard or shift-drag zoom
  and the zoom buttons land at once, a keyboard pan steps, and a drag stops where the pointer
  lets go instead of coasting.
  The interactions are replaced where they stand, so the order they see an event in is unchanged.
  Animations an application starts on its own map — a fit to a result set — are its own to
  shorten.
- **Base layers.** `<app-base-map-layers>` is projected as the _first_ content inside
  `<app-ol-map>` so base layers sit under feature layers. OSM is always present and visible;
  OpenSeaMap is registered but hidden, and only offered in the switcher when enabled by
  configuration — a host whose map should never offer it at all suppresses the layer with
  `[openSeaMap]="false"`; the WMS layer renders only when it is enabled and has a URL — and, for a
  protected WMS, only for an authenticated user, tracked reactively so the entry appears after a
  mid-session login.
- **Protected WMS.** In protected mode tiles load through `HttpClient` so the auth interceptor can
  attach the token, and the layer caps its resolution at 1000 m/px so a world view cannot spawn
  dozens of authenticated requests. Known deployment issue: where the WMS answers no-data tiles
  with a redirect, the authenticated cross-origin fetches fail noisily. Fix it at the gateway or
  the proxy — a transparent-tile workaround in the loader hides a real misconfiguration and was
  removed once.
- **Initial view** is a per-axis cascade in `map-view-defaults.ts`: the session domain's values
  win per axis, deployment configuration fills the gaps (a centre needs _both_ coordinates —
  half a coordinate is noise), and nothing at all leaves OpenLayers to its own default.
- **Colours.** The S-124 portrayal's colours are data in `shared-map/portrayal/portrayal.json`,
  which `map-styles` reads. Every other colour in an OpenLayers style module — the halos, the count
  badge, the pins, the editor and overlay layers — is read from the design tokens through
  `readCssVar` (`shared-map/css-tokens.ts`, imported as `@niord/map/css-tokens` by both
  applications), because canvas styles cannot resolve `var(...)`. Never hardcode a colour literal
  in a style module — see [styling-reference.md](./styling-reference.md).

Symbology rules for messages on the map are in [map-portrayal.md](./map-portrayal.md).

## The message-HTML contract

A message part's details, a message's publication reference and an editor comment are HTML the
backend stores and neither application authors. What that HTML is allowed to render is decided
once, in `shared-html/` — the second folder that belongs to neither application and that both
compile — behind the alias `@niord/html/*`, with the same shape as the map kit: no barrel (and no country file
imports it: lint bans the alias in country code), both
tsconfigs include it, both projects run its specs and lint it, and its ESLint block allows
`@angular/*` and relative siblings and nothing else.

`MessageHtmlSanitizer` (`shared-html/message-html-sanitizer.ts`) does four things:

- **Records, then strips, the inline styles.** It parses the markup with `DOMParser` and reads each
  element's `style`, keeping two declarations — `text-align` with one of the four keywords, and
  `padding-left` as a plain length capped at 160 px, whatever unit it is written in (the rules are
  `shared-html/message-html-declarations.ts`). Every other declaration is dropped, and the whole
  attribute is removed before the sanitiser ever sees it, which is also what stops the browser
  console filling with one "sanitizing HTML stripped some content" line per styled element. In
  development — Angular prints that warning nowhere else — the same parse, made as Angular's
  sanitiser makes it, takes out what earlier editors left in the stored markup and the sanitiser
  would drop just the same (comments, a word processor's `<o:p>`, `data-*`, `id`, `caption`,
  TinyMCE's `mce-*` and AngularJS's `ng-*` and `<render-message-part>`), unless taking it out would
  change how the rest parses, so the warning is left for markup nobody expected. The pass is
  guarded by `ngDevMode`, which a production build folds to false: neither application ships it,
  and the public site's first visit does not pay for it.
- **Lets Angular's sanitiser decide everything else.** Elements, attributes and URLs are its call,
  unchanged: `class`, `rel` and `target` survive, `<script>`, event attributes and `javascript:`
  URLs do not.
- **Narrows what the sanitiser keeps verbatim.** A `class` keeps only the vocabulary the shared
  message-HTML stylesheet styles (`MESSAGE_HTML_CLASSES` in `shared-html/message-html-classes.ts`,
  with `allowedClasses` beside it: the editor's table, row and cell classes,
  the backend's position lists and tables, `no-wrap`); both applications' stylesheets carry global
  utilities such as `fixed inset-0 z-50`, and a class from a message reaches them as surely as one
  from a template. The backend's mails keep the same class names (`MailHtml` in `be/niord`), and its
  `MailHtmlTest` reads `message-html-classes.ts` and fails when the two lists differ, so a change to
  the vocabulary changes both and runs Backend CI. Every element with a `target` gets
  `rel="noopener noreferrer"`, whatever `rel` it carried, so a page a message link opens cannot
  reach back through `window.opener`.
- **Puts the two declarations back.** A recorded declaration travels through the sanitiser as a
  class token on its own element, never as a position in the tree: the sanitiser drops every
  element it does not know and hoists its children, so every position after such an element
  shifts, and an element it removes has to take its indent with it. A `data-` attribute cannot carry the mark, because the sanitiser's attribute
  allow-list has no `data-*` in it. The value is rebuilt from the validated property and value, so
  nothing is copied out of the input.

The result is marked safe, and this is the one place in the workspace that may say so about message
HTML: a plain string bound with `[innerHTML]` is sanitised a second time at the binding, which would
remove the two declarations again. Both applications reach it through their own
`safe-message-html.pipe.ts`; the administration app's comment cards call it directly. ESLint's
`no-restricted-properties` fails every `bypassSecurityTrust*` call outside `shared-html/` and the
administration app's two documented exemptions — `plain-text-to-html.pipe.ts`, which marks safe
what it has just escaped, and `<app-sandboxed-html>` — and `npm run test:guards` proves the rule
still fires.

The same folder holds `repo-path.ts`, the one encoder of a repository path both applications build
attachment and image addresses with: every segment is encoded, and because the backend hands a path
out both as uploaded and already encoded, a segment is decoded first where it decodes, so both
spellings give the same address and a `#` or `?` in a file name stays part of the name.

The administration app's rich-text editor holds the same two declarations through an edit — a
`padding-left` on the block nodes, a `text-align` on paragraphs, headings and table cells — read with
the same rules from `message-html-declarations.ts`. The editing surface therefore shows what the
rendered message shows, and saving an edit keeps an indent the editor did not touch. It narrows
classes by the same vocabulary, through `allowedClasses` from `message-html-classes.ts`: the
`class` of a table, row, cell and link keeps only `MESSAGE_HTML_CLASSES`, a link with a `target`
gets `rel="noopener noreferrer"`, and a publication span keeps its `publication` id, `title`,
`lang`, `dir` and a narrowed `class` and nothing else — no event attribute and no `style` reaches
the editable surface. Every attribute is narrowed where the editor parses content, which every way
content gets in goes through, and again where it renders it. The editor does not run the sanitiser
itself: Angular's sanitiser drops the `publication` attribute the Publication field must keep. A whole
backend-generated document — a mail body — is a different problem with a different answer,
`<app-sandboxed-html>` (see [ui-standards.md](./ui-standards.md)).

## Shared primitives the architecture relies on

The wrapper catalogue and the "which surface for which intent" rules are in
[ui-standards.md](./ui-standards.md). The architectural facts about them:

- **Drawer** (`shared/components/drawer/`) — visibility is parent-owned; `[dirty]` is bound to the
  same `changed()` signal that gates Save, and the drawer runs the unsaved-changes gate on every
  close path. `closed` and `hidden` are different events and the data-lifetime rule depends on the
  distinction.
- **Dialog + `DialogHostService<TResult>`** (`shared/dialogs/`) — a route mounts one host component
  per dialog kind; the caller subscribes to the observable returned by `open(...)` and receives the
  result, or `undefined` on dismissal. Each `open()` uses a fresh subject, so a stale caller can
  never receive a later result.
- **`ConfirmService` + `<app-confirm>`** — deliberately _not_ `providedIn: 'root'` alone:
  `<app-confirm>` injects the nearest instance, so a component that re-provides the service must
  mount its own `<app-confirm>`. A root instance exists for root-provided consumers.
- **`PickerPopover`** — the one anchored-overlay recipe. New anchored pickers route through it
  rather than hand-rolling a connected overlay.
- **Rich text is TipTap** (`shared/forms/rich-text-editor/`), loaded lazily on first mount through a
  dynamic import. Nothing eagerly loaded may import the engine module or `@tiptap/*` statically, or
  the whole editor lands in the main bundle. Never add a second rich-text library.
- **Code editing is CodeMirror 6** behind `<app-code-text-input>`
  (`features/admin/components/code-text-input/`). Without a `[language]` it is a plain textarea and
  costs nothing; with one it lazily mounts the editor. Never add a second code-editor library.
- **The geometry editor** (`shared/components/geometry-editor/`) is a _value_ editor, not a form:
  `[geometry]` in, `(geometryChange)` out **on commit only**, cancel discards the working state. It
  provides its own `MapShellService` and depends on no forms layer.
- **`HistoryStore<T>`** (`shared/utils/history-store.ts`) is the undo/redo primitive — signal-backed,
  depth-capped, over immutable snapshots. The geometry editor is its consumer today; never
  re-implement undo per surface.

## Roles, domains and the shell

Which areas exist, who may enter them, and how the domain (tenant) scopes everything is described
in [features/shell-and-auth.md](./features/shell-and-auth.md). Two architectural consequences worth
knowing before you write a feature: there is no implicit role hierarchy (every role list spells out
every admitting role), and the session domain supplies the time zone every instant in the UI is
rendered through — see [coding-standards.md](./coding-standards.md).
