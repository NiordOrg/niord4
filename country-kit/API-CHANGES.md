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
  break cannot pass unnoticed; from P3c the upgrade test builds it against the previous published
  version's API as well.
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

**The base product's repository is `NiordOrg/niord4`, public, from 4.0.182.** The Base bump
template's `BASE_REPO` names it instead of `SIFA-DK/niord-ng`, and the kit's guides download a
release from it (`gh release download v<version> --repo NiordOrg/niord4`). No export, text or
setting changes, and the packages stay in the registry of `NiordOrg/niord`. Why: the base product
moved to a public repository of its own, without the history; `SIFA-DK/niord-ng`, with the releases
up to 4.0.181, is archived and stays private.

What a country must do: copy `.niord/kit/templates/base-bump.yml` over
`.github/workflows/base-bump.yml` at this bump; the Base bump comment lists the differing copy as an
item. Until then its copy looks for the release notes in `SIFA-DK/niord-ng`, which has no 4.0.182
or later, and asks for the pin by hand: pin the lock from
`gh release view v<version> --repo NiordOrg/niord4`. A repository secret `NIORD_NG_RELEASE_READ`,
where a country made one, is no longer needed and may be deleted.

**The error hunt's phase 2 adds 37 administration texts, changes 7 and drops 1.** In
`fe/languages/en/i18n/admin/en.json`, new keys:

- `schedule.toast.exercisesPartlyFailed`, `schedule.toast.exercisesPartlyFailedOne`;
- `shared.forms.lengthCounter.count`, `.tooLong`, `.countIn`, `.tooLongIn`;
- `admin.categories.toast.testRefused`, `admin.paramTypes.paramDialog.idTaken`,
  `admin.schedules.field.scheduleDaysInvalid`, `admin.secom.outbox.newestOnly`,
  `admin.settings.editor.emptyMeansDefault`, `admin.series.nextNumber.toast.belowUsed`;
- `admin.publications.issue.clearUploadedFileMessage`, `admin.publications.series.toast.failedRules`,
  `admin.publications.categories.hint.nameRequired`, `.priorityRange`, `.priorityRequired`,
  `admin.publications.error.categoryInvalid`, `.categoryIdTaken`, `.issueNotOpen`, `.staleNotShown`,
  `.idUnservable`;
- `messages.mail.refused`, `messages.statusUpdate.reason.noTitle`, `.mandatoryOff`, `.scheduled`,
  `messages.statusUpdate.toast.rowsRefused`, `.rowsRefusedOne`, `messages.print.dialog.testHeader`,
  `messages.print.errorExpandParams`, `messages.tags.dialog.edit.typeAdminOnly`,
  `messages.tags.dialog.refused`;
- `editor.load.toStart`, `editor.load.error.otherDomain`, `.copyOtherDomain`,
  `editor.start.subtitleCannotCreate`, `editor.status.error.refusedReloaded`.

Changed English: `common.dateTime.noTimeZone`, `admin.domains.field.publishHint`,
`admin.schedules.toast.exercisesStarted` ("Firing exercises updated", no longer "started"),
`messages.list.attention.title`, `editor.status.error.missingFields`, `geometry.import.utmHelp` and
`geometry.import.utmExampleFormat`. Gone: `editor.status.error.refused`, which
`editor.status.error.refusedReloaded` replaces. The public site's texts are unchanged. No export is
added or removed. The backend's `PUT /rest/message-series/series/{seriesIds}/number/{year}` now
answers 400 for a next number below one the series already used (below the number Compute gives),
and the next-number popover then shows `admin.series.nextNumber.toast.belowUsed` instead of the
generic `admin.series.nextNumber.toast.updateError`. Why: the fixes say what the backend refused and
why, count what is typed against a column's length, and correct texts that named the wrong cause.

What a country must do: its home pack gives each new key a value at its next bump, since the pack
check fails a missing key, and lists it in the pack's `status/unaccepted.json` until it is accepted;
a pinned pack that lacks one lists it in `missing-keys-accepted.json`, staging then ships the
English text, until a version of that pack carries it. A changed English text is a warning only:
the home checks its translation and accepts it (`pack accept`) or lists the key as unaccepted. A
pack's `editor.status.error.refused` is stripped from the staged bundle and listed; it may be
deleted.

**The settings check says what the deployment does with a language the build does not name.**
`lib/settings-check.mjs`'s warning for a language of `defaults/country/settings.json` that the
manifest does not name ends in what the deployment does with it: "the deployment drops it" for
`siteLanguages` and `infoSiteLanguages`, as before; for `modelLanguages` the deployment keeps it and
the editor gives it a field, under its pack's label where the manifest names it as a UI language of
the administration app and otherwise labelled with its code in upper case; for `defaultLanguage` the administration app keeps it
while `modelLanguages` names it and otherwise takes the first model language; for
`infoDefaultLanguage` the public site takes its first language instead. No export is added or
removed, and nothing that was a warning fails. Why: "the deployment drops it" was untrue of the
three keys whose languages the deployment keeps or replaces.

What a country must do: nothing; its next set-up may print the reworded warning.

**The branding check holds the public site's footer labels to their documents, as warnings.**
`branding.schema.json` gives the public site's legal documents their footer labels (`labelKey`:
`FOOTER_DISCLAIMER` for the disclaimer, `FOOTER_COOKIES` for the cookie statement) and its legal
section `labelCheck: "warning"`, and `FOOTER_LOGO` `recommends` `FOOTER_LOGO_ALT`. Set-up now warns
of a footer label set without its `LEGAL_TITLE_` key or with its document shipped in no branding
language, of a shipped legal document whose language's branding lacks its footer label ("so the
default footer never offers it"), and of `FOOTER_LOGO` set without `FOOTER_LOGO_ALT`. These are
warnings, not failures: a country may replace the default footer with its own. The schema's two new
words are the kit's; a country's branding never writes them. No export is added or removed. Why: the
default footer offers a document only by its label, and a label without its dialog's title or
document, or a logo without its text, went unnoticed.

What a country must do: nothing; a warning it gets is for its branding or its legal files to
answer.

**The pack check warns of a pack without its own language's name.** A pack whose backend half's
`web.properties` lacks its own `lang.<code>` (for example `lang.kl` in a `kl` pack), or holds it
empty, gets the warning "… has no lang.<code>, the language's name in a draft print: where no pack
has it, the print names <code> by its code, <CODE>", for a UI language as for a content language;
for a pack with no `web.properties` at all, the line that says so names it too. Every
`lang.<code>` key of `web` is still kept when a pack is staged or pruned, and the other languages'
stay optional. No export is added or removed. Why: a draft print names each model language by that
key, and failed without it until a deployment imports the base product's release folder
`2026-11-16-error-hunt-phase-2-templates`; after it, the print names the language by its code.

What a country must do: nothing at its bump. A home pack that gets the warning gives the key a
value; a pinned pack whose home left it out cannot be fixed in the pack, so the country supplies the
name from its own texts layer or asks the home country.

**The agent template names the missing pencil, not "sees only Messages".** In
`templates/AGENTS.md`, "When a check fails" says that when the country's domain has no pencil in
the menu's domain list, or a firing schedule's domain picker lacks it, for the local `sysadmin`,
`./start-local.sh` is restarted (the launcher adds a missing client, and a session signed in before
it needs a new sign-in), and that the areas open without that client, since the local `sysadmin`
holds `sysadmin` on `niord-client-master`, which counts in every domain. No export is added or
removed. Why: the local `sysadmin` sees every area without the client; what it lacks is the domain's
pencil.

What a country must do at its bump: merge the line from `.niord/kit/templates/AGENTS.md` into
`AGENTS.md`; the Base bump comment's agent-template box asks for it.

**`ci.yml`'s push filter comment says when it decides an image.** In `templates/ci.yml`, the
comment on the push filter says that the Image workflow (`image.yml`) runs on demand only, as the
kit writes it, and that the filter decides which push makes an image only once the country gives
`image.yml` a `workflow_run` trigger on a green push run of CI. The filter is unchanged. No export
is added or removed. Why: the comment said the Image workflow follows a green push run of CI, and
the kit's `image.yml` has only `workflow_dispatch`.

What a country must do: nothing; the upgrade report lists the template under To look at.

**The public site's image verifies an https chart server.** In `nginx/default.conf.template`, the
`/wms/` location verifies an https `WMS_SERVER_URL` against the image's CA bundle, for the name in
the URL, which it also sends as SNI, as the backend's location does, and empties
`Proxy-Authorization` and `NiordDomain` beside `Authorization` and `Cookie`. No export is added or
removed. Why: the image checked an https backend's certificate, but not the chart server's.

What a country must do: nothing while its public site has no sea chart. A country that turns the
chart on with an https `WMS_SERVER_URL` needs a chart server whose certificate is publicly trusted:
one a private CA signed is refused (502). The next image the country builds carries the file.

**`roleGuard` counts a role held on the base client.** `roleGuard` of `@niord/admin/api`, with
every role check of the administration app behind it, now grants an `editor`, `admin` or `sysadmin`
role (the `AuthRole` values other than `user`) held on the base client `niord-client-master` in
every domain, as the backend does, beside the role on the session domain's client. A signed-in
account with no role in the session domain is no longer offered the Status filter's non-public
statuses or the message actions whose endpoints ask for the `user` role, and Mail is offered to
editors only. No export is added or removed. Why: the app offered less than the backend grants to an
account whose roles are on the base client, and offered actions the backend refuses to an account
with no role in the domain.

What a country must do: nothing. A route of its own guarded by `roleGuard` now admits an account
whose role is on the base client; a deployment that sets `niord.oidc.base-client` to another client
is not read by the app, which reads `niord-client-master`.

**The public site's image passes on a message id that holds an encoded slash.**
`nginx/default.conf.template` gains a location for `/rest/public/v1/message/<id>` that passes the
request line on as it arrived, as the one-message PDF's location does, and both hold the raw line to
one encoded path segment that may now carry `%2F` (in either case) and still refuses an encoded dot,
backslash, percent, semicolon or NUL, an encoded double slash, a further segment and a `..`. Before,
a short id holding a slash, such as `B/1 2026`, reached the backend split in two on the first path
and was refused with 403 on the second. The container test
(`fe/scripts/info-container-test.mjs`) asks for both. No export is added or removed. Why: the
backend reads `%2F` in the message id on these paths, and the public site's message details and
prints of such notices failed on the site's own host.

What a country must do: nothing in its repository; the bump brings the new file, and the next image
the country builds carries it.

**`init` writes a whole country from a handful of answers.** `init.mjs` asks the answers line by
line, each standard default in brackets and taken with Enter alone, or reads them from
`--answers <file>`; `init-answers.schema.json` names them and `lib/answers.mjs` checks them. It
checks the release from `--sha256-file <file>`, the release notes' two lines, or from
`--zip-sha256` and `--kit-sha256`, needs no `be/pom.xml` first, reads the source zip in memory and
takes the version from the kit's `kit.json`. It writes `be/` whole: the pom from
`templates/be/pom.xml.tmpl` with the country build group's test dependencies, the Maven wrapper, the
application class, `application.yml` with `niord.s124.enabled: true` and the producer code when one
is answered, `application-local.yml`, `defaults/country/settings.json` from the base settings file,
the bootstrap with the first domain, its time zone and its series, `overrides.json` and the group
check. Beside it it writes
`fe/info/app/info.config.ts` with the time zone, `.github/CODEOWNERS` with the owners,
`.github/workflows/image.yml` with its `MODULE` filled, `.github/workflows/ci.yml` and a root
`README.md`. The older `init.mjs <cc> --zip-sha256 … --kit-sha256 …` still runs and asks the other
answers; there is no frontend-only mode. No export is added or removed. Why: a new country is green
and runnable with no file written by hand.

What a country must do: nothing; a country that exists never runs init again.

**The kit package carries the base settings file, its catalog, the PDF logo and the Maven wrapper.**
The kit package's build adds six entries under `backend/`, copied from the base product's `be/`:
`backend/mvnw` (LF), `backend/mvnw.cmd` (CRLF), `backend/maven-wrapper.properties`,
`backend/defaults/base/settings.json`, `backend/defaults/base/settings-catalog.json` and
`backend/img/company-logo.png`; the kit copy of a `NIORD_BASE` set-up carries them too. No export is
added or removed. Why: init writes the backend from them, and a country computes its logo override's
`basedOn` with `sha256sum .niord/kit/backend/img/company-logo.png`, with no jar.

What a country must do: nothing.

**The launcher gives each active bootstrap domain its client in the local realm.** The library of
`org.niord:niord-local-run`, at the bootstrap step of every start, creates in the shared local
Keycloak the missing client of each domain of `be/bootstrap/domains.json` with `"active": true`,
with the roles user, editor, admin, sysadmin and publication-curate, and gives sysadmin and
publication-curate to the group Sysadmin. It never updates or deletes a client, and it reads only
the country's `be/bootstrap/`: never the base product's bootstrap archive, and nothing on a start
with `--sample`; `templates/start-local.sh` is unchanged. No export is added or removed. Why: in a
new domain the local sysadmin saw only Messages until someone made the domain's client by hand.

What a country must do: nothing; the launcher finds a client that exists and adds nothing.

**The public site's default footer offers the legal documents the branding labels.** Beside the
copyright line, the branding texts `FOOTER_DISCLAIMER` and `FOOTER_COOKIES` open the legal dialog
with the disclaimer and the cookie statement, `FOOTER_ACCESSIBILITY` links the
`accessibilityStatement` of `fe/info/app/info.config.ts`, and `FOOTER_LOGO` shows the footer logo
with `FOOTER_LOGO_ALT`, each only when its label is set; with no label set the footer is as it was.
No export is added or removed. Why: a country offers its legal documents with files and labels
alone, without a footer component of its own.

What a country must do: nothing; a `footerComponent` of its own is kept as it is.

**Two guides: the short standard path and the in-depth reference.** `docs/country-guide.md` is now
the standard path: the release, init's answers, set-up and the local run, then Make it yours and
Deploy, with the definition of done and the checklist before go-live.
`docs/country-guide-in-depth.md` holds the reference that was the old guide's steps 1 to 11, under
the same headings where another document links one (The public site's time zone, The base product's
backend files, Recipe: add a language). No export is added or removed. Why: most countries need only
the standard path.

What a country must do: nothing. A link of its own into a section of the old guide finds that
section in `.niord/kit/docs/country-guide-in-depth.md`.

**The agent template's "Read first" and "When a check fails" changed.** In `templates/AGENTS.md`,
"Read first" names the short guide `docs/country-guide.md`, the in-depth guide
`docs/country-guide-in-depth.md` and the new set-up prompt
[docs/set-up-prompt.md](docs/set-up-prompt.md), which a person gives an AI agent to set up a new
country from an empty folder, and points at `.niord/kit/backend/defaults/base/` for the base
product's settings file and its catalog. In "When a check fails", a local `sysadmin` who sees only
Messages in the country's domain restarts `./start-local.sh`, whose launcher adds a missing client,
instead of making the client in the local Keycloak. No export is added or removed. Why: the agent
contract names the guides as they are now, and the launcher makes the client.

What a country must do at its bump: merge "Read first" and the `sysadmin` line of "When a check
fails" from `.niord/kit/templates/AGENTS.md` into `AGENTS.md`; it is the merge the Base bump
comment's agent-template box asks for, so one merge ticks both boxes.

**`ci.yml` is a kit template now, and the upgrade report compares it.** `templates/ci.yml` (the
backend's tests with set-up before Maven, the override check and the group count; the frontend's
set-up, specs, lint, end-to-end list and build; `workflow_dispatch`) is what init writes into a new
country as `.github/workflows/ci.yml`, and the upgrade report lists a change to it under To look at
as a merge into `.github/workflows/ci.yml`. A template new to the kit counts as changed, so the
first bump onto it lists it once; until `base-bump.yml` names it, the Base bump comment shows it as
a generic Look at item. No export is added or removed. Why: every country's CI builds the country,
and a later change every CI needs is announced.

What a country must do at its bump: compare `.github/workflows/ci.yml` with
`.niord/kit/templates/ci.yml` once and take what it wants; nothing in it is required.

**The base product reads no root-level backend texts.** The backend takes a default text from
`defaults/country/texts/<lang>/<bundle>.properties`, then from the staged language pack or the base
product's English, and no longer from a `<bundle>_<lang>.properties` at the root of the class path;
the text check's rule `root-bundle` fails a country build that still has one. No export is added or
removed. Why: the root layer was for a country that did not yet stage its language pack, and no
country needs it now.

What a country must do: nothing when it has no root-level `<bundle>_<lang>.properties` (DK and GL
have none). Otherwise it moves the keys into `defaults/country/texts/<lang>/<bundle>.properties`
before the bump; its build's text check names any it finds (`.niord/kit/docs/country-api.md`,
§ Changes).

**The source zip carries nothing of the kit.** `niord-fe-src` no longer holds the two files the
kit's move left in it, the stand-in set-up a stub from before the move handed over to and the stub
it named; the kit, its stub included, is only the package `org.niord:niord-country-kit`. No export
is added or removed. Why: a stub of 4.0.109 or later fetches the kit package.

What a country must do: nothing on 4.0.109 or later. A country on an earlier version bumps to
4.0.111 first.

**The upgrade report puts every failed check under To do, does not list a section of a version the
country already had, compares a released section with the previous Unreleased per entry, names the
kit's files at `.niord/kit/` from the repository root, and gives each listed action whole.** A
failed check's line that no To-do item stands for is listed under To do as set-up printed it, so a
red set-up never reads "Nothing."; a section whose version is not newer than the previous version
is not listed; a released section the previous copy has no heading of lists only the leads the
previous copy's Unreleased lacked; the report names `.niord/kit/API-CHANGES.md`,
`.niord/kit/security.json` and `.niord/kit/seams.json`, and set-up's deprecation warning
`.niord/kit/API-CHANGES.md`, each from the repository root; after a lead-in ending in a colon each
list item is one action, given whole in the JSON and the Markdown. The JSON's keys are unchanged.
No export is added or removed. Why: a red bump could read "Nothing.", a renamed section was listed
again with its actions done, and a long action was cut at 300 characters.

What a country must do: nothing; the Base bump comment has one box per list item.

**`templates/gitattributes` keeps Windows command scripts CRLF.** After `* text=auto eol=lf` it has
`*.cmd text eol=crlf`, so `init` writes it into `fe/.gitattributes` and the root `.gitattributes`
and `be/mvnw.cmd` keeps CRLF on every checkout. No export is added or removed. Why: only a country
that pinned its Windows command scripts itself kept their CRLF.

What a country must do: nothing. A country whose `be/mvnw.cmd` is pinned nowhere may add
`*.cmd text eol=crlf` to its root `.gitattributes`; DK and GL pin it in `be/.gitattributes`.

**`info-separation-check.mjs --country` takes two letters.** The public site's separation check
refuses a `--country` that is not two letters a-z, the country's ISO 3166-1 alpha-2 code, as `init`
and the manifest already do. No export is added or removed. Why: it was the last of the checks to
take three letters.

What a country must do: nothing.

**`AppConfig` gains the optional `s124Enabled`.** `AppConfig` (exported by `@niord/admin/api`) has
a new optional `readonly s124Enabled?: boolean`, which the web-settings overlay fills from
`/rest/settings/web`: the backend computes it from its boot property `niord.s124.enabled`, and an
absent value reads as on. No export is added or removed (the section gate's move from
`GET /rest/settings/setting/secomEnabled` to the same `/rest/settings/web` map is inside the app).
Why: the dictionaries page leaves the S-124 agency reader out of its delete confirm when S-124 is
off. What a country must do: nothing; its environment files never set it.

**S-124 can be switched off, and the chart has no default producer code.** The backend half is the
boot property `niord.s124.enabled` (default `true`), which `niord.secom.enabled` needs; with it
`false`, `/rest/S-124/**` answers 404 and the producer code is neither used nor checked
(`be/docs/country-api.md` § Changes). The chart renders `niord.s124.enabled` and
`niord.s124.producer-code` only when its values set `s124.enabled` and `s124.producerCode`; until
now it always rendered `DK00`, which overrode the image's code. Why: the generic niord-app is a
demo application with no producer code, and writes `false`. What a country must do at its bump:
write `niord.s124.enabled` explicitly in its backend's `application.yml` (`true` for DK and GL),
and check that its GitLab chart values set `s124.producerCode` wherever a deployment relied on
`DK00`, since the jar's `DK01` applies otherwise.

**The Niord base bump app opens the base bump; the pinned packs ride along.** The base product's
publish run opens the bump pull request minutes after each release, in every country repository
that installed the GitHub App Niord base bump (`https://github.com/apps/niord-base-bump`): it
pushes `base-bump/<version>` from the default branch, whose one change is the `<parent>` of
`be/pom.xml`. A newer release moves the open one on its branch; a branch in conflict with `main` is
replaced by a fresh pull request, and the old one is closed with a comment.
`templates/base-bump.yml` moves every pinned pack to its home's newest pack
(`node setup.mjs --update-pins`) in its pin commit and names each moved pin, from and to, with the
home's `pack-releases.md`, in its comment; keeps the tick of a box while what it is about is
unchanged when it posts the comment again (its text, and for a box about a kit template the
template's content, named by a hidden marker at the box's end); and accepts `base-bump/` branches
only. `templates/renovate.json` is
retired, with its `CODEOWNERS` line and its place among the templates the upgrade report compares;
the comment of `templates/dependabot.yml` names the base bump instead of Renovate; `init` writes no
`.github/renovate.json` and names the install link in its closing lines. No export is added or
removed. Why: the bump arrives by itself within minutes, with no schedule to pause and no
credential in the country.

What a country must do: Nothing at its bump beyond the boxes the Base bump comment itself lists
(the copy of `.niord/kit/templates/base-bump.yml` over `.github/workflows/base-bump.yml` and the
merge of `.niord/kit/templates/AGENTS.md` into `AGENTS.md`).

What a country should know:

- Install the Niord base bump app once on this repository, with "Only select repositories"
  ([Install the Niord base bump app](docs/responsibilities-and-upgrades.md#install-the-niord-base-bump-app)),
  so each base version arrives as a pull request. Without it, push `base-bump/<version>` as before
  ([Without the app](docs/responsibilities-and-upgrades.md#without-the-app-push-base-bumpversion)).
- `.github/renovate.json` and its `CODEOWNERS` line are inert where no Renovate is installed:
  delete them, or keep them.
- The pack refresh and the kept ticks start at the bump after the copied `base-bump.yml` is on
  `main`.
- `.github/dependabot.yml` may take the template's new comment; its settings are unchanged.

**The base product's bootstrap carries all 45 categories.** `be/niord/bootstrap/categories.json`
adds, after the 13 it had, the 32 categories under `Buoy`, `Beacon`, `Obstruction`,
`Drifting object` and `Radio navigation`, in English, with production's sort orders, AtoN filters
and scripts, and without `messageId` or domain links; `Radio navigation`/`DGPS` comes without its
scripts and template parameter (`tmpl/dgps.ftl`, `tmpl/dgps.js` and the `dgps` type are not the
base product's). Only a fresh database gets them; no deployment's rows change. No export is added
or removed. Why: the categories are base and universal (the owner's ruling of 2026-11-04).

What a country must do: nothing at its bump; a country that wants its own names for the 32
restates them in its archive as it does for the 13.

What a country should know: until then a fresh database shows the 32 in English, offers them in
every domain, and `DGPS` has no template. An entry names its base category by the exact English
name, nested under its parents' entries, and adds its languages, its template parameters' names by
`paramId`, its domain links and, for `DGPS`, the scripts and the `dgps` parameter
(`.niord/kit/docs/country-api.md`, § Bootstrap and release folders). The bootstrap ITs that count
the base product's categories from its archive count 45 after the bump.

**The Maven wrapper pins its distribution's checksum.** `be/.mvn/wrapper/maven-wrapper.properties`, the file
the kit carries for `init` (`backend/maven-wrapper.properties`), gains `distributionSha256Sum` for
`apache-maven-3.9.16-bin.zip`, so the wrapper refuses a download that does not match it; the value was taken
from the zip and checked against ASF's published sha512 and Central's sha1. A new country gets it from `init`.
Why: supply-chain hygiene, the tracker's F6. What a country must do: nothing at its bump; an existing country may
copy the line into its own wrapper properties, and moves it together with `distributionUrl` when it moves the
Maven version.

**The Base bump comment names every other template the kit changed, in one line.** The upgrade
report's data has the new key `otherTemplatesChanged`, after `templatesUnavailable`: every file of
`templates/` other than the eight the report tracks and the stub, by its path from `templates/` (the
`be/` folder included), that differs from the previous kit's or that one of the two kits lacks,
sorted, or `null` when the previous kit is not at hand (the reason is `templatesUnavailable`). The
report names them in one line under To look at, and the Base bump workflow
(`templates/base-bump.yml`) names them in one line above `## Human actions`, with no box and no
marker, so `niord/upgrade-actions` does not count it. No export is added or removed. Why: at the
first app bump, `CODEOWNERS`, `dependabot.yml` and the `be/` folder changed and the comment named
none of them.

What a country must do: nothing at its bump.

**Three defaults of a fresh database follow the owner's rulings of 2026-11-04 (decision 146).** The base
archive's Twitter promulgation type starts inactive (`be/niord/bootstrap/promulgation-types.json`); the PDF
encryption password's code default is empty, the known string is gone (`FmTemplateService`, the settings
catalog); and the base settings file's `mode` is `test`, so a fresh base-only database shows the TEST ribbon
until a production deployment sets `mode=production` by environment, a system property or environment
variable named like the setting, which `SettingsService` honours ahead of the stored row; on the base
chart that is `-Dmode=production` in the value `app.javaOpts`, which the chart passes as
`JAVA_TOOL_OPTIONS` (the chart has no value of its own for the mode). Only fresh
databases are affected; no deployment's rows change. Why: a forgotten setting shows a ribbon rather than a
test site passing as production, and no secret lives in the code. What a country must do: nothing at its
bump. A country's own settings file replaces the base file whole, so its `mode` is its own: the owner ruled
`test` for DK and GL too, in their own repositories, with `mode=production` in their production values; a
country whose archive restates `twitter` with `active: true` keeps Twitter active until it changes that.

**The public site's image runs as an unprivileged user and listens on 8080.**
`ui-apps/info/Dockerfile` creates the user `nginx` with uid and gid 101 before it installs the nginx
package, runs as that user (`USER 101:101`) and exposes port 8080 instead of 80.
`nginx/entrypoint.sh` renders the server block into `/tmp/niord-http.d`, which `/etc/nginx/http.d`
links to, `nginx/default.conf.template` listens on 8080 and keeps nginx's temporary files under
`/tmp`, and `/etc/nginx/conf.d/niord-pid.conf`, written by the Dockerfile, puts the pid file there
too. The container test (`fe/scripts/info-container-test.mjs`, which a country's `image:info` runs
before its image may be pushed) runs the image as its own user, with every capability dropped, no
privilege escalation and a read-only root filesystem, and checks that the image declares uid 101.
The image carries the same five nginx files; no export is added or removed. Why: the base Helm chart
now deploys the site, and the clusters hold every pod to the restricted Pod Security standard, which
refuses a root user and so a port below 1024.

What a country must do: nothing in its repository; its `image.yml` names neither the port nor the
user, and the bump brings the new files. Whoever runs the image outside the chart publishes port
8080 instead of 80 (`-p <host port>:8080`), and an image built on an older base keeps port 80 and
root: the chart's `info` component needs an image built on this version or later.

**The help paths are code; `AppConfig.helpUrls` is deprecated and ignored.** The map from a help
section id to its page and anchor in the user guides is `HELP_URLS`, read from
`ui-apps/admin/src/app/core/main-shell/help/help-urls.json`, the same in every build; the property
`helpUrls` of `AppConfig` (exported by `@niord/admin/api`) is now optional and never read, its
comment says so, and the web-settings overlay still leaves it alone. `documentationUrl` is unchanged:
the environment file's value, which the backend's `documentationUrl` web setting overrides at
runtime; without one no help link shows. The base product's environment files and the example
country's no longer carry the block. No export is added or removed. Why: the map is a contract
between the code and the guides, not deployment configuration, and as configuration it gave a
production build help on five screens only, the example country's on two.

What a country must do: nothing; its admin app now shows every help link the base product has,
whatever its environment files carry. Delete the `helpUrls` block from its
`admin/environments/environment*.ts` when convenient: a block that stays compiles and changes
nothing, and an entry of its own there, a remapped or an added section, no longer has any effect.

When it goes: the property is removed in a later version, not before one both DK and GL have taken
without the block.

**Keycloak 26.8.2, and `niord-web` carries the client scope `kc-admin-api`.** The compose file of
`org.niord:niord-local-run` and the Helm chart run Keycloak 26.8.2 instead of 26.6.3, and the chart's
proxy runs nginx 1.30.6 instead of 1.27. The local realm, `keycloak/niord-realm-local.json`, and the
chart's realm define the client scope `kc-admin-api` and give it to `niord-web` as a default scope. No
export is added or removed. Why: 26.8.2 is a security release, and from it the Admin REST API accepts
only a token with that scope; the backend's admin calls (a domain's client, the users) carry the
signed-in user's `niord-web` token.

What a country must do: nothing in its repository. A developer whose `niord-keycloak-data` volume was
imported by an older image gets 401 from those calls once `niord-keycloak` is recreated on 26.8.2
(launchers never recreate it): remove the volume, or add the scope by hand (`be/README.md` of the base
product, _Run locally_). A realm on a shared Keycloak that upgrades to 26.8.2 needs the same hand step
for `niord-web`.

## 4.0.111: the kit as its own package, the Base bump, the guides and the launcher

**The kit is its own package, `org.niord:niord-country-kit`; the set-up stub changed.** The kit is
no longer inside the source zip: it is the package `org.niord:niord-country-kit` of the same
version, whose line `<sha256>  niord-country-kit-<version>.zip` the release notes give beside the
source zip's, and a country has it at `.niord/kit/` at its repository root. The lock pins it:
`fe/niord-pins.lock.json` holds `"kit": { "sha256": "<64 hex digits>" }` between `base` and
`packs`. The new stub, `.niord/kit/templates/setup.mjs`, refuses a lock without that pin, naming
the release notes' line; it writes the root's `.niord/.gitignore` holding `*`, fetches the kit
package into `.niord/` with Maven, checks it against `kit.sha256`, unpacks it into `.niord/kit/`
(its marker `.niord/kit.sha256`, its zip kept for the next upgrade report) and checks that its
`kit.json` names the base version, then fetches the source zip as before. `init` runs from the kit
package, `node .niord/kit/init.mjs <cc> --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>`, checks
both zips and pins both; the generated scripts and lint configuration, `pack.yml` and the
launcher's message name the kit as `../.niord/kit/` from `fe/`. No export is added or removed. Why:
the kit is a package of its own, outside `fe/`, so a country takes the kit and the base product as
two packages of one version and pins each by its hash.

What a country on 4.0.97 saw at the bump: its old stub fetched the new version's source zip, which
carried a stand-in for the kit's set-up, and set-up stopped with
`setup: error: stub: fe/setup.mjs is not the kit's own; the kit moved to its own package: in fe/, copy .niord/base/country-kit/templates/setup.mjs over setup.mjs, and pin the kit in niord-pins.lock.json as kit.sha256, from the release notes' niord-country-kit line`
and an upgrade report whose To do was that copy and that pin. A report was written only when a
set-up record existed: a fresh clone ran set-up once at 4.0.97 first (as DK did) or its second
set-up with `--previous 4.0.97` (as GL did). The source zip of the version after 4.0.111 carries
neither the stand-in nor the stub. What a country must do at its bump:
in `fe/`, copy `.niord/base/country-kit/templates/setup.mjs` (or, once set-up has fetched the kit,
`../.niord/kit/templates/setup.mjs`) over `setup.mjs` byte for byte; add
`"kit": { "sha256": "<sha256>" }` to `fe/niord-pins.lock.json` with the hash of the release notes'
line `<sha256>  niord-country-kit-<version>.zip`; run `node setup.mjs` again; then copy
`.niord/kit/templates/pack.yml` over `.github/workflows/pack.yml` and
`.niord/kit/templates/start-local.sh` over `start-local.sh`. Nothing for `.niord/.gitignore`: set-up
writes it before it fetches, and `init` writes it into a new country. Optional: `init` now also
writes the line-ending rule as `.gitattributes` at the repository root (the same text as
`fe/.gitattributes`), so `start-local.sh` and the workflows keep LF on a Windows checkout; a country
without a root `.gitattributes` may copy `fe/.gitattributes` there.

**Renovate opens the base bump; the Base bump workflow re-pins.** A new template,
`templates/base-bump.yml`, is the workflow `Base bump`: on a pull request from a `renovate/` or
`base-bump/` branch of the country's repository that moves the `<parent>` of `be/pom.xml`, it
compares the two zips the registry serves with the two lines of release `v<version>`'s notes in the
base product's repository, and only when they agree pins `fe/niord-pins.lock.json`, runs set-up,
copies the stub and the launcher, moves the foreign pack pins the upgrade report asks for, commits
as `github-actions[bot]`, dispatches `ci.yml` and posts one comment with the upgrade report and
`## Human actions` as boxes; the status `niord/upgrade-actions` stays red while a box is unticked.
`init` writes it as `.github/workflows/base-bump.yml` and prints a notice when `ci.yml` lacks
`workflow_dispatch:`; `templates/CODEOWNERS` gains `/.github/workflows/base-bump.yml` and
`/.github/renovate.json`. `templates/renovate.json` gains the weekly window (early on Monday,
Europe/Copenhagen time), `updateNotScheduled: false`, `rebaseWhen: conflicted`, the Dependency
Dashboard without approval, `gitIgnoredAuthors` (the workflow's bot), `prBodyNotes` and `hostRules`
for GitHub Packages. Set-up writes `.niord/niord-upgrade-report.json` beside the Markdown report,
takes `--previous <version>` (the upgrade report without a set-up record), names the templates
changed since the previous kit under To look at, and compares `Unreleased` per entry. No export is
added or removed. Why: green means no task: every action set-up cannot check is a box on the bump
pull request, and a green bump needs only the review, the merge and the deploy.

What a country must do at its bump: copy `.niord/kit/templates/renovate.json` over
`.github/renovate.json`.

What a country must do at its bump: copy `.niord/kit/templates/base-bump.yml` to
`.github/workflows/base-bump.yml`.

What a country must do at its bump: add `/.github/workflows/base-bump.yml` and
`/.github/renovate.json` to `.github/CODEOWNERS`, as `.niord/kit/templates/CODEOWNERS` has them.

What a country must do at its bump: keep, or add, `workflow_dispatch:` on a line of its own in the
`on:` of `.github/workflows/ci.yml`: the Base bump workflow dispatches CI on the commit it pushes.

What a country must do at its bump: merge the section "The base bump" of
`.niord/kit/templates/AGENTS.md` into `AGENTS.md`.

What a country should know: a tick in the comment and Run workflow (`issue_comment` and
`workflow_dispatch` runs) use the default branch's copy of the workflow, so nothing of the recount
works until the bump that brings it is merged; the first Renovate run opens a Dependency Dashboard
issue; the repository's Actions permissions need no change; and the workflow is inert until a pull
request from a `renovate/` or `base-bump/` branch exists. While the base product's repository is
private, the Base bump workflow cannot read the release notes with your repository's own token, so
it pins nothing: its comment's one item is the pin by hand from the notes, and once you have pushed
it, running the workflow on the pull request (Actions, Base bump, Run workflow, `pr <n>`) adds the
upgrade report and the checklist. When that repository is public, the workflow's own token reads
the notes and the pin is automatic. An optional repository secret, `NIORD_NG_RELEASE_READ`, a token
that can read the base product's repository's releases, lets the workflow read the notes before
then; nothing asks for it.

**The guides for people and operators, and the agent contract.** Two new guides:
`docs/country-guide.md`, the steps of a new country from an empty repository to a local run, with
the recipes for a country and a language, a definition of done and a checklist before go-live; and
`docs/operators-guide.md`, what a fresh deployment holds and what an operator sets up in it, from
the administration app and Keycloak. `templates/AGENTS.md` is now the agent contract, not a
placeholder: the layout, the commands, each check's failure and its fix, what an agent never does,
the base bump and the backend rules. `init` writes it byte for byte as `AGENTS.md` at the root of a
new country, with a one-line `CLAUDE.md` that imports it
([Starting a country](README.md#starting-a-country--init)). No export is added or
removed. Why: a new country's maintainer, an agent or an operator must be able to do their part
from the guides alone.

What a country must do at its bump: `init` keeps an existing agent contract, so a country with its
own merges the template's sections (`.niord/kit/templates/AGENTS.md`) into its `AGENTS.md` by hand,
keeps its own rules in a section below them, and drops any line that tells an agent to follow
another repository's backend rules: the template states them itself. Nothing else.

**The agent contract is `AGENTS.md`.** The kit's agent template is `templates/AGENTS.md`, a name any
AI agent finds; it was `templates/CLAUDE.md`. `init` writes it byte for byte as `AGENTS.md` at the
root of a new country, with a `CLAUDE.md` holding the one line `@AGENTS.md`, which loads it in
Claude Code: Claude Code reads an `AGENTS.md` by itself only where no `CLAUDE.md` is in the folder
or above it. When `CLAUDE.md` exists, `init` keeps it and prints that it must import `AGENTS.md`. No
export is added or removed. Why: the agent contract has an agent-neutral name.

What a country must do at its bump: rename its `CLAUDE.md` to `AGENTS.md`, commit a `CLAUDE.md`
holding the one line `@AGENTS.md`, and merge what changed in `.niord/kit/templates/AGENTS.md` into
its `AGENTS.md`, its own rules in a section below the template's.

**The backend contract comes with the kit.** `docs/country-api.md`, the backend contract, is in the
kit package `org.niord:niord-country-kit` of the same version, which a country unpacks at
`.niord/kit/`: read it at `.niord/kit/docs/country-api.md`. `org.niord:niord-local-run` no longer
carries it. No export is added or removed. Why: the contract has one published home, beside the
guides that cite it.

What a country must do: read the contract at `.niord/kit/docs/country-api.md`, and drop any fetch
of it from `niord-local-run`; the country guide's "The base product's backend files" fetches the
niord-base jar's files only.

**The country launcher, `templates/start-local.sh`.** A new template: one bash launcher, the same in
every country, that starts a country's whole local stack from its repository root — the shared
containers, the country's backend, the administration app and the public site, with the country's
own bootstrap on `niord_<cc>` or, with `--sample`, the base product's sample on `niord_sample_<cc>`
([Running it locally](README.md#running-it-locally--start-localsh)). It reads the country's files
(`fe/niord-country.json`, and `be/pom.xml`'s `<parent>` through the stub's `parentVersion` and its own
`<artifactId>`), fetches `org.niord:niord-local-run` and `org.niord:niord-bootstrap` of the pinned
version into `.local-run/<version>/`, runs set-up before the backend, and refuses an existing database
it did not make (it marks each one it creates). `init` writes it into a new country, executable, and
adds `/.local-run/` to the root `.gitignore`, the one existing file it changes. No export is added or
removed. Why: a country developer needs one command for a local run (fe-country-split-plan §6 P8,
the owner's ruling of 2026-10-23).

What a country must do at its bump: copy `.niord/kit/templates/start-local.sh` to its
repository root byte for byte (its CI may compare the two after set-up), and copy it again at every
bump; add `/.local-run/` to the root `.gitignore`; and commit a `start-local.json`,
`{ "database": "<name>" }`, when `niord_<cc>` already holds local data the launcher did not make. The
launcher needs a base product version that publishes `org.niord:niord-local-run`, this one on.

**A country code is two letters.** `init`, the manifest schema's `country`, the launcher
`templates/start-local.sh` and the secret scan's allow-list entries take a country's ISO 3166-1
alpha-2 code only, `^[a-z]{2}$`, and refuse a three-letter one, naming the rule ("its ISO 3166-1
alpha-2 code, two letters a-z"); the launcher's `--sample` refusal of a three-letter code, which can
no longer be reached, is gone. Language codes keep two or three letters. No export is added or
removed. Why: a country's code is its ISO 3166-1 alpha-2 code.

What a country must do: nothing when its code is two letters; the launcher is copied again at the
bump, as above.

**The public-site image's base can come from a mirror.** `ui-apps/info/Dockerfile` takes its base
from the build argument `ALPINE_IMAGE` (default `alpine:3.22`, as before), and `image:info` passes
`ALPINE_IMAGE` from its environment to `docker build` as `--build-arg ALPINE_IMAGE=<value>` (when
it is unset, no build argument is passed and the default stands) and logs it when it is set. No
export is added or removed. Why: a country whose runners meet Docker Hub's anonymous pull limit can
build from a mirror of the same image that it has access to.

What a country must do: nothing; with `ALPINE_IMAGE` unset its image builds from `alpine:3.22` as
before. A country that wants its mirror sets `ALPINE_IMAGE` to it, such as
`registry.example.org/library/alpine:3.22`, in its frontend job's `env`.

**Every run block of the workflow templates starts strict.** `set -euo pipefail` is the first line
of every run block of `templates/image-frontend-job.yml`, `templates/image.yml` and
`templates/pack.yml`, the one-line steps included, which are `run: |` blocks now; the backend job's
were strict from the start. The image tools' `split-jar.sh` and `publish-images.sh` give the
jar-set hash the same on Git Bash as on Linux: their `sha256` helper turns sha256sum's binary-mode
marker ` *` into the two spaces. No export is added or removed. Why: a one-line step ran without
`-u` and `pipefail`, and a split on Windows named the dependency set by another hash than CI's.

What a country must do at its bump: copy `.niord/kit/templates/pack.yml` over
`.github/workflows/pack.yml` (or add `set -euo pipefail` as the first line of each of its run
blocks), and in `image.yml` start every run block with `set -euo pipefail` where one does not yet.
Nothing for the hash fix: it is in the base version's image tools.

**The image template's backend job; set-up runs before Maven.** `templates/image-backend-job.yml`
is new: the backend job, which runs set-up before Maven, then builds the jar, runs the override
check over it, splits it and hands it on as `backend-dist.tar` with its SHA-256. `init` writes it
into a new `image.yml` and prints it when `image.yml` exists. In `templates/image.yml` the push job
`needs: [frontend, backend]`, downloads `backend-dist` and verifies `backend-dist.tar.sha256`
beside the other two, and runs the new step `Check the version` before it pushes; the `env` gains
`MODULE` (empty until the country sets it), `MVN_FLAGS` and `MAVEN_OPTS`. The README's
[The backend build](README.md#the-backend-build) gives the pom's copies of a staged pack and the
Maven check. No export is added or removed. Why: no backend job ran set-up, so
`fe/.niord/packs/<lang>/backend/` did not exist when Maven ran, and a pinned pack's backend half
reached no jar.

What a country must do at its bump:

1. Add the backend job to `image.yml` by hand, as `init` prints it, and set `MODULE` to its
   deployable's `artifactId`. Where `image.yml` already has a backend job, add `setup-node` and
   `Set up the workspace` (in `fe/`, the workflow token bound) before `Build the jar`. Either way,
   take the push job's changes from `.niord/kit/templates/image.yml`: both jobs as its
   needs, the `backend-dist` download and its `sha256sum -c`, and `Check the version`.
2. Add `setup-node` and `Set up the workspace` before `./test-it` in the backend test job of its
   `ci.yml`.
3. Re-point the pom's pack copies from `fe/packs/<lang>/` to
   `fe/.niord/packs/<lang>/backend` and `fe/.niord/packs/<lang>/img/flags` for every staged pack,
   adding a pinned pack's copies where the pom has none.
4. Add the Maven check, `maven-antrun-plugin`'s `require-staged-packs` at `validate`, over the paths
   the copies read.
5. Run `node fe/setup.mjs` before any local Maven build.

**The SECOM page's reconcile toast names the failed count.** `admin.secom.toast.reconciled` gains
the placeholder `{{failed}}`: its English is "Reconciliation: {{created}} created, {{updated}}
updated, {{cancelled}} cancelled, {{unchanged}} unchanged, {{failed}} failed", the count of eligible
messages whose S-124 GML could not be generated, which the next sweep tries again. No export is
added or removed. Why: a sysadmin saw "0 created" while messages failed to generate, and nothing on
the page said so.

What a country must do: its home pack's value gains `{{failed}}` at its next bump, since the pack
check fails a text whose placeholders are not English's, and moves the key from its
`status/fingerprints.json` to its `status/unaccepted.json` until it is accepted (DK's `da`): the pack
check fails a key that is in both. A pinned pack that lacks it (GL's pin of
`niord-lang-da:2`) lists the key in `missing-keys-accepted.json`, staging then ships the English
text, until a version of that pack carries it.

**The administration app's service worker caches the `media/` files.** A new asset group, `media`
(installed lazily, the files `/media/**`), holds the files the build hashes into `media/` for the
style sheets, so a face a country vendors (`styles/fonts.<cc>.css`) is cached at its first load and
shows offline. Why: the face fell back to the system face offline (fe-country-split-plan §4.7). What
a country must do: nothing.

**The public site's brandable images have their pixel sizes.** The public site's files in
`branding.schema.json` gain a `width` and a `height` in px: `branding/favicon.png` 32×32,
`branding/apple-touch-icon.png` 152×152, and every `branding/logo-gray.png` and
`branding/logo-gray-<lang>.png` 44 px high (twice the 22 px the footer draws; its width is free).
Set-up's branding check (`lib/branding.mjs`, which exports the reader as `pngSize`) reads the size
from the PNG's header chunk and fails a file of another size, e.g.
`info/assets/branding/logo-gray.png: is 120×24 px; branding.schema.json gives it a height of 44 px`,
and a PNG whose first chunk is not its header: `… is a PNG without its header chunk (IHDR) first, so
its size cannot be read`. A file that fails is not staged, so a `FOOTER_LOGO` naming it fails as
well. Every shipped footer logo is held to the height, not only the one `FOOTER_LOGO` names. Why: P7
deleted the base product's specs that checked these sizes on the copies of the countries' files it
carried, so nothing checked a country's own files (fe-country-split-plan D23).

What a country must do: a country whose `info/assets/branding/` icon or footer logo is another size
redraws it at that size and runs `node setup.mjs`.

**The public site's copyright line writes no year, its accessibility link no address.** In
`branding.schema.json`, `FOOTER_COPYRIGHT` gains `"forbids": ["year"]` and `MAP_ACCESSIBILITY`
`"forbids": ["address"]`, two rules `lib/branding.mjs` names (`FORBIDDEN`). A `FOOTER_COPYRIGHT`
value with four digits standing alone, once its character references and `{{ }}` placeholders are
removed, fails: `info/assets/branding/<lang>.json FOOTER_COPYRIGHT has four digits standing alone,
2026, written in: a year goes to {{year}}, which the product fills with the deployment's own`. A
`MAP_ACCESSIBILITY` value with an
`http:`, `https:`, `mailto:` or `www.` address or an e-mail address, as written or with its
character references decoded, fails (`… has the address <address> written in: the product fills
{{url}} from info.config.ts`), and so does one that gives `{{url}}` other than as its link's href,
`href="{{url}}"` with either quote (`… gives {{url}} outside an href: the product fills it with the
address of the accessibility statement, which the link opens`). Why: P7 deleted the base product's
specs that checked these values on the copies of the countries' files it carried, so nothing checked
a country's own files (fe-country-split-plan D23).

What a country must do: a country whose public-site copyright line has four digits standing alone,
or whose accessibility link writes an address, removes them and keeps `{{year}}`, or `{{url}}` as the
link's href, and runs `node setup.mjs`.

**The cookie statements name every name the public site stores.** `@niord/info/api` exports
`PREFERENCE_KEYS` and `PREFERENCE_PREFIX`, for a country component that tells a reader what the site
stores; both are read from `ui-apps/info/src/app/core/services/preference-keys.json`, the public
site's one list, which the kit reads too (`lib/branding.mjs`, `PREFERENCES` and `readPreferences`).
The public site's `cookies` document in `branding.schema.json` gains `"namesPreferences": true`, and
set-up fails each shipped `info/assets/legal/cookies.<lang>.html` that does not name every prefixed
key, today `niord-info.language`, `niord-info.rootAreaId`, `niord-info.NW` and `niord-info.NM`: one
error per statement listing the names it lacks, e.g. `info/assets/legal/cookies.<lang>.html: does
not name niord-info.NM, which the public site stores in a reader's browser
(ui-apps/info/src/app/core/services/preference-keys.json)`. A name counts when it is written as it
is and not followed by a letter, a digit or `_` (`niord-info.NMX` does not name `niord-info.NM`). The
administration app's cookie statements are not held to them. Why: P7 deleted the base product's spec
that checked the statements on the copies of the countries' files it carried, so nothing checked a
country's own statements, though each is a privacy statement (fe-country-split-plan D23).

What a country must do: a country whose cookie statement lacks one of the names adds it and runs
`node setup.mjs`; a name added to the list later comes with its own entry here.

**The public site's built page is in its default language.** The checks on every country build
(`check-build.mjs`, check 10; `lib/build-checks.mjs` exports it as `indexProblems`) hold
`dist/niord-info/browser/index.html` to the public site's default language in the manifest: the html
tag's `lang` is that language, compared without case, and its one title, its character references
decoded and white space folded, is the `SITE_TITLE` of the built
`dist/niord-info/browser/assets/i18n/<default language>.json`, after packs and text overrides. Each
finding is prefixed `index (dist/niord-info/browser): `, e.g. `index.html has lang "en", not <lang>,
the public site's default language: the country's info/app/index.html (the index seam) gives the
page's lang and title in its default language`; a title that differs, a missing page or
`SITE_TITLE` and a page with more or fewer than one title fail too. A passing build prints
`dist/niord-info/browser/index.html: lang <lang>, titled as SITE_TITLE`. Why: P7 deleted the base
product's specs that checked the page on the copies of the countries' files it carried, so nothing
checked a country's own page (fe-country-split-plan D23).

What a country must do: a country whose public site's default language is not English, and that
carries no `info/app/index.html` (the index seam), adds one with that language's `lang` and its
`SITE_TITLE` as the title. A country whose page has another `lang` or title corrects it.

**The country URL allow-list scopes an entry to one application.** An entry of
`fe/build-urls-allowed.json` takes an optional `app`, `"admin"` or `"info"`
(`compileCountryUrlAllowList` in `scripts/build-urls-check.mjs`, which exports the two as
`APP_BUILDS`; `checkAbsoluteUrls` takes the application as its fourth argument). A scoped entry
admits its URL only in that application's output; another value fails,
`fe/build-urls-allowed.json: allow[0]: app is "backend", not "admin" or "info"`, and the entry
admits nothing. A URL that only an entry scoped to the other application would admit fails as
before, the line naming that entry: `… (fe/build-urls-allowed.json allow[0] admits it in the public
site's build only)`. Every build prints a scoped entry with its scope, `urls: the country's
allow-list admits <url> in the public site's build only — <reason>`, and warns about it when it
admits no URL of its own application's build (`… admits no URL of the public site's build, so it
permits nothing: remove it`). An entry without `app` admits its URL in either application, is
printed and warned about as before, and the base product's own list still refuses `app` as an
unknown field. Why: a `files` glob such as `main-*.js` also matched the other application's main
bundle, so an address of one application was admitted in both.

What a country must do: nothing. A country whose entries each name an address of one application
only may add `"app"` to them at its next bump.

**The styles budget allows for a country's face.** When `styles/fonts.<cc>.css` exists, the
generated `angular.json` raises each application's `styles` bundle budget, its `maximumWarning` and
`maximumError`, by the sheet's size in bytes, written in kB as Angular counts them (1000 bytes), in
the options and in any configuration with budgets of its own (`lib/workspace.mjs`, which exports
the rule as `raiseStylesBudgets`). A budget with a baseline or a size that is not absolute, every
other budget, and all of them when there is no sheet or an empty one, stay the base product's. Why:
a vendored face put the public site's styles past the base product's budget, so every build warned
and a real regression would have hidden behind the warning.

What a country must do: nothing.

**A country's e2e specs are linted.** `npm run lint` lints `e2e/**/*.ts` in `niord-app`'s lint
target, and a country with specs and no administration code now lints `niord-app`. The specs are
parsed in their own program, `.niord/tsconfig.e2e.json`, which now extends the generated
`tsconfig.json`, takes no ambient types and includes `../e2e/**/*.ts` (for TypeScript and the lint
it still maps only `@niord/admin/e2e`; Playwright's loader also follows the root's references, so
`npm run e2e` resolves the base product's other aliases in a spec too, and the lint is what refuses
them), and the generated `eslint.config.js` gives them that project in place of the project
service. The base product's rules apply to them, a new rule `niord-country/e2e-imports` allows
only `@niord/admin/e2e` and `@playwright/test`, each exactly, `node:` built-ins and files of
`e2e/` (`"lodash" is not for an e2e spec. A spec imports @niord/admin/e2e (the login fixtures),
@playwright/test, a node: built-in or a file of e2e/ (country-kit/README.md, End-to-end specs)`),
and no inline configuration applies: an `eslint-disable` disables nothing and is itself reported.
Why: the country lint covered `admin/` and `info/` only, so review alone held a spec to the login
fixtures.

What a country must do: a country whose spec imports anything else (a bare built-in such as `fs`
is written `node:fs`), carries an `eslint-disable` comment, or breaks another of the base product's
rules fixes it at its next bump and runs `npm run lint`.

**`npm audit signatures` is retried once after a Windows process crash.** When it exits 3221226505
(0xC0000409) with no report, `audit.mjs` prints `npm audit signatures: exit 3221226505 with no
report, a Windows process crash; running it once more with a fresh npm cache` and runs it once more
with an npm cache of its own, a new folder under the system's temporary folder given as
`npm_config_cache` and removed afterwards; a second crash fails as before, the finding adding
`, after one retry with a fresh npm cache`. Another exit without a report, and a crash with a
report, are not retried. Why: the command crashed so, once, while other runs shared the npm cache,
and passed when run again.

What a country must do: nothing.

## 4.0.97: the base product lets go

First published in 4.0.97. DK and GL took its actions at their bumps onto 4.0.97 (niord-dk #10,
niord-gl #9, 2026-10-25).

**`image:info`'s labels leave the command line.** The image carries the same two labels,
`org.niord.build-report` and `org.opencontainers.image.version`, with the same values. `image:info`
writes them as a LABEL instruction into its build context's copy of the Dockerfile, in lines of at
most 16 KiB that Docker joins, instead of passing them as `--label` arguments, and after the build
reads them back with `docker inspect`: a label that differs from the report fails the run, naming
the label. A Docker or Node call that cannot start is named with its error's code and message
(e.g. `ENAMETOOLONG`), where only "docker build of … failed" or "(exit none)" was printed. Why: a
long build report made the command line too long (`ENAMETOOLONG` on Windows past 32,767
characters), and Docker refuses a Dockerfile line of 64 KiB.

What a country must do: nothing.

**`templates/image.yml`'s comments** name niord-dk's `image.yml` as the model for the
administration bundle's image step. What a country must do: nothing.

**The published test scripts and specs name no country.** `info-container-test.mjs`'s default
`--legal-doc` is `disclaimer.en.html` (was `disclaimer.da.html`), its usage lines show the base
build's image, and its fixtures use the series ids `xx-nw`, `xx-nw-local` and `xx-nm` (were
`dma-nw`, `dma-nw-local` and `dma-nm`). `info-layout-check.mjs`'s stub backend answers every
root-area MRN with the fixture's fictitious roots, in the order asked: DK's and GL's MRNs are no
longer answered as their own. The specs the zip carries use `example.org` hosts, the locales `da`
and `kl` without a region, and the zones `America/Halifax` and `Europe/Lisbon`, keeping Greenland's
zone only where a case needs its rule. Why: the base product names no country
(fe-country-split-plan §4.8, §6 P7). What a country must do: nothing; `image:info` always passes
`--legal-doc` or `--no-legal-doc`.

**Audio promulgation is the base product's.** The administration app registers a viewer and an
editor for the `audio` service itself, beside NAVTEX, Twitter and SafetyNET, with the English texts
`editor.promulgation.audio.*`. No export is added or removed. Why: the backend's audio service is
the base product's and every backend registers it.

What a country must do: delete its copies of the two components and their registration in
`admin/app/country.providers.ts`, or the whole file when nothing else is in it, since set-up swaps
only a seam whose country file exists and the base product's empty default then applies. A copy left
in place silently replaces the base product's: the registries are Maps, and the last registration
for a `serviceId` wins.

The example country also registers an editor for its own service `xx-bulletin`
(`admin/app/bulletin/bulletin-edit.ts`), a model for a country's own.

**`niord-web-admin` carries the classifier `en` only.** From this version
`org.niord.web:niord-web-admin` holds one zip, the generic build of the administration app under the
classifier `en`; the `dk` and `gl` zips are no longer published (versions before it keep them). Why:
the base product builds no country (fe-country-split-plan §6 P7). What a country must do: nothing; a
country builds its administration bundle from `niord-fe-src`, and neither DK's nor GL's `image.yml`
reads `niord-web-admin`.

**The e2e suite the zip carries drops three specs.** `ui-apps/admin/e2e/` no longer has
`language-switch.spec.ts`, `admin-model-language.spec.ts` or `gl-model-languages.spec.ts`, and
`app-breadth.spec.ts` no longer tests the footer's legal dialogs; the fixtures are unchanged. Why:
they tested DK's and GL's copies, which their repositories carry. What a country must do: nothing;
its own `e2e/` keeps its copies.

**The base product's country configurations and its frozen packs are gone.** The zip's
`angular.json` no longer has niord-app's or niord-info's DK and GL build and serve configurations,
its `package.json` no longer has their start and build scripts, the tree no longer holds a Danish
pack frozen at P5 or the Kalaallisut pack, and `pack.mjs check` loses `--frozen`. Why: the base
product builds no country (fe-country-split-plan §6 P7). What a country must do: nothing; the
generator copies only the base product's production, development, local and test configurations
(`lib/workspace.mjs` CONFIGURATIONS), its packs come from its home or its pins, and `check` runs in
the base product tree only.

**The include list and the generated `angular.json` drop the in-tree countries' paths.**
`zip-include.json` no longer excludes `country-specific/{dk,gl}/`, `public/assets/{dk,gl}/` or
`styles/*.{dk,gl}.css`, which the base product's tree no longer holds (a test in `test:guards`
keeps it so), and the public-folder asset entries of the generated `angular.json` ignore
`assets/default/**` alone, no longer the two country folders `assets/{dk,gl}/**`. Why: the base
product builds no country (fe-country-split-plan §6 P7). What a country must do: nothing; set-up
regenerates `angular.json`.

**A refused batch restart shows the server's reason.** The batch page's Restart, answered 409 with
a problem detail (a bootstrap job is never restarted), raises the new English key
`admin.batch.toast.restartRefused`, "The batch execution was not restarted: {{detail}}"; any other
failure keeps `admin.batch.toast.actionError`. The English of
`admin.publications.series.hint.messageReferenceFormat` changed: its example reads
"NtM 27/2026". Why: the sysadmin never saw why the restart was refused, and the base product's
English names no country (fe-country-split-plan §4.8). What a country must do: its home pack gives the new key a
value (DK's `da`; GL's `kl` has no administration bundle, so GL's route is the pinned-pack clause
that follows), listed in the pack's `status/unaccepted.json` until accepted; a pinned
pack that lacks it (GL's pin of `niord-lang-da:1`) lists it in `missing-keys-accepted.json` until a
version of that pack carries it. The changed hint is a warning only: the home checks its
translation and accepts it (`pack accept`), or lists the key in its `status/unaccepted.json` with
the stale fingerprint removed when its texts are accepted later (DK).

**`test:guards` refuses two more country shapes.** Besides a pack but `en`, a country tree, a
country asset folder and a country style sheet, the base product's tree check refuses
`ui-apps/<app>/i18n-status/` (a country's pack status) and `styles/fonts/<face>/` (a vendored font
face). Why: P7 removed the first from the base product's tree, a vendored face is a country's
(fe-country-split-plan §4.8), and the include list ships `styles/**`. What a country must do: nothing; the check runs in the base product's tree only.

## 4.0.87: the language packs on GHCR

First published in 4.0.87. DK and GL took its actions at their bumps onto 4.0.87
(fe-country-split-plan §6 P5e, P6e); its last paragraph, on GL's order, is history since GL pinned
`da` on 2026-10-24.

**Language packs are published, pinned and withdrawn on GHCR** (fe-country-split-plan §4.5, §4.11
rule 6). Set-up stages every pack the manifest names but English into `.niord/packs/<lang>/`, in
exactly the layout of the pack's canonical archive: a home pack from `packs/<lang>/`; a pinned pack
pulled anonymously from GHCR by the digest of its pin, the pack's withdrawn marker read first and the
archive verified before a byte of it is unpacked (cached in `.niord/oci/`, verified again on reuse);
and a `NIORD_PACK_<LANG>` copy, still the development path and still refused when `CI` is set, which
the generated `build` script and `image:info` refuse as before. A pin the withdrawn marker lists fails
set-up with the home's reason, and the upgrade report lists it under To do. `niord-pins.lock.json`'s
`packs` holds each pin as its full reference, `ghcr.io/<owner>/niord-lang-<lang>:<tag>@sha256:<64 hex>`,
which `node setup.mjs --update-pins [<lang>=<tag>]...` writes: the tag given, else the highest tag the
marker does not list. The build report's `packs.<lang>` gains `reference` for a pinned pack (additive;
its format stays 1). A set-up that fails from staging the packs on removes `.niord/packs/` after
writing the upgrade report, so the folder is only ever one a set-up's checks passed; `.niord/oci/`
keeps only the archives the run used. Why: a country builds from a foreign pack's published digest rather than a local copy, and the
staged path is where its Maven build will read every pack's backend half, home or pinned, once a
later kit version adds a backend job to the templates.

New set-up refusals with it: a file outside the archive's layout in a home pack folder or a
`NIORD_PACK_<LANG>` copy (a `renames.json`, a stray file, a link), named; a `NIORD_PACK_<LANG>` folder
not named for its language; a pinned pack without a pin, a pin for a pack the manifest does not pin,
and a pin under another owner than the manifest's entry names.

**`pack.yml` publishes and withdraws.** `templates/pack.yml` has three jobs: `validate` (set-up with
the pinned catalog, fatal; the pack check against the latest published base product catalog,
information only; the canonical archive with its SHA-256), `publish` (the archive pushed unchanged as
`ghcr.io/<owner>/niord-lang-<lang>:<run>`, the first publish pushing the empty withdrawn marker
before the pack) and `withdraw` (by dispatch, with the tag and a one-line reason). A push to `main`
that changes a pack folder publishes it; a change to a pack's `status/unaccepted.json` alone does
not, since the archive leaves the reviewers' notes out. `pack-releases.md` at the repository
root is the home's record of each publish and withdrawal. `pack.mjs` gains `latest-catalog`,
`validate` and `prune <lang> --base <version>`. Why: a home publishes its packs itself (R13), and a
bad pack can be withdrawn from every consumer.

**The backend half's status.** `status/unaccepted.json` takes a section
`"backend": { "<bundle>": { "unaccepted": [<keys>] } }`, and `status/fingerprints.json` the backend
fingerprints per bundle; `pack accept <lang> --app backend --bundle <bundle> <key>...` accepts a
backend text. A backend key listed twice, listed but missing or empty, or listed and fingerprinted
fails; a key in neither list is one warning per bundle with the count, so a pack's existing backend
texts need no fingerprints seeded. Why: a backend text awaits acceptance as a frontend one does.

**A country's own URL allow-list and branding-unaccepted list.** `fe/build-urls-allowed.json`, in the
base product's list's format with a reason per entry, each pattern one literal `https:` address ending
with `$` (no other regular-expression syntax, a dotted host name, no IP address, port or credentials),
is read by the checks on every country build beside the base product's list; each entry is printed on
every build, and one that admits nothing is a warning. `fe/<app>/branding-unaccepted.json`, in the
base product's format, lists the branding values and legal documents that await acceptance, for the
application's UI languages only; the branding check fails a bad entry and warns while a list is not
empty. Why: a country's own address and its texts awaiting acceptance are the country's, not the base
product's (R1, R5).

**`init`** writes `fe/.gitattributes` (the base product's line-ending rule, text stored LF and the
binary types never converted) and `pack-releases.md`, and no longer `be/bootstrap/releases/README.md`;
that file's text is in the template of `be/bootstrap/README.md`. Why: without the rule a Windows
checkout with `core.autocrlf=true` turns the moved files CRLF and breaks the kit's byte comparisons
and the legal documents' bytes; and the bootstrap archive accepts under `releases/` only files inside
a release's folder, so the releases README failed a country's bootstrap folder test and its bootstrap
zip.

What a country must do: copy `.niord/base/country-kit/templates/pack.yml` over
`.github/workflows/pack.yml` (also where an earlier copy exists: this version's adds the notes filter,
the force-push check and the queue); add `pack-releases.md` at the repository root from
`.niord/base/country-kit/templates/pack-releases.md`; add `fe/.gitattributes` from
`.niord/base/country-kit/templates/gitattributes` where it has none; delete
`be/bootstrap/releases/README.md` if `init` wrote one; move any file outside the archive's layout out
of `fe/packs/<lang>/`; keep a branding-unaccepted list at `fe/<app>/branding-unaccepted.json` with its
UI languages' lists only; pin each foreign pack with `node setup.mjs --update-pins` once its home has
published it and the package is public, comparing a first pin with the home's `pack-releases.md`, and
commit `niord-pins.lock.json`; then run `node setup.mjs`. The set-up stub is unchanged. Not in this
bump: pointing the Maven build at `fe/.niord/packs/<lang>/backend/`; it comes with the kit version
that adds a backend job to the templates, which runs set-up before Maven. Until then a home's pom
copies its pack's `fe/packs/<lang>/backend/` in place, and a consumer keeps the root-level bundles of
a pinned language. DK and GL: the first publish and the order of the pin, in the two paragraphs of
this section that follow.

A home's first publish is its first push to `main` that changes a pack folder. For DK, the bump PR's
merge is the first publish of `niord-lang-da`: it brings `pack.yml` and `fe/packs/da/` to `main` (DK's
`main` has neither; the bump also turns the note's eight backend values into the backend list). Before
that merge the owner checks the
organisation's package setting: that a workflow token may create a package and that a package may be
public. Take the merge's run summary line into `pack-releases.md`; dispatch `publish` only when a
merge touched no pack folder, or when the merge's publish failed on the setting and the owner has
fixed it. The owner then makes `ghcr.io/sifa-dk/niord-lang-da` public once.

GL, in this order: DK bumps, and its merge publishes `niord-lang-da`; the owner makes
`ghcr.io/sifa-dk/niord-lang-da` public; only then GL pins it with `node setup.mjs --update-pins`. Until
then GL's set-up, its CI and its own `pack.yml` validate fail on the missing pin, and
`NIORD_PACK_DA` is the local way round.

## 4.0.81: the first version PR A published

**The country API is introduced.** Country files outside `admin/overrides/` and `info/overrides/` no
longer reach `@niord/admin/internal/*` or `@niord/info/internal/*`, nor anything else of the base
product: neither a relative path out of the country's folders nor `@niord/map/*` or `@niord/html/*`.
The workspace maps `internal/*` only for an application whose files the country overrides, and lint
keeps every other file of it to the API. Why: a country file that imports any base product file
breaks on any base product refactoring; the API names what a country may rely on, and only that.

What a country must do: import through the API, then run `node setup.mjs` and `npm run lint`.

| Was (P3a's interim alias)                                                              | Now                                                                                                         |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `@niord/admin/internal/core/config/app-config.interface`                               | `AppConfig`, `LanguageConfig` from `@niord/admin/api`                                                       |
| `@niord/admin/internal/features/messages/promulgation/promulgation-renderer.token`     | `PROMULGATION_RENDERERS`, `PromulgationRenderer`, `BasePromulgation` from `@niord/admin/api`                |
| `@niord/admin/internal/features/editor/promulgation/promulgation-editor.token`         | `PROMULGATION_EDITORS`, `PromulgationEditor`, `PromulgationEditorContext` from `@niord/admin/api`           |
| `@niord/admin/internal/features/messages/models/message.model`                         | `MessageStatus` from `@niord/admin/api`                                                                     |
| `@niord/admin/internal/shared/slots/component-slots`                                   | `provideSlot`, `COMPONENT_SLOTS`, `SlotName`, `SlotLoader`, `SlotRegistration` from `@niord/admin/api`      |
| `@niord/admin/internal/core/main-shell/country-menu`                                   | `CountryMenuEntry`, `MenuAnchor` from `@niord/admin/api`                                                    |
| `@niord/admin/internal/core/auth/guards/role.guard`                                    | `roleGuard` from `@niord/admin/api`                                                                         |
| `@niord/admin/internal/core/auth/area-roles`                                           | `EDITOR_AREA_ROLES`, `EDITOR_VIEW_ROLES`, `ADMIN_AREA_ROLES`, `SYSADMIN_AREA_ROLES` from `@niord/admin/api` |
| `@niord/admin/internal/core/auth/facades/auth.facade` (`AuthRole`)                     | `AuthRole` from `@niord/admin/api`                                                                          |
| `@niord/admin/internal/shared/pipes/plain-text-to-html.pipe`                           | `PlainTextToHtmlPipe` from `@niord/admin/api`                                                               |
| `@niord/admin/internal/shared/testing/transloco-testing` (specs)                       | `provideTestTransloco` from `@niord/admin/api/testing`                                                      |
| `@niord/admin/internal/core/country/country-routes` (`countryRouteProblems`, specs)    | `countryRouteProblems` from `@niord/admin/api/testing`                                                      |
| `@niord/info/internal/niord-info.config`                                               | `InfoConfig`, `RootAreaSpec` from `@niord/info/api`                                                         |
| `@niord/info/internal/core/config/language-config`                                     | `LanguageConfig` from `@niord/info/api`                                                                     |
| `@niord/info/internal/shared/slots/component-slots`                                    | `provideSlot`, `COMPONENT_SLOTS`, `SlotName`, `SlotLoader`, `SlotRegistration` from `@niord/info/api`       |
| `@niord/info/internal/core/services/app-config.service`                                | `AppConfigService` from `@niord/info/api`                                                                   |
| `@niord/info/internal/shared/components/dialog/open-dialog`                            | `openInfoDialog` from `@niord/info/api`                                                                     |
| `@niord/info/internal/shared/utils/current-year`                                       | `currentYearInZone` from `@niord/info/api`                                                                  |
| `import('@niord/info/internal/shared/components/legal-dialog/legal-dialog.component')` | `loadLegalDialog` from `@niord/info/api`, passed to `openInfoDialog` as the loader                          |
| `@niord/info/internal/testing/test-config` (specs)                                     | `getTranslocoTestingModule`, `provideTestNiordConfig` from `@niord/info/api/testing`                        |

`import type` where only types are imported. `loadLegalDialog()` resolves to the legal dialog's
component class, so the dialog stays a lazy chunk; the component itself is not exported. The testing
entry points are for `*.spec.ts` files only: test helpers belong in no build (the public site's use
Vitest's globals and load the English bundle). `COUNTRY_MENU` is not exported: the application wires it, and a country fills it
through `admin/app/country.menu.ts`.

A guarded override keeps its `@niord/<app>/internal/*` imports, as `--add-override` wrote them.

**The base product's login fixtures for a country's end-to-end specs.** A country's specs under
`e2e/` import `test` and `expect`, with the signed-in page fixtures, from `@niord/admin/e2e`: the
base product's `ui-apps/admin/e2e/auth/fixtures.ts`, mapped by the generated
`.niord/tsconfig.e2e.json` that the generated `playwright.config.ts` names. Why: a country tests its
own pages signed in as the base product's suite does, without copying the login. This is an addition.

What a country must do: nothing; `npm run e2e` runs its specs, and passes when it has none.

**The set-up stub changed** (`country-kit/templates/setup.mjs`). Its Maven command joins the
wrapper's path for the platform the command is for (#185), and its header says what it trusts and
that a change to it is recorded here. Why: a command built for Windows on Linux read `…\be/mvnw.cmd`;
a real run, whose host is its target, behaves as before.

What a country must do: copy `.niord/base/country-kit/templates/setup.mjs` over `fe/setup.mjs` and
commit it; set-up names it in the upgrade report's To do until it is done.
