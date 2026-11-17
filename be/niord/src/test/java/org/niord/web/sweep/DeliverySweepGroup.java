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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.niord.web.sweep.DeliverySweepFixture.*;

/**
 * The endpoint sweep of the delivery and template handlers ({@code endpoints/delivery.json}): mailing lists,
 * promulgation types, NAVTEX transmitters, SafetyNET areas, Twitter settings, templates and parameter types, firing
 * schedules and periods, AtoNs, Niord integrations and scheduled mails.
 *
 * <p>The fixture ({@link DeliverySweepFixture}) makes, by name and through the API where the API can: a NAVTEX type
 * with a transmitter, a SafetyNET type with one area of each shape and a Twitter type with settings, each in the
 * domain niord-nw (PROM6, PROM7 and the NAVTEX sibling are faults of a type's lazy domains, seen only on such a type); an
 * inactive mailing list with a public-report trigger that has a script (inactive, so the minute trigger job never
 * runs it); a contact on no list; a list parameter type; an inactive Niord integration at {@code http://127.0.0.1:9};
 * a firing period on a fixed day in a firing area of niord-fa (the read passes the day and that domain's header); an
 * AtoN; and a scheduled mail whose send date is far ahead, read as PENDING or SENT. The tear-down removes every one
 * of them and reads each gone; the Twitter settings, which no endpoint removes (PROM8), are removed through the
 * entity manager so that their type can be deleted.
 *
 * <p>The cases are the ones of the table; the code-checked ones are the ticketed exports, the report, the template
 * execution and the firing message generation (see the table's cells). Write cases make their own rows, read back
 * every field they sent, remove them and read them gone.
 */
class DeliverySweepGroup implements SweepGroup {

    /** The two fixed days of the firing periods, far from the days the firing ITs use */
    static final long FIRING_DAY = Instant.parse("2033-02-14T12:00:00Z").toEpochMilli();
    static final long FIRING_DAY_OF_THE_WRITE = Instant.parse("2033-02-15T12:00:00Z").toEpochMilli();

    private final DeliverySweepFixture fixture = new DeliverySweepFixture();

    @Override
    public String name() {
        return "delivery";
    }

    @Override
    public Set<String> rows() {
        return ROWS;
    }

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        fixture.setUp(ctx);
    }

    @Override
    public List<String> tearDown(SweepContext ctx) throws Exception {
        return fixture.tearDown(ctx);
    }

    @Override
    public Map<String, String> leftByDesign() {
        return Map.of("the batch instance of each upload (param-type-import, promulgation-type-import, "
                + "mailing-list-import; file sweep-delivery-<unique>.json), and the job's BATCH_JOB_<job name> "
                + "Sequence row the first run of a job makes", "no endpoint removes a batch instance or a sequence");
    }

    @Override
    public Set<String> tables() {
        return TABLES;
    }

    @Override
    public void cases(SweepCases cases) {
        atonCases(cases);
        firingCases(cases);
        DeliverySweepMailingLists.cases(cases);
        DeliverySweepPromulgation.cases(cases);
        integrationAndMailCases(cases);
        templateCases(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // AtoNs
    // ------------------------------------------------------------------------------------------- //

    private static void atonCases(SweepCases cases) {
        cases.add("getAtonOverviewIcon", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/aton-icon/overview")
                        .query("seamark:type", "buoy_cardinal")
                        .query("seamark:buoy_cardinal:category", "north")
                        .query("seamark:buoy_cardinal:colour", "black;yellow"))
                .variants((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/aton-icon/overview")
                        .note("no seamark:type: the generic icon"), Cell.status(307, null))));

        cases.add("createSvgForAton", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/aton-icon/svg").json(atonBody("buoy_cardinal")))
                .sweep((ctx, as) -> {
                    SweepResponse svg = ctx.ok(as, SweepRequest.post("/rest/aton-icon/svg")
                            .json(atonBody("buoy_cardinal")));
                    ctx.check(svg.text().contains("<svg"), "the answer is an SVG document", svg);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/aton-icon/svg").query("width", 0)
                            .json(atonBody("buoy_cardinal")).note("width 0"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.post("/rest/aton-icon/svg").json(Map.of()).note("no tags"),
                            Cell.status(400, "No AtoN tags to render"));
                    ctx.call(as.name(), SweepRequest.post("/rest/aton-icon/svg").query("scale", "abc")
                            .json(atonBody("buoy_cardinal")).note("malformed scale"),
                            Cell.status(400, "Failed to convert 'scale' with value: 'abc'"));
                    // The tags as a list are read as no tags and the icon is drawn without them (200), as the old
                    // system's deserializer read them; no screen calls it: not a fault (wave 3b's second reader)
                    SweepResponse listed = ctx.ok(as, SweepRequest.post("/rest/aton-icon/svg")
                            .json(atonBodyWithTagList("buoy_cardinal"))
                            .note("the tags as a list of key-value objects"));
                    ctx.check(listed.text().contains("<svg"), "the answer is an SVG document (drawn without tags)",
                            listed);
                }));

        cases.add("streamAtonTile", SweepCase
                .of((ctx, row) -> atonTile(ATON_TILE_ZOOM))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.get("/rest/aton-tiles/{z}/{x}/{y}.png").var("z", 99)
                            .var("x", 0).var("y", 0).note("a zoom level not served"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.get("/rest/aton-tiles/{z}/{x}/{y}.png").var("z", 3)
                            .var("x", "abc").var("y", 0).note("malformed x"),
                            Cell.status(400, "Failed to convert 'x' with value: 'abc'"));
                }));

        cases.add("getAtons", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/atons/aton/{atonUids}").var("atonUids", ATON))
                .probes((ctx, as) -> {
                    SweepResponse none = ctx.ok(as, SweepRequest.get("/rest/atons/aton/{atonUids}")
                            .var("atonUids", ctx.name("unknown")).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), none.json().getList("$"), "the answer for an unknown AtoN", none);
                }));

        cases.add("searchAtonsByName", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/atons/search-name").query("name", ATON))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/atons/search-name")
                        .query("name", ATON).query("maxAtonNo", "abc").note("malformed maxAtonNo"),
                        Cell.status(400, "Failed to convert 'maxAtonNo' with value: 'abc'"))));

        cases.add("searchAtons", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/atons/search").query("name", ATON))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/atons/search")
                        .query("minLat", ATON_LAT - 0.01).query("minLon", ATON_LON - 0.01)
                        .query("maxLat", ATON_LAT + 0.01).query("maxLon", ATON_LON + 0.01)
                        .note("the extent around the fixture's AtoN")), "data", ATON))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/atons/search")
                        .query("minLat", "north").note("malformed minLat"),
                        Cell.status(400, "Failed to convert 'minLat' with value: 'north'"))));

        cases.add("getNodeTypeNames", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/atons/defaults/node-types"))
                .variants((ctx, as) -> {
                    SweepResponse named = ctx.ok(as, SweepRequest.get("/rest/atons/defaults/node-types")
                            .query("name", "Cardinal"));
                    ctx.check(named.text().contains("North Cardinal"), "the names holding Cardinal list the north "
                            + "cardinal buoy", named);
                }));

        cases.add("mergeAtonWithNodeTypes", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/atons/defaults/merge-with-node-types")
                        .json(Map.of("aton", atonBody("buoy_cardinal"), "nodeTypeNames",
                                List.of("North Cardinal Buoy"))))
                .sweep((ctx, as) -> {
                    SweepResponse merged = ctx.ok(as, SweepRequest.post("/rest/atons/defaults/merge-with-node-types")
                            .json(Map.of("aton", atonBody("buoy_cardinal"), "nodeTypeNames",
                                    List.of("North Cardinal Buoy"))));
                    ctx.checkEquals("north", merged.json().getString("tags.'seamark:buoy_cardinal:category'"),
                            "the category the north cardinal preset adds", merged);
                    ctx.checkEquals(ATON, merged.json().getString("tags.'seamark:name'"),
                            "the AtoN's own name tag, kept by the merge", merged);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post(
                        "/rest/atons/defaults/merge-with-node-types").json(Map.of()).note("no AtoN"),
                        Cell.status(400, "No AtoN to merge"))));

        cases.add("autoCompleteKey", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/atons/defaults/auto-complete-key")
                        .query("key", "seamark:buoy_cardinal").json(atonBody("buoy_cardinal"))));

        cases.add("autoCompleteValue", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/atons/defaults/auto-complete-value")
                        .query("key", "seamark:type").query("value", "buoy").json(atonBody("buoy_cardinal"))));
    }

    /** The tile of the fixture's AtoN at the zoom level (OpenStreetMap numbering) */
    static SweepRequest atonTile(int zoom) {
        int tiles = 1 << zoom;
        int x = (int) Math.floor((ATON_LON + 180.0) / 360.0 * tiles);
        double lat = Math.toRadians(ATON_LAT);
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(lat) + 1.0 / Math.cos(lat)) / Math.PI) / 2.0 * tiles);
        return SweepRequest.get("/rest/aton-tiles/{z}/{x}/{y}.png").var("z", zoom).var("x", x).var("y", y);
    }

    // ------------------------------------------------------------------------------------------- //
    // Firing schedules and periods
    // ------------------------------------------------------------------------------------------- //

    private static void firingCases(SweepCases cases) {
        cases.add("getFiringSchedules", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/firing-schedules/all")));

        cases.add("createFiringSchedule", SweepCase
                // A refused cell names a domain that does not exist: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/firing-schedules/firing-schedule")
                        .json(schedule(null, ctx.name("no-such-domain"), "niord-nm", "dma-nm", false, 3)))
                .sweep((ctx, as) -> createScheduleReadBackAndRemove(ctx, as, "/rest/firing-schedules/firing-schedule"))
                .variants((ctx, as) -> createScheduleReadBackAndRemove(ctx, as,
                        "/rest/firing-schedules/firing-schedule/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/firing-schedules/firing-schedule").json(Map.of())
                            .note("empty body"), Cell.status(400, "A firing schedule must name its domain"));
                    ctx.call(as.name(), SweepRequest.post("/rest/firing-schedules/firing-schedule")
                                    .json(Map.of("domain", Map.of("domainId", FIRING_DOMAIN))).note("no target domain"),
                            Cell.status(400, "A firing schedule must name its target domain"));
                }));

        cases.add("updateFiringSchedule", SweepCase
                // The body's id is not the path's: a gate that let it through answers 400 and changes nothing
                .of((ctx, row) -> SweepRequest.put("/rest/firing-schedules/firing-schedule/{id}").var("id", UNKNOWN)
                        .json(schedule(UNKNOWN + 1, FIRING_DOMAIN, "niord-nm", "dma-nm", false, 3)))
                .sweep((ctx, as) -> {
                    Integer id = createSchedule(ctx, as, "/rest/firing-schedules/firing-schedule");
                    if (id == null) {
                        return;
                    }
                    try {
                        ctx.ok(as, SweepRequest.put("/rest/firing-schedules/firing-schedule/{id}").var("id", id)
                                .json(schedule(id, FIRING_DOMAIN, "niord-nw", "dma-nw", false, 5)));
                        readScheduleBack(ctx, id, "niord-nw", "dma-nw", false, 5);
                    } finally {
                        removeScheduleAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/firing-schedules/firing-schedule/{id}")
                        .var("id", UNKNOWN).json(schedule(UNKNOWN + 1, FIRING_DOMAIN, "niord-nm", "dma-nm", false, 3))
                        .note("the body's id is not the path's"), Cell.status(400, null))));

        cases.add("deleteFiringSchedule", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/firing-schedules/firing-schedule/{id}").var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    Integer id = createSchedule(ctx, as, "/rest/firing-schedules/firing-schedule");
                    if (id != null) {
                        removeScheduleAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete(
                        "/rest/firing-schedules/firing-schedule/{id}").var("id", "abc").note("malformed id"),
                        Cell.status(400, "Failed to convert 'id' with value: 'abc'"))));

        // Global: refused cells only (its run over every active schedule is FiringExerciseServiceITTest's)
        cases.add("updateFiringExercises", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/firing-schedules/update-firing-exercises")));

        cases.add("searchFiringAreaPeriods", SweepCase
                .of((ctx, row) -> firingPeriods(FIRING_DAY))
                .sweep((ctx, as) -> {
                    SweepResponse found = ctx.ok(as, firingPeriods(FIRING_DAY));
                    if (!found.is2xx()) {
                        return;
                    }
                    List<Object> ids = found.json().getList("find { it.area.id == " + ctx.row(FIRING_AREA)
                            + " }.firingPeriods.id");
                    ctx.check(ids != null && ids.contains(ctx.row(FIRING_PERIOD)), "the firing area of the fixture "
                            + "holds its period " + ctx.row(FIRING_PERIOD) + " on its day", found);
                })
                .variants((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get("/rest/firing-schedules/search-firing-area-periods").domain(FIRING_DOMAIN)
                            .note("no date: today"));
                    // niord-nw's area tree holds Denmark and so niord-fa's firing areas: no check of absence
                    ctx.ok(as, firingPeriods(FIRING_DAY).domain("niord-nw").note("another domain's header"));
                    ctx.ok(as, firingPeriods(FIRING_DAY).query("inactive", true).query("lang", "da"));
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(
                        "/rest/firing-schedules/search-firing-area-periods").query("date", "tomorrow")
                        .domain(FIRING_DOMAIN).note("malformed date"),
                        Cell.status(400, "Failed to convert 'date' with value: 'tomorrow'"))));

        cases.add("updateFiringAreaPeriods", SweepCase
                // A refused cell sends the fixture's day as it is: a gate that let it through would change nothing
                .of((ctx, row) -> putFiringPeriods(FIRING_DAY, ctx.row(FIRING_AREA),
                        List.of(period(ctx.row(FIRING_PERIOD)))))
                .sweep((ctx, as) -> {
                    Object area = ctx.row(FIRING_AREA);
                    long from = FIRING_DAY_OF_THE_WRITE - 2 * 3_600_000L;
                    long to = FIRING_DAY_OF_THE_WRITE + 2 * 3_600_000L;
                    try {
                        ctx.ok(as, putFiringPeriods(FIRING_DAY_OF_THE_WRITE, area,
                                List.of(Map.of("fromDate", from, "toDate", to))));
                        SweepResponse read = ctx.ok("anonymous", firingPeriods(FIRING_DAY_OF_THE_WRITE));
                        String at = "find { it.area.id == " + area + " }.firingPeriods";
                        ctx.checkEquals(1, read.json().getList(at).size(), "the number of periods of the day", read);
                        ctx.checkEquals(from, millis(read.json().get(at + "[0].fromDate")), "the period's fromDate",
                                read);
                        ctx.checkEquals(to, millis(read.json().get(at + "[0].toDate")), "the period's toDate", read);
                    } finally {
                        ctx.ok(as, putFiringPeriods(FIRING_DAY_OF_THE_WRITE, area, List.of()));
                        SweepResponse gone = ctx.ok("anonymous", firingPeriods(FIRING_DAY_OF_THE_WRITE)
                                .note("read after the day's periods were removed"));
                        ctx.checkEquals(List.of(), gone.json().getList("find { it.area.id == " + area
                                + " }.firingPeriods"), "the day's periods after the removal", gone);
                    }
                })
                .probes((ctx, as) -> {
                    Object area = ctx.row(FIRING_AREA);
                    ctx.call(as.name(), SweepRequest.put("/rest/firing-schedules/firing-area-periods")
                                    .json(Map.of("area", Map.of("id", area), "firingPeriods", List.of()))
                                    .domain(FIRING_DOMAIN).note("no date"),
                            Cell.status(400, "The date must be specified"));
                    ctx.call(as.name(), putFiringPeriods(FIRING_DAY_OF_THE_WRITE, null, List.of()).note("no area"),
                            Cell.status(400, "The firing area must be specified"));
                    ctx.call(as.name(), SweepRequest.put("/rest/firing-schedules/firing-area-periods")
                                    .query("date", FIRING_DAY_OF_THE_WRITE).json(Map.of("area", Map.of("id", area)))
                                    .domain(FIRING_DOMAIN).note("no list of periods"),
                            Cell.status(400, "The firing periods must be specified"));
                }));

        // Global: refused cells only (its drafts for every firing area are FiringScheduleServiceGapITTest's)
        cases.add("generateFiringAreaMessages", SweepCase
                // The series is niord-nm's: an admin of niord-nw is refused in code; nothing is generated
                .of((ctx, row) -> SweepRequest.post("/rest/firing-schedules/generate-firing-area-messages")
                        .json(Map.of("seriesId", "dma-nm"))));
    }

    /** A date as the answers write it (ISO 8601 with an offset), or epoch milliseconds, in epoch milliseconds */
    static Long millis(Object date) {
        if (date == null) {
            return null;
        }
        if (date instanceof Number n) {
            return n.longValue();
        }
        return java.time.OffsetDateTime.parse(String.valueOf(date)).toInstant().toEpochMilli();
    }

    static SweepRequest firingPeriods(long day) {
        return SweepRequest.get("/rest/firing-schedules/search-firing-area-periods").query("date", day)
                .domain(FIRING_DOMAIN);
    }

    static SweepRequest putFiringPeriods(long day, Object areaId, List<?> periods) {
        Map<String, Object> body = map("area", areaId == null ? null : Map.of("id", areaId), "firingPeriods", periods);
        return SweepRequest.put("/rest/firing-schedules/firing-area-periods").query("date", day).json(body)
                .domain(FIRING_DOMAIN);
    }

    static Map<String, Object> schedule(Integer id, String domain, String targetDomain, String targetSeries,
                                        boolean active, int days) {
        return map("id", id, "domain", Map.of("domainId", domain), "targetDomain", Map.of("domainId", targetDomain),
                "targetSeriesId", targetSeries, "active", active, "scheduleDays", days);
    }

    private static Integer createSchedule(SweepContext ctx, SweepIdentity as, String pattern) {
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern)
                .json(schedule(null, FIRING_DOMAIN, "niord-nm", "dma-nm", false, 3)));
        return created.is2xx() ? created.json().getInt("id") : null;
    }

    private static void createScheduleReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Integer id = createSchedule(ctx, as, pattern);
        if (id == null) {
            return;
        }
        try {
            readScheduleBack(ctx, id, "niord-nm", "dma-nm", false, 3);
        } finally {
            removeScheduleAndReadGone(ctx, as, id);
        }
    }

    private static void readScheduleBack(SweepContext ctx, int id, String targetDomain, String targetSeries,
                                         boolean active, int days) {
        SweepResponse all = ctx.ok("anonymous", SweepRequest.get("/rest/firing-schedules/all"));
        String at = "find { it.id == " + id + " }";
        JsonPath json = all.json();
        ctx.checkEquals(FIRING_DOMAIN, json.getString(at + ".domain.domainId"), "the schedule's domain", all);
        ctx.checkEquals(targetDomain, json.getString(at + ".targetDomain.domainId"), "the target domain", all);
        ctx.checkEquals(targetSeries, json.getString(at + ".targetSeriesId"), "the target series", all);
        ctx.checkEquals(active, json.getBoolean(at + ".active"), "active", all);
        ctx.checkEquals(days, json.getInt(at + ".scheduleDays"), "the schedule days", all);
    }

    private static void removeScheduleAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/firing-schedules/firing-schedule/{id}").var("id", id));
        SweepResponse all = ctx.ok("anonymous", SweepRequest.get("/rest/firing-schedules/all")
                .note("read after the delete"));
        ctx.check(all.json().getList("findAll { it.id == " + id + " }").isEmpty(), "the schedule " + id
                + " is gone", all);
    }

    // ------------------------------------------------------------------------------------------- //
    // Niord integrations and scheduled mails
    // ------------------------------------------------------------------------------------------- //

    private static void integrationAndMailCases(SweepCases cases) {
        cases.add("getAllNiordIntegrations", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/niord-integrations/all")));

        cases.add("getNiordIntegration", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/niord-integrations/niord-integration/{id}")
                        .var("id", ctx.row(INTEGRATION)))
                .probes((ctx, as) -> {
                    // The handler's 404 carries no detail; the handler mapping is resolved before every request
                    ctx.call(as.name(), SweepRequest.get("/rest/niord-integrations/niord-integration/{id}")
                            .var("id", UNKNOWN).note("unknown-id probe"), Cell.status(404, null));
                    ctx.call(as.name(), SweepRequest.get("/rest/niord-integrations/niord-integration/{id}")
                            .var("id", "abc").note("malformed id"),
                            Cell.status(400, "Failed to convert 'id' with value: 'abc'"));
                }));

        cases.add("createNiordIntegration", SweepCase
                // A refused cell sends a URL the validator refuses: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/niord-integrations/niord-integration")
                        .json(integration(null, "file:///sweep-delivery", List.of())))
                .sweep((ctx, as) -> createIntegrationReadBackAndRemove(ctx, as,
                        "/rest/niord-integrations/niord-integration"))
                .variants((ctx, as) -> createIntegrationReadBackAndRemove(ctx, as,
                        "/rest/niord-integrations/niord-integration/"))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/niord-integrations/niord-integration")
                        .json(integration(null, "ftp://127.0.0.1:9/x", List.of())).note("a scheme other than http"),
                        Cell.status(400, "Niord integration URL must be http or https"))));

        cases.add("updateNiordIntegration", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/niord-integrations/niord-integration/{id}")
                        .var("id", ctx.row(INTEGRATION))
                        .json(integration(UNKNOWN, INTEGRATION_URL, List.of())))
                .sweep((ctx, as) -> {
                    Integer id = createIntegration(ctx, as, "/rest/niord-integrations/niord-integration");
                    if (id == null) {
                        return;
                    }
                    try {
                        String url = "http://127.0.0.1:9/" + ctx.name("updated-" + ctx.unique());
                        List<Map<String, Object>> mappings = List.of(
                                Map.of("sourceSeriesId", "sweep-delivery-a", "targetSeriesId", "dma-nw"),
                                Map.of("sourceSeriesId", "sweep-delivery-b", "targetSeriesId", "dma-nw-local"));
                        // Both flags flipped from the create's (assignNewUids on, createBaseData off)
                        ctx.ok(as, SweepRequest.put("/rest/niord-integrations/niord-integration/{id}").var("id", id)
                                .json(integration(id, url, mappings, false, true)));
                        readIntegrationBack(ctx, as, id, url, mappings, false, true);
                    } finally {
                        removeIntegrationAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put(
                        "/rest/niord-integrations/niord-integration/{id}").var("id", ctx.row(INTEGRATION))
                        .json(integration(UNKNOWN, INTEGRATION_URL, List.of())).note("the body's id is not the path's"),
                        Cell.status(400, null))));

        cases.add("deleteNiordIntegration", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/niord-integrations/niord-integration/{id}")
                        .var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    Integer id = createIntegration(ctx, as, "/rest/niord-integrations/niord-integration");
                    if (id != null) {
                        removeIntegrationAndReadGone(ctx, as, id);
                    }
                }));

        cases.add("executeNiordIntegration", SweepCase
                // A refused cell names no integration: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.put("/rest/niord-integrations/execute/{id}").var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    // The sweep's own inactive integration: the execution returns before any network call
                    Integer id = createIntegration(ctx, as, "/rest/niord-integrations/niord-integration");
                    if (id == null) {
                        return;
                    }
                    try {
                        // The create set a next execution already; the execution computes a new one (now plus
                        // its interval plus random seconds, to the millisecond), so a changed value shows it ran
                        Object before = ctx.ok(as, SweepRequest.get("/rest/niord-integrations/niord-integration/{id}")
                                .var("id", id)).json().get("nextScheduledExecution");
                        ctx.ok(as, SweepRequest.put("/rest/niord-integrations/execute/{id}").var("id", id));
                        SweepResponse read = ctx.ok(as, SweepRequest.get(
                                "/rest/niord-integrations/niord-integration/{id}").var("id", id));
                        ctx.checkEquals(false, read.json().getBoolean("active"), "the executed integration is "
                                + "still inactive", read);
                        Object after = read.json().get("nextScheduledExecution");
                        ctx.check(after != null && !after.equals(before), "the execution computed a new next "
                                + "scheduled execution (was " + before + ")", read);
                    } finally {
                        removeIntegrationAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/niord-integrations/execute/{id}")
                        .var("id", UNKNOWN).note("unknown-id probe"),
                        Cell.status(400, "No Niord Integration with ID " + UNKNOWN))));

        cases.add("searchScheduledMails", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/scheduled-mails/search").query("subject", MAIL))
                .sweep((ctx, as) -> {
                    SweepResponse found = ctx.ok(as, SweepRequest.get("/rest/scheduled-mails/search")
                            .query("subject", MAIL));
                    String status = found.json().getString("data.find { it.subject == '" + MAIL + "' }.status");
                    // The minute sender may have sent it (its send date is far ahead, so it should still wait)
                    ctx.check("PENDING".equals(status) || "SENT".equals(status), "the fixture's mail is PENDING or "
                            + "SENT (is " + status + ")", found);
                })
                .variants((ctx, as) -> {
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/scheduled-mails/search")
                            .query("recipient", MAIL_RECIPIENT).query("maxSize", 5).query("page", 0)), "data", MAIL);
                    SweepResponse sent = ctx.ok(as, SweepRequest.get("/rest/scheduled-mails/search")
                            .query("subject", MAIL).query("status", "ERROR"));
                    ctx.checkEquals(0, sent.json().getInt("total"), "the fixture's mails in ERROR", sent);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/scheduled-mails/search")
                        .query("status", "abc").note("malformed status"),
                        Cell.status(400, "Failed to convert 'status' with value: 'abc'"))));

        cases.add("getScheduledMail", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/scheduled-mails/scheduled-mail/{scheduledMailId}")
                        .var("scheduledMailId", ctx.row(MAIL)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(
                        "/rest/scheduled-mails/scheduled-mail/{scheduledMailId}").var("scheduledMailId", UNKNOWN)
                        .note("unknown-id probe: the handler's 404 carries no detail"), Cell.status(404, null))));
    }

    static Map<String, Object> integration(Integer id, String url, List<Map<String, Object>> mappings) {
        return integration(id, url, mappings, false, false);
    }

    /** An inactive integration (never fetched: the execution returns before the network for an inactive one) */
    static Map<String, Object> integration(Integer id, String url, List<Map<String, Object>> mappings,
                                           boolean assignNewUids, boolean createBaseData) {
        return map("id", id, "url", url, "active", false, "assignNewUids", assignNewUids,
                "createBaseData", createBaseData, "messageSeriesMappings", mappings);
    }

    /** Creates an integration with assignNewUids set and createBaseData not (the update flips both) */
    private static Integer createIntegration(SweepContext ctx, SweepIdentity as, String pattern) {
        String url = "http://127.0.0.1:9/" + ctx.name("created-" + ctx.unique());
        List<Map<String, Object>> mappings = List.of(Map.of("sourceSeriesId", "sweep-delivery-a",
                "targetSeriesId", "dma-nw"));
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern)
                .json(integration(null, url, mappings, true, false)));
        if (!created.is2xx()) {
            return null;
        }
        int id = created.json().getInt("id");
        readIntegrationBack(ctx, as, id, url, mappings, true, false);
        return id;
    }

    private static void createIntegrationReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Integer id = createIntegration(ctx, as, pattern);
        if (id != null) {
            removeIntegrationAndReadGone(ctx, as, id);
        }
    }

    private static void readIntegrationBack(SweepContext ctx, SweepIdentity as, int id, String url,
                                            List<Map<String, Object>> mappings, boolean assignNewUids,
                                            boolean createBaseData) {
        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/niord-integrations/niord-integration/{id}")
                .var("id", id));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(url, json.getString("url"), "the url", read);
        ctx.checkEquals(false, json.getBoolean("active"), "active", read);
        ctx.checkEquals(assignNewUids, json.getBoolean("assignNewUids"), "assignNewUids", read);
        ctx.checkEquals(createBaseData, json.getBoolean("createBaseData"), "createBaseData", read);
        ctx.checkEquals(mappings.size(), json.getList("messageSeriesMappings").size(), "the number of mappings", read);
        for (int i = 0; i < mappings.size(); i++) {
            ctx.checkEquals(mappings.get(i).get("sourceSeriesId"),
                    json.getString("messageSeriesMappings[" + i + "].sourceSeriesId"), "mapping " + i + "'s source",
                    read);
            ctx.checkEquals(mappings.get(i).get("targetSeriesId"),
                    json.getString("messageSeriesMappings[" + i + "].targetSeriesId"), "mapping " + i + "'s target",
                    read);
        }
    }

    private static void removeIntegrationAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/niord-integrations/niord-integration/{id}").var("id", id));
        SweepResponse all = ctx.ok(as, SweepRequest.get("/rest/niord-integrations/all").note("read after the delete"));
        ctx.check(all.json().getList("findAll { it.id == " + id + " }").isEmpty(), "the integration " + id
                + " is gone", all);
    }

    // ------------------------------------------------------------------------------------------- //
    // Templates and parameter types
    // ------------------------------------------------------------------------------------------- //

    private static void templateCases(SweepCases cases) {
        cases.add("getParamTypes", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/templates/parameter-types"))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/templates/parameter-types")
                        .query("lang", "da")), null, PARAM_TYPE)));

        cases.add("exportParamTypes", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/templates/parameter-types/export"))
                .sweep((ctx, as) -> {
                    SweepResponse export = ctx.ok(as, SweepRequest.get("/rest/templates/parameter-types/export"));
                    ctx.check(export.json().get("find { it.name == '" + PARAM_TYPE + "' }.id") == null,
                            "the export resets the ids", export);
                    ctx.check(export.json().getList("findAll { it.type == 'STANDARD' }").isEmpty(),
                            "the export leaves the standard types out", export);
                }));

        cases.add("getParamType", SweepCase
                .of((ctx, row) -> paramType(ctx.row(PARAM_TYPE)))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, paramType(ctx.row(PARAM_TYPE)).query("lang", "da")),
                        null, PARAM_TYPE))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), paramType(UNKNOWN).note("unknown-id probe"),
                            Cell.status(404, "No such parameter type: " + UNKNOWN));
                    ctx.call(as.name(), paramType("abc").note("malformed id"),
                            Cell.status(400, "Failed to convert 'id' with value: 'abc'"));
                }));

        cases.add("createParamType", SweepCase
                // A refused cell names a standard type: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/templates/parameter-type")
                        .json(paramTypeBody(null, "text")))
                .sweep((ctx, as) -> createParamTypeReadBackAndRemove(ctx, as, "/rest/templates/parameter-type"))
                .variants((ctx, as) -> createParamTypeReadBackAndRemove(ctx, as, "/rest/templates/parameter-type/"))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/templates/parameter-type")
                        .json(paramTypeBody(null, "text")).note("a standard type's name"),
                        Cell.status(400, "Illegal parameter type"))));

        cases.add("updateParamType", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/templates/parameter-type/{id}").var("id", ctx.row(PARAM_TYPE))
                        .json(paramTypeBody(UNKNOWN, PARAM_TYPE)))
                .sweep((ctx, as) -> {
                    Integer id = createParamType(ctx, as, "/rest/templates/parameter-type");
                    if (id == null) {
                        return;
                    }
                    try {
                        // A value by the key of a dictionary entry a seed list type uses: the update resolves it
                        // by key and makes no entry (an unknown key would be dropped)
                        SweepResponse all = ctx.ok("anonymous", SweepRequest.get("/rest/templates/parameter-types"));
                        String key = all.json().getString("find { it.type == 'LIST' && it.values }.values[0].key");
                        if (key == null) {
                            ctx.fail(all, "a list parameter type of the seed has a value");
                            return;
                        }
                        String renamed = ctx.name("updated-" + ctx.unique());
                        Map<String, Object> body = paramTypeBody(id, renamed);
                        body.put("values", List.of(Map.of("key", key)));
                        ctx.ok(as, SweepRequest.put("/rest/templates/parameter-type/{id}").var("id", id).json(body));
                        readParamTypeBack(ctx, id, renamed, List.of(key));
                    } finally {
                        removeParamTypeAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/templates/parameter-type/{id}")
                        .var("id", ctx.row(PARAM_TYPE)).json(paramTypeBody(UNKNOWN, PARAM_TYPE))
                        .note("the body's id is not the path's"), Cell.status(400, null))));

        cases.add("deleteParamType", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/templates/parameter-type/{id}").var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    Integer id = createParamType(ctx, as, "/rest/templates/parameter-type");
                    if (id != null) {
                        removeParamTypeAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/templates/parameter-type/{id}")
                        .var("id", UNKNOWN).note("unknown-id probe"),
                        Cell.status(404, "No such parameter type: " + UNKNOWN))));

        cases.add("importParameterTypes", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/templates/upload-param-types")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String name = ctx.name("uploaded-" + ctx.unique());
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post("/rest/templates/upload-param-types"),
                            "[{\"type\":\"LIST\",\"name\":\"" + name + "\",\"values\":[]}]");
                    ctx.jobs().awaitClean(ctx, "param-type-import", file);
                    SweepResponse all = ctx.ok("anonymous", SweepRequest.get("/rest/templates/parameter-types"));
                    Integer id = all.json().get("find { it.name == '" + name + "' }.id");
                    if (id == null) {
                        ctx.fail(all, "the uploaded parameter type " + name + " is listed after its job completed");
                        return;
                    }
                    try {
                        readParamTypeBack(ctx, id, name);
                    } finally {
                        removeParamTypeAndReadGone(ctx, as, id);
                    }
                }));

        cases.add("applyTemplate", SweepCase
                .of((ctx, row) -> row == null
                        // The unsaved template category of the category editor's Test action: sysadmin only
                        ? SweepRequest.put("/rest/templates/execute").json(map(
                                "category", map("type", "TEMPLATE", "scriptResourcePaths", List.of(),
                                        "descs", List.of(Map.of("lang", "en", "name", ctx.name("unsaved-template")))),
                                "message", inlineMessage()))
                        : INLINE_CATEGORY_WITH_ID.equals(row)
                        // An inline category with an id: anyone but a sysadmin gets the stored category of that id
                        // (here none: 404), never the inline definition run as sent
                        ? SweepRequest.put("/rest/templates/execute").json(map(
                                "category", map("id", ctx.row(row), "type", "TEMPLATE", "scriptResourcePaths",
                                        List.of(), "descs", List.of(Map.of("lang", "en", "name",
                                                ctx.name("inline-template-with-id")))),
                                "message", inlineMessage()))
                        // A stored message by its short id, executed with its own template categories
                        : SweepRequest.put("/rest/templates/execute").json(Map.of("messageId", ctx.row(row))))
                .as("sysadmin")
                .probes((ctx, as) -> {
                    ctx.call("user", SweepRequest.put("/rest/templates/execute").json(Map.of()).note("no message"),
                            Cell.status(400, "No proper message specified"));
                    ctx.call("user", SweepRequest.put("/rest/templates/execute").json(Map.of("messageId",
                            ctx.name("no-such-message"))).note("unknown message"),
                            Cell.status(404, "No such message: " + ctx.name("no-such-message")));
                    ctx.call("user", SweepRequest.put("/rest/templates/execute").json(Map.of("categoryId", UNKNOWN,
                            "message", inlineMessage())).note("unknown category"),
                            Cell.status(404, "No such category: " + UNKNOWN));
                }));

        cases.add("validateLightCharacter", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/templates/validate-light-character")
                        .json(Map.of("value", "Fl(2) W 10s")))
                .sweep((ctx, as) -> {
                    SweepResponse valid = ctx.ok(as, SweepRequest.post("/rest/templates/validate-light-character")
                            .json(Map.of("value", "Fl(2) W 10s")));
                    ctx.checkEquals(true, valid.json().getBoolean("isValid"), "Fl(2) W 10s is valid", valid);
                    SweepResponse invalid = ctx.ok(as, SweepRequest.post("/rest/templates/validate-light-character")
                            .json(Map.of("value", "sweep-delivery-no-light")));
                    ctx.checkEquals(false, invalid.json().getBoolean("isValid"), "a text that is no light character "
                            + "is invalid", invalid);
                }));

        cases.add("validateCallSign", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/templates/validate-call-sign").json(Map.of("value", "oxyz")))
                .sweep((ctx, as) -> {
                    SweepResponse answer = ctx.ok(as, SweepRequest.post("/rest/templates/validate-call-sign")
                            .json(Map.of("value", "oxyz")));
                    ctx.checkEquals("OXYZ", answer.json().getString("value"), "the call sign in upper case", answer);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/templates/validate-call-sign")
                        .json(Map.of()).note("no value"), Cell.status(400, "No value to validate"))));
    }

    /** An inline draft message with no template categories of its own */
    static Map<String, Object> inlineMessage() {
        return Map.of("mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT", "autoTitle", false,
                "categories", List.of());
    }

    static SweepRequest paramType(Object id) {
        return SweepRequest.get("/rest/templates/parameter-type/{id}").var("id", id);
    }

    static Map<String, Object> paramTypeBody(Integer id, String name) {
        return map("type", "LIST", "id", id, "name", name, "values", List.of());
    }

    private static Integer createParamType(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("created-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(paramTypeBody(null, name)));
        if (!created.is2xx()) {
            return null;
        }
        int id = created.json().getInt("id");
        readParamTypeBack(ctx, id, name);
        return id;
    }

    private static void createParamTypeReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Integer id = createParamType(ctx, as, pattern);
        if (id != null) {
            removeParamTypeAndReadGone(ctx, as, id);
        }
    }

    private static void readParamTypeBack(SweepContext ctx, int id, String name) {
        readParamTypeBack(ctx, id, name, List.of());
    }

    private static void readParamTypeBack(SweepContext ctx, int id, String name, List<String> valueKeys) {
        SweepResponse read = ctx.ok("anonymous", paramType(id));
        if (!read.is2xx()) {
            return;
        }
        ctx.checkEquals(id, read.json().getInt("id"), "the id", read);
        ctx.checkEquals(name, read.json().getString("name"), "the name", read);
        ctx.checkEquals("LIST", read.json().getString("type"), "the type", read);
        ctx.checkEquals(valueKeys, read.json().getList("values.key"), "the values' keys", read);
    }

    private static void removeParamTypeAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/templates/parameter-type/{id}").var("id", id));
        ctx.call("anonymous", paramType(id).note("read after the delete"),
                Cell.status(404, "No such parameter type: " + id));
    }
}
