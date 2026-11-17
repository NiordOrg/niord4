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

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.niord.web.sweep.PublicationsSweepFixture.*;

/**
 * The endpoint sweep of the publication series, one-off, publication category and legacy publication handlers
 * ({@code endpoints/publications.json}: {@code PublicationSeriesRestService}, {@code OneOffRestService},
 * {@code PublicationCategoryRestService}, {@code PublicationRestService}). The fixture and the helpers are in
 * {@link PublicationsSweepFixture}.
 *
 * <p>The code checks the cells assert, read from the code:
 * <ul>
 *   <li>the series and one-off writes are guarded on the owning domain ({@code PublicationDomainGuard}): a write on
 *       another domain's row, or a body that places a row in another domain, is a 403 {@code NOT_IN_DOMAIN}, the
 *       strongest role included (the realm's admin holds admin in every domain through the base client); the owner
 *       transfer also asks for admin in the target domain from the token's per-domain roles
 *       ({@code isAdminIn}), which the realm's admin has in niord-nw only and {@code adminElsewhere} in niord-nm
 *       only;</li>
 *   <li>the legacy publication create and update refuse a message tag filter change to anyone but a system
 *       administrator; the export asks for the admin role itself (a ticket with that role passes, a role-less one
 *       does not); the report generator and the file upload refuse a folder that is not a temporary one;</li>
 *   <li>the public reads of the legacy publications narrow to the public tier for a caller without the user role
 *       (ACTIVE rows of a publishing category; the address of an unreleased document withheld), which the sweeps
 *       check per identity.</li>
 * </ul>
 * Refusals of the publication resources answer {@code {code, message}} rather than a problem detail, so their cells
 * are {@code "detail": null} and the steps check the code ({@link PublicationsSweepFixture#refusedWith}).
 */
class PublicationsSweepGroup implements SweepGroup {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An id no publication row has */
    static final String UNKNOWN = "sweep-publications-no-such-row";
    /** A publicationId no legacy publication has (they are UUIDs) */
    static final String UNKNOWN_PUBLICATION = "00000000-0000-4000-8000-000000000000";

    @Override
    public String name() {
        return "publications";
    }

    @Override
    public Set<String> rows() {
        return ROWS;
    }

    @Override
    public void setUp(SweepContext ctx) {
        PublicationsSweepFixture.setUp(ctx);
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        return PublicationsSweepFixture.tearDown(ctx);
    }

    @Override
    public Map<String, String> leftByDesign() {
        Map<String, String> left = new LinkedHashMap<>();
        left.put("the batch instance of each upload (publication-series-import, publication-category-import, "
                + "publication-import; files sweep-publications-<unique>.json)", "no endpoint removes a batch instance");
        left.put("the repository files of the released fixture publication (its committed folder: the report the "
                + "release rendered)", "deleting a legacy publication leaves its repository folder; no endpoint "
                + "removes it");
        left.put("the temporary repository folders the legacy publication reads and writes make (editable, "
                + "new and copy templates, the report generator, the file upload)",
                "temporary folders expire after a day");
        return left;
    }

    @Override
    public Set<String> tables() {
        return Set.of("PublicationSeries", "PublicationSeriesDesc", "PublicationSeries_languages",
                "PublicationSeries_AvailableDomain", "PublicationIssue", "PublicationIssueDesc", "IssueAuditEntry",
                "PublicationCategory", "PublicationCategoryDesc", "Publication", "PublicationDesc", "MessageTag");
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        oneOffCases(cases);
        categoryCases(cases);
        seriesReadCases(cases);
        seriesWriteCases(cases);
        seriesToolCases(cases);
        publicationReadCases(cases);
        publicationWriteCases(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // One-off publications
    // ------------------------------------------------------------------------------------------- //

    private void oneOffCases(SweepCases cases) {
        cases.add("oneOffList", SweepCase
                .of((ctx, row) -> SweepRequest.get(O_LIST).query("lang", "en"))
                .sweep((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get(O_LIST).query("lang", "en"));
                    // The domain parameter narrows by owner
                    SweepResponse nm = ctx.ok(as, SweepRequest.get(O_LIST).query("domain", NM));
                    ctx.expectRows(nm, null, ONE_OFF_NM);
                    absent(ctx, nm, ONE_OFF, "the one-offs owned by niord-nm leave out niord-nw's");
                    // A scheduled series is no one-off
                    absent(ctx, nm, SERIES_NM, "the one-off list leaves out a scheduled series");
                })
                .variants((ctx, as) -> {
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(O_LIST_ALIAS).query("lang", "da")), null, ONE_OFF);
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(O_LIST).query("domain", NW)), null, ONE_OFF);
                }));

        cases.add("oneOffGet", SweepCase
                .of((ctx, row) -> SweepRequest.get(O_ONE).var("seriesId", ctx.row(row == null ? ONE_OFF : row))
                        .query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, SweepRequest.get(O_ONE).var("seriesId", ONE_OFF)
                            .query("lang", "en"));
                    checkOneOff(ctx, oneOffBody(ONE_OFF, NW), read, "the fixture one-off");
                    // Unscoped: another domain's one-off reads from this desk
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(O_ONE).var("seriesId", ONE_OFF_NM)), null,
                            ONE_OFF_NM);
                })
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.get(O_ONE).var("seriesId", UNKNOWN)
                            .note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                    refusedWith(ctx, as.name(), SweepRequest.get(O_ONE).var("seriesId", SERIES_NW)
                            .note("a scheduled series is no one-off"), 404, "SERIES_NOT_FOUND");
                }));

        cases.add("oneOffCreate", SweepCase
                .of((ctx, row) -> SweepRequest.post(O_LIST)
                        .json(oneOffBody(ctx.name("refused-" + ctx.unique()), ONE_OFF_NM.equals(row) ? NM : NW)))
                .sweep((ctx, as) -> {
                    oneOffRoundTrip(ctx, as, NW, O_LIST);
                    activeToggleRoundTrip(ctx, as);
                    // The body may not place the one-off in another domain: the code of the cell's refusal
                    refusedWith(ctx, as.name(), SweepRequest.post(O_LIST)
                                    .json(oneOffBody(ctx.name("refused-" + ctx.unique()), NM))
                                    .note("a one-off authored into another domain"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> oneOffRoundTrip(ctx, as, domainOf(row), O_LIST))
                .variants((ctx, as) -> oneOffRoundTrip(ctx, as, NW, O_LIST_ALIAS))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.post(O_LIST).json(Map.of()).note("empty body"),
                            400, "SERIES_INVALID");
                    Map<String, Object> nameless = oneOffBody(ctx.name("probe"), NW);
                    seriesOf(nameless).remove("seriesId");
                    seriesOf(nameless).put("descs", new ArrayList<>());
                    refusedWith(ctx, as.name(), SweepRequest.post(O_LIST).json(nameless).note("no name, no id"),
                            400, "NAME_BLANK");
                    refusedWith(ctx, as.name(), SweepRequest.post(O_LIST).json(oneOffBody(ONE_OFF, NW))
                            .note("the id of an existing one-off"), 409, "SERIES_ID_TAKEN");
                    Map<String, Object> uncategorised = oneOffBody(ctx.name("probe"), NW);
                    seriesOf(uncategorised).put("categoryId", UNKNOWN);
                    refusedWith(ctx, as.name(), SweepRequest.post(O_LIST).json(uncategorised)
                            .note("an unknown category"), 404, "CATEGORY_NOT_FOUND");
                }));

        cases.add("oneOffUpdate", SweepCase
                .of((ctx, row) -> fromDesk(row, SweepRequest.put(O_ONE).var("seriesId", ctx.row(row == null
                        ? ONE_OFF : row)).query("lang", "en").json(oneOffAsRead(ctx, (String) ctx.row(row == null
                        ? ONE_OFF : row)))))
                .sweep((ctx, as) -> {
                    oneOffUpdateRoundTrip(ctx, as, NW);
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", ONE_OFF_NM)
                                    .json(oneOffAsRead(ctx, ONE_OFF_NM)).note("another domain's one-off, unchanged"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> oneOffUpdateRoundTrip(ctx, as, domainOf(row)))
                .probes((ctx, as) -> {
                    Map<String, Object> stale = oneOffAsRead(ctx, ONE_OFF);
                    seriesOf(stale).put("version", ((Number) seriesOf(stale).get("version")).intValue() + 1000);
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", ONE_OFF).json(stale)
                            .note("a stale revision"), 409, "STALE_VERSION");
                    Map<String, Object> renamed = oneOffAsRead(ctx, ONE_OFF);
                    seriesOf(renamed).put("seriesId", UNKNOWN);
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", ONE_OFF).json(renamed)
                            .note("the body's id is not the path's"), 400, "SERIES_ID_IMMUTABLE");
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", UNKNOWN)
                            .json(oneOffAsRead(ctx, ONE_OFF)).note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", SERIES_NW)
                            .json(oneOffAsRead(ctx, ONE_OFF)).note("a scheduled series"), 400, "SERIES_NOT_ONE_OFF");
                    refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", ONE_OFF).json(Map.of())
                            .note("empty body"), 400, "SERIES_INVALID");
                }));

        cases.add("oneOffWindow", SweepCase
                .of((ctx, row) -> fromDesk(row, SweepRequest.put(O_WINDOW).var("seriesId", ctx.row(row == null
                        ? ONE_OFF : row)).json(windowAsRead(ctx, (String) ctx.row(row == null ? ONE_OFF : row)))))
                .sweep((ctx, as) -> {
                    windowRoundTrip(ctx, as, NW);
                    refusedWith(ctx, as.name(), SweepRequest.put(O_WINDOW).var("seriesId", ONE_OFF_NM)
                                    .json(windowAsRead(ctx, ONE_OFF_NM)).note("another domain's one-off, unchanged"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> windowRoundTrip(ctx, as, domainOf(row)))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.put(O_WINDOW).var("seriesId", UNKNOWN)
                            .json(Map.of()).note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                    refusedWith(ctx, as.name(), SweepRequest.put(O_WINDOW).var("seriesId", SERIES_NW)
                            .json(Map.of()).note("a scheduled series"), 409, "NOT_ONE_OFF");
                    Map<String, Object> stale = new LinkedHashMap<>(windowAsRead(ctx, ONE_OFF));
                    stale.put("version", ((Number) stale.get("version")).intValue() + 1000);
                    refusedWith(ctx, as.name(), SweepRequest.put(O_WINDOW).var("seriesId", ONE_OFF).json(stale)
                            .note("a stale revision"), 409, "STALE_VERSION");
                }));
    }

    /** The domain a domain-row cell is called from: niord-nm for the from-nm rows, else niord-nw */
    static String domainOf(String row) {
        return SERIES_NM_FROM_NM.equals(row) || ONE_OFF_NM_FROM_NM.equals(row) ? NM : NW;
    }

    /** The request sent from the desk the row names (niord-nm for the from-nm rows) */
    static SweepRequest fromDesk(String row, SweepRequest request) {
        return NM.equals(domainOf(row)) ? request.domain(NM) : request;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> seriesOf(Map<String, Object> oneOff) {
        return (Map<String, Object>) oneOff.get("series");
    }

    /** The one-off as its read gives it, in the shape the save takes: the series, the active dot, the links */
    static Map<String, Object> oneOffAsRead(SweepContext ctx, String seriesId) {
        Map<String, Object> read = oneOff(ctx, seriesId).json().getMap("");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", read.get("series"));
        body.put("active", read.get("active"));
        body.put("links", read.get("links"));
        return body;
    }

    /** The one-off's public window as its read gives it, with the series revision */
    static Map<String, Object> windowAsRead(SweepContext ctx, String seriesId) {
        Map<String, Object> read = oneOff(ctx, seriesId).json().getMap("");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicFrom", read.get("publicFrom"));
        body.put("publicTo", read.get("publicTo"));
        body.put("version", seriesOf(read).get("version"));
        return body;
    }

    /** Checks the one-off read carries what the create body sent */
    @SuppressWarnings("unchecked")
    static void checkOneOff(SweepContext ctx, Map<String, Object> sent, SweepResponse read, String what) {
        if (!read.is2xx()) {
            return;
        }
        Map<String, Object> got = read.json().getMap("");
        Map<String, Object> sentSeries = seriesOf(sent);
        Map<String, Object> gotSeries = seriesOf(got);
        for (String key : List.of("seriesId", "categoryId", "domainId", "contentMode", "messagePublication")) {
            ctx.checkEquals(sentSeries.get(key), gotSeries.get(key), what + ": " + key, read);
        }
        ctx.checkEquals(sentSeries.get("languages"), gotSeries.get("languages"), what + ": the languages", read);
        Map<String, Map<String, Object>> sentDescs = byLang((List<Map<String, Object>>) sentSeries.get("descs"));
        Map<String, Map<String, Object>> gotDescs = byLang((List<Map<String, Object>>) gotSeries.get("descs"));
        sentDescs.forEach((lang, d) -> ctx.checkEquals(d.get("name"), gotDescs.getOrDefault(lang, Map.of())
                .get("name"), what + ": the " + lang + " name", read));
        ctx.checkEquals("ONE_OFF", gotSeries.get("kind"), what + ": the kind", read);
        ctx.checkEquals("NONE", gotSeries.get("cadence"), what + ": the cadence", read);
        ctx.checkEquals(sent.get("active"), got.get("active"), what + ": active", read);
        ctx.checkEquals("OPEN", got.get("issueStatus"), what + ": the issue's status", read);
        for (Map<String, Object> link : (List<Map<String, Object>>) sent.get("links")) {
            ctx.checkEquals(link.get("value"), valueFor((List<Map<String, Object>>) got.get("links"),
                    (String) link.get("lang")), what + ": the " + link.get("lang") + " link", read);
        }
        ctx.checkEquals(CATEGORY, got.get("categoryName"), what + ": the category's English name", read);
        ctx.checkEquals(Boolean.TRUE, got.get("categoryPublish"), what + ": the category's publish flag", read);
    }

    /** Creates a one-off as the identity at the domain, reads it back, removes it and reads it gone */
    private static void oneOffRoundTrip(SweepContext ctx, SweepIdentity as, String domain, String pattern) {
        String id = ctx.name("oneoff-" + ctx.unique());
        Map<String, Object> body = oneOffBody(id, domain);
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).query("lang", "en").json(body).domain(domain));
        if (!created.is2xx()) {
            return;
        }
        try {
            checkOneOff(ctx, body, created, "the create's answer");
            checkOneOff(ctx, body, oneOff(ctx, id), "the one-off read after the create");
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /**
     * The active dot, both ways: a link one-off created live in one call (series ACTIVE, its issue PUBLISHED), then
     * saved with the dot off, which retires the issue and asks for a reason. A RETIRED issue can be deleted (a
     * PUBLISHED one cannot), so the one-off is removed through the API afterwards.
     */
    private static void activeToggleRoundTrip(SweepContext ctx, SweepIdentity as) {
        String id = ctx.name("oneoff-live-" + ctx.unique());
        Map<String, Object> body = oneOffBody(id, NW);
        body.put("active", true);
        // S-13: a citable publication goes live only with a reference format in every language
        for (Map<String, Object> d : descsOf(seriesOf(body))) {
            d.put("messageReferenceFormat", "Sweep one-off (" + d.get("lang") + ")");
        }
        SweepResponse created = ctx.ok(as, SweepRequest.post(O_LIST).query("lang", "en").json(body));
        if (!created.is2xx()) {
            return;
        }
        try {
            SweepResponse live = oneOff(ctx, id);
            ctx.checkEquals(Boolean.TRUE, live.json().get("active"), "a live one-off's active dot", live);
            ctx.checkEquals("PUBLISHED", live.json().get("issueStatus"), "a live one-off's issue", live);
            ctx.checkEquals("ACTIVE", live.json().get("series.status"), "a live one-off's series", live);

            Map<String, Object> off = oneOffAsRead(ctx, id);
            off.put("active", false);
            refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", id).json(off)
                    .note("taking a one-off off the public list without a reason"), 400, "REASON_REQUIRED");
            off.put("reason", "The sweep took " + id + " off the public list");
            ctx.ok(as, SweepRequest.put(O_ONE).var("seriesId", id).json(off));
            SweepResponse retired = oneOff(ctx, id);
            ctx.checkEquals(Boolean.FALSE, retired.json().get("active"), "the active dot after it was turned off",
                    retired);
            ctx.checkEquals("RETIRED", retired.json().get("issueStatus"), "the issue after the dot was turned off",
                    retired);
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /** Makes a one-off as the identity at the domain, saves it changed, reads it back, removes it */
    @SuppressWarnings("unchecked")
    private static void oneOffUpdateRoundTrip(SweepContext ctx, SweepIdentity as, String domain) {
        String id = ctx.name("oneoff-update-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(O_LIST).json(oneOffBody(id, domain)).domain(domain)).is2xx()) {
            return;
        }
        try {
            Map<String, Object> changed = oneOffAsRead(ctx, id);
            Map<String, Object> series = seriesOf(changed);
            series.put("messagePublication", "INTERNAL");
            for (Map<String, Object> d : (List<Map<String, Object>>) series.get("descs")) {
                d.put("name", d.get("name") + "-renamed");
            }
            changed.put("links", new ArrayList<>(List.of(
                    link("da", "https://example.org/" + id + "/changed/da"),
                    link("en", "https://example.org/" + id + "/changed/en"))));
            ctx.ok(as, SweepRequest.put(O_ONE).var("seriesId", id).query("lang", "en").json(changed).domain(domain));
            checkOneOff(ctx, changed, oneOff(ctx, id), "the one-off read after the save");

            // The save's other domain check (assertMayAssign on the body): the desk that owns the one-off may not
            // move it into another domain by a save. The cells' refusals stop at the stored owner first
            Map<String, Object> moved = oneOffAsRead(ctx, id);
            String elsewhere = NW.equals(domain) ? NM : NW;
            seriesOf(moved).put("domainId", elsewhere);
            refusedWith(ctx, as.name(), SweepRequest.put(O_ONE).var("seriesId", id).json(moved).domain(domain)
                    .note("an own one-off moved into another domain by a save"), 403, "NOT_IN_DOMAIN");
            SweepResponse kept = oneOff(ctx, id);
            ctx.checkEquals(domain, kept.json().get("series.domainId"), "the one-off's owner after a refused move "
                    + "by a save", kept);
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /** Makes a one-off, sets and clears its public window, reading each back; removes it */
    private static void windowRoundTrip(SweepContext ctx, SweepIdentity as, String domain) {
        String id = ctx.name("oneoff-window-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(O_LIST).json(oneOffBody(id, domain)).domain(domain)).is2xx()) {
            return;
        }
        try {
            long from = FIRST_START + 10 * DAY;
            long to = FIRST_START + 40 * DAY;
            Map<String, Object> set = new LinkedHashMap<>(windowAsRead(ctx, id));
            set.put("publicFrom", from);
            set.put("publicTo", to);
            SweepResponse answer = ctx.ok(as, SweepRequest.put(O_WINDOW).var("seriesId", id).json(set).domain(domain));
            if (!answer.is2xx()) {
                return;
            }
            SweepResponse read = oneOff(ctx, id);
            ctx.checkEquals(from, millis(read.json().get("publicFrom")), "the public window's start", read);
            ctx.checkEquals(to, millis(read.json().get("publicTo")), "the public window's end", read);

            Map<String, Object> clear = new LinkedHashMap<>(windowAsRead(ctx, id));
            clear.put("publicFrom", null);
            clear.put("publicTo", null);
            ctx.ok(as, SweepRequest.put(O_WINDOW).var("seriesId", id).json(clear).domain(domain));
            SweepResponse cleared = oneOff(ctx, id);
            ctx.checkEquals(null, cleared.json().get("publicFrom"), "the cleared window's start", cleared);
            ctx.checkEquals(null, cleared.json().get("publicTo"), "the cleared window's end", cleared);
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Publication categories
    // ------------------------------------------------------------------------------------------- //

    private void categoryCases(SweepCases cases) {
        cases.add("categoriesAll", SweepCase
                .of((ctx, row) -> SweepRequest.get(C_ALL).query("lang", "en"))
                .variants((ctx, as) -> {
                    SweepResponse da = ctx.ok(as, SweepRequest.get(C_ALL).query("lang", "da"));
                    ctx.checkEquals(CATEGORY + "-da", da.json().get("find { it.categoryId == '" + CATEGORY
                            + "' }.descs[0].name"), "the category's Danish name with lang=da", da);
                    SweepResponse one = ctx.ok(as, SweepRequest.get(C_ALL).query("limit", 1));
                    ctx.checkEquals(1, one.json().getList("$").size(), "the number of categories with limit=1", one);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(C_ALL).query("limit", "abc")
                        .note("malformed limit"), Cell.status(400, "Failed to convert 'limit' with value: 'abc'"))));

        cases.add("categoryGet", SweepCase
                .of((ctx, row) -> SweepRequest.get(C_ONE).var("categoryId", CATEGORY).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, SweepRequest.get(C_ONE).var("categoryId", CATEGORY));
                    checkCategory(ctx, categoryBody(CATEGORY, 900, true), read, "the fixture category");
                })
                .variants((ctx, as) -> {
                    SweepResponse da = ctx.ok(as, SweepRequest.get(C_ONE).var("categoryId", CATEGORY)
                            .query("lang", "da"));
                    ctx.checkEquals(List.of(CATEGORY + "-da"), da.json().getList("descs.name"),
                            "the Danish name alone with lang=da", da);
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(C_ONE).var("categoryId", UNKNOWN)
                        .note("unknown-id probe"), 404, "CATEGORY_NOT_FOUND")));

        cases.add("categoryCreate", SweepCase
                .of((ctx, row) -> SweepRequest.post(C_CREATE)
                        .json(categoryBody(ctx.name("refused-" + ctx.unique()), 950, false)))
                .sweep((ctx, as) -> categoryRoundTrip(ctx, as, C_CREATE))
                .variants((ctx, as) -> categoryRoundTrip(ctx, as, C_CREATE_ALIAS))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.post(C_CREATE).json(Map.of()).note("empty body"),
                            400, "CATEGORY_INVALID");
                    refusedWith(ctx, as.name(), SweepRequest.post(C_CREATE).json(categoryBody(CATEGORY, 900, true))
                            .note("an existing categoryId"), 409, "CATEGORY_ID_TAKEN");
                }));

        cases.add("categoryUpdate", SweepCase
                .of((ctx, row) -> SweepRequest.put(C_ONE).var("categoryId", CATEGORY).json(ctx.ok("anonymous",
                        SweepRequest.get(C_ONE).var("categoryId", CATEGORY)).json().getMap("")))
                .sweep((ctx, as) -> {
                    String id = ctx.name("category-update-" + ctx.unique());
                    if (!ctx.ok(as, SweepRequest.post(C_CREATE).json(categoryBody(id, 950, false))).is2xx()) {
                        return;
                    }
                    try {
                        Map<String, Object> changed = categoryBody(id, 960, true);
                        // The Danish name is left out: the update removes a language the body does not send
                        changed.put("descs", new ArrayList<>(List.of(Map.of("lang", "en", "name", id + "-renamed",
                                "description", "Renamed by the sweep"))));
                        ctx.ok(as, SweepRequest.put(C_ONE).var("categoryId", id).json(changed));
                        checkCategory(ctx, changed, ctx.ok("anonymous", SweepRequest.get(C_ONE)
                                .var("categoryId", id)), "the category read after the update");
                    } finally {
                        ctx.check(removeCategory(ctx, id), "the category " + id + " is gone after its removal",
                                null);
                    }
                })
                .probes((ctx, as) -> {
                    Map<String, Object> renamed = categoryBody(UNKNOWN, 900, true);
                    refusedWith(ctx, as.name(), SweepRequest.put(C_ONE).var("categoryId", CATEGORY).json(renamed)
                            .note("the body's id is not the path's"), 400, "CATEGORY_ID_IMMUTABLE");
                    refusedWith(ctx, as.name(), SweepRequest.put(C_ONE).var("categoryId", UNKNOWN)
                            .json(Map.of("priority", 1)).note("unknown-id probe"), 404, "CATEGORY_NOT_FOUND");
                    refusedWith(ctx, as.name(), SweepRequest.put(C_ONE).var("categoryId", CATEGORY)
                            .json(Map.of("priority", "first")).note("a priority that is no number"), 400,
                            "CATEGORY_INVALID");
                }));

        cases.add("categoryDelete", SweepCase
                // A refused cell asks to remove a category that does not exist
                .of((ctx, row) -> SweepRequest.delete(C_ONE).var("categoryId", UNKNOWN))
                .sweep((ctx, as) -> {
                    String id = ctx.name("category-delete-" + ctx.unique());
                    if (!ctx.ok(as, SweepRequest.post(C_CREATE).json(categoryBody(id, 950, false))).is2xx()) {
                        return;
                    }
                    ctx.ok(as, SweepRequest.delete(C_ONE).var("categoryId", id));
                    refusedWith(ctx, "anonymous", SweepRequest.get(C_ONE).var("categoryId", id)
                            .note("read after the delete"), 404, "CATEGORY_NOT_FOUND");
                })
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.delete(C_ONE).var("categoryId", CATEGORY)
                            .note("a category the sweep's series are filed under"), 409, "CATEGORY_IN_USE");
                    refusedWith(ctx, as.name(), SweepRequest.delete(C_ONE).var("categoryId", UNKNOWN)
                            .note("unknown-id probe"), 404, "CATEGORY_NOT_FOUND");
                }));

        cases.add("categoriesUpload", SweepCase
                .of((ctx, row) -> SweepRequest.post(C_UPLOAD)
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String id = ctx.name("category-uploaded-" + ctx.unique());
                    Map<String, Object> body = categoryBody(id, 970, false);
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post(C_UPLOAD),
                            JSON.writeValueAsString(List.of(body)));
                    ctx.jobs().awaitClean(ctx, "publication-category-import", file);
                    try {
                        checkCategory(ctx, body, ctx.ok("anonymous", SweepRequest.get(C_ONE).var("categoryId", id)),
                                "the uploaded category");
                    } finally {
                        ctx.check(removeCategory(ctx, id), "the category " + id + " is gone after its removal",
                                null);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post(C_UPLOAD)
                        .file("file", ctx.name("broken") + ".json", "[{\"categoryId\":".getBytes(),
                                "application/json").note("a broken file"), Cell.status(400, "is not valid JSON"))));
    }

    /** Checks the category read carries every field the body sent */
    @SuppressWarnings("unchecked")
    static void checkCategory(SweepContext ctx, Map<String, Object> sent, SweepResponse read, String what) {
        if (!read.is2xx()) {
            return;
        }
        Map<String, Object> got = read.json().getMap("");
        for (String key : List.of("categoryId", "priority", "publish")) {
            ctx.checkEquals(norm(sent.get(key)), norm(got.get(key)), what + ": " + key, read);
        }
        Map<String, Map<String, Object>> sentDescs = byLang((List<Map<String, Object>>) sent.get("descs"));
        Map<String, Map<String, Object>> gotDescs = byLang((List<Map<String, Object>>) got.get("descs"));
        ctx.checkEquals(sentDescs.keySet(), gotDescs.keySet(), what + ": the description languages", read);
        sentDescs.forEach((lang, d) -> {
            for (String field : List.of("name", "description")) {
                ctx.checkEquals(d.get(field), gotDescs.getOrDefault(lang, Map.of()).get(field),
                        what + ": the " + lang + " " + field, read);
            }
        });
    }

    private static void categoryRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        String id = ctx.name("category-" + ctx.unique());
        Map<String, Object> body = categoryBody(id, 950, false);
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        try {
            checkCategory(ctx, body, ctx.ok("anonymous", SweepRequest.get(C_ONE).var("categoryId", id)),
                    "the category read after the create");
        } finally {
            ctx.check(removeCategory(ctx, id), "the category " + id + " is gone after its removal", null);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Publication series: reads
    // ------------------------------------------------------------------------------------------- //

    private void seriesReadCases(SweepCases cases) {
        cases.add("seriesSearch", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_SEARCH).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse all = ctx.ok(as, SweepRequest.get(S_SEARCH).query("lang", "en"));
                    absent(ctx, all, ONE_OFF, "the editors' list leaves out a DRAFT series");
                    // The domain parameter narrows by owner
                    SweepResponse nm = ctx.ok(as, SweepRequest.get(S_SEARCH).query("domain", NM));
                    ctx.expectRows(nm, null, SERIES_NM);
                    absent(ctx, nm, SERIES_NW, "the series owned by niord-nm leave out niord-nw's");
                })
                .variants((ctx, as) -> {
                    SweepResponse da = ctx.ok(as, SweepRequest.get(S_SEARCH).query("lang", "da"));
                    ctx.checkEquals(List.of("da"), da.json().getList("find { it.seriesId == '" + SERIES_NW
                            + "' }.descs.lang"), "the descriptions with lang=da", da);
                }));

        cases.add("seriesSearchDetails", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_SEARCH_DETAILS))
                .sweep((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get(S_SEARCH_DETAILS));
                    SweepResponse active = ctx.ok(as, SweepRequest.get(S_SEARCH_DETAILS).query("status", "ACTIVE")
                            .query("domain", NW));
                    ctx.expectRows(active, null, SERIES_NW);
                    absent(ctx, active, SERIES_NM, "the ACTIVE series of niord-nw leave out niord-nm's");
                    absent(ctx, active, ONE_OFF, "the ACTIVE series leave out a DRAFT one");
                    SweepResponse drafts = ctx.ok(as, SweepRequest.get(S_SEARCH_DETAILS).query("status", "DRAFT"));
                    ctx.expectRows(drafts, null, ONE_OFF);
                    absent(ctx, drafts, SERIES_NW, "the DRAFT series leave out an ACTIVE one");
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(S_SEARCH_DETAILS)
                        .query("status", "PAUSED").note("an unknown status"), 400, "INVALID_STATUS")));

        cases.add("seriesGet", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_GET).var("seriesId", ctx.row(row == null ? SERIES_NW : row)))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, SweepRequest.get(S_GET).var("seriesId", SERIES_NW));
                    ctx.checkEquals(CATEGORY, read.json().get("categoryId"), "the lean read's category", read);
                    ctx.checkEquals(null, read.json().get("descs[0].fileNamePattern"),
                            "the lean read's authoring pattern (none: the lean shape)", read);
                    // Reads are not scoped: another domain's series reads from this desk
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(S_GET).var("seriesId", SERIES_NM)), null, SERIES_NM);
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(S_GET).var("seriesId", UNKNOWN)
                        .note("unknown-id probe"), 404, "SERIES_NOT_FOUND")));

        cases.add("seriesEditable", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_EDITABLE).var("seriesId", SERIES_NW))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, SweepRequest.get(S_EDITABLE).var("seriesId", SERIES_NW));
                    checkSeries(ctx, seriesBody(SERIES_NW, NW), read, "the fixture series");
                    ctx.checkEquals("ACTIVE", read.json().get("status"), "the fixture series' status", read);
                    ctx.checkEquals(0, read.json().get("publishedIssueCount"), "the released issues", read);
                    checkSeries(ctx, seriesBody(SERIES_NM, NM), ctx.ok(as, SweepRequest.get(S_EDITABLE)
                            .var("seriesId", SERIES_NM)), "the fixture series of niord-nm");
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(S_EDITABLE)
                        .var("seriesId", UNKNOWN).note("unknown-id probe"), 404, "SERIES_NOT_FOUND")));

        cases.add("seriesByIds", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_BY_IDS).var("seriesIds", SERIES_NW + "," + SERIES_NM))
                .sweep((ctx, as) -> {
                    // Exactly the series asked for, in the order asked, an unknown id left out: a picker resolving
                    // its stored ids must not receive a series nobody named
                    SweepResponse two = ctx.ok(as, SweepRequest.get(S_BY_IDS)
                            .var("seriesIds", SERIES_NM + "," + UNKNOWN + "," + SERIES_NW));
                    if (two.is2xx()) {
                        ctx.checkEquals(List.of(SERIES_NM, SERIES_NW), two.json().getList("seriesId"),
                                "the series of the ids asked for, in their order", two);
                    }
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get(S_BY_IDS).var("seriesIds", UNKNOWN)
                            .note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown id", unknown);
                }));

        cases.add("seriesExport", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_EXPORT))
                .sweep((ctx, as) -> {
                    SweepResponse export = ctx.ok(as, SweepRequest.get(S_EXPORT));
                    if (export.is2xx()) {
                        checkSeries(ctx, seriesBody(SERIES_NW, NW), export.json().getMap("find { it.seriesId == '"
                                + SERIES_NW + "' }"), export, "the export of the fixture series");
                    }
                }));

        cases.add("seriesHistory", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_HISTORY).var("seriesId", SERIES_NW))
                .sweep((ctx, as) -> {
                    SweepResponse history = ctx.ok(as, SweepRequest.get(S_HISTORY).var("seriesId", SERIES_NW));
                    ctx.check(history.json().getList("action").contains("SERIES_ACTIVATED"),
                            "the fixture series' history holds its activation", history);
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(S_HISTORY)
                        .var("seriesId", UNKNOWN).note("unknown-id probe"), 404, "SERIES_NOT_FOUND")));

        cases.add("seriesIssueDraft", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_ISSUE_DRAFT).var("seriesId", SERIES_NW).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse draft = ctx.ok(as, SweepRequest.get(S_ISSUE_DRAFT).var("seriesId", SERIES_NW)
                            .query("lang", "en"));
                    if (!draft.is2xx()) {
                        return;
                    }
                    ctx.checkEquals(SERIES_NW, draft.json().get("seriesId"), "the draft's series", draft);
                    // The fixture series has no issue: the draft is the head of the chain, at its declared start
                    ctx.checkEquals(null, draft.json().get("chainedFromPublicId"),
                            "the draft of a series without issues chains off nothing", draft);
                    ctx.checkEquals(FIRST_START, millis(draft.json().get("intervalFrom")),
                            "the head of the chain opens at the series' first issue start", draft);
                })
                .variants((ctx, as) -> ctx.ok(as, SweepRequest.get(S_ISSUE_DRAFT).var("seriesId", SERIES_NW)
                        .query("intervalFrom", FIRST_START).query("intervalTo", FIRST_START + 7 * DAY)))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.get(S_ISSUE_DRAFT).var("seriesId", UNKNOWN)
                            .note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                    ctx.call(as.name(), SweepRequest.get(S_ISSUE_DRAFT).var("seriesId", SERIES_NW)
                                    .query("intervalFrom", "monday").note("malformed intervalFrom"),
                            Cell.status(400, "Failed to convert 'intervalFrom' with value: 'monday'"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Publication series: writes
    // ------------------------------------------------------------------------------------------- //

    private void seriesWriteCases(SweepCases cases) {
        cases.add("seriesCreate", SweepCase
                .of((ctx, row) -> SweepRequest.post(S_CREATE)
                        .json(seriesBody(ctx.name("refused-" + ctx.unique()), SERIES_NM.equals(row) ? NM : NW)))
                .sweep((ctx, as) -> {
                    seriesRoundTrip(ctx, as, NW, S_CREATE);
                    refusedWith(ctx, as.name(), SweepRequest.post(S_CREATE)
                                    .json(seriesBody(ctx.name("refused-" + ctx.unique()), NM))
                                    .note("a series authored into another domain"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> seriesRoundTrip(ctx, as, domainOf(row), S_CREATE))
                .variants((ctx, as) -> seriesRoundTrip(ctx, as, NW, S_CREATE_ALIAS))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.post(S_CREATE).json(Map.of()).note("empty body"),
                            400, "SERIES_INVALID");
                    refusedWith(ctx, as.name(), SweepRequest.post(S_CREATE).json(seriesBody(SERIES_NW, NW))
                            .note("an existing seriesId"), 409, "SERIES_ID_TAKEN");
                    Map<String, Object> uncategorised = seriesBody(ctx.name("probe"), NW);
                    uncategorised.put("categoryId", UNKNOWN);
                    refusedWith(ctx, as.name(), SweepRequest.post(S_CREATE).json(uncategorised)
                            .note("an unknown category"), 404, "CATEGORY_NOT_FOUND");
                    Map<String, Object> dangling = seriesBody(ctx.name("probe"), NW);
                    dangling.put("criteria", criteria(node("messageSeries", UNKNOWN)));
                    refusedWith(ctx, as.name(), SweepRequest.post(S_CREATE).json(dangling)
                            .note("a criteria operand that names nothing"), 400, "SERIES_INVALID");
                }));

        cases.add("seriesUpdate", SweepCase
                .of((ctx, row) -> {
                    String id = (String) ctx.row(row == null ? SERIES_NW : row);
                    return fromDesk(row, SweepRequest.put(S_ONE).var("seriesId", id)
                            .json(editableSeries(ctx, id).json().getMap("")));
                })
                .sweep((ctx, as) -> {
                    seriesUpdateRoundTrip(ctx, as, NW);
                    refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", SERIES_NM)
                                    .json(editableSeries(ctx, SERIES_NM).json().getMap(""))
                                    .note("another domain's series, unchanged"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> seriesUpdateRoundTrip(ctx, as, domainOf(row)))
                .probes((ctx, as) -> {
                    Map<String, Object> stale = editableSeries(ctx, SERIES_NW).json().getMap("");
                    stale.put("version", ((Number) stale.get("version")).intValue() + 1000);
                    refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", SERIES_NW).json(stale)
                            .note("a stale revision"), 409, "STALE_VERSION");
                    Map<String, Object> renamed = editableSeries(ctx, SERIES_NW).json().getMap("");
                    renamed.put("seriesId", UNKNOWN);
                    refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", SERIES_NW).json(renamed)
                            .note("the body's id is not the path's"), 400, "SERIES_ID_IMMUTABLE");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", UNKNOWN)
                            .json(Map.of("languages", List.of("en"))).note("unknown-id probe"), 404,
                            "SERIES_NOT_FOUND");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", SERIES_NW)
                            .json("null").note("no body"), 400, "SERIES_INVALID");
                }));

        cases.add("seriesStatus", SweepCase
                .of((ctx, row) -> {
                    String id = (String) ctx.row(row == null ? SERIES_NW : row);
                    return fromDesk(row, SweepRequest.put(S_STATUS).var("seriesId", id)
                            .query("version", seriesVersion(ctx, id)).json("\"ACTIVE\""));
                })
                .sweep((ctx, as) -> {
                    statusRoundTrip(ctx, as, NW);
                    refusedWith(ctx, as.name(), SweepRequest.put(S_STATUS).var("seriesId", SERIES_NM)
                                    .json("\"ACTIVE\"").note("another domain's series"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> statusRoundTrip(ctx, as, domainOf(row)))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.put(S_STATUS).var("seriesId", SERIES_NW)
                            .json("\"PAUSED\"").note("an unknown status"), 400, "INVALID_STATUS");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_STATUS).var("seriesId", SERIES_NW)
                            .query("version", seriesVersion(ctx, SERIES_NW) + 1000).json("\"ACTIVE\"")
                            .note("a stale revision"), 409, "STALE_VERSION");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_STATUS).var("seriesId", UNKNOWN)
                            .json("\"ACTIVE\"").note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                    ctx.call(as.name(), SweepRequest.put(S_STATUS).var("seriesId", SERIES_NW)
                            .query("version", "one").json("\"ACTIVE\"").note("malformed version"),
                            Cell.status(400, "Failed to convert 'version' with value: 'one'"));
                }));

        cases.add("seriesOwner", SweepCase
                // A refused cell names no reason, so a gate that let it through would refuse it for that
                // (REASON_REQUIRED) and move nothing
                .of((ctx, row) -> {
                    String id = (String) ctx.row(row == null ? SERIES_NW : row);
                    String target = SERIES_NW.equals(id) ? NM : NW;
                    return fromDesk(row, SweepRequest.put(S_OWNER).var("seriesId", id)
                            .json(Map.of("domainId", target)));
                })
                .as("sysadmin")
                .sweep((ctx, as) -> {
                    String id = ctx.name("series-owner-" + ctx.unique());
                    if (!ctx.ok(as, SweepRequest.post(S_CREATE).json(seriesBody(id, NW))).is2xx()) {
                        return;
                    }
                    try {
                        // The codes of the cells' refusals: the realm's admin is no admin in niord-nm, and
                        // adminElsewhere (at niord-nm) is none in niord-nw
                        refusedWith(ctx, "admin", SweepRequest.put(S_OWNER).var("seriesId", id)
                                        .json(Map.of("domainId", NM, "reason", "sweep: not an admin there"))
                                        .note("a transfer by an admin of the source domain only"),
                                403, "NOT_IN_DOMAIN");
                        refusedWith(ctx, "adminElsewhere", SweepRequest.put(S_OWNER).var("seriesId", SERIES_NM)
                                        .json(Map.of("domainId", NW, "reason", "sweep: not an admin there"))
                                        .domain(NM).note("a transfer by an admin of the source domain only"),
                                403, "NOT_IN_DOMAIN");
                        refusedWith(ctx, as.name(), SweepRequest.put(S_OWNER).var("seriesId", id)
                                .json(Map.of("domainId", NM)).note("a transfer without a reason"), 400,
                                "REASON_REQUIRED");

                        String reason = "The sweep moved " + id + " to niord-nm";
                        SweepResponse moved = ctx.ok(as, SweepRequest.put(S_OWNER).var("seriesId", id)
                                .json(Map.of("domainId", NM, "reason", reason,
                                        "version", seriesVersion(ctx, id))));
                        ctx.checkEquals(NM, moved.json().get("domainId"), "the transfer's answer: the owner",
                                moved);
                        SweepResponse read = editableSeries(ctx, id);
                        ctx.checkEquals(NM, read.json().get("domainId"), "the owner read after the transfer", read);
                        SweepResponse history = ctx.ok("admin", SweepRequest.get(S_HISTORY).var("seriesId", id));
                        ctx.check(history.json().getList("reason").contains(reason),
                                "the series' history holds the transfer's reason", history);
                        // The old desk may not write it any more
                        refusedWith(ctx, "admin", SweepRequest.put(S_ONE).var("seriesId", id)
                                        .json(read.json().getMap("")).note("the old desk saving the moved series"),
                                403, "NOT_IN_DOMAIN");
                    } finally {
                        removeSeriesAndReadGone(ctx, id);
                    }
                })
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.put(S_OWNER).var("seriesId", SERIES_NW)
                            .json(Map.of("domainId", UNKNOWN, "reason", "probe")).note("an unknown domain"), 400,
                            "SERIES_INVALID");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_OWNER).var("seriesId", SERIES_NW)
                            .json(Map.of("domainId", NW, "reason", "probe")).note("the domain it is in"), 400,
                            "SERIES_INVALID");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_OWNER).var("seriesId", SERIES_NW)
                            .json(Map.of("reason", "probe")).note("no target domain"), 400, "SERIES_INVALID");
                    refusedWith(ctx, as.name(), SweepRequest.put(S_OWNER).var("seriesId", UNKNOWN)
                            .json(Map.of("domainId", NM, "reason", "probe")).note("unknown-id probe"), 404,
                            "SERIES_NOT_FOUND");
                }));

        cases.add("seriesDelete", SweepCase
                // A refused cell sends a stale revision, so a gate that let it through would refuse it for that
                .of((ctx, row) -> fromDesk(row, SweepRequest.delete(S_ONE).var("seriesId", ctx.row(row == null
                        ? SERIES_NW : row)).query("version", -1)))
                .sweep((ctx, as) -> {
                    deleteRoundTrip(ctx, as, NW);
                    refusedWith(ctx, as.name(), SweepRequest.delete(S_ONE).var("seriesId", SERIES_NM)
                                    .query("version", -1).note("another domain's series"),
                            403, "NOT_IN_DOMAIN");
                })
                .allowed((ctx, as, row) -> deleteRoundTrip(ctx, as, domainOf(row)))
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.delete(S_ONE).var("seriesId", SERIES_NW)
                            .query("version", -1).note("a stale revision"), 409, "STALE_VERSION");
                    refusedWith(ctx, as.name(), SweepRequest.delete(S_ONE).var("seriesId", ONE_OFF)
                            .note("a series with an issue"), 409, "SERIES_HAS_ISSUES");
                    refusedWith(ctx, as.name(), SweepRequest.delete(S_ONE).var("seriesId", UNKNOWN)
                            .note("unknown-id probe"), 404, "SERIES_NOT_FOUND");
                }));

        cases.add("seriesUpload", SweepCase
                .of((ctx, row) -> SweepRequest.post(S_UPLOAD)
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String id = ctx.name("series-uploaded-" + ctx.unique());
                    Map<String, Object> body = seriesBody(id, NW);
                    // A row aimed at another domain's series is skipped: the import keeps the domain rule
                    Map<String, Object> takeover = editableSeries(ctx, SERIES_NM).json().getMap("");
                    String nameBefore = String.valueOf(byLang(descsOf(takeover)).get("en").get("name"));
                    byLang(descsOf(takeover)).get("en").put("name", id + "-taken-over");
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post(S_UPLOAD),
                            JSON.writeValueAsString(List.of(body, takeover)));
                    SweepJobs.JobEnd end = ctx.jobs().awaitClean(ctx, "publication-series-import", file);
                    try {
                        if (end != null) {
                            ctx.check(end.log().contains("Skipping series " + SERIES_NM),
                                    "the job's log says it skipped the row aimed at niord-nm's series", null);
                        }
                        SweepResponse read = ctx.ok("admin", SweepRequest.get(S_EDITABLE).var("seriesId", id));
                        if (read.is2xx()) {
                            checkSeries(ctx, body, read, "the uploaded series");
                            ctx.checkEquals("DRAFT", read.json().get("status"), "the uploaded series' status", read);
                        }
                        SweepResponse nm = editableSeries(ctx, SERIES_NM);
                        ctx.checkEquals(nameBefore, nm.json().get("descs.find { it.lang == 'en' }.name"),
                                "niord-nm's series after an upload from niord-nw that names it", nm);
                    } finally {
                        removeSeriesAndReadGone(ctx, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post(S_UPLOAD)
                        .file("file", ctx.name("broken") + ".json", "[{\"seriesId\":".getBytes(),
                                "application/json").note("a broken file"), Cell.status(400, "is not valid JSON"))));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> descsOf(Map<String, Object> series) {
        return (List<Map<String, Object>>) series.get("descs");
    }

    /** Creates a series as the identity at the domain with every list set, reads it back, removes it */
    private static void seriesRoundTrip(SweepContext ctx, SweepIdentity as, String domain, String pattern) {
        String id = ctx.name("series-" + ctx.unique());
        Map<String, Object> body = seriesBody(id, domain);
        body.put("availability", "SELECTED_DOMAINS");
        body.put("availableDomainIds", new ArrayList<>(List.of("niord-fa", RECORDING_DOMAIN)));
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body).domain(domain));
        if (!created.is2xx()) {
            return;
        }
        try {
            ctx.checkEquals("DRAFT", created.json().get("status"), "a series is created DRAFT", created);
            checkSeries(ctx, body, created, "the create's answer");
            checkSeries(ctx, body, editableSeries(ctx, id), "the series read after the create");
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /** Makes a series as the identity at the domain, saves every list changed, reads it back, removes it */
    @SuppressWarnings("unchecked")
    private static void seriesUpdateRoundTrip(SweepContext ctx, SweepIdentity as, String domain) {
        String id = ctx.name("series-update-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(S_CREATE).json(seriesBody(id, domain)).domain(domain)).is2xx()) {
            return;
        }
        try {
            Map<String, Object> changed = editableSeries(ctx, id).json().getMap("");
            changed.put("sortOrder", 11);
            changed.put("languages", new ArrayList<>(List.of("en", "da")));
            changed.put("availability", "SELECTED_DOMAINS");
            changed.put("availableDomainIds", new ArrayList<>(List.of("niord-fa")));
            changed.put("pageOrientation", "LANDSCAPE");
            changed.put("mapThumbnails", true);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("subtitle", "Changed by the sweep");
            params.put("pageNumbers", false);
            changed.put("reportParams", params);
            Map<String, Object> criteria = (Map<String, Object>) changed.get("criteria");
            ((List<Object>) criteria.get("criteria")).add(node("messageType", "LOCAL_WARNING", "COASTAL_WARNING"));
            // Every other field the save copies, so that one it drops shows (releaseMode and nextIssueCreation have
            // one value each; the numbering, cadence, kind and content mode are bound to the weekly shape)
            changed.put("cutoffDefault", "PERIOD_END");
            changed.put("nominalCutoffDay", "FRIDAY");
            changed.put("nominalCutoffTime", "09:30");
            changed.put("timeRelation", "IN_FORCE_AT_CUTOFF");
            changed.put("aliveAtCutoff", false);
            changed.put("firstIssueStartsAt", FIRST_START + 7 * DAY);
            changed.put("pageSize", "A3");
            changed.put("messageSortBy", "DATE");
            changed.put("messageSortOrder", "DESC");
            changed.put("messagePublication", "INTERNAL");
            changed.put("languageSpecific", false);
            for (Map<String, Object> d : descsOf(changed)) {
                String lang = String.valueOf(d.get("lang"));
                d.put("name", id + "-renamed-" + lang);
                d.put("nameSuggestionPattern", "SWC ${week}/${year} " + lang);
                d.put("fileNamePattern", "sweep-changed-${year}-${week-2-digits}-" + lang);
                d.put("messageReferenceFormat", "SWC ${week}/${year} " + lang);
                d.put("linkPattern", "https://example.org/sweep-publications/changed/" + lang + "/${year}/${week}");
            }
            ctx.ok(as, SweepRequest.put(S_ONE).var("seriesId", id).json(changed).domain(domain));
            checkSeries(ctx, changed, editableSeries(ctx, id), "the series read after the update");

            // The save's other domain check (assertMayAssign on the body): the desk that owns the series may not
            // move it into another domain by a save (that is the owner transfer's job). The cells' refusals stop at
            // the stored owner first
            Map<String, Object> moved = editableSeries(ctx, id).json().getMap("");
            moved.put("domainId", NW.equals(domain) ? NM : NW);
            refusedWith(ctx, as.name(), SweepRequest.put(S_ONE).var("seriesId", id).json(moved).domain(domain)
                    .note("an own series moved into another domain by a save"), 403, "NOT_IN_DOMAIN");
            SweepResponse kept = editableSeries(ctx, id);
            ctx.checkEquals(domain, kept.json().get("domainId"), "the series' owner after a refused move by a save",
                    kept);
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /** Makes a series, activates and retires it (with and without a reason), reads each step back; removes it */
    private static void statusRoundTrip(SweepContext ctx, SweepIdentity as, String domain) {
        String id = ctx.name("series-status-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(S_CREATE).json(seriesBody(id, domain)).domain(domain)).is2xx()) {
            return;
        }
        try {
            ctx.ok(as, SweepRequest.put(S_STATUS).var("seriesId", id).query("version", seriesVersion(ctx, id))
                    .json("\"ACTIVE\"").domain(domain));
            SweepResponse active = editableSeries(ctx, id);
            ctx.checkEquals("ACTIVE", active.json().get("status"), "the status after the activation", active);
            refusedWith(ctx, as.name(), SweepRequest.put(S_STATUS).var("seriesId", id).json("\"RETIRED\"")
                    .domain(domain).note("leaving ACTIVE without a reason"), 400, "REASON_REQUIRED");
            String reason = "The sweep retired " + id;
            ctx.ok(as, SweepRequest.put(S_STATUS).var("seriesId", id).query("reason", reason)
                    .query("version", seriesVersion(ctx, id)).json("\"RETIRED\"").domain(domain));
            SweepResponse retired = editableSeries(ctx, id);
            ctx.checkEquals("RETIRED", retired.json().get("status"), "the status after the retire", retired);
            SweepResponse history = ctx.ok("admin", SweepRequest.get(S_HISTORY).var("seriesId", id));
            // The history answers newest first; the order of writing is the order of the ids
            List<Map<String, Object>> entries = new ArrayList<>(history.json().getList("$"));
            entries.sort(java.util.Comparator.comparingLong(h -> ((Number) h.get("id")).longValue()));
            ctx.checkEquals(List.of("SERIES_ACTIVATED", "SERIES_RETIRED"),
                    entries.stream().map(h -> h.get("action")).toList(),
                    "the series' history in the order written (one entry per transition)", history);
            ctx.check(history.json().getList("reason").contains(reason), "the history holds the retire's reason",
                    history);
        } finally {
            removeSeriesAndReadGone(ctx, id);
        }
    }

    /** Makes a series as the identity at the domain, deletes it with its revision, reads it gone */
    private static void deleteRoundTrip(SweepContext ctx, SweepIdentity as, String domain) {
        String id = ctx.name("series-delete-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(S_CREATE).json(seriesBody(id, domain)).domain(domain)).is2xx()) {
            return;
        }
        try {
            ctx.ok(as, SweepRequest.delete(S_ONE).var("seriesId", id).query("version", seriesVersion(ctx, id))
                    .domain(domain));
            refusedWith(ctx, "admin", SweepRequest.get(S_EDITABLE).var("seriesId", id).note("read after the delete"),
                    404, "SERIES_NOT_FOUND");
        } finally {
            removeSeries(ctx, id);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Publication series: the tools
    // ------------------------------------------------------------------------------------------- //

    private void seriesToolCases(SweepCases cases) {
        cases.add("seriesNewTemplate", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_NEW_TEMPLATE))
                .sweep((ctx, as) -> {
                    SweepResponse template = ctx.ok(as, SweepRequest.get(S_NEW_TEMPLATE));
                    ctx.checkEquals("DRAFT", template.json().get("status"), "the template's status", template);
                    ctx.checkEquals("NONE", template.json().get("cadence"), "the template's cadence", template);
                    ctx.checkEquals(template.json().getList("languages"), template.json().getList("descs.lang"),
                            "one description per language the template declares", template);
                    ctx.checkEquals(0, template.json().get("publishedIssueCount"), "the template's released issues",
                            template);
                }));

        cases.add("seriesCopyTemplate", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_COPY).var("seriesId", SERIES_NW))
                .sweep((ctx, as) -> {
                    SweepResponse copy = ctx.ok(as, SweepRequest.get(S_COPY).var("seriesId", SERIES_NW));
                    ctx.checkEquals(null, copy.json().get("seriesId"), "the copy's seriesId (none)", copy);
                    ctx.checkEquals(null, copy.json().get("domainId"), "the copy's owner (none)", copy);
                    ctx.checkEquals("DRAFT", copy.json().get("status"), "the copy's status", copy);
                    ctx.checkEquals(norm(seriesBody(SERIES_NW, NW).get("criteria")),
                            norm(copy.json().get("criteria")), "the copy's criteria document", copy);
                })
                .probes((ctx, as) -> refusedWith(ctx, as.name(), SweepRequest.get(S_COPY).var("seriesId", UNKNOWN)
                        .note("unknown-id probe"), 404, "SERIES_NOT_FOUND")));

        cases.add("seriesResolvePreview", SweepCase
                .of((ctx, row) -> SweepRequest.post(S_PREVIEW).query("lang", "en").json(previewBody()))
                .sweep((ctx, as) -> {
                    SweepResponse preview = ctx.ok(as, SweepRequest.post(S_PREVIEW).query("lang", "en")
                            .json(previewBody()));
                    ctx.checkEquals("PROBE", preview.json().get("source"), "the preview's source", preview);
                    ctx.check(preview.json().get("total") instanceof Number, "the preview counts its members",
                            preview);
                })
                .probes((ctx, as) -> {
                    refusedWith(ctx, as.name(), SweepRequest.post(S_PREVIEW).json(Map.of()).note("empty body"),
                            400, "CRITERIA_INVALID");
                    Map<String, Object> noCutoff = previewBody();
                    noCutoff.remove("cutoff");
                    refusedWith(ctx, as.name(), SweepRequest.post(S_PREVIEW).json(noCutoff).note("no cut-off"),
                            400, "CRITERIA_INVALID");
                    Map<String, Object> relation = previewBody();
                    relation.put("timeRelation", "SOMETIME");
                    refusedWith(ctx, as.name(), SweepRequest.post(S_PREVIEW).json(relation)
                            .note("an unknown time relation"), 400, "INVALID_FILTER_VALUE");
                }));

        cases.add("seriesValidate", SweepCase
                .of((ctx, row) -> SweepRequest.post(S_VALIDATE).json(seriesBody(SERIES_NW, NW)))
                .sweep((ctx, as) -> {
                    SweepResponse valid = ctx.ok(as, SweepRequest.post(S_VALIDATE).json(seriesBody(SERIES_NW, NW)));
                    ctx.checkEquals(List.of(), valid.json().getList("$"), "the rules a valid series fails", valid);
                    Map<String, Object> broken = seriesBody(ctx.name("broken"), NW);
                    broken.put("cadence", "MONTHLY");
                    SweepResponse errors = ctx.ok(as, SweepRequest.post(S_VALIDATE).json(broken));
                    ctx.check(errors.json().getList("rule").contains("S-6"),
                            "a monthly series with no cut-off day fails S-6", errors);
                }));

        cases.add("seriesNameTokens", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_TOKENS))
                .sweep((ctx, as) -> {
                    SweepResponse tokens = ctx.ok(as, SweepRequest.get(S_TOKENS));
                    // The token names, without the ${...} a pattern wraps them in
                    ctx.check(tokens.json().getList("$").containsAll(List.of("week", "year", "edition")),
                            "the vocabulary holds week, year and edition", tokens);
                }));

        cases.add("seriesImportDryRun", SweepCase
                .of((ctx, row) -> SweepRequest.post(S_IMPORT_DRY))
                .sweep((ctx, as) -> {
                    SweepResponse report = ctx.ok(as, SweepRequest.post(S_IMPORT_DRY));
                    ctx.checkEquals(Boolean.TRUE, report.json().get("dryRun"), "the report is a dry run's", report);
                    ctx.check(report.json().get("wouldSucceed") instanceof Boolean, "the report says whether the "
                            + "import would succeed", report);
                }));

        // Global: refused cells only. The run imports the whole legacy estate into every domain; the undo deletes
        // every imported series. Their behaviour is the services' (see the record)
        cases.add("seriesImportLegacy", SweepCase.of((ctx, row) -> SweepRequest.post(S_IMPORT)));
        cases.add("seriesUndoImport", SweepCase.of((ctx, row) -> SweepRequest.delete(S_IMPORT)));

        cases.add("seriesImportCheck", SweepCase
                .of((ctx, row) -> SweepRequest.get(S_IMPORT_CHECK))
                .sweep((ctx, as) -> {
                    // One call: the check reads the whole estate (seconds)
                    SweepResponse check = ctx.ok(as, SweepRequest.get(S_IMPORT_CHECK));
                    if (check.is2xx()) {
                        ctx.checkEquals(List.of("clear", "counts", "violations", "triggerAudit", "series"),
                                new ArrayList<>(check.json().getMap("").keySet()), "the check's parts", check);
                    }
                }));
    }

    static Map<String, Object> previewBody() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("criteria", seriesBody(SERIES_NW, NW).get("criteria"));
        probe.put("intervalFrom", FIRST_START);
        probe.put("cutoff", FIRST_START + 7 * DAY);
        probe.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        probe.put("aliveAtCutoff", true);
        return probe;
    }

    // ------------------------------------------------------------------------------------------- //
    // Legacy publications: reads
    // ------------------------------------------------------------------------------------------- //

    private void publicationReadCases(SweepCases cases) {
        cases.add("publicationsSearch", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_SEARCH).query("title", PUBLICATION).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepRequest search = SweepRequest.get(P_SEARCH).query("title", PUBLICATION).query("lang", "en");
                    SweepResponse anonymous = ctx.ok(as, search);
                    absent(ctx, anonymous, PUBLICATION_DRAFT, "the public tier leaves out a DRAFT publication");
                    // The public tier overwrites the statuses asked for
                    SweepResponse widened = ctx.ok(as, SweepRequest.get(P_SEARCH).query("title", PUBLICATION)
                            .query("status", "DRAFT"));
                    absent(ctx, widened, PUBLICATION_DRAFT, "the public tier leaves out a DRAFT publication asked "
                            + "for by status");
                    SweepResponse user = ctx.ok("user", SweepRequest.get(P_SEARCH).query("title", PUBLICATION));
                    ctx.expectRows(user, null, PUBLICATION, PUBLICATION_DRAFT);
                    SweepResponse noHeader = ctx.ok("user", SweepRequest.get(P_SEARCH).query("title", PUBLICATION)
                            .domain(null));
                    absent(ctx, noHeader, PUBLICATION_DRAFT, "a token without the NiordDomain header reads the "
                            + "public tier");
                })
                .variants((ctx, as) -> {
                    SweepResponse paged = ctx.ok("user", SweepRequest.get(P_SEARCH).query("title", PUBLICATION)
                            .query("maxSize", 1).query("page", 1));
                    ctx.checkEquals(1, paged.json().getList("data").size(), "the rows of page 1 with maxSize=1",
                            paged);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.get(P_SEARCH).query("maxSize", "many").note("malformed maxSize"),
                            Cell.status(400, "Failed to convert 'maxSize' with value: 'many'"));
                    ctx.call(as.name(), SweepRequest.get(P_SEARCH).query("status", "SOMETIME")
                            .note("an unknown status"), Cell.status(400, "Failed to convert 'status'"));
                }));

        cases.add("publicationsSearchDetails", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_SEARCH_DETAILS).query("title", "sweep-publications-")
                        .query("maxSize", 1000))
                .sweep((ctx, as) -> {
                    SweepResponse all = ctx.ok(as, SweepRequest.get(P_SEARCH_DETAILS)
                            .query("title", "sweep-publications-").query("maxSize", 1000));
                    ctx.expectRows(all, null, PUBLICATION, PUBLICATION_DRAFT, RECORDING);
                    // The domain parameter: the publications of the domain and of no domain (the legacy rule)
                    SweepResponse nw = ctx.ok(as, SweepRequest.get(P_SEARCH_DETAILS)
                            .query("title", "sweep-publications-").query("domain", NW).query("maxSize", 1000));
                    ctx.expectRows(nw, null, PUBLICATION, PUBLICATION_DRAFT);
                    absent(ctx, nw, RECORDING, "the publications of niord-nw leave out niord-almanac's");
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(P_SEARCH_DETAILS).query("title", "sweep-publications-")
                            .query("domain", RECORDING_DOMAIN).query("maxSize", 1000)), null, RECORDING);
                    // The status parameter
                    SweepResponse drafts = ctx.ok(as, SweepRequest.get(P_SEARCH_DETAILS)
                            .query("title", "sweep-publications-").query("status", "DRAFT").query("maxSize", 1000));
                    ctx.expectRows(drafts, null, PUBLICATION_DRAFT);
                    absent(ctx, drafts, PUBLICATION, "the DRAFT publications leave out an ACTIVE one");
                    absent(ctx, drafts, RECORDING, "the DRAFT publications leave out a RECORDING one");
                }));

        cases.add("publicationsAll", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_ALL).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse anonymous = ctx.ok(as, SweepRequest.get(P_ALL).query("lang", "en"));
                    absent(ctx, anonymous, PUBLICATION_DRAFT, "the public tier leaves out a DRAFT publication");
                    ctx.expectRows(ctx.ok("user", SweepRequest.get(P_ALL)), null, PUBLICATION, PUBLICATION_DRAFT);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(P_ALL).query("maxSize", "many")
                        .note("malformed maxSize"), Cell.status(400, "Failed to convert 'maxSize' with value: 'many'"))));

        cases.add("publicationsGet", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_GET).var("publicationIds", ctx.row(PUBLICATION_DRAFT) + ","
                        + ctx.row(PUBLICATION)))
                .sweep((ctx, as) -> {
                    SweepRequest both = SweepRequest.get(P_GET).var("publicationIds", ctx.row(PUBLICATION_DRAFT) + ","
                            + ctx.row(PUBLICATION));
                    String draftLink = "find { it.publicationId == '" + ctx.row(PUBLICATION_DRAFT)
                            + "' }.descs[0].link";
                    String activeLink = "find { it.publicationId == '" + ctx.row(PUBLICATION) + "' }.descs[0].link";
                    SweepResponse anonymous = ctx.ok(as, both);
                    ctx.checkEquals(null, anonymous.json().get(draftLink),
                            "the address of an unreleased document, read anonymously (withheld)", anonymous);
                    ctx.checkEquals("https://example.org/" + PUBLICATION + "/en", anonymous.json().get(activeLink),
                            "the address of a released document, read anonymously", anonymous);
                    SweepResponse user = ctx.ok("user", both);
                    ctx.checkEquals("https://example.org/" + PUBLICATION_DRAFT + "/en", user.json().get(draftLink),
                            "the address of an unreleased document, read by a user", user);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get(P_GET)
                            .var("publicationIds", UNKNOWN_PUBLICATION).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown id", unknown);
                }));

        cases.add("publicationEditable", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_EDITABLE).var("publicationId", ctx.row(PUBLICATION)))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, SweepRequest.get(P_EDITABLE)
                            .var("publicationId", ctx.row(PUBLICATION)));
                    checkPublication(ctx, publicationBody((String) ctx.row(PUBLICATION), PUBLICATION, "LINK", "ACTIVE",
                            null), read, "the fixture publication");
                    ctx.check(String.valueOf(read.json().getString("editRepoPath")).startsWith("temp/"),
                            "the editable read names a temporary folder to edit in", read);
                    SweepResponse recording = ctx.ok(as, SweepRequest.get(P_EDITABLE)
                            .var("publicationId", ctx.row(RECORDING)));
                    checkPublication(ctx, publicationBody((String) ctx.row(RECORDING), RECORDING, "MESSAGE_REPORT",
                            "RECORDING", (String) ctx.row(TAG)), recording, "the fixture recording publication");
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(P_EDITABLE)
                        .var("publicationId", UNKNOWN_PUBLICATION).note("unknown-id probe"),
                        Cell.status(404, "No such publication: " + UNKNOWN_PUBLICATION))));

        cases.add("publicationNewTemplate", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_NEW_TEMPLATE).query("mainType", "PUBLICATION"))
                .sweep((ctx, as) -> {
                    SweepResponse template = ctx.ok(as, SweepRequest.get(P_NEW_TEMPLATE)
                            .query("mainType", "PUBLICATION"));
                    ctx.checkEquals("PUBLICATION", template.json().get("mainType"), "the template's main type",
                            template);
                    ctx.checkEquals("LINK", template.json().get("type"), "the template's type", template);
                    ctx.check(template.json().get("publicationId") != null, "the template has a publicationId",
                            template);
                    ctx.check(String.valueOf(template.json().getString("editRepoPath")).startsWith("temp/"),
                            "the template names a temporary folder to edit in", template);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(P_NEW_TEMPLATE).query("mainType", "BOOK")
                        .note("an unknown main type"), Cell.status(400, "Failed to convert 'mainType'"))));

        cases.add("publicationCopyTemplate", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_COPY).var("publicationId", ctx.row(PUBLICATION)))
                .sweep((ctx, as) -> {
                    SweepResponse copy = ctx.ok(as, SweepRequest.get(P_COPY).var("publicationId",
                            ctx.row(PUBLICATION)));
                    ctx.check(!ctx.row(PUBLICATION).equals(copy.json().get("publicationId")),
                            "the copy has a publicationId of its own", copy);
                    ctx.checkEquals("DRAFT", copy.json().get("status"), "the copy's status", copy);
                    ctx.checkEquals(null, copy.json().get("descs[0].link"), "the copy's link (cleared)", copy);
                    ctx.checkEquals(PUBLICATION, copy.json().get("descs[0].title"), "the copy's title", copy);
                })
                .variants((ctx, as) -> ctx.ok(as, SweepRequest.get(P_COPY).var("publicationId", ctx.row(PUBLICATION))
                        .query("nextIssue", true)))
                // The sibling read (editable-publication) answers an unknown id 404 "No such publication"; this
                // one answers 204 with no body, as the old system's handler did. No screen calls it and the release
                // calls it only with an id it has just found: not a fault (wave 3b's second reader)
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get(P_COPY)
                            .var("publicationId", UNKNOWN_PUBLICATION).note("unknown-id probe"));
                    ctx.checkEquals(204, unknown.status(), "the status of the answer for an unknown id", unknown);
                    ctx.checkEquals(0, unknown.body().length, "the size of the answer for an unknown id", unknown);
                }));

        cases.add("publicationsExport", SweepCase
                .of((ctx, row) -> SweepRequest.get(P_EXPORT).query("lang", "en"))
                .as("admin"));
    }

    /** Checks the legacy publication read carries every field the body sent */
    @SuppressWarnings("unchecked")
    static void checkPublication(SweepContext ctx, Map<String, Object> sent, SweepResponse read, String what) {
        if (!read.is2xx()) {
            return;
        }
        Map<String, Object> got = read.json().getMap("");
        for (String key : List.of("publicationId", "mainType", "type", "status", "languageSpecific", "edition",
                "messagePublication", "messageTagFilter", "periodicalType")) {
            if (sent.containsKey(key)) {
                ctx.checkEquals(norm(sent.get(key)), norm(got.get(key)), what + ": " + key, read);
            }
        }
        for (String key : List.of("publishDateFrom", "publishDateTo")) {
            if (sent.containsKey(key)) {
                ctx.checkEquals(millis(sent.get(key)), millis(got.get(key)), what + ": " + key, read);
            }
        }
        ctx.checkEquals(CATEGORY, read.json().get("category.categoryId"), what + ": the category", read);
        if (sent.containsKey("domain")) {
            ctx.checkEquals(((Map<String, Object>) sent.get("domain")).get("domainId"),
                    read.json().get("domain.domainId"), what + ": the domain", read);
        }
        if (sent.containsKey("messageTag")) {
            ctx.checkEquals(((Map<String, Object>) sent.get("messageTag")).get("tagId"),
                    read.json().get("messageTag.tagId"), what + ": the message tag", read);
        }
        if (sent.containsKey("printSettings")) {
            ctx.checkEquals(norm(sent.get("printSettings")), norm(got.get("printSettings")),
                    what + ": the print settings", read);
        }
        if (sent.containsKey("reportParams")) {
            ctx.checkEquals(norm(sent.get("reportParams")), norm(got.get("reportParams")),
                    what + ": the report parameters", read);
        }
        Map<String, Map<String, Object>> sentDescs = byLang((List<Map<String, Object>>) sent.get("descs"));
        Map<String, Map<String, Object>> gotDescs = byLang((List<Map<String, Object>>) got.get("descs"));
        ctx.checkEquals(sentDescs.keySet(), gotDescs.keySet(), what + ": the description languages", read);
        sentDescs.forEach((lang, d) -> {
            for (String field : List.of("title", "link", "messagePublicationFormat")) {
                if (d.containsKey(field)) {
                    ctx.checkEquals(d.get(field), gotDescs.getOrDefault(lang, Map.of()).get(field),
                            what + ": the " + lang + " " + field, read);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // Legacy publications: writes
    // ------------------------------------------------------------------------------------------- //

    private void publicationWriteCases(SweepCases cases) {
        cases.add("publicationCreate", SweepCase
                .of((ctx, row) -> {
                    Map<String, Object> body = publicationBody(UUID.randomUUID().toString(),
                            ctx.name("refused-" + ctx.unique()), "LINK", "DRAFT", null);
                    if (FILTER.equals(row)) {
                        body.put("messageTagFilter", SCRIPT_FILTER);
                    }
                    return SweepRequest.post(P_CREATE).json(body);
                })
                .sweep((ctx, as) -> publicationRoundTrip(ctx, as, P_CREATE, null))
                .allowed((ctx, as, row) -> publicationRoundTrip(ctx, as, P_CREATE,
                        FILTER.equals(row) ? SCRIPT_FILTER : null))
                .variants((ctx, as) -> publicationRoundTrip(ctx, as, P_CREATE_ALIAS, null))
                .probes((ctx, as) -> {
                    Map<String, Object> uncategorised = publicationBody(UUID.randomUUID().toString(),
                            ctx.name("probe"), "LINK", "DRAFT", null);
                    uncategorised.remove("category");
                    ctx.call(as.name(), SweepRequest.post(P_CREATE).json(uncategorised).note("no category"),
                            Cell.status(400, "A publication must name its category"));
                    Map<String, Object> typeless = publicationBody(UUID.randomUUID().toString(),
                            ctx.name("probe"), "LINK", "DRAFT", null);
                    typeless.remove("type");
                    ctx.call(as.name(), SweepRequest.post(P_CREATE).json(typeless).note("no type"),
                            Cell.status(400, "A publication must name its type (type)"));
                    ctx.call(as.name(), SweepRequest.post(P_CREATE).json(publicationBody(
                                    (String) ctx.row(PUBLICATION), ctx.name("probe"), "LINK", "DRAFT", null))
                                    .note("an existing publicationId"),
                            Cell.status(400, "A publication with this publication ID already exists"));
                }));

        cases.add("publicationUpdate", SweepCase
                .of((ctx, row) -> {
                    String id = (String) ctx.row(FILTER.equals(row) ? FILTER : PUBLICATION);
                    Map<String, Object> body = editablePublication(ctx, id).json().getMap("");
                    if (FILTER.equals(row)) {
                        body.put("messageTagFilter", SCRIPT_FILTER);
                    }
                    return SweepRequest.put(P_ONE).var("publicationId", id).json(body);
                })
                .sweep((ctx, as) -> publicationUpdateRoundTrip(ctx, as, null))
                .allowed((ctx, as, row) -> publicationUpdateRoundTrip(ctx, as,
                        FILTER.equals(row) ? SCRIPT_FILTER : null))
                .probes((ctx, as) -> {
                    Map<String, Object> other = editablePublication(ctx, ctx.row(PUBLICATION)).json().getMap("");
                    ctx.call(as.name(), SweepRequest.put(P_ONE).var("publicationId", ctx.row(PUBLICATION_DRAFT))
                            .json(other).note("the body's id is not the path's"), Cell.status(400, null));
                    Map<String, Object> unknown = editablePublication(ctx, ctx.row(PUBLICATION)).json().getMap("");
                    unknown.put("publicationId", UNKNOWN_PUBLICATION);
                    ctx.call(as.name(), SweepRequest.put(P_ONE).var("publicationId", UNKNOWN_PUBLICATION)
                            .json(unknown).note("unknown-id probe"),
                            Cell.status(400, "Cannot update non-existing publication " + UNKNOWN_PUBLICATION));
                }));

        cases.add("publicationDelete", SweepCase
                // A refused cell asks to remove a publication that does not exist
                .of((ctx, row) -> SweepRequest.delete(P_ONE).var("publicationId", UNKNOWN_PUBLICATION))
                .sweep((ctx, as) -> {
                    String id = UUID.randomUUID().toString();
                    if (!ctx.ok(as, SweepRequest.post(P_CREATE).json(publicationBody(id,
                            ctx.name("publication-delete-" + ctx.unique()), "LINK", "DRAFT", null))).is2xx()) {
                        return;
                    }
                    ctx.ok(as, SweepRequest.delete(P_ONE).var("publicationId", id));
                    ctx.call("admin", SweepRequest.get(P_EDITABLE).var("publicationId", id)
                            .note("read after the delete"), Cell.status(404, "No such publication: " + id));
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete(P_ONE)
                        .var("publicationId", UNKNOWN_PUBLICATION).note("unknown-id probe: removes nothing"),
                        Cell.status(204, null))));

        cases.add("publicationStatus", SweepCase
                // A refused cell asks for the status the publication has, which a gate that let it through refuses
                .of((ctx, row) -> SweepRequest.put(P_STATUS).json(Map.of("publicationId", ctx.row(PUBLICATION),
                        "status", "ACTIVE")))
                .sweep((ctx, as) -> {
                    String id = UUID.randomUUID().toString();
                    if (!ctx.ok(as, SweepRequest.post(P_CREATE).json(publicationBody(id,
                            ctx.name("publication-status-" + ctx.unique()), "LINK", "DRAFT", null))).is2xx()) {
                        return;
                    }
                    try {
                        for (String status : List.of("ACTIVE", "INACTIVE")) {
                            SweepResponse answer = ctx.ok(as, SweepRequest.put(P_STATUS)
                                    .json(Map.of("publicationId", id, "status", status)));
                            ctx.checkEquals(status, answer.json().get("status"), "the answer's status", answer);
                            SweepResponse read = editablePublication(ctx, id);
                            ctx.checkEquals(status, read.json().get("status"), "the status read back", read);
                        }
                    } finally {
                        ctx.check(removePublication(ctx, id), "the publication " + id + " is gone after its "
                                + "removal", null);
                    }
                })
                .probes((ctx, as) -> {
                    // A refused transition is a conflict with the publication's status (PUB1)
                    ctx.call(as.name(), SweepRequest.put(P_STATUS).json(Map.of("publicationId", ctx.row(PUBLICATION),
                            "status", "ACTIVE")).note("an invalid transition (ACTIVE to ACTIVE)"),
                            Cell.status(409, "Invalid state transition ACTIVE -> ACTIVE"));
                    ctx.call(as.name(), SweepRequest.put(P_STATUS).json(Map.of("publicationId", ctx.row(PUBLICATION)))
                                    .note("no status"),
                            Cell.status(400, "A status is required"));
                    ctx.call(as.name(), SweepRequest.put(P_STATUS).json(Map.of("publicationId", UNKNOWN_PUBLICATION,
                            "status", "ACTIVE")).note("unknown-id probe"),
                            Cell.status(400, "Non-existing publication " + UNKNOWN_PUBLICATION));
                }));

        cases.add("publicationReport", SweepCase
                .of((ctx, row) -> SweepRequest.post(P_GENERATE).var("folder", ctx.row(FOLDER))
                        .query("report", "standard").query("lang", "en")
                        .json(Map.of("lang", "en", "fileName", "sweep-publications-refused.pdf")))
                .sweep((ctx, as) -> withReachableBaseUri(ctx, (c, a) -> {
                    String folder = tempFolder(c, a);
                    SweepResponse desc = c.ok(a, SweepRequest.post(P_GENERATE).var("folder", folder)
                            .query("report", "standard").query("lang", "en").query("tag", c.row(TAG))
                            .json(Map.of("lang", "en", "fileName", "sweep-publications-report.pdf")));
                    if (!desc.is2xx()) {
                        return;
                    }
                    c.checkEquals("sweep-publications-report.pdf", desc.json().get("fileName"),
                            "the generated report's file name", desc);
                    c.checkEquals(REPO_PREFIX + folder + "/sweep-publications-report.pdf", desc.json().get("link"),
                            "the generated report's address", desc);
                    checkPdfAt(c, desc.json().getString("link"), "the generated report");
                }, as))
                .probes((ctx, as) -> {
                    String folder = tempFolder(ctx, as);
                    ctx.call(as.name(), SweepRequest.post(P_GENERATE).var("folder", folder)
                            .json(Map.of("lang", "en")).note("no report parameters"),
                            Cell.status(400, "The report parameters are missing"));
                    ctx.call(as.name(), SweepRequest.post(P_GENERATE).var("folder", folder)
                                    .query("report", "standard").json(Map.of("lang", "en", "fileName", "../a.pdf"))
                                    .note("a file name with a path"),
                            Cell.status(400, "Invalid publication file name"));
                }));

        cases.add("publicationRelease", SweepCase
                .of((ctx, row) -> SweepRequest.put(P_RELEASE).var("publicationId", ctx.row(RECORDING)))
                .sweep((ctx, as) -> withReachableBaseUri(ctx, (c, a) -> {
                    // The fixture's RECORDING publication: released here, removed by the tear-down
                    SweepResponse released = c.ok(a, SweepRequest.put(P_RELEASE)
                            .var("publicationId", c.row(RECORDING)));
                    if (!released.is2xx()) {
                        return;
                    }
                    SweepResponse read = editablePublication(c, c.row(RECORDING));
                    c.checkEquals("ACTIVE", read.json().get("status"), "the released publication's status", read);
                    String link = read.json().get("descs.find { it.lang == 'en' }.link");
                    c.check(read.json().get("descs.find { it.lang == 'en' }.fileName") != null && link != null,
                            "the released publication's English description names its report", read);
                    if (link != null) {
                        checkPdfAt(c, link, "the released report");
                    }
                    SweepResponse tag = c.ok("admin", SweepRequest.get(TAGS_READ).var("tagIds", c.row(TAG)));
                    c.checkEquals(List.of(true), tag.json().getList("locked"),
                            "the released publication's message tag is locked", tag);
                }, as))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put(P_RELEASE).var("publicationId", ctx.row(RECORDING))
                                    .note("a released publication, again"),
                            Cell.status(400, "Cannot release publication " + ctx.row(RECORDING)));
                    ctx.call(as.name(), SweepRequest.put(P_RELEASE).var("publicationId", ctx.row(PUBLICATION_DRAFT))
                                    .note("a DRAFT link publication"),
                            Cell.status(400, "Cannot release publication " + ctx.row(PUBLICATION_DRAFT)));
                    ctx.call(as.name(), SweepRequest.put(P_RELEASE).var("publicationId", UNKNOWN_PUBLICATION)
                            .note("unknown-id probe"), Cell.status(400, "Invalid publication ID " + UNKNOWN_PUBLICATION));
                }));

        cases.add("publicationFile", SweepCase
                .of((ctx, row) -> SweepRequest.post(P_UPLOAD_FILE).var("folder", ctx.row(FOLDER))
                        .field("data", "{\"lang\":\"en\",\"fileName\":\"sweep-publications-refused.txt\"}")
                        .file("file", "sweep-publications-refused.txt", "refused".getBytes(), "text/plain"))
                .sweep((ctx, as) -> {
                    String folder = tempFolder(ctx, as);
                    String content = ctx.name("file-" + ctx.unique());
                    SweepResponse desc = ctx.ok(as, SweepRequest.post(P_UPLOAD_FILE).var("folder", folder)
                            .field("data", "{\"lang\":\"en\",\"fileName\":\"sweep-publications-file.txt\"}")
                            .file("file", "upload.txt", content.getBytes(StandardCharsets.UTF_8), "text/plain"));
                    if (!desc.is2xx()) {
                        return;
                    }
                    ctx.checkEquals("sweep-publications-file.txt", desc.json().get("fileName"),
                            "the uploaded file's name", desc);
                    String link = desc.json().getString("link");
                    ctx.checkEquals(REPO_PREFIX + folder + "/sweep-publications-file.txt", link,
                            "the uploaded file's address", desc);
                    SweepResponse file = ctx.ok("anonymous", SweepRequest.get(REPO_FILE)
                            .var("file", link.substring(REPO_PREFIX.length())));
                    ctx.checkEquals(content, file.text(), "the uploaded file's content read back", file);
                })
                .probes((ctx, as) -> {
                    String folder = tempFolder(ctx, as);
                    ctx.call(as.name(), SweepRequest.post(P_UPLOAD_FILE).var("folder", folder)
                            .file("file", "a.txt", "a".getBytes(), "text/plain").note("no descriptor"),
                            Cell.status(400, "No publication descriptor found"));
                    ctx.call(as.name(), SweepRequest.post(P_UPLOAD_FILE).var("folder", folder)
                                    .field("data", "{\"lang\":\"en\",\"fileName\":\"../a.txt\"}")
                                    .file("file", "a.txt", "a".getBytes(), "text/plain")
                                    .note("a descriptor file name with a path"),
                            Cell.status(400, "Invalid publication file name"));
                }));

        cases.add("publicationsUpload", SweepCase
                .of((ctx, row) -> SweepRequest.post(P_UPLOAD)
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String id = UUID.randomUUID().toString();
                    String title = ctx.name("publication-uploaded-" + ctx.unique());
                    Map<String, Object> body = publicationBody(id, title, "LINK", "DRAFT", null);
                    String file = ctx.jobs().upload(ctx, as.name(), SweepRequest.post(P_UPLOAD),
                            JSON.writeValueAsString(List.of(body)));
                    ctx.jobs().awaitClean(ctx, "publication-import", file);
                    try {
                        checkPublication(ctx, body, editablePublication(ctx, id), "the uploaded publication");
                    } finally {
                        ctx.check(removePublication(ctx, id), "the publication " + id + " is gone after its "
                                + "removal", null);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post(P_UPLOAD)
                        .file("file", ctx.name("broken") + ".json", "[{\"publicationId\":".getBytes(),
                                "application/json").note("a broken file"), Cell.status(400, "is not valid JSON"))));
    }

    /** A temporary folder to write publication files in: the one a new template names, with a revision */
    static String tempFolder(SweepContext ctx, SweepIdentity as) {
        String editRepoPath = ctx.ok(as, SweepRequest.get(P_NEW_TEMPLATE)).json().getString("editRepoPath");
        return editRepoPath + "/1";
    }

    /** Reads the repository file at the address and checks it is a PDF */
    static void checkPdfAt(SweepContext ctx, String link, String what) {
        if (!link.startsWith(REPO_PREFIX)) {
            ctx.fail(what + ": its address " + link + " is not a repository file");
            return;
        }
        String path = java.net.URLDecoder.decode(link.substring(REPO_PREFIX.length()), StandardCharsets.UTF_8);
        SweepResponse file = ctx.ok("anonymous", SweepRequest.get(REPO_FILE).var("file", path));
        ctx.check(file.text().startsWith("%PDF"), what + " read back is a PDF", file);
    }

    /** Creates a legacy publication with every field set as the identity, reads it back, removes it */
    private static void publicationRoundTrip(SweepContext ctx, SweepIdentity as, String pattern, String filter) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> body = fullPublicationBody(id, ctx.name("publication-" + ctx.unique()), filter);
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body));
        if (!created.is2xx()) {
            return;
        }
        try {
            checkPublication(ctx, body, created, "the create's answer");
            checkPublication(ctx, body, editablePublication(ctx, id), "the publication read after the create");
        } finally {
            ctx.check(removePublication(ctx, id), "the publication " + id + " is gone after its removal", null);
        }
    }

    /** Makes a publication, saves it changed (and with the filter, if any), reads it back, removes it */
    @SuppressWarnings("unchecked")
    private static void publicationUpdateRoundTrip(SweepContext ctx, SweepIdentity as, String filter) {
        String id = UUID.randomUUID().toString();
        String title = ctx.name("publication-update-" + ctx.unique());
        if (!ctx.ok(as, SweepRequest.post(P_CREATE).json(fullPublicationBody(id, title, null))).is2xx()) {
            return;
        }
        try {
            Map<String, Object> changed = editablePublication(ctx, id).json().getMap("");
            changed.put("edition", 4);
            changed.put("messagePublication", "INTERNAL");
            changed.put("publishDateTo", FIRST_START + 60 * DAY);
            changed.put("reportParams", new LinkedHashMap<>(Map.of("subtitle", "Changed by the sweep")));
            // Every other field the save copies, so that one it drops shows
            changed.put("periodicalType", "MONTHLY");
            changed.put("publishDateFrom", FIRST_START + DAY);
            changed.put("languageSpecific", false);
            changed.put("printSettings", new LinkedHashMap<>(Map.of("pageSize", "A4")));
            if (filter != null) {
                changed.put("messageTagFilter", filter);
            }
            for (Map<String, Object> d : (List<Map<String, Object>>) changed.get("descs")) {
                d.put("title", title + "-renamed-" + d.get("lang"));
                d.put("link", "https://example.org/" + title + "/changed/" + d.get("lang"));
                d.put("messagePublicationFormat", "Sweep publication, changed (" + d.get("lang") + ")");
            }
            ctx.ok(as, SweepRequest.put(P_ONE).var("publicationId", id).json(changed));
            checkPublication(ctx, changed, editablePublication(ctx, id), "the publication read after the update");
        } finally {
            ctx.check(removePublication(ctx, id), "the publication " + id + " is gone after its removal", null);
        }
    }

    /** A link publication of niord-nw with every field the editor writes */
    static Map<String, Object> fullPublicationBody(String id, String title, String filter) {
        Map<String, Object> p = publicationBody(id, title, "LINK", "DRAFT", null);
        p.put("domain", new LinkedHashMap<>(Map.of("domainId", NW)));
        p.put("edition", 3);
        p.put("messagePublication", "EXTERNAL");
        p.put("periodicalType", "WEEKLY");
        p.put("publishDateFrom", FIRST_START);
        p.put("publishDateTo", FIRST_START + 30 * DAY);
        p.put("reportParams", new LinkedHashMap<>(Map.of("subtitle", "Sweep publications")));
        if (filter != null) {
            p.put("messageTagFilter", filter);
        }
        List<Map<String, Object>> descs = new ArrayList<>();
        for (String lang : List.of("da", "en")) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("lang", lang);
            d.put("title", title + "-" + lang);
            d.put("link", "https://example.org/" + title + "/" + lang);
            d.put("messagePublicationFormat", "Sweep publication (" + lang + ")");
            descs.add(d);
        }
        p.put("descs", descs);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    static boolean holds(SweepResponse response, String name) {
        return response.is2xx() && SweepEngine.rowMissing(response, null, name) == null;
    }

    /** Checks the answer does not hold the row */
    static void absent(SweepContext ctx, SweepResponse response, String name, String expectation) {
        ctx.check(response.is2xx() && !holds(response, name), expectation, response);
    }
}
