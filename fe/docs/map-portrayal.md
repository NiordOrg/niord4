# Map portrayal — IHO S-124

Navigational warnings (`NW`) and notices to mariners (`NM`) are drawn on every message map with
the symbology of the **IHO S-124 (Navigational Warnings) Edition 2.0.0 portrayal catalogue, Day
palette**. The rules are not decorative: the magenta, the symbol shapes, the dash ratio, the
no-fill surface rule and the de-duplication behaviour all come from the standard, and a mariner
moving between deployments — or between the administration application and the public site — must
read the same marks.

The portrayal lives in the map kit, which belongs to neither application, in two parts. **What** is
drawn is the definition `shared-map/portrayal/portrayal.json` and the six symbol SVGs beside it:
the colours, the symbol files and where each one's pivot sits, the pen and its dash, the sizes, and
which style a message gets. It is the one source of every portrayal value — the backend's map-image
renderer reads the same file — and each application's build copies the SVGs into its own
`assets/portrayal/`. **How** it is drawn is `shared-map/map-styles.ts`, which imports the definition
and turns it into OpenLayers styles; both applications draw with it. A portrayal change is an edit
to the definition and its SVGs, never to the code. A change under `shared-map/portrayal/` runs
Backend CI too, where `PortrayalDefinitionTest` and `MapImagePlannerTest` fail on a definition, a
symbol or a golden fixture the backend cannot use. The co-location stamping that feeds it is the
administration application's
`ui-apps/admin/src/app/features/messages/services/co-location.ts`; the public site does not stamp, so no
count badge appears there.

## The catalogue is not in this repository

The authoritative IHO material (S-100 Ed 5.2.1, S-97, S-98, the S-124 portrayal catalogue 2.0.0 and
the S-125 portrayal catalogue 1.0.0) is roughly 41 MB and is **gitignored** — `.gitignore` excludes
`S-124_S-125_PS/`, and no file under it is tracked on any branch. It must be obtained from the IHO
if you need to re-derive a symbol, add a palette or check a rule.

**This document is the in-repo record of the catalogue's rules.** Everything transcribed from the
catalogue is marked as such below; everything else is verifiable in the code.

When you do have the catalogue, the files that matter are:

| Path inside the catalogue                           | What it gives you                                  |
| --------------------------------------------------- | -------------------------------------------------- |
| `Rules/NavwarnPart.xsl`                             | the conditional rules (which instruction, when)    |
| `Symbols/NavigationalWarningFeaturePart.svg`        | the NW-in-circle point symbol                      |
| `Symbols/NavigationalWarningFeaturePartPoint.svg`   | the "NW" tag used along lines and centred on areas |
| `Symbols/ApproximatePosition.svg`                   | the approximate-position adornment                 |
| `LineStyles/NavigationalWarningFeaturePartLine.xml` | pen width and dash pattern                         |
| `ColorProfiles/colorProfile.xml`                    | the colour tokens for all three palettes           |
| `Symbols/daySvgStyle.css`                           | the Day RGB values (cross-check only)              |

Trust `colorProfile.xml` over the stylesheets: the shipped S-124 `nightSvgStyle.css` mislabels
`.sNWWHT`.

## What is drawn

One exported function produces every message-feature style:

```ts
styleForMessageFeature(feature: Feature, ctx: MessageFeatureStyleContext, resolution = 1): Style[]
```

Keeping that signature is what makes a portrayal change low-risk. Seven map surfaces consume it —
four in the administration application, three on the public site — across four layer components,
three in the administration application and one on the public site:

| Surface                          | Layer component                                                                          | Notes                                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Messages map view                | admin `features/messages/components/map-layers/ol-message-list-layer.ts`                 | full geometry; selection + hover halos                                                                       |
| Home map                         | the same layer, `showOutline` on                                                         | geometry collapsed to a single `Point` at its interior point (`GeometryConversionService.getGeometryCenter`) |
| Message details dialog map       | admin `features/messages/components/map-layers/ol-message-details-layer.ts`              | never selected, so no halos; also honours `showOutline`                                                      |
| Editor insert-locations map      | admin `features/editor/components/insert-locations-dialog/insert-locations-map-layer.ts` | draws the selection halo for picked locations                                                                |
| Public site's chart              | info `shared/components/message-features-layer/message-features-layer.ts`                | warnings and notices on a layer each                                                                         |
| Public site's details-dialog map | the same layer, `interactive` off                                                        | one message; the numbered coordinate pins come from the labels layer beside it                               |
| Public site's teaser             | the same layer, `interactive` off                                                        | one picture with a link on it — nothing is selected, hovered or clicked                                      |

No public-site surface selects or hovers a feature, so each passes `isSelected: false` and no
`isHovered`: the site answers a pointer with a tooltip, not with a halo. None of them stamps
co-location either, so no count badge appears there.

`getGeometryCenter` is not a centroid: it returns a polygon's `getInteriorPoint()`, a line's
midpoint, and the point itself for points — so the collapsed mark always lands on the geometry.

The numbered coordinate pin and the name beside it are in the same module —
`coordinateIndexStyle()` and `coordinateNameStyle()`, drawn in the `--niord-pin-*` tokens. They are
not S-124: there is no producer-authored text in this data, and the numbering is what lets a reader
follow the positions written in a message against the chart. Both applications' labels layers draw
them (admin `ol-message-labels-layer.ts`, info `message-labels-layer.ts`); the public site also
names a whole geometry at its middle, with the same label style at a smaller offset.

The geometry editor is deliberately **not** part of this: it keeps its own styling in
`ui-apps/admin/src/app/shared/components/geometry-editor/util/editor-map-styles.ts`.

### Rules by geometry primitive

The style is chosen in two steps. First the definition's `select` rows name the style: a row
matches when every key it names equals the feature's property of that name, the first match wins,
and the last row names no key, so a feature nothing else matches ends there. A row may name
`mainType` and `messageType`. Today `NM` selects the NM style and anything else, including a missing
value, the NW one. `messageType` is the message's own type (`TEMPORARY_NOTICE`, `LOCAL_WARNING`, …),
a feature property the kit reads as a plain string; no layer sets it yet and no row names it, so it
changes nothing until both happen. Then the GeoJSON geometry type decides what that style draws:

| Geometry                         | Drawn                                                                                                                            | Standard rule                                             |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `Point` / `MultiPoint`           | the NW-in-circle symbol, 32 px, anchored at its pivot (the centre of its box)                                                    | point → `NavigationalWarningFeaturePart`                  |
| `LineString` / `MultiLineString` | dashed magenta stroke **plus** upright "NW" tags repeated along the line                                                         | curve → dashed pen + periodic tag symbol                  |
| `Polygon` / `MultiPolygon`       | dashed magenta outline with **no area fill**, plus exactly one tag at the interior point                                         | surface → dashed boundary + one centred tag, never filled |
| `GeometryCollection`             | dashed magenta stroke on its lines and outlines, one tag at the centre of its extent, and the point symbol at each of its points | each point keeps the point rule; the rest as a surface    |
| anything else                    | treated as a surface (dashed outline + centred tag)                                                                              | the catalogue's `QUESMRK1` "?" fallback is not reproduced |

Deriving the style from the geometry type is safe because the S-124 producer does the same: its GML
template maps `Point` → `S100:Point`, `LineString` → `Curve` and `Polygon` → `Surface`, so the three
branches here and the primitives in the producer's output stay aligned without the frontend
depending on anything the backend sends.

An S-124 surface is **never** filled. The catalogue ships an area colour-fill instruction
(`NWMGD` at 0.80 transparency) but it is commented out — filling a warning polygon is
non-conformant. In the code this is expressed by simply omitting the `fill` key from the OpenLayers
`Style`; do not "fix" a missing fill.

**No layer adds a mark of its own to a line or a surface — no centre pin.** The portrayal already
marks both at every zoom: a surface with its one tag at the interior point, a line with its tags
along it, at least one at its middle. So a message stays visible however far out the reader is,
and a point symbol at the middle of a line or a surface would only print a second mark over the
tag — S-124 draws the point symbol for a point and nothing else.

### Sizes, pen and anchors

The maps draw with the definition's `sizes.screen` set, in CSS pixels. The `sizes.print` set has the
same keys in image pixels and is for the backend's map images; the maps never read it (see "The
print size set" below).

| In the definition                                 | Value                           | Meaning                                                                                            |
| ------------------------------------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------- |
| `sizes.screen.line.dash`                          | `[13.6, 6.8]`                   | dash : gap = 2 : 1, the catalogue's 3.6 mm : 1.8 mm ratio at about 96 dpi                          |
| `sizes.screen.line.width`                         | `2`                             | px pen, approximating the catalogue's 0.64 mm                                                      |
| `sizes.screen.symbols` → `nw-point`, `nm-point`   | `[32, 32]`                      | on-screen size of the point symbol — the catalogue's 13.96 mm at 2.292 px/mm                       |
| `sizes.screen.symbols` → `nw-approx`, `nm-approx` | `[43, 43]`                      | on-screen size of the approximate-position adornment — its 18.7 mm at the same 2.292 px/mm         |
| `sizes.screen.symbols` → `nw-tag`, `nm-tag`       | `[44, 27]`                      | on-screen size of the tag symbol — the catalogue's 15.81 : 9.71 aspect to the nearest whole pixel  |
| `sizes.screen.tagSpacing`                         | `140`                           | multiplied by the view resolution, so on-screen tag spacing is stable at any zoom                  |
| `sizes.screen.buffer`                             | width `1`, dash `[4, 4]`        | the affected-radius / buffer overlay's pen                                                         |
| `lineStyle`                                       | cap `round`, join `round`, `10` | cap, join and miter limit of every pen — OpenLayers' own defaults, which the maps always drew with |
| `symbols` → `anchor`                              | e.g. `[0.499284, 0.498567]`     | where the symbol's pivot sits inside its viewBox, as fractions of the box — see below              |

The halos and the count badge are not the portrayal, so they are not in the definition. They are
code in `map-styles.ts`, derived from the selected style's screen point size:

| In `map-styles.ts`                                     | Value                         | Meaning                                                                                                                                                                               |
| ------------------------------------------------------ | ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| half the point symbol                                  | `32 / 2` → `16`               | the base every ring and offset below is derived from                                                                                                                                  |
| `SELECTED_HALO_STANDOFF_PX` / `SELECTED_HALO_WIDTH_PX` | `1` → radius `17` / width `2` | selection ring, standing just outside the symbol (niord, not S-124)                                                                                                                   |
| `HOVERED_HALO_STANDOFF_PX` / `HOVERED_HALO_WIDTH_PX`   | `5` → radius `21` / width `4` | hover ring, a further four pixels out (niord, not S-124)                                                                                                                              |
| `COUNT_BADGE_INSET_PX`                                 | `2` → offset `14`             | co-location badge displacement on both axes: its centre lands two pixels inside the ring's edge, so it reads as a badge on the symbol rather than a mark beside it (niord, not S-124) |

The point symbol and the approximate-position adornment share **one** millimetre-to-pixel scale,
2.292 px/mm, because they are drawn at the same position and have to stay in register: each is its
own catalogue extent times that single factor, so the adornment keeps the proportion to the symbol
that the portrayal gives it. Changing either size means changing the scale and recomputing both.

The tag leaves that scale on purpose. It is the one symbol whose lettering has to be **read** rather
than recognised, and 15.81 × 9.71 mm at 2.292 px/mm is 36 × 22 px, which leaves its letters
unresolvable at device pixel ratio 1 — so it is sized on its own at 44 × 27, the catalogue's aspect
to the nearest whole pixel, which leaves the box shape unchanged. It can leave the shared scale
because it is never drawn beside the point symbol: it is drawn along lines and at the interior point
of a surface, and the only symbol it can share a position with is the adornment on an approximate
line or surface, where nothing has to line up. The adornment on a **point** does have to line up,
which is why it stays on the scale.

The halo and badge geometry is derived from the selected style's screen point size rather than
written as literals: a literal stops matching the moment the symbol size moves, and the selection
halo then lies across the symbol instead of clearing it. Their whole-pixel radii and even stroke
widths are load-bearing too, and so is an even point size — see "Sharpness — device pixels, not the
SVG file" below.

Transcribed from the catalogue for reference: the point symbol is a circle of **r = 5 mm** with a
**0.96 mm** stroke and no fill inside a 13.96 mm symbol whose pivot is user coordinate `(0,0)`; the
tag box is **15.81 × 9.71 mm** and the approximate-position adornment **18.7 mm** square, each with
its pivot at `(0,0)` as well, though only the point symbol's box is centred on that pivot; the line
pen is **0.64 mm** with a **3.6 mm** dash within a 40.18 mm interval.

### The print size set

The backend draws a message's map image with the same definition and the same rules (see
"Server-made map images" and "Golden fixtures" below), with one deviation, in size only:
`sizes.print`. Its lengths are pixels of an image `referenceImageSize` (256) pixels wide; an image
of another `mapImageSize` scales every length by `mapImageSize / 256`. One image is shown at three
scales — a PDF prints it 120 CSS px = 31.75 mm wide, so one image pixel is **0.124 mm** on paper;
the administration app's Details view shows it at 220 × 170 CSS px, cropped to cover; a tweet
carries it at 1× — and the set is tuned for paper, the smallest of them. To tune it,
`./mvnw -pl niord -Dtest=MapImagePreviewTest -Dportrayal.preview=true test` (in `be/`) paints
every golden fixture with the print set onto a plain background into
`be/niord/target/portrayal-preview/`, with the time each step takes.

| In the definition                                | Value                     | On paper, at 0.124 mm per image pixel                                                                               |
| ------------------------------------------------ | ------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `sizes.print.symbols` → `nw-point`, `nm-point`   | `[34, 34]`                | the ring 3.3 mm across (26.7 px) and its letters 0.94 mm high — the catalogue's 13.96 mm symbol at 2.436 px/mm      |
| `sizes.print.symbols` → `nw-approx`, `nm-approx` | `[46, 46]`                | 5.7 mm square: the adornment's 18.7 mm at the point symbol's 2.436 px/mm (45.5, to the nearest whole pixel)         |
| `sizes.print.symbols` → `nw-tag`, `nm-tag`       | `[53, 33]`                | 6.6 × 4.1 mm, its letters 1.16 mm high (9.4 px) — sized on its own for legible lettering, at the catalogue's aspect |
| `sizes.print.line`                               | width `2`, dash `[12, 6]` | a 0.25 mm pen (the catalogue's 0.64 mm at 2.436 px/mm is 1.6 px), dashes 1.5 mm long with 0.75 mm gaps              |
| `sizes.print.tagSpacing`                         | `160`                     | 19.8 mm between the tags along a line, three tag widths; a line across the image carries one or two                 |
| `sizes.print.buffer`                             | width `1`, dash `[4, 4]`  | a 0.12 mm pen with 0.5 mm dashes around the translucent buffer fill                                                 |

The same rules as on screen hold here: the point symbol and the adornment share one scale, so they
stay in register; the tag leaves it for legibility; every size is its symbol's viewBox aspect in
whole pixels. A change to the print set gives the map images a new version, so each is made again
the next time it is asked for.

### Symbol assets

Three SVGs per main type, each named in the definition's `symbols` by its file name, so `NM` can
diverge without touching the engine:

| Asset                             | Used for                                 |
| --------------------------------- | ---------------------------------------- |
| `nw-point.svg` / `nm-point.svg`   | the point symbol                         |
| `nw-tag.svg` / `nm-tag.svg`       | along-line tags and the centred area tag |
| `nw-approx.svg` / `nm-approx.svg` | the approximate-position adornment       |

`nw-point.svg` reproduces the catalogue's circle geometry exactly — `viewBox="-6.97 -6.96 13.96
13.96"`, a circle at the origin with `r="5"` and `stroke-width="0.96"`, no fill. The tag SVGs carry
the catalogue's rounded-tag box path verbatim. The `nm-*` files are the `nw-*` files with the glyph
changed to `NM`.

**Every glyph is an outline `<path>`, never a `<text>` element.** The catalogue draws its lettering
as outline paths too, and a path draws the same letters in every renderer, a browser or a server,
whatever fonts are installed where it runs; a `<text>` element takes whichever face the machine
resolves. The letters are the outlines of **Liberation Sans Bold 2.1.5** (SIL Open Font License,
metric-compatible with Arial), so they are not copies of the catalogue's own paths — expect that
difference if you diff the two. Each was extracted at the size and position of the text it
replaced: font size 4.5 centred on `x = 0` with its baseline at `y = 1.58` in the point symbols, font
size 4 centred on `x = -0.2` with its baseline at `y = 1.45` in the tags. A new or changed glyph is
made the same way: load the TTF with Java's `Font.createFont`, take the `GlyphVector` outline at the
font's own units-per-em (so no hinting reaches it), scale it to the font size, centre it on its
advance width as `text-anchor="middle"` would, and write it in the file's user units to three
decimals, filled with the glyph colour.

**The `width`/`height` attributes carry the viewBox's aspect, in whole numbers.** Each `Icon` is
given an explicit `width`/`height` in CSS pixels, and OpenLayers turns that into a scale once the SVG
reports its own natural size, so a symbol lands at the size above whatever the file declares — but
the declared size still shapes the drawing in two ways. OpenLayers scales each axis on its own, so a
file whose `width` : `height` differs from its viewBox is drawn distorted: the browser fits the
viewBox inside the declared box with a margin, and the margin is stretched along with it (a tag
declared 30 × 18 draws its box about a pixel narrower than its 44 px). And a browser reports the
natural size in whole pixels while laying the file out at the fractional size, so a fractional
`width` or `height` shrinks or stretches the symbol by a fraction of a pixel. Each file therefore
declares its viewBox times ten, rounded to whole numbers: 140 × 140 for the point symbols, 158 × 97
for the tags, 187 × 187 for the adornments. Authoring a file larger buys no sharpness — see
"Sharpness — device pixels, not the SVG file" below.

**The glyph and stroke colour is baked into each SVG** (`#C045D1`). Recolouring the symbols means
editing the SVGs as well as the definition's `colours`: the definition's colour draws the pens, the
count badge's disc and the overlay, and each SVG's own colour draws its symbol.

### Colours

The portrayal's colours are the definition's `colours`, by name: a style names its pen colour
(`stroke`), and `buffer` names the affected-area overlay's `fill` and `stroke`. They are not tokens —
one file that every drawing reads, the backend's images included, is what keeps the marks the same
everywhere, and a canvas style needs a literal string anyway.

| Name in `colours` | Value                      | Used for                                                         |
| ----------------- | -------------------------- | ---------------------------------------------------------------- |
| `NWMGD`           | `#C045D1`                  | the pen of both styles: line, outline and the count badge's disc |
| `bufferFill`      | `rgba(192, 69, 209, 0.15)` | affected-radius / buffer overlay fill                            |
| `bufferStroke`    | `rgba(192, 69, 209, 0.6)`  | affected-radius / buffer overlay stroke                          |

What is not the portrayal stays on tokens. Canvas cannot resolve `var(...)`, so each is read once
through `readCssVar(token, fallback)` (`shared-map/css-tokens.ts`) into a memoised bundle. The read
happens lazily on first style call, never at module evaluation, so the stylesheet is guaranteed to
have applied.

| Token                       | Value                     | Used for                                     |
| --------------------------- | ------------------------- | -------------------------------------------- |
| `--niord-msg-count-fill`    | `#ffffff`                 | the co-location count's numeral, every style |
| `--niord-msg-count-stroke`  | `#ffffff`                 | outline around the count badge, every style  |
| `--niord-msg-selected-halo` | `rgba(255, 165, 0, 0.95)` | selection halo (orange)                      |
| `--niord-msg-hover-halo`    | `rgba(0, 120, 215, 0.95)` | hover halo (blue)                            |

The geometry editor's buffer overlay reads `--niord-msg-buffer-fill` and `--niord-msg-buffer-stroke`.
They carry the same values as the definition's buffer colours, but they are the editor's own
styling, outside the portrayal, like the rest of the editor's marks.

`#C045D1` is the S-52-derived colour token `CHMGD` / `NWMGD` in the Day palette. `rgba(192,69,209,…)`
is the same colour with transparency.

**The definition is country-locked.** S-124 is an international standard, so a deployment must not
rebrand the portrayal magenta. Of the S-124 portrayal a country build may change **nothing**: no
country folder, brand token file or build configuration swaps `portrayal.json` or its SVGs, and the
halo and badge tokens sit in `tokens.base.css`, which no country overlay restates. The brandable map
colours are the area and chart overlay tokens (`--niord-area-*`, `--niord-chart-*`), which every
brand token file restates and a country build may re-colour. See
[styling-reference.md](styling-reference.md) for the full token catalogue and
[country-deployments.md](country-deployments.md) for what a country build may swap.

**Only sizes may deviate from the catalogue.** S-124 is an international standard and the portrayal
is followed as it is published — that is the product owner's ruling, and it is narrow on purpose:
sizes are the single permitted deviation, because a browser cannot be handed a calibrated display.
Nothing else moves. No fill behind a symbol, no colour other than the palette's, no change to a
geometry, a stroke or a dash, no extra mark added to a symbol. Anything that reads as an improvement
to the marks themselves is a non-conformance, not an improvement. The selection and hover halos and
the co-location count badge are outside S-124 altogether — niord enhancements the standard has no
opinion on — and those may change freely.

The map's other tunable settings — default centre and zoom, the WMS and OpenSeaMap layers, and the
`map.highlightEnabled` halo switch below — are environment config with a runtime web-settings
overlay, not a country build; see [platform.md](platform.md). The base map's tile addresses are the
backend web settings `osmUrl` and `openSeaMapUrl` (the public site reads only `osmUrl`, and only an
`https:` one), which reach the kit's tile layers through its `MAP_TILE_SOURCES` token; see
[architecture.md](architecture.md) → _The map layer_.

Transcribed from `colorProfile.xml`, for anyone adding a palette later (these values exist nowhere
else in the repository):

| Token             | Day       | Dusk                                  | Night                 |
| ----------------- | --------- | ------------------------------------- | --------------------- |
| `CHMGD` / `NWMGD` | `#C045D1` | `#826CA1` (CHMGD) / `#774082` (NWMGD) | `#411247` / `#3A1342` |
| `NWWHT`           | `#FFFFFF` | `#727272`                             | `#4D4C4D`             |
| `CHRED`           | `#EA5471` | `#9B3549`                             | `#390E16`             |
| `CHBLK`           | `#000000` | `#6B7F89`                             | `#252D31`             |
| `CHMGF`           | `#CBA9F9` | `#772782`                             | `#411247`             |

### De-duplication of co-located warnings

S-124 requires that when several warning parts share a point, **only the first draws the symbol** and
the number of co-located warnings is shown beside it.

`stampCoLocation` (`ui-apps/admin/src/app/features/messages/services/co-location.ts`) runs after every feature
rebuild on the two messages layers — the list layer and the details layer; the editor's
insert-locations picker never calls it, so **no count badge exists on that surface**. It groups
`Point` features by their metre-rounded coordinate, and for any group of two or more stamps
`coLocationCount` on every member and `coLocationPrimary` on the first. The style function draws the
badge only on the primary feature.

Implementation difference: the catalogue draws the count as plain magenta text; here it is a white
numeral on a filled magenta disc with a white outline, offset up and to the right of the symbol — the
same information, more readable over chart imagery.

### Approximate position — implemented, but it can never draw today

S-124 `restriction = 1` means "approximate position" and adds the `ApproximatePosition` adornment
next to the symbol. The hook is complete: the assets exist, and a feature whose `approximate`
property is `true` gets the adornment (at the point for point geometry, at the interior point
otherwise).

**Nothing sets that flag.** There is no positional-quality field on the message model, on the GeoJSON
feature properties, or in the backend's S-124 output, so the adornment never renders. Lighting it up
requires a backend change first — a per-part positional-quality field carried through to the GeoJSON
feature properties — and only then a frontend change to read it.

> **False friend.** Message GeoJSON features do carry a `restriction` property with the value
> `'affected'` (see `message-geometry.service.ts`). That is the affected-area / vessel-restriction
> enum and has nothing to do with S-124 `restriction = 1`. Do not wire one to the other.

### Halos, the buffer overlay and the count badge — deliberate additions

Three things on the map are not S-124 concepts and are kept on purpose:

- **Affected-radius / buffer geometry.** A feature carrying `parentFeatureIds` short-circuits to a
  single translucent dashed style and gets no symbol and no halo. S-124 gives `NavwarnAreaAffected`
  no portrayal at all (it is meant to be highlighted only when the user interrogates it); niord keeps
  drawing it, recoloured to the standard magenta. Because the maps and the backend's images both
  draw it, it is in the definition — the `buffer` colours and the `buffer` pen of each size set.
- **Selection (orange) and hover (blue) halos.** Stacked _underneath_ the base styles so the symbol
  stays on top, hover outermost so both states read at once. They are suppressed when
  `ctx.highlightEnabled` is `false`, which comes from `AppConfig.map.highlightEnabled` (default
  `true`; set it `false` in a deployment that needs strictly-standard output). The flag is read by
  the messages list layer; the details map never selects anything, and the editor's location picker
  draws its selection halo regardless because picking is the point of that dialog. Only the maps
  draw halos, so they are code and tokens in the kit, not the definition.
- **The co-location count badge.** Its disc takes the selected style's pen colour from the
  definition; its numeral and outline are the `--niord-msg-count-*` tokens, and its offset derives
  from the point symbol's size (see _Sizes, pen and anchors_ above). Only the administration app's
  maps draw it, so it too is code and tokens rather than the definition.

### Two display rules that are not about symbols

- The portrayal is an **always-on, scale-independent overlay**: warnings draw at every zoom level,
  with no scale-based suppression of the symbols. Nothing in the style code is resolution-gated
  except tag spacing, which is resolution-multiplied precisely so the on-screen result does _not_
  change with zoom.
- **Cancelled warnings must not draw on the map**, while staying discoverable in a list. That is a
  filtering rule, not a portrayal one: the public site's chart leaves cancelled messages out of a
  list it draws (`MessageMapComponent`), and the map of one message draws it whatever its status.

## OpenLayers techniques you need to know before editing

- **No portrayal literal in the style module.** Every portrayal value — a colour, a symbol file, an
  anchor, a size, a pen, a dash — comes from the definition, which `map-styles.ts` imports (so it is
  bundled with the module and never fetched). A niord-only colour — the halos, the count badge, the
  pins — comes from a token via `readCssVar` with a literal fallback, because a canvas cannot
  resolve `var(...)`. Both are resolved into a bundle built once per style and cached.
- **There is no along-line symbol repeater in OpenLayers**, and no `ol-ext` in this project. The
  repeated tags are a `Style` whose `geometry` function returns a `MultiPoint` sampled with
  `LineString.getCoordinateAt(d / total)`; the spacing is multiplied by the view `resolution`, which
  is why the style callback takes `(feature, resolution)`. Each line always gets at least one tag (its
  midpoint).
- **The centred area tag** is a second `Style` whose geometry function returns
  `Polygon.getInteriorPoint()` / `MultiPolygon.getInteriorPoints()` — the interior point, not the
  extent centre, so the tag lands inside concave shapes.
- **Pivot is not `[0.5, 0.5]`.** Every catalogue symbol draws around user coordinate `(0, 0)`, and
  that point — not the middle of the drawing — is what the position means. So each symbol's `anchor`
  in the definition is the fraction of its own `viewBox` at which that pivot falls, to six decimals:
  `6.97/13.96, 6.96/13.96` for the point symbol, `8.11/15.81, 4.8/9.71` for the tag,
  `16.7/18.7, 16.7/18.7` for the adornment. Only the point symbol's box is centred on its pivot.
  The adornment's box (`-16.7 -16.7 18.7 18.7`) lies almost entirely up and to the left of it, so a
  centred anchor would hang it most of a symbol away from the position it marks.
- **Tags are upright**, matching the standard. Rotating them to the line bearing would be a change,
  not a fix.
- **An unfilled mark is a thin target.** OpenLayers hit-tests a feature by the pixels it paints: a
  surface paints only its dashed outline and its tag, a point symbol only its ring and letters. So
  every message layer resolves a pointer through `featuresAtPixel` (`shared-map/hit-detection.ts`):
  the marks painted within `HIT_TOLERANCE_PX` (4 px; 10 px for a finger) first, then every surface
  that contains the position, smallest first, so a click inside nested areas names the innermost.
  A pointer inside an area means that area — that is what the containment test is for, and it is
  why the fix for a missed click is never a fill.
- A new symbol file under `shared-map/portrayal/` is not served by a running dev server until it is
  restarted. That is a dev-server limitation; a production build copies it correctly.

## Sharpness — device pixels, not the SVG file

A canvas re-rasterises an SVG at the size it is drawn at, not at the size the file was authored at.
A symbol's sharpness is therefore set entirely by the **device** pixels the map gives it — the CSS
sizes above times the map's pixel ratio — and no amount of resolution in the SVG can add to that.

That makes the pixel ratio part of the portrayal. OpenLayers reads `window.devicePixelRatio` once,
when its module graph is evaluated, and every map built afterwards inherits that one number; a map
already running when the ratio changes keeps sizing its canvas by the old one, and the compositor
stretches the whole map — tiles, strokes and symbols go soft together, at any map zoom. `OlMap`
(`shared-map/ol-map.ts`) hands the map the current ratio again on two triggers,
because neither covers the other: a browser zoom also resizes the CSS viewport, which the map's
`ResizeObserver` sees, while a move to a screen with a different scale factor changes only the ratio
and is caught by a media query pinned to the current value, re-armed at each new one.

The halos need the same care a level down. OpenLayers rasterises a `Circle` style into a sprite of
`ceil(2 × radius + strokeWidth)` pixels, draws the circle at the sprite's centre, then snaps the
sprite's top-left corner to a whole device pixel. An odd sprite puts the halo's centre on a pixel
**centre**, while the point symbol's pivot anchor (15.98 px into its 32 px box) puts the ring's
centre on a pixel **boundary** — half a pixel apart, and visible as soon as the two rings come close.
Whole-pixel radii with an even stroke width keep the sprite even and land both centres on the same
sub-pixel phase, which is why the halo radii and stroke widths are derived from the symbol size
rather than chosen — and why the definition's screen point size has to stay an even whole number,
which the kit's `portrayal.spec.ts` holds it to.

## Server-made map images

The backend draws the map image of a message itself, `GET /rest/message-map-image/{uid}.png`: the
thumbnail a PDF prints, a tweet attaches on publish and the administration application's Details
and Grid views show. The rules below are settled; change one only with the product owner.

- **One definition.** Both applications and the backend's renderer read `shared-map/portrayal/` —
  Maven copies it into the backend's base library, where `PortrayalDefinition` reads it — so a
  portrayal update changes that folder and nothing else, and an image differs from the maps in size
  alone ("The print size set" above). The golden fixtures (below) pin the renderer's placement of
  every mark to the web maps'.
- **OpenStreetMap tiles only.** The background is the base map of the `osmUrl` setting —
  `tile.openstreetmap.org` unless a deployment names another host — and nothing else. The renderer
  has no code path to OpenSeaMap or to the WMS, whatever the settings say, so an image never shows
  the sea-mark overlay or the protected sea chart, even where a deployment's maps offer them.
- **Made on request, never ahead.** An image is drawn the first time it is asked for. Nothing
  pre-builds images or prefetches tiles: the OpenStreetMap tile policy forbids bulk downloading.
  While the backend starts, it draws one made-up image on blank tiles in memory and runs one tile
  exchange against a socket of its own on the loopback interface, so that the first real image does
  not load that code while its request waits; neither asks a tile host for anything or writes a file.
- **The tile policy's duties, and how `TileFetcher` meets them.** Every request carries a unique
  User-Agent, `Niord/<version> (+<contact>)`, whose contact is the `mapTileContact` setting (the
  `baseUri` when that is empty; the backend warns at start-up when that names the machine itself).
  A tile is cached on disk for at least seven days, longer when the response's cache headers allow,
  and then revalidated; no request asks the host to bypass its cache. The cache is
  `${niord.home}/tile-cache` (the `mapTileCachePath` setting, which a deployment sets, not the
  Settings page), outside the repository folder, and nothing serves it, so a tile reaches a reader
  only inside an image; a nightly sweep keeps it under 200 MB. An image fetches exactly the tiles it
  covers, at its own zoom level. At most 64 requests go to one host at a time — enough for a Details
  or Grid page of 50 images of places no image was drawn of lately, up to four tiles each, to be
  drawn in two thirds of the time the previous renderer took; with 8 it took twice that time ([the
  map-image benchmark](../../be/docs/map-image-benchmark.md)) — images drawn at the same time
  share the fetch of a tile, a tile goes to the disk cache after the image has it, and a host that
  fails three times in a row gets no request for two minutes (a circuit breaker). An image whose
  tiles cannot be had within 8 s is not written, and the endpoint answers with the placeholder image.
- **The placeholder is the endpoint's own answer.** A message without a geometry, an image that
  cannot be drawn, a failure and a portrayal definition that cannot be read all get the placeholder
  from the endpoint itself — `200`, `image/png`, the header `X-Niord-Map-Image: placeholder` and the
  current image's 10-minute expiry — not a redirect to `/img/map_image_placeholder.png`. A redirect
  names that file on the request's host, which has it only when the backend serves the page, so the
  image would not load behind the dev server or any front that forwards `/rest` alone. The file
  stays served for any page that links it.
- **The credit is in the PDF footer.** A PDF that contains a map image, generated or custom, carries
  `© OpenStreetMap contributors` on every page, in the page-margin box beside the footer
  (bottom-right, or bottom-left when the template's own style fills that one), at the top of the
  bottom margin. The PDF renderer injects it, not a template, so the templates a deployment keeps in
  its database and the country templates get it too ([backend-api-notes.md](backend-api-notes.md)).
  It is document-wide once any page shows a map image, because a margin box cannot name single
  pages. A tweet and the administration app's Details and Grid views show the image without a
  credit, as they always have: an accepted deviation.
- **A portrayal change gives every image a new file name.** An image is stored in the message's
  repository folder as `map_<size>_<version>.png`, and the version is derived, never written: a
  digest of the image-relevant part of the definition (all of it but the screen size set and the
  catalogue name), the bytes of the symbols its styles use and `RENDERER_REVISION`
  (`PortrayalDefinition`). Nothing is bumped by hand, except `RENDERER_REVISION` when a change to the
  Java drawing changes the image an unchanged definition gives. A change to the screen sizes alone
  changes no image.
- **Stale while it redraws.** An image shows the message as it is when it was drawn after the
  message last changed (its file time is set to the message's change time). When the message has
  no such image of the current version but has one of an earlier version, that one is served — its
  redirect may be cached for 60 s instead of 10 minutes — while one background regeneration draws
  the current one. The regenerations wait while image requests are being served, at most 2 s after
  they were asked for, so a Details or Grid page gets its images first and the redrawing follows
  it. A message edited since its last image is drawn while the request waits. A
  superseded file is deleted 16 minutes after its successor was drawn: longer than a browser may go
  on following a redirect to it, plus the time it may cache the file.
- **Custom thumbnails win.** A thumbnail an editor captured is served instead of the generated
  image and is left as it is. It may carry WMS pixels: the capture dialog shows the administration
  app's base layers, the WMS included where the deployment enables it.
- **A geometry collection** gets the pen on its lines and outlines, one tag at the centre of its
  extent and the point symbol at each of its points — the web's rule (`GeometryCollection` under
  "Rules by geometry primitive"), which the renderer mirrors. A change to it changes both sides,
  with the golden fixture of the case.

## What is deliberately not implemented

| Not implemented                                 | Why                                                                                                                                                                                                                                                                                                                | What it would take                                                                                     |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Dusk and Night palettes                         | Day only was the chosen scope; the colours are named in the definition, so a palette is additive                                                                                                                                                                                                                   | a second/third colour set in the definition plus a palette switch                                      |
| S-125 portrayal for `NM`                        | S-125 is Marine Aids to Navigation, not notices, but the product owner has ruled which of its change symbols each notice type takes (below). It waits for the S-125 portrayal catalogue to leave draft                                                                                                             | S-125 symbols, styles and `select` rows in `portrayal.json`; `messageType` set at the web call sites   |
| Calibrated physical size                        | S-124 sizes symbols and pens in millimetres on a calibrated display; a browser knows no such thing. Sizing is the catalogue's millimetres times one scale factor for the two symbols that share a position, and the tag sized for legibility — the proportions hold, the absolute millimetre on the glass does not | a DPI-aware rendering pass OpenLayers does not offer, over a calibration the user would have to supply |
| Producer-authored text labels                   | S-124 `TextPlacement` features carry black label text; there is no such data here (message titles appear in the hover tooltip instead)                                                                                                                                                                             | a text-placement field end to end                                                                      |
| The `QUESMRK1` fallback symbol                  | unrecognised geometry falls through to the surface rules rather than a magenta "?"                                                                                                                                                                                                                                 | one extra branch, if an encoding-error marker is ever wanted                                           |
| Viewing groups, display planes, SCAMIN plumbing | a web overlay does not need an S-100 portrayal engine; reproducing the output faithfully is the goal. Nothing is lost: the overlay is scale-independent by design, so there is no scale suppression to plumb                                                                                                       | a full S-100 engine                                                                                    |

**S-125 for notices is ruled, not built.** When it is built, a notice's type picks the change
symbol the product owner chose for it: `TEMPORARY_NOTICE` → `CHNGTC02` (`TC`), `PERMANENT_NOTICE` →
`CHNGCC01`, `PRELIMINARY_NOTICE` → `CHNGAC02` (`AC`), while `MISCELLANEOUS_NOTICE` keeps the S-124
`NM` symbol. A line or an area keeps the S-124 geometry, with the S-125 ring where its tag is. The
engine needs no change, and the backend nothing but the definition: its renderer already selects on
the message's type. It waits for the S-125 portrayal catalogue to settle; today it is the draft
1.0.0 in the IHO's `iho-ohi/S-125-Product-Specification-Development` repository, which carries no
licence file.

Transcribed from the S-125 catalogue for that work: its change symbology is point-only, a red
`CHRED` (`#EA5471`) ring with a two-letter code — `AC` advance notice or new aid (`changeTypes` 1),
`DC` discrepancy (2), `SC` proposed change (3), `TC` temporary change (4), and permanent change (5)
mapped to `CHNGCC01`, whose glyph prints `PC`; anything else falls back to `QUESMRK1`. Match the
code, not the letters: the catalogue also ships `CHNGPC02`, a second `PC` glyph meaning _proposed_
change, and its rules never reference it. The richer change details (light unlit, buoy adrift, …)
are textual in the standard, not symbolised.

Which warnings appear on the map at all is a filtering question, not a portrayal one — see
[features/messages.md](features/messages.md).

## Verifying a change

`map-styles.spec.ts` (in the kit, so **both** applications' suites run it) and `co-location.spec.ts`
cover the branch structure (symbol per primitive, no-fill polygons, halo stacking and suppression,
the badge on the primary feature only, the approximate adornment, the selection rows), and
`map-styles.spec.ts` pins the screen values both applications draw — a changed size, anchor, pen
or colour fails there, and the pin is updated in the same commit as the definition. The kit's
`portrayal.spec.ts` holds the definition to the shape its readers assume: every selection row names
an existing style and the last names no key, every style names existing symbols, every symbol is
sized in every size set, screen sizes are whole pixels and the point size is even, anchors lie
inside their box, colours are `#RRGGBB` or `rgba()`, and every dash is one dash and one gap. None
of them checks pixels, so walk the map after any portrayal edit — and walk it in both
applications, because one module now draws both:

1. An NW point draws the magenta NW-in-circle, no fill.
2. An NW line draws dashed magenta with upright "NW" tags whose on-screen spacing stays stable as you
   zoom.
3. An NW polygon draws a dashed magenta outline with **no** fill and exactly one centred tag.
4. NM renders identically, with the `NM` glyph.
5. The affected-radius / buffer overlay is translucent dashed magenta and carries no symbol.
6. Selection and hover show the orange and blue halos, _under_ the symbol; with
   `map.highlightEnabled` set to `false` they disappear.
7. Co-located warnings show one symbol plus one count badge.
8. Repeat what applies per surface: the messages map takes all of the above; the home map shows one
   symbol per message at the collapsed interior point (steps 1 and 4 only); the message-details map
   takes everything except the halos; the editor's insert-locations picker takes steps 1–5 plus its
   own selection halo, and has **no** count badge (step 7 does not apply there). The public site's
   three surfaces take steps 1–5 and nothing else — no halos, no count badge — and step 12 applies
   to its chart, not to the teaser, which is one link.
9. The approximate-position adornment must **not** appear — it is dormant by design.
10. On a details map, the numbered coordinate pins are magenta circles with white numerals, and a
    name sits just under its circle.
11. A line or a surface carries only its own tags — no point symbol at its middle, at any zoom.
12. A hover or a click inside an area, away from its outline and its tag, names that area's
    message, and so does one at the middle of a point symbol, between its ring and its letters.
13. A geometry the geometry editor merged from a point and a line or an area draws the point symbol
    at the point, beside the pen and the one tag of the rest.

### Golden fixtures

The backend's map images draw the same portrayal from the same definition, and the golden
fixtures keep the two engines together. `shared-map/portrayal/fixtures/cases.json` holds map views
of message features — points, lines, a zero-length line, a concave area, an area with a hole,
several areas, a buffer, a geometry collection, approximate positions, NW and NM, a line that
leaves the view — and `expected.json` beside it the draw list the web maps make of each: every
fill, stroke and symbol in drawing order, with its colour, pen and dash, the path coordinates and
each symbol's top-left corner in image pixels. The cases are drawn with the screen size set.

The web maps are the reference. `shared-map/portrayal-fixtures.spec.ts` (a kit spec, so both
applications' suites run it) draws each case with `map-styles.ts` and OpenLayers' own canvas replay
— the builders and executors a vector layer uses — onto a canvas that records every call, and
compares with `expected.json`. After a change to a case, to the screen sizes, to `map-styles.ts` or
to OpenLayers, run `npm run portrayal:fixtures` to write `expected.json` from the web again, and
commit the diff with the change. On the backend `MapImagePlannerTest` plans every case and compares
with the same file: path coordinates within a thousandth of a pixel, every symbol on the same whole
pixel. The backend's planner ports what decides a mark's pixels — the dispatch of `map-styles.ts`,
the tag sampling along a line, OpenLayers' interior point, the half-pixel simplification, the ring
orientation, the culling outside the view and the passes (areas, then lines, then symbols) — so a
change to `expected.json` that `MapImagePlannerTest` then fails is a change the port has to follow.

What the draw list cannot pin, and the two engines do differently:

- **Rasterisation.** A browser canvas (Skia) and Java 2D anti-alias the same path into slightly
  different edge pixels, and the backend rasterises the symbol SVGs with Batik rather than the
  browser's SVG renderer; both draw the SVG scaled from its own size as a browser does.
- **Device pixel ratio.** A map draws at the screen's device pixel ratio, which makes the
  simplification tolerance and the pixel snapping finer; an image is drawn at ratio 1, as the
  fixtures are.
- **Sizes that are not whole pixels.** At a `mapImageSize` other than 256 the print set scales to
  fractional sizes; the backend rasterises a symbol at the nearest whole-pixel size where OpenLayers
  would scale it.
- **A polygon edge exactly on the interior-point scan line** makes a NaN crossing, which JavaScript
  and Java sort alike only while there are fewer than 32 crossings.
- The web maps' own additions — halos, the count badge, coordinate pins, names — are not part of the
  portrayal and are not in the images.
