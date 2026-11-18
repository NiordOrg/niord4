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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.niord.core.message.Message;
import org.niord.core.publication.series.criteria.CriteriaResolver;
import org.niord.core.publication.series.resolve.Interval;
import org.niord.core.publication.series.resolve.MembershipPredicate;
import org.niord.core.publication.series.resolve.MessageFacts;
import org.niord.core.publication.series.resolve.ResolvedCriteria;
import org.niord.core.service.BaseService;

import java.util.ArrayList;
import org.niord.model.message.Status;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which publication issues a message is in, answered from the message.
 *
 * Editors ask "is this already out, and where" constantly, and until now the only
 * way to answer was to open each issue in the admin area and look. The question
 * belongs on the message.
 *
 * TWO HALVES, and they are different KINDS of answer rather than two queries.
 *
 * A published issue froze its membership at publication, so its answer is a fact:
 * read the member rows. An OPEN issue's member rows are no such fact: the
 * publications import copies the old system's member list onto issues that are
 * still open, and the publish re-resolves the issue and rewrites them. So the
 * rows are read only of an issue that is frozen by its status, as the issue's
 * member list reads them, and an open issue's answer is resolved now: a
 * PREDICTION, what this issue would contain if it closed at this moment. Those
 * must not be presented as the same thing, which is why every row carries which
 * one it is.
 *
 * The prediction is the point of the feature. "It is in EfS week 29 (open)" is
 * the sentence an editor needs before deciding whether to amend a message, and it
 * cannot come from stored data because nothing has stored it yet.
 *
 * A THIRD KIND, the omission. Membership is filed by publish date, so a notice
 * whose date lies in a period that was released before it was published belongs
 * to that released issue and is in none of its rows. Without a row of its own
 * such a message is in no issue at all and nothing says so; it is reported as
 * MISSED, by the same predicate the release asked, so an administrator can amend
 * the issue.
 *
 * ONE MESSAGE, NOT ONE ISSUE. The publish screen resolves an issue's whole
 * membership; doing that here, once per open issue, would make opening a message
 * cost a dozen full resolutions -- one of them over a five-year interval. It is
 * not needed. MemberResolutionService documents its SQL narrowing as a SUPERSET
 * of the answer and re-checks every narrowed conjunct in the predicate, so for a
 * single message the predicate alone gives the same answer: one fact read, then
 * pure evaluation per issue. The publish screen stays the authority on an issue's
 * membership; this asks the identical rule a narrower question.
 */
@Service
public class MessageIssueLookup extends BaseService {

    @jakarta.inject.Inject
    CompilationResolver compilations;

    /** How a message came to be in an issue: recorded, or resolved just now. */
    public enum Membership {
        /** Frozen at publication. A fact about a document that exists. */
        FROZEN,
        /** Resolved at read time. What this open issue would contain if it closed now. */
        LIVE,
        /**
         * Released over a period that holds this message's publish date, without it.
         *
         * Membership is filed by date, never by the moment of publication, so a
         * notice published (or due to be published) with a date inside a period
         * that was already released belongs to that released issue and to no
         * other -- and the release did not carry it. Only an amend of that issue
         * takes it in. Reported so that somebody is told; without this row the
         * message is a member of nothing and no screen says so.
         */
        MISSED
    }

    /** One issue a message is in, and on what basis. */
    public record MessageIssue(PublicationIssue issue, Membership membership) {
    }

    /**
     * Whether a compilation's union already holds a message at one instant.
     *
     * A collaborator rather than a branch inside {@link #wouldContain}, because
     * that method is static and free of the entity manager on purpose: the
     * override precedence it encodes is one deleted branch away from telling an
     * editor a message is in an issue a curator explicitly removed it from, and a
     * plain unit test has to be able to pin it. The compiled answer is a query,
     * so it arrives as a function and a test hands in its own.
     */
    @FunctionalInterface
    public interface CompiledUnion {
        boolean holds(PublicationIssue issue, String messageUid, Date at);
    }

    /**
     * Every issue this message is in, frozen first, then the open issues'
     * predictions, then the released issues whose period holds its date and
     * which went out without it.
     *
     * Frozen first because those are the ones already published: an editor
     * scanning the list wants "this is already out" before "this is due to go
     * out".
     */
    @Transactional
    public List<MessageIssue> forMessage(String messageUid, Date now) {
        if (messageUid == null || messageUid.isBlank()) {
            return List.of();
        }
        Date at = now == null ? new Date() : now;

        List<MessageIssue> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();

        for (PublicationIssue issue : frozenIssues(messageUid)) {
            // Only a frozen issue's rows are a record; an open issue's are asked
            // below like those of every other open issue.
            if (issue.getId() != null && IssueResolutionService.isFrozen(issue) && seen.add(issue.getId())) {
                out.add(new MessageIssue(issue, Membership.FROZEN));
            }
        }

        MessageFacts facts = factsOf(messageUid);
        Map<Integer, OverrideKind> overrides = overridesFor(messageUid);

        for (PublicationIssue issue : openIssues()) {
            // An issue that already froze this message is reported once, as the
            // fact rather than the prediction. Re-resolving it would also be
            // wrong: a published issue asked what it would contain today gives an
            // authoritative-looking answer about a document that does not exist.
            if (issue.getId() == null || seen.contains(issue.getId())) {
                continue;
            }
            if (wouldContain(issue, facts, overrides.get(issue.getId()), at, this::compiledUnionHolds)) {
                seen.add(issue.getId());
                out.add(new MessageIssue(issue, Membership.LIVE));
            }
        }

        for (PublicationIssue issue : releasedPeriodsHolding(facts, overrides, seen)) {
            seen.add(issue.getId());
            out.add(new MessageIssue(issue, Membership.MISSED));
        }
        return out;
    }

    /**
     * The released issues whose period holds this message's publish date, and
     * which the release left it out of although its own rule takes it in.
     *
     * One per series at most: of a tiling series only the NEWEST published issue
     * that opened strictly before the date can hold it -- strictly, because the
     * predicate's lower bound is strict, so a date on a shared cut-off belongs to
     * the issue that closed there. Whether it does is asked of the predicate
     * itself, over the window the release took, (period start, effective
     * cut-off], with this message's override on the issue: the same question the
     * release asked, so the two cannot disagree about what the issue should hold.
     *
     * Only for a QUERY series that tiles, and not for an issue whose membership
     * was imported: the estate froze the old system's members, which filed a
     * back-dated notice in the next weekly issue, and asking today's rule about
     * those issues would flag notices that went out in the next edition.
     *
     * A VERIFIED message is asked as it will stand once published: publication
     * keeps a publish date that has passed, so the release has already missed it
     * and this is the moment the editor can still act on it. A draft, and a
     * message with no publish date, gets no row.
     */
    private List<PublicationIssue> releasedPeriodsHolding(MessageFacts facts,
                                                          Map<Integer, OverrideKind> overrides,
                                                          Set<Integer> seen) {
        if (facts == null || facts.publishDateFrom() == null) {
            return List.of();
        }
        MessageFacts asPublished = facts.status() == Status.VERIFIED
                ? new MessageFacts(facts.uid(), facts.publishDateFrom(), facts.publishDateTo(),
                        Status.PUBLISHED, facts.type(), facts.messageSeriesId(), facts.mainType(),
                        facts.areaMrns(), facts.categoryMrns(), facts.chartNumbers(), facts.withdrawnAt())
                : facts;

        List<PublicationIssue> candidates = em.createQuery(
                        "SELECT i FROM PublicationIssue i JOIN FETCH i.series s "
                                + "WHERE i.status = org.niord.core.publication.series.IssueStatus.PUBLISHED "
                                + "AND s.status = org.niord.core.publication.series.SeriesStatus.ACTIVE "
                                + "AND i.intervalFrom = (SELECT MAX(j.intervalFrom) FROM PublicationIssue j "
                                + "WHERE j.series = s "
                                + "AND j.status = org.niord.core.publication.series.IssueStatus.PUBLISHED "
                                + "AND j.intervalFrom < :from) "
                                + "ORDER BY i.intervalFrom DESC, i.id DESC", PublicationIssue.class)
                .setParameter("from", facts.publishDateFrom())
                .getResultList();

        List<PublicationIssue> out = new ArrayList<>();
        Set<Integer> seriesAsked = new HashSet<>();
        for (PublicationIssue issue : candidates) {
            PublicationSeries series = issue.getSeries();
            // The newest per series is the period that holds the date; an older
            // edition sharing its start is not asked again.
            if (issue.getId() == null || series == null || !seriesAsked.add(series.getId())) {
                continue;
            }
            if (seen.contains(issue.getId())
                    || MembershipRegime.of(series) != MembershipRegime.QUERY
                    || series.getTimeRelation() == null || !series.getTimeRelation().tiles()
                    || issue.getMembershipProvenance() == MembershipProvenance.IMPORTED) {
                continue;
            }
            Date cutoff = issue.effectiveCutoff();
            if (cutoff == null || !issue.getIntervalFrom().before(cutoff)) {
                continue;
            }
            if (wouldContain(issue, asPublished, overrides.get(issue.getId()), cutoff, null)) {
                out.add(issue);
            }
        }
        return out;
    }

    /**
     * Issues that hold a member row for this message: the ones that recorded it
     * at publication, and open issues whose rows an import copied from the old
     * system, which the caller leaves to the open-issue resolution.
     *
     * The series is fetch-joined because every caller reads it, and this list is
     * not short: an IN_FORCE_AT_CUTOFF series re-lists every message still in
     * force in every edition, so a long-lived notice is a member of a hundred-odd
     * issues of one series. Left lazy, that is a hundred extra round trips on a
     * panel that renders when a message is opened.
     *
     * Fully ordered, newest first: an in-force edition has no interval start, so
     * the start alone left those rows in whatever order the database returned
     * them; the close and then the issue id settle it.
     */
    private List<PublicationIssue> frozenIssues(String messageUid) {
        return em.createQuery(
                        "SELECT i FROM IssueMember m JOIN m.issue i JOIN FETCH i.series s "
                                + "WHERE m.messageUid = :uid ORDER BY i.intervalFrom DESC NULLS LAST, "
                                + "i.intervalTo DESC NULLS LAST, i.id DESC",
                        PublicationIssue.class)
                .setParameter("uid", messageUid)
                .getResultList();
    }

    /**
     * The open issues on a live series.
     *
     * Not narrowed to query-backed ones. A series with no criteria still has
     * members -- the annex issues name theirs by hand -- and those are exactly the
     * memberships an editor has no other way to discover, since no query explains
     * them. A DRAFT series is excluded because it publishes nothing: reporting a
     * membership in a definition somebody is still drafting is noise, not
     * transparency.
     */
    private List<PublicationIssue> openIssues() {
        // PORT: intervalFrom is nullable, and PostgreSQL orders NULLs FIRST in DESC
        // where MySQL ordered them last, so an open issue with no interval start
        // would head this list; NULLS LAST keeps it at the bottom the panel read it
        // from. The set is unchanged either way -- there is no limit here and the
        // panel renders every row -- so only the reading order is pinned.
        return em.createQuery(
                        "SELECT i FROM PublicationIssue i JOIN FETCH i.series s "
                                + "WHERE i.status = org.niord.core.publication.series.IssueStatus.OPEN "
                                + "AND s.status = org.niord.core.publication.series.SeriesStatus.ACTIVE "
                                + "ORDER BY i.intervalFrom DESC NULLS LAST", PublicationIssue.class)
                .getResultList();
    }

    /**
     * This message's curator overrides, by issue.
     *
     * One query for the whole answer rather than one per issue: an override names
     * a message directly, so asking by message is the natural direction and the
     * result is a handful of rows.
     */
    private Map<Integer, OverrideKind> overridesFor(String messageUid) {
        Map<Integer, OverrideKind> out = new HashMap<>();
        List<IssueOverride> rows = em.createQuery(
                        "SELECT o FROM IssueOverride o WHERE o.messageUid = :uid", IssueOverride.class)
                .setParameter("uid", messageUid)
                .getResultList();
        for (IssueOverride o : rows) {
            if (o.getIssue() != null && o.getIssue().getId() != null) {
                out.put(o.getIssue().getId(), o.getKind());
            }
        }
        return out;
    }

    /**
     * The compiled arm, bound to this bean's persistence context.
     *
     * ONE existence query per issue asked, never the union: the panel renders
     * once per message an editor opens, and a running annual's union is a
     * thousand rows it would throw away.
     */
    private boolean compiledUnionHolds(PublicationIssue issue, String messageUid, Date at) {
        // An issue whose period has not opened yet describes no window at all, and
        // Interval refuses one. This panel renders whenever an editor opens a
        // message, so the honest answer is "not yet a member" rather than a 500
        // from a screen that was only asking a question.
        if (issue.getIntervalFrom() == null || !issue.getIntervalFrom().before(at)) {
            return false;
        }
        return compilations.contains(issue.getSeries().getSourceSeries(),
                new Interval(issue.getIntervalFrom(), at), messageUid);
    }

    /** The facts membership turns on, or null when the message is gone. */
    private MessageFacts factsOf(String messageUid) {
        List<Message> rows = em.createQuery(
                        "SELECT m FROM Message m WHERE m.uid = :uid", Message.class)
                .setParameter("uid", messageUid)
                .getResultList();
        // EVERY facet, because this reader does not know which series it will be
        // asked about. One message's areas, categories and charts cost three lazy
        // loads; getting it wrong costs a panel that tells an editor a message is
        // in no issue whenever the series selects by one of them.
        return rows.isEmpty() ? null : MemberResolutionService.allFactsOf(rows.get(0));
    }

    /**
     * Whether this open issue would contain the message if it closed now.
     *
     * The same interval the publish checklist uses -- the issue's own start to the
     * proposed cut-off -- so the two cannot disagree about what is in an issue.
     * Answering over a different window would mean the message detail and the
     * publish screen tell an editor different things about the same issue.
     *
     * An override settles it outright, in both directions and for every series
     * shape. That is the publish path's own order: overrides constitute membership
     * on their own, so a named message is in a criteria-less annex issue and an
     * excluded one is out of an issue whose query still selects it.
     *
     * A criteria document that cannot resolve is NOT a membership answer, so it
     * returns false rather than guessing. The series validation surface is where
     * that gets reported; inventing membership here would put a wrong sentence on
     * a message.
     *
     * Static and free of the entity manager so a plain unit test can pin it. The
     * override precedence in particular is one deleted branch away from telling an
     * editor a message is in an issue a curator explicitly removed it from.
     */
    static boolean wouldContain(PublicationIssue issue, MessageFacts facts,
                                OverrideKind override, Date now, CompiledUnion compiled) {
        if (override == OverrideKind.EXCLUDE) {
            return false;
        }
        if (override == OverrideKind.INCLUDE) {
            return true;
        }

        PublicationSeries series = issue.getSeries();
        MembershipRegime regime = MembershipRegime.of(series);
        if (facts == null || regime == MembershipRegime.NONE
                || issue.getIntervalFrom() == null) {
            return false;
        }
        if (regime == MembershipRegime.COMPILED) {
            // A compilation runs no predicate: the answer is whether one of the
            // source series' published issues inside this period already printed
            // the message. It is a query, so it arrives as a collaborator rather
            // than being taken here -- which is also what keeps the override
            // precedence above testable without a database.
            return compiled != null
                    && compiled.holds(issue, facts.uid(), now);
        }
        try {
            // The EFFECTIVE document, so this panel and the publish screen cannot
            // disagree about an issue that carries a criteriaOverride.
            ResolvedCriteria criteria = EffectiveCriteria.resolvedFor(issue);
            return criteria != null && MembershipPredicate
                    .decide(facts, criteria, new Interval(issue.getIntervalFrom(), now))
                    .member();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
