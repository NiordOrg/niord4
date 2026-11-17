# Country API changes

What changed in the country API from one base product version to the next, and what a country must
do about it. The API is what country code may import of the base product (fe-country-split-plan
§4.3):

| Entry point                | File                               | For                                    |
| -------------------------- | ---------------------------------- | -------------------------------------- |
| `@niord/admin/api`         | `ui-apps/admin/src/public-api.ts`  | the administration app's country files |
| `@niord/admin/api/testing` | `ui-apps/admin/src/testing-api.ts` | their specs only                       |
| `@niord/info/api`          | `ui-apps/info/src/public-api.ts`   | the public site's country files        |
| `@niord/info/api/testing`  | `ui-apps/info/src/testing-api.ts`  | their specs only                       |

A country's files under `admin/app/`, `admin/environments/`, `info/app/` and their specs import base
product code through these and nothing else; lint fails any other import (`npm run lint`); an
`@niord/<app>/internal/*` import outside an override fails compilation as well, since the workspace
does not map it. Only a guarded override (`admin/overrides/`, `info/overrides/`) reaches base product
internals, through `@niord/admin/internal/*` and `@niord/info/internal/*`.

**Paused until production.** This record is asleep until the first production deployment, or the
first country that the base product's own developers do not maintain, whichever comes first
([Paused until production](README.md#paused-until-production)): until then an entry is written only
when the base product's upgrade test demands one. The record starts again, empty, after 4.0.196;
what changed up to that version is in the `API-CHANGES.md` of its kit package. The policy and the
form of an entry below hold in full from the day the record wakes.

## The compatibility policy

- The API is a contract kept across base product versions. Each export is there on purpose: it has a
  contract comment saying what it is for a country and which extension point it serves, and the kit
  fails an export no country file, example file or generated file uses unless it is listed as a
  contract-only type with its reason (the kit's API test, which the base product's CI runs).
- A change that breaks the API — an export removed or renamed, a type narrowed, a token's value
  changed in a way a country's provider no longer satisfies — is named in its pull request and
  recorded below with what a country must do.
- A removed export is first deprecated: kept, its contract comment marked with a line
  `Deprecated since <version>: <what to use instead>`, or, once the version it goes in is decided,
  `Deprecated since <version>, removed in <version>: <what to use instead>` (never a JSDoc
  `@deprecated` tag, which the base product's lint would turn into an error at once; a
  `Deprecated since` line of another form fails set-up), and recorded below. `node setup.mjs` warns
  about every use of it in the country's files, naming the version it is removed in or that its
  removal is not scheduled yet, and the upgrade report lists each use under "To look at". It goes
  only after at least one base product version that both DK and GL have taken.
- A change to the set-up stub, `country-kit/templates/setup.mjs`, is a breaking change: every country
  commits the stub byte for byte as `fe/setup.mjs`, and set-up refuses one that is not the kit's. It
  is recorded below under its version with "What a country must do: copy
  `.niord/kit/templates/setup.mjs` over `fe/setup.mjs`". On the bump, set-up reports
  the old stub as a failed check with that copy in the upgrade report's To do, rather than refusing
  before the report; the example harness's upgrade test copies the new stub as a routine action.
- The example country (`country-kit/example/`) compiles against the API in the base product's CI, so a
  break cannot pass unnoticed; the upgrade test builds it against the previous published version's
  API as well.
- Base product code never imports the API: it is a one-way facade for country code.

## How an entry is written

One section per base product version, newest first; the changes not yet published go under
`Unreleased`, which always exists (the example harness's upgrade test inserts its fixture version
above it). A version is the number of the publish run that publishes it, unknown in the pull
request, so the first kit change after a publish renames `Unreleased` to
`## <version>: <what it brings>` and opens a new `Unreleased` above it. The renamed section keeps
every entry as it stood, its bold lead unchanged, and gets no opening text of its own, and a bold
lead is never used again in a later version: the upgrade report compares a released section the
country's previous copy has no heading of with that copy's `Unreleased` by lead alone, so a reworded
lead lists an entry again and a reused one hides a new entry. Each entry says:

- **What changed**, naming each export and its entry point.
- **Why**, in a sentence.
- **What a country must do**, concretely: the import to change, the export to use instead, the
  command to run. "Nothing" is a valid answer for an addition.
- For a deprecation, **when it goes**: not before a version both DK and GL have taken. Once that
  version is decided, the contract comment's line names it: `, removed in <version>`.

## Unreleased
