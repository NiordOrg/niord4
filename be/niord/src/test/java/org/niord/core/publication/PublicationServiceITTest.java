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
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.core.util.UidUtils;
import org.niord.model.publication.PublicationType;
import org.niord.test.ITTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PublicationService} — focused on the publication <b>state machine</b>
 * ({@code updateStatus}) and on the persistence/<b>filesystem</b> (hashed repo-path) side of
 * {@code createPublication} / {@code deletePublication} — run against the seeded MySQL container of
 * the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes roll back, keeping the shared seed
 * database pristine and the tests order-independent. Newly created publications use no fixed id, so the
 * service assigns a fresh UUID and a {@code publications/x/yy/uuid} repo path that cannot collide with
 * the eight seeded publications.
 *
 * <p>The {@code updateStatus} validation matrix under test (from the production switch):
 * <ul>
 *   <li>{@code -> DRAFT}: only from RECORDING, for a PUBLICATION/MESSAGE_REPORT.</li>
 *   <li>{@code -> RECORDING}: only from DRAFT, for a PUBLICATION/MESSAGE_REPORT with a message tag.</li>
 *   <li>{@code -> ACTIVE}: from any non-ACTIVE state, if it is a TEMPLATE, or type NONE, or every
 *       description has a non-blank link.</li>
 *   <li>{@code -> INACTIVE}: only from ACTIVE.</li>
 * </ul>
 * An invalid transition throws a (checked) {@link Exception}; a non-existing publication throws
 * {@link IllegalArgumentException}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PublicationServiceITTest extends ITTest {

    /**
     * The seeded, stable publication category that owns exactly one ACTIVE PUBLICATION/LINK
     * publication. The publication's own {@code publicationId} (a UUID) and {@code repoPath} are
     * regenerated each time the seed is rebuilt, so we resolve them at runtime via this stable
     * business key rather than hard-coding them.
     */
    private static final String SEEDED_PUB_CATEGORY = "dk-external-publications";

    @Autowired
    PublicationService service;

    @PersistenceContext
    EntityManager em;

    /** Resolves the single seeded publication in the {@link #SEEDED_PUB_CATEGORY} category. */
    private Publication resolveSeededPublication() {
        PublicationSearchParams params = new PublicationSearchParams();
        params.category(SEEDED_PUB_CATEGORY);
        return service.searchPublications(params).getData().stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a seeded publication in category " + SEEDED_PUB_CATEGORY));
    }

    /** Builds a detached, brand-new publication referencing a seeded publication category. */
    private Publication newPublication(PublicationStatus status, PublicationMainType mainType, PublicationType type) {
        Publication p = new Publication();
        p.setStatus(status);
        p.setMainType(mainType);
        p.setType(type);
        PublicationCategory cat = new PublicationCategory();
        cat.setCategoryId("dk-dma-publications"); // seeded category id 1210
        p.setCategory(cat);
        return p;
    }

    /** Adds an English description with the given (possibly blank) link. */
    private void addLinkDesc(Publication p, String link) {
        PublicationDesc desc = p.createDesc("en");
        desc.setTitle("IT Title");
        desc.setLink(link);
    }

    // --------------------------------------------------------------------------------------------- //
    // createPublication — persistence + hashed repo path (filesystem layout)
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createAssignsUuidAndHashedRepoPathAndPersists() {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();
        assertNotNull(id, "createPublication must assign a generated database id");

        String pubId = created.getPublicationId();
        assertTrue(UidUtils.validUidFormat(pubId), "a fresh UUID must be assigned");
        assertEquals(UidUtils.uidToHashedFolderPath(Publication.PUBLICATION_REPO_FOLDER, pubId),
                created.getRepoPath(), "repo path must be the hashed-folder mapping of the id");

        em.flush();
        em.clear();

        Publication reloaded = em.find(Publication.class, id);
        assertNotNull(reloaded, "the publication row must exist in the database");
        assertEquals(pubId, reloaded.getPublicationId());
        assertEquals(created.getRepoPath(), reloaded.getRepoPath());
        assertEquals(PublicationStatus.DRAFT, reloaded.getStatus());
    }

    @Test
    @Transactional
    void createSubstitutesPersistedCategory() {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));

        // The detached category template must be swapped for the managed seeded category (id 1210).
        assertNotNull(created.getCategory().getId());
        assertEquals("dk-dma-publications", created.getCategory().getCategoryId());

        em.flush();
        em.clear();

        Publication reloaded = em.find(Publication.class, created.getId());
        assertEquals("dk-dma-publications", reloaded.getCategory().getCategoryId());
    }

    @Test
    @Transactional
    void createRejectsPublicationThatAlreadyHasAnId() {
        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE);
        p.setId(123_456); // isNew() == false

        assertThrows(IllegalArgumentException.class, () -> service.createPublication(p));
    }

    // --------------------------------------------------------------------------------------------- //
    // findByPublicationId / delete — stable seeded reads and removal
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByPublicationIdReadsStableSeededPublication() {
        // Resolve the seeded publication via its stable category, then re-read it by its publicationId
        // to exercise findByPublicationId against a known-good business key.
        String pubId = resolveSeededPublication().getPublicationId();

        Publication seeded = service.findByPublicationId(pubId);
        assertNotNull(seeded, "the seeded publication must exist");
        assertEquals(PublicationStatus.ACTIVE, seeded.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, seeded.getMainType());
        assertEquals(SEEDED_PUB_CATEGORY, seeded.getCategory().getCategoryId());
        // The repo path is the hashed-folder mapping of the (regenerated) publicationId.
        assertEquals(UidUtils.uidToHashedFolderPath(Publication.PUBLICATION_REPO_FOLDER, pubId),
                seeded.getRepoPath(), "repo path must be the hashed-folder mapping of the publicationId");
    }

    @Test
    @Transactional
    void findByPublicationIdReturnsNullForUnknownId() {
        assertNull(service.findByPublicationId("IT-no-such-publication"));
    }

    @Test
    @Transactional
    void deleteRemovesCreatedPublicationFromDatabase() {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();
        String pubId = created.getPublicationId();
        em.flush();

        assertTrue(service.deletePublication(pubId));
        em.flush();
        em.clear();

        assertNull(em.find(Publication.class, id), "deleted publication must be gone from the DB");
        assertNull(service.findByPublicationId(pubId));
    }

    @Test
    @Transactional
    void deleteReturnsFalseForUnknownId() {
        assertFalse(service.deletePublication("IT-no-such-publication"));
    }

    // --------------------------------------------------------------------------------------------- //
    // updateStatus — valid transitions
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void draftToActiveAllowedForTypeNone() throws Exception {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE);

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void draftToActiveAllowedForLinkWithAllLinksSet() throws Exception {
        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        addLinkDesc(p, "http://example.dk/doc.pdf");
        Publication created = service.createPublication(p);
        Integer id = created.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE);

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void activeToInactiveAllowed() throws Exception {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.INACTIVE);

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.INACTIVE, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void inactiveToActiveAllowed() throws Exception {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.INACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE);

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void templateToActiveAllowedEvenWithBlankLink() throws Exception {
        // mainType TEMPLATE short-circuits the per-description link check, so a blank link is fine.
        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.TEMPLATE, PublicationType.LINK);
        addLinkDesc(p, ""); // blank link that would fail the PUBLICATION link check
        Publication created = service.createPublication(p);
        Integer id = created.getId();

        service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE);

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
    }

    // --------------------------------------------------------------------------------------------- //
    // updateStatus — invalid transitions
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void draftToActiveRejectedForLinkWithBlankLink() {
        Publication p = newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK);
        addLinkDesc(p, ""); // blank link -> not all descriptions have a link
        Publication created = service.createPublication(p);
        Integer id = created.getId();

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE));

        // The refusal comes before any change (PUB1: an IllegalStateException, which also rolls back), so the status
        // read back is unchanged.
        em.flush();
        em.clear();
        assertEquals(PublicationStatus.DRAFT, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void activeToActiveRejectedAndStatusUnchanged() {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.ACTIVE, PublicationMainType.PUBLICATION, PublicationType.NONE));
        Integer id = created.getId();

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(created.getPublicationId(), PublicationStatus.ACTIVE));

        em.flush();
        em.clear();
        assertEquals(PublicationStatus.ACTIVE, em.find(Publication.class, id).getStatus());
    }

    @Test
    @Transactional
    void draftToDraftRejected() {
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.NONE));

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(created.getPublicationId(), PublicationStatus.DRAFT));
    }

    @Test
    @Transactional
    void draftToRecordingRejectedWithoutMessageTag() {
        // RECORDING requires a MESSAGE_REPORT publication AND a non-null message tag.
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.MESSAGE_REPORT));

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(created.getPublicationId(), PublicationStatus.RECORDING));
    }

    @Test
    @Transactional
    void draftToRecordingRejectedForWrongType() {
        // Even with otherwise-valid state, a LINK publication can never enter RECORDING.
        Publication created = service.createPublication(
                newPublication(PublicationStatus.DRAFT, PublicationMainType.PUBLICATION, PublicationType.LINK));

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(created.getPublicationId(), PublicationStatus.RECORDING));
    }

    @Test
    @Transactional
    void updateStatusThrowsForNonExistingPublication() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus("IT-no-such-publication", PublicationStatus.ACTIVE));
    }

    // --------------------------------------------------------------------------------------------- //
    // searchPublications
    // --------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByCategoryReturnsMatchingSeededPublication() {
        PublicationSearchParams params = new PublicationSearchParams();
        params.category(SEEDED_PUB_CATEGORY); // stable category -> exactly one seeded publication

        var result = service.searchPublications(params);

        assertFalse(result.getData().isEmpty(),
                "search by category must return the seeded publication in that category");
        assertTrue(result.getData().stream()
                        .allMatch(p -> SEEDED_PUB_CATEGORY.equals(p.getCategory().getCategoryId())),
                "the category filter must only return publications in that category");
    }

    @Test
    @Transactional
    void searchByMainTypeTemplateReturnsOnlyTemplates() {
        PublicationSearchParams params = new PublicationSearchParams();
        params.mainType(PublicationMainType.TEMPLATE);

        var result = service.searchPublications(params);

        assertFalse(result.getData().isEmpty(), "the seed data contains template publications");
        assertTrue(result.getData().stream().allMatch(p -> p.getMainType() == PublicationMainType.TEMPLATE),
                "main-type filter must exclude non-template publications");
    }
}
