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
package org.niord.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalSymbols;
import org.niord.core.portrayal.PortrayalTestSupport;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool for tuning the print size set of the portrayal definition, run on request only:
 * <pre>{@code
 * ./mvnw -pl niord -Dtest=MapImagePreviewTest -Dportrayal.preview=true test
 * }</pre>
 * It paints every case of the golden fixtures ({@code fe/shared-map/portrayal/fixtures/cases.json}) with the
 * print size set onto a plain land-coloured background and writes each to
 * {@code target/portrayal-preview/<case>.png}, together with {@code timings.txt}: how long the symbols take to
 * rasterise when nothing is warm, how long one image takes to plan and paint once they are, and how long the
 * background takes from the network, from memory and from the disk cache (an {@link HttpStub} tile server on
 * this machine, so the network part is a floor).
 *
 * <p>The images are 256 px wide, the size the definition's print set is for. A PDF shows them 120 CSS px =
 * 31.75 mm wide, so one image pixel is 0.124 mm on paper; the size set is tuned against that scale.
 */
@EnabledIfSystemProperty(named = "portrayal.preview", matches = "true")
class MapImagePreviewTest {

    /** OpenStreetMap's land colour, a neutral stand-in for the tiles */
    private static final Color LAND = new Color(0xF2, 0xEF, 0xE9);

    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private static final Path PREVIEW = Path.of("target", "portrayal-preview");

    @Test
    void paintsTheFixturesWithThePrintSizesAndTimesTheSteps() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(PREVIEW);
        List<String> timings = new ArrayList<>();

        long start = System.nanoTime();
        PortrayalDefinition portrayal = PortrayalTestSupport.classPathDefinition();
        timings.add(line("definition read", start));

        PortrayalSymbols symbols = PortrayalSymbols.of(portrayal);
        SizeSet sizes = portrayal.print(256);
        start = System.nanoTime();
        symbols.warmUp(sizes).get(60, TimeUnit.SECONDS);
        timings.add(line("symbols rasterised, nothing warm (" + sizes.symbols().size() + " sprites)", start));

        JsonNode cases = JSON.readTree(Files.readString(
                PortrayalTestSupport.repositoryPath("fe/shared-map/portrayal/fixtures/cases.json"))).get("cases");
        List<Plan> plans = new ArrayList<>();
        for (JsonNode fixture : cases) {
            plans.add(plan(fixture, portrayal, sizes));
        }
        for (Plan plan : plans) {
            BufferedImage image = background(plan.window());
            MapImagePainter.paint(image, MapImagePlanner.plan(plan.fcs(), plan.mainType(), plan.messageType(),
                    portrayal, sizes, plan.window()), symbols);
            ImageIO.write(image, "png", PREVIEW.resolve(plan.id() + ".png").toFile());
        }

        // Plan and paint, warm: every case many times
        int rounds = 200;
        for (int warm = 0; warm < 2; warm++) {
            start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                for (Plan plan : plans) {
                    BufferedImage image = background(plan.window());
                    MapImagePainter.paint(image, MapImagePlanner.plan(plan.fcs(), plan.mainType(),
                            plan.messageType(), portrayal, sizes, plan.window()), symbols);
                }
            }
        }
        timings.add(String.format(Locale.ROOT, "%-58s %8.3f ms", "plan + paint per image, warm",
                (System.nanoTime() - start) / 1e6 / (rounds * plans.size())));

        start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(background(plans.getFirst().window()), "png", png);
        }
        timings.add(String.format(Locale.ROOT, "%-58s %8.3f ms", "PNG encoding per image",
                (System.nanoTime() - start) / 1e6 / rounds));

        // The background: fetched, then from memory, then from the disk cache of a new fetcher
        byte[] tile = tilePng();
        Path cache = Files.createTempDirectory("portrayal-preview-tiles");
        try (HttpStub stub = HttpStub.start()) {
            stub.respond(request -> StubResponse.bytes("image/png", tile));
            TileSource source = new TileSource(stub.baseUrl() + "/{z}/{x}/{y}.png",
                    "Niord/preview (+https://niord.example.org)", cache);
            TileWindow window = plans.getFirst().window();
            TileFetcher fetcher = new TileFetcher();
            try {
                start = System.nanoTime();
                fetcher.background(source, window);
                timings.add(line("background, " + stub.requestCount() + " tiles from the tile server", start));
                start = System.nanoTime();
                fetcher.background(source, window);
                timings.add(line("background, tiles from memory", start));
            } finally {
                fetcher.shutdown();
            }
            TileFetcher restarted = new TileFetcher();
            try {
                start = System.nanoTime();
                restarted.background(source, window);
                timings.add(line("background, tiles from the disk cache", start));
            } finally {
                restarted.shutdown();
            }
        }

        Files.write(PREVIEW.resolve("timings.txt"), timings);
        timings.forEach(System.out::println);
        assertTrue(Files.exists(PREVIEW.resolve("line.png")));
    }

    private record Plan(String id, FeatureCollectionVo[] fcs, String mainType, String messageType,
                        TileWindow window) {
    }

    private static Plan plan(JsonNode fixture, PortrayalDefinition portrayal, SizeSet sizes) {
        JsonNode view = fixture.get("view");
        TileWindow window = TileWindow.centredOn(
                OpenLayersGeometry.x3857(view.get("centre").get(0).asDouble()),
                OpenLayersGeometry.y3857(view.get("centre").get(1).asDouble()),
                view.get("zoom").asInt(), view.get("size").get(0).asInt(), view.get("size").get(1).asInt());
        ObjectNode collection = JSON.createObjectNode();
        collection.put("type", "FeatureCollection");
        collection.set("features", fixture.get("features"));
        FeatureCollectionVo fc = JSON.treeToValue(collection, FeatureCollectionVo.class);
        return new Plan(fixture.get("id").asString(), new FeatureCollectionVo[] { fc },
                fixture.get("mainType").asString(),
                fixture.has("messageType") ? fixture.get("messageType").asString() : null, window);
    }

    private static BufferedImage background(TileWindow window) {
        BufferedImage image = new BufferedImage(window.width(), window.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(LAND);
        g.fillRect(0, 0, window.width(), window.height());
        g.dispose();
        return image;
    }

    /** A tile with some structure, so its PNG is not trivially small */
    private static byte[] tilePng() throws IOException {
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                tile.setRGB(x, y, ((x * 7 + y * 3) % 40 < 20) ? 0xF2EFE9 : 0xAAD3DF);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(tile, "png", png);
        return png.toByteArray();
    }

    private static String line(String what, long start) {
        return String.format(Locale.ROOT, "%-58s %8.1f ms", what, (System.nanoTime() - start) / 1e6);
    }
}
