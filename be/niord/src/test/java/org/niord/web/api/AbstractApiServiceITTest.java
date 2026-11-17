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
package org.niord.web.api;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.niord.core.web.NiordWebException;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationDesc;
import org.niord.core.publication.PublicationService;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessageVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.publication.PublicationType;
import org.niord.model.publication.PublicationVo;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the PUBLIC-API security gate implemented in {@link AbstractApiService} — the
 * logic that decides "what is publicly visible" for unauthenticated callers of the public web/REST
 * API. The central risk pinned here is a DRAFT-leak: a draft (or otherwise non-public) message,
 * publication or status must NEVER be returned by these gate methods.
 *
 * <p>{@link AbstractApiService} is abstract; the concrete {@code @ApplicationScoped} sibling
 * {@link ApiWebService} extends it and is injected here so we can call the inherited gate methods
 * ({@code getMessage}, {@code searchMessages}, {@code getPublication}, {@code getArea}) AND the
 * thin {@code search(..)}/{@code details(..)} VO wrappers in-transaction. {@link ApiWebService} is
 * deliberately preferred over {@code ApiRestService} because the latter is {@code @RequestScoped}
 * and cannot be driven from a bare {@link TestTransaction} unit test.
 *
 * <p>Each test runs inside a {@link TestTransaction} that rolls back. Messages are built with
 * {@code createMessage(...)} (which flushes), so the JPA-criteria search inside
 * {@code searchMessages(...)} sees exactly the rows this test created and nothing else — keeping the
 * tests self-contained and order-independent. Free-text Lucene is NOT exercised here.
 *
 * <p>The publish flow (DRAFT -> VERIFIED -> PUBLISHED) takes a message number from the series'
 * yearly sequence inside the publishing transaction, here the surrounding {@link TestTransaction}, so
 * the number rolls back with the published Message row (SERIES6). The test's transaction holds that
 * year's number row until then, so every published draft is stamped with a far-past publish date
 * (year 2001): the publish flow only ever touches the year-2001 row, never the current-year row
 * asserted by other suites.
 *
 * <p>Fixture facts relied upon (niord-base-data.sql):
 * <ul>
 *   <li>domain {@code niord-nw} has message series {@code dma-nw} (NW) + {@code dma-nw-local};
 *       domain {@code niord-client-master} has NO message series (drives the "return nothing" branch).</li>
 *   <li>series {@code dma-nw} is NW; {@code dma-nm} is NM.</li>
 *   <li>publication category {@code dk-dma-publications} (id 2) has {@code publish=true};
 *       {@code dk-external-publications} (id 1) has {@code publish=false}.</li>
 *   <li>the DK country root area, MRN {@code urn:mrn:iho:country:dk} ({@code SEED_AREA_MRN}), exists.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AbstractApiServiceITTest extends ITTest {

    /** Stable MRN of the seeded DK-country root area (legacy MySQL id 1192); ids differ under Postgres. */
    private static final String SEED_AREA_MRN = "urn:mrn:iho:country:dk";

    /** Far-past publish date so publishing takes its number from the year-2001 sequence (rolled back with the test). */
    private static final Date PAST_PUBLISH_DATE = pastPublishDate();

    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    @Autowired
    ApiWebService apiService;

    @Autowired
    MessageService messageService;

    @Autowired
    PublicationService publicationService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Resolves the persisted numeric id of a seed area by its stable MRN business key. */
    private Integer seedAreaIdByMrn(String mrn) {
        Area area = em.createNamedQuery("Area.findByMrn", Area.class)
                .setParameter("mrn", mrn)
                .getSingleResult();
        return area.getId();
    }

    /** Builds a detached DRAFT message template - createMessage substitutes the persisted series. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries(seriesId);
        if (type != null) {
            series.setMainType(type.getMainType());
        }
        msg.setMessageSeries(series);
        msg.setType(type);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(PAST_PUBLISH_DATE);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Persists the given draft template and returns the managed message. */
    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Creates a fresh DRAFT NW (COASTAL_WARNING) message in series dma-nw. */
    private Message createDraftNw(String title) {
        return create(newDraft("dma-nw", Type.COASTAL_WARNING, title));
    }

    /** Transitions a message to the given status through a legal single-step transition. */
    private Message status(Message msg, Status target) {
        try {
            return messageService.updateStatus(msg.getUid(), target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Publishes a fresh NW draft (DRAFT -> VERIFIED -> PUBLISHED) and returns the published message. */
    private Message publishNw(String title) {
        Message msg = createDraftNw(title);
        status(msg, Status.VERIFIED);
        return status(msg, Status.PUBLISHED);
    }

    /** Builds a detached ACTIVE publication referencing the seeded publish=true category. */
    private Publication newPublication(PublicationStatus status, String categoryId) {
        Publication p = new Publication();
        p.setStatus(status);
        p.setMainType(PublicationMainType.PUBLICATION);
        p.setType(PublicationType.NONE);
        PublicationCategory cat = new PublicationCategory();
        cat.setCategoryId(categoryId);
        p.setCategory(cat);
        PublicationDesc desc = p.createDesc("en");
        desc.setTitle("IT API Title");
        return p;
    }

    private Publication createPublication(PublicationStatus status, String categoryId) {
        return publicationService.createPublication(newPublication(status, categoryId));
    }

    // ------------------------------------------------------------------------------------------- //
    // getMessage gate — the DRAFT-leak guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getMessageHidesDraft_documentsLeakGuard() {
        Message draft = createDraftNw("gate-draft-" + uniq());
        assertEquals(Status.DRAFT, draft.getStatus());

        assertNull(apiService.getMessage(draft.getUid()),
                "a DRAFT message must NOT be exposed by the public getMessage gate");
    }

    @Test
    @Transactional
    void getMessageHidesVerified() {
        Message verified = status(createDraftNw("gate-verified-" + uniq()), Status.VERIFIED);
        assertEquals(Status.VERIFIED, verified.getStatus());

        assertNull(apiService.getMessage(verified.getUid()),
                "a VERIFIED message is still non-public and must NOT be exposed");
    }

    @Test
    @Transactional
    void getMessageReturnsPublishedByUid() {
        Message published = publishNw("gate-published-" + uniq());
        assertEquals(Status.PUBLISHED, published.getStatus());

        Message gated = apiService.getMessage(published.getUid());
        assertNotNull(gated, "a PUBLISHED message must be exposed by the public gate");
        assertEquals(published.getUid(), gated.getUid());
        assertEquals(Status.PUBLISHED, gated.getStatus());
    }

    @Test
    @Transactional
    void getMessageResolvesPublishedByShortId() {
        Message published = publishNw("gate-shortid-" + uniq());
        String shortId = published.getShortId();
        assertNotNull(shortId, "publishing a NW message must assign a short id");

        Message gated = apiService.getMessage(shortId);
        assertNotNull(gated, "getMessage must resolve a public message by its short id");
        assertEquals(published.getUid(), gated.getUid(),
                "resolving by short id must return the same published message");
    }

    @Test
    @Transactional
    void getMessageReturnsCancelledMessage() {
        Message published = publishNw("gate-cancelled-" + uniq());
        Message cancelled = status(published, Status.CANCELLED);
        assertEquals(Status.CANCELLED, cancelled.getStatus());

        Message gated = apiService.getMessage(cancelled.getUid());
        assertNotNull(gated, "a CANCELLED message is public and must be exposed");
        assertEquals(Status.CANCELLED, gated.getStatus());
    }

    @Test
    @Transactional
    void getMessageReturnsExpiredMessage() {
        Message published = publishNw("gate-expired-" + uniq());
        Message expired = status(published, Status.EXPIRED);
        assertEquals(Status.EXPIRED, expired.getStatus());

        Message gated = apiService.getMessage(expired.getUid());
        assertNotNull(gated, "an EXPIRED message is public and must be exposed");
        assertEquals(Status.EXPIRED, gated.getStatus());
    }

    @Test
    @Transactional
    void getMessageReturnsNullForUnknownId() {
        assertNull(apiService.getMessage("no-such-id"),
                "an unknown message id must resolve to null, not throw");
    }

    @Test
    @Transactional
    void getMessageReturnsNullForBlankId() {
        assertNull(apiService.getMessage(""),
                "a blank message id must resolve to null");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchMessages gate — status enforcement & the empty-series branch
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchMessagesBySeriesReturnsOnlyPublishedNotDraft() throws Exception {
        String tag = uniq();
        Message published = publishNw("search-pub-" + tag);
        Message draft = createDraftNw("search-draft-" + tag);

        Set<String> uids = searchUids(null, null, Set.of("dma-nw"), null, null, null, null);

        assertTrue(uids.contains(published.getUid()),
                "the PUBLISHED message must appear in the public search");
        assertFalse(uids.contains(draft.getUid()),
                "the DRAFT message must NOT appear — status is forced to PUBLISHED when no tags given");
    }

    @Test
    @Transactional
    void searchMessagesBySeriesExcludesVerified() throws Exception {
        String tag = uniq();
        Message published = publishNw("search-pub2-" + tag);
        Message verified = status(createDraftNw("search-verified-" + tag), Status.VERIFIED);

        Set<String> uids = searchUids(null, null, Set.of("dma-nw"), null, null, null, null);

        assertTrue(uids.contains(published.getUid()));
        assertFalse(uids.contains(verified.getUid()),
                "a VERIFIED message must NOT appear in the public search");
    }

    @Test
    @Transactional
    void searchMessagesByDomainWithNoSeriesReturnsEmpty() throws Exception {
        // Pre-condition: niord-client-master has no message series in the seed, so the gate's
        // "no tags and no series => return nothing" branch must fire.
        publishNw("search-master-" + uniq()); // a public NW message exists, but must NOT be returned

        PagedSearchResultVo<Message> result = apiService.searchMessages(
                "en", Set.of("niord-client-master"), null, null, null, null, null);

        assertEquals(0, result.getTotal(),
                "a domain with no message series must yield an empty public result");
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @Transactional
    void searchMessagesByDomainNwReturnsPublishedNw() throws Exception {
        Message published = publishNw("search-domain-nw-" + uniq());

        Set<String> uids = searchUids("en", Set.of("niord-nw"), null, null, null, null, null);

        assertTrue(uids.contains(published.getUid()),
                "domain niord-nw maps to dma-nw and must surface the published NW message");
    }

    @Test
    @Transactional
    void searchMessagesMainTypeFilterMatchesNwButNotNm() throws Exception {
        Message published = publishNw("search-maintype-" + uniq());

        Set<String> nwUids = searchUids(null, null, Set.of("dma-nw"), null, null, Set.of(MainType.NW), null);
        assertTrue(nwUids.contains(published.getUid()),
                "a published NW message must match a mainTypes={NW} filter");

        Set<String> nmUids = searchUids(null, null, Set.of("dma-nw"), null, null, Set.of(MainType.NM), null);
        assertFalse(nmUids.contains(published.getUid()),
                "a published NW message must NOT match a mainTypes={NM} filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // ApiWebService.search / details — VO wrappers over the gate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchVoReturnsPublishedMessageVo() throws Exception {
        String title = "vo-search-" + uniq();
        Message published = publishNw(title);

        List<MessageVo> vos = apiService.search(
                "en", null, Set.of("dma-nw"), null, null, null, null);

        List<String> uids = vos.stream().map(MessageVo::getId).collect(Collectors.toList());
        assertTrue(uids.contains(published.getUid()),
                "search(..) must return a MessageVo for the published message");

        MessageVo vo = vos.stream()
                .filter(v -> published.getUid().equals(v.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(title, voTitle(vo),
                "the returned MessageVo must carry the localized title");
    }

    @Test
    @Transactional
    void searchVoOmitsDraftMessage() throws Exception {
        String tag = uniq();
        Message published = publishNw("vo-search-pub-" + tag);
        Message draft = createDraftNw("vo-search-draft-" + tag);

        List<String> uids = apiService.search("en", null, Set.of("dma-nw"), null, null, null, null)
                .stream().map(MessageVo::getId).collect(Collectors.toList());

        assertTrue(uids.contains(published.getUid()));
        assertFalse(uids.contains(draft.getUid()),
                "search(..) must not leak a DRAFT message as a VO");
    }

    @Test
    @Transactional
    void detailsReturnsVoForPublishedMessage() throws Exception {
        String title = "vo-details-" + uniq();
        Message published = publishNw(title);

        MessageVo vo = apiService.details("en", published.getUid());
        assertNotNull(vo, "details(..) must return a VO for a public message");
        assertEquals(published.getUid(), vo.getId());
        assertEquals(title, voTitle(vo), "details(..) must carry the localized title");
    }

    @Test
    @Transactional
    void detailsThrowsNotFoundForDraftMessage() {
        Message draft = createDraftNw("vo-details-draft-" + uniq());

        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> apiService.details("en", draft.getUid()),
                "details(..) on a non-public (DRAFT) message must throw NiordWebException (404)");
        assertEquals(404, ex.getStatusCode().value(),
                "a non-public message must be reported as HTTP 404");
        assertTrue(ex.getReason().contains(draft.getUid()),
                "the NiordWebException reason should reference the requested id");
    }

    @Test
    @Transactional
    void detailsThrowsNotFoundForMissingMessage() {
        NiordWebException missing = assertThrows(NiordWebException.class,
                () -> apiService.details("en", "missing-message-id"),
                "details(..) on a missing message must throw NiordWebException (404)");
        assertEquals(404, missing.getStatusCode().value(),
                "a missing message must be reported as HTTP 404");
    }

    // ------------------------------------------------------------------------------------------- //
    // getPublication gate — ACTIVE + category.publish
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPublicationReturnsActivePublishablePublication() {
        // dk-dma-publications has publish=true in the seed.
        Publication created = createPublication(PublicationStatus.ACTIVE, "dk-dma-publications");
        em.flush();
        String pubId = created.getPublicationId();

        PublicationVo gated = apiService.getPublication(pubId, null);
        assertNotNull(gated, "an ACTIVE publication in a publish=true category must be exposed");
        assertEquals(pubId, gated.getPublicationId());
        // PORT: getPublication now returns the public value object rather than the entity, because a
        // public publication can also come from a published issue of a publication series, which has
        // no Publication row behind it. PublicationVo carries no status -- being returned at all IS
        // the ACTIVE assertion, and the three tests below pin the other side of the same gate.
    }

    @Test
    @Transactional
    void getPublicationHidesDraftPublication() {
        // Same publishable category, but status DRAFT => must be hidden by the gate.
        Publication created = createPublication(PublicationStatus.DRAFT, "dk-dma-publications");
        em.flush();

        assertNull(apiService.getPublication(created.getPublicationId(), null),
                "a non-ACTIVE (DRAFT) publication must NOT be exposed by the public gate");
    }

    @Test
    @Transactional
    void getPublicationHidesActiveButNonPublishableCategory() {
        // dk-external-publications has publish=false => even an ACTIVE publication must be hidden.
        Publication created = createPublication(PublicationStatus.ACTIVE, "dk-external-publications");
        em.flush();

        assertNull(apiService.getPublication(created.getPublicationId(), null),
                "an ACTIVE publication in a publish=false category must NOT be exposed");
    }

    @Test
    @Transactional
    void getPublicationReturnsNullForUnknownId() {
        assertNull(apiService.getPublication("no-such-publication-id", null),
                "an unknown publication id must resolve to null, not throw");
    }

    // ------------------------------------------------------------------------------------------- //
    // getArea gate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAreaResolvesSeededAreaByNumericId() {
        // Postgres assigns its own per-entity ids, so the legacy MySQL numeric id (1192) is gone.
        // Resolve the seeded DK-country root area by its stable MRN business key, then verify that
        // getArea(numericId) resolves the very same area by that numeric id.
        Integer seededId = seedAreaIdByMrn(SEED_AREA_MRN);
        Area area = apiService.getArea(String.valueOf(seededId));
        assertNotNull(area, "the seeded area must be resolvable by its numeric id");
        assertEquals(seededId, area.getId());
        assertEquals(SEED_AREA_MRN, area.getMrn(),
                "the resolved area must be the MRN-identified seed area");
    }

    @Test
    @Transactional
    void getAreaReturnsNullForUnknownArea() {
        assertNull(apiService.getArea("no-such-area"),
                "an unknown (non-numeric, unmatched MRN) area id must resolve to null");
    }

    @Test
    @Transactional
    void getAreaReturnsNullForUnknownNumericId() {
        assertNull(apiService.getArea("99999999"),
                "an unknown numeric area id must resolve to null");
    }

    // ------------------------------------------------------------------------------------------- //
    // Internal helpers
    // ------------------------------------------------------------------------------------------- //

    /** Runs the gate search and returns the matched message uids as a set. */
    private Set<String> searchUids(String lang, Set<String> domainIds, Set<String> series,
                                   Set<String> publicationIds, Set<String> areaIds,
                                   Set<MainType> mainTypes, String wkt) throws Exception {
        return apiService.searchMessages(lang, domainIds, series, publicationIds, areaIds, mainTypes, wkt)
                .getData().stream()
                .map(Message::getUid)
                .collect(Collectors.toSet());
    }

    /** Extracts the "en" title from a MessageVo (the public details filter is "en"). */
    private static String voTitle(MessageVo vo) {
        assertNotNull(vo.getDescs(), "a details VO must carry descriptions");
        return vo.getDescs().stream()
                .filter(d -> "en".equals(d.getLang()))
                .map(d -> d.getTitle())
                .findFirst()
                .orElse(null);
    }

    /** A short unique token so titles/series rows never collide across concurrently-running tests. */
    private static String uniq() {
        return Long.toString(System.nanoTime(), 36);
    }
}
