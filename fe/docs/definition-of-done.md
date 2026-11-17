# Definition of Done

A change is done when it behaves correctly, when the rules below hold, and when a reviewer can _see_
that they hold. Every item here is checkable — by running a command, by pointing at a file, or by
exercising the screen. An item you have not checked is not evidence that it passes.

Use it twice: as your own pass before asking for review, and as the reviewer's list. An item that
does not apply is called out as "not applicable — \<reason\>", not left silently unticked.

The workspace holds two applications. The gates below are per application; the rest of this
document is written for the administration app (`ui-apps/admin/`). For a change to the public site
(`ui-apps/info/`), [its AGENTS.md](../ui-apps/info/AGENTS.md) is the rule wherever the two differ,
and the items below that hold for the administration app alone say so.

## Green gates

Run the gate of the application the change reaches, and both gates when it reaches what both build
from: `styles/`, `shared-map/`, `shared-html/`, the workspace configuration (`angular.json`, the
`tsconfig` files, `eslint.config.js`, `package.json`) or a script both gates run.

### The administration app

```bash
npm run verify      # paths:check → contents:check → format:check → lint → lint:scripts → unit tests → production build → urls:check
npm run e2e         # needs a dev server, a live backend and KEYCLOAK_URL (no default); not in CI
```

### The public site

```bash
npm run verify:info     # paths:check → contents:check → format:check → lint:info → test:info → build:info → urls:check → separation:info → bundle:info
npm run layout:info     # after build:info, when the bars, a message card or a label's length can move
npm run build:info && npm run image:info -- --tag niord-info
npm run test:container:info -- --image niord-info --no-legal-doc --no-branding-text
                        # when the change reaches the image; --browser as well when it reaches the
                        # frame policy
```

The last three are manual tools on the base build; Frontend CI's Example country job runs the
layout check and the container test on the example country's image on pull requests.

`bundle:info` fails a first visit that grew past `ui-apps/info/bundle-baseline.json` by more than
2 % of a group, or 64 bytes where that is more. A change passes it as it stands: an improvement is
reported, and `--update-baseline` is a deliberate act the change explains — never the way to make
the gate pass. `separation:info` fails a first-party module of the build that lies outside the
public site and the three shared folders. When the layout check and the image test apply is in
[`ui-apps/info/AGENTS.md`](../ui-apps/info/AGENTS.md) → _Guards_; the image is packaged again from a
fresh `npm run build:info` for the test, since it runs against a built image, not the working tree.

### Both

```bash
npm run docs:check   # when you touched README.md, an AGENTS.md, docs/, the kit's documents, the
                     # kit's guides or its agent template, or the review skill
npm run test:guards  # when you touched eslint.config.js, a guard script, a folder the lint
                     # boundaries name, the public site's route table, its print addresses,
                     # its nginx query maps, its hash-address rules or the country kit
node ../country-kit/example/run.mjs --work <dir>   # when you touched the kit, its example, the zip's
                     # include list or a workspace root file: the example country set up and both
                     # applications built from outside the tree (JAVA_HOME; verify-local runs it)
```

`docs:check` verifies that relative links resolve and that their `#fragment` names a heading
(GitHub's slug) or an explicit anchor of the document, that the paths, npm scripts and build
configurations a document names exist, that an `ng` command written out names its project, and
that the forward-looking vocabulary holds. In a document the kit package carries, a link stays
inside the kit, and a `.niord/kit/` or `.niord/base/` path names a file of the kit package or the
source zip. In one the source zip carries, a link stays inside it. The kit's guides may name only a
country workspace's npm scripts. The agent template is checked for its `.niord/kit/` and
`.niord/base/` paths. The publications specifications and
the go-live runbooks are excluded on purpose — they stay verbatim.

`npm run format` rewrites **every** file in the repository. Do not run it: format only what you
changed. The pre-commit hook (husky + lint-staged) already formats and lints staged files.

### What is enforced, and by what

Frontend CI (`fe-ci.yml`) is the administration app's workflow and Info CI (`info-ci.yml`) the
public site's; which of the two a change runs is in the [workspace AGENTS.md](../AGENTS.md) →
_Guards_.

| Gate                                                                        | Enforced by                                                                                                                                                                                                                                                                               |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Formatting                                                                  | `npm run format:check` (Frontend CI; Info CI checks `ui-apps/info/` and the info scripts) + pre-commit hook                                                                                                                                                                               |
| Paths in `angular.json`                                                     | `npm run paths:check` — the first step of `verify` and of `verify:info`; the Checks + builds job of both workflows                                                                                                                                                                        |
| What each build configuration ships                                         | `npm run contents:check` — after `paths:check` in `verify` and `verify:info`, without building; the Checks + builds job of both workflows                                                                                                                                                 |
| No external font or style URL in a build                                    | `npm run urls:check` — after the production build in `verify` and `verify:info`; the Checks + builds job of both workflows, after each build                                                                                                                                              |
| Lint: layer boundaries, template accessibility, time zones, deprecated APIs | `npm run lint`, `npm run lint:info` (CI)                                                                                                                                                                                                                                                  |
| Lint: the kit's and the workspace's scripts (`.mjs`, `.cjs`)                | `npm run lint:scripts` — Frontend CI's Checks + builds job, `verify-local`'s administration-app row                                                                                                                                                                                       |
| The lint boundaries and the guards themselves still firing                  | `npm run test:guards` — the Checks + builds job of both workflows                                                                                                                                                                                                                         |
| Unit tests                                                                  | `npm run test`, `npm run test:info` (CI)                                                                                                                                                                                                                                                  |
| Translation packs held to English                                           | `npm run languages:check` — the pack check (in this tree the `en` pack alone; a country's build runs it over its packs); `npm run test:info` — `ui-apps/info/src/app/i18n/en-bundle.spec.ts` for the English bundle itself                                                                |
| Production build and bundle budgets                                         | `npm run build`, `npm run build:info` (CI); budgets in `angular.json`                                                                                                                                                                                                                     |
| A country building from outside the tree                                    | `node ../country-kit/example/run.mjs` — the example country's set-up against a source zip of the change, its `npm ci`, both builds, its specs and its lint: `verify-local`'s _example country_ row; Frontend CI's `Example country` job, on a change to what the zip or the harness reads |
| The public site's separation and first visit                                | `npm run separation:info`, `npm run bundle:info` (Info CI)                                                                                                                                                                                                                                |
| The public site's bars, cards and chips in a browser                        | `npm run layout:info` on the example country's image (Frontend CI's `Example country` job, on pull requests) and by hand on the base build                                                                                                                                                |
| The public site's image: proxy allow-list, headers, start-up checks, frames | `npm run test:container:info -- --browser` against the example country's image (Frontend CI's `Example country` job, on pull requests) — the frame policy as Chromium applies it included — and by hand against the base build's                                                          |
| Documentation links, paths, scripts, configurations, commands, vocabulary   | `npm run docs:check` — the Checks + builds job of both workflows                                                                                                                                                                                                                          |
| End-to-end flows                                                            | `npm run e2e` — needs a dev server, a live backend and `KEYCLOAK_URL` given by the runner, so it is deliberately out of CI                                                                                                                                                                |
| Accessibility beyond the static template rules                              | **Review only** — no automated accessibility run exists                                                                                                                                                                                                                                   |
| No component library                                                        | **Review only** — no lint guard exists                                                                                                                                                                                                                                                    |
| No country branch in shared code                                            | Public site: lint fails a country-folder import anywhere but `app.config.ts`. A branch on a country value, and anything in the administration app: **review only**                                                                                                                        |

The "review only" rows are the ones that quietly rot. Treat them as the parts of this document
that actually need you.

## Behaviour

- The intended behaviour is implemented, and the edge cases the feature can actually reach were
  considered — not only the happy path.
- **Loading, empty, error and transition states are all handled.** A screen that only looks right
  with data is not finished.
- **Permissions are right in both directions** (the administration app; the public site has no
  login). Roles are defined in `ui-apps/admin/src/app/core/auth/area-roles.ts` and enforced by
  `ui-apps/admin/src/app/core/auth/guards/role.guard.ts`; the unauthorised path is exercised, not
  assumed. Read access and mutation rights are separate questions — a role that may open a screen
  does not automatically get its buttons.
- **Failures land where the policy says**: a read failure renders inline on the owning screen, a
  mutation failure raises a toast _and_ resolves to a result the caller can act on. See
  [`coding-standards.md`](./coding-standards.md).
- **A time zone comes from the domain, never from the browser.** Build and read instants through the
  zoned-date helpers in `ui-apps/admin/src/app/shared/utils/zoned-date.ts` and render through the locale format
  service; lint errors on the browser-local `Date` and `Intl.DateTimeFormat` forms, and the short
  exemption list takes an inline disable naming the reason. See
  [`coding-standards.md`](./coding-standards.md). The public site has no domain: its zone is the
  country's, it renders through its own `DateFormatService`, and it carries no inline disable.
- Behaviour you had to infer from the backend rather than read somewhere is written down as a
  comment where the inference lives.
- A deliberate limit is recorded in [`backlog.md`](./backlog.md) rather than left to be re-discovered
  as a bug.

## Architecture

- The change respects the four layers and the import boundaries in
  [`architecture.md`](./architecture.md). `shared/` and `core/` never import `features/`; a feature
  never imports a sibling feature — the single exception is one-way, every feature may import the
  messages hub and the hub imports none of them. ESLint errors on all of these, so if you are
  fighting the rule, the design is wrong, not the rule. The public site's folders follow rules of
  their own, which its AGENTS.md states and its lint block enforces.
- **No server data is held beyond the view that shows it.** There is no client-side cache; component
  lifetime is data lifetime. Fix over-fetching structurally (fewer, better-shaped calls), never by
  caching.
- Variation is handled through an extension point — an injection token or a registry — not a
  conditional in shared code.
- No unnecessary duplication, and no unrelated area rewritten alongside the change.

## Angular and TypeScript

Full rules live in [`angular-conventions.md`](./angular-conventions.md) and
[`typescript-conventions.md`](./typescript-conventions.md); generic framework idioms live in the
vendored [`angular-developer`](../.claude/skills/angular-developer/SKILL.md) skill. The bar a change
has to clear:

- **Zoneless.** No `zone.js`, no `NgZone`, and no `ChangeDetectionStrategy` annotation — the symbol
  appears in neither application's sources. Zoneless change detection only refreshes what a
  notification marked, so every component has to be OnPush-compatible anyway and writing the
  strategy out adds nothing. Updates are driven by signals, events and `input()` / `output()`.
- **Signal-first.** Local and derived UI state is signal-backed; server state is projected to signals
  at the service or facade boundary. `effect()` is for true side effects only.
- **Data loads pick the right mechanism** — `httpResource` for reactive GETs, `HttpClient` for
  imperative GETs and every write, `rxResource` only when an operator must live inside the resource —
  and every `httpResource` read goes behind `hasValue()`, because `value()` throws in the error
  state. See [`angular-conventions.md`](./angular-conventions.md).
- **Signal Forms** (`@angular/forms/signals`) for every edit flow — the administration app. Form
  values map explicitly back to the request payload. The public site has no edit flow, and its lint
  and its separation check reject the package.
- **Strictly typed.** `strict` plus the extra compiler flags in `tsconfig.json` are on; do not widen
  a type to make an error go away.
- Templates bind a UI model, never a backend DTO, wherever a UI model exists; business logic stays
  out of templates.

## UI and accessibility

- Standard UI is assembled from the shared wrappers — in the administration app those in
  `ui-apps/admin/src/app/shared/components/`, `ui-apps/admin/src/app/shared/forms/` and
  `ui-apps/admin/src/app/shared/icons/`; the public site composes its own few under
  `ui-apps/info/src/app/shared/` on the same foundation. **No component library is added** — the
  foundation is `@angular/cdk` + `@angular/aria` with Lucide icons, deliberately, and a new
  dependency that ships widgets is a design discussion, not a commit. See
  [`ui-standards.md`](./ui-standards.md).
- **The screen does each job the way the app already does it** — layout, navigation, controls and
  glyphs follow the existing precedent rather than a new local idea (an actions menu is the cog
  trigger with its chevron, everywhere). See the Foundation rules in
  [`ui-standards.md`](./ui-standards.md).
- Styling uses the existing tokens and the shared component classes before anything new is authored;
  [`styling-reference.md`](./styling-reference.md) is the catalogue. A colour written straight into a
  template or a canvas style is a defect. The map portrayal is the one place that is not a token: its
  colours, symbols, anchors, sizes and pens are data in `shared-map/portrayal/portrayal.json`, the
  one source of portrayal literals, and a portrayal value written anywhere else — a token, a
  constant in a style module — is a defect too. See [`map-portrayal.md`](./map-portrayal.md).
- **WCAG AA.** Every interactive element is keyboard reachable and operable; overlays trap focus and
  restore it to the trigger; icon-only controls carry an accessible name on the interactive element;
  labels are associated with their controls; new muted text stays at or above the established
  contrast floor. Lint covers the static template rules — focus order, live behaviour and contrast
  are yours to check by hand.
- The layout survives **longer translated strings** and a narrow viewport. Check the same screen in
  every language the application ships before calling it done.

## Internationalization

- Every user-visible string is a Transloco key — templates, validators, toasts, dialogs, menus, and
  the inputs of shared wrappers. The component that puts the text in the DOM owns the `| transloco`
  call; keys are passed across component boundaries, never pre-translated strings. The one
  sanctioned exception is the map kit's text inputs, which take text the host has translated —
  [`angular-conventions.md`](./angular-conventions.md) → _Translation ownership_.
- Backend enum, status and code values are mapped to keys. Raw exception text is never shown to a
  user.
- **A key is added to `en` only.** Every other language is a pack in a country repository (Danish
  is `niord-dk`'s), held to `en` by that country's pack check: a missing key, an empty value where
  English has one, other placeholders, other markup, a date pattern the renderer cannot read or a
  month or weekday list of another length fails that country's build until it translates or
  hatches the key; a key `en` no longer has is stripped from the staged bundle and listed. A
  renamed key is named in `languages/en/renames.json` and named as a rename in the PR, so each
  country migrates its pack. Translated _values_ are not judged.
- **No translation in a base product PR.** Never invent Danish or Kalaallisut: a country's values
  are translated in its own pack and accepted there by its product owner. The public site adds no
  visible text but required accessibility labels — its AGENTS.md → _No new user-visible text_.
- Language support scales through the configured language list, not through code that assumes two
  languages.
- **Authority wording is branding.** The authority's name, the footer's link and copyright, logos
  and legal texts are the country's branding files and legal documents in its asset overlay; the
  base product's bundles never carry them.

## Testing

Details and the harness traps are in [`testing.md`](./testing.md). The bar:

- **Unit tests** for logic, mapping, facades and anything with a branch worth protecting. Existing
  relevant tests still pass; a failing test is fixed or explained, never weakened, widened or
  skipped to reach green.
- **A Playwright test when the flow warrants it**: a path crossing several screens, a permission
  gate, or a regression unit tests structurally cannot see. Exercise the real click path, not only
  the deep link — routing bugs hide exactly there.
- **Locators are stable.** Prefer role or component locators; do not pin text the backend owns
  (the runtime dictionary overrides the compiled bundle, so what you read in `en.json` may not be
  what the app paints). Accessible names match as substrings and roles are exact, not inherited.
- Negative and unauthorised cases are covered when the change introduces them.
- When an end-to-end test fails, decide _stale test versus real bug_ before touching it.

## Country deployments

- No hardcoded country assumption enters `core/`, `shared/` or a feature. The base product's seams
  have neutral defaults — each application's `src/app/country-specific/default/`,
  `styles/tokens.default.css` and the public site's `public/assets/default/` — and a country's
  values live in its own repository — see [`country-deployments.md`](./country-deployments.md).
- If the change touches anything a country replaces (promulgation, language order, tokens,
  branding), **the example country is built** (`node ../country-kit/example/run.mjs`, verify-local's
  _example country_ row) and opened, not just compiled, and a change a country sees is recorded in
  `country-kit/API-CHANGES.md`.
- The solution stays viable for a country that does not exist yet: prefer configuration and
  composition over a fork.
- A pull request that changes a file under `ui-apps/<app>/src/app/` for a security fix adds
  `{ version, files, summary }` to `country-kit/security.json` in the same pull request, with the
  version that first carries the fix; an entry is never removed. A country that overrides one of
  those files then fails its set-up until it has merged the fix into its copy (`--accept-override`).

## Review readiness

- A reviewer can follow the change without a walkthrough. Comments explain the non-obvious and the
  invariants — why this shape was chosen, what breaks if it is "simplified" — and stand on their own
  without pointing at anything outside the repository.
- Tradeoffs and assumptions are written down where they apply.
- Documentation under `docs/` is updated **in the same change** whenever a rule, an invariant or a
  contract it describes moved. A doc that describes yesterday's code is worse than no doc.
- Source files carry no per-file copyright header; do not add one. Ownership is stated once, in the
  appendix of `LICENSE` at the repository root, and nowhere else in the source tree.
- Anything deliberately left undone is a row in [`backlog.md`](./backlog.md) with enough context for
  someone else to pick it up.
