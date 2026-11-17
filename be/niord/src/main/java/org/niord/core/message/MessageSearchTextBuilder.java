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
import org.niord.core.area.Area;
import org.niord.core.area.AreaDesc;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryDesc;
import org.niord.core.util.TextUtils;

import java.util.function.Consumer;

/**
 * Assembles the per-language free-text searchable representation of a {@link Message}.
 * <p>
 * This is the single source of truth for <em>what text is searchable</em> for a message in a given
 * language: the UID, short-id, number, reference ids/descriptions, the area and category
 * <strong>parent lineage</strong> names, chart numbers, horizontal datum, the localized title /
 * vicinity / publication / source, every message-part subject and (HTML-stripped) details, and the
 * attachment captions.
 * <p>
 * Both free-text search backends consume this walk so they index identical text:
 * <ul>
 *   <li>{@link MessageLuceneIndex} adds each visited value as a separate tokenized Lucene field, and</li>
 *   <li>{@link MessagePostgresIndex} concatenates the values into one string and feeds it to
 *       {@code to_tsvector}.</li>
 * </ul>
 * The walk previously lived inline in {@code MessageLuceneIndex.addMessageToDocument}; it was extracted
 * here unchanged so the Lucene path keeps producing byte-identical fields.
 */
public final class MessageSearchTextBuilder {

    private MessageSearchTextBuilder() {
    }

    /**
     * Visits every searchable value of the message for the given language, in a fixed order, passing
     * each raw value (String, enum, number, ...) to the sink. The sink is responsible for null/blank
     * handling and stringification (mirroring {@code MessageLuceneIndex.addPhraseSearchField}).
     *
     * @param message  the message to walk
     * @param language the language whose localized descriptions to collect
     * @param sink     receives each searchable value
     */
    public static void forEachSearchValue(Message message, String language, Consumer<Object> sink) {

        sink.accept(message.getStatus());

        // UID
        sink.accept(message.getUid());

        // Message series identifier
        sink.accept(message.getShortId()); // e.g. "DK-074-14"
        if (message.getNumber() != null) {
            sink.accept(String.valueOf(message.getNumber()));
        }

        // References
        message.getReferences().forEach(ref -> {
            sink.accept(ref.getMessageId());
            ReferenceDesc desc = ref.getDesc(language);
            if (desc != null) {
                sink.accept(desc.getDescription());
            }
        });

        // Areas
        message.getAreas().forEach(a -> {
            for (Area area = a; area != null; area = area.getParent()) {
                AreaDesc desc = area.getDesc(language);
                if (desc != null) {
                    sink.accept(desc.getName());
                }
            }
        });

        // Category
        message.getCategories().forEach(category -> {
            for (Category cat = category; cat != null; cat = cat.getParent()) {
                CategoryDesc desc = cat.getDesc(language);
                if (desc != null) {
                    sink.accept(desc.getName());
                }
            }
        });

        // Charts
        message.getCharts().forEach(chart -> {
            sink.accept(chart.getChartNumber());
            sink.accept(chart.getInternationalNumber());
        });

        // Horizontal datum
        sink.accept(message.getHorizontalDatum());

        // Add language specific fields
        MessageDesc msgDesc = message.getDesc(language);
        if (msgDesc != null) {
            sink.accept(msgDesc.getTitle());
            sink.accept(msgDesc.getVicinity());
            sink.accept(TextUtils.html2txt(msgDesc.getPublication()));
            sink.accept(msgDesc.getSource());
        }

        // Add message parts
        message.getParts().stream()
                .flatMap(part -> part.getDescs().stream())
                .filter(desc -> language.equals(desc.getLang()))
                .forEach(desc -> {
                    sink.accept(desc.getSubject());
                    sink.accept(TextUtils.html2txt(desc.getDetails()));
                });

        // Attachments
        message.getAttachments().forEach(att -> {
            AttachmentDesc desc = att.getDesc(language);
            if (desc != null) {
                sink.accept(desc.getCaption());
            }
        });
    }

    /**
     * Builds the concatenated searchable text for the message in the given language: each non-blank value
     * (with HTML already stripped by {@link #forEachSearchValue}) joined by newlines.
     * <p>
     * The text is left raw &mdash; not lower-cased or accent-folded &mdash; because the PostgreSQL backend
     * feeds it to {@code to_tsvector}, whose text-search configuration handles tokenization, stemming, case
     * and stop words.
     *
     * @param message  the message
     * @param language the language
     * @return the searchable text (possibly empty, never {@code null})
     */
    public static String buildSearchText(Message message, String language) {
        StringBuilder sb = new StringBuilder();
        forEachSearchValue(message, language, value -> {
            if (value != null) {
                String str = (value instanceof String s) ? s : value.toString();
                if (StringUtils.isNotBlank(str)) {
                    sb.append(str).append('\n');
                }
            }
        });
        return sb.toString();
    }
}
