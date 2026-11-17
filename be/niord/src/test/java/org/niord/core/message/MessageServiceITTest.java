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
package org.niord.core.message;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageService} persistence and lifecycle behaviour, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The seed data contains <em>no</em> {@code Message} rows, so every test creates the messages it
 * needs. Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent and repeatable. Persisted state is asserted
 * after {@code em.flush()}/{@code em.clear()} reloads, so we observe what really hit the database
 * rather than the in-memory object graph.
 *
 * <p>The tests cover: {@link MessageService#createMessage} validation and field substitution
 * (message series, main type, areas/categories/charts, repo path, generated uid, default status);
 * {@link MessageService#updateMessage} mutation and status guarding; the
 * {@link MessageService#updateStatus} state machine and its publish/expire/cancel side effects;
 * look-ups ({@code findByUid}, {@code findByLegacyId}, {@code findByShortId}, {@code resolveMessage});
 * {@link MessageService#getReferencedMessages} filtering; {@link MessageService#newTemplateMessage};
 * {@link MessageService#changeAreaSortOrder}; {@link MessageService#getSeparatePageUids}; and the
 * message history written by {@link MessageService#saveHistory}.
 *
 * <p>Note on number sequences: publishing a draft takes a message number from the series' yearly
 * sequence inside the publishing transaction, which here is the test's {@link TestTransaction}, so
 * the number rolls back with the test (SERIES6). These tests assert only the <em>structure</em> of
 * the resulting short ID (its prefix and that a number/year were assigned), never an absolute number,
 * so they do not depend on what other suites left in the sequence.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageServiceITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * A fixed PAST publish date. Publishing a draft takes a message number from the YEARLY sequence of
     * its series inside the test's {@link TestTransaction}: the number rolls back with the test, and the
     * test's transaction holds that year's number row until then. By stamping every message with a
     * far-past publish date, the publish flow only ever touches the year-2001 row, never the current-year
     * row that other suites (e.g. {@code MessageSeriesRestServiceITTest}) assert starts at 1.
     * {@code updateStatus} keeps a non-null, non-future publish date, so this value survives the publish
     * transition.
     */
    private static final Date PAST_PUBLISH_DATE = pastPublishDate();

    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached, un-persisted draft message template with one DETAILS part and an "en" title. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries(seriesId); // createMessage substitutes the persisted one
        if (type != null) {
            // Mirror a real caller (a SystemMessageVo always carries the series main type): updateMessage
            // validates the type against this detached series, so it must expose a main type.
            series.setMainType(type.getMainType());
        }
        msg.setMessageSeries(series);
        msg.setType(type);                                  // mainType derived from type
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(PAST_PUBLISH_DATE);          // keep publishes off the current-year sequence
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates and persists a fresh DRAFT NW message and returns the managed entity. */
    private Message createDraftNw(String title) throws Exception {
        return messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, title));
    }

    /** Reloads a message from the database by primary key after flushing and clearing the context. */
    private Message reload(Integer id) {
        em.flush();
        em.clear();
        return em.find(Message.class, id);
    }

    /**
     * Resolves the seeded "Denmark" area (the root of the Danish area tree) by its stable name and
     * returns the managed entity. PostgreSQL's per-entity sequences assign different numeric ids than
     * the old MySQL shared sequence, so seed ids (formerly area 1192) are no longer stable; tests must
     * look the entity up by a business key (its name) instead of hard-coding the id.
     */
    private Area seededArea() {
        Area area = areaService.findByName("Denmark", "en", null);
        assertNotNull(area, "seeded area 'Denmark' must exist");
        return area;
    }

    /**
     * Resolves the seeded "Light" category by its stable name (formerly category 1802) and returns the
     * managed entity. See {@link #seededArea()} for why the numeric id is no longer used.
     */
    private Category seededCategory() {
        Category category = categoryService.findByName("Light", "en", null);
        assertNotNull(category, "seeded category 'Light' must exist");
        return category;
    }

    // ------------------------------------------------------------------------------------------- //
    // createMessage - happy path & field substitution
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMessageAssignsGeneratedIdUidRepoPathAndDefaultsStatusToDraft() throws Exception {
        Message created = createDraftNw("Title A");

        assertNotNull(created.getId(), "createMessage must assign a generated primary key");
        assertNotNull(created.getUid(), "createMessage must assign a uid");
        assertNotNull(created.getRepoPath(), "createMessage must assign a repo path");
        assertTrue(created.getRepoPath().startsWith(Message.MESSAGE_REPO_FOLDER),
                "repo path must be rooted under the message repo folder");
        assertEquals(Status.DRAFT, created.getStatus(), "a new message must default to DRAFT");

        Message reloaded = reload(created.getId());
        assertNotNull(reloaded, "the message row must exist in the database");
        assertEquals(created.getUid(), reloaded.getUid());
        assertEquals(Status.DRAFT, reloaded.getStatus());
        assertEquals(created.getRepoPath(), reloaded.getRepoPath());
    }

    @Test
    @Transactional
    void createMessageLeavesCreatedByNullWhenNoAuthenticatedUser() throws Exception {
        Message created = createDraftNw("No author");
        Message reloaded = reload(created.getId());
        assertNull(reloaded.getCreatedBy(), "without an authenticated user createdBy must be null");
        assertNull(reloaded.getLastUpdatedBy(), "lastUpdatedBy must also remain null");
    }

    @Test
    @Transactional
    void createMessageSubstitutesDetachedSeriesWithPersistedSeededSeries() throws Exception {
        Message created = createDraftNw("Series substitution");

        // The detached new MessageSeries("dma-nw") must have been replaced by the seeded entity. Assert on
        // the stable business key (seriesId) rather than the generated numeric id, which is not stable across
        // the PostgreSQL per-entity-sequence seed.
        assertNotNull(created.getMessageSeries().getId(),
                "the substituted message series must be a persisted entity with an id");
        assertEquals("dma-nw", created.getMessageSeries().getSeriesId(),
                "the substituted series must be the seeded dma-nw series");

        Message reloaded = reload(created.getId());
        assertEquals("dma-nw", reloaded.getMessageSeries().getSeriesId());
        assertEquals(created.getMessageSeries().getId(), reloaded.getMessageSeries().getId());
    }

    @Test
    @Transactional
    void createMessageDerivesMainTypeFromType() throws Exception {
        Message created = createDraftNw("Main type derivation");
        assertEquals(MainType.NW, created.getMainType(), "mainType must be derived from the NW type");

        Message reloaded = reload(created.getId());
        assertEquals(MainType.NW, reloaded.getMainType());
        assertEquals(Type.COASTAL_WARNING, reloaded.getType());
    }

    @Test
    @Transactional
    void createMessageWithoutAreasComputesZeroAreaSortOrder() throws Exception {
        Message created = createDraftNw("No areas");
        assertEquals(0.0, created.getAreaSortOrder(), 0.0001,
                "a message with no associated area must have an area sort order of 0.0");

        Message reloaded = reload(created.getId());
        assertEquals(0.0, reloaded.getAreaSortOrder(), 0.0001);
        assertTrue(reloaded.getAreas().isEmpty(), "no areas must be associated");
    }

    @Test
    @Transactional
    void createMessageAttachesPersistedAreaCategoryAndChart() throws Exception {
        Area area = seededArea();
        Category category = seededCategory();
        Chart chart = chartService.findByChartNumber("D");
        assertNotNull(chart, "seeded chart 'D' must exist");
        Integer areaId = area.getId();
        Integer categoryId = category.getId();

        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "With associations");
        msg.getAreas().add(area);
        msg.getCategories().add(category);
        msg.getCharts().add(chart);

        Message created = messageService.createMessage(msg);
        Integer id = created.getId();

        Message reloaded = reload(id);
        assertEquals(1, reloaded.getAreas().size());
        assertEquals(areaId, reloaded.getAreas().get(0).getId());
        assertEquals(1, reloaded.getCategories().size());
        assertEquals(categoryId, reloaded.getCategories().get(0).getId());
        assertEquals(1, reloaded.getCharts().size());
        assertEquals("D", reloaded.getCharts().get(0).getChartNumber());
        // The denormalized "area" reference must point at the first area in the list.
        assertNotNull(reloaded.getArea(), "the denormalized area reference must be set");
        assertEquals(areaId, reloaded.getArea().getId());
    }

    @Test
    @Transactional
    void createMessageRemovesEmptyGeometries() throws Exception {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Empty geometry");
        // Attach an empty FeatureCollection geometry to the part - createMessage must null it out.
        MessagePart part = msg.getParts().get(0);
        part.setGeometry(new org.niord.core.geojson.FeatureCollection());

        Message created = messageService.createMessage(msg);
        Message reloaded = reload(created.getId());

        assertNull(reloaded.getParts().get(0).getGeometry(),
                "an empty geometry must be removed on create");
        assertFalse(reloaded.getHasGeometry(), "hasGeometry must be false when no geometry survives");
    }

    @Test
    @Transactional
    void createMessageWritesPartSubjectAndDetails() throws Exception {
        Message created = createDraftNw("Part contents");
        Message reloaded = reload(created.getId());

        assertEquals(1, reloaded.getParts().size());
        MessagePartDesc pd = reloaded.getParts().get(0).getDesc("en");
        assertNotNull(pd, "the English part description must be persisted");
        assertEquals("subject", pd.getSubject());
        assertEquals("details", pd.getDetails());
    }

    // ------------------------------------------------------------------------------------------- //
    // createMessage - negative / validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMessageRejectsNullMessageSeries() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "No series");
        msg.setMessageSeries(null);

        Exception ex = assertThrows(Exception.class, () -> messageService.createMessage(msg));
        assertEquals("Message series not specified", ex.getMessage());
    }

    @Test
    @Transactional
    void createMessageRejectsAlreadyPersistedMessage() throws Exception {
        Message created = createDraftNw("Already persisted");
        em.flush();

        // Re-submitting the now-persisted entity must be rejected.
        Exception ex = assertThrows(Exception.class, () -> messageService.createMessage(created));
        assertEquals("Message already persisted", ex.getMessage());
    }

    @Test
    @Transactional
    void createMessageRejectsTypeWhoseMainTypeDiffersFromSeries() {
        // dma-nw is an NW series; TEMPORARY_NOTICE is an NM type -> invalid type.
        Message msg = newDraft("dma-nw", Type.TEMPORARY_NOTICE, "Wrong type");

        Exception ex = assertThrows(Exception.class, () -> messageService.createMessage(msg));
        assertTrue(ex.getMessage().startsWith("Invalid type for message"),
                "an NM type on an NW series must be rejected: " + ex.getMessage());
    }

    @Test
    @Transactional
    void createMessageRejectsInconsistentMainTypeWhenNoType() {
        // No type, but mainType explicitly set to NM while the series is NW -> invalid main-type.
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setMainType(MainType.NM);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("s");
        msg.addPart(part);

        Exception ex = assertThrows(Exception.class, () -> messageService.createMessage(msg));
        assertTrue(ex.getMessage().startsWith("Invalid main-type for message"),
                "an NM main-type on an NW series must be rejected: " + ex.getMessage());
    }

    @Test
    @Transactional
    void createMessageAcceptsExplicitDraftStatus() throws Exception {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Explicit draft");
        msg.setStatus(Status.DRAFT);

        Message created = messageService.createMessage(msg);
        assertEquals(Status.DRAFT, reload(created.getId()).getStatus());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMessage
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMessageUpdatesMutableFields() throws Exception {
        Message created = createDraftNw("Original title");
        Integer id = created.getId();
        String uid = created.getUid();
        em.flush();
        em.clear();

        // Build a fresh template carrying the same uid and a changed title + horizontal datum + chart.
        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Updated title");
        template.setUid(uid);
        template.setStatus(Status.DRAFT);
        template.setHorizontalDatum("WGS84");
        Chart chart = chartService.findByChartNumber("D");
        template.getCharts().add(chart);

        messageService.updateMessage(template);

        Message reloaded = reload(id);
        assertEquals("Updated title", reloaded.getDesc("en").getTitle());
        assertEquals("WGS84", reloaded.getHorizontalDatum());
        assertEquals(1, reloaded.getCharts().size());
        assertEquals("D", reloaded.getCharts().get(0).getChartNumber());
    }

    @Test
    @Transactional
    void updateMessageRejectsUnknownUid() {
        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Ghost");
        template.setUid("00000000-0000-0000-0000-000000000000");
        template.setStatus(Status.DRAFT);

        Exception ex = assertThrows(Exception.class, () -> messageService.updateMessage(template));
        assertEquals("Message not an existing message", ex.getMessage());
    }

    @Test
    @Transactional
    void updateMessageRejectsStatusChange() throws Exception {
        Message created = createDraftNw("Status guard");
        String uid = created.getUid();
        em.flush();
        em.clear();

        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Status guard");
        template.setUid(uid);
        template.setStatus(Status.PUBLISHED); // differs from the persisted DRAFT

        Exception ex = assertThrows(Exception.class, () -> messageService.updateMessage(template));
        assertEquals("updateMessage() cannot change status", ex.getMessage());
    }

    @Test
    @Transactional
    void updateMessageRejectsNullMessageSeries() throws Exception {
        Message created = createDraftNw("Null series on update");
        String uid = created.getUid();
        em.flush();
        em.clear();

        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Null series on update");
        template.setUid(uid);
        template.setStatus(Status.DRAFT);
        template.setMessageSeries(null);

        Exception ex = assertThrows(Exception.class, () -> messageService.updateMessage(template));
        assertEquals("Message series not specified", ex.getMessage());
    }

    @Test
    @Transactional
    void updateMessageDowngradesVerifiedToDraft() throws Exception {
        Message created = createDraftNw("Verify then update");
        String uid = created.getUid();
        Integer id = created.getId();
        messageService.updateStatus(uid, Status.VERIFIED);
        em.flush();
        em.clear();

        // Update is submitted with the current (VERIFIED) status, so the status guard passes,
        // and the message must be downgraded back to DRAFT.
        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Edited while verified");
        template.setUid(uid);
        template.setStatus(Status.VERIFIED);

        Message updated = messageService.updateMessage(template);
        assertEquals(Status.DRAFT, updated.getStatus(),
                "editing a VERIFIED message must move it back to DRAFT");
        assertEquals(Status.DRAFT, reload(id).getStatus());
    }

    @Test
    @Transactional
    void updateMessageReplacesAreaCollection() throws Exception {
        Area area = seededArea();
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Areas then none");
        msg.getAreas().add(area);
        Message created = messageService.createMessage(msg);
        Integer id = created.getId();
        String uid = created.getUid();
        em.flush();
        em.clear();

        // Update with no areas -> the previously associated area must be cleared.
        Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "Areas then none");
        template.setUid(uid);
        template.setStatus(Status.DRAFT);

        messageService.updateMessage(template);

        Message reloaded = reload(id);
        assertTrue(reloaded.getAreas().isEmpty(), "the area collection must be fully replaced (cleared)");
        assertNull(reloaded.getArea(), "the denormalized area reference must be cleared too");
    }

    @Test
    @Transactional
    void updateMessageValidatesTypeAgainstResolvedSeries_MSG1() throws Exception {
        // updateMessage validates the requested type against the RESOLVED/persisted series
        // (original.getMessageSeries()), exactly like createMessage. The detached argument may carry only a
        // seriesId (no mainType); previously validating against THAT series dereferenced the null mainType
        // and threw a NullPointerException. Now the authoritative persisted series (dma-nw, mainType NW) is
        // used, so an NM type is cleanly rejected with the meaningful "Invalid type" error and no NPE occurs.
        Message created = createDraftNw("Detached series validation");
        String uid = created.getUid();
        em.flush();
        em.clear();

        Message template = new Message();
        template.setUid(uid);
        template.setStatus(Status.DRAFT);
        template.setMessageSeries(new MessageSeries("dma-nw")); // bare series: NO mainType
        template.setType(Type.PERMANENT_NOTICE);                 // an NM type, invalid for the NW dma-nw series
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("s");
        template.addPart(part);
        template.createDesc("en").setTitle("t");

        Exception ex = assertThrows(Exception.class, () -> messageService.updateMessage(template));
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertFalse(root instanceof NullPointerException,
                "MSG1: updateMessage must no longer NPE on a bare detached series; was: " + root);
        assertTrue(root.getMessage() != null && root.getMessage().contains("Invalid type"),
                "MSG1: the type is now validated against the resolved series, yielding a meaningful "
                        + "'Invalid type' error; was: " + root);
    }

    // ------------------------------------------------------------------------------------------- //
    // updateStatus - valid transitions & side effects
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateStatusDraftToVerified() throws Exception {
        Message created = createDraftNw("Draft to verified");
        Integer id = created.getId();

        Message updated = messageService.updateStatus(created.getUid(), Status.VERIFIED);
        assertEquals(Status.VERIFIED, updated.getStatus());
        assertNull(updated.getLastUpdatedBy(), "lastUpdatedBy stays null without an authenticated user");
        assertEquals(Status.VERIFIED, reload(id).getStatus());
    }

    @Test
    @Transactional
    void updateStatusVerifiedToPublishedStampsDatesNumberAndShortId() throws Exception {
        Message created = createDraftNw("Publish me");
        Integer id = created.getId();
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);

        Message published = messageService.updateStatus(uid, Status.PUBLISHED);
        assertEquals(Status.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishDateFrom(), "publishing must stamp publishDateFrom");
        assertNotNull(published.getNumber(), "publishing must assign a message number");
        assertNotNull(published.getShortId(), "publishing must assign a short ID");
        assertTrue(published.getShortId().startsWith("NW-"),
                "dma-nw short IDs use the 'NW-' prefix: " + published.getShortId());
        // The year is denormalized into the short ID's 2-digit suffix (shortFormat NW-${number-3-digits}-${year-2-digits}).
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(published.getPublishDateFrom());
        int yy = cal.get(java.util.Calendar.YEAR) % 100;
        assertTrue(published.getShortId().endsWith(String.format("-%02d", yy)),
                "publishing must derive the year from the publish date into the short ID: " + published.getShortId());

        Message reloaded = reload(id);
        assertEquals(Status.PUBLISHED, reloaded.getStatus());
        assertNotNull(reloaded.getPublishDateFrom());
        assertNotNull(reloaded.getShortId());
        assertEquals(published.getShortId(), reloaded.getShortId());
    }

    @Test
    @Transactional
    void updateStatusPublishedToCancelledStampsPublishDateTo() throws Exception {
        Message created = createDraftNw("Cancel me");
        Integer id = created.getId();
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        messageService.updateStatus(uid, Status.PUBLISHED);

        Message cancelled = messageService.updateStatus(uid, Status.CANCELLED);
        assertEquals(Status.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getPublishDateTo(), "cancelling must stamp publishDateTo");

        Message reloaded = reload(id);
        assertEquals(Status.CANCELLED, reloaded.getStatus());
        assertNotNull(reloaded.getPublishDateTo());
    }

    @Test
    @Transactional
    void updateStatusPublishedToExpiredStampsPublishDateTo() throws Exception {
        Message created = createDraftNw("Expire me");
        Integer id = created.getId();
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        messageService.updateStatus(uid, Status.PUBLISHED);

        Message expired = messageService.updateStatus(uid, Status.EXPIRED);
        assertEquals(Status.EXPIRED, expired.getStatus());
        assertNotNull(expired.getPublishDateTo(), "expiring must stamp publishDateTo");

        assertEquals(Status.EXPIRED, reload(id).getStatus());
    }

    @Test
    @Transactional
    void updateStatusDraftToDeleted() throws Exception {
        Message created = createDraftNw("Delete me");
        Integer id = created.getId();

        Message deleted = messageService.updateStatus(created.getUid(), Status.DELETED);
        assertEquals(Status.DELETED, deleted.getStatus());
        // The row is not physically removed - DELETED is a soft-delete status.
        assertEquals(Status.DELETED, reload(id).getStatus());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateStatus - invalid transitions
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateStatusRejectsDraftToPublished() throws Exception {
        Message created = createDraftNw("Skip verify");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> messageService.updateStatus(created.getUid(), Status.PUBLISHED));
        assertTrue(ex.getMessage().startsWith("Invalid status transition"),
                ex.getMessage());
        assertEquals(Status.DRAFT, reload(created.getId()).getStatus(),
                "a rejected transition must leave the status unchanged");
    }

    @Test
    @Transactional
    void updateStatusRejectsVerifiedToExpired() throws Exception {
        Message created = createDraftNw("Verified to expired");
        messageService.updateStatus(created.getUid(), Status.VERIFIED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> messageService.updateStatus(created.getUid(), Status.EXPIRED));
        assertTrue(ex.getMessage().startsWith("Invalid status transition"), ex.getMessage());
    }

    @Test
    @Transactional
    void updateStatusRejectsPublishedToDraft() throws Exception {
        Message created = createDraftNw("Published to draft");
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        messageService.updateStatus(uid, Status.PUBLISHED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> messageService.updateStatus(uid, Status.DRAFT));
        assertTrue(ex.getMessage().startsWith("Invalid status transition"), ex.getMessage());
    }

    @Test
    @Transactional
    void updateStatusRejectsDeletedToVerified() throws Exception {
        Message created = createDraftNw("Deleted then verified");
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.DELETED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> messageService.updateStatus(uid, Status.VERIFIED));
        assertTrue(ex.getMessage().startsWith("Invalid status transition"), ex.getMessage());
    }

    /** MSG5: no status to change to is the caller's error (400 over REST), not a refused transition. */
    @Test
    @Transactional
    void updateStatusRefusesANullStatus() throws Exception {
        Message created = createDraftNw("No status");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.updateStatus(created.getUid(), null));
        assertTrue(ex.getMessage().contains(created.getUid()), ex.getMessage());
        assertEquals(Status.DRAFT, reload(created.getId()).getStatus());
    }

    // ------------------------------------------------------------------------------------------- //
    // Lookups
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByUidReturnsMatchAndNullForMiss() throws Exception {
        Message created = createDraftNw("Find by uid");
        em.flush();

        Message found = messageQueryService.findByUid(created.getUid());
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());

        assertNull(messageQueryService.findByUid("no-such-uid"), "an unknown uid must resolve to null");
    }

    @Test
    @Transactional
    void findByLegacyIdReturnsMatch() throws Exception {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Legacy");
        msg.setLegacyId("LEGACY-IT-123");
        Message created = messageService.createMessage(msg);
        em.flush();

        Message found = messageService.findByLegacyId("LEGACY-IT-123");
        assertNotNull(found, "the message must be found by its legacy id");
        assertEquals(created.getId(), found.getId());

        assertNull(messageService.findByLegacyId("LEGACY-IT-MISSING"));
    }

    @Test
    @Transactional
    void findByShortIdReturnsPublishedMessage() throws Exception {
        Message created = createDraftNw("Short id lookup");
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        Message published = messageService.updateStatus(uid, Status.PUBLISHED);
        String shortId = published.getShortId();
        assertNotNull(shortId);
        em.flush();

        List<Message> found = messageService.findByShortId(shortId);
        assertEquals(1, found.size(), "exactly one message must carry this short ID");
        assertEquals(created.getId(), found.get(0).getId());

        assertTrue(messageService.findByShortId("NW-NO-SUCH-ID").isEmpty(),
                "an unknown short ID must return an empty list");
    }

    @Test
    @Transactional
    void findByShortIdIsCaseInsensitive() throws Exception {
        // PG1: Message.findByShortId now compares lower(msg.shortId) = lower(:shortId). Under MySQL's
        // default _ai_ci collation the match was implicitly case-insensitive; PostgreSQL's '=' is
        // case-sensitive, so the query lowers both sides explicitly (like Message.findByMessageId).
        Message created = createDraftNw("Short id case-insensitive");
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        Message published = messageService.updateStatus(uid, Status.PUBLISHED);
        String shortId = published.getShortId();   // generated upper-case, e.g. NW-001-26
        assertNotNull(shortId);
        em.flush();

        List<Message> lower = messageService.findByShortId(shortId.toLowerCase());
        assertEquals(1, lower.size(),
                "a lower-cased short id must still match the upper-cased stored short id");
        assertEquals(created.getId(), lower.get(0).getId());

        // resolveMessage delegates to findByShortId, so it must be case-insensitive too.
        Message resolved = messageService.resolveMessage(shortId.toLowerCase());
        assertNotNull(resolved, "resolveMessage must resolve a mixed-case short id");
        assertEquals(created.getId(), resolved.getId());
    }

    @Test
    @Transactional
    void resolveMessageByUidAndShortId() throws Exception {
        Message created = createDraftNw("Resolve");
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        Message published = messageService.updateStatus(uid, Status.PUBLISHED);
        em.flush();

        Message byUid = messageService.resolveMessage(uid);
        assertNotNull(byUid);
        assertEquals(created.getId(), byUid.getId());

        Message byShortId = messageService.resolveMessage(published.getShortId());
        assertNotNull(byShortId);
        assertEquals(created.getId(), byShortId.getId());
    }

    @Test
    @Transactional
    void resolveMessageReturnsNullForBlankAndUnknown() {
        assertNull(messageService.resolveMessage(null));
        assertNull(messageService.resolveMessage(""));
        assertNull(messageService.resolveMessage("   "));
        assertNull(messageService.resolveMessage("no-such-message"));
    }

    // ------------------------------------------------------------------------------------------- //
    // getReferencedMessages
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getReferencedMessagesResolvesAndFiltersByTypeAndStatus() throws Exception {
        // Message B is published so it has a resolvable uid and a PUBLISHED status.
        Message b = createDraftNw("Referenced B");
        String bUid = b.getUid();
        messageService.updateStatus(bUid, Status.VERIFIED);
        messageService.updateStatus(bUid, Status.PUBLISHED);

        // Message A references B by B's uid with a REFERENCE type.
        Message aTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "Referencing A");
        Reference ref = new Reference();
        ref.setMessageId(bUid);
        ref.setType(ReferenceType.REFERENCE);
        aTemplate.addReference(ref);
        Message a = messageService.createMessage(aTemplate);
        em.flush();
        em.clear();

        Message reloadedA = messageQueryService.findByUid(a.getUid());

        // No filters -> B is returned.
        List<Message> all = messageService.getReferencedMessages(reloadedA, null, null);
        assertEquals(1, all.size());
        assertEquals(b.getId(), all.get(0).getId());

        // Matching reference-type filter -> B is returned.
        Set<ReferenceType> matchingTypes = EnumSet.of(ReferenceType.REFERENCE);
        assertEquals(1, messageService.getReferencedMessages(reloadedA, matchingTypes, null).size());

        // Non-matching reference-type filter -> nothing.
        Set<ReferenceType> otherTypes = EnumSet.of(ReferenceType.CANCELLATION);
        assertTrue(messageService.getReferencedMessages(reloadedA, otherTypes, null).isEmpty(),
                "a non-matching reference type must filter out the reference");

        // Matching status filter (B is PUBLISHED) -> B is returned.
        assertEquals(1, messageService.getReferencedMessages(reloadedA, null, Status.PUBLISHED).size());

        // Non-matching status filter -> nothing.
        assertTrue(messageService.getReferencedMessages(reloadedA, null, Status.DRAFT).isEmpty(),
                "a non-matching status must filter out the reference");
    }

    @Test
    @Transactional
    void getReferencedMessagesSkipsUnresolvableReferences() throws Exception {
        Message aTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "Dangling ref");
        Reference ref = new Reference();
        ref.setMessageId("does-not-resolve");
        ref.setType(ReferenceType.REFERENCE);
        aTemplate.addReference(ref);
        Message a = messageService.createMessage(aTemplate);
        em.flush();
        em.clear();

        Message reloadedA = messageQueryService.findByUid(a.getUid());
        assertTrue(messageService.getReferencedMessages(reloadedA, null, null).isEmpty(),
                "references that resolve to no message must be dropped");
    }

    // ------------------------------------------------------------------------------------------- //
    // newTemplateMessage
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void newTemplateMessageForNwHasNoOriginalInformation() {
        Message tpl = messageService.newTemplateMessage(MainType.NW);
        assertNotNull(tpl.getUid(), "a template message must be assigned a uid");
        assertEquals(Status.DRAFT, tpl.getStatus());
        assertTrue(tpl.isAutoTitle(), "template messages enable auto-title");
        assertEquals(MainType.NW, tpl.getMainType());
        // NW does not set originalInformation.
        assertTrue(tpl.getOriginalInformation() == null || !tpl.getOriginalInformation(),
                "NW template must not flag original information");
    }

    @Test
    @Transactional
    void newTemplateMessageForNmFlagsOriginalInformation() {
        Message tpl = messageService.newTemplateMessage(MainType.NM);
        assertNotNull(tpl.getUid());
        assertEquals(Status.DRAFT, tpl.getStatus());
        assertTrue(tpl.isAutoTitle());
        assertEquals(MainType.NM, tpl.getMainType());
        assertEquals(Boolean.TRUE, tpl.getOriginalInformation(),
                "NM template must flag original information as true");
    }

    // ------------------------------------------------------------------------------------------- //
    // changeAreaSortOrder
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void changeAreaSortOrderMovesBeforeAfterAndBetween() throws Exception {
        Message m1 = createDraftNw("Sort 1");
        Message m2 = createDraftNw("Sort 2");
        Message m3 = createDraftNw("Sort 3");
        m1.setAreaSortOrder(10.0);
        m2.setAreaSortOrder(20.0);
        m3.setAreaSortOrder(30.0);
        em.flush();

        // Move m1 before m2 -> bm - 1 = 19.0.
        messageService.changeAreaSortOrder(m1.getUid(), null, m2.getUid());
        assertEquals(19.0, messageQueryService.findByUid(m1.getUid()).getAreaSortOrder(), 0.0001);

        // Move m3 after m2 -> am + 1 = 21.0.
        messageService.changeAreaSortOrder(m3.getUid(), m2.getUid(), null);
        assertEquals(21.0, messageQueryService.findByUid(m3.getUid()).getAreaSortOrder(), 0.0001);

        // Move m1 between m2 (20) and m3 (now 21) -> average = 20.5.
        messageService.changeAreaSortOrder(m1.getUid(), m2.getUid(), m3.getUid());
        assertEquals(20.5, messageQueryService.findByUid(m1.getUid()).getAreaSortOrder(), 0.0001);
    }

    @Test
    @Transactional
    void changeAreaSortOrderRejectsNullMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> messageService.changeAreaSortOrder("no-such-uid", null, null));
    }

    @Test
    @Transactional
    void changeAreaSortOrderRejectsBothNeighboursNull() throws Exception {
        Message m = createDraftNw("Lonely");
        em.flush();
        assertThrows(IllegalArgumentException.class,
                () -> messageService.changeAreaSortOrder(m.getUid(), null, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // getSeparatePageUids
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getSeparatePageUidsReturnsOnlyFlaggedMessages() throws Exception {
        Message separate = newDraft("dma-nw", Type.COASTAL_WARNING, "Separate page");
        separate.setSeparatePage(true);
        Message sepCreated = messageService.createMessage(separate);

        Message inline = newDraft("dma-nw", Type.COASTAL_WARNING, "Inline");
        inline.setSeparatePage(false);
        Message inlineCreated = messageService.createMessage(inline);
        em.flush();

        Set<String> uids = new java.util.HashSet<>();
        uids.add(sepCreated.getUid());
        uids.add(inlineCreated.getUid());

        Set<String> result = messageService.getSeparatePageUids(uids);
        assertEquals(1, result.size(), "only the separate-page message must be returned");
        assertTrue(result.contains(sepCreated.getUid()));
        assertFalse(result.contains(inlineCreated.getUid()));
    }

    @Test
    @Transactional
    void getSeparatePageUidsReturnsEmptyForNullOrEmptyInput() {
        assertTrue(messageService.getSeparatePageUids(null).isEmpty());
        assertTrue(messageService.getSeparatePageUids(new java.util.HashSet<>()).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // Message history
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMessageRecordsHistoryEntry() throws Exception {
        Message created = createDraftNw("History on create");
        em.flush();

        List<MessageHistory> history = messageService.getMessageHistory(created.getId());
        assertFalse(history.isEmpty(), "createMessage must record at least one history entry");
        // Most recent entry first (ordered by created desc) - its status matches the message status.
        assertEquals(Status.DRAFT, history.get(0).getStatus());
    }

    @Test
    @Transactional
    void statusTransitionsAccumulateHistoryEntries() throws Exception {
        Message created = createDraftNw("History accumulation");
        Integer id = created.getId();
        String uid = created.getUid();
        messageService.updateStatus(uid, Status.VERIFIED);
        messageService.updateStatus(uid, Status.PUBLISHED);
        em.flush();

        List<MessageHistory> history = messageService.getMessageHistory(id);
        // create (DRAFT) + verify (VERIFIED) + publish (PUBLISHED) = at least three entries.
        assertTrue(history.size() >= 3,
                "each create/status change must record a history entry, got " + history.size());
        // The latest entry (ordered created desc) reflects the most recent status.
        List<Status> statuses = new ArrayList<>();
        history.forEach(h -> statuses.add(h.getStatus()));
        assertEquals(Status.PUBLISHED, history.get(0).getStatus(),
                "the most recent history entry must be PUBLISHED");
        assertTrue(statuses.contains(Status.VERIFIED), "the VERIFIED transition must be recorded");
        assertTrue(statuses.contains(Status.DRAFT), "the initial DRAFT must be recorded");
    }

    @Test
    @Transactional
    void getMessageHistoryReturnsEmptyForUnknownMessageId() {
        assertTrue(messageService.getMessageHistory(-12345).isEmpty(),
                "an unknown message id must have no history");
    }

    @Test
    @Transactional
    void distinctMessagesDoNotShareUidOrShortId() throws Exception {
        Message a = createDraftNw("Distinct A");
        Message b = createDraftNw("Distinct B");
        em.flush();
        assertNotEquals(a.getUid(), b.getUid(), "each created message must get a unique uid");
        assertNotEquals(a.getId(), b.getId(), "each created message must get a unique primary key");
    }
}
