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
package org.niord.core.portrayal;

import org.junit.jupiter.api.Test;
import org.niord.core.portrayal.PortrayalDefinition.SelectRow;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalDefinition.StrokeSize;
import org.niord.core.portrayal.PortrayalDefinition.Symbol;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The portrayal definition the web maps and the backend's map images share: the copy the Maven build puts on the
 * class path is complete and readable, its symbols agree with their SVG files, the style selection is first-match
 * with a catch-all, and the image version changes exactly when a map image would.
 *
 * <p>Everything is read from the class path, as the application reads it, so a copy the build did not make fails
 * here rather than in a running backend. The source folder is read only to check that the copy is complete.
 */
class PortrayalDefinitionTest {

    /** The source folder of the definition, relative to the repository root */
    private static final String SOURCE_FOLDER = "fe/shared-map/portrayal";

    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private static final Pattern SVG_ROOT = Pattern.compile("<svg\\b[^>]*>");

    /** The width and height of a symbol may be a whole pixel off the exact viewBox aspect, but no more */
    private static final double ROUNDING = 0.5;

    // ------------------------------------------------------------------------------------------- //
    // The class path copy
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theDefinitionOnTheClassPathIsAvailable() {
        // Read directly first: an invalid definition fails here with the reason, not only with a log line
        PortrayalDefinition read = classPathDefinition();
        assertTrue(read.imageVersion().matches("[0-9a-f]{12}"), read.imageVersion());

        PortrayalDefinition bean = new PortrayalDefinition();
        assertTrue(bean.available());
        assertEquals(read.imageVersion(), bean.imageVersion());
        assertFalse(bean.catalogue().isBlank());
        assertEquals(BasicStroke.CAP_ROUND, bean.lineStyle().awtCap());
        assertEquals(BasicStroke.JOIN_ROUND, bean.lineStyle().awtJoin());
    }

    /**
     * The bean reads the definition on a thread of its own, so creating it costs the application's start nothing;
     * an accessor called meanwhile waits for the reading instead of failing.
     */
    @Test
    void theDefinitionIsReadOnTheReaderNotOnTheThreadThatCreatesTheBean() throws Exception {
        List<Runnable> handedOver = new ArrayList<>();
        Set<String> readingThreads = ConcurrentHashMap.newKeySet();
        Function<String, byte[]> resources = name -> {
            readingThreads.add(Thread.currentThread().getName());
            return PortrayalDefinition.classPathResource(name);
        };

        PortrayalDefinition bean = new PortrayalDefinition(resources, handedOver::add);

        assertEquals(1, handedOver.size(), "the reading is handed to the reader");
        assertTrue(readingThreads.isEmpty(), "nothing is read on the thread that creates the bean");

        CompletableFuture<String> version = CompletableFuture.supplyAsync(bean::imageVersion);
        Thread.sleep(200);
        assertFalse(version.isDone(), "an accessor waits for the reading");

        Thread reader = Thread.ofPlatform().name("test-reader").start(handedOver.getFirst());
        reader.join(10_000);

        assertEquals(classPathDefinition().imageVersion(), version.get(10, TimeUnit.SECONDS));
        assertEquals(Set.of("test-reader"), readingThreads);
    }

    @Test
    void aDefinitionTheReaderCannotReadIsUnavailableRatherThanAwaitedForEver() {
        PortrayalDefinition bean = new PortrayalDefinition(name -> {
            throw new IllegalStateException("test: no class path");
        }, Runnable::run);

        assertFalse(bean.available());
        assertThrows(IllegalStateException.class, bean::imageVersion);
    }

    @Test
    void theClassPathHoldsAnUnchangedCopyOfEveryDefinitionAndSymbolFile() throws IOException {
        Path source = repositoryFolder(SOURCE_FOLDER);
        List<Path> files;
        try (Stream<Path> list = Files.list(source)) {
            files = list.filter(file -> file.toString().endsWith(".json") || file.toString().endsWith(".svg"))
                    .sorted()
                    .toList();
        }
        assertTrue(files.stream().anyMatch(file -> file.endsWith("portrayal.json")), "no portrayal.json in " + source);

        for (Path file : files) {
            String name = PortrayalDefinition.FOLDER + file.getFileName();
            byte[] copy = PortrayalDefinition.classPathResource(name);
            assertNotNull(copy, name + " is not on the class path: see the <resources> of be/niord/pom.xml");
            assertArrayEquals(Files.readAllBytes(file), copy, name + " differs from " + file);
            if (name.endsWith(".svg")) {
                // A <text> element would draw with whichever font the machine has; the glyphs are outline paths
                assertFalse(new String(copy, StandardCharsets.UTF_8).contains("<text"), name + " has a <text> element");
            }
        }
    }

    @Test
    void everySymbolFileIsOnTheClassPathAndDrawsItsGlyphsAsOutlines() {
        PortrayalDefinition definition = classPathDefinition();
        for (Symbol symbol : definition.symbols().values()) {
            String svg = svgOf(definition, symbol);
            assertTrue(svg.contains("<svg"), symbol.file() + " is not an SVG document");
            assertFalse(svg.contains("<text"), symbol.file() + " has a <text> element: draw the glyphs as outlines");
        }
    }

    @Test
    void eachAnchorIsTheViewBoxOriginAsAFractionOfTheViewBox() {
        PortrayalDefinition definition = classPathDefinition();
        for (Symbol symbol : definition.symbols().values()) {
            double[] viewBox = viewBox(svgOf(definition, symbol), symbol.file());
            assertEquals(-viewBox[0] / viewBox[2], symbol.anchorX(), 1e-4, symbol.id() + " anchor x");
            assertEquals(-viewBox[1] / viewBox[3], symbol.anchorY(), 1e-4, symbol.id() + " anchor y");
        }
    }

    @Test
    void eachSymbolFileDeclaresAWholePixelSizeOfItsViewBoxAspect() {
        PortrayalDefinition definition = classPathDefinition();
        for (Symbol symbol : definition.symbols().values()) {
            String svg = svgOf(definition, symbol);
            double width = attribute(svg, "width", symbol.file());
            double height = attribute(svg, "height", symbol.file());
            assertEquals(Math.rint(width), width, symbol.file() + " width");
            assertEquals(Math.rint(height), height, symbol.file() + " height");
            assertHasViewBoxAspect(viewBox(svg, symbol.file()), width, height, symbol.file());
        }
    }

    @Test
    void everySymbolSizeOfBothSetsKeepsItsViewBoxAspect() {
        PortrayalDefinition definition = classPathDefinition();
        for (SizeSet set : List.of(definition.screen(), definition.print())) {
            set.symbols().forEach((id, size) -> assertHasViewBoxAspect(
                    viewBox(svgOf(definition, definition.symbol(id)), id), size.width(), size.height(),
                    id + " in the " + set.unit() + " size set"));
        }
    }

    @Test
    void theLineDashIsTwoToOneInBothSizeSets() {
        PortrayalDefinition definition = classPathDefinition();
        for (SizeSet set : List.of(definition.screen(), definition.print())) {
            List<Double> dash = set.line().dash();
            assertEquals(2, dash.size(), set.unit() + " line dash " + dash);
            assertEquals(2 * dash.get(1), dash.get(0), 1e-9, set.unit() + " line dash " + dash);
        }
        assertEquals("css-px", definition.screen().unit());
        assertEquals("image-px", definition.print().unit());
        assertTrue(definition.screen().referenceImageSize().isEmpty());
        assertTrue(definition.print().referenceImageSize().isPresent());
    }

    // ------------------------------------------------------------------------------------------- //
    // Style selection
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theSelectRowsEndWithACatchAllAndNameExistingStyles() {
        PortrayalDefinition definition = classPathDefinition();
        List<SelectRow> rows = definition.select();
        assertTrue(rows.getLast().keyless(), "the last row must match every message: " + rows.getLast());
        for (SelectRow row : rows) {
            assertTrue(definition.styles().containsKey(row.style()), row + " names no style");
        }
    }

    @Test
    void aNoticeToMarinersGetsTheNmStyleAndEveryOtherMessageTheNwStyle() {
        PortrayalDefinition definition = classPathDefinition();
        assertEquals("s124-nm", definition.styleFor("NM", null).id());
        assertEquals("s124-nw", definition.styleFor("NW", null).id());
        assertEquals("s124-nw", definition.styleFor(null, null).id());
        assertEquals("nm-point", definition.styleFor("NM", null).point());
        assertEquals("nw-tag", definition.styleFor("NW", null).tag());
    }

    @Test
    void theFirstMatchingRowWinsAndAMessageTypeNarrowsARow() {
        PortrayalDefinition definition = definitionWith(root -> {
            ObjectNode styles = (ObjectNode) root.get("styles");
            styles.set("temporary", styles.get("s124-nm").deepCopy());
            ArrayNode select = JSON.createArrayNode();
            select.addObject().put("mainType", "NM").put("messageType", "TEMPORARY_NOTICE").put("style", "temporary");
            select.addObject().put("mainType", "NM").put("style", "s124-nm");
            select.addObject().put("style", "s124-nw");
            root.set("select", select);
        });
        assertEquals("temporary", definition.styleFor("NM", "TEMPORARY_NOTICE").id());
        assertEquals("s124-nm", definition.styleFor("NM", "PERMANENT_NOTICE").id());
        assertEquals("s124-nm", definition.styleFor("NM", null).id());
        assertEquals("s124-nw", definition.styleFor("NW", "TEMPORARY_NOTICE").id());
        assertEquals("s124-nw", definition.styleFor("nm", null).id(), "the keys are matched exactly");
    }

    // ------------------------------------------------------------------------------------------- //
    // Sizes and colours
    // ------------------------------------------------------------------------------------------- //

    @Test
    void thePrintSetScalesWithTheMapImageSize() {
        PortrayalDefinition definition = classPathDefinition();
        SizeSet print = definition.print();
        int reference = print.referenceImageSize().orElseThrow();
        assertSame(print, definition.print(reference));

        SizeSet doubled = definition.print(2 * reference);
        assertEquals(2 * reference, doubled.referenceImageSize().orElseThrow());
        SymbolSize point = print.symbol("nw-point");
        assertEquals(new SymbolSize(2 * point.width(), 2 * point.height()), doubled.symbol("nw-point"));
        assertEquals(2 * print.line().width(), doubled.line().width(), 1e-9);
        assertEquals(print.line().dash().stream().map(length -> 2 * length).toList(), doubled.line().dash());
        assertEquals(2 * print.tagSpacing(), doubled.tagSpacing(), 1e-9);
        assertEquals(2 * print.buffer().width(), doubled.buffer().width(), 1e-9);
        assertEquals(print.unit(), doubled.unit());

        assertThrows(IllegalArgumentException.class, () -> definition.print(0));
        assertThrows(IllegalArgumentException.class, () -> print.symbol("no-such-symbol"));
    }

    @Test
    void aStrokeGivesItsDashAsBasicStrokeTakesIt() {
        assertArrayEquals(new float[] {12f, 6f}, new StrokeSize(2, List.of(12.0, 6.0)).dashPattern());
        assertNull(new StrokeSize(2, List.of()).dashPattern(), "an empty dash is a solid stroke");
    }

    @Test
    void theColoursAreParsedWithTheirAlpha() {
        PortrayalDefinition definition = classPathDefinition();
        assertEquals(new Color(0xC0, 0x45, 0xD1), definition.colour("NWMGD"));
        assertEquals(new Color(192, 69, 209, 38), definition.colour(definition.buffer().fill()));
        assertEquals(new Color(192, 69, 209, 153), definition.colour(definition.buffer().stroke()));
        assertThrows(IllegalArgumentException.class, () -> definition.colour("no-such-colour"));

        assertEquals(new Color(0, 0, 0, 255), PortrayalDefinitionReader.parseColour("rgba(0,0,0,1)"));
        assertEquals(new Color(255, 255, 255, 0), PortrayalDefinitionReader.parseColour(" rgba(255, 255, 255, 0) "));
        assertEquals(new Color(0xab, 0xcd, 0xef), PortrayalDefinitionReader.parseColour("#ABCDEF"));
        for (String invalid : List.of("magenta", "#abc", "#C045D1FF", "rgb(1, 2, 3)", "rgba(256, 0, 0, 1)",
                "rgba(0, 0, 0, 1.5)", "rgba(0, 0, 0)")) {
            assertThrows(IllegalArgumentException.class, () -> PortrayalDefinitionReader.parseColour(invalid),
                    invalid);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The image version
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aChangeToTheScreenSizesOrTheCatalogueNameKeepsTheImageVersion() {
        String version = classPathDefinition().imageVersion();
        assertEquals(version, definitionWith(root -> {
            ObjectNode screen = (ObjectNode) root.get("sizes").get("screen");
            ((ObjectNode) screen.get("line")).put("width", 3);
            ((ObjectNode) screen.get("symbols")).set("nw-point", JSON.createArrayNode().add(34).add(34));
            screen.put("tagSpacing", 150);
            root.put("catalogue", "another name");
        }).imageVersion());
    }

    @Test
    void aChangeToThePrintSizesChangesTheImageVersion() {
        String version = classPathDefinition().imageVersion();
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("sizes").get("print").get("symbols"))
                .set("nw-point", JSON.createArrayNode().add(30).add(30))).imageVersion());
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("sizes").get("print").get("line"))
                .put("width", 3)).imageVersion());
    }

    @Test
    void aChangeToWhatAnImageShowsChangesTheImageVersion() {
        String version = classPathDefinition().imageVersion();
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("colours"))
                .put("NWMGD", "#C045D2")).imageVersion());
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("symbols").get("nw-tag"))
                .set("anchor", JSON.createArrayNode().add(0.5).add(0.5))).imageVersion());
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("lineStyle"))
                .put("join", "miter")).imageVersion());
        assertNotEquals(version, definitionWith(root -> ((ObjectNode) root.get("styles").get("s124-nm"))
                .put("point", "nw-point")).imageVersion());
    }

    @Test
    void aChangedByteOfASymbolFileChangesTheImageVersion() {
        byte[] json = classPathResource(PortrayalDefinition.DEFINITION);
        String version = PortrayalDefinition.of(json, PortrayalDefinitionTest::symbolFile).imageVersion();

        // One byte of one symbol: the last digit of its colour
        String file = classPathDefinition().symbol("nm-tag").file();
        String svg = new String(symbolFile(file), StandardCharsets.UTF_8);
        assertTrue(svg.contains("#C045D1"), file);
        byte[] changed = svg.replaceFirst("#C045D1", "#C045D2").getBytes(StandardCharsets.UTF_8);
        assertEquals(symbolFile(file).length, changed.length);

        Map<String, byte[]> files = symbolFiles();
        files.put(file, changed);
        assertNotEquals(version, PortrayalDefinition.of(json, files::get).imageVersion());
    }

    @Test
    void carriageReturnsKeyOrderAndFormattingDoNotChangeTheImageVersion() {
        byte[] json = classPathResource(PortrayalDefinition.DEFINITION);
        String version = PortrayalDefinition.of(json, PortrayalDefinitionTest::symbolFile).imageVersion();

        // Every symbol file with CRLF line ends, as a Windows checkout without the LF rule would have it
        Map<String, byte[]> crlf = new HashMap<>();
        symbolFiles().forEach((file, bytes) -> crlf.put(file,
                new String(bytes, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals(version, PortrayalDefinition.of(json, crlf::get).imageVersion());

        // The same definition with its keys reversed at every level and written without line breaks
        JsonNode reversed = reverseKeys(JSON.readTree(json));
        assertEquals(version, PortrayalDefinition.of(JSON.writeValueAsBytes(reversed), PortrayalDefinitionTest::symbolFile)
                .imageVersion());
    }

    @Test
    void aSymbolNoStyleUsesDoesNotChangeTheImageVersion() {
        String version = classPathDefinition().imageVersion();
        assertEquals(version, definitionWith(root -> ((ObjectNode) root.get("symbols")).putObject("spare")
                .put("file", "nw-point.svg")
                .set("anchor", JSON.createArrayNode().add(0.5).add(0.5))).imageVersion());
    }

    // ------------------------------------------------------------------------------------------- //
    // A definition that cannot be used
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMissingOrBrokenDefinitionLeavesTheDefinitionUnavailableInsteadOfFailing() {
        PortrayalDefinition missing = PortrayalDefinition.fromResources(name -> null);
        assertFalse(missing.available());
        assertThrows(IllegalStateException.class, missing::imageVersion);
        assertThrows(IllegalStateException.class, () -> missing.styleFor("NW", null));
        assertThrows(IllegalStateException.class, missing::print);

        PortrayalDefinition broken = PortrayalDefinition.fromResources(name -> name.equals(PortrayalDefinition.DEFINITION)
                ? "{ \"colours\": ".getBytes(StandardCharsets.UTF_8)
                : classPathResource(name));
        assertFalse(broken.available());

        PortrayalDefinition noSymbols = PortrayalDefinition.fromResources(name -> name.equals(PortrayalDefinition.DEFINITION)
                ? classPathResource(name)
                : null);
        assertFalse(noSymbols.available());

        assertTrue(PortrayalDefinition.fromResources(PortrayalDefinitionTest::classPathResource).available());
    }

    @Test
    void anInconsistentDefinitionIsRejectedWithTheReason() {
        assertRejected("symbols.nw-point.file names missing.svg",
                root -> ((ObjectNode) root.get("symbols").get("nw-point")).put("file", "missing.svg"));
        assertRejected("symbols.nw-point.file must name an .svg file",
                root -> ((ObjectNode) root.get("symbols").get("nw-point")).put("file", "../portrayal.json"));
        assertRejected("styles.s124-nw.point names nowhere, which is not a symbol",
                root -> ((ObjectNode) root.get("styles").get("s124-nw")).put("point", "nowhere"));
        assertRejected("styles.s124-nw.stroke names nothing, which is not a colour",
                root -> ((ObjectNode) root.get("styles").get("s124-nw")).put("stroke", "nothing"));
        assertRejected("styles.s124-nw.tag names nw-tag, which has no size in sizes.print",
                root -> ((ObjectNode) root.get("sizes").get("print").get("symbols")).remove("nw-tag"));
        assertRejected("colours.NWMGD '#C045D' is neither",
                root -> ((ObjectNode) root.get("colours")).put("NWMGD", "#C045D"));
        assertRejected("select[1] is the last row and must have no keys",
                root -> ((ObjectNode) root.get("select").get(1)).put("mainType", "NW"));
        assertRejected("select[0] has no keys and matches every message",
                root -> ((ObjectNode) root.get("select").get(0)).remove("mainType"));
        assertRejected("select[0] has the key 'type'",
                root -> ((ObjectNode) root.get("select").get(0)).put("type", "TEMPORARY_NOTICE"));
        assertRejected("select[0].style names s125, which is not a style",
                root -> ((ObjectNode) root.get("select").get(0)).put("style", "s125"));
        assertRejected("sizes.print.referenceImageSize must be a number",
                root -> ((ObjectNode) root.get("sizes").get("print")).remove("referenceImageSize"));
        assertRejected("sizes.screen.line.dash must hold no negative length",
                root -> ((ObjectNode) root.get("sizes").get("screen").get("line"))
                        .set("dash", JSON.createArrayNode().add(4).add(-2)));
        assertRejected("lineStyle.cap must be one of",
                root -> ((ObjectNode) root.get("lineStyle")).put("cap", "flat"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> PortrayalDefinition.of(
                "{\"colours\": {}, \"colours\": {}}".getBytes(StandardCharsets.UTF_8), name -> null));
        assertTrue(duplicate.getMessage().contains("colours"), duplicate.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Reads the class path definition with the factory that throws the reason when it is invalid */
    private static PortrayalDefinition classPathDefinition() {
        byte[] json = classPathResource(PortrayalDefinition.DEFINITION);
        assertNotNull(json, PortrayalDefinition.DEFINITION + " is not on the class path: see the <resources> of"
                + " be/niord/pom.xml");
        return PortrayalDefinition.of(json, PortrayalDefinitionTest::symbolFile);
    }

    /** Returns the class path definition after the edit, with the class path symbol files */
    private static PortrayalDefinition definitionWith(Consumer<ObjectNode> edit) {
        ObjectNode root = (ObjectNode) JSON.readTree(classPathResource(PortrayalDefinition.DEFINITION));
        edit.accept(root);
        return PortrayalDefinition.of(JSON.writeValueAsBytes(root), PortrayalDefinitionTest::symbolFile);
    }

    private static void assertRejected(String reason, Consumer<ObjectNode> edit) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> definitionWith(edit), reason);
        assertTrue(e.getMessage().startsWith(reason), "expected '" + reason + "...', was '" + e.getMessage() + "'");
    }

    private static byte[] classPathResource(String name) {
        return PortrayalDefinition.classPathResource(name);
    }

    private static byte[] symbolFile(String file) {
        return classPathResource(PortrayalDefinition.FOLDER + file);
    }

    /** Returns every symbol file of the class path definition by its name, in a map the test may change */
    private static Map<String, byte[]> symbolFiles() {
        Map<String, byte[]> files = new HashMap<>();
        classPathDefinition().symbols().values().forEach(symbol -> files.put(symbol.file(), symbolFile(symbol.file())));
        return files;
    }

    private static String svgOf(PortrayalDefinition definition, Symbol symbol) {
        return new String(definition.symbolSvg(symbol.id()), StandardCharsets.UTF_8);
    }

    /** Returns the viewBox of the SVG's root element: min x, min y, width, height */
    private static double[] viewBox(String svg, String name) {
        String[] parts = attributeText(svg, "viewBox", name).trim().split("[\\s,]+");
        assertEquals(4, parts.length, name + " viewBox");
        double[] viewBox = new double[4];
        for (int i = 0; i < 4; i++) {
            viewBox[i] = Double.parseDouble(parts[i]);
        }
        assertTrue(viewBox[2] > 0 && viewBox[3] > 0, name + " viewBox size");
        return viewBox;
    }

    private static double attribute(String svg, String attribute, String name) {
        return Double.parseDouble(attributeText(svg, attribute, name));
    }

    private static String attributeText(String svg, String attribute, String name) {
        Matcher root = SVG_ROOT.matcher(svg);
        assertTrue(root.find(), name + " has no <svg> element");
        Matcher value = Pattern.compile("\\s" + attribute + "=\"([^\"]*)\"").matcher(root.group());
        assertTrue(value.find(), name + " declares no " + attribute);
        return value.group(1);
    }

    /**
     * Asserts that the size is the viewBox size at some scale, each length rounded to whole pixels: there is a
     * scale at which both lengths lie within {@value #ROUNDING} pixel of the scaled viewBox lengths.
     */
    private static void assertHasViewBoxAspect(double[] viewBox, double width, double height, String name) {
        double lowest = Math.max((width - ROUNDING) / viewBox[2], (height - ROUNDING) / viewBox[3]);
        double highest = Math.min((width + ROUNDING) / viewBox[2], (height + ROUNDING) / viewBox[3]);
        assertTrue(lowest <= highest, name + ": " + width + " x " + height + " is not the aspect of the viewBox "
                + viewBox[2] + " x " + viewBox[3]);
    }

    private static JsonNode reverseKeys(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), reverseKeys(entry.getValue())));
            ObjectNode reversed = JSON.createObjectNode();
            sorted.descendingMap().forEach(reversed::set);
            return reversed;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.values().forEach(value -> array.add(reverseKeys(value)));
            return array;
        }
        return node;
    }

    /** Finds a folder of the repository from the working directory (the module's directory under Maven) upwards */
    private static Path repositoryFolder(String path) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path folder = dir.resolve(path);
            if (Files.isDirectory(folder)) {
                return folder;
            }
        }
        return fail(path + " not found in " + Path.of("").toAbsolutePath() + " or above it");
    }
}
