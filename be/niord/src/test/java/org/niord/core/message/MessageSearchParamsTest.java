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
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.domain.Domain;
import org.niord.core.message.MessageSearchParams.CommentsType;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.message.MessageSearchParams.UserType;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link MessageSearchParams}: the fluent builder, the URL/parameter-map
 * parsing in {@code instantiate(...)}, the sort-mode predicates, the domain sort-order fallback in
 * {@code checkSortByDomain}, the bounding-box {@code extent(...)} conversion, the {@code from/to(Long)}
 * epoch conversion and the {@code toString()} used for search logging.
 *
 * <p>These verify the search-parameter contract independently of Quarkus/persistence, so they are
 * expected to survive a future Quarkus-to-Spring migration unchanged.
 */
public class MessageSearchParamsTest {

    // ------------------------------------------------------------------------------------------- //
    // Default state
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void freshParamsHaveEmptyCollectionsAndNullScalars() {
        MessageSearchParams p = new MessageSearchParams();
        assertTrue(p.getStatuses().isEmpty());
        assertTrue(p.getTypes().isEmpty());
        assertTrue(p.getMainTypes().isEmpty());
        assertTrue(p.getKinds().isEmpty(), "programmatic searches are unfiltered by kind");
        assertTrue(p.getSeriesIds().isEmpty());
        assertTrue(p.getAreaIds().isEmpty());
        assertTrue(p.getCategoryIds().isEmpty());
        assertTrue(p.getChartNumbers().isEmpty());
        assertTrue(p.getTags().isEmpty());
        assertTrue(p.getPublications().isEmpty());
        assertTrue(p.getPromulgationTypes().isEmpty());
        assertNull(p.getQuery());
        assertNull(p.getLanguage());
        assertNull(p.getExtent());
        assertNull(p.getDateType());
        assertFalse(p.requiresLuceneSearch());
    }

    // ------------------------------------------------------------------------------------------- //
    // Fluent builder identity
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void chainingSettersReturnSameInstance() {
        MessageSearchParams p = new MessageSearchParams();
        assertSame(p, p.language("en"));
        assertSame(p, p.query("buoy"));
        assertSame(p, p.domain("niord-client-nw"));
        assertSame(p, p.messageId("NW-001-24"));
        assertSame(p, p.referenceLevels(2));
        assertSame(p, p.from(new Date()));
        assertSame(p, p.to(new Date()));
        assertSame(p, p.dateType(DateType.EVENT_DATE));
        assertSame(p, p.updatedFrom(new Date()));
        assertSame(p, p.updatedTo(new Date()));
        assertSame(p, p.username("editor"));
        assertSame(p, p.userType(UserType.CREATED_BY));
        assertSame(p, p.commentsType(CommentsType.OWN));
        assertSame(p, p.statuses(Status.DRAFT));
        assertSame(p, p.viewMode("map"));
        assertSame(p, p.includeNoPos(Boolean.TRUE));
    }

    // ------------------------------------------------------------------------------------------- //
    // requiresLuceneSearch
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void requiresLuceneSearchOnlyWhenQueryIsNonBlank() {
        assertFalse(new MessageSearchParams().requiresLuceneSearch());
        assertFalse(new MessageSearchParams().query(null).requiresLuceneSearch());
        assertFalse(new MessageSearchParams().query("").requiresLuceneSearch());
        assertFalse(new MessageSearchParams().query("   ").requiresLuceneSearch());
        assertTrue(new MessageSearchParams().query("lighthouse").requiresLuceneSearch());
        assertTrue(new MessageSearchParams().query("  buoy  ").requiresLuceneSearch());
    }

    // ------------------------------------------------------------------------------------------- //
    // Sort-mode predicates (driven by the inherited sortBy string, case-insensitive)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void sortByIdPredicate() {
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("id")).sortById());
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("ID")).sortById());
        assertFalse(((MessageSearchParams) new MessageSearchParams().sortBy("area")).sortById());
    }

    @Test
    public void sortByEventDatePredicateUsesTheLiteralDate() {
        MessageSearchParams p = (MessageSearchParams) new MessageSearchParams().sortBy("DATE");
        assertTrue(p.sortByEventDate());
        assertFalse(p.sortByPublishDate());
        assertFalse(p.sortById());
        assertFalse(p.sortByArea());
        assertFalse(p.sortByFollowUpDate());
    }

    @Test
    public void sortByPublishDatePredicate() {
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("publish_date")).sortByPublishDate());
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("PUBLISH_DATE")).sortByPublishDate());
    }

    @Test
    public void sortByFollowUpDatePredicate() {
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("follow_up_date")).sortByFollowUpDate());
    }

    @Test
    public void sortByAreaPredicate() {
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("area")).sortByArea());
        assertTrue(((MessageSearchParams) new MessageSearchParams().sortBy("AREA")).sortByArea());
    }

    @Test
    public void unknownSortByMatchesNoPredicate() {
        MessageSearchParams p = (MessageSearchParams) new MessageSearchParams().sortBy("bananas");
        assertFalse(p.sortById());
        assertFalse(p.sortByEventDate());
        assertFalse(p.sortByPublishDate());
        assertFalse(p.sortByFollowUpDate());
        assertFalse(p.sortByArea());
    }

    @Test
    public void nullSortByMatchesNoPredicate() {
        MessageSearchParams p = new MessageSearchParams();
        assertFalse(p.sortById());
        assertFalse(p.sortByArea());
    }

    // ------------------------------------------------------------------------------------------- //
    // checkSortByDomain - domain sort-order fallback + defaults
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void checkSortByDomainReturnsSameInstance() {
        MessageSearchParams p = new MessageSearchParams();
        assertSame(p, p.checkSortByDomain(null));
    }

    @Test
    public void checkSortByDomainAppliesAreaAscDefaultWhenNoDomain() {
        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(null);
        assertEquals("AREA", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
        assertTrue(p.sortByArea());
    }

    @Test
    public void checkSortByDomainUsesDomainSortOrderWhenUnset() {
        Domain domain = new Domain();
        domain.setMessageSortOrder("PUBLISH_DATE DESC");

        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(domain);

        assertEquals("PUBLISH_DATE", p.getSortBy());
        assertEquals(SortOrder.DESC, p.getSortOrder());
        assertTrue(p.sortByPublishDate());
    }

    @Test
    public void checkSortByDomainDoesNotOverrideExplicitSort() {
        Domain domain = new Domain();
        domain.setMessageSortOrder("PUBLISH_DATE DESC");

        MessageSearchParams p = new MessageSearchParams();
        p.sortBy("ID");
        p.sortOrder(SortOrder.ASC);
        p.checkSortByDomain(domain);

        assertEquals("ID", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    @Test
    public void checkSortByDomainWithBlankDomainOrderFallsBackToAreaAsc() {
        Domain domain = new Domain();
        domain.setMessageSortOrder("   ");

        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(domain);

        assertEquals("AREA", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    @Test
    public void checkSortByDomainWithSingleTokenOrderKeepsTokenAndDefaultsAscOrder() {
        // "AREA" has no order token; the split[1] access throws and is swallowed, so the sortBy is set
        // from token 0 and the order falls through to the ASC default.
        Domain domain = new Domain();
        domain.setMessageSortOrder("AREA");

        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(domain);

        assertEquals("AREA", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    // ------------------------------------------------------------------------------------------- //
    // from/to epoch conversion
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void fromAndToLongConvertEpochMillisToDate() {
        MessageSearchParams p = new MessageSearchParams();
        p.from(1_000L);
        p.to(2_000L);
        assertEquals(new Date(1_000L), p.getFrom());
        assertEquals(new Date(2_000L), p.getTo());
    }

    @Test
    public void fromAndToLongNullYieldNullDate() {
        MessageSearchParams p = new MessageSearchParams();
        p.from((Long) null);
        p.to((Long) null);
        assertNull(p.getFrom());
        assertNull(p.getTo());
    }

    @Test
    public void fromAndToDatePassThrough() {
        Date d1 = new Date(123_456L);
        Date d2 = new Date(789_000L);
        MessageSearchParams p = new MessageSearchParams();
        p.from(d1);
        p.to(d2);
        assertSame(d1, p.getFrom());
        assertSame(d2, p.getTo());
    }

    // ------------------------------------------------------------------------------------------- //
    // Status / type set helpers
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void statusesVarargsAndSetBothPopulate() {
        MessageSearchParams p1 = new MessageSearchParams().statuses(Status.DRAFT, Status.PUBLISHED);
        assertEquals(2, p1.getStatuses().size());
        assertTrue(p1.getStatuses().contains(Status.DRAFT));
        assertTrue(p1.getStatuses().contains(Status.PUBLISHED));

        Map<String, String[]> ignored = new HashMap<>();
        MessageSearchParams p2 = new MessageSearchParams();
        p2.statuses(java.util.Set.of(Status.EXPIRED));
        assertEquals(1, p2.getStatuses().size());
        assertTrue(p2.getStatuses().contains(Status.EXPIRED));
    }

    // ------------------------------------------------------------------------------------------- //
    // extent(minLat, minLon, maxLat, maxLon) -> JTS geometry (x=lon, y=lat)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void extentFromBoundsBuildsEnvelopeWithLonAsXAndLatAsY() {
        MessageSearchParams p = new MessageSearchParams();
        // minLat=54, minLon=8, maxLat=58, maxLon=13
        p.extent(54.0, 8.0, 58.0, 13.0);

        Geometry extent = p.getExtent();
        assertNotNull(extent);
        Envelope env = extent.getEnvelopeInternal();
        assertEquals(8.0, env.getMinX(), 1e-9);
        assertEquals(13.0, env.getMaxX(), 1e-9);
        assertEquals(54.0, env.getMinY(), 1e-9);
        assertEquals(58.0, env.getMaxY(), 1e-9);
    }

    @Test
    public void extentWithAllNullBoundsIsNull() {
        MessageSearchParams p = new MessageSearchParams();
        p.extent((Double) null, null, null, null);
        assertNull(p.getExtent());
    }

    @Test
    public void extentWithPartialBoundsFillsWorldDefaults() {
        // Only minLat supplied -> the other three default to the world bounds (-180/-90..180/90).
        MessageSearchParams p = new MessageSearchParams();
        p.extent(0.0, null, null, null);

        Geometry extent = p.getExtent();
        assertNotNull(extent);
        Envelope env = extent.getEnvelopeInternal();
        assertEquals(-180.0, env.getMinX(), 1e-9);
        assertEquals(180.0, env.getMaxX(), 1e-9);
        assertEquals(0.0, env.getMinY(), 1e-9);
        assertEquals(90.0, env.getMaxY(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate(Domain, Map<String,String[]>)
    // ------------------------------------------------------------------------------------------- //

    private static String[] v(String... values) {
        return values;
    }

    @Test
    public void instantiateParsesScalarAndCollectionParameters() {
        Map<String, String[]> req = new HashMap<>();
        req.put("lang", v("en"));
        req.put("query", v("buoy"));
        req.put("domain", v("niord-client-nw"));
        req.put("messageId", v("NW-001-24"));
        req.put("referenceLevels", v("3"));
        req.put("status", v("PUBLISHED", "CANCELLED"));
        req.put("mainType", v("NW"));
        req.put("type", v("LOCAL_WARNING"));
        req.put("messageSeries", v("dma-nw"));
        req.put("area", v("a1", "a2"));
        req.put("category", v("c1"));
        req.put("chart", v("101"));
        req.put("tag", v("t1"));
        req.put("publication", v("p1"));
        req.put("promulgationType", v("navtex"));
        req.put("username", v("editor"));
        req.put("userType", v("CREATED_BY"));
        req.put("comments", v("OWN_UNACK"));
        req.put("dateType", v("EVENT_DATE"));
        req.put("viewMode", v("map"));

        MessageSearchParams p = MessageSearchParams.instantiate(null, req);

        assertEquals("en", p.getLanguage());
        assertEquals("buoy", p.getQuery());
        assertEquals("niord-client-nw", p.getDomain());
        assertEquals("NW-001-24", p.getMessageId());
        assertEquals(Integer.valueOf(3), p.getReferenceLevels());
        assertEquals(2, p.getStatuses().size());
        assertTrue(p.getStatuses().contains(Status.PUBLISHED));
        assertTrue(p.getStatuses().contains(Status.CANCELLED));
        assertTrue(p.getMainTypes().contains(MainType.NW));
        assertTrue(p.getTypes().contains(Type.LOCAL_WARNING));
        assertTrue(p.getSeriesIds().contains("dma-nw"));
        assertEquals(2, p.getAreaIds().size());
        assertTrue(p.getCategoryIds().contains("c1"));
        assertTrue(p.getChartNumbers().contains("101"));
        assertTrue(p.getTags().contains("t1"));
        assertTrue(p.getPublications().contains("p1"));
        assertTrue(p.getPromulgationTypes().contains("navtex"));
        assertEquals("editor", p.getUsername());
        assertEquals(UserType.CREATED_BY, p.getUserType());
        assertEquals(CommentsType.OWN_UNACK, p.getCommentsType());
        assertEquals(DateType.EVENT_DATE, p.getDateType());
        assertEquals("map", p.getViewMode());
        assertTrue(p.requiresLuceneSearch());
    }

    @Test
    public void instantiateAppliesMaxSizeAndPageDefaults() {
        MessageSearchParams p = MessageSearchParams.instantiate(null, new HashMap<>());
        assertEquals(100, p.getMaxSize());
        assertEquals(0, p.getPage());
    }

    @Test
    public void instantiateHonoursExplicitMaxSizeAndPage() {
        Map<String, String[]> req = new HashMap<>();
        req.put("maxSize", v("25"));
        req.put("page", v("4"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(25, p.getMaxSize());
        assertEquals(4, p.getPage());
    }

    @Test
    public void instantiateDefaultsToAreaAscWhenNoSortAndNoDomain() {
        MessageSearchParams p = MessageSearchParams.instantiate(null, new HashMap<>());
        assertEquals("AREA", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    @Test
    public void instantiateParsesFromAndToEpochMillis() {
        Map<String, String[]> req = new HashMap<>();
        req.put("fromDate", v("1000"));
        req.put("toDate", v("5000"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(new Date(1000L), p.getFrom());
        assertEquals(new Date(5000L), p.getTo());
    }

    @Test
    public void instantiateParsesExtentAndIncludeNoPos() {
        Map<String, String[]> req = new HashMap<>();
        req.put("minLat", v("54"));
        req.put("minLon", v("8"));
        req.put("maxLat", v("58"));
        req.put("maxLon", v("13"));
        req.put("includeNoPos", v("true"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);

        assertNotNull(p.getExtent());
        Envelope env = p.getExtent().getEnvelopeInternal();
        assertEquals(8.0, env.getMinX(), 1e-9);
        assertEquals(58.0, env.getMaxY(), 1e-9);
        assertEquals(Boolean.TRUE, p.getIncludeNoPos());
    }

    @Test
    public void instantiateUsesDomainSortOrderWhenNoSortParam() {
        Domain domain = new Domain();
        domain.setMessageSortOrder("PUBLISH_DATE DESC");
        MessageSearchParams p = MessageSearchParams.instantiate(domain, new HashMap<>());
        assertEquals("PUBLISH_DATE", p.getSortBy());
        assertEquals(SortOrder.DESC, p.getSortOrder());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate - the "kind" parameter and its WARNING default
    //
    // Query-driven searches (everything parsed through instantiate: the UI list, reports,
    // exports, mailing-list trigger queries) default to ordinary WARNING messages; the
    // system-managed kinds (auto-created CANCELLATION warnings) must be requested explicitly.
    // Programmatic searches (new MessageSearchParams()) stay unfiltered - see the default-state
    // test above - so internal flows like the expiry sweeper keep seeing all kinds.
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateDefaultsKindsToWarningWhenUnspecified() {
        MessageSearchParams p = MessageSearchParams.instantiate(null, new HashMap<>());
        assertEquals(1, p.getKinds().size());
        assertTrue(p.getKinds().contains(MessageKind.WARNING));
    }

    @Test
    public void instantiateParsesExplicitKindWithoutAddingTheDefault() {
        Map<String, String[]> req = new HashMap<>();
        req.put("kind", v("CANCELLATION"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(1, p.getKinds().size());
        assertTrue(p.getKinds().contains(MessageKind.CANCELLATION));
        assertFalse(p.getKinds().contains(MessageKind.WARNING),
                "an explicit kind request must not be widened with the WARNING default");
    }

    @Test
    public void instantiateParsesMultipleKindValues() {
        Map<String, String[]> req = new HashMap<>();
        req.put("kind", v("WARNING", "CANCELLATION"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(2, p.getKinds().size());
        assertTrue(p.getKinds().contains(MessageKind.WARNING));
        assertTrue(p.getKinds().contains(MessageKind.CANCELLATION));
    }

    @Test
    public void instantiateRejectsUnknownKindEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("kind", v("NOT_A_KIND"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateSkipsTheKindDefaultForMessageIdReferenceLookups() {
        // A messageId search is ID-driven direct addressing (the reference tree of a message);
        // the search service ANDs the resolved reference-ID set with every other predicate, so
        // a WARNING default would filter e.g. a cancellation warning out of its own tree.
        Map<String, String[]> req = new HashMap<>();
        req.put("messageId", v("NW-001-24"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertTrue(p.getKinds().isEmpty(), "reference lookups must stay kind-independent by default");
    }

    @Test
    public void instantiateHonoursExplicitKindOnMessageIdLookups() {
        Map<String, String[]> req = new HashMap<>();
        req.put("messageId", v("NW-001-24"));
        req.put("kind", v("CANCELLATION"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(1, p.getKinds().size());
        assertTrue(p.getKinds().contains(MessageKind.CANCELLATION));
    }

    @Test
    public void kindsVarargsAndSetBothPopulate() {
        MessageSearchParams p1 = new MessageSearchParams().kinds(MessageKind.WARNING, MessageKind.CANCELLATION);
        assertEquals(2, p1.getKinds().size());

        MessageSearchParams p2 = new MessageSearchParams();
        p2.kinds(java.util.Set.of(MessageKind.BULLETIN));
        assertEquals(1, p2.getKinds().size());
        assertTrue(p2.getKinds().contains(MessageKind.BULLETIN));
    }

    @Test
    public void toStringRendersKindsWhenSet() {
        MessageSearchParams p = new MessageSearchParams().kinds(MessageKind.CANCELLATION);
        assertTrue(p.toString().contains("Kinds:"), p.toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate(Domain, String url)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateFromUrlParsesQueryString() {
        MessageSearchParams p = MessageSearchParams.instantiate(
                null, "/rest/messages/search?query=fog&status=PUBLISHED&maxSize=7");
        assertEquals("fog", p.getQuery());
        assertTrue(p.getStatuses().contains(Status.PUBLISHED));
        assertEquals(7, p.getMaxSize());
    }

    // ------------------------------------------------------------------------------------------- //
    // adjustDateInterval
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void adjustDateIntervalSnapsFromToStartOfDayAndToToEndOfDay() {
        TimeZone utc = TimeZone.getTimeZone("UTC");

        Calendar from = Calendar.getInstance(utc);
        from.set(2024, Calendar.MARCH, 15, 13, 45, 30);
        from.set(Calendar.MILLISECOND, 123);

        Calendar to = Calendar.getInstance(utc);
        to.set(2024, Calendar.MARCH, 20, 6, 10, 5);
        to.set(Calendar.MILLISECOND, 500);

        MessageSearchParams p = new MessageSearchParams();
        p.from(from.getTime());
        p.to(to.getTime());
        p.adjustDateInterval(utc);

        Calendar adjFrom = Calendar.getInstance(utc);
        adjFrom.setTime(p.getFrom());
        assertEquals(0, adjFrom.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, adjFrom.get(Calendar.MINUTE));
        assertEquals(0, adjFrom.get(Calendar.SECOND));
        assertEquals(0, adjFrom.get(Calendar.MILLISECOND));
        assertEquals(15, adjFrom.get(Calendar.DAY_OF_MONTH));

        Calendar adjTo = Calendar.getInstance(utc);
        adjTo.setTime(p.getTo());
        assertEquals(23, adjTo.get(Calendar.HOUR_OF_DAY));
        assertEquals(59, adjTo.get(Calendar.MINUTE));
        assertEquals(59, adjTo.get(Calendar.SECOND));
        assertEquals(20, adjTo.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------------------------------- //
    // toString
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void toStringListsTheSetCriteria() {
        MessageSearchParams p = new MessageSearchParams()
                .language("en")
                .query("buoy")
                .statuses(Status.PUBLISHED);
        p.domain("niord-client-nw");

        String s = p.toString();
        assertTrue(s.contains("Language: en"), s);
        assertTrue(s.contains("Query: 'buoy'"), s);
        assertTrue(s.contains("Domain: niord-client-nw"), s);
        assertTrue(s.contains("Statuses:"), s);
    }

    @Test
    public void toStringIsEmptyWhenNothingSet() {
        assertEquals("", new MessageSearchParams().toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate - invalid enum strings: status / type / mainType
    //
    // These go through toSet(String[], Enum::valueOf) which maps each element with valueOf and lets
    // the IllegalArgumentException propagate (no silent skip). The collection enums and the scalar
    // enums (dateType/userType/comments) behave the same way.
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateRejectsUnknownStatusEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("status", v("NOT_A_STATUS"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownTypeEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("type", v("NOT_A_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownMainTypeEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("mainType", v("NOT_A_MAIN_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownDateTypeEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("dateType", v("BOGUS_DATE_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownUserTypeEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("userType", v("BOGUS_USER_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownCommentsTypeEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("comments", v("BOGUS_COMMENTS"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsOneBadValueAmongstValidStatuses() {
        // The map-and-collect over the whole array fails as soon as any element is invalid.
        Map<String, String[]> req = new HashMap<>();
        req.put("status", v("PUBLISHED", "WAT", "DRAFT"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateRejectsUnknownSortOrderEnumValue() {
        Map<String, String[]> req = new HashMap<>();
        req.put("sortOrder", v("SIDEWAYS"));
        assertThrows(IllegalArgumentException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    // ------------------------------------------------------------------------------------------- //
    // extent(String wkt) - WKT parsing swallows ParseException
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void extentFromValidWktParsesPolygon() {
        MessageSearchParams p = new MessageSearchParams();
        MessageSearchParams ret = p.extent("POLYGON ((8 54, 13 54, 13 58, 8 58, 8 54))");

        assertSame(p, ret);
        Geometry extent = p.getExtent();
        assertNotNull(extent);
        Envelope env = extent.getEnvelopeInternal();
        assertEquals(8.0, env.getMinX(), 1e-9);
        assertEquals(13.0, env.getMaxX(), 1e-9);
        assertEquals(54.0, env.getMinY(), 1e-9);
        assertEquals(58.0, env.getMaxY(), 1e-9);
    }

    @Test
    public void extentFromMalformedWktSwallowsParseExceptionAndLeavesExtentNull() {
        MessageSearchParams p = new MessageSearchParams();
        // Not valid WKT - WKTReader throws ParseException which extent(String) swallows.
        MessageSearchParams ret = p.extent("this is not wkt at all");

        assertSame(p, ret);
        assertNull(p.getExtent());
    }

    @Test
    public void extentFromMalformedWktDoesNotOverwriteExistingExtent() {
        MessageSearchParams p = new MessageSearchParams();
        p.extent(54.0, 8.0, 58.0, 13.0);
        Geometry before = p.getExtent();
        assertNotNull(before);

        // A failed WKT parse must not clear or change the previously-set extent.
        p.extent("POLYGON ((bad coords))");
        assertSame(before, p.getExtent());
    }

    @Test
    public void extentFromBlankWktLeavesExtentNull() {
        // JtsConverter.wktToJts returns null for blank input (no exception), so extent stays null.
        MessageSearchParams p = new MessageSearchParams();
        p.extent("   ");
        assertNull(p.getExtent());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate - malformed numeric extent bounds
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateRejectsNonNumericExtentBound() {
        Map<String, String[]> req = new HashMap<>();
        req.put("minLat", v("not-a-number"));
        assertThrows(NumberFormatException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    // ------------------------------------------------------------------------------------------- //
    // checkSortByDomain - single-token order, swallowed exception, and explicit-order edge cases
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void checkSortByDomainSingleTokenOrderSwallowsArrayIndexAndDefaultsOrder() {
        // "PUBLISH_DATE" has no second token; split(" ")[1] throws ArrayIndexOutOfBoundsException,
        // which is swallowed. sortBy IS still set from token 0, and sortOrder falls to the ASC default.
        Domain domain = new Domain();
        domain.setMessageSortOrder("PUBLISH_DATE");

        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(domain);

        assertEquals("PUBLISH_DATE", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
        assertTrue(p.sortByPublishDate());
    }

    @Test
    public void checkSortByDomainInvalidOrderTokenSwallowsEnumValueOfAndDefaultsOrder() {
        // The order token "UPWARDS" is not a SortOrder; SortOrder.valueOf throws and is swallowed.
        // sortBy is still set from token 0; sortOrder falls back to the ASC default.
        Domain domain = new Domain();
        domain.setMessageSortOrder("ID UPWARDS");

        MessageSearchParams p = new MessageSearchParams();
        p.checkSortByDomain(domain);

        assertEquals("ID", p.getSortBy());
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    @Test
    public void checkSortByDomainKeepsExplicitSortOrderWhenOnlySortByMissing() {
        // sortBy unset but sortOrder explicitly DESC: domain fills sortBy from token 0,
        // and the explicit DESC order is preserved (not overwritten by the domain's ASC).
        Domain domain = new Domain();
        domain.setMessageSortOrder("ID ASC");

        MessageSearchParams p = new MessageSearchParams();
        p.sortOrder(SortOrder.DESC);
        p.checkSortByDomain(domain);

        assertEquals("ID", p.getSortBy());
        assertEquals(SortOrder.DESC, p.getSortOrder());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate - date parsing branches & pagination bounds
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateLeavesFromAndToNullWhenDateParamsAbsent() {
        MessageSearchParams p = MessageSearchParams.instantiate(null, new HashMap<>());
        assertNull(p.getFrom());
        assertNull(p.getTo());
    }

    @Test
    public void instantiateParsesOnlyFromDateWhenToDateAbsent() {
        Map<String, String[]> req = new HashMap<>();
        req.put("fromDate", v("42"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(new Date(42L), p.getFrom());
        assertNull(p.getTo());
    }

    @Test
    public void instantiateRejectsNonNumericFromDate() {
        Map<String, String[]> req = new HashMap<>();
        req.put("fromDate", v("yesterday"));
        assertThrows(NumberFormatException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    @Test
    public void instantiateClampsNegativeAndZeroMaxSizeToOne() {
        // The page size comes from the caller: a non-positive one is raised to the smallest page
        // (these two cases used to pin a plain passthrough, which let subList fail on a negative size).
        Map<String, String[]> req = new HashMap<>();
        req.put("maxSize", v("-5"));
        assertEquals(1, MessageSearchParams.instantiate(null, req).getMaxSize());

        req.put("maxSize", v("0"));
        assertEquals(1, MessageSearchParams.instantiate(null, req).getMaxSize());
    }

    @Test
    public void instantiateClampsOversizedMaxSizeToTheCeiling() {
        // An unbounded page size let one request load and serialise a whole archive (it used to be
        // passed through unchanged); the ceiling is the largest page any client asks for.
        Map<String, String[]> req = new HashMap<>();
        req.put("maxSize", v("1000000"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(MessageSearchParams.MAX_PAGE_SIZE, p.getMaxSize());
        assertEquals(1000, p.getMaxSize());
    }

    @Test
    public void instantiateKeepsMaxSizeAtTheCeiling() {
        // The map page and the home page ask for exactly 1000 - that must still be honoured.
        Map<String, String[]> req = new HashMap<>();
        req.put("maxSize", v("1000"));
        assertEquals(1000, MessageSearchParams.instantiate(null, req).getMaxSize());
        assertEquals(100, MessageSearchParams.instantiate(null, new HashMap<>()).getMaxSize(),
                "the default page size is unchanged");
    }

    @Test
    public void instantiateNeverSetsLiteralQueryFromTheRequest() {
        // literalQuery is decided by the REST layer from the caller's identity; no request
        // parameter may switch it on or off.
        Map<String, String[]> req = new HashMap<>();
        req.put("query", v("fog"));
        req.put("literalQuery", v("true"));
        assertFalse(MessageSearchParams.instantiate(null, req).isLiteralQuery());
        assertTrue(new MessageSearchParams().literalQuery(true).isLiteralQuery());
    }

    @Test
    public void instantiatePassesThroughLargePageNumber() {
        Map<String, String[]> req = new HashMap<>();
        req.put("page", v("999"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);
        assertEquals(999, p.getPage());
    }

    @Test
    public void instantiateRejectsNonNumericMaxSize() {
        Map<String, String[]> req = new HashMap<>();
        req.put("maxSize", v("lots"));
        assertThrows(NumberFormatException.class, () -> MessageSearchParams.instantiate(null, req));
    }

    // ------------------------------------------------------------------------------------------- //
    // toString - full rendering with many fields & referenceLevels=0 boundary
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void toStringRendersManySetFields() {
        MessageSearchParams p = new MessageSearchParams()
                .language("da")
                .query("storm")
                .messageId("NW-009-24")
                .referenceLevels(2)
                .dateType(DateType.PUBLISH_DATE)
                .username("alice")
                .userType(UserType.UPDATED_BY)
                .commentsType(CommentsType.ANY_UNACK)
                .statuses(Status.PUBLISHED)
                .from(1_000L)
                .to(2_000L);
        p.domain("niord-client-nw");
        p.mainTypes(java.util.Set.of(MainType.NW));
        p.types(java.util.Set.of(Type.LOCAL_WARNING));
        p.areaIds(java.util.Set.of("a1"));
        p.categoryIds(java.util.Set.of("c1"));
        p.chartNumbers(java.util.Set.of("101"));
        p.seriesIds(java.util.Set.of("dma-nw"));
        p.tags(java.util.Set.of("urgent"));
        p.publications(java.util.Set.of("pub1"));
        p.promulgationTypes(java.util.Set.of("navtex"));
        p.updatedFrom(new Date(3_000L));
        p.updatedTo(new Date(4_000L));
        p.extent(54.0, 8.0, 58.0, 13.0);

        String s = p.toString();
        assertTrue(s.contains("Language: da"), s);
        assertTrue(s.contains("Query: 'storm'"), s);
        assertTrue(s.contains("Domain: niord-client-nw"), s);
        assertTrue(s.contains("Ref. message: NW-009-24"), s);
        assertTrue(s.contains("Ref. levels: 2"), s);
        assertTrue(s.contains("Date Type: PUBLISH_DATE"), s);
        assertTrue(s.contains("User: alice"), s);
        assertTrue(s.contains("User type: UPDATED_BY"), s);
        assertTrue(s.contains("Comments: ANY_UNACK"), s);
        assertTrue(s.contains("Statuses:"), s);
        assertTrue(s.contains("Main types:"), s);
        assertTrue(s.contains("Types:"), s);
        assertTrue(s.contains("Area IDs:"), s);
        assertTrue(s.contains("Category IDs:"), s);
        assertTrue(s.contains("Chart Numbers:"), s);
        assertTrue(s.contains("Series IDs:"), s);
        assertTrue(s.contains("Tags:"), s);
        assertTrue(s.contains("Publications:"), s);
        assertTrue(s.contains("Promulgation Types:"), s);
        assertTrue(s.contains("From:"), s);
        assertTrue(s.contains("To:"), s);
        assertTrue(s.contains("Updated from:"), s);
        assertTrue(s.contains("Updated to:"), s);
        assertTrue(s.contains("Extent:"), s);
        // The description is the search's log line and its result's text: the plural takes no apostrophe
        assertFalse(s.contains("ID's"), s);
        // Fields are joined with ", "
        assertTrue(s.contains(", "), s);
    }

    @Test
    public void toStringRendersReferenceLevelsZeroBoundary() {
        // referenceLevels != null is the guard, so 0 IS rendered (boundary, not omitted).
        MessageSearchParams p = new MessageSearchParams().referenceLevels(0);
        assertEquals("Ref. levels: 0", p.toString());
    }

    @Test
    public void toStringOmitsBlankAndNullScalars() {
        // Blank language/query/messageId are guarded by isNotBlank and must not appear.
        MessageSearchParams p = new MessageSearchParams()
                .language("  ")
                .query("")
                .messageId("");
        assertEquals("", p.toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // instantiate - multi-element array params parse into right-sized collections
    //
    // The list params (area/category/chart/messageSeries) read the raw String[] directly, so the
    // collection size equals the number of distinct array elements. A single element containing a
    // comma is NOT split - it stays as one entry.
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void instantiateParsesMultiElementListParamsIntoRightSizedSets() {
        Map<String, String[]> req = new HashMap<>();
        req.put("area", v("a1", "a2", "a3"));
        req.put("category", v("c1", "c2"));
        req.put("chart", v("101", "102", "103", "104"));
        req.put("messageSeries", v("s1"));

        MessageSearchParams p = MessageSearchParams.instantiate(null, req);

        assertEquals(3, p.getAreaIds().size());
        assertTrue(p.getAreaIds().contains("a1"));
        assertTrue(p.getAreaIds().contains("a2"));
        assertTrue(p.getAreaIds().contains("a3"));

        assertEquals(2, p.getCategoryIds().size());
        assertTrue(p.getCategoryIds().contains("c1"));
        assertTrue(p.getCategoryIds().contains("c2"));

        assertEquals(4, p.getChartNumbers().size());
        assertTrue(p.getChartNumbers().contains("104"));

        assertEquals(1, p.getSeriesIds().size());
        assertTrue(p.getSeriesIds().contains("s1"));
    }

    @Test
    public void instantiateDoesNotSplitAListElementOnCommas() {
        // A single array element "a1,a2" is kept verbatim - the parser does NOT comma-split it.
        Map<String, String[]> req = new HashMap<>();
        req.put("area", v("a1,a2"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);

        assertEquals(1, p.getAreaIds().size());
        assertTrue(p.getAreaIds().contains("a1,a2"));
    }

    @Test
    public void instantiateDeduplicatesRepeatedListValues() {
        // Sets dedupe, so duplicate array entries collapse to one.
        Map<String, String[]> req = new HashMap<>();
        req.put("area", v("a1", "a1", "a2"));
        MessageSearchParams p = MessageSearchParams.instantiate(null, req);

        assertEquals(2, p.getAreaIds().size());
        assertTrue(p.getAreaIds().contains("a1"));
        assertTrue(p.getAreaIds().contains("a2"));
    }
}
