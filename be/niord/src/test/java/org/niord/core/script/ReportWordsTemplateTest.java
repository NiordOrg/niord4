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
package org.niord.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.core.domain.DomainService;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.web.map.MapImageCredit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The words the PDF reports printed in English whatever the report's language are texts of the {@code pdf}
 * dictionary: the edition's label of the six reports that print one ({@code pdf.edition}), and the message list's
 * "Generated" (its page header and front page), "Criteria", "Result" and its count of messages
 * ({@code pdf.list.generated}, {@code pdf.list.criteria}, {@code pdf.list.result}, {@code pdf.list.result_count}),
 * and a draft report's status, which is the {@code message} dictionary's {@code msg.status.*} in lower case (it was
 * the status's name in lower case, the same word in English).
 * A report in another language prints that language's value; a country that held a whole-file language variant of
 * a report for these words no longer needs it.
 *
 * <p>Rendered through the real builder and the base product's templates, as {@link MessageAttachmentTemplateTest}
 * does, with the dictionaries a report gets made the way {@code DictionaryService} makes them: the language's value,
 * else the default language's (English).
 */
class ReportWordsTemplateTest {

    /** The release folder before this change, whose templates still printed the words in English */
    private static final Path PREVIOUS_RELEASE =
            Path.of("bootstrap/releases/2026-11-23-description-default-language/script-resources.json");

    /** The dictionaries a report gets, as the base product ships them in English */
    private static final List<String> DICTIONARIES = List.of("web", "message", "pdf");

    /** The reports whose words became texts */
    private static final List<String> REPORTS = List.of("message-list-pdf", "fa-list-pdf", "nm-report-pdf",
            "nm-almanac-report-pdf", "nm-annex-report-pdf", "nm-tp-report-pdf", "nm-accumulated-report-pdf");

    /** The template the reports include, which prints a draft report's status */
    private static final String SUPPORT = "templates/messages/message-support.ftl";

    /** The six reports that print the edition's label */
    private static final List<String> EDITION_REPORTS = REPORTS.subList(1, REPORTS.size());

    /** A second language's values of the new texts: test data, in no language pack */
    private static final Map<String, String> DANISH = Map.of(
            "pdf.edition", "Udgave {0}",
            "pdf.list.generated", "Genereret",
            "pdf.list.criteria", "Kriterier",
            "pdf.list.result", "Resultat",
            "pdf.list.result_count", "{0} meddelelser");

    /** The keys this change added, which a deployment that has not got them yet lacks */
    private static final Set<String> NEW_KEYS = DANISH.keySet();

    /** A second language's value of the status a draft report prints: test data, in no language pack */
    private static final Map<String, String> DANISH_STATUS = Map.of("msg.status.PUBLISHED", "Publiceret");

    /** The English texts of the base product, by key */
    private static Map<String, String> englishTexts() throws IOException {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String dictionary : DICTIONARIES) {
            String file = "defaults/base/texts/en/" + dictionary + ".properties";
            try (InputStream in = ReportWordsTemplateTest.class.getClassLoader().getResourceAsStream(file);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                properties.stringPropertyNames().forEach(key -> texts.put(key, properties.getProperty(key)));
            }
        }
        return texts;
    }

    /**
     * The dictionary a deployment holds: every English text, the second language's values given, and the keys left
     * out missing in every language
     */
    private static DictionaryVo dictionary(Map<String, String> danish, Set<String> missing) throws IOException {
        DictionaryVo dictionary = new DictionaryVo();
        dictionary.setName("pdf");
        englishTexts().forEach((key, value) -> {
            if (missing.contains(key)) {
                return;
            }
            DictionaryEntryVo entry = new DictionaryEntryVo();
            entry.setKey(key);
            entry.createDesc("en").setValue(value);
            if (danish.containsKey(key)) {
                entry.createDesc("da").setValue(danish.get(key));
            }
            dictionary.getEntries().put(key, entry);
        });
        return dictionary;
    }

    /** The dictionary as a resource bundle of the language, as {@code DictionaryService} makes it */
    private static ResourceBundle bundle(DictionaryVo dictionary, String language) {
        Properties values = dictionary.toProperties(language, "en");
        Locale locale = Locale.of(language);
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return values.getProperty(key);
            }

            @Override
            public Locale getLocale() {
                return locale;
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(values.stringPropertyNames());
            }
        };
    }

    /** A template service over the given template rows and dictionary */
    private static FmTemplateService templateService(Map<String, ScriptResource> rows, DictionaryVo dictionary) {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage(anyString())).thenAnswer(call -> call.getArgument(0));
        when(app.getDefaultLanguage()).thenReturn("en");
        // The languages a draft report prints each message in: here only the report's own
        when(app.getLanguages(anyString())).thenAnswer(call -> new String[] { call.getArgument(0) });
        when(app.getLocale(any())).thenAnswer(call -> Locale.of(call.<String>getArgument(0)));

        DictionaryService dictionaryService = mock(DictionaryService.class);
        when(dictionaryService.getDictionariesAsResourceBundle(any(), anyString()))
                .thenAnswer(call -> bundle(dictionary, call.getArgument(1)));

        ScriptResourceService resources = mock(ScriptResourceService.class);
        BootstrapTemplates.stub(resources, rows);
        return new FmTemplateService(dictionaryService, mock(DomainService.class), resources, app,
                new MapImageCredit());
    }

    /** The base product's templates as they are */
    private static Map<String, ScriptResource> currentTemplates() {
        return BootstrapTemplates.rows();
    }

    /** The base product's templates with the reports as the previous release folder ships them */
    private static Map<String, ScriptResource> previousTemplates() {
        Map<String, ScriptResource> rows = BootstrapTemplates.rows();
        for (ScriptResourceVo previous : ReleaseFilesGenerator.readScriptResources(PREVIOUS_RELEASE)) {
            if (previous.getPath().equals(SUPPORT)
                    || REPORTS.stream().anyMatch(report -> previous.getPath().equals(path(report)))) {
                rows.put(previous.getPath(), BootstrapTemplates.row(previous.getPath(), previous.getContent()));
            }
        }
        return rows;
    }

    private static String path(String report) {
        return "templates/messages/" + report + ".ftl";
    }

    /** What a report is handed: no messages, but the annex, which prints its first; an edition after the first */
    private static Map<String, Object> model(String report) {
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> messages = new ArrayList<>();
        if (report.equals("nm-annex-report-pdf")) {
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("lang", "en");
            desc.put("title", "An annex");
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", "uid-1");
            message.put("descs", List.of(desc));
            messages.add(message);
        }
        model.put("messages", messages);
        model.put("areaHeadings", false);
        model.put("searchCriteria", "Domain: test");
        model.put("edition", "2");
        model.put("ISSN", "1234-5678");
        model.put("week", "36");
        model.put("weeks", "36");
        model.put("year", "2026");
        model.put("yearNumber", 2026);
        return model;
    }

    private static String render(FmTemplateService templateService, String report, String language)
            throws Exception {
        return render(templateService, report, model(report), language);
    }

    private static String render(FmTemplateService templateService, String report, Map<String, Object> model,
                                 String language) throws Exception {
        return templateService.newFmTemplateBuilder()
                .templatePath(path(report))
                .data(model)
                .dictionaryNames(DICTIONARIES.toArray(String[]::new))
                .language(language)
                .process();
    }

    /** The draft report: the message list over one published message, each message in the report's language */
    private static String renderDraft(FmTemplateService templateService, String language) throws Exception {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("lang", language);
        desc.put("title", "A warning");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "uid-2");
        message.put("status", "PUBLISHED");
        message.put("mainType", "NW");
        message.put("type", "COASTAL_WARNING");
        message.put("descs", List.of(desc));

        Map<String, Object> model = model("message-list-pdf");
        model.put("messages", List.of(message));
        model.put("draft", true);
        model.put("frontPage", false);
        model.put("mapThumbnails", false);
        return render(templateService, "message-list-pdf", model, language);
    }

    /** The status line of a draft report, its label and its value with the white space between them made single */
    private static String statusLine(String html) {
        int at = html.indexOf("<td class=\"field-name\">Status</td>");
        assertTrue(at >= 0, () -> "no status line:\n" + html);
        return html.substring(at, html.indexOf("</tr>", at)).replaceAll("\\s+", " ");
    }

    /**
     * An English report is byte for byte what the previous templates printed. Rendered before, after and before
     * again, since the message list prints the minute it was made: one of the two renders of the previous
     * templates is of the same minute as the current one.
     */
    @ParameterizedTest
    @ValueSource(strings = { "message-list-pdf", "fa-list-pdf", "nm-report-pdf", "nm-almanac-report-pdf",
            "nm-annex-report-pdf", "nm-tp-report-pdf", "nm-accumulated-report-pdf" })
    void anEnglishReportIsPrintedAsBefore(String report) throws Exception {
        DictionaryVo dictionary = dictionary(Map.of(), Set.of());
        FmTemplateService previous = templateService(previousTemplates(), dictionary);
        FmTemplateService current = templateService(currentTemplates(), dictionary);

        String before = render(previous, report, "en");
        String now = render(current, report, "en");
        String after = render(previous, report, "en");

        assertTrue(now.equals(before) || now.equals(after),
                () -> report + " prints English other than before:\n" + now + "\n---- before:\n" + before);
        String word = report.equals("message-list-pdf") ? ">Generated: &nbsp;</th>" : "Version 2";
        assertTrue(before.contains(word),
                () -> report + ": the previous template does not print " + word + ":\n" + before);
    }

    /** An English draft report prints the status as before, the status's name in lower case */
    @Test
    void anEnglishDraftPrintsTheStatusAsBefore() throws Exception {
        DictionaryVo dictionary = dictionary(Map.of(), Set.of());
        FmTemplateService previous = templateService(previousTemplates(), dictionary);
        FmTemplateService current = templateService(currentTemplates(), dictionary);

        String before = renderDraft(previous, "en");
        String now = renderDraft(current, "en");
        String after = renderDraft(previous, "en");

        assertTrue(now.equals(before) || now.equals(after),
                () -> "the draft prints English other than before:\n" + now + "\n---- before:\n" + before);
        assertTrue(statusLine(before).contains("<td class=\"field-value\"> published </td>"), before);
    }

    /**
     * A draft report in a second language prints the status in that language, in lower case. FAILS on the template
     * that wrote the status's name.
     */
    @Test
    void aDraftPrintsTheStatusInTheReportsLanguage() throws Exception {
        Map<String, String> danish = new LinkedHashMap<>(DANISH);
        danish.putAll(DANISH_STATUS);
        String html = renderDraft(templateService(currentTemplates(), dictionary(danish, Set.of())), "da");

        String line = statusLine(html);
        assertTrue(line.contains("<td class=\"field-value\"> publiceret </td>"), line);
        assertFalse(line.contains("published"), line);
    }

    /** A deployment whose dictionary lacks the status's text prints the status's name in lower case, as before */
    @Test
    void aDraftWithoutTheStatusTextPrintsTheStatusName() throws Exception {
        String html = renderDraft(templateService(currentTemplates(),
                dictionary(Map.of(), DANISH_STATUS.keySet())), "da");

        assertTrue(statusLine(html).contains("<td class=\"field-value\"> published </td>"), html);
    }

    /** The edition's label in a second language. FAILS on the templates that wrote "Version". */
    @Test
    void theEditionIsLabelledInTheReportsLanguage() throws Exception {
        FmTemplateService templateService = templateService(currentTemplates(), dictionary(DANISH, Set.of()));
        for (String report : EDITION_REPORTS) {
            String html = render(templateService, report, "da");
            assertTrue(html.contains("Udgave 2"), () -> report + " does not label the edition in Danish:\n" + html);
            assertFalse(html.contains("Version"), () -> report + " still prints the English label:\n" + html);
        }
    }

    /**
     * The message list's page header and front page in a second language. FAILS on the template that wrote
     * "Generated", "Criteria", "Result" and "messages".
     */
    @Test
    void theMessageListsWordsAreInTheReportsLanguage() throws Exception {
        String html = render(templateService(currentTemplates(), dictionary(DANISH, Set.of())),
                "message-list-pdf", "da");

        assertTrue(html.contains("<span style=\"float: left\">Genereret "), html);
        assertTrue(html.contains(">Genereret: &nbsp;</th>"), html);
        assertTrue(html.contains(">Kriterier: &nbsp;</th>"), html);
        assertTrue(html.contains(">Resultat: &nbsp;</th>"), html);
        assertTrue(html.contains("<td>0 meddelelser</td>"), html);
        for (String english : List.of("Generated", "Criteria", ">Result:", "messages<")) {
            assertFalse(html.contains(english), () -> "the Danish list still prints " + english + ":\n" + html);
        }
    }

    /** A language with no value of its own takes the default language's, as every text does */
    @Test
    void aLanguageWithoutAValuePrintsTheDefaultLanguages() throws Exception {
        FmTemplateService templateService = templateService(currentTemplates(), dictionary(DANISH, Set.of()));
        for (String report : EDITION_REPORTS) {
            assertTrue(render(templateService, report, "kl").contains("Version 2"), report);
        }
        String list = render(templateService, "message-list-pdf", "kl");
        assertTrue(list.contains(">Generated: &nbsp;</th>") && list.contains("<td>0 messages</td>"), list);
    }

    /**
     * A deployment without the new texts (imported the templates before the backend that adds them, or deleted a
     * key) prints the English words and the report renders, as an English report prints them.
     */
    @Test
    void aDeploymentWithoutTheTextsPrintsTheEnglishWords() throws Exception {
        DictionaryVo withoutTheTexts = dictionary(Map.of(), NEW_KEYS);
        FmTemplateService current = templateService(currentTemplates(), withoutTheTexts);
        FmTemplateService withTexts = templateService(currentTemplates(), dictionary(Map.of(), Set.of()));
        for (String report : REPORTS) {
            String before = render(withTexts, report, "da");
            String now = render(current, report, "da");
            String after = render(withTexts, report, "da");
            assertTrue(now.equals(before) || now.equals(after), () -> report + ":\n" + now + "\n----\n" + before);
        }
    }
}
