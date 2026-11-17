/*
 * Copyright 2017 Danish Maritime Authority.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;
import static org.niord.core.script.directive.DirectiveTestSupport.utc;

/**
 * Framework-neutral Tier-1 unit tests for {@link NavtexDateFormatDirective}.
 * <p>
 * The directive renders its {@code date} parameter into the NAVTEX date format by delegating to
 * {@link org.niord.core.util.TimeUtils#formatNavtexDate(Date)}, which uses the pattern
 * {@code "ddHHmm 'UTC' MMM yy"} in {@link java.util.Locale#US} and the UTC time zone. The month is
 * rendered as the {@code Locale.US} short name and is NOT uppercased (e.g. {@code "Apr"}). When no
 * (or a null) date is supplied, the underlying formatter NPEs internally and both the formatter and
 * the directive swallow the exception ("Prefer robustness over correctness"), emitting an empty string.
 * <p>
 * These tests run through a real Freemarker {@link freemarker.template.Configuration} via
 * {@link DirectiveTestSupport} only, so they are independent of Quarkus and survive a Spring migration.
 */
public class NavtexDateFormatDirectiveTest {

    // A standard date renders as "ddHHmm 'UTC' MMM yy" in UTC with the (non-uppercased) US month name.
    @Test
    public void rendersStandardDateInNavtexFormat() throws Exception {
        Map<String, Object> data = data();
        data.put("d", new NavtexDateFormatDirective());
        data.put("date", utc("2017-04-12 13:00"));
        Assertions.assertEquals("121300 UTC Apr 17", render("<@d date=date/>", data));
    }

    // End-of-year boundary date renders with day=31, time=2359, month=Dec and two-digit year 20.
    @Test
    public void rendersEndOfYearBoundaryDate() throws Exception {
        Map<String, Object> data = data();
        data.put("d", new NavtexDateFormatDirective());
        data.put("date", utc("2020-12-31 23:59"));
        Assertions.assertEquals("312359 UTC Dec 20", render("<@d date=date/>", data));
    }

    // The month abbreviation is the US short form and is left untouched (not uppercased) for a single-digit day.
    @Test
    public void rendersSingleDigitDayWithNonUppercasedMonth() throws Exception {
        Map<String, Object> data = data();
        data.put("d", new NavtexDateFormatDirective());
        data.put("date", utc("2019-01-05 00:00"));
        Assertions.assertEquals("050000 UTC Jan 19", render("<@d date=date/>", data));
    }

    // Without a date parameter the directive swallows the internal NPE and emits an empty string.
    @Test
    public void emitsEmptyStringWhenNoDateParameterGiven() throws Exception {
        Map<String, Object> data = data();
        data.put("d", new NavtexDateFormatDirective());
        Assertions.assertEquals("", render("<@d/>", data));
    }

    // Output is computed in UTC: a Date one minute past midnight UTC renders day/time without time-zone shifting.
    @Test
    public void rendersInUtcWithoutTimeZoneShift() throws Exception {
        Map<String, Object> data = data();
        data.put("d", new NavtexDateFormatDirective());
        data.put("date", utc("2017-04-12 00:01"));
        Assertions.assertEquals("120001 UTC Apr 17", render("<@d date=date/>", data));
    }
}
