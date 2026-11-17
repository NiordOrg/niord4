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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.domain.Domain;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.report.FmReport;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The laws every comparator must keep, or a sort may throw ("Comparison method violates its general contract") or
 * order a list differently from run to run: antisymmetric, transitive, and consistent for elements it calls equal.
 * Checked over elements generated from a fixed seed, the boundaries included (null fields, equal values, the
 * extremes of the value range), for the sort orders that decide what a reader sees first: the date intervals of a
 * message part, which of several messages a reference resolves to, message tags, promulgation types, reports and
 * sibling areas.
 */
public class SortOrderPropertyTest {

    private static final long SEED = 20261115L;

    /** The first violation of a comparator law among the elements, or null */
    static <T> String violation(List<T> elements, Comparator<T> c, Function<T, String> show) {
        for (T a : elements) {
            for (T b : elements) {
                int ab = Integer.signum(c.compare(a, b));
                int ba = Integer.signum(c.compare(b, a));
                if (ab != -ba) {
                    return "not antisymmetric: " + show.apply(a) + " vs " + show.apply(b) + " gives " + ab + " and " + ba;
                }
            }
        }
        for (T a : elements) {
            for (T b : elements) {
                if (c.compare(a, b) > 0) {
                    continue;
                }
                for (T x : elements) {
                    if (c.compare(b, x) <= 0 && c.compare(a, x) > 0) {
                        return "not transitive: " + show.apply(a) + " <= " + show.apply(b) + " <= " + show.apply(x)
                                + " but " + show.apply(a) + " > " + show.apply(x);
                    }
                    if (c.compare(a, b) == 0 && Integer.signum(c.compare(a, x)) != Integer.signum(c.compare(b, x))) {
                        return "not consistent: " + show.apply(a) + " == " + show.apply(b) + " but they compare "
                                + "differently with " + show.apply(x);
                    }
                }
            }
        }
        return null;
    }

    private static Date date(Random r) {
        // A few distinct instants, so that equal dates occur, and null
        int k = r.nextInt(6);
        return k == 0 ? null : new Date(1_700_000_000_000L + k * 3_600_000L);
    }

    @Test
    public void theDateIntervalsOfAPart_areOrderedConsistently() {
        Random random = new Random(SEED);
        List<DateInterval> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            list.add(new DateInterval(random.nextBoolean(), date(random), date(random)));
        }
        assertEquals(null, violation(list, DateInterval::compareTo,
                d -> "[" + (d.getFromDate() == null ? "-" : d.getFromDate().getTime() % 100_000_000)
                        + ", " + (d.getToDate() == null ? "-" : d.getToDate().getTime() % 100_000_000) + "]"));
    }

    @Test
    public void theMessageAReferenceResolvesTo_isChosenConsistently() {
        // MessageIdMatchComparator: the current domain's messages first, then by status (published first), then the
        // newest publish date (none last), then the highest id (none last). Few distinct dates and ids, so that every
        // step of the tie-break is reached, and full ties remain.
        Random random = new Random(SEED + 1);
        MessageSeries own = new MessageSeries();
        own.setSeriesId("own-nw");
        MessageSeries other = new MessageSeries();
        other.setSeriesId("other-nw");
        Domain domain = new Domain();
        domain.getMessageSeries().add(own);
        MessageIdMatchComparator comparator = new MessageIdMatchComparator(domain);
        List<Message> list = new ArrayList<>();
        list.add(null);
        Status[] statuses = Status.values();
        for (int i = 0; i < 40; i++) {
            Message m = new Message();
            int s = random.nextInt(statuses.length + 1);
            m.setStatus(s == statuses.length ? null : statuses[s]);
            int series = random.nextInt(3);
            m.setMessageSeries(series == 0 ? null : series == 1 ? own : other);
            m.setPublishDateFrom(date(random));
            int id = random.nextInt(5);
            m.setId(id == 0 ? null : id);
            list.add(m);
        }
        assertEquals(null, violation(list, comparator, m -> m == null ? "null"
                : (m.getMessageSeries() == null ? "-" : m.getMessageSeries().getSeriesId()) + "/" + m.getStatus()
                        + "/" + (m.getPublishDateFrom() == null ? "-" : m.getPublishDateFrom().getTime() % 100_000_000)
                        + "/" + m.getId()));
        // The tie-break is reached: two messages of the same series and status but different dates do not tie
        Message older = new Message();
        older.setMessageSeries(own);
        older.setStatus(Status.CANCELLED);
        older.setPublishDateFrom(new Date(1_700_000_000_000L));
        Message newer = new Message();
        newer.setMessageSeries(own);
        newer.setStatus(Status.EXPIRED);
        newer.setPublishDateFrom(new Date(1_700_000_000_001L));
        assertTrue(comparator.compare(newer, older) < 0, "the newer message ranks first");
    }

    @Test
    public void messageTags_areOrderedByNameIgnoringCase() {
        Random random = new Random(SEED + 2);
        String[] names = { "NM 2026", "nm 2026", "Ærø", "ærø", "Østersø", "a", "B", "b", "", "NW-001-26" };
        List<MessageTag> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            MessageTag t = new MessageTag();
            t.setName(names[random.nextInt(names.length)]);
            list.add(t);
        }
        assertEquals(null, violation(list, MessageTag::compareTo, MessageTag::getName));
    }

    @Test
    public void promulgationTypes_areOrderedByPriority_documentsBug() {
        // Known: PROM4 in be/docs/potential-errors.md names this overflow; the property shows it.
        // PromulgationTypeVo.compareTo returns "priority - other priority", an int subtraction that overflows:
        // for priorities more than 2^31 apart the sign is wrong, and the order is no order. A sysadmin sets the
        // priority on the Promulgation types page as any whole number. With the priorities the base product ships
        // (small numbers) nothing goes wrong. Pinned: with the range's ends the laws fail; Integer.compare would not.
        Random random = new Random(SEED + 3);
        int[] priorities = { Integer.MIN_VALUE, -1_500_000_000, -1, 0, 1, 10, 100, 1_500_000_000, Integer.MAX_VALUE };
        List<PromulgationTypeVo> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            PromulgationTypeVo t = new PromulgationTypeVo();
            t.setPriority(priorities[random.nextInt(priorities.length)]);
            list.add(t);
        }
        String v = violation(list, PromulgationTypeVo::compareTo, t -> String.valueOf(t.getPriority()));
        assertTrue(v != null && v.startsWith("not"), "the overflow is fixed: " + v);
        // The smallest input: 1,500,000,000 against -1,500,000,000 compares as smaller
        PromulgationTypeVo high = new PromulgationTypeVo();
        high.setPriority(1_500_000_000);
        PromulgationTypeVo low = new PromulgationTypeVo();
        low.setPriority(-1_500_000_000);
        assertTrue(high.compareTo(low) < 0, "the higher priority sorts first");

        // Within the range the base product uses, the order is right
        List<PromulgationTypeVo> small = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            PromulgationTypeVo t = new PromulgationTypeVo();
            t.setPriority(random.nextInt(2001) - 1000);
            small.add(t);
        }
        assertEquals(null, violation(small, PromulgationTypeVo::compareTo, t -> String.valueOf(t.getPriority())));
    }

    @Test
    public void reports_areOrderedBySortOrder_unsetLast() {
        Random random = new Random(SEED + 4);
        Integer[] orders = { null, Integer.MIN_VALUE, -5, 0, 0, 3, Integer.MAX_VALUE };
        List<FmReport> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            FmReport r = new FmReport();
            r.setSortOrder(orders[random.nextInt(orders.length)]);
            list.add(r);
        }
        assertEquals(null, violation(list, FmReport::compareTo, r -> String.valueOf(r.getSortOrder())));
    }

    @Test
    public void siblingAreas_areOrderedBySortOrder() {
        Random random = new Random(SEED + 5);
        Double[] orders = { null, -1.0, 0.0, -0.0, 0.5, 1.0, 1e300, Double.MAX_VALUE };
        List<SystemAreaVo> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            SystemAreaVo a = new SystemAreaVo();
            a.setSiblingSortOrder(orders[random.nextInt(orders.length)]);
            list.add(a);
        }
        assertEquals(null, violation(list, SystemAreaVo::compareTo, a -> String.valueOf(a.getSiblingSortOrder())));
    }
}
