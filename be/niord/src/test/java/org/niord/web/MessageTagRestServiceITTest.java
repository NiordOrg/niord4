/*
 * Copyright 2016 Danish Maritime Authority.
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
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageTagRestService}: the HTTP contract of the {@code /rest/tags}
 * endpoints, including the authorization matrix exercised against a real Keycloak token (see
 * {@link KeycloakTestTokens}) and the tag-visibility rules enforced by
 * {@code MessageTagService.validateAccess}.
 *
 * <p>The read endpoints ({@code /search}, {@code /message/{uid}}, {@code /tag/{ids}}) are
 * {@code @PermitAll}; the create/update/delete/temp-tag/message-membership endpoints require the
 * {@code user} role; {@code /unlock-tag/{id}} requires {@code admin}. The bare testuser token carries
 * no base role (its {@code user} is on {@code niord-nw}'s client), so the USER endpoints reject it (403)
 * until a {@code NiordDomain} header lets the augmentor grant that domain's roles; the sysadmin token's
 * base roles hold {@code user} with or without the header. The ADMIN-protected {@code /unlock-tag} passes the role check
 * with the bare token, but a PUBLIC tag that records a domain is unlocked from that domain only, and an
 * unknown or invisible tag answers 404.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll
 * back, so a tag created by a POST is committed. Every created tag uses a unique {@code IT-REST-Tag-}
 * name (never present in seed data) and its assigned {@code tagId} is tracked and removed in
 * {@link #cleanup()} straight from the database, so the shared seed database stays pristine and tests
 * stay independent.
 *
 * <p>The key authorization assertion is the PRIVATE-tag visibility test: a PRIVATE tag created by
 * the sysadmin user (resolved via token + {@code NiordDomain}) is returned to its owner but filtered
 * out for an anonymous reader, even though the read endpoint itself is {@code @PermitAll}. A TEMP tag
 * is its creator's in the same way, and a DOMAIN tag is listed to a user of its domain only - never to
 * a caller that merely names the domain in the header. A locked tag is changed by an admin only, except a
 * PRIVATE or TEMP tag, which stays its owner's (TAG1), and a PUBLIC tag that records a domain from that
 * domain only. A tag is created and updated with a type and a name, or the request answers 400 (TAG2). A shared
 * tag's type is changed by an admin only, a PRIVATE or TEMP tag's by its owner, and no type change may leave a tag
 * with nobody to manage it (TAG4).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageTagRestServiceITTest extends ITTest {

    /** The domain whose USER role chain the augmentor grants for the sysadmin token. */
    private static final String NW_DOMAIN = "niord-nw";

    /** A domain that does NOT own the {@code dma-nw} message series of the fixture messages. */
    private static final String NM_DOMAIN = "niord-nm";

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    /** Tag IDs created via REST, removed in {@link #cleanup()}. */
    private final List<String> createdTagIds = new ArrayList<>();

    /** Primary keys of committed fixture messages, removed in {@link #cleanup()} after the tags. */
    private final List<Integer> createdMessageIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Straight from the database: some tags are out of reach of the sysadmin's REST DELETE - a TEMP tag
        // another user created, a PUBLIC tag of another domain.
        for (String tagId : createdTagIds) {
            try {
                Tx.requiringNew().run(() -> em
                        .createQuery("select t from MessageTag t where t.tagId = :tagId", MessageTag.class)
                        .setParameter("tagId", tagId)
                        .getResultList()
                        .forEach(em::remove));
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdTagIds.clear();

        // The tags (and their join rows) are gone, so the fixture messages can be removed.
        for (Integer msgId : createdMessageIds) {
            try {
                Tx.requiringNew().run(() -> {
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
        createdMessageIds.clear();
    }

    /**
     * Creates a tag through {@code POST /rest/tags/tag/} as the sysadmin user (token + NiordDomain
     * grants the USER role chain), asserts a 200 and an assigned non-blank tagId, tracks the id for
     * cleanup and returns it.
     */
    private String createTagAsUser(String name, String type, boolean locked) {
        String tagId = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .body(Map.of("name", name, "type", type, "locked", locked))
                .when().post("/rest/tags/tag/")
                .then().statusCode(200)
                .body("name", is(name))
                .body("type", is(type))
                .body("tagId", notNullValue())
                .extract().path("tagId");
        assertNotNull(tagId, "created tag must carry an assigned tagId");
        assertTrue(!tagId.isBlank(), "created tag must carry a non-blank tagId");
        createdTagIds.add(tagId);
        return tagId;
    }

    /** Returns a name token guaranteed not to collide with seed data or with other tests. */
    private static String uniqueName(String suffix) {
        return "IT-REST-Tag-" + suffix + "-" + System.nanoTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTagWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", uniqueName("unauth-create"), "type", "PUBLIC", "locked", false))
                .when().post("/rest/tags/tag/")
                .then().statusCode(401);
    }

    @Test
    void deleteTagWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/tags/tag/IT-REST-Tag-no-such-id")
                .then().statusCode(401);
    }

    @Test
    void addMessagesWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(List.of())
                .when().put("/rest/tags/tag/IT-REST-Tag-no-such-id/add-messages")
                .then().statusCode(401);
    }

    @Test
    void removeMessagesWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(List.of())
                .when().put("/rest/tags/tag/IT-REST-Tag-no-such-id/remove-messages")
                .then().statusCode(401);
    }

    @Test
    void clearTagWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/tags/tag/IT-REST-Tag-no-such-id/messages")
                .then().statusCode(401);
    }

    @Test
    void createTempTagWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(List.of())
                .when().post("/rest/tags/temp-tag/")
                .then().statusCode(401);
    }

    @Test
    void updateTagWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("tagId", "IT-REST-Tag-no-such-id", "name", "x", "type", "PUBLIC", "locked", false))
                .when().put("/rest/tags/tag/IT-REST-Tag-no-such-id")
                .then().statusCode(401);
    }

    @Test
    void unlockTagWithoutTokenIsUnauthorized() {
        // /unlock-tag is the only ADMIN-protected endpoint; an anonymous request is still rejected.
        given()
                .when().put("/rest/tags/unlock-tag/IT-REST-Tag-no-such-id")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — authenticated-but-insufficient-role (bare testuser token, no 'user')
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTagWithBareTokenButNoDomainIsForbidden() {
        // testuser holds 'user' only on niord-nw's client, so its bare token carries no role. createTag is
        // @RolesAllowed("user"), so without a NiordDomain header to trigger the role-chain augmentor
        // the authenticated request is rejected as forbidden.
        given().auth().oauth2(accessToken("user"))
                .contentType(ContentType.JSON)
                .body(Map.of("name", uniqueName("bare-forbidden"), "type", "PUBLIC", "locked", false))
                .when().post("/rest/tags/tag/")
                .then().statusCode(403);
    }

    @Test
    void createTempTagWithBareTokenButNoDomainIsForbidden() {
        given().auth().oauth2(accessToken("user"))
                .contentType(ContentType.JSON)
                .body(List.of())
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // Create / read-back / persistence
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createPublicTagReturnsVoWithAssignedId() {
        String name = uniqueName("public-create");
        String tagId = createTagAsUser(name, "PUBLIC", false);

        // The freshly created PUBLIC tag is immediately readable via the @PermitAll get-by-id
        // endpoint, and visible to an anonymous (no-token) reader.
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].tagId", is(tagId))
                .body("[0].name", is(name))
                .body("[0].type", is("PUBLIC"));
    }

    @Test
    void searchFindsCreatedPublicTagAnonymously() {
        String name = uniqueName("public-search");
        createTagAsUser(name, "PUBLIC", false);

        // The search endpoint is @PermitAll; a PUBLIC tag is visible to an anonymous reader and the
        // unique name token guarantees the created tag is the only match.
        given()
                .queryParam("name", name)
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("name", hasItem(name))
                .body("findAll { it.name == '" + name + "' }.type", hasItem("PUBLIC"));
    }

    @Test
    void getUnknownTagReturnsEmptyArray() {
        // The get-by-id endpoint is @PermitAll and reports an empty list for an unknown id.
        given()
                .when().get("/rest/tags/tag/IT-REST-Tag-definitely-missing-" + System.nanoTime())
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void updateTagChangesNameAndLockedAndPersists() {
        String name = uniqueName("update");
        String tagId = createTagAsUser(name, "PUBLIC", false);

        String newName = name + "-renamed";
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .body(Map.of("tagId", tagId, "name", newName, "type", "PUBLIC", "locked", true))
                .when().put("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("tagId", is(tagId))
                .body("name", is(newName))
                .body("locked", is(true));

        // Side-effect: the new name and locked flag are persisted and read back.
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is(newName))
                .body("[0].locked", is(true));
    }

    /** The number of tags whose name is the given one, or blank when the name is null. */
    private long tagsNamed(String name) {
        String jpql = name == null
                ? "select count(t) from MessageTag t where trim(t.name) = ''"
                : "select count(t) from MessageTag t where t.name = :name";
        return Tx.requiringNew().call(() -> {
            var query = em.createQuery(jpql, Long.class);
            if (name != null) {
                query.setParameter("name", name);
            }
            return query.getSingleResult();
        });
    }

    @Test
    void createTagWithoutATypeOrANameAnswers400AndStoresNothing() {
        // WEB17's site in this file: the type is a NOT NULL column, so a tag without one failed at the flush
        // with a 500, and a blank name was stored as it was. FAILS on the pre-fix code (500, then 200 twice).
        String name = uniqueName("no-type");
        long blankBefore = tagsNamed(null);
        Map<String, Object> noType = new java.util.HashMap<>();
        noType.put("name", name);
        noType.put("locked", false);
        List<Integer> statuses = new ArrayList<>();
        for (Map<String, ?> body : List.<Map<String, ?>>of(noType,
                Map.of("type", "PRIVATE", "locked", false),
                Map.of("name", "", "type", "PRIVATE", "locked", false),
                Map.of("name", "   ", "type", "PUBLIC", "locked", false))) {
            io.restassured.response.Response response = as("sysadmin", NW_DOMAIN).contentType(ContentType.JSON)
                    .body(body)
                    .when().post("/rest/tags/tag/");
            if (response.statusCode() == 200) {
                createdTagIds.add(response.path("tagId"));
            }
            statuses.add(response.statusCode());
        }
        assertEquals(List.of(400, 400, 400, 400), statuses, "no type, no name, an empty name, a blank name");
        assertEquals(0L, tagsNamed(name), "no tag without a type");
        assertEquals(blankBefore, tagsNamed(null), "no tag with a blank name");

        // The right path: a type and a name
        createTagAsUser(uniqueName("typed"), "PRIVATE", false);
    }

    @Test
    void updateTagWithMismatchedIdReturns400() {
        String name = uniqueName("mismatch");
        String tagId = createTagAsUser(name, "PUBLIC", false);

        // The path tagId and body tagId differ -> the endpoint throws NiordWebException(400).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .body(Map.of("tagId", tagId + "-other", "name", name, "type", "PUBLIC", "locked", false))
                .when().put("/rest/tags/tag/" + tagId)
                .then().statusCode(400);
    }

    @Test
    void deleteTagReturnsTrueAndRemovesIt() {
        String name = uniqueName("delete");
        String tagId = createTagAsUser(name, "PUBLIC", false);
        // Remove from tracking; this test deletes the tag explicitly.
        createdTagIds.remove(tagId);

        String body = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().delete("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body, "deleting an existing tag must report true");

        // Side-effect: the deleted tag is no longer present.
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void deleteUnknownTagReturnsFalse() {
        String body = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().delete("/rest/tags/tag/IT-REST-Tag-never-existed-" + System.nanoTime())
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("false", body, "deleting a non-existing tag must report false");
    }

    @Test
    void createTempTagReturnsTempTypeWithZeroMessages() {
        // POST /temp-tag/ with an empty message-uid array creates a TEMP tag with no messages. The
        // server assigns the id and type; we track the returned id for cleanup.
        String tagId = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .body(List.of())
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(200)
                .body("type", is("TEMP"))
                .body("messageCount", is(0))
                .body("tagId", notNullValue())
                .extract().path("tagId");
        assertNotNull(tagId, "temp tag must carry an assigned tagId");
        createdTagIds.add(tagId);
    }

    // ------------------------------------------------------------------------------------------- //
    // Security / visibility — PRIVATE tag is owner-only even on a @PermitAll read endpoint
    // ------------------------------------------------------------------------------------------- //

    @Test
    void privateTagIsVisibleToOwnerButFilteredForAnonymousReader() {
        String name = uniqueName("private");
        // Created as the sysadmin user (token + NiordDomain) -> owned by sysadmin.
        String tagId = createTagAsUser(name, "PRIVATE", false);

        // The owner (token + NiordDomain resolves the same user) sees the PRIVATE tag.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].tagId", is(tagId))
                .body("[0].type", is("PRIVATE"));

        // The endpoint is @PermitAll so an anonymous reader still gets a 200, but validateAccess
        // filters the PRIVATE tag out because the anonymous request has no owning user -> empty list.
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void privateTagIsHiddenFromAnonymousSearch() {
        String name = uniqueName("private-search");
        createTagAsUser(name, "PRIVATE", false);

        // A PRIVATE tag must not surface in an anonymous search, even by its exact unique name.
        given()
                .queryParam("name", name)
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("name", not(hasItem(name)));
    }

    // ------------------------------------------------------------------------------------------- //
    // unlock-tag — ADMIN endpoint; a PUBLIC tag of a domain is unlocked from that domain only
    // ------------------------------------------------------------------------------------------- //

    @Test
    void unlockTagClearsLockedFlagAndPersists() {
        String name = uniqueName("unlock");
        String tagId = createTagAsUser(name, "PUBLIC", true);

        // unlock-tag is @RolesAllowed("admin"); the sysadmin holds 'admin', and the tag records the nw
        // domain, so the request comes from there. The response reports the now-unlocked tag.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().put("/rest/tags/unlock-tag/" + tagId)
                .then().statusCode(200)
                .body("tagId", is(tagId))
                .body("locked", is(false));

        // Side-effect: the persisted tag is unlocked.
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].locked", is(false));
    }

    @Test
    void unlockTagFromAnotherDomainIsForbiddenAndChangesNothing() {
        String name = uniqueName("unlock-other-domain");
        String tagId = createTagAsUser(name, "PUBLIC", true);   // a locked PUBLIC tag of niord-nw

        // The sysadmin holds the admin role in niord-nm too, so only the tag's domain can refuse this,
        // as it refuses every other change of the tag from there. FAILS on the pre-fix code (200, unlocked).
        as("sysadmin", NM_DOMAIN)
                .when().put("/rest/tags/unlock-tag/" + tagId)
                .then().statusCode(403);
        // Without a domain the request names none, which is not the tag's either
        given().auth().oauth2(accessToken())
                .when().put("/rest/tags/unlock-tag/" + tagId)
                .then().statusCode(403);
        assertTag(tagId, name, true, 0);
    }

    @Test
    void unlockUnknownTagAnswers404() {
        // FAILS on the pre-fix code (a NullPointerException: 500).
        as("sysadmin", NW_DOMAIN)
                .when().put("/rest/tags/unlock-tag/IT-REST-Tag-never-existed-" + System.nanoTime())
                .then().statusCode(404);
    }

    @Test
    void unlockTagTheCallerCannotSeeAnswers404AndChangesNothing() {
        // A locked PRIVATE tag of testeditor, which no other user can see
        String name = uniqueName("unlock-private");
        String tagId = as("editor", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("name", name, "type", "PRIVATE", "locked", true))
                .when().post("/rest/tags/tag/")
                .then().statusCode(200)
                .extract().path("tagId");
        createdTagIds.add(tagId);

        // The sysadmin cannot see it, so it answers like an unknown tag. FAILS on the pre-fix code (500).
        as("sysadmin", NW_DOMAIN)
                .when().put("/rest/tags/unlock-tag/" + tagId)
                .then().statusCode(404);

        as("editor", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].locked", is(true));
    }

    // ------------------------------------------------------------------------------------------- //
    // TAG-3: per-message access check when grouping messages into a tag.
    //
    // createTempTag, addMessageToTag and removeMessageFromTag take caller-supplied message UIDs.
    // Without the checkMessageViewingAccess gate any authenticated USER could attach arbitrary UIDs —
    // including other domains' unpublished drafts — to a tag (and e.g. leak a draft by adding it to a
    // PUBLIC tag, which grants everyone viewing access to it). The gate mirrors
    // MessageRestService.checkMessageViewingAccess: public status, own domain, or member of a public
    // tag. Fixture messages live in the dma-nw series, owned by niord-nw but not
    // niord-nm; the "attacker" is the same sysadmin token acting from the nm domain, so
    // PRIVATE tags still resolve for it and a 403 can only come from the per-message gate.
    // ------------------------------------------------------------------------------------------- //

    /**
     * Creates a committed message in the {@code dma-nw} series with the given status and returns its
     * UID. {@code messageService.createMessage} is {@code @Transactional}; called from this
     * non-transactional test it COMMITS, so the message is torn down explicitly in {@link #cleanup()}.
     */
    private String createCommittedNwMessage(Status status) throws Exception {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.LOCAL_WARNING.getMainType());
        msg.setMessageSeries(series);
        msg.setType(Type.LOCAL_WARNING);
        msg.setAutoTitle(false);
        msg.setStatus(status);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("IT-TAG");
        msg.addPart(part);
        msg.createDesc("en").setTitle("IT tag message " + System.nanoTime());
        Message created = messageService.createMessage(msg);
        createdMessageIds.add(created.getId());
        return created.getUid();
    }

    /** Like {@link #createTagAsUser} but resolving the USER role chain via the given domain. */
    private String createTagInDomain(String name, String type, String domain) {
        String tagId = given().auth().oauth2(accessToken())
                .header("NiordDomain", domain)
                .contentType(ContentType.JSON)
                .body(Map.of("name", name, "type", type, "locked", false))
                .when().post("/rest/tags/tag/")
                .then().statusCode(200)
                .extract().path("tagId");
        assertNotNull(tagId, "created tag must carry an assigned tagId");
        createdTagIds.add(tagId);
        return tagId;
    }

    /** Adds the given message UIDs to the tag via REST from the given domain. */
    private io.restassured.response.Response addMessages(String domain, String tagId, List<String> uids) {
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", domain)
                .contentType(ContentType.JSON)
                .body(uids)
                .when().put("/rest/tags/tag/" + tagId + "/add-messages");
    }

    /** Asserts the persisted message count of the tag, read back as the nw-domain user. */
    private void assertTagMessageCount(String tagId, int expected) {
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].messageCount", is(expected));
    }

    @Test
    void addMessagesWithOwnDomainDraftSucceedsAndPersists() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);
        String tagId = createTagAsUser(uniqueName("own-add"), "PRIVATE", false);

        // A draft of the caller's own domain may be grouped (legitimate flow must not regress).
        addMessages(NW_DOMAIN, tagId, List.of(draftUid))
                .then().statusCode(200)
                .body("messageCount", is(1));

        assertTagMessageCount(tagId, 1);
    }

    @Test
    void addMessagesWithCrossDomainDraftIsForbiddenAndChangesNothing() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);
        // The attacker's own tag, created from the nm domain — the gate is about the message, not the tag.
        String tagId = createTagInDomain(uniqueName("cross-add"), "PRIVATE", NM_DOMAIN);

        // niord-nm does not own the dma-nw series, so grouping its unpublished draft is rejected.
        // FAILS on the pre-fix code (the add succeeds with 200 and the foreign draft joins the tag).
        addMessages(NM_DOMAIN, tagId, List.of(draftUid))
                .then().statusCode(403);

        assertTagMessageCount(tagId, 0);
    }

    @Test
    void createTempTagWithOwnDomainDraftSucceeds() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);

        String tagId = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .body(List.of(draftUid))
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(200)
                .body("type", is("TEMP"))
                .body("messageCount", is(1))
                .extract().path("tagId");
        createdTagIds.add(tagId);
    }

    @Test
    void createTempTagWithCrossDomainDraftIsForbidden() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);

        // FAILS on the pre-fix code (a TEMP tag containing the foreign draft is created with 200).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NM_DOMAIN)
                .contentType(ContentType.JSON)
                .body(List.of(draftUid))
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(403);
    }

    @Test
    void removeMessagesWithCrossDomainDraftIsForbiddenAndChangesNothing() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);
        String tagId = createTagAsUser(uniqueName("cross-remove"), "PRIVATE", false);
        addMessages(NW_DOMAIN, tagId, List.of(draftUid))
                .then().statusCode(200);

        // Tampering with the membership of a foreign draft is rejected just like adding it.
        // FAILS on the pre-fix code (the remove succeeds with 200 and the draft leaves the tag).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NM_DOMAIN)
                .contentType(ContentType.JSON)
                .body(List.of(draftUid))
                .when().put("/rest/tags/tag/" + tagId + "/remove-messages")
                .then().statusCode(403);

        assertTagMessageCount(tagId, 1);
    }

    @Test
    void addMessagesWithPublishedCrossDomainMessageSucceeds() throws Exception {
        String publishedUid = createCommittedNwMessage(Status.PUBLISHED);
        String tagId = createTagInDomain(uniqueName("published-add"), "PRIVATE", NM_DOMAIN);

        // A published message is public; grouping it from another domain is a legitimate flow
        // (e.g. temp-tag selections over search results) and must not regress.
        addMessages(NM_DOMAIN, tagId, List.of(publishedUid))
                .then().statusCode(200)
                .body("messageCount", is(1));
    }

    @Test
    void addMessagesWithDraftInPublicTagSucceedsCrossDomain() throws Exception {
        String draftUid = createCommittedNwMessage(Status.DRAFT);

        // The draft's own domain places it in a PUBLIC tag, making it viewable by everyone...
        String publicTagId = createTagAsUser(uniqueName("public-member"), "PUBLIC", false);
        addMessages(NW_DOMAIN, publicTagId, List.of(draftUid))
                .then().statusCode(200);

        // ...so a user of another domain may group it as well (viewing-access rule 3).
        String otherDomainTagId = createTagInDomain(uniqueName("public-member-cross"), "PRIVATE", NM_DOMAIN);
        addMessages(NM_DOMAIN, otherDomainTagId, List.of(draftUid))
                .then().statusCode(200)
                .body("messageCount", is(1));
    }

    @Test
    void addMessagesWithUnknownUidIsIgnored() {
        String tagId = createTagAsUser(uniqueName("unknown-uid"), "PRIVATE", false);

        // An unknown UID resolves to no message: it is silently ignored, not rejected, preserving
        // the endpoint's pre-existing contract.
        addMessages(NW_DOMAIN, tagId, List.of("IT-REST-Tag-no-such-msg-" + System.nanoTime()))
                .then().statusCode(200)
                .body("messageCount", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // F-X4: a locked tag is changed by an admin only, and a PUBLIC tag from its own domain only.
    //
    // Publication tags are PUBLIC and locked; the lock is what keeps a released issue's message list
    // fixed. /unlock-tag is ADMIN-only, and the mutation endpoints check the persisted lock as well, so a
    // plain user can neither unlock a tag through PUT nor change it while it is locked. The plain user
    // is testuser with the nw header (the user role, nothing more) and the tag belongs to nw, so only the
    // lock can refuse it.
    // ------------------------------------------------------------------------------------------- //

    /** A request as the given identity tier with the given {@code NiordDomain} header. */
    private static RequestSpecification as(String role, String domain) {
        return given().auth().oauth2(accessToken(role)).header("NiordDomain", domain);
    }

    /** A locked PUBLIC tag of niord-nw holding one committed published nw message. */
    private record LockedTag(String tagId, String name, String messageUid) {}

    private LockedTag lockedPublicTagWithOneMessage() throws Exception {
        String name = uniqueName("locked");
        String tagId = createTagAsUser(name, "PUBLIC", true);
        String uid = createCommittedNwMessage(Status.PUBLISHED);
        // The sysadmin holds the admin role, which a locked tag still admits
        addMessages(NW_DOMAIN, tagId, List.of(uid))
                .then().statusCode(200);
        return new LockedTag(tagId, name, uid);
    }

    /** Asserts the tag as the sysadmin reads it back in the nw domain. */
    private void assertTag(String tagId, String name, boolean locked, int messageCount) {
        given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is(name))
                .body("[0].type", is("PUBLIC"))
                .body("[0].locked", is(locked))
                .body("[0].messageCount", is(messageCount));
    }

    @Test
    void plainUserCannotUnlockOrRenameALockedTag() throws Exception {
        LockedTag tag = lockedPublicTagWithOneMessage();

        // FAILS on the pre-fix code (the PUT copied locked=false off the body: 200, and the tag unlocked).
        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("tagId", tag.tagId(), "name", tag.name(), "type", "PUBLIC", "locked", false))
                .when().put("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);

        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("tagId", tag.tagId(), "name", tag.name() + "-renamed", "type", "PUBLIC", "locked", true))
                .when().put("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);
    }

    @Test
    void plainUserCannotChangeTheMessagesOfALockedTag() throws Exception {
        LockedTag tag = lockedPublicTagWithOneMessage();
        String otherUid = createCommittedNwMessage(Status.PUBLISHED);

        // FAILS on the pre-fix code (clear, add and remove all succeeded on a locked tag: 200).
        as("user", NW_DOMAIN)
                .when().delete("/rest/tags/tag/" + tag.tagId() + "/messages")
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);

        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(List.of(otherUid))
                .when().put("/rest/tags/tag/" + tag.tagId() + "/add-messages")
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);

        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(List.of(tag.messageUid()))
                .when().put("/rest/tags/tag/" + tag.tagId() + "/remove-messages")
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);
    }

    @Test
    void plainUserCannotDeleteALockedTag() throws Exception {
        LockedTag tag = lockedPublicTagWithOneMessage();

        // FAILS on the pre-fix code (the tag was deleted: 200 "true").
        as("user", NW_DOMAIN)
                .when().delete("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(403);
        assertTag(tag.tagId(), tag.name(), true, 1);
    }

    @Test
    void plainUserCanStillLockAnUnlockedTag() {
        String name = uniqueName("user-lock");
        String tagId = createTagAsUser(name, "PUBLIC", false);

        // The lock gate reads the persisted flag, so locking - which the tag manager offers everyone -
        // stays open to a plain user.
        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("tagId", tagId, "name", name, "type", "PUBLIC", "locked", true))
                .when().put("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("locked", is(true));
        assertTag(tagId, name, true, 0);
    }

    @Test
    void publicTagIsChangedFromItsOwnDomainOnly() throws Exception {
        String name = uniqueName("public-other-domain");
        String tagId = createTagAsUser(name, "PUBLIC", false);   // a PUBLIC tag of niord-nw
        String publishedUid = createCommittedNwMessage(Status.PUBLISHED);

        // The sysadmin holds the user and admin roles in niord-nm, and the tag is unlocked, so only
        // the tag's domain can refuse these. FAILS on the pre-fix code (each call succeeded: 200).
        as("sysadmin", NM_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("tagId", tagId, "name", name + "-renamed", "type", "PUBLIC", "locked", false))
                .when().put("/rest/tags/tag/" + tagId)
                .then().statusCode(403);
        as("sysadmin", NM_DOMAIN).contentType(ContentType.JSON)
                .body(List.of(publishedUid))
                .when().put("/rest/tags/tag/" + tagId + "/add-messages")
                .then().statusCode(403);
        as("sysadmin", NM_DOMAIN)
                .when().delete("/rest/tags/tag/" + tagId)
                .then().statusCode(403);
        assertTag(tagId, name, false, 0);

        // Its own domain still changes it
        addMessages(NW_DOMAIN, tagId, List.of(publishedUid))
                .then().statusCode(200)
                .body("messageCount", is(1));
    }

    // ------------------------------------------------------------------------------------------- //
    // N-06: a TEMP tag belongs to the user who created it, like a PRIVATE one.
    //
    // The admin app makes TEMP tags for print, export, mail and area-sort selections, often of drafts,
    // and only its creator ever uses one. Readable by everyone, a TEMP tag id also fed a tag= search
    // that skips the domain's series restriction. The creator here is testeditor.
    // ------------------------------------------------------------------------------------------- //

    /** A TEMP tag's id and its server-assigned name. */
    private record TempTag(String tagId, String name) {}

    /** Creates an empty TEMP tag as the given identity tier in the nw domain. */
    private TempTag createTempTagAs(String role) {
        io.restassured.response.Response created = as(role, NW_DOMAIN).contentType(ContentType.JSON)
                .body(List.of())
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(200)
                .body("type", is("TEMP"))
                .extract().response();
        TempTag tag = new TempTag(created.path("tagId"), created.path("name"));
        createdTagIds.add(tag.tagId());
        return tag;
    }

    @Test
    void tempTagIsVisibleToItsCreatorOnly() {
        String tagId = createTempTagAs("editor").tagId();

        as("editor", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].type", is("TEMP"));

        // Another user of the same domain, the sysadmin and an anonymous caller all get an empty list.
        // FAILS on the pre-fix code (every caller got the tag).
        as("user", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(0));
        as("sysadmin", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(0));
        given()
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void anonymousTagSearchListsNoTempTags() {
        TempTag tag = createTempTagAs("editor");

        // FAILS on the pre-fix code (every TEMP tag was listed to anyone).
        given()
                .queryParam("type", "TEMP")
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("size()", is(0));

        // Its creator still finds it
        as("editor", NW_DOMAIN)
                .queryParam("type", "TEMP")
                .queryParam("name", tag.name())
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("tagId", hasItem(tag.tagId()));
    }

    @Test
    void anotherUserCannotRewriteATempTag() {
        String tagId = createTempTagAs("editor").tagId();

        // testuser cannot see the tag, so the service reports it as unknown (400) and nothing changes.
        // FAILS on the pre-fix code (the PUT turned the TEMP tag into a PUBLIC one: 200).
        as("user", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("tagId", tagId, "name", uniqueName("temp-hijack"), "type", "PUBLIC", "locked", false))
                .when().put("/rest/tags/tag/" + tagId)
                .then().statusCode(400);

        as("editor", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tagId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].type", is("TEMP"));
    }

    @Test
    void aTempTagItsCreatorLockedStaysTheCreators() {
        // A TEMP tag is found for its creator only, like a PRIVATE one (TAG1), so the lock does not take it
        // from the creator, who holds no admin role. FAILS on the pre-fix code (the rename and the delete: 403).
        TempTag tag = createTempTagAs("editor");
        Map<String, Object> body = new java.util.HashMap<>(as("editor", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200).extract().jsonPath().getMap("[0]"));
        body.put("locked", true);
        as("editor", NW_DOMAIN).contentType(ContentType.JSON).body(body)
                .when().put("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .body("locked", is(true));

        // Another user still cannot see it: the service reports it as unknown (400)
        as("user", NW_DOMAIN).contentType(ContentType.JSON).body(body)
                .when().put("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(400);

        body.put("name", tag.name() + "-renamed");
        as("editor", NW_DOMAIN).contentType(ContentType.JSON).body(body)
                .when().put("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .body("name", is(tag.name() + "-renamed"))
                .body("locked", is(true));
        String deleted = as("editor", NW_DOMAIN)
                .when().delete("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", deleted, "the creator deletes the locked TEMP tag");
    }

    // ------------------------------------------------------------------------------------------- //
    // N-11: a DOMAIN tag is listed to a USER of its domain, not to a caller that only names the domain.
    //
    // The NiordDomain header is unauthenticated; the user role, which NiordDomainFilter only adds from
    // the verified token's roles on the named domain, is what proves membership.
    // ------------------------------------------------------------------------------------------- //

    /** A DOMAIN tag of niord-nw and the one committed published nw message it holds. */
    private record DomainTag(String tagId, String messageUid) {}

    private DomainTag domainTagWithOneMessage(String name) throws Exception {
        String tagId = createTagAsUser(name, "DOMAIN", false);
        String uid = createCommittedNwMessage(Status.PUBLISHED);
        addMessages(NW_DOMAIN, tagId, List.of(uid))
                .then().statusCode(200);
        return new DomainTag(tagId, uid);
    }

    @Test
    void domainTagIsHiddenFromAnAnonymousCallerThatNamesItsDomain() throws Exception {
        String name = uniqueName("domain-anon");
        DomainTag tag = domainTagWithOneMessage(name);

        // FAILS on the pre-fix code (the header alone listed the tag on all three read endpoints).
        given().header("NiordDomain", NW_DOMAIN)
                .queryParam("name", name)
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("name", not(hasItem(name)));
        given().header("NiordDomain", NW_DOMAIN)
                .queryParam("type", "DOMAIN")
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("size()", is(0));
        given().header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .body("size()", is(0));
        given().header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tags/message/" + tag.messageUid())
                .then().statusCode(200)
                .body("tagId", not(hasItem(tag.tagId())));
    }

    @Test
    void domainTagIsListedToAUserOfItsDomainOnly() throws Exception {
        String name = uniqueName("domain-user");
        DomainTag tag = domainTagWithOneMessage(name);

        // A plain user of niord-nw sees the tag on all three read endpoints
        as("user", NW_DOMAIN)
                .queryParam("name", name)
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("name", hasItem(name));
        as("user", NW_DOMAIN)
                .when().get("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].type", is("DOMAIN"));
        as("user", NW_DOMAIN)
                .when().get("/rest/tags/message/" + tag.messageUid())
                .then().statusCode(200)
                .body("tagId", hasItem(tag.tagId()));

        // A user of another domain does not
        as("sysadmin", NM_DOMAIN)
                .queryParam("name", name)
                .when().get("/rest/tags/search")
                .then().statusCode(200)
                .body("name", not(hasItem(name)));
        as("sysadmin", NM_DOMAIN)
                .when().get("/rest/tags/tag/" + tag.tagId())
                .then().statusCode(200)
                .body("size()", is(0));
        as("sysadmin", NM_DOMAIN)
                .when().get("/rest/tags/message/" + tag.messageUid())
                .then().statusCode(200)
                .body("tagId", not(hasItem(tag.tagId())));
    }

    // ------------------------------------------------------------------------------------------- //
    // TAG4: a tag's type is changed by an admin, or by the owner of a PRIVATE or TEMP tag; and no type
    // change may leave a tag with nobody to manage it. TAG2: an update without a type or a name is a 400.
    // ------------------------------------------------------------------------------------------- //

    @Autowired
    MessageTagService messageTagService;

    /** A PUT of the tag with the given type, name and lock as the identity, from the domain */
    private io.restassured.response.Response putTag(String role, String domain, String tagId, String type,
                                                     String name, boolean locked) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("tagId", tagId);
        body.put("name", name);
        body.put("locked", locked);
        if (type != null) {
            body.put("type", type);
        }
        return as(role, domain).contentType(ContentType.JSON).body(body)
                .when().put("/rest/tags/tag/" + tagId);
    }

    /** The stored type and name of the tag, read straight from the database */
    private List<String> storedTypeAndName(String tagId) {
        return Tx.requiringNew().call(() -> {
            MessageTag tag = em.createQuery("select t from MessageTag t where t.tagId = :tagId", MessageTag.class)
                    .setParameter("tagId", tagId)
                    .getSingleResult();
            return List.of(tag.getType().name(), tag.getName());
        });
    }

    @Test
    void aUserWhoIsNotAnAdminCannotChangeTheTypeOfASharedTag() {
        String publicName = uniqueName("type-public");
        String publicTagId = createTagAsUser(publicName, "PUBLIC", false);
        String domainName = uniqueName("type-domain");
        String domainTagId = createTagAsUser(domainName, "DOMAIN", false);

        // testuser holds the user role in niord-nw; testadminelsewhere is an admin of niord-nm only, a plain
        // user here. FAILS on the pre-fix code (each change was taken: 200, and a PUBLIC tag turned PRIVATE
        // went to its creator alone).
        List<Integer> statuses = new ArrayList<>();
        for (String role : List.of("user", "editor", "adminElsewhere")) {
            io.restassured.response.Response toPrivate = putTag(role, NW_DOMAIN, publicTagId, "PRIVATE",
                    publicName, false);
            statuses.add(toPrivate.statusCode());
            io.restassured.response.Response toPublic = putTag(role, NW_DOMAIN, domainTagId, "PUBLIC",
                    domainName, false);
            statuses.add(toPublic.statusCode());
            if (toPublic.statusCode() == 403) {
                assertEquals("Only admins can change the type of a shared tag", toPublic.path("detail"));
            }
        }
        assertEquals(List.of(403, 403, 403, 403, 403, 403), statuses, "user, editor and adminElsewhere");
        assertEquals(List.of("PUBLIC", publicName), storedTypeAndName(publicTagId));
        assertEquals(List.of("DOMAIN", domainName), storedTypeAndName(domainTagId));

        // The right path: the same user still renames the shared tag when the type stays
        putTag("user", NW_DOMAIN, publicTagId, "PUBLIC", publicName + "-renamed", false)
                .then().statusCode(200)
                .body("name", is(publicName + "-renamed"))
                .body("type", is("PUBLIC"));
    }

    @Test
    void anAdminChangesTheTypeOfASharedTag() {
        String name = uniqueName("type-admin");
        String tagId = createTagAsUser(name, "PUBLIC", false);   // the sysadmin's PUBLIC tag of niord-nw

        // testadmin holds the admin role in niord-nw
        putTag("admin", NW_DOMAIN, tagId, "DOMAIN", name, false)
                .then().statusCode(200)
                .body("type", is("DOMAIN"));
        putTag("admin", NW_DOMAIN, tagId, "PUBLIC", name, false)
                .then().statusCode(200)
                .body("type", is("PUBLIC"));
        assertEquals(List.of("PUBLIC", name), storedTypeAndName(tagId));
    }

    @Test
    void theOwnerOfAPrivateOrTempTagChangesItsType() {
        // testeditor holds no admin role; a PRIVATE or TEMP tag is found for its owner only, so the caller is it
        String name = uniqueName("type-owner");
        String privateTagId = as("editor", NW_DOMAIN).contentType(ContentType.JSON)
                .body(Map.of("name", name, "type", "PRIVATE", "locked", false))
                .when().post("/rest/tags/tag/")
                .then().statusCode(200)
                .extract().path("tagId");
        createdTagIds.add(privateTagId);
        putTag("editor", NW_DOMAIN, privateTagId, "PUBLIC", name, false)
                .then().statusCode(200)
                .body("type", is("PUBLIC"));

        TempTag temp = createTempTagAs("editor");
        putTag("editor", NW_DOMAIN, temp.tagId(), "PRIVATE", temp.name(), false)
                .then().statusCode(200)
                .body("type", is("PRIVATE"));
        assertEquals(List.of("PRIVATE", temp.name()), storedTypeAndName(temp.tagId()));
    }

    @Test
    void aTypeThatWouldLeaveATagWithNobodyToManageItIsRefusedForAnAdminToo() {
        // A PUBLIC tag with no user and no domain, as the publication code makes them: made outside a request,
        // so the service records neither
        MessageTag template = new MessageTag();
        template.setType(org.niord.core.message.vo.MessageTagVo.MessageTagType.PUBLIC);
        String name = uniqueName("type-orphan");
        template.setName(name);
        String tagId = messageTagService.createMessageTag(template).getTagId();
        createdTagIds.add(tagId);

        // FAILS on the pre-fix code (the first change was taken, 200, and the tag, now PRIVATE with no user, was
        // found by nobody: the next two answered 400 as an unknown tag).
        List<Integer> statuses = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (String type : List.of("PRIVATE", "TEMP", "DOMAIN")) {
            io.restassured.response.Response response = putTag("sysadmin", NW_DOMAIN, tagId, type, name, false);
            statuses.add(response.statusCode());
            details.add(response.statusCode() == 400 ? response.path("detail") : null);
        }
        assertEquals(List.of(400, 400, 400), statuses, "PRIVATE, TEMP and DOMAIN on a tag with no user or domain");
        for (String detail : details) {
            assertTrue(detail != null && detail.endsWith("no one could manage it"), String.valueOf(detail));
        }
        assertEquals(List.of("PUBLIC", name), storedTypeAndName(tagId));

        // The right path: the tag is still renamed when the type stays
        putTag("sysadmin", NW_DOMAIN, tagId, "PUBLIC", name + "-renamed", false)
                .then().statusCode(200)
                .body("name", is(name + "-renamed"))
                .body("type", is("PUBLIC"));
    }

    @Test
    void anUpdateWithoutATypeOrANameAnswers400AndChangesNothing() {
        // TAG2: the create's check in the update. FAILS on the pre-fix code (no type: a 500 at the flush; an
        // empty or blank name: 200, stored as it was).
        String name = uniqueName("update-no-type");
        String tagId = createTagAsUser(name, "PRIVATE", false);

        List<Integer> statuses = new ArrayList<>();
        statuses.add(putTag("sysadmin", NW_DOMAIN, tagId, null, name, false).statusCode());
        statuses.add(putTag("sysadmin", NW_DOMAIN, tagId, "PRIVATE", "", false).statusCode());
        statuses.add(putTag("sysadmin", NW_DOMAIN, tagId, "PRIVATE", "   ", false).statusCode());
        assertEquals(List.of(400, 400, 400), statuses, "no type, an empty name, a blank name");
        assertEquals(List.of("PRIVATE", name), storedTypeAndName(tagId));

        putTag("sysadmin", NW_DOMAIN, tagId, null, name, false)
                .then().statusCode(400)
                .body("detail", is("A tag must have a type"));
        putTag("sysadmin", NW_DOMAIN, tagId, "PRIVATE", " ", false)
                .then().statusCode(400)
                .body("detail", is("A tag must have a name"));
    }
}
