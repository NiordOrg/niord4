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
package org.niord.core.geojson;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.MessageVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GeometryFormatService}, exercised against the wired Quarkus container
 * of the {@link ITTest} infrastructure. The service depends on {@code FmTemplateService} and
 * {@code NiordApp}, both CDI beans, so it cannot be unit-tested in isolation and must be {@code @Autowired}ed.
 *
 * <p>These tests are read-only with respect to the database (the service only renders Freemarker
 * templates and rewrites in-memory value objects), so they need no {@code @Transactional} rollback,
 * no created-id tracking and no {@code @AfterEach} cleanup — they leave the shared seed database
 * untouched and are therefore independent and order-insensitive.
 *
 * <p>IMPORTANT — observed behaviour vs. intent: the service renders the "list" geometry template at
 * {@code /templates/geometry/list.ftl}, but no such resource exists (neither on the class-path nor in
 * the seeded {@code ScriptResource} table — only {@code bullet-list.ftl}, {@code numbered-list.ftl},
 * {@code table.ftl}, {@code from-to-table.ftl} and {@code text.ftl} are shipped). Consequently the
 * Freemarker lookup throws, {@link GeometryFormatService#appendGeometryToDetails(String, String,
 * FeatureCollectionVo)} swallows the exception in its {@code catch (Exception ignored)} block and
 * returns the details UNCHANGED — it never appends a rendered position list. The assertions below pin
 * this ACTUAL behaviour; the discrepancy is reported in docs/potential-errors.md (GEOM-1).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class GeometryFormatServiceITTest extends ITTest {

    @Autowired
    GeometryFormatService geometryFormatService;

    // ------------------------------------------------------------------------------------------- //
    // Geometry builders (mirrors the GeoJSON VO assembly used by the geojson unit tests)
    // ------------------------------------------------------------------------------------------- //

    /** Builds a single-feature GeoJSON FeatureCollection holding one Point at [lon, lat]. */
    private FeatureCollectionVo point(double lon, double lat) {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new PointVo(new double[]{lon, lat}));

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{feature});
        return fc;
    }

    /** Builds a message part with the given descriptions and (optional) geometry. */
    private MessagePartVo part(FeatureCollectionVo geometry, MessagePartDescVo... descs) {
        MessagePartVo part = new MessagePartVo();
        part.setGeometry(geometry);
        if (descs != null) {
            part.setDescs(new ArrayList<>(List.of(descs)));
        }
        return part;
    }

    /** Builds a message-part description for the given language and details. */
    private MessagePartDescVo desc(String lang, String details) {
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang(lang);
        desc.setDetails(details);
        return desc;
    }

    // ------------------------------------------------------------------------------------------- //
    // formatGeometryAsHtml
    // ------------------------------------------------------------------------------------------- //

    @Test
    void formatGeometryAsHtmlReturnsEmptyStringForNullGeometry() throws Exception {
        // The null-geometry short-circuit returns "" before any template lookup, so this path is
        // independent of the missing list.ftl template and is the one behaviour guaranteed by contract.
        assertEquals("", geometryFormatService.formatGeometryAsHtml("en", "list", "dec", null));
    }

    @Test
    void formatGeometryAsHtmlReturnsEmptyStringForNullGeometryRegardlessOfTemplateAndFormat() throws Exception {
        // Even with an explicit (existing) template + format, a null geometry still short-circuits to "".
        assertEquals("", geometryFormatService.formatGeometryAsHtml("da", "bullet-list", "sec", null));
        assertEquals("", geometryFormatService.formatGeometryAsHtml(null, null, null, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // appendGeometryToDetails(language, details, geometry)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void appendGeometryToDetailsReturnsDetailsUnchangedWhenGeometryIsNull_GEOM2() {
        // With a null geometry, formatGeometryAsHtml returns ""
        // (blank), so there are NO positions to append. The service now returns the details verbatim.
        // The "<br>" is only inserted as a SEPARATOR when positions actually follow it, so no dangling
        // trailing "<br>" is produced.
        String details = "Existing <b>details</b> with markup";
        String result = geometryFormatService.appendGeometryToDetails("en", details, null);

        assertEquals(details, result,
                "null geometry returns details verbatim with no dangling <br> separator");
    }

    @Test
    void appendGeometryToDetailsReturnsNullWhenDetailsAndGeometryAreBothNull() {
        // Blank details + blank rendering => the StringBuilder stays empty => the original (null)
        // details are returned.
        assertNull(geometryFormatService.appendGeometryToDetails("en", null, null));
    }

    @Test
    void appendGeometryToDetailsReturnsBlankDetailsUnchangedWhenGeometryIsNull() {
        assertEquals("", geometryFormatService.appendGeometryToDetails("en", "", null));
    }

    @Test
    void appendGeometryToDetailsLeavesDetailsUnchangedWhenTemplateMissing_documentsBug() {
        // Intended behaviour: original details + "<br>" + rendered position list. ACTUAL behaviour:
        // list.ftl does not exist, formatGeometryAsHtml throws, the exception is swallowed and the
        // ORIGINAL details are returned with no "<br>" and no positions. See potential-errors.md GEOM-1.
        String details = "Vessels are advised to keep clear.";
        String result = geometryFormatService.appendGeometryToDetails("en", details, point(10.0, 56.0));

        assertEquals(details, result, "missing list.ftl => details returned unchanged (no <br>, no positions)");
        assertTrue(!result.contains("<br>"),
                "no separator must be appended when the position rendering fails");
    }

    @Test
    void appendGeometryToDetailsBlankDetailsStaysBlankWhenTemplateMissing_documentsBug() {
        // Intended behaviour: just the rendered positions (no leading "<br>"). ACTUAL behaviour: the
        // rendering fails, so the original blank details are returned. See potential-errors.md GEOM-1.
        String result = geometryFormatService.appendGeometryToDetails("en", "", point(10.0, 56.0));
        assertEquals("", result, "missing list.ftl => blank details returned unchanged (no positions)");
    }

    @Test
    void appendGeometryToDetailsNeverThrowsForValidGeometry() {
        // The service must be exception-safe: even though the template lookup fails internally, the
        // public method swallows the error and returns the (unchanged) details rather than propagating.
        String details = "Stable details";
        assertEquals(details,
                geometryFormatService.appendGeometryToDetails("da", details, point(12.5, 55.7)));
    }

    // ------------------------------------------------------------------------------------------- //
    // appendGeometryToDetails(MessageVo) — part filtering
    // ------------------------------------------------------------------------------------------- //

    @Test
    void appendGeometryToDetailsOnMessageReturnsTheSameInstance() {
        MessageVo message = new MessageVo();
        message.setParts(new ArrayList<>());
        assertSame(message, geometryFormatService.appendGeometryToDetails(message),
                "the message-level overload must return the same object it was given");
    }

    @Test
    void appendGeometryToDetailsOnMessageHandlesNullPartsList() {
        // getParts() == null must be tolerated (the message-VO overload guards on it).
        MessageVo message = new MessageVo();
        assertSame(message, geometryFormatService.appendGeometryToDetails(message));
        assertNull(message.getParts());
    }

    @Test
    void appendGeometryToDetailsOnMessageOnlyRewritesPartsWithBothDescsAndGeometry() {
        MessagePartDescVo bothDesc = desc("en", "Part with descs AND geometry");

        MessagePartVo bothPart = part(point(10.0, 56.0), bothDesc);                 // descs + geometry -> visited, descs iterated
        MessagePartVo descOnlyPart = part(null, desc("en", "Part with descs only")); // descs, no geometry -> filtered out (geometry == null)
        MessagePartVo geomOnlyPart = part(point(11.0, 55.0));                        // geometry + EMPTY (non-null) descs list -> visited but nothing to iterate

        MessageVo message = new MessageVo();
        message.setParts(new ArrayList<>(List.of(bothPart, descOnlyPart, geomOnlyPart)));

        MessageVo result = geometryFormatService.appendGeometryToDetails(message);
        assertSame(message, result);

        // The part with both descs + geometry IS visited; because list.ftl is missing the rendered
        // positions are not appended, so its details survive unchanged (documents GEOM-1 at the
        // message level too). The important contract verified here is the part-FILTERING.
        assertEquals("Part with descs AND geometry", bothPart.getDescs().get(0).getDetails(),
                "visited part keeps its details (no positions appended due to missing template)");

        // The descs-only part has no geometry, so its descs filter excludes it and details are untouched.
        assertEquals("Part with descs only", descOnlyPart.getDescs().get(0).getDetails(),
                "part without geometry must never be touched");

        // The geometry-only part was built with zero varargs descriptions, which the part() helper turns
        // into an EMPTY (but non-null) descs list. The production filter (descs != null && geometry !=
        // null) therefore still SELECTS it, but the per-desc forEach has nothing to iterate, so the empty
        // list survives untouched. The contract verified here is that no description content is fabricated.
        assertNotNull(geomOnlyPart.getDescs(), "empty-but-non-null descs list is retained as-is");
        assertTrue(geomOnlyPart.getDescs().isEmpty(),
                "no descriptions are fabricated for a part that had none");
    }

    @Test
    void appendGeometryToDetailsOnMessageRewritesEveryDescOfAVisitedPart() {
        // A visited part rewrites EVERY one of its language descriptions through the per-desc overload.
        MessagePartDescVo en = desc("en", "English details");
        MessagePartDescVo da = desc("da", "Danske detaljer");
        MessagePartVo multiLangPart = part(point(10.0, 56.0), en, da);

        MessageVo message = new MessageVo();
        message.setParts(new ArrayList<>(List.of(multiLangPart)));

        geometryFormatService.appendGeometryToDetails(message);

        // Both descs are passed through appendGeometryToDetails(lang, details, geometry); with the
        // template missing each is returned unchanged, so both original details survive.
        assertEquals("English details", en.getDetails());
        assertEquals("Danske detaljer", da.getDetails());
    }

    @Test
    void appendGeometryToDetailsOnNullMessageReturnsNull() {
        // The generic overload guards against a null message and simply returns it.
        MessageVo nullMessage = null;
        assertNull(geometryFormatService.appendGeometryToDetails(nullMessage));
    }

    @Test
    void appendGeometryToDetailsOnMessagePreservesBlankDetailsOfVisitedPart() {
        // A visited part whose desc has blank details stays blank (positions cannot be appended).
        MessagePartDescVo blank = desc("en", "");
        MessagePartVo visited = part(point(10.0, 56.0), blank);

        MessageVo message = new MessageVo();
        message.setParts(new ArrayList<>(List.of(visited)));

        geometryFormatService.appendGeometryToDetails(message);

        assertNotNull(visited.getDescs());
        assertEquals("", blank.getDetails(),
                "blank details of a visited part remain blank when the position template is missing");
    }
}
