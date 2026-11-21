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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The endpoint sweep of the publication issues ({@code endpoints/publication-issues.json}): the 30 handlers of
 * {@code PublicationIssueRestService}.
 *
 * <p><b>What the code decides.</b> Every write but the curation calls {@code PublicationDomainGuard.assertWritable}
 * on the issue's (or, for the create, the series') domain: a caller whose {@code NiordDomain} is not the series'
 * owner is refused 403 {@code NOT_IN_DOMAIN}, whatever its roles, so each write has a cell {@code admin@<a row of
 * niord-nm>} (the test realm's admin holds admin in every domain, so only the guard refuses it). The three curation
 * writes are admin tier and ask the same guard: an administrator curates, and there is no separate curation role. The
 * workbench adds the release rail for an admin ({@code isCallerInRole(ADMIN)}), which every caller it admits is; the
 * hydration by ids is anonymous and withholds the document link of an unreleased issue from a caller without the user
 * role; the archived document is {@code @PermitAll} with the admin role checked in the body, so a ticket minted with
 * the admin role opens it and nothing else does. The other reads and the publish rail are annotation-only: one anonymous call, the sweep as
 * the least privileged identity the annotation admits ({@code PublicationTierMatrixTest} holds their tiers), and the
 * editor, adminElsewhere and admin cells of the four curation reads written out.
 *
 * <p><b>The state-builder.</b> The fixture, made through the API as admin: a weekly query-backed series in niord-nw
 * with a published issue (amended once, so its trail holds an archived generation), a retired one and an open one
 * (with a standing curation decision and a preview); an in-force unscheduled series with an open issue that passes
 * the publish checklist (members, a report for each language); a series of uploaded documents with an open issue
 * holding a Danish document; and an in-force series owned by niord-nm with an open issue, the other domain's row. Each
 * write case takes a fresh issue in the state it needs from {@link #fresh} (open, published or retired, on the
 * in-force, weekly or document series) and removes it in {@code finally} through the API (a published issue is
 * retired first: a released document is never deleted, a retired one is), so no case depends on another's order.
 *
 * <p>No existing IT counts the issues or series of the estate: the counts in {@code PublicationIssueRoundTripITTest},
 * {@code PublicationSeriesRoundTripITTest} and {@code OneOffRoundTripITTest} are before and after one call inside one
 * test, and the lists the ITs size are of their own series. The tear-down removes every issue and series through the
 * API, reads each one gone, holds the publication tables to their row counts before the fixture and the repository to
 * no file of an issue the group made; the tables are compacted after it ({@link #tables()}).
 */
class PublicationIssuesSweepGroup implements SweepGroup {

    static final String ISSUES = "/rest/publication-issues";
    static final String SERIES = "/rest/publication-series";

    // The series (their rows hold the series id)
    static final String WEEKLY = "sweep-publication-issues-weekly";
    static final String INFORCE = "sweep-publication-issues-inforce";
    static final String UPLOADED = "sweep-publication-issues-uploaded";
    static final String ELSEWHERE = "sweep-publication-issues-elsewhere";

    // The issues (their rows hold the public id; each issue's English name is the row's name)
    static final String PUBLISHED = "sweep-publication-issues-published";
    static final String RETIRED = "sweep-publication-issues-retired";
    static final String OPEN = "sweep-publication-issues-open";
    static final String READY = "sweep-publication-issues-ready";
    static final String FILED = "sweep-publication-issues-filed";
    static final String ELSEWHERE_OPEN = "sweep-publication-issues-elsewhere-open";

    /** The amend of the published issue: its trail entry's id; the name is the amend's reason */
    static final String AMENDED = "sweep-publication-issues-amended";

    /** The open issue's standing decision (an include of {@link #NW_001}): the name is its reason */
    static final String DECISION = "sweep-publication-issues-decision";

    /** The domain of the fixture's series, and the other one */
    static final String DOMAIN = SweepIdentity.DOMAIN;
    static final String OTHER_DOMAIN = SweepIdentity.OTHER_DOMAIN;

    /** A seeded publishing category the series belong to */
    static final String CATEGORY = "dk-dma-publications";

    /** The seed's published NM notice of September 2026 (dma-nm): a member the weekly published issue froze */
    static final String NM_001 = "5a3f1c70-0000-4000-8000-000000000015";
    /** A published NW warning of the same month, which the series' criteria do not select */
    static final String NW_001 = "5a3f1c70-0000-4000-8000-000000000001";
    /** A draft, which may not be included */
    static final String NW_DRAFT = "5a3f1c70-0000-4000-8000-000000000007";

    static final long SEP_1 = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    static final long SEP_30 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
    static final long OCT_31 = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();
    /** A period of the weekly series no published issue covers: the fresh weekly issues are opened there */
    static final long FUTURE_FROM = Instant.parse("2027-01-06T11:00:00Z").toEpochMilli();
    static final long FUTURE_TO = Instant.parse("2027-01-13T11:00:00Z").toEpochMilli();

    /** A public id no issue has */
    static final String UNKNOWN = "00000000-0000-4000-8000-00000sweep00";

    /** The tables the group's rows live in (the publication tables) */
    static final Set<String> PUBLICATION_TABLES = Set.of("PublicationSeries", "PublicationSeriesDesc",
            "PublicationSeries_AvailableDomain", "PublicationSeries_languages", "PublicationIssue",
            "PublicationIssueDesc", "IssueMember", "IssueOverride", "IssueAuditEntry");

    /** Every issue the group made, by public id: the tear-down holds the repository to no file of theirs */
    private final Set<String> made = new LinkedHashSet<>();

    /** The row count of each publication table before the fixture */
    private Map<String, Long> countsBefore;

    @Override
    public String name() {
        return "publication-issues";
    }

    @Override
    public Set<String> rows() {
        return Set.of(WEEKLY, INFORCE, UPLOADED, ELSEWHERE, PUBLISHED, RETIRED, OPEN, READY, FILED, ELSEWHERE_OPEN,
                AMENDED, DECISION);
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture: the state-builder
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        if (lookUp(ctx)) {
            return;
        }
        // A part of an earlier run's fixture is there, but not all of it: remove it and build it again
        if (!removeAll(ctx).isEmpty()) {
            ctx.fail("The publication-issues fixture of an earlier run could not be removed");
        }
        countsBefore = tableCounts(ctx);

        // The weekly series: a published issue (amended), a retired one, an open one with a decision and a preview
        createSeries(ctx, weeklyBody(), DOMAIN);
        ctx.putRow(WEEKLY, WEEKLY);
        String published = createIssue(ctx, "admin", WEEKLY, SEP_1, SEP_30, PUBLISHED, DOMAIN);
        publish(ctx, "admin", published, SEP_30);
        ctx.ok("admin", amend(published, AMENDED, acknowledgeable(ctx, published, null), version(ctx, published)));
        ctx.putRow(PUBLISHED, published);
        ctx.putRow(AMENDED, entry(ctx, published, "AMENDED").get("id"));

        String retired = createIssue(ctx, "admin", WEEKLY, SEP_30, OCT_31, RETIRED, DOMAIN);
        publish(ctx, "admin", retired, OCT_31);
        ctx.ok("admin", retire(retired, ctx.name("retired by the fixture"), version(ctx, retired)));
        ctx.putRow(RETIRED, retired);

        String open = createIssue(ctx, "admin", WEEKLY, OCT_31, null, OPEN, DOMAIN);
        ctx.ok("admin", curate(open, "include", NW_001, DECISION, version(ctx, open)));
        // A JSON body, as the admin app sends (with none, the client's form content type is refused 415)
        ctx.ok("admin", SweepRequest.post(ISSUES + "/issue/{publicId}/preview").var("publicId", open).json(Map.of()));
        ctx.putRow(OPEN, open);
        ctx.putRow(DECISION, NW_001);

        // The in-force series: an open issue that passes the publish checklist
        createSeries(ctx, inForceBody(INFORCE, DOMAIN), DOMAIN);
        ctx.putRow(INFORCE, INFORCE);
        ctx.putRow(READY, createIssue(ctx, "admin", INFORCE, null, null, READY, DOMAIN));

        // The series of uploaded documents: an open issue holding a Danish document
        createSeries(ctx, documentBody(), DOMAIN);
        ctx.putRow(UPLOADED, UPLOADED);
        String filed = createIssue(ctx, "admin", UPLOADED, null, null, FILED, DOMAIN);
        ctx.ok("admin", upload(filed, "da", FILED + "-da.pdf", FILED.getBytes(StandardCharsets.UTF_8),
                version(ctx, filed)));
        ctx.putRow(FILED, filed);

        // The other domain's series and its open issue
        createSeries(ctx, inForceBody(ELSEWHERE, OTHER_DOMAIN), OTHER_DOMAIN);
        ctx.putRow(ELSEWHERE, ELSEWHERE);
        ctx.putRow(ELSEWHERE_OPEN, createIssue(ctx, "admin", ELSEWHERE, null, null, ELSEWHERE_OPEN, OTHER_DOMAIN));
    }

    /** Registers the rows of an earlier run's fixture when every one of them is there; false otherwise */
    private boolean lookUp(SweepContext ctx) {
        Map<String, String> issues = new LinkedHashMap<>();
        for (String series : List.of(WEEKLY, INFORCE, UPLOADED, ELSEWHERE)) {
            SweepResponse list = ctx.send("admin", bySeries(series));
            if (!list.is2xx()) {
                return false;
            }
            for (Map<String, Object> row : list.json().<Map<String, Object>>getList("data")) {
                String name = nameOf(row, "en");
                if (name != null && rows().contains(name)) {
                    issues.put(name, (String) row.get("publicId"));
                }
            }
        }
        if (!issues.keySet().containsAll(List.of(PUBLISHED, RETIRED, OPEN, READY, FILED, ELSEWHERE_OPEN))) {
            return false;
        }
        Object amended = entry(ctx, issues.get(PUBLISHED), "AMENDED").get("id");
        for (String series : List.of(WEEKLY, INFORCE, UPLOADED, ELSEWHERE)) {
            ctx.putRow(series, series);
        }
        issues.forEach(ctx::putRow);
        made.addAll(issues.values());
        ctx.putRow(AMENDED, amended);
        ctx.putRow(DECISION, NW_001);
        return true;
    }

    @Override
    public List<String> tearDown(SweepContext ctx) throws Exception {
        List<String> left = removeAll(ctx);
        if (countsBefore != null) {
            Map<String, Long> after = tableCounts(ctx);
            System.out.println("Endpoint sweep: publication-issues: rows per table before the fixture " + countsBefore
                    + ", after the tear-down " + after);
            countsBefore.forEach((table, before) -> {
                if (after.getOrDefault(table, 0L) > before) {
                    left.add("table " + table + ": " + before + " rows before the fixture, " + after.get(table)
                            + " after the tear-down");
                }
            });
        }
        // No repository file (document, archived generation) of an issue the group made is left
        try (Connection connection = ctx.bean(DataSource.class).getConnection();
             PreparedStatement files = connection.prepareStatement(
                     "SELECT path FROM public.\"RepoFile\" WHERE position(? in path) > 0")) {
            for (String publicId : made) {
                files.setString(1, publicId);
                try (ResultSet rows = files.executeQuery()) {
                    while (rows.next()) {
                        left.add("the repository file " + rows.getString(1));
                    }
                }
            }
        }
        System.out.println("Endpoint sweep: publication-issues: " + made.size() + " issues made and removed");
        return left;
    }

    /**
     * Removes every issue of the group's series (a published one is retired first: a released document is never
     * deleted) and the series, through the API, and reads each one gone; answers what is still there
     */
    private List<String> removeAll(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        for (String series : List.of(WEEKLY, INFORCE, UPLOADED, ELSEWHERE)) {
            String domain = series.equals(ELSEWHERE) ? OTHER_DOMAIN : DOMAIN;
            SweepResponse list = ctx.send("admin", bySeries(series).note("the tear-down lists the series' issues"));
            if (list.status() == 404) {
                continue;
            }
            if (!list.is2xx()) {
                left.add(series + " (its issues could not be listed: " + list.status() + ")");
                continue;
            }
            for (Map<String, Object> row : list.json().<Map<String, Object>>getList("data")) {
                String publicId = (String) row.get("publicId");
                if (publicId != null && !removeIssue(ctx, publicId, domain, false)) {
                    left.add("the issue " + publicId + " of " + series);
                }
            }
            SweepResponse editable = ctx.send("admin", editableSeries(series));
            if (editable.is2xx()) {
                ctx.send("admin", SweepRequest.delete(SERIES + "/series/{seriesId}").var("seriesId", series)
                        .query("version", editable.json().getInt("version")).domain(domain));
            }
            SweepResponse read = ctx.send("admin", editableSeries(series).note("read after the tear-down's delete"));
            if (!SweepEngine.matches(Cell.status(404, "SERIES_NOT_FOUND"), read)) {
                left.add(series + " (read after the delete: " + read.status() + ")");
            }
        }
        return left;
    }

    /** The number of rows of each publication table */
    private static Map<String, Long> tableCounts(SweepContext ctx) throws Exception {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection connection = ctx.bean(DataSource.class).getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : PUBLICATION_TABLES) {
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
        return Map.of();
    }

    @Override
    public Set<String> tables() {
        Set<String> tables = new LinkedHashSet<>(PUBLICATION_TABLES);
        tables.add("RepoFile");
        return tables;
    }

    // ------------------------------------------------------------------------------------------- //
    // Series and issues through the API
    // ------------------------------------------------------------------------------------------- //

    /** Creates the series as admin sitting at the domain, and activates it */
    private static void createSeries(SweepContext ctx, Map<String, Object> body, String domain) {
        String seriesId = (String) body.get("seriesId");
        ctx.ok("admin", SweepRequest.post(SERIES + "/series").json(body).domain(domain));
        int version = ctx.ok("admin", editableSeries(seriesId)).json().getInt("version");
        ctx.ok("admin", SweepRequest.put(SERIES + "/series/{seriesId}/status").var("seriesId", seriesId)
                .query("version", version).json("\"ACTIVE\"").domain(domain));
    }

    /** A weekly, query-backed series of the NM notices in two languages (as PublicationIssueRoundTripITTest's) */
    private static Map<String, Object> weeklyBody() {
        Map<String, Object> s = seriesBody(WEEKLY, DOMAIN);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", false);
        s.put("firstIssueStartsAt", SEP_1);
        s.put("criteria", criteria("dma-nm"));
        s.put("availability", "OWNER_ONLY");
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("messagePublication", "NONE");
        s.put("releaseMode", "MANUAL_GATE");
        // A cadenced series always opens the next issue on publish (PublicationSeries.normaliseDerivedFields): the
        // published issue's release opens an unnamed successor, which the tear-down removes with the rest
        s.put("nextIssueCreation", "AUTO_ON_PUBLISH");
        s.put("descs", List.of(
                Map.of("lang", "da", "name", WEEKLY + "-da", "nameSuggestionPattern", "Sweep uge ${week} ${year}",
                        "fileNamePattern", "sweep-${year}-${week-2-digits}-da"),
                Map.of("lang", "en", "name", WEEKLY, "nameSuggestionPattern", "Sweep week ${week} ${year}",
                        "fileNamePattern", "sweep-${year}-${week-2-digits}-en")));
        return s;
    }

    /** An unscheduled, query-backed series whose issues list the notices in force at their cut-off */
    private static Map<String, Object> inForceBody(String seriesId, String domain) {
        Map<String, Object> s = seriesBody(seriesId, domain);
        s.put("kind", "UNSCHEDULED");
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "NONE");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("timeRelation", "IN_FORCE_AT_CUTOFF");
        s.put("aliveAtCutoff", true);
        s.put("criteria", criteria("dma-nm"));
        s.put("availability", "OWNER_ONLY");
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("messagePublication", "NONE");
        s.put("descs", List.of(Map.of("lang", "da", "name", seriesId + "-da", "fileNamePattern", "sweep-in-force-da"),
                Map.of("lang", "en", "name", seriesId, "fileNamePattern", "sweep-in-force-en")));
        return s;
    }

    /** An unscheduled series whose document is an uploaded file */
    private static Map<String, Object> documentBody() {
        Map<String, Object> s = seriesBody(UPLOADED, DOMAIN);
        s.put("kind", "UNSCHEDULED");
        s.put("contentMode", "UPLOADED_FILE");
        s.put("cadence", "NONE");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("availability", "ALL_DOMAINS");
        s.put("messagePublication", "NONE");
        s.put("descs", List.of(Map.of("lang", "da", "name", UPLOADED + "-da"), Map.of("lang", "en", "name", UPLOADED)));
        return s;
    }

    private static Map<String, Object> seriesBody(String seriesId, String domain) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", CATEGORY);
        s.put("domainId", domain);
        s.put("languages", List.of("da", "en"));
        return s;
    }

    private static Map<String, Object> criteria(String... messageSeries) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "messageSeries");
        node.put("operator", "IN");
        node.put("values", new ArrayList<>(List.of(messageSeries)));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("schemaVersion", 1);
        c.put("match", "ALL");
        c.put("criteria", new ArrayList<>(List.of(node)));
        return c;
    }

    /** Creates an issue of the series named (English) as given and (Danish) with "-da"; answers its public id */
    private String createIssue(SweepContext ctx, String as, String seriesId, Long from, Long to, String name,
                               String domain) {
        SweepResponse created = ctx.ok(as, SweepRequest.post(ISSUES + "/issue")
                .json(createBody(seriesId, from, to, name)).domain(domain));
        if (!created.is2xx()) {
            throw new IllegalStateException("The issue " + name + " of " + seriesId + " could not be created: "
                    + created.status() + " " + created.preview());
        }
        String publicId = created.json().getString("publicId");
        made.add(publicId);
        return publicId;
    }

    private static Map<String, Object> createBody(String seriesId, Long from, Long to, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seriesId", seriesId);
        body.put("intervalFrom", from);
        body.put("intervalTo", to);
        body.put("names", Map.of("en", name, "da", name + "-da"));
        return body;
    }

    /** The states a write case can take a fresh issue in */
    enum State { OPEN, PUBLISHED, RETIRED }

    /**
     * A fresh issue of the series in the state, made through the API as admin (an in-force or document series: any
     * number of issues, none overlapping; the weekly one: in a future period no published issue covers)
     */
    private String fresh(SweepContext ctx, String seriesId, State state) {
        boolean weekly = seriesId.equals(WEEKLY);
        String domain = seriesId.equals(ELSEWHERE) ? OTHER_DOMAIN : DOMAIN;
        String publicId = createIssue(ctx, "admin", seriesId, weekly ? FUTURE_FROM : null, weekly ? FUTURE_TO : null,
                ctx.name("fresh-" + ctx.unique()), domain);
        if (state != State.OPEN) {
            publish(ctx, "admin", publicId, null);
        }
        if (state == State.RETIRED) {
            ctx.ok("admin", retire(publicId, ctx.name("retired for a case"), version(ctx, publicId)).domain(domain));
        }
        return publicId;
    }

    /**
     * Removes the issue through the API as admin (retires it first when it is published) and reads it gone. Answers
     * whether it is gone; with {@code check} a refusal on the way is a failure of the case
     */
    private static boolean removeIssue(SweepContext ctx, String publicId, String domain, boolean check) {
        SweepResponse read = ctx.send("admin", editable(publicId).note("read before the clean-up"));
        if (read.status() == 404) {
            return true;
        }
        if (!read.is2xx()) {
            return false;
        }
        if ("PUBLISHED".equals(read.json().getString("status"))) {
            SweepResponse retired = ctx.send("admin", retire(publicId, "sweep-publication-issues clean-up",
                    read.json().getInt("version")).domain(domain));
            if (check) {
                ctx.expectOk(retired);
            }
            read = ctx.send("admin", editable(publicId));
        }
        SweepResponse deleted = ctx.send("admin", SweepRequest.delete(ISSUES + "/issue/{publicId}")
                .var("publicId", publicId).query("reason", "sweep-publication-issues clean-up")
                .query("version", read.json().getInt("version")).domain(domain));
        if (check) {
            ctx.expectOk(deleted);
        }
        SweepResponse gone = ctx.send("admin", editable(publicId).note("read after the delete"));
        boolean isGone = SweepEngine.matches(Cell.status(404, "ISSUE_NOT_FOUND"), gone);
        if (check) {
            ctx.check(isGone, "the issue " + publicId + " is gone after its delete (404 ISSUE_NOT_FOUND)", gone);
        }
        return isGone;
    }

    /** The case's clean-up of a fresh issue: removed through the API and read gone */
    private static void remove(SweepContext ctx, String publicId) {
        if (publicId != null) {
            removeIssue(ctx, publicId, DOMAIN, true);
        }
    }

    /** Publishes as the identity, acknowledging every warning the rail raises for the cut-off */
    private static SweepResponse publish(SweepContext ctx, String as, String publicId, Long cutoff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acknowledgedWarnings", acknowledgeable(ctx, publicId, cutoff));
        if (cutoff != null) {
            body.put("cutoff", cutoff);
        }
        body.put("version", version(ctx, publicId));
        return ctx.ok(as, SweepRequest.put(ISSUES + "/issue/{publicId}/publish").var("publicId", publicId).json(body));
    }

    /** The acknowledge codes of the rail's failing, acknowledgeable rows, for the cut-off */
    private static List<String> acknowledgeable(SweepContext ctx, String publicId, Long cutoff) {
        SweepRequest rail = checklist(publicId);
        if (cutoff != null) {
            rail.query("cutoff", cutoff);
        }
        SweepResponse answer = ctx.ok("admin", rail);
        List<String> codes = new ArrayList<>();
        if (!answer.is2xx()) {
            return codes;
        }
        for (Map<String, Object> row : answer.json().<Map<String, Object>>getList("rows")) {
            if (Boolean.TRUE.equals(row.get("acknowledgeable")) && !Boolean.TRUE.equals(row.get("passed"))
                    && row.get("acknowledgeCode") != null) {
                codes.add((String) row.get("acknowledgeCode"));
            }
        }
        return codes;
    }

    private static int version(SweepContext ctx, String publicId) {
        SweepResponse read = ctx.ok("admin", editable(publicId));
        if (!read.is2xx()) {
            throw new IllegalStateException("The issue " + publicId + " cannot be read: " + read.status());
        }
        return read.json().getInt("version");
    }

    /** The newest trail entry of the action */
    private static Map<String, Object> entry(SweepContext ctx, String publicId, String action) {
        SweepResponse trail = ctx.ok("admin", audit(publicId));
        Map<String, Object> newest = null;
        for (Map<String, Object> e : trail.json().<Map<String, Object>>getList("$")) {
            if (action.equals(e.get("action")) && (newest == null
                    || ((Number) e.get("id")).longValue() > ((Number) newest.get("id")).longValue())) {
                newest = e;
            }
        }
        if (newest == null) {
            throw new IllegalStateException("The trail of " + publicId + " holds no " + action + " entry: "
                    + trail.preview());
        }
        return newest;
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests
    // ------------------------------------------------------------------------------------------- //

    static SweepRequest editable(Object publicId) {
        return SweepRequest.get(ISSUES + "/editable-issue/{publicId}").var("publicId", publicId);
    }

    static SweepRequest editableSeries(String seriesId) {
        return SweepRequest.get(SERIES + "/editable-series/{seriesId}").var("seriesId", seriesId);
    }

    static SweepRequest bySeries(String seriesId) {
        return SweepRequest.get(ISSUES + "/series/{seriesId}").var("seriesId", seriesId);
    }

    static SweepRequest audit(Object publicId) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/audit").var("publicId", publicId);
    }

    static SweepRequest checklist(Object publicId) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/publish-checklist").var("publicId", publicId);
    }

    static SweepRequest members(Object publicId) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/members").var("publicId", publicId).query("lang", "en");
    }

    static SweepRequest overrides(Object publicId) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/overrides").var("publicId", publicId).query("lang", "en");
    }

    static SweepRequest retire(Object publicId, String reason, Object version) {
        SweepRequest request = SweepRequest.put(ISSUES + "/issue/{publicId}/retire").var("publicId", publicId);
        if (reason != null) {
            request.query("reason", reason);
        }
        return withVersion(request, version);
    }

    static SweepRequest reactivate(Object publicId, String reason, Object version) {
        return withVersion(SweepRequest.put(ISSUES + "/issue/{publicId}/reactivate").var("publicId", publicId)
                .query("reason", reason), version);
    }

    static SweepRequest amend(Object publicId, String reason, List<String> acknowledged, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("acknowledgedWarnings", acknowledged);
        body.put("version", version);
        return SweepRequest.put(ISSUES + "/issue/{publicId}/amend").var("publicId", publicId).json(body);
    }

    static SweepRequest curate(Object publicId, String kind, String messageUid, String reason, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageUids", List.of(messageUid));
        body.put("reason", reason);
        body.put("version", version);
        return SweepRequest.put(ISSUES + "/issue/{publicId}/overrides/" + kind).var("publicId", publicId).json(body);
    }

    static SweepRequest clearOverride(Object publicId, String messageUid, String reason, Object version) {
        return withVersion(SweepRequest.delete(ISSUES + "/issue/{publicId}/overrides/{messageUid}")
                .var("publicId", publicId).var("messageUid", messageUid).query("reason", reason), version);
    }

    static SweepRequest upload(Object publicId, String lang, String fileName, byte[] bytes, Object version) {
        return withVersion(SweepRequest.post(ISSUES + "/issue/{publicId}/file/{lang}").var("publicId", publicId)
                .var("lang", lang), version).file("file", fileName, bytes, "application/pdf");
    }

    /** The request with the revision as a query parameter, or without one for null */
    static SweepRequest withVersion(SweepRequest request, Object version) {
        return version == null ? request : request.query("version", version);
    }

    static SweepRequest setLink(Object publicId, String lang, String link, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("link", link);
        body.put("version", version);
        return SweepRequest.put(ISSUES + "/issue/{publicId}/link/{lang}").var("publicId", publicId).var("lang", lang)
                .json(body);
    }

    static SweepRequest archived(Object publicId, Object auditEntryId, String lang) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/archive/{auditEntryId}/{lang}").var("publicId", publicId)
                .var("auditEntryId", auditEntryId).var("lang", lang);
    }

    static SweepRequest preview(Object publicId, String lang) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/preview/{lang}").var("publicId", publicId)
                .var("lang", lang);
    }

    /** The issue a cell names after '@' (a row of the fixture), or the default row */
    private static Object issueOf(SweepContext ctx, String row, String defaultRow) {
        return ctx.row(row == null ? defaultRow : row);
    }

    // ------------------------------------------------------------------------------------------- //
    // JSON
    // ------------------------------------------------------------------------------------------- //

    /** The name of a language of an issue as a JSON object carries it in its descs */
    @SuppressWarnings("unchecked")
    static String nameOf(Map<String, Object> issue, String lang) {
        Object descs = issue.get("descs");
        if (descs instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> desc && lang.equals(desc.get("lang"))) {
                    return (String) ((Map<String, Object>) desc).get("name");
                }
            }
        }
        return null;
    }

    /** A field of a language's desc of the issue in the answer */
    static Object descField(JsonPath json, String lang, String field) {
        return json.get("descs.find { it.lang == '" + lang + "' }." + field);
    }

    /** A date as JSON carries it (epoch milliseconds or an ISO-8601 instant), or null */
    static Long millis(Object date) {
        if (date == null) {
            return null;
        }
        return date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }

    /** The trail's actions, in the order the answer lists them */
    static List<String> actions(SweepResponse trail) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : trail.json().<Map<String, Object>>getList("$")) {
            out.add((String) e.get("action"));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        readCases(cases);
        curateReadCases(cases);
        lifecycleCases(cases);
        documentCases(cases);
        curationCases(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // The reads of the editor and admin tiers, and the public hydration
    // ------------------------------------------------------------------------------------------- //

    private void readCases(SweepCases cases) {
        cases.add("get", SweepCase
                .of((ctx, row) -> SweepRequest.get(ISSUES + "/issue/{publicId}").var("publicId", issueOf(ctx, row, OPEN)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(ISSUES + "/issue/{publicId}")
                        .var("publicId", UNKNOWN).note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"))));

        cases.add("bySeries", SweepCase
                .of((ctx, row) -> bySeries(WEEKLY))
                .variants((ctx, as) -> {
                    // Paging is opt-in. The total counts the real issues of the whole archive, paged or not (the
                    // gap rows the list synthesizes are counted apart, IssueListService.forSeries says so), and a
                    // page of one holds one real issue
                    SweepResponse whole = ctx.ok(as, bySeries(WEEKLY));
                    SweepResponse page = ctx.ok(as, bySeries(WEEKLY).query("page", 0).query("maxSize", 1));
                    ctx.checkEquals(whole.json().getInt("total"), page.json().getInt("total"),
                            "the total of the paged archive (the unpaged one's)", page);
                    ctx.checkEquals(1, page.json().getList("data.findAll { it.publicId != null }").size(),
                            "the real issues of a page of one", page);
                    ctx.check(whole.json().getInt("total") >= 3, "the archive holds the fixture's three issues",
                            whole);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), bySeries(ctx.name("unknown")).note("unknown-id probe"),
                            Cell.status(404, "SERIES_NOT_FOUND"));
                    ctx.call(as.name(), bySeries(WEEKLY).query("page", "abc").note("malformed page"),
                            Cell.status(400, "Failed to convert 'page' with value: 'abc'"));
                }));

        cases.add("pickerSearch", SweepCase
                .of((ctx, row) -> picker())
                .sweep((ctx, as) -> {
                    // The default offers what is citable, open and published, and never a retired issue (the rule the
                    // handler states); the same call the default sweep makes, read further
                    SweepResponse offered = ctx.ok(as, picker());
                    if (offered.is2xx()) {
                        ctx.expectRows(offered, null, OPEN, PUBLISHED);
                        ctx.check(!offered.text().contains(RETIRED + "\""),
                                "the default search leaves the retired issue out", offered);
                    }
                })
                .variants((ctx, as) -> {
                    // A status narrows within the default (open and published)
                    SweepResponse published = ctx.ok(as, picker().query("status", "PUBLISHED"));
                    ctx.expectRows(published, null, PUBLISHED);
                    ctx.check(!published.text().contains(OPEN + "\""), "the published-only search leaves the open "
                            + "issue out", published);
                    // The retired issue is not offered by default
                    ctx.check(!ctx.ok(as, picker()).text().contains(RETIRED + "\""),
                            "the default search leaves the retired issue out", published);
                    // In Danish, narrowed to the weekly series: the Danish names
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(ISSUES + "/picker").query("lang", "da")
                            .query("title", "sweep-publication-issues").query("publicationSeriesId", WEEKLY)),
                            null, OPEN + "-da");
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), picker().query("status", "BOGUS").note("unknown status"),
                            Cell.status(400, "INVALID_STATUS"));
                    ctx.call(as.name(), picker().query("type", "bogus").note("unknown type"),
                            Cell.status(400, "INVALID_FILTER_VALUE"));
                    ctx.call(as.name(), picker().query("messagePublication", "bogus").note("unknown message publication"),
                            Cell.status(400, "INVALID_FILTER_VALUE"));
                    ctx.call(as.name(), picker().query("maxSize", "abc").note("malformed maxSize"),
                            Cell.status(400, "Failed to convert 'maxSize' with value: 'abc'"));
                }));

        cases.add("byIds", SweepCase
                .of((ctx, row) -> byIds(ctx))
                .sweep((ctx, as) -> {
                    // Every row resolves (in English, the language the rows are asked in here)
                    ctx.ok(as, byIds(ctx));
                    // In Danish, the open issue's uploaded document is not addressed for a caller without the user role
                    SweepResponse anonymous = ctx.ok(as, byIds(ctx, "da"));
                    if (anonymous.is2xx()) {
                        ctx.checkEquals(null, filedLink(anonymous, ctx), "the Danish link of the open issue's upload "
                                + "for a caller without the user role", anonymous);
                    }
                    // A signed-in user is handed the link
                    SweepResponse user = ctx.ok("user", byIds(ctx, "da"));
                    if (user.is2xx()) {
                        Object link = filedLink(user, ctx);
                        ctx.check(link instanceof String s && s.endsWith(FILED + "-da.pdf"),
                                "the Danish link of the open issue's upload for a user names the file (" + link + ")",
                                user);
                    }
                })
                .variants((ctx, as) -> {
                    SweepResponse none = ctx.ok(as, SweepRequest.get(ISSUES + "/by-ids"));
                    ctx.checkEquals(List.of(), none.json().getList("$"), "the answer to no ids", none);
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get(ISSUES + "/by-ids").query("ids", UNKNOWN + ","
                            + ctx.row(OPEN)));
                    ctx.checkEquals(1, unknown.json().getList("$").size(), "an unknown id is left out", unknown);
                })
                .probes((ctx, as) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i <= 200; i++) {
                        ids.add("sweep-" + i);
                    }
                    ctx.call(as.name(), SweepRequest.get(ISSUES + "/by-ids").query("ids", String.join(",", ids))
                            .note("more ids than one request hydrates"), Cell.status(400, "TOO_MANY_IDS"));
                }));

        cases.add("recent", SweepCase
                .of((ctx, row) -> SweepRequest.get(ISSUES + "/recent").query("publicationSeriesId", WEEKLY))
                .sweep((ctx, as) -> {
                    // The series id alone proves nothing: the answer echoes it for a series it treats as unknown
                    // (an empty group). The strip's real rows (a synthesized cell has no publicId) are the weekly
                    // series' own issues, and the fixture's are among them
                    SweepResponse strip = ctx.ok(as, SweepRequest.get(ISSUES + "/recent")
                            .query("publicationSeriesId", WEEKLY));
                    if (strip.is2xx()) {
                        checkStrip(ctx, strip, WEEKLY, "the weekly series' strip");
                    }
                })
                .variants((ctx, as) -> {
                    // A series that is not there leaves an empty group, the others are answered
                    SweepResponse two = ctx.ok(as, SweepRequest.get(ISSUES + "/recent")
                            .query("publicationSeriesId", ctx.name("unknown") + "," + WEEKLY).query("periods", 2));
                    ctx.expectRows(two, null, WEEKLY);
                    ctx.checkEquals(2, two.json().getList("$").size(), "a group per series named", two);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.get(ISSUES + "/recent").note("no series"),
                            Cell.status(400, "NO_SERIES_IDS"));
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i <= 50; i++) {
                        ids.add("sweep-" + i);
                    }
                    ctx.call(as.name(), SweepRequest.get(ISSUES + "/recent").query("publicationSeriesId",
                            String.join(",", ids)).note("more series than one strip"),
                            Cell.status(400, "TOO_MANY_SERIES_IDS"));
                    ctx.call(as.name(), SweepRequest.get(ISSUES + "/recent").query("publicationSeriesId", WEEKLY)
                            .query("periods", "abc").note("malformed periods"),
                            Cell.status(400, "Failed to convert 'periods' with value: 'abc'"));
                }));

        cases.add("recentByDomain", SweepCase
                .of((ctx, row) -> SweepRequest.get(ISSUES + "/recent-by-domain").query("domain", DOMAIN))
                .sweep((ctx, as) -> {
                    // A desk's strips are the series it OWNS: niord-nw's hold the weekly and the in-force series and
                    // not niord-nm's, and niord-nm's the other way round (PublicationSeriesService.findTimelineSeries)
                    SweepResponse desk = ctx.ok(as, SweepRequest.get(ISSUES + "/recent-by-domain").query("domain", DOMAIN));
                    if (desk.is2xx()) {
                        List<String> ids = desk.json().getList("publicationSeriesId");
                        ctx.check(ids.contains(WEEKLY) && ids.contains(INFORCE), "niord-nw's strips hold its weekly "
                                + "and in-force series (" + ids + ")", desk);
                        ctx.check(!ids.contains(ELSEWHERE), "niord-nw's strips leave niord-nm's series out (" + ids
                                + ")", desk);
                        checkStrip(ctx, desk, WEEKLY, "the weekly series' strip on niord-nw's desk");
                    }
                    SweepResponse other = ctx.ok(as, SweepRequest.get(ISSUES + "/recent-by-domain")
                            .query("domain", OTHER_DOMAIN));
                    if (other.is2xx()) {
                        List<String> ids = other.json().getList("publicationSeriesId");
                        ctx.check(ids.contains(ELSEWHERE) && !ids.contains(WEEKLY) && !ids.contains(INFORCE),
                                "niord-nm's strips hold its series and none of niord-nw's (" + ids + ")", other);
                    }
                })
                .variants((ctx, as) -> {
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(ISSUES + "/recent-by-domain").query("domain", DOMAIN)
                            .query("cadenced", true)), null, WEEKLY);
                    SweepResponse unscheduled = ctx.ok(as, SweepRequest.get(ISSUES + "/recent-by-domain")
                            .query("domain", DOMAIN).query("cadenced", false));
                    ctx.expectRows(unscheduled, null, INFORCE);
                    ctx.check(!unscheduled.text().contains("\"" + WEEKLY + "\""),
                            "cadenced=false leaves the weekly series out", unscheduled);
                    ctx.expectRows(ctx.ok(as, SweepRequest.get(ISSUES + "/recent-by-domain")
                            .query("domain", OTHER_DOMAIN)), null, ELSEWHERE);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get(ISSUES + "/recent-by-domain")
                        .note("no domain"), Cell.status(400, "DOMAIN_REQUIRED"))));

        cases.add("forMessage", SweepCase
                .of((ctx, row) -> SweepRequest.get(ISSUES + "/by-message/{messageUid}").var("messageUid", NM_001))
                .sweep((ctx, as) -> {
                    // The panel's point is FROZEN against LIVE: the September notice went out in a released issue of
                    // the weekly series, so its weekly row is FROZEN and names that issue
                    SweepResponse refs = ctx.ok(as, SweepRequest.get(ISSUES + "/by-message/{messageUid}")
                            .var("messageUid", NM_001));
                    if (refs.is2xx()) {
                        Object frozen = refs.json().get("find { it.seriesId == '" + WEEKLY
                                + "' && it.membership == 'FROZEN' }.issuePublicId");
                        ctx.check(frozen != null && (frozen.equals(ctx.row(PUBLISHED)) || frozen.equals(ctx.row(RETIRED))),
                                "the weekly series' row for " + NM_001 + " is FROZEN and names a released issue of the "
                                        + "fixture (" + frozen + ")", refs);
                    }
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get(ISSUES + "/by-message/{messageUid}")
                            .var("messageUid", UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown message",
                            unknown);
                }));

        cases.add("publishChecklist", SweepCase
                .of((ctx, row) -> checklist(ctx.row(READY)))
                .sweep((ctx, as) -> {
                    SweepResponse rail = ctx.ok(as, checklist(ctx.row(READY)));
                    if (rail.is2xx()) {
                        ctx.checkEquals(true, rail.json().getBoolean("canPublish"),
                                "the ready issue passes the publish checklist", rail);
                        ctx.checkEquals(List.of(), rail.json().getList("blockingCodes"), "its blocking codes", rail);
                        ctx.check(rail.json().getInt("memberCount") > 0, "it has members", rail);
                    }
                    // And a rail that blocks says so: the open weekly issue at a cut-off inside the published one's
                    // period (a rail that always passes would let the dialog offer every release)
                    SweepResponse early = ctx.ok(as, checklist(ctx.row(OPEN)).query("cutoff", SEP_30));
                    if (early.is2xx()) {
                        ctx.checkEquals(false, early.json().getBoolean("canPublish"),
                                "the open weekly issue at a cut-off inside the published one's period", early);
                        ctx.check(!early.json().getList("blockingCodes").isEmpty(), "its blocking codes are named",
                                early);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), checklist(UNKNOWN).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), checklist(ctx.row(READY)).query("cutoff", "abc").note("malformed cutoff"),
                            Cell.status(400, "Failed to convert 'cutoff' with value: 'abc'"));
                }));

        cases.add("preview", SweepCase
                .of((ctx, row) -> preview(ctx.row(OPEN), "da"))
                .variants((ctx, as) -> {
                    SweepResponse en = ctx.ok(as, preview(ctx.row(OPEN), "en"));
                    ctx.check(en.body().length > 0, "the English preview has bytes", en);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), preview(ctx.row(OPEN), "xx").note("a language with no preview"),
                            Cell.status(404, "NO_PREVIEW"));
                    ctx.call(as.name(), preview(ctx.row(READY), "da").note("an issue never previewed"),
                            Cell.status(404, "NO_PREVIEW"));
                    ctx.call(as.name(), preview(UNKNOWN, "da").note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                }));

        cases.add("archivedFile", SweepCase
                .of((ctx, row) -> archived(ctx.row(PUBLISHED), ctx.row(AMENDED), "da"))
                .as("admin")
                .variants((ctx, as) -> {
                    SweepResponse en = ctx.ok(as, archived(ctx.row(PUBLISHED), ctx.row(AMENDED), "en"));
                    ctx.check(en.body().length > 0, "the archived English generation has bytes", en);
                    ctx.check(String.valueOf(en.header("Cache-Control")).contains("no-store"),
                            "an archived document is not stored by the browser", en);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), archived(ctx.row(PUBLISHED), 987654321, "da").note("unknown entry"),
                            Cell.status(404, "ARCHIVE_ENTRY_NOT_FOUND"));
                    ctx.call(as.name(), archived(ctx.row(OPEN), ctx.row(AMENDED), "da")
                            .note("the entry of another issue"), Cell.status(404, "ARCHIVE_ENTRY_NOT_FOUND"));
                    ctx.call(as.name(), archived(ctx.row(PUBLISHED), ctx.row(AMENDED), "xx").note("unknown language"),
                            Cell.status(404, "ARCHIVE_ENTRY_NOT_FOUND"));
                    ctx.call(as.name(), archived(ctx.row(PUBLISHED), "abc", "da").note("malformed entry id"),
                            Cell.status(400, "Failed to convert 'auditEntryId' with value: 'abc'"));
                    ctx.call(as.name(), archived(UNKNOWN, ctx.row(AMENDED), "da").note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                }));
    }

    /**
     * A timeline answer's group of the series: its real rows (a synthesized cell carries no publicId) are issues of
     * that series, and at least one of them is an issue of the fixture's
     */
    private static void checkStrip(SweepContext ctx, SweepResponse answer, String seriesId, String what) {
        List<Object> real = answer.json().getList("find { it.publicationSeriesId == '" + seriesId
                + "' }.rows.findAll { it.publicId != null }.publicId");
        Set<Object> ofSeries = new LinkedHashSet<>(ctx.ok("admin", bySeries(seriesId)).json()
                .getList("data.findAll { it.publicId != null }.publicId"));
        ctx.check(real != null && !real.isEmpty() && ofSeries.containsAll(real)
                        && (real.contains(ctx.row(PUBLISHED)) || real.contains(ctx.row(RETIRED))
                        || real.contains(ctx.row(OPEN))),
                what + " holds real rows, all of them issues of " + seriesId + ", the fixture's among them (" + real
                        + ")", answer);
    }

    private static SweepRequest picker() {
        return SweepRequest.get(ISSUES + "/picker").query("lang", "en").query("title", "sweep-publication-issues");
    }

    private static SweepRequest byIds(SweepContext ctx) {
        return byIds(ctx, "en");
    }

    private static SweepRequest byIds(SweepContext ctx, String lang) {
        return SweepRequest.get(ISSUES + "/by-ids")
                .query("ids", ctx.row(OPEN) + "," + ctx.row(PUBLISHED) + "," + ctx.row(FILED)).query("lang", lang);
    }

    /** The Danish link of the open issue with the uploaded document, in a by-ids answer */
    private static Object filedLink(SweepResponse answer, SweepContext ctx) {
        return answer.json().get("find { it.publicId == '" + ctx.row(FILED) + "' }.descs.find { it.lang == 'da' }"
                + ".link");
    }

    // ------------------------------------------------------------------------------------------- //
    // The curation reads (admin tier)
    // ------------------------------------------------------------------------------------------- //

    private void curateReadCases(SweepCases cases) {
        cases.add("getEditable", SweepCase
                .of((ctx, row) -> editable(issueOf(ctx, row, OPEN)))
                .probes((ctx, as) -> ctx.call(as.name(), editable(UNKNOWN).note("unknown-id probe"),
                        Cell.status(404, "ISSUE_NOT_FOUND"))));

        cases.add("members", SweepCase
                .of((ctx, row) -> members(issueOf(ctx, row, OPEN)))
                .sweep((ctx, as) -> {
                    // The open issue's live list holds the message the fixture included by hand
                    SweepResponse live = ctx.ok(as, members(ctx.row(OPEN)));
                    ctx.check(live.is2xx() && live.json().getList("messageUid").contains(NW_001),
                            "the open issue's members hold the included " + NW_001, live);
                    // The published issue's frozen list holds the September notice
                    SweepResponse frozen = ctx.ok(as, members(ctx.row(PUBLISHED)));
                    ctx.check(frozen.is2xx() && frozen.json().getList("messageUid").contains(NM_001),
                            "the published issue's frozen members hold " + NM_001, frozen);
                })
                .variants((ctx, as) -> {
                    // The language titles the rows and decides nothing else
                    SweepResponse en = ctx.ok(as, members(ctx.row(PUBLISHED)));
                    SweepResponse da = ctx.ok(as, SweepRequest.get(ISSUES + "/issue/{publicId}/members")
                            .var("publicId", ctx.row(PUBLISHED)).query("lang", "da"));
                    ctx.checkEquals(en.json().getList("messageUid"), da.json().getList("messageUid"),
                            "the members in Danish are the members in English", da);
                })
                .probes((ctx, as) -> ctx.call(as.name(), members(UNKNOWN).note("unknown-id probe"),
                        Cell.status(404, "ISSUE_NOT_FOUND"))));

        cases.add("workbench", SweepCase
                .of((ctx, row) -> workbench(issueOf(ctx, row, OPEN)))
                .sweep((ctx, as) -> {
                    // An administrator, the only caller the gate admits, gets the screen with its release rail
                    SweepResponse admin = ctx.ok(as, workbench(ctx.row(OPEN)));
                    if (admin.is2xx()) {
                        ctx.check(admin.json().getList("members.messageUid").contains(NW_001),
                                "the admin's screen lists the members", admin);
                        ctx.check(admin.json().get("checklist.rows") instanceof List<?> rows && !rows.isEmpty(),
                                "the admin's screen carries the release rail", admin);
                    }
                })
                .variants((ctx, as) -> {
                    // A window: answered at the instant named, and it says so
                    long at = System.currentTimeMillis() - 60_000;
                    SweepResponse windowed = ctx.ok(as, workbench(ctx.row(OPEN)).query("at", at));
                    ctx.checkEquals(at, ((Number) windowed.json().get("viewedAt")).longValue(),
                            "the instant the screen was answered at", windowed);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), workbench(UNKNOWN).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), workbench(ctx.row(OPEN)).query("at", "abc").note("malformed at"),
                            Cell.status(400, "Failed to convert 'at' with value: 'abc'"));
                    ctx.call(as.name(), workbench(ctx.row(OPEN)).query("at", OCT_31 + 86_400_000L)
                            .query("from", OCT_31 + 2 * 86_400_000L).note("a window that ends before it starts"),
                            Cell.status(400, "INVALID_INSTANT"));
                }));

        cases.add("auditTrail", SweepCase
                .of((ctx, row) -> audit(issueOf(ctx, row, PUBLISHED)))
                .probes((ctx, as) -> ctx.call(as.name(), audit(UNKNOWN).note("unknown-id probe"),
                        Cell.status(404, "ISSUE_NOT_FOUND"))));

        cases.add("overrides", SweepCase
                .of((ctx, row) -> overrides(issueOf(ctx, row, OPEN)))
                .probes((ctx, as) -> ctx.call(as.name(), overrides(UNKNOWN).note("unknown-id probe"),
                        Cell.status(404, "ISSUE_NOT_FOUND"))));
    }

    private static SweepRequest workbench(Object publicId) {
        return SweepRequest.get(ISSUES + "/issue/{publicId}/workbench").var("publicId", publicId).query("lang", "en");
    }

    // ------------------------------------------------------------------------------------------- //
    // The lifecycle writes: create, edit, preview, publish, amend, new edition, retire, reactivate, delete
    // ------------------------------------------------------------------------------------------- //

    private void lifecycleCases(SweepCases cases) {
        cases.add("create", SweepCase
                // A refused cell sends a period that ends before it starts: refused after the gates, so a gate that
                // let it through would create nothing. The row is a series (the cell's other domain: niord-nm's)
                .of((ctx, row) -> SweepRequest.post(ISSUES + "/issue")
                        .json(createBody((String) issueOf(ctx, row, WEEKLY), FUTURE_TO, FUTURE_FROM, ctx.name("refused"))))
                .sweep((ctx, as) -> {
                    String name = ctx.name("created-" + ctx.unique());
                    SweepResponse created = ctx.ok(as, SweepRequest.post(ISSUES + "/issue")
                            .json(createBody(WEEKLY, FUTURE_FROM, FUTURE_TO, name)));
                    if (!created.is2xx()) {
                        return;
                    }
                    String publicId = created.json().getString("publicId");
                    made.add(publicId);
                    try {
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        JsonPath json = read.json();
                        ctx.checkEquals(WEEKLY, json.getString("seriesId"), "the series", read);
                        ctx.checkEquals(FUTURE_FROM, millis(json.get("intervalFrom")), "the period's start", read);
                        ctx.checkEquals(FUTURE_TO, millis(json.get("intervalTo")), "the period's close", read);
                        ctx.checkEquals(name, descField(json, "en", "name"), "the English name", read);
                        ctx.checkEquals(name + "-da", descField(json, "da", "name"), "the Danish name", read);
                        ctx.checkEquals("OPEN", json.getString("status"), "the status of a new issue", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue").json(Map.of()).note("empty body"),
                            Cell.status(400, "SERIES_INVALID"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue")
                                    .json(createBody(ctx.name("unknown"), null, null, ctx.name("refused")))
                                    .note("unknown series"), Cell.status(404, "SERIES_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue")
                                    .json(createBody(WEEKLY, FUTURE_TO, FUTURE_FROM, ctx.name("refused")))
                                    .note("a period that ends before it starts"), Cell.status(400, "INTERVAL_INVERTED"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue")
                                    .json(createBody(WEEKLY, SEP_1 + 86_400_000L, null, ctx.name("refused")))
                                    .note("a period inside a published one"), Cell.status(409, "ISSUE_INTERVAL_OVERLAP"));
                    // A name in a language the series does not have: refused, and the issue it created is rolled back
                    int before = ctx.ok("admin", bySeries(INFORCE)).json().getList("data").size();
                    Map<String, Object> body = createBody(INFORCE, null, null, ctx.name("refused"));
                    body.put("names", Map.of("xx", ctx.name("refused")));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue").json(body)
                            .note("a name in a language the series does not have"), Cell.status(400, "NO_SUCH_LANGUAGE"));
                    SweepResponse after = ctx.ok("admin", bySeries(INFORCE));
                    ctx.checkEquals(before, after.json().getList("data").size(),
                            "the issues of the series after the refused create", after);
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue")
                                    .json(createBody(WEEKLY, FUTURE_FROM, FUTURE_TO, ctx.name("refused")))
                                    .domain(null).note("no NiordDomain header"), Cell.status(403, "NOT_IN_DOMAIN"));
                }));

        cases.add("update", SweepCase
                .of((ctx, row) -> SweepRequest.put(ISSUES + "/issue/{publicId}")
                        .var("publicId", issueOf(ctx, row, OPEN)).json(Map.of("edition", "9", "version", -1)))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, WEEKLY, State.OPEN);
                    try {
                        String name = ctx.name("updated-" + ctx.unique());
                        Map<String, Object> edit = new LinkedHashMap<>();
                        edit.put("names", Map.of("en", name, "da", name + "-da"));
                        edit.put("intervalFrom", FUTURE_FROM + 86_400_000L);
                        edit.put("intervalTo", FUTURE_TO + 86_400_000L);
                        edit.put("edition", "2");
                        edit.put("weekLabel", "2-3");
                        edit.put("yearLabel", "2027");
                        edit.put("fileNames", Map.of("da", name + "-da.pdf", "en", name + "-en.pdf"));
                        edit.put("reportId", "public");
                        edit.put("reportParams", Map.of("subtitle", name));
                        edit.put("criteriaOverride", criteria("dma-nm", "dma-nw"));
                        edit.put("version", version(ctx, publicId));
                        SweepResponse answer = ctx.ok(as, SweepRequest.put(ISSUES + "/issue/{publicId}")
                                .var("publicId", publicId).json(edit));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        JsonPath json = read.json();
                        ctx.checkEquals(name, descField(json, "en", "name"), "the English name", read);
                        ctx.checkEquals(name + "-da", descField(json, "da", "name"), "the Danish name", read);
                        ctx.checkEquals(FUTURE_FROM + 86_400_000L, millis(json.get("intervalFrom")), "the period's start",
                                read);
                        ctx.checkEquals(FUTURE_TO + 86_400_000L, millis(json.get("intervalTo")), "the period's close",
                                read);
                        ctx.checkEquals("2", json.getString("edition"), "the edition", read);
                        ctx.checkEquals("2-3", json.getString("weekLabel"), "the week label", read);
                        ctx.checkEquals("2027", json.getString("yearLabel"), "the year label", read);
                        ctx.checkEquals(name + "-da.pdf", descField(json, "da", "fileName"), "the Danish file name", read);
                        ctx.checkEquals(name + "-en.pdf", descField(json, "en", "fileName"), "the English file name",
                                read);
                        ctx.checkEquals("public", json.getString("reportId"), "the report", read);
                        ctx.checkEquals(name, json.getString("reportParams.subtitle"), "the report parameter subtitle",
                                read);
                        ctx.checkEquals(criteria("dma-nm", "dma-nw"), json.getMap("criteriaOverride"),
                                "the criteria override", read);
                        ctx.check(json.getInt("version") > (Integer) edit.get("version"),
                                "the edit moved the issue's revision", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}").var("publicId", UNKNOWN)
                            .json(Map.of("edition", "2")).note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}").var("publicId", ctx.row(OPEN))
                            .json(Map.of("edition", "", "version", version(ctx, (String) ctx.row(OPEN))))
                            .note("an empty edition"), Cell.status(400, "EDITION_INVALID"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}").var("publicId",
                                    ctx.row(PUBLISHED)).json(Map.of("edition", "2")).note("a published issue"),
                            Cell.status(409, "ISSUE_NOT_OPEN"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}").var("publicId", ctx.row(OPEN))
                            .json(Map.of("version", "abc")).note("a revision that is not a number"),
                            Cell.status(400, "Failed to read request"));
                }));

        cases.add("generatePreview", SweepCase
                // A refused cell names an instant that is not one: refused after the gates, nothing rendered
                .of((ctx, row) -> SweepRequest.post(ISSUES + "/issue/{publicId}/preview")
                        .var("publicId", issueOf(ctx, row, OPEN)).json(Map.of("at", "not an instant")))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.OPEN);
                    try {
                        long at = System.currentTimeMillis() - 60_000;
                        SweepResponse answer = ctx.ok(as, SweepRequest.post(ISSUES + "/issue/{publicId}/preview")
                                .var("publicId", publicId).json(Map.of("at", at)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        // In no defined order: the previews follow the issue's descs, a set
                        List<String> langs = new ArrayList<>(answer.json().getList("lang"));
                        langs.sort(null);
                        ctx.checkEquals(List.of("da", "en"), langs, "a preview per language", answer);
                        SweepResponse da = ctx.ok("admin", preview(publicId, "da"));
                        ctx.check(da.body().length > 0, "the Danish preview has bytes", da);
                        ctx.check(actions(ctx.ok("admin", audit(publicId))).contains("PREVIEW_GENERATED"),
                                "the trail records the preview", da);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/preview").var("publicId", UNKNOWN)
                            .json(Map.of()).note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/preview")
                            .var("publicId", ctx.row(OPEN)).json(Map.of("at", OCT_31 - 86_400_000L))
                            .note("an instant before the period opens"), Cell.status(400, "INVALID_INSTANT"));
                }));

        cases.add("publish", SweepCase
                .of((ctx, row) -> SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                        .var("publicId", issueOf(ctx, row, READY)).json(Map.of("version", -1)))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.OPEN);
                    try {
                        long cutoff = System.currentTimeMillis() - 60_000;
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("acknowledgedWarnings", acknowledgeable(ctx, publicId, cutoff));
                        body.put("cutoff", cutoff);
                        body.put("version", version(ctx, publicId));
                        SweepResponse answer = ctx.ok(as, SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                                .var("publicId", publicId).json(body));
                        if (!answer.is2xx()) {
                            return;
                        }
                        ctx.checkEquals(cutoff, answer.json().getLong("stampedAt"), "the answer's stamped cut-off",
                                answer);
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals("PUBLISHED", read.json().getString("status"), "the status", read);
                        ctx.checkEquals(cutoff, millis(read.json().get("cutoffStampedAt")), "the stamped cut-off", read);
                        ctx.checkEquals(answer.json().getInt("memberCount"), read.json().getInt("memberCount"),
                                "the frozen member count", read);
                        ctx.check(answer.json().getInt("memberCount") > 0, "the release froze members", answer);
                        ctx.check(actions(ctx.ok("admin", audit(publicId))).contains("PUBLISHED"),
                                "the trail records the release", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/publish").var("publicId", UNKNOWN)
                            .json(Map.of()).note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                            .var("publicId", ctx.row(READY)).json(Map.of("cutoff", System.currentTimeMillis()
                                    + 86_400_000L)).note("a cut-off in the future"), Cell.status(400, "CUTOFF_IN_FUTURE"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                            .var("publicId", ctx.row(READY)).json(Map.of("acknowledgedWarnings", "x"))
                            .note("an acknowledgement that is not a list"), Cell.status(400, "INVALID_FILTER_VALUE"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                            .var("publicId", ctx.row(READY)).json(Map.of("cutoff", "next week"))
                            .note("a cut-off that is not a number"), Cell.status(400, "For input string: \"next week\""));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/publish")
                            .var("publicId", ctx.row(PUBLISHED)).json(Map.of()).note("an issue already published"),
                            Cell.status(409, "ISSUE_ALREADY_PUBLISHED"));
                }));

        cases.add("amend", SweepCase
                .of((ctx, row) -> amend(issueOf(ctx, row, PUBLISHED), ctx.name("refused"), List.of(), -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.PUBLISHED);
                    try {
                        String reason = ctx.name("amended-" + ctx.unique());
                        SweepResponse answer = ctx.ok(as, amend(publicId, reason, acknowledgeable(ctx, publicId, null),
                                version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        ctx.checkEquals(2, answer.json().getList("archivePaths").size(),
                                "one archived document per language", answer);
                        Map<String, Object> entry = entry(ctx, publicId, "AMENDED");
                        ctx.checkEquals(reason, entry.get("reason"), "the amend's reason in the trail", answer);
                        ctx.check(entry.get("archive") != null, "the amend's trail entry names the archive", answer);
                        SweepResponse archivedDa = ctx.ok("admin", archived(publicId, entry.get("id"), "da"));
                        ctx.check(archivedDa.body().length > 0, "the archived Danish generation has bytes", archivedDa);
                        ctx.checkEquals("PUBLISHED", ctx.ok("admin", editable(publicId)).json().getString("status"),
                                "the status after the amend", answer);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), amend(UNKNOWN, ctx.name("refused"), List.of(), null).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), amend(ctx.row(OPEN), ctx.name("refused"), List.of(), null)
                            .note("an open issue"), Cell.status(409, "ISSUE_NOT_PUBLISHED"));
                    ctx.call(as.name(), amend(ctx.row(PUBLISHED), null, acknowledgeable(ctx, (String) ctx.row(PUBLISHED),
                            null), null).note("no reason"), Cell.status(400, "REASON_REQUIRED"));
                }));

        cases.add("newEdition", SweepCase
                .of((ctx, row) -> SweepRequest.post(ISSUES + "/issue/{publicId}/new-edition")
                        .var("publicId", issueOf(ctx, row, PUBLISHED)).json(Map.of("version", -1)))
                .sweep((ctx, as) -> {
                    String first = fresh(ctx, INFORCE, State.PUBLISHED);
                    String edition = null;
                    try {
                        SweepResponse answer = ctx.ok(as, SweepRequest.post(ISSUES + "/issue/{publicId}/new-edition")
                                .var("publicId", first).json(Map.of("version", version(ctx, first))));
                        if (!answer.is2xx()) {
                            return;
                        }
                        edition = answer.json().getString("publicId");
                        made.add(edition);
                        SweepResponse read = ctx.ok("admin", editable(edition));
                        ctx.checkEquals(first, read.json().getString("supersedesPublicId"),
                                "the edition supersedes the published issue", read);
                        ctx.checkEquals("OPEN", read.json().getString("status"), "the edition's status", read);
                        ctx.checkEquals("2", read.json().getString("edition"), "the edition's number", read);
                        ctx.check(actions(ctx.ok("admin", audit(first))).contains("SUPERSEDED_BY"),
                                "the predecessor's trail says so", read);
                    } finally {
                        remove(ctx, edition);
                        remove(ctx, first);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/new-edition")
                            .var("publicId", UNKNOWN).json(Map.of()).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/new-edition")
                            .var("publicId", ctx.row(OPEN)).json(Map.of()).note("an open issue"),
                            Cell.status(409, "PREDECESSOR_NOT_PUBLISHED"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/new-edition")
                            .var("publicId", ctx.row(PUBLISHED)).json(Map.of("intervalFrom", "abc"))
                            .note("a start that is not a number"), Cell.status(400, "For input string: \"abc\""));
                }));

        cases.add("retire", SweepCase
                .of((ctx, row) -> retire(issueOf(ctx, row, PUBLISHED), ctx.name("refused"), -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.PUBLISHED);
                    try {
                        String reason = ctx.name("retired-" + ctx.unique());
                        SweepResponse answer = ctx.ok(as, retire(publicId, reason, version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals("RETIRED", read.json().getString("status"), "the status", read);
                        ctx.checkEquals(reason, read.json().getString("retiredReason"), "the retire's reason", read);
                        ctx.checkEquals(reason, entry(ctx, publicId, "RETIRED").get("reason"),
                                "the retire's reason in the trail", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), retire(UNKNOWN, ctx.name("refused"), null).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), retire(ctx.row(PUBLISHED), null, null).note("no reason"),
                            Cell.status(400, "REASON_REQUIRED"));
                    ctx.call(as.name(), retire(ctx.row(OPEN), ctx.name("refused"), null).note("an open issue"),
                            Cell.status(409, "ISSUE_NOT_PUBLISHED"));
                    ctx.call(as.name(), retire(ctx.row(PUBLISHED), ctx.name("refused"), "abc")
                            .note("malformed version"), Cell.status(400, "Failed to convert 'version' with value: 'abc'"));
                }));

        cases.add("reactivate", SweepCase
                .of((ctx, row) -> reactivate(issueOf(ctx, row, RETIRED), ctx.name("refused"), -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.RETIRED);
                    try {
                        String reason = ctx.name("reactivated-" + ctx.unique());
                        SweepResponse answer = ctx.ok(as, reactivate(publicId, reason, version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals("PUBLISHED", read.json().getString("status"), "the status", read);
                        ctx.checkEquals(null, read.json().get("retiredReason"), "the retire's reason is cleared", read);
                        ctx.checkEquals(reason, entry(ctx, publicId, "REACTIVATED").get("reason"),
                                "the reactivation's reason in the trail", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), reactivate(UNKNOWN, ctx.name("refused"), null).note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), reactivate(ctx.row(PUBLISHED), ctx.name("refused"), null)
                            .note("a published issue"), Cell.status(409, "ISSUE_NOT_RETIRED"));
                }));

        cases.add("delete", SweepCase
                .of((ctx, row) -> SweepRequest.delete(ISSUES + "/issue/{publicId}")
                        .var("publicId", issueOf(ctx, row, OPEN)).query("reason", ctx.name("refused"))
                        .query("version", -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, INFORCE, State.OPEN);
                    try {
                        ctx.ok(as, SweepRequest.delete(ISSUES + "/issue/{publicId}").var("publicId", publicId)
                                .query("reason", ctx.name("deleted")).query("version", version(ctx, publicId))
                                .query("lang", "en"));
                        ctx.call("admin", editable(publicId).note("read after the delete"),
                                Cell.status(404, "ISSUE_NOT_FOUND"));
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .variants((ctx, as) -> {
                    // A retired issue nothing cites goes too
                    String publicId = fresh(ctx, INFORCE, State.RETIRED);
                    try {
                        ctx.ok(as, SweepRequest.delete(ISSUES + "/issue/{publicId}").var("publicId", publicId)
                                .query("version", version(ctx, publicId)));
                        ctx.call("admin", editable(publicId).note("read after the delete"),
                                Cell.status(404, "ISSUE_NOT_FOUND"));
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.delete(ISSUES + "/issue/{publicId}").var("publicId", UNKNOWN)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.delete(ISSUES + "/issue/{publicId}")
                                    .var("publicId", ctx.row(PUBLISHED)).note("a published issue"),
                            Cell.status(409, "ISSUE_PUBLISHED_NOT_DELETABLE"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // The document writes: upload, clear, link
    // ------------------------------------------------------------------------------------------- //

    private void documentCases(SweepCases cases) {
        cases.add("uploadFile", SweepCase
                .of((ctx, row) -> upload(issueOf(ctx, row, FILED), "da", ctx.name("refused") + ".pdf",
                        "refused".getBytes(StandardCharsets.UTF_8), -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, UPLOADED, State.OPEN);
                    try {
                        String fileName = ctx.name("uploaded-" + ctx.unique()) + "-da.pdf";
                        byte[] bytes = (fileName + " bytes").getBytes(StandardCharsets.UTF_8);
                        SweepResponse answer = ctx.ok(as, upload(publicId, "da", fileName, bytes,
                                version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals(fileName, descField(read.json(), "da", "fileName"), "the Danish file name",
                                read);
                        String path = read.json().getString("repoPath") + "/" + fileName;
                        SweepResponse document = ctx.ok("admin", SweepRequest.get("/rest/repo/file/{*file}")
                                .var("file", path));
                        ctx.checkEquals(fileName + " bytes", document.text(), "the stored document's bytes", document);
                        ctx.check(actions(ctx.ok("admin", audit(publicId))).contains("FILE_UPLOADED"),
                                "the trail records the upload", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    Object filed = ctx.row(FILED);
                    ctx.call(as.name(), upload(UNKNOWN, "da", ctx.name("refused") + ".pdf", new byte[]{1}, null)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), upload(filed, "xx", ctx.name("refused") + ".pdf", new byte[]{1}, null)
                            .note("a language the series does not have"), Cell.status(400, "NO_SUCH_LANGUAGE"));
                    ctx.call(as.name(), upload(filed, "en", FILED + "-da.pdf", new byte[]{1}, null)
                            .note("the other language's file name"), Cell.status(409, "FILE_NAME_NOT_DISTINCT"));
                    ctx.call(as.name(), SweepRequest.post(ISSUES + "/issue/{publicId}/file/{lang}")
                            .var("publicId", filed).var("lang", "da")
                            .file("file", ctx.name("one") + ".pdf", new byte[]{1}, "application/pdf")
                            .file("file2", ctx.name("two") + ".pdf", new byte[]{2}, "application/pdf")
                            .note("two files"), Cell.status(400, "TOO_MANY_FILES"));
                }));

        cases.add("clearFile", SweepCase
                .of((ctx, row) -> SweepRequest.delete(ISSUES + "/issue/{publicId}/file/{lang}")
                        .var("publicId", issueOf(ctx, row, FILED)).var("lang", "da").query("version", -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, UPLOADED, State.OPEN);
                    try {
                        String fileName = ctx.name("cleared-" + ctx.unique()) + "-da.pdf";
                        ctx.ok("admin", upload(publicId, "da", fileName, fileName.getBytes(StandardCharsets.UTF_8),
                                version(ctx, publicId)));
                        SweepResponse answer = ctx.ok(as, SweepRequest.delete(ISSUES + "/issue/{publicId}/file/{lang}")
                                .var("publicId", publicId).var("lang", "da").query("version", version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals(null, descField(read.json(), "da", "fileName"), "the cleared Danish file name",
                                read);
                        ctx.check(actions(ctx.ok("admin", audit(publicId))).contains("FILE_CLEARED"),
                                "the trail records the clear", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.delete(ISSUES + "/issue/{publicId}/file/{lang}")
                            .var("publicId", UNKNOWN).var("lang", "da").note("unknown-id probe"),
                            Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.delete(ISSUES + "/issue/{publicId}/file/{lang}")
                            .var("publicId", ctx.row(PUBLISHED)).var("lang", "da").note("a published issue"),
                            Cell.status(409, "ISSUE_NOT_OPEN"));
                }));

        cases.add("setLink", SweepCase
                .of((ctx, row) -> setLink(issueOf(ctx, row, FILED), "en", "https://example.org/refused.pdf", -1))
                .sweep((ctx, as) -> {
                    String publicId = fresh(ctx, UPLOADED, State.OPEN);
                    try {
                        String link = "https://example.org/" + ctx.name("linked-" + ctx.unique()) + ".pdf";
                        SweepResponse answer = ctx.ok(as, setLink(publicId, "en", link, version(ctx, publicId)));
                        if (!answer.is2xx()) {
                            return;
                        }
                        SweepResponse read = ctx.ok("admin", editable(publicId));
                        ctx.checkEquals(link, descField(read.json(), "en", "link"), "the English link", read);
                        ctx.check(actions(ctx.ok("admin", audit(publicId))).contains("LINK_SET"),
                                "the trail records the link", read);
                    } finally {
                        remove(ctx, publicId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), setLink(UNKNOWN, "en", "https://example.org/x.pdf", null)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), setLink(ctx.row(FILED), "en", "javascript:alert(1)", null)
                            .note("a link that is not an address"), Cell.status(400, "LINK_INVALID"));
                    ctx.call(as.name(), setLink(ctx.row(FILED), "xx", "https://example.org/x.pdf", null)
                            .note("a language the series does not have"), Cell.status(400, "NO_SUCH_LANGUAGE"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // The curation writes: include, exclude, withdraw (admin tier, the owner's domain)
    // ------------------------------------------------------------------------------------------- //

    private void curationCases(SweepCases cases) {
        cases.add("includeMember", SweepCase
                .of((ctx, row) -> curate(issueOf(ctx, row, OPEN), "include", NW_001, ctx.name("refused"), -1))
                .sweep((ctx, as) -> includeRoundTrip(ctx, as))
                .allowed((ctx, as, row) -> includeRoundTrip(ctx, as))
                .probes((ctx, as) -> {
                    Object open = ctx.row(OPEN);
                    ctx.call(as.name(), curate(UNKNOWN, "include", NW_001, ctx.name("refused"), null)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), SweepRequest.put(ISSUES + "/issue/{publicId}/overrides/include")
                            .var("publicId", open).json(Map.of("reason", ctx.name("refused"))).note("no message"),
                            Cell.status(400, "NO_MESSAGES"));
                    ctx.call(as.name(), curate(open, "include", NM_001, null, null).note("no reason"),
                            Cell.status(400, "OVERRIDE_REASON_REQUIRED"));
                    ctx.call(as.name(), curate(open, "include", UNKNOWN, ctx.name("refused"), null)
                            .note("an unknown message"), Cell.status(409, "OVERRIDE_MESSAGE_NOT_FOUND"));
                    ctx.call(as.name(), curate(open, "include", NW_DRAFT, ctx.name("refused"), null)
                            .note("a draft"), Cell.status(409, "OVERRIDE_MESSAGE_NOT_PUBLIC"));
                    ctx.call(as.name(), curate(ctx.row(PUBLISHED), "include", NW_001, ctx.name("refused"), null)
                            .note("a published issue"), Cell.status(409, "ISSUE_NOT_OPEN"));
                }));

        cases.add("excludeMember", SweepCase
                .of((ctx, row) -> curate(issueOf(ctx, row, OPEN), "exclude", NW_001, ctx.name("refused"), -1))
                .sweep((ctx, as) -> excludeRoundTrip(ctx, as))
                .allowed((ctx, as, row) -> excludeRoundTrip(ctx, as))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), curate(UNKNOWN, "exclude", NW_001, ctx.name("refused"), null)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), curate(ctx.row(OPEN), "exclude", NW_001, null, null).note("no reason"),
                            Cell.status(400, "OVERRIDE_REASON_REQUIRED"));
                }));

        cases.add("clearOverride", SweepCase
                .of((ctx, row) -> clearOverride(issueOf(ctx, row, OPEN), NW_001, ctx.name("refused"), -1))
                .sweep((ctx, as) -> clearRoundTrip(ctx, as))
                .allowed((ctx, as, row) -> clearRoundTrip(ctx, as))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), clearOverride(UNKNOWN, NW_001, ctx.name("refused"), null)
                            .note("unknown-id probe"), Cell.status(404, "ISSUE_NOT_FOUND"));
                    ctx.call(as.name(), clearOverride(ctx.row(OPEN), NM_001, ctx.name("refused"), null)
                            .note("a message with no decision"), Cell.status(404, "OVERRIDE_NOT_FOUND"));
                }));
    }

    /** Includes a message the criteria do not select, as the identity, on a fresh open issue; reads it back */
    private void includeRoundTrip(SweepContext ctx, SweepIdentity as) {
        String publicId = fresh(ctx, INFORCE, State.OPEN);
        try {
            String reason = ctx.name("included-" + ctx.unique());
            SweepResponse answer = ctx.ok(as, curate(publicId, "include", NW_001, reason, version(ctx, publicId)));
            if (!answer.is2xx()) {
                return;
            }
            SweepResponse decisions = ctx.ok(as, overrides(publicId));
            ctx.checkEquals(List.of(NW_001), decisions.json().getList("messageUid"), "the standing decisions",
                    decisions);
            ctx.checkEquals("INCLUDE", decisions.json().getString("[0].kind"), "the decision's kind", decisions);
            ctx.checkEquals(reason, decisions.json().getString("[0].reason"), "the decision's reason", decisions);
            SweepResponse live = ctx.ok(as, members(publicId));
            ctx.check(live.json().getList("messageUid").contains(NW_001), "the included message is a member", live);
        } finally {
            remove(ctx, publicId);
        }
    }

    /** Excludes a member, as the identity, on a fresh open issue; reads it back */
    private void excludeRoundTrip(SweepContext ctx, SweepIdentity as) {
        String publicId = fresh(ctx, INFORCE, State.OPEN);
        try {
            List<String> before = ctx.ok(as, members(publicId)).json().getList("messageUid");
            if (before.isEmpty()) {
                ctx.fail("The fresh in-force issue has no member to exclude");
                return;
            }
            String member = before.getFirst();
            String reason = ctx.name("excluded-" + ctx.unique());
            SweepResponse answer = ctx.ok(as, curate(publicId, "exclude", member, reason, version(ctx, publicId)));
            if (!answer.is2xx()) {
                return;
            }
            SweepResponse decisions = ctx.ok(as, overrides(publicId));
            ctx.checkEquals(List.of(member), decisions.json().getList("messageUid"), "the standing decisions",
                    decisions);
            ctx.checkEquals("EXCLUDE", decisions.json().getString("[0].kind"), "the decision's kind", decisions);
            ctx.checkEquals(reason, decisions.json().getString("[0].reason"), "the decision's reason", decisions);
            SweepResponse live = ctx.ok(as, members(publicId));
            ctx.check(!live.json().getList("messageUid").contains(member), "the excluded message is no member", live);
        } finally {
            remove(ctx, publicId);
        }
    }

    /** Withdraws a decision, as the identity, on a fresh open issue; reads it gone */
    private void clearRoundTrip(SweepContext ctx, SweepIdentity as) {
        String publicId = fresh(ctx, INFORCE, State.OPEN);
        try {
            ctx.ok("admin", curate(publicId, "include", NW_001, ctx.name("to withdraw"), version(ctx, publicId)));
            String reason = ctx.name("withdrawn-" + ctx.unique());
            SweepResponse answer = ctx.ok(as, clearOverride(publicId, NW_001, reason, version(ctx, publicId)));
            if (!answer.is2xx()) {
                return;
            }
            SweepResponse decisions = ctx.ok(as, overrides(publicId));
            ctx.checkEquals(List.of(), decisions.json().getList("$"), "the standing decisions after the withdrawal",
                    decisions);
            Map<String, Object> removed = entry(ctx, publicId, "OVERRIDE_REMOVED");
            ctx.checkEquals(reason, removed.get("reason"), "the withdrawal's reason in the trail", decisions);
            SweepResponse live = ctx.ok(as, members(publicId));
            ctx.check(!live.json().getList("messageUid").contains(NW_001), "the message is no member again", live);
        } finally {
            remove(ctx, publicId);
        }
    }
}
