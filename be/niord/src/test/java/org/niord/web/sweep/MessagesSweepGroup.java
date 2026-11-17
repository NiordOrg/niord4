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
import org.niord.core.mail.MailService;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.niord.web.sweep.MessagesSweepFixture.ATTACHMENT_IMAGE;
import static org.niord.web.sweep.MessagesSweepFixture.ATTACHMENT_TEXT;
import static org.niord.web.sweep.MessagesSweepFixture.COMMENTS;
import static org.niord.web.sweep.MessagesSweepFixture.COMMENT_EDITOR;
import static org.niord.web.sweep.MessagesSweepFixture.COMMENT_USER;
import static org.niord.web.sweep.MessagesSweepFixture.DRAFT;
import static org.niord.web.sweep.MessagesSweepFixture.DRAFT_NM;
import static org.niord.web.sweep.MessagesSweepFixture.FILTER_EDITOR;
import static org.niord.web.sweep.MessagesSweepFixture.FILTER_PARAMETERS;
import static org.niord.web.sweep.MessagesSweepFixture.FILTER_USER;
import static org.niord.web.sweep.MessagesSweepFixture.FOLDER;
import static org.niord.web.sweep.MessagesSweepFixture.NM;
import static org.niord.web.sweep.MessagesSweepFixture.NW;
import static org.niord.web.sweep.MessagesSweepFixture.PUBLISHED;
import static org.niord.web.sweep.MessagesSweepFixture.REPORT;
import static org.niord.web.sweep.MessagesSweepFixture.REPORT_NM;
import static org.niord.web.sweep.MessagesSweepFixture.SERIES_NM;
import static org.niord.web.sweep.MessagesSweepFixture.SERIES_NW;
import static org.niord.web.sweep.MessagesSweepFixture.TAGS;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_DOMAIN;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_LOCKED;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_PRIVATE_EDITOR;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_PRIVATE_USER;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_PUBLIC_NM;
import static org.niord.web.sweep.MessagesSweepFixture.TAG_PUBLIC_NW;
import static org.niord.web.sweep.MessagesSweepFixture.editable;
import static org.niord.web.sweep.MessagesSweepFixture.status;

/**
 * The endpoint sweep of the messages group ({@code endpoints/messages.json}): the 71 handlers of the message, tag,
 * comment, filter, report, repository, mail, map image and public API controllers.
 *
 * <p>The fixture ({@link MessagesSweepFixture}) is made through the API as the identity whose view the reads show.
 * The code-checked handlers' cells run on its rows: a message of the caller's domain and one of the other domain
 * (domain), a comment, a PRIVATE tag and a filter of another identity (owner), a locked tag (the admin rule), a ticket
 * with and without the role the admin app asks for (ticket). The reads that narrow what a caller sees without refusing
 * (the message search, the export, the PDFs, the tag reads, the filters) check what each identity sees in their sweep.
 *
 * <p>Each write's sweep makes a row of its own, reads back every field it sent, removes it and reads it gone. Message
 * writes end their drafts DELETED; the status sweep publishes in {@code dma-nw-local} (no number, no cancellation
 * warning) and ends CANCELLED. What no endpoint removes is listed in {@link #leftByDesign()}.
 */
class MessagesSweepGroup implements SweepGroup {

    /** An id no row has */
    static final int UNKNOWN = 987654321;

    private final MessagesSweepFixture fixture = new MessagesSweepFixture();

    /** A legacy publication of the seed, for the publication reads and computations (null when there is none) */
    private String publicationId;

    /** An area of the seed with active sub-areas */
    private Integer parentArea;

    @Override
    public String name() {
        return "messages";
    }

    @Override
    public Set<String> rows() {
        return new HashSet<>(MessagesSweepFixture.ROWS);
    }

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        fixture.setUp(ctx);
        JdbcTemplate jdbc = ctx.bean(JdbcTemplate.class);
        List<String> publications = jdbc.queryForList("SELECT \"publicationId\" FROM public.\"Publication\" "
                + "WHERE status = 'ACTIVE' ORDER BY id LIMIT 1", String.class);
        publicationId = publications.isEmpty() ? null : publications.getFirst();
        List<Integer> parents = jdbc.queryForList("SELECT p.id FROM public.\"Area\" p WHERE EXISTS (SELECT 1 FROM "
                + "public.\"Area\" c WHERE c.parent_id = p.id AND c.active = true) ORDER BY p.id LIMIT 1", Integer.class);
        parentArea = parents.isEmpty() ? null : parents.getFirst();
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        return fixture.tearDown(ctx);
    }

    @Override
    public Map<String, String> leftByDesign() {
        Map<String, String> left = new LinkedHashMap<>();
        left.put("the fixture's messages sweep-messages-draft, -draft-nm (DELETED) and -published (CANCELLED), and "
                        + "each message write's own drafts (DELETED) and the status sweep's message (CANCELLED)",
                "no endpoint removes a message; a draft ends DELETED, a published message can only end CANCELLED");
        left.put("the comments on those messages (the fixture's three, each comment write's own)",
                "no endpoint removes a comment");
        left.put("the files of the fixture draft's folder, and the folders the message writes copy",
                "no endpoint removes a committed message folder; temp folders expire after a day");
        left.put("the batch instance of the report upload (report-import, file sweep-messages-<unique>.json)",
                "no endpoint removes a batch instance");
        return left;
    }

    /**
     * The tables the group's rows live in, and the message tables its writes rewrite: a message save replaces the
     * rows of the message's unordered collections (its categories have no order column), and the free space that
     * leaves made a later IT read a new message's categories back in another order (MessageRoundTripITTest in the
     * reverse class order, found at the merge of the lanes)
     */
    @Override
    public Set<String> tables() {
        return Set.of("MessageTag", "MessageTag_Message", "MessageFilter", "FmReport", "FmReport_Domain", "RepoFile",
                "ScheduledMail", "ScheduledMailRecipient",
                "Message", "MessageDesc", "MessageHistory", "Message_Area", "Message_Category", "Message_Chart",
                "MessagePart", "MessagePartDesc", "DateInterval", "FeatureCollection", "Feature", "Reference",
                "ReferenceDesc", "Attachment", "AttachmentDesc", "Comment", "Comment_emailAddresses",
                "BaseMessagePromulgation", "NavtexMessagePromulgation", "NavtexMessagePromulgation_NavtexTransmitter",
                "AudioMessagePromulgation", "SafetyNetMessagePromulgation", "TwitterMessagePromulgation");
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        filters(cases);
        comments(cases);
        tags(cases);
        messageReads(cases);
        messageWrites(cases);
        computations(cases);
        searches(cases);
        mailAndMapImages(cases);
        reports(cases);
        publicApi(cases);
        repository(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // Filters: a caller with no user is refused (403); a filter is its owner's alone
    // ------------------------------------------------------------------------------------------- //

    private void filters(SweepCases cases) {
        cases.add("getMessageFilters", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/filters/all"))
                .as("user")
                .sweep((ctx, as) -> {
                    SweepResponse mine = ctx.ok(as, SweepRequest.get("/rest/filters/all"));
                    absent(ctx, mine, FILTER_EDITOR, "the user's filters leave out the editor's filter");
                }));

        cases.add("getMessageFilter", SweepCase
                .of((ctx, row) -> filter(ctx.row(row == null ? FILTER_USER : row)))
                .as("user")
                .probes((ctx, as) -> ctx.call(as.name(), filter(UNKNOWN).note("unknown-id probe"),
                        Cell.status(204, null))));

        cases.add("createMessageFilter", SweepCase
                .of((ctx, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (row != null) {
                        // The other owner's filter as it is: the write is refused before it changes anything
                        body.put("id", ctx.row(row));
                        body.put("name", row);
                    } else {
                        body.put("name", ctx.name("filter-refused"));
                    }
                    body.put("parameters", FILTER_PARAMETERS);
                    return SweepRequest.post("/rest/filters/filter").json(body);
                })
                .as("user")
                .sweep((ctx, as) -> createFilterRoundTrip(ctx, as, "/rest/filters/filter"))
                .variants((ctx, as) -> createFilterRoundTrip(ctx, as, "/rest/filters/filter/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/filters/filter").json(Map.of()).note("empty body"),
                            Cell.status(400, "A filter needs a name"));
                    ctx.call(as.name(), SweepRequest.post("/rest/filters/filter")
                                    .json(Map.of("id", UNKNOWN, "name", ctx.name("unknown"))).note("unknown-id probe"),
                            Cell.status(400, "Invalid filter id"));
                }));

        cases.add("deleteMessageFilter", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/filters/filter/{filterId}")
                        .var("filterId", ctx.row(row == null ? FILTER_USER : row)))
                .as("user")
                .sweep((ctx, as) -> {
                    int id = ctx.ok(as, SweepRequest.post("/rest/filters/filter").json(Map.of("name",
                            ctx.name("filter-delete-" + ctx.unique()), "parameters", FILTER_PARAMETERS))).json()
                            .getInt("id");
                    removeFilterAndReadGone(ctx, as, id);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/filters/filter/{filterId}")
                        .var("filterId", UNKNOWN).note("unknown-id probe"), Cell.status(204, null))));
    }

    private static SweepRequest filter(Object id) {
        return SweepRequest.get("/rest/filters/filter/{filterId}").var("filterId", id);
    }

    /** Creates a filter, reads it back, saves it again under its name (an update), removes it and reads it gone */
    private static void createFilterRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("filter-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(Map.of("name", name,
                "parameters", FILTER_PARAMETERS)));
        int id = created.json().getInt("id");
        try {
            SweepResponse read = ctx.ok(as, filter(id));
            ctx.checkEquals(name, read.json().getString("name"), "the filter's name", read);
            ctx.checkEquals(FILTER_PARAMETERS, read.json().getString("parameters"), "the filter's parameters", read);
            // A filter of the same name is the same filter, updated
            SweepResponse again = ctx.ok(as, SweepRequest.post(pattern).json(Map.of("name", name,
                    "parameters", "status=PUBLISHED")));
            ctx.checkEquals(id, again.json().getInt("id"), "the id of a filter saved again under its name", again);
            read = ctx.ok(as, filter(id));
            ctx.checkEquals("status=PUBLISHED", read.json().getString("parameters"),
                    "the parameters of the filter saved again", read);
        } finally {
            removeFilterAndReadGone(ctx, as, id);
        }
    }

    private static void removeFilterAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/filters/filter/{filterId}").var("filterId", id));
        // An unknown filter reads as no content
        ctx.call(as.name(), filter(id).note("read after the delete"), Cell.status(204, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // Comments: the message's own domain only; an edit is the author's or an admin's
    // ------------------------------------------------------------------------------------------- //

    private void comments(SweepCases cases) {
        cases.add("getComments", SweepCase
                .of((ctx, row) -> MessagesSweepFixture.comments(ctx.row(row == null ? DRAFT : row))));

        cases.add("createComment", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/message/{messageId}/comment")
                        .var("messageId", ctx.row(row == null ? DRAFT : row))
                        .json(Map.of("comment", ctx.name("comment-refused"))))
                .sweep((ctx, as) -> {
                    String text = ctx.name("comment-" + ctx.unique());
                    // An address that is no mailbox at all: stored with the comment, left out of the mail, so no
                    // mail is queued (the comment shows it to its author and to admins only)
                    SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/messages/message/{messageId}/comment")
                            .var("messageId", ctx.row(DRAFT)).json(Map.of("comment", text,
                                    "emailAddresses", List.of(NO_MAILBOX))));
                    int id = created.json().getInt("id");
                    readComment(ctx, as.name(), id, text, null);
                    commentAddresses(ctx, as.name(), id, List.of(NO_MAILBOX), "its author");
                    commentAddresses(ctx, "admin", id, List.of(NO_MAILBOX), "an admin");
                    commentAddresses(ctx, as.name().equals("user") ? "editor" : "user", id, null,
                            "another user of the domain");
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/message/{messageId}/comment")
                                    .var("messageId", ctx.row(DRAFT)).json(Map.of("comment", " ")).note("blank text"),
                            Cell.status(400, "A comment must have a text"));
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/message/{messageId}/comment")
                                    .var("messageId", "sweep-no-such-message").json(Map.of("comment", "x"))
                                    .note("unknown-id probe"),
                            Cell.status(400, "Invalid message ID sweep-no-such-message"));
                }));

        cases.add("updateComment", SweepCase
                .of((ctx, row) -> updateComment(ctx, row == null ? COMMENT_USER : row, null))
                .sweep(MessagesSweepGroup::updateOwnComment)
                .allowed((ctx, as, row) -> {
                    if (row == null) {
                        // A tier cell of the full run: the identity edits a comment of its own
                        updateOwnComment(ctx, as);
                        return;
                    }
                    // An admin edits another's comment: sent as it is, so nothing changes
                    ctx.ok(as, updateComment(ctx, row, null));
                    readComment(ctx, COMMENTS.get(row)[1], (Integer) ctx.row(row), row, null);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), updateComment(ctx, COMMENT_USER, UNKNOWN).note("the body's id is not the path's"),
                            Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/messages/message/{messageId}/comment/{commentId}")
                                    .var("messageId", ctx.row(DRAFT)).var("commentId", UNKNOWN)
                                    .json(Map.of("id", UNKNOWN, "comment", "x")).note("unknown-id probe"),
                            Cell.status(400, "Invalid message or comment ID"));
                }));

        cases.add("acknowledgeComment", SweepCase
                .of((ctx, row) -> ackComment(ctx, row == null ? COMMENT_EDITOR : row))
                .sweep((ctx, as) -> {
                    // The user acknowledges a comment the editor makes for the purpose
                    int id = ctx.ok("editor", SweepRequest.post("/rest/messages/message/{messageId}/comment")
                            .var("messageId", ctx.row(DRAFT))
                            .json(Map.of("comment", ctx.name("comment-ack-" + ctx.unique())))).json().getInt("id");
                    SweepResponse acked = ctx.ok(as, SweepRequest.put(
                                    "/rest/messages/message/{messageId}/comment/{commentId}/ack")
                            .var("messageId", ctx.row(DRAFT)).var("commentId", id));
                    ctx.check(acked.json().getString("acknowledgedBy") != null, "the answer names who acknowledged",
                            acked);
                    readComment(ctx, as.name(), id, null, acked.json().getString("acknowledgedBy"));
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put(
                                "/rest/messages/message/{messageId}/comment/{commentId}/ack")
                        .var("messageId", ctx.row(DRAFT)).var("commentId", UNKNOWN).note("unknown-id probe"),
                        Cell.status(400, "Invalid message or comment ID"))));
    }

    /** A comment's e-mail address that parses as no mailbox: kept with the comment, never mailed */
    static final String NO_MAILBOX = "<sweep-messages-no-mailbox";

    /** Reads the draft's comments as the identity and checks the e-mail addresses it sees on the comment */
    private static void commentAddresses(SweepContext ctx, String identity, int id, List<String> expected,
                                         String who) {
        SweepResponse read = ctx.ok(identity, MessagesSweepFixture.comments(ctx.row(DRAFT)));
        ctx.checkEquals(expected, read.json().getList("find { it.id == " + id + " }.emailAddresses"),
                "the comment's e-mail addresses read by " + who, read);
    }

    /** Makes a comment as the identity, changes its text, reads it back */
    private static void updateOwnComment(SweepContext ctx, SweepIdentity as) {
        int id = ctx.ok(as, SweepRequest.post("/rest/messages/message/{messageId}/comment")
                .var("messageId", ctx.row(DRAFT))
                .json(Map.of("comment", ctx.name("comment-update-" + ctx.unique())))).json().getInt("id");
        String changed = ctx.name("comment-updated-" + ctx.unique());
        ctx.ok(as, SweepRequest.put("/rest/messages/message/{messageId}/comment/{commentId}")
                .var("messageId", ctx.row(DRAFT)).var("commentId", id)
                .json(Map.of("id", id, "comment", changed)));
        readComment(ctx, as.name(), id, changed, null);
    }

    private static SweepRequest updateComment(SweepContext ctx, String row, Integer bodyId) {
        Object id = ctx.row(row);
        return SweepRequest.put("/rest/messages/message/{messageId}/comment/{commentId}")
                .var("messageId", ctx.row(COMMENTS.get(row)[0])).var("commentId", id)
                .json(Map.of("id", bodyId == null ? id : bodyId, "comment", row));
    }

    private static SweepRequest ackComment(SweepContext ctx, String row) {
        return SweepRequest.put("/rest/messages/message/{messageId}/comment/{commentId}/ack")
                .var("messageId", ctx.row(COMMENTS.get(row)[0])).var("commentId", ctx.row(row));
    }

    /** Reads the draft's comments as the identity and checks the comment's text and acknowledgement */
    private static void readComment(SweepContext ctx, String identity, int id, String text, String acknowledgedBy) {
        SweepResponse read = ctx.ok(identity, MessagesSweepFixture.comments(ctx.row(DRAFT)));
        JsonPath json = read.json();
        String at = "find { it.id == " + id + " }";
        ctx.check(json.get(at) != null, "the comments hold comment " + id, read);
        if (text != null) {
            ctx.checkEquals(text, json.getString(at + ".comment"), "the comment's text", read);
        }
        if (acknowledgedBy != null) {
            ctx.checkEquals(acknowledgedBy, json.getString(at + ".acknowledgedBy"), "who acknowledged the comment",
                    read);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Tags: PRIVATE and TEMP tags are their owner's, DOMAIN tags their domain's users', a locked tag an admin's,
    // a PUBLIC tag of a domain that domain's
    // ------------------------------------------------------------------------------------------- //

    private void tags(SweepCases cases) {
        cases.add("searchTags", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/tags/search").query("name", "sweep-messages-tag"))
                .sweep((ctx, as) -> {
                    SweepRequest search = SweepRequest.get("/rest/tags/search").query("name", "sweep-messages-tag");
                    SweepResponse anonymous = ctx.ok(as, search);
                    absent(ctx, anonymous, TAG_PRIVATE_USER, "an anonymous search leaves out a PRIVATE tag");
                    absent(ctx, anonymous, TAG_DOMAIN, "an anonymous search leaves out a DOMAIN tag");
                    SweepResponse user = ctx.ok("user", search);
                    ctx.expectRows(user, null, TAG_PRIVATE_USER, TAG_DOMAIN, TAG_PUBLIC_NW, TAG_PUBLIC_NM);
                    absent(ctx, user, TAG_PRIVATE_EDITOR, "the user's search leaves out the editor's PRIVATE tag");
                    SweepResponse other = ctx.ok("userOtherDomain", search);
                    absent(ctx, other, TAG_DOMAIN, "a search from another domain leaves out the DOMAIN tag");
                    // userOtherDomain holds no role in niord-nm, so the role hides the DOMAIN tag from it; the
                    // sysadmin holds the user role there, so only the tag's domain does
                    SweepResponse nm = ctx.ok("sysadmin", SweepRequest.get("/rest/tags/search")
                            .query("name", "sweep-messages-tag").domain(NM));
                    ctx.expectRows(nm, null, TAG_PUBLIC_NM);
                    absent(ctx, nm, TAG_DOMAIN, "a search by a user of niord-nm leaves out niord-nw's DOMAIN tag");
                })
                .variants((ctx, as) -> {
                    SweepResponse typed = ctx.ok("user", SweepRequest.get("/rest/tags/search")
                            .query("name", "sweep-messages-tag").query("type", "PRIVATE"));
                    ctx.expectRows(typed, null, TAG_PRIVATE_USER);
                    absent(ctx, typed, TAG_PUBLIC_NW, "type=PRIVATE leaves out a PUBLIC tag");
                    SweepResponse locked = ctx.ok(as, SweepRequest.get("/rest/tags/search")
                            .query("name", "sweep-messages-tag").query("locked", true));
                    ctx.expectRows(locked, null, TAG_LOCKED);
                    absent(ctx, locked, TAG_PUBLIC_NW, "locked=true leaves out an unlocked tag");
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.get("/rest/tags/search").query("limit", "abc")
                            .note("malformed limit"), Cell.status(400, "Failed to convert 'limit' with value: 'abc'"));
                    ctx.call(as.name(), SweepRequest.get("/rest/tags/search").query("type", "NOPE")
                            .note("unknown type"), Cell.status(400, "Failed to convert 'type' with value: 'NOPE'"));
                }));

        cases.add("findTagsByMessageId", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/tags/message/{messageUid}").var("messageUid", ctx.row(DRAFT)))
                .sweep((ctx, as) -> {
                    SweepRequest read = SweepRequest.get("/rest/tags/message/{messageUid}")
                            .var("messageUid", ctx.row(DRAFT));
                    SweepResponse anonymous = ctx.ok(as, read);
                    absent(ctx, anonymous, TAG_PRIVATE_USER, "anonymous does not see the user's PRIVATE tag");
                    absent(ctx, anonymous, TAG_DOMAIN, "anonymous does not see the DOMAIN tag");
                    ctx.expectRows(ctx.ok("user", read), null, TAG_PRIVATE_USER, TAG_DOMAIN);
                    SweepResponse editor = ctx.ok("editor", read);
                    ctx.expectRows(editor, null, TAG_DOMAIN);
                    absent(ctx, editor, TAG_PRIVATE_USER, "the editor does not see the user's PRIVATE tag");
                    SweepResponse nm = ctx.ok("sysadmin", SweepRequest.get("/rest/tags/message/{messageUid}")
                            .var("messageUid", ctx.row(DRAFT)).domain(NM));
                    absent(ctx, nm, TAG_DOMAIN, "a user of niord-nm does not see niord-nw's DOMAIN tag");
                }));

        cases.add("getTags", SweepCase
                .of((ctx, row) -> allTags(ctx))
                .sweep((ctx, as) -> {
                    SweepResponse anonymous = ctx.ok(as, allTags(ctx));
                    ctx.expectRows(anonymous, null, TAG_PUBLIC_NW, TAG_PUBLIC_NM, TAG_LOCKED);
                    absent(ctx, anonymous, TAG_PRIVATE_USER, "anonymous does not get a PRIVATE tag by id");
                    absent(ctx, anonymous, TAG_DOMAIN, "anonymous does not get a DOMAIN tag by id");
                    SweepResponse user = ctx.ok("user", allTags(ctx));
                    ctx.expectRows(user, null, TAG_PRIVATE_USER, TAG_DOMAIN);
                    absent(ctx, user, TAG_PRIVATE_EDITOR, "the user does not get the editor's PRIVATE tag by id");
                    SweepResponse nm = ctx.ok("sysadmin", allTags(ctx).domain(NM));
                    ctx.expectRows(nm, null, TAG_PUBLIC_NM);
                    absent(ctx, nm, TAG_DOMAIN, "a user of niord-nm does not get niord-nw's DOMAIN tag by id");
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get("/rest/tags/tag/{tagIds}")
                            .var("tagIds", "sweep-no-such-tag").note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown tag", unknown);
                }));

        cases.add("createTag", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/tags/tag")
                        .json(Map.of("type", "PRIVATE", "name", ctx.name("tag-refused"))))
                .sweep((ctx, as) -> createTagRoundTrip(ctx, as, "/rest/tags/tag"))
                .variants((ctx, as) -> createTagRoundTrip(ctx, as, "/rest/tags/tag/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/tags/tag").json(Map.of()).note("empty body"),
                            Cell.status(400, "A tag must have a type"));
                    ctx.call(as.name(), SweepRequest.post("/rest/tags/tag").json(Map.of("type", "PRIVATE"))
                            .note("no name"), Cell.status(400, "A tag must have a name"));
                }));

        cases.add("updateTag", SweepCase
                .of((ctx, row) -> putTag(ctx, row == null ? TAG_PRIVATE_USER : row))
                .sweep(MessagesSweepGroup::updateOwnTag)
                .allowed((ctx, as, row) -> {
                    if (row == null) {
                        // A tier cell of the full run: the identity changes a tag of its own
                        updateOwnTag(ctx, as);
                        return;
                    }
                    // An admin changes the locked tag: sent as it is, so nothing changes
                    ctx.ok(as, putTag(ctx, row));
                    MessagesSweepFixture.TagRow tag = tagRow(row);
                    readTag(ctx, as.name(), (String) ctx.row(row), tag.type(), tag.name(), tag.locked(), null);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/tags/tag/{tagId}")
                        .var("tagId", ctx.row(TAG_PRIVATE_USER))
                        .json(tagBody("sweep-another-tag", "PRIVATE", TAG_PRIVATE_USER, false))
                        .note("the body's id is not the path's"), Cell.status(400, null))));

        cases.add("deleteTag", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/tags/tag/{tagId}")
                        .var("tagId", ctx.row(row == null ? TAG_PRIVATE_USER : row)))
                .sweep(MessagesSweepGroup::deleteOwnTag)
                .allowed((ctx, as, row) -> {
                    if (row == null) {
                        deleteOwnTag(ctx, as);
                        return;
                    }
                    // Another's PRIVATE tag is not found for the caller: the delete answers false and removes nothing
                    SweepResponse answer = ctx.ok(as, SweepRequest.delete("/rest/tags/tag/{tagId}")
                            .var("tagId", ctx.row(row)));
                    ctx.checkEquals("false", answer.text().trim(), "the answer to deleting another's tag", answer);
                    MessagesSweepFixture.TagRow tag = tagRow(row);
                    readTag(ctx, tag.owner(), (String) ctx.row(row), tag.type(), tag.name(), tag.locked(), null);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.delete("/rest/tags/tag/{tagId}")
                            .var("tagId", "sweep-no-such-tag").note("unknown-id probe"));
                    ctx.checkEquals("false", unknown.text().trim(), "the answer for an unknown tag", unknown);
                }));

        cases.add("unlockTag", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/tags/unlock-tag/{tagId}")
                        .var("tagId", ctx.row(row == null ? TAG_LOCKED : row)))
                .sweep((ctx, as) -> {
                    String name = ctx.name("tag-unlock-" + ctx.unique());
                    String tagId = createTag(ctx, as.name(), NW, "PUBLIC", name);
                    try {
                        ctx.ok(as, SweepRequest.put("/rest/tags/tag/{tagId}").var("tagId", tagId)
                                .json(tagBody(tagId, "PUBLIC", name, true)));
                        readTag(ctx, as.name(), tagId, "PUBLIC", name, true, null);
                        SweepResponse unlocked = ctx.ok(as, SweepRequest.put("/rest/tags/unlock-tag/{tagId}")
                                .var("tagId", tagId));
                        ctx.checkEquals(false, unlocked.json().getBoolean("locked"), "locked in the answer", unlocked);
                        readTag(ctx, as.name(), tagId, "PUBLIC", name, false, null);
                    } finally {
                        removeTagAndReadGone(ctx, as.name(), NW, tagId);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/tags/unlock-tag/{tagId}")
                        .var("tagId", "sweep-no-such-tag").note("unknown-id probe"), Cell.status(404, "No such tag"))));

        cases.add("createTempTag", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/tags/temp-tag")
                        .json(List.of(ctx.row(row == null ? DRAFT : row))))
                .sweep((ctx, as) -> tempTagRoundTrip(ctx, as, "/rest/tags/temp-tag"))
                .variants((ctx, as) -> tempTagRoundTrip(ctx, as, "/rest/tags/temp-tag/")));

        cases.add("clearTag", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/tags/tag/{tagId}/messages")
                        .var("tagId", ctx.row(row == null ? TAG_PRIVATE_USER : row)))
                .sweep(MessagesSweepGroup::clearOwnTag)
                .allowed((ctx, as, row) -> {
                    if (row == null) {
                        clearOwnTag(ctx, as);
                        return;
                    }
                    // Another's PRIVATE tag is not found for the caller: false, and its messages stay
                    SweepResponse answer = ctx.ok(as, SweepRequest.delete("/rest/tags/tag/{tagId}/messages")
                            .var("tagId", ctx.row(row)));
                    ctx.checkEquals("false", answer.text().trim(), "the answer to clearing another's tag", answer);
                }));

        cases.add("addMessageToTag", SweepCase
                .of((ctx, row) -> tagMessages(ctx, "add-messages", row))
                .sweep((ctx, as) -> {
                    String tagId = createTag(ctx, as.name(), NW, "PRIVATE", ctx.name("tag-add-" + ctx.unique()));
                    try {
                        SweepResponse added = ctx.ok(as, addMessages(tagId, ctx.row(DRAFT), ctx.row(PUBLISHED)));
                        ctx.checkEquals(2, added.json().getInt("messageCount"), "the message count in the answer",
                                added);
                        readTag(ctx, as.name(), tagId, "PRIVATE", null, false, 2);
                        SweepResponse byMessage = ctx.ok(as, SweepRequest.get("/rest/tags/message/{messageUid}")
                                .var("messageUid", ctx.row(PUBLISHED)));
                        ctx.check(byMessage.json().getList("tagId").contains(tagId),
                                "the published message's tags hold the tag it was added to", byMessage);
                    } finally {
                        removeTagAndReadGone(ctx, as.name(), NW, tagId);
                    }
                }));

        cases.add("removeMessageFromTag", SweepCase
                .of((ctx, row) -> tagMessages(ctx, "remove-messages", row))
                .sweep((ctx, as) -> {
                    String tagId = createTag(ctx, as.name(), NW, "PRIVATE", ctx.name("tag-remove-" + ctx.unique()));
                    try {
                        ctx.ok(as, addMessages(tagId, ctx.row(DRAFT), ctx.row(PUBLISHED)));
                        SweepResponse removed = ctx.ok(as, SweepRequest.put("/rest/tags/tag/{tagId}/remove-messages")
                                .var("tagId", tagId).json(List.of(ctx.row(PUBLISHED))));
                        ctx.checkEquals(1, removed.json().getInt("messageCount"), "the message count in the answer",
                                removed);
                        readTag(ctx, as.name(), tagId, "PRIVATE", null, false, 1);
                        SweepResponse byMessage = ctx.ok(as, SweepRequest.get("/rest/tags/message/{messageUid}")
                                .var("messageUid", ctx.row(PUBLISHED)));
                        ctx.check(!byMessage.json().getList("tagId").contains(tagId),
                                "the published message's tags no longer hold the tag", byMessage);
                    } finally {
                        removeTagAndReadGone(ctx, as.name(), NW, tagId);
                    }
                }));
    }

    /** Makes a PRIVATE tag as the identity, renames and locks it, unlocks it, reads it back each time, removes it */
    private static void updateOwnTag(SweepContext ctx, SweepIdentity as) {
        String tagId = createTag(ctx, as.name(), NW, "PRIVATE", ctx.name("tag-update-" + ctx.unique()));
        try {
            String renamed = ctx.name("tag-updated-" + ctx.unique());
            ctx.ok(as, SweepRequest.put("/rest/tags/tag/{tagId}").var("tagId", tagId)
                    .json(tagBody(tagId, "PRIVATE", renamed, true)));
            readTag(ctx, as.name(), tagId, "PRIVATE", renamed, true, null);
            // The owner of a PRIVATE tag unlocks it again
            ctx.ok(as, SweepRequest.put("/rest/tags/tag/{tagId}").var("tagId", tagId)
                    .json(tagBody(tagId, "PRIVATE", renamed, false)));
            readTag(ctx, as.name(), tagId, "PRIVATE", renamed, false, null);
        } finally {
            removeTagAndReadGone(ctx, as.name(), NW, tagId);
        }
    }

    private static void deleteOwnTag(SweepContext ctx, SweepIdentity as) {
        removeTagAndReadGone(ctx, as.name(), NW,
                createTag(ctx, as.name(), NW, "PRIVATE", ctx.name("tag-delete-" + ctx.unique())));
    }

    /** Makes a PRIVATE tag with two messages as the identity, clears it, reads it back empty, removes it */
    private static void clearOwnTag(SweepContext ctx, SweepIdentity as) {
        String tagId = createTag(ctx, as.name(), NW, "PRIVATE", ctx.name("tag-clear-" + ctx.unique()));
        try {
            ctx.ok(as, addMessages(tagId, ctx.row(DRAFT), ctx.row(PUBLISHED)));
            SweepResponse cleared = ctx.ok(as, SweepRequest.delete("/rest/tags/tag/{tagId}/messages")
                    .var("tagId", tagId));
            ctx.checkEquals("true", cleared.text().trim(), "the answer to clearing the tag", cleared);
            readTag(ctx, as.name(), tagId, "PRIVATE", null, false, 0);
        } finally {
            removeTagAndReadGone(ctx, as.name(), NW, tagId);
        }
    }

    private static SweepRequest allTags(SweepContext ctx) {
        List<String> ids = new ArrayList<>();
        TAGS.forEach(t -> ids.add((String) ctx.row(t.name())));
        return SweepRequest.get("/rest/tags/tag/{tagIds}").var("tagIds", String.join(",", ids));
    }

    private static MessagesSweepFixture.TagRow tagRow(String name) {
        return TAGS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Object> tagBody(String tagId, String type, String name, boolean locked) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tagId", tagId);
        body.put("type", type);
        body.put("name", name);
        body.put("locked", locked);
        return body;
    }

    /** The fixture tag as it is */
    private static SweepRequest putTag(SweepContext ctx, String row) {
        MessagesSweepFixture.TagRow tag = tagRow(row);
        String tagId = (String) ctx.row(row);
        return SweepRequest.put("/rest/tags/tag/{tagId}").var("tagId", tagId)
                .json(tagBody(tagId, tag.type(), tag.name(), tag.locked()));
    }

    /**
     * A message list change on a tag. On a tag row: that tag and the published message (which the fixture's public
     * tags already hold, so a change that passed a gate would change nothing on them). On a message row: the user's
     * PRIVATE tag and that message.
     */
    private static SweepRequest tagMessages(SweepContext ctx, String action, String row) {
        String tag = TAG_PRIVATE_USER;
        Object message = ctx.row(DRAFT);
        if (row != null && row.startsWith("sweep-messages-tag-")) {
            tag = row;
            message = ctx.row(PUBLISHED);
        } else if (row != null) {
            message = ctx.row(row);
        }
        return SweepRequest.put("/rest/tags/tag/{tagId}/" + action).var("tagId", ctx.row(tag))
                .json(List.of(message));
    }

    private static SweepRequest addMessages(String tagId, Object... uids) {
        return SweepRequest.put("/rest/tags/tag/{tagId}/add-messages").var("tagId", tagId).json(List.of(uids));
    }

    /** Creates a tag as the identity in the domain; answers its id */
    private static String createTag(SweepContext ctx, String identity, String domain, String type, String name) {
        SweepResponse created = ctx.ok(identity, SweepRequest.post("/rest/tags/tag")
                .json(Map.of("type", type, "name", name)).domain(domain));
        return created.json().getString("tagId");
    }

    private static void createTagRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("tag-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(Map.of("type", "PRIVATE", "name", name)));
        String tagId = created.json().getString("tagId");
        try {
            readTag(ctx, as.name(), tagId, "PRIVATE", name, false, 0);
        } finally {
            removeTagAndReadGone(ctx, as.name(), NW, tagId);
        }
    }

    private static void tempTagRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).query("ttl", 5)
                .json(List.of(ctx.row(DRAFT))));
        String tagId = created.json().getString("tagId");
        try {
            ctx.checkEquals("TEMP", created.json().getString("type"), "the type of the temp tag", created);
            readTag(ctx, as.name(), tagId, "TEMP", null, false, 1);
            SweepResponse anonymous = ctx.ok("anonymous", SweepRequest.get("/rest/tags/tag/{tagIds}")
                    .var("tagIds", tagId));
            ctx.checkEquals(List.of(), anonymous.json().getList("$"), "a TEMP tag read by anonymous", anonymous);
        } finally {
            removeTagAndReadGone(ctx, as.name(), NW, tagId);
        }
    }

    /** Reads the tag back as the identity and checks the fields given (a null name or count is not checked) */
    private static void readTag(SweepContext ctx, String identity, String tagId, String type, String name,
                                boolean locked, Integer messageCount) {
        SweepResponse read = ctx.ok(identity, SweepRequest.get("/rest/tags/tag/{tagIds}").var("tagIds", tagId));
        JsonPath json = read.json();
        if (!ctx.checkEquals(1, json.getList("$").size(), "the number of tags read back by id", read)) {
            return;
        }
        ctx.checkEquals(type, json.getString("[0].type"), "the tag's type", read);
        if (name != null) {
            ctx.checkEquals(name, json.getString("[0].name"), "the tag's name", read);
        }
        ctx.checkEquals(locked, json.getBoolean("[0].locked"), "the tag's lock", read);
        if (messageCount != null) {
            ctx.checkEquals(messageCount, json.getInt("[0].messageCount"), "the tag's message count", read);
        }
    }

    private static void removeTagAndReadGone(SweepContext ctx, String identity, String domain, String tagId) {
        SweepResponse deleted = ctx.ok(identity, SweepRequest.delete("/rest/tags/tag/{tagId}").var("tagId", tagId)
                .domain(domain));
        ctx.checkEquals("true", deleted.text().trim(), "the answer to deleting the tag", deleted);
        SweepResponse read = ctx.ok(identity, SweepRequest.get("/rest/tags/tag/{tagIds}").var("tagIds", tagId)
                .domain(domain).note("read after the delete"));
        ctx.checkEquals(List.of(), read.json().getList("$"), "the tag read after its delete", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Message reads: a draft is its domain's users' (or a ticket's that carries the user role)
    // ------------------------------------------------------------------------------------------- //

    private void messageReads(SweepCases cases) {
        cases.add("getMessage", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/message/{messageId}")
                        .var("messageId", ctx.row(row == null ? PUBLISHED : row)))
                .sweep((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get("/rest/messages/message/{messageId}")
                            .var("messageId", ctx.row(PUBLISHED)));
                    // A draft in a PUBLIC tag is shared with the users of every domain, and with no one without
                    // the user role: the niord-nm draft, put into niord-nm's PUBLIC tag for the purpose
                    SweepRequest readNm = SweepRequest.get("/rest/messages/message/{messageId}")
                            .var("messageId", ctx.row(DRAFT_NM));
                    ctx.ok("sysadmin", SweepRequest.put("/rest/tags/tag/{tagId}/add-messages")
                            .var("tagId", ctx.row(TAG_PUBLIC_NM)).json(List.of(ctx.row(DRAFT_NM))).domain(NM));
                    try {
                        ctx.expectRows(ctx.ok("user", readNm), null, DRAFT_NM);
                        ctx.call(as.name(), readNm.note("a draft in a PUBLIC tag, anonymous"), Cell.status(403, null));
                        ctx.call("ticket(user)", SweepRequest.get("/rest/messages/message/{messageId}")
                                        .var("messageId", ctx.row(DRAFT_NM)).note("a draft in a PUBLIC tag, role-less ticket"),
                                Cell.status(403, null));
                    } finally {
                        ctx.ok("sysadmin", SweepRequest.put("/rest/tags/tag/{tagId}/remove-messages")
                                .var("tagId", ctx.row(TAG_PUBLIC_NM)).json(List.of(ctx.row(DRAFT_NM))).domain(NM));
                    }
                    ctx.call("user", SweepRequest.get("/rest/messages/message/{messageId}")
                                    .var("messageId", ctx.row(DRAFT_NM)).note("the draft out of the PUBLIC tag again"),
                            Cell.status(403, null));
                })
                .variants((ctx, as) -> {
                    SweepResponse danish = ctx.ok(as, SweepRequest.get("/rest/messages/message/{messageId}")
                            .var("messageId", ctx.row(PUBLISHED)).query("lang", "da"));
                    ctx.expectRows(danish, null, PUBLISHED + "-da");
                    ctx.check(!danish.text().contains("\"lang\":\"en\""), "lang=da leaves out the English texts",
                            danish);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/messages/message/{messageId}")
                        .var("messageId", "sweep-no-such-message").note("unknown-id probe"), Cell.status(204, null))));

        cases.add("getSystemMessage", SweepCase
                .of((ctx, row) -> editable(ctx.row(row == null ? DRAFT : row)))
                .sweep((ctx, as) -> {
                    SweepResponse read = ctx.ok(as, editable(ctx.row(DRAFT)));
                    readBackDraft(ctx, read);
                })
                .probes((ctx, as) -> ctx.call(as.name(), editable("sweep-no-such-message").note("unknown-id probe"),
                        Cell.status(204, null))));

        cases.add("newTemplateMessage", SweepCase
                .of((ctx, row) -> {
                    SweepRequest template = SweepRequest.get("/rest/messages/new-message-template")
                            .query("mainType", "NW");
                    // On the other domain's draft: the call is made in that domain, which has no NW series
                    return DRAFT_NM.equals(row) ? template.domain(NM) : template;
                })
                .sweep((ctx, as) -> {
                    SweepResponse template = ctx.ok(as, SweepRequest.get("/rest/messages/new-message-template")
                            .query("mainType", "NW").query("populate", true));
                    ctx.checkEquals("NW", template.json().getString("mainType"), "the template's main type", template);
                    ctx.check(template.json().getString("editRepoPath") != null, "the template has an edit folder",
                            template);
                    ctx.check(template.json().getList("descs.lang").contains("en"),
                            "populate=true gives the template an English description", template);
                    // A main type the domain has no series of is refused
                    ctx.call(as.name(), SweepRequest.get("/rest/messages/new-message-template")
                            .query("mainType", "NM"), Cell.status(403, null));
                    // A call without a domain header is refused, as its neighbours refuse it (WEB13)
                    ctx.call(as.name(), SweepRequest.get("/rest/messages/new-message-template")
                            .query("mainType", "NW").domain(null).note("no NiordDomain header"), Cell.status(403, null));
                }));

        cases.add("copyMessageTemplate", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/copy-message-template/{messageId}")
                        .var("messageId", ctx.row(row == null ? DRAFT : row)))
                .sweep((ctx, as) -> {
                    SweepResponse copy = ctx.ok(as, SweepRequest.get("/rest/messages/copy-message-template/{messageId}")
                            .var("messageId", ctx.row(DRAFT)).query("referenceType", "REFERENCE"));
                    JsonPath json = copy.json();
                    ctx.check(!ctx.row(DRAFT).equals(json.getString("id")), "the copy has an id of its own", copy);
                    ctx.checkEquals("DRAFT", json.getString("status"), "the copy's status", copy);
                    ctx.checkEquals(DRAFT, json.getString("descs.find { it.lang == 'en' }.title"),
                            "the copy's English title", copy);
                    ctx.check(json.getList("references.messageId").contains(ctx.row(DRAFT)),
                            "referenceType=REFERENCE adds a reference to the original", copy);
                }));

        cases.add("getReferencedMessages", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/referenced-messages/{messageId}")
                        .var("messageId", ctx.row(row == null ? DRAFT : row)))
                .variants((ctx, as) -> {
                    SweepResponse typed = ctx.ok(as, SweepRequest.get("/rest/messages/referenced-messages/{messageId}")
                            .var("messageId", ctx.row(DRAFT)).query("referenceType", "UPDATE")
                            .query("status", "PUBLISHED"));
                    ctx.expectRows(typed, null, PUBLISHED);
                    SweepResponse none = ctx.ok(as, SweepRequest.get("/rest/messages/referenced-messages/{messageId}")
                            .var("messageId", ctx.row(DRAFT)).query("status", "EXPIRED"));
                    ctx.checkEquals(List.of(), none.json().getList("$"), "the referenced messages with status=EXPIRED",
                            none);
                }));

        cases.add("getMessageHistory", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/message/{messageId}/history")
                        .var("messageId", ctx.row(row == null ? DRAFT : row)))
                .sweep((ctx, as) -> {
                    SweepResponse history = ctx.ok(as, SweepRequest.get("/rest/messages/message/{messageId}/history")
                            .var("messageId", ctx.row(DRAFT)));
                    ctx.check(!history.json().getList("$").isEmpty(), "the draft has a history", history);
                    ctx.check(history.json().getList("status").contains("DRAFT"),
                            "the history holds the draft's DRAFT version", history);
                    SweepResponse published = ctx.ok(as, SweepRequest.get("/rest/messages/message/{messageId}/history")
                            .var("messageId", ctx.row(PUBLISHED)));
                    ctx.check(published.json().getList("status").contains("PUBLISHED"),
                            "the published message's history holds its PUBLISHED version", published);
                }));

        cases.add("getRecentlyEditedMessages", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/recently-edited-drafts"))
                .as("editor")
                .sweep((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get("/rest/messages/recently-edited-drafts").query("lang", "en"));
                    SweepResponse user = ctx.ok("user", SweepRequest.get("/rest/messages/recently-edited-drafts"));
                    absent(ctx, user, DRAFT, "the user, who edited nothing, does not get the editor's draft");
                    // The domain narrowing: the sysadmin made the niord-nm draft, and gets it in niord-nm only. (The
                    // sysadmin edits no niord-nw draft that stays a draft: MessageRestServiceITTest counts its
                    // recently edited drafts in niord-nw.)
                    SweepRequest recent = SweepRequest.get("/rest/messages/recently-edited-drafts");
                    ctx.expectRows(ctx.ok("sysadmin", recent.domain(NM)), null, DRAFT_NM);
                    SweepResponse nw = ctx.ok("sysadmin", SweepRequest.get("/rest/messages/recently-edited-drafts")
                            .domain(NW));
                    absent(ctx, nw, DRAFT_NM, "the sysadmin's recently edited drafts in niord-nw leave out its "
                            + "niord-nm draft");
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/messages/recently-edited-drafts")
                        .query("maxMessageNo", "abc").note("malformed maxMessageNo"),
                        Cell.status(400, "Failed to convert 'maxMessageNo' with value: 'abc'"))));
    }

    /** Checks the editable form of the fixture draft holds every list the fixture made it with, in order */
    private void readBackDraft(SweepContext ctx, SweepResponse read) {
        JsonPath json = read.json();
        ctx.checkEquals(DRAFT, json.getString("descs.find { it.lang == 'en' }.title"), "the English title", read);
        ctx.checkEquals(DRAFT + "-da", json.getString("descs.find { it.lang == 'da' }.title"), "the Danish title",
                read);
        ctx.checkEquals(fixture.areas, json.getList("areas.id"), "the areas in order", read);
        ctx.checkEquals(fixture.charts, json.getList("charts.chartNumber"), "the charts in order", read);
        ctx.checkEquals(new HashSet<>(fixture.categories), new HashSet<>(json.getList("categories.id")),
                "the categories", read);
        ctx.checkEquals(List.of(DRAFT + " part 1", DRAFT + " part 2"),
                json.getList("parts.collect { p -> p.descs.find { it.lang == 'en' }.subject }"), "the parts in order",
                read);
        ctx.checkEquals(List.of(ATTACHMENT_TEXT + ".txt", ATTACHMENT_IMAGE + ".png"), json.getList("attachments.fileName"),
                "the attachments in order", read);
        ctx.checkEquals(List.of("REFERENCE", "UPDATE"), json.getList("references.type"), "the references in order",
                read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Message writes: the message's own domain, an editor (an admin for a message that is no longer a draft)
    // ------------------------------------------------------------------------------------------- //

    private void messageWrites(SweepCases cases) {
        cases.add("createMessage", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/message").json(refusedBody(ctx, row)))
                .sweep((ctx, as) -> createMessageRoundTrip(ctx, as))
                .allowed((ctx, as, row) -> createMessageRoundTrip(ctx, as))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/message").json(Map.of()).note("empty body"),
                            Cell.status(400, "A message must name its message series"));
                    Map<String, Object> outside = refusedBody(ctx, null);
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/message").json(outside)
                            .note("an attachment outside the message folder"), Cell.status(400, "Invalid attachment path"));
                }));

        cases.add("updateMessage", SweepCase
                .of((ctx, row) -> putMessage(ctx, row == null ? DRAFT : row))
                .sweep(this::updateOwnMessage)
                .allowed((ctx, as, row) -> {
                    if (row == null) {
                        // A tier cell of the full run: the identity updates a draft of its own
                        updateOwnMessage(ctx, as);
                        return;
                    }
                    // The admin saves the published message as it is
                    ctx.ok(as, putMessage(ctx, row));
                    SweepResponse read = ctx.ok(as, editable(ctx.row(row)));
                    ctx.checkEquals(row, read.json().getString("descs.find { it.lang == 'en' }.title"),
                            "the title after an unchanged save", read);
                    ctx.checkEquals("PUBLISHED", read.json().getString("status"), "the status after an unchanged save",
                            read);
                })
                .probes((ctx, as) -> {
                    Map<String, Object> body = ctx.ok("admin", editable(ctx.row(DRAFT))).json().getMap("$");
                    ctx.call(as.name(), SweepRequest.put("/rest/messages/message/{messageId}")
                                    .var("messageId", "sweep-another-id").json(body).note("the body's id is not the path's"),
                            Cell.status(400, null));
                }));

        cases.add("updateMessageStatus", SweepCase
                .of((ctx, row) -> status(ctx.row(row == null ? DRAFT : row), "DRAFT"))
                .sweep((ctx, as) -> {
                    String uid = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW,
                            ctx.name("status-" + ctx.unique()), false, List.of());
                    ctx.settle(null);
                    String end = "DELETED";
                    try {
                        for (String next : List.of("VERIFIED", "DRAFT", "VERIFIED", "PUBLISHED")) {
                            SweepResponse changed = ctx.ok(as, status(uid, next));
                            ctx.checkEquals(next, changed.json().getString("status"), "the status in the answer",
                                    changed);
                            readStatus(ctx, as.name(), uid, next);
                            if (next.equals("PUBLISHED")) {
                                end = "CANCELLED";
                                ctx.check(changed.json().get("publishDateFrom") != null,
                                        "a published message has a publish date", changed);
                            }
                        }
                    } finally {
                        endMessage(ctx, as.name(), uid, end);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), status(ctx.row(DRAFT), "PUBLISHED").note("a transition the rules refuse"),
                            Cell.status(409, "PUBLISHED"));
                    ctx.call(as.name(), status(ctx.row(DRAFT), "NOPE").note("an unknown status"),
                            Cell.status(400, "NOPE"));
                }));

        cases.add("updateMessageStatuses", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/messages/update-statuses").json(List.of(Map.of(
                        "messageId", ctx.row(row == null ? DRAFT : row), "status", "DRAFT"))))
                .sweep((ctx, as) -> {
                    String a = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW,
                            ctx.name("statuses-a-" + ctx.unique()), false, List.of());
                    String b = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW,
                            ctx.name("statuses-b-" + ctx.unique()), false, List.of());
                    ctx.settle(null);
                    try {
                        SweepResponse verified = ctx.ok(as, statuses(a, "VERIFIED", b, "VERIFIED"));
                        ctx.checkEquals(List.of(a, b), verified.json().getList("id"), "the messages in the answer, in "
                                + "the order sent", verified);
                        readStatus(ctx, as.name(), a, "VERIFIED");
                        readStatus(ctx, as.name(), b, "VERIFIED");
                        ctx.ok(as, statuses(a, "DELETED", b, "DELETED"));
                        readStatus(ctx, as.name(), a, "DELETED");
                        readStatus(ctx, as.name(), b, "DELETED");
                    } finally {
                        endMessage(ctx, as.name(), a, "DELETED");
                        endMessage(ctx, as.name(), b, "DELETED");
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/messages/update-statuses")
                        .json(List.of(Map.of("messageId", ctx.row(DRAFT)))).note("an item without a status"),
                        Cell.status(400, "No status to change message"))));

        cases.add("changeAreaSortOrder", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/messages/change-area-sort-order")
                        .json(Map.of("id", ctx.row(row == null ? DRAFT : row), "afterId", ctx.row(PUBLISHED))))
                .sweep((ctx, as) -> {
                    String a = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW,
                            ctx.name("sort-a-" + ctx.unique()), false, List.of());
                    String b = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW,
                            ctx.name("sort-b-" + ctx.unique()), false, List.of());
                    ctx.settle(null);
                    try {
                        ctx.ok(as, SweepRequest.put("/rest/messages/change-area-sort-order")
                                .json(Map.of("id", a, "afterId", b)));
                        ctx.checkEquals(areaSortOrder(ctx, b) + 1, areaSortOrder(ctx, a),
                                "the area sort order of a message moved after another", null);
                        ctx.ok(as, SweepRequest.put("/rest/messages/change-area-sort-order")
                                .json(Map.of("id", a, "beforeId", b)));
                        ctx.checkEquals(areaSortOrder(ctx, b) - 1, areaSortOrder(ctx, a),
                                "the area sort order of a message moved before another", null);
                    } finally {
                        endMessage(ctx, as.name(), a, "DELETED");
                        endMessage(ctx, as.name(), b, "DELETED");
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/messages/change-area-sort-order")
                        .json(Map.of("id", ctx.row(DRAFT))).note("no neighbour"), Cell.status(400, null))));

        cases.add("reindexPublishedMessageAreaSorting", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/messages/recompute-area-sort-order"))
                .sweep((ctx, as) -> {
                    // Idempotent: it computes again what each published message of the domain already holds. The
                    // published fixture message's stored order is spoiled first (no endpoint writes or shows it),
                    // and the recompute must give the computed one back.
                    String uid = (String) ctx.row(PUBLISHED);
                    double computed = areaSortOrder(ctx, uid);
                    JdbcTemplate jdbc = ctx.bean(JdbcTemplate.class);
                    jdbc.update("UPDATE public.\"Message\" SET \"areaSortOrder\" = ? WHERE uid = ?", computed + 1000.5,
                            uid);
                    try {
                        ctx.ok(as, SweepRequest.put("/rest/messages/recompute-area-sort-order"));
                        // Nearly: the recompute's value differs from the create's in the seventh decimal (measured:
                        // -298.0405961689513 stored, -298.04059684641567 recomputed), the spoiled one by 1000.5
                        double recomputed = areaSortOrder(ctx, uid);
                        ctx.check(Math.abs(recomputed - computed) < 0.001, "the published message's area sort order "
                                + "after the recompute is " + computed + " (read back: " + recomputed + ")", null);
                    } finally {
                        jdbc.update("UPDATE public.\"Message\" SET \"areaSortOrder\" = ? WHERE uid = ?", computed, uid);
                    }
                })
                .probes((ctx, as) -> ctx.call("sysadmin", SweepRequest.put("/rest/messages/recompute-area-sort-order")
                        .domain(null).note("no NiordDomain header"), Cell.status(403, null))));

        cases.add("uploadMessageAttachments", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/attachments/{*folder}")
                        .var("folder", row == null ? "temp/sweep-messages-refused" : ctx.row(row))
                        .file("file", ctx.name("refused") + ".txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .sweep((ctx, as) -> {
                    String folder = newTempDir(ctx, as.name());
                    SweepResponse uploaded = ctx.ok(as, SweepRequest.post("/rest/messages/attachments/{*folder}")
                            .var("folder", folder)
                            .file("file", "sweep-messages-a.txt", "sweep-messages-a".getBytes(StandardCharsets.UTF_8),
                                    "text/plain")
                            .file("file", "sweep-messages-b.png", MessagesSweepFixture.png(), "image/png"));
                    JsonPath json = uploaded.json();
                    ctx.checkEquals(List.of("sweep-messages-a.txt", "sweep-messages-b.png"),
                            json.getList("fileName"), "the uploaded attachments, in order", uploaded);
                    ctx.checkEquals(List.of(folder + "/sweep-messages-a.txt", folder + "/sweep-messages-b.png"),
                            json.getList("path"), "the attachments' paths", uploaded);
                    ctx.checkEquals(List.of("text/plain", "image/png"), json.getList("type"), "the attachments' types",
                            uploaded);
                    List<String> paths = json.getList("path");
                    for (String path : paths) {
                        try {
                            SweepResponse file = ctx.ok("anonymous", repoFile(path));
                            ctx.check(file.body().length > 0, "the uploaded file " + path + " reads back", file);
                        } finally {
                            deleteRepoFileAndReadGone(ctx, as.name(), path);
                        }
                    }
                }));
    }

    /** The update sweep: a draft of its own, changed (titles, area order, a chart less, a new reference), read
     * back field by field, then DELETED */
    @SuppressWarnings("unchecked")
    private void updateOwnMessage(SweepContext ctx, SweepIdentity as) throws IOException {
        String created = ctx.name("update-" + ctx.unique());
        String uid = fixture.createMessage(ctx, as.name(), NW, "NW", SERIES_NW, created, false, List.of());
        ctx.settle(null);
        try {
            Map<String, Object> edit = ctx.ok(as, editable(uid)).json().getMap("$");
            String title = ctx.name("updated-" + ctx.unique());
            edit.put("descs", List.of(MessagesSweepFixture.desc("en", title),
                    MessagesSweepFixture.desc("da", title + "-da")));
            List<Object> areas = new ArrayList<>(asList(edit.get("areas")));
            java.util.Collections.reverse(areas);
            edit.put("areas", areas);
            edit.put("charts", asList(edit.get("charts")).subList(0, 1));
            // One category less, and the first part's English subject changed
            edit.put("categories", asList(edit.get("categories")).subList(0, 1));
            Map<String, Object> firstPart = (Map<String, Object>) asList(edit.get("parts")).getFirst();
            for (Object desc : asList(firstPart.get("descs"))) {
                if ("en".equals(((Map<String, Object>) desc).get("lang"))) {
                    ((Map<String, Object>) desc).put("subject", title + " part 1");
                }
            }
            edit.put("references", List.of(Map.of("messageId", ctx.row(PUBLISHED), "type", "REPETITION")));
            ctx.ok(as, SweepRequest.put("/rest/messages/message/{messageId}").var("messageId", uid)
                    .json(edit));
            SweepResponse readAnswer = ctx.ok(as, editable(uid));
            JsonPath read = readAnswer.json();
            ctx.checkEquals(title, read.getString("descs.find { it.lang == 'en' }.title"),
                    "the updated English title", readAnswer);
            ctx.checkEquals(title + "-da", read.getString("descs.find { it.lang == 'da' }.title"),
                    "the updated Danish title", readAnswer);
            List<Integer> reversed = new ArrayList<>(fixture.areas);
            java.util.Collections.reverse(reversed);
            ctx.checkEquals(reversed, read.getList("areas.id"), "the areas in their new order",
                    readAnswer);
            ctx.checkEquals(fixture.charts.subList(0, 1), read.getList("charts.chartNumber"),
                    "the one chart left", readAnswer);
            ctx.checkEquals(List.of(((Map<String, Object>) asList(edit.get("categories")).getFirst()).get("id")),
                    read.getList("categories.id"), "the one category left", readAnswer);
            ctx.checkEquals(List.of(title + " part 1", created + " part 2"),
                    read.getList("parts.collect { p -> p.descs.find { it.lang == 'en' }.subject }"),
                    "the parts' English subjects after the update", readAnswer);
            ctx.checkEquals(List.of("REPETITION"), read.getList("references.type"), "the new reference",
                    readAnswer);
        } finally {
            endMessage(ctx, as.name(), uid, "DELETED");
        }
    }

    /** A create body that fails after the gate (an attachment outside the message's folder), on the row's domain */
    private Map<String, Object> refusedBody(SweepContext ctx, String row) {
        boolean nm = DRAFT_NM.equals(row);
        String uid = UUID.randomUUID().toString();
        Map<String, Object> body = fixture.body(uid, "messages/sweep/" + uid, null, nm ? "NM" : "NW",
                nm ? SERIES_NM : SERIES_NW, ctx.name("refused"), List.of(),
                List.of(Map.of("fileName", "x.txt", "path", "elsewhere/x.txt")));
        return body;
    }

    /** The row's message as it is, as its editable form gives it in its own domain */
    private static SweepRequest putMessage(SweepContext ctx, String row) {
        String domain = DRAFT_NM.equals(row) ? NM : NW;
        Object uid = ctx.row(row);
        Map<String, Object> body = ctx.ok("sysadmin", editable(uid).domain(domain)).json().getMap("$");
        return SweepRequest.put("/rest/messages/message/{messageId}").var("messageId", uid).json(body);
    }

    /** The write sweep of the create: a draft with two of every list, read back field by field, then DELETED */
    private void createMessageRoundTrip(SweepContext ctx, SweepIdentity as) throws IOException {
        String title = ctx.name("create-" + ctx.unique());
        Map<String, Object> body = fixture.newBody(ctx, as.name(), NW, "NW", SERIES_NW, title, true, List.of(
                Map.of("messageId", (String) ctx.row(PUBLISHED), "type", "REFERENCE"),
                Map.of("messageId", (String) ctx.row(PUBLISHED), "type", "UPDATE")));
        String uid = (String) body.get("id");
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/messages/message").json(body));
        if (!created.is2xx()) {
            return;
        }
        ctx.settle(null);
        try {
            SweepResponse read = ctx.ok(as, editable(uid));
            JsonPath json = read.json();
            ctx.checkEquals(uid, json.getString("id"), "the uid", read);
            ctx.checkEquals("DRAFT", json.getString("status"), "the status", read);
            ctx.checkEquals("LOCAL_WARNING", json.getString("type"), "the type", read);
            ctx.checkEquals(SERIES_NW, json.getString("messageSeries.seriesId"), "the series", read);
            ctx.checkEquals(false, json.getBoolean("autoTitle"), "autoTitle", read);
            ctx.checkEquals(title, json.getString("descs.find { it.lang == 'en' }.title"), "the English title", read);
            ctx.checkEquals(title + "-da", json.getString("descs.find { it.lang == 'da' }.title"), "the Danish title",
                    read);
            ctx.checkEquals("Vicinity da", json.getString("descs.find { it.lang == 'da' }.vicinity"),
                    "the Danish vicinity", read);
            ctx.checkEquals(fixture.areas, json.getList("areas.id"), "the areas in order", read);
            ctx.checkEquals(fixture.charts, json.getList("charts.chartNumber"), "the charts in order", read);
            ctx.checkEquals(new HashSet<>(fixture.categories), new HashSet<>(json.getList("categories.id")),
                    "the categories", read);
            ctx.checkEquals(List.of(title + " part 1", title + " part 2"),
                    json.getList("parts.collect { p -> p.descs.find { it.lang == 'en' }.subject }"),
                    "the parts in order", read);
            ctx.checkEquals(List.of(title + " part 1 da", title + " part 2 da"),
                    json.getList("parts.collect { p -> p.descs.find { it.lang == 'da' }.subject }"),
                    "the parts' Danish subjects", read);
            ctx.checkEquals(List.of("Point", "LineString"),
                    json.getList("parts.collect { p -> p.geometry.features[0].geometry.type }"),
                    "the parts' geometry", read);
            // Sent as epoch milliseconds (3000-01-01T00:00Z, one hour long), read back as ISO-8601
            ctx.checkEquals(List.of("3000-01-01T00:00:00.000+00:00", "3000-01-01T00:00:00.000+00:00"),
                    json.getList("parts.collect { p -> p.eventDates[0].fromDate }", String.class),
                    "the parts' event dates (from)", read);
            ctx.checkEquals(List.of("3000-01-01T01:00:00.000+00:00", "3000-01-01T01:00:00.000+00:00"),
                    json.getList("parts.collect { p -> p.eventDates[0].toDate }", String.class),
                    "the parts' event dates (to)", read);
            ctx.checkEquals(List.of(ctx.row(PUBLISHED), ctx.row(PUBLISHED)), json.getList("references.messageId"),
                    "the references' messages", read);
            ctx.checkEquals(List.of("REFERENCE", "UPDATE"), json.getList("references.type"),
                    "the references in order", read);
            ctx.checkEquals(List.of(ATTACHMENT_TEXT + ".txt", ATTACHMENT_IMAGE + ".png"),
                    json.getList("attachments.fileName"), "the attachments in order", read);
            // The attachments were copied from the edit folder into the message's own folder
            String textPath = json.getString("attachments[0].path");
            SweepResponse text = ctx.ok("editor", repoFile(textPath));
            ctx.checkEquals(ATTACHMENT_TEXT, text.text(), "the text attachment's content in the message folder", text);
        } finally {
            endMessage(ctx, as.name(), uid, "DELETED");
        }
    }

    /** Moves the message to its end status (DELETED for a draft, CANCELLED for a published one) and reads it there */
    private static void endMessage(SweepContext ctx, String identity, String uid, String end) {
        SweepResponse ended = ctx.send(identity, status(uid, end));
        if (!ended.is2xx() && !end.equals(readStatusValue(ctx, identity, uid))) {
            ctx.expectOk(ended);
        }
        readStatus(ctx, identity, uid, end);
    }

    private static String readStatusValue(SweepContext ctx, String identity, String uid) {
        SweepResponse read = ctx.send(identity, editable(uid));
        return read.status() == 200 ? read.json().getString("status") : null;
    }

    private static void readStatus(SweepContext ctx, String identity, String uid, String expected) {
        SweepResponse read = ctx.ok(identity, editable(uid).note("read the status back"));
        ctx.checkEquals(expected, read.json().getString("status"), "the status read back", read);
    }

    private static SweepRequest statuses(String a, String statusA, String b, String statusB) {
        return SweepRequest.put("/rest/messages/update-statuses").json(List.of(
                Map.of("messageId", a, "status", statusA), Map.of("messageId", b, "status", statusB)));
    }

    /** The area sort order of a message, which no endpoint shows */
    private static double areaSortOrder(SweepContext ctx, String uid) {
        Double order = ctx.bean(JdbcTemplate.class).queryForObject(
                "SELECT \"areaSortOrder\" FROM public.\"Message\" WHERE uid = ?", Double.class, uid);
        return order == null ? Double.NaN : order;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value == null ? new ArrayList<>() : new ArrayList<>((List<Object>) value);
    }

    // ------------------------------------------------------------------------------------------- //
    // Computations: they store nothing
    // ------------------------------------------------------------------------------------------- //

    private void computations(SweepCases cases) {
        cases.add("adjustEditableMessage", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/adjust-editable-message").json(adjustBody(ctx)))
                .sweep((ctx, as) -> {
                    SweepResponse adjusted = ctx.ok(as, SweepRequest.post("/rest/messages/adjust-editable-message")
                            .json(adjustBody(ctx)));
                    JsonPath json = adjusted.json();
                    ctx.check(json.getList("descs.lang").contains("en"), "an auto title gives an English title line",
                            adjusted);
                    ctx.check(json.getString("descs.find { it.lang == 'en' }.title") != null,
                            "the English title line is computed", adjusted);
                    ctx.check(json.get("messageSeries") == null, "the series is pruned from the answer", adjusted);
                }));

        cases.add("extractMessagePublication", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/extract-message-publication")
                        .query("publicationId", publicationId).query("lang", "en")
                        .json(Map.of("descs", List.of(Map.of("lang", "en", "title", ctx.name("publication"))))))
                .sweep((ctx, as) -> {
                    // A message that cites the publication (as the update below writes it) gives the publication back;
                    // one that does not cite it gives nothing (204), which the editor reads as an empty form
                    Map<String, Object> cited = citing(ctx, as);
                    SweepResponse extracted = ctx.ok(as, SweepRequest.post("/rest/messages/extract-message-publication")
                            .query("publicationId", publicationId).query("lang", "en").json(cited));
                    ctx.checkEquals(publicationId, extracted.json().getString("publication.publicationId"),
                            "the publication extracted from the message", extracted);
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/extract-message-publication")
                            .query("publicationId", publicationId).query("lang", "en")
                            .json(Map.of("descs", List.of(Map.of("lang", "en", "title", ctx.name("publication")))))
                            .note("a message that does not cite it"), Cell.status(204, null));
                }));

        cases.add("updateMessagePublications", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/update-message-publications")
                        .query("publicationId", publicationId).query("parameters", "sweep-messages-parameter")
                        .query("lang", "en")
                        .json(Map.of("descs", List.of(Map.of("lang", "en", "title", ctx.name("publication"))))))
                .sweep((ctx, as) -> {
                    Map<String, Object> cited = citing(ctx, as);
                    ctx.checkEquals(ctx.name("publication"), firstDesc(cited).get("title"),
                            "the message's title after the citation", null);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/messages/update-message-publications")
                        .query("publicationId", publicationId).json(Map.of()).note("a message without descs"),
                        Cell.status(400, "A message must carry its descs"))));

        cases.add("formatGeometry", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/format-message-geometry")
                        .json(featureCollection()))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/format-message-geometry")
                                    .query("template", "../x").json(featureCollection()).note("a template path"),
                            Cell.status(400, "Invalid geometry template"));
                    ctx.call(as.name(), SweepRequest.post("/rest/messages/format-message-geometry")
                                    .query("template", "sweep-none").json(featureCollection()).note("an unknown template"),
                            Cell.status(404, "No geometry template sweep-none"));
                }));

        cases.add("parsePlainTextGeometry", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/parse-geometry")
                        .json(Map.of("geometryText", "54° 45,7' N 10° 29,1' E")))
                .sweep((ctx, as) -> {
                    SweepResponse parsed = ctx.ok(as, SweepRequest.post("/rest/messages/parse-geometry")
                            .json(Map.of("geometryText", "54° 45,7' N 10° 29,1' E")));
                    List<Float> coordinates = parsed.json().getList("features[0].geometry.coordinates", Float.class);
                    ctx.check(coordinates.size() == 2 && Math.abs(coordinates.get(1) - 54.7617) < 0.001
                            && Math.abs(coordinates.get(0) - 10.485) < 0.001, "the parsed point is 54.7617 N, "
                            + "10.485 E (is " + coordinates + ")", parsed);
                }));

        cases.add("formatGeometryAsPlainText", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/format-geometry").json(featureCollection()))
                .sweep((ctx, as) -> {
                    SweepResponse text = ctx.ok(as, SweepRequest.post("/rest/messages/format-geometry")
                            .query("lang", "en").json(featureCollection()));
                    ctx.check(text.text().contains("56") && text.text().contains("11"),
                            "the plain text holds the point's degrees", text);
                }));

        cases.add("parseUtmGeometry", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/messages/parse-utm")
                        .json(Map.of("geometryText", "32 V 624726 6207884")))
                .sweep((ctx, as) -> {
                    SweepResponse parsed = ctx.ok(as, SweepRequest.post("/rest/messages/parse-utm")
                            .json(Map.of("geometryText", "32 V 624726 6207884")));
                    List<Float> coordinates = parsed.json().getList("features[0].geometry.coordinates", Float.class);
                    ctx.check(coordinates.size() == 2 && coordinates.get(1) > 55 && coordinates.get(1) < 57
                            && coordinates.get(0) > 10 && coordinates.get(0) < 12, "the UTM point lies in Denmark "
                            + "(is " + coordinates + ")", parsed);
                }));
    }

    /**
     * A message whose English publication field cites the seed's publication, as update-message-publications writes
     * it (the answer is checked: the citation names the publication and the parameters)
     */
    private Map<String, Object> citing(SweepContext ctx, SweepIdentity as) {
        SweepResponse updated = ctx.ok(as, SweepRequest.post("/rest/messages/update-message-publications")
                .query("publicationId", publicationId).query("parameters", "sweep-messages-parameter")
                .query("lang", "en")
                .json(Map.of("descs", List.of(Map.of("lang", "en", "title", ctx.name("publication"))))));
        Map<String, Object> message = updated.json().getMap("$");
        Object publication = firstDesc(message).get("publication");
        // The seed's publication has a citation format without ${parameters}, so the parameters are not cited
        ctx.check(publication != null && publication.toString().contains("publication=\"" + publicationId + "\""),
                "the English publication field cites the publication", updated);
        return message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDesc(Map<String, Object> message) {
        List<Map<String, Object>> descs = (List<Map<String, Object>>) message.get("descs");
        return descs == null || descs.isEmpty() ? Map.of() : descs.getFirst();
    }

    private Map<String, Object> adjustBody(SweepContext ctx) {
        Map<String, Object> body = fixture.body(UUID.randomUUID().toString(), null, null, "NW", SERIES_NW,
                ctx.name("adjust"), List.of(), List.of());
        body.put("autoTitle", true);
        body.remove("repoPath");
        body.remove("editRepoPath");
        return body;
    }

    private static Map<String, Object> featureCollection() {
        return Map.of("type", "FeatureCollection", "features", List.of(MessagesSweepFixture.point(11.25, 56.5)));
    }

    // ------------------------------------------------------------------------------------------- //
    // Searches and the export: what a caller gets narrows to what the caller may see
    // ------------------------------------------------------------------------------------------- //

    private void searches(SweepCases cases) {
        cases.add("search", SweepCase
                .of((ctx, row) -> draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")))
                .sweep((ctx, as) -> {
                    SweepResponse anonymous = ctx.ok(as, draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")));
                    absent(ctx, anonymous, DRAFT, "an anonymous search leaves out the draft");
                    SweepResponse user = ctx.ok("user", draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")));
                    ctx.expectRows(user, "data", DRAFT, PUBLISHED);
                    SweepResponse other = ctx.ok("userOtherDomain",
                            draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")));
                    absent(ctx, other, DRAFT, "a search from another domain leaves out the draft");
                    SweepResponse header = ctx.ok("anonymous+header",
                            draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")));
                    absent(ctx, header, DRAFT, "a search with the domain header and no token leaves out the draft");
                    // userOtherDomain holds no role in niord-nm, so the status floor hides the draft from it; the
                    // sysadmin holds the user role there, so only the series of the search domain do
                    SweepResponse nm = ctx.ok("sysadmin",
                            draftAndPublished(ctx, SweepRequest.get("/rest/messages/search")).domain(NM));
                    absent(ctx, nm, DRAFT, "a search by a user of niord-nm leaves out the niord-nw draft");
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/messages/search")
                        .query("status", "NOPE").note("an unknown status"), Cell.status(400, "NOPE"))));

        cases.add("searchMessageIds", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/messages/search-message-ids")
                        .query("txt", ctx.row(DRAFT)).query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse anonymous = ctx.ok(as, SweepRequest.get("/rest/messages/search-message-ids")
                            .query("txt", ctx.row(DRAFT)).query("lang", "en"));
                    absent(ctx, anonymous, DRAFT, "an anonymous id search does not name the draft");
                    SweepResponse user = ctx.ok("user", SweepRequest.get("/rest/messages/search-message-ids")
                            .query("txt", ctx.row(DRAFT)).query("lang", "en"));
                    ctx.expectRows(user, null, DRAFT);
                    SweepResponse published = ctx.ok(as, SweepRequest.get("/rest/messages/search-message-ids")
                            .query("txt", ctx.row(PUBLISHED)).query("lang", "en"));
                    ctx.expectRows(published, null, PUBLISHED);
                }));

        cases.add("generateZipArchiveForSearch", SweepCase
                .of((ctx, row) -> draftAndPublished(ctx, SweepRequest.get("/rest/message-io/export.zip")))
                .sweep((ctx, as) -> {
                    SweepResponse anonymous = ctx.ok(as, draftAndPublished(ctx,
                            SweepRequest.get("/rest/message-io/export.zip")));
                    String json = messagesJson(ctx, anonymous);
                    ctx.check(SweepEngine.holdsName(json, PUBLISHED), "the anonymous export holds the published "
                            + "message", anonymous);
                    ctx.check(!SweepEngine.holdsName(json, DRAFT), "the anonymous export leaves out the draft",
                            anonymous);
                    SweepResponse ticket = ctx.ok("ticket(user:user)", draftAndPublished(ctx,
                            SweepRequest.get("/rest/message-io/export.zip")));
                    ctx.check(SweepEngine.holdsName(messagesJson(ctx, ticket), DRAFT),
                            "the export with a ticket of the user role holds the draft", ticket);
                    SweepResponse roleless = ctx.ok("ticket(user)", draftAndPublished(ctx,
                            SweepRequest.get("/rest/message-io/export.zip")));
                    ctx.check(!SweepEngine.holdsName(messagesJson(ctx, roleless), DRAFT),
                            "the export with a ticket of no role leaves out the draft", roleless);
                }));

        cases.add("importMessages", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-io/import")
                        .file("file", ctx.name("refused") + ".zip", new byte[0], "application/zip")));
    }

    /** The search parameters that find the fixture's draft and the published message it references */
    private static SweepRequest draftAndPublished(SweepContext ctx, SweepRequest request) {
        return request.query("messageId", ctx.row(DRAFT)).query("status", "DRAFT").query("status", "PUBLISHED")
                .query("lang", "en");
    }

    /** The messages.json of an export zip */
    private static String messagesJson(SweepContext ctx, SweepResponse zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if ("messages.json".equals(entry.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        ctx.fail(zip, "the export holds a messages.json");
        return "";
    }

    // ------------------------------------------------------------------------------------------- //
    // The mail and the map images
    // ------------------------------------------------------------------------------------------- //

    private void mailAndMapImages(SweepCases cases) {
        cases.add("sendMessageMail", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-mail/send")
                        .query("mailTo", ctx.name("refused") + "@example.org")
                        .query("mailSubject", ctx.name("refused")).query("messageId", ctx.row(PUBLISHED)))
                .sweep((ctx, as) -> {
                    String subject = ctx.name("mail-" + ctx.unique());
                    String to = ctx.name(ctx.unique()) + "@example.org";
                    // The minimal call (MAIL11): an address and a search that finds a message, no language
                    SweepResponse sent = ctx.ok(as, SweepRequest.get("/rest/message-mail/send").query("mailTo", to)
                            .query("mailSubject", subject).query("messageId", ctx.row(PUBLISHED)));
                    ctx.check(sent.text().contains("Message mail scheduled to " + to), "the answer names the address",
                            sent);
                    try {
                        SweepResponse queued = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                                .query("subject", subject));
                        ctx.checkEquals(1, ((Number) queued.json().get("total")).intValue(),
                                "the number of scheduled mails of the subject", queued);
                        ctx.check(queued.text().contains(to), "the scheduled mail names the recipient", queued);
                        // Sent here as the minute sender sends it (no endpoint sends a queued mail). Its stylesheet
                        // is fetched from the baseUri, http://localhost:8888, where nothing listens in the test
                        // context: the mail goes out without it (MAIL9), in the LOG simulation mode
                        Integer mailId = ctx.bean(JdbcTemplate.class).queryForObject(
                                "SELECT id FROM public.\"ScheduledMail\" WHERE subject = ?", Integer.class, subject);
                        ctx.bean(MailService.class).sendScheduledMail(mailId);
                        SweepResponse sentMail = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                                .query("subject", subject).note("read after the send"));
                        ctx.checkEquals("SENT", sentMail.json().get("data[0].status"),
                                "the scheduled mail's status after the send", sentMail);
                    } finally {
                        removeMail(ctx, subject);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/message-mail/send")
                        .query("messageId", ctx.row(PUBLISHED)).note("no address"),
                        Cell.status(400, "A message mail needs at least one address"))));

        // The same mail with the recipients, the subject and the text in the body (MAIL12); the search stays in the
        // query
        cases.add("sendMessageMailFromBody", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-mail/send")
                        .query("messageId", ctx.row(PUBLISHED))
                        .json(Map.of("mailTo", List.of(ctx.name("refused") + "@example.org"),
                                "mailSubject", ctx.name("refused"))))
                .sweep((ctx, as) -> {
                    String subject = ctx.name("mail-" + ctx.unique());
                    String to = ctx.name(ctx.unique()) + "@example.org";
                    SweepResponse sent = ctx.ok(as, SweepRequest.post("/rest/message-mail/send")
                            .query("messageId", ctx.row(PUBLISHED))
                            .json(Map.of("mailTo", List.of(to), "mailSubject", subject, "mailMessage", "Sweep")));
                    ctx.check(sent.text().contains("Message mail scheduled to " + to), "the answer names the address",
                            sent);
                    try {
                        SweepResponse queued = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                                .query("subject", subject));
                        ctx.checkEquals(1, ((Number) queued.json().get("total")).intValue(),
                                "the number of scheduled mails of the subject", queued);
                        ctx.check(queued.text().contains(to), "the scheduled mail names the recipient", queued);
                    } finally {
                        removeMail(ctx, subject);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/message-mail/send")
                            .query("messageId", ctx.row(PUBLISHED)).json(Map.of("mailSubject", ctx.name("refused")))
                            .note("no address"), Cell.status(400, "A message mail needs at least one address"));
                    ctx.call(as.name(), SweepRequest.post("/rest/message-mail/send")
                            .query("messageId", ctx.row(PUBLISHED))
                            .json(Map.of("mailTo", List.of(ctx.name("no-domain")), "mailSubject", ctx.name("refused")))
                            .note("malformed address (MAIL8)"), Cell.status(400, "is not a mail address"));
                }));

        cases.add("getMessageMapImage", SweepCase
                .of((ctx, row) -> mapImage(ctx.row(PUBLISHED)))
                .sweep((ctx, as) -> withTileStub(() -> {
                    SweepResponse image = ctx.ok(as, mapImage(ctx.row(PUBLISHED)));
                    ctx.check(image.contentType() != null && image.contentType().startsWith("image/png"),
                            "the map image is a PNG", image);
                    ctx.check(!"placeholder".equalsIgnoreCase(image.header("X-Niord-Map-Image")),
                            "the map image is drawn, not the placeholder", image);
                }))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.send(as, mapImage("sweep-no-such-message").note("unknown-id probe"));
                    ctx.check(unknown.status() == 404 && unknown.text().contains("Message map image not found"),
                            "an unknown message's map image answers 404 \"Message map image not found\"", unknown);
                }));

        cases.add("updateMessageMapImage", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/message-map-image/{*folder}")
                        .var("folder", row == null ? "temp/sweep-messages-refused" : ctx.row(row))
                        .json(dataUrl()))
                .sweep((ctx, as) -> customThumbnail(ctx, as, SweepRequest.put("/rest/message-map-image/{*folder}")
                        .json(dataUrl())))
                .probes((ctx, as) -> {
                    String folder = newTempDir(ctx, as.name());
                    ctx.call(as.name(), SweepRequest.put("/rest/message-map-image/{*folder}").var("folder", folder)
                            .json("not a data url").note("not a data URL"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/message-map-image/{*folder}").var("folder", folder)
                                    .json("data:image/png;base64," + Base64.getEncoder().encodeToString(
                                            "not an image".getBytes(StandardCharsets.UTF_8))).note("not an image"),
                            Cell.status(400, "The uploaded data is not an image"));
                }));

        cases.add("uploadMessageMapImage", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-map-image/{*folder}")
                        .var("folder", row == null ? "temp/sweep-messages-refused" : ctx.row(row))
                        .file("file", ctx.name("refused") + ".png", MessagesSweepFixture.png(), "image/png"))
                .sweep((ctx, as) -> customThumbnail(ctx, as, SweepRequest.post("/rest/message-map-image/{*folder}")
                        .file("file", ctx.name("thumb") + ".png", MessagesSweepFixture.png(), "image/png"))));
    }

    /**
     * Removes the sweep's still-pending scheduled mail of the subject (no endpoint removes a scheduled mail), and
     * checks none is left
     */
    private static void removeMail(SweepContext ctx, String subject) {
        JdbcTemplate jdbc = ctx.bean(JdbcTemplate.class);
        String mails = "SELECT id FROM public.\"ScheduledMail\" WHERE subject = ?";
        jdbc.update("DELETE FROM public.\"ScheduledMailRecipient\" WHERE mail_id IN (" + mails + ")", subject);
        jdbc.update("DELETE FROM public.\"ScheduledMail\" WHERE id IN (" + mails + ")", subject);
        SweepResponse left = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                .query("subject", subject).note("read after the removal"));
        ctx.checkEquals(0, ((Number) left.json().get("total")).intValue(),
                "the scheduled mails of the subject after their removal", left);
    }

    private static SweepRequest mapImage(Object uid) {
        return SweepRequest.get("/rest/message-map-image/{uid}.png").var("uid", uid);
    }

    private static String dataUrl() throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(MessagesSweepFixture.png());
    }

    /** Stores a custom thumbnail into a fresh temp folder, reads it back as a PNG, removes it and reads it gone */
    private static void customThumbnail(SweepContext ctx, SweepIdentity as, SweepRequest store) {
        String folder = newTempDir(ctx, as.name());
        SweepResponse stored = ctx.ok(as, store.var("folder", folder));
        String path = stored.text().trim();
        ctx.check(path.startsWith(folder + "/custom_thumb_") && path.endsWith(".png"),
                "the answer is the thumbnail's path in the folder", stored);
        try {
            SweepResponse image = ctx.ok("anonymous", repoFile(path));
            ctx.check(image.contentType() != null && image.contentType().startsWith("image/png"),
                    "the stored thumbnail reads back as a PNG", image);
        } finally {
            deleteRepoFileAndReadGone(ctx, as.name(), path);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /**
     * Runs the body with the map tiles served by a stub on the loopback (as {@code MessageMapImageEndToEndITTest}
     * does): the tile address, the contact and a tile cache folder of its own, restored afterwards. No tile server
     * outside this machine is asked.
     */
    private static void withTileStub(Body body) throws Exception {
        Map<String, String> previous = new LinkedHashMap<>();
        Path cache = Files.createTempDirectory("niord-sweep-tile-cache-");
        byte[] tile = tilePng();
        try (HttpStub tiles = HttpStub.start()) {
            tiles.respond(request -> StubResponse.bytes("image/png", tile));
            setProperty(previous, "osmUrl", tiles.baseUrl() + "/sweep-" + UUID.randomUUID() + "/{z}/{x}/{y}.png");
            setProperty(previous, "mapTileContact", "sweep@example.org");
            setProperty(previous, "mapTileCachePath", cache.toString());
            body.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
            try (var files = Files.walk(cache)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void setProperty(Map<String, String> previous, String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** A 256 x 256 tile of one colour */
    private static byte[] tilePng() throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(256, 256,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(170, 211, 223));
        g.fillRect(0, 0, 256, 256);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------------------------- //
    // Reports and the PDFs
    // ------------------------------------------------------------------------------------------- //

    private void reports(SweepCases cases) {
        cases.add("getReports", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-reports/reports"))
                .sweep((ctx, as) -> {
                    // The public reports for everyone; a domain's own reports for a caller in that domain only
                    SweepRequest reports = SweepRequest.get("/rest/message-reports/reports");
                    SweepResponse anonymous = ctx.ok(as, reports);
                    ctx.expectRows(anonymous, null, REPORT);
                    absent(ctx, anonymous, REPORT_NM, "the reports for a caller without a domain leave out a report of "
                            + "niord-nm");
                    SweepResponse user = ctx.ok("user", reports);
                    ctx.expectRows(user, null, REPORT);
                    absent(ctx, user, REPORT_NM, "the reports in niord-nw leave out a report of niord-nm");
                    ctx.expectRows(ctx.ok("userOtherDomain", reports), null, REPORT, REPORT_NM);
                })
                .variants((ctx, as) -> {
                    SweepResponse plain = ctx.ok(as, SweepRequest.get("/rest/message-reports/reports")
                            .query("expandParams", false));
                    ctx.expectRows(plain, null, REPORT);
                }));

        cases.add("getAllReports", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-reports/all"))
                .as("sysadmin"));

        cases.add("getDetailReports", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/message-reports/detail-reports"))
                .sweep((ctx, as) -> {
                    SweepResponse details = ctx.ok(as, SweepRequest.get("/rest/message-reports/detail-reports"));
                    ctx.checkEquals(List.of("standard", "draft"), details.json().getList("reportId"),
                            "the detail reports", details);
                }));

        // Its round trip (the parameters expanded, nothing stored) is MessageReportRestCrudITTest's; the case gives
        // the cells their request
        cases.add("expandReportParams", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-reports/expand-params")
                        .json(MessagesSweepFixture.report(REPORT, REPORT))));

        cases.add("createReport", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-reports/report")
                        .json(MessagesSweepFixture.report(ctx.name("report-refused"), ctx.name("report-refused"))))
                .sweep((ctx, as) -> createReportRoundTrip(ctx, as, "/rest/message-reports/report"))
                .variants((ctx, as) -> createReportRoundTrip(ctx, as, "/rest/message-reports/report/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/message-reports/report").json(Map.of())
                            .note("empty body"), Cell.status(400, "A report needs a report ID"));
                    ctx.call(as.name(), SweepRequest.post("/rest/message-reports/report")
                                    .json(Map.of("reportId", ctx.name("no-name"))).note("no name"),
                            Cell.status(400, "needs a name"));
                }));

        cases.add("updateReport", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/message-reports/report/{reportId}").var("reportId", REPORT)
                        .json(MessagesSweepFixture.report(REPORT, REPORT)))
                .sweep((ctx, as) -> {
                    String reportId = ctx.name("report-update-" + ctx.unique());
                    ctx.ok(as, SweepRequest.post("/rest/message-reports/report")
                            .json(MessagesSweepFixture.report(reportId, reportId)));
                    try {
                        Map<String, Object> changed = MessagesSweepFixture.report(reportId, reportId + "-renamed",
                                List.of(NM));
                        changed.put("sortOrder", 901);
                        changed.put("properties", Map.of("sweepProperty", "changed"));
                        ctx.ok(as, SweepRequest.put("/rest/message-reports/report/{reportId}")
                                .var("reportId", reportId).json(changed));
                        readReport(ctx, as, reportId, reportId + "-renamed", 901, "changed", List.of(NM));
                    } finally {
                        removeReportAndReadGone(ctx, as, reportId);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/message-reports/report/{reportId}")
                                    .var("reportId", REPORT).json(MessagesSweepFixture.report("sweep-another", REPORT))
                                    .note("the body's id is not the path's"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/message-reports/report/{reportId}")
                                    .var("reportId", "sweep-no-such-report")
                                    .json(MessagesSweepFixture.report("sweep-no-such-report", "x"))
                                    .note("unknown-id probe"),
                            Cell.status(404, "Report sweep-no-such-report not found"));
                }));

        cases.add("deleteReport", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/message-reports/report/{reportId}").var("reportId", REPORT))
                .sweep((ctx, as) -> {
                    String reportId = ctx.name("report-delete-" + ctx.unique());
                    ctx.ok(as, SweepRequest.post("/rest/message-reports/report")
                            .json(MessagesSweepFixture.report(reportId, reportId)));
                    removeReportAndReadGone(ctx, as, reportId);
                }));

        cases.add("importReports", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/message-reports/upload-reports")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String reportId = ctx.name("report-upload-" + ctx.unique());
                    String json = "[{\"reportId\":\"" + reportId + "\",\"name\":\"" + reportId + "\",\"sortOrder\":902,"
                            + "\"templatePath\":\"/templates/messages/message-list-pdf.ftl\","
                            + "\"properties\":{\"sweepProperty\":\"uploaded\"},\"params\":{}}]";
                    String fileName = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/message-reports/upload-reports"), json);
                    ctx.jobs().awaitClean(ctx, "report-import", fileName);
                    try {
                        readReport(ctx, as, reportId, reportId, 902, "uploaded", List.of());
                    } finally {
                        removeReportAndReadGone(ctx, as, reportId);
                    }
                }));

        cases.add("generatePdfForMessage", SweepCase
                .of((ctx, row) -> messagePdf(ctx.row(row == null ? PUBLISHED : row)))
                .sweep((ctx, as) -> {
                    ctx.ok(as, messagePdf(ctx.row(PUBLISHED)));
                    // An anonymous caller gets the standard report only
                    ctx.call(as.name(), messagePdf(ctx.row(PUBLISHED)).query("report", "draft"),
                            Cell.status(403, null));
                    // A signed-in caller may ask for the HTML the PDF is made from
                    SweepResponse html = ctx.ok("user", messagePdf(ctx.row(DRAFT)).query("debug", true));
                    ctx.check(SweepEngine.holdsName(html.text(), DRAFT), "the user's draft report holds the draft's "
                            + "title", html);
                }));

        cases.add("generatePdfForSearch", SweepCase
                .of((ctx, row) -> draftAndPublished(ctx, SweepRequest.get("/rest/message-reports/report.pdf")))
                .sweep((ctx, as) -> {
                    ctx.ok(as, draftAndPublished(ctx, SweepRequest.get("/rest/message-reports/report.pdf")));
                    ctx.call(as.name(), draftAndPublished(ctx, SweepRequest.get("/rest/message-reports/report.pdf"))
                            .query("report", "draft"), Cell.status(403, null));
                    SweepResponse user = ctx.ok("user", draftAndPublished(ctx,
                            SweepRequest.get("/rest/message-reports/report.pdf")).query("debug", true));
                    ctx.check(SweepEngine.holdsName(user.text(), DRAFT) && SweepEngine.holdsName(user.text(), PUBLISHED),
                            "the user's list report holds the draft and the published message", user);
                    SweepResponse other = ctx.ok("userOtherDomain", draftAndPublished(ctx,
                            SweepRequest.get("/rest/message-reports/report.pdf")).query("debug", true));
                    ctx.check(!SweepEngine.holdsName(other.text(), DRAFT),
                            "a list report from another domain leaves out the draft", other);
                }));
    }

    private static SweepRequest messagePdf(Object uid) {
        return SweepRequest.get("/rest/message-reports/message/{messageId}.pdf").var("messageId", uid);
    }

    private static void createReportRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        String reportId = ctx.name("report-" + ctx.unique());
        ctx.ok(as, SweepRequest.post(pattern).json(MessagesSweepFixture.report(reportId, reportId, List.of(NM))));
        try {
            readReport(ctx, as, reportId, reportId, 900, "sweep-value", List.of(NM));
        } finally {
            removeReportAndReadGone(ctx, as, reportId);
        }
    }

    private static void readReport(SweepContext ctx, SweepIdentity as, String reportId, String name, int sortOrder,
                                   String property, List<String> domains) {
        SweepResponse all = ctx.ok(as, SweepRequest.get("/rest/message-reports/all"));
        String at = "find { it.reportId == '" + reportId + "' }";
        JsonPath json = all.json();
        if (!ctx.check(json.get(at) != null, "the reports hold " + reportId, all)) {
            return;
        }
        ctx.checkEquals(name, json.getString(at + ".name"), "the report's name", all);
        ctx.checkEquals(sortOrder, json.getInt(at + ".sortOrder"), "the report's sort order", all);
        ctx.checkEquals("/templates/messages/message-list-pdf.ftl", json.getString(at + ".templatePath"),
                "the report's template", all);
        ctx.checkEquals(property, json.getString(at + ".properties.sweepProperty"), "the report's property", all);
        ctx.checkEquals(domains, json.getList(at + ".domains.domainId"), "the report's domains", all);
    }

    private static void removeReportAndReadGone(SweepContext ctx, SweepIdentity as, String reportId) {
        ctx.ok(as, SweepRequest.delete("/rest/message-reports/report/{reportId}").var("reportId", reportId));
        SweepResponse all = ctx.ok(as, SweepRequest.get("/rest/message-reports/all").note("read after the delete"));
        ctx.check(!all.json().getList("reportId").contains(reportId), "the report " + reportId + " is gone", all);
    }

    // ------------------------------------------------------------------------------------------- //
    // The public API: anonymous, no domain header
    // ------------------------------------------------------------------------------------------- //

    private void publicApi(SweepCases cases) {
        cases.add("searchMessages", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/messages").query("messageSeries", SERIES_NW)
                        .query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse found = ctx.ok(as, SweepRequest.get("/rest/public/v1/messages")
                            .query("messageSeries", SERIES_NW).query("lang", "en"));
                    absent(ctx, found, DRAFT, "the public search leaves out the draft");
                })
                .variants((ctx, as) -> {
                    SweepResponse iso = ctx.ok(as, SweepRequest.get("/rest/public/v1/messages")
                            .query("messageSeries", SERIES_NW).query("dateFormat", "ISO_8601"));
                    ctx.expectRows(iso, null, PUBLISHED);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/public/v1/messages")
                        .query("mainType", "NOPE").note("an unknown main type"),
                        Cell.status(400, "Failed to convert 'mainType' with value: 'NOPE'"))));

        cases.add("messageDetails", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/message/{messageId}")
                        .var("messageId", ctx.row(PUBLISHED)))
                .sweep((ctx, as) -> {
                    ctx.ok(as, SweepRequest.get("/rest/public/v1/message/{messageId}").var("messageId",
                            ctx.row(PUBLISHED)));
                    SweepResponse draft = ctx.send(as, SweepRequest.get("/rest/public/v1/message/{messageId}")
                            .var("messageId", ctx.row(DRAFT)).note("the draft"));
                    ctx.check(draft.status() == 404 && draft.text().contains("No message found with ID"),
                            "the draft is not found by the public API (404 \"No message found with ID\")", draft);
                }));

        cases.add("getMessageXsd", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/xsd/{schemaFile}").var("schemaFile", "schema1.xsd"))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/public/v1/xsd/{schemaFile}")
                        .var("schemaFile", "schema9.xsd").note("an unknown schema"),
                        Cell.status(400, "Only 'schema1.xsd' and 'schema2.xsd' allowed"))));

        cases.add("searchPublications", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/publications").query("lang", "en")));

        cases.add("publicationDetails", SweepCase
                .of((ctx, row) -> {
                    // A publication the public search lists (the legacy seed rows are not public)
                    SweepResponse listed = ctx.ok("anonymous", SweepRequest.get("/rest/public/v1/publications"));
                    List<String> ids = listed.json().getList("publicationId");
                    return SweepRequest.get("/rest/public/v1/publication/{publicationId}")
                            .var("publicationId", ids.isEmpty() ? "sweep-none" : ids.getFirst()).query("lang", "en");
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.send(as, SweepRequest.get("/rest/public/v1/publication/{publicationId}")
                            .var("publicationId", "sweep-no-such-publication").note("unknown-id probe"));
                    ctx.check(unknown.status() == 404 && unknown.text().contains("No publication found with ID"),
                            "an unknown publication answers 404 \"No publication found with ID\"", unknown);
                }));

        cases.add("areaDetails", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/area/{areaId}").var("areaId", parentArea)
                        .query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse area = ctx.ok(as, SweepRequest.get("/rest/public/v1/area/{areaId}")
                            .var("areaId", parentArea).query("lang", "en"));
                    ctx.checkEquals(parentArea, area.json().getInt("id"), "the area's id", area);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.send(as, SweepRequest.get("/rest/public/v1/area/{areaId}")
                            .var("areaId", UNKNOWN).note("unknown-id probe"));
                    ctx.check(unknown.status() == 404 && unknown.text().contains("No area found with ID"),
                            "an unknown area answers 404 \"No area found with ID\"", unknown);
                }));

        cases.add("subAreas", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/public/v1/area/{areaId}/sub-areas").var("areaId", parentArea)
                        .query("lang", "en"))
                .sweep((ctx, as) -> {
                    SweepResponse children = ctx.ok(as, SweepRequest.get("/rest/public/v1/area/{areaId}/sub-areas")
                            .var("areaId", parentArea).query("lang", "en"));
                    ctx.check(!children.json().getList("$").isEmpty(), "the area has active sub-areas", children);
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // The repository: anyone reads a served file; an editor writes and deletes below the temp root only
    // ------------------------------------------------------------------------------------------- //

    private void repository(SweepCases cases) {
        cases.add("streamFile", SweepCase
                .of((ctx, row) -> repoFile(ctx.row(ATTACHMENT_TEXT)))
                .probes((ctx, as) -> {
                    SweepResponse missing = ctx.send(as, repoFile("messages/sweep-no-such-file.txt")
                            .note("unknown-file probe"));
                    ctx.check(missing.status() == 404 && missing.text().equals("File not found"),
                            "a missing file answers 404 \"File not found\"", missing);
                }));

        cases.add("getThumbnail", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/repo/thumb/{*file}").var("file", ctx.row(ATTACHMENT_IMAGE)))
                .sweep((ctx, as) -> {
                    SweepResponse thumb = ctx.ok(as, SweepRequest.get("/rest/repo/thumb/{*file}")
                            .var("file", ctx.row(ATTACHMENT_IMAGE)).query("size", 32));
                    ctx.check(thumb.contentType() != null && thumb.contentType().startsWith("image/"),
                            "the thumbnail of an image is an image", thumb);
                    // A file that is not an image gets its type's icon
                    SweepResponse icon = ctx.send(as, SweepRequest.get("/rest/repo/thumb/{*file}")
                            .var("file", ctx.row(ATTACHMENT_TEXT)));
                    ctx.check(icon.status() == 307 && icon.header("Location") != null,
                            "a text file's thumbnail redirects to an icon", icon);
                }));

        cases.add("listFiles", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/repo/list/{*folder}").var("folder", ctx.row(FOLDER))));

        cases.add("getNewTempDir", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/repo/new-temp-dir"))
                .sweep((ctx, as) -> {
                    SweepResponse dir = ctx.ok(as, SweepRequest.get("/rest/repo/new-temp-dir"));
                    ctx.check(dir.json().getString("path").startsWith("temp/") && dir.json().getBoolean("directory"),
                            "a new temp folder below temp/", dir);
                }));

        cases.add("deleteFile", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/repo/file/{*file}")
                        .var("file", row == null ? "temp/sweep-messages-refused/x.txt" : ctx.row(row)))
                .sweep((ctx, as) -> {
                    String folder = newTempDir(ctx, as.name());
                    List<String> paths = ctx.ok(as, SweepRequest.post("/rest/repo/upload-temp/{*folder}")
                            .var("folder", folder).file("file", "sweep-messages-delete.txt",
                                    "x".getBytes(StandardCharsets.UTF_8), "text/plain")).json().getList("$");
                    deleteRepoFileAndReadGone(ctx, as.name(), paths.getFirst());
                })
                .probes((ctx, as) -> {
                    SweepResponse missing = ctx.send(as, SweepRequest.delete("/rest/repo/file/{*file}")
                            .var("file", "temp/sweep-no-such-folder/x.txt").note("unknown-file probe"));
                    ctx.check(missing.status() == 404 && missing.text().startsWith("File not found"),
                            "deleting a missing file answers 404 \"File not found\"", missing);
                }));

        cases.add("uploadFile", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/repo/upload/{*folder}")
                        .var("folder", row == null ? "temp/sweep-messages-refused" : ctx.row(row))
                        .file("file", ctx.name("refused") + ".txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .sweep((ctx, as) -> uploadRoundTrip(ctx, as, "/rest/repo/upload/{*folder}")));

        cases.add("uploadTempFile", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/repo/upload-temp/{*folder}")
                        .var("folder", row == null ? "temp/sweep-messages-refused" : ctx.row(row))
                        .file("file", ctx.name("refused") + ".txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .sweep((ctx, as) -> uploadRoundTrip(ctx, as, "/rest/repo/upload-temp/{*folder}")));
    }

    private static SweepRequest repoFile(Object path) {
        return SweepRequest.get("/rest/repo/file/{*file}").var("file", path);
    }

    private static String newTempDir(SweepContext ctx, String identity) {
        return ctx.ok(identity, SweepRequest.get("/rest/repo/new-temp-dir")).json().getString("path");
    }

    /** Uploads two files of one name into a fresh temp folder: the second gets a numbered name. Reads, removes both */
    private static void uploadRoundTrip(SweepContext ctx, SweepIdentity as, String pattern) {
        String folder = newTempDir(ctx, as.name());
        SweepResponse uploaded = ctx.ok(as, SweepRequest.post(pattern).var("folder", folder)
                .file("file", "sweep-messages-upload.txt", "first".getBytes(StandardCharsets.UTF_8), "text/plain")
                .file("file", "sweep-messages-upload.txt", "second".getBytes(StandardCharsets.UTF_8), "text/plain"));
        List<String> paths = uploaded.json().getList("$");
        ctx.checkEquals(List.of(folder + "/sweep-messages-upload.txt", folder + "/sweep-messages-upload 2.txt"),
                paths, "the uploaded files' paths", uploaded);
        try {
            List<String> contents = new ArrayList<>();
            for (String path : paths) {
                contents.add(ctx.ok("anonymous", repoFile(path)).text());
            }
            ctx.checkEquals(List.of("first", "second"), contents, "the uploaded files' contents", uploaded);
            SweepResponse listed = ctx.ok("anonymous", SweepRequest.get("/rest/repo/list/{*folder}")
                    .var("folder", folder));
            ctx.checkEquals(Set.of("sweep-messages-upload 2.txt", "sweep-messages-upload.txt"),
                    new HashSet<>(listed.json().getList("name")), "the files the folder lists", listed);
        } finally {
            paths.forEach(path -> deleteRepoFileAndReadGone(ctx, as.name(), path));
        }
    }

    private static void deleteRepoFileAndReadGone(SweepContext ctx, String identity, String path) {
        SweepResponse deleted = ctx.ok(identity, SweepRequest.delete("/rest/repo/file/{*file}").var("file", path));
        ctx.check(deleted.text().startsWith("File deleted"), "the answer to deleting " + path, deleted);
        SweepResponse read = ctx.send("anonymous", repoFile(path).note("read after the delete"));
        ctx.check(read.status() == 404 && read.text().equals("File not found"),
                "the file " + path + " reads 404 \"File not found\" after its delete", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Shared checks
    // ------------------------------------------------------------------------------------------- //

    /** Checks the answer does not hold the row's name as a value of its own */
    private static void absent(SweepContext ctx, SweepResponse response, String row, String expectation) {
        ctx.check(SweepEngine.rowMissing(response, null, row) != null, expectation, response);
    }
}
