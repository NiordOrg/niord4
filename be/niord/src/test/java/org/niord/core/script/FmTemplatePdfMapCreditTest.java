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
package org.niord.core.script;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.DomainService;
import org.niord.core.script.FmTemplateService.ProcessFormat;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessageVo;
import org.niord.web.map.MapImageCredit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The map credit on the PDFs made from a template.
 *
 * <p>The template is either the shipped message list or whatever the template loader hands over, as a
 * deployment's database copy would be: neither carries a credit of its own, so a credit in the PDF comes from
 * the rendering. No image is fetched: without a base URI the renderer loads nothing, and the credit follows
 * the image's element, not its pixels.
 */
class FmTemplatePdfMapCreditTest {

    private static final String TEMPLATE = "templates/it/map-credit-test.ftl";

    private static final String CREDIT = "© OpenStreetMap contributors";

    private ScriptResourceService resources;

    /** The rows the template loader reads: the base product's templates, and the test's own */
    private Map<String, ScriptResource> rows;
    private FmTemplateService templateService;

    /** The dictionary: the date formats the list template uses, and the key itself for the rest */
    private static class Texts extends ResourceBundle {
        private static final Map<String, String> FORMATS = Map.of(
                "msg.time.date_format", "d MMMM yyyy",
                "msg.time.date_time_format", "d MMMM yyyy, 'hours' HH:mm");

        @Override
        protected Object handleGetObject(String key) {
            return FORMATS.getOrDefault(key, key);
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.enumeration(FORMATS.keySet());
        }
    }

    @BeforeEach
    void setUp() {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage(anyString())).thenReturn("en");
        when(app.getLanguages(anyString())).thenReturn(new String[] { "en" });
        when(app.getLocale(any())).thenReturn(Locale.ENGLISH);

        DictionaryService dictionaryService = mock(DictionaryService.class);
        when(dictionaryService.getDictionariesAsResourceBundle(any(), anyString())).thenReturn(new Texts());

        resources = mock(ScriptResourceService.class);
        rows = BootstrapTemplates.rows();
        BootstrapTemplates.stub(resources, rows);

        templateService = new FmTemplateService(dictionaryService, mock(DomainService.class), resources, app,
                new MapImageCredit());
    }

    /** The template the loader hands over */
    private void template(String content) {
        rows.put(TEMPLATE, BootstrapTemplates.row(TEMPLATE, content));
    }

    private byte[] process(ProcessFormat format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .data("uid", "m1")
                .process(format, out);
        return out.toByteArray();
    }

    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            assertEquals(1, reader.getNumberOfPages());
            return PdfTextExtractor.getTextFromPage(reader, 1);
        } finally {
            reader.close();
        }
    }

    @Test
    void aPdfShowingAMapImageCreditsOpenStreetMap() throws Exception {
        template("<html><body><p>Buoy off station</p><img src=\"/rest/message-map-image/${uid}.png\"/></body></html>");

        String text = pdfText(process(ProcessFormat.PDF));

        assertTrue(text.contains("Buoy off station"), text);
        assertTrue(text.contains(CREDIT), text);
    }

    @Test
    void aPdfWithoutAMapImageHasNoCredit() throws Exception {
        template("<html><body><p>Buoy off station</p><img src=\"/img/company-logo.png\"/></body></html>");

        String text = pdfText(process(ProcessFormat.PDF));

        assertTrue(text.contains("Buoy off station"), text);
        assertFalse(text.contains("OpenStreetMap"), text);
    }

    // ------------------------------------------------------------------ the shipped list template

    /** The pages of the message list PDF of one message, with or without map thumbnails */
    private List<String> listPdfPages(boolean mapThumbnails) throws Exception {
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("Buoy off station");
        MessageVo message = new MessageVo();
        message.setId("m1");
        message.setDescs(new ArrayList<>(List.of(desc)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        templateService.newFmTemplateBuilder()
                .templatePath("/templates/messages/message-list-pdf.ftl")
                .data("messages", List.of(message))
                .data("areaHeadings", false)
                .data("searchCriteria", "")
                .data("pageSize", "A4")
                .data("pageOrientation", "portrait")
                .data("mapThumbnails", mapThumbnails)
                .data("frontPage", false)
                .timeZone("UTC")
                .dictionaryNames("web", "message", "pdf")
                .language("en")
                .process(ProcessFormat.PDF, out);

        PdfReader reader = new PdfReader(out.toByteArray());
        try {
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(PdfTextExtractor.getTextFromPage(reader, page));
            }
            return pages;
        } finally {
            reader.close();
        }
    }

    @Test
    void theListWithMapThumbnailsCreditsOpenStreetMapAndTheListWithoutDoesNot() throws Exception {
        List<String> withThumbnails = listPdfPages(true);
        List<String> withoutThumbnails = listPdfPages(false);

        assertFalse(withThumbnails.isEmpty());
        for (String page : withThumbnails) {
            assertTrue(page.contains(CREDIT), page);
        }
        assertFalse(withoutThumbnails.isEmpty());
        for (String page : withoutThumbnails) {
            assertTrue(page.contains("Buoy off station"), page);
            assertFalse(page.contains("OpenStreetMap"), page);
        }
    }

    @Test
    void theDebugHtmlIsTheTemplateOutputWithoutTheCredit() throws Exception {
        template("<html><body><img src=\"/rest/message-map-image/${uid}.png\"/></body></html>");

        String html = new String(process(ProcessFormat.TEXT), StandardCharsets.UTF_8);

        assertEquals("<html><body><img src=\"/rest/message-map-image/m1.png\"/></body></html>", html);
    }
}
