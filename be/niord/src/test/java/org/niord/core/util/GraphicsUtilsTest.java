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

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link GraphicsUtils#antialias(Graphics2D)}.
 *
 * <p>The method is expected to install a fixed set of high-quality rendering hints
 * (anti-aliasing, bicubic interpolation, quality rendering, text anti-aliasing and
 * fractional metrics) on the supplied {@link Graphics2D} context.</p>
 *
 * <p>{@link BufferedImage} provides a real, headless {@link Graphics2D} so these tests
 * run without a display.</p>
 */
public class GraphicsUtilsTest {

    /** Returns a fresh ARGB Graphics2D backed by an off-screen BufferedImage. */
    private Graphics2D newGraphics() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        return image.createGraphics();
    }

    @Test
    public void antialiasEnablesAntiAliasing() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasSetsBicubicInterpolation() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasSetsQualityRendering() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_RENDER_QUALITY,
                    g2.getRenderingHint(RenderingHints.KEY_RENDERING));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasEnablesTextAntiAliasing() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasEnablesFractionalMetrics() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasSetsAllFiveHintsTogether() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
            assertEquals(RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION));
            assertEquals(RenderingHints.VALUE_RENDER_QUALITY,
                    g2.getRenderingHint(RenderingHints.KEY_RENDERING));
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
            assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasOverwritesPreviouslyOppositeHints() {
        // setRenderingHints replaces the named hints, so a previously-OFF anti-alias
        // value must be flipped to ON after the call.
        Graphics2D g2 = newGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

            GraphicsUtils.antialias(g2);

            assertEquals(RenderingHints.VALUE_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasPreservesUnrelatedHints() {
        // Graphics2D.setRenderingHints(Map) only adds/replaces the hints in the map,
        // it does not clear other hints. An unrelated hint must survive the call.
        Graphics2D g2 = newGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            GraphicsUtils.antialias(g2);

            assertEquals(RenderingHints.VALUE_STROKE_PURE,
                    g2.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasDoesNotSetUnrelatedHint() {
        // The method only sets five specific hints; a hint it never touches should
        // remain unset (null) on a fresh context.
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            assertNull(g2.getRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasIsIdempotentWhenAppliedTwice() {
        Graphics2D g2 = newGraphics();
        try {
            GraphicsUtils.antialias(g2);
            GraphicsUtils.antialias(g2);
            assertEquals(RenderingHints.VALUE_ANTIALIAS_ON,
                    g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
            assertEquals(RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION));
        } finally {
            g2.dispose();
        }
    }

    @Test
    public void antialiasWithNullGraphicsThrowsNullPointerException() {
        // The method dereferences g2 directly (g2.setRenderingHints), so a null
        // argument must raise a NullPointerException.
        assertThrows(NullPointerException.class, () -> GraphicsUtils.antialias(null));
    }
}
