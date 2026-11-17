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

import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.IssueStatus;
import org.niord.core.publication.series.MessageIssueLookup;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.series.PublicationIssueDesc;
import org.niord.core.publication.series.PublicationSeries;
import org.niord.core.publication.series.PublicationSeriesDesc;
import org.niord.core.publication.series.vo.MessageIssueRefVo;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire shape of "which issues is this message in".
 *
 * Two things are pinned here and they are both about what the row MEANS rather
 * than about mapping mechanics.
 *
 * FROZEN versus LIVE survives the mapping. A published issue's membership is a
 * record; an open issue's is a prediction resolved at read time. The frontend
 * renders them differently -- one says the message is out, the other says it is
 * due to go out -- and if the flag were dropped every row would read as the
 * stronger claim.
 *
 * The payload stays scoped. This endpoint is readable by anyone who may open a
 * message, which is a wider audience than series administrators, so the series'
 * naming and file patterns must not ride along on it.
 */
public class MessageIssueRefWireTest {

    // ------------------------------------------------------------- builders

    private static PublicationSeries series() {
        return series("dma-efs");
    }

    private static PublicationSeries series(String seriesId) {
        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(seriesId);

        PublicationSeriesDesc da = new PublicationSeriesDesc();
        da.setLang("da");
        da.setName("Efterretninger for Sofarende");
        da.setNameSuggestionPattern("EfS uge ${week}, ${year}");
        da.setFileNamePattern("efs-${week}-${year}.pdf");
        da.setMessageReferenceFormat("EfS ${week}/${year}");
        s.getDescs().add(da);
        return s;
    }

    private static PublicationIssue issue(IssueStatus status) {
        return issue(status, series(), "EfS uge 29, 2026", 1_700_000_000_000L);
    }

    private static PublicationIssue issue(IssueStatus status, PublicationSeries series,
                                          String name, long intervalFrom) {
        PublicationIssue i = new PublicationIssue();
        i.setSeries(series);
        i.setStatus(status);
        i.setIntervalFrom(new Date(intervalFrom));
        i.setPublishedAt(status == IssueStatus.PUBLISHED ? new Date(intervalFrom + 500_000_000L) : null);

        PublicationIssueDesc da = new PublicationIssueDesc();
        da.setLang("da");
        da.setName(name);
        da.setLink("/publications/efs-29-2026.pdf");
        i.getDescs().add(da);
        return i;
    }

    private static MessageIssueLookup.MessageIssue frozen(PublicationIssue issue) {
        return new MessageIssueLookup.MessageIssue(issue, MessageIssueLookup.Membership.FROZEN);
    }

    private static MessageIssueLookup.MessageIssue live(PublicationIssue issue) {
        return new MessageIssueLookup.MessageIssue(issue, MessageIssueLookup.Membership.LIVE);
    }

    // ------------------------------------------------------------ assertions

    /** A published issue's row says the message is already out. */
    @Test
    public void afrozenRowKeepsItsBasis() {
        MessageIssueRefVo vo = PublicationIssueRestService.refOf(new MessageIssueLookup.MessageIssue(
                issue(IssueStatus.PUBLISHED), MessageIssueLookup.Membership.FROZEN));

        assertEquals(MessageIssueRefVo.Membership.FROZEN, vo.getMembership());
        assertEquals(IssueStatus.PUBLISHED, vo.getStatus());
        assertEquals("dma-efs", vo.getSeriesId());
        assertEquals("EfS uge 29, 2026", vo.getNames().get("da"));
        assertEquals("Efterretninger for Sofarende", vo.getSeriesNames().get("da"));
        assertEquals("/publications/efs-29-2026.pdf", vo.getLinks().get("da"));
    }

    /**
     * An open issue's row says the message is DUE to go out, not that it has.
     *
     * The status and the membership carry different halves of that and both are
     * needed: a row could in principle be FROZEN on an issue that was reopened,
     * so the frontend reads the basis rather than inferring it from the status.
     */
    @Test
    public void aliveRowIsMarkedAsAPrediction() {
        MessageIssueRefVo vo = PublicationIssueRestService.refOf(new MessageIssueLookup.MessageIssue(
                issue(IssueStatus.OPEN), MessageIssueLookup.Membership.LIVE));

        assertEquals(MessageIssueRefVo.Membership.LIVE, vo.getMembership());
        assertEquals(IssueStatus.OPEN, vo.getStatus());
        assertNull(vo.getPublishedAt(), "an open issue has not been published");
    }

    /**
     * A released issue that went out without the message stays MISSED on the wire.
     *
     * Read as LIVE, the row would tell the editor the message is due in an issue
     * that was released without it -- the opposite of the warning it carries.
     */
    @Test
    public void amissedRowStaysMissedOnTheWire() throws Exception {
        MessageIssueRefVo vo = PublicationIssueRestService.refOf(new MessageIssueLookup.MessageIssue(
                issue(IssueStatus.PUBLISHED), MessageIssueLookup.Membership.MISSED));

        assertEquals(MessageIssueRefVo.Membership.MISSED, vo.getMembership());
        assertEquals(IssueStatus.PUBLISHED, vo.getStatus());
        assertEquals(new Date(1_700_000_000_000L), vo.getIntervalFrom(),
                "the period start is the stamp the panel shows beside a missed issue");

        String json = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build()
                .writeValueAsString(vo);
        assertTrue(json.contains("\"membership\":\"MISSED\""), json);
    }

    /** Every membership the lookup can answer has its own wire value. */
    @Test
    public void everyLookupMembershipHasItsOwnWireValue() {
        for (MessageIssueLookup.Membership m : MessageIssueLookup.Membership.values()) {
            MessageIssueRefVo vo = PublicationIssueRestService.refOf(
                    new MessageIssueLookup.MessageIssue(issue(IssueStatus.PUBLISHED), m));
            assertEquals(m.name(), vo.getMembership().name());
        }
    }

    /**
     * The series' editing surface does not travel to the message editor.
     *
     * Asserted against the serialized JSON rather than against absent getters,
     * because the failure this guards against is somebody widening the VO to
     * reuse PublicationSeriesDescVo -- which would compile, pass every mapping
     * assertion above, and quietly ship the patterns.
     */
    @Test
    public void thePayloadCarriesNoSeriesEditingSurface() throws Exception {
        MessageIssueRefVo vo = PublicationIssueRestService.refOf(new MessageIssueLookup.MessageIssue(
                issue(IssueStatus.PUBLISHED), MessageIssueLookup.Membership.FROZEN));

        // PORT: the wire is written by Jackson 3 here, configured with the
        // Jackson 2 defaults this deployment pins.
        String json = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build()
                .writeValueAsString(vo);

        assertTrue(json.contains("EfS uge 29, 2026"), "the issue name is the point of the row");
        for (String leaked : new String[] {
                "nameSuggestionPattern", "fileNamePattern", "messageReferenceFormat", "linkPattern" }) {
            assertEquals(-1, json.indexOf(leaked),
                    leaked + " belongs to the series editor, not to a message screen");
        }
    }

    // -------------------------------------------------------------- grouping

    /**
     * A message re-listed in every edition of one series is ONE row, not a hundred.
     *
     * This is the shape the estate actually has: an IN_FORCE_AT_CUTOFF series
     * lists every message still in force in every weekly edition, so a notice in
     * force for two years is a member of a hundred-odd issues of it. Ungrouped,
     * those hundred rows push the single EfS row an editor came to find off the
     * bottom of the panel, and the response carries a hundred near-identical
     * objects to achieve it.
     */
    @Test
    public void manyIssuesOfOneSeriesCollapseToOneCountedRow() {
        PublicationSeries pt = series("dma-pt");
        var rows = PublicationIssueRestService.group(List.of(
                frozen(issue(IssueStatus.PUBLISHED, pt, "P&T uge 27", 1_700_000_000_000L)),
                frozen(issue(IssueStatus.PUBLISHED, pt, "P&T uge 29", 1_701_200_000_000L)),
                frozen(issue(IssueStatus.PUBLISHED, pt, "P&T uge 28", 1_700_600_000_000L))));

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).getIssueCount());
        assertEquals("P&T uge 29", rows.get(0).getNames().get("da"),
                "the group is represented by its LATEST issue -- 'has this gone out' is a "
                        + "question about the most recent edition");
    }

    /**
     * Published-in-one-issue and pending-in-another do not merge.
     *
     * This is the normal state of a weekly series, not an edge case: the message
     * went out in last week's issue and is due again in this week's. Merging on
     * the series alone would report one of the two and silently drop the other --
     * and which one it dropped would depend on the order the rows arrived in.
     */
    @Test
    public void thetwoBasesOfOneSeriesStayApart() {
        PublicationSeries efs = series("dma-efs");
        var rows = PublicationIssueRestService.group(List.of(
                frozen(issue(IssueStatus.PUBLISHED, efs, "EfS uge 28", 1_700_000_000_000L)),
                live(issue(IssueStatus.OPEN, efs, "EfS uge 29", 1_700_600_000_000L))));

        assertEquals(2, rows.size());
        assertEquals(MessageIssueRefVo.Membership.FROZEN, rows.get(0).getMembership());
        assertEquals(MessageIssueRefVo.Membership.LIVE, rows.get(1).getMembership());
        assertEquals(1, rows.get(0).getIssueCount());
        assertEquals(1, rows.get(1).getIssueCount());
    }

    /**
     * A week that printed the message and a week that missed it are two rows.
     *
     * Merged on the series alone, the frozen row would stand for both and the
     * missed week -- the one an amend is due on -- would be counted and not named.
     */
    @Test
    public void afrozenAndAMissedIssueOfOneSeriesStayApart() {
        PublicationSeries efs = series("dma-efs");
        var rows = PublicationIssueRestService.group(List.of(
                frozen(issue(IssueStatus.PUBLISHED, efs, "EfS uge 28", 1_700_000_000_000L)),
                new MessageIssueLookup.MessageIssue(
                        issue(IssueStatus.PUBLISHED, efs, "EfS uge 29", 1_700_600_000_000L),
                        MessageIssueLookup.Membership.MISSED)));

        assertEquals(2, rows.size());
        assertEquals(MessageIssueRefVo.Membership.FROZEN, rows.get(0).getMembership());
        assertEquals(MessageIssueRefVo.Membership.MISSED, rows.get(1).getMembership());
        assertEquals("EfS uge 29", rows.get(1).getNames().get("da"));
    }

    /** Different series stay separate rows, in the order the lookup produced them. */
    @Test
    public void separateSeriesRemainSeparateRows() {
        var rows = PublicationIssueRestService.group(List.of(
                frozen(issue(IssueStatus.PUBLISHED, series("dma-efs"), "EfS uge 29", 1_700_000_000_000L)),
                frozen(issue(IssueStatus.PUBLISHED, series("dma-pt"), "P&T uge 29", 1_700_000_000_000L))));

        assertEquals(List.of("dma-efs", "dma-pt"),
                rows.stream().map(MessageIssueRefVo::getSeriesId).toList());
    }

    /**
     * Issues with no series do not all collapse into one row.
     *
     * A null seriesId is not a shared identity. Keying on it would merge every
     * seriesless issue into a single row claiming a count it never earned.
     */
    @Test
    public void seriesLessIssuesDoNotMergeWithEachOther() {
        PublicationIssue a = issue(IssueStatus.PUBLISHED, null, "Bilag A", 1_700_000_000_000L);
        PublicationIssue b = issue(IssueStatus.PUBLISHED, null, "Bilag B", 1_700_600_000_000L);
        a.setPublicId("annex-a");
        b.setPublicId("annex-b");

        var rows = PublicationIssueRestService.group(List.of(frozen(a), frozen(b)));

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).getIssueCount());
        assertEquals(1, rows.get(1).getIssueCount());
    }

    /** An issue with no interval never wins the group over one that has a date. */
    @Test
    public void anUndatedIssueDoesNotWinTheGroup() {
        PublicationSeries efs = series("dma-efs");
        PublicationIssue undated = issue(IssueStatus.PUBLISHED, efs, "Udateret", 1_700_000_000_000L);
        undated.setIntervalFrom(null);

        var rows = PublicationIssueRestService.group(List.of(
                frozen(issue(IssueStatus.PUBLISHED, efs, "EfS uge 29", 1_700_600_000_000L)),
                frozen(undated)));

        assertEquals(1, rows.size());
        assertEquals("EfS uge 29", rows.get(0).getNames().get("da"));
        assertEquals(2, rows.get(0).getIssueCount(), "it still counts, it just does not represent");
    }

    /**
     * An edition of an in-force series, as the shaping leaves it: no interval
     * start, the validity date as its close, published the day after.
     */
    private static PublicationIssue inForceEdition(PublicationSeries series, String publicId, String name,
                                                   long intervalTo) {
        PublicationIssue i = issue(IssueStatus.PUBLISHED, series, name, intervalTo);
        i.setPublicId(publicId);
        i.setIntervalFrom(null);
        i.setIntervalTo(new Date(intervalTo));
        i.setPublishedAt(new Date(intervalTo + DAY));
        return i;
    }

    private static final long DAY = 24 * 3600_000L;

    /**
     * Every edition of an in-force series has no interval start, so the start
     * cannot rank them: the group is represented by the edition with the latest
     * close, whichever order the lookup returned them in.
     */
    @Test
    public void anInForceSeriesIsRepresentedByItsLatestEdition() {
        PublicationSeries fa = series("dma-firing-areas");
        var rows = PublicationIssueRestService.group(List.of(
                frozen(inForceEdition(fa, "fa-2025", "Skydeområder 2025", 1_700_000_000_000L)),
                frozen(inForceEdition(fa, "fa-2027", "Skydeområder 2027", 1_700_000_000_000L + 730 * DAY)),
                frozen(inForceEdition(fa, "fa-2026", "Skydeområder 2026", 1_700_000_000_000L + 365 * DAY))));

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).getIssueCount());
        assertEquals("Skydeområder 2027", rows.get(0).getNames().get("da"),
                "the group is represented by the edition with the latest close, not by the first row");
    }

    /**
     * A retired issue and the replacement that superseded it cover one period:
     * the same start and the same close. The replacement was published later and
     * represents the group, so the panel does not show the withdrawn edition.
     */
    @Test
    public void aRetiredIssueAndItsReplacementAreRepresentedByTheReplacement() {
        PublicationSeries efs = series("dma-efs");
        PublicationIssue retired = issue(IssueStatus.RETIRED, efs, "EfS uge 29", 1_700_000_000_000L);
        retired.setPublicId("efs-29-a");
        retired.setIntervalTo(new Date(1_700_000_000_000L + 7 * DAY));
        retired.setPublishedAt(new Date(1_700_000_000_000L + 7 * DAY));
        PublicationIssue replacement = issue(IssueStatus.PUBLISHED, efs, "EfS uge 29, rettet", 1_700_000_000_000L);
        replacement.setPublicId("efs-29-b");
        replacement.setIntervalTo(new Date(1_700_000_000_000L + 7 * DAY));
        replacement.setPublishedAt(new Date(1_700_000_000_000L + 9 * DAY));

        for (List<PublicationIssue> order : List.of(List.of(retired, replacement), List.of(replacement, retired))) {
            var rows = PublicationIssueRestService.group(order.stream().map(MessageIssueRefWireTest::frozen).toList());

            assertEquals(1, rows.size());
            assertEquals(2, rows.get(0).getIssueCount());
            assertEquals("efs-29-b", rows.get(0).getIssuePublicId(),
                    "the replacement, published later, represents the period");
            assertEquals(IssueStatus.PUBLISHED, rows.get(0).getStatus(),
                    "the panel would show the period as withdrawn");
        }
    }

    /**
     * Editions that tie on every date are ranked by their public id, so the
     * representative does not depend on the order the database returned them in.
     */
    @Test
    public void editionsThatTieOnEveryDateHaveAStableRepresentative() {
        PublicationSeries fa = series("dma-firing-areas");
        PublicationIssue a = inForceEdition(fa, "fa-a", "Skydeområder A", 1_700_000_000_000L);
        PublicationIssue b = inForceEdition(fa, "fa-b", "Skydeområder B", 1_700_000_000_000L);

        for (List<PublicationIssue> order : List.of(List.of(a, b), List.of(b, a))) {
            var rows = PublicationIssueRestService.group(order.stream().map(MessageIssueRefWireTest::frozen).toList());

            assertEquals(1, rows.size());
            assertEquals("fa-b", rows.get(0).getIssuePublicId(), "the representative changed with the row order");
        }
    }

    /** A missing close or publish stamp loses to a real one, as a missing start does. */
    @Test
    public void aMissingCloseOrPublishStampDoesNotWinTheGroup() {
        PublicationSeries fa = series("dma-firing-areas");
        PublicationIssue noClose = inForceEdition(fa, "fa-z1", "Uden slut", 1_700_000_000_000L);
        noClose.setIntervalTo(null);
        PublicationIssue noStamp = inForceEdition(fa, "fa-z2", "Uden stempel", 1_700_000_000_000L);
        noStamp.setPublishedAt(null);
        PublicationIssue dated = inForceEdition(fa, "fa-a", "Skydeområder", 1_700_000_000_000L);

        var rows = PublicationIssueRestService.group(List.of(frozen(noClose), frozen(noStamp), frozen(dated)));

        assertEquals(1, rows.size());
        assertEquals("fa-a", rows.get(0).getIssuePublicId());
    }

    /** A series that was never loaded leaves the row without one, rather than failing. */
    @Test
    public void anIssueWithNoSeriesStillMaps() {
        PublicationIssue orphan = issue(IssueStatus.OPEN);
        orphan.setSeries(null);

        MessageIssueRefVo vo = PublicationIssueRestService.refOf(
                new MessageIssueLookup.MessageIssue(orphan, MessageIssueLookup.Membership.LIVE));

        assertNull(vo.getSeriesId());
        assertTrue(vo.getSeriesNames().isEmpty());
        assertEquals("EfS uge 29, 2026", vo.getNames().get("da"));
    }
}
