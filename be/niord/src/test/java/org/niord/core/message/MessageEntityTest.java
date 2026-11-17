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

import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.category.Category;
import org.niord.core.chart.Chart;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.user.User;
import org.niord.core.util.UidUtils;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.message.AreaVo;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.ChartVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.MessageVo;
import org.niord.model.message.ReferenceVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for the pure (non-persistent) business logic of the core {@link Message} entity, covering:
 * <ul>
 *     <li>NAVTEX-style id formatting ({@code computeNumberYearId})</li>
 *     <li>year derivation from the publish-start date ({@code checkUpdateYear})</li>
 *     <li>aggregation of event-date intervals across message parts ({@code updateAggregateEventDateInterval})</li>
 *     <li>publish-start / publish-end event-date interval bookkeeping</li>
 *     <li>language union ({@code computeLanguages}) and part filtering ({@code partsByType})</li>
 *     <li>parent/child back-reference wiring of the add* helpers</li>
 *     <li>promulgation lookups by type id and by class</li>
 *     <li>GeoJSON aggregation with no geometry present</li>
 *     <li>the {@code @PrePersist} hook ({@code onPersist}) and {@code assignNewUid} repo-path rewriting</li>
 *     <li>entity-to-VO and VO-to-entity round-tripping</li>
 *     <li>auto-title computation ({@code updateMessageTitle} / {@code computeAreaTitle})</li>
 * </ul>
 *
 * <p>These are framework-neutral, plain JUnit 4 tests — no database, no Quarkus, no Spring, no mocks. All
 * objects are constructed directly with {@code new} + setters and asserted on concrete values, so they
 * survive a future Quarkus-to-Spring port. Every message/VO id that flows into a repo-path is a valid UUID
 * (via {@link UidUtils#newUid()}) or {@code null}, because {@link UidUtils#uidToHashedFolderPath} throws on
 * non-UUID, non-blank ids.
 */
public class MessageEntityTest {

    // --------------------------------------------------------------------------------------------- //
    // Helpers
    // --------------------------------------------------------------------------------------------- //

    /** Builds a DETAILS message part attached to the given message. */
    private static MessagePart detailsPart(Message m) {
        MessagePart p = new MessagePart(MessagePartType.DETAILS);
        m.addPart(p);
        return p;
    }

    /** Builds an Area with an English name (so title computation never NPEs). */
    private static Area area(String enName) {
        Area a = new Area();
        a.createDesc("en").setName(enName);
        return a;
    }

    // --------------------------------------------------------------------------------------------- //
    // computeNumberYearId()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void computeNumberYearIdFormatsWithZeroPadding() {
        Message m = new Message();
        m.setNumber(7);
        m.year = 2023;
        assertEquals("007/23", m.computeNumberYearId());
    }

    @Test
    public void computeNumberYearIdEmptyWhenNumberNull() {
        Message m = new Message();
        m.year = 2023;
        // number is null
        assertEquals("", m.computeNumberYearId());
    }

    @Test
    public void computeNumberYearIdEmptyWhenYearNull() {
        Message m = new Message();
        m.setNumber(7);
        // year is null
        assertEquals("", m.computeNumberYearId());
    }

    // --------------------------------------------------------------------------------------------- //
    // checkUpdateYear()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void checkUpdateYearDerivesYearFromPublishDateFrom() {
        Message m = new Message();
        m.setPublishDateFrom(new GregorianCalendar(2018, Calendar.JUNE, 15).getTime());
        Integer year = m.checkUpdateYear();
        assertEquals(Integer.valueOf(2018), year);
        assertEquals(Integer.valueOf(2018), m.year);
    }

    @Test
    public void checkUpdateYearNullWhenNoPublishDate() {
        Message m = new Message();
        // publishDateFrom is null
        assertNull(m.checkUpdateYear());
        assertNull(m.year);
    }

    // --------------------------------------------------------------------------------------------- //
    // updateAggregateEventDateInterval()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateAggregateEventDateIntervalSpansMinFromAndMaxTo() {
        Message m = new Message();
        MessagePart p1 = detailsPart(m);
        p1.addEventDates(new DateInterval(false, new Date(1000), new Date(5000)));
        MessagePart p2 = detailsPart(m);
        p2.addEventDates(new DateInterval(false, new Date(500), new Date(9000)));

        m.updateAggregateEventDateInterval();

        assertEquals(new Date(500), m.getEventDateFrom());
        assertEquals(new Date(9000), m.getEventDateTo());
    }

    @Test
    public void updateAggregateEventDateIntervalNullWhenNoEventDates() {
        Message m = new Message();
        detailsPart(m);
        detailsPart(m);
        // seed non-null values to prove they get reset
        m.setEventDateFrom(new Date(1));
        m.setEventDateTo(new Date(2));

        m.updateAggregateEventDateInterval();

        assertNull(m.getEventDateFrom());
        assertNull(m.getEventDateTo());
    }

    // --------------------------------------------------------------------------------------------- //
    // checkEventDateIntervalsUponPublishStart()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void publishStartAddsOpenEndedIntervalToDetailsPartsOnly() {
        Message m = new Message();
        MessagePart details = detailsPart(m);
        MessagePart time = new MessagePart(MessagePartType.TIME);
        m.addPart(time);

        Date pub = new Date(4000);
        m.setPublishDateFrom(pub);

        m.checkEventDateIntervalsUponPublishStart();

        assertEquals(1, details.getEventDates().size());
        DateInterval added = details.getEventDates().get(0);
        assertSame(pub, added.getFromDate());
        assertNull(added.getToDate());
        // TIME part is untouched
        assertEquals(0, time.getEventDates().size());
    }

    @Test
    public void publishStartIsNoOpWhenAnyEventDateAlreadyPresent() {
        Message m = new Message();
        MessagePart p1 = detailsPart(m);
        MessagePart p2 = detailsPart(m);
        // p2 already has an event date -> the whole method is a no-op
        p2.addEventDates(new DateInterval(false, new Date(1000), new Date(2000)));

        m.setPublishDateFrom(new Date(4000));
        m.checkEventDateIntervalsUponPublishStart();

        assertEquals(0, p1.getEventDates().size());
        assertEquals(1, p2.getEventDates().size());
    }

    @Test
    public void publishStartIsNoOpWhenNoPublishDate() {
        Message m = new Message();
        MessagePart details = detailsPart(m);
        // publishDateFrom is null
        m.checkEventDateIntervalsUponPublishStart();
        assertEquals(0, details.getEventDates().size());
    }

    // --------------------------------------------------------------------------------------------- //
    // checkEventDateIntervalsUponPublishEnd()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void publishEndClosesRemovesAndPreservesIntervals() {
        Message m = new Message();
        m.setPublishDateTo(new Date(5000));

        // open-ended interval, from <= publishDateTo -> toDate set to publishDateTo
        MessagePart p1 = detailsPart(m);
        DateInterval closeMe = p1.addEventDates(new DateInterval(false, new Date(1000), null));

        // open-ended interval, from > publishDateTo -> removed
        MessagePart p2 = detailsPart(m);
        p2.addEventDates(new DateInterval(false, new Date(9000), null));

        // closed interval -> untouched
        MessagePart p3 = detailsPart(m);
        DateInterval closed = p3.addEventDates(new DateInterval(false, new Date(2000), new Date(3000)));

        m.checkEventDateIntervalsUponPublishEnd();

        // closed-out interval now ends at the publish-end date
        assertEquals(1, p1.getEventDates().size());
        assertEquals(new Date(5000), closeMe.getToDate());

        // future open-ended interval removed
        assertEquals(0, p2.getEventDates().size());

        // closed interval unchanged
        assertEquals(1, p3.getEventDates().size());
        assertEquals(new Date(2000), closed.getFromDate());
        assertEquals(new Date(3000), closed.getToDate());
    }

    @Test
    public void publishEndIsNoOpWhenNoPublishDateTo() {
        Message m = new Message();
        MessagePart p = detailsPart(m);
        DateInterval open = p.addEventDates(new DateInterval(false, new Date(1000), null));
        // publishDateTo is null
        m.checkEventDateIntervalsUponPublishEnd();
        assertEquals(1, p.getEventDates().size());
        assertNull(open.getToDate());
    }

    // --------------------------------------------------------------------------------------------- //
    // computeLanguages()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void computeLanguagesUnionsMessageAndPartLanguages() {
        Message m = new Message();
        m.createDesc("en");
        MessagePart p = detailsPart(m);
        p.createDesc("da");

        Set<String> expected = new HashSet<>(Arrays.asList("en", "da"));
        assertEquals(expected, m.computeLanguages());
    }

    @Test
    public void computeLanguagesEmptyWhenNoDescs() {
        Message m = new Message();
        detailsPart(m);
        assertTrue(m.computeLanguages().isEmpty());
    }

    // --------------------------------------------------------------------------------------------- //
    // partsByType()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void partsByTypeFiltersByType() {
        Message m = new Message();
        detailsPart(m);
        detailsPart(m);
        m.addPart(new MessagePart(MessagePartType.TIME));

        assertEquals(2, m.partsByType(MessagePartType.DETAILS).size());
        assertEquals(1, m.partsByType(MessagePartType.TIME).size());
        assertEquals(0, m.partsByType(MessagePartType.NOTE).size());
    }

    // --------------------------------------------------------------------------------------------- //
    // add* back-references
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void addPartSetsBackReferenceAndAddsToParts() {
        Message m = new Message();
        MessagePart p = new MessagePart(MessagePartType.DETAILS);
        MessagePart result = m.addPart(p);

        assertSame(p, result);
        assertSame(m, p.getMessage());
        assertEquals(1, m.getParts().size());
        assertSame(p, m.getParts().get(0));
    }

    @Test
    public void addReferenceSetsBackReference() {
        Message m = new Message();
        Reference r = new Reference();
        Reference result = m.addReference(r);

        assertSame(r, result);
        assertSame(m, r.getMessage());
        assertEquals(1, m.getReferences().size());
    }

    @Test
    public void addAttachmentSetsBackReference() {
        Message m = new Message();
        Attachment a = new Attachment();
        Attachment result = m.addAttachment(a);

        assertSame(a, result);
        assertSame(m, a.getMessage());
        assertEquals(1, m.getAttachments().size());
    }

    @Test
    public void addDescSetsEntityBackReference() {
        Message m = new Message();
        MessageDesc d = new MessageDesc();
        d.setLang("en");
        MessageDesc result = m.addDesc(d);

        assertSame(d, result);
        assertSame(m, d.getEntity());
        assertEquals(1, m.getDescs().size());
    }

    @Test
    public void createDescBuildsLocalizedDescAttachedToMessage() {
        Message m = new Message();
        MessageDesc d = m.createDesc("en");

        assertEquals("en", d.getLang());
        assertSame(m, d.getEntity());
        assertEquals(1, m.getDescs().size());
        assertSame(d, m.getDescs().get(0));
    }

    @Test
    public void addPromulgationSetsBackReference() {
        Message m = new Message();
        BaseMessagePromulgation pr = new BaseMessagePromulgation();
        BaseMessagePromulgation result = m.addPromulgation(pr);

        assertSame(pr, result);
        assertSame(m, pr.getMessage());
        assertEquals(1, m.getPromulgations().size());
    }

    // --------------------------------------------------------------------------------------------- //
    // promulgation lookups
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void promulgationLookupByTypeId() {
        Message m = new Message();
        BaseMessagePromulgation p = new BaseMessagePromulgation();
        PromulgationType t = new PromulgationType();
        t.setTypeId("navtex");
        p.setType(t);
        m.addPromulgation(p);

        assertSame(p, m.promulgation("navtex"));
        assertNull(m.promulgation("missing"));
    }

    @Test
    public void promulgationLookupByClassAndTypeId() {
        Message m = new Message();
        BaseMessagePromulgation p = new BaseMessagePromulgation();
        PromulgationType t = new PromulgationType();
        t.setTypeId("navtex");
        p.setType(t);
        m.addPromulgation(p);

        assertSame(p, m.promulgation(BaseMessagePromulgation.class, "navtex"));
        assertNull(m.promulgation(BaseMessagePromulgation.class, "missing"));
    }

    // --------------------------------------------------------------------------------------------- //
    // toGeoJson() / toFeatureCollection() with no geometry
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toGeoJsonReturnsEmptyArrayWhenNoGeometry() {
        Message m = new Message();
        detailsPart(m);
        FeatureCollectionVo[] fcs = m.toGeoJson();
        assertNotNull(fcs);
        assertEquals(0, fcs.length);
    }

    @Test
    public void toFeatureCollectionHasNoFeaturesWhenNoGeometry() {
        Message m = new Message();
        detailsPart(m);
        FeatureCollectionVo fc = m.toFeatureCollection();
        assertNotNull(fc);
        assertNotNull(fc.getFeatures());
        assertEquals(0, fc.getFeatures().length);
    }

    // --------------------------------------------------------------------------------------------- //
    // onPersist()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void onPersistDerivesMainTypeAreaGeometryAndUid() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);

        Area a1 = area("Area 1");
        Area a2 = area("Area 2");
        m.setAreas(new ArrayList<>(Arrays.asList(a1, a2)));

        // a good reference stays, a blank one is dropped
        Reference good = new Reference();
        good.setMessageId("NW-001-23");
        m.addReference(good);
        Reference blank = new Reference();
        blank.setMessageId("  ");
        m.addReference(blank);

        // autoTitle false so the title computation is skipped
        m.setAutoTitle(false);

        assertNull(m.getUid());
        m.onPersist();

        // type NW main type derived
        assertEquals(MainType.NW, m.getMainType());
        // primary area is the first area in the list
        assertSame(a1, m.getArea());
        // blank reference removed, good one stays
        assertEquals(1, m.getReferences().size());
        assertSame(good, m.getReferences().get(0));
        // no geometry
        assertFalse(m.getHasGeometry());
        // a valid uid assigned
        assertNotNull(m.getUid());
        assertTrue(UidUtils.validUidFormat(m.getUid()));
        // repoPath derived from the new uid
        assertEquals(UidUtils.uidToHashedFolderPath("messages", m.getUid()), m.getRepoPath());
    }

    @Test
    public void onPersistDefaultsLastUpdatedByToCreatedBy() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);

        User user = new User();
        user.setUsername("editor");
        m.setCreatedBy(user);
        // lastUpdatedBy left null

        m.onPersist();

        assertSame(user, m.getLastUpdatedBy());
    }

    @Test
    public void onPersistKeepsExplicitLastUpdatedBy() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);

        User creator = new User();
        creator.setUsername("creator");
        User updater = new User();
        updater.setUsername("updater");
        m.setCreatedBy(creator);
        m.setLastUpdatedBy(updater);

        m.onPersist();

        assertSame(updater, m.getLastUpdatedBy());
    }

    // --------------------------------------------------------------------------------------------- //
    // assignNewUid()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void assignNewUidRewritesRepoPathsAcrossPartsAttachmentsAndThumbnail() {
        Message m = new Message();
        String oldRepo = "messages/x/yz/old-uid";
        m.setRepoPath(oldRepo);

        // a part whose details reference the old repo path
        MessagePart part = detailsPart(m);
        MessagePartDesc desc = part.createDesc("en");
        String oldLink = "\"/rest/repo/file/" + oldRepo + "/a.png\"";
        desc.setDetails("<img src=" + oldLink + "/>");

        // an attachment whose path uses the old repo path
        Attachment att = new Attachment();
        att.setPath(oldRepo + "/a.png");
        m.addAttachment(att);

        // a thumbnail using the old repo path
        m.setThumbnailPath(oldRepo + "/thumb.png");

        String newUid = m.assignNewUid();

        assertTrue(UidUtils.validUidFormat(newUid));
        assertEquals(newUid, m.getUid());

        String newRepo = UidUtils.uidToHashedFolderPath("messages", newUid);
        assertEquals(newRepo, m.getRepoPath());

        // the part details no longer reference the old repo path but reference the new one
        assertFalse(desc.getDetails().contains(oldRepo));
        assertTrue(desc.getDetails().contains("\"/rest/repo/file/" + newRepo + "/a.png\""));

        // the attachment path and thumbnail path rewritten
        assertEquals(newRepo + "/a.png", att.getPath());
        assertEquals(newRepo + "/thumb.png", m.getThumbnailPath());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(SystemMessageVo.class, ...)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoMapsSystemFieldsAndIncrementsRevision() {
        Message m = new Message();
        String uid = UidUtils.newUid();
        m.setUid(uid);
        m.setNumber(7);
        m.setShortId("NW-007-23");
        m.setStatus(Status.PUBLISHED);
        m.setType(Type.LOCAL_WARNING);
        m.setMainType(MainType.NW);
        m.setRevision(2);

        MessagePart part = detailsPart(m);
        part.createDesc("en").setSubject("A subject");

        m.createDesc("en").setTitle("Title");

        SystemMessageVo vo = m.toVo(SystemMessageVo.class,
                DataFilter.get().fields(DataFilter.ALL).lang("en"));

        assertEquals(uid, vo.getId());
        assertEquals(Integer.valueOf(7), vo.getNumber());
        assertEquals(Status.PUBLISHED, vo.getStatus());
        // NB: toVo increments the revision number by 1
        assertEquals(3, vo.getRevision());
        assertNotNull(vo.getDescs());
        assertEquals("Title", vo.getDescs().get(0).getTitle());
        assertNotNull(vo.getParts());
        assertEquals(1, vo.getParts().size());
        // the part's localized subject also round-trips into the VO (part descs are not title-gated)
        assertEquals("A subject", vo.getParts().get(0).getDescs().get(0).getSubject());
        assertEquals(Integer.valueOf(0), vo.getUnackComments());
    }

    // --------------------------------------------------------------------------------------------- //
    // new Message(SystemMessageVo, DataFilter)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructFromSystemMessageVoCopiesAllFields() {
        SystemMessageVo vo = new SystemMessageVo();
        String id = UidUtils.newUid();
        vo.setId(id);

        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId("dma-nw");
        vo.setMessageSeries(series);

        vo.setNumber(5);
        vo.setShortId("NW-005-23");
        vo.setMainType(MainType.NW);
        vo.setType(Type.LOCAL_WARNING);
        vo.setStatus(Status.DRAFT);
        vo.setAutoTitle(true);

        MessagePartVo partVo = new MessagePartVo();
        partVo.setType(MessagePartType.DETAILS);
        MessagePartDescVo partDesc = new MessagePartDescVo();
        partDesc.setLang("en");
        partDesc.setSubject("S");
        partVo.setDescs(new ArrayList<>(Arrays.asList(partDesc)));
        vo.setParts(new ArrayList<>(Arrays.asList(partVo)));

        MessageDescVo descVo = new MessageDescVo();
        descVo.setLang("en");
        descVo.setTitle("T");
        vo.setDescs(new ArrayList<>(Arrays.asList(descVo)));

        Message m = new Message(vo, DataFilter.get());

        assertEquals(id, m.getUid());
        assertNotNull(m.getMessageSeries());
        assertEquals("dma-nw", m.getMessageSeries().getSeriesId());
        assertEquals(Integer.valueOf(5), m.getNumber());
        assertEquals("NW-005-23", m.getShortId());
        assertEquals(MainType.NW, m.getMainType());
        assertEquals(Type.LOCAL_WARNING, m.getType());
        assertEquals(Status.DRAFT, m.getStatus());
        assertTrue(m.isAutoTitle());
        assertEquals(1, m.getParts().size());
        // the message part and its localized subject were copied from the VO
        assertEquals(MessagePartType.DETAILS, m.getParts().get(0).getType());
        assertNotNull(m.getParts().get(0).getDesc("en"));
        assertEquals("S", m.getParts().get(0).getDesc("en").getSubject());
        assertNotNull(m.getDesc("en"));
        assertEquals("T", m.getDesc("en").getTitle());
        assertEquals(UidUtils.uidToHashedFolderPath("messages", id), m.getRepoPath());
    }

    // --------------------------------------------------------------------------------------------- //
    // MessageKind mapping (kind is system-managed and only exposed on SystemMessageVo)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void kindDefaultsToWarning() {
        assertEquals(MessageKind.WARNING, new Message().getKind());
    }

    @Test
    public void toVoMapsKindToSystemMessageVo() {
        Message m = new Message();
        m.setUid(UidUtils.newUid());
        m.setStatus(Status.PUBLISHED);
        m.setKind(MessageKind.CANCELLATION);

        SystemMessageVo vo = m.toVo(SystemMessageVo.class,
                DataFilter.get().fields(DataFilter.ALL).lang("en"));

        assertEquals(MessageKind.CANCELLATION, vo.getKind());
    }

    @Test
    public void constructFromSystemMessageVoCopiesKind() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(UidUtils.newUid());
        vo.setKind(MessageKind.CANCELLATION);

        assertEquals(MessageKind.CANCELLATION, new Message(vo, DataFilter.get()).getKind());
    }

    @Test
    public void constructFromSystemMessageVoDefaultsNullKindToWarning() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(UidUtils.newUid());
        vo.setKind(null);

        assertEquals(MessageKind.WARNING, new Message(vo, DataFilter.get()).getKind());
    }

    @Test
    public void constructFromPlainMessageVoKeepsDefaultWarningKind() {
        MessageVo vo = new MessageVo();
        vo.setId(UidUtils.newUid());

        assertEquals(MessageKind.WARNING, new Message(vo, DataFilter.get()).getKind());
    }

    // --------------------------------------------------------------------------------------------- //
    // computeAreaTitle() / updateMessageTitle()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void computeAreaTitleIncludesAreaAndVicinity() {
        Message m = new Message();
        Area dk = area("Denmark");
        m.setAreas(new ArrayList<>(Arrays.asList(dk)));
        m.createDesc("en").setVicinity("Off the coast");

        assertEquals("Denmark. Off the coast.", m.computeAreaTitle("en"));
    }

    @Test
    public void updateMessageTitleComposesAreaAndSubjectWhenAutoTitle() {
        Message m = new Message();
        Area dk = area("Denmark");
        m.setAreas(new ArrayList<>(Arrays.asList(dk)));
        // no vicinity on the message desc -> title is "Denmark. " + subject
        m.setAutoTitle(true);

        MessagePart part = detailsPart(m);
        part.createDesc("en").setSubject("Buoy missing");

        m.updateMessageTitle();

        assertNotNull(m.getDesc("en"));
        assertEquals("Denmark. Buoy missing.", m.getDesc("en").getTitle());
    }

    @Test
    public void updateMessageTitleIsNoOpWhenAutoTitleDisabled() {
        Message m = new Message();
        Area dk = area("Denmark");
        m.setAreas(new ArrayList<>(Arrays.asList(dk)));
        m.setAutoTitle(false);

        MessagePart part = detailsPart(m);
        part.createDesc("en").setSubject("Buoy missing");

        m.updateMessageTitle();

        // No message-level title desc was created / mutated
        assertNull(m.getDesc("en"));
    }

    // --------------------------------------------------------------------------------------------- //
    // Additional helpers for the filter / collection-replacement tests
    // --------------------------------------------------------------------------------------------- //

    /** Builds an AreaVo carrying an id + English name. */
    private static AreaVo areaVo(Integer id, String enName) {
        AreaVo a = new AreaVo();
        a.setId(id);
        a.createDesc("en").setName(enName);
        return a;
    }

    /** Builds a CategoryVo carrying an id + English name. */
    private static CategoryVo categoryVo(Integer id, String enName) {
        CategoryVo c = new CategoryVo();
        c.setId(id);
        c.createDesc("en").setName(enName);
        return c;
    }

    /** Builds a ChartVo with the given chart number. */
    private static ChartVo chartVo(String chartNumber) {
        ChartVo c = new ChartVo();
        c.setChartNumber(chartNumber);
        return c;
    }

    /** Builds a ReferenceVo pointing at the given message id. */
    private static ReferenceVo refVo(String messageId) {
        ReferenceVo r = new ReferenceVo();
        r.setMessageId(messageId);
        return r;
    }

    /**
     * Builds a fully-populated message with areas/categories/charts/references/parts/descs/promulgations,
     * used to compare the field-presence behaviour of the different data filters. The message has no real
     * GeoJSON geometry (so {@code hasGeometry} stays false) - the filter tests assert on collection presence,
     * not on geometry content.
     */
    private static Message richMessage() {
        Message m = new Message();
        m.setUid(UidUtils.newUid());
        m.setNumber(7);
        m.setShortId("NW-007-23");
        m.setStatus(Status.PUBLISHED);
        m.setType(Type.LOCAL_WARNING);
        m.setMainType(MainType.NW);

        m.setAreas(new ArrayList<>(Arrays.asList(area("Denmark"))));

        Category cat = new Category();
        cat.createDesc("en").setName("Cat");
        m.setCategories(new ArrayList<>(Arrays.asList(cat)));

        Chart chart = new Chart();
        chart.setChartNumber("DK-1");
        m.setCharts(new ArrayList<>(Arrays.asList(chart)));

        Reference ref = new Reference();
        ref.setMessageId("NW-001-23");
        m.addReference(ref);

        MessagePart part = detailsPart(m);
        part.createDesc("en").setSubject("Subject");

        MessageDesc d = m.createDesc("en");
        d.setTitle("Title");
        d.setVicinity("Off the coast");
        d.setPublication("Pub");
        d.setSource("Src");
        d.setInternalPublication("Internal");

        return m;
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(...) under MESSAGE_MAP_FILTER vs MESSAGE_DETAILS_FILTER
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoUnderMapFilterIncludesPartsAndTitleButOmitsDetailCollections() {
        Message m = richMessage();

        MessageVo vo = m.toVo(MessageVo.class, Message.MESSAGE_MAP_FILTER.lang("en"));

        // Always-present identity fields
        assertEquals(m.getUid(), vo.getId());
        assertEquals(Integer.valueOf(7), vo.getNumber());
        assertEquals(Status.PUBLISHED, vo.getStatus());

        // The map filter does NOT include details -> the detail-only collections are left unset (null)
        assertNull(vo.getAreas(), "areas must be omitted under the map filter");
        assertNull(vo.getCategories(), "categories must be omitted under the map filter");
        assertNull(vo.getCharts(), "charts must be omitted under the map filter");
        assertNull(vo.getReferences(), "references must be omitted under the map filter");
        assertNull(vo.getMessageSeries(), "messageSeries must be omitted under the map filter");

        // Parts ARE included because the map filter includes geometry (parts gate on details OR geometry)
        assertNotNull(vo.getParts());
        assertEquals(1, vo.getParts().size());

        // Descs ARE included because the map filter carries the "MessageDesc.title" field
        assertNotNull(vo.getDescs());
        assertEquals(1, vo.getDescs().size());
        assertEquals("Title", vo.getDescs().get(0).getTitle());
    }

    @Test
    public void toVoUnderMapFilterEmitsTitleOnlyDesc() {
        Message m = richMessage();

        MessageVo vo = m.toVo(MessageVo.class, Message.MESSAGE_MAP_FILTER.lang("en"));

        // Under the map filter MessageDesc is in "title-only" mode: only the title round-trips,
        // the remaining localized fields are suppressed.
        MessageDescVo desc = vo.getDescs().get(0);
        assertEquals("Title", desc.getTitle());
        assertNull(desc.getVicinity(), "vicinity must be suppressed in title-only mode");
        assertNull(desc.getPublication(), "publication must be suppressed in title-only mode");
        assertNull(desc.getSource(), "source must be suppressed in title-only mode");
        assertNull(desc.getInternalPublication(), "internalPublication must be suppressed in title-only mode");
    }

    @Test
    public void toVoUnderDetailsFilterIncludesAllDetailCollections() {
        Message m = richMessage();

        MessageVo vo = m.toVo(MessageVo.class, Message.MESSAGE_DETAILS_FILTER.lang("en"));

        // Detail-only collections are now present
        assertNotNull(vo.getAreas(), "areas must be present under the details filter");
        assertEquals(1, vo.getAreas().size());
        assertNotNull(vo.getCategories(), "categories must be present under the details filter");
        assertEquals(1, vo.getCategories().size());
        assertNotNull(vo.getCharts(), "charts must be present under the details filter");
        assertEquals(1, vo.getCharts().size());
        assertEquals("DK-1", vo.getCharts().get(0).getChartNumber());
        assertNotNull(vo.getReferences(), "references must be present under the details filter");
        assertEquals(1, vo.getReferences().size());
        assertEquals("NW-001-23", vo.getReferences().get(0).getMessageId());

        assertNotNull(vo.getParts());
        assertEquals(1, vo.getParts().size());

        // The details filter emits the FULL message desc (title-only mode is off)
        MessageDescVo desc = vo.getDescs().get(0);
        assertEquals("Title", desc.getTitle());
        assertEquals("Off the coast", desc.getVicinity());
        assertEquals("Pub", desc.getPublication());
        assertEquals("Src", desc.getSource());
    }

    /** A message with a TIME, a DETAILS and a NOTE part, in that order, whose stored indexNo values are stale. */
    static Message threePartMessage() {
        Message m = new Message();
        m.setUid(UidUtils.newUid());
        m.setStatus(Status.PUBLISHED);
        m.setType(Type.LOCAL_WARNING);
        MessagePartType[] types = { MessagePartType.TIME, MessagePartType.DETAILS, MessagePartType.NOTE };
        int[] staleIndexNos = { 2, 0, 0 }; // as after an in-memory reorder, or for parts never flushed
        for (int i = 0; i < types.length; i++) {
            MessagePart part = new MessagePart(types[i]);
            part.setIndexNo(staleIndexNos[i]);
            part.createDesc("en").setSubject("Part " + i);
            m.addPart(part);
        }
        return m;
    }

    @Test
    public void toVoNumbersThePartsByTheirPlaceInTheMessage() {
        // MSG7: indexNo is the part's place in its message, 0, 1, 2 in order, under every filter that
        // includes the parts; the list position is used, not the stored (possibly stale) column.
        Message m = threePartMessage();

        for (DataFilter filter : List.of(Message.MESSAGE_DETAILS_FILTER.lang("en"),
                Message.MESSAGE_MAP_FILTER.lang("en"))) {
            MessageVo vo = m.toVo(MessageVo.class, filter);
            assertEquals(3, vo.getParts().size());
            for (int i = 0; i < 3; i++) {
                assertEquals(m.getParts().get(i).getType(), vo.getParts().get(i).getType());
                assertEquals(i, vo.getParts().get(i).getIndexNo(), "part " + i);
            }
        }

        // The public API copies the value object it serves; the copy keeps the numbers.
        MessageVo copy = m.toVo(MessageVo.class, Message.MESSAGE_DETAILS_FILTER.lang("en"))
                .copy(Message.MESSAGE_DETAILS_FILTER.lang("en"));
        assertEquals(List.of(0, 1, 2), copy.getParts().stream().map(MessagePartVo::getIndexNo).toList());
    }

    @Test
    public void toVoDetailsDescOmitsInternalPublicationWithoutUserRole() {
        Message m = richMessage();

        // No UserResolver attached -> filter.userInRole("user") is false
        MessageVo vo = m.toVo(MessageVo.class, Message.MESSAGE_DETAILS_FILTER.lang("en"));

        MessageDescVo desc = vo.getDescs().get(0);
        // Non-title fields present, but internalPublication is gated on the "user" role
        assertEquals("Pub", desc.getPublication());
        assertNull(desc.getInternalPublication(), "internalPublication requires the 'user' role");
    }

    @Test
    public void toVoDetailsDescIncludesInternalPublicationForUserRole() {
        Message m = richMessage();

        DataFilter filter = Message.MESSAGE_DETAILS_FILTER.lang("en").user(new DataFilter.UserResolver() {
            @Override
            public java.security.Principal getPrincipal() {
                return null;
            }
            @Override
            public boolean isUserInRole(String role) {
                return "user".equals(role);
            }
        });

        MessageVo vo = m.toVo(MessageVo.class, filter);

        MessageDescVo desc = vo.getDescs().get(0);
        assertEquals("Internal", desc.getInternalPublication());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(...) promulgation inclusion gated on filter + SystemMessageVo target
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoOmitsPromulgationsUnderDetailsFilter() {
        Message m = richMessage();
        BaseMessagePromulgation p = new BaseMessagePromulgation();
        PromulgationType t = new PromulgationType();
        t.setTypeId("navtex");
        p.setType(t);
        m.addPromulgation(p);

        // Plain details filter does NOT carry the "promulgations" field
        SystemMessageVo vo = m.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER.lang("en"));

        assertNull(vo.getPromulgations(), "promulgations must be omitted unless the filter requests them");
    }

    @Test
    public void toVoIncludesPromulgationsUnderDetailsAndPromulgationsFilter() {
        Message m = richMessage();
        BaseMessagePromulgation p = new BaseMessagePromulgation();
        PromulgationType t = new PromulgationType();
        t.setTypeId("navtex");
        p.setType(t);
        m.addPromulgation(p);

        SystemMessageVo vo = m.toVo(SystemMessageVo.class,
                Message.MESSAGE_DETAILS_AND_PROMULGATIONS_FILTER.lang("en"));

        // The promulgation list is mapped through BaseMessagePromulgation.toVo(), which on the
        // base class returns null -> the resulting list is non-null, size 1, holding a single null.
        // (Pins the current base-class behaviour; subclasses override toVo() to return a real VO.)
        assertNotNull(vo.getPromulgations(), "promulgations must be present under the promulgations filter");
        assertEquals(1, vo.getPromulgations().size());
        assertNull(vo.getPromulgations().get(0));
    }

    // --------------------------------------------------------------------------------------------- //
    // updateMessage(...) clear()+re-add of areas/categories/charts/references
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateMessageReplacesAreaCategoryChartAndReferenceCollections() {
        // Start from a message already carrying one of each kind of related entity
        Message m = new Message();
        m.setAreas(new ArrayList<>(Arrays.asList(area("Old Area"))));
        Category oldCat = new Category();
        oldCat.createDesc("en").setName("Old Cat");
        m.setCategories(new ArrayList<>(Arrays.asList(oldCat)));
        Chart oldChart = new Chart();
        oldChart.setChartNumber("OLD-1");
        m.setCharts(new ArrayList<>(Arrays.asList(oldChart)));
        Reference oldRef = new Reference();
        oldRef.setMessageId("OLD-REF");
        m.addReference(oldRef);

        // A VO that carries two of each kind
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(UidUtils.newUid());
        vo.setType(Type.LOCAL_WARNING);
        vo.setAreas(new ArrayList<>(Arrays.asList(areaVo(1, "New A"), areaVo(2, "New B"))));
        vo.setCategories(new ArrayList<>(Arrays.asList(categoryVo(10, "New C1"), categoryVo(11, "New C2"))));
        vo.setCharts(new ArrayList<>(Arrays.asList(chartVo("NEW-1"), chartVo("NEW-2"))));
        vo.setReferences(new ArrayList<>(Arrays.asList(refVo("NEW-REF-1"), refVo("NEW-REF-2"))));

        m.updateMessage(vo, DataFilter.get());

        // Old contents fully replaced (clear()+re-add), not appended to
        assertEquals(2, m.getAreas().size());
        assertEquals(2, m.getCategories().size());
        assertEquals(2, m.getCharts().size());
        assertEquals(2, m.getReferences().size());

        // The new values are present and ordered as in the VO
        assertEquals("NEW-1", m.getCharts().get(0).getChartNumber());
        assertEquals("NEW-2", m.getCharts().get(1).getChartNumber());
        assertEquals("NEW-REF-1", m.getReferences().get(0).getMessageId());
        assertEquals("NEW-REF-2", m.getReferences().get(1).getMessageId());

        // References were re-attached to this message (back-reference re-wired)
        assertSame(m, m.getReferences().get(0).getMessage());
    }

    @Test
    public void updateMessageClearsCollectionsWhenVoCollectionsAreNull() {
        Message m = new Message();
        m.setAreas(new ArrayList<>(Arrays.asList(area("Old Area"))));
        Chart oldChart = new Chart();
        oldChart.setChartNumber("OLD-1");
        m.setCharts(new ArrayList<>(Arrays.asList(oldChart)));
        Reference oldRef = new Reference();
        oldRef.setMessageId("OLD-REF");
        m.addReference(oldRef);

        // VO leaves all collections null
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(UidUtils.newUid());
        vo.setType(Type.LOCAL_WARNING);

        m.updateMessage(vo, DataFilter.get());

        // clear() runs unconditionally -> the old contents are gone even though the VO had nothing to add
        assertTrue(m.getAreas().isEmpty());
        assertTrue(m.getCharts().isEmpty());
        assertTrue(m.getReferences().isEmpty());
    }

    @Test
    public void updateMessageDropsBlankReferences() {
        Message m = new Message();

        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(UidUtils.newUid());
        vo.setType(Type.LOCAL_WARNING);
        // one good reference, one blank one
        vo.setReferences(new ArrayList<>(Arrays.asList(refVo("KEEP-ME"), refVo("   "))));

        m.updateMessage(vo, DataFilter.get());

        // updateMessage filters references on StringUtils.isNotBlank(messageId)
        assertEquals(1, m.getReferences().size());
        assertEquals("KEEP-ME", m.getReferences().get(0).getMessageId());
    }

    // --------------------------------------------------------------------------------------------- //
    // getDescs(DataFilter) sentinel / fallback behaviour
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void getDescsByFilterPicksRequestedLanguage() {
        Message m = new Message();
        m.createDesc("en").setTitle("English");
        m.createDesc("da").setTitle("Dansk");

        List<MessageDesc> descs = m.getDescs(DataFilter.get().lang("da"));

        assertEquals(1, descs.size());
        assertEquals("Dansk", descs.get(0).getTitle());
    }

    @Test
    public void getDescsByFilterFallsBackToFirstWhenLanguageMissing() {
        Message m = new Message();
        m.createDesc("en").setTitle("English");
        m.createDesc("da").setTitle("Dansk");

        // No description matches "fr" -> fall back to the first available description
        List<MessageDesc> descs = m.getDescs(DataFilter.get().lang("fr"));

        assertEquals(1, descs.size());
        assertEquals("English", descs.get(0).getTitle());
    }

    @Test
    public void getDescsByFilterReturnsAllWhenNoLanguageGiven() {
        Message m = new Message();
        m.createDesc("en").setTitle("English");
        m.createDesc("da").setTitle("Dansk");

        // A filter with no language includes every description
        List<MessageDesc> descs = m.getDescs(DataFilter.get());

        assertEquals(2, descs.size());
    }

    // --------------------------------------------------------------------------------------------- //
    // copyDescsFrom-style copy via copyDescs(...) + title-only MessageDesc construction
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void copyDescsMergesIntoExistingDescByLanguage() {
        Message m = new Message();
        // pre-existing English desc with a stale title
        m.createDesc("en").setTitle("Stale");

        // a detached source desc (not attached to any message)
        MessageDesc src = new MessageDesc();
        src.setLang("en");
        src.setTitle("Fresh");
        src.setVicinity("Near here");

        m.copyDescs(new ArrayList<>(Arrays.asList(src)));

        // copyDescs copies onto the EXISTING desc for that language (no duplicate created)
        assertEquals(1, m.getDescs().size());
        assertEquals("Fresh", m.getDesc("en").getTitle());
        assertEquals("Near here", m.getDesc("en").getVicinity());
        // the copy mutated the message's own desc, not the source
        assertSame(m, m.getDesc("en").getEntity());
    }

    @Test
    public void copyDescsCreatesDescForNewLanguage() {
        Message m = new Message();
        m.createDesc("en").setTitle("English");

        MessageDesc danish = new MessageDesc();
        danish.setLang("da");
        danish.setTitle("Dansk");

        m.copyDescs(new ArrayList<>(Arrays.asList(danish)));

        // a new desc is created for the previously-absent language
        assertEquals(2, m.getDescs().size());
        assertEquals("English", m.getDesc("en").getTitle());
        assertEquals("Dansk", m.getDesc("da").getTitle());
    }

    @Test
    public void messageDescTitleOnlyFilterCopiesOnlyTitle() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setTitle("Just a title");
        vo.setVicinity("ignored");
        vo.setPublication("ignored");
        vo.setSource("ignored");

        // "MessageDesc.title" reduces to "title" after forComponent(MessageDesc) -> title-only copy mode
        MessageDesc desc = new MessageDesc(vo, DataFilter.get().fields("MessageDesc.title"));

        assertEquals("Just a title", desc.getTitle());
        assertNull(desc.getVicinity(), "vicinity must not be copied in title-only mode");
        assertNull(desc.getPublication(), "publication must not be copied in title-only mode");
        assertNull(desc.getSource(), "source must not be copied in title-only mode");
    }

    @Test
    public void messageDescFullFilterCopiesAllFields() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setTitle("Title");
        vo.setVicinity("Vicinity");
        vo.setPublication("Publication");
        vo.setSource("Source");
        vo.setInternalPublication("Internal");

        // A filter that neither requests "title" nor "*" copies every field (the full-copy branch).
        // NB: passing DataFilter.ALL would instead trigger title-only mode, because includeField("title")
        // returns true whenever "*" is present.
        MessageDesc desc = new MessageDesc(vo, DataFilter.get());

        assertEquals("Title", desc.getTitle());
        assertEquals("Vicinity", desc.getVicinity());
        assertEquals("Publication", desc.getPublication());
        assertEquals("Source", desc.getSource());
        assertEquals("Internal", desc.getInternalPublication());
    }

    // --------------------------------------------------------------------------------------------- //
    // onPersist() ordering: hasGeometry recompute + uid/repoPath derivation
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void onPersistKeepsExistingUidButRederivesRepoPath() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);

        String uid = UidUtils.newUid();
        m.setUid(uid);
        // a deliberately wrong repo path that must be overwritten
        m.setRepoPath("messages/bogus/path");

        m.onPersist();

        // existing uid retained (assignNewUid only runs when uid == null)
        assertEquals(uid, m.getUid());
        // repoPath always re-derived from the uid
        assertEquals(UidUtils.uidToHashedFolderPath("messages", uid), m.getRepoPath());
    }

    @Test
    public void onPersistSetsHasGeometryWhenAPartHasGeometry() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);
        m.setUid(UidUtils.newUid());

        // a part carrying a geometry feature collection
        MessagePart part = detailsPart(m);
        org.niord.core.geojson.FeatureCollection fc = new org.niord.core.geojson.FeatureCollection();
        part.setGeometry(fc);

        assertFalse(m.getHasGeometry());
        m.onPersist();
        assertTrue(m.getHasGeometry(), "hasGeometry must be recomputed to true when a part has geometry");
    }

    @Test
    public void onPersistClearsHasGeometryWhenNoPartHasGeometry() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);
        m.setUid(UidUtils.newUid());
        detailsPart(m); // a part with no geometry

        // pre-seed a stale true value to prove it is recomputed, not merely left alone
        m.setHasGeometry(true);
        m.onPersist();
        assertFalse(m.getHasGeometry(), "hasGeometry must be recomputed to false when no part has geometry");
    }

    @Test
    public void onPersistNullsAreaWhenNoAreas() {
        Message m = new Message();
        m.setType(Type.LOCAL_WARNING);
        m.setAutoTitle(false);
        m.setUid(UidUtils.newUid());
        // seed a stale primary area to prove it is recomputed
        m.setArea(area("Stale"));

        m.onPersist();

        assertNull(m.getArea(), "primary area must be null when the areas list is empty");
    }
}
