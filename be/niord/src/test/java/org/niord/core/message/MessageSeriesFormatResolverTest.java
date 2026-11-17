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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.MessageSeriesService.FormatResolver;
import org.niord.model.message.MainType;
import org.niord.model.message.Type;

import java.util.Calendar;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Framework-neutral unit tests for {@link MessageSeriesService.FormatResolver}, the pure short-ID format
 * expander used by {@code MessageSeriesService.updateMessageIdsFromMessageSeries(...)}.
 *
 * <p>The resolver builds a fixed map of {@code ${...}} tokens from a {@link Message} (year/week derived from the
 * publish date, number, main-type, id, legacy-id, T/P marker) plus the application country from
 * {@link NiordApp#getCountry()}, and then {@link FormatResolver#resolveFormat(String)} performs literal
 * {@code String.replace} substitution (trimming the running result on every iteration).
 *
 * <p>These tests construct real {@link Message} objects via {@code new} + setters and mock only the external
 * {@link NiordApp} dependency (needed solely for {@code ${country}}). They assert observable outcomes (the
 * resolved string or a thrown exception) and intentionally avoid any Quarkus/CDI wiring, so they should survive
 * a future Quarkus-to-Spring port unchanged.
 */
public class MessageSeriesFormatResolverTest {

    /** A fixed date so the derived year/week are deterministic regardless of when the test runs.
     *  2023-06-15 is a Thursday in ISO-8601 week 24. */
    private static java.util.Date date(int year, int month, int day) {
        return new GregorianCalendar(year, month, day).getTime();
    }

    /** Mocks a NiordApp whose getCountry() returns the given value. */
    private static NiordApp appWithCountry(String country) {
        NiordApp app = mock(NiordApp.class);
        when(app.getCountry()).thenReturn(country);
        return app;
    }

    /** Builds a message with a publish date (required by the resolver), a number and a main type. */
    private static Message messageWith(int year, int month, int day, Integer number, MainType mainType) {
        Message msg = new Message();
        msg.setPublishDateFrom(date(year, month, day));
        msg.setNumber(number);
        msg.setMainType(mainType);
        return msg;
    }

    // ------------------------------------------------------------------
    // (1) Token expansion: number, number-3-digits, year, year-2-digits, week
    // ------------------------------------------------------------------

    @Test
    public void resolveFormat_expandsNumberAndZeroPaddedNumber() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // ${number} is the raw number, ${number-3-digits} is zero-padded to 3 digits.
        assertEquals("7", resolver.resolveFormat("${number}"));
        assertEquals("007", resolver.resolveFormat("${number-3-digits}"));
    }

    @Test
    public void resolveFormat_expandsYearAndTwoDigitYear() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // ${year} is the full 4-digit year; ${year-2-digits} is the trailing two digits.
        assertEquals("2023", resolver.resolveFormat("${year}"));
        assertEquals("23", resolver.resolveFormat("${year-2-digits}"));
    }

    @Test
    public void resolveFormat_expandsIso8601WeekZeroPadded() {
        // 2023-01-05 is the Thursday of ISO-8601 week 1 => "01".
        Message msg = messageWith(2023, Calendar.JANUARY, 5, 3, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        assertEquals("01", resolver.resolveFormat("${week}"));
        assertEquals("01", resolver.resolveFormat("${week-2-digits}"));
    }

    @Test
    public void resolveFormat_expandsIdLegacyIdAndCountry() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        msg.setId(42);
        msg.setLegacyId("LEG-99");
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        assertEquals("42", resolver.resolveFormat("${id}"));
        assertEquals("LEG-99", resolver.resolveFormat("${legacy-id}"));
        assertEquals("dk", resolver.resolveFormat("${country}"));
    }

    @Test
    public void resolveFormat_expandsMainTypeFromExplicitMainType() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        assertEquals("NW", resolver.resolveFormat("${main-type}"));
        assertEquals("nw", resolver.resolveFormat("${main-type-lower}"));
    }

    @Test
    public void resolveFormat_derivesMainTypeFromTypeWhenMainTypeAbsent() {
        // mainType is left null; the resolver must fall back to type.getMainType().
        Message msg = new Message();
        msg.setPublishDateFrom(date(2023, Calendar.JUNE, 15));
        msg.setNumber(7);
        msg.setType(Type.PERMANENT_NOTICE); // PERMANENT_NOTICE -> MainType.NM
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        assertEquals("NM", resolver.resolveFormat("${main-type}"));
        assertEquals("nm", resolver.resolveFormat("${main-type-lower}"));
    }

    @Test
    public void resolveFormat_expandsTOrPMarkerForNoticeType() {
        Message tMsg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NM);
        tMsg.setType(Type.TEMPORARY_NOTICE);
        assertEquals("(T)",
                new FormatResolver(tMsg, appWithCountry("dk")).resolveFormat("${t-or-p}"));

        Message pMsg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NM);
        pMsg.setType(Type.PRELIMINARY_NOTICE);
        assertEquals("(P)",
                new FormatResolver(pMsg, appWithCountry("dk")).resolveFormat("${t-or-p}"));

        // A non-T/P type yields an empty marker.
        Message other = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NM);
        other.setType(Type.PERMANENT_NOTICE);
        assertEquals("",
                new FormatResolver(other, appWithCountry("dk")).resolveFormat("${t-or-p}"));
    }

    @Test
    public void resolveFormat_expandsCompositeFormatWithMultipleTokens() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // A realistic short-ID format combining several tokens; surrounding literals are preserved.
        assertEquals("NW-007-23",
                resolver.resolveFormat("${main-type}-${number-3-digits}-${year-2-digits}"));
    }

    // ------------------------------------------------------------------
    // (2) Unknown / unsupported tokens are left verbatim
    // ------------------------------------------------------------------

    @Test
    public void resolveFormat_leavesUnknownTokenUnchanged() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // ${number-2} and ${year-2} are NOT registered tokens (only -3-digits / -2-digits variants exist),
        // so they pass through literally.
        assertEquals("${number-2}", resolver.resolveFormat("${number-2}"));
        assertEquals("${year-2}", resolver.resolveFormat("${year-2}"));
        assertEquals("${totally-unknown}", resolver.resolveFormat("${totally-unknown}"));
    }

    // ------------------------------------------------------------------
    // (3) Null number edge / null publish-date edge
    // ------------------------------------------------------------------

    @Test
    public void resolveFormat_nullNumberExpandsToEmptyString() {
        // number==null => both ${number} and ${number-3-digits} expand to "".
        Message msg = messageWith(2023, Calendar.JUNE, 15, null, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // A bare ${number} becomes "" after the trailing trim().
        assertEquals("", resolver.resolveFormat("${number}"));
        assertEquals("", resolver.resolveFormat("${number-3-digits}"));
        // Embedded in literals the empty replacement leaves the surrounding text (then trimmed).
        assertEquals("NW--23",
                resolver.resolveFormat("${main-type}-${number}-${year-2-digits}"));
    }

    @Test
    public void resolveFormat_nullIdAndLegacyIdExpandToEmptyString() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        // id and legacyId are left null.
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        assertEquals("", resolver.resolveFormat("${id}"));
        assertEquals("", resolver.resolveFormat("${legacy-id}"));
    }

    @Test
    public void constructor_withNullPublishDate_throwsIllegalArgument() {
        // The resolver constructor guards against a missing publish date.
        Message msg = new Message();
        msg.setNumber(7);
        msg.setMainType(MainType.NW);
        // No publishDateFrom set.
        NiordApp app = appWithCountry("dk");

        assertThrows(IllegalArgumentException.class, () -> new FormatResolver(msg, app));
    }

    @Test
    public void constructor_withNullMainTypeAndNullType_throwsNpe() {
        // PINS CURRENT BEHAVIOUR: with both mainType and type null, the constructor dereferences
        // message.getType() (null) -> NullPointerException. The code does not guard this case.
        Message msg = new Message();
        msg.setPublishDateFrom(date(2023, Calendar.JUNE, 15));
        msg.setNumber(7);
        // mainType and type both left null.
        NiordApp app = appWithCountry("dk");

        assertThrows(NullPointerException.class, () -> new FormatResolver(msg, app));
    }

    // ------------------------------------------------------------------
    // (4) Literal / blank / null format handling
    // ------------------------------------------------------------------

    @Test
    public void resolveFormat_literalWithoutTokensReturnedTrimmed() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // A format with no tokens still goes through the replace loop (params is never empty),
        // each iteration trims, so surrounding whitespace is stripped but the content is unchanged.
        assertEquals("plain-literal", resolver.resolveFormat("  plain-literal  "));
    }

    @Test
    public void resolveFormat_nullFormatReturnedAsNull() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // isNotBlank(null) is false => the loop is skipped and the null is returned unchanged.
        assertEquals(null, resolver.resolveFormat(null));
    }

    @Test
    public void resolveFormat_blankFormatReturnedUnchanged() {
        Message msg = messageWith(2023, Calendar.JUNE, 15, 7, MainType.NW);
        FormatResolver resolver = new FormatResolver(msg, appWithCountry("dk"));

        // isNotBlank("   ") is false => no processing, the original (untrimmed) blank string is returned.
        assertEquals("   ", resolver.resolveFormat("   "));
    }
}
