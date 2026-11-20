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
import org.niord.core.dictionary.DictionaryService;
import org.niord.model.search.PagedSearchResultVo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The line a printed list and a mailing-list report carry when their search was answered while the free-text index
 * was being filled (LUCENE-7): only for a marked result, in the document's language, and the built-in English text
 * when the dictionary lacks the key.
 */
class IndexIncompleteNoticeTest {

    private IndexIncompleteNotice notice;

    @BeforeEach
    void setUp() {
        notice = new IndexIncompleteNotice();
        notice.dictionaryService = mock(DictionaryService.class);
    }

    private static PagedSearchResultVo<Message> result(boolean indexIncomplete) {
        PagedSearchResultVo<Message> result = new PagedSearchResultVo<>();
        result.setIndexIncomplete(indexIncomplete);
        return result;
    }

    @Test
    void aMarkedResultGetsTheDictionarysTextInTheDocumentsLanguage() {
        when(notice.dictionaryService.value("pdf", "da", IndexIncompleteNotice.TEXT_KEY)).thenReturn("Dansk tekst");

        assertEquals("Dansk tekst", notice.forResult(result(true), "da"));
    }

    @Test
    void anUnmarkedResultGetsNoLine() {
        when(notice.dictionaryService.value("pdf", "en", IndexIncompleteNotice.TEXT_KEY)).thenReturn("English text");

        assertNull(notice.forResult(result(false), "en"));
        assertNull(notice.forResult(null, "en"));
    }

    @Test
    void withoutTheKeyTheLineIsTheBuiltInEnglishText() {
        assertEquals(IndexIncompleteNotice.BUILT_IN_TEXT, notice.forResult(result(true), "en"));
    }

    /** The built-in text is the base product's English value, so a deployment without the key reads the same words */
    @Test
    void theBuiltInTextIsTheBaseProductsEnglishValue() throws Exception {
        Properties pdf = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("defaults/base/texts/en/pdf.properties")) {
            assertNotNull(in);
            pdf.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        assertEquals(IndexIncompleteNotice.BUILT_IN_TEXT, pdf.getProperty(IndexIncompleteNotice.TEXT_KEY));
    }
}
