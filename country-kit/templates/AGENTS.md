# AGENTS.md

This repository is a country of Niord: its backend module in `be/`, its frontend in `fe/` and its
pipelines in `.github/`. It builds on one version of the base product, the country-unaware Niord,
which it never copies or edits. Below, `<cc>` is the country code in `fe/niord-country.json`, and
`<version>` is `be/pom.xml`'s `<parent>` version, the base product version it builds on.

`init` wrote this file from the kit's agent template. It names no country: keep the country's own
rules in a section of their own below it. `CLAUDE.md` beside it imports this file with the line
`@AGENTS.md`, which loads it in Claude Code (a country that kept its own `CLAUDE.md` adds that line
to it): keep every rule here.

## Read first

`node setup.mjs` in `fe/` unpacks the kit of `<version>` into `.niord/kit/` at the repository root,
and the base product's frontend into `fe/.niord/base/`. Read the kit's documents in `.niord/kit/`,
since they match the version you build against:

- `.niord/kit/docs/README.md`: the guides, and who reads which. The standard path of a new country
  is in `.niord/kit/docs/country-guide.md`, the reference behind it in
  `.niord/kit/docs/country-guide-in-depth.md`, the set-up an AI agent does from an empty folder in
  `.niord/kit/docs/set-up-prompt.md`, and what an operator sets up in a running system in
  `.niord/kit/docs/operators-guide.md`.
- `.niord/kit/README.md`: the kit's reference, every check, file and command.
- `.niord/kit/API-CHANGES.md`: what each base version changed, and what a country must do.
- `.niord/kit/docs/country-api.md`: the backend contract. Read it before you write the backend or
  its bootstrap. Read it with the base product's settings file and its settings catalog, in
  `.niord/kit/backend/defaults/base/`, and with the English of its backend texts, which you fetch
  into `.local-run/<version>/read/` as "The base product's backend files" in
  `.niord/kit/docs/country-guide-in-depth.md` says.

## The layout

The country owns and commits:

- `be/`: `pom.xml`, the Maven wrapper, its Java under `src/main/java/org/niord/<cc>/`, its Spring
  configuration and `defaults/country/` under `src/main/resources/`, its data in `bootstrap/`, and
  `overrides.json`.
- `fe/niord-country.json` (the manifest), `fe/niord-pins.lock.json` (the pins) and `fe/packs/<lang>/`
  (its home language packs).
- `fe/admin/` and `fe/info/`: the seams' files, its pages, overrides and branding.
- `fe/styles/` (its tokens and fonts), `fe/text-overrides/`, `fe/country/` (its own npm packages) and
  `fe/e2e/` (its end-to-end specs).
- The lists: `fe/missing-keys-accepted.json` (the escape hatch), `fe/build-urls-allowed.json`,
  `fe/audit-accepted.json`, `fe/admin/branding-unaccepted.json` and
  `fe/info/branding-unaccepted.json`.
- `.github/`: `ci.yml`, `image.yml`, `pack.yml`, `base-bump.yml`, `dependabot.yml`, `CODEOWNERS`.

`fe/setup.mjs` and `start-local.sh` are committed copies of the kit's, never changed by hand.
Generated and never committed: `.niord/` at the root (the kit and its zip; it ignores itself), every
file `fe/.gitignore` lists (`fe/.niord/`, `fe/angular.json`, `fe/package.json`,
`fe/package-lock.json` and the other workspace files), what `be/.gitignore` lists (`be/target/` and
`be/.flattened-pom.xml`) and `.local-run/`, the launcher's downloads and logs. On Windows, git
records a new file without its exec bit: give `start-local.sh`, `be/mvnw` and every script
`git update-index --chmod=+x <file>` after `git add` and before their first commit.

## The commands

Set-up and Maven need `JAVA_HOME` naming a JDK 25, exported in the shell: set-up fetches the base
product through `../be/mvnw`. In `fe/`:

```bash
node setup.mjs                                    # verify the pins, check, stage, generate
node setup.mjs --add-override <base file> --reason <text>
node setup.mjs --accept-override <file>           # after reviewing the base file's change
node setup.mjs --update-country-lock              # after a change to fe/country/package.json
node setup.mjs --update-pins [<lang>=<tag>]...    # move the foreign packs' pins
npm ci
npm run build        # the audit, both applications, the checks on every country build
npm test             # the country's specs, and the base product's spec of each override
npm run lint
npm run e2e          # the specs in e2e/, against a running start:admin
npm run image:info -- --tag <registry>/<name>:<tag>
npm run start:admin  # 4200; start:info is the public site on 4400
npm run audit        # the audit alone; bundle:info is the public site's bundle report alone
```

Run `node setup.mjs` again after any change to the manifest, a pack, branding, a seam, an override,
a text override or the country's packages, and before any Maven build. In `be/` (one module), with
Docker running:

```bash
./mvnw verify                                                                          # the suite
./mvnw verify -Dit.test=<Class> -Dtest=_none_ -Dsurefire.failIfNoSpecifiedTests=false  # one IT
./mvnw test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false                     # one unit test
```

After a whole `verify`, check that the country build group ran: in `be/target/`,
`failsafe-reports/failsafe-summary-niord-country-build.xml` has `completed` above 0 and `skipped`
below `completed`, and `surefire-reports/` holds a `CountryContractGuardTest` report. From the
root, `./start-local.sh` starts the whole stack locally (`--sample` for the base product's sample,
`--sample --reset` to load it afresh). Its plain run bootstraps the country's database once: start it
the first time only when `be/bootstrap/` holds `batch-set.json` and a `domains.json` with an active
domain.

## When a check fails

Each line is "fails with → what you do". The section of `.niord/kit/README.md` named in brackets
has the detail.

Set-up:

- Maven's `The JAVA_HOME environment variable is not defined correctly` → export `JAVA_HOME`
  naming a JDK 25, and run set-up again (Set-up).
- A bump without a new pin, or a zip hash that is not the pin → move the pin as "The base bump"
  below says (Set-up).
- `fe/setup.mjs` is not the kit's own → copy `.niord/kit/templates/setup.mjs` over it.
- A pin is withdrawn (the line gives the home's reason) → `node setup.mjs --update-pins`
  (Withdrawing a pack).
- The pack check (a key missing, or in both status lists or neither) → in a home pack a person
  translates and accepts; for a foreign pack, wait for its home, write a text override, or, with the
  maintainer's say-so, add an escape-hatch entry with its reason (Language packs).
- The escape hatch printed, or a stale entry → remove what is no longer needed (Language packs).
- Branding → make it match `.niord/kit/branding.schema.json` (Branding).
- The production environment → in `fe/admin/environments/environment.production.ts`,
  `production: true`, and `apiBaseUrl`, `keycloak.url` and `map.wmsUrl` paths starting with one `/` (Set-up).
- The settings file's languages → the same languages in the manifest and in
  `be/src/main/resources/defaults/country/settings.json` (Set-up).
- An override → compare, then `--accept-override`; on `SECURITY:` merge the fix first; a protected
  or removed file needs an extension point (Guarded overrides).
- An orphan file → reach it from a seam's file, fix its name, or delete it (The orphan-file check).
- A country dependency → fix `fe/country/package.json`, run `--update-country-lock`; a package
  under 7 days old waits (Country dependencies).
- `audit-accepted.json` or an advisory → update a country package; a base product one is listed
  with a reason until a base version fixes it (npm audit).
- A secret → remove it; a false positive is a base product change (Secrets).

The build, the backend and the launcher:

- One of the ten checks on every country build → do what the finding names; never stage a file
  where a base product file lands (The checks on every country build).
- An absolute URL on no list → remove it, or list the country's own address in
  `fe/build-urls-allowed.json` with a reason and its `app`, with the maintainer's say-so (The checks
  on every country build).
- The bundle baseline missing (a warning) or exceeded → start it as the README says; record it
  again only once a change explains the growth (The checks on every country build).
- `fe/.niord/packs/ lacks a pack this build copies: run node fe/setup.mjs first` → run set-up; the
  pom's copies follow the manifest's packs in the same change (The backend build).
- `Niord country contract: the start is refused` → remove each finding it lists; more needs an
  extension point (the backend contract).
- `niord.s124.producer-code '…' is not a valid IHO S-62 producer code` → four upper-case letters or
  digits in `be/src/main/resources/application.yml`, then start the launcher again with no
  `dropdb`: a backend that did not start posted no bootstrap (Running it locally).
- The group ran 0 tests, or `Tests run: 0` with BUILD SUCCESS → start Docker, check that `be/pom.xml`
  declares niord-base's test-jar, and run again.
- The launcher refuses a database without its mark → name another in `start-local.json` (The
  databases); a database is dropped only when a person says so.
- The country's domain has no pencil in the menu's domain list, or a firing schedule's domain
  picker lacks it, for the local `sysadmin` → restart `./start-local.sh`; the launcher adds a
  missing client, and a session signed in before it needs a new sign-in (Running it locally). The
  areas themselves open without that client: the local `sysadmin` holds `sysadmin` on
  `niord-client-master`, which counts in every domain.

## Never

- Edit a generated file, anything under `.niord/` or `fe/.niord/`, `fe/setup.mjs` or
  `start-local.sh`. When the upgrade report says so, copy `.niord/kit/templates/setup.mjs` over
  `fe/setup.mjs`; at every bump, copy `.niord/kit/templates/start-local.sh` over `start-local.sh`.
- Add an npm dependency anywhere but `fe/country/package.json`.
- Fetch a font, or link any `https:` font face or stylesheet: fonts are files in `fe/styles/`.
- Copy base product text into a pack. The country's own wording of a base key is a text override or
  branding.
- Weaken a check, add an allow-list or escape-hatch entry, or move an address to make a build green,
  without the maintainer's say-so.
- Accept a translation or record a fingerprint on a person's behalf: `pack.mjs accept` is a person's
  act. Draft a value only when asked, and list it in the pack's `status/unaccepted.json`.
- Write a country word into a base product file, or change one from here: a base product change is
  a request to the base product.
- Commit a secret: frontend files ship to every browser, and the settings file holds none.
- Post a bootstrap or an import to any deployment but your local one.

## The base bump

One pull request moves `be/pom.xml`'s `<parent>`, `fe/niord-pins.lock.json`'s base
`{ version, sha256 }` and its kit `{ sha256 }` together. The Niord base bump app opens it minutes
after each release, on a `base-bump/<version>` branch whose one change is the `<parent>`, and never
merges it. A newer release moves the same pull request on its branch, and your commits on it stay; a
branch that comes to conflict with `main` is replaced by a fresh pull request, and the old one is
closed with a comment. Without the app, a person pushes `base-bump/<version>` from `main` with the
`<parent>` moved and opens it. On either, the Base bump workflow
(`.github/workflows/base-bump.yml`) reads release `v<version>`'s notes and, only when their two
lines equal the downloads' hashes, pins the lock, runs set-up with every pinned pack moved to its
home's newest pack, copies the stub and the launcher, commits as `github-actions[bot]`, dispatches
CI and posts one comment: `## Human actions` as boxes, and the upgrade report. The status
`niord/upgrade-actions` stays red while a box is unticked.

Each `sha256` is the one on its line in the base product release notes,
`<sha256>  niord-fe-src-<version>.zip` and `<sha256>  niord-country-kit-<version>.zip`, never one
computed from a download: the workflow's pin is the download's hash after the notes confirmed it.
When it cannot read the notes, the run fails and pins nothing: run it again on the pull request
(Actions, Base bump, Run workflow, `pr <n>`).

Your part:

1. Read the comment: what the workflow pinned and copied, `## Human actions`, and the upgrade
   report.
2. Do **To do** of the upgrade report (in the comment, and in `fe/.niord/niord-upgrade-report.md`
   after a set-up of your own): your packs' new keys, each override, and
   `node setup.mjs --update-pins` only when the workflow could not move a pinned pack's pin (its
   warning says why). The comment names each moved pin, old and new; compare a first pin's digest
   with the summary of the home's Pack run that published its tag (Pinning a pack).
3. Do each `## Human actions` item in full, and tick its box: each new section's "What a country
   must do" of `.niord/kit/API-CHANGES.md` (set-up checks none of them), the merge of
   `.niord/kit/templates/AGENTS.md` into this file, each new bootstrap release folder for a person
   to decide on, and each file of `be/bootstrap/templates/` that replaces a base product template the
   new version changed (a copy at the template's own path, or a language variant `x_<ll>.ftl` of its
   `x.ftl`): compare it with the new base template, carry the change into your file, and ship it in a
   release folder of your own. A box is ticked, never deleted, and never ticked for work not done.
4. A workflow file item (`pack.yml`, `base-bump.yml`, `image.yml`) is a copy or merge by hand: the
   workflow's token cannot push a workflow file.
5. A newer release moves this pull request and posts the comment again: a box keeps its tick while
   what it is about is unchanged (its text, and for a box about a kit template the template's content,
   which a hidden marker at the box's end names by its hash), one whose text or template changed is
   asked again, and a workflow file still to copy is always asked again; your commits on the branch
   stay.
6. When you changed files, run `npm ci`, the build, the tests, lint, and `./mvnw verify` with the
   group count. A person reviews and merges, never you.

Without the app, or until it is installed, a person opens the bump on `base-bump/<version>`.
"Install the Niord base bump app" and "How an upgrade works today" in
`.niord/kit/docs/responsibilities-and-upgrades.md` say the rest.

## The backend

The base product's rules, which the country's backend keeps too:

- Java 25, Spring Boot 4, Spring MVC (`@RestController`, every handler under `/rest/`).
- Services: `@Service` with field `jakarta.inject.Inject`. Controllers: `private final` fields and a
  package-private constructor. Never `@Autowired` in main code.
- Spring's `@Transactional`, reached through another bean, never by self-invocation.
- Start-up work on the database: `@EventListener(ApplicationReadyEvent.class)` with
  `@Transactional`, never `@PostConstruct`.
- Every handler has `@PermitAll`, `@RolesAllowed` or `@DenyAll`, on the method or its class.
- No CORS anywhere. No protected bean replaced or added (`SecurityFilterChain`, the JWT converter,
  `NiordDomainFilter`, the domain, user, settings, template, script and mail services,
  `WebAppConfig`: the contract lists them), no `JwtDecoder`, no servlet filter, no
  `@PropertySource`, no `spring.security.*` key but `spring.security.oauth2.resourceserver.jwt.issuer-uri`,
  no `management.*` key that widens the actuator. The start-up guard refuses most of these.
- Errors: throw `NiordWebException`. JSON: Jackson 3 (`tools.jackson.*`).
- Nothing depends on the JVM's zone, and the JVM runs in UTC: the launcher passes
  `-Duser.timezone=UTC`; pass it on any other start too. A domain's time zone formats the
  administration app's and the backend's times; the public site's is `timeZone` in
  `fe/info/app/info.config.ts`, UTC unless set.
- A repository change never changes or deletes a deployment's rows. Templates, scripts and reports
  are data: they arrive only by the bootstrap of a fresh database (`be/bootstrap/`) or a sysadmin's
  import of a release folder, `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` with its `README.md`,
  never changed once merged. None is ever in `src/main/resources`.
- Country texts: `src/main/resources/defaults/country/texts/<lang>/`, its own keys prefixed `<cc>.`,
  and its own wording of a base key that a template or Java reads.
- The settings file, `src/main/resources/defaults/country/settings.json`, sets every key of the base
  product's or lists it in `useBaseDefault` with a reason; it sets `country`, `organisation` and
  `"niord.home": "${NIORD_HOME}"`, and holds no secret. Own settings are declared in code (an
  `@Setting` field or a static `Setting` constant) with their scope.
- The only override is a static image under `META-INF/resources/img/`, listed in `be/overrides.json`.
- `*Test` runs under Surefire and `*ITTest` under Failsafe; a class named otherwise never runs, and a
  run whose ITs skipped reports `Tests run: 0` with BUILD SUCCESS.
- A change looks like the code around it. A suspected base product bug is reported to the base
  product, never patched here.

## The guides

`.niord/kit/docs/` holds the guides of the version you build against. This file was copied at
`init`; where it and they differ, they are right, and this file is brought up to date.
