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

import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import org.niord.core.portrayal.PortrayalSymbols;
import org.niord.core.util.GraphicsUtils;
import org.niord.web.map.MapImageMark.IconMark;
import org.niord.web.map.MapImageMark.PathMark;
import org.niord.web.map.MapImageMark.Subpath;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Paints a map image's draw list ({@link MapImagePlanner}) onto its background with Java 2D, in the list's order,
 * as a canvas draws the web maps: anti-aliased, strokes at their exact position ({@code STROKE_PURE}) with the
 * dash starting anew on every subpath, fills by the non-zero winding rule, and every symbol a pre-rasterised
 * sprite ({@link PortrayalSymbols}) drawn at its whole-pixel position without resampling.
 */
final class MapImagePainter {

    private MapImagePainter() {
    }

    /**
     * Paints the marks onto the image.
     *
     * @param image   the background, which is painted on
     * @param marks   the marks, in the order they are painted
     * @param symbols the sprites of the symbols
     */
    static void paint(BufferedImage image, List<MapImageMark> marks, PortrayalSymbols symbols) {
        Graphics2D g = image.createGraphics();
        try {
            GraphicsUtils.antialias(g);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (MapImageMark mark : marks) {
                switch (mark) {
                    case PathMark path -> paint(g, path);
                    case IconMark icon -> g.drawImage(
                            symbols.sprite(icon.symbolId(), new SymbolSize(icon.width(), icon.height())),
                            icon.x(), icon.y(), null);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static void paint(Graphics2D g, PathMark mark) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (Subpath subpath : mark.subpaths()) {
            double[] xy = subpath.xy();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) {
                path.lineTo(xy[i], xy[i + 1]);
            }
            if (subpath.closed()) {
                path.closePath();
            }
        }
        if (mark.fill() != null) {
            g.setPaint(mark.fill());
            g.fill(path);
        }
        if (mark.stroke() != null) {
            g.setPaint(mark.stroke().colour());
            g.setStroke(mark.stroke().stroke());
            g.draw(path);
        }
    }
}
