# Accessibility audit of the public site

Audited 2026-11-18, against WCAG 2.1 level A and AA, which EN 301 549 version 3.2.1 applies to web
content (see [accessibility-findings.md](accessibility-findings.md) for the legal scope). This is
recommendation A of that document, carried out for the public site (`fe/ui-apps/info/`, Angular
project `niord-info`). The administration app is not part of it.

**State: the base build is audited. The Danish and the Greenlandic builds are not.** An
accessibility statement is made per site, after an evaluation of that site, and a reader never sees
the base build: each country's build has its own footer, legal documents, statement link, colours,
languages and area filter. Until each country's build has been run through the steps under
[What remains](#what-remains), this record backs no statement. A page changed after this date is
looked at again before a statement is dated.

## How the evaluation was done

| What | Value |
|---|---|
| Date | 2026-11-18 |
| Code | `main` at commit `3481b0b`, with no application code changed |
| Build | the base product's public site in its `production` configuration (`ng serve niord-info --configuration production`), against the backend of the sample deployment (`./start-local.sh --sample`) |
| What the base build is | English only; the default tokens (`fe/styles/tokens.default.css`); no root areas, so no area row in the filter bar; the product's own footer with no legal document and no statement link; no sea-chart layer |
| Data | the sample deployment's messages and publications, which are made up and English in this build; no attachment among them, and no real PDF |
| Deployment mode | not production, so the corner ribbon that names a test site shows; a production deployment has none |
| Scanner | axe-core 4.14.0, `npm run a11y:info` in `fe/` (`fe/scripts/info-a11y-scan.mjs`), rule tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, and `best-practice` reported apart |
| Browser | Chromium 156 from Playwright 1.64.1, headless |
| Widths | the scan at 1280 × 800 and 320 × 640; the reflow checks also at 640 × 400 (200 % zoom of a 1280 × 800 screen), 320 × 200 (400 %) and 640 × 320 (a phone held sideways) |
| Keyboard | Tab, Shift+Tab, Enter, Escape and the arrow keys, driven through Playwright, reading on every stop the focused element, whether it is in view and on top, and its focus style |
| Text spacing | the WCAG 1.4.12 values injected on every page and in a dialog (`line-height: 1.5`, `letter-spacing: .12em`, `word-spacing: .16em`, `margin-bottom: 2em` on paragraphs), looking for clipped content and sideways scrolling |
| Contrast | where axe-core cannot decide (text over the chart), computed from the default tokens and the chart's own colours |
| Who | a coding agent ran every check above on a developer's machine; no person has reviewed the results yet |

Not done, and needed before a statement:

- **No screen-reader pass.** Nobody has used the site with NVDA or JAWS; what a scanner and a script
  read from the page is not what a screen reader announces. Recommendation E of
  [accessibility-findings.md](accessibility-findings.md) holds: an evaluation by a tool alone is
  weak evidence, so this record is the review and the fault list, and a person's pass is the
  evidence.
- **No external audit.**
- **No other browser.** The focus rings of most controls are the browser's own (in Chromium a
  two-tone ring that shows on any background); Firefox and Safari draw theirs differently.
- **No real content.** Long titles, attachments, the real PDFs, messages in a language other than
  the page's, and the map with many messages are not in the sample as the base build shows it.
- **No country build** (see [What remains](#what-remains)).
- **Dark mode does not apply.** The site has no dark tokens, and the `dark:` variant is bound to a
  class nobody sets (`fe/styles/theme.css`), so the site renders the same whatever the reader's
  system is set to.

## Results per page

The scan's states are the routes of `routes/create-niord-routes.ts` and `features/messages/messages.routes.ts`,
a message's dialog opened from the list, the Downloads page's second tab, and the message list
empty and failed (the list read answered with an empty list and with an error). The base build's footer has no document, so the
footer-document state was not scanned. "Rule" is an axe-core rule; a rule that axe-core could not
decide is under "needs a person". No WCAG rule failed on any page; what failed is best practice.

### `/messages/details` (the landing page)

- **Scan.** WCAG: nothing failing, nothing undecided. Best practice: `page-has-heading-one` (no
  `<h1>`); `region` on the corner ribbon, which lies outside every landmark and shows on a test
  deployment only.
- **Keyboard.** The skip link is the first stop and moves focus to the main content; then the four
  menu links, the language button, the four filter chips (the main types, active now, print), and
  each card's title and references. No trap: the walk ends past the last control. Every stop is
  in view and on top. Focus is visible on every stop.
- **Reflow and spacing.** No sideways scrolling at 320 px, 200 % or 400 %; at 400 % the bars are not
  pinned (also held by `npm run layout:info`). Nothing clipped with the text spacing.
- **Structure.** One `main`, one `nav`, a footer; headings start at level four.

### `/messages/details/message/<id>` (one message, opened in a dialog by its address)

- **Scan.** Nothing failing. Needs a person: `color-contrast` (1.4.3) on the chart's credit line,
  `link-in-text-block` (1.4.1) on its link, and `aria-hidden-focus` (4.1.2) on the page behind the
  dialog, which the dialog hides while it is open.
- **Keyboard.** Focus starts in the dialog on its close button and stays in it. Escape closes it and
  the address moves to `/messages/details`, but focus then falls to the page's body: **fail, 2.4.3**
  (there is no control it was opened from to return to).
- **Judged.** The credit link is told from its line by colour alone at 2.2:1: **fail, 1.4.1**. The
  credit's text holds 5.1:1 or more over the chart. `aria-hidden-focus`: the page behind a modal
  dialog is hidden and focus cannot reach it: pass.

### `/messages/table` (the list)

- **Scan.** WCAG: nothing. Best practice: `page-has-heading-one`, `region` on the ribbon.
- **Keyboard.** Menu, language, chips, then one stop per message row. Focus visible on each (a
  2 px ring, 10:1 against the page). No trap.
- **Reflow and spacing.** Pass at every width; nothing clipped.

### A message's dialog, opened from the list

- **Scan.** As for the one-message route: needs a person on the chart's credit, and the hidden page.
- **Keyboard.** Opened with Enter, focus starts on the close button; Tab and Shift+Tab stay in the
  dialog (the chart's zoom buttons, the credit link, the chart's layer control, the close
  buttons); Escape closes it and focus returns to the row it was opened from. The same at 320 px.
- **Reflow and spacing.** At 320 px the dialog fits the screen with no sideways scrolling; nothing
  clipped with the text spacing.
- **Judged.** The credit link: fail, 1.4.1, as above.

### `/messages/map`

- **Scan.** WCAG: nothing failing. Needs a person: `color-contrast` and `link-in-text-block` on the
  chart's credit. Best practice: `page-has-heading-one`, `region`.
- **Keyboard.** Menu, language, chips, the chart's zoom in and zoom out (a 1 px ring, 5.7:1), the
  credit link. The chart's messages are drawn on a canvas and are not reached by keyboard; each is
  also in the details and the list views. Whether everything the chart shows is also given as
  text is the map exemption's question, still open in
  [accessibility-findings.md](accessibility-findings.md) section 1.
- **Reflow.** The chart fills the view at every width, and at 400 % the bars scroll away. The panel
  of messages without a position covering the zoom buttons on a phone (backlog) did not show with
  the sample's messages; the entry stays.
- **Judged.** The credit link: fail, 1.4.1.

### `/messages/print` (the print sheet)

- **Scan.** WCAG: nothing. Best practice: `landmark-one-main` (no `main`), `page-has-heading-one`,
  and `region` on every part of the sheet, which follows from the missing `main`.
- **Keyboard.** The only stops are the link-styled buttons inside the message cards, and each loses
  its focus ring (`outline: none`): **fail, 2.4.7**, an entry of the backlog already.
- **Reflow and spacing.** Pass.

### `/publications` (Downloads)

- **Scan.** WCAG: nothing failing; at 320 px `color-contrast` undecided on the second tab, which is
  scrolled half out of its strip. Best practice: `page-has-heading-one`, `region`.
- **Keyboard.** The tabs are one stop and the arrow keys move between them; then the panel and its
  links. Focus visible. No trap.
- **Reflow.** The page does not scroll sideways; at 320 px and 400 % the tab strip scrolls inside its
  own box, the backlog's _The Downloads page's tabs overflow on a phone_. Pass, with that entry.
- **The second tab** (historical publications, its own scan state). Scan: as the first tab, and
  `th-has-data-cells` (1.3.1) undecided on its table, which the sample leaves with a header row and
  no rows for the chosen range: looked at again with real content. Keyboard: the two date fields
  have their labels, are reached by Tab segment by segment as Chromium draws a date field, and show
  focus with a border in the primary colour; then the range buttons. No sideways scrolling.

### `/teaser` (the frame other sites embed)

- **Scan.** WCAG: nothing failing. Needs a person: `bypass` (2.4.1) on a page with one link and
  nothing to skip, `color-contrast` (1.4.3) on the banner and on the credit, `link-in-text-block`
  on the credit link. Best practice: `region` (no landmark; backlog).
- **Keyboard.** One stop, the link that fills the frame, with a visible ring; then the credit link.
- **Judged.** `bypass`: pass, there is no repeated block. The banner's white text on the chrome
  colour at 60 % is 4.89:1 over the chart's land, 5.98:1 over its sea and 4.44:1 over a white
  tile: **fail, 1.4.3**, where a white tile lies under it. The credit link: fail, 1.4.1.

### The list empty, the list failed

- **Scan.** WCAG: nothing. Best practice as on the landing page.
- **Status messages.** A failed read is written into a `role="alert"` region that is on the page
  before the failure: pass (4.1.3). An empty list says nothing at all, which a reader can take for
  a page that failed: the backlog's _A list that comes back empty shows nothing_. It is no WCAG
  failure by itself, since no message is shown at all, but it is the same gap for a screen reader.

### The footer's documents

- Not in the base build. Each country's footer, its disclaimer and cookie dialogs and its statement
  link are audited with that country's build.

### The PDFs

Both print buttons open a PDF the backend renders. Those PDFs are untagged and fail EN 301 549
chapter 10 for documents; [accessibility-findings.md](accessibility-findings.md) section 3 has the
finding and recommendation C the replacement of the renderer.

## The criteria

Pass, fail, not applicable, or needs a person, for the base build. A fail names the entry under
"The public site" in [fe/docs/backlog.md](../fe/docs/backlog.md) that tracks it.

| Criterion | Level | Verdict | Note |
|---|---|---|---|
| 1.1.1 Non-text content | A | Pass | Icons are decoration, flags carry the language's name. The chart is a map: see 2.1.1 |
| 1.2.1 Audio-only and video-only (prerecorded) | A | N/A | No audio or video |
| 1.2.2 Captions (prerecorded) | A | N/A | |
| 1.2.3 Audio description or media alternative | A | N/A | |
| 1.2.4 Captions (live) | AA | N/A | |
| 1.2.5 Audio description (prerecorded) | AA | N/A | |
| 1.3.1 Info and relationships | A | Pass | Remarks in the backlog: no `<h1>`, headings start at level four; the filter bar's toggles have no group name; the print sheet and the teaser have no `main` |
| 1.3.2 Meaningful sequence | A | Pass | |
| 1.3.3 Sensory characteristics | A | Pass | |
| 1.3.4 Orientation | AA | Pass | Both orientations render |
| 1.3.5 Identify input purpose | AA | N/A | The site takes no personal data |
| 1.4.1 Use of colour | A | **Fail** | The chart's credit link, by colour alone at 2.2:1: _The map credit's link is told from its line by a shade of grey alone_ |
| 1.4.2 Audio control | A | N/A | |
| 1.4.3 Contrast (minimum) | AA | **Fail** | The teaser's banner over a white tile, 4.44:1: _The teaser's banner can fall just under 4.5:1_. Every other text axe-core measured passes; the default subtle text colour (backlog) was not met on any page scanned, and its entry stays for the token |
| 1.4.4 Resize text | AA | Pass | At 200 % page zoom; the browser's text-only zoom was not checked |
| 1.4.5 Images of text | AA | Pass | None |
| 1.4.10 Reflow | AA | Pass | Nothing scrolls sideways at 320 px or 400 %; the Downloads tab strip scrolls in its own box (backlog). The chart is exempt as content that needs two dimensions |
| 1.4.11 Non-text contrast | AA | Pass | Focus rings: the browser's two-tone ring, a 2 px ring at 10:1 or more, the chart's 1 px zoom ring at 5.7:1; a pressed filter chip is filled. Other browsers not checked |
| 1.4.12 Text spacing | AA | Pass | Nothing clipped on any page or in a dialog |
| 1.4.13 Content on hover or focus | AA | Needs a person | The chart's hover tooltip: whether it can be dismissed and hovered was not checked |
| 2.1.1 Keyboard | A | Pass | Every action reachable: views, language, filters, print, cards, dialogs, tabs, Downloads links, chart zoom. The chart's messages are not, under the map exemption, each being in the lists. Attachments are not in the sample: checked again on real content |
| 2.1.2 No keyboard trap | A | Pass | Dialogs hold focus while open and Escape leaves them |
| 2.1.4 Character key shortcuts | A | Pass | None |
| 2.2.1 Timing adjustable | A | N/A | No time limit |
| 2.2.2 Pause, stop, hide | A | Pass | The only motion is the loading spinner, and only where motion is allowed |
| 2.3.1 Three flashes or below threshold | A | Pass | |
| 2.4.1 Bypass blocks | A | Pass | The skip link and the landmarks; the print sheet and the teaser repeat no block |
| 2.4.2 Page titled | A | Pass | Each view titled; one message shares the details view's title |
| 2.4.3 Focus order | A | **Fail** | Focus falls to the body when a dialog opened by its address closes: _Closing a message's dialog opened by its own address leaves focus nowhere_ |
| 2.4.4 Link purpose (in context) | A | Pass | |
| 2.4.5 Multiple ways | AA | Pass | Every message is in three views, each one step from the menu |
| 2.4.6 Headings and labels | AA | Pass | Remark: no page heading (backlog) |
| 2.4.7 Focus visible | AA | **Fail** | The print sheet's link-styled buttons lose their ring (backlog, the print route's link buttons) |
| 2.5.1 Pointer gestures | A | Pass | The chart's drag has the zoom buttons beside it; the teaser does not pan |
| 2.5.2 Pointer cancellation | A | Pass | |
| 2.5.3 Label in name | A | Pass | |
| 2.5.4 Motion actuation | A | N/A | |
| 3.1.1 Language of page | A | Pass | `lang` follows the site's language |
| 3.1.2 Language of parts | AA | Needs a person | Text in another language is marked in the code, but the base build showed none: checked on the country builds |
| 3.2.1 On focus | A | Pass | |
| 3.2.2 On input | A | Pass | The filters and the language are buttons |
| 3.2.3 Consistent navigation | AA | Pass | |
| 3.2.4 Consistent identification | AA | Pass | |
| 3.3.1 Error identification | A | N/A | No form |
| 3.3.2 Labels or instructions | A | Pass | The chart's layer checkboxes and the Downloads date fields have labels |
| 3.3.3 Error suggestion | AA | N/A | |
| 3.3.4 Error prevention (legal, financial, data) | AA | N/A | |
| 4.1.1 Parsing | A | Pass | |
| 4.1.2 Name, role, value | A | Pass | Pressed state on the chips and the language buttons, tab roles, a modal dialog with a name; to be confirmed by the screen-reader pass |
| 4.1.3 Status messages | AA | Pass | A failed read in an alert region, a read on its way marked busy; an empty list says nothing (backlog) |

Four fails in the base build: 1.4.1, 1.4.3, 2.4.3, 2.4.7, and the PDFs. Three of them were found
here and are new entries of the backlog; 2.4.7 was there already.

## What remains

### The Danish and the Greenlandic builds

Each country's build is evaluated on its own, from that country's repository, with its languages,
its tokens and its own files. Nothing in a country repository is changed by it. For each:

1. **A backend with the sample.** From a checkout of this repository, `./start-local.sh --sample
   --admin` (on a slot of its own if another stack runs); the sample holds messages under both
   countries' domains. Note the backend's port.
2. **The country's site in its production configuration.** In the country's `fe/`, set up by the kit
   as its README says, write down the base version its `.niord/base` holds (the fixes of this round
   reach a country only with its next bump), then run its generated `start:info` with
   `-- --configuration production --port <a free port>`, with `NIORD_BE_PORT` set to the backend's
   port.
3. **The scan.** From this repository's `fe/`: `npm run a11y:info -- --base
   http://localhost:<that port> --out <a folder outside both repositories>`. It scans every language
   the switcher offers and the first footer document, which the base build could not. Its reports
   are never committed; the verdicts come here.
4. **By hand, on what the country owns**, in every language of the build:
   - the footer: each control reached by Tab, with a visible ring and a name; the statement link;
   - each legal document's dialog (disclaimer, cookies): opened by keyboard, focus in it, Escape
     closes it and focus returns, its text at 320 px and with the text spacing, its `lang`;
   - the page before the application starts (`info/app/index.html`): its `lang` and title are the
     default language's;
   - the tokens: the contrast of the menu bar's text, of subtle text on the page's background, of
     the teaser's banner over a white tile, of the focus ring of a filter chip;
   - the area row of the filter bar and the area headings, at 320 px with the longest names;
   - Danish text on a Kalaallisut page and the reverse: marked with its language (3.1.2);
   - the keyboard walk, reflow at 320 px and 400 %, and the text spacing on every route, as above.
5. **The record.** A section per country here, with the date, the base version, the verdicts and
   every fault. A fault in a file the country owns (its footer, legal documents, tokens, branding,
   `index.html`) is written in that section and given to the country's owner, not into
   `fe/docs/backlog.md`, which holds the base product's faults.

### The rest

- **The screen-reader pass** with NVDA or JAWS on the main journeys: open the site, filter, read a
  message, open one on the chart and in the list, change the language, print, open Downloads. A
  person's work; its findings come here per page.
- **Real content**, after the first restore of a copy of production: the scan and the keyboard walk
  again, with long titles, attachments and real PDFs, and whether every position and area on the
  chart is also given as text (the map exemption).
- **After the fixes**: every page a fix touches, scanned and walked again, on the base build and
  on each country's build once its bump carries it, before a statement is dated.
