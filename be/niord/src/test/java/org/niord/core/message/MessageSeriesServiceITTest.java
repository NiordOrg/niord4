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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageSeriesService} CRUD and message-number generation, run against
 * the seeded PostgreSQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each CRUD test is {@code @Transactional}, so its writes to the {@code MessageSeries} table
 * are rolled back, keeping the shared seed database pristine. Test series use an {@code IT-} series-id
 * prefix that does not occur in the seed data (seeded series are {@code dma-nw}, {@code dma-nm}, ...),
 * so lookups stay isolated.
 *
 * <p>The table is not this class's alone: other IT classes in the same run commit series and leave
 * them (the publication-series ITs' {@code ms-<uuid>}), and Failsafe orders the classes as the file
 * system lists them. So no test asserts the table's total: a test that lists every series names the
 * seeded series it expects, and a test that creates or finds one compares with the count before it.
 *
 * <p>The number-generation tests deliberately exercise the seeded {@code dma-nw} series. A number is taken,
 * set and peeked inside the caller's transaction ({@code SequenceService.takeNextValue}, {@code resetNextValue}
 * and {@code peekNextValue} join it), so what these {@code @Transactional} tests do to a sequence rolls back with
 * the test. They still use far-future years of their own and set the sequence to a known value with
 * {@code setNextMessageNumber} before asserting, so a committed row another class left in such a year changes
 * nothing. Concurrent publications are in {@code MessageNumberConcurrencyITTest}.
 */
class MessageSeriesServiceITTest extends ITTest {

    /** The six message series of the seed data. */
    private static final List<String> SEEDED_SERIES_IDS = List.of(
            "dma-nw", "dma-nw-local", "dma-nm", "dma-nm-annex", "dma-nm-almanac", "dma-fa");

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached MessageSeries template with the given id and a YEARLY number sequence. */
    private MessageSeries newSeries(String seriesId, MainType mainType) {
        MessageSeries series = new MessageSeries(seriesId);
        series.setMainType(mainType);
        series.setNumberSequenceType(NumberSequenceType.YEARLY);
        return series;
    }

    // ------------------------------------------------------------------------------------------- //
    // createMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMessageSeriesPersistsAllFieldsWithGeneratedId() {
        MessageSeries series = newSeries("IT-create", MainType.NW);
        series.setShortFormat("NW-${number-3-digits}-${year-2-digits}");
        series.setNavtexFormat("NAVTEX-${number}");
        series.setNumberSequenceType(NumberSequenceType.CONTINUOUS);
        series.getTypes().add(Type.LOCAL_WARNING);
        series.getEditorFields().add("publishDateFrom");
        series.setExcludeFromMessageIndex(Boolean.TRUE);

        MessageSeries created = messageSeriesService.createMessageSeries(series);
        Integer id = created.getId();
        assertNotNull(id, "createMessageSeries must assign a generated id");

        em.flush();
        em.clear();

        MessageSeries reloaded = em.find(MessageSeries.class, id);
        assertNotNull(reloaded, "message series row must exist in the database");
        assertEquals("IT-create", reloaded.getSeriesId());
        assertEquals(MainType.NW, reloaded.getMainType());
        assertEquals("NW-${number-3-digits}-${year-2-digits}", reloaded.getShortFormat());
        assertEquals("NAVTEX-${number}", reloaded.getNavtexFormat());
        assertEquals(NumberSequenceType.CONTINUOUS, reloaded.getNumberSequenceType());
        assertTrue(reloaded.getTypes().contains(Type.LOCAL_WARNING));
        assertTrue(reloaded.getEditorFields().contains("publishDateFrom"));
        assertEquals(Boolean.TRUE, reloaded.getExcludeFromMessageIndex());
    }

    @Test
    @Transactional
    void createMessageSeriesDefaultsNumberSequenceTypeToYearly() {
        // A series built only via the (seriesId) constructor must default to YEARLY.
        MessageSeries series = new MessageSeries("IT-default-seq");
        series.setMainType(MainType.NM);

        MessageSeries created = messageSeriesService.createMessageSeries(series);
        em.flush();
        em.clear();

        MessageSeries reloaded = em.find(MessageSeries.class, created.getId());
        assertEquals(NumberSequenceType.YEARLY, reloaded.getNumberSequenceType());
    }

    @Test
    @Transactional
    void createMessageSeriesRejectsDuplicateSeriesId() {
        // dma-nw is already present in the seed data.
        MessageSeries dup = newSeries("dma-nw", MainType.NW);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.createMessageSeries(dup));
        assertTrue(ex.getMessage().contains("duplicate"),
                "duplicate series-id creation must be rejected with a 'duplicate' message");
    }

    @Test
    @Transactional
    void createMessageSeriesRejectsDuplicateOfNewlyCreatedSeries() {
        messageSeriesService.createMessageSeries(newSeries("IT-dup-new", MainType.NW));
        em.flush();

        assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.createMessageSeries(newSeries("IT-dup-new", MainType.NM)));
    }

    // ------------------------------------------------------------------------------------------- //
    // findBySeriesId / findByIds / getAllMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findBySeriesIdReturnsSeededSeries() {
        MessageSeries series = messageSeriesService.findBySeriesId("dma-nw");
        assertNotNull(series, "seeded series dma-nw must be found");
        assertEquals("dma-nw", series.getSeriesId());
        assertEquals(MainType.NW, series.getMainType());
        assertEquals(NumberSequenceType.YEARLY, series.getNumberSequenceType());
    }

    @Test
    @Transactional
    void findBySeriesIdReturnsNullForUnknownAndNull() {
        assertNull(messageSeriesService.findBySeriesId("IT-does-not-exist"));
        assertNull(messageSeriesService.findBySeriesId(null));
    }

    @Test
    @Transactional
    void findByIdsReturnsOnlyMatchingSeries() {
        List<MessageSeries> result = messageSeriesService.findByIds("dma-nw", "dma-nm", "IT-unknown");
        assertEquals(2, result.size(), "only the two existing seeded series must be returned");
        assertTrue(result.stream().anyMatch(s -> "dma-nw".equals(s.getSeriesId())));
        assertTrue(result.stream().anyMatch(s -> "dma-nm".equals(s.getSeriesId())));
    }

    @Test
    @Transactional
    void getAllMessageSeriesReturnsAllSeededRows() {
        List<String> ids = messageSeriesService.getAllMessageSeries().stream()
                .map(MessageSeries::getSeriesId)
                .toList();
        assertTrue(ids.containsAll(SEEDED_SERIES_IDS), "every seeded series must be returned, got " + ids);
    }

    @Test
    @Transactional
    void getAllMessageSeriesIncludesNewlyCreatedSeries() {
        int before = messageSeriesService.getAllMessageSeries().size();
        messageSeriesService.createMessageSeries(newSeries("IT-all-new", MainType.NW));
        em.flush();

        List<MessageSeries> all = messageSeriesService.getAllMessageSeries();
        assertEquals(before + 1, all.size(), "a newly created series must be visible in getAllMessageSeries");
        assertTrue(all.stream().anyMatch(s -> "IT-all-new".equals(s.getSeriesId())));
    }

    // ------------------------------------------------------------------------------------------- //
    // findOrCreateMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findOrCreateReturnsExistingSeriesWithoutCreating() {
        long before = messageSeriesService.count(MessageSeries.class);
        MessageSeries template = newSeries("dma-nw", MainType.NM); // different main type than seed
        MessageSeries result = messageSeriesService.findOrCreateMessageSeries(template);

        assertNotNull(result);
        assertEquals("dma-nw", result.getSeriesId());
        // The existing seeded series is returned, so the seeded NW main type is preserved.
        assertEquals(MainType.NW, result.getMainType());

        em.flush();
        em.clear();
        // Total row count is unchanged - nothing new was persisted.
        assertEquals(before, messageSeriesService.count(MessageSeries.class));
    }

    @Test
    @Transactional
    void findOrCreateCreatesSeriesWhenMissing() {
        MessageSeries template = newSeries("IT-foc-new", MainType.NM);
        MessageSeries result = messageSeriesService.findOrCreateMessageSeries(template);
        assertNotNull(result.getId(), "a missing series must be created with a generated id");

        em.flush();
        em.clear();

        MessageSeries reloaded = em.find(MessageSeries.class, result.getId());
        assertNotNull(reloaded, "the created series must be persisted");
        assertEquals("IT-foc-new", reloaded.getSeriesId());
        assertEquals(MainType.NM, reloaded.getMainType());
    }

    @Test
    @Transactional
    void findOrCreateReturnsNullForNullOrBlankSeriesId() {
        assertNull(messageSeriesService.findOrCreateMessageSeries(null));
        assertNull(messageSeriesService.findOrCreateMessageSeries(new MessageSeries((String) null)));
        assertNull(messageSeriesService.findOrCreateMessageSeries(new MessageSeries("   ")));
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMessageSeriesPersistsAllMutableFields() {
        MessageSeries created = messageSeriesService.createMessageSeries(newSeries("IT-update", MainType.NW));
        Integer id = created.getId();
        em.flush();

        MessageSeries template = new MessageSeries("IT-update");
        template.setMainType(MainType.NM);
        template.setShortFormat("UPD-${number}");
        template.setNavtexFormat("UPD-NAVTEX");
        template.setNumberSequenceType(NumberSequenceType.MANUAL);
        template.getTypes().add(Type.TEMPORARY_NOTICE);
        template.getEditorFields().add("prohibition");
        template.setExcludeFromMessageIndex(Boolean.TRUE);

        messageSeriesService.updateMessageSeries(template);
        em.flush();
        em.clear();

        MessageSeries reloaded = em.find(MessageSeries.class, id);
        assertEquals(MainType.NM, reloaded.getMainType());
        assertEquals("UPD-${number}", reloaded.getShortFormat());
        assertEquals("UPD-NAVTEX", reloaded.getNavtexFormat());
        assertEquals(NumberSequenceType.MANUAL, reloaded.getNumberSequenceType());
        assertTrue(reloaded.getTypes().contains(Type.TEMPORARY_NOTICE));
        assertTrue(reloaded.getEditorFields().contains("prohibition"));
        assertEquals(Boolean.TRUE, reloaded.getExcludeFromMessageIndex());
    }

    @Test
    @Transactional
    void updateMessageSeriesReplacesTypesAndEditorFieldCollections() {
        MessageSeries created = newSeries("IT-update-coll", MainType.NW);
        created.getTypes().add(Type.LOCAL_WARNING);
        created.getEditorFields().add("oldField");
        messageSeriesService.createMessageSeries(created);
        Integer id = created.getId();
        em.flush();

        MessageSeries template = newSeries("IT-update-coll", MainType.NW);
        template.getTypes().add(Type.COASTAL_WARNING); // replaces LOCAL_WARNING
        template.getEditorFields().add("newField");    // replaces oldField

        messageSeriesService.updateMessageSeries(template);
        em.flush();
        em.clear();

        MessageSeries reloaded = em.find(MessageSeries.class, id);
        assertEquals(1, reloaded.getTypes().size());
        assertTrue(reloaded.getTypes().contains(Type.COASTAL_WARNING));
        assertFalse(reloaded.getTypes().contains(Type.LOCAL_WARNING),
                "the old type must be cleared, not merged");
        assertEquals(List.of("newField"), reloaded.getEditorFields(),
                "the editor-field collection must be fully replaced");
    }

    @Test
    @Transactional
    void updateMessageSeriesDoesNotChangeSeriesId() {
        // updateMessageSeries copies fields onto the original but never re-keys the series id.
        MessageSeries created = messageSeriesService.createMessageSeries(newSeries("IT-update-id", MainType.NW));
        Integer id = created.getId();
        em.flush();

        MessageSeries template = newSeries("IT-update-id", MainType.NM);
        messageSeriesService.updateMessageSeries(template);
        em.flush();
        em.clear();

        assertEquals("IT-update-id", em.find(MessageSeries.class, id).getSeriesId());
    }

    @Test
    @Transactional
    void updateMessageSeriesRejectsUnknownSeriesId() {
        MessageSeries template = newSeries("IT-update-unknown", MainType.NW);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.updateMessageSeries(template));
        assertTrue(ex.getMessage().contains("non-existing"),
                "updating a non-existing series must be rejected");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteMessageSeriesRemovesRowAndReturnsTrue() {
        MessageSeries created = messageSeriesService.createMessageSeries(newSeries("IT-delete", MainType.NW));
        Integer id = created.getId();
        em.flush();

        assertTrue(messageSeriesService.deleteMessageSeries("IT-delete"));
        em.flush();
        em.clear();

        assertNull(em.find(MessageSeries.class, id), "the deleted series must be gone from the database");
        assertNull(messageSeriesService.findBySeriesId("IT-delete"));
    }

    @Test
    @Transactional
    void deleteMessageSeriesReturnsFalseForUnknownSeriesId() {
        assertFalse(messageSeriesService.deleteMessageSeries("IT-delete-unknown"));
    }

    @Test
    @Transactional
    void deleteMessageSeriesReturnsFalseForNull() {
        assertFalse(messageSeriesService.deleteMessageSeries(null));
    }

    // ------------------------------------------------------------------------------------------- //
    // searchMessageSeries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchMessageSeriesMatchesBySubstringCaseInsensitively() {
        // The seeded series ids all start with "dma-"; "DMA" (upper case) must still match.
        List<MessageSeries> result = messageSeriesService.searchMessageSeries("DMA", null, 100);
        assertEquals(6, result.size(), "all six seeded series share the 'dma-' prefix");
    }

    @Test
    @Transactional
    void searchMessageSeriesMatchesNarrowerSubstring() {
        List<MessageSeries> result = messageSeriesService.searchMessageSeries("nm-an", null, 100);
        assertEquals(1, result.size());
        assertEquals("dma-nm-annex", result.get(0).getSeriesId());
    }

    @Test
    @Transactional
    void searchMessageSeriesRespectsLimit() {
        List<MessageSeries> result = messageSeriesService.searchMessageSeries("dma", null, 2);
        assertEquals(2, result.size(), "the result must be capped at the requested limit");
    }

    @Test
    @Transactional
    void searchMessageSeriesReturnsEmptyForBlankTerm() {
        assertTrue(messageSeriesService.searchMessageSeries("", null, 100).isEmpty());
        assertTrue(messageSeriesService.searchMessageSeries(null, null, 100).isEmpty());
        assertTrue(messageSeriesService.searchMessageSeries("   ", null, 100).isEmpty());
    }

    @Test
    @Transactional
    void searchMessageSeriesReturnsEmptyForNonMatchingTerm() {
        assertTrue(messageSeriesService.searchMessageSeries("IT-no-such-series", null, 100).isEmpty());
    }

    @Test
    @Transactional
    void searchMessageSeriesFiltersByDomain() {
        // Domain niord-nw is linked (in the seed Domain_MessageSeries table) to dma-nw and
        // dma-nw-local only, so a "dma" search restricted to that domain must return just those two.
        List<MessageSeries> result = messageSeriesService.searchMessageSeries("dma", "niord-nw", 100);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "dma-nw".equals(s.getSeriesId())));
        assertTrue(result.stream().anyMatch(s -> "dma-nw-local".equals(s.getSeriesId())));
        assertFalse(result.stream().anyMatch(s -> "dma-nm".equals(s.getSeriesId())),
                "series outside the domain must be filtered out");
    }

    @Test
    @Transactional
    void searchMessageSeriesByUnknownDomainReturnsAllMatches() {
        // An unknown domain id resolves to a null Domain, which disables domain filtering entirely.
        List<MessageSeries> result = messageSeriesService.searchMessageSeries("dma", "IT-unknown-domain", 100);
        assertEquals(6, result.size(), "an unknown domain must not restrict the search");
    }

    // ------------------------------------------------------------------------------------------- //
    // Message-number generation
    //
    // A number is taken, set and peeked inside the test's transaction and rolls back with it. These
    // tests use far-future years of their own and set the sequence first, so a row another class
    // committed in such a year changes nothing.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getNextMessageNumberPeeksInitialValueForFreshYear() {
        // 2089 is a year no other test bumps; a never-bumped sequence peeks its initial value of 1.
        assertEquals(1, messageSeriesService.getNextMessageNumber("dma-nw", 2089));
    }

    @Test
    @Transactional
    void getNextMessageNumberDoesNotBumpTheSequence() {
        // Reset to a known value, then peek twice - peeking must be non-destructive.
        messageSeriesService.setNextMessageNumber("dma-nw", 2091, 42);
        assertEquals(42, messageSeriesService.getNextMessageNumber("dma-nw", 2091));
        assertEquals(42, messageSeriesService.getNextMessageNumber("dma-nw", 2091),
                "peeking must not advance the sequence");
    }

    @Test
    @Transactional
    void newMessageNumberBumpsSequenceOnEachCall() {
        // Reset to a deterministic baseline so the test is repeatable despite committed sequence state.
        messageSeriesService.setNextMessageNumber("dma-nw", 2092, 100);

        assertEquals(100, messageSeriesService.newMessageNumber("dma-nw", 2092));
        assertEquals(101, messageSeriesService.newMessageNumber("dma-nw", 2092));
        assertEquals(102, messageSeriesService.newMessageNumber("dma-nw", 2092));
        // After three bumps the peek must reflect the next unused number.
        assertEquals(103, messageSeriesService.getNextMessageNumber("dma-nw", 2092));
    }

    @Test
    @Transactional
    void setNextMessageNumberResetsTheSequence() {
        messageSeriesService.setNextMessageNumber("dma-nw", 2093, 7);
        assertEquals(7, messageSeriesService.getNextMessageNumber("dma-nw", 2093));
        assertEquals(7, messageSeriesService.newMessageNumber("dma-nw", 2093));

        // Reset back down to a lower value and confirm the sequence honours it.
        messageSeriesService.setNextMessageNumber("dma-nw", 2093, 3);
        assertEquals(3, messageSeriesService.getNextMessageNumber("dma-nw", 2093));
    }

    @Test
    @Transactional
    void yearlyAndContinuousSequencesAreIndependentPerYear() {
        // dma-nw is YEARLY: different years use different sequence keys, so they bump independently.
        messageSeriesService.setNextMessageNumber("dma-nw", 2094, 50);
        messageSeriesService.setNextMessageNumber("dma-nw", 2095, 80);

        assertEquals(50, messageSeriesService.newMessageNumber("dma-nw", 2094));
        assertEquals(80, messageSeriesService.newMessageNumber("dma-nw", 2095),
                "a different year must not share the 2094 sequence");
        assertEquals(51, messageSeriesService.getNextMessageNumber("dma-nw", 2094));
        assertEquals(81, messageSeriesService.getNextMessageNumber("dma-nw", 2095));
    }

    /**
     * A number taken in a transaction that rolls back is given back: the next number of the series is the one it
     * took. Before SERIES6 was fixed the number was committed in a transaction of its own and the series had a gap.
     */
    @Test
    void aNumberTakenInATransactionThatRollsBackIsGivenBack() {
        int before = Tx.requiringNew().call(() -> messageSeriesService.getNextMessageNumber("dma-nw", 2098));

        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            assertEquals(before, messageSeriesService.newMessageNumber("dma-nw", 2098));
            throw new IllegalStateException("IT: the publication fails after taking its number");
        }));

        assertEquals(before, Tx.requiringNew().call(() -> messageSeriesService.getNextMessageNumber("dma-nw", 2098)),
                "the series' next number is the one the failed publication took");
    }

    /** The lock a cancellation takes before it changes anything changes no number. */
    @Test
    @Transactional
    void lockMessageNumberChangesNoNumber() {
        MessageSeries series = messageSeriesService.findBySeriesId("dma-nw");
        Date publishDate = Date.from(ZonedDateTime.of(2099, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant());
        messageSeriesService.setNextMessageNumber("dma-nw", 2099, 12);

        messageSeriesService.lockMessageNumber(series, publishDate);

        assertEquals(12, messageSeriesService.getNextMessageNumber("dma-nw", 2099));
        assertEquals(12, messageSeriesService.newMessageNumber("dma-nw", 2099));
    }

    @Test
    @Transactional
    void messageNumberGenerationRejectsUnknownSeries() {
        assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.getNextMessageNumber("IT-no-series", 2090));
        assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.newMessageNumber("IT-no-series", 2090));
    }

    @Test
    @Transactional
    void messageNumberGenerationRejectsYearOutOfRange() {
        // sequenceForSeries enforces a sensible 1700..2100 range.
        assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.getNextMessageNumber("dma-nw", 1699));
        assertThrows(IllegalArgumentException.class,
                () -> messageSeriesService.getNextMessageNumber("dma-nw", 2101));
    }

    @Test
    @Transactional
    void messageNumberGenerationAcceptsRangeBoundaries() {
        // 1700 and 2100 are the inclusive boundaries and must be accepted.
        assertEquals(1, messageSeriesService.getNextMessageNumber("dma-nw", 1700));
        assertEquals(1, messageSeriesService.getNextMessageNumber("dma-nw", 2100));
    }

    @Test
    @Transactional
    void computeNextMessageNumberReturnsOneWhenNoMessagesExist() {
        // The fixture's messages (the sample's) are numbered in 2026 only, so coalesce(max(number),0)+1
        // must be 1 for any of its series in 2050.
        assertEquals(1, messageSeriesService.computeNextMessageNumber("dma-nw", 2050));
        assertEquals(1, messageSeriesService.computeNextMessageNumber("dma-nm", 2050));
    }

    @Test
    @Transactional
    void computeNextMessageNumberForContinuousSeriesCountsEarlierYears() {
        // A CONTINUOUS series numbers on across the years, so its message numbered 7 and published in 2020
        // makes the next number 8 in 2021 too, before the series has a message of that year (SERIES1).
        MessageSeries series = newSeries("IT-compute-continuous", MainType.NW);
        series.setNumberSequenceType(NumberSequenceType.CONTINUOUS);
        MessageSeries created = messageSeriesService.createMessageSeries(series);

        Message msg = new Message();
        msg.setMessageSeries(created);
        msg.setType(Type.COASTAL_WARNING);
        msg.setStatus(Status.PUBLISHED);
        msg.setNumber(7);
        msg.setPublishDateFrom(Date.from(ZonedDateTime.of(2020, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()));
        em.persist(msg);
        em.flush();

        assertEquals(8, messageSeriesService.computeNextMessageNumber("IT-compute-continuous", 2021));
    }


}
