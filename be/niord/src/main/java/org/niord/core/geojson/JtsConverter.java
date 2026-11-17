/*
 * Copyright 2016 Danish Maritime Authority.
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

package org.niord.core.geojson;

import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.niord.model.geojson.*;

/**
 * Utility functions for converting between the Niord and JTS GeoJSON representations
 */
@SuppressWarnings("unused")
public class JtsConverter {

    final static GeometryFactory factory = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));

    private JtsConverter() {
    }

    /**
     * Parses WKT into a JTS geometry
     * @param wkt the well-known text
     * @return the corresponding JTS geometry
     */
    public static Geometry wktToJts(String wkt) throws ParseException {
        if (StringUtils.isNotBlank(wkt)) {
            return new WKTReader().read(wkt);
        }
        return null;
    }

    /**
     * Converts a lat-lon position to the corresponding JTS geometry
     * @param lat the latitude
     * @param lon the longitude
     * @return the corresponding JTS geometry
     */
    public static Geometry toJtsPoint(double lat, double lon) {
        return factory.createPoint(toJtsCoords(new double[]{lon, lat}));
    }

    /**
     * Converts two lat-lon corner positions to the corresponding JTS geometry
     * @param minLat the minimum latitude
     * @param minLon the minimum longitude
     * @param maxLat the maximum latitude
     * @param maxLon the maximum longitude
     * @return the corresponding JTS geometry
     */
    public static Geometry toJtsExtent(Double minLat, Double minLon, Double maxLat, Double maxLon) {

        if (minLat != null || minLon != null || maxLat != null || maxLon != null) {
            minLat = minLat != null ? minLat : -90.0;
            minLon = minLon != null ? minLon : -180.0;
            maxLat = maxLat != null ? maxLat : 90.0;
            maxLon = maxLon != null ? maxLon : 180.0;

            double[][][] coords = {{
                    {minLon, minLat},
                    {minLon, maxLat},
                    {maxLon, maxLat},
                    {maxLon, minLat},
                    {minLon, minLat}
            }};
            return toJtsPolygon(coords);
        }
        return null;
    }

    /**
     * Converts a GeoJson geometry to the corresponding JTS geometry
     * @param g the GeoJson to convert
     * @return the corresponding JTS geometry
     */
    public static Geometry toJts(GeometryVo g) {
        return switch (g) {
            case null -> null;
            case PointVo p -> factory.createPoint(toJtsCoords(p.getCoordinates()));
            case LineStringVo ls -> factory.createLineString(toJtsCoords(ls.getCoordinates()));
            case PolygonVo pol -> toJtsPolygon(pol.getCoordinates());
            case MultiPointVo mp -> factory.createMultiPointFromCoords(toJtsCoords(mp.getCoordinates()));
            case MultiLineStringVo mls -> toJtsMultiLineString(mls);
            case MultiPolygonVo mpol -> toJtsMultiPolygon(mpol);
            case GeometryCollectionVo gc -> toJtsGeometryCollection(gc);
            default -> throw new UnsupportedOperationException();
        };
    }

    /**
     * Converts a GeoJson geometry that is to be stored to the corresponding JTS geometry, on the globe.
     *
     * <p>A geometry whose longitudes lie beyond 180 degrees east or west (drawn on a wrapped world copy of a map,
     * or read from a file or an old row) is moved by whole turns of 360 degrees, the whole geometry by the same
     * turns, so that the middle of its longitude extent lies in [-180, 180). It stays the same place on the globe,
     * and a geometry that crosses the antimeridian keeps its shape (it is not cut, and its points are not moved
     * one by one). A geometry whose longitudes all lie within [-180, 180] is left as it is.
     *
     * <p>A latitude beyond 90 degrees north or south is no place on the globe, and is refused, as is a coordinate
     * that is not a finite number and a geometry wider than the globe (its longitudes spanning more than 360
     * degrees), which no turn can bring onto it.
     *
     * <p>Used where an entity is built from a value object to be stored; {@link #toJts(GeometryVo)} is left as it
     * is for search geometries.
     *
     * @param g the GeoJson to convert
     * @return the corresponding JTS geometry, on the globe
     * @throws IllegalArgumentException for a latitude beyond 90, a coordinate that is not a finite number or a
     *         geometry wider than the globe
     */
    public static Geometry toJtsOnGlobe(GeometryVo g) {
        Geometry geometry = toJts(g);
        if (geometry == null || geometry.isEmpty()) {
            return geometry;
        }

        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (Coordinate c : geometry.getCoordinates()) {
            if (!Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                throw new IllegalArgumentException("Invalid position: latitude " + c.y + ", longitude " + c.x
                        + " is not a number");
            }
            if (c.y < -90.0 || c.y > 90.0) {
                throw new IllegalArgumentException("Invalid latitude " + c.y + ": a latitude lies between -90 and 90");
            }
            minLon = Math.min(minLon, c.x);
            maxLon = Math.max(maxLon, c.x);
        }

        if (minLon >= -180.0 && maxLon <= 180.0) {
            return geometry;
        }
        if (maxLon - minLon > 360.0) {
            throw new IllegalArgumentException("Invalid geometry: its longitudes span " + (maxLon - minLon)
                    + " degrees, more than the globe");
        }

        double turns = wholeTurnsOffGlobe(minLon, maxLon);
        if (turns != 0) {
            double shift = -turns * 360.0;
            geometry.apply(new CoordinateSequenceFilter() {
                @Override
                public void filter(CoordinateSequence seq, int i) {
                    seq.setOrdinate(i, CoordinateSequence.X, seq.getOrdinate(i, CoordinateSequence.X) + shift);
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public boolean isGeometryChanged() {
                    return true;
                }
            });
        }
        return geometry;
    }

    /**
     * How many whole turns of 360 degrees the middle of a longitude extent lies east (positive) or west (negative)
     * of [-180, 180). A middle on 180 itself counts one turn east, as the admin map editor counts it.
     */
    static double wholeTurnsOffGlobe(double minLon, double maxLon) {
        double middle = (minLon + maxLon) / 2.0;
        return Math.floor((middle + 180.0) / 360.0);
    }

    /**
     * Converts a JTS geometry to the corresponding GeoJson geometry
     * @param g the JTS to convert
     * @return the corresponding GeoJson geometry
     */
    public static GeometryVo fromJts(Geometry g) {
        if (g == null) {
            return null;
        }

        Class<? extends Geometry> c = g.getClass();
        if (c.equals(Point.class)) {
            return new PointVo(fromJtsCoords(g.getCoordinate()));
        } else if (c.equals(LineString.class)) {
            return new LineStringVo(fromJtsCoords(g.getCoordinates()));
        } else if (c.equals(Polygon.class)) {
            return fromJtsPolygon((Polygon) g);
        } else if (c.equals(MultiPoint.class)) {
            return new MultiPointVo(fromJtsCoords(g.getCoordinates()));
        } else if (c.equals(MultiLineString.class)) {
            return fromJtsMultiLineString((MultiLineString) g);
        } else if (c.equals(MultiPolygon.class)) {
            return fromJtsMultiPolygon((MultiPolygon) g);
        } else if (c.equals(GeometryCollection.class)) {
            return fromJtsGeometryCollection((GeometryCollection) g);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private static Coordinate toJtsCoords(double[] coords) {
        return new Coordinate(coords[0], coords[1]);
    }

    private static Coordinate[] toJtsCoords(double[][] coords) {
        Coordinate[] coordinates = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            coordinates[i] = toJtsCoords(coords[i]);
        }
        return coordinates;
    }

    private static Polygon toJtsPolygon(double[][][] coordinates) {
        LinearRing outerRing = factory.createLinearRing(toJtsCoords(coordinates[0]));

        if (coordinates.length > 1) {
            int size = coordinates.length - 1;
            LinearRing[] innerRings = new LinearRing[size];
            for (int i = 0; i < size; i++) {
                innerRings[i] = factory.createLinearRing(toJtsCoords(coordinates[i + 1]));
            }
            return factory.createPolygon(outerRing, innerRings);
        } else {
            return factory.createPolygon(outerRing);
        }
    }

    private static MultiLineString toJtsMultiLineString(MultiLineStringVo multiLineString) {
        int size = multiLineString.getCoordinates().length;
        LineString[] lineStrings = new LineString[size];
        for (int i = 0; i < size; i++) {
            lineStrings[i] = factory.createLineString(toJtsCoords(multiLineString.getCoordinates()[i]));
        }
        return factory.createMultiLineString(lineStrings);
    }

    private static MultiPolygon toJtsMultiPolygon(MultiPolygonVo multiPolygon) {
        int size = multiPolygon.getCoordinates().length;
        Polygon[] polygons = new Polygon[size];
        for (int i = 0; i < size; i++) {
            polygons[i] = toJtsPolygon(multiPolygon.getCoordinates()[i]);
        }
        return factory.createMultiPolygon(polygons);
    }

    private static GeometryCollection toJtsGeometryCollection(GeometryCollectionVo gc) {
        int size = gc.getGeometries().length;
        Geometry[] geometries = new Geometry[size];
        for (int i = 0; i < size; i++) {
            geometries[i] = toJts(gc.getGeometries()[i]);
        }
        return factory.createGeometryCollection(geometries);
    }

    private static double[] fromJtsCoords(Coordinate coordinate) {
        return new double[] { coordinate.x, coordinate.y };
    }

    private static double[][] fromJtsCoords(Coordinate[] coordinates) {
        double[][] array = new double[coordinates.length][];
        for (int i = 0; i < coordinates.length; i++) {
            array[i] = fromJtsCoords(coordinates[i]);
        }
        return array;
    }

    private static PolygonVo fromJtsPolygon(Polygon polygon) {
        int size = polygon.getNumInteriorRing() + 1;
        double[][][] rings = new double[size][][];
        rings[0] = fromJtsCoords(polygon.getExteriorRing().getCoordinates());
        for (int i = 0; i < size - 1; i++) {
            rings[i + 1] = fromJtsCoords(polygon.getInteriorRingN(i).getCoordinates());
        }
        return new PolygonVo(rings);
    }

    private static MultiLineStringVo fromJtsMultiLineString(MultiLineString multiLineString) {
        int size = multiLineString.getNumGeometries();
        double[][][] lineStrings = new double[size][][];
        for (int i = 0; i < size; i++) {
            lineStrings[i] = fromJtsCoords(multiLineString.getGeometryN(i).getCoordinates());
        }
        return new MultiLineStringVo(lineStrings);
    }

    private static MultiPolygonVo fromJtsMultiPolygon(MultiPolygon multiPolygon) {
        int size = multiPolygon.getNumGeometries();
        double[][][][] polygons = new double[size][][][];
        for (int i = 0; i < size; i++) {
            polygons[i] = fromJtsPolygon((Polygon) multiPolygon.getGeometryN(i)).getCoordinates();
        }
        return new MultiPolygonVo(polygons);
    }

    private static GeometryCollectionVo fromJtsGeometryCollection(GeometryCollection gc) {
        int size = gc.getNumGeometries();
        GeometryVo[] geometries = new GeometryVo[size];
        for (int i = 0; i < size; i++) {
            geometries[i] = fromJts(gc.getGeometryN(i));
        }
        return new GeometryCollectionVo(geometries);
    }

}
