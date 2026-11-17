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
import org.niord.model.message.DateIntervalVo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link DateInterval} entity's pure (non-persistent) logic: its two value
 * constructors, the {@link DateInterval#toVo() toVo} mapping, the {@code updateDateInterval} copy,
 * the {@code dateIntervalDefined} predicate (including its to-date clamping side effect), the
 * {@code openEnded} predicate and the {@link Comparable#compareTo(Object) compareTo} ordering
 * (null from-dates first, then by from-date, then by to-date with nulls last).
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Entities and
 * value objects are built directly and asserted on concrete field values / ordering, so the tests
 * survive a future Quarkus-&gt;Spring port. {@link Date} values are built from fixed millis so the
 * tests are deterministic and time-zone independent.
 */
public class DateIntervalEntityTest {

    /** Convenience factory: a {@link DateInterval} from raw millis, using null for a null bound. */
    private static DateInterval di(Long fromMillis, Long toMillis) {
        Date from = fromMillis == null ? null : new Date(fromMillis);
        Date to = toMillis == null ? null : new Date(toMillis);
        return new DateInterval(false, from, to);
    }

    /* ------------------------------------------------------------------ */
    /* Constructors                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    public void valueConstructorSetsAllThreeFields() {
        Date from = new Date(1000L);
        Date to = new Date(2000L);
        DateInterval di = new DateInterval(Boolean.TRUE, from, to);

        assertEquals(Boolean.TRUE, di.getAllDay());
        assertSame(from, di.getFromDate());
        assertSame(to, di.getToDate());
    }

    @Test
    public void valueConstructorAcceptsNulls() {
        DateInterval di = new DateInterval(null, null, null);

        assertEquals(null, di.getAllDay());
        assertEquals(null, di.getFromDate());
        assertEquals(null, di.getToDate());
    }

    @Test
    public void voConstructorCopiesAllDayFromAndTo() {
        Date from = new Date(3000L);
        Date to = new Date(4000L);
        DateIntervalVo vo = new DateIntervalVo();
        vo.setAllDay(Boolean.TRUE);
        vo.setFromDate(from);
        vo.setToDate(to);

        DateInterval di = new DateInterval(vo);

        assertEquals(Boolean.TRUE, di.getAllDay());
        assertSame(from, di.getFromDate());
        assertSame(to, di.getToDate());
    }

    @Test
    public void voConstructorCopiesNullsFromVo() {
        DateIntervalVo vo = new DateIntervalVo();
        // allDay/fromDate/toDate left at their default null values

        DateInterval di = new DateInterval(vo);

        assertEquals(null, di.getAllDay());
        assertEquals(null, di.getFromDate());
        assertEquals(null, di.getToDate());
    }

    /* ------------------------------------------------------------------ */
    /* toVo                                                               */
    /* ------------------------------------------------------------------ */

    @Test
    public void toVoCopiesAllDayFromAndTo() {
        Date from = new Date(5000L);
        Date to = new Date(6000L);
        DateInterval di = new DateInterval(Boolean.FALSE, from, to);

        DateIntervalVo vo = di.toVo();

        assertEquals(Boolean.FALSE, vo.getAllDay());
        assertSame(from, vo.getFromDate());
        assertSame(to, vo.getToDate());
    }

    @Test
    public void toVoPreservesNulls() {
        DateInterval di = new DateInterval(null, null, null);

        DateIntervalVo vo = di.toVo();

        assertEquals(null, vo.getAllDay());
        assertEquals(null, vo.getFromDate());
        assertEquals(null, vo.getToDate());
    }

    @Test
    public void toVoRoundTripsThroughVoConstructor() {
        Date from = new Date(7000L);
        Date to = new Date(8000L);
        DateInterval original = new DateInterval(Boolean.TRUE, from, to);

        DateInterval roundTripped = new DateInterval(original.toVo());

        assertEquals(Boolean.TRUE, roundTripped.getAllDay());
        assertSame(from, roundTripped.getFromDate());
        assertSame(to, roundTripped.getToDate());
    }

    /* ------------------------------------------------------------------ */
    /* updateDateInterval                                                 */
    /* ------------------------------------------------------------------ */

    @Test
    public void updateDateIntervalCopiesAllDayAndDatesButNotTheIndex() {
        Date from = new Date(1000L);
        Date to = new Date(2000L);
        DateInterval source = new DateInterval(Boolean.TRUE, from, to);
        source.setIndexNo(7);

        DateInterval target = new DateInterval(Boolean.FALSE, new Date(0L), new Date(0L));
        target.setIndexNo(0);

        target.updateDateInterval(source);

        assertEquals(0, target.getIndexNo(), "the list position is the order; indexNo is read-only");
        assertEquals(Boolean.TRUE, target.getAllDay());
        assertSame(from, target.getFromDate());
        assertSame(to, target.getToDate());
    }

    @Test
    public void updateDateIntervalCopiesNullDatesAndAllDay() {
        DateInterval source = new DateInterval(null, null, null);
        source.setIndexNo(3);

        DateInterval target = new DateInterval(Boolean.TRUE, new Date(1000L), new Date(2000L));
        target.setIndexNo(99);

        target.updateDateInterval(source);

        assertEquals(99, target.getIndexNo(), "the list position is the order; indexNo is read-only");
        assertEquals(null, target.getAllDay());
        assertEquals(null, target.getFromDate());
        assertEquals(null, target.getToDate());
    }

    /* ------------------------------------------------------------------ */
    /* dateIntervalDefined                                                */
    /* ------------------------------------------------------------------ */

    @Test
    public void dateIntervalDefinedFalseWhenBothNull() {
        DateInterval di = new DateInterval(false, null, null);
        assertFalse(di.dateIntervalDefined());
    }

    @Test
    public void dateIntervalDefinedTrueWhenOnlyFromSet() {
        DateInterval di = new DateInterval(false, new Date(1000L), null);
        assertTrue(di.dateIntervalDefined());
    }

    @Test
    public void dateIntervalDefinedTrueWhenOnlyToSet() {
        DateInterval di = new DateInterval(false, null, new Date(2000L));
        assertTrue(di.dateIntervalDefined());
    }

    @Test
    public void dateIntervalDefinedTrueWhenBothSetInOrder() {
        Date from = new Date(1000L);
        Date to = new Date(2000L);
        DateInterval di = new DateInterval(false, from, to);

        assertTrue(di.dateIntervalDefined());
        // Properly ordered interval is left untouched
        assertSame(from, di.getFromDate());
        assertSame(to, di.getToDate());
    }

    @Test
    public void dateIntervalDefinedClampsToDateWhenToBeforeFrom() {
        Date from = new Date(5000L);
        Date to = new Date(1000L); // before from -> must be clamped to from
        DateInterval di = new DateInterval(false, from, to);

        assertTrue(di.dateIntervalDefined());
        // Side effect: the inverted to-date is clamped to the from-date (the very same reference),
        // and is therefore no longer the original (earlier) to-date value.
        assertSame(from, di.getToDate());
        assertFalse(to.equals(di.getToDate()), "clamping must replace the inverted to-date");
    }

    /* ------------------------------------------------------------------ */
    /* openEnded                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    public void openEndedTrueWhenFromSetAndToNull() {
        DateInterval di = new DateInterval(false, new Date(1000L), null);
        assertTrue(di.openEnded());
    }

    @Test
    public void openEndedFalseWhenBothSet() {
        DateInterval di = new DateInterval(false, new Date(1000L), new Date(2000L));
        assertFalse(di.openEnded());
    }

    @Test
    public void openEndedFalseWhenBothNull() {
        DateInterval di = new DateInterval(false, null, null);
        assertFalse(di.openEnded());
    }

    @Test
    public void openEndedFalseWhenOnlyToSet() {
        DateInterval di = new DateInterval(false, null, new Date(2000L));
        assertFalse(di.openEnded());
    }

    /* ------------------------------------------------------------------ */
    /* compareTo                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    public void compareToBothFromNullIsEqual() {
        assertEquals(0, di(null, null).compareTo(di(null, null)));
        // to-dates are ignored entirely when both from-dates are null
        assertEquals(0, di(null, 2000L).compareTo(di(null, 9000L)));
    }

    @Test
    public void compareToThisFromNullSortsFirst() {
        assertTrue(di(null, null).compareTo(di(1000L, 2000L)) < 0);
    }

    @Test
    public void compareToOtherFromNullSortsLast() {
        assertTrue(di(1000L, 2000L).compareTo(di(null, null)) > 0);
    }

    @Test
    public void compareToOrdersByFromDate() {
        assertTrue(di(1000L, 9000L).compareTo(di(2000L, 3000L)) < 0);
        assertTrue(di(2000L, 3000L).compareTo(di(1000L, 9000L)) > 0);
    }

    @Test
    public void compareToOpenEndedToDateSortsAfterDefinedToDate() {
        // Same from-date; this has null to-date -> sorts AFTER an interval with a defined to-date
        assertTrue(di(1000L, null).compareTo(di(1000L, 2000L)) > 0);
        // Symmetric: a defined to-date sorts BEFORE a null to-date
        assertTrue(di(1000L, 2000L).compareTo(di(1000L, null)) < 0);
    }

    @Test
    public void compareToTieOnFromOrdersByToDate() {
        assertTrue(di(1000L, 2000L).compareTo(di(1000L, 5000L)) < 0);
        assertTrue(di(1000L, 5000L).compareTo(di(1000L, 2000L)) > 0);
    }

    @Test
    public void compareToEqualIntervalsIsZero() {
        assertEquals(0, di(1000L, 2000L).compareTo(di(1000L, 2000L)));
    }

    @Test
    public void compareToSameFromBothToNullIsZero() {
        assertEquals(0, di(1000L, null).compareTo(di(1000L, null)));
    }

    @Test
    public void collectionsSortYieldsNullsFirstThenByFromThenByTo() {
        DateInterval nullFrom = di(null, null);
        DateInterval earlyFrom = di(1000L, 2000L);
        DateInterval sameFromEarlyTo = di(5000L, 6000L);
        DateInterval sameFromLateTo = di(5000L, 9000L);
        DateInterval sameFromOpenEnded = di(5000L, null);

        List<DateInterval> list = new ArrayList<>();
        // Deliberately scrambled insertion order
        list.add(sameFromOpenEnded);
        list.add(earlyFrom);
        list.add(sameFromLateTo);
        list.add(nullFrom);
        list.add(sameFromEarlyTo);

        Collections.sort(list);

        // Expected: null from-date first, then by from-date, and within equal
        // from-dates by to-date with the open-ended (null to-date) entry last.
        assertEquals(5, list.size());
        assertSame(nullFrom, list.get(0));
        assertSame(earlyFrom, list.get(1));
        assertSame(sameFromEarlyTo, list.get(2));
        assertSame(sameFromLateTo, list.get(3));
        assertSame(sameFromOpenEnded, list.get(4));
    }
}
