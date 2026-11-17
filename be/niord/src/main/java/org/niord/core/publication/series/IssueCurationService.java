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
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.publication.series.resolve.Interval;
import org.niord.core.publication.series.resolve.MembershipPredicate;
import org.niord.core.publication.series.resolve.ResolvedCriteria;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curation: a person adding a message to an issue, or removing one.
 *
 * Overrides are recorded rather than applied destructively. The query result is
 * never edited; the override sits beside it and the resolver composes the two.
 * That is what makes "why is this here" answerable after the fact, and what lets
 * an override be withdrawn without re-deriving anything.
 *
 * Curation is only legal while the issue is still OPEN. Once frozen, the member
 * set is a historical record -- changing it would rewrite what was published.
 */
@Service
public class IssueCurationService extends BaseService {

    @Inject
    IssueAuditService audit;

    @Inject
    MemberResolutionService resolver;

    @Inject
    CompilationResolver compilations;

    @Inject
    org.niord.core.publication.series.criteria.DomainSeriesExpander domains;

    /**
     * O-4. Including a message the query already selects is refused.
     *
     * Not harmless duplication: the override would sit in the audit trail
     * claiming somebody decided to add a message that was already there, and if
     * the criteria later narrow, that stale INCLUDE quietly keeps a message the
     * query no longer selects -- a membership nobody chose, explained by a
     * decision nobody made.
     *
     * ASKED OF THE LIVE RESOLUTION, because there is nothing else to ask. Member
     * rows are written by the freeze and curation is only legal while the issue is
     * OPEN, so the two conditions never hold at once: counting rows meant counting
     * zero, every time, on every issue this rule could apply to. The rule was
     * unenforceable in the shape it was written in, and read as enforced.
     */
    @Transactional
    public IssueOverride include(PublicationIssue issue, String messageUid, User author, String reason) {
        if (selectedByCriteria(issue, messageUid)) {
            throw new IssueLifecycleService.TransitionRefusedException("OVERRIDE_ALREADY_A_MEMBER",
                    "message '" + messageUid + "' is already selected by this issue's criteria. An "
                            + "INCLUDE on top of it records a decision nobody made, and would keep "
                            + "the message if the criteria later narrowed.");
        }
        return addOverride(issue, messageUid, OverrideKind.INCLUDE, author, reason, AuditAction.OVERRIDE_INCLUDED);
    }

    /**
     * Whether the issue's own derivation already puts this message in it.
     *
     * The frozen rows for an issue that has them, the live derivation for one that
     * does not -- the criteria for a query-backed issue, the compiled union for a
     * compilation. A derivation that cannot be taken -- an unresolvable document,
     * a series with no query and no source -- answers "no": refusing a curation
     * because the criteria could not be run would block the one action that still
     * works on a misconfigured series.
     */
    private boolean selectedByCriteria(PublicationIssue issue, String messageUid) {
        // BOTH DERIVED SOURCES, because the question is "did this issue's own
        // derivation put it here" and a compilation's derivation writes COMPILED.
        // An include on top of a row a source issue already printed records a
        // decision nobody made, exactly as one on top of a criteria match does.
        Long frozen = em.createQuery(
                        "SELECT COUNT(m) FROM IssueMember m WHERE m.issue = :i AND m.messageUid = :uid "
                                + "AND m.source IN :derived", Long.class)
                .setParameter("i", issue)
                .setParameter("uid", messageUid)
                .setParameter("derived", List.of(MemberSource.CRITERIA, MemberSource.COMPILED))
                .getSingleResult();
        if (frozen > 0) {
            return true;
        }

        PublicationSeries series = issue.getSeries();
        MembershipRegime regime = MembershipRegime.of(series);
        if (regime == MembershipRegime.NONE) {
            return false;
        }
        try {
            if (regime == MembershipRegime.COMPILED) {
                // ONE existence query, and never the union: this is asked per
                // message on a curation, and a running annual's union is upwards
                // of a thousand rows that would be thrown away.
                return compilations.contains(series.getSourceSeries(),
                        new Interval(issue.getIntervalFrom(), new Date()), messageUid);
            }
            ResolvedCriteria criteria = EffectiveCriteria.resolvedFor(issue, domains);
            if (criteria == null) {
                return false;
            }
            // Resolved WITHOUT the curation: the question is what the query
            // selects, and folding the existing overrides in would make an earlier
            // include look like a criteria match.
            return resolver.resolve(criteria, new Interval(issue.getIntervalFrom(), new Date()))
                    .members().contains(messageUid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Transactional
    public IssueOverride exclude(PublicationIssue issue, String messageUid, User author, String reason) {
        return addOverride(issue, messageUid, OverrideKind.EXCLUDE, author, reason, AuditAction.OVERRIDE_EXCLUDED);
    }

    /**
     * The same decision over a selection, and it is ALL OR NOTHING.
     *
     * A curator excluding eleven messages is taking one decision about a list,
     * not eleven decisions that happen to arrive together. Applying the nine that
     * resolve and refusing the two that do not would leave the issue in a state
     * nobody asked for and no single message would say so -- so every uid is
     * checked before any override is written, and the refusal names EVERY
     * offender rather than the first, because a curator who has to discover them
     * one request at a time will give up before the list is clean.
     */
    @Transactional
    public List<IssueOverride> curate(PublicationIssue issue, List<String> messageUids,
                                      OverrideKind kind, User author, String reason) {
        assertOpen(issue);
        assertReason(reason);
        if (messageUids == null || messageUids.isEmpty()) {
            throw new IssueLifecycleService.TransitionRefusedException("NO_MESSAGES",
                    "a curation decision names at least one message");
        }
        if (messageUids.size() > MAX_CURATED_AT_ONCE) {
            throw new IssueLifecycleService.TransitionRefusedException("TOO_MANY_MESSAGES",
                    "at most " + MAX_CURATED_AT_ONCE + " messages in one decision; " + messageUids.size()
                            + " were named");
        }

        List<String> unique = messageUids.stream().distinct().toList();
        List<String> unknown = unique.stream().filter(uid -> !messageExists(uid)).toList();
        if (!unknown.isEmpty()) {
            throw new IssueLifecycleService.TransitionRefusedException("OVERRIDE_MESSAGE_NOT_FOUND",
                    "no message has uid " + String.join(", ", unknown)
                            + ". Nothing was recorded: a decision about a list is taken over the whole "
                            + "list, and a partly applied one is a state nobody asked for.");
        }
        if (kind == OverrideKind.INCLUDE) {
            // The same status floor include() applies per message, asked of the
            // whole selection first so the refusal names every offender and
            // nothing is written.
            Map<String, Status> statuses = new LinkedHashMap<>();
            em.createQuery("SELECT m.uid, m.status FROM Message m WHERE m.uid IN :uids", Object[].class)
                    .setParameter("uids", unique)
                    .getResultList()
                    .forEach(row -> statuses.put((String) row[0], (Status) row[1]));
            List<String> notPublic = unique.stream()
                    .filter(uid -> !isPublic(statuses.get(uid)))
                    .map(uid -> uid + " (" + statuses.get(uid) + ")")
                    .toList();
            if (!notPublic.isEmpty()) {
                throw notPublic(String.join(", ", notPublic));
            }
        }

        List<IssueOverride> written = new ArrayList<>();
        for (String uid : unique) {
            written.add(kind == OverrideKind.INCLUDE
                    ? include(issue, uid, author, reason)
                    : exclude(issue, uid, author, reason));
        }
        return written;
    }

    /**
     * Withdraw a curation decision, by the message it was about.
     *
     * Addressed by message rather than by override id because that is what the
     * caller is looking at: a row in the member list with a why-line saying
     * somebody put it there. An override that is not there is not an error worth
     * refusing over -- the desired state is "no override for this message", and
     * it already holds.
     */
    @Transactional
    public void clear(PublicationIssue issue, String messageUid, User actor, String reason) {
        assertOpen(issue);
        assertReason(reason);
        List<IssueOverride> existing = em.createQuery(
                        "SELECT o FROM IssueOverride o WHERE o.issue = :i AND o.messageUid = :uid",
                        IssueOverride.class)
                .setParameter("i", issue).setParameter("uid", messageUid).getResultList();
        if (existing.isEmpty()) {
            throw new IssueLifecycleService.TransitionRefusedException("OVERRIDE_NOT_FOUND",
                    "no curation decision on this issue names message '" + messageUid + "'");
        }
        for (IssueOverride override : existing) {
            remove(override, actor, reason.trim());
        }
    }

    /** One decision, at most this many messages. */
    public static final int MAX_CURATED_AT_ONCE = 500;

    private boolean messageExists(String uid) {
        return em.createQuery("SELECT COUNT(m) FROM Message m WHERE m.uid = :uid", Long.class)
                .setParameter("uid", uid).getSingleResult() > 0;
    }

    /**
     * RI-1 for a manual include: only a message in a public status.
     *
     * An INCLUDE puts a message into the issue's member set, which is frozen at
     * publish and printed into the public document. Every other route into a
     * member set applies the public-status rule (MembershipPredicate.PUBLIC_STATUSES);
     * a manual include is held to the same rule when it is written, so a draft,
     * a verified or a deleted message -- which the curator's domain may not even
     * be able to see -- can never be frozen into a published issue. A public
     * status is never left again, so the check cannot go stale before the freeze.
     *
     * An EXCLUDE is not checked: excluding a message puts nothing into a document.
     */
    private static boolean isPublic(Status status) {
        return status != null && MembershipPredicate.PUBLIC_STATUSES.contains(status);
    }

    /** The refusal of an include naming a message that is not in a public status. */
    private static IssueLifecycleService.TransitionRefusedException notPublic(String offenders) {
        return new IssueLifecycleService.TransitionRefusedException(OVERRIDE_MESSAGE_NOT_PUBLIC,
                "only a message in a public status " + MembershipPredicate.PUBLIC_STATUSES
                        + " can be included; not public: " + offenders + ". A manual include is "
                        + "printed into the issue's public document. Nothing was recorded.");
    }

    /** The wire code of that refusal: 409, because the same request succeeds once the message is published. */
    public static final String OVERRIDE_MESSAGE_NOT_PUBLIC = "OVERRIDE_MESSAGE_NOT_PUBLIC";

    /**
     * A reason, of a length somebody can actually read.
     *
     * The floor is what separates a reason from a keystroke: "x" records that
     * somebody typed something, which is worse than nothing because it looks like
     * a decision was explained. The ceiling keeps a rail readable.
     */
    private static void assertReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON || trimmed.length() > MAX_REASON) {
            throw new IssueLifecycleService.TransitionRefusedException("OVERRIDE_REASON_REQUIRED",
                    "a curation decision must say why, in between " + MIN_REASON + " and " + MAX_REASON
                            + " characters; an unexplained override is unreviewable later");
        }
    }

    static final int MIN_REASON = 3;
    static final int MAX_REASON = 512;

    @Transactional
    public void remove(IssueOverride override, User actor) {
        // Self-invocation, so Spring's proxy does not re-apply the @Transactional on
        // the overload below -- which changes nothing: this method's own transaction
        // is already open and REQUIRED would have joined it anyway.
        remove(override, actor, null);
    }

    /** Withdraw one override, recording why where a reason was given. */
    @Transactional
    public void remove(IssueOverride override, User actor, String reason) {
        assertOpen(override.getIssue());
        PublicationIssue issue = override.getIssue();
        String uid = override.getMessageUid();
        em.remove(em.contains(override) ? override : em.merge(override));
        // The member set changed, and the issue's own stamp is what says so --
        // the preview's freshness is read against it.
        issue.setUpdated(new Date());
        em.merge(issue);
        // And its revision moves with it. A child row coming or going does not
        // touch the parent's counter on its own, so without this two curators who
        // both loaded the issue at revision 7 would both commit at revision 7 and
        // the second decision would silently replace the first.
        StaleVersionGuard.forceIncrement(em, issue);
        audit.override(issue, actor, AuditAction.OVERRIDE_REMOVED, uid, reason);
    }

    public List<IssueOverride> forIssue(PublicationIssue issue) {
        return em.createQuery("SELECT o FROM IssueOverride o WHERE o.issue = :i ORDER BY o.id",
                        IssueOverride.class)
                .setParameter("i", issue).getResultList();
    }

    private IssueOverride addOverride(PublicationIssue issue, String messageUid, OverrideKind kind,
                                      User author, String reason, AuditAction action) {
        assertOpen(issue);
        assertReason(reason);

        // One override per message per issue: two would be either redundant or
        // contradictory, and neither is something to store.
        // O-6. An override naming a message that does not exist is a hard error.
        // Silently keeping it would leave a curation decision that can never
        // apply, and the annex report takes its heading from the first member --
        // so the visible result of a bad uid is an untitled PDF rather than a
        // complaint.
        List<Status> statuses = em.createQuery(
                        "SELECT m.status FROM Message m WHERE m.uid = :uid", Status.class)
                .setParameter("uid", messageUid)
                .getResultList();
        if (statuses.isEmpty()) {
            throw new IssueLifecycleService.TransitionRefusedException("OVERRIDE_MESSAGE_NOT_FOUND",
                    "no message has uid '" + messageUid + "'. An override that names nothing can "
                            + "never apply, and would sit in the audit trail looking like a decision.");
        }
        // RI-1 for a manual include, on the one path every include takes: see isPublic.
        if (kind == OverrideKind.INCLUDE && !isPublic(statuses.get(0))) {
            throw notPublic(messageUid + " (" + statuses.get(0) + ")");
        }

        List<IssueOverride> existing = em.createQuery(
                        "SELECT o FROM IssueOverride o WHERE o.issue = :i AND o.messageUid = :uid",
                        IssueOverride.class)
                .setParameter("i", issue).setParameter("uid", messageUid).getResultList();
        for (IssueOverride old : existing) {
            em.remove(old);
        }
        // FLUSHED before the replacement is persisted, and this is load-bearing.
        // Hibernate orders its action queue inserts-before-deletes, so without it
        // the new row reaches the database while the old one is still there --
        // which is a silent no-op until UNIQUE (issue_id, messageUid) exists, and
        // a constraint violation the moment it does. The invariant was specified
        // as a data-model rule and asserted here all along ("two overrides for one
        // message were kept" is the failure message); only the ordering that makes
        // it survivable at the database level was missing.
        em.flush();

        IssueOverride override = new IssueOverride();
        override.setIssue(issue);
        override.setMessageUid(messageUid);
        override.setKind(kind);
        override.setAuthor(author);
        override.setReason(reason);
        em.persist(override);
        // The member set changed; see remove().
        issue.setUpdated(new Date());
        em.merge(issue);
        StaleVersionGuard.forceIncrement(em, issue);

        audit.override(issue, author, action, messageUid, reason);
        return override;
    }

    private static void assertOpen(PublicationIssue issue) {
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw new IssueLifecycleService.TransitionRefusedException("ISSUE_NOT_OPEN",
                    "the member set is frozen; changing it now would rewrite what was published");
        }
    }
}
