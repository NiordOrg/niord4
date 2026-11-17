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

import org.niord.model.geojson.GeometryCollectionVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiLineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.MultiPolygonVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The geometry of a message as OpenLayers holds it, and the OpenLayers algorithms that decide where the web maps
 * draw its marks, ported so that the backend's map images place every mark where the web maps do.
 *
 * <p>A geometry is kept as OpenLayers keeps it: flat arrays of x, y pairs in EPSG:3857 metres, with the ends of
 * its rings or lines as indexes into that array. Every method names the OpenLayers function it follows (OpenLayers
 * 10, {@code ol/geom/*} and {@code ol/geom/flat/*}) and keeps its arithmetic, its order of operations and its
 * quirks, since a changed rounding moves a mark by a pixel. The golden fixtures of the portrayal definition
 * ({@code fe/shared-map/portrayal/fixtures}) compare the two engines and fail when they drift apart.
 *
 * <p>Nothing here changes its input: every method that reorients or simplifies works on a copy.
 */
final class OpenLayersGeometry {

    /** The earth radius of EPSG:3857, {@code RADIUS} of {@code ol/proj/epsg3857} */
    static final double RADIUS = 6378137;

    /** Half the width of the EPSG:3857 world, {@code HALF_SIZE} of {@code ol/proj/epsg3857} */
    static final double HALF_SIZE = Math.PI * RADIUS;

    /** The northing a pole is clamped to, {@code MAX_SAFE_Y} of {@code ol/proj/epsg3857} */
    static final double MAX_SAFE_Y = RADIUS * StrictMath.log(StrictMath.tan(Math.PI / 2));

    private OpenLayersGeometry() {
    }

    /** A geometry in EPSG:3857 metres, one type per OpenLayers geometry class */
    sealed interface Geom permits Point, MultiPoint, LineString, MultiLineString, Polygon, MultiPolygon,
            GeometryCollection {
    }

    /** {@code ol/geom/Point} */
    record Point(double x, double y) implements Geom {
    }

    /** {@code ol/geom/MultiPoint}: x, y pairs */
    record MultiPoint(double[] flat) implements Geom {
    }

    /** {@code ol/geom/LineString}: x, y pairs */
    record LineString(double[] flat) implements Geom {
    }

    /** {@code ol/geom/MultiLineString}: x, y pairs, and the end of each line in {@code flat} */
    record MultiLineString(double[] flat, int[] ends) implements Geom {
    }

    /** {@code ol/geom/Polygon}: x, y pairs, and the end of each ring in {@code flat}; the first ring is the exterior */
    record Polygon(double[] flat, int[] ends) implements Geom {
    }

    /** {@code ol/geom/MultiPolygon}: x, y pairs, and per polygon the end of each of its rings in {@code flat} */
    record MultiPolygon(double[] flat, int[][] endss) implements Geom {
    }

    /** {@code ol/geom/GeometryCollection} */
    record GeometryCollection(List<Geom> geometries) implements Geom {
        GeometryCollection {
            geometries = List.copyOf(geometries);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Reading GeoJSON (ol/format/GeoJSON with the data projection EPSG:4326)
    // ------------------------------------------------------------------------------------------- //

    /**
     * Returns the geometry of a GeoJSON geometry, projected from longitude and latitude to EPSG:3857 as the web
     * maps read it ({@code GeoJSON.readFeature} with {@code dataProjection: 'EPSG:4326'} and
     * {@code featureProjection: 'EPSG:3857'}). A position that is null or has fewer than two numbers is left out.
     *
     * @param geometry the GeoJSON geometry, which is not changed
     * @return the geometry, or null when there is none
     */
    static Geom fromGeoJson(GeometryVo geometry) {
        return switch (geometry) {
            case null -> null;
            case PointVo point -> {
                double[] c = point.getCoordinates();
                yield position(c) ? new Point(x3857(c[0]), y3857(c[1])) : null;
            }
            case MultiPointVo multiPoint -> new MultiPoint(flat(multiPoint.getCoordinates()));
            case LineStringVo line -> new LineString(flat(line.getCoordinates()));
            case MultiLineStringVo lines -> {
                FlatBuilder b = new FlatBuilder();
                List<Integer> ends = new ArrayList<>();
                for (double[][] line : nonNull(lines.getCoordinates())) {
                    b.addAll(line);
                    ends.add(b.size());
                }
                yield new MultiLineString(b.toArray(), toInts(ends));
            }
            case PolygonVo polygon -> {
                FlatBuilder b = new FlatBuilder();
                int[] ends = rings(b, polygon.getCoordinates());
                yield new Polygon(b.toArray(), ends);
            }
            case MultiPolygonVo polygons -> {
                FlatBuilder b = new FlatBuilder();
                List<int[]> endss = new ArrayList<>();
                for (double[][][] polygon : nonNull(polygons.getCoordinates())) {
                    endss.add(rings(b, polygon));
                }
                yield new MultiPolygon(b.toArray(), endss.toArray(int[][]::new));
            }
            case GeometryCollectionVo collection -> {
                List<Geom> members = new ArrayList<>();
                for (GeometryVo member : nonNull(collection.getGeometries())) {
                    Geom geom = fromGeoJson(member);
                    if (geom != null) {
                        members.add(geom);
                    }
                }
                yield new GeometryCollection(members);
            }
            default -> null;
        };
    }

    /** Appends the rings to the builder and returns the end of each */
    private static int[] rings(FlatBuilder b, double[][][] rings) {
        List<Integer> ends = new ArrayList<>();
        for (double[][] ring : nonNull(rings)) {
            b.addAll(ring);
            ends.add(b.size());
        }
        return toInts(ends);
    }

    private static double[] flat(double[][] positions) {
        FlatBuilder b = new FlatBuilder();
        b.addAll(positions);
        return b.toArray();
    }

    private static boolean position(double[] c) {
        return c != null && c.length >= 2;
    }

    @SafeVarargs
    private static <T> List<T> nonNull(T... values) {
        return values == null ? List.of() : Arrays.stream(values).filter(v -> v != null).toList();
    }

    private static int[] toInts(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    /** {@code fromEPSG4326} of {@code ol/proj/epsg3857}: the easting of a longitude */
    static double x3857(double lon) {
        return (HALF_SIZE * lon) / 180;
    }

    /** {@code fromEPSG4326} of {@code ol/proj/epsg3857}: the northing of a latitude, clamped at the poles */
    static double y3857(double lat) {
        double y = RADIUS * StrictMath.log(StrictMath.tan((Math.PI * (lat + 90)) / 360));
        if (y > MAX_SAFE_Y) {
            y = MAX_SAFE_Y;
        } else if (y < -MAX_SAFE_Y) {
            y = -MAX_SAFE_Y;
        }
        return y;
    }

    // ------------------------------------------------------------------------------------------- //
    // Extents (ol/extent)
    // ------------------------------------------------------------------------------------------- //

    /** An empty extent, {@code createEmpty} of {@code ol/extent} */
    static double[] emptyExtent() {
        return new double[] { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
    }

    /** The extent of a geometry: minimum x, minimum y, maximum x, maximum y; empty when it has no coordinates */
    static double[] extent(Geom geom) {
        double[] extent = emptyExtent();
        extend(extent, geom);
        return extent;
    }

    private static void extend(double[] extent, Geom geom) {
        switch (geom) {
            case Point p -> extendXY(extent, p.x(), p.y());
            case MultiPoint g -> extendFlat(extent, g.flat(), 0, g.flat().length);
            case LineString g -> extendFlat(extent, g.flat(), 0, g.flat().length);
            case MultiLineString g -> extendFlat(extent, g.flat(), 0, g.flat().length);
            case Polygon g -> extendFlat(extent, g.flat(), 0, g.flat().length);
            case MultiPolygon g -> extendFlat(extent, g.flat(), 0, g.flat().length);
            case GeometryCollection g -> g.geometries().forEach(member -> extend(extent, member));
        }
    }

    private static void extendFlat(double[] extent, double[] flat, int offset, int end) {
        for (int i = offset; i < end; i += 2) {
            extendXY(extent, flat[i], flat[i + 1]);
        }
    }

    private static void extendXY(double[] extent, double x, double y) {
        extent[0] = Math.min(extent[0], x);
        extent[1] = Math.min(extent[1], y);
        extent[2] = Math.max(extent[2], x);
        extent[3] = Math.max(extent[3], y);
    }

    /** {@code getCenter} of {@code ol/extent} */
    static Point centre(double[] extent) {
        return new Point((extent[0] + extent[2]) / 2, (extent[1] + extent[3]) / 2);
    }

    /** {@code intersects} of {@code ol/extent}, which is also how the vector source finds the features in view */
    static boolean intersects(double[] a, double[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }

    /** {@code containsXY} of {@code ol/extent} */
    static boolean containsXY(double[] extent, double x, double y) {
        return extent[0] <= x && x <= extent[2] && extent[1] <= y && y <= extent[3];
    }

    /** {@code buffer} of {@code ol/extent} */
    static double[] buffer(double[] extent, double value) {
        return new double[] { extent[0] - value, extent[1] - value, extent[2] + value, extent[3] + value };
    }

    // ------------------------------------------------------------------------------------------- //
    // Lengths and positions along a line (ol/geom/flat/length, ol/geom/flat/interpolate)
    // ------------------------------------------------------------------------------------------- //

    /** {@code lineStringLength} of {@code ol/geom/flat/length} */
    static double lineStringLength(double[] flat, int offset, int end) {
        double x1 = flat[offset];
        double y1 = flat[offset + 1];
        double length = 0;
        for (int i = offset + 2; i < end; i += 2) {
            double x2 = flat[i];
            double y2 = flat[i + 1];
            length += Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
            x1 = x2;
            y1 = y2;
        }
        return length;
    }

    /**
     * {@code interpolatePoint} of {@code ol/geom/flat/interpolate}, as {@code LineString.getCoordinateAt} calls
     * it: the point at a fraction of the line's length, by linear interpolation within the segment it falls in.
     *
     * @return x and y; NaN for a line without coordinates
     */
    static double[] coordinateAt(double[] flat, int offset, int end, double fraction) {
        int o = -1;
        boolean interpolate = false;
        double t = 0;
        int n = (end - offset) / 2;
        if (n == 1) {
            o = offset;
        } else if (n == 2) {
            o = offset;
            t = fraction;
            interpolate = true;
        } else if (n != 0) {
            double x1 = flat[offset];
            double y1 = flat[offset + 1];
            double length = 0;
            double[] cumulativeLengths = new double[n];
            int count = 1;
            for (int i = offset + 2; i < end; i += 2) {
                double x2 = flat[i];
                double y2 = flat[i + 1];
                length += Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
                cumulativeLengths[count++] = length;
                x1 = x2;
                y1 = y2;
            }
            double target = fraction * length;
            int index = binarySearch(cumulativeLengths, target);
            if (index < 0) {
                if (-index - 2 < 0 || -index - 1 >= n) {
                    // Before the start or beyond the end: OpenLayers reads past its array and yields NaN
                    return new double[] { Double.NaN, Double.NaN };
                }
                t = (target - cumulativeLengths[-index - 2])
                        / (cumulativeLengths[-index - 1] - cumulativeLengths[-index - 2]);
                o = offset + (-index - 2) * 2;
                interpolate = true;
            } else {
                o = offset + index * 2;
            }
        }
        double[] dest = new double[2];
        for (int i = 0; i < 2; ++i) {
            dest[i] = o < 0
                    ? Double.NaN
                    : interpolate ? lerp(flat[o + i], flat[o + 2 + i], t) : flat[o + i];
        }
        return dest;
    }

    /** {@code lerp} of {@code ol/math} */
    private static double lerp(double a, double b, double x) {
        return a + x * (b - a);
    }

    /**
     * {@code binarySearch} of {@code ol/array} with its {@code ascending} comparator: the index of the needle, or
     * {@code ~insertionPoint} when it is not in the array
     */
    private static int binarySearch(double[] haystack, double needle) {
        int low = 0;
        int high = haystack.length;
        boolean found = false;
        while (low < high) {
            int mid = low + ((high - low) >> 1);
            int cmp = ascending(haystack[mid], needle);
            if (cmp < 0) {
                low = mid + 1;
            } else {
                high = mid;
                found = cmp == 0;
            }
        }
        return found ? low : ~low;
    }

    /** {@code ascending} of {@code ol/array}: a NaN compares equal to everything */
    private static int ascending(double a, double b) {
        return a > b ? 1 : a < b ? -1 : 0;
    }

    // ------------------------------------------------------------------------------------------- //
    // Interior points (ol/geom/flat/interiorpoint, ol/geom/flat/contains, ol/geom/flat/center)
    // ------------------------------------------------------------------------------------------- //

    /** {@code Polygon.getInteriorPoint}: the tag position of a polygon */
    static Point interiorPoint(Polygon polygon) {
        Point centre = centre(extent(polygon));
        double[] point = interiorPointOfArray(orientedFlatCoordinates(polygon), 0, polygon.ends(),
                centre.x(), centre.y());
        return new Point(point[0], point[1]);
    }

    /** {@code MultiPolygon.getInteriorPoints}: one tag position per polygon */
    static MultiPoint interiorPoints(MultiPolygon polygons) {
        double[] flat = polygons.flat();
        int[][] endss = polygons.endss();
        // linearRingss of ol/geom/flat/center: the centre of each polygon's exterior ring's extent
        double[] centres = new double[2 * endss.length];
        int offset = 0;
        for (int i = 0; i < endss.length; i++) {
            int[] ends = endss[i];
            double[] extent = emptyExtent();
            extendFlat(extent, flat, offset, ends.length == 0 ? offset : ends[0]);
            centres[2 * i] = (extent[0] + extent[2]) / 2;
            centres[2 * i + 1] = (extent[1] + extent[3]) / 2;
            offset = ends.length == 0 ? offset : ends[ends.length - 1];
        }
        double[] oriented = orientedFlatCoordinates(polygons);
        double[] points = new double[2 * endss.length];
        offset = 0;
        for (int i = 0; i < endss.length; i++) {
            int[] ends = endss[i];
            double[] point = interiorPointOfArray(oriented, offset, ends, centres[2 * i], centres[2 * i + 1]);
            points[2 * i] = point[0];
            points[2 * i + 1] = point[1];
            offset = ends.length == 0 ? offset : ends[ends.length - 1];
        }
        return new MultiPoint(points);
    }

    /**
     * {@code getInteriorPointOfArray} of {@code ol/geom/flat/interiorpoint}: on the horizontal line through the
     * centre of the extent, the middle of the longest stretch whose middle lies inside the polygon; the extent
     * centre when no stretch does.
     *
     * <p>Kept exactly, including a quirk: the scan of every ring after the first starts at the polygon's first
     * coordinate rather than at the ring's own, so a polygon with holes counts the exterior's crossings again and
     * adds the crossings of the segments that join the rings. The intersections are sorted as OpenLayers sorts
     * them in V8: a TimSort with the {@code ascending} comparator, under which a NaN (a horizontal edge on the
     * scan line) compares equal to everything; Java's {@link List#sort} is the same TimSort, and for fewer than
     * 32 intersections both are the same binary insertion sort.
     *
     * @return x and y of the point
     */
    static double[] interiorPointOfArray(double[] flat, int offset, int[] ends, double centreX, double centreY) {
        double y = centreY;
        List<Double> intersections = new ArrayList<>();
        for (int end : ends) {
            double x1 = flat[end - 2];
            double y1 = flat[end - 2 + 1];
            for (int i = offset; i < end; i += 2) {
                double x2 = flat[i];
                double y2 = flat[i + 1];
                if ((y <= y1 && y2 <= y) || (y1 <= y && y <= y2)) {
                    intersections.add(((y - y1) / (y2 - y1)) * (x2 - x1) + x1);
                }
                x1 = x2;
                y1 = y2;
            }
        }
        double pointX = Double.NaN;
        double maxSegmentLength = Double.NEGATIVE_INFINITY;
        intersections.sort(ASCENDING);
        if (!intersections.isEmpty()) {
            double x1 = intersections.getFirst();
            for (int i = 1; i < intersections.size(); ++i) {
                double x2 = intersections.get(i);
                double segmentLength = Math.abs(x2 - x1);
                if (segmentLength > maxSegmentLength) {
                    double x = (x1 + x2) / 2;
                    if (linearRingsContainsXY(flat, offset, ends, x, y)) {
                        pointX = x;
                        maxSegmentLength = segmentLength;
                    }
                }
                x1 = x2;
            }
        }
        if (Double.isNaN(pointX)) {
            pointX = centreX;
        }
        return new double[] { pointX, y };
    }

    private static final Comparator<Double> ASCENDING = (a, b) -> ascending(a, b);

    /** {@code linearRingsContainsXY} of {@code ol/geom/flat/contains}: inside the exterior and outside every hole */
    static boolean linearRingsContainsXY(double[] flat, int offset, int[] ends, double x, double y) {
        if (ends.length == 0) {
            return false;
        }
        if (!linearRingContainsXY(flat, offset, ends[0], x, y)) {
            return false;
        }
        for (int i = 1; i < ends.length; ++i) {
            if (linearRingContainsXY(flat, ends[i - 1], ends[i], x, y)) {
                return false;
            }
        }
        return true;
    }

    /** {@code linearRingContainsXY} of {@code ol/geom/flat/contains}: a winding-number test */
    static boolean linearRingContainsXY(double[] flat, int offset, int end, double x, double y) {
        int wn = 0;
        double x1 = flat[end - 2];
        double y1 = flat[end - 2 + 1];
        for (; offset < end; offset += 2) {
            double x2 = flat[offset];
            double y2 = flat[offset + 1];
            if (y1 <= y) {
                if (y2 > y && (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1) > 0) {
                    wn++;
                }
            } else if (y2 <= y && (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1) < 0) {
                wn--;
            }
            x1 = x2;
            y1 = y2;
        }
        return wn != 0;
    }

    // ------------------------------------------------------------------------------------------- //
    // Ring orientation (ol/geom/flat/orient, ol/geom/flat/reverse)
    // ------------------------------------------------------------------------------------------- //

    /**
     * {@code Polygon.getOrientedFlatCoordinates}: the coordinates with the exterior ring clockwise and the holes
     * anticlockwise (OpenLayers' left-hand rule), which is the order the web maps stroke the rings in
     *
     * @return the polygon's own array when it is oriented already, otherwise a reoriented copy
     */
    static double[] orientedFlatCoordinates(Polygon polygon) {
        double[] flat = polygon.flat();
        if (linearRingsAreOriented(flat, 0, polygon.ends())) {
            return flat;
        }
        double[] oriented = flat.clone();
        orientLinearRings(oriented, 0, polygon.ends());
        return oriented;
    }

    /** {@code MultiPolygon.getOrientedFlatCoordinates} */
    static double[] orientedFlatCoordinates(MultiPolygon polygons) {
        double[] flat = polygons.flat();
        boolean oriented = true;
        int offset = 0;
        for (int[] ends : polygons.endss()) {
            if (!linearRingsAreOriented(flat, offset, ends)) {
                oriented = false;
                break;
            }
            if (ends.length > 0) {
                offset = ends[ends.length - 1];
            }
        }
        if (oriented) {
            return flat;
        }
        double[] copy = flat.clone();
        offset = 0;
        for (int[] ends : polygons.endss()) {
            offset = orientLinearRings(copy, offset, ends);
        }
        return copy;
    }

    /** {@code linearRingIsClockwise} of {@code ol/geom/flat/orient}: null for a ring without area */
    static Boolean linearRingIsClockwise(double[] flat, int offset, int end) {
        double edge = 0;
        double x1 = flat[end - 2];
        double y1 = flat[end - 2 + 1];
        for (; offset < end; offset += 2) {
            double x2 = flat[offset];
            double y2 = flat[offset + 1];
            edge += (x2 - x1) * (y2 + y1);
            x1 = x2;
            y1 = y2;
        }
        return edge == 0 ? null : edge > 0;
    }

    /** {@code linearRingsAreOriented} of {@code ol/geom/flat/orient} with {@code right} false */
    private static boolean linearRingsAreOriented(double[] flat, int offset, int[] ends) {
        for (int i = 0; i < ends.length; ++i) {
            int end = ends[i];
            Boolean clockwise = linearRingIsClockwise(flat, offset, end);
            if (i == 0) {
                if (!Boolean.TRUE.equals(clockwise)) {
                    return false;
                }
            } else if (Boolean.TRUE.equals(clockwise)) {
                return false;
            }
            offset = end;
        }
        return true;
    }

    /** {@code orientLinearRings} of {@code ol/geom/flat/orient} with {@code right} false; changes the array */
    private static int orientLinearRings(double[] flat, int offset, int[] ends) {
        for (int i = 0; i < ends.length; ++i) {
            int end = ends[i];
            Boolean clockwise = linearRingIsClockwise(flat, offset, end);
            boolean reverse = i == 0 ? !Boolean.TRUE.equals(clockwise) : Boolean.TRUE.equals(clockwise);
            if (reverse) {
                reverseCoordinates(flat, offset, end);
            }
            offset = end;
        }
        return offset;
    }

    /** {@code coordinates} of {@code ol/geom/flat/reverse}: reverses the order of the pairs; changes the array */
    private static void reverseCoordinates(double[] flat, int offset, int end) {
        while (offset < end - 2) {
            for (int i = 0; i < 2; ++i) {
                double tmp = flat[offset + i];
                flat[offset + i] = flat[end - 2 + i];
                flat[end - 2 + i] = tmp;
            }
            offset += 2;
            end -= 2;
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Simplification before drawing (Geometry.simplifyTransformed, ol/geom/flat/simplify)
    // ------------------------------------------------------------------------------------------- //

    /**
     * {@code Geometry.getSimplifiedGeometry}, which the web maps apply to every geometry before they draw it, with
     * a tolerance of half a pixel: a line loses the vertices Douglas-Peucker finds within the tolerance, a polygon
     * is quantised to a grid of the tolerance. The simplified geometry is used only when it has fewer
     * coordinates than the geometry itself.
     *
     * @return the geometry itself, or a simplified copy
     */
    static Geom simplified(Geom geom, double squaredTolerance) {
        if (squaredTolerance < 0) {
            return geom;
        }
        return switch (geom) {
            case Point p -> p;
            case MultiPoint p -> p;
            case LineString line -> {
                double[] out = new double[line.flat().length];
                int n = douglasPeucker(line.flat(), 0, line.flat().length, squaredTolerance, out, 0);
                yield n < line.flat().length ? new LineString(Arrays.copyOf(out, n)) : line;
            }
            case MultiLineString lines -> {
                double[] out = new double[lines.flat().length];
                int[] ends = new int[lines.ends().length];
                int n = 0;
                int offset = 0;
                for (int i = 0; i < ends.length; ++i) {
                    n = douglasPeucker(lines.flat(), offset, lines.ends()[i], squaredTolerance, out, n);
                    ends[i] = n;
                    offset = lines.ends()[i];
                }
                yield n < lines.flat().length ? new MultiLineString(Arrays.copyOf(out, n), ends) : lines;
            }
            case Polygon polygon -> {
                double tolerance = Math.sqrt(squaredTolerance);
                FlatBuilder out = new FlatBuilder();
                int[] ends = quantizeArray(polygon.flat(), 0, polygon.ends(), tolerance, out);
                yield out.size() < polygon.flat().length ? new Polygon(out.toArray(), ends) : polygon;
            }
            case MultiPolygon polygons -> {
                double tolerance = Math.sqrt(squaredTolerance);
                FlatBuilder out = new FlatBuilder();
                int[][] endss = new int[polygons.endss().length][];
                int offset = 0;
                for (int i = 0; i < endss.length; ++i) {
                    int[] ends = polygons.endss()[i];
                    endss[i] = quantizeArray(polygons.flat(), offset, ends, tolerance, out);
                    offset = ends.length == 0 ? offset : ends[ends.length - 1];
                }
                yield out.size() < polygons.flat().length ? new MultiPolygon(out.toArray(), endss) : polygons;
            }
            case GeometryCollection collection -> {
                List<Geom> members = new ArrayList<>();
                boolean changed = false;
                for (Geom member : collection.geometries()) {
                    Geom simplified = simplified(member, squaredTolerance);
                    members.add(simplified);
                    changed |= simplified != member;
                }
                yield changed ? new GeometryCollection(members) : collection;
            }
        };
    }

    /** {@code douglasPeucker} of {@code ol/geom/flat/simplify}; returns the new end in {@code out} */
    static int douglasPeucker(double[] flat, int offset, int end, double squaredTolerance, double[] out,
                              int outOffset) {
        int n = (end - offset) / 2;
        if (n < 3) {
            for (; offset < end; offset += 2) {
                out[outOffset++] = flat[offset];
                out[outOffset++] = flat[offset + 1];
            }
            return outOffset;
        }
        boolean[] markers = new boolean[n];
        markers[0] = true;
        markers[n - 1] = true;
        int[] stack = new int[2 * n];
        int top = 0;
        stack[top++] = offset;
        stack[top++] = end - 2;
        int index = 0;
        while (top > 0) {
            int last = stack[--top];
            int first = stack[--top];
            double maxSquaredDistance = 0;
            double x1 = flat[first];
            double y1 = flat[first + 1];
            double x2 = flat[last];
            double y2 = flat[last + 1];
            for (int i = first + 2; i < last; i += 2) {
                double squaredDistance = squaredSegmentDistance(flat[i], flat[i + 1], x1, y1, x2, y2);
                if (squaredDistance > maxSquaredDistance) {
                    index = i;
                    maxSquaredDistance = squaredDistance;
                }
            }
            if (maxSquaredDistance > squaredTolerance) {
                markers[(index - offset) / 2] = true;
                if (first + 2 < index) {
                    stack[top++] = first;
                    stack[top++] = index;
                }
                if (index + 2 < last) {
                    stack[top++] = index;
                    stack[top++] = last;
                }
            }
        }
        for (int i = 0; i < n; ++i) {
            if (markers[i]) {
                out[outOffset++] = flat[offset + i * 2];
                out[outOffset++] = flat[offset + i * 2 + 1];
            }
        }
        return outOffset;
    }

    /** {@code squaredSegmentDistance} of {@code ol/math} */
    private static double squaredSegmentDistance(double x, double y, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx != 0 || dy != 0) {
            double t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy);
            if (t > 1) {
                x1 = x2;
                y1 = y2;
            } else if (t > 0) {
                x1 += dx * t;
                y1 += dy * t;
            }
        }
        return squaredDistance(x, y, x1, y1);
    }

    /** {@code squaredDistance} of {@code ol/math} */
    private static double squaredDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    /** {@code quantizeArray} of {@code ol/geom/flat/simplify}: quantises each ring; returns the new ends */
    private static int[] quantizeArray(double[] flat, int offset, int[] ends, double tolerance, FlatBuilder out) {
        int[] simplifiedEnds = new int[ends.length];
        for (int i = 0; i < ends.length; ++i) {
            int end = ends[i];
            quantize(flat, offset, end, tolerance, out);
            simplifiedEnds[i] = out.size();
            offset = end;
        }
        return simplifiedEnds;
    }

    /**
     * {@code quantize} of {@code ol/geom/flat/simplify}: snaps every coordinate to the tolerance grid and drops a
     * point that snaps onto its predecessor or lies on the straight line between its neighbours
     */
    private static void quantize(double[] flat, int offset, int end, double tolerance, FlatBuilder out) {
        if (offset == end) {
            return;
        }
        double x1 = snap(flat[offset], tolerance);
        double y1 = snap(flat[offset + 1], tolerance);
        offset += 2;
        out.add(x1, y1);
        double x2;
        double y2;
        do {
            x2 = snap(flat[offset], tolerance);
            y2 = snap(flat[offset + 1], tolerance);
            offset += 2;
            if (offset == end) {
                out.add(x2, y2);
                return;
            }
        } while (x2 == x1 && y2 == y1);
        while (offset < end) {
            double x3 = snap(flat[offset], tolerance);
            double y3 = snap(flat[offset + 1], tolerance);
            offset += 2;
            if (x3 == x2 && y3 == y2) {
                continue;
            }
            double dx1 = x2 - x1;
            double dy1 = y2 - y1;
            double dx2 = x3 - x1;
            double dy2 = y3 - y1;
            // P2 lies on the straight line from P1 to P3 and P3 is further along it
            if (dx1 * dy2 == dy1 * dx2
                    && ((dx1 < 0 && dx2 < dx1) || dx1 == dx2 || (dx1 > 0 && dx2 > dx1))
                    && ((dy1 < 0 && dy2 < dy1) || dy1 == dy2 || (dy1 > 0 && dy2 > dy1))) {
                x2 = x3;
                y2 = y3;
                continue;
            }
            out.add(x2, y2);
            x1 = x2;
            y1 = y2;
            x2 = x3;
            y2 = y3;
        }
        out.add(x2, y2);
    }

    /** {@code snap} of {@code ol/geom/flat/simplify}: the nearest multiple of the tolerance */
    static double snap(double value, double tolerance) {
        return tolerance * jsRound(value / tolerance);
    }

    /** JavaScript's {@code Math.round}: the nearest integer, a tie rounding up */
    static double jsRound(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) >= 0x1p52) {
            return value;
        }
        return Math.round(value);
    }

    /** A growable array of x, y pairs */
    static final class FlatBuilder {
        private double[] values = new double[32];
        private int size;

        void add(double x, double y) {
            if (size + 2 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = x;
            values[size++] = y;
        }

        /** Adds the positions projected to EPSG:3857, leaving out any that is not a position */
        void addAll(double[][] positions) {
            if (positions == null) {
                return;
            }
            for (double[] c : positions) {
                if (position(c)) {
                    add(x3857(c[0]), y3857(c[1]));
                }
            }
        }

        int size() {
            return size;
        }

        double get(int i) {
            return values[i];
        }

        void set(int i, double value) {
            values[i] = value;
        }

        double[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
