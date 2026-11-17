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

package org.niord.web.aton;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;
import org.niord.core.aton.vo.Iso8601DateXmlAdapter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AtonIconRenderer}, which renders an OSM seamark AtoN to either SVG or PNG.
 *
 * <p>These are pure (no-DB) unit tests of the static {@code renderIcon} method. They exercise the
 * real rendering pipeline, which is backed by the vendored GPL {@code render}/{@code s57} libraries
 * (JOSM searchart based) and Apache Batik for SVG generation. As such, the tests are coupled to those
 * vendored libraries plus headless AWT graphics. The headless mode is enabled in {@link #setUpHeadless()}
 * so the suite is deterministic on CI machines without a display.</p>
 */
@SuppressWarnings("unused")
public class AtonIconRendererTest {

    /**
     * Force headless AWT so the rendering pipeline does not require a display server.
     * Done before any AWT/Graphics2D class is touched by the renderer.
     */
    @BeforeAll
    public static void setUpHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * SVG rendering produces a non-empty, well-formed SVG document carrying the W3C SVG namespace.
     */
    @Test
    public void testRenderSvgProducesSvgDocument() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "svg", baos, 100, 100, 50, 50, 0.4);

        assertTrue(baos.size() > 0, "SVG output should not be empty");
        String svg = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(svg.toLowerCase().contains("<svg"),
                "Output should contain an <svg element, was: " + svg);
        assertTrue(svg.contains(AtonIconRenderer.SVG_NS),
                "Output should declare the SVG namespace, was: " + svg);
        // Sanity-check the namespace constant against the spec value.
        assertEquals("http://www.w3.org/2000/svg", AtonIconRenderer.SVG_NS);
    }

    /**
     * The format match is case-insensitive ({@code equalsIgnoreCase}), so upper-case "SVG"
     * must produce the same kind of SVG document as lower-case "svg".
     */
    @Test
    public void testRenderSvgFormatIsCaseInsensitive() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "SVG", baos, 100, 100, 50, 50, 0.4);

        assertTrue(baos.size() > 0, "Upper-case SVG should still render output");
        String svg = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(svg.toLowerCase().contains("<svg"),
                "Upper-case SVG should contain an <svg element");
        assertTrue(svg.contains(AtonIconRenderer.SVG_NS),
                "Upper-case SVG should declare the SVG namespace");
    }

    /**
     * PNG rendering produces a readable PNG image whose dimensions match the requested width/height.
     * The renderer builds a {@code new BufferedImage(w, h, ...)}, so the decoded image must be w x h.
     */
    @Test
    public void testRenderPngProducesImageOfRequestedSize() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "png", baos, 60, 90, 20, 60, 0.3);

        byte[] bytes = baos.toByteArray();
        assertTrue(bytes.length > 0, "PNG output should not be empty");

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "Output bytes should decode to a PNG image");
        assertEquals(60, img.getWidth(), "PNG width should equal requested w");
        assertEquals(90, img.getHeight(), "PNG height should equal requested h");
    }

    /**
     * The PNG dimensions track the w/h arguments independently of x/y/scale, so a different
     * canvas size yields a different image size. This guards against w/h being swapped or fixed.
     */
    @Test
    public void testRenderPngHonoursDifferentDimensions() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "PNG", baos, 128, 32, 64, 16, 0.5);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
        assertNotNull(img, "Output bytes should decode to a PNG image");
        assertEquals(128, img.getWidth(), "PNG width should equal requested w");
        assertEquals(32, img.getHeight(), "PNG height should equal requested h");
    }

    /**
     * An unrecognised format matches neither the PNG nor the SVG branch, so nothing is written.
     */
    @Test
    public void testUnknownFormatWritesNothing() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "gif", baos, 100, 100, 50, 50, 0.4);

        assertEquals(0, baos.size(), "Unknown format should produce no output");
    }

    /**
     * A second unrecognised format ("jpeg") also produces no output, confirming the if/else-if
     * only ever matches the two known formats.
     */
    @Test
    public void testAnotherUnknownFormatWritesNothing() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "jpeg", baos, 80, 80, 40, 40, 0.4);

        assertEquals(0, baos.size(), "Unknown format should produce no output");
    }

    /**
     * A light-typed seamark with no tags beyond the type still renders a valid SVG, confirming the
     * pipeline does not require the full cardinal-buoy tag set.
     */
    @Test
    public void testRenderSvgForLightSeamark() throws Exception {
        AtonNodeVo aton = createLightNode();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AtonIconRenderer.renderIcon(aton, "svg", baos, 100, 100, 50, 50, 0.4);

        assertTrue(baos.size() > 0, "SVG output for a light should not be empty");
        String svg = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(svg.contains(AtonIconRenderer.SVG_NS),
                "SVG output for a light should declare the SVG namespace");
    }

    /**
     * The same AtoN rendered to both SVG and PNG yields output on each path; this confirms a single
     * model can drive both renderers and that both branches actually emit bytes.
     */
    @Test
    public void testSameAtonRendersToBothFormats() throws Exception {
        AtonNodeVo aton = createCardinalBuoyNode();

        ByteArrayOutputStream svgOut = new ByteArrayOutputStream();
        AtonIconRenderer.renderIcon(aton, "svg", svgOut, 100, 100, 50, 50, 0.4);

        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        AtonIconRenderer.renderIcon(aton, "png", pngOut, 100, 100, 50, 50, 0.4);

        assertTrue(svgOut.size() > 0, "SVG path should emit bytes");
        assertTrue(pngOut.size() > 0, "PNG path should emit bytes");

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngOut.toByteArray()));
        assertNotNull(img, "PNG bytes should decode to an image");
        assertEquals(100, img.getWidth());
        assertEquals(100, img.getHeight());
    }

    /*************************/
    /** Test data helpers   **/
    /*************************/

    /**
     * Constructs a north-cardinal-buoy seamark AtoN, mirroring the canonical fixture used by
     * {@code org.niord.core.AtonTest#createAtonNode}.
     */
    private AtonNodeVo createCardinalBuoyNode() throws Exception {
        AtonNodeVo aton = new AtonNodeVo();
        aton.setId(672436827);
        aton.setLat(50.8070813);
        aton.setLon(-1.2841124);
        aton.setUser("malcolmh");
        aton.setUid(128186);
        aton.setVisible(true);
        aton.setVersion(11);
        aton.setChangeset(9107813);
        aton.setTimestamp(new Iso8601DateXmlAdapter().unmarshal("2011-08-23T21:22:36Z"));
        List<AtonTagVo> tags = new ArrayList<>();
        tags.add(new AtonTagVo("seamark:buoy_cardinal:category", "north"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:colour", "black;yellow"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:colour_pattern", "horizontal"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:shape", "pillar"));
        tags.add(new AtonTagVo("seamark:light:character", "VQ"));
        tags.add(new AtonTagVo("seamark:light:colour", "white"));
        tags.add(new AtonTagVo("seamark:name", "Calshot"));
        tags.add(new AtonTagVo("seamark:topmark:colour", "black"));
        tags.add(new AtonTagVo("seamark:topmark:shape", "2 cones up"));
        tags.add(new AtonTagVo("seamark:type", "buoy_cardinal"));
        aton.setTags(tags.toArray(new AtonTagVo[tags.size()]));
        return aton;
    }

    /**
     * Constructs a minimal light seamark AtoN.
     */
    private AtonNodeVo createLightNode() throws Exception {
        AtonNodeVo aton = new AtonNodeVo();
        aton.setId(100000001);
        aton.setLat(55.0);
        aton.setLon(11.0);
        aton.setUser("tester");
        aton.setUid(1);
        aton.setVisible(true);
        aton.setVersion(1);
        aton.setChangeset(1);
        aton.setTimestamp(new Iso8601DateXmlAdapter().unmarshal("2016-01-01T00:00:00Z"));
        List<AtonTagVo> tags = new ArrayList<>();
        tags.add(new AtonTagVo("seamark:light:character", "Fl"));
        tags.add(new AtonTagVo("seamark:light:colour", "red"));
        tags.add(new AtonTagVo("seamark:type", "light_minor"));
        aton.setTags(tags.toArray(new AtonTagVo[tags.size()]));
        return aton;
    }
}
