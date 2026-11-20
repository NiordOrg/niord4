# UI standards

How the user interface is built: which shared component to reach for, what each one's contract is,
and the rules that keep the surfaces consistent. Code structure and data flow live in
[`architecture.md`](./architecture.md); the token and class catalogue lives in
[`styling-reference.md`](./styling-reference.md).

## Foundation

The component layer is headless and first-party: **`@angular/cdk`** + **`@angular/aria`** for
behaviour, **`@ng-icons/lucide`** for icons, **Tailwind CSS v4** for styling. Everything an
interaction needs — focus management, positioning, scroll-lock, keyboard navigation — is wrapped
once in `ui-apps/admin/src/app/shared/` and consumed from there.

Three rules follow:

- **Compose the shared wrappers; do not re-wire raw CDK per feature.** If a wrapper exists for the
  interaction, use it. If one does not, add it to `shared/` rather than to the feature.
- **Do not add a component library.** No PrimeNG, no Material, no paid component packs. A
  third-party widget brings its own theme, its own a11y assumptions and its own upgrade axis; the
  wrapper layer exists so none of that enters the app.
- **No business logic in templates.** Keep it in components, facades or services.
- **Reuse the pattern that already exists; do not invent a second one.** Before building a
  layout, a navigation step, a control or an icon for an action, find where the app already does
  the same job and do it the same way — same component, same glyph, same position. A screen that
  solves a solved problem differently is a defect even when the new way is defensible on its own:
  the user has to learn it, and the next screen has two precedents to choose from. If the existing
  pattern is genuinely wrong for the case, change the pattern everywhere or raise it — never fork
  it locally.

## Overlay surface — choose by intent

The same intent always gets the same surface.

| Surface              | Use for                                                                                                    | Primitive                                                    |
| -------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| **Right drawer**     | Manage or edit a record _against its list_ — message details, tag management, admin entity editors         | `<app-drawer>`                                               |
| **Centered modal**   | A read-one that interrupts, or a focused task — import, export-with-options                                | `<app-dialog>`                                               |
| **Confirm dialog**   | Destroy / irreversible — delete, publish-with-cancel                                                       | `ConfirmService` + `<app-confirm>`                           |
| **Anchored popover** | A quick in-context pick or membership toggle — tag/area/category pickers, filter-chip editors, popup menus | `<app-picker-popover>`, `<app-menu>`                         |
| **No overlay**       | A plain download; and the live filter bar, which is inline and always visible                              | a link or menu item; the filter bar is part of the list page |

**Never stack two drawers.** A sub-action launched from inside a drawer becomes a popover (a light
pick), a modal or confirm (a focused task), or an in-drawer drill-in (replace the body and add a
back arrow). Only the topmost layer draws a backdrop, and CDK routes Escape to the topmost overlay
only — so no stand-down bookkeeping is needed. "Add to tag" from the details drawer opens the tag
popover _above_ the drawer.

**Message details is a drawer, not a modal**, specifically so the list stays visible behind it.

### Anchored-overlay positioning discipline

An anchored panel must present as a **fixed, viewport-bounded box whose size does not depend on
async or interactive content**. `<app-picker-popover>`
([`ui-apps/admin/src/app/shared/components/picker-popover/`](../ui-apps/admin/src/app/shared/components/picker-popover/)) owns
half of that contract: four candidate positions are solved once at show time and frozen with
`lockPosition` (`growAfterOpen` and `flexibleDimensions` off), so the panel opens instantly and
never visibly re-positions. When none of the four fits — a short window with the trigger
mid-screen — push moves the panel inside the viewport, over its trigger if it must; the push is
solved at the same moment and frozen with it, so a panel is never partly off-screen. It has no
backdrop; the trigger's own click owns re-click-to-close.

The consumer owns the other half: **the scroll region needs a fixed, viewport-bounded height**
(`h-[min(18rem,45vh)]`, or a tree `scrollHeight`). Without it the one-time position is wrong. The
accepted trade-off is that a short list shows some empty space rather than hugging its content.
Narrow fixed-size content (the calendar, the time columns) opts into `panelWidth="fit-content"`.
Never size a picker to fill the available side — it reads as a modal and can cover its own trigger.
Popup menus open instantly and grow in place; a tall menu just scrolls.

## Overlay contracts

### `<app-drawer>`

The parent owns visibility; the drawer only _requests_ closes.

| Binding          | Contract                                                                                                                                                                |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `[visible]`      | Parent-owned. The drawer never flips its own state.                                                                                                                     |
| `(closed)`       | The ask — a close intent passed the unsaved-changes gate. The parent clears `visible`.                                                                                  |
| `(hidden)`       | Fires later, once the slide-out has finished and the overlay is disposed. Wait for it before destroying the drawer's host. Never fires when a reopen cancels the close. |
| `[dirty]`        | Gates ✕ / Cancel / Esc / backdrop, plus refresh and in-app navigation.                                                                                                  |
| `[size]`         | `default` (520 px), `wide` (820 px), `xwide` (1100 px), viewport-clamped; capped at 90vw from `md` up, 100vw below.                                                     |
| `[initialFocus]` | `'close'` (default) for a read-first drawer; `'first'` for an editor.                                                                                                   |

`initialFocus="first"` is not cosmetic: landing on the ✕ costs a Tab before the first keystroke and
makes the first key a writer presses the one that closes the drawer. It lands on the first writable
_field_ only — a button is not where a writer starts.

**Every close path funnels through one gate.** ✕, footer Cancel, Escape and backdrop all call
`requestClose()`, which consults `DiscardConfirmService`. Refresh and tab-close are caught by a
native `beforeunload` binding; in-app route changes are caught by `drawerUnsavedChangesGuard`, which
reads the registry every open dirty drawer writes into (`DrawerGateService`). Widening a drawer from
a stylesheet silently fails — CDK writes the pane's size as inline styles, so the drawer uses
`OverlayRef.updateSize`.

### Entity create/edit surfaces

Every entity create/edit form in a list section uses the drawer editor pattern:

- a role-named `<entity>-editor` component;
- a Signal Forms save gate — `computed(() => !changed() || form().invalid() || saving())`;
  `form().invalid()` is a memoized signal read and is safe inside a `computed()`;
- drawer `[dirty]` bound to the **same** `changed()` signal — the unsaved-changes exit guard is
  mandatory for every editor, whatever its container;
- `initialFocus="first"`;
- a top-level `<div drawerFooter>` with a `.btn-tertiary` Cancel and a `.btn-primary` Save.

### `<app-dialog>`

Two-way `[(visible)]`, `[header]` rendered verbatim (the consumer translates), `[style]` as the
width mechanism (inline styles on the panel), and the same optional `[dirty]` gate as the drawer.
Horizontally centred and **top-anchored** at `[top]` (default `5vh`), so a tall dialog grows
downward from a stable position instead of recentering as its content changes; a near-fullscreen
panel passes a smaller offset (`top="1rem"`). Block scroll, focus trap with auto-capture and
restore. `[dismissableMask]` is off by default. An optional `[describedBy]` takes the id of a body
element the panel's `aria-describedby` points at, for a warning a screen reader should announce
when the dialog opens.

### Confirmations

Route every confirmation through `ConfirmService` —
[`ui-apps/admin/src/app/shared/services/confirm.service.ts`](../ui-apps/admin/src/app/shared/services/confirm.service.ts) —
using `confirmDestructive` (red accept: delete, irreversible removal) or `confirmNeutral` (primary
accept: resets, benign confirmations). The service is a DOM-free signal bus, so the intent →
button-styling mapping lives in one view. An optional `details` list (already translated, like
`message`) renders as a bulleted list under the message — what else a delete affects, for example —
and scrolls inside the panel when long; the message and the list describe the dialog to a screen
reader (`aria-describedby`).

Two contracts that are easy to miss:

- **`ConfirmService` is deliberately not `providedIn: 'root'`.** `<app-confirm>` injects the
  _nearest_ instance, so a component that re-provides its own and mounts its own `<app-confirm>`
  gets a scoped confirm; everything else binds the app-level instance. `DiscardConfirmService` owns
  a private bus rendered by the always-mounted `DiscardConfirmHost`, so discard prompts can never
  collide with a feature confirm.
- **Backdrop click is intentionally inert** (destructive safety); Escape rejects.

### `DialogHostService<TResult>`

The pattern for a dialog that hands a value back to an orchestrating caller
([`ui-apps/admin/src/app/shared/dialogs/dialog-host.service.ts`](../ui-apps/admin/src/app/shared/dialogs/dialog-host.service.ts)):
the route mounts one host component per dialog kind, the component binds `[(visible)]` to
`svc.visible`, and the caller subscribes to the observable `open(...)` returns. Subclasses call
`armOpen()` **first** — it resolves any still-live prior open as a dismissal and installs a fresh
Subject, so an earlier caller can never receive a later open's result — then `submit(value)` or
`dismiss()`. `onVisibleChange(false)` binds straight to `<app-dialog (visibleChange)>`.

### Menus

`<app-menu>` renders a `MenuItem[]` into a CDK menu panel and exposes it as a `TemplateRef`; it
renders no trigger of its own. Open it with `appMenuTriggerFor`, or with the styled
`<app-menu-trigger [menu]="…">`.

**Never provide `MENU_TRIGGER` / `MENU_STACK` at component level.** Each menu level is reached
through an `ngTemplateOutlet` hop to a shared template, which severs CDK's per-trigger portal
injector. Providing those tokens on the component is the tempting fix and makes a nested `cdkMenu`
resolve the _root_ trigger, which is a synchronous infinite loop in `MenuStack.closeSubMenuOf` — a
frozen tab. The correct mechanism is already in place: the `appMenuOutletInjector` directive
captures each level's portal injector and forwards it via `[ngTemplateOutletInjector]`.

**An actions menu opens from one trigger everywhere: `<app-menu-trigger icon="lucideSettings">`
with its chevron.** That holds for a page toolbar, a detail-page header and a table row alike — the
message list's per-row menu and every admin page's toolbar menu are the reference. No `⋯`, no bare
cog with the chevron switched off, no hand-rolled `icon-button` with `appMenuTriggerFor`, no border
of its own. When the menu sits beside a button that would also carry a cog, the _button_ takes
another glyph (an edit surface takes `lucidePencil`). A trigger with a visible label — "Add …",
"Action" — is the same component with `[label]`.

### Toast

Mount exactly one `<app-toast>` at the app shell. It attaches a single persistent top-right CDK
overlay and renders `NotificationService.messages()` inside it — the container stays put and only
rows come and go. Each toast is `role="alert"` inside an `aria-live="polite"` stack. Auto-dismiss
timing belongs to the store, not the component. When to toast at all is an error-handling rule —
see [`coding-standards.md`](./coding-standards.md).

## Shared component catalogue

Everything below lives under [`ui-apps/admin/src/app/shared/`](../ui-apps/admin/src/app/shared/). Read the class doc comment
before using one; each states its own contract.

| Component                                                                                                                                                          | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<app-tabs>` / `-tab-list` / `-tab` / `-tab-panels` / `-tab-panel`                                                                                                 | On `@angular/aria` tabs (roles, roving tabindex, `aria-selected`, `aria-controls` cross-link by matching `value`). **Panels render eagerly** — all stay mounted, inactive ones hidden via `inert` — so an inactive tab's on-init fetch still runs.                                                                                                                                                                                                                                                                      |
| `<app-entity-tree>`                                                                                                                                                | Single-select hierarchical browser on CDK tree. **Stateless**: a row click just emits `entitySelected`, so no selection highlight leaks across dialog reopens. Optional drag-reparent with a 5 px click threshold.                                                                                                                                                                                                                                                                                                      |
| `<app-tree-picker>`                                                                                                                                                | Multi-select checkbox tree in a popover. Selection is a pure computed over `selectedIds` — no internal selection state, and no ancestor/descendant propagation (areas and categories filter by their own id, not by ancestry).                                                                                                                                                                                                                                                                                          |
| Data tables                                                                                                                                                        | A plain semantic `<table>` wearing the `.niord-table` class. **There is no `CdkTable`.** `.niord-table` is for genuinely interactive data — sortable, filterable, paged or selectable. A label/value read-out is not a table: use `<dl class="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1">`.                                                                                                                                                                                                                      |
| `<app-pager>`                                                                                                                                                      | Purely presentational: bind `[first]`/`[rows]`/`[total]`, apply `(pageChange)`. Self-hides below two pages; renders the count language-neutrally as `n / m`. Paging **state** stays with the caller.                                                                                                                                                                                                                                                                                                                    |
| `<app-card>`                                                                                                                                                       | Rounded bordered section with an optional header band carrying a right-aligned `[cardActions]` slot. `[padded]` (default true), `[fill]` (flex column whose body grows and scrolls), `sectionClass`. Host is `display: contents` so a `fill` card's `<section>` is the direct grid/flex child and the shrink/scroll chain works.                                                                                                                                                                                        |
| `<app-empty-state>`                                                                                                                                                | `[compact]` for a small in-page surface: no glyph, half the vertical room, one text tier down — a full-height placeholder inside a dashboard card outweighs the cards that do have content.                                                                                                                                                                                                                                                                                                                             |
| `<app-progress-bar>`                                                                                                                                               | Indeterminate and determinate; `overlay` mode pins it to the top of the nearest positioned ancestor.                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `<app-error-banner>`, `<app-filter-panel>`, `<app-copy-link-button>`, `<app-expected-date>`, `<app-tag-scope-select>`, `<app-json-diff-pane>`, `<app-coming-soon>` | Smaller shared surfaces; see each class doc comment.                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `<app-sandboxed-html>`                                                                                                                                             | The **only** sanctioned way to render a backend-generated HTML document (mail bodies): an `<iframe srcdoc>` with an empty `sandbox` attribute, so scripts are inert and the origin is opaque, while the document keeps its own `<head>`/`<style>`. The `bypassSecurityTrustHtml` inside is safe by construction of that sandbox — never reuse the pattern outside one. The other trusted string in the workspace is the message-HTML sanitiser's own output (`shared-html/`), which is trusted because it sanitised it. |
| `<app-file-upload>`                                                                                                                                                | Auto-uploads each selected or dropped file, **one multipart request per file** under the part name `file`. The bar is necessarily indeterminate: the app's fetch HTTP backend throws on upload-progress reporting, and moving the whole app to XHR for a percentage was rejected.                                                                                                                                                                                                                                       |

## Form primitives

Edit flows use **Signal Forms** only (`@angular/forms/signals`). The shared controls live in
[`ui-apps/admin/src/app/shared/forms/`](../ui-apps/admin/src/app/shared/forms/); every one of them that is **bindable with
`[formField]`** implements `FormValueControl<T>`:

- `value = model<T>()` — two-way, bindable with `[formField]` inside a `form()` **or** standalone
  via `[value]`/`(valueChange)`;
- `disabled = input(false)` — written by the form;
- `touch = output<void>()` — emitted on blur so touched-tracking and `debounce('blur')` work.

The presentational sub-components in the same folder — `autocomplete`, `autocomplete-option`,
`select-option`, `calendar`, `entity-multiselect-control` — implement nothing of that contract;
they take plain `input()`s and emit their own events, and a `FormValueControl` wrapper composes
them.

Native inputs keep their built-in value accessor, which a number of simple surfaces (confirm and
settings dialogs, the messages filter panels, a few sysadmin list editors) use for one-way
`[ngModel]` → signal glue. Classic reactive forms (`FormGroup` / `formControlName` /
`ReactiveFormsModule`) are not used anywhere.

### A schema rule must reach the control that carries it

Every field a schema constrains is bound with `[formField]` — that directive is what reflects the
constraint onto the native element, and `.text-input:user-invalid` is what marks the field on screen;
a native `min=` / `max=` attribute beside it is the mirror mistake. The rule and its traps are in
[`angular-conventions.md`](./angular-conventions.md) → _Signal Forms_. A rule that genuinely belongs
to the server is not declared in the schema at all.

### The controls

| Control                                                | Contract                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<app-select>`                                         | `role="combobox"` trigger portalling a `role="listbox"` panel (CDK overlay + `ActiveDescendantKeyManager`), below-first with a flip-above fallback. Setting `optionValue` makes the value a scalar field of the option; without it the whole option object **is** the value; object options are matched to it by label only when `optionLabel` is set, by identity otherwise. `[emptyIsNoSelection]="true"` treats `''` as "nothing chosen" and shows the placeholder — opt-in, because several lists carry a deliberate labelled empty option that _is_ a selection. `[clearOnPick]="true"` makes it an action trigger: every pick emits `(picked)`, also the same option twice, and no value is kept (the trigger shows the placeholder). |
| `<app-autocomplete>` — **presentation only**           | Chip combobox, not `[formField]`-bindable: its `value` is a plain `input<readonly T[]>`, it has no `touch`, and it emits `(select)` with the new value array. It holds **no** search or loading state either — the consumer debounces `(query)`, fetches, and feeds `suggestions` / `value` / `fetchError` back in. The `FormValueControl` wrappers around it are `AutocompleteSearchField` (single value) and `EntityMultiselectField` (multi); bind one of those in a form.                                                                                                                                                                                                                                                               |
| `<app-date-picker>`, `<app-date-time-field>`           | Carry **instants**. `[timeZone]` names the IANA zone the shown and typed wall clock belongs to (default: the session domain's); `[local]="true"` opts out and carries the browser clock unconverted; with **neither**, the control renders disabled with `aria-disabled` and an explanatory title rather than standing in the runner's zone. A guessed zone is as wrong as a hardcoded one.                                                                                                                                                                                                                                                                                                                                                 |
| `<app-time-field>`                                     | The bare `HH:mm` carrier, and it deliberately takes **no** zone. A time of day with no date is not an instant, so there is nothing for a zone to resolve — which is exactly what a cut-off time or a scheduled send time needs. A caller holding a real instant converts on either side.                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `<app-localized-inputs>`                               | One input per configured model language, flag painted inside the field. Controlled component: the caller feeds a `Record<lang, string>` and re-applies it on each `(valueInput)`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `<app-rich-text-editor>`                               | TipTap, loaded **lazily** on first mount — nothing eagerly loaded may import the engine module or `@tiptap/*` statically, or the editor lands in the main bundle. Three presets: `full` (message description), `inline` (publication — inline-only schema with no root `<p>`, preserving arbitrary inline attributes), `basic` (comments). **Single write path**: programmatic writes never emit `valueChange`; only genuine user edits do. TipTap is the only rich-text engine — never add a second.                                                                                                                                                                                                                                       |
| `<app-code-text-input>` (`features/admin/components/`) | Plain wrapping `<textarea>` by default — zero editor bundle. Pass `[language]` and a lazy CodeMirror 6 editor mounts behind the same string value, with the textarea as the fallback until it does. CodeMirror is the only code editor — never add a second.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `<app-format-token-input>`                             | A format field with an Insert menu listing tokens by friendly label; picking one splices the literal token at the caret and parks the caret after it. A field that has never held the caret for the current text appends instead — an untouched field must not insert at position 0. Token gating stays with the consumer.                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `<app-length-counter>` — **presentation only**         | "n of max characters" under a text input whose column has a limit. The limit is the form's: a `validate()` rule with `lengthError` (or `lengthErrorAny` for a per-language map) holds Save back, never the schema's `maxLength()`, which `[formField]` reflects onto a native input as `maxlength` and the browser then cuts a longer paste without a word. Counts characters as PostgreSQL does (code points). Over the limit the line turns red and becomes an alert; a native input points `aria-describedby` at its `counterId`; `language` names the row under a group of per-language inputs.                                                                                                                                         |

Zone rules, helpers and the lint rule that enforces them are in
[`coding-standards.md`](./coding-standards.md) → "Dates and time zones".

### A per-language field's placeholder is keyed, not pre-translated

`<app-localized-inputs>` and `<app-localized-text-input>` (`features/admin/components/`) take a
`placeholderKey` and resolve it in each row's **own** language via
`selectTranslate(key, undefined, lang)` — so the Danish row hints in
Danish beside the English one hinting in English, whichever language the operator is running the app
in. Passing an already-translated `placeholder` hints every row in the active language, which is
wrong on every row but one.

Layout must tolerate longer translated labels, and per-language groups scale to **N** configured
languages — two languages is a coincidence, not a contract.

### Field guidance is keyed beside the labels, not mixed into them

Explanatory prose under an input, select or date field is a `<p class="text-xs text-fg-muted">`
whose key lives in the section's own `hint` node, grouped so a translator sees the guidance together
and does not have to tell a control's **label** from prose about it. A checkbox whose own label _is_
the explanation stays a `field` key, because there it is the label. Hints are owed where a section
carries concepts a reader will not already know — not on every field.

### A server refusal renders as a sentence, never as wire text

Rule ids, wire field paths (`descs.da.name`) and the server's own English message are not UI copy.
Map the field path to the label the form already shows, say the refusal through a translated key,
and carry the code only as the reference an operator can quote.
[`publication-errors.ts`](../ui-apps/admin/src/app/features/admin/sections/admin/publications/publication-errors.ts)
is the worked example.

One exception: the sysadmin pages' import dialogs (Script resources, Dictionaries) show the import
preview's `reason` and the import job's answer as the backend sends them, because each names a token,
class, path or limit the sysadmin must see verbatim to fix the file; see
[features/sysadmin.md](features/sysadmin.md).

## Icons

`<app-icon name="lucidePencil" />`, where `name` is the `@ng-icons/lucide` export identifier. Every
glyph used anywhere in the app is imported and registered once in
[`ui-apps/admin/src/app/shared/icons/niord-icons.ts`](../ui-apps/admin/src/app/shared/icons/niord-icons.ts) and provided through
`provideNiordIcons()`. "Exists in Lucide" is not "the identifier is registered" — verify a new glyph
resolves before shipping it.

`<app-icon>` is **decorative** (`aria-hidden`), so the accessible name goes on the interactive
element. Size defaults to `1em`, so a glyph scales with the surrounding font-size.

`<app-icon>` draws the glyph itself, in the markup `<ng-icon>` drew (a box of the icon's size holding
the `<svg>`), from the same registry: it parses each glyph's SVG once and clones it for every icon,
where `<ng-icon>` parsed it for every icon — a message list shows some 700 of them. Use `<app-icon>`,
never `<ng-icon>`.

## Component traps

Seven failure modes that have each cost real debugging time. All seven are live conventions in the
code.

1. **A native `<select>` binds `[selected]` per `<option>`, never `[value]` on the select.** Angular
   applies the select binding before the `@for` options exist, so a non-default value silently fails
   to display — the classic symptom is undo restoring the data while the dropdown shows the default.
2. **An overlay's enter animation is the Web Animations API called synchronously at attach.** CDK
   applies `panelClass` _after_ DOM insertion, so `@starting-style` never fires; a bare
   `requestAnimationFrame` lands a frame late and the pane flashes fully open first. The drawer is
   the reference: `animate()` right after `attach()`, a resting class holding the end state, and the
   CSS transition handling only the close.
3. **`cdkDrag` goes on the row; `cdkDragHandle` goes on a small grip inside it.** `cdkDrag` on a row
   containing clickable children swallows those clicks.
4. **Narrow `event.target` with `instanceof Element`, never `HTMLElement`.** Lucide glyphs are
   `<svg>` / `<path>`, i.e. `SVGElement`; `HTMLElement` silently skips every icon click. `Element` is
   the common base and is where `.closest()` is defined.
5. **`ng-content select=` matches top-level content children only.** Angular treats a control-flow
   block as one projection unit, so an attribute-selected node nested inside `@if` lands in the
   default slot — the footer renders in the body while the real footer stays invisible behind
   `empty:hidden`. Put the slotted node at the top level and the conditional logic **inside** it (or
   project it explicitly with `ngProjectAs`).
6. **A full-bleed bar needs `z-30`.** The `relative left-1/2 w-screen -translate-x-1/2` pattern
   applies a transform, which makes the bar its own stacking context and traps absolutely-positioned
   child popups below any later sibling that is itself a stacking context (a sticky `<th>` always
   is). Raising the panel's own z-index does nothing; lift the whole bar.
7. **A conditional colour override needs `[style.*]` bound to a token.** A conditionally-added
   Tailwind colour utility does not beat a custom-theme colour utility of equal specificity — the
   `@theme` colours are generated after the default palette, so the conditional colour is applied and
   invisible. And a utility that appears nowhere in source is never generated at all. Bind
   `[style.border-color]` to a colour custom property instead, never to a hex literal.

## Accessibility

- Every interactive element is keyboard reachable and operable.
- **A button acts on `(click)`**, which Enter and Space on a focused button fire as well; never on
  `(mousedown)`, which the keyboard never fires. A button beside an input that must not take its
  focus (the autocomplete's chip-remove and clear buttons, the select's clear) binds `(mousedown)`
  to `preventDefault()` alone. A button that leaves the DOM with what it removes hands the focus to
  a defined element first — the input or trigger of its control — so it is not dropped on the
  page's body; and a button repeated per item (a chip's remove) is named after its item. Rows of a
  listbox are not buttons: they keep their mousedown pick, and the keyboard picks through
  `aria-activedescendant`.
- **Icon-only controls** carry `[attr.aria-label]` on the interactive element (button or anchor),
  because `<app-icon>` is `aria-hidden`.
- **Overlays trap focus** via CDK (`cdkTrapFocus`) and restore focus to the trigger on close. The
  drawer additionally round-trips the opener's focus-**ring** state, so a mouse-opened row does not
  come back wearing a keyboard focus ring.
- **Listbox and menu keyboard navigation** goes through `ActiveDescendantKeyManager`.
- **Tab semantics** come from `@angular/aria` tabs.
- Pair `<label for="…">` with the control's `id`.
- A `<fieldset>`'s `gap-*` never reaches its `<legend>` — the legend is not a flex item of the
  fieldset — so put that spacing on the legend with `mb-*`, or on a single content wrapper with
  `pt-*`; without it the first control sits flush under the group caption.
- `text-fg-subtle` is held at WCAG AA in every brand — do not lighten it. A non-text graphic (a
  status rail, a divider that carries meaning) needs 3:1 measured against **what it actually
  touches**, which for a rail is the row background on one side and the table background on the other.
- **Colour is never the only carrier of a state.** The row-attention rail pairs with a hover title,
  so the reason survives for anyone who cannot distinguish the hue — and stays reachable when
  selection replaces the rail. A high-contrast theme repaints every colour, so a toggle whose
  pressed state is its fill gets a forced-colours rule as well
  ([`styling-reference.md`](./styling-reference.md) → _Responsive rules that live in the
  stylesheet_).
- **A link is underlined at rest.** Nothing hovers on a touch screen, and the brand primary is too
  close to body text in lightness to mark a link by colour alone. The message-HTML rules draw
  every link a message carries that way; only a publication field, whose wrapper carries
  `.message-publication`, keeps its citation in the colour of the text. A link to a file drawn by
  a template is a `.link-button`.
- **Motion follows `prefers-reduced-motion`.** The map kit's zooms and pans land at once for a
  reader who asked the system for less motion.

## Responsive rules

Tailwind v4 breakpoints are canonical: `sm` 40rem, `md` 48rem, `lg` 64rem, `xl` 80rem, `2xl` 96rem.
Express a responsive rule as a Tailwind variant wherever one fits. A hand-rolled
`mediaQuerySignal(...)` gate should use a Tailwind breakpoint value unless it has a reason not to,
so the JS side and the `lg:` / `max-lg:` utilities flip together; the editor's narrow gate at
1080px is the standing exception.

Three app-wide behaviours ride on those breakpoints:

- **Below `lg`** the admin section nav becomes a sticky single-row tab strip that pans horizontally,
  matching the header's own collapse point.
- **Below `xl`** a section table's trailing actions column is pinned so row actions stay reachable
  while the table scrolls sideways. Add `.is-unpinned` where the last column is content rather than
  row actions.
- **Below `lg`** the shared controls take a 40 px touch floor, in **one** media block at the end of
  each class file — do not add `min-h-*` at call sites; a template utility still wins, which is the
  escape hatch. Which classes grow and which get a `::after` hit box instead:
  [`styling-reference.md`](./styling-reference.md) → _Responsive rules that live in the stylesheet_.

Two related traps: `.sr-only` is `position: absolute` and escapes an unpositioned scroll box (which
is why `.admin-card` is `relative`), and a `min-width` floor that helps at 768 can break a
seven-column table at 1280 — scope such floors with `sm:max-xl:`.

## Styling layers

Styling runs through four layers — design tokens (`styles/tokens.*.css`), the Tailwind
`@theme` bridge in `styles/theme.css`, Layer-3 semantic classes in `styles/components-core.css`
and `ui-apps/admin/src/styles/components-admin.css`, and per-component CSS as the escape hatch. The layer table, the cascade order and the full token and
class catalogue live in [`styling-reference.md`](./styling-reference.md); the per-country swap
mechanism is in [`country-deployments.md`](./country-deployments.md).

The authoring rule: walk the layers top-down and fall to the **lowest one that fits** — a
universally standardized visual → `tokens.base.css`; a brand colour, spacing, radius or shadow that
varies per country → `tokens.<brand>.css`; a utility combo used twice or more → a Layer-3 class;
otherwise per-component CSS. Never hardcode a colour or size, and reach for the `@theme` utilities
(`text-fg-muted`, `bg-chrome`, `bg-surface-0`, `rounded-niord-md`) rather than arbitrary-value
brackets like `text-[var(--niord-fg-muted)]`.

Two cascade facts a component author has to carry: an explicitly applied Tailwind utility always
beats a Layer-3 class, and an override of an **overlay pane's own box** has to sit outside every
`@layer` in `components-admin.css` — the block that carries `.cdk-overlay-pane.ac-overlay-content` —
because CDK injects its own `.cdk-overlay-pane` rules (`max-width: 100%` among them) as a runtime
`<style>` outside every layer. A pane's **size** is inline-styled by CDK and no stylesheet beats it;
change it through `OverlayRef.updateSize`.

### Layer-3 authoring rules

- Inside `@layer components { … }`, Tailwind v4's `@apply` accepts only **Tailwind utility
  classes**. `@apply btn-base;` where `.btn-base` is itself Layer 3 silently fails to compile. Share
  base shape by inlining the utilities into each variant (the `.btn-primary` / `.btn-secondary` /
  `.btn-tertiary` trio is the reference) or via a plain grouped CSS rule.
- Name by **intent**, not appearance. Add a class when two or more sites share a pattern; resist
  single-site naming.
- **Country overlays never override a Layer-3 class.** A country need that no token expresses is
  answered by adding a token and parameterising the class.
- The production `styles` bundle carries a budget, which is the direct brake on Layer-3 growth.

### Transparent wrappers, and why `gap` beats `space-y-*`

Any component whose template can render nothing under some input combination (`@if`-gated content,
conditional `@for`, optional projection) **must** declare `host: { class: 'contents' }`. Angular
leaves comment placeholders inside the host even when the template collapses, so the host survives as
a 0-height element that a flex or grid parent with `gap` counts as an item — phantom spacing.
`<router-outlet>` gets the same treatment globally. Opt out only when the component carries its own
host-level visual styling (border, background, padding); then render it conditionally with `@if` in
the parent instead.

The corollary: **space a stack with a container `gap`, never `space-y-*`**, whenever any child may be
such a wrapper. Tailwind v4 hangs `space-y-*` off the **preceding** sibling's margin, and a
box-less child generates no box, so that margin is silently discarded and only the gap below it
collapses — which reads as a one-off design miss rather than a layout bug. Diagnose by measuring
`nextEl.getBoundingClientRect().top - prevInnerEl.bottom`; a `display: contents` element's own rect
is degenerate and will mislead you.

### Backend HTML is the one global-CSS exception

Angular's emulated encapsulation cannot reach content injected via `[innerHTML]`, and Tailwind's
preflight zeroes table borders and list markers — so message-part HTML from the backend is styled by
rules namespaced under a wrapper class (`.message-details-html table { … }`) in `styles.css`. Do not
use this route for first-party templates.

Those rules are **one part of a three-file contract**: the table, row and cell class options the
rich text editor offers (`no-border`, `condensed`, `positions`, `position-table`, `underline`,
`pos-col`, `pos-index`) are declared as constants in `rich-text-editor.ts`, rendered by the scoped
rules in `styles.css`, and listed in `MESSAGE_HTML_CLASSES` (`shared-html/message-html-classes.ts`),
the vocabulary both the sanitiser and the editor narrow every class to. Change one without the
others and you silently break either the toolbar or the rendered message — an option missing from
the vocabulary is dropped as soon as it is set.

## Colour and state

Pick the token that states the **role**, not the one that looks right:

| Role                                                                  | Utility              | Notes                                                                                                |
| --------------------------------------------------------------------- | -------------------- | ---------------------------------------------------------------------------------------------------- |
| Field or group label — the caption above an input, select or chip row | `text-fg-title`      | Aliases the brand primary. This is the only thing that makes such a label read as a label.           |
| Drawer or section heading (`<h2>`)                                    | `text-fg`            | Headings earn rank from **size**, not colour — two coloured tiers side by side flattens both.        |
| Body / value text                                                     | `text-fg`            | Also the document default (`body` carries it explicitly); usually inherited, so you rarely write it. |
| Secondary text, captions, read-only values                            | `text-fg-muted`      |                                                                                                      |
| Placeholders, faint captions                                          | `text-fg-subtle`     | Held at AA — do not lighten.                                                                         |
| Text on a brand fill                                                  | `text-fg-on-primary` |                                                                                                      |

**Hierarchy must come from a token you chose, never from an accident.** Worked example, because it
cost real debugging time: field titles were once plain `text-fg` beside unstyled (therefore black)
content. In the default brand `--niord-fg` is a saturated navy, visibly different from black, so the
titles _looked_ deliberately styled; in a country's brand whose `--niord-fg` is near-neutral the
identical markup rendered as ordinary text, and the hierarchy vanished on a brand swap. The fix was a
semantic `--niord-fg-title` each brand sets on purpose. Relatedly, **`font-medium` alone can never
mark a title** — a brand face without a 500 weight, such as Verdana, which a country ships, renders
`font-medium` identically to normal text. Weight reinforces; colour signals.

**Row-level status is a left rail, not a text colour or a row wash.** A row that needs attention
gets `.row-attention` — a red inset rail — while its background and every piece of text inside keep
their ordinary colours. Two rules fall out, and any future row status must respect them:

- **Selection outranks status.** `.row-selected` wins outright (`.row-attention:not(.row-selected)`)
  — the rail reverts to the accent, so a row the user just picked reads as picked. Status and
  selection use the same 3 px inset rail and are told apart by **hue**, not shape.
- **A status rail must be distinguishable from the selection rail in every brand.** `--niord-accent`
  is a teal in the default brand and can be an orange in a country's, so a hue that reads apart from
  one may not read apart from the other. Measure the default brand and a country's, not the one you
  happen to be running.

**Status widgets read locals, they do not know statuses.** A state class sets `--status-*` and every
widget class reads them, so one pipe (`statusClass`) drives every status-aware widget. **Add a new
status by adding a state class, not by touching widgets.** The protocol, the state-class families and
the `color-mix` rule are in [`styling-reference.md`](./styling-reference.md) → _The status protocol_.

### Button tiers

- `.btn-primary` is the affirmative action (Save, Create, Publish) and is country-flippable via a
  token. `.btn-secondary` / `.btn-tertiary` are lower emphasis; `.btn-danger` is destructive.
- **Close and Cancel are the quiet tier** — `.btn-tertiary`. Picker "Done" buttons follow. For a
  borderless icon action use `.icon-button` rather than re-rolling `rounded p-1 hover:bg-surface-*`.
- **A text link that sits beside what it acts on is `.link-button.is-quiet`, not `.is-subtle`.** Both
  drop the underline and the medium weight; only `.is-quiet` drops the brand colour. A disclosure
  toggle or a per-row secondary action in brand blue is the loudest thing in a row whose point is the
  value next to it. Keep `.is-subtle` where the link _is_ the point — a document name, a count that
  opens its own screen, an "Add" that starts a row.

### Two admin frames, and they are not interchangeable

`.admin-card` is the shell around a section's **table**; `.admin-panel` (+ `-header` / `-body`) is
the frame for a detail screen's read-and-act sections, and a bare `<h4>` inside a padded box is not
a panel header. What each carries and why is in [`styling-reference.md`](./styling-reference.md) →
_Section chrome_.

### Dark-mode reachability

Layer-3 classes and component templates must not use Tailwind's hard-coded colour palette
(`text-slate-900`, `bg-white`, `border-gray-200`) for project-level colour. Read the project tokens
(`text-fg-muted`, `bg-bg`) or the surface scale (`bg-surface-0`, `border-surface-200`). Hard-coded
Tailwind colours are reserved for visuals that genuinely do not theme (a deployment-mode badge red,
a `bg-white/10` overlay on dark chrome).

The shared `styles/theme.css`, which both entry stylesheets import, declares
`@custom-variant dark (&:where(.app-dark, .app-dark *));`, so `dark:` utilities already track an
`.app-dark` class on `<html>` in both applications and never the operating system's colour scheme. Shipping dark mode is therefore a
tokens-file addition — author `tokens.dark.css` with dark values for the same `--niord-*` names,
import it after the active brand tokens, and wire the toggle. No component needs to change.

## Maps

Map surfaces are built from the shared OpenLayers shell; its structure (the map shell's
inherit-or-create contract, layer registration, the tile-load gate, base layers and the protected
WMS) is described in [`architecture.md`](./architecture.md). Two rules belong here:

- **A canvas colour comes from the portrayal definition or from a token, never from a literal.**
  The S-124 portrayal's colours — the magenta pen and the affected-area overlay on a message map —
  are data in [`shared-map/portrayal/portrayal.json`](../shared-map/portrayal/portrayal.json), the
  one definition both applications' maps and the backend's map images read. Every other map colour
  (halos, the count badge, pins, the editor and overlay layers) crosses the CSS/JS boundary through
  `readCssVar` only: OpenLayers renders to a canvas and cannot resolve `var(...)`, so it is read at
  runtime from the Layer-2 tokens via [`shared-map/css-tokens.ts`](../shared-map/css-tokens.ts),
  with a literal fallback. Read at construction, not at module evaluation — module eval can precede
  the stylesheet. Never hardcode a colour literal in a style module.
- **Marine-warning symbology is standardized, not branded.** The portrayal definition and the
  marker tokens in `tokens.base.css` are country-locked and must not be rebranded per deployment —
  see [`map-portrayal.md`](./map-portrayal.md).

## Owned elsewhere

Generic Angular and TypeScript API rules — signals and `linkedSignal`, `input()` / `output()` /
`model()`, `inject()`, native control flow, standalone components, lazy routes — are owned by the
vendored [`angular-developer`](../.claude/skills/angular-developer/) skill and the `angular-cli`
MCP; verify version-specific facts there. Project Angular idioms are in
[`angular-conventions.md`](./angular-conventions.md), error handling and date/time policy in
[`coding-standards.md`](./coding-standards.md), and spec recipes (including how to test a CDK
overlay under zoneless change detection) in [`testing.md`](./testing.md).
