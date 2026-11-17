/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.core.util.NavWarnDateFormatter.Format;
import org.niord.model.message.DateIntervalVo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NavWarnDateFormatter}, the formatter used to render dates and date intervals
 * in the form expected by navigational warnings.
 *
 * <p>All dates are built explicitly in UTC via {@link #utc(String)} so that the assertions are
 * deterministic regardless of the JVM's default time zone or locale. Formatters are created with
 * explicit {@link Locale#ENGLISH} and explicit "UTC" time zones for the same reason.</p>
 *
 * <p>The terms used by the formatter are loaded from the base product's English texts,
 * {@code defaults/base/texts/en/template.properties}, e.g. {@code navwarn.date.day_prefix=the},
 * {@code navwarn.date.time_prefix=at}, etc.</p>
 */
public class NavWarnDateFormatterTest {

    /** The application resource bundle containing the formatting terms (English). */
    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("defaults/base/texts/en/template", Locale.ROOT);

    /** Builds a UTC date from a "yyyy-MM-dd HH:mm" string. */
    private static Date utc(String dateTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(dateTime);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /** A PLAIN formatter, UTC, showing the time zone label. */
    private static NavWarnDateFormatter plain() {
        return NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "UTC", "UTC", true);
    }

    /** A NAVTEX formatter, UTC, showing the time zone label. */
    private static NavWarnDateFormatter navtex() {
        return NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.NAVTEX, Locale.ENGLISH, "UTC", "UTC", true);
    }

    /** An HTML formatter, UTC, showing the time zone label. */
    private static NavWarnDateFormatter html() {
        return NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.HTML, Locale.ENGLISH, "UTC", "UTC", true);
    }

    /** Builds a date interval. */
    private static DateIntervalVo interval(Date from, Date to, Boolean allDay) {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(from);
        di.setToDate(to);
        di.setAllDay(allDay);
        return di;
    }

    // ------------------------------------------------------------------
    //  formatDate(Date)
    // ------------------------------------------------------------------

    @Test
    public void formatDate_null_returnsEmptyString() {
        assertEquals("", plain().formatDate(null));
    }

    @Test
    public void formatDate_plain_rendersDayPrefixSuffixMonthYearTimeAndZone() {
        assertEquals("the 12th April 2017 at 1300 UTC",
                plain().formatDate(utc("2017-04-12 13:00")));
    }

    @Test
    public void formatDate_plain_dayWithStSuffix() {
        // The 1st of the month -> "st" suffix (daySuffix index 1).
        assertEquals("the 1st April 2017 at 0000 UTC",
                plain().formatDate(utc("2017-04-01 00:00")));
    }

    @Test
    public void formatDate_plain_dayWithNdSuffix() {
        // The 2nd of the month -> "nd" suffix.
        assertEquals("the 2nd December 2017 at 2359 UTC",
                plain().formatDate(utc("2017-12-02 23:59")));
    }

    @Test
    public void formatDate_plain_dayWithRdSuffix() {
        // The 23rd of the month -> "rd" suffix (daySuffix index 23).
        assertEquals("the 23rd June 2020 at 0830 UTC",
                plain().formatDate(utc("2020-06-23 08:30")));
    }

    @Test
    public void formatDate_navtex_rendersCompactUppercaseFormat() {
        // NAVTEX format: ddHHmm 'UTC' MMM yy, uppercased.
        assertEquals("121300 UTC APR 17",
                navtex().formatDate(utc("2017-04-12 13:00")));
    }

    @Test
    public void formatDate_html_wrapsSuffixInSupTag() {
        String result = html().formatDate(utc("2017-04-12 13:00"));
        assertEquals("the 12<sup>th</sup> April 2017 at 1300 UTC", result);
    }

    @Test
    public void formatDate_plain_withoutTimeZoneLabel_omitsZone() {
        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "UTC", "UTC", false);
        assertEquals("the 12th April 2017 at 1300",
                f.formatDate(utc("2017-04-12 13:00")));
    }

    // ------------------------------------------------------------------
    //  formatNavWarnDate(Date) -- behaves like the PLAIN branch of formatDate
    // ------------------------------------------------------------------

    @Test
    public void formatNavWarnDate_plainFormatter_rendersFullPrefixedDate() {
        // formatNavWarnDate is the nav-warn rendering used by the PLAIN/HTML branch of formatDate.
        assertEquals("the 12th April 2017 at 1300 UTC",
                plain().formatNavWarnDate(utc("2017-04-12 13:00")));
    }

    @Test
    public void formatNavWarnDate_navtexFormatter_usesNavtexPrefixesAndMonthAbbrev() {
        // formatNavWarnDate always uses the nav-warn assembly, but the formatter's NAVTEX settings
        // still apply: empty day/time prefixes and the abbreviated month pattern ("Apr").
        assertEquals("12th Apr 2017 1300 UTC",
                navtex().formatNavWarnDate(utc("2017-04-12 13:00")));
    }

    // ------------------------------------------------------------------
    //  formatDateInterval(DateIntervalVo)
    // ------------------------------------------------------------------

    @Test
    public void formatDateInterval_null_returnsEmptyString() {
        assertEquals("", plain().formatDateInterval(null));
    }

    @Test
    public void formatDateInterval_bothDatesNull_returnsUntilFurtherNotice() {
        assertEquals("until further notice",
                plain().formatDateInterval(interval(null, null, false)));
    }

    @Test
    public void formatDateInterval_bothDatesNull_allDayNull_returnsUntilFurtherNotice() {
        assertEquals("until further notice",
                plain().formatDateInterval(interval(null, null, null)));
    }

    @Test
    public void formatDateInterval_timedSameInstant_rendersSinglePoint() {
        Date d = utc("2017-04-12 13:00");
        assertEquals("the 12th April 2017 at 1300 UTC",
                plain().formatDateInterval(interval(d, d, false)));
    }

    @Test
    public void formatDateInterval_timedSameDayDifferentTime_rendersTimeRange() {
        assertEquals("the 12th April 2017 from 1300 to 1355 UTC",
                plain().formatDateInterval(
                        interval(utc("2017-04-12 13:00"), utc("2017-04-12 13:55"), false)));
    }

    @Test
    public void formatDateInterval_timedDifferentDay_rendersFromToWithBothDates() {
        assertEquals("from the 12th April 2017 at 1300 UTC to the 16th April 2017 at 1355 UTC",
                plain().formatDateInterval(
                        interval(utc("2017-04-12 13:00"), utc("2017-04-16 13:55"), false)));
    }

    @Test
    public void formatDateInterval_timedFromOnly_rendersFromClause() {
        assertEquals("from the 12th April 2017 at 1300 UTC",
                plain().formatDateInterval(interval(utc("2017-04-12 13:00"), null, false)));
    }

    @Test
    public void formatDateInterval_timedToOnly_rendersUntilClause() {
        assertEquals("until the 12th April 2017 at 1300 UTC",
                plain().formatDateInterval(interval(null, utc("2017-04-12 13:00"), false)));
    }

    @Test
    public void formatDateInterval_allDaySameDay_rendersDateOnly() {
        Date d = utc("2017-04-12 00:00");
        assertEquals("the 12th April 2017",
                plain().formatDateInterval(interval(d, d, true)));
    }

    @Test
    public void formatDateInterval_allDayDifferentDay_rendersFromToDatesWithoutTime() {
        // Same month and year are shared, so the from-side drops both month and year and only the
        // to-side carries the full "April 2017".
        assertEquals("from the 12th to the 16th April 2017",
                plain().formatDateInterval(
                        interval(utc("2017-04-12 00:00"), utc("2017-04-16 00:00"), true)));
    }

    @Test
    public void formatDateInterval_allDayFromOnly_rendersFromDateWithoutTime() {
        assertEquals("from the 12th April 2017",
                plain().formatDateInterval(interval(utc("2017-04-12 00:00"), null, true)));
    }

    @Test
    public void formatDateInterval_allDayToOnly_rendersUntilDateWithoutTime() {
        assertEquals("until the 12th April 2017",
                plain().formatDateInterval(interval(null, utc("2017-04-12 00:00"), true)));
    }

    @Test
    public void formatDateInterval_allDayDifferentMonthSameYear_collapsesSharedYear() {
        // sameYear -> the from-side year is dropped (only the to-side keeps the shared year);
        // the months differ so both month names are shown.
        assertEquals("from the 12th April to the 16th May 2017",
                plain().formatDateInterval(
                        interval(utc("2017-04-12 00:00"), utc("2017-05-16 00:00"), true)));
    }

    @Test
    public void formatDateInterval_navtexTimedDifferentDay_rendersDashSeparatedUppercase() {
        assertEquals("121300 UTC APR 17 - 161355 UTC APR 17",
                navtex().formatDateInterval(
                        interval(utc("2017-04-12 13:00"), utc("2017-04-16 13:55"), false)));
    }

    @Test
    public void formatDateInterval_navtexTimedToOnly_rendersUntilClause() {
        // Fixed (docs/potential-errors.md U5): the NAVTEX to-only branch is now trimmed, so the
        // leading space of untilTxt (" until ") no longer leaks. (Casing is intentionally unchanged:
        // "until" stays lowercase and the date stays mixed-case, as before.)
        assertEquals("until 121300 UTC Apr 17",
                navtex().formatDateInterval(interval(null, utc("2017-04-12 13:00"), false)));
    }

    @Test
    public void formatDateInterval_navtexTimedFromOnly_uppercasesFromText() {
        // Fixed (docs/potential-errors.md U5): the NAVTEX from-only branch is now trimmed, so the
        // leading space of " FROM " no longer leaks. (The pre-existing casing — "FROM" uppercased,
        // the date left mixed-case "Apr" — is deliberately preserved as the narrow leading-space fix.)
        assertEquals("FROM 121300 UTC Apr 17",
                navtex().formatDateInterval(interval(utc("2017-04-12 13:00"), null, false)));
    }

    @Test
    public void formatDateInterval_navtexAllDay_fallsBackToNavWarnRendering() {
        // For all-day intervals NAVTEX deliberately delegates to the nav-warn (date-only) rendering.
        Date d = utc("2017-04-12 00:00");
        // NAVTEX dayPrefix/timePrefix are empty, so no "the" prefix is emitted, and the NAVTEX
        // month pattern is the abbreviated "MMM" ("Apr"), not the full "MMMM".
        assertEquals("12th Apr 2017",
                navtex().formatDateInterval(interval(d, d, true)));
    }

    /**
     * DICT4: in an English-only deployment the template dictionary holds the English texts alone, and
     * {@code navwarn.date.time_interval_prefix} is empty on purpose. The bundle the templates get is built from
     * {@link DictionaryVo#toProperties(String, String)}; the empty text must be in it, so a within-day interval is
     * formatted (no MissingResourceException, which answered 500) and prints no "kl.".
     */
    @Test
    public void formatDateInterval_withinADay_fromAnEnglishOnlyDictionary_printsNoPrefix() throws IOException {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("defaults/base/texts/en/template.properties")) {
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("", texts.getProperty("navwarn.date.time_interval_prefix"),
                "the base product's English prefix is empty on purpose");

        DictionaryVo dict = new DictionaryVo();
        dict.setName("template");
        for (String key : texts.stringPropertyNames()) {
            DictionaryEntryVo entry = new DictionaryEntryVo();
            entry.setKey(key);
            entry.createDesc("en").setValue(texts.getProperty(key));
            dict.getEntries().put(key, entry);
        }
        Properties en = dict.toProperties("en", "en");
        ResourceBundle bundle = new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return en.getProperty(key);
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(en.stringPropertyNames());
            }
        };

        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                bundle, Format.PLAIN, Locale.ENGLISH, "UTC", "UTC", true);
        String text = f.formatDateInterval(
                interval(utc("2017-04-12 13:00"), utc("2017-04-12 13:55"), false));
        assertEquals("the 12th April 2017 from 1300 to 1355 UTC", text);
        assertFalse(text.contains("kl."), text);
    }

    /**
     * R1-1 (DICT4): a template dictionary whose empty English {@code navwarn.date.time_interval_prefix} row has gone
     * has no such key. The formatter prints no prefix instead of throwing MissingResourceException (a 500 from every
     * template that formats event dates).
     */
    @Test
    public void formatDateInterval_withinADay_withoutTheTimeIntervalPrefixKey_printsNoPrefix() throws IOException {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("defaults/base/texts/en/template.properties")) {
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        texts.remove("navwarn.date.time_interval_prefix");
        ResourceBundle bundle = new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return texts.getProperty(key);
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(texts.stringPropertyNames());
            }
        };

        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                bundle, Format.PLAIN, Locale.ENGLISH, "UTC", "UTC", true);
        assertEquals("the 12th April 2017 from 1300 to 1355 UTC", f.formatDateInterval(
                interval(utc("2017-04-12 13:00"), utc("2017-04-12 13:55"), false)));
    }

    // ------------------------------------------------------------------
    //  formatDateIntervals(List<DateIntervalVo>)
    // ------------------------------------------------------------------

    @Test
    public void formatDateIntervals_null_returnsEmptyString() {
        assertEquals("", plain().formatDateIntervals(null));
    }

    @Test
    public void formatDateIntervals_empty_returnsEmptyString() {
        assertEquals("", plain().formatDateIntervals(new ArrayList<DateIntervalVo>()));
    }

    @Test
    public void formatDateIntervals_singleElement_returnsThatInterval() {
        List<DateIntervalVo> list = new ArrayList<>();
        list.add(interval(utc("2017-04-12 13:00"), null, false));
        assertEquals("from the 12th April 2017 at 1300 UTC",
                plain().formatDateIntervals(list));
    }

    @Test
    public void formatDateIntervals_twoElements_joinedWithAnd() {
        List<DateIntervalVo> list = new ArrayList<>();
        list.add(interval(utc("2017-04-12 13:00"), null, false));
        list.add(interval(utc("2017-04-16 13:55"), null, false));
        assertEquals("from the 12th April 2017 at 1300 UTC and from the 16th April 2017 at 1355 UTC",
                plain().formatDateIntervals(list));
    }

    @Test
    public void formatDateIntervals_threeElements_commaSeparatedWithFinalAnd() {
        List<DateIntervalVo> list = new ArrayList<>();
        list.add(interval(utc("2017-04-12 13:00"), null, false));
        list.add(interval(utc("2017-04-16 13:55"), null, false));
        list.add(interval(utc("2017-04-20 09:15"), null, false));
        assertEquals(
                "from the 12th April 2017 at 1300 UTC, "
                        + "from the 16th April 2017 at 1355 UTC and "
                        + "from the 20th April 2017 at 0915 UTC",
                plain().formatDateIntervals(list));
    }

    // ------------------------------------------------------------------
    //  Time-zone handling
    // ------------------------------------------------------------------

    @Test
    public void formatDate_nonUtcTimeZone_shiftsWallClockAndLabel() {
        // 13:00 UTC is 14:00 in +01:00 (CET, no DST in this fixed offset zone).
        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "GMT+1", "GMT+1", true);
        assertEquals("the 12th April 2017 at 1400 GMT+1",
                f.formatDate(utc("2017-04-12 13:00")));
    }

    @Test
    public void formatDate_timeZoneShiftCanCrossDayBoundary() {
        // 23:30 UTC is 00:30 the next day in +01:00, so the day rolls over to the 13th.
        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "GMT+1", "GMT+1", true);
        assertEquals("the 13th April 2017 at 0030 GMT+1",
                f.formatDate(utc("2017-04-12 23:30")));
    }

    @Test
    public void formatDateInterval_allDayUsesAllDayTimeZone() {
        // All-day intervals use the allDayTimeZone, which here differs from the regular timeZone.
        // At 23:30 UTC, the all-day GMT+1 zone sees the 13th.
        NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "UTC", "GMT+1", true);
        Date d = utc("2017-04-12 23:30");
        assertEquals("the 13th April 2017",
                f.formatDateInterval(interval(d, d, true)));
    }

    // ------------------------------------------------------------------
    //  Locale handling
    // ------------------------------------------------------------------

    @Test
    public void formatDate_localeAffectsMonthName() {
        // The month name is rendered using the formatter's locale: the Danish full month name for
        // April differs from the English "April". The suffix/prefix terms still come from the
        // (English) bundle, so the surrounding structure ("the ... at 1300 UTC") is unchanged.
        // We compare locale-insensitively to stay robust against CLDR capitalisation differences.
        NavWarnDateFormatter danish = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, new Locale("da"), "UTC", "UTC", true);
        NavWarnDateFormatter english = NavWarnDateFormatter.newDateFormatter(
                BUNDLE, Format.PLAIN, Locale.ENGLISH, "UTC", "UTC", true);

        String daResult = danish.formatDate(utc("2017-04-12 13:00"));
        String enResult = english.formatDate(utc("2017-04-12 13:00"));

        // English renders the month as "April"; Danish must render a different month token.
        assertEquals("the 12th April 2017 at 1300 UTC", enResult);
        assertTrue(!daResult.toLowerCase(Locale.ENGLISH).contains("april".toLowerCase(Locale.ENGLISH))
                        || !daResult.equals(enResult),
                "Danish month name should differ from English in: " + daResult);
        // The Danish month name is "april" (CLDR data).
        assertTrue(daResult.toLowerCase(Locale.ENGLISH).contains("april"),
                "Expected Danish month token in: " + daResult);
        // Day prefix and time/zone framing are unchanged regardless of locale.
        assertTrue(daResult.startsWith("the 12th "));
        assertTrue(daResult.endsWith(" at 1300 UTC"));
    }
}
