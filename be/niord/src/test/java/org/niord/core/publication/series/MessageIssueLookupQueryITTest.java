/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.criteria.IssueCriteriaVo;
import org.niord.core.publication.series.criteria.MessageSeriesCriterionVo;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.user.User;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookup's queries, executed against a real schema, and the released issues
 * that went out without a message.
 *
 * MessageIssueLookupTest covers the decision; nothing there touches SQL. These
 * queries are built from strings at call time, so a wrong field name or a stale
 * entity name is not a compile error and not a boot error either -- it is a 500
 * the first time an editor opens a message, on a panel that is supposed to be a
 * quiet footnote on the screen.
 *
 * A uid that matches nothing is the right probe for the first two cases: it
 * drives the queries (members, overrides, the message itself, the open issues)
 * and asserts only that they PARSE and RUN.
 *
 * The rest are about MISSED. Membership is filed by publish date, so a notice
 * published with a date inside a week that was already released belongs to that
 * week and is in none of its rows; the lookup names that week so an editor is
 * told an amend is due. Each case builds its own message series and publication
 * series, and reads only the rows of that series, so the rest of the database
 * cannot decide the answer.
 */
public class MessageIssueLookupQueryITTest extends ITTest {

    @Autowired
    MessageIssueLookup lookup;

    @Autowired
    IssueLifecycleService lifecycle;

    @Autowired
    IssueCurationService curation;

    @PersistenceContext
    EntityManager em;

    /** A week in the past, so every release below is a released week. */
    private static final Date OPENS = new Date(1_699_000_000_000L);

    private static final long HOUR = 3600_000L;

    private static final long DAY = 24 * HOUR;

    /** Every query runs, and a uid nobody has answers empty rather than throwing. */
    @Test
    public void theQueriesRunAgainstTheSchema() {
        List<MessageIssueLookup.MessageIssue> rows =
                lookup.forMessage("no-such-message-uid", new Date());

        assertNotNull(rows);
        assertTrue(rows.isEmpty(), "a uid that matches no message is in no issue");
    }

    /**
     * A blank uid is answered without a round trip.
     *
     * The message editor mounts the panel before the message has loaded, so this
     * is the common case rather than an edge one.
     */
    @Test
    public void ablankUidIsAnsweredWithoutQuerying() {
        assertTrue(lookup.forMessage(null, new Date()).isEmpty());
        assertTrue(lookup.forMessage("  ", new Date()).isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private String messageSeriesId;

    /** A weekly published-in-interval series selecting its own message series. */
    private PublicationSeries series() {
        return series(TimeRelation.PUBLISHED_IN_INTERVAL);
    }

    /** A weekly series of the given time relation selecting its own message series. */
    private PublicationSeries series(TimeRelation relation) {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(TestIds.category());
        c.setPriority(100);
        em.persist(c);

        messageSeriesId = TestIds.id("ms-");
        MessageSeries ms = new MessageSeries();
        ms.setSeriesId(messageSeriesId);
        ms.setMainType(MainType.NM);
        em.persist(ms);

        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(TestIds.series());
        s.setStatus(SeriesStatus.ACTIVE);
        s.setContentMode(ContentMode.GENERATED_FROM_QUERY);
        s.setReportId("some-report");
        s.setCadence(SeriesCadence.WEEKLY);
        s.setTimeRelation(relation);
        s.setAliveAtCutoff(relation == TimeRelation.IN_FORCE_AT_CUTOFF);
        s.setReleaseMode(ReleaseMode.MANUAL_GATE);
        s.setNextIssueCreation(NextIssueCreation.MANUAL);
        s.setMessagePublication(MessagePublication.NONE);
        s.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
        s.setCategory(c);
        s.setDomain(TestOwnerDomain.of(em));
        s.getLanguages().add("da");

        IssueCriteriaVo doc = new IssueCriteriaVo();
        MessageSeriesCriterionVo node = new MessageSeriesCriterionVo();
        node.setValues(new ArrayList<>(List.of(messageSeriesId)));
        doc.getCriteria().add(node);
        s.setCriteria(doc);

        s.createDesc("da").setName("Test series");
        em.persist(s);
        return s;
    }

    /** An open issue over [from, to]. */
    private PublicationIssue week(PublicationSeries s, Date from, Date to) {
        PublicationIssue i = lifecycle.create(s, from, IntervalBoundSource.STAMPED, to, user());
        em.flush();
        return i;
    }

    /** The issue released at its planned cut-off, as the release leaves it. */
    private PublicationIssue release(PublicationIssue i) {
        i.setStatus(IssueStatus.PUBLISHED);
        i.setCutoffStampedAt(i.getIntervalTo());
        i.setPublishedAt(i.getIntervalTo());
        PublicationIssue merged = em.merge(i);
        em.flush();
        return merged;
    }

    private Message message(Status status, Date publishDateFrom) {
        MessageSeries ms = em.createQuery(
                        "SELECT ms FROM MessageSeries ms WHERE ms.seriesId = :id", MessageSeries.class)
                .setParameter("id", messageSeriesId).getSingleResult();

        Message m = new Message();
        m.setUid(UUID.randomUUID().toString());
        m.setMessageSeries(ms);
        m.setMainType(MainType.NM);
        m.setType(Type.TEMPORARY_NOTICE);
        m.setStatus(status);
        m.setPublishDateFrom(publishDateFrom);
        em.persist(m);
        em.flush();
        return m;
    }

    /** The frozen row a release writes for a member. */
    private void member(PublicationIssue issue, Message message) {
        IssueMember m = new IssueMember();
        m.setIssue(issue);
        m.setMessage(message);
        m.setMessageUid(message.getUid());
        m.setSortIndex(0);
        m.setFrozenShortId("NM-1");
        m.setFrozenMainType(message.getMainType().name());
        m.setFrozenType(message.getType().name());
        m.setFrozenStatus(message.getStatus().name());
        m.setFrozenPublishDateFrom(message.getPublishDateFrom());
        m.setSource(MemberSource.CRITERIA);
        em.persist(m);
        em.flush();
    }

    private User user() {
        User u = new User();
        u.setUsername(TestIds.user());
        em.persist(u);
        return u;
    }

    /** The lookup's rows for this message, of this series only. */
    private List<MessageIssueLookup.MessageIssue> rowsOf(Message m, PublicationSeries s) {
        return lookup.forMessage(m.getUid(), new Date()).stream()
                .filter(r -> s.getSeriesId().equals(r.issue().getSeries().getSeriesId()))
                .toList();
    }

    private static Date at(long offset) {
        return new Date(OPENS.getTime() + offset);
    }

    // ------------------------------------------------------------------- MISSED

    /**
     * Week 46 released at its cut-off; a notice then published with a date inside
     * week 46 is in no issue. The lookup names week 46 as MISSED.
     */
    @Test
    @Transactional
    public void aNoticeDatedInsideAReleasedWeekIsReportedAsMissedByIt() {
        PublicationSeries s = series();
        PublicationIssue released = release(week(s, OPENS, at(7 * DAY)));
        Message notice = message(Status.PUBLISHED, at(2 * DAY));

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertEquals(1, rows.size(), "the released week is not named: " + rows);
        assertEquals(MessageIssueLookup.Membership.MISSED, rows.get(0).membership());
        assertEquals(released.getId(), rows.get(0).issue().getId());
    }

    /**
     * A verified notice dated inside a released week is reported too. Publication
     * keeps a past date, so the release has already missed it, and this is when
     * the editor can still act.
     */
    @Test
    @Transactional
    public void aVerifiedNoticeDatedInsideAReleasedWeekIsReportedAsMissed() {
        PublicationSeries s = series();
        release(week(s, OPENS, at(7 * DAY)));
        Message notice = message(Status.VERIFIED, at(2 * DAY));

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertEquals(1, rows.size(), "a verified notice dated inside a released week is not warned: " + rows);
        assertEquals(MessageIssueLookup.Membership.MISSED, rows.get(0).membership());
    }

    /** A draft is not judged: its date is still the editor's to set. */
    @Test
    @Transactional
    public void aDraftDatedInsideAReleasedWeekIsNotReported() {
        PublicationSeries s = series();
        release(week(s, OPENS, at(7 * DAY)));
        Message draft = message(Status.DRAFT, at(2 * DAY));

        assertTrue(rowsOf(draft, s).isEmpty());
    }

    /** A member of the released week is a FROZEN row, and only that. */
    @Test
    @Transactional
    public void aMemberOfTheReleasedWeekIsReportedFrozenNotMissed() {
        PublicationSeries s = series();
        PublicationIssue w = week(s, OPENS, at(7 * DAY));
        Message notice = message(Status.PUBLISHED, at(2 * DAY));
        PublicationIssue released = release(w);
        member(released, notice);

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertEquals(1, rows.size(), "a member was reported twice: " + rows);
        assertEquals(MessageIssueLookup.Membership.FROZEN, rows.get(0).membership());
    }

    /** A notice a curator excluded from the week before its release was not missed. */
    @Test
    @Transactional
    public void anExcludedNoticeIsNotReportedMissed() {
        PublicationSeries s = series();
        PublicationIssue w = week(s, OPENS, at(7 * DAY));
        Message notice = message(Status.PUBLISHED, at(2 * DAY));
        curation.exclude(w, notice.getUid(), user(), "held over");
        em.flush();
        release(w);

        assertTrue(rowsOf(notice, s).isEmpty(), "an excluded notice was reported as missed");
    }

    /**
     * An issue whose membership was imported is not asked: the old system filed a
     * back-dated notice in the next weekly issue, and today's rule would flag
     * notices that went out in the next edition.
     */
    @Test
    @Transactional
    public void anImportedReleasedIssueReportsNoMissedRows() {
        PublicationSeries s = series();
        PublicationIssue w = week(s, OPENS, at(7 * DAY));
        w.setMembershipProvenance(MembershipProvenance.IMPORTED);
        release(w);
        Message notice = message(Status.PUBLISHED, at(2 * DAY));

        assertTrue(rowsOf(notice, s).isEmpty(), "an imported issue was reported as having missed a notice");
    }

    /**
     * Of two released weeks the one whose period holds the date is named, and a
     * date on the shared cut-off belongs to the week that closed there.
     */
    @Test
    @Transactional
    public void theReleasedWeekThatHoldsTheDateIsNamedAndASharedCutoffBelongsToTheWeekBefore() {
        PublicationSeries s = series();
        PublicationIssue first = release(week(s, OPENS, at(7 * DAY)));
        PublicationIssue second = release(week(s, at(7 * DAY), at(14 * DAY)));

        Message inFirst = message(Status.PUBLISHED, at(3 * DAY));
        Message onCutoff = message(Status.PUBLISHED, at(7 * DAY));
        Message inSecond = message(Status.PUBLISHED, at(9 * DAY));

        assertEquals(List.of(first.getId()), rowsOf(inFirst, s).stream().map(r -> r.issue().getId()).toList());
        assertEquals(List.of(first.getId()), rowsOf(onCutoff, s).stream().map(r -> r.issue().getId()).toList(),
                "a date on the shared cut-off is in the week that closed there, as the predicate files it");
        assertEquals(List.of(second.getId()), rowsOf(inSecond, s).stream().map(r -> r.issue().getId()).toList());
    }

    /** A notice dated after the release is the open successor's, LIVE, and not missed. */
    @Test
    @Transactional
    public void aNoticeDatedAfterTheReleaseIsLiveInTheOpenWeekNotMissed() {
        PublicationSeries s = series();
        release(week(s, OPENS, at(7 * DAY)));
        PublicationIssue open = week(s, at(7 * DAY), at(14 * DAY));
        Message notice = message(Status.PUBLISHED, at(8 * DAY));

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertEquals(1, rows.size(), "" + rows);
        assertEquals(MessageIssueLookup.Membership.LIVE, rows.get(0).membership());
        assertEquals(open.getId(), rows.get(0).issue().getId());
    }

    // ------------------------------------------------- OPEN ISSUE WITH ROWS

    /**
     * An open issue can carry member rows an import copied from the old system.
     * They are not a record of a release: the issue is answered as every open
     * issue is, LIVE when its rule takes the message in, and never FROZEN.
     */
    @Test
    @Transactional
    public void anOpenIssueWithImportedMembersIsAnsweredLiveNotFrozen() {
        PublicationSeries s = series();
        PublicationIssue open = week(s, at(7 * DAY), at(14 * DAY));
        open.setMembershipProvenance(MembershipProvenance.IMPORTED);
        em.flush();
        Message notice = message(Status.PUBLISHED, at(8 * DAY));
        member(open, notice);

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertEquals(1, rows.size(), "" + rows);
        assertEquals(MessageIssueLookup.Membership.LIVE, rows.get(0).membership(),
                "an open issue's imported rows were read as a published record");
        assertEquals(open.getId(), rows.get(0).issue().getId());
    }

    /**
     * A recorded member of an open issue that today's rule does not take in is
     * not reported at all: the publish would re-resolve the issue without it.
     */
    @Test
    @Transactional
    public void anOpenIssuesRecordedMemberTheRuleDoesNotTakeIsNotReported() {
        PublicationSeries s = series();
        PublicationIssue open = week(s, at(7 * DAY), at(14 * DAY));
        open.setMembershipProvenance(MembershipProvenance.IMPORTED);
        em.flush();
        Message before = message(Status.PUBLISHED, at(-2 * DAY));
        member(open, before);

        assertTrue(rowsOf(before, s).isEmpty(),
                "an open issue's recorded member was reported although its rule leaves it out");
    }

    // -------------------------------------------------------------------- FROZEN

    /**
     * The frozen rows come in a full order: interval start, then close, both newest
     * first with a missing one last, then the newest issue. An in-force series'
     * editions all have no start, so without the close and the id their order was
     * whatever the database returned.
     */
    @Test
    @Transactional
    public void theFrozenRowsOfAnInForceSeriesComeNewestFirst() {
        PublicationSeries s = series(TimeRelation.IN_FORCE_AT_CUTOFF);
        Message notice = message(Status.PUBLISHED, at(-30 * DAY));

        PublicationIssue oldest = release(lifecycle.create(s, null, null, at(10 * DAY), user()));
        PublicationIssue newest = release(lifecycle.create(s, null, null, at(30 * DAY), user()));
        PublicationIssue middle = release(lifecycle.create(s, null, null, at(20 * DAY), user()));
        PublicationIssue sameDayAsNewest = release(lifecycle.create(s, null, null, at(30 * DAY), user()));
        for (PublicationIssue i : List.of(oldest, newest, middle, sameDayAsNewest)) {
            assertNull(i.getIntervalFrom(), "an in-force edition has no interval start");
            member(i, notice);
        }

        List<MessageIssueLookup.MessageIssue> rows = rowsOf(notice, s);

        assertTrue(rows.stream().allMatch(r -> r.membership() == MessageIssueLookup.Membership.FROZEN), "" + rows);
        assertEquals(List.of(sameDayAsNewest.getId(), newest.getId(), middle.getId(), oldest.getId()),
                rows.stream().map(r -> r.issue().getId()).toList(),
                "the frozen rows are ordered by close, then by the newest issue");
    }
}
