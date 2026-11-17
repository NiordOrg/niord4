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
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.model.publication.PublicationType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PublicationSearchParams} — the chained search-criteria builder used by the
 * publication search service, plus its {@link PublicationSearchParams#toString()} description.
 *
 * <p>These are framework-neutral plain JUnit 4 tests (no Quarkus, no DB, no mocks) following the
 * conventions of the sibling {@code PublicationUtilsTest}. They assert concrete {@code toString()}
 * output (exact labels, separators and {@code dd-MM-yyyy} formatting), the de-duplication semantics
 * of the {@code statuses} setters, and the {@code Long}/{@code millis} handling of {@code from}.
 *
 * <p>To keep the {@code dd-MM-yyyy} assertions deterministic across machines, every test parses its
 * own fixed date with the production {@code DATE_FORMAT} pattern and feeds the resulting instant
 * back in, so the round-trip is independent of the local time zone's intra-day offset.
 *
 * <p>{@code toString()} renders fields whose iteration order is unstable (a {@code HashSet} of
 * statuses) — therefore every full-string assertion uses at most a single status so the rendered
 * description is deterministic.
 */
public class PublicationSearchParamsTest {

    /** Parses a {@code dd-MM-yyyy} date the same way the production formatter renders it. */
    private static Date date(String ddMMyyyy) {
        try {
            return new SimpleDateFormat(PublicationSearchParams.DATE_FORMAT).parse(ddMMyyyy);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------------------------------------- //
    // toString
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void emptyParamsToStringIsEmpty() {
        // A brand-new instance has only an (empty) statuses set and no other criteria.
        assertEquals("", new PublicationSearchParams().toString());
    }

    @Test
    public void toStringRendersEachFieldWithItsLabelInDeclarationOrder() {
        PublicationSearchParams p = new PublicationSearchParams()
                .messagePublication(MessagePublication.EXTERNAL)
                .mainType(PublicationMainType.PUBLICATION)
                .type(PublicationType.LINK)
                .statuses(PublicationStatus.ACTIVE)        // single value -> deterministic rendering
                .published(Boolean.TRUE)
                .language("en")
                .category("nm")
                .title("J.no")
                .from(date("01-02-2020"))
                .to(date("31-12-2020"));

        // Fields are appended in the order coded in toString() and joined by ", ".
        // Note: domain is omitted here to keep this ordered assertion focused; the single-domain
        // rendering is covered separately in toStringRendersDomainExactlyOnce.
        assertEquals(
                "MessagePublication: EXTERNAL, "
                        + "Main Type: PUBLICATION, "
                        + "Type: LINK, "
                        + "Statuses: [ACTIVE], "
                        + "Published: true, "
                        + "Language: en, "
                        + "Category: nm, "
                        + "Title: 'J.no', "
                        + "From: 01-02-2020, "
                        + "To: 31-12-2020",
                p.toString());
    }

    @Test
    public void toStringFormatsDatesAsDdMmYyyy() {
        PublicationSearchParams p = new PublicationSearchParams()
                .from(date("09-03-2021"))
                .to(date("25-11-2021"));

        // Only the two date fields are set, so the whole description is the two date clauses.
        assertEquals("From: 09-03-2021, To: 25-11-2021", p.toString());
    }

    @Test
    public void toStringOmitsBlankTitleLanguageDomainAndCategory() {
        PublicationSearchParams p = new PublicationSearchParams()
                .title("   ")        // blank -> isNotBlank(...) == false -> omitted
                .language("")        // empty -> omitted
                .domain(null)        // null  -> omitted
                .category("  \t ");  // whitespace -> omitted

        // None of the blank string fields contribute, and nothing else is set.
        assertEquals("", p.toString());
    }

    @Test
    public void toStringIncludesPublishedTrueAndFalseAndOmitsWhenNull() {
        assertEquals("Published: true",
                new PublicationSearchParams().published(Boolean.TRUE).toString());
        assertEquals("Published: false",
                new PublicationSearchParams().published(Boolean.FALSE).toString());
        // published == null -> the clause is dropped entirely.
        assertEquals("",
                new PublicationSearchParams().published(null).toString());
    }

    /**
     * Fixed (docs/potential-errors.md PSP1): the duplicate "Domain:" clause was removed, so a set
     * domain is rendered exactly once (as "Domain: %s", consistent with the Language/Category labels).
     * Previously it was emitted twice with two different label formats.
     */
    @Test
    public void toStringRendersDomainExactlyOnce() {
        PublicationSearchParams p = new PublicationSearchParams().domain("niord-nw");

        assertEquals("Domain: niord-nw", p.toString());
    }

    // --------------------------------------------------------------------------------------------- //
    // from / to value handling
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void fromLongNullLeavesFromNullWhileFromMillisYieldsThatInstant() {
        // from(Long) with null -> from stays null.
        PublicationSearchParams nullFrom = new PublicationSearchParams().from((Long) null);
        assertNull(nullFrom.getFrom());
        assertEquals("", nullFrom.toString());

        // from(Long) with a millis value -> new Date(millis).
        long millis = 1_580_000_000_000L;
        PublicationSearchParams setFrom = new PublicationSearchParams().from(millis);
        assertEquals(new Date(millis), setFrom.getFrom());
        assertEquals(millis, setFrom.getFrom().getTime());
    }

    // --------------------------------------------------------------------------------------------- //
    // statuses semantics
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void statusesVarargsDeDuplicatesRepeatedValues() {
        PublicationSearchParams p = new PublicationSearchParams()
                .statuses(PublicationStatus.ACTIVE, PublicationStatus.ACTIVE, PublicationStatus.DRAFT);

        // The varargs overload funnels through toSet(...), so duplicates collapse.
        assertEquals(2, p.getStatuses().size());
        Set<PublicationStatus> expected = new HashSet<>();
        expected.add(PublicationStatus.ACTIVE);
        expected.add(PublicationStatus.DRAFT);
        assertEquals(expected, p.getStatuses());
    }

    @Test
    public void statusesSetIsStoredByReferenceAsIs() {
        Set<PublicationStatus> set = new HashSet<>();
        set.add(PublicationStatus.INACTIVE);

        PublicationSearchParams p = new PublicationSearchParams().statuses(set);

        // The Set overload stores the very instance handed in (no defensive copy).
        assertSame(set, p.getStatuses());

        // Mutating the original therefore shows through the params (documents the reference sharing).
        set.add(PublicationStatus.RECORDING);
        assertEquals(2, p.getStatuses().size());
        assertTrue(p.getStatuses().contains(PublicationStatus.RECORDING));
    }

    @Test
    public void emptyStatusesSetIsOmittedFromToStringButSingleStatusIsRendered() {
        // An explicitly empty set -> the Statuses clause is skipped.
        PublicationSearchParams empty = new PublicationSearchParams().statuses(new HashSet<>());
        assertFalse(empty.toString().contains("Statuses"));
        assertEquals("", empty.toString());

        // A single status renders with the set's bracketed form.
        PublicationSearchParams one = new PublicationSearchParams().statuses(PublicationStatus.INACTIVE);
        assertEquals("Statuses: [INACTIVE]", one.toString());
    }
}
