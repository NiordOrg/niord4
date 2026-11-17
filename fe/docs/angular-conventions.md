# Angular conventions

How Angular is written in this codebase. Layering, data flow and where a piece of code belongs are
in [architecture.md](./architecture.md); this file owns the concrete idioms — signals, effects, data
loading, forms, templates, components, routing and Transloco.

Generic framework knowledge is **not** restated here. Signals, `linkedSignal`, `input()`/`output()`/
`model()`, `inject()`, `@if`/`@for`/`@switch`/`@let`, standalone components, lazy routes,
`NgOptimizedImage`, self-closing tags and the rest are owned by the vendored Angular skill in
[`.claude/skills/angular-developer/`](../.claude/skills/angular-developer/) and by the `angular-cli`
MCP (which answers version-specific API questions). What follows is only what is specific to this
application, or a framework behaviour that has already cost this codebase a bug.

## Framework baseline

| Fact                                                                                                          | Consequence                                                                                                                                                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `provideZonelessChangeDetection()` in `ui-apps/admin/src/app/app.config.ts`; `zone.js` is not a dependency    | Nothing may assume zone-driven change detection. `NgZone` is never injected and `markForCheck()` is never called.                                                                                                                                                                                                                |
| Change detection runs only for what a notification marked — a signal write, a template event, an input change | Every component has to be OnPush-compatible anyway, so never write `changeDetection: ChangeDetectionStrategy.OnPush` — the symbol appears nowhere in `ui-apps/admin/src/`.                                                                                                                                                       |
| The `async` pipe is not used anywhere                                                                         | Templates read signals only; `toSignal()` is the observable→signal boundary. An RxJS pipeline is fine wherever an operator is genuinely needed — debounced pickers, dialogs, components — but its result is converted back to a signal before a template reads it, and a long-lived subscription carries `takeUntilDestroyed()`. |
| `CommonModule`, `NgIf`, `NgFor`, `ngClass`, `ngStyle` are not used                                            | Native control flow and `[class]`/`[style]` bindings only.                                                                                                                                                                                                                                                                       |
| Work that has to run after a render uses `afterNextRender` / `afterRenderEffect`                              | Measuring, mounting a third-party widget and first-focus run there, not in a constructor.                                                                                                                                                                                                                                        |

## Signals and derived state

- Signals are the default for local and derived UI state. `computed()` derives; `linkedSignal()`
  derives a value the user can then overwrite. Never derive with an `effect()` that writes a signal.
- **A `computed()` must read its signals first and unconditionally.** A computed whose first operand
  short-circuits past every signal read registers zero dependencies and is cached forever. It looks
  fine in the flow where the signal happens to be read first and is frozen in the other one.
- **Never mix a `computed()` with a mutable non-signal read.** When the computed re-evaluates to an
  equal value it does not bump its version, dependents are not invalidated, and the plain property is
  never re-read. Mirror any mutable non-signal state through a signal.
  The Signal Forms save gate is safe for exactly this reason: `form().invalid()` is a memoized signal
  read, not a plain property.

## Effects

An `effect()` moves state **out** of the signal graph: into a non-signal library (CodeMirror,
OpenLayers, TipTap), the URL, browser storage, or a request. It also resets local UI state when an
input changes. Deriving is `computed()`'s job.

Two rules, both learned from real defects:

- **An HTTP kickoff inside an `effect()` goes in `untracked()`.** Subscribing runs the interceptor
  chain synchronously in the reactive context, so every signal the interceptors read becomes a
  dependency of the effect — and a response that rebinds one of them loops forever. See the comment
  at `ui-apps/admin/src/app/core/domain/services/domain.service.ts` for the case that produced an endless
  `domains/all` refetch for guests.
- **Do not track your own writes.** An effect that reads a signal and then updates it (or updates
  related state) wraps the write in `untracked()`. `ui-apps/admin/src/app/features/messages/services/messages-filter-url-sync.service.ts`
  is the reference.

The same `untracked()` wrapper is what makes "rebuild when the input model changes" effects safe —
the effect tracks the input, not everything the rebuild touches
(`ui-apps/admin/src/app/features/admin/sections/admin/charts/chart-editor.ts`):

```ts
effect(() => {
  const model = this.model();
  if (model) {
    untracked(() => this.rebuildForm(model));
  } else {
    this.geometryEditorOpen.set(false);
  }
});
```

## Data loading: three mechanisms

| Mechanism      | Use it for                                                                                                                                                                      |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `httpResource` | Reactive data-load GETs — the request is a function of signals.                                                                                                                 |
| `rxResource`   | Only when an operator must live _inside_ the resource. It is rare and stays rare: two debounced tag searches (`timer(...).pipe(switchMap(...))`) and one reactive POST preview. |
| `HttpClient`   | Imperative fetch-on-action GETs, and every write. All RxJS orchestration.                                                                                                       |

### `httpResource` rules

- **Read the value behind `hasValue()`.** `value()` _throws_ in the error state, so every consumer
  degrades explicitly:

  ```ts
  readonly comments = computed(() => (this.resource.hasValue() ? this.resource.value() : []));
  ```

- **Return `undefined` from the request function to issue no request.** That is how a fetch is gated
  on authentication, on a drawer having been opened, or on an id existing. The resource returns to
  `idle` and `value()` falls back to `defaultValue` — no reset effect needed. A template `@if` gates
  only the rendering, never the request.
- **The request is compared by reference.** The request function returns a fresh object on each
  evaluation, so _any_ signal it reads forces a refetch, and a request function that reads no signal
  fetches exactly once. Keep the key minimal: derive a primitive (an id) rather than reading a whole
  object that unrelated writes replace.
- **`reload()` does nothing while the resource is already loading.** When a re-read must be
  guaranteed, fold a monotonic version signal into the request function instead. `loadSeq` in
  `ui-apps/admin/src/app/features/editor/services/editor-comments.store.ts` is the pattern: keyed on
  `(id, language, loadSeq)` so re-opening the _same_ message still refetches.
- **Language belongs in the key** whenever the payload is server-translated — see
  [architecture.md](./architecture.md#data-flow-api-service--facade--component).
- `params` accepts a plain record; there is no need to build `HttpParams`.

Facades that need debouncing or distinct-until-changed run `toObservable(triggerKey)` through an
RxJS pipeline and expose signals again — `ui-apps/admin/src/app/features/messages/services/message-list.facade.ts`
is the worked example. Settling resources in specs is covered in [testing.md](./testing.md).

## Signal Forms

Edit flows use `@angular/forms/signals` exclusively. There is no `FormGroup`, `formControlName`,
`FormBuilder`, `ReactiveFormsModule` or `ControlValueAccessor` anywhere in `ui-apps/admin/src/` or `ui-apps/admin/e2e/` — a
control is a `FormValueControl`, not a CVA.

### The editor recipe

```ts
private readonly formModel = signal<ChartFormModel>({ /* … */ });
protected readonly form = form(this.formModel, (path) => {
  required(path.chartNumber);
  disabled(path.chartNumber, { when: () => this.model()?.mode === 'edit' });
});

// `dirty` is interaction-based and never clears on revert, so it cannot mean
// "differs from what was loaded". A serialized baseline can.
private readonly baselineJson = signal('');
protected readonly changed = computed(() => JSON.stringify(this.formModel()) !== this.baselineJson());
protected readonly saveDisabled = computed(
  () => !this.changed() || this.form().invalid() || this.saving(),
);
```

Rebuilding on open sets the model, then the baseline, then calls `form().reset()`.
`ui-apps/admin/src/app/features/admin/sections/admin/charts/chart-editor.ts` is the canonical small version;
several editors add their own extra predicates to the gate.

### Rules

- **A schema rule must reach the control that carries it.** Bind the field with `[formField]` — that
  directive is what reflects the constraint onto the element and what the invalid styling keys on. A
  `required()` on a hand-bound control disables Save with nothing on screen explaining why.
- **Never write native `required` / `pattern` / `min` / `max` / `minlength` / `maxlength`
  attributes on an element bound with `[formField]`.** The browser draws them and `form().invalid()`
  never sees them (a month of 13 once left Save live); put the rule in the schema, which reflects it
  back onto the input anyway. A static constraint attribute alongside `[formField]` is a compile
  error. A hand-bound native input outside the form model — `[value]` plus `(input)`, as in the
  attachment width/height cells — may still carry its own attributes.
- **Arrays** use `applyEach(path.rows, (row) => required(row.field))`; the net-change gate already
  covers add/remove, so rows need no separate tracking.
- **A field typed `unknown`** is bound `[formField]="$any(form.value)"` — the honest translation of a
  dynamically-rendered value, and the only place `$any` is welcome.
- **`<form (submit)="$event.preventDefault(); save()">`.** A bare `<form>` with no forms directive
  reloads the whole application when the user presses Enter, even with no submit button.
- **`[ngModel]` is one-way glue only** — `[ngModel]` plus `(ngModelChange)` into a signal, for small
  dialog/checkbox cases outside a real edit form. `[(ngModel)]` appears nowhere.
- Validation messages are translation keys, never literals.

### Writing a shared control

```ts
export class TimeField implements FormValueControl<Date | null> {
  readonly value = model<Date | null>(null); // two-way via [formField] or [value]/(valueChange)
  readonly disabled = input(false); // written by the form
  readonly touch = output<void>(); // emitted on blur for touched-tracking
}
```

- **Guard the echo.** `value` is a `model()`, so a one-way re-push emits `valueChange` — an effect
  that writes the value into a wrapped widget must compare against the last value it wrote, or a user
  keystroke bounces back and resets the cursor. `code-text-input.ts` (`lastEditorValue`) and
  `script-resources-field.ts` (`lastSynced`) show the shape.
- **`pattern`, `min`, `max`, `minLength`, `maxLength` and `required` are reserved input names** —
  `[formField]` reflects the field's constraint signals onto same-named inputs of the bound control,
  so a control input of your own with one of those names collides on type. Name it something else:
  `pathPattern`, `minSearchLength`.
- A control does not self-validate: its `errors` input _receives_ errors for display. Validation
  lives in the consuming form's schema.

The catalogue of controls and their behaviour is in [ui-standards.md](./ui-standards.md).

## Templates

- Native control flow only; `@let` for a repeated sub-expression. Keep logic out of the template —
  expose a `computed()` instead.
- Every user-visible string goes through the `| transloco` pipe (see below).
- **`[class]` array entries must be single class names.** Angular silently drops an entry that packs
  space-separated classes and the attribute renders empty — a runtime-only failure that lint and
  tests pass. Concatenate when a value may carry several classes:
  `[class]="(item.icon ?? '') + ' ' + (item.iconClass ?? '')"`.
- **Give a `@defer` block an `@error` branch.** Angular records the failure on the template and never
  retries, and the production error surface is silent — one rejected chunk fetch would otherwise leave
  the feature missing for the rest of the session. The two map blocks (home page, message details)
  are the reference; the two editor dialog blocks
  (`geometry-preview-field.html`, `part-card.html`) do not yet follow the rule and should gain one
  when next touched.
- **A hoisted `@defer` repeats its guard inside `@placeholder` and `@error`.** A block nested under
  an `@if` cannot prefetch before it is instantiated, so the block wraps the `@if`; the guard is then
  repeated in the other branches so the placeholder does not reserve space on screens that will never
  show the content. `ui-apps/admin/src/app/features/messages/message-details-dialog/message-details-dialog.html`
  carries the full reasoning inline.
- **No backticks and no `${` inside an inline `template:` string** — including inside an HTML comment.
  Either one closes the template literal and produces a misleading `Parsing error: ',' expected`.
  External `.html` templates are immune.
- Accessibility rules for the shared surfaces are in [ui-standards.md](./ui-standards.md).

## Components

- **No `Component` suffix on the class, no `.component` segment in the filename.**
  `shared/components/dialog/dialog.ts` exports `Dialog` with selector `app-dialog`. ESLint enforces
  the `app` prefix (element/kebab-case for components, attribute/camelCase for directives) but not
  the class name — keep it by convention.
- **`templateUrl` for views and pages; an inline `template` for small overlay-shaped components** —
  dialogs, popovers, menus, single-purpose controls. Most components use an external template.
  Component CSS files are rare: styling is Tailwind utilities plus the shared component classes, see
  [styling-reference.md](./styling-reference.md).
- **Style the host, do not add a wrapper element.** `host: { class: 'block' }`, `host: { class:
'contents' }` for a transparent wrapper that must not take part in the parent's flex/grid layout
  (the layout reasoning is in [ui-standards.md](./ui-standards.md)).
- **`protected readonly` for everything the template reads, `private readonly` for the rest**, and
  `readonly` on all inputs/outputs. Anything the template does not touch stays private.
- **Dependencies come from `inject()`**, never constructor parameters.
- Keep components narrow: a component that calls an API or reshapes DTOs belongs one layer down (see
  [architecture.md](./architecture.md)).

## DI and providers

- Service scoping — root-provided API services, screen-scoped facades, and the messages services that
  deliberately outlive the screen — is in
  [architecture.md](./architecture.md#data-flow-api-service--facade--component).
- Shared code never imports a feature. When a shared control needs feature-owned data the dependency
  is inverted through an injection token; the registries and tokens are catalogued in
  [architecture.md](./architecture.md#extension-points).
- Multi-provider registries are contributed at the root injector only — a lower-level provider
  shadows rather than merges.

## Routing

- **Every top-level area route is lazy** — `loadChildren` into an area's `*.routes.ts`, or
  `loadComponent` for the home page, which has no route file of its own. Inside an already-lazy chunk
  the area shell is referenced eagerly (`component: EditorShell`, `component: Messages`) and child
  routes may be either. An area may own more than one route file: `features/admin/` ships
  `admin.routes.ts` and `sysadmin.routes.ts`. Route components stay thin.
- **`title` is a translation key, not text.** `I18nTitleStrategy` composes the application name with
  every `title` key down the activated tree, and re-resolves them when a language finishes loading —
  a literal string would be printed verbatim.
- **Role gating is `canMatch: [roleGuard]` plus `data.rolesRequired`.** Role sets are named constants
  in `ui-apps/admin/src/app/core/auth/area-roles.ts`; see [features/shell-and-auth.md](./features/shell-and-auth.md).
- **The help button reads `data.help`** — `{ section }` only (a `manual` key on one route is read by
  nothing; do not copy it). Child routes inherit it because the router's default
  `paramsInheritanceStrategy: 'always'` is deliberately relied upon; **do not re-pin `'emptyOnly'`** —
  anything declaring route `data` once on a parent and reading it on a non-empty child would silently
  stop seeing it. Nothing depends on it today (each editor leaf carries its own `editType`,
  `rolesRequired` is read off the matched route, `HelpUrlService` walks the ancestor chain itself),
  but it is the assumption route `data` is written against.
- Master/detail routes in the admin areas are registered as _siblings_, which changes how you
  navigate between them — see [architecture.md](./architecture.md#the-admin-area-shell).

## Internationalization (Transloco)

**Every user-visible string is a key.** Templates, TypeScript, wrapper inputs, validation messages,
toasts, dialogs, menus and tests — no hardcoded Danish or English.

| Where                                           | API                                                                                                      |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Templates                                       | the `transloco` pipe — `{{ 'menu.editor' \| transloco }}`; the structural directive is not used anywhere |
| TypeScript, reactive value                      | `translateSignal('common.timePicker.hours')`                                                             |
| TypeScript, one-shot string                     | `TranslocoService.translate(key, params)` — toasts, tab titles, confirm text                             |
| A row that must render in a _specific_ language | `selectTranslate(key, undefined, lang)`                                                                  |

### Translation ownership

**The component that puts text into the DOM owns the `| transloco` call.** Keys — literal strings —
cross component and service boundaries; nothing is pre-translated:

```html
<app-areas-field placeholder="messages.filter.area.placeholder" … />
```

The field translates its own `placeholder` and derives its accessible name from the same key. This
applies to every key-shaped input (`placeholder`, `label`, `header`, `tooltip`, `aria-*`); free text
(a search query, user-typed content) passes through unchanged. Services follow the same rule:
`NotificationService.success(key, params)` takes a key and translates it itself.

**One sanctioned exception: the map kit.** `shared-map/` belongs to both applications and may
depend on nothing but `@angular/*`, `ol/*` and `rxjs`, so it cannot translate: each application
brings its own bundles, and the kit renders what it is given verbatim. Its text inputs therefore
take translated text — the layer switcher's `title`, each layer's `name` (tile, OpenSeaMap, WMS) and
the tile layer's `attributions` — and the host puts the `| transloco` call on those bindings, where
it would otherwise pass the key:

```html
<app-ol-layer-switcher [title]="'messages.map.layerSwitcher.title' | transloco" />
```

The host is still the one component that knows the key, so the rule's point — one place owns each
translation — holds. Nothing else in the workspace takes pre-translated text on this ground; a
component of an application's own takes a key.

### Keys and placeholders

- Dot-separated, feature-first (`messages.list.error`, `editor.attachments.widthPlaceholder`). Two
  groups are not feature-first: `common.*` holds the generic UI vocabulary the shared controls need
  (`common.retry`, `common.loading`, `common.calendar`, `common.timePicker`, `common.dateTime`), and
  `shared.*` holds the text belonging to a named shared component (`shared.confirm`,
  `shared.entityTree`, `shared.pager`, `shared.forms`, `shared.richText`, `shared.map`, …).
  Everything else goes under its feature. Keep a new key next to its neighbours.
- **Flat or nested is a real choice.** The bundle mixes flat dot-containing top-level keys with
  nested groups and both resolve, but a key an administrator must be able to override from the
  sysadmin Dictionaries screen has to sit in the flat space, because backend Dictionary entries merge
  into the same keyspace at runtime — see [platform.md](./platform.md). Whichever you choose, add the
  key to `en.json` only: every other language is a country's pack (Danish is `niord-dk`'s), and a
  key a pack lacks fails that country's pack check (why a one-sided key is not merely cosmetic:
  [platform.md](./platform.md) → _Key conventions_). A key `en.json` no longer has is stripped from
  the staged bundles; a renamed key is named in `languages/en/renames.json` and named as a rename
  in the PR, so each country migrates its pack.
- Keys are stable: changing the displayed wording never changes the key.
- **Composing a key from a closed backend enum is the standard pattern**, not an exception —
  `{{ 'msg.status.' + status | transloco }}`, or `` `admin.publications.rule.${rule}` `` in
  TypeScript. The constraint is that the value set is closed and every member has a key.
- **Placeholders are named** (`{{volume}}`, `{{count}}`, `{{date}}`); positional placeholders do not
  exist in the bundles. The parameter names a call site passes must match the compiled value's
  tokens, because runtime overrides are matched against them — see [platform.md](./platform.md).
- The language list is configuration, never a hardcoded pair; any per-language surface scales to N
  languages. See [country-deployments.md](./country-deployments.md).
- **A base product PR writes no language but English.** Danish wording is `niord-dk`'s pack,
  translated and accepted there by the product owner, and so is every other language's in its home
  repository. A value a country authors goes into its pack as plain text and is listed in that
  pack's `status/unaccepted.json`, where it stays a proposal until a reader of the language accepts
  it; `languages:accept` there takes it off the list and records the English text it was accepted
  against. The value never carries a marker: the list is the only thing distinguishing a proposal
  from accepted copy, and no build ships it.
- Server-translated payloads must refetch when the language changes — the rule and the patterns are
  in [architecture.md](./architecture.md#data-flow-api-service--facade--component).

## Where the rest lives

| Topic                                                 | Document                                                 |
| ----------------------------------------------------- | -------------------------------------------------------- |
| Layers, data flow, extension points, admin shell, map | [architecture.md](./architecture.md)                     |
| Bootstrap, config, i18n loading, deployment           | [platform.md](./platform.md)                             |
| Shared components and accessibility                   | [ui-standards.md](./ui-standards.md)                     |
| Tokens and component classes                          | [styling-reference.md](./styling-reference.md)           |
| Types, strictness, naming                             | [typescript-conventions.md](./typescript-conventions.md) |
| Error handling, dates and time zones, comments        | [coding-standards.md](./coding-standards.md)             |
| Unit specs, zoneless settling, e2e                    | [testing.md](./testing.md)                               |
