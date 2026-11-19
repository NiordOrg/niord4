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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.report.FmReportService;
import org.niord.core.user.User;
import org.niord.test.ITTest;
import org.niord.test.ReportFixtures;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REAL renderer, drawing a two-language issue at publish.
 *
 * Every other publications IT renders through {@link StubIssueRenderService}, which is a
 * {@code @Service @Primary} on the test classpath and therefore wins wherever an
 * {@link IssueRenderService} is injected. That is right for those suites -- they are about what is
 * stamped, frozen, archived and recorded, and deterministic bytes are the cheapest way to have a
 * document at all -- but it left the real renderer with no test in this module: nothing had ever
 * run FreeMarker and the PDF engine from the release path, and nothing had ever put a render on a
 * worker thread of the bounded pool {@code IssueRenderService.renderAll} draws the languages on.
 *
 * WHAT THIS PROVES. A two-language release goes all the way through: the report row and the
 * templates are read, the FreeMarker template is processed, the layout engine produces a document
 * per language, and the bytes the publish writes are complete PDFs at the paths the issue's own
 * desc rows name. Two languages rather than one is the whole point -- {@code renderAll} renders a
 * single request inline and only submits to the executor from the second -- so this is also the
 * only test in which a render actually happens on a pool thread that carries neither the publish
 * transaction nor a persistence context of the caller's. It comes back with a document, which is
 * what "safe by construction" has to mean in practice: the report and the members reach the worker
 * as detached data, and the few rows a render still reads for itself it reads in a session of its
 * own.
 *
 * OPTING OUT OF THE STUB, HERE ONLY. Marking a second bean {@code @Primary} does not work -- two
 * primary candidates for one type is a {@code NoUniqueBeanDefinitionException}, not a
 * tie-break -- and the stub is a shared fixture that must keep winning for every other suite. So
 * the imported configuration below does not add a bean: it flips the primary flag on the two
 * definitions that already exist, in this context and no other. Every other IT keeps its
 * {@code MergedContextConfiguration} and therefore keeps the stub. The cost is that this class
 * boots a Spring context of its own (the {@code @Import} is part of the context cache key) against
 * the same shared containers.
 *
 * THE FIXTURE HAS NO MEMBERS, deliberately. The IT seed carries zero Message rows, so there is no
 * corpus to draw from and a hand-built one would say nothing about the renderer that an empty list
 * does not: what is under test is the template, the engine and the threading, and the report's
 * template prints its cover and its heading for an empty issue exactly as it prints them for a
 * full one. The report is a real weekly report of DK's, which {@link ReportFixtures} adds since the
 * fixture (the base product's bootstrap and the sample) carries none; which one, and why not the
 * obvious one, is recorded on {@link #REPORT_ID}.
 *
 * WHAT IT LEAVES BEHIND. The rows are not committed -- the test is {@code @Transactional} and
 * Spring rolls it back -- but a written file is not part of a transaction, so the documents are
 * removed in {@link #removeTheWrittenDocuments()}, and so is the issue's folder of the preview store.
 * The templates are rows: the fixture holds the base
 * product's templates, as a bootstrapped deployment has them, and since C4.2a a render reads the
 * database only and writes no row.
 */
@Import(IssueRenderRealITTest.RealRendererPreferred.class)
public class IssueRenderRealITTest extends ITTest {

    /** The file name each language's document is written under; distinct, as D-3 requires. */
    private static final String FILE_NAME_PATTERN = "nm-%s.pdf";

    /**
     * A weekly report of DK's whose template renders an issue (a base product template).
     * Without the row the release fails (no such report), so it is ensured first.
     * "nm-report" includes {@code nm-report-pdf-intro.ftl}, an empty file until C4.2a, whose include
     * then failed; since C4.2a the bootstrap carries it as a one-line comment. This test keeps
     * "nm-tp-report", which it was written against. The base product's own reports, "standard" and
     * "draft", have cells of their own below ({@link #anIssueOnABaseReportIsPreviewedAndReleased}).
     */
    private static final String REPORT_ID = "nm-tp-report";

    /** The issue's name in each language, which the front page's caption row prints. */
    private static final String ISSUE_NAME = "Test issue";

    /**
     * The one context in which the module-wide stub does not win.
     *
     * A {@code BeanFactoryPostProcessor} rather than a bean: both renderers are already defined --
     * the real one by its own {@code @Service}, the stub by the one on the test classpath -- so
     * there is nothing to add, only a preference to state. It runs after every definition is
     * registered and before any of them is instantiated, and it fails loudly if either bean is ever
     * renamed.
     */
    @TestConfiguration
    static class RealRendererPreferred {

        @Bean
        static BeanFactoryPostProcessor preferTheRealIssueRenderService() {
            return beanFactory -> {
                beanFactory.getBeanDefinition("stubIssueRenderService").setPrimary(false);
                beanFactory.getBeanDefinition("issueRenderService").setPrimary(true);
            };
        }
    }

    @Autowired
    IssuePublishService publishService;

    @Autowired
    IssueLifecycleService lifecycle;

    @Autowired
    PublicationPathService paths;

    @Autowired
    org.niord.core.repo.RepoStore store;

    /** The issue's folder of the store the release wrote into, whose rows are deleted after the test */
    private String writtenFolder;

    /** The renderer the publish path will be handed -- the real one, in this context. */
    @Autowired
    IssueRenderService renderer;

    @Autowired
    FmReportService reportService;

    @PersistenceContext
    EntityManager em;

    /** The directory the release wrote into, held so it can be taken away again. */
    private Path writtenDir;

    /** The issue's folder of the preview store, which is outside the repository and deleted after the test */
    private Path previewFolder;

    /**
     * One render first, on one thread, before two of them run side by side.
     *
     * The stub's controls are statics shared by every IT in the JVM, so they are put back; the
     * render is the part that matters. The rows a render reads are the fixture's base product templates
     * and the report {@link ReportFixtures} ensures, committed (no template is copied into the
     * {@code ScriptResource} table on use since C4.2a; the settings it reads do not write since C3b).
     * The path is still warmed here, on one thread and in a committed transaction, so that what the
     * release below proves is the render of two workers side by side and not a first load.
     */
    @BeforeEach
    public void warmTheRenderPath() {
        StubIssueRenderService.reset();
        ReportFixtures.ensure(reportService, REPORT_ID);
        Tx.requiringNew().run(() -> renderer.render(new IssueRenderService.RenderRequest(
                REPORT_ID, "da", List.of(), null, null, null, false, null,
                // The zone is carried rather than asked of the current domain, exactly as a release
                // carries it: there is no request domain on this thread either.
                Map.of("timeZone", "Europe/Copenhagen"), null, Set.of())));
    }

    /**
     * A rollback takes the rows back and leaves the bytes, so the documents are removed by hand.
     * Nothing else touches these paths -- the issue's public id is minted per test.
     */
    @AfterEach
    public void removeTheWrittenDocuments() throws IOException {
        StubIssueRenderService.reset();
        if (writtenFolder != null) {
            store.deleteFolder(writtenFolder);
            writtenFolder = null;
        }
        if (previewFolder != null) {
            FileSystemUtils.deleteRecursively(previewFolder);
            previewFolder = null;
        }
    }

    // ------------------------------------------------------------------ fixture

    /** An OPEN issue of a query-backed weekly configured for Danish and English. */
    private PublicationIssue anIssueInTwoLanguages() {
        return anIssueInTwoLanguages(REPORT_ID);
    }

    /** An OPEN issue of a query-backed weekly on the given report, configured for Danish and English. */
    private PublicationIssue anIssueInTwoLanguages(String reportId) {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(TestIds.category());
        em.persist(c);

        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(TestIds.series());
        s.setStatus(SeriesStatus.ACTIVE);
        s.setContentMode(ContentMode.GENERATED_FROM_QUERY);
        s.setReportId(reportId);
        s.setCadence(SeriesCadence.WEEKLY);
        s.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
        s.setAliveAtCutoff(false);
        s.setReleaseMode(ReleaseMode.MANUAL_GATE);
        s.setNextIssueCreation(NextIssueCreation.MANUAL);
        s.setMessagePublication(MessagePublication.NONE);
        s.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
        s.setCategory(c);
        s.setDomain(TestOwnerDomain.of(em));
        for (String lang : List.of("da", "en")) {
            s.getLanguages().add(lang);
            PublicationSeriesDesc d = s.createDesc(lang);
            d.setName("Test series");
            // Without a pattern every language falls back to the issue's public id, and the two
            // documents would be written to one path -- a collision that would hide the render.
            d.setFileNamePattern(String.format(FILE_NAME_PATTERN, lang));
        }
        em.persist(s);

        User u = new User();
        u.setUsername(TestIds.user());
        em.persist(u);

        PublicationIssue i = lifecycle.create(s, new Date(1_699_000_000_000L),
                IntervalBoundSource.STAMPED, u);
        for (PublicationIssueDesc d : i.getDescs()) {
            d.setName(ISSUE_NAME);
        }
        em.flush();
        return i;
    }

    // ------------------------------------------------------------------ the release

    /**
     * Both languages of a release are drawn by the real renderer and land on disk as PDFs.
     *
     * The magic bytes and the trailer together are what make this an assertion about a DOCUMENT
     * rather than about a file existing: a render that threw half way would leave a truncated
     * stream that still starts with %PDF.
     */
    @Test
    @Transactional
    public void bothLanguagesOfARealReleaseAreWrittenAsCompletePdfs() throws Exception {
        assertFalse(renderer instanceof StubIssueRenderService,
                "the publish path was handed the stub, so this test would prove nothing about the "
                        + "renderer; the primary-bean override did not take effect");

        PublicationIssue issue = anIssueInTwoLanguages();
        writtenFolder = issue.getRepoPath();

        publishService.publish(issue.getId(),
                new IssuePublishService.PublishRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, null,
                        new Date(1_700_000_000_000L)));
        em.flush();

        PublicationIssue published = em.find(PublicationIssue.class, issue.getId());
        assertEquals(IssueStatus.PUBLISHED, published.getStatus());

        for (String lang : List.of("da", "en")) {
            PublicationIssueDesc desc = published.getDescs().stream()
                    .filter(d -> lang.equals(d.getLang())).findFirst().orElseThrow();

            assertEquals(FileSource.GENERATED, desc.getFileSource(),
                    "language " + lang + " was not generated by the release");
            assertEquals(String.format(FILE_NAME_PATTERN, lang), desc.getFileName(),
                    "language " + lang + " was written under another language's name");
            assertEquals(published.getRepoPath() + "/" + desc.getFileName(), desc.getFilePath(),
                    "the official record for " + lang + " points somewhere other than the file the "
                            + "release wrote");

            java.util.Optional<org.niord.core.repo.RepoFileInfo> file = store.stat(desc.getFilePath());
            assertTrue(file.isPresent(),
                    "language " + lang + " has a recorded path with no row behind it: " + desc.getFilePath());
            byte[] bytes = store.readAll(file.get());
            assertTrue(bytes.length > 0, "language " + lang + " was written as an empty file");
            assertEquals("%PDF", header(bytes),
                    "language " + lang + " is not a PDF; the real renderer produced "
                            + bytes.length + " bytes beginning '" + header(bytes) + "'");
            assertTrue(trailer(bytes).contains("%%EOF"),
                    "language " + lang + " has no PDF trailer, so the document is truncated: the "
                            + "render failed part way and the bytes were written anyway");
        }

        assertNotEquals(filePathOf(published, "da"), filePathOf(published, "en"),
                "both languages were recorded against one file, so one release overwrote the other");
        assertTrue(StubIssueRenderService.requests().isEmpty(),
                "the stub recorded a render, so the publish did not go through the real renderer");
    }

    /**
     * An issue on the base product's Standard or Draft report is previewed and released (RENDER11).
     *
     * Both reports draw {@code message-list-pdf.ftl}, whose front page prints
     * {@code ${searchCriteria}} with no default. The issue render passed no caption, so FreeMarker
     * failed on a missing variable and both the preview and the release answered RENDER_FAILED;
     * Standard is the first report the series editor offers. The render now gives the template the
     * issue's name as the caption.
     */
    @ParameterizedTest
    @ValueSource(strings = {"standard", "draft"})
    @Transactional
    public void anIssueOnABaseReportIsPreviewedAndReleased(String reportId) throws Exception {
        assertFalse(renderer instanceof StubIssueRenderService,
                "the publish path was handed the stub, so this test would prove nothing about the renderer");

        PublicationIssue issue = anIssueInTwoLanguages(reportId);
        writtenFolder = issue.getRepoPath();
        previewFolder = paths.previewRoot().resolve(issue.getPublicId());

        List<IssuePreviewService.Preview> previews = publishService.preview(issue.getId());
        assertEquals(2, previews.size(), "the preview on '" + reportId + "' did not draw both languages");
        for (IssuePreviewService.Preview preview : previews) {
            byte[] bytes = Files.readAllBytes(preview.path());
            assertEquals("%PDF", header(bytes),
                    "the preview of " + preview.lang() + " on '" + reportId + "' is not a PDF");
            assertTrue(trailer(bytes).contains("%%EOF"),
                    "the preview of " + preview.lang() + " on '" + reportId + "' is truncated");
        }

        publishService.publish(issue.getId(),
                new IssuePublishService.PublishRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, null,
                        new Date(1_700_000_000_000L)));
        em.flush();

        PublicationIssue published = em.find(PublicationIssue.class, issue.getId());
        assertEquals(IssueStatus.PUBLISHED, published.getStatus(),
                "an issue on '" + reportId + "' was not released");
        for (String lang : List.of("da", "en")) {
            PublicationIssueDesc desc = published.getDescs().stream()
                    .filter(d -> lang.equals(d.getLang())).findFirst().orElseThrow();
            assertEquals(FileSource.GENERATED, desc.getFileSource(),
                    "language " + lang + " on '" + reportId + "' was not generated by the release");
            java.util.Optional<org.niord.core.repo.RepoFileInfo> file = store.stat(desc.getFilePath());
            assertTrue(file.isPresent(), "language " + lang + " on '" + reportId + "' has no document");
            byte[] bytes = store.readAll(file.get());
            assertEquals("%PDF", header(bytes),
                    "the released " + lang + " document on '" + reportId + "' is not a PDF");
            assertTrue(trailer(bytes).contains("%%EOF"),
                    "the released " + lang + " document on '" + reportId + "' is truncated");
        }
    }

    /**
     * A series whose report does not exist is neither previewed nor released: the render used to
     * fall back to the standard report and publish the generic message list as the issue's document.
     */
    @Test
    @Transactional
    public void anIssueWhoseReportDoesNotExistIsNotPreviewedNorReleased() {
        String missing = "no-such-report-" + UUID.randomUUID();
        PublicationIssue issue = anIssueInTwoLanguages(missing);
        writtenFolder = issue.getRepoPath();
        previewFolder = paths.previewRoot().resolve(issue.getPublicId());

        IssueRenderService.RenderFailedException preview = assertThrows(
                IssueRenderService.RenderFailedException.class, () -> publishService.preview(issue.getId()),
                "a preview on a report that does not exist drew a document");
        assertTrue(causeChainMentions(preview, "no such report: " + missing),
                "the preview's failure does not name the missing report: " + preview);

        IssueRenderService.RenderFailedException release = assertThrows(
                IssueRenderService.RenderFailedException.class,
                () -> publishService.publish(issue.getId(), new IssuePublishService.PublishRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, null, new Date(1_700_000_000_000L))),
                "a release on a report that does not exist published a document");
        assertTrue(causeChainMentions(release, "no such report: " + missing),
                "the release's failure does not name the missing report: " + release);
        assertEquals(IssueStatus.OPEN, issue.getStatus(), "a release on a missing report flipped the status");
        for (PublicationIssueDesc d : issue.getDescs()) {
            assertNull(d.getFilePath(), "language " + d.getLang() + " got a document from a report that does not exist");
        }
    }

    /** Whether the message of the exception or of one of its causes contains the text. */
    private static boolean causeChainMentions(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The caption the front page prints is the issue's name in the render language, else the series'
     * name, and never null: a null caption is what made the template fail (RENDER11).
     */
    @Test
    @Transactional
    public void theCaptionIsTheIssuesNameElseTheSeriesName() {
        PublicationIssue issue = anIssueInTwoLanguages("standard");
        PublicationSeries series = issue.getSeries();

        assertEquals(ISSUE_NAME, IssuePublishService.captionFor(issue, series, "da"));
        assertEquals(ISSUE_NAME, IssuePublishService.captionFor(issue, series, "en"));

        issue.getDesc("en").setName(" ");
        assertEquals("Test series", IssuePublishService.captionFor(issue, series, "en"),
                "an issue with no name in the language is captioned with the series' name");

        series.getDesc("en").setName(null);
        assertEquals("", IssuePublishService.captionFor(issue, series, "en"),
                "with no name at all the caption is empty, never null");
        assertEquals(ISSUE_NAME, IssuePublishService.captionFor(issue, series, "da"),
                "the other language keeps its own name");
    }

    /**
     * The caption is escaped for the template, as the report page's print escapes its own.
     *
     * {@code message-list-pdf.ftl} prints {@code ${searchCriteria}} as it gets it, and the renderer
     * parses the result as HTML. The issue's and the series' names are free text an editor types,
     * so a name with markup-like text must stay text in the released PDF: unescaped, 'Week 12
     * <draft>' would lose '<draft>' and an unclosed {@code <style} would swallow the front page.
     * With UTF-8 only the markup characters are escaped; a letter such as Ø stays as it is.
     */
    @Test
    @Transactional
    public void theCaptionIsEscapedForTheTemplate() throws Exception {
        PublicationIssue issue = anIssueInTwoLanguages("standard");
        PublicationSeries series = issue.getSeries();

        issue.getDesc("en").setName("Kattegat & <Øresund>");
        assertEquals("Kattegat &amp; &lt;Øresund&gt;", IssuePublishService.captionFor(issue, series, "en"),
                "the issue's name reached the template as markup");

        issue.getDesc("da").setName(" ");
        series.getDesc("da").setName("A &lt; B \"quoted\" <style>");
        assertEquals("A &amp;lt; B &quot;quoted&quot; &lt;style&gt;",
                IssuePublishService.captionFor(issue, series, "da"),
                "the series' name reached the template as markup");

        // The Standard report still draws both languages with these names on its front page.
        previewFolder = paths.previewRoot().resolve(issue.getPublicId());
        em.flush();
        List<IssuePreviewService.Preview> previews = publishService.preview(issue.getId());
        assertEquals(2, previews.size(), "the preview did not draw both languages");
        for (IssuePreviewService.Preview preview : previews) {
            byte[] bytes = Files.readAllBytes(preview.path());
            assertEquals("%PDF", header(bytes), "the preview of " + preview.lang() + " is not a PDF");
            assertTrue(trailer(bytes).contains("%%EOF"), "the preview of " + preview.lang() + " is truncated");
        }
    }

    private static String filePathOf(PublicationIssue issue, String lang) {
        return issue.getDescs().stream().filter(d -> lang.equals(d.getLang()))
                .findFirst().orElseThrow().getFilePath();
    }

    /** The first four bytes, as text, for the magic-number assertion and its message. */
    private static String header(byte[] bytes) {
        return new String(bytes, 0, Math.min(4, bytes.length), StandardCharsets.ISO_8859_1);
    }

    /** The last few bytes, as text: a complete PDF ends with its %%EOF trailer. */
    private static String trailer(byte[] bytes) {
        int from = Math.max(0, bytes.length - 32);
        return new String(bytes, from, bytes.length - from, StandardCharsets.ISO_8859_1);
    }
}
