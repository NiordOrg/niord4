# AGENTS.md

`ui-apps/admin/` is the administration application of Niord (Angular project `niord-app`): the message
editor, the firing-area schedule, and the admin and sysadmin areas, all against the Niord REST API
with Keycloak authentication. Standalone components, zoneless, signal-first, strict TypeScript,
Tailwind CSS v4, Transloco. The component foundation is `@angular/cdk` + `@angular/aria` for
behaviour and `@ng-icons/lucide` for icons, wrapped once by the shared components in
`src/app/shared/`. The rules both applications of the workspace share are in the
[workspace AGENTS.md](../../AGENTS.md).

## Non-negotiables

- **Do not invent a second way.** Before building a layout, a navigation step, a control or an icon
  for an action, find where the app already does that job and do it the same way. An actions menu is
  the cog trigger with its chevron, everywhere. See the Foundation rules in
  [docs/ui-standards.md](../../docs/ui-standards.md).
- **CDK/aria wrappers only.** Compose the wrappers in `src/app/shared/`; never reach for a component
  library.
- **What the HTML a message carries may render is shared.** `MessageHtmlSanitizer`
  (`@niord/html/message-html-sanitizer`, in `shared-html/`) is the one sanitiser for message
  content: it runs Angular's sanitiser and then puts back the two inline declarations a message is
  allowed to keep, `text-align` and a capped `padding-left`. The editor preview, the collapsed
  description row, the message details and the comment cards all render through it, and the
  rich-text editor holds the same two declarations and the same class vocabulary by the same rules
  (`@niord/html/message-html-declarations`, `@niord/html/message-html-classes`), so an editor sees
  what a mariner will see; its attribute slots are allow-lists, so no event attribute or `style`
  from stored markup reaches the editing surface. Never call
  `bypassSecurityTrustHtml` in a component; `<app-sandboxed-html>` is the separate, sandboxed answer
  for a whole backend-generated document.
- **The map components are shared, and so is how a message is drawn.** `<app-ol-map>`, its shell,
  the base layers, the projection service and the S-124 portrayal (`map-styles`, drawing with the
  definition `portrayal/portrayal.json` and its symbol files — the one source of every portrayal
  value) live in `shared-map/` behind `@niord/map/*`, because the public site draws the same maps
  and a mariner must read the same marks in both. A behaviour, portrayal or performance change to
  any of them is made there and measured for both applications; what is administration-only — the
  authenticated layer set, the domain's initial view, the coordinate readout, the co-location
  stamping, every message layer — stays here and passes what it knows in.
- **Every user-visible string is a Transloco key in `languages/en/i18n/admin/en.json` only.**
  Every other language is a country's pack, translated in its repository (Danish is `niord-dk`'s
  `niord-lang-da`, translated in DK's bump PR or ahead of it there;
  [fe-country-split-plan.md](../../../docs/fe-country-split-plan.md) §6 P5). **No language but
  English is written in a base product PR.** A renamed key is named in `languages/en/renames.json`
  and named as a rename in the PR, so each pack migrates.
  A text that names an authority — the footer link and copyright, a logo, a legal document — is not a
  base key: it goes in the country's branding (`admin/assets/branding/<lang>.json` in its repository,
  the legal documents in `admin/assets/legal/`), never in `en.json`.
  A build ships exactly the UI bundles its language configuration names, each staged complete (a key
  a pack lacks is filled from `en`), and there is no fallback language: the loader
  (`src/app/transloco-loader.ts`) fetches only the requested bundle — a content-only language takes
  the default's — and merges the branding and the Dictionary's overrides over it, and a key still
  missing at runtime renders as the key. A bundle that does not arrive is retried twice, the load
  then completes without it, and the loader asks for it again in the background (after 30 s, then up
  to three more times, each pause twice the one before), replacing the translation when it arrives.
- **The time zone comes from the domain, never the browser.** Use the zoned-date helpers and
  `LocaleFormatService`; lint fails on browser-local `Date` forms. A missing zone disables, it never
  guesses.
- **Data loading is a three-way rule.** `httpResource` for reactive GETs behind `hasValue()`,
  `HttpClient` for imperative GETs and every write, `rxResource` only when an operator must live
  inside the resource.
- **Signal Forms** (`@angular/forms/signals`) for every edit flow; every schema rule is bound with
  `[formField]`.
- **No country logic in `core/`, `shared/` or `features/`.** Country variation is a country
  repository's files at the seams below, its brand tokens and its branding — never a country
  branch. `src/environments/` holds the base product's neutral files; a country's environments are
  its own (`admin/environments/` in its repository).
- **Besides `language.config.ts`, the country seams are three files of
  `country-specific/default/`**, replaced by a country's own file when its repository has one (its
  set-up swaps it in): `country.providers.ts` (`provideCountry()`, called once
  at the root injector in `app.config.ts`: the country's hook registrations (a promulgation
  renderer or editor for a service of its own; the base product registers NAVTEX, Twitter, SafetyNET
  and Audio itself) and its `provideSlot` registrations), `country.routes.ts` (its
  own pages, loaded lazily after every base route; `core/country/country-routes.ts` keeps only an
  entry with `canMatch: [roleGuard]` and a non-empty `data.rolesRequired`, `data: { public: true }`
  or a redirect) and `country.menu.ts` (`COUNTRY_MENU_ENTRIES`: the base item each follows, a
  `country.*` label key, an icon, its page and its roles, role-checked by the main menu like the
  base items). The default routes and menu are empty. A hook (a multi-provider token such as
  `PROMULGATION_RENDERERS`) or a slot (`<app-slot name="…" />` in a base template, with a lazy
  loader, rendering nothing by default; today `home.welcome`) is a base product contract kept across
  versions: add one deliberately, only where a country needs it, with a neutral default and a spec,
  and catalogue it in `country-kit/seams.json`, whose test holds the catalogue to the tree; its
  token goes into the country API too.
- **A country's files (in its repository) import the base product only through `@niord/admin/api`**
  (`src/public-api.ts`; in specs also `@niord/admin/api/testing`, `src/testing-api.ts`), never by a
  relative path into the base product — the country lint (`country-kit/lib/country-lint.cjs`) fails
  every other import, static or dynamic, a bare `[innerHTML]` and an `eslint-disable` there. Nothing
  else in the application imports the API. A country need it does not meet is a new export, added
  deliberately under its own contract comment in the file's form and recorded in
  `country-kit/API-CHANGES.md`; an export is removed only after a deprecation.

## Reading order (`../../docs/`)

1. [architecture.md](../../docs/architecture.md) — layers, import boundaries, data flow, state, extension points, admin shell, map layer
2. [platform.md](../../docs/platform.md) — bootstrap, configuration overlay, auth, domains, i18n loading, deployment
3. [angular-conventions.md](../../docs/angular-conventions.md) — signals, effects, data loading, Signal Forms, templates, routing, Transloco
4. [typescript-conventions.md](../../docs/typescript-conventions.md) — compiler flags, lint, naming, closed sets, wire vs model
5. [coding-standards.md](../../docs/coding-standards.md) — error handling, dates and time zones, comments, commit gates
6. [ui-standards.md](../../docs/ui-standards.md) — component catalogue, overlay surfaces, forms, accessibility
7. [styling-reference.md](../../docs/styling-reference.md) — every token and Layer-3 class
8. [testing.md](../../docs/testing.md) — unit harness, spec idioms, e2e fixtures
9. [definition-of-done.md](../../docs/definition-of-done.md) — the bar a change has to clear
10. [country-deployments.md](../../docs/country-deployments.md) — the base product's seams and defaults, the token contract, where a difference lives
11. [backend-api-notes.md](../../docs/backend-api-notes.md) — wire contracts that are not obvious
12. [features/](../../docs/features/) — one document per area; [publications/](../../docs/publications/) — the publications domain
13. [backlog.md](../../docs/backlog.md) — before go-live, maintenance, deliberately not built

## Publications

Read [docs/publications/domain-rules.md](../../docs/publications/domain-rules.md) before touching
anything under `features/admin/sections/admin/publications/`. The specifications
([DATA-MODEL.md](../../docs/publications/DATA-MODEL.md), [API-CONTRACT.md](../../docs/publications/API-CONTRACT.md))
are guarded by `node scripts/publications/spec-check.js`; the go-live set under
`docs/publications/go-live/` is kept verbatim until go-live — do not reformat or reword it.

## Guards

- `npm run docs:check` after any change to this file, README.md, `docs/` or the review skill.
- `node scripts/publications/spec-check.js` after any edit to the two publications specifications.
- Review with the `niord-review` skill; the checklist is [docs/definition-of-done.md](../../docs/definition-of-done.md).
