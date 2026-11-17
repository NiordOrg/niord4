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

import org.niord.web.map.MapImageMark.IconMark;
import org.niord.web.map.MapImageMark.Pass;
import org.niord.web.map.MapImageMark.PathMark;
import org.niord.web.map.MapImageMark.Pen;
import org.niord.web.map.MapImageMark.Subpath;
import org.niord.web.map.OpenLayersGeometry.FlatBuilder;
import org.niord.web.map.OpenLayersGeometry.Geom;
import org.niord.web.map.OpenLayersGeometry.GeometryCollection;
import org.niord.web.map.OpenLayersGeometry.LineString;
import org.niord.web.map.OpenLayersGeometry.MultiLineString;
import org.niord.web.map.OpenLayersGeometry.MultiPoint;
import org.niord.web.map.OpenLayersGeometry.MultiPolygon;
import org.niord.web.map.OpenLayersGeometry.Point;
import org.niord.web.map.OpenLayersGeometry.Polygon;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.niord.web.map.OpenLayersGeometry.buffer;
import static org.niord.web.map.OpenLayersGeometry.containsXY;
import static org.niord.web.map.OpenLayersGeometry.jsRound;
import static org.niord.web.map.OpenLayersGeometry.orientedFlatCoordinates;
import static org.niord.web.map.OpenLayersGeometry.simplified;
import static org.niord.web.map.OpenLayersGeometry.snap;

/**
 * How an OpenLayers vector layer draws a feature's styles onto its canvas, ported for the backend's map images:
 * the web maps' canvas replay ({@code ol/render/canvas/BuilderGroup}, its polygon, line string and image builders,
 * and {@code ol/render/canvas/Executor}), reduced to what the portrayal uses - fills, dashed strokes and symbols
 * without text, rotation or offsets - and the view of one map image, whose canvas is the image at pixel ratio 1.
 *
 * <p>A style is {@linkplain #render rendered} per feature in the layer's order; the replay collects the drawing
 * per kind of mark and {@link #finish()} returns the draw list in the order OpenLayers replays it: every area
 * ({@code Polygon} builder), then every line ({@code LineString}), then every symbol ({@code Image}). On the way it
 * does what OpenLayers does to the coordinates, each step a fraction of a pixel but together the difference
 * between the same mark and a near miss:
 * <ul>
 *   <li>simplifies each geometry with a tolerance of half a pixel ({@link OpenLayersGeometry#simplified});</li>
 *   <li>strokes the rings of a polygon in OpenLayers' orientation, which sets where a ring's dash starts;</li>
 *   <li>leaves out the coordinates of a line that stay outside the view plus the render buffer, which changes
 *       where the dashes of a line that leaves the view fall;</li>
 *   <li>snaps every coordinate of an area to the half-pixel grid of the simplification;</li>
 *   <li>skips a vertex that rounds to the same pixel as the one before it, the last one excepted;</li>
 *   <li>puts a symbol's top-left corner on the nearest whole pixel, and draws no symbol whose box lies outside
 *       the image.</li>
 * </ul>
 *
 * <p>The view is the map image's {@link TileWindow}: its centre, its resolution and its size in pixels, with the
 * pixel transform OpenLayers composes for a view centred there ({@code CanvasLayerRenderer.getRenderTransform}),
 * which puts a point where {@link TileWindow#imageX} and {@link TileWindow#imageY} do.
 */
final class OpenLayersReplay {

    /** {@code renderBuffer} of an OpenLayers vector layer, its default: the margin around the view, in pixels */
    static final double RENDER_BUFFER = 100;

    /** {@code SIMPLIFY_TOLERANCE} of {@code ol/renderer/vector}: half a pixel */
    static final double SIMPLIFY_TOLERANCE = 0.5;

    /**
     * A symbol as the image builder draws it.
     *
     * @param symbolId the symbol id
     * @param anchorX  the distance from the symbol's left edge to the point placed on the position, in pixels
     * @param anchorY  the distance from its top edge to that point, in pixels
     * @param width    the width it is drawn at
     * @param height   the height it is drawn at
     */
    record ImageSymbol(String symbolId, double anchorX, double anchorY, double width, double height) {
        ImageSymbol {
            Objects.requireNonNull(symbolId);
        }
    }

    private final int width;
    private final int height;
    private final double resolution;
    private final double[] maxExtent;
    private final double tolerance;
    private final double squaredTolerance;
    private final double sx;
    private final double sy;
    private final double dx;
    private final double dy;

    private final PolygonBuilder polygons = new PolygonBuilder();
    private final LineStringBuilder lines = new LineStringBuilder();
    private final ImageBuilder images = new ImageBuilder();

    /** A replay onto the given map image */
    OpenLayersReplay(TileWindow window) {
        this.width = window.width();
        this.height = window.height();
        this.resolution = window.resolution();
        // The view's centre: the centre of the window, whose corner is a whole pixel
        double centreX = (window.originX() + width / 2.0) * resolution - OpenLayersGeometry.HALF_SIZE;
        double centreY = OpenLayersGeometry.HALF_SIZE - (window.originY() + height / 2.0) * resolution;
        // getForViewAndSize of ol/extent, then the layer's render buffer (CanvasVectorLayerRenderer.prepareFrame)
        double halfWidth = (resolution * width) / 2;
        double halfHeight = (resolution * height) / 2;
        double[] frame = { centreX - halfWidth, centreY - halfHeight, centreX + halfWidth, centreY + halfHeight };
        this.maxExtent = buffer(frame, RENDER_BUFFER * resolution);
        // getTolerance and getSquaredTolerance of ol/renderer/vector at pixel ratio 1
        this.tolerance = SIMPLIFY_TOLERANCE * resolution;
        this.squaredTolerance = tolerance * tolerance;
        // compose of ol/transform with no rotation: x' = sx * x + dx, y' = sy * y + dy
        this.sx = 1 / resolution;
        this.sy = -sx;
        this.dx = -centreX * sx + width / 2.0;
        this.dy = -centreY * sy + height / 2.0;
    }

    /** The extent of the view plus the render buffer: a feature outside it is not drawn */
    double[] maxExtent() {
        return maxExtent.clone();
    }

    /**
     * Renders one style of a feature ({@code renderFeature} of {@code ol/renderer/vector}): the geometry is
     * simplified and handed to the builder of its kind. A style draws what it has on a geometry that can show
     * it: a fill and a pen on areas, a pen on lines, a symbol on points; on anything else it draws nothing.
     *
     * @param geometry the style's geometry, or null for none
     * @param fill     the fill colour, or null
     * @param stroke   the pen, or null
     * @param image    the symbol, or null
     */
    void render(Geom geometry, Color fill, Pen stroke, ImageSymbol image) {
        if (geometry == null) {
            return;
        }
        draw(simplified(geometry, squaredTolerance), fill, stroke, image);
    }

    private void draw(Geom geometry, Color fill, Pen stroke, ImageSymbol image) {
        switch (geometry) {
            case Point point -> {
                if (image != null) {
                    images.drawPoint(point.x(), point.y(), image);
                }
            }
            case MultiPoint points -> {
                if (image != null) {
                    images.drawMultiPoint(points.flat(), image);
                }
            }
            case LineString line -> {
                if (stroke != null) {
                    lines.setStroke(stroke);
                    lines.drawLine(line.flat(), new int[] { line.flat().length });
                }
            }
            case MultiLineString multiLine -> {
                if (stroke != null) {
                    lines.setStroke(stroke);
                    lines.drawLine(multiLine.flat(), multiLine.ends());
                }
            }
            case Polygon polygon -> {
                if (fill != null || stroke != null) {
                    polygons.setFillStroke(fill, stroke);
                    polygons.drawRings(orientedFlatCoordinates(polygon), 0, polygon.ends());
                }
            }
            case MultiPolygon multiPolygon -> {
                if (fill != null || stroke != null) {
                    polygons.setFillStroke(fill, stroke);
                    double[] oriented = orientedFlatCoordinates(multiPolygon);
                    int offset = 0;
                    for (int[] ends : multiPolygon.endss()) {
                        offset = polygons.drawRings(oriented, offset, ends);
                    }
                }
            }
            case GeometryCollection collection -> {
                for (Geom member : collection.geometries()) {
                    draw(member, fill, stroke, image);
                }
            }
        }
    }

    /**
     * Returns the draw list: the areas, then the lines, then the symbols, each in the order they were rendered
     * ({@code ExecutorGroup.execute}). The replay cannot be used afterwards.
     */
    List<MapImageMark> finish() {
        polygons.finish();
        lines.finish();
        List<MapImageMark> marks = new ArrayList<>();
        executePaths(polygons, polygons.paths, Pass.POLYGON, marks);
        executePaths(lines, lines.paths, Pass.LINE_STRING, marks);
        executeImages(marks);
        return marks;
    }

    // ------------------------------------------------------------------------------------------- //
    // Executor
    // ------------------------------------------------------------------------------------------- //

    /** {@code transform2D} of {@code ol/geom/flat/transform}: the pixel positions of a builder's coordinates */
    private double[] pixels(FlatBuilder coordinates) {
        double[] pixels = new double[coordinates.size()];
        for (int i = 0; i < pixels.length; i += 2) {
            pixels[i] = sx * coordinates.get(i) + dx;
            pixels[i + 1] = sy * coordinates.get(i + 1) + dy;
        }
        return pixels;
    }

    private void executePaths(Builder builder, List<PathOp> paths, Pass pass, List<MapImageMark> marks) {
        double[] pixels = pixels(builder.coordinates);
        for (PathOp path : paths) {
            List<Subpath> subpaths = new ArrayList<>();
            for (Part part : path.parts()) {
                if (part.end() - part.begin() >= 2) {
                    subpaths.add(moveToLineTo(pixels, part.begin(), part.end(), part.closePath()));
                }
            }
            if (!subpaths.isEmpty()) {
                marks.add(new PathMark(pass, path.fill(), path.stroke(), subpaths));
            }
        }
    }

    /**
     * The {@code MOVE_TO_LINE_TO} instruction: a move to the first point and a line to each further point, except
     * to one that rounds to the same pixel as the point drawn before it; the last point is always drawn.
     */
    private static Subpath moveToLineTo(double[] pixels, int begin, int end, boolean closePath) {
        FlatBuilder points = new FlatBuilder();
        double x = pixels[begin];
        double y = pixels[begin + 1];
        points.add(x, y);
        int prevX = toInt32(x + 0.5);
        int prevY = toInt32(y + 0.5);
        for (int k = begin + 2; k < end; k += 2) {
            x = pixels[k];
            y = pixels[k + 1];
            int roundX = toInt32(x + 0.5);
            int roundY = toInt32(y + 0.5);
            if (k == end - 2 || roundX != prevX || roundY != prevY) {
                points.add(x, y);
                prevX = roundX;
                prevY = roundY;
            }
        }
        return new Subpath(points.toArray(), closePath);
    }

    /** JavaScript's {@code value | 0}: truncated toward zero to a 32-bit integer */
    private static int toInt32(double value) {
        return Double.isFinite(value) ? (int) (long) value : 0;
    }

    /** The {@code DRAW_IMAGE} instruction for each position, snapped to whole pixels */
    private void executeImages(List<MapImageMark> marks) {
        double[] pixels = pixels(images.coordinates);
        for (ImageOp op : images.ops) {
            ImageSymbol symbol = op.symbol();
            for (int d = op.begin(); d < op.end(); d += 2) {
                double x = pixels[d] - symbol.anchorX();
                double y = pixels[d + 1] - symbol.anchorY();
                // The declutter box of calculateImageOrLabelDimensions_; replayImageOrLabel_ draws only a box
                // that intersects the canvas
                double minX = Math.min(x, x + symbol.width());
                double maxX = Math.max(x, x + symbol.width());
                double minY = Math.min(y, y + symbol.height());
                double maxY = Math.max(y, y + symbol.height());
                if (minX <= width && maxX >= 0 && minY <= height && maxY >= 0) {
                    marks.add(new IconMark(symbol.symbolId(), (int) jsRound(x), (int) jsRound(y),
                            symbol.width(), symbol.height()));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Builders
    // ------------------------------------------------------------------------------------------- //

    /** A ring or line of a path: its coordinates in the builder, and whether it is closed */
    private record Part(int begin, int end, boolean closePath) {
    }

    /** A path: filled and/or stroked once, after all its parts */
    private record PathOp(Color fill, Pen stroke, List<Part> parts) {
    }

    /** A symbol at the positions {@code begin} to {@code end} of the image builder's coordinates */
    private record ImageOp(ImageSymbol symbol, int begin, int end) {
    }

    /** {@code ol/render/canvas/Builder}: the coordinates of one kind of mark, culled to the view */
    private abstract class Builder {

        final FlatBuilder coordinates = new FlatBuilder();
        private double maxLineWidth;
        private double[] bufferedMaxExtent;

        /** {@code getBufferedMaxExtent}: the view plus the render buffer plus half the widest pen */
        double[] bufferedMaxExtent() {
            if (bufferedMaxExtent == null) {
                bufferedMaxExtent = maxExtent.clone();
                if (maxLineWidth > 0) {
                    bufferedMaxExtent = buffer(bufferedMaxExtent, (resolution * (maxLineWidth + 1)) / 2);
                }
            }
            return bufferedMaxExtent;
        }

        /** The part of {@code strokeStyleToState} that widens the buffered extent for a wider pen */
        void widenFor(Pen stroke) {
            if (stroke != null && stroke.width() > maxLineWidth) {
                maxLineWidth = stroke.width();
                bufferedMaxExtent = null;
            }
        }

        /**
         * {@code appendFlatLineCoordinates}: appends a line or ring, leaving out the runs of coordinates that stay
         * on one side outside the buffered extent (their first and last coordinate stay)
         *
         * @return the new end of the builder's coordinates
         */
        int appendFlatLineCoordinates(double[] flat, int offset, int end, boolean closed, boolean skipFirst) {
            if (skipFirst) {
                offset += 2;
            }
            if (offset >= end) {
                // A part without coordinates, which only malformed input has: nothing to draw
                return coordinates.size();
            }
            double[] extent = bufferedMaxExtent();
            double lastX = flat[offset];
            double lastY = flat[offset + 1];
            boolean skipped = true;
            int lastRel = -1;
            int i;
            for (i = offset + 2; i < end; i += 2) {
                double nextX = flat[i];
                double nextY = flat[i + 1];
                int nextRel = coordinateRelationship(extent, nextX, nextY);
                if (nextRel != lastRel) {
                    if (skipped) {
                        coordinates.add(lastX, lastY);
                        skipped = false;
                    }
                    coordinates.add(nextX, nextY);
                } else if (nextRel == INTERSECTING) {
                    coordinates.add(nextX, nextY);
                    skipped = false;
                } else {
                    skipped = true;
                }
                lastX = nextX;
                lastY = nextY;
                lastRel = nextRel;
            }
            if ((closed && skipped) || i == offset + 2) {
                coordinates.add(lastX, lastY);
            }
            return coordinates.size();
        }

        /** {@code appendFlatPointCoordinates}: appends the positions inside the buffered extent */
        int appendFlatPointCoordinates(double[] flat) {
            double[] extent = bufferedMaxExtent();
            for (int i = 0; i + 1 < flat.length; i += 2) {
                if (containsXY(extent, flat[i], flat[i + 1])) {
                    coordinates.add(flat[i], flat[i + 1]);
                }
            }
            return coordinates.size();
        }
    }

    // The flags of ol/extent/Relationship
    private static final int INTERSECTING = 1;
    private static final int ABOVE = 2;
    private static final int RIGHT = 4;
    private static final int BELOW = 8;
    private static final int LEFT = 16;

    /** {@code coordinateRelationship} of {@code ol/extent} */
    private static int coordinateRelationship(double[] extent, double x, double y) {
        int relationship = 0;
        if (x < extent[0]) {
            relationship |= LEFT;
        } else if (x > extent[2]) {
            relationship |= RIGHT;
        }
        if (y < extent[1]) {
            relationship |= BELOW;
        } else if (y > extent[3]) {
            relationship |= ABOVE;
        }
        return relationship == 0 ? INTERSECTING : relationship;
    }

    /** {@code ol/render/canvas/PolygonBuilder}: each polygon one path, filled and then stroked */
    private final class PolygonBuilder extends Builder {

        final List<PathOp> paths = new ArrayList<>();
        private Color fill;
        private Pen stroke;

        void setFillStroke(Color fill, Pen stroke) {
            this.fill = fill;
            this.stroke = stroke;
            widenFor(stroke);
        }

        /** {@code drawFlatCoordinatess_}: one path of the polygon's rings; returns the offset after them */
        int drawRings(double[] flat, int offset, int[] ends) {
            boolean stroked = stroke != null;
            List<Part> parts = new ArrayList<>();
            for (int end : ends) {
                int begin = coordinates.size();
                int myEnd = appendFlatLineCoordinates(flat, offset, end, true, !stroked);
                // A ring is closed with closePath only when it is stroked; a fill closes it anyway
                parts.add(new Part(begin, myEnd, stroked));
                offset = end;
            }
            paths.add(new PathOp(fill, stroke, parts));
            return offset;
        }

        /** {@code finish}: every coordinate snapped to the simplification grid, to keep shared edges shared */
        void finish() {
            if (tolerance != 0) {
                for (int i = 0; i < coordinates.size(); ++i) {
                    coordinates.set(i, snap(coordinates.get(i), tolerance));
                }
            }
        }
    }

    /**
     * {@code ol/render/canvas/LineStringBuilder}: consecutive lines with an equal pen are one path, stroked once
     * when the pen changes or at the end
     */
    private final class LineStringBuilder extends Builder {

        final List<PathOp> paths = new ArrayList<>();
        private Pen stroke;
        private Pen currentStroke;
        private Integer lastStroke;
        private PathOp open;

        void setStroke(Pen stroke) {
            this.stroke = stroke;
            widenFor(stroke);
        }

        /** {@code drawLineString} / {@code drawMultiLineString}: the lines ending at {@code ends} */
        void drawLine(double[] flat, int[] ends) {
            if (!stroke.equals(currentStroke)) {
                applyStroke();
                currentStroke = stroke;
            }
            int offset = 0;
            for (int end : ends) {
                int begin = coordinates.size();
                int myEnd = appendFlatLineCoordinates(flat, offset, end, false, false);
                open.parts().add(new Part(begin, myEnd, false));
                offset = end;
            }
        }

        /** {@code applyStroke}: strokes the open path, sets the new pen and begins a new path */
        private void applyStroke() {
            if (lastStroke != null && lastStroke != coordinates.size()) {
                strokeOpenPath();
            }
            lastStroke = 0;
            open = new PathOp(null, stroke, new ArrayList<>());
        }

        void finish() {
            if (lastStroke != null && lastStroke != coordinates.size()) {
                strokeOpenPath();
            }
        }

        private void strokeOpenPath() {
            if (open != null) {
                paths.add(open);
                open = null;
            }
        }
    }

    /** {@code ol/render/canvas/ImageBuilder}: a symbol at each position inside the view plus the render buffer */
    private final class ImageBuilder extends Builder {

        final List<ImageOp> ops = new ArrayList<>();

        void drawPoint(double x, double y, ImageSymbol symbol) {
            if (!containsXY(maxExtent, x, y)) {
                return;
            }
            int begin = coordinates.size();
            int end = appendFlatPointCoordinates(new double[] { x, y });
            ops.add(new ImageOp(symbol, begin, end));
        }

        void drawMultiPoint(double[] flat, ImageSymbol symbol) {
            FlatBuilder filtered = new FlatBuilder();
            for (int i = 0; i + 1 < flat.length; i += 2) {
                if (containsXY(maxExtent, flat[i], flat[i + 1])) {
                    filtered.add(flat[i], flat[i + 1]);
                }
            }
            int begin = coordinates.size();
            int end = appendFlatPointCoordinates(filtered.toArray());
            ops.add(new ImageOp(symbol, begin, end));
        }
    }
}
