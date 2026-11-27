# The set-up prompt

Give this whole file to an AI agent that runs commands on your machine: in Git Bash on Windows,
from an empty folder whose path has no spaces. The agent asks you every answer and the release's two
lines, never handles your token, and ends with your country's stack running on your machine and one
local commit, which you push yourself. It follows the country guide's
[Quick start](country-guide.md#quick-start); what comes after is
[Make it yours](country-guide.md#make-it-yours).

Everything below is for the agent.

You are setting up a new country repository of Niord for the person who gave you this file. Work
through the eleven steps in order. Every command runs from the repository root unless it starts with
`(cd fe && …)` or `(cd be && …)`. When a "Stop when" holds, stop: show the person the step, the
command and the last lines of its output, and wait for them. Never work around a refusal.

## 1. The goal, and what you never do

The goal: from an empty folder to the country's stack running on the person's machine, with every
answer the person's, ending at one local commit. Nothing is pushed.

- Never edit `fe/setup.mjs`, `start-local.sh` or anything under `.niord/`. Before init you put only
  the answers file, the two zips and `SHA256SUMS` in `.niord/` and unzip the kit into `.niord/kit/`;
  after that, nothing.
- Never invent an answer, a version or a sha256 line, and never take a default the person has not
  confirmed.
- Never handle the token: the person writes `~/.m2/settings.xml`; never print, copy, write or edit
  it, and never ask for the token.
- Never compute a pin or a sha256 line from a download: the person gives the two lines, and you
  write them into `.niord/SHA256SUMS` exactly as given.
- Never set `REGISTRY`, `HARBOR_PROJECT` or robot secrets, run `image.yml`, or touch staging or
  production.
- Never `dropdb`, run `./start-local.sh --reset`, or delete a database without the person's say-so.
- Never push, and never create or change a remote repository.

## 2. The prerequisites

Run each command below, then report every result to the person in one list. You install nothing:
when one is missing or wrong, the person installs or fixes it.

| What                         | Command                                        | Expect                                                                                                                                                            |
| ---------------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| JDK 25, `JAVA_HOME` exported | `"$JAVA_HOME/bin/java" -version`               | version 25                                                                                                                                                        |
| its `jar`                    | `"$JAVA_HOME/bin/jar" --version`               | 25                                                                                                                                                                |
| Node                         | `node --version`                               | 22.22.3 or a later 22, 24.15.0 or a later 24, or 26                                                                                                               |
| npm                          | `npm --version`                                | 11 (the base product names 11.11.0; npm only warns)                                                                                                               |
| Docker, running              | `docker info --format '{{.ServerVersion}}'`    | a version and no error (the person starts Docker Desktop)                                                                                                         |
| curl                         | `curl --version`                               | a version                                                                                                                                                         |
| git                          | `git --version`                                | a version                                                                                                                                                         |
| unzip                        | `unzip -v`                                     | a version                                                                                                                                                         |
| sha256sum                    | `sha256sum --version`                          | a version                                                                                                                                                         |
| the GitHub CLI, signed in    | `gh auth status`                               | logged in; when it is not, the person signs in, or gives you the release's two files at step 5                                                                    |
| the token file               | `grep -c '<id>github</id>' ~/.m2/settings.xml` | 1 or more. It prints a count only: never show the file. When it is 0 or the file is missing, the person writes it as [The token](country-guide.md#the-token) says |
| on Windows, Git Bash         | `uname -s`                                     | starts with `MINGW`                                                                                                                                               |
| the folder                   | `pwd` and `ls -A`                              | no space in the path; the folder is empty, or the person chooses to make the repository in a new folder inside it (step 4)                                        |

Stop when: any row is not as expected. The detail is in
[What you need](country-guide.md#what-you-need) and [Prerequisites](../README.md#prerequisites).

## 3. The questions

Ask the person all sixteen questions, in the table's order and as init asks them. Ask the required
ones without a default. Show every other one with its standard default filled in from the answers
so far: `<cc>` is the country code, `<CC>` the same in upper case, `<name>` the country's name, and
when `domain.mainType` is `NM` the default is the NM one. Take a default only when the person
confirms it. When an answer breaks its rule, say which rule and ask again.

| Key                         | init asks                                                                          | Rule                                                                                                                                                                                                                                                                               | Standard default                                                         |
| --------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `country`                   | `Country code, ISO 3166-1 alpha-2 in lower case`                                   | the ISO 3166-1 alpha-2 code, two letters a-z; it names `niord-<cc>`, `org.niord.<cc>` and the database `niord_<cc>`: choose it once                                                                                                                                                | **required**                                                             |
| `name`                      | `Country name, as the applications show it, at most 60 characters`                 | plain text (no `<`, `>` or `&`, no control character), not blank, no leading or trailing space, at most 60 characters                                                                                                                                                              | **required**                                                             |
| `organisation`              | `Organisation that runs the deployment`                                            | the authority that runs the deployment; plain text as for `name`, at most 100 characters                                                                                                                                                                                           | **required**                                                             |
| `languages`                 | `Languages besides English, published packs, separated by spaces [none]`           | 2-3-letter codes a-z, each once, never `en`; each a published pack pinned from its home, which becomes a UI and content language of both applications                                                                                                                              | `[]` (English only)                                                      |
| `producerCode`              | `S-124 producer code, IHO S-62, four capitals or digits [none]`                    | four upper-case letters A-Z or digits, or empty; not another authority's code nor the guides' placeholder; empty until the authority has its own                                                                                                                                   | `""` (none)                                                              |
| `timeZone`                  | `Time zone of the first domain and the public site, IANA (UTC or Area/Location)`   | an IANA zone spelt in the tz database's case: `UTC`, or Area/Location with capitals such as `Europe/London`; `europe/london` and `GMT+1` are refused                                                                                                                               | **required**                                                             |
| `map.lat`                   | `Map centre latitude, whole degrees from -90 to 90`                                | a whole number from -90 to 90                                                                                                                                                                                                                                                      | **required**                                                             |
| `map.lon`                   | `Map centre longitude, whole degrees from -180 to 180`                             | a whole number from -180 to 180                                                                                                                                                                                                                                                    | **required**                                                             |
| `map.zoom`                  | `Map zoom level, 1 to 18 [6]`                                                      | a whole number from 1 to 18                                                                                                                                                                                                                                                        | `6`                                                                      |
| `domain.mainType`           | `First domain's main type, NW or NM [NW]`                                          | `NW` (navigational warnings) or `NM` (notices to mariners)                                                                                                                                                                                                                         | `"NW"`                                                                   |
| `domain.id`                 | `First domain's id [<cc>-nw]`                                                      | lower-case letters a-z, digits and `-`, starting with a letter, 2 to 63 characters; not `niord-client-master`, `account`, `account-console`, `admin-cli`, `broker`, `realm-management`, `security-admin-console` or `niord-web`; it is also the id of the domain's Keycloak client | `"<cc>-nw"` (`"<cc>-nm"` for NM)                                         |
| `domain.name`               | `First domain's name [<name> Navigational Warnings]`                               | plain text as for `name`, at most 100 characters                                                                                                                                                                                                                                   | `"<name> Navigational Warnings"` (`"<name> Notices to Mariners"` for NM) |
| `series.id`                 | `First message series' id [<cc>-nw]`                                               | the rule of `domain.id`                                                                                                                                                                                                                                                            | `"<cc>-nw"` (`"<cc>-nm"` for NM)                                         |
| `series.shortFormat`        | `First message series' short format [<CC>-NW-${number-3-digits}-${year-2-digits}]` | plain text with the backend's tokens only, such as `${number-3-digits}`, `${year-2-digits}` and `${year}`; `${number}` or `${number-3-digits}` among them unless the numbering is `NONE`; init names every token when it refuses one                                               | `"<CC>-NW-${number-3-digits}-${year-2-digits}"` (`-NM-` for NM)          |
| `series.numberSequenceType` | `First message series' numbering, YEARLY, CONTINUOUS, MANUAL or NONE [YEARLY]`     | `YEARLY`, `CONTINUOUS`, `MANUAL` or `NONE`                                                                                                                                                                                                                                         | `"YEARLY"`                                                               |
| `owners`                    | `Repository owners for CODEOWNERS, @user or @org/team, separated by spaces`        | one or more, each a GitHub `@user` or `@org/team`, each once                                                                                                                                                                                                                       | **required**                                                             |

For `languages`, each code is a pack another country publishes on GHCR as
`ghcr.io/<owner>/niord-lang-<lang>`, each tag with its digest, which the summary of the home's Pack
run that published it states too. When this kit was released, `da` was the one published
pack: `ghcr.io/sifa-dk/niord-lang-da`, whose home is `SIFA-DK/niord-dk`. Make no registry call of
your own to check a code: step 8 pulls and verifies each pack, and refuses one that is not
published and public.

What each answer means is in [The answers](country-guide.md#the-answers); what changing one later
costs is in [Changing an answer later](country-guide.md#changing-an-answer-later).

## 4. The answers file

Make the repository. When the person chose a new folder, make it first with
`mkdir niord-<cc> && cd niord-<cc>`; it is the repository root from then on. Then, in the root:

```bash
git init
mkdir .niord
```

Write `.niord/init-answers.json` with all sixteen answers, the confirmed defaults included. This
is its shape, for the made-up country `qq`; the values are the person's, never these:

```json
{
  "country": "qq",
  "name": "Qualia",
  "organisation": "Qualia Maritime Authority",
  "languages": [],
  "producerCode": "",
  "timeZone": "UTC",
  "map": { "lat": 0, "lon": 0, "zoom": 6 },
  "domain": { "id": "qq-nw", "name": "Qualia Navigational Warnings", "mainType": "NW" },
  "series": {
    "id": "qq-nw",
    "shortFormat": "QQ-NW-${number-3-digits}-${year-2-digits}",
    "numberSequenceType": "YEARLY"
  },
  "owners": ["@qualia-maritime/niord-maintainers"]
}
```

Show the file to the person and go on only with their yes. Nothing of `.niord/` is ever committed.

## 5. The release

Ask the person for the base product version, `<version>`, and for the two lines of its release
notes, exactly as they give them:

```text
<sha256>  niord-fe-src-<version>.zip
<sha256>  niord-country-kit-<version>.zip
```

Then fetch both zips, write the two lines, check the zips against them and unzip the kit:

```bash
gh release download v<version> --repo NiordOrg/niord4 \
  --pattern 'niord-fe-src-<version>.zip' --pattern 'niord-country-kit-<version>.zip' --dir .niord
printf '%s\n' '<the first line>' '<the second line>' > .niord/SHA256SUMS
(cd .niord && sha256sum -c SHA256SUMS) && unzip -q .niord/niord-country-kit-<version>.zip -d .niord/kit
cat .niord/kit/kit.json
```

Run the lines one at a time. Expect two lines ending `OK` from `sha256sum -c`, and `kit.json` naming
`<version>`. Run nothing from either zip before both say `OK`.

Stop when: a line says `FAILED` (never change a line and never hash a file yourself; show it), or
`kit.json` names another version. The detail is in [The release](country-guide.md#the-release).

## 6. init

```bash
node .niord/kit/init.mjs --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS
```

Expect about 40 lines `init: wrote <file>`, no line `init: kept <file>, which exists`, and the
closing lines from `init: next, from the repository root:`, which name the set-up command of step 8.
On Windows two more lines follow: the commit line of step 11 and the `NIORD_HOME` line of step 10.

Stop when:

- a line says `kept`: the folder was not empty;
- init says `the answers are refused: nothing was written`, with a line `<answer>: <message>` for
  each refused answer: ask the person for each corrected answer, change `.niord/init-answers.json`
  to it, show them the file, and run init again;
- init refuses anything else (a hash, the version, a zip): show the refusal.

The detail is in [Init, set-up and the launcher](country-guide.md#init-set-up-and-the-launcher),
[Starting a country — init](../README.md#starting-a-country--init) and
[What init wrote, file by file](country-guide-in-depth.md#what-init-wrote-file-by-file).

## 7. From here on, AGENTS.md is the contract

Read the `AGENTS.md` init wrote at the repository root, whole, now. For all later work in this
repository it is the contract: its "Never" holds beside this file's, and its "When a check fails"
says what a failure means and what to do. `CLAUDE.md` beside it only imports it.

## 8. Set-up

Run the set-up command init's closing lines named: with no languages answered,

```bash
(cd fe && node setup.mjs)
```

and with languages, which writes their pins,

```bash
(cd fe && node setup.mjs --update-pins)
```

Expect it to end with exit code 0. Stop when: Maven answers 401 or 403 (the token: the person fixes
`~/.m2/settings.xml`), `JAVA_HOME` is refused, a pack is refused (a line saying
`an anonymous pull needs the package to be public` or `is the package public?`: the pack is not
published and public, and no pin is written), or a check fails (`AGENTS.md`, "When a check fails").

With languages, set-up prints `setup: --update-pins: <lang>: no pin -> <the pin>` for each new pin,
the pin ending in its `sha256:` digest, and then a line ending `review the new digests, and a
first pin against the summary of the home's Pack run that published its tag`. Show the person each
new pin's line and where its home states that tag's digest: the summary of the home's Pack run on
`main` whose run number is the tag (Actions, Pack) — for `da`, a run of `SIFA-DK/niord-dk`, a
repository that is not public yet (when the person cannot read that repository, or the run is gone,
since GitHub keeps a run only for the repository's retention period, its maintainers give the
reference). Stop when: the person has not confirmed that each new digest is the one that run's
summary, or the home's maintainers, state for its tag. The comparison is the person's: go on to
step 9 only with their yes, so nothing is committed at step 11 without it.

The detail is in [Set-up](../README.md#set-up) and
[A language another country is home to](country-guide-in-depth.md#a-language-another-country-is-home-to).

## 9. The launcher and the three checks

Start the launcher as a background task of your tool, or so:

```bash
mkdir -p .local-run && ./start-local.sh > .local-run/start-local.out 2>&1 &
```

init's `.gitignore` keeps `.local-run/` out of the commit. The first run fetches, builds and
bootstraps, and often takes 20 minutes or more. On the way it prints
`>> Creating the local Keycloak client <domain.id>` (or a line ending `exist: left as they are`).
Wait until it prints its closing block, whose first line ends `is running (loopback only)`.

Stop when:

- the launcher exits, or prints a line starting `!!`: show its last 30 lines and the log it names;
- it says a port is in use: see
  [When it goes wrong on Windows](country-guide.md#when-it-goes-wrong-on-windows).

Never stop or remove the shared containers, never change Keycloak yourself, and never run the
launcher again with `--reset` or after a `dropdb`.

Then make the three checks and report each result. The addresses are those of the closing block:
the administration app at `http://localhost:<FE_PORT>`, 4200 by default, the public site at
`http://localhost:<INFO_PORT>`, 4400 by default, and the backend at
`http://localhost:<BE_PORT>/rest`, 8888 by default; write each command with the port the block
names.

1. `curl -s http://localhost:<FE_PORT>/rest/settings/web`: `siteLanguages`, `modelLanguages` and
   `infoSiteLanguages` each hold `en` and every answered language.
2. `curl -s http://localhost:<FE_PORT>/rest/domains/all`: a domain whose `domainId` is the answered
   `domain.id` and whose `timeZone` is the answered zone.
3. The person, or you with a browser tool, signs in at the administration app's address as
   `sysadmin` / `sysadmin` and chooses the domain in the menu: Editor, Admin and Sysadmin appear,
   and the domain has a pencil in the menu's domain list. The three areas appear without the
   domain's client too, since the local `sysadmin` holds `sysadmin` on `niord-client-master`; the
   pencil shows the client exists. A session signed in before the client was made signs in again.
   When the pencil is missing, restart `./start-local.sh` (the launcher adds a missing client) and
   see
   [Your sysadmin in your own domain](country-guide-in-depth.md#your-sysadmin-in-your-own-domain).

Report the addresses of the closing block (the administration app, the public site, the backend,
Keycloak with its admin console's `admin` / `admin`, MailHog) and the login `sysadmin` /
`sysadmin`. The detail is in [How to see it worked](country-guide.md#how-to-see-it-worked),
[Log lines that are harmless](country-guide.md#log-lines-that-are-harmless),
[Running locally in detail](country-guide-in-depth.md#running-locally-in-detail) and
[Running it locally](../README.md#running-it-locally--start-localsh).

## 10. Optional: the builds

Only when the person asks for them. Stop the launcher first, as you started it, and see that the
ports 4200 and 4400 are free again
([When it goes wrong on Windows](country-guide.md#when-it-goes-wrong-on-windows)): on Windows its
development servers hold `fe/node_modules/`, which `npm ci` replaces. The shared containers keep
running.

```bash
(cd fe && npm ci && npm run build && npm test && npm run lint)
```

Then, with Docker running, the backend and its group check. On Windows the first line is init's
last one and runs in the same shell as the second; on Linux and macOS leave it out.

```bash
export NIORD_HOME=$(cygpath -m "$TEMP")/niord
(cd be && ./mvnw verify && scripts/check-country-build-group.sh)
```

Expect the group check's line `the country build group ran: <n> integration tests`, with `<n>`
above zero. Stop when: a command fails (`AGENTS.md`, "When a check fails"). The detail is in
[Definition of done for a country](country-guide.md#definition-of-done-for-a-country),
[The backend build](country-guide-in-depth.md#the-backend-build) and
[The group check](country-guide-in-depth.md#the-group-check).

## 11. The commit

On Windows, give the three scripts their exec bits as init's commit line says; on Linux and macOS
`git add -A` alone keeps the modes init gave.

```bash
git add -A && git add --chmod=+x start-local.sh be/mvnw be/scripts/check-country-build-group.sh
git status --short
```

Expect `git status --short` to list nothing under `.niord/`, `fe/.niord/`, `fe/node_modules/`,
`.local-run/` or `be/target/`. Stop when it does: show the person the list. Then commit, and check
the exec bit:

```bash
git commit -m "Start niord-<cc> on the base product <version>"
git ls-files -s be/mvnw
```

Expect the last line to start with `100755`. Stop when: git has no user name or email (the person
sets them). Never push. The detail is in [Commit](country-guide.md#commit).

Report to the person: the commit, the answers file, the version and the results of the three checks.
Their next sections are [Make it yours](country-guide.md#make-it-yours),
[Your logo in the PDFs](country-guide.md#your-logo-in-the-pdfs) and
[Deploy](country-guide.md#deploy).
