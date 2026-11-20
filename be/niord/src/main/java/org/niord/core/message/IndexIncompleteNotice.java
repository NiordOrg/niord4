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

import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.dictionary.DictionaryService;
import org.niord.model.search.PagedSearchResultVo;
import org.springframework.stereotype.Service;

/**
 * The line a document made from a message search carries at its top when the search was answered while the free-text
 * index was still being filled after a start ({@link PagedSearchResultVo#getIndexIncomplete()}): the printed list
 * (the PDF) and a mailing-list report run by hand. The search still answers, and the document is still made; the
 * line says that messages may be missing from it, in the words the editors' list shows above such a result.
 * <p>
 * The text is the {@code pdf} dictionary's {@value #TEXT_KEY}, in the document's language; without the key in any
 * language, the built-in English sentence.
 */
@Service
public class IndexIncompleteNotice {

    /** The key of the text in the {@code pdf} dictionary */
    public static final String TEXT_KEY = "pdf.list.index_incomplete";

    /** The text for a deployment whose dictionary lacks the key */
    static final String BUILT_IN_TEXT = "The search index is being rebuilt, for example after a restart of the server. "
            + "Until it is complete, messages may be missing from this result.";

    @Inject
    DictionaryService dictionaryService;

    /**
     * Returns the line for a document made from the given search result, or null when the result is not marked
     *
     * @param result   the search result the document is made from
     * @param language the language of the document
     * @return the text of the line, or null
     */
    public String forResult(PagedSearchResultVo<?> result, String language) {
        return result != null && Boolean.TRUE.equals(result.getIndexIncomplete()) ? text(language) : null;
    }

    /**
     * Returns the text of the line in the given language: the dictionary's (which falls back to the default
     * language), else the built-in English sentence
     *
     * @param language the language of the document
     * @return the text of the line
     */
    String text(String language) {
        String text = dictionaryService.value("pdf", language, TEXT_KEY);
        return StringUtils.isBlank(text) ? BUILT_IN_TEXT : text;
    }
}
