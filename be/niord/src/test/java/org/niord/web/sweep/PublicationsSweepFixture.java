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

import org.niord.core.settings.SettingsService;
import org.niord.test.Tx;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The fixture of {@link PublicationsSweepGroup}: the rows it makes through the API, the bodies its cases send, the
 * helpers that read and remove publication rows, and the tear-down.
 *
 * <p>Rows (each made through the API as the identity whose desk it belongs to):
 * <ul>
 *   <li>a publication category (admin), which every other row is filed under;</li>
 *   <li>an ACTIVE weekly query-backed series of niord-nw (admin) and one of niord-nm (adminElsewhere, sitting at
 *       niord-nm, where it is an administrator);</li>
 *   <li>a link-backed one-off of niord-nw (admin) and one of niord-nm (adminElsewhere at niord-nm), both left
 *       unpublished: a DRAFT series with an OPEN issue, which the API can remove (a published issue cannot be);</li>
 *   <li>three legacy publications: an ACTIVE link (the public tier reads it), a DRAFT link (only a signed-in user
 *       reads it) and a RECORDING message report with a private message tag of the admin and the print setting
 *       {@code report=standard}, owned by niord-almanac, whose message series no case publishes in (a recording
 *       publication collects the published messages of its domain's series);</li>
 *   <li>the private message tag of the recording publication.</li>
 * </ul>
 * Four rows name an existing row for another purpose: {@code ...-series-nm-from-nm} and {@code ...-oneoff-nm-from-nm}
 * are the niord-nm rows called with {@code NiordDomain: niord-nm} (the desk that owns them), {@code ...-filter} is the
 * DRAFT publication sent with a message tag filter only a system administrator may set, and {@code ...-folder} is the
 * ACTIVE publication's committed repository folder (not a temporary one).
 *
 * <p>Publication refusals are not problem details: {@code PublicationExceptionAdvice} answers {@code {code, message}},
 * a published contract. A table cell of such a refusal is therefore {@code {"status": 403, "detail": null}}, and the
 * cases check the code itself ({@link #refusedWith}).
 */
final class PublicationsSweepFixture {

    static final String NW = SweepIdentity.DOMAIN;
    static final String NM = SweepIdentity.OTHER_DOMAIN;
    /** The domain of the recording publication: a domain whose message series no sweep case publishes in */
    static final String RECORDING_DOMAIN = "niord-almanac";

    // Rows (the id of each is given beside it)
    /** categoryId = the name */
    static final String CATEGORY = "sweep-publications-category";
    /** seriesId = the name */
    static final String SERIES_NW = "sweep-publications-series-nw";
    static final String SERIES_NM = "sweep-publications-series-nm";
    /** The niord-nm series, called from niord-nm (id: its seriesId) */
    static final String SERIES_NM_FROM_NM = "sweep-publications-series-nm-from-nm";
    /** seriesId = the name */
    static final String ONE_OFF = "sweep-publications-oneoff";
    static final String ONE_OFF_NM = "sweep-publications-oneoff-nm";
    /** The niord-nm one-off, called from niord-nm (id: its seriesId) */
    static final String ONE_OFF_NM_FROM_NM = "sweep-publications-oneoff-nm-from-nm";
    /** publicationId = a UUID; the English title is the name */
    static final String PUBLICATION = "sweep-publications-publication";
    static final String PUBLICATION_DRAFT = "sweep-publications-publication-draft";
    static final String RECORDING = "sweep-publications-recording";
    /** The DRAFT publication, sent with a message tag filter only a system administrator may set (its id) */
    static final String FILTER = "sweep-publications-filter";
    /** tagId */
    static final String TAG = "sweep-publications-tag";
    /** The ACTIVE publication's committed repository folder (a repository path) */
    static final String FOLDER = "sweep-publications-folder";

    static final Set<String> ROWS = Set.of(CATEGORY, SERIES_NW, SERIES_NM, SERIES_NM_FROM_NM, ONE_OFF, ONE_OFF_NM,
            ONE_OFF_NM_FROM_NM, PUBLICATION, PUBLICATION_DRAFT, RECORDING, FILTER, TAG, FOLDER);

    /** A message tag filter no legacy publication stores, so only a system administrator may set it */
    static final String SCRIPT_FILTER = "msg.type == Type.SWEEP_PUBLICATIONS";

    /** 2026-01-01T00:00:00Z: the first issue start of the sweep's weekly series */
    static final long FIRST_START = 1_767_225_600_000L;
    static final long DAY = 24L * 3600 * 1000;

    // Paths, as the table writes them
    static final String S_SEARCH = "/rest/publication-series/search";
    static final String S_SEARCH_DETAILS = "/rest/publication-series/search-details";
    static final String S_GET = "/rest/publication-series/series/{seriesId}";
    static final String S_EDITABLE = "/rest/publication-series/editable-series/{seriesId}";
    static final String S_BY_IDS = "/rest/publication-series/series-by-ids/{seriesIds}";
    static final String S_CREATE = "/rest/publication-series/series";
    static final String S_CREATE_ALIAS = "/rest/publication-series/series/";
    static final String S_NEW_TEMPLATE = "/rest/publication-series/new-series-template";
    static final String S_COPY = "/rest/publication-series/copy-series-template/{seriesId}";
    static final String S_PREVIEW = "/rest/publication-series/resolve-preview";
    static final String S_ONE = "/rest/publication-series/series/{seriesId}";
    static final String S_STATUS = "/rest/publication-series/series/{seriesId}/status";
    static final String S_OWNER = "/rest/publication-series/series/{seriesId}/owner";
    static final String S_ISSUE_DRAFT = "/rest/publication-series/series/{seriesId}/issue-draft";
    static final String S_HISTORY = "/rest/publication-series/{seriesId}/history";
    static final String S_TOKENS = "/rest/publication-series/name-tokens";
    static final String S_VALIDATE = "/rest/publication-series/validate";
    static final String S_IMPORT_DRY = "/rest/publication-series/import-legacy/validate";
    static final String S_IMPORT = "/rest/publication-series/import-legacy";
    static final String S_IMPORT_CHECK = "/rest/publication-series/import-check";
    static final String S_EXPORT = "/rest/publication-series/export";
    static final String S_UPLOAD = "/rest/publication-series/upload-series";

    static final String O_LIST = "/rest/one-off-publications";
    static final String O_LIST_ALIAS = "/rest/one-off-publications/";
    static final String O_ONE = "/rest/one-off-publications/{seriesId}";
    static final String O_WINDOW = "/rest/one-off-publications/{seriesId}/public-window";

    static final String C_ALL = "/rest/publication-categories/all";
    static final String C_ONE = "/rest/publication-categories/publication-category/{categoryId}";
    static final String C_CREATE = "/rest/publication-categories/publication-category";
    static final String C_CREATE_ALIAS = "/rest/publication-categories/publication-category/";
    static final String C_UPLOAD = "/rest/publication-categories/upload-publication-categories";

    static final String P_SEARCH = "/rest/publications/search";
    static final String P_SEARCH_DETAILS = "/rest/publications/search-details";
    static final String P_ALL = "/rest/publications/all";
    static final String P_GET = "/rest/publications/publication/{publicationIds}";
    static final String P_EDITABLE = "/rest/publications/editable-publication/{publicationId}";
    static final String P_NEW_TEMPLATE = "/rest/publications/new-publication-template";
    static final String P_COPY = "/rest/publications/copy-publication-template/{publicationId}";
    static final String P_CREATE = "/rest/publications/publication";
    static final String P_CREATE_ALIAS = "/rest/publications/publication/";
    static final String P_ONE = "/rest/publications/publication/{publicationId}";
    static final String P_STATUS = "/rest/publications/update-status";
    static final String P_GENERATE = "/rest/publications/generate-publication-report/{*folder}";
    static final String P_RELEASE = "/rest/publications/release-publication/{publicationId}";
    static final String P_UPLOAD_FILE = "/rest/publications/upload-publication-file/{*folder}";
    static final String P_EXPORT = "/rest/publications/export";
    static final String P_UPLOAD = "/rest/publications/upload-publications";

    static final String I_ONE = "/rest/publication-issues/issue/{publicId}";
    static final String TAGS_SEARCH = "/rest/tags/search";
    static final String TAGS_CREATE = "/rest/tags/tag";
    static final String TAGS_ONE = "/rest/tags/tag/{tagId}";
    static final String TAGS_READ = "/rest/tags/tag/{tagIds}";
    static final String TAGS_UNLOCK = "/rest/tags/unlock-tag/{tagId}";
    static final String REPO_FILE = "/rest/repo/file/{*file}";
    static final String REPO_PREFIX = "/rest/repo/file/";

    private PublicationsSweepFixture() {
    }

    // ------------------------------------------------------------------------------------------- //
    // Set-up
    // ------------------------------------------------------------------------------------------- //

    static void setUp(SweepContext ctx) {
        ctx.putRow(CATEGORY, findOrCreateCategory(ctx));
        ctx.putRow(SERIES_NW, findOrCreateSeries(ctx, SERIES_NW, NW, "admin"));
        ctx.putRow(SERIES_NM, findOrCreateSeries(ctx, SERIES_NM, NM, "adminElsewhere"));
        ctx.putRow(SERIES_NM_FROM_NM, SERIES_NM);
        ctx.putRow(ONE_OFF, findOrCreateOneOff(ctx, ONE_OFF, NW, "admin"));
        ctx.putRow(ONE_OFF_NM, findOrCreateOneOff(ctx, ONE_OFF_NM, NM, "adminElsewhere"));
        ctx.putRow(ONE_OFF_NM_FROM_NM, ONE_OFF_NM);
        ctx.putRow(TAG, findOrCreateTag(ctx, TAG));
        String active = findOrCreatePublication(ctx, PUBLICATION, "LINK", "ACTIVE", null);
        ctx.putRow(PUBLICATION, active);
        ctx.putRow(PUBLICATION_DRAFT, findOrCreatePublication(ctx, PUBLICATION_DRAFT, "LINK", "DRAFT", null));
        ctx.putRow(FILTER, ctx.row(PUBLICATION_DRAFT));
        ctx.putRow(RECORDING, findOrCreatePublication(ctx, RECORDING, "MESSAGE_REPORT", "RECORDING",
                (String) ctx.row(TAG)));
        ctx.putRow(FOLDER, editablePublication(ctx, active).json().getString("repoPath"));
    }

    private static String findOrCreateCategory(SweepContext ctx) {
        SweepResponse found = ctx.send("anonymous", SweepRequest.get(C_ONE).var("categoryId", CATEGORY)
                .note("the fixture looks its category up"));
        if (found.status() != 200) {
            ctx.ok("admin", SweepRequest.post(C_CREATE).json(categoryBody(CATEGORY, 900, true)));
        }
        return CATEGORY;
    }

    static Map<String, Object> categoryBody(String categoryId, int priority, boolean publish) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("priority", priority);
        body.put("publish", publish);
        body.put("descs", new ArrayList<>(List.of(
                desc("en", categoryId, "The sweep's category (English)"),
                desc("da", categoryId + "-da", "The sweep's category (Danish)"))));
        return body;
    }

    private static Map<String, Object> desc(String lang, String name, String description) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lang", lang);
        d.put("name", name);
        d.put("description", description);
        return d;
    }

    private static String findOrCreateSeries(SweepContext ctx, String seriesId, String domain, String owner) {
        SweepResponse found = ctx.send("admin", SweepRequest.get(S_EDITABLE).var("seriesId", seriesId)
                .note("the fixture looks its series up"));
        if (found.status() != 200) {
            ctx.ok(owner, SweepRequest.post(S_CREATE).json(seriesBody(seriesId, domain)).domain(domain));
        }
        if (!"ACTIVE".equals(editableSeries(ctx, seriesId).json().getString("status"))) {
            status(ctx, owner, seriesId, domain, "ACTIVE", null);
        }
        return seriesId;
    }

    private static String findOrCreateOneOff(SweepContext ctx, String seriesId, String domain, String owner) {
        SweepResponse found = ctx.send("admin", SweepRequest.get(O_ONE).var("seriesId", seriesId)
                .note("the fixture looks its one-off up"));
        if (found.status() != 200) {
            ctx.ok(owner, SweepRequest.post(O_LIST).json(oneOffBody(seriesId, domain)).domain(domain));
        }
        return seriesId;
    }

    static String findOrCreateTag(SweepContext ctx, String name) {
        String tagId = tagIdOf(ctx, name);
        if (tagId != null) {
            return tagId;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "PRIVATE");
        body.put("name", name);
        body.put("locked", false);
        return ctx.ok("admin", SweepRequest.post(TAGS_CREATE).json(body)).json().getString("tagId");
    }

    static String tagIdOf(SweepContext ctx, String name) {
        SweepResponse found = ctx.ok("admin", SweepRequest.get(TAGS_SEARCH).query("name", name));
        return found.json().get("find { it.name == '" + name + "' }.tagId");
    }

    private static String findOrCreatePublication(SweepContext ctx, String title, String type, String status,
                                                  String tagId) {
        String id = publicationIdOf(ctx, title);
        if (id != null) {
            return id;
        }
        id = UUID.randomUUID().toString();
        ctx.ok("admin", SweepRequest.post(P_CREATE).json(publicationBody(id, title, type, status, tagId)));
        return id;
    }

    /** The publicationId of the legacy publication of the English title, or null */
    static String publicationIdOf(SweepContext ctx, String title) {
        SweepResponse found = ctx.ok("admin", SweepRequest.get(P_SEARCH_DETAILS).query("title", title)
                .query("maxSize", 1000));
        return found.json().get("data.find { it.descs.find { d -> d.lang == 'en' && d.title == '" + title
                + "' } != null }.publicationId");
    }

    // ------------------------------------------------------------------------------------------- //
    // Bodies
    // ------------------------------------------------------------------------------------------- //

    /** A weekly, query-backed, ACTIVE-able series of the domain with its name in two languages */
    static Map<String, Object> seriesBody(String seriesId, String domain) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", CATEGORY);
        s.put("domainId", domain);
        s.put("sortOrder", 7);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("kind", "SCHEDULED");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", true);
        s.put("firstIssueStartsAt", FIRST_START);
        s.put("criteria", NW.equals(domain)
                ? criteria(node("messageSeries", "dma-nw", "dma-nw-local"), node("messageMainType", "NW"),
                        node("domain", NW))
                : criteria(node("messageSeries", "dma-nm"), node("messageMainType", "NM"), node("domain", NM)));
        s.put("availability", "OWNER_ONLY");
        s.put("availableDomainIds", new ArrayList<>());
        s.put("languages", new ArrayList<>(List.of("da", "en")));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("messageSortBy", "AREA");
        s.put("messageSortOrder", "ASC");
        s.put("messagePublication", "EXTERNAL");
        s.put("releaseMode", "MANUAL_GATE");
        s.put("nextIssueCreation", "AUTO_ON_PUBLISH");
        s.put("languageSpecific", true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subtitle", "Sweep publications");
        params.put("columns", 2);
        s.put("reportParams", params);
        s.put("descs", new ArrayList<>(List.of(
                seriesDesc("da", seriesId + "-da", "SWP"),
                seriesDesc("en", seriesId, "SWP"))));
        return s;
    }

    static Map<String, Object> seriesDesc(String lang, String name, String abbreviation) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lang", lang);
        d.put("name", name);
        d.put("nameSuggestionPattern", abbreviation + " ${week}/${year}");
        d.put("fileNamePattern", "sweep-${year}-${week-2-digits}-" + lang);
        d.put("messageReferenceFormat", abbreviation + " ${week}/${year}");
        d.put("linkPattern", "https://example.org/sweep-publications/" + lang + "/${year}/${week}");
        return d;
    }

    @SafeVarargs
    static Map<String, Object> criteria(Map<String, Object>... nodes) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("schemaVersion", 1);
        c.put("match", "ALL");
        c.put("criteria", new ArrayList<>(List.of(nodes)));
        return c;
    }

    static Map<String, Object> node(String kind, String... values) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", kind);
        n.put("operator", "IN");
        n.put("values", new ArrayList<>(List.of(values)));
        return n;
    }

    /** A link-backed one-off of the domain, not made live: a DRAFT series holding one OPEN issue */
    static Map<String, Object> oneOffBody(String seriesId, String domain) {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("seriesId", seriesId);
        series.put("categoryId", CATEGORY);
        series.put("domainId", domain);
        series.put("contentMode", "EXTERNAL_LINK");
        series.put("messagePublication", "EXTERNAL");
        series.put("languages", new ArrayList<>(List.of("da", "en")));
        series.put("descs", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("lang", "da", "name", seriesId + "-da")),
                new LinkedHashMap<>(Map.of("lang", "en", "name", seriesId)))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("active", false);
        body.put("links", new ArrayList<>(List.of(
                link("da", "https://example.org/" + seriesId + "/da"),
                link("en", "https://example.org/" + seriesId + "/en"))));
        return body;
    }

    static Map<String, Object> link(String lang, String value) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("lang", lang);
        l.put("value", value);
        return l;
    }

    /** A legacy publication of the main type PUBLICATION, filed under the sweep's category */
    static Map<String, Object> publicationBody(String id, String title, String type, String status, String tagId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("publicationId", id);
        p.put("mainType", "PUBLICATION");
        p.put("type", type);
        p.put("status", status);
        p.put("category", new LinkedHashMap<>(Map.of("categoryId", CATEGORY)));
        p.put("languageSpecific", true);
        Map<String, Object> en = new LinkedHashMap<>();
        en.put("lang", "en");
        en.put("title", title);
        if ("LINK".equals(type)) {
            en.put("link", "https://example.org/" + title + "/en");
        }
        p.put("descs", new ArrayList<>(List.of(en)));
        if (tagId != null) {
            p.put("messageTag", new LinkedHashMap<>(Map.of("tagId", tagId)));
            p.put("printSettings", new LinkedHashMap<>(Map.of("report", "standard")));
            p.put("domain", new LinkedHashMap<>(Map.of("domainId", RECORDING_DOMAIN)));
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // Reads and writes the cases share
    // ------------------------------------------------------------------------------------------- //

    static SweepResponse editableSeries(SweepContext ctx, String seriesId) {
        return ctx.ok("admin", SweepRequest.get(S_EDITABLE).var("seriesId", seriesId));
    }

    static int seriesVersion(SweepContext ctx, String seriesId) {
        return editableSeries(ctx, seriesId).json().getInt("version");
    }

    /** A status transition of the series, as the identity sitting at the domain */
    static SweepResponse status(SweepContext ctx, String identity, String seriesId, String domain, String status,
                                String reason) {
        SweepRequest request = SweepRequest.put(S_STATUS).var("seriesId", seriesId)
                .query("version", seriesVersion(ctx, seriesId)).json("\"" + status + "\"").domain(domain);
        if (reason != null) {
            request.query("reason", reason);
        }
        return ctx.ok(identity, request);
    }

    static SweepResponse oneOff(SweepContext ctx, String seriesId) {
        return ctx.ok("admin", SweepRequest.get(O_ONE).var("seriesId", seriesId).query("lang", "en"));
    }

    static SweepResponse editablePublication(SweepContext ctx, Object publicationId) {
        return ctx.ok("admin", SweepRequest.get(P_EDITABLE).var("publicationId", publicationId));
    }

    /** The code of a publication refusal ({@code {code, message}}), or null */
    static String codeOf(SweepResponse response) {
        try {
            Object code = response.json().get("code");
            return code == null ? null : code.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sends the request and checks the publication refusal: the status and the code of its body */
    static SweepResponse refusedWith(SweepContext ctx, String identity, SweepRequest request, int status,
                                     String code) {
        SweepResponse response = ctx.send(identity, request);
        ctx.check(response.status() == status && code.equals(codeOf(response)),
                "answers " + status + " with the code " + code, response);
        return response;
    }

    /**
     * Removes a series and everything under it through the API, sitting at its own domain as the system
     * administrator: its issues (none of the sweep's is ever published, so each can be deleted), then the series.
     * Checks the series is gone and answers whether it is.
     */
    static boolean removeSeries(SweepContext ctx, String seriesId) {
        SweepResponse read = ctx.send("admin", SweepRequest.get(S_EDITABLE).var("seriesId", seriesId)
                .note("the series before its removal"));
        if (read.status() == 404) {
            return true;
        }
        String domain = read.json().getString("domainId");
        List<String> issues = ctx.bean(JdbcTemplate.class).queryForList(
                "SELECT i.\"publicId\" FROM public.\"PublicationIssue\" i JOIN public.\"PublicationSeries\" s"
                        + " ON s.id = i.series_id WHERE s.\"seriesId\" = ?", String.class, seriesId);
        for (String publicId : issues) {
            ctx.send("sysadmin", SweepRequest.delete(I_ONE).var("publicId", publicId).domain(domain)
                    .note("the removal of a sweep series' issue"));
        }
        ctx.send("sysadmin", SweepRequest.delete(S_ONE).var("seriesId", seriesId).domain(domain)
                .note("the removal of a sweep series"));
        SweepResponse after = ctx.send("admin", SweepRequest.get(S_EDITABLE).var("seriesId", seriesId)
                .note("read after the removal"));
        return after.status() == 404 && "SERIES_NOT_FOUND".equals(codeOf(after));
    }

    /** Removes a series and checks it is gone, failing the case when it is not */
    static void removeSeriesAndReadGone(SweepContext ctx, String seriesId) {
        ctx.check(removeSeries(ctx, seriesId), "the series " + seriesId + " is gone after its removal", null);
    }

    /** Removes a legacy publication and answers whether it is gone */
    static boolean removePublication(SweepContext ctx, String publicationId) {
        ctx.send("admin", SweepRequest.delete(P_ONE).var("publicationId", publicationId)
                .note("the removal of a sweep publication"));
        SweepResponse after = ctx.send("admin", SweepRequest.get(P_EDITABLE).var("publicationId", publicationId)
                .note("read after the removal"));
        return SweepEngine.matches(Cell.status(404, "No such publication: " + publicationId), after);
    }

    /** Removes a category and answers whether it is gone */
    static boolean removeCategory(SweepContext ctx, String categoryId) {
        ctx.send("admin", SweepRequest.delete(C_ONE).var("categoryId", categoryId)
                .note("the removal of a sweep category"));
        SweepResponse after = ctx.send("anonymous", SweepRequest.get(C_ONE).var("categoryId", categoryId)
                .note("read after the removal"));
        return after.status() == 404 && "CATEGORY_NOT_FOUND".equals(codeOf(after));
    }

    /** Unlocks and removes a message tag and answers whether it is gone */
    static boolean removeTag(SweepContext ctx, String tagId) {
        ctx.send("admin", SweepRequest.put(TAGS_UNLOCK).var("tagId", tagId).note("unlocked before its removal"));
        ctx.send("admin", SweepRequest.delete(TAGS_ONE).var("tagId", tagId).note("the removal of a sweep tag"));
        SweepResponse after = ctx.send("admin", SweepRequest.get(TAGS_READ).var("tagIds", tagId)
                .note("read after the removal"));
        return after.is2xx() && !after.text().contains(tagId);
    }

    /**
     * Runs the step with the {@code baseUri} setting pointed at this server. The report generator and the release
     * fetch {@code /rest/message-reports/report.pdf} from the backend itself at the base URI; the IT context's
     * {@code niord.json} sets {@code http://localhost:8888}, where nothing listens. Restored in {@code finally}, and
     * the cache evicted after each commit (the setting service evicts inside its transaction).
     */
    static void withReachableBaseUri(SweepContext ctx, SweepCase.Step step, SweepIdentity as) throws Exception {
        SettingsService settings = ctx.bean(SettingsService.class);
        String before = settings.getString("baseUri");
        String port = ctx.bean(Environment.class).getProperty("local.server.port");
        Tx.requiringNew().run(() -> settings.set("baseUri", "http://localhost:" + port));
        settings.evictFromCache("baseUri");
        try {
            step.run(ctx, as);
        } finally {
            Tx.requiringNew().run(() -> settings.set("baseUri", before));
            settings.evictFromCache("baseUri");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Tear-down
    // ------------------------------------------------------------------------------------------- //

    static List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        // The legacy publications first: the recording one holds the tag
        for (String row : List.of(RECORDING, PUBLICATION, PUBLICATION_DRAFT)) {
            if (ctx.hasRow(row) && !removePublication(ctx, (String) ctx.row(row))) {
                left.add(row + " (publication " + ctx.row(row) + ")");
            }
        }
        if (ctx.hasRow(TAG) && !removeTag(ctx, (String) ctx.row(TAG))) {
            left.add(TAG + " (tag " + ctx.row(TAG) + ")");
        }
        for (String row : List.of(ONE_OFF, ONE_OFF_NM, SERIES_NW, SERIES_NM)) {
            if (ctx.hasRow(row) && !removeSeries(ctx, (String) ctx.row(row))) {
                left.add(row + " (series " + ctx.row(row) + ")");
            }
        }

        // Rows a case should have removed and did not: named, and removed here so that the run leaves nothing
        Set<String> known = new LinkedHashSet<>();
        ctx.rows().values().forEach(v -> known.add(String.valueOf(v)));
        for (String seriesId : strays(ctx.ok("admin", SweepRequest.get(S_SEARCH_DETAILS)).json()
                .getList("seriesId", String.class), known)) {
            removeSeries(ctx, seriesId);
            left.add("a series a case left: " + seriesId + " (removed by the tear-down)");
        }
        SweepResponse publications = ctx.ok("admin", SweepRequest.get(P_SEARCH_DETAILS)
                .query("title", "sweep-publications-").query("maxSize", 1000));
        List<Map<String, Object>> rows = publications.json().getList("data");
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String id = (String) row.get("publicationId");
            if (id != null && !known.contains(id) && String.valueOf(row.get("descs")).contains("sweep-publications-")) {
                removePublication(ctx, id);
                left.add("a publication a case left: " + id + " (removed by the tear-down)");
            }
        }
        SweepResponse tags = ctx.ok("admin", SweepRequest.get(TAGS_SEARCH).query("name", "sweep-publications-"));
        for (Map<String, Object> tag : tags.json().<Map<String, Object>>getList("$")) {
            String tagId = (String) tag.get("tagId");
            if (String.valueOf(tag.get("name")).startsWith("sweep-publications-") && !known.contains(tagId)) {
                removeTag(ctx, tagId);
                left.add("a tag a case left: " + tag.get("name") + " (removed by the tear-down)");
            }
        }
        for (String categoryId : strays(ctx.ok("anonymous", SweepRequest.get(C_ALL)).json()
                .getList("categoryId", String.class), known)) {
            removeCategory(ctx, categoryId);
            left.add("a category a case left: " + categoryId + " (removed by the tear-down)");
        }

        if (ctx.hasRow(CATEGORY) && !removeCategory(ctx, CATEGORY)) {
            left.add(CATEGORY + " (category)");
        }
        return left;
    }

    private static List<String> strays(List<String> ids, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            if (id != null && id.startsWith("sweep-publications-") && !known.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Comparison
    // ------------------------------------------------------------------------------------------- //

    /** Numbers as longs, recursively, so an integer sent and a long read back compare equal */
    @SuppressWarnings("unchecked")
    static Object norm(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, norm(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(PublicationsSweepFixture::norm).toList();
        }
        return value;
    }

    /** The scalar fields of a series body, compared as sent */
    static final List<String> SERIES_SCALARS = List.of("seriesId", "categoryId", "domainId", "sortOrder",
            "contentMode", "cadence", "kind", "cutoffDefault", "nominalCutoffDay", "nominalCutoffTime",
            "numberingScheme", "timeRelation", "aliveAtCutoff", "availability", "reportId", "pageSize",
            "pageOrientation", "mapThumbnails", "messageSortBy", "messageSortOrder", "messagePublication",
            "releaseMode", "nextIssueCreation", "languageSpecific");

    /** A date as JSON carries it (epoch milliseconds or an ISO-8601 date-time), or null */
    static Long millis(Object date) {
        if (date == null) {
            return null;
        }
        if (date instanceof Number n) {
            return n.longValue();
        }
        return java.time.OffsetDateTime.parse(date.toString()).toInstant().toEpochMilli();
    }

    static final List<String> SERIES_DESC_FIELDS = List.of("name", "nameSuggestionPattern", "fileNamePattern",
            "messageReferenceFormat", "linkPattern");

    /** Checks the read series carries every field the body sent */
    static void checkSeries(SweepContext ctx, Map<String, Object> sent, SweepResponse read, String what) {
        checkSeries(ctx, sent, read.json().getMap(""), read, what);
    }

    /** Checks the series read (one row of the answer) carries every field the body sent */
    @SuppressWarnings("unchecked")
    static void checkSeries(SweepContext ctx, Map<String, Object> sent, Map<String, Object> got, SweepResponse read,
                            String what) {
        if (got == null) {
            ctx.fail(read, what + ": the answer holds the series");
            return;
        }
        for (String key : SERIES_SCALARS) {
            if (sent.containsKey(key)) {
                ctx.checkEquals(norm(sent.get(key)), norm(got.get(key)), what + ": " + key, read);
            }
        }
        if (sent.containsKey("firstIssueStartsAt")) {
            ctx.checkEquals(millis(sent.get("firstIssueStartsAt")), millis(got.get("firstIssueStartsAt")),
                    what + ": firstIssueStartsAt", read);
        }
        ctx.checkEquals(sent.get("languages"), got.get("languages"), what + ": the languages, in order", read);
        ctx.checkEquals(new LinkedHashSet<>((List<Object>) sent.get("availableDomainIds")),
                new LinkedHashSet<>(got.get("availableDomainIds") == null ? List.of()
                        : (List<Object>) got.get("availableDomainIds")),
                what + ": the sharing domains (a set)", read);
        ctx.checkEquals(norm(sent.get("criteria")), norm(got.get("criteria")), what + ": the criteria document",
                read);
        ctx.checkEquals(norm(sent.get("reportParams")), norm(got.get("reportParams")), what + ": the report "
                + "parameters", read);
        Map<String, Map<String, Object>> sentDescs = byLang((List<Map<String, Object>>) sent.get("descs"));
        Map<String, Map<String, Object>> gotDescs = byLang((List<Map<String, Object>>) got.get("descs"));
        ctx.checkEquals(sentDescs.keySet(), gotDescs.keySet(), what + ": the description languages", read);
        sentDescs.forEach((lang, d) -> {
            for (String field : SERIES_DESC_FIELDS) {
                if (d.containsKey(field)) {
                    ctx.checkEquals(d.get(field), gotDescs.getOrDefault(lang, Map.of()).get(field),
                            what + ": the " + lang + " " + field, read);
                }
            }
        });
    }

    static Map<String, Map<String, Object>> byLang(List<Map<String, Object>> descs) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> d : descs == null ? List.<Map<String, Object>>of() : descs) {
            out.put(String.valueOf(d.get("lang")), d);
        }
        return out;
    }

    /** The value of a per-language list ({@code [{lang, value}]}) for the language */
    static String valueFor(List<Map<String, Object>> list, String lang) {
        for (Map<String, Object> l : list == null ? List.<Map<String, Object>>of() : list) {
            if (Objects.equals(lang, l.get("lang"))) {
                return l.get("value") == null ? null : l.get("value").toString();
            }
        }
        return null;
    }
}
