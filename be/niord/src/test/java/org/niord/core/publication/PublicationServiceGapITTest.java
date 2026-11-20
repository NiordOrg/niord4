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
package org.niord.core.publication;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.model.publication.PublicationType;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PublicationService} focused on the behaviour <b>not</b> already covered
 * by the sibling {@code PublicationServiceITTest}: the <b>template-cascade</b> path of
 * {@code updatePublication}/{@code updateFromTemplate}, the related-entity substitution performed by
 * {@code updateRelatedEntities}, the message-tag side effects of {@code updateStatus}
 * (RECORDING start-recording, ACTIVE tag locking), and the various finder/search queries
 * ({@code searchPagedPublicationIds} paging, {@code searchPublications} filters, {@code findByIds}
 * ordering, {@code findByPublicationIds} de-duplication, {@code findByTemplateId}).
 *
 * <p>Every test runs inside a {@link TestTransaction}; all writes roll back so the shared seed database
 * stays pristine and the tests are order-independent. The service's {@code @Transactional(REQUIRED)}
 * methods join the test transaction, so writes are visible through the injected {@link EntityManager}
 * after a flush, and disappear when the test rolls back.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class PublicationServiceGapITTest extends ITTest {

    /** The fixture's publication category id 2, a row of the sample. */
    private static final String SEEDED_CATEGORY = "dk-dma-publications";
    /** The fixture's publication category id 1, a row of the sample. */
    private static final String SEEDED_CATEGORY_EXTERNAL = "dk-external-publications";
    /** The fixture's domain id 2, a row of the sample. */
    private static final String SEEDED_DOMAIN = "niord-nw";

    @Autowired
    PublicationService service;

    @PersistenceContext
    EntityManager em;

    @Autowired
    MessageTagService messageTagService;

    // --------------------------------------------------------------------------------------------- //
    // Fixtures
    // --------------------------------------------------------------------------------------------- //

    /** A detached publication with a detached (categoryId-only) seeded category reference. */
    private Publication newPublication(PublicationStatus status, PublicationMainType mainType, PublicationType type) {
        Publication p = new Publication();
        p.setStatus(status);
        p.setMainType(mainType);
        p.setType(type);
        PublicationCategory cat = new PublicationCategory();
        cat.setCategoryId(SEEDED_CATEGORY);
        p.setCategory(cat);
        return p;
    }

    /** Adds an English description with the given title and link. */
    private void addDesc(Publication p, String title, String link) {
        PublicationDesc desc = p.createDesc("en");
        desc.setTitle(title);
        desc.setLink(link);
    }

    /** Creates and persists a brand-new, unlocked, PUBLIC message tag and returns it (managed). */
    private MessageTag createPublicTag(String name) {
        MessageTag tag = new MessageTag();
        tag.checkAssignTagId();
        tag.setName(name);
        tag.setType(MessageTagType.PUBLIC);
        tag.setLocked(false);
        em.persist(tag);
        em.flush();
        return tag;
    }

    // --------------------------------------------------------------------------------------------- //
    // updatePublication - template cascade
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateTemplateCascadesNewFieldIntoDraftChild() {
        // A TEMPLATE whose messagePublication starts at NONE.
        Publication template = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.TEMPLATE, PublicationType.LINK));
        String templateId = template.getPublicationId();

        // A draft PUBLICATION child pointing at the template; on create it inherits NONE.
        Publication childInput = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        childInput.setTemplate(template);
        Publication child = service.createPublication(childInput);
        Integer childId = child.getId();
        assertEquals(MessagePublication.NONE, child.getMessagePublication(),
                "child must initially inherit the template's NONE messagePublication");

        // Change a field on the template and push the update through the service.
        Publication templateUpdate = newPublication(PublicationStatus.DRAFT, PublicationMainType.TEMPLATE, PublicationType.LINK);
        templateUpdate.setPublicationId(templateId);
        templateUpdate.setMessagePublication(MessagePublication.EXTERNAL);
        service.updatePublication(templateUpdate);

        em.flush();
        em.clear();

        // The cascade (findByTemplateId -> updateFromTemplate) must have rewritten the draft child.
        Publication reloadedChild = em.find(Publication.class, childId);
        assertEquals(MessagePublication.EXTERNAL, reloadedChild.getMessagePublication(),
                "updating a TEMPLATE field must cascade into its draft children");
    }

    @Test
    @Transactional
    void updateChildReappliesTemplateOverridingChildChange() {
        // A TEMPLATE forcing messagePublication = EXTERNAL.
        Publication templateInput = newPublication(PublicationStatus.DRAFT, PublicationMainType.TEMPLATE, PublicationType.LINK);
        templateInput.setMessagePublication(MessagePublication.EXTERNAL);
        Publication template = service.createPublication(templateInput);
        String templateId = template.getPublicationId();

        Publication childInput = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        childInput.setTemplate(template);
        Publication child = service.createPublication(childInput);
        Integer childId = child.getId();
        String childPubId = child.getPublicationId();
        assertEquals(MessagePublication.EXTERNAL, child.getMessagePublication());

        // Try to update the child to INTERNAL - the template must win and reset it to EXTERNAL.
        Publication childUpdate = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        childUpdate.setPublicationId(childPubId);
        Publication templateRef = new Publication();
        templateRef.setPublicationId(templateId);
        childUpdate.setTemplate(templateRef);
        childUpdate.setMessagePublication(MessagePublication.INTERNAL);
        service.updatePublication(childUpdate);

        em.flush();
        em.clear();

        Publication reloadedChild = em.find(Publication.class, childId);
        assertEquals(MessagePublication.EXTERNAL, reloadedChild.getMessagePublication(),
                "the template must re-override the child's own change on update");
    }

    @Test
    @Transactional
    void updateThrowsForNonExistingPublication() {
        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE);
        p.setPublicationId("IT-no-such-publication-" + System.nanoTime());

        assertThrows(IllegalArgumentException.class, () -> service.updatePublication(p));
    }

    @Test
    @Transactional
    void updateSubstitutesManagedCategoryAndDomainForDetachedTemplates() {
        // Seed a publication carrying detached (id-less) category + domain references.
        Publication p = newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE);
        Domain detachedDomain = new Domain();
        detachedDomain.setDomainId(SEEDED_DOMAIN);
        p.setDomain(detachedDomain);
        Publication created = service.createPublication(p);
        Integer id = created.getId();
        String pubId = created.getPublicationId();
        em.flush();
        em.clear();

        // Update with brand-new detached category + domain objects (no DB id).
        Publication update = newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE);
        update.setPublicationId(pubId);
        Domain detachedDomain2 = new Domain();
        detachedDomain2.setDomainId(SEEDED_DOMAIN);
        update.setDomain(detachedDomain2);
        Publication result = service.updatePublication(update);

        // updateRelatedEntities must have swapped in the managed (id-bearing) entities.
        assertNotNull(result.getCategory().getId(), "detached category must be replaced by the managed one");
        assertEquals(SEEDED_CATEGORY, result.getCategory().getCategoryId());
        assertNotNull(result.getDomain().getId(), "detached domain must be replaced by the managed one");
        assertEquals(SEEDED_DOMAIN, result.getDomain().getDomainId());

        em.flush();
        em.clear();

        Publication reloaded = em.find(Publication.class, id);
        assertEquals(SEEDED_CATEGORY, reloaded.getCategory().getCategoryId());
        assertEquals(SEEDED_DOMAIN, reloaded.getDomain().getDomainId());
    }

    // --------------------------------------------------------------------------------------------- //
    // updateStatus - message-tag side effects
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void draftToRecordingPersistsRecordingAndRunsStartRecording() throws Exception {
        MessageTag tag = createPublicTag("IT-recording-tag-" + System.nanoTime());

        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.MESSAGE_REPORT);
        p.setMessageTag(tag);
        // A non-blank filter makes startRecordingPublication actually run its message scan; the uid
        // clause makes that scan match nothing DETERMINISTICALLY. newPublication() sets no domain, so
        // startRecordingPublication passes an empty seriesId set (PublicationService:522-529) and the
        // scan is therefore database-WIDE. A bare "msg.status == Status.PUBLISHED" would consequently
        // pick up every published message any earlier IT class left committed, making this assertion
        // pass or fail purely on Failsafe class order - green on macOS, red on Linux CI. Same trap as
        // ContactServiceITTest's blank-name search once fell into.
        String unmatchableUid = "IT-no-such-message-" + System.nanoTime();
        p.setMessageTagFilter("msg.status == Status.PUBLISHED && msg.uid == '" + unmatchableUid + "'");
        Publication created = service.createPublication(p);
        Integer id = created.getId();
        Integer tagId = tag.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.RECORDING);

        em.flush();
        em.clear();

        Publication reloaded = em.find(Publication.class, id);
        assertEquals(PublicationStatus.RECORDING, reloaded.getStatus(),
                "DRAFT -> RECORDING must be persisted");

        // RECORDING does not lock the tag (only ACTIVE/INACTIVE do); startRecordingPublication scanned
        // the published messages, matched none of them against the filter, and left the tag empty.
        MessageTag reloadedTag = em.find(MessageTag.class, tagId);
        assertFalse(reloadedTag.isLocked(), "RECORDING must not lock the associated message tag");
        assertEquals(0, reloadedTag.getMessageCount(),
                "the filter matches no message, so start-recording adds nothing to the tag");
        assertTrue(reloadedTag.getMessages().isEmpty());
    }

    @Test
    @Transactional
    void recordingToActiveLocksTheMessageTag() throws Exception {
        MessageTag tag = createPublicTag("IT-active-lock-tag-" + System.nanoTime());

        Publication p = newPublication(PublicationStatus.RECORDING, PublicationMainType.PUBLICATION, PublicationType.MESSAGE_REPORT);
        p.setMessageTag(tag);
        Publication created = service.createPublication(p);
        Integer id = created.getId();
        Integer tagId = tag.getId();
        assertFalse(em.find(MessageTag.class, tagId).isLocked(), "precondition: tag starts unlocked");

        // MESSAGE_REPORT with no description links may still go ACTIVE if all descs have links;
        // here there are no descs, so allMatch(...) is vacuously true -> transition allowed.
        service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE);

        em.flush();
        em.clear();

        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
        assertTrue(em.find(MessageTag.class, tagId).isLocked(),
                "activating a publication must lock its associated message tag");
    }

    // --------------------------------------------------------------------------------------------- //
    // searchPagedPublicationIds - paging semantics
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchPagedReturnsOnePerPageWhileReportingFullTotal() {
        PublicationSearchParams params = new PublicationSearchParams();
        params.maxSize(1);
        params.page(0);

        PagedSearchResultVo<Publication> result = new PagedSearchResultVo<>();
        List<Integer> firstPage = service.searchPagedPublicationIds(params, result);

        long total = result.getTotal();
        assertTrue(total >= 8, "the seed data contains at least the eight seeded publications, got " + total);
        assertEquals(1, firstPage.size(), "maxSize=1 must yield a single id on the first page");

        // A page index far beyond the result set must come back empty, while the total is unchanged.
        PublicationSearchParams beyond = new PublicationSearchParams();
        beyond.maxSize(1);
        beyond.page((int) total + 5);
        PagedSearchResultVo<Publication> beyondResult = new PagedSearchResultVo<>();
        List<Integer> beyondPage = service.searchPagedPublicationIds(beyond, beyondResult);

        assertTrue(beyondPage.isEmpty(), "a page index beyond the result range must be empty");
        assertEquals(total, beyondResult.getTotal(),
                "the reported total must reflect all matches regardless of paging");
    }

    // --------------------------------------------------------------------------------------------- //
    // searchPublications - filter combinations
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByStatusTypeMessagePublicationAndTitleMatchesCreatedPublication() {
        String uniqueTitle = "IT-Gap-SearchTitle-" + System.nanoTime();

        Publication p = newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.LINK);
        p.setMessagePublication(MessagePublication.EXTERNAL);
        addDesc(p, uniqueTitle, "http://example.dk/it.pdf");
        Publication created = service.createPublication(p);
        em.flush();

        PublicationSearchParams params = new PublicationSearchParams();
        params.statuses(PublicationStatus.ACTIVE);
        params.type(PublicationType.LINK);
        params.messagePublication(MessagePublication.EXTERNAL);
        params.title(uniqueTitle.substring(0, 12)); // 'like' prefix match

        PagedSearchResultVo<Publication> result = service.searchPublications(params);

        assertEquals(1, result.getData().size(),
                "the combined filter must match exactly the one publication just created");
        assertEquals(created.getPublicationId(), result.getData().get(0).getPublicationId());
    }

    @Test
    @Transactional
    void searchByMessagePublicationExcludesNonMatching() {
        // A publication with messagePublication = NONE must be excluded by an EXTERNAL filter.
        String title = "IT-Gap-NoneMsgPub-" + System.nanoTime();
        Publication p = newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.LINK);
        p.setMessagePublication(MessagePublication.NONE);
        addDesc(p, title, "http://example.dk/none.pdf");
        service.createPublication(p);
        em.flush();

        PublicationSearchParams params = new PublicationSearchParams();
        params.messagePublication(MessagePublication.EXTERNAL);
        params.title(title);

        PagedSearchResultVo<Publication> result = service.searchPublications(params);
        assertTrue(result.getData().isEmpty(),
                "an EXTERNAL messagePublication filter must exclude a NONE publication");
    }

    @Test
    @Transactional
    void searchByPublishDateOverlapMatchesAndMisses() {
        // A publication active only during a narrow window in the year 3000.
        Calendar cal = Calendar.getInstance();
        cal.set(3000, Calendar.MARCH, 10, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date from = cal.getTime();
        cal.set(3000, Calendar.MARCH, 20, 0, 0, 0);
        Date to = cal.getTime();

        String title = "IT-Gap-DateOverlap-" + System.nanoTime();
        Publication p = newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE);
        p.setPublishDateFrom(from);
        p.setPublishDateTo(to);
        addDesc(p, title, "");
        Publication created = service.createPublication(p);
        em.flush();

        // An overlapping query window must include it.
        cal.set(3000, Calendar.MARCH, 15, 0, 0, 0);
        Date queryFrom = cal.getTime();
        cal.set(3000, Calendar.MARCH, 25, 0, 0, 0);
        Date queryTo = cal.getTime();
        PublicationSearchParams overlapping = new PublicationSearchParams();
        overlapping.title(title);
        overlapping.from(queryFrom);
        overlapping.to(queryTo);
        PagedSearchResultVo<Publication> hit = service.searchPublications(overlapping);
        assertEquals(1, hit.getData().size(), "an overlapping date window must match");
        assertEquals(created.getPublicationId(), hit.getData().get(0).getPublicationId());

        // A non-overlapping window (entirely before the publication) must miss.
        cal.set(3000, Calendar.JANUARY, 1, 0, 0, 0);
        Date missFrom = cal.getTime();
        cal.set(3000, Calendar.JANUARY, 31, 0, 0, 0);
        Date missTo = cal.getTime();
        PublicationSearchParams nonOverlapping = new PublicationSearchParams();
        nonOverlapping.title(title);
        nonOverlapping.from(missFrom);
        nonOverlapping.to(missTo);
        PagedSearchResultVo<Publication> miss = service.searchPublications(nonOverlapping);
        assertTrue(miss.getData().isEmpty(), "a non-overlapping date window must not match");
    }

    // --------------------------------------------------------------------------------------------- //
    // finder queries
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByIdsPreservesInputOrder() {
        Publication a = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Publication b = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Publication c = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        em.flush();

        // Request in a deliberately scrambled order; the result must mirror the requested order.
        List<Integer> requested = List.of(c.getId(), a.getId(), b.getId());
        List<Publication> found = service.findByIds(requested);

        assertEquals(3, found.size());
        assertEquals(requested,
                found.stream().map(Publication::getId).toList(),
                "findByIds must return publications in the order of the supplied id list");
    }

    @Test
    @Transactional
    void findByIdsReturnsEmptyForNullOrEmpty() {
        assertTrue(service.findByIds(null).isEmpty());
        assertTrue(service.findByIds(List.of()).isEmpty());
    }

    @Test
    @Transactional
    void findByPublicationIdsDedupsDuplicateInput() {
        Publication a = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        em.flush();
        String pubId = a.getPublicationId();

        // The same id three times must yield exactly one row (the named query uses an IN over a Set).
        List<Publication> found = service.findByPublicationIds(pubId, pubId, pubId);

        assertEquals(1, found.size(), "duplicate publication ids must be de-duplicated");
        assertEquals(pubId, found.get(0).getPublicationId());
    }

    @Test
    @Transactional
    void findByPublicationIdsReturnsEmptyForNoArgs() {
        assertTrue(service.findByPublicationIds().isEmpty());
    }

    @Test
    @Transactional
    void findByTemplateIdReturnsOnlyMatchingChildren() {
        Publication template = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.TEMPLATE, PublicationType.LINK));
        String templateId = template.getPublicationId();

        Publication childInput = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        childInput.setTemplate(template);
        Publication child = service.createPublication(childInput);

        // An unrelated publication with no template must NOT show up.
        Publication unrelated = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        em.flush();

        List<Publication> matches = service.findByTemplateId(templateId);

        assertEquals(1, matches.size(), "only the single template child must match");
        assertEquals(child.getPublicationId(), matches.get(0).getPublicationId());
        assertFalse(matches.stream().anyMatch(p -> p.getPublicationId().equals(unrelated.getPublicationId())),
                "a publication without that template must be excluded");
    }

}
