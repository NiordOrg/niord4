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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.DomainService;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessageVo;
import org.niord.web.map.MapImageCredit;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The zone a document is dated in, set on the template builder.
 *
 * <p>The instant under test is 23:30 UTC on 5 March 2026: already 6 March in Copenhagen (UTC+1) and still
 * 5 March in Nuuk (UTC-2). Each case renders it in both zones, so a rendering that followed the JVM's own
 * zone instead would print the same day twice and fail one of the two, whatever zone the build runs in.
 *
 * <p>The builder is real and so is the template loading, from rows a stand-in database answers with: the base
 * product's templates read from {@code bootstrap/templates/}, and the test's own; the application settings and
 * the dictionary are stood in for, the dictionary answering every key with a fixed English text.
 */
class FmTemplateBuilderTimeZoneTest {

    /** 23:30 UTC, 5 March 2026 */
    private static final Date NEAR_MIDNIGHT = Date.from(Instant.parse("2026-03-05T23:30:00Z"));

    private static final String DATE_TEMPLATE = "templates/it/time-zone-test.ftl";

    private ScriptResourceService resources;

    /** The rows the template loader reads: the base product's templates, and the test's own */
    private Map<String, ScriptResource> rows;
    private FmTemplateService templateService;

    /** The dictionary: the date texts the message templates use, and the key itself for the rest */
    private static class Texts extends ResourceBundle {
        private static final Map<String, String> TEXTS = Map.of(
                "msg.field.published", "Published",
                "msg.time.date_format", "d MMMM yyyy",
                "msg.time.date_time_format", "d MMMM yyyy, 'hours' HH:mm");

        @Override
        protected Object handleGetObject(String key) {
            return TEXTS.getOrDefault(key, key);
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.enumeration(TEXTS.keySet());
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

        templateService = new FmTemplateService(dictionaryService, mock(DomainService.class), resources, app, new MapImageCredit());
    }

    /** A template that prints the date it is handed and the zone value it was given */
    private void dateTemplate(String content) {
        rows.put(DATE_TEMPLATE, BootstrapTemplates.row(DATE_TEMPLATE, content));
    }

    private String renderDate(String timeZone) throws Exception {
        return templateService.newFmTemplateBuilder()
                .templatePath(DATE_TEMPLATE)
                .data("published", NEAR_MIDNIGHT)
                .timeZone(timeZone)
                .language("en")
                .process();
    }

    @Test
    void theDateFollowsTheZoneTheBuilderWasGiven() throws Exception {
        dateTemplate("${published?string('d MMMM yyyy HH:mm')}|${timeZone}");

        assertEquals("6 March 2026 00:30|Europe/Copenhagen", renderDate("Europe/Copenhagen"));
        assertEquals("5 March 2026 21:30|America/Nuuk", renderDate("America/Nuuk"));
    }

    @Test
    void aTemplateThatSetsItsOwnZoneKeepsIt() throws Exception {
        dateTemplate("<#setting time_zone='UTC'>${published?string('d MMMM yyyy HH:mm')}");

        assertEquals("5 March 2026 23:30", renderDate("Europe/Copenhagen"));
    }

    @Test
    void aBlankZoneChangesNothing() {
        FmTemplateService.FmTemplateBuilder builder = templateService.newFmTemplateBuilder().timeZone(" ");

        assertNull(builder.getTimeZone());
        assertTrue(builder.getData() == null || !builder.getData().containsKey(FmTemplateService.TIME_ZONE_PROPERTY));
    }

    // ------------------------------------------------------------------ the shipped list template

    private static MessageVo publishedNearMidnight() {
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("Buoy off station");
        MessageVo message = new MessageVo();
        message.setId("m1");
        message.setDescs(new java.util.ArrayList<>(List.of(desc)));
        message.setPublishDateFrom(NEAR_MIDNIGHT);
        return message;
    }

    private String renderList(String timeZone, boolean frontPage) throws Exception {
        return templateService.newFmTemplateBuilder()
                .templatePath("/templates/messages/message-list-pdf.ftl")
                .data("messages", List.of(publishedNearMidnight()))
                .data("areaHeadings", false)
                .data("searchCriteria", "Statuses: [PUBLISHED]")
                .data("pageSize", "A4")
                .data("pageOrientation", "portrait")
                .data("mapThumbnails", false)
                .data("frontPage", frontPage)
                .timeZone(timeZone)
                .dictionaryNames("web", "message", "pdf")
                .language("en")
                .process();
    }

    @Test
    void theListTemplatePrintsThePublishingDayOfTheZoneItWasGiven() throws Exception {
        String copenhagen = renderList("Europe/Copenhagen", false);
        String nuuk = renderList("America/Nuuk", false);

        assertTrue(copenhagen.contains("Published 6 March 2026"), copenhagen);
        assertTrue(nuuk.contains("Published 5 March 2026"), nuuk);
    }

    @Test
    void withoutItsFrontPageTheListOpensWithTheMessages() throws Exception {
        // The public report is the standard template with frontPage false
        String standard = renderList("Europe/Copenhagen", true);
        String withoutFrontPage = renderList("Europe/Copenhagen", false);

        assertTrue(standard.contains("Criteria:") && standard.contains("Statuses: [PUBLISHED]"), standard);
        assertFalse(withoutFrontPage.contains("Criteria:"), withoutFrontPage);
        assertFalse(withoutFrontPage.contains("Statuses: [PUBLISHED]"), withoutFrontPage);
        assertFalse(withoutFrontPage.contains("Result:"), withoutFrontPage);
        assertTrue(withoutFrontPage.contains("Buoy off station"), withoutFrontPage);
    }

    /**
     * RENDER6. The list says when it was made by the language's date-and-time text ({@code msg.time.date_time_format},
     * the form the message PDFs print a time in), on its front page and in its page header, not in FreeMarker's
     * default "Nov 11, 2026, 11:28:28 AM". FAILS on the template that printed {@code .now?datetime}.
     */
    @Test
    void theListSaysWhenItWasMadeByTheDateAndTimeText() throws Exception {
        String standard = renderList("Europe/Copenhagen", true);

        String stamp = "\\d{1,2} \\p{L}+ \\d{4}, hours \\d{2}:\\d{2}";
        assertTrue(Pattern.compile("Generated: &nbsp;</th>\\s*<td>" + stamp + "</td>").matcher(standard).find(),
                standard);
        assertTrue(Pattern.compile("Generated " + stamp + "</span>").matcher(standard).find(), standard);
    }
}
