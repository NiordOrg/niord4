# The user guides' screenshot rig

Every screenshot of the user guides (`guides/`) is taken by a script, so a change to the interface
means running the script again, not a week of clicking. The pictures show the base product's
default styling and English interface, on a scrubbed copy of the test data, in the database
`niord_guides`. This folder is a Playwright project of its own: the end-to-end run never picks it
up, it never runs in CI (its configuration refuses), and the source zip does not carry it.

## Prerequisites

- Docker Desktop running, with the shared containers `niord-db`, `niord-keycloak` and `niord-smtp`
  (any `./start-local.sh` run starts them).
- Git Bash (on Windows), JDK 25 and Node, as for `./start-local.sh`; `npm ci` done in `fe/`.
- Playwright's Chromium: `npx playwright install chromium` in `fe/`.
- The database `niord_guides` and the Niord home `~/.niord-ng-guides`, made by the data scripts
  in `data/` (step 1; their own README says what they copy, scrub and check). The rig refuses to
  start without the database, and refuses one that still holds an e-mail address outside
  `example.org` in a user, contact or mail recipient.
- Mails are sent: `data/prepare.sql` sets `mailValidRecipients` to `ALL` in `niord_guides`, so a
  mail the backend sends reaches MailHog, the local stack's mail catcher (every recipient in the
  scrubbed data is an invented one on `example.org`). With `LOG` the backend only logs a mail and
  the pilot's mail picture is skipped, naming the setting.
- Network access to the map tiles (OpenStreetMap) and, for a PDF page, to `cdn.jsdelivr.net`, from
  which the browser loads PDF.js at a pinned version.

## The three commands

1. **Make the data.** From the repository root, with the rig's backend stopped:
   `bash fe/guides-shots/data/make-guides-db.sh` copies the test data into `niord_guides`, scrubs
   it, checks it and applies `prepare.sql`, `english.sql` and `english-history.mjs` (the copy's
   English-language texts made English, its message history too: the guides show no Danish text);
   `bash fe/guides-shots/data/make-guides-home.sh` makes
   the Niord home `~/.niord-ng-guides` from the test data's home. Neither writes to any other
   database or home. After the first backend start on a new copy, a sysadmin runs the repository
   import once (`data/README.md`, "After a backend start"), or attachments and publication files
   answer 404; then, in the same backend run, `node fe/guides-shots/data/seed-examples.mjs` makes
   the example rows the pictures need that must look fresh or are made through the application
   (drafts, comments, tags, a saved filter, an NM draft with an image, mailing lists of `editor`,
   a retired and a weekly example series, an amended issue, a message added by hand, and last the
   firing exercise messages; `data/README.md`). So on a new copy: `make-guides-db.sh`,
   `make-guides-home.sh`, step 2, the repository import, `seed-examples.mjs`, step 3.
2. **Start the stack.** From the repository root: `bash fe/guides-shots/start-rig.sh`. It starts
   the backend as `./start-local.sh --slot 6` would, on `niord_guides` with the Niord home
   `~/.niord-ng-guides`, builds both frontends once (the `local` configuration, into
   `.local-run/guides-dist/`) and serves those files with `serve-built.mjs`: the administration
   app on 4106, the public site on 4406, the backend on 8894, beside Keycloak on 8080 and MailHog
   on 8025. Ctrl+C stops it. No dev server runs, so the pages are fixed files for the whole run:
   after a change under `fe/`, start the rig again. A part that fails does not stop the others
   (the file server is started again, a backend that stops answering is reported with the time).
   While pictures are taken, nothing else uses the stack: no `make-guides-db.sh`, no Maven build
   in this checkout's `be/` (the backend restarts when its classes change), no second run.
3. **Take the pictures.** From `fe/`, in a second terminal, once the rig prints "The rig is up":
   - `npm run shots:pilot`: one picture of each kind, into `fe/guides-shots/.pilot/` (gitignored).
   - `npm run shots`: every manual's pictures, into `guides/<manual>/images/`.
   - `npm run shots:check`: the pictures the specs take against the manifests' retake, replace and
     new entries: what is missing, what is extra.

## Taking one picture again

Each test is titled `<manual>/<file>` after the picture it writes, so:

```bash
npm run shots -- --grep "editor-manual/Home.png"
```

A test fails, and writes nothing, when the page shows an error toast, logs a console error or a
missing translation, got a 5xx answer, shows a text shaped like a translation key, or shows Danish
text. The failure names what it saw; a trace of the failed test is kept under
`fe/guides-shots/test-results/`.

Two errors are left out, both raised in a sandboxed frame (the administration app's
`app-sandboxed-html`, an `<iframe sandbox="">` that shows a mail in the mail queue, a report's result
or a mailing list's test mail), where Playwright runs every init script although the frame may run
none: the browser's `Blocked script execution in 'about:blank'` (or `'about:srcdoc'`), which Chromium
logs for Playwright's own script even when the rig adds none, and the page error `Failed to read the
'localStorage' property from 'Window': The document is sandboxed ...` raised by a script whose stack
names no file, the end-to-end suite's token seeding (`ui-apps/admin/e2e/auth/inject-auth.ts`), which
reads the storage in every frame. The rig's own init scripts (the session domain in `loginAs`, the
stabiliser) do nothing in such a frame. The frame's text is still read like any other's.

The guides show the English interface only (the owner's ruling of 2026-11-11): no Danish interface
word and no Danish sentence of content, while a Danish name (Rønne Havn, a vessel's or a company's
name), the base map's labels and a code may show. The guard (`support/danish.ts`) reads every
visible text of the page (rendered, and not under `visibility: hidden` or `opacity: 0`), of its
frames and of their open shadow roots, inside the picture or not: each text's whole sentence
and each piece of it on its own (a row of buttons reads as one sentence), each button, link, label,
menu item and tab, the value of each field, the selected option of each list box, each placeholder
and title, the `<title>` of each visible SVG, the alternative text of an image that did not load,
and the generated content of `::before` and `::after`. It fails a picture on a text that holds two different Danish function
words (og, på, af, ikke, ...), a short Danish sentence (two words of a short list, `Skriv en
kommentar her`, `Vis på kortet`, in a text with no English stop word), a Danish date (`7. juni`,
`21 Marts`), the Danish `og` after a number or a lower-case word, `ved` after a number, the journal
number `J.nr.`, a Danish content word (`slukket`, `boringer`, `periode`, `navigationsadvarsler`,
`vrag`, `kabelarbejde`, `usikker`, `signalmast`, `trafiksignal`, `bassin`, `isbilag`, ...; `kanal`,
`fyr`, `bro` only in lower case and not after a name: `VHF kanal 16` is refused, `Odense Kanal` and
`Skagen fyr` are not), a Danish interface word as a whole text (`Gem`, `Slet`, `Opret`,
`Indstillinger`, `Annulleret`, `Afslut`, `Zoom ind`, `Fjern filter`, ...), a Danish dictionary
value that is no English text, or the Danish value of two or more words of any of the copy's texts
that is not made of names only (`Bøje savnet og fyr slukket`). Whole texts and dictionary values
count in any case (`kladde` set in capitals by CSS), except a text of one to three capitals, which
is a code (`NY`); a soft hyphen or a zero-width character inside a word hides nothing. The Danish
values come from the database: every dictionary's Danish rows and the Danish rows `english.sql`
deletes are kept in `guides.danish_saved`, and the view `guides.danish_text` adds the Danish rows
the copy keeps (areas, publications, messages); the base product's English bundle is never Danish.
A text on one of the two topics the owner's standing ruling keeps out of the guides fails a picture
too (`forbiddenTopic` in `support/danish.ts`). After the second fix the guard was measured on the
3,052 distinct English texts of the live messages, areas, charts and publications, the 301 English
names of the live messages' positions, the 2,618 texts of the English bundle, the 17,696 titles,
subjects and position names of the messages the copy's rule accepted in any status, and the 7,759
lines of the 262 screens captured then: it refused 3 live texts, all almanac parts: one holds the
Danish word `farvandsudsigter`, two cite the Danish-only publication `Afmærkning af danske farvande`
by its Danish title (a name; a picture of them allows it). The fourth check (2026-11-11) added the
words and whole texts the third found in pictures the guard had passed, and stopped there: the
English interface cannot show a Danish interface word that is in neither the copy's data nor its
dictionaries, so the lists are not extended with invented words. A stored file name is not Danish
text, but `Isbilag-2026.pdf` holds the content word `isbilag`: a spec that must show the ice
annex's file allows it with `guard: { allowDanish: [{ text, reason }] }`, as it allows any text it
must show.

What the guard cannot see, and how the rig keeps it English instead. The guard is a net, not the
check: every picture is still looked at by a person before it goes into a guide.

- Text drawn on a canvas: a map's labels (a position's name, a place). The guard reads no canvas, so
  a map shows only data the copy's rule has checked: the example messages are English throughout,
  the names of their positions included (below), and `english.sql` gives every position an English
  name (the firing areas' `Signalmast.` is `Signal mast.` since the fourth check). The base map's
  own labels are the map's, and may be Danish place names.
- A Danish interface word that is in neither the copy's data nor its dictionaries: the guard knows
  the data's Danish values and a list of interface words, but the English interface takes its words
  from the base product's English bundle, so it has no other Danish word to show.
- MailHog's list of mails: the copy's mailing lists sent Danish mails before `english.sql` made every
  recipient English. `openNewestMail` first deletes the mails whose subject is Danish and whose
  every recipient is on `example.org` (`deleteDanishMail` in `support/mailhog.ts`); a spec that
  photographs MailHog's list calls it too. Another local stack's mails are left alone.
- Script resources: the templates under `templates/tmpl/` hold Danish parameters by design (a
  template writes both languages: `daDetails="er ikke på plads og i orden"`); the guard refuses a
  drawer that shows one, so a picture of Sysadmin > Script Resources keeps to other rows.
- The local realm's group `MAS-vagt` (`vagt` is Danish for watch, duty). Since the fifth check
  (2026-11-11) the guard refuses it (`vagt` is in `DANISH_CONTENT_WORDS`, with a pilot case), so no
  picture that shows it passes where the group still has that name: the Groups tab of a user's drawer
  (Admin > Users & Contacts, its Available groups) and Keycloak's Groups page, which `shot` guards
  as it guards every page. Nothing refers to the group by its name: not the backend (it lists a
  user's groups and joins one by the group's id), not the frontend, not the copy's data, and no
  start script (the launcher imports `be/keycloak/niord-realm-local.json` only into a new Keycloak
  volume). On this workstation the group was renamed `MAS duty` on 2026-11-11, with the owner's
  approval, in the shared local Keycloak, which every local stack signs in to. A new Keycloak
  volume imports the realm file again and holds `MAS-vagt` once more (`be/docs/potential-errors.md`,
  REALM1), so the rename is repeated there. With Keycloak's admin token in `$TOKEN` (the master
  realm's `admin-cli` password grant, admin / admin), it keeps the group's id, attribute and roles:

  ```bash
  curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    http://localhost:8080/auth/admin/realms/niord/groups/93c432b4-5513-4b9b-b003-a255e17cb59f \
    -d '{"name":"MAS duty","attributes":{"editorFieldsNw":["{ \"publication\": false, \"references\": false }"]}}'
  ```

  The same call with `"name":"MAS-vagt"` puts the old name back.

The example messages a spec picks (`publishedMessageId`, `messageIdWithStatus` in
`support/backend.ts`) are English throughout by the copy's own rule, `guides.english_messages`,
which `english.sql` makes and the rig reads through psql in the database container
(`GUIDES_DB_CONTAINER`, `GUIDES_DB`, `GUIDES_DB_USER`; read only): their English texts, the names of
their positions as the administration app shows them, and their audio and NAVTEX texts. Every live
message (draft, verified, published) was read by a person in the fourth check (2026-11-11), its
Danish put right by `english.sql`, and the rule picks every one of them but the few
`guides.not_english` lists with a reason (`english.sql` stops when it would turn one down). A
message no longer live (expired, cancelled, deleted) was read by no one, so the rule takes it only
when, besides, none of its English titles, vicinities, part subjects and position names is the
Danish one word for word (apart from names) and its NAVTEX text holds no Danish word: of the 51,115
old messages it keeps 47,575 (`data/README.md` has the counts by domain and status).

An e-mail address outside `example.org` on a picture (in the page, in any of its frames, or in the
text of a PDF page) is recorded, not refused: the test title and
the address, masked to its first two characters, are appended to `mail-report.txt` beside the
pictures. Organisations' mailboxes in published notices may show; a private person's may not, and
the report is where a reviewer tells them apart. `GUIDES_STRICT_MAIL=1` refuses such a picture
instead. Only what the picture shows counts: visible text (as above) within the picture's clip (the
window, fitted, for a picture of the window), read a block at a time, so an address split by inline
tags is read whole and a block the clip's edge cuts is read whole, and not covered by a layer
painted above it with a solid, fully opaque background (a dialog over the list behind it, a sticky
bar over the page scrolled under it); a translucent scrim covers nothing, and a text the browser
cannot place counts as shown. A PDF page's text lies, transparent, where the page shows it, so a
crop that leaves out the footer leaves out its mailbox. A picture taken again replaces its line in
the report, or takes it out when it shows no address any more.

## Where things live

- `playwright.config.ts`: the project. Chromium only, one worker, no retries, a 1280 × 800 viewport
  at device scale 2, `en-GB`, `Europe/Copenhagen`, light colours, reduced motion. It signs in to the
  local realm only, as its users `sysadmin`, `admin`, `editor` and `user`. A spec that needs another
  window (a phone, a drawer taller than 800 px) sets it with `test.use` in a `describe` of its own.
- `support/`: the helpers. `loginAs` (the end-to-end suite's password grant and token seeding, plus
  the session domain); `shot` (the picture, below); the stabiliser (hides the deployment badge and
  ribbon, toasts, the caret and scrollbars; stops animations; waits for the network, the fonts and
  every map's last tile; a picture keeps its toast with the class `guides-show-toasts` on the root
  element, and the badge with `guides-show-badge`); `fixClock` (a page that shows "now");
  `showMapAt` (moves every map to a fixed centre and zoom, so a map shows the same waters on every
  run instead of fitting itself to whatever is published); the guard; `openPdfFirstPage`; the MailHog and Keycloak console helpers;
  and the map tile cache (`.tile-cache/`, gitignored): every OpenStreetMap tile is fetched once and
  served from disk afterwards, because the tile servers re-render a tile when its data changes and
  a map taken twice would otherwise differ. Delete the folder to take current tiles.
- `shot()` takes the window, or one or several elements with a margin. A picture of the window ends
  above a line of text, a row or a control its bottom edge cuts through, and just below the last
  content when the window ends in empty space. An element is measured by what it paints (its text,
  borders, backgrounds, images and the content of a frame), not by its layout box, and the margin
  around it is painted over with a plain colour (the colour behind it), so no part of a neighbour
  shows at the edges; `keep` names what else stays visible (a menu's header). `hide` takes elements
  out of the layout before the guard reads the page, so a text the picture leaves out does not
  refuse it. A focused button or menu item loses its focus ring. Every picture's size is recorded
  in `image-sizes.json` beside the pictures: its CSS width and height and the width to print it
  at, the same share of its CSS width for every picture (`PRINT_SCALE`, 0.625: the whole window
  prints 800 px wide), so text is the same size in every figure.
- `*.shots.ts`: one spec per manual or subject: `editor-manual`, `admin-manual`, `sysadmin-manual`,
  `public-site`, `integration-docs`, `keycloak`, and `pilot`.
- `manifest-check.mjs`: the checklist comparison. The manifests (one JSON file per manual group,
  each picture with its manual, file and disposition) are read from `GUIDES_MANIFEST_DIR`, by
  default `guides/manifest/` at the repository root.
- `start-rig.sh`: step 2, and `serve-built.mjs`, the file server it starts (the two built
  applications on their ports, `/rest` passed to the backend, and for the administration app also
  `/img/` and `/css/templates/` when the build has no such file, as a deployment's web server
  does; deep links answered with the page).
- `data/`: step 1, the scripts that make `niord_guides` and `~/.niord-ng-guides`
  (`make-guides-db.sh`, `scrub.sql`, `check.sql`, `check-names.sh`, `prepare.sql`, `english.sql`,
  `english-history.mjs`, `report.sql`, `dict-diff.js`, `make-guides-home.sh`), with their README,
  and `seed-examples.mjs`, the example rows made through the backend once the rig is up.

Every address and folder can be set in the environment: `GUIDES_ADMIN_URL`, `GUIDES_INFO_URL`,
`GUIDES_BACKEND_URL`, `GUIDES_KEYCLOAK_URL`, `GUIDES_MAILHOG_URL`, `GUIDES_DOMAIN` (the session
domain, `niord-nw`), `GUIDES_NOW` (the clock of a page that shows "now"), `GUIDES_DIR` (the guides'
folder), `GUIDES_PILOT_DIR`, `GUIDES_ALLOWED_MAIL_DOMAINS`, `GUIDES_STRICT_MAIL` and
`GUIDES_TILE_CACHE` (the tile cache's folder, or `off`).

## What cannot be scripted

- Drawings: the message status diagram, the package and application structure, infrastructure
  diagrams, logos, and any arrow or numbered call-out on a picture.
- A file system view (the batch job folders) and a post on a social network.
- A mail as a mail program shows it: the rig shows it in MailHog's web page.
- A screen the data does not hold: a state the scrubbed copy lacks (a publication issue in a given
  state, a running batch job, a second Niord to import from) needs a set-up step first, against
  `niord_guides` only.
- Keycloak's console looks like the Keycloak version the compose file runs, not like Niord.
- The server's clock: `fixClock` fixes what the browser calls "now", but what the backend selects
  as in force follows the day of the run, and what it prints follows the second. On a PDF page the
  rig writes each time stamp shaped like the report templates' (`Nov 11, 2026, 10:09:29PM`, alone
  in its text or after `Generated`, as the public report's header prints it) again with
  `GUIDES_NOW`, so the picture is the same on every run; a time stamp in any other shape is not.
