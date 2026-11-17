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
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link MessagePrintParams}.
 *
 * <p>Covers the fluent (method-chaining) getters/setters and their default values, the
 * {@code getFileNameHeader(...)} Content-Disposition construction (including ".pdf" suffix handling
 * and URL-encoding via {@code WebUtils.encodeURIComponent}), the {@code toString()} description, and
 * the {@code instantiate(HttpServletRequest)} factory that reads "default" parameter names plus
 * "param:"-prefixed report parameters from a servlet request.
 *
 * <p>These tests use only plain JUnit 4 and real domain objects. The single exception is the
 * {@link HttpServletRequest} input to {@code instantiate(...)}: it is a Mockito mock that stubs only
 * {@code getParameter}/{@code getParameterMap} against a backing {@code Map<String,String[]>}, which
 * keeps the tests free of Quarkus/Spring/servlet-container wiring. Mockito is framework-neutral, so the
 * tests remain portable across a future Quarkus-&gt;Spring migration.
 */
public class MessagePrintParamsTest {

    /**
     * Builds a minimal {@link HttpServletRequest} with Mockito. Only {@code getParameter(name)} (first
     * value) and {@code getParameterMap()} are stubbed against the given parameter map; every other
     * method returns the Mockito default.
     */
    private static HttpServletRequest requestWith(Map<String, String[]> paramMap) {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getParameterMap()).thenReturn(paramMap);
        Mockito.when(req.getParameter(Mockito.anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            String[] values = paramMap.get(name);
            return (values == null || values.length == 0) ? null : values[0];
        });
        return req;
    }

    // ------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------

    @Test
    public void freshInstanceHasDocumentedDefaults() {
        MessagePrintParams p = new MessagePrintParams();

        assertNullReport(p);
        assertEquals("A4", p.getPageSize());
        assertEquals("portrait", p.getPageOrientation());
        assertEquals(Boolean.FALSE, p.getMapThumbnails());
        assertEquals(Boolean.FALSE, p.getDebug());
        assertEquals(null, p.getFileName());
        assertNotNull(p.getParams(), "params map should be initialised");
        assertTrue(p.getParams().isEmpty(), "params map should be empty by default");
    }

    private static void assertNullReport(MessagePrintParams p) {
        assertEquals(null, p.getReport());
    }

    // ------------------------------------------------------------------
    // Method-chaining setters/getters
    // ------------------------------------------------------------------

    @Test
    public void settersRoundTripValues() {
        MessagePrintParams p = new MessagePrintParams()
                .report("std")
                .pageSize("A3")
                .pageOrientation("landscape")
                .mapThumbnails(Boolean.TRUE)
                .fileName("out.pdf")
                .debug(Boolean.TRUE);

        assertEquals("std", p.getReport());
        assertEquals("A3", p.getPageSize());
        assertEquals("landscape", p.getPageOrientation());
        assertEquals(Boolean.TRUE, p.getMapThumbnails());
        assertEquals("out.pdf", p.getFileName());
        assertEquals(Boolean.TRUE, p.getDebug());
    }

    @Test
    public void paramsMapIsMutableAndReflectsPuts() {
        MessagePrintParams p = new MessagePrintParams();
        p.getParams().put("foo", "bar");

        assertEquals("bar", p.getParams().get("foo"));
        assertEquals(1, p.getParams().size());
    }

    @Test
    public void eachSetterReturnsSameInstance() {
        MessagePrintParams p = new MessagePrintParams();

        assertSame(p, p.report("std"));
        assertSame(p, p.pageSize("A3"));
        assertSame(p, p.pageOrientation("landscape"));
        assertSame(p, p.mapThumbnails(Boolean.TRUE));
        assertSame(p, p.fileName("out.pdf"));
        assertSame(p, p.debug(Boolean.TRUE));
    }

    @Test
    public void settersAcceptNullValues() {
        MessagePrintParams p = new MessagePrintParams()
                .report(null)
                .pageSize(null)
                .pageOrientation(null)
                .fileName(null);

        assertEquals(null, p.getReport());
        assertEquals(null, p.getPageSize());
        assertEquals(null, p.getPageOrientation());
        assertEquals(null, p.getFileName());
    }

    // ------------------------------------------------------------------
    // getFileNameHeader(defaultName)
    // ------------------------------------------------------------------

    @Test
    public void fileNameHeaderUsesDefaultNameWhenFileNameUnset() {
        MessagePrintParams p = new MessagePrintParams();

        assertEquals("attachment; filename=\"report.pdf\"", p.getFileNameHeader("report"));
    }

    @Test
    public void fileNameHeaderUsesDefaultNameWhenFileNameBlank() {
        MessagePrintParams p = new MessagePrintParams().fileName("   ");

        // StringUtils.defaultIfBlank falls back to the default name for blank file names.
        assertEquals("attachment; filename=\"report.pdf\"", p.getFileNameHeader("report"));
    }

    @Test
    public void fileNameHeaderAppendsPdfSuffixWhenMissing() {
        MessagePrintParams p = new MessagePrintParams().fileName("invoice");

        assertEquals("attachment; filename=\"invoice.pdf\"", p.getFileNameHeader("ignored"));
    }

    @Test
    public void fileNameHeaderKeepsExistingPdfSuffixCaseInsensitive() {
        MessagePrintParams p = new MessagePrintParams().fileName("myfile.PDF");

        String header = p.getFileNameHeader("ignored");
        // No double ".pdf" should be appended; the original casing is preserved.
        assertTrue(header.contains("myfile.PDF"), "header should keep the original name: " + header);
        assertEquals("attachment; filename=\"myfile.PDF\"", header);
    }

    @Test
    public void fileNameHeaderUrlEncodesSpaces() {
        MessagePrintParams p = new MessagePrintParams().fileName("my report");

        // The space is encoded by WebUtils.encodeURIComponent (-> %20) and ".pdf" is appended.
        assertEquals("attachment; filename=\"my%20report.pdf\"", p.getFileNameHeader("ignored"));
    }

    // ------------------------------------------------------------------
    // toString()
    // ------------------------------------------------------------------

    @Test
    public void toStringOmitsBlankReportButKeepsPageSizeAndOrientation() {
        MessagePrintParams p = new MessagePrintParams();

        assertEquals("Page size: A4, Page orientation: portrait", p.toString());
    }

    @Test
    public void toStringIncludesReportWhenSet() {
        MessagePrintParams p = new MessagePrintParams()
                .report("standard")
                .pageSize("A3")
                .pageOrientation("landscape");

        assertEquals("Report: standard, Page size: A3, Page orientation: landscape", p.toString());
    }

    @Test
    public void toStringIsEmptyWhenAllFieldsBlank() {
        MessagePrintParams p = new MessagePrintParams()
                .report(null)
                .pageSize("")
                .pageOrientation("   ");

        assertEquals("", p.toString());
    }

    // ------------------------------------------------------------------
    // instantiate(HttpServletRequest)
    // ------------------------------------------------------------------

    @Test
    public void instantiateReadsDefaultParamsAndStripsParamPrefix() {
        Map<String, String[]> map = new HashMap<>();
        map.put("report", new String[] { "std" });
        map.put("pageSize", new String[] { "A3" });
        map.put("mapThumbnails", new String[] { "true" });
        map.put("debug", new String[] { "true" });
        map.put("fileName", new String[] { "out" });
        map.put("param:foo", new String[] { "bar" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertEquals("std", p.getReport());
        assertEquals("A3", p.getPageSize());
        // pageOrientation parameter absent -> default "portrait"
        assertEquals("portrait", p.getPageOrientation());
        assertEquals(Boolean.TRUE, p.getMapThumbnails());
        assertEquals(Boolean.TRUE, p.getDebug());
        assertEquals("out", p.getFileName());
        // "param:" prefix stripped; single-value param stored as the bare String.
        assertEquals("bar", p.getParams().get("foo"));
        assertFalse(p.getParams().containsKey("param:foo"), "prefixed key must not survive");
    }

    @Test
    public void instantiateAppliesDefaultsForAbsentParams() {
        Map<String, String[]> map = new HashMap<>();
        // Only a report parameter is supplied; everything else defaults.

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertEquals(null, p.getReport());
        assertEquals("A4", p.getPageSize());
        assertEquals("portrait", p.getPageOrientation());
        assertEquals(Boolean.FALSE, p.getMapThumbnails());
        assertEquals(Boolean.FALSE, p.getDebug());
        assertEquals(null, p.getFileName());
        assertTrue(p.getParams().isEmpty());
    }

    @Test
    public void instantiateStoresMultiValueParamAsStringArray() {
        Map<String, String[]> map = new HashMap<>();
        map.put("param:multi", new String[] { "a", "b" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        Object value = p.getParams().get("multi");
        assertTrue(value instanceof String[], "multi-value param should be stored as a String[]");
        assertArrayEquals(new String[] { "a", "b" }, (String[]) value);
    }

    @Test
    public void instantiateEscapesMarkupInASingleValueParam() {
        Map<String, String[]> map = new HashMap<>();
        map.put("param:searchCriteria", new String[] { "<img src=\"http://example.com/x.png\">AREA & 'CLOSED'" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertEquals("&lt;img src=&quot;http://example.com/x.png&quot;&gt;AREA &amp; &#39;CLOSED&#39;",
                p.getParams().get("searchCriteria"));
    }

    @Test
    public void instantiateEscapesEveryElementOfAMultiValueParam() {
        Map<String, String[]> map = new HashMap<>();
        map.put("param:multi", new String[] { "<b>a</b>", "plain", "<link rel=\"stylesheet\" href=\"x.css\">" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertArrayEquals(new String[] { "&lt;b&gt;a&lt;/b&gt;", "plain",
                        "&lt;link rel=&quot;stylesheet&quot; href=&quot;x.css&quot;&gt;" },
                (String[]) p.getParams().get("multi"));
    }

    @Test
    public void instantiateKeepsTheShippedReportParamsAsTheyAre() {
        // Every shipped report parameter is plain text, and escapes to itself
        Map<String, String[]> map = new HashMap<>();
        map.put("param:year", new String[] { "2026" });
        map.put("param:week", new String[] { "39" });
        map.put("param:edition", new String[] { "2" });
        map.put("param:NtM-ref", new String[] { "NtM 12/2026" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertEquals(Map.of("year", "2026", "week", "39", "edition", "2", "NtM-ref", "NtM 12/2026"), p.getParams());
    }

    @Test
    public void instantiateEscapesOnlyTheMarkupCharactersOfAFreeTextParam() {
        // A publication's heading and footnote are free Danish text; a template may work on them
        // (?upper_case, ?length), so the letters must not turn into entities such as &aelig;
        Map<String, String[]> map = new HashMap<>();
        map.put("param:overskrift", new String[] { "Aktive P&T - Færøerne, Østersøen og Ålborg" });
        map.put("param:multi", new String[] { "æøå", "ÆØÅ ü é" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertEquals("Aktive P&amp;T - Færøerne, Østersøen og Ålborg", p.getParams().get("overskrift"));
        assertArrayEquals(new String[] { "æøå", "ÆØÅ ü é" }, (String[]) p.getParams().get("multi"));
    }

    @Test
    public void instantiateIgnoresNonParamPrefixedEntries() {
        Map<String, String[]> map = new HashMap<>();
        map.put("report", new String[] { "std" });
        map.put("notAParam", new String[] { "ignored" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

        assertTrue(p.getParams().isEmpty(), "only param:-prefixed entries belong in params");
    }

    // ------------------------------------------------------------------
    // The page layout: written into the PDF's style sheet, so only keywords pass
    // ------------------------------------------------------------------

    @Test
    public void instantiateKeepsEveryKnownPageSizeAndOrientationAsSpelt() {
        for (String size : new String[] { "A3", "A4", "A5", "B4", "B5", "LETTER", "LEGAL", "LEDGER", "a3", " letter " }) {
            Map<String, String[]> map = new HashMap<>();
            map.put("pageSize", new String[] { size });
            assertEquals(size.trim(), MessagePrintParams.instantiate(requestWith(map)).getPageSize());
        }
        for (String orientation : new String[] { "portrait", "landscape", "PORTRAIT", "LANDSCAPE" }) {
            Map<String, String[]> map = new HashMap<>();
            map.put("pageOrientation", new String[] { orientation });
            assertEquals(orientation, MessagePrintParams.instantiate(requestWith(map)).getPageOrientation());
        }
    }

    @Test
    public void instantiateReplacesAnyOtherPageLayoutWithTheDefault() {
        for (String value : new String[] { "", "  ", "A0", "210mm 297mm", "A4;} body{display:none",
                "A4</style><script>alert(1)</script>" }) {
            Map<String, String[]> map = new HashMap<>();
            map.put("pageSize", new String[] { value });
            map.put("pageOrientation", new String[] { value });

            MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));

            assertEquals("A4", p.getPageSize(), "pageSize '" + value + "'");
            assertEquals("portrait", p.getPageOrientation(), "pageOrientation '" + value + "'");
        }
    }

    // ------------------------------------------------------------------
    // forAnonymousCaller()
    // ------------------------------------------------------------------

    @Test
    public void forAnonymousCallerDropsTheDebugSwitchTheMapThumbnailsAndEveryTemplateValue() {
        Map<String, String[]> map = new HashMap<>();
        map.put("report", new String[] { "public" });
        map.put("pageSize", new String[] { "A3" });
        map.put("pageOrientation", new String[] { "landscape" });
        map.put("mapThumbnails", new String[] { "true" });
        map.put("fileName", new String[] { "out" });
        map.put("debug", new String[] { "true" });
        map.put("param:searchCriteria", new String[] { "<h1>AREA CLOSED</h1>" });
        map.put("param:messages", new String[] { "x", "y" });

        MessagePrintParams p = MessagePrintParams.instantiate(requestWith(map));
        assertSame(p, p.forAnonymousCaller());

        assertEquals(Boolean.FALSE, p.getDebug());
        assertTrue(p.getParams().isEmpty(), "no param:<name> value reaches the template");
        // A thumbnail is a map image the renderer fetches from the backend for each message; the
        // public site never asks for them, so an anonymous request does not get them
        assertEquals(Boolean.FALSE, p.getMapThumbnails());
        // What an anonymous reader may choose stays as asked
        assertEquals("public", p.getReport());
        assertEquals("A3", p.getPageSize());
        assertEquals("landscape", p.getPageOrientation());
        assertEquals("out", p.getFileName());
    }
}
