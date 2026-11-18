# Accessibility: legal scope, current state and recommendations

Investigated 2026-09-19. This is an analysis. No code was changed, and no page has been audited yet.

This is not legal advice. The reading of the scope below should be confirmed in writing with
Digitaliseringsstyrelsen before it is relied on.

## Summary

- Niord falls under the Danish web accessibility act, including the employee-only administration
  tool. The exemption for internal systems only covers systems published before 23 September 2019,
  and it ends when the system is replaced. The new frontend is a replacement.
- The requirement is EN 301 549 version 3.2.1, which for web content means WCAG 2.1 level A and AA.
- Generated PDFs are in scope. The PDFs Niord produces today cannot comply, because the rendering
  library writes untagged PDFs. The library has to be replaced.
- Maps are largely exempt, provided the essential information is also available as accessible text.
- The frontend was moved off PrimeNG onto Angular's headless building blocks. Focus handling, menus,
  trees and tabs still come tested from Angular. The select, autocomplete and date picker are now
  hand-written, declare the right roles, and have never been verified with assistive technology.
- There is no automated accessibility check anywhere in the repository or in CI.
- An accessibility statement in the agency's WAS-Tool is required.

## 1. Legal scope

### The rules

| What | Reference |
|---|---|
| The act | Lov nr. 692 af 08/06/2018 om tilgængelighed af offentlige organers websteder og mobilapplikationer, in force 23 September 2018 |
| Amendment | Lov nr. 429 af 2021, in force 1 July 2023: content supplied by another public body is not third-party content |
| Technical standard | Bekendtgørelse nr. 2180 af 26/11/2021, pointing at EN 301 549 version 3.2.1 (2021-03) |
| Statement and monitoring | Bekendtgørelse nr. 904 af 22/08/2019 |
| Underlying directive | Directive (EU) 2016/2102 |
| Supervising authority | Digitaliseringsstyrelsen |

EN 301 549 version 3.2.1 is the version linked from the agency's legislation page. Its chapter 9
applies the WCAG 2.1 level A and AA success criteria to web content, and its chapter 10 applies them
to documents.

### The employee-only administration tool is covered

Section 1, stk. 1 applies the act to public bodies' "websteder" with no condition that they face the
public. The phrase "til brug for den brede offentlighed" appears only in the definition of mobile
applications in section 2, not for websites.

The agency's guidance states:

> Både intra- og ekstranet skal betragtes som websteder og er omfattet af lov om webtilgængelighed.

The same guidance treats case and specialist systems, "sags- og fagsystemer", as intranets or
extranets when only a closed group can reach them. That describes the Niord administration tool.

The one exemption is section 1, stk. 5, nr. 7:

> Indhold på ekstranet og intranet, som er offentliggjort inden den 23. september 2019, indtil sådanne
> websteder undergår en væsentlig revision.

The agency describes the end of that exemption as the point where the site "bliver udskiftet eller
undergår markante ændringer i design, interaktion, funktionalitet eller formål". It lists what does
not count: new design on single elements, a new logo, minor colour corrections, and a change of
technical platform that keeps the existing design, interaction, functionality and purpose.

The new Niord frontend replaces the previous one with a new design, new interaction and new
functionality. That is a replacement, not a platform change, so the exemption does not apply.

The frontend also contains a public message browser and map, which is covered without any of the
above reasoning.

### Documents and PDFs are covered

Section 1, stk. 5, nr. 1 exempts only old documents:

> Dokumentformater, der er offentliggjort inden den 23. september 2018, medmindre indholdet er
> nødvendigt for aktive administrative processer i forbindelse med de opgaver, som det offentlige
> organ varetager.

The directive's recital 26 names PDF and Microsoft Office files as the examples of this category.
The agency's guidance confirms that documents reachable from a covered website must be accessible,
and adds that PDF/UA is a good starting point but does not stand alone, because EN 301 549 sets the
full requirements for documents.

Every PDF Niord generates after that date, and that ends up downloadable from a covered website, is
in scope. That includes publications and reports.

### Maps are largely exempt

Section 1, stk. 5, nr. 4 exempts:

> Onlinekort og kortlægningstjenester, når væsentlige oplysninger gives på en tilgængelig digital måde
> omkring kort, der er bestemt til navigationsbrug.

The agency adds that a map serving purely as a geographic description is always exempt, and that a
map for navigational use needs its essential information given in another accessible digital form.

For Niord this means the map itself does not have to be operable with a screen reader, as long as a
message's position, area and details are complete as text next to it. Two points need confirming:

- whether every piece of information shown only on the map also exists as text
- whether the map-based geometry editor has an adequate non-map way to enter and edit positions

### Disproportionate burden

Section 3, stk. 2 lets a public body leave out a requirement that would be an "uforholdsmæssig stor
byrde". The assessment weighs the body's size, resources and nature, and the estimated costs against
the estimated benefit for people with disabilities, taking into account how often and for how long
the site is used.

A specialist tool with few users strengthens that argument for individual hard items. It is assessed
per requirement and declared in the accessibility statement. It cannot cover the system as a whole.

### The accessibility statement

A covered website needs its own accessibility statement, the tilgængelighedserklæring, created in the
agency's WAS-Tool. A self-evaluation is an accepted basis, so an external audit is not mandatory. The
statement must describe how the evaluation was done.

Each application has a place to link its own statement: the public site from `accessibilityStatement`
in its country configuration, the administration app's footer from the branding keys
`footer.accessibility` and `footer.accessibilityLink`. Both stay empty until a country sets them.

## 2. Current state of the frontend

### Foundation

The frontend was refactored away from PrimeNG when its components became a paid product. It now uses:

- `@angular/cdk` for behaviour without visuals: overlay, portal, menu, tree, drag-and-drop and the
  accessibility module
- `@angular/aria` for accessible headless patterns, used in one place, the tabs
- Lucide icons and Tailwind CSS for the look

Everything is wrapped once under `fe/ui-apps/admin/src/app/shared/`, about 24 shared components plus the form
controls, and features consume only those wrappers.

### What still comes tested from Angular

- Focus traps in dialogs and drawers, focus return to the trigger, and the live announcer, from the
  CDK accessibility module, used in eight places
- Keyboard navigation and ARIA roles for menus and trees, from the CDK
- Roles and arrow-key behaviour for tabs, from `@angular/aria`
- Static template checks: the Angular ESLint accessibility rule set is on, and the lint job in CI
  fails on missing labels, click handlers without keyboard equivalents and similar mistakes

### What is now the project's own responsibility

A component library ships tested ARIA roles, keyboard handling and screen reader behaviour for every
widget. These are now hand-written in `fe/ui-apps/admin/src/app/shared/forms/`:

- the select dropdown
- the autocomplete
- the date picker with its calendar
- the multi-select and tag fields built on them

All three base widgets declare `combobox` and `listbox` roles and have keyboard handlers. The
accessibility is not missing. It is unverified. Combobox and date picker are the two patterns most
often subtly wrong in custom code, for example in how the active option is announced, or how the
calendar grid is navigated. Nobody has tested these with a screen reader.

### What does not exist

- No automated runtime accessibility check. `fe/docs/testing.md` states there is no axe, pa11y or
  Lighthouse dependency and no CI step.
- No record of a manual audit against WCAG 2.1.
- No way but a drag to reorder a message's references in the editor (`references-field-editor.ts`): no
  keyboard way and no single-pointer way (WCAG 2.1.1, 2.5.7). The attachments' grip has arrow keys since
  2026-11-13, but no single-pointer way either. Seen by the error hunt's fix stage (2026-11-13).

The project's own Definition of Done already requires WCAG AA, so the intent is in place. The
verification is not.

## 3. Current state of generated PDFs

### The requirement

The precise technical standard is PDF/UA, ISO 14289, plus the WCAG criteria that PDF/UA does not
judge, mainly colour contrast and whether texts are meaningful. An accessible PDF needs:

- tags: a structure tree marking headings, paragraphs, lists and tables with header cells, in the
  correct reading order
- the document language, and language marks on passages in another language, which matters for
  Niord where Danish, English and Greenlandic can share one document
- a document title in the metadata
- alternative text on meaningful images, and decorative ones marked as artifacts
- embedded fonts with proper Unicode mapping
- bookmarks for longer documents, and real links
- sufficient colour contrast

### The finding

Niord renders PDFs with Flying Saucer 9.7.2 on iText 5, in
`be/niord/src/main/java/org/niord/core/script/pdf/HtmlToPdfRenderer.java`. That combination writes
untagged PDFs and has no tagging support. A screen reader sees such a file as a flat stream of text
with no headings and no tables. Template work cannot fix this. The library has to be replaced.

### Verification tools

| Tool | What it is | How it fits |
|---|---|---|
| veraPDF | Open source, the reference PDF/UA validator, command line | Can run in the backend tests and in CI, validating every generated report on each build |
| PAC, PDF Accessibility Checker | Free, Windows only, the tool most auditors use | Run by hand; also gives a screen reader preview of the structure |
| Adobe Acrobat Pro | Built-in accessibility checker | An alternative where a licence already exists |

What stays manual is small: whether the reading order makes sense, and whether alternative texts are
meaningful. For generated reports with fixed templates that is a one-time review per template.

## 4. Recommendations

### A. Audit the frontend page by page

An internal pass can cover most of the work before anyone external is paid:

- an axe-core scan through Playwright on every route, in both UI languages and in light and dark
  mode. Automated scanning reliably finds roughly a third of WCAG issues: contrast, missing names
  and labels, invalid ARIA, heading and landmark structure, language attributes
- a template review against the level A and AA criteria, for what scanners miss: focus handling in
  overlays, error messages tied to fields, status messages, keyboard operability of custom controls
- scripted keyboard walkthroughs: focus order, visible focus, focus traps, every action reachable
  without a mouse
- reflow at 320 pixels wide, 200 percent zoom and increased text spacing
- a report per page mapped to each success criterion, with pass, fail or needs human check

Look hardest at the three hand-written form widgets, the map, and the geometry editor.

### B. Move the select and autocomplete onto `@angular/aria`

`@angular/aria` ships tested combobox and listbox patterns. Rebuilding the shared select and
autocomplete on them hands the semantics, keyboard model and screen reader behaviour back to the
Angular team, the way the tabs already are. Because both are shared wrappers defined once, the change
lands on every page at the same time, and the multi-select and tag fields built on them inherit it.

Two conditions:

- Do the audit in A first. It shows whether the current widgets have real defects, and it gives a
  before and after to compare.
- Check what `@angular/aria` offers for a date picker. If it has no calendar grid pattern, the date
  picker stays hand-written and needs the most careful manual testing of the three.

### C. Replace the PDF renderer

1. Swap Flying Saucer and iText 5 for a renderer with PDF/UA support. openhtmltopdf is the natural
   candidate: it is a fork of the same code base, so the FreeMarker templates and CSS largely carry
   over, and it has a built-in PDF/UA mode that writes tags from the HTML structure. Check how
   actively its maintained fork is developed before committing to it.
2. Port the custom element handling in `HtmlToPdfRenderer`.
3. Make the report templates semantically correct, because the tags come from the HTML: headings in
   order, table header cells, a language attribute, a title, alternative text on images.
4. Embed the fonts explicitly.
5. Confirm the existing password-encryption option still works.
6. Compare output visually with today's PDFs, since pagination and spacing may shift.
7. Add veraPDF validation to the backend tests so a regression fails the build.

This is a `pom.xml` change, which the backend conventions require to be deliberate and explained.

### D. Add automated checks to CI

- an axe-core pass in the frontend tests for the shared components, which needs no backend
- veraPDF in the backend tests, from recommendation C
- an axe-core pass over full pages once the end-to-end suite can run against a local stack

### E. Bring in people for what tools cannot judge

- a screen reader pass with NVDA or JAWS on the main journeys, in-house or by a specialist
- one external audit before go-live. For a system that the agency can inspect, this is a sensible
  investment, and it costs much less after A to D are done.
- an evaluation done only by an AI tool is weak evidence in an inspection. Treat the internal pass
  as the review and fix list, and the human passes as the compliance evidence.

### F. Settle the open questions with the agency

- confirmation that the scope reading in section 1 is correct for the administration tool
- whether the geometry editor's map-based editing needs a specific alternative
- which parts, if any, could reasonably be declared a disproportionate burden

### Suggested order

1. F, in parallel with everything else, since answers take time
2. A, the frontend audit
3. C, the PDF renderer, which is independent of the frontend work
4. B, informed by what A found
5. D, as each area is fixed
6. E, last, and then the accessibility statement in the WAS-Tool

## Sources

- [Lov nr. 692 af 2018 om tilgængelighed af offentlige organers websteder og mobilapplikationer](https://www.retsinformation.dk/eli/lta/2018/692)
- [Bekendtgørelse nr. 2180 af 2021, the technical standard](https://www.retsinformation.dk/eli/lta/2021/2180)
- [Bekendtgørelse nr. 904 af 2019, statement and monitoring](https://www.retsinformation.dk/eli/lta/2019/904)
- [Digitaliseringsstyrelsen: Lovgivning om webtilgængelighed](https://digst.dk/tilsyn/webtilgaengelighed/lovgivning/)
- [Digitaliseringsstyrelsen: Spørgsmål og svar om webtilgængelighed](https://digst.dk/tilsyn/webtilgaengelighed/spoergsmaal-og-svar-qa/)
- [Directive (EU) 2016/2102](https://eur-lex.europa.eu/LexUriServ/LexUriServ.do?uri=CELEX:32016L2102:EN:HTML)
- [EN 301 549 version 3.2.1](https://www.etsi.org/deliver/etsi_en/301500_301599/301549/03.02.01_60/en_301549v030201p.pdf)
- [WCAG 2.1](https://www.w3.org/TR/WCAG21/)
