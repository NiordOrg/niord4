# Duty-officer scenarios

End-to-end scenarios of a duty officer's working day, through the screens, against the base
product's **sample deployment**. Each scenario writes data: it makes messages, mailing lists,
schedules and publications, so the scenarios are opt-in, and they never run in CI.

| File                         | Scenario                                                                                                    |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `nw-warning.spec.ts`         | A navigational warning from a template: saved, edited, verified, published, read everywhere, cancelled.     |
| `nm-notice.spec.ts`          | A notice to mariners with several parts, positions and attachments: saved, edited, published, read back.    |
| `firing-schedule.spec.ts`    | The firing schedule: periods entered, the exercises generated, a period changed and the warning following.  |
| `weekly-publication.spec.ts` | A weekly publication: created, an issue published, amended, and what the public side serves.                |
| `keyboard-publish.spec.ts`   | A warning written, saved, verified and published with the keyboard alone; its overlays scanned by axe-core. |

After every step that saves, a scenario compares what the screen shows with what the admin API
holds and, where the step is public, what the public API, the public site, the PDF and the mail
hold. Once per scenario the backend is restarted and everything is read again.

## The switch and the guards

Without `E2E_SCENARIOS=1` every scenario test skips and says so, so a plain `npm run e2e` against
somebody's development data stays harmless. With it, before anything is written:

- `KEYCLOAK_URL` must be set;
- the run must have one worker (`--workers=1`): each scenario restarts the backend the others use;
- the backend must hold the sample: a public message titled "SAMPLE —" and the domains `niord-nw`,
  `niord-nm` and `niord-fa`;
- the backend must not run in production mode.

## Variables

| Variable                        | Required | What                                                                                                                           |
| ------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `E2E_SCENARIOS`                 | yes      | `1` turns the scenarios on.                                                                                                    |
| `E2E_BASE_URL`                  | yes      | The administration app's dev server, proxying `/rest` to the sample's backend.                                                 |
| `KEYCLOAK_URL`                  | yes      | The local stack's Keycloak, `http://localhost:8080/auth`.                                                                      |
| `KEYCLOAK_TEST_*`               | yes      | The sample's logins: the base pair (`sysadmin`) and the `_SYSADMIN`, `_ADMIN`, `_EDITOR`, `_USER` pairs (`../auth/README.md`). |
| `E2E_INFO_URL`                  | no       | The public site's dev server. Without it the public-site pages are not read.                                                   |
| `E2E_MAILHOG_URL`               | no       | MailHog, `http://localhost:8025`. Without it the mails are not read.                                                           |
| `E2E_SCENARIOS_RESTART`         | no       | A shell command that restarts the backend (below). Without it the restart is skipped.                                          |
| `E2E_SCENARIOS_RESTART_TIMEOUT` | no       | Seconds to wait for the command and the backend's readiness; default 600.                                                      |
| `E2E_SCENARIOS_BACKEND_URL`     | no       | The backend itself, `http://127.0.0.1:8888`. After a restart the readiness is polled there, not through the dev server.        |

A left-out read is not silent: the report carries an annotation such as `restart skipped:
E2E_SCENARIOS_RESTART is not set` or `E2E_MAILHOG_URL is not set: the mails are not read`. A run
that is to stand for the scenarios has none of them.

## Running them

`./start-local.sh --sample` cannot host the restart: it stops the whole run once the backend has
not answered for about ten seconds. So the backend runs apart from it:

1. Once, create and load the sample database: `./start-local.sh --sample`, wait for "Niord is
   running", then stop it with Ctrl+C.
2. Start the backend alone with the same JVM arguments `start-local.sh --sample` gives it, plus
   `-DmailValidRecipients=ALL`, so that its mails reach MailHog (the default, `LOG`, only logs
   them; a system property wins over the stored setting, so the database is not changed). From
   `be/`, in Git Bash:

   ```bash
   ./run.sh "-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC -Dserver.port=8888 \
     -Dniord.home=$HOME/.niord-local/niord_sample \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/niord_sample -Dmode=development \
     -DadminAppUrl=http://localhost:4100 -Dniord.secom.enabled=false -DmailValidRecipients=ALL" \
     >> ../.local-run/backend-scenarios.log 2>&1
   ```

3. Start the two dev servers from `fe/`: `npm run start:local -- --port 4100` and
   `npm run start:info -- --port 4400`.
4. Write the restart command: a script that stops the backend (the process that listens on its
   port, and the Maven process that started it), waits until the port is free, starts step 2's
   command again in the background with its output in the log, and returns at once. The suite
   runs it, then waits until `/rest/actuator/health/readiness` reads `UP` and `/rest/settings/web`
   answers.
5. Run, from `fe/`, with the credentials set only in the shell:

   ```bash
   E2E_SCENARIOS=1 E2E_BASE_URL=http://localhost:4100 E2E_INFO_URL=http://localhost:4400 \
   E2E_MAILHOG_URL=http://localhost:8025 KEYCLOAK_URL=http://localhost:8080/auth \
   E2E_SCENARIOS_RESTART="bash /path/to/restart-backend.sh" \
   E2E_SCENARIOS_BACKEND_URL=http://127.0.0.1:8888 \
   KEYCLOAK_TEST_USERNAME=sysadmin KEYCLOAK_TEST_PASSWORD=... \
   KEYCLOAK_TEST_USERNAME_SYSADMIN=sysadmin KEYCLOAK_TEST_PASSWORD_SYSADMIN=... \
   KEYCLOAK_TEST_USERNAME_ADMIN=admin KEYCLOAK_TEST_PASSWORD_ADMIN=... \
   KEYCLOAK_TEST_USERNAME_EDITOR=editor KEYCLOAK_TEST_PASSWORD_EDITOR=... \
   KEYCLOAK_TEST_USERNAME_USER=user KEYCLOAK_TEST_PASSWORD_USER=... \
   npx playwright test ui-apps/admin/e2e/scenarios --workers=1
   ```

A whole run takes four to eight minutes with the restarts, three to four without.

The pages reach no host but this machine: a map tile gets a one-pixel image and every other
request off the machine is aborted (`support/offline.ts`). The backend is not held: a map image it
renders for a message fetches its tiles from the tile server in its settings. The scenarios ask
for none.

The admin dev server can die while the backend is down (on Windows, esbuild has stopped with
`all goroutines are asleep - deadlock!`). Give `E2E_SCENARIOS_BACKEND_URL`, so that the readiness
polls do not go through it; after a restart the suite checks the dev server once more and names it
if it does not answer. Every action and navigation has a time limit of 30 and 60 seconds, so a
dead dev server fails a test in a minute, not at the ten-minute test timeout. Start it again and
run again: the next run tidies what the failed one left (below).

## What a run leaves behind

Every name a scenario gives carries the run's marker, `E2E-<UTC time>-<4 characters>`, and every
assertion names the run's own rows, never a total, so a second run on the same database passes.
The messages stay: they are the run's record. What would act on a later run is switched off at
the end:

- `nw-warning.spec.ts` leaves a contact `e2e-<marker>@example.org` and a mailing list
  `e2e-nw-<marker>`, switched off at the end; its warning, cancelled, and the cancellation warning
  the backend published for it, which stays in force (the messages list shows it with the Type
  filter's _Cancellation warnings_ chip); their mails in MailHog.
- `nm-notice.spec.ts` leaves its notice, published, with its attachments in the repository. It
  makes nothing else: the notices it refers to, the areas, the category and the charts are the
  sample's, and its files are made in memory.
- `firing-schedule.spec.ts` makes the firing schedule of `niord-fa` the first time it runs, into
  the NW domain's series without short ids, and keeps it: a domain has one schedule, so it cannot
  be named per run. The set-up switches it on and the end switches it off, so the hourly firing
  run does not act on the sample between runs; the Schedule page shows in `niord-fa` from the first
  run on. Firing periods have no name either: the scenario takes the areas of the sample's
  published firing area messages and the days around the browser's today (one day before to six
  after), clears those days before it starts and when it ends, and updates the exercises, so the
  run's last warning is cancelled. Its warnings stay, cancelled, as the run's record.
- `keyboard-publish.spec.ts` leaves its warning, titled `<marker> keyboard walk` and with the vicinity
  `<marker> keyboard harbour`, cancelled at the end through the admin API, as the run's record, and
  the cancellation warning the backend publishes for it in the sample's auto-numbered NW series,
  which stays in force, as `nw-warning.spec.ts`'s does; so does the one for each earlier walk's
  warning its set-up cancels. The series, the area and the category are the sample's.
- `weekly-publication.spec.ts` leaves two published notices to mariners titled with the marker
  (one dated a day back, into the released week), and a weekly series `E2E weekly <marker>`,
  retired at the end. Its first issue stays published and public, with its document at its link
  and the replaced one in the archive; the next issue the release opened stays open on the
  retired series. Each run's notices also join the issues of later runs on the same day: the
  scenario asserts only that its own notices are there.

A run that crashes or is stopped skips the ends of its scenarios. The next run's set-ups tidy
what it left switched on, and the report names each with a `tidy` line: `nw-warning.spec.ts`
switches off every `e2e-nw-<marker>` list still on and cancels every warning still published whose
vicinity carries a marker; `weekly-publication.spec.ts` retires every `e2e-weekly-<marker>` series
still active; `firing-schedule.spec.ts` clears its days before it starts and switches the schedule
off when it ends; `keyboard-publish.spec.ts` cancels every warning still published whose vicinity is
a marker and `keyboard harbour`.

`./start-local.sh --sample --reset` starts the sample afresh.

## Known faults

A product fault a scenario meets is marked where it shows with `knownFault(code, title, check)`
(`support/known-fault.ts`): the check asserts the right behaviour; while the fault shows, the step
passes with a `known-fault` annotation in the report; once it is fixed, the test fails and asks for
the mark to be removed. `code` names the record in `be/docs/potential-errors.md` or
`fe/docs/backlog.md`; `pending: <title>` is a fault not yet filed.

## The keyboard walk

`keyboard-publish.spec.ts` is the administration app's keyboard check: after its one page load it
presses keys only, asserts after every step which control has the focus and that the focus shows,
and reads the published warning back through the admin API. Each overlay it opens is scanned with
axe-core against the list of known violations, `../a11y/known-violations.json`; the route scan
beside it, `../a11y-routes.spec.ts`, runs with `E2E_A11Y=1` on the same stack
([testing.md](../../../../docs/testing.md)). A control the keyboard cannot reach or operate is a
`knownFault`, and the walk then goes on the nearest way the keyboard still allows.

## Support

| File                     | What                                                                                       |
| ------------------------ | ------------------------------------------------------------------------------------------ |
| `support/env.ts`         | The switch, the variables, `describeScenario` (serial, ten minutes a test) and the guards. |
| `support/api.ts`         | The admin API with the page's own login and domain header; the public API, anonymous.      |
|                          | `asTestUser` for a hook with no page. A failure's message never carries the token.         |
| `support/compare.ts`     | Screen against API: normalised text, lists in order, positions within the shown precision. |
| `support/run-id.ts`      | The run's marker.                                                                          |
| `support/restart.ts`     | Runs `E2E_SCENARIOS_RESTART` and waits for the backend.                                    |
| `support/mailhog.ts`     | The mails to an address, and waiting for exactly so many.                                  |
| `support/pdf-text.ts`    | The text of a PDF the backend renders, without a dependency.                               |
| `support/offline.ts`     | Keeps the browser on this machine.                                                         |
| `support/known-fault.ts` | The known-fault mark.                                                                      |
| `support/zoned-time.ts`  | Wall clocks in a domain's zone, and the dates and times the date controls show.            |
| `support/pickers.ts`     | Picking a day and a time in the app's date and time controls.                              |
