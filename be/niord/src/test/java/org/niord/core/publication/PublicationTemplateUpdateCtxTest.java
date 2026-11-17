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
package org.niord.core.publication;

import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link PublicationTemplateUpdateCtx}: the deterministic ${...} placeholder
 * expansion performed by {@code str(...)}, the null/default fall-back rules of {@code str(...)}
 * and {@code val(...)}, and the constructor's derivation of the replacement-value map from a
 * publication's {@code edition} and {@code publishDateFrom}.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. The
 * {@link org.niord.core.message.MessageTagService} dependency is passed as {@code null}: none of
 * the constructor / {@code str} / {@code val} code paths under test ever touch it (only the
 * separate {@code findOrCreatePublicMessageTag} method does, which is not exercised here).
 *
 * <p>The anchor date is fixed to <b>2015-02-09</b> via {@link Calendar} so the derived ISO-8601
 * week (7), the one-based month (2) and the zero-padded two-digit forms (week "07", month "02")
 * are deterministic and independent of when the suite runs.
 */
public class PublicationTemplateUpdateCtxTest {

    /** Edition used for the ${edition} expansion assertions. */
    private static final Integer EDITION = 42;

    /**
     * Builds the fixed anchor date 2015-02-09 12:00:00.
     * For this date: ISO week = 7, month value (Calendar.MONTH + 1) = 2, year = 2015.
     */
    private static Date anchorDate() {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2015, Calendar.FEBRUARY, 9, 12, 0, 0);
        return cal.getTime();
    }

    /** A publication carrying the fixed edition and anchor publish-date. */
    private static Publication publicationWithDate() {
        Publication p = new Publication();
        p.setEdition(EDITION);
        p.setPublishDateFrom(anchorDate());
        return p;
    }

    // --------------------------------------------------------------------------------------------- //
    // str(...) — placeholder expansion
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void strExpandsYearWeekMonthAndEditionPlaceholders() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // Every supported placeholder, in one template, expanded against 2015-02-09 / edition 42.
        String template = "${year}/${week}/${week-2-digits}/${month}/${month-2-digits}/${edition}";
        assertEquals("2015/7/07/2/02/42", ctx.str(template, null));
    }

    @Test
    public void strExpandsTwoDigitYearPlaceholder() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // ${year-2-digits} drops the century: 2015 -> "15".
        assertEquals("NW-15-07", ctx.str("NW-${year-2-digits}-${week-2-digits}", null));
    }

    @Test
    public void strZeroPadsSingleDigitWeekAndMonth() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // Week 7 and month 2: the plain forms are single-digit, the *-2-digits forms are padded.
        assertEquals("7", ctx.str("${week}", null));
        assertEquals("07", ctx.str("${week-2-digits}", null));
        assertEquals("2", ctx.str("${month}", null));
        assertEquals("02", ctx.str("${month-2-digits}", null));
    }

    @Test
    public void strLeavesValueWithoutPlaceholdersUnchanged() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // No ${...} markers -> the value is returned verbatim, even though replacements exist.
        assertEquals("Plain title - no markers", ctx.str("Plain title - no markers", "fallback"));
    }

    // --------------------------------------------------------------------------------------------- //
    // str(...) — blank value / default-value fall-back
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void strReturnsDefaultValueForBlankValue() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // Blank (whitespace-only) value with a non-blank default -> the default is returned.
        // NB: the default itself is NOT placeholder-expanded.
        assertEquals("My default ${week}", ctx.str("   ", "My default ${week}"));
        assertEquals("My default ${week}", ctx.str(null, "My default ${week}"));
    }

    @Test
    public void strReturnsBlankValueUnchangedWhenDefaultAlsoBlank() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // Blank value AND blank default: neither branch substitutes, so the value is returned as-is.
        assertEquals("   ", ctx.str("   ", "   "));
        assertNull(ctx.str(null, null));
        assertEquals("", ctx.str("", ""));
    }

    // --------------------------------------------------------------------------------------------- //
    // Constructor — replacement-value map derivation
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorWithNullPublishDateLeavesDatePlaceholdersLiteral() {
        Publication p = new Publication();
        p.setEdition(EDITION);
        // publishDateFrom left null -> no year/week/month entries are added to the map.

        PublicationTemplateUpdateCtx ctx = new PublicationTemplateUpdateCtx(p, null);

        // ${edition} still expands (always added), but the date placeholders stay literal.
        assertEquals("ed-42 wk-${week} mo-${month}",
                ctx.str("ed-${edition} wk-${week} mo-${month}", null));
    }

    @Test
    public void constructorWithNullEditionFallsBackToDefaultEdition() {
        Publication p = new Publication();
        // edition left null -> the constructor substitutes Publication.DEFAULT_EDITION (= 1).
        p.setPublishDateFrom(anchorDate());

        PublicationTemplateUpdateCtx ctx = new PublicationTemplateUpdateCtx(p, null);

        assertEquals(Integer.valueOf(1), Publication.DEFAULT_EDITION);
        assertEquals("edition=1", ctx.str("edition=${edition}", null));
    }

    // --------------------------------------------------------------------------------------------- //
    // val(...) — null fall-back
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void valReturnsValueWhenPresentElseDefault() {
        PublicationTemplateUpdateCtx ctx =
                new PublicationTemplateUpdateCtx(publicationWithDate(), null);

        // Non-null value is returned untouched; null value yields the supplied default.
        assertEquals("actual", ctx.val("actual", "fallback"));
        assertEquals("fallback", ctx.val(null, "fallback"));
        assertEquals(Integer.valueOf(7), ctx.val(7, 99));
        assertNull(ctx.val(null, null));
    }
}
