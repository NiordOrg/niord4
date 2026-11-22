# TypeScript conventions

What is specific to this repository. Angular APIs, signals and templates are in
[angular-conventions.md](./angular-conventions.md); layering and the import boundaries are in
[architecture.md](./architecture.md); generic language and framework API questions belong to the
vendored [`angular-developer` skill](../.claude/skills/angular-developer/).

TypeScript is `~6.0`. Formatting is not a convention — Prettier owns it (`printWidth` 100, single
quotes) and runs on staged files.

## Compiler configuration

Every flag, and the two `paths` aliases, is set once in the root `tsconfig.json`. The three project
configs of the administration app extend it and add only `outDir`, `include`, and either `exclude`
(the app) or `types` (spec and e2e); the public site's `tsconfig.app.json` and `tsconfig.spec.json`
have the same shape:

| Config                             | Covers                                                                           | Notes                                                    |
| ---------------------------------- | -------------------------------------------------------------------------------- | -------------------------------------------------------- |
| `tsconfig.json`                    | nothing                                                                          | `files: []` plus project references — see the trap below |
| `ui-apps/admin/tsconfig.app.json`  | `ui-apps/admin/src/**/*.ts`, `shared-map/` and `shared-html/`, minus specs       | what `ng build niord-app` typechecks                     |
| `ui-apps/admin/tsconfig.spec.json` | specs (the two shared folders' too), `*.d.ts`, `ui-apps/admin/src/test-setup.ts` | adds `types: ["vitest/globals"]`                         |
| `ui-apps/admin/e2e/tsconfig.json`  | `ui-apps/admin/e2e/**/*.ts`                                                      | `types: []`; exists so the IDE resolves the suite        |

**Trap:** `npx tsc -p tsconfig.json --noEmit` is _not_ the typecheck. The root config compiles no
files, so it passes on code the build rejects. The honest gates are `npm run build` and `npm test`
— see [testing.md](./testing.md).

### What is on

| Flag                                                          | What it costs you                                                                             |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `strict`                                                      | `strictNullChecks`, `noImplicitAny`, and `useUnknownInCatchVariables` (see [Errors](#errors)) |
| `noImplicitReturns`                                           | every path returns — this is what makes a `default`-less switch exhaustive                    |
| `noFallthroughCasesInSwitch`                                  | a non-empty `case` must `break` or `return`                                                   |
| `noImplicitOverride`                                          | a method that overrides a base member needs `override`                                        |
| `noPropertyAccessFromIndexSignature`                          | see below — the single most common surprise                                                   |
| `isolatedModules`                                             | each file must transpile alone                                                                |
| `importHelpers`                                               | emit helpers come from `tslib`, not from every file                                           |
| `experimentalDecorators`, `skipLibCheck`, `resolveJsonModule` | toolchain defaults, unchanged                                                                 |
| `target` / `lib`                                              | `ES2022`, with `DOM` and `DOM.Iterable`                                                       |
| `module: "preserve"`                                          | import syntax is emitted as written; the bundler resolves it                                  |

Angular compiler options: `strictTemplates`, `strictInjectionParameters` and
`strictInputAccessModifiers` are all on.

`noPropertyAccessFromIndexSignature` means anything reached through an index signature must use
bracket notation. Query-parameter bags are the usual encounter:

```ts
const params: Record<string, string> = {};
if (status) {
  params['status'] = status; // params.status does not compile
}
```

### What is deliberately off

| Flag                         | Why it stays off                                                      |
| ---------------------------- | --------------------------------------------------------------------- |
| `noUncheckedIndexedAccess`   | every array and record read would become `T \| undefined`             |
| `exactOptionalPropertyTypes` | the wire types are `?`-heavy; `undefined` is assigned freely          |
| `verbatimModuleSyntax`       | nothing forces `import type`; the compiler elides type imports itself |

Turning any of these on is a decision, not a cleanup: each touches a large amount of existing code.

## What lint enforces, and what is only convention

`npm run lint` runs `eslint` over `ui-apps/admin/src/**/*.ts` and `ui-apps/admin/src/**/*.html`; `lint-staged` also runs it
with `--fix` on staged `.ts`/`.html` anywhere in the repo. The rule sets are
`eslint:recommended`, `typescript-eslint` **recommended** and **stylistic**, and
`angular-eslint`, plus the project rules below.

Enforced — these fail the build. Most come from the shared presets; only `no-deprecated`,
`no-restricted-syntax`, the two `@angular-eslint` selector rules and the boundary blocks are
written out in `eslint.config.js`, so a row you cannot find there is a preset rule, not a stale
claim:

| Rule                                                                       | Effect                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@typescript-eslint/no-explicit-any`                                       | `any` is an error. There is none in `ui-apps/admin/src/`, and no rule disable for it                                                                                                                                                                                                                                                                                                                                                                                                       |
| `@typescript-eslint/ban-ts-comment`                                        | `@ts-ignore` is an error, `@ts-expect-error` needs a description. Neither appears in `ui-apps/admin/src/`                                                                                                                                                                                                                                                                                                                                                                                  |
| `@typescript-eslint/consistent-type-definitions`                           | object shapes are `interface`, never a `type X = { … }` object literal. Mapped types are unaffected — the `-readonly [K in keyof …]` form model Signal Forms editors declare is a `type` on purpose                                                                                                                                                                                                                                                                                        |
| `@typescript-eslint/consistent-type-assertions`                            | `value as T`, never `<T>value`                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `@typescript-eslint/array-type`                                            | `T[]` and `readonly T[]`, not `Array<T>` / `ReadonlyArray<T>` (the `new Array<T>(n)` _constructor_ is fine)                                                                                                                                                                                                                                                                                                                                                                                |
| `@typescript-eslint/consistent-indexed-object-style`                       | `Record<K, V>` over a written-out index signature                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `@typescript-eslint/no-inferrable-types`                                   | no `const n: number = 1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `@typescript-eslint/no-namespace`, `no-require-imports`                    | ES modules only                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `@typescript-eslint/no-unused-vars`, `prefer-as-const`, `prefer-for-of`    | the usual stylistic set                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `@typescript-eslint/no-deprecated`                                         | type-aware: any use of a `@deprecated` API is an error, a call or a read, and, with typescript-eslint 8.73, also a deprecated property written in an object literal of its type (a spec fixture of a server payload says why it keeps one). This is why lint uses `parserOptions.projectService`                                                                                                                                                                                           |
| `no-restricted-imports`                                                    | the layer boundaries — one block for `shared/`, one for `core/`, and one per feature (the `features/messages` block is the one-way hub rule). See [architecture.md](./architecture.md)                                                                                                                                                                                                                                                                                                     |
| `no-restricted-syntax` (four time-zone selectors, and the `import()` bans) | the four ways the browser's own zone leaks in: `Date` mutators, `Date` readers, the multi-argument `new Date(y, m, …)` constructor, and an `Intl.DateTimeFormat` built without a `timeZone`. A short exemption list covers the conversion layer (the zone helpers, the format module, the date/time controls) and all specs — see [coding-standards.md](./coding-standards.md). The same rule bans a dynamic `import()` into the other application, which the exemption list does not lift |

Convention only — nothing checks these, so they are review material:

- `import type` for type-only imports. Widely followed, but `verbatimModuleSyntax` is off and there
  is no `consistent-type-imports` rule.
- All identifier naming (there is no `naming-convention` rule).
- JSDoc on exported APIs.
- Where a type is declared (see [Where types live](#where-types-live)).

The root tool configs (`vitest.config.ts`, `playwright.config.ts`, `transloco.config.ts`) belong to
no `tsconfig`, so they are listed in the parser's `allowDefaultProject`. Adding another root-level
`.ts` file without adding it there makes lint fail on that file.

## Naming

| Kind                                     | Form                                                                                                                                                                                                                                                                         |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Variables, functions, methods, signals   | `camelCase`. A `$` suffix means an RxJS stream (`search$`, `loadMore$`) and nothing else — signals never carry it                                                                                                                                                            |
| Classes, interfaces, type aliases        | `PascalCase` — no `I` prefix                                                                                                                                                                                                                                                 |
| Module-level constants, injection tokens | `UPPER_SNAKE_CASE` (`APP_CONFIG`, `MESSAGE_STATUSES`, `PROMULGATION_ADMIN_PLUGINS`)                                                                                                                                                                                          |
| Wire-shape interfaces                    | the backend's own name with a `Vo` suffix (`BatchInstanceVo`)                                                                                                                                                                                                                |
| Files                                    | kebab-case; the suffix names the role — `*.model.ts`, `*-api.service.ts`, `*.facade.ts`, `*.token.ts`, `*.util.ts`, `*.spec.ts`. One facade (`editor-facade.ts`) predates the dot form and `messages-facade.service.ts` carries a second suffix; new files use `*.facade.ts` |

A writable member kept behind a public read-only projection is underscore-prefixed and always
`private readonly`. The same idiom covers a `Subject` behind a public `Observable`:

```ts
private readonly _dirty = signal(false);
readonly dirty = this._dirty.asReadonly();
```

That is the idiom to follow — it is what every such member in `ui-apps/admin/src/` uses but one.
`MessageSelectionService` spells the same thing with an ES private field (`readonly #entries`,
projected as `selectedEntries` and four `computed()` views); it is the only `#` field in the
application and not a second convention to pick from. Do not underscore-prefix anything that is
not the writable half of a public companion.

There are no barrel files, and the only `paths` aliases are the two shared folders' — `@niord/map/*`
for `shared-map/` and `@niord/html/*` for `shared-html/`, declared in the root `tsconfig.json`, where
a call site names the file it wants (`@niord/map/ol-map`). Every other import is a deep relative
path — which is why the boundary lint rules need a sibling-relative regex beside the glob
([architecture.md](./architecture.md) → _Import boundaries_).

## Closed sets of values

There is no `enum` anywhere in `ui-apps/admin/src/`, and none should be added: `const enum` does not survive
`isolatedModules`, and a plain `enum` emits runtime code that a union of literals does not.

When a closed set needs only a type, write the union. When it also needs a runtime collection —
iteration order for a picker, a `Set` for membership, a `Record` for lookup — declare the values
**once** as an `as const` array and derive everything from it:

```ts
export const MESSAGE_STATUSES = [
  'DRAFT',
  'VERIFIED',
  'PUBLISHED',
  'CANCELLED',
  'EXPIRED',
  'DELETED',
] as const;

export type MessageStatus = (typeof MESSAGE_STATUSES)[number];

export const KNOWN_MESSAGE_STATUSES: ReadonlySet<MessageStatus> = new Set(MESSAGE_STATUSES);
```

Rules that follow from it:

- The array and every collection derived from it live in the **owning** model file. Consumers
  import the constant; they never re-list the literals locally.
- Where the union is owned elsewhere and the array only enumerates it for one screen, pin the two
  together with `satisfies` so a divergence is a compile error:
  `export const PROMULGATION_REQUIREMENTS = ['OPTIONAL', 'DEFAULT', 'MANDATORY'] as const satisfies readonly PromulgationRequirement[];`
- Most of these unions are server enums. A value the backend can send and the union does not list
  reaches the screen as a raw identifier; a value listed here the backend does not have is a
  control every save will refuse. The specs walk the arrays against the backend enum transcribed
  beside them — keep that spec when you add a member.
- Render order in such an array is often load-bearing (it is the checkbox order _and_ the
  save-payload order). Say so in a comment when it is.

## Exhaustive switches

A `switch` over a closed union takes **no `default`**. `noImplicitReturns` and the declared return
type then turn a newly added union member into a compile error:

```ts
function comparePrimary<T extends PublicationIssueRow>(
  a: T,
  b: T,
  column: IssueSortColumn,
  nameOf: (row: T) => string,
): number {
  switch (column) {
    case 'period':
      return issuePeriodKey(a) - issuePeriodKey(b);
    case 'edition':
      return COLLATOR.compare(a.edition ?? '', b.edition ?? '');
    case 'name':
      return COLLATOR.compare(nameOf(a), nameOf(b));
  }
}
```

A `default` is correct only when the input is genuinely wider than the union — typically a raw
`string` straight off the wire, where the fallback is the "unknown value" branch. Do not add one to
a switch whose argument is already the union; it disables the check.

A `Readonly<Record<Union, T>>` lookup table gives the same guarantee without a switch, and is the
better shape for a pure value mapping (label keys, icons, badge classes, default directions). There
is no `assertNever` helper in the codebase and none is needed.

## Wire types vs model types

API services are thin and there is **no adapter layer** — no `mappers/`, no `dto/` folder, no
interceptor that reshapes bodies. Normalisation, where a response needs it, lives in the API
service file itself, and the two shapes are named apart:

- **Wire shape** — an interface named after the backend value object with a `Vo` suffix, **not
  exported**, declared in the same file as the service. Every field is optional and nullable,
  because that is what the serialiser can produce.
- **Model** — the exported interface the rest of the application consumes: total, `readonly`, no
  nulls.
- **Mapper** — a small `…FromVo` arrow function beside them, filling defaults, piped through `map`.

```ts
interface BatchTypeVo {
  name?: string | null;
  instanceCount?: number | null;
}

const typeFromVo = (vo: BatchTypeVo): BatchType => ({
  name: vo.name ?? '',
  instanceCount: vo.instanceCount ?? 0,
});
```

Where the response already matches the model, the service types the call directly
(`http.get<PublicationSeries[]>(…)`) and no `Vo` pair exists. Use the pair when defaults, renames
or null-collapsing are needed; do not introduce one mechanically.

**A type annotation is not a runtime guarantee.** Several endpoints send shapes the declared types
do not describe — message dates are typed `string` but arrive as epoch-millisecond numbers, an
unknown message id answers `200` with a `null` body. Anything comparing such values must normalise
first — the editor reduces `number | string | Date` to epoch ms with `normalizeDate` in
`ui-apps/admin/src/app/features/editor/util/message-change.util.ts`. That helper belongs to the editor and the
layer boundaries keep it there; elsewhere, normalise at that feature's own API boundary rather than
reaching for it. The catalogue of these quirks
is [backend-api-notes.md](./backend-api-notes.md); when you meet a new one, add it there and
narrow at the boundary rather than widening the model.

### Where types live

| Type                                                            | Home                                                         |
| --------------------------------------------------------------- | ------------------------------------------------------------ |
| Vocabulary shared across features (`MessageStatus`, `MainType`) | `ui-apps/admin/src/app/shared/models/`                       |
| Feature-wide domain models                                      | `ui-apps/admin/src/app/features/<feature>/models/`           |
| A model owned by one admin section                              | that section's own folder, beside the components that use it |
| Wire shapes and the unions of one endpoint family               | beside the API service that calls it                         |
| A row/view shape one component renders                          | unexported, in that component's file                         |

The editor is the exception to the first row: it has no `models/` directory and keeps its types
beside the services and in `util/`. Follow the table for new features.

Components derive view state with `computed()`; they do not hold a parallel copy of server data
(see [architecture.md](./architecture.md)). A view shape that is not shared stays unexported — only
promote it when a second file needs it.

## Nullability, immutability, assertions

- Model nullable backend fields explicitly and narrow before use. `noUncheckedIndexedAccess` is
  off, so an index read is _not_ narrowed for you — check it yourself when the key may be absent.
- Model interfaces are `readonly` throughout, including `readonly T[]` for collections. Build new
  objects rather than mutating shared ones.
- Non-null assertions (`x!`) are rare outside specs — nine sites in `ui-apps/admin/src/`, four of them inside
  inline templates where a guard is awkward. Reach for a guard, a narrowing `computed()`, or an
  early return instead.
- `as` casts are confined to boundaries where the type genuinely is not known: JSON parsing, DOM
  and library interop, and walking a config object as `Record<string, unknown>`. Inside the
  application, a cast usually means the model is wrong.
- `satisfies` checks a literal against a type without widening its inferred keys — prefer it to an
  annotation when the narrow type is what callers need.

## Errors

`strict` implies `useUnknownInCatchVariables`, so a caught error is `unknown`. In a `catch` block
or an RxJS `catchError` — which is where failures are actually handled here — narrow before reading
anything off the error:

```ts
if (err instanceof HttpErrorResponse && err.status === 403) {
  /* … */
}
```

There are no error subclasses in this codebase — nothing `extends Error`, and nothing should
without a reason. Failures are handled by policy, not by type hierarchy: reads surface an inline
error state, mutations raise a toast. That policy, including what may be swallowed, is in
[coding-standards.md](./coding-standards.md).

## Imports and exports

- Named exports everywhere. The only `export default` statements are the five lazy route files
  (`admin.routes.ts`, `sysadmin.routes.ts`, `editor.routes.ts`, `messages.routes.ts`,
  `schedule.routes.ts`), where the router requires it.
- No namespace imports (`import * as …`) in application code.
- `import type` for anything used only as a type. It is a convention, not a compiler rule, so it is
  the reviewer who catches it.
- Respect the layer boundaries; when `shared/` or `core/` needs something a feature owns, invert
  the dependency with an injection token rather than reaching across — the existing tokens are
  catalogued in [architecture.md](./architecture.md) → _Extension points_.
