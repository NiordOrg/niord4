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
package org.niord.core.script.directive;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.core.script.FmTemplateService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * Framework-neutral Tier-1 unit tests for {@link LatLonDirective}, the Freemarker directive that
 * formats a latitude and/or longitude into a human-readable string.
 * <p>
 * Each test renders a one-off template that invokes the directive ({@code <@p .../>}) through a real
 * Freemarker {@link freemarker.template.Configuration} (see {@link DirectiveTestSupport}) and pins the
 * exact emitted string. The cases cover: the default {@code "dec"} format (3 decimals), the explicit
 * {@code dec-N}/{@code navtex-N} decimal suffixes, the language-independent {@code "sec"} format, the
 * single-space-separated {@code "navtex"} and locale-driven {@code "audio"} formats, an explicit
 * {@code decimals} override, lat-only / lon-only emission (separator suppression), a custom
 * separator, southern/western-hemisphere sign handling, and the required-parameter contract where a
 * missing {@code lat} AND {@code lon} throws a {@link TemplateException} before the directive's
 * robustness try/catch. The audio format follows the render's own bundle, so an edit or deletion of the
 * position words reaches the next render (SCRIPT11).
 * <p>
 * The degree sign {@code °} and the literal double-quote seconds marker match the format constants used
 * by {@link org.niord.core.util.PositionFormatter}. All assertions use the ENGLISH locale.
 */
public class LatLonDirectiveTest {

    @Test
    public void defaultFormat_isDecWithThreeDecimals() throws Exception {
        // No format code => "dec" with 3 decimals (LATLON_DEC_3), default " - " separator.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00.000'N - 012° 00.000'E", render("<@p lat=55.0 lon=12.0/>", data));
    }

    @Test
    public void defaultFormat_roundsDecimalMinutes() throws Exception {
        // dec-3: 55.2211 -> 13.266' and 12.5 -> 30.000'.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 13.266'N - 012° 30.000'E", render("<@p lat=55.2211 lon=12.5/>", data));
    }

    @Test
    public void decFormat_oneDecimalSuffix() throws Exception {
        // "dec-1" => one decimal minute (LATLON_DEC_1).
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 30.0'N - 012° 30.0'E", render("<@p lat=55.5 lon=12.5 format=\"dec-1\"/>", data));
    }

    @Test
    public void decFormat_twoDecimalSuffix() throws Exception {
        // "dec-2" => two decimal minutes (LATLON_DEC_2).
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 13.27'N - 012° 30.00'E", render("<@p lat=55.2211 lon=12.5 format=\"dec-2\"/>", data));
    }

    @Test
    public void secFormat_emitsSecondsWithDoubleQuoteMarker() throws Exception {
        // "sec" => degrees/minutes/seconds, seconds suffixed with a literal double-quote (LATLON_SEC).
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00' 00\"N - 012° 00' 00\"E", render("<@p lat=55.0 lon=12.0 format=\"sec\"/>", data));
    }

    @Test
    public void navtexFormat_usesSpaceSeparatorAndDashedComponents() throws Exception {
        // "navtex" => single-space separator, dash-joined components, default 1 decimal (LATLON_NAVTEX_1).
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55-30.7N 012-00.0E", render("<@p lat=55.512345 lon=12.0 format=\"navtex\"/>", data));
    }

    @Test
    public void navtexFormat_threeDecimalSuffix() throws Exception {
        // "navtex-3" => three decimal minutes, single-space separator (LATLON_NAVTEX_3).
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55-13.266N 012-30.000E", render("<@p lat=55.2211 lon=12.5 format=\"navtex-3\"/>", data));
    }

    @Test
    public void minutesThatRoundUpTo60_areCarriedIntoTheDegrees() throws Exception {
        // POS1: the light at Aggersund (NW-319-26 of the test data) lies at 56° 59.964' N, which printed
        // "56° 60.0'N", "56-60.0N" and "56 degrees 60.0 min. North" before the fix.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        String position = "lat=56.99940309853659 lon=9.294097396125087";
        Assertions.assertEquals("57° 00.0'N - 009° 17.6'E", render("<@p " + position + " format=\"dec-1\"/>", data));
        Assertions.assertEquals("57-00.0N 009-17.6E", render("<@p " + position + " format=\"navtex\"/>", data));
        Assertions.assertEquals("57 degrees 0.0 min. North 9 degrees 17.6 min. East",
                render("<@p " + position + " format=\"audio\"/>", data));
        Assertions.assertEquals("56° 59.964'N - 009° 17.646'E", render("<@p " + position + "/>", data));
    }

    @Test
    public void audioFormat_usesSpaceSeparatorAndTextBundle() throws Exception {
        // "audio" => spoken form from the "text" bundle, single-space separator, default 1 decimal.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55 degrees 30.0 min. North 12 degrees 30.0 min. East",
                render("<@p lat=55.5 lon=12.5 format=\"audio\"/>", data));
    }

    @Test
    public void audioFormat_followsAnEditOfThePositionWords() throws Exception {
        // SCRIPT11: the audio format was built once per language and decimals and kept in a static map, so an
        // edit of position.* on the Dictionaries page reached the templates only after a restart. Each render
        // now builds it from the render's own bundle.
        Map<String, Object> before = dataWith(positionWords("degrees", "min.", "North", "South", "East", "West"));
        before.put("p", new LatLonDirective());
        Assertions.assertEquals("55 degrees 30.0 min. North 12 degrees 30.0 min. East",
                render("<@p lat=55.5 lon=12.5 format=\"audio\"/>", before));

        Map<String, Object> after = dataWith(positionWords("deg.", "minutes", "N.", "S.", "E.", "W."));
        after.put("p", new LatLonDirective());
        Assertions.assertEquals("55 deg. 30.0 minutes N. 12 deg. 30.0 minutes E.",
                render("<@p lat=55.5 lon=12.5 format=\"audio\"/>", after));
    }

    @Test
    public void audioFormat_printsNothingWhenAPositionWordIsDeletedAfterAnEarlierRender() throws Exception {
        // The Dictionaries page says a position text missing means audio positions print nothing; that held
        // only before the first render, while the start-up format was kept.
        Map<String, Object> before = data();
        before.put("p", new LatLonDirective());
        Assertions.assertEquals("55 degrees 30.0 min. North 12 degrees 30.0 min. East",
                render("<@p lat=55.5 lon=12.5 format=\"audio\"/>", before));

        Map<String, Object> withoutDeg = dataWith(positionWords(null, "min.", "North", "South", "East", "West"));
        withoutDeg.put("p", new LatLonDirective());
        Assertions.assertEquals("", render("<@p lat=55.5 lon=12.5 format=\"audio\"/>", withoutDeg));
    }

    /** A data model whose {@code text} bundle is the given one. */
    private static Map<String, Object> dataWith(ResourceBundle bundle) {
        Map<String, Object> m = data();
        m.put(FmTemplateService.BUNDLE_PROPERTY, new MultiResourceBundleModel(
                Collections.singletonList(bundle),
                new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build()));
        return m;
    }

    /** An English bundle holding the six position words; a null word leaves its key out. */
    private static ResourceBundle positionWords(String deg, String min, String north, String south,
                                                String east, String west) {
        Map<String, String> words = new LinkedHashMap<>();
        String[] keys = {"position.deg", "position.min", "position.north", "position.south",
                "position.east", "position.west"};
        String[] values = {deg, min, north, south, east, west};
        for (int i = 0; i < keys.length; i++) {
            if (values[i] != null) {
                words.put(keys[i], values[i]);
            }
        }
        return new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return words.entrySet().stream()
                        .map(e -> new Object[] {e.getKey(), e.getValue()})
                        .toArray(Object[][]::new);
            }

            @Override
            public Locale getLocale() {
                return Locale.ENGLISH;
            }
        };
    }

    @Test
    public void decimalsParameter_overridesDefaultDecimals() throws Exception {
        // An explicit decimals=1 on "dec" overrides the default 3 decimals.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 30.0'N - 012° 30.0'E", render("<@p lat=55.5 lon=12.5 format=\"dec\" decimals=1/>", data));
    }

    @Test
    public void latOnly_emitsNoSeparator() throws Exception {
        // With only lat, just the latitude is emitted and no separator is written.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00.000'N", render("<@p lat=55.0/>", data));
    }

    @Test
    public void lonOnly_emitsNoSeparator() throws Exception {
        // With only lon, just the longitude is emitted and no separator is written.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("012° 00.000'E", render("<@p lon=12.0/>", data));
    }

    @Test
    public void customSeparator_isUsedBetweenLatAndLon() throws Exception {
        // A custom separator parameter replaces the default " - " between lat and lon.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00.000'N / 012° 00.000'E", render("<@p lat=55.0 lon=12.0 separator=\" / \"/>", data));
    }

    @Test
    public void secFormat_ignoresDecimalsParameter() throws Exception {
        // "sec" uses the fixed LATLON_SEC format, so the decimals parameter has no effect.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00' 00\"N - 012° 00' 00\"E",
                render("<@p lat=55.0 lon=12.0 format=\"sec\" decimals=5/>", data));
    }

    @Test
    public void negativeCoordinates_yieldSouthernAndWesternSuffixes() throws Exception {
        // Negative lat/lon produce S/W suffixes; the sign is dropped from the numeric components.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        Assertions.assertEquals("55° 00' 00\"S - 012° 00' 00\"W",
                render("<@p lat=-55.0 lon=-12.0 format=\"sec\"/>", data));
    }

    @Test
    public void missingBothLatAndLon_throwsTemplateException() throws Exception {
        // Neither lat nor lon is supplied: the directive throws before its robustness try/catch.
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        try {
            render("<@p/>", data);
            Assertions.fail("Expected a TemplateException when neither 'lat' nor 'lon' is specified");
        } catch (TemplateException expected) {
            // expected
        }
    }
}
