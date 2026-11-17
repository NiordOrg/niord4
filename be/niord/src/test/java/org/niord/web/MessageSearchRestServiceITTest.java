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
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for {@link MessageSearchRestService}: the HTTP contract of the two
 * {@code @PermitAll} read endpoints {@code GET /rest/messages/search} and
 * {@code GET /rest/messages/search-message-ids}.
 *
 * <p>The most important behaviour verified here is the security defaulting applied by
 * {@code MessageSearchRestService.search(MessageSearchParams)}: when there is no token and no current
 * domain the resolved {@code searchDomain} is {@code null}, so the service forces
 * {@code statuses = {PUBLISHED}}. As a result an anonymous {@code /search} only ever returns
 * PUBLISHED messages; DRAFT/VERIFIED messages are invisible. Supplying a {@code domain} query param
 * resolves a search domain, restricts the result to that domain's message series and keeps only the
 * public statuses (PUBLISHED/CANCELLED/EXPIRED).
 *
 * <p>These tests need COMMITTED data: an HTTP request runs in the server's own transaction, which
 * {@code @Transactional} cannot roll back, and the rows must already be visible to the separate
 * request transaction. Messages are therefore created/published through {@link MessageService}
 * (whose {@code @Transactional} methods commit when invoked outside an enclosing transaction) and
 * removed again in {@link #cleanup()} using the {@code Tx.requiringNew()} pattern
 * (delete the {@code MessageHistory} rows first, then {@code em.remove} the {@code Message}). Every
 * message uses a globally-unique title token so a test only ever asserts on rows it created and the
 * tests stay independent of the seed data and of each other.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSearchRestServiceITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageTagService messageTagService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    private final List<String> createdTagIds = new ArrayList<>();

    /** Whether a test rebuilt the free-text index, which is then rebuilt again once its rows are gone. */
    private boolean luceneIndexRebuilt;

    @AfterEach
    void cleanup() {
        // Delete the tags first so their message-tag join rows are gone before the messages are removed.
        // Straight from the database: a TEMP tag is its creator's, so the service does not see it here.
        for (String tagId : createdTagIds) {
            Tx.requiringNew().run(() -> em
                    .createQuery("select t from MessageTag t where t.tagId = :tagId", MessageTag.class)
                    .setParameter("tagId", tagId)
                    .getResultList()
                    .forEach(em::remove));
        }
        createdTagIds.clear();
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdMessageIds.clear();

        if (luceneIndexRebuilt) {
            luceneIndexRebuilt = false;
            try {
                messageLuceneIndex.recreateIndex();
            } catch (Exception ignored) {
                // best effort: the next index update drops the removed rows anyway
            }
        }
    }

    /** Rebuilds the free-text index from the committed rows, so a free-text search can see them. */
    private void rebuildLuceneIndex() throws Exception {
        luceneIndexRebuilt = true;
        messageLuceneIndex.recreateIndex();
    }

    /** Builds a detached ownerless PUBLIC message tag template with the given name. */
    private MessageTag newPublicTag(String name) {
        MessageTag tag = new MessageTag();
        tag.setType(MessageTagType.PUBLIC);
        tag.setName(name);
        return tag;
    }

    /**
     * Creates a COMMITTED, ownerless PUBLIC tag (createMessageTag/addMessageToTag commit when invoked
     * outside a transaction) containing the given already-committed messages. Because the tag carries no
     * user/domain, {@code MessageTagService.validateAccess} authorizes it for an anonymous caller.
     */
    private MessageTag createPublicTagWithMessages(String name, Message... messages) {
        MessageTag tag = messageTagService.createMessageTag(newPublicTag(name));
        createdTagIds.add(tag.getTagId());
        List<String> uids = new ArrayList<>();
        for (Message m : messages) {
            uids.add(m.getUid());
        }
        if (!uids.isEmpty()) {
            messageTagService.addMessageToTag(tag.getTagId(), uids);
        }
        return tag;
    }

    /** A globally-unique alphanumeric token used in titles so each test asserts only on its own rows. */
    private String uniqueToken() {
        return "zzsrch" + System.nanoTime();
    }

    /**
     * A fixed PAST publish date. Publishing takes a number from the YEARLY sequence of the series inside
     * the publishing transaction, and this suite's publishes commit (in a committed {@code Tx}), so
     * their numbers stay taken; a far-past date keeps these publishes
     * on the year-2001 sequence so they never disturb the current-year number that
     * {@code MessageSeriesRestServiceITTest} asserts is 1.
     */
    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached DRAFT message template in the given series. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        // createMessage substitutes the persisted series looked up by id.
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type); // mainType is derived from the type
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate()); // keep publishes off the current-year number sequence

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates a COMMITTED DRAFT message (createMessage commits since it runs outside a tx). */
    private Message createDraft(String seriesId, Type type, String title) throws Exception {
        Message created = messageService.createMessage(newDraft(seriesId, type, title));
        createdMessageIds.add(created.getId());
        return created;
    }

    /**
     * Creates a COMMITTED DRAFT message with a manually-assigned shortId. createMessage/onPersist do
     * not clear a shortId set on the template, so this lets the searchMessageIds shortId/deleted
     * filter be exercised without going through a publish (which would assign its own shortId).
     */
    private Message createDraftWithShortId(String seriesId, Type type, String title, String shortId)
            throws Exception {
        Message template = newDraft(seriesId, type, title);
        template.setShortId(shortId);
        Message created = messageService.createMessage(template);
        createdMessageIds.add(created.getId());
        return created;
    }

    /**
     * Creates a COMMITTED message and publishes it (DRAFT -> VERIFIED -> PUBLISHED). Publishing
     * assigns the message number and short ID from the message series. The walk to PUBLISHED runs in
     * ONE committed transaction: the auto-publish job only sees committed state, so the atomic walk
     * never exposes a committed VERIFIED + past-date row for it to seize.
     */
    private Message createPublished(String seriesId, Type type, String title) throws Exception {
        Message created = createDraft(seriesId, type, title);
        return Tx.requiringNew().call(() -> {
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — anonymous access defaults to PUBLISHED-only
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithoutTokenReturnsHttp200AndPagedEnvelope() throws Exception {
        createPublished("dma-nw", Type.COASTAL_WARNING, "Published " + uniqueToken());

        // The PagedSearchResultVo envelope always carries data/total/size; with at least one
        // published message present total is >= 1 and the data list is non-empty.
        given()
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data", is(not(empty())))
                .body("total", greaterThanOrEqualTo(1))
                .body("size", greaterThanOrEqualTo(1));
    }

    @Test
    void searchWithoutTokenReturnsPublishedMessageByUid() throws Exception {
        String token = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Published " + token);

        // The MessageVo 'id' field is the message UID (see Message.toVo). A published message must be
        // present in an anonymous search since the service defaults statuses to {PUBLISHED}.
        given()
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));
    }

    @Test
    void searchWithoutTokenDoesNotReturnDraftMessage() throws Exception {
        // A committed DRAFT must NOT surface for an anonymous search: searchDomain==null forces
        // statuses={PUBLISHED}, and DRAFT is not public.
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())));
    }

    @Test
    void searchWithoutTokenReturnsOnlyPublishedStatuses() throws Exception {
        // Mix of statuses: a PUBLISHED message is visible, a DRAFT one is not, and every returned
        // row carries status PUBLISHED because the anonymous default restricts to {PUBLISHED}.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()))
                .body("data.id", not(hasItem(draft.getUid())))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — explicit status / series filters
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithExplicitPublishedStatusReturnsThePublishedMessage() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("status", "PUBLISHED")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchWithDraftStatusIsStrippedToPublishedForAnonymousCaller() throws Exception {
        // status=DRAFT is requested, but for searchDomain==null the only public default that
        // survives is PUBLISHED, so the DRAFT message is still NOT returned (status restriction).
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .queryParam("status", "DRAFT")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchFilteredByMessageSeriesReturnsOnlyThatSeries() throws Exception {
        // Publish into two different series; restricting messageSeries=dma-nw must include the NW one
        // and exclude the NM one. (For anonymous callers the seriesIds filter is applied as-is since
        // there is no search domain to intersect against.)
        Message nw = createPublished("dma-nw", Type.COASTAL_WARNING, "NW " + uniqueToken());
        Message nm = createPublished("dma-nm", Type.TEMPORARY_NOTICE, "NM " + uniqueToken());

        given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(nw.getUid()))
                .body("data.id", not(hasItem(nm.getUid())))
                .body("data.messageSeries.seriesId", everyItem(is("dma-nw")));
    }

    @Test
    void searchFilteredByMainTypeNwExcludesNmMessages() throws Exception {
        Message nw = createPublished("dma-nw", Type.COASTAL_WARNING, "NW " + uniqueToken());
        Message nm = createPublished("dma-nm", Type.TEMPORARY_NOTICE, "NM " + uniqueToken());

        given()
                .queryParam("mainType", "NW")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(nw.getUid()))
                .body("data.id", not(hasItem(nm.getUid())))
                .body("data.mainType", everyItem(is("NW")));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — pagination
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchHonoursMaxSizePageBound() throws Exception {
        // Create several published messages, then request a single-row page. The returned data list
        // must never exceed the requested maxSize, while total reflects the unpaged count.
        createPublished("dma-nw", Type.COASTAL_WARNING, "P1 " + uniqueToken());
        createPublished("dma-nw", Type.COASTAL_WARNING, "P2 " + uniqueToken());
        createPublished("dma-nw", Type.COASTAL_WARNING, "P3 " + uniqueToken());

        given()
                .queryParam("maxSize", 1)
                .queryParam("page", 0)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.size()", is(1))
                .body("total", greaterThanOrEqualTo(3));
    }

    @Test
    void searchSecondPageReturnsDifferentRowThanFirstPage() throws Exception {
        // With at least two published rows, page 0 and page 1 (size 1) must not return the same id.
        createPublished("dma-nw", Type.COASTAL_WARNING, "PageA " + uniqueToken());
        createPublished("dma-nw", Type.COASTAL_WARNING, "PageB " + uniqueToken());

        String firstId = given()
                .queryParam("maxSize", 1).queryParam("page", 0)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.size()", is(1))
                .extract().path("data[0].id");

        given()
                .queryParam("maxSize", 1).queryParam("page", 1)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.size()", is(1))
                .body("data[0].id", not(is(firstId)));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — domain restriction
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithNwDomainReturnsNwSeriesMessageButNotNmSeriesMessage() throws Exception {
        // Domain niord-nw is linked to dma-nw + dma-nw-local only. With domain=niord-nw
        // the search is restricted to that domain's series, so a published NW-series message shows up
        // while a published NM-series message (dma-nm, not in the domain) is filtered out.
        Message nw = createPublished("dma-nw", Type.COASTAL_WARNING, "NW " + uniqueToken());
        Message nm = createPublished("dma-nm", Type.TEMPORARY_NOTICE, "NM " + uniqueToken());

        given()
                .queryParam("domain", "niord-nw")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(nw.getUid()))
                .body("data.id", not(hasItem(nm.getUid())))
                // niord-nw is linked to dma-nw + dma-nw-local only.
                .body("data.messageSeries.seriesId", everyItem(isOneOf("dma-nw", "dma-nw-local")))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchWithUnknownDomainFallsBackToPublishedOnlyDefaulting() throws Exception {
        // An unknown domain id resolves to a null search domain (same as no domain), so the anonymous
        // PUBLISHED-only default applies and a published message is still returned with a 200.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("domain", "IT-no-such-domain")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchWithDomainHeaderDoesNotLeakDraftToAnonymousCaller() throws Exception {
        // The NiordDomain header sets the current (and, absent a domain query param, the search) domain
        // server-side with NO authentication. An anonymous caller must NOT be able to read that domain's
        // DRAFT messages simply by asking for status=DRAFT: because the caller holds no USER role, the
        // status restriction still strips the non-public statuses and floors the result to PUBLISHED.
        // This is the regression guard for the header-based draft-leak fix in
        // MessageSearchRestService.search(); it FAILS on the pre-fix code (the draft leaks).
        String token = uniqueToken();
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + token);
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);

        given()
                .header("NiordDomain", "niord-nw")
                .queryParam("status", "DRAFT")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())))
                .body("data.id", hasItem(published.getUid()))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchWithDomainHeaderAndUserRoleReturnsOwnDomainDraft() throws Exception {
        // Positive regression: an authenticated USER of niord-nw (the sysadmin identity carries
        // the user role on that client, granted by the NiordDomain augmentor) with the matching header
        // IS allowed to search its own domain's drafts. currentDomain == searchDomain AND the caller
        // holds USER, so the status restriction is not applied and the requested DRAFT survives. This
        // proves the leak fix does not over-restrict the legitimate authenticated draft-search flow.
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("status", "DRAFT")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                // The requested DRAFT status survives, so the caller's own domain draft is returned...
                .body("data.id", hasItem(draft.getUid()))
                // ...and, since status=DRAFT was preserved (not floored to PUBLISHED), every returned
                // row carries a non-public DRAFT status — content an anonymous caller never sees.
                .body("data.status", everyItem(is("DRAFT")));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — SECAUD-6: a PUBLIC tag must not bypass the public-status floor for anonymous callers
    //
    // Supplying a tag short-circuits the no-tag status restriction, so before the fix an anonymous
    // caller could read a public tag's DRAFT/VERIFIED members (and, via export.zip which funnels through
    // this same search(), their promulgations and attachment bytes). The final status floor in
    // search() closes both the NiordDomain-header variant and the bare no-domain variant.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchByPublicTagWithDomainHeaderDoesNotLeakDraftToAnonymousCaller() throws Exception {
        // Path A: anonymous caller + NiordDomain header. The header sets the search/current domain but is
        // NOT proof of authorization, so a PUBLIC tag containing a DRAFT must still be floored to public
        // statuses. FAILS on the pre-fix code (the tagged draft leaks).
        String token = uniqueToken();
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + token);
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);
        MessageTag tag = createPublicTagWithMessages("IT-SECAUD6a-" + token, draft, published);

        given()
                .header("NiordDomain", "niord-nw")
                .queryParam("tag", tag.getTagId())
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())))
                .body("data.id", hasItem(published.getUid()))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchByPublicTagWithoutDomainDoesNotLeakDraftToAnonymousCaller() throws Exception {
        // Path B: bare anonymous caller, no domain header and no domain query param — the easiest variant
        // to trigger. The tag still resolves (PUBLIC tags are accessible anonymously), but the draft must
        // be stripped. FAILS on the pre-fix code (no floor is applied on the no-domain tag path).
        String token = uniqueToken();
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + token);
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);
        MessageTag tag = createPublicTagWithMessages("IT-SECAUD6b-" + token, draft, published);

        given()
                .queryParam("tag", tag.getTagId())
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())))
                .body("data.id", hasItem(published.getUid()))
                .body("data.status", everyItem(is("PUBLISHED")));
    }

    @Test
    void searchByPublicTagWithDomainHeaderAndUserRoleReturnsDraft() throws Exception {
        // Positive regression: an authenticated USER of niord-nw (the sysadmin identity carries the
        // user role on that client via the domain augmentor) IS allowed to read the tag's DRAFT members.
        // Proves the SECAUD-6 floor does not over-restrict the legitimate authenticated tagged-export flow.
        String token = uniqueToken();
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + token);
        MessageTag tag = createPublicTagWithMessages("IT-SECAUD6c-" + token, draft);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("tag", tag.getTagId())
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(draft.getUid()))
                .body("data.status", everyItem(is("DRAFT")));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — N-06: a TEMP tag is its creator's, so another caller's tag= names no member set.
    //
    // A tag= search skips the domain's series restriction, and the status floor only binds a caller who
    // is not a USER of the search domain. A TEMP tag id someone else created therefore handed its drafts
    // to any user, of any domain. The foreign id is now dropped and the search falls back to the no-tag
    // restrictions.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchByAnotherUsersTempTagDoesNotReturnItsDraft() throws Exception {
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        // testeditor makes the selection, as the admin app does for a print, export or mail
        String tempTagId = given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(List.of(draft.getUid()))
                .when().post("/rest/tags/temp-tag/?ttl=10")
                .then().statusCode(200)
                .extract().path("tagId");
        createdTagIds.add(tempTagId);

        // Its creator's tagged search still returns the draft
        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .queryParam("tag", tempTagId)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(draft.getUid()));

        // Another user of the same domain, and a user of another domain, do not get it through the tag.
        // FAILS on the pre-fix code (the TEMP tag was valid for every caller and returned the draft).
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("tag", tempTagId)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())));
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .queryParam("tag", tempTagId)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(draft.getUid())));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — page bounds (maxSize is clamped to [1, 1000], the page offset uses long arithmetic)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchClampsNonPositiveMaxSizeToOneRow() throws Exception {
        // A negative page size used to fail the paging (subList) and come back as an empty result;
        // it is now raised to the smallest page.
        createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("maxSize", -5)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.size()", is(1))
                .body("total", greaterThanOrEqualTo(1));
    }

    @Test
    void searchWithHugePageNumberReturnsAnEmptyPageAndTheTotal() throws Exception {
        // A smoke test of the HTTP contract only: the catch-all in MessageSearchService.search() turned
        // an overflowing offset into this same empty page before. The offset arithmetic itself is
        // pinned in MessageSearchServiceITTest.pagingWithAHugePageNumberDoesNotOverflowTheOffset.
        createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("maxSize", 1000)
                .queryParam("page", Integer.MAX_VALUE)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data", empty())
                .body("total", greaterThanOrEqualTo(1));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — free text from a caller without an identity is matched as plain words
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anonymousFreeTextQueryIsMatchedAsPlainWords() throws Exception {
        String token = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Lucene " + token);
        rebuildLuceneIndex();
        String leadingWildcard = "*" + token.substring(2);

        // Control: the plain word still finds the message anonymously
        given()
                .queryParam("query", token)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));

        // A leading wildcard from an anonymous caller is text, not a pattern
        given()
                .queryParam("query", leadingWildcard)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(published.getUid())));

        // A signed-in USER keeps the query syntax
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("query", leadingWildcard)
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search — the author and comment filters are internal: dropped for a caller without an
    // identity, kept for a signed-in caller and for the role-less ticket the admin print and export use
    // ------------------------------------------------------------------------------------------- //

    /**
     * A fresh one-time ticket issued the way the admin print and export fetch one: signed in, with the
     * {@code NiordDomain} header and no {@code role}, so it names the user and domain but grants no role.
     */
    private String roleLessTicket(String identity) {
        return given().auth().oauth2(accessToken(identity))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();
    }

    @Test
    void anonymousSearchIgnoresTheUsernameFilter() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("username", "it-no-such-user-" + System.nanoTime())
                .queryParam("userType", "CREATED_BY")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));
    }

    @Test
    void anonymousSearchIgnoresTheCommentsFilter() throws Exception {
        // The message has no comments, so an applied ANY_UNACK filter would leave it out.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .header("NiordDomain", "niord-nw")
                .queryParam("comments", "ANY_UNACK")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));
    }

    @Test
    void domainUserSearchKeepsTheUsernameFilter() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        // Control: without the filter the USER sees the message...
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));

        // ...and with a username nobody has, the filter applies and leaves it out
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("username", "it-no-such-user-" + System.nanoTime())
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(published.getUid())));
    }

    @Test
    void ticketedStaffSearchKeepsTheUsernameFilter() throws Exception {
        // The admin print and export search with a role-less ticket and no token. The ticket holds no
        // USER role, so the status floor applies, but it names a signed-in user, so the author filter
        // the user chose still narrows the printed or exported list.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        // Control: without the filter the ticketed search sees the message...
        given()
                .queryParam("ticket", roleLessTicket("user"))
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", hasItem(published.getUid()));

        // ...and with a username nobody has, the filter applies and leaves it out
        given()
                .queryParam("ticket", roleLessTicket("user"))
                .queryParam("username", "it-no-such-user-" + System.nanoTime())
                .queryParam("maxSize", 500)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(published.getUid())));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search-message-ids
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchMessageIdsWithBlankTextReturnsEmptyArray() {
        // searchMessageIds returns an empty list for blank text, even though includeText defaults true.
        given()
                .queryParam("txt", "")
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsWithAbsentTextReturnsEmptyArray() {
        given()
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsIncludesLeadingTextMatchByDefault() throws Exception {
        // includeText defaults to true: the first entry echoes the search text as a TEXT match.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());
        String shortId = published.getShortId();

        given()
                .queryParam("txt", shortId)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("[0].type", is("TEXT"))
                .body("[0].messageId", is(shortId));
    }

    @Test
    void searchMessageIdsCanSuppressLeadingTextMatch() throws Exception {
        // With includeText=false there is no leading TEXT entry; only real matches are returned.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());
        String shortId = published.getShortId();

        given()
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("type", not(hasItem("TEXT")))
                .body("findAll { it.type == 'SHORT_ID' }.messageId", hasItem(shortId));
    }

    @Test
    void searchMessageIdsMatchesPublishedShortId() throws Exception {
        // The published message's shortId must surface as a SHORT_ID match with the right status.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());
        String shortId = published.getShortId();

        given()
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.messageId", hasItem(shortId))
                .body("find { it.type == 'SHORT_ID' && it.messageId == '" + shortId + "' }.status",
                        is("PUBLISHED"));
    }

    @Test
    void searchMessageIdsMatchesUidAsUidType() throws Exception {
        // A full UID lookup yields a UID match (findByUid hit) in addition to the TEXT echo.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());
        String uid = published.getUid();

        given()
                .queryParam("txt", uid)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("[0].type", is("TEXT"))
                .body("[0].messageId", is(uid))
                .body("findAll { it.type == 'UID' }.messageId", hasItem(uid));
    }

    @Test
    void searchMessageIdsExcludesDeletedShortIdByDefault() throws Exception {
        // A DRAFT carrying a manual shortId is DELETED (DRAFT -> DELETED is a legal transition). The
        // default deleted=false adds "and m.status != 'DELETED'" to the shortId query, so the deleted
        // message's shortId must NOT surface as a SHORT_ID match.
        String shortId = "IT-SRCH-DEL-" + System.nanoTime();
        Message draft = createDraftWithShortId("dma-nw", Type.COASTAL_WARNING,
                "Del " + uniqueToken(), shortId);
        messageService.updateStatus(draft.getUid(), Status.DELETED);

        given()
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.messageId", not(hasItem(shortId)));
    }

    @Test
    void searchMessageIdsIncludesDeletedShortIdWhenRequestedByDomainUser() throws Exception {
        // Control for the exclusion test: with deleted=true the status filter is dropped for a USER
        // (the editor's reference picker), so the DELETED message's shortId IS returned as a SHORT_ID
        // match with status DELETED. This ran anonymously until the public floor was added; an
        // anonymous caller now gets public messages only (see the anonymous cases below).
        String shortId = "IT-SRCH-DEL2-" + System.nanoTime();
        Message draft = createDraftWithShortId("dma-nw", Type.COASTAL_WARNING,
                "Del " + uniqueToken(), shortId);
        messageService.updateStatus(draft.getUid(), Status.DELETED);

        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .queryParam("deleted", true)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.messageId", hasItem(shortId))
                .body("find { it.type == 'SHORT_ID' && it.messageId == '" + shortId + "' }.status",
                        is("DELETED"));
    }

    @Test
    void searchMessageIdsMatchesNonDeletedDraftShortIdForDomainUser() throws Exception {
        // A non-deleted DRAFT with a manual shortId is matched for a USER by the default deleted=false
        // query: the shortId SQL filters out only DELETED rows, not other statuses. This ran
        // anonymously until the public floor was added.
        String shortId = "IT-SRCH-DRAFT-" + System.nanoTime();
        createDraftWithShortId("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken(), shortId);

        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.messageId", hasItem(shortId))
                .body("find { it.type == 'SHORT_ID' && it.messageId == '" + shortId + "' }.status",
                        is("DRAFT"));
    }

    // ------------------------------------------------------------------------------------------- //
    // /search-message-ids — the public floor for a caller without the USER role
    //
    // The endpoint stays anonymous (the admin application's guest mode offers the reference picker),
    // so such a caller must only ever learn of published, cancelled and expired messages.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchMessageIdsHidesDraftShortIdFromAnonymousCaller() throws Exception {
        String shortId = "IT-SRCH-ADRAFT-" + System.nanoTime();
        createDraftWithShortId("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken(), shortId);

        given()
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsHidesDraftShortIdFromSignedInCallerWithoutUserRole() throws Exception {
        // A token without the NiordDomain header carries no USER role, so the floor applies as well.
        String shortId = "IT-SRCH-NOROLE-" + System.nanoTime();
        createDraftWithShortId("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken(), shortId);

        given().auth().oauth2(accessToken("user"))
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsIgnoresDeletedFlagForAnonymousCaller() throws Exception {
        String shortId = "IT-SRCH-ADEL-" + System.nanoTime();
        Message draft = createDraftWithShortId("dma-nw", Type.COASTAL_WARNING,
                "Del " + uniqueToken(), shortId);
        messageService.updateStatus(draft.getUid(), Status.DELETED);

        given()
                .queryParam("txt", shortId)
                .queryParam("includeText", false)
                .queryParam("deleted", true)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsGivesNoUidMatchForADraftToAnonymousCaller() throws Exception {
        // A UID match carries the title and status, so it must not confirm a draft's uid.
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .queryParam("txt", draft.getUid())
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }

    @Test
    void searchMessageIdsMatchesLikeWildcardsOnlyLiterally() throws Exception {
        // The text is data, not a LIKE pattern: "%" and "_" stand only for themselves.
        String nano = String.valueOf(System.nanoTime());
        String plain = "IT-SRCH-LIKE" + nano;
        String withPercent = "IT-SRCH-LIKE%" + nano;
        Message plainMessage = createPublished("dma-nw", Type.COASTAL_WARNING, "Like " + uniqueToken());
        // Publishing assigns the series short id; give the committed message the short id under test.
        Tx.requiringNew().run(() -> em.find(Message.class, plainMessage.getId()).setShortId(plain));
        Message percentMessage = createPublished("dma-nw", Type.COASTAL_WARNING, "Like " + uniqueToken());
        Tx.requiringNew().run(() -> em.find(Message.class, percentMessage.getId()).setShortId(withPercent));

        // As wildcards these texts would match the plain short id
        for (String wildcardSpelling : new String[] {
                "IT-SRCH-LIKE%" + nano.substring(nano.length() - 3), "IT-SRCH-LIKE_" + nano.substring(1) }) {
            given()
                    .queryParam("txt", wildcardSpelling)
                    .queryParam("includeText", false)
                    .when().get("/rest/messages/search-message-ids")
                    .then().statusCode(200)
                    .body("findAll { it.type == 'SHORT_ID' }.messageId", not(hasItem(plain)));
        }

        // The literal character still matches a short id that contains it, and only that one
        given()
                .queryParam("txt", withPercent)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.messageId", hasItem(withPercent))
                .body("findAll { it.type == 'SHORT_ID' }.messageId", not(hasItem(plain)));
    }

    @Test
    void searchMessageIdsClampsMaxGroupCount() throws Exception {
        // maxGroupCount is kept within [1, 50]: a non-positive value still returns the best match
        // instead of failing the query.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("txt", published.getShortId())
                .queryParam("includeText", false)
                .queryParam("maxGroupCount", -5)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("findAll { it.type == 'SHORT_ID' }.size()", is(1));
    }

    @Test
    void searchMessageIdsForUnknownTextReturnsOnlyTheTextEcho() {
        // A text that matches no UID and no shortId yields just the TEXT echo (includeText default).
        String token = uniqueToken();

        given()
                .queryParam("txt", token)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].type", is("TEXT"))
                .body("[0].messageId", is(token));
    }

    @Test
    void searchMessageIdsForUnknownTextWithoutEchoReturnsEmptyArray() {
        // No matching id and includeText=false -> nothing at all.
        String token = uniqueToken();

        given()
                .queryParam("txt", token)
                .queryParam("includeText", false)
                .when().get("/rest/messages/search-message-ids")
                .then().statusCode(200)
                .body("$", empty());
    }
}
