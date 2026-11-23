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

package org.niord.core.defaults;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The base product's default of {@code menu.action.tag.remove_named} is a text the administration app can show. The
 * app uses the key as the heading of its "Remove from…" submenu, the list of tags a message is in, and passes it no
 * parameter; it takes a Dictionary value over its own only when the value has exactly the placeholders of its own
 * text (mergeDictionaryOverrides). The default was {@code Remove from "{{tag}}"}, from the old application, which named
 * one tag with the key: the app refused it, and refused every edit of it that kept the {@code {{tag}}}, so the
 * Dictionary's value was never shown. FAILS on that value.
 */
class RemoveFromTagTextTest {

    private static final String WEB_TEXTS = "defaults/base/texts/en/web.properties";

    private static final String KEY = "menu.action.tag.remove_named";

    private static Properties englishWebTexts() throws IOException {
        Properties texts = new Properties();
        try (InputStream in = RemoveFromTagTextTest.class.getClassLoader().getResourceAsStream(WEB_TEXTS)) {
            assertNotNull(in, WEB_TEXTS + " is on the class path");
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return texts;
    }

    @Test
    void theRemoveFromTagHeadingCarriesNoPlaceholder() throws IOException {
        String text = englishWebTexts().getProperty(KEY);
        assertNotNull(text, KEY + " is a base product text");
        assertFalse(text.contains("{{") || text.contains("}}"),
                KEY + " is a heading the administration app passes no parameter to: " + text);
        assertEquals("Remove from...", text);
    }
}
