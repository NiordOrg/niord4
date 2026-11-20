# Potential errors — open suspected issues

Suspected bugs, design errors, broken assumptions and inconsistent behaviour found while working on the
codebase (test-writing rounds, migrations, reviews). Where a test pins the current (incorrect) behaviour,
that test is named with a `_documentsBug`/`_documentsBehavior` suffix and links back here, so a future fix
fails the pin loudly rather than silently.

> **Maintenance rule: this file lists OPEN potential errors only — never progress.** Do not record fixes,
> fix batches, "done" notes, counts or status banners here. When an item is resolved, **delete its entry**
> — git history is the record of what was fixed, and the pinning test (flipped to assert the corrected
> behaviour) is the durable guard.

# Potential errors — `util` package

Suspected bugs found while adding the `org.niord.core.util` Tier-1 unit tests
(`niord/src/test/java/org/niord/core/util/…`). **Production code was not changed.** Tests that pin the
current (incorrect) behaviour are named with a `_documentsBug` / `_documentsBehavior` suffix so a future
fix fails them loudly. The substantive ones have since been fixed; the lower-severity quirks recorded
for completeness remain below.

## U6. Lower-severity quirks (recorded, not necessarily defects)

- **`TextUtils.txt2html(String)` double-escapes existing entities** (lines 91–96). `&` is the first
  `replaceEach` target, so already-escaped input is re-escaped: `txt2html("&amp;") → "&amp;amp;"`. Likely
  intended for raw plain text, but a hazard when concatenating partially-escaped fragments.
  Pinned by `TextUtilsTest.txt2htmlEscapesAmpersandOnce`.
- **`TextUtils.maxLineLength(String,int)` under-fills lines** (lines 113–147). It breaks at the *last
  space within the truncated window*, which is often an early space, so `maxLineLength("aaa bbb ccc", 7)`
  yields `"aaa\nbbb ccc\n"` rather than the fuller `"aaa bbb\nccc\n"`. Suboptimal NAVTEX packing, not a
  crash. The linear rewrite of the method (2026-09) kept this output on purpose. Pinned by
  `TextUtilsTest.maxLineLengthWrapsAtWordBoundary`.
- **`PositionFormatter.format(Locale,String,double)`**: the non-floor default spec `"%02f"` for
  `MIN`/`SEC` (`DEFAULT_FORMATS`) is a no-op width flag that yields six decimals (e.g. `"30.600000"`). It is
  not hit by the built-in formats (callers always pass an explicit spec), so it is documented but not pinned.
- **`PositionAssembler.assemble(...)` renders a `null` direction as the literal `"null"`** (NAVTEX
  lines 67–80, audio 105–120) — no null-guard on `direction`. Benign because callers always supply one.
  Pinned by `PositionAssemblerTest.navtexWithNullDirectionAppendsLiteralNull_documentsBehavior` and
  `audioEnglishNullDirectionFallsBackToLiteralNull_documentsBehavior`.
- **`HtmlToPlainText` renders a relative anchor parsed without a base URI as `link <>`** (the `tail()`
  `"a"` branch) — `absUrl("href")` returns `""`, dropping the href. Correct usage parses with a base URI
  (then it renders `link <http://…>`). Pinned by
  `HtmlToPlainTextTest.anchorWithoutBaseUriResolvesRelativeHrefToEmptyAngleBrackets`.

---

# Potential errors — `model.geojson` package

Suspected bugs found while adding Tier-1 unit tests for the GeoJSON value-object model
(`niord/src/test/java/org/niord/model/geojson/…`). **Production code was not changed.** Tests that pin
the current behaviour use a `_documentsBug` suffix and link back here.

## G1. `GeoJsonVo.computeBBox()` / `computeCenter()` have no empty-geometry guard — sentinel values leak

- **Location:** `niord/src/main/java/org/niord/model/geojson/GeoJsonVo.java`, `computeBBox()`
  (lines 102–112, carrying a `// TODO: Naive implementation - cater with border cases later...`
  comment) and `computeCenter()` (lines 115–118).
- **Description:** `computeBBox()` initialises the accumulator to the sentinel
  `{ Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE }` and only narrows it
  inside `visitCoordinates(...)`. When the geometry visits **no** coordinates (e.g. a `FeatureVo` with a
  `null` geometry, an empty `FeatureCollectionVo`, or a geometry whose every coordinate array has
  length < 2 and is therefore skipped by the shared `visitCoordinates` helper), the handler never runs
  and the raw sentinel `{2.147483647E9, 2.147483647E9, -2.147483648E9, -2.147483648E9}` is returned as
  if it were a real bounding box (note `minX > maxX`, an inverted/empty box).
- **Why it looks wrong:** `computeCenter()` then derives a center from these sentinels, yielding
  `{(MAX_VALUE + MIN_VALUE)/2, …}` = `{-0.5, -0.5}` — a coordinate off the coast of West Africa that has
  nothing to do with the (absent) geometry. Any consumer that maps or zooms to a computed bbox/center
  for an empty geometry would jump to a nonsensical location instead of handling "no geometry"
  explicitly (e.g. returning `null` or an empty box). The in-source TODO confirms border cases were
  knowingly left unhandled.
- **Exposed by:** `FeatureVoTest.computeBBox_withNullGeometry_returnsSentinelValues_documentsBug` and
  `FeatureVoTest.computeCenter_withNullGeometry_returnsNonsensicalCenter_documentsBug`.
- **Severity:** Low/medium — depends on whether empty/coordinate-less geometries reach these methods.
  Inherited by every `GeoJsonVo` subclass (`FeatureVo`, `FeatureCollectionVo`, all geometry VOs).
- **Also pinned by:** `GeoJsonVoTest.computeBBox_emptyGeometry_returnsSentinelBounds_documentsBehavior` /
  `…computeCenter_emptyGeometry_returnsHalfNegativeHalf_documentsBehavior` and the
  `GeometryTypesVoTest` equivalents, which assert the same sentinel `{2.147483647E9, …}` and `{-0.5,-0.5}`.

# Potential errors — `model.message` package

Suspected bugs found while adding Tier-1 unit tests for the message value-object model and its
`org.niord.model` filter/localization base (`niord/src/test/java/org/niord/model/…` and
`…/model/message/…`). **Production code was not changed.** Note: the `getDescs(filter)` *fall-back to the
first available description* and `getDesc(lang)` *null-lang-as-wildcard* behaviours are the **documented
contract** of `ILocalizable` (verified, pinned by `_documentsBehavior` tests) and are **not** listed here
as bugs.

## M3. `DataFilter.allOfFields()` returns `true` for empty varargs (vacuous truth), unlike `anyOfFields()`

- **Location:** `niord/src/main/java/org/niord/model/DataFilter.java`, `allOfFields(String...)`
  (lines 254–264) vs `anyOfFields(String...)` (235–245).
- **Description:** `allOfFields()` called with no arguments (or an empty array) returns `true` on any
  filter — the loop finds no missing field. `anyOfFields()` with no arguments returns `false`. The two
  siblings disagree on the empty case.
- **Why it looks wrong:** A caller passing a dynamically-built, possibly-empty field list to
  `allOfFields(list...)` gets "include" (`true`) when nothing was actually required — the opposite of the
  intuitive reading. It is the classic vacuous-truth footgun; whether intended is unclear.
- **Exposed by:** `DataFilterTest.allOfFields_emptyVarargsReturnsTrue_documentsBehavior`.
- **Severity:** Low — no current caller invokes it with an empty list, but it is an easy future trap.

---

# Potential errors — `core.geojson` package

Suspected bugs found while adding Tier-1 unit tests for the pure converters in `org.niord.core.geojson`
(`niord/src/test/java/org/niord/core/geojson/…`). **Production code was not changed.**

## CG2. `FeatureName` accepts non-2-char language tokens; `getCoordIndex()` defaults to 0

- **Location:** `niord/src/main/java/org/niord/core/geojson/FeatureName.java`, the `FEATURE_NAME`/
  `FEATURE_COORD_NAME` patterns (lines 35–36) and `getCoordIndex()` (line 138).
- **Description:** The Javadoc says the language token is a "2 character language code", but the regex
  `[a-zA-Z_]+` accepts any length, so `"name:english"`, `"name:e"`, and `"name:da_DK"` all parse as valid
  feature names and `getLanguage()` returns the whole token. Separately, `coordIndex` is a primitive `int`
  only assigned for `FeatureCoordName` keys, so `getCoordIndex()` silently returns `0` for a plain feature
  name or invalid key (indistinguishable from a real index 0).
- **Why it looks wrong:** Documented contract (2-char code) is not enforced, so a stored name may never
  round-trip against a normalized lookup key; and `getCoordIndex()` is ambiguous without first checking
  `isFeatureCoordName()`.
- **Exposed by:** `FeatureNameTest.{multiCharacterLanguageIsAccepted,singleCharacterLanguageIsAccepted,underscoreInLanguageIsAccepted}_documentsBehavior`
  and `coordIndexDefaultsToZeroForPlainFeatureName`.
- **Severity:** Low — inputs are produced internally in the expected `name:<2-char>` form.

## CG3. `GeoJsonUtils.serializeFeatureCollection(...)` carries the coordinate index across features

- **Location:** `niord/src/main/java/org/niord/core/geojson/GeoJsonUtils.java`,
  `serializeFeatureCollection` (the `startIndex` local declared outside the per-feature loop, lines ~359–385).
- **Description:** `startIndex` accumulates across features (continuous 1-based numbering for the whole
  collection). A feature that defines an explicit `startCoordIndex` property resets it, and every
  *subsequent* feature then inherits the overridden numbering rather than the natural running count;
  name/coordinate-less features are skipped without consuming indexes.
- **Why it looks wrong:** Almost certainly intentional (continuous numbering), but the cross-feature
  stateful coupling makes a later feature's indexes depend on earlier features' content — fragile.
- **Exposed by:** `GeoJsonUtilsTest.{serializeFeatureCollectionContinuesIndexingAcrossFeatures,serializeFeatureCollectionHonoursStartCoordIndexProperty}`.
- **Severity:** Low — pinned as current behaviour, not a defect.

## CG4. `UTMCoordinateConversion.mgrutm2LatLon(...)` reverse path is broken in the southern hemisphere (latitude ~90° off)

- **Location:** `niord/src/main/java/org/niord/core/geojson/UTMCoordinateConversion.java`,
  `MGRUTM2LatLon.convertMGRUTMToLatLong` — the southern-hemisphere latitude reconstruction
  (`if (latZoneDegree < 0) { latitude = 90 - latitude; }` combined with the `getHemisphere(...) == "S"`
  negation).
- **Description:** discovered while fixing the northern-hemisphere band defect. With the northing
  reconstruction corrected, the **longitude** of a
  southern MGRUTM string now round-trips correctly, but the **latitude** comes back a constant ~90° too far
  south — e.g. `latLon2MGRUTM(-33.92, 18.42)` (Cape Town) → `mgrutm2LatLon(...)` ≈ `(-123.92, 18.42)`,
  `(-23.55, -46.63)` → `(-113.55, -46.63)`, `(-1.5, 36.8)` → `(-91.5, 36.8)`. The error is a uniform `-90°`
  offset, so the southern latitude-band branch (not the northing cycle) is at fault.
- **Why it looks wrong:** a coordinate round-trip should recover the input. The northern hemisphere now
  round-trips to ~1e-4°; the southern hemisphere is off by a quarter-circle, so the southern reverse path is
  unusable as-is. The CG1 fix was deliberately scoped to the northern hemisphere — the realistic domain for
  this Danish maritime application — and explicitly does not touch the southern branch.
- **Exposed by:** code inspection plus a standalone round-trip sweep run while fixing CG1; not pinned by a
  committed test (the suite asserts only northern round-trips — see
  `UTMCoordinateConversionTest.mgrutmRoundTrip_northernHemisphere_recoversInputAcrossBands`, which is
  intentionally scoped north).
- **Severity:** Low for this application (no southern-hemisphere coordinate use), Medium for any reuse of the
  MGRUTM reverse path below the equator. Pre-existing — present both before and after the CG1 fix.

---

# Potential errors — `model.publication` package

Suspected bugs found while adding Tier-1 unit tests for `org.niord.model.publication`. **Production code
was not changed.** (The whitespace-`fieldsDefined` issue also affects the publication descs — see
[M2](#m2-ilocalizeddescfieldsdefined-treats-whitespace-only-fields-as-defined) — and is not repeated here.)

## P2. `PublicationVo.rewriteRepoPath(...)` replaces every occurrence, not just a prefix

- **Location:** `niord/src/main/java/org/niord/model/publication/PublicationVo.java`, `rewriteRepoPath`
  (lines 85–95) — `desc.setLink(desc.getLink().replace(repoPath1, repoPath2))`.
- **Description:** `String.replace` rewrites **all** occurrences of `repoPath1` in the link, so a link such
  as `/old/sub/old/x.pdf` has both `/old` segments rewritten.
- **Why it looks wrong:** The method intent ("rewrites the publication links from one repository path to
  another") reads as a single leading-prefix rewrite; replacing later occurrences could corrupt the file
  portion of a link. (The analogous `MessageVo`/`MessagePartVo`/`AttachmentVo` rewrites share this.)
- **Exposed by:** `PublicationVoTest.rewriteRepoPath_replacesEveryOccurrence_documentsBehavior`.
- **Severity:** Low — repo paths are unique UUID-based prefixes in practice.

_(`model.search` — `PagedSearchParamsVo` and `PagedSearchResultVo` — was covered with no suspected bugs.)_

---

# Potential errors — `core.category` package

## CAT1. `Category.toVo(...)` has no cycle guard — recurses forever when both parent and children are requested

- **Location:** `niord/src/main/java/org/niord/core/category/Category.java`, `toVo(Class, DataFilter)`.
  The method follows the parent (`getParent().toVo(clz, compFilter)`) and the children
  (`child.toVo(SystemCategoryVo.class, compFilter)`) using the *same* component filter, with no
  visited-set / depth guard.
- **Description:** `TreeBaseEntity.addChild(child)` wires a bidirectional link (the child's `parent`
  points back at its parent). If `toVo` is called with a filter that includes **both** `parent` and
  `children` — e.g. `DataFilter.get().fields("*")` (`DataFilter.ALL`) — projecting a parent walks to its
  child, which walks back to its parent, which walks to its child… until `StackOverflowError`.
- **Why it looks wrong:** A projection should terminate. Either direction alone (`PARENT` *or*
  `CHILDREN`) is safe; only the combination on a cyclic entity graph blows the stack. There is no
  cycle detection.
- **Exposed by:** Discovered while writing `org.niord.core.category.CategoryTest`: an initial
  `toVo_includesChildrenWhenFiltered` using `fields("*")` on a parent with an `addChild`-linked child
  threw `StackOverflowError`. The test was changed to request only the `children` field (which
  terminates) and a comment points here. The same shape exists in other `TreeBaseEntity.toVo`
  implementations (e.g. `Area`).
- **Severity:** Low — production call sites request `PARENT` or `CHILDREN`, not both, so the cycle is
  latent. A `fields("*")` projection of a category subtree would trigger it. A stored parent cycle made by a move
  (the error hunt of 2026-11-12) was fixed on 2026-11-13 by refusing the move, without a cycle guard in `toVo`. If
  the guard is added, make it one visited set threaded through both the parent walk (180-181) and the children
  (200).

## CAT3. Moving a category or area among siblings that share a sort order can do nothing, in an order nobody saw

- **Location:** `niord/src/main/java/org/niord/core/service/TreeBaseService.java`,
  `changeSortOrder(Class, Integer, boolean)`, reached through `CategoryService.changeSortOrder` and
  `AreaService.changeSortOrder` (`PUT /rest/categories/change-sort-order`, `PUT /rest/areas/change-sort-order`);
  `TreeBaseEntity.compareTo` and the `@OrderBy("siblingSortOrder ASC")` on `TreeBaseEntity.children`.
- **Description:** the siblings are the parent's `children`, which the database returns ordered by
  `siblingSortOrder` alone, so siblings that share a value come back in whatever order PostgreSQL picks.
  `Collections.sort` keeps that order (`compareTo` answers `0` on a tie and the sort is stable), the moved
  node's index is read from it, and the tie repair then numbers the siblings `0, 1, 2, …` in that same order.
  Which of two tied siblings counts as the upper one is therefore the database's choice at that moment: when
  the node being moved up happens to come back first, its index is `0`, nothing moves, and the endpoint answers
  `false` — after the repair has already saved new sort orders for every sibling.
- **Why it looks wrong:** a tie is the ordinary case, not a bootstrap leftover: a category or area created
  without an explicit sort order gets `0.0`, so every such sibling ties with the others. The tree the editor
  sees orders tied siblings the same arbitrary way (the children by the same `@OrderBy`, the roots by the same
  `compareTo`), and nothing ties that read to the one `changeSortOrder` makes, so "move up" on the node shown
  second can do nothing, or move the node relative to an order the editor never saw. A tie-breaker on the id —
  in `compareTo`, the `@OrderBy` and the repair — would make both reads agree.
- **Exposed by:** `CategoryRestServiceITTest.changeSortOrderAsAdminReturnsTrue` failed once in Backend CI
  (`expected: <true> but was: <false>`) and passed on the re-run. It creates two children of a fresh parent,
  both at `0.0`, and moves the second up. `AreaRestServiceITTest.changeSortOrderAsAdminReturnsTrue` hit the
  same thing earlier and was made deterministic by giving the two children distinct sort orders first; its
  comment still names the entry for that flake, which was deleted when the test was pinned. The error hunt's fix
  stage (2026-11-13) gave `CategoryRestServiceITTest`'s two children distinct sort orders (0 and 10) the same way.
  The production behaviour was not changed by any of them.

# Potential errors — `web` package (`DictionaryRestService`)

Suspected issues found while adding integration tests for `org.niord.web.DictionaryRestService`
(`niord/src/test/java/org/niord/web/DictionaryRestServiceITTest.java`). **Production code was not
changed.**

## DR2. `PUT /rest/dictionaries/matches-aton` returns `204 No Content` on a non-match (not `200` + empty body)

- **Location:** `niord/src/main/java/org/niord/web/DictionaryRestService.java`, `matchesAton(...)`.
- **Description:** When no supplied dictionary entry carries an `atonFilter` (the short-circuit `return
  null`) **or** when no filter matches the supplied AtoN (`DictionaryService.matchesAton(...)` returns
  `null` via `findFirst().orElse(null)`), the handler returns `null` — which `NullBodyNoContentAdvice`
  turns into an empty body with **HTTP 204 No Content**, not `200`.
- **Why it looks wrong:** This is more of a contract quirk than a defect — `204` is a reasonable way to
  signal "no matching entry". But the method declares `produces = "application/json…"` and the caller may
  expect either `200` with a JSON `null`/empty body, or a documented `204`. The status differs between
  "found" (`200` + entry body) and "not found" (`204` + empty body), which is easy to get wrong in a
  client, and it is applied globally by an advice rather than chosen per endpoint.
- **Exposed by:** `DictionaryRestServiceITTest.matchesAtonWithDomainReturnsEmptyWhenNoFilterMatches`
  (buoy AtoN vs. a light filter → `204`) and `…matchesAtonWithDomainAndNoAtonFilterReturnsEmpty`
  (values present but none carries an `atonFilter` → `204`). A genuine match still returns `200` with the
  entry body (`…matchesAtonWithDomainReturnsMatchingEntry`).
- **Severity:** Low — documented behaviour, pinned so any change to the status is deliberate.

---

# Infrastructure services — `repo` / `mail` / `schedule` / `sequence` / `cache` / `integration`

Each is pinned (or noted) by a test that asserts the **actual** current behavior, named with a
`_documentsBug` / `_documentsBehavior` suffix.

## MAIL4 — `HtmlMail` `INLINE_CSS` mode does not process or remove `<style>` blocks

- **Location:** `niord/src/main/java/org/niord/core/mail/HtmlMail.java`, `processHtml()`/`inlineStyleSheets()`.
- **Description:** `INLINE_STYLES` mode (`inlineStyles`) parses and removes both `<link>` and `<style>`
  elements; `INLINE_CSS` mode (`inlineStyleSheets`) only converts `<link>` stylesheets to `cid:` parts and
  leaves inline `<style>` blocks untouched in the output.
- **Why it looks wrong:** The asymmetry is surprising — a caller choosing `INLINE_CSS` still ships raw
  `<style>` blocks (which many mail clients strip), whereas `INLINE_STYLES` does not.
- **Exposed by:** `HtmlMailTest.inlineCssDoesNotRemoveStyleBlock_documentsBehavior` (and the contrasting
  `inlineStylesRemovesStyleBlock`).
- **Severity:** Low — design asymmetry, may surprise callers.

## REPOFILTER1 — `RepositoryServletFilter` catches a RESTEasy exception the MVC stack never throws

- **Location:** `niord/src/main/java/org/niord/core/repo/RepositoryServletFilter.java:64-66` —
  `catch (UnhandledException ex)` on `org.jboss.resteasy.spi.UnhandledException`, unwrapping a
  `"Broken pipe"` `IOException` and swallowing it.
- **Description:** the filter exists to keep broken-pipe stack traces (a client aborting a repo-file
  download) out of the log. It catches the RESTEasy wrapper type. RESTEasy is still on the classpath, but
  only to serve the SECOM servlet mounted at `/api/secom/*` — which this filter does not front. Repository
  files are served through the Spring MVC pipeline, which wraps nothing in `UnhandledException`.
- **Why it looks wrong:** if the branch is unreachable, broken-pipe `IOException`s now propagate and fill the
  log with exactly the noise the filter was written to suppress — a silent regression with no failing test,
  since nothing asserts log output. The import also keeps a RESTEasy type coupled to the repository layer.
- **Suggested check before fixing:** abort a large `GET /rest/repo/file/...` mid-download and look at what,
  if anything, reaches the log and in which exception shape; then either catch the shape actually thrown or
  delete the filter.
- **Severity:** Low — log noise only, no functional impact. Related: the untestability note for this filter
  in [`untestable-code.md`](untestable-code.md).

## REPO2 — `FileTypes` content-type/icon lookup oddities

- **Location:** `niord/src/main/java/org/niord/core/repo/FileTypes.java`.
- **Description:** (a) `getContentType()` (line ~113) chains `fileExtensionLookup.get(ext).getMimeType()`, so
  an unknown extension triggers an internal NPE that is silently caught (lines ~114-117) and returned as
  `null` — masking the real cause rather than checking the map for `null`. (b) `.doc`/`.dot` map to icon
  `"docx"`.
- **Why it looks wrong:** NPE-as-control-flow (and a `.doc`→`docx` icon mismatch); harmless today (the icons
  resolve to generic-ish paths) but fragile.
- **Exposed by:** `FileTypesTest` asserts the *actual* mappings (including unknown-extension → `null`).
- **Severity:** Low.

## REPO5 — A message PDF may leave out an image attachment whose name holds `%` or `;`

- **Location:** `be/niord/bootstrap/templates/messages/message-support.ftl`, macro `renderAttachment`
  (`<img src="/rest/repo/file/${att.path}">`, the path written unencoded), fetched by the PDF renderer over HTTP
  from the backend's own origin (`org/niord/core/script/pdf/SameOriginUserAgent`). The same line is in the
  Greenland repository's copy of the template.
- **Description:** since the request firewall accepts `%25` and `%3B` on a repository file's path, a file whose
  name holds `%` or `;` opens in the administration app and on the public site. The
  template does not encode the path, so the renderer asks for a raw `%` (an invalid escape) or a raw `;` (a path
  parameter, refused by the firewall), and the image is presumably missing from the PDF. Not checked by running it.
- **Why it looks wrong:** the attachment shows in the admin app and on the public site but not in the printed
  message.
- **Exposed by:** reading the template while the firewall was opened for such names, 2026-11-18; no test.
- **Severity:** Low — an image attachment whose name holds one of these characters is rare. The template is data:
  a fix (`?url` on each segment, or a path the code encodes) ships in a release folder and reaches a database only
  by import.

## REPO6 — A link stored with a raw `;` in a repository file name still does not open

- **Location:** the stored links that point at repository files: a publication desc's `link` written by
  `RepositoryService.getRepoUri` before it encoded a `;`, a legacy publication link kept as it was
  (`IssuePublicationMapping.linkOf` returns an explicit link untouched), and any message HTML that names a file.
- **Description:** the request firewall accepts `%25` and `%3B` on `/rest/repo/file/**` and `/rest/repo/thumb/**`,
  the links the backend derives write `;` as `%3B` (`WebUtils.encodeURI`, `IssuePublicationMapping.linkOf`), and
  the public site's proxy passes the path of a repository file on as it arrived
  (`fe/ui-apps/info/nginx/default.conf.template`), so a file whose name holds `;` opens in the administration
  app and on the public site through a link written now. What is left: a link stored earlier with a raw `;`
  after `/rest/repo/file/` stays refused everywhere, and no firewall setting can serve it: Tomcat would cut the
  name at the `;`.
- **Why it looks wrong:** the file is listed and linked, and whoever follows the old link gets an error.
- **Suggested fix:** count the stored links first (fe/docs/backlog.md, section 1, the repository file names);
  rewriting them is data, not code.
- **Exposed by:** reading the public site's proxy while the firewall was opened for such names, 2026-11-18.
- **Severity:** Low — a file name with `;` is rare; to be weighed by the count at the first restore.

## REPO7 — With vips, the thumbnail of an image whose name holds `%s` is written under another name

- **Location:** `niord/src/main/java/org/niord/core/repo/ThumbnailService.java`, `createThumbnailUsingVips` (the
  thumbnail's path handed to `vipsthumbnail` as `-o`), with the cached thumbnail's name from
  `RepoCache.thumbnailName` (`<name>_thumb_<size>_<hash>.<ext>`, the upload's own name kept).
- **Description:** `vipsthumbnail` reads its `-o` argument as a format, in which `%s` (and `%<n>s`) stands for the
  input file's base name. The thumbnail's name keeps the upload's name, so for an image named, say, `50%sale.png`
  the format names another file: the input is the spooled copy of the row, under a temporary name, so vips writes
  `50<spool name>ale_thumb_64_<hash>.png` into the cache folder. `Files.setLastModifiedTime(thumbFile)` then fails,
  the request falls back to the file type's icon, and the stray file stays. Since the request firewall accepts
  `%25` on `/rest/repo/thumb/**`, such a name can be asked for, also anonymously for a published message's image.
- **Why it looks wrong:** the thumbnail is never served, and every request for it leaves one more file on the
  cache volume.
- **Exposed by:** reading the vips path while the firewall was opened for names holding `%`, 2026-11-18; not run.
  It applies only where the `vipsPath` setting is set: it is unset by default, and no image installs libvips.
- **Severity:** Low — vips is off unless a deployment turns it on, and such a name is rare. Fix, once the vips
  behaviour is checked on a machine with libvips: let vips write to a neutral temporary name in the cache folder
  (`Files.createTempFile(folder, "thumb-", "." + ext)`) and move that to the thumbnail's name, or draw a name
  holding `%` with the Java path.

## INT4 — `NiordIntegrationExecutionService.processNiordIntegration` still swallows a failed re-import and shares one transaction per target series

- **Location:** `niord/src/main/java/org/niord/core/integration/NiordIntegrationExecutionService.java`,
  `processNiordIntegration(...)` — the method-level `catch (Exception e) { log.error(...); }` and the single
  `@Transactional(REQUIRES_NEW)` spanning the whole per-target-series reconciliation.
- **Description:** residual after the fix that reordered the update leg to re-import **before** cancelling
  the old copy (so a failed re-import no longer orphans a published Notice-to-Mariners as `CANCELLED`).
  Two design weaknesses remain: (a) the
  re-import failure is still **swallowed** by the method-level catch-all — no exception propagates and the
  scheduler still advances `nextScheduledExecution`, so a newer remote version that fails to import is
  silently never applied and no operator is signalled; and (b) the entire per-target-series run (all
  cancels/creates/updates) shares one transaction, so a single malformed source message can still roll back
  the whole batch for that target. A sysadmin's *Execute* (`PUT /rest/niord-integrations/execute/{id}`) does not
  surface it either: `NiordIntegrationService.processNiordIntegration` catches every failure of the run, the fetch
  from the other server included (`:178-185`), so the call answers 204 and the page says "Integration pull started"
  when nothing was fetched (found by the user guides' writers, 2026-11-11; `guides/sysadmin-manual/13-Integration.adoc`
  says so as it is).
- **Why it looks wrong:** a failed reconciliation that "looks successful" (no error surfaced, schedule
  advanced) hides a stale, safety-relevant message from operators; and one bad message should not be able to
  discard the other messages' valid updates computed in the same run.
- **Exposed by:** `NiordIntegrationExecutionGapITTest.failedReimportLeavesOldCopyPublishedAndImportsNoReplacement`
  asserts the post-INT3-fix safe end-state (old copy stays `PUBLISHED`, no replacement imported, failure
  swallowed) — the swallow itself is the residual documented here.
- **Severity:** Medium — surfacing the failure (per-message transactions, or recording a failure on the
  integration) is the natural follow-up during the transaction/event rewrite. The error hunt of 2026-11-12 found two
  more faults that this catch kept silent (two own copies with one `legacyId`, and references an import left out
  without a word); the fix stage of 2026-11-13 fixed both, and each now writes a WARN line to the log only, so the
  failure record asked for here is still missing. Since 2026-11-16 (the review of the phase 2 fix stage, R6) one
  kind of malformed source message no longer ends the run: a message whose content this Niord refuses as it builds
  it (a position off the globe, an `IllegalArgumentException` before anything is written) is skipped with a WARN
  that names it, the rest of the run goes on, and an updated message's old copy stays (`importOrSkip`, 259-272).
  Every other failure of an import, such as the test's message without a type (a `NullPointerException`), still
  ends the run as (b) says. Since the fix stage of 2026-11-13, a failed cancellation inside
  `processNiordIntegration` rolls the whole run back (`MessageService.cancelMessage` and `updateStatus` roll back for
  any exception): the run's catch logs it, the commit throws `UnexpectedRollbackException` and
  `NiordIntegrationService` logs that, where the part before the failure used to be committed. The one transaction
  spans every target series of the integration (the loop at 133 runs inside the `REQUIRES_NEW` at 116-117); the
  per-message transaction the scheduled publication and the hourly firing run now use, `MessageTxRunner`
  (`org.niord.core.message`), is the one this fix can use.

---

# Potential errors — public API, security internals & persistence infra (`web/api`, `core/keycloak`, `core/db`)

_Added while testing the public REST API surface, the Keycloak/domain security internals, and the
`core.db` persistence helpers. Production code was not changed; each item is pinned by a test
suffixed `_documentsBug` / `_documentsBehavior` where practical._

## DB1 — JSON attribute converters silently swallow (de)serialization failures

- **Location:** `niord/src/main/java/org/niord/core/db/JpaJsonAttributeConverter.java` and
  `JpaPropertiesAttributeConverter.java` (both `convertToDatabaseColumn` / `convertToEntityAttribute`
  catch blocks).
- **Description:** A serialization failure in `convertToDatabaseColumn` is logged and returns `null`, so a
  bad value is persisted as a `null` column instead of failing the transaction; a deserialization failure in
  `convertToEntityAttribute` is logged and returns `null` (JSON) / an empty map (Properties).
- **Why it looks wrong:** Silent failure on write can cause undetected data loss; silent failure on read masks
  corrupt columns. Converters normally let the exception propagate so the surrounding transaction fails.
- **Exposed by:** `JpaJsonAttributeConverterTest.convertToEntityAttributeMalformedJsonReturnsNull` /
  `convertToDatabaseColumnUnserializableValueReturnsNull`;
  `JpaPropertiesAttributeConverterTest.convertToEntityAttributeMalformedJsonReturnsEmptyMap`.
- **Severity:** Low/Medium.

## DB2 — The two converters disagree on null / error handling

- **Location:** `JpaJsonAttributeConverter` vs `JpaPropertiesAttributeConverter`.
- **Description:** Near-identical converters have different contracts: the JSON converter returns `null` for a
  `null` column **and** on error; the Properties converter returns a **new empty `HashMap`** for a `null`
  column and on error. A caller reading a JSON-backed column may NPE where a Properties-backed column would
  hand back an empty map.
- **Exposed by:** `JpaJsonAttributeConverterTest.nullHandlingDiffersFromPropertiesConverter_documentsBehavior`.
- **Severity:** Low — latent inconsistency; the two near-identical converters should agree on a contract.

## DB3 — un-audited `equals`/`like` predicates are silently case- and accent-sensitive

- **Location:** equality/`LIKE` predicates on text columns throughout the criteria layer — `CriteriaHelper`
  `equals`/`like` (`niord/src/main/java/org/niord/core/db/CriteriaHelper.java`), the area lineage-prefix
  `like(lineage, parent.lineage + '%')`, and the ~101 `@NamedQuery` call sites that compare strings.
- **Description:** `equals` emits a plain SQL `=` (`cb.equal`), so its case-sensitivity is the column's
  collation, not the helper's. Under PostgreSQL's default collation `=` and `LIKE` are **case- and
  accent-sensitive**: `equals(name, "alpha")` does *not* match a stored `"Alpha"`.
  `equalsIgnoreCase`/`like` (which fold case explicitly via `cb.lower`) are the ones that do.
- **Why it looks wrong:** the codebase inherits its lookup shapes from an era where the database collation
  was case- and accent-*insensitive*, so a call site could rely on a forgiving match without saying so.
  Nothing enforces that callers pass canonically-cased values (usernames, short ids are typically
  lower-/upper-cased upstream, but not by contract). A lookup that used to succeed on a differently-cased
  value now silently returns empty — and the suite does not catch it, because the tests assert exact-case
  matches, which pass either way.
- **Concretely-surfaced sites are handled** — `Message.findByShortId` and the `MessageSearchService`
  creator/updater username match have been switched to case-insensitive matching. This umbrella stays open
  for the **un-audited remainder**.
- **Recommended action:** decide per column family whether case-insensitive matching is required; if so,
  either fold both sides in the query (`lower(col) = lower(:v)`) or declare the affected columns with a
  case-insensitive collation (`citext`, or an ICU `und-x-icu` case-insensitive collation) in the Liquibase
  changelog. Do **not** silently accept the case-sensitive default without an explicit decision.
- **Exposed by:** `CriteriaHelperITTest.equalsIsCaseSensitiveUnderPostgresDefaultCollation_documentsBehavior`
  pins the helper's behaviour; the broad audit is not exercised by any single test.
- **Severity:** Medium — latent, collation-dependent correctness across an unaudited set of predicates.

## DB4 — `AtonService.search` / `searchPositions` swallow all exceptions and return "no matches"

- **Location:** `niord/src/main/java/org/niord/core/aton/AtonService.java:153-204` (`search`, the catch at 200-203)
  and `211-235` (`searchPositions`, the catch at 231-234).
- **Description:** Both methods wrap the entire criteria query — including the spatial `ST_Within` path — in a
  broad `try/catch(Exception)` that logs and returns an empty result. A genuine spatial failure (SRID mismatch,
  missing/renamed `ST_Within`, malformed extent geometry) is therefore indistinguishable to callers from "zero
  AtoNs matched".
- **Why it looks wrong:** It masks spatial-query regressions as empty results. (Surfaced while building the
  direct-predicate tests, which deliberately run the `CriteriaQuery` outside the service so such failures fail
  loudly.)
- **Exposed by:** `SpatialPredicateITTest` (the service-path test asserts the inside id is *present*, so a
  silent empty return would fail it; the direct-predicate tests bypass the swallow entirely).
- **Severity:** Low/Medium — observability/robustness; not user-facing wrong data under correct config.

---

# Potential errors — `core.promulgation` package

## PROM2. `SafetyNetPromulgationRestService.getAreas` ignores its `lang` query parameter

- **Location:** `niord/src/main/java/org/niord/core/promulgation/SafetyNetPromulgationRestService.java`,
  `getAreas(typeId, lang)` (line ~88).
- **Description:** The method builds `DataFilter filter = DataFilter.get().lang(lang);` but then never uses
  `filter` — the stream maps each area with a freshly-built `DataFilter.get().fields(DataFilter.DETAILS)`,
  discarding both the `lang` and the local `filter`. The `lang` `@RequestParam` is therefore dead.
  Contrast `NavtexPromulgationRestService.getTransmitters`, which *does* pass its `filter` (with `lang`) through.
- **Why it looks wrong:** a caller-supplied `lang` is silently ignored; the local variable is unused.
  (Functionally harmless today because `SafetyNetArea.toVo` carries no localized descriptions, but it is a
  latent smell and an inconsistency between the two sibling endpoints.)
- **Severity:** Low — dead parameter / unused local; no current functional impact.

## PROM4. `PromulgationTypeVo.compareTo(null)` returns `-1` instead of throwing `NullPointerException`

- **Location:** `niord/src/main/java/org/niord/core/promulgation/vo/PromulgationTypeVo.java`,
  `compareTo(PromulgationTypeVo p)` (line ~57): `return p == null ? -1 : priority - p.getPriority();`.
- **Description:** `java.lang.Comparable.compareTo(null)` is contractually required to throw
  `NullPointerException`; this implementation returns `-1` instead. Separately, `priority - p.getPriority()`
  is an `int` subtraction that can overflow for extreme priority values (the classic comparator-overflow
  smell), although the priorities used in practice are small.
- **Exposed by:** `PromulgationTypeVoTest.compareToNull_documentsBehavior` (asserts the actual `-1` return and
  flags the contract deviation); the ordinary priority ordering is covered by the same test class.
- **Severity:** Low — only matters if a null ever reaches a sort; pinned so a refactor cannot silently
  "fix" it and change ordering behaviour.

---

# AtoN subsystem (`core/aton`, `web/aton`)

Found while covering `org.niord.core.aton.*`, `org.niord.web.aton.AtonIconRenderer` and the AtoN
service/REST integration tests. Tests pinning the current (incorrect) behaviour use a `_documentsBug` /
`_documentsBehavior` suffix.

## ATON8. `LightCharacterParser` telephony helpers: trailing-empty join + vacuous-valid

- **Location:** `niord/src/main/java/org/niord/core/aton/LightCharacterParser.java`,
  `getTelephonyCode(String)` / `telephonyCodeValid(String)`.
- **Note:** `getTelephonyCode("A!")` returns `"Alpha-"` (the invalid `!` maps to `""` but the `"-"`
  joiner leaves a trailing separator), and `telephonyCodeValid("")` returns `true` (`allMatch` over an
  empty stream is vacuously true). Minor surprises, consistent with the code; pinned by
  `LightCharacterParserTest`.
- **Severity:** Low.
- **Reviewed — left as-is (by design):** cosmetic edge quirks only, on inputs (invalid telephony chars /
  empty strings) that the light-character parser does not produce in practice; the current outputs are pinned
  and consistent. Not worth a behaviour change.

## TMPL4. Plain-text field-template clean-up joins whitespace-padded lines (drops the newline)

- **Location:** `FieldTemplateProcessor.FieldTemplate.cleanUpPlainTextContent`, step
  `content.replaceAll("(?m)^\\s|\\s$", "")` (combined with the later `\\n+` → `\\n` step).
- **Why it looks surprising:** because `\\s` also matches `\n` and the multiline `^`/`$` anchors sit at
  the newline boundaries, a newline that is *padded by whitespace* (e.g. `"Line one   \n\n   Line two"`)
  has its surrounding spaces AND the newlines themselves removed, so the two lines are concatenated with
  **no separator** (`"Line oneLine two"`). A single newline between non-padded lines is preserved as
  expected. The intent appears to be "trim each line", but it silently destroys paragraph breaks whenever
  Freemarker emits indentation around blank lines.
- **Exposed by:** `FieldTemplateProcessorTest.parseContentTextWhitespacePaddedNewlinesCollapseAndJoin_documentsQuirk`
  (vs. `...PreservesSingleNewlineBetweenLines`).
- **Severity:** Low — formatting quirk in generated plain-text fields (e.g. NAVTEX/subject text). Related
  to the "prefer robustness over correctness" clean-up philosophy of the template helpers.

---

# Potential errors — `core.message` entity layer

Suspected issues found while adding the `org.niord.core.message` entity/value-object unit suite
(`niord/src/test/java/org/niord/core/message/*Test.java`). **Production code was not changed.** A test that
pins the current (surprising) behaviour is named with a `_documentsBehavior` suffix and links back here.

## MSG1. `MessageDesc` projection: the `"*"` (ALL) wildcard yields LESS data than `DETAILS`

- **Location:** `niord/src/main/java/org/niord/core/message/MessageDesc.java` — both
  `toVo(DataFilter)` (the `if (!compFilter.includeField("title")) { ... }` guard around vicinity/publication/
  source/internalPublication) and the `MessageDesc(MessageDescVo, DataFilter)` constructor (the
  `if (compFilter.includeField("title")) { this.title = ...; } else { /* copy all fields */ }` branch).
- **Description:** the field name `"title"` is overloaded as a sentinel meaning *"restrict the projection to
  just the title"* (used by `Message.MESSAGE_MAP_FILTER`, which sets `fields("Message.geometry",
  "MessageDesc.title")`). But `DataFilter.includeField(field)` returns `true` whenever the **ALL wildcard**
  `"*"` is present (`return field != null && (fields.contains(ALL) || fields.contains(field));`). So a filter
  built with `DataFilter.get().fields(DataFilter.ALL)` makes `includeField("title")` true, which selects the
  *title-only* branch — dropping `vicinity`, `publication`, `source` and `internalPublication`. The
  consequence is counter-intuitive: `fields(ALL)` (nominally a superset) returns **less** desc data than
  `fields(DETAILS)`.
- **Why it looks wrong:** `ALL` ("everything") should never produce a strict subset of what `DETAILS`
  produces. The collision is accidental — the sentinel `"title"` and the wildcard `"*"` were never meant to
  interact, but `includeField` treats `"*"` as matching every field name including the sentinel.
- **Exposed by:** `MessageDescEntityTest.toVoUnderAllWildcardDropsNonTitleFields_documentsBehavior` and
  `MessageDescEntityTest.constructorUnderAllWildcardCopiesOnlyTitle_documentsBehavior` (pin the current
  behaviour: under `fields(ALL)` only the title survives), contrasted with
  `MessageDescEntityTest.toVoUnderDetailsFilterCopiesNonTitleFields` (under `fields(DETAILS)` the full desc
  is copied). The two `_documentsBehavior` tests will fail loudly if the sentinel collision is ever fixed.
- **Severity:** Low / latent — every production message projection uses `Message.MESSAGE_DETAILS_FILTER`
  (fields `details`/`geometry`, **not** `ALL`) or `Message.MESSAGE_MAP_FILTER` (title-only by design), so no
  current caller hits the surprising path. It is a trap for future code that reasonably assumes
  `fields(ALL)` returns a complete `MessageDesc`.

---

# Potential errors — `core.message.batch` import writer

## BATCH3. `AbstractMessageImportWriter.saveMessageTag` `@Transactional` is a no-op on self-invocation

- **Location:** `niord/src/main/java/org/niord/core/message/batch/AbstractMessageImportWriter.java`,
  lines 56-64 — `@Transactional protected MessageTag saveMessageTag(MessageTag tag)`, called from
  `BatchMessageImportWriter.writeItems` / `BatchMsgArchiveImportWriter.writeItems` (same bean).
- **Description:** `saveMessageTag` is annotated `@Transactional`, but it is `protected` and is only ever
  invoked via `this.saveMessageTag(tag)` from `writeItems` of the same bean. Spring applies `@Transactional`
  through the bean proxy, so a self-invocation bypasses the proxy and the advice never runs — the annotation
  has no effect here. The tag persistence therefore relies entirely on the ambient transaction (the JSR-352
  chunk-step transaction in production, the test's transaction in the IT), not on the annotation.
- **Why it looks wrong:** the annotation advertises a transaction boundary that does not exist, which is
  misleading and would silently lose its (apparent) `REQUIRED` semantics if `writeItems` were ever called
  outside an active transaction. `protected` visibility makes it un-proxyable on a self-call regardless.
- **Exposed by:** `BatchMessageImportReaderWriterITTest.writerAddsImportedMessagesToTag` — the tag is
  persisted correctly, but only because the writer is driven inside `@TestTransaction`; the test asserts the
  committed `messageCount`/membership rather than relying on `saveMessageTag` opening its own transaction.
- **Severity:** Low/latent — works today because the batch job XML runs the writer inside a chunk transaction.
  Pinned, not fixed.

# Potential errors — `core.area`

_Pinned by tests asserting the ACTUAL behavior (method names end `_documentsBug`)._

## AREA-NOTE. `AreaService.importArea` does not match existing areas by `legacyId`

- **Location:** `niord/src/main/java/org/niord/core/area/AreaService.java` `importArea(Area, boolean, boolean)` (~423-461).
- **Description:** behavior clarification (not necessarily a defect): the match order is (1) `findByMrn` if MRN present, else (2) `findByName(name, lang, parentId)`. `legacyId` is never consulted even though `Area` carries it. Callers relying on legacyId-based dedup on re-import will create duplicates when MRN is absent.
- **Exposed by:** noted while writing `AreaServiceGapITTest` (the test asserts the actual MRN/name match order).
- **Severity:** Low (document for the migration; confirm intended).

# Potential errors — reliability & API-contract gaps

## PROM5 — Twitter promulgation failure is swallowed; the tweet is lost with no retry or record

- **Location:** `niord/src/main/java/org/niord/core/promulgation/TwitterPromulgationService.java`
  `promulgateMessage(...)` (private, 213), catch block at 261-263; before the post it also returns with no log
  when the type has no settings or their credentials are not valid (221-223), and after an ERROR line from
  `instantiateTwitter` (318) when the client cannot be made (227-229).
- **Description:** on publish, promulgation runs fire-and-forget and `promulgateMessage` posts the tweet. A
  `TwitterException` from the post is handled with only
  `log.warn("Failed promulgating to Twitter for message " + message.getUid(), e)` — no status persisted, no
  retry, nothing surfaced to the caller or the message record.
- **Why it looks wrong / risk:** a failed external delivery is silently dropped: the message reads as published
  but the tweet never went out, with no durable record or retry path to recover it. (The NAVTEX/SafetyNet
  `onUpdateMessageStatus` delivery hooks are similarly no-op.) The fix shape is the one `org.niord.secom`
  already uses for SECOM deliveries: enqueue a per-kind outbox row on publish and let a dispatcher retry with
  backoff and escalate to `ERROR`, so a transient Twitter/network failure is retried rather than lost.
- **Exposed by:** code inspection during the port comparison.
- **Severity:** Medium — reliability; silent loss of an outbound promulgation on any transient failure.
  Neither national deployment uses Twitter/X promulgation (the owner, 2026-11-19); the code is left as it is and is
  planned for removal.
- **Related:** PROM10. X refuses the endpoint the post goes to, so today every attempt fails the same way, and a
  retry alone would not get a tweet out. The step before the post is lost the same silent way: the status-change
  event that starts a tweet rides the JMS status topic, which is not stored (the embedded broker's
  `persistent: false`) and whose subscription is not durable, so a status change committed just before a restart,
  one whose broadcast meets a broker error ("Failed sending JMS" is only logged) and one broadcast while
  `TwitterMessageListener` subscribes again after a broker failure is never tweeted, with no record. The error
  hunt's fix stage of 2026-11-13 made the listener's loop log the failure and subscribe again (`JmsTopicLoop`,
  `TwitterMessageListenerFaultTest`) instead of ending; the mailing lists have kept their status changes in an
  outbox since (`MailingListStatusChangeOutbox`), and SECOM has its reconciliation sweep. A tweet has neither: the
  outbox row this entry recommends would cover the lost event too, if it is written in the status change's own
  transaction, as the mailing lists' is.

## PROM10 — Tweets go to X's v1.1 `statuses/update`, which X refuses for its Free and Basic apps

- **Location:** `niord/src/main/java/org/niord/core/promulgation/TwitterPromulgationService.java`
  `promulgateMessage(...)` (`twitter.updateStatus(statusUpdate)`), on twitter4j 4.0.6 (`be/pom.xml`,
  `twitter4j.version`).
- **Description:** twitter4j's `updateStatus` posts to `https://api.twitter.com/1.1/statuses/update.json`. Since
  2023 X has given its Free and Basic access levels only a subset of v2 and a few v1.1 endpoints (media upload,
  OAuth), and answers a v1.1 post with 403, error code 453 ("You currently have access to a subset of Twitter API
  v2 endpoints and limited v1.1 endpoints ... only"). Posting goes through v2, `POST /2/tweets`, with OAuth 1.0a
  or OAuth 2.0 user context. No twitter4j release moves `updateStatus` to v2 [read in X's migration guide and in
  public reports of the 453 answer; not run against an X account].
- **Why it looks wrong:** on such an account every tweet is refused. PROM5 then logs a warning and nothing else,
  so the Twitter promulgation of a published message never goes out and nobody is told.
- **Exposed by:** the PROM5 assessment (2026-11-12). The owner decided to leave Twitter as it is for now.
- **Severity:** Medium where Twitter promulgation is used; none where it is not (the test seed's `twitter` type
  is inactive). Fix: post through `POST /2/tweets` (twitter4j has no v2 client, so a client of its own or another
  library), and then PROM5's record and retry. Neither national deployment uses Twitter/X promulgation (the owner,
  2026-11-19); the code is left as it is and is planned for removal.

## BUILD1 — A build without a warm Maven cache fails whenever GRAD's repository is down

- **Location:** `be/pom.xml` (the `grad` repository block, `<id>grad</id>` and
  `https://rnavlab.gla-rad.org/mvn/repository/grad/`, ~372-380, beside Central, osgeo and jitpack) and `be/niord/pom.xml` (the SECOM dependency
  `org.grad.secom:secom-v2-core-jakarta:0.1.0`, ~39-45 and ~196-206). Every country's build inherits it through
  `niord-base`.
- **Description:** two artifacts are hosted only by GRAD, `secom-v2-core-jakarta` 0.1.0 (jar and pom) and its
  parent pom `secom-v2-parent` 0.1.0; everything else comes from Central. On 2026-10-23 GRAD refused
  connections (from GitHub's runners and from a workstation alike), and a backend job of niord-dk's CI failed at
  dependency resolution: niord-dk had no Maven cache yet (`Cache not found for input keys: … maven-Linux-`), so
  the `restore-keys` fallback that keeps the base product's own CI green during such an outage had nothing to
  restore.
- **Why it looks wrong / risk:** the build's availability is GRAD's. While GRAD is down, a country repository
  without a cache, any cache that has expired (7 days unused), and a newcomer's first `./start-local.sh
  --sample` on an empty Maven repository all fail before compiling. A release build would too.
- **What a fix needs (an owner decision):** a lasting home for the two artifacts that does not depend on GRAD's
  repository being up. The owner's ruling of 2026-11-01 keeps the library in the countries' file mirror for now;
  where it lives for good is a later ruling (F5, the mirrors' removal, waits for it). A country may carry a file
  mirror, `be/third-party-maven/`, listed first in its pom: niord-dk and niord-gl do, holding the files
  byte for byte with GRAD's checksums, with a README on how to remove it. The base product has none: its CI cache holds the
  files, and a fresh local build here still needs GRAD or a warm cache, so the entry stays open.
- **Exposed by:** niord-dk's CI, 2026-10-23.
- **Severity:** Medium — availability of every build that has no warm cache, outside our control.

## ERR8 — Two `IllegalStateException` sites that cannot be reached in normal use would answer a server fault with 409

- **Location:** `niord/src/main/java/org/niord/core/user/UserService.java` (`currentUser()`: the first-login
  row is neither created nor found, which needs `createUser` to succeed and the re-read straight after it to
  miss) and `niord/src/main/java/org/niord/core/keycloak/KeycloakIntegrationService.java` (`keycloakAdmin()`,
  "User not authenticated", for a caller with no token, which method security refuses before it gets there);
  `RestExceptionStatusAdvice` answers an `IllegalStateException` with `409 Conflict`.
- **Why it looks wrong:** both are server faults a caller cannot resolve by retrying, not conflicts. Neither
  can be reached in normal use, and there is no reproduction.
- **Exposed by:** the fix of the S-124 republish chain's 409s (2026-09-09), which moved those sites to a
  dedicated exception rendered 500 and left these two.
- **Severity:** Low. Fix: throw a dedicated runtime exception, rendered 500, at both sites.

## WEB10. Behind the local chart's nginx, `WebUtils.absoluteUrl` redirects point at port 80

- **Location:** `k8s/helm/niord/templates/nginx/configmap.yaml` (`location /`, 76-93, sets `Host $host` and
  `X-Forwarded-Proto $niord_forwarded_proto`, which is `$scheme` without an Ingress, but not
  `X-Forwarded-Port $niord_forwarded_port`, which `location /auth` sends at 67) together with
  `niord/src/main/java/org/niord/core/util/WebUtils.java` `absoluteUrl`
  (`ServletUriComponentsBuilder.fromCurrentContextPath()`); no `server.forward-headers-strategy` is set anywhere.
- **Description:** nginx's `$host` carries no port, and the `/` location forwards no `X-Forwarded-Port` (the
  `/auth` location does). Tomcat therefore gives the request the scheme's default port 80 — from the port-less
  `Host`, or from `X-Forwarded-Proto: http` when Boot's native forwarded-header support is on, which it turns
  on by itself only when it detects Kubernetes. `absoluteUrl` builds every redirect `Location` from that
  request, so on the local chart (published on `localhost:18888`) the 307s of `RepositoryService` (repository
  thumbnails, `/img/filetypes/**`), `BatchRestService` (`/img/filetypes/**`), `MessageMapImageRestService`
  (the map image files under `/rest/repo/file/`) and `AtonIconRestService` (`/img/aton/aton.png`) point at
  `http://localhost/...`.
- **Why it looks wrong:** a redirect should land on the public origin the request came through; on any
  published port other than 80 the browser follows it to a host that does not answer, and the thumbnail or
  icon breaks.
- **Exposed by:** reading the chart's proxy configuration against the redirect sites while documenting what
  the backend serves (2026-09-23). Not reproduced on a running cluster.

## WEB11. The local chart never sets `baseUri`, so message links from it point at `localhost:8888`

- **Location:** `k8s/helm/niord/values.yaml` (`externalBaseUri`, used only by `templates/NOTES.txt` and the
  k8s README) against the `baseUri` setting (`niord/src/main/java/org/niord/core/NiordApp.java`), which the
  chart leaves at the base product settings file's `http://localhost:8888` (`defaults/base/settings.json`, added
  at the first start).
- **Description:** nothing maps `externalBaseUri` onto `baseUri` (no env var, no `-DbaseUri`), and `adminAppUrl`
  is empty in the chart, so the message links in mails and tweets (`<adminAppUrl or baseUri>/messages?messageId=`)
  name `http://localhost:8888`, not the chart's public `http://localhost:18888`. A cluster environment pins the
  setting in its values (`app.settings.baseUri`, rendered as an environment variable named `baseUri`, which the
  app reads before the stored row); the chart's default, `app.settings: {}`, pins nothing, so the local release
  keeps the stored row. Mapping `externalBaseUri` onto `baseUri` by itself would break the local mails' styling
  and images, which the pod fetches from `baseUri` and cannot fetch from the host's `localhost:18888`.
- **Why it looks wrong:** a link a recipient clicks should name the public origin. The backend's own HTTP fetches
  of `/css/templates` and `/img` through `baseUri` work only because the pod itself listens on 8888.
- **Exposed by:** documenting `adminAppUrl` and the message-link form (2026-09-23). Not reproduced on a running
  cluster.

## LUCENE-7. For minutes after each start, a script's free-text search and a late scheduled mail can miss messages without a sign

- **Location:** `niord/src/main/java/org/niord/core/message/MessageLuceneIndex.java` (`init()` deletes the index when
  `messageIndexDeleteOnStartup` is on, the default; `allMessagesIndexed()` is false until a pass has made every message
  searchable again) and `MessageSearchService.searchPagedMessageIds`, which marks a free-text result answered in that
  window (`PagedSearchResultVo.indexIncomplete`). The callers that still hand such a result on without the mark: a
  deployment's script that searches with free text (`searchOrFail` from a template or mailing-list script), and
  `MailingListExecutionService.executeScheduledTrigger` more than half an hour after the start.
- **Description:** until the passes have indexed every message again, which on a large database takes several
  minutes, a free-text search answers from the messages indexed so far. The owner's decision (2026-11-19): the search
  keeps answering, and the people who use the answer are told. The editors' _Messages_ list shows a warning above such
  a result; the print of the list (PDF) and a mailing-list report run by hand carry a line at their top
  (`pdf.list.index_incomplete`, `IndexIncompleteNotice`); the administration app shows the list's warning in the mail
  dialog and asks before an export (ZIP), whose archive keeps its form. A scheduled mailing-list trigger whose stored
  query holds a `query=` term waits for the index in the half hour after the start (tried again every two minutes,
  with one WARN and one "Delayed:" record in the Mail Queue); after that half hour it is mailed from what the index
  holds, with a WARN naming the trigger and nothing in the mail. A script that searches with free text gets the marked
  result and decides itself; none of the base product's scripts reads the mark. The message mail and the export
  endpoints, called by anything other than the administration app, carry no sign either. The public API
  (`/rest/public/v1/messages`) takes no free text, and the PostgreSQL backend (`disableLucene`) keeps its table across
  a start: neither is affected.
- **Why it looks wrong:** a scheduled mail sent half an hour after a deploy whose index refill stalled, or a script's
  output made in the window, can leave warnings out without a word to its readers.
- **Exposed by:** `MessageLuceneIndexStartWindowTest` and `MessageSearchIndexIncompleteITTest` (the mark, the print's
  line); `MailingListDeliveryFaultITTest` holds a scheduled trigger to the half hour's wait, its retry and its record;
  `MailingListExecutionServiceITTest` holds the report's line. What a script does with a marked result is not tested.
- **Severity:** Low — the window is short, the scheduled mails wait for it, and the people who print, export, mail or
  run a report are told. With `messageIndexDeleteOnStartup` off the index is kept across a start, and only the
  messages changed since its last pass are missing until the first pass (not checked by a test).

# Potential errors — REST-controller security audit

Open findings from a security audit of every `@RestController`. **No production code was changed** by the
audit itself; the items that have since been fixed are gone from here.

**The enforcement model these findings sit in:** authorization is **pure JSR-250** method security
(`@EnableMethodSecurity(jsr250Enabled=true)`); the HTTP layer is `anyRequest().permitAll()`, so *every*
access decision is an annotation. `401`/`403` come from the resource server's `ExceptionTranslationFilter`,
and per-request domain roles are added by `NiordDomainFilter` reading **verified** `Jwt` claims. The guard
test
[`RestControllerSecurityAnnotationTest`](../niord/src/test/java/org/niord/web/RestControllerSecurityAnnotationTest.java)
**fails the build if any handler lacks an explicit `@PermitAll`/`@RolesAllowed`/`@DenyAll`**, so
"unannotated ⇒ silently anonymous" cannot ship. Note that adding a class-level `@PermitAll` to
`MonitorRestService`, `S124RestService` and `AtonIconRestService` to satisfy that guard makes their
pre-existing anonymous exposure *explicit* — it is not a fix.

What the audit's fixes left open is below: INT8, from its finding on the Niord integration's address and TLS
checks, and the mass-assignment entries, from its sweep of the `@RequestBody` handlers.

## INT8 — An integration address with a credential in it is logged once, and the fetch follows a redirect from https to http *(LOW · OPEN)*

- **Location:** `niord/src/main/java/org/niord/web/NiordIntegrationRestService.java` (`createNiordIntegration`
  logs `integration.getUrl()` at INFO before the service checks the address);
  `niord/src/main/java/org/niord/core/integration/NiordIntegrationExecutionService.java` (`newHttpClient`:
  `HttpClients.custom()` keeps the default redirect strategy).
- **Description:** an address such as `http://user:password@host` is refused at create, but the controller has
  written it to the application log the line before. And the fetch follows a redirect from https to http: the
  address check (`NiordIntegrationUrlValidator.dnsResolver`) checks every hop's address, not its scheme, so a
  peer that redirects its own answer to http has the imported messages fetched without TLS.
- **Why it looks wrong:** a refused credential should not reach the log, and a fetch that starts on https
  should not end on http. The endpoint is SYSADMIN only, so the first needs a system administrator to type a
  credential into the address; the second needs a trusted peer that downgrades its own redirects.
- **Exposed by:** the security audit of every `@RestController` (2026-09), as what its fix of the
  integration's SSRF and disabled TLS checks left open.
- **Severity:** Low. Fix: log the integration's id or host only, and set a redirect strategy that refuses an
  https-to-http hop (which could stop a peer that really redirects to http).

## MASSASSIGN-1 — `BaseService.saveEntity()` merges on any client-supplied id *(LOW · OPEN)*

- **Location:** `niord/src/main/java/org/niord/core/service/BaseService.java` (`saveEntity`: `em.merge` when
  `isPersisted()`), plus the 14 entity constructors that copy `id` off their VO (`Area`, `Category`,
  `Comment`, `Contact`, `DictionaryEntry`, `ParamType`, `ScriptResource`, `Source`, `NiordIntegration`,
  `FiringSchedule`, `FiringPeriod`, `MessageParamFilter`, `AtonNode`, `BaseMessagePromulgation`).
- **Description:** "create" and "update" are decided by whether the entity carries an id, and the id can come
  straight from a request body. A create path without a guard therefore silently updates an existing row: the
  security audit of 2026-09 confirmed it on `POST /rest/dictionaries/dictionary/message` with the id of an
  entry of another dictionary, which moved that entry across dictionaries, rewrote its key and orphan-deleted
  its descriptions, with HTTP 200. Every create path has to remember to guard — thirteen now do: eleven
  refuse an entity that is already persisted (`isPersisted()`/`isNew()`; the audit added five of them, for
  dictionary entries, parameter types, contacts, script resources and comments), and
  `AreaService.createArea` / `CategoryService.createCategory` reset the id instead, because the area and
  category imports (the upload endpoints' batch jobs among them, at the create endpoints' own roles) pass a
  template carrying a foreign system's id; their REST create handlers also answer `400` to a body with an id.
  The next create path written will have no guard unless its author remembers.
- **Why it looks wrong:** the safety property is spread across every caller instead of being enforced once.
  The repo already has the machinery to pin this kind of invariant at build time —
  `RestControllerSecurityAnnotationTest` does exactly that for missing security annotations — but nothing
  equivalent covers create-with-a-body-supplied-id.
- **Exposed by:** the security audit's sweep of all 96 `@RequestBody` handlers (2026-09), which found seven
  entity constructors copying the id off the body and guarded the create paths it found.

## MASSASSIGN-4 — Assorted server-managed fields accepted from the request body *(LOW · OPEN)*

- `PUT /rest/publications/publication/{id}` writes `status` directly (`Publication.updatePublication`),
  bypassing the `updateStatus` state machine and its tag-locking / start-recording side effects. Both
  endpoints are ADMIN, so no privilege gain — an inconsistency, not a vulnerability.
- `Publication(vo)` and `Message.updateMessage` copy `created` off the body, letting an ADMIN/EDITOR backdate a
  record's creation timestamp (`@PreUpdate` only resets `updated`). Audit-trail cosmetics.
- `MessageRestService.createMessage` accepts `status` from the body and `MessageService.createMessage` only
  defaults it when null. `checkMessageEditingAccess` blocks a plain EDITOR, but an ADMIN can create a message
  directly in `PUBLISHED`, skipping number assignment, promulgation and the MQ status event. (A create body with
  no `status` at all answers 400 since 2026-11-13.)
- `FiringScheduleService.updateFiringPeriod` loads a firing period by the body's id without checking it
  belongs to the area named in the request, so an EDITOR can reassign a firing period between areas. Within
  EDITOR authority, so cosmetic.
- **Exposed by:** the security audit's sweep of the `@RequestBody` handlers (2026-09).

> **Residual blind spot (not a finding):** XXE was asserted-absent but the message-import ZIP
> (`BatchMsgArchiveImportProcessor`) XML entity handling was **not** positively verified. The S-124 side is
> verified closed (2026-09-10): the SECOM serve path parses no GML at all since the move to immutable
> published versions, and the one XML it does read back — the persisted discovery-metadata entry — goes
> through the bindings' hardened `SecureXmlSource` (DOCTYPE refused, external entities off, secure
> processing on, fail-closed), as does every unmarshaller of the 0.3.x bindings.

## DOC1. Sysadmin-manual `<<Section Title>>` cross references render as dead links

- **Location:** `guides/_parked/secom/A2-SecomCertificates.adoc` (pre-existing occurrences:
  `<<Settings>>`, `<<Monitoring>>`, `<<Obtaining a Certificate>>`, `<<The Maritime Connectivity Platform>>`, …)
  and possibly other manual chapters.
- **Description:** AsciiDoc xrefs written as `<<Section Title>>` (title text, not the generated id) do not
  resolve under the manual's asciidoctor settings — the committed `manual.html` renders them as
  `<a href="#Section Title">[Section Title]</a>`, a bracketed dead fragment link. The generated ids are the
  slugified forms (e.g. `id="rotating-certificates-renewal"`), so the refs must be
  `<<rotating-certificates-renewal,Rotating Certificates (Renewal)>>`.
- **Why it looks wrong:** clicking any of these links in the shipped manual jumps nowhere.
- **Exposed by:** the phase-4 SECOM review (2026-07-13); the four *new* refs added in phase 4 were fixed to the
  id form, the pre-existing ones across the manual were left for a doc-wide sweep.

## TESTQ1. `DkNavWarnDateFormatterTest` is print-only (no assertions)

- **Location:** `SIFA-DK/niord-dk` `be/src/test/java/org/niord/core/DkNavWarnDateFormatterTest.java`.
- **Description:** the test formats its inputs and `System.out.println`s the results without a single assertion:
  it fails only if an exception escapes, so a silently wrong Danish NAV-warn date passes the suite.
  `DkLightCharacterTest`, the other one this entry named, asserts each rendering non-blank since DK's C6.
- **Why it looks wrong:** it is the only test of the Danish NAV-warn date formatting; it should pin the expected
  output strings.
- **Exposed by:** a review of the two Danish unit tests — neither carried a meaningful assertion.

## TESTQ5. `SecomProtocolITTest.cancelledDatasetsRequireATemporalExtentOrDataReference` expects an empty database-wide summary

- **Location:** `niord/src/test/java/org/niord/secom/SecomProtocolITTest.java`,
  `cancelledDatasetsRequireATemporalExtentOrDataReference`: after cancelling its own dataset, the test calls
  `GET /api/secom/v2/object/summary` with no parameters and asserts `summaryObject` has size 0, and
  `GET /api/secom/v2/object` with no parameters and asserts 404.
- **Description:** without a temporal extent the summary serves every *active* S-124 dataset in the shared
  database, not only this test's. The assertion therefore requires that no other test has left a published,
  uncancelled dataset behind at that moment. Backend CI on `main` (2026-10-06)
  found one — `NW-112-01`, data reference `8a0980ce-65a7-8ea0-98a0-3662403d8e11`, committed by
  another IT class that ran earlier — and the step failed with "Expected: a collection with size <0>, Actual:
  <[{dataReference=8a0980ce-…, info_identifier=NW-112-01, …}]>"; the same commit's suite was green on the pull
  request and green on the rerun. The second summary assertion (`validFrom=1970-01-01`, `hasSize(1)`) has the
  same exposure.
- **Why it looks wrong:** the test's own dataset is identifiable (its `dataReference`), and the test checks it
  individually in its last step; the two database-wide assertions pin the state of the whole shared database
  instead, which depends on run order and on which earlier classes clean up after themselves — the coupling
  that once made two other tests' database-wide counts green on a developer machine and red on CI.
- **What exposed it:** a red `main` after a merge whose change could not touch SECOM (a registry URL in
  the pom and a workflow), with a green rerun of the same job. A fix would filter the summary assertions to
  the test's own dataset (or assert that it is absent/present by `dataReference`), not pin the collection size.

## TESTQ4. `IssuePreviewAndFileITTest` reads two pool-thread names as proof of overlap, which the pool does not promise

- **Location:** `niord/src/test/java/org/niord/core/publication/series/IssuePreviewAndFileITTest.java`,
  `eachLanguageIsDrawnOnItsOwnThreadWithItsOwnPersistenceContext` (the closing `assertNotEquals` on
  `threads.get(0).thread()` and `threads.get(1).thread()`), against `IssueRenderService.renderAll`, which submits
  the languages back to back to one `Executors.newFixedThreadPool(MAX_CONCURRENT_LANGUAGES)` (three workers, kept
  for the bean's lifetime), and `StubIssueRenderService.render`, which records its thread, reads one row and
  returns.
- **Description:** a `ThreadPoolExecutor` does not promise that two queued tasks run on two workers. The worker
  that takes the first language runs it and goes straight back to the queue; the queue's lock is not fair, so
  when that worker gets there before an idle one has taken the second language, it runs that one as well. The
  stub's render is one small query long (the whole test takes about 20 ms), so the window is real, and a loaded
  runner that is slow to schedule the idle worker widens it. The two renders then run one after the other on one
  pool thread, and the thread comparison fails although every other assertion holds: neither render is on the
  publishing thread, neither is in a transaction, and each reads in a session of its own, because
  `em.getDelegate()` with no transaction opens a new session per call, whichever thread asks.
- **Not a product defect:** what `renderAll` has to guarantee is that each language's bytes come back in request
  order, drawn off the publishing thread, outside its transaction and in a session of their own; drawing them at
  the same time only saves time. A real render takes seconds per language, so the second worker has taken its
  task long before the first render ends, and a document whose languages did run on one worker would be the same
  document, only later.
- **The fix the test needs:** make the overlap happen rather than infer it from thread names. An opt-in rendezvous
  in the stub, armed by this test only (in the style of `readsTheDatabase()`) and disarmed by `reset()`: a
  `CountDownLatch(2)` counted down on entry to `render` and then awaited with a bounded timeout (ten seconds),
  the outcome recorded in `RenderThread`. Each language is then held until the other has started, the test
  asserts that both saw the other start, and two distinct threads follow from that. A regression that drew the
  languages one after the other — a pool of one, or inline on the caller — fails on the timeout instead of
  hanging. The single-language path renders inline on the caller and must never find the latch armed. Dropping
  the thread comparison and keeping only the distinct-session assertion would also end the flake, but a pool of
  one would then pass.
- **Exposed by:** Backend CI "Integration tests", the first attempt of a run on 2026-09-24:
  `both languages were drawn on one thread, so nothing overlapped ==> expected: not equal but was:
  <pool-100-thread-3>`, the one failure among 2987 tests; the re-run passed, and the PR changed report-PDF code
  only, not the render path. A standalone run of the same pattern (two back-to-back submits to a warm
  `newFixedThreadPool(3)`) put both tasks on one worker in most rounds with near-instant tasks, and in 2 of 5000
  rounds with 1–5 ms tasks on a loaded CPU.
- **Severity:** Low — test-only; a red integration run on an unrelated change.

# Potential errors — publications port (2026-09-14)

Suspected issues surfaced while porting the publications redesign (`org.niord.core.publication.series`,
`org.niord.web.publication`) from the Quarkus branch. The port kept the source behaviour; nothing below
was changed in production code.

## PORT-15. An unresolvable `${niord.home}` expands to the empty string, so a fresh database boots with its roots at the filesystem root

- **Location:** `niord/src/main/java/org/niord/core/settings/SettingsService.java` (`expandSettingValue`, the
  final `replaceToken(token, "")`), consumed through `SettingInjectionBeanPostProcessor` by every `@Setting`
  that defaults under `${niord.home}` — `PublicationPathService` (`publicationPreviewRootPath`), the Lucene index
  folders, and whatever else names a directory.
- **Description:** `niord.home` resolves from a system property `niord.home`, an environment variable
  literally named `niord.home`, a `NIORD_HOME` system property or environment variable, or the `Setting` row.
  In the IT suite the row comes from the test tree's `niord.json` (`niord.home = ${NIORD_HOME}`), which the IT
  start writes (the fixture holds no `Setting` row since C4b); a database built from the bare master changelog
  has no `Setting` rows, and the token resolves to nothing when neither a `niord.home` nor a `NIORD_HOME`
  system property or environment variable is set. The token is then replaced with nothing,
  so `${niord.home}/repo` becomes `/repo`. Observed on CI (2026-09-15, `LiquibaseBootstrapITTest`, which
  boots exactly that fresh database): `PublicationPathService` refused to start with
  `could not create /publication-archive`, a permission error at the filesystem root. Running as root in a
  container it would instead create `/repo` and `/publication-archive` at the root and serve from there. On
  Windows it creates `C:\repo`, `C:\publication-archive`, `C:\publication-preview` and `C:\message-index` —
  all four are present on the development machine from local runs of that test, which is why it stayed green
  there.
- **Why it looks wrong:** a token nothing can resolve should refuse, or at least say so, rather than vanish
  into a valid-looking absolute path. The code before the port tolerated it silently (the Lucene index logs
  `Failed to open Lucene Index in folder /message-index` on every sweep); the publications containment check
  turns the same condition into a boot failure whose message names a directory nobody configured.
- **Workaround:** pass `-Dniord.home=...` (what `start-local.sh` does) or deploy with the seeded changelog.
  `LiquibaseBootstrapITTest` now sets the system property in its static bootstrap. **Exposed by:** the first
  CI run of the merged publications port.

## PORT-5. `WebUtils.getMultipartFiles` keys parts by file name, so two parts with one name collapse

- **Location:** `niord/src/main/java/org/niord/core/util/WebUtils.java` (`getMultipartFiles`,
  `inputFiles.put(fileName, …)`, line ~323, where `fileName` is the part's name reduced to a bare name by
  `bareFileName`); the legacy RESTEasy reader had the same shape. Since the reduction, two parts whose names
  differ only in their folder (`a/x.pdf`, `b/x.pdf`) collapse too.
- **Why it looks wrong:** `PublicationIssueRestService.uploadFile`'s `TOO_MANY_FILES` guard tests
  `files.size() > 1`, which cannot fire for two parts posted under the same file name; one document is
  silently dropped instead of refused.

## PORT-8. `PublicationCategoryService` refuses a duplicate `categoryId` in two shapes

- **Location:** `niord/src/main/java/org/niord/core/publication/PublicationCategoryService.java`:
  `createPublicationCategory` throws `NiordWebException(…, 409)` (this repository's guard), while the
  branch's `createUnderNewId` throws `TransitionRefusedException("CATEGORY_ID_TAKEN")` rendered by the
  publications error catalogue.
- **Why it looks wrong:** one rule, two wire shapes on two methods of the same class. Kept deliberately
  during the port; worth one ruling once the catalogue advice is the only publications error shape.

## PORT-10. Two independent literals carry the legacy import's 1800 s budget

- **Location:** `LegacyImportService.IMPORT_TIMEOUT_SECONDS` (reported in the run's log line only)
  and `PublicationTxRunner.callWithImportBudget` (`@Transactional(timeout = 1800)`, the one enforced).
- **Why it looks wrong:** changing one silently makes the log lie about the budget. The number itself
  was argued from MySQL's shared `hibernate_sequence`, which this schema does not have. On PostgreSQL
  the import has been timed once, on a workstation, well inside the budget, and never on a cluster.

## PORT-13. `IssuePublicWindowService.set()` marks the caller's transaction rollback-only on a refusal

- **Location:** `org/niord/core/publication/series/IssuePublicWindowService.java` (`set`, and its javadoc).
- **Description:** the javadoc says the method deliberately does not open its own transaction so a refusal cannot
  mark the caller rollback-only. In this tree the class extends `BaseService`, whose class-level Spring
  `@Transactional` advises `set()` as a participating transaction; a `TransitionRefusedException` therefore
  sets rollback-only (`globalRollbackOnParticipationFailure` defaults to true).
- **Why it looks wrong:** a caller that catches the refusal and continues writing gets an `UnexpectedRollbackException`
  at commit instead of its own result. Ported verbatim; the javadoc no longer describes the mechanism. The scheduled
  publication sweep and the hourly firing run had the same mechanism (the error hunt of 2026-11-12); their fix of
  2026-11-13 (a transaction of its own per message or per area, `MessageTxRunner`) does not reach this method.

## RENDER4. `PublicationIssueDesc.fileSize`, `fileHash` and `fileGeneratedAt` are never written

- **Location:** `org/niord/core/publication/series/PublicationIssueDesc.java` (the three fields and their setters); no
  caller anywhere under `src/main` invokes `setFileSize`, `setFileHash` or `setFileGeneratedAt`.
- **Why it looks wrong:** both paths that produce a document — `IssuePublishService.writeFiles` (generated) and
  `IssueFileService.upload` (uploaded) — record only `fileName`, `filePath` and `fileSource`, so the columns that would
  let a released document be verified against its record, or tell two generations apart without reading the archive,
  are permanently NULL.

## PORT-17. An amend re-orders the whole issue, where the data model says surviving members keep their order

- **Location:** `org/niord/core/publication/series/IssuePublishService.java` (`amend` →
  `resolveFreezeAndWrite`, step 5: `MemberResolutionService.orderFor` and `IssueOrdering.assignSortIndex` over
  every member) against `fe/docs/publications/DATA-MODEL.md` §6.6 rule 5.
- **Description:** rule 5 says an amend does not recompute `sortIndex` for surviving members: they keep their
  relative order, an added member is placed by the recorded sort key and the indices are re-densified. The code
  sorts the whole post-override union again with the series' current sort, so a surviving member moves whenever
  its sort key (its area's place in the tree, its number, a date) changed since the release, or the sort itself
  did (an issue sorted by id is ordered by year and number since 2026-11-19, PORT-9).
- **Why it looks wrong:** the stored order exists so a re-issued document does not re-order an unchanged list;
  either the rule or the code is wrong, and nothing says which.
- **Exposed by:** the check of PORT-9's fix, 2026-11-19 (read in the code).
- **Severity:** Low — an amend replaces a document by a deliberate act with a reason, and the new document is
  ordered consistently; only the promise that survivors keep their places is broken.

# Potential errors — security review and fix sweep (2026-09)

Residues of the fixes made after the security review of September 2026, recorded rather than fixed there. The
review itself and the status of each of its findings are in
[`security-review-2026-09.md`](../../docs/security-review-2026-09.md).

## SECREV-1. Mailing-list trigger filters are handed the managed `Message` entity

- **Location:** `org/niord/core/mailinglist/MessageFilter.java` (`matches(Message)` calls the filter function with
  the entity), called from `MailingListExecutionService` for every trigger that has a `messageFilter`.
- **Description:** the filter is text an administrator enters on a mailing-list trigger. Since 2026-09 it runs on
  the sandboxed Nashorn engine, which takes away Java class lookup, reflection and the engine's `exit`, `quit`
  and `load`. The engine limits what the filter can look up, not what it can call on the objects it is handed:
  the filter still gets the managed JPA entity and can call every public method of it and of the entities it
  returns, including the ones that change them.
- **Why it looks wrong:** a filter only has to read a message. Handing it a detached value object, or a small
  read-only facade with what filters read, or replacing the script with a structured filter the way AtoN
  filters were narrowed to a grammar, would leave a filter nothing to change.
- **Exposed by:** the script-engine phase of the security fix sweep (2026-09), which sandboxed this engine and
  recorded what the sandbox does not cover in the class's Javadoc.

# Potential errors — code, data and configuration

Found by the inventory behind `docs/code-data-config-plan.md` (2026-10-19/20).

## CDI1. `CdiUtils` keeps the last-started application context in a static field, so a script can read another test's database

- **Location:** `niord/src/main/java/org/niord/core/util/CdiUtils.java:34, :44-46, :55-57` (`private static ApplicationContext applicationContext`, set by `setApplicationContext` of each context that starts, read by `getBean`); the scripts that call `CdiUtils.getBean` (`ScriptResourceCheck.ALLOWED_BEAN_TYPES`), for example `templates/mailinglist/fetch-firing-exercises.js` in DK's and GL's bootstraps.
- **Description:** in a JVM with more than one application context, every context that starts overwrites the field, so `CdiUtils.getBean` in a script resolves the beans of the context that started last, which reads another database. Every IT suite with a test that starts a context of its own on a container of its own (`BootstrapITTest`-style) has several. DK's first whole run of its C6 failed this way (`DkDgpsTemplateITTest`'s script found no area); DK's and GL's IT fixtures re-point the field at the test's own context (`DkBootstrapTemplates.pointScriptsAt`, `GlBootstrapTemplates.pointScriptsAt`). niord-base's own suite carries the same latent order dependence: `BootstrapITTest`, `BootstrapSameRunITTest` and `LiquibaseBootstrapITTest` start their own contexts beside `ITTest`'s cached one [inferred; no niord-base test has failed this way yet]. A production backend has one context and is not affected.
- **Why it looks wrong:** a test's outcome depends on which context started last, and a script under test can read and act on another test's data without any error.
- **Exposed by:** DK's and GL's C6 (`SIFA-DK/niord-dk` and `SIFA-DK/niord-gl`, 2026-10-22). Recorded, not fixed.

## SETTINGS-5. `mailSender` defaults to a DMA address in the base product

- **Location:** `niord/src/main/java/org/niord/core/mail/MailService.java:47` (`@Setting(value = "mailSender",
  defaultValue = "niord@e-navigation.net", ...)`), published in `defaults/base/settings-catalog.json`.
- **Description:** a deployment that does not set `mailSender` sends its mails from `niord@e-navigation.net`, an
  address of the Danish Maritime Authority's e-Navigation project.
- **Why it looks wrong:** the base product names no country or organisation (C5's done-when), and a mail from
  another organisation's address may be rejected or misread. `e-navigation` is not on C5's word list, which is why
  the scan missed it.
- **Exposed by:** the second review of the code, data and configuration work (2026-10-22). Pre-existing; not changed by that work.


## CATEGORY1. Every category the import finds "has changed", so it is rewritten whole on every import

- **Location:** `niord/src/main/java/org/niord/core/category/Category.java:227-239` (`hasChanged`), `:232`
  (`!Objects.equals(editorFields, template.getEditorFields())`); `editorFields` is an `@ElementCollection` with no
  `@OrderColumn` (`:67-68`), loaded as a Hibernate `PersistentBag`; `CategoryService.checkUpdateCategoryData`
  (`CategoryService.java:303-308`).
- **Description:** `PersistentBag.equals` is instance equality, so `Objects.equals` of a loaded category's editor
  fields and a template's list is false whatever they hold. Every category the category import finds therefore
  "has changed", and `updateCategoryData` rewrites it whole on every import: its template parameters are deleted
  and inserted again and its version moves on (confirmed by a run: an unchanged import replaces all five
  `TemplateParam` rows with new ids). The
  same-run rule's own comparison (`CategoryService.replaceInCategory`, since the owner's ruling of 2026-10-24;
  `keptFields` until then) compares each list as a copy (`sameList`) before it writes, and assigns a differing one
  element-wise into the row's own collection, so it is not affected.
- **Why it looks wrong:** `hasChanged` exists to skip an unchanged category, and an unchanged import writes every
  category's rows again. Copying the lists before comparing them, as `sameList` does, closes it.
- **Exposed by:** a review of 2026-10-22; pinned by the error hunt of 2026-11-12 (track A4),
  `CategoryImportRoundTripITTest#anUnchangedImportRewritesEveryTemplateParameter_documentsBug`. The opposite fault
  in `Area.hasChanged` (fields left out of the comparison) was fixed on 2026-11-13. Pre-existing; recorded, not
  fixed.

## CATEGORY2. A root category template is matched by name at any depth

- **Location:** `niord/src/main/java/org/niord/core/category/CategoryService.java:123-128` (`searchCategories`
  adds the parent predicate only when `parentId` is not null), `:394-410` (`findByName`: the first of an
  unordered result of at most one row), called by `importCategory` for each of the template's names.
- **Description:** for a root category of an import file, `findByName(name, lang, null)` searches every category,
  so a root can be found as a same-named category elsewhere in the tree, and which one is found is not defined
  when several share the name (no ordering, `maxSize 1`). The import then updates that category, or, within one
  bootstrap run, adds to it [inferred from the code; not run].
- **Why it looks wrong:** an import file's root is meant to match a root. A `parent IS NULL` predicate when no
  parent is given closes it; the firing-exercises lookup (`getFiringExercisesCategory`) reads the same way.
- **Exposed by:** a review of 2026-10-22. Pre-existing; recorded, not fixed.

## SCRIPT8. A deployment's `common.js` or `fetch-firing-exercises.js` row copied from a pre-port deployment may predate the port

- **Location:** the database rows `templates/tmpl/common.js` and `templates/mailinglist/fetch-firing-exercises.js`
  of a deployment whose script resources were copied from a deployment that ran before the port; the base
  product's `niord/bootstrap/templates/tmpl/common.js` resolves `messageService` (`:17-20`), and the script
  bindings are `em` and the builder data only (`niord/src/main/java/org/niord/core/script/JsResourceService.java:122-127`).
- **Description:** a `common.js` row without the lines that resolve `messageService`, or a
  `fetch-firing-exercises.js` row that calls `MessageService.search` (moved to `MessageSearchService` by the port),
  ends in a script error when a template derives its areas from the geometry or the firing-exercises mail runs
  [the `fetch-firing-exercises.js` row of the DK test environment's data seen stale by the error hunt of
  2026-11-12, see Exposed by; the DK and GL production rows were not checked]. GL's repository side is closed by
  GL's C6: its bootstrap carries the current files, and its release README names the overwrites.
- **Why it looks wrong:** the data of a running deployment is the truth, so a stale row stays until a sysadmin
  imports the release file and ticks the overwrite. Whether DK's and GL's rows are stale is a check for the deploy
  step. For `fetch-firing-exercises.js` that import must take a country's release of 2026-11-19 or a later one:
  from that day both countries' files call `searchOrFail`, and an earlier release brings back the lenient
  `search`, whose failure mails the list an empty schedule its readers cannot tell from a day without exercises.
- **Exposed by:** the review of the code, data and configuration plan (2026-10-20); narrowed to the
  deployment data by a review of 2026-10-22. Seen by the error hunt of 2026-11-12 (wave 3, the soak
  track): on two local copies of `niord_test` (`niord_d`, `niord_guides`) the audio-broadcast list's
  firing-exercise trigger 1949666 fails with `messageService.search is not a function`, so the list's scheduled
  mail is never made, and `GET /rest/mailing-lists/execute-report/1949666.html` answers 500 on the same script;
  `niord_test` holds the same trigger and due time. There the failed run is now retried every hour and leaves a
  mail in status Error in the Mail Queue once a day, which does not mend the row; the test environment's
  row is replaced only by a sysadmin's release import with the overwrite ticked, on a human's say-so. Open.

## TZ1. Time-dependent ITs fail by exactly one hour when they run in the hour the clocks go back

- **Location:** the timestamp columns of `niord/src/main/resources/db/changelog/changes/V001__baseline_schema.sql`,
  every one `timestamp(6) without time zone`; `niord/src/main/resources/config/niord-core-defaults.yml:45-64`
  (`spring.jpa.properties.hibernate`), which sets no `hibernate.jdbc.time_zone`, so Hibernate reads and writes
  them in the JVM's default zone. The ITs that failed are under `niord/src/test/java/org/niord/core/`:
  `publication/series/EntityCriteriaResolutionITTest`, `OneOffWindowITTest`, `IssuePublishITTest`,
  `IssueWorkMarkerITTest`, `AmendAndOverlapITTest`, `MemberResolutionDifferentialITTest`,
  `integration/NiordIntegrationImportITTest`, `mail/ScheduledMailServiceITTest` and
  `message/MessageTagServiceITTest`.
- **Description:** in the hour that happens twice when the clocks go back (02:00-03:00 local in
  Europe/Copenhagen), an instant written as a local time without a zone does not round-trip: read back, the
  local time is taken as its other occurrence, one hour off. Seventeen ITs failed that way, each by exactly one
  hour: `EntityCriteriaResolutionITTest` 8 of its 13, `OneOffWindowITTest` 2,
  `IssuePublishITTest.concurrentPublishesProduceExactlyOneStamp` ("expected Sun Oct 25 02:01:36 CEST but was
  2026-10-25 02:01:36.793"), one each of `IssueWorkMarkerITTest`, `AmendAndOverlapITTest` and
  `MemberResolutionDifferentialITTest`, `NiordIntegrationImportITTest.importDefaultsMissingPublishDateFromToAboutNow`,
  `ScheduledMailServiceITTest.getPendingMailsExcludesFutureAndSentMails` and
  `MessageTagServiceITTest.createTempMessageTagDefaultsTtlWhenNull`. A re-run after the hour passed. Cause:
  the columns without a zone, read and written in the JVM's zone [inferred; not traced]. The two regeneration
  scripts pin the zone themselves (`niord/scripts/regen-sample.sh:304` and
  `niord/scripts/regen-test-seed-postgres.sh:178-180`, `-Duser.timezone=UTC`); Surefire and Failsafe do not.
- **Why it looks wrong:** a stored instant that moves by an hour once a year is a wrong value, not only a test
  artefact: whatever the ITs check (publish stamps, publication windows, a tag's expiry, a mail's due time) is
  stored the same way in a deployment. Whether a deployment is exposed depends on its JVM's zone: the image
  (`k8s/docker/Dockerfile`, `ENV TZ=UTC`) and the chart (`k8s/helm/niord/templates/app/deployment.yaml`, a fixed
  `TZ: UTC` entry in the app container's environment, outside `app.javaOpts`, so a values file that replaces
  that value keeps it) pin the pod's JVM to UTC; only an explicit `-Duser.timezone` in `app.javaOpts` would
  override it, which the chart's README and the operators' guide say not to add. In UTC there is no repeated
  hour; a developer's or a test server's JVM in a zone with summer time has one each year, and so has an
  integration-test run.
- **Exposed by:** the first `./verify-local` of the country split's P7-bs, which ran at 02:04 local on
  2026-10-25, in the repeated hour.
- **Decided (the owner, 2026-10-25):** the JVM runs in UTC; the chart and the images set it at the deploy step
  (e.g. `TZ`/`-Duser.timezone=UTC` in `k8s/docker/Dockerfile` and `k8s/helm/niord/values.yaml`'s
  `app.javaOpts`), and the operators' guide states it (`docs/fe-country-split-plan.md` §5 step 9). Built in the
  image and the chart on 2026-11-18 (`TZ=UTC`, not in `app.javaOpts`); the entry stays open for the tests' zone,
  which Surefire and Failsafe still do not pin.

## BATCH5. Sysadmin > Batch Jobs forgets every execution at a restart, and the bootstrap's job logs with it

- **Location:** the JBeret SE environment's job repository (`niord/src/main/java/org/niord/core/conf/BatchConfig.java`
  forwards `BatchSEEnvironment`'s, which is in memory with no `jberet.properties`), read
  by the Batch Jobs page.
- **Description:** after a restart of the backend every job type lists "0 executions" ("No executions for this job
  type"). The bootstrap's import jobs and their logs, which say what each job added and, for a country's archive,
  what it replaced, are gone with them. The `BatchData` rows stay.
- **Why it looks wrong:** the operators' guide sends the installer to those logs to see what a bootstrap did, and a
  deployment restarts at every roll-out. The guide now says to read them before a restart and to check the other
  Sysadmin lists after one. A persistent job repository, or the bootstrap's job logs kept beside its marker, would
  keep the record.
- **Exposed by:** the operator route of the country guides' verification (2026-10-26,
  `docs/fe-country-split-plan.md` §5): the launcher started the backend again on a database another route had
  bootstrapped, and Batch Jobs showed 0 executions for every import job.
- **Severity:** Low — no data is lost, only the record of what the bootstrap did.

## REALM1. The local realm every country's launcher imports carries clients and groups of other deployments and a Danish sign-in page

- **Location:** `keycloak/niord-realm-local.json`, packed into `org.niord:niord-local-run:<v>:zip`
  (`scripts/local-run-zip.sh`) and imported by every country's launcher into the shared `niord-keycloak`.
- **Description:** beside `niord-web`, `niord-client-master` and the sample's domain clients (`niord-nw`, `niord-nm`,
  `niord-fa`, `niord-almanac`, `niord-annex`), the realm holds the clients `niord-gl-nw` (GL's domain), `niord-fe` and
  `niord-legacy-nw`, the groups `MAS-vagt`, `AOK Editor` and `FE Editors`, and `"defaultLocale": "da"`. A new
  country's operator meets a Danish sign-in page ("Log ind i Niord") and other deployments' clients in the console,
  and the local `sysadmin` holds roles on them. The realm holds no client for the running country's own domains;
  the country guide's *Run it locally* says how to add one by hand (`fe/docs/backlog.md` has the launcher doing it).
- **Why it looks wrong:** the base product ships no country-specific code, text or data and publishes English only
  (`country-kit/docs/responsibilities-and-upgrades.md`). The sample's Danish content is the accepted exception
  (the split plan's R11); GL's client, the three groups and the Danish default locale are not the sample's.
- **Exposed by:** the agent and operator routes of the country guides' verification (2026-10-26,
  `docs/fe-country-split-plan.md` §5).
- **Severity:** Low — local runs only. Fix: English as the default locale, only the base product's and the sample's
  clients and groups; GL's client moves to `niord-gl`'s own local set-up.

## DOMAIN1. An anonymous `GET /rest/domains/all` logs ERROR twice with a stack trace while answering correctly

- **Location:** `org.niord.core.domain.DomainService`, loading the Keycloak states of the domains it returns
  (`/rest/domains/all` is anonymous: the administration app's sign-in page and the public site read it).
- **Description:** the request answers with the domains, but the backend log holds, twice per request,
  `ERROR … DomainService : Failed loading Keycloak states for domains` followed by the full
  `java.lang.IllegalStateException: User not authenticated` stack trace. A fresh country's log shows it on the
  administration app's first page, before anyone signs in.
- **Why it looks wrong:** an anonymous read of public data is the normal case for this endpoint, not an error; at
  ERROR level with a stack trace it drowns a log that an operator reads for real failures.
- **Exposed by:** the person route of the kit move's route re-run (2026-11-02, `docs/fe-country-split-plan.md`
  §5), `curl -s http://localhost:4200/rest/domains/all` against a local country.
- **Severity:** Low — log noise only. Fix: skip the Keycloak states, or log at DEBUG, when no user is authenticated.

## K8S2. The chart cannot pin a setting whose value is a secret

- **Location:** `be/k8s/helm/niord/templates/app/deployment.yaml` (the app container's environment) and
  `templates/validate.yaml` (`app.settings`).
- **Description:** a setting is pinned per environment, over its stored row, by `app.settings` (an environment
  variable named like the key, which `SettingsService` reads before the row) or by `-D<key>=<value>` in
  `app.javaOpts`. Both are plain values: the render refuses an `app.settings` key that names a password, secret or
  token, and the chart has no value that passes a key of a Secret to the app container under a setting's name.
  The two settings of type `Password`, `pdfEncryptionPassword` and `wmsPassword`, can therefore only be the stored
  row, and a database restored from another system brings that system's values for them.
- **Why it looks wrong:** every other setting a restore must override can be pinned outside the database; these
  two cannot be without writing the secret into the values.
- **Exposed by:** a reading of the chart's pinned settings against what a restored database brings (2026-11-19).
- **Severity:** Low — both are empty by default and are changed on the Settings page after a restore. Fix: a value
  that maps a setting key to a Secret and its key, rendered as an environment variable from `secretKeyRef`, held to
  the key rules of `app.settings`.

## K8S3. On a dev-mode Keycloak the chart makes, a domain outside the sample's five has no lasting client

- **Location:** `be/k8s/helm/niord/files/niord-realm.json` (the chart's realm) and the dev-mode Keycloak the chart
  runs for test environments (`be/k8s/README.md`, the realm's import).
- **Description:** the chart's realm holds a client for each of the base sample's five domains and for no other.
  A database restored from a deployment with further domains brings domains that have no client on that realm. The
  client can be made from the Domains page's *Create in Keycloak* or in the admin console, but a dev-mode Keycloak
  keeps its data in the container and imports the realm file again at every restart, so the client and every role
  given on it are gone after the next pod restart or chart upgrade that changes the realm's checksum.
- **Why it looks wrong:** a person whose roles are on domain clients alone is refused in such a domain again after
  each restart, and nothing in the realm or the chart keeps the client a sysadmin made.
- **Exposed by:** a reading of the chart's realm against the domains a restored database brings (2026-11-19).
- **Severity:** Low — test environments only (a production-mode realm, or a realm on a shared Keycloak, keeps the
  client), and roles on the base client `niord-client-master` count in every domain. Fix: a chart value that adds
  domain clients to the realm the chart renders, or a dev-mode Keycloak with a lasting database.

## WMS1. Without the tile cache, a failed sea-chart tile stays blank in the browser for a day

- **Location:** `org.niord.web.wms.WmsProxyServlet.doGet`: `WebUtils.cache(response, CACHE_TIMEOUT)` runs before
  the provider is asked, so the redirect to `/img/blank.png` that answers a missing provider or a failed fetch
  carries `Cache-Control: max-age=86400` and `Expires`.
- **Description:** a browser may keep a redirect that carries a lifetime. One failed request for a tile, a
  hiccup of the provider's for instance, then gives that browser the blank image for that tile for 24 hours,
  whatever the provider does meanwhile.
- **Why it looks wrong:** the day is meant for a tile, not for the absence of one. With the tile cache switched on
  (`wmsCacheDays`), the servlet answers a failed tile with the same redirect kept for five minutes; the two paths
  differ only because the path without the cache was left exactly as it was.
- **Exposed by:** the sea-chart tile cache (2026-11-12, `docs/sea-chart-tile-cache-plan.md`, decision 6: recorded,
  not fixed).
- **Severity:** Low — blank tiles in one browser until the next day or a forced reload. The header also keeps
  browsers from asking a failing provider again, and this path has no pause of its own (a request waits up to
  40 s for the provider), so a fix shortens the lifetime, to the cached path's five minutes, rather than drops it.

## WMS2. The integration suite asks a real WMS host on the internet

- **Location:** `niord/src/test/resources/niord.json` sets `wmsProvider` to `http://kortforsyningen.kms.dk/` and
  `wmsPassword` to the placeholder `TO_BE_DEFINED`; `WmsProxyServletITTest`, whose Javadoc says the test
  application configures no provider.
- **Description:** the two authenticated tests of `WmsProxyServletITTest` do not take the no-provider branch they
  describe. The servlet asks that host with the placeholder as its token, the host answers 404 (the Failsafe
  output has `Failed loading WMS image for URL http://kortforsyningen.kms.dk/?TOKEN=***: FileNotFoundException`,
  once per test), and the blank redirect the tests assert comes from the failure branch.
- **Why it looks wrong:** a test run makes requests to a third party's host, depends on that host and the network
  (a host that does not answer costs each test up to 40 s), and asserts another branch than the one it names.
  `WmsProxyHttpITTest` shows the way: a local stub.
- **Exposed by:** running the two WMS integration classes for the sea-chart tile cache (2026-11-12).
- **Severity:** Low — test hygiene. Fix: leave the provider empty in the test settings, so the no-provider branch
  is the one tested, and correct the Javadoc if a provider stays.

## RENDER6. The message-list PDF prints "Series ID's" and an American time stamp

- **Location:** `MessageSearchParams` (the search description, `String.format("Series ID's: %s", ...)`) and
  `bootstrap/templates/messages/message-list-pdf.ftl` (`${.now?datetime}`, in the "Generated" row and the header).
- **Description:** the first page of the message-list report reads "Criteria: Domain: niord-nw, Statuses:
  [PUBLISHED], Kinds: [WARNING], Series ID's: [dma-nw-local, dma-nw]" and "Generated: Nov 11, 2026, 11:28:28AM".
  The criteria line is the search's English debug description, with a greengrocer's apostrophe and Java's list
  brackets, printed whatever the report's language; the time stamp is FreeMarker's default `?datetime` format,
  US order and a 12-hour clock with no space before AM, where every other date in the reports is formatted on
  purpose (`dd. MMMM yyyy` in the accumulated report).
- **Why it looks wrong:** it is the first thing a reader sees on a printed report, and the user guides' picture of
  that page shows it.
- **Exposed by:** the user guides' screenshot rig (2026-11-11, `fe/guides-shots/`, `pilot/report-first-page.png`).
- **Severity:** Low, cosmetic. Fix: a criteria line made from the report's own texts in its language, and a
  formatted time stamp in the domain's zone.

## PROM7. The Twitter settings of a type answer 500, and the dialog then offers an empty form whose save is refused

- **Location:** `org/niord/core/promulgation/TwitterSettingsRestService` (`GET
  /rest/promulgation/twitter-settings/{typeId}`, and the POST and PUT, which map their answer the same way, no
  transaction; `TwitterSettings.toVo` `:80` maps the type with its lazy `domains`, `PromulgationType.toVo` `:154`;
  `open-in-view: false`, `config/niord-core-defaults.yml`);
  `fe/ui-apps/admin/.../promulgation/twitter-settings-plugin.ts` (`load()`: an error "silently flips the dialog to
  add-mode").
- **Description:** for a type that has settings, the GET answers 500 with `LazyInitializationException: Cannot lazily
  initialize collection of role 'org.niord.core.promulgation.PromulgationType.domains' ... (no session)` (seen for
  the copy's type `twitter`, reproduced by the rig's checker). The OAuth Settings dialog takes the
  error for "no settings yet" and opens an empty form. *Save Settings* then POSTs, which the backend refuses because
  the type has settings (`TwitterPromulgationService.createSettings` `:155-158`, `IllegalArgumentException`, 400):
  the user sees the create-error toast, and no second row is written. A type without settings answers with no body
  and rightly opens the empty form; its first save, and any later PUT, store the row and then fail on the same lazy
  `domains` while building the answer, so the user sees an error for a save that took [the POST and PUT confirmed
  by the error hunt's pinned test, see Exposed by]. Once the GET works, the dialog opens with the two secrets empty,
  since `toVo` never returns them (`:86-88`), while the form requires all four credentials: changing only the
  format means typing both secrets again. The plugin's class comment says the backend returns them in clear text.
- **Why it looks wrong:** a sysadmin who opens the dialog sees the stored credentials as gone and cannot save, where
  the stored values should show; a first save reports an error although it was stored.
- **Exposed by:** the user guides' screenshot rig (2026-11-11, `TwitterSettingsDialog.png`, taken on an example type
  without settings instead). The system administrators' manual (`guides/sysadmin-manual/05-PromulgationTypes.adoc`,
  _Twitter_) describes the dialog as it should work. Pinned by the error hunt of 2026-11-12 (track A1):
  `PromulgationRoundTripITTest#twitterSettingsAreStoredButTheirWritesAndReadAnswer500_documentsBug` (the POST, the
  GET and the PUT each answer 500; the create and the update are stored, one row, checked in SQL); TESTQ6's
  `TwitterSettingsRestServiceITTest` 76 accepts the same 500. Also of this entry, handed over by the error hunt:
  `TwitterPromulgationService.getSettings` (139-147) ends `catch (Exception e) { return null; }`, the one
  single-row look-up left out when the others were narrowed to `NoResultException`, since Twitter is to go, so a
  failed read lets `createSettings` (155) try a second row, `updateSettings` (171) answer "No Settings
  exists" and the tweet path (218) send nothing; it goes with the rest of this entry.
- **Severity:** Medium where Twitter promulgation is used; the owner plans to remove Twitter promulgation
  (2026-11-12), and the entry goes with it. Fix, should it stay: a transaction around the handlers, as the NAVTEX
  transmitters' and the SafetyNET areas' handlers have; an error state in the
  dialog instead of the silent switch to an empty form; and secrets that may be left empty in edit mode to keep the
  stored ones. Neither national deployment uses Twitter/X promulgation (the owner, 2026-11-19); the code is left as
  it is and is planned for removal.

## PROM8. A promulgation type with transmitters, areas or saved Twitter settings cannot be deleted

- **Location:** `org/niord/core/promulgation/PromulgationTypeService.deletePromulgationType` (385-392, a plain
  `remove` of the type); the foreign keys on `PromulgationType` from `TwitterSettings`, `NavtexTransmitter`,
  `SafetyNetArea` and `BaseMessagePromulgation` (`db/changelog/changes/V001__baseline_schema.sql` 3418, 3502,
  3712, 3985, none with `ON DELETE`, and no later changeset touches them); `TwitterSettingsRestService` (GET, POST,
  PUT, no DELETE).
- **Description:** deleting a type removes the type row only, so a transmitter, an area or a settings row of the
  type makes the database refuse, and the page says "Error deleting promulgation type" without a reason.
  Transmitters and areas can be deleted in their dialogs first; Twitter settings cannot be deleted anywhere, so a
  Twitter type whose settings were once saved can never be deleted, only made inactive [read in the schema and the code; no delete was tried on the rig].
- **Why it looks wrong:** a type's transmitters, areas and settings are its own configuration, not records that
  refer to it from outside, and the sysadmin gets no reason and, for Twitter, no way out. A type that messages were
  promulgated with is a different case: keeping it for their history is right, and the refusal should say so.
  A contact on mailing lists is taken off them when it is deleted. An area, category or message series in use is
  refused with a 409 that names its uses since 2026-11-13; that refusal is the model for this one.
- **Exposed by:** the user guides' screenshot rig (2026-11-11). The system administrators' manual
  (`guides/sysadmin-manual/05-PromulgationTypes.adoc`, _Deleting a Promulgation Type_) describes the refusals as
  they are; change it with the fix.
- **Severity:** Low. Fix: delete the type's own transmitters, areas and settings with it, and refuse a type that
  messages refer to with that reason.

## PROM12. A promulgation without a type answers 500

- **Location:** `PromulgationManager.persistedPromulgationTypes`, `messagePromulgationGenerated` and
  `resetMessagePromulgations` (`getType().getTypeId()` on each promulgation).
- **Description:** a message body whose promulgation holds no `type` object fails with a `NullPointerException` in
  the manager, on create, update and template execution [read in the code; not run]. The entry's other half, `GET
  /rest/promulgations/promulgation-type/{typeId}` with an unknown id, answers 404 since the error hunt's fix stage
  of 2026-11-13 (`UnknownIdReadsITTest#anUnknownPromulgationTypeAnswers404`).
- **Why it looks wrong:** a client's mistake answered as a server fault, with nothing naming it.
- **Exposed by:** the review of the PROM9 fix (2026-11-12).
- **Severity:** Low. Fix: a refusal (400) that names the promulgation.

## SCRIPT9. A script resource's history can give a change the version number its previous entry has

- **Location:** `niord/src/main/java/org/niord/core/script/ScriptResourceService.java` `saveScriptResourceHistory`
  (`:303`, `hist.setVersion(resource.getVersion() + 1)`), called by `saveScriptResource` (`:91-94`) right after
  `saveEntity` and before the flush that raises the row's JPA `@Version` (`VersionedEntity`).
- **Description:** an entry is numbered the resource's version, as the save holds it before the flush, plus one.
  Over the editor's REST calls that numbers right: a resource created and changed twice has the entries 1, 2 and 3
  (the error hunt's guard, see Exposed by), so the first update of a resource this backend created does not say 1 again
  on that path, as this entry first read the code. Which path leaves the version unmoved was not traced; the one
  case seen was a release import. In `niord_test` and `niord_guides` (read on 2026-11-11) every
  history follows row version + 1 except `templates/messages/nm-accumulated-report-pdf.ftl`, the one resource
  this backend has changed: two entries, both version 1 (created 2026-09-14, release import 2026-11-02), row version 1.
- **Why it looks wrong:** the History dialog keys its rows and its selection on the version
  (`fe/ui-apps/admin/.../sysadmin/script-resources/script-resource-history-dialog.ts` 57-62 and `.html` 25-38): ticking
  either version-1 row ticks both and shows the two compared, neither can be shown alone, and a third tick selects
  three rows and shows nothing [read in the code; not clicked on the rig].
- **Exposed by:** the user guides' screenshot rig (2026-11-11, `guides/sysadmin-manual/09-ScriptResources.adoc`,
  `ScriptResourceHistory.png`, which shows the two rows). Not reproduced over the editor's REST calls by the error
  hunt of 2026-11-12 (track A1): `AdminDataRoundTripITTest#aScriptResourceKeepsItsContentAndNumbersItsHistory` (a
  guard) reads 1, 2, 3; the release import's path is the one to re-check.
- **Severity:** Low. Fix: number an entry one above the resource's highest history version, and key the dialog on
  the entry's id.

## RENDER7. The base product's report PDFs print their date the Danish way in English

- **Location:** the base product's templates `bootstrap/templates/messages/nm-report-pdf.ftl:78`,
  `nm-tp-report-pdf.ftl:53`, `nm-almanac-report-pdf.ftl:35`, `nm-annex-report-pdf.ftl:40`, `fa-list-pdf.ftl:40`
  (`${.now?string["dd. MMMM yyyy"]}`) and `nm-accumulated-report-pdf.ftl:249,251` (the period lines); the English
  value of `mail.date_format.long` in `bootstrap/texts.json` (`d. MMMM yyyy`).
- **Description:** the pattern is written into the templates, not taken from the report's language, so the English
  NM report prints "11. November 2026": the day with a full stop is the Danish form. The base product's own English
  date formats elsewhere are `d MMMM yyyy` (`msg.time.date_format`).
- **Why it looks wrong:** the base product publishes English only, and since the owner's ruling of 2026-11-11 (no
  Danish in English texts) the user guides' guard treats the date as Danish; RENDER6 is the same kind of fault on
  the message-list PDF, which calls the accumulated report's pattern deliberate.
- **Exposed by:** the user guides' screenshot rig (2026-11-11): its guard refuses the copy's NM report as Danish,
  so `guides/sysadmin-manual/11-PrintReports.adoc` shows the Standard report instead (`PrintReportExample.png`).
- **Severity:** Low, cosmetic. Fix: a date-format text per language read by the templates; the templates are data
  a deployment receives by a release import (`docs/code-data-config-plan.md`), so a fix ships as one. The error
  hunt's fix of 2026-11-13 to line 53 of `nm-almanac-report-pdf.ftl` ships that template in the release folder
  `be/niord/bootstrap/releases/2026-11-12-error-hunt-templates/` and leaves line 35, this entry's, as it was.

## KEYCLOAK1. A domain whose client the Keycloak integration creates gets no `publication-curate` role

- **Location:** `niord/src/main/java/org/niord/core/keycloak/KeycloakIntegrationService.java` `createKeycloakDomain`
  (259-323): the roles created for the new client are `user`, `editor`, `admin` and `sysadmin` (296-301); and
  `niord/src/main/java/org/niord/core/user/Roles.java` (35-53), whose ADMIN fallback on the curation endpoints is
  "to be removed one release after the deployed realms are re-imported".
- **Description:** the realm files in the repository carry `publication-curate` on every domain client, but a domain
  created from Sysadmin > Domains with the Keycloak integration has no such role, so a curator cannot be given the
  right in it until a system administrator creates the role by hand in Keycloak. Today the ADMIN fallback lets an
  administrator curate there; once it is removed, as `Roles.java` plans, the curation endpoints of such a domain
  admit nobody until the role is added [read in the code; not run].
- **Why it looks wrong:** the integration exists to set up a domain's client completely, and the role it leaves out
  is the only one the publication curation needs.
- **Exposed by:** the user guides' writers (2026-11-11, the system administrators' manual, part 2). The manuals
  (`guides/sysadmin-manual/A1-KeycloakIntegration.adoc` and `guides/whats-new/05-SystemAdministrators.adoc`) tell the
  reader to add the role by hand; change them with the fix. Related: the go-live row "No curator role" in
  `fe/docs/backlog.md`.
- **Severity:** Low today; Medium once the ADMIN fallback is removed. Fix: create `publication-curate` (not part of
  the composite ladder) with the other four roles, before the fallback goes.

## KEYCLOAK3. The integration tests sign in against Keycloak 22.0.5, a version no environment runs

- **Location:** `niord/src/test/java/org/niord/test/NiordTestContainers.java` `KEYCLOAK_IMAGE` (57,
  `quay.io/keycloak/keycloak:22.0.5`), whose comment (51) says the images are pinned to the versions of
  `docker-compose.yml`, and `docs/test-infrastructure.md` (211), whose table heading says the same; against
  `docker-compose.yml` (51) and `k8s/helm/niord/values.yaml` (`keycloak.image.tag`, 343), both `26.8.2`. The
  integration tests' realm, `niord/src/test/resources/keycloak/niord-realm.json`, was exported from 22.0.5
  (`keycloakVersion`, 3851).
- **Description:** every integration test that signs in gets its token from a 22.0.5 server and validates it
  against that server's keys: the token requests (`KeycloakTestTokens`, `JwtAuthorizedPartyITTest`), the
  resource server's `issuer-uri` (`ITTest` and the classes that set their own), and the endpoint sweep's cells that
  point the backend's admin calls at the container (`web/sweep/SystemSweepKeycloak.java`, `realm`). The local run
  and the chart run 26.8.2, and the stagings' shared Keycloak is pinned to the same version
  (F-X114 in the repository root's `docs/security-review-2026-09.md`).
- **Why it looks wrong:** the suite proves the login integration against a server that no environment runs, four
  major versions behind. A change between them in the tokens, the realm import or the Admin API is met only by
  hand: 26.8.2's Admin API accepts only a token with the client scope `kc-admin-api` (`k8s/README.md` 268-274),
  which a 22.0.5 server never asks for, so no test can tell whether the realm files carry it.
- **Exposed by:** the preparation of a Keycloak bump checklist, 2026-11-18 (read in the code and the pins; not
  run).
- **Severity:** Low, tests only; it narrows what a green CI says about sign-in. Fix, the smallest step: set
  `KEYCLOAK_IMAGE` to the chart's pin (and the table row with it), run the integration suite
  (`be/test-it`), and see what fails; then keep the two pins together, as the comment already claims.

## TESTQ6. Five REST ITs accept a 500 as a refusal, and the one IT that reloads two of each list skips on CI

- **Location:** `niord/src/test/java/org/niord/web/DomainRestServiceITTest.java` 249 and 314,
  `NiordIntegrationRestServiceITTest.java` 344 and `MessageRestGeometryITTest.java` 349
  (`statusCode(greaterThanOrEqualTo(400))`); `core/promulgation/TwitterSettingsRestServiceITTest.java` 76
  (`statusCode(not(anyOf(is(401), is(403))))`, its one authorised GET);
  `niord/src/test/java/org/niord/core/publication/series/IssueRenderMemberLoadITTest.java` `requireTheMemberCorpus`
  (244-252, `Assumptions.assumeTrue(present > 100, ...)`).
- **Description:** the first four expect a bad request to be refused (a duplicate or a missing domain, an unknown
  integration, a body that is not JSON) and pass on any status from 400 up, so a handler that starts to answer 500
  for the same input stays green; the Twitter test passes on PROM7's 500 today. `IssueRenderMemberLoadITTest`
  persists messages with two parts, intervals, references and attachments and asserts two of each on reload, which
  would have caught the order-column fault of the four message lists (fixed since, with `MessageListOrderITTest`),
  but it runs only when over 100 members of a developer's seeded corpus
  (`be/scripts/seed-dev-database.mjs`) are in the database, so on CI and in a plain `be/test-it` run it skips with
  BUILD SUCCESS. Five more ITs assume the same corpus (`MemberResolutionDifferentialITTest`,
  `ResolutionDiagnosticsITTest`, `PublicationResolutionITTest`, `IssueLifecycleITTest`, `IssueInvariantsITTest`)
  [read in the tests; none was run].
- **Why it looks wrong:** a test that accepts the fault it should catch, or that never runs where the suite runs,
  reads as coverage that is not there.
- **Exposed by:** the user guides' check (2026-11-12, its map of the backend tests made after PROM7 and the message
  lists' order fault).
- **Severity:** Low, tests only. Fix: assert the status the handler means (400, 404 or 409);
  `MessageRestServiceITTest:652`, which pins a 500 on purpose, stays. The message lists' round trip through REST
  (two and three of each, read back, saved again) runs on CI since `MessageListOrderITTest`.

# Potential errors — the error hunt of 2026-11-12

The error hunt of 2026-11-12 filed 83 entries in this section and 17 in `fe/docs/backlog.md`, each candidate
checked by a second agent before it was filed. Track A wrote data through REST and read it back (round-trip
integration tests), track B tried formats and times over grids of values (property tests), track C read the paths
a warning takes to its readers, track D held the frontend's calls against the backend's handlers and roles. A
second wave added the imports and their round trips, the bootstrap's templates and reports over a matrix of
parameters, and fault-injection integration tests of track C's records; a third wave ran on the live system. The
detector check of the same day planted faults, repaired pinned tests and tried provisional fixes against them; its
reports, named in the entries by file, are in `user-guides-notes/error-hunt/detector-check/` beside the worktrees,
outside the repository.

The fix stage of 2026-11-13 fixed every entry of the hunt that is no longer listed: each fix came with a regression
test, most of them the entry's `_documentsBug` pin turned round, and git history keeps the entries it removed. The
entries still listed are of three kinds: those the fix stage left on purpose, each saying why and what is
recommended, for the owner to decide (MAILLIST1, SERIES7, SCRIPT10, API3, SETTINGS-7, MSG6); the part of
an entry the fix stage fixed only in part (DOMAIN2); and, under a line of their own at the end
of the section, the faults the fix stage met on its way and did not fix. Paths are under `niord/src/main/java/`
unless they say otherwise.

## MAILLIST1. Every save of a mailing list replaces all its trigger rows with new ids

- **Location:** `org/niord/core/mailinglist/MailingListService.java` `updateMailingList` (152-187; 167-168 clear the
  triggers and add new ones) with `orphanRemoval = true` (`MailingList.java` 76-77); `MailingListTrigger.toVo`
  (194 on) carries no id.
- **Description:** a PUT of the list exactly as read deletes every trigger row and inserts new ones: the count
  stays, the ids change. A reports dialog open during the save holds stale trigger ids. A report link carries a
  one-time ticket valid for a minute, so a bookmarked link fails anyway. A new scheduled row keeps the stored row's
  `nextScheduledExecution` when its schedule is unchanged and the list was active; otherwise it is computed again.
  A save that commits between the scheduler's read of the due triggers and its run of one deletes the row the run
  looks up, so that run fails with "Invalid trigger"; a new row that kept the due time is sent a minute later. The
  mailing-list import replaces the list's triggers with the file's the same way (track A4).
- **Why it looks wrong:** an unchanged save should change nothing; no other consequence a user sees was found.
- **Exposed by:** the error hunt of 2026-11-12, track A1, a round-trip integration test:
  `MailingListRoundTripITTest#anUnchangedSaveReplacesEveryTriggerRow_documentsBehavior`.
- **Severity:** Low. Fix: carry the trigger id in `MailingListTriggerVo` and update existing triggers in place.
- **Left by the fix stage (2026-11-13):** no consequence a user sees was found, and the fix changes the admin app's
  value object and the import's matching (a trigger id from another deployment must never match); recommended as a
  follow-up with the next change to mailing lists, the pin staying until then.

## SERIES7. A deleted message series leaves its number sequence, which a series re-created with the same id continues

- **Location:** `org/niord/core/message/MessageSeriesService.java` `deleteMessageSeries` (207-218, removes the
  series row only); the keys `MESSAGE_SERIES_NUMBER_<seriesId>_<year>` and, for a Continuous series,
  `MESSAGE_SERIES_NUMBER_<seriesId>` (45-46, 243-246) in the table `Sequence`.
- **Description:** set a Yearly series' next number for 2030 to 17 and delete the series: the `Sequence` row stays,
  and a series created again with the same id answers 17. A Continuous series carries over its whole sequence.
  Since 2026-11-13 only a series with no messages, no domain, no firing schedule and no S-124 configuration can be
  deleted (a series in use answers 409 naming its uses).
- **Why it looks wrong:** a delete leaves state that the next series of the id inherits silently. It may be wanted:
  carrying on avoids reusing short ids already published. The owner's call.
- **Exposed by:** the error hunt of 2026-11-12, track A1, a round-trip integration test:
  `ReferenceDataRoundTripITTest#aDeletedSeriesLeavesItsNumberSequenceBehind_documentsBehavior`.
- **Severity:** Low — only after a sysadmin deletes an unused series and creates it again under the same id. Fix:
  delete the series' `Sequence` rows with it, or keep them and say so on the series page.
- **Left by the fix stage (2026-11-13):** the owner's call, as above; recommended: keep the sequence, which avoids
  reusing a short id that was once published, and say so on the series page.

## SCRIPT10. `formatPos` with `navtex` or `dec` and 0 or 4+ decimals falls back to DEC_3, with degree signs

- **Location:** `org/niord/core/script/directive/LatLonDirective.java`: the cache holds `dec-1..3` and `navtex-1..3`
  only (43-51); `resolvePositionFormat` (136-159) falls back to `LATLON_DEC_3` (153-156); the NAVTEX separator
  (103-108).
- **Description:** `<@formatPos format="navtex" decimals=0>` and `format="navtex-4"` print
  `56° 59.964'N 009° 17.646'E`; `dec` with 0 or 4 decimals prints three. `audio` builds any count
  (`PositionUtils.getAudioFormat`); these do not. `decimals` comes from `params.posDecimals` (`common.ftl` 14, 199).
- **Why it looks wrong:** a template asking for the NAVTEX form at a precision silently gets another form. (Its
  degree signs no longer reach a NAVTEX text: since 2026-11-13 every NAVTEX text is held to the NAVTEX alphabet
  when it is generated and saved, which maps them to spaces; the wrong position form remains.)
- **Exposed by:** the error hunt of 2026-11-12, track B1, a property test, `LatLonDirectiveGridTest`:
  `navtexWithNoDecimalsOrFour_printsTheDecimalFormWithDegreeSigns_documentsBug`.
- **Severity:** Low — latent: the admin app always sends `posDecimals` 1, and no template asks otherwise. Fix: build
  the format for any count, or refuse an unsupported one loudly.
- **Left by the fix stage (2026-11-13):** latent, as no template or dialog asks for another precision, and a fix
  needs a choice the entry leaves open (what 0 decimals prints, and how a refusal is shown when the directive
  turns its exceptions into an empty output and a warning in the log); recommended to decide with the next template
  that wants another precision.

## API3. The public API ignores a malformed `wkt` and answers every message

- **Location:** `org/niord/core/message/MessageSearchParams.java` `extent(String)` (296-302, the `ParseException`
  ignored); `org/niord/web/api/AbstractApiService.java` 120, serving `ApiRestService`
  (`GET /rest/public/v1/messages`) and `ApiWebService` (51).
- **Description:** a truncated WKT such as `POLYGON((1 2` leaves no extent, so the answer is 200 with every
  published message of the default domains, NW-001-26 included, which a valid Southern Ocean polygon excludes. WKT
  that parses into an invalid geometry gives no server error: a self-intersecting polygon is applied as given (200,
  filtered, nothing logged).
- **Why it looks wrong:** a client asking for its route area gets the whole set as if it matched, and cannot tell a
  bad query from a real answer.
- **Exposed by:** the error hunt of 2026-11-12, track C, read in the delivery path, then reproduced by track CT,
  `DeliveryFaultRestITTest#aMalformedWktIsIgnoredAndAnswersEveryMessage_documentsBug`;
  `aSelfIntersectingWktPolygonIsAppliedAsGivenWithoutAServerError` guards the invalid-geometry case.
- **Severity:** Low — it errs towards more messages. Fix: 400 on an unparsable WKT; the v1 API is frozen, so that
  changes its contract, which is the owner's call.
- **Left by the fix stage (2026-11-13):** a 400 changes the frozen `/rest/public/v1` contract; recommended: keep v1
  as it is and give a later version of the API the 400.

## SETTINGS-7. A Password setting saved back as the settings API reads it is erased

- **Location:** `org/niord/web/SettingsRestService.java` `SettingVo(Setting)` (221-228, leaves a Password's value
  out), `GET /rest/settings/setting/{key}` (149-164) and `updateSetting` (175-183, the PUT);
  `org/niord/core/settings/SettingsService.java` `set` (710-740, sets the value unconditionally); the import has the
  guard (`BatchSettingsImportProcessor.java` 80-82), the PUT does not.
- **Description:** `pdfEncryptionPassword` set in SQL, read with the GET (no value) and sent back unchanged with the
  PUT: 200, and the stored `setting_value` is NULL, unless the setting is declared required. The settings page sends
  nothing unless the value changed (`setting-editor.ts`, `saveDisabled`; not re-checked), so it is an API client or a
  script that erases the password.
- **Why it looks wrong:** saving what was read should change nothing.
- **Exposed by:** the error hunt of 2026-11-12, track A4, a round-trip integration test:
  `LeftoverRoundTripITTest#aPasswordSettingSavedBackAsReadIsErased_documentsBehavior`. Track A1 noted it, unrun.
  A stored row that still holds the password's former published default is another matter.
- **Severity:** Low — not reachable from the application; a script erases the PDF encryption or WMS password. Fix:
  in `updateSetting` read a missing or null value of a Password setting as "unchanged" and an explicit empty string
  as a clear, the distinction the import makes.
- **Left by the fix stage (2026-11-13):** not reachable from the application, and no text, mail or message is wrong
  because of it; recommended as a Low follow-up (the fix above, in `updateSetting`) unless the owner wants it sooner.

**Wave 3.** The entries from here to the fix stage's line below come from the hunt's third wave, run on the live
system: a duty officer's scenarios in a browser on the local rig (tracks E1 to E4), the old system held against the
new (F1, F2), every endpoint called once per role (D1, D2a, D2b) and the three backends' logs read after the wave
(the soak track).

## MSG6. A cancellation warning's reference names the cancelled warning by its uid, where every other reference shows a short id

- **Location:** `org/niord/core/message/MessageService.java` `composeCancellationWarning` (880:
  `reference.setMessageId(source.getUid())`; the title at 885 uses `defaultIfBlank(source.getShortId(),
  source.getUid())`); the readers that print the stored id as it is: `bootstrap/templates/messages/message-support.ftl`
  355-359 (the PDF and the details), `MessageMapper.java` 235 (the public `MessageVo`), and the admin app's
  `fe/ui-apps/admin/src/app/features/messages/components/message-reference-type/message-reference-type.html` 3.
- **Description:** NW-355-26, the cancellation warning of NW-354-26, reads "References
  8aa22d57-7858-4f6e-ad9e-30c54b4f0305 - (cancelled)." in its PDF, in the admin app's status page and preview, and
  as `references[0].messageId` in the public API. A reference an editor adds by hand stores the short id (NW-354-26's
  reads NW-353-26); in `niord_test` 7,942 references hold a short id and 2 a uid. The uid is the documented and
  tested choice (`docs/s124-cancellation-plan.md` 127, `MessageCancellationWarningTest` 136), but neither gives a
  reason, and every reader of a reference also takes a short id: `S124Service` 280 and `S124DatasetService` 278
  resolve it through `messageService.resolveMessage`, and the named query `Message.findByMessageId` matches uid or
  short id. `bootstrap/templates/tmpl/navtex.ftl` 22 would print the uid in a NAVTEX cancellation line too; no NAVTEX
  text is generated for a cancellation warning, so that is latent.
- **Why it looks wrong:** the References line shows an internal id where every other reference shows the warning's
  number. Nothing is lost: the title just above it reads "Cancellation of NW-354-26", and since 2026-11-13 the
  mailing-list mail of a cancellation warning prints that title too.
- **Exposed by:** the error hunt of 2026-11-12, wave 3, track E1, a duty officer's scenario in a browser on the
  local rig: after cancelling NW-354-26, an anonymous GET of `/rest/message-reports/message/NW-355-26.pdf?lang=en`
  and of `/rest/public/v1/message/NW-355-26?lang=en` showed the uid, and the Reference rows of NW-355-26 and
  NW-354-26 held the uid and NW-353-26. The pinned test asserts the uid.
- **Severity:** Low — cosmetic and inconsistent, no information lost. Fix: store
  `StringUtils.defaultIfBlank(source.getShortId(), source.getUid())` as the reference's `messageId`, as the title
  does, and change the plan document's field table and `MessageCancellationWarningTest` with it; or resolve the id to
  a short id where it is rendered.
- **Left by the fix stage (2026-11-13):** the uid is the documented choice, and about ten test classes find minted
  warnings by it; recommended: keep the uid, or, if the owner wants the short id shown, store it for new
  cancellation warnings in one change with the plan document and those tests.

## DOMAIN2. The backend drops or keeps unknown references without a word

- **Location:** `org/niord/core/domain/DomainService.java` `updateDomain` (unknown area and category ids dropped by
  `persistedList`, unknown series by `persistedMessageSeries`); `org/niord/core/category/CategoryService.java`
  `createCategory` (a root category's unknown domain dropped by `persistedDomains`);
  `TemplateExecutionService.createParamType` (an unknown dictionary entry or parameter type kept);
  `org/niord/core/integration/NiordIntegrationService.java` `createNiordIntegration` (an unknown target series
  kept).
- **Description:** each of these answers 200 and stores the data: `PUT /rest/domains/domain/{id}` with an unknown
  area, category or series id, which is dropped without a word; a root category created with an unknown domain (the
  domain is dropped); a parameter type naming an unknown dictionary entry id or an unknown parameter type; a Niord
  integration mapped to an unknown target series. The entry's other parts (an unknown domain time zone, a negative
  next number, nameless areas, categories and sources, an invalid contact address) are refused with 400 since the
  error hunt's fix stage of 2026-11-13 (`ServerSideValidationITTest`).
- **Why it looks wrong:** the backend should refuse what its own forms and imports refuse; a sysadmin is not told
  that a reference was dropped. The archive import and the integration, which drop such references the same way,
  name each one they leave out in their log since 2026-11-13.
- **Exposed by:** the error hunt of 2026-11-12, wave 3, track D2b, every write endpoint called once per role on a
  local copy, with a valid body and then with each malformed one; the stored rows were read back by GET and SQL.
  The parameter-type and integration cases were read in the finder's results and the code, not run again. No test
  pins it yet.
- **Severity:** Low — it takes a sysadmin's script, an edited import file or a form bug. Fix: answer 400 naming
  each referenced id that does not exist, or record each dropped one as the imports do.
- **Left by the fix stage (2026-11-13):** refusing unknown references changes what the imports between deployments
  do, so the fix stage recorded them in the imports' logs rather than refusing them, and left this part to the
  owner; recommended: the same, a log line or a warning in the answer naming each reference left out, not a
  refusal.

**Found by the fix stage.** The entries from here on are faults the error hunt's fix stage of 2026-11-13 met while
it fixed the entries above, and did not fix because they were outside the fault it was fixing. Each was read in the
code by the developer or the checker of the cluster named; most were not run.

## PROM18. A NAVTEX transmitter or SafetyNET area write for a name or type that does not exist answers 500

- **Location:** `org/niord/core/promulgation/NavtexPromulgationService.java` `updateTransmitter` and
  `createTransmitter`; `org/niord/core/promulgation/SafetyNetPromulgationService.java` `updateArea`.
- **Description:** `PUT /rest/promulgation/navtex/transmitters/{typeId}/transmitter/{name}` for a name that does
  not exist dereferences the null from `findTransmitterByName`, a `NullPointerException` and a 500; `updateArea`
  does the same for a SafetyNET area. A POST of a transmitter for a `typeId` that names no promulgation type sets a
  null type on the `@NotNull` column and answers 500 at the flush.
- **Why it looks wrong:** a client's mistake answered as a server fault, with nothing naming it.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, clusters A15 and B18 (read in the code; not run).
- **Severity:** Low. Fix: 404 for an unknown name or type, as the reads answer since 2026-11-13.

## TMPL17. `getListValue` in `common.ftl` throws for a list value without a description

- **Location:** `be/niord/bootstrap/templates/tmpl/common.ftl`, `getListValue` (`<#elseif defaultValue>`, a string
  in a boolean test).
- **Description:** for a list value whose `descs` list is empty, the function throws instead of returning its
  default. Every caller is reached: `renderListValue`, and `exercise.ftl`'s exercise text since 2026-11-13.
- **Why it looks wrong:** a default that cannot be returned is no default.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster A17 (read in the template; not run).
- **Severity:** Low — a list value from the parameter types always carries descriptions. Fix:
  `<#elseif defaultValue?has_content>`. A template fix ships to running deployments by a release import.

## TMPL18. An exercise without a type gives a NAVTEX text with no subject and a title that is only the area

- **Location:** `be/niord/bootstrap/templates/tmpl/exercise.ftl` (the subject and the NAVTEX lines).
- **Description:** with no exercise type, the NAVTEX text starts with the times ("120800 UTC MAY 26 - 121200 UTC
  MAY 26 / 55-30.0N 010-30.0E. GUARD VESSELS ...") and the subject is empty, so the title is only the area lineage
  ("Denmark. The Baltic Sea."). The details say "An exercise will take place" since 2026-11-13.
- **Why it looks wrong:** a warning's title and NAVTEX text that do not say what it warns of.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster A17 (rendered by
  `BootstrapTemplateMatrixITTest`).
- **Severity:** Low — the editor sees the title before saving. Fix: "Exercise" as the subject and the NAVTEX
  heading when no type is chosen.

## PUBSERIES2. `DomainSeriesExpander.seriesIdsOf` fails outside a transaction

- **Location:** `org/niord/core/publication/series/criteria/DomainSeriesExpander.java` `seriesIdsOf`, which
  reads the lazy collection `Domain.messageSeries` of a domain fetched by `DomainLookupService`.
- **Description:** a caller outside a transaction gets a `LazyInitializationException`. Every caller today runs
  inside a service's transaction (the series import asks through `PublicationSeriesService` since 2026-11-13); a
  future caller on a batch or scheduler thread would meet it.
- **Why it looks wrong:** a public method that works only by the luck of its callers' transactions.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster A13 (met while fixing the series import; read in
  the code).
- **Severity:** Low, latent. Fix: a JPQL join for the ids, or a transaction on the expander's two public methods.

## ERR6. A publication saved with a category that does not exist answers 500

- **Location:** `org/niord/core/publication/PublicationService.java` `updateRelatedEntities` (the publication's
  category).
- **Description:** `POST /rest/publications/publication` with a category whose `categoryId` names no category
  replaces it with null, and the NOT NULL column fails at the flush, a 500.
- **Why it looks wrong:** the same class of fault the moves, the tree writes and the reads answer with 404 or 400
  since 2026-11-13.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B18 (read in the code; not run). The area and
  category creates under an unknown parent, the entry's other part (cluster B2; run by the wave 3b endpoint sweep's
  full run, `sweep-reference-data-0252` and `-0323`: a 500 from `… addChild(…) because "parent" is null`), were
  fixed on 2026-11-14 (wave 3b fix lane C): they look the parent up as the moves do and answer 404 "No Area with id
  …" / "No Category with id …".
- **Severity:** Low. Fix: a 400 naming the unknown category.

## INT7. An answer that serves one message id twice stops the whole integration run

- **Location:** `org/niord/core/integration/NiordIntegrationExecutionService.java` `processNiordIntegration`, the
  map of the served messages (`Collectors.toMap(MessageVo::getId)`, with no merge function).
- **Description:** an answer from the other Niord that holds one id twice throws `IllegalStateException` and stops
  the run for every series, logged only at ERROR by the method's catch (INT4).
- **Why it looks wrong:** one bad row of the other server's answer stops every series of the integration.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B6 (read in the code; not run).
- **Severity:** Low — it needs a broken source server. Fix: a merge function that keeps one copy and logs the
  duplicate, as the own copies' map does since 2026-11-13.

## PUB3. A template report parameter whose value is JSON null throws when a publication is made from its template

- **Location:** `org/niord/core/publication/Publication.java` `updateFromTemplate`, which expands a template report
  parameter with `value.toString()`.
- **Description:** a parameter whose value is JSON null throws a `NullPointerException`.
- **Why it looks wrong:** an empty parameter should expand to nothing, not fail the publication.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B7b (read in the code; not reproduced).
- **Severity:** Low. Fix: expand a null value as an empty text.

## ISSUE10. The message's publications panel misses a weekly issue whose series' criteria hold a domain

- **Location:** `org/niord/core/publication/series/MessageIssueLookup.java` `wouldContain`, which resolves the
  criteria with `EffectiveCriteria.resolvedFor(issue)`, without the domain expander the release and the workbench
  use.
- **Description:** for a series whose criteria hold a domain node, the resolve throws and `wouldContain` answers
  false, so the message's publications panel shows no LIVE row for the issue, and since 2026-11-13 no MISSED row
  either.
- **Why it looks wrong:** the panel shows too few rows for such a series; it never shows a wrong one.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B8b (read in the code; not run).
- **Severity:** Low. Fix: resolve with the domain expander, as the release does.

## ISSUE11. The New issue dialog shows a close equal to the cadence close as entered by hand

- **Location:** `org/niord/core/publication/series/IssueDraftService.java` (the draft marks any `explicitTo` as
  MANUAL).
- **Description:** since 2026-11-13 an issue created from the dialog records an edge that lands on the cadence as
  NOMINAL, but the draft still marks it MANUAL, so a dialog opened from a gap row whose close equals the cadence
  close shows it as entered by hand while the issue created from it records NOMINAL.
  `IssueDraftITTest#anExplicitIntervalThatLandsOnACloseIsStillChained` pins MANUAL.
- **Why it looks wrong:** the dialog and the stored issue disagree about the same edge.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B23.
- **Severity:** Low, cosmetic. Fix: take the draft's explicit close's source from the rule `boundSourcesFor` uses.

## ISSUE12. A predecessor whose successor is published can be given another edition

- **Location:** `org/niord/core/publication/series/IssueLifecycleService.java` (New edition).
- **Description:** since 2026-11-13 New edition answers with the open successor while one is OPEN. Once that
  successor is PUBLISHED, New edition on the predecessor makes another "edition 2" beside the published one.
- **Why it looks wrong:** two editions of the same number for one issue.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B24 (the plan's named risk; not run).
- **Severity:** Low. Fix: refuse New edition on an issue that a published edition already supersedes, or make the
  edition from the latest edition.

## CONTACT3. Changing a contact's address to one another contact has answers 500

- **Location:** `org/niord/core/user/ContactService.java` `updateContact`, which copies the new address without
  asking whether another contact has it.
- **Description:** the update fails at the flush on the unique key and answers 500; creating a contact with a
  taken address answers 400 since 2026-11-13.
- **Why it looks wrong:** a client's mistake answered as a server fault, and the create and the update disagree.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B14 (read in the code; not run).
- **Severity:** Low. Fix: the create's check in the update, answering 400.

## DOMAIN3. An update can blank every name of an area, a category or a source

- **Location:** the update paths of `AreaService`, `CategoryService` and `SourceService` (`PUT` of an area,
  category or source).
- **Description:** since 2026-11-13 a create without a name is refused with 400; the name checks cover create only,
  so a `PUT` can still store the row with every name blank.
- **Why it looks wrong:** a nameless row the create refuses can still be made by an update.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B14 (its checker; read in the code).
- **Severity:** Low. Fix: the same check on update.

## SERIES4. The message-series refusals run the id into the text

- **Location:** `org/niord/core/message/MessageSeriesService.java` `createMessageSeries` and `updateMessageSeries`.
- **Description:** the refusals read "Cannot create message series with duplicate message series IDs<id>" and
  "... non-existing message series IDs<id>", with no space before the id.
- **Why it looks wrong:** a sysadmin reads a run-together id.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B14b.
- **Severity:** Low, cosmetic. Fix: a space, or the id in quotes.

## MAILLIST7. A test of a status-change trigger without a message, or with a null trigger, answers 500

- **Location:** `org/niord/web/MailingListRestService.java` `test-execute-trigger`;
  `MailingListExecutionService.executeStatusChangeTrigger`.
- **Description:** a test of a STATUS_CHANGE trigger without `messageId`, or with one that names no message, passes
  a null message to `executeStatusChangeTrigger` [not checked whether that fails]; a trigger list holding a null
  element still answers 500.
- **Why it looks wrong:** a client's mistake answered as a server fault, as the other message-side writes no longer
  do since 2026-11-13.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B18 (read in the code; not run).
- **Severity:** Low. Fix: 400 for a missing or unknown message and for a null trigger.

## CG7. A `-` before the longitude is read as a divider, and the hemisphere letter decides

- **Location:** `org/niord/core/geojson/PlainTextConverter.java` (`POSITION_FORMAT`, 84) and the admin app's
  `parseLatLon` (`fe/ui-apps/admin/src/app/shared/utils/coordinate-format.ts` 124).
- **Description:** a `-` just after the latitude's hemisphere letter is eaten as a divider, so `55 30N -012 00E`
  is read as east.
- **Why it looks wrong:** read as a sign, the `-` says west while the letter says east, and nothing tells the
  editor which one won.
- **Why it is left:** the input contradicts itself only when the editor also typed the wrong letter, and the
  letter is what every position here is read by. Refusing a `-` there would refuse `64 10N -051 43W`, which is
  read correctly today and is pinned by
  `PlainTextConverterGridTest.aDashBeforeTheLongitude_isADivider_andTheHemisphereLetterDecides` and the admin
  app's `coordinate-format.spec.ts`. The other half of this entry, a fraction of a degree followed by minutes
  (`56.5 30N` read as 57°), is refused by both parsers since 2026-11-19, and minutes with a fraction followed
  by seconds (`56 30.5' 20"N`) since 2026-11-20 (the admin app's parser reads no seconds at all).
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster C12 (left by the plan as a named risk; read in
  the code).
- **Severity:** Low. Fix, if it is ever wanted: accept the divider only as a `-` with a space after it, and
  refuse a sign joined to the longitude's digits; decide first what happens to `64 10N -051 43W`.

## TESTQ7. Two publication tests set a cut-off zone that nothing reads, so they run in UTC without saying so

- **Location:** `be/niord/src/test/java/org/niord/core/publication/series/IssueListWireTest.java` and
  `IssueTimelineTest.java` (their calls of `setNominalCutoffTimeZone("Europe/Copenhagen")`).
- **Description:** `cutoffZone()` does not read the value those tests set, so the tests run in UTC while they read
  as Copenhagen tests.
- **Why it looks wrong:** a test that reads as covering a zone it never uses.
- **Exposed by:** the error hunt's fix stage, 2026-11-13, cluster B22.
- **Severity:** Low, tests only. Fix: set the zone the code reads, or drop the call.

## PROM19. NAVTEX *Generate* keeps a transmitter tick that areas never saved gave

- **Location:** `org/niord/core/promulgation/NavtexPromulgationService.java` `generateMessagePromulgation` and
  `transmittersOfTheStoredAreas`.
- **Description:** since 2026-11-13, *Generate* drops a ticked transmitter that the stored message's areas gave when
  the editor has changed the areas, and keeps every other tick as one set by hand. A tick that came from areas the
  message never had in the database (a new message, or areas changed twice between two saves, with *Generate* in
  between) cannot be told from a hand tick, so it stays after the areas change again.
- **Why it looks wrong:** the NAVTEX text then goes to a transmitter that none of the message's areas covers.
- **Exposed by:** the integration review of the error hunt's fix stage, 2026-11-13 (finding R1-2, fixed for the
  stored areas only; read in the code).
- **Severity:** Low — the editor sees the ticks before saving. Fix: the editor sends which ticks were set by hand
  (a frontend change), and *Generate* keeps only those.

## BUILD2. `./verify-local`'s example-country row can read "Binary file … matches" in place of the harness's own lines

- **Location:** `verify-local` (the repository root), the example part: the three `grep -E '^example: …'` calls that
  fill `registry`, `upgrade` and `backend` from `.local-run/verify-local/example.log` before `EXAMPLE_DETAIL` is
  built.
- **Description:** the example log carries the generated country's backend output, and a byte in it that grep
  takes for binary makes Git Bash's GNU grep 3.0 print `Binary file …/example.log matches` instead of the matching
  line. On a Windows run of 2026-11-13 the part passed, and its summary row ended in that sentence: the lines on the
  generated backend, publishing and pinning, the fixture upgrade and the upgrade test were not in the row.
- **Why it looks wrong:** the summary is what a developer reads of a local run before pushing, and it then hides
  what the harness reported having run or skipped. The part's result is not affected (it is the harness's exit
  status).
- **Exposed by:** the error hunt's fix stage, its closing `./verify-local`, 2026-11-13 (read in the summary and the
  script).
- **Severity:** Low, tooling only. Fix: `grep -a` on the three calls, so the log is read as text whatever it holds.

## DB5. Description lists come back in no defined order

- **Location:** every `@OneToMany` `descs` list of `org.niord.core` (Area, Category, TemplateParam, DictionaryEntry,
  MailingList, MailingListTrigger, Attachment, Message, MessagePart, Reference, Publication, PublicationCategory,
  PublicationIssue, PublicationSeries, Source; for example `org/niord/core/source/Source.java` 53-54) has neither
  `@OrderBy` nor `@OrderColumn`.
- **Description:** the descriptions are read in whatever order PostgreSQL returns the rows. An update writes a new
  row version, which can take free space ahead of the other language's row, and the read order then flips.
  Reproduced: a source created `[en, da]` read back `[da, en]` after another source's descriptions were deleted, the
  table vacuumed, and only the Danish abbreviation changed through `PUT /rest/sources/source/{id}` (the plan is a
  sequential scan). Readers pick a description by language (`ILocalizable.getDescs` falls back to the default
  language first; the admin app takes `descs[0]` only when the chosen language is missing), so nothing shown
  changes. JSON exports and API answers of unchanged data can list them in another order.
- **Why it looks wrong:** two exports of the same data can differ, so a comparison of two exports (or of a test's
  read with what it sent) passes or fails by the table's free space. The old system had the same mapping; on MySQL it
  read the rows in insertion order in practice.
- **Exposed by:** the error hunt's wave 3b, 2026-11-14: the integration suite in the reverse class order with the
  endpoint sweep failed `ReferenceDataImportRoundTripITTest.sourcesImportedOverThemselvesUpdateThemAndAddTheNewOne`
  (line 308) on the order of one source's two descriptions. The sweep now compacts the tables it deletes from
  (`VACUUM FULL`), which makes the failure rarer but does not remove it. Confirmed by a second reader's IT,
  deterministically. The test now compares each row's descriptions by language (its `byLang`); what is left is
  the product's half.
- **Severity:** Low. Fix: `@OrderBy("lang")` on the description lists (mapping only, no Liquibase change), after
  checking that no reader takes `getDescs().getFirst()` unfiltered as the first language entered.

## MSG9. A message's categories come back in no defined order, so the editor's order is not kept

- **Location:** `org/niord/core/message/Message.java` `categories` (162-163), a `@ManyToMany` list with no
  `@OrderColumn`, unlike `areas` (148-150) and `charts` (165-167) beside it.
- **Description:** the `Message_Category` rows are read in the order the query plan gives. Reproduced through the
  product's own path: with other drafts' categories edited and the table vacuumed now and then, the 93rd save of a
  message with `[13, 14, 5]` read back as `[5, 13, 14]` through `editable-message`, while its rows lay physically in
  the order sent. The editor's Categories row lists the names in the order read. For a message with two or more
  template categories, `TemplateExecutionService.executeTemplate` (540-565, `checkCreateDetailParts`) runs the
  templates in that order and matches them by index to the DETAILS parts and the template parameters, so a re-run
  after a reload that flipped the order can swap the order of the DETAILS paragraphs. Each text stays correct, and
  no mail, PDF or public-site template prints the categories.
- **Why it looks wrong:** the editor lets one order a message's categories, and the order is lost; two reads of one
  message can differ.
- **Exposed by:** the error hunt's wave 3b, 2026-11-14: the integration suite in the reverse class order, after the
  endpoint sweep's lanes were merged, failed `MessageRoundTripITTest` (line 221), which picked categories by their
  position in the read; the test now picks them by id. Confirmed by a second reader's IT. The old system mapped the
  list the same way; on MySQL it kept the insertion order in practice.
- **Severity:** Low. Fix: `@OrderColumn` on `Message.categories`, with a Liquibase change that adds the order column
  to `Message_Category` and fills it for every existing row (as `Message_Area` and `Message_Chart` have; a row left
  without it reads as a null in the list); check the archive export and import and `MessageRoundTripITTest`.
  `@OrderBy("id")` would be mapping only and stable, but would not keep the editor's order.
- **What the fix needs beyond that** (found when the fix was taken up, 2026-11-14, and left open for it):
  - **Both fixtures are regenerated with it.** The integration fixture (`niord/src/test/resources/db/niord-base-data.sql`,
    the 30 `Message_Category` inserts) and the sample (`niord/sample/niord-sample.sql`) insert the join rows with the
    two columns only, and the sample's guard lists the schema's changesets (`SampleFolderTest`
    `theGuardsChangesetsAreTheChangelogs` is red for any new change set). So the change set comes with
    `niord/scripts/regen-sample.sh` (the docker-compose `niord-db` and the local realm) and
    `niord/scripts/regen-test-seed-postgres.sh` (a throwaway PostGIS container), as V011 did.
  - **An image older than the change set** (a rolled-back image) maps the categories as a bag: a save deletes the
    message's join rows and inserts them again with the two columns only. With the column `NOT NULL` and no default,
    every save of a message that has a category then fails (500) while that image runs. This does not hold for
    areas and charts: their order columns are in the baseline, so every image writes them. Either the change set
    also carries a `BEFORE INSERT` trigger that numbers a row inserted without an order after the message's last one
    (the old image inserts in its list order, so the new image reads that order back; checked on a scratch copy of
    the table), or a rollback across the change set drops the column first.
  - **The fill**, in the order the rows lie today, per message, with no gap and no repeat:
    `UPDATE public."Message_Category" mc SET "categories_ORDER" = n.rn - 1 FROM (SELECT ctid, row_number() OVER
    (PARTITION BY "Message_id" ORDER BY ctid) AS rn FROM public."Message_Category") n WHERE mc.ctid = n.ctid`, then
    `SET NOT NULL` and the primary key `("Message_id", "categories_ORDER")` as `Message_Area` has (checked on a scratch
    copy with interleaved messages, a duplicate pair and a reused line pointer).
  - Already in order without a change: the archive export writes `MessageVo.categories` as a list and the import
    (`BatchMessageImportProcessor`) sets it in that order; a history snapshot is the JSON of the message at its save
    and keeps the order it was written with. `MessageRoundTripITTest` (186) then picks the categories by position
    again.

## KEYCLOAK2. A Keycloak user read that Keycloak refuses, or cannot answer, shows an empty list; a user write a 500

- **Location:** `org/niord/core/user/UserService.java`: the reads `searchKeycloakUsers`, `getKeycloakGroups` and
  `getKeycloakUserGroups`, which catch every failure of the Keycloak admin call and answer an empty list; the user
  writes `addKeycloakUser`, `updateKeycloakUser` and `deleteKeycloakUser`, whose failure reaches the dispatcher.
  Behind the `kc-*` handlers of `UserRestService`.
- **Description:** when Keycloak refuses (403) or fails (503; an outage is the realistic case) an administrator's
  call from Admin > Users & Contacts > Users, the reads answer 200 `[]`: the Users tab shows "No users match the
  search" instead of its error banner, and the editor shows no groups and no error. The user writes (`POST`, `PUT`
  and `DELETE /rest/users/kc-user…`) answer 500 with no detail, and the editor shows "User operation failed".
- **Why it looks wrong:** an outage looks like an empty directory. Both are the old system's behaviour (the same
  `UserService`), and neither claims a success.
- **Exposed by:** the error hunt's wave 3b endpoint sweep, 2026-11-14: the `admin` cells of the Keycloak handlers
  (the test realm's `testadmin` lacks `manage-users`, so Keycloak refuses; `sweep-system-0124`, `-0133`, `-0234`,
  `-0239`, `-0245`, `-0251`, `-0254`). Confirmed by a second reader's IT against an HTTP stub that answered 403, then
  503. The group join and leave, which answered 204 and toasted "User joined group" / "User left group", were
  fixed on 2026-11-14 (wave 3b fix lane C): they answer 403, 404 or 502 with a sentence, which the editor shows.
- **Severity:** Low. Fix: let the reads throw as the join and leave do (`UserService.groupChangeFailed`, the typed
  `KeycloakAdminException`), so the users facade shows its error banner; give the user writes the same 403 / 502
  answers. That changes `UserRestServiceITTest`'s pinned empty lists (`kcUsersWithBareTokenReturnsEmptyList`,
  `kcGroupsWithBareTokenReturnsEmptyList`) and the sweep's five KEYCLOAK2 cells.

# Potential errors — the error hunt's phase 2 (2026-11-15)

Phase 2 of the error hunt ran in two stages on 2026-11-15. The first read the code, the documents, the bootstrap
templates and two local data copies, and ran nothing: SETTINGS-8, SETTINGS-10, RENDER10, DICT7 and DICT8 below
are its entries. They were read in the code; SETTINGS-8 was also read in the local data copies. No test pins a
first-stage entry, except that two template tests pin the value RENDER10 names. The second stage drove the
administration app and the public site in a browser on a local rig (a local backend on a copy of a deployment's
data, since dropped): PUBCAT2 below is its entry. Its other entry, STALE1 (a save in a second tab of an admin or sysadmin
editor silently undoes the first tab's change) was accepted by the owner on 2026-11-15 as last write wins and is
recorded in `fe/docs/backlog.md`, "Deliberately not built". A second agent tried to refute each finding
before it was filed; every entry below was confirmed that way, and each says how it was found. The finders' and
the verifiers' notes are named in the entries by their ids (H1-1, PUBLICATIONS-2 and so on); they are in
`user-guides-notes/error-hunt/phase2/` beside the worktrees, outside the repository. An entry with a **Decision
first** line asked for the owner's word; he answered on 2026-11-15, and an entry he left says so in a line of its
own and stays open as the record. The others say what is recommended.
Java paths are under `be/niord/src/main/java/` unless they say otherwise; `admin/` stands for `fe/ui-apps/admin/src/app/`; every other
path is from the repository root.

## SETTINGS-8. A stored setting keeps an older declaration's web and editable flags, so a section stays hidden and a key cannot be edited

- **Location:** `org/niord/core/settings/Setting.java` 32-35 (the named queries by `s.web` and `s.editable`);
  `org/niord/core/settings/SettingsService.java` `addMissing` (233 on; 255-262 skip a present row), `getAllForWeb`
  (460-466) and `getAllEditable` (478-484), which take the rows' flags and leave out only a key no catalogue
  declares; `org/niord/web/SettingsRestService.java` 148-170 (`/settings/web` from the rows). `admin/features/admin/services/section-gates.service.ts` 51-52 and
  `admin/features/admin/section-descriptors.ts` 87-94. The catalogue:
  `be/niord/src/main/resources/defaults/base/settings-catalog.json`.
- **Description:** a setting row keeps the web, editable and type flags it was first written with. A new row takes
  the catalogue's flags; nothing ever changes an existing row's. Both `/rest/settings/web` and the Settings page
  read the rows, not the catalogue. In both local data copies `adminIntegrationPageEnabled`, `mode` and
  `wmsProtected` are stored with web and editable off, against the catalogue's on; the first is a key of this
  product, so an earlier build of it wrote that row. The Integration section then never shows in the rail (a deep
  link still opens it), and these keys are not on the Settings page. The settings import can change the value, not
  the flags.
- **Why it looks wrong:** the plan names the catalogue as the source of a key's flags
  (`docs/code-data-config-plan.md` 737), while its §5.3 (835-837) keeps the two endpoints on the database; a
  deployment cannot reach a key the catalogue declares editable. `fe/docs/backlog.md` ("The backend `mode`
  setting") names both flags for `mode` alone.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, read in the code and in two local data copies, read only
  (H1-1, H2-1). Confirmed by their second readers. No test pins it yet.
- **Severity:** Medium. Fix: let `getAllForWeb` and `getAllEditable` decide by the catalogue entry's `web` and
  `editable` (and `cached`) for a declared key, and by the row's flags only for an undeclared one. No data changes.
  Could break: `mode` reaches `/settings/web` for the first time, so the public site shows its TEST ribbon where a
  deployment's row says `test`; `wmsProtected` reaches `/settings/web` with its stored String type, which both
  sides read as before: the backend takes a text `false` safely (`WmsProxyServlet.isProtected`), and the admin
  app's web-settings overlay converts a text `true` or `false` to a boolean (`admin/core/config/web-settings.ts`,
  `coerce`). SETTINGS-10 belongs in the same change.
- **Decision first:** may declared settings take their flags from the catalogue, once each staging's stored `mode`
  value has been read? Recommended: yes, in one change with SETTINGS-10.
- **Left by the owner (2026-11-15):** no change to where the flags come from, and no read of the stagings' rows;
  the entry stays open as the record.

## SETTINGS-10. A setting marked not editable is still written by the settings API and the settings import

- **Location:** `org/niord/core/settings/SettingsService.java` `set` (709-740: catalogue, scope, row, type and
  required are checked, never `editable`) and `addMissing` (249-268, makes a row for every operator key, the
  not-editable ones too); `org/niord/core/settings/batch/BatchSettingsImportProcessor.java` 50-87;
  `org/niord/web/SettingsRestService.java` 56 (SYSADMIN) and 97-98 (the editable list, the flag's only reader).
- **Description:** `PUT /rest/settings/setting` and the settings import never check `editable`, and every operator
  key has a row, so the "no row" refusal does not stop them. A sysadmin can set a key that the catalogue and the
  security review call not editable, such as `authServerTrustAllCertificates`.
- **Why it looks wrong:** a declared flag that nothing enforces.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, read in the code (G4-5). Confirmed by a second reader. No
  test pins it yet.
- **Severity:** Low (SYSADMIN only; that key is read once at start). Fix: refuse a not-editable key in `set` (400)
  and skip it in the import with a log line, judged by the catalogue's flag (SETTINGS-8). Could break: a settings
  export and import round trip that carries such keys logs skips; tests that set `secomEnabled` or
  `mapTileCachePath` through `set`.
- **Decision first:** may the settings API and the settings import refuse a key the catalogue marks not editable,
  the import skipping it with a log line? Recommended: yes, in the same change as SETTINGS-8.
- **Left by the owner (2026-11-15):** left as is; the entry stays open as the record.

## RENDER10. The base product's NM report covers count the volume from 1884, one deployment's own base year

- **Location:** `be/niord/bootstrap/templates/messages/nm-report-pdf.ftl` 88 and `nm-accumulated-report-pdf.ftl`
  192 (`yearNumber - 1884`), and the same lines in the three release folders' `script-resources.json`; pinned by
  `WeeklyReportTemplateTest` (153) and `AccumulatedReportTemplateTest`; `fe/docs/publications/DATA-MODEL.md` 424
  records the computation.
- **Description:** the base templates print the volume as the year minus 1884 for every deployment. That base year
  belongs to one deployment's series; on another deployment's cover the number means nothing.
- **Why it looks wrong:** one deployment's numbering sits in the base product's templates.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, read in the templates (G2-2). Confirmed by a second reader.
- **Severity:** Low (the cover only). Fix: take the base year from a text that a deployment sets, with no volume
  printed when it is unset, or drop the volume from the base product's covers and leave it to a country's own
  templates; either ships as a release import. Could break: the two tests that pin `year - 1884`. RENDER7 is its
  sibling in the same templates.
- **Decision first:** a text that a deployment sets, or no volume in the base product? Recommended: the text, with
  no volume printed when it is unset.
- **Left by the owner (2026-11-15):** 1884 stays in the base templates; the entry stays open as the record.

## DICT7. The required-texts list names a template reader no shipped template uses

- **Location:** `org/niord/core/dictionary/RequiredTexts.java` 78-82, 108 and 111 (`FROM_TO_DATE_DIRECTIVE`);
  `org/niord/core/script/directive/FromToDateDirective.java` 98 (the only reader of
  `msg.time.until_further_notice`); `admin/features/admin/sections/sysadmin/dictionaries/dictionaries-page.ts` 55 and
  `fe/languages/en/i18n/admin/en.json` 606.
- **Description:** the Dictionaries page warns that without `msg.time.until_further_notice` "from–to dates in
  templates print nothing". No template in the bootstrap or its release folders makes the directive. An operator's
  own template could still use it, and the admin app has its own fallback for the key.
- **Why it looks wrong:** the warning names a reader that does not exist in what ships.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, read in the code and the templates (G2-4). Confirmed by a
  second reader.
- **Severity:** Low; recommended: leave, since the warning stays true for an operator's own template and warning
  too much harms no one. Fix, if wanted: drop `FROM_TO_DATE_DIRECTIVE` from the two keys and the admin text, or
  delete the unused directive with it and adjust `RequiredTextsTest`.

## DICT8. About 45 English backend bundle texts are read by nothing

- **Location:** `be/niord/src/main/resources/defaults/base/texts/en/*.properties`; for example `msg.show_positions`,
  `menu.atons`, `front_page.brand`, `pdf.nw`, `source.date_format`. Not among them, though they look unread:
  `footer.copyright`, `footer.link`, `footer.disclaimer` and `footer.cookies`, which
  `admin/core/main-shell/footer/footer.ts` 29-34 reads.
- **Description:** no template, backend class or frontend reads these texts. By the code-data rule they are
  harmless data.
- **Why it looks wrong:** dead texts in the base product's defaults.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, an inventory of the bundles against the readers (G2-7).
  Confirmed by a second reader, who corrected the count from 49 to at most 45.
- **Severity:** Low; recommended: leave. If wanted: drop them from the class-path defaults only, never from a
  deployment's rows, and keep the footer keys. Could break: nothing.

## PUBCAT2. Three imports still store a publication category or series id with `/` or `;`, and the record can then never be opened, edited or deleted

- **Location:** `org/niord/core/publication/PublicationCategoryService.java` `findOrCreatePublicationCategory`
  (124-134, creates a missing category under the id it is given), called by the publication import
  (`org/niord/core/publication/batch/BatchPublicationImportProcessor.java` 59) and the legacy-to-series import
  (`org/niord/core/publication/series/legacy/LegacyImportService.java` 2246); the publication series import
  (`org/niord/core/publication/series/batch/BatchPublicationSeriesImportProcessor.java` `processItem`, 87-148: a
  new series is made with no check of its id). The rule the other paths apply is
  `org/niord/core/publication/PathSegmentId.java`; `org/niord/core/conf/EncodedSlashConfig.java` holds the firewall.
- **Description:** the REST creates of a category, a series and a one-off, and the category import, refuse an id
  the request firewall cannot serve (one that holds `/`, `\`, `%`, `;` or a control character, or is `.` or `..`).
  These three imports do not: they store such an id, and every later GET, PUT and DELETE of the record is refused
  with 400 by the firewall before any handler runs. A category already stored under such an id still cannot be
  opened, saved or deleted in the admin app; the category import is the one way to unpublish or rename it.
- **Why it looks wrong:** an import file's typo leaves a record that no one can remove through the app.
- **Exposed by:** the error hunt's phase 2, 2026-11-15, seen in a browser and with REST calls on a local rig in two
  runs, and read in the code (PUBLICATIONS-2, S1-11); narrowed to the imports by the phase 2 fix stage
  (2026-11-16, read in the code). No test pins the import paths.
- **Severity:** Low. Fix: call `PathSegmentId.refusal` in `findOrCreatePublicationCategory` before it creates a
  category, and in the series import's new-series branch, and skip the row with the sentence, as the category
  import does. Could break: an import file that already holds such an id; read the stagings' and the country
  repositories' category and series ids first (read only). Repository file names, whose `%` and `;` the
  firewall refused the same way, were given their own allowance in `EncodedSlashConfig.MessageIdFirewall`; an id
  here is better refused where it is made.

# Potential errors — met by the error hunt's phase 2 fix stage (2026-11-16)

The fix stage of phase 2 met these while it fixed other entries, in its integration's check, or in the review of
its riskiest fixes; none was fixed. Each says how it was found. Java paths are under `be/niord/src/main/java/` unless
they say otherwise; every other path is from the repository root.

## CG10. A coordinate sent as JSON null is stored as 0

- **Location:** the coordinate arrays of the GeoJSON value objects (`org/niord/model/geojson/PointVo.java` 34,
  `LineStringVo.java` 34, `MultiPointVo.java` 34 and the others), read by the REST JSON mapper; the save paths
  that build an entity from them (`Feature.fromGeoJson`, `Area(vo)`, `Chart(vo)`).
- **Description:** a coordinate of `null` in a geometry the backend receives is read as 0 before any check runs:
  `POST /rest/messages/message` with the coordinates `[10.5, null]` answered 200 and stored `[10.5, 0.0]`. A
  latitude beyond 90 is refused on every save path since 2026-11-16, but one typed in the admin editor's
  edit-as-text view reaches the backend as `null` (the map's projection has no number for it) and is stored as
  latitude 0, without a word.
- **Why it looks wrong:** a value the client did not send is stored as a position.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, cluster P1-12: seen in its red run and again in
  its check's run on the fixed code.
- **Severity:** Low. Fix: refuse a null coordinate with 400, in the value objects' coordinate fields or in the
  REST mapper. Could break: a client that relies on `null` being read as 0.

## FIRING10. A firing-period save that names a period another tab deleted answers 500

- **Location:** `org/niord/core/schedule/FiringScheduleService.java` `updateFiringAreaPeriodsForDate` (316 on;
  341-345 update every persisted period of the body) and `updateFiringPeriod` (421-435: `getByPrimaryKey` answers
  null for a deleted row, and 432 calls `updateFiringPeriod` on it).
- **Description:** tab A deletes a firing period; tab B, which loaded the day before that, saves it with the
  period still in its body, under its id. The update finds no row and throws a `NullPointerException`, which
  answers 500.
- **Why it looks wrong:** a stale save is a conflict the user can act on (409, or 404 naming the period), not a
  server fault.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, cluster P1-15 (read in the code; no test).
- **Severity:** Low. Fix: answer 409 (or 404) when a persisted period of the body has no row, before anything is
  changed.

## FIRING11. The `firingExerciseScheduleDays` setting takes a value below 1

- **Location:** `org/niord/core/schedule/FiringExerciseService.java` 72-76 (the `@Setting`, an `Integer` read at
  start-up) and 269 (used when a schedule has no days of its own); its catalogue entry in
  `be/niord/src/main/resources/defaults/base/settings-catalog.json` is editable.
- **Description:** a schedule's own days below 1 are refused with 400 since 2026-11-16, but the setting that
  stands in for empty days has no lower bound. Set to 0 it leaves only today in the run's window; set below 0 it
  ends the window before today, so the run (after the next start-up) finds no firing period ahead for the areas
  of every schedule without days of its own.
- **Why it looks wrong:** the same value that is refused on a schedule is accepted for every schedule at once.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, the check of cluster P1-14 (read in the code;
  not run).
- **Severity:** Low — a sysadmin's setting. Fix: refuse a value below 1 for this key in the settings write and the
  settings import, or read a value below 1 as the default.

## PORT-16. On Windows, a repository file whose name holds `?` cannot be read

- **Location:** `org/niord/core/repo/RepositoryService.java` `mediaTypeOf` (191-192:
  `fileTypes.getContentType(Paths.get(fileName))`), called at 147 and 350.
- **Description:** on Windows `Paths.get` throws `InvalidPathException` for a name that holds `?`, `*`, `:`, `<`,
  `>`, `|` or `"`. It is an `IllegalArgumentException`, which `RestExceptionStatusAdvice` (63-64) answers with
  400, so `GET` (and `HEAD`) `/rest/repo/file/<path>` of such a file answers 400 on a Windows workstation. On
  Linux the file is served.
- **Why it looks wrong:** a content type is looked up by a path the file name never needed to be.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, cluster P2-21:
  `RepositoryRestServiceITTest#aListedFileWhoseNameHoldsAnApostropheAndAQuestionMark_streamsByItsListedPath`
  skips the `?` stream where the platform refuses the name.
- **Severity:** Low — Windows development machines only. Fix: take the extension from the name and look the type
  up by it, without building a `Path`; then drop the test's skip.

## SETTINGS-13. A rolled-back setting or dictionary write can leave its value in the cache

- **Location:** `org/niord/core/defaults/DefaultsLedger.java` `afterCommit` (225-236: registers `afterCommit`
  only); its callers `org/niord/core/settings/SettingsService.java` 286, 772 and 818 and
  `org/niord/core/dictionary/DictionaryService.java` 317 and 585; the settings read that fills the cache
  (`SettingsService` 530-540).
- **Description:** the cache is emptied of a changed key only after the write commits. A read in the writing
  transaction after the write, before a rollback, sees the uncommitted row and puts its value in the cache, and
  after the rollback nothing empties it. No caller is known to read a key again after writing it in a transaction
  that then rolls back.
- **Why it looks wrong:** a value that was never committed is served until the cache is emptied.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, cluster P2-3 (read in the code; not run).
- **Severity:** Low — latent. Fix: empty the key also after a rollback (`afterCompletion` with
  `STATUS_ROLLED_BACK`).

## SETTINGS-14. `/rest/settings/web` serves an emptied number setting as null, not as its default

- **Location:** `org/niord/web/SettingsRestService.java` `getWebSettings` (148-170: each row's stored value; only a
  `String` setting takes an override); the admin app's `mergeWebSettings`
  (`fe/ui-apps/admin/src/app/core/config/web-settings.ts` 256).
- **Description:** a web setting of a number type emptied on the Settings page (`mapDefaultLatitude`,
  `mapDefaultLongitude`, `mapDefaultZoomLevel`, `mapImageSize`) is stored with no value. The backend's own reads
  fall back to the default, but `/rest/settings/web` serves the stored null, and the admin app then keeps the
  value of its own configuration, which may differ from the catalogue's default.
- **Why it looks wrong:** the backend and the browser use two different values for one emptied setting.
- **Exposed by:** the error hunt's phase 2 fix stage, 2026-11-16, cluster P2-4 (read in the code; not run).
- **Severity:** Low. Fix: serve the default in `getWebSettings` for a row with no value, as the backend's read
  does.

## SERIES-RESET. A reset of the next message number to 0 is accepted where 0 is the only number used

- **Location:** `org/niord/core/message/MessageSeriesService.java` `setNextMessageNumber` (the exemption "Compute gives
  1") and `computeNextMessageNumber` (the highest number used, or 0, plus one).
- **Description:** since 2026-11-16 a reset below a number already used is refused with 400. The check cannot tell a
  series and year with no numbered message from one whose only numbered message has the number 0: Compute gives 1
  for both, and 0 and 1 stay possible where it gives 1. So where a sysadmin once set the next number to 0 and one
  warning was published as number 0, a reset to 0 is still accepted and the next warning takes 0 again.
- **Why it looks wrong:** two warnings with one number, the fault the refusal is there to stop.
- **Exposed by:** the check of the SERIES-RESET fix, 2026-11-16 (read in the code; not run).
- **Severity:** Low. It needs the number 0 to have been used, which no shipped short format expects. Fix: let
  Compute's query tell "no number" from 0 (no `coalesce`), and exempt only "no number".

## TESTQ8. The S-124 integration tests count every published warning of the seed's series, so their result depends on the class order

- **Location:** `be/niord/src/test/java/org/niord/secom/S124DatasetPipelineITTest.java` and
  `S124ReconcileFailureITTest.java` (their counts of the reconcile's `failed`).
- **Description:** the two classes count every published NW of the seed's own series. An integration test that
  runs before them and leaves a published NW without a geometry in that series makes them fail (`failed=2` where 0
  is expected, `failed=3` where 1 is expected). `DeliveryFaultInjectionITTest` did so until the fix stage's
  integration repaired it. Failsafe sets no run order here, so the order is the file system's, and CI on Linux may
  order the classes differently from a Windows machine.
- **Why it looks wrong:** a test passes or fails by what another class left behind.
- **Exposed by:** the integration of the error hunt's phase 2 fix stage, 2026-11-16 (five failures in its first
  whole run, reproduced by its check with the repair taken out).
- **Severity:** Low, tests only. Fix: let the two classes count only the messages they make.

## PUBSERIES3. A publication series' sharing list comes back in no defined order

- **Location:** `org/niord/core/publication/series/PublicationSeries.java` `availableDomains` (212-216), a
  `@ManyToMany` list on the join table `PublicationSeries_AvailableDomain` with neither `@OrderColumn` nor
  `@OrderBy`; the answer is built from it in `toVo` (891-896), which adds the domain ids to
  `SystemPublicationSeriesVo.availableDomainIds` in the order the list was loaded.
- **Description:** the join rows are read in the order the query plan gives, so `availableDomainIds` in
  `GET /rest/publication-series/editable-series/{seriesId}`, in the export and in every answer of a create or a
  save can list the same domains in another order than they were sent or last read. Two reads of one unchanged
  series, with no committed write between them, came back as `[niord-nm, niord-fa]` and then
  `[niord-fa, niord-nm]`. Nothing is decided by the order: the list is a set of domains (the visibility predicate
  in `SeriesVisibility` joins it, and `SeriesAvailabilityResolver.resolve` drops duplicates and the owner), and the
  administration app shows a tick per domain.
- **Why it looks wrong:** two exports of the same data can differ, and a client that compares a read with what it
  sent or read before, as a list, sees a change where there is none.
- **Exposed by:** an integration run on CI, 2026-11-17: `PublicationSeriesExportImportRoundTripITTest
  .aRowUploadedFromAnotherDeskIsSkippedAndTheStoredSeriesIsUntouched` and `PublicationSeriesRoundTripITTest
  .anActiveSeriesEditedIntoIncompletenessIsRefusedAndKeepsWhatItHad` each compared a whole series before and after
  a refused write and failed on the order of this list alone. Read in the code; not reproduced on a workstation.
  The tests now compare the list without regard to its order (`PublicationHttpFixture.sharingSorted`) and
  everything else as before; what is left is the product's half. The same kind as DB5 and MSG9.
- **Severity:** Low. Fix: `@OrderBy("domainId")` on `availableDomains` (mapping only, no Liquibase change; the
  order is then by domain id, not the order sent, which no reader needs), and the tests then compare the list as it
  is read.
