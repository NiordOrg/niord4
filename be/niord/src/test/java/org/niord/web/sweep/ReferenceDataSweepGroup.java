/*
 * Copyright 2026 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.web.sweep;

import io.restassured.path.json.JsonPath;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.test.http.HttpStub;
import org.niord.test.http.KeycloakAdminStub;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * The endpoint sweep of the reference data ({@code endpoints/reference-data.json}): the 62 handlers of
 * {@code AreaRestService}, {@code CategoryRestService}, {@code ChartRestService}, {@code DictionaryRestService},
 * {@code DomainRestService} and {@code MessageSeriesRestService}.
 *
 * <p>None of them has a code check: their role cells are the annotation's (one anonymous call each for the
 * {@code @RolesAllowed} ones, every tier in the full run), and the sweep runs as the least privileged identity the
 * annotation admits. The ten entries the generator marked {@code found} call {@code requireByPrimaryKey} or
 * {@code MessageSeriesService.requireSeriesAndYear}, input checks (404 or 400 for an unknown id or a year out of
 * range), not rules: the table says so in their notes.
 *
 * <p>The fixture, made through the API as admin or sysadmin: an area root with a child, a category root with a
 * child (each root sorted after every seeded root, so the tree recompute numbers them last), a chart, a key of the
 * {@code web} dictionary, a domain naming the area child and the category child, and a message series in no domain
 * with its next number set for 2099. The roots' geometry and the chart's lie in the Southern Ocean, where no seeded
 * area or chart is. The domain is made while the Keycloak integration points at a {@link KeycloakAdminStub}: a domain
 * create also creates its Keycloak client, and the suite's {@code authServerUrl} reaches no Keycloak. The writes make
 * rows of their own under the fixture's roots (or with unique ids), read back every field they sent, remove them and
 * read them gone; a message series id is never used twice (SERIES7: a series created again continues the number
 * sequence of a deleted one). The uploads are awaited and their job logs read. The two tree recomputes run once,
 * last, and are held to keep the order of every area or category that was there before.
 *
 * <p>No existing IT counts the lists these rows are added to while the sweep runs (the sweep is one IT class; the
 * rows are gone after its tear-down). What stays: each upload's batch instance, a defaults-ledger mark for each
 * dictionary key the sweep deleted, and the number sequences of the fixture series ({@link #leftByDesign()}).
 */
class ReferenceDataSweepGroup implements SweepGroup {

    static final String AREA_ROOT = "sweep-reference-data-area-root";
    static final String AREA_CHILD = "sweep-reference-data-area-child";
    static final String CATEGORY_ROOT = "sweep-reference-data-category-root";
    static final String CATEGORY_CHILD = "sweep-reference-data-category-child";
    static final String CHART = "sweep-reference-data-chart";
    static final String DICTIONARY_KEY = "sweep-reference-data-key";
    static final String DOMAIN = "sweep-reference-data-domain";
    static final String SERIES = "sweep-reference-data-series";

    /** The seeded dictionary the sweep's keys are made in (the admin app's own texts) */
    static final String DICTIONARY = "web";

    /** An id no row has */
    static final long UNKNOWN = 987654321L;

    /** The sibling sort order of the fixture's roots: after every seeded root */
    static final double LAST = 1_000_000d;

    /** The year and the next number the fixture sets for its series, and the year the number write sets */
    static final int NUMBER_YEAR = 2099;
    static final int NUMBER = 7;
    static final int WRITE_YEAR = 2098;

    /** The AtoN filter of the dictionary key: matches a minor light */
    static final String ATON_FILTER = "aton.kv('seamark:type', 'light_minor')";

    /** The backlog's entry on the two lookups that send geometry=false to handlers that do not read it */
    static final String GEOMETRY_BACKLOG = "Two lookups send `geometry=false` to handlers that do not read it";

    @Override
    public String name() {
        return "reference-data";
    }

    @Override
    public Set<String> rows() {
        return Set.of(AREA_ROOT, AREA_CHILD, CATEGORY_ROOT, CATEGORY_CHILD, CHART, DICTIONARY_KEY, DOMAIN, SERIES);
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture
    // ------------------------------------------------------------------------------------------- //

    /** The row count of each of the group's tables before the fixture; the tear-down holds the tables to it */
    private Map<String, Long> countsBefore;

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        countsBefore = tableCounts(ctx);
        int areaRoot = findOrCreateArea(ctx, AREA_ROOT, null, LAST, box(170, -60, 171, -59));
        ctx.putRow(AREA_ROOT, areaRoot);
        ctx.putRow(AREA_CHILD, findOrCreateArea(ctx, AREA_CHILD, areaRoot, 1, box(170.2, -59.8, 170.8, -59.2)));

        int categoryRoot = findOrCreateCategory(ctx, CATEGORY_ROOT, null, LAST);
        ctx.putRow(CATEGORY_ROOT, categoryRoot);
        ctx.putRow(CATEGORY_CHILD, findOrCreateCategory(ctx, CATEGORY_CHILD, categoryRoot, 1));

        if (charts(ctx, CHART).isEmpty()) {
            ctx.ok("admin", SweepRequest.post("/rest/charts/chart").json(chartBody(CHART, "Sweep chart", 900001)));
        }
        ctx.putRow(CHART, CHART);

        if (entry(ctx, DICTIONARY_KEY) == null) {
            ctx.ok("sysadmin", SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", DICTIONARY)
                    .json(entryBody(DICTIONARY_KEY, "Sweep text", "Sweep tekst", ATON_FILTER)));
        }
        ctx.putRow(DICTIONARY_KEY, DICTIONARY_KEY);

        if (series(ctx, SERIES) == null) {
            ctx.ok("sysadmin", SweepRequest.post("/rest/message-series/series").json(seriesBody(SERIES)));
        }
        ctx.ok("sysadmin", SweepRequest.put("/rest/message-series/series/{seriesIds}/number/{year}")
                .var("seriesIds", SERIES).var("year", NUMBER_YEAR).json(NUMBER));
        ctx.putRow(SERIES, SERIES);

        if (domain(ctx, DOMAIN) == null) {
            try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
                withKeycloak(ctx, keycloak.baseUrl(), () -> ctx.ok("sysadmin",
                        SweepRequest.post("/rest/domains/domain").json(domainBody(ctx, DOMAIN, "Sweep domain"))));
            }
        }
        ctx.putRow(DOMAIN, DOMAIN);
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        // The domain first: it names the area and category children, which refuses their delete
        if (ctx.hasRow(DOMAIN)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/domains/domain/{domainId}").var("domainId", DOMAIN));
            if (domain(ctx, DOMAIN) != null) {
                left.add(DOMAIN);
            }
        }
        for (String root : List.of(AREA_ROOT, CATEGORY_ROOT)) {
            if (!ctx.hasRow(root)) {
                continue;
            }
            boolean area = root.equals(AREA_ROOT);
            String child = area ? AREA_CHILD : CATEGORY_CHILD;
            Object id = ctx.row(root);
            // The root's delete removes its subtree; read both gone
            ctx.send(area ? "admin" : "sysadmin", area ? deleteArea(id) : deleteCategory(id));
            for (String row : List.of(root, child)) {
                if (ctx.hasRow(row)) {
                    SweepResponse read = ctx.send("anonymous", (area ? getArea(ctx.row(row)) : getCategory(ctx.row(row)))
                            .note("read after the tear-down's delete"));
                    if (!read.is2xx() || read.body().length > 0) {
                        left.add(row + " (id " + ctx.row(row) + ", read after the delete: " + read.status() + ")");
                    }
                }
            }
        }
        if (ctx.hasRow(CHART)) {
            ctx.send("admin", SweepRequest.delete("/rest/charts/chart/{chartNumber}").var("chartNumber", CHART));
            if (!charts(ctx, CHART).isEmpty()) {
                left.add(CHART);
            }
        }
        if (ctx.hasRow(DICTIONARY_KEY)) {
            ctx.send("sysadmin", deleteEntry(DICTIONARY_KEY));
            if (entry(ctx, DICTIONARY_KEY) != null) {
                left.add(DICTIONARY_KEY);
            }
        }
        if (ctx.hasRow(SERIES)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/message-series/series/{seriesId}").var("seriesId", SERIES));
            if (series(ctx, SERIES) != null) {
                left.add(SERIES);
            }
        }
        // Every row a case made is gone too: no table of the group holds more rows than before the fixture
        if (countsBefore != null) {
            try {
                Map<String, Long> after = tableCounts(ctx);
                System.out.println("Endpoint sweep: reference-data: rows per table before the fixture " + countsBefore
                        + ", after the tear-down " + after);
                countsBefore.forEach((table, before) -> {
                    if (after.getOrDefault(table, 0L) > before) {
                        left.add("table " + table + ": " + before + " rows before the fixture, " + after.get(table)
                                + " after the tear-down");
                    }
                });
            } catch (Exception e) {
                left.add("the group's tables could not be counted after the tear-down: " + e);
            }
        }
        return left;
    }

    /** The number of rows of each of the group's tables */
    private Map<String, Long> tableCounts(SweepContext ctx) throws Exception {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection connection = ctx.bean(DataSource.class).getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : tables()) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.\"" + table + "\"")) {
                    rows.next();
                    counts.put(table, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    @Override
    public Map<String, String> leftByDesign() {
        Map<String, String> left = new LinkedHashMap<>();
        left.put("the batch instance of each upload (area-import, category-import, chart-import, dictionary-import; "
                + "file sweep-reference-data-<unique>.json)", "no endpoint removes a batch instance");
        left.put("a defaults-ledger 'deleted' mark (DefaultsLedger, lang '*') for each key of the web dictionary the "
                        + "sweep deleted (sweep-reference-data-...)",
                "a deleted dictionary key is recorded so that start-up does not add it again; no endpoint removes it");
        left.put("the number sequences MESSAGE_SERIES_NUMBER_sweep-reference-data-series_2099 and _2098 (Sequence)",
                "SERIES7: deleting a series leaves its number sequence; no endpoint removes one");
        return left;
    }

    @Override
    public Set<String> tables() {
        return Set.of("Area", "AreaDesc", "Area_editorFields", "Category", "CategoryDesc", "Category_Domain",
                "Category_editorFields", "Category_scriptResourcePaths", "Category_stdTemplateFields", "Chart", "DictionaryEntry", "DictionaryEntryDesc", "Domain", "Domain_Area",
                "Domain_Category", "Domain_MessageSeries", "MessageSeries", "MessageSeries_types",
                "MessageSeries_editorFields");
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        areaCases(cases);
        categoryCases(cases);
        chartCases(cases);
        dictionaryCases(cases);
        domainCases(cases);
        seriesCases(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // Areas
    // ------------------------------------------------------------------------------------------- //

    private void areaCases(SweepCases cases) {
        cases.add("getAllAreas", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/areas/all")));

        cases.add("getAreaRoots", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/areas/area-roots").query("lang", "en"))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/areas/area-roots")
                        .query("lang", "da")), null, AREA_ROOT + "-da", AREA_CHILD + "-da")));

        cases.add("getArea", SweepCase
                .of((ctx, row) -> getArea(ctx.row(row == null ? AREA_CHILD : row)))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, getArea(ctx.row(AREA_CHILD)).query("parent", true)),
                        "parent", AREA_ROOT))
                .probes((ctx, as) -> {
                    // The handler answers an unknown id with no body
                    SweepResponse unknown = ctx.ok(as, getArea(UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals(0, unknown.body().length, "the size of the answer for an unknown id", unknown);
                    ctx.call(as.name(), getArea("abc").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'areaId' with value: 'abc'"));
                }));

        cases.add("searchAreas", SweepCase
                .of((ctx, row) -> searchAreas(AREA_ROOT).query("limit", 10))
                .sweep((ctx, as) -> {
                    // The limit defaults to 0, which the query takes for "no row": the search without a limit
                    // answers no area, as the old system's did (it also passed 0 to setMaxResults); every caller
                    // sends a limit. Not a fault (wave 3b's second reader)
                    SweepResponse unlimited = ctx.ok(as, SweepRequest.get("/rest/areas/search").query("lang", "en")
                            .query("name", AREA_ROOT));
                    ctx.checkEquals(List.of(), unlimited.json().getList("$"),
                            "the search without a limit (no area: the limit defaults to 0)", unlimited);
                    ctx.expectRows(ctx.ok(as, searchAreas(AREA_ROOT).query("limit", 10)), null, AREA_ROOT);
                })
                .variants((ctx, as) -> {
                    SweepResponse withGeometry = ctx.ok(as, searchAreas(AREA_ROOT).query("limit", 10).query("geometry", true));
                    ctx.check(withGeometry.json().get("find { it.descs[0].name == '" + AREA_ROOT + "' }.geometry")
                            != null, "geometry=true answers the area with its geometry", withGeometry);
                    // The domain names the child: the search keeps the areas under it
                    SweepResponse inDomain = ctx.ok(as, searchAreas("sweep-reference-data-area").query("limit", 10)
                            .query("domain", DOMAIN));
                    ctx.expectRows(inDomain, null, AREA_CHILD);
                    ctx.check(!names(inDomain).contains(AREA_ROOT),
                            "the domain's search leaves out the root it does not name", inDomain);
                    SweepResponse limited = ctx.ok(as, searchAreas("sweep-reference-data-area").query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of areas with limit=1", limited);
                    SweepResponse sorting = ctx.ok(as, searchAreas(AREA_ROOT).query("limit", 10)
                            .query("messageSorting", true));
                    ctx.checkEquals(List.of(), sorting.json().getList("$"),
                            "the areas with a message sorting among those named " + AREA_ROOT, sorting);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), searchAreas(AREA_ROOT).query("limit", "abc").note("malformed limit"),
                            Cell.status(400, "Failed to convert 'limit' with value: 'abc'"));
                    ctx.call(as.name(), searchAreas(AREA_ROOT).query("geometry", "maybe").note("malformed geometry"),
                            Cell.status(400, "Failed to convert 'geometry' with value: 'maybe'"));
                }));

        cases.add("searchAreaIds", SweepCase
                .of((ctx, row) -> searchAreaIds(ctx.row(AREA_ROOT) + "," + ctx.row(AREA_CHILD)))
                .variants((ctx, as) -> {
                    SweepResponse limited = ctx.ok(as, searchAreaIds(ctx.row(AREA_ROOT) + "," + ctx.row(AREA_CHILD))
                            .query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of areas with limit=1", limited);
                    // The admin app sends geometry=false; the handler reads no such parameter (the backlog)
                    SweepResponse withoutGeometry = ctx.ok(as, searchAreaIds(String.valueOf(ctx.row(AREA_CHILD)))
                            .query("geometry", false).note("effects variant"));
                    ctx.check(withoutGeometry.json().get("[0].geometry") == null,
                            "geometry=false leaves the geometry out", withoutGeometry);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, searchAreaIds(String.valueOf(UNKNOWN)).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown id", unknown);
                    ctx.call(as.name(), searchAreaIds("abc").note("malformed-id probe"),
                            Cell.status(400, "For input string: \"abc\""));
                }));

        cases.add("computeIntersectingAreas", SweepCase
                .of((ctx, row) -> intersectingAreas(box(170.3, -59.7, 170.7, -59.3)))
                .variants((ctx, as) -> {
                    // maxLevel=1 answers the root instead of the child
                    SweepResponse level1 = ctx.ok(as, intersectingAreas(box(170.3, -59.7, 170.7, -59.3))
                            .query("maxLevel", 1));
                    ctx.expectRows(level1, null, AREA_ROOT);
                    ctx.check(!names(level1).contains(AREA_CHILD), "maxLevel=1 answers no area below the root", level1);
                    // domain=true keeps the areas of the request's domain: the fixture's domain names the child
                    ctx.expectRows(ctx.ok(as, intersectingAreas(box(170.3, -59.7, 170.7, -59.3)).query("domain", true)
                            .domain(DOMAIN)), null, AREA_CHILD);
                    // A geometry inside the root but outside the child: without the domain it answers the root, with
                    // domain=true and the fixture's domain (which names only the child) it answers no area
                    Map<String, Object> rootOnly = box(170.85, -59.95, 170.95, -59.85);
                    SweepResponse outside = ctx.ok(as, intersectingAreas(rootOnly));
                    ctx.expectRows(outside, null, AREA_ROOT);
                    SweepResponse outsideDomain = ctx.ok(as, intersectingAreas(rootOnly).query("domain", true)
                            .domain(DOMAIN));
                    ctx.checkEquals(List.of(), outsideDomain.json().getList("$"),
                            "domain=true answers no area outside the request domain's areas", outsideDomain);
                    SweepResponse withGeometry = ctx.ok(as, intersectingAreas(box(170.3, -59.7, 170.7, -59.3))
                            .query("geometry", true));
                    ctx.check(withGeometry.json().get("[0].geometry") != null,
                            "geometry=true answers the areas with their geometry", withGeometry);
                })
                .probes((ctx, as) -> {
                    SweepResponse empty = ctx.ok(as, SweepRequest.post("/rest/areas/intersecting-areas")
                            .json(Map.of("type", "FeatureCollection", "features", List.of())).note("no features"));
                    ctx.checkEquals(List.of(), empty.json().getList("$"), "the areas of no geometry", empty);
                    ctx.call(as.name(), intersectingAreas(box(170.3, -59.7, 170.7, -59.3)).query("maxLevel", "abc")
                            .note("malformed maxLevel"), Cell.status(400, "Failed to convert 'maxLevel' with value: 'abc'"));
                }));

        cases.add("createArea", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/areas/area")
                        .json(areaBody(ctx.name("refused-" + ctx.unique()), ctx.row(AREA_ROOT), 5, null)))
                .sweep((ctx, as) -> createAreaReadBackAndRemove(ctx, as, "/rest/areas/area"))
                .variants((ctx, as) -> createAreaReadBackAndRemove(ctx, as, "/rest/areas/area/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/areas/area").json(Map.of()).note("empty body"),
                            Cell.status(400, "An area needs a name in at least one language"));
                    Map<String, Object> withId = areaBody(ctx.name("with-id"), ctx.row(AREA_ROOT), 5, null);
                    withId.put("id", ctx.row(AREA_CHILD));
                    ctx.call(as.name(), SweepRequest.post("/rest/areas/area").json(withId).note("a create with an id"),
                            Cell.status(400, null));
                    // An unknown parent: the move's refusal (ERR6, fixed)
                    ctx.call(as.name(), SweepRequest.post("/rest/areas/area")
                                    .json(areaBody(ctx.name("orphan-" + ctx.unique()), UNKNOWN, 5, null))
                                    .note("an unknown parent"),
                            Cell.status(404, "No Area with id " + UNKNOWN));
                }));

        cases.add("updateArea", SweepCase
                // A refused cell sends the fixture's child as it is
                .of((ctx, row) -> SweepRequest.put("/rest/areas/area/{areaId}").var("areaId", ctx.row(AREA_CHILD))
                        .json(withId(areaBody(AREA_CHILD, ctx.row(AREA_ROOT), 1, box(170.2, -59.8, 170.8, -59.2)),
                                ctx.row(AREA_CHILD))))
                .sweep((ctx, as) -> {
                    int id = createArea(ctx, as, ctx.name("update-" + ctx.unique()), ctx.row(AREA_ROOT), 5);
                    try {
                        String renamed = ctx.name("updated-" + ctx.unique());
                        Map<String, Object> changed = withId(fullAreaBody(ctx, renamed, ctx.row(AREA_ROOT)), id);
                        changed.put("type", "FIRING_AREA");
                        changed.put("messageSorting", "WE");
                        changed.put("originAngle", 45);
                        changed.put("siblingSortOrder", 7.5);
                        changed.put("editorFields", List.of("charts"));
                        changed.put("geometry", box(170.25, -59.75, 170.75, -59.25));
                        ctx.ok(as, SweepRequest.put("/rest/areas/area/{areaId}").var("areaId", id).json(changed));
                        readBackArea(ctx, id, changed);
                    } finally {
                        removeAreaAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    Object child = ctx.row(AREA_CHILD);
                    Map<String, Object> mismatched = withId(areaBody(AREA_CHILD, ctx.row(AREA_ROOT), 1, null),
                            ((Number) child).longValue() + 1);
                    ctx.call(as.name(), SweepRequest.put("/rest/areas/area/{areaId}").var("areaId", child).json(mismatched)
                            .note("the body's id is not the path's"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/areas/area/{areaId}").var("areaId", UNKNOWN)
                                    .json(withId(areaBody(ctx.name("unknown"), null, 1, null), UNKNOWN))
                                    .note("unknown-id probe"),
                            Cell.status(404, "No Area with id " + UNKNOWN));
                }));

        cases.add("deleteArea", SweepCase
                // The fixture's child is named by the fixture's domain: a delete that got through is refused (409)
                .of((ctx, row) -> deleteArea(ctx.row(AREA_CHILD)))
                .sweep((ctx, as) -> removeAreaAndReadGone(ctx, as,
                        createArea(ctx, as, ctx.name("delete-" + ctx.unique()), ctx.row(AREA_ROOT), 5)))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, deleteArea(UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals("false", unknown.text().trim(), "the answer for an unknown id", unknown);
                    ctx.call(as.name(), deleteArea(ctx.row(AREA_CHILD)).note("an area a domain names"),
                            Cell.status(409, "The area or one of its sub-areas is in use: the domain " + DOMAIN));
                }));

        cases.add("moveArea", SweepCase
                // A refused cell moves the child under the parent it has: a move that got through changes nothing
                .of((ctx, row) -> moveArea(ctx.row(AREA_CHILD), ctx.row(AREA_ROOT)))
                .sweep((ctx, as) -> {
                    int a = createArea(ctx, as, ctx.name("move-a-" + ctx.unique()), ctx.row(AREA_ROOT), 5);
                    try {
                        int b = createArea(ctx, as, ctx.name("move-b-" + ctx.unique()), ctx.row(AREA_ROOT), 6);
                        SweepResponse moved = ctx.ok(as, moveArea(b, a));
                        ctx.checkEquals("true", moved.text().trim(), "the answer of the move", moved);
                        SweepResponse read = ctx.ok("anonymous", getArea(b).query("parent", true));
                        ctx.checkEquals(a, read.json().getInt("parent.id"), "the moved area's parent", read);
                        ctx.checkEquals(ctx.row(AREA_ROOT), read.json().getInt("parent.parent.id"),
                                "the moved area's grandparent", read);
                    } finally {
                        // The delete of the first removes the second, now under it
                        removeAreaAndReadGone(ctx, as, a);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), moveArea(UNKNOWN, ctx.row(AREA_ROOT)).note("unknown-id probe"),
                            Cell.status(404, "No Area with id " + UNKNOWN));
                    ctx.call(as.name(), moveArea(ctx.row(AREA_ROOT), ctx.row(AREA_CHILD)).note("a move under its own child"),
                            Cell.status(400, "cannot be moved under itself or under one of its own descendants"));
                }));

        cases.add("changeAreaSortOrder", SweepCase
                // A refused cell moves the fixture's only child up: a change that got through changes nothing
                .of((ctx, row) -> SweepRequest.put("/rest/areas/change-sort-order")
                        .json(Map.of("areaId", ctx.row(AREA_CHILD), "moveUp", true)))
                .sweep((ctx, as) -> {
                    int parent = createArea(ctx, as, ctx.name("order-" + ctx.unique()), ctx.row(AREA_ROOT), 5);
                    try {
                        int first = createArea(ctx, as, ctx.name("order-a-" + ctx.unique()), parent, 1);
                        int second = createArea(ctx, as, ctx.name("order-b-" + ctx.unique()), parent, 2);
                        SweepResponse changed = ctx.ok(as, SweepRequest.put("/rest/areas/change-sort-order")
                                .json(Map.of("areaId", second, "moveUp", true)));
                        ctx.checkEquals("true", changed.text().trim(), "the answer of the change", changed);
                        double a = ctx.ok("anonymous", getArea(first)).json().getDouble("siblingSortOrder");
                        SweepResponse read = ctx.ok("anonymous", getArea(second));
                        ctx.check(read.json().getDouble("siblingSortOrder") < a,
                                "the area moved up sorts before its sibling (" + a + ")", read);
                    } finally {
                        removeAreaAndReadGone(ctx, as, parent);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/areas/change-sort-order")
                                .json(Map.of("areaId", UNKNOWN, "moveUp", true)).note("unknown-id probe"),
                        Cell.status(404, "No Area with id " + UNKNOWN))));

        cases.add("recomputeAreaTreeSortOrder", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/areas/recompute-tree-sort-order"))
                .sweep((ctx, as) -> recomputeKeepsTheOrder(ctx, as, "Area", "/rest/areas/recompute-tree-sort-order",
                        List.of(AREA_ROOT, AREA_CHILD))));

        cases.add("importAreas", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/areas/upload-areas")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String root = ctx.name("uploaded-" + ctx.unique());
                    String child = root + "-child";
                    String json = "[{\"active\":true,\"siblingSortOrder\":" + (LAST + 1) + ",\"descs\":[{\"lang\":\"en\","
                            + "\"name\":\"" + root + "\"}],\"children\":[{\"active\":true,\"type\":\"COUNTRY\","
                            + "\"descs\":[{\"lang\":\"en\",\"name\":\"" + child + "\"},{\"lang\":\"da\",\"name\":\""
                            + child + "-da\"}]}]}]";
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post("/rest/areas/upload-areas"), json);
                    ctx.jobs().awaitClean(ctx, "area-import", file);
                    Integer rootId = areaId(ctx, root);
                    if (rootId == null) {
                        ctx.fail("The uploaded area " + root + " is not found after its job completed");
                        return;
                    }
                    try {
                        Integer childId = areaId(ctx, child);
                        ctx.check(childId != null, "the uploaded child " + child + " is found", null);
                        if (childId != null) {
                            SweepResponse read = ctx.ok("anonymous", getArea(childId).query("parent", true));
                            ctx.checkEquals(rootId, read.json().getInt("parent.id"), "the uploaded child's parent", read);
                            ctx.checkEquals("COUNTRY", read.json().getString("type"), "the uploaded child's type", read);
                            ctx.checkEquals(child + "-da", read.json().getString("descs.find { it.lang == 'da' }.name"),
                                    "the uploaded child's Danish name", read);
                        }
                    } finally {
                        removeAreaAndReadGone(ctx, as, rootId);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/areas/upload-areas")
                                .file("file", ctx.name("broken") + ".json", "[{\"active\":".getBytes(), "application/json")
                                .note("a broken file"),
                        Cell.status(400, "is not valid JSON"))));
    }

    /** The write sweep of the area create: every field, read back, removed, read gone */
    private static void createAreaReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Map<String, Object> body = fullAreaBody(ctx, ctx.name("created-" + ctx.unique()), ctx.row(AREA_ROOT));
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        int id = created.json().getInt("id");
        try {
            readBackArea(ctx, id, body);
        } finally {
            removeAreaAndReadGone(ctx, as, id);
        }
    }

    /** An area of every field: type, MRN, sibling order, parent, two names, geometry, sorting, origin, editor fields */
    private static Map<String, Object> fullAreaBody(SweepContext ctx, String name, Object parentId) {
        Map<String, Object> body = areaBody(name, parentId, 5, box(170.3, -59.7, 170.7, -59.3));
        body.put("type", "COUNTRY");
        body.put("mrn", "urn:mrn:sweep:reference-data:" + ctx.unique());
        body.put("messageSorting", "NS");
        body.put("originLatitude", -59.5);
        body.put("originLongitude", 170.5);
        body.put("originAngle", 90);
        body.put("editorFields", List.of("title", "areas"));
        return body;
    }

    /** Reads the area back as anonymous (with its parent) and checks every field the write sent */
    @SuppressWarnings("unchecked")
    private static void readBackArea(SweepContext ctx, int id, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", getArea(id).query("parent", true));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(id, json.getInt("id"), "the id", read);
        ctx.checkEquals(sent.get("type"), json.getString("type"), "the type", read);
        ctx.checkEquals(sent.get("mrn"), json.getString("mrn"), "the MRN", read);
        ctx.checkEquals(sent.get("active"), json.getBoolean("active"), "active", read);
        ctx.checkEquals(((Number) sent.get("siblingSortOrder")).doubleValue(), json.getDouble("siblingSortOrder"),
                "the sibling sort order", read);
        ctx.checkEquals(((Map<String, Object>) sent.get("parent")).get("id"), json.getInt("parent.id"), "the parent",
                read);
        for (Map<String, Object> desc : (List<Map<String, Object>>) sent.get("descs")) {
            ctx.checkEquals(desc.get("name"), json.getString("descs.find { it.lang == '" + desc.get("lang") + "' }.name"),
                    "the " + desc.get("lang") + " name", read);
        }
        ctx.checkEquals(((List<?>) sent.get("descs")).size(), json.getList("descs").size(), "the number of names", read);
        ctx.checkEquals(ring(sent.get("geometry")), ring(json.get("geometry")), "the geometry", read);
        ctx.checkEquals(sent.get("messageSorting"), json.getString("messageSorting"), "the message sorting", read);
        ctx.checkEquals(((Number) sent.get("originLatitude")).doubleValue(), json.getDouble("originLatitude"),
                "the origin latitude", read);
        ctx.checkEquals(((Number) sent.get("originLongitude")).doubleValue(), json.getDouble("originLongitude"),
                "the origin longitude", read);
        ctx.checkEquals(sent.get("originAngle"), json.getInt("originAngle"), "the origin angle", read);
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("editorFields")), new HashSet<>(json.getList("editorFields")),
                "the editor fields", read);
    }

    /** Creates an area of the name under the parent as the identity; answers its id */
    private static int createArea(SweepContext ctx, SweepIdentity as, String name, Object parentId, double order) {
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/areas/area").json(areaBody(name, parentId, order,
                null)));
        if (!created.is2xx()) {
            throw new IllegalStateException("The area " + name + " could not be made: " + created.status());
        }
        return created.json().getInt("id");
    }

    /** Removes the area (and its subtree) as the identity, and reads it gone */
    private static void removeAreaAndReadGone(SweepContext ctx, SweepIdentity as, Object id) {
        SweepResponse removed = ctx.ok(as, deleteArea(id));
        ctx.checkEquals("true", removed.text().trim(), "the answer of the delete", removed);
        SweepResponse read = ctx.ok("anonymous", getArea(id).note("read after the delete"));
        ctx.checkEquals(0, read.body().length, "the size of the answer for the deleted area", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Categories
    // ------------------------------------------------------------------------------------------- //

    private void categoryCases(SweepCases cases) {
        cases.add("getAllCategories", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/categories/all")));

        cases.add("getCategoryRoots", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/categories/category-roots").query("lang", "en"))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/categories/category-roots")
                        .query("lang", "da")), null, CATEGORY_ROOT + "-da", CATEGORY_CHILD + "-da")));

        cases.add("exportCategories", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/categories/export"))
                .variants((ctx, as) -> {
                    SweepResponse exported = ctx.ok(as, SweepRequest.get("/rest/categories/export"));
                    ctx.check(exported.json().get("find { it.descs.find { d -> d.name == '" + CATEGORY_ROOT
                            + "' } }.id") == null, "the export leaves the ids out", exported);
                }));

        cases.add("getCategory", SweepCase
                .of((ctx, row) -> getCategory(ctx.row(row == null ? CATEGORY_CHILD : row)))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, getCategory(ctx.row(CATEGORY_CHILD))
                        .query("parent", true)), "parent", CATEGORY_ROOT))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, getCategory(UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals(0, unknown.body().length, "the size of the answer for an unknown id", unknown);
                    ctx.call(as.name(), getCategory("abc").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'categoryId' with value: 'abc'"));
                }));

        cases.add("searchCategories", SweepCase
                .of((ctx, row) -> searchCategories(CATEGORY_ROOT))
                .variants((ctx, as) -> {
                    SweepResponse templates = ctx.ok(as, searchCategories(CATEGORY_ROOT).query("type", "TEMPLATE"));
                    ctx.checkEquals(List.of(), templates.json().getList("$"),
                            "the templates among the categories named " + CATEGORY_ROOT, templates);
                    SweepResponse below = ctx.ok(as, searchCategories("sweep-reference-data-category")
                            .query("ancestorId", ctx.row(CATEGORY_ROOT)));
                    ctx.expectRows(below, null, CATEGORY_CHILD);
                    ctx.check(!names(below).contains(CATEGORY_ROOT),
                            "ancestorId answers the categories below the ancestor only", below);
                    // The domain names the child: the search keeps the categories under it and leaves out the root
                    SweepResponse inDomain = ctx.ok(as, searchCategories("sweep-reference-data-category")
                            .query("domain", DOMAIN));
                    ctx.expectRows(inDomain, null, CATEGORY_CHILD);
                    ctx.check(!names(inDomain).contains(CATEGORY_ROOT),
                            "the domain's search leaves out the root it does not name", inDomain);
                    SweepResponse limited = ctx.ok(as, searchCategories("sweep-reference-data-category")
                            .query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of categories with limit=1",
                            limited);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), searchCategories(CATEGORY_ROOT).query("type", "NOSUCHTYPE").note("unknown type"),
                            Cell.status(400, "Failed to convert 'type' with value: 'NOSUCHTYPE'"));
                    ctx.call(as.name(), searchCategories(CATEGORY_ROOT).query("ancestorId", "abc")
                            .note("malformed ancestorId"), Cell.status(400, "Failed to convert 'ancestorId' with value: 'abc'"));
                }));

        cases.add("searchCategoriesWithAtons", SweepCase
                .of((ctx, row) -> searchCategoriesWithAtons(CATEGORY_ROOT, List.of()))
                .probes((ctx, as) -> {
                    List<Map<String, Object>> atons = new ArrayList<>();
                    for (int i = 0; i <= 100; i++) {
                        atons.add(Map.of("tags", Map.of("seamark:type", "light_minor")));
                    }
                    ctx.call(as.name(), searchCategoriesWithAtons(CATEGORY_ROOT, atons).note("101 AtoNs"),
                            Cell.status(400, "At most 100 AtoNs per search"));
                }));

        cases.add("searchCategoryIds", SweepCase
                .of((ctx, row) -> searchCategoryIds(ctx.row(CATEGORY_ROOT) + "," + ctx.row(CATEGORY_CHILD)))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, searchCategoryIds(String.valueOf(UNKNOWN)).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown id", unknown);
                    ctx.call(as.name(), searchCategoryIds("abc").note("malformed-id probe"),
                            Cell.status(400, "For input string: \"abc\""));
                }));

        cases.add("createCategory", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/categories/category")
                        .json(categoryBody(ctx.name("refused-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 5)))
                .sweep((ctx, as) -> createCategoryReadBackAndRemove(ctx, as, "/rest/categories/category"))
                .variants((ctx, as) -> createCategoryReadBackAndRemove(ctx, as, "/rest/categories/category/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/categories/category").json(Map.of()).note("empty body"),
                            Cell.status(400, "A category needs a name in at least one language"));
                    ctx.call(as.name(), SweepRequest.post("/rest/categories/category")
                                    .json(withId(categoryBody(ctx.name("with-id"), ctx.row(CATEGORY_ROOT), 5),
                                            ctx.row(CATEGORY_CHILD))).note("a create with an id"),
                            Cell.status(400, null));
                    // An unknown parent: the move's refusal (ERR6, fixed)
                    ctx.call(as.name(), SweepRequest.post("/rest/categories/category")
                                    .json(categoryBody(ctx.name("orphan-" + ctx.unique()), UNKNOWN, 5))
                                    .note("an unknown parent"),
                            Cell.status(404, "No Category with id " + UNKNOWN));
                }));

        cases.add("updateCategory", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/categories/category/{categoryId}")
                        .var("categoryId", ctx.row(CATEGORY_CHILD))
                        .json(withId(categoryBody(CATEGORY_CHILD, ctx.row(CATEGORY_ROOT), 1), ctx.row(CATEGORY_CHILD))))
                .sweep((ctx, as) -> {
                    int id = createCategory(ctx, as, ctx.name("update-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 5);
                    try {
                        Map<String, Object> changed = withId(fullCategoryBody(ctx, ctx.name("updated-" + ctx.unique()),
                                ctx.row(CATEGORY_ROOT)), id);
                        changed.put("type", "TEMPLATE");
                        changed.put("siblingSortOrder", 7.5);
                        changed.put("editorFields", List.of("charts"));
                        changed.put("domains", List.of());
                        changed.put("atonFilter", "aton.kv('seamark:type', 'buoy_lateral')");
                        changed.put("scriptResourcePaths", List.of("templates/sweep-reference-data/updated.js"));
                        changed.put("messageId", "sweep-reference-data-message-updated");
                        changed.put("stdTemplateFields", List.of("charts"));
                        ctx.ok(as, SweepRequest.put("/rest/categories/category/{categoryId}").var("categoryId", id)
                                .json(changed));
                        readBackCategory(ctx, id, changed);
                    } finally {
                        removeCategoryAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    Object child = ctx.row(CATEGORY_CHILD);
                    ctx.call(as.name(), SweepRequest.put("/rest/categories/category/{categoryId}").var("categoryId", child)
                                    .json(withId(categoryBody(CATEGORY_CHILD, ctx.row(CATEGORY_ROOT), 1),
                                            ((Number) child).longValue() + 1)).note("the body's id is not the path's"),
                            Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/categories/category/{categoryId}").var("categoryId", UNKNOWN)
                                    .json(withId(categoryBody(ctx.name("unknown"), null, 1), UNKNOWN))
                                    .note("unknown-id probe"),
                            Cell.status(404, "No Category with id " + UNKNOWN));
                }));

        cases.add("deleteCategory", SweepCase
                // The fixture's child is named by the fixture's domain
                .of((ctx, row) -> deleteCategory(ctx.row(CATEGORY_CHILD)))
                .sweep((ctx, as) -> removeCategoryAndReadGone(ctx, as,
                        createCategory(ctx, as, ctx.name("delete-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 5)))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, deleteCategory(UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals("false", unknown.text().trim(), "the answer for an unknown id", unknown);
                }));

        cases.add("moveCategory", SweepCase
                .of((ctx, row) -> moveCategory(ctx.row(CATEGORY_CHILD), ctx.row(CATEGORY_ROOT)))
                .sweep((ctx, as) -> {
                    int a = createCategory(ctx, as, ctx.name("move-a-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 5);
                    try {
                        int b = createCategory(ctx, as, ctx.name("move-b-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 6);
                        SweepResponse moved = ctx.ok(as, moveCategory(b, a));
                        ctx.checkEquals("true", moved.text().trim(), "the answer of the move", moved);
                        SweepResponse read = ctx.ok("anonymous", getCategory(b).query("parent", true));
                        ctx.checkEquals(a, read.json().getInt("parent.id"), "the moved category's parent", read);
                    } finally {
                        removeCategoryAndReadGone(ctx, as, a);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), moveCategory(UNKNOWN, ctx.row(CATEGORY_ROOT)).note("unknown-id probe"),
                            Cell.status(404, "No Category with id " + UNKNOWN));
                    ctx.call(as.name(), moveCategory(ctx.row(CATEGORY_ROOT), ctx.row(CATEGORY_CHILD))
                                    .note("a move under its own child"),
                            Cell.status(400, "cannot be moved under itself or under one of its own descendants"));
                }));

        cases.add("changeCategorySortOrder", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/categories/change-sort-order")
                        .json(Map.of("categoryId", ctx.row(CATEGORY_CHILD), "moveUp", true)))
                .sweep((ctx, as) -> {
                    int parent = createCategory(ctx, as, ctx.name("order-" + ctx.unique()), ctx.row(CATEGORY_ROOT), 5);
                    try {
                        int first = createCategory(ctx, as, ctx.name("order-a-" + ctx.unique()), parent, 1);
                        int second = createCategory(ctx, as, ctx.name("order-b-" + ctx.unique()), parent, 2);
                        SweepResponse changed = ctx.ok(as, SweepRequest.put("/rest/categories/change-sort-order")
                                .json(Map.of("categoryId", second, "moveUp", true)));
                        ctx.checkEquals("true", changed.text().trim(), "the answer of the change", changed);
                        double a = ctx.ok("anonymous", getCategory(first)).json().getDouble("siblingSortOrder");
                        SweepResponse read = ctx.ok("anonymous", getCategory(second));
                        ctx.check(read.json().getDouble("siblingSortOrder") < a,
                                "the category moved up sorts before its sibling (" + a + ")", read);
                    } finally {
                        removeCategoryAndReadGone(ctx, as, parent);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/categories/change-sort-order")
                                .json(Map.of("categoryId", UNKNOWN, "moveUp", true)).note("unknown-id probe"),
                        Cell.status(404, "No Category with id " + UNKNOWN))));

        cases.add("recomputeCategoryTreeSortOrder", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/categories/recompute-tree-sort-order"))
                .sweep((ctx, as) -> recomputeKeepsTheOrder(ctx, as, "Category",
                        "/rest/categories/recompute-tree-sort-order", List.of(CATEGORY_ROOT, CATEGORY_CHILD))));

        cases.add("importCategories", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/categories/upload-categories")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String root = ctx.name("uploaded-" + ctx.unique());
                    String child = root + "-child";
                    String json = "[{\"active\":true,\"type\":\"CATEGORY\",\"siblingSortOrder\":" + (LAST + 1)
                            + ",\"descs\":[{\"lang\":\"en\",\"name\":\"" + root + "\"}],\"children\":[{\"active\":true,"
                            + "\"type\":\"CATEGORY\",\"descs\":[{\"lang\":\"en\",\"name\":\"" + child + "\"},"
                            + "{\"lang\":\"da\",\"name\":\"" + child + "-da\"}]}]}]";
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post("/rest/categories/upload-categories"),
                            json);
                    ctx.jobs().awaitClean(ctx, "category-import", file);
                    Integer rootId = categoryId(ctx, root);
                    if (rootId == null) {
                        ctx.fail("The uploaded category " + root + " is not found after its job completed");
                        return;
                    }
                    try {
                        Integer childId = categoryId(ctx, child);
                        ctx.check(childId != null, "the uploaded child " + child + " is found", null);
                        if (childId != null) {
                            SweepResponse read = ctx.ok("anonymous", getCategory(childId).query("parent", true));
                            ctx.checkEquals(rootId, read.json().getInt("parent.id"), "the uploaded child's parent", read);
                            ctx.checkEquals(child + "-da", read.json().getString("descs.find { it.lang == 'da' }.name"),
                                    "the uploaded child's Danish name", read);
                        }
                    } finally {
                        removeCategoryAndReadGone(ctx, as, rootId);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/categories/upload-categories")
                                .file("file", ctx.name("broken") + ".json", "[{\"active\":".getBytes(), "application/json")
                                .note("a broken file"),
                        Cell.status(400, "is not valid JSON"))));
    }

    private static void createCategoryReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Map<String, Object> body = fullCategoryBody(ctx, ctx.name("created-" + ctx.unique()), ctx.row(CATEGORY_ROOT));
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        int id = created.json().getInt("id");
        try {
            readBackCategory(ctx, id, body);
        } finally {
            removeCategoryAndReadGone(ctx, as, id);
        }
    }

    /**
     * A category of the fields the sweep holds: type, MRN, sibling order, parent, names, editor fields, domains, AtoN
     * filter, script resource paths, message id and standard template fields. The template parameters are left out:
     * each names a parameter type, which the reference data does not own.
     */
    private static Map<String, Object> fullCategoryBody(SweepContext ctx, String name, Object parentId) {
        Map<String, Object> body = categoryBody(name, parentId, 5);
        body.put("type", "CATEGORY");
        body.put("mrn", "urn:mrn:sweep:reference-data:" + ctx.unique());
        body.put("editorFields", List.of("title", "categories"));
        body.put("domains", List.of(Map.of("domainId", DOMAIN)));
        body.put("atonFilter", ATON_FILTER);
        body.put("scriptResourcePaths", List.of("templates/sweep-reference-data/created.ftl"));
        body.put("messageId", "sweep-reference-data-message");
        body.put("stdTemplateFields", List.of("title", "details"));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void readBackCategory(SweepContext ctx, int id, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", getCategory(id).query("parent", true));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(id, json.getInt("id"), "the id", read);
        ctx.checkEquals(sent.get("type"), json.getString("type"), "the type", read);
        ctx.checkEquals(sent.get("mrn"), json.getString("mrn"), "the MRN", read);
        ctx.checkEquals(sent.get("active"), json.getBoolean("active"), "active", read);
        ctx.checkEquals(((Number) sent.get("siblingSortOrder")).doubleValue(), json.getDouble("siblingSortOrder"),
                "the sibling sort order", read);
        ctx.checkEquals(((Map<String, Object>) sent.get("parent")).get("id"), json.getInt("parent.id"), "the parent",
                read);
        for (Map<String, Object> desc : (List<Map<String, Object>>) sent.get("descs")) {
            ctx.checkEquals(desc.get("name"), json.getString("descs.find { it.lang == '" + desc.get("lang") + "' }.name"),
                    "the " + desc.get("lang") + " name", read);
        }
        ctx.checkEquals(((List<?>) sent.get("descs")).size(), json.getList("descs").size(), "the number of names", read);
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("editorFields")), new HashSet<>(json.getList("editorFields")),
                "the editor fields", read);
        List<String> domains = ((List<Map<String, Object>>) sent.get("domains")).stream()
                .map(d -> (String) d.get("domainId")).toList();
        List<String> readDomains = json.getList("domains.domainId");
        ctx.checkEquals(domains, readDomains == null ? List.of() : readDomains, "the domains", read);
        ctx.checkEquals(sent.get("atonFilter"), json.getString("atonFilter"), "the AtoN filter", read);
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("scriptResourcePaths")),
                new HashSet<>(listOrEmpty(json.getList("scriptResourcePaths"))), "the script resource paths", read);
        ctx.checkEquals(sent.get("messageId"), json.getString("messageId"), "the message id", read);
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("stdTemplateFields")),
                new HashSet<>(listOrEmpty(json.getList("stdTemplateFields"))), "the standard template fields", read);
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static int createCategory(SweepContext ctx, SweepIdentity as, String name, Object parentId, double order) {
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/categories/category")
                .json(categoryBody(name, parentId, order)));
        if (!created.is2xx()) {
            throw new IllegalStateException("The category " + name + " could not be made: " + created.status());
        }
        return created.json().getInt("id");
    }

    private static void removeCategoryAndReadGone(SweepContext ctx, SweepIdentity as, Object id) {
        SweepResponse removed = ctx.ok(as, deleteCategory(id));
        ctx.checkEquals("true", removed.text().trim(), "the answer of the delete", removed);
        SweepResponse read = ctx.ok("anonymous", getCategory(id).note("read after the delete"));
        ctx.checkEquals(0, read.body().length, "the size of the answer for the deleted category", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Charts
    // ------------------------------------------------------------------------------------------- //

    private void chartCases(SweepCases cases) {
        cases.add("getCharts", SweepCase
                .of((ctx, row) -> chartsByNumber(CHART))
                .variants((ctx, as) -> {
                    // The admin app sends geometry=false; the handler reads no such parameter (the backlog)
                    SweepResponse withoutGeometry = ctx.ok(as, chartsByNumber(CHART).query("geometry", false)
                            .note("effects variant"));
                    ctx.check(withoutGeometry.json().get("[0].geometry") == null,
                            "geometry=false leaves the geometry out", withoutGeometry);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, chartsByNumber(ctx.name("no-such-chart")).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown chart", unknown);
                    ctx.call(as.name(), chartsByNumber(CHART).query("limit", "abc").note("malformed limit"),
                            Cell.status(400, "Failed to convert 'limit' with value: 'abc'"));
                }));

        cases.add("searchCharts", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/charts/search").query("name", CHART))
                .variants((ctx, as) -> {
                    SweepResponse withoutGeometry = ctx.ok(as, SweepRequest.get("/rest/charts/search")
                            .query("name", CHART).query("geometry", false).note("effects variant"));
                    ctx.check(withoutGeometry.json().get("[0].geometry") == null,
                            "geometry=false leaves the geometry out", withoutGeometry);
                    // The chart's name and its international number find it too
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/charts/search").query("name", "Sweep chart")),
                            null, CHART);
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/charts/search").query("name", "900001")), null,
                            CHART);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/charts/search").query("name", CHART)
                                .query("inactive", "maybe").note("malformed inactive"),
                        Cell.status(400, "Failed to convert 'inactive' with value: 'maybe'"))));

        cases.add("getAllCharts", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/charts/all"))
                .variants((ctx, as) -> {
                    SweepResponse limited = ctx.ok(as, SweepRequest.get("/rest/charts/all").query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of charts with limit=1", limited);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/charts/all").query("limit", "abc")
                        .note("malformed limit"), Cell.status(400, "Failed to convert 'limit' with value: 'abc'"))));

        cases.add("computeIntersectingCharts", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/charts/intersecting-charts")
                        .json(featureCollection(box(170.3, -59.7, 170.7, -59.3))))
                .probes((ctx, as) -> {
                    SweepResponse empty = ctx.ok(as, SweepRequest.post("/rest/charts/intersecting-charts")
                            .json(Map.of("type", "FeatureCollection", "features", List.of())).note("no features"));
                    ctx.checkEquals(List.of(), empty.json().getList("$"), "the charts of no geometry", empty);
                }));

        cases.add("createChart", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/charts/chart")
                        .json(chartBody(ctx.name("refused-" + ctx.unique()), "Refused", 900002)))
                .sweep((ctx, as) -> createChartReadBackAndRemove(ctx, as, "/rest/charts/chart"))
                .variants((ctx, as) -> createChartReadBackAndRemove(ctx, as, "/rest/charts/chart/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/charts/chart").json(Map.of()).note("empty body"),
                            Cell.status(400, "A chart needs a chart number"));
                    ctx.call(as.name(), SweepRequest.post("/rest/charts/chart").json(chartBody(CHART, "Again", 900003))
                            .note("a second chart of the number"),
                            Cell.status(400, "Cannot create chart with duplicate chart number " + CHART));
                }));

        cases.add("updateChart", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/charts/chart/{chartNumber}").var("chartNumber", CHART)
                        .json(chartBody(CHART, "Sweep chart", 900001)))
                .sweep((ctx, as) -> {
                    String number = createChart(ctx, as);
                    try {
                        Map<String, Object> changed = chartBody(number, "Sweep chart renamed", 900004);
                        changed.put("active", false);
                        changed.put("scale", 25000);
                        changed.put("horizontalDatum", "ED50");
                        changed.put("geometry", box(170.25, -59.75, 170.75, -59.25));
                        ctx.ok(as, SweepRequest.put("/rest/charts/chart/{chartNumber}").var("chartNumber", number)
                                .json(changed));
                        readBackChart(ctx, changed);
                        // Now inactive: the search finds it only when asked for inactive charts too
                        SweepResponse active = ctx.ok("anonymous", SweepRequest.get("/rest/charts/search")
                                .query("name", number));
                        ctx.check(!listOrEmpty(active.json().getList("chartNumber")).contains(number),
                                "the search without inactive=true leaves out the inactive chart " + number, active);
                        ctx.expectRows(ctx.ok("anonymous", SweepRequest.get("/rest/charts/search").query("name", number)
                                .query("inactive", true)), null, number);
                    } finally {
                        removeChartAndReadGone(ctx, as, number);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/charts/chart/{chartNumber}").var("chartNumber", CHART)
                                    .json(chartBody(CHART + "-other", "Sweep chart", 900001))
                                    .note("the body's number is not the path's"), Cell.status(400, null));
                    String unknown = ctx.name("no-such-chart");
                    ctx.call(as.name(), SweepRequest.put("/rest/charts/chart/{chartNumber}").var("chartNumber", unknown)
                                    .json(chartBody(unknown, "Unknown", 900005)).note("unknown-id probe"),
                            Cell.status(400, "Cannot update non-existing chart " + unknown));
                }));

        cases.add("deleteChart", SweepCase
                // A refused cell asks to remove the fixture's chart; the reads after it would fail if it went
                .of((ctx, row) -> SweepRequest.delete("/rest/charts/chart/{chartNumber}").var("chartNumber", CHART))
                .sweep((ctx, as) -> removeChartAndReadGone(ctx, as, createChart(ctx, as)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/charts/chart/{chartNumber}")
                        .var("chartNumber", ctx.name("no-such-chart")).note("unknown-id probe"), Cell.status(204, null))));

        cases.add("importCharts", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/charts/upload-charts")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String number = ctx.name("uploaded-" + ctx.unique());
                    String json = "[{\"chartNumber\":\"" + number + "\",\"internationalNumber\":900006,\"active\":true,"
                            + "\"scale\":50000,\"name\":\"Uploaded sweep chart\",\"horizontalDatum\":\"WGS84\"}]";
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post("/rest/charts/upload-charts"), json);
                    ctx.jobs().awaitClean(ctx, "chart-import", file);
                    SweepResponse read = ctx.ok("anonymous", chartsByNumber(number));
                    if (read.json().getList("$").isEmpty()) {
                        ctx.fail("The uploaded chart " + number + " is not found after its job completed");
                        return;
                    }
                    try {
                        ctx.checkEquals("Uploaded sweep chart", read.json().getString("[0].name"),
                                "the uploaded chart's name", read);
                        ctx.checkEquals(50000, read.json().getInt("[0].scale"), "the uploaded chart's scale", read);
                        ctx.checkEquals(900006, read.json().getInt("[0].internationalNumber"),
                                "the uploaded chart's international number", read);
                    } finally {
                        removeChartAndReadGone(ctx, as, number);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/charts/upload-charts")
                                .file("file", ctx.name("broken") + ".json", "[{\"active\":".getBytes(), "application/json")
                                .note("a broken file"),
                        Cell.status(400, "is not valid JSON"))));
    }

    private static void createChartReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String number = ctx.name("created-" + ctx.unique());
        Map<String, Object> body = chartBody(number, "Created sweep chart", 900007);
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        try {
            readBackChart(ctx, body);
        } finally {
            removeChartAndReadGone(ctx, as, number);
        }
    }

    private static String createChart(SweepContext ctx, SweepIdentity as) {
        String number = ctx.name("chart-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/charts/chart")
                .json(chartBody(number, "Sweep chart of a case", 900008)));
        if (!created.is2xx()) {
            throw new IllegalStateException("The chart " + number + " could not be made: " + created.status());
        }
        return number;
    }

    /** Reads the chart back by its number (inactive ones are found by number too) and checks every field sent */
    private static void readBackChart(SweepContext ctx, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", chartsByNumber((String) sent.get("chartNumber")));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(1, json.getList("$").size(), "the number of charts of the number", read);
        ctx.checkEquals(sent.get("chartNumber"), json.getString("[0].chartNumber"), "the chart number", read);
        ctx.checkEquals(sent.get("internationalNumber"), json.getInt("[0].internationalNumber"),
                "the international number", read);
        ctx.checkEquals(sent.get("active"), json.getBoolean("[0].active"), "active", read);
        ctx.checkEquals(sent.get("scale"), json.getInt("[0].scale"), "the scale", read);
        ctx.checkEquals(sent.get("name"), json.getString("[0].name"), "the name", read);
        ctx.checkEquals(sent.get("horizontalDatum"), json.getString("[0].horizontalDatum"), "the horizontal datum", read);
        ctx.checkEquals(ring(sent.get("geometry")), ring(json.get("[0].geometry")), "the geometry", read);
    }

    private static void removeChartAndReadGone(SweepContext ctx, SweepIdentity as, String number) {
        ctx.ok(as, SweepRequest.delete("/rest/charts/chart/{chartNumber}").var("chartNumber", number));
        SweepResponse read = ctx.ok("anonymous", chartsByNumber(number).note("read after the delete"));
        ctx.checkEquals(List.of(), read.json().getList("$"), "the charts of the deleted number", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Dictionaries
    // ------------------------------------------------------------------------------------------- //

    private void dictionaryCases(SweepCases cases) {
        cases.add("getDictionaryNames", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/dictionaries/names"))
                .sweep((ctx, as) -> {
                    SweepResponse names = ctx.ok(as, SweepRequest.get("/rest/dictionaries/names"));
                    ctx.check(names.json().getList("$").contains(DICTIONARY), "the names hold " + DICTIONARY, names);
                }));

        cases.add("getDictionary", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/dictionaries/dictionary/{name:[^.]+}").var("name", DICTIONARY))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get("/rest/dictionaries/dictionary/{name:[^.]+}")
                            .var("name", ctx.name("no-such-dictionary")).note("unknown-name probe"));
                    ctx.checkEquals(0, unknown.body().length, "the size of the answer for an unknown dictionary",
                            unknown);
                }));

        cases.add("getDictionaryEntries", SweepCase
                .of((ctx, row) -> entries(DICTIONARY))
                .variants((ctx, as) -> {
                    SweepResponse danish = ctx.ok(as, entries(DICTIONARY).query("lang", "da"));
                    ctx.checkEquals("da", danish.json().getString("find { it.key == '" + DICTIONARY_KEY
                            + "' }.descs[0].lang"), "lang=da puts the Danish text first", danish);
                })
                .probes((ctx, as) -> {
                    String unknown = ctx.name("no-such-dictionary");
                    ctx.call(as.name(), entries(unknown).note("unknown-name probe"),
                            Cell.status(404, "No such dictionary: " + unknown));
                }));

        cases.add("getDictionaryEntriesAsPropertyFile", SweepCase
                .of((ctx, row) -> properties(DICTIONARY, "en"))
                .variants((ctx, as) -> {
                    SweepResponse danish = ctx.ok(as, properties(DICTIONARY, "da"));
                    ctx.check(danish.text().contains(DICTIONARY_KEY + " = Sweep tekst"),
                            "the Danish property file holds the key's Danish text", danish);
                })
                .probes((ctx, as) -> ctx.call(as.name(), properties(ctx.name("nodictionary"), "en")
                        .note("unknown-name probe"), Cell.status(404, null))));

        cases.add("exportDictionary", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/dictionaries/dictionary/{names}.json").var("names", DICTIONARY))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get("/rest/dictionaries/dictionary/{names}.json")
                            .var("names", ctx.name("nodictionary")).note("unknown-name probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the export of an unknown dictionary", unknown);
                }));

        cases.add("getKeyUsers", SweepCase
                .of((ctx, row) -> keyUsers(DICTIONARY_KEY))
                .sweep((ctx, as) -> {
                    // The sweep's key: no template or script names it, no Java code reads it, no parameter type lists it
                    SweepResponse users = ctx.ok(as, keyUsers(DICTIONARY_KEY));
                    for (String list : List.of("templates", "readers", "paramTypes")) {
                        ctx.checkEquals(List.of(), users.json().getList(list), "the key's " + list, users);
                    }
                    // A key the Java code reads (RequiredTexts): the answer names its reader
                    SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/dictionaries/dictionary/{name}/key-users")
                            .var("name", "message").query("key", "msg.cancellation.title"));
                    ctx.check(listOrEmpty(read.json().getList("readers")).contains("MESSAGE_CANCELLATION"),
                            "the readers of msg.cancellation.title name MESSAGE_CANCELLATION", read);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/dictionaries/dictionary/{name}/key-users")
                        .var("name", DICTIONARY).note("no key"), Cell.status(400, "Required parameter 'key' is not present"))));

        cases.add("previewDictionaryImport", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/dictionaries/import-preview")
                        .file("file", ctx.name("preview") + ".json", dictionaryFile(DICTIONARY_KEY, "Sweep text changed"),
                                "application/json"))
                .sweep((ctx, as) -> {
                    // The fixture's key with another text: the preview lists it among the keys it would overwrite
                    SweepResponse preview = ctx.ok(as, SweepRequest.post("/rest/dictionaries/import-preview")
                            .file("file", ctx.name("preview") + ".json",
                                    dictionaryFile(DICTIONARY_KEY, "Sweep text changed"), "application/json"));
                    ctx.expectRows(preview, null, DICTIONARY_KEY);
                    SweepResponse text = ctx.ok("anonymous", entries(DICTIONARY));
                    ctx.checkEquals("Sweep text", text.json().getString("find { it.key == '" + DICTIONARY_KEY
                            + "' }.descs.find { it.lang == 'en' }.value"), "the key's text after the preview", text);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/import-preview")
                                    .file("file", ctx.name("broken") + ".json", "[{\"name\":".getBytes(),
                                            "application/json").note("a broken file"),
                            Cell.status(400, "is not a list of dictionaries"));
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/import-preview")
                                    .file("file", ctx.name("one") + ".json", "[]".getBytes(), "application/json")
                                    .file("other", ctx.name("two") + ".json", "[]".getBytes(), "application/json")
                                    .note("two files"),
                            Cell.status(400, "Upload one dictionary file; the request has 2"));
                }));

        cases.add("matchesAton", SweepCase
                .of((ctx, row) -> matchesAton("light_minor"))
                .probes((ctx, as) -> {
                    // A buoy matches no light filter: the answer is empty (DR2: 204, not 200 with an empty body)
                    SweepResponse none = ctx.ok(as, matchesAton("buoy_lateral").note("no match"));
                    ctx.checkEquals(0, none.body().length, "the size of the answer when no filter matches", none);
                    ctx.call(as.name(), SweepRequest.put("/rest/dictionaries/matches-aton")
                                    .json(Map.of("values", List.of())).note("no AtoN"),
                            Cell.status(400, "No proper message specified"));
                }));

        cases.add("createDictionaryEntry", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", DICTIONARY)
                        .json(entryBody(ctx.name("refused-" + ctx.unique()), "Refused", "Afvist", null)))
                .sweep((ctx, as) -> {
                    String key = ctx.name("created-" + ctx.unique());
                    Map<String, Object> body = entryBody(key, "Created text", "Oprettet tekst", ATON_FILTER);
                    SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/dictionaries/dictionary/{name}")
                            .var("name", DICTIONARY).json(body));
                    if (!created.is2xx()) {
                        return;
                    }
                    try {
                        readBackEntry(ctx, body);
                    } finally {
                        removeEntryAndReadGone(ctx, as, key);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", DICTIONARY)
                                    .json(Map.of("descs", List.of(Map.of("lang", "en", "value", "No key"))))
                                    .note("no key"),
                            Cell.status(400, "A dictionary entry needs a key"));
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", DICTIONARY)
                                    .json(entryBody(DICTIONARY_KEY, "Again", "Igen", null)).note("a key that exists"),
                            Cell.status(400, "Entry already exists: " + DICTIONARY_KEY));
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", DICTIONARY)
                                    .json(Map.of("key", ctx.name("novalue"), "descs", List.of())).note("no text"),
                            Cell.status(400, "No localized values defined for entry"));
                    String unknown = ctx.name("nodictionary");
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/dictionary/{name}").var("name", unknown)
                                    .json(entryBody(ctx.name("key"), "Text", "Tekst", null)).note("unknown dictionary"),
                            Cell.status(400, "Non-existing dictionary: " + unknown));
                }));

        cases.add("updateDictionaryEntry", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/dictionaries/dictionary/{name}/{key}").var("name", DICTIONARY)
                        .var("key", DICTIONARY_KEY).json(entryBody(DICTIONARY_KEY, "Sweep text", "Sweep tekst",
                                ATON_FILTER)))
                .sweep((ctx, as) -> {
                    String key = createEntry(ctx, as);
                    try {
                        Map<String, Object> changed = entryBody(key, "Updated text", "Opdateret tekst",
                                "aton.kv('seamark:type', 'buoy_lateral')");
                        ctx.ok(as, SweepRequest.put("/rest/dictionaries/dictionary/{name}/{key}").var("name", DICTIONARY)
                                .var("key", key).json(changed));
                        readBackEntry(ctx, changed);
                    } finally {
                        removeEntryAndReadGone(ctx, as, key);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/dictionaries/dictionary/{name}/{key}")
                                    .var("name", DICTIONARY).var("key", DICTIONARY_KEY)
                                    .json(entryBody(DICTIONARY_KEY + "-other", "Text", "Tekst", null))
                                    .note("the body's key is not the path's"), Cell.status(400, null));
                    String unknown = ctx.name("no-such-key");
                    ctx.call(as.name(), SweepRequest.put("/rest/dictionaries/dictionary/{name}/{key}")
                                    .var("name", DICTIONARY).var("key", unknown)
                                    .json(entryBody(unknown, "Text", "Tekst", null)).note("unknown-key probe"),
                            Cell.status(400, "Non-existing dictionary entry: " + unknown));
                }));

        cases.add("deleteDictionaryEntry", SweepCase
                .of((ctx, row) -> deleteEntry(DICTIONARY_KEY))
                .sweep((ctx, as) -> removeEntryAndReadGone(ctx, as, createEntry(ctx, as)))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, deleteEntry(ctx.name("no-such-key")).note("unknown-key probe"));
                    ctx.checkEquals("false", unknown.text().trim(), "the answer for an unknown key", unknown);
                }));

        cases.add("importDictionaries", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/dictionaries/upload-dictionaries")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String key = ctx.name("uploaded-" + ctx.unique());
                    String json = new String(dictionaryFile(key, "Uploaded text"), StandardCharsets.UTF_8);
                    String file = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/dictionaries/upload-dictionaries"), json);
                    ctx.jobs().awaitClean(ctx, "dictionary-import", file);
                    SweepResponse read = ctx.ok("anonymous", entries(DICTIONARY));
                    if (read.json().get("find { it.key == '" + key + "' }") == null) {
                        ctx.fail("The uploaded key " + key + " is not found after its job completed");
                        return;
                    }
                    try {
                        ctx.checkEquals("Uploaded text", read.json().getString("find { it.key == '" + key
                                + "' }.descs.find { it.lang == 'en' }.value"), "the uploaded key's English text", read);
                    } finally {
                        removeEntryAndReadGone(ctx, as, key);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/dictionaries/upload-dictionaries")
                                    .file("file", ctx.name("broken") + ".json", "[{\"name\":".getBytes(),
                                            "application/json")
                                    .note("a broken file"),
                            Cell.status(400, "is not valid JSON"));
                    // The sysadmin's ticks (form fields) are checked before any job starts: each refusal starts none
                    ctx.call(as.name(), refusedTicks(ctx).field("add", "not a list").note("add is no JSON array"),
                            Cell.status(400, "The field 'add' is not a JSON array of dictionary keys"));
                    ctx.call(as.name(), refusedTicks(ctx).field("overwrite", "[{\"dictionary\":\"web\",\"key\":7}]")
                                    .note("an overwrite tick whose key is no text"),
                            Cell.status(400, "The field 'overwrite' holds"));
                    ctx.call(as.name(), refusedTicks(ctx).field("add", "[]").field("add", "[]")
                                    .note("the add field twice"),
                            Cell.status(400, "The field 'add' is given more than once"));
                }));
    }

    /** A dictionary upload of a well-formed empty file, to which a probe adds ticks the handler must refuse */
    private static SweepRequest refusedTicks(SweepContext ctx) {
        return SweepRequest.post("/rest/dictionaries/upload-dictionaries")
                .file("file", ctx.name("ticks") + ".json", "[]".getBytes(), "application/json");
    }

    /** Reads the entry back from the dictionary's entries and checks every field sent */
    @SuppressWarnings("unchecked")
    private static void readBackEntry(SweepContext ctx, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", entries(DICTIONARY));
        if (!read.is2xx()) {
            return;
        }
        String at = "find { it.key == '" + sent.get("key") + "' }";
        JsonPath json = read.json();
        ctx.check(json.get(at) != null, "the dictionary holds the key " + sent.get("key"), read);
        for (Map<String, Object> desc : (List<Map<String, Object>>) sent.get("descs")) {
            ctx.checkEquals(desc.get("value"), json.getString(at + ".descs.find { it.lang == '" + desc.get("lang")
                    + "' }.value"), "the " + desc.get("lang") + " text", read);
        }
        ctx.checkEquals(((List<?>) sent.get("descs")).size(), json.getList(at + ".descs").size(),
                "the number of texts", read);
        ctx.checkEquals(sent.get("atonFilter"), json.getString(at + ".atonFilter"), "the AtoN filter", read);
    }

    private static String createEntry(SweepContext ctx, SweepIdentity as) {
        String key = ctx.name("entry-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/dictionaries/dictionary/{name}")
                .var("name", DICTIONARY).json(entryBody(key, "Text", "Tekst", null)));
        if (!created.is2xx()) {
            throw new IllegalStateException("The key " + key + " could not be made: " + created.status());
        }
        return key;
    }

    private static void removeEntryAndReadGone(SweepContext ctx, SweepIdentity as, String key) {
        SweepResponse removed = ctx.ok(as, deleteEntry(key));
        ctx.checkEquals("true", removed.text().trim(), "the answer of the delete", removed);
        SweepResponse read = ctx.ok("anonymous", entries(DICTIONARY).note("read after the delete"));
        ctx.check(read.json().get("find { it.key == '" + key + "' }") == null,
                "the dictionary no longer holds the deleted key " + key, read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Domains
    // ------------------------------------------------------------------------------------------- //

    private void domainCases(SweepCases cases) {
        cases.add("getAllDomains", SweepCase
                // The cells ask without the Keycloak state, which the suite's Keycloak cannot give (no admin API)
                .of((ctx, row) -> allDomains())
                .sweep((ctx, as) -> {
                    // As the sign-in page and the public site ask: anonymous, with the Keycloak state (DOMAIN1)
                    SweepResponse all = ctx.ok(as, SweepRequest.get("/rest/domains/all"));
                    ctx.expectRows(all, "domainId", DOMAIN);
                    ctx.checkEquals("Sweep domain", all.json().getString("find { it.domainId == '" + DOMAIN + "' }.name"),
                            "the fixture domain's name", all);
                })
                .variants((ctx, as) -> {
                    // A signed-in sysadmin with a Keycloak that holds the domain's client
                    try (KeycloakAdminStub keycloak = KeycloakAdminStub.start().withClient(DOMAIN)) {
                        withKeycloak(ctx, keycloak.baseUrl(), () -> {
                            SweepResponse states = ctx.ok("sysadmin", SweepRequest.get("/rest/domains/all"));
                            ctx.checkEquals(true, states.json().get("find { it.domainId == '" + DOMAIN + "' }.inKeycloak"),
                                    "the fixture domain is in Keycloak", states);
                            ctx.checkEquals(false, states.json().get("find { it.domainId == 'niord-nw' }.inKeycloak"),
                                    "niord-nw is not in this Keycloak", states);
                            return null;
                        });
                    }
                    SweepResponse withoutState = ctx.ok(as, allDomains());
                    ctx.check(withoutState.json().get("find { it.domainId == '" + DOMAIN + "' }.inKeycloak") == null,
                            "keycloakState=false leaves the Keycloak state out", withoutState);
                })
                .probes((ctx, as) -> ctx.call(as.name(), allDomains().query("inactive", "maybe")
                        .note("malformed inactive"), Cell.status(400, "Failed to convert 'inactive' with value: 'maybe'"))));

        cases.add("createDomain", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/domains/domain")
                        .json(domainBody(ctx, ctx.name("refused-" + ctx.unique()), "Refused")))
                .sweep((ctx, as) -> createDomainReadBackAndRemove(ctx, as, "/rest/domains/domain"))
                .variants((ctx, as) -> createDomainReadBackAndRemove(ctx, as, "/rest/domains/domain/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/domains/domain")
                                    .json(domainBody(ctx, DOMAIN, "Again")).note("a domain id that exists"),
                            Cell.status(400, "Cannot create domain with duplicate domain ID " + DOMAIN));
                    Map<String, Object> zone = domainBody(ctx, ctx.name("zone-" + ctx.unique()), "Zone");
                    zone.put("timeZone", "Mars/Base");
                    ctx.call(as.name(), SweepRequest.post("/rest/domains/domain").json(zone).note("an unknown time zone"),
                            Cell.status(400, "Unknown time zone 'Mars/Base'"));
                    // A create without a domain id or a name is refused before Keycloak is asked for the
                    // domain's client (DOMAIN4, fixed): a create that got through would create one at the stub,
                    // not at authServerUrl, and the stub is read to hold no request
                    try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
                        Map<String, Object> nameless = domainBody(ctx, ctx.name("nameless-" + ctx.unique()), "x");
                        nameless.remove("name");
                        SweepResponse refused = withKeycloak(ctx, keycloak.baseUrl(), () -> {
                            ctx.call(as.name(), SweepRequest.post("/rest/domains/domain").json(Map.of())
                                    .note("empty body"), Cell.status(400, "A domain needs a domain id"));
                            return ctx.call(as.name(), SweepRequest.post("/rest/domains/domain").json(nameless)
                                    .note("a domain id and no name"), Cell.status(400, "A domain needs a name"));
                        });
                        ctx.checkEquals(0, keycloak.http().requestCount(),
                                "the Keycloak requests of the refused creates", refused);
                    }
                }));

        cases.add("createDomainInKeycloak", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/domains/keycloak")
                        .json(Map.of("domainId", ctx.name("refused-" + ctx.unique()), "name", "Refused")))
                .sweep((ctx, as) -> {
                    String domainId = ctx.name("keycloak-" + ctx.unique());
                    try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
                        withKeycloak(ctx, keycloak.baseUrl(), () -> ctx.ok(as, SweepRequest.post("/rest/domains/keycloak")
                                .json(Map.of("domainId", domainId, "name", "Sweep Keycloak domain"))));
                        String uuid = keycloak.clientUuid(domainId);
                        ctx.check(uuid != null, "the Keycloak stub holds the client " + domainId, null);
                        List<String> roles = keycloak.http().requests().stream()
                                .filter(r -> r.method().equals("POST") && r.path().endsWith("/clients/" + uuid + "/roles"))
                                .map(HttpStub.RecordedRequest::bodyString)
                                .toList();
                        ctx.checkEquals(4, roles.size(), "the number of roles made for the client", null);
                    }
                    // Nothing of it is stored in Niord
                    ctx.check(domain(ctx, domainId) == null, "Niord stores no domain " + domainId, null);
                }));

        cases.add("updateDomain", SweepCase
                // A refused cell sends the fixture's domain as it is
                .of((ctx, row) -> SweepRequest.put("/rest/domains/domain/{domainId}").var("domainId", DOMAIN)
                        .json(domainBody(ctx, DOMAIN, "Sweep domain")))
                .sweep((ctx, as) -> {
                    String domainId = createDomain(ctx, as);
                    try {
                        Map<String, Object> changed = fullDomainBody(ctx, domainId);
                        changed.put("name", "Sweep domain renamed");
                        changed.put("active", false);
                        changed.put("timeZone", "UTC");
                        changed.put("color", "#123456");
                        changed.put("areas", List.of());
                        changed.put("categories", List.of(Map.of("id", ctx.row(CATEGORY_CHILD))));
                        ctx.ok(as, SweepRequest.put("/rest/domains/domain/{domainId}").var("domainId", domainId)
                                .json(changed));
                        readBackDomain(ctx, changed);
                        // Now inactive: the list leaves it out unless asked for inactive domains too
                        SweepResponse active = ctx.ok("anonymous", allDomains());
                        ctx.check(active.json().get("find { it.domainId == '" + domainId + "' }") == null,
                                "the domains without inactive=true leave out the inactive " + domainId, active);
                    } finally {
                        removeDomainAndReadGone(ctx, as, domainId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/domains/domain/{domainId}").var("domainId", DOMAIN)
                                    .json(domainBody(ctx, DOMAIN + "-other", "Sweep domain"))
                                    .note("the body's domain id is not the path's"), Cell.status(400, null));
                    String unknown = ctx.name("no-such-domain");
                    ctx.call(as.name(), SweepRequest.put("/rest/domains/domain/{domainId}").var("domainId", unknown)
                                    .json(domainBody(ctx, unknown, "Unknown")).note("unknown-id probe"),
                            Cell.status(400, "Cannot update non-existing domain " + unknown));
                    // DOMAIN2: an unknown area is dropped without a word; the domain's own state is sent again
                    Map<String, Object> unknownArea = domainBody(ctx, DOMAIN, "Sweep domain");
                    unknownArea.put("areas", List.of(Map.of("id", ctx.row(AREA_CHILD)), Map.of("id", UNKNOWN)));
                    ctx.call(as.name(), SweepRequest.put("/rest/domains/domain/{domainId}").var("domainId", DOMAIN)
                                    .json(unknownArea).note("an unknown area"),
                            Cell.status(400, String.valueOf(UNKNOWN)));
                }));

        cases.add("deleteDomain", SweepCase
                // A refused cell asks to remove the fixture's domain; the reads after it would fail if it went
                .of((ctx, row) -> SweepRequest.delete("/rest/domains/domain/{domainId}").var("domainId", DOMAIN))
                .sweep((ctx, as) -> removeDomainAndReadGone(ctx, as, createDomain(ctx, as)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/domains/domain/{domainId}")
                        .var("domainId", ctx.name("no-such-domain")).note("unknown-id probe"), Cell.status(204, null))));

        cases.add("importDomains", SweepCase
                // Global: only the refused cells run; a file of no domains, which an import that got through ignores
                .of((ctx, row) -> SweepRequest.post("/rest/domains/upload-domains")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json")));
    }

    private static void createDomainReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern)
            throws Exception {
        String domainId = ctx.name("created-" + ctx.unique());
        Map<String, Object> body = fullDomainBody(ctx, domainId);
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
            SweepResponse created = withKeycloak(ctx, keycloak.baseUrl(),
                    () -> ctx.ok(as, SweepRequest.post(pattern).json(body)));
            if (!created.is2xx()) {
                return;
            }
            ctx.check(keycloak.clientUuid(domainId) != null, "the create made the Keycloak client " + domainId, created);
        }
        try {
            readBackDomain(ctx, body);
        } finally {
            removeDomainAndReadGone(ctx, as, domainId);
        }
    }

    /** A domain of every field the form sends, with the fixture's area child, category child and series */
    private static Map<String, Object> fullDomainBody(SweepContext ctx, String domainId) {
        Map<String, Object> body = domainBody(ctx, domainId, "Sweep domain of a case");
        body.put("sortOrder", 900);
        body.put("timeZone", "Europe/Copenhagen");
        body.put("lat", -59.5);
        body.put("lon", 170.5);
        body.put("zoomLevel", 6);
        body.put("messageSortOrder", "ID ASC");
        body.put("color", "#abcdef");
        body.put("publish", false);
        body.put("atons", true);
        body.put("templates", false);
        body.put("messageSeries", List.of(Map.of("seriesId", SERIES)));
        return body;
    }

    /** Reads the domain back (inactive ones included, without the Keycloak state) and checks every field sent */
    @SuppressWarnings("unchecked")
    private static void readBackDomain(SweepContext ctx, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", allDomains().query("inactive", true));
        if (!read.is2xx()) {
            return;
        }
        String at = "find { it.domainId == '" + sent.get("domainId") + "' }";
        JsonPath json = read.json();
        if (json.get(at) == null) {
            ctx.fail(read, "the domains hold " + sent.get("domainId"));
            return;
        }
        for (String field : List.of("name", "active", "sortOrder", "timeZone", "zoomLevel", "messageSortOrder", "color",
                "publish", "atons", "templates")) {
            ctx.checkEquals(sent.get(field), json.get(at + "." + field), "the domain's " + field, read);
        }
        for (String field : List.of("lat", "lon")) {
            ctx.checkEquals(((Number) sent.get(field)).floatValue(), ((Number) json.get(at + "." + field)).floatValue(),
                    "the domain's " + field, read);
        }
        ctx.checkEquals(ids(sent.get("areas"), "id"), ids(json.get(at + ".areas"), "id"), "the domain's areas", read);
        ctx.checkEquals(ids(sent.get("categories"), "id"), ids(json.get(at + ".categories"), "id"),
                "the domain's categories", read);
        ctx.checkEquals(ids(sent.get("messageSeries"), "seriesId"), ids(json.get(at + ".messageSeries"), "seriesId"),
                "the domain's message series", read);
    }

    /** Creates a domain of every field as the identity, with the Keycloak integration at a stub; answers its id */
    private static String createDomain(SweepContext ctx, SweepIdentity as) throws Exception {
        String domainId = ctx.name("domain-" + ctx.unique());
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
            SweepResponse created = withKeycloak(ctx, keycloak.baseUrl(), () -> ctx.ok(as,
                    SweepRequest.post("/rest/domains/domain").json(fullDomainBody(ctx, domainId))));
            if (!created.is2xx()) {
                throw new IllegalStateException("The domain " + domainId + " could not be made: " + created.status());
            }
        }
        return domainId;
    }

    private static void removeDomainAndReadGone(SweepContext ctx, SweepIdentity as, String domainId) {
        ctx.ok(as, SweepRequest.delete("/rest/domains/domain/{domainId}").var("domainId", domainId));
        SweepResponse read = ctx.ok("anonymous", allDomains().query("inactive", true).note("read after the delete"));
        ctx.check(read.json().get("find { it.domainId == '" + domainId + "' }") == null,
                "the domains no longer hold the deleted " + domainId, read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Message series
    // ------------------------------------------------------------------------------------------- //

    private void seriesCases(SweepCases cases) {
        cases.add("getAllMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-series/all"))
                .variants((ctx, as) -> {
                    // The next number of this year: the fixture set a number for 2099 only
                    SweepResponse numbers = ctx.ok(as, SweepRequest.get("/rest/message-series/all")
                            .query("messageNumbers", true));
                    ctx.checkEquals(1, numbers.json().get("find { it.seriesId == '" + SERIES + "' }.nextMessageNumber"),
                            "the fixture series' next number this year", numbers);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/message-series/all")
                                .query("messageNumbers", "maybe").note("malformed messageNumbers"),
                        Cell.status(400, "Failed to convert 'messageNumbers' with value: 'maybe'"))));

        cases.add("getMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-series/search/{seriesIds}").var("seriesIds", SERIES))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get("/rest/message-series/search/{seriesIds}")
                            .var("seriesIds", ctx.name("no-such-series")).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown series", unknown);
                    ctx.call(as.name(), SweepRequest.get("/rest/message-series/search/{seriesIds}").var("seriesIds", SERIES)
                            .query("limit", "abc").note("malformed limit"),
                            Cell.status(400, "Failed to convert 'limit' with value: 'abc'"));
                }));

        cases.add("searchMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-series/search").query("name", SERIES))
                .variants((ctx, as) -> {
                    // The series is in no domain
                    SweepResponse inDomain = ctx.ok(as, SweepRequest.get("/rest/message-series/search")
                            .query("name", SERIES).query("domain", "niord-nw"));
                    ctx.checkEquals(List.of(), inDomain.json().getList("$"), "the fixture series in niord-nw", inDomain);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/message-series/search")
                        .query("name", SERIES).query("limit", "abc").note("malformed limit"),
                        Cell.status(400, "Failed to convert 'limit' with value: 'abc'"))));

        cases.add("getNextMessageSeriesNumber", SweepCase
                .of((ctx, row) -> number(SERIES, NUMBER_YEAR))
                .sweep((ctx, as) -> {
                    SweepResponse number = ctx.ok(as, number(SERIES, NUMBER_YEAR));
                    ctx.checkEquals(String.valueOf(NUMBER), number.text().trim(), "the next number the fixture set",
                            number);
                })
                .probes((ctx, as) -> {
                    String unknown = ctx.name("no-such-series");
                    ctx.call(as.name(), number(unknown, NUMBER_YEAR).note("unknown-id probe"),
                            Cell.status(400, "Invalid message series " + unknown));
                    ctx.call(as.name(), number(SERIES, 1500).note("a year out of range"),
                            Cell.status(400, "Invalid year 1500"));
                    ctx.call(as.name(), number(SERIES, NUMBER_YEAR).var("year", "abc").note("malformed year"),
                            Cell.status(400, "Failed to convert 'year' with value: 'abc'"));
                }));

        cases.add("computeNextMessageSeriesNumber", SweepCase
                .of((ctx, row) -> computeNumber(SERIES, Year.now().getValue()))
                .sweep((ctx, as) -> {
                    // No message is in the fixture's series
                    SweepResponse number = ctx.ok(as, computeNumber(SERIES, Year.now().getValue()));
                    ctx.checkEquals("1", number.text().trim(), "the computed next number of a series of no message",
                            number);
                })
                .probes((ctx, as) -> {
                    // SERIES3: a year beyond PostgreSQL's range answered 500
                    ctx.call(as.name(), computeNumber(SERIES, 300000).note("a year beyond PostgreSQL's range"),
                            Cell.status(400, "Invalid year 300000"));
                    String unknown = ctx.name("no-such-series");
                    ctx.call(as.name(), computeNumber(unknown, 2026).note("unknown-id probe"),
                            Cell.status(400, "Invalid message series " + unknown));
                }));

        cases.add("setNextMessageSeriesNumber", SweepCase
                // A refused cell sets the number the fixture set
                .of((ctx, row) -> setNumber(SERIES, NUMBER_YEAR).json(NUMBER))
                .sweep((ctx, as) -> {
                    SweepResponse set = ctx.ok(as, SweepRequest.put("/rest/message-series/series/{seriesIds}/number/{year}")
                            .var("seriesIds", SERIES).var("year", WRITE_YEAR).json(11));
                    ctx.checkEquals("11", set.text().trim(), "the answer of the write", set);
                    SweepResponse read = ctx.ok("anonymous", number(SERIES, WRITE_YEAR));
                    ctx.checkEquals("11", read.text().trim(), "the next number read back", read);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), setNumber(SERIES, WRITE_YEAR).note("no number"),
                            Cell.status(400, "The next number is missing"));
                    ctx.call(as.name(), setNumber(SERIES, WRITE_YEAR).json(-1).note("a negative number"),
                            Cell.status(400, "The next number must be 0 or more, was -1"));
                    String unknown = ctx.name("no-such-series");
                    ctx.call(as.name(), setNumber(unknown, WRITE_YEAR).json(3).note("unknown-id probe"),
                            Cell.status(400, "Invalid message series " + unknown));
                    ctx.call(as.name(), setNumber(SERIES, 2200).json(3).note("a year out of range"),
                            Cell.status(400, "Invalid year 2200"));
                }));

        cases.add("createMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-series/series")
                        .json(seriesBody(ctx.name("refused-" + ctx.unique()))))
                .sweep((ctx, as) -> createSeriesReadBackAndRemove(ctx, as, "/rest/message-series/series"))
                .variants((ctx, as) -> createSeriesReadBackAndRemove(ctx, as, "/rest/message-series/series/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/message-series/series").json(Map.of()).note("empty body"),
                            Cell.status(400, "A message series needs a series id"));
                    String id = ctx.name("no-type-" + ctx.unique());
                    ctx.call(as.name(), SweepRequest.post("/rest/message-series/series")
                                    .json(Map.of("seriesId", id, "numberSequenceType", "YEARLY")).note("no main type"),
                            Cell.status(400, "Message series " + id + " needs a main type (NW or NM)"));
                    ctx.call(as.name(), SweepRequest.post("/rest/message-series/series").json(seriesBody(SERIES))
                                    .note("a series id that exists"),
                            Cell.status(400, "Cannot create message series with duplicate message series IDs"));
                }));

        cases.add("updateMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/message-series/series/{seriesId}").var("seriesId", SERIES)
                        .json(seriesBody(SERIES)))
                .sweep((ctx, as) -> {
                    String seriesId = createSeries(ctx, as);
                    try {
                        Map<String, Object> changed = seriesBody(seriesId);
                        changed.put("types", List.of("SUBAREA_WARNING"));
                        changed.put("shortFormat", "SWU-${number-3-digits}");
                        changed.put("navtexFormat", "SWU ${number-3-digits}");
                        changed.put("numberSequenceType", "CONTINUOUS");
                        changed.put("editorFields", List.of("charts"));
                        changed.put("excludeFromMessageIndex", false);
                        ctx.ok(as, SweepRequest.put("/rest/message-series/series/{seriesId}").var("seriesId", seriesId)
                                .json(changed));
                        readBackSeries(ctx, changed);
                    } finally {
                        removeSeriesAndReadGone(ctx, as, seriesId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/message-series/series/{seriesId}").var("seriesId", SERIES)
                                    .json(seriesBody(SERIES + "-other")).note("the body's series id is not the path's"),
                            Cell.status(400, null));
                    String unknown = ctx.name("no-such-series");
                    ctx.call(as.name(), SweepRequest.put("/rest/message-series/series/{seriesId}").var("seriesId", unknown)
                                    .json(seriesBody(unknown)).note("unknown-id probe"),
                            // The detail names the id; not its run-together wording, a recorded fault (SERIES4)
                            Cell.status(400, unknown));
                }));

        cases.add("deleteMessageSeries", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/message-series/series/{seriesId}").var("seriesId", SERIES))
                .sweep((ctx, as) -> removeSeriesAndReadGone(ctx, as, createSeries(ctx, as)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/message-series/series/{seriesId}")
                        .var("seriesId", ctx.name("no-such-series")).note("unknown-id probe"), Cell.status(204, null))));
    }

    private static void createSeriesReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        // A new id each time: a series created again under a deleted id continues its number sequence (SERIES7)
        String seriesId = ctx.name("created-" + ctx.unique());
        Map<String, Object> body = seriesBody(seriesId);
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        try {
            readBackSeries(ctx, body);
        } finally {
            removeSeriesAndReadGone(ctx, as, seriesId);
        }
    }

    @SuppressWarnings("unchecked")
    private static void readBackSeries(SweepContext ctx, Map<String, Object> sent) {
        SweepResponse read = ctx.ok("anonymous", SweepRequest.get("/rest/message-series/search/{seriesIds}")
                .var("seriesIds", sent.get("seriesId")));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(1, json.getList("$").size(), "the number of series of the id", read);
        for (String field : List.of("seriesId", "mainType", "shortFormat", "navtexFormat", "numberSequenceType",
                "excludeFromMessageIndex")) {
            ctx.checkEquals(sent.get(field), json.get("[0]." + field), "the series' " + field, read);
        }
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("types")), new HashSet<>(json.getList("[0].types")),
                "the series' types", read);
        ctx.checkEquals(new HashSet<>((List<String>) sent.get("editorFields")),
                new HashSet<>(json.getList("[0].editorFields")), "the series' editor fields", read);
    }

    private static String createSeries(SweepContext ctx, SweepIdentity as) {
        String seriesId = ctx.name("series-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/message-series/series").json(seriesBody(seriesId)));
        if (!created.is2xx()) {
            throw new IllegalStateException("The series " + seriesId + " could not be made: " + created.status());
        }
        return seriesId;
    }

    private static void removeSeriesAndReadGone(SweepContext ctx, SweepIdentity as, String seriesId) {
        ctx.ok(as, SweepRequest.delete("/rest/message-series/series/{seriesId}").var("seriesId", seriesId));
        SweepResponse read = ctx.ok("anonymous", SweepRequest.get("/rest/message-series/search/{seriesIds}")
                .var("seriesIds", seriesId).note("read after the delete"));
        ctx.checkEquals(List.of(), read.json().getList("$"), "the series of the deleted id", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // The tree recomputes
    // ------------------------------------------------------------------------------------------- //

    /**
     * Recomputes the tree's sort order as the identity and checks that it keeps the order of every row that was there
     * before (no two rows swap), and that the fixture's root and child are numbered in tree order. A recompute after
     * which nothing changed in the tree does nothing (it compares the last change with the last run), so the order the
     * other tests' rows were left in is the one the check holds it to.
     */
    private static void recomputeKeepsTheOrder(SweepContext ctx, SweepIdentity as, String table, String path,
                                               List<String> fixture) throws Exception {
        Map<Integer, Integer> before = treeSortOrders(ctx, table);
        SweepResponse answer = ctx.ok(as, SweepRequest.put(path));
        ctx.checkEquals("true", answer.text().trim(), "the answer of the recompute", answer);
        Map<Integer, Integer> after = treeSortOrders(ctx, table);
        List<String> swapped = new ArrayList<>();
        List<Integer> ids = new ArrayList<>(before.keySet());
        ids.retainAll(after.keySet());
        // A row never numbered (0: made since the last recompute) has no order to keep
        ids.removeIf(id -> before.get(id) == 0);
        for (Integer a : ids) {
            for (Integer b : ids) {
                if (before.get(a) < before.get(b) && after.get(a) >= after.get(b)) {
                    swapped.add(table + " " + a + " (" + before.get(a) + " -> " + after.get(a) + ") and " + b + " ("
                            + before.get(b) + " -> " + after.get(b) + ")");
                }
            }
        }
        ctx.check(swapped.isEmpty(), "the recompute keeps the order of the " + table.toLowerCase() + " rows that were "
                + "there before; swapped: " + (swapped.size() > 5 ? swapped.subList(0, 5) + " ..." : swapped), answer);
        int root = (Integer) ctx.row(fixture.get(0));
        int child = (Integer) ctx.row(fixture.get(1));
        ctx.check(after.get(root) != null && after.get(child) != null && after.get(root) < after.get(child),
                "the fixture's root is numbered before its child (" + after.get(root) + ", " + after.get(child) + ")",
                answer);
        long changed = after.keySet().stream().filter(id -> !Objects.equals(before.get(id), after.get(id))).count();
        System.out.println("Endpoint sweep: reference-data: " + path + " changed the treeSortOrder of " + changed + " of "
                + after.size() + " " + table + " rows");
    }

    /** Every row's treeSortOrder, read from the database (no value object carries it) */
    private static Map<Integer, Integer> treeSortOrders(SweepContext ctx, String table) throws Exception {
        Map<Integer, Integer> orders = new TreeMap<>();
        try (Connection connection = ctx.bean(DataSource.class).getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, \"treeSortOrder\" FROM public.\"" + table + "\"")) {
            while (rows.next()) {
                orders.put(rows.getInt(1), rows.getInt(2));
            }
        }
        return orders;
    }

    // ------------------------------------------------------------------------------------------- //
    // Look-ups of the fixture's rows
    // ------------------------------------------------------------------------------------------- //

    private static int findOrCreateArea(SweepContext ctx, String name, Integer parentId, double order,
                                        Map<String, Object> geometry) {
        Integer id = areaId(ctx, name);
        if (id != null) {
            return id;
        }
        SweepResponse created = ctx.ok("admin", SweepRequest.post("/rest/areas/area")
                .json(areaBody(name, parentId, order, geometry)));
        return created.json().getInt("id");
    }

    /** The id of the area of the English name (active or not), or null */
    static Integer areaId(SweepContext ctx, String name) {
        SweepResponse found = ctx.ok("anonymous", searchAreas(name).query("inactive", true).query("limit", 100));
        return found.json().get("find { it.descs.find { d -> d.lang == 'en' && d.name == '" + name + "' } }.id");
    }

    private static int findOrCreateCategory(SweepContext ctx, String name, Integer parentId, double order) {
        Integer id = categoryId(ctx, name);
        if (id != null) {
            return id;
        }
        SweepResponse created = ctx.ok("sysadmin", SweepRequest.post("/rest/categories/category")
                .json(categoryBody(name, parentId, order)));
        return created.json().getInt("id");
    }

    /** The id of the category of the English name (active or not, of any type), or null */
    static Integer categoryId(SweepContext ctx, String name) {
        SweepResponse found = ctx.ok("anonymous", searchCategories(name).query("inactive", true));
        return found.json().get("find { it.descs.find { d -> d.lang == 'en' && d.name == '" + name + "' } }.id");
    }

    private static List<Object> charts(SweepContext ctx, String number) {
        return ctx.ok("anonymous", chartsByNumber(number)).json().getList("$");
    }

    private static Object entry(SweepContext ctx, String key) {
        return ctx.ok("anonymous", entries(DICTIONARY)).json().get("find { it.key == '" + key + "' }");
    }

    private static Object series(SweepContext ctx, String seriesId) {
        List<Object> found = ctx.ok("anonymous", SweepRequest.get("/rest/message-series/search/{seriesIds}")
                .var("seriesIds", seriesId)).json().getList("$");
        return found.isEmpty() ? null : found.getFirst();
    }

    private static Object domain(SweepContext ctx, String domainId) {
        return ctx.ok("anonymous", allDomains().query("inactive", true)).json()
                .get("find { it.domainId == '" + domainId + "' }");
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests and bodies
    // ------------------------------------------------------------------------------------------- //

    private static SweepRequest getArea(Object id) {
        return SweepRequest.get("/rest/areas/area/{areaId}").var("areaId", id);
    }

    private static SweepRequest deleteArea(Object id) {
        return SweepRequest.delete("/rest/areas/area/{areaId}").var("areaId", id);
    }

    private static SweepRequest moveArea(Object id, Object parentId) {
        return SweepRequest.put("/rest/areas/move-area").json(Map.of("areaId", id, "parentId", parentId));
    }

    /** The area search by English name; the limit is set by the caller (without one it answers nothing) */
    private static SweepRequest searchAreas(String name) {
        return SweepRequest.get("/rest/areas/search").query("lang", "en").query("name", name);
    }

    private static SweepRequest searchAreaIds(String ids) {
        return SweepRequest.get("/rest/areas/search/{areaIds}").var("areaIds", ids);
    }

    private static SweepRequest intersectingAreas(Map<String, Object> geometry) {
        return SweepRequest.post("/rest/areas/intersecting-areas").json(featureCollection(geometry));
    }

    private static SweepRequest getCategory(Object id) {
        return SweepRequest.get("/rest/categories/category/{categoryId}").var("categoryId", id);
    }

    private static SweepRequest deleteCategory(Object id) {
        return SweepRequest.delete("/rest/categories/category/{categoryId}").var("categoryId", id);
    }

    private static SweepRequest moveCategory(Object id, Object parentId) {
        return SweepRequest.put("/rest/categories/move-category").json(Map.of("categoryId", id, "parentId", parentId));
    }

    private static SweepRequest searchCategories(String name) {
        return SweepRequest.get("/rest/categories/search").query("lang", "en").query("name", name);
    }

    private static SweepRequest searchCategoriesWithAtons(String name, List<?> atons) {
        return SweepRequest.put("/rest/categories/search").query("lang", "en").query("name", name).json(atons);
    }

    private static SweepRequest searchCategoryIds(String ids) {
        return SweepRequest.get("/rest/categories/search/{categoryIds}").var("categoryIds", ids);
    }

    private static SweepRequest chartsByNumber(String numbers) {
        return SweepRequest.get("/rest/charts/search/{chartNumbers}").var("chartNumbers", numbers);
    }

    private static SweepRequest entries(String dictionary) {
        return SweepRequest.get("/rest/dictionaries/dictionary/{name}/entries").var("name", dictionary);
    }

    private static SweepRequest properties(String dictionary, String lang) {
        return SweepRequest.get("/rest/dictionaries/dictionary/{name}_{lang}.properties").var("name", dictionary)
                .var("lang", lang);
    }

    private static SweepRequest keyUsers(String key) {
        return SweepRequest.get("/rest/dictionaries/dictionary/{name}/key-users").var("name", DICTIONARY)
                .query("key", key);
    }

    private static SweepRequest deleteEntry(String key) {
        return SweepRequest.delete("/rest/dictionaries/dictionary/{name}/{key}").var("name", DICTIONARY).var("key", key);
    }

    private static SweepRequest matchesAton(String seamarkType) {
        return SweepRequest.put("/rest/dictionaries/matches-aton").json(Map.of(
                "aton", Map.of("tags", Map.of("seamark:type", seamarkType)),
                "values", List.of(Map.of("key", DICTIONARY_KEY, "atonFilter", ATON_FILTER))));
    }

    private static SweepRequest allDomains() {
        return SweepRequest.get("/rest/domains/all").query("keycloakState", false);
    }

    private static SweepRequest number(String seriesId, int year) {
        return SweepRequest.get("/rest/message-series/series/{seriesIds}/number/{year}").var("seriesIds", seriesId)
                .var("year", year);
    }

    private static SweepRequest setNumber(String seriesId, int year) {
        return SweepRequest.put("/rest/message-series/series/{seriesIds}/number/{year}").var("seriesIds", seriesId)
                .var("year", year);
    }

    private static SweepRequest computeNumber(String seriesId, int year) {
        return SweepRequest.get("/rest/message-series/series/{seriesIds}/compute-number/{year}")
                .var("seriesIds", seriesId).var("year", year);
    }

    /** An area with an English and a Danish name, under the parent (null: a root) */
    static Map<String, Object> areaBody(String name, Object parentId, double order, Map<String, Object> geometry) {
        Map<String, Object> area = new LinkedHashMap<>();
        area.put("active", true);
        area.put("siblingSortOrder", order);
        if (parentId != null) {
            area.put("parent", Map.of("id", parentId));
        }
        area.put("descs", List.of(Map.of("lang", "en", "name", name), Map.of("lang", "da", "name", name + "-da")));
        if (geometry != null) {
            area.put("geometry", geometry);
        }
        return area;
    }

    /** A category with an English and a Danish name, under the parent (null: a root) */
    static Map<String, Object> categoryBody(String name, Object parentId, double order) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("active", true);
        category.put("type", "CATEGORY");
        category.put("siblingSortOrder", order);
        if (parentId != null) {
            category.put("parent", Map.of("id", parentId));
        }
        category.put("descs", List.of(Map.of("lang", "en", "name", name), Map.of("lang", "da", "name", name + "-da")));
        return category;
    }

    static Map<String, Object> chartBody(String number, String name, int internationalNumber) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartNumber", number);
        chart.put("internationalNumber", internationalNumber);
        chart.put("active", true);
        chart.put("scale", 50000);
        chart.put("name", name);
        chart.put("horizontalDatum", "WGS84");
        chart.put("geometry", box(170, -60, 171, -59));
        return chart;
    }

    static Map<String, Object> entryBody(String key, String english, String danish, String atonFilter) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("descs", List.of(Map.of("lang", "en", "value", english), Map.of("lang", "da", "value", danish)));
        entry.put("atonFilter", atonFilter);
        return entry;
    }

    /** A dictionary file of the web dictionary with the key's English text */
    static byte[] dictionaryFile(String key, String english) {
        return ("[{\"name\":\"" + DICTIONARY + "\",\"entries\":[{\"key\":\"" + key + "\",\"descs\":[{\"lang\":\"en\","
                + "\"value\":\"" + english + "\"}]}]}]").getBytes(StandardCharsets.UTF_8);
    }

    /** A domain naming the fixture's area child and category child (no series: a series a domain names is in use) */
    static Map<String, Object> domainBody(SweepContext ctx, String domainId, String name) {
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("domainId", domainId);
        domain.put("name", name);
        domain.put("active", true);
        if (ctx.hasRow(AREA_CHILD)) {
            domain.put("areas", List.of(Map.of("id", ctx.row(AREA_CHILD))));
        }
        if (ctx.hasRow(CATEGORY_CHILD)) {
            domain.put("categories", List.of(Map.of("id", ctx.row(CATEGORY_CHILD))));
        }
        return domain;
    }

    /** A Yearly NW series with its formats, types and editor fields */
    static Map<String, Object> seriesBody(String seriesId) {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("seriesId", seriesId);
        series.put("mainType", "NW");
        series.put("types", List.of("LOCAL_WARNING", "COASTAL_WARNING"));
        series.put("shortFormat", "SW-${number-3-digits}-${year-2-digits}");
        series.put("navtexFormat", "SW ${number-3-digits}/${year-2-digits}");
        series.put("numberSequenceType", "YEARLY");
        series.put("editorFields", List.of("title", "areas"));
        series.put("excludeFromMessageIndex", true);
        return series;
    }

    private static Map<String, Object> withId(Map<String, Object> body, Object id) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put("id", id);
        return copy;
    }

    /** A rectangle as a GeoJSON polygon */
    static Map<String, Object> box(double west, double south, double east, double north) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(west, south), List.of(east, south),
                List.of(east, north), List.of(west, north), List.of(west, south))));
    }

    static Map<String, Object> featureCollection(Map<String, Object> geometry) {
        return Map.of("type", "FeatureCollection", "features", List.of(Map.of("type", "Feature",
                "geometry", geometry, "properties", Map.of())));
    }

    /** The outer ring of a polygon as text, so that a sent and a read geometry compare whatever their number types */
    @SuppressWarnings("unchecked")
    private static String ring(Object geometry) {
        if (!(geometry instanceof Map<?, ?> map)) {
            return String.valueOf(geometry);
        }
        List<String> points = new ArrayList<>();
        for (Object point : ((List<Object>) ((List<Object>) map.get("coordinates")).getFirst())) {
            List<Object> xy = (List<Object>) point;
            // RestAssured's JsonPath reads a decimal as a Float: its text is the number the answer holds
            points.add(Double.parseDouble(String.valueOf(xy.get(0))) + "," + Double.parseDouble(String.valueOf(xy.get(1))));
        }
        return map.get("type") + " " + String.join(" ", points);
    }

    /** The names of the rows a list answers (not of their parents), in every language */
    @SuppressWarnings("unchecked")
    private static Set<String> names(SweepResponse response) {
        Set<String> names = new HashSet<>();
        List<Map<String, Object>> rows = response.json().getList("$");
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            for (Map<String, Object> desc : (List<Map<String, Object>>) row.getOrDefault("descs", List.of())) {
                names.add(String.valueOf(desc.get("name")));
            }
        }
        return names;
    }

    /** The values of the field of each object of a list, as a set (the join tables have no order) */
    @SuppressWarnings("unchecked")
    private static Set<Object> ids(Object list, String field) {
        Set<Object> ids = new HashSet<>();
        if (list instanceof Collection<?> items) {
            for (Object item : items) {
                Object value = ((Map<String, Object>) item).get(field);
                ids.add(value instanceof Number n ? n.longValue() : value);
            }
        }
        return ids;
    }

    /** Runs the body while the Keycloak integration points at the URL; points it back afterwards */
    static <T> T withKeycloak(SweepContext ctx, String url, Callable<T> body) throws Exception {
        KeycloakIntegrationService target = AopTestUtils.getTargetObject(ctx.bean(KeycloakIntegrationService.class));
        Object original = ReflectionTestUtils.getField(target, "authServerUrl");
        ReflectionTestUtils.setField(target, "authServerUrl", url);
        try {
            return body.call();
        } finally {
            ReflectionTestUtils.setField(target, "authServerUrl", original);
        }
    }
}
