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

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The message ids a message carries as text - its short id, and the message id of each of its references -
 * may not hold a markup character ({@code <}, {@code >}, {@code "} or {@code &}) when they come from outside:
 * from a request body or an imported file.
 * <p>
 * The mail and PDF templates print these ids into HTML unescaped, in element text and in attribute values.
 * Every short-id format in use - letters, digits, spaces, {@code -}, {@code /} and {@code .}, as in
 * {@code NM-001-24}, {@code A/12 2024} or {@code FA/EK-D-371 2017} - passes. Ids that Niord generates itself are
 * not checked here, and an id the stored message already has is left alone, so a message saved before the rule
 * stays editable.
 */
public final class MessageIdMarkup {

    /** The characters a message id from outside may not hold */
    private static final char[] MARKUP_CHARACTERS = { '<', '>', '"', '&' };

    private MessageIdMarkup() {
    }

    /**
     * Returns whether the id holds a markup character
     * @param id the id, or null
     * @return whether the id holds a markup character
     */
    public static boolean holdsMarkup(String id) {
        return StringUtils.containsAny(id, MARKUP_CHARACTERS);
    }

    /**
     * Returns why the message ids of a message about to be saved are refused, or null when they are not: when its
     * short id, or the message id of one of its references, holds a markup character and is not already an id of
     * the stored message.
     *
     * @param message the message about to be saved, as the request body or the imported file gives it
     * @param stored the stored message it updates, or null when it is a new message
     * @return why the ids are refused, or null
     */
    public static String refusal(Message message, Message stored) {
        String shortId = message.getShortId();
        if (holdsMarkup(shortId) && (stored == null || !shortId.equals(stored.getShortId()))) {
            return "A message short id cannot contain <, >, \" or &";
        }

        Set<String> storedReferenceIds = (stored == null)
                ? Set.of()
                : stored.getReferences().stream()
                        .map(Reference::getMessageId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        for (Reference reference : message.getReferences()) {
            String messageId = reference.getMessageId();
            if (holdsMarkup(messageId) && !storedReferenceIds.contains(messageId)) {
                return "A referenced message id cannot contain <, >, \" or &";
            }
        }
        return null;
    }
}
