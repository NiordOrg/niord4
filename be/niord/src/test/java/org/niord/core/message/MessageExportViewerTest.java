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

import org.junit.jupiter.api.Test;
import org.niord.model.message.MessagePartType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page an unpacked message export opens with ({@code export-messages.html}, written into the archive as
 * {@code messages-preview.html}) labels each message part by its type in words: the message dictionary's English
 * text of the type ({@code msg.field.details} and the rest), the language the page writes its other labels in. FAILS
 * without the words: the page printed the type's code in lower case ("details", "time", "positions").
 */
class MessageExportViewerTest {

    /** One entry of the page's map of part labels: {@code DETAILS: 'Details'} */
    private static final Pattern ENTRY = Pattern.compile("(\\w+): '([^']*)'");

    private static String viewer() throws IOException {
        try (InputStream in = MessageExportViewerTest.class.getResourceAsStream("/export-messages.html")) {
            assertNotNull(in, "export-messages.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The page's map of part labels, by type */
    private static Map<String, String> partTypeTexts(String viewer) {
        Matcher block = Pattern.compile("const PART_TYPE_TEXTS = \\{([^}]*)}").matcher(viewer);
        assertTrue(block.find(), "the page holds the labels of the part types");
        Map<String, String> texts = new LinkedHashMap<>();
        Matcher entry = ENTRY.matcher(block.group(1));
        while (entry.find()) {
            texts.put(entry.group(1), entry.group(2));
        }
        return texts;
    }

    @Test
    void everyPartTypeIsLabelledWithTheMessageDictionarysEnglishText() throws IOException {
        Properties message = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("defaults/base/texts/en/message.properties")) {
            assertNotNull(in);
            message.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, String> expected = Arrays.stream(MessagePartType.values()).collect(Collectors.toMap(
                Enum::name, t -> message.getProperty("msg.field." + t.name().toLowerCase(Locale.ROOT)),
                (a, b) -> a, LinkedHashMap::new));

        assertEquals(expected, partTypeTexts(viewer()));
    }

    @Test
    void aPartsRowIsLabelledFromTheMapNotWithItsCode() throws IOException {
        String viewer = viewer();

        assertFalse(viewer.contains("(part.type || '').toLowerCase()"), "the row printed the type's code");
        assertTrue(viewer.contains("Object.hasOwn(PART_TYPE_TEXTS, part.type)"), "the row reads the map");
    }
}
