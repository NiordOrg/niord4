# Niord guides

The sources of the Niord user guides, published at <https://docs.niord.org>. This folder is their one
source: the site is built from here, and the NiordOrg/docs.niord.org repository only receives the
built result.

| Folder | What | Built to |
|---|---|---|
| `editor-manual/` | User manual for editors | `editor-manual/manual.html` |
| `admin-manual/` | User manual for administrators | `admin-manual/manual.html` |
| `sysadmin-manual/` | User manual for system administrators | `sysadmin-manual/manual.html` |
| `whats-new/` | What changed since the old Niord | `whats-new/whats-new.html` |
| `public-site/` | The public site, a guide for mariners (once it exists) | `public-site/<master>.html` |
| `model/` | The Niord message model (integration) | `model/model.html` |
| `public-api/` | The public REST API (integration) | `public-api/api.html` |
| `presentation/` | How a client presents Niord messages (integration) | `presentation/presentation.html` |
| `dev-guide-v4/` | The developer guide of the current Niord | `dev-guide-v4/guide.html` |
| `dev-guide-v2/`, `dev-guide-v3/` | Development guides of earlier Niord versions, kept as an archive: their text is not edited, only a note at the top that points to the current guide | `dev-guide-v2/guide.html`, `dev-guide-v3/guide.html` |
| (none: `../country-kit/`) | The country kit's guides, rendered from the kit (below) | `country-kit/index.html` and `country-kit/docs/*.html` |
| `index.html`, `stylesheets/`, `images/`, `CNAME` | The landing page and its files | copied as they are |
| `manifest/` | The pictures' checklist (below) | nothing |
| `_parked/` | Text kept but not built (below) | nothing |
| `build.sh`, `build-tools.mjs`, `check-links.mjs` | The build, its helpers and the link check | nothing |

Each guide is AsciiDoc: a master document (`manual.adoc`, `guide.adoc`, `model.adoc`, ...) that
includes one file per chapter, with the chapter's pictures in the folder's `images/`.

## Building

```bash
./build.sh          # the HTML site, in _site/
./build.sh --pdf    # the HTML site and a PDF of each guide
./build.sh --preview  # the site for a folder of docs.niord.org that nothing links yet (see below)
```

From Git Bash on Windows or bash on Linux. It needs Docker and Node, nothing else: Asciidoctor,
kramdoc and Graphviz run from one pinned image (`asciidoctor/docker-asciidoctor`, tag and digest at
the top of `build.sh`), so every machine and CI produce the same output, and the build downloads
nothing else. Open `_site/index.html` to read the result.

What the build does, in order:

1. **Finds the guides.** Every folder here is a guide (a sub-site) when it holds a master
   document: an `.adoc` file at the folder's top level with a document title, a line `= Title`.
   The chapters it includes start at `==` and are not masters. Folders starting with `_` or `.`,
   and `images/`, `stylesheets/` and `manifest/`, are never guides. So a new guide is a new folder
   with its master document: `build.sh` needs no edit. `node build-tools.mjs subsites` lists what
   the build will find.
2. **Copies** the landing page and its files, and each guide's `images/` folder, into `_site/`.
3. **Renders the country kit's guides** into `_site/country-kit/` (below).
4. **Builds each master document** to `_site/<folder>/<master>.html` (and `.pdf` with `--pdf`),
   with its text diagrams (below). An Asciidoctor error, such as a missing include, fails the
   build; warnings are printed and counted.
5. **Fills in the landing page.** An element of `index.html` with `data-subsite="<folder>"` stays
   only when the build produced that guide, and `@master` in its links becomes the folder's master
   document. So an entry can be written before its guide exists, and the landing page never links a
   page that is not there.
6. **Checks the site** with `check-links.mjs`: every relative link, image and `#fragment` between
   the built pages must resolve, no page may define an id twice, and every target of the
   application's help map must exist (see the anchor rule below). External addresses are not
   fetched. The archived development guides only warn.
7. **Checks what may be published.** No page, the archive's included, may name an internal host
   (anything under `sitcloud.dk`, the old `t-dma.dk` test hosts, the old `niord.dma.dk` and
   `nautiskinformation.soefartsstyrelsen.dk` addresses), a person's home folder (`C:\Users\...`,
   `/c/Users/...`) or a staff account's user id (`FORBIDDEN` in `check-links.mjs`); a hit fails
   the build on any page. Outside the archived development guides, no page may name SECOM or
   S-124, which the guides leave out, link a GitHub address other than the organisation itself,
   `https://github.com/NiordOrg` (no deep link into a repository, no other organisation), or name a
   country's repositories or packages (`SIFA-DK`): `RULED_OUT` in `check-links.mjs`. A hit fails
   the build there; in the archive, whose text is kept as it was written, it is listed as a warning
   and the page is published. So a current guide that breaks either list cannot be published by
   accident.

The output is never committed: `_site/` and `_kit/` (the kit's guides on their way) are ignored by
git. The `Guides` workflow (`.github/workflows/guides.yml`) builds the site on every pull request,
drafts included, that touches `guides/`, the kit's guides or the help map, and keeps it as a
downloadable artifact; it deploys nothing.

**Publishing in a folder, before the root.** Until the new system is live, docs.niord.org's root
keeps the manuals of the system in production, and these guides are published beside them in the
folder `new/` of the `NiordOrg/docs.niord.org` repository (GitHub Pages serves its `master` branch):
`./build.sh --preview` marks every page `noindex, nofollow` and leaves the `CNAME` out, and the
contents of `_site/` become the contents of `new/`. Every link between the pages is relative, so
the site works in any folder. A deployment's help buttons open it when its setting
`documentationUrl` (Sysadmin > Settings) is `https://docs.niord.org/new`; the code's default stays
the root address, which is where the guides go at go-live (a build without `--preview`, into the
repository's root).

No other CI job checks the pictures out: every other checkout in `.github/workflows/` is a sparse
checkout that leaves out each folder named `images` under `guides/` (the pattern
`!/guides/**/images/`, explained in `be-ci.yml`'s header under "What a job checks out"), and
`fe/scripts/ci-checkouts.test.mjs` fails one that does not. So a picture goes into its guide's
`images/` folder: one kept anywhere else under `guides/` is downloaded by every CI job until the
pattern is widened to its place.

### Diagrams

A diagram is text in the guide's source, never a picture drawn by hand. Two kinds work:

- A literal block (`....`) with a box-and-arrow drawing in plain characters, which shows as it is.
- A diagram block of the image's `asciidoctor-diagram` extension, which `build.sh` loads
  (`-r asciidoctor-diagram`): PlantUML, Graphviz, Ditaa, ASCIIToSVG and the blockdiag family, for
  example `[plantuml, message-model, svg]` above a `----` block. The build draws it as
  `_site/<folder>/images/<name>.svg`, for the HTML and the PDF alike, so give each diagram a name
  of its own within its guide, one no picture in `images/` has. Nothing is written into this folder.

Mermaid cannot be rendered: the image has no Mermaid, and the build downloads nothing. The kit's
guides get their Mermaid flowcharts converted to Graphviz (below); a guide here uses PlantUML or
Graphviz instead.

## The country kit's guides

The country kit's guides are Markdown, written for people building a country on the base product,
and they ship in the kit's package (`org.niord:niord-country-kit`; what it holds is
`../country-kit/kit-include.json`). They are rendered onto the site from where they are, never
copied into this folder, so the site always shows the kit of the commit it was built from. Nothing
here edits them.

`build.sh` does it in three steps, with `build-tools.mjs`:

1. **`kit-prepare`** (Node) takes the candidates: `../country-kit/docs/*.md`, the kit's
   `README.md` and `API-CHANGES.md`, `docs/country-api.md` (which the kit's build copies from
   `../be/docs/country-api.md`) and `templates/AGENTS.md`. **A guide that names SECOM or S-124 is
   left out**, and the build says so with the file and line. In each guide it keeps, it rewrites
   the links: a link to another guide on the site points at its page, a link to
   `https://docs.niord.org/...` becomes relative (so the link check covers it), a link to a guide
   left out becomes its text followed by the guide's file ("in the kit package's
   `docs/country-guide.md`"), which the page header says is read in the kit package only, and a
   link to anything else of the kit (a script, a schema) becomes its text. Each Mermaid
   flowchart becomes a Graphviz file. It also writes the set's index page from the reading order in
   the kit's own `docs/README.md` ("Who reads what"), naming each guide left out as a file of the
   kit package.
2. In the pinned image, **kramdoc** turns the Markdown into AsciiDoc and **dot** draws the
   diagrams as SVG.
3. **`kit-finish`** (Node) gives every heading the id GitHub gives it, so the guides' own
   `#links` keep working; puts each diagram in as a picture with its content in words as the
   alternative text, or as its Mermaid source when the flowchart uses more than the converter
   knows; and adds the page header (the way back to the index, and where the guide comes from).
   Asciidoctor then renders the pages with the guides.

Before a kit guide goes public it is read for internal host names and staff names like any page
(step 7 above catches the hosts, and a country's repositories and GitHub addresses, which would
fail the build the day a guide left out for SECOM loses that mention; names are a reviewer's eye).
Change the kit's guides in `../country-kit/`, never in `_kit/`.

## Pictures

Every screenshot is taken by the screenshot rig, `../fe/guides-shots/` (its README), from the
manifests in `manifest/`: one JSON file per group of guides, one entry per picture, in document
order. An entry names its `manual` (the guide's folder), its `chapterFile` and `line`, its `file`,
its `caption`, what it `shows`, and how the rig takes it (`app`, `route`, `role`, `steps`, `crop`,
`needsData`, `note`). Its `disposition` says what happens to it: `keep`, `retake` (the same picture
again from the new system), `replace` (a new picture under a new name, in `newFile`), `new`,
`redraw` (a diagram, to be redrawn as text), or `drop` (the picture leaves the guide: its passage
was rewritten or removed, another entry's picture took its place, or it was redrawn as a text
diagram; the `note`, or a `done` field, says which). The rig takes only the `retake`, `replace`
and `new` entries; a `keep` or `drop` entry is a record of the guide's pictures, not work.

**A picture still to be taken** is declared in two places:

- in its group's manifest, as an entry with the disposition `new` (or `retake` or `replace`),
  `line` 0 for a new one, and enough in `shows`, `steps` and `needsData` for the rig to take it;
- in the chapter, at the place the picture goes, as a comment line, not an image macro:
  `// Picture to take: PublicSiteMapPage.png (manifest/other-guides.json)`.

The comment becomes `image::PublicSiteMapPage.png[The Map view]` in the change that adds the file
to the guide's `images/`. An image macro naming a file that is not there fails the link check, so a
picture that was never taken cannot reach the site. `npm run shots:check` in `fe/` compares the
manifests with the rig's specs.

## The anchor rule: a help target keeps its id

The administration application's help buttons open `<documentation URL>/<manual>/manual.html#<id>`,
for the editor, admin and sysadmin manuals. The contract is one file,
`../fe/ui-apps/admin/src/app/core/main-shell/help/help-urls.json`:
a flat map of help key to `/<manual>/manual.html#<id>`. The link check reads it after every build
and fails, naming the key and its target, when a target names a page the build did not produce or
an id that page does not have.

- Every section a help link points at carries an explicit id in the source (`[#messages]` on the
  line above the heading), so rewording a heading does not move it.
- Never remove or rename such an id, or move a manual, without changing `help-urls.json` in the
  same change.
- A new help target gets an explicit id the same way, and its key goes into `help-urls.json`.

The kit's operators' guide links the same manual sections; since its links are made relative, the
link check covers them too.

Cross references inside a guide use ids (`<<message-tags,Message Tags>>`); links between guides are
relative (`link:../admin-manual/manual.html#areas[Areas]`), so a local or preview build links to
itself and the link check covers them.

## Parked text

`_parked/secom/` holds the sysadmin appendix on SECOM certificates and MCP identities with its
pictures. It is not built into any manual: SECOM and S-124 are not in production yet, and the
standards are still changing. The check in step 7 fails any built page outside the archive that
names them.
