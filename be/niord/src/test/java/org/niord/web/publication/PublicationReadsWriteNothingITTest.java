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

package org.niord.web.publication;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.message.Message;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.IntervalBoundSource;
import org.niord.core.publication.series.IssueLifecycleService;
import org.niord.core.publication.series.IssueMember;
import org.niord.core.publication.series.IssueOverride;
import org.niord.core.publication.series.IssueStatus;
import org.niord.core.publication.series.MemberSource;
import org.niord.core.publication.series.NumberingScheme;
import org.niord.core.publication.series.OverrideKind;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.series.PublicationSeries;
import org.niord.core.publication.series.PublicationSeriesService;
import org.niord.core.publication.series.SeededSeries;
import org.niord.core.publication.series.SeriesCadence;
import org.niord.core.publication.series.SeriesStatus;
import org.niord.core.publication.series.TestOwnerDomain;
import org.niord.core.publication.series.criteria.IssueCriteriaVo;
import org.niord.core.publication.series.criteria.MessageMainTypeCriterionVo;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.user.User;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reads behind the publications screens write nothing, so they answer when they arrive together.
 *
 * <p>A read that writes is a read that can lose. The criteria document is a converted attribute:
 * Hibernate decides whether a row changed by comparing the value it loaded with the value the entity
 * holds, and the converter builds a fresh document every time. A document type without value equality
 * therefore makes every series and issue that carries one look changed, and the commit at the end of
 * each GET rewrites every row the GET loaded and moves its version. One reader at a time, nothing
 * shows. Two at once, the second commit's UPDATE finds the version the first has already moved, and
 * the request answers 500 -- on the issue list, with an UPDATE per issue, nearly every time. The
 * screens once chained their reads for exactly that reason.
 *
 * <p>So the rule is held at its cause: every read below is made once, and the rows of both fixture
 * series must be as they were -- every column, and the transaction that last wrote each row, so a
 * rewrite of identical values counts too. The second test is the symptom -- six of each read at once,
 * and the one-off screen's three reads together -- so that "these reads must not run in parallel"
 * stays tested rather than believed.
 *
 * <p>The fixture is COMMITTED, because the reads run in the server's own transactions: a weekly series
 * whose released issues each carry a criteria document, the current one with frozen members and on
 * the public list; an open issue the workbench resolves live, with a standing inclusion; and a
 * query-backed one-off whose released issue carries a criteria document as well. It is removed in
 * {@link #removeTheFixture()}.
 */
class PublicationReadsWriteNothingITTest extends ITTest {

    /** The desk the fixture belongs to, named in the {@code NiordDomain} header of every staff read. */
    private static final String DOMAIN = "niord-nw";

    /** Released issues in the weekly series, besides its open one. */
    private static final int RELEASED_ISSUES = 30;

    private static final long WEEK = 7L * 24 * 3600 * 1000;

    private static final long START = 1_500_000_000_000L;

    @PersistenceContext
    EntityManager em;

    @Autowired
    PublicationSeriesService seriesService;

    @Autowired
    IssueLifecycleService lifecycle;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String username;
    private String categoryId;
    private String seriesId;
    private String openIssue;
    private String currentIssue;
    private String oneOffSeriesId;
    private String oneOffIssue;

    /** A read, and a fragment its answer must contain to show that it reached the fixture. */
    private record Read(String name, String path, boolean anonymous, String fixtureMark) {
    }

    @BeforeEach
    void seedTheFixture() {
        Tx.requiringNew().run(() -> {
            Domain owner = TestOwnerDomain.of(em, DOMAIN);
            List<Message> messages = em.createQuery(
                            "SELECT m FROM Message m WHERE m.type IS NOT NULL ORDER BY m.id", Message.class)
                    .setMaxResults(3)
                    .getResultList();

            User author = new User();
            author.setUsername(username = TestIds.user());
            em.persist(author);

            PublicationCategory category = new PublicationCategory();
            category.setCategoryId(categoryId = TestIds.category());
            category.setPriority(100);
            // A publishing category, so the anonymous list serves the current issue.
            category.setPublish(true);
            em.persist(category);

            PublicationSeries weekly = new PublicationSeries();
            weekly.setSeriesId(seriesId = TestIds.series());
            weekly.setStatus(SeriesStatus.ACTIVE);
            weekly.setCadence(SeriesCadence.WEEKLY);
            weekly.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
            weekly.setReportId("some-report");
            weekly.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
            weekly.setAliveAtCutoff(false);
            weekly.setCriteria(criteria());
            weekly.getReportParams().put("pageSize", "A4");
            weekly.setCategory(category);
            weekly.setDomain(owner);
            weekly.getLanguages().add("en");
            weekly.createDesc("en").setName("IT reads write nothing");
            em.persist(weekly);

            for (int n = 0; n < RELEASED_ISSUES; n++) {
                Date to = new Date(START + (n + 1) * WEEK);
                PublicationIssue issue = lifecycle.create(weekly, new Date(START + n * WEEK),
                        IntervalBoundSource.STAMPED, to, null);
                boolean current = n == RELEASED_ISSUES - 1;
                release(issue, to, current ? null : new Date(to.getTime() + WEEK));
                if (current) {
                    currentIssue = issue.getPublicId();
                    for (int i = 0; i < messages.size(); i++) {
                        em.persist(member(issue, messages.get(i), i));
                    }
                    issue.setMemberCount(messages.size());
                }
            }

            PublicationIssue open = lifecycle.create(weekly, new Date(START + RELEASED_ISSUES * WEEK),
                    IntervalBoundSource.STAMPED, null);
            openIssue = open.getPublicId();
            if (!messages.isEmpty()) {
                em.persist(inclusion(open, messages.get(0), author));
            }

            PublicationSeries oneOff = new PublicationSeries();
            oneOff.setSeriesId(oneOffSeriesId = TestIds.series());
            oneOff.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
            oneOff.setCriteria(criteria());
            oneOff.setCategory(category);
            oneOff.setDomain(owner);
            oneOff.getLanguages().add("en");
            oneOff.createDesc("en").setName("IT one-off reads write nothing");
            OneOffRestService.forceOneOffShape(oneOff);
            oneOff = seriesService.create(oneOff);
            PublicationIssue only = lifecycle.create(oneOff, new Date(START), IntervalBoundSource.MANUAL, null);
            release(only, new Date(START + WEEK), new Date(START + 2 * WEEK));
            oneOffIssue = only.getPublicId();
        });
    }

    @AfterEach
    void removeTheFixture() {
        Tx.requiringNew().run(() -> {
            SeededSeries.delete(em, fixtureSeries());
            if (categoryId != null) {
                em.createQuery("DELETE FROM PublicationCategory c WHERE c.categoryId = :id")
                        .setParameter("id", categoryId).executeUpdate();
            }
            if (username != null) {
                em.createQuery("DELETE FROM User u WHERE u.username = :name")
                        .setParameter("name", username).executeUpdate();
            }
        });
    }

    /** Every read, made on its own, reaches the fixture and leaves its rows as they were. */
    @Test
    void eachReadLeavesThePublicationRowsAsTheyWere() throws Exception {
        for (Read read : reads()) {
            Map<String, String> before = snapshot();

            HttpResponse<String> response = get(read);

            assertEquals(200, response.statusCode(), read.name() + " answered " + response.body());
            assertTrue(response.body().contains(read.fixtureMark()),
                    read.name() + " did not reach the fixture, so it proves nothing about its rows");
            assertEquals(before, snapshot(), read.name() + " wrote to the publication tables");
        }
    }

    /**
     * Six of each read at once, as six simultaneous requests once failed four times in six on the
     * issue list, and the one-off screen's three reads together, as the screen makes them.
     */
    @Test
    void readsThatArriveTogetherAllAnswer() throws Exception {
        Map<String, String> before = snapshot();
        List<String> failed = new ArrayList<>();

        for (Read read : reads()) {
            failed.addAll(atOnce(Collections.nCopies(6, read)));
        }
        failed.addAll(atOnce(oneOffScreen()));

        assertEquals(List.of(), failed, "reads that failed when they arrived together");
        assertEquals(before, snapshot(), "and none of them wrote");
    }

    // ------------------------------------------------------------------------------------------- //

    /** The reads, named; each loads series or issues that carry the converted attributes. */
    private List<Read> reads() {
        List<Read> reads = new ArrayList<>(List.of(
                staff("the series list", "/rest/publication-series/search-details", seriesId),
                staff("the series", "/rest/publication-series/editable-series/" + seriesId, seriesId),
                staff("the issue list", "/rest/publication-issues/series/" + seriesId, openIssue),
                staff("the desk's recent issues",
                        "/rest/publication-issues/recent-by-domain?domain=" + DOMAIN, seriesId),
                staff("the workbench of the open issue",
                        "/rest/publication-issues/issue/" + openIssue + "/workbench?lang=en", openIssue),
                staff("the workbench of the current issue",
                        "/rest/publication-issues/issue/" + currentIssue + "/workbench?lang=en", currentIssue),
                staff("the one-off list", "/rest/one-off-publications?domain=" + DOMAIN + "&lang=en",
                        oneOffSeriesId),
                // What the public site asks for, anonymously and from every reader at once.
                new Read("the public list", "/rest/public/v1/publications?lang=en", true, currentIssue)));
        reads.addAll(oneOffScreen());
        return reads;
    }

    /** What the one-off page reads: the publication, then its issue and that issue's history together. */
    private List<Read> oneOffScreen() {
        return List.of(
                staff("the one-off", "/rest/one-off-publications/" + oneOffSeriesId + "?lang=en", oneOffSeriesId),
                staff("the one-off's issue", "/rest/publication-issues/editable-issue/" + oneOffIssue, oneOffIssue),
                staff("the one-off's history", "/rest/publication-issues/issue/" + oneOffIssue + "/audit",
                        "CREATED"));
    }

    private static Read staff(String name, String path, String fixtureMark) {
        return new Read(name, path, false, fixtureMark);
    }

    private static IssueCriteriaVo criteria() {
        MessageMainTypeCriterionVo mainType = new MessageMainTypeCriterionVo();
        mainType.setValues(new ArrayList<>(List.of("NW")));
        IssueCriteriaVo criteria = new IssueCriteriaVo();
        criteria.setCriteria(new ArrayList<>(List.of(mainType)));
        return criteria;
    }

    /**
     * Released as the legacy import releases an issue: stamped, public for a window, and frozen with
     * the criteria document it was released under. A null end keeps it on the public list.
     */
    private static void release(PublicationIssue issue, Date cutoff, Date publicTo) {
        issue.setStatus(IssueStatus.PUBLISHED);
        issue.setCutoffStampedAt(cutoff);
        issue.setPublishedAt(cutoff);
        issue.setPublicFrom(cutoff);
        issue.setPublicTo(publicTo);
        issue.setCriteriaSnapshot(criteria());
        issue.setSnapshotFrozenAt(cutoff);
        issue.getReportParams().put("pageSize", "A4");
    }

    private static IssueMember member(PublicationIssue issue, Message message, int sortIndex) {
        IssueMember member = new IssueMember();
        member.setIssue(issue);
        member.setMessage(message);
        member.setMessageUid(message.getUid());
        member.setSortIndex(sortIndex);
        member.setFrozenShortId(message.getShortId());
        member.setFrozenMainType(message.getMainType().name());
        member.setFrozenType(message.getType().name());
        member.setFrozenStatus(message.getStatus().name());
        member.setFrozenPublishDateFrom(message.getPublishDateFrom());
        member.setSource(MemberSource.CRITERIA);
        return member;
    }

    private static IssueOverride inclusion(PublicationIssue issue, Message message, User author) {
        IssueOverride include = new IssueOverride();
        include.setIssue(issue);
        include.setMessage(message);
        include.setMessageUid(message.getUid());
        include.setKind(OverrideKind.INCLUDE);
        include.setAuthor(author);
        include.setReason("A standing decision the workbench reads");
        return include;
    }

    private HttpRequest request(Read read) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + read.path()))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET();
        if (!read.anonymous()) {
            request.header("Authorization", "Bearer " + accessToken("admin"))
                    .header("NiordDomain", DOMAIN);
        }
        return request.build();
    }

    private HttpResponse<String> get(Read read) throws Exception {
        return client.send(request(read), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends every request before waiting for any, and names the ones that did not answer 200. */
    private List<String> atOnce(List<Read> reads) throws Exception {
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (Read read : reads) {
            pending.add(client.sendAsync(request(read), HttpResponse.BodyHandlers.ofString()));
        }
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < reads.size(); i++) {
            HttpResponse<String> response = pending.get(i).get(60, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                failed.add(reads.get(i).name() + " answered " + response.statusCode());
            }
        }
        return failed;
    }

    private List<Integer> fixtureSeries() {
        List<String> ids = new ArrayList<>();
        if (seriesId != null) {
            ids.add(seriesId);
        }
        if (oneOffSeriesId != null) {
            ids.add(oneOffSeriesId);
        }
        return ids.isEmpty() ? List.of() : em.createQuery(
                        "SELECT s.id FROM PublicationSeries s WHERE s.seriesId IN :ids", Integer.class)
                .setParameter("ids", ids).getResultList();
    }

    /**
     * The rows of both fixture series, table by table: how many, and a checksum over every column and
     * over the transaction that last wrote each row.
     *
     * The writing transaction ({@code xmin}) is what catches an UPDATE that writes back the values a
     * row already held, which leaves every column as it was -- and on a table with no version column,
     * nothing else would.
     */
    private Map<String, String> snapshot() {
        return Tx.requiringNew().call(() -> {
            List<Integer> series = fixtureSeries();
            String issues = "SELECT id FROM public.\"PublicationIssue\" WHERE series_id IN (:series)";
            Map<String, String> rows = new LinkedHashMap<>();
            rows.put("PublicationSeries", "id IN (:series)");
            rows.put("PublicationSeriesDesc", "entity_id IN (:series)");
            rows.put("PublicationIssue", "series_id IN (:series)");
            rows.put("PublicationIssueDesc", "entity_id IN (" + issues + ")");
            rows.put("IssueMember", "issue_id IN (" + issues + ")");
            rows.put("IssueOverride", "issue_id IN (" + issues + ")");
            rows.put("IssueAuditEntry", "series_id IN (:series) OR issue_id IN (" + issues + ")");

            Map<String, String> snapshot = new LinkedHashMap<>();
            rows.forEach((table, which) -> {
                Object[] result = (Object[]) em.createNativeQuery(
                                "SELECT count(*), md5(coalesce(string_agg(t::text || '@' || t.xmin::text, '|' "
                                        + "ORDER BY t.id), '')) FROM public.\"" + table + "\" t WHERE " + which)
                        .setParameter("series", series)
                        .getSingleResult();
                snapshot.put(table, result[0] + " rows, " + result[1]);
            });
            return snapshot;
        });
    }
}
