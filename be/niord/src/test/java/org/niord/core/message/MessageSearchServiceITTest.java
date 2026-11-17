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
import org.niord.core.area.AreaService;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.user.User;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the message-search behaviour of {@link MessageService}: the JPA-Criteria
 * search path of {@link MessageService#search(MessageSearchParams)} /
 * {@code searchPagedMessageIds(...)}, the lightweight {@link MessageService#searchMessageIds}
 * id-matcher, and the predicate/sort/paging semantics defined in
 * {@link MessageSearchParams} and {@code CriteriaHelper}.
 *
 * <p>Every test builds its own messages with {@code createMessage(...)} (which flushes) and then
 * searches inside the SAME transaction, so the criteria query sees the test's flushed-but-uncommitted
 * rows. Since C4b the fixture also holds the sample's 34 messages (12 of them in {@code dma-nm}), so
 * a test asserts on its own messages, or on a count the series held before plus its own, never on a
 * series' bare count. Each test is wrapped in {@link TestTransaction} so its messages are rolled back.
 *
 * <p>Deliberately NOT covered here: the free-text {@code query(...)} path, which delegates to the
 * committed filesystem Lucene index and therefore cannot see in-transaction messages.
 *
 * <p>The publish/short-id tests trigger {@code MessageSeriesService.newMessageNumber(...)}, which
 * takes the number inside the publishing transaction, here the {@link TestTransaction}: the number
 * rolls back with the published Message row (SERIES6). The tests never assert an absolute short-id
 * value — they read whatever short id the publish flow assigned and assert relative matching
 * behaviour, so they do not depend on what other suites left in the sequence.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSearchServiceITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached DRAFT message template - createMessage substitutes the persisted series. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Persists the given draft template and returns the managed message. */
    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Convenience: create a draft NW (COASTAL_WARNING) message in series dma-nw. */
    private Message createNw(String title) {
        return create(newDraft("dma-nw", Type.COASTAL_WARNING, title));
    }

    /** Convenience: create a draft NM (PERMANENT_NOTICE) message in series dma-nm. */
    private Message createNm(String title) {
        return create(newDraft("dma-nm", Type.PERMANENT_NOTICE, title));
    }

    /**
     * Resolves the seeded "Denmark" area (the root of the Danish area tree) by its stable name and
     * returns its numeric id as a String. PostgreSQL's per-entity sequences assign different numeric
     * ids than the old MySQL shared sequence, so seed ids (formerly area 1192) are no longer stable;
     * tests must look the entity up by a business key (its name) and reuse the resolved id, which both
     * the {@code withArea(..)} builder and {@code MessageSearchParams.areaIds(..)} consume as a String.
     */
    private String seededAreaId() {
        org.niord.core.area.Area area = areaService.findByName("Denmark", "en", null);
        assertNotNull(area, "seeded area 'Denmark' must exist");
        return area.getId().toString();
    }

    /**
     * Resolves the seeded "Light" category by its stable name (formerly category 1802) and returns its
     * numeric id as a String. See {@link #seededAreaId()} for why the numeric id is resolved at runtime.
     */
    private String seededCategoryId() {
        org.niord.core.category.Category category = categoryService.findByName("Light", "en", null);
        assertNotNull(category, "seeded category 'Light' must exist");
        return category.getId().toString();
    }

    /** Runs the criteria search and returns the matched message ids in result order. */
    private List<Integer> searchIds(MessageSearchParams params) {
        return messageSearchService.search(params).getData().stream()
                .map(Message::getId)
                .collect(Collectors.toList());
    }

    /**
     * Sets the sort fields on the params and returns it as a {@link MessageSearchParams}.
     * The fluent {@code sortBy(..)/sortOrder(..)} setters are declared on the base
     * {@link org.niord.model.search.PagedSearchParamsVo} and return that base type, so we set them
     * here and hand back the concrete subtype.
     */
    private MessageSearchParams sorted(MessageSearchParams params, String sortBy, SortOrder order) {
        params.sortBy(sortBy);
        params.sortOrder(order);
        return params;
    }

    private static Date date(String yyyy_MM_dd) {
        String[] p = yyyy_MM_dd.split("-");
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 12, 0, 0);
        return c.getTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // Status filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByStatusReturnsOnlyMatchingStatus() {
        Message draft = createNw("draft-msg");
        Message verified = messageStatus(createNw("verified-msg"), Status.VERIFIED);

        List<Integer> draftIds = searchIds(new MessageSearchParams().statuses(Status.DRAFT));
        assertTrue(draftIds.contains(draft.getId()), "DRAFT message must match a DRAFT-status filter");
        assertFalse(draftIds.contains(verified.getId()), "VERIFIED message must not match a DRAFT-status filter");

        List<Integer> verifiedIds = searchIds(new MessageSearchParams().statuses(Status.VERIFIED));
        assertTrue(verifiedIds.contains(verified.getId()));
        assertFalse(verifiedIds.contains(draft.getId()));
    }

    @Test
    @Transactional
    void searchByMultipleStatusesUnionsMatches() {
        Message draft = createNw("draft-msg");
        Message verified = messageStatus(createNw("verified-msg"), Status.VERIFIED);
        Message deleted = messageStatus(createNw("deleted-msg"), Status.DELETED);

        List<Integer> ids = searchIds(new MessageSearchParams().statuses(Status.DRAFT, Status.VERIFIED));
        assertTrue(ids.contains(draft.getId()));
        assertTrue(ids.contains(verified.getId()));
        assertFalse(ids.contains(deleted.getId()),
                "a status outside the filter set must be excluded");
    }

    @Test
    @Transactional
    void searchByStatusWithNoMatchesIsEmpty() {
        // The only message THIS test creates is a DRAFT, so a PUBLISHED-status filter must not surface it.
        // We assert the draft is excluded (and that every row the filter does return is genuinely PUBLISHED)
        // rather than asserting a globally-empty result: the status filter spans all series, and the
        // committed-row REST ITs (MessageRest*ITTest) can leave a stray committed PUBLISHED message behind
        // whose best-effort @AfterEach cleanup failed - a stray that has nothing to do with this test's
        // DRAFT-vs-PUBLISHED contract.
        Message draft = createNw("only-a-draft");
        assertEquals(Status.DRAFT, draft.getStatus(), "the created message must be a DRAFT");

        PagedSearchResultVo<Message> result =
                messageSearchService.search(new MessageSearchParams().statuses(Status.PUBLISHED));

        List<Integer> ids = result.getData().stream().map(Message::getId).collect(Collectors.toList());
        assertFalse(ids.contains(draft.getId()),
                "a DRAFT message must not be returned by a PUBLISHED-status filter");
        assertTrue(result.getData().stream().allMatch(m -> m.getStatus() == Status.PUBLISHED),
                "every message returned by a PUBLISHED-status filter must have status PUBLISHED");
    }

    // ------------------------------------------------------------------------------------------- //
    // Type / main-type filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByTypeReturnsOnlyMatchingType() {
        Message coastal = createNw("coastal");
        Message local = create(newDraft("dma-nw", Type.LOCAL_WARNING, "local"));

        List<Integer> ids = searchIds(new MessageSearchParams().types(Set.of(Type.COASTAL_WARNING)));
        assertTrue(ids.contains(coastal.getId()));
        assertFalse(ids.contains(local.getId()),
                "a LOCAL_WARNING must not match a COASTAL_WARNING type filter");
    }

    @Test
    @Transactional
    void searchByMainTypeReturnsOnlyMatchingMainType() {
        Message nw = createNw("nw-msg");
        Message nm = createNm("nm-msg");

        List<Integer> nwIds = searchIds(new MessageSearchParams().mainTypes(Set.of(MainType.NW)));
        assertTrue(nwIds.contains(nw.getId()));
        assertFalse(nwIds.contains(nm.getId()), "an NM message must not match an NW main-type filter");

        List<Integer> nmIds = searchIds(new MessageSearchParams().mainTypes(Set.of(MainType.NM)));
        assertTrue(nmIds.contains(nm.getId()));
        assertFalse(nmIds.contains(nw.getId()));
    }

    @Test
    @Transactional
    void searchByTypeAndMainTypeCombineAsAnd() {
        Message coastalNw = createNw("coastal-nw");
        Message permNm = createNm("perm-nm");

        // type=COASTAL_WARNING (NW) AND mainType=NM => contradictory => no matches
        List<Integer> ids = searchIds(new MessageSearchParams()
                .types(Set.of(Type.COASTAL_WARNING))
                .mainTypes(Set.of(MainType.NM)));
        assertFalse(ids.contains(coastalNw.getId()));
        assertFalse(ids.contains(permNm.getId()));
    }

    // ------------------------------------------------------------------------------------------- //
    // Message-series filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchBySeriesIdReturnsOnlyMatchingSeries() {
        Message nw = createNw("nw-msg");
        Message nm = createNm("nm-msg");

        List<Integer> nwIds = searchIds(new MessageSearchParams().seriesIds(Set.of("dma-nw")));
        assertTrue(nwIds.contains(nw.getId()));
        assertFalse(nwIds.contains(nm.getId()));

        List<Integer> bothIds = searchIds(new MessageSearchParams().seriesIds(Set.of("dma-nw", "dma-nm")));
        assertTrue(bothIds.contains(nw.getId()));
        assertTrue(bothIds.contains(nm.getId()));
    }

    @Test
    @Transactional
    void searchByUnknownSeriesIdIsEmpty() {
        createNw("nw-msg");
        PagedSearchResultVo<Message> result =
                messageSearchService.search(new MessageSearchParams().seriesIds(Set.of("no-such-series")));
        assertEquals(0, result.getTotal());
    }

    // ------------------------------------------------------------------------------------------- //
    // Area filter (lineage prefix match)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByAreaIdMatchesOnAreaLineageAndExcludesUnrelated() {
        String areaId = seededAreaId();
        Message withArea = create(withArea(newDraft("dma-nw", Type.COASTAL_WARNING, "with-area"), areaId));
        Message withoutArea = createNw("without-area");

        List<Integer> ids = searchIds(new MessageSearchParams().areaIds(Set.of(areaId)));
        assertTrue(ids.contains(withArea.getId()),
                "a message in the seeded area must match areaIds={" + areaId + "}");
        assertFalse(ids.contains(withoutArea.getId()),
                "a message with no area must not match an area filter");
    }

    @Test
    @Transactional
    void searchByUnknownAreaIdIsEmpty() {
        create(withArea(newDraft("dma-nw", Type.COASTAL_WARNING, "with-area"), seededAreaId()));
        PagedSearchResultVo<Message> result =
                messageSearchService.search(new MessageSearchParams().areaIds(Set.of("999999")));
        assertEquals(0, result.getTotal(),
                "an unknown area id resolves to no area predicate and matches nothing");
    }

    // ------------------------------------------------------------------------------------------- //
    // Category and chart filters
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByCategoryIdMatchesOnCategoryLineage() {
        String categoryId = seededCategoryId();
        Message withCat = create(withCategory(newDraft("dma-nw", Type.COASTAL_WARNING, "with-cat"), categoryId));
        Message withoutCat = createNw("without-cat");

        List<Integer> ids = searchIds(new MessageSearchParams().categoryIds(Set.of(categoryId)));
        assertTrue(ids.contains(withCat.getId()));
        assertFalse(ids.contains(withoutCat.getId()));
    }

    @Test
    @Transactional
    void searchByChartNumberMatchesAttachedChart() {
        Message withChart = create(withChart(newDraft("dma-nw", Type.COASTAL_WARNING, "with-chart"), "D"));
        Message withoutChart = createNw("without-chart");

        List<Integer> ids = searchIds(new MessageSearchParams().chartNumbers(Set.of("D")));
        assertTrue(ids.contains(withChart.getId()));
        assertFalse(ids.contains(withoutChart.getId()));
    }

    @Test
    @Transactional
    void searchByWrongChartNumberExcludesMessage() {
        Message withChartD = create(withChart(newDraft("dma-nw", Type.COASTAL_WARNING, "chart-d"), "D"));

        List<Integer> ids = searchIds(new MessageSearchParams().chartNumbers(Set.of("C")));
        assertFalse(ids.contains(withChartD.getId()),
                "a message charted to 'D' must not match a chart filter of 'C'");
    }

    // ------------------------------------------------------------------------------------------- //
    // Date filters
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByPublishDateOverlapIncludesAndExcludes() {
        Message inRange = newDraft("dma-nw", Type.COASTAL_WARNING, "in-range");
        inRange.setPublishDateFrom(date("2026-06-10"));
        inRange.setPublishDateTo(date("2026-06-20"));
        inRange = create(inRange);

        Message before = newDraft("dma-nw", Type.COASTAL_WARNING, "before");
        before.setPublishDateFrom(date("2026-01-01"));
        before.setPublishDateTo(date("2026-01-05"));
        before = create(before);

        // Filter window 2026-06-01 .. 2026-06-30 overlaps inRange but not the January message.
        MessageSearchParams params = new MessageSearchParams()
                .dateType(DateType.PUBLISH_DATE)
                .from(date("2026-06-01"))
                .to(date("2026-06-30"));
        List<Integer> ids = searchIds(params);
        assertTrue(ids.contains(inRange.getId()), "an overlapping publish interval must match");
        assertFalse(ids.contains(before.getId()), "a non-overlapping interval must be excluded");
    }

    @Test
    @Transactional
    void searchByPublishDateMatchesOpenEndedInterval() {
        // An open-ended (publishDateTo == null) message overlaps any window after its from date.
        Message openEnded = newDraft("dma-nw", Type.COASTAL_WARNING, "open-ended");
        openEnded.setPublishDateFrom(date("2026-03-01"));
        openEnded.setPublishDateTo(null);
        openEnded = create(openEnded);

        MessageSearchParams params = new MessageSearchParams()
                .dateType(DateType.PUBLISH_DATE)
                .from(date("2026-09-01"))
                .to(date("2026-09-30"));
        List<Integer> ids = searchIds(params);
        assertTrue(ids.contains(openEnded.getId()),
                "an open-ended publish interval (null end) must overlap a later window");
    }

    @Test
    @Transactional
    void searchByEventDateOverlapIncludesAndExcludes() {
        Message inRange = withEventDates(newDraft("dma-nw", Type.COASTAL_WARNING, "event-in"),
                date("2026-07-10"), date("2026-07-15"));
        inRange = create(inRange);

        Message outOfRange = withEventDates(newDraft("dma-nw", Type.COASTAL_WARNING, "event-out"),
                date("2026-02-01"), date("2026-02-05"));
        outOfRange = create(outOfRange);

        MessageSearchParams params = new MessageSearchParams()
                .dateType(DateType.EVENT_DATE)
                .from(date("2026-07-01"))
                .to(date("2026-07-31"));
        List<Integer> ids = searchIds(params);
        assertTrue(ids.contains(inRange.getId()), "an overlapping event interval must match");
        assertFalse(ids.contains(outOfRange.getId()), "a non-overlapping event interval must be excluded");
    }

    @Test
    @Transactional
    void searchByCreatedDateBracketsTheCreationTimestamp() {
        Message msg = createNw("created-now");
        Date now = msg.getCreated();
        assertNotNull(now, "created timestamp must be populated on persist");

        // A window that brackets "now" must match.
        Date from = new Date(now.getTime() - 60_000);
        Date to = new Date(now.getTime() + 60_000);
        List<Integer> hit = searchIds(new MessageSearchParams()
                .dateType(DateType.CREATED_DATE).from(from).to(to));
        assertTrue(hit.contains(msg.getId()));

        // A window entirely in the past must not match.
        List<Integer> miss = searchIds(new MessageSearchParams()
                .dateType(DateType.CREATED_DATE)
                .from(date("2000-01-01")).to(date("2000-12-31")));
        assertFalse(miss.contains(msg.getId()));
    }

    @Test
    @Transactional
    void searchByUpdatedDateBracketsTheUpdatedTimestamp() {
        Message msg = createNw("updated-now");
        Date now = msg.getUpdated();
        assertNotNull(now, "updated timestamp must be populated on persist");

        List<Integer> hit = searchIds(new MessageSearchParams()
                .dateType(DateType.UPDATED_DATE)
                .from(new Date(now.getTime() - 60_000))
                .to(new Date(now.getTime() + 60_000)));
        assertTrue(hit.contains(msg.getId()));

        List<Integer> miss = searchIds(new MessageSearchParams()
                .dateType(DateType.UPDATED_DATE)
                .from(date("2000-01-01")).to(date("2000-12-31")));
        assertFalse(miss.contains(msg.getId()));
    }

    @Test
    @Transactional
    void searchByPublishFromDateUsesPublishStartOnly() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "publish-from");
        msg.setPublishDateFrom(date("2026-05-15"));
        msg.setPublishDateTo(date("2026-05-20"));
        msg = create(msg);

        // PUBLISH_FROM_DATE brackets the publishDateFrom (15th); the window contains it.
        List<Integer> hit = searchIds(new MessageSearchParams()
                .dateType(DateType.PUBLISH_FROM_DATE)
                .from(date("2026-05-10")).to(date("2026-05-16")));
        assertTrue(hit.contains(msg.getId()));

        // A window AFTER the start date (but covering the to-date) must NOT match PUBLISH_FROM_DATE.
        List<Integer> miss = searchIds(new MessageSearchParams()
                .dateType(DateType.PUBLISH_FROM_DATE)
                .from(date("2026-05-18")).to(date("2026-05-25")));
        assertFalse(miss.contains(msg.getId()),
                "PUBLISH_FROM_DATE must look at the start date only, not the end date");
    }

    @Test
    @Transactional
    void searchByPublishToDateUsesPublishEndOnly() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "publish-to");
        msg.setPublishDateFrom(date("2026-05-15"));
        msg.setPublishDateTo(date("2026-05-20"));
        msg = create(msg);

        // PUBLISH_TO_DATE brackets the publishDateTo (20th).
        List<Integer> hit = searchIds(new MessageSearchParams()
                .dateType(DateType.PUBLISH_TO_DATE)
                .from(date("2026-05-18")).to(date("2026-05-25")));
        assertTrue(hit.contains(msg.getId()));

        // A window covering only the start date must NOT match PUBLISH_TO_DATE.
        List<Integer> miss = searchIds(new MessageSearchParams()
                .dateType(DateType.PUBLISH_TO_DATE)
                .from(date("2026-05-10")).to(date("2026-05-16")));
        assertFalse(miss.contains(msg.getId()),
                "PUBLISH_TO_DATE must look at the end date only, not the start date");
    }

    @Test
    @Transactional
    void searchDefaultsToPublishDateWhenNoDateTypeGiven() {
        Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "default-date-type");
        msg.setPublishDateFrom(date("2026-08-10"));
        msg.setPublishDateTo(date("2026-08-20"));
        msg = create(msg);

        // No dateType set -> defaults to PUBLISH_DATE overlap.
        List<Integer> ids = searchIds(new MessageSearchParams()
                .from(date("2026-08-01")).to(date("2026-08-31")));
        assertTrue(ids.contains(msg.getId()),
                "a missing dateType must default to PUBLISH_DATE overlap semantics");
    }

    // ------------------------------------------------------------------------------------------- //
    // Pagination
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void paginationReturnsRequestedPageWithFullTotal() {
        // The series holds the fixture's messages too (the sample's dma-nm messages), so the total is
        // what the series held before plus these 5, and the pages are cut where these 5 sit last.
        long before = dmaNmTotal();
        for (int i = 0; i < 5; i++) {
            createNm("page-msg-" + i);
        }
        long total = before + 5;

        MessageSearchParams page0 = sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "id", SortOrder.ASC);
        page0.maxSize(2).page(0);
        PagedSearchResultVo<Message> r0 = messageSearchService.search(page0);
        assertEquals(total, r0.getTotal(), "total must be the full unpaged count");
        assertEquals(2, r0.getData().size(), "page 0 must contain maxSize rows");

        // The last page: a single row when the total is odd, two when it is even
        int lastPage = (int) ((total - 1) / 2);
        MessageSearchParams last = sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "id", SortOrder.ASC);
        last.maxSize(2).page(lastPage);
        PagedSearchResultVo<Message> rLast = messageSearchService.search(last);
        assertEquals(total, rLast.getTotal());
        assertEquals(total % 2 == 1 ? 1 : 2, rLast.getData().size(),
                "the last page must contain the remaining rows");
    }

    /** The unpaged count of the series dma-nm, which holds the fixture's (the sample's) NM messages */
    private long dmaNmTotal() {
        return messageSearchService.search(new MessageSearchParams().seriesIds(Set.of("dma-nm"))).getTotal();
    }

    @Test
    @Transactional
    void paginationBeyondLastPageReturnsEmptyDataButFullTotal() {
        // The fixture's dma-nm messages count too: the total is the series' before plus these 3
        long before = dmaNmTotal();
        for (int i = 0; i < 3; i++) {
            createNm("beyond-msg-" + i);
        }
        MessageSearchParams params = new MessageSearchParams().seriesIds(Set.of("dma-nm"));
        int beyond = (int) ((before + 3) / 2) + 10;
        params.maxSize(2).page(beyond);
        PagedSearchResultVo<Message> result = messageSearchService.search(params);
        assertEquals(before + 3, result.getTotal(), "total is independent of the requested page");
        assertTrue(result.getData().isEmpty(), "a page past the end yields no data");
    }

    @Test
    @Transactional
    void pagingWithAHugePageNumberDoesNotOverflowTheOffset() throws Exception {
        // page * maxSize is beyond the int range. Called directly, below the catch-all in search(), an
        // offset computed in int arithmetic would fail the list slicing; in long arithmetic the page
        // is simply past the end.
        // The fixture's dma-nm messages count too: the total is the series' before plus these 3
        long before = dmaNmTotal();
        for (int i = 0; i < 3; i++) {
            createNm("huge-page-msg-" + i);
        }
        MessageSearchParams params = new MessageSearchParams().seriesIds(Set.of("dma-nm"));
        params.maxSize(1000).page(Integer.MAX_VALUE);
        PagedSearchResultVo<Message> result = new PagedSearchResultVo<>();

        List<Integer> page = messageSearchService.searchPagedMessageIds(params, result);

        assertTrue(page.isEmpty(), "a page far past the end yields no ids");
        assertEquals(before + 3, result.getTotal(), "the total is still counted");
    }

    // ------------------------------------------------------------------------------------------- //
    // Sorting
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void sortByIdAscAndDescOrderMessages() {
        Message a = createNm("sort-a");
        Message b = createNm("sort-b");
        Message c = createNm("sort-c");
        // dma-nm is YEARLY but these drafts have no number/year, so id sort falls back to id order.

        List<Integer> asc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "id", SortOrder.ASC));
        // The three created ids in ascending order.
        List<Integer> expectedAsc = List.of(a.getId(), b.getId(), c.getId());
        assertEquals(expectedAsc, onlyOurs(asc, expectedAsc));

        List<Integer> desc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "id", SortOrder.DESC));
        List<Integer> expectedDesc = List.of(c.getId(), b.getId(), a.getId());
        assertEquals(expectedDesc, onlyOurs(desc, expectedDesc));
    }

    @Test
    @Transactional
    void sortByPublishDateOrdersChronologically() {
        Message early = newDraft("dma-nm", Type.PERMANENT_NOTICE, "pub-early");
        early.setPublishDateFrom(date("2026-01-01"));
        early = create(early);

        Message mid = newDraft("dma-nm", Type.PERMANENT_NOTICE, "pub-mid");
        mid.setPublishDateFrom(date("2026-06-01"));
        mid = create(mid);

        Message late = newDraft("dma-nm", Type.PERMANENT_NOTICE, "pub-late");
        late.setPublishDateFrom(date("2026-12-01"));
        late = create(late);

        List<Integer> asc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "publish_date", SortOrder.ASC));
        List<Integer> expectedAsc = List.of(early.getId(), mid.getId(), late.getId());
        assertEquals(expectedAsc, onlyOurs(asc, expectedAsc));

        List<Integer> desc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "publish_date", SortOrder.DESC));
        List<Integer> expectedDesc = List.of(late.getId(), mid.getId(), early.getId());
        assertEquals(expectedDesc, onlyOurs(desc, expectedDesc));
    }

    @Test
    @Transactional
    void sortByEventDateOrdersChronologically() {
        Message early = create(withEventDates(newDraft("dma-nm", Type.PERMANENT_NOTICE, "ev-early"),
                date("2026-02-01"), date("2026-02-02")));
        Message late = create(withEventDates(newDraft("dma-nm", Type.PERMANENT_NOTICE, "ev-late"),
                date("2026-11-01"), date("2026-11-02")));

        List<Integer> asc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "date", SortOrder.ASC));
        List<Integer> expectedAsc = List.of(early.getId(), late.getId());
        assertEquals(expectedAsc, onlyOurs(asc, expectedAsc));

        List<Integer> desc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "date", SortOrder.DESC));
        List<Integer> expectedDesc = List.of(late.getId(), early.getId());
        assertEquals(expectedDesc, onlyOurs(desc, expectedDesc));
    }

    @Test
    @Transactional
    void sortByFollowUpDateOrdersChronologically() {
        Message early = newDraft("dma-nm", Type.PERMANENT_NOTICE, "fu-early");
        early.setFollowUpDate(date("2026-03-01"));
        early = create(early);

        Message late = newDraft("dma-nm", Type.PERMANENT_NOTICE, "fu-late");
        late.setFollowUpDate(date("2026-10-01"));
        late = create(late);

        List<Integer> asc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "follow_up_date", SortOrder.ASC));
        List<Integer> expectedAsc = List.of(early.getId(), late.getId());
        assertEquals(expectedAsc, onlyOurs(asc, expectedAsc));

        List<Integer> desc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "follow_up_date", SortOrder.DESC));
        List<Integer> expectedDesc = List.of(late.getId(), early.getId());
        assertEquals(expectedDesc, onlyOurs(desc, expectedDesc));
    }

    @Test
    @Transactional
    void sortByAreaPlacesGeneralMessagesLast() {
        // A message with an area sorts before a message with no area (no area => treeSortOrder 999999).
        Message withArea = create(withArea(newDraft("dma-nm", Type.PERMANENT_NOTICE, "area-msg"), seededAreaId()));
        Message general = createNm("general-msg");

        List<Integer> asc = searchIds(sorted(
                new MessageSearchParams().seriesIds(Set.of("dma-nm")), "area", SortOrder.ASC));
        List<Integer> ours = onlyOurs(asc, List.of(withArea.getId(), general.getId()));
        assertEquals(List.of(withArea.getId(), general.getId()), ours,
                "the message with an area must sort before the general (area-less) message");
    }

    // ------------------------------------------------------------------------------------------- //
    // Empty / over-restrictive criteria
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void overRestrictiveCriteriaYieldEmptyResult() {
        createNw("nw-msg"); // exists, but cannot satisfy the contradictory filter

        PagedSearchResultVo<Message> result = messageSearchService.search(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .mainTypes(Set.of(MainType.NM)));   // dma-nw is NW, contradicts NM
        assertEquals(0, result.getTotal());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @Transactional
    void noCriteriaReturnsCreatedMessages() {
        Message a = createNw("any-a");
        Message b = createNm("any-b");

        List<Integer> ids = searchIds(new MessageSearchParams());
        assertTrue(ids.contains(a.getId()));
        assertTrue(ids.contains(b.getId()));
    }

    // ------------------------------------------------------------------------------------------- //
    // searchMessageIds (id matcher)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchMessageIdsReturnsEmptyForBlankText() {
        assertTrue(messageService.searchMessageIds("en", null, 10, false, false, false).isEmpty());
        assertTrue(messageService.searchMessageIds("en", "", 10, false, false, false).isEmpty());
        assertTrue(messageService.searchMessageIds("en", "   ", 10, false, false, false).isEmpty());
    }

    @Test
    @Transactional
    void searchMessageIdsMatchesExactUid() {
        Message msg = createNw("uid-match");
        String uid = msg.getUid();
        assertNotNull(uid);

        List<MessageIdMatch> matches = messageService.searchMessageIds("en", uid, 10, false, false, false);
        assertTrue(matches.stream().anyMatch(m ->
                        m.getType() == MessageIdMatch.MatchType.UID && uid.equals(m.getMessageId())),
                "an exact UID must yield a UID match");
    }

    @Test
    @Transactional
    void searchMessageIdsMatchesPartialShortId() {
        Message published = publishNw("short-id-match");
        String shortId = published.getShortId();
        assertNotNull(shortId, "a published NW message must be assigned a short id");
        // Use a partial fragment of the short id (drop the last char).
        String partial = shortId.substring(0, Math.max(1, shortId.length() - 1));

        List<MessageIdMatch> matches = messageService.searchMessageIds("en", partial, 10, false, false, false);
        assertTrue(matches.stream().anyMatch(m ->
                        m.getType() == MessageIdMatch.MatchType.SHORT_ID && shortId.equals(m.getMessageId())),
                "a partial short-id fragment must yield a SHORT_ID match for the message");
    }

    @Test
    @Transactional
    void searchMessageIdsIncludesTextMatchWhenRequested() {
        String txt = "free-text-fragment";
        List<MessageIdMatch> withText = messageService.searchMessageIds("en", txt, 10, true, false, false);
        assertFalse(withText.isEmpty());
        MessageIdMatch first = withText.get(0);
        assertEquals(MessageIdMatch.MatchType.TEXT, first.getType(),
                "includeText must prepend a TEXT match");
        assertEquals(txt, first.getMessageId());

        List<MessageIdMatch> withoutText = messageService.searchMessageIds("en", txt, 10, false, false, false);
        assertTrue(withoutText.stream().noneMatch(m -> m.getType() == MessageIdMatch.MatchType.TEXT),
                "without includeText there must be no TEXT match");
    }

    @Test
    @Transactional
    void searchMessageIdsHonoursIncludeDeletedForShortIdMatches() {
        // Build a DELETED message that carries a known short id. A DRAFT keeps any short id set on it
        // (createMessage does not clear it) and DRAFT -> DELETED is a legal transition.
        String shortId = "ITDEL-0001";
        Message draft = newDraft("dma-nw", Type.COASTAL_WARNING, "deleted-short-id");
        draft.setShortId(shortId);
        Message deleted = messageStatus(create(draft), Status.DELETED);
        assertEquals(Status.DELETED, deleted.getStatus());
        assertEquals(shortId, deleted.getShortId());

        // Without includeDeleted, the DELETED message's short id must be filtered out.
        List<MessageIdMatch> excluded = messageService.searchMessageIds("en", shortId, 10, false, false, false);
        assertTrue(excluded.stream().noneMatch(m ->
                        m.getType() == MessageIdMatch.MatchType.SHORT_ID && shortId.equals(m.getMessageId())),
                "a DELETED message's short id must be excluded when includeDeleted=false");

        // With includeDeleted, it must be returned.
        List<MessageIdMatch> included = messageService.searchMessageIds("en", shortId, 10, true, true, false);
        assertTrue(included.stream().anyMatch(m ->
                        m.getType() == MessageIdMatch.MatchType.SHORT_ID && shortId.equals(m.getMessageId())),
                "a DELETED message's short id must be included when includeDeleted=true");
    }

    @Test
    @Transactional
    void searchMessageIdsSetsTitleAndStatusOnShortIdMatch() {
        Message published = publishNw("titled-short-id");
        String shortId = published.getShortId();

        List<MessageIdMatch> matches = messageService.searchMessageIds("en", shortId, 10, false, false, false);
        MessageIdMatch match = matches.stream()
                .filter(m -> m.getType() == MessageIdMatch.MatchType.SHORT_ID && shortId.equals(m.getMessageId()))
                .findFirst()
                .orElse(null);
        assertNotNull(match, "the published short id must produce a SHORT_ID match");
        assertEquals(Status.PUBLISHED, match.getStatus(),
                "a SHORT_ID match must carry the message status");
        assertNotNull(match.getTitle());
        assertTrue(match.getTitle().contains("titled-short-id"),
                "a SHORT_ID match must carry the localized message title");
    }

    /**
     * Regression test: {@code searchMessageIds} ranks short-id
     * matches by where the search fragment occurs in the short id - an earlier occurrence must sort
     * first ("earliest match first"). The previous key comparator
     * ({@code id1.indexOf(txt) < id2.indexOf(txt) ? 1 : 0}) only ever returned {@code 1} or {@code 0},
     * so it was neither antisymmetric nor transitive: it actually inverted the intended order (and on a
     * larger match set risked a {@code java.lang.IllegalArgumentException: Comparator contract violated}).
     * The corrected {@code Integer.compare(id1.indexOf(txt), id2.indexOf(txt))} restores the documented
     * ordering. With the old comparator the assertion below failed (the earlier match sorted last).
     */
    @Test
    @Transactional
    void searchMessageIdsRanksEarlierShortIdMatchFirst() {
        // Two DRAFT messages whose short ids both contain "qzx" case-insensitively at different offsets:
        //   "qzx-1"  -> case-insensitive indexOf("qzx") == 0  (earlier - must rank first)
        //   "AB-QZX" -> case-insensitive indexOf("qzx") == 3  (later)
        Message earlyDraft = newDraft("dma-nw", Type.COASTAL_WARNING, "msg2-early");
        earlyDraft.setShortId("qzx-1");
        Message early = create(earlyDraft);
        assertEquals("qzx-1", early.getShortId());

        Message lateDraft = newDraft("dma-nw", Type.COASTAL_WARNING, "msg2-late");
        lateDraft.setShortId("AB-QZX");
        Message late = create(lateDraft);
        assertEquals("AB-QZX", late.getShortId());

        List<MessageIdMatch> matches = messageService.searchMessageIds("en", "qzx", 10, false, false, false);

        List<String> shortIdOrder = matches.stream()
                .filter(m -> m.getType() == MessageIdMatch.MatchType.SHORT_ID)
                .map(MessageIdMatch::getMessageId)
                .collect(Collectors.toList());

        int idxEarly = shortIdOrder.indexOf("qzx-1");
        int idxLate = shortIdOrder.indexOf("AB-QZX");
        assertTrue(idxEarly >= 0, "the qzx-1 short id must be returned as a SHORT_ID match");
        assertTrue(idxLate >= 0, "the AB-QZX short id must be returned as a SHORT_ID match");
        assertTrue(idxEarly < idxLate,
                "the short id whose fragment occurs earlier (qzx-1, offset 0) must rank before the later "
                        + "match (AB-QZX, offset 3); got order " + shortIdOrder);
    }

    /**
     * publicOnly is the floor for a caller that may not see drafts: only published, cancelled and
     * expired messages match, by short id or by uid, and it overrides includeDeleted.
     */
    @Test
    @Transactional
    void searchMessageIdsPublicOnlyMatchesOnlyPublicMessages() {
        String fragment = "itpo" + System.nanoTime();
        Message draftTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "public-only-draft");
        draftTemplate.setShortId(fragment + "-d");
        Message draft = create(draftTemplate);
        Message deletedTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "public-only-deleted");
        deletedTemplate.setShortId(fragment + "-x");
        messageStatus(create(deletedTemplate), Status.DELETED);

        // Unrestricted (with deleted) both non-public short ids match...
        Set<String> unrestricted = shortIdMatches(messageService.searchMessageIds("en", fragment, 10, false, true, false));
        assertEquals(Set.of(fragment + "-d", fragment + "-x"), unrestricted);

        // ...under the public floor neither does, although deleted ones were asked for.
        assertTrue(shortIdMatches(messageService.searchMessageIds("en", fragment, 10, false, true, true)).isEmpty(),
                "publicOnly must hide DRAFT and DELETED short ids, whatever includeDeleted says");

        // A draft's uid gives no UID match under the floor, but does without it.
        assertTrue(messageService.searchMessageIds("en", draft.getUid(), 10, false, false, true).stream()
                        .noneMatch(m -> m.getType() == MessageIdMatch.MatchType.UID),
                "publicOnly must not confirm a draft's uid");
        assertTrue(messageService.searchMessageIds("en", draft.getUid(), 10, false, false, false).stream()
                        .anyMatch(m -> m.getType() == MessageIdMatch.MatchType.UID));

        // A published message still matches under the floor, by short id and by uid.
        Message published = publishNw("public-only-published");
        assertTrue(shortIdMatches(messageService.searchMessageIds("en", published.getShortId(), 10, false, false, true))
                .contains(published.getShortId()), "publicOnly still matches a published short id");
        assertTrue(messageService.searchMessageIds("en", published.getUid(), 10, false, false, true).stream()
                        .anyMatch(m -> m.getType() == MessageIdMatch.MatchType.UID
                                && published.getUid().equals(m.getMessageId())),
                "publicOnly still matches a published uid");
    }

    /** The search text is data: LIKE wildcards in it match only themselves. */
    @Test
    @Transactional
    void searchMessageIdsTreatsLikeWildcardsLiterally() {
        String nano = String.valueOf(System.nanoTime());
        String plain = "itlk" + nano;
        String withPercent = "itlk%" + nano;
        String withBang = "itlk!" + nano;   // '!' is the escape character of the query
        for (String shortId : new String[] { plain, withPercent, withBang }) {
            Message template = newDraft("dma-nw", Type.COASTAL_WARNING, "like-" + shortId);
            template.setShortId(shortId);
            create(template);
        }

        // As LIKE wildcards, "_" and "%" in these texts would match the plain short id
        String underscoreSpelling = "itlk_" + nano.substring(1);
        String percentSpelling = "itlk%" + nano.substring(nano.length() - 3);
        assertFalse(shortIdMatches(messageService.searchMessageIds("en", underscoreSpelling, 10, false, false, false))
                .contains(plain), "an _ in the text must not act as a single-character wildcard");
        assertFalse(shortIdMatches(messageService.searchMessageIds("en", percentSpelling, 10, false, false, false))
                .contains(plain), "a % in the text must not act as a wildcard");

        // ...while a short id that contains the character itself is still found, and only that one
        assertEquals(Set.of(withPercent),
                shortIdMatches(messageService.searchMessageIds("en", withPercent, 10, false, false, false)),
                "a literal % matches the same character in a short id");
        assertEquals(Set.of(withBang),
                shortIdMatches(messageService.searchMessageIds("en", withBang, 10, false, false, false)),
                "the escape character in the text is matched literally");
    }

    /** The short ids of the SHORT_ID matches in a searchMessageIds result. */
    private static Set<String> shortIdMatches(List<MessageIdMatch> matches) {
        return matches.stream()
                .filter(m -> m.getType() == MessageIdMatch.MatchType.SHORT_ID)
                .map(MessageIdMatch::getMessageId)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------------------------------- //
    // User (creator/updater) filter — DB3/PG1 case-insensitive username matching
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByCreatorUsernameIsCaseInsensitive() {
        // DB3/PG1: MessageSearchService now matches the creating/updating username with
        // equalsIgnoreCase (lower(col) = lower(:v)). Under MySQL's default _ai_ci collation the '='
        // match was implicitly case-insensitive; PostgreSQL's '=' is case-sensitive, so a lower-cased
        // creator query must still match a mixed-case stored username (mirrors Message.findByShortId).
        User creator = new User();
        creator.setUsername("CaseFoldAlice");
        creator.setFirstName("F");
        creator.setLastName("L");
        em.persist(creator);
        em.flush();

        // createMessage() stamps createdBy with the (anonymous -> null) current user, so attach the
        // mixed-case creator afterwards; the managed message is flushed to issue the UPDATE.
        Message msg = createNw("case-creator");
        msg.setCreatedBy(creator);
        em.flush();

        // Lower-cased query must match the mixed-case stored username. Fails pre-fix on PostgreSQL,
        // where '=' is case-sensitive so 'casefoldalice' != stored 'CaseFoldAlice'.
        List<Integer> lower = searchIds(new MessageSearchParams()
                .username("casefoldalice")
                .userType(MessageSearchParams.UserType.CREATED_BY));
        assertTrue(lower.contains(msg.getId()),
                "creator search must be case-insensitive: 'casefoldalice' must match stored 'CaseFoldAlice'");

        // Guard: exact-case query must still match (the fix must not regress same-case matching).
        List<Integer> exact = searchIds(new MessageSearchParams()
                .username("CaseFoldAlice")
                .userType(MessageSearchParams.UserType.CREATED_BY));
        assertTrue(exact.contains(msg.getId()));
    }

    // ------------------------------------------------------------------------------------------- //
    // Builder helpers (entity wiring)
    // ------------------------------------------------------------------------------------------- //

    private Message withArea(Message msg, String areaId) {
        msg.setAreas(List.of(areaService.findByAreaId(areaId)));
        return msg;
    }

    private Message withCategory(Message msg, String categoryId) {
        msg.setCategories(List.of(categoryService.findByCategoryId(categoryId)));
        return msg;
    }

    private Message withChart(Message msg, String chartNumber) {
        msg.setCharts(List.of(chartService.findByChartNumber(chartNumber)));
        return msg;
    }

    private Message withEventDates(Message msg, Date from, Date to) {
        msg.getParts().get(0).addEventDates(new DateInterval(false, from, to));
        return msg;
    }

    /** Transitions a message to the given status through legal single-step transitions. */
    private Message messageStatus(Message msg, Status target) {
        try {
            return messageService.updateStatus(msg.getUid(), target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Publishes a fresh NW draft via DRAFT -> VERIFIED -> PUBLISHED and returns the published message.
     * The draft is stamped with a far-past publish date so the publish flow takes its number from the
     * year-2001 message number sequence rather than the current-year one. The number is taken inside the
     * {@link TestTransaction} and rolls back with it, but the test's transaction holds that year's number
     * row until then; the far-past year keeps it off the current-year row whose next number
     * {@code MessageSeriesRestServiceITTest} asserts is 1.
     */
    private Message publishNw(String title) {
        try {
            Message draft = newDraft("dma-nw", Type.COASTAL_WARNING, title);
            draft.setPublishDateFrom(date("2001-06-15"));
            Message msg = create(draft);
            messageService.updateStatus(msg.getUid(), Status.VERIFIED);
            return messageService.updateStatus(msg.getUid(), Status.PUBLISHED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Filters a result id list down to (and in the order of) our expected ids, ignoring strays. */
    private List<Integer> onlyOurs(List<Integer> actual, List<Integer> expected) {
        return actual.stream().filter(expected::contains).collect(Collectors.toList());
    }
}
