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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.DomainService;
import org.niord.web.map.MapImageCredit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The description {@code descForLang} picks for a language: the one in that language, else the one in the
 * deployment's default language, else the first. The base product has the function twice, with the same steps:
 * <ul>
 *     <li>{@code messages/message-support.ftl}, which every message print and mail that includes the file reads a
 *     message's, a part's and a reference's text through;</li>
 *     <li>{@code tmpl/common.ftl}, which every category template includes, and reads an area's, a message's, a
 *     part's, a template's and a list value's text through.</li>
 * </ul>
 * Without the second step a message with no text in the language asked for printed the first text it had, whatever
 * its language; a country with a language its editors do not always write in (Greenland) held its own copy of each
 * whole file only to add that step.
 *
 * <p>Rendered through the real builder and the base product's templates, as {@link MessageAttachmentTemplateTest}
 * does, with a one-line template that includes the file and calls the function.
 */
class DescForLangTemplateTest {

    private static final String TEMPLATE = "templates/it/desc-for-lang-test.ftl";

    /** The language of the description descForLang of the included file picks, or "none" */
    private static String picked(String include, String defaultLanguage, String asked, String... languages)
            throws Exception {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage(anyString())).thenAnswer(call -> call.getArgument(0));
        when(app.getDefaultLanguage()).thenReturn(defaultLanguage);
        when(app.getLocale(any())).thenReturn(Locale.ENGLISH);

        ScriptResourceService resources = mock(ScriptResourceService.class);
        Map<String, ScriptResource> rows = BootstrapTemplates.rows();
        rows.put(TEMPLATE, BootstrapTemplates.row(TEMPLATE,
                "<#include \"/" + include + "\"/>"
                        + "<#assign desc = descForLang(msg, asked)!>"
                        + "<#if desc?has_content>${desc.lang}<#else>none</#if>"));
        BootstrapTemplates.stub(resources, rows);

        FmTemplateService templateService = new FmTemplateService(mock(DictionaryService.class),
                mock(DomainService.class), resources, app, new MapImageCredit());

        List<Map<String, Object>> descs = new ArrayList<>();
        for (String language : languages) {
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("lang", language);
            desc.put("title", "The title in " + language);
            descs.add(desc);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("descs", descs);

        return templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .data("msg", msg)
                .data("asked", asked)
                // A category template is run with its parameters (TemplateExecutionService); common.ftl reads them
                .data("params", Map.of())
                .process()
                .strip();
    }

    /** FAILS on a template that goes from the language asked for straight to the first description ("en"). */
    @ParameterizedTest
    @ValueSource(strings = { "templates/messages/message-support.ftl", "templates/tmpl/common.ftl" })
    void aMissingLanguageFallsBackToTheDefaultLanguage(String include) throws Exception {
        assertEquals("da", picked(include, "da", "kl", "en", "da"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "templates/messages/message-support.ftl", "templates/tmpl/common.ftl" })
    void theLanguageAskedForComesFirst(String include) throws Exception {
        assertEquals("en", picked(include, "da", "en", "da", "en"));
        assertEquals("kl", picked(include, "da", "kl", "en", "da", "kl"));
    }

    /** A deployment whose texts are complete, or that lacks the default language's text too, prints as before */
    @ParameterizedTest
    @ValueSource(strings = { "templates/messages/message-support.ftl", "templates/tmpl/common.ftl" })
    void withoutTheDefaultLanguageTheFirstDescriptionIsTaken(String include) throws Exception {
        assertEquals("en", picked(include, "da", "kl", "en", "fr"));
        assertEquals("en", picked(include, null, "kl", "en", "da"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "templates/messages/message-support.ftl", "templates/tmpl/common.ftl" })
    void anEntityWithoutDescriptionsHasNone(String include) throws Exception {
        assertEquals("none", picked(include, "da", "kl"));
    }
}
