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

import org.springframework.stereotype.Service;

import static org.niord.core.geojson.Feature.WGS84_SRID;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY_UNACK;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN_UNACK;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.db.SpatialIntersectsPredicate;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.message.MessageSearchParams.UserType;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.publication.series.IssueLifecycleService.TransitionRefusedException;
import org.niord.core.service.BaseService;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.niord.model.search.PagedSearchResultVo;

import jakarta.inject.Inject;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;

/**
 * Business interface for searching messages
 */
@Service
@SuppressWarnings("unused")
public class MessageSearchService extends BaseService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageSearchService.class);

    @Inject
    NiordApp app;

    @Inject
    SettingsService settingsService;

    @Inject
    AreaService areaService;

    @Inject
    CategoryService categoryService;

    @Inject
    MessageTagService messageTagService;

    @Inject
    UserService userService;

    @Inject
    MessageLuceneIndex messageLuceneIndex;

    @Inject
    MessagePostgresIndex messagePostgresIndex;

    // The narrow interface rather than PublicationService: that service already
    // injects this one, and injecting it back would close the cycle.
    @Inject
    PublicationMemberSetSource memberSetSource;

    /**
     * What the publication= ids designate, resolved at most once per search.
     *
     * A REST layer resolves first, because it needs the answer before this point
     * to decide whether to widen, and it derives the tier from its caller.
     * Anything reaching the search directly resolves here instead -- without this
     * branch those callers parse publication= and then ignore it, which is the
     * behaviour they have today.
     *
     * THE PUBLIC TIER, for the direct path, and the direction matters.
     *
     * In practice exactly one kind of caller reaches here carrying publications: a
     * mailing-list trigger, whose stored message query is parsed by
     * MessageSearchParams.instantiate. Every other system caller builds its params
     * programmatically and never sets publications at all, so the tier is moot for
     * them. A mailing list sends to recipients this system does not control.
     *
     * So the two failures are not symmetric. At the internal tier, a trigger
     * naming an issue that is still OPEN mails the contents of an unpublished
     * issue to external recipients -- unrecoverable, because the mail is sent, and
     * invisible, because it looks like an ordinary send. At the public tier, that
     * same trigger is refused, the refusal is logged against the trigger id, and
     * the other triggers in the run are unaffected. The second failure is the one
     * to prefer.
     *
     * A caller that genuinely needs the internal tier resolves for itself and sets
     * the designation on the params, exactly as the two REST layers do.
     */
    private MemberSetDesignation designation(MessageSearchParams param) {
        if (param.getMemberSetDesignation() != null) {
            return param.getMemberSetDesignation();
        }

        MemberSetDesignation designation = param.getPublications().isEmpty()
                ? MemberSetDesignation.NONE
                : memberSetSource.designate(
                        param.getPublications(), PublicationMemberSetSource.Audience.PUBLIC);

        param.memberSetDesignation(designation);
        return designation;
    }

    /**
     * Returns the messages with the given UID or short ID.
     *
     * @param messageId the UID or short ID of the messages
     * @return the messages with the given message ID
     */
    private List<Message> findByMessageId(String messageId) {
        if (StringUtils.isBlank(messageId)) {
            return Collections.emptyList();
        }
        return em.createNamedQuery("Message.findByMessageId", Message.class)
                .setParameter("msgId", messageId.toLowerCase())
                .getResultList();
    }

    /***************************************/
    /** Message Searching                 **/
    /***************************************/

    /**
     * Main message search function.
     * <p>
     * A failed search is logged and answered with an empty result, which a caller cannot tell from a real answer of
     * zero messages. It remains for screens and scripts that only show what they find. A caller that answers the
     * public, mails people or acts on "nothing found", by cancelling or creating messages, uses
     * {@link #searchOrFail(MessageSearchParams)} instead.
     *
     * @param params the search parameters
     * @return the search result
     */
    public PagedSearchResultVo<Message> search(MessageSearchParams params) {

        try {
            return searchOrFail(params);

        } catch (TransitionRefusedException e) {
            // Never swallowed.
            //
            // The catch below turns any failure into an empty result, which a
            // caller cannot tell from a real answer of zero messages. An
            // unresolvable publication= id is exactly the failure this is meant
            // to make visible, so swallowing it here would replace "returns
            // everything" with "returns nothing", quietly, and call it fixed.
            throw e;

        } catch (Exception e) {
            log.error("Error performing search " + params + ": " + e, e);
            return new PagedSearchResultVo<>();
        }
    }

    /**
     * The message search of {@link #search(MessageSearchParams)}, which lets a failure through instead of answering
     * it with an empty result: an empty result is then a real answer of zero messages.
     * <p>
     * Used by the public API, the scheduled mailing-list mail and the mailing-list report, the Niord integration's
     * look-up of its own copies, a publication entering recording, the recompute of the area sort order and the
     * hourly firing-exercise run (which cancels the current exercises when it finds no template): each of them would
     * act on, or hand on, an empty answer as if nothing had been found.
     *
     * @param params the search parameters
     * @return the search result
     * @throws Exception when the search fails
     */
    public PagedSearchResultVo<Message> searchOrFail(MessageSearchParams params) throws Exception {
        PagedSearchResultVo<Message> result = new PagedSearchResultVo<>();

        List<Integer> pagedMsgIds = searchPagedMessageIds(params, result);

        // Fetch the cached messages
        List<Message> messages = getMessages(pagedMsgIds);
        result.setData(messages);
        result.updateSize();

        return result;
    }

    /**
     * Returns the message with the given IDs
     *
     * @param ids the message IDs
     * @return the message with the given IDs
     */
    private List<Message> getMessages(List<Integer> ids) {

        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> messages = em.createNamedQuery("Message.findByIds", Message.class)
                .setParameter("ids", ids)
                .getResultList();

        // Sort the result according to the order of the messages in the ID list
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            rank.putIfAbsent(ids.get(i), i);
        }
        messages.sort(Comparator.comparingInt(m -> rank.getOrDefault(m.getId(), Integer.MAX_VALUE)));

        return messages;
    }

    /**
     * Searches out the ID's of the paged result set of messages defined by the search parameters.
     * Also fills out the total result count of the message search result.
     *
     * @param param the search parameters
     * @param result the search result to update with the total result count
     * @return the paged list of message ID's
     */
    @SuppressWarnings("all")
    List<Integer> searchPagedMessageIds(MessageSearchParams param, PagedSearchResultVo<Message> result) throws Exception {

        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> tupleQuery = builder.createTupleQuery();

        // Select messages
        Root<Message> msgRoot = tupleQuery.from(Message.class);

        // Build the predicates based on the search parameters
        CriteriaHelper<Tuple> criteriaHelper = CriteriaHelper.initWithTupleQuery(em)
                .between(msgRoot.get("updated"), param.getUpdatedFrom(), param.getUpdatedTo());

        // Filter by dates
        if (param.getFrom() != null || param.getTo() != null) {
            DateType dateType = param.getDateType() != null
                    ? param.getDateType()
                    : DateType.PUBLISH_DATE;
            Date from = param.getFrom();
            Date to = param.getTo();
            switch (dateType) {
                case PUBLISH_DATE -> criteriaHelper.overlaps(msgRoot.get("publishDateFrom"), msgRoot.get("publishDateTo"), from, to);
                case EVENT_DATE -> criteriaHelper.overlaps(msgRoot.get("eventDateFrom"), msgRoot.get("eventDateTo"), from, to);
                case CREATED_DATE -> criteriaHelper.between(msgRoot.get("created"), from, to);
                case UPDATED_DATE -> criteriaHelper.between(msgRoot.get("updated"), from, to);
                case PUBLISH_FROM_DATE -> criteriaHelper.between(msgRoot.get("publishDateFrom"), from, to);
                case PUBLISH_TO_DATE -> criteriaHelper.between(msgRoot.get("publishDateTo"), from, to);
            }
        }

        // Main types and sub-types
        if (!param.getMainTypes().isEmpty()) {
            criteriaHelper.in(msgRoot.get("mainType"), param.getMainTypes());
        }
        if (!param.getTypes().isEmpty()) {
            criteriaHelper.in(msgRoot.get("type"), param.getTypes());
        }

        // Statuses
        if (!param.getStatuses().isEmpty()) {
            criteriaHelper.in(msgRoot.get("status"), param.getStatuses());
        }

        // Kinds (message classification: WARNING vs system-managed kinds such as the
        // auto-created CANCELLATION warnings; query-driven searches default to WARNING)
        if (!param.getKinds().isEmpty()) {
            criteriaHelper.in(msgRoot.get("kind"), param.getKinds());
        }

        // Search the free-text index for free text search. The backend is selected by the
        // "disableLucene" setting (default false = Lucene); when true the PostgreSQL backend is used.
        // This lets the PostgreSQL search be evaluated side-by-side before Lucene is removed.
        // Both backends rank each language query by score. freeTextRelevanceOrder preserves the
        // combined language-group order below when no explicit sort is requested.
        List<Integer> freeTextRelevanceOrder = null;
        if (param.requiresLuceneSearch()) {
            boolean disableLucene = Boolean.TRUE.equals(
                    settingsService.getBoolean(MessagePostgresIndex.DISABLE_LUCENE_SETTING));
            // Relevance is grouped by language: UI language first, then the remaining model
            // languages in app order. Each group keeps index score order; the first hit wins
            // for duplicates. Scores from separate language queries are not globally ranked.
            // A failure of the index is let through: answered as "no matching ids", it would
            // read as a real answer of zero messages.
            Set<Long> matches = new LinkedHashSet<>();
            // A literal query (a caller without an identity) reaches Lucene as plain words. The
            // PostgreSQL backend needs no flag: websearch_to_tsquery has no wildcard syntax.
            for (String language : app.getLanguages(param.getLanguage())) {
                matches.addAll(disableLucene
                        ? messagePostgresIndex.searchIndex(param.getQuery(), language, Integer.MAX_VALUE)
                        : messageLuceneIndex.searchIndex(param.getQuery(), language, Integer.MAX_VALUE,
                                param.isLiteralQuery()));
            }
            List<Long> ids = new ArrayList<>(matches);
            // Need to convert them to int as per message.id type
            List<Integer> idsInteger = ids.stream().map(l->l.intValue()).toList();
//            log.info("Found these messages for tree text query '" + param.getQuery() + " " + idsInteger);
            criteriaHelper.in(msgRoot.get("id"), idsInteger);
            freeTextRelevanceOrder = idsInteger;
        }

        // Message series
        if (!param.getSeriesIds().isEmpty()) {
            criteriaHelper.in(msgRoot.get("messageSeries").get("seriesId"), param.getSeriesIds());
        }

        // Filter by area, join over...
        if (!param.getAreaIds().isEmpty()) {
            Join<Message, Area> areas = msgRoot.join("areas", JoinType.LEFT);
            if (!param.getAreaIds().isEmpty()) {
                Predicate[] areaMatch = param.getAreaIds().stream()
                        .map(aid -> areaService.findByAreaId(aid))
                        .filter(Objects::nonNull)
                        .map(a -> builder.like(areas.get("lineage"), a.getLineage() + "%"))
                        .toArray(Predicate[]::new);
                criteriaHelper.add(builder.or(areaMatch));
            }
        }

        // Filter on categories
        if (!param.getCategoryIds().isEmpty()) {
            Join<Message, Category> categories = msgRoot.join("categories", JoinType.LEFT);
            Predicate[] categoryMatch = param.getCategoryIds().stream()
                    .map(cid -> categoryService.findByCategoryId(cid))
                    .filter(Objects::nonNull)
                    .map(c -> builder.like(categories.get("lineage"), c.getLineage() + "%"))
                    .toArray(Predicate[]::new);
            criteriaHelper.add(builder.or(categoryMatch));
        }

        // Filter on charts
        if (!param.getChartNumbers().isEmpty()) {
            Join<Message, Chart> charts = msgRoot.join("charts", JoinType.LEFT);
            Predicate[] chartMatch = param.getChartNumbers().stream()
                    .map(m -> builder.equal(charts.get("chartNumber"), m))
                    .toArray(Predicate[]::new);
            criteriaHelper.add(builder.or(chartMatch));
        }

        // Geometry
        Geometry extent = param.getExtent();
        if (extent != null) {
            // PostGIS uses the standard (lon, lat) axis order for SRID 4326 — the same order in which
            // JtsConverter builds the extent and stores feature geometry — so, unlike MySQL 8 (which
            // honours the SRID-4326 (lat, lon) axis order in ST_Within/ST_Intersects and therefore
            // required the extent coordinates to be swapped here), no coordinate reversal is needed.
            extent.setSRID(WGS84_SRID);
            Join<Message, MessagePart> partRoot = msgRoot.join("parts", JoinType.LEFT);
            Join<Message, FeatureCollection> fcRoot = partRoot.join("geometry", JoinType.LEFT);
            Join<FeatureCollection, Feature> fRoot = fcRoot.join("features", JoinType.LEFT);
            Predicate geomPredicate = new SpatialIntersectsPredicate(
                    getNodeBuilder(),
                    fRoot.get("geometry"),
                    extent,
                    false);

            if (param.getIncludeNoPos() != null && param.getIncludeNoPos().booleanValue()) {
                // search for message with no geometry in addition to messages within extent
                criteriaHelper.add(builder.or(builder.equal(msgRoot.get("hasGeometry"), false), geomPredicate));
            } else {
                // Only search for messages within extent
                criteriaHelper.add(geomPredicate);
            }
        }

        // Tags and publication member sets -- ONE disjunction, never two conjuncts.
        //
        // Two publication= ids used to become two tags, which this ORs. Giving the
        // member set its own conjunct instead would quietly turn that union into
        // an intersection: two issues would return the messages in BOTH rather
        // than in either, and a caller asking for two weeks of EfS would get
        // whatever the weeks have in common, usually nothing.
        MemberSetDesignation designation = designation(param);
        List<Predicate> memberSet = new ArrayList<>();

        // ONE join for both sources of tag. Two LEFT JOINs on the same collection
        // would multiply the rows before the OR ever ran.
        final Join<Message, MessageTag> tags =
                param.getTags().isEmpty() && designation.tagIds().isEmpty()
                        ? null
                        : msgRoot.join("tags", JoinType.LEFT);

        // The caller's own tag= parameter, VISIBILITY-CHECKED as it always was.
        //
        // An empty result here still means zero messages. The previous code got
        // that for free from builder.or() of an empty array; collecting predicates
        // into a shared list loses it, and losing it turns a tag the caller cannot
        // see into no filter at all.
        if (!param.getTags().isEmpty()) {
            String[] callerTagIds = param.getTags().toArray(new String[0]);
            List<Predicate> callerMatch = messageTagService.findTags(callerTagIds).stream()
                    .map(mt -> builder.equal(tags.get("id"), mt.getId()))
                    .collect(Collectors.toList());

            memberSet.add(callerMatch.isEmpty()
                    ? builder.disjunction()
                    : builder.or(callerMatch.toArray(new Predicate[0])));
        }

        if (designation.designatesMemberSet()) {
            // Publication-derived tags are NOT visibility-checked, and that is the
            // restriction lift rather than an oversight.
            //
            // findTags applies validateAccess against the CURRENT domain, so a
            // DOMAIN-typed publication tag is dropped whenever the caller is in
            // another domain -- or, on the public API, in no domain at all. The
            // predicate then collapses to FALSE and ?publication=<id> answers 200
            // with zero messages: precisely the "returns nothing, quietly" outcome
            // this redesign exists to remove, wearing the shape of a fix.
            //
            // A publication is an official, published document. Its contents do not
            // depend on which domain the reader happens to be browsing, and 17 live
            // publications have no domain at all.
            List<Integer> pubTagIds = designation.tagIds().isEmpty()
                    ? List.of()
                    : em.createNamedQuery("MessageTag.findTagsByTagIds", MessageTag.class)
                            .setParameter("tagIds", designation.tagIds())
                            .getResultList().stream()
                            .map(MessageTag::getId)
                            .collect(Collectors.toList());

            for (Integer tagId : pubTagIds) {
                memberSet.add(builder.equal(tags.get("id"), tagId));
            }

            if (!designation.memberUids().isEmpty()) {
                // A frozen snapshot is an explicit uid list. Served verbatim: no
                // date window, so none of adjustDateInterval's whole-day rounding.
                memberSet.add(msgRoot.get("uid").in(designation.memberUids()));
            }

            if (pubTagIds.isEmpty() && designation.memberUids().isEmpty()) {
                // The designation resolved but contributes nothing -- either it
                // genuinely has no members, or its tag no longer exists. Zero
                // messages. Never in (), and never a fall-through to the widening
                // branch, which is what returns every published message today.
                memberSet.add(builder.disjunction());
            }
        }

        if (!memberSet.isEmpty()) {
            criteriaHelper.add(builder.or(memberSet.toArray(new Predicate[0])));
        }

        // User
        if (StringUtils.isNotBlank(param.getUsername())) {
            UserType userType = param.getUserType() == null ? UserType.UPDATED_BY : param.getUserType();
            if (userType == UserType.CREATED_BY || userType == UserType.LAST_UPDATED_BY) {
                String joinCol = userType == UserType.CREATED_BY ? "createdBy" : "lastUpdatedBy";
                Join<Message, User> userRoot = msgRoot.join(joinCol, JoinType.LEFT);
                criteriaHelper.equalsIgnoreCase(userRoot.get("username"), param.getUsername());
            } else {
                Join<Message, MessageHistory> historyRoot = msgRoot.join("history", JoinType.LEFT);
                Join<MessageHistory, User> userRoot = historyRoot.join("user", JoinType.LEFT);
                criteriaHelper.equalsIgnoreCase(userRoot.get("username"), param.getUsername());
            }
        }

        // Comments
        if (param.getCommentsType() != null) {
            Join<Message, Comment> comments = msgRoot.join("comments", JoinType.LEFT);
            User user = userService.currentUser();
            Predicate own = user != null ? builder.equal(comments.get("user"), user) : null;
            Predicate exists = builder.isNotNull(comments.get("id"));
            Predicate unack = builder.isNull(comments.get("acknowledgedBy"));

            if (user != null && param.getCommentsType() == OWN) {
                criteriaHelper.add(own);
            } else if (user != null && param.getCommentsType() == OWN_UNACK) {
                criteriaHelper.add(builder.and(own, unack));
            } else if (param.getCommentsType() == ANY_UNACK) {
                criteriaHelper.add(builder.and(exists, unack));
            } else if (param.getCommentsType() == ANY) {
                criteriaHelper.add(exists);
            } else {
                // OWN / OWN_UNACK requested but there is no current user: a user-scoped "show only my
                // comments" filter that cannot identify a user must match nothing (fail closed) rather
                // than silently returning every message. builder.disjunction() is an always-false predicate.
                criteriaHelper.add(builder.disjunction());
            }
        }

        // Refenced messages
        if (StringUtils.isNotBlank(param.getMessageId())) {
            int levels = param.getReferenceLevels() == null ? 1 : param.getReferenceLevels();
            // NB: This is expensive queries - limit the levels
            levels = Math.max(0, Math.min(5, levels));
            // First, find messages referenced by the message ID
            Set<Integer> referencedIds = findReferencedMessageIds(new HashSet<>(), param.getMessageId(), levels);
            // Next, add messages referencing the message ID
            findReferencingMessageIds(referencedIds, param.getMessageId(), levels);
            criteriaHelper.in(msgRoot.get("id"), referencedIds);
        }

        // Promulgation types
        if (!param.getPromulgationTypes().isEmpty()) {
            Join<Message, BaseMessagePromulgation> promulgationJoin = msgRoot.join("promulgations", JoinType.LEFT);
            Join<BaseMessagePromulgation, PromulgationType> promulgationTypeJoin = promulgationJoin.join("type", JoinType.LEFT);
            criteriaHelper.equals(promulgationJoin.get("promulgate"), true);
            criteriaHelper.in(promulgationTypeJoin.get("typeId"), param.getPromulgationTypes());
        }

        // Determine the fields to fetch
        Join<Message, Area> areaRoot = null;
        Expression<?> treeSortOrder = null;
        List<Selection<?>> fields = new ArrayList<>();
        fields.add(msgRoot.get("id"));
        if (param.sortByEventDate()) {
            fields.add(msgRoot.get("eventDateFrom"));
            fields.add(msgRoot.get("eventDateTo"));
        } else if (param.sortByPublishDate()) {
            fields.add(msgRoot.get("publishDateFrom"));
            fields.add(msgRoot.get("publishDateTo"));
        } else if (param.sortByFollowUpDate()) {
            fields.add(msgRoot.get("followUpDate"));
        } else if (param.sortById()) {
            fields.add(msgRoot.get("year"));
            fields.add(msgRoot.get("number"));
            fields.add(msgRoot.get("publishDateFrom"));
        } else if (param.sortByArea()) {
            areaRoot = msgRoot.join("area", JoinType.LEFT);
            // General messages (without an associated area) should be sorted last
            treeSortOrder = builder.selectCase()
                    .when(builder.isNull(areaRoot.get("treeSortOrder")), 999999)
                    .otherwise(areaRoot.get("treeSortOrder"));
            fields.add(treeSortOrder);
            fields.add(msgRoot.get("areaSortOrder"));
            fields.add(msgRoot.get("year"));
            fields.add(msgRoot.get("number"));
        }
        Selection[] f = fields.toArray(new Selection<?>[fields.size()]);

        // Complete the query and fetch the message id's (and fields used for sorting)
        tupleQuery.multiselect(f)
                .distinct(true)
                .where(criteriaHelper.where());

        // Sort the query. A relevance sort (param.sortByRelevance() — the free-text default) intentionally
        // matches none of the branches below and emits no ORDER BY: the matched ids are re-ordered by the
        // backend's relevance ranking after the query runs (best match first; SortOrder is not applied).
        if (param.sortByEventDate()) {
            if (param.getSortOrder() == SortOrder.ASC) {
                tupleQuery.orderBy(
                        builder.asc(msgRoot.get("eventDateFrom")),
                        builder.asc(msgRoot.get("eventDateTo")),
                        builder.asc(msgRoot.get("id")));
            } else {
                tupleQuery.orderBy(
                        builder.desc(msgRoot.get("eventDateFrom")),
                        builder.desc(msgRoot.get("eventDateTo")),
                        builder.desc(msgRoot.get("id")));
            }
        } else if (param.sortByPublishDate()) {
            if (param.getSortOrder() == SortOrder.ASC) {
                tupleQuery.orderBy(
                        builder.asc(msgRoot.get("publishDateFrom")),
                        builder.asc(msgRoot.get("publishDateTo")),
                        builder.asc(msgRoot.get("id")));
            } else {
                tupleQuery.orderBy(
                        builder.desc(msgRoot.get("publishDateFrom")),
                        builder.desc(msgRoot.get("publishDateTo")),
                        builder.desc(msgRoot.get("id")));
            }
        } else if (param.sortByFollowUpDate()) {
            if (param.getSortOrder() == SortOrder.ASC) {
                tupleQuery.orderBy(
                        builder.asc(msgRoot.get("followUpDate")),
                        builder.asc(msgRoot.get("id")));
            } else {
                tupleQuery.orderBy(
                        builder.desc(msgRoot.get("followUpDate")),
                        builder.desc(msgRoot.get("id")));
            }
        } else if (param.sortById()) {
            if (param.getSortOrder() == SortOrder.ASC) {
                tupleQuery.orderBy(
                        builder.asc(msgRoot.get("year")),
                        builder.asc(msgRoot.get("number")),
                        builder.asc(msgRoot.get("publishDateFrom")),
                        builder.asc(msgRoot.get("id")));
            } else {
                tupleQuery.orderBy(
                        builder.desc(msgRoot.get("year")),
                        builder.desc(msgRoot.get("number")),
                        builder.desc(msgRoot.get("publishDateFrom")),
                        builder.desc(msgRoot.get("id")));
            }
        } else if (param.sortByArea()) {
            if (param.getSortOrder() == SortOrder.ASC) {
                tupleQuery.orderBy(
                        builder.asc(treeSortOrder),
                        builder.asc(msgRoot.get("areaSortOrder")),
                        builder.asc(msgRoot.get("year")),
                        builder.asc(msgRoot.get("number")),
                        builder.asc(msgRoot.get("id")));
            } else {
                tupleQuery.orderBy(
                        builder.desc(treeSortOrder),
                        builder.desc(msgRoot.get("areaSortOrder")),
                        builder.desc(msgRoot.get("year")),
                        builder.desc(msgRoot.get("number")),
                        builder.desc(msgRoot.get("id")));
            }
        }

        // Execute the query
        List<Tuple> totalResult = em
                .createQuery(tupleQuery)
                .getResultList();

        // Register the total result
        result.setTotal(totalResult.size());

        List<Integer> msgIds = totalResult.stream()
                .map(t -> (Integer) t.get(0))
                .collect(Collectors.toList());

        // A free-text search with no explicit sort is ordered by relevance ("best match first"): the JPA
        // query applied no ORDER BY in that case, so re-order the matched ids by their position in the
        // backend's relevance-ordered id list (Lucene score order / PostgreSQL ts_rank order).
        boolean orderByRelevance = freeTextRelevanceOrder != null && param.sortByRelevance();
        if (orderByRelevance) {
            Map<Integer, Integer> relevanceRank = new HashMap<>();
            for (int i = 0; i < freeTextRelevanceOrder.size(); i++) {
                relevanceRank.putIfAbsent(freeTextRelevanceOrder.get(i), i);
            }
            msgIds.sort(Comparator.comparingInt(id -> relevanceRank.getOrDefault(id, Integer.MAX_VALUE)));
        }

        // Extract and return the paged sub-list. Long arithmetic: page and maxSize come from the
        // caller, and page * maxSize overflows into a negative index long before either is refused.
        // A negative page reads as the first page, a non-positive maxSize as an empty page.
        long pageSize = Math.max(0, param.getMaxSize());
        long start = (long) Math.max(0, param.getPage()) * pageSize;
        int startIndex = (int) Math.min(msgIds.size(), start);
        int endIndex = (int) Math.min(msgIds.size(), start + pageSize);
        return msgIds.subList(startIndex, endIndex);
    }

    /**
     * Resolves the IDs of all messages referenced by the given message ID within the given number of levels
     * @param result the result
     * @param messageId the message to find referenced messages for
     * @param levels the levels
     * @return the result
     */
    private Set<Integer> findReferencedMessageIds(Set<Integer> result, String messageId, int levels) {
        for (Message message : findByMessageId(messageId)) {
            result.add(message.getId());
            if (levels > 0) {
                for (Reference reference : message.getReferences()) {
                    findReferencedMessageIds(result, reference.getMessageId(), levels - 1);
                }
            }
        }
        return result;
    }

    /**
     * Resolves the IDs of all messages referencing the given message ID within the given number of levels
     * @param result the result
     * @param messageId the message to find referencing messages for
     * @param levels the levels
     * @return the result
     * @noinspection all
     */
    private Set<Integer> findReferencingMessageIds(Set<Integer> result, String messageId, int levels) {
        for (Message message : findByMessageId(messageId)) {
            findReferencingMessageIds(result, message, levels);
        }
        return result;
    }

    /**
     * Resolves the IDs of all messages referencing the given message ID within the given number of levels
     * @param result the result
     * @param message the message to find referencing messages for
     * @param levels the levels
     * @return the result
     */
    private Set<Integer> findReferencingMessageIds(Set<Integer> result, Message message, int levels) {
        result.add(message.getId());
        if (levels > 0) {
            Set<String> messageIds = new HashSet<>();
            messageIds.add(message.getUid());
            if (message.getShortId() != null) {
                messageIds.add(message.getShortId().toLowerCase());
            }
            em.createNamedQuery("Message.findByReference", Message.class)
                    .setParameter("messageIds", messageIds)
                    .getResultList()
                    .forEach(msg -> findReferencingMessageIds(result, msg, levels - 1));
        }
        return result;
    }
}
