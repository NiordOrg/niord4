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

package org.niord.core.category;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TemplateExecutionService#containsMarkup(Object)}, the guard that keeps markup out of the
 * template parameters (F-X139). The parameters arrive as the untyped tree Jackson reads from the request body:
 * lists, maps and scalars.
 */
class TemplateParamsMarkupGuardTest {

    /** A LIST parameter value as the editor echoes it back: a dictionary entry with per-language descs */
    private static Map<String, Object> listValue(String enValue) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("lang", "en");
        desc.put("value", enValue);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", "wreck_type.vessel");
        entry.put("descs", List.of(desc));
        return entry;
    }

    @Test
    void plainTextNumbersAndNullsAreAllowed() {
        Map<String, Object> params = new HashMap<>();
        params.put("wreck_depth", 12.5);
        params.put("wreck_visible", false);
        params.put("name", "Smith & Co, æøå – depth < 2 m, width > 3 m, 1<2");
        params.put("wreck_type", listValue("a fishing vessel"));
        params.put("missing", null);

        assertFalse(TemplateExecutionService.containsMarkup(List.of(params)));
        assertFalse(TemplateExecutionService.containsMarkup(null));
        assertFalse(TemplateExecutionService.containsMarkup(new ArrayList<>()));
        assertFalse(TemplateExecutionService.containsMarkup(42));
    }

    @Test
    void aTagStartAnywhereInTheTreeIsFound() {
        for (String text : List.of("<b>bold</b>", "a</field-template>", "<!-- c -->", "<?xml?>", "x<img src=y>")) {
            Map<String, Object> params = new HashMap<>();
            params.put("name", text);
            assertTrue(TemplateExecutionService.containsMarkup(List.of(params)), "top-level: " + text);

            Map<String, Object> nested = new HashMap<>();
            nested.put("wreck_type", listValue(text));
            assertTrue(TemplateExecutionService.containsMarkup(List.of(nested)), "list-value desc: " + text);

            Map<String, Object> inList = new HashMap<>();
            inList.put("markings", List.of(Map.of("type", "buoy"), Map.of("name", List.of("ok", text))));
            assertTrue(TemplateExecutionService.containsMarkup(List.of(inList)), "nested list: " + text);
        }
    }

    @Test
    void mapKeysAndArraysAreWalkedToo() {
        Map<String, Object> keyed = new HashMap<>();
        keyed.put("<b>", "value");
        assertTrue(TemplateExecutionService.containsMarkup(keyed));

        assertTrue(TemplateExecutionService.containsMarkup(new Object[] { "ok", new String[] { "<i>" } }));
        assertFalse(TemplateExecutionService.containsMarkup(Arrays.asList("ok", null, 1)));
    }
}
