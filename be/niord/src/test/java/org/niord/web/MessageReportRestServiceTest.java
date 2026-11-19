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
package org.niord.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.IndexIncompleteNotice;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.report.FmReport;
import org.niord.core.report.FmReportService;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.FmTemplateService.FmTemplateBuilder;
import org.niord.core.script.FmTemplateService.ProcessFormat;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.web.NiordWebException;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.MessageVo;
import org.niord.model.search.PagedSearchResultVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two PDF endpoints of {@link MessageReportRestService}, as far as the request decides them: who may
 * switch on the debug HTML, the map thumbnails and hand the template values of their own, which report
 * renders a list and which one a message, which zone the document is dated in, what an unknown message
 * answers, and how many anonymous PDFs are made at once - the rest are answered 503, and a permit is back
 * however the PDF that held it ended.
 *
 * <p>Both endpoints answer anonymously and the public site sends its readers to them, so the anonymous
 * caller is the one that matters. The template engine is replaced by a builder that records what it was
 * handed; what the templates then do with it is FreeMarker's business, and the zone is applied by
 * {@link FmTemplateBuilder#timeZone(String)} itself, covered in {@code FmTemplateBuilderTimeZoneTest}.
 */
class MessageReportRestServiceTest {

    private DomainService domainService;
    private UserService userService;
    private MessageService messageService;
    private MessageRestService messageRestService;
    private MessageSearchRestService messageSearchRestService;
    private FmReportService fmReportService;
    private FmTemplateService templateService;
    private FmTemplateBuilder builder;
    private IndexIncompleteNotice indexIncompleteNotice;
    private MessageReportRestService service;

    @BeforeEach
    void setUp() {
        domainService = mock(DomainService.class);
        userService = mock(UserService.class);
        messageService = mock(MessageService.class);
        messageRestService = mock(MessageRestService.class);
        messageSearchRestService = mock(MessageSearchRestService.class);
        fmReportService = mock(FmReportService.class);
        templateService = mock(FmTemplateService.class);
        builder = mock(FmTemplateBuilder.class, RETURNS_SELF);
        when(templateService.newFmTemplateBuilder()).thenReturn(builder);
        indexIncompleteNotice = mock(IndexIncompleteNotice.class);

        FmReport standard = new FmReport();
        standard.setReportId(FmReportService.STANDARD_REPORT_ID);
        standard.setTemplatePath("/templates/messages/message-list-pdf.ftl");
        when(fmReportService.getReport(any())).thenReturn(standard);
        when(fmReportService.getStandardReport()).thenReturn(standard);
        // As the backend defines it: the standard template without the editors' front page
        FmReport publicReport = new FmReport();
        publicReport.setReportId(FmReportService.PUBLIC_REPORT_ID);
        publicReport.setTemplatePath("/templates/messages/message-list-pdf.ftl");
        publicReport.getProperties().put("frontPage", false);
        when(fmReportService.getPublicReport()).thenReturn(publicReport);
        when(messageService.getSeparatePageUids(any())).thenReturn(Set.of());

        service = withPermits(3);
    }

    /** The service, making at most the given number of anonymous PDFs at once */
    private MessageReportRestService withPermits(int anonymousPdfConcurrency) {
        return new MessageReportRestService(mock(NiordApp.class), domainService, userService, messageService,
                messageRestService, messageSearchRestService, fmReportService, templateService, indexIncompleteNotice,
                anonymousPdfConcurrency);
    }

    // ------------------------------------------------------------------ fixtures

    /** A request carrying the given query parameters and nothing else */
    private static HttpServletRequest request(Map<String, String[]> parameters) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(parameters);
        when(request.getParameter(anyString())).thenAnswer(invocation -> {
            String[] values = parameters.get(invocation.<String>getArgument(0));
            return values == null || values.length == 0 ? null : values[0];
        });
        return request;
    }

    /** The query a crafted link would carry: every print parameter an anonymous caller must not set */
    private static Map<String, String[]> hostileQuery() {
        Map<String, String[]> parameters = new HashMap<>();
        parameters.put("lang", new String[] { "da" });
        parameters.put("debug", new String[] { "true" });
        parameters.put("param:searchCriteria", new String[] { "<script>alert(1)</script>" });
        parameters.put("param:frontPage", new String[] { "true" });
        parameters.put("pageSize", new String[] { "A4}</style><script>alert(1)</script>" });
        return parameters;
    }

    private static MessageVo message(String id, String seriesId) {
        MessageVo message = new MessageVo();
        message.setId(id);
        if (seriesId != null) {
            MessageSeriesVo series = new MessageSeriesVo();
            series.setSeriesId(seriesId);
            message.setMessageSeries(series);
        }
        return message;
    }

    private static Domain domain(String domainId, String timeZone, String... seriesIds) {
        Domain domain = new Domain();
        domain.setDomainId(domainId);
        domain.setTimeZone(timeZone);
        List<MessageSeries> series = new ArrayList<>();
        for (String seriesId : seriesIds) {
            MessageSeries messageSeries = new MessageSeries();
            messageSeries.setSeriesId(seriesId);
            series.add(messageSeries);
        }
        domain.setMessageSeries(series);
        return domain;
    }

    private void searchReturns(String description, MessageVo... messages) throws Exception {
        PagedSearchResultVo<MessageVo> result = new PagedSearchResultVo<>();
        result.setData(new ArrayList<>(List.of(messages)));
        result.setDescription(description);
        when(messageSearchRestService.searchMessages(any(MessageSearchParams.class))).thenReturn(result);
    }

    /** Runs the streamed body, which is where the template is rendered */
    private static void render(ResponseEntity<StreamingResponseBody> response) throws Exception {
        response.getBody().writeTo(new ByteArrayOutputStream());
    }

    private static String contentType(ResponseEntity<?> response) {
        return String.valueOf(response.getHeaders().getContentType());
    }

    // ------------------------------------------------------------------ one message

    @Test
    void anUnknownMessageIsNotFoundAndNothingIsRendered() throws Exception {
        when(messageRestService.getMessage("no-such-message", null)).thenReturn(null);

        NiordWebException e = assertThrows(NiordWebException.class, () ->
                service.generatePdfForMessage("no-such-message", "da", request(Map.of())));

        assertEquals(404, e.getStatusCode().value());
        verify(fmReportService, never()).getReport(any());
        verify(builder, never()).templatePath(anyString());
    }

    @Test
    void anAnonymousCallerGetsThePdfWhateverTheQuerySays() throws Exception {
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        ResponseEntity<StreamingResponseBody> response =
                service.generatePdfForMessage("m1", "da", request(hostileQuery()));
        render(response);

        assertEquals("application/pdf", contentType(response));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").startsWith("attachment"));
        // Only the debug HTML is sandboxed: a browser's PDF viewer does not open a sandboxed document
        assertNull(response.getHeaders().getFirst("Content-Security-Policy"));
        verify(builder).process(eq(ProcessFormat.PDF), any());
        verify(builder, never()).process(eq(ProcessFormat.TEXT), any());
        // The param: values are handed over as the last data map, and it is empty
        verify(builder, never()).data(argThat((Map<String, Object> data) -> data.containsKey("searchCriteria")));
        verify(builder, never()).data(argThat((Map<String, Object> data) -> data.containsKey("frontPage")));
        // The page layout fell back to the default instead of carrying markup into the style sheet
        verify(builder).data("pageSize", "A4");
    }

    @Test
    void aSignedInCallerStillGetsTheDebugHtmlAndTheTemplateValues() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        Map<String, String[]> query = new HashMap<>();
        query.put("debug", new String[] { "true" });
        query.put("param:edition", new String[] { "2" });

        ResponseEntity<StreamingResponseBody> response = service.generatePdfForMessage("m1", "da", request(query));
        render(response);

        assertEquals("text/html;charset=UTF-8", contentType(response));
        // Whatever markup a param: value carried runs no script on this origin
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        verify(builder).process(eq(ProcessFormat.TEXT), any());
        verify(builder).data(Map.of("edition", "2"));
    }

    @Test
    void aSignedInCallersTemplateValuesAreTextAndCannotReplaceWhatTheEndpointSets() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        Map<String, String[]> query = new HashMap<>();
        query.put("param:NtM-ref", new String[] { "<img src=\"http://example.com/x.png\">" });
        query.put("param:searchCriteria", new String[] { "<h1>AREA CLOSED</h1>" });
        query.put("param:pageSize", new String[] { "A4; background: url(http://example.com/x.png)" });
        query.put("param:pageOrientation", new String[] { "landscape" });
        query.put("param:frontPage", new String[] { "true" });
        query.put("param:messages", new String[] { "x" });

        render(service.generatePdfForMessage("m1", "da", request(query)));

        // The report's own parameters remain, escaped; the endpoint's names are gone
        verify(builder).data(Map.of("NtM-ref", "&lt;img src=&quot;http://example.com/x.png&quot;&gt;"));
        // The checked page layout is what the template gets
        verify(builder).data("pageSize", "A4");
        verify(builder).data("pageOrientation", "portrait");
    }

    @Test
    void aSignedInListKeepsTheEndpointsOwnCriteriaAndLayout() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        searchReturns("Statuses: [PUBLISHED]");
        Map<String, String[]> query = new HashMap<>();
        query.put("param:searchCriteria", new String[] { "<h1>AREA CLOSED</h1>" });
        query.put("param:pageSize", new String[] { "A4; background: url(http://example.com/x.png)" });
        query.put("param:separatePageIds", new String[] { "x" });
        query.put("param:week", new String[] { "39" });

        render(service.generatePdfForSearch(request(query)));

        verify(builder).data("searchCriteria", "Statuses: [PUBLISHED]");
        verify(builder).data("pageSize", "A4");
        verify(builder).data(Map.of("week", "39"));
        for (String name : MessageReportRestService.ENDPOINT_TEMPLATE_VALUES) {
            verify(builder, never()).data(argThat((Map<String, Object> data) -> data.containsKey(name)));
        }
    }

    @Test
    void anAnonymousReaderGetsTheZoneOfTheDomainTheMessageComesFrom() throws Exception {
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "gl-nw"));
        when(domainService.getDomains()).thenReturn(List.of(
                domain("niord-nw", "Europe/Copenhagen", "dma-nw"),
                domain("niord-gl-nw", "America/Nuuk", "gl-nw")));

        render(service.generatePdfForMessage("m1", "kl", request(Map.of())));

        verify(builder).timeZone("America/Nuuk");
    }

    @Test
    void theDetailReportsAreTheBuiltInsTheDeploymentHas() {
        // The built-in reports are data since C4.1: nothing creates a missing one, and a database that is not
        // bootstrapped yet has none, which the administration app must still open on
        FmReport standard = new FmReport();
        standard.setReportId(FmReportService.STANDARD_REPORT_ID);
        when(fmReportService.findByReportId(FmReportService.STANDARD_REPORT_ID)).thenReturn(standard);

        assertEquals(List.of(FmReportService.STANDARD_REPORT_ID),
                service.getDetailReports().stream().map(r -> r.getReportId()).toList());

        when(fmReportService.findByReportId(FmReportService.STANDARD_REPORT_ID)).thenReturn(null);
        assertEquals(List.of(), service.getDetailReports());
        verify(fmReportService, never()).getStandardReport();
        verify(fmReportService, never()).getDraftReport();
    }

    @Test
    void anAnonymousMessageThatNamesNoReportGetsTheStandardReport() throws Exception {
        // What the public site's reader gets: its address names the language alone
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        render(service.generatePdfForMessage("m1", "da", request(Map.of("lang", new String[] { "da" }))));

        verify(fmReportService).getStandardReport();
        verify(fmReportService, never()).getReport(any());
        verify(builder).templatePath("/templates/messages/message-list-pdf.ftl");
        verify(builder).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void anAnonymousMessageThatNamesTheStandardReportOrAnEmptyOneGetsTheSame() throws Exception {
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        render(service.generatePdfForMessage("m1", "da", request(Map.of("report", new String[] { "standard" }))));
        render(service.generatePdfForMessage("m1", "da", request(Map.of("report", new String[] { "" }))));

        verify(fmReportService, times(2)).getStandardReport();
        verify(fmReportService, never()).getReport(any());
    }

    @Test
    void anAnonymousMessageThatNamesAnotherReportIsRefusedBeforeTheMessageIsLookedUp() throws Exception {
        for (String reportId : List.of("draft", "public", "nm-report", "Standard", "standards")) {
            Map<String, String[]> query = new HashMap<>();
            query.put("lang", new String[] { "da" });
            query.put("report", new String[] { reportId });

            NiordWebException e = assertThrows(NiordWebException.class, () ->
                    service.generatePdfForMessage("m1", "da", request(query)), reportId);

            assertEquals(403, e.getStatusCode().value(), reportId);
        }
        verify(messageRestService, never()).getMessage(anyString(), any());
        verify(fmReportService, never()).getReport(any());
        verify(fmReportService, never()).getStandardReport();
        verify(builder, never()).templatePath(anyString());
    }

    @Test
    void aSignedInMessageStillGetsTheReportItNames() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        render(service.generatePdfForMessage("m1", "da", request(Map.of("report", new String[] { "draft" }))));

        verify(fmReportService).getReport("draft");
        verify(fmReportService, never()).getStandardReport();
    }

    @Test
    void aSignedInMessageThatNamesNoReportIsStillResolvedByName() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        render(service.generatePdfForMessage("m1", "da", request(Map.of())));

        verify(fmReportService).getReport(null);
        verify(fmReportService, never()).getStandardReport();
    }

    // ------------------------------------------------------------------ the list

    @Test
    void anAnonymousListGetsThePdfAndItsCriteriaStayText() throws Exception {
        searchReturns("Query: '<h1>AREA CLOSED</h1>'", message("m1", "dma-nw"));

        ResponseEntity<StreamingResponseBody> response = service.generatePdfForSearch(request(hostileQuery()));
        render(response);

        assertEquals("application/pdf", contentType(response));
        verify(builder).process(eq(ProcessFormat.PDF), any());
        verify(builder).data("searchCriteria", "Query: &#39;&lt;h1&gt;AREA CLOSED&lt;/h1&gt;&#39;");
        verify(builder, never()).data(argThat((Map<String, Object> data) -> data.containsKey("searchCriteria")));
    }

    @Test
    void theCriteriaKeepTheirLettersOnlyTheMarkupCharactersAreEscaped() throws Exception {
        searchReturns("Område: Øresund & Bælt", message("m1", "dma-nw"));

        render(service.generatePdfForSearch(request(Map.of())));

        verify(builder).data("searchCriteria", "Område: Øresund &amp; Bælt");
    }

    @Test
    void aSignedInCallerStillGetsTheDebugHtmlOfTheList() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        searchReturns("Statuses: [PUBLISHED]");

        ResponseEntity<StreamingResponseBody> response = service.generatePdfForSearch(request(hostileQuery()));
        render(response);

        assertEquals("text/html;charset=UTF-8", contentType(response));
        // Whatever markup a param: value carried runs no script on this origin
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        verify(builder).process(eq(ProcessFormat.TEXT), any());
    }

    /**
     * A list whose search was answered while the free-text index was being filled (LUCENE-7) is still printed, with
     * the line that says so at the top of the document. FAILS without it: the builder is handed no line.
     */
    @Test
    void aListAnsweredFromAnIncompleteIndexIsPrintedWithTheLineThatSaysSo() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        PagedSearchResultVo<MessageVo> result = new PagedSearchResultVo<>();
        result.setData(new ArrayList<>(List.of(message("m1", "dma-nw"))));
        result.setIndexIncomplete(true);
        when(messageSearchRestService.searchMessages(any(MessageSearchParams.class))).thenReturn(result);
        when(indexIncompleteNotice.forResult(result, "en")).thenReturn("Messages may be missing.");
        Map<String, String[]> query = new HashMap<>();
        query.put("lang", new String[] { "en" });
        query.put("query", new String[] { "wreck" });
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage("en")).thenReturn("en");
        service = new MessageReportRestService(app, domainService, userService, messageService, messageRestService,
                messageSearchRestService, fmReportService, templateService, indexIncompleteNotice, 3);

        ResponseEntity<StreamingResponseBody> response = service.generatePdfForSearch(request(query));
        render(response);

        assertEquals("application/pdf", contentType(response));
        verify(builder).topNotice("Messages may be missing.");
        verify(builder).process(eq(ProcessFormat.PDF), any());
    }

    /** A list from a complete index gets no line */
    @Test
    void aListAnsweredFromACompleteIndexGetsNoLine() throws Exception {
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));

        render(service.generatePdfForSearch(request(Map.of())));

        verify(builder).topNotice(null);
    }

    @Test
    void anAnonymousListThatNamesNoReportGetsThePublicReport() throws Exception {
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));
        Map<String, String[]> query = new HashMap<>();
        query.put("lang", new String[] { "da" });
        query.put("mainType", new String[] { "NW" });

        render(service.generatePdfForSearch(request(query)));

        verify(fmReportService).getPublicReport();
        verify(fmReportService, never()).getReport(any());
        verify(builder).data(Map.of("frontPage", false));
    }

    @Test
    void anAnonymousListWithAnEmptyReportNameGetsThePublicReport() throws Exception {
        searchReturns("Statuses: [PUBLISHED]");
        Map<String, String[]> query = new HashMap<>();
        query.put("report", new String[] { "" });

        render(service.generatePdfForSearch(request(query)));

        verify(fmReportService).getPublicReport();
        verify(fmReportService, never()).getReport(any());
    }

    @Test
    void anAnonymousListThatNamesThePublicReportGetsTheOneTheBackendDefines() throws Exception {
        // Not the lookup by name, which would answer a deleted public report with the standard one
        searchReturns("Statuses: [PUBLISHED]");
        Map<String, String[]> query = new HashMap<>();
        query.put("report", new String[] { "public" });

        render(service.generatePdfForSearch(request(query)));

        verify(fmReportService).getPublicReport();
        verify(fmReportService, never()).getReport(any());
        verify(builder).data(Map.of("frontPage", false));
    }

    @Test
    void anAnonymousListThatNamesAnotherReportIsRefusedBeforeAnythingIsSearched() throws Exception {
        for (String reportId : List.of("standard", "draft", "nm-report", "Public", "publicity")) {
            Map<String, String[]> query = new HashMap<>();
            query.put("lang", new String[] { "da" });
            query.put("report", new String[] { reportId });

            NiordWebException e = assertThrows(NiordWebException.class, () ->
                    service.generatePdfForSearch(request(query)), reportId);

            assertEquals(403, e.getStatusCode().value(), reportId);
        }
        verify(messageSearchRestService, never()).searchMessages(any());
        verify(fmReportService, never()).getReport(any());
        verify(fmReportService, never()).getPublicReport();
        verify(builder, never()).templatePath(anyString());
    }

    @Test
    void aSignedInListThatNamesNoReportStillGetsTheStandardReport() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        searchReturns("Statuses: [PUBLISHED]");
        Map<String, String[]> query = new HashMap<>();
        query.put("lang", new String[] { "da" });

        render(service.generatePdfForSearch(request(query)));

        verify(fmReportService).getReport(null);
        verify(fmReportService, never()).getPublicReport();
        verify(builder, never()).data(Map.of("frontPage", false));
    }

    @Test
    void aSignedInListStillGetsTheReportItNames() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        searchReturns("Statuses: [PUBLISHED]");
        Map<String, String[]> query = new HashMap<>();
        query.put("report", new String[] { "nm-report" });

        render(service.generatePdfForSearch(request(query)));

        verify(fmReportService).getReport("nm-report");
        verify(fmReportService, never()).getPublicReport();
    }

    @Test
    void anEmptyListIsDatedInTheZoneOfTheRequestedSeries() throws Exception {
        searchReturns("Statuses: [PUBLISHED]");
        when(domainService.getDomains()).thenReturn(List.of(
                domain("niord-nw", "Europe/Copenhagen", "dma-nw"),
                domain("niord-gl-nw", "America/Nuuk", "gl-nw")));
        Map<String, String[]> query = new HashMap<>();
        query.put("messageSeries", new String[] { "gl-nw" });

        render(service.generatePdfForSearch(request(query)));

        verify(builder).timeZone("America/Nuuk");
    }

    // ------------------------------------------------------------------ the anonymous limit

    /**
     * An anonymous list PDF being made on another thread: the template engine holds its first render
     * until the test closes this, so the render keeps its permit meanwhile.
     */
    private final class HeldRender implements AutoCloseable {

        private final CountDownLatch rendering = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        HeldRender(MessageReportRestService limited) throws Exception {
            AtomicInteger renders = new AtomicInteger();
            doAnswer(invocation -> {
                if (renders.getAndIncrement() == 0) {
                    rendering.countDown();
                    assertTrue(finish.await(60, TimeUnit.SECONDS), "the held render was never let go");
                }
                return null;
            }).when(builder).process(any(), any());

            thread = new Thread(() -> {
                try {
                    limited.generatePdfForSearch(request(Map.of()));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.start();
            // A guard against a hung test, not a measurement: the render starts at once
            assertTrue(rendering.await(60, TimeUnit.SECONDS), "the held render never started");
        }

        @Override
        public void close() throws Exception {
            finish.countDown();
            thread.join();
            assertNull(failure.get(), "the held render failed");
        }
    }

    @Test
    void anAnonymousCallerFindingEveryPermitTakenIsTurnedAwayBeforeAnythingIsLookedUp() throws Exception {
        MessageReportRestService limited = withPermits(1);
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));
        when(messageRestService.getMessage("m2", null)).thenReturn(message("m2", "dma-nw"));

        try (HeldRender ignored = new HeldRender(limited)) {
            NiordWebException list = assertThrows(NiordWebException.class,
                    () -> limited.generatePdfForSearch(request(Map.of())));
            NiordWebException one = assertThrows(NiordWebException.class,
                    () -> limited.generatePdfForMessage("m2", "da", request(Map.of())));

            assertEquals(503, list.getStatusCode().value());
            assertEquals(503, one.getStatusCode().value());
            // Only the held render searched and rendered
            verify(messageSearchRestService, times(1)).searchMessages(any());
            verify(messageRestService, never()).getMessage(anyString(), any());
            verify(builder, times(1)).process(any(), any());
        }
    }

    @Test
    void aPermitIsBackOnceTheRenderHoldingItIsDone() throws Exception {
        MessageReportRestService limited = withPermits(1);
        searchReturns("Statuses: [PUBLISHED]");

        try (HeldRender ignored = new HeldRender(limited)) {
            assertThrows(NiordWebException.class, () -> limited.generatePdfForSearch(request(Map.of())));
        }

        render(limited.generatePdfForSearch(request(Map.of())));
        render(limited.generatePdfForSearch(request(Map.of())));
        verify(builder, times(3)).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void aPermitIsBackAfterAFailedRender() throws Exception {
        MessageReportRestService limited = withPermits(1);
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        doThrow(new IOException("the renderer failed")).doNothing().when(builder).process(any(), any());

        NiordWebException e = assertThrows(NiordWebException.class,
                () -> limited.generatePdfForMessage("m1", "da", request(Map.of())));
        assertEquals(500, e.getStatusCode().value());

        render(limited.generatePdfForMessage("m1", "da", request(Map.of())));
        verify(builder, times(2)).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void aPermitIsBackAfterAnUnknownMessage() throws Exception {
        MessageReportRestService limited = withPermits(1);
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        NiordWebException e = assertThrows(NiordWebException.class,
                () -> limited.generatePdfForMessage("no-such-message", "da", request(Map.of())));
        assertEquals(404, e.getStatusCode().value());

        render(limited.generatePdfForMessage("m1", "da", request(Map.of())));
        verify(builder).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void aRefusedReportLeavesThePermitFree() throws Exception {
        MessageReportRestService limited = withPermits(1);
        searchReturns("Statuses: [PUBLISHED]");
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        Map<String, String[]> draft = Map.of("report", new String[] { "draft" });

        assertEquals(403, assertThrows(NiordWebException.class,
                () -> limited.generatePdfForSearch(request(draft))).getStatusCode().value());
        assertEquals(403, assertThrows(NiordWebException.class,
                () -> limited.generatePdfForMessage("m1", "da", request(draft))).getStatusCode().value());

        render(limited.generatePdfForSearch(request(Map.of())));
        render(limited.generatePdfForMessage("m1", "da", request(Map.of())));
        verify(builder, times(2)).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void aKnownCallerIsNotLimited() throws Exception {
        MessageReportRestService limited = withPermits(1);
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        try (HeldRender ignored = new HeldRender(limited)) {
            // The only permit is the held anonymous render's; a signed-in caller still gets its PDFs
            when(userService.currentUser()).thenReturn(new User());
            render(limited.generatePdfForSearch(request(Map.of())));
            render(limited.generatePdfForMessage("m1", "da", request(Map.of())));
        }
        verify(builder, times(3)).process(eq(ProcessFormat.PDF), any());
    }

    @Test
    void anAnonymousPdfIsMadeBeforeTheHandlerReturnsAndAKnownCallersWhenItIsStreamed() throws Exception {
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        doAnswer(invocation -> {
            invocation.<OutputStream>getArgument(1).write("%PDF".getBytes(StandardCharsets.US_ASCII));
            return null;
        }).when(builder).process(any(), any());

        // Made while the anonymous caller's permit is held; the body only writes the finished document
        ResponseEntity<StreamingResponseBody> anonymous = service.generatePdfForMessage("m1", "da", request(Map.of()));
        verify(builder, times(1)).process(eq(ProcessFormat.PDF), any());
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        anonymous.getBody().writeTo(written);
        assertEquals("%PDF", written.toString(StandardCharsets.US_ASCII));
        verify(builder, times(1)).process(any(), any());

        // A known caller's document is still made as the body is streamed
        when(userService.currentUser()).thenReturn(new User());
        ResponseEntity<StreamingResponseBody> known = service.generatePdfForMessage("m1", "da", request(Map.of()));
        verify(builder, times(1)).process(any(), any());
        render(known);
        verify(builder, times(2)).process(any(), any());
    }

    @Test
    void anAnonymousCallerGetsNoMapThumbnails() throws Exception {
        // Each thumbnail is a map image the renderer fetches from the backend itself, for every message
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));
        Map<String, String[]> query = Map.of("mapThumbnails", new String[] { "true" });

        render(service.generatePdfForSearch(request(query)));
        render(service.generatePdfForMessage("m1", "da", request(query)));

        verify(builder, times(2)).data("mapThumbnails", false);
        verify(builder, never()).data("mapThumbnails", true);
    }

    @Test
    void aSignedInCallerStillGetsTheMapThumbnailsItAsksFor() throws Exception {
        when(userService.currentUser()).thenReturn(new User());
        searchReturns("Statuses: [PUBLISHED]", message("m1", "dma-nw"));

        render(service.generatePdfForSearch(request(Map.of("mapThumbnails", new String[] { "true" }))));

        verify(builder).data("mapThumbnails", true);
    }

    @Test
    void theLimitAllowsAtLeastOnePdf() {
        assertThrows(IllegalArgumentException.class, () -> withPermits(0));
        assertThrows(IllegalArgumentException.class, () -> withPermits(-1));
    }

    // ------------------------------------------------------------------ the zone

    @Test
    void aCallerWithADomainGetsThatDomainsZone() {
        when(domainService.currentDomain()).thenReturn(domain("niord-gl-nw", "America/Nuuk"));

        assertEquals("America/Nuuk", service.reportTimeZone(List.of(message("m1", "dma-nw")), Set.of()));
        verify(domainService, never()).getDomains();
    }

    @Test
    void theFirstDomainInSortOrderHoldingASeriesDecides() {
        when(domainService.getDomains()).thenReturn(List.of(
                domain("niord-nw", "Europe/Copenhagen", "dma-nw"),
                domain("niord-legacy-nw", "UTC", "dma-nw", "gl-nw"),
                domain("niord-gl-nw", "America/Nuuk", "gl-nw")));

        assertEquals("Europe/Copenhagen",
                service.reportTimeZone(List.of(message("m1", "gl-nw"), message("m2", "dma-nw")), Set.of()));
        assertEquals("UTC", service.reportTimeZone(List.of(message("m1", "gl-nw")), Set.of()));
    }

    @Test
    void aDomainWithoutAZoneIsPassedOver() {
        when(domainService.currentDomain()).thenReturn(domain("niord-nw", " "));
        when(domainService.getDomains()).thenReturn(List.of(
                domain("niord-nw", null, "dma-nw"),
                domain("niord-legacy-nw", "Europe/Copenhagen", "dma-nw")));

        assertEquals("Europe/Copenhagen", service.reportTimeZone(List.of(message("m1", "dma-nw")), Set.of()));
    }

    @Test
    void withoutASeriesTheFirstPublishedDomainWithAZoneDecides() {
        when(domainService.getDomains()).thenReturn(List.of(domain("niord-nw", "Europe/Copenhagen", "dma-nw")));
        when(domainService.getPublishedDomains()).thenReturn(List.of(
                domain("niord-fe", null), domain("niord-gl-nw", "America/Nuuk")));

        assertEquals("America/Nuuk", service.reportTimeZone(List.of(message("m1", null)), Set.of("other")));
    }

    @Test
    void noZoneAnywhereLeavesTheRendererItsOwn() {
        assertNull(service.reportTimeZone(List.of(message("m1", "dma-nw")), null));
    }

    @Test
    void theZoneIsResolvedBeforeTheBodyIsStreamed() throws Exception {
        // The body renders on another thread, where the request's domain is gone: the zone must be
        // settled while the handler still runs, not when the template asks for it
        when(domainService.currentDomain()).thenReturn(domain("niord-nw", "Europe/Copenhagen"));
        when(messageRestService.getMessage("m1", null)).thenReturn(message("m1", "dma-nw"));

        ResponseEntity<StreamingResponseBody> response = service.generatePdfForMessage("m1", "da", request(Map.of()));
        when(domainService.currentDomain()).thenReturn(null);
        render(response);

        verify(builder).timeZone("Europe/Copenhagen");
    }
}
