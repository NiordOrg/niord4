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
import org.niord.core.area.AreaType;
import org.niord.core.category.Category;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link EditorFieldsService#computeEditorFields(Message, java.util.Map)} and its
 * {@link SystemMessageVo} variant, run against the seeded MySQL container of the {@link ITTest}
 * infrastructure.
 *
 * <p>The editor-fields result is an overlay computed, in order, from: the base settings
 * ({@code editorFieldsBase}), the main-type settings ({@code editorFieldsNw} for NW /
 * {@code editorFieldsNm} for NM), each associated area's {@code editorFields} along its lineage (with
 * firing areas additionally forcing {@code prohibition} and {@code signals} on), each associated
 * category's {@code editorFields} along its lineage, the message series {@code editorFields}, and finally
 * a per-user JSON overlay supplied via Keycloak "other claims" attributes. Each later layer overwrites
 * the fields set by earlier layers, so these tests assert the resulting {@code Boolean} value of the
 * field that the layer under test is expected to flip.
 *
 * <p>The seed settings (the base-data seed's rows; start-up adds what it lacks from the test tree's root
 * {@code niord.json}) are: {@code editorFieldsBase} with {@code title},
 * {@code type}, {@code id}, {@code references}, {@code areas}, {@code categories}, {@code positions},
 * {@code subject}, {@code description}, {@code publish_date} and {@code publication} all true, and
 * {@code charts}, {@code orig_info}, {@code source}, {@code event_dates}, {@code attachments},
 * {@code part_types}, {@code prohibition}, {@code signals} and {@code layout} all false;
 * {@code editorFieldsNw} empty; and {@code editorFieldsNm} flipping {@code orig_info}, {@code references},
 * {@code attachments}, {@code charts}, {@code event_dates}, {@code source}, {@code part_types} and
 * {@code layout} to true.
 *
 * <p>Because the service re-resolves the associated areas/categories/series by id from the database
 * (see {@code persistedList}/{@code findBySeriesId}), every area/category that must contribute editor
 * fields is persisted first. Each test is wrapped in {@link TestTransaction}, so all writes (including
 * the temporary mutation of the seeded {@code dma-nw} series) are rolled back, keeping the shared seed
 * database pristine and the tests independent and repeatable.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class EditorFieldsServiceITTest extends ITTest {

    @Autowired
    EditorFieldsService editorFieldsService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached NW message (LOCAL_WARNING, DRAFT) with no series, areas or categories. */
    private Message newNwMessage() {
        Message m = new Message();
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        m.setStatus(Status.DRAFT);
        return m;
    }

    /** Builds a detached NM message (PERMANENT_NOTICE, DRAFT) with no series, areas or categories. */
    private Message newNmMessage() {
        Message m = new Message();
        m.setMainType(MainType.NM);
        m.setType(Type.PERMANENT_NOTICE);
        m.setStatus(Status.DRAFT);
        return m;
    }

    /** Persists and returns a managed area of the given type carrying the given editor fields. */
    private Area persistArea(AreaType type, List<String> editorFields) {
        Area a = new Area();
        a.setType(type);
        a.setEditorFields(new ArrayList<>(editorFields));
        // A unique English name keeps the row well-formed and isolated from concurrent runs.
        a.createDesc("en").setName("IT-Area-" + UUID.randomUUID());
        em.persist(a);
        em.flush();
        return a;
    }

    /** Persists and returns a managed category carrying the given editor fields. */
    private Category persistCategory(List<String> editorFields) {
        Category c = new Category();
        c.setEditorFields(new ArrayList<>(editorFields));
        c.createDesc("en").setName("IT-Category-" + UUID.randomUUID());
        em.persist(c);
        em.flush();
        return c;
    }

    // ------------------------------------------------------------------------------------------- //
    // Base & main-type overlays
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void baseFieldsForNwMessage() {
        Message m = newNwMessage();

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        // editorFieldsNw is empty, so an NW message reflects the base settings unchanged.
        assertEquals(Boolean.TRUE, r.get("title"), "base sets title=true");
        assertEquals(Boolean.TRUE, r.get("areas"), "base sets areas=true");
        assertEquals(Boolean.FALSE, r.get("charts"), "base sets charts=false and NW does not flip it");
        assertEquals(Boolean.FALSE, r.get("orig_info"), "base sets orig_info=false and NW does not flip it");
    }

    @Test
    @Transactional
    void nmMessageAppliesNmOverrides() {
        Message m = newNmMessage();

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        // editorFieldsNm overlays the base false values with true for these fields.
        assertEquals(Boolean.TRUE, r.get("orig_info"), "NM overlay flips orig_info on");
        assertEquals(Boolean.TRUE, r.get("charts"), "NM overlay flips charts on");
        assertEquals(Boolean.TRUE, r.get("attachments"), "NM overlay flips attachments on");
        // A field not in the NM overlay keeps its base value.
        assertEquals(Boolean.TRUE, r.get("title"), "title stays true from base");
    }

    // ------------------------------------------------------------------------------------------- //
    // Area overlay
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void areaEditorFieldsOverlayTurnsFieldOn() {
        // The area's editorFields list always sets its entries to TRUE, overriding the base charts=false.
        Area area = persistArea(AreaType.COUNTRY, List.of("charts"));
        Message m = newNwMessage();
        m.setAreas(new ArrayList<>(List.of(area)));

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        assertEquals(Boolean.TRUE, r.get("charts"), "an area editor field forces charts=true over the base false");
    }

    @Test
    @Transactional
    void firingAreaForcesProhibitionAndSignals() {
        // A FIRING_AREA implicitly contributes "prohibition" and "signals" even with empty editorFields.
        Area area = persistArea(AreaType.FIRING_AREA, List.of());
        Message m = newNwMessage();
        m.setAreas(new ArrayList<>(List.of(area)));

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        assertEquals(Boolean.TRUE, r.get("prohibition"), "a firing area forces prohibition=true over the base false");
        assertEquals(Boolean.TRUE, r.get("signals"), "a firing area forces signals=true over the base false");
    }

    // ------------------------------------------------------------------------------------------- //
    // Category overlay
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void categoryEditorFieldsOverlay() {
        // A category editor field that is not part of any base/main-type set is added as TRUE.
        Category cat = persistCategory(List.of("custom_cat_field"));
        Message m = newNwMessage();
        m.setCategories(new ArrayList<>(List.of(cat)));

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        assertEquals(Boolean.TRUE, r.get("custom_cat_field"), "a category editor field is added as true");
    }

    // ------------------------------------------------------------------------------------------- //
    // Message series overlay
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void messageSeriesEditorFieldsOverlay() {
        // Mutate the seeded series in-transaction; the @Transactional rollback restores it afterwards.
        MessageSeries series = messageSeriesService.findBySeriesId("dma-nw");
        assertNotNull(series, "the seeded dma-nw message series must exist");
        series.getEditorFields().add("series_only_field");
        em.flush();

        Message m = newNwMessage();
        // The service re-resolves the series by its seriesId, so a bare carrier is enough.
        m.setMessageSeries(new MessageSeries("dma-nw"));

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, null);

        assertEquals(Boolean.TRUE, r.get("series_only_field"), "a series editor field is added as true");
    }

    // ------------------------------------------------------------------------------------------- //
    // User-attribute overlay
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void userAttributeOverlayTurnsFieldOff() {
        Message m = newNwMessage();
        // The NW user overlay is the last layer applied and can turn a base-true field off.
        Map<String, Object> attrs = Map.of("editorFieldsNw", "{\"title\": false}");

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, attrs);

        assertEquals(Boolean.FALSE, r.get("title"), "the NW user overlay turns the base-true title off");
    }

    @Test
    @Transactional
    void userAttributeForNmUsesNmKey() {
        Message m = newNmMessage();
        // For an NM message only the editorFieldsNm user key is consulted; it overrides the NM-true charts.
        Map<String, Object> attrs = Map.of("editorFieldsNm", "{\"charts\": false}");

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, attrs);

        assertEquals(Boolean.FALSE, r.get("charts"), "the NM user overlay turns the NM-true charts off");
    }

    @Test
    @Transactional
    void userAttributeWrongMainTypeKeyIgnored() {
        Message m = newNmMessage();
        // An NW user key on an NM message is ignored, so the base/NM value for title (true) survives.
        Map<String, Object> attrs = Map.of("editorFieldsNw", "{\"title\": false}");

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, attrs);

        assertEquals(Boolean.TRUE, r.get("title"), "the NW user key is ignored for an NM message");
    }

    @Test
    @Transactional
    void userAttributeInvalidJsonIsIgnored() {
        Message m = newNwMessage();
        // A non-JSON user attribute value is swallowed by the service and leaves the result unchanged.
        Map<String, Object> attrs = Map.of("editorFieldsNw", "not-json");

        Map<String, Boolean> r = editorFieldsService.computeEditorFields(m, attrs);

        assertEquals(Boolean.TRUE, r.get("title"), "invalid user JSON is ignored, so title stays true");
    }

    // ------------------------------------------------------------------------------------------- //
    // SystemMessageVo variant
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void voVariantSetsEditorFieldsOnVo() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setMainType(MainType.NW);

        editorFieldsService.computeEditorFields(vo, null);

        assertNotNull(vo.getEditorFields(), "the VO variant must populate the editor fields map");
        assertEquals(Boolean.TRUE, vo.getEditorFields().get("title"),
                "the computed editor fields must be stored on the VO with the base title=true");
    }
}
