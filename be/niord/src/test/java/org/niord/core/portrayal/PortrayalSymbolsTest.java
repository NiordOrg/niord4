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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalDefinition.Symbol;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The symbols of the portrayal definition rasterised for the map images: every symbol at every size the images
 * draw it at, in the definition's colour, its ink inside the sprite and centred where its anchor says, and each
 * sprite made once.
 */
class PortrayalSymbolsTest {

    /** The magenta every symbol of the definition is drawn in */
    private static final Color NWMGD = new Color(0xC0, 0x45, 0xD1);

    private static PortrayalDefinition definition;

    @BeforeAll
    static void headlessDefinition() {
        System.setProperty("java.awt.headless", "true");
        definition = PortrayalTestSupport.classPathDefinition();
    }

    @Test
    void everySymbolRasterisesAtEveryPrintSizeWithItsInkInsideTheSprite() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        for (int imageSize : List.of(256, 512, 300)) {
            SizeSet sizes = definition.print(imageSize);
            for (Map.Entry<String, SymbolSize> entry : sizes.symbols().entrySet()) {
                String id = entry.getKey();
                SymbolSize size = entry.getValue();
                String name = id + " at " + size.width() + " x " + size.height();

                BufferedImage sprite = symbols.sprite(id, size);

                assertEquals(Math.round(size.width()), sprite.getWidth(), name);
                assertEquals(Math.round(size.height()), sprite.getHeight(), name);
                assertEquals(BufferedImage.TYPE_INT_ARGB, sprite.getType(), name);
                Ink ink = Ink.of(sprite);
                assertTrue(ink.pixels() > sprite.getWidth(), name + " draws almost nothing: " + ink.pixels() + " px");
                // Every symbol has a margin inside its viewBox: nothing is cut off at the sprite's edge
                assertTrue(ink.minX() >= 1 && ink.maxX() <= sprite.getWidth() - 2 && ink.minY() >= 1
                        && ink.maxY() <= sprite.getHeight() - 2, name + " touches the edge of its sprite: " + ink);
                assertTrue(ink.opaque() > 0, name + " has no fully covered pixel");
                assertEquals(NWMGD, ink.opaqueColour(), name + " is not drawn in the definition's magenta");
            }
        }
    }

    /**
     * The point symbol is a ring around its pivot with the letters inside: the ink is centred on the anchor, and
     * there is ink both at the ring and at the letters near the centre, with an empty band between them
     */
    @Test
    void thePointSymbolIsARingAroundItsAnchorWithTheLettersInside() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        for (String id : List.of("nw-point", "nm-point")) {
            SymbolSize size = definition.print().symbol(id);
            Symbol symbol = definition.symbol(id);
            BufferedImage sprite = symbols.sprite(id, size);
            Ink ink = Ink.of(sprite);

            double anchorX = symbol.anchorX() * sprite.getWidth();
            double anchorY = symbol.anchorY() * sprite.getHeight();
            assertEquals(anchorX, (ink.minX() + ink.maxX() + 1) / 2.0, 1.0, id + " ring centre x");
            assertEquals(anchorY, (ink.minY() + ink.maxY() + 1) / 2.0, 1.0, id + " ring centre y");

            // The ring is r = 5 of a 13.96 viewBox, the letters span about 3.5 of it on either side of the centre
            double unit = sprite.getWidth() / 13.96;
            int y = (int) anchorY;
            assertTrue(hasInk(sprite, (int) (anchorX + 5 * unit), y), id + " has no ring right of the centre");
            assertTrue(!hasInk(sprite, (int) (anchorX + 4.2 * unit), y), id + " is not empty inside the ring");
            boolean letters = false;
            for (int x = (int) (anchorX - 3 * unit); x <= anchorX + 3 * unit; x++) {
                letters |= hasInk(sprite, x, y);
            }
            assertTrue(letters, id + " has no letters at the centre");
        }
    }

    @Test
    void theNwAndNmSymbolsDiffer() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        SymbolSize size = definition.print().symbol("nw-point");
        BufferedImage nw = symbols.sprite("nw-point", size);
        BufferedImage nm = symbols.sprite("nm-point", size);
        int differing = 0;
        for (int y = 0; y < nw.getHeight(); y++) {
            for (int x = 0; x < nw.getWidth(); x++) {
                differing += nw.getRGB(x, y) != nm.getRGB(x, y) ? 1 : 0;
            }
        }
        assertTrue(differing > 10, "the NW and NM point symbols look the same: " + differing + " px differ");
    }

    @Test
    void aSpriteIsRasterisedOncePerSymbolAndSize() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        SymbolSize size = definition.print().symbol("nw-tag");

        BufferedImage first = symbols.sprite("nw-tag", size);

        assertSame(first, symbols.sprite("nw-tag", size));
        // A length is rounded to whole pixels before it is looked up
        assertSame(first, symbols.sprite("nw-tag", new SymbolSize(size.width() + 0.3, size.height() - 0.3)));
        assertNotSame(first, symbols.sprite("nw-tag", new SymbolSize(size.width() + 2, size.height() + 1)));
    }

    @Test
    void theWarmUpRasterisesEverySymbolOfTheSizeSetAhead() throws Exception {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        SizeSet sizes = definition.print(256);

        symbols.warmUp(sizes).get(30, TimeUnit.SECONDS);

        // Every sprite is there: asking for it again returns the same instance without rasterising
        for (Map.Entry<String, SymbolSize> entry : sizes.symbols().entrySet()) {
            BufferedImage sprite = symbols.sprite(entry.getKey(), entry.getValue());
            assertSame(sprite, symbols.sprite(entry.getKey(), entry.getValue()));
        }
    }

    @Test
    void rasteriseAllRasterisesEverySymbolOfTheSizeSetBeforeItReturns() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        SizeSet sizes = definition.print(256);
        assertEquals(0, symbols.spriteCount());

        symbols.rasteriseAll(sizes);

        assertEquals(sizes.symbols().size(), symbols.spriteCount(), "one sprite per symbol of the set, made here");
        BufferedImage point = symbols.sprite("nw-point", sizes.symbol("nw-point"));
        assertEquals(sizes.symbols().size(), symbols.spriteCount(), "asking for a sprite afterwards makes none");
        assertEquals(Math.round(sizes.symbol("nw-point").width()), point.getWidth());
    }

    @Test
    void anUnknownSymbolIsRejected() {
        PortrayalSymbols symbols = PortrayalSymbols.of(definition);
        assertThrows(IllegalArgumentException.class, () -> symbols.sprite("no-such-symbol", new SymbolSize(10, 10)));
    }

    private static boolean hasInk(BufferedImage sprite, int x, int y) {
        return x >= 0 && y >= 0 && x < sprite.getWidth() && y < sprite.getHeight()
                && (sprite.getRGB(x, y) >>> 24) > 64;
    }

    /** Where a sprite has ink (alpha above zero), and the colour of its fully covered pixels */
    private record Ink(int pixels, int opaque, int minX, int minY, int maxX, int maxY, Color opaqueColour) {

        static Ink of(BufferedImage sprite) {
            int pixels = 0;
            int opaque = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            Color colour = null;
            for (int y = 0; y < sprite.getHeight(); y++) {
                for (int x = 0; x < sprite.getWidth(); x++) {
                    int argb = sprite.getRGB(x, y);
                    int alpha = argb >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    pixels++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    if (alpha == 255) {
                        opaque++;
                        colour = new Color(argb & 0xFFFFFF);
                    }
                }
            }
            return new Ink(pixels, opaque, minX, minY, maxX, maxY, colour);
        }
    }
}
