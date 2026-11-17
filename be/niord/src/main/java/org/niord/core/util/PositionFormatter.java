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
package org.niord.core.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class used for formatting positions according to a flexible format template.
 * <p>
 * The format template can contain the following type parts:
 * <ul>
 *     <li>DEG: The degrees of a position</li>
 *     <li>MIN: The minutes of a position</li>
 *     <li>SEC: The seconds of a position</li>
 *     <li>DIR: The direction of a position</li>
 * </ul>
 * The numeric types can be tagged with a "-F" suffix, meaning that a floor version
 * of the value will be used (i.e. rounded down numerically).
 * <p>
 * Additionally, each type can be suffixed with a format specification. For the
 * numeric type, the <i>printf</i> format is used and for the "dir" type, a comma-separated
 * pair is used for positive respectively negative values.
 * <p>
 * Examples:
 * <ul>
 *     <li>MIN: the minutes.</li>
 *     <li>MIN-F: the rounded down minutes.</li>
 *     <li>MIN-F[%02d]: the rounded down minutes prefixed with zeros to ensure two integer digits.</li>
 *     <li>MIN[%06.3f]: the minutes formatted to have two integer digits and three decimal digits.</li>
 *     <li>DIR[N,S]: the direction which emits "N" for positive values and "S" for negative values.</li>
 * </ul>
 *
 * <p>
 * Lat-lon pairs can be formatted using a "latLonFormat" template where all occurrences of "LAT" is replaced
 * with the formatted latitude and all occurrences of "LON" is replaced with the formatted longitude.
 *
 * <p>
 * The specified locale is used to define the decimal separator (period or comma).
 *
 * <p>
 * A value is rounded once, on the smallest unit the template prints, and the rounding is carried into the larger
 * units, so the minutes and seconds printed are always below 60: 56.9994° prints "57° 00.0'", not "56° 60.0'"
 * (see {@link Rounded}). A template prints each unit once. Only the smallest unit it prints may have decimals, and
 * minutes or seconds are printed beside the degrees: a template that prints rounded minutes beside seconds can still
 * print 60 minutes, and one that prints minutes without the degrees loses the carried degree. A degrees part that is
 * not floored (decimal degrees) prints the value itself, rounded by its own format, as it always did.
 *
 */
public class PositionFormatter {

    public static final Format LATLON_SEC = new Format(
            "DEG-F[%02d]\u00B0 MIN-F[%02d]' SEC-F[%02d]\"DIR[N,S]",
            "DEG-F[%03d]\u00B0 MIN-F[%02d]' SEC-F[%02d]\"DIR[E,W]");

    public static final Format LATLON_DEC_3 = new Format(
            "DEG-F[%02d]\u00B0 MIN[%06.3f]'DIR[N,S]",
            "DEG-F[%03d]\u00B0 MIN[%06.3f]'DIR[E,W]");

    public static final Format LATLON_DEC_2 = new Format(
            "DEG-F[%02d]\u00B0 MIN[%05.2f]'DIR[N,S]",
            "DEG-F[%03d]\u00B0 MIN[%05.2f]'DIR[E,W]");

    public static final Format LATLON_DEC_1 = new Format(
            "DEG-F[%02d]\u00B0 MIN[%04.1f]'DIR[N,S]",
            "DEG-F[%03d]\u00B0 MIN[%04.1f]'DIR[E,W]");

    public static final Format LATLON_NAVTEX_3 = new Format(
            "DEG-F[%02d]-MIN[%06.3f]DIR[N,S]",
            "DEG-F[%03d]-MIN[%06.3f]DIR[E,W]");

    public static final Format LATLON_NAVTEX_2 = new Format(
            "DEG-F[%02d]-MIN[%05.2f]DIR[N,S]",
            "DEG-F[%03d]-MIN[%05.2f]DIR[E,W]");

    public static final Format LATLON_NAVTEX_1 = new Format(
            "DEG-F[%02d]-MIN[%04.1f]DIR[N,S]",
            "DEG-F[%03d]-MIN[%04.1f]DIR[E,W]");

    /**
     * Formats the position.
     *
     * @param lat the latitude
     * @param lon the longitude
     * @return the formatted value
     */
    public static String format(double lat, double lon) {
        return format(null, null, null, lat, lon);
    }

    /**
     * Formats the position according to the give format.
     *
     * @param format the position format
     * @param lat the latitude
     * @param lon the longitude
     * @return the formatted value
     */
    public static String format(Format format, double lat, double lon) {
        return format(null, format, null, lat, lon);
    }

    /**
     * Formats the position according to the give format using the default locale.
     *
     * The latLonFormat is used as a template and all occurrences of "LAT" is replaced
     * with the latitude and all occurrences of "LON" is replaced with the longitude.
     *
     * @param format the position format
     * @param latLonFormat the combined lat lon template
     * @param lat the latitude
     * @param lon the longitude
     * @return the formatted value
     */
    public static String format(Format format, String latLonFormat, double lat, double lon) {
        return format(null, format, latLonFormat, lat, lon);
    }

    /**
     * Formats the position according to the give format using the given locale.
     *
     * @param locale the locale
     * @param format the position format
     * @param lat the latitude
     * @param lon the longitude
     * @return the formatted value
     */
    public static String format(Locale locale, Format format, double lat, double lon) {
        return format(locale, format, null, lat, lon);
    }

    /**
     * Formats the position according to the give format using the given locale.
     *
     * The latLonFormat is used as a template and all occurrences of "LAT" is replaced
     * with the latitude and all occurrences of "LON" is replaced with the longitude.
     *
     * @param locale the locale
     * @param format the position format
     * @param latLonFormat the combined lat lon template
     * @param lat the latitude
     * @param lon the longitude
     * @return the formatted value
     * @throws IllegalArgumentException when a latitude or longitude the format prints a number of is not finite
     */
    public static String format(Locale locale, Format format, String latLonFormat, double lat, double lon) {
        // Set defaults
        format = (format == null) ? LATLON_SEC : format;
        latLonFormat = (latLonFormat == null) ? "LAT LON" : latLonFormat;

        String latitude = format(locale, format.getLatFormat(), lat);
        String longitude = format(locale, format.getLonFormat(), lon);

        return latLonFormat.replaceAll("LAT", latitude).replaceAll("LON", longitude);
    }

    /**
     * Formats the value according to the give format using the default locale
     * @param format the format
     * @param value the value to format
     * @return the formatted value
     */
    public static String format(String format, double value) {
        return format(null, format, value);
    }

    /**
     * Formats the value according to the give format using the given locale
     * @param locale the locale
     * @param format the format
     * @param value the value to format
     * @return the formatted value
     * @throws IllegalArgumentException when the format prints a number and the value is not finite
     */
    public static String format(Locale locale, String format, double value) {
        locale = (locale != null) ? locale : Locale.ENGLISH;
        double abs = Math.abs(value);

        // Round once, on the smallest unit the format prints, and carry into the larger units (POS1)
        Rounded rounded = Rounded.of(format, abs);

        Part p;
        while ((p = findPart(format, "DEG")) != null) {
            // Decimal degrees are the value itself, rounded by their own format: not split, so nothing to carry
            format = p.floor
                    ? p.replaceNumber(locale, rounded.degrees, "%02d")
                    : p.replaceNumber(locale, abs, DEFAULT_FORMATS[DEG]);
        }
        while ((p = findPart(format, "MIN")) != null) {
            format = p.floor
                    ? p.replaceNumber(locale, rounded.minutes(), "%02d")
                    : p.replaceNumber(locale, rounded.inUnit(MIN), DEFAULT_FORMATS[MIN]);
        }
        while ((p = findPart(format, "SEC")) != null) {
            format = p.floor
                    ? p.replaceNumber(locale, rounded.seconds(), "%02d")
                    : p.replaceNumber(locale, rounded.inUnit(SEC), DEFAULT_FORMATS[SEC]);
        }
        while ((p = findPart(format, "DIR")) != null) {
            int signIndex = value >= 0.0 ? 0 : 1;
            format = p.replaceIndex(signIndex);
        }
        return format;

    }

    /** The numeric part types, from the largest unit to the smallest, and how many of each make a degree. */
    private static final int DEG = 0, MIN = 1, SEC = 2;
    private static final String[] TYPES = { "DEG", "MIN", "SEC" };
    private static final int[] UNITS_PER_DEGREE = { 1, 60, 3600 };

    /** The printf format of a part that is not floored and gives none. */
    private static final String[] DEFAULT_FORMATS = { "%.2f", "%02f", "%02f" };

    /** The precision of a printf format, e.g. 3 of "%06.3f"; a format without one prints 6 decimals. */
    private static final Pattern PRECISION = Pattern.compile("\\.(\\d+)");

    /**
     * The absolute value of a latitude or longitude, rounded once, on the smallest unit its format prints, with the
     * rounding carried into the larger units. The minutes of 56.9994° are 59.964', which rounded to one decimal are
     * 60.0', so the position is 57° 00.0', not 56° 60.0' (POS1: the degrees used to be floored and the minutes
     * rounded on their own). Each minutes, seconds and floored degrees part of the format prints its share of this
     * one value; a degrees part that is not floored prints the value itself, rounded by its own format, so a decimal
     * degree is rounded once, as before.
     * <p>
     * When every part of the smallest unit is floored, or printed as an integer, the value is floored to whole units,
     * as those parts always were. Otherwise it is rounded half up to the most decimals those parts print, by the same
     * printf rounding the part used before, so every position that printed right prints the same.
     */
    static final class Rounded {
        /** The unit the value was rounded on: DEG, MIN or SEC. */
        final int unit;
        /** The whole degrees, after the carry. */
        final int degrees;
        /** The rest within the degree, in the unit rounded on: at least 0 and less than a degree. */
        final BigDecimal rest;

        private Rounded(int unit, int degrees, BigDecimal rest) {
            this.unit = unit;
            this.degrees = degrees;
            this.rest = rest;
        }

        /**
         * Rounds the absolute value for the given format.
         * @param format the format of a latitude or a longitude
         * @param abs the absolute value of the latitude or longitude
         * @return the rounded value
         */
        static Rounded of(String format, double abs) {
            int unit = -1;
            for (int type = SEC; type >= DEG && unit < 0; type--) {
                if (findPart(format, TYPES[type]) != null) {
                    unit = type;
                }
            }
            if (unit < 0) {
                // The format prints no number
                return new Rounded(DEG, 0, BigDecimal.ZERO);
            }
            if (!Double.isFinite(abs)) {
                throw new IllegalArgumentException("Not a finite latitude or longitude: " + abs);
            }

            int decimals = decimals(format, TYPES[unit], DEFAULT_FORMATS[unit]);
            BigDecimal perDegree = BigDecimal.valueOf(UNITS_PER_DEGREE[unit]);
            int degrees = (int) Math.floor(abs);
            BigDecimal rest = (unit == DEG)
                    ? round(abs, decimals).subtract(BigDecimal.valueOf(degrees))
                    : round((abs - degrees) * UNITS_PER_DEGREE[unit], decimals);
            if (rest.compareTo(perDegree) >= 0) {
                // The rounding reached the next degree: carry it
                degrees++;
                rest = rest.subtract(perDegree);
            }
            return new Rounded(unit, degrees, rest);
        }

        /**
         * Returns the most decimals a part of the given type prints, or -1 when every one is floored or an integer.
         * @param format the format
         * @param type the part type
         * @param defaultFormat the printf format of a part of the type that is not floored and gives none
         * @return the decimals, or -1 for floored
         */
        private static int decimals(String format, String type, String defaultFormat) {
            int decimals = -1;
            Part p;
            while ((p = findPart(format, type)) != null) {
                String fmt = (p.format == null) ? defaultFormat : p.format;
                if (!p.floor && !fmt.endsWith("d")) {
                    Matcher m = PRECISION.matcher(fmt);
                    decimals = Math.max(decimals, m.find() ? Integer.parseInt(m.group(1)) : 6);
                }
                format = p.after;
            }
            return decimals;
        }

        /**
         * Rounds a value to the given decimals, or floors it.
         * @param value the value
         * @param decimals the decimals, or -1 to floor
         * @return the rounded value
         */
        private static BigDecimal round(double value, int decimals) {
            return (decimals < 0)
                    ? BigDecimal.valueOf(Math.floor(value))
                    : new BigDecimal(String.format(Locale.ROOT, "%." + decimals + "f", value));
        }

        /** Returns the whole minutes within the degree. */
        int minutes() {
            return switch (unit) {
                case MIN -> rest.intValue();
                case SEC -> rest.intValue() / 60;
                default -> 0;
            };
        }

        /** Returns the whole seconds within the minute. */
        int seconds() {
            return (unit == SEC) ? rest.intValue() % 60 : 0;
        }

        /**
         * Returns the value in the unit of a minutes or seconds part, with the decimals of the rounding: the minutes
         * within the degree or the seconds within the minute.
         * @param type the part type, MIN or SEC
         * @return the value in the unit of the part
         */
        BigDecimal inUnit(int type) {
            return switch (type) {
                case MIN -> (unit == SEC) ? rest.divide(BigDecimal.valueOf(60), MathContext.DECIMAL64) : rest;
                default -> rest.subtract(BigDecimal.valueOf(60L * minutes()));
            };
        }
    }

    /**
     * Looks for a format part of the given type and parses it into a Part object.
     * Returns null if the part type is not found
     * @param format the position format to search
     * @param type the part type to search for
     * @return the Part object or null
     */
    private static Part findPart(String format, String type) {
        int index = format.indexOf(type);
        if (index == -1) {
            return null;
        }

        Part p = new Part();
        p.type = type;
        p.before = format.substring(0, index);

        // Look for a floor postfix
        if (format.indexOf(type + "-F") == index) {
            p.floor = true;
            type = type + "-F";
        }

        // Check if the format defines a "[%02d]" style format
        if (format.indexOf(type + "[") == index && format.indexOf("]", index) > -1) {
            p.format = format.substring(index + type.length() + 1, format.indexOf("]", index));
            p.after = format.substring(format.indexOf("]", index) + 1);
        } else {
            p.after = format.substring(index + type.length());
        }
        return p;
    }

    public static void main(String[] args) {
        System.out.println(PositionFormatter.format(Locale.ENGLISH, "DEG[%02d], MIN[%2d], MIN[%.2f], SEC[%02d], DIR[N,S]", 55.0999));
        System.out.println(PositionFormatter.format(Locale.ENGLISH, LATLON_DEC_3.getLatFormat(), 55.2211));
        System.out.println(PositionFormatter.format(Locale.ENGLISH, LATLON_NAVTEX_1.getLatFormat(), 55.512345));
    }

    /**
     * Defines a combined lat-lon format
     */
    public static class Format {
        String latFormat, lonFormat;

        public Format(String latFormat, String lonFormat) {
            this.latFormat = latFormat;
            this.lonFormat = lonFormat;
        }

        public String getLatFormat() {
            return latFormat;
        }

        public String getLonFormat() {
            return lonFormat;
        }
    }

    /**
     * Helper class
     * Encapsulates a part of a position format such as ""
     * The type field can be one of "DEG", "MIN", "SEC" or "DIR"
     * The format can be a printf format for the numeric types (e.g. "%02d" or "%.2f") or a comma-separated array of values for "DIR".
     * The "before" and "after" fields contain the part of the format before and after the matched type part
     */
    static class Part {
        String type;
        String format;
        String before;
        String after;
        boolean floor;

        /**
         * Replaces the matched type part with value formatted according to the current format
         * @param locale the locale
         * @param value the value
         * @param defaultFormat if format to use, if the format field is undefined
         * @return the concatenated before - formatted value - after string.
         */
        public String replaceNumber(Locale locale, Object value, String defaultFormat) {
            String fmt = (format == null) ? defaultFormat : format;
            String val;
            if (!(value instanceof Number n)) {
                val = "";
            } else {
                Object v = fmt.endsWith("d") ? n.intValue() : value;
                val = String.format(locale, fmt, v);
            }
            return before + val + after;
        }

        /**
         * Replaces the matched type part with the indexed value of the the format
         * @param signIndex the value
         * @return the concatenated before - formatted value - after string.
         */
        public String replaceIndex(int signIndex) {
            String val;
            if (format == null || signIndex >= format.split(",").length) {
                val = "";
            } else {
                val = format.split(",")[signIndex];
            }
            return before + val + after;
        }
    }
}
