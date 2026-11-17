/*
 * Copyright 2024 Danish Maritime Authority.
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration-safety tests for {@link MessageService#search(MessageSearchParams)} <em>geometry / extent</em>
 * filtering — the single highest-risk path in the planned MySQL&nbsp;-&gt;&nbsp;PostgreSQL&nbsp;+&nbsp;PostGIS
 * migration.
 *
 * <p><b>Why this matters.</b> {@code MessageService#buildKeySetQuery} reverses the X/Y order of the search
 * extent <em>only</em> when {@code quarkus.datasource.db-kind == "mysql"} (the {@code if (dbKind.equals("mysql"))}
 * branch). That reversal compensates for MySQL&nbsp;8 honouring the SRID-4326 (lat,lon) axis order in
 * {@code ST_Within}/{@code ST_Intersects}; PostGIS always uses (lon,lat), so the reversal must be removed for
 * Postgres. A wrong change there silently returns the wrong messages — or none — for every spatial search, and
 * (until now) no test exercised it. The sibling {@code MessageSearchFilterMatrixITTest} explicitly skips the
 * geometry predicate.
 *
 * <p><b>Migration-agnostic by construction.</b> Every test asserts the observable end-to-end contract through
 * the real API: a message is stored at a known position with {@link MessageService#createMessage} and retrieved
 * with the intuitive covering {@link MessageSearchParams#extent}. That contract holds on MySQL today (via the
 * reversal) and must still hold on PostGIS after (without it), so these tests pass before and after the
 * migration. They never assert on the {@code dbKind} string or any dialect internal.
 *
 * <p>Geometries are built exclusively through {@link JtsConverter} (lon = x, lat = y), exactly as production
 * does. The chosen box {@code lat[55,56] x lon[10,11]} has <em>non-overlapping</em> lat and lon ranges, so a
 * coordinate-swap regression is always detectable: a point at {@code (lon=10.5, lat=55.5)} is inside, while the
 * axis-swapped decoy {@code (lon=55.5, lat=10.5)} is far outside.
 *
 * <p>Each test runs in a rolled-back {@link Transactional} transaction (the search criteria query sees the
 * flushed-but-uncommitted rows, mirroring {@code SpatialPredicateITTest}), so the shared seed database stays
 * pristine and the tests are order-independent. Result assertions are restricted to the ids each test created,
 * so unrelated seed rows never affect the outcome.
 *
 * <p>This is a framework-coupled test (it boots the application context to reach the JPA criteria query and the
 * spatial dialect) — see docs/framework-coupled-tests.md.
 */
class MessageSpatialSearchITTest extends ITTest {

    /** Box with non-overlapping lat/lon ranges so an axis swap is always detectable. */
    private static final double BOX_MIN_LAT = 55.0;
    private static final double BOX_MIN_LON = 10.0;
    private static final double BOX_MAX_LAT = 56.0;
    private static final double BOX_MAX_LON = 11.0;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A detached DRAFT dma-nw message with one DETAILS part (mirrors MessageSearchFilterMatrixITTest). */
    private Message newDraft(String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    /** Creates and persists a dma-nw message whose single part carries a Point geometry at (lat,lon). */
    private Message createWithPoint(String title, double lat, double lon) {
        Message draft = newDraft(title);
        FeatureCollection fc = new FeatureCollection();
        Feature f = new Feature();
        f.setGeometry(JtsConverter.toJtsPoint(lat, lon));
        fc.addFeature(f);
        draft.getParts().get(0).setGeometry(fc);
        return create(draft);
    }

    /** Creates a dma-nw message with NO geometry. */
    private Message createWithoutGeometry(String title) {
        return create(newDraft(title));
    }

    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Integer> searchIds(MessageSearchParams params) {
        return messageSearchService.search(params).getData().stream()
                .map(Message::getId)
                .collect(Collectors.toList());
    }

    /** The intuitive covering extent for the box (lat[55,56] x lon[10,11]). */
    private MessageSearchParams coveringExtent() {
        return new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .extent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON);
    }

    // ------------------------------------------------------------------------------------------- //
    // Extent search: inside is returned, outside (incl. axis-swapped decoy) is excluded
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByExtentReturnsMessageInsideTheBoxAndExcludesMessagesOutside() {
        // (lon=10.5, lat=55.5) is squarely inside lat[55,56] x lon[10,11].
        Message inside = createWithPoint("spatial-inside", 55.5, 10.5);
        // Far away in the South Atlantic — outside the box on both axes.
        Message farAway = createWithPoint("spatial-far", -30.0, -30.0);
        // Axis-swapped decoy: real position (lon=55.5, lat=10.5) is far from the box. If the production
        // reversal logic were broken so the extent matched swapped coordinates, this would wrongly match.
        Message swappedDecoy = createWithPoint("spatial-swap-decoy", 10.5, 55.5);

        List<Integer> ids = searchIds(coveringExtent());

        assertTrue(ids.contains(inside.getId()),
                "a message whose geometry lies inside the extent must be returned by an extent search");
        assertFalse(ids.contains(farAway.getId()),
                "a message far outside the extent must NOT be returned");
        assertFalse(ids.contains(swappedDecoy.getId()),
                "an axis-swapped point (real position far from the box) must NOT be returned — guards the "
                        + "MySQL/PostGIS coordinate-reversal logic");
    }

    @Test
    @Transactional
    void searchByExtentFarFromEveryPointReturnsNoneOfThisTestsMessages() {
        Message a = createWithPoint("farbox-a", 55.5, 10.5);
        Message b = createWithPoint("farbox-b", 55.2, 10.8);
        Set<Integer> mine = Set.of(a.getId(), b.getId());

        // A small extent in the South Pacific, nowhere near the Danish points.
        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .extent(-40.0, -120.0, -39.0, -119.0));

        assertFalse(ids.stream().anyMatch(mine::contains),
                "an extent far from every created point must return none of this test's messages");
    }

    // ------------------------------------------------------------------------------------------- //
    // includeNoPos OR-branch: extent matches PLUS messages with no geometry
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByExtentWithIncludeNoPosAlsoReturnsMessagesWithoutGeometry() {
        Message inside = createWithPoint("inclpos-inside", 55.5, 10.5);
        Message outside = createWithPoint("inclpos-outside", -30.0, -30.0);
        Message noGeometry = createWithoutGeometry("inclpos-nogeom");

        List<Integer> ids = searchIds(coveringExtent().includeNoPos(true));

        assertTrue(ids.contains(inside.getId()),
                "includeNoPos must still return the message inside the extent");
        assertTrue(ids.contains(noGeometry.getId()),
                "includeNoPos=true must additionally return messages that have no geometry at all");
        assertFalse(ids.contains(outside.getId()),
                "includeNoPos must still exclude a geometry-bearing message that is outside the extent");
    }

    @Test
    @Transactional
    void searchByExtentWithoutIncludeNoPosExcludesMessagesWithoutGeometry() {
        Message inside = createWithPoint("exclpos-inside", 55.5, 10.5);
        Message noGeometry = createWithoutGeometry("exclpos-nogeom");

        List<Integer> ids = searchIds(coveringExtent());

        assertTrue(ids.contains(inside.getId()),
                "the in-extent message must be returned");
        assertFalse(ids.contains(noGeometry.getId()),
                "without includeNoPos, a message with no geometry must NOT be returned by an extent search");
    }

    // ------------------------------------------------------------------------------------------- //
    // Write -> read geometry round-trip (the stored geometry read path is axis-symmetric on both DBs)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void messageGeometryRoundTripsAsLonLatAndIsRetrievableByCoveringExtent() {
        double lat = 55.5;
        double lon = 10.5;
        Message created = createWithPoint("roundtrip", lat, lon);
        Integer id = created.getId();

        em.flush();
        em.clear();

        Message reloaded = em.find(Message.class, id);
        assertNotNull(reloaded, "the created message must be reloadable by id");
        assertTrue(reloaded.getHasGeometry(),
                "a message created with a non-empty geometry must have hasGeometry=true");

        Geometry geometry = reloaded.getParts().get(0).getGeometry().getFeatures().get(0).getGeometry();
        assertNotNull(geometry, "the reloaded feature must carry its geometry");
        // The display/read path stores and returns geometry as (x=lon, y=lat) on both MySQL and PostGIS;
        // only the ST_* comparison axis order differs (handled by the extent reversal), so this raw
        // round-trip is dialect-independent.
        assertEquals(lon, geometry.getCoordinate().getX(), 1e-9, "stored geometry X must round-trip as longitude");
        assertEquals(lat, geometry.getCoordinate().getY(), 1e-9, "stored geometry Y must round-trip as latitude");

        // And the same message is found by the intuitive covering extent (end-to-end contract).
        List<Integer> ids = searchIds(coveringExtent());
        assertTrue(ids.contains(id),
                "the round-tripped message must be retrievable by an extent covering its position");
    }
}
