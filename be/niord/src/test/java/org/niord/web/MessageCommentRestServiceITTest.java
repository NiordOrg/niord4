/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.web;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.message.Comment;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for {@link MessageCommentRestService}: the HTTP contract and authorization of the
 * comment endpoints under {@code /rest/messages/message/{messageId}/...}.
 *
 * <p><b>Authorization model under test.</b> The class is {@code @Path("/messages")} and declared
 * {@code @PermitAll}, but every comment handler ({@code getComments}, {@code createComment},
 * {@code updateComment}, {@code acknowledgeComment}) carries {@code @RolesAllowed(USER)}. The
 * method-level {@code @RolesAllowed} therefore governs access: a request with no token is rejected
 * by the container with {@code 401}, while an authenticated identity that lacks the {@code user}
 * role is rejected with {@code 403}. Past the role, every handler requires the caller's current domain
 * to own the message's series ({@code 403} otherwise); a comment created or updated without a text, or with
 * a text of blanks only, is refused with {@code 400} (MSG8); a comment's e-mail recipients are shown to its
 * author and to an admin only, a comment is mailed to at most
 * {@link MessageCommentRestService#MAX_COMMENT_MAIL_RECIPIENTS} mailboxes (a group address is refused), and
 * its mail carries the formatting but not the active content of its HTML.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). USER is the lowest tier, so all four seeded identities
 * (see {@link KeycloakTestTokens}) can in principle pass — but the {@code user} role only ever comes
 * from the {@code NiordDomain} augmentor. The seeded {@code testuser} carries no
 * {@code niord-client-master} role at all, so its bare token (no {@code NiordDomain} header) is
 * authenticated but role-less and is rejected with {@code 403}; only when the
 * {@code NiordDomain: niord-nw} header is present (via {@link #as(String)}) does the augmentor
 * grant the {@code user} role and the request succeed. A missing token still yields the container's
 * {@code 401} regardless of the header.
 *
 * <p><b>Resolution oracle.</b> {@code getComments} resolves the message through
 * {@code messageService.resolveMessage}; an unknown id resolves to {@code null} and the handler
 * returns an empty list (HTTP {@code 200}), it does not 404. {@code createComment} on an unknown id
 * throws an {@code IllegalArgumentException}, which {@code RestExceptionStatusAdvice} maps to HTTP
 * {@code 400} (the ERR1 fix — previously an unmapped {@code 500}). {@code updateComment} enforces the
 * REST PUT pattern: a path {@code commentId} that differs from the body {@code id} throws
 * {@code NiordWebException(400)} before any persistence happens.
 *
 * <p><b>Committed-message fixture.</b> {@code messageService.createMessage} is {@code @Transactional};
 * called from this non-transactional test it COMMITS, so the message and any comments created against
 * it through the REST endpoints (which run in the server's own committed transaction) are real rows
 * that {@code @Transactional} cannot roll back. The fixture is therefore torn down explicitly in
 * {@link #cleanup()} in a fresh transaction: comments first, then message history, then the message.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageCommentRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    @Autowired
    MessageService messageService;

    @Autowired
    NiordApp app;

    @PersistenceContext
    EntityManager em;

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    /** Primary key + uid of the committed fixture message created per test. */
    private Integer msgId;
    private String msgUid;

    /**
     * Part of every e-mail address a test puts on a comment, and unique to the test: its scheduled mails are
     * counted, and removed in {@link #cleanup()}, by it. A mail scheduled to a group address carries the whole
     * group as its address, so the token finds that one too.
     */
    private final String mailToken = "it-comment-" + System.nanoTime() + "-";

    /** How many e-mail addresses this test has made */
    private int mailCount;

    /** Returns a new e-mail address, unique to this test. */
    private String mailAddress() {
        return mailToken + (++mailCount) + "@example.com";
    }

    /** Returns the given number of new e-mail addresses. */
    private List<String> mailAddresses(int count) {
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            addresses.add(mailAddress());
        }
        return addresses;
    }

    /** Returns an RFC 822 group address that holds the given number of new e-mail addresses. */
    private String groupAddress(int count) {
        return "IT group: " + String.join(", ", mailAddresses(count)) + ";";
    }

    private String commentsUrl() {
        return "/rest/messages/message/" + msgUid + "/comments";
    }

    private String commentUrl() {
        return "/rest/messages/message/" + msgUid + "/comment";
    }

    @BeforeEach
    void setUp() throws Exception {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.LOCAL_WARNING.getMainType());
        msg.setMessageSeries(series);
        msg.setType(Type.LOCAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("IT-COMMENT");
        msg.addPart(part);
        msg.createDesc("en").setTitle("IT comment message " + System.nanoTime());

        // @Transactional createMessage COMMITS when called from this non-transactional test.
        Message created = messageService.createMessage(msg);
        msgId = created.getId();
        msgUid = created.getUid();
    }

    @AfterEach
    void cleanup() {
        // A comment with e-mail addresses schedules one mail per address, in the request's own transaction
        if (mailCount > 0) {
            try {
                Tx.requiringNew().run(() -> em
                        .createQuery("select r.mail from ScheduledMailRecipient r where r.address like :pattern",
                                ScheduledMail.class)
                        .setParameter("pattern", "%" + mailToken + "%")
                        .getResultList()
                        .forEach(em::remove));
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        try {
            Tx.requiringNew().run(() -> {
                // Removed one by one, so their e-mail address rows go with them
                em.createQuery("select c from Comment c where c.message.id = :id", Comment.class)
                        .setParameter("id", msgId).getResultList()
                        .forEach(em::remove);
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", msgId).executeUpdate();
                Message m = em.find(Message.class, msgId);
                if (m != null) {
                    em.remove(m);
                }
            });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /message/{messageId}/comments — @RolesAllowed(USER).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getComments_rejectsAnonymousWith401() {
        given().when().get(commentsUrl()).then().statusCode(401);
    }

    @Test
    void getComments_forbidsAuthenticatedUserWithoutDomainHeaderWith403() {
        // No NiordDomain header: the augmentor never grants 'user', so the role-less token is 403.
        given().auth().oauth2(accessToken("user")).when().get(commentsUrl()).then().statusCode(403);
    }

    @Test
    void getComments_allowsUserAndReturnsEmptyListInitially() {
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(0));
    }

    @Test
    void getComments_unknownMessageReturns200EmptyList() {
        // resolveMessage(null) -> handler returns Collections.emptyList(), not a 404.
        as(accessToken("user")).when().get("/rest/messages/message/NO-SUCH-MSG/comments")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /message/{messageId}/comment — @RolesAllowed(USER).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createComment_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl()).then().statusCode(401);
    }

    @Test
    void createComment_allowsUserAndPersistsBodyAndId() {
        as(accessToken("user")).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl())
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("comment", equalTo("IT hello"))
                .body("id", notNullValue());
    }

    @Test
    void createComment_unknownMessageReturns400() {
        // ERR1 fix: the handler's IllegalArgumentException("Invalid message ID ...") is a client-input
        // error; RestExceptionStatusAdvice maps it to 400 Bad Request with an RFC 9457 ProblemDetail
        // body (previously it surfaced as an unmapped 500).
        as(accessToken("user")).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post("/rest/messages/message/NO-SUCH-MSG/comment")
                .then().statusCode(400)
                .body("detail", containsString("Invalid message ID"));
    }

    // ------------------------------------------------------------------------------------------- //
    // MSG8: a comment must have a text. Without one it says nothing, yet it waits in the message's
    // comment list as unacknowledged until someone acknowledges it.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEmptyOrBlankCommentIsRefusedAndNothingIsStored() {
        // FAILS on the pre-fix code (each body answered 200 and stored a comment with no or blank text).
        for (String body : List.of("{}", "{\"comment\":null}", "{\"comment\":\"\"}", "{\"comment\":\"   \"}")) {
            as(accessToken("user")).contentType(ContentType.JSON).body(body)
                    .when().post(commentUrl())
                    .then().statusCode(400)
                    .body("detail", containsString("A comment must have a text"));
        }

        // Refused before the mail: a blank comment with an address is neither stored nor mailed
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("comment", " ", "emailAddresses", List.of(mailAddress())))
                .when().post(commentUrl())
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(0));
        assertScheduledMails(0);

        // A comment with a text is still stored
        as(accessToken("user")).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl())
                .then().statusCode(200)
                .body("comment", equalTo("IT hello"));
    }

    @Test
    void anUpdateWithoutTextIsRefused() {
        int commentId = createCommentAs(accessToken("user"));

        // FAILS on the pre-fix code (each body answered 200 and cleared the comment's text).
        for (String body : List.of("{\"id\":" + commentId + "}",
                "{\"id\":" + commentId + ",\"comment\":\"\"}",
                "{\"id\":" + commentId + ",\"comment\":\" \\n \"}")) {
            as(accessToken("user")).contentType(ContentType.JSON).body(body)
                    .when().put(commentUrl() + "/" + commentId)
                    .then().statusCode(400)
                    .body("detail", containsString("A comment must have a text"));
        }

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"));

        // An update with a text still goes through
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT updated"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(200)
                .body("comment", equalTo("IT updated"));
    }

    @Test
    void aBlankCommentOfAnotherDomainIsStill403() {
        // The domain gate comes before the text check, so a caller of another domain learns nothing more
        given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nm")
                .contentType(ContentType.JSON).body("{}")
                .when().post(commentUrl())
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /message/{messageId}/comment/{commentId} — @RolesAllowed(USER). Mismatched path/body id -> 400.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateComment_withMismatchedIdReturns400() {
        // Create a comment to obtain a real id, then send a body whose id differs from the path id.
        int commentId = as(accessToken("user")).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl())
                .then().statusCode(200)
                .extract().path("id");

        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId + 1, "comment", "IT updated"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(400);

        // The mismatched update must not have altered the original comment.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Full lifecycle: create -> get -> update -> acknowledge, asserting persisted state at each step.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void commentLifecycle_createGetUpdateAck() {
        // CREATE
        Response created = as(accessToken("user")).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl())
                .then().statusCode(200)
                .body("comment", equalTo("IT hello"))
                .body("id", notNullValue())
                .extract().response();
        int commentId = created.path("id");

        // GET — the comment is now persisted and visible.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"));

        // UPDATE — consistent path/body id, comment text changes.
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT updated"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(200)
                .body("id", equalTo(commentId))
                .body("comment", equalTo("IT updated"));

        // GET — the update is persisted.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT updated"));

        // ACKNOWLEDGE
        as(accessToken("user")).when().put(commentUrl() + "/" + commentId + "/ack")
                .then().statusCode(200)
                .body("id", equalTo(commentId))
                .body("acknowledgedBy", notNullValue());

        // GET — the acknowledgement is persisted on the comment.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].acknowledgedBy", notNullValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // SECAUD-5: comment update is author-or-admin; acknowledge is domain-scoped (not author-restricted).
    //
    // Without these checks any authenticated USER who can resolve a message UID could overwrite or
    // acknowledge another user's comment, cross-user and cross-domain. updateComment is author-or-admin,
    // the same rule the editor applies; acknowledgeComment is a legitimate action on another
    // user's comment but only within the message's own domain (mirrors checkMessageEditingAccess).
    // ------------------------------------------------------------------------------------------- //

    /** Creates a comment authored by the identity behind the given token and returns its id. */
    private int createCommentAs(String token) {
        return as(token).contentType(ContentType.JSON).body(Map.of("comment", "IT hello"))
                .when().post(commentUrl())
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void updateComment_byDifferentNonAdminUserIsForbiddenAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));   // author = testuser

        // testeditor is a different user and not an admin, so it must not overwrite testuser's comment.
        // FAILS on the pre-fix code (the update succeeds with 200 and "HACKED" is persisted).
        as(accessToken("editor")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "HACKED"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(403);

        // The comment text is unchanged.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"));
    }

    @Test
    void updateComment_byAuthorSucceeds() {
        int commentId = createCommentAs(accessToken("user"));   // author = testuser

        // The author may edit their own comment (legitimate flow must not regress).
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT updated"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(200)
                .body("comment", equalTo("IT updated"));
    }

    @Test
    void updateComment_byAdminSucceeds() {
        int commentId = createCommentAs(accessToken("user"));   // author = testuser

        // testadmin is a different user but holds the admin role, so it may edit any comment:
        // the admin branch of the author-or-admin rule.
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "admin edit"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(200)
                .body("comment", equalTo("admin edit"));
    }

    @Test
    void acknowledgeComment_byDifferentUserInSameDomainSucceeds() {
        int commentId = createCommentAs(accessToken("user"));   // author = testuser

        // A different user in the message's own domain (niord-nw owns the dma-nw series) may
        // acknowledge another user's comment — acknowledge is intentionally NOT author-restricted.
        as(accessToken("editor")).when().put(commentUrl() + "/" + commentId + "/ack")
                .then().statusCode(200)
                .body("acknowledgedBy", notNullValue());
    }

    @Test
    void acknowledgeComment_fromWrongDomainIsForbiddenAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));

        // niord-nm does not own the dma-nw series, so acknowledging from that domain is rejected.
        // FAILS on the pre-fix code (the acknowledge succeeds cross-domain with 200).
        given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nm")
                .when().put(commentUrl() + "/" + commentId + "/ack")
                .then().statusCode(403);

        // The comment is still unacknowledged.
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].acknowledgedBy", nullValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // F-X2 / N-07: reading, writing and mailing comments is for the message's own domain.
    //
    // Comments are internal notes with their authors' names and recipients, and a comment with e-mail
    // addresses mails the full message to them. The "other domain" caller is the sysadmin with the
    // niord-nm header: the user and admin roles there, but not the dma-nw series.
    // ------------------------------------------------------------------------------------------- //

    /** Creates a comment with the given body and e-mail addresses as the identity behind the token. */
    private int createCommentAs(String token, String body, List<String> emailAddresses) {
        return as(token).contentType(ContentType.JSON)
                .body(Map.of("comment", body, "emailAddresses", emailAddresses))
                .when().post(commentUrl())
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void getComments_fromWrongDomainIsForbidden() {
        createCommentAs(accessToken("user"));

        // FAILS on the pre-fix code (any USER of any domain read the comments: 200).
        given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nm")
                .when().get(commentsUrl())
                .then().statusCode(403);
    }

    @Test
    void getComments_hidesEmailAddressesOfOtherUsersComments() {
        String address = mailAddress();
        createCommentAs(accessToken("user"), "IT hello", List.of(address));   // author = testuser

        // testeditor reads the comment but not its recipients: it may not edit the comment, and the edit
        // form is the only place the recipients appear.
        // FAILS on the pre-fix code (every reader got the address list).
        as(accessToken("editor")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"))
                .body("[0].emailAddresses", nullValue());

        // The author and an admin - the two who may edit it - still get them
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].emailAddresses", equalTo(List.of(address)));
        as(accessToken("admin")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].emailAddresses", equalTo(List.of(address)));
    }

    @Test
    void createComment_fromWrongDomainIsForbiddenAndPersistsNothing() {
        // FAILS on the pre-fix code (the comment was saved - and mailed - cross-domain: 200).
        given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nm")
                .contentType(ContentType.JSON)
                .body(Map.of("comment", "IT cross-domain", "emailAddresses", List.of(mailAddress())))
                .when().post(commentUrl())
                .then().statusCode(403);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(0));
        assertScheduledMails(0);
    }

    @Test
    void updateComment_fromWrongDomainIsForbiddenAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));

        // The sysadmin is an admin in niord-nm too; the admin branch of author-or-admin must not
        // reach a message of another domain.
        // FAILS on the pre-fix code (the admin role alone let the update through: 200).
        given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nm")
                .contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "HACKED"))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(403);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].comment", equalTo("IT hello"));
    }

    @Test
    void createComment_withTooManyRecipientsIsRejectedAndPersistsNothing() {
        List<String> addresses = mailAddresses(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS + 1);

        // FAILS on the pre-fix code (the comment was saved and one mail scheduled per address: 200).
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("comment", "IT too many", "emailAddresses", addresses))
                .when().post(commentUrl())
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(0));
        assertScheduledMails(0);
    }

    @Test
    void updateComment_withTooManyRecipientsIsRejectedAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));
        List<String> addresses = mailAddresses(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS + 1);

        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT too many", "emailAddresses", addresses))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].comment", equalTo("IT hello"));
        assertScheduledMails(0);
    }

    // ------------------------------------------------------------------------------------------- //
    // External review (N-07): the cap counts mailboxes, not strings.
    //
    // An RFC 822 group address ("name: a@x, b@y;") is one string that passes InternetAddress.validate(),
    // and the mail server sends to every mailbox in it. A group is refused whatever its size, before the
    // comment is saved or any mail is scheduled; plain addresses up to the cap are mailed as before.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createComment_withAGroupOfMoreMailboxesThanTheCapIsRejectedAndPersistsNothing() {
        String group = groupAddress(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS + 1);

        // FAILS on the pre-fix code (one string passed the cap: the comment was saved, and its mail scheduled
        // to the group: 200).
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("comment", "IT group", "emailAddresses", List.of(group)))
                .when().post(commentUrl())
                .then().statusCode(400)
                .body("detail", containsString("group"));

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(0));
        assertScheduledMails(0);
    }

    @Test
    void createComment_withASmallGroupIsRejectedAndPersistsNothing() {
        List<String> addresses = List.of(mailAddress(), groupAddress(3));

        // FAILS on the pre-fix code (200; the comment was saved and mailed to the address and the group).
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("comment", "IT group", "emailAddresses", addresses))
                .when().post(commentUrl())
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("size()", is(0));
        assertScheduledMails(0);
    }

    @Test
    void createComment_withTheCapOfPlainAddressesIsMailedToEach() {
        List<String> addresses = mailAddresses(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS);

        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("comment", "IT at the cap", "emailAddresses", addresses))
                .when().post(commentUrl())
                .then().statusCode(200)
                .body("emailAddresses", equalTo(addresses));

        assertScheduledMails(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS);
    }

    @Test
    void updateComment_withAGroupOfMoreMailboxesThanTheCapIsRejectedAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));
        String group = groupAddress(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS + 1);

        // FAILS on the pre-fix code (200; the update was saved and mailed to the group).
        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT group", "emailAddresses", List.of(group)))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].comment", equalTo("IT hello"))
                .body("[0].emailAddresses", nullValue());
        assertScheduledMails(0);
    }

    @Test
    void updateComment_withASmallGroupIsRejectedAndChangesNothing() {
        int commentId = createCommentAs(accessToken("user"));

        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT group", "emailAddresses", List.of(groupAddress(3))))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(400);

        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].comment", equalTo("IT hello"))
                .body("[0].emailAddresses", nullValue());
        assertScheduledMails(0);
    }

    @Test
    void updateComment_withTheCapOfPlainAddressesIsMailedToEach() {
        int commentId = createCommentAs(accessToken("user"));
        List<String> addresses = mailAddresses(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS);

        as(accessToken("user")).contentType(ContentType.JSON)
                .body(Map.of("id", commentId, "comment", "IT at the cap", "emailAddresses", addresses))
                .when().put(commentUrl() + "/" + commentId)
                .then().statusCode(200)
                .body("emailAddresses", equalTo(addresses));

        assertScheduledMails(MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS);
    }

    @Test
    void commentMail_keepsTheFormattingButNotTheActiveContent() throws Exception {
        String address = mailAddress();
        String body = "<p>IT <strong>bold</strong> and <a href=\"https://example.com/x\">a link</a></p>"
                + "<script>alert('it-script')</script>"
                + "<p onclick=\"alert('it-onclick')\">clicked</p>"
                + "<img src=\"https://example.com/it.png\" onerror=\"alert('it-onerror')\">";
        createCommentAs(accessToken("user"), body, List.of(address));

        // The stored comment is what the author wrote; only the mail body is cleaned
        as(accessToken("user")).when().get(commentsUrl())
                .then().statusCode(200)
                .body("[0].comment", equalTo(body));

        String html = scheduledMailHtml(address);

        // FAILS on the pre-fix code (the template printed the comment unescaped, script and handlers included).
        assertThat(html, containsString("<strong>bold</strong>"));
        assertThat(html, containsString("href=\"https://example.com/x\""));
        assertThat(html, containsString("clicked"));
        assertThat(html, not(containsString("it-script")));
        assertThat(html, not(containsString("it-onclick")));
        assertThat(html, not(containsString("it-onerror")));
        assertThat(html, not(containsString("https://example.com/it.png")));
    }

    /**
     * The comment keeps the strict allow-list: the message-HTML classes and the application's own images, which
     * the message in the same mail keeps, are dropped from the text of the comment.
     */
    @Test
    void commentMail_dropsTheClassesAndImagesOfTheComment() throws Exception {
        String address = mailAddress();
        String body = "<table class=\"positions\"><tbody><tr><td class=\"pos-col\">it-comment-cell</td></tr></tbody>"
                + "</table><p><img src=\"/rest/repo/file/temp/it-comment/it-own.png\" alt=\"it-own\"></p>";
        createCommentAs(accessToken("user"), body, List.of(address));

        String html = scheduledMailHtml(address);

        assertThat(html, containsString("<table><tbody><tr><td>it-comment-cell</td></tr></tbody></table>"));
        assertThat(html, not(containsString("it-own")));
    }

    @Test
    void commentMail_keepsARootRelativeLinkAsAnAbsoluteOne() throws Exception {
        String address = mailAddress();
        String body = "<p>IT see <a href=\"/rest/repo/file/temp/it-comment/notice.pdf\">the notice</a></p>";
        createCommentAs(accessToken("user"), body, List.of(address));

        // A repository file is linked root-relative. The mail is read elsewhere, so the link is made absolute
        // against the application's base URI. FAILS on the pre-fix code (with no base URI the link could not be
        // resolved, and the safelist dropped the href).
        assertThat(scheduledMailHtml(address), containsString("href=\""
                + StringUtils.stripEnd(app.getBaseUri(), "/") + "/rest/repo/file/temp/it-comment/notice.pdf\""));
    }

    /** Returns the HTML of the one mail scheduled to the given address. */
    private String scheduledMailHtml(String address) {
        return Tx.requiringNew().call(() -> em
                .createQuery("select r.mail from ScheduledMailRecipient r where r.address = :address",
                        ScheduledMail.class)
                .setParameter("address", address)
                .getSingleResult()
                .getHtmlContents());
    }

    /** Asserts how many mails are scheduled to the addresses this test used, group addresses included. */
    private void assertScheduledMails(long expected) {
        long count = Tx.requiringNew().call(() -> em
                .createQuery("select count(r) from ScheduledMailRecipient r where r.address like :pattern",
                        Long.class)
                .setParameter("pattern", "%" + mailToken + "%")
                .getSingleResult());
        assertThat(count, is(expected));
    }
}
