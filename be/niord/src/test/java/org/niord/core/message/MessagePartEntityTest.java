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
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.message.DateIntervalVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MessagePart} entity's pure (non-persistent) logic: the default-type and
 * value-object constructors (event-date filtering/sorting and description filtering by
 * {@code descDefined()}), {@link MessagePart#partDefined()} (which mutates by removing undefined event
 * dates and blank descriptions), the {@code toVo(DataFilter)} mapping under different filters,
 * {@link MessagePart#updateMessagePart(MessagePart)}, and the small collection helpers
 * ({@code createDesc}, {@code addDesc}, {@code addEventDates}).
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. {@link MessagePart}
 * and its VOs are built directly via constructors/setters and asserted on concrete field, ordering and
 * collection state, so the tests survive a future Quarkus-&gt;Spring port. A {@link MessagePart}
 * requires no parent {@link Message} to exercise any of the behavior covered here.
 *
 * <p>Date values are built from fixed millis so the tests are deterministic and timezone-independent.
 */
public class MessagePartEntityTest {

    /** Helper: builds a DateIntervalVo with the given from/to millis (null means unset). */
    private static DateIntervalVo dateVo(Long fromMillis, Long toMillis) {
        DateIntervalVo vo = new DateIntervalVo();
        vo.setFromDate(fromMillis == null ? null : new Date(fromMillis));
        vo.setToDate(toMillis == null ? null : new Date(toMillis));
        return vo;
    }

    /** Helper: builds a MessagePartDescVo. */
    private static MessagePartDescVo descVo(String lang, String subject, String details) {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang(lang);
        vo.setSubject(subject);
        vo.setDetails(details);
        return vo;
    }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    @Test
    public void defaultConstructorUsesDetailsType() {
        MessagePart part = new MessagePart();
        assertEquals(MessagePartType.DETAILS, part.getType());
        assertTrue(part.getEventDates().isEmpty(), "fresh part should have an empty event-date list");
        assertTrue(part.getDescs().isEmpty(), "fresh part should have an empty desc list");
        assertNull(part.getHideSubject());
    }

    @Test
    public void typeConstructorSetsType() {
        MessagePart part = new MessagePart(MessagePartType.TIME);
        assertEquals(MessagePartType.TIME, part.getType());
    }

    @Test
    public void voConstructorCopiesTypeFiltersUndefinedEventDatesAndBlankDescs() {
        MessagePartVo vo = new MessagePartVo();
        vo.setType(MessagePartType.TIME);
        vo.setHideSubject(Boolean.TRUE);

        // One defined interval (from=1000, to=2000) and one entirely-undefined interval.
        List<DateIntervalVo> dates = new ArrayList<>();
        dates.add(dateVo(1000L, 2000L));
        dates.add(dateVo(null, null));
        vo.setEventDates(dates);

        // One defined desc (subject "S") and one entirely blank desc.
        List<MessagePartDescVo> descs = new ArrayList<>();
        descs.add(descVo("en", "S", null));
        descs.add(descVo("da", "", ""));
        vo.setDescs(descs);

        MessagePart part = new MessagePart(vo);

        assertEquals(MessagePartType.TIME, part.getType());
        assertEquals(Boolean.TRUE, part.getHideSubject());

        // Undefined interval filtered out.
        assertEquals(1, part.getEventDates().size());
        DateInterval di = part.getEventDates().get(0);
        assertEquals(new Date(1000L), di.getFromDate());
        assertEquals(new Date(2000L), di.getToDate());
        // The copied interval is back-linked to the part.
        assertSame(part, di.getMessagePart());

        // Blank desc filtered out, only the defined one copied with its subject.
        assertEquals(1, part.getDescs().size());
        MessagePartDesc desc = part.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals("S", desc.getSubject());
        assertNull(desc.getDetails());
        assertSame(part, desc.getEntity());
    }

    @Test
    public void voConstructorSortsEventDatesAscending() {
        MessagePartVo vo = new MessagePartVo();
        vo.setType(MessagePartType.TIME);

        // Out of order: later interval (from=5000) added before the earlier one (from=1000).
        List<DateIntervalVo> dates = new ArrayList<>();
        dates.add(dateVo(5000L, 6000L));
        dates.add(dateVo(1000L, 2000L));
        vo.setEventDates(dates);

        MessagePart part = new MessagePart(vo);

        assertEquals(2, part.getEventDates().size());
        assertEquals(new Date(1000L), part.getEventDates().get(0).getFromDate(),
                "earliest interval must come first after sorting");
        assertEquals(new Date(5000L), part.getEventDates().get(1).getFromDate());
    }

    @Test
    public void voConstructorWithFilterCopiesSameContent() {
        MessagePartVo vo = new MessagePartVo();
        vo.setType(MessagePartType.NOTE);
        List<MessagePartDescVo> descs = new ArrayList<>();
        descs.add(descVo("en", null, "the details"));
        vo.setDescs(descs);

        MessagePart part = new MessagePart(vo, DataFilter.get());

        assertEquals(MessagePartType.NOTE, part.getType());
        assertEquals(1, part.getDescs().size());
        assertEquals("the details", part.getDescs().get(0).getDetails());
        assertNull(part.getDescs().get(0).getSubject());
    }

    @Test
    public void voConstructorWithNullCollectionsLeavesEmptyLists() {
        MessagePartVo vo = new MessagePartVo();
        vo.setType(MessagePartType.POSITIONS);
        // eventDates and descs left null on the VO.

        MessagePart part = new MessagePart(vo);

        assertEquals(MessagePartType.POSITIONS, part.getType());
        assertTrue(part.getEventDates().isEmpty());
        assertTrue(part.getDescs().isEmpty());
        assertNull(part.getHideSubject());
    }

    // ------------------------------------------------------------------
    // partDefined()
    // ------------------------------------------------------------------

    @Test
    public void partDefinedFalseForEmptyPart() {
        MessagePart part = new MessagePart();
        assertFalse(part.partDefined());
    }

    @Test
    public void partDefinedTrueWithOneDefinedDesc() {
        MessagePart part = new MessagePart();
        MessagePartDesc desc = part.createDesc("en");
        desc.setSubject("Subject");

        assertTrue(part.partDefined());
        assertEquals(1, part.getDescs().size(), "the defined desc must be retained");
    }

    @Test
    public void partDefinedFalseAndRemovesBlankDescOnly() {
        MessagePart part = new MessagePart();
        // Blank desc: no subject, no details.
        part.createDesc("en");

        assertFalse(part.partDefined(), "blank-only part is not defined");
        assertTrue(part.getDescs().isEmpty(), "the blank desc must have been removed");
    }

    @Test
    public void partDefinedTrueWithOneDefinedEventDate() {
        MessagePart part = new MessagePart();
        part.addEventDates(new DateInterval(null, new Date(1000L), new Date(2000L)));

        assertTrue(part.partDefined());
        assertEquals(1, part.getEventDates().size());
    }

    @Test
    public void partDefinedRemovesUndefinedEventDate() {
        MessagePart part = new MessagePart();
        part.addEventDates(new DateInterval(null, null, null));

        assertFalse(part.partDefined());
        assertTrue(part.getEventDates().isEmpty(), "the undefined event date must have been removed");
    }

    // ------------------------------------------------------------------
    // toVo(DataFilter)
    // ------------------------------------------------------------------

    @Test
    public void toVoWithoutDetailsOmitsEventDatesAndDescs() {
        MessagePart part = new MessagePart(MessagePartType.TIME);
        part.setHideSubject(Boolean.TRUE);
        part.addEventDates(new DateInterval(null, new Date(1000L), new Date(2000L)));
        MessagePartDesc desc = part.createDesc("en");
        desc.setSubject("S");

        MessagePartVo vo = part.toVo(DataFilter.get());

        assertEquals(MessagePartType.TIME, vo.getType());
        assertEquals(Boolean.TRUE, vo.getHideSubject());
        // checkCreateEventDates / checkCreateDescs were never called -> still null on the VO.
        assertNull(vo.getEventDates());
        assertNull(vo.getDescs());
        assertNull(vo.getGeometry());
    }

    @Test
    public void toVoWithDetailsIncludesEventDatesAndDescs() {
        MessagePart part = new MessagePart(MessagePartType.TIME);
        part.setHideSubject(Boolean.FALSE);
        part.addEventDates(new DateInterval(Boolean.TRUE, new Date(1000L), new Date(2000L)));
        MessagePartDesc desc = part.createDesc("en");
        desc.setSubject("Subject");
        desc.setDetails("Details");

        MessagePartVo vo = part.toVo(DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals(MessagePartType.TIME, vo.getType());
        assertEquals(Boolean.FALSE, vo.getHideSubject());

        assertEquals(1, vo.getEventDates().size());
        DateIntervalVo diVo = vo.getEventDates().get(0);
        assertEquals(Boolean.TRUE, diVo.getAllDay());
        assertEquals(new Date(1000L), diVo.getFromDate());
        assertEquals(new Date(2000L), diVo.getToDate());

        assertEquals(1, vo.getDescs().size());
        MessagePartDescVo descVo = vo.getDescs().get(0);
        assertEquals("en", descVo.getLang());
        assertEquals("Subject", descVo.getSubject());
        assertEquals("Details", descVo.getDetails());

        // Geometry left null -> stays null even with DETAILS.
        assertNull(vo.getGeometry());
    }

    // ------------------------------------------------------------------
    // updateMessagePart(other)
    // ------------------------------------------------------------------

    @Test
    public void updateMessagePartCopiesTypeDatesDescsAndHideSubjectButNotTheIndex() {
        MessagePart source = new MessagePart(MessagePartType.TIME);
        source.setIndexNo(7);
        source.setHideSubject(Boolean.TRUE);
        // Out-of-order, plus an undefined interval that must be dropped.
        source.addEventDates(new DateInterval(null, new Date(5000L), new Date(6000L)));
        source.addEventDates(new DateInterval(null, new Date(1000L), new Date(2000L)));
        source.addEventDates(new DateInterval(null, null, null));
        MessagePartDesc sDesc = source.createDesc("en");
        sDesc.setSubject("New subject");
        sDesc.setDetails("New details");

        MessagePart target = new MessagePart(MessagePartType.DETAILS);
        target.setIndexNo(0);
        // Pre-existing event date that must be replaced.
        target.addEventDates(new DateInterval(null, new Date(99000L), new Date(99100L)));

        target.updateMessagePart(source);

        assertEquals(0, target.getIndexNo(), "the list position is the order; indexNo is read-only");
        assertEquals(MessagePartType.TIME, target.getType());
        assertEquals(Boolean.TRUE, target.getHideSubject());

        // Old event date replaced; defined ones kept and sorted; undefined one dropped.
        assertEquals(2, target.getEventDates().size());
        assertEquals(new Date(1000L), target.getEventDates().get(0).getFromDate());
        assertEquals(new Date(5000L), target.getEventDates().get(1).getFromDate());
        // Copied intervals are back-linked to the target part.
        assertSame(target, target.getEventDates().get(0).getMessagePart());

        assertEquals(1, target.getDescs().size());
        MessagePartDesc copied = target.getDescs().get(0);
        assertEquals("en", copied.getLang());
        assertEquals("New subject", copied.getSubject());
        assertEquals("New details", copied.getDetails());
    }

    @Test
    public void updateMessagePartRemovesBlankCopiedDescs() {
        MessagePart source = new MessagePart(MessagePartType.NOTE);
        // A blank desc on the source must not survive the copy.
        source.createDesc("en");

        MessagePart target = new MessagePart(MessagePartType.DETAILS);

        target.updateMessagePart(source);

        assertEquals(MessagePartType.NOTE, target.getType());
        assertTrue(target.getDescs().isEmpty(), "blank desc should not be copied into the target");
    }

    // ------------------------------------------------------------------
    // createDesc / addDesc / addEventDates
    // ------------------------------------------------------------------

    @Test
    public void createDescReturnsDescBackLinkedAndAddedToList() {
        MessagePart part = new MessagePart();

        MessagePartDesc desc = part.createDesc("en");

        assertEquals("en", desc.getLang());
        assertSame(part, desc.getEntity());
        assertEquals(1, part.getDescs().size());
        assertSame(desc, part.getDescs().get(0));
    }

    @Test
    public void addDescSetsEntityAndAppends() {
        MessagePart part = new MessagePart();
        MessagePartDesc desc = new MessagePartDesc();
        desc.setLang("da");
        desc.setSubject("Emne");

        part.addDesc(desc);

        assertSame(part, desc.getEntity());
        assertEquals(1, part.getDescs().size());
        assertSame(desc, part.getDescs().get(0));
    }

    @Test
    public void addEventDatesBackLinksAddsAndReturnsInterval() {
        MessagePart part = new MessagePart();
        DateInterval di = new DateInterval(null, new Date(1000L), new Date(2000L));

        DateInterval returned = part.addEventDates(di);

        assertSame(di, returned, "addEventDates should return the same interval");
        assertSame(part, di.getMessagePart());
        assertEquals(1, part.getEventDates().size());
        assertSame(di, part.getEventDates().get(0));
    }

    // ------------------------------------------------------------------
    // toVo(DataFilter) — geometry inclusion
    // ------------------------------------------------------------------

    /**
     * Builds a {@link FeatureCollection} entity carrying the given uid and a single point feature.
     * Uses {@link JtsConverter#toJtsPoint(double, double)} so a real, non-empty geometry is produced
     * (no DB / Quarkus involved).
     */
    private static FeatureCollection pointFeatureCollection(String uid, double lat, double lon) {
        FeatureCollection fc = new FeatureCollection();
        fc.setUid(uid);
        Feature feature = new Feature();
        feature.setGeometry(JtsConverter.toJtsPoint(lat, lon));
        fc.addFeature(feature);
        return fc;
    }

    @Test
    public void toVoWithGeometryFieldIncludesGeometryInVo() {
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.setGeometry(pointFeatureCollection("fc-uid-1", 56.0, 11.0));

        MessagePartVo vo = part.toVo(DataFilter.get().fields(DataFilter.GEOMETRY));

        FeatureCollectionVo geo = vo.getGeometry();
        assertNotNull(geo, "GEOMETRY field requested -> geometry must be mapped onto the VO");
        // The entity uid surfaces as the GeoJson collection id.
        assertEquals("fc-uid-1", geo.getId());
        assertNotNull(geo.getFeatures());
        assertEquals(1, geo.getFeatures().length, "the single point feature must be carried over");
        // Without DETAILS, descs/eventDates are still omitted even though geometry is present.
        assertNull(vo.getEventDates());
        assertNull(vo.getDescs());
    }

    @Test
    public void toVoWithoutGeometryFieldOmitsGeometryEvenWhenPresent() {
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        // Geometry IS set on the entity ...
        part.setGeometry(pointFeatureCollection("fc-uid-2", 56.0, 11.0));

        // ... but the filter does not request the GEOMETRY field (default filter).
        MessagePartVo vo = part.toVo(DataFilter.get());

        assertNull(vo.getGeometry(),
                "geometry must be omitted from the VO when the GEOMETRY field is not requested");
    }

    @Test
    public void toVoWithGeometryFieldButNullGeometryLeavesVoGeometryNull() {
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        // No geometry set on the entity.

        MessagePartVo vo = part.toVo(DataFilter.get().fields(DataFilter.GEOMETRY));

        assertNull(vo.getGeometry(),
                "a null entity geometry stays null on the VO even when GEOMETRY is requested");
    }

    // ------------------------------------------------------------------
    // updateMessagePart(other) — geometry handling
    // ------------------------------------------------------------------

    @Test
    public void updateMessagePartLeavesTargetGeometryUntouched() {
        // updateMessagePart copies index/type/eventDates/descs/hideSubject but NOT geometry.
        FeatureCollection sourceGeo = pointFeatureCollection("source-geo", 10.0, 10.0);
        MessagePart source = new MessagePart(MessagePartType.TIME);
        source.setGeometry(sourceGeo);

        FeatureCollection targetGeo = pointFeatureCollection("target-geo", 20.0, 20.0);
        MessagePart target = new MessagePart(MessagePartType.DETAILS);
        target.setGeometry(targetGeo);

        target.updateMessagePart(source);

        assertEquals(MessagePartType.TIME, target.getType(), "type is copied");
        assertSame(targetGeo, target.getGeometry(),
                "target keeps its OWN geometry instance — source geometry is not copied");
        assertEquals("target-geo", target.getGeometry().getUid());
    }

    @Test
    public void updateMessagePartDoesNotClearExistingTargetGeometryWhenSourceHasNone() {
        MessagePart source = new MessagePart(MessagePartType.TIME); // no geometry on source
        FeatureCollection targetGeo = pointFeatureCollection("target-geo", 20.0, 20.0);
        MessagePart target = new MessagePart(MessagePartType.DETAILS);
        target.setGeometry(targetGeo);

        target.updateMessagePart(source);

        assertSame(targetGeo, target.getGeometry(),
                "a source without geometry must not null-out the target geometry");
    }

    // ------------------------------------------------------------------
    // partDefined() — geometry makes it true; mutation idempotency
    // ------------------------------------------------------------------

    @Test
    public void partDefinedTrueWhenOnlyGeometryIsPresent() {
        MessagePart part = new MessagePart();
        part.setGeometry(pointFeatureCollection("geo-only", 56.0, 11.0));

        assertTrue(part.partDefined(), "a part with only geometry (no dates/descs) is still defined");
    }

    @Test
    public void partDefinedIsIdempotentAndPrunesBlanksExactlyOnce() {
        MessagePart part = new MessagePart();
        // One defined desc, one blank desc.
        part.createDesc("en").setSubject("Subject");
        part.createDesc("da"); // blank -> must be pruned
        // One defined event date, one undefined event date.
        part.addEventDates(new DateInterval(null, new Date(1000L), new Date(2000L)));
        part.addEventDates(new DateInterval(null, null, null)); // undefined -> must be pruned

        boolean first = part.partDefined();
        // After the first call the blanks/undefined entries are already removed.
        assertEquals(1, part.getDescs().size(), "blank desc pruned on first call");
        assertEquals(1, part.getEventDates().size(), "undefined event date pruned on first call");

        boolean second = part.partDefined();

        assertTrue(first, "first call reports the part as defined");
        assertEquals(first, second, "calling partDefined twice yields the same result");
        // Second call must not prune anything further nor re-add anything.
        assertEquals(1, part.getDescs().size(), "desc list unchanged on the second call");
        assertEquals(1, part.getEventDates().size(), "event-date list unchanged on the second call");
        assertEquals("Subject", part.getDescs().get(0).getSubject(),
                "the surviving desc is the defined one");
    }
}
