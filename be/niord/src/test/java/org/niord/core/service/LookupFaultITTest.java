/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.TemplateExecutionService;
import org.niord.core.chart.ChartService;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.mailinglist.MailingListService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.publication.PublicationService;
import org.niord.core.report.FmReportService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.source.SourceService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PORT-14: the single-row look-ups answer null only when no row has the identifier (a {@code NoResultException}).
 * A failure of the look-up's query propagates, so a caller does not act on "not found" and the log points at the
 * failure. Where the schema does not hold the identifier unique (areas' and categories' MRN and legacy id, a
 * message's or a chart's legacy id, a source's name, and a script path or a contact address compared ignoring
 * case), several rows answer null as before and a WARN names the identifier, without dooming the caller's
 * transaction. The look-up of a message by uid has the same guard in {@code MessageLookupFaultITTest}.
 */
class LookupFaultITTest extends ITTest {

    private static final Object UNSET = new Object();

    @Autowired AreaService areaService;
    @Autowired TemplateExecutionService templateExecutionService;
    @Autowired ChartService chartService;
    @Autowired DictionaryService dictionaryService;
    @Autowired MailingListService mailingListService;
    @Autowired MessageSeriesService messageSeriesService;
    @Autowired PublicationService publicationService;
    @Autowired FmReportService fmReportService;
    @Autowired ScriptResourceService scriptResourceService;
    @Autowired SourceService sourceService;

    @PersistenceContext
    EntityManager em;

    /** One look-up: the SQL that picks an identifier exactly one row has, and the look-up by that identifier */
    private record LookUp(String name, String existingSql, Function<String, Object> lookUp) {}

    private List<LookUp> lookUps() {
        return List.of(
                new LookUp("AreaService.findByMrn",
                        "SELECT mrn FROM public.\"Area\" WHERE mrn IS NOT NULL GROUP BY mrn HAVING count(*) = 1 "
                                + "ORDER BY mrn LIMIT 1",
                        areaService::findByMrn),
                // The fixture's categories carry no MRN; CategoryService.findByMrn is the same code as the area's
                new LookUp("TemplateExecutionService.getParamTypeByName",
                        "SELECT name FROM public.\"ParamType\" WHERE \"DTYPE\" = 'CompositeParamType' ORDER BY 1 LIMIT 1",
                        templateExecutionService::getParamTypeByName),
                new LookUp("MessageSeriesService.findBySeriesId",
                        "SELECT \"seriesId\" FROM public.\"MessageSeries\" ORDER BY 1 LIMIT 1",
                        messageSeriesService::findBySeriesId),
                new LookUp("ChartService.findByChartNumber",
                        "SELECT \"chartNumber\" FROM public.\"Chart\" WHERE \"chartNumber\" IS NOT NULL ORDER BY 1 LIMIT 1",
                        chartService::findByChartNumber),
                new LookUp("DictionaryService.findByName",
                        "SELECT name FROM public.\"Dictionary\" ORDER BY 1 LIMIT 1",
                        dictionaryService::findByName),
                new LookUp("FmReportService.findByReportId",
                        "SELECT \"reportId\" FROM public.\"FmReport\" ORDER BY 1 LIMIT 1",
                        fmReportService::findByReportId),
                new LookUp("ScriptResourceService.findByPath",
                        "SELECT min(path) FROM public.\"ScriptResource\" GROUP BY lower(path) HAVING count(*) = 1 "
                                + "ORDER BY 1 LIMIT 1",
                        scriptResourceService::findByPath),
                new LookUp("MailingListService.findByMailingListId",
                        "SELECT \"mailingListId\" FROM public.\"MailingList\" ORDER BY 1 LIMIT 1",
                        mailingListService::findByMailingListId),
                new LookUp("PublicationService.findByPublicationId",
                        "SELECT \"publicationId\" FROM public.\"Publication\" ORDER BY 1 LIMIT 1",
                        publicationService::findByPublicationId),
                new LookUp("SourceService.findByName",
                        "SELECT min(lang) || '|' || min(name) FROM public.\"SourceDesc\" WHERE name IS NOT NULL "
                                + "GROUP BY lang, lower(name) HAVING count(*) = 1 ORDER BY 1 LIMIT 1",
                        langAndName -> sourceService.findByName(langAndName.split("\\|", 2)[0],
                                langAndName.split("\\|", 2)[1])));
    }

    /**
     * For each look-up: an identifier one fixture row has is found, one no row has answers null, and when the query
     * fails (here the transaction's earlier statement failed, so PostgreSQL refuses every later one, as it does after
     * a lock or statement timeout or a lost connection) the failure propagates as a {@code PersistenceException}
     * instead of answering null for a row that exists.
     */
    @TestFactory
    Stream<DynamicTest> aFailedLookUpPropagatesAndNotFoundStillAnswersNull() {
        return lookUps().stream().map(l -> DynamicTest.dynamicTest(l.name(), () -> {
            String existing = Tx.requiringNew().call(() -> (String) em.createNativeQuery(l.existingSql())
                    .getSingleResult());
            assertNotNull(existing, l.name() + ": precondition: the fixture has an identifier to look up");
            assertNotNull(Tx.requiringNew().call(() -> l.lookUp().apply(existing)),
                    l.name() + ": the fixture's '" + existing + "' is found");
            String absent = l.name().startsWith("SourceService") ? "en|IT no such source" : "IT-no-such-identifier";
            assertNull(Tx.requiringNew().call(() -> l.lookUp().apply(absent)),
                    l.name() + ": an identifier no row has answers null");

            AtomicReference<Object> found = new AtomicReference<>(UNSET);
            AtomicReference<Exception> failure = new AtomicReference<>();
            AtomicReference<Exception> lookUpFailure = new AtomicReference<>();
            assertThrows(UnexpectedRollbackException.class, () -> Tx.requiringNew().run(() -> {
                try {
                    em.createNativeQuery("select * from \"IT_no_such_table\"").getResultList();
                } catch (Exception e) {
                    failure.set(e);
                }
                try {
                    found.set(l.lookUp().apply(existing));
                } catch (Exception e) {
                    lookUpFailure.set(e);
                }
            }), l.name() + ": the transaction was doomed by the failed statement");

            assertNotNull(failure.get(), l.name() + ": precondition: the injected statement failed");
            assertInstanceOf(PersistenceException.class, lookUpFailure.get(),
                    l.name() + ": the failed look-up propagates the failure, it answered " + found.get());
            assertSame(UNSET, found.get(), l.name() + ": the failed look-up did not answer \"not found\"");
        }));
    }

    /**
     * Two areas share an MRN and a legacy id, as restored data can (neither column is unique in the schema). Each
     * look-up answers null, as it did before it was narrowed, a WARN names the identifier, and the caller's
     * transaction is not doomed: it commits the two areas it wrote.
     */
    @Test
    void twoAreasWithOneIdentifierAnswerNullWithAWarningAndTheCallerCommits() {
        String suffix = Long.toString(System.nanoTime());
        String mrn = "urn:mrn:iho:test:it-duplicate-area-" + suffix;
        String legacyId = "it-duplicate-area-" + suffix;

        Logger logger = (Logger) LoggerFactory.getLogger(AreaService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AtomicReference<List<Integer>> ids = new AtomicReference<>(List.of());
        try {
            List<Object> answers = Tx.requiringNew().call(() -> {
                Area first = area(mrn, legacyId, "IT duplicate area one");
                Area second = area(mrn, legacyId, "IT duplicate area two");
                em.flush();
                ids.set(List.of(first.getId(), second.getId()));
                return Arrays.asList(areaService.findByMrn(mrn), areaService.findByLegacyId(legacyId));
            });

            assertEquals(2, ids.get().size(), "precondition: two areas were committed");
            assertEquals(2L, Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT count(*) FROM public.\"Area\" WHERE mrn = :mrn").setParameter("mrn", mrn)
                    .getSingleResult()).longValue()), "the caller's transaction committed both areas");
            assertNull(answers.get(0), "two areas with one MRN: the look-up by MRN answers none");
            assertNull(answers.get(1), "two areas with one legacy id: the look-up by legacy id answers none");

            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(warnings.stream().anyMatch(w -> w.contains("'" + mrn + "'")),
                    "a WARN names the MRN: " + warnings);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("'" + legacyId + "'")),
                    "a WARN names the legacy id: " + warnings);
        } finally {
            logger.detachAppender(appender);
            Tx.requiringNew().run(() -> {
                for (Integer id : ids.get()) {
                    Area area = em.find(Area.class, id);
                    if (area != null) {
                        em.remove(area);
                    }
                }
            });
        }
    }

    private Area area(String mrn, String legacyId, String name) {
        Area area = new Area();
        area.setMrn(mrn);
        area.setLegacyId(legacyId);
        area.checkCreateDesc("en").setName(name);
        em.persist(area);
        return area;
    }
}
