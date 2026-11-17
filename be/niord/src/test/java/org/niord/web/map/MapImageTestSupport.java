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
package org.niord.web.map;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import io.restassured.response.Response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small, framework-neutral image helpers shared by the map-image unit tests ({@link MessageMapImageGenerator},
 * {@link TileFetcher}, {@link CustomThumbnailScaler}).
 *
 * <p>Everything here is plain {@code java.awt} / {@code javax.imageio}. The class makes no assertions of its
 * own; it only builds test fixtures (solid-colour PNG/JPEG byte buffers) and reads rendered output (per-pixel
 * colour and alpha), so the test classes can express meaningful behavioural oracles about what was drawn or
 * written.
 */
final class MapImageTestSupport {

    /** Asserts that the response served the given image file of the cache tree, byte for byte */
    static void assertServes(Path file, Response response) throws IOException {
        assertEquals(200, response.statusCode(), "the image is served, not redirected to: " + file);
        assertNull(response.getHeader("Location"));
        assertEquals("image/png", response.getContentType());
        assertTrue(Files.isRegularFile(file), "the served image exists: " + file);
        assertArrayEquals(Files.readAllBytes(file), response.asByteArray(), "the response serves " + file);
    }

    private MapImageTestSupport() {
    }

    /** An <em>opaque</em> solid-colour PNG of the given size, as encoded bytes. */
    static byte[] solidPng(int w, int h, Color color) throws IOException {
        return encode(w, h, color, "png");
    }

    /** An <em>opaque</em> solid-colour JPEG of the given size — used to prove the format-agnostic fast path. */
    static byte[] jpeg(int w, int h, Color color) throws IOException {
        return encode(w, h, color, "jpeg");
    }

    private static byte[] encode(int w, int h, Color color, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, format, b);
        return b.toByteArray();
    }

    /** Decodes an in-memory image buffer. */
    static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** Reads a written image file back into memory. */
    static BufferedImage read(Path path) throws IOException {
        return ImageIO.read(path.toFile());
    }

    /** The colour (ignoring alpha) at the given pixel. */
    static Color rgb(BufferedImage img, int x, int y) {
        return new Color(img.getRGB(x, y), false);
    }

    /** The alpha channel (0-255) at the given pixel. */
    static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xff;
    }

    /** Recursively deletes a temporary directory tree (test teardown). */
    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
    }
}
