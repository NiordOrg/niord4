# Styling reference

The catalogue of every design token and every named CSS class the application defines
globally. Each entry names the file it lives in; find it there by grepping its name.

Use it to answer "where is X defined" and "what should I use for Y". For _when_ to reach
for a given control and how the shared components behave, see
[`ui-standards.md`](./ui-standards.md). For producing a branded build for another country,
see [`country-deployments.md`](./country-deployments.md).

---

## 1. The layers

| Layer                 | Source                                                                                              | Shared with the public site | Role                                                                                                      |
| --------------------- | --------------------------------------------------------------------------------------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------- |
| 2a. CSS tokens        | [`styles/tokens.*.css`](../styles/)                                                                 | yes                         | Brand vocabulary as `--niord-*` custom properties (colour, radius, shadow, font)                          |
| 2b. Tailwind `@theme` | [`styles/theme.css`](../styles/theme.css)                                                           | yes                         | Re-exports those tokens as first-class Tailwind utilities                                                 |
| 3. Named classes      | [`styles/components-core.css`](../styles/components-core.css)                                       | yes                         | The composite semantic classes both applications render (`.btn-primary`, `.status-badge`, `.dialog-*`, …) |
| 3. Named classes      | [`ui-apps/admin/src/styles/components-admin.css`](../ui-apps/admin/src/styles/components-admin.css) | no — this application only  | The rest: `.message-table`, the autocomplete and select chrome, the trees, the toasts, …                  |
| 3. Element rules      | [`styles/message-html.css`](../styles/message-html.css)                                             | yes                         | The one carve-out: element-level rules under `.message-details-html`, for the HTML a message carries      |
| 4. Per-component CSS  | inside each component                                                                               | no                          | Last resort — only for visuals unique to that component                                                   |

Numbering starts at 2 and matches the `Layer 2` / `Layer 3` markers written in the source
files; there is no layer 1.

`fe/styles/` belongs to neither application: both entry stylesheets reach into it, and nothing
in it may name an application. Each application owns its own entry stylesheet and its own
Layer-3 remainder; the `Shared` column above says which is which.

Class names state **intent** (`.status-badge`, `.btn-primary`), never appearance
(`.red-pill`), so a token change re-skins every use site without a codebase sweep. Country
overlays never override a Layer-3 class — a country need that no token expresses is
answered by adding a token and parameterising the class through it.

### Cascade order

[`styles.css`](../ui-apps/admin/src/styles.css) declares:

```css
@layer base, components, utilities;
```

Tailwind's preflight sits in `base`, the named classes in `components`, and utilities win
over them — so a utility written on a template always beats a Layer-3 class. Above all
layers sits a small block of **unlayered** rules in `components-admin.css` — grep
`UNLAYERED OVERRIDES` (§5.10).

### The utility scan

Tailwind emits a utility for every candidate string it finds, and what it reads is decided by
the entry stylesheet. Without a `source(…)` the compiler's base is the directory the build runs
from — the whole workspace — so the administration build used to emit the utilities of the
other application's templates and of every class name written in prose in these documents.
Each entry stylesheet therefore pins its own scan, and the paths are relative to the entry
file's own directory:

```css
/* ui-apps/admin/src/styles.css */
@import 'tailwindcss' source(none);
@source '../../../ui-apps/admin/src';
@source '../../../shared-map';
```

The entry file sits three folders below the workspace (`ui-apps/admin/src/`), so each path climbs
three levels; a path one level short resolves inside `ui-apps/` and scans nothing, without an
error. The public site's `ui-apps/info/src/styles.css` carries the same lines with
`ui-apps/info/src` in place of `ui-apps/admin/src`, plus an `@source not` for the spec files of
each folder: a spec that quotes a class name out of a message's HTML would otherwise emit a
utility of that name, which outranks the message-HTML rule it was named after. The shared map kit
is scanned by both because its components carry utilities of their own, which have to survive in
both builds. `styles/` is deliberately not scanned: `@apply` resolves utilities directly, and
scanning a stylesheet only invites phantom candidates.

The public site's entry stylesheet then imports `tokens.base.css`, `theme.css`,
`components-core.css` and `message-html.css` from `styles/`, and two files of its own under
`ui-apps/info/src/styles/`: the site chrome and the print stylesheet. It loads no
`components-admin.css`: that file is this application's half of the split, and the public site
has no consumer for any of it. Both applications therefore render a message's own HTML through
the same rules — the details of a message part, its publication reference, and in the
administration app the editor surface that writes them.

`@angular/cdk/overlay-prebuilt.css` is imported with `layer(base)` by **both** entry
stylesheets — the administration app portals its overlays, the public site its dialogs — so a
class or utility handed to `panelClass` / `backdropClass` can override the pane and backdrop
chrome it carries. That import does **not** settle everything: `@angular/cdk` also injects its own
copy of the `.cdk-overlay-pane` rules — `max-width: 100%` among them — as a runtime
`<style>` element outside every layer, and unlayered CSS beats every layer whatever the
specificity. **Any rule that must override an overlay pane's own box belongs in the
unlayered block.** A pane's _size_ is written by the CDK as inline styles, which no
stylesheet beats at all; change it through `OverlayRef.updateSize` (the drawer does).

---

## 2. Tokens — country-locked ([`tokens.base.css`](../styles/tokens.base.css))

Identical in every deployment; always loaded. A country overlay does not override these.
They live here so a revision to an international standard is a one-file change.

| Token                                                                   | Role                                                                                                                                                                                                                                           |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `--niord-font-mono`                                                     | Mono font stack (code, identifiers). No external dependency.                                                                                                                                                                                   |
| `--niord-pin-fill` `-stroke` `-label` `-label-fill` `-label-stroke`     | The international maritime pin marker. A user moving between deployments must see the same marker.                                                                                                                                             |
| `--niord-msg-selected-halo` `-hover-halo` `-count-fill` `-count-stroke` | Message map marks outside the S-124 portrayal: the selection (orange) and hover (blue) halos, and the co-location count badge's numeral and the outline that lifts it off the map.                                                             |
| `--niord-msg-buffer-fill` `-buffer-stroke`                              | The geometry editor's buffer overlay (the editor's own styling, outside the portrayal).                                                                                                                                                        |
| `--niord-editor-fill`                                                   | Geometry-editor translucent fill (its vertices and outline reuse `--niord-pin-*`).                                                                                                                                                             |
| `--niord-measure-stroke` `-fill`                                        | Transient measure-tool overlay (amber path).                                                                                                                                                                                                   |
| `--niord-chart-extent-stroke` `-label-fill` `-label-stroke`             | Chart-extent overlay + label halo on the admin Charts map.                                                                                                                                                                                     |
| `--niord-notice-{success,info,warn,error}-{bg,fg}`                      | Universal alert palette, shared by the inline `.message` notice, `<app-toast>` and `.checklist-verdict`. `-bg` is the tint, `-fg` the icon and left accent.                                                                                    |
| `--niord-attention-rail`                                                | The left rail of a list row that needs a look (a passed follow-up date); the row keeps its ordinary background. Must stay distinguishable from the SELECTION rail in every brand — the accent is a teal in one brand and an orange in another. |
| `--niord-diff-{added,changed,removed}`                                  | Revision-diff palette (add green / change amber / remove red) for the history diff pane, its legend and the comparison word diff.                                                                                                              |

The map tokens are all read at runtime by OpenLayers style modules through `readCssVar`
([`shared-map/css-tokens.ts`](../shared-map/css-tokens.ts)) — canvas cannot resolve `var()`.
Never hardcode a colour in an OL style module.

The S-124 portrayal has no tokens. Its magenta pen and the affected-area overlay on a message map
are data in [`shared-map/portrayal/portrayal.json`](../shared-map/portrayal/portrayal.json), the one
definition both applications' maps and the backend's map images read, and country-locked like this
file — see [`map-portrayal.md`](./map-portrayal.md).

---

## 3. Tokens — brand ([`tokens.default.css`](../styles/tokens.default.css))

`tokens.default.css` is the base product's brand file. A country's `styles/tokens.<cc>.css`, in its
repository, declares the **same set of token names** with its own values; that parity is the
contract every Layer-3 class and the `@theme` bridge rely on. The kit's init copies
`tokens.default.css` to start a country's file.

| Token group                                                                      | Role                                                                                                                                                                                                                  |
| -------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `--niord-primary-{50..950}`                                                      | Primary semantic scale. Wired to the `--color-primary*` utilities.                                                                                                                                                    |
| `--niord-accent-{50..950}`                                                       | Accent semantic scale — selection wash, brand bar, marked-state affordances. Only the base tone is exposed as a utility.                                                                                              |
| `--niord-primary` `-hover` `-soft`, `--niord-accent` `-hover` `-soft`            | Convenience aliases for hand-written CSS.                                                                                                                                                                             |
| `--niord-action-primary-bg` `-bg-hover` `-fg`                                    | The `.btn-primary` indirection — the one hook that lets a country render affirmative actions in a different hue.                                                                                                      |
| `--niord-action-danger-bg` `-bg-hover` `-fg`                                     | The `.btn-danger` and destructive-confirm indirection. Universal red, not brand.                                                                                                                                      |
| `--niord-bg` / `--niord-surface` / `--niord-surface-sunken`                      | Surface stack: page → card → recessed area. `--niord-surface` is referenced directly by Layer-3 classes and has no utility; `--niord-bg` and `--niord-surface-sunken` are exposed as `bg-bg` and `bg-surface-sunken`. |
| `--niord-header-band`                                                            | The ONE shared header-strip fill: card header bands, the messages table header, the admin and sysadmin list-table headers.                                                                                            |
| `--niord-surface-{0..950}`                                                       | Neutral surface scale behind the `bg/text/border-surface-*` utilities.                                                                                                                                                |
| `--niord-divider` / `-border` / `-border-strong`                                 | Three border weights.                                                                                                                                                                                                 |
| `--niord-fg` / `-fg-muted` / `-fg-subtle`                                        | Body text stack, all WCAG AA on the surface. `--niord-fg` is also the document default (`body` in the `@layer base` block of `styles.css`). Do not lighten `-fg-subtle`.                                              |
| `--niord-fg-on-primary`                                                          | Text on a brand fill (active chips, accent badges).                                                                                                                                                                   |
| `--niord-fg-title`                                                               | Field and section TITLE colour — one deliberate step above body text, carried by HUE rather than by weight. Aliases `--niord-primary` in the default brand.                                                           |
| `--niord-header-bg` `-fg` `-muted` `-active`                                     | App-shell header chrome; `-active` is the brand bar and aliases the accent.                                                                                                                                           |
| `--niord-status-{published,draft,verified,cancelled,expired,deleted}-{bg,fg,bd}` | Six statuses × three colours. Independent variables even where a brand repeats a value, so any one can be retuned alone.                                                                                              |
| `--niord-area-*` / `--niord-chart-*`                                             | Area and chart map-overlay colours. Themeable (unlike the locked marker language) and read via `readCssVar`.                                                                                                          |
| `--niord-radius-sm` / `--niord-radius` / `-lg`                                   | Three corner radii.                                                                                                                                                                                                   |
| `--niord-shadow-sm` / `--niord-shadow` / `-lg`                                   | Three shadow weights, tinted to the brand primary.                                                                                                                                                                    |
| `--niord-font-family` / `--niord-font-size-adjust`                               | Body font stack plus the x-height parity hook a non-system font needs.                                                                                                                                                |

### The build-time swap

[`tokens.active.css`](../styles/tokens.active.css) contains nothing but an `@import` of
`tokens.default.css`. The brand file is chosen by the **`styles[]` array** in `angular.json`, in
both applications: the base product's configurations list `tokens.active.css`, and a country's
generated configurations list its own `styles/tokens.<cc>.css` after the generated Tailwind entry
([country-kit/README.md](../../country-kit/README.md) → _The seams_). It is done this way because
Angular's `fileReplacements` accepts only JS/TS/JSON and rejects CSS; `fileReplacements` carries
the country's TypeScript files instead. Cascade order survives the swap because custom properties
are unlayered and resolve at use time. Do not put brand values in the stub file. Where a
difference lives: [`country-deployments.md`](./country-deployments.md).

---

## 4. The Tailwind `@theme` bridge ([`theme.css`](../styles/theme.css))

The `@theme` block re-exports the tokens as first-class utilities. **Naming rule:** a role
name is used directly where it does not collide with a Tailwind default; a `niord-` infix
is used where it would override one, so the Tailwind default stays available
(`rounded-niord-md` beside `rounded-md`). Use these utilities rather than arbitrary-value
brackets like `text-[var(--niord-fg-muted)]`.

| Theme variable                                   | Wired to                                    | Yields                                                                      |
| ------------------------------------------------ | ------------------------------------------- | --------------------------------------------------------------------------- |
| `--color-fg{,-muted,-subtle,-on-primary,-title}` | `--niord-fg*`                               | `text-fg`, `text-fg-muted`, `text-fg-title`, …                              |
| `--color-bg`                                     | `--niord-bg`                                | `bg-bg` (the page ground)                                                   |
| `--color-surface-{0..950}`                       | `--niord-surface-*`                         | `bg/text/border-surface-*`                                                  |
| `--color-surface-sunken`                         | `--niord-surface-sunken`                    | `bg-surface-sunken`                                                         |
| `--color-header-band`                            | `--niord-header-band`                       | `bg-header-band` (the table rules read the token directly)                  |
| `--color-primary` + `-{50..950}`                 | `--niord-primary*`                          | `bg/text/border-primary*`                                                   |
| `--color-accent`                                 | `--niord-accent`                            | `text/bg/border-accent` (the scale is not exposed)                          |
| `--color-danger` / `--color-warning`             | literal red-600 / yellow-700                | `text-danger`, `text-warning`                                               |
| `--color-chrome{,-fg,-muted,-active}`            | `--niord-header-*`                          | `bg-chrome`, `text-chrome-fg`, …                                            |
| `--radius-niord-{sm,md,lg}`                      | `--niord-radius*`                           | `rounded-niord-*`                                                           |
| `--shadow-niord-{sm,md,lg}`                      | `--niord-shadow*`                           | `shadow-niord-*`                                                            |
| `--font-sans` / `--font-mono`                    | `--niord-font-family` / `--niord-font-mono` | overrides the Tailwind defaults, so preflight's body font follows the brand |

The bridge is shared: the block lives in `fe/styles/theme.css` and both entry stylesheets
import it, so a utility name means the same thing in both applications.

A value only one application draws with is declared in that application's entry stylesheet
instead, in an `@theme` block of its own, because nothing in `fe/styles/` may name an
application. The public site has two: `--color-map-sea` (`bg-map-sea`, the ground behind the map
tiles, wired to the site's own `--niord-map-sea`) and `--text-message-title`
(`text-message-title`, the 1.2em step a message's title is set in).

The theme file also binds `@custom-variant dark` to an `.app-dark` class on `<html>` (no dark
tokens are authored — the selector is pre-picked so adding them stays a tokens-file change). It
is bound there rather than in an entry stylesheet because the map kit's components carry `dark:`
utilities that both applications compile; `npm run test:guards` fails an entry that binds its
own.

`styles.css` also owns: the `tailwindcss`, `ol/ol.css` and CDK overlay imports; the token,
theme and component-class imports; `html { font-size-adjust }` plus `overflow-y: scroll` (an always-shown
scrollbar, which stops centred columns shifting when a section gains one; not
`scrollbar-gutter: stable`, whose empty gutter puts every right-aligned CDK overlay 15 px off); the cascade-order
declaration; an `@layer base` block restoring `cursor: pointer` on enabled buttons and
anchoring `body` at `--niord-fg`; and `router-outlet { display: contents }`.

---

## 5. Layer-3 classes ([`components-core.css`](../styles/components-core.css), [`components-admin.css`](../ui-apps/admin/src/styles/components-admin.css))

The classes live in two files and the entry stylesheet loads the shared one first. A class is
in `components-core.css` when both applications render it and in `components-admin.css` when
only this one does; grep the class name to see which file owns it. No selector is declared in
both, so which file a class sits in changes nothing about how it cascades — and a class that
gains a second consumer moves file rather than being copied.

### 5.1 The status protocol

Status colour is carried by custom properties, not by the widget. A **state class** sets
`--status-bg` / `--status-fg` / `--status-bd`; a **widget class** reads them. The two are
orthogonal, so any widget renders in any status for free, and a widget with no state class
falls back to a neutral muted treatment through the `var(…, fallback)` chain.

Message statuses are applied through
[`StatusClassPipe`](../ui-apps/admin/src/app/shared/pipes/status-class.pipe.ts):

```html
<span class="status-badge" [class]="msg.status | statusClass">…</span>
```

| State class family                                                                     | Set by                                                                                                                                                                                                                                    |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.is-published` `.is-draft` `.is-verified` `.is-cancelled` `.is-expired` `.is-deleted` | `StatusClassPipe`                                                                                                                                                                                                                         |
| `.is-mail-pending` `.is-mail-sent` `.is-mail-error`                                    | by hand, mailing-list queue                                                                                                                                                                                                               |
| `.is-batch-running` `-completed` `-stopped` `-failed` `-default`                       | by hand, batch jobs                                                                                                                                                                                                                       |
| `.is-script-fm` `.is-script-js`                                                        | by hand, script resources                                                                                                                                                                                                                 |
| `.is-secom-pending` `-sent` `-acked` `-opened` `-failed` `-cancelled`                  | `outboxStatusClass()` in `features/admin/sections/sysadmin/secom/secom-labels.ts`                                                                                                                                                         |
| `.is-secom-dataset-active` `.is-secom-dataset-cancelled`                               | `datasetStatusClass()` in the same file                                                                                                                                                                                                   |
| `.is-issue-published` `.is-issue-open`, `.is-gap-MISSING` `.is-gap-UPCOMING`           | by hand, and by the badge helpers in `features/admin/sections/admin/publications/publication-status.ts` (`issueBadgeClass()`, `seriesBadgeClass()`, `computedIssueStatusClass()`) and `publication-visibility.ts` (`oneOffStatusClass()`) |
| `.is-tailored`                                                                         | by hand, on a field one edition states differently from its series                                                                                                                                                                        |

The mail, batch, script, SECOM and issue families reuse the message-status palette or mix
off `--color-warning` / `--color-danger` / `--niord-accent`, so they are never a second
palette to keep in step. The two SECOM helpers return `''` for a wire value this build does
not know, which leaves the badge on its neutral default — the intended "undefined status"
treatment, not a gap.

Two details that carry meaning, not decoration:

- `.is-issue-open` / `-published` are blue and green from the message-status palette, never
  the accent — the accent is what the application means by "selected" everywhere else. On a
  message they say what its membership is **based on**: `published` for a membership frozen
  at publication, `open` for one resolved live and still able to change, and the two must
  not share a tint because a pending membership can still change. On a publication screen
  they carry the issue's own lifecycle (and a draft series reuses the "open for work" tint).
  A retired issue maps to `.is-cancelled`, because a withdrawn document is what that tint
  means everywhere else, and an unrecognised value returns `''` rather than a colour that
  claims something. `StatusClassPipe` must never be pointed at this vocabulary or at
  `.is-gap-*`.
- `.is-gap-MISSING` mixes off `--color-warning` (something is wrong and can be acted on)
  while `.is-gap-UPCOMING` reuses the neutral cancelled palette (a period still open is not
  a problem and must not read as one).

**Every `color-mix` that touches a status tint interpolates `in srgb`, never `in oklch`.**
Chrome treats a very pale colour (OKLCH chroma below ~0.02 — which every status tint is) as
achromatic inside `color-mix` and drops its hue; when the other side carries no hue either
(`transparent`, `white`, `black`) nothing supplies one and the leftover chroma renders as
PINK. Measured: `color-mix(in oklch, #e8f0f8, transparent 35%)` computes to
`oklch(0.951 0.0137 none / 0.65)` and paints `rgb(247 235 238)` — a pink from a blue.
`in srgb` has no separate hue channel to lose. `in oklch` is safe only between two
saturated colours, which is why the `.row-selected` hover mix may use it.

### 5.2 Status widgets

| Class                                     | Shape                                                                                                                                                                                                                                         |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.status-badge`                           | Dot-led soft-tinted pill. The leading dot is the mark of a lifecycle state.                                                                                                                                                                   |
| `.status-badge.mono-id`                   | Filled, squared message-id variant — solid status tint, no leading dot.                                                                                                                                                                       |
| `.status-badge.is-plain`                  | Same pill without the dot, for a badge that labels a fact rather than a lifecycle state.                                                                                                                                                      |
| `.status-chip`                            | Outlined pill — border and text in the status hue over a transparent ground.                                                                                                                                                                  |
| `.status-chip.is-active`                  | Fills with `--status-bg`. Toggles use it for "on"; display chips apply it for the filled look.                                                                                                                                                |
| `.status-chip.is-deleted:not(.is-active)` | The one inverted status: falls back to `--niord-fg` so an outlined chip is not white-on-white.                                                                                                                                                |
| `button.status-chip`                      | Pointer cursor; hover and focus lighten, and darken when active.                                                                                                                                                                              |
| `.status-dot`                             | 8px dot in `--status-bd`.                                                                                                                                                                                                                     |
| `.status-dot.is-active` / `.is-inactive`  | The generic boolean flag — accent / muted, no status machinery. A dot standing alone for a state needs `role="img"` and a name.                                                                                                               |
| `.status-row`                             | Whole-row soft status tint.                                                                                                                                                                                                                   |
| `.expected-date`                          | A date not yet stamped. Reads none of `--status-*`: three carriers, none of them colour — clock glyph, italics, dotted rule. Never apply it by hand; `<app-expected-date>` owns the markup, the hover title and the one date-formatting path. |
| `.badge-accent`                           | Small accent count pill (the Selection-tab count).                                                                                                                                                                                            |

### 5.3 Rows, tables and cards

| Class                                     | Shape                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.row-interactive`                        | Cursor, hover tint and a keyboard-only focus ring. Must be `:focus-visible` — rows regain focus programmatically when an overlay closes.                                                                                                                                                                                                                                                                                              |
| `.row-selected`                           | Accent-soft wash plus an inset left accent rail, text unchanged.                                                                                                                                                                                                                                                                                                                                                                      |
| `.row-attention:not(.row-selected)`       | A red left rail only; the row keeps its ordinary background. Selection outranks attention outright; the two are told apart by HUE, not shape.                                                                                                                                                                                                                                                                                         |
| `.row-link`                               | The row's own anchor (the `appRowLinkAnchor` cell): inherits the row's colour, underlines on hover, same focus ring. It exists so the row can be opened in a new tab, not to be spotted as a link.                                                                                                                                                                                                                                    |
| `.message-table`                          | Card frame for the messages list, details and selected views — border, radius, shadow, `overflow: clip`, sticky header band in `--niord-header-band`, hairline row dividers. `.clickable` marks a clickable header or cell.                                                                                                                                                                                                           |
| `.details-row` / `.details-thumb`         | The 3-column details layout (220 thumb / 1fr / 40 actions) and its 220×170 map preview. Stacks to one column below 768px. `.row-selected .details-row` draws the accent frame that is the selection cue in that layout.                                                                                                                                                                                                               |
| `.area-dot`                               | 6px accent dot before an area group heading.                                                                                                                                                                                                                                                                                                                                                                                          |
| `.card-interactive`                       | Base shadow plus a 1px hover lift — the card sibling of `.row-interactive`.                                                                                                                                                                                                                                                                                                                                                           |
| `.card-overlay-top` / `-bottom`           | Frosted metadata strips over a card image.                                                                                                                                                                                                                                                                                                                                                                                            |
| `.niord-table`                            | Plain semantic table chrome for the admin and sysadmin list sections: hairline header in `--niord-header-band`, hover rows, tabular numerals. Paired with `<app-pager>`; there is no CDK table.                                                                                                                                                                                                                                       |
| `.pager` / `.pager-btn` / `.pager-status` | The prev/next pager under a `.niord-table`: centred row, 32px square buttons with hover and disabled states, tabular-numeral status text. `<app-pager>` owns the markup — do not hand-roll the row.                                                                                                                                                                                                                                   |
| `.th-sort`                                | Sortable-header button inside the `<th>`. The `<th>` carries `[attr.aria-sort]`; the arrow glyph is decorative and marks only the ACTIVE column, so the button has its own focus ring.                                                                                                                                                                                                                                                |
| `.num`                                    | Right-aligned numeric cell or header.                                                                                                                                                                                                                                                                                                                                                                                                 |
| `.issue-row-pseudo`                       | A row standing for a period that produced no document: sunken, italic, muted, so a reader can see which lines describe documents that exist.                                                                                                                                                                                                                                                                                          |
| `.pub-cell`                               | One period of a publication's recent-periods strip. FOUR shapes rather than four colours — filled for the period being worked on, outlined where a document came out, dashed where none did, quiet outline for a period still to come — so the strip reads without hue. Its tints come from the `.is-issue-*` / `.is-gap-*` class the caller already puts on it, which is what keeps a cell and the badge behind it from disagreeing. |

### 5.4 Buttons, links and chips

| Class                       | Use for                                                                                            |
| --------------------------- | -------------------------------------------------------------------------------------------------- |
| `.btn-primary`              | Affirmative action. Follows the country action-primary alias.                                      |
| `.btn-danger`               | Destructive affirmative (delete, irreversible).                                                    |
| `.btn-secondary`            | Neutral bordered action.                                                                           |
| `.btn-tertiary`             | Quiet text-only action — also the Close / Cancel tier.                                             |
| `.btn-*:disabled`           | Dimmed, default cursor, hover neutralised.                                                         |
| `.icon-button`              | Borderless square icon action, transparent until hover. Its glyph is scaled to fill the button.    |
| `.link-button`              | A `<button>`, or an `<a>` to a file, that reads as a text link: primary, accent hover, underlined. |
| `.link-button.is-subtle`    | No underline, regular weight, still branded.                                                       |
| `.link-button.is-quiet`     | Muted instead of branded (`fg-muted` → `fg` on hover).                                             |
| `.link-button.is-prose`     | Marker only — a link inside running text; opts out of the below-`lg` hit box (§5.9).               |
| `.reference-link`           | Inline link to a related message: dashed underline that solidifies on hover.                       |
| `.attach-link`              | Attachment pill, surface-sunken at rest and accent on hover.                                       |
| `.chip` / `.chip.is-active` | Generic filter toggle; active fills with primary (`Highlight` in a forced-colours theme, §5.9).    |
| `.nav-item-states`          | Hover state for header nav items, dropdown triggers and the burger.                                |
| `.toolbar-tab-active`       | Inset accent underline (`::after`) for the active desktop tab link.                                |
| `.app-shell-gutter`         | The single horizontal inset (`px-4 md:px-6`) every full-width shell surface shares.                |

`.is-subtle` vs `.is-quiet`: both drop the underline and the medium weight; only
`.is-quiet` drops the brand COLOUR. Use `.is-subtle` where the link IS the point (a name, a
count, an "Add"); use `.is-quiet` where it sits beside the content it acts on and must not
outrank it.

The four `.btn-*` variants each inline their base shape rather than sharing a `.btn-base` —
`@apply` of a Layer-3 class does not compile; the authoring rule is in
[`ui-standards.md`](./ui-standards.md) → _Layer-3 authoring rules_.

### 5.5 Form controls

| Class                      | Use for                                                                                                                                                                                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.text-input`              | Native `<input>` / `<textarea>` (a textarea variant relaxes the fixed height). Its border is `--niord-surface-500`, which holds 3:1 on the page and on a surface; the administration app's `.ac-control`, `.sel-control` and `.tree-filter-input` share it. |
| `.text-input:user-invalid` | Danger border once the USER has produced an invalid value. `:user-invalid`, not `:invalid`, so a seeded or empty drawer opens clean.                                                                                                                        |
| `.checkbox` / `.radio`     | Native checkbox and radio. They carry `flex: none` at every width so a wrapping label can never squeeze the box off square.                                                                                                                                 |
| `.check-row`               | A checkbox/radio + label row. Reach for it instead of re-rolling `flex items-center gap-2 text-sm`; a row needing top alignment adds `items-start` on top.                                                                                                  |
| `.seg` / `.seg-btn`        | Segmented toggle row; the active segment fills with primary (`Highlight` in a forced-colours theme, §5.9).                                                                                                                                                  |
| `.seg.is-soft`             | Modifier — the active segment becomes a tint instead of a solid block. For a control standing INSIDE a form, where a saturated fill outweighs the field titles around it.                                                                                   |
| `.seg.is-stack-2`          | Modifier — below `sm` the row becomes a 2×2 grid, for four segments on a phone.                                                                                                                                                                             |
| `.file-drop-zone`          | Dotted file drop target (`.is-dragover`, plus a `:focus-within` ring because the real input is visually hidden).                                                                                                                                            |
| `.ed-field-group`          | Set on an editor field-group body: its last child drops the row divider so the group closes flush. It passes `--ed-row-border` down rather than overriding the border, because the row's own scoped stylesheet is unlayered and beats any layered rule.     |

Native elements bind directly with Signal Forms' `[formField]`, so they need no wrapper
component. See [`ui-standards.md`](./ui-standards.md) for the shared control inventory.

### 5.6 Overlay surfaces

All of these portal into `.cdk-overlay-container`, so they cannot be component-scoped. The
CDK pane is transparent in every case — the Layer-3 class owns the card chrome.

| Family         | Classes                                                                                                                                                                                                                                |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dialog         | `.dialog-backdrop` `.dialog-panel` `.dialog-header` `.dialog-title` `.dialog-body` `.dialog-foot`                                                                                                                                      |
| Confirm        | `.confirm-backdrop` `.confirm-panel` `.confirm-head` `.confirm-title` `.confirm-body` `.confirm-details` `.confirm-foot`                                                                                                               |
| Menu           | `.menu-panel` `.menu-sep` `.menu-item` (+ `.is-disabled`, `.menu-group-label`, `.is-destructive`)                                                                                                                                      |
| Drawer         | `.niord-drawer-backdrop` `.niord-drawer-panel` (+ `.is-open`)                                                                                                                                                                          |
| Picker popover | `.picker-panel` `.picker-foot`                                                                                                                                                                                                         |
| Autocomplete   | `.ac-control` `.ac-disabled` `.ac-input` `.ac-chip` `.ac-chip-remove` `.ac-clear` `.ac-panel` `.ac-option` `.ac-option-active` `.ac-empty` `.ac-status` `.ac-overlay-content`                                                          |
| Select         | `.sel-control` `.sel-disabled` `.sel-trigger` `.sel-value` `.sel-placeholder` `.sel-chevron` `.sel-clear` `.sel-panel` `.sel-option` (+ `-active`, `-selected`)                                                                        |
| Tree           | `.tree-filter` `.tree-filter-icon` `.tree-filter-input` `.tree-scroll` `.tree-root` `.tree-row` `.tree-row-toggle` `.tree-row-check` `.tree-row-main` `.tree-row-folder` `.tree-row-label` `.tree-children` `.tree-children-collapsed` |

Notes that are not visible from the class names:

- `.menu-panel` is viewport-bounded, so a tall menu scrolls in place.
  `.menu-item.menu-group-label` is a heading over a separated group, set on a _disabled_
  item so the roving tabindex skips it; `.menu-item.is-destructive` is reached only through
  a separated group, so its colour is the second signal rather than the only one.
- `.niord-drawer-panel` owns the surface chrome, the off-screen `translateX(100%)` and the
  transition; `.is-open` is applied at attach and holds the resting position, so removing it
  at close drives the slide-OUT. The slide-IN is a Web Animations call run before first paint
  in the drawer component — a CSS-only entrance is unreliable through a CDK overlay, which
  applies `panelClass` after DOM insertion. Its width is NOT set here: the pane is capped
  from `md` up so a tappable scrim strip always remains beside it, and that cap goes through
  `OverlayRef.updateSize` in the drawer component.
- The tree classes are shared by `<app-entity-tree>` (single-select browse) and
  `<app-tree-picker>` (checkbox multi-select) so the two can never drift. Hover and focus
  reuse `.row-interactive`; the already-picked tint reuses `.row-selected`.
- Select and autocomplete chrome mirror each other deliberately, so both read as one
  form-field family.
- `.ac-overlay-content` is added when `[panelWidth]="'content'"`, which makes the control's
  width a FLOOR rather than a fixed width; its cap is the unlayered rule in §5.10.

### 5.7 Section chrome

| Class                                                                             | Shape                                                                                                                                        |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `.admin-toolbar`                                                                  | Single-row section toolbar: filters left, actions right.                                                                                     |
| `.admin-toolbar-stack` / `.admin-toolbar-row`                                     | A vertical stack of toolbar rows, for a section that needs more than one line of filters.                                                    |
| `.admin-toolbar-spacer`                                                           | Pushes the following toolbar items right.                                                                                                    |
| `.admin-panel` / `-header` / `-body`                                              | A titled content panel: sunken header band over a bordered body. The frame for a detail screen's read-and-act sections.                      |
| `.admin-card` (+ `.is-unpinned`)                                                  | The shell around a section's TABLE — shadow, horizontal scroll, and the below-`xl` last-column pin.                                          |
| `.batch-type-card` (+ `.is-selected`, `.batch-type-count`, `.batch-type-running`) | Selectable batch-type tiles in the Batch Jobs master list.                                                                                   |
| `.batch-instance-row > td`                                                        | The sunken band that makes one instance group read as one.                                                                                   |
| `.text-viewer`                                                                    | Monospace read-only viewer body for a fetched text payload: a batch log file, a dataset's stored S-124 GML.                                  |
| `.cm-host .cm-editor`                                                             | Minimum height for the code editor. Global because CodeMirror creates the element at runtime, outside the component's encapsulation.         |
| `.message-diff ins` / `del`, `.diff-swatch-add` / `-del`                          | The comparison word diff and its legend swatches, tinted off `--niord-diff-*`. Global because the diffed HTML arrives through `[innerHTML]`. |

**`.admin-card` and `.admin-panel` are not interchangeable.** The card is for a table; the
panel is for a read-and-act section. A bare `<h4>` inside a padded box is not a panel
header, and it is the one thing the `--niord-header-band` retint can never reach.

Two things about `.admin-card` look incidental and are not:

- Its `position: relative` is load-bearing. Tailwind's `.sr-only` is `position: absolute`,
  and the visually-hidden column labels inside the table's `<th>`s must resolve their
  containing block to the scroll box that clips them. Without it they resolve against the
  initial containing block, escape the clip and stretch `documentElement.scrollWidth` into
  a page-level horizontal scrollbar.
- The below-`xl` pin assumes the trailing column is row ACTIONS. It is a no-op for a table
  that fits, so it costs nothing on narrow sections — but a card whose last column is
  content (a reason column, a Status column) takes `.is-unpinned`, and so does a single
  table inside a card holding several where only some of them end in actions. The pinned
  cell inherits the row's paint, so a hover, selection or status wash runs under it
  unbroken.

Panel bands are sentence-case, `text-sm`, `text-fg`, on a band fill — so a card standing
beside a panel reads as the same kind of surface. There is one band style per surface kind:
`.admin-panel-header` carries `font-medium` on `--niord-surface-sunken`, and the
`<app-card>` label `font-semibold` on `--niord-header-band`. Reuse `.admin-panel-header` or
`<app-card>`; never hand-roll a third.

### 5.8 Feedback surfaces

| Class                                                                                                                                         | Shape                                                                                                                                                                                                                                                             |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.message`, `.message-icon`, `.message-{success,info,warn,error}`                                                                             | Severity-tinted inline notice block. `--message-accent` drives the leading icon and the left border; the tint is the severity `-bg` token.                                                                                                                        |
| `.checklist-verdict`, `.checklist-verdict.is-{ok,warn,blocked}`                                                                               | A release rail's one-line answer. Same severity palette, but no tint, border or rail: a boxed banner reads as something that _happened_, and a verdict is a standing state. Weight and colour carry it; the caller nests the icon, which inherits `currentColor`. |
| `.toast-stack`, `.toast`, `.toast-icon`, `.toast-body`, `.toast-summary`, `.toast-detail`, `.toast-close`, `.toast-{success,info,warn,error}` | Top-right overlay stack. The wrapper is click-through, each card interactive and left-accented.                                                                                                                                                                   |
| `.progress`, `.progress-fill`, `.progress.is-indeterminate`                                                                                   | A fixed 3px track with an accent fill; the indeterminate variant sweeps a 40% segment (keyframes `niord-progress-indeterminate`). The track height is fixed on purpose — a percentage height filled the whole of any definite-height flex parent.                 |
| `.tabs`, `.tabs-list`, `.tabs-tab`, `.tabs-panel[inert]`                                                                                      | The skin over the `@angular/aria` tabs pattern; roles and roving focus come from the directives. The active tab reuses the toolbar underline; the focus ring is the primary. Panels are mounted eagerly and the inactive one is hidden by its `inert` attribute.  |

Below `lg` a tab strip wider than its column scrolls **inside itself** rather than widening
the surface around it, so a tabbed drawer or panel never becomes a sideways scroller. The
scrollbar is hidden (the tabs stay reachable by roving focus) and the active underline
moves onto the border line, because a scroll container clips at its padding edge. A
consumer must not add its own overflow or width utilities to `<app-tabs>`.

### 5.9 Responsive rules that live in the stylesheet

| Breakpoint         | What flips                                                                |
| ------------------ | ------------------------------------------------------------------------- |
| `width < 40rem`    | `.seg.is-stack-2` becomes a 2×2 grid.                                     |
| `max-width: 767px` | `.details-row` stacks to one column and `.details-thumb` spans the width. |
| `width < 64rem`    | The `<app-tabs>` strip pans inside itself; the touch floors below.        |
| `width < 80rem`    | `.admin-card` pins the table's last column.                               |

**Touch floors.** One `@media (width < 64rem)` block at the end of each of the two class
files, the shared controls in `components-core.css` and the rest in `components-admin.css` —
the same boundary Tailwind compiles `max-lg:` to, so the control floors flip in step with the
header's own collapse. `.btn-*` and `.pager-btn` reach 40px; `.checkbox` / `.radio` reach
20px; `.check-row`, `.seg-btn`, `button.chip` and `.ac-option` take a 40px minimum height;
`.pub-cell` grows to 36px. A chip that is a `<span>` — a value shown inside an autocomplete —
is not a control and keeps its size.

Glyph-sized controls — `.icon-button`, `.ac-clear`, `.ac-chip-remove` and `.link-button` —
keep their FOOTPRINT and get the target from a centred `::after` box
(`max(100%, 2.5rem)` × 2.5rem) instead, because growing them for real would loosen dense
table rows and card bands and push the autocomplete control past the `.text-input` beside
it. Adjacent icon-buttons take 16px of separation so the later hit box cannot reach over
the earlier glyph. `.link-button` opts out with `.is-prose` where the link sits
mid-sentence.

A template utility (`h-7`, `h-8`, `text-xs`) still wins over all of this, which is the
escape hatch for a control that must stay small. Do not add `min-h-*` / `min-w-*`
utilities to these classes at the call site — the floor belongs here, once.

**Forced colours.** One `@media (forced-colors: active)` block at the end of
`components-core.css`. A high-contrast theme repaints every colour from the reader's palette, so
a toggle whose only pressed mark is its fill loses it; a pressed `button.chip` and an active
`.seg-btn` take `Highlight` / `HighlightText` instead, the pair the theme reserves for a chosen
item. A new toggle whose state is carried by colour alone belongs in that block.

### 5.10 Unlayered overrides

One block in `components-admin.css` — grep `UNLAYERED OVERRIDES`; it sits between the tree/overlay
classes and the admin-section chrome, not at the end of the file. Outside every `@layer`, so
these rules beat all layers — including a utility, and including CSS a library injects at
runtime.

| Rule                                                                                          | Why it has to be unlayered                                                                                                                                                                                                                   |
| --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `.selection-star app-icon` and `.selection-star-overlay.is-selected .selection-star app-icon` | An `<app-icon>` is a `1em` glyph, and the star's overlay wrapper carries `text-3xl`. The base size must beat that utility; the selected message's star is then enlarged.                                                                     |
| `.cdk-overlay-pane.ac-overlay-content`                                                        | Caps a content-sized autocomplete panel. The CDK's own runtime copy of the `.cdk-overlay-pane` rules is unlayered, so a `max-width` written in `@layer components` is inert and the panel would be capped only by the flexible bounding box. |

---

## 6. Global scoped rules ([`styles.css`](../ui-apps/admin/src/styles.css))

| Selector                | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `.message-details-html` | The deliberate carve-out for backend-authored `[innerHTML]` content, in its own shared file, [`styles/message-html.css`](../styles/message-html.css) — the public site renders the same message HTML. Restores table borders and padding, list markers and spacing, and draws every `<a>` as a link at rest — the primary and an underline — except under `.message-publication`, the modifier a publication field's wrapper carries beside it, where a link keeps the colour of the text around it and underlines on hover; and handles the classes that content carries (`.no-border`, `.condensed`, `.positions`, `.position-table`, `tr.underline`, `.no-wrap`). This is the only place element-level styling of injected markup is allowed. |
| `app-ol-map .ol-zoom`   | Repositions the OpenLayers zoom control to bottom-right, clear of the result-list overlay.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

---

## 7. How do I…

| Task                                                    | Use                                                                                                                                                                                                        |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Add a clickable table row                               | `class="row-interactive"` + `row-selected` when selected                                                                                                                                                   |
| Make a clickable row openable in a new tab              | `[appRowLink]` on the row + `<a appRowLinkAnchor class="row-link">` on its name cell                                                                                                                       |
| Render a status as a label                              | `<span class="status-badge" [class]="msg.status \| statusClass">`                                                                                                                                          |
| Render a status as a filter chip                        | `<button class="status-chip" [class]="status \| statusClass">` + `is-active` when on                                                                                                                       |
| Render a non-status filter toggle                       | `<button class="chip">` + `is-active` when on                                                                                                                                                              |
| Render the affirmative action button                    | `<button class="btn-primary">` (or `.btn-danger` when destructive)                                                                                                                                         |
| Render a native field / checkbox / segmented toggle     | `.text-input` / `.checkbox` + `.check-row` / `.seg` + `.seg-btn`                                                                                                                                           |
| Soften a segmented toggle that sits inside a form       | add `is-soft` to the `.seg` row                                                                                                                                                                            |
| Render a link that must not outrank what it sits beside | `class="link-button is-quiet"`                                                                                                                                                                             |
| Style a popup menu item row                             | `class="menu-item"` + `[class.is-disabled]="item.disabled"` inside a `.menu-panel`                                                                                                                         |
| Build an anchored picker                                | project content into `<app-picker-popover>`; box = `.picker-panel`, footer = `.picker-foot`                                                                                                                |
| Show an inline severity notice / a toast                | `.message` + `.message-<severity>` / `<app-toast>`                                                                                                                                                         |
| Frame a section's table vs a detail section             | `.admin-card` vs `.admin-panel` + `.admin-panel-header` + `.admin-panel-body`                                                                                                                              |
| Keep a table's last column reachable when it is content | add `.is-unpinned` to the `.admin-card` (or to that one `<table>`)                                                                                                                                         |
| Tint a publication issue or gap row                     | `computedIssueStatusClass(row.computedStatus)`; period-less rows also take `.issue-row-pseudo`                                                                                                             |
| Render backend HTML with correct tables and links       | wrap it in `<span class="message-details-html" [innerHTML]="…">`; a publication field adds `message-publication` beside it                                                                                 |
| Override a Tailwind utility (rare)                      | add the rule to the unlayered block in `components-admin.css` (§5.10)                                                                                                                                      |
| Make a value country-tunable                            | add a `--niord-…` token to **both** brand files and reference it with `var(…)`                                                                                                                             |
| Give an OpenLayers / canvas layer a themeable colour    | add a `--niord-…` token and read it via `readCssVar` — never a literal in an OL style                                                                                                                      |
| Apply a conditional colour                              | bind `[style.border-color]` to a colour token. A conditionally-added Tailwind colour utility loses to a theme colour of equal specificity, and a utility that appears nowhere in source is never generated |

---

## 8. Keeping this file true

- Add a row when you add a token or a class; remove the row when you remove one. Names are
  the anchor — everything here should be findable with a single `grep`.
- Don't paraphrase the inline comment in the source; the source explains _why_, this file
  says _where_ and _what for_.
- Don't grow it into prose. Rules about which control to reach for belong in
  [`ui-standards.md`](./ui-standards.md); the country-overlay procedure belongs in
  [`country-deployments.md`](./country-deployments.md).
