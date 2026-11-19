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

import org.niord.core.geojson.GeoJsonUtils;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.portrayal.PortrayalDefinition.Buffer;
import org.niord.core.portrayal.PortrayalDefinition.SizeSet;
import org.niord.core.portrayal.PortrayalDefinition.StrokeSize;
import org.niord.core.portrayal.PortrayalDefinition.Style;
import org.niord.core.portrayal.PortrayalDefinition.Symbol;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import org.niord.core.util.GlobalMercator;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.PointVo;
import org.niord.web.map.MapImageMark.Pen;
import org.niord.web.map.OpenLayersGeometry.FlatBuilder;
import org.niord.web.map.OpenLayersGeometry.Geom;
import org.niord.web.map.OpenLayersGeometry.GeometryCollection;
import org.niord.web.map.OpenLayersGeometry.LineString;
import org.niord.web.map.OpenLayersGeometry.MultiLineString;
import org.niord.web.map.OpenLayersGeometry.MultiPoint;
import org.niord.web.map.OpenLayersGeometry.MultiPolygon;
import org.niord.web.map.OpenLayersGeometry.Point;
import org.niord.web.map.OpenLayersGeometry.Polygon;
import org.niord.web.map.OpenLayersReplay.ImageSymbol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plans a message's map image: which part of the map it shows, and the marks it draws there, as a draw list that
 * {@link MapImagePainter} paints onto the background. It reads nothing but its arguments and changes none of them.
 *
 * <p>The marks are the web maps' portrayal of the message, drawn from the same definition
 * ({@link PortrayalDefinition}): {@code fe/shared-map/map-styles.ts} chooses the styles of a feature and
 * OpenLayers draws them, and this class ports both - {@link #plan} follows {@code styleForMessageFeature} and its
 * geometry functions, {@link OpenLayersReplay} the canvas replay. What the map image leaves out is what only a
 * web map has: the selection and hover halos, the co-location count badge, the coordinate pins and all text.
 */
public final class MapImagePlanner {

    /** The highest zoom level a map image of more than one position is shown at */
    static final int MAX_ZOOM = 12;

    /** The lowest zoom level a map image is shown at, however large its geometry */
    static final int MIN_ZOOM = 3;

    /** The share of the image's width and height the geometry may fill at the chosen zoom level */
    static final double FILL = 0.8;

    // The feature properties the portrayal reads, as map-styles.ts names them
    static final String PARENT_FEATURE_IDS_KEY = "parentFeatureIds";
    static final String APPROXIMATE_KEY = "approximate";

    private static final GlobalMercator MERCATOR = new GlobalMercator();

    private MapImagePlanner() {
    }

    /**
     * Returns the part of the map a message's image shows. It is centred on the average of the centres of the
     * feature collections ({@link GeoJsonUtils#computeCenter}). A message whose geometry is one point is shown at
     * the given zoom level; any other at the highest zoom level from {@value #MAX_ZOOM} down to {@value #MIN_ZOOM}
     * at which the bounding box of its geometry fills at most {@value #FILL} of the image's width and height,
     * measured in EPSG:3857 as the map draws it.
     *
     * @param fcs             the message's geometry, which is not changed
     * @param imageSize       the width and height of the image in pixels
     * @param singlePointZoom the zoom level of a message whose geometry is one point
     * @return the window, or null when the geometry has no position at all
     */
    public static TileWindow frame(FeatureCollectionVo[] fcs, int imageSize, int singlePointZoom) {
        if (fcs == null || fcs.length == 0 || !hasPosition(fcs)) {
            return null;
        }
        double[] bbox = GeoJsonUtils.computeBBox(fcs);
        double[] centre = GeoJsonUtils.computeCenter(fcs);

        boolean singlePoint = fcs.length == 1 && fcs[0].getFeatures() != null && fcs[0].getFeatures().length == 1
                && fcs[0].getFeatures()[0].getGeometry() instanceof PointVo;
        int maxWidthHeight = (int) (imageSize * FILL);
        int zoom = singlePoint ? singlePointZoom : zoomToFit(bbox, maxWidthHeight);

        return TileWindow.centredOn(
                OpenLayersGeometry.x3857(centre[0]), OpenLayersGeometry.y3857(centre[1]), zoom, imageSize, imageSize);
    }

    /** Whether the geometry has at least one position */
    private static boolean hasPosition(FeatureCollectionVo[] fcs) {
        AtomicBoolean found = new AtomicBoolean();
        for (FeatureCollectionVo fc : fcs) {
            if (fc != null) {
                fc.visitCoordinates(c -> found.compareAndSet(false, c != null && c.length >= 2));
            }
        }
        return found.get();
    }

    /** The highest zoom level at which the bounding box fits a square of the given size */
    private static int zoomToFit(double[] bbox, int maxWidthHeight) {
        double west = OpenLayersGeometry.x3857(bbox[0]);
        double south = OpenLayersGeometry.y3857(bbox[1]);
        double east = OpenLayersGeometry.x3857(bbox[2]);
        double north = OpenLayersGeometry.y3857(bbox[3]);
        for (int zoom = MAX_ZOOM; zoom > MIN_ZOOM; zoom--) {
            double resolution = MERCATOR.Resolution(zoom);
            if ((east - west) / resolution <= maxWidthHeight && (north - south) / resolution <= maxWidthHeight) {
                return zoom;
            }
        }
        return MIN_ZOOM;
    }

    /**
     * Returns the marks of a message's map image in the order they are painted.
     *
     * <p>The style is the one the definition selects for the message's main type and type. Per feature, in the
     * order of the feature collections, as {@code styleForMessageFeature} of {@code map-styles.ts} does:
     * <ul>
     *   <li>a feature with a {@value #PARENT_FEATURE_IDS_KEY} property is a buffer area around another feature and
     *       gets the definition's buffer fill and pen, and nothing else;</li>
     *   <li>a point or multi-point gets the style's point symbol at every position;</li>
     *   <li>a line or multi-line gets the style's pen and its tag along each line, at half the tag spacing from
     *       the start and then every tag spacing, or once at its middle when the line is shorter;</li>
     *   <li>a polygon gets the pen on its outline and no fill, and the tag at its interior point; a multi-polygon
     *       a tag per polygon;</li>
     *   <li>anything else (a geometry collection) gets the pen on its lines and outlines, one tag at the centre of
     *       its extent, and the style's point symbol at every position of its points;</li>
     *   <li>a feature whose {@value #APPROXIMATE_KEY} property is {@code true} also gets the approximate-position
     *       adornment: at every position of a point, at the tag position of anything else.</li>
     * </ul>
     *
     * @param fcs         the message's geometry, which is not changed
     * @param mainType    the message's main type, or null
     * @param messageType the message's type, or null
     * @param portrayal   the portrayal definition, which must be available
     * @param sizes       the size set to draw with, in the image's pixels
     * @param window      the part of the map the image shows
     * @return the marks, possibly none
     */
    public static List<MapImageMark> plan(FeatureCollectionVo[] fcs, String mainType, String messageType,
                                          PortrayalDefinition portrayal, SizeSet sizes, TileWindow window) {
        OpenLayersReplay replay = new OpenLayersReplay(window);
        if (fcs == null) {
            return replay.finish();
        }
        Style style = portrayal.styleFor(mainType, messageType);
        Pen line = pen(portrayal, style.stroke(), sizes.line());
        ImageSymbol point = symbol(portrayal, sizes, style.point());
        ImageSymbol tag = symbol(portrayal, sizes, style.tag());
        ImageSymbol approx = symbol(portrayal, sizes, style.approx());
        Buffer buffer = portrayal.buffer();
        Pen bufferPen = pen(portrayal, buffer.stroke(), sizes.buffer());
        // The tag spacing is a length on the image; along a line it is measured in map units
        double spacing = sizes.tagSpacing() * window.resolution();
        double[] maxExtent = replay.maxExtent();

        for (FeatureCollectionVo fc : fcs) {
            if (fc == null || fc.getFeatures() == null) {
                continue;
            }
            for (FeatureVo feature : fc.getFeatures()) {
                Geom geometry = feature == null ? null : OpenLayersGeometry.fromGeoJson(feature.getGeometry());
                // The vector source hands the renderer only the features whose extent meets the view
                if (geometry == null || !OpenLayersGeometry.intersects(OpenLayersGeometry.extent(geometry), maxExtent)) {
                    continue;
                }
                Map<String, Object> properties = feature.getProperties() != null ? feature.getProperties() : Map.of();

                if (properties.containsKey(PARENT_FEATURE_IDS_KEY)) {
                    replay.render(geometry, portrayal.colour(buffer.fill()), bufferPen, null);
                    continue;
                }

                boolean pointGeometry = geometry instanceof Point || geometry instanceof MultiPoint;
                if (pointGeometry) {
                    replay.render(geometry, null, null, point);
                } else if (geometry instanceof LineString || geometry instanceof MultiLineString) {
                    replay.render(geometry, null, line, null);
                    replay.render(alongLine(geometry, spacing), null, null, tag);
                } else {
                    replay.render(geometry, null, line, null);
                    replay.render(interiorGeometry(geometry), null, null, tag);
                    if (geometry instanceof GeometryCollection) {
                        // A symbol with no geometry of its own: the replay draws it on the points of the collection
                        replay.render(geometry, null, null, point);
                    }
                }

                if (Boolean.TRUE.equals(properties.get(APPROXIMATE_KEY))) {
                    replay.render(pointGeometry ? geometry : interiorGeometry(geometry), null, null, approx);
                }
            }
        }
        return replay.finish();
    }

    /** A pen of the definition: a named colour, a stroke size of the size set and the definition's line style */
    private static Pen pen(PortrayalDefinition portrayal, String colour, StrokeSize size) {
        return new Pen(portrayal.colour(colour), size.width(), size.dash(), portrayal.lineStyle());
    }

    /** A symbol at its size in the size set, its anchor in pixels */
    private static ImageSymbol symbol(PortrayalDefinition portrayal, SizeSet sizes, String symbolId) {
        Symbol symbol = portrayal.symbol(symbolId);
        SymbolSize size = sizes.symbol(symbolId);
        return new ImageSymbol(symbolId, symbol.anchorX() * size.width(), symbol.anchorY() * size.height(),
                size.width(), size.height());
    }

    /**
     * {@code interiorGeometry} of {@code map-styles.ts}: the tag position of an area - a polygon's interior point,
     * a multi-polygon's interior point per polygon - and the centre of the extent of anything else
     */
    static Geom interiorGeometry(Geom geometry) {
        return switch (geometry) {
            case Polygon polygon -> OpenLayersGeometry.interiorPoint(polygon);
            case MultiPolygon polygons -> OpenLayersGeometry.interiorPoints(polygons);
            default -> OpenLayersGeometry.centre(OpenLayersGeometry.extent(geometry));
        };
    }

    /**
     * {@code alongLineMultiPoint} of {@code map-styles.ts}: per line, positions at half the spacing from its start
     * and then every spacing, measured along the line; a line with none gets one at its middle
     *
     * @param spacing the spacing in map units
     */
    static MultiPoint alongLine(Geom geometry, double spacing) {
        FlatBuilder positions = new FlatBuilder();
        switch (geometry) {
            case LineString line -> sample(line.flat(), 0, line.flat().length, spacing, positions);
            case MultiLineString lines -> {
                int offset = 0;
                for (int end : lines.ends()) {
                    sample(lines.flat(), offset, end, spacing, positions);
                    offset = end;
                }
            }
            default -> {
            }
        }
        return new MultiPoint(positions.toArray());
    }

    private static void sample(double[] flat, int offset, int end, double spacing, FlatBuilder positions) {
        int before = positions.size();
        double total = end - offset >= 2 ? OpenLayersGeometry.lineStringLength(flat, offset, end) : 0;
        if (total > 0 && spacing > 0) {
            for (double d = spacing / 2; d < total; d += spacing) {
                double[] at = OpenLayersGeometry.coordinateAt(flat, offset, end, d / total);
                positions.add(at[0], at[1]);
            }
        }
        if (positions.size() == before) {
            double[] at = OpenLayersGeometry.coordinateAt(flat, offset, end, 0.5);
            positions.add(at[0], at[1]);
        }
    }
}
