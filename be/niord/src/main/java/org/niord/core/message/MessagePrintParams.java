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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.publication.series.PageOrientation;
import org.niord.core.publication.series.PageSize;
import org.niord.core.util.WebUtils;
import org.niord.model.search.PagedSearchParamsVo;
import org.springframework.web.util.HtmlUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Defines the message print parameters
 */
@SuppressWarnings("unused")
public class MessagePrintParams extends PagedSearchParamsVo {

    // Print parameters
    String report;
    String pageSize = "A4";
    String pageOrientation = "portrait";
    Boolean mapThumbnails = Boolean.FALSE;
    String fileName;
    Boolean debug = Boolean.FALSE;
    Map<String, Object> params = new HashMap<>();

    /**
     * Returns a MessagePrintParams initialized with parameter values from a request using "default" parameter names
     * @param req the servlet request
     * @return the MessageSearchParams initialized with parameter values
     */
    public static MessagePrintParams instantiate(HttpServletRequest req) {
        MessagePrintParams params = new MessagePrintParams();
        params.report(req.getParameter("report"))
                .pageSize(layoutValue(req.getParameter("pageSize"), PageSize.class, "A4"))
                .pageOrientation(layoutValue(req.getParameter("pageOrientation"), PageOrientation.class, "portrait"))
                .mapThumbnails(checkNull(req.getParameter("mapThumbnails"), false, Boolean::valueOf))
                .fileName(checkNull(req.getParameter("fileName"), null, Function.identity()))
                .debug(checkNull(req.getParameter("debug"), false, Boolean::valueOf))
                .readReportParams(req);

        return params;
    }

    /**
     * Returns the page size or orientation a request asked for when it names one of the given keywords,
     * in any case, and the default otherwise. Both values are written into the style sheet the PDF is
     * rendered from, where anything but a known keyword would be markup rather than a layout.
     */
    private static <E extends Enum<E>> String layoutValue(String value, Class<E> keywords, String defaultValue) {
        if (StringUtils.isNotBlank(value)) {
            String name = value.trim().toUpperCase(Locale.ROOT);
            for (E keyword : keywords.getEnumConstants()) {
                if (keyword.name().equals(name)) {
                    return value.trim();
                }
            }
        }
        return defaultValue;
    }

    /**
     * Drops what only a caller the backend knows may set: the debug switch, which answers with the HTML
     * the PDF is rendered from instead of the PDF, the free-form {@code param:<name>} values, which
     * reach the template after everything else and can therefore replace what the document says it
     * lists, and the map thumbnails, for which the renderer fetches one map image per message from the
     * backend itself while the document is made. The report, the page layout and the file name stay.
     *
     * @return this instance
     */
    public MessagePrintParams forAnonymousCaller() {
        debug = Boolean.FALSE;
        mapThumbnails = Boolean.FALSE;
        params.clear();
        return this;
    }

    /**
     * Returns a valid PDF file name "Content-Disposition" header
     * @param defaultName the default name to use if no file name has been set
     * @return a valid PDF file name "Content-Disposition" header
     */
    public String getFileNameHeader(String defaultName) {
        String name = StringUtils.defaultIfBlank(fileName, defaultName);
        if (!name.toLowerCase().endsWith(".pdf")) {
            name += ".pdf";
        }

        return "attachment; filename=\"" + WebUtils.encodeURIComponent(name) + "\"";
    }

    /**
     * Returns a string representation of the print params
     * @return a string representation of the print params
     */
    @Override
    public String toString() {
        List<String> desc = new ArrayList<>();
        if (StringUtils.isNotBlank(report)) { desc.add(String.format("Report: %s", report)); }
        if (StringUtils.isNotBlank(pageSize)) { desc.add(String.format("Page size: %s", pageSize)); }
        if (StringUtils.isNotBlank(pageOrientation)) { desc.add(String.format("Page orientation: %s", pageOrientation)); }

        return String.join(", ", desc);
    }

    /*******************************************/
    /** Method chaining Getters and Setters   **/
    /***/

    public String getReport() {
        return report;
    }

    public MessagePrintParams report(String report) {
        this.report = report;
        return this;
    }

    public String getPageSize() {
        return pageSize;
    }

    public MessagePrintParams pageSize(String pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public String getPageOrientation() {
        return pageOrientation;
    }

    public MessagePrintParams pageOrientation(String pageOrientation) {
        this.pageOrientation = pageOrientation;
        return this;
    }

    public String getFileName() {
        return fileName;
    }

    public MessagePrintParams fileName(String fileName) {
        this.fileName = fileName;
        return this;
    }

    public Boolean getDebug() {
        return debug;
    }

    public MessagePrintParams debug(Boolean debug) {
        this.debug = debug;
        return this;
    }

    public Boolean getMapThumbnails() {
        return mapThumbnails;
    }

    public MessagePrintParams mapThumbnails(Boolean mapThumbnails) {
        this.mapThumbnails = mapThumbnails;
        return this;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * Reads the {@code param:<name>} values of the request. They are text - the shipped reports take a year,
     * a week, an edition and a notice reference - and the templates print them as they are handed over, so
     * each value is HTML-escaped here: markup in one stays text in the document, and cannot name an image or
     * style sheet for the PDF renderer to load.
     */
    private MessagePrintParams readReportParams(HttpServletRequest request) {
        request.getParameterMap().entrySet().stream()
                .filter(e -> e.getKey().startsWith("param:"))
                .forEach(e -> {
                    String key = e.getKey().substring("param:".length());
                    if (e.getValue() != null && e.getValue().length == 1) {
                        params.put(key, escape(e.getValue()[0]));
                    } else if (e.getValue() != null && e.getValue().length > 0) {
                        params.put(key, Arrays.stream(e.getValue()).map(MessagePrintParams::escape).toArray(String[]::new));
                    }
                });
        return this;
    }

    /**
     * Escapes the markup characters only ({@code < > & " '}), so letters such as æ, ø and å stay as they
     * are for a template that works on the value rather than just printing it
     */
    private static String escape(String value) {
        return value == null ? null : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

}
