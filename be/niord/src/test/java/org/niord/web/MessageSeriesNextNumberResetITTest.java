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
package org.niord.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;
import org.niord.test.DeliveryFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.niord.test.DeliveryFixtures.PAST_YEAR;
import static org.niord.test.DeliveryFixtures.uniq;

/**
 * A sysadmin's reset of a series' next number may not go below a number a message of the series already has
 * (SERIES-RESET, the review's finding R5 (b)). Before, the reset refused only a number below 0: a reset to 12 when 12
 * was already used (a wrong value, or a value read in the popover before a publication took it) was kept, and the
 * next publication took 12 again, beside the warning that already had it.
 *
 * <p>Now the PUT answers 400 with the reason, and the sequence keeps its value, for a number below the one Compute
 * gives (the highest number used in the series and year, plus one; in a CONTINUOUS series, in any year). Compute's
 * number and any number above it are accepted as before, and so is any number of 0 or more in a series and year with
 * no numbered message.
 *
 * <p>The series are this class's own, in no domain, made with {@link DeliveryFixtures}; their messages are dated and
 * numbered in {@link DeliveryFixtures#PAST_YEAR}. The messages, the series and the series' number rows are removed
 * after each test.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class MessageSeriesNextNumberResetITTest extends RoundTripSupport {

    @Autowired
    ApplicationContext context;

    private DeliveryFixtures fixtures;
    private final List<String> seriesIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new DeliveryFixtures(context, em);
    }

    @AfterEach
    void tearDown() {
        fixtures.removeAll();
        for (String seriesId : seriesIds) {
            try {
                execute("DELETE FROM public.\"Sequence\" WHERE name LIKE ?1", "MESSAGE_SERIES_NUMBER_" + seriesId + "%");
            } catch (RuntimeException e) {
                System.err.println("MessageSeriesNextNumberResetITTest: number rows of " + seriesId + " left: " + e);
            }
        }
        seriesIds.clear();
    }

    private String series(NumberSequenceType numbering) {
        String seriesId = "it-reset-" + uniq();
        fixtures.createSeries(seriesId, MainType.NW, numbering, "RS-${number-3-digits}-${year-2-digits}");
        seriesIds.add(seriesId);
        return seriesId;
    }

    private static String numberPath(String seriesId, int year) {
        return "/rest/message-series/series/" + seriesId + "/number/" + year;
    }

    /** The committed next number of the series in the year, as the popover reads it */
    private static int nextNumber(String seriesId, int year) {
        return Integer.parseInt(as("sysadmin").when().get(numberPath(seriesId, year))
                .then().statusCode(200).extract().asString().trim());
    }

    /** The number Compute gives */
    private static int computed(String seriesId, int year) {
        return Integer.parseInt(as("sysadmin").when()
                .get("/rest/message-series/series/" + seriesId + "/compute-number/" + year)
                .then().statusCode(200).extract().asString().trim());
    }

    /** Publishes two warnings of the series in {@link DeliveryFixtures#PAST_YEAR}: they take 1 and 2 */
    private void publishTwo(String seriesId) {
        String a = fixtures.published(seriesId, "IT reset A " + uniq(), null);
        String b = fixtures.published(seriesId, "IT reset B " + uniq(), null);
        assertEquals(List.of(1, 2), List.of(fixtures.numberOf(a), fixtures.numberOf(b)), "the fixture's numbers");
    }

    // ------------------------------------------------------------------------------------------- //
    // A YEARLY series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aResetBelowANumberAlreadyUsedIsRefusedAndTheSequenceKeepsItsValue() {
        String s = series(NumberSequenceType.YEARLY);
        publishTwo(s);
        assertEquals(3, computed(s, PAST_YEAR));
        assertEquals(3, nextNumber(s, PAST_YEAR));

        putRaw("sysadmin", numberPath(s, PAST_YEAR), "2").statusCode(400)
                .body("detail", containsString("already used"));
        assertEquals(3, nextNumber(s, PAST_YEAR), "the sequence keeps its value");

        putRaw("sysadmin", numberPath(s, PAST_YEAR), "1").statusCode(400);
        putRaw("sysadmin", numberPath(s, PAST_YEAR), "0").statusCode(400);
        assertEquals(3, nextNumber(s, PAST_YEAR), "the sequence keeps its value");
    }

    @Test
    void aResetToTheComputedNumberOrAboveIsAccepted() {
        String s = series(NumberSequenceType.YEARLY);
        publishTwo(s);

        putRaw("sysadmin", numberPath(s, PAST_YEAR), "10").statusCode(200);
        assertEquals(10, nextNumber(s, PAST_YEAR));

        // Down again, to Compute's number: no message has it
        putRaw("sysadmin", numberPath(s, PAST_YEAR), "3").statusCode(200);
        assertEquals(3, nextNumber(s, PAST_YEAR));

        // A year of the series with no numbered message is not held by the other year's numbers
        putRaw("sysadmin", numberPath(s, PAST_YEAR + 1), "1").statusCode(200);
        assertEquals(1, nextNumber(s, PAST_YEAR + 1));
    }

    // ------------------------------------------------------------------------------------------- //
    // A CONTINUOUS series: the year is ignored
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aContinuousSeriesRefusesANumberUsedInAnotherYear() {
        String s = series(NumberSequenceType.CONTINUOUS);
        publishTwo(s);
        int laterYear = PAST_YEAR + 5;
        assertEquals(3, computed(s, laterYear), "a continuous series numbers on across the years");

        putRaw("sysadmin", numberPath(s, laterYear), "2").statusCode(400)
                .body("detail", containsString("already used"));
        assertEquals(3, nextNumber(s, laterYear), "the sequence keeps its value");

        putRaw("sysadmin", numberPath(s, laterYear), "3").statusCode(200);
        putRaw("sysadmin", numberPath(s, PAST_YEAR), "7").statusCode(200);
        assertEquals(7, nextNumber(s, laterYear), "one sequence for every year");
    }

    // ------------------------------------------------------------------------------------------- //
    // A series and year with no numbered message: any number of 0 or more, as before
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSeriesWithNoNumberedMessageAcceptsAnyNumberOfZeroOrMore() {
        String yearly = series(NumberSequenceType.YEARLY);
        String continuous = series(NumberSequenceType.CONTINUOUS);
        for (String s : List.of(yearly, continuous)) {
            assertEquals(1, computed(s, PAST_YEAR));
            for (String n : List.of("5", "1", "0")) {
                putRaw("sysadmin", numberPath(s, PAST_YEAR), n).statusCode(200);
                assertEquals(Integer.parseInt(n), nextNumber(s, PAST_YEAR));
            }
            putRaw("sysadmin", numberPath(s, PAST_YEAR), "-1").statusCode(400);
            assertEquals(0, nextNumber(s, PAST_YEAR));
        }
    }
}
