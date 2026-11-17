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

package org.niord.web;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessagePrintParams;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageService;
import org.niord.core.report.FmReport;
import org.niord.core.report.FmReportService;
import org.niord.core.report.vo.FmReportVo;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.FmTemplateService.ProcessFormat;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.DataFilter;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.MessageVo;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.HtmlUtils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.niord.core.web.ServedContentHeaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REST interface for generating PDF message reports.
 */
@RestController
@RequestMapping("/rest/message-reports")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class MessageReportRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageReportRestService.class);

    private final NiordApp app;

    private final DomainService domainService;

    private final UserService userService;

    private final MessageService messageService;

    private final MessageRestService messageRestService;

    private final MessageSearchRestService messageSearchRestService;

    private final FmReportService fmReportService;

    private final FmTemplateService templateService;

    /**
     * The PDFs rendered for anonymous callers at once, see {@link #acquireAnonymousPdfPermit(boolean)}.
     * Sized by {@code niord.reports.anonymous-pdf-concurrency}.
     */
    private final Semaphore anonymousPdfPermits;

    MessageReportRestService(NiordApp app, DomainService domainService, UserService userService, MessageService messageService, MessageRestService messageRestService, MessageSearchRestService messageSearchRestService, FmReportService fmReportService, FmTemplateService templateService,
                             @Value("${niord.reports.anonymous-pdf-concurrency}") int anonymousPdfConcurrency) {
        this.app = app;
        this.domainService = domainService;
        this.userService = userService;
        this.messageService = messageService;
        this.messageRestService = messageRestService;
        this.messageSearchRestService = messageSearchRestService;
        this.fmReportService = fmReportService;
        this.templateService = templateService;
        if (anonymousPdfConcurrency < 1) {
            throw new IllegalArgumentException(
                    "niord.reports.anonymous-pdf-concurrency must be at least 1, was " + anonymousPdfConcurrency);
        }
        this.anonymousPdfPermits = new Semaphore(anonymousPdfConcurrency);
    }

    /***************************************/
    /** PDF Reports                       **/
    /***************************************/

    /**
     * Returns the reports available for printing message lists in the current domain
     * @return the reports available for printing message lists in the current domain
     */
    @GetMapping("/reports")
    public List<FmReportVo> getReports(
            @RequestParam(name = "expandParams", defaultValue = "true") boolean expandParams
    ) {
        return fmReportService.getReports().stream()
                .map(FmReport::toVo)
                .map(r -> expandParams ? fmReportService.expandReportParams(r) : r)
                .collect(Collectors.toList());
    }

    /**
     * Returns all reports
     * @return all reports
     */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<FmReportVo> getAllReports() {

        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.SYSADMIN)) {
            throw new NiordWebException(403);
        }

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).fields("domains");
        return fmReportService.getAllReports().stream()
                .map(r -> r.toVo(filter))
                .collect(Collectors.toList());
    }

    /**
     * Returns the reports available for printing message details: the standard and the draft report, those of
     * them the deployment has. A database that is not bootstrapped yet has neither, and the administration app
     * still opens.
     * @return the reports available for printing message details
     */
    @GetMapping("/detail-reports")
    public List<FmReportVo> getDetailReports() {
        return Stream.of(FmReportService.STANDARD_REPORT_ID, FmReportService.DRAFT_REPORT_ID)
                .map(fmReportService::findByReportId)
                .filter(Objects::nonNull)
                .map(FmReport::toVo)
                .collect(Collectors.toList());
    }

    /** Creates a new report */
    @PostMapping(value = {"/report", "/report/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public FmReportVo createReport(@RequestBody FmReportVo report) throws Exception {
        if (StringUtils.isBlank(report.getReportId())) {
            throw new IllegalArgumentException("A report needs a report ID");
        }
        refuseIncompleteReport(report);
        log.info("Creating report " + report);
        return fmReportService.createReport(new FmReport(report))
                .toVo();
    }

    /**
     * Answers the report the request carries with its parameters expanded as {@link #getReports(boolean)} expands
     * them: {@code ${year}}, {@code ${week}} and their two-digit forms read today in the current domain's zone (see
     * {@link FmReportService#expandReportParams(FmReportVo)}). The report need not be saved: the Print reports page
     * tests the report as it stands in the editor, which may be a new one, one with changes not yet saved, or one of
     * a domain other than the current one, so the stored reports cannot be looked up for it. Nothing is stored.
     *
     * @param report the report as the editor holds it
     * @return the same report, its parameters expanded
     */
    @PostMapping(value = "/expand-params",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public FmReportVo expandReportParams(@RequestBody FmReportVo report) {
        return fmReportService.expandReportParams(report);
    }

    /**
     * Refuses a report without a name or a template path, which the NOT NULL columns would refuse at the flush with
     * a 500, and a properties or parameters field sent as null, which the copy cannot read
     */
    private static void refuseIncompleteReport(FmReportVo report) {
        if (StringUtils.isBlank(report.getName())) {
            throw new IllegalArgumentException("The report " + report.getReportId() + " needs a name");
        }
        if (StringUtils.isBlank(report.getTemplatePath())) {
            throw new IllegalArgumentException("The report " + report.getReportId() + " needs a template path");
        }
        if (report.getProperties() == null || report.getParams() == null) {
            throw new IllegalArgumentException("The properties and the parameters of the report "
                    + report.getReportId() + " may be empty but not null");
        }
    }

    /** Updates an existing report */
    @PutMapping(value = "/report/{reportId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public FmReportVo updateReport(@PathVariable("reportId") String reportId, @RequestBody FmReportVo report) throws Exception {
        if (!Objects.equals(reportId, report.getReportId())) {
            throw new NiordWebException(400);
        }

        if (fmReportService.findByReportId(reportId) == null) {
            throw new NiordWebException("Report " + reportId + " not found", 404);
        }
        refuseIncompleteReport(report);

        log.info("Updating report " + report);
        return fmReportService.updateReport(new FmReport(report))
                .toVo();
    }

    /** Deletes an existing report */
    @DeleteMapping("/report/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void deleteReport(@PathVariable("reportId") String reportId) throws Exception {
        log.info("Deleting report " + reportId);
        fmReportService.deleteReport(reportId);
    }

    /**
     * Imports an uploaded reports json file
     *
     * @param request the multi-part form data input request
     * @return a status
     */
    @PostMapping(value = "/upload-reports",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importReports(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "report-import");
    }

    /**
     * Generates a PDF for the message with the given message id, which may be either a UID,
     * or a short ID of a message.
     *
     * If the debug flag is set to true, the HTML that is used for the PDF is returned directly -
     * to a caller the backend knows only, see {@link #printParams(HttpServletRequest, boolean)}. Which
     * report renders the message is decided by {@link #messageReport(String, boolean)}, before the
     * message is looked up.
     *
     * @param messageId the message ID
     * @param language the language of the returned data
     * @return the message as a PDF
     */
    @GetMapping("/message/{messageId}.pdf")
    public ResponseEntity<StreamingResponseBody> generatePdfForMessage(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "lang", required = false) String language,
            HttpServletRequest request) throws Exception {

        boolean knownCaller = userService.currentUser() != null;
        MessagePrintParams printParams = printParams(request, knownCaller);
        FmReport report = messageReport(printParams.getReport(), knownCaller);

        boolean permit = acquireAnonymousPdfPermit(knownCaller);
        try {
            // The "draft" report display all language variants, so, sort instead of filter by language
            MessageVo message = messageRestService.getMessage(messageId, null);
            if (message == null) {
                throw new NiordWebException("Message " + messageId + " not found", 404);
            }
            // The templates print in one language, so a request without a language (or with one the
            // system does not have) gets the default language
            String lang = app.getLanguage(language);
            message.sortDescs(lang);

            String timeZone = reportTimeZone(Collections.singleton(message), Collections.emptySet());

            try {
                ProcessFormat format = printParams.getDebug() ? ProcessFormat.TEXT : ProcessFormat.PDF;

                StreamingResponseBody stream = os -> {
                    try {
                        templateService.newFmTemplateBuilder()
                                .templatePath(report.getTemplatePath())
                                .data("executionMode", app.getExecutionMode())
                                .data("messages", Collections.singleton(message))
                                .data("areaHeadings", false)
                                .data("pageSize", printParams.getPageSize())
                                .data("pageOrientation", printParams.getPageOrientation())
                                .data("mapThumbnails", printParams.getMapThumbnails())
                                .data("frontPage", false)
                                .timeZone(timeZone)
                                .data(report.getProperties())   // Let report override settings
                                .data(printParams.getParams())  // Custom user-defined params
                                .dictionaryNames("web", "message", "pdf")
                                .language(lang)
                                .process(format, os);
                    } catch (Exception e) {
                        throw new NiordWebException("Error generating PDF for message " + messageId, e);
                    }
                };
                if (permit) {
                    stream = renderNow(stream);
                }

                return printParams.getDebug()
                        ? debugHtml(stream)
                        : ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/pdf"))
                            .header("Content-Disposition", printParams.getFileNameHeader("message-" + messageId))
                            .body(stream);

            } catch (Exception e) {
                log.error("Error generating PDF for message " + messageId, e);
                throw e;
            }
        } finally {
            if (permit) {
                anonymousPdfPermits.release();
            }
        }
    }

    /**
     * Generates a PDF for the message search result.
     *
     * If the debug flag is set to true, the HTML that is used for the PDF is returned directly -
     * to a caller the backend knows only, see {@link #printParams(HttpServletRequest, boolean)}. Which
     * report renders the list is decided by {@link #listReport(String, boolean)}.
     */
    @GetMapping("/report.pdf")
    public ResponseEntity<StreamingResponseBody> generatePdfForSearch(HttpServletRequest request) throws Exception {

        boolean knownCaller = userService.currentUser() != null;
        MessagePrintParams printParams = printParams(request, knownCaller);
        FmReport report = listReport(printParams.getReport(), knownCaller);

        boolean permit = acquireAnonymousPdfPermit(knownCaller);
        try {
            // Perform a search for at most 1000 messages
            MessageSearchParams params = MessageSearchParams.instantiate(domainService.currentDomain(), request);
            params.maxSize(1000).page(0);

            // We prefer to get all language variants and then sort the result. The templates print in one
            // language, so a request without a language (or with one the system does not have) gets the
            // default language
            String language = app.getLanguage(params.getLanguage());
            params.language(null);
            PagedSearchResultVo<MessageVo> result = messageSearchRestService.searchMessages(params);
            result.getData().forEach(m -> m.sort(language));

            // Get the UIDs of the messages that should start on a new page
            Set<String> separatePageIds = messageService.getSeparatePageUids(
                    result.getData().stream().map(MessageVo::getId).collect(Collectors.toSet()));

            String timeZone = reportTimeZone(result.getData(), params.getSeriesIds());

            // The criteria describe the request, free text included, and the templates print them as
            // they are handed over; escaped here, they stay text in every copy of a template, the ones
            // a deployment keeps in its database among them. Only the markup characters are escaped.
            String searchCriteria = result.getDescription() == null ? null
                    : HtmlUtils.htmlEscape(result.getDescription(), StandardCharsets.UTF_8.name());

            try {
                ProcessFormat format = printParams.getDebug() ? ProcessFormat.TEXT : ProcessFormat.PDF;

                StreamingResponseBody stream = os -> {
                    try {
                        templateService.newFmTemplateBuilder()
                                .templatePath(report.getTemplatePath())
                                .data("executionMode", app.getExecutionMode())
                                .data("messages", result.getData())
                                .data("areaHeadings", params.sortByArea())
                                .data("searchCriteria", searchCriteria)
                                .data("pageSize", printParams.getPageSize())
                                .data("pageOrientation", printParams.getPageOrientation())
                                .data("mapThumbnails", printParams.getMapThumbnails())
                                .data("separatePageIds", separatePageIds)
                                .data("frontPage", true)
                                .timeZone(timeZone)
                                .data(report.getProperties())  // Let report override settings
                                .data(printParams.getParams()) // Custom user-defined params
                                .dictionaryNames("web", "message", "pdf")
                                .language(language)
                                .process(format, os);
                    } catch (Exception e) {
                        throw new NiordWebException("Error generating PDF for messages", e);
                    }
                };
                if (permit) {
                    stream = renderNow(stream);
                }

                return printParams.getDebug()
                        ? debugHtml(stream)
                        : ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/pdf"))
                            .header("Content-Disposition", printParams.getFileNameHeader("messages"))
                            .body(stream);

            } catch (Exception e) {
                log.error("Error generating PDF for messages", e);
                throw e;
            }
        } finally {
            if (permit) {
                anonymousPdfPermits.release();
            }
        }
    }

    /**
     * Takes one of the permits for an anonymous PDF, or refuses the request.
     *
     * Both PDF endpoints answer anonymously, and a PDF is cheap to ask for and costly to answer: a
     * search of up to 1000 messages, holding a database connection, and a layout of all of them. So
     * only {@code niord.reports.anonymous-pdf-concurrency} anonymous PDFs are made at once, and a caller
     * who finds every permit taken is answered 503 straight away, before anything is looked up or
     * rendered. A caller the backend knows - signed in, or presenting a ticket, which is how the
     * administration application and the publication release come - takes no permit.
     *
     * @param knownCaller whether the caller is one the backend knows
     * @return whether a permit was taken; the caller releases it in a {@code finally} block, and renders
     *         the document with {@link #renderNow(StreamingResponseBody)} while it holds it
     * @throws NiordWebException 503 when an anonymous caller finds no permit free
     */
    private boolean acquireAnonymousPdfPermit(boolean knownCaller) {
        if (knownCaller) {
            return false;
        }
        if (!anonymousPdfPermits.tryAcquire()) {
            throw new NiordWebException("Too many documents are being generated, please try again shortly", 503);
        }
        return true;
    }

    /**
     * Renders the document now, on the request thread, and answers the finished bytes.
     *
     * This is how an anonymous PDF is made while its permit is held, so the permit is back when the
     * handler returns, whatever becomes of the response. Streamed later, the body would run on the
     * shared task executor and would have to give the permit back itself; and an asynchronous request
     * that times out cancels a body that has not started yet, which would keep the permit for good.
     *
     * @param stream the body that renders the document
     * @return a body that writes the rendered document
     */
    private static StreamingResponseBody renderNow(StreamingResponseBody stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream.writeTo(out);
        byte[] document = out.toByteArray();
        return os -> os.write(document);
    }

    /**
     * The debug answer: the HTML the PDF is rendered from, served sandboxed.
     *
     * The HTML carries the free-form {@code param:<name>} values, escaped (see
     * {@link #printParams(HttpServletRequest, boolean)}), and a caller who may set them can hand a link
     * carrying their own ticket to someone else. The templates, a copy kept in a database among them,
     * are not all the backend's own, so the page is sandboxed as well: an origin of its own that runs no
     * script and submits no form. The PDF renderer runs no script either, so the page shows what the PDF
     * would.
     */
    static ResponseEntity<StreamingResponseBody> debugHtml(StreamingResponseBody stream) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header(ServedContentHeaders.CONTENT_SECURITY_POLICY, ServedContentHeaders.SANDBOX)
                .body(stream);
    }

    /**
     * The template values the endpoints set themselves. A {@code param:<name>} value is handed to the
     * template after them, so one of these names would replace what the endpoint decided: the checked
     * page layout among them, which is written into a style sheet where escaping would not keep a value
     * from being read as more than a keyword.
     */
    static final Set<String> ENDPOINT_TEMPLATE_VALUES = Set.of(
            "executionMode", "messages", "areaHeadings", "searchCriteria", "pageSize", "pageOrientation",
            "mapThumbnails", "separatePageIds", "frontPage");

    /**
     * The print parameters of the request, as far as its caller may set them.
     *
     * Both PDF endpoints answer anonymously, and the public site sends its readers to them. A caller
     * the backend knows - signed in, or presenting a ticket issued to a signed-in user, which is how
     * the administration application and the publication release reach these endpoints - may set
     * every print parameter. Anyone else gets neither the debug switch nor the free-form
     * {@code param:<name>} values: the first would serve the HTML behind the PDF, and with it
     * whatever markup the query carried, as a page of this origin; the second would let any link
     * rewrite what a genuine-looking document says. Nor does anyone else get the map thumbnails, for
     * which the renderer would request one map image per message from the backend itself.
     *
     * The {@code param:<name>} values a known caller sets are text: {@link MessagePrintParams} escapes
     * them, and those named like a value the endpoint sets itself ({@link #ENDPOINT_TEMPLATE_VALUES})
     * are dropped, so the report's own parameters - a year, a week, an edition - are what remains.
     *
     * @param request the request
     * @param knownCaller whether the caller is one the backend knows: {@code userService.currentUser()} is set
     * @return the print parameters the caller may set
     */
    private MessagePrintParams printParams(HttpServletRequest request, boolean knownCaller) {
        MessagePrintParams printParams = MessagePrintParams.instantiate(request);
        if (!knownCaller) {
            return printParams.forAnonymousCaller();
        }
        printParams.getParams().keySet().removeAll(ENDPOINT_TEMPLATE_VALUES);
        return printParams;
    }

    /**
     * The report a message list is rendered with.
     *
     * A caller the backend knows - see {@link #printParams(HttpServletRequest, boolean)} - prints with the
     * report it names, and with the standard report when it names none. Anyone else gets the public report,
     * the one the backend defines for readers of the public site, and naming any other report is refused.
     * The public site's own container lets no other query through, but this endpoint also answers
     * anonymously wherever else the backend is reachable, the administration host among them, with no
     * such proxy in front. There, the standard report would open the list with the front page written
     * for the editors - the search criteria with their internal ids - and a named report would render
     * whichever report the editors keep for their own prints, whatever its domain.
     *
     * The public report is looked up as the backend defines it, not by name through
     * {@link FmReportService#getReport(String)}, which answers a missing report with the standard one.
     *
     * @param reportId the report the request names, or null
     * @param knownCaller whether the caller is one the backend knows
     * @return the report to render the list with
     * @throws NiordWebException 403 when an anonymous caller names a report other than the public one
     */
    FmReport listReport(String reportId, boolean knownCaller) {
        if (knownCaller) {
            return fmReportService.getReport(reportId);
        }
        if (StringUtils.isNotBlank(reportId) && !FmReportService.PUBLIC_REPORT_ID.equals(reportId)) {
            throw new NiordWebException(403);
        }
        return fmReportService.getPublicReport();
    }

    /**
     * The report one message is printed with.
     *
     * A caller the backend knows prints with the report it names, and with the standard report when it
     * names none. Anyone else gets the standard report - what the public site's reader gets, whose address
     * names no report - whether the request names it or names none, and naming any other report is refused.
     * The public site's container lets no report through on this path, but the endpoint also answers
     * anonymously wherever else the backend is reachable, the administration host among them, with no such
     * proxy in front. There, a named report would render the message in whichever report the editors keep
     * for their own prints, whatever its domain - the draft report among them, which prints every language
     * variant of the message. One message has no front page in any report, so the standard report is what
     * the public site prints it with, unlike a list.
     *
     * @param reportId the report the request names, or null
     * @param knownCaller whether the caller is one the backend knows
     * @return the report to render the message with
     * @throws NiordWebException 403 when an anonymous caller names a report other than the standard one
     */
    FmReport messageReport(String reportId, boolean knownCaller) {
        if (knownCaller) {
            return fmReportService.getReport(reportId);
        }
        if (StringUtils.isNotBlank(reportId) && !FmReportService.STANDARD_REPORT_ID.equals(reportId)) {
            throw new NiordWebException(403);
        }
        return fmReportService.getStandardReport();
    }

    /**
     * The time zone the dates of a PDF are printed in, or null when no domain names one.
     *
     * Resolved on the request thread, because the document of a known caller is rendered later, on another
     * thread, where the caller's domain is no longer known; left to the renderer, every PDF would be
     * dated in whatever zone the server runs in. A caller with a current domain gets that domain's
     * zone. A reader of the public site has none, and gets the zone of the domain the messages come
     * from: the first active domain, in sort order, holding the series of one of the messages or
     * one of the requested series - and failing that, the first published domain that names a zone.
     *
     * @param messages the messages the document prints
     * @param seriesIds the message series the request asked for
     * @return the time zone id, or null
     */
    String reportTimeZone(Collection<MessageVo> messages, Collection<String> seriesIds) {
        Domain current = domainService.currentDomain();
        if (current != null && StringUtils.isNotBlank(current.getTimeZone())) {
            return current.getTimeZone();
        }

        Set<String> series = new HashSet<>();
        if (seriesIds != null) {
            series.addAll(seriesIds);
        }
        messages.stream()
                .map(MessageVo::getMessageSeries)
                .filter(Objects::nonNull)
                .map(MessageSeriesVo::getSeriesId)
                .filter(Objects::nonNull)
                .forEach(series::add);

        return domainService.getDomains().stream()
                .filter(d -> StringUtils.isNotBlank(d.getTimeZone()))
                .filter(d -> series.stream().anyMatch(d::containsMessageSeries))
                .map(Domain::getTimeZone)
                .findFirst()
                .orElseGet(() -> domainService.getPublishedDomains().stream()
                        .map(Domain::getTimeZone)
                        .filter(StringUtils::isNotBlank)
                        .findFirst()
                        .orElse(null));
    }

}
