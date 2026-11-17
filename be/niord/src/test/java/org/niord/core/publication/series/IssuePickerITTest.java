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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.publication.series.vo.PublicationIssuePickerVo;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.model.publication.PublicationType;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker and the hydration behind a citation chip.
 *
 * Three things are pinned here that nothing else can pin. The picker's default
 * is PUBLISHED and OPEN and not "all three", so a withdrawn publication is not
 * offered for citation while staying resolvable by id. The two status
 * vocabularies both work, because two frontends are in service and the older one
 * literally sends {@code status=ACTIVE&status=RECORDING}. And hydration by id
 * applies NO status narrowing at all, because a message citing an issue that was
 * later retired still has to render what it cited. It does withhold, from a
 * caller without the user role, the file link of an issue not yet released.
 *
 * The fixtures are seeded with an import-source marker and taken away again --
 * the same discipline the search-adapter test carries, for the same reason: on a
 * real deployment the schema IS the estate, and a leftover fixture is reported
 * as a finding about production data.
 */
public class IssuePickerITTest extends ITTest {

    private static final String SEEDED_BY = "issue-picker-fixture";

    /** I28, the anonymous hydration. */
    private static final String BY_IDS = "/rest/publication-issues/by-ids";

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    @Autowired
    IssuePickerService picker;

    @PersistenceContext
    EntityManager em;

    /**
     * PORT: the cleanup ran under jakarta's {@code Transactional}, which COMMITTED in Quarkus. Spring's test {@code @Transactional} rolls back instead, so most of what the cases
     * below seed is gone before this runs -- and on a test that carries no {@code @Transactional}
     * there is no transaction here at all, which is where the deletes would fail outright. The body
     * therefore runs in its own committed transaction: {@code @Transactional} on a method of a test
     * class is not proxied by Spring, so the annotation would have been a silent no-op.
     */
    @AfterEach
    public void removeWhatWasSeeded() {
        // PORT: end (roll back) the still-open test-managed transaction first — it may hold key-share
        // locks on the seeded rows, and the committed DELETE below would otherwise wait on it forever.
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
        Tx.requiringNew().run(this::deleteWhatWasSeeded);
    }

    private void deleteWhatWasSeeded() {
        List<Integer> seriesIds = em.createQuery(
                        "SELECT s.id FROM PublicationSeries s WHERE s.importSource = :src", Integer.class)
                .setParameter("src", SEEDED_BY).getResultList();
        if (seriesIds.isEmpty()) {
            return;
        }
        // Each fixture series gets a category of its own; a committed one would outlive it.
        List<Integer> categoryIds = em.createQuery(
                        "SELECT s.category.id FROM PublicationSeries s WHERE s.id IN :series", Integer.class)
                .setParameter("series", seriesIds).getResultList();

        SeededSeries.delete(em, seriesIds);
        if (!categoryIds.isEmpty()) {
            em.createQuery("DELETE FROM PublicationCategory c WHERE c.id IN :categories")
                    .setParameter("categories", categoryIds).executeUpdate();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private Domain domain(String domainId) {
        Domain d = em.createQuery("SELECT d FROM Domain d WHERE d.domainId = :id", Domain.class)
                .setParameter("id", domainId).getResultStream().findFirst().orElse(null);
        if (d == null) {
            d = new Domain();
            d.setDomainId(domainId);
            d.setName(domainId);
            em.persist(d);
        }
        return d;
    }

    private PublicationSeries series(ContentMode mode, Domain domain, MessagePublication publication) {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(TestIds.category());
        c.setPriority(100);
        em.persist(c);

        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(TestIds.series());
        s.setStatus(SeriesStatus.ACTIVE);
        s.setImportSource(SEEDED_BY);
        s.setContentMode(mode);
        s.setCadence(SeriesCadence.WEEKLY);
        s.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
        s.setAliveAtCutoff(false);
        s.setReleaseMode(ReleaseMode.MANUAL_GATE);
        s.setNextIssueCreation(NextIssueCreation.MANUAL);
        s.setMessagePublication(publication);
        s.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
        s.setCategory(c);
        s.setDomain(domain);
        s.getLanguages().add("da");
        s.createDesc("da").setName("Picker fixture series");
        em.persist(s);
        return s;
    }

    /** An issue in a given state, planted directly: the lifecycle is not what is under test. */
    private PublicationIssue issue(PublicationSeries s, IssueStatus status, String name, long cutoff) {
        PublicationIssue i = new PublicationIssue();
        i.setSeries(s);
        i.setPublicId(UUID.randomUUID().toString());
        i.setRepoPath("publications/" + i.getPublicId());
        i.setStatus(status);
        i.setIntervalFrom(new Date(cutoff - 7L * 24 * 3600_000L));
        i.setIntervalTo(new Date(cutoff));
        if (status != IssueStatus.OPEN) {
            i.setCutoffStampedAt(new Date(cutoff));
            i.setPublicFrom(new Date(cutoff));
        }
        PublicationIssueDesc d = i.createDesc("da");
        d.setName(name);
        d.setLink("https://example.test/" + name.replace(' ', '-'));
        em.persist(i);
        em.flush();
        return i;
    }

    /**
     * An issue whose document is a file in the repository rather than an
     * external link, so its row's link is the repository address.
     */
    private PublicationIssue issueWithAFile(PublicationSeries s, IssueStatus status, String name, long cutoff) {
        PublicationIssue i = issue(s, status, name, cutoff);
        PublicationIssueDesc d = i.getDescs().get(0);
        d.setLink(null);
        d.setFileName(name.replace(' ', '-') + ".pdf");
        d.setFilePath(i.getRepoPath() + "/" + d.getFileName());
        em.flush();
        return i;
    }

    private static String repoLinkOf(PublicationIssue issue) {
        return "/rest/repo/file/" + issue.getRepoPath() + "/" + issue.getDescs().get(0).getFileName();
    }

    private static boolean contains(List<PublicationIssuePickerVo> rows, PublicationIssue issue) {
        return rows.stream().anyMatch(r -> r.getPublicId().equals(issue.getPublicId()));
    }

    private IssuePickerService.PickerQuery query(String seriesId, Set<IssueStatus> statuses) {
        return new IssuePickerService.PickerQuery("da", null, seriesId, statuses,
                null, null, null, 0, 100);
    }

    // ------------------------------------------------------------------ I27

    /**
     * The default is PUBLISHED and OPEN. A RETIRED issue is not offered.
     *
     * Offering a withdrawn publication in the same list as the current week's is
     * how a citation gets made into something nobody may read any more, and the
     * citation is bytes inside stored message HTML afterwards.
     */
    @Test
    @Transactional
    public void thePickerDefaultsToPublishedAndOpenAndNotToAllThree() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue open = issue(s, IssueStatus.OPEN, "Open week", 1_700_000_000_000L);
        PublicationIssue published = issue(s, IssueStatus.PUBLISHED, "Published week", 1_700_100_000_000L);
        PublicationIssue retired = issue(s, IssueStatus.RETIRED, "Retired week", 1_700_200_000_000L);

        List<PublicationIssuePickerVo> rows = picker.search(
                query(s.getSeriesId(), IssuePickerService.DEFAULT_STATUSES)).getData();

        assertTrue(contains(rows, open));
        assertTrue(contains(rows, published));
        assertFalse(contains(rows, retired),
                "the picker offered a RETIRED issue for citation. It stays reachable by id, which is "
                        + "what the retired-citation chip needs, and that is all it needs");
    }

    /**
     * The shipped frontend's literal query works, and does not 400.
     *
     * {@code status=ACTIVE&status=RECORDING} is what the older picker sends. Under
     * a strict parse the citation dialog would come back empty with nothing in the
     * response to explain it.
     */
    @Test
    @Transactional
    public void theLegacyStatusVocabularyIsAcceptedAndTranslated() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue open = issue(s, IssueStatus.OPEN, "Open week", 1_700_000_000_000L);
        PublicationIssue published = issue(s, IssueStatus.PUBLISHED, "Published week", 1_700_100_000_000L);
        PublicationIssue retired = issue(s, IssueStatus.RETIRED, "Retired week", 1_700_200_000_000L);

        Set<IssueStatus> asked = IssueStatusTokens.parseAll(List.of("ACTIVE", "RECORDING"),
                IssuePickerService.DEFAULT_STATUSES);
        assertEquals(Set.of(IssueStatus.PUBLISHED, IssueStatus.OPEN), asked);

        List<PublicationIssuePickerVo> rows = picker.search(query(s.getSeriesId(), asked)).getData();
        assertTrue(contains(rows, open));
        assertTrue(contains(rows, published));
        assertFalse(contains(rows, retired));

        // And the other three words of the old vocabulary map where they should.
        assertEquals(IssueStatus.OPEN, IssueStatusTokens.parse("DRAFT"));
        assertEquals(IssueStatus.RETIRED, IssueStatusTokens.parse("INACTIVE"));
        assertEquals(IssueStatus.PUBLISHED, IssueStatusTokens.parse("published"));
    }

    /**
     * An unrecognised token is refused, and refused as a CLIENT error.
     *
     * Dropping it silently would WIDEN the list rather than narrow it -- a caller
     * asking for released issues would be offered drafts -- and valueOf's own
     * exception reaches a caller as a 500 that says nothing.
     */
    @Test
    public void anUnknownStatusTokenIsRefusedRatherThanDropped() {
        IssueLifecycleService.TransitionRefusedException refused = assertThrows(
                IssueLifecycleService.TransitionRefusedException.class,
                () -> IssueStatusTokens.parseAll(List.of("PENDING"), IssuePickerService.DEFAULT_STATUSES));
        assertEquals("INVALID_STATUS", refused.code());
    }

    /**
     * The legacy type is derived from the series' content mode, and omitting the
     * parameter still reaches LINK rows.
     *
     * One shipped consumer sends type=MESSAGE_REPORT and the other sends nothing,
     * and both are signed contracts -- so a narrowing that leaked into the
     * unfiltered case would empty one of the two dialogs.
     */
    @Test
    @Transactional
    public void theLegacyTypeIsDerivedFromTheContentMode() {
        PublicationSeries generated = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"),
                MessagePublication.NONE);
        PublicationSeries linked = series(ContentMode.EXTERNAL_LINK, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue report = issue(generated, IssueStatus.PUBLISHED, "Report", 1_700_000_000_000L);
        PublicationIssue link = issue(linked, IssueStatus.PUBLISHED, "Link", 1_700_100_000_000L);

        PublicationIssuePickerVo reportRow = picker.byIds(List.of(report.getPublicId()), "da").get(0);
        PublicationIssuePickerVo linkRow = picker.byIds(List.of(link.getPublicId()), "da").get(0);
        assertEquals(PublicationType.MESSAGE_REPORT, reportRow.getType());
        assertEquals(PublicationType.LINK, linkRow.getType());

        // Narrowed on MESSAGE_REPORT, the link row is not offered.
        List<PublicationIssuePickerVo> narrowed = picker.search(new IssuePickerService.PickerQuery(
                "da", null, linked.getSeriesId(), IssuePickerService.DEFAULT_STATUSES,
                null, PublicationType.MESSAGE_REPORT, null, 0, 100)).getData();
        assertFalse(contains(narrowed, link));

        // With the parameter omitted it is.
        List<PublicationIssuePickerVo> unfiltered = picker.search(
                query(linked.getSeriesId(), IssuePickerService.DEFAULT_STATUSES)).getData();
        assertTrue(contains(unfiltered, link),
                "omitting type must still reach LINK rows; one of the two shipped pickers sends no "
                        + "type at all and would otherwise show nothing");
    }

    /**
     * The picker offers what the caller's domain may CITE, not what it owns.
     *
     * Four cases in one request, because they are one rule and asserting them
     * apart is how two of them come to disagree: a publication shared with every
     * domain is offered, one shared with THIS domain by name is offered, one
     * shared with a DIFFERENT domain is not, and one that is nobody's but its
     * owner's is not.
     *
     * The first case is the one that matters most in practice. Most of the
     * citation-only catalogue is shared with every domain -- it is what a null
     * owner used to express -- so getting it wrong empties the citation dialog.
     */
    @Test
    @Transactional
    public void thePickerOffersWhatTheDomainMayCite() {
        Domain elsewhere = domain("picker-domain-a");
        Domain asking = domain("picker-domain-b");
        Domain third = domain("picker-domain-c");

        PublicationSeries everywhere = shared(elsewhere, SeriesAvailability.ALL_DOMAINS);
        PublicationSeries named = shared(elsewhere, SeriesAvailability.SELECTED_DOMAINS, asking);
        PublicationSeries namedElsewhere = shared(elsewhere, SeriesAvailability.SELECTED_DOMAINS, third);
        PublicationSeries theirs = shared(elsewhere, SeriesAvailability.OWNER_ONLY);

        // A marker in the name, so the assertion is about the domain clause and
        // not about which page of a shared test database the row landed on --
        // the query is deliberately unscoped by series here, which is the whole
        // point of it.
        String marker = TestIds.id("dmn-");
        PublicationIssue everywhereIssue = issue(everywhere, IssueStatus.PUBLISHED,
                "Everywhere " + marker, 1_700_000_000_000L);
        PublicationIssue namedIssue = issue(named, IssueStatus.PUBLISHED,
                "Named " + marker, 1_700_100_000_000L);
        PublicationIssue namedElsewhereIssue = issue(namedElsewhere, IssueStatus.PUBLISHED,
                "Named elsewhere " + marker, 1_700_200_000_000L);
        PublicationIssue theirsIssue = issue(theirs, IssueStatus.PUBLISHED,
                "Theirs " + marker, 1_700_300_000_000L);

        List<PublicationIssuePickerVo> offered = picker.search(
                new IssuePickerService.PickerQuery("da", marker, null,
                        IssuePickerService.DEFAULT_STATUSES, null, null, "picker-domain-b", 0, 500))
                .getData();

        assertTrue(contains(offered, everywhereIssue),
                "a publication shared with every domain vanished from a domain-scoped picker. "
                        + "Most of the citation-only catalogue is shared that way, so this empties "
                        + "the citation dialog");
        assertTrue(contains(offered, namedIssue),
                "a publication shared with THIS domain by name was not offered; 'selected domains' "
                        + "would then select nothing");
        assertFalse(contains(offered, namedElsewhereIssue),
                "a publication shared with a DIFFERENT domain was offered");
        assertFalse(contains(offered, theirsIssue),
                "a publication belonging to another domain and shared with nobody was offered");
    }

    /**
     * An INACTIVE domain on the list reaches nothing.
     *
     * A domain that has been switched off is not a desk anybody is sitting at, so
     * a stale row naming one must not keep a publication reachable from a place
     * that no longer exists.
     */
    @Test
    @Transactional
    public void asharedInactiveDomainIsIgnoredByThePicker() {
        Domain switchedOff = domain("picker-domain-off");
        switchedOff.setActive(false);
        em.flush();

        PublicationSeries s = shared(domain("picker-domain-a"),
                SeriesAvailability.SELECTED_DOMAINS, switchedOff);
        String marker = TestIds.id("off-");
        PublicationIssue i = issue(s, IssueStatus.PUBLISHED, "Off " + marker, 1_700_000_000_000L);

        assertFalse(contains(picker.search(new IssuePickerService.PickerQuery("da", marker, null,
                                IssuePickerService.DEFAULT_STATUSES, null, null,
                                "picker-domain-off", 0, 500)).getData(), i),
                "a switched-off domain still reached the publication");
    }

    /** A series owned by one domain and shared as stated. */
    private PublicationSeries shared(Domain owner, SeriesAvailability availability, Domain... with) {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, owner, MessagePublication.NONE);
        s.setAvailability(availability);
        for (Domain d : with) {
            s.getAvailableDomains().add(d);
        }
        em.flush();
        return s;
    }

    /** The title filter is a case-insensitive substring within the requested language. */
    @Test
    @Transactional
    public void theTitleFilterMatchesASubstringCaseInsensitively() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue wanted = issue(s, IssueStatus.PUBLISHED, "EfS uge 33", 1_700_000_000_000L);
        PublicationIssue other = issue(s, IssueStatus.PUBLISHED, "P&T uge 33", 1_700_100_000_000L);

        List<PublicationIssuePickerVo> rows = picker.search(new IssuePickerService.PickerQuery(
                "da", "efs", s.getSeriesId(), IssuePickerService.DEFAULT_STATUSES,
                null, null, null, 0, 100)).getData();

        assertTrue(contains(rows, wanted));
        assertFalse(contains(rows, other));
    }

    /**
     * Paging is `maxSize` and `page`, and `total` counts the whole result.
     *
     * A page that reported its own length as the total would leave every list
     * claiming to be one page long, and no pager could ever reach page two.
     */
    @Test
    @Transactional
    public void pagingNarrowsTheDataAndNotTheTotal() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        for (int n = 0; n < 5; n++) {
            issue(s, IssueStatus.PUBLISHED, "Week " + n, 1_700_000_000_000L + n * 100_000_000L);
        }

        PagedSearchResultVo<PublicationIssuePickerVo> first = picker.search(
                new IssuePickerService.PickerQuery("da", null, s.getSeriesId(),
                        IssuePickerService.DEFAULT_STATUSES, null, null, null, 0, 2));
        assertEquals(2, first.getData().size());
        assertEquals(5, first.getTotal());
        assertEquals(2, first.getSize());

        PagedSearchResultVo<PublicationIssuePickerVo> second = picker.search(
                new IssuePickerService.PickerQuery("da", null, s.getSeriesId(),
                        IssuePickerService.DEFAULT_STATUSES, null, null, null, 1, 2));
        assertEquals(2, second.getData().size());
        assertFalse(second.getData().get(0).getPublicId().equals(first.getData().get(0).getPublicId()),
                "page two repeated page one; the order is not total");
    }

    /** The row carries the flag the per-message link override depends on. */
    @Test
    @Transactional
    public void theRowCarriesWhetherTheSeriesIsLanguageSpecific() {
        PublicationSeries s = series(ContentMode.EXTERNAL_LINK, domain("picker-owner"), MessagePublication.EXTERNAL);
        s.setLanguageSpecific(false);
        em.merge(s);
        PublicationIssue i = issue(s, IssueStatus.PUBLISHED, "Shared link", 1_700_000_000_000L);

        PublicationIssuePickerVo row = picker.byIds(List.of(i.getPublicId()), "da").get(0);
        assertFalse(row.isLanguageSpecific(),
                "without this flag the editor cannot decide whether to offer a per-message link "
                        + "override, and the picker is the only payload that consumer reads");
        assertEquals(MessagePublication.EXTERNAL, row.getMessagePublication());
    }

    // ------------------------------------------------------------------ I28

    /**
     * Hydration by id applies NO status narrowing, so a RETIRED issue resolves.
     *
     * The retired-citation chip depends on exactly this. Narrowing to published
     * would blank precisely the chips a reader most needs explained.
     */
    @Test
    @Transactional
    public void hydrationByIdReturnsARetiredIssue() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue retired = issue(s, IssueStatus.RETIRED, "Retired week", 1_700_000_000_000L);

        List<PublicationIssuePickerVo> rows = picker.byIds(List.of(retired.getPublicId()), "da");
        assertEquals(1, rows.size());
        assertEquals("RETIRED", rows.get(0).getStatus());
        assertEquals("Retired week", rows.get(0).getDescs().get(0).getTitle());
        assertNotNull(rows.get(0).getDescs().get(0).getLink());
    }

    /** An unknown id is omitted, never a refusal, and nothing at all is an empty list. */
    @Test
    @Transactional
    public void unknownIdsAreOmittedSilently() {
        PublicationSeries s = series(ContentMode.GENERATED_FROM_QUERY, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue known = issue(s, IssueStatus.PUBLISHED, "Known", 1_700_000_000_000L);

        List<PublicationIssuePickerVo> rows =
                picker.byIds(List.of("no-such-id", known.getPublicId()), "da");
        assertEquals(1, rows.size(),
                "an unknown id must not fail the lookup: one dead citation in a message would then "
                        + "blank the four beside it");
        assertEquals(known.getPublicId(), rows.get(0).getPublicId());

        assertTrue(picker.byIds(List.of("no-such-id"), "da").isEmpty());
        assertTrue(picker.byIds(List.of(), "da").isEmpty());
    }

    /**
     * Asked to, hydration withholds the document address of an issue that has
     * not been released, and only of that one.
     *
     * An upload to an OPEN issue already sits in the served repository, so the
     * endpoint asks for this on behalf of a caller outside the signed-in tier.
     * The row still resolves, title and status included, so a chip keeps
     * rendering; the released issues beside it keep their links; and the
     * signed-in lookup is unchanged.
     */
    @Test
    @Transactional
    public void hydrationWithholdsTheFileLinkOfAnUnreleasedIssueWhenAsked() {
        PublicationSeries s = series(ContentMode.UPLOADED_FILE, domain("picker-owner"), MessagePublication.NONE);
        PublicationIssue open = issueWithAFile(s, IssueStatus.OPEN, "Open week", 1_700_000_000_000L);
        PublicationIssue published = issueWithAFile(s, IssueStatus.PUBLISHED, "Published week", 1_700_100_000_000L);
        PublicationIssue retired = issueWithAFile(s, IssueStatus.RETIRED, "Retired week", 1_700_200_000_000L);
        List<String> ids = List.of(open.getPublicId(), published.getPublicId(), retired.getPublicId());

        List<PublicationIssuePickerVo> withheld = picker.byIds(ids, "da", true);
        assertEquals(3, withheld.size());
        assertEquals("OPEN", withheld.get(0).getStatus());
        assertEquals("Open week", withheld.get(0).getDescs().get(0).getTitle(),
                "the row of an unreleased issue must still resolve, or its chip goes blank");
        assertNull(withheld.get(0).getDescs().get(0).getLink(),
                "the repository address of an OPEN issue's upload was handed out before release");
        assertEquals(repoLinkOf(published), withheld.get(1).getDescs().get(0).getLink());
        assertEquals(repoLinkOf(retired), withheld.get(2).getDescs().get(0).getLink(),
                "a RETIRED issue was released once, and its citation chip still links to it");

        assertEquals(repoLinkOf(open), picker.byIds(ids, "da", false).get(0).getDescs().get(0).getLink());
        assertEquals(repoLinkOf(open), picker.byIds(ids, "da").get(0).getDescs().get(0).getLink(),
                "the two-argument lookup is the signed-in one and keeps every link");
    }

    /**
     * The anonymous I28 endpoint withholds the address of an OPEN issue's
     * upload, and a signed-in user still gets it.
     *
     * Through HTTP, because what is under test is the tier the endpoint derives
     * from the caller. The fixture is committed, since the request runs in the
     * server's own transaction, and removed again by the cleanup above; its
     * series is owned by a seeded domain, so no Domain row outlives it.
     */
    @Test
    public void theAnonymousHydrationEndpointWithholdsTheFileLinkOfAnOpenIssue() {
        PublicationIssue open = Tx.requiringNew().call(() -> issueWithAFile(
                series(ContentMode.UPLOADED_FILE, domain(NW_DOMAIN), MessagePublication.NONE),
                IssueStatus.OPEN, "Open week", 1_700_000_000_000L));
        String link = repoLinkOf(open);

        given().queryParam("ids", open.getPublicId()).queryParam("lang", "da")
                .when().get(BY_IDS)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].status", is("OPEN"))
                .body("[0].descs[0].title", is("Open week"))
                .body("[0].descs[0].link", nullValue());

        given().auth().oauth2(accessToken("user")).header("NiordDomain", NW_DOMAIN)
                .queryParam("ids", open.getPublicId()).queryParam("lang", "da")
                .when().get(BY_IDS)
                .then().statusCode(200)
                .body("[0].descs[0].link", is(link));
    }

    /** And the id list is bounded, because the endpoint serving it is anonymous. */
    @Test
    public void moreIdsThanTheCapAreRefused() {
        List<String> ids = new ArrayList<>();
        for (int n = 0; n <= IssuePickerService.MAX_IDS; n++) {
            ids.add("id-" + n);
        }
        IssueLifecycleService.TransitionRefusedException refused = assertThrows(
                IssueLifecycleService.TransitionRefusedException.class,
                () -> picker.byIds(ids, "da"));
        assertEquals("TOO_MANY_IDS", refused.code());
    }

    /**
     * The payload carries nothing operational.
     *
     * Asserted over the DECLARED FIELDS rather than over a response, because a
     * field added by accident is invisible in every behavioural test -- and this
     * type is served to every logged-in user and, by id, to anonymous ones.
     */
    @Test
    public void thePickerPayloadCarriesNothingOperational() {
        List<String> declared = new ArrayList<>();
        for (var f : PublicationIssuePickerVo.class.getDeclaredFields()) {
            declared.add(f.getName());
        }
        for (String leaky : List.of("criteria", "criteriaOverride", "reportId", "reportParams",
                "repoPath", "filePath", "memberCount", "releaseMode",
                "cutoffStampedAt", "snapshotIntervalFrom")) {
            assertFalse(declared.contains(leaky),
                    "PublicationIssuePickerVo declares " + leaky + "; a field that is not there "
                            + "cannot leak, which is the whole reason this type exists rather than "
                            + "the system shape");
        }
    }
}
