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
package org.niord.core.script.directive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * {@link LatLonDirective} (the {@code formatPos} of every template) over every format code and number of
 * decimals a template can ask for, and over a grid of positions with minutes just below the next unit. What
 * {@code PositionFormatterRoundingTest} checks on the formatter, this checks through the directive: the
 * format a template names is the format it gets, and both the latitude and the longitude read back on their own
 * side of the globe (the first version read back the latitude only and
 * accepted either hemisphere letter, so a formatter that printed every position north and east passed it).
 */
public class LatLonDirectiveGridTest {

    private static String pos(String attrs) throws Exception {
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        return render("<@p " + attrs + "/>", data, Locale.ENGLISH);
    }

    /** "DD-MM.m{n}X DDD-MM.m{n}X", the NAVTEX form. */
    private static final Pattern NAVTEX = Pattern.compile("(\\d{2})-(\\d{2}(?:\\.\\d+)?)([NS]) (\\d{3})-(\\d{2}(?:\\.\\d+)?)([EW])");
    /** "DD° MM.m{n}'X - DDD° MM.m{n}'X", the decimal form. */
    private static final Pattern DEC = Pattern.compile("(\\d{2})° (\\d{2}(?:\\.\\d+)?)'([NS]) - (\\d{3})° (\\d{2}(?:\\.\\d+)?)'([EW])");

    @Test
    public void navtexAndDec_withOneToThreeDecimals_printThatForm_overTheGrid() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String code : List.of("navtex", "dec")) {
            for (int decimals = 1; decimals <= 3; decimals++) {
                for (int deg : new int[] { 0, 9, 55, 56, 89 }) {
                    for (double minutes : new double[] { 0, 29.96, 59.94, 59.95, 59.96, 59.994, 59.995, 59.9994, 59.9995 }) {
                      // Both hemispheres of each: north and west, then south and east
                      for (int sign : new int[] { 1, -1 }) {
                        double lat = sign * (deg + minutes / 60);
                        double lon = -sign * (deg + minutes / 60);
                        for (String attrs : List.of(
                                "format=\"" + code + "\" decimals=" + decimals,
                                "format=\"" + code + "-" + decimals + "\"")) {
                            String printed = pos("lat=" + lat + " lon=" + lon + " " + attrs);
                            Matcher m = (code.equals("navtex") ? NAVTEX : DEC).matcher(printed);
                            String problem = null;
                            if (!m.matches()) {
                                problem = "not the " + code + " form";
                            } else if (m.group(2).length() != 2 + (decimals > 0 ? decimals + 1 : 0)
                                    || m.group(5).length() != 2 + (decimals > 0 ? decimals + 1 : 0)) {
                                problem = "not " + decimals + " decimals";
                            } else if (Double.parseDouble(m.group(2)) >= 60 || Double.parseDouble(m.group(5)) >= 60) {
                                problem = "60 minutes";
                            } else {
                                // The latitude and the longitude each read back, on its own side of the globe
                                double tolerance = 0.5 * Math.pow(10, -decimals) / 60 + 1e-9;
                                double back = Integer.parseInt(m.group(1)) + Double.parseDouble(m.group(2)) / 60;
                                double backLon = Integer.parseInt(m.group(4)) + Double.parseDouble(m.group(5)) / 60;
                                String hemispheres = (lat >= 0 ? "N" : "S") + (lon >= 0 ? "E" : "W");
                                if (Math.abs(back - Math.abs(lat)) > tolerance) {
                                    problem = "reads back as " + back;
                                } else if (Math.abs(backLon - Math.abs(lon)) > tolerance) {
                                    problem = "the longitude reads back as " + backLon;
                                } else if (!hemispheres.equals(m.group(3) + m.group(6))) {
                                    problem = "the hemispheres are " + m.group(3) + m.group(6) + ", not " + hemispheres;
                                }
                            }
                            if (problem != null) {
                                failures.add(lat + "," + lon + " [" + attrs + "] -> \"" + printed + "\": " + problem);
                            }
                            checked++;
                        }
                      }
                    }
                }
            }
        }
        assertTrue(checked > 400, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " of " + checked + " wrong, the first: "
                + failures.subList(0, Math.min(10, failures.size())));
    }

    @Test
    public void navtexWithNoDecimalsOrFour_printsTheDecimalFormWithDegreeSigns_documentsBug() throws Exception {
        // resolvePositionFormat looks "navtex-0" and "navtex-4" up in a cache that holds only navtex-1..3 and
        // dec-1..3, and falls back to LATLON_DEC_3: the template asked for the NAVTEX form and gets degree
        // signs and apostrophes, which are not in the NAVTEX alphabet, joined by the NAVTEX separator. The same
        // happens to "dec" with 0 or 4 decimals (three are printed). Templates pass decimals=posDecimals, a
        // template parameter (common.ftl renderPositionList), so the value is data. The right output is the
        // NAVTEX form at the asked precision ("57-00N 009-18E" for 0 decimals), or a refusal; the assertions
        // then change to that.
        String aggersund = "lat=56.99940309853659 lon=9.294097396125087";
        assertEquals("56° 59.964'N 009° 17.646'E", pos(aggersund + " format=\"navtex\" decimals=0"));
        assertEquals("56° 59.964'N 009° 17.646'E", pos(aggersund + " format=\"navtex-4\""));
        assertEquals("56° 59.964'N - 009° 17.646'E", pos(aggersund + " format=\"dec\" decimals=0"));
        assertEquals("56° 59.964'N - 009° 17.646'E", pos(aggersund + " format=\"dec-4\""));
    }

    @Test
    public void audio_withEveryDecimalCount_carriesTheRounding() throws Exception {
        // The audio format is built per call for any number of decimals, so 0 and 4 work there.
        String aggersund = "lat=56.99940309853659 lon=9.294097396125087";
        assertEquals("57 degrees 0 min. North 9 degrees 18 min. East", pos(aggersund + " format=\"audio\" decimals=0"));
        assertEquals("57 degrees 0.0 min. North 9 degrees 17.6 min. East", pos(aggersund + " format=\"audio\" decimals=1"));
        assertEquals("56 degrees 59.9642 min. North 9 degrees 17.6458 min. East",
                pos(aggersund + " format=\"audio\" decimals=4"));
    }
}
