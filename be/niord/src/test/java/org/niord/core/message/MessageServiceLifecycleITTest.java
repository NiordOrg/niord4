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
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;
import org.niord.core.domain.DomainService;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.message.MessageService.AdjustmentType;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.AreaVo;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.ChartVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the currently-uncovered {@link MessageService} lifecycle-helper cluster, run
 * against the seeded MySQL container of the {@link ITTest} infrastructure. These complement
 * {@code MessageServiceITTest} (create/update/status lifecycle) and {@code MessageSearchServiceITTest}
 * (the search path) by targeting the auto-field / adjustment / base-data helpers and the
 * history-backed "recently edited" queries.
 *
 * <p>The seed contains <em>no</em> {@code Message} rows, so every test creates the data it needs and
 * is wrapped in {@link TestTransaction} so all writes roll back, keeping the shared seed pristine and
 * the tests independent and repeatable. Persisted state is asserted after {@code em.flush()}/
 * {@code em.clear()} reloads so we observe what really hit the database.
 *
 * <p>The methods exercised here:
 * <ul>
 *   <li>{@link MessageService#computeMessageAreaSortingOrder(Message)} — computes <em>and persists</em>
 *       a new {@code areaSortOrder} (verified for both the area-less zero case and a custom
 *       {@code messageSorting=NS} area where the value is derived from latitude).</li>
 *   <li>{@link MessageService#updateAutoMessageFields(Message)} — recomputes the auto-title.</li>
 *   <li>{@link MessageService#adjustMessage(SystemMessageVo, AdjustmentType...)} — the
 *       {@code AREAS} / {@code SHORT_ID} / {@code TITLE} adjustment selectors, independently and
 *       combined.</li>
 *   <li>{@link MessageService#checkUpdateShortId(Message)} — the {@code MANUAL}-series short-ID path
 *       (seeded series {@code dma-nm-almanac}, shortFormat {@code A/${number} ${year}}).</li>
 *   <li>{@link MessageService#updateBaseDate(SystemMessageVo)} — detached area/category/chart VOs are
 *       replaced by persisted entities (ids + descriptors re-populated).</li>
 *   <li>{@link MessageService#findUpdatedMessages(Date, int)} — the {@code updated > date} window and
 *       the {@code maxCount} cap.</li>
 *   <li>{@link MessageService#getMostRecentlyEditedMessages(int, Status...)} /
 *       {@link MessageService#getMostRecentlyEditedDrafts(int)} — the documented empty-result branches
 *       (no current user). The populated path is not IT-testable here; see the note on that test and
 *       docs/untestable-code.md.</li>
 * </ul>
 *
 * <p><b>MSG1 regression pin.</b> {@code adjustMessage(..., SHORT_ID)} -> {@code checkUpdateShortId}
 * resolves the persisted series before validating, so it is NOT affected by the MSG1 bug. The MSG1 bug
 * lives in {@code updateMessage} (it validates the type against the <em>unresolved</em> detached
 * series); the regression pin below asserts that current behaviour. See docs/potential-errors.md (MSG1).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageServiceLifecycleITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    ChartService chartService;

    @Autowired
    DomainService domainService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * A fixed PAST publish date. Anything that ends up taking a message number takes it inside the
     * {@link TestTransaction}, which rolls it back but holds that year's number row until then; with this
     * date it is the year-2001 row rather than the current-year one that other suites assert starts at 1.
     * The lifecycle helpers here never publish, but
     * {@code checkUpdateShortId} stamps a publish date if none is set, so we always supply this one.
     */
    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached, un-persisted draft message template with one DETAILS part and an "en" title. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries(seriesId);
        if (type != null) {
            series.setMainType(type.getMainType());
        }
        msg.setMessageSeries(series);
        msg.setType(type);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
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

    /** Attaches a single-point part geometry (lon/lat order) to the first part of the message. */
    private void addPointGeometry(Message msg, double lat, double lon) {
        FeatureCollection fc = new FeatureCollection();
        Feature f = new Feature();
        f.setGeometry(JtsConverter.toJtsPoint(lat, lon));
        fc.addFeature(f);
        msg.getParts().get(0).setGeometry(fc);
    }

    /**
     * Resolves the seeded "Denmark" area (the root of the Danish area tree, which carries the
     * country-wide geometry) by its stable name and returns the managed entity. PostgreSQL's
     * per-entity sequences assign different numeric ids than the old MySQL shared sequence, so seed
     * ids (formerly area 1192) are no longer stable; tests must look the entity up by a business key.
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
    // computeMessageAreaSortingOrder - recompute + persist
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void computeMessageAreaSortingOrderRecomputesZeroForAreaLessMessageAndPersistsIt() throws Exception {
        Message created = createDraftNw("Area sort recompute");
        Integer id = created.getId();

        // Seed a deliberately-bogus value so a no-op would be detectable.
        created.setAreaSortOrder(987.0);
        em.flush();

        // With no associated area, the algorithm short-circuits to 0.0 and saveMessage persists it.
        messageService.computeMessageAreaSortingOrder(created);
        assertEquals(0.0, created.getAreaSortOrder(), 0.0001,
                "an area-less message must recompute to a 0.0 sort order");

        Message reloaded = reload(id);
        assertEquals(0.0, reloaded.getAreaSortOrder(), 0.0001,
                "the recomputed 0.0 sort order must be persisted (not the bogus 987.0)");
    }

    @Test
    @Transactional
    void computeMessageAreaSortingOrderDerivesFromLatitudeForNsArea() throws Exception {
        // A north-south (NS) sorting area yields areaSortOrder == -latitude (plus a tiny random epsilon).
        Area nsArea = new Area();
        nsArea.setActive(true);
        nsArea.setMessageSorting(AreaMessageSorting.NS);
        nsArea.setGeometry(JtsConverter.toJtsPoint(55.0, 12.0));
        nsArea.createDesc("en").setName("IT-NS-SortArea");
        Area persistedArea = areaService.createArea(nsArea, null);
        em.flush();

        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "NS sorted");
        msg.getAreas().add(persistedArea);
        addPointGeometry(msg, 55.0, 12.0); // center latitude = 55.0
        Message created = messageService.createMessage(msg);
        Integer id = created.getId();

        // Overwrite with a bogus value, then recompute.
        created.setAreaSortOrder(123456.0);
        em.flush();

        messageService.computeMessageAreaSortingOrder(created);
        // NS => order = -lat = -55.0 (+ random/1e6). Assert it landed in the expected narrow band.
        assertTrue(created.getAreaSortOrder() <= -54.999 && created.getAreaSortOrder() >= -55.001,
                "an NS area at latitude 55 must yield a sort order of ~ -55, but was "
                        + created.getAreaSortOrder());

        Message reloaded = reload(id);
        assertTrue(reloaded.getAreaSortOrder() <= -54.999 && reloaded.getAreaSortOrder() >= -55.001,
                "the latitude-derived sort order must be persisted, but was " + reloaded.getAreaSortOrder());
    }

    /**
     * Exercises the private spherical-math sort ({@code computeCwOrCcwSortOrder}/{@code lon2x}/{@code lat2y})
     * reached only through the CW/CCW arm of {@link MessageService#computeMessageAreaSortingOrder(Message)}.
     * <p>
     * With the sort origin at (0,0) and origin-angle 0, a message centred <em>due north</em> of the origin
     * projects to a stereographic angle of 90°. A clockwise (CW) area negates that angle, so the sort order
     * is ~ -90 (plus the tiny random epsilon); its counter-clockwise (CCW) twin below yields ~ +90 from the
     * same fixture, which is what makes the two arms distinguishable.
     */
    @Test
    @Transactional
    void computeMessageAreaSortingOrderProjectsAngleForCwArea() throws Exception {
        Area cwArea = new Area();
        cwArea.setActive(true);
        cwArea.setMessageSorting(AreaMessageSorting.CW);
        cwArea.setOriginLatitude(0.0f);
        cwArea.setOriginLongitude(0.0f);
        cwArea.setOriginAngle(0);
        cwArea.setGeometry(JtsConverter.toJtsPoint(0.0, 0.0));
        cwArea.createDesc("en").setName("IT-CW-SortArea");
        Area persistedArea = areaService.createArea(cwArea, null);
        em.flush();

        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "CW sorted");
        msg.getAreas().add(persistedArea);
        addPointGeometry(msg, 1.0, 0.0); // centre due north of the (0,0) origin
        Message created = messageService.createMessage(msg);
        Integer id = created.getId();

        created.setAreaSortOrder(123456.0);
        em.flush();

        messageService.computeMessageAreaSortingOrder(created);
        // CW => -(90°) (+ random/1e6). Assert the narrow band.
        assertTrue(created.getAreaSortOrder() <= -89.999 && created.getAreaSortOrder() >= -90.001,
                "a CW area with a due-north message must yield a sort order of ~ -90, but was "
                        + created.getAreaSortOrder());

        Message reloaded = reload(id);
        assertTrue(reloaded.getAreaSortOrder() <= -89.999 && reloaded.getAreaSortOrder() >= -90.001,
                "the CW projected sort order must be persisted, but was " + reloaded.getAreaSortOrder());
    }

    @Test
    @Transactional
    void computeMessageAreaSortingOrderNegatesAngleDirectionForCcwArea() throws Exception {
        // Same fixture as the CW case, but a counter-clockwise area keeps the angle positive: ~ +90.
        Area ccwArea = new Area();
        ccwArea.setActive(true);
        ccwArea.setMessageSorting(AreaMessageSorting.CCW);
        ccwArea.setOriginLatitude(0.0f);
        ccwArea.setOriginLongitude(0.0f);
        ccwArea.setOriginAngle(0);
        ccwArea.setGeometry(JtsConverter.toJtsPoint(0.0, 0.0));
        ccwArea.createDesc("en").setName("IT-CCW-SortArea");
        Area persistedArea = areaService.createArea(ccwArea, null);
        em.flush();

        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "CCW sorted");
        msg.getAreas().add(persistedArea);
        addPointGeometry(msg, 1.0, 0.0); // centre due north of the (0,0) origin
        Message created = messageService.createMessage(msg);
        Integer id = created.getId();

        created.setAreaSortOrder(123456.0);
        em.flush();

        messageService.computeMessageAreaSortingOrder(created);
        // CCW => +(90°) (+ random/1e6). Assert the narrow band — the opposite sign of the CW case.
        assertTrue(created.getAreaSortOrder() >= 89.999 && created.getAreaSortOrder() <= 90.001,
                "a CCW area with a due-north message must yield a sort order of ~ +90, but was "
                        + created.getAreaSortOrder());

        Message reloaded = reload(id);
        assertTrue(reloaded.getAreaSortOrder() >= 89.999 && reloaded.getAreaSortOrder() <= 90.001,
                "the CCW projected sort order must be persisted, but was " + reloaded.getAreaSortOrder());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateAutoMessageFields - auto-title recompute
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateAutoMessageFieldsCopiesSubjectIntoTitleWhenAutoTitleOn() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "ignored-when-auto");
        msg.setAutoTitle(true);
        // No areas => the auto-title is just the part subject(s).
        Message result = messageService.updateAutoMessageFields(msg);

        assertEquals("subject.", result.getDesc("en").getTitle(),
                "with auto-title on, the title must be (re)computed from the part subject");
    }

    @Test
    @Transactional
    void updateAutoMessageFieldsLeavesTitleUntouchedWhenAutoTitleOff() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "manual title stays");
        msg.setAutoTitle(false);
        Message result = messageService.updateAutoMessageFields(msg);

        assertEquals("manual title stays", result.getDesc("en").getTitle(),
                "with auto-title off, the manual title must be left exactly as set");
    }

    // ------------------------------------------------------------------------------------------- //
    // adjustMessage - AREAS / SHORT_ID / TITLE selectors
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void adjustMessageTitleComputesAutoTitleFromSubject() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "will-be-overwritten");
        msg.setAutoTitle(true);
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);

        SystemMessageVo adjusted = messageService.adjustMessage(vo, AdjustmentType.TITLE);

        String title = adjusted.getDescs().stream()
                .filter(d -> "en".equals(d.getLang()))
                .map(org.niord.model.message.MessageDescVo::getTitle)
                .findFirst().orElse(null);
        assertEquals("subject.", title,
                "TITLE adjustment with auto-title on must copy the computed title back to the VO");
    }

    @Test
    @Transactional
    void adjustMessageTitleLeavesManualTitleWhenAutoTitleOff() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "keep-this-manual-title");
        msg.setAutoTitle(false);
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);

        SystemMessageVo adjusted = messageService.adjustMessage(vo, AdjustmentType.TITLE);

        String title = adjusted.getDescs().stream()
                .filter(d -> "en".equals(d.getLang()))
                .map(org.niord.model.message.MessageDescVo::getTitle)
                .findFirst().orElse(null);
        assertEquals("keep-this-manual-title", title,
                "TITLE adjustment with auto-title off must not overwrite the manual title");
    }

    @Test
    @Transactional
    void adjustMessageShortIdComputesManualSeriesShortId() {
        // dma-nm-almanac is a seeded MANUAL series with shortFormat "A/${number} ${year}".
        Message msg = newDraft("dma-nm-almanac", Type.PERMANENT_NOTICE, "Almanac");
        msg.setNumber(7);
        msg.setPublishDateFrom(pastPublishDate()); // year 2001 -> "A/7 2001"
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);

        SystemMessageVo adjusted = messageService.adjustMessage(vo, AdjustmentType.SHORT_ID);

        assertEquals("A/7 2001", adjusted.getShortId(),
                "SHORT_ID adjustment must expand the MANUAL shortFormat against number + publish year");
    }

    @Test
    @Transactional
    void adjustMessageAreasPopulatesAreasFromGeometryWhenNoneSet() {
        // A message with no areas but with a geometry must have areas computed from the geometry.
        // Use the centroid of the seeded "Denmark" area so getIntersectingAreas resolves to a real area.
        Area seededArea = seededArea();
        assertNotNull(seededArea.getGeometry(), "seeded area 'Denmark' must carry a geometry to intersect");
        org.locationtech.jts.geom.Point centroid = seededArea.getGeometry().getCentroid();

        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Geometry to areas");
        // JTS centroid is in (x=lon, y=lat) order; toJtsPoint expects (lat, lon).
        addPointGeometry(msg, centroid.getY(), centroid.getX());
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);
        // Precondition: the VO carries no areas before adjustment.
        assertTrue(vo.getAreas() == null || vo.getAreas().isEmpty(),
                "precondition: the VO must start without areas");

        SystemMessageVo adjusted = messageService.adjustMessage(vo, AdjustmentType.AREAS);

        assertNotNull(adjusted.getAreas(), "AREAS adjustment must populate the areas list");
        assertFalse(adjusted.getAreas().isEmpty(),
                "a message whose geometry sits inside a seeded area must have that area computed");
    }

    @Test
    @Transactional
    void adjustMessageAreasIsNoOpWhenAreasAlreadyPresent() {
        Area seededArea = seededArea();
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "Areas already set");
        msg.getAreas().add(seededArea);
        addPointGeometry(msg, 56.0, 11.0);
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);
        int before = vo.getAreas().size();
        Integer beforeId = vo.getAreas().get(0).getId();

        SystemMessageVo adjusted = messageService.adjustMessage(vo, AdjustmentType.AREAS);

        assertEquals(before, adjusted.getAreas().size(),
                "AREAS adjustment must not change an already-populated area list");
        assertEquals(beforeId, adjusted.getAreas().get(0).getId(),
                "the existing area must be preserved unchanged");
    }

    @Test
    @Transactional
    void adjustMessageCombinedAppliesTitleAndShortId() {
        Message msg = newDraft("dma-nm-almanac", Type.PERMANENT_NOTICE, "overwrite-me");
        msg.setAutoTitle(true);
        msg.setNumber(42);
        msg.setPublishDateFrom(pastPublishDate());
        SystemMessageVo vo = msg.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);

        SystemMessageVo adjusted =
                messageService.adjustMessage(vo, AdjustmentType.TITLE, AdjustmentType.SHORT_ID);

        assertEquals("A/42 2001", adjusted.getShortId(),
                "combined adjustment must still compute the MANUAL short ID");
        String title = adjusted.getDescs().stream()
                .filter(d -> "en".equals(d.getLang()))
                .map(org.niord.model.message.MessageDescVo::getTitle)
                .findFirst().orElse(null);
        assertEquals("subject.", title,
                "combined adjustment must still compute the auto-title from the subject");
    }

    // ------------------------------------------------------------------------------------------- //
    // checkUpdateShortId - MANUAL series short ID + publish-date stamping
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkUpdateShortIdComputesShortIdForManualSeriesWithFormatAndNumber() {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nm-almanac")); // resolved inside the method
        msg.setType(Type.PERMANENT_NOTICE);                        // gives FormatResolver a main type
        msg.setNumber(13);
        msg.setPublishDateFrom(pastPublishDate());                 // year 2001

        Message result = messageService.checkUpdateShortId(msg);

        assertEquals("A/13 2001", result.getShortId(),
                "a MANUAL series with a shortFormat + number must yield a formatted short ID");
        // The detached series must have been substituted with the persisted (id-bearing) entity.
        assertNotNull(result.getMessageSeries().getId(),
                "checkUpdateShortId must substitute the persisted message series");
    }

    @Test
    @Transactional
    void checkUpdateShortIdStampsPublishDateWhenNullForManualSeries() {
        Date before = new Date();
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nm-almanac"));
        msg.setType(Type.PERMANENT_NOTICE);
        msg.setNumber(1);
        msg.setPublishDateFrom(null); // must be stamped by the method

        Message result = messageService.checkUpdateShortId(msg);

        assertNotNull(result.getPublishDateFrom(),
                "a null publish date must be stamped when a MANUAL short ID is generated");
        assertTrue(!result.getPublishDateFrom().before(before),
                "the stamped publish date must be 'now' (at or after the call)");
        // The short ID uses the year stamped onto the message ('now').
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        assertEquals("A/1 " + year, result.getShortId(),
                "the generated short ID must use the stamped publish year");
    }

    @Test
    @Transactional
    void checkUpdateShortIdLeavesShortIdUnsetForManualSeriesWithoutFormat() {
        // dma-fa is a seeded MANUAL series with a NULL shortFormat -> no short ID is computed.
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-fa"));
        msg.setType(Type.PERMANENT_NOTICE);
        msg.setNumber(5);
        msg.setPublishDateFrom(pastPublishDate());

        Message result = messageService.checkUpdateShortId(msg);

        assertEquals(null, result.getShortId(),
                "a MANUAL series with no shortFormat must not compute a short ID");
    }

    @Test
    @Transactional
    void checkUpdateShortIdIsNoOpWhenNumberIsNull() {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nm-almanac"));
        msg.setType(Type.PERMANENT_NOTICE);
        msg.setNumber(null);          // no number -> the MANUAL short-ID branch is skipped
        msg.setPublishDateFrom(null);

        Message result = messageService.checkUpdateShortId(msg);

        assertEquals(null, result.getShortId(),
                "without a message number the MANUAL short-ID branch must not run");
        assertEquals(null, result.getPublishDateFrom(),
                "the publish date must not be stamped when the short-ID branch is skipped");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateBaseDate - detached VOs replaced by persisted entities
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateBaseDateResolvesDetachedAreaCategoryChartVos() {
        // Start from persisted entities, build a "client" VO that carries only the identifying fields
        // (area/category id, chart number) with no descriptors - exactly what updateBaseDate must enrich.
        Area area = seededArea();
        Category category = seededCategory();
        Chart chart = chartService.findByChartNumber("D");
        assertNotNull(chart);

        SystemMessageVo vo = new SystemMessageVo();
        vo.assignNewId();

        AreaVo areaVo = new AreaVo();
        areaVo.setId(area.getId());
        vo.setAreas(new java.util.ArrayList<>(List.of(areaVo)));

        CategoryVo categoryVo = new CategoryVo();
        categoryVo.setId(category.getId());
        vo.setCategories(new java.util.ArrayList<>(List.of(categoryVo)));

        ChartVo chartVo = new ChartVo();
        chartVo.setChartNumber(chart.getChartNumber());
        vo.setCharts(new java.util.ArrayList<>(List.of(chartVo)));

        // Preconditions: the detached VOs carry no descriptors / no chart id.
        assertTrue(areaVo.getDescs() == null || areaVo.getDescs().isEmpty());
        assertTrue(categoryVo.getDescs() == null || categoryVo.getDescs().isEmpty());

        SystemMessageVo result = messageService.updateBaseDate(vo);

        // Area: same id, now carrying descriptors loaded from the database.
        assertEquals(1, result.getAreas().size());
        assertEquals(area.getId(), result.getAreas().get(0).getId(),
                "the resolved area VO must keep the requested id");
        assertNotNull(result.getAreas().get(0).getDescs(),
                "updateBaseDate must re-populate the area descriptors from the persisted entity");
        assertFalse(result.getAreas().get(0).getDescs().isEmpty(),
                "the resolved area must carry at least one name descriptor");

        // Category: same id, now carrying descriptors.
        assertEquals(1, result.getCategories().size());
        assertEquals(category.getId(), result.getCategories().get(0).getId());
        assertNotNull(result.getCategories().get(0).getDescs());
        assertFalse(result.getCategories().get(0).getDescs().isEmpty(),
                "the resolved category must carry at least one name descriptor");

        // Chart: chart number preserved, and now backed by a persisted entity (id set).
        assertEquals(1, result.getCharts().size());
        assertEquals(chart.getChartNumber(), result.getCharts().get(0).getChartNumber());
    }

    @Test
    @Transactional
    void updateBaseDateDropsUnresolvableReferences() {
        // An area whose id does not exist must be dropped (persistedList filters out null look-ups).
        SystemMessageVo vo = new SystemMessageVo();
        vo.assignNewId();
        AreaVo ghost = new AreaVo();
        ghost.setId(-9999);
        vo.setAreas(new java.util.ArrayList<>(List.of(ghost)));

        SystemMessageVo result = messageService.updateBaseDate(vo);

        assertTrue(result.getAreas() == null || result.getAreas().isEmpty(),
                "an area id that resolves to no entity must be dropped from the base data");
    }

    // ------------------------------------------------------------------------------------------- //
    // findUpdatedMessages - "updated > date" window + maxCount cap
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findUpdatedMessagesReturnsOnlyMessagesUpdatedAfterTheGivenDate() throws Exception {
        Date beforeCreate = new Date(System.currentTimeMillis() - 60_000);
        Message a = createDraftNw("Updated A");
        Message b = createDraftNw("Updated B");
        em.flush();

        Date afterCreate = new Date(System.currentTimeMillis() + 60_000);

        List<Integer> recent = messageQueryService.findUpdatedMessages(beforeCreate, 100).stream()
                .map(Message::getId).toList();
        assertTrue(recent.contains(a.getId()),
                "a message updated after the window start must be returned");
        assertTrue(recent.contains(b.getId()));

        // A date in the future excludes our just-created messages (the query is strictly "updated > date").
        List<Integer> none = messageQueryService.findUpdatedMessages(afterCreate, 100).stream()
                .map(Message::getId).toList();
        assertFalse(none.contains(a.getId()),
                "a message must not be returned for a window starting after its update timestamp");
        assertFalse(none.contains(b.getId()));
    }

    @Test
    @Transactional
    void findUpdatedMessagesHonoursTheMaxCountCap() throws Exception {
        Date beforeCreate = new Date(System.currentTimeMillis() - 60_000);
        createDraftNw("Cap 1");
        createDraftNw("Cap 2");
        createDraftNw("Cap 3");
        em.flush();

        List<Message> capped = messageQueryService.findUpdatedMessages(beforeCreate, 2);
        assertEquals(2, capped.size(),
                "findUpdatedMessages must cap the result at maxCount even when more rows match");
    }

    // ------------------------------------------------------------------------------------------- //
    // getMostRecentlyEditedMessages / getMostRecentlyEditedDrafts - documented empty branches
    //
    // These read the "recently edited by the current user" message history. A plain service test has
    // NO authenticated user, so userService.currentUser() == null and the method short-circuits to an
    // empty list - regardless of how many messages exist or whether a domain is installed. The populated
    // path requires an authenticated current user, which this IT harness cannot install for a service-layer
    // call; that gap is recorded in docs/untestable-code.md. Here we pin the documented empty branches.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getMostRecentlyEditedMessagesReturnsEmptyWhenNoCurrentUser() throws Exception {
        // Create + edit a message so history rows certainly exist - they still must not be returned
        // because there is no current user to attribute them to.
        Message created = createDraftNw("Recent edit no-user");
        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        em.flush();

        try {
            // Install a domain so only the "user == null" guard is in play.
            domainService.setDomainForCurrentThread("niord-nw");
            assertNotNull(domainService.currentDomain(), "precondition: a current domain must resolve");

            assertTrue(messageService.getMostRecentlyEditedMessages(50).isEmpty(),
                    "with no authenticated user the recently-edited list must be empty");
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    @Test
    @Transactional
    void getMostRecentlyEditedMessagesReturnsEmptyWhenNoCurrentDomain() throws Exception {
        Message created = createDraftNw("Recent edit no-domain");
        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        em.flush();

        // No thread-local domain installed -> currentDomain() == null -> empty list.
        assertTrue(messageService.getMostRecentlyEditedMessages(50).isEmpty(),
                "with no current domain the recently-edited list must be empty");
    }

    @Test
    @Transactional
    void getMostRecentlyEditedDraftsDelegatesAndReturnsEmptyWhenNoCurrentUser() throws Exception {
        Message created = createDraftNw("Recent draft");
        em.flush();
        try {
            domainService.setDomainForCurrentThread("niord-nw");
            assertTrue(messageService.getMostRecentlyEditedDrafts(50).isEmpty(),
                    "getMostRecentlyEditedDrafts must also be empty without an authenticated user");
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // MSG1 regression pin - updateMessage validates type against the UNRESOLVED detached series
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMessageValidatesTypeAgainstResolvedSeries_MSG1() throws Exception {
        // updateMessage assigns the resolved/persisted series to `original` and validates the
        // requested type against original.getMessageSeries() (the authoritative persisted series), like
        // createMessage. A detached argument carrying only a seriesId (no mainType) therefore no longer
        // dereferences a null mainType -> no NullPointerException. With the persisted dma-nw series (mainType
        // NW), an NM type is cleanly rejected with the meaningful "Invalid type" error.
        Message created = createDraftNw("MSG1 base");
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
}
