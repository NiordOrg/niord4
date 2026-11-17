/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.web.wms;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WmsTileKey}: which WMS requests are tiles of the Web Mercator grid, and so have a place in
 * the tile cache, and what that place is.
 *
 * <p>The boxes are written as a web map writes them: the literal strings are what OpenLayers' tile grid computes
 * for a tile in JavaScript and joins with commas, floating-point noise included (the east edge of {@code 3/7/7} is
 * {@code 20037508.342789248}, not the world's {@code ...244}).
 */
class WmsTileKeyTest {

    private static final String PROVIDER = "https://wms.example.com/sea-charts";

    // z/x/y -> the box OpenLayers asks for
    private static final String BOX_0_0_0 = "-20037508.342789244,-20037508.342789244,20037508.342789244,20037508.342789244";
    private static final String BOX_3_7_7 = "15028131.257091936,-20037508.342789244,20037508.342789248,-15028131.257091932";
    private static final String BOX_8_136_79 = "1252344.271424327,7514065.628545966,1408887.305352368,7670608.662474007";
    private static final String BOX_12_2180_1279 = "1291480.0299063362,7514065.628545966,1301263.9695268387,7523849.568166468";
    private static final String BOX_16_34880_20470 = "1291480.0299063362,7519569.0945825,1292091.5261326176,7520180.590808782";
    private static final String BOX_20_558090_327532 = "1291862.2150477655,7519683.750124928,1291900.433561908,7519721.96863907";
    private static final String BOX_21_1116180_655064 = "1291862.2150477655,7519702.859381998,1291881.3243048368,7519721.968639069";

    /** The parameters a web map's tile request is forwarded with, by upper-case name */
    private static Map<String, String> tile(String box) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        forwarded.put("SERVICE", "WMS");
        forwarded.put("VERSION", "1.3.0");
        forwarded.put("REQUEST", "GetMap");
        forwarded.put("FORMAT", "image/png");
        forwarded.put("STYLES", "");
        forwarded.put("TRANSPARENT", "TRUE");
        forwarded.put("WIDTH", "256");
        forwarded.put("HEIGHT", "256");
        forwarded.put("CRS", "EPSG:3857");
        forwarded.put("BBOX", box);
        forwarded.put("LAYERS", "cells");
        return forwarded;
    }

    private static Map<String, String> tileWith(String box, String name, String value) {
        Map<String, String> forwarded = tile(box);
        if (value == null) {
            forwarded.remove(name);
        } else {
            forwarded.put(name, value);
        }
        return forwarded;
    }

    private static String zxy(Map<String, String> forwarded) {
        return WmsTileKey.of(PROVIDER, forwarded).map(k -> k.z() + "/" + k.x() + "/" + k.y()).orElse("none");
    }

    @Test
    void aTileOfTheGridHasItsZoomColumnAndRow() {
        assertEquals("0/0/0", zxy(tile(BOX_0_0_0)));
        assertEquals("3/7/7", zxy(tile(BOX_3_7_7)), "the south-east corner tile, its east edge off by rounding");
        assertEquals("8/136/79", zxy(tile(BOX_8_136_79)));
        assertEquals("12/2180/1279", zxy(tile(BOX_12_2180_1279)));
        assertEquals("16/34880/20470", zxy(tile(BOX_16_34880_20470)));
        assertEquals("20/558090/327532", zxy(tile(BOX_20_558090_327532)), "the deepest zoom cached");
    }

    @Test
    void aTileDeeperThanTheDeepestZoomHasNoKey() {
        assertEquals("none", zxy(tile(BOX_21_1116180_655064)));
    }

    @Test
    void theOlderWmsVersionNamesTheProjectionAsSrs() {
        Map<String, String> forwarded = tileWith(BOX_8_136_79, "CRS", null);
        forwarded.put("SRS", "EPSG:3857");
        forwarded.put("VERSION", "1.1.1");

        assertEquals("8/136/79", zxy(forwarded));
    }

    @Test
    void aBoxThatIsNoTileOfTheGridHasNoKey() {
        // The tile 8/136/79 is 156543.03 m wide, 611.5 m a pixel
        assertEquals("none", zxy(tile("1252955.767650608,7514065.628545966,1409498.801578649,7670608.662474007")),
                "one pixel to the east");
        assertEquals("none", zxy(tile("1252344.271424327,7514677.124772247,1408887.305352368,7671220.158700288")),
                "one pixel to the north");
        assertEquals("none", zxy(tile("1252344.271424327,7514065.628545966,1408887.305352368,7592337.145510000")),
                "half as high as wide");
        assertEquals("none", zxy(tile("1252344.271424327,7514065.628545966,1369751.546870000,7631472.903991639")),
                "three quarters of a tile: between two zooms");
        assertEquals("none", zxy(tile("1408887.305352368,7514065.628545966,1252344.271424327,7670608.662474007")),
                "east and west swapped");
        assertEquals("none", zxy(tile("20037508.342789244,-20037508.342789244,60112525.02836773,20037508.342789244")),
                "a world-sized box one world to the east: column 1 of zoom 0, which has column 0 only");
    }

    @Test
    void aBoxThatIsNotFourPlainNumbersHasNoKey() {
        for (String box : new String[] {
                "", "1,2,3", "1,2,3,4,5", "a,b,c,d",
                "NaN,7514065.628545966,1408887.305352368,7670608.662474007",
                "-Infinity,7514065.628545966,1408887.305352368,7670608.662474007",
                "0x1.0p20,7514065.628545966,1408887.305352368,7670608.662474007",
                "1252344.271424327,7514065.628545966,1408887.305352368,7670608.662474007d",
                " 1252344.271424327,7514065.628545966,1408887.305352368,7670608.662474007",
                "1252344.271424327,7514065.628545966,1408887.305352368,1e999" }) {
            assertEquals("none", zxy(tile(box)), "box '" + box + "'");
        }
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "BBOX", null)), "no box at all");
    }

    @Test
    void anythingButA256PixelPngInWebMercatorHasNoKey() {
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "WIDTH", "512")), "a hidpi image");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "HEIGHT", "255")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "WIDTH", null)));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "CRS", "EPSG:4326")), "another projection");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "CRS", null)), "no projection named");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "SRS", "EPSG:4326")), "two projections named, one of them another");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "FORMAT", "image/jpeg")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "REQUEST", "GetFeatureInfo")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "REQUEST", null)));
    }

    @Test
    void aProviderAddressWithoutAHostHasNoKey() {
        assertFalse(WmsTileKey.of("not a url", tile(BOX_8_136_79)).isPresent());
        assertFalse(WmsTileKey.of("file:///tmp/tiles", tile(BOX_8_136_79)).isPresent());
    }

    @Test
    void theFileIsNamedByHostVariantZoomColumnAndRow() {
        WmsTileKey key = WmsTileKey.of("http://Wms.Example.com:8080/sea", tile(BOX_8_136_79)).orElseThrow();

        assertEquals("wms.example.com_8080", key.host(), "the host in lower case, the port after an underscore");
        assertTrue(key.variant().matches("[0-9a-f]{32}"), "the variant is 32 hex digits: " + key.variant());
        assertEquals(Path.of("/cache", "wms.example.com_8080", key.variant(), "8", "136", "79.png"),
                key.file(Path.of("/cache")));
    }

    @Test
    void theVariantIsTheProviderAndEveryParameterButTheBox() {
        String variant = WmsTileKey.of(PROVIDER, tile(BOX_8_136_79)).orElseThrow().variant();

        assertEquals(variant, WmsTileKey.of(PROVIDER, tile(BOX_12_2180_1279)).orElseThrow().variant(),
                "another tile of the same map");
        assertEquals(variant, WmsTileKey.of(" " + PROVIDER + " ", tile(BOX_8_136_79)).orElseThrow().variant(),
                "white space around the provider's address");
        for (String[] change : new String[][] {
                { "LAYERS", "cells,soundings" }, { "STYLES", "night" }, { "TRANSPARENT", "FALSE" },
                { "TRANSPARENT", "true" }, { "TRANSPARENT", null }, { "VERSION", "1.1.1" }, { "SERVICE", null } }) {
            Optional<WmsTileKey> other = WmsTileKey.of(PROVIDER, tileWith(BOX_8_136_79, change[0], change[1]));
            assertNotEquals(variant, other.orElseThrow().variant(), "another " + change[0]);
        }
        assertNotEquals(variant, WmsTileKey.of(PROVIDER + "2", tile(BOX_8_136_79)).orElseThrow().variant(),
                "another provider address on the same host");
    }

    @Test
    void theProtocolsConstantsMustBeTheOnesAWebMapSends() {
        assertEquals("8/136/79", zxy(tileWith(BOX_8_136_79, "SERVICE", null)), "no SERVICE is the WMS's default");
        assertEquals("8/136/79", zxy(tileWith(BOX_8_136_79, "TRANSPARENT", null)));
        assertEquals("8/136/79", zxy(tileWith(BOX_8_136_79, "TRANSPARENT", "false")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "SERVICE", "WFS")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "VERSION", "9")));
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "VERSION", null)), "the provider would choose a version");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "TRANSPARENT", "x26vh2z7")),
                "a value made up to hash like another map's");
        assertEquals("none", zxy(tileWith(BOX_8_136_79, "TRANSPARENT", "TRUE\nVERSION=1.3.0")));
    }

    @Test
    void noValueReadsAsTheEndOfOneParameterAndTheStartOfAnother() {
        // Written one after the other with a separator, these two would be the same text
        Map<String, String> plain = tile(BOX_8_136_79);
        Map<String, String> crafted = tileWith(BOX_8_136_79, "TRANSPARENT", null);
        crafted.put("STYLES", "\nTRANSPARENT=TRUE");
        Map<String, String> craftedToo = tileWith(BOX_8_136_79, "TRANSPARENT", null);
        craftedToo.put("STYLES", "0:11:TRANSPARENT4:TRUE");

        String variant = WmsTileKey.of(PROVIDER, plain).orElseThrow().variant();

        assertNotEquals(variant, WmsTileKey.of(PROVIDER, crafted).orElseThrow().variant());
        assertNotEquals(variant, WmsTileKey.of(PROVIDER, craftedToo).orElseThrow().variant());
    }

    @Test
    void aBoxGivenTwiceNamesTheTileThatIsAskedFor() {
        // The container hands both over; the first is forwarded, and the key is of the forwarded one
        Map<String, String[]> params = new LinkedHashMap<>();
        tile(BOX_8_136_79).forEach((name, value) -> params.put(name, new String[] { value }));
        params.remove("BBOX");
        params.put("bbox", new String[] { BOX_12_2180_1279 });
        params.put("BBOX", new String[] { BOX_8_136_79 });

        Map<String, String> forwarded = WmsProxyServlet.forwardedParams(params, null);
        String query = WmsProxyServlet.upstreamQuery(forwarded, "tok", false);

        assertEquals("12/2180/1279", zxy(forwarded));
        assertTrue(query.contains("BBOX=" + BOX_12_2180_1279), "the provider is asked for the same box: " + query);
        assertFalse(query.contains(BOX_8_136_79), "and for no other: " + query);
    }

    @Test
    void theForwardedParametersCarryNoToken() {
        Map<String, String[]> params = new LinkedHashMap<>();
        tile(BOX_8_136_79).forEach((name, value) -> params.put(name, new String[] { value }));
        params.put("TOKEN", new String[] { "caller-token" });

        Map<String, String> forwarded = WmsProxyServlet.forwardedParams(params, "layerA");

        assertFalse(forwarded.containsKey("TOKEN"), "a caller's token is dropped, and the provider's is added later");
        assertEquals("layerA", forwarded.get("LAYERS"), "the configured layers name the variant");
    }
}
