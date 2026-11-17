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
package org.niord.core.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GlobalMercator}, the TMS Global (Spherical) Mercator
 * projection utility (EPSG:900913 / EPSG:3857).
 *
 * The tests pin down the exact numeric contracts of the coordinate conversions
 * (lat/lon &lt;-&gt; meters &lt;-&gt; pixels &lt;-&gt; tiles), the resolution pyramid, the
 * TMS/Google tile-origin flip, and the QuadTree encoding. They are pure-math
 * tests and therefore framework-neutral.
 *
 * Reference constants (derived from the spherical Earth radius 6378137 m):
 * <ul>
 *     <li>originShift  = 2 * PI * 6378137 / 2          = 20037508.342789244</li>
 *     <li>Resolution(0)= 2 * PI * 6378137 / 256        = 156543.03392804097</li>
 * </ul>
 */
public class GlobalMercatorTest {

    /** Earth half-circumference in meters - the EPSG:900913 coordinate extent. */
    private static final double ORIGIN_SHIFT = 20037508.342789244;

    /** Meters/pixel at zoom 0 for a 256px tile (actual Java double value). */
    private static final double RESOLUTION_0 = 156543.03392804097;

    private GlobalMercator gm;

    @BeforeEach
    public void setUp() {
        gm = new GlobalMercator();
    }

    // ------------------------------------------------------------------
    // LatLonToMeters
    // ------------------------------------------------------------------

    @Test
    public void latLonToMeters_origin_isZeroZero() {
        double[] m = gm.LatLonToMeters(0.0, 0.0);
        assertEquals(0.0, m[0], 1e-9);
        assertEquals(0.0, m[1], 1e-6);
    }

    @Test
    public void latLonToMeters_antimeridian_equalsOriginShift() {
        // lon == 180 maps exactly to the eastern extent (originShift)
        double[] m = gm.LatLonToMeters(0.0, 180.0);
        assertEquals(ORIGIN_SHIFT, m[0], 1e-6);
    }

    @Test
    public void latLonToMeters_westernAntimeridian_isNegativeOriginShift() {
        double[] m = gm.LatLonToMeters(0.0, -180.0);
        assertEquals(-ORIGIN_SHIFT, m[0], 1e-6);
    }

    @Test
    public void latLonToMeters_northernLatitude_isPositiveY() {
        double[] m = gm.LatLonToMeters(55.0, 12.0);
        assertTrue(m[1] > 0.0, "Northern latitude must produce positive northing");
        // lon 12 deg -> 12/180 of the eastern extent
        assertEquals(12.0 * ORIGIN_SHIFT / 180.0, m[0], 1e-6);
    }

    @Test
    public void latLonToMeters_southernLatitude_isNegativeY() {
        double[] m = gm.LatLonToMeters(-55.0, 12.0);
        assertTrue(m[1] < 0.0, "Southern latitude must produce negative northing");
    }

    @Test
    public void latLonToMeters_isAntisymmetricInLatitude() {
        double[] north = gm.LatLonToMeters(40.0, 0.0);
        double[] south = gm.LatLonToMeters(-40.0, 0.0);
        // The mercator northing is an odd function of latitude.
        assertEquals(north[1], -south[1], 1e-6);
    }

    // ------------------------------------------------------------------
    // MetersToLatLon (round-trips)
    // ------------------------------------------------------------------

    @Test
    public void metersToLatLon_origin_isZeroZero() {
        double[] ll = gm.MetersToLatLon(0.0, 0.0);
        assertEquals(0.0, ll[0], 1e-9);
        assertEquals(0.0, ll[1], 1e-9);
    }

    @Test
    public void latLonToMeters_thenBack_isIdentity() {
        double[] m = gm.LatLonToMeters(55.0, 12.0);
        double[] ll = gm.MetersToLatLon(m[0], m[1]);
        assertEquals(55.0, ll[0], 1e-6);
        assertEquals(12.0, ll[1], 1e-6);
    }

    @Test
    public void latLonToMeters_thenBack_negativeCoordinates_isIdentity() {
        double[] m = gm.LatLonToMeters(-33.86, -70.66);
        double[] ll = gm.MetersToLatLon(m[0], m[1]);
        assertEquals(-33.86, ll[0], 1e-6);
        assertEquals(-70.66, ll[1], 1e-6);
    }

    @Test
    public void metersToLatLon_originShiftX_isLon180() {
        double[] ll = gm.MetersToLatLon(ORIGIN_SHIFT, 0.0);
        assertEquals(180.0, ll[1], 1e-9);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Test
    public void resolution_atZoomZero_isInitialResolution() {
        assertEquals(RESOLUTION_0, gm.Resolution(0), 1e-6);
    }

    @Test
    public void resolution_halvesEachZoomLevel() {
        // Each zoom level exactly halves the resolution of the previous one.
        assertEquals(gm.Resolution(0) / 2.0, gm.Resolution(1), 0.0);
        assertEquals(gm.Resolution(1) / 2.0, gm.Resolution(2), 0.0);
        assertEquals(gm.Resolution(0) / 4.0, gm.Resolution(2), 0.0);
    }

    // ------------------------------------------------------------------
    // PixelsToMeters / MetersToPixels
    // ------------------------------------------------------------------

    @Test
    public void pixelsToMeters_bottomLeftPixel_isSouthWestExtent() {
        // Pixel (0,0) at zoom 0 is the south-west corner of the world.
        double[] m = gm.PixelsToMeters(0, 0, 0);
        assertEquals(-ORIGIN_SHIFT, m[0], 1e-6);
        assertEquals(-ORIGIN_SHIFT, m[1], 1e-6);
    }

    @Test
    public void pixelsToMeters_topRightPixel_isNorthEastExtent() {
        // Pixel (256,256) at zoom 0 is the north-east corner of the world.
        double[] m = gm.PixelsToMeters(256, 256, 0);
        assertEquals(ORIGIN_SHIFT, m[0], 1e-6);
        assertEquals(ORIGIN_SHIFT, m[1], 1e-6);
    }

    @Test
    public void metersToPixels_southWestExtent_isOrigin() {
        int[] p = gm.MetersToPixels(-ORIGIN_SHIFT, -ORIGIN_SHIFT, 0);
        assertArrayEquals(new int[] { 0, 0 }, p);
    }

    @Test
    public void metersToPixels_northEastExtent_isTopRight() {
        int[] p = gm.MetersToPixels(ORIGIN_SHIFT, ORIGIN_SHIFT, 0);
        assertArrayEquals(new int[] { 256, 256 }, p);
    }

    @Test
    public void metersToPixels_center_isMidPixel() {
        // The map centre (0,0 m) is half a tile in at zoom 0.
        int[] p = gm.MetersToPixels(0.0, 0.0, 0);
        assertArrayEquals(new int[] { 128, 128 }, p);
    }

    @Test
    public void pixelsToMeters_thenBack_isIdentity() {
        double[] m = gm.PixelsToMeters(300, 400, 3);
        int[] p = gm.MetersToPixels(m[0], m[1], 3);
        assertArrayEquals(new int[] { 300, 400 }, p);
    }

    // ------------------------------------------------------------------
    // LatLonToPixels
    // ------------------------------------------------------------------

    @Test
    public void latLonToPixels_origin_atZoomZero_isCenter() {
        int[] p = gm.LatLonToPixels(0.0, 0.0, 0);
        assertArrayEquals(new int[] { 128, 128 }, p);
    }

    @Test
    public void latLonToPixels_matchesMetersToPixelsComposition() {
        double[] m = gm.LatLonToMeters(55.0, 12.0);
        int[] viaMeters = gm.MetersToPixels(m[0], m[1], 5);
        int[] direct = gm.LatLonToPixels(55.0, 12.0, 5);
        assertArrayEquals(viaMeters, direct);
    }

    // ------------------------------------------------------------------
    // PixelsToTile
    // ------------------------------------------------------------------

    @Test
    public void pixelsToTile_firstPixelOfSecondTile_isTileOne() {
        // ceil(257/256 - 1) = ceil(0.0039) = 1
        assertArrayEquals(new int[] { 1, 1 }, gm.PixelsToTile(257, 257));
    }

    @Test
    public void pixelsToTile_exactTileBoundary_staysOnLowerTile() {
        // ceil(256/256 - 1) = ceil(0) = 0
        assertArrayEquals(new int[] { 0, 0 }, gm.PixelsToTile(256, 256));
    }

    @Test
    public void pixelsToTile_originPixel_isMinusOne() {
        // ceil(0/256 - 1) = ceil(-1) = -1 (a documented consequence of the formula)
        assertArrayEquals(new int[] { -1, -1 }, gm.PixelsToTile(0, 0));
    }

    @Test
    public void pixelsToTile_lastPixelOfFirstTile_isTileZero() {
        // ceil(255/256 - 1) = ceil(-0.0039) = 0
        assertArrayEquals(new int[] { 0, 0 }, gm.PixelsToTile(255, 255));
    }

    @Test
    public void pixelsToTile_secondTileExactBoundary_isTileOne() {
        // ceil(512/256 - 1) = ceil(1) = 1
        assertArrayEquals(new int[] { 1, 1 }, gm.PixelsToTile(512, 512));
    }

    // ------------------------------------------------------------------
    // PixelsToRaster
    // ------------------------------------------------------------------

    @Test
    public void pixelsToRaster_flipsYAroundMapSize_atZoomZero() {
        // mapSize = 256 << 0 = 256, so y -> 256 - y; x is unchanged.
        assertArrayEquals(new int[] { 10, 256 - 30 }, gm.PixelsToRaster(10, 30, 0));
    }

    @Test
    public void pixelsToRaster_usesZoomDependentMapSize() {
        // mapSize = 256 << 2 = 1024.
        assertArrayEquals(new int[] { 100, 1024 - 200 }, gm.PixelsToRaster(100, 200, 2));
    }

    // ------------------------------------------------------------------
    // MetersToTile
    // ------------------------------------------------------------------

    @Test
    public void metersToTile_center_atZoomZero_isTileZero() {
        // Centre maps to pixel (128,128); ceil(128/256 - 1) = ceil(-0.5) = 0.
        assertArrayEquals(new int[] { 0, 0 }, gm.MetersToTile(0.0, 0.0, 0));
    }

    @Test
    public void metersToTile_southWestExtent_atZoomZero_isMinusOneTile() {
        // SW extent maps to pixel (0,0); ceil(0/256 - 1) = -1.
        assertArrayEquals(new int[] { -1, -1 }, gm.MetersToTile(-ORIGIN_SHIFT, -ORIGIN_SHIFT, 0));
    }

    // ------------------------------------------------------------------
    // TileBounds / TileLatLonBounds
    // ------------------------------------------------------------------

    @Test
    public void tileBounds_singleTileAtZoomZero_coversWholeWorld() {
        double[] b = gm.TileBounds(0, 0, 0);
        assertEquals(-ORIGIN_SHIFT, b[0], 1e-6);
        assertEquals(-ORIGIN_SHIFT, b[1], 1e-6);
        assertEquals(ORIGIN_SHIFT, b[2], 1e-6);
        assertEquals(ORIGIN_SHIFT, b[3], 1e-6);
    }

    @Test
    public void tileBounds_areContiguousBetweenNeighbours() {
        // The max-x of tile 0 equals the min-x of tile 1 at the same zoom.
        double[] tile0 = gm.TileBounds(0, 0, 3);
        double[] tile1 = gm.TileBounds(1, 0, 3);
        assertEquals(tile0[2], tile1[0], 1e-6);
    }

    @Test
    public void tileLatLonBounds_singleTileAtZoomZero_spansFullLongitude() {
        double[] b = gm.TileLatLonBounds(0, 0, 0);
        // min lon and max lon span the full -180..180 range.
        assertEquals(-180.0, b[1], 1e-6);
        assertEquals(180.0, b[3], 1e-6);
        // Latitudes are the Mercator clip values ~ +/- 85.0511.
        assertEquals(-85.05112877980659, b[0], 1e-6);
        assertEquals(85.05112877980659, b[2], 1e-6);
    }

    // ------------------------------------------------------------------
    // ZoomForPixelSize
    // ------------------------------------------------------------------

    @Test
    public void zoomForPixelSize_largerThanZoomZeroResolution_returnsZero() {
        // pixelSize > Resolution(0): the loop matches at i==0 and returns 0 (no scale-up).
        assertEquals(0, gm.ZoomForPixelSize(200000));
    }

    @Test
    public void zoomForPixelSize_betweenZoomZeroAndOneResolution_returnsZero() {
        // Resolution(1) ~ 78271 < 100000 < Resolution(0) ~ 156543; first match at i==0 -> 0.
        assertEquals(0, gm.ZoomForPixelSize(100000));
    }

    @Test
    public void zoomForPixelSize_betweenZoomOneAndTwoResolution_returnsOne() {
        // Resolution(2) ~ 39135 < 40000 < Resolution(1) ~ 78271; first match at i==2 -> i-1 == 1.
        assertEquals(1, gm.ZoomForPixelSize(40000));
    }

    @Test
    public void zoomForPixelSize_onePixelMeter_returnsSeventeen() {
        // For a 1 m/pixel target, Resolution(18) < 1 < Resolution(17); first match at i==18 -> 17.
        assertEquals(17, gm.ZoomForPixelSize(1));
    }

    @Test
    public void zoomForPixelSize_zero_neverGreaterThanAnyResolution_returnsZero() {
        // pixelSize 0 is never strictly greater than a (positive) resolution, so the
        // loop never matches and the method falls through to its default 0.
        assertEquals(0, gm.ZoomForPixelSize(0));
    }

    // ------------------------------------------------------------------
    // GoogleTile <-> TMS tile
    // ------------------------------------------------------------------

    @Test
    public void googleTile_flipsYOriginToTopLeft() {
        // At zoom 1 there are 2x2 tiles; TMS y=0 (bottom) becomes Google y=1 (top).
        assertArrayEquals(new int[] { 5, 1 }, gm.GoogleTile(5, 0, 1));
    }

    @Test
    public void googleTile_topTmsRow_becomesGoogleZero() {
        // TMS y=1 (top) becomes Google y=0.
        assertArrayEquals(new int[] { 5, 0 }, gm.GoogleTile(5, 1, 1));
    }

    @Test
    public void googleTile_isItsOwnInverse() {
        // GoogleTile and TMSTileFromGoogleTile share the same flip, so applying
        // GoogleTile twice round-trips back to the original tile.
        int tx = 3, ty = 2, zoom = 4;
        int[] google = gm.GoogleTile(tx, ty, zoom);
        int[] back = gm.TMSTileFromGoogleTile(google[0], google[1], zoom);
        assertArrayEquals(new int[] { tx, ty }, back);
    }

    @Test
    public void tmsTileFromGoogleTile_matchesGoogleTileFlip() {
        // The two methods are implemented identically; verify they agree.
        assertArrayEquals(gm.GoogleTile(7, 3, 5), gm.TMSTileFromGoogleTile(7, 3, 5));
    }

    @Test
    public void googleTile_fromLatLon_origin_atZoomZero() {
        // Centre of the map at zoom 0 lives in the single tile, flipped to Google (0,0).
        int[] g = gm.GoogleTile(0.0, 0.0, 0);
        assertArrayEquals(new int[] { 0, 0 }, g);
    }

    @Test
    public void googleTile_fromLatLon_matchesManualComposition() {
        double lat = 55.0, lon = 12.0;
        int zoom = 4;
        double[] m = gm.LatLonToMeters(lat, lon);
        int[] tmsTile = gm.MetersToTile(m[0], m[1], zoom);
        int[] expected = gm.GoogleTile(tmsTile[0], tmsTile[1], zoom);
        assertArrayEquals(expected, gm.GoogleTile(lat, lon, zoom));
    }

    // ------------------------------------------------------------------
    // QuadTree
    // ------------------------------------------------------------------

    @Test
    public void quadTree_encodesTileToMicrosoftQuadKey() {
        // The encoding loop runs from `zoom` down to 1, emitting one base-4 digit per zoom
        // level. ty is first flipped from TMS (origin bottom-left) to Google (origin top-left)
        // tile numbering. The values below are computed by hand from the Microsoft QuadKey
        // definition: QuadTree(3,5,3) -> ty=2, digits 0,3,1 -> "031".
        assertEquals("031", gm.QuadTree(3, 5, 3));
        assertEquals("2", gm.QuadTree(0, 0, 1));
        assertEquals("1", gm.QuadTree(1, 1, 1));
    }

    @Test
    public void quadTree_zoomZero_isEmpty() {
        // A zoom-0 QuadKey legitimately has length 0 (one digit per level, zero levels).
        assertEquals("", gm.QuadTree(0, 0, 0));
    }

    @Test
    public void quadTree_lengthEqualsZoomAndUsesBase4Digits() {
        // One digit per zoom level, each digit in {0,1,2,3}.
        String key = gm.QuadTree(123, 456, 10);
        assertEquals(10, key.length());
        assertTrue(key.matches("[0-3]+"), "quad key must contain only base-4 digits");
    }
}
