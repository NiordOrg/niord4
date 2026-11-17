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

import freemarker.template.TemplateException;
import org.junit.jupiter.api.Test;
import org.niord.model.message.DateIntervalVo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;
import static org.niord.core.script.directive.DirectiveTestSupport.utc;

/**
 * Framework-neutral Tier-1 unit tests for {@link DateIntervalDirective}, the Freemarker directive that
 * renders single dates, single date intervals and lists of date intervals in Navigational Warning form.
 * <p>
 * The directive is exercised through a real Freemarker {@link freemarker.template.Configuration} via
 * {@link DirectiveTestSupport}, which mirrors the production {@code text} resource-bundle model and the
 * {@code timeZone="UTC"} env entry that {@code FmTemplateService} always injects. The directive delegates
 * to {@code NavWarnDateFormatter}; because the {@code fm-test-messages_en} bundle carries the same
 * {@code navwarn.date.*} values as the application bundle, the formatter oracles transfer verbatim.
 * <p>
 * Key behaviours pinned here:
 * <ul>
 *   <li>the time-zone label is shown only when a non-blank {@code tz} param is supplied; otherwise the
 *       env {@code timeZone} ("UTC") drives the wall clock but the label is omitted;</li>
 *   <li>{@code format} selects PLAIN / NAVTEX / HTML rendering and {@code capFirst} capitalises;</li>
 *   <li>omitting all of {@code date}/{@code dateInterval}/{@code dateIntervals} throws a
 *       {@link TemplateException} (the guard is hit before the swallowing try/catch).</li>
 * </ul>
 * No Quarkus, database or Docker is involved, so these tests survive a future Spring migration.
 */
public class DateIntervalDirectiveTest {

    /** Builds a {@link DateIntervalVo} with the given from/to dates and all-day flag. */
    private static DateIntervalVo di(Date from, Date to, Boolean allDay) {
        DateIntervalVo d = new DateIntervalVo();
        d.setFromDate(from);
        d.setToDate(to);
        d.setAllDay(allDay);
        return d;
    }

    // ------------------------------------------------------------------
    //  Single "date" parameter
    // ------------------------------------------------------------------

    @Test
    public void singleDate_noTz_rendersPlainWallClockWithoutZoneLabel() throws Exception {
        // Without a tz param the env "UTC" zone drives the wall clock but no zone label is emitted.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("the 12th April 2017 at 1300", render("<@d date=date/>", data));
    }

    @Test
    public void singleDate_withTz_appendsZoneLabel() throws Exception {
        // A non-blank tz param turns on the zone label appended after the time.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("the 12th April 2017 at 1300 UTC", render("<@d date=date tz=\"UTC\"/>", data));
    }

    @Test
    public void singleDate_navtexFormat_rendersCompactUppercaseForm() throws Exception {
        // format="navtex" produces the compact uppercase ddHHmm 'UTC' MMM yy NAVTEX form.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("121300 UTC APR 17", render("<@d date=date format=\"navtex\"/>", data));
    }

    @Test
    public void singleDate_htmlFormatWithTz_wrapsSuffixInSupTag() throws Exception {
        // format="html" wraps the day-suffix in a <sup> tag; tz keeps the zone label.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("the 12<sup>th</sup> April 2017 at 1300 UTC",
                render("<@d date=date format=\"html\" tz=\"UTC\"/>", data));
    }

    @Test
    public void singleDate_capFirstTrue_capitalisesFirstCharacter() throws Exception {
        // capFirst=true capitalises the leading character ("the" -> "The").
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("The 12th April 2017 at 1300", render("<@d date=date capFirst=true/>", data));
    }

    @Test
    public void singleDate_tzOverrideGmtPlus1_shiftsWallClockAndUsesOverrideLabel() throws Exception {
        // An explicit tz param overrides the env zone: 13:00 UTC becomes 14:00 GMT+1 with that label.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2017-04-12 13:00"));
        assertEquals("the 12th April 2017 at 1400 GMT+1", render("<@d date=date tz=\"GMT+1\"/>", data));
    }

    // ------------------------------------------------------------------
    //  Single "dateInterval" parameter
    // ------------------------------------------------------------------

    @Test
    public void dateInterval_sameDayTimedWithTz_rendersTimeRangeWithZoneLabel() throws Exception {
        // A same-day timed interval renders a single date with a "from .. to .." time range.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(utc("2017-04-12 13:00"), utc("2017-04-12 13:55"), false));
        assertEquals("the 12th April 2017 from 1300 to 1355 UTC",
                render("<@d dateInterval=iv tz=\"UTC\"/>", data));
    }

    @Test
    public void dateInterval_differentDayTimedWithTz_rendersFromToWithBothDates() throws Exception {
        // A timed interval spanning different days renders both full dates in a from/to clause.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(utc("2017-04-12 13:00"), utc("2017-04-16 13:55"), false));
        assertEquals("from the 12th April 2017 at 1300 UTC to the 16th April 2017 at 1355 UTC",
                render("<@d dateInterval=iv tz=\"UTC\"/>", data));
    }

    @Test
    public void dateInterval_allDaySameDayNoTz_rendersDateOnly() throws Exception {
        // An all-day same-day interval renders just the date, with no time and no zone label.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(utc("2017-04-12 00:00"), utc("2017-04-12 00:00"), true));
        assertEquals("the 12th April 2017", render("<@d dateInterval=iv/>", data));
    }

    @Test
    public void dateInterval_bothDatesNull_rendersUntilFurtherNotice() throws Exception {
        // An interval with no from and no to date renders the "until further notice" phrase.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(null, null, false));
        assertEquals("until further notice", render("<@d dateInterval=iv/>", data));
    }

    @Test
    public void dateInterval_fromOnlyTimedWithTz_rendersFromClause() throws Exception {
        // A from-only timed interval renders only the "from .." clause with the zone label.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(utc("2017-04-12 13:00"), null, false));
        assertEquals("from the 12th April 2017 at 1300 UTC",
                render("<@d dateInterval=iv tz=\"UTC\"/>", data));
    }

    @Test
    public void dateInterval_toOnlyTimedWithTz_rendersUntilClause() throws Exception {
        // A to-only timed interval renders only the "until .." clause with the zone label.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(null, utc("2017-04-12 13:00"), false));
        assertEquals("until the 12th April 2017 at 1300 UTC",
                render("<@d dateInterval=iv tz=\"UTC\"/>", data));
    }

    @Test
    public void dateInterval_navtexDifferentDayTimed_rendersDashSeparatedUppercase() throws Exception {
        // NAVTEX renders a different-day timed interval as two compact dates joined by " - ".
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        data.put("iv", di(utc("2017-04-12 13:00"), utc("2017-04-16 13:55"), false));
        assertEquals("121300 UTC APR 17 - 161355 UTC APR 17",
                render("<@d dateInterval=iv format=\"navtex\"/>", data));
    }

    // ------------------------------------------------------------------
    //  "dateIntervals" list parameter
    // ------------------------------------------------------------------

    @Test
    public void dateIntervals_twoFromOnlyTimedNoTz_joinedWithAnd() throws Exception {
        // A list of two from-only timed intervals is joined with " and " (no zone label without tz).
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        List<DateIntervalVo> list = new ArrayList<>();
        list.add(di(utc("2017-04-12 13:00"), null, false));
        list.add(di(utc("2017-04-16 13:55"), null, false));
        data.put("list", list);
        assertEquals("from the 12th April 2017 at 1300 and from the 16th April 2017 at 1355",
                render("<@d dateIntervals=list/>", data));
    }

    // ------------------------------------------------------------------
    //  Missing required parameter
    // ------------------------------------------------------------------

    @Test
    public void noDateParam_throwsTemplateException() throws Exception {
        // Omitting date/dateInterval/dateIntervals trips the guard before the swallowing try/catch,
        // so a TemplateException propagates out of rendering.
        Map<String, Object> data = data();
        data.put("d", new DateIntervalDirective());
        try {
            render("<@d/>", data);
            fail("Expected a TemplateException when no date parameter is supplied");
        } catch (TemplateException expected) {
            // expected
        }
    }
}
