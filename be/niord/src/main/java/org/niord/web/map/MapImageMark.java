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

import org.niord.core.portrayal.PortrayalDefinition.LineStyle;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.List;
import java.util.Objects;

/**
 * One mark of a map image's draw list: a path that is filled and/or stroked, or a symbol. {@link MapImagePlanner}
 * makes the list, {@link MapImagePainter} paints it in its order onto the background. Every length and position
 * is in image pixels, with the origin at the top-left corner of the image.
 */
public sealed interface MapImageMark permits MapImageMark.PathMark, MapImageMark.IconMark {

    /**
     * The pass of the draw list a mark belongs to. The web maps draw a layer in passes by kind of mark - every
     * area first, then every line, then every symbol - whatever the order of the features, and so does the
     * image; a pass is named after the OpenLayers canvas builder that draws it.
     */
    enum Pass {
        /** Areas: their fills and outlines */
        POLYGON("Polygon"),
        /** Lines */
        LINE_STRING("LineString"),
        /** Symbols */
        IMAGE("Image");

        private final String builderType;

        Pass(String builderType) {
            this.builderType = builderType;
        }

        /** The OpenLayers builder type of the pass, as the golden fixtures name it */
        public String builderType() {
            return builderType;
        }
    }

    /** The pass the mark is painted in */
    Pass pass();

    /**
     * A pen: how a path is stroked.
     *
     * @param colour    the colour, with its alpha
     * @param width     the line width
     * @param dash      the lengths of the dashes and the gaps between them, alternately, starting with a dash; empty
     *                  for a solid line
     * @param lineStyle the cap, join and miter limit
     */
    record Pen(Color colour, double width, List<Double> dash, LineStyle lineStyle) {
        public Pen {
            Objects.requireNonNull(colour);
            dash = List.copyOf(dash);
            Objects.requireNonNull(lineStyle);
        }

        /** The pen as Java 2D takes it; the dash starts at the start of every subpath, as on a canvas */
        public BasicStroke stroke() {
            float[] pattern = null;
            if (!dash.isEmpty()) {
                pattern = new float[dash.size()];
                for (int i = 0; i < pattern.length; i++) {
                    pattern[i] = dash.get(i).floatValue();
                }
            }
            return new BasicStroke((float) width, lineStyle.awtCap(), lineStyle.awtJoin(),
                    (float) Math.max(1, lineStyle.miterLimit()), pattern, 0);
        }
    }

    /**
     * A subpath of a path.
     *
     * @param xy     the x, y pairs of its points
     * @param closed whether the subpath returns to its first point
     */
    record Subpath(double[] xy, boolean closed) {
        public Subpath {
            Objects.requireNonNull(xy);
        }
    }

    /**
     * A path, filled and then stroked. A fill covers the areas of the subpaths by the non-zero winding rule.
     *
     * @param pass     the pass
     * @param fill     the fill colour, or null for no fill
     * @param stroke   the pen, or null for no stroke
     * @param subpaths the subpaths
     */
    record PathMark(Pass pass, Color fill, Pen stroke, List<Subpath> subpaths) implements MapImageMark {
        public PathMark {
            Objects.requireNonNull(pass);
            subpaths = List.copyOf(subpaths);
        }
    }

    /**
     * A symbol of the portrayal definition, drawn with its top-left corner on a whole pixel.
     *
     * @param symbolId the symbol id
     * @param x        the image column of the symbol's left edge
     * @param y        the image row of the symbol's top edge
     * @param width    the width the symbol is drawn at
     * @param height   the height the symbol is drawn at
     */
    record IconMark(String symbolId, int x, int y, double width, double height) implements MapImageMark {
        public IconMark {
            Objects.requireNonNull(symbolId);
        }

        @Override
        public Pass pass() {
            return Pass.IMAGE;
        }
    }
}
