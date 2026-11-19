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
import org.niord.core.util.PositionFormatter;
import org.niord.core.util.PositionFormatter.Format;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeoJsonVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Converts a human-writable plain-text geometry specification into
 * a proper GeoJSON representation, and vice versa.
 * <p>
 * When formatting GeoJSON, only the following simple types of geometries are supported:
 * Point, MultiPoint, LineString, Polygon.<br>
 * For polygons, only an exterior ring is supported, and the last coordinate (same as the first) will be omitted.<br>
 * Feature and FeatureCollection are also supported if their geometries are as described above.<br>
 * The actual plain-text format is exemplified below.
 * <p>
 * When parsing GeoJSON, the supported plain text specification is very simple and constitutes lists of point,
 * along with localized names.<br>
 * Example:
 * <pre>
 *   1) 54° 45,7' N 10° 29,1' E, Ærø S.
 *   2) 54° 41,2' N 10° 36,9' E, Keldsnor SW.
 *   3) 54° 38,3' N 10° 40,1' E, Keldsnor S.
 *   4) 54° 41,0' N 10° 48,1' E, Keldsnor SE.
 * </pre>
 * Or:
 * <pre>
 *   54° 41,000'N - 010° 35,000'E
 *   54° 35,566'N - 010° 35,010'E
 *   54° 38,397'N - 010° 25,125'E
 * </pre>
 * Or:
 * <pre>
 *   Point, da:Slukket bøje, en: Unlit buoy
 *   54° 41,000'N - 010° 35,000'E
 *
 *   LineString
 *   54° 35,566'N - 010° 35,010'E, da: kyst, en: shore
 *   54° 38,397'N - 010° 25,125'E
 *   54° 39,111'N - 010° 21,202'E
 * </pre>
 */
public class PlainTextConverter {

    public static final Pattern TYPE_FORMAT = Pattern.compile(
            "^(?<type>Point|MultiPoint|LineString|Polygon)(?<desc>,.+)?$",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern POSITION_FORMAT = Pattern.compile(
            "^(?<lat>[^NS]+)(?<latDir>[NS])([ -])*(?<lon>[^EW]+)(?<lonDir>[EW])(?<desc>.*)$",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern UTM_ZONE_FORMAT = Pattern.compile(
            "^(?<zone>\\d+)(?<latZone>[a-zA-Z])\\s+"
    );

    /** A UTM line as prepareUtmLine leaves it: the zone, a space, the latitude band letter, a space and the rest */
    private static final Pattern UTM_LINE_FORMAT = Pattern.compile(
            "^(?<zone>\\d+) (?<latZone>[a-zA-Z]) (?<rest>.+)$"
    );

    /** The UTM latitude bands, C (from 80° S) to X (to 84° N), without I and O */
    private static final String UTM_BANDS = "CDEFGHJKLMNPQRSTUVWX";

    /** How far past 180° a UTM reading may land and still be 180° (about a metre at the equator, less elsewhere) */
    private static final double UTM_LONGITUDE_ROUNDING = 1e-5;

    private final List<String> languages = new ArrayList<>();

    /**
     * Constructor
     * @param languages the supported languages
     */
    private PlainTextConverter(String[] languages) {
        if (languages == null || languages.length == 0) {
            this.languages.add("en");
        } else {
            this.languages.addAll(Arrays.asList(languages));
        }
    }

    /** Factory method - returns a new plain text converter that supports the given languages **/
    public static PlainTextConverter newInstance(String[] languages) {
        return new PlainTextConverter(languages);
    }

    /***********************************************/
    /** Formatting GeoJSON as plain-text          **/
    /***********************************************/

    /**
     * Converts the GeoJSON to plain text.
     * <p>
     * Only the following simple types of geometries are supported:
     * Point, MultiPoint, LineString, Polygon.<br>
     *
     * For polygons, only an exterior ring is supported, and the last coordinate (same as the first) will be omitted.<br>
     * Feature and FeatureCollection are also supported if their geometries are as described above.<br>
     * The actual plain-text format is exemplified below.
     *
     * @param g the GeoJSON to format as plain text
     * @return the formatted GeoJSON
     */
    public String toPlainText(GeoJsonVo g) throws Exception {
        if (g == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        formatGeoJson(result, g, null);
        return result.toString();
    }

    /**
     * Formats the GeoJSON as plain text
     * @param result the result
     * @param g the GeoJSON to format
     */
    @SuppressWarnings("all")
    private void formatGeoJson(StringBuilder result, GeoJsonVo g, Map<String, Object> properties) throws Exception {
        if (g == null) {
            return;
        } else if (g instanceof PointVo point) {
            if (point.getCoordinates() == null) {
                throw new Exception("Point has no associated coordinates");
            }
            result.append("Point");
            formatFeatureName(result, "name:", properties);
            formatCoordinates(result, g, properties);

        } else if (g instanceof MultiPointVo) {
            MultiPointVo multiPoint = (MultiPointVo)g;
            if (multiPoint.getCoordinates() == null || multiPoint.getCoordinates().length == 0) {
                throw new Exception("MultiPoint has no associated coordinates");
            }
            result.append("MultiPoint");
            formatFeatureName(result, "name:", properties);
            formatCoordinates(result, g, properties);

        } else if (g instanceof LineStringVo) {
            LineStringVo lineString = (LineStringVo)g;
            if (lineString.getCoordinates() == null || lineString.getCoordinates().length == 0) {
                throw new Exception("LineString has no associated coordinates");
            }
            result.append("LineString");
            formatFeatureName(result, "name:", properties);
            formatCoordinates(result, g, properties);

        } else if (g instanceof PolygonVo) {
            PolygonVo polygon = (PolygonVo)g;
            if (polygon.getCoordinates() == null || polygon.getCoordinates().length > 1 ||
                    polygon.getCoordinates()[0].length == 0) {
                throw new Exception("Only polygons with an exterior ring and no interior rings supported");
            }
            result.append("Polygon");
            formatFeatureName(result, "name:", properties);
            formatCoordinates(result, g, properties);

        } else if (g instanceof FeatureVo) {
            FeatureVo feature = (FeatureVo)g;
            if (feature.getGeometry() == null) {
                throw new Exception("Feature has no associated geometry");
            }
            formatGeoJson(result, feature.getGeometry(), feature.getProperties());

        } else if (g instanceof FeatureCollectionVo) {
            FeatureCollectionVo featureCollection = (FeatureCollectionVo)g;
            if (featureCollection.getFeatures() != null) {
                for (FeatureVo feature : featureCollection.getFeatures()) {
                    formatGeoJson(result, feature, null);
                    // Add a blank line between each feature
                    result.append(System.lineSeparator());
                }
            }

        } else {
            throw new Exception("Unsupported GeoJSON type " + g.getClass().getSimpleName());
        }
    }

    /** Formats the coordinates of the geometry **/
    private void formatCoordinates(StringBuilder result, GeoJsonVo g, Map<String, Object> properties) {
        Format format = PositionFormatter.LATLON_DEC_3;
        Locale locale = new Locale(languages.get(0));
        AtomicInteger coordIndex = new AtomicInteger(0);

        // For polygons, only select exterior ring, and omit last coordinate which is identical to the first coordinate
        if (g instanceof PolygonVo) {
            PolygonVo pol = (PolygonVo)g;
            if (pol.getCoordinates() != null && pol.getCoordinates().length >= 1 && pol.getCoordinates()[0].length >= 3) {
                for (int index = 0; index < pol.getCoordinates()[0].length - 1; index++) {
                    double[] xy = pol.getCoordinates()[0][index];
                    result.append(PositionFormatter.format(locale, format, xy[1], xy[0]));
                    formatFeatureName(result, "name:" + coordIndex.getAndIncrement() + ":", properties);
                }
            }
        } else {
            g.visitCoordinates(xy -> {
                result.append(PositionFormatter.format(locale, format, xy[1], xy[0]));
                formatFeatureName(result, "name:" + coordIndex.getAndIncrement() + ":", properties);
            });
        }
    }

    /** Formats the localized feature names **/
    private void formatFeatureName(StringBuilder result, String prefix, Map<String, Object> properties) {
        if (properties != null) {
            for (String lang : languages) {
                Object name = properties.get(prefix + lang);
                if (name != null) {
                    result.append(", ").append(lang).append(": ").append(name);
                }
            }
        }
        result.append(System.lineSeparator());
    }

    /***********************************************/
    /** Parsing plain-text as GeoJSON             **/
    /***********************************************/

    /**
     * Returns the geometry given by the given plain text representation.
     * Return null for empty representations and throws an exception when
     * experiencing parsing errors.
     *
     * @param text the plain text representation
     * @return the resulting GeoJSON feature collection
     */
    public FeatureCollectionVo fromPlainText(String text) throws Exception {

        if (StringUtils.isBlank(text)) {
            return null;
        }
        text = text.trim();

        // Group the text into feature lines
        String[] lines = Arrays.stream(text.split("\n")).map(String::trim).toArray(String[]::new);
        List<List<String>> featureCollectionLines = new ArrayList<>();
        List<String> featureLines = null;
        for (String line : lines) {
            if (StringUtils.isBlank(line)) {
                featureLines = null;
            } else {
                if (featureLines == null) {
                    featureLines = new ArrayList<>();
                    featureCollectionLines.add(featureLines);
                }
                featureLines.add(line);
            }
        }

        // Convert the feature lines into proper features
        List<FeatureVo> features = new ArrayList<>();
        for (List<String> fl : featureCollectionLines) {
            FeatureVo feature = fromPlainText(fl);
            if (feature == null) {
                return null;
            } else {
                features.add(feature);
            }
        }
        if (features.isEmpty()) {
            return null;
        }

        // Assemble the feature collection from the list of features
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(features.toArray(new FeatureVo[features.size()]));

        return fc;
    }

    /** Converts a list of lines into a feature **/
    @SuppressWarnings("all")
    private FeatureVo fromPlainText(List<String> lines) throws Exception {

        Map<String, Object> properties = new HashMap<>();
        List<double[]> coordinates = new ArrayList<>();
        String type = null;
        int offset = 0;

        // Check if the first line designates the geometry type
        Matcher m = TYPE_FORMAT.matcher(lines.get(0));
        if (m.find()) {
            offset = 1;
            type = m.group("type");
            parseFeatureNames(properties, "name:", m.group("desc"));
        }

        // Parse the coordinates
        for (int coordIndex = offset; coordIndex < lines.size(); coordIndex++) {
            String line = lines.get(coordIndex);
            if (!parsePosition(line, coordinates, properties, coordIndex - offset)) {
                throw new Exception("Invalid format: " + line);
            }
        }

        if (coordinates.isEmpty()) {
            return null;
        } else if (type == null && coordinates.size() == 1) {
            type = "Point";
        } else if (type == null && coordinates.size() > 1) {
            type = "MultiPoint";
        }

        FeatureVo feature = new FeatureVo();
        feature.setProperties(properties);

        switch (type.toLowerCase()) {
            case "point" -> {
                if (coordinates.size() != 1) {
                    throw new Exception("Point must have one coordinate");
                }
                PointVo point = new PointVo();
                point.setCoordinates(coordinates.get(0));
                feature.setGeometry(point);
            }
            case "multipoint" -> {
                if (coordinates.isEmpty()) {
                    throw new Exception("MultiPoint must have at least one coordinate");
                }
                MultiPointVo multiPoint = new MultiPointVo();
                multiPoint.setCoordinates(coordinates.toArray(new double[coordinates.size()][]));
                feature.setGeometry(multiPoint);
            }
            case "linestring" -> {
                if (coordinates.size() < 2) {
                    throw new Exception("LineString must have at least two coordinates");
                }
                LineStringVo lineString = new LineStringVo();
                lineString.setCoordinates(coordinates.toArray(new double[coordinates.size()][]));
                feature.setGeometry(lineString);
            }
            case "polygon" -> {
                if (coordinates.size() < 3) {
                    throw new Exception("Polygon must have at least three coordinates");
                }
                // Same first and last coordinate in GeoJSON Polygon linear rings.
                coordinates.add(coordinates.get(0));
                PolygonVo polygon = new PolygonVo();
                double[][][] coords = new double[1][][];
                coords[0] = coordinates.toArray(new double[coordinates.size()][]);
                polygon.setCoordinates(coords);
                feature.setGeometry(polygon);
            }
            default -> throw new Exception("Unknown type " + type);
        }

        return feature;
    }

    /** Parses a single line as a position and optionally the name properties **/
    private boolean parsePosition(String line, List<double[]> coordinates, Map<String, Object> properties, int index) throws Exception {
        line = line.replaceFirst("^\\d+\\)", "").trim();
        Matcher m = POSITION_FORMAT.matcher(line);
        if (m.find()) {
            double[] coord = {
                parseCoordinate(m.group("lon"), m.group("lonDir"), 180),
                parseCoordinate(m.group("lat"), m.group("latDir"), 90)
            };
            coordinates.add(coord);
            parseFeatureNames(properties, "name:"+ index + ":", m.group("desc"));
            return true;
        }
        return false;
    }

    /**
     * Parses the degree (either latitude or longitude).
     * <p>
     * Every position here carries a hemisphere letter, so a sign before the degrees is refused, as are minutes or
     * seconds outside [0, 60), a fraction of a degree followed by minutes and a value beyond {@code maxDegrees}
     * (90 for a latitude, 180 for a longitude): each would otherwise be read as a position the editor never typed.
     *
     * @param deg the degree text
     * @param dir the hemisphere letter
     * @param maxDegrees the largest number of degrees allowed
     */
    private double parseCoordinate(String deg, String dir, double maxDegrees) throws Exception {
        // Check if it include a seconds part
        boolean includesSeconds = deg.contains("\"");

        String[] parts = deg.replace("°", " ")
                .replace("'", " ")
                .replace("\"", " ")
                .replace("I", " ") // When you copy-paste ° from DMA NtM PDF
                .replace("J", " ") // When you copy-paste ' from DMA NtM PDF
                .replaceAll("\\s+", " ")
                .replace(",", ".")
                .split(" ");
        if (parts.length == 0 || (parts.length > 2 && !includesSeconds) || (parts.length > 3 && includesSeconds)
                || parts[0].startsWith("-") || parts[0].startsWith("+")) {
            throw new Exception("Invalid degree format: " + deg);
        }

        try {
            double val = Double.valueOf(parts[0]);
            if (parts.length == 2 || parts.length == 3) {
                if (parts[0].contains(".")) {
                    // A fraction of a degree followed by minutes ("56.5 30N", often a slipped decimal comma) would
                    // add the minutes to the fraction: 57°, a position the editor never typed
                    throw new Exception("Invalid degree format: " + deg);
                }
                double min = Double.valueOf(parts[1]);
                if (!(min >= 0 && min < 60)) {
                    throw new Exception("Invalid degree format: " + deg);
                }
                val += min / 60.0;
            }
            if (parts.length == 3 && includesSeconds) {
                double secs = Double.valueOf(parts[2]);
                if (!(secs >= 0 && secs < 60)) {
                    throw new Exception("Invalid degree format: " + deg);
                }
                val += secs / (60.0 * 60.0);
            }
            if (!(val <= maxDegrees)) {
                // Also refuses NaN
                throw new Exception("Invalid degree format: " + deg);
            }

            if (dir.equalsIgnoreCase("S") || dir.equalsIgnoreCase("W")) {
                val = -val;
            }
            return val;
        } catch (NumberFormatException e) {
            throw new Exception("Invalid degree format: " + deg);
        }
    }

    /**
     * Parses the feature name properties from the description line.
     * <p>
     * For general lines, feature names are defined for all languages of this converter instance.
     * <p>
     * However, if the line contains language indicators ("lang:"), then this is used to split
     * the description line into separate language-specific parts. Example:<br>
     * <pre>, da: Ubåd U-9., en: Submarine U-9.</pre>
     * A language indicator is a word of its own: at the start of the description, or after a comma or a white
     * space. A word that merely ends in a language code and a colon ("Havnen:" with English configured) is part
     * of the name.
     **/
    private void parseFeatureNames(Map<String, Object> properties, String namePrefix, String desc) {
        if (StringUtils.isNotBlank(desc)) {
            desc = desc.replaceFirst("^,?\\s+", "");
            if (StringUtils.isNotBlank(desc)) {

                // Convert languages to regex string like "da:|en:|fr:"
                String langs = languages.stream()
                        .map(l -> l + ":")
                        .collect(Collectors.joining("|"));

                // A marker only at the start, or after a comma or a white space, never at the end of another word
                Pattern LANG_MATCH = Pattern.compile(
                        "(?:^|,|\\s)\\s*(?<lang>" + langs + ")\\s*",
                        Pattern.CASE_INSENSITIVE
                );

                Matcher m = LANG_MATCH.matcher(desc);
                String lang = null;
                int startIndex = 0;
                while (m.find()) {
                    if (lang != null) {
                        properties.put(namePrefix + lang, desc.substring(startIndex, m.start()));
                    }

                    lang = m.group("lang");
                    // Strip colon from language
                    lang = lang.substring(0, lang.length() - 1);
                    startIndex = m.end();
                }

                if (lang != null) {
                    properties.put(namePrefix + lang, desc.substring(startIndex));

                } else {
                    // Add the feature name for all languages
                    for (String l : languages) {
                        properties.put(namePrefix + l, desc);
                    }

                }
            }
        }
    }

    /***********************************************/
    /** Parsing UTM text as GeoJSON               **/
    /***********************************************/

    /**
     * Returns the geometry given by the given UTM text representation.
     * Return null for empty representations and throws an exception when
     * experiencing parsing errors.
     *
     * @param text the UTM text representation
     * @return the resulting GeoJSON feature collection
     */
    public FeatureCollectionVo fromUtmText(String text) throws Exception {

        UTMCoordinateConversion utmConverter = new UTMCoordinateConversion();

        if (StringUtils.isBlank(text)) {
            return null;
        }
        text = text.trim();

        // One position per line; blank lines are skipped, and a line that cannot be read refuses the whole text
        List<String> lines = Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        double[][] coordinates = new double[lines.size()][];
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            double[] latLon;
            try {
                String prepared = prepareUtmLine(line);
                checkUtmZoneAndBand(prepared);
                latLon = utmConverter.utm2LatLon(prepared);
            } catch (Exception e) {
                throw new Exception("Invalid UTM position: " + line, e);
            }
            // A position on the antimeridian, typed to the centimetre in zone 1 or 60, may be read a hair past 180°:
            // that is the conversion's rounding, and the position is 180°
            if (Math.abs(latLon[1]) > 180 && Math.abs(latLon[1]) <= 180 + UTM_LONGITUDE_ROUNDING) {
                latLon[1] = Math.copySign(180, latLon[1]);
            }
            // Only a position on the globe: far from its zone's central meridian, or near a pole, the conversion
            // runs away (a NaN fails both comparisons and is refused too)
            if (!(Math.abs(latLon[0]) <= 90) || !(Math.abs(latLon[1]) <= 180)) {
                throw new Exception("Invalid UTM position: " + line);
            }
            coordinates[i] = new double[]{ latLon[1], latLon[0] };
        }

        if (coordinates.length == 0) {
            return null;
        }

        // Assemble the feature collection from the list of features
        FeatureCollectionVo fc = new FeatureCollectionVo();
        FeatureVo featureVo = new FeatureVo();
        if (coordinates.length == 1) {
            PointVo pt = new PointVo();
            pt.setCoordinates(coordinates[0]);
            featureVo.setGeometry(pt);
        } else {
            MultiPointVo mpt = new MultiPointVo();
            mpt.setCoordinates(coordinates);
            featureVo.setGeometry(mpt);
        }
        fc.setFeatures(new FeatureVo[] { featureVo });

        return fc;
    }

    /**
     * The UTMCoordinateParser assumes a space between zone and lat-zone, i.e.
     * "32V 624726.15 6207884.60" is invalid but "32 V 624726.15 6207884.60" is valid.
     * Fix this problem by inserting a space if needed.
     * A decimal comma between two digits ("624800,00") is read as a decimal point, as the plain-text parser does.
     * The band letter is upper-cased, glued or spaced: the converter knows the southern bands in upper case only,
     * so "32c" would be read in the northern hemisphere.
     * @param line the line to update
     */
    private String prepareUtmLine(String line) {
        line = line.replaceAll("\\s+", " ").replaceAll("(\\d),(\\d)", "$1.$2");
        Matcher m = UTM_ZONE_FORMAT.matcher(line);
        if (m.find()) {
            line = m.group("zone") + " " + m.group("latZone") + " "
                    + line.substring(line.indexOf(" ")).trim();
        }
        m = UTM_LINE_FORMAT.matcher(line);
        if (m.matches()) {
            line = m.group("zone") + " " + m.group("latZone").toUpperCase(Locale.ROOT) + " " + m.group("rest");
        }
        return line;
    }

    /**
     * Refuses a prepared UTM line whose zone is not one of 1 to 60 or whose latitude band letter is not one of C to X
     * (I and O excluded). The converter would read any whole number as a zone (61 at a central meridian of 183°,
     * 0 as zone 31) and any word as a band (a northern one).
     * @param line the line as prepareUtmLine leaves it
     */
    private static void checkUtmZoneAndBand(String line) {
        Matcher m = UTM_LINE_FORMAT.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("A UTM line starts with the zone and the latitude band letter");
        }
        int zone = Integer.parseInt(m.group("zone")); // a number too long for an int is refused as well
        if (zone < 1 || zone > 60) {
            throw new IllegalArgumentException("UTM zones are 1 to 60: " + zone);
        }
        if (UTM_BANDS.indexOf(m.group("latZone").charAt(0)) < 0) {
            throw new IllegalArgumentException("UTM latitude bands are C to X, without I and O: " + m.group("latZone"));
        }
    }

}
