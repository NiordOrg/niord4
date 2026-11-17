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
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixture of {@link MessagesSweepGroup}: the rows it makes through the API as the identity whose view the reads
 * show, the bodies its cases send, and the tear-down.
 *
 * <p>Messages: a published one and a draft in {@code dma-nw-local} (niord-nw; numbering NONE, so publishing takes no
 * number and cancelling mints no cancellation warning), made by the editor, and a draft in {@code dma-nm} (niord-nm)
 * made by the sysadmin in that domain (the test realm's admin holds no user role in niord-nm, which the create's own
 * read-back of a draft asks for). The draft holds two of every list (descriptions, parts with geometry and event
 * dates, areas, categories, charts, references to the published message, attachments: a text file and a PNG), so
 * that the reads show each list and the repository reads have files. Comments: one by the user and one by the editor
 * on the draft, one by the sysadmin on the niord-nm draft. Tags: a PRIVATE tag of the user (holding the draft) and of
 * the editor, a PUBLIC tag of niord-nw (holding the published message) and one of niord-nm (sysadmin), a locked
 * PUBLIC tag of niord-nw, a DOMAIN tag of niord-nw (holding the draft). Filters: one of the user and one of the editor. A report
 * without a domain (a public one) and one of niord-nm only.
 */
final class MessagesSweepFixture {

    static final String GROUP = "messages";

    // Messages (the row's id is the message uid)
    static final String PUBLISHED = "sweep-messages-published";
    static final String DRAFT = "sweep-messages-draft";
    static final String DRAFT_NM = "sweep-messages-draft-nm";

    // Comments (the id is the comment id; the message is in COMMENT_MESSAGE)
    static final String COMMENT_USER = "sweep-messages-comment-user";
    static final String COMMENT_EDITOR = "sweep-messages-comment-editor";
    static final String COMMENT_NM = "sweep-messages-comment-nm";

    // Tags (the id is the tag id)
    static final String TAG_PRIVATE_USER = "sweep-messages-tag-private-user";
    static final String TAG_PRIVATE_EDITOR = "sweep-messages-tag-private-editor";
    static final String TAG_PUBLIC_NW = "sweep-messages-tag-public-nw";
    static final String TAG_PUBLIC_NM = "sweep-messages-tag-public-nm";
    static final String TAG_LOCKED = "sweep-messages-tag-locked";
    static final String TAG_DOMAIN = "sweep-messages-tag-domain";

    // Filters (the id is the filter id)
    static final String FILTER_USER = "sweep-messages-filter-user";
    static final String FILTER_EDITOR = "sweep-messages-filter-editor";

    // A report (the id is the report id)
    static final String REPORT = "sweep-messages-report";
    // A report of niord-nm only (the id is the report id): the report list of another domain leaves it out
    static final String REPORT_NM = "sweep-messages-report-nm";

    // The repository: the draft's committed folder, and its two attachments (the id is the repository path)
    static final String FOLDER = "sweep-messages-folder";
    static final String ATTACHMENT_TEXT = "sweep-messages-attachment-text";
    static final String ATTACHMENT_IMAGE = "sweep-messages-attachment-image";

    static final List<String> ROWS = List.of(PUBLISHED, DRAFT, DRAFT_NM, COMMENT_USER, COMMENT_EDITOR, COMMENT_NM,
            TAG_PRIVATE_USER, TAG_PRIVATE_EDITOR, TAG_PUBLIC_NW, TAG_PUBLIC_NM, TAG_LOCKED, TAG_DOMAIN, FILTER_USER,
            FILTER_EDITOR, REPORT, REPORT_NM, FOLDER, ATTACHMENT_TEXT, ATTACHMENT_IMAGE);

    static final String NW = SweepIdentity.DOMAIN;
    static final String NM = SweepIdentity.OTHER_DOMAIN;
    static final String SERIES_NW = "dma-nw-local";
    static final String SERIES_NM = "dma-nm";

    /** The filter parameters every sweep filter carries */
    static final String FILTER_PARAMETERS = "messageSeries=dma-nw-local&status=DRAFT";

    /** A tag of the fixture: its owner, its domain header, its type, whether it is locked, and the messages it holds */
    record TagRow(String name, String owner, String domain, String type, boolean locked, String message) {
    }

    static final List<TagRow> TAGS = List.of(
            new TagRow(TAG_PRIVATE_USER, "user", NW, "PRIVATE", false, DRAFT),
            new TagRow(TAG_PRIVATE_EDITOR, "editor", NW, "PRIVATE", false, null),
            new TagRow(TAG_PUBLIC_NW, "editor", NW, "PUBLIC", false, PUBLISHED),
            new TagRow(TAG_PUBLIC_NM, "sysadmin", NM, "PUBLIC", false, null),
            new TagRow(TAG_LOCKED, "admin", NW, "PUBLIC", true, PUBLISHED),
            new TagRow(TAG_DOMAIN, "editor", NW, "DOMAIN", false, DRAFT));

    /** The message each comment row is on, its author and the author's domain header */
    static final Map<String, String[]> COMMENTS = new LinkedHashMap<>();

    static {
        COMMENTS.put(COMMENT_USER, new String[] {DRAFT, "user", NW});
        COMMENTS.put(COMMENT_EDITOR, new String[] {DRAFT, "editor", NW});
        COMMENTS.put(COMMENT_NM, new String[] {DRAFT_NM, "sysadmin", NM});
    }

    /** Seeded reference data the message bodies name: two child areas, two categories, two charts */
    List<Integer> areas = List.of();
    List<Integer> categories = List.of();
    List<String> charts = List.of();

    // ------------------------------------------------------------------------------------------- //
    // Set-up
    // ------------------------------------------------------------------------------------------- //

    void setUp(SweepContext ctx) throws IOException {
        JdbcTemplate jdbc = ctx.bean(JdbcTemplate.class);
        areas = jdbc.queryForList("SELECT id FROM public.\"Area\" WHERE parent_id IS NOT NULL ORDER BY id LIMIT 2",
                Integer.class);
        categories = jdbc.queryForList("SELECT id FROM public.\"Category\" WHERE type = 'CATEGORY' ORDER BY id "
                + "LIMIT 2", Integer.class);
        charts = jdbc.queryForList("SELECT \"chartNumber\" FROM public.\"Chart\" ORDER BY id LIMIT 2", String.class);

        // The messages: the published one first, which the draft references
        ctx.putRow(PUBLISHED, findOrCreatePublished(ctx));
        ctx.putRow(DRAFT, findOrCreateDraft(ctx));
        ctx.putRow(DRAFT_NM, findOrCreateDraftNm(ctx));

        // The draft's committed folder and attachments, as its editable form names them
        SweepResponse draft = ctx.ok("editor", editable(ctx.row(DRAFT)));
        JsonPath json = draft.json();
        ctx.putRow(FOLDER, json.getString("repoPath"));
        ctx.putRow(ATTACHMENT_TEXT, json.getString("attachments.find { it.fileName == '" + ATTACHMENT_TEXT
                + ".txt' }.path"));
        ctx.putRow(ATTACHMENT_IMAGE, json.getString("attachments.find { it.fileName == '" + ATTACHMENT_IMAGE
                + ".png' }.path"));

        for (Map.Entry<String, String[]> comment : COMMENTS.entrySet()) {
            ctx.putRow(comment.getKey(), findOrCreateComment(ctx, comment.getKey(), comment.getValue()));
        }
        for (TagRow tag : TAGS) {
            ctx.putRow(tag.name(), findOrCreateTag(ctx, tag));
        }
        ctx.putRow(FILTER_USER, findOrCreateFilter(ctx, "user", FILTER_USER));
        ctx.putRow(FILTER_EDITOR, findOrCreateFilter(ctx, "editor", FILTER_EDITOR));
        ctx.putRow(REPORT, findOrCreateReport(ctx, REPORT, List.of()));
        ctx.putRow(REPORT_NM, findOrCreateReport(ctx, REPORT_NM, List.of(NM)));
    }

    /** The uid of the message of the English title in the series and status, or null */
    static String messageUid(SweepContext ctx, String title, String series, String status) {
        List<String> uids = ctx.bean(JdbcTemplate.class).queryForList(
                "SELECT m.uid FROM public.\"Message\" m JOIN public.\"MessageDesc\" d ON d.entity_id = m.id "
                        + "JOIN public.\"MessageSeries\" s ON s.id = m.\"messageSeries_id\" "
                        + "WHERE d.lang = 'en' AND d.title = ? AND s.\"seriesId\" = ? AND m.status = ? ORDER BY m.id",
                String.class, title, series, status);
        return uids.isEmpty() ? null : uids.getFirst();
    }

    private String findOrCreatePublished(SweepContext ctx) throws IOException {
        String uid = messageUid(ctx, PUBLISHED, SERIES_NW, "PUBLISHED");
        if (uid != null) {
            return uid;
        }
        uid = createMessage(ctx, "editor", NW, "NW", SERIES_NW, PUBLISHED, false, List.of());
        ctx.ok("editor", status(uid, "VERIFIED"));
        ctx.ok("editor", status(uid, "PUBLISHED"));
        return uid;
    }

    private String findOrCreateDraft(SweepContext ctx) throws IOException {
        String uid = messageUid(ctx, DRAFT, SERIES_NW, "DRAFT");
        if (uid != null) {
            return uid;
        }
        return createMessage(ctx, "editor", NW, "NW", SERIES_NW, DRAFT, true, List.of(
                Map.of("messageId", (String) ctx.row(PUBLISHED), "type", "REFERENCE"),
                Map.of("messageId", (String) ctx.row(PUBLISHED), "type", "UPDATE")));
    }

    private String findOrCreateDraftNm(SweepContext ctx) throws IOException {
        String uid = messageUid(ctx, DRAFT_NM, SERIES_NM, "DRAFT");
        if (uid != null) {
            return uid;
        }
        return createMessage(ctx, "sysadmin", NM, "NM", SERIES_NM, DRAFT_NM, false, List.of());
    }

    /**
     * Creates a draft through the API as the admin app does: the new-message template (its uid and folders), the
     * attachments uploaded into its edit folder when asked, and the create. Answers the uid.
     */
    String createMessage(SweepContext ctx, String identity, String domain, String mainType, String series,
                         String title, boolean attachments, List<Map<String, String>> references) throws IOException {
        Map<String, Object> body = newBody(ctx, identity, domain, mainType, series, title, attachments, references);
        SweepResponse created = ctx.ok(identity, SweepRequest.post("/rest/messages/message").json(body)
                .domain(domain));
        if (!created.is2xx()) {
            throw new IllegalStateException("The fixture could not create the message " + title + ": "
                    + created.status() + " " + created.preview());
        }
        return created.json().getString("id");
    }

    /** A message body on a fresh template of the identity, with two of every list */
    Map<String, Object> newBody(SweepContext ctx, String identity, String domain, String mainType, String series,
                                String title, boolean attachments, List<Map<String, String>> references)
            throws IOException {
        SweepResponse template = ctx.ok(identity, SweepRequest.get("/rest/messages/new-message-template")
                .query("mainType", mainType).domain(domain));
        if (!template.is2xx()) {
            throw new IllegalStateException("No message template for " + identity + " in " + domain + ": "
                    + template.status() + " " + template.preview());
        }
        JsonPath t = template.json();
        String editRepoPath = t.getString("editRepoPath");
        List<Object> uploaded = new ArrayList<>();
        if (attachments) {
            SweepResponse files = ctx.ok(identity, SweepRequest.post("/rest/messages/attachments/{*folder}")
                    .var("folder", editRepoPath)
                    .file("file", ATTACHMENT_TEXT + ".txt", ATTACHMENT_TEXT.getBytes(StandardCharsets.UTF_8),
                            "text/plain")
                    .file("file", ATTACHMENT_IMAGE + ".png", png(), "image/png")
                    .domain(domain));
            uploaded.addAll(files.json().getList("$"));
        }
        return body(t.getString("id"), t.getString("repoPath"), editRepoPath, mainType, series, title, references,
                uploaded);
    }

    /** The body of a draft: two descriptions, two parts with geometry and event dates, two of each reference list */
    Map<String, Object> body(String uid, String repoPath, String editRepoPath, String mainType, String series,
                             String title, List<Map<String, String>> references, List<Object> attachments) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", repoPath);
        msg.put("editRepoPath", editRepoPath);
        msg.put("revision", 0);
        msg.put("mainType", mainType);
        msg.put("type", "NW".equals(mainType) ? "LOCAL_WARNING" : "TEMPORARY_NOTICE");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        msg.put("messageSeries", Map.of("seriesId", series, "mainType", mainType));
        msg.put("descs", List.of(desc("en", title), desc("da", title + "-da")));
        msg.put("areas", areas.stream().map(id -> Map.of("id", id)).toList());
        msg.put("categories", categories.stream().map(id -> Map.of("id", id)).toList());
        msg.put("charts", charts.stream().map(n -> Map.of("chartNumber", n)).toList());
        msg.put("parts", List.of(
                part(title + " part 1", point(11.25, 56.5)),
                part(title + " part 2", line(10.0, 55.0, 10.5, 55.25))));
        msg.put("references", references);
        msg.put("attachments", attachments);
        return msg;
    }

    static Map<String, Object> desc(String lang, String title) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("lang", lang);
        desc.put("title", title);
        desc.put("vicinity", "Vicinity " + lang);
        return desc;
    }

    /** A DETAILS part with an English and a Danish description, one event date interval and the feature */
    static Map<String, Object> part(String subject, Map<String, Object> feature) {
        long from = 32503680000000L; // 3000-01-01: never in a list of current messages
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "DETAILS");
        part.put("descs", List.of(
                Map.of("lang", "en", "subject", subject, "details", "<p>" + subject + "</p>"),
                Map.of("lang", "da", "subject", subject + " da", "details", "<p>" + subject + " da</p>")));
        part.put("eventDates", List.of(Map.of("allDay", false, "fromDate", from, "toDate", from + 3_600_000L)));
        part.put("geometry", Map.of("type", "FeatureCollection", "features", List.of(feature)));
        return part;
    }

    static Map<String, Object> point(double lon, double lat) {
        return Map.of("type", "Feature", "geometry", Map.of("type", "Point", "coordinates", List.of(lon, lat)),
                "properties", Map.of("name:en", "Buoy", "name:da", "Boeje"));
    }

    static Map<String, Object> line(double lon1, double lat1, double lon2, double lat2) {
        return Map.of("type", "Feature", "geometry", Map.of("type", "LineString",
                "coordinates", List.of(List.of(lon1, lat1), List.of(lon2, lat2))), "properties", Map.of());
    }

    /** A 16 x 16 PNG */
    static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(170, 211, 223));
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static Integer findOrCreateComment(SweepContext ctx, String name, String[] spec) {
        String messageUid = (String) ctx.row(spec[0]);
        SweepResponse comments = ctx.ok(spec[1], comments(messageUid).domain(spec[2]));
        Integer id = comments.json().get("find { it.comment == '" + name + "' }.id");
        if (id != null) {
            return id;
        }
        SweepResponse created = ctx.ok(spec[1], SweepRequest.post("/rest/messages/message/{messageId}/comment")
                .var("messageId", messageUid).json(Map.of("comment", name)).domain(spec[2]));
        return created.json().getInt("id");
    }

    private static String findOrCreateTag(SweepContext ctx, TagRow tag) {
        SweepResponse found = ctx.ok(tag.owner(), SweepRequest.get("/rest/tags/search").query("name", tag.name())
                .domain(tag.domain()));
        String tagId = found.json().get("find { it.name == '" + tag.name() + "' }.tagId");
        if (tagId != null) {
            return tagId;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", tag.type());
        body.put("name", tag.name());
        body.put("locked", false);
        SweepResponse created = ctx.ok(tag.owner(), SweepRequest.post("/rest/tags/tag").json(body)
                .domain(tag.domain()));
        tagId = created.json().getString("tagId");
        if (tag.message() != null) {
            ctx.ok(tag.owner(), SweepRequest.put("/rest/tags/tag/{tagId}/add-messages").var("tagId", tagId)
                    .json(List.of(ctx.row(tag.message()))).domain(tag.domain()));
        }
        if (tag.locked()) {
            body.put("tagId", tagId);
            body.put("locked", true);
            ctx.ok(tag.owner(), SweepRequest.put("/rest/tags/tag/{tagId}").var("tagId", tagId).json(body)
                    .domain(tag.domain()));
        }
        return tagId;
    }

    private static Integer findOrCreateFilter(SweepContext ctx, String owner, String name) {
        SweepResponse all = ctx.ok(owner, SweepRequest.get("/rest/filters/all"));
        Integer id = all.json().get("find { it.name == '" + name + "' }.id");
        if (id != null) {
            return id;
        }
        SweepResponse created = ctx.ok(owner, SweepRequest.post("/rest/filters/filter")
                .json(Map.of("name", name, "parameters", FILTER_PARAMETERS)));
        return created.json().getInt("id");
    }

    private static String findOrCreateReport(SweepContext ctx, String reportId, List<String> domains) {
        SweepResponse all = ctx.ok("sysadmin", SweepRequest.get("/rest/message-reports/all"));
        if (all.json().getList("reportId").contains(reportId)) {
            return reportId;
        }
        ctx.ok("sysadmin", SweepRequest.post("/rest/message-reports/report").json(report(reportId, reportId, domains)));
        return reportId;
    }

    /** A public report (no domain) of the standard template */
    static Map<String, Object> report(String reportId, String name) {
        return report(reportId, name, List.of());
    }

    /** A report of the standard template for the domains named (none: a public report) */
    static Map<String, Object> report(String reportId, String name, List<String> domains) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportId", reportId);
        report.put("name", name);
        report.put("sortOrder", 900);
        report.put("templatePath", "/templates/messages/message-list-pdf.ftl");
        report.put("domains", domains.stream().map(d -> Map.of("domainId", d)).toList());
        report.put("properties", Map.of("sweepProperty", "sweep-value"));
        report.put("params", Map.of());
        return report;
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests the fixture and the cases share
    // ------------------------------------------------------------------------------------------- //

    static SweepRequest editable(Object uid) {
        return SweepRequest.get("/rest/messages/editable-message/{messageId}").var("messageId", uid);
    }

    static SweepRequest status(Object uid, String status) {
        return SweepRequest.put("/rest/messages/message/{messageId}/status").var("messageId", uid).json(status);
    }

    static SweepRequest comments(Object uid) {
        return SweepRequest.get("/rest/messages/message/{messageId}/comments").var("messageId", uid);
    }

    // ------------------------------------------------------------------------------------------- //
    // Tear-down
    // ------------------------------------------------------------------------------------------- //

    /**
     * Removes the fixture's rows and reads each one gone; answers what is still there. The drafts end DELETED, the
     * published message CANCELLED (a published message can never be DELETED); the comments stay on them (no endpoint
     * removes a comment).
     */
    List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        for (TagRow tag : TAGS) {
            if (!ctx.hasRow(tag.name())) {
                continue;
            }
            Object tagId = ctx.row(tag.name());
            if (tag.locked()) {
                ctx.send("admin", SweepRequest.put("/rest/tags/unlock-tag/{tagId}").var("tagId", tagId)
                        .domain(tag.domain()));
            }
            ctx.send(tag.owner(), SweepRequest.delete("/rest/tags/tag/{tagId}").var("tagId", tagId)
                    .domain(tag.domain()));
            SweepResponse read = ctx.send(tag.owner(), SweepRequest.get("/rest/tags/tag/{tagIds}")
                    .var("tagIds", tagId).domain(tag.domain()).note("read after the tear-down's delete"));
            if (read.status() != 200 || !read.json().getList("$").isEmpty()) {
                left.add(tag.name() + " (tag " + tagId + ", read after the delete: " + read.status() + " "
                        + read.preview() + ")");
            }
        }
        for (String[] filter : new String[][] {{FILTER_USER, "user"}, {FILTER_EDITOR, "editor"}}) {
            if (!ctx.hasRow(filter[0])) {
                continue;
            }
            Object id = ctx.row(filter[0]);
            ctx.send(filter[1], SweepRequest.delete("/rest/filters/filter/{filterId}").var("filterId", id));
            SweepResponse read = ctx.send(filter[1], SweepRequest.get("/rest/filters/filter/{filterId}")
                    .var("filterId", id).note("read after the tear-down's delete"));
            if (read.status() != 204) {
                left.add(filter[0] + " (filter " + id + ", read after the delete: " + read.status() + ")");
            }
        }
        for (String report : List.of(REPORT, REPORT_NM)) {
            if (!ctx.hasRow(report)) {
                continue;
            }
            ctx.send("sysadmin", SweepRequest.delete("/rest/message-reports/report/{reportId}")
                    .var("reportId", report));
            SweepResponse all = ctx.send("sysadmin", SweepRequest.get("/rest/message-reports/all")
                    .note("read after the tear-down's delete"));
            if (all.status() != 200 || all.json().getList("reportId").contains(report)) {
                left.add(report + " (read after the delete: " + all.status() + ")");
            }
        }
        endMessage(ctx, left, DRAFT, "editor", NW, "DELETED");
        endMessage(ctx, left, DRAFT_NM, "sysadmin", NM, "DELETED");
        endMessage(ctx, left, PUBLISHED, "editor", NW, "CANCELLED");
        return left;
    }

    /** Moves a fixture message to its end status and reads it there */
    private static void endMessage(SweepContext ctx, List<String> left, String row, String identity, String domain,
                                   String end) {
        if (!ctx.hasRow(row)) {
            return;
        }
        Object uid = ctx.row(row);
        ctx.send(identity, status(uid, end).domain(domain));
        SweepResponse read = ctx.send(identity, editable(uid).domain(domain).note("read after the tear-down"));
        String status = read.status() == 200 ? read.json().getString("status") : null;
        if (!end.equals(status)) {
            left.add(row + " (message " + uid + ", status after the tear-down: " + status + ", answer "
                    + read.status() + ")");
        }
    }
}
