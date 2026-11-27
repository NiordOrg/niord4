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
package org.niord.core.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.CategoryService;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.message.MessageSearchParams.UserType;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.publication.PublicationResolver;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The criteria line on the front page of a printed message list: the names of the criteria and the values of the
 * closed sets are texts of the print's language, the areas, tags and the domain are named, and what the dictionary
 * lacks is printed as the base product's English name, or the value as it is stored. FAILS without the texts: the
 * line was the search's English description, with its enum names, ids and Java's list brackets.
 */
class MessageSearchCriteriaTextTest {

    private static final String ZONE = "Europe/Copenhagen";

    /** The Danish values a deployment's dictionary might hold; a key without one falls back to English */
    private static final Map<String, String> DANISH = Map.ofEntries(
            Map.entry("menu.filter.domain", "Domæne"),
            Map.entry("menu.filter.status", "Status"),
            Map.entry("menu.filter.type", "Type"),
            Map.entry("menu.filter.kind", "Slags"),
            Map.entry("menu.filter.area", "Område"),
            Map.entry("menu.filter.date", "Dato"),
            Map.entry("msg.status.DRAFT", "Kladde"),
            Map.entry("msg.status.PUBLISHED", "Publiceret"),
            Map.entry("msg.type.TEMPORARY_NOTICE", "Midl."),
            Map.entry("msg.kind.WARNING", "Advarsler"),
            Map.entry("msg.field.publish_date", "Publiceringsdato"),
            Map.entry("pdf.list.criteria.main_type", "Hovedtype"),
            Map.entry("pdf.nm", "Efterretninger for Søfarende"));

    private MessageSearchCriteriaText criteria;

    /** What the dictionary answers per dictionary, language and key */
    private final Map<String, String> dictionary = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        criteria = new MessageSearchCriteriaText();
        criteria.app = mock(NiordApp.class);
        when(criteria.app.getDefaultLanguage()).thenReturn("en");
        criteria.dictionaryService = mock(DictionaryService.class);
        when(criteria.dictionaryService.value(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            String lang = invocation.getArgument(1);
            String key = invocation.getArgument(2);
            String value = dictionary.get(name + "/" + lang + "/" + key);
            return value != null ? value : dictionary.get(name + "/en/" + key);
        });
        criteria.domainService = mock(DomainService.class);
        criteria.areaService = mock(AreaService.class);
        criteria.categoryService = mock(CategoryService.class);
        criteria.messageTagService = mock(MessageTagService.class);
        criteria.publicationResolver = mock(PublicationResolver.class);
        criteria.promulgationTypeService = mock(PromulgationTypeService.class);

        for (String name : List.of("web", "message", "pdf")) {
            baseTexts(name).forEach((key, value) -> dictionary.put(name + "/en/" + key, (String) value));
        }

        Domain domain = new Domain();
        domain.setDomainId("niord-nm");
        domain.setName("NM domain");
        when(criteria.domainService.getDomains(true, false)).thenReturn(List.of(domain));

        Area area = new Area();
        area.createDesc("en").setName("The Sound");
        area.createDesc("da").setName("Øresund");
        when(criteria.areaService.findByAreaId("7")).thenReturn(area);
    }

    /** The base product's English texts of the dictionary */
    private static Properties baseTexts(String name) throws IOException {
        Properties texts = new Properties();
        try (InputStream in = MessageSearchCriteriaTextTest.class.getClassLoader()
                .getResourceAsStream("defaults/base/texts/en/" + name + ".properties")) {
            assertNotNull(in, name);
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return texts;
    }

    private void danish() {
        DANISH.forEach((key, value) -> {
            String name = key.startsWith("menu.") ? "web" : key.startsWith("pdf.") ? "pdf" : "message";
            dictionary.put(name + "/da/" + key, value);
        });
    }

    /** What an editor's print of a filtered list searches for */
    private static MessageSearchParams editorsPrint() {
        MessageSearchParams params = new MessageSearchParams()
                .domain("niord-nm")
                .statuses(Set.of(Status.PUBLISHED, Status.DRAFT))
                .types(Set.of(Type.PRELIMINARY_NOTICE, Type.TEMPORARY_NOTICE))
                .mainTypes(Set.of(MainType.NM))
                .kinds(MessageKind.WARNING)
                .seriesIds(Set.of("dma-nm"))
                .areaIds(Set.of("7"));
        params.from(Date.from(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.of(ZONE)).toInstant()));
        params.to(Date.from(ZonedDateTime.of(2026, 11, 30, 23, 59, 59, 0, ZoneId.of(ZONE)).toInstant()));
        params.dateType(DateType.PUBLISH_DATE);
        return params;
    }

    @Test
    void anEnglishPrintNamesItsCriteriaInWords() {
        assertEquals("Domain: NM domain; Date: Publish date, 1 November 2026 - 30 November 2026; "
                        + "Status: Draft, Published; Type: Temp., Prelim.; Main type: Notices to Mariners; "
                        + "Kind: Warnings; Message Series: dma-nm; Area: The Sound",
                criteria.describe(editorsPrint(), "en", ZONE));
    }

    @Test
    void aDanishPrintNamesItsCriteriaInDanishAndFallsBackToTheDefaultLanguage() {
        danish();

        // "Prelim." has no Danish value in this dictionary, so it is the default language's
        assertEquals("Domæne: NM domain; Dato: Publiceringsdato, 1 november 2026 - 30 november 2026; "
                        + "Status: Kladde, Publiceret; Type: Midl., Prelim.; Hovedtype: Efterretninger for Søfarende; "
                        + "Slags: Advarsler; Message Series: dma-nm; Område: Øresund",
                criteria.describe(editorsPrint(), "da", ZONE));
    }

    @Test
    void withoutTheTextsTheNamesAreEnglishAndTheValuesAsStored() {
        dictionary.clear();
        MessageSearchParams params = new MessageSearchParams()
                .statuses(Set.of(Status.PUBLISHED))
                .kinds(MessageKind.BULLETIN)
                .areaIds(Set.of("no-such-area"));

        assertEquals("Status: PUBLISHED; Kind: BULLETIN; Area: no-such-area", criteria.describe(params, "da", ZONE));
    }

    @Test
    void aPrintOfTheSelectionNamesTheSelectionNotItsTemporaryTag() {
        MessageTag temporary = new MessageTag();
        temporary.setTagId("0b5c6f1e-4a7d-4f53-9a43-3c1d3f8e2a10");
        temporary.setType(MessageTagType.TEMP);
        when(criteria.messageTagService.findTag(temporary.getTagId())).thenReturn(temporary);
        MessageTag named = new MessageTag();
        named.setTagId("tag-1");
        named.setType(MessageTagType.DOMAIN);
        named.setName("Firing exercises");
        when(criteria.messageTagService.findTag("tag-1")).thenReturn(named);

        assertEquals("Tag: Selected messages",
                criteria.describe(new MessageSearchParams().tags(Set.of(temporary.getTagId())), "en", ZONE));
        assertEquals("Tag: Firing exercises",
                criteria.describe(new MessageSearchParams().tags(Set.of("tag-1")), "en", ZONE));
    }

    @Test
    void theUserAndTheCommentsCriteriaAreTexts() {
        MessageSearchParams params = new MessageSearchParams()
                .username("editor")
                .userType(UserType.LAST_UPDATED_BY)
                .commentsType(MessageSearchParams.CommentsType.ANY_UNACK);

        assertEquals("User: Last updated by editor; Comments: Any unacknowledged",
                criteria.describe(params, "en", ZONE));
    }

    @Test
    void aSearchWithoutCriteriaHasAnEmptyLine() {
        assertEquals("", criteria.describe(new MessageSearchParams(), "en", ZONE));
    }

    /** A name's built-in word is the base product's English value, so a dictionary without the key reads the same */
    @Test
    void theBuiltInNamesAreTheBaseProductsEnglishValues() throws IOException {
        for (MessageSearchCriteriaText.Name name : MessageSearchCriteriaText.Name.values()) {
            assertEquals(baseTexts(name.text.dictionary()).getProperty(name.text.key()), name.builtIn, name.name());
        }
    }
}
