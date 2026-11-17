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
import org.niord.core.domain.Domain;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MessageIdMatchComparator}, the {@link java.util.Comparator} used when several
 * messages match the same message ID and a single best match must be ranked first.
 *
 * <p>The comparator enforces two rules, in priority order:
 * <ol>
 *     <li>A message whose message series belongs to the comparator's domain is preferred over one that
 *         does not (regardless of status).</li>
 *     <li>When both (or neither) messages are in-domain, the message with the higher status sort order
 *         is preferred: PUBLISHED(4) &gt; EXPIRED/CANCELLED(3) &gt; DRAFT/VERIFIED(2) &gt; DELETED(1) &gt;
 *         no/null status(0).</li>
 *     <li>When the domain and the status rank tie, the newer {@code publishDateFrom} is preferred (a message
 *         without one last), then the higher id (a message without one last), so a short id two messages share
 *         resolves to the same message whatever order the database returns them in (MSG11). Messages built
 *         without a date or an id still tie.</li>
 * </ol>
 *
 * <p>These tests are deliberately framework-neutral: they use plain JUnit 4 and real domain objects
 * constructed with {@code new} + setters, with no Quarkus, Spring, CDI, persistence or mocking
 * dependencies. They assert observable comparator behaviour (sign of the result and resulting sort
 * order), so they are expected to survive a future Quarkus-&gt;Spring port unchanged.
 */
public class MessageIdMatchComparatorTest {

    private static final String IN_DOMAIN_SERIES = "dma-nw";
    private static final String OUT_OF_DOMAIN_SERIES = "other";

    /** Builds a comparator whose domain contains the in-domain message series. */
    private MessageIdMatchComparator comparatorWithDomain() {
        Domain domain = new Domain();
        domain.getMessageSeries().add(new MessageSeries(IN_DOMAIN_SERIES));
        return new MessageIdMatchComparator(domain);
    }

    /** Builds a message with the given series id (or none) and status (or none). */
    private Message message(String seriesId, Status status) {
        Message m = new Message();
        if (seriesId != null) {
            m.setMessageSeries(new MessageSeries(seriesId));
        }
        if (status != null) {
            m.setStatus(status);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Identity and null handling
    // ------------------------------------------------------------------

    @Test
    public void identicalReferenceReturnsZero() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message m = message(IN_DOMAIN_SERIES, Status.PUBLISHED);

        // Same instance on both sides must compare equal regardless of in-domain/status.
        assertEquals(0, cmp.compare(m, m));
    }

    @Test
    public void firstArgumentNullSortsBeforeNonNull() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message m = message(IN_DOMAIN_SERIES, Status.PUBLISHED);

        assertTrue(cmp.compare(null, m) < 0);
    }

    @Test
    public void secondArgumentNullSortsAfterNonNull() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message m = message(IN_DOMAIN_SERIES, Status.PUBLISHED);

        assertTrue(cmp.compare(m, null) > 0);
    }

    @Test
    public void bothNullReturnsZero() {
        MessageIdMatchComparator cmp = comparatorWithDomain();

        // Two nulls are the identical reference (== branch) -> 0.
        assertEquals(0, cmp.compare(null, null));
    }

    // ------------------------------------------------------------------
    // Current-domain preference (rule 1) overrides status (rule 2)
    // ------------------------------------------------------------------

    @Test
    public void inDomainMessageIsPreferredEvenWithLowerStatus() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message inDomainDraft = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message outOfDomainPublished = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);

        // In-domain DRAFT must still beat out-of-domain PUBLISHED.
        assertTrue(cmp.compare(inDomainDraft, outOfDomainPublished) < 0);
    }

    @Test
    public void outOfDomainMessageSortsAfterInDomainMessage_symmetric() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message inDomainDraft = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message outOfDomainPublished = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);

        // Swapping the arguments must flip the sign.
        assertTrue(cmp.compare(outOfDomainPublished, inDomainDraft) > 0);
    }

    @Test
    public void bothInDomainFallsBackToStatusOrder() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message published = message(IN_DOMAIN_SERIES, Status.PUBLISHED);
        Message draft = message(IN_DOMAIN_SERIES, Status.DRAFT);

        // Both in-domain -> higher status (PUBLISHED) sorts first.
        assertTrue(cmp.compare(published, draft) < 0);
        assertTrue(cmp.compare(draft, published) > 0);
    }

    @Test
    public void messageWithoutSeriesIsTreatedAsOutOfDomain() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message inDomain = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message noSeries = message(null, Status.PUBLISHED);

        // A message with a null series can never be in-domain, so in-domain wins.
        assertTrue(cmp.compare(inDomain, noSeries) < 0);
        assertTrue(cmp.compare(noSeries, inDomain) > 0);
    }

    // ------------------------------------------------------------------
    // Status order (rule 2) when both are out of domain
    // ------------------------------------------------------------------

    @Test
    public void bothOutOfDomainHigherStatusSortsFirst() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message published = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);
        Message draft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT);

        assertTrue(cmp.compare(published, draft) < 0);
        assertTrue(cmp.compare(draft, published) > 0);
    }

    @Test
    public void bothOutOfDomainEqualStatusReturnsZero() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message a = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);
        Message b = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);

        // Same status (both 4) and both out of domain -> equal.
        assertEquals(0, cmp.compare(a, b));
    }

    @Test
    public void expiredAndCancelledShareTheSameStatusOrder() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message expired = message(OUT_OF_DOMAIN_SERIES, Status.EXPIRED);
        Message cancelled = message(OUT_OF_DOMAIN_SERIES, Status.CANCELLED);

        // EXPIRED and CANCELLED both map to 3 -> equal ranking.
        assertEquals(0, cmp.compare(expired, cancelled));
    }

    @Test
    public void draftAndVerifiedShareTheSameStatusOrder() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message draft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT);
        Message verified = message(OUT_OF_DOMAIN_SERIES, Status.VERIFIED);

        // DRAFT and VERIFIED both map to 2 -> equal ranking.
        assertEquals(0, cmp.compare(draft, verified));
    }

    @Test
    public void deletedSortsAfterDraft() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message draft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT);
        Message deleted = message(OUT_OF_DOMAIN_SERIES, Status.DELETED);

        // DRAFT(2) > DELETED(1).
        assertTrue(cmp.compare(draft, deleted) < 0);
        assertTrue(cmp.compare(deleted, draft) > 0);
    }

    // ------------------------------------------------------------------
    // null/none status edge cases
    // ------------------------------------------------------------------

    @Test
    public void nullStatusSortsLastAmongOutOfDomainMessages() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message published = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);
        Message noStatus = message(OUT_OF_DOMAIN_SERIES, null);

        // A message with no status has sort order 0, so it ranks last.
        assertTrue(cmp.compare(published, noStatus) < 0);
        assertTrue(cmp.compare(noStatus, published) > 0);
    }

    @Test
    public void bothNullStatusReturnsZero() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message a = message(OUT_OF_DOMAIN_SERIES, null);
        Message b = message(OUT_OF_DOMAIN_SERIES, null);

        assertEquals(0, cmp.compare(a, b));
    }

    @Test
    public void deletedStillBeatsNoStatus() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message deleted = message(OUT_OF_DOMAIN_SERIES, Status.DELETED);
        Message noStatus = message(OUT_OF_DOMAIN_SERIES, null);

        // DELETED(1) > no status(0).
        assertTrue(cmp.compare(deleted, noStatus) < 0);
        assertTrue(cmp.compare(noStatus, deleted) > 0);
    }

    // ------------------------------------------------------------------
    // Null-domain comparator: domain preference can never trigger
    // ------------------------------------------------------------------

    @Test
    public void nullDomainComparatorFallsBackToPureStatusOrder() {
        // A null domain produces an empty series set, so no message is ever "in domain".
        MessageIdMatchComparator cmp = new MessageIdMatchComparator(null);
        Message dmaPublished = message(IN_DOMAIN_SERIES, Status.PUBLISHED);
        Message otherDraft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT);

        // Series no longer matters; PUBLISHED(4) > DRAFT(2).
        assertTrue(cmp.compare(dmaPublished, otherDraft) < 0);
        assertTrue(cmp.compare(otherDraft, dmaPublished) > 0);
    }

    @Test
    public void nullDomainComparatorTreatsFormerlyInDomainSeriesAsOutOfDomain() {
        MessageIdMatchComparator cmp = new MessageIdMatchComparator(null);
        Message dmaDraft = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message dmaPublished = message(IN_DOMAIN_SERIES, Status.PUBLISHED);

        // Even though both are in the dma-nw series, the empty domain means status alone decides.
        assertTrue(cmp.compare(dmaPublished, dmaDraft) < 0);
    }

    // ------------------------------------------------------------------
    // End-to-end: sorting a list with the comparator
    // ------------------------------------------------------------------

    @Test
    public void sortingRanksInDomainFirstThenByStatus() {
        MessageIdMatchComparator cmp = comparatorWithDomain();

        Message outPublished = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);
        Message inDraft = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message inPublished = message(IN_DOMAIN_SERIES, Status.PUBLISHED);
        Message outDraft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT);

        List<Message> messages = new ArrayList<>();
        messages.add(outPublished);
        messages.add(inDraft);
        messages.add(inPublished);
        messages.add(outDraft);

        messages.sort(cmp);

        // In-domain messages first (PUBLISHED before DRAFT), then out-of-domain (PUBLISHED before DRAFT).
        assertSame(inPublished, messages.get(0));
        assertSame(inDraft, messages.get(1));
        assertSame(outPublished, messages.get(2));
        assertSame(outDraft, messages.get(3));
    }

    @Test
    public void sortingWithNullDomainOrdersPurelyByStatus() {
        MessageIdMatchComparator cmp = new MessageIdMatchComparator(null);

        Message draft = message(IN_DOMAIN_SERIES, Status.DRAFT);
        Message published = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED);
        Message deleted = message(IN_DOMAIN_SERIES, Status.DELETED);
        Message expired = message(OUT_OF_DOMAIN_SERIES, Status.EXPIRED);

        List<Message> messages = new ArrayList<>();
        messages.add(draft);
        messages.add(published);
        messages.add(deleted);
        messages.add(expired);

        messages.sort(cmp);

        // Pure status order: PUBLISHED(4) > EXPIRED(3) > DRAFT(2) > DELETED(1).
        assertSame(published, messages.get(0));
        assertSame(expired, messages.get(1));
        assertSame(draft, messages.get(2));
        assertSame(deleted, messages.get(3));
    }

    // ------------------------------------------------------------------
    // The final tie-break (rule 3): newest publish date, then highest id (MSG11)
    // ------------------------------------------------------------------

    /** Builds a message of the given series and status, with a publish date (or none) and an id (or none). */
    private Message message(String seriesId, Status status, Long publishDateFrom, Integer id) {
        Message m = message(seriesId, status);
        m.setPublishDateFrom(publishDateFrom == null ? null : new Date(publishDateFrom));
        m.setId(id);
        return m;
    }

    private static final long OLDER = 1_700_000_000_000L;
    private static final long NEWER = OLDER + 86_400_000L;

    @Test
    public void equalRankPrefersTheNewerPublishDate() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message older = message(IN_DOMAIN_SERIES, Status.PUBLISHED, OLDER, 7);
        Message newer = message(IN_DOMAIN_SERIES, Status.PUBLISHED, NEWER, 3);

        // The date decides before the id: the newer message wins although its id is lower.
        assertTrue(cmp.compare(newer, older) < 0);
        assertTrue(cmp.compare(older, newer) > 0);
    }

    @Test
    public void tiedStatusesArePutInOrderByPublishDate() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        // EXPIRED and CANCELLED share a rank, so the date decides between them.
        Message expired = message(OUT_OF_DOMAIN_SERIES, Status.EXPIRED, OLDER, 1);
        Message cancelled = message(OUT_OF_DOMAIN_SERIES, Status.CANCELLED, NEWER, 2);

        assertTrue(cmp.compare(cancelled, expired) < 0);
        assertTrue(cmp.compare(expired, cancelled) > 0);
    }

    @Test
    public void equalRankPutsAMessageWithoutPublishDateLast() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message dated = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT, OLDER, 1);
        Message undated = message(OUT_OF_DOMAIN_SERIES, Status.VERIFIED, null, 9);

        assertTrue(cmp.compare(dated, undated) < 0);
        assertTrue(cmp.compare(undated, dated) > 0);
    }

    @Test
    public void equalRankAndDatePrefersTheHigherId() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message low = message(IN_DOMAIN_SERIES, Status.PUBLISHED, OLDER, 4);
        Message high = message(IN_DOMAIN_SERIES, Status.PUBLISHED, OLDER, 12);

        assertTrue(cmp.compare(high, low) < 0);
        assertTrue(cmp.compare(low, high) > 0);
    }

    @Test
    public void equalRankAndNoDatePrefersTheHigherIdAndPutsNoIdLast() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message low = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT, null, 4);
        Message high = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT, null, 12);
        Message unsaved = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT, null, null);

        assertTrue(cmp.compare(high, low) < 0);
        assertTrue(cmp.compare(low, unsaved) < 0);
        assertTrue(cmp.compare(unsaved, low) > 0);
    }

    @Test
    public void theTieBreakDoesNotOverrideStatusOrDomain() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        Message oldPublished = message(OUT_OF_DOMAIN_SERIES, Status.PUBLISHED, OLDER, 1);
        Message newDraft = message(OUT_OF_DOMAIN_SERIES, Status.DRAFT, NEWER, 99);
        Message oldInDomainDraft = message(IN_DOMAIN_SERIES, Status.DRAFT, OLDER, 1);

        // The status still decides first, and the domain before it.
        assertTrue(cmp.compare(oldPublished, newDraft) < 0);
        assertTrue(cmp.compare(oldInDomainDraft, oldPublished) < 0);
    }

    @Test
    public void aSharedShortIdResolvesToTheSameMessageWhateverTheInputOrder() {
        MessageIdMatchComparator cmp = comparatorWithDomain();
        // Four candidates the domain and status cannot tell apart, as the lookup may return them in any order.
        Message a = message(IN_DOMAIN_SERIES, Status.EXPIRED, OLDER, 20);
        Message b = message(IN_DOMAIN_SERIES, Status.CANCELLED, NEWER, 10);
        Message c = message(IN_DOMAIN_SERIES, Status.CANCELLED, NEWER, 11);
        Message d = message(IN_DOMAIN_SERIES, Status.EXPIRED, null, 30);
        List<List<Message>> orders = List.of(
                List.of(a, b, c, d), List.of(d, c, b, a), List.of(b, d, a, c), List.of(c, a, d, b));

        for (List<Message> order : orders) {
            List<Message> messages = new ArrayList<>(order);
            messages.sort(cmp);
            // Newest date first, the higher id between equal dates, the undated one last.
            assertEquals(List.of(c, b, a, d), messages);
        }
    }
}
